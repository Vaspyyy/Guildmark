package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.tales.Cast
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.FenceStages
import io.github.vaspyyy.guildmark.tales.Gone
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Planned
import io.github.vaspyyy.guildmark.tales.Seeding
import io.github.vaspyyy.guildmark.tales.Step
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCast
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.TaleLimits
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleOption
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
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import java.util.UUID

/**
 * The Fence Between (living-world/stories/design.md 8.5, size 1), Tales T1a: two neighbours who have shared a lane for years have stopped
 * speaking, over a small thing that grew in the lean days, and each is too proud to go first. The tale is a mending: carry a word each way, find
 * that each thinks the other began it, and put bread between them. It is the smallest tale and the most direct kind of kindness; it ends with two
 * people at the well at dusk.
 *
 * **Steps.** 0 ASK (S3, the Quarrel in the lane at noon; yes puts the player in; by D2: if none comes, W), 1 CHOOSE (hear each side, and bring
 * each to the well at dusk or sting it; by D4), 2 GIVE (two loaves into `a`'s hands; by D5), 3 SCENE (the Mending, the nod or the wait, at dusk;
 * by D6, and the world's way ends it at the close after).
 * **Numbers.** `n[0]` the thing they quarrel about (0 to 6, [FenceStages.THINGS]), `n[1]` side a and `n[2]` side b (0 not yet won, 1 agreed to come
 * to the well, 2 stung), `n[3]` the loaves given (0 to 2), `n[4]` the sides whose account the player has heard (bit 1 a, bit 2 b), `n[5]` the ending
 * the tale is going to (1 to 4 for A to W), `n[6]` the one who has gone (1 a, 2 b). **Strings.** `s[0]` a's door and `s[1]` b's (block positions),
 * `s[2]` the well, or the middle of the lane if the town has none near, `s[3]` the player whose deed counted last (the deciding deed, 7.3).
 *
 * **The endings.** A (mended: both agreed by D4 and the loaves given by D5), B (slow: both agreed, no bread), C (hushed: one side agreed), W (fenced:
 * none did, or nobody took it up). The ending the tale is going to is fixed as the steps go (`n[5]`) and shown at step 3 by the scene
 * ([FenceStages]), after which the scene ends the tale; with nobody to see it, the world's way ends it at the next close. [end] does the rest:
 * the flips, the volunteers of the works for ten market days, the favour of the third.
 */
