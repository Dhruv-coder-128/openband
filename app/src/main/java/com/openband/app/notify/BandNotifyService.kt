package com.openband.app.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.openband.app.ble.BandManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns phone notifications from the chosen apps into a band vibration. Only the app's name, the kind of notification
 * and a fingerprint (to skip repeats) are looked at; message text is never stored or sent to the band.
 */
class BandNotifyService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "alert", e) })
    private val queue = Channel<List<Buzz.Step>>(4, BufferOverflow.DROP_OLDEST)
    private val gate = Buzz.Gate()
    @Volatile private var ringing: String? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (steps in queue) {
                val ok = runCatching { BandManager.alert(steps) }.getOrDefault(false)
                Log.i(TAG, "band alert ${if (ok) "sent" else "skipped"}")
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handle(sbn) }.onFailure { Log.w(TAG, "notification", it) }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (!BuzzStore.enabled(this) || sbn.packageName == packageName) return
        val n = sbn.notification
        val ranking = Ranking()
        val ranked = currentRanking?.getRanking(sbn.key, ranking) == true
        // Do Not Disturb is respected
        if (ranked && !ranking.matchesInterruptionFilter()) return
        // call style notifications say what they are (1 = ringing); older ones only show a full-screen screen while ringing
        val callType = n.extras.getInt("android.callType", 0)
        val incoming = n.category == Notification.CATEGORY_CALL && (callType == 1 || callType == 0 && n.fullScreenIntent != null)
        val pkg: String
        if (incoming) {
            if (ringing == sbn.key) return
            pkg = Buzz.CALLS
        } else {
            if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.category == Notification.CATEGORY_CALL && ringing != null) {
                // the call that was ringing has turned into an ongoing call
                if (ringing == sbn.key) stopRinging()
                return
            }
            // silent notifications stay silent
            if (ranked && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) return
            pkg = sbn.packageName
        }
        val pattern = BuzzStore.pattern(this, pkg)
        if (pattern == Buzz.Pattern.OFF) return
        val fingerprint = "$pkg|${sbn.id}|${n.extras.getCharSequence(Notification.EXTRA_TITLE)}|${n.extras.getCharSequence(Notification.EXTRA_TEXT)}".hashCode().toString()
        if (!incoming && !gate.allow(pkg, fingerprint, System.currentTimeMillis())) return
        val label = if (incoming) "Call" else BuzzStore.label(this, pkg)
        queue.trySend(Buzz.steps(pattern, label))
        if (incoming) {
            ringing = sbn.key
            // never leave the band ringing if the end of the call goes unnoticed
            scope.launch { delay(30_000); if (ringing == sbn.key) stopRinging() }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == ringing) stopRinging()
    }

    private fun stopRinging() {
        ringing = null
        queue.trySend(Buzz.endCall)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object { const val TAG = "BandNotify" }
}
