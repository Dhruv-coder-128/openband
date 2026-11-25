package com.openband.app.ble

import android.bluetooth.BluetoothGattService
import java.util.UUID

/** What a protocol driver can do with the connected band. Implemented by [BandManager]. */
interface BandIo {
    /** Write to a characteristic. noResponse=null picks the write type from the characteristic's properties. */
    suspend fun write(char: UUID, data: ByteArray, noResponse: Boolean? = null): Boolean
    /** Write to a characteristic by its ATT handle (Android instanceId) - used to replay learned commands. */
    suspend fun writeHandle(handle: Int, data: ByteArray): Boolean
    suspend fun read(char: UUID): ByteArray?
    fun hasChar(char: UUID): Boolean
    /** Record a measurement coming from the band. */
    fun emit(type: String, value: Double, ts: Long = System.currentTimeMillis(), endTs: Long = ts)
    fun setLiveHr(bpm: Int?)
    fun setBattery(level: Int)
    fun setInfo(key: String, value: String)
    fun log(msg: String)
    /** How many days of history the current sync should read (7 = full, fewer = quick top-up). */
    val historyDays: Int get() = 7
    /** True when another app (the official one) is talking to the band and we should step aside. */
    fun shouldYield(): Boolean = false
    /** The band answered a command we never sent: another app is using the band. */
    fun foreignTraffic(what: String) {}
    /** The band pushed an event (new steps / measurement) - refresh soon. */
    fun bandEvent() {}
}

/** A driver for one band protocol family. Several drivers can be active on one connection. */
interface BandProtocol {
    val name: String
    /** True if this driver understands the band (normally: a known service UUID is present). */
    fun matches(services: List<BluetoothGattService>): Boolean
    /** Called once after services are discovered and notifications are enabled. */
    suspend fun onReady(io: BandIo) {}
    /** Every notification/indication from the band. Must be fast (no suspending). */
    fun onNotify(io: BandIo, char: UUID, value: ByteArray) {}
    /** Pull stored history from the band. */
    suspend fun sync(io: BandIo) {}
    /** Quick refresh while the app is open (today's steps / heart rate). [full] also reads slower data. */
    suspend fun poll(io: BandIo, full: Boolean) {}
    val supportsLive: Boolean get() = false
    suspend fun startLive(io: BandIo, kind: LiveKind) {}
    suspend fun stopLive(io: BandIo) {}
}

/** REALTIME_HR = the band's continuous heart-rate mode (what the Pebble app's sport mode uses); HEART_RATE = one 30 s measurement. */
enum class LiveKind { HEART_RATE, SPO2, HRV, STRESS, REALTIME_HR }

fun uuid16(short: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun hexToBytes(s: String): ByteArray {
    val clean = s.replace(Regex("[^0-9a-fA-F]"), "")
    require(clean.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xff
fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.u32le(i: Int): Long = (u16le(i).toLong()) or (u16le(i + 2).toLong() shl 16)
