package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.quest.GoodsPrices
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import kotlin.math.floor

/**
 * Fetch notes for the goods the markets trade. A capital's own quest board asks for one only while its market is
 * short of it (it holds less than [SHORT] of what the town likes to keep), and pays at most [GoodsPrices.MAX_SHARE]
 * of what the market asks for the goods, bonuses and all, so buying them at the stalls to hand in never pays. Any
 * other board's note for them is a carrier's errand: it pays at most what the nearest open market asks for them and
 * a carrier's quarter on top ([CARRIED]), so buying them there and carrying them out pays a little and no more.
 * Fetches for anything else are left to [GoodsPrices].
 */
object MarketFetch {
    /** Short of a good: holding less than this share of what the town likes to keep of it. */
    const val SHORT = 0.5

    /** Of what the nearest market asks for its goods, the most a note for them away from the capitals' own boards pays. */
    const val CARRIED = 1.25

    /** The market of the capital whose own quest board stands at [pos] (any of its cells), if it is one and the markets are running. */
    fun at(level: ServerLevel, pos: BlockPos): Markets.Entry? {
        if (!level.isLoaded(pos)) return null
        val state = level.getBlockState(pos)
        if (state.block !is NoticeBoardBlock) return null
        val anchor = NoticeBoardBlock.anchorPos(pos, state)
        val board = level.getBlockEntity(anchor) as? QuestBoardBlockEntity ?: return null
        if (!board.capital) return null
        val capital = Furnish.capitalAt(level, anchor) ?: return null
        return Markets.of(level, capital)
    }

    /** The traded good [item] is, if it is one. */
    fun good(item: Identifier): Good? = Good.entries.firstOrNull { g -> g.traded && g.item?.let { BuiltInRegistries.ITEM.getKey(it) } == item }

    /** Whether [entry]'s market is short of [good]. */
    fun short(entry: Markets.Entry, good: Good): Boolean = entry.market.stock(good) < entry.town.target(good) * SHORT

    /** What a capital's market asks for one of a good ([price]), and whether it's [short] of it. */
    class Ask(val price: Double, val short: Boolean)

    /** What [entry]'s market asks for one [item], and whether it's short of it; null if it doesn't trade it. */
    fun ask(entry: Markets.Entry, item: Identifier): Ask? {
        val good = good(item) ?: return null
        return Ask(entry.market.price(entry.town, good), short(entry, good))
    }

    /** The open market of the capital nearest [pos], with the markets on; null if none is open yet. */
    fun nearest(level: ServerLevel, pos: BlockPos): Markets.Entry? {
        if (!Markets.enabled()) return null
        return Markets.get(level).all().minByOrNull { e ->
            val dx = (e.capital.x - pos.x).toDouble()
            val dz = (e.capital.z - pos.z).toDouble()
            dx * dx + dz * dz
        }
    }

    /** What the market nearest [pos] asks for one [item], if it trades it: what a carrier's errand there is reckoned at. */
    fun errand(level: ServerLevel, pos: BlockPos, item: Identifier): Double? {
        val good = good(item) ?: return null
        val entry = nearest(level, pos) ?: return null
        val capital = entry.market.price(entry.town, good)
        // A board standing in a village whose market is open is reckoned at the lesser of the nearest capital's price and the village's
        // own, so goods bought at the village's counter and handed in on its board never pay more than a carrier's quarter over what
        // they cost there (and a village's shortage is never paid out as a relief fee; the villages design, section 18.5)
        val village = Villages.at(level, pos)?.let { VillageMarkets.find(level, it.key) } ?: return capital
        return minOf(capital, village.market.price(village.town, good))
    }

    /**
     * Whether [item] is one of the markets' goods while they're running: bought for Marks, so a fetch for it never
     * gives a relic cache.
     */
    fun sold(item: Identifier): Boolean = Markets.enabled() && good(item) != null

    /**
     * The most a fetch of [count] [item] posted on the board at [board] may pay now, or null if it isn't one of the
     * markets' goods: at a capital's own board half what its market asks, anywhere else what the nearest market asks
     * and a carrier's quarter, for the whole party: the one handing it in and their [mates] nearby, who each get a
     * share on top (see [Parties.sharePay]). Anywhere else only for a note [capped] by the carrier's quarter, as one is
     * posted now (see QuestNote.capped): one posted before it pays what it states.
     */
    fun cap(level: ServerLevel, board: BlockPos, item: Identifier, count: Int, mates: Int = 0, capped: Boolean = true): Int? {
        val good = good(item) ?: return null
        at(level, board)?.let { return floor(it.market.price(it.town, good) * count * GoodsPrices.MAX_SHARE).toInt() }
        if (!capped) return null
        val each = errand(level, board, item) ?: return null
        return floor(each * count * CARRIED / (1.0 + mates.toDouble() / Parties.PAY_SHARE)).toInt()
    }

    /**
     * The most the whole party may be paid in all for a fetch of [count] [item] posted on the board at [board], if it is
     * a carrier's errand there (see [cap]): what a lone carrier would be paid, the one handing it in and their mates
     * together. Null if it isn't one.
     */
    fun partyCap(level: ServerLevel, board: BlockPos, item: Identifier, count: Int): Int? {
        if (good(item) == null || at(level, board) != null) return null
        val each = errand(level, board, item) ?: return null
        return floor(each * count * CARRIED).toInt()
    }
}
