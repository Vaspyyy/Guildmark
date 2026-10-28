package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Noted
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.faction.Factions
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
import io.github.vaspyyy.guildmark.tales.TaleCast
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.TaleLines
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
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Slight
import io.github.vaspyyy.guildmark.townsfolk.talk.Spread
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * The Locket (living-world/stories/design.md 8.11, size 2): in the lean days something small and dear went from a windowsill at dusk, a locket
 * with a curl of hair in it. Three neighbours saw three different things, and three people were about. A small whodunit worked out by talking and
 * reading the grey lines, and its heart is what to do with a hungry neighbour once you know: feed them, hush it, or shame them.
 *
 * - **Seed.** The lean days (the times lean or hard on two of the last four closed days) or a crime standing on the town's page; the cast is found
 *   by a search around the oldest homes ([plan]). The theft is set down in the town's record (`Noted.THEFT`) when the tale begins.
 * - **The three facts of the thief** are numbers in the record ([LocketSlots]): the side (F1), the piece (F2) and the child (F3). The culprit shows
 *   all three, decoy_a F1 and F2 but no child, decoy_b F2 and the child but not the side. The three who saw (witness1 to 3) each tell one.
 * - **Steps.** 0 ASK the owner by 2; 1 MEET the three who saw, by 5; 2 MEET the three who were about, find the thief, by 6; 3 CHOOSE how it goes
 *   back (loaves, "found", "before the street"), by 7; 4 SCENE the return, by 8. The alibis and the accusation are offered from step 1, to a
 *   player who holds two words, so nobody waits for a close they need not.
 * - **The factions** each have a way of their own, once for each tale: the Starwatch reads the sill (all three words at once), the Hearthsworn
 *   gives the formal word (C) and the Quiet Hand the quiet one (B). A wrong guess spends the tool and costs the slight [Slight.WRONGED].
 * - **Endings.** A mended (three loaves; the culprit takes it back that dusk and is fed), B found (the culprit gives it to the player, who says it
 *   was found, or the Quiet Hand's word), C shamed (before the street, the formal word, or `sill`: the world, or the culprit's going, puts it back
 *   by night), W lost (no thief found).
 *
 * The locket is never an item: [LocketSlots.LOCKET] says who has it. In a scene a body holds the drawn prop of the look `locket`.
 */
object Locket : Template, TaleVoice, TaleWords.Names {
    override val id: String = "locket"
    override val size: Int = 2

