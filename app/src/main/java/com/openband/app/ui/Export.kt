package com.openband.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.openband.app.ble.BandManager
import com.openband.app.data.Db
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Export {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private fun file(ctx: Context, name: String): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        return File(dir, name)
    }

    private fun share(ctx: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** All measurements as CSV. Call off the main thread for the file write; share happens after. */
    fun csv(ctx: Context): File {
        val f = file(ctx, "openband-data.csv")
        f.bufferedWriter().use { w ->
            w.write("start,end,type,value,source\n")
            Db.get(ctx).forEachSample { s ->
                w.write("${iso.format(Date(s.ts))},${iso.format(Date(s.endTs))},${s.type},${s.value},${s.source}\n")
            }
        }
        // a copy in the app's external folder (handy for USB debugging)
        runCatching { ctx.getExternalFilesDir(null)?.let { f.copyTo(File(it, f.name), overwrite = true) } }
        return f
    }

    /** Everything needed to write a decoder for an unknown band. */
    fun labReport(ctx: Context): File {
        val f = file(ctx, "openband-lab-report.txt")
        val st = BandManager.state.value
        val db = Db.get(ctx)
        f.bufferedWriter().use { w ->
            w.write("OpenBand lab report ${iso.format(Date())}\n")
            w.write("Device: ${st.deviceName} ${st.address}\nProtocols: ${st.protocols}\nInfo: ${st.info}\n\n")
            w.write("== GATT services ==\n")
            for (s in st.services) {
                w.write("service ${s.uuid}\n")
                for (c in s.chars) w.write("   char ${c.uuid} handle=0x%04x props=%s\n".format(c.handle, c.propsText))
            }
            w.write("\n== Learned commands from snoop log (handle: hex) ==\n")
            for ((h, hex) in db.learnedWrites()) w.write("0x%04x: %s\n".format(h, hex))
            w.write("\n== Snoop packets (oldest first) ==\n")
            for (p in db.recentRaw(5000, "SNOOP").reversed())
                w.write("${iso.format(Date(p.ts))} ${p.dir} 0x%04x %s\n".format(p.handle, p.hex))
            w.write("\n== Live packets (oldest first) ==\n")
            for (p in db.recentRaw(20000).filter { !it.dir.startsWith("SNOOP") }.reversed())
                w.write("${iso.format(Date(p.ts))} ${p.dir} 0x%04x ${p.uuid} %s\n".format(p.handle, p.hex))
            w.write("\n== Log ==\n")
            st.log.reversed().forEach { w.write(it + "\n") }
        }
        // a copy in the app's external folder (handy for USB debugging)
        runCatching { ctx.getExternalFilesDir(null)?.let { f.copyTo(File(it, f.name), overwrite = true) } }
        return f
    }

    fun shareCsv(ctx: Context, f: File) = share(ctx, f, "text/csv")
    fun shareText(ctx: Context, f: File) = share(ctx, f, "text/plain")
}
