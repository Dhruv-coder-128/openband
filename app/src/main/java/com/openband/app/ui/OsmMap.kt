package com.openband.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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

/** OpenStreetMap raster tiles with a memory + disk cache, so maps you have seen once also work offline. */
object TileCache {
    private val mem = LruCache<String, ImageBitmap>(120)
    private val gate = Semaphore(4)
    private val failedAt = HashMap<String, Long>()
    private var dir: File? = null

    private fun dir(ctx: Context): File = dir ?: File(ctx.cacheDir, "osm").apply { mkdirs() }.also { dir = it }

    fun peek(z: Int, x: Int, y: Int): ImageBitmap? = mem.get("$z/$x/$y")

    suspend fun load(ctx: Context, z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        if (System.currentTimeMillis() - (failedAt[key] ?: 0L) < 20_000) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val f = File(dir(ctx), "${z}_${x}_$y.png")
                runCatching {
                    if (!f.exists() || f.length() < 100) {
                        val c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
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

private const val TILE = 256
private fun worldSize(z: Int) = TILE * 2.0.pow(z)
private fun lonToX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * worldSize(z)
private fun latToY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat)
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(z)
}
private fun xToLon(x: Double, z: Int) = x / worldSize(z) * 360.0 - 180.0
private fun yToLat(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / worldSize(z)))))

/**
 * A real map (OpenStreetMap) with the GPS track drawn on it. Drag to move, +/- to zoom.
 * [follow] = live mode: keeps the latest position in the middle until you drag the map yourself.
 */
@Composable
fun OsmMap(
    route: List<RoutePt>,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    follow: Boolean = false,
    me: Pair<Double, Double>? = null,
) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    var zoom by remember { mutableIntStateOf(16) }
    var cLat by remember { mutableStateOf(0.0) }
    var cLon by remember { mutableStateOf(0.0) }
    var moved by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B2127))) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }

        // first placement / follow
        val last = route.lastOrNull()
        val target = me ?: last?.let { it.lat to it.lon }
        LaunchedEffect(route.size, me, w, h) {
            if (target == null) return@LaunchedEffect
            if (follow) {
                if (!moved) { cLat = target.first; cLon = target.second; if (!fitted) { zoom = 17; fitted = true } }
            } else if (!fitted && route.size >= 2) {
                val minLat = route.minOf { it.lat }; val maxLat = route.maxOf { it.lat }
                val minLon = route.minOf { it.lon }; val maxLon = route.maxOf { it.lon }
                var z = 18
                while (z > 3) {
                    val dx = lonToX(maxLon, z) - lonToX(minLon, z)
                    val dy = latToY(minLat, z) - latToY(maxLat, z)
                    if (dx < w * 0.78 && dy < h * 0.78) break
                    z--
                }
                zoom = z; cLat = (minLat + maxLat) / 2; cLon = (minLon + maxLon) / 2; fitted = true
            } else if (!fitted) { cLat = target.first; cLon = target.second; zoom = 16; fitted = true }
        }

        if (target == null && route.isEmpty()) {
            Text("Waiting for GPS…", color = Color(0xFF9AA4AE), modifier = Modifier.align(Alignment.Center), fontSize = 13.sp)
            return@BoxWithConstraints
        }

        val cx = lonToX(cLon, zoom); val cy = latToY(cLat, zoom)
        val ox = cx - w / 2; val oy = cy - h / 2
        val n = 2.0.pow(zoom).toInt()
        val x0 = floor(ox / TILE).toInt(); val x1 = floor((ox + w) / TILE).toInt()
        val y0 = floor(oy / TILE).toInt(); val y1 = floor((oy + h) / TILE).toInt()
        val needed = ArrayList<Triple<Int, Int, Int>>()
        for (ty in y0..y1) for (tx in x0..x1) if (ty in 0 until n) needed += Triple(zoom, ((tx % n) + n) % n, ty)
        val sig = needed.joinToString("|")
        LaunchedEffect(sig) {
            for ((z, x, y) in needed) {
                val k = "$z/$x/$y"
                if (tiles.containsKey(k)) continue
                TileCache.peek(z, x, y)?.let { tiles[k] = it; return@let }
                if (!tiles.containsKey(k)) launch { TileCache.load(ctx, z, x, y)?.let { tiles[k] = it } }
            }
        }

        Canvas(
            Modifier.fillMaxSize().pointerInput(zoom) {
                detectDragGestures { change, drag ->
                    change.consume()
                    moved = true
                    val ncx = lonToX(cLon, zoom) - drag.x
                    val ncy = latToY(cLat, zoom) - drag.y
                    cLon = xToLon(ncx, zoom); cLat = yToLat(ncy, zoom)
                }
            },
        ) {
            for (ty in y0..y1) for (tx in x0..x1) {
                if (ty !in 0 until n) continue
                val img = tiles["$zoom/${((tx % n) + n) % n}/$ty"] ?: continue
                drawImage(img, dstOffset = IntOffset((tx * TILE - ox).roundToInt(), (ty * TILE - oy).roundToInt()))
            }
            if (!look.light) drawRect(Color.Black.copy(alpha = 0.22f))
            fun pt(p: RoutePt) = Offset((lonToX(p.lon, zoom) - ox).toFloat(), (latToY(p.lat, zoom) - oy).toFloat())
            if (route.size >= 2) {
                val path = Path()
                route.forEachIndexed { i, p -> val o = pt(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, Color.White, style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, look.primary, style = Stroke(width = 6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (route.isNotEmpty()) {
                val s = pt(route.first())
                drawCircle(Color.White, 13f, s); drawCircle(Color(0xFF22C55E), 9f, s)
                if (!follow && route.size > 1) { val e = pt(route.last()); drawCircle(Color.White, 13f, e); drawCircle(Color(0xFFEF4444), 9f, e) }
            }
            val here = me ?: if (follow) route.lastOrNull()?.let { it.lat to it.lon } else null
            if (here != null) {
                val o = Offset((lonToX(here.second, zoom) - ox).toFloat(), (latToY(here.first, zoom) - oy).toFloat())
                drawCircle(Color(0x553B82F6), 30f, o); drawCircle(Color.White, 15f, o); drawCircle(Color(0xFF3B82F6), 11f, o)
            }
        }

        // zoom + recenter
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            MapBtn("+") { if (zoom < 19) zoom++ }
            Box(Modifier.size(6.dp))
            MapBtn("−") { if (zoom > 3) zoom-- }
            if (follow && moved) { Box(Modifier.size(6.dp)); MapBtn("◎") { moved = false; target?.let { cLat = it.first; cLon = it.second } } }
        }
        Text(
            "© OpenStreetMap contributors", color = Color(0xFF333333), fontSize = 9.sp,
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
