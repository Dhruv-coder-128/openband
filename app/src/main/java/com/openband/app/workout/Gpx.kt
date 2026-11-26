package com.openband.app.workout

import com.openband.app.data.Workout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Standard GPX 1.1 track (with heart rate in Garmin's extension, which Strava and most tools read). */
object Gpx {
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun build(w: Workout, name: String): String {
        val hr = w.hr.sortedBy { it.first }
        var j = 0
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OpenBand\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("<metadata><time>").append(iso(w.start)).append("</time></metadata>\n")
        sb.append("<trk><name>").append(escape(name)).append("</name><type>").append(escape(w.sport)).append("</type><trkseg>\n")
        for (p in w.route) {
            sb.append(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\">", p.lat, p.lon))
            if (p.alt != 0.0) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p.alt))
            sb.append("<time>").append(iso(w.start + p.t * 1000L)).append("</time>")
            while (j + 1 < hr.size && hr[j + 1].first <= p.t) j++
            val h = hr.getOrNull(j)
            if (h != null && Math.abs(h.first - p.t) <= 60) {
                sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(h.second)
                    .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }
}
