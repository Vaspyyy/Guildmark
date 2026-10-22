package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.trade.TallyClient
import io.github.vaspyyy.guildmark.trade.Tally
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/*
 * What the Tally (living-world/trader/design.md, Part 1; build part B) sends between the server and its screen. The server works out
 * everything the book shows, from its sights and the world as they stand, and the screen only draws it: a time is the overworld's clock in game ticks
 * (a thousand to the hour, as the Word keeps it), so the screen can go on ageing what it was told between one payload and the next.
 */

/** One good of one town as the Word page shows it: how many it held ([stock]) against what it likes to keep ([target]), the [band] that made (0 scarce to 4 glut) and what a unit went for against the usual price ([price]: 1 is the usual). */
data class TallyGood(val good: String, val stock: Int, val target: Int, val band: Int, val price: Float) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyGood> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TallyGood::good,
            ByteBufCodecs.VAR_INT, TallyGood::stock,
            ByteBufCodecs.VAR_INT, TallyGood::target,
            ByteBufCodecs.VAR_INT, TallyGood::band,
            ByteBufCodecs.FLOAT, TallyGood::price,
            ::TallyGood,
        )
    }
}

/** Trouble or luck a town's market had then: its [kind] ("blight", "fever", "feast"...), the [good] it fell on ("" for none) and the clock tick it was to last until. */
data class TallyNote(val kind: String, val good: String, val until: Long) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyNote> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TallyNote::kind,
            ByteBufCodecs.STRING_UTF8, TallyNote::good,
            ByteBufCodecs.VAR_LONG, TallyNote::until,
            ::TallyNote,
        )
    }
}

/** A haul ticket as a town had it out: number [id], [units] of [good] to the [shop] (its id) for [pay] Marks in all, of this [kind] (its id), due at the clock tick [due]. */
data class TallyTicket(val id: Int, val good: String, val units: Int, val pay: Int, val shop: String, val kind: String, val due: Long) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyTicket> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TallyTicket::id,
            ByteBufCodecs.STRING_UTF8, TallyTicket::good,
            ByteBufCodecs.VAR_INT, TallyTicket::units,
            ByteBufCodecs.VAR_INT, TallyTicket::pay,
            ByteBufCodecs.STRING_UTF8, TallyTicket::shop,
            ByteBufCodecs.STRING_UTF8, TallyTicket::kind,
            ByteBufCodecs.VAR_LONG, TallyTicket::due,
            ::TallyTicket,
        )
    }
}

/**
 * A capital in the Tally: its key ([capital], the packed position of its heart, which is also where it stands on the map), its [name], and the
 * sight the trader has of it: the clock tick it is of ([tick]), the tick the trader got it ([copied]), how they got it ([from]: 0 stood there,
 * 1 read from a board, 2 live from a stall or a Store, 3 set by an operator), whether it is [live] (the town they stand in now, or one it
 * is as fresh as that), the worst road into it ([road]: -1 not kept, else 0 open to 3 closed; [side] "n", "e", "s", "w" or "any"), and what the board
 * of the capital they stand in has for it ([board]: the tick of its row, or -1) so that a fresher sight can be [report]ed.
 */
data class TallyTown(
    val capital: Long,
    val name: String,
    val tick: Long,
    val copied: Long,
    val from: Int,
    val live: Boolean,
    val road: Int,
    val side: String,
    val board: Long,
    val report: Boolean,
    val goods: List<TallyGood>,
    val notes: List<TallyNote>,
    val tickets: List<TallyTicket>,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyTown> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TallyTown::capital,
            ByteBufCodecs.STRING_UTF8, TallyTown::name,
            ByteBufCodecs.VAR_LONG, TallyTown::tick,
            ByteBufCodecs.VAR_LONG, TallyTown::copied,
            ByteBufCodecs.VAR_INT, TallyTown::from,
            ByteBufCodecs.BOOL, TallyTown::live,
            ByteBufCodecs.VAR_INT, TallyTown::road,
            ByteBufCodecs.STRING_UTF8, TallyTown::side,
            ByteBufCodecs.VAR_LONG, TallyTown::board,
            ByteBufCodecs.BOOL, TallyTown::report,
            TallyGood.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyTown::goods,
            TallyNote.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyTown::notes,
            TallyTicket.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyTown::tickets,
            ::TallyTown,
        )
    }
}

