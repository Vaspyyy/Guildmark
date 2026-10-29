package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Reason
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksKinds
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
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
import io.github.vaspyyy.guildmark.tales.TaleShares
import io.github.vaspyyy.guildmark.tales.TaleTurn
import io.github.vaspyyy.guildmark.tales.TaleVoice
import io.github.vaspyyy.guildmark.tales.TaleWords
import io.github.vaspyyy.guildmark.tales.Template
import io.github.vaspyyy.guildmark.tales.Verb
import io.github.vaspyyy.guildmark.tales.View
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.Random
import java.util.UUID

/**
 * The Bell Moot (living-world/stories/design.md 8.7, size 2), Tales T4: the town needs a works (an oven, a granary, a tower) and cannot agree
 * to pay for it. It has been wanted for days and hardly a Mark is pledged; the crier rings the handbell and puts it to the square. The player is
 * not the town's leader: they are the one who can say the true thing, because the true thing is written in the town's own times.
 *
 * **Steps.** 0 ASK (the crier's call at noon on D0, and from 11:00 the two sides arguing in a half ring of the square, S3's crowd variant; the
 * crier asks; by D2), 1 MEET (each side's account: why they argue as they do, and from which reason; by D2), 2 CHOOSE (at noon on D2, from the
 * end of the crier's news until two, the moot sits at the board and the player speaks one of the town's own reasons, or puts Marks in first;
 * by D2). The first deciding word ends it, and the verdict is the ending's scene. The moot sitting, its holds, its crowd and its two o'clock are
 * [MootSquare]'s; the scenes are [MootScenes]'.
 *
 * **The arguments.** What the player may say is the record's: [offered] takes the town's reasons (`Fortunes.reasons`), the three biggest that
 * [bear] on the works first, then the biggest that do not. The biggest bearing reason carries the room (A), another bearing one half carries
 * it (B), one that does not bear, or the vague word, carries nothing (C); 10 Marks or more pledged first by a player in the tale lift the verdict
 * a step. Read at the moment of speaking ([speak]), so the same words are right on one day and wrong on another.
 *
 * **Numbers.** `n[0]` the works' kind ([KINDS]), `n[1]` its id (-1 in the lean variant until it is asked for), `n[2]` its road (a tower's; -1),
 * `n[3]` the bits: the sides heard (1 for, 2 against), the quarrel shown (4), a word spoken at the moot (8); `n[4]` the Marks pledged in pledges
 * of 10 or more by players in the tale, `n[5]` whether the moot sits (1), `n[6]` the Marks the town gave, `n[7]` the verdict (1 to 4 for A to
 * W as a word or `tale end` made it, 5 the works paid for before the moot met, 6 it was called off). **Strings.** `s[0]` the works in a word,
 * lower case ("second oven": the sentences' `%1$s`), `s[1]` the player whose word decided it, `s[2]` the two sides' front doors, `s[3]` where
 * the crier stands to cry (block positions in base 36).
 *
 * **The endings** (design 8.7 and 6.3). A `given`: the town's full gift on the works' plaque (`Works.fundTown`: 40 percent of the price, at
 * most 60 Marks), `for` CHEERFUL, the town's thanks to the sides; B `half`: half the gift, the thanks; C `deferred`: no gift, the works stays
 * wanted (or, in the lean variant, is asked for); W `broke`: nobody spoke by two on D2, `for` and `against` GRUMPY. The times, the cry, the
 * keepsake and the pay are the engine's.
 */
object Moot : Template, TaleVoice, TaleWords.Names {
    override val id: String = "moot"
    override val size: Int = 2

