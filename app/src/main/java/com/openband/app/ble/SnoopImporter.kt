package com.openband.app.ble

import com.openband.app.data.RawPacket
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parses an Android Bluetooth HCI snoop log (btsnoop_hci.log, or a bug-report .zip containing it)
 * and extracts the ATT traffic between the phone and the band:
 *  - SNOOP_TX: write requests / commands the official app sent
 *  - SNOOP_RX: notifications / indications the band sent back
 */
object SnoopImporter {

    data class Result(val packets: List<RawPacket>, val connections: Int, val chosenAddress: String?, val writes: Int, val notifies: Int)

    private const val BTSNOOP_EPOCH_DELTA = 0x00dcddb30f2f8000L // microseconds from year 0 to 1970

    fun import(input: InputStream, isZip: Boolean, preferredAddress: String?): Result {
        val bytes = if (isZip) extractFromZip(input) ?: error("No btsnoop_hci.log inside the zip") else input.readBytes()
        return parse(bytes, preferredAddress)
    }

    private fun extractFromZip(input: InputStream): ByteArray? {
        var best: ByteArray? = null
        ZipInputStream(BufferedInputStream(input)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.lowercase()
                if (n.contains("btsnoop") && !e.isDirectory) {
                    val b = z.readBytes()
                    if (b.size > (best?.size ?: 0) && b.size > 16 && String(b, 0, 7, Charsets.US_ASCII) == "btsnoop") best = b
                }
            }
        }
        return best
    }

    private class Conn(val handle: Int) {
        var address: String? = null
        val packets = ArrayList<RawPacket>()
        var writes = 0
        val reassembly = ByteArrayOutputStream()
        var expected = 0
        var reassemblyDir = ""
        var reassemblyTs = 0L
    }

    fun parse(data: ByteArray, preferredAddress: String?): Result {
        val inp = DataInputStream(data.inputStream())
        val magic = ByteArray(8); inp.readFully(magic)
        require(String(magic, 0, 7, Charsets.US_ASCII) == "btsnoop") { "Not a btsnoop file" }
        inp.readInt() // version
        val datalink = inp.readInt() // 1002 = HCI UART (H4)
        val conns = HashMap<Int, Conn>()
        fun conn(h: Int) = conns.getOrPut(h) { Conn(h) }

        try {
            while (true) {
                inp.readInt() // original length
                val incl = inp.readInt()
                val flags = inp.readInt()
                inp.readInt() // drops
                val tsMicro = inp.readLong()
                val rec = ByteArray(incl); inp.readFully(rec)
                val ts = (tsMicro - BTSNOOP_EPOCH_DELTA) / 1000
                val received = flags and 1 == 1
                var off = 0
                var type: Int
                if (datalink == 1002) { if (rec.isEmpty()) continue; type = rec.u8(0); off = 1 }
                else type = if (flags and 2 != 0) (if (received) 4 else 1) else 2
                when (type) {
                    4 -> parseEvent(rec, off, ::conn)
                    2 -> parseAcl(rec, off, received, ts, ::conn)
                }
            }
        } catch (_: EOFException) {
        } catch (_: IndexOutOfBoundsException) {
        }

        val pref = preferredAddress?.uppercase()
        val chosen = conns.values.firstOrNull { pref != null && it.address == pref && it.packets.isNotEmpty() }
            ?: conns.values.filter { it.packets.isNotEmpty() }.maxByOrNull { it.writes * 10 + it.packets.size }
        val pk = chosen?.packets ?: emptyList()
        return Result(pk, conns.size, chosen?.address, pk.count { it.dir == "SNOOP_TX" }, pk.count { it.dir == "SNOOP_RX" })
    }

    private fun parseEvent(r: ByteArray, off: Int, conn: (Int) -> Conn) {
        if (r.size < off + 3) return
        val code = r.u8(off)
        if (code != 0x3E) return // LE meta event
        val sub = r.u8(off + 2)
        if (sub == 0x01 || sub == 0x0A) { // LE (enhanced) connection complete
            val p = off + 3
            if (r.size < p + 11) return
            if (r.u8(p) != 0) return
            val handle = r.u16le(p + 1) and 0x0fff
            val addr = (0 until 6).map { "%02X".format(r.u8(p + 5 + 5 - it)) }.joinToString(":")
            conn(handle).address = addr
        }
    }

    private fun parseAcl(r: ByteArray, off: Int, received: Boolean, ts: Long, conn: (Int) -> Conn) {
        if (r.size < off + 4) return
        val hf = r.u16le(off)
        val handle = hf and 0x0fff
        val pb = (hf shr 12) and 0x3
        val len = r.u16le(off + 2)
        val payloadStart = off + 4
        if (r.size < payloadStart + len) return
        val c = conn(handle)
        val dir = if (received) "SNOOP_RX" else "SNOOP_TX"
        if (pb == 0x1) { // continuation fragment
            if (c.expected == 0 || c.reassemblyDir != dir) return
            c.reassembly.write(r, payloadStart, len)
            if (c.reassembly.size() >= c.expected) {
                val l2 = c.reassembly.toByteArray()
                c.expected = 0
                handleL2cap(l2, 0, c, dir, c.reassemblyTs)
            }
            return
        }
        if (len < 4) return
        val l2len = r.u16le(payloadStart)
        if (l2len + 4 > len) { // start of fragmented packet
            c.reassembly.reset()
            c.reassembly.write(r, payloadStart, len)
            c.expected = l2len + 4
            c.reassemblyDir = dir
            c.reassemblyTs = ts
            return
        }
        handleL2cap(r.copyOfRange(payloadStart, payloadStart + len), 0, c, dir, ts)
    }

    private fun handleL2cap(l2: ByteArray, off: Int, c: Conn, dir: String, ts: Long) {
        if (l2.size < off + 5) return
        val l2len = l2.u16le(off)
        val cid = l2.u16le(off + 2)
        if (cid != 0x0004) return // ATT only
        val att = off + 4
        val end = minOf(l2.size, att + l2len)
        val opcode = l2.u8(att)
        val isWrite = opcode == 0x12 || opcode == 0x52
        val isNotify = opcode == 0x1B || opcode == 0x1D
        if (!isWrite && !isNotify) return
        if (end < att + 3) return
        // only phone->band writes and band->phone notifications are interesting
        if (isWrite && dir != "SNOOP_TX") return
        if (isNotify && dir != "SNOOP_RX") return
        val attHandle = l2.u16le(att + 1)
        val value = l2.copyOfRange(att + 3, end)
        // skip CCCD writes (enabling notifications), they are done automatically
        if (isWrite && value.size == 2 && (value.hex() == "0100" || value.hex() == "0200")) return
        if (isWrite) c.writes++
        c.packets += RawPacket(0, ts, dir, attHandle, "", value.hex())
    }
}