    /** The days are D2, D5, D6, D7 and D8: the longest route is eight, and no step lasts more than three. */
    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2),
        Step(Verb.MEET, by = 5, real = true),
        Step(Verb.MEET, by = 6, real = true),
        Step(Verb.CHOOSE, by = 7, real = true, also = listOf(Verb.GIVE)),
        Step(Verb.SCENE, by = 8, needs = { Tales.scenes() }),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "mended"),
        Ending(Ending.B, "found"),
        Ending(Ending.C, "shamed"),
        Ending(Ending.W, "lost"),
    )

    /**
     * The three who saw are standing (any neighbour can tell what was seen, and a stand-in tells the same), and so are the two decoys: the tale is
     * not about them (they are not thanked at an A or a B, which would undo the slight of a wrong word), and a stand-in for one is given their own
     * facts ([stoodIn]). The owner and the culprit are the tale.
     */
    override val standing: Set<String> = LocketCast.WITNESSES.keys + LocketCast.DECOYS

    override val numbers: List<String> = listOf("words", "locket", "side", "piece", "decoys", "tools", "way", "played")

    // ---- The numbers ----

    /** How far around the owner's door the cast lives, in blocks; the oldest homes tried for an owner; the days of the times read. */
    private const val NEAR = 40.0
    private const val OWNERS = 6
    private const val DAYS_READ = 4
    private const val LEAN_DAYS = 2

    /** Loaves the culprit is fed with (a meal for the family, about a Mark), and the market days the culprit's household is protected after an A. */
    private const val LOAVES = 3
    private const val PROTECTED_DAYS = 20

    /** How far the witness who saw the side points down it, in blocks. */
    private const val POINT = 12

    /**
     * The hours (from the close at two in the morning) in which a return reached at step 4 can still be seen the same day: the Return's dusk ends
     * at nine and the Naming's day at eight, and each wants an hour and a half to be asked for and played.
     */
    private val RETURN_TODAY: ClosedFloatingPointRange<Double> = 2.0..19.5
    private val NAMING_TODAY: ClosedFloatingPointRange<Double> = 2.0..18.5

    /** The loaves the tale takes: the tag `guildmark:tale_loaf` (bread, and the loaf of the cook's dishes), as the fence takes them. */
    private val LOAF: TagKey<Item> = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("guildmark", "tale_loaf"))

    override fun needs(): Boolean = Fortunes.enabled()

    // ---- Seed ----

    /** What starts the tale: its first reason, and what a forced seed made up. */
    private class Trouble(val why: String, val madeUp: String = "")

    private fun trouble(c: Seeding): Trouble? {
        val level = c.level
        val lean = Fortunes.days(level, c.capital).takeLast(DAYS_READ).count { it.times == Times.LEAN || it.times == Times.HARD }
        if (lean >= LEAN_DAYS) return Trouble("locket.lean")
        if (Fortunes.reasons(level, c.capital).any { it.kind == FortuneReasons.CRIME && (it.until < 0 || it.until >= c.day) }) return Trouble("locket.crime")
        if (!c.forced) return null
        return Trouble("locket.lean", "the lean days it was taken in (the times here were lean on $lean of the last $DAYS_READ closed days, and no crime stands on the town's page)")
    }

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val trouble = trouble(c) ?: return c.none("the times were not lean on two of the last $DAYS_READ closed days, and no crime stands on the town's page")
        val lodgings = c.here.places.lodgings
        val pool = TaleCast.pool(level, c.here.capital.center).filter { lodgings[it.home] != null }
        val owners = pool.sortedWith(compareBy<Resident>({ it.since }, { it.id })).take(OWNERS)
        if (owners.isEmpty()) return c.none("no grown person with a home is free to be cast")
        val kids = Residents.get(level).of(c.here.capital.center)
            .filter { it.alive && it.left < 0 && it.role == TownRole.CHILD && it.home >= 0 }
            .mapTo(HashSet()) { it.home }
        for (owner in owners) plan(c, owner, pool, kids, trouble)?.let { return it }
        return c.none("no citizen with a child at home and two neighbours who wear the same piece, within ${NEAR.toInt()} blocks of the oldest homes")
    }

    /** The three who were about, the piece and the sides, found around [owner]'s door. */
    private class Pick(val culprit: Resident, val a: Resident, val b: Resident, val piece: Int, val side: Int, val sideB: Int)

    private fun plan(c: Seeding, owner: Resident, pool: List<Resident>, kids: Set<Int>, trouble: Trouble): Planned? {
        val lodgings = c.here.places.lodgings
        val home = lodgings[owner.home] ?: return null
        val centre = home.box.center
        val door = LocketFacts.door(home)
        fun middle(r: Resident): BlockPos = lodgings.getValue(r.home).box.center
        fun doorOf(r: Resident): BlockPos = LocketFacts.door(lodgings.getValue(r.home))
        val near = pool.filter { it.id != owner.id && it.home != owner.home && middle(it).distSqr(centre) <= NEAR * NEAR }
        val pick = pick(c, door, near, kids, ::doorOf) ?: return null
        val homes = setOf(owner.home, pick.culprit.home, pick.a.home, pick.b.home)
        val others = pool.filter { it.home !in homes }.sortedBy { middle(it).distSqr(centre) }
        // The third who saw is the baker when the town has one (the baker sees who buys bread); the other two are the nearest neighbours
        val baker = others.firstOrNull { it.role == TownRole.BAKER }
        val neighbours = others.filter { it !== baker && middle(it).distSqr(centre) <= NEAR * NEAR }
        val saw = if (baker != null) neighbours.take(2) + baker else neighbours.take(3)
        if (saw.size < 3) return null
        val cast = listOf(
            Cast(LocketCast.OWNER, owner.id, owner.name),
            Cast(LocketCast.CULPRIT, pick.culprit.id, pick.culprit.name),
            Cast(LocketCast.DECOY_A, pick.a.id, pick.a.name),
            Cast(LocketCast.DECOY_B, pick.b.id, pick.b.name),
            Cast(LocketCast.WITNESS_1, saw[0].id, saw[0].name),
            Cast(LocketCast.WITNESS_2, saw[1].id, saw[1].name),
            Cast(LocketCast.WITNESS_3, saw[2].id, saw[2].name),
        )
        val decoys = LocketFacts.with(
            LocketFacts.pack(pick.side, false, pick.piece),
            LocketCast.DECOY_B, LocketFacts.pack(pick.sideB, true, pick.piece),
        )
        return Planned(
            cast = cast,
            why = Why(trouble.why, listOf(given(owner.name))),
            step = LocketSlots.AT_ASK,
            n = listOf(0, LocketSlots.WITH_CULPRIT, pick.side, pick.piece, decoys, 0, LocketSlots.NONE, 0),
            madeUp = trouble.madeUp,
        )
    }

    /**
     * The culprit (a citizen whose household has a child in it, of another household than the owner's), and a piece of their look that decoy_a (on
     * the same side of the owner's [door], with no child at home) and decoy_b (on another side, with a child) wear too. The first triple the dice
     * find; null if no triple fits.
     */
    private fun pick(c: Seeding, door: BlockPos, near: List<Resident>, kids: Set<Int>, doorOf: (Resident) -> BlockPos): Pick? {
        for (culprit in near.filter { it.role == TownRole.CITIZEN && it.home in kids }.shuffled(c.random)) {
            val side = LocketFacts.sideOf(door, doorOf(culprit))
            for (piece in LocketFacts.pieces(culprit).shuffled(c.random)) {
                val wearing = near.filter { it.id != culprit.id && it.home != culprit.home && LocketFacts.wears(it, piece) }
                val a = wearing.filter { it.home !in kids && LocketFacts.sideOf(door, doorOf(it)) == side }.shuffled(c.random).firstOrNull() ?: continue
                val b = wearing.filter { it.home in kids && it.home != a.home && LocketFacts.sideOf(door, doorOf(it)) != side }.shuffled(c.random).firstOrNull() ?: continue
                return Pick(culprit, a, b, LocketFacts.PIECES.indexOf(piece), side, LocketFacts.sideOf(door, doorOf(b)))
            }
        }
        return null
    }

    // ---- The steps ----

    /**
     * The theft is set down in the town's record once, as a stolen purse is (`Noted.THEFT`: the crime reason, -1 for three days). At step 4 a
     * return that is a scene (the loaves' at dusk, the street's at the well) is looked for today if there is still time for it, else tomorrow,
     * and the close after it settles the step if nobody saw it; one in the player's keeping waits for them until the table's day.
     */
    override fun enter(c: Ctx, t: Tale) {
        if (t.n[LocketSlots.TOOLS] and LocketSlots.NOTED == 0) {
            t.n[LocketSlots.TOOLS] = t.n[LocketSlots.TOOLS] or LocketSlots.NOTED
            Fortunes.note(c.level, c.capital, Noted.THEFT)
            c.data.changed()
        }
        if (t.step != LocketSlots.AT_RETURN) return
        val time = when (t.n[LocketSlots.WAY]) {
            LocketSlots.BREAD -> RETURN_TODAY
            LocketSlots.STREET, LocketSlots.LAW -> NAMING_TODAY
            else -> return
        }
        t.by = minOf(t.by, c.day + if (Stage.within(c.level, time)) 0 else 1)
        c.data.changed()
    }

    /** Nothing here comes from a hook: what the player says in the talk is the whole of it. */
    override fun heard(c: Ctx, t: Tale, e: Event) {}

    /**
     * The owner on their doorstep by day (the opener with a passer-by's comfort; while the words are gathered and the thief looked for, alone), the
     * scene of the way at step 4 (the Return at dusk for the loaves, the Naming at the well for the street and the Order's word), which ends the tale
     * once it has played; and, as the tale ends, the Clasp of a B, the owner who has stopped looking of a W, and the Return or the Naming of an A
     * or a C that no scene showed (`tale end`), if it is their hour (an ending at the close, at two in the morning, is not seen).
     */
    override fun scene(c: Ctx, t: Tale): Stage? {
        if (!Tales.scenes()) return null
        if (!t.open) {
            // The ending seen, once, as it is made (`tale end`, a word in the talk), when no scene of step 4 showed it already and it is its hour
            return when (t.ending) {
                Ending.A -> if (t.n[LocketSlots.PLAYED] == 0) LocketScenes.homecomingFor(c, t, BooleanArray(1)) else null
                Ending.B -> LocketScenes.claspFor(c, t)
                Ending.C -> if (t.n[LocketSlots.PLAYED] == 0 && t.n[LocketSlots.WAY] != LocketSlots.SILL) LocketScenes.namingFor(c, t, BooleanArray(1)) else null
                else -> LocketScenes.lostFor(c, t)
            }
        }
        return when (t.step) {
            LocketSlots.AT_ASK -> LocketScenes.doorstepFor(c, t, comfort = true)
            LocketSlots.AT_WORDS, LocketSlots.AT_FIND -> LocketScenes.doorstepFor(c, t, comfort = false)
            LocketSlots.AT_RETURN -> {
                if (t.n[LocketSlots.PLAYED] != 0) return null
                val played = BooleanArray(1)
                when (t.n[LocketSlots.WAY]) {
                    LocketSlots.BREAD -> LocketScenes.ending(t, c.level, LocketScenes.homecomingFor(c, t, played), played)
                    LocketSlots.STREET, LocketSlots.LAW -> LocketScenes.ending(t, c.level, LocketScenes.namingFor(c, t, played), played)
                    else -> null
                }
            }
            else -> null
        }
    }

    /** The talk is [TaleVoice]'s; this template gives no script of its own. */
    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    // ---- The world's way ----

    /**
     * What the town does without a hero. Nobody asked: the street carries the word to the three who saw (step 1). Fewer than two words by D5: the
     * trail goes cold (W). The thief not found by D6: W. The thief found and no way chosen by D7: the culprit puts it back on the sill by night (C,
     * `sill`). The return not seen by D8: it went home the way chosen (A, B or C).
     */
    override fun settle(c: Ctx, t: Tale) {
        val owner = given(t.cast(LocketCast.OWNER)?.name.orEmpty())
        when (t.step) {
            LocketSlots.AT_ASK -> {
                c.why(t, "locket.unasked")
                c.next(t)
            }
            LocketSlots.AT_WORDS -> if (LocketFacts.count(t.n[LocketSlots.WORDS]) >= 2) {
                c.why(t, "locket.enough")
                c.next(t)
            } else {
                c.why(t, "locket.cold")
                c.end(t, Ending.W)
            }
            LocketSlots.AT_FIND -> {
                c.why(t, "locket.unfound")
                c.end(t, Ending.W)
            }
            LocketSlots.AT_CHOOSE -> sill(c, t)
            else -> {
                val way = t.n[LocketSlots.WAY]
                c.why(t, if (way == LocketSlots.FOUND || way == LocketSlots.QUIET) "locket.sill" else "locket.home", owner)
                t.n[LocketSlots.LOCKET] = LocketSlots.HOME
                c.data.changed()
                c.end(t, endingOf(t))
            }
        }
    }

    /** The culprit puts it back on the owner's sill by night: C, `sill`. */
    private fun sill(c: Ctx, t: Tale) {
        t.n[LocketSlots.WAY] = LocketSlots.SILL
        t.n[LocketSlots.LOCKET] = LocketSlots.HOME
        c.why(t, "locket.sill", given(t.cast(LocketCast.OWNER)?.name.orEmpty()))
        c.data.changed()
        c.end(t, Ending.C)
    }

    /** The ending the way chosen makes: A (the loaves), B ("found", the quiet word), C (before the street, the formal word, the sill); W if there is no way. */
    internal fun endingOf(t: Tale): String = when (t.n[LocketSlots.WAY]) {
        LocketSlots.BREAD -> Ending.A
        LocketSlots.FOUND, LocketSlots.QUIET -> Ending.B
        LocketSlots.STREET, LocketSlots.LAW, LocketSlots.SILL -> Ending.C
        else -> Ending.W
    }

    /**
     * The owner is gone (died, or their household left): no one is left to give it back to, and the tale ends W. The culprit is gone: the locket is
     * found on the sill, ending C `sill`; but once it is on its way home it gets there (fed, the A stands; in the player's keeping, they can still
     * give it back). A witness gone with no one to take their place: that word is simply not given, and the others still are (the step is met by the
     * last word the town can still give). A decoy gone with no one in their place: one suspect fewer.
     */
    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            LocketCast.OWNER -> {
                c.why(t, "locket.owner_gone")
                c.end(t, Ending.W)
            }
            LocketCast.CULPRIT -> when {
                t.step == LocketSlots.AT_RETURN && t.n[LocketSlots.WAY] == LocketSlots.BREAD -> {
                    t.n[LocketSlots.LOCKET] = LocketSlots.HOME
                    c.why(t, "locket.home", given(t.cast(LocketCast.OWNER)?.name.orEmpty()))
                    c.end(t, Ending.A)
                }
                t.step == LocketSlots.AT_RETURN && t.n[LocketSlots.LOCKET] == LocketSlots.WITH_PLAYER -> {}
                else -> sill(c, t)
            }
            else -> if (slot in LocketCast.WITNESSES) wordsDone(c, t, null)
        }
    }

    /**
     * A decoy's stand-in is given their own facts, from where they live, what they wear and who is at home, so their alibi is the truth about
     * them (the record kept the one who went). A witness's stand-in tells what the one before them saw.
     */
    override fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {
        if (slot !in LocketCast.DECOYS) return
        val r = c.resident(t, slot) ?: return
        val lodgings = c.here.places.lodgings
        val owner = c.resident(t, LocketCast.OWNER)?.let { lodgings[it.home] } ?: return
        val home = lodgings[r.home] ?: return
        val side = LocketFacts.sideOf(LocketFacts.door(owner), LocketFacts.door(home))
        val child = Residents.get(c.level).of(c.here.capital.center).any { it.alive && it.left < 0 && it.role == TownRole.CHILD && it.home == r.home }
        val piece = LocketFacts.piece(t)
        val shows = if (LocketFacts.wears(r, piece)) t.n[LocketSlots.PIECE] else LocketFacts.pieces(r).firstOrNull()?.let { LocketFacts.PIECES.indexOf(it) } ?: LocketFacts.NO_PIECE
        t.n[LocketSlots.DECOYS] = LocketFacts.with(t.n[LocketSlots.DECOYS], slot, LocketFacts.pack(side, child, shows))
        c.data.changed()
    }

    /**
     * The consequences of an ending (8.11): A, the culprit becomes DILIGENT (they were fed) and the owner CHEERFUL, and the culprit's household is
     * protected from leaving for twenty days (in lean times it would have been the next to go); B, the owner CHEERFUL (the culprit is not named or
     * changed); C, the culprit GRUMPY; W, the owner SHY. The locket is home in all but W, where it stays with the culprit.
     */
    override fun end(c: Ctx, t: Tale, ending: String) {
        when (ending) {
            Ending.A -> {
                t.n[LocketSlots.LOCKET] = LocketSlots.HOME
                c.flip(t, LocketCast.CULPRIT, Trait.DILIGENT)
                c.flip(t, LocketCast.OWNER, Trait.CHEERFUL)
                c.resident(t, LocketCast.CULPRIT)?.takeIf { it.home >= 0 }?.let { c.protect(t, it.home, PROTECTED_DAYS) }
            }
            Ending.B -> {
                t.n[LocketSlots.LOCKET] = LocketSlots.HOME
                c.flip(t, LocketCast.OWNER, Trait.CHEERFUL)
            }
            Ending.C -> {
                t.n[LocketSlots.LOCKET] = LocketSlots.HOME
                c.flip(t, LocketCast.CULPRIT, Trait.GRUMPY)
            }
            else -> c.flip(t, LocketCast.OWNER, Trait.SHY)
        }
        c.data.changed()
    }

    /** The words an ending is said with (E's table): `%1` the owner, `%2` the culprit, whom only A and C name. */
    override fun words(t: Tale): List<String> =
        listOf(t.cast(LocketCast.OWNER)?.name.orEmpty(), t.cast(LocketCast.CULPRIT)?.name.orEmpty())

    // ---- The words ----

    /** The sightings still to be heard that a witness who is still there could give. */
    private fun open(t: Tale): Int =
        LocketCast.WITNESSES.entries.filter { (slot, bit) -> t.n[LocketSlots.WORDS] and bit == 0 && t.cast(slot)?.gone.isNullOrEmpty() }.sumOf { it.value }

    /**
     * The last word the town can still give was heard (or a witness went and there is none left to hear): the step's bit is set for [part] (else the
     * lead), and the tale goes on to the thief if two words or more are known.
     */
    private fun wordsDone(c: Ctx, t: Tale, part: Part?) {
        val words = t.n[LocketSlots.WORDS]
        if (words == 0 || open(t) != 0) return
        (part ?: t.lead)?.let { it.did = it.did or Part.WALKED }
        c.data.changed()
        if (t.step == LocketSlots.AT_WORDS && LocketFacts.count(words) >= 2) c.next(t)
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val title = TaleKeys.title(id)
        val owner = given(t.cast(LocketCast.OWNER)?.name.orEmpty())
        val words = t.n[LocketSlots.WORDS]
        return when (t.step) {
            LocketSlots.AT_ASK -> View(title, status = TaleKeys.status(id, LocketSlots.AT_ASK), statusArgs = listOf(owner), target = doorOf(t, LocketCast.OWNER))
            LocketSlots.AT_WORDS -> View(
                title, status = TaleKeys.status(id, LocketSlots.AT_WORDS), statusArgs = listOf(LocketFacts.count(words).toString()),
                // The nearest door of the three who saw that has not been heard (the owner's, once nobody is left to hear)
                target = nearest(t, LocketCast.WITNESSES.filter { (_, bit) -> words and bit == 0 }.keys) ?: doorOf(t, LocketCast.OWNER),
            )
            LocketSlots.AT_FIND -> View(title, status = TaleKeys.status(id, LocketSlots.AT_FIND), target = nearest(t, LocketCast.SUSPECTS))
            LocketSlots.AT_CHOOSE -> View(title, status = TaleKeys.status(id, LocketSlots.AT_CHOOSE), target = doorOf(t, LocketCast.CULPRIT))
            else -> when (t.n[LocketSlots.WAY]) {
                LocketSlots.BREAD -> View(title, status = "gui.guildmark.tale.locket.status.dusk", tone = View.Tone.DONE, target = doorOf(t, LocketCast.OWNER))
                LocketSlots.STREET, LocketSlots.LAW -> View(title, status = "gui.guildmark.tale.locket.status.well", tone = View.Tone.DONE, target = wellOf(t))
                else -> View(
                    title, status = TaleKeys.status(id, LocketSlots.AT_RETURN), statusArgs = listOf(owner),
                    tone = if (t.n[LocketSlots.LOCKET] == LocketSlots.WITH_PLAYER) View.Tone.TALE else View.Tone.DONE, target = doorOf(t, LocketCast.OWNER),
                )
            }
        }
    }

    /** A name's first word: the tracker and the grey lines say given names only. */
    internal fun given(name: String): String = name.substringBefore(' ')

    /** The capital's places, once the talk has found them; null before (seconds, after a restart). */
    private fun places(t: Tale): CityPlaces? = Spread.view(t.capital).places

    /** The front door of the home of the person in [slot], else the middle of the building; null while the capital's places or the person are not known. */
    internal fun doorOf(t: Tale, slot: String): BlockPos? {
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return null
        val r = Residents.get(level)[t.resident(slot)] ?: return null
        val lodging = places(t)?.lodgings?.get(r.home) ?: return null
        return LocketFacts.door(lodging)
    }

    /** The door, of those in [slots] who are still there, nearest to the owner's. */
    private fun nearest(t: Tale, slots: Collection<String>): BlockPos? {
        val from = doorOf(t, LocketCast.OWNER)
        val doors = slots.filter { t.cast(it)?.gone.isNullOrEmpty() }.mapNotNull { doorOf(t, it) }
        return if (from == null) doors.firstOrNull() else doors.minByOrNull { it.distSqr(from) } ?: from
    }

    /** The well nearest the owner's door, where the Naming is made (the market if the town has no well). */
    private fun wellOf(t: Tale): BlockPos? {
        val places = places(t) ?: return null
        val from = doorOf(t, LocketCast.OWNER) ?: return places.market
        return places.wells.minByOrNull { it.distSqr(from) } ?: places.market
    }

    // ---- The talk ----

    override fun asker(t: Tale): String = LocketCast.OWNER

    /** The owner wrings their hands as they ask (one who is sitting sighs: the clip is drawn standing). */
    override fun gesture(beat: String, turn: TaleTurn): Activity? =
        if ((beat == "ask" || beat == "passing") && turn.slot == LocketCast.OWNER) Sets.fit(turn.ask.speaker, Activity.WRING_HANDS) else null

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        return when (beat) {
            "ask" -> {
                val about = LocketCast.SUSPECTS.map { given(turn.name(it)) }.sorted().map { TaleLines.lit(it) }
                TaleLines.facts("talk.detail.tale.locket.ask", about[0], about[1], about[2])
            }
            "rumour" -> TaleLines.facts("talk.detail.tale.locket.rumour", TaleLines.lit(given(turn.name(LocketCast.OWNER))))
            "stand" -> stand(turn)
            else -> null
        }
    }

    /** How it stands, from the record: the words heard and what they told, the thief found, the locket on its way home, and the day the step closes. */
    private fun stand(turn: TaleTurn): String? {
        val t = turn.tale
        val closes = TaleLines.due(turn.left)
        return when (t.step) {
            LocketSlots.AT_CHOOSE -> TaleLines.facts("talk.detail.tale.locket.stand.found", closes)
            LocketSlots.AT_RETURN -> when (t.n[LocketSlots.WAY]) {
                LocketSlots.BREAD -> TaleLines.facts("talk.detail.tale.locket.stand.dusk", closes)
                LocketSlots.STREET, LocketSlots.LAW -> TaleLines.facts("talk.detail.tale.locket.stand.well", closes)
                else -> if (t.n[LocketSlots.LOCKET] == LocketSlots.WITH_PLAYER) TaleLines.facts("talk.detail.tale.locket.stand.carried", closes) else null
            }
            else -> {
                val told = told(t)
                if (told.isEmpty()) {
                    val saw = LocketCast.WITNESSES.keys.map { TaleLines.lit(given(turn.name(it))) }
                    TaleLines.facts("talk.detail.tale.locket.stand.words.0", saw[0], saw[1], saw[2], closes)
                } else {
                    TaleLines.facts("talk.detail.tale.locket.stand.words.${told.size}", *(told + closes).toTypedArray())
                }
            }
        }
    }

    /** What the words heard so far told, as phrases of the language (side, piece, child), in that order. */
    private fun told(t: Tale): List<String> {
        val words = t.n[LocketSlots.WORDS]
        val out = ArrayList<String>()
        if (words and LocketSlots.SAW_SIDE != 0) out += lane(t.n[LocketSlots.SIDE])
        if (words and LocketSlots.SAW_PIECE != 0) out += wearing(LocketFacts.piece(t))
        if (words and LocketSlots.SAW_CHILD != 0) out += CORNER
        return out
    }

    private const val CORNER = "say.guildmark.talk.detail.tale.locket.child.corner"

    /** "the north lane" and the like. */
    private fun lane(side: Int): String = "say.guildmark.talk.detail.tale.locket.lane.${LocketFacts.SIDES.getOrElse(side) { "north" }}"

    /** "a cap" and the like; "nothing to know them by" for none of the six. */
    private fun wearing(piece: String): String = "say.guildmark.talk.detail.tale.locket.piece.${piece.ifEmpty { "none" }}"

    /** "a child at home" or "no child at home". */
    private fun home(child: Boolean): String = if (child) "say.guildmark.talk.detail.tale.locket.child.yes" else "say.guildmark.talk.detail.tale.locket.child.no"

    override fun options(turn: TaleTurn): List<TaleOption> {
        val slot = turn.slot
        return when {
            slot == LocketCast.OWNER -> owner(turn)
            slot in LocketCast.WITNESSES -> if (turn.step == LocketSlots.AT_WORDS || turn.step == LocketSlots.AT_FIND) listOf(saw(slot)) else listOf()
            slot in LocketCast.SUSPECTS -> suspect(turn)
            else -> listOf()
        }
    }

    private fun label(key: String, vararg args: Any): Component = Component.translatable("talk.guildmark.choice.tale.locket.$key", *args)

    private fun think() = TaleReply("ask.think", gesture = Activity.THINK)

    private fun member(player: ServerPlayer?, faction: Faction): Boolean = player != null && Factions.state(player).member(faction)

    /** Whether [t] is at a step where the words are gathered and the thief looked for. */
    private fun looking(t: Tale): Boolean = t.step == LocketSlots.AT_WORDS || t.step == LocketSlots.AT_FIND

    // ---- The owner ----

    private fun owner(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val out = ArrayList<TaleOption>()
        if (looking(t) && member(turn.player, Faction.STARWATCH) && t.n[LocketSlots.TOOLS] and LocketSlots.READ == 0 && t.n[LocketSlots.WORDS] != LocketSlots.ALL_WORDS) out += read()
        if (t.step == LocketSlots.AT_RETURN && t.n[LocketSlots.LOCKET] == LocketSlots.WITH_PLAYER && turn.mine?.did(Part.CHOSE) == true) out += deliver()
        return out
    }

    /** A Starwatch member reads the sill: all three words at once, once for the tale. The owner points to the sill, amazed. */
    private fun read() = TaleOption("read", label("read")) { turn ->
        val t = turn.tale
        if (!looking(t) || !member(turn.player, Faction.STARWATCH) || t.n[LocketSlots.TOOLS] and LocketSlots.READ != 0) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        val part = turn.part() ?: return@TaleOption think()
        t.n[LocketSlots.TOOLS] = t.n[LocketSlots.TOOLS] or LocketSlots.READ
        t.n[LocketSlots.WORDS] = LocketSlots.ALL_WORDS
        part.did = part.did or Part.WALKED
        c.why(t, "locket.read", turn.who)
        turn.changed()
        if (t.step == LocketSlots.AT_WORDS) c.next(t)
        val facts = TaleLines.facts("talk.detail.tale.locket.read", lane(t.n[LocketSlots.SIDE]), wearing(LocketFacts.piece(t)), CORNER)
        TaleReply(listOf(turn.say("read", facts)), Activity.POINT, toward = doorOf(t, LocketCast.OWNER))
    }

    /** "I found this on the lane.": the locket the culprit gave over is given to the owner, and no one is named (B); the Clasp follows. */
    private fun deliver() = TaleOption("deliver", label("deliver")) { turn ->
        val t = turn.tale
        if (t.step != LocketSlots.AT_RETURN || t.n[LocketSlots.LOCKET] != LocketSlots.WITH_PLAYER) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        val part = turn.part() ?: return@TaleOption think()
        part.did = part.did or Part.HANDED
        t.n[LocketSlots.LOCKET] = LocketSlots.HOME
        c.why(t, "locket.delivered", turn.who)
        turn.changed()
        c.end(t, Ending.B)
        // The talk closes so the owner is free for the Clasp, which the ending queues
        TaleReply(listOf(turn.say("found", TaleLines.facts("talk.detail.tale.locket.found"))), Activity.THANK, ends = true)
    }

    // ---- The three who saw ----

    /** What the witness in [slot] saw: the side, the piece or the child, by the word they give. Said again, it says the same. */
    private fun saw(slot: String) = TaleOption("saw", label("saw")) { turn ->
        val t = turn.tale
        if (!looking(t)) return@TaleOption null
        val bit = LocketCast.WITNESSES[slot] ?: return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        val part = turn.part() ?: return@TaleOption think()
        if (t.n[LocketSlots.WORDS] and bit == 0) {
            t.n[LocketSlots.WORDS] = t.n[LocketSlots.WORDS] or bit
            c.why(t, "locket.heard", turn.who, given(turn.name(slot)))
            turn.changed()
            wordsDone(c, t, part)
        }
        when (bit) {
            LocketSlots.SAW_SIDE -> TaleReply(
                listOf(turn.say("sight_side", TaleLines.facts("talk.detail.tale.locket.sight_side", lane(t.n[LocketSlots.SIDE])))),
                Activity.POINT, toward = down(t),
            )
            LocketSlots.SAW_PIECE -> TaleReply(listOf(turn.say("sight_piece", TaleLines.facts("talk.detail.tale.locket.sight_piece", wearing(LocketFacts.piece(t))))), Activity.EXPLAIN)
            else -> TaleReply(listOf(turn.say("sight_child", TaleLines.facts("talk.detail.tale.locket.sight_child"))), Activity.EXPLAIN)
        }
    }

    /** Down the lane the thief went by: a point [POINT] blocks from the owner's door on the culprit's side. */
    private fun down(t: Tale): BlockPos? {
        val dir = when (t.n[LocketSlots.SIDE]) {
            0 -> Direction.NORTH
            1 -> Direction.EAST
            2 -> Direction.SOUTH
            else -> Direction.WEST
        }
        return doorOf(t, LocketCast.OWNER)?.relative(dir, POINT)
    }

    // ---- The three who were about ----

    /** The bit that says the decoy in [slot] has been wrongly asked already (0 for the culprit). */
    private fun wronged(slot: String): Int = when (slot) {
        LocketCast.DECOY_A -> LocketSlots.WRONG_A
        LocketCast.DECOY_B -> LocketSlots.WRONG_B
        else -> 0
    }

    private fun suspect(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val slot = turn.slot
        if (t.step == LocketSlots.AT_CHOOSE) {
            return if (slot == LocketCast.CULPRIT && t.n[LocketSlots.WAY] == LocketSlots.NONE) listOf(bread(), handed(), street()) else listOf()
        }
        if (!looking(t)) return listOf()
        val out = ArrayList<TaleOption>()
        out += alibi(slot)
        // A suspect is asked once: one wrongly asked has nothing more to say to it, and the slight is the whole of the cost
        if (t.n[LocketSlots.TOOLS] and wronged(slot) != 0) return out
        if (LocketFacts.count(t.n[LocketSlots.WORDS]) >= 2) out += accuse(slot)
        val tools = t.n[LocketSlots.TOOLS]
        if (member(turn.player, Faction.HEARTHSWORN) && tools and LocketSlots.LAW_TOOL == 0) out += law(slot)
        if (member(turn.player, Faction.QUIET_HAND) && tools and LocketSlots.QUIET_TOOL == 0) out += quiet(slot)
        return out
    }

    /** "Where were you at dusk?": each of the three says the same sort of thing, and the grey line gives their own three facts. */
    private fun alibi(slot: String) = TaleOption("alibi", label("alibi")) { turn ->
        val t = turn.tale
        if (!looking(t)) return@TaleOption null
        val a = LocketFacts.alibi(t, slot) ?: return@TaleOption null
        val facts = TaleLines.facts("talk.detail.tale.locket.alibi", TaleLines.lit(given(turn.name(slot))), lane(a.side), wearing(a.piece), home(a.child))
        TaleReply(listOf(turn.say("alibi", facts)), Activity.SHRUG)
    }

    /** "Give back what you took.", with two words or more: the culprit owns to it; a wrong one is offended and the player earns the slight with them. */
    private fun accuse(slot: String) = TaleOption("accuse", label("accuse")) { turn ->
        val t = turn.tale
        if (!looking(t) || LocketFacts.count(t.n[LocketSlots.WORDS]) < 2 || t.n[LocketSlots.TOOLS] and wronged(slot) != 0) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        val part = turn.part() ?: return@TaleOption think()
        if (slot != LocketCast.CULPRIT) return@TaleOption wrong(turn, c, slot, 0)
        part.did = part.did or Part.WALKED
        c.why(t, "locket.confessed", turn.who)
        turn.changed()
        c.go(t, LocketSlots.AT_CHOOSE)
        TaleReply(listOf(turn.say("confess")), Activity.SIGH)
    }

    /** The Hearthsworn's word, from the start: the culprit yields at once and the return is made at the well, before the watch (C); a wrong person spends the tool. */
    private fun law(slot: String) = TaleOption("law", label("law")) { turn ->
        tool(turn, slot, LocketSlots.LAW_TOOL, Faction.HEARTHSWORN, LocketSlots.LAW, "law", "locket.law", Activity.BOW)
    }

    /** The Quiet Hand's word, from the start: the culprit gives it up quietly into the player's keeping (B); a wrong person spends the tool. */
    private fun quiet(slot: String) = TaleOption("quiet", label("quiet")) { turn ->
        tool(turn, slot, LocketSlots.QUIET_TOOL, Faction.QUIET_HAND, LocketSlots.QUIET, "quiet", "locket.quiet", Activity.WHISPER)
    }

    private fun tool(turn: TaleTurn, slot: String, bit: Int, faction: Faction, way: Int, beat: String, why: String, gesture: Activity): TaleReply? {
        val t = turn.tale
        if (!looking(t) || !member(turn.player, faction) || t.n[LocketSlots.TOOLS] and bit != 0 || t.n[LocketSlots.TOOLS] and wronged(slot) != 0) return null
        val c = turn.ctx() ?: return think()
        val part = turn.part() ?: return think()
        if (slot != LocketCast.CULPRIT) return wrong(turn, c, slot, bit)
        t.n[LocketSlots.TOOLS] = t.n[LocketSlots.TOOLS] or bit
        t.n[LocketSlots.WAY] = way
        if (way == LocketSlots.QUIET) t.n[LocketSlots.LOCKET] = LocketSlots.WITH_PLAYER
        part.did = part.did or Part.WALKED or Part.CHOSE or Part.DECIDED
        c.why(t, why, turn.who, given(turn.name(slot)))
        turn.changed()
        c.go(t, LocketSlots.AT_RETURN)
        val detail = if (way == LocketSlots.QUIET) carried(turn) else TaleLines.facts("talk.detail.tale.locket.law")
        return TaleReply(listOf(turn.say(beat, detail)), gesture)
    }

    /** "The locket is in your keeping. Tell Maud it was found.": the grey under the culprit's word when it is given over. */
    private fun carried(turn: TaleTurn): String? = TaleLines.facts("talk.detail.tale.locket.carried", TaleLines.lit(given(turn.name(LocketCast.OWNER))))

    /**
     * A wrong person asked for the locket: they are offended, the player earns the slight [Slight.WRONGED] from them alone (the town does not hear
     * of it), a tool used is spent, and they are not asked again. The player can try another door.
     */
    private fun wrong(turn: TaleTurn, c: Ctx, slot: String, bit: Int): TaleReply {
        val t = turn.tale
        t.n[LocketSlots.TOOLS] = t.n[LocketSlots.TOOLS] or bit or wronged(slot)
        val r = c.resident(t, slot)
        val player = turn.player
        if (r != null && player != null) TownTalk.slight(turn.level, r, player.uuid, Slight.WRONGED)
        val name = given(r?.name ?: turn.name(slot))
        c.why(t, "locket.wrong", turn.who, name)
        turn.changed()
        return TaleReply(listOf(turn.say("wrongly", TaleLines.facts("talk.detail.tale.locket.wrongly", TaleLines.lit(name)))), Activity.RECOIL)
    }

    // ---- The way (step 3), in the culprit's script ----

    /** "Here is bread. Take it back yourself.": three loaves from the pack, spent and not given back; the culprit takes the locket back at dusk (A). */
    private fun bread() = TaleOption("bread", label("bread")) { turn ->
        val t = turn.tale
        if (t.step != LocketSlots.AT_CHOOSE || t.n[LocketSlots.WAY] != LocketSlots.NONE) return@TaleOption null
        val player = turn.player ?: return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption think()
        val part = turn.part() ?: return@TaleOption think()
        if (!take(player, LOAVES)) {
            return@TaleOption TaleReply(listOf(TaleSay("tale.locket.short", TaleLines.facts("talk.detail.tale.locket.short", LOAVES))), Activity.SHRUG)
        }
        t.n[LocketSlots.WAY] = LocketSlots.BREAD
        part.did = part.did or Part.GAVE or Part.CHOSE or Part.DECIDED
        c.why(t, "locket.bread", turn.who, LOAVES.toString())
        turn.changed()
        c.go(t, LocketSlots.AT_RETURN)
        TaleReply(listOf(turn.say("bread", TaleLines.facts("talk.detail.tale.locket.bread", LOAVES))), Activity.RECEIVE)
    }

    /** "Give it to me. I'll say it was found.": free; the locket passes to the player's keeping (in the record), to be given to the owner (B). */
    private fun handed() = TaleOption("handed", label("handed")) { turn ->
        way(turn, LocketSlots.FOUND, "locket.found_way") { TaleReply(listOf(turn.say("quiet", carried(turn))), Activity.WHISPER) }
    }

    /** "You'll give it back before the street.": free; the return is made at the well in front of the watchers (C). */
    private fun street() = TaleOption("street", label("street")) { turn ->
        way(turn, LocketSlots.STREET, "locket.street_way") {
            TaleReply(listOf(TaleSay("tale.locket.street", TaleLines.facts("talk.detail.tale.locket.street"))), Activity.SIGH)
        }
    }

    private fun way(turn: TaleTurn, way: Int, why: String, says: () -> TaleReply): TaleReply? {
        val t = turn.tale
        if (t.step != LocketSlots.AT_CHOOSE || t.n[LocketSlots.WAY] != LocketSlots.NONE) return null
        val c = turn.ctx() ?: return think()
        val part = turn.part() ?: return think()
        t.n[LocketSlots.WAY] = way
        if (way == LocketSlots.FOUND) t.n[LocketSlots.LOCKET] = LocketSlots.WITH_PLAYER
        part.did = part.did or Part.CHOSE or Part.DECIDED
        c.why(t, why, turn.who)
        turn.changed()
        c.go(t, LocketSlots.AT_RETURN)
        return says()
    }

    /** Takes [count] loaves (anything of the tag `guildmark:tale_loaf`) from [player]'s pack, all or none; false if the pack holds fewer. */
    private fun take(player: ServerPlayer, count: Int): Boolean {
        val inventory = player.inventory
        var has = 0
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && stack.`is`(LOAF)) has += stack.count
        }
        if (has < count) return false
        var taken = 0
        for (slot in 0 until inventory.containerSize) {
            if (taken >= count) break
            val stack = inventory.getItem(slot)
            if (stack.isEmpty || !stack.`is`(LOAF)) continue
            val take = minOf(stack.count, count - taken)
            stack.shrink(take)
            taken += take
        }
        inventory.setChanged()
        return true
    }
}
