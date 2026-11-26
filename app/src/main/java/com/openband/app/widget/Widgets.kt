package com.openband.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import com.openband.app.MainActivity
import com.openband.app.R

/** Base of all 15 widget providers: they only differ in the [kind] they draw. */
abstract class BaseWidget(val kind: String) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = WidgetUpdater.refresh(context, goAsync())
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) =
        WidgetUpdater.refresh(context, goAsync())
    override fun onEnabled(context: Context) = WidgetUpdater.refresh(context, null)
    override fun onDeleted(context: Context, appWidgetIds: IntArray) = appWidgetIds.forEach { WidgetConfigs.delete(context, it) }
}

object WidgetUpdater {
    class Info(val kind: String, val cls: Class<out BaseWidget>, val label: String, val wDp: Int, val hDp: Int, val note: String)

    /** Every widget on offer, in the order shown in the gallery. */
    val catalog: List<Info> = listOf(
        Info("steps", StepsWidget::class.java, "Steps ring", 150, 150, "Today's steps vs your goal"),
        Info("calories", CaloriesWidget::class.java, "Calories ring", 150, 150, "Active calories vs your goal"),
        Info("hr", HeartRateWidget::class.java, "Heart rate", 150, 150, "Latest bpm + today's trend"),
        Info("hrgraph", HeartGraphWidget::class.java, "Heart-rate graph", 320, 150, "Full day graph, min / avg / max"),
        Info("sleep", SleepWidget::class.java, "Sleep ring", 150, 150, "Last night vs your goal"),
        Info("sleepstages", SleepStagesWidget::class.java, "Sleep stages", 320, 150, "Deep, light, REM and awake timeline"),
        Info("spo2", Spo2Widget::class.java, "Blood oxygen", 150, 150, "Latest SpO2 on a gauge"),
        Info("hrv", HrvWidget::class.java, "HRV", 150, 150, "Variability + 7-day trend"),
        Info("stress", StressWidget::class.java, "Stress", 150, 150, "Latest stress on a colour gauge"),
        Info("temp", TempWidget::class.java, "Skin temperature", 150, 150, "Latest wrist temperature"),
        Info("battery", BatteryWidget::class.java, "Band battery", 150, 70, "Your band's charge"),
        Info("rings3", RingsWidget::class.java, "Daily rings", 320, 150, "Sleep, steps, calories rings"),
        Info("vitals", VitalsWidget::class.java, "All vitals", 320, 150, "HR, SpO2, HRV, stress, temp, battery"),
        Info("weeksteps", WeekStepsWidget::class.java, "Week of steps", 320, 150, "7 days with your goal line"),
        Info("streak", StreakWidget::class.java, "Streak", 150, 150, "Active-day streak + this week"),
    )
    private val kinds: List<Pair<String, Class<out BaseWidget>>> = catalog.map { it.kind to it.cls }

    fun label(kind: String) = catalog.firstOrNull { it.kind == kind }?.label ?: "Widget"

    /** A widget currently on the home screen. */
    class Placed(val id: Int, val info: Info, val cfg: WidgetConfig, val wDp: Int, val hDp: Int)