/** Some of one ticket's units a line of a basket fills. */
data class TallyServe(val ticket: Int, val units: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyServe> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TallyServe::ticket,
            ByteBufCodecs.VAR_INT, TallyServe::units,
            ::TallyServe,
        )
    }
}

/** One good of a basket: [units] of [good] cost [cost] Marks where the trader stands and would sell for [pay] there, in [slots] slots of the pack, filling some tickets ([serves]). */
data class TallyLine(val good: String, val units: Int, val cost: Int, val pay: Int, val slots: Int, val serves: List<TallyServe>) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyLine> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TallyLine::good,
            ByteBufCodecs.VAR_INT, TallyLine::units,
            ByteBufCodecs.VAR_INT, TallyLine::cost,
            ByteBufCodecs.VAR_INT, TallyLine::pay,
            ByteBufCodecs.VAR_INT, TallyLine::slots,
            TallyServe.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyLine::serves,
            ::TallyLine,
        )
    }
}

/** A way the Routes page offers: to the capital [capital] ([name], [distance] blocks), [hours] there with the stay, buying the [lines] for [cost] to sell for [pay], [perHour] Marks an hour, on news of it from the clock tick [news]. */
data class TallyLeg(
    val capital: Long,
    val name: String,
    val distance: Int,
    val hours: Float,
    val cost: Int,
    val pay: Int,
    val perHour: Float,
    val news: Long,
    val lines: List<TallyLine>,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyLeg> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TallyLeg::capital,
            ByteBufCodecs.STRING_UTF8, TallyLeg::name,
            ByteBufCodecs.VAR_INT, TallyLeg::distance,
            ByteBufCodecs.FLOAT, TallyLeg::hours,
            ByteBufCodecs.VAR_INT, TallyLeg::cost,
            ByteBufCodecs.VAR_INT, TallyLeg::pay,
            ByteBufCodecs.FLOAT, TallyLeg::perHour,
            ByteBufCodecs.VAR_LONG, TallyLeg::news,
            TallyLine.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyLeg::lines,
            ::TallyLeg,
        )
    }
}

/**
 * The Routes page: the ways from the capital [from] ([name]; [live] if the trader stands in it, else it is as they last saw it, on the news of the clock tick [tick]), best an hour
 * first, for the [slots] free in the pack. [book] says whether the Book button works (the haul tickets are in and on), [locked] that the page is not
 * open to this trader yet (it opens at the Hawker's rung) and so lists no way.
 */
data class TallyRoutes(
    val from: Long,
    val name: String,
    val live: Boolean,
    val tick: Long,
    val slots: Int,
    val book: Boolean,
    val legs: List<TallyLeg>,
    val locked: Boolean = false,
) {
    companion object {
        /** No capital to start from. */
        const val NONE = Long.MIN_VALUE

        val EMPTY = TallyRoutes(NONE, "", false, 0L, 0, false, listOf())

        val STREAM_CODEC: StreamCodec<ByteBuf, TallyRoutes> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TallyRoutes::from,
            ByteBufCodecs.STRING_UTF8, TallyRoutes::name,
            ByteBufCodecs.BOOL, TallyRoutes::live,
            ByteBufCodecs.VAR_LONG, TallyRoutes::tick,
            ByteBufCodecs.VAR_INT, TallyRoutes::slots,
            ByteBufCodecs.BOOL, TallyRoutes::book,
            TallyLeg.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyRoutes::legs,
            ByteBufCodecs.BOOL, TallyRoutes::locked,
            ::TallyRoutes,
        )
    }
}

