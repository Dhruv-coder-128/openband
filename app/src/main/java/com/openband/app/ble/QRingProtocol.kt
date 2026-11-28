package com.openband.app.ble

import android.bluetooth.BluetoothGattService
import com.openband.app.data.M
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Driver for the Oudmon "QRing" protocol, used by the Pebble Qore 2 (official app: com.pebble.ring.app,
 * SDK com.oudmon.ble). Command numbers and packet layouts below were taken from that SDK's
 * `Constants`, `*Req`, `*Rsp` and `LargeDataHandler` classes.
 *
 * Two channels:
 *  - command channel (6e40fff0…): 16-byte packets [cmd, 14 data bytes, checksum = sum & 0xFF]
 *  - big-data channel (de5bf728…): [0xBC, action, lenLo, lenHi, crcLo, crcHi, payload…], CRC16/MODBUS
 */
class QRingProtocol : BandProtocol {
    override val name = "Qore / QRing"

    companion object {
        val SERVICE: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        val WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val BIG_SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        val BIG_WRITE: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        val BIG_NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

        // command channel (Constants.CMD_*)
        const val CMD_SET_TIME = 1
        const val CMD_BATTERY = 3
        const val CMD_HR_LOG = 21
        const val CMD_HR_SETTING = 22
        const val CMD_SPO2_SETTING = 44
        const val CMD_STRESS_SETTING = 54
        const val CMD_STRESS = 55
        const val CMD_HRV_SETTING = 56
        const val CMD_HRV = 57
        const val CMD_STEPS = 67
        const val CMD_LIVE_START = 105
        const val CMD_LIVE_STOP = 106
        const val CMD_DEVICE_NOTIFY = 115
        const val CMD_FIND_DEVICE = 80
        const val CMD_PUSH_MSG = 114

        // big-data channel (LargeDataHandler.ACTION_*)
        const val BIG_SLEEP = 39
        const val BIG_SPO2_DAILY = 42
        const val BIG_SPO2_INTERVAL = 95
        const val BIG_TEMPERATURE = 119
        const val BIG_TEMP_SERIES = 37
        const val BIG_TEMP_ONCE = 38

        const val HISTORY_DAYS = 7

        /** CRC16/MODBUS as in com.oudmon.ble.base.communication.utils.CRC16. */
        fun crc16(data: ByteArray): Int {
            if (data.isEmpty()) return 0xFFFF
            var crc = 0xFFFF
            for (b in data) {
                crc = crc xor (b.toInt() and 0xff)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
            }
            return crc and 0xFFFF
        }

        /** LargeDataHandler.addHeader */
        fun bigPacket(action: Int, payload: ByteArray?): ByteArray {
            val p = payload ?: ByteArray(0)
            val out = ByteArray(6 + p.size)
            out[0] = 0xBC.toByte()
            out[1] = action.toByte()
            if (p.isNotEmpty()) {
                out[2] = (p.size and 0xff).toByte(); out[3] = (p.size shr 8).toByte()
                val crc = crc16(p)
                out[4] = (crc and 0xff).toByte(); out[5] = (crc shr 8).toByte()
                System.arraycopy(p, 0, out, 6, p.size)
            } else {
                out[4] = 0xFF.toByte(); out[5] = 0xFF.toByte()
            }
            return out
        }
    }

    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val bigInbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var liveType = 0
    private var rt6n = 0
    /** command byte -> when we last sent it; used to spot replies to someone else's request. */
    private val sentAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val bigSentAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private fun recentlySent(map: Map<Int, Long>, k: Int) = System.currentTimeMillis() - (map[k] ?: 0L) < 10_000
    private val zone = ZoneId.systemDefault()

    override fun matches(services: List<BluetoothGattService>) = services.any { it.uuid == SERVICE }

    private fun packet(cmd: Int, vararg data: Int): ByteArray {
        val p = ByteArray(16)
        p[0] = cmd.toByte()
        data.forEachIndexed { i, b -> if (i < 14) p[i + 1] = b.toByte() }
        p[15] = (p.take(15).sumOf { it.toInt() and 0xff } and 0xff).toByte()
        return p
    }

