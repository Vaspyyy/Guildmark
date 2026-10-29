package io.github.vaspyyy.guildmark.economy.fortune

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity.Slip
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.MarketBoards
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/** How a capital is faring (living-world/design-influence.md section 4), from hard times to thriving. */
enum class Times(val id: String) { HARD("hard"), LEAN("lean"), STEADY("steady"), GOOD("good"), THRIVING("thriving") }

/**
 * Something that weighs on a capital's fortunes: its [kind], [points] for or against, until market day [until] (-1
 * while it holds), its words' [args], the players it's [about], and the works that eased it ([works], or -1).
 *
 * [until] is the last market day whose close counts it: a reason of "the day" lasts only the day it was read on,
 * and one noted on day D for N days counts at the closes of D+1 to D+N. Its words are each kind's own (see
 * [FortuneReasons.text], which says any reason in a sentence).
 */
class Reason(val kind: String, val points: Int, val until: Long, val args: List<String>, val about: List<UUID>, val works: Int)

/** A capital's fortunes on market day [day]: its [score] and the [times] it made. */
class FortuneDay(val day: Long, val score: Int, val times: Times)

/**
 * A capital's good times and hard (living-world/design-influence.md section 4): its score from what really happened
 * to it, and the times that follow. The API between M6's parts (section 10).
 *
 * Once a day, at the market's close (two in the morning, [day], from Markets.step), each capital adds its reasons up
 * (see [FortuneReasons]): what the market, the road, the calendar and the works hold, and what's been noted since
 * the close before ([note]). The score is their sum, kept to -10..10, and the times move one step a day toward the
 * times it calls for ([called]). A market catching up closes each of its days in turn, so it comes out as it would
 * have day by day, and one run on ahead of the clock (`market run`) closes the fortunes' next days as it goes. A day
 * already closed isn't closed twice: with the clock set back, or after a run, the fortunes wait for the clock to come
 * round to the days they haven't closed, and a note counts at the next closes, whichever days they are ([today]).
 *
 * Where it shows: the medallion at the head of the capital's market board turns to the times; the crier cries them
 * at noon when they've moved, with a FORTUNE slip ("Hard times", and its two biggest reasons), and while they aren't
 * steady; and the crier cries the town's works wanted, paid for and raised, each with its WORKS slip (see [cries]).
 * The crowds and the town's spending go by [busy] and [spend].
 */
object Fortunes {
    /**
     * Whether Part C is wired in. M6's parts were built side by side and are all switched on now; false would turn
     * them off again: nothing here runs and every capital is STEADY, so every game is as it was before M6.
     */
    private const val READY = true

    /** Days of fortunes kept, and the score's bounds. */
    const val DAYS = 14
    const val LOWEST = -10
    const val HIGHEST = 10

    /** Server ticks between looks at the boards of the capitals players are in. */
    private const val LOOK = 100

    /** A board's medallion is seen to turn by a player this near it (as the board's own "seen"), while the move is this fresh (ticks). */
    private const val SEEN = 48.0
    private const val FRESH = 600L

    /** The most works cried at a noon, and the works news kept for the crier per capital. */
    private const val WORKS_CRIED = 2
    private const val WORKS_KEPT = 12

    /** The works whose news is cried (a stall of a player's own never is), and what news it is, least first. */
    private val CRIED = setOf(WorksKind.OVEN.id, WorksKind.GRANARY.id, WorksKind.GREAT_GRANARY.id, WorksKind.TOWER.id)
    private val NEWS = listOf("wanted", "funded", "raised")

    /** Whether the times are worked out (townFortunes, with the markets). */
    fun enabled(): Boolean = READY && setting { GuildmarkConfig.TOWN_FORTUNES.get() } && Markets.enabled()

    /** The times in the capital whose heart is [capital]. */
    fun times(level: ServerLevel, capital: Long): Times = record(level, capital)?.times ?: Times.STEADY

    /** [capital]'s score, -10 to 10. */
    fun score(level: ServerLevel, capital: Long): Int = record(level, capital)?.score ?: 0

