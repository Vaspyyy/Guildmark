package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.talk.Fade
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumours
import io.github.vaspyyy.guildmark.townsfolk.talk.Scope
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Venue
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The two rumours the Word starts (living-world/trader/design.md, Part 1, "Rumours"): what a town is short of, and what it has too much of. They are
 * told only by [io.github.vaspyyy.guildmark.townsfolk.talk.sources.TradeTopics] ("About the trade..."), never as news or cried; the town's hall merchants
 * know them first, since they chalk the board.
 *
 * - `trade.short` [the good's key, =town]: a haul ticket was posted for a good the town is short of (heat 0.55, small, region scope: it goes to
 *   the other capitals at 400 blocks a day and arrives at 0.6 of its heat, so word of a shortage is three days old at 1,024 blocks).
 * - `trade.glut` [the same]: a good stood at 1.8 times what the town likes to keep when its snapshot was taken (heat 0.45, small, the town's own).
 *
 * The third, `trade.hauled`, is begun by the town's answer to a delivery (build part H), not here. With the Word, the tickets or the townsfolk's
 * rumours off nothing is started; a rumour already going round goes on as it did.
 */
object TradeRumours : Hauls.Listener, Word.Listener {
    /** How hot each kind begins: see above. */
    private const val SHORT_HEAT = 0.55f
    private const val GLUT_HEAT = 0.45f

    /** How many times its target a good's stock must be for the town to talk of the glut of it. */
    const val GLUT_AT = 1.8

    /** The most glut rumours one snapshot begins (the most glutted first), so that a town with its shelves full of everything does not crowd the talk. */
    private const val MOST = 2

    /** Subscribes to the Word's snapshots and to the tickets as they are posted; once, at setup. */
    fun register() {
        Word.listen(this)
        Hauls.listen(this)
    }

    /** A ticket was posted for a good the town is short of: it is the talk of its hall. */
    override fun posted(level: ServerLevel, haul: Haul) {
        if (!TradeConfig.hauls() || !Rumours.enabled()) return
        start(level, haul.capital, Rumours.TRADE_SHORT, haul.good, SHORT_HEAT, Scope.REGION, -1)
    }

    /** [entry]'s market was snapshotted: the goods it has far too much of are talked of. */
    override fun taken(level: ServerLevel, entry: Markets.Entry, sight: Sight) {
        if (!TradeConfig.word() || !Rumours.enabled()) return
        val glut = Good.TRADED.filter { sight.target(it) > 0.0 && sight.ratio(it) >= GLUT_AT }.sortedByDescending { sight.ratio(it) }.take(MOST)
        for (good in glut) start(level, entry.capital, Rumours.TRADE_GLUT, good, GLUT_HEAT, Scope.TOWN, 1)
    }

    /**
     * Begins (or heats, if it is going round already) a rumour of [kind] about [good] at the capital whose heart is [capital]: the town's name as its
     * second fact, if it has one yet. The hall's merchants know it first, from the board.
     */
    private fun start(level: ServerLevel, capital: BlockPos, kind: String, good: Good, heat: Float, scope: Scope, tone: Int) {
        val name = Word.nameOf(level, capital.asLong()).ifEmpty { Word.found(level, capital.asLong()) }
        val args = if (name.isEmpty()) listOf(good.key) else listOf(good.key, Witness.named(name))
        val knowers = Residents.get(level).of(capital).filter { it.alive && it.role == TownRole.MERCHANT }.map { it.id to Venue.MARKET }
        TownTalk.begin(level, capital, kind, null, -1, args, heat, Fade.SMALL, scope, tone, capital, Rumours.TOWN, knowers)
    }
}
