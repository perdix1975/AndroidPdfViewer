package gr.kingpool.tracker

import kotlin.math.abs

class VolumeGestureRecognizer(
    private val maxGapMs: Long = 800L,
    private val maxPatternMs: Long = 2_200L,
    private val cooldownMs: Long = 3_000L,
) {
    data class Trigger(val originalVolume: Int)
    private data class Step(val direction: Int, val atMs: Long, val beforeVolume: Int)
    private val steps = ArrayDeque<Step>()
    private var lastTriggerAtMs: Long? = null

    fun onVolumeChange(beforeVolume: Int, currentVolume: Int, atMs: Long): Trigger? {
        val delta = currentVolume - beforeVolume
        if (delta == 0) return null
        if (abs(delta) > 2) {
            resetPending()
            return null
        }
        val direction = if (delta > 0) 1 else -1
        while (steps.isNotEmpty() && atMs - steps.first().atMs > maxPatternMs) steps.removeFirst()
        if (steps.isNotEmpty() && atMs - steps.last().atMs > maxGapMs) steps.clear()
        steps.add(Step(direction, atMs, beforeVolume))
        while (steps.size > 4) steps.removeFirst()
        if (steps.size != 4) return null
        val a = steps.elementAt(0)
        val b = steps.elementAt(1)
        val c = steps.elementAt(2)
        val d = steps.elementAt(3)
        val alternating = a.direction == -b.direction && b.direction == -c.direction && c.direction == -d.direction
        if (!alternating || d.atMs - a.atMs > maxPatternMs) return null
        val previousTrigger = lastTriggerAtMs
        if (previousTrigger != null && atMs - previousTrigger < cooldownMs) {
            steps.clear()
            return null
        }
        lastTriggerAtMs = atMs
        steps.clear()
        return Trigger(originalVolume = a.beforeVolume)
    }

    fun resetPending() {
        steps.clear()
    }
}
