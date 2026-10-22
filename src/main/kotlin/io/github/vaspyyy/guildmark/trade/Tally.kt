package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.network.TallyActPayload
import io.github.vaspyyy.guildmark.network.TallyPayload
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.min

/**
 * A sight in a trader's Tally (living-world/trader/design.md, Part 1): one capital, as [sight] has it, which the trader [from] (one of
 * [VISIT] and the rest) got when the overworld's clock stood at [copied], and the capital's [name] as it was then. A Tally keeps one for each
 * capital, up to [Tally.MOST]; they are kept in the player's record ([TradeBooks.Book.sights]), not in the item, so a Tally that is lost
 * loses nothing and a new one is as full as the old.
 */
class Seen(val capital: Long, val name: String, val copied: Long, val from: Int, val sight: Sight) {
    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putLong("capital", capital)
        tag.putString("name", name)
        tag.putLong("copied", copied)
        tag.putInt("from", from)
        tag.put("sight", sight.toTag())
        return tag
    }

    companion object {
        /** The trader stood in the town (opened the Tally there, or stood in its square for a minute). */
        const val VISIT = 0

        /** Copied from a Haul Board (part C), or from the board's slate by the command that stands in for reading one. */
        const val BOARD = 1

        /** A stall or a Store of theirs keeps the town's Word live whenever the Tally is opened. */
        const val LIVE = 2

        /** Set by an operator's command (`trade sight`), for testing. */
        const val SET = 3

        fun fromTag(tag: CompoundTag): Seen? {
            val sight = Sight.fromTag(tag.getCompoundOrEmpty("sight")) ?: return null
            return Seen(tag.getLongOr("capital", 0L), tag.getStringOr("name", ""), tag.getLongOr("copied", 0L), tag.getIntOr("from", VISIT), sight)
        }
    }
}

/**
 * The Tally, the trader's book (living-world/trader/design.md, Part 1): what a trader has seen of the other towns, which they carry, read
 * and file reports from. This is the book's side on the server: the sights, how they are kept, how a trader gets one, the item's handing
 * out, what the screen is told ([TallyViews]) and the reports a trader files.
 *
 * A sight is the freshest news the trader has of a town. One they get by standing in the town is as new as the moment they stood there;
 * one they copy from a board is as old as that row, and replaces the sight they had only if it is newer. At most eight are kept; the stalest
 * goes when a ninth town is seen. At three days a sight is struck through on the page, and stays until something replaces it.
 *
 * With `tradeWord` off nothing of the Word is read or written: the book keeps what the trader has seen in the town they stand in, and nothing
 * else (no board is copied, no stall or Store keeps a town live, no report is filed).
 */
object Tally {
    /** The most sights a Tally keeps. */
    const val MOST = 8

    /** The reports a trader may file in a day, how much fresher than the board's row a sight must be to be worth filing, and what a report earns: points of standing at the town and Trade XP. */
    const val REPORTS = 3
    const val FRESHER = 6L * Word.HOUR
    const val STANDING = 2.0
    const val XP = 4.0

    /** The least game ticks between two askings of one open screen, so that a screen (or a client that is not one) cannot make the server work out its page over and over. */
    private const val ASKED = 40L

    /** The most reports the Word keeps in all, oldest dropped first (each is a sight of about 120 bytes). */
    private const val FILED = 96

    // ---- The sights ----

    /** [player]'s sights, newest first, read from their record without making one. */
    fun seen(level: ServerLevel, player: UUID): List<Seen> {
        val book = peekBooks(level)?.get(player) ?: return listOf()
        return book.sights.mapNotNull(Seen::fromTag).sortedByDescending { it.sight.tick }
    }

    /** [player]'s sight of the capital whose heart is [capital], if they have one. */
    fun seen(level: ServerLevel, player: UUID, capital: Long): Seen? = seen(level, player).firstOrNull { it.capital == capital }

    /**
     * Puts [seen] in [player]'s Tally: in place of the sight of that capital they have, if it is newer (or if [force] says to replace it whatever it is),
     * else as a new one, putting out the stalest if they have [MOST] already. Gives whether the Tally changed.
     */
    fun note(level: ServerLevel, player: ServerPlayer, seen: Seen, force: Boolean = false): Boolean {
        val books = TradeBooks.get(level)
        val book = books.of(player.uuid)
        val list = book.sights
        val at = list.indexOfFirst { it.getLongOr("capital", Long.MIN_VALUE) == seen.capital }
        if (at >= 0) {
            val was = list[at].getCompoundOrEmpty("sight").getLongOr("tick", Long.MIN_VALUE)
            if (!force && was >= seen.sight.tick) return false
            list[at] = seen.toTag()
        } else {
            if (list.size >= MOST) {
                val stalest = list.indices.minByOrNull { list[it].getCompoundOrEmpty("sight").getLongOr("tick", Long.MIN_VALUE) }
                if (stalest != null) list.removeAt(stalest)
            }
            list.add(seen.toTag())
        }
        books.changed()
        return true
    }