/** A ticket the trader holds booked: number [id] at the capital [capital] ([place]), [units] of [good] still to bring to the [shop] for [pay] Marks, due at the clock tick [due]; [carried] of them are with the trader and came far enough to count, which is what they could hand in now. */
data class TallyHeld(
    val id: Int,
    val capital: Long,
    val place: String,
    val shop: String,
    val good: String,
    val units: Int,
    val pay: Int,
    val kind: String,
    val due: Long,
    val carried: Int,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyHeld> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TallyHeld::id,
            ByteBufCodecs.VAR_LONG, TallyHeld::capital,
            ByteBufCodecs.STRING_UTF8, TallyHeld::place,
            ByteBufCodecs.STRING_UTF8, TallyHeld::shop,
            ByteBufCodecs.STRING_UTF8, TallyHeld::good,
            ByteBufCodecs.VAR_INT, TallyHeld::units,
            ByteBufCodecs.VAR_INT, TallyHeld::pay,
            ByteBufCodecs.STRING_UTF8, TallyHeld::kind,
            ByteBufCodecs.VAR_LONG, TallyHeld::due,
            ByteBufCodecs.VAR_INT, TallyHeld::carried,
            ::TallyHeld,
        )
    }
}

/** A good the held tickets still need ([units] of [good] more than could be handed in now) and where the Tally knows it cheapest: the capital [at] ([place]), [each] Marks a unit, [stock] held on the news of the clock tick [tick]; [at] is [TallyRoutes.NONE] if the book knows no town far enough from the tickets' to count. */
data class TallyNeed(val good: String, val units: Int, val at: Long, val place: String, val each: Float, val stock: Int, val tick: Long) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyNeed> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TallyNeed::good,
            ByteBufCodecs.VAR_INT, TallyNeed::units,
            ByteBufCodecs.VAR_LONG, TallyNeed::at,
            ByteBufCodecs.STRING_UTF8, TallyNeed::place,
            ByteBufCodecs.FLOAT, TallyNeed::each,
            ByteBufCodecs.VAR_INT, TallyNeed::stock,
            ByteBufCodecs.VAR_LONG, TallyNeed::tick,
            ::TallyNeed,
        )
    }
}

/**
 * Some units of a good the trader carries that were bought away from where they are: at the capital [origin] ([place], [distance] blocks from where they stand),
 * with [left] of the [of] days that keep them in the ledger still to run ([bonded]: put by in a Store, where they keep longer). They count as hauled
 * ([counts]; a stall pays the dealer's share for them) if they came as far as a haul is.
 */
data class TallyParcel(
    val good: String,
    val units: Int,
    val origin: Long,
    val place: String,
    val distance: Int,
    val left: Int,
    val of: Int,
    val bonded: Boolean,
    val counts: Boolean,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyParcel> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TallyParcel::good,
            ByteBufCodecs.VAR_INT, TallyParcel::units,
            ByteBufCodecs.VAR_LONG, TallyParcel::origin,
            ByteBufCodecs.STRING_UTF8, TallyParcel::place,
            ByteBufCodecs.VAR_INT, TallyParcel::distance,
            ByteBufCodecs.VAR_INT, TallyParcel::left,
            ByteBufCodecs.VAR_INT, TallyParcel::of,
            ByteBufCodecs.BOOL, TallyParcel::bonded,
            ByteBufCodecs.BOOL, TallyParcel::counts,
            ::TallyParcel,
        )
    }
}

/** One thing the next rung asks: its words ([key], a lang key), how far along it is ([have]) and what it takes ([need]). */
data class TallyAsk(val key: String, val have: Int, val need: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyAsk> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TallyAsk::key,
            ByteBufCodecs.VAR_INT, TallyAsk::have,
            ByteBufCodecs.VAR_INT, TallyAsk::need,
            ::TallyAsk,
        )
    }
}

/**
 * The Standing page: the [rung] they hold (0 Pedlar up to 4 the House), what the next rung [asks], the tickets they have filled ([filled]), and
 * their standing at the capital they stand in ([rank] 0 to 4 or -1 where there is none to tell, with [points] and the [next] rank's points, -1 at the top),
 * and how many tickets they may hold booked at once ([limit], their rung's).
 */