    /** What weighs on [capital] now, biggest first: its reasons at its last close. */
    fun reasons(level: ServerLevel, capital: Long): List<Reason> = record(level, capital)?.reasons?.toList() ?: listOf()

    /** [capital]'s last 14 days, oldest first. */
    fun days(level: ServerLevel, capital: Long): List<FortuneDay> = record(level, capital)?.days?.takeLast(DAYS) ?: listOf()

    /** The market day [capital]'s times last moved (at its close, two in the morning), or -1. */
    fun since(level: ServerLevel, capital: Long): Long = record(level, capital)?.since ?: -1L

    /** The times [capital]'s moved from when they last moved (at [since]); STEADY if they never have (M6 Part E: which way they went). */
    fun from(level: ServerLevel, capital: Long): Times = record(level, capital)?.from ?: Times.STEADY

    /**
     * Something weighs on [capital]: [kind] for [points] over [days] market days (counted at the next [days] closes),
     * with its words' [args], the player it's [about] and the works it's of. Nothing while the fortunes are off, nor
     * for a capital nobody has been to yet (no market open: it has no fortunes to weigh). A village's bell is a key as a
     * capital's heart is, once its market is open ([Markets.find]) and while Village Fortunes is on.
     *
     * A kind of section 4's table is added up by its rule (see [FortuneReasons.noted]). "supplied" is a player's
     * each: `note(level, capital, "supplied", 1, 3, about = player.uuid)` is all it takes (their name is looked up
     * when [args] is empty), and said again the same day it's the one note.
     */
    fun note(
        level: ServerLevel, capital: Long, kind: String, points: Int, days: Int,
        args: List<String> = listOf(), about: UUID? = null, works: Int = -1,
    ) {
        if (!enabled() || points == 0 || days <= 0) return
        if (market(level, capital) == null) return
        val words = if (kind == FortuneReasons.SUPPLIED && args.isEmpty() && about != null) listOfNotNull(name(level, about)) else args
        val day = today(level, capital)
        add(level, capital, FortuneData.Note(kind, points, day, day + days, words, about, works, false))
    }

    /** As [note], one of the reasons noted as they happen ([noted]: its kind, points and days, and for crime what it was). */
    fun note(level: ServerLevel, capital: Long, noted: Noted, args: List<String> = listOf(), about: UUID? = null, works: Int = -1) =
        note(level, capital, noted.kind, noted.points, noted.days, listOfNotNull(noted.what) + args, about, works)

    /**
     * As [note], in whichever capital takes in [pos] (found from loaded chunks only), or else the village whose ground it
     * is ([Villages.at], with Village Fortunes on); nothing outside both.
     */
    fun noteAt(level: ServerLevel, pos: BlockPos, noted: Noted, args: List<String> = listOf(), about: UUID? = null) {
        if (!enabled() || level.dimension() != Level.OVERWORLD) return
        val capital = Furnish.capitalAt(level, pos)?.center?.asLong() ?: villageAt(level, pos) ?: return
        note(level, capital, noted, args, about)
    }

    /** The bell of the village whose ground takes in [pos], while the villages' fortunes are worked out; null outside one. */
    private fun villageAt(level: ServerLevel, pos: BlockPos): Long? = if (VillageMarkets.fortunes()) Villages.at(level, pos)?.key else null

    /**
     * The market whose books [key] keeps: a capital's, or a village's ([Markets.find]) while Village Fortunes is on; null for
     * a place with no market open.
     */
    private fun market(level: ServerLevel, key: Long): Markets.Entry? =
        Markets.get(level)[BlockPos.of(key)] ?: if (VillageMarkets.fortunes()) Markets.find(level, key) else null

    /** [player] sold [entry]'s market [good] at a keeper's stall (from Witness.dealt, as Stalls tells of a sale): supplied, if the town was short of it. */
    fun sold(level: ServerLevel, entry: Markets.Entry, good: Good, player: ServerPlayer) {
        if (!enabled() || Trading.quote(entry, good).why?.dear != true) return
        note(level, entry.market.capital, Noted.SUPPLIED, listOf(player.name.string), player.uuid)
    }

