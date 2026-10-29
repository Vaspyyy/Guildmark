package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.block.HaulBoardBlock
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.network.BoardHere
import io.github.vaspyyy.guildmark.network.BoardSight
import io.github.vaspyyy.guildmark.network.BoardTown
import io.github.vaspyyy.guildmark.network.BoardView
import io.github.vaspyyy.guildmark.network.HaulBoardActPayload
import io.github.vaspyyy.guildmark.network.HaulBoardPayload
import io.github.vaspyyy.guildmark.network.TallyNote
import io.github.vaspyyy.guildmark.network.TallyTicket
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.Optional
import java.util.UUID

/**
 * What the Haul Board's screen is told and asked (living-world/trader/design.md, Part 1; build part C). Reading the board opens it: the towns of the
 * slate as the reader reads them ([Word.view] for this player), the sight their own Tally had of each when they came (reading the board then copies
 * the slate into the Tally: [Tally.copyBoard]), the tickets each town was heard to have out, and this town's own tickets, each with a Book button.
 *
 * Booking is the shop's Take and the Tally's Book: [Hauls.book] called as [TallyLinks.book] calls it, checked against the town itself now whatever the
 * screen was told, with the same deposit and the same refusals, and answered as the shop answers it (the booking's terms in chat, or why not); a ticket
 * that is gone by now is "Word was old: that one's gone", and nothing is taken. The hall's merchant at the board says their word over a booking.
 */
object HaulBoardViews {
    /** The least game ticks between two pages worked out for one open screen (but for the answer to a press), and between two presses that are looked at. */
    private const val ASKED = 40L
    private const val PRESSED = 10L

    /** How near the board a reader must stay for their screen to be answered, and how near it the merchant must be to say a word over a booking. */
    private const val REACH = 10.0
    private const val CLERK = 14.0

    /** The most goods named for what a town is short of, and for what it has too much of. */
    private const val GOODS = 6

    /** A reader at a board: where it is, the capital it stands in and its name, the reader's Tally as it was when they came, and when they last asked and pressed. */
    private class Reading(
        val anchor: BlockPos, val capital: BlockPos, val town: String, val held: Map<Long, Seen>, val copied: Int,
        var asked: Long = Long.MIN_VALUE / 2, var pressed: Long = Long.MIN_VALUE / 2,
    )

    private val reading = HashMap<UUID, Reading>()

    /** Registers the screen's two payloads, and forgets a reader who leaves. */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToClient(HaulBoardPayload.TYPE, HaulBoardPayload.STREAM_CODEC, HaulBoardPayload::handle)
                .playToServer(HaulBoardActPayload.TYPE, HaulBoardActPayload.STREAM_CODEC, HaulBoardActPayload::handle)
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { reading.remove(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> reading.clear() }
    }

    /**
     * [player] reads the board at [anchor], in the capital whose heart is [capital]: the slate goes into their Tally if they carry one, and the board's
     * screen opens, its foot showing what the Tally had before.
     */
    fun open(level: ServerLevel, player: ServerPlayer, anchor: BlockPos, capital: BlockPos) {
        val tally = Tally.has(player)
        val held = if (tally) Tally.seen(level, player.uuid).associateBy { it.capital } else mapOf()
        val copied = if (tally) Tally.copyBoard(level, player, capital) else 0
        val town = Word.nameOf(level, capital.asLong()).ifEmpty { Word.found(level, capital.asLong(), ground = true) }
        val r = Reading(anchor.immutable(), capital.immutable(), town, held, copied)
        r.asked = level.gameTime
        reading[player.uuid] = r
        send(level, player, r, open = true)
    }

    /** [player]'s screen asks for [action] about ticket [id]. */
    fun act(player: ServerPlayer, action: Int, id: Int) {
        val level = player.level() as? ServerLevel ?: return
        val r = reading[player.uuid] ?: return
        if (!TradeConfig.life() || player.distanceToSqr(r.anchor.x + 0.5, r.anchor.y + 1.0, r.anchor.z + 0.5) > REACH * REACH ||
            level.getBlockState(r.anchor).block !is HaulBoardBlock
        ) {
            reading.remove(player.uuid)
            return
        }
        val now = level.gameTime
        when (action) {
            HaulBoardActPayload.REFRESH -> if (now - r.asked < ASKED) return
            HaulBoardActPayload.BOOK -> {
                if (now - r.pressed < PRESSED) return
                r.pressed = now
                book(level, player, r, id)
            }
            else -> return
        }
        r.asked = now
        send(level, player, r, open = false)
    }

