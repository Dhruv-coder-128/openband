package com.openband.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.data.RoutePt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/** Map tiles (street or satellite) with a memory + disk cache, so maps you have seen once also work offline. */
object TileCache {
    private val mem = LruCache<String, ImageBitmap>(120)
    private val gate = Semaphore(4)
    private val failedAt = HashMap<String, Long>()
    private var dir: File? = null

    private fun dir(ctx: Context): File = dir ?: File(ctx.cacheDir, "osm").apply { mkdirs() }.also { dir = it }

    private fun key(sat: Boolean, z: Int, x: Int, y: Int) = (if (sat) "s/" else "m/") + "$z/$x/$y"

    fun peek(sat: Boolean, z: Int, x: Int, y: Int): ImageBitmap? = mem.get(key(sat, z, x, y))

    suspend fun load(ctx: Context, sat: Boolean, z: Int, x: Int, y: Int): ImageBitmap? {
        val key = key(sat, z, x, y)
        mem.get(key)?.let { return it }
        if (System.currentTimeMillis() - (failedAt[key] ?: 0L) < 20_000) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val f = File(dir(ctx), (if (sat) "s_" else "") + "${z}_${x}_$y.png")
                runCatching {
                    if (!f.exists() || f.length() < 100) {
                        val url = if (sat) "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
                        else "https://tile.openstreetmap.org/$z/$x/$y.png"
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 8000; c.readTimeout = 8000
                        // OSM's tile policy asks for an identifying User-Agent
                        c.setRequestProperty("User-Agent", "OpenBand/5 (Android fitness app)")
                        try {
                            if (c.responseCode == 200) c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                            else throw java.io.IOException("HTTP ${c.responseCode}")
                        } finally { c.disconnect() }
                    }
                    BitmapFactory.decodeFile(f.path)?.asImageBitmap()
                }.getOrNull()?.also { mem.put(key, it) } ?: run { failedAt[key] = System.currentTimeMillis(); null }
            }
        }
    }
}

internal const val TILE = 256
internal fun worldSize(z: Int) = TILE * 2.0.pow(z)
internal fun lonToX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * worldSize(z)
internal fun latToY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat)
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(z)
}
private fun xToLon(x: Double, z: Int) = x / worldSize(z) * 360.0 - 180.0
private fun yToLat(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / worldSize(z)))))

/** An extra line drawn under the main track (heatmap pieces, the whole route during a replay...). */
class MapLine(val points: List<RoutePt>, val color: Color, val widthDp: Float)

/**
 * A real map (satellite photos or streets) with the GPS track, start and finish flags and the lap line.
 * Drag to move, pinch or +/- to zoom. [follow] = live mode: keeps the latest position centred until you drag.
 */
