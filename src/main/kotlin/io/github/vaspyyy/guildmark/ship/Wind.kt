package io.github.vaspyyy.guildmark.ship

import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import kotlin.math.abs
import kotlin.math.sin

/**
 * The wind over the water. It veers slowly round the compass through the day and freshens and falls away
 * again; rain stiffens it and a thunderstorm blows hard. It's worked out from the world's clock and weather
 * alone, so the server and every client agree on it without being told.
 */
object Wind {
    private const val TAU = Math.PI * 2.0

    /** Degrees, as a Minecraft yaw: the way you'd face to look into the wind (where it blows from). */
    fun from(level: Level, partial: Float = 0f): Float = from(level.gameTime + partial.toDouble())

    fun from(time: Double): Float {
        val drift = 150.0 * sin(time / 9100.0 * TAU + 1.3) +
            95.0 * sin(time / 23300.0 * TAU + 0.4) +
            70.0 * sin(time / 61700.0 * TAU + 2.2) +
            12.0 * sin(time / 1300.0 * TAU + 5.1)
        return Mth.wrapDegrees((BASE + drift).toFloat())
    }

    /** 0 to 1: a breeze at 0.3, a fresh wind at 0.6, a gale at 1. Gusts included. */
    fun strength(level: Level, partial: Float = 0f): Float {
        val time = level.gameTime + partial.toDouble()
        val weather = 0.22f * level.getRainLevel(partial) + 0.25f * level.getThunderLevel(partial)
        return (calm(time) + gust(time) + weather).coerceIn(0.12f, 1f)
    }

    /** The wind's strength before the weather and the gusts. */
    private fun calm(time: Double): Float =
        (0.5 + 0.17 * sin(time / 7300.0 * TAU + 0.7) + 0.08 * sin(time / 1900.0 * TAU + 2.9)).toFloat()

    /** Gusts: a few seconds' freshening now and then. */
    private fun gust(time: Double): Float {
        val g = sin(time / 97.0 * TAU) * sin(time / 41.0 * TAU + 1.0)
        return (0.06 * g * abs(sin(time / 600.0 * TAU))).toFloat()
    }

    /** Knots for a speed in blocks a tick (a block is a metre). */
    fun knots(blocksPerTick: Double): Double = blocksPerTick * 20.0 / 0.5144

    /** Where the compass would put the wind first thing in a new world. */
    private const val BASE = 215.0
}