    /** The crowds' share in [capital] as the times go, 0.7 to 1.25. */
    fun busy(level: ServerLevel, capital: Long): Double = when (times(level, capital)) {
        Times.HARD -> 0.7
        Times.LEAN -> 0.85
        Times.STEADY -> 1.0
        Times.GOOD -> 1.1
        Times.THRIVING -> 1.25
    }

    /** What [capital] spends on wares and ale as the times go, 0.92 to 1.05. */
    fun spend(level: ServerLevel, capital: Long): Double = when (times(level, capital)) {
        Times.HARD -> 0.92
        Times.LEAN -> 0.97
        Times.STEADY -> 1.0
        Times.GOOD -> 1.02
        Times.THRIVING -> 1.05
    }

    /** The times [score] calls for: -6 or lower HARD, -5 to -2 LEAN, -1 to +2 STEADY, +3 to +5 GOOD, +6 or higher THRIVING. */
    fun called(score: Int): Times = when {
        score <= -6 -> Times.HARD
        score <= -2 -> Times.LEAN
        score <= 2 -> Times.STEADY
        score <= 5 -> Times.GOOD
        else -> Times.THRIVING
    }

    private val listeners = ArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    /** Hears a capital's times move. */
    interface Listener {
        fun changed(level: ServerLevel, capital: Long, from: Times, to: Times)
    }

    /** The listeners hear [capital]'s times move from [from] to [to]: one that fails is logged, and the rest still hear. */
    private fun tell(level: ServerLevel, capital: Long, from: Times, to: Times) {
        for (l in listeners.toList()) {
            try {
                l.changed(level, capital, from, to)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Something hearing of the times failed in the capital at {}", BlockPos.of(capital).toShortString(), e)
            }
        }
    }

    /** At setup: the boards' medallions kept to the times, and the road, the works and the households heard. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        // The next world's clock starts again from its own time
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> fresh.clear() }
        Caravans.listen(Road)
        Works.listen(Building)
        Comings.listen(Households)
    }

    // ---- The close ----

    /** [capital]'s record while the fortunes are on (a village's while Village Fortunes is on too: off, every village reads steady). */
    private fun record(level: ServerLevel, capital: Long): FortuneData.Town? = if (enabled()) FortuneData.get(level)[capital]?.takeIf { kept(level, capital) } else null

    /** Whether the record of [key] counts: always a capital's; a village's only while Village Fortunes is on. */
    private fun kept(level: ServerLevel, key: Long): Boolean =
        VillageMarkets.fortunes() || Markets.get(level)[BlockPos.of(key)] != null || Villages.byKey(level, key) == null

    /**
     * From Markets.step, after the hour is worked out: at the close (two in the morning) [entry]'s capital's
     * fortunes for the market day just begun, once a day.
     */
    internal fun day(level: ServerLevel, entry: Markets.Entry) {
        if (!enabled()) return
        val m = entry.market
        val c = Conditions(m.hour)
        if (c.clock != Economy.NEW_DAY) return
        val data = FortuneData.get(level)
        val town = data.town(m.capital)
        // On ahead of the clock (`market run`), each day the market closes is the fortunes' next, however often it's
        // run on from the same hour: those days are lived already, and the clock's closes wait for them to come round
        val day = if (m.hour > Markets.hourOf(level)) maxOf(c.day, town.closed + 1) else c.day
        // A day already closed (lived on ahead by a market run) isn't closed twice
        if (day <= town.closed) return
        town.closed = day
        close(level, entry, town, day, now = false)
        data.changed()
    }

