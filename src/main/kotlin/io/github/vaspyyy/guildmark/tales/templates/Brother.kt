package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.atlas.Atlas
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.lair.Lair
import io.github.vaspyyy.guildmark.lair.Lairs
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
import io.github.vaspyyy.guildmark.tales.TalePay
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
import io.github.vaspyyy.guildmark.townsfolk.ComingsData
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * The Brother Under the Hill (living-world/stories/design.md 8.12), Tales T5: tools ran short in the town, and one of the smith's own house
 * went down the old mine two nights ago to bring up iron, and has not come back. The smith cannot leave the forge the town leans on. There
 * are three ways: go down for them (the lair), hire delvers (40 Marks), or light the old fire by the mine and wait (they make for the light
 * if they live); and if nothing is done in time, a cairn is set at the mouth. It is the only tale that sends a player into a lair, and it can
 * be finished with Marks alone.
 *
 * **The seed.** A `wanting` reason of the town's fortunes with tools or coal in it (`Fortunes.reasons`; a forced start makes the shortage up
 * and says so), a SMITH free of a tale whose household has another grown person (no guard, no leader, nobody's faction person) free too: the
 * one who went down. The slot is called `brother` and the tale keeps its title, but nothing a player reads or hears says what the two are
 * to each other or which the one below is: they are "they", by name, or "one of the smith's house". A guard of the town is the captain, a
 * neighbour the helper, the temple's priest (else the oldest) for a laying. **The mine** is an uncleared old mine of rank 1 or 2 within 400
 * blocks of the board (the reuse a lair note makes), else one is made at the first yes as a lair note makes one ([BrotherMine.make]); if none
 * can be, the tale ends C at once with nothing to pay.
 *
 * **The one below** ([BROTHER]) is held from the seed: their day body leaves by the gate if a player sees it, else is gone out of sight, and
 * their tale body is made at their mark in the mine (against the wall of the first hall past the entrance, [BrotherMine.mark]; in a surface
 * ring six blocks from the altar on the town's side) only while a player is near. They are found, led up at their own pace (S8, the slow walk,
 * with their dark lamp), and walked home or left to find their own way; or they make for the fire a player lit at the mouth; or the delvers
 * bring them up the street at first light; or, with the mine's boss fallen by others' hands, they walk up by themselves (C, freed).
 *
 * **The steps.** 0 the smith asks (ASK: at the forge by day, the calling at the gate from 15:30, waiting there until 22:00); 1 the captain
 * at the gate (MEET: the mine goes on the map); 2 the way (CHOOSE: "I'll go down for them.", "I'll hire delvers.", "I'll light the old
 * fire." while it is cold); 3 the deed (WATCH, GIVE or WATCH: up out of the mine, 40 Marks to the delver at the tavern's door, the fire
 * kindled; all three stay open, the first done ends the step); 4 home (SCENE: the homecoming at the forge door, the delvers' Return at
 * first light, or the walk home by first light). A deed of a way (a player below, Marks given, the fire kindled) is the choice too.
 *
 * **What the tale keeps** ([numbers]): the lair, the way, the Marks put in, where they are, the market day the party went or they come home,
 * the gate on the mine's side and the sum, the fire (from the mouth), and the flags below; in [strings], the players who led, paid and
 * kindled, and the forge door. Everything a player is shown is read from these.
 */
object Brother : Template, TaleVoice, TaleWords.Names {
    override val id: String = "brother"
    override val size: Int = 3

    /** Every step hears the mine's boss fall and its fire kindled (a deed of a way counts before its step); the deed's hears the close. */
    private val HEARS = setOf(Event.Kind.LAIR, Event.Kind.KINDLED)

    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2, hears = HEARS),
        Step(Verb.MEET, by = 3, hears = HEARS),
        Step(Verb.CHOOSE, by = 4, real = true, hears = HEARS),
        Step(Verb.WATCH, by = 5, real = true, also = listOf(Verb.GIVE, Verb.WATCH), hears = HEARS + Event.Kind.DAY),
        Step(Verb.SCENE, by = 7, hears = setOf(Event.Kind.LAIR)),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "brought"),
        Ending(Ending.B, "hired"),
        Ending(Ending.C, "lit"),
        Ending(Ending.W, "cairn"),
    )

    /** Its W leaves the times a reason of -1: the town lost one of its own below. */
    override val hurts: Boolean = true

    /** The captain is any guard, the helper any neighbour, the priest any priest; the smith and the one below are themselves. */
    override val standing: Set<String> = setOf(CAPTAIN, HELPER, PRIEST)

    override val numbers: List<String> = listOf("lair", "way", "Marks put in", "where", "the party's or the dawn's day", "gate and sum", "fire", "flags")
    override val strings: List<String> = listOf("leader", "payer", "kindler", "forge door")

    // ---- The record ----

    const val SMITH = "smith"
    const val BROTHER = "brother"
    const val CAPTAIN = "captain"
    const val HELPER = "helper"
    const val PRIEST = "priest"

    /** The made figures: the leader of the hired party at the tavern's door, and the two who come back up the street with them. */
    const val DELVER = "delver"
    const val DELVER2 = "delver2"
    const val DELVER3 = "delver3"

    const val AT_ASK = 0
    const val AT_MEET = 1
    const val AT_CHOOSE = 2
    const val AT_DEED = 3
    const val AT_HOME = 4

    internal const val N_LAIR = 0
    internal const val N_WAY = 1
    internal const val N_PAID = 2
    internal const val N_WHERE = 3
    internal const val N_DAY = 4
    internal const val N_GATE = 5
    internal const val N_FIRE = 6
    internal const val N_FLAGS = 7

    internal const val S_LEADER = 0
    internal const val S_PAYER = 1
    internal const val S_KINDLER = 2
    internal const val S_FORGE = 3

    /** The ways (`n[1]`): none yet, down for them, the delvers, the fire. */
    const val DOWN = 1
    const val HIRE = 2
    const val FIRE = 3

    /** Where they are (`n[3]`): below, being led, out of the mine, home, lost. */
    const val BELOW = 0
    const val LED = 1
    const val OUT = 2
    const val HOME = 3
    const val LOST = 4

    /**
     * The flags, in `n[7]`: found below by a player; the mine's fire kindled; the party went down; they found their own way home; the mine was
     * cleared by others while they were below (or they went from the record); no mine could be made; a forced start made the shortage up; the
     * tale made the mine; a leading was running at the last close; the mine is on every part's map; the smith is gone and the captain asks; the
     * delvers' sum is whole; they came up to the fire; the laying was played; they were told to wait where they stand; the lantern is hung at
     * the forge door.
     */
    const val FOUND = 1
    const val LIT = 2
    const val GONE = 4
    const val ALONE = 8
    const val FREED = 16
    const val NOMINE = 32
    const val FORCED = 64
    const val MADE = 128
    const val LATE = 256
    const val MAPPED = 512
    const val SMITH_GONE = 1024
    const val WHOLE = 2048
    const val ROSE = 4096
    const val LAID = 8192
    const val WAITS = 16384
    const val LAMPED = 32768

    /** The delvers' fee, fixed at the seed (7.5 rule 1; 0.8 of the 56 is 44.8), and a quarter of it, which earns a GIVE's bit. */
    const val SUM = 40
    const val QUARTER = 10

    /** The days they live below: until the deed's close, D5 (a rescue in motion then is carried out, a leading to D7 at most). */
    const val LIVES = 5

    /** The clock: the calling at the gate, the smith there until late; the forge by day; the delver at the tavern; the captain at the gate; the dawns. */
    val CALL_FROM: Int = Days.clock(15.5)
    val CALL_TO: Int = Days.clock(19.0)
    val GATE_TO: Int = Days.clock(22.0)
    val FORGE_FROM: Int = Days.clock(7.0)
    val DELVER_FROM: Int = Days.clock(6.0)
    val DELVER_TO: Int = Days.clock(20.0)
    val CAPTAIN_FROM: Int = Days.clock(8.0)
    val CAPTAIN_TO: Int = Days.clock(18.0)
    val A_DAWN: Int = Days.clock(6.0)
    val C_DAWN: Int = Days.clock(7.0)
    val DOOR_FROM: Int = Days.clock(5.0)
    val DAWN_TO: Int = Days.clock(10.0)

    internal fun flag(t: Tale, f: Int): Boolean = t.n[N_FLAGS] and f != 0

    internal fun set(t: Tale, f: Int) {
        t.n[N_FLAGS] = t.n[N_FLAGS] or f
    }

    internal fun unset(t: Tale, f: Int) {
        t.n[N_FLAGS] = t.n[N_FLAGS] and f.inv()
    }

    internal fun way(t: Tale): Int = t.n[N_WAY]

    internal fun where(t: Tale): Int = t.n[N_WHERE]

    internal fun setWhere(t: Tale, w: Int) {
        t.n[N_WHERE] = w
    }

    /** The mine (its lair record), or null while none is chosen or made, or it is no longer on record. */
    internal fun lair(level: ServerLevel, t: Tale): Lair? = t.n[N_LAIR].takeIf { it > 0 }?.let { Lairs.get(level).lair(it) }

    /** The gate on the mine's side, where the smith calls and waits and the captain stands; null for a town with no gate on a road. */
    internal fun gate(t: Tale): Dir? = Dir.entries.getOrNull((t.n[N_GATE] and 0xFF) - 1)

    private fun setGate(t: Tale, gate: Dir?) {
        t.n[N_GATE] = (t.n[N_GATE] and 0xFF.inv()) or ((gate?.ordinal ?: -1) + 1)
    }

    /** The delvers' fee, as the seed fixed it. */
    internal fun sum(t: Tale): Int = (t.n[N_GATE] ushr 8).takeIf { it > 0 } ?: SUM

    /** What is left of the fee to put in. */
    internal fun owing(t: Tale): Int = (sum(t) - t.n[N_PAID]).coerceAtLeast(0)

    /** The gate's front (outside the gatehouse), else the town's board for a town with no gate on a road. */
    internal fun front(c: Ctx, t: Tale): BlockPos = gate(t)?.let { WayOut.front(c.here.places, it) } ?: c.here.capital.boardAnchor

    /** Where the captain stands at the gate: beside its front. */
    internal fun captainAt(c: Ctx, t: Tale): BlockPos {
        val front = front(c, t)
        val g = gate(t) ?: return front
        return io.github.vaspyyy.guildmark.tales.Sets.standable(c.level, front.relative(c.here.capital.direction(g).clockWise, 2)) ?: front
    }

    /** The forge door, as the seed found it (the smith's work, else their home's door), kept so a stand-in waits at the same door. */
    internal fun forge(t: Tale): BlockPos? {
        val parts = t.s[S_FORGE].split(',')
        if (parts.size != 3) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        val z = parts[2].toIntOrNull() ?: return null
        return BlockPos(x, y, z)
    }

    private fun forgeText(at: BlockPos): String = "${at.x},${at.y},${at.z}"

    /** The forge door, else the board. */
    internal fun forgeOr(c: Ctx, t: Tale): BlockPos = forge(t) ?: c.here.capital.boardAnchor

    /** The door of the place [r] works (their post's building, else the plan's), else their home's, else the board. */
    internal fun forgeDoor(level: ServerLevel, here: Scenes.Here, r: Resident): BlockPos {
        val places = here.places
        val work = ComingsData.get(level).find(here.key)?.posts?.get(r.id)?.work
            ?: places.census.residents.getOrNull(r.planned)?.work?.let { here.capital.world(it.x, it.y, it.z) }
        return work?.let { places.lodgingAt(it)?.front?.outside } ?: places.lodgings[r.home]?.front?.outside ?: here.capital.boardAnchor
    }

    /** The nearest tavern's door to the forge, outside: where the delver waits. */
    internal fun tavern(c: Ctx, t: Tale): BlockPos? =
        c.here.places.nearest(forgeOr(c, t), CityPlaces.TAVERNS).firstNotNullOfOrNull { it.front?.outside }

    internal fun given(name: String): String = name.substringBefore(' ')

    internal fun brotherName(t: Tale): String = t.cast(BROTHER)?.name.orEmpty()

    internal fun smithName(t: Tale): String = t.cast(SMITH)?.name.orEmpty()

    internal fun clock(level: ServerLevel): Int = Days.offsetOf(level.overworldClockTime)

    /** The side the wall mark is on in the first hall: the tale's id says, so two tales in one mine sit apart. */
    internal fun side(t: Tale): Int = if (t.id % 2 == 0) 1 else -1

    /** The part of the player called [name] (as `s` keeps them), if they have one. */
    internal fun partNamed(level: ServerLevel, t: Tale, name: String): Part? {
        if (name.isBlank()) return null
        return t.parts.firstOrNull { TaleEngine.playerName(level, it.player) == name }
    }

    /** Whether [p] has taken the tale up. */
    internal fun joined(p: Part?): Boolean = p != null && (p.status == Part.Status.IN || p.status == Part.Status.DONE)

    /** [part]'s deed settled the ending: theirs alone. */
    internal fun decide(t: Tale, part: Part) {
        for (p in t.parts) if (p !== part) p.did = p.did and Part.DECIDED.inv()
        part.did = part.did or Part.DECIDED
    }

    /** [player]'s part, taken up for them if they have not (a deed is a yes); null if they cannot take it up. */
    internal fun take(level: ServerLevel, t: Tale, player: ServerPlayer): Part? {
        val mine = t.part(player.uuid)
        if (joined(mine)) return mine
        if (!Tales.join(level, t, player)) return null
        return t.part(player.uuid)?.takeIf { joined(it) }
    }

    /** What the players put in toward the delvers is given back, and the sum is begun again. */
    internal fun refund(c: Ctx, t: Tale) {
        TalePay.refund(c, t)
        t.n[N_PAID] = 0
        unset(t, WHOLE)
        c.data.changed()
    }

    /** Who asks: the smith; the captain once the smith is gone; the helper once both are. */
    internal fun askerOf(t: Tale): String = when {
        !flag(t, SMITH_GONE) && t.cast(SMITH)?.gone?.isEmpty() == true -> SMITH
        t.cast(CAPTAIN)?.let { it.resident >= 0 && it.gone.isEmpty() } == true -> CAPTAIN
        t.cast(HELPER)?.let { it.resident >= 0 && it.gone.isEmpty() } == true -> HELPER
        else -> SMITH
    }

    /** The words a sentence is said with (`TaleWords`): the one below, then the smith. */
    override fun words(t: Tale): List<String> = listOf(brotherName(t), smithName(t))

    // ---- The seed ----

    override fun needs(): Boolean = Fortunes.enabled() && Tales.scenes()

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val data = TaleData.get(level)
        val places = c.here.places
        val center = c.here.capital.center
        val board = c.here.capital.boardAnchor
        // The shortage: tools, else coal, wanted in the town now
        val wanted = Fortunes.reasons(level, c.capital)
            .filter { it.kind == FortuneReasons.WANTING && (it.until < 0 || it.until >= c.day) }
            .flatMap { it.args }
        var short = when {
            Good.TOOLS.id in wanted -> Good.TOOLS.id
            Good.COAL.id in wanted -> Good.COAL.id
            else -> null
        }
        var madeUp = ""
        if (short == null) {
            if (!c.forced) return c.none("neither tools nor coal is wanting in the town (`/guildmark market event waylaid tools`, then a close, makes the shortage)")
            short = Good.TOOLS.id
            madeUp = "tools short in the town (no 'wanting' reason stands; the shortage is the tale's alone, the fortunes are not touched)"
        }
        // The smith and the one who went down: a household of two grown people free of a tale, the smith one of them
        val pool = TaleCast.pool(level, center)
        class Pick(val smith: Resident, val below: Resident)
        val picks = ArrayList<Pick>()
        for (smith in pool.filter { it.role == TownRole.SMITH }) {
            val below = pool.filter { it.id != smith.id && it.home == smith.home && it.role != TownRole.GUARD && it.role != TownRole.NOBLE && it.role != TownRole.KNIGHT }
                .minWithOrNull(compareBy<Resident>({ if (it.family == smith.family) 0 else 1 }, { it.id })) ?: continue
            picks += Pick(smith, below)
        }
        if (picks.isEmpty()) return c.none("no smith free of a tale has another grown person at home free of one too (no guard, leader or faction person)")
        val pick = picks[c.random.nextInt(picks.size)]
        val forge = forgeDoor(level, c.here, pick.smith)
        // The mine: one standing near enough that no other open tale sends someone into, else it is made at the first yes
        val others = data.all().filter { it.template == id && it.open }.map { it.n[N_LAIR] }.filter { it > 0 }.toSet()
        val mine = BrotherMine.reuse(level, board, others)
        val gates = WayOut.gates(places)
        val gate = mine?.let { WayOut.gateNear(places, it.pos) } ?: WayOut.gateNear(places, forge) ?: gates.firstOrNull()
        val front = gate?.let { WayOut.front(places, it) } ?: board
        val taken = hashSetOf(pick.smith.id, pick.below.id)
        val lodgings = places.lodgings
        val captain = pool.filter { it.role == TownRole.GUARD && it.id !in taken }
            .minWithOrNull(compareBy<Resident>({ r -> lodgings[r.home]?.box?.center?.distSqr(front) ?: Double.MAX_VALUE }, { it.id }))
            ?: return c.none("no guard is free to be the captain at the gate")
        taken += captain.id
        val home = lodgings[pick.smith.home]?.box?.center ?: forge
        val byHome = compareBy<Resident>({ r -> lodgings[r.home]?.box?.center?.distSqr(home) ?: Double.MAX_VALUE }, { it.id })
        val helper = Witness.neighbours(level, pick.smith)
            .filter { TaleCast.free(it, data) && it.role != TownRole.GUARD && it.id !in taken }
            .minWithOrNull(byHome)
            ?: pool.filter { it.role == TownRole.CITIZEN && it.id !in taken }.minWithOrNull(byHome)
        helper?.let { taken += it.id }
        val priest = Chair.priestFor(level, places, center, pick.below, taken, data)
        val cast = ArrayList<Cast>()
        cast += Cast(SMITH, pick.smith.id, pick.smith.name)
        cast += Cast(BROTHER, pick.below.id, pick.below.name)
        cast += Cast(CAPTAIN, captain.id, captain.name)
        helper?.let { cast += Cast(HELPER, it.id, it.name) }
        priest?.let { cast += Cast(PRIEST, it.id, it.name) }
        val n = IntArray(8)
        n[N_LAIR] = mine?.id ?: 0
        n[N_WHERE] = BELOW
        n[N_GATE] = ((gate?.ordinal ?: -1) + 1) or (SUM shl 8)
        n[N_FLAGS] = if (madeUp.isNotEmpty()) FORCED else 0
        val why = Why(if (short == Good.COAL.id) "brother.start_coal" else "brother.start", listOf(TaleWords.town(level, c.capital), pick.below.name))
        return Planned(cast, why, step = AT_ASK, n = n.toList(), s = listOf("", "", "", forgeText(forge)), madeUp = madeUp)
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        // The first yes makes the mine, as a lair note does, if none stood near; with none to be made the tale is over
        if (t.step == AT_MEET && t.n[N_LAIR] == 0 && t.parts.any { joined(it) }) {
            makeMine(c, t) ?: return
        }
        if (t.step == AT_MEET) lair(c.level, t)?.let { BrotherMine.raise(c.level, it) }
        BrotherWatch.below(c, t)
    }

    /** The mine, made now if the tale has none (as a lair note makes one, 120 to 220 blocks out); null, with the tale ended C, if none can be. */
    internal fun makeMine(c: Ctx, t: Tale): Lair? {
        lair(c.level, t)?.let { return it }
        if (!t.open) return null
        val made = BrotherMine.make(c.level, c.here.capital.boardAnchor)
        if (made == null) {
            set(t, NOMINE)
            c.why(t, "brother.nomine", brotherName(t))
            c.data.changed()
            c.end(t, Ending.C)
            return null
        }
        t.n[N_LAIR] = made.id
        set(t, MADE)
        WayOut.gateNear(c.here.places, made.pos)?.let { setGate(t, it) }
        c.why(t, "brother.mine", brotherName(t))
        c.data.changed()
        BrotherWatch.forget(t.id, SMITH)
        BrotherWatch.forget(t.id, CAPTAIN)
        return made
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        when (e) {
            is Event.Lair -> if (e.lair == t.n[N_LAIR]) cleared(c, t, e.heroes)
            is Event.Kindled -> kindled(c, t, e.pos, e.player)
            is Event.Gave -> if (e.marks > 0) paid(c, t, e.player, e.marks)
            is Event.Arrived -> if (e.slot == BROTHER) arrived(c, t)
            is Event.Day -> if (t.step == AT_DEED) close(c, t)
            else -> {}
        }
    }

    /**
     * The mine's boss fell while they were below (`Lairs.onBossDeath`, or `tale brother clear`): they walk up by themselves and the tale ends
     * C, freed; the [heroes] who are in the tale have the bit, the first of them the deed. One being led up is the leader's: nothing changes.
     */
    internal fun cleared(c: Ctx, t: Tale, heroes: List<UUID>) {
        if (!t.open || flag(t, FREED) || flag(t, GONE) || where(t) >= OUT) return
        if (where(t) == LED && BrotherWatch.leading(c.level, t)) return
        set(t, FREED)
        var first: Part? = null
        for (h in heroes) {
            val p = t.part(h)?.takeIf { joined(it) } ?: continue
            p.did = p.did or Part.WALKED
            if (first == null) first = p
        }
        first?.let { decide(t, it) }
        refund(c, t)
        c.why(t, "brother.freed", brotherName(t))
        c.data.changed()
        BrotherWatch.freed(c, t)
    }

    /**
     * [player] kindled a fire: the mine's own, cold until now, while they are below, is the deed of the fire. With [found] false (`tale brother
     * kindle` while the mine's land is not loaded) [pos] is taken as the mine's fire without looking.
     */
    internal fun kindled(c: Ctx, t: Tale, pos: BlockPos, player: ServerPlayer, found: Boolean = true) {
        if (!t.open || t.step > AT_DEED || flag(t, LIT) || flag(t, GONE) || flag(t, FREED) || where(t) > LED) return
        val lair = lair(c.level, t) ?: return
        if (found && BrotherMine.fireOf(c.level, lair) != pos) return
        set(t, LIT)
        t.setS(S_KINDLER, player.gameProfile.name)
        t.n[N_FIRE] = BrotherMine.pack(lair.pos, pos)
        c.data.changed()
        take(c.level, t, player)?.let { it.did = it.did or Part.WALKED }
        if (!t.open) return
        c.why(t, "brother.kindled", player.gameProfile.name, brotherName(t))
        deed(c, t, player, FIRE)
        if (t.open) BrotherWatch.lit(c, t)
    }

    /** A deed of way [w] by [player] before the deed's step: it is the choice (its bit), and the deed's step is next. At the deed, a way if none was chosen. */
    internal fun deed(c: Ctx, t: Tale, player: ServerPlayer?, w: Int) {
        if (!t.open) return
        if (t.step <= AT_CHOOSE) {
            t.n[N_WAY] = w
            player?.let { t.part(it.uuid) }?.takeIf { joined(it) }?.let { it.did = it.did or Part.CHOSE }
            c.data.changed()
            c.go(t, AT_DEED)
        } else if (t.step == AT_DEED && way(t) == 0) {
            t.n[N_WAY] = w
            c.data.changed()
        }
    }

    /** Whether way [w] can still be chosen: they are below, nobody has brought them up, and (for the fire) the mine's fire is cold. */
    internal fun canChoose(level: ServerLevel, t: Tale, w: Int): Boolean {
        if (!t.open || (t.step != AT_CHOOSE && t.step != AT_DEED) || flag(t, FREED) || flag(t, GONE) || where(t) > LED) return false
        if (w == HIRE && flag(t, WHOLE)) return false
        if (w == FIRE && fireLit(level, t)) return false
        return true
    }

    /** Whether the mine's fire burns: kindled in the tale, or found lit (in loaded land only). */
    internal fun fireLit(level: ServerLevel, t: Tale): Boolean {
        if (flag(t, LIT)) return true
        val lair = lair(level, t) ?: return false
        val fire = BrotherMine.fireOf(level, lair) ?: return false
        return BrotherMine.lit(level, fire)
    }

    /** [player] chooses way [w] with the smith (or by the command): the way is theirs, the choice's bit at the choice, and the deed is next. */
    internal fun choose(c: Ctx, t: Tale, player: ServerPlayer, w: Int): Boolean {
        if (!canChoose(c.level, t, w)) return false
        val kind = when (w) {
            DOWN -> "brother.down"
            HIRE -> "brother.hire"
            else -> "brother.fire"
        }
        // The reason first, so that what the next step adds is newer than it
        t.addWhy(Why(kind, listOf(player.gameProfile.name, brotherName(t))))
        t.n[N_WAY] = w
        if (t.step == AT_CHOOSE) t.part(player.uuid)?.takeIf { joined(it) }?.let { it.did = it.did or Part.CHOSE }
        c.data.changed()
        if (t.step < AT_DEED) c.go(t, AT_DEED)
        return true
    }

    /** [player] put [marks] toward the delvers' fee ([TaleOptions.put], heard as `Event.Gave`): a quarter is a bit; the payment that makes it whole is the deed. */
    private fun paid(c: Ctx, t: Tale, player: ServerPlayer, marks: Int) {
        if (!t.open || t.step != AT_DEED || flag(t, WHOLE) || flag(t, GONE)) return
        t.n[N_PAID] += marks
        val part = t.part(player.uuid)
        if (part != null && part.escrow >= QUARTER) part.did = part.did or Part.GAVE
        if (t.n[N_PAID] >= sum(t)) {
            set(t, WHOLE)
            t.setS(S_PAYER, player.gameProfile.name)
            part?.let { decide(t, it) }
            c.why(t, "brother.paid", player.gameProfile.name, sum(t).toString())
        }
        c.data.changed()
    }

    /** The deed's close: a fire lit while they were below has them up to it; else a fee made whole sends the party down. */
    private fun close(c: Ctx, t: Tale) {
        if (flag(t, FREED) || flag(t, GONE)) return
        if (flag(t, LIT) && !flag(t, ROSE) && where(t) == BELOW) return rose(c, t)
        if (flag(t, WHOLE) && where(t) == BELOW) partyGoes(c, t)
    }

    /**
     * They came up to the fire (seen, [BrotherScenes.rise], or by the record at the close): the deed of the fire, decided by whoever kindled
     * it; what was put in toward the delvers is given back; they are home at the forge door at seven ([C_DAWN]) unless someone walks them.
     */
    internal fun rose(c: Ctx, t: Tale) {
        if (!t.open || flag(t, ROSE) || flag(t, FREED) || flag(t, GONE) || where(t) > LED) return
        set(t, ROSE)
        setWhere(t, OUT)
        refund(c, t)
        partNamed(c.level, t, t.s[S_KINDLER])?.let { decide(t, it) }
        t.n[N_DAY] = (if (clock(c.level) < C_DAWN) c.day else c.day + 1).toInt()
        c.why(t, "brother.rose", brotherName(t))
        c.data.changed()
        if (t.step < AT_HOME) c.go(t, AT_HOME)
    }

    /** The fee is whole at the close: the party goes down off stage, the payment that made it is the deed, and they come up the street at first light. */
    private fun partyGoes(c: Ctx, t: Tale) {
        set(t, GONE)
        t.n[N_DAY] = c.day.toInt()
        partNamed(c.level, t, t.s[S_PAYER])?.let { decide(t, it) }
        c.why(t, "brother.party", brotherName(t))
        c.data.changed()
        BrotherWatch.partyGone(c, t)
        // The step is met: what was put in is spent
        c.go(t, AT_HOME)
    }

    /** They are up out of the mine, led by [leader] (the escort, `tale brother up`): the deed of A, the leader's; what was put in is given back. */
    internal fun up(c: Ctx, t: Tale, leader: UUID?) {
        if (!t.open || where(t) >= OUT || flag(t, FREED) || flag(t, GONE)) return
        setWhere(t, OUT)
        refund(c, t)
        val part = leader?.let { t.part(it) } ?: partNamed(c.level, t, t.s[S_LEADER])
        if (part != null) {
            part.did = part.did or Part.WALKED
            decide(t, part)
        }
        if (way(t) == 0) t.n[N_WAY] = DOWN
        val name = part?.let { TaleEngine.playerName(c.level, it.player) }.orEmpty().ifEmpty { t.s[S_LEADER] }
        c.why(t, "brother.led", name, brotherName(t))
        c.data.changed()
        if (t.step < AT_HOME) c.go(t, AT_HOME)
    }

    /** The walk home reached the forge door: they are home (up first, if the walk began below), and the homecoming is next. */
    internal fun arrived(c: Ctx, t: Tale) {
        if (!t.open || where(t) == HOME || where(t) == LOST) return
        if (where(t) < OUT) up(c, t, BrotherWatch.leaderOf(t))
        if (!t.open) return
        setWhere(t, HOME)
        unset(t, ALONE)
        BrotherWatch.leaderOf(t)?.let { t.part(it) }?.let { it.did = it.did or Part.WALKED }
        c.why(t, "brother.home", brotherName(t))
        c.data.changed()
        if (t.step < AT_HOME) c.go(t, AT_HOME)
    }

    /** [player] says "Can you find your way?": they walk home by themselves, at the forge door by first light. */
    internal fun alone(c: Ctx, t: Tale, player: ServerPlayer?): Boolean {
        if (!t.open || where(t) != OUT || flag(t, ALONE)) return false
        set(t, ALONE)
        unset(t, WAITS)
        if (!flag(t, ROSE)) t.n[N_DAY] = (if (clock(c.level) < A_DAWN) c.day else c.day + 1).toInt()
        c.why(t, "brother.alone", brotherName(t))
        c.data.changed()
        if (t.step < AT_HOME) c.go(t, AT_HOME)
        return true
    }

    // ---- The world's way ----

    /**
     * The close (or `tale step`): nobody took it up, and the word goes round (0); the mine goes on every part's map (1); nobody chose, and all
     * three ways stay open by deed (2). At D5's close (3): out already is the walk home; a leading in motion with its leader near runs on to
     * D7; anything else is W. At the last (4): a leading still running is up if its leader is with them; then the ending the record has.
     */
    override fun settle(c: Ctx, t: Tale) {
        when (t.step) {
            AT_ASK -> {
                c.why(t, "brother.unasked", given(t.cast(askerOf(t))?.name.orEmpty()))
                c.next(t)
            }
            AT_MEET -> {
                if (t.parts.any { joined(it) }) makeMine(c, t)
                if (!t.open) return
                mapped(c, t)
                c.next(t)
            }
            AT_CHOOSE -> {
                c.why(t, "brother.unchosen")
                c.next(t)
            }
            AT_DEED -> {
                t.n[N_PAID] = 0
                unset(t, WHOLE)
                c.data.changed()
                when {
                    where(t) >= OUT -> c.go(t, AT_HOME)
                    where(t) == LED && BrotherWatch.leaderNear(c.level, t, 40.0) -> {
                        set(t, LATE)
                        c.why(t, "brother.late_lead", brotherName(t))
                        c.go(t, AT_HOME)
                    }
                    else -> c.end(t, Ending.W)
                }
            }
            else -> {
                if (where(t) == LED && BrotherWatch.leaderNear(c.level, t, 40.0)) up(c, t, BrotherWatch.leaderOf(t))
                finish(c, t, outcome(t))
            }
        }
    }

    /** The mine goes on the map of every part that is on, asked or not. */
    private fun mapped(c: Ctx, t: Tale) {
        val lair = lair(c.level, t) ?: return
        for (p in t.parts) (c.level.server.playerList.getPlayer(p.player))?.let { note(c.level, it, lair) }
        if (!flag(t, MAPPED)) {
            set(t, MAPPED)
            c.why(t, "brother.mapped", brotherName(t))
        }
        c.data.changed()
    }

    /** The mine on [player]'s map and compass. */
    internal fun note(level: ServerLevel, player: ServerPlayer, lair: Lair) {
        Atlas.note(player, AtlasPlace(AtlasPlace.LAIR, Level.OVERWORLD, lair.pos, ref = lair.id, found = level.gameTime))
    }

    /** The tale ends [ending]; whoever did the deed of a way nobody decided by name is the deciding one. */
    internal fun finish(c: Ctx, t: Tale, ending: String) {
        if (!t.open) return
        if (t.parts.none { it.did(Part.DECIDED) }) {
            val by = when (ending) {
                Ending.A, Ending.C -> t.parts.firstOrNull { it.did(Part.WALKED) }
                Ending.B -> t.parts.firstOrNull { it.did(Part.GAVE) }
                else -> null
            }
            by?.let { decide(t, it) }
        }
        c.end(t, ending)
    }

    /** The ending the record has now: B once the party went; C if they came up to the fire, were freed, or no mine could be made; A if out; else W. */
    internal fun outcome(t: Tale): String = when {
        flag(t, GONE) -> Ending.B
        flag(t, ROSE) || flag(t, FREED) || flag(t, NOMINE) -> Ending.C
        where(t) in OUT..HOME -> Ending.A
        else -> Ending.W
    }

    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            SMITH -> smithGone(c, t)
            // Gone from the record (they cannot die in the tale but through W): the tale ends C, freed
            BROTHER -> {
                set(t, FREED)
                c.why(t, "brother.gone", brotherName(t))
                c.data.changed()
                finish(c, t, Ending.C)
            }
            else -> BrotherWatch.forget(t.id, slot)
        }
    }

    /**
     * The smith died (a trade household does not leave): the captain asks from now on, and another grown person of the household, if there
     * is one free, waits at the forge door in their place. Nobody to wait, and one who is home is home.
     */
    private fun smithGone(c: Ctx, t: Tale) {
        set(t, SMITH_GONE)
        val old = smithName(t)
        c.why(t, "brother.smith_gone", t.cast(CAPTAIN)?.name.orEmpty(), old)
        val below = c.resident(t, BROTHER)
        val cast = t.cast(SMITH)
        if (cast != null && below != null && below.home >= 0) {
            val taken = t.cast.filter { it.resident >= 0 && it.gone.isEmpty() }.mapTo(HashSet()) { it.resident }
            val next = TaleCast.pool(c.level, c.here.capital.center).firstOrNull { it.home == below.home && it.id !in taken }
            if (next != null) {
                cast.resident = next.id
                cast.name = next.name
                cast.gone = ""
                cast.held = false
                TaleEngine.reindex()
                c.why(t, "stood_in", next.name, old)
            }
        }
        c.data.changed()
        BrotherWatch.forget(t.id, SMITH)
        if (t.step == AT_HOME && where(t) == HOME && t.cast(SMITH)?.gone?.isNotEmpty() == true) finish(c, t, outcome(t))
    }

    /** A stand-in for the captain, the helper or the priest: the watch holds them again from the next second. */
    override fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {
        BrotherWatch.forget(t.id, slot)
    }

    // ---- The endings ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        BrotherWatch.letGo(c, t)
        val holders = t.parts.filter { TaleShares.holds(it) }
        when (ending) {
            Ending.A -> {
                setWhere(t, HOME)
                if (c.flip(t, SMITH, Trait.CHEERFUL)) c.why(t, "brother.cheerful", smithName(t))
                if (c.flip(t, BROTHER, Trait.DILIGENT)) c.why(t, "brother.diligent", brotherName(t))
                for (p in holders) {
                    c.favour(t, SMITH, p.player, Favour.TALE_HELPED)
                    c.favour(t, BROTHER, p.player, Favour.TALE_HELPED)
                }
            }
            Ending.B -> {
                setWhere(t, HOME)
                if (c.flip(t, SMITH, Trait.CHEERFUL)) c.why(t, "brother.cheerful", smithName(t))
                for (p in holders) c.favour(t, SMITH, p.player, Favour.TALE_HELPED)
            }
            Ending.C -> {
                if (!flag(t, NOMINE)) setWhere(t, HOME)
                if (!flag(t, FREED) && !flag(t, NOMINE)) for (p in holders) c.favour(t, SMITH, p.player, Favour.TALE_HELPED)
            }
            Ending.W -> lost(c, t)
        }
        c.data.changed()
        BrotherWatch.ended(t.id)
    }

    /** W: they are lost below. Their body goes on into the dark, they pass (MOURNING), a cairn at the mouth and a stone at the forge door. */
    private fun lost(c: Ctx, t: Tale) {
        setWhere(t, LOST)
        BrotherWatch.intoTheDark(c, t)
        c.resident(t, BROTHER)?.takeIf { it.alive }?.let { Residents.pass(c.level, it, null, "lost in the old mine") }
        val name = brotherName(t)
        if (c.memorial(t, "cairn", listOf(name), lair(c.level, t)?.pos) != null) c.why(t, "brother.cairn", name)
        if (c.memorial(t, "stone", listOf(name), forge(t)) != null) c.why(t, "brother.stone", name)
        if (c.flip(t, SMITH, Trait.IDLE)) c.why(t, "brother.idle", smithName(t))
        c.say(t, SMITH, "tale.brother.late")
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld()
        val c = level?.let { TaleEngine.ctx(it, t.capital) }
        val title = TaleKeys.title(id)
        val below = given(brotherName(t))
        val mine = level?.let { lair(it, t)?.pos }
        val fire = level?.let { fireAt(it, t) } ?: mine
        val forge = c?.let { forgeOr(it, t) } ?: forge(t)
        return when (t.step) {
            AT_ASK -> View(title, status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(given(t.cast(askerOf(t))?.name.orEmpty())), target = c?.let { askerAt(it, t) })
            AT_MEET -> View(title, status = TaleKeys.status(id, AT_MEET), target = c?.let { captainAt(it, t) })
            AT_CHOOSE -> View(title, status = TaleKeys.status(id, AT_CHOOSE), target = c?.let { askerAt(it, t) })
            AT_DEED -> when {
                where(t) == LED -> View(title, status = STATUS_LEAD, statusArgs = listOf(below), target = mine)
                flag(t, LIT) -> View(title, status = STATUS_LIT, target = fire)
                flag(t, WHOLE) -> View(title, status = STATUS_WAIT, target = c?.let { tavern(it, t) } ?: forge)
                way(t) == HIRE -> View(title, status = STATUS_HIRE, target = c?.let { tavern(it, t) } ?: forge)
                way(t) == FIRE -> View(title, status = STATUS_FIRE, target = fire)
                else -> View(title, status = TaleKeys.status(id, AT_DEED), statusArgs = listOf(below), target = mine)
            }
            else -> when {
                level != null && BrotherWatch.leading(level, t) -> View(title, status = STATUS_WALK, statusArgs = listOf(below), tone = View.Tone.DONE, target = forge)
                where(t) != HOME && (flag(t, GONE) || flag(t, ALONE) || flag(t, ROSE)) -> View(title, status = STATUS_DAWN, tone = View.Tone.DONE, target = forge)
                where(t) == OUT -> View(title, status = STATUS_WALK, statusArgs = listOf(below), tone = View.Tone.DONE, target = forge)
                else -> View(title, status = TaleKeys.status(id, AT_HOME), statusArgs = listOf(given(t.cast(askerOf(t))?.name.orEmpty())), tone = View.Tone.DONE, target = forge)
            }
        }
    }

    private const val STATUS_LEAD = "gui.guildmark.tale.brother.status.3.lead"
    private const val STATUS_HIRE = "gui.guildmark.tale.brother.status.3.hire"
    private const val STATUS_WAIT = "gui.guildmark.tale.brother.status.3.wait"
    private const val STATUS_FIRE = "gui.guildmark.tale.brother.status.3.fire"
    private const val STATUS_LIT = "gui.guildmark.tale.brother.status.3.lit"
    private const val STATUS_WALK = "gui.guildmark.tale.brother.status.4.walk"
    private const val STATUS_DAWN = "gui.guildmark.tale.brother.status.4.dawn"

    /** Where the one who asks is to be found: the smith at the gate from 15:30 to 22:00, else at the forge; the captain at the gate. */
    internal fun askerAt(c: Ctx, t: Tale): BlockPos {
        if (askerOf(t) != SMITH) return captainAt(c, t)
        val now = clock(c.level)
        return if (now in CALL_FROM until GATE_TO) front(c, t) else forgeOr(c, t)
    }

    /** The mine's fire, as the record keeps it (from the mouth), else as found; null with no mine. */
    internal fun fireAt(level: ServerLevel, t: Tale): BlockPos? {
        val lair = lair(level, t) ?: return null
        return BrotherMine.unpack(lair.pos, t.n[N_FIRE]) ?: BrotherMine.fireOf(level, lair)
    }

    // ---- The telling ----

    /** The seed scenes, while they are below: the smith at the forge by day, the calling at the gate from 15:30. */
    override fun scene(c: Ctx, t: Tale): Stage? {
        if (!t.open || t.step > AT_DEED || where(t) > LED || flag(t, GONE) || flag(t, FREED)) return null
        return BrotherScenes.waiting(c, t)
    }

    // ---- The talk ----

    override fun asker(t: Tale): String = askerOf(t)

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val below = TaleLines.lit(brotherName(t))
        return when (beat) {
            "ask" -> TaleLines.facts("talk.detail.tale.brother.ask", below, (2L + turn.today - t.d0).coerceAtLeast(2L))
            "rumour" -> rumour(turn.level, t)
            "stand" -> stand(turn, t)
            "captain" -> TaleLines.facts("talk.detail.tale.brother.captain")
            "hire" -> hireDetail(t)
            "cairn" -> TaleLines.facts("talk.detail.tale.brother.cairn", below)
            else -> null
        }
    }

    /** The rumour's grey words: who, and where the mine lies from the board (to the nearest ten blocks). */
    private fun rumour(level: ServerLevel, t: Tale): String? {
        val below = TaleLines.lit(brotherName(t))
        val lair = lair(level, t) ?: return TaleLines.facts("talk.detail.tale.brother.rumour.nomine", below)
        val c = TaleEngine.ctx(level, t.capital) ?: return TaleLines.facts("talk.detail.tale.brother.rumour.nomine", below)
        val board = c.here.capital.boardAnchor
        val dx = (lair.pos.x - board.x).toDouble()
        val dz = (lair.pos.z - board.z).toDouble()
        val blocks = (Math.round(kotlin.math.sqrt(dx * dx + dz * dz) / 10.0) * 10L).coerceAtLeast(10L)
        val side = gate(t)?.let { WayOut.side(c.here, it) } ?: compass(dx, dz)
        return TaleLines.facts("talk.detail.tale.brother.rumour", below, blocks, WayOut.sideKey(side))
    }

    private fun compass(dx: Double, dz: Double): String =
        if (kotlin.math.abs(dx) > kotlin.math.abs(dz)) (if (dx > 0) "east" else "west") else (if (dz > 0) "south" else "north")

    /** The grey words of the fee: the whole of it, or what is still to go. */
    internal fun hireDetail(t: Tale): String? =
        if (t.n[N_PAID] <= 0) TaleLines.facts("talk.detail.tale.brother.hire", sum(t))
        else TaleLines.facts("talk.detail.tale.brother.hire.part", sum(t), owing(t))

    /** Market days from today until the deed's close, the last day to pay, light or lead. */
    private fun daysLeft(turn: TaleTurn, t: Tale): Long = (t.d0 + LIVES - turn.today).coerceAtLeast(0L)

    /** "How does it stand?": where they are and what is left to do, from the record. */
    internal fun stand(turn: TaleTurn, t: Tale): String? {
        val below = TaleLines.lit(given(brotherName(t)))
        val due = TaleLines.due(daysLeft(turn, t))
        return when {
            where(t) == HOME -> TaleLines.facts("talk.detail.tale.brother.stand.home", below)
            flag(t, GONE) -> TaleLines.facts("talk.detail.tale.brother.stand.party")
            flag(t, ROSE) -> TaleLines.facts("talk.detail.tale.brother.stand.rose", below)
            flag(t, ALONE) -> TaleLines.facts("talk.detail.tale.brother.stand.alone", below)
            where(t) == OUT -> TaleLines.facts("talk.detail.tale.brother.stand.out", below)
            where(t) == LED -> TaleLines.facts("talk.detail.tale.brother.stand.lead", below)
            flag(t, LIT) -> TaleLines.facts("talk.detail.tale.brother.stand.lit", below)
            flag(t, WHOLE) -> TaleLines.facts("talk.detail.tale.brother.stand.paid")
            way(t) == HIRE -> TaleLines.facts("talk.detail.tale.brother.stand.hire", sum(t), t.n[N_PAID], due)
            way(t) == FIRE -> TaleLines.facts("talk.detail.tale.brother.stand.fire", due)
            else -> TaleLines.facts("talk.detail.tale.brother.stand", below, due)
        }
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = when (beat) {
        "ask" -> Activity.WRING_HANDS
        "passing" -> Activity.SIGH
        else -> null
    }

    /** The smith points the way to the mine, the tavern or the fire, as the way is. */
    override fun toward(beat: String, turn: TaleTurn): BlockPos? {
        if (beat != "stand" && beat != "rumour") return null
        val t = turn.tale
        if (where(t) > LED || flag(t, GONE)) return null
        val level = turn.level
        return when (way(t)) {
            HIRE -> turn.ctx()?.let { tavern(it, t) }
            FIRE -> fireAt(level, t)
            else -> lair(level, t)?.pos
        }
    }

    /** The delver names the fee; the one below speaks as they are found, led, and home. */
    override fun greet(turn: TaleTurn): TaleSay? {
        val t = turn.tale
        return when (turn.slot) {
            DELVER -> if (flag(t, WHOLE)) turn.say("paid") else turn.say("hire", hireDetail(t))
            BROTHER -> when (where(t)) {
                BELOW -> turn.say("found")
                LED, OUT -> turn.say("follow")
                HOME -> turn.say("home")
                else -> null
            }
            else -> null
        }
    }

    override fun options(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val asks = askerOf(t)
        return when (turn.slot) {
            SMITH -> if (asks == SMITH) smithOptions(turn) else listOf()
            CAPTAIN -> (captainOptions(turn) + if (asks == CAPTAIN) smithOptions(turn) else listOf()).take(4)
            HELPER -> if (asks == HELPER) smithOptions(turn) else listOf()
            BROTHER -> brotherOptions(turn)
            DELVER -> delverOptions(turn)
            else -> listOf()
        }
    }

    /** What the one who asks offers: the three ways at the choice; at the deed the other ways, and the fee itself when no delver can stand at the tavern. */
    private fun smithOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val level = turn.level
        val out = ArrayList<TaleOption>()
        when (t.step) {
            AT_CHOOSE -> {
                out += ways()
                for (w in listOf(DOWN, HIRE, FIRE)) if (canChoose(level, t, w)) out += wayOption(w)
            }
            AT_DEED -> {
                for (w in listOf(DOWN, HIRE, FIRE)) if (w != way(t) && canChoose(level, t, w)) out += wayOption(w)
                if (!Tales.scenes() && way(t) == HIRE) out += giveOptions(turn)
            }
        }
        return out.take(4)
    }

    /** What the captain offers: where they were seen going, the mine on the map, at the meeting (and again while they are below). */
    private fun captainOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        return when {
            t.step == AT_MEET -> listOf(where())
            t.step in AT_CHOOSE..AT_DEED && where(t) <= LED && !flag(t, GONE) && !flag(t, FREED) -> listOf(where())
            else -> listOf()
        }
    }

    /** What the one below offers: to come along, to wait, to find their own way, as they stand. */
    private fun brotherOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        if (!t.open || flag(t, FREED) || flag(t, GONE) || where(t) >= HOME) return listOf()
        val out = ArrayList<TaleOption>()
        val walking = BrotherWatch.leading(turn.level, t)
        if (walking) out += waitHere() else out += walk()
        if (where(t) == OUT && !flag(t, ALONE)) out += findWay()
        return out
    }

    /** What the delver offers: a quarter of the fee, or the rest, while they are below and the fee is not whole. */
    private fun delverOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        if (t.step !in AT_MEET..AT_DEED || flag(t, WHOLE) || flag(t, GONE) || flag(t, FREED) || where(t) != BELOW) return listOf()
        return giveOptions(turn)
    }

    private fun giveOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val owing = owing(t)
        if (owing <= 0) return listOf()
        val out = ArrayList<TaleOption>()
        if (owing > QUARTER) out += give("give", QUARTER)
        out += give("rest", owing)
        return out
    }

    private fun think(): TaleReply = TaleReply("ask.think", gesture = Activity.THINK)

    // ---- The options ----

    /** "What can be done?": the smith says what the three ways are. Nothing is changed. */
    private fun ways() = TaleOption("ways", Component.translatable("talk.guildmark.choice.tale.brother.ways")) { turn ->
        if (turn.tale.step != AT_CHOOSE) return@TaleOption null
        TaleReply(listOf(turn.say("choose")), Activity.EXPLAIN, toward = lair(turn.level, turn.tale)?.pos)
    }

    /** "I'll go down for them." / "I'll hire delvers." / "I'll light the old fire.": the way, and what it is to do. */
    private fun wayOption(w: Int): TaleOption {
        val word = when (w) {
            DOWN -> "down"
            HIRE -> "hire"
            else -> "fire"
        }
        return TaleOption(word, Component.translatable("talk.guildmark.choice.tale.brother.$word")) { turn ->
            val t = turn.tale
            val c = turn.ctx() ?: return@TaleOption think()
            val player = turn.player ?: return@TaleOption null
            if (!choose(c, t, player, w)) return@TaleOption null
            when (w) {
                FIRE -> TaleReply(listOf(turn.say("light")), Activity.POINT, toward = fireAt(turn.level, t) ?: lair(turn.level, t)?.pos)
                HIRE -> TaleReply(listOf(turn.say("stand", stand(turn, t))), Activity.POINT, toward = tavern(c, t))
                else -> TaleReply(listOf(turn.say("stand", stand(turn, t))), Activity.NOD, toward = lair(turn.level, t)?.pos)
            }
        }
    }

    /** The captain's "Where did they go?": seen at dusk going toward the old workings; the mine goes on the player's map, and the choice is next. */
    private fun where() = TaleOption("where", Component.translatable("talk.guildmark.choice.tale.brother.where")) { turn ->
        val t = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!tellMine(c, t, player)) return@TaleOption null
        TaleReply(listOf(turn.say("captain", detail("captain", turn))), Activity.POINT, toward = lair(turn.level, t)?.pos)
    }

    /** The captain tells [player] of the mine (made now if there is none): it goes on their map, and at the meeting the choice is next. */
    internal fun tellMine(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if (!t.open || t.step !in AT_MEET..AT_DEED) return false
        val lair = makeMine(c, t) ?: return false
        note(c.level, player, lair)
        if (t.step == AT_MEET) {
            t.addWhy(Why("brother.seen", listOf(t.cast(CAPTAIN)?.name.orEmpty(), brotherName(t))))
            c.data.changed()
            c.go(t, AT_CHOOSE)
        }
        return true
    }

    /** "Can you walk? Come with me.": they follow at their own pace (S8), their lamp in hand, to the forge door. */
    private fun walk() = TaleOption("walk", Component.translatable("talk.guildmark.choice.tale.brother.walk")) { turn ->
        val t = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!BrotherWatch.walk(c, t, player)) return@TaleOption think()
        TaleReply(listOf(turn.say("follow")), Activity.NOD, ends = true)
    }

    /** "Wait here.": they stay where they stand until asked to come on. */
    private fun waitHere() = TaleOption("wait", Component.translatable("talk.guildmark.choice.tale.brother.wait")) { turn ->
        val t = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        if (!BrotherWatch.wait(c, t)) return@TaleOption null
        TaleReply(listOf(turn.say("wait")), Activity.NOD, ends = true)
    }

    /** "Can you find your way?": out of the mine, they walk home by themselves; at the forge door by first light. */
    private fun findWay() = TaleOption("alone", Component.translatable("talk.guildmark.choice.tale.brother.alone")) { turn ->
        val t = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        BrotherWatch.cancelWalk(c.level, t)
        if (!alone(c, t, turn.player)) return@TaleOption null
        TaleReply(listOf(turn.say("alone")), Activity.NOD, ends = true)
    }

    /** "Here are 10 Marks." / "Here's the rest: 22 Marks.": toward the fee, from the pack, held until the party goes (or given back). */
    private fun give(word: String, marks: Int) = TaleOption(word, Component.translatable("talk.guildmark.choice.tale.brother.$word", marks)) { turn ->
        val t = turn.tale
        if (t.step !in AT_MEET..AT_DEED || flag(t, WHOLE) || flag(t, GONE) || where(t) != BELOW || marks > owing(t)) return@TaleOption null
        when (TaleOptions.check(turn, marks)) {
            TaleOptions.Put.SHORT -> return@TaleOption TaleReply(listOf(turn.say("hire", hireDetail(t))), Activity.ARMS_FOLDED)
            TaleOptions.Put.LATER -> return@TaleOption think()
            else -> {}
        }
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        // The reason first; Marks put in before the deed's step are the choice of the delvers, and are held at the deed
        val why = Why("brother.gave", listOf(turn.who, marks.toString()))
        t.addWhy(why)
        deed(c, t, player, HIRE)
        if (TaleOptions.put(turn, marks) != TaleOptions.Put.DONE) {
            if (t.why.lastOrNull() === why) t.why.removeAt(t.why.size - 1)
            return@TaleOption think()
        }
        if (flag(t, WHOLE)) TaleReply(listOf(turn.say("paid")), Activity.RECEIVE, ends = true)
        else TaleReply(listOf(turn.say("hire", hireDetail(t))), Activity.RECEIVE)
    }

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}
}
