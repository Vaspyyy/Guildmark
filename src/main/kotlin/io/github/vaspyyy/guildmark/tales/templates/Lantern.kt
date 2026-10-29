package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.season.Seasons
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
import io.github.vaspyyy.guildmark.tales.TaleLimits
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.TaleOption
import io.github.vaspyyy.guildmark.tales.TaleReply
import io.github.vaspyyy.guildmark.tales.TaleSay
import io.github.vaspyyy.guildmark.tales.TaleTurn
import io.github.vaspyyy.guildmark.tales.TaleVoice
import io.github.vaspyyy.guildmark.tales.TaleWords
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.Template
import io.github.vaspyyy.guildmark.tales.Verb
import io.github.vaspyyy.guildmark.tales.View
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Resident
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
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.util.Prediction
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A Lantern for Nell (living-world/stories/design.md 8.9, size 1): an old resident sits at their door with a paper lantern, as every
 * year, to let it go with the town's at Lanternrise for someone they lost long ago (the name drawn at the seed, `s[nell]`), and this
 * year their legs will not carry them to the square and they will not ask. The tale is a candle and a slow walk, and the only one
 * that keeps to a festival's clock.
 *
 * The steps: ASK (the old resident at their door, ten to five on the days before the festival and ten to nine on the day: [LanternWalk]),
 * GIVE (one candle of any colour, set in the lantern: [LanternScenes.light]), WATCH (on the day, from noon to half past five, "Shall we
 * walk to the square?", and they set out at their own pace with the lantern alight, resting at the benches: Tales B's `TaleEscort`,
 * led) and SCENE (at the square, waiting for the town's lanterns). Where they are when the lanterns go decides it: A at the square as
 * the town's begin; B there after them (or at seven at a square with no festival); C with the candle given and the walk not made (the
 * lantern goes up from the door); W with nothing done (it stays dark). A and B leave a tradition: a lantern for the same name rises at
 * every later Lanternrise ([LanternFestival]).
 *
 * The old resident is a man (look 3, who speaks in the old man's voice) or a woman (look 7, in the woman's): nothing here or in the
 * language file is worded as either. The record is the truth: the festival's day is kept as an offset from the day the tale began
 * (`n[festival]`), and everything else the tale needs is in the numbers below; the door, the walk and the let-go are scenery rebuilt
 * from them each second.
 *
 * Every step is settled at the close that ends the festival's day ([enter] puts `by` there; the table's `by` are the most the steps may
 * last, as `tale check` reads them), so a candle given on the morning of the day still lights the lantern for the walk. 8.9's table
 * settles the first two at F-1; this is the one difference, and it means W comes at F's close, as the world's way says.
 */
object Lantern : Template, TaleVoice, TaleWords.Names {
    override val id: String = "lantern"
    override val size: Int = 1

    /** The cast: the old resident, essential, and the neighbour who comes to comfort them at the door, standing. */
    const val OLD = "old"
    const val NEIGHBOUR = "neighbour"

