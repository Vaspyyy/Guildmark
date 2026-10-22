package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.season.Seasons
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
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCast
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleOption
import io.github.vaspyyy.guildmark.tales.TaleOptions
import io.github.vaspyyy.guildmark.tales.TalePay
import io.github.vaspyyy.guildmark.tales.TaleReply
import io.github.vaspyyy.guildmark.tales.TaleSay
import io.github.vaspyyy.guildmark.tales.TaleTurn
import io.github.vaspyyy.guildmark.tales.TaleVoice
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.Template
import io.github.vaspyyy.guildmark.tales.Verb
import io.github.vaspyyy.guildmark.tales.View
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Spread
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * The Silent Lute (living-world/stories/design.md 8.6, size 1): in the lean days a bard pawned her lute with the innkeeper for her rent and
 * her bread, and has not had the heart to ask for it back. The square has gone quiet. The tale is a redeeming and a concert.
 *
 * - **Seed.** A BARD resident at home and an INNKEEPER (else a merchant) to hold the lute, and either the times LEAN or HARD now or a
 *   festival within six days; a free adult who used to listen is the `regular`. A forced seed makes up the window and says so.
 * - **The silence** is [LuteSilence]: read from this tale's record by `Jobs.bard`, never saved of its own.
 * - **Steps.** 0 ASK (the bard on a bench, S1's hush) by D2; 1 CHOOSE at the keeper (redeem it, half now and half after, or lend it for a
 *   night on the player's word, which needs the town to know them) by D4; 2 GIVE (12 Marks at once, or 6 and 6 within three days) by D5;
 *   3 SCENE, the concert at the next dusk, by D6.
 * - **Endings.** A redeemed (12 Marks at once), B parts (6 and 6), C lent (the word), W silent (the world's way: the lute is sold). The
 *   concert plays the ending in: the full play of it ends the tale at the way the player chose; the cheer follows (A, B) or the lute goes back to
 *   its keeper (C). With the concert not played (nobody near, scenes off) the close ends it at the same ending.
 *
 * What the tale keeps in `n`: [LuteSlots]. In `s[0]` where the lute is kept: "inn", or "stall" for a merchant.
 */
object Lute : Template, TaleVoice {
    override val id: String = "lute"
    override val size: Int = 1

    /** The days are D2, D4, D5 and D6: no step lasts more than two days and the longest route is six. */
    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2),
        Step(Verb.CHOOSE, by = 4, real = true),
        Step(Verb.GIVE, by = 5, real = true, hears = setOf(Event.Kind.GAVE)),
        Step(Verb.SCENE, by = 6, needs = { Tales.scenes() }),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "redeemed"),
        Ending(Ending.B, "parts"),
        Ending(Ending.C, "lent"),
        Ending(Ending.W, "silent"),
    )

    override val standing: Set<String> = setOf("keeper", "regular")

    override val numbers: List<String> = listOf("way", "paid", "sum", "back", "played")

    override val strings: List<String> = listOf("kept")

    // ---- The numbers ----

    /** The sum the keeper wants, and the quarter of it that is a real deed (3 Marks), and the half of a B. */
    private const val SUM = 12
    private const val QUARTER = 3
    private const val HALF = 6

    /** Market days the second half of a B has after the first, and how far a step's `by` may be moved (the tale's twelve days end after d0 + 12). */
    private const val REST_DAYS = 3
    private const val LATEST = 11

    /** A festival within this many days is a reason to want the lute (design 8.6). */
    private const val FESTIVAL_DAYS = 6

    /** The latest the concert is begun tonight: half an hour before the hush ends, so that it has time to be played. */
    private const val TONIGHT = 19.5

    /** The steps by their place in the table. */
    private const val AT_ASK = 0
    private const val AT_CHOOSE = 1
    private const val AT_GIVE = 2
    private const val AT_SCENE = LuteSlots.AT_SCENE

    private const val KEPT = 0
    private const val INN = "inn"
    private const val STALL = "stall"

    override fun needs(): Boolean = Fortunes.enabled() || Seasons.festivalsEnabled()

    // ---- Seed ----

    /** What starts the tale: the sentence of its first reason, and what a forced seed made up. */
    private class Trigger(val why: Why, val madeUp: String = "")

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val pool = TaleCast.pool(level, c.here.capital.center)
        val bards = pool.filter { it.role == TownRole.BARD }
        if (bards.isEmpty()) return c.none("no bard is at home to have pawned her lute")
        val keepers = pool.filter { it.role == TownRole.INNKEEPER }.ifEmpty { pool.filter { it.role == TownRole.MERCHANT } }
        if (keepers.isEmpty()) return c.none("neither an innkeeper nor a merchant is at home to hold the lute")
        val trigger = trigger(c) ?: return c.none("the times are not lean, and no festival is within $FESTIVAL_DAYS days")
        val bard = bards[c.random.nextInt(bards.size)]
        val keeper = keepers[c.random.nextInt(keepers.size)]
        val regular = regular(c, pool, bard, keeper)
        val cast = listOfNotNull(
            Cast("bard", bard.id, bard.name),
            Cast("keeper", keeper.id, keeper.name),
            regular?.let { Cast("regular", it.id, it.name) },
        )
        return Planned(
            cast = cast,
            why = trigger.why,
            step = AT_ASK,
            n = listOf(LuteSlots.NONE, 0, SUM, 0, 0),
            s = listOf(if (keeper.role == TownRole.INNKEEPER) INN else STALL),
            madeUp = trigger.madeUp,
        )
    }

    /** The lean times, or a festival within six days; a forced seed that finds neither makes the lean times up (and says so). */
    private fun trigger(c: Seeding): Trigger? {
        val level = c.level
        var times = Times.STEADY
        if (Fortunes.enabled()) {
            times = Fortunes.times(level, c.capital)
            if (times == Times.LEAN || times == Times.HARD) return Trigger(Why("lute.lean"))
        }
        if (Seasons.festivalsEnabled()) {
            val (kind, days) = FestivalCalendar.next(Seasons.today(level))
            if (days in 1..FESTIVAL_DAYS) return Trigger(Why("lute.fest_${kind.id}"))
        }
        if (!c.forced) return null
        return Trigger(Why("lute.lean"), "the lean times she pawned her lute in (the times here are ${times.id}, and no festival is within $FESTIVAL_DAYS days)")
    }

    /** The one who used to listen: a grown person of another trade, from one of the five homes nearest the bard's. */
    private fun regular(c: Seeding, pool: List<Resident>, bard: Resident, keeper: Resident): Resident? {
        val others = pool.filter { it.id != bard.id && it.id != keeper.id && it.role != TownRole.BARD && it.role != TownRole.INNKEEPER }
        if (others.isEmpty()) return null
        val lodgings = c.here.places.lodgings
        val from = lodgings[bard.home]?.box?.center
        val near = if (from == null) others else others.sortedBy { lodgings[it.home]?.box?.center?.distSqr(from) ?: Double.MAX_VALUE }.take(5)
        return near[c.random.nextInt(near.size)]
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        LuteSilence.refresh(t.capital)
        if (t.step != AT_SCENE) return
        // The concert gets one dusk: tonight's if there is time to play it, else tomorrow's
        val tonight = Days.offsetOf(c.level.overworldClockTime) < Days.clock(TONIGHT)
        t.by = minOf(c.day + (if (tonight) 0 else 1), t.d0 + LATEST)
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        if (e !is Event.Gave || e.tale != t.id || t.step != AT_GIVE || e.marks <= 0) return
        val who = e.player.gameProfile.name
        val part = t.partFor(e.player.uuid)
        val paid = (t.n[LuteSlots.PAID] + e.marks).coerceAtMost(t.n[LuteSlots.SUM])
        t.n[LuteSlots.PAID] = paid
        // A quarter of the sum is a real deed; a second player who pays the other half is paid for it as well
        if (part != null && part.escrow >= QUARTER) part.did = part.did or Part.GAVE
        if (paid >= t.n[LuteSlots.SUM]) {
            // The Marks that cleared the sum decide it (8.6): whoever paid last
            part?.let { it.did = it.did or Part.GAVE or Part.DECIDED }
            t.n[LuteSlots.BACK] = 1
            c.why(t, if (t.n[LuteSlots.WAY] == LuteSlots.B) "lute.rest" else "lute.paid", who)
            LuteSilence.refresh(t.capital)
            c.next(t)
        } else if (t.n[LuteSlots.WAY] == LuteSlots.B && paid >= HALF && t.n[LuteSlots.BACK] == 0) {
            // The first half is the lute lent into her hands again, and the second half has three days
            t.n[LuteSlots.BACK] = 1
            t.by = minOf(c.day + REST_DAYS, t.d0 + LATEST)
            c.why(t, "lute.half", who, e.marks.toString(), REST_DAYS.toString())
            LuteSilence.refresh(t.capital)
        } else {
            c.why(t, "lute.gave", who, e.marks.toString())
        }
        c.data.changed()
    }

    /** The concert, or the hush before the player has taken it up, or at the ending the cheer and the lute's return (the sets of [LuteScenes]). */
    override fun scene(c: Ctx, t: Tale): Stage? {
        if (!Tales.scenes()) return null
        if (t.open) {
            return when {
                t.step == AT_SCENE && t.n[LuteSlots.PLAYED] == 0 -> LuteScenes.concertFor(c, t)
                t.n[LuteSlots.BACK] == 0 -> LuteScenes.hushFor(c, t)
                else -> null
            }
        }
        return when (t.ending) {
            Ending.A, Ending.B -> LuteScenes.cheerFor(c, t)
            Ending.C -> LuteScenes.giveBackFor(c, t)
            else -> null
        }
    }

    /** The talk is [TaleVoice]'s; this template gives no script of its own. */
    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    // ---- The world's way ----

    /**
     * What the town does without a hero. Nobody asked: the keeper's price stands for any who come (step 1). No way chosen by D4: the
     * lute is sold (W). A way chosen and nothing paid by D5, or a B whose second half was not paid in its three days: the lute is sold
     * and what was put in has been given back already (W). The concert not played: the lute is hers, and the ending is the way chosen.
     */
    override fun settle(c: Ctx, t: Tale) {
        when (t.step) {
            AT_ASK -> {
                c.why(t, "lute.unasked")
                c.next(t)
            }
            AT_CHOOSE -> {
                c.why(t, "lute.unchosen")
                c.end(t, Ending.W)
            }
            AT_GIVE -> {
                c.why(t, if (t.n[LuteSlots.PAID] > 0) "lute.sold_half" else "lute.sold")
                t.n[LuteSlots.PAID] = 0
                t.n[LuteSlots.BACK] = 0
                c.end(t, Ending.W)
            }
            else -> c.end(t, endingOf(t))
        }
    }

    /** The ending the way chosen makes of the concert: A, B or C; W if the record has no way. */
    internal fun endingOf(t: Tale): String = when (t.n[LuteSlots.WAY]) {
        LuteSlots.A -> Ending.A
        LuteSlots.B -> Ending.B
        LuteSlots.C -> Ending.C
        else -> Ending.W
    }

    /**
     * The bard is gone (she died, or her household left): no concert, and the tale ends C, as 8.6 has it, with what was put in given back
     * first (an ending that is not W spends it). The keeper is gone and the engine found no one of her trade to stand in: nobody holds the lute.
     */
    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            "bard" -> {
                TalePay.refund(c, t)
                t.n[LuteSlots.PAID] = 0
                c.end(t, Ending.C)
            }
            "keeper" -> {
                TalePay.refund(c, t)
                t.n[LuteSlots.PAID] = 0
                t.n[LuteSlots.BACK] = 0
                c.why(t, "lute.keeper_gone")
                c.end(t, Ending.W)
            }
            else -> {}
        }
    }

    /**
     * The consequences of an ending (8.6): A and B, the lute is hers (A makes her CHEERFUL); C, it goes back to the keeper, and the
     * square is silent again for ten days; W, it is sold, she is SHY, says a glum word if someone is by, and the silence lasts ten days.
     */
    override fun end(c: Ctx, t: Tale, ending: String) {
        when (ending) {
            Ending.A -> {
                t.n[LuteSlots.BACK] = 1
                c.flip(t, "bard", Trait.CHEERFUL)
            }
            Ending.B -> t.n[LuteSlots.BACK] = 1
            Ending.C -> t.n[LuteSlots.BACK] = 0
            else -> {
                t.n[LuteSlots.BACK] = 0
                c.flip(t, "bard", Trait.SHY)
                // A word only from a bard who is awake, to whoever is by (the world's way is usually found at the close, in the small hours)
                TaleBodies.body(c.level, t, "bard")?.takeIf { Tales.scenes() && !it.isSleeping }
                    ?.let { Words.sayWith(it, "tale.lute.glum", why = "the lute is sold") }
            }
        }
        c.data.changed()
        LuteSilence.refresh(t.capital)
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val bard = given(t.cast("bard")?.name.orEmpty())
        val keeper = given(t.cast("keeper")?.name.orEmpty())
        return when (t.step) {
            AT_ASK -> View(TaleKeys.title(id), status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(bard), target = market(t))
            AT_CHOOSE -> View(TaleKeys.title(id), status = TaleKeys.status(id, AT_CHOOSE), statusArgs = listOf(keeper), target = kept(t))
            AT_GIVE -> View(
                TaleKeys.title(id), status = if (t.n[LuteSlots.BACK] == 1) "gui.guildmark.tale.lute.status.rest" else TaleKeys.status(id, AT_GIVE), target = kept(t),
            )
            else -> View(TaleKeys.title(id), status = TaleKeys.status(id, AT_SCENE), statusArgs = listOf(bard), tone = View.Tone.DONE, target = market(t))
        }
    }

    /** A name's first word: the tracker says given names only. */
    internal fun given(name: String): String = name.substringBefore(' ')

    /** The market, where the square is; the capital's heart while its places are not known. */
    private fun market(t: Tale): BlockPos = Spread.view(t.capital).places?.market ?: BlockPos.of(t.capital)

    /** Where the lute is kept: the inn's front door (the tavern nearest the market), else the market, for a merchant. */
    private fun kept(t: Tale): BlockPos {
        val places = Spread.view(t.capital).places ?: return BlockPos.of(t.capital)
        if (t.s[KEPT] != INN) return places.market
        val inn = places.nearest(places.market, CityPlaces.TAVERNS).firstOrNull() ?: return places.market
        return inn.front?.pos ?: inn.box.center
    }

    // ---- The talk ----

    override fun asker(t: Tale): String = "bard"

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val bard = TaleLines.lit(given(turn.name("bard")))
        val keeper = TaleLines.lit(given(turn.name("keeper")))
        return when (beat) {
            "ask" -> TaleLines.facts(if (t.s[KEPT] == INN) "talk.detail.tale.lute.ask" else "talk.detail.tale.lute.ask.stall", bard, keeper)
            "rumour" -> TaleLines.facts("talk.detail.tale.lute.rumour", bard, keeper)
            "stand" -> stand(turn, bard, keeper)
            else -> null
        }
    }

    /** How it stands, from the record: what the keeper wants, the Marks paid, the dusk. */
    private fun stand(turn: TaleTurn, bard: String, keeper: String): String? {
        val t = turn.tale
        val closes = TaleLines.due(turn.left)
        return when (t.step) {
            AT_ASK -> TaleLines.facts("talk.detail.tale.lute.stand.0", bard, keeper, closes)
            AT_CHOOSE -> TaleLines.facts("talk.detail.tale.lute.stand.1", keeper, t.n[LuteSlots.SUM], closes)
            AT_GIVE -> TaleLines.facts("talk.detail.tale.lute.stand.2", t.n[LuteSlots.PAID], t.n[LuteSlots.SUM], closes)
            else -> TaleLines.facts("talk.detail.tale.lute.stand.3", bard, closes)
        }
    }

    /** Before the lute is back in her hands, where it is kept: she points to the inn as she says so. */
    override fun toward(beat: String, turn: TaleTurn): BlockPos? {
        val t = turn.tale
        if (turn.slot != "bard" || beat != "ask" && beat != "stand" || t.step > AT_GIVE || t.n[LuteSlots.BACK] == 1) return null
        return kept(t)
    }

    /** The keeper, to a player who has taken it up and turns to them, says what is owed. */
    override fun greet(turn: TaleTurn): TaleSay? {
        val t = turn.tale
        if (turn.slot != "keeper" || t.step !in AT_CHOOSE..AT_GIVE) return null
        return TaleSay("tale.lute.keeper", TaleLines.facts("talk.detail.tale.lute.keeper", (t.n[LuteSlots.SUM] - t.n[LuteSlots.PAID]).coerceAtLeast(0)))
    }

    override fun options(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        return when (turn.slot) {
            "keeper" -> when (t.step) {
                AT_CHOOSE -> listOf(redeem(), parts(), vouch())
                AT_GIVE -> pay(turn)
                else -> listOf()
            }
            "bard" -> if (t.n[LuteSlots.BACK] == 1) listOf(thanks()) else listOf()
            else -> listOf()
        }
    }

    private fun label(key: String, vararg args: Any): Component = Component.translatable("talk.guildmark.choice.tale.lute.$key", *args)

    private fun think() = TaleReply("ask.think", gesture = Activity.THINK)

    // ---- CHOOSE: the three ways ----

    private fun redeem() = TaleOption("redeem", label("redeem")) { turn ->
        choose(turn, LuteSlots.A, "lute.redeem") {
            TaleSay("tale.lute.keeper", TaleLines.facts("talk.detail.tale.lute.keeper", SUM))
        }
    }

    private fun parts() = TaleOption("parts", label("parts")) { turn ->
        choose(turn, LuteSlots.B, "lute.parts") {
            TaleSay("tale.lute.parts", TaleLines.facts("talk.detail.tale.lute.parts", HALF, SUM - HALF, REST_DAYS))
        }
    }

    /** A way chosen at the keeper: the first word wins, and a second player is told it is decided. */
    private fun choose(turn: TaleTurn, way: Int, why: String, says: () -> TaleSay): TaleReply? {
        val t = turn.tale
        if (t.step != AT_CHOOSE || t.n[LuteSlots.WAY] != LuteSlots.NONE) return null
        val c = turn.ctx() ?: return think()
        t.n[LuteSlots.WAY] = way
        c.why(t, why, turn.who)
        turn.changed()
        c.go(t, AT_GIVE)
        return TaleReply(listOf(says()), Activity.NOD)
    }

    /** "Lend it for one night on my word": the town must know the player (Known or more, 8.6); no Marks, and the concert is tonight's. */
    private fun vouch() = TaleOption("vouch", label("vouch")) { turn ->
        val t = turn.tale
        if (t.step != AT_CHOOSE || t.n[LuteSlots.WAY] != LuteSlots.NONE) return@TaleOption null
        val player = turn.player ?: return@TaleOption null
        val c = turn.ctx()
        val board = TaleOptions.board(turn)
        val part = turn.part()
        if (c == null || board == null || part == null) return@TaleOption think()
        if (Standing.tier(turn.level, board, player).threshold < StandingTier.KNOWN.threshold) {
            return@TaleOption TaleReply(listOf(TaleSay("tale.lute.refused", TaleLines.facts("talk.detail.tale.lute.refused", TaleLines.lit(given(turn.name("keeper")))))), Activity.SHRUG)
        }
        t.n[LuteSlots.WAY] = LuteSlots.C
        t.n[LuteSlots.BACK] = 1
        part.did = part.did or Part.CHOSE or Part.DECIDED
        c.why(t, "lute.vouch", turn.who)
        LuteSilence.refresh(t.capital)
        turn.changed()
        c.go(t, AT_SCENE)
        TaleReply(listOf(TaleSay("tale.lute.vouch", TaleLines.facts("talk.detail.tale.lute.vouch"))), Activity.NOD)
    }

    // ---- GIVE: the Marks ----

    /** What is still wanted to reach the next payment: the whole of it for an A, the half and then the rest for a B. */
    private fun needed(t: Tale): Int {
        val paid = t.n[LuteSlots.PAID]
        val sum = t.n[LuteSlots.SUM]
        return when (t.n[LuteSlots.WAY]) {
            LuteSlots.A -> sum - paid
            LuteSlots.B -> (if (paid < HALF) HALF else sum) - paid
            else -> 0
        }.coerceAtLeast(0)
    }

    private fun pay(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val marks = needed(t)
        if (marks <= 0) return listOf()
        val key = when {
            t.n[LuteSlots.WAY] == LuteSlots.A -> "pay_all"
            t.n[LuteSlots.PAID] == 0 -> "pay_half"
            else -> "pay_rest"
        }
        return listOf(TaleOption("pay$marks", label(key, marks)) { put(it, marks) })
    }

    /** The Marks put in: taken from the pack and held by the engine until the step is met (7.5); `heard` counts them and moves the tale on. */
    private fun put(turn: TaleTurn, marks: Int): TaleReply? {
        val t = turn.tale
        if (t.step != AT_GIVE || needed(t) != marks) return null
        when (TaleOptions.check(turn, marks)) {
            TaleOptions.Put.SHORT -> return TaleReply(listOf(TaleSay("tale.lute.short", TaleLines.facts("talk.detail.tale.lute.short", marks))), Activity.SHRUG)
            TaleOptions.Put.LATER -> return think()
            else -> {}
        }
        if (TaleOptions.put(turn, marks) != TaleOptions.Put.DONE) return think()
        val detail = when {
            t.step != AT_GIVE -> TaleLines.facts("talk.detail.tale.lute.paid_all")
            t.n[LuteSlots.BACK] == 1 -> TaleLines.facts("talk.detail.tale.lute.paid_half", TaleLines.due(turn.left))
            else -> TaleLines.facts("talk.detail.tale.lute.paid", t.n[LuteSlots.PAID], t.n[LuteSlots.SUM])
        }
        return TaleReply(listOf(TaleSay("tale.lute.paid", detail)), Activity.THANK)
    }

    /** The bard, once her lute is in her hands: the player tells her so, and she thanks them. */
    private fun thanks() = TaleOption("thanks", label("thanks")) { turn ->
        if (turn.tale.n[LuteSlots.BACK] != 1) return@TaleOption null
        TaleReply(turn.family("thanks"), gesture = Activity.THANK)
    }
}