data class TallyStanding(val rung: Int, val asks: List<TallyAsk>, val filled: Int, val rank: Int, val points: Int, val next: Int, val limit: Int = 2) {
    companion object {
        val EMPTY = TallyStanding(0, listOf(), 0, -1, 0, -1)

        val STREAM_CODEC: StreamCodec<ByteBuf, TallyStanding> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TallyStanding::rung,
            TallyAsk.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyStanding::asks,
            ByteBufCodecs.VAR_INT, TallyStanding::filled,
            ByteBufCodecs.VAR_INT, TallyStanding::rank,
            ByteBufCodecs.VAR_INT, TallyStanding::points,
            ByteBufCodecs.VAR_INT, TallyStanding::next,
            ByteBufCodecs.VAR_INT, TallyStanding::limit,
            ::TallyStanding,
        )
    }
}

/**
 * Everything the Tally's five pages show, as the server worked it out at the clock tick [now]: the capital the trader stands in ([here], [TallyRoutes.NONE]
 * if none; [place] its name), whether the Word is on ([word]; off, the book holds only what they have seen themselves), how many reports they may
 * still file today ([reports]), the [towns] of their book (the town they stand in first, then the freshest news first), the [routes] from where they are,
 * the tickets they [held] and what those [needs], the hauled goods they carry ([parcels]) and where they [standing] are.
 */
data class TallyView(
    val now: Long,
    val here: Long,
    val place: String,
    val word: Boolean,
    val reports: Int,
    val towns: List<TallyTown>,
    val routes: TallyRoutes,
    val held: List<TallyHeld>,
    val needs: List<TallyNeed>,
    val parcels: List<TallyParcel>,
    val standing: TallyStanding,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TallyView> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TallyView::now,
            ByteBufCodecs.VAR_LONG, TallyView::here,
            ByteBufCodecs.STRING_UTF8, TallyView::place,
            ByteBufCodecs.BOOL, TallyView::word,
            ByteBufCodecs.VAR_INT, TallyView::reports,
            TallyTown.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyView::towns,
            TallyRoutes.STREAM_CODEC, TallyView::routes,
            TallyHeld.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyView::held,
            TallyNeed.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyView::needs,
            TallyParcel.STREAM_CODEC.apply(ByteBufCodecs.list()), TallyView::parcels,
            TallyStanding.STREAM_CODEC, TallyView::standing,
            ::TallyView,
        )
    }
}

/**
 * Server sends a trader's Tally screen what it shows: [open] opens it (or, if it is open already, brings it up to date), else it is the answer to the
 * screen's own question and only brings the open screen up to date.
 */
data class TallyPayload(val view: TallyView, val open: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TallyPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TallyPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tally"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TallyPayload> = StreamCodec.composite(
            TallyView.STREAM_CODEC, TallyPayload::view,
            ByteBufCodecs.BOOL, TallyPayload::open,
            ::TallyPayload,
        )

        fun handle(payload: TallyPayload, context: IPayloadContext) {
            TallyClient.receive(payload.view, payload.open)
        }
    }
}

/**
 * Client's Tally screen asks the server to do [action] (one of [REFRESH] and the rest) about the capital whose key is [about]: the open screen asks
 * for fresh news every few seconds, and the buttons ask to file a report or to book a route's tickets. The server decides all of it, and answers with
 * a fresh [TallyPayload]: no oftener than every two seconds, except to show what a press has just changed.
 */
data class TallyActPayload(val action: Int, val about: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TallyActPayload> = TYPE

    companion object {
        /** Brings the screen up to date; files the sight of [about] as a report at the board of the capital the trader stands in; books the tickets of the route to [about]. */
        const val REFRESH = 0
        const val REPORT = 1
        const val BOOK = 2

        val TYPE: CustomPacketPayload.Type<TallyActPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tally_act"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TallyActPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TallyActPayload::action,
            ByteBufCodecs.VAR_LONG, TallyActPayload::about,
            ::TallyActPayload,
        )

        fun handle(payload: TallyActPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Tally.act(player, payload.action, payload.about)
        }
    }
}