    /**
     * The clock set back (from Markets.run, [capital]'s market going back [hours] with it): the record goes back [days]
     * market days, the day it last closed and what it dates by its days (the notes, the hold, the days kept, the
     * reasons' and since when the times stand) and by the clock's hours (when they moved, the works' news), so the
     * clock's next close is its next and nothing noted waits or lasts the longer. Days lived on ahead by a market run
     * stay ahead.
     */
    internal fun back(level: ServerLevel, capital: Long, days: Long, hours: Long) {
        val data = FortuneData.get(level)
        val town = data[capital] ?: return
        if (town.closed != FortuneData.NEVER) town.closed -= days
        // Nothing goes before the clock's first day, where -1 is "never" or "none"
        if (town.since >= 0) town.since = maxOf(0L, town.since - days)
        if (town.moved != FortuneData.NEVER) town.moved -= hours
        if (town.held >= 0) town.held = (town.held - days).takeIf { it >= 0 } ?: -1L
        town.reasons.replaceAll { Reason(it.kind, it.points, if (it.until >= 0) maxOf(0L, it.until - days) else it.until, it.args, it.about, it.works) }
        town.notes.replaceAll { FortuneData.Note(it.kind, it.points, it.from - days, it.until - days, it.args, it.about, it.works, it.hand) }
        town.days.replaceAll { FortuneDay(it.day - days, it.score, it.times) }
        town.works.replaceAll { FortuneData.WorksNews(it.id, it.kind, it.road, it.what, it.hour - hours, it.why, it.patron) }
        data.changed()
    }

    /**
     * Works [town]'s fortunes out for market [day]: its reasons, its score, and the times a step toward what that
     * calls for (unless `fortune set` holds them). [now]: `fortune step`, counting today's notes too and letting any
     * hold go. Gives the times the score calls for.
     */
    private fun close(level: ServerLevel, entry: Markets.Entry, town: FortuneData.Town, day: Long, now: Boolean): Times {
        val reasons = FortuneReasons.biggestFirst(FortuneReasons.read(level, entry, day) + FortuneReasons.noted(town.notes, day, now))
        town.notes.removeAll { it.until < day }
        town.reasons.clear()
        town.reasons.addAll(reasons)
        town.score = FortuneReasons.score(reasons)
        val called = called(town.score)
        val from = town.times
        val held = !now && town.held >= day
        if (!held) town.held = -1L
        val to = if (held) from else toward(from, called)
        town.days.removeAll { it.day == day }
        town.days.add(FortuneDay(day, town.score, to))
        town.days.sortBy { it.day }
        while (town.days.size > DAYS) town.days.removeAt(0)
        if (to != from) move(level, entry.market.capital, town, to, day)
        return called
    }

    /** One step from [from] toward [to]. */
    private fun toward(from: Times, to: Times): Times = Times.entries[from.ordinal + Integer.signum(to.ordinal - from.ordinal)]

    /**
     * [capital]'s times move to [to] on market day [day]: kept (since that day, and for the crier the clock's hour
     * now, which the noon's news goes by however far ahead `market run` has the market), the medallion's turn made
     * fresh, and the listeners told.
     */
    private fun move(level: ServerLevel, capital: Long, town: FortuneData.Town, to: Times, day: Long) {
        val from = town.times
        town.from = from
        town.times = to
        town.since = day
        town.moved = Markets.hourOf(level)
        fresh[capital] = level.gameTime
        tell(level, capital, from, to)
    }

    /** Adds [note] to [capital]'s record: said again the same day of the same player or works, it's the one note (see [FortuneReasons.SAME]). */
    private fun add(level: ServerLevel, capital: Long, note: FortuneData.Note) {
        val data = FortuneData.get(level)
        val notes = data.town(capital).notes
        val same = if (note.kind in FortuneReasons.SAME) {
            notes.indexOfFirst { !it.hand && it.kind == note.kind && it.from == note.from && it.about == note.about && it.works == note.works && it.args == note.args }
        } else -1
        if (same >= 0) {
            notes[same] = notes[same].until(maxOf(notes[same].until, note.until))
        } else {
            notes.add(note)
            while (notes.size > FortuneReasons.NOTES) notes.removeAt(0)
        }
        data.changed()
    }

    /** The hour [capital]'s market (or a village's, see [market]) has got to (the clock's, with no market). */
    private fun hourOf(level: ServerLevel, capital: Long): Long =
        market(level, capital)?.market?.hour?.takeIf { it != Long.MIN_VALUE } ?: Markets.hourOf(level)