    const val AT_ASK = 0
    const val AT_CANDLE = 1
    const val AT_WALK = 2
    const val AT_WATCH = 3

    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 5, hears = setOf(Event.Kind.LANTERNS)),
        Step(Verb.GIVE, by = 5, real = true, hears = setOf(Event.Kind.GAVE, Event.Kind.LANTERNS)),
        Step(Verb.WATCH, by = 6, real = true, hears = setOf(Event.Kind.ARRIVED, Event.Kind.LANTERNS)),
        Step(Verb.SCENE, by = 6, hears = setOf(Event.Kind.ARRIVED, Event.Kind.LANTERNS)),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "released"),
        Ending(Ending.B, "late"),
        Ending(Ending.C, "window"),
        Ending(Ending.W, "dark"),
    )

    /**
     * `n[festival]` the festival's market day less the day the tale began; `n[candle]` 1 once the candle is in the lantern; `n[walk]` 0
     * not set out, 1 on the way, 2 at the square; `n[begun]` 1 once the town's lanterns have begun; `n[letgo]` 0 not let go, 1 being let
     * go with the town's, 2 let go; `n[route]` the ending decided and waiting for its scene (0 none, 1 A, 2 B, 3 C, 4 W); `n[made]` 1 if a
     * forced seed made the festival's day up (the town holds no festival on it).
     */
    override val numbers: List<String> = listOf("festival", "candle", "walk", "begun", "letgo", "route", "made")

    /**
     * `s[nell]` the one the lantern is for; `s[walker]` the player the old resident set out with (a uuid); `s[door]` the block before
     * their door, and `s[square]` their place at the square once they have set out (blocks as longs).
     */
    override val strings: List<String> = listOf("nell", "walker", "door", "square")

    const val N_FESTIVAL = 0
    const val N_CANDLE = 1
    const val N_WALK = 2
    const val N_BEGUN = 3
    const val N_LETGO = 4
    const val N_ROUTE = 5
    const val N_MADE = 6

    const val S_NELL = 0
    const val S_WALKER = 1
    const val S_DOOR = 2
    const val S_SQUARE = 3

    const val ROUTE_A = 1
    const val ROUTE_B = 2
    const val ROUTE_C = 3
    const val ROUTE_W = 4

    /** The neighbour is any adult of the homes next door: one who dies or goes is stood in for, or the doorstep is played without them. */
    override val standing: Set<String> = setOf(NEIGHBOUR)

    /** The tale is tied to a date, so its days do not move on with the clock when tales come back on (11.5). */
    override val thaws: Boolean = false

    /** How many days before the festival it may be seeded (2 to 6 market days), and how many a forced one makes up. */
    private const val LEAST = 2
    private const val MOST = 6
    private const val MADE_UP = 3

    /** The days an old resident has lived in the town before they are one to ask (a forced seed lets that go). */
    private const val SETTLED = 20L

    /** The two looks of a citizen that are old: the old man (3) and the woman with the hair bun (7). */
    const val LOOK_MAN = 3
    const val LOOK_WOMAN = 7
    private val OLD_LOOKS = setOf(LOOK_MAN, LOOK_WOMAN)

    /** The festivals keep to the calendar, and the walk and the let-go are scenes. */
    override fun needs(): Boolean = Seasons.festivalsEnabled() && Tales.scenes()

    // ---- The seed ----

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        if (!Seasons.festivalsEnabled()) return c.none("the festivals are switched off (setting: Festivals)")
        val today = VillageBoards.day(level)
        val (kind, ahead) = FestivalCalendar.next(Seasons.today(level))
        val lanternrise = kind == FestivalKind.LANTERNRISE
        var offset = (today + ahead - c.day).toInt()
        var made = ""
        val fits = lanternrise && if (c.forced) offset in 0..MOST else offset in LEAST..MOST
        if (!fits) {
            val said = if (lanternrise) "Lanternrise is $ahead days off" else "the next festival is ${kind.id}, $ahead days off"
            if (!c.forced) return c.none("$said, and the tale begins $LEAST to $MOST market days before Lanternrise ('/guildmark season set winter 1' brings one)")
            offset = MADE_UP
            made = "a Lanternrise $MADE_UP days off ($said): the town holds no festival on it, so the old resident's lantern goes up beside the fountain at seven; " +
                "'/guildmark season set winter 1' brings a real one, or '/guildmark festival start lanternrise' holds one now"
        }
        val data = TaleData.get(level)
        val pool = TaleCast.pool(level, c.here.capital.center).filter { it.role == TownRole.CITIZEN && it.look in OLD_LOOKS && doorOf(c, it) != null }
        val settled = pool.filter { today - it.since >= SETTLED }.ifEmpty { if (c.forced) pool else listOf() }
        if (settled.isEmpty()) return c.none("no old resident is free to ask (a citizen of look $LOOK_MAN or $LOOK_WOMAN with a front door, in the town $SETTLED days or more)")
        // The old man if there is one, else the woman with the hair bun
        val old = settled.filter { it.look == LOOK_MAN }.ifEmpty { settled }.let { it[c.random.nextInt(it.size)] }
        val door = doorOf(c, old) ?: return c.none("the old resident's home has no front door")
        val neighbour = neighbourOf(c, old, data)
        return Planned(
            cast = listOfNotNull(Cast(OLD, old.id, old.name), neighbour?.let { Cast(NEIGHBOUR, it.id, it.name) }),
            why = if (offset <= 1) Why("lantern.start_soon", listOf(old.name)) else Why("lantern.start", listOf(old.name, offset.toString())),
            step = AT_ASK,
            n = listOf(offset, 0, 0, 0, 0, 0, if (made.isEmpty()) 0 else 1),
            s = listOf(nellFor(c, old), "", door.asLong().toString(), ""),
            madeUp = made,
        )
    }

    /** The block outside [r]'s front door, where they sit, or null if their home has none. */
    private fun doorOf(c: Seeding, r: Resident): BlockPos? = c.here.places.lodgings[r.home]?.front?.outside

    /** The nearest adult of the homes next door who may be cast, or null. */
    private fun neighbourOf(c: Seeding, old: Resident, data: TaleData): Resident? {
        val lodgings = c.here.places.lodgings
        val at = lodgings[old.home]?.box?.center ?: return null
        return Witness.neighbours(c.level, old)
            .filter { it.id != old.id && TaleCast.free(it, data) }
            .minByOrNull { lodgings[it.home]?.box?.center?.distSqr(at) ?: Double.MAX_VALUE }
    }

    /** The one the lantern is for, a given name of the town's way, in the other voice from the old resident's own. */
    private fun nellFor(c: Seeding, old: Resident): String {
        val style = c.here.capital.style
        val woman = old.look == LOOK_MAN
        var name = ""
        for (i in 0 until 6) {
            name = Names.given(style, woman, c.random.nextLong())
            if (name.isNotBlank() && name != old.given) break
        }
        return name.take(TaleLimits.STRING_LETTERS)
    }

    // ---- The steps ----

    /** Each step is settled at the close that ends the festival's day (and tonight's, if that is past already). */
    override fun enter(c: Ctx, t: Tale) {
        t.by = minOf(maxOf(LanternClock.festival(t), c.day), t.d0 + TaleLimits.TALE_DAYS)
        c.data.changed()
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        when (e) {
            is Event.Gave -> candle(c, t, e)
            is Event.Arrived -> if (e.slot == OLD) LanternWalk.arrived(c, t)
            is Event.Lanterns -> LanternWalk.lanterns(c, t, e)
            else -> {}
        }
    }

    /** A candle of any colour, set in the lantern: the step is met, and the walk is next. */
    private fun candle(c: Ctx, t: Tale, e: Event.Gave) {
        if (t.step != AT_CANDLE || e.items.none { it.`is`(ItemTags.CANDLES) }) return
        t.partFor(e.player.uuid)?.let { it.did = it.did or Part.GAVE }
        t.n[N_CANDLE] = 1
        c.why(t, "lantern.candle", e.player.gameProfile.name)
        c.next(t)
    }

    /**
     * The scene of the step (asked every forty seconds while a player is within 48 blocks of the old resident): the doorstep (S1) in its
     * hours, and from seven on the day at the door the lantern going up from there (the candle given and the walk not made: C) or kept
     * dark (nothing given: W, which the close then makes). Once ended, the ending's scene if its lantern has not been seen to go: the
     * let-go for A and B (as `tale end` makes them), the window for C, the dark lantern for W.
     */
    override fun scene(c: Ctx, t: Tale): Stage? {
        if (!Tales.scenes()) return null
        val level = c.level
        val old = TaleBodies.body(level, t, OLD) ?: return null
        val near = level.getNearestPlayer(old, TaleLook.NEAR)
        if (!t.open) {
            if (t.n[N_LETGO] == 2) return null
            return when (t.ending) {
                Ending.A, Ending.B -> LanternScenes.letGo(old, near)
                Ending.C -> LanternScenes.letGo(old, near, "tale.lantern.window")
                else -> LanternScenes.dark(old, near)
            }
        }
        if (t.n[N_WALK] != 0 || t.n[N_ROUTE] != 0 || t.cast(OLD)?.held != true) return null
        val door = LanternClock.door(t) ?: return null
        if (old.distanceToSqr(Vec3.atBottomCenterOf(door)) > 4.0 * 4.0) return null
        if (LanternClock.dusk(level, t)) {
            if (t.n[N_CANDLE] == 0) return LanternScenes.dark(old, near)
            return window(c, t, old, near)
        }
        if (!Stage.within(level, LanternScenes.DOOR_HOURS)) return null
        val passer = TaleBodies.body(level, t, NEIGHBOUR)?.takeIf { it !== old && it.distanceToSqr(old) <= 32.0 * 32.0 } ?: Sets.passerBy(old)
        return LanternScenes.door(old, passer, LanternWalk.centre(c, t), lit = t.n[N_CANDLE] != 0)
    }

    /** The candle is in it and the walk was not made: at seven it goes up from the door, and that is the ending (C), made as the scene ends. */
    private fun window(c: Ctx, t: Tale, old: io.github.vaspyyy.guildmark.townsfolk.Townsperson, near: net.minecraft.world.entity.player.Player?): Stage {
        t.n[N_ROUTE] = ROUTE_C
        c.data.changed()
        val stage = LanternScenes.letGo(old, near, "tale.lantern.window")
        val level = c.level
        val capital = t.capital
        val id = t.id
        stage.then = { _ ->
            val tale = TaleData.get(level)[id]
            val cc = TaleEngine.ctx(level, capital)
            if (tale != null && tale.open && cc != null) {
                tale.n[N_LETGO] = 2
                cc.end(tale, Ending.C)
            }
        }
        return stage
    }

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    /**
     * The world's way, at the close that ends the festival's day (or a test's `tale step`): the record is complete. An ending decided and
     * waiting for its scene is that ending; at the square, B (they let it go there, whether anyone saw); the candle in the lantern and
     * the walk not made, C (it went up from the door); nothing done, W (it stayed dark).
     */
    override fun settle(c: Ctx, t: Tale) {
        val route = t.n[N_ROUTE]
        val ending = when {
            route != 0 -> LanternWalk.ending(route)
            t.n[N_WALK] == 2 -> Ending.B
            t.n[N_CANDLE] != 0 -> Ending.C
            else -> Ending.W
        }
        if (route == 0) {
            when (ending) {
                Ending.C -> c.why(t, if (t.n[N_WALK] == 1) "lantern.began" else "lantern.window")
                Ending.W -> c.why(t, "lantern.dark")
                else -> {}
            }
        }
        c.end(t, ending)
    }

    /** The old resident is essential: if they die or go the tale ends W with no tradition (the engine has said why). The neighbour is only missed. */
    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        if (slot == OLD) c.end(t, Ending.W)
    }

    /**
     * The consequences of an ending (6): A and B leave the tradition, a lantern for the same name at every later Lanternrise; A also makes
     * the old resident one for visits (CHATTY); the town's thanks for A and B are the engine's. C is thanked by the old resident alone. The
     * deciding deed is the walk (A, B: the one they set out with, else the first who walked) or the candle (C).
     */
    override fun end(c: Ctx, t: Tale, ending: String) {
        when (ending) {
            Ending.A, Ending.B -> {
                val walker = LanternWalk.walker(t)
                val decider = t.parts.firstOrNull { it.player == walker && it.did(Part.WALKED) } ?: t.parts.firstOrNull { it.did(Part.WALKED) }
                decider?.let { it.did = it.did or Part.DECIDED }
                LanternFestival.add(c.level, t.capital, t.s[S_NELL])
                if (ending == Ending.A) c.flip(t, OLD, Trait.CHATTY)
            }
            Ending.C -> {
                t.parts.firstOrNull { it.did(Part.GAVE) }?.let {
                    it.did = it.did or Part.DECIDED
                    c.favour(t, OLD, it.player, Favour.TALE_HELPED)
                }
            }
            else -> {}
        }
        // A walk still on the way is over (the engine lets the hold go after this)
        TaleBodies.body(c.level, t, OLD)?.let { (it.director as? TaleEscort)?.cancel(it) }
        LanternWalk.ended(c.level, t)
        c.data.changed()
    }

    override fun view(t: Tale, player: UUID): View = View(
        title = TaleKeys.title(id),
        status = TaleKeys.status(id, t.step),
        statusArgs = if (t.step == AT_ASK || t.step == AT_WALK) listOf(given(t)) else listOf(),
        // The door they sit at until they set out, then their place at the square
        target = if (t.n[N_WALK] == 0) LanternClock.door(t) ?: BlockPos.of(t.capital) else LanternWalk.square(t) ?: BlockPos.of(t.capital),
    )

    override fun words(t: Tale): List<String> = listOf(t.cast(OLD)?.name.orEmpty(), t.s[S_NELL])

    /** The old resident's given name: what the tracker's row says ("Talk to Aldous"). */
    fun given(t: Tale): String = t.cast(OLD)?.name?.substringBefore(' ').orEmpty()

    // ---- The talk ----

    override fun asker(t: Tale): String = OLD

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val due = TaleLines.due(LanternClock.daysTo(turn.level, t))
        return when (beat) {
            "ask" -> TaleLines.facts("talk.detail.tale.lantern.ask", TaleLines.lit(t.s[S_NELL]), due)
            "rumour" -> TaleLines.facts("talk.detail.tale.lantern.rumour", TaleLines.lit(t.cast(OLD)?.name.orEmpty()), TaleLines.lit(t.s[S_NELL]))
            "stand" -> stand(turn, due)
            else -> null
        }
    }

    /** How it stands: the step's own words, and for the walk the hours it can set out in. */
    private fun stand(turn: TaleTurn, due: String): String? {
        val t = turn.tale
        return when (t.step) {
            AT_ASK -> TaleLines.facts("talk.detail.tale.lantern.stand.0", due)
            AT_CANDLE -> TaleLines.facts("talk.detail.tale.lantern.stand.1", due)
            AT_WALK -> if (t.n[N_WALK] != 0) TaleLines.facts("talk.detail.tale.lantern.stand.walking") else walkWords(turn, due)
            else -> TaleLines.facts("talk.detail.tale.lantern.stand.3")
        }
    }

    /** When the walk can set out, in grey: on another day, later today, now, or no longer. */
    private fun walkWords(turn: TaleTurn, due: String): String? = when (LanternClock.walk(turn.level, turn.tale)) {
        LanternClock.Walk.OPEN -> TaleLines.facts("talk.detail.tale.lantern.stand.2.today", TaleLines.lit(LanternClock.text(LanternClock.LATEST)))
        LanternClock.Walk.LATE -> TaleLines.facts("talk.detail.tale.lantern.stand.2.late")
        LanternClock.Walk.EARLY -> TaleLines.facts("talk.detail.tale.lantern.stand.2.noon", TaleLines.lit(LanternClock.text(LanternClock.from(turn.tale))))
        LanternClock.Walk.NOT_TODAY -> TaleLines.facts("talk.detail.tale.lantern.stand.2", due)
    }

    /** The old resident sighs as they ask. */
    override fun gesture(beat: String, turn: TaleTurn): Activity? = if (beat == "ask" && turn.slot == OLD) Activity.SIGH else null

    /** The lantern is for the square: they look that way as they say they cannot get there, and as they tell how the walk stands. */
    override fun toward(beat: String, turn: TaleTurn): BlockPos? {
        if (turn.slot != OLD) return null
        return if (beat == "passing" || beat == "stand" && turn.step >= AT_WALK) BlockPos.of(turn.tale.capital) else null
    }

    /** What the old resident offers one who is in the tale: the candle (when they carry one) at the second step, the walk at the third. */
    override fun options(turn: TaleTurn): List<TaleOption> {
        if (turn.slot != OLD) return listOf()
        val status = turn.mine?.status
        if (status != Part.Status.IN && status != Part.Status.DONE) return listOf()
        return when (turn.step) {
            AT_CANDLE -> if (turn.player?.let { candleIn(it) } != null) listOf(candleOption()) else listOf()
            AT_WALK -> walkOptions(turn)
            else -> listOf()
        }
    }

    /** The slot of the first candle (of any colour) in [player]'s pack, or null. */
    private fun candleIn(player: ServerPlayer): Int? {
        val inventory = player.inventory
        for (i in 0 until inventory.containerSize) if (inventory.getItem(i).`is`(ItemTags.CANDLES)) return i
        return null
    }

    /** "Here, a candle for it.": one candle from the pack, taken in their hands and set in the lantern; the lighting plays as the talk ends. */
    private fun candleOption() = TaleOption("candle", Component.translatable("talk.guildmark.choice.tale.lantern.candle")) { turn ->
        val t = turn.tale
        val player = turn.player ?: return@TaleOption null
        if (t.step != AT_CANDLE) return@TaleOption null
        if (turn.ctx() == null) return@TaleOption think()
        val slot = candleIn(player) ?: return@TaleOption think()
        val given = player.inventory.getItem(slot).split(1)
        Tales.tell(turn.level, Event.Gave(t.id, player, 0, listOf(given)))
        if (t.step == AT_CANDLE) {
            // The tale could not take it: it goes back in the pack
            player.inventory.placeItemBackInInventory(given, Prediction.SERVER_ONLY)
            return@TaleOption think()
        }
        val old = turn.ask.speaker
        // The candle is in their hands as they take it (the clip draws what is carried), then the lantern is lit
        old.carried = given.copy()
        old.carriedUntil = old.tickCount + Activity.RECEIVE.ticks
        if (Tales.scenes()) TaleLook.queue(turn.level, t.capital, LanternScenes.light(old, player), urgent = true)
        val then = when (LanternClock.walk(turn.level, t)) {
            LanternClock.Walk.OPEN -> TaleLines.facts("talk.detail.tale.lantern.stand.2.today", TaleLines.lit(LanternClock.text(LanternClock.LATEST)))
            LanternClock.Walk.LATE -> null
            LanternClock.Walk.EARLY -> TaleLines.facts("talk.detail.tale.lantern.stand.2.noon", TaleLines.lit(LanternClock.text(LanternClock.from(t))))
            LanternClock.Walk.NOT_TODAY -> TaleLines.facts("talk.detail.tale.lantern.soon")
        }
        TaleReply(listOf(TaleSay(turn.family("candle"), then)), Activity.RECEIVE, ends = true)
    }

    /** "Shall we walk to the square?" in the hours it can set out in (and after them, when it is too late), or to take a broken walk up again. */
    private fun walkOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        if (!Tales.scenes() || t.n[N_ROUTE] != 0) return listOf()
        return when (t.n[N_WALK]) {
            0 -> when (LanternClock.walk(turn.level, t)) {
                LanternClock.Walk.OPEN, LanternClock.Walk.LATE -> listOf(walkOption())
                else -> listOf()
            }
            1 -> if (LanternWalk.lost(turn.level, t)) listOf(walkOption()) else listOf()
            else -> listOf()
        }
    }

    private fun walkOption() = TaleOption("walk", Component.translatable("talk.guildmark.choice.tale.lantern.walk")) { turn ->
        val t = turn.tale
        val player = turn.player ?: return@TaleOption null
        if (t.step != AT_WALK || t.n[N_ROUTE] != 0) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        if (t.n[N_WALK] == 1) {
            // On again from here, with the one who asks: the walk is taken up as the talk ends
            if (!LanternWalk.resume(c, t, player, turn.ask.speaker)) return@TaleOption think()
            return@TaleOption TaleReply(turn.family("shall"), gesture = Activity.NOD, ends = true)
        }
        when (LanternClock.walk(turn.level, t)) {
            LanternClock.Walk.LATE -> return@TaleOption TaleReply(turn.family("late"), gesture = Activity.SIGH, back = true)
            LanternClock.Walk.OPEN -> {}
            else -> return@TaleOption null
        }
        if (!LanternWalk.setOut(c, t, player, turn.ask.speaker)) return@TaleOption think()
        TaleReply(turn.family("shall"), gesture = Activity.NOD, ends = true)
    }

    private fun think() = TaleReply("ask.think", gesture = Activity.THINK, back = true)
}
