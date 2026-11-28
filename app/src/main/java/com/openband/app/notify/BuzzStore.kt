package com.openband.app.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** Which app uses which vibration. Apps that are not listed do not vibrate the band. */
object BuzzStore {
    val changes = MutableStateFlow(0)

    private fun sp(c: Context) = c.getSharedPreferences("buzz", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)

    /** Switching on for the first time gives the installed well-known apps a pattern each. */
    fun setEnabled(c: Context, on: Boolean) {
        val e = sp(c).edit().putBoolean("enabled", on)
        if (on && !sp(c).getBoolean("filled", false)) {
            e.putBoolean("filled", true)
            for (app in Buzz.KNOWN) app.packages.filter { installed(c, it) }.forEach { e.putString("app:$it", app.default.id) }
        }
        e.apply()
        changes.value++
    }

    fun pattern(c: Context, pkg: String): Buzz.Pattern = Buzz.Pattern.of(sp(c).getString("app:$pkg", null)) ?: Buzz.Pattern.OFF

    fun set(c: Context, packages: List<String>, pattern: Buzz.Pattern) {
        val e = sp(c).edit()
        packages.forEach { e.putString("app:$it", pattern.id) }
        e.apply()
        changes.value++
    }

    fun remove(c: Context, pkg: String) { sp(c).edit().remove("app:$pkg").apply(); changes.value++ }

    /** Every package the user has set a pattern for (including "off"). */
    fun listed(c: Context): List<String> = sp(c).all.keys.filter { it.startsWith("app:") }.map { it.removePrefix("app:") }

    fun installed(c: Context, pkg: String): Boolean =
        pkg == Buzz.CALLS || runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun label(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    /** Apps with a home-screen icon, by name. */
    fun launchable(c: Context): List<Pair<String, String>> = c.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName to it.loadLabel(c.packageManager).toString() }
        .filter { it.first != c.packageName }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }

    /** Has the user allowed OpenBand to see notifications? */
    fun accessGranted(c: Context) = c.packageName in NotificationManagerCompat.getEnabledListenerPackages(c)

    fun listener(c: Context) = ComponentName(c, BandNotifyService::class.java)
}