    /** Every step hears the works (`funded` and `removed` end it, a pledge lifts it) and the day. */
    private val HEARS: Set<Event.Kind> = setOf(Event.Kind.DAY, Event.Kind.WORKS)

    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2, hears = HEARS),
        Step(Verb.MEET, by = 2, hears = HEARS),
        Step(Verb.CHOOSE, by = 2, real = true, also = listOf(Verb.GIVE), hears = HEARS),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "given"),
        Ending(Ending.B, "half"),
        Ending(Ending.C, "deferred"),
        Ending(Ending.W, "broke"),
    )

    /** The moot's W splits the town: the times a reason of -1 for three days (6.1). */
    override val hurts: Boolean = true

    /** A side who dies or goes is stood in for by someone of the same trade (8.7); the crier is the moot's own, and stood in for by [gone]. */
    override val standing: Set<String> = setOf("for", "against")

    override val numbers: List<String> = listOf("kind", "works", "road", "bits", "pledged", "sits", "gift", "verdict")
    override val strings: List<String> = listOf("works_word", "decider", "doors", "spot")

    override fun needs(): Boolean = Works.enabled() && Fortunes.enabled()

    // ---- The slots ----

    const val SPEAKER = "speaker"
    const val FOR = "for"
    const val AGAINST = "against"

    internal const val KIND = 0
    internal const val WORKS = 1
    internal const val ROAD = 2
    internal const val BITS = 3
    internal const val PLEDGED = 4
    internal const val SITS = 5
    internal const val GIFT = 6
    internal const val VERDICT = 7
    internal const val NAME = 0
    internal const val DECIDER = 1
    internal const val DOORS = 2
    internal const val SPOT = 3

    internal const val HEARD_FOR = 1
    internal const val HEARD_AGAINST = 2
    internal const val QUARRELLED = 4
    internal const val SPOKE = 8

    /** The verdicts that are not a letter's: the works paid for, or called off, before the moot met. */
    internal const val PAID = 5
    internal const val REMOVED = 6

    internal const val AT_ASK = 0
    internal const val AT_MEET = 1
    internal const val AT_CHOOSE = 2

    /** The market day after the seed the moot meets on, and how many days a works must have been wanted for one to be called. */
    internal const val MOOT_DAY = 2
    private const val WANTED_DAYS = 3

    /** A works is unfunded while under a tenth of its price is pledged. */
    private const val UNFUNDED = 10

    /** The Marks that lift the verdict a step, and that "I'll put Marks in first" pledges. */
    internal const val LIFT = 10
    internal const val PLEDGE = 10

    /** The town's gift (6.3): this share of the price, in percent, and no more than so many Marks. */
    private const val GIFT_SHARE = 40
    private const val GIFT_MOST = 60

    /** The kinds a moot is called for, in the order of `n[0]`. */
    internal val KINDS: List<WorksKind> = listOf(WorksKind.OVEN, WorksKind.GRANARY, WorksKind.GREAT_GRANARY, WorksKind.TOWER)

    /** The why the lean variant asks for its works with. */
    internal const val WHY = "gui.guildmark.works.why.moot"

    /** The trades whose work the works helps: `for` is one of them, else any adult citizen. */
    private fun helped(kind: WorksKind): Set<TownRole> = when (kind) {
        WorksKind.OVEN -> setOf(TownRole.BAKER)
        WorksKind.GRANARY, WorksKind.GREAT_GRANARY -> setOf(TownRole.FARMER, TownRole.FISHER)
        else -> setOf(TownRole.GUARD)
    }

    // ---- Reading the record ----

    internal fun first(name: String?): String = name.orEmpty().substringBefore(' ')

    internal fun kind(t: Tale): WorksKind = KINDS.getOrElse(t.n[KIND]) { WorksKind.OVEN }

    /** The works in a word, lower case, as the sentences say it ("second oven"). */
    fun word(t: Tale): String = t.s[NAME].ifBlank { FortuneReasons.works(kind(t).id).string }

    override fun words(t: Tale): List<String> = listOf(word(t))

    /** The tale's works as the works see it, if it is in their records. */
    internal fun works(level: ServerLevel, t: Tale): WorksView? = t.n[WORKS].takeIf { it >= 0 }?.let { Works.get(level, it) }

    private fun pos(text: String): BlockPos? = text.toLongOrNull(36)?.let { BlockPos.of(it) }

    private fun code(pos: BlockPos): String = pos.asLong().toString(36)

    /** Where the crier stands to cry, for the tracker. */
    internal fun spot(t: Tale): BlockPos? = pos(t.s[SPOT])

    /** [slot]'s front door, for the tracker. */
    internal fun door(t: Tale, slot: String): BlockPos? = t.s[DOORS].split(';').getOrNull(if (slot == FOR) 0 else 1)?.let { pos(it) }

    internal fun decider(t: Tale): UUID? = try {
        UUID.fromString(t.s[DECIDER])
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The town's gift for a works of [price] (6.3): 40 percent of it, and no more than 60 Marks. */
    fun gift(price: Int): Int = minOf(price * GIFT_SHARE / 100, GIFT_MOST)

    /** Whether reason [r] bears on a works of [kind] (8.7's table): it weighs the town down, and is of the works' own kind of trouble. */
    fun bears(kind: WorksKind, r: Reason): Boolean {
        if (r.points >= 0) return false
        return when (kind) {
            WorksKind.OVEN -> r.kind == FortuneReasons.HUNGRY || r.kind == FortuneReasons.DEAR || r.kind == FortuneReasons.IDLE && "ovens" in r.args
            WorksKind.GRANARY, WorksKind.GREAT_GRANARY ->
                r.kind == FortuneReasons.HUNGRY || r.kind == FortuneReasons.DEAR || r.kind == FortuneReasons.WANTING || r.kind == FortuneReasons.IDLE && "mill" in r.args
            WorksKind.TOWER -> r.kind == FortuneReasons.ROAD || r.kind == FortuneReasons.WAYLAID
            else -> false
        }
    }

    /** A reason as the moot knows it again a moment later: its kind, and for a road its side. */
    fun ident(r: Reason): String = if (r.kind == FortuneReasons.ROAD) "road_" + r.args.getOrElse(1) { "" } else r.kind

    /** The town's reasons now, biggest first, without the tales' own. */
    internal fun reasons(level: ServerLevel, capital: Long): List<Reason> =
        FortuneReasons.biggestFirst(Fortunes.reasons(level, capital)).filter { it.kind != FortuneReasons.TALE }

    /** What the crier offers to be said: the three biggest reasons that bear on the works, then the biggest that do not (the troubles first). */
    internal fun offered(level: ServerLevel, t: Tale): List<Reason> {
        val kind = kind(t)
        val all = reasons(level, t.capital)
        val bearing = all.filter { bears(kind, it) }
        val rest = all.filter { !bears(kind, it) }.sortedBy { if (it.points < 0) 0 else 1 }
        return (bearing.take(3) + rest).distinctBy { ident(it) }.take(3)
    }

    /**
     * What the word [said] (a reason's [ident], or null for the vague word) carries by itself now, as a letter: the biggest reason that
     * bears on the works A, another bearing one B, any other (or one the record no longer has) C.
     */
    fun carries(level: ServerLevel, t: Tale, said: String?): String {
        if (said == null) return Ending.C
        val kind = kind(t)
        val now = reasons(level, t.capital)
        val r = now.firstOrNull { ident(it) == said } ?: return Ending.C
        val heaviest = now.firstOrNull { bears(kind, it) }
        return when {
            heaviest != null && ident(heaviest) == said -> Ending.A
            bears(kind, r) -> Ending.B
            else -> Ending.C
        }
    }

    /** What the word [said] carries ([carries]), lifted a step (C to B, B to A) if players in the tale pledged 10 Marks or more first. */
    fun verdict(level: ServerLevel, t: Tale, said: String?): String {
        val base = carries(level, t, said)
        if (t.n[PLEDGED] < LIFT) return base
        return if (base == Ending.C) Ending.B else Ending.A
    }

    /** The argument `for` makes: the heaviest reason that bears on the works, in the town's own words; else the works' own why; else none. */
    internal fun argument(level: ServerLevel, t: Tale): String? {
        val kind = kind(t)
        val r = reasons(level, t.capital).firstOrNull { bears(kind, it) }
        if (r != null) return FortuneReasons.text(r).string
        return works(level, t)?.why?.let { Component.translatable(it).string }
    }

    /** The works' price: its record's, else what one of its kind would cost the town now (the lean variant before it is asked for). */
    internal fun price(c: Ctx, t: Tale): Int = works(c.level, t)?.price ?: WorksKinds.parts(kind(t), c.entry.town).values.sum()

    // ---- Seeding ----

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        if (!Works.enabled() || !Fortunes.enabled()) return c.none("the works or the fortunes are switched off")
        val capital = c.capital
        val places = c.here.places
        var madeUp = ""
        val all = Works.all(level, capital)
        val unfunded = all.filter { it.kind in KINDS && it.state == WorksState.WANTED && it.pledged * UNFUNDED < it.price }
        var works: WorksView? = unfunded.filter { c.day - it.wanted >= WANTED_DAYS }.minWithOrNull(compareBy<WorksView> { it.wanted }.thenBy { it.id })
        var lean: Pair<WorksKind, Int>? = null
        if (works == null) {
            // The lean variant: nothing wanted or going, and the times lean or hard
            val going = all.any { it.kind.per != WorksKind.Per.PLAYER && it.state != WorksState.DONE }
            val times = Fortunes.times(level, capital)
            val pick = if (going) null else leanKind(c, all)
            when {
                pick != null && (times == Times.LEAN || times == Times.HARD) -> lean = pick
                c.forced && pick != null -> {
                    lean = pick
                    madeUp = "the lean times (they are ${times.id})"
                }
                c.forced && unfunded.isNotEmpty() -> {
                    works = unfunded.minWithOrNull(compareBy<WorksView> { it.wanted }.thenBy { it.id })
                    madeUp = "three days wanted (the ${works?.kind?.id} was wanted ${works?.let { c.day - it.wanted }} days)"
                }
                c.forced && !going && all.none { it.kind == WorksKind.OVEN } -> {
                    lean = WorksKind.OVEN to -1
                    madeUp = "a reason for an oven, and the lean times (they are ${times.id})"
                }
            }
            if (works == null && lean == null) {
                return c.none(
                    if (going) "no oven, granary or tower has been wanted ${WANTED_DAYS} days with under a tenth of its price pledged, and a works is going"
                    else "no oven, granary or tower is wanted, and the times are ${times.id} (the moot's lean variant wants them lean or hard, with a reason to point at)",
                )
            }
        }
        val kind = works?.kind ?: lean!!.first
        val road = works?.road ?: lean!!.second
        // The crier, and the two sides
        val crew = Hands.of(level, places)
        val data = TaleData.get(level)
        val residents = Residents.get(level)
        val crierId = crew.crier ?: return c.none("the town has no crier")
        val crier = residents[crierId]?.takeIf { TaleCast.free(it, data) } ?: return c.none("the crier is not free to be cast (dead, away, or in another tale)")
        val pool = TaleCast.pool(level, c.here.capital.center).filter { it.id != crier.id && it.id !in crew.carters && door(c, it) != null }
        val trades = helped(kind)
        val forPool = pool.filter { it.role in trades }.ifEmpty { pool.filter { it.role == TownRole.CITIZEN } }
        if (forPool.isEmpty()) return c.none("nobody of the trade the ${kind.id} helps (or any citizen) is free to argue for it")
        val pro = forPool[c.random.nextInt(forPool.size)]
        val conPool = pool.filter { it.id != pro.id && it.role == TownRole.MERCHANT }.ifEmpty { pool.filter { it.id != pro.id && it.role == TownRole.CITIZEN } }
        if (conPool.isEmpty()) return c.none("no merchant or citizen is free to argue against it")
        val con = conPool[c.random.nextInt(conPool.size)]
        val word = FortuneReasons.works(kind.id).string
        val spot = Crier.cryingSpot(places).pos
        val why = when {
            works == null -> Why("moot.lean", listOf(word))
            works.pledged == 0 -> Why("moot.wanted", listOf(word, (c.day - works.wanted).coerceAtLeast(0L).toString()))
            else -> Why("moot.wanted_some", listOf(word, (c.day - works.wanted).coerceAtLeast(0L).toString(), works.pledged.toString()))
        }
        return Planned(
            cast = listOf(Cast(SPEAKER, crier.id, crier.name), Cast(FOR, pro.id, pro.name), Cast(AGAINST, con.id, con.name)),
            why = why,
            step = AT_ASK,
            n = listOf(KINDS.indexOf(kind), works?.id ?: -1, road, 0, 0, 0, 0, 0),
            s = listOf(word, "", code(door(c, pro)!!) + ";" + code(door(c, con)!!), code(spot)),
            madeUp = madeUp,
        )
    }

    private fun door(c: Seeding, r: Resident): BlockPos? = c.here.places.lodgings[r.home]?.front?.pos

    /**
     * The works the lean variant asks for, from the biggest reason that weighs on the town (6.3): hunger or dear food the oven, a short store or
     * an idle mill the granary (cold ovens the oven), waylaid wagons or a bad road the tower on that road; one the town has had already is
     * passed over for the next reason. Null if no reason points at one.
     */
    private fun leanKind(c: Seeding, all: List<WorksView>): Pair<WorksKind, Int>? {
        val legs = CaravanRoutes.legs(c.level, c.capital).orEmpty()
        fun had(kind: WorksKind, road: Int) = all.any { it.kind == kind && (kind.per != WorksKind.Per.ROAD || it.road == road) }
        fun roadOf(side: String?): Int? = legs.firstOrNull { it.side.name.lowercase() == side }?.road ?: legs.firstOrNull()?.road
        for (r in reasons(c.level, c.capital)) {
            if (r.points >= 0) continue
            val pick: Pair<WorksKind, Int>? = when (r.kind) {
                FortuneReasons.HUNGRY, FortuneReasons.DEAR -> WorksKind.OVEN to -1
                FortuneReasons.WANTING -> WorksKind.GRANARY to -1
                FortuneReasons.IDLE -> if ("mill" in r.args) WorksKind.GRANARY to -1 else WorksKind.OVEN to -1
                FortuneReasons.ROAD -> roadOf(r.args.getOrNull(1))?.let { WorksKind.TOWER to it }
                FortuneReasons.WAYLAID -> roadOf(r.args.getOrNull(2))?.let { WorksKind.TOWER to it }
                else -> null
            }
            if (pick != null && !had(pick.first, pick.second)) return pick
        }
        return null
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        when (t.step) {
            AT_ASK -> {
                // The works' own why, as the second reason the crier can give ("the bread ran short")
                val w = works(c.level, t) ?: return
                val because = "moot.because." + w.why.removePrefix("gui.guildmark.works.why.")
                if (t.why.size <= 1 && TaleLines.quiet(TaleKeys.why(because))) c.why(t, because)
            }
            // The moot is sitting already: whoever comes now has heard the sides' arguing in the square
            AT_MEET -> if (t.n[SITS] == 1) c.go(t, AT_CHOOSE)
        }
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        if (e !is Event.Works || !t.open) return
        val v = e.view
        if (!ours(c, t, v)) return
        when (e.what) {
            "pledged" -> pledged(c, t, e)
            "funded" -> before(c, t, PAID, "moot.paid_before")
            "removed" -> before(c, t, REMOVED, "moot.removed")
        }
    }

    /** Whether [v] is the tale's works: its own, or (the lean variant) the one of its kind and road the town has come to want meanwhile, taken as its own. */
    private fun ours(c: Ctx, t: Tale, v: WorksView): Boolean {
        if (t.n[WORKS] >= 0) return v.id == t.n[WORKS]
        val kind = kind(t)
        if (v.capital != t.capital || v.kind != kind || kind.per == WorksKind.Per.ROAD && v.road != t.n[ROAD]) return false
        t.n[WORKS] = v.id
        c.data.changed()
        return true
    }

    /** A player in the tale pledged toward the works: 10 or more is a GIVE, and lifts the verdict. */
    private fun pledged(c: Ctx, t: Tale, e: Event.Works) {
        val player = e.player ?: return
        if (player == Works.TOWN || e.marks < LIFT) return
        val part = t.part(player) ?: return
        if (part.status != Part.Status.IN && part.status != Part.Status.DONE) return
        part.did = part.did or Part.GAVE
        t.n[PLEDGED] += e.marks
        c.data.changed()
        c.why(t, "moot.pledged", TaleEngine.playerName(c.level, player), e.marks.toString(), word(t))
    }

    /** The works was paid for, or called off, before anyone's word: the moot is not needed, and ends C with no gift. */
    private fun before(c: Ctx, t: Tale, verdict: Int, why: String) {
        if (t.n[VERDICT] != 0) return
        t.n[VERDICT] = verdict
        c.data.changed()
        c.why(t, why, word(t))
        c.end(t, Ending.C)
    }

    /** The world's way at D2's close: nobody spoke, and the moot broke up in anger. */
    override fun settle(c: Ctx, t: Tale) {
        c.why(t, if (t.step == AT_ASK && t.lead == null) "moot.nobody" else "moot.unspoken")
        c.end(t, Ending.W)
    }

    /** Both sides heard (or gone): the tale goes on to the moot. */
    internal fun sidesHeard(c: Ctx, t: Tale) {
        if (!t.open || t.step != AT_MEET) return
        if (t.n[BITS] and (HEARD_FOR or HEARD_AGAINST) == HEARD_FOR or HEARD_AGAINST) c.next(t)
    }

    // ---- The consequences of an ending ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        // `tale end` and the close come here without a word: the verdict is the ending's, for the scene
        if (t.n[VERDICT] == 0) t.n[VERDICT] = Ending.LETTERS.indexOf(ending) + 1
        // The deciding deed (7.3): the word that carried or lost the room
        if (ending != Ending.W) decider(t)?.let { id -> t.part(id)?.let { it.did = it.did or Part.DECIDED } }
        when (ending) {
            Ending.A, Ending.B -> {
                give(c, t, ending)
                if (ending == Ending.A) c.flip(t, FOR, Trait.CHEERFUL)
                // The town's thanks to both sides (the engine's go to the crier, who is not standing)
                for (part in t.parts) if (TaleShares.holds(part)) {
                    c.favour(t, FOR, part.player, Favour.TALE_HELPED)
                    c.favour(t, AGAINST, part.player, Favour.TALE_HELPED)
                }
            }
            // The question stays: in the lean variant the works is asked for (not when it was paid for or called off meanwhile)
            Ending.C -> if (t.n[VERDICT] != PAID && t.n[VERDICT] != REMOVED) worksFor(c, t, propose = true)
            Ending.W -> {
                c.flip(t, FOR, Trait.GRUMPY)
                c.flip(t, AGAINST, Trait.GRUMPY)
            }
        }
    }

    /** The town's gift (6.3): the full one for A, half for B, on the works' plaque as a free pledge of "the town". */
    private fun give(c: Ctx, t: Tale, ending: String) {
        val v = worksFor(c, t, propose = true)
        if (v == null) {
            c.why(t, "moot.no_works", word(t))
            return
        }
        val full = gift(v.price)
        val marks = if (ending == Ending.A) full else full / 2
        val rec = Works.record(c.level, v.id) ?: return
        val given = Works.fundTown(c.level, rec, marks)
        t.n[GIFT] = given
        c.data.changed()
        if (given > 0) c.why(t, "moot.gift", given.toString(), word(t))
    }

    /**
     * The tale's works: its own, or one of its kind and road the town has (taken as its own); with [propose], in the lean variant, asked for
     * now (`Works.proposeNow` with the moot's why, 6.3). Null if there is none and none can be asked for.
     */
    internal fun worksFor(c: Ctx, t: Tale, propose: Boolean): WorksView? {
        works(c.level, t)?.let { return it }
        val kind = kind(t)
        val road = t.n[ROAD]
        Works.all(c.level, c.capital).firstOrNull { it.kind == kind && (kind.per != WorksKind.Per.ROAD || it.road == road) }?.let {
            t.n[WORKS] = it.id
            c.data.changed()
            return it
        }
        if (!propose || !Works.enabled()) return null
        val rec = Works.proposeNow(c.level, c.capital, kind, road, null, WHY) ?: return null
        t.n[WORKS] = rec.id
        c.data.changed()
        c.why(t, "moot.proposed", word(t))
        return Works.get(c.level, rec.id)
    }

    // ---- Those who go ----

    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            // The next crier rings the bell: the citizen the town's crew names, else the one living nearest the board
            SPEAKER -> {
                val cast = t.cast(SPEAKER) ?: return
                val next = nextCrier(c, t) ?: return
                val old = cast.name
                cast.resident = next.id
                cast.name = next.name
                cast.gone = ""
                cast.held = false
                c.data.changed()
                TaleEngine.reindex()
                c.why(t, "stood_in", next.name, old)
            }
            // A side with nobody of their trade to stand in: the moot goes on without their argument
            FOR, AGAINST -> {
                t.n[BITS] = t.n[BITS] or (if (slot == FOR) HEARD_FOR else HEARD_AGAINST)
                c.data.changed()
                sidesHeard(c, t)
            }
        }
    }

    private fun nextCrier(c: Ctx, t: Tale): Resident? {
        val crew = Hands.of(c.level, c.here.places)
        val residents = Residents.get(c.level)
        val taken = t.cast.mapTo(HashSet()) { it.resident }
        crew.crier?.let { residents[it] }?.takeIf { it.id !in taken && TaleCast.free(it, c.data) }?.let { return it }
        val board = c.here.places.marketBoard.pos
        return TaleCast.pool(c.level, c.here.capital.center)
            .filter { it.role == TownRole.CITIZEN && it.id !in taken && it.id !in crew.carters }
            .minWithOrNull(compareBy<Resident>({ r -> c.here.places.lodgings[r.home]?.box?.center?.distSqr(board) ?: Double.MAX_VALUE }, { it.id }))
    }

    // ---- The scenes ----

    override fun scene(c: Ctx, t: Tale): Stage? = MootScenes.scene(c, t)

    // ---- The talk ----

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    override fun asker(t: Tale): String = SPEAKER

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val word = TaleLines.lit(word(t))
        return when (beat) {
            "ask" -> {
                val w = works(turn.level, t) ?: return TaleLines.facts("talk.detail.tale.moot.ask.lean", word)
                when (val days = (turn.today - w.wanted).coerceAtLeast(0L)) {
                    0L -> TaleLines.facts("talk.detail.tale.moot.ask.0", word, w.pledged, w.price)
                    1L -> TaleLines.facts("talk.detail.tale.moot.ask.1", word, w.pledged, w.price)
                    else -> TaleLines.facts("talk.detail.tale.moot.ask.n", word, days.toInt(), w.pledged, w.price)
                }
            }
            "stand" -> if (t.n[SITS] == 1) TaleLines.facts("talk.detail.tale.moot.stand.sits")
            else TaleLines.facts("talk.detail.tale.moot.stand", TaleLines.due((t.d0 + MOOT_DAY - turn.today).coerceAtLeast(0L)))
            else -> null
        }
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = when (beat) {
        "ask" -> Activity.EXPLAIN
        "stand" -> Activity.NOD
        else -> null
    }

    override fun toward(beat: String, turn: TaleTurn): BlockPos? = if (beat == "ask") works(turn.level, turn.tale)?.site?.post else null

    override fun options(turn: TaleTurn): List<TaleOption> {
        val mine = turn.mine ?: return listOf()
        if (mine.status != Part.Status.IN && mine.status != Part.Status.DONE) return listOf()
        val t = turn.tale
        return when (turn.slot) {
            FOR -> if (t.step in AT_MEET..AT_CHOOSE && t.n[BITS] and HEARD_FOR == 0) listOf(forSide()) else listOf()
            AGAINST -> if (t.step in AT_MEET..AT_CHOOSE && t.n[BITS] and HEARD_AGAINST == 0) listOf(againstSide()) else listOf()
            SPEAKER -> if (t.step == AT_CHOOSE && t.n[SITS] == 1) moot(turn) else listOf()
            else -> listOf()
        }
    }

    private fun label(key: String): Component = Component.translatable(key)

    // ---- The sides (step 1) ----

    /** "Why should the town build it?": `for`'s argument, from the heaviest reason that bears on the works, the reason in grey. */
    private fun forSide() = TaleOption("why_for", label("talk.guildmark.choice.tale.moot.why_for")) { turn ->
        val t = turn.tale
        if (!t.open || t.step !in AT_MEET..AT_CHOOSE || t.n[BITS] and HEARD_FOR != 0) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val reason = argument(c.level, t)
        val name = first(turn.name(FOR))
        t.n[BITS] = t.n[BITS] or HEARD_FOR
        c.data.changed()
        c.why(t, if (reason == null) "moot.for_need" else "moot.for", name, reason.orEmpty())
        sidesHeard(c, t)
        val detail = if (reason == null) TaleLines.facts("talk.detail.tale.moot.for_need", TaleLines.lit(name))
        else TaleLines.facts("talk.detail.tale.moot.for", TaleLines.lit(name), TaleLines.lit(reason))
        TaleReply("tale.moot.for", detail, Activity.POINT, works(c.level, t)?.site?.post ?: spot(t))
    }

    /** "Who would pay for it?": `against`'s argument, the price in grey. */
    private fun againstSide() = TaleOption("who_pays", label("talk.guildmark.choice.tale.moot.who_pays")) { turn ->
        val t = turn.tale
        if (!t.open || t.step !in AT_MEET..AT_CHOOSE || t.n[BITS] and HEARD_AGAINST != 0) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val price = price(c, t)
        val name = first(turn.name(AGAINST))
        t.n[BITS] = t.n[BITS] or HEARD_AGAINST
        c.data.changed()
        c.why(t, "moot.against", name, price.toString())
        sidesHeard(c, t)
        TaleReply("tale.moot.against", TaleLines.facts("talk.detail.tale.moot.against", TaleLines.lit(name), price), Activity.WAVE_OFF)
    }

    // ---- The moot (step 2) ----

    /**
     * What may be said at the moot, while it sits: the reasons [offered] (each the town's own sentence, in an order of the tale's own so the
     * heaviest is not always first), the vague word if there are fewer than three, and "I'll put Marks in first" until 10 are in. At most four.
     */
    private fun moot(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val reasons = offered(turn.level, t).shuffled(Random(t.id * 1_000_003L + turn.today))
        val out = ArrayList<TaleOption>()
        for (r in reasons) {
            val said = ident(r)
            val text = FortuneReasons.text(r)
            out += TaleOption("say_$said", text) { tt -> speak(tt, said, text.string) }
        }
        if (out.size < 3) out += TaleOption("vague", label("talk.guildmark.choice.tale.moot.vague")) { tt -> speak(tt, null, null) }
        if (t.n[PLEDGED] < LIFT) out += pledge()
        return out.take(4)
    }

    /**
     * The word spoken at the moot ([said] a reason's [ident], or null for the vague word; [sentence] how it was put): what it carries is read
     * now ([verdict]), it is the deciding deed, and the tale ends with it; the verdict is the ending's scene.
     */
    private fun speak(turn: TaleTurn, said: String?, sentence: String?): TaleReply? {
        val t = turn.tale
        if (!t.open || t.step != AT_CHOOSE || t.n[SITS] != 1) return null
        val c = turn.ctx() ?: return TaleReply("ask.think", gesture = Activity.THINK)
        val player = turn.player ?: return null
        val letter = decide(c, t, player, said, sentence) ?: return null
        return if (letter == Ending.C) TaleReply("tale.moot.deferred", gesture = Activity.SIGH, ends = true)
        else TaleReply("tale.moot.thanks", gesture = Activity.THANK, ends = true)
    }

    /**
     * [player]'s word at the moot ([said] a reason's [ident], or null for the vague word; [sentence] how it was put): what it carries is read
     * from the record now ([verdict]), the CHOOSE is theirs and the deciding deed, and the tale ends with the letter, which is returned. Null if
     * they have not taken the tale up. The talk's options and `tale moot say` both come here.
     */
    internal fun decide(c: Ctx, t: Tale, player: ServerPlayer, said: String?, sentence: String?): String? {
        if (!t.open) return null
        val part = t.part(player.uuid)?.takeIf { it.status == Part.Status.IN || it.status == Part.Status.DONE } ?: return null
        val before = carries(c.level, t, said)
        val letter = verdict(c.level, t, said)
        part.did = part.did or Part.CHOSE
        t.setS(DECIDER, player.uuid.toString())
        t.n[BITS] = t.n[BITS] or SPOKE
        t.n[VERDICT] = Ending.LETTERS.indexOf(letter) + 1
        c.data.changed()
        val who = player.gameProfile.name
        when {
            said == null -> c.why(t, "moot.vague", who)
            before == Ending.A -> c.why(t, "moot.spoke_a", who, sentence.orEmpty())
            before == Ending.B -> c.why(t, "moot.spoke_b", who, sentence.orEmpty(), word(t))
            else -> c.why(t, "moot.spoke_c", who, sentence.orEmpty(), word(t))
        }
        if (letter != before) c.why(t, "moot.lifted", t.n[PLEDGED].toString())
        c.end(t, letter)
        return letter
    }

    /** "I'll put 10 Marks in first.": a real pledge to the works (kept whatever the verdict), which lifts the verdict a step. */
    private fun pledge() = TaleOption("pledge", Component.translatable("talk.guildmark.choice.tale.moot.pledge", PLEDGE)) { turn ->
        val t = turn.tale
        if (!t.open || t.step != AT_CHOOSE || t.n[PLEDGED] >= LIFT) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val player = turn.player ?: return@TaleOption null
        val w = worksFor(c, t, propose = true)
            ?: return@TaleOption TaleReply("tale.moot.stand", TaleLines.facts("talk.detail.tale.moot.no_works", TaleLines.lit(word(t))), Activity.SHRUG)
        val refused = Works.pledge(c.level, player, w.id, PLEDGE)
        when {
            refused == null -> TaleReply(
                "tale.moot.thanks", TaleLines.facts("talk.detail.tale.moot.pledged", PLEDGE, TaleLines.lit(word(t))), Activity.NOD,
            )
            refused.key == "gui.guildmark.works.why.purse" ->
                TaleReply("tale.moot.stand", TaleLines.facts("talk.detail.tale.moot.purse", PLEDGE), Activity.SHRUG)
            else -> TaleReply("tale.moot.stand", TaleLines.pass(Component.translatable(refused.key, *refused.args.toTypedArray())), Activity.SHRUG)
        }
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val title = TaleKeys.title(id)
        return when (t.step) {
            AT_ASK -> View(title, status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(first(t.cast(SPEAKER)?.name)), target = spot(t))
            AT_MEET -> {
                val slot = if (t.n[BITS] and HEARD_FOR == 0) FOR else AGAINST
                View(title, status = TaleKeys.status(id, AT_MEET), statusArgs = listOf(first(t.cast(slot)?.name)), target = door(t, slot))
            }
            else -> View(title, status = TaleKeys.status(id, AT_CHOOSE), tone = if (t.n[SITS] == 1) View.Tone.LATE else View.Tone.TALE, target = spot(t))
        }
    }
}
