package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Cast
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Gone
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Planned
import io.github.vaspyyy.guildmark.tales.Seeding
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Step
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCast
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleEscort
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleOption
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
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import kotlin.math.sqrt

/**
 * Where the Little One Went (living-world/stories/design.md 8.4): a child of a household that came to the town a few days ago has gone out
 * of the gate in the afternoon to walk back to the old house. The parent calls at the gate; by dusk the road belongs to the night. The tale is
 * a search, a talk with a frightened child and a slow walk home in the dark the mod already makes: the gate, the road, the wardfire, the watch.
 * It is the shortest tale, a single afternoon and night, and the only one with a clock in it. Nothing in it needs a weapon, and the child cannot
 * be hurt.
 *
 * **The clock.** A tale is seeded at the close, two in the morning, and the child goes out at three in the afternoon: the tale keeps no timer, the
 * clock is read whenever something asks, and the record says the child is out from 15:00 ([left]) and missed from 15:30 ([missed]). Until the
 * parent has missed the child she is no one's asker ([asker] names a slot nobody is in), no mote rises from her and no rumour is begun; at 15:30 the
 * watch ([ChildWatch], once a second) begins the rumour. A forced start (`tale start child`) says the child is out already, at whatever hour it
 * is, and that it made that up.
 *
 * **The steps.** 0 the parent asks (ASK); 1 the guard at the gate tells which way the small one went (MEET, a choice of his); 2 the find (WATCH: the
 * first player within six blocks of the child, which the watch sees; it joins them to the tale, sets the bit, and moves on to 3); 3 the walk home
 * (WATCH: the child's own choice starts [TaleEscort]'s child variant, and its arrival at the parent's mark tells the tale); 4 the reunion (SCENE,
 * S6). Every step is settled at the close that follows the seed, as the table says: not found is W, found and not home is C, home is A (before
 * 19:00) or B (after).
 *
 * **First light.** The close decides C or W, but a child is never left on the night road for it: with a player near enough to see, the close
 * keeps the tale open a few hours more ([DAWN]), the child held where they are (beyond harm, as every tale body is), and at first light the
 * guard walks out to them and brings them in by the road ([ChildWatch.dawn], W at 06:00, C at 07:00); the tale ends as they reach the gate. With
 * no one near, the close ends it at once and the record does the rest (the child is home, out of sight). The next settling ends it whatever
 * happens, so `tale step` twice ends it now.
 *
 * **What the tale keeps** ([numbers]): the place on the road the child sits at (`x`, `y`, `z`), the flags below, the gate's front (the parent's mark)
 * and the gate. The child's own mark and the clearing the watch found are memory ([ChildWatch]): a restart sets them down again from these.
 */
object Child : Template, TaleVoice, TaleWords.Names {
    override val id: String = "child"
    override val size: Int = 2

