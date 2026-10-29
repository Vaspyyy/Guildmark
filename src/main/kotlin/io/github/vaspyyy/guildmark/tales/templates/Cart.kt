package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.homestead.Materials
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
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
import io.github.vaspyyy.guildmark.tales.TaleOptions
import io.github.vaspyyy.guildmark.tales.TaleReply
import io.github.vaspyyy.guildmark.tales.TaleSay
import io.github.vaspyyy.guildmark.tales.TaleShares
import io.github.vaspyyy.guildmark.tales.TaleTurn
import io.github.vaspyyy.guildmark.tales.TaleVoice
import io.github.vaspyyy.guildmark.tales.TaleWords
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.Template
import io.github.vaspyyy.guildmark.tales.Verb
import io.github.vaspyyy.guildmark.tales.View
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.ComingsData
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.sqrt

/**
 * The Handcart by the Wardfire (living-world/stories/design.md 8.8): a household that left in the hard days did not get far. Their cart lost
 * a wheel a little way out on the road, and they have camped beside it, too proud and too afraid of the dark to walk back and ask. A neighbour
 * of the empty house asks after them; at the camp the parent tells of the wheel; four timber mends it; the road is kept for a night (five
 * monsters killed by the camp, three once its wardfire is lit) or the watch is paid twelve Marks to ride with them; and at first light the family
 * packs up and sets off, and the comings' own return brings them in at the gate with their cart. It is the one tale about a town getting back
 * what it lost.
 *
 * **The camp** is memory ([CartWatch]): the record keeps only the place on the road (`x`, `y`, `z`) and the household it is about. When a
 * player comes within 64 blocks the family are made figures there with their names and looks from the records (never saved, never adopted):
 * the parent crouched at the off wheel (COWER), an adult kin on the lookout toward the road, a child with a blanket (CARRY by day, SHIVER in
 * the dark), and the cart itself, a real handcart heaped with the household's goods and drawn broken ([io.github.vaspyyy.guildmark.cart.Handcart.broken])
 * until it is mended. A wardfire within 24 blocks of the place takes the camp into its clearing.
 *
 * **The household is away**, so none of the family is a resident in the cast while the tale is open (the engine would see them gone): the
 * parent and the kin are figures' slots, named from the record, and the household's id and the parent's resident id are kept in `n`. When the
 * tale ends with the family home, the parent's slot is given its resident, so the town's thanks and the flips reach them.
 *
 * **The steps.** 0 the neighbour asks (ASK, the S1 variant at the old house); 1 the camp is found (MEET: the first player within seven blocks of
 * the parent takes the tale up); 2 four timber (GIVE, [Materials.take] a plank or a log at a time), and *The Wheel* is played; 3 the parent's
 * choice for the night (CHOOSE: keep the road, ask the watch, or wait for first light); 4 the night (WATCH, also GIVE: monsters killed within
 * 32 blocks of the camp by a player of the tale or their companion, five or three; or twelve Marks to the guard at the gate, in escrow and
 * spent as the watch rides); and at the first light after the family is ready, 5 the going (WATCH: the camp packs, the parent takes up the
 * shafts, and once nobody sees them the comings bring the household home through its gate). The tale ends as the household reaches its door
 * (`Comings.Listener.returned`): A if the road was kept, B if the watch rode, C if neither.
 *
 * **What the tale keeps** ([numbers]): the place on the road, the flags below, the household's id, the parent's resident id, the counts (kills,
 * timber given, and the gate the household left by) and the day of the first light the family waits for.
 */
object Cart : Template, TaleVoice, TaleWords.Names {
    override val id: String = "cart"
    override val size: Int = 2

