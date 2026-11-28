package com.openband.app.ble

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Whether the official band app is on screen, so OpenBand can keep quiet meanwhile. Needs "Usage access". */
object OfficialApp {
    val PACKAGES = listOf("com.pebble.ring.app", "com.pebble.plus")

    fun usageGranted(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    @Volatile private var checkedAt = 0L
    @Volatile private var front = false

    /** True while one of the official app's screens is the one in use. The answer is kept for two seconds. */
    fun inFront(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt < 2000) return front
        front = runCatching {
            val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(now - 6 * 3_600_000L, now)
            val e = UsageEvents.Event()
            var open = false
            while (events.getNextEvent(e)) {
                if (e.packageName !in PACKAGES) continue
                // 1 = a screen came to the front, 2 = it left, 23 = it stopped
                when (e.eventType) { 1 -> open = true; 2, 23 -> open = false }
            }
            open
        }.getOrDefault(false)
        checkedAt = now
        return front
    }
}