    private fun send(level: ServerLevel, player: ServerPlayer, r: Reading, open: Boolean) {
        PacketDistributor.sendToPlayer(player, HaulBoardPayload(build(level, player, r), open))
    }

    /** The page for [player] at [r]'s board, from the world as it stands. Makes nothing: every record is only looked at. */
    private fun build(level: ServerLevel, player: ServerPlayer, r: Reading): BoardView {
        val towns = Word.view(level, r.capital, player).map { row ->
            val s = row.sight
            val board = BoardSight(
                if (row.seeded) 0L else row.tick, if (row.seeded) (row.age * Word.HOUR).toLong() else -1L,
                s.shorts(GOODS).map { it.id }, s.gluts(GOODS).map { it.id }, s.road?.stage ?: -1, s.road?.side ?: "",
                s.events.map { TallyNote(it.kind, it.good, it.until * Word.HOUR) }, Seen.BOARD,
            )
            val mine = r.held[row.capital.asLong()]?.let { seen ->
                val t = seen.sight
                BoardSight(t.tick, -1L, t.shorts(GOODS).map { it.id }, t.gluts(GOODS).map { it.id }, t.road?.stage ?: -1, t.road?.side ?: "",
                    t.events.map { TallyNote(it.kind, it.good, it.until * Word.HOUR) }, seen.from)
            }
            val heard = s.tickets.sortedByDescending { it.pay }.map { TallyTicket(it.id, it.good.id, it.units, it.pay, it.shop.id, it.kind.id, it.due * Word.HOUR) }
            BoardTown(row.capital.asLong(), row.name, row.distance, row.by ?: "", board, heard, Optional.ofNullable(mine))
        }
        val here = ArrayList<BoardHere>()
        if (TradeConfig.hauls()) {
            val book = HaulData.peek(level)?.get(r.capital.asLong())
            for (shop in book?.tickets?.map { it.shop }?.distinct().orEmpty()) {
                for (haul in Hauls.view(level, player, r.capital, shop)) here.add(BoardHere(shop.id, haul))
            }
        }
        // Theirs first, then what they can take, the dearest first
        here.sortWith(compareBy({ if (it.haul.mine > 0) 0 else if (it.haul.why.isEmpty()) 1 else 2 }, { -it.haul.pay }, { it.haul.id }))
        return BoardView(
            r.anchor.asLong(), r.town, level.overworldClockTime, TradeConfig.word(), TradeConfig.hauls(), Tally.has(player), r.copied,
            Hauls.seal(player.uuid), towns, here,
        )
    }

    /**
     * [player] books ticket [id]: this town's, or one a town was heard to have out (which may be gone, or taken, by now). The booking is [TallyLinks.book]'s
     * call of [Hauls.book], answered as the shop's Take is: the terms in chat; "that one's gone" if it is gone (nothing taken); else why not.
     */
    private fun book(level: ServerLevel, player: ServerPlayer, r: Reading, id: Int) {
        when (val why = Hauls.book(level, id, Taker.Player(player.uuid), 0, player.gameProfile.name)) {
            null -> {
                val h = Hauls.find(level, id) ?: return
                val row = Hauls.view(level, player, h.capital, h.shop).firstOrNull { it.id == id } ?: return
                player.sendSystemMessage(
                    Component.translatable(
                        "message.guildmark.trade.haul.booked", row.mine, Component.translatable(h.good.key + ".many"), Orders.shopName(h.shop), place(level, h.capital),
                        Orders.marks(row.pay), Orders.marks(row.deposit), Hauls.due(row.hours),
                    ).withStyle(ChatFormatting.YELLOW),
                )
                // The hall's merchant, if they are at the board, says a word over it
                if (Words.on()) {
                    HaulClerk.merchants(level, r.anchor, r.capital, CLERK).firstOrNull()
                        ?.say("trade_book_${player.random.nextInt(2)}", force = true)
                }
            }
            "gone" -> player.sendSystemMessage(Component.translatable(TallyLinks.Booked.GONE.key).withStyle(ChatFormatting.GRAY))
            "off" -> player.sendSystemMessage(Component.translatable(TallyLinks.Booked.NOT_YET.key).withStyle(ChatFormatting.GRAY))
            else -> player.sendOverlayMessage(Component.translatable("gui.guildmark.trade.haul.refused.$why"))
        }
    }

    /** What the booking's terms call the capital whose heart is [capital]. */
    private fun place(level: ServerLevel, capital: BlockPos): String =
        Word.nameOf(level, capital.asLong()).ifEmpty { Word.found(level, capital.asLong(), ground = true) }
}