    /**
     * The day the fortunes of [capital] stand at, which a note, a hold and `fortune step` go by: its market's day, or
     * the last day they closed if that's later (`market run` on ahead), so a note counts at the next closes whichever
     * they are.
     */
    internal fun today(level: ServerLevel, capital: Long): Long {
        val closed = FortuneData.get(level)[capital]?.closed ?: FortuneData.NEVER
        return maxOf(Conditions(hourOf(level, capital)).day, closed)
    }

    /** A player's name as the world knows it, whether they're on or not. */
    private fun name(level: ServerLevel, id: UUID): String? =
        level.server.playerList.getPlayer(id)?.name?.string ?: level.server.services().nameToIdCache().get(id).map { it.name }.orElse(null)

    // ---- For the commands ----

    /**
     * `fortune set`: [capital]'s times are [to] from now, held through the next [closes] closes (none for 0). Gives
     * the last day they're held through, or -1.
     */
    internal fun set(level: ServerLevel, capital: Long, to: Times, closes: Int): Long {
        val day = today(level, capital)
        val data = FortuneData.get(level)
        val town = data.town(capital)
        town.held = if (closes > 0) day + closes else -1L
        if (town.times != to) move(level, capital, town, to, day)
        data.changed()
        return town.held
    }

    /**
     * `fortune step`: [entry]'s capital's day (see [today]) worked out again now, today's notes with it, and the
     * times a step on, any hold let go. Gives the times called for.
     */
    internal fun step(level: ServerLevel, entry: Markets.Entry): Times {
        val day = today(level, entry.market.capital)
        val data = FortuneData.get(level)
        val called = close(level, entry, data.town(entry.market.capital), day, now = true)
        data.changed()
        return called
    }

    /** `fortune reason`: [kind] for [points] over the next [days] closes, by hand: a reason of its own, whatever its kind's rule. */
    internal fun hand(level: ServerLevel, capital: Long, kind: String, points: Int, days: Int) {
        val day = today(level, capital)
        add(level, capital, FortuneData.Note(kind, points, day, day + days, listOf(), null, -1, true))
    }

    /** `fortune clear`: [capital]'s record forgotten but for the day it last closed, and its times steady again. */
    internal fun clear(level: ServerLevel, capital: Long) {
        val town = FortuneData.get(level).clear(capital) ?: return
        if (town.times == Times.STEADY) return
        fresh[capital] = level.gameTime
        tell(level, capital, town.times, Times.STEADY)
    }

    // ---- The board's medallion ----

    /** The game time each capital's times last moved (not kept: after a restart the medallion is found turned). */
    private val fresh = HashMap<Long, Long>()

    /**
     * Every [LOOK] ticks, the market board of each capital a player is in shows its times: turning to them in sight
     * of a player near it if they've only just moved, or found turned if not (nobody saw it go).
     */
    private fun tick(server: MinecraftServer) {
        if (!READY || server.tickCount % LOOK != 0) return
        val level = server.overworld()
        val looked = HashSet<Long>()
        for (player in level.players()) {
            val capital = Furnish.capitalAt(level, player.blockPosition()) ?: continue
            val key = capital.center.asLong()
            if (!looked.add(key)) continue
            val places = CityPlaces.of(level, capital) ?: continue
            val board = MarketBoards.board(level, places) ?: continue
            val times = times(level, key).id
            if (board.times == times) continue
            val at = board.blockPos
            val near = level.getNearestPlayer(at.x + 0.5, at.y + 0.5, at.z + 0.5, SEEN, false) != null
            val moved = fresh[key]
            board.times(times, near && moved != null && level.gameTime - moved in 0..FRESH)
        }
    }

    // ---- The crier ----

