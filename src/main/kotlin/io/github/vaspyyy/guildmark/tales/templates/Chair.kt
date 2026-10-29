package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.tales.Cast
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Gone
import io.github.vaspyyy.guildmark.tales.MemorialSites
import io.github.vaspyyy.guildmark.tales.Memorials
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
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * The Empty Chair (living-world/stories/design.md 8.2, size 2), Tales T4: someone in the town died a few days ago and left a household
 * behind, and the town has not marked it. The one who is left sets two bowls at the table. The tale is a laying: three who knew the dead
 * say a word, three candles are brought, a place is chosen, and at dusk the stone is laid there. It is the quietest tale, and nothing can
 * go wrong in it but silence.
 *
 * **Steps.** 0 ASK (S1 on the doorstep, 17:00 to 20:00, the mourner seated, sighing; by D2), 1 MEET x3 (the three friends, each in their
 * own day, give one word: `word_a`, `word_b`, `word_c`, no two alike; by D5), 2 GIVE (three candles of any colour, the `minecraft:candles`
 * tag, into the mourner's hands; by D5), 3 CHOOSE (the temple garden, the well in the square, or the doorstep; by D5) and 4 SCENE (S5 The
 * Laying at dusk, 17:00 to 19:30, on the day after steps 1 to 3 are done, or on D7 with whatever is done; by D7). Steps 1 to 3 are done in
 * any order and the tale stands at the first unfinished ([first]). The doorstep, the gathering at the place and the laying's hours are
 * [ChairWatch]'s; the scenes are [ChairScenes]'.
 *
 * **Numbers.** `n[0]` the words heard (a bit for each friend: 1, 2, 4), `n[1]` the candles brought (0 to 3), `n[2]` the place (0 none, 1 the
 * temple garden, 2 the well, 3 the doorstep), `n[3]` the dead resident's id, `n[4]` the laying's day less the seed day, plus one (0 until set), `n[5]`
 * the flags ([LAID]: the posy went down in a laying seen through; [MOURNER_GONE]). **Strings.** `s[0]` the doors of the first two friends,
 * `s[1]` the third friend's and the mourner's (blocks before the front door, in base 36), `s[2]` the place the stone is to stand.
 *
 * **The endings** (8.2's table). A `laid`: three words, three candles and a place; the laying with everyone, the friends lighting a candle
 * each, a lantern at dusk; the mourner flipped by the place (the temple DEVOUT, the well CHATTY), their household kept 20 days, the town's
 * thanks to the mourner and the three. B `quiet`: a word and a candle at least; the laying with those who gave a word; kept 20 days. C
 * `alone`: anything else done (the place, a word, a candle); the mourner and the priest; kept 10 days. W `empty`: nothing done by D7's close:
 * a plain stone by the temple and the household leaves (`Ctx.leave`; if the town will not let it go, the mourner keeps to themselves, SHY).
 * The stone is always set (`Ctx.memorial`, Part F). The times, the cry, the keepsake and the pay are the engine's.
 *
 * Nothing is worded as the dead's sex or trade but the grey detail, which names the trade (`tie.*`); the mourner and the friends are they.
 */
object Chair : Template, TaleVoice, TaleWords.Names {
    override val id: String = "chair"
    override val size: Int = 2

    // ---- The slots ----

    const val DEAD = "dead"
    const val MOURNER = "mourner"
    const val PRIEST = "priest"
    val FRIENDS: List<String> = listOf("friend1", "friend2", "friend3")

    internal const val AT_ASK = 0
    internal const val AT_WORDS = 1
    internal const val AT_CANDLES = 2
    internal const val AT_PLACE = 3
    internal const val AT_LAY = 4

    internal const val N_WORDS = 0
    internal const val N_CANDLES = 1
    internal const val N_PLACE = 2
    internal const val N_DEAD = 3
    internal const val N_LAY = 4
    internal const val N_FLAGS = 5
    internal const val S_DOORS = 0
    internal const val S_DOORS2 = 1
    internal const val S_PLACE = 2

    /** The places (`n[2]`). */
    internal const val TEMPLE = 1
    internal const val WELL = 2
    internal const val DOOR = 3

    /** The flags (`n[5]`): the posy went down in a laying seen through; the mourner died or left. */
    internal const val LAID = 1
    internal const val MOURNER_GONE = 2

    /** Three of each for an A. */
    internal const val ALL = 3
    private const val ALL_WORDS = 7

    /** The last day the laying can be on (D7), and how many days back a death may be (the last five, 8.2). */
    internal const val LAST = 7
    private const val WINDOW = 5L

    /** How long the household is kept from leaving after an A or B, and after a C (6.5). */
    private const val KEPT = 20
    private const val KEPT_C = 10

    /** The three words, one family each, no two friends alike: friend `i` of tale `t` says `WORD_FAMILIES[(i + t.id) % 3]`. */
    private val WORD_FAMILIES = listOf("tale.chair.word_a", "tale.chair.word_b", "tale.chair.word_c")

    /** Every step hears the day and the candles given (a candle may come at any of steps 1 to 4). */
    private val HEARS: Set<Event.Kind> = setOf(Event.Kind.DAY, Event.Kind.GAVE)

    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2, hears = HEARS),
        Step(Verb.MEET, by = 5, real = true, also = listOf(Verb.GIVE, Verb.CHOOSE), hears = HEARS),
        Step(Verb.GIVE, by = 5, real = true, also = listOf(Verb.MEET, Verb.CHOOSE), hears = HEARS),
        Step(Verb.CHOOSE, by = 5, real = true, also = listOf(Verb.MEET, Verb.GIVE), hears = HEARS),
        Step(Verb.SCENE, by = LAST, hears = HEARS),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "laid"),
        Ending(Ending.B, "quiet"),
        Ending(Ending.C, "alone"),
        Ending(Ending.W, "empty"),
    )

    /** W hurts nobody else: the comings' `LEAVING` stands, and the chair has no times note of its own (8.2, 6.1). */
    override val hurts: Boolean = false

    /** The priest and the three friends are any of their kind: one who dies or goes is stood in for (the engine, else [gone]). */
    override val standing: Set<String> = setOf(PRIEST) + FRIENDS

    override val numbers: List<String> = listOf("words", "candles", "place", "dead", "lay", "flags")
    override val strings: List<String> = listOf("doors", "doors2", "place")

    /** The chair needs nothing but the town: with the memorials off the laying is acted where the stone would stand (6.4). */
    override fun needs(): Boolean = true

    // ---- Reading the record ----

    /** The dead's name, as the stone and the sentences say it. */
    fun dead(t: Tale): String = t.cast(DEAD)?.name.orEmpty()

    override fun words(t: Tale): List<String> = listOf(dead(t))

    internal fun first(name: String?): String = name.orEmpty().substringBefore(' ')

    /** How many of the three have said their word. */
    fun words(n: Int): Int = Integer.bitCount(n and ALL_WORDS)

    internal fun heard(t: Tale, slot: String): Boolean = t.n[N_WORDS] and bit(slot) != 0

    private fun bit(slot: String): Int = 1 shl FRIENDS.indexOf(slot).coerceAtLeast(0)

    /** The word friend [slot] says: one of the three families, no two alike, in an order of the tale's own. */
    internal fun family(t: Tale, slot: String): String = WORD_FAMILIES[(FRIENDS.indexOf(slot).coerceAtLeast(0) + t.id) % WORD_FAMILIES.size]

    /** The step the tale stands at while steps 1 to 3 are done in any order: the first unfinished, else the laying. */
    internal fun first(t: Tale): Int = when {
        words(t.n[N_WORDS]) < ALL -> AT_WORDS
        t.n[N_CANDLES] < ALL -> AT_CANDLES
        t.n[N_PLACE] == 0 -> AT_PLACE
        else -> AT_LAY
    }

    /** What the tale would end with now, from what is done (8.2): A all of it, B a word and a candle, C anything, W nothing. */
    fun letter(t: Tale): String {
        val words = words(t.n[N_WORDS])
        val candles = t.n[N_CANDLES]
        val place = t.n[N_PLACE]
        return when {
            words >= ALL && candles >= ALL && place != 0 -> Ending.A
            words >= 1 && candles >= 1 -> Ending.B
            words > 0 || candles > 0 || place != 0 -> Ending.C
            else -> Ending.W
        }
    }

    /** The market day of the laying (D7 until steps 1 to 3 are done): `n[4]` is its day less the seed day, plus one (0 until set). */
    internal fun layDay(t: Tale): Long = t.d0 + if (t.n[N_LAY] > 0) t.n[N_LAY] - 1 else LAST

    /** The laying is on market day [day] (D0 to D7), and the step's close with it. */
    private fun setLay(c: Ctx, t: Tale, day: Long) {
        t.n[N_LAY] = (day - t.d0).coerceIn(0L, LAST.toLong()).toInt() + 1
        if (t.step == AT_LAY) t.by = layDay(t)
        c.data.changed()
    }

    /** `tale chair lay`: the laying is today. */
    internal fun layToday(c: Ctx, t: Tale) = setLay(c, t, c.day)

    internal fun flag(t: Tale, f: Int): Boolean = t.n[N_FLAGS] and f != 0

    /** The friends who come to the laying of an ending [letter]: A all three, B those who said a word, C none (the mourner and the priest). */
    internal fun attending(t: Tale, letter: String): List<String> = when (letter) {
        Ending.A -> FRIENDS
        Ending.B -> FRIENDS.filter { heard(t, it) }
        else -> listOf()
    }

    private fun pos(text: String): BlockPos? = text.toLongOrNull(36)?.let { BlockPos.of(it) }

    private fun code(pos: BlockPos): String = pos.asLong().toString(36)

    /** The block before [slot]'s front door (a friend's, the mourner's), for the tracker and the doorstep. */
    internal fun door(t: Tale, slot: String): BlockPos? {
        val (i, part) = when (slot) {
            FRIENDS[0] -> S_DOORS to 0
            FRIENDS[1] -> S_DOORS to 1
            FRIENDS[2] -> S_DOORS2 to 0
            else -> S_DOORS2 to 1
        }
        return t.s[i].split(';').getOrNull(part)?.let { pos(it) }
    }

    private fun setDoor(t: Tale, slot: String, at: BlockPos?) {
        val (i, part) = when (slot) {
            FRIENDS[0] -> S_DOORS to 0
            FRIENDS[1] -> S_DOORS to 1
            FRIENDS[2] -> S_DOORS2 to 0
            else -> S_DOORS2 to 1
        }
        val both = t.s[i].split(';').let { listOf(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }) }.toMutableList()
        both[part] = at?.let { code(it) }.orEmpty()
        t.setS(i, both.joinToString(";"))
    }

    /** Where the stone is to stand, once a place is chosen (or, at the laying, where it belongs). */
    internal fun place(t: Tale): BlockPos? = pos(t.s[S_PLACE])

    private fun doorOf(places: CityPlaces, r: Resident?): BlockPos? = r?.let { places.lodgings[it.home]?.front?.outside }

    /** The place of a memorial of kind [kind] ("temple", "well") in [c]'s capital, as Part F's anchors name them, or null. */
    private fun anchor(c: Ctx, kind: String): BlockPos? =
        MemorialSites(c.level, c.here.capital, c.here.places).named(false).firstOrNull { it.kind == kind }?.let { BlockPos(it.x, it.y, it.z) }

    /** Where the stone of place [place] would stand: the temple's door, the well nearest the heart of the town, the household's doorstep. */
    internal fun placeFor(c: Ctx, t: Tale, place: Int): BlockPos? = when (place) {
        TEMPLE -> anchor(c, "temple")
        WELL -> anchor(c, "well")
        DOOR -> door(t, MOURNER) ?: doorOf(c.here.places, c.resident(t, MOURNER))
        else -> null
    }

    /** The places the mourner may choose among here: the temple garden only where the town has a temple, the well where it has one. */
    internal fun places(c: Ctx, t: Tale): List<Int> = listOf(TEMPLE, WELL, DOOR).filter { placeFor(c, t, it) != null }

    /** The language key of place [place] in grey ("the well"). */
    internal fun placeWord(place: Int): String = when (place) {
        TEMPLE -> "say.guildmark.talk.detail.tale.chair.place.temple"
        WELL -> "say.guildmark.talk.detail.tale.chair.place.well"
        DOOR -> "say.guildmark.talk.detail.tale.chair.place.door"
        else -> "say.guildmark.talk.detail.tale.chair.place.none"
    }

    /** The stone of [t] (`Ctx.memorial`, Part F): at the place chosen, else where a stone belongs (a temple's door first). Asked again, the same one. */
    internal fun memorial(c: Ctx, t: Tale): TaleData.Memorial? {
        val at = if (t.n[N_PLACE] != 0) place(t) ?: placeFor(c, t, t.n[N_PLACE]) else null
        return c.memorial(t, "stone", listOf(dead(t)), at)
    }

    /** Where the stone stands or is to stand: the memorial's own block, else the place. */
    internal fun stoneAt(c: Ctx, t: Tale): BlockPos? =
        Memorials.of(c, t).firstOrNull { it.names == listOf(dead(t)) }?.pos ?: place(t) ?: placeFor(c, t, t.n[N_PLACE]) ?: Memorials.anchor(c, false)

    private fun residents(level: ServerLevel): Residents = Residents.get(level)

    /** The dead's own record, if the town still has it. */
    internal fun deadOf(level: ServerLevel, t: Tale): Resident? = residents(level)[t.n[N_DEAD]]?.takeIf { it.name == dead(t) }

    // ---- Seeding ----

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val data = TaleData.get(level)
        val residents = residents(level)
        val people = residents.of(c.here.capital.center)
        val today = VillageBoards.day(level)
        val marked = data.memorials.filter { it.capital == c.capital }.flatMap { it.names }.toSet()
        val told = data.all().filter { it.template == id && it.capital == c.capital }.map { it.n[N_DEAD] }.toSet()
        val dead = people.filter { r ->
            !r.alive && r.died >= 0 && r.left < 0 && r.role.adult && r.role != TownRole.GUARD && Faction.of(r.role) == null && r.home >= 0 &&
                r.name !in marked && r.id !in told
        }.sortedWith(compareByDescending<Resident> { residents.diedOn(it) }.thenBy { it.id })
        if (dead.isEmpty()) return c.none("nobody of the town has died who is not marked yet (an adult, no guard or faction person, with a home; '/kill' one, then 'tale day')")
        val recent = dead.filter { today - residents.diedOn(it) in 0..WINDOW }
        var madeUp = ""
        val list = if (recent.isNotEmpty() || !c.forced) recent else dead
        if (list.isEmpty()) return c.none("the last death of the town was ${today - residents.diedOn(dead.first())} days ago: the chair wants one in the last $WINDOW")
        var why = ""
        for (r in list) {
            val home = people.filter { it.alive && it.home == r.home && it.role.adult && TaleCast.free(it, data) }
            if (home.isEmpty()) {
                why = "${r.name} left nobody grown at home who is free to be cast"
                continue
            }
            val mourner = home[c.random.nextInt(home.size)]
            val friends = friendsFor(level, c.here.places, c.here.capital.center, r, setOf(mourner.id), data)
            if (friends.size < ALL) {
                why = "fewer than three who knew ${r.name} are free to be cast (neighbours, workmates, the tavern's)"
                continue
            }
            val priest = priestFor(level, c.here.places, c.here.capital.center, r, setOf(mourner.id) + friends.map { it.id }, data)
            if (today - residents.diedOn(r) !in 0..WINDOW) madeUp = "a death in the last $WINDOW days (${r.name} died ${today - residents.diedOn(r)} days ago)"
            val cast = ArrayList<Cast>()
            cast += Cast(DEAD, -1, r.name)
            cast += Cast(MOURNER, mourner.id, mourner.name)
            if (priest != null) cast += Cast(PRIEST, priest.id, priest.name)
            friends.forEachIndexed { i, f -> cast += Cast(FRIENDS[i], f.id, f.name) }
            val places = c.here.places
            val doors = listOf(doorOf(places, friends[0]), doorOf(places, friends[1])).joinToString(";") { it?.let { p -> code(p) }.orEmpty() }
            val doors2 = listOf(doorOf(places, friends[2]), doorOf(places, mourner)).joinToString(";") { it?.let { p -> code(p) }.orEmpty() }
            return Planned(
                cast = cast,
                why = Why("chair.died", listOf(r.name, Seasons.dateOn(level.server, residents.diedOn(r)).title.string)),
                step = AT_ASK,
                n = listOf(0, 0, 0, r.id, 0, 0),
                s = listOf(doors, doors2, ""),
                madeUp = madeUp,
            )
        }
        return c.none(why)
    }

    /**
     * Three who knew [dead] and may be cast (alive, grown, in no tale, not of their home, not in [except]): one of their own trade if they had
     * one (a workmate), then their neighbours ([Witness.neighbours]), then those who live round the tavern nearest their home ([Witness.round]),
     * then whoever lives nearest; the nearest first in each.
     */
    internal fun friendsFor(level: ServerLevel, places: CityPlaces, capital: BlockPos, dead: Resident, except: Set<Int>, data: TaleData): List<Resident> {
        val at = places.lodgings[dead.home]?.box?.center
        val pool = TaleCast.pool(level, capital)
        fun ok(r: Resident) = r.id !in except && r.home != dead.home && TaleCast.free(r, data)
        fun near(r: Resident) = at?.let { a -> places.lodgings[r.home]?.box?.center?.distSqr(a) } ?: Double.MAX_VALUE
        val out = LinkedHashMap<Int, Resident>()
        if (dead.role != TownRole.CITIZEN) pool.filter { ok(it) && it.role == dead.role }.minByOrNull(::near)?.let { out[it.id] = it }
        for (r in Witness.neighbours(level, dead).filter(::ok).sortedBy(::near)) if (out.size < ALL) out.putIfAbsent(r.id, r)
        val tavern = tavernOf(places, dead)
        if (tavern != null) for (r in Witness.round(level, capital, tavern, dead.home).filter(::ok).sortedBy(::near)) if (out.size < ALL) out.putIfAbsent(r.id, r)
        for (r in pool.filter(::ok).sortedBy(::near)) if (out.size < ALL) out.putIfAbsent(r.id, r)
        return out.values.take(ALL)
    }

    private fun tavernOf(places: CityPlaces, dead: Resident): BlockPos? {
        val at = places.lodgings[dead.home]?.box?.center ?: return null
        return places.nearest(at, CityPlaces.TAVERNS).firstOrNull()?.front?.pos
    }

    /**
     * Who says the words: the temple's priest if the town has a temple and one is free (the nearest the temple), else the one who has lived
     * in the town longest (`Resident.since`), not of the dead's home and not in [except]. Null if nobody is free.
     */
    internal fun priestFor(level: ServerLevel, places: CityPlaces, capital: BlockPos, dead: Resident, except: Set<Int>, data: TaleData): Resident? {
        val pool = TaleCast.pool(level, capital).filter { it.id !in except && it.home != dead.home && TaleCast.free(it, data) }
        val temple = places.lodgings.values.filter { it.kind in CityPlaces.TEMPLES }.map { it.box.center }
        if (temple.isNotEmpty()) {
            pool.filter { it.role == TownRole.PRIEST }
                .minByOrNull { r -> places.lodgings[r.home]?.box?.center?.let { h -> temple.minOf { it.distSqr(h) } } ?: Double.MAX_VALUE }
                ?.let { return it }
        }
        return pool.minWithOrNull(compareBy<Resident>({ it.since }, { it.id }))
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        when (t.step) {
            // No priest to say the words: the town's oldest will
            AT_ASK -> {
                val priest = c.resident(t, PRIEST)
                if (priest != null && priest.role != TownRole.PRIEST && t.why.none { it.kind == "chair.elder" }) c.why(t, "chair.elder", priest.name)
            }
            AT_WORDS, AT_CANDLES, AT_PLACE -> {
                val next = first(t)
                if (next > t.step) c.go(t, next)
            }
            AT_LAY -> {
                // The day after steps 1 to 3 are done, or D7 with whatever is done; the close of that day settles it
                if (t.n[N_LAY] == 0) setLay(c, t, if (first(t) == AT_LAY) minOf(c.day + 1, t.d0 + LAST) else t.d0 + LAST)
                t.by = layDay(t)
                if (place(t) == null) (placeFor(c, t, t.n[N_PLACE]) ?: Memorials.anchor(c, false))?.let { t.setS(S_PLACE, code(it)) }
                c.data.changed()
            }
        }
    }

    /** A deed was done (a word, candles, the place): the tale goes on to the first unfinished of steps 1 to 3, or the laying. */
    internal fun progress(c: Ctx, t: Tale) {
        if (!t.open) return
        val next = first(t)
        when {
            t.step in AT_WORDS..AT_PLACE && next > t.step -> {
                if (next == AT_LAY) c.why(t, "chair.ready")
                c.go(t, next)
            }
            // All of it done after the days ran short: the laying is the next dusk, if that is sooner
            t.step == AT_LAY && next == AT_LAY -> {
                val sooner = minOf(c.day + 1, t.d0 + LAST)
                if (sooner < layDay(t)) {
                    setLay(c, t, sooner)
                    c.why(t, "chair.ready")
                }
            }
        }
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        if (e is Event.Gave) candles(c, t, e)
    }

    /** Candles of any colour into the mourner's hands (step 2, or any of 1 to 4): up to three in all. */
    private fun candles(c: Ctx, t: Tale, e: Event.Gave) {
        if (e.tale != t.id || !t.open || t.step < AT_WORDS || flag(t, LAID)) return
        val brought = e.items.filter { it.`is`(ItemTags.CANDLES) }.sumOf { it.count }
        val took = minOf(brought, ALL - t.n[N_CANDLES])
        if (took <= 0) return
        t.n[N_CANDLES] += took
        t.partFor(e.player.uuid)?.let { it.did = it.did or Part.GAVE }
        c.data.changed()
        if (took == 1) c.why(t, "chair.candle", e.player.gameProfile.name) else c.why(t, "chair.candles", e.player.gameProfile.name, took.toString())
        progress(c, t)
    }

    /**
     * The world's way. At D2 nobody has taken it up: the tale goes on to the words, and anyone may still come until D7. At D5 steps 1 to 3
     * are not all done: the laying is set for D7 with what is ready. At the laying's close it was not seen through: it ends now with what was
     * done (W if nothing was: [finish]).
     */
    override fun settle(c: Ctx, t: Tale) {
        when (t.step) {
            AT_ASK -> c.go(t, AT_WORDS)
            AT_WORDS, AT_CANDLES, AT_PLACE -> {
                c.why(t, "chair.late")
                c.go(t, AT_LAY)
            }
            else -> finish(c, t)
        }
    }

    /** The laying was not seen through (no one by at dusk, or the day is over): the tale ends with what was done; W only at the close. */
    internal fun finish(c: Ctx, t: Tale) {
        if (!t.open) return
        val letter = letter(t)
        if (letter == Ending.W) c.why(t, "chair.nobody", dead(t))
        c.end(t, letter)
    }

    /** The posy went down in a laying seen through ([ChairScenes]): the tale ends with what was done, and the scene was its ending. */
    internal fun laid(c: Ctx, t: Tale) {
        if (!t.open) return
        t.n[N_FLAGS] = t.n[N_FLAGS] or LAID
        c.data.changed()
        c.end(t, letter(t).takeIf { it != Ending.W } ?: Ending.C)
    }

    // ---- The consequences of an ending ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        val level = c.level
        // The deciding deed (8.2): the candles, or the place if there were none
        if (ending != Ending.W) (t.parts.firstOrNull { it.did(Part.GAVE) } ?: t.parts.firstOrNull { it.did(Part.CHOSE) })?.let { it.did = it.did or Part.DECIDED }
        // The stone is set whatever the ending: at the place chosen, else by the temple
        val rec = memorial(c, t)
        if (!flag(t, LAID) && ending != Ending.W && !ChairScenes.willPlay(c, t, rec)) ChairScenes.atOnce(c, t, rec, ending)
        val mourner = c.resident(t, MOURNER)
        val home = mourner?.home ?: deadOf(level, t)?.home ?: -1
        when (ending) {
            Ending.A -> {
                when (t.n[N_PLACE]) {
                    WELL -> c.flip(t, MOURNER, Trait.CHATTY)
                    DOOR -> {}
                    else -> c.flip(t, MOURNER, Trait.DEVOUT)
                }
                if (home >= 0) c.protect(t, home, KEPT)
                thank(c, t, FRIENDS)
            }
            Ending.B -> {
                if (home >= 0) c.protect(t, home, KEPT)
                thank(c, t, FRIENDS.filter { heard(t, it) })
            }
            Ending.C -> if (home >= 0) c.protect(t, home, KEPT_C)
            Ending.W -> {
                c.why(t, "chair.stone", dead(t))
                if (!flag(t, MOURNER_GONE) && mourner != null && mourner.alive && home >= 0) leave(c, t, mourner, home)
            }
        }
        ChairWatch.ended(level, t)
        c.data.changed()
    }

    /** The town's thanks from the friends who came ([slots]) to each player who did their part (the mourner's are the engine's). */
    private fun thank(c: Ctx, t: Tale, slots: List<String>) {
        for (part in t.parts) if (TaleShares.holds(part)) for (slot in slots) c.favour(t, slot, part.player, Favour.TALE_HELPED)
    }

    /** W: the household goes (6.5), the mourner saying so at the gate if anyone is by; if the town will not let it, they keep to themselves. */
    private fun leave(c: Ctx, t: Tale, mourner: Resident, home: Int) {
        c.say(t, MOURNER, "tale.chair.leave", TaleLines.facts("talk.detail.tale.chair.leave", TaleLines.lit(mourner.family)))
        if (c.leave(t, home)) {
            c.why(t, "chair.left", mourner.family)
            return
        }
        if (c.flip(t, MOURNER, Trait.SHY)) c.why(t, "chair.shy", mourner.name)
    }

    // ---- Those who go ----

    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            // The one left behind is gone too: the stone is set all the same (W)
            MOURNER -> {
                t.n[N_FLAGS] = t.n[N_FLAGS] or MOURNER_GONE
                c.data.changed()
                c.end(t, Ending.W)
            }
            // No priest of the trade to stand in: the oldest resident will
            PRIEST -> recast(c, t, slot) { except -> deadOf(c.level, t)?.let { priestFor(c.level, c.here.places, c.here.capital.center, it, except, c.data) } }
            // No one of the friend's trade: the next nearest who knew them
            else -> if (slot in FRIENDS) {
                recast(c, t, slot) { except -> deadOf(c.level, t)?.let { friendsFor(c.level, c.here.places, c.here.capital.center, it, except, c.data).firstOrNull() } }
            }
        }
    }

    private fun recast(c: Ctx, t: Tale, slot: String, pick: (Set<Int>) -> Resident?) {
        val cast = t.cast(slot) ?: return
        val taken = t.cast.filter { it.resident >= 0 && it.gone.isEmpty() }.mapTo(HashSet()) { it.resident }
        val next = pick(taken) ?: return
        val old = cast.name
        cast.resident = next.id
        cast.name = next.name
        cast.gone = ""
        cast.held = false
        TaleEngine.reindex()
        if (slot in FRIENDS) setDoor(t, slot, doorOf(c.here.places, next))
        c.data.changed()
        ChairWatch.forget(t.id, slot)
        c.why(t, "stood_in", next.name, old)
    }

    /** A friend stood in for by the engine: the tracker points at the new one's door. */
    override fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {
        if (slot in FRIENDS) {
            setDoor(t, slot, doorOf(c.here.places, c.resident(t, slot)))
            c.data.changed()
        }
        ChairWatch.forget(t.id, slot)
    }

    // ---- The scenes ----

    override fun scene(c: Ctx, t: Tale): Stage? = if (Tales.scenes()) ChairScenes.scene(c, t) else null

    // ---- The talk ----

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    override fun asker(t: Tale): String = MOURNER

    /** The dead's name and how long ago they died, in grey, from the record. */
    private fun died(turn: TaleTurn): String? {
        val t = turn.tale
        val name = TaleLines.lit(dead(t))
        val r = deadOf(turn.level, t)
        val days = if (r != null) (VillageBoards.day(turn.level) - residents(turn.level).diedOn(r)).coerceAtLeast(0L) else return TaleLines.facts("talk.detail.tale.chair.ask.n", name, (turn.today - t.d0).toInt())
        return when (days) {
            0L -> TaleLines.facts("talk.detail.tale.chair.ask.0", name)
            1L -> TaleLines.facts("talk.detail.tale.chair.ask.1", name)
            else -> TaleLines.facts("talk.detail.tale.chair.ask.n", name, days.toInt())
        }
    }

    /** How it stands, in grey: the words, the candles and the place so far; once all is set, when and where the laying is. */
    internal fun standDetail(t: Tale, today: Long): String? {
        if (t.step == AT_LAY) {
            val place = t.n[N_PLACE].takeIf { it != 0 } ?: TEMPLE
            return TaleLines.facts("talk.detail.tale.chair.stand.lay", TaleLines.due((layDay(t) - today).coerceAtLeast(0L)), placeWord(place))
        }
        return TaleLines.facts("talk.detail.tale.chair.stand", words(t.n[N_WORDS]), t.n[N_CANDLES], placeWord(t.n[N_PLACE]))
    }

    override fun detail(beat: String, turn: TaleTurn): String? = when (beat) {
        "ask", "rumour" -> died(turn)
        "stand" -> standDetail(turn.tale, turn.today)
        else -> null
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = when (beat) {
        "ask", "passing" -> Activity.SIGH
        "stand" -> Activity.NOD
        else -> null
    }

    override fun toward(beat: String, turn: TaleTurn): BlockPos? = if (beat == "stand" && turn.slot == MOURNER) place(turn.tale) else null

    override fun options(turn: TaleTurn): List<TaleOption> {
        val mine = turn.mine ?: return listOf()
        if (mine.status != Part.Status.IN && mine.status != Part.Status.DONE) return listOf()
        val t = turn.tale
        if (!t.open || t.step < AT_WORDS || flag(t, LAID)) return listOf()
        return when (turn.slot) {
            MOURNER -> {
                val out = ArrayList<TaleOption>()
                if (t.n[N_CANDLES] < ALL && turn.player?.let { candleIn(it) } != null) out += candleOption()
                if (t.n[N_PLACE] == 0) turn.ctx()?.let { c -> for (p in places(c, t)) out += placeOption(p) }
                out
            }
            PRIEST -> listOf(proper())
            else -> if (turn.slot in FRIENDS && !heard(t, turn.slot)) listOf(knew(t, turn.slot)) else listOf()
        }
    }

    private fun think() = TaleReply("ask.think", gesture = Activity.THINK, back = true)

    // ---- The friends (step 1) ----

    /** "You knew them?": the friend's word, the dead and their tie to them in grey. */
    private fun knew(t: Tale, slot: String) = TaleOption("knew", Component.translatable("talk.guildmark.choice.tale.chair.knew", first(dead(t)))) { turn ->
        val tale = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!word(c, tale, player, slot)) return@TaleOption null
        TaleReply(listOf(TaleSay(family(tale, slot), tie(c.level, c.here.places, tale, slot))), Activity.NOD)
    }

    /**
     * [player] hears friend [slot]'s word (the talk and `tale chair word`): the bit is set, the third heard is a real deed (WALKED), and the
     * tale goes on. False if it does not apply now (heard already, the tale past it, the player not in it).
     */
    internal fun word(c: Ctx, t: Tale, player: ServerPlayer, slot: String): Boolean {
        if (!t.open || t.step < AT_WORDS || flag(t, LAID) || slot !in FRIENDS || heard(t, slot)) return false
        val part = t.part(player.uuid)?.takeIf { it.status == Part.Status.IN || it.status == Part.Status.DONE } ?: return false
        t.n[N_WORDS] = t.n[N_WORDS] or bit(slot)
        c.data.changed()
        c.why(t, "chair.word", t.cast(slot)?.name.orEmpty(), dead(t))
        if (words(t.n[N_WORDS]) == ALL) {
            part.did = part.did or Part.WALKED
            c.why(t, "chair.words", dead(t))
        }
        progress(c, t)
        return true
    }

    /**
     * The friend's tie to the dead in grey, from the record as it is now: one of the same trade says what the dead did ("who baked our
     * bread"), a neighbour "next door", one of the tavern's round "at the tavern", anyone else that they knew them well.
     */
    internal fun tie(level: ServerLevel, places: CityPlaces, t: Tale, slot: String): String? {
        val name = TaleLines.lit(dead(t))
        val r = deadOf(level, t)
        val friend = t.resident(slot).takeIf { it >= 0 }?.let { residents(level)[it] }
        if (r == null || friend == null) return TaleLines.facts("talk.detail.tale.chair.tie.known", name)
        if (friend.role == r.role) trade(r.role)?.let { return TaleLines.facts(it, name) }
        if (Witness.neighbours(level, r).any { it.id == friend.id }) return TaleLines.facts("talk.detail.tale.chair.tie.door", name)
        val tavern = tavernOf(places, r)
        if (tavern != null && Witness.round(level, r.capital, tavern, r.home).any { it.id == friend.id }) return TaleLines.facts("talk.detail.tale.chair.tie.tavern", name)
        return TaleLines.facts("talk.detail.tale.chair.tie.known", name)
    }

    /** What one of their trade says the dead did, as a detail key; null for a trade that has no shared work (the citizens). */
    private fun trade(role: TownRole): String? = when (role) {
        TownRole.SMITH -> "talk.detail.tale.chair.tie.smith"
        TownRole.BAKER -> "talk.detail.tale.chair.tie.baker"
        TownRole.MERCHANT -> "talk.detail.tale.chair.tie.merchant"
        TownRole.FARMER -> "talk.detail.tale.chair.tie.farmer"
        TownRole.FISHER -> "talk.detail.tale.chair.tie.fisher"
        TownRole.NOBLE -> "talk.detail.tale.chair.tie.noble"
        TownRole.INNKEEPER -> "talk.detail.tale.chair.tie.innkeeper"
        TownRole.BARD -> "talk.detail.tale.chair.tie.bard"
        TownRole.PRIEST -> "talk.detail.tale.chair.tie.priest"
        else -> null
    }

    // ---- The priest ----

    /** "What does a laying need?": what is proper, and in grey choosing the place with the mourner (or where the stone will stand). */
    private fun proper() = TaleOption("proper", Component.translatable("talk.guildmark.choice.tale.chair.proper")) { turn ->
        val t = turn.tale
        if (!t.open) return@TaleOption null
        val detail = when (t.n[N_PLACE]) {
            TEMPLE -> TaleLines.facts("talk.detail.tale.chair.priest.temple")
            WELL -> TaleLines.facts("talk.detail.tale.chair.priest.well")
            DOOR -> TaleLines.facts("talk.detail.tale.chair.priest.door")
            else -> TaleLines.facts("talk.detail.tale.chair.priest", TaleLines.lit(first(t.cast(MOURNER)?.name)))
        }
        TaleReply(listOf(TaleSay("tale.chair.priest", detail)), Activity.EXPLAIN, toward = place(t))
    }

    // ---- The mourner (steps 2 and 3) ----

    /** The slot of the first candle (of any colour) in [player]'s pack, or null. */
    private fun candleIn(player: ServerPlayer): Int? {
        val inventory = player.inventory
        for (i in 0 until inventory.containerSize) if (inventory.getItem(i).`is`(ItemTags.CANDLES)) return i
        return null
    }

    /** "Here are candles for the laying.": as many as are still wanted, from the pack, into the mourner's hands. */
    private fun candleOption() = TaleOption("candles", Component.translatable("talk.guildmark.choice.tale.chair.candles")) { turn ->
        val t = turn.tale
        val player = turn.player ?: return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        val given = give(c, t, player) ?: return@TaleOption think()
        val mourner = turn.ask.speaker
        // The candles are in their hands as they take them (the clip draws what is carried)
        mourner.carried = given.copy()
        mourner.carriedUntil = mourner.tickCount + Activity.RECEIVE.ticks
        TaleReply(listOf(TaleSay("tale.chair.candles", TaleLines.facts("talk.detail.tale.chair.candles", t.n[N_CANDLES]))), Activity.RECEIVE)
    }

    /**
     * [player] gives the mourner as many candles from their pack as are still wanted (the talk and `tale chair candles`): told to the tale as
     * `Event.Gave`, so it is counted where every gift is. The first stack given, or null if none was taken (and none is lost).
     */
    internal fun give(c: Ctx, t: Tale, player: ServerPlayer): ItemStack? {
        if (!t.open || t.step < AT_WORDS || flag(t, LAID) || t.n[N_CANDLES] >= ALL) return null
        val wanted = ALL - t.n[N_CANDLES]
        val inventory = player.inventory
        val taken = ArrayList<ItemStack>()
        var left = wanted
        for (i in 0 until inventory.containerSize) {
            if (left <= 0) break
            val stack = inventory.getItem(i)
            if (!stack.`is`(ItemTags.CANDLES)) continue
            val part = stack.split(minOf(left, stack.count))
            left -= part.count
            taken += part
        }
        if (taken.isEmpty()) return null
        val before = t.n[N_CANDLES]
        Tales.tell(c.level, Event.Gave(t.id, player, 0, taken))
        if (t.n[N_CANDLES] == before) {
            // The tale could not take them: they go back in the pack
            for (s in taken) player.inventory.placeItemBackInInventory(s, Prediction.SERVER_ONLY)
            return null
        }
        return taken.first()
    }

    /** One of the places, as the player would put it; the mourner answers how it now stands, with the gesture of the choice. */
    private fun placeOption(place: Int): TaleOption {
        val (word, label) = when (place) {
            TEMPLE -> "temple" to "talk.guildmark.choice.tale.chair.temple"
            WELL -> "well" to "talk.guildmark.choice.tale.chair.well"
            else -> "door" to "talk.guildmark.choice.tale.chair.door"
        }
        return TaleOption(word, Component.translatable(label)) { turn ->
            val t = turn.tale
            val c = turn.ctx() ?: return@TaleOption think()
            val player = turn.player ?: return@TaleOption null
            val at = choose(c, t, player, place) ?: return@TaleOption null
            val gesture = if (place == DOOR) Activity.NOD else Activity.POINT
            TaleReply(listOf(TaleSay("tale.chair.stand", standDetail(t, turn.today))), gesture, toward = if (place == DOOR) null else at)
        }
    }

    /** [player] chooses place [place] (the talk and `tale chair place`): the CHOOSE is theirs, and the tale goes on. Where the stone will stand, or null. */
    internal fun choose(c: Ctx, t: Tale, player: ServerPlayer, place: Int): BlockPos? {
        if (!t.open || t.step < AT_WORDS || flag(t, LAID) || t.n[N_PLACE] != 0) return null
        val part = t.part(player.uuid)?.takeIf { it.status == Part.Status.IN || it.status == Part.Status.DONE } ?: return null
        val at = placeFor(c, t, place) ?: return null
        t.n[N_PLACE] = place
        t.setS(S_PLACE, code(at))
        part.did = part.did or Part.CHOSE
        c.data.changed()
        val who = player.gameProfile.name
        when (place) {
            TEMPLE -> c.why(t, "chair.place.temple", who)
            WELL -> c.why(t, "chair.place.well", who)
            else -> c.why(t, "chair.place.door", who)
        }
        progress(c, t)
        return at
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val title = TaleKeys.title(id)
        return when (t.step) {
            AT_ASK -> View(title, status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(first(t.cast(MOURNER)?.name)), target = door(t, MOURNER))
            AT_WORDS -> {
                val next = FRIENDS.firstOrNull { !heard(t, it) && t.cast(it)?.gone.isNullOrEmpty() }
                View(title, status = TaleKeys.status(id, AT_WORDS), statusArgs = listOf(words(t.n[N_WORDS]).toString()), target = next?.let { door(t, it) } ?: door(t, MOURNER))
            }
            AT_CANDLES -> View(title, status = TaleKeys.status(id, AT_CANDLES), statusArgs = listOf(t.n[N_CANDLES].toString()), target = door(t, MOURNER))
            AT_PLACE -> View(title, status = TaleKeys.status(id, AT_PLACE), target = door(t, MOURNER))
            else -> View(title, status = TaleKeys.status(id, AT_LAY), target = place(t) ?: door(t, MOURNER))
        }
    }
}