    /**
     * [player] sees the capital whose heart is [capital] for themselves, now: a sight as new as the moment, from its market as it stands, kept as
     * [from] says. Nothing if that capital's market has not opened. Gives the sight (whether or not it replaced an older one, which it always does).
     * A trader who stands there ([from] [Seen.VISIT]) may have its name looked for on the ground; one who only keeps a stall or a Store there may not.
     */
    fun visit(level: ServerLevel, player: ServerPlayer, capital: BlockPos, from: Int = Seen.VISIT): Seen? {
        val seen = look(level, capital, from, from == Seen.VISIT) ?: return null
        Word.named(level, capital.asLong(), seen.name)
        note(level, player, seen)
        return seen
    }

    /** The sight of the capital whose heart is [capital] as it stands this moment, as [from] says; not kept anywhere. */
    fun look(level: ServerLevel, capital: BlockPos, from: Int, ground: Boolean): Seen? {
        val entry = Markets.find(level, capital.asLong()) ?: return null
        val m = entry.market
        if (m.hour == Long.MIN_VALUE) return null
        // A market that is behind the clock is seen as far as it has got
        val now = level.overworldClockTime
        val tick = min(now, m.hour * Word.HOUR + Word.HOUR - 1)
        val sight = Sight.of(level, entry, tick, Word.tickets(level, capital))
        val name = Word.nameOf(level, capital.asLong()).ifEmpty { Word.found(level, capital.asLong(), ground) }
        return Seen(capital.asLong(), name, now, from, sight)
    }

    /**
     * Copies the slate of the board at the capital whose heart is [viewer] into [player]'s Tally, as [player] reads it: each row in place of the
     * sight they have of that town if it is newer. This is what reading a Haul Board does (part C); the command `trade tally copy` stands
     * in for reading one until it is there. Gives how many sights changed.
     */
    fun copyBoard(level: ServerLevel, player: ServerPlayer, viewer: BlockPos): Int {
        var changed = 0
        val now = level.overworldClockTime
        for (row in Word.view(level, viewer, player)) {
            if (note(level, player, Seen(row.capital.asLong(), row.name, now, Seen.BOARD, row.sight.stamped(row.tick)))) changed++
        }
        if (changed > 0) player.level().playSound(null, player.blockPosition(), TradeSounds.get("trade.tally_notch"), SoundSource.PLAYERS, 0.7f, 1f)
        return changed
    }

    // ---- The book itself ----

    /** Whether [player] carries a Tally. */
    fun has(player: ServerPlayer): Boolean {
        val item = TallyItems.TALLY.get()
        val pack = player.inventory
        for (i in 0 until pack.containerSize) if (pack.getItem(i).item === item) return true
        return false
    }

    /**
     * Hands [player] a Tally (the hall's merchant [by] says a line over it, if one is there), and takes them as having taken up the trade. A lost one
     * is given again the same way, free. It goes into their pack, or drops at their feet if the pack is full.
     */
    fun give(level: ServerLevel, player: ServerPlayer, by: Townsperson? = null) {
        val books = TradeBooks.get(level)
        val book = books.of(player.uuid)
        if (!book.started) {
            book.started = true
            books.changed()
        }
        player.inventory.placeItemBackInInventory(ItemStack(TallyItems.TALLY.get()), Prediction.SERVER_ONLY)
        level.playSound(null, player.blockPosition(), TradeSounds.get("trade.tally_page"), SoundSource.PLAYERS, 0.8f, 1f)
        by?.say("trade_tally_${player.random.nextInt(2)}", force = true)
    }

    /** Opens [player]'s Tally: their own sight of where they stand and of the towns they keep live made new, then the screen. */
    fun open(level: ServerLevel, player: ServerPlayer) {
        if (!TradeConfig.life()) {
            player.sendSystemMessage(Component.translatable(Wares.OFF).withStyle(ChatFormatting.GRAY))
            return
        }
        val books = TradeBooks.get(level)
        val book = books.of(player.uuid)
        if (!book.started) {
            book.started = true
            books.changed()
        }
        val here = Furnish.capitalAt(level, player.blockPosition())?.center
        if (here != null) visit(level, player, here)
        if (TradeConfig.word()) {
            for (key in owned(level, player)) if (key != here?.asLong()) visit(level, player, BlockPos.of(key), Seen.LIVE)
        }
        player.level().playSound(null, player.blockPosition(), TradeSounds.get("trade.tally_open"), SoundSource.PLAYERS, 0.7f, 1f)
        PacketDistributor.sendToPlayer(player, TallyPayload(TallyViews.build(level, player), true))
    }