    /** The camp's steps hear the wardfire and the household; the night's hear the kills too. */
    private val CAMP = setOf(Event.Kind.KINDLED, Event.Kind.HOUSEHOLD)
    private val NIGHT = setOf(Event.Kind.KILL, Event.Kind.KINDLED, Event.Kind.HOUSEHOLD)

    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2),
        Step(Verb.MEET, by = 3, hears = CAMP, needs = { Tales.scenes() }),
        Step(Verb.GIVE, by = 5, real = true, hears = CAMP, needs = { Tales.scenes() }),
        Step(Verb.CHOOSE, by = 6, hears = CAMP, needs = { Tales.scenes() }),
        Step(Verb.WATCH, by = 7, real = true, also = listOf(Verb.GIVE), hears = NIGHT),
        Step(Verb.WATCH, by = 8, hears = NIGHT),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "home"),
        Ending(Ending.B, "escorted"),
        Ending(Ending.C, "mended"),
        Ending(Ending.W, "moved"),
    )

    /** The neighbour and the guard are anyone of their kind; the family are not residents while they are away (see the class). */
    override val standing: Set<String> = setOf(NEIGHBOUR, GUARD)

    override val numbers: List<String> = listOf("road x", "road y", "road z", "flags", "household", "parent", "kills timber gate", "dawn day")
    override val strings: List<String> = listOf("side", "family")

    // ---- The record ----

    const val NEIGHBOUR = "neighbour"
    const val PARENT = "parent"
    const val GUARD = "guard"
    val KIN: List<String> = listOf("kin1", "kin2", "kin3")
    val WATCH_SLOTS: List<String> = listOf("watch1", "watch2")

    const val AT_ASK = 0
    const val AT_MEET = 1
    const val AT_GIVE = 2
    const val AT_CHOOSE = 3
    const val AT_ROAD = 4
    const val AT_HOME = 5

    private const val X = 0
    private const val FLAGS = 3
    private const val HOUSEHOLD = 4
    private const val PARENT_ID = 5
    private const val COUNTS = 6
    private const val DAWN = 7

    /**
     * The flags, in `n[3]`: the camp was found; the wheel is mended; the player chose to keep the road, or to ask the watch, or the family waits
     * for first light; the camp's wardfire is lit; the road is kept (the kills are done); the watch is paid; the family is in the comings'
     * hands, coming home; they came home on their own; the times were made up (a forced start in hard times).
     */
    const val MET = 1
    const val MENDED = 2
    const val KEEP = 4
    const val WATCH = 8
    const val WAIT = 16
    const val LIT = 32
    const val SAFE = 64
    const val PAID = 128
    const val HOME = 256
    const val OWN = 512
    const val MADE = 1024

    /** Four timber mend the wheel; five monsters keep the road, three once the fire is lit; twelve Marks pay two of the watch. */
    const val TIMBER = 4
    const val KILLS = 5
    const val KILLS_LIT = 3
    const val WATCH_MARKS = 12

    /** Blocks from the place on the road a kill counts within, and a wardfire takes the camp into its clearing within. */
    const val KILL_RANGE = 32.0
    const val CLEARING = 24

    /** Households that left in the last [AWAY_DAYS] market days; the camp is [ROAD_MIN] to [ROAD_MAX] blocks out along the road. */
    private const val AWAY_DAYS = 8
    private const val ROAD_MIN = 40
    private const val ROAD_MAX = 70

    /** First light (06:00) and dusk (19:00), in the offsets the days keep. */
    val FIRST_LIGHT: Int = Days.clock(6.0)
    val DUSK: Int = Days.clock(19.0)

    internal fun flag(t: Tale, f: Int): Boolean = t.n[FLAGS] and f != 0

    internal fun set(t: Tale, f: Int) {
        t.n[FLAGS] = t.n[FLAGS] or f
    }

    internal fun clear(t: Tale, f: Int) {
        t.n[FLAGS] = t.n[FLAGS] and f.inv()
    }

    /** The place on the road the camp is beside. */
    internal fun road(t: Tale): BlockPos = BlockPos(t.n[X], t.n[X + 1], t.n[X + 2])

    internal fun household(t: Tale): Int = t.n[HOUSEHOLD]
    internal fun parentId(t: Tale): Int = t.n[PARENT_ID]

    internal fun kills(t: Tale): Int = t.n[COUNTS] and 0xFF
    internal fun timber(t: Tale): Int = (t.n[COUNTS] shr 8) and 0xFF
    internal fun gate(t: Tale): Dir = Dir.entries.getOrElse((t.n[COUNTS] shr 16) and 0xFF) { Dir.entries.first() }

    private fun setKills(t: Tale, k: Int) {
        t.n[COUNTS] = (t.n[COUNTS] and 0xFF.inv()) or k.coerceIn(0, 255)
    }

    private fun setTimber(t: Tale, k: Int) {
        t.n[COUNTS] = (t.n[COUNTS] and (0xFF shl 8).inv()) or (k.coerceIn(0, 255) shl 8)
    }

    /** How many kills keep the road: five, three once the camp's wardfire is lit. */
    internal fun wanted(t: Tale): Int = if (flag(t, LIT)) KILLS_LIT else KILLS

    /** The family is ready to go at first light: they chose to wait, the watch is paid, or the road is kept. */
    internal fun ready(t: Tale): Boolean = flag(t, WAIT) || flag(t, PAID) || flag(t, SAFE)

    /** The clock day of the first light the family waits for. */
    internal fun dawn(t: Tale): Long = t.n[DAWN].toLong()

    /** Sets the first light the family waits for to the next one from now (today's, before 06:00), if they were not ready before. */
    private fun readyNow(level: ServerLevel, t: Tale, was: Boolean) {
        if (was) return
        val time = level.overworldClockTime
        val day = Days.dayOf(time)
        t.n[DAWN] = (if (Days.offsetOf(time) < FIRST_LIGHT) day else day + 1).toInt()
    }

    /** Whether the first light the family waits for has come. */
    internal fun firstLight(level: ServerLevel, t: Tale): Boolean {
        val time = level.overworldClockTime
        val day = Days.dayOf(time)
        val dawn = dawn(t)
        return day > dawn || day == dawn && Days.offsetOf(time) >= FIRST_LIGHT
    }

    /** The light is gone: from dusk to first light. */
    internal fun dark(level: ServerLevel): Boolean = Days.offsetOf(level.overworldClockTime).let { it >= DUSK || it < FIRST_LIGHT }

    /** The ending the family's way home makes: A if the road was kept, B if the watch rode, C if neither (the better route counts). */
    internal fun route(t: Tale): String = when {
        flag(t, SAFE) -> Ending.A
        flag(t, PAID) -> Ending.B
        else -> Ending.C
    }

    /** The family's name with an s, as the record has it ("Hales"). */
    internal fun family(t: Tale): String = t.s.getOrNull(1).orEmpty()

    /** The side the gate is on ("north"). */
    internal fun side(t: Tale): String = t.s.getOrNull(0).orEmpty().ifBlank { "north" }

    private fun given(name: String): String = name.substringBefore(' ')

    private fun plural(family: String): String = if (family.isBlank() || family.endsWith("s", ignoreCase = true)) family else family + "s"

    /** The words a sentence is said with (`TaleWords`): the family with an s ("The Hales came home with the watch"). */
    override fun words(t: Tale): List<String> = listOf(family(t))

    // ---- The household ----

    /** The household's entry among those away, or null once it is not away (home, or gone from the record). */
    internal fun moved(level: ServerLevel, t: Tale): ComingsData.Moved? =
        ComingsData.get(level).find(t.capital)?.away?.firstOrNull { it.id == household(t) }

    /** Whether [r] is away in the record: not alive, not dead, gone. */
    private fun away(r: Resident): Boolean = !r.alive && r.died < 0 && r.left >= 0

    /** The record of the household member in the figure's [slot] (the parent's by id, a kin's by the name the slot carries). */
    internal fun member(level: ServerLevel, t: Tale, slot: String): Resident? {
        val residents = Residents.get(level)
        val parent = residents[parentId(t)]
        if (slot == PARENT) return parent
        val name = t.cast(slot)?.name?.takeIf { it.isNotBlank() } ?: return null
        val ids = moved(level, t)?.members ?: parent?.let { p -> residents.of(p.capital).filter { it.home == p.home }.map { it.id } } ?: return null
        return ids.mapNotNull { residents[it] }.firstOrNull { it.id != parentId(t) && it.name == name }
    }

    /** The household's members as the camp has them: the parent, then the kin in their slots' order. */
    internal fun familySlots(t: Tale): List<String> = listOf(PARENT) + KIN.filter { !t.cast(it)?.name.isNullOrBlank() }

    /** The old home's front door, where the neighbour waits and the family comes home to. */
    internal fun door(places: CityPlaces, home: Int): CityPlaces.Door? = places.lodgings[home]?.front

    /** The gate's front, outside the gatehouse on the road the household left by. */
    internal fun front(c: Ctx, t: Tale): BlockPos = WayOut.front(c.here.places, gate(t))

    // ---- The seed ----

    override fun needs(): Boolean = Comings.enabled() && Handcarts.enabled() && Tales.scenes()

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val hard = Fortunes.times(level, c.capital) < Times.LEAN
        if (hard && !c.forced) return c.none("the times are hard: the town is asked to take a family back only when it could feed them (lean times or better)")
        val town = ComingsData.get(level).find(c.capital) ?: return c.none("the comings have no record of this town")
        val residents = Residents.get(level)
        val today = Comings.dated(level)
        var found = "no household is away from the town in hard times"

        class Pick(val m: ComingsData.Moved, val adult: Resident, val kin: List<Resident>)

        val picks = ArrayList<Pick>()
        for (m in town.away.sortedBy { it.id }) {
            if (m.reason != Comings.HARD) continue
            if (m.why.startsWith("tale ")) {
                found = "the households away were sent by a tale, which has its own ending for them"
                continue
            }
            if (today - m.day !in 0..AWAY_DAYS.toLong()) {
                found = "the households away left more than $AWAY_DAYS market days ago (they are long gone down the road)"
                continue
            }
            val people = m.members.mapNotNull { residents[it] }
            if (people.isEmpty() || people.size != m.members.size || people.any { !away(it) }) continue
            val adult = people.filter { it.role == TownRole.CITIZEN }.minByOrNull { it.id }
            if (adult == null) {
                found = "the household away has no grown citizen to ask"
                continue
            }
            val kin = people.filter { it.id != adult.id }.sortedWith(compareBy<Resident>({ if (it.role.adult) 0 else 1 }, { it.id })).take(KIN.size)
            picks += Pick(m, adult, kin)
        }
        if (picks.isEmpty()) return c.none(found)
        val pick = picks[c.random.nextInt(picks.size)]
        val places = c.here.places
        val oldDoor = door(places, pick.m.home)?.pos ?: places.lodgings[pick.m.home]?.box?.center
        val left = oldDoor?.let { WayOut.gateNear(places, it) }
        val spot = WayOut.spot(level, c.here, c.random, ROAD_MIN, ROAD_MAX, left) ?: WayOut.spot(level, c.here, c.random, ROAD_MIN, ROAD_MAX)
            ?: return c.none("the town has no road out that reaches $ROAD_MIN blocks")
        val data = TaleData.get(level)
        val taken = HashSet<Int>()
        // A neighbour of the old home: a grown person living next door, free of a tale
        val neighbour = Witness.neighbours(level, pick.adult)
            .filter { it.alive && it.role.adult && it.home != pick.m.home && TaleCast.free(it, data) }
            .minWithOrNull(compareBy<Resident>({ r -> places.lodgings[r.home]?.box?.center?.let { a -> oldDoor?.distSqr(a) } ?: Double.MAX_VALUE }, { it.id }))
            ?: return c.none("nobody next door to the old home is free to ask after the family")
        taken += neighbour.id
        // A guard for the escort, the one whose home is nearest the gate; with none the watch is not offered
        val front = WayOut.front(places, spot.gate)
        val guard = TaleCast.pool(level, c.here.capital.center).filter { it.role == TownRole.GUARD && it.id !in taken }
            .minWithOrNull(compareBy<Resident>({ r -> places.lodgings[r.home]?.box?.center?.distSqr(front) ?: Double.MAX_VALUE }, { it.id }))
        val cast = ArrayList<Cast>()
        cast += Cast(NEIGHBOUR, neighbour.id, neighbour.name)
        cast += Cast(PARENT, -1, pick.adult.name)
        pick.kin.forEachIndexed { i, r -> cast += Cast(KIN[i], -1, r.name) }
        if (guard != null) cast += Cast(GUARD, guard.id, guard.name)
        val family = plural(pick.adult.family)
        val n = IntArray(8)
        n[X] = spot.point.x
        n[X + 1] = spot.point.y
        n[X + 2] = spot.point.z
        n[FLAGS] = if (hard) MADE else 0
        n[HOUSEHOLD] = pick.m.id
        n[PARENT_ID] = pick.adult.id
        n[COUNTS] = spot.gate.ordinal shl 16
        return Planned(
            cast, Why("cart.start", listOf(family)), step = AT_ASK, n = n.toList(), s = listOf(WayOut.side(c.here, spot.gate), family),
            madeUp = if (hard) "the times are hard (a real tale waits for lean times or better before the town is asked to take a family back)" else "",
        )
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {}

    override fun heard(c: Ctx, t: Tale, e: Event) {
        when (e) {
            is Event.Kill -> killed(c, t, e)
            is Event.Kindled -> kindled(c, t, e)
            is Event.Household -> if (e.what == "returned" && e.household.id == household(t)) returned(c, t)
            else -> {}
        }
    }

    /**
     * A monster killed within [KILL_RANGE] of the camp by a player of the tale (or their companion), at the night step or before the family
     * sets off: it is counted, the killer's part has the bit, and the fifth (or the third, the fire lit) keeps the road, which decides A.
     */
    private fun killed(c: Ctx, t: Tale, e: Event.Kill) {
        if (t.step < AT_ROAD || flag(t, SAFE) || flag(t, HOME) || CartWatch.going(t.id)) return
        val victim = e.victim
        if (victim.type.category != MobCategory.MONSTER) return
        val at = CartWatch.campAt(t) ?: Vec3.atCenterOf(road(t))
        val dx = victim.x - at.x
        val dz = victim.z - at.z
        if (dx * dx + dz * dz > KILL_RANGE * KILL_RANGE || kotlin.math.abs(victim.y - at.y) > KILL_RANGE) return
        val part = t.part(e.killer.uuid) ?: return
        val count = kills(t) + 1
        setKills(t, count)
        part.did = part.did or Part.WALKED
        if (count >= wanted(t)) {
            val was = ready(t)
            set(t, SAFE)
            // The road kept is the better route: it decides, not the Marks
            for (p in t.parts) if (p !== part) p.did = p.did and Part.DECIDED.inv()
            part.did = part.did or Part.DECIDED
            c.why(t, "cart.safe", e.killer.gameProfile.name, count.toString())
            readyNow(c.level, t, was)
            CartWatch.safe(c, t)
        }
        c.data.changed()
    }

    /** The camp's wardfire is kindled: the road is kept with three. */
    private fun kindled(c: Ctx, t: Tale, e: Event.Kindled) {
        if (flag(t, LIT)) return
        val at = road(t)
        val dx = (e.pos.x - at.x).toDouble()
        val dz = (e.pos.z - at.z).toDouble()
        if (sqrt(dx * dx + dz * dz) > CLEARING + 2) return
        set(t, LIT)
        c.why(t, "cart.lit", e.player.gameProfile.name)
        c.data.changed()
    }

    /**
     * The household is at its door: if the tale handed them to the comings, it ends with the way home made (A, B or C); if the comings
     * brought them home on their own (good times, a command), the camp is let go and it ends C.
     */
    private fun returned(c: Ctx, t: Tale) {
        CartWatch.atDoor(c, t)
        if (flag(t, HOME)) return c.end(t, route(t))
        set(t, OWN)
        c.why(t, "cart.own", family(t))
        c.data.changed()
        c.end(t, Ending.C)
    }

    // ---- The world's way ----

    /**
     * At each step's day, without its act: nobody asked or found them, and the tale goes on as the world has it; no timber is W (they go on
     * down the road); no choice is the family's own (they wait for first light); a night not kept is C's way; and the going is the record's
     * (the comings bring them home now, a player near or not).
     */
    override fun settle(c: Ctx, t: Tale) {
        when (t.step) {
            AT_ASK -> {
                c.why(t, "cart.unasked", family(t))
                c.next(t)
            }
            AT_MEET -> {
                if (!flag(t, MET)) c.why(t, "cart.unmet", family(t))
                c.next(t)
            }
            AT_GIVE -> {
                c.why(t, "cart.unmended", family(t))
                c.end(t, Ending.W)
            }
            AT_CHOOSE -> {
                val was = ready(t)
                set(t, WAIT)
                readyNow(c.level, t, was)
                c.why(t, "cart.unchosen", family(t))
                c.go(t, AT_ROAD)
            }
            AT_ROAD -> {
                val was = ready(t)
                if (!was) {
                    set(t, WAIT)
                    c.why(t, "cart.unkept", family(t))
                }
                readyNow(c.level, t, was)
                c.go(t, AT_HOME)
            }
            else -> {
                if (!flag(t, HOME)) CartWatch.handOff(c, t)
                if (t.open) c.end(t, route(t))
            }
        }
    }

    /** The neighbour or the guard has gone with no one of their kind to stand in: the asking goes on without the one, the watch without the other. */
    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            NEIGHBOUR -> if (t.step == AT_ASK) c.next(t)
            GUARD -> CartWatch.forgetGuard(t)
        }
    }

    /** A stand-in for the guard is held at the gate as the old one was, from the next second. */
    override fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {
        if (slot == GUARD) CartWatch.forgetGuard(t)
    }

    // ---- The endings ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        val level = c.level
        CartWatch.ending(c, t, ending)
        // The family is home: the parent's slot has its resident now, so the thanks and the flips reach the household
        val parent = Residents.get(level)[parentId(t)]?.takeIf { it.alive && it.left < 0 }
        if (ending != Ending.W && parent != null) {
            t.cast(PARENT)?.let {
                it.resident = parent.id
                it.name = parent.name
            }
            c.data.changed()
            TaleEngine.reindex()
        }
        val home = parent?.home ?: -1
        val thankers = t.parts.filter { TaleShares.holds(it) }.map { it.player }
        when (ending) {
            Ending.A -> {
                if (home >= 0) c.protect(t, home, 20)
                if (parent != null) c.flip(t, PARENT, Trait.CHEERFUL)
                c.flip(t, NEIGHBOUR, Trait.CHATTY)
                for (p in thankers) c.favour(t, NEIGHBOUR, p, Favour.TALE_HELPED)
            }
            Ending.B -> {
                if (home >= 0) c.protect(t, home, 20)
                for (p in thankers) c.favour(t, NEIGHBOUR, p, Favour.TALE_HELPED)
            }
            Ending.C -> {
                if (home >= 0) c.protect(t, home, 5)
                // The street heard late: the neighbour's thanks alone
                for (p in thankers) c.favour(t, NEIGHBOUR, p, Favour.TALE_HELPED)
            }
            Ending.W -> c.flip(t, NEIGHBOUR, Trait.SHY)
        }
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val title = TaleKeys.title(id)
        val camp = CartWatch.campAt(t)?.let { BlockPos.containing(it) } ?: road(t)
        val front = CartWatch.frontOf(t)
        return when (t.step) {
            AT_ASK -> View(title, status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(given(t.cast(NEIGHBOUR)?.name.orEmpty())), target = camp)
            AT_MEET -> View(title, status = TaleKeys.status(id, AT_MEET), statusArgs = listOf(family(t)), target = camp)
            AT_GIVE -> View(title, status = TaleKeys.status(id, AT_GIVE), statusArgs = listOf((TIMBER - timber(t)).coerceAtLeast(0).toString()), target = camp)
            AT_CHOOSE -> View(title, status = TaleKeys.status(id, AT_CHOOSE), target = camp)
            AT_ROAD -> when {
                ready(t) -> View(title, status = DAWN_STATUS, tone = View.Tone.DONE, target = camp)
                flag(t, WATCH) && !flag(t, KEEP) -> View(title, status = WATCH_STATUS, target = front ?: camp)
                else -> View(title, status = TaleKeys.status(id, AT_ROAD), statusArgs = listOf(kills(t).toString(), wanted(t).toString()), target = camp)
            }
            else -> View(title, status = TaleKeys.status(id, AT_HOME), tone = View.Tone.DONE, target = front ?: camp)
        }
    }

    /** The tracker's lines while the family waits for first light, and while the watch is to be paid at the gate. */
    private const val DAWN_STATUS = "gui.guildmark.tale.cart.status.dawn"
    private const val WATCH_STATUS = "gui.guildmark.tale.cart.status.watch"

    // ---- The telling ----

    /** The S1 variant at the old house while the neighbour asks (10:00 to 18:00); the rest is played at the camp, which no capital's stage reaches. */
    override fun scene(c: Ctx, t: Tale): Stage? = if (t.open && t.step == AT_ASK) CartScenes.waiting(c, t) else null

    // ---- The talk ----

    override fun asker(t: Tale): String = NEIGHBOUR

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val family = TaleLines.lit(family(t))
        val side = WayOut.sideKey(side(t))
        return when (beat) {
            "ask" -> {
                val days = moved(turn.level, t)?.let { (Comings.dated(turn.level) - it.day).coerceAtLeast(0L) } ?: return null
                when (days) {
                    0L -> TaleLines.facts("talk.detail.tale.cart.ask_today", family, side)
                    1L -> TaleLines.facts("talk.detail.tale.cart.ask_yesterday", family, side)
                    else -> TaleLines.facts("talk.detail.tale.cart.ask", family, side, days)
                }
            }
            "rumour" -> TaleLines.facts("talk.detail.tale.cart.rumour", family, side)
            "stand" -> if (flag(t, MENDED)) TaleLines.facts("talk.detail.tale.cart.stand_mended", family, side)
            else TaleLines.facts("talk.detail.tale.cart.stand", family, side, (TIMBER - timber(t)).coerceAtLeast(0))
            "wheel" -> TaleLines.facts("talk.detail.tale.cart.wheel", (TIMBER - timber(t)).coerceAtLeast(0))
            "night" -> if (flag(t, LIT)) TaleLines.facts("talk.detail.tale.cart.lit", wanted(t))
            else TaleLines.facts("talk.detail.tale.cart.night", KILLS, KILLS_LIT)
            "keep" -> TaleLines.facts("talk.detail.tale.cart.keep", kills(t), wanted(t))
            "guard_yes" -> TaleLines.facts("talk.detail.tale.cart.guard_yes", WATCH_MARKS)
            "moved" -> TaleLines.facts("talk.detail.tale.cart.moved", family, side)
            else -> null
        }
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = when (if (beat == "greet") greetBeat(turn) else beat) {
        "ask", "passing" -> Activity.SIGH
        "found" -> Activity.WAVE_OFF
        "wheel" -> Activity.SHRUG
        "night" -> Activity.SIGH
        "keep", "safe" -> Activity.NOD
        "go_ahead" -> Activity.POINT
        "guard_yes" -> Activity.TALK
        else -> null
    }

    /** The neighbour points down the road to the camp; the guard too; the parent, going, toward the town. */
    override fun toward(beat: String, turn: TaleTurn): BlockPos? = when (if (beat == "greet") greetBeat(turn) else beat) {
        "ask", "guard_yes" -> road(turn.tale)
        "go_ahead" -> CartWatch.frontOf(turn.tale)
        else -> null
    }

    /** The parent, turned to, says what the step has them say; the guard, at the night step with the watch asked for, his fee. */
    override fun greet(turn: TaleTurn): TaleSay? {
        val beat = greetBeat(turn) ?: return null
        return turn.say(beat, detailFor(beat, turn))
    }

    /** The line [greet] says, and so its gesture and where it points. */
    private fun greetBeat(turn: TaleTurn): String? {
        val t = turn.tale
        return when (turn.slot) {
            PARENT -> when {
                !flag(t, MET) -> "found"
                t.step <= AT_GIVE -> "wheel"
                t.step == AT_CHOOSE -> "night"
                t.step == AT_ROAD -> when {
                    flag(t, SAFE) -> "safe"
                    ready(t) -> "night"
                    flag(t, KEEP) && dark(turn.level) -> "keep"
                    else -> "night"
                }
                else -> "go_ahead"
            }
            GUARD -> if (t.step == AT_ROAD && flag(t, WATCH) && !flag(t, PAID)) "guard_yes" else null
            else -> null
        }
    }

    /** The grey words of the parent's and the guard's greeting: the timber still wanted, the night's count, "Come back at dusk" by day. */
    private fun detailFor(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        return when {
            beat == "wheel" -> detail("wheel", turn)
            beat == "night" && t.step == AT_CHOOSE -> detail("night", turn)
            beat == "night" && t.step == AT_ROAD && !ready(t) && flag(t, KEEP) -> TaleLines.facts("talk.detail.tale.cart.day")
            beat == "night" && t.step == AT_ROAD && !ready(t) && flag(t, WATCH) ->
                TaleLines.facts("talk.detail.tale.cart.watch", WayOut.sideKey(side(t)), WATCH_MARKS)
            beat == "keep" -> detail("keep", turn)
            beat == "guard_yes" -> detail("guard_yes", turn)
            else -> null
        }
    }

    override fun options(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        return when (turn.slot) {
            PARENT -> when (t.step) {
                AT_MEET -> listOf(mend(), need(), comeHome())
                AT_GIVE -> listOf(give(), need())
                AT_CHOOSE -> listOfNotNull(keep(), if (watchable(turn)) watch() else null, waitForLight())
                else -> listOf()
            }
            GUARD -> if (t.step == AT_ROAD && flag(t, WATCH) && !flag(t, PAID)) listOf(pay()) else listOf()
            else -> listOf()
        }
    }

    /** The watch can be asked only with a guard in the cast to ask. */
    private fun watchable(turn: TaleTurn): Boolean = turn.tale.cast(GUARD)?.let { it.resident >= 0 && it.gone.isEmpty() } == true

    private fun think() = TaleReply("ask.think", gesture = Activity.THINK)

    private fun label(word: String, vararg args: Any): Component = Component.translatable("talk.guildmark.choice.tale.cart.$word", *args)

    /** "I'll mend the wheel.": the timber is next. */
    private fun mend() = TaleOption("mend", label("mend")) { turn ->
        val t = turn.tale
        if (t.step != AT_MEET) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        // The reason first, so that what the next step adds is newer than it
        t.addWhy(Why("cart.mending", listOf(turn.who)))
        turn.changed()
        c.go(t, AT_GIVE)
        TaleReply(listOf(turn.say("wheel", detail("wheel", turn))), Activity.SHRUG, toward = CartWatch.wheelAt(t)?.let { BlockPos.containing(it) })
    }

    /** "What do you need?": the wheel again, with the timber it wants in grey. */
    private fun need() = TaleOption("need", label("need")) { turn ->
        if (turn.step != AT_MEET && turn.step != AT_GIVE) return@TaleOption null
        TaleReply(listOf(turn.say("wheel", detail("wheel", turn))), Activity.SHRUG, toward = CartWatch.wheelAt(turn.tale)?.let { BlockPos.containing(it) })
    }

    /** "Come home with me.": they cannot leave the cart. */
    private fun comeHome() = TaleOption("home", label("home")) { turn ->
        if (turn.step != AT_MEET) return@TaleOption null
        TaleReply(listOf(turn.say("wheel")), Activity.SHRUG)
    }

    /**
     * "Here's some timber.": a plank or a log at a time ([Materials.take], a plank before a log) until four units are given; a small overshoot
     * is the player's gift. The fourth mends the wheel: *The Wheel* is played, and the choice for the night is next.
     */
    private fun give() = TaleOption("give", label("give")) { turn ->
        val t = turn.tale
        if (t.step != AT_GIVE) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        val part = turn.part() ?: return@TaleOption think()
        var got = timber(t)
        var took = 0
        while (got < TIMBER) {
            val units = Materials.take(player.inventory, Stuff.TIMBER)
            if (units <= 0) break
            got += units
            took += units
        }
        if (took == 0) return@TaleOption TaleReply(listOf(turn.say("wheel", detail("wheel", turn))), Activity.SHRUG)
        setTimber(t, got)
        part.did = part.did or Part.GAVE
        t.addWhy(Why("cart.timber", listOf(turn.who, took.toString())))
        turn.changed()
        if (got < TIMBER) {
            return@TaleOption TaleReply(listOf(turn.say("thanks_wood", TaleLines.facts("talk.detail.tale.cart.more", TIMBER - got))), Activity.NOD)
        }
        set(t, MENDED)
        t.addWhy(Why("cart.mended", listOf(turn.who)))
        turn.changed()
        CartWatch.mend(c, t, player)
        c.go(t, AT_CHOOSE)
        // The talk ends so the parent can set to work
        TaleReply(listOf(turn.say("thanks_wood")), Activity.NOD, ends = true)
    }

    /** "I'll keep the road for you tonight.": the night step, counting the kills. */
    private fun keep() = TaleOption("road", label("road")) { turn -> choose(turn, KEEP, "cart.road", "keep") }

    /** "I'll ask the watch to ride with you.": the night step, the guard at the gate to be paid. */
    private fun watch() = TaleOption("watch", label("watch")) { turn -> choose(turn, WATCH, "cart.watch", "keep") }

    /** "Wait for first light.": neither; the family takes its chance at first light (C). */
    private fun waitForLight() = TaleOption("wait", label("wait")) { turn -> choose(turn, WAIT, "cart.wait", "night") }

    private fun choose(turn: TaleTurn, flag: Int, why: String, line: String): TaleReply? {
        val t = turn.tale
        if (t.step != AT_CHOOSE) return null
        val c = turn.ctx() ?: return think()
        val was = ready(t)
        t.addWhy(Why(why, listOf(if (flag == WAIT) family(t) else turn.who)))
        set(t, flag)
        turn.part()?.let { it.did = it.did or Part.CHOSE }
        readyNow(c.level, t, was || flag != WAIT)
        turn.changed()
        c.go(t, AT_ROAD)
        val detail = when (flag) {
            KEEP -> detail("night", turn)
            WATCH -> TaleLines.facts("talk.detail.tale.cart.watch", WayOut.sideKey(side(t)), WATCH_MARKS)
            else -> null
        }
        return TaleReply(listOf(turn.say(line, detail)), if (flag == WAIT) null else Activity.NOD)
    }

    /** "Here are 12 Marks.": two of the watch will ride at first light; the Marks are held until they do. */
    private fun pay() = TaleOption("pay", label("pay", WATCH_MARKS)) { turn ->
        val t = turn.tale
        if (t.step != AT_ROAD || flag(t, PAID)) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        when (TaleOptions.check(turn, WATCH_MARKS)) {
            TaleOptions.Put.SHORT -> return@TaleOption TaleReply(listOf(turn.say("guard_yes", TaleLines.facts("talk.detail.tale.cart.short", WATCH_MARKS))), Activity.SHRUG)
            TaleOptions.Put.LATER -> return@TaleOption think()
            else -> {}
        }
        // The reason first, so that what the next step adds is newer than it
        val why = Why("cart.paid", listOf(turn.who, family(t)))
        t.addWhy(why)
        if (TaleOptions.put(turn, WATCH_MARKS) != TaleOptions.Put.DONE) {
            if (t.why.lastOrNull() === why) t.why.removeAt(t.why.size - 1)
            return@TaleOption think()
        }
        val was = ready(t)
        set(t, PAID)
        turn.part()?.let { it.did = it.did or Part.GAVE or (if (flag(t, SAFE)) 0 else Part.DECIDED) }
        readyNow(c.level, t, was)
        turn.changed()
        CartWatch.paid(c, t)
        // Paid on the step's last day the close would come before first light and give the Marks back: the watch is set to ride now
        if (turn.left == 0L) c.go(t, AT_HOME)
        TaleReply(listOf(turn.say("guard_set")), Activity.SALUTE, ends = true)
    }

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    // ---- For the watch and the commands ----

    /** The timber given so far, as the record has it (for `tale cart`). */
    internal fun timberGiven(t: Tale): Int = timber(t)

    /** What the record holds of a forced start's made-up times, for `tale cart`. */
    internal fun madeUp(t: Tale): Boolean = flag(t, MADE)

    /** The parent's slot is taken by the next adult of the household (the old record is gone); false if there is none. */
    internal fun nextParent(level: ServerLevel, t: Tale): Boolean {
        val m = moved(level, t) ?: return false
        val residents = Residents.get(level)
        val next = m.members.mapNotNull { residents[it] }.filter { it.id != parentId(t) && it.role.adult && away(it) }.minByOrNull { it.id } ?: return false
        t.n[PARENT_ID] = next.id
        t.cast(PARENT)?.name = next.name
        // A kin slot that was theirs is no one's now
        KIN.firstOrNull { t.cast(it)?.name == next.name }?.let { slot -> t.cast(slot)?.name = "" }
        return true
    }
}
