package io.github.vaspyyy.guildmark.client.cooking

import kotlin.math.abs

/**
 * A number a station's picture eases toward what its view says, a tick at a time, with the value of the tick before it
 * kept, so a frame draws [at] between the two by the partial tick and nothing ever jumps (living-world/cook/design.md 1.8:
 * the client animates everything from the clock against the stamps the server sent).
 *
 * Call [begin] once at the start of each tick (it keeps the last value), then one of [ease], [spring], [move] or [set].
 */
class Ease(start: Float = 0f) {
    var cur = start
        private set
    var prev = start
        private set
    private var vel = 0f

    /** The last tick's value is kept, before this tick's changes. */
    fun begin() {
        prev = cur
    }

    /** The value between the last tick's and this one's, [partial] of the way (0 to 1). */
    fun at(partial: Float): Float = prev + (cur - prev) * partial

    /** Jumps to [value] with no motion (the first sight of a station, or a station that was taken away and set up again). */
    fun snap(value: Float) {
        cur = value
        prev = value
        vel = 0f
    }

    /** Sets this tick's value outright (the last tick's is kept by [begin]). */
    fun set(value: Float) {
        cur = value
    }

    /** Moves a [rate] of the way toward [target] (0 to 1): fast at first, slower as it arrives. */
    fun ease(target: Float, rate: Float) {
        cur += (target - cur) * rate
        if (abs(target - cur) < 0.0005f) cur = target
    }

    /** Moves toward [target] by at most [step], and by at least a [rate] of the way: a steady move that still arrives softly. */
    fun move(target: Float, step: Float, rate: Float = 0.2f) {
        val gap = target - cur
        val want = gap * rate
        cur += if (gap > 0f) want.coerceIn(minOf(gap, step * 0.15f), step) else want.coerceIn(-step, maxOf(gap, -step * 0.15f))
        if (abs(target - cur) < 0.0005f) cur = target
    }

    /**
     * A spring toward [target]: pulled with [k] and slowed by [d] a tick, so it arrives a little past and settles back
     * (a chain taking up a weight, a door against its stop). k 0.25 and d 0.62 overshoot by 14% and settle in a dozen ticks.
     */
    fun spring(target: Float, k: Float, d: Float) {
        vel = (vel + (target - cur) * k) * d
        cur += vel
        if (abs(target - cur) < 0.0005f && abs(vel) < 0.0005f) {
            cur = target
            vel = 0f
        }
    }

    /**
     * A spring toward [target] that cannot leave [lo] to [hi]: where it meets either end it gives a [bounce] of the speed
     * back (a door against its frame or its stop), and so settles with a small rebound and not through the wall.
     */
    fun swing(target: Float, k: Float, d: Float, lo: Float, hi: Float, bounce: Float) {
        vel = (vel + (target - cur) * k) * d
        cur += vel
        if (cur < lo) {
            cur = lo
            vel = -vel * bounce
        } else if (cur > hi) {
            cur = hi
            vel = -vel * bounce
        }
        if (abs(target - cur) < 0.0005f && abs(vel) < 0.0005f) {
            cur = target
            vel = 0f
        }
    }

    /** Moves the value and the last tick's together by [delta] (a turning angle brought back into range, with no motion seen). */
    fun shift(delta: Float) {
        cur += delta
        prev += delta
    }

    /** Gives the spring a push (an impulse in value per tick). */
    fun kick(impulse: Float) {
        vel += impulse
    }

    /** How fast it is moving now, value per tick. */
    fun speed(): Float = vel
}
