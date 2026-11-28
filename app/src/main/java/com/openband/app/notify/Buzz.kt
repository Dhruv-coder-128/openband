package com.openband.app.notify

/** How the band can be made to vibrate, and which apps use which pattern. */
object Buzz {
    /** One command to the band: [messageType] null = the "find my band" vibration. */
    data class Step(val messageType: Int?, val label: String, val pauseMs: Long = 0)

    enum class Pattern(val id: String, val label: String, val about: String) {
        OFF("off", "Off", "No vibration"),
        ONE("one", "Short", "1 buzz"),
        TWO("two", "Double", "2 buzzes"),
        THREE("three", "Triple", "3 buzzes"),
        FIND("find", "Strong", "find my band"),
        CALL("call", "Ringing", "like a call");

        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    /** Apps offered by name; any other installed app can be added too. */
    data class Known(val title: String, val packages: List<String>, val default: Pattern)

    val KNOWN = listOf(
        Known("Phone calls", listOf(CALLS), Pattern.CALL),
        Known("Messages (SMS)", listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"), Pattern.ONE),
        Known("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), Pattern.TWO),
        Known("Instagram", listOf("com.instagram.android"), Pattern.THREE),
        Known("X", listOf("com.twitter.android"), Pattern.ONE),
        Known("YouTube", listOf("com.google.android.youtube"), Pattern.ONE),
        Known("Telegram", listOf("org.telegram.messenger"), Pattern.TWO),
        Known("Snapchat", listOf("com.snapchat.android"), Pattern.THREE),
        Known("Facebook", listOf("com.facebook.katana", "com.facebook.orca"), Pattern.ONE),
        Known("Gmail", listOf("com.google.android.gm"), Pattern.ONE),
    )

    /** Stand-in "package" for incoming calls, whichever dialler shows them. */
    const val CALLS = "calls"

    // PushMsgUintReq types that make this band vibrate: the others (SMS, WhatsApp...) are accepted but stay silent
    private const val RING = 0
    private const val CALL_END = 4

    /** How long a short buzz rings before it is stopped, and the gap before the next one. */
    private const val PULSE_MS = 900L
    private const val GAP_MS = 900L

    /** Short buzzes are a call alert that is stopped at once. */
    private fun pulses(count: Int, label: String) = (0 until count).flatMap { i ->
        listOf(Step(RING, label, if (i == 0) 0 else GAP_MS), Step(CALL_END, label, PULSE_MS))
    }

    fun steps(pattern: Pattern, label: String): List<Step> = when (pattern) {
        Pattern.OFF -> emptyList()
        Pattern.ONE -> pulses(1, label)
        Pattern.TWO -> pulses(2, label)
        Pattern.THREE -> pulses(3, label)
        Pattern.FIND -> listOf(Step(null, label))
        Pattern.CALL -> listOf(Step(RING, label))
    }

    /** Tells the band the call was answered or ended, so it stops ringing. */
    val endCall = listOf(Step(CALL_END, "Call"))

    /** True for the step that silences a ringing band: it must be sent even when everything else is skipped. */
    fun stops(step: Step) = step.messageType == CALL_END

    /**
     * Whether a notification deserves a buzz: not the app's own housekeeping, not the same text again, and not too soon
     * after the previous buzz for that app.
     */
    class Gate(private val sameTextMs: Long = 60_000, private val appGapMs: Long = 6_000) {
        private val seen = HashMap<String, Long>()
        private val apps = HashMap<String, Long>()

        fun allow(pkg: String, fingerprint: String, now: Long): Boolean {
            seen.entries.removeAll { now - it.value > sameTextMs }
            if (fingerprint in seen) return false
            seen[fingerprint] = now
            if (now - (apps[pkg] ?: -appGapMs) < appGapMs) return false
            apps[pkg] = now
            return true
        }
    }
}
