package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.lamps.StreetLamps
import net.minecraft.server.level.ServerLevel
import java.util.Locale

/**
 * The rationed lamps (M6 Part D2): a capital whose candles are short (under a quarter of what it likes to keep in, or
 * run out yesterday) has its lamplighters light every other lamp at dusk, saying so, the rest catching by themselves
 * a little before every lamp must be lit (lamps/StreetLamps, townsfolk/Lamplighters). Settled once a night as the
 * evening's rounds begin. Off with the times, but for `/guildmark streets lamps`'s tests (this session's).
 */
object Rationing : StreetLamps.Rationing {
    /** The streets command's rationed evenings, by capital column: the game time each lasts until. This session's only. */
    private val forced = HashMap<Long, Long>()

    override fun rationed(level: ServerLevel, capital: CapitalPiece): Boolean {
        val column = Streets.column(capital)
        val until = forced[column]
        if (until != null) {
            if (Streets.testing && level.gameTime < until) return true
            forced.remove(column)
        }
        return Fortunes.enabled() && short(level, capital)
    }

    /** Whether [capital]'s candles are short: under [LOW] of what it keeps in, or run short yesterday. */
    fun short(level: ServerLevel, capital: CapitalPiece): Boolean {
        val entry = Markets.get(level)[capital.center] ?: return false
        val m = entry.market
        return m.stock(Good.CANDLE) < entry.town.target(Good.CANDLE) * LOW || m.yesterday(Tally.SHORT, Good.CANDLE) > NONE
    }

    /** For the streets command: whether [capital]'s lamps are rationed and why, in a few words. */
    fun why(level: ServerLevel, capital: CapitalPiece): String {
        val column = Streets.column(capital)
        val until = forced[column]
        if (until != null && Streets.testing && level.gameTime < until) return "rationed by the streets command (${(until - level.gameTime) / 20}s left)"
        if (!Fortunes.enabled()) return "not rationed (the times are off)"
        val entry = Markets.get(level)[capital.center] ?: return "not rationed (no market open)"
        val m = entry.market
        val stock = m.stock(Good.CANDLE)
        val target = entry.town.target(Good.CANDLE)
        val short = m.yesterday(Tally.SHORT, Good.CANDLE)
        val said = String.format(Locale.ROOT, "candles %.1f of %.1f kept in, %.1f short yesterday", stock, target, short)
        return if (short(level, capital)) "rationed tonight: $said" else "not rationed: $said"
    }

    /** `/guildmark streets lamps`: [capital]'s lamps rationed for the next [ticks], whatever its candles. */
    fun force(level: ServerLevel, capital: CapitalPiece, ticks: Long) {
        Streets.testing = true
        forced[Streets.column(capital)] = level.gameTime + ticks
    }

    fun forget() = forced.clear()

    /** Under this share of what the town keeps in, its candles are short; and any shortfall yesterday above [NONE]. */
    private const val LOW = 0.25
    private const val NONE = 1e-6
}
