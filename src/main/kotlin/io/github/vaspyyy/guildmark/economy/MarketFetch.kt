package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.quest.GoodsPrices
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import kotlin.math.floor

/**
 * Fetch notes on a capital's own quest board for the goods its market trades. The board asks for one only while the
 * market is short of it (it holds less than [SHORT] of what the town likes to keep), and pays at most
 * [GoodsPrices.MAX_SHARE] of what the market asks for the goods, bonuses and all, so buying them at the stalls to
 * hand in never pays. Fetches for anything else, and every fetch at a village, are left to [GoodsPrices].
 */
object MarketFetch {
    /** Short of a good: holding less than this share of what the town likes to keep of it. */
    const val SHORT = 0.5

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

    /** The most a fetch of [count] [item] posted on the board at [board] may pay now, or null if it isn't one of its market's goods. */
    fun cap(level: ServerLevel, board: BlockPos, item: Identifier, count: Int): Int? {
        val entry = at(level, board) ?: return null
        val good = good(item) ?: return null
        return floor(entry.market.price(entry.town, good) * count * GoodsPrices.MAX_SHARE).toInt()
    }
}
