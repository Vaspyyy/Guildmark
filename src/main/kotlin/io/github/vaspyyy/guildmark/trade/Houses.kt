package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.townsfolk.Comings
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * The Counting House (living-world/trader/design.md, Part 5; the building is build part F2's, what it does is G's): a trader's second
 * works in a capital where they already have a Store. Here F2 keeps it in the owner's [TradeBooks.Book], where [Rungs] reads it for the
 * trading house's asks; part G fills in the rest (the purse, the carters and the trips) beside these two.
 */
object Houses {
    /** [owner]'s Counting House in the capital whose heart is [capital] has opened (its works finished): known as [name] in their [colour], its door at [origin] facing [facing]. */
    fun opening(level: ServerLevel, capital: Long, owner: UUID, name: String, colour: Int, origin: BlockPos, facing: Direction) {
        val books = TradeBooks.get(level)
        books.of(owner).addHouse(BlockPos.of(capital))
        books.changed()
        level.server.playerList.getPlayer(owner)?.let { Rungs.check(level, it) }
    }

    /** [owner]'s Counting House in [capital] is gone (its works taken down): struck out of their book. */
    fun closing(level: ServerLevel, capital: Long, owner: UUID) {
        val books = TradeBooks.peek(level) ?: return
        val book = books[owner] ?: return
        if (!book.hasHouse(BlockPos.of(capital))) return
        book.removeHouse(BlockPos.of(capital))
        books.changed()
    }

    /** The line the Works screen gives a standing Counting House: the House keeps no books until part G fills it in. */
    fun doing(level: ServerLevel, capital: Long, owner: UUID?): Component = Component.translatable("gui.guildmark.works.doing.counting_house")

    /** What `works` says of a standing Counting House: whether the scribe it sent for has come. */
    internal fun describe(level: ServerLevel, w: WorksRec): String =
        "a Counting House" + if (Comings.enabled() && w.hired < 0) "; its scribe hasn't come yet" else ""
}
