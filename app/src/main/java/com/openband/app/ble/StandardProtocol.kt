package com.openband.app.ble

import android.bluetooth.BluetoothGattService
import com.openband.app.data.M
import java.util.UUID

/**
 * Bluetooth SIG standard profiles that many bands expose:
 * Heart Rate (0x180D), Battery (0x180F), Device Information (0x180A).
 */
class StandardProtocol : BandProtocol {
    override val name = "Standard GATT"

    companion object {
        val HR_MEASUREMENT: UUID = uuid16(0x2A37)
        val BATTERY_LEVEL: UUID = uuid16(0x2A19)
        val INFO = linkedMapOf(
            uuid16(0x2A29) to "Manufacturer",
            uuid16(0x2A24) to "Model",
            uuid16(0x2A25) to "Serial",
            uuid16(0x2A27) to "Hardware",
            uuid16(0x2A26) to "Firmware",
            uuid16(0x2A28) to "Software",
        )
    }

    private var lastStoredHr = 0L

    override fun matches(services: List<BluetoothGattService>) = true

    override suspend fun onReady(io: BandIo) {
        for ((uuid, label) in INFO) {
            if (!io.hasChar(uuid)) continue
            io.read(uuid)?.let { v ->
                val s = String(v, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                if (s.isNotEmpty()) io.setInfo(label, s)
            }
        }
        readBattery(io)
    }

    override suspend fun sync(io: BandIo) = readBattery(io)

    private suspend fun readBattery(io: BandIo) {
        if (!io.hasChar(BATTERY_LEVEL)) return
        io.read(BATTERY_LEVEL)?.takeIf { it.isNotEmpty() }?.let { io.setBattery(it.u8(0)) }
    }

    override fun onNotify(io: BandIo, char: UUID, value: ByteArray) {
        when (char) {
            HR_MEASUREMENT -> if (value.size >= 2) {
                val flags = value.u8(0)
                val bpm = if (flags and 0x01 != 0 && value.size >= 3) value.u16le(1) else value.u8(1)
                if (bpm in 25..250) {
                    io.setLiveHr(bpm)
                    val now = System.currentTimeMillis()
                    // store at most one sample per 10 s to keep the DB small
                    if (now - lastStoredHr >= 10_000) {
                        lastStoredHr = now
                        io.emit(M.HR, bpm.toDouble(), now)
                    }
                }
            }
            BATTERY_LEVEL -> if (value.isNotEmpty()) io.setBattery(value.u8(0))
        }
    }
}