    /** Every step is settled at the next close: the whole tale is an afternoon and a night. */
    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 0),
        Step(Verb.MEET, by = 0),
        Step(Verb.WATCH, by = 0, real = true, needs = { Tales.scenes() }),
        Step(Verb.WATCH, by = 0, real = true, hears = setOf(Event.Kind.ARRIVED), needs = { Tales.scenes() }),
        Step(Verb.SCENE, by = 0),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "home"),
        Ending(Ending.B, "night"),
        Ending(Ending.C, "found"),
        Ending(Ending.W, "watch"),
    )

    /** The guard and the neighbour are anyone of their kind; the parent and the child are essential. */
    override val standing: Set<String> = setOf(GUARD, HELPER)

    override val numbers: List<String> = listOf("x", "y", "z", "flags", "front x", "front y", "front z", "gate")
    override val strings: List<String> = listOf("side")

    // ---- The record ----

    const val PARENT = "parent"
    const val CHILD = "child"
    const val GUARD = "guard"
    const val HELPER = "helper"

    const val AT_ASK = 0
    const val AT_GUARD = 1
    const val AT_FIND = 2
    const val AT_WALK = 3
    const val AT_SCENE = 4

    private const val X = 0
    private const val FLAGS = 3
    private const val FRONT = 4
    private const val GATE = 7

    /**
     * The flags, in `n[3]`: the child is found; the walk was begun; the child came to the gate; and it was after dusk when they did; the calling
     * has been played; the rumour begun; the child is out whatever the hour (a forced start); a neighbour asks (no household to protect); the
     * close has come and gone and the watch brings the child in at first light (C if they were found, else W).
     */
    const val FOUND = 1
    const val WALKING = 2
    const val ARRIVED = 4
    const val NIGHT = 8
    const val CALLED = 16
    const val RUMOUR = 32
    const val OUT = 64
    const val NEIGHBOUR = 128
    const val DAWN = 256

    /**
     * The clock, in the offsets the days keep (offset 0 is two in the morning): the child goes at 15:00, the parent misses them at 15:30, and
     * dusk is 19:00; first light is 06:00, when the watch goes out for a child nobody found (W), and 07:00 for one who was found and not walked
     * home (C); a bring-in not begun by noon is the record's.
     */
    val LEAVES: Int = Days.clock(15.0)
    val CALLS: Int = Days.clock(15.5)
    val DUSK: Int = Days.clock(19.0)
    val FIRST_LIGHT: Int = Days.clock(6.0)
    val WATCH_OUT: Int = Days.clock(7.0)
    val NOON: Int = Days.clock(12.0)

    /** The wardfire a child is found at must be within this many blocks of the place on the road. */
    const val CLEARING = 24

    /** The households that came in the last [ARRIVED_DAYS] market days are the ones a child may go from; the road is [ROAD_MIN] to [ROAD_MAX] blocks long. */
    private const val ARRIVED_DAYS = 8
    private const val ROAD_MIN = 50
    private const val ROAD_MAX = 90

    internal fun flag(t: Tale, f: Int): Boolean = t.n[FLAGS] and f != 0

    internal fun set(t: Tale, f: Int) {
        t.n[FLAGS] = t.n[FLAGS] or f
    }

    internal fun clock(level: ServerLevel): Int = Days.offsetOf(level.overworldClockTime)

    internal fun place(t: Tale): BlockPos = BlockPos(t.n[X], t.n[X + 1], t.n[X + 2])

    internal fun front(t: Tale): BlockPos = BlockPos(t.n[FRONT], t.n[FRONT + 1], t.n[FRONT + 2])

    /** The child is out: a forced start says so from the first, the tale has turned if the guard has been asked, and the clock says so from 15:00. */
    internal fun left(level: ServerLevel, t: Tale): Boolean = flag(t, OUT) || t.step >= AT_GUARD || clock(level) >= LEAVES

    /** The parent has missed the child: from 15:30 (or at once for a forced start). */
    internal fun missed(level: ServerLevel, t: Tale): Boolean = flag(t, OUT) || t.step >= AT_GUARD || clock(level) >= CALLS

    /** The light is gone: from dusk to first light. */
    internal fun dark(level: ServerLevel): Boolean = clock(level).let { it >= DUSK || it < FIRST_LIGHT }

    /** The ending the close decided, which the watch brings in at first light: C if the child was found, else W. */
    internal fun decided(t: Tale): String = if (flag(t, FOUND)) Ending.C else Ending.W

    /** Whether it is the hour the watch goes out for [t]'s child (06:00 for a child nobody found, 07:00 for one found), and not yet noon. */
    internal fun firstLight(level: ServerLevel, t: Tale): Boolean = clock(level).let { it >= (if (flag(t, FOUND)) WATCH_OUT else FIRST_LIGHT) && it < NOON }

    private fun given(name: String): String = name.substringBefore(' ')

    private fun plural(family: String): String = if (family.isBlank() || family.endsWith("s", ignoreCase = true)) family else family + "s"

    internal fun childName(t: Tale): String = given(t.cast(CHILD)?.name.orEmpty())

    internal fun familyOf(t: Tale): String {
        val own = t.cast(CHILD)?.name.orEmpty().substringAfter(' ', "")
        return plural(own.ifBlank { t.cast(PARENT)?.name.orEmpty().substringAfter(' ', "") })
    }

    /** The words a sentence is said with (`TaleWords`): the child's given name, and the family with an s ("The Brooks went back down the road"). */
    override fun words(t: Tale): List<String> = listOf(childName(t), familyOf(t))

    // ---- The seed ----

    override fun needs(): Boolean = Comings.enabled() && Tales.scenes()

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val data = TaleData.get(level)
        val residents = Residents.get(level)
        val arrived = Comings.arrived(level, c.capital, ARRIVED_DAYS)
        if (arrived.isEmpty()) return c.none("no household has come to the town in the last $ARRIVED_DAYS market days")
        val may = Comings.leavers(Comings.ctx(level, c.here.places, begin = false)).map { it.home.id }.toSet()
        var found = "none of the households that came is a citizens' household with a child, at home, free of a tale, that could leave if it came to it"

        class Pick(val adult: Resident, val kid: Resident, val day: Long)

        val picks = ArrayList<Pick>()
        for (h in arrived.sortedBy { it.id }) {
            if (h.reason != Comings.REPLACE && h.reason != Comings.GROW) {
                found = "the households that came were of a trade, or came home (only those that came to replace or to grow the town)"
                continue
            }
            val people = h.members.mapNotNull { residents[it] }
            if (people.size != h.members.size || people.any { !it.alive || it.left >= 0 || it.home != h.home }) continue
            val kids = people.filter { !it.role.adult }
            if (kids.isEmpty()) {
                found = "the household that came has no child"
                continue
            }
            val adults = people.filter { it.role.adult }
            if (adults.isEmpty() || adults.any { it.role != TownRole.CITIZEN }) continue
            if (h.home !in may) continue
            val adult = adults.filter { TaleCast.free(it, data) }.minByOrNull { it.id } ?: continue
            val kid = kids.filter { TaleCast.free(it, data, child = true) }.minByOrNull { it.id } ?: continue
            picks += Pick(adult, kid, h.day)
        }
        if (picks.isEmpty()) return c.none(found)
        val pick = picks[c.random.nextInt(picks.size)]
        val spot = WayOut.spot(level, c.here, c.random, ROAD_MIN, ROAD_MAX) ?: return c.none("the town has no road out that reaches $ROAD_MIN blocks")
        val front = WayOut.front(c.here.places, spot.gate)
        val lodgings = c.here.places.lodgings
        val taken = hashSetOf(pick.adult.id, pick.kid.id)
        // A guard on the gate: the one whose home is nearest it
        val guard = TaleCast.pool(level, c.here.capital.center).filter { it.role == TownRole.GUARD && it.id !in taken }
            .minWithOrNull(compareBy<Resident>({ r -> lodgings[r.home]?.box?.center?.distSqr(front) ?: Double.MAX_VALUE }, { it.id }))
            ?: return c.none("no guard is free to stand at the gate")
        taken += guard.id
        // A neighbour of the household: the one from the nearest home, a grown person free of a tale
        val from = lodgings[pick.adult.home]?.box?.center ?: front
        val helper = Witness.neighbours(level, pick.adult)
            .filter { it.alive && it.role.adult && it.id !in taken && TaleCast.free(it, data) }
            .minWithOrNull(compareBy<Resident>({ r -> lodgings[r.home]?.box?.center?.distSqr(from) ?: Double.MAX_VALUE }, { it.id }))
        val cast = ArrayList<Cast>()
        cast += Cast(PARENT, pick.adult.id, pick.adult.name)
        cast += Cast(CHILD, pick.kid.id, pick.kid.name)
        cast += Cast(GUARD, guard.id, guard.name)
        if (helper != null) cast += Cast(HELPER, helper.id, helper.name)
        val days = (Comings.dated(level) - pick.day).coerceAtLeast(0L)
        val town = TaleWords.town(level, c.capital)
        val family = plural(pick.adult.family)
        val why = when (days) {
            0L -> Why("child.start_0", listOf(family, town))
            1L -> Why("child.start_1", listOf(family, town))
            else -> Why("child.start", listOf(family, town, days.toString()))
        }
        val n = IntArray(8)
        n[X] = spot.point.x
        n[X + 1] = spot.point.y
        n[X + 2] = spot.point.z
        // A forced start has the child out already, and the rumour is begun with the tale (the seed's own)
        n[FLAGS] = if (c.forced) OUT or RUMOUR else 0
        n[FRONT] = front.x
        n[FRONT + 1] = front.y
        n[FRONT + 2] = front.z
        n[GATE] = spot.gate.ordinal
        return Planned(
            cast, why, step = AT_ASK, n = n.toList(), s = listOf(WayOut.side(c.here, spot.gate)),
            madeUp = if (c.forced) "the child is out already, whatever the hour it is (a real tale has them leave at 15:00 and the parent miss them at 15:30)" else "",
        )
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        // The parent is at the gate for the walk, wherever the child is found
        if (t.step == AT_WALK) ChildWatch.parent(c, t)
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        if (e is Event.Arrived && e.tale == t.id && e.slot == CHILD) arrived(c, t)
    }

    /** The child is at the parent's mark: the player who walked them is paid for the deed, the time is kept (before or after dusk), and the reunion is next. */
    internal fun arrived(c: Ctx, t: Tale) {
        if (flag(t, ARRIVED) || t.step != AT_WALK) return
        val level = c.level
        set(t, ARRIVED)
        if (clock(level) >= DUSK) set(t, NIGHT)
        val body = TaleBodies.body(level, t, CHILD)
        val near = level.players().filter { body != null && it.isAlive && !it.isSpectator && it.distanceTo(body) < NEAR_ARRIVAL }.mapNotNull { t.part(it.uuid) }
        for (p in near) p.did = p.did or Part.WALKED
        // The deciding deed is the walk: the player whose word began it, else the first near, else the one who took the tale up (after the
        // close it is the find, which the close has marked: the walk is a real deed, but C was decided)
        val walker = ChildWatch.walkers[t.id]?.let { t.part(it) } ?: near.firstOrNull() ?: t.lead
        walker?.let { it.did = it.did or Part.WALKED or (if (flag(t, DAWN)) 0 else Part.DECIDED) }
        c.why(t, "child.home", childName(t))
        c.data.changed()
        c.go(t, AT_SCENE)
    }

    private const val NEAR_ARRIVAL = 40.0

    // ---- The world's way ----

    /**
     * The close (or `tale step`): home is A or B; found and not walked home is C, and the finder's deed settles it; not found is W. A C or a W
     * with a player near enough to see is brought in by the watch at first light ([DAWN], [ChildWatch.dawn]); with none near, or settled a
     * second time, it ends now.
     */
    override fun settle(c: Ctx, t: Tale) {
        if (t.step == AT_SCENE) return c.end(t, arrival(t))
        val ending = decided(t)
        if (ending == Ending.C) decide(t)
        if (flag(t, DAWN) || !ChildWatch.waits(c, t)) return c.end(t, ending)
        set(t, DAWN)
        c.why(t, if (ending == Ending.C) "child.dawn_found" else "child.dawn", childName(t))
        c.data.changed()
    }

    /** Whoever found the child settles C: the first with the bit, if no one's deed has settled it yet. */
    private fun decide(t: Tale) {
        if (t.parts.any { it.did(Part.DECIDED) }) return
        (t.parts.firstOrNull { it.did(Part.WALKED) } ?: t.lead)?.let { it.did = it.did or Part.DECIDED }
    }

    /** A is the child at the mark before dusk, B after; after the close it is C, which the close decided. */
    internal fun arrival(t: Tale): String = when {
        flag(t, DAWN) -> Ending.C
        flag(t, NIGHT) -> Ending.B
        else -> Ending.A
    }

    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            // A child is never lost in a tale: if the record has gone, they were found
            CHILD -> c.end(t, Ending.C)
            PARENT -> takeTheParent(c, t)
            // With no guard to be had the asking is done without him
            GUARD -> {
                c.why(t, "child.noguard", t.cast(GUARD)?.name.orEmpty())
                if (t.step == AT_GUARD) c.next(t)
            }
        }
    }

    /** The other adult of the household asks in the parent's place; with none a neighbour does, and the household is not protected after (5.6). */
    private fun takeTheParent(c: Ctx, t: Tale) {
        val cast = t.cast(PARENT) ?: return
        val level = c.level
        val home = c.resident(t, CHILD)?.home ?: -1
        val center = c.here.capital.center
        val taken = t.cast.mapTo(HashSet()) { it.resident }
        val other = Residents.get(level).of(center)
            .filter { home >= 0 && it.home == home && it.role.adult && it.id !in taken && TaleCast.free(it, c.data) }
            .minByOrNull { it.id }
        val lodgings = c.here.places.lodgings
        val from = lodgings[home]?.box?.center
        val neighbour = if (other != null) null else TaleCast.pool(level, center).filter { it.id !in taken && it.role == TownRole.CITIZEN }
            .minWithOrNull(compareBy<Resident>({ r -> from?.let { f -> lodgings[r.home]?.box?.center?.distSqr(f) } ?: Double.MAX_VALUE }, { it.id }))
        val next = other ?: neighbour ?: return
        val old = cast.name
        cast.resident = next.id
        cast.name = next.name
        cast.gone = ""
        cast.held = false
        ChildWatch.placed.remove("${t.id}:$PARENT")
        if (other == null) set(t, NEIGHBOUR)
        c.why(t, if (other != null) "child.other" else "child.neighbour", next.name, old)
        c.data.changed()
        TaleEngine.reindex()
    }

    // ---- The endings ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        val home = c.resident(t, CHILD)?.home ?: -1
        val keeps = home >= 0 && !flag(t, NEIGHBOUR)
        ChildWatch.letGo(c, t)
        // A child this ending finds out on the road in sight of a player walks in by it (the watch has brought one in to the gate already, and
        // one nobody can see is home in the record)
        if (ending == Ending.C || ending == Ending.W) ChildWatch.bringIn(c, t)
        when (ending) {
            Ending.A -> {
                if (keeps) c.protect(t, home, 20)
                // The neighbours who searched are friends now
                c.flip(t, PARENT, Trait.CHATTY)
            }
            Ending.B -> if (keeps) c.protect(t, home, 20)
            Ending.C -> {
                if (keeps) c.protect(t, home, 10)
                // The child's own thanks to whoever found them
                for (p in t.parts.filter { TaleShares.holds(it) && it.did(Part.WALKED) }) c.favour(t, CHILD, p.player, Favour.TALE_HELPED)
            }
            Ending.W -> if (keeps) goBack(c, t, home)
        }
        ChildWatch.forget(t.id)
    }

    /**
     * The family goes back down the road (6.5): the town lets them if it can; if it cannot, the parent keeps to themselves and the family
     * stays (a `Why` says why). True if they went.
     */
    private fun goBack(c: Ctx, t: Tale, home: Int): Boolean {
        val parent = c.resident(t, PARENT)
        c.say(t, PARENT, "tale.child.leave", TaleLines.facts("talk.detail.tale.child.leave", TaleLines.lit(familyOf(t))))
        c.release(t, CHILD)
        c.release(t, PARENT)
        if (c.leave(t, home)) {
            c.why(t, "child.left", familyOf(t))
            return true
        }
        if (parent != null && c.flip(t, PARENT, Trait.SHY)) c.why(t, "child.shy", parent.name)
        return false
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val parent = given(t.cast(PARENT)?.name.orEmpty())
        val child = childName(t)
        val args = when (t.step) {
            AT_ASK, AT_SCENE -> listOf(parent)
            AT_GUARD -> listOf()
            else -> listOf(child)
        }
        // After the close only the bringing in is left: the watch goes out from the gate at first light
        if (flag(t, DAWN)) return View(title = TaleKeys.title(id), status = DAWN_STATUS, tone = View.Tone.DONE, target = front(t))
        val target = when (t.step) {
            AT_FIND -> ChildWatch.clearings[t.id] ?: place(t)
            else -> front(t)
        }
        return View(title = TaleKeys.title(id), status = TaleKeys.status(id, t.step), statusArgs = args, target = target)
    }

    /** The tracker's line while the watch is to bring the child in. */
    private const val DAWN_STATUS = "gui.guildmark.tale.child.status.dawn"

    // ---- The telling ----

    override fun scene(c: Ctx, t: Tale): Stage? {
        if (!t.open) return if (t.ending == Ending.C) homecoming(c, t) else gift(c, t)
        return when (t.step) {
            AT_ASK, AT_GUARD -> callingScene(c, t)
            AT_SCENE -> reunion(c, t)
            else -> null
        }
    }

    /** How far from the gate the parent may be for the calling to be played, and how near the neighbour and the guard must be to be in it. */
    private const val FAR_FROM_GATE = 90.0
    private const val NEAR_GATE = 50.0

    /**
     * S2 The Calling (4.3) at the gate, from 15:30 to 19:00: the parent calls the name, a neighbour comes and asks, the guard (a second helper,
     * when no neighbour is near) is asked the way and points down the road. Played once; a forced start may play it at any hour.
     */
    private fun callingScene(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (flag(t, CALLED) || !missed(level, t) || (!flag(t, OUT) && clock(level) >= DUSK)) return null
        val parent = TaleBodies.body(level, t, PARENT) ?: return null
        val front = front(t)
        val at = Vec3.atCenterOf(front)
        if (parent.distanceToSqr(at) > FAR_FROM_GATE * FAR_FROM_GATE) return null
        val helpers = listOf(HELPER, GUARD).mapNotNull { TaleBodies.body(level, t, it) }
            .filter { it !== parent && it.distanceToSqr(at) < NEAR_GATE * NEAR_GATE }
        val stage = Sets.calling(parent, childName(t), front, lookDown(t), helpers, hours = null) ?: return null
        stage.then = { played ->
            if (played && t.open) {
                set(t, CALLED)
                c.data.changed()
            }
        }
        return stage
    }

    /** A point down the road from the gate, at the height of a calling voice, for the faces to turn to. */
    private fun lookDown(t: Tale): Vec3 {
        val front = front(t)
        val at = place(t)
        val dx = (at.x - front.x).toDouble()
        val dz = (at.z - front.z).toDouble()
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1.0)
        return Vec3(front.x + 0.5 + dx / len * 10.0, front.y + 1.0, front.z + 0.5 + dz / len * 10.0)
    }

    /**
     * S6 The Reunion (4.3) at the gate: the parent runs to the child, the guard and the neighbour clap (one by lantern, three in the last
     * light), and the tale ends with it, A in the last light and B in the night; if it cannot be staged the tale ends all the same.
     */
    private fun reunion(c: Ctx, t: Tale): Stage? {
        val level = c.level
        val parent = TaleBodies.body(level, t, PARENT) ?: return null
        val child = TaleBodies.body(level, t, CHILD) ?: return null
        val player = level.getNearestPlayer(child, 24.0) as? ServerPlayer
        val at = parent.position()
        val watchers = listOfNotNull(TaleBodies.body(level, t, GUARD), TaleBodies.body(level, t, HELPER))
            .filter { it.distanceToSqr(at) < NEAR_GATE * NEAR_GATE }.take(if (flag(t, NIGHT)) 1 else 3)
        val stage = Sets.reunion(parent, child, player, watchers, hours = null) ?: return null
        stage.then = { if (t.open && t.step == AT_SCENE) c.end(t, arrival(t)) }
        return stage
    }

    /**
     * The ending C, seen: the watch has brought the child in at first light, and the parent, who waited at the gate all night, runs to them
     * (S6 with no thanks to anyone: nobody walked them home but the guard, who looks on). Nothing unless both are at the gate.
     */
    private fun homecoming(c: Ctx, t: Tale): Stage? {
        val level = c.level
        val at = Vec3.atCenterOf(front(t))
        val parent = TaleBodies.body(level, t, PARENT)?.takeIf { it.distanceToSqr(at) < AT_GATE * AT_GATE } ?: return null
        val child = TaleBodies.body(level, t, CHILD)?.takeIf { it.distanceToSqr(at) < AT_GATE * AT_GATE } ?: return null
        val watchers = listOfNotNull(TaleBodies.body(level, t, GUARD)).filter { it.distanceToSqr(at) < NEAR_GATE * NEAR_GATE }
        return Sets.reunion(parent, child, null, watchers, hours = null)
    }

    /** How near the gate's front the parent and the child must be for the homecoming to be played there. */
    private const val AT_GATE = 20.0

    /** The ending, seen (A and B): the child holds out the wooden horse for luck, and the parent thanks the player. */
    private fun gift(c: Ctx, t: Tale): Stage? {
        if (t.ending != Ending.A && t.ending != Ending.B) return null
        val level = c.level
        val child = TaleBodies.body(level, t, CHILD) ?: return null
        val parent = TaleBodies.body(level, t, PARENT) ?: return null
        if (child.distanceToSqr(parent) > 30.0 * 30.0) return null
        val player = level.getNearestPlayer(child, 20.0) as? ServerPlayer ?: return null
        val you = { player.takeIf { it.isAlive }?.eyePosition ?: parent.eyePosition }
        val k = ArrayList<Move>()
        k += Turn(you)
        k += Stage.Hold(Sets.prop("child"))
        k += Stage.Say("tale.child.gift", gesture = Activity.HAND_OVER, at = you)
        k += Stage.Barrier("gave", 100, at = you)
        val p = ArrayList<Move>()
        p += Turn({ child.eyePosition })
        p += Stage.Barrier("gave", 600, at = { child.eyePosition })
        p += Turn(you)
        p += Stage.Say("tale.child.thanks", gesture = Activity.THANK, at = you)
        return Stage("gift", listOf(Stage.Part(CHILD, child, moves = k), Stage.Part(PARENT, parent, moves = p)), null, child.position())
    }

    // ---- The talk ----

    /** The parent asks, once the child has been missed. Before that no one does (a slot nobody is in), so no mote rises, the opener is not said, and no rumour begins at the seed. */
    override fun asker(t: Tale): String {
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return PARENT
        return if (missed(level, t)) PARENT else NOBODY
    }

    private const val NOBODY = "none"

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val child = TaleLines.lit(childName(t))
        val side = WayOut.sideKey(t.s[0].ifBlank { "north" })
        return when (beat) {
            "ask" -> TaleLines.facts("talk.detail.tale.child.ask", child, side)
            "rumour" -> TaleLines.facts("talk.detail.tale.child.rumour", child, TaleLines.lit(familyOf(t)), side)
            "stand" -> TaleLines.facts("talk.detail.tale.child.stand", child, side)
            "guard" -> TaleLines.facts("talk.detail.tale.child.guard", side)
            else -> null
        }
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = when (beat) {
        "ask", "passing" -> Activity.WRING_HANDS
        "stand" -> Activity.PLEAD
        else -> null
    }

    /** The guard points to the place on the road when he tells of it. */
    override fun toward(beat: String, turn: TaleTurn): BlockPos? = if (beat == "guard") place(turn.tale) else null

    /** The child, turned to by a player who has taken the tale up, says why they went. */
    override fun greet(turn: TaleTurn): TaleSay? = if (turn.slot == CHILD) turn.say("found") else null

    override fun options(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        return when {
            turn.slot == GUARD && t.step == AT_GUARD -> listOf(askGuard())
            turn.slot == CHILD && t.step == AT_WALK && !flag(t, ARRIVED) && !walking(turn) ->
                if (flag(t, WALKING)) listOf(walk("restart", "talk.guildmark.choice.tale.child.restart"), notNow())
                else listOf(calling(), walk("walk", "talk.guildmark.choice.tale.child.walk"), notNow())
            else -> listOf()
        }
    }

    /** Whether the child is on a walk with a player just now (their talk is then the ordinary one). */
    private fun walking(turn: TaleTurn): Boolean {
        val body = TaleBodies.body(turn.level, turn.tale, CHILD) ?: return false
        return (body.director as? TaleEscort)?.done == false
    }

    private fun askGuard() = TaleOption("guard", Component.translatable("talk.guildmark.choice.tale.child.ask_guard")) { turn ->
        val t = turn.tale
        if (t.step != AT_GUARD) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        // The reason first, so that what the next step adds is newer than it
        t.addWhy(Why("child.guard", listOf(turn.name(GUARD), childName(t))))
        turn.changed()
        c.next(t)
        TaleReply(listOf(turn.say("guard", detail("guard", turn))), toward = place(t))
    }

    private fun calling() = TaleOption("calling", Component.translatable("talk.guildmark.choice.tale.child.calling")) { turn ->
        if (turn.step != AT_WALK) return@TaleOption null
        TaleReply(listOf(turn.say("agree")))
    }

    /** The player will walk the child home: the escort begins (the child's variant), and the parent is at the gate to wait. */
    private fun walk(id: String, label: String) = TaleOption(id, Component.translatable(label)) { turn ->
        val t = turn.tale
        if (t.step != AT_WALK || flag(t, ARRIVED)) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val player = turn.player ?: return@TaleOption null
        val body = TaleBodies.body(turn.level, t, CHILD) ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        if (!TaleEscort.start(turn.level, t, CHILD, body, player, front(t), null, 0L, child = true)) return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        // The reason first, so that what the next step adds is newer than it
        if (!flag(t, WALKING)) t.addWhy(Why("child.walking", listOf(turn.who, childName(t))))
        set(t, WALKING)
        ChildWatch.walkers[t.id] = player.uuid
        turn.part()?.let { it.did = it.did or Part.WALKED }
        turn.changed()
        ChildWatch.parent(c, t)
        TaleReply(listOf(turn.say("agree")), ends = true)
    }

    private fun notNow() = TaleOption("notnow", Component.translatable("talk.guildmark.choice.tale.child.notnow")) { turn ->
        if (turn.step != AT_WALK) return@TaleOption null
        TaleReply("ask.yes", gesture = Activity.NOD, back = true)
    }

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    // ---- Stand-ins ----

    /** A stand-in for the guard is held where the old one stood: the watch does it again from the next second. */
    override fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {
        ChildWatch.placed.remove("${t.id}:$slot")
    }
}
