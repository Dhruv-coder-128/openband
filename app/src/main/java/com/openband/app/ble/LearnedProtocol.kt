package com.openband.app.ble

import android.bluetooth.BluetoothGattService
import com.openband.app.data.Db
import kotlinx.coroutines.delay

/**
 * "Auto-learn" driver for bands whose protocol is not built in.
 *
 * The user captures one sync of the OFFICIAL app with Android's Bluetooth HCI snoop log and imports it
 * in the Lab tab. Every command the official app wrote to the band is stored. On each sync this driver
 * replays those commands (matched by ATT handle), so the band sends its full history again and every
 * response is saved in the raw packet log, ready to be decoded / exported.
 */
class LearnedProtocol(private val db: Db) : BandProtocol {
    override val name = "Learned (replay)"

    override fun matches(services: List<BluetoothGattService>) = db.learnedWrites().isNotEmpty()

    override suspend fun sync(io: BandIo) {
        val cmds = db.learnedWrites().distinct()
        if (cmds.isEmpty()) return
        io.log("Replaying ${cmds.size} learned commands")
        for ((handle, hex) in cmds) {
            val ok = runCatching { io.writeHandle(handle, hexToBytes(hex)) }.getOrDefault(false)
            if (!ok) io.log("replay failed for handle 0x%04x".format(handle))
            delay(250)
        }
        // give the band time to stream its responses
        delay(3000)
    }
}