    private fun bcd(v: Int) = ((v / 10) shl 4) or (v % 10)
    private fun unbcd(b: Int) = ((b shr 4) * 10) + (b and 0x0f)

    private fun drain() {
        while (inbox.tryReceive().isSuccess) { /* discard */ }
    }

    /** Sends a command and collects responses with the same command byte until [done] says stop. */
    private suspend fun request(io: BandIo, pkt: ByteArray, timeoutMs: Long = 4000, done: (ByteArray, Int) -> Boolean): List<ByteArray> {
        drain()
        sentAt[pkt.u8(0)] = System.currentTimeMillis()
        if (!io.write(WRITE, pkt)) return emptyList()
        val cmd = pkt[0]
        val out = ArrayList<ByteArray>()
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val p = inbox.receive()
                if (p.isEmpty() || p[0] != cmd) continue
                out += p
                if (done(p, out.size)) break
            }
        }
        return out
    }

    override fun onNotify(io: BandIo, char: UUID, value: ByteArray) {
        when (char) {
            NOTIFY -> {
                if (value.isEmpty()) return
                val cmd = value.u8(0)
                when {
                    cmd == CMD_DEVICE_NOTIFY -> io.bandEvent()
                    cmd == 47 || cmd == 135 -> {} // things the band pushes by itself - not another app
                    cmd == CMD_LIVE_START && liveType != 0 -> {}
                    !recentlySent(sentAt, cmd) -> io.foreignTraffic("cmd $cmd")
                }
                when (cmd) {
                    CMD_LIVE_START -> if (value.size >= 4 && value.u8(2) == 0) {
                        val type = value.u8(1)
                        val v = value.u8(3)
                        if (type == 6) { rt6n++; if (rt6n <= 4 || rt6n % 30 == 0) io.log("realtime HR #$rt6n: ${value.hex()}") }
                        if (v > 0) when (type) {
                            1, 6 -> if (v in 25..250) { io.setLiveHr(v); io.emit(M.HR, v.toDouble()); if (type == 1) io.liveReading(LiveKind.HEART_RATE, v.toDouble()) }
                            3 -> if (v in 70..100) { io.emit(M.SPO2, v.toDouble()); io.liveReading(LiveKind.SPO2, v.toDouble()) }
                            10 -> if (v in 1..250) { io.emit(M.HRV, v.toDouble()); io.liveReading(LiveKind.HRV, v.toDouble()) }
                            8 -> if (v in 1..100) { io.emit(M.STRESS, v.toDouble()); io.liveReading(LiveKind.STRESS, v.toDouble()) }
                        }
                    } else if (value.size >= 4 && value.u8(1) == liveType) {
                        io.log("measurement refused: ${value.hex()}")
                        kindOf(liveType)?.let { io.liveFailed(it) }
                    }
                    CMD_BATTERY -> if (value.size >= 2) io.setBattery(value.u8(1))
                }
                inbox.trySend(value)
            }
            BIG_NOTIFY -> {
                if (value.size >= 2 && value.u8(0) == 0xBC && bigSentAt.values.none { System.currentTimeMillis() - it < 10_000 }) io.foreignTraffic("big ${value.u8(1)}")
                bigInbox.trySend(value)
            }
        }
    }

    /**
     * Nothing is written to the band on connect: no clock, no settings. The official app owns the band's
     * configuration (auto HR / HRV / SpO2 / stress switches, time), OpenBand only reads.
     */
    override suspend fun onReady(io: BandIo) {}

    override suspend fun sync(io: BandIo) {
        request(io, packet(CMD_BATTERY)) { _, _ -> true }.firstOrNull()?.let { if (it.size >= 2) io.setBattery(it.u8(1)) }
        val counts = LinkedHashMap<String, Int>()
        fun count(k: String, n: Int) { counts[k] = (counts[k] ?: 0) + n }
        val days = io.historyDays.coerceIn(1, HISTORY_DAYS)
        for (day in 0 until days) {
            if (io.shouldYield()) { io.log("Official app is using the band - sync paused"); return }
            count("steps", runCatching { syncSteps(io, day) }.getOrDefault(0))
            count("hr", runCatching { syncHeartLog(io, LocalDate.now().minusDays(day.toLong())) }.getOrDefault(0))
            count("hrv", runCatching { syncHalfHourly(io, CMD_HRV, day, M.HRV, 1..250) }.getOrDefault(0))
            count("stress", runCatching { syncHalfHourly(io, CMD_STRESS, day, M.STRESS, 1..100) }.getOrDefault(0))
        }
        if (io.hasChar(BIG_WRITE)) {
            count("sleep", runCatching { syncSleep(io) }.onFailure { io.log("sleep: ${it.message}") }.getOrDefault(0))
            for (day in 0 until days) {
                if (io.shouldYield()) { io.log("Official app is using the band - sync paused"); return }
                var t = runCatching { syncTempSeries(io, day) }.getOrDefault(0) +
                    runCatching { syncTempOnce(io, day) }.getOrDefault(0)
                if (t == 0) t = runCatching { syncInterval(io, BIG_TEMPERATURE, day, M.TEMP) }.getOrDefault(0)
                count("temp", t)
                count("spo2", runCatching { syncInterval(io, BIG_SPO2_INTERVAL, day, M.SPO2) }.getOrDefault(0))
            }
            count("spo2", runCatching { syncSpo2Daily(io) }.getOrDefault(0))
        }
        io.log("Band data ($days d): " + counts.entries.joinToString { "${it.key} ${it.value}" })
    }

    private var lastBatteryPoll = 0L

    /** Live refresh while the app is open: today's steps every call, heart rate / HRV / stress on [full]. */
    override suspend fun poll(io: BandIo, full: Boolean) {
        syncSteps(io, 0)
        if (full) {
            if (io.shouldYield()) return
            syncHeartLog(io, LocalDate.now())
            syncHalfHourly(io, CMD_HRV, 0, M.HRV, 1..250)
            syncHalfHourly(io, CMD_STRESS, 0, M.STRESS, 1..100)
        }
        if (System.currentTimeMillis() - lastBatteryPoll > 5 * 60_000) {
            lastBatteryPoll = System.currentTimeMillis()
            request(io, packet(CMD_BATTERY)) { _, _ -> true }.firstOrNull()?.let { if (it.size >= 2) io.setBattery(it.u8(1)) }
        }
    }

    // ------------------------------------------------------------------ background, settings, alerts

    override suspend fun quickRead(io: BandIo) {
        request(io, packet(CMD_BATTERY), 2500) { _, _ -> true }.firstOrNull()?.let { if (it.size >= 2) io.setBattery(it.u8(1)) }
        val steps: List<suspend () -> Unit> = listOf(
            { syncSteps(io, 0) },
            { syncHeartLog(io, LocalDate.now()) },
            { syncHalfHourly(io, CMD_HRV, 0, M.HRV, 1..250) },
            { syncHalfHourly(io, CMD_STRESS, 0, M.STRESS, 1..100) },
            { if (io.hasChar(BIG_WRITE)) syncInterval(io, BIG_SPO2_INTERVAL, 0, M.SPO2) },
            { if (io.hasChar(BIG_WRITE)) syncTempSeries(io, 0) },
        )
        for (step in steps) {
            if (io.shouldYield()) return
            runCatching { step() }.onFailure { io.log("quick read: ${it.message}") }
        }
    }

    /** HeartRateSettingReq: read [22, 1]; the reply is [22, 1, on(1)/off(2), minutes, …]. */
    override suspend fun readHrSetting(io: BandIo): HrSetting? {
        val r = request(io, packet(CMD_HR_SETTING, 1), 3000) { p, _ -> p.u8(1) == 1 }.lastOrNull { it.u8(1) == 1 } ?: return null
        io.log("HR setting: ${r.hex()}")
        return HrSetting(r.u8(2) == 1, r.u8(3), listOf(r.u8(4), r.u8(5), r.u8(6)))
    }

    /** Write [22, 2, on, minutes, …]. Writes only when something differs, then reads back to confirm. */
    override suspend fun writeHrSetting(io: BandIo, minutes: Int): HrSetting? {
        val now = readHrSetting(io) ?: return null
        if (now.enabled && now.minutes == minutes) return now
        if (io.shouldYield()) return null
        request(io, packet(CMD_HR_SETTING, 2, 1, minutes, now.extra.getOrElse(0) { 0 }, now.extra.getOrElse(1) { 0 }, now.extra.getOrElse(2) { 0 }), 3000) { _, _ -> true }
        return readHrSetting(io)
    }

    /** PushMsgUintReq: [114, type, packets, index, up to 11 bytes of text]. */
    override suspend fun alertMessage(io: BandIo, type: Int, label: String): Boolean {
        val text = label.toByteArray().take(11).map { it.toInt() and 0xff }.toIntArray()
        sentAt[CMD_PUSH_MSG] = System.currentTimeMillis()
        return io.write(WRITE, packet(CMD_PUSH_MSG, type, 1, 1, *text))
    }

    /** FindDeviceReq: [80, 0x55, 0xAA]. */
    override suspend fun alertFind(io: BandIo): Boolean {
        sentAt[CMD_FIND_DEVICE] = System.currentTimeMillis()
        return io.write(WRITE, packet(CMD_FIND_DEVICE, 0x55, 0xAA))
    }

    // ------------------------------------------------------------------ command channel

    private class Bin(val ts: Long, val end: Long, var steps: Double, var cal: Double, var dist: Double)

    /**
     * The band's own "today so far" totals (command 72): steps, calories (cal), distance (m). This is the number the Pebble app shows.
     * The hourly bins can disagree with it by a few hundred steps while an hour is still running.
     */
    private suspend fun readTodayTotals(io: BandIo): Triple<Double, Double, Double>? {
        val r = request(io, packet(72), 2500) { _, _ -> true }.firstOrNull() ?: return null
        if (r.size < 13) return null
        val steps = ((r.u8(1) shl 16) or (r.u8(2) shl 8) or r.u8(3)).toDouble()
        val kcal = ((r.u8(7) shl 16) or (r.u8(8) shl 8) or r.u8(9)) / 1000.0
        val dist = ((r.u8(10) shl 16) or (r.u8(11) shl 8) or r.u8(12)).toDouble()
        return Triple(steps, kcal, dist)
    }

    /** Moves the difference between the hourly bins and the true total into the newest bins, so the day adds up exactly. */
    private fun fit(bins: List<Bin>, total: Double, get: (Bin) -> Double, set: (Bin, Double) -> Unit) {
        var diff = total - bins.sumOf(get)
        if (kotlin.math.abs(diff) < 0.5) return
        for (b in bins.sortedByDescending { it.ts }) {
            if (diff > 0) { set(b, get(b) + diff); return }
            val take = minOf(get(b), -diff)
            set(b, get(b) - take); diff += take
            if (diff > -0.5) return
        }
    }

    private suspend fun syncSteps(io: BandIo, dayOffset: Int): Int {
        var newCalorieProtocol = false
        val bins = ArrayList<Bin>()
        val packets = request(io, packet(CMD_STEPS, dayOffset, 0x0f, 0x00, 0x5f, 0x01), 5000) { p, _ ->
            when {
                p.u8(1) == 0xff -> true
                p.u8(1) == 0xf0 -> false
                else -> p.u8(5) >= p.u8(6) - 1
            }
        }
        for (p in packets) {
            if (p.u8(1) == 0xf0) { newCalorieProtocol = p.u8(3) == 1; continue }
            if (p.u8(1) == 0xff) continue
            try {
                val start = LocalDateTime.of(2000 + unbcd(p.u8(1)), unbcd(p.u8(2)), unbcd(p.u8(3)), p.u8(4) / 4, (p.u8(4) % 4) * 15)
                val ts = start.atZone(zone).toInstant().toEpochMilli()
                var cal = p.u16le(7).toDouble()
                if (newCalorieProtocol) cal *= 10
                bins += Bin(ts, ts + 15 * 60_000, p.u16le(9).toDouble(), cal / 1000.0, p.u16le(11).toDouble())
            } catch (e: Exception) {
                io.log("bad steps packet ${p.hex()}")
            }
        }
        if (dayOffset == 0 && bins.isNotEmpty()) {
            readTodayTotals(io)?.let { (steps, kcal, dist) ->
                fit(bins, steps, { it.steps }, { b, v -> b.steps = v })
                fit(bins, kcal, { it.cal }, { b, v -> b.cal = v })
                fit(bins, dist, { it.dist }, { b, v -> b.dist = v })
            }
        }
        var n = 0
        for (b in bins) {
            if (b.steps > 0) { io.emit(M.STEPS, b.steps, b.ts, b.end); n++ }
            if (b.cal > 0) io.emit(M.CALORIES, b.cal, b.ts, b.end)
            if (b.dist > 0) io.emit(M.DISTANCE, b.dist, b.ts, b.end)
        }
        return n
    }

    private suspend fun syncHeartLog(io: BandIo, date: LocalDate): Int {
        val ts = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        var size = 0
        var range = 5
        val values = ArrayList<Int>()
        request(io, packet(CMD_HR_LOG, (ts and 0xff).toInt(), ((ts shr 8) and 0xff).toInt(), ((ts shr 16) and 0xff).toInt(), ((ts shr 24) and 0xff).toInt()), 6000) { p, _ ->
            val sub = p.u8(1)
            when (sub) {
                0xff -> return@request true
                0 -> { size = p.u8(2); range = p.u8(3).takeIf { it > 0 } ?: 5 }
                1 -> for (i in 6..14) values += p.u8(i)
                else -> for (i in 2..14) values += p.u8(i)
            }
            size > 0 && sub >= size - 1
        }
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        var n = 0
        values.forEachIndexed { i, v ->
            if (v in 30..230) { io.emit(M.HR, v.toDouble(), midnight + i.toLong() * range * 60_000); n++ }
        }
        if (date == LocalDate.now()) {
            val slots = values.indices.filter { values[it] in 30..230 }.takeLast(8).joinToString { "%02d:%02d".format(it * range / 60, it * range % 60) }
            io.log("HR measured by the band today at: $slots")
        }
        return n
    }

    /**
     * HRV (57) and stress (55) history - HRVRsp / PressureRsp:
     * [cmd, 0, size, rangeMin] header; [cmd, 1, daysAgo, 12 values]; [cmd, n, 13 values] … ; [cmd, 0xFF] = no data.
     */
    private suspend fun syncHalfHourly(io: BandIo, cmd: Int, dayOffset: Int, type: String, valid: IntRange): Int {
        var size = 0
        var range = 30
        var daysAgo = dayOffset
        val values = ArrayList<Int>()
        request(io, packet(cmd, dayOffset), 5000) { p, _ ->
            val sub = p.u8(1)
            when (sub) {
                0xff -> return@request true
                0 -> { size = p.u8(2); range = p.u8(3).takeIf { it > 0 } ?: 30 }
                1 -> { daysAgo = p.u8(2); for (i in 3..14) values += p.u8(i) }
                else -> for (i in 2..14) values += p.u8(i)
            }
            size > 0 && sub >= size - 1
        }
        val midnight = LocalDate.now().minusDays(daysAgo.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        var n = 0
        values.forEachIndexed { i, v ->
            val t = midnight + i.toLong() * range * 60_000
            if (v in valid && t <= now) { io.emit(type, v.toDouble(), t); n++ }
        }
        return n
    }

    // ------------------------------------------------------------------ big-data channel

    /** Sends a big-data request and returns the payload (bytes after the 6-byte header). */
    private suspend fun bigRequest(io: BandIo, action: Int, payload: ByteArray?, timeoutMs: Long = 8000): ByteArray? {
        while (bigInbox.tryReceive().isSuccess) { }
        bigSentAt[action] = System.currentTimeMillis()
        if (!io.write(BIG_WRITE, bigPacket(action, payload), noResponse = true)) return null
        val buf = ByteArrayOutputStream()
        var expected = -1
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val p = bigInbox.receive()
                if (expected < 0) {
                    if (p.size < 6 || p.u8(0) != 0xBC || p.u8(1) != action) continue
                    expected = p.u16le(2)
                    buf.write(p, 6, p.size - 6)
                } else buf.write(p)
                if (buf.size() >= expected) break
            }
        }
        val data = buf.toByteArray()
        return if (expected >= 0 && data.size >= expected) data.copyOf(expected) else null
    }

    /** Sleep - LargeDataHandler.syncSleepList / parseDaySleep. Payload [0xFF, 1] = all stored days. */
    private suspend fun syncSleep(io: BandIo): Int {
        val d = bigRequest(io, BIG_SLEEP, byteArrayOf(0xFF.toByte(), 1)) ?: return 0
        if (d.isEmpty()) return 0
        var n = 0
        val days = d.u8(0)
        var i = 1
        repeat(days) {
            if (i + 2 > d.size) return n
            val daysAgo = d.u8(i)
            val len = d.u8(i + 1)
            val block = i + 2
            val blockEnd = block + len
            if (blockEnd > d.size || len < 4) return n
            val endMin = d.u16le(block + 2)
            // stage pairs: (type, minutes); 2 light, 3 deep, 4 REM, 5 awake
            val stages = ArrayList<Pair<Int, Int>>()
            var j = block + 4
            while (j + 1 < blockEnd) { stages += d.u8(j) to d.u8(j + 1); j += 2 }
            val total = stages.sumOf { it.second }
            val dayMidnight = LocalDate.now().minusDays(daysAgo.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = dayMidnight + endMin * 60_000L
            var cursor = end - total * 60_000L
            for ((stage, minutes) in stages) {
                val type = when (stage) {
                    2 -> M.SLEEP_LIGHT
                    3 -> M.SLEEP_DEEP
                    4 -> M.SLEEP_REM
                    5 -> M.SLEEP_AWAKE
                    else -> null
                }
                if (type != null && minutes > 0) { io.emit(type, minutes.toDouble(), cursor, cursor + minutes * 60_000L); n++ }
                cursor += minutes * 60_000L
            }
            i = blockEnd
        }
        return n
    }

    /**
     * Interval data (temperature 119, SpO2 95) - payload [dayOffset, packetIndex].
     * Response: [day, intervalMin, packetCount, packetIndex, values…]; temperature = uint16 LE / 100 °C, SpO2 = 1 byte %.
     */
    private suspend fun syncInterval(io: BandIo, action: Int, dayOffset: Int, type: String): Int {
        val values = ArrayList<Double>()
        var interval = 0
        var index = 0
        while (index < 20) {
            val d = bigRequest(io, action, byteArrayOf(dayOffset.toByte(), index.toByte()), 5000) ?: break
            if (d.size < 4) break
            interval = d.u8(1)
            val count = d.u8(2)
            val body = d.copyOfRange(4, d.size)
            if (action == BIG_TEMPERATURE) {
                var k = 0
                while (k + 1 < body.size) { values += body.u16le(k) / 100.0; k += 2 }
            } else body.forEach { values += (it.toInt() and 0xff).toDouble() }
            if (count == 0 || index >= count - 1) break
            index++
        }
        if (interval <= 0) return 0
        val midnight = LocalDate.now().minusDays(dayOffset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        var n = 0
        values.forEachIndexed { i, v ->
            val t = midnight + i.toLong() * interval * 60_000
            val ok = if (type == M.TEMP) v in 30.0..43.0 else v in 70.0..100.0
            if (ok && t <= now) { io.emit(type, v, t); n++ }
        }
        return n
    }

    /**
     * Temperature series - TemperatureHandle.startObtainSeries / parseReceivedData (big-data action 37, payload [day]).
     * Response: [day, intervalMin, encoded…]; byte > 128 = skip (b - 128) empty slots, otherwise °C = b / 10 + 32.
     */
    private suspend fun syncTempSeries(io: BandIo, dayOffset: Int): Int {
        val d = bigRequest(io, BIG_TEMP_SERIES, byteArrayOf(dayOffset.toByte()), 5000) ?: return 0
        if (d.size < 3) return 0
        val interval = d.u8(1)
        if (interval <= 0) return 0
        val midnight = LocalDate.now().minusDays(dayOffset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        var n = 0
        // FileHandle / DataHelper.parseTemperature: one byte per slot, °C = b / 10 + 20, 0 = no reading
        for (k in 2 until d.size) {
            val b = d.u8(k)
            val c = b / 10.0 + 20.0
            val t = midnight + (k - 2).toLong() * interval * 60_000
            if (b > 0 && c in 30.0..43.0 && t <= now) { io.emit(M.TEMP, c, t); n++ }
        }
        return n
    }

    /** Manual temperature readings - DataHelper.parseTemperatureOnce (action 38): [daysAgo, (minuteLE16, value)…]. */
    private suspend fun syncTempOnce(io: BandIo, dayOffset: Int): Int {
        val d = bigRequest(io, BIG_TEMP_ONCE, byteArrayOf(dayOffset.toByte()), 5000) ?: return 0
        if (d.size < 4) return 0
        val midnight = LocalDate.now().minusDays(d.u8(0).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        var n = 0
        var k = 1
        while (k + 2 < d.size) {
            val minute = d.u16le(k)
            val b = d.u8(k + 2)
            val c = b / 10.0 + 20.0
            if (b > 0 && minute < 1440 && c in 30.0..43.0) { io.emit(M.TEMP, c, midnight + minute * 60_000L); n++ }
            k += 3
        }
        return n
    }

    /** Daily SpO2 (42): per day [daysAgo, 24 × (min, max)]. */
    private suspend fun syncSpo2Daily(io: BandIo): Int {
        val d = bigRequest(io, BIG_SPO2_DAILY, null) ?: return 0
        var i = 0
        var n = 0
        while (i + 49 <= d.size) {
            val daysAgo = d.u8(i)
            i++
            val midnight = LocalDate.now().minusDays(daysAgo.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            for (h in 0 until 24) {
                val mn = d.u8(i); val mx = d.u8(i + 1)
                i += 2
                if (mn in 70..100 && mx in 70..100) { io.emit(M.SPO2, (mn + mx) / 2.0, midnight + h * 3_600_000L + 30 * 60_000L); n++ }
            }
        }
        return n
    }

    // ------------------------------------------------------------------ live

    override val supportsLive = true

    private fun kindOf(type: Int) = when (type) { 1 -> LiveKind.HEART_RATE; 3 -> LiveKind.SPO2; 8 -> LiveKind.STRESS; 10 -> LiveKind.HRV; else -> null }

    override suspend fun startLive(io: BandIo, kind: LiveKind) {
        liveType = when (kind) {
            LiveKind.HEART_RATE -> 1
            LiveKind.SPO2 -> 3
            LiveKind.STRESS -> 8
            LiveKind.HRV -> 10
            LiveKind.REALTIME_HR -> 6
        }
        sentAt[CMD_LIVE_START] = System.currentTimeMillis()
        io.write(WRITE, packet(CMD_LIVE_START, liveType, 1))
    }

    override suspend fun stopLive(io: BandIo) {
        if (liveType == 6) { sentAt[CMD_LIVE_START] = System.currentTimeMillis(); io.write(WRITE, packet(CMD_LIVE_START, 6, 4)) }   // action STOP
        else if (liveType != 0) { sentAt[CMD_LIVE_STOP] = System.currentTimeMillis(); io.write(WRITE, packet(CMD_LIVE_STOP, liveType, 0, 0)) }
        liveType = 0
    }
}
