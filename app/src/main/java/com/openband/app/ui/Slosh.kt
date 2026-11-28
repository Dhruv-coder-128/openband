package com.openband.app.ui

/**
 * The liquid's surface as a lightly damped spring: it swings towards the target, overshoots a little and settles.
 * Advanced in small fixed steps, so it moves the same at 60 or 120 frames a second and stays steady after a late frame.
 */
class Slosh(private val stiffness: Float = 55f, private val damping: Float = 9.5f) {
    var position = 0f
        private set
    private var velocity = 0f

    fun step(target: Float, seconds: Float): Float {
        var left = seconds.coerceIn(0f, 0.1f)
        while (left > 0f) {
            val dt = minOf(left, STEP)
            velocity += (stiffness * (target - position) - damping * velocity) * dt
            position += velocity * dt
            left -= dt
        }
        return position
    }

    private companion object { const val STEP = 1f / 240f }
}