@Composable
fun OsmMap(
    route: List<RoutePt>,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    follow: Boolean = false,
    me: Pair<Double, Double>? = null,
    lapLine: RoutePt? = null,
    lines: List<MapLine> = emptyList(),
    /** What the first view should frame; defaults to the track itself. */
    fit: List<RoutePt>? = null,
    /** A moving position dot (route replay). */
    marker: RoutePt? = null,
    flags: Boolean = true,
) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val sat = look.prefs.mapSatellite
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    var zoom by remember { mutableIntStateOf(16) }
    var cLat by remember { mutableStateOf(0.0) }
    var cLon by remember { mutableStateOf(0.0) }
    var moved by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    var big by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxWidth().height(if (big) height * 1.9f else height).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B2127))) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // map tiles are 256 px; draw them larger on dense screens so labels and detail stay readable
        val scale = (density.density / 1.6f).coerceIn(1f, 2f)
        val vw = w / scale; val vh = h / scale

        // first placement / follow
        val frame = fit ?: route
        val last = frame.lastOrNull()
        val target = me ?: last?.let { it.lat to it.lon }
        LaunchedEffect(frame.size, me, w, h) {
            if (target == null) return@LaunchedEffect
            if (follow) {
                if (!moved) { cLat = target.first; cLon = target.second; if (!fitted) { zoom = 17; fitted = true } }
            } else if (!fitted && frame.size >= 2) {
                val minLat = frame.minOf { it.lat }; val maxLat = frame.maxOf { it.lat }
                val minLon = frame.minOf { it.lon }; val maxLon = frame.maxOf { it.lon }
                var z = 18
                while (z > 3) {
                    val dx = lonToX(maxLon, z) - lonToX(minLon, z)
                    val dy = latToY(minLat, z) - latToY(maxLat, z)
                    if (dx < vw * 0.78 && dy < vh * 0.74) break
                    z--
                }
                zoom = z; cLat = (minLat + maxLat) / 2; cLon = (minLon + maxLon) / 2; fitted = true
            } else if (!fitted) { cLat = target.first; cLon = target.second; zoom = 16; fitted = true }
        }

        if (target == null && frame.isEmpty()) {
            Text("Waiting for GPS…", color = Color(0xFF9AA4AE), modifier = Modifier.align(Alignment.Center), fontSize = 13.sp)
            return@BoxWithConstraints
        }

        val cx = lonToX(cLon, zoom); val cy = latToY(cLat, zoom)
        val ox = cx - vw / 2; val oy = cy - vh / 2
        val n = 2.0.pow(zoom).toInt()
        val x0 = floor(ox / TILE).toInt(); val x1 = floor((ox + vw) / TILE).toInt()
        val y0 = floor(oy / TILE).toInt(); val y1 = floor((oy + vh) / TILE).toInt()
        val needed = ArrayList<Triple<Int, Int, Int>>()
        for (ty in y0..y1) for (tx in x0..x1) if (ty in 0 until n) needed += Triple(zoom, ((tx % n) + n) % n, ty)
        val layer = if (sat) "s" else "m"
        val sig = layer + needed.joinToString("|")
        LaunchedEffect(sig) {
            for ((z, x, y) in needed) {
                val k = "$layer/$z/$x/$y"
                if (tiles.containsKey(k)) continue
                val cached = TileCache.peek(sat, z, x, y)
                if (cached != null) tiles[k] = cached
                else launch { TileCache.load(ctx, sat, z, x, y)?.let { tiles[k] = it } }
            }
        }

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var pinch = 1f
                detectTransformGestures { _, pan, factor, _ ->
                    if (pan != Offset.Zero) {
                        moved = true
                        val ncx = lonToX(cLon, zoom) - pan.x / scale
                        val ncy = latToY(cLat, zoom) - pan.y / scale
                        cLon = xToLon(ncx, zoom); cLat = yToLat(ncy, zoom)
                    }
                    pinch *= factor
                    if (pinch > 1.35f && zoom < 19) { zoom++; pinch = 1f }
                    if (pinch < 0.74f && zoom > 3) { zoom--; pinch = 1f }
                }
            },
        ) {
            val side = kotlin.math.ceil(TILE * scale).toInt()
            for (ty in y0..y1) for (tx in x0..x1) {
                if (ty !in 0 until n) continue
                val img = tiles["$layer/$zoom/${((tx % n) + n) % n}/$ty"] ?: continue
                drawImage(
                    img, dstOffset = IntOffset(((tx * TILE - ox) * scale).roundToInt(), ((ty * TILE - oy) * scale).roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(side, side),
                )
            }
            if (!look.light && !sat) drawRect(Color.Black.copy(alpha = 0.22f))
            fun at(lat: Double, lon: Double) = Offset(((lonToX(lon, zoom) - ox) * scale).toFloat(), ((latToY(lat, zoom) - oy) * scale).toFloat())
            val dp = density.density
            // bright orange reads well on photos, the accent colour on the street map
            val trackColor = if (sat) Color(0xFFFF6A2B) else look.primary
            lines.forEach { l ->
                if (l.points.size < 2) return@forEach
                val path = Path()
                l.points.forEachIndexed { i, p -> val o = at(p.lat, p.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, l.color, style = Stroke(width = l.widthDp * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (route.size >= 2) {
                val path = Path()
                route.forEachIndexed { i, p -> val o = at(p.lat, p.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, Color.White, style = Stroke(width = 6f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, trackColor, style = Stroke(width = 3.5f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            fun flag(o: Offset, color: Color, label: String, above: Boolean) {
                drawCircle(Color.White, 9f * dp, o); drawCircle(color, 6.5f * dp, o)
                val native = drawContext.canvas.nativeCanvas
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 11f * dp; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER
                }
                val tw = paint.measureText(label)
                val ty = if (above) o.y - 17f * dp else o.y + 25f * dp
                paint.color = android.graphics.Color.WHITE
                native.drawRoundRect(o.x - tw / 2 - 7f * dp, ty - 13f * dp, o.x + tw / 2 + 7f * dp, ty + 5f * dp, 9f * dp, 9f * dp, paint)
                paint.color = 0xFF17181A.toInt()
                native.drawText(label, o.x, ty, paint)
            }
            if (flags && route.isNotEmpty()) {
                val first = at(route.first().lat, route.first().lon)
                val end = at(route.last().lat, route.last().lon)
                fun close(a: Offset, b: Offset) = (a - b).getDistance() < 40f * dp
                val done = !follow && route.size > 1
                val loop = done && close(first, end)
                if (lapLine != null) {
                    val o = at(lapLine.lat, lapLine.lon)
                    if (!close(o, first) && !(done && close(o, end))) flag(o, Color(0xFFF2B21B), "Lap line", false)
                }
                if (done && !loop) flag(end, Color(0xFFD63B3B), "Finish", false)
                flag(first, Color(0xFF1F9D58), if (loop) "Start & finish" else "Start", true)
            }
            val here = me ?: marker?.let { it.lat to it.lon } ?: if (follow) route.lastOrNull()?.let { it.lat to it.lon } else null
            if (here != null) {
                val o = at(here.first, here.second)
                drawCircle(Color(0x553B82F6), 14f * dp, o); drawCircle(Color.White, 7.5f * dp, o); drawCircle(Color(0xFF3B82F6), 5.5f * dp, o)
            }
        }

        // layer switch
        Row(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0xF2FFFFFF)).padding(3.dp)) {
            listOf(true to "Satellite", false to "Map").forEach { (isSat, label) ->
                val on = sat == isSat
                Text(
                    label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else Color(0xFF333333),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0xFF17181A) else Color.Transparent)
                        .clickable { RaceSettings.update { it.copy(mapSatellite = isSat) } }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        // zoom, size, recentre
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            MapBtn("+") { if (zoom < 19) zoom++ }
            Box(Modifier.size(6.dp))
            MapBtn("−") { if (zoom > 3) zoom-- }
            Box(Modifier.size(6.dp))
            MapBtn(if (big) "⤡" else "⤢") { big = !big; fitted = follow }
            if (moved) {
                Box(Modifier.size(6.dp))
                MapBtn("◎") { moved = false; if (follow) target?.let { cLat = it.first; cLon = it.second } else fitted = false; if (!follow && frame.isNotEmpty()) { cLat = (frame.minOf { it.lat } + frame.maxOf { it.lat }) / 2; cLon = (frame.minOf { it.lon } + frame.maxOf { it.lon }) / 2 } }
            }
        }
        Text(
            if (sat) "Imagery © Esri, Maxar, Earthstar Geographics" else "© OpenStreetMap contributors", color = Color(0xFF333333), fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun MapBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color(0xE6FFFFFF)).border(1.dp, Color(0x22000000), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color(0xFF222222), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
}
