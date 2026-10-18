package io.github.vaspyyy.guildmark.economy.fortune

import net.minecraft.server.level.ServerLevel
import java.util.UUID

/** How a capital is faring (living-world/design-influence.md section 4), from hard times to thriving. */
enum class Times(val id: String) { HARD("hard"), LEAN("lean"), STEADY("steady"), GOOD("good"), THRIVING("thriving") }

/**
 * Something that weighs on a capital's fortunes: its [kind], [points] for or against, until market day [until] (-1
 * while it holds), its words' [args], the players it's [about], and the works that eased it ([works], or -1).
 */
class Reason(val kind: String, val points: Int, val until: Long, val args: List<String>, val about: List<UUID>, val works: Int)

/** A capital's fortunes on market day [day]: its [score] and the [times] it made. */
class FortuneDay(val day: Long, val score: Int, val times: Times)

/**
 * A capital's good times and hard (living-world/design-influence.md section 4): its score from what really happened
 * to it, and the times that follow. The API between M6's parts (section 10). Until Part C fills it in, every capital
 * is STEADY with no reasons.
 */
object Fortunes {
    /** Whether the times are worked out (townFortunes, with the markets). */
    fun enabled(): Boolean = false

    /** The times in the capital whose heart is [capital]. */
    fun times(level: ServerLevel, capital: Long): Times = Times.STEADY

    /** [capital]'s score, -10 to 10. */
    fun score(level: ServerLevel, capital: Long): Int = 0

    /** What weighs on [capital] now, biggest first. */
    fun reasons(level: ServerLevel, capital: Long): List<Reason> = listOf()

    /** [capital]'s last 14 days, oldest first. */
    fun days(level: ServerLevel, capital: Long): List<FortuneDay> = listOf()

    /** The market day [capital]'s times last moved, or -1. */
    fun since(level: ServerLevel, capital: Long): Long = -1L

    /** Something weighs on [capital]: [kind] for [points] over [days] market days. */
    fun note(
        level: ServerLevel, capital: Long, kind: String, points: Int, days: Int,
        args: List<String> = listOf(), about: UUID? = null, works: Int = -1,
    ) {}

    /** The crowds' share in [capital] as the times go, 0.7 to 1.25. */
    fun busy(level: ServerLevel, capital: Long): Double = 1.0

    /** What [capital] spends on wares and ale as the times go, 0.92 to 1.05. */
    fun spend(level: ServerLevel, capital: Long): Double = 1.0

    private val listeners = ArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    /** Hears a capital's times move. */
    interface Listener {
        fun changed(level: ServerLevel, capital: Long, from: Times, to: Times)
    }
}
