package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.townsfolk.Comings
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.IEventBus
import java.util.UUID

/**
 * The Carriers' Store (living-world/trader/design.md, Part 5; build part F2): a trader's own works in a capital, raised from their Marks by
 * the town's builders as a stall of one's own is. What it does for the ladder is here: a Store that stands is written into its owner's
 * [TradeBooks.Book] (which [Rungs] reads for the Factor's asks and [Tally] for the town's Word), and one taken down is struck out.
 *
 * Reading never writes: [closing] looks at the books with [TradeBooks.peek], so a world nobody has traded in grows no `trade_books.dat`.
 */
object Stores {
    /** Registers the Store's and the Counting House's part (build part F2): their fittings' blocks and their daily life. */
    fun register(modBus: IEventBus) {
        TradeBlocks.register(modBus)
        StoreLife.register()
    }

    /** [owner]'s Store in the capital whose heart is [capital] has opened (its works finished): known as [name] in their [colour], its door at [origin] facing [facing]. */
    fun opening(level: ServerLevel, capital: Long, owner: UUID, name: String, colour: Int, origin: BlockPos, facing: Direction) {
        val books = TradeBooks.get(level)
        books.of(owner).addStore(BlockPos.of(capital))
        books.changed()
        level.server.playerList.getPlayer(owner)?.let { Rungs.check(level, it) }
    }

    /** [owner]'s Store in [capital] is gone (its works taken down): struck out of their book. */
    fun closing(level: ServerLevel, capital: Long, owner: UUID) {
        val books = TradeBooks.peek(level) ?: return
        val book = books[owner] ?: return
        if (!book.hasStore(BlockPos.of(capital))) return
        book.removeStore(BlockPos.of(capital))
        books.changed()
    }

    /** The line the Works screen gives a standing Store, from what it really holds (none while nothing is bonded). */
    fun doing(level: ServerLevel, capital: Long, owner: UUID?): Component = Component.empty()

    /** What `works` says of a standing Carriers' Store: whether the clerk it sent for has come. */
    internal fun describe(level: ServerLevel, w: WorksRec): String =
        "a Carriers' Store" + if (Comings.enabled() && w.hired < 0) "; its clerk hasn't come yet" else ""
}
