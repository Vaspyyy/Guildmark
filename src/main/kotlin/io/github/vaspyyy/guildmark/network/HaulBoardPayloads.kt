package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.trade.HaulBoardLink
import io.github.vaspyyy.guildmark.trade.HaulBoardViews
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.Optional

/*
 * What the Haul Board (living-world/trader/design.md, Part 1; build part C) sends between the server and its screen. The server works out everything the
 * screen shows, from the Word, the haul tickets and the reader's Tally as they stand, and the screen only draws it: a time is the overworld's clock in game
 * ticks (a thousand to the hour, as the Word keeps it), so the screen can go on ageing what it was told between one payload and the next. The tickets a town
 * was heard to have out are the Tally's own [TallyTicket]s.
 */

/**
 * A sight of a town as the screen sets it out: the clock tick its news is of ([tick]; news only as old as the delay allows, the Word's first, has none and an
 * [age] in ticks instead, else [age] is -1), the goods it was short of and had too much of (by id, the worst first), the stage of its worst road (0 open to 3
 * closed, -1 not kept) and the [side] it comes in from, its [notes] of trouble and luck, and how it was come by ([from], a
 * [io.github.vaspyyy.guildmark.trade.Seen] source: 1 is a board's).
 */
data class BoardSight(
    val tick: Long, val age: Long, val shorts: List<String>, val gluts: List<String>, val road: Int, val side: String, val notes: List<TallyNote>, val from: Int,
) {
    companion object {
        private val GOODS: StreamCodec<ByteBuf, List<String>> = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())

        val STREAM_CODEC: StreamCodec<ByteBuf, BoardSight> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BoardSight::tick,
            ByteBufCodecs.VAR_LONG, BoardSight::age,
            GOODS, BoardSight::shorts,
            GOODS, BoardSight::gluts,
            ByteBufCodecs.VAR_INT, BoardSight::road,
            ByteBufCodecs.STRING_UTF8, BoardSight::side,
            TallyNote.STREAM_CODEC.apply(ByteBufCodecs.list()), BoardSight::notes,
            ByteBufCodecs.VAR_INT, BoardSight::from,
            ::BoardSight,
        )
    }
}

/**
 * One town of the slate, as the reader reads it: the capital's key and [name] ("" while it has none), its [distance] in blocks, who brought the news if a report
 * did ([by]), the [board]'s sight of it, the tickets it was [heard] to have out, and the sight the reader's own Tally had of it when they came to the board
 * ([held]; none if it had none, or they carry no Tally).
 */
data class BoardTown(
    val capital: Long, val name: String, val distance: Int, val by: String, val board: BoardSight, val heard: List<TallyTicket>, val held: Optional<BoardSight>,
) {
    /** The Tally's sight, if there was one. */
    val tally: BoardSight? get() = held.orElse(null)

    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, BoardTown> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BoardTown::capital,
            ByteBufCodecs.STRING_UTF8, BoardTown::name,
            ByteBufCodecs.VAR_INT, BoardTown::distance,
            ByteBufCodecs.STRING_UTF8, BoardTown::by,
            BoardSight.STREAM_CODEC, BoardTown::board,
            TallyTicket.STREAM_CODEC.apply(ByteBufCodecs.list()), BoardTown::heard,
            ByteBufCodecs.optional(BoardSight.STREAM_CODEC), BoardTown::held,
            ::BoardTown,
        )
    }
}

/** A ticket this town has out, as the reader's screen shows it: the [shop] (its id) it is to be brought to, and the ticket as the shop's own screen has it. */
data class BoardHere(val shop: String, val haul: MarketHaul) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, BoardHere> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BoardHere::shop,
            MarketHaul.STREAM_CODEC, BoardHere::haul,
            ::BoardHere,
        )
    }
}

/**
 * Everything the board's screen shows: where the [board] stands (its anchor, packed), the [town] it stands in, the clock then ([now]), whether the Word and
 * the haul tickets are on, whether the reader carries a Tally ([tally]) and how many of its sights the reading made newer ([copied]), the colour of the
 * reader's own seal ([seal], 0 to 5), the towns of the slate, nearest first, and this town's own tickets ([here]), the reader's first.
 */
data class BoardView(
    val board: Long, val town: String, val now: Long, val word: Boolean, val hauls: Boolean, val tally: Boolean, val copied: Int, val seal: Int,
    val towns: List<BoardTown>, val here: List<BoardHere>,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, BoardView> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BoardView::board,
            ByteBufCodecs.STRING_UTF8, BoardView::town,
            ByteBufCodecs.VAR_LONG, BoardView::now,
            ByteBufCodecs.BOOL, BoardView::word,
            ByteBufCodecs.BOOL, BoardView::hauls,
            ByteBufCodecs.BOOL, BoardView::tally,
            ByteBufCodecs.VAR_INT, BoardView::copied,
            ByteBufCodecs.VAR_INT, BoardView::seal,
            BoardTown.STREAM_CODEC.apply(ByteBufCodecs.list()), BoardView::towns,
            BoardHere.STREAM_CODEC.apply(ByteBufCodecs.list()), BoardView::here,
            ::BoardView,
        )
    }
}

/**
 * Server sends the board's screen what it shows: [open] opens it (or, if it is open already, brings it up to date), else it is the answer to the screen's
 * own question and only brings the open screen up to date.
 */
data class HaulBoardPayload(val view: BoardView, val open: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HaulBoardPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<HaulBoardPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "haul_board"))

        val STREAM_CODEC: StreamCodec<ByteBuf, HaulBoardPayload> = StreamCodec.composite(
            BoardView.STREAM_CODEC, HaulBoardPayload::view,
            ByteBufCodecs.BOOL, HaulBoardPayload::open,
            ::HaulBoardPayload,
        )

        fun handle(payload: HaulBoardPayload, context: IPayloadContext) {
            HaulBoardLink.receive(payload.view, payload.open)
        }
    }
}

/**
 * Client's board screen asks the server to do [action] ([REFRESH] or [BOOK]) about the ticket numbered [id]: the open screen asks for fresh news every two
 * seconds, and its Book button books a ticket, this town's or one a town was heard to have out, exactly as the shop's Take button and the Tally's Book do.
 * The server decides all of it, and answers with a fresh [HaulBoardPayload].
 */
data class HaulBoardActPayload(val action: Int, val id: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HaulBoardActPayload> = TYPE

    companion object {
        const val REFRESH = 0
        const val BOOK = 1

        val TYPE: CustomPacketPayload.Type<HaulBoardActPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "haul_board_act"))

        val STREAM_CODEC: StreamCodec<ByteBuf, HaulBoardActPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HaulBoardActPayload::action,
            ByteBufCodecs.VAR_INT, HaulBoardActPayload::id,
            ::HaulBoardActPayload,
        )

        fun handle(payload: HaulBoardActPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            HaulBoardViews.act(player, payload.action, payload.id)
        }
    }
}