    /**
     * What the crier of [entry]'s capital cries last at [c]'s noon (Crier.news keeps room for them; [at] is the game
     * time, for the slips): the town's works wanted, paid for and raised since the noon before, at most
     * [WORKS_CRIED], each with its WORKS slip; then its times, moved since the noon before (with its FORTUNE slip:
     * "Hard times", and the two biggest reasons pulling that way) or else still hard, lean, good or thriving, with
     * those reasons after it in grey. None while neither the works nor the fortunes are on.
     */
    fun cries(level: ServerLevel, entry: Markets.Entry, c: Conditions, at: Long): List<Crier.Cry> {
        val works = READY && Works.enabled()
        val times = enabled()
        if (!works && !times) return listOf()
        val capital = entry.market.capital
        val town = FortuneData.get(level)[capital] ?: return listOf()
        // The hour of this day's noon (the day starts at two, ten hours before it), as Crier.news reckons it
        val noon = c.day * 24 + 6
        val out = ArrayList<Crier.Cry>()
        if (works) {
            town.works.filter { it.hour > noon - 24 && it.hour <= noon && it.kind in CRIED && it.what in NEWS }
                .groupBy { it.id }.values.map { it.last() }
                .sortedByDescending { NEWS.indexOf(it.what) }
                .take(WORKS_CRIED)
                .forEach { out.add(worksCry(capital, it, at)) }
        }
        if (times) timesCry(town, noon, at)?.let(out::add)
        return out
    }

    /** The voiced [line]'s words. */
    private fun said(line: String): MutableComponent = Component.translatable("say.guildmark.$line")

    /** [n] cried: "The town asks for a granary." with what made it wanted, who paid for it or by whose gift it stands, and its slip. */
    private fun worksCry(capital: Long, n: FortuneData.WorksNews, at: Long): Crier.Cry {
        val line = "crier_works_${n.what}_${n.kind}"
        val name = Component.translatableWithFallback("board.guildmark.slip.works.kind.${n.kind}", n.kind)
        val text = Component.empty()
        if (n.road >= 0) text.append(Component.translatable("board.guildmark.slip.works.on", Component.translatable("road.guildmark.way." + FortuneReasons.side(capital, n.road)))).append(" ")
        when (n.what) {
            "wanted" -> {
                if (n.why.isNotEmpty()) text.append(Component.translatable(n.why)).append(" ")
                text.append(Component.translatable("board.guildmark.slip.works.wanted.text"))
            }
            else -> text.append(
                if (n.patron.isNotEmpty()) Component.translatable("board.guildmark.slip.works.${n.what}.text", n.patron)
                else Component.translatable("board.guildmark.slip.works.${n.what}.text.nobody"),
            )
        }
        val slip = Slip(Slip.Kind.WORKS.id, Component.translatable("board.guildmark.slip.works.${n.what}", name), text, at, n.id)
        return Crier.Cry(line, said(line).append(" ").append(text.copy().withStyle(ChatFormatting.GRAY)), slip, if (n.what == "wanted") 0 else 1)
    }

    /**
     * [town]'s times at the noon of market hour [noon]: moved since the noon before, or still not steady; null if
     * steady all along. Moved since this noon (`fortune set` or `market run` in the afternoon), it's the next noon's
     * news, and this noon's times are the ones they moved from.
     */
    private fun timesCry(town: FortuneData.Town, noon: Long, at: Long): Crier.Cry? {
        val times = if (town.moved > noon) town.from else town.times
        if (town.moved > noon - 24 && town.moved <= noon) {
            val sign = if (times.ordinal > town.from.ordinal) 1 else -1
            val line = "crier_times_${times.id}"
            val why = FortuneReasons.because(town.reasons, sign) ?: Component.translatable("board.guildmark.slip.fortune.${times.id}.text")
            val slip = Slip(Slip.Kind.FORTUNE.id, Component.translatable("board.guildmark.slip.fortune.${times.id}"), why, at)
            return Crier.Cry(line, said(line).append(" ").append(why.copy().withStyle(ChatFormatting.GRAY)), slip, sign)
        }
        if (times == Times.STEADY) return null
        val sign = if (times < Times.STEADY) -1 else 1
        val line = "crier_times_still_${times.id}"
        val why = FortuneReasons.because(town.reasons, sign)
        return Crier.Cry(line, if (why != null) said(line).append(" ").append(why.withStyle(ChatFormatting.GRAY)) else said(line), null, sign)
    }