    // ---- Where a trader stands, and what they keep ----

    /** Server ticks between looks at where the traders stand (a beat after the markets' own run, which is at the start of each second). */
    private const val STAND = 100

    /** How long, in game ticks, a trader stands in a capital before their book takes a fresh sight of it (a minute). */
    const val DWELL = 1200L

    /** Where each trader has been standing: the capital and the game time they came to it (not kept). */
    private val dwelling = HashMap<UUID, Pair<Long, Long>>()

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % STAND != STAND / 10 || !TradeConfig.life()) return
        stand(server.overworld())
    }

    /** Gives the book of each trader who has stood a minute in a capital a fresh sight of it. Their own sightings go on with the Word off. */
    private fun stand(level: ServerLevel) {
        val books = peekBooks(level)
        val here = HashSet<UUID>()
        if (books != null) {
            val now = level.gameTime
            for (player in level.players()) {
                if (books[player.uuid]?.started != true) continue
                val capital = Furnish.capitalAt(level, player.blockPosition())?.center
                if (capital == null) {
                    dwelling.remove(player.uuid)
                    continue
                }
                here.add(player.uuid)
                val since = dwelling[player.uuid]
                if (since == null || since.first != capital.asLong()) {
                    dwelling[player.uuid] = capital.asLong() to now
                    continue
                }
                if (now - since.second < DWELL) continue
                dwelling[player.uuid] = capital.asLong() to now
                visit(level, player, capital)
            }
        }
        dwelling.keys.retainAll(here)
    }

    /**
     * The capitals whose Word is live to [player] the moment they open their Tally: where a stall of theirs stands (a rented table in the hall or a
     * stall of their own in the square) or they keep a Carriers' Store (design Part 1, "What the Word is worth to own").
     */
    fun owned(level: ServerLevel, player: ServerPlayer): List<Long> {
        val out = LinkedHashSet<Long>()
        for (hall in OwnStalls.get(level).all()) if (hall.of(player.uuid) >= 0 || hall.pitches.containsKey(player.uuid)) out.add(hall.capital)
        peekBooks(level)?.get(player.uuid)?.let { out.addAll(it.stores) }
        return out.toList()
    }

    // ---- What the screen asks ----

    /** When each open screen last asked (game time); kept in memory only. */
    private val asked = HashMap<UUID, Long>()

    /** The screen of [player] asks the server to do [action] ([TallyActPayload]'s REFRESH, REPORT or BOOK) about the capital whose key is [about]. */
    fun act(player: ServerPlayer, action: Int, about: Long) {
        val level = player.level() as? ServerLevel ?: return
        if (!TradeConfig.life() || !has(player)) return
        val now = level.gameTime
        when (action) {
            TallyActPayload.REFRESH -> {
                if (now - (asked[player.uuid] ?: Long.MIN_VALUE / 2) < ASKED) return
                asked[player.uuid] = now
            }
            TallyActPayload.REPORT -> tell(player, report(level, player, about))
            TallyActPayload.BOOK -> tell(player, book(level, player, about))
            else -> return
        }
        PacketDistributor.sendToPlayer(player, TallyPayload(TallyViews.build(level, player), false))
    }

    private fun tell(player: ServerPlayer, what: Reported) {
        player.sendSystemMessage(Component.translatable(what.key).withStyle(if (what == Reported.FILED) ChatFormatting.GREEN else ChatFormatting.GRAY))
    }

    private fun tell(player: ServerPlayer, what: TallyLinks.Booked) {
        player.sendSystemMessage(Component.translatable(what.key).withStyle(if (what == TallyLinks.Booked.DONE) ChatFormatting.GREEN else ChatFormatting.GRAY))
    }

    // ---- Booking from the Routes page ----

    /**
     * [player] asks to book the tickets of the route to the capital whose key is [about], as the Routes page last offered it. It is worked out again here
     * from what the book says now, and each ticket is booked as the shop's screen would book it ([TallyLinks.book]), the best paying first; the
     * first answer that is not a booking is what they are told. Nothing is booked while the haul tickets are not in.
     */
    private fun book(level: ServerLevel, player: ServerPlayer, about: Long): TallyLinks.Booked {
        if (!TallyLinks.HAULS || !TradeConfig.hauls()) return TallyLinks.Booked.NOT_YET
        val leg = TallyViews.leg(level, player, about) ?: return TallyLinks.Booked.GONE
        var done = false
        for (line in leg.lines) {
            for (serve in line.serves.sortedByDescending { it.ticket.each }) {
                val result = TallyLinks.book(level, player, serve.ticket.id, leg.to.capital, serve.units)
                if (result == TallyLinks.Booked.DONE) done = true else if (!done) return result
            }
        }
        return if (done) TallyLinks.Booked.DONE else TallyLinks.Booked.GONE
    }

    // ---- Reports ----

    /** How a report went: what the trader is told ([key], a lang key). */
    enum class Reported(val key: String) {
        /** Filed: the board of the capital they stand in has their sight for the row. */
        FILED("message.guildmark.trade.report.filed"),

        /** The Word is off, and with it the boards. */
        OFF("message.guildmark.trade.report.off"),

        /** They do not stand in a capital: a report is handed in at its board. */
        NOWHERE("message.guildmark.trade.report.nowhere"),

        /** That is the town they stand in: the board knows it as it is. */
        HERE("message.guildmark.trade.report.here"),

        /** Their book has no sight of that town. */
        UNKNOWN("message.guildmark.trade.report.unknown"),

        /** The board of the town they stand in keeps no row for it (it is too far, or it has no news yet). */
        NO_ROW("message.guildmark.trade.report.no_row"),

        /** Their sight is not fresher than the board's row by six hours. */
        STALE("message.guildmark.trade.report.stale"),

        /** They have filed three today. */
        TIRED("message.guildmark.trade.report.tired"),
    }

    /** How many reports [player] may still file today. */
    fun reportsLeft(level: ServerLevel, player: UUID): Int {
        val book = peekBooks(level)?.get(player) ?: return REPORTS
        return if (book.reportsDay == Days.dayOf(level.overworldClockTime)) (REPORTS - book.reportsToday).coerceAtLeast(0) else REPORTS
    }

    /**
     * [player] files their sight of the capital whose key is [about] at the board of the capital they stand in (living-world/trader/design.md,
     * Part 1, "Reports"): if it is fresher than that board's row for it by six hours or more, it becomes the row, for everyone, for as long as it is
     * fresher than the one the Word would give; and they are given [STANDING] points of standing at this capital and [XP] Trade XP. Three a day.
     * Nothing is changed unless it is filed.
     */
    fun report(level: ServerLevel, player: ServerPlayer, about: Long): Reported {
        if (!TradeConfig.word()) return Reported.OFF
        val here = Furnish.capitalAt(level, player.blockPosition())?.center ?: return Reported.NOWHERE
        if (about == here.asLong()) return Reported.HERE
        val seen = seen(level, player.uuid, about) ?: return Reported.UNKNOWN
        val row = Word.row(level, here, about, null) ?: return Reported.NO_ROW
        if (seen.sight.tick - row.tick < FRESHER) return Reported.STALE
        val books = TradeBooks.get(level)
        val book = books.of(player.uuid)
        val day = Days.dayOf(level.overworldClockTime)
        if (book.reportsDay != day) {
            book.reportsDay = day
            book.reportsToday = 0
        }
        if (book.reportsToday >= REPORTS) return Reported.TIRED
        book.reportsToday++
        books.changed()

        val data = TradeWord.get(level)
        val now = level.overworldClockTime
        data.reports.removeAll { it.at == here.asLong() && it.about == about }
        data.reports.add(TradeWord.Report(here.asLong(), about, seen.sight, player.uuid, player.name.string, now))
        while (data.reports.size > FILED) data.reports.removeAt(0)
        data.changed()

        if (Suppliers.enabled()) Suppliers.gain(level, here, player.uuid, STANDING)
        Skills.gain(player, Skill.TRADE, XP)
        level.playSound(null, player.blockPosition(), TradeSounds.get("trade.report_file"), SoundSource.PLAYERS, 0.8f, 1f)
        merchant(level, player)?.say("trade_report_${player.random.nextInt(2)}", force = true)
        return Reported.FILED
    }

    /** The hall's merchant nearest [player], within a few steps of them, if there is one: whoever takes a slip over the board. */
    private fun merchant(level: ServerLevel, player: ServerPlayer): Townsperson? =
        level.getEntitiesOfClass(Townsperson::class.java, AABB(player.blockPosition()).inflate(24.0)) { it.role == TownRole.MERCHANT && it.isAlive }
            .minByOrNull { it.distanceToSqr(player) }

    // ---- Setting up ----

    /** Registers the Tally: its item, its two payloads, its commands, the watch on where traders stand and what it keeps in memory. */
    fun register(modBus: IEventBus) {
        TallyItems.register(modBus)
        TallyCommand.init()
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToClient(TallyPayload.TYPE, TallyPayload.STREAM_CODEC, TallyPayload::handle)
                .playToServer(TallyActPayload.TYPE, TallyActPayload.STREAM_CODEC, TallyActPayload::handle)
        }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            asked.remove(event.entity.uuid)
            dwelling.remove(event.entity.uuid)
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            asked.clear()
            dwelling.clear()
        }
    }
}