    fun placed(ctx: Context): List<Placed> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return catalog.flatMap { info ->
            mgr.getAppWidgetIds(ComponentName(ctx, info.cls)).map { id ->
                val (w, h) = sizeDp(mgr, id, info.kind, ctx)
                Placed(id, info, WidgetConfigs.load(ctx, id, info.kind), w, h)
            }
        }
    }

    /** Picture of a widget with a given configuration and size, for previews. */
    fun draw(ctx: Context, cfg: WidgetConfig, wDp: Int, hDp: Int, data: WData): android.graphics.Bitmap {
        val d = ctx.resources.displayMetrics.density
        return WidgetRenderer.render(ctx, cfg, wDp.toFloat(), hDp.toFloat(), (wDp * d).toInt(), (hDp * d).toInt(), data)
    }

    /** Preview picture of one widget at its natural size (used by the gallery). */
    fun preview(ctx: Context, info: Info, data: WData): android.graphics.Bitmap = draw(ctx, WidgetConfig(info.kind), info.wDp, info.hDp, data)

    /** Asks the launcher to pin this widget (one tap for the user). Returns false if the launcher can't. */
    fun pin(ctx: Context, info: Info): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return mgr.requestPinAppWidget(ComponentName(ctx, info.cls), null, null)
    }

    private val defaults = mapOf(
        "hrgraph" to (320 to 150), "sleepstages" to (320 to 150), "rings3" to (320 to 150), "vitals" to (320 to 150), "weeksteps" to (320 to 150),
        "battery" to (150 to 70),
    )

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var pending = false

    /** Redraws every placed widget on a background thread. */
    fun refresh(ctx: Context, pr: BroadcastReceiver.PendingResult?) {
        val app = ctx.applicationContext
        Thread { try { updateAll(app) } catch (_: Throwable) { } finally { pr?.finish() } }.start()
    }

    /** Called whenever data or the theme changes; several calls within 4 s become one redraw. */
    fun request(ctx: Context) {
        if (pending) return
        pending = true
        val app = ctx.applicationContext
        handler.postDelayed({ pending = false; refresh(app, null) }, 4000)
    }

    private fun sizeDp(mgr: AppWidgetManager, id: Int, kind: String, ctx: Context): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var w = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        var h = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val d = defaults[kind] ?: (150 to 150)
        if (w <= 0) w = d.first
        if (h <= 0) h = d.second
        return w to h
    }

    private fun views(ctx: Context, cfg: WidgetConfig, wDp: Float, hDp: Float, data: WData, cap: Float): RemoteViews {
        val dens = ctx.resources.displayMetrics.density
        var w = wDp * dens; var h = hDp * dens
        val px = w * h
        if (px > cap) { val k = Math.sqrt((cap / px).toDouble()).toFloat(); w *= k; h *= k }   // keep the bitmap small enough for the launcher
        val bmp = WidgetRenderer.render(ctx, cfg, wDp, hDp, w.toInt(), h.toInt(), data)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_image)
        rv.setImageViewBitmap(R.id.widget_img, bmp)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        rv.setOnClickPendingIntent(R.id.widget_root, open)
        return rv
    }

    /** Draws one placed widget at the size(s) the launcher says it has, so it stays sharp after resizing. */
    private fun draw(ctx: Context, mgr: AppWidgetManager, id: Int, kind: String, data: WData) {
        val cfg = WidgetConfigs.load(ctx, id, kind)
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes = mgr.getAppWidgetOptions(id).getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width >= 30f && it.height >= 30f }?.distinct()?.take(4)
            if (!sizes.isNullOrEmpty()) {
                val cap = 900_000f / sizes.size
                mgr.updateAppWidget(id, RemoteViews(sizes.associateWith { views(ctx, cfg, it.width, it.height, data, cap.coerceAtMost(520_000f)) }))
                return
            }
        }
        val (w, h) = sizeDp(mgr, id, kind, ctx)
        mgr.updateAppWidget(id, views(ctx, cfg, w.toFloat(), h.toFloat(), data, 520_000f))
    }

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val placed = kinds.map { (k, c) -> Triple(k, c, mgr.getAppWidgetIds(ComponentName(ctx, c))) }.filter { it.third.isNotEmpty() }
        if (placed.isEmpty()) return
        val data = loadWidgetData(ctx)
        for ((kind, _, ids) in placed) for (id in ids) {
            runCatching { draw(ctx, mgr, id, kind, data) }.onFailure { android.util.Log.w("Widget", "update $kind", it) }
        }
    }

    /** Fills the launcher's widget picker with live-looking previews (Android 15+). Done once per app version. */
    fun ensurePreviews(ctx: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val prefs = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
        if (prefs.getLong("preview_ver", -1) == ver) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val data = loadWidgetData(ctx)
        runCatching {
            for ((kind, c) in kinds) {
                val d = defaults[kind] ?: (150 to 150)
                val dd = if (d.first == 150 && d.second == 150 && kind != "battery") 150 to 150 else d
                mgr.setWidgetPreview(ComponentName(ctx, c), AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, views(ctx, WidgetConfig(kind), dd.first.toFloat(), dd.second.toFloat(), data, 520_000f))
            }
            prefs.edit().putLong("preview_ver", ver).apply()
        }
    }
}