    // ---- What's heard ----

    /** The road: wagons waylaid, relief wagons sent when a band is broken, and wagons seen in under a player's escort. */
    private object Road : Caravans.Listener {
        override fun setOut(level: ServerLevel, caravan: Caravans.View) {
            if (!enabled()) return
            val c = CaravanData.get(level).caravan(caravan.id) ?: return
            if (c.run == Run.RELIEF) note(level, c.capital, Noted.RELIEF, listOf(FortuneReasons.side(c.capital, c.road)))
        }

        override fun struck(level: ServerLevel, caravan: Caravans.View, strike: Caravans.Strike) {
            if (!enabled() || !strike.waylaid) return
            val c = CaravanData.get(level).caravan(caravan.id) ?: return
            note(level, c.capital, Noted.WAYLAID, listOf(c.place, FortuneReasons.side(c.capital, c.road)))
        }

        override fun arrived(level: ServerLevel, caravan: Caravans.View) {
            val escort = caravan.escort ?: return
            if (!enabled()) return
            val c = CaravanData.get(level).caravan(caravan.id) ?: return
            note(level, c.capital, Noted.ESCORTED, listOfNotNull(name(level, escort)), escort)
        }
    }

    /** The works: wanted, paid for and raised (the crier's news), and raised and easing the town (the fortunes'). */
    private object Building : Works.Listener {
        override fun wanted(level: ServerLevel, w: WorksView) = news(level, w, "wanted")

        override fun funded(level: ServerLevel, w: WorksView) = news(level, w, "funded")

        override fun finished(level: ServerLevel, w: WorksView) {
            news(level, w, "raised")
            if (w.kind.per == WorksKind.Per.PLAYER) return
            note(level, w.capital, Noted.RAISED, listOf(w.kind.id, patron(level, w).orEmpty()), w.patron, w.id)
        }

        override fun eased(level: ServerLevel, w: WorksView, what: String) {
            if (w.kind.per == WorksKind.Per.PLAYER) return
            val side = if (w.road >= 0) FortuneReasons.side(w.capital, w.road) else ""
            note(level, w.capital, Noted.EASED, listOf(w.kind.id, what, patron(level, w).orEmpty(), side), w.patron, w.id)
        }

        /** [w]'s news for the crier at the next noon: [what] became of it, at the clock's hour (as the noon's news goes by). */
        private fun news(level: ServerLevel, w: WorksView, what: String) {
            if (!READY || !Works.enabled() || w.kind.id !in CRIED) return
            val data = FortuneData.get(level)
            val works = data.town(w.capital).works
            works.add(FortuneData.WorksNews(w.id, w.kind.id, w.road, what, Markets.hourOf(level), w.why, patron(level, w).orEmpty()))
            while (works.size > WORKS_KEPT) works.removeAt(0)
            data.changed()
        }

        /** Who pledged most toward [w], by name. */
        private fun patron(level: ServerLevel, w: WorksView): String? {
            val id = w.patron ?: return null
            return w.pledges.firstOrNull { it.player == id }?.name?.takeIf { it.isNotEmpty() } ?: name(level, id)
        }
    }

    /** The households: moved in, come home, or gone. */
    private object Households : Comings.Listener {
        override fun arrived(level: ServerLevel, h: Household) = household(level, h, Noted.NEWCOMERS, h.reason)

        override fun left(level: ServerLevel, h: Household) = household(level, h, Noted.LEAVING, h.reason)

        override fun returned(level: ServerLevel, h: Household) = household(level, h, Noted.NEWCOMERS, "return")

        private fun household(level: ServerLevel, h: Household, noted: Noted, why: String) {
            if (!enabled()) return
            val residents = Residents.get(level)
            val family = h.members.firstNotNullOfOrNull { residents[it]?.family }.orEmpty()
            note(level, h.capital, noted, listOf(why, family))
        }
    }

    private inline fun setting(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }
}
