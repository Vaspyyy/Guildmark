package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.tales.Cast
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Gone
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Planned
import io.github.vaspyyy.guildmark.tales.Seeding
import io.github.vaspyyy.guildmark.tales.Step
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleCast
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleOption
import io.github.vaspyyy.guildmark.tales.TaleReply
import io.github.vaspyyy.guildmark.tales.TaleSay
import io.github.vaspyyy.guildmark.tales.TaleScript
import io.github.vaspyyy.guildmark.tales.TaleShares
import io.github.vaspyyy.guildmark.tales.TaleSpeakers
import io.github.vaspyyy.guildmark.tales.TaleTurn
import io.github.vaspyyy.guildmark.tales.TaleVoice
import io.github.vaspyyy.guildmark.tales.TaleWords
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.Template
import io.github.vaspyyy.guildmark.tales.Verb
import io.github.vaspyyy.guildmark.tales.View
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * The Sergeant's Friend (living-world/stories/design.md 8.10), Tales T3: a band has struck the town's wagons twice on one road within six days,
 * and each time it knew the day. The sergeant of the gate watch says it plainly: somebody is selling the wagons' hours. Three could have known,
 * the two who live by the gate the road leaves by and the sergeant's oldest friend, and the sergeant will not have the friend looked at. The
 * trap is the watch's: let one of them hear of a wagon, and the sergeant sends a decoy at the close; the band strikes it only if the one told is
 * the one who talks, and the record says that is always the friend. Then the choice is what to do with a friend who has done a wrong.
 *
 * **The seed.** Two or more of the town's own wagons (a wagon or a convoy of the timetable, never a trading house's or a test) waylaid on one road
 * by one band in the last six market days (the caravans' listener keeps them in the town's list), the band built, at large and working the
 * town's roads (`Caravans.works`), road ambushes on, and the cast below. A forced start with no such pair makes the trouble up on the road of a
 * band that works one, and says so.
 *
 * **The cast.** The sergeant (the guard longest in the town, a standing slot: the next guard takes it), the friend (the grown citizen of the home
 * nearest the sergeant's whose household the comings would let leave, so that the ending A can send it away: essential), and the two grown
 * citizens living nearest the gate the road leaves by (standing slots: the next nearest). The band's scout is a made figure of the dead drop alone.
 *
 * **The steps.** 0 the sergeant asks (ASK, at the gate by day); 1 the three tell what they see of the wagons, each in their own day (MEET: the
 * tells, and "Let slip" from the first); 2 the test (DO: "Let slip a wagon is coming" to one of them; the decoy at the close; the answer at first
 * light; the dead drop at dusk when the one told is the friend); 3 "Who did you tell?" (CHOOSE: the friend named before the watch, A; "Let me
 * speak to them first" and the friend owns up, B; only the road, C); 4 the Naming in the gate yard or the Handshake at the friend's door (SCENE).
 *
 * **What the tale keeps** ([numbers]): the men told and the tells heard, the man told and not yet tried, the road (with its gate and the side of
 * the drop), the band, the closes the band has lain low, the flags below, the last answer (with the strikes and the days of the seed), and the
 * market day the way was chosen; in [strings], the band's name and the side of the town the road leaves by.
 */
object Informer : Template, TaleVoice, TaleWords.Names {
    override val id: String = "informer"
    override val size: Int = 3

    /** Every step hears the close: the sergeant's own test, the decoy, the answer, and the ending's record. */
    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2, hears = setOf(Event.Kind.DAY)),
        Step(Verb.MEET, by = 3, hears = setOf(Event.Kind.DAY)),
        Step(Verb.DO, by = 6, real = true, hears = setOf(Event.Kind.DAY)),
        Step(Verb.CHOOSE, by = 7, real = true, hears = setOf(Event.Kind.DAY)),
        Step(Verb.SCENE, by = 8, hears = setOf(Event.Kind.DAY)),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "named"),
        Ending(Ending.B, "hushed"),
        Ending(Ending.C, "watch"),
        Ending(Ending.W, "eyes"),
    )

    /** Its W leaves the times a reason of -1: the band keeps its eyes in the town. */
    override val hurts: Boolean = true

    /** The sergeant is any guard of the town, the gate's two neighbours any citizens near it; the friend is themselves. */
    override val standing: Set<String> = setOf(SERGEANT, NEIGHBOUR_A, NEIGHBOUR_B)

    override val numbers: List<String> = listOf("men told and heard", "man under test", "road, gate and drop", "band", "closes lain low", "flags", "answer", "day chosen")
    override val strings: List<String> = listOf("band", "side")

    // ---- The record ----

    const val SERGEANT = "sergeant"
    const val FRIEND = "friend"
    const val NEIGHBOUR_A = "neighbour_a"
    const val NEIGHBOUR_B = "neighbour_b"

    /** The made figure: the band's scout at the dead drop. */
    const val SCOUT = "scout"

    const val AT_ASK = 0
    const val AT_MEET = 1
    const val AT_TEST = 2
    const val AT_CHOOSE = 3
    const val AT_TELL = 4

    private const val MEN = 0
    private const val PENDING = 1
    private const val ROAD = 2
    private const val BAND = 3
    private const val LOW = 4
    private const val FLAGS = 5
    private const val ANSWER = 6
    private const val CHOSEN = 7

    /** The three who saw the days, as bits (`n[0]` the men told, its bits four up the tells heard; `n[1]` the one told and not yet tried). */
    const val BIT_A = 1
    const val BIT_B = 2
    const val BIT_FRIEND = 4
    const val ALL = BIT_A or BIT_B or BIT_FRIEND

    /** The three's slots, in the order the sergeant names them. */
    val THREE: List<String> = listOf(NEIGHBOUR_A, NEIGHBOUR_B, FRIEND)

    /**
     * The flags, in `n[5]`: a strike was seen (by a test or at the drop: the friend is unmasked); the friend caught at the drop; the way chosen
     * (one of three); "Let me speak to them first" said; a forced start made the trouble up; the sergeant tried the neighbours themselves; the
     * friend went to the sergeant themselves; the friend gone; the band broken; the roads quiet (ambushes off).
     */
    const val SEEN = 1
    const val CAUGHT = 2
    const val WAY_A = 4
    const val WAY_B = 8
    const val WAY_C = 16
    const val HUSH = 32
    const val MADE = 64
    const val SELF = 128
    const val OWNED = 256
    const val GONE = 512
    const val BROKEN = 1024
    const val QUIET = 2048

    private const val WAYS = WAY_A or WAY_B or WAY_C

    /** The answers the sergeant has, in `n[6]` (the men it is of four bits up). */
    const val CLEAR = 1
    const val STRUCK = 2
    const val LOWLIE = 3
    const val RETURNED = 4
    const val AT_DROP = 5

    /** The market days a strike stays a seed: the last six. */
    const val WINDOW = 6L

    /** The closes the band may lie low before the test is given back. */
    const val LIES_LOW = 2

    /** The days the second pair stands at the gate (C). */
    const val WATCH_DAYS = 10

    /** The clock: the sergeant at the gate from 10:00 to 17:00; the dead drop from 18:00 to 19:30; the Naming from 10:00 to 16:00; the Handshake at dusk. */
    val POST_FROM: Int = Days.clock(10.0)
    val POST_TO: Int = Days.clock(17.0)
    val DROP_FROM: Int = Days.clock(18.0)
    val DROP_TO: Int = Days.clock(19.5)
    val NAMING_FROM: Int = Days.clock(10.0)
    val NAMING_TO: Int = Days.clock(16.0)
    val DUSK_FROM: Int = Days.clock(17.5)
    val DUSK_TO: Int = Days.clock(20.5)

    internal fun flag(t: Tale, f: Int): Boolean = t.n[FLAGS] and f != 0

    internal fun set(t: Tale, f: Int) {
        t.n[FLAGS] = t.n[FLAGS] or f
    }

    private fun clear(t: Tale, f: Int) {
        t.n[FLAGS] = t.n[FLAGS] and f.inv()
    }

    /** The way chosen: [WAY_A], [WAY_B], [WAY_C], or 0 for none yet. */
    internal fun way(t: Tale): Int = t.n[FLAGS] and WAYS

    internal fun choose(t: Tale, w: Int) {
        clear(t, WAYS)
        set(t, w)
    }

    /** The men told (bits). */
    internal fun told(t: Tale): Int = t.n[MEN] and ALL

    internal fun setTold(t: Tale, bits: Int) {
        t.n[MEN] = t.n[MEN] or (bits and ALL)
    }

    internal fun untell(t: Tale, bits: Int) {
        t.n[MEN] = t.n[MEN] and (bits and ALL).inv()
    }

    /** The men whose tell a player has heard (bits). */
    internal fun heardTells(t: Tale): Int = (t.n[MEN] ushr 4) and ALL

    internal fun hearTell(t: Tale, bits: Int) {
        t.n[MEN] = t.n[MEN] or ((bits and ALL) shl 4)
    }

    private fun unhear(t: Tale, bits: Int) {
        t.n[MEN] = t.n[MEN] and ((bits and ALL) shl 4).inv()
    }

    /** The man told and not yet tried (a bit, or 0). */
    internal fun pending(t: Tale): Int = t.n[PENDING] and ALL

    internal fun setPending(t: Tale, bit: Int) {
        t.n[PENDING] = bit and ALL
    }

    internal fun low(t: Tale): Int = t.n[LOW]

    internal fun setLow(t: Tale, n: Int) {
        t.n[LOW] = n
    }

    internal fun bandId(t: Tale): Int = t.n[BAND]

    /** The worked road (-1: the plan's own country road). */
    internal fun road(t: Tale): Int = (t.n[ROAD] and 0xFFFF) - 1

    internal fun gate(t: Tale): Dir = Dir.entries.getOrElse((t.n[ROAD] ushr 16) and 0xFF) { Dir.entries.first() }

    /** Which verge of the road the drop is on (1 or -1). */
    internal fun verge(t: Tale): Int = if ((t.n[ROAD] ushr 24) and 1 == 0) 1 else -1

    /** The last answer the sergeant has ([CLEAR], [STRUCK], [LOWLIE], [RETURNED], [AT_DROP]; 0 none) and the men it is of. */
    internal fun answer(t: Tale): Int = t.n[ANSWER] and 0xF

    internal fun answerOf(t: Tale): Int = (t.n[ANSWER] ushr 4) and ALL

    internal fun setAnswer(t: Tale, kind: Int, men: Int) {
        t.n[ANSWER] = (t.n[ANSWER] and 0xFF.inv()) or (kind and 0xF) or ((men and ALL) shl 4)
    }

    /** How many wagons were struck, and over how many market days, when the tale was seeded (0 for a forced start's). */
    internal fun strikes(t: Tale): Int = (t.n[ANSWER] ushr 8) and 0xF

    internal fun span(t: Tale): Int = (t.n[ANSWER] ushr 12) and 0xF

    /** The market day the way was chosen (-1: not yet). */
    internal fun chosen(t: Tale): Long = t.n[CHOSEN].toLong()

    internal fun setChosen(t: Tale, day: Long) {
        t.n[CHOSEN] = day.toInt()
    }

    /** The band's name. */
    internal fun bandWord(t: Tale): String = t.s[0]

    /** The side of the town the worked road leaves by ("north"). */
    internal fun side(t: Tale): String = t.s[1].ifBlank { "north" }

    internal fun given(name: String): String = name.substringBefore(' ')

    internal fun name(t: Tale, slot: String): String = t.cast(slot)?.name.orEmpty()

    internal fun givenOf(t: Tale, slot: String): String = given(name(t, slot))

    internal fun bitOf(slot: String): Int = when (slot) {
        NEIGHBOUR_A -> BIT_A
        NEIGHBOUR_B -> BIT_B
        FRIEND -> BIT_FRIEND
        else -> 0
    }

    internal fun slotOf(bit: Int): String? = when (bit) {
        BIT_A -> NEIGHBOUR_A
        BIT_B -> NEIGHBOUR_B
        BIT_FRIEND -> FRIEND
        else -> null
    }

    /** The given names of the men of [bits], in the sergeant's order ("Wat, Garth"). */
    internal fun names(t: Tale, bits: Int): String = THREE.filter { bitOf(it) and bits != 0 }.joinToString(", ") { givenOf(t, it) }

    /** How many of the three have been tried. */
    internal fun tested(t: Tale): Int = Integer.bitCount(told(t) and pending(t).inv())

    internal fun clock(level: ServerLevel): Int = Days.offsetOf(level.overworldClockTime)

    internal fun band(level: ServerLevel, t: Tale): OutlawBand? = OutlawCamps.get(level).band(bandId(t))

    /** The front of the gate the worked road leaves by. */
    internal fun front(c: Ctx, t: Tale): BlockPos = WayOut.front(c.here.places, gate(t))

    /** The words a sentence is said with (`TaleWords`): A the friend and the band, B the friend and the sergeant, C and W the road. */
    override fun words(t: Tale): List<String> = when (t.ending) {
        Ending.A -> listOf(name(t, FRIEND), bandWord(t))
        Ending.B -> listOf(name(t, FRIEND), name(t, SERGEANT))
        else -> listOf(side(t))
    }

    // ---- The seed ----

    override fun needs(): Boolean = Caravans.ambushes()

    /** The trouble to seed from: the [road] the strikes fell on, the [band], the [gate] the road leaves by, how many [strikes] over how many days ([span]). */
    private class Trouble(val road: Int, val band: OutlawBand, val gate: Dir, val strikes: Int, val span: Int)

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val data = TaleData.get(level)
        val places = c.here.places
        if (WayOut.gates(places).isEmpty()) return c.none("the town has no gate with a country road")
        val camps = OutlawCamps.get(level)
        val caravans = CaravanData.get(level)
        var found = "fewer than two wagons bound here were waylaid on one road by one band in the last $WINDOW market days"
        var trouble: Trouble? = null
        val recent = data.find(c.capital)?.waylaid.orEmpty().filter { it.band >= 0 && c.day - it.day in 0..WINDOW }
        val groups = recent.groupBy { it.road to it.band }.entries.sortedByDescending { e -> e.value.maxOf { it.day } }
        for ((key, list) in groups) {
            val struck = list.distinctBy { it.caravan }
            if (struck.size < 2) continue
            val (road, bandId) = key
            val band = camps.band(bandId)
            if (band == null || !band.built || band.slain) {
                found = "the band that struck twice is broken, or has no camp yet"
                continue
            }
            if (!Caravans.works(level, band)) {
                found = "the band that struck twice no longer works the town's roads"
                continue
            }
            if (data.all().any { it.template == id && it.capital == c.capital && road(it) == road && bandId(it) == bandId && c.day - it.d0 <= WINDOW }) {
                found = "those strikes have had their tale already"
                continue
            }
            val gate = gateOf(c, caravans, struck.map { it.caravan }, road)
            if (gate == null) {
                found = "the road the strikes fell on leaves by no gate the town knows"
                continue
            }
            val span = (struck.maxOf { it.day } - struck.minOf { it.day } + 1).toInt().coerceIn(1, 15)
            trouble = Trouble(road, band, gate, struck.size.coerceAtMost(15), span)
            break
        }
        var madeUp = ""
        if (trouble == null && c.forced) {
            trouble = madeUp(c)
            if (trouble != null) madeUp = "two strikes on the ${WayOut.side(c.here, trouble.gate)} road by ${bandName(trouble.band)} (no wagon was struck: none is on record)"
        }
        if (trouble == null) return c.none(if (c.forced) "$found, and no band at large works any of the town's roads" else found)
        val cast = castFor(c, trouble.gate) ?: return c.none(lastWhy)
        val side = WayOut.side(c.here, trouble.gate)
        val n = IntArray(8)
        n[ROAD] = ((trouble.road + 1) and 0xFFFF) or (trouble.gate.ordinal shl 16) or ((if (c.random.nextBoolean()) 0 else 1) shl 24)
        n[BAND] = trouble.band.id
        n[FLAGS] = if (madeUp.isNotEmpty()) MADE else 0
        n[ANSWER] = (trouble.strikes shl 8) or (trouble.span shl 12)
        n[CHOSEN] = -1
        val why = if (madeUp.isNotEmpty()) Why("informer.start_made", listOf(side))
        else Why("informer.start", listOf(trouble.strikes.toString(), side, trouble.span.toString()))
        return Planned(cast, why, step = AT_ASK, n = n.toList(), s = listOf(bandName(trouble.band), side), madeUp = madeUp)
    }

    /** Why the cast could not be found, for [seed]'s answer. */
    private var lastWhy = ""

    /**
     * The sergeant (the guard longest in the town), the friend (the grown citizen of the home nearest the sergeant's whose household the comings
     * would let leave) and the two grown citizens nearest the gate [gate] (of two homes, neither the friend's nor the sergeant's); null if any is missing.
     */
    private fun castFor(c: Seeding, gate: Dir): List<Cast>? {
        val level = c.level
        val places = c.here.places
        val lodgings = places.lodgings
        val pool = TaleCast.pool(level, c.here.capital.center)
        val sergeant = pool.filter { it.role == TownRole.GUARD }.minWithOrNull(compareBy<Resident>({ it.since }, { it.id }))
        if (sergeant == null) {
            lastWhy = "no guard is free to be the sergeant"
            return null
        }
        val at = lodgings[sergeant.home]?.box?.center
        val leavers = Comings.leavers(Comings.ctx(level, places, begin = false)).map { it.home.id }.toSet()
        val friend = pool.filter { it.role == TownRole.CITIZEN && it.home >= 0 && it.home != sergeant.home && it.home in leavers }
            .minWithOrNull(compareBy<Resident>({ r -> at?.let { lodgings[r.home]?.box?.center?.distSqr(it) } ?: Double.MAX_VALUE }, { it.id }))
        if (friend == null) {
            lastWhy = "no grown citizen free of a tale has a household that could leave the town"
            return null
        }
        val front = WayOut.front(places, gate)
        val near = pool.filter { it.role == TownRole.CITIZEN && it.home >= 0 && it.home != sergeant.home && it.home != friend.home }
            .sortedWith(compareBy<Resident>({ r -> lodgings[r.home]?.box?.center?.distSqr(front) ?: Double.MAX_VALUE }, { it.id }))
        val a = near.firstOrNull()
        val b = a?.let { first -> near.firstOrNull { it.home != first.home } }
        if (a == null || b == null) {
            lastWhy = "fewer than two grown citizens of two homes, free of a tale, live by the ${WayOut.side(c.here, gate)} gate"
            return null
        }
        return listOf(
            Cast(SERGEANT, sergeant.id, sergeant.name),
            Cast(FRIEND, friend.id, friend.name),
            Cast(NEIGHBOUR_A, a.id, a.name),
            Cast(NEIGHBOUR_B, b.id, b.name),
        )
    }

    /** The name a band goes by, its captain's ("Black Aldric"), as the language has it. */
    private fun bandName(band: OutlawBand): String = Component.translatable("${Outlaws.NAME_KEY}.${band.name}").string

    /** The gate the road comes in by: its leg's side, else the gate one of the waylaid wagons' records names, else the gate nearest one's wreck. */
    private fun gateOf(c: Seeding, caravans: CaravanData, struck: List<Long>, road: Int): Dir? {
        val places = c.here.places
        val gates = WayOut.gates(places)
        CaravanRoutes.cached(c.capital, road)?.side?.takeIf { it in gates }?.let { return it }
        for (id in struck) {
            val rec = caravans.caravan(id) ?: continue
            if (rec.gate != 0L) WayOut.gateNear(places, BlockPos.of(rec.gate))?.let { return it }
            if (rec.wx != 0.0 || rec.wz != 0.0) WayOut.gateNear(places, BlockPos.containing(rec.wx, rec.wy, rec.wz))?.let { return it }
        }
        return null
    }

    /** A forced start's trouble: the first of the town's roads a band at large works. */
    private fun madeUp(c: Seeding): Trouble? {
        val level = c.level
        val gates = WayOut.gates(c.here.places)
        for (leg in CaravanRoutes.legs(level, c.capital).orEmpty().sortedBy { it.road }) {
            val band = CaravanRoutes.band(level, leg)?.takeIf { it.built && !it.slain } ?: continue
            val gate = leg.side.takeIf { it in gates } ?: continue
            return Trouble(leg.road, band, gate, 0, 0)
        }
        return null
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        // Every tell heard before the meeting began: the three are seen
        if (t.step == AT_MEET && heardTells(t) == ALL) c.go(t, AT_TEST)
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        if (e is Event.Day) InformerTrap.close(c, t)
    }

    /**
     * The step's day (1.4): nobody said yes, and the sergeant watches alone (0); the sergeant tries the two neighbours themselves and will not
     * try the friend (1); nothing struck a decoy, and the band keeps its eyes in the town (2, W); the leak shown and nobody named anyone: the
     * sergeant doubles the watch on the road (3, C); the scene not seen, the record ends it the way it was chosen (4).
     */
    override fun settle(c: Ctx, t: Tale) {
        when (t.step) {
            AT_ASK -> {
                c.why(t, "informer.unasked", name(t, SERGEANT))
                c.next(t)
            }
            AT_MEET -> {
                InformerTrap.selfTest(c, t)
                if (t.open && t.step == AT_MEET) c.next(t)
            }
            AT_TEST -> if (flag(t, SEEN)) c.go(t, AT_CHOOSE) else {
                c.why(t, "informer.eyes", side(t))
                c.end(t, Ending.W)
            }
            AT_CHOOSE -> {
                c.why(t, "informer.unchosen", name(t, SERGEANT), side(t))
                choose(t, WAY_C)
                c.data.changed()
                c.end(t, Ending.C)
            }
            else -> c.end(t, outcome(t))
        }
    }

    /** The ending the record has now: the way chosen (A, B or C); C if the leak was shown, or it stopped of itself; W if nothing ever struck. */
    internal fun outcome(t: Tale): String = when {
        way(t) == WAY_A -> Ending.A
        way(t) == WAY_B -> Ending.B
        way(t) == WAY_C || flag(t, SEEN) || flag(t, GONE) || flag(t, BROKEN) || flag(t, QUIET) -> Ending.C
        else -> Ending.W
    }

    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            FRIEND -> {
                InformerWatch.forget(t.id, FRIEND)
                // At the scene the friend's fate was settled already: the record ends it the way it was chosen
                if (t.step == AT_TELL && (way(t) == WAY_A || way(t) == WAY_B)) return c.end(t, outcome(t))
                set(t, GONE)
                c.why(t, "informer.gone", name(t, FRIEND), side(t))
                c.data.changed()
                c.end(t, Ending.C)
            }
            SERGEANT -> {
                InformerWatch.forget(t.id, SERGEANT)
                c.why(t, "informer.nosergeant")
                c.end(t, if (flag(t, SEEN)) Ending.C else Ending.W)
            }
            NEIGHBOUR_A, NEIGHBOUR_B -> {
                // Nobody to try in their place: they count as tried and clear
                InformerWatch.forget(t.id, slot)
                val bit = bitOf(slot)
                setTold(t, bit)
                hearTell(t, bit)
                if (pending(t) == bit) setPending(t, 0)
                c.why(t, "informer.neighbour_gone", name(t, slot))
                c.data.changed()
                if (t.step == AT_MEET && heardTells(t) == ALL) c.go(t, AT_TEST)
            }
        }
    }

    /** A stand-in: a new sergeant is held at the gate from the next second; a new neighbour has told nothing and been told nothing. */
    override fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {
        InformerWatch.forget(t.id, slot)
        val bit = bitOf(slot)
        if (bit != 0 && pending(t) != bit) {
            untell(t, bit)
            unhear(t, bit)
            c.data.changed()
        }
    }

    // ---- The endings ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        InformerWatch.letGo(c, t)
        val holders = t.parts.filter { TaleShares.holds(it) }
        when (ending) {
            Ending.A -> {
                // The band's eyes in the town put out: the road is helped
                InformerTrap.peril(c, t, RoadPeril.HELPED)
                if (c.flip(t, SERGEANT, Trait.DRINKER)) c.why(t, "informer.drinker", name(t, SERGEANT))
                for (p in holders) c.favour(t, SERGEANT, p.player, Favour.TALE_HELPED)
                c.say(t, SERGEANT, "tale.informer.thanks")
                // The household goes: the friend is let go to their day first, so that the comings can take them
                val home = c.resident(t, FRIEND)?.takeIf { it.alive }?.home ?: -1
                c.release(t, FRIEND)
                if (home >= 0 && c.leave(t, home)) c.why(t, "informer.left", name(t, FRIEND))
            }
            Ending.B -> {
                InformerTrap.peril(c, t, -1)
                if (c.flip(t, FRIEND, Trait.SHY)) c.why(t, "informer.shy", name(t, FRIEND))
                c.resident(t, FRIEND)?.home?.takeIf { it >= 0 }?.let { c.protect(t, it, 20) }
                for (p in holders) c.favour(t, SERGEANT, p.player, Favour.TALE_HELPED)
                c.say(t, SERGEANT, "tale.informer.thanks")
            }
            Ending.C -> if (!flag(t, GONE) && !flag(t, BROKEN) && !flag(t, QUIET)) {
                InformerTrap.peril(c, t, -1)
                c.why(t, "informer.frightened", name(t, FRIEND))
                for (p in holders) c.favour(t, SERGEANT, p.player, Favour.TALE_HELPED)
                c.say(t, SERGEANT, "tale.informer.thanks")
            }
            Ending.W -> {
                InformerTrap.lastStrike(c, t)
                InformerTrap.peril(c, t, 1)
                if (c.flip(t, SERGEANT, Trait.GRUMPY)) c.why(t, "informer.grumpy", name(t, SERGEANT))
                c.say(t, SERGEANT, "tale.informer.w")
            }
        }
        c.data.changed()
        InformerWatch.ended(t.id)
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld()
        val c = level?.let { TaleEngine.ctx(it, t.capital) }
        val title = TaleKeys.title(id)
        val sergeant = givenOf(t, SERGEANT)
        val post = c?.let { sergeantAt(it, t) }
        return when (t.step) {
            AT_ASK -> View(title, status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(sergeant), target = post)
            AT_MEET -> View(title, status = TaleKeys.status(id, AT_MEET), target = c?.let { nextMan(it, t, heardTells(t)) })
            AT_TEST -> if (pending(t) != 0) View(title, status = STATUS_WAIT, target = post)
            else View(title, status = TaleKeys.status(id, AT_TEST), target = c?.let { nextMan(it, t, told(t)) })
            AT_CHOOSE -> if (flag(t, HUSH)) View(title, status = STATUS_HUSH, statusArgs = listOf(givenOf(t, FRIEND)), target = c?.let { doorOf(it, t, FRIEND) })
            else View(title, status = TaleKeys.status(id, AT_CHOOSE), statusArgs = listOf(sergeant), target = post)
            else -> if (way(t) == WAY_B) View(title, status = STATUS_DOOR, statusArgs = listOf(givenOf(t, FRIEND)), tone = View.Tone.DONE, target = c?.let { doorOf(it, t, FRIEND) })
            else View(title, status = TaleKeys.status(id, AT_TELL), tone = View.Tone.DONE, target = c?.let { yard(it, t) })
        }
    }

    private const val STATUS_WAIT = "gui.guildmark.tale.informer.status.2.wait"
    private const val STATUS_HUSH = "gui.guildmark.tale.informer.status.3.hush"
    private const val STATUS_DOOR = "gui.guildmark.tale.informer.status.4.door"

    /** Where the sergeant is to be found: at the gate in their hours there, else at their door. */
    internal fun sergeantAt(c: Ctx, t: Tale): BlockPos {
        val front = front(c, t)
        if (clock(c.level) in POST_FROM until POST_TO) return front
        return doorOf(c, t, SERGEANT) ?: front
    }

    /** The front door of [slot]'s home, on the street side. */
    internal fun doorOf(c: Ctx, t: Tale, slot: String): BlockPos? {
        val r = c.resident(t, slot) ?: return null
        return c.here.places.lodgings[r.home]?.front?.outside
    }

    /** The door of the first of the three not in [done] (bits), else the gate. */
    private fun nextMan(c: Ctx, t: Tale, done: Int): BlockPos =
        THREE.firstOrNull { bitOf(it) and done == 0 && t.cast(it)?.gone?.isEmpty() == true }?.let { doorOf(c, t, it) } ?: front(c, t)

    /** The gate yard: six blocks in from the front of the road's gate, toward the town's heart. */
    internal fun yard(c: Ctx, t: Tale): BlockPos = inward(c, t, 6.0)

    /** [blocks] in from the front of the road's gate, toward the town's heart. */
    internal fun inward(c: Ctx, t: Tale, blocks: Double): BlockPos {
        val front = front(c, t)
        val center = c.here.capital.center
        val dx = (center.x - front.x).toDouble()
        val dz = (center.z - front.z).toDouble()
        val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1.0)
        return BlockPos.containing(front.x + 0.5 + dx / len * blocks, front.y.toDouble(), front.z + 0.5 + dz / len * blocks)
    }

    // ---- The telling ----

    override fun scene(c: Ctx, t: Tale): Stage? = if (t.open && t.step <= AT_MEET) InformerScenes.slate(c, t) else null

    // ---- The talk ----

    override fun asker(t: Tale): String = SERGEANT

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val road = WayOut.sideKey(side(t))
        return when (beat) {
            "ask" -> if (flag(t, MADE) || strikes(t) <= 0) TaleLines.facts("talk.detail.tale.informer.ask.made", road)
            else TaleLines.facts("talk.detail.tale.informer.ask", strikes(t), road, span(t))
            "rumour" -> TaleLines.facts("talk.detail.tale.informer.rumour", TaleLines.lit(givenOf(t, SERGEANT)), road)
            "plan", "yes" -> plan(t)
            "friend", "tell_friend" -> friendDetail(t)
            "tell" -> TaleLines.facts("talk.detail.tale.informer.tell", TaleLines.lit(givenOf(t, turn.slot)), road)
            "stand" -> stand(t)
            else -> null
        }
    }

    /** "Test one a day: Wat, Pell, Garth. Garth drinks with Brann." */
    private fun plan(t: Tale): String? = TaleLines.facts(
        "talk.detail.tale.informer.plan",
        TaleLines.lit(givenOf(t, NEIGHBOUR_A)), TaleLines.lit(givenOf(t, NEIGHBOUR_B)), TaleLines.lit(givenOf(t, FRIEND)), TaleLines.lit(givenOf(t, SERGEANT)),
    )

    /** "Garth, the sergeant's friend." */
    private fun friendDetail(t: Tale): String? = TaleLines.facts("talk.detail.tale.informer.friend", TaleLines.lit(givenOf(t, FRIEND)))

    /** "How does it stand?": who has been tried and who not, and the road; at the choice, who was told; at the scene, what comes. */
    internal fun stand(t: Tale): String? {
        val road = WayOut.sideKey(side(t))
        return when {
            t.step == AT_TELL && way(t) == WAY_B -> TaleLines.facts("talk.detail.tale.informer.stand.hushed", TaleLines.lit(givenOf(t, FRIEND)))
            t.step == AT_TELL -> TaleLines.facts("talk.detail.tale.informer.stand.named", TaleLines.lit(givenOf(t, FRIEND)))
            t.step == AT_CHOOSE -> who(t)
            else -> {
                val tried = told(t) and pending(t).inv()
                val untried = ALL and tried.inv()
                when {
                    tried == 0 -> TaleLines.facts("talk.detail.tale.informer.stand.none", TaleLines.lit(names(t, ALL)), road)
                    untried == 0 -> TaleLines.facts("talk.detail.tale.informer.stand.all", TaleLines.lit(names(t, tried)), road)
                    else -> TaleLines.facts("talk.detail.tale.informer.stand", TaleLines.lit(names(t, tried)), TaleLines.lit(names(t, untried)), road)
                }
            }
        }
    }

    /** "You told: Wat, Garth." */
    internal fun who(t: Tale): String? = TaleLines.facts("talk.detail.tale.informer.who", TaleLines.lit(names(t, told(t) or BIT_FRIEND)))

    /** The grey words of the sergeant's last answer. */
    internal fun answerDetail(t: Tale): String? {
        val men = answerOf(t)
        return when (answer(t)) {
            CLEAR -> if (Integer.bitCount(men) > 1) {
                val two = THREE.filter { bitOf(it) and men != 0 }
                TaleLines.facts("talk.detail.tale.informer.clear.two", TaleLines.lit(givenOf(t, two[0])), TaleLines.lit(givenOf(t, two[1])), tested(t), THREE.size)
            } else {
                TaleLines.facts("talk.detail.tale.informer.clear", TaleLines.lit(names(t, men)), tested(t), THREE.size)
            }
            STRUCK -> TaleLines.facts("talk.detail.tale.informer.struck", WayOut.sideKey(side(t)), TaleLines.lit(names(t, men)))
            LOWLIE -> TaleLines.facts("talk.detail.tale.informer.lowlie")
            RETURNED -> TaleLines.facts("talk.detail.tale.informer.lowlie.returned", TaleLines.lit(names(t, men)))
            else -> null
        }
    }

    /** The family of the sergeant's last answer, or null for none to tell (the drop's answer was the friend's own words). */
    internal fun answerFamily(t: Tale): String? = when (answer(t)) {
        CLEAR -> "tale.informer.clear"
        STRUCK -> "tale.informer.struck"
        LOWLIE, RETURNED -> "tale.informer.lowlie"
        else -> null
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = when (beat) {
        "ask" -> Activity.SIGH
        "passing" -> Activity.SHRUG
        "plan", "yes" -> Activity.WHISPER
        "stand" -> if (turn.tale.step == AT_CHOOSE) Activity.ARMS_FOLDED else null
        "greet" -> Activity.SHRUG
        else -> null
    }

    /** The sergeant points down the road when they ask. */
    override fun toward(beat: String, turn: TaleTurn): BlockPos? {
        if (beat != "ask") return null
        val c = turn.ctx() ?: return null
        return front(c, turn.tale)
    }

    /** At the yes, the sergeant's plan, with the three names in grey. */
    override fun yes(turn: TaleTurn): TaleSay = TaleSay("tale.informer.plan", plan(turn.tale))

    /**
     * What the three say when a player in the tale turns to them: their tell at the meeting and the test; the friend, once a player has gone
     * to speak to them first, asks for the night. Reads only: the tell is counted heard by the talk that says it ([Telling]).
     */
    override fun greet(turn: TaleTurn): TaleSay? {
        val t = turn.tale
        if (bitOf(turn.slot) == 0) return null
        if (turn.slot == FRIEND && t.step == AT_CHOOSE && flag(t, HUSH)) return turn.say("hush")
        if (t.step != AT_MEET && t.step != AT_TEST) return null
        return if (turn.slot == FRIEND) turn.say("tell_friend", friendDetail(t)) else turn.say("tell", detail("tell", turn))
    }

    override fun options(turn: TaleTurn): List<TaleOption> {
        if (turn.player == null) return listOf()
        return when (turn.slot) {
            SERGEANT -> sergeantOptions(turn)
            NEIGHBOUR_A, NEIGHBOUR_B, FRIEND -> manOptions(turn)
            else -> listOf()
        }
    }

    /** What the sergeant offers: of the friend, and a name before any proof, at the meeting and the test; the choice once the leak is shown. */
    private fun sergeantOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        return when (t.step) {
            AT_MEET, AT_TEST -> listOf(ofFriend(t), proof())
            AT_CHOOSE -> if (flag(t, SEEN)) listOfNotNull(named(t), if (!flag(t, HUSH)) hush(t) else null, road(), notNow()) else listOf()
            else -> listOf()
        }
    }

    /** What one of the three offers: the slip (or that a test is set) at the meeting and the test; the friend, asked for the night, the two answers. */
    private fun manOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val bit = bitOf(turn.slot)
        return when {
            (t.step == AT_MEET || t.step == AT_TEST) && told(t) and bit == 0 -> listOf(if (pending(t) != 0) slipSet() else slip(t))
            t.step == AT_CHOOSE && turn.slot == FRIEND && flag(t, HUSH) -> listOf(own(), gateNow())
            else -> listOf()
        }
    }

    // ---- The deeds (the talk's options and `tale informer` both do them through these) ----

    /** [bit]'s tell is heard: at the meeting, all three heard is the step done. */
    internal fun heardFrom(c: Ctx?, t: Tale, bit: Int) {
        if (c == null || bit == 0 || heardTells(t) and bit != 0) return
        hearTell(t, bit)
        c.data.changed()
        if (t.step == AT_MEET && heardTells(t) == ALL) c.go(t, AT_TEST)
    }

    /** [player] lets slip to [slot] that a wagon is coming: the test is set for the next close. False if it no longer can be. */
    internal fun slipTo(c: Ctx, t: Tale, player: ServerPlayer, slot: String): Boolean {
        val bit = bitOf(slot)
        if (bit == 0 || (t.step != AT_MEET && t.step != AT_TEST) || pending(t) != 0 || told(t) and bit != 0) return false
        if (t.cast(slot)?.gone?.isNotEmpty() == true) return false
        t.addWhy(Why("informer.slip", listOf(player.gameProfile.name, name(t, slot), side(t))))
        setTold(t, bit)
        setPending(t, bit)
        setLow(t, 0)
        // The slip to the one who talks is the deed: the record has them struck
        if (bit == BIT_FRIEND) t.partFor(player.uuid)?.let { it.did = it.did or Part.WALKED }
        c.data.changed()
        if (t.step == AT_MEET) c.go(t, AT_TEST)
        return true
    }

    /** [player] caught the friend at the dead drop: the leak is shown, the decoy is called off, and the choice is next. */
    internal fun caught(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if (!t.open || t.step != AT_TEST || pending(t) != BIT_FRIEND || flag(t, SEEN)) return false
        set(t, SEEN or CAUGHT)
        setPending(t, 0)
        setLow(t, 0)
        setAnswer(t, AT_DROP, BIT_FRIEND)
        InformerWatch.answered(t.id)
        c.why(t, "informer.caught", player.gameProfile.name, name(t, FRIEND), side(t))
        Tales.join(c.level, t, player)
        t.partFor(player.uuid)?.let { it.did = it.did or Part.WALKED }
        c.data.changed()
        if (t.open && t.step == AT_TEST) c.go(t, AT_CHOOSE)
        return true
    }

    /** [player] names the friend before the watch (or sends them to the gate from their door): the way is A, the Naming next. */
    internal fun nameFriend(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if (t.step != AT_CHOOSE || !flag(t, SEEN)) return false
        t.addWhy(Why("informer.named", listOf(player.gameProfile.name, name(t, FRIEND), name(t, SERGEANT))))
        decide(c, t, player, WAY_A)
        return true
    }

    /** [player] will speak to the friend first: the friend's door is next, and the choice waits for what is said there. */
    internal fun hushFirst(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if (t.step != AT_CHOOSE || !flag(t, SEEN) || flag(t, HUSH)) return false
        t.addWhy(Why("informer.hush", listOf(player.gameProfile.name, name(t, FRIEND))))
        set(t, HUSH)
        t.partFor(player.uuid)?.let { it.did = it.did or Part.CHOSE }
        c.data.changed()
        return true
    }

    /** [player] tells the friend to go to the sergeant themselves: the way is B, the Handshake next. */
    internal fun owns(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if (t.step != AT_CHOOSE || !flag(t, HUSH)) return false
        t.addWhy(Why("informer.owned", listOf(name(t, FRIEND), name(t, SERGEANT))))
        set(t, OWNED)
        decide(c, t, player, WAY_B)
        return true
    }

    /** [player] tells only the road: the sergeant doubles the watch on it, and it ends (C). */
    internal fun roadOnly(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if (t.step != AT_CHOOSE || !flag(t, SEEN)) return false
        t.addWhy(Why("informer.road_only", listOf(player.gameProfile.name, name(t, SERGEANT), side(t))))
        decide(c, t, player, WAY_C)
        if (t.open) c.end(t, Ending.C)
        return true
    }

    /** [player]'s word picked [w]: theirs is the deciding deed, the day is kept for the scene's window, and (A or B) the scene is next. */
    private fun decide(c: Ctx, t: Tale, player: ServerPlayer, w: Int) {
        choose(t, w)
        val part = t.partFor(player.uuid)
        for (p in t.parts) if (p !== part) p.did = p.did and Part.DECIDED.inv()
        part?.let { it.did = it.did or Part.CHOSE or Part.DECIDED }
        setChosen(t, c.day)
        c.data.changed()
        if (w != WAY_C) c.go(t, AT_TELL)
    }

    // ---- The options ----

    private fun think(): TaleReply = TaleReply("ask.think", gesture = Activity.THINK)

    /** "Let slip a wagon is coming on the north road.": the same answer from all three, so that it tells nothing. */
    private fun slip(t: Tale) = TaleOption(
        "slip", Component.translatable("talk.guildmark.choice.tale.informer.slip", Component.translatable(WayOut.sideKey(side(t)))),
    ) { turn ->
        val tt = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!slipTo(c, tt, player, turn.slot)) return@TaleOption null
        val detail = TaleLines.facts("talk.detail.tale.informer.slip_ok", TaleLines.lit(givenOf(tt, turn.slot)), WayOut.sideKey(side(tt)))
        TaleReply(listOf(turn.say("slip_ok", detail)), Activity.NOD).handingBack()
    }

    /** "A test is already set.": nothing to do until the sergeant has the answer. */
    private fun slipSet() = TaleOption("set", Component.translatable("talk.guildmark.choice.tale.informer.slip_set")) { _ ->
        TaleReply("tale.ask.later", gesture = Activity.NOD, back = true)
    }

    /** "What of Garth?": the sergeant will not have their oldest friend looked at. */
    private fun ofFriend(t: Tale) = TaleOption("of_friend", Component.translatable("talk.guildmark.choice.tale.informer.of_friend", givenOf(t, FRIEND))) { turn ->
        TaleReply(listOf(turn.say("friend", friendDetail(turn.tale))), Activity.WAVE_OFF)
    }

    /** "I know who's talking.": before any test has struck, the sergeant wants proof (there is no wrong word in this tale). */
    private fun proof() = TaleOption("proof", Component.translatable("talk.guildmark.choice.tale.informer.proof")) { turn ->
        TaleReply(listOf(turn.say("proof")), Activity.ARMS_FOLDED)
    }

    /** "It was Garth. Take it to the watch." */
    private fun named(t: Tale) = TaleOption("named", Component.translatable("talk.guildmark.choice.tale.informer.named", givenOf(t, FRIEND))) { turn ->
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!nameFriend(c, turn.tale, player)) return@TaleOption null
        // The sergeant's word has no family of its own: the talk's "It's been decided.", the gate yard in grey
        TaleReply(listOf(TaleSay("tale.ask.decided", stand(turn.tale))), Activity.SIGH, toward = yard(c, turn.tale)).handingBack()
    }

    /** "Let me speak to Garth first.": the sergeant lets them, and the friend's door is next. */
    private fun hush(t: Tale) = TaleOption("hush", Component.translatable("talk.guildmark.choice.tale.informer.hush", givenOf(t, FRIEND))) { turn ->
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!hushFirst(c, turn.tale, player)) return@TaleOption null
        // The sergeant's word has no family of its own: the talk's "Another time, then.", the friend's door in grey
        val detail = TaleLines.facts("talk.detail.tale.informer.hush_go", TaleLines.lit(givenOf(turn.tale, FRIEND)))
        TaleReply(listOf(TaleSay("tale.ask.later", detail)), Activity.SIGH, toward = doorOf(c, turn.tale, FRIEND)).handingBack()
    }

    /** "Only the road: the band is listening.": the second pair at the gate, and the leak stops with nobody named. */
    private fun road() = TaleOption("road", Component.translatable("talk.guildmark.choice.tale.informer.road")) { turn ->
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!roadOnly(c, turn.tale, player)) return@TaleOption null
        TaleReply(listOf(turn.say("road_only", TaleLines.facts("talk.detail.tale.informer.road_only", WATCH_DAYS))), Activity.NOD)
    }

    /** "Not now.": nothing is decided; the step lapses on its day. */
    private fun notNow() = TaleOption("not_now", Component.translatable("talk.guildmark.choice.tale.informer.not_now")) { _ ->
        TaleReply("tale.ask.later", gesture = Activity.NOD, back = true)
    }

    /** The friend's "I'll tell the sergeant myself": "Tell the sergeant yourself." (B). */
    private fun own() = TaleOption("own", Component.translatable("talk.guildmark.choice.tale.informer.own")) { turn ->
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!owns(c, turn.tale, player)) return@TaleOption null
        TaleReply(listOf(turn.say("sorry")), Activity.SIGH, ends = true)
    }

    /** "No: the gate, now." (A). */
    private fun gateNow() = TaleOption("gate", Component.translatable("talk.guildmark.choice.tale.informer.gate")) { turn ->
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!nameFriend(c, turn.tale, player)) return@TaleOption null
        TaleReply(listOf(turn.say("sorry")), Activity.RECOIL, ends = true)
    }

    // ---- The sergeant's answers, the three's tells, and the friend caught at the drop ----

    /**
     * The talk with the sergeant, to a player in the tale, once there is an answer they have not heard (the test's, at the test; "Who did you
     * tell?" at the choice): the answer is said in place of how it stands, and the talk goes on as ever after. The talk with one of the three at
     * the meeting and the test, to a player in the tale: their tell, counted heard as it is said. Makes nothing happen: the talk asks it often.
     */
    override fun script(a: Ask, t: Tale, slot: String): Script? {
        if (!t.open) return null
        val player = a.player ?: return null
        if (!TaleSpeakers.joined(t.part(player.uuid))) return null
        if (slot == SERGEANT) {
            val fresh = InformerWatch.fresh(t, player.uuid) && answerFamily(t) != null
            if (t.step != AT_CHOOSE && !(t.step == AT_TEST && fresh)) return null
            return Answering(TaleScript(t, slot, this, this, player.uuid), t)
        }
        val bit = bitOf(slot)
        if (bit == 0 || (t.step != AT_MEET && t.step != AT_TEST)) return null
        return Telling(TaleScript(t, slot, this, this, player.uuid, asks = false), t, bit)
    }

    /** The sergeant's talk with the answer first: [inner] is the talk every tale has, and does the rest. */
    private class Answering(private val inner: Script, private val t: Tale) : Script {
        override fun opener(ask: Ask): Utterance {
            val player = ask.player ?: return inner.opener(ask)
            if (!t.open) return inner.opener(ask)
            val parts = ArrayList<Utterance.Part>()
            val family = answerFamily(t)
            if (family != null && InformerWatch.fresh(t, player.uuid)) TaleLines.part(ask, family, answerDetail(t))?.let { parts += it }
            if (t.step == AT_CHOOSE) TaleLines.part(ask, "tale.informer.who", who(t))?.let { parts += it }
            if (parts.isEmpty()) return inner.opener(ask)
            InformerWatch.heard(t, player.uuid)
            return Utterance(parts, null, if (t.step == AT_CHOOSE) Activity.ARMS_FOLDED else Activity.NOD)
        }

        override fun choices(ask: Ask): List<Choice> = inner.choices(ask)

        override fun answer(ask: Ask, choice: String): Pair<Utterance, Boolean> = inner.answer(ask, choice)

        override fun back(ask: Ask, choice: String): Boolean = inner.back(ask, choice)
    }

    /** One of the three's talk at the meeting and the test: [inner] says their tell, and it is counted heard ([bit]). */
    private class Telling(private val inner: Script, private val t: Tale, private val bit: Int) : Script {
        override fun opener(ask: Ask): Utterance {
            val said = inner.opener(ask)
            if (t.open && (t.step == AT_MEET || t.step == AT_TEST)) heardFrom(TaleEngine.ctx(ask.level, t.capital), t, bit)
            return said
        }

        override fun choices(ask: Ask): List<Choice> = inner.choices(ask)

        override fun answer(ask: Ask, choice: String): Pair<Utterance, Boolean> = inner.answer(ask, choice)

        override fun back(ask: Ask, choice: String): Boolean = inner.back(ask, choice)
    }

    /** The friend at the dead drop, spoken to by a player: "It isn't what it looks like!", and the leak is shown (the stake-out). */
    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {
        if (a.occasion != Occasion.OPENER || !t.open || t.step != AT_TEST) return
        if (a.player == null) return
        if (TaleSpeakers.slotOf(t, a.speaker) != FRIEND || !InformerWatch.atDrop(a.level, t, a.speaker)) return
        val part = TaleLines.part(a, "tale.informer.caught") ?: return
        out.add(Topic(
            "tale.${t.id}.caught", Kind.TALE, 0.95, Utterance(listOf(part), null, Activity.RECOIL), tone = -1, gesture = Activity.RECOIL, first = true,
            onSaid = { ask ->
                val p = ask.player
                if (p != null && t.open) TaleEngine.ctx(ask.level, t.capital)?.let { c -> if (caught(c, t, p)) InformerWatch.dropCaught(c, t) }
            },
        ))
    }
}