object Fence : Template, TaleVoice {
    override val id: String = "fence"
    override val size: Int = 1

    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2, hears = setOf(Event.Kind.DAY)),
        Step(Verb.CHOOSE, by = 4, real = true, hears = setOf(Event.Kind.DAY)),
        Step(Verb.GIVE, by = 5, real = true, hears = setOf(Event.Kind.DAY)),
        Step(Verb.SCENE, by = 6, hears = setOf(Event.Kind.DAY), needs = { Tales.scenes() }),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "mended"),
        Ending(Ending.B, "slow"),
        Ending(Ending.C, "hushed"),
        Ending(Ending.W, "fenced"),
    )

    /** The third is whichever neighbour hushes them: a stand-in of the same trade takes their place. */
    override val standing: Set<String> = setOf("third")

    override val numbers: List<String> = listOf("thing", "sideA", "sideB", "loaves", "told", "ending", "gone")
    override val strings: List<String> = listOf("doorA", "doorB", "site", "decider")

    override fun needs(): Boolean = Fortunes.enabled()

    // ---- The numbers ----

    private const val THING = 0
    private const val SIDE_A = 1
    private const val SIDE_B = 2
    private const val LOAVES = 3
    private const val TOLD = 4
    private const val ENDING = 5
    private const val GONE = 6
    private const val DOOR_A = 0
    private const val DOOR_B = 1
    private const val SITE = 2
    private const val DECIDER = 3

    /** A side not yet won, agreed to come to the well, or stung. */
    private const val OPEN = 0
    private const val AGREED = 1
    private const val STUNG = 2

    /** The loaves the well wants, and the market days the works take the mended first (the day of the ending and nine more). */
    private const val BREAD = 2
    private const val VOLUNTEER_DAYS = 9L

    /** How far apart two homes' front doors may be (blocks), how far from the pair the third lives, and how far the pair's well may be. */
    private const val DOORS = 14.0
    private const val THIRD_REACH = 24.0
    private const val WELL_REACH = 40.0

    /** Who may be neighbours: the folk of the lanes. */
    private val LANE: Set<TownRole> = setOf(TownRole.CITIZEN, TownRole.FARMER, TownRole.FISHER)

    private const val AT_ASK = 0
    private const val AT_CHOOSE = 1
    private const val AT_GIVE = 2
    private const val AT_SCENE = 3

    /** The loaves the tale takes: the tag `guildmark:tale_loaf` (bread, and the loaf of the cook's dishes). */
    private val LOAF: TagKey<Item> = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("guildmark", "tale_loaf"))

    /** The ending scenes this build has already shown at step 3 (memory only): the ending's own scene is not played again for them. */
    private val shown = HashSet<Int>()

    private fun first(name: String): String = name.substringBefore(' ')

    private fun letter(code: Int): String = Ending.LETTERS.getOrElse(code - 1) { Ending.W }

    private fun code(letter: String): Int = Ending.LETTERS.indexOf(letter) + 1

    /** The ending the tale is going to: fixed already, else the slowest of the good ones. */
    private fun planned(t: Tale): String = if (t.n[ENDING] in 1..4) letter(t.n[ENDING]) else Ending.B

    private fun side(t: Tale, slot: String): Int = t.n[if (slot == "a") SIDE_A else SIDE_B]

    private fun setSide(t: Tale, slot: String, to: Int) {
        t.n[if (slot == "a") SIDE_A else SIDE_B] = to
    }

    private fun other(slot: String): String = if (slot == "a") "b" else "a"

    private fun bit(slot: String): Int = if (slot == "a") 1 else 2

    private fun door(t: Tale, slot: String): BlockPos? = t.s[if (slot == "a") DOOR_A else DOOR_B].toLongOrNull()?.let { BlockPos.of(it) }

    private fun site(t: Tale): BlockPos? = t.s[SITE].toLongOrNull()?.let { BlockPos.of(it) }

    private fun flat(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return Math.sqrt(dx * dx + dz * dz)
    }

    // ---- Seeding ----

    private class Neighbour(val id: Int, val name: String, val home: Int, val door: BlockPos)

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        var madeUp = ""
        val times = Fortunes.times(level, c.capital)
        if (times != Times.LEAN && times != Times.HARD) {
            if (!c.forced) return c.none("the times are ${times.id}, and the fence wants them lean or hard")
            madeUp = "the lean times (they are ${times.id})"
        }
        val places = c.here.places
        val pool = TaleCast.pool(level, c.here.capital.center)
        val lane = pool.filter { it.role in LANE }.mapNotNull { r ->
            places.lodgings[r.home]?.front?.pos?.let { Neighbour(r.id, r.name, r.home, it) }
        }
        val pairs = ArrayList<Pair<Neighbour, Neighbour>>()
        for (i in lane.indices) for (j in i + 1 until lane.size) {
            val x = lane[i]
            val y = lane[j]
            if (x.home != y.home && flat(x.door, y.door) <= DOORS) pairs += x to y
        }
        if (pairs.isEmpty()) return c.none("no two citizens, farmers or fishers live within ${DOORS.toInt()} blocks of each other, door to door")
        val (p, q) = pairs[c.random.nextInt(pairs.size)]
        val a = if (c.random.nextBoolean()) p else q
        val b = if (a === p) q else p
        val middle = BlockPos((a.door.x + b.door.x) / 2, (a.door.y + b.door.y) / 2, (a.door.z + b.door.z) / 2)
        // The third: the nearest neighbour of the pair, of any lane trade, who is not one of them
        val third = pool.filter { it.id != a.id && it.id != b.id && it.role in LANE }.mapNotNull { r ->
            places.lodgings[r.home]?.front?.pos?.takeIf { flat(it, middle) <= THIRD_REACH }?.let { Neighbour(r.id, r.name, r.home, it) }
        }.minWithOrNull(compareBy<Neighbour> { flat(it.door, middle) }.thenBy { it.id })
        val well = places.wells.minByOrNull { it.distSqr(middle) }?.takeIf { flat(it, middle) <= WELL_REACH }
        val thing = c.random.nextInt(FenceStages.THINGS)
        return Planned(
            cast = listOfNotNull(Cast("a", a.id, a.name), Cast("b", b.id, b.name), third?.let { Cast("third", it.id, it.name) }),
            why = Why("fence.lean"),
            step = AT_ASK,
            n = listOf(thing, 0, 0, 0, 0, 0, 0),
            s = listOf(a.door.asLong().toString(), b.door.asLong().toString(), (well ?: middle).asLong().toString(), ""),
            madeUp = madeUp,
        )
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        when (t.step) {
            // Bread or none, they meet at the well at dusk: if the day's dusk is yet to come the scene is today's, else tomorrow's. The town's
            // day starts at two in the morning and the next close is then, so from two until seven in the evening the world's way (the next
            // close) waits for today's dusk; after seven, and until two, it waits for tomorrow's
            AT_SCENE -> t.by = c.day + if (Stage.within(c.level, 2.0..19.0)) 0 else 1
            AT_GIVE -> if (t.n[ENDING] !in 1..4) t.n[ENDING] = code(Ending.B)
        }
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {}

    /** The world's way (1.4): nobody took it up, a side or none agreed, the loaves are counted. */
    override fun settle(c: Ctx, t: Tale) {
        when (t.step) {
            AT_ASK -> {
                c.why(t, "fence.nobody")
                c.end(t, Ending.W)
            }
            AT_CHOOSE -> {
                val agreed = listOf(SIDE_A, SIDE_B).count { t.n[it] == AGREED }
                when {
                    agreed >= 2 -> c.go(t, AT_GIVE)
                    agreed == 1 -> {
                        c.why(t, "fence.one", first(t.cast(if (t.n[SIDE_A] == AGREED) "a" else "b")?.name.orEmpty()))
                        toScene(c, t, Ending.C)
                    }
                    else -> {
                        c.why(t, "fence.none")
                        c.end(t, Ending.W)
                    }
                }
            }
            AT_GIVE -> {
                c.why(t, "fence.no_bread")
                toScene(c, t, Ending.B)
            }
            else -> c.end(t, planned(t))
        }
    }

    /** Fixes the ending the dusk is going to show, and goes to it. */
    private fun toScene(c: Ctx, t: Tale, letter: String) {
        t.n[ENDING] = code(letter)
        c.data.changed()
        c.go(t, AT_SCENE)
    }

    /** A side has been won or stung: both won is the bread, one and one is the wait at the well, both stung is the end. */
    private fun sidesChanged(c: Ctx, t: Tale) {
        if (!t.open || t.step != AT_CHOOSE) return
        val a = t.n[SIDE_A]
        val b = t.n[SIDE_B]
        when {
            a == AGREED && b == AGREED -> c.go(t, AT_GIVE)
            a != OPEN && b != OPEN && (a == AGREED || b == AGREED) -> toScene(c, t, Ending.C)
            a == STUNG && b == STUNG -> {
                c.why(t, "fence.none")
                c.end(t, Ending.W)
            }
        }
    }

    // ---- The scenes ----

    private fun folk(c: Ctx, t: Tale): FenceStages.Folk? {
        val a = TaleBodies.body(c.level, t, "a") ?: return null
        val b = TaleBodies.body(c.level, t, "b") ?: return null
        val at = site(t) ?: return null
        return FenceStages.Folk(c.level, a, b, TaleBodies.body(c.level, t, "third"), at, t.n[THING])
    }

    override fun scene(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (!t.open) {
            // The ending seen, once, when no scene of step 3 showed it already
            if (t.id in shown || !Stage.within(level, FenceStages.DUSK)) return null
            return folk(c, t)?.let { ending(it, t, t.ending) }
        }
        return when (t.step) {
            AT_ASK -> if (Stage.within(level, FenceStages.NOON)) folk(c, t)?.let { FenceStages.quarrel(it) } else null
            AT_SCENE -> dusk(c, t)
            else -> null
        }
    }

    /** The dusk of step 3: the ending the tale is going to, and when it has played the tale ends. */
    private fun dusk(c: Ctx, t: Tale): Stage? {
        val level = c.level
        if (!Stage.within(level, FenceStages.DUSK)) return null
        val folk = folk(c, t) ?: return null
        val letter = planned(t)
        val stage = ending(folk, t, letter) ?: return null
        val capital = t.capital
        stage.then = { played ->
            if (played) {
                TaleEngine.withCtx(level, capital, "the end of the fence tale #${t.id}") { later ->
                    if (t.open && t.step == AT_SCENE) {
                        shown += t.id
                        later.end(t, letter)
                    }
                }
            }
        }
        return stage
    }

    /** The scene of the ending [letter] for [folk]: the Mending (A), the nod (B), the wait (C), none (W). */
    private fun ending(folk: FenceStages.Folk, t: Tale, letter: String): Stage? = when (letter) {
        Ending.A -> FenceStages.mending(folk, bread = true)
        Ending.B -> FenceStages.mending(folk, bread = false)
        Ending.C -> if (t.n[SIDE_A] == AGREED) FenceStages.waited(folk, folk.a, folk.b) else FenceStages.waited(folk, folk.b, folk.a)
        else -> null
    }

    // ---- The consequences of an ending ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        // The deciding deed (7.3): the loaves for A, the second agreement for B, the one agreement for C
        if (ending != Ending.W) surety(t)?.let { id -> t.part(id)?.let { it.did = it.did or Part.DECIDED } }
        when (ending) {
            Ending.A -> {
                c.flip(t, "a", Trait.CHEERFUL)
                c.flip(t, "b", Trait.CHATTY)
                volunteers(c, t)
                // The third's thanks, as the two have (the engine's own go to the two)
                for (part in t.parts) if (part.did != 0) c.favour(t, "third", part.player, Favour.TALE_HELPED)
            }
            Ending.B -> c.flip(t, "a", Trait.CHEERFUL)
            Ending.W -> {
                c.flip(t, "a", Trait.GRUMPY)
                c.flip(t, "b", Trait.GRUMPY)
            }
        }
    }

    private fun surety(t: Tale): UUID? = try {
        UUID.fromString(t.s[DECIDER])
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The two are the works' first hands for ten market days (6.3): `Tales.volunteers` is what `WorksBuild.labourers` reads. */
    private fun volunteers(c: Ctx, t: Tale) {
        val town = c.data.town(c.capital)
        val until = c.day + VOLUNTEER_DAYS
        for (slot in listOf("a", "b")) {
            val id = t.resident(slot)
            if (id < 0) continue
            town.volunteers.removeAll { it.resident == id }
            town.volunteers.add(TaleData.Volunteer(id, until))
        }
        while (town.volunteers.size > TaleLimits.VOLUNTEERS) town.volunteers.removeAt(0)
        c.data.changed()
    }

    // ---- Those who go ----

    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        if (slot != "a" && slot != "b") return
        // Either of the two leaving or dying ends the tale at C; the one who is left speaks of it
        t.n[GONE] = if (slot == "a") 1 else 2
        c.data.changed()
        val left = other(slot)
        c.say(t, left, "tale.fence.glum")
        c.end(t, Ending.C)
    }

    // ---- The talk ----

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    override fun asker(t: Tale): String = "a"

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val a = TaleLines.lit(turn.name("a"))
        val b = TaleLines.lit(turn.name("b"))
        return when (beat) {
            "ask" -> TaleLines.facts("talk.detail.tale.fence.ask", a, b, FenceStages.thingKey(t.n[THING]))
            "rumour" -> TaleLines.facts("talk.detail.tale.fence.rumour", a, b)
            "stand" -> when (t.step) {
                AT_ASK, AT_CHOOSE -> {
                    val age = (turn.today - t.d0).coerceAtLeast(0L)
                    when (age) {
                        0L -> TaleLines.facts("talk.detail.tale.fence.stand.0", a, b)
                        1L -> TaleLines.facts("talk.detail.tale.fence.stand.1", a, b)
                        else -> TaleLines.facts("talk.detail.tale.fence.stand.n", a, b, age.toInt())
                    }
                }
                AT_GIVE -> TaleLines.facts("talk.detail.tale.fence.stand.give", a, b, (BREAD - t.n[LOAVES]).coerceAtLeast(0))
                else -> TaleLines.facts("talk.detail.tale.fence.stand.dusk", a, b)
            }
            else -> null
        }
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = if (beat == "ask") Activity.SIGH else null

    override fun toward(beat: String, turn: TaleTurn): BlockPos? = if (beat == "ask" && turn.slot == "a") door(turn.tale, "b") else null

    override fun options(turn: TaleTurn): List<TaleOption> {
        if (turn.mine == null) return listOf()
        return when (turn.step) {
            AT_CHOOSE -> if (turn.slot == "a" || turn.slot == "b") sides(turn.tale, turn.slot) else listOf()
            AT_GIVE -> if (turn.slot == "a") listOf(bread()) else listOf()
            else -> listOf()
        }
    }

    // ---- Each side's script ----

    /** What the player may say to a side that is not yet won: ask what happened, and then bring them to the well or sting them. */
    private fun sides(t: Tale, slot: String): List<TaleOption> {
        if (side(t, slot) != OPEN) return listOf()
        if (t.n[TOLD] and bit(slot) == 0) return listOf(account(slot))
        return listOf(well(slot), face(slot))
    }

    private fun label(key: String): Component = Component.translatable("talk.guildmark.choice.tale.fence.$key")

    /** "What happened between you?": the side's account, with the thing in grey, and that they would sit with the other if the other came first. */
    private fun account(slot: String) = TaleOption("account", label("account")) { turn ->
        val t = turn.tale
        if (t.step != AT_CHOOSE || side(t, slot) != OPEN) return@TaleOption null
        t.n[TOLD] = t.n[TOLD] or bit(slot)
        turn.changed()
        val me = TaleLines.lit(first(turn.name(slot)))
        TaleReply(
            listOf(
                TaleSay("tale.fence.side_$slot", TaleLines.facts("talk.detail.tale.fence.side_$slot.${t.n[THING].coerceIn(0, FenceStages.THINGS - 1)}", me)),
                TaleSay("tale.fence.willing"),
            ),
            Activity.POINT, door(t, other(slot)),
        )
    }

    /** "At the well, at dusk. They'll be there.": the side agrees to come (the bit is the player's whose word it was). */
    private fun well(slot: String) = TaleOption("well", label("well")) { turn ->
        val t = turn.tale
        if (t.step != AT_CHOOSE || side(t, slot) != OPEN) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val part = turn.part() ?: return@TaleOption null
        val player = turn.player ?: return@TaleOption null
        setSide(t, slot, AGREED)
        part.did = part.did or Part.CHOSE
        t.setS(DECIDER, player.uuid.toString())
        t.addWhy(Why("fence.agreed", listOf(first(turn.name(slot)), turn.who)))
        c.data.changed()
        sidesChanged(c, t)
        TaleReply("tale.fence.arranged", gesture = Activity.NOD)
    }

    /** "Say it to their face.": nothing is agreed, and the side is stung. */
    private fun face(slot: String) = TaleOption("face", label("face")) { turn ->
        val t = turn.tale
        if (t.step != AT_CHOOSE || side(t, slot) != OPEN) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        setSide(t, slot, STUNG)
        t.addWhy(Why("fence.stung", listOf(first(turn.name(slot)), turn.who)))
        c.data.changed()
        sidesChanged(c, t)
        TaleReply("tale.fence.glum", gesture = Activity.GRUMBLE)
    }

    // ---- The bread, into a's hands ----

    /** "Here, bread for the well.": takes up to the loaves still wanted from the pack, and the step is met with the second. */
    private fun bread() = TaleOption("bread", label("bread")) { turn ->
        val t = turn.tale
        if (t.step != AT_GIVE) return@TaleOption null
        val left = BREAD - t.n[LOAVES]
        if (left <= 0) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val player = turn.player ?: return@TaleOption null
        val part = turn.part() ?: return@TaleOption null
        val taken = takeLoaves(player, left)
        if (taken == 0) {
            return@TaleOption TaleReply(turn.family("bread_short"), TaleLines.facts("talk.detail.tale.fence.bread", left), Activity.SHRUG)
        }
        t.n[LOAVES] += taken
        part.did = part.did or Part.GAVE
        t.setS(DECIDER, player.uuid.toString())
        t.addWhy(Why("fence.bread", listOf(turn.who)))
        c.data.changed()
        val done = t.n[LOAVES] >= BREAD
        if (done) {
            t.n[ENDING] = code(Ending.A)
            c.go(t, AT_SCENE)
        }
        val say = if (done) turn.say("bread_both") else turn.say("bread_one", TaleLines.facts("talk.detail.tale.fence.bread", BREAD - t.n[LOAVES]))
        TaleReply(listOf(say), Activity.THANK)
    }

    /** Takes up to [n] loaves of the tag from [player]'s pack; how many it took. */
    private fun takeLoaves(player: Player, n: Int): Int {
        var taken = 0
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            if (taken >= n) break
            val stack = inventory.getItem(slot)
            if (stack.isEmpty || !stack.`is`(LOAF)) continue
            val take = minOf(stack.count, n - taken)
            stack.shrink(take)
            taken += take
        }
        if (taken > 0) inventory.setChanged()
        return taken
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val title = TaleKeys.title(id)
        val aName = first(t.cast("a")?.name.orEmpty())
        val bName = first(t.cast("b")?.name.orEmpty())
        return when (t.step) {
            AT_ASK -> View(title, status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(aName), target = door(t, "a"))
            AT_CHOOSE -> {
                // The side not yet won, the one not yet heard first
                val slot = if (side(t, "a") == OPEN && (t.n[TOLD] and 1 == 0 || side(t, "b") != OPEN)) "a" else "b"
                View(title, status = TaleKeys.status(id, AT_CHOOSE), statusArgs = listOf(if (slot == "a") aName else bName), target = door(t, slot))
            }
            AT_GIVE -> View(title, status = TaleKeys.status(id, AT_GIVE), statusArgs = listOf((BREAD - t.n[LOAVES]).coerceAtLeast(0).toString()), target = door(t, "a"))
            else -> View(title, status = TaleKeys.status(id, AT_SCENE), target = site(t))
        }
    }
}
