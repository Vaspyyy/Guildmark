package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.city.plan.Folk
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.faction.Factions
import io.github.vaspyyy.guildmark.tales.Cast
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Gone
import io.github.vaspyyy.guildmark.tales.NoteStages
import io.github.vaspyyy.guildmark.tales.NoteStall
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Planned
import io.github.vaspyyy.guildmark.tales.Seeding
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Step
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCast
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.TaleOption
import io.github.vaspyyy.guildmark.tales.TaleOptions
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
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.ShutStall
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Slight
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The Marlow note (living-world/stories/design.md 8.1, size 2), Tales T1a: the lean days have gone on, and a stall keeper has borrowed against her
 * stall from the town's gentleman. The note falls due in five days, 24 Marks; unpaid, the stall is sealed. This is the market as a story: every
 * purchase at her stall helps, a lender's tools are open to a Starwatch, Hearthsworn or Quiet Hand member, and a player the town knows can stand surety.
 *
 * **Steps.** 0 ASK (S1, the Count at her stall: yes puts the player in; by D3, and if none comes the note still falls due), 1 GIVE (the sum,
 * until D5, or D8 after a grace), 2 WAIT (the debt is clear: the lender comes to the stall in the morning), 3 SCENE (the ending, 9:00 to 17:00).
 * **Numbers.** `n[0]` owed (24 less what has gone in: bought, cut and gold), `n[1]` Marks bought at her stall (to 12), `n[2]` Marks cut by a faction
 * (0 or 6), `n[3]` grace given (0 or 1), `n[4]` gold put in and held until the sum is met, `n[5]` the ending the tale is going to (1 to 4 for A to W),
 * `n[6]` no seal (the debtor or the last holder of the note is gone). **Strings.** `s[0]` the player who stood surety, `s[1]` the stall (its keeper's
 * place, `NoteStall.STALL`), `s[2]` the Marks each player has bought (four hex digits of the uuid and a sum, to four players).
 *
 * **The endings.** A (paid, owed 0 on or before D5), B (time, cleared after the grace, D6 to D8), C (lease, owed 12 or less on the due day), W (sealed,
 * owed over 12). The ending a tale is going to is fixed when the sum is cleared or the due day comes (`n[5]`) and shown at step 3 by the scene
 * ([NoteStages]), after which the scene ends the tale; with nobody to see it the world's way ends it at the next close. [end] does the rest:
 * the debtor's ways, the favour at C, the stall's seal for ten market days ([NoteStall]), and the surety's broken word.
 */
object Note : Template, TaleVoice {
    override val id: String = "note"
    override val size: Int = 2

    /** D3, D5, the due day (D5), and the day of the ending: the longest route, with a grace, is the ending on D9, and the tale's steps do not last more than five days. */
    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 3, hears = setOf(Event.Kind.DAY)),
        Step(Verb.GIVE, by = 5, real = true, also = listOf(Verb.CHOOSE), hears = setOf(Event.Kind.BOUGHT, Event.Kind.GAVE)),
        Step(Verb.WAIT, by = 5, hears = setOf(Event.Kind.DAY)),
        Step(Verb.SCENE, by = 6, hears = setOf(Event.Kind.DAY), needs = { Tales.scenes() }),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "paid"),
        Ending(Ending.B, "time"),
        Ending(Ending.C, "lease"),
        Ending(Ending.W, "sealed"),
    )

    /** The world's way at the due day leaves the times a reason of -1 for 3 days (6.1). */
    override val hurts: Boolean = true

    /** The lender is any noble (or innkeeper), the neighbour any keeper of the next stall: a stand-in of the same trade takes their place. */
    override val standing: Set<String> = setOf("lender", "neighbour")

    override val numbers: List<String> = listOf("owed", "bought", "cut", "grace", "gold", "ending", "noseal")
    override val strings: List<String> = listOf("surety", "stall", "tally")

    override fun needs(): Boolean = Fortunes.enabled()

    // ---- The numbers ----

    private const val SUM = 24
    private const val OWED = 0
    private const val BOUGHT = 1
    private const val CUT = 2
    private const val GRACE = 3
    private const val GOLD = 4
    private const val ENDING = 5
    private const val NOSEAL = 6
    private const val SURETY = 0
    private const val TALLY = 2

    /** The most Marks bought at her stall that count, the cut a faction's word takes off, the Marks bought (a player's own) that earn the bit. */
    private const val BOUGHT_MAX = 12
    private const val CUT_MARKS = 6
    private const val BUY_BIT = 6

    /** The due day (D5) and with a grace (D8); the owed that is a lease and not a seal (12). */
    private const val DUE = 5
    private const val DUE_GRACE = 8
    private const val LEASE_AT = 12

    /** The standing a broken word costs the surety. */
    private const val STANDING_LOSS = 10

    /** The last days the times are read over, how many of them must be lean or hard. */
    private const val DAYS_SEEN = 5
    private const val LEAN_NEEDED = 3

    private const val AT_ASK = 0
    private const val AT_GIVE = 1
    private const val AT_WAIT = 2
    private const val AT_SCENE = 3

    private fun owed(t: Tale): Int = t.n[OWED]

    /** Works out `n[0]` from what has gone in. */
    private fun recount(t: Tale): Int {
        t.n[OWED] = (SUM - t.n[BOUGHT] - t.n[CUT] - t.n[GOLD]).coerceAtLeast(0)
        return t.n[OWED]
    }

    /** The market day the note falls due on: D5, or D8 after a grace. */
    private fun dueDay(t: Tale): Long = t.d0 + if (t.n[GRACE] != 0) DUE_GRACE else DUE

    /** Whole market days to the due day (0: today). */
    private fun dueIn(t: Tale, today: Long): Long = (dueDay(t) - today).coerceAtLeast(0L)

    private fun stallKey(t: Tale): Long? = t.s[NoteStall.STALL].toLongOrNull()

    private fun first(name: String): String = name.substringBefore(' ')

    private fun letter(code: Int): String = Ending.LETTERS.getOrElse(code - 1) { Ending.W }

    private fun code(letter: String): Int = Ending.LETTERS.indexOf(letter) + 1

    /** The ending the tale is going to: fixed already, else what the debt says now. */
    private fun planned(t: Tale): String {
        if (t.n[ENDING] in 1..4) return letter(t.n[ENDING])
        val owed = owed(t)
        return if (owed <= 0) Ending.A else if (owed <= LEASE_AT) Ending.C else Ending.W
    }

    // ---- Seeding ----

    /** The market stall the resident [r] keeps, by the plan: the stall whose keeper's place is their post. Null for anyone who keeps none. */
    private fun stallOf(c: Seeding, r: Resident): CityPlaces.Stall? {
        val planned = c.here.places.census.residents.getOrNull(r.planned) ?: return null
        if (planned.folk != Folk.MERCHANT) return null
        val post = planned.post ?: return null
        val at = c.here.capital.world(post.x, post.y, post.z)
        return c.here.places.stalls.firstOrNull { it.keeper.pos.distSqr(at) <= 4.0 }
    }

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val days = Fortunes.days(level, c.capital).takeLast(DAYS_SEEN)
        val lean = days.count { it.times == Times.LEAN || it.times == Times.HARD }
        var madeUp = ""
        if (lean < LEAN_NEEDED) {
            if (!c.forced) return c.none("only $lean of the last ${days.size} closed days were lean or hard (the note wants $LEAN_NEEDED of $DAYS_SEEN)")
            madeUp = "the lean days (only $lean of the last ${days.size} were lean or hard)"
        }
        val pool = TaleCast.pool(level, c.here.capital.center)
        val keepers = pool.filter { it.role == TownRole.MERCHANT }.mapNotNull { r -> stallOf(c, r)?.let { r to it } }
        val now = level.gameTime
        val debtors = keepers.filter { (_, stall) ->
            Shop.of(stall.trade) != null && !ShutStall.isShut(stall) && Trades.wares(c.entry, stall.trade).isNotEmpty() &&
                !NoteStall.sealed(stall.keeper.pos.asLong(), now)
        }
        if (debtors.isEmpty()) return c.none("no free stall keeper has an open stall with goods to sell")
        val (debtor, stall) = debtors[c.random.nextInt(debtors.size)]
        val lenders = pool.filter { it.role == TownRole.NOBLE && it.id != debtor.id }.ifEmpty { pool.filter { it.role == TownRole.INNKEEPER && it.id != debtor.id } }
        if (lenders.isEmpty()) return c.none("no noble or innkeeper is free to hold the note")
        val lender = lenders[c.random.nextInt(lenders.size)]
        // The next stall's keeper, if any is free and near: any free adult nearby does their part otherwise
        val neighbour = keepers.filter { (r, other) -> r.id != debtor.id && other !== stall && other.keeper.pos.distSqr(stall.keeper.pos) <= NoteStages.NEIGHBOUR_REACH * NoteStages.NEIGHBOUR_REACH }
            .minByOrNull { (_, other) -> other.keeper.pos.distSqr(stall.keeper.pos) }?.first
        return Planned(
            cast = listOfNotNull(
                Cast("debtor", debtor.id, debtor.name),
                Cast("lender", lender.id, lender.name),
                neighbour?.let { Cast("neighbour", it.id, it.name) },
            ),
            why = Why("note.lean"),
            step = AT_ASK,
            n = listOf(SUM, 0, 0, 0, 0, 0, 0),
            s = listOf("", stall.keeper.pos.asLong().toString(), ""),
            madeUp = madeUp,
        )
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        when (t.step) {
            // The debt is clear: the world's way is the next close, and the lender comes in the morning
            AT_WAIT -> t.by = c.day
            AT_SCENE -> {
                // The ending is shown on the day it is entered, 9:00 to 17:00; the lender is held near the stall to come to it
                t.by = c.day
                holdLender(c, t)
            }
        }
    }

    /** Holds the lender a few blocks from the stall, out in the street, for the morning. */
    private fun holdLender(c: Ctx, t: Tale) {
        val stall = stallOf(c, t) ?: return
        val back = stall.customer.facing.opposite
        val mark = listOf(9, 7, 5, 3).firstNotNullOfOrNull { Sets.standable(c.level, stall.customer.pos.relative(back, it)) }
        c.hold(t, "lender", mark)
    }

    private fun stallOf(c: Ctx, t: Tale): CityPlaces.Stall? {
        val key = stallKey(t) ?: return null
        return c.here.places.stalls.firstOrNull { it.keeper.pos.asLong() == key }
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        if (t.step != AT_GIVE) return
        when (e) {
            is Event.Bought -> if (e.buying && e.keeper >= 0 && e.keeper == t.resident("debtor")) bought(c, t, e)
            is Event.Gave -> gave(c, t, e)
            else -> {}
        }
    }

    /** Marks a player spent at the debtor's stall count one for one up to twelve, if they have taken the note up. */
    private fun bought(c: Ctx, t: Tale, e: Event.Bought) {
        val part = t.part(e.player.uuid) ?: return
        if (part.status != Part.Status.IN && part.status != Part.Status.DONE) return
        val count = minOf(e.marks, BOUGHT_MAX - t.n[BOUGHT])
        if (count <= 0) return
        t.n[BOUGHT] += count
        val mine = tally(t, e.player.uuid, count)
        if (mine >= BUY_BIT && !part.did(Part.GAVE)) {
            part.did = part.did or Part.GAVE
            c.why(t, "note.bought", e.player.gameProfile.name, mine.toString())
        }
        val owed = recount(t)
        c.data.changed()
        c.say(t, "debtor", "tale.note.progress")
        if (owed <= 0) cleared(c, t, e.player.uuid)
    }

    /** Marks a player put in through the debtor's script (held until the sum is met, and given back if the step lapses). */
    private fun gave(c: Ctx, t: Tale, e: Event.Gave) {
        if (e.tale != t.id || e.marks <= 0) return
        val before = owed(t)
        val part = t.part(e.player.uuid) ?: return
        t.n[GOLD] += e.marks
        // A quarter of what was left earns the bit
        if (e.marks * 4 >= before) part.did = part.did or Part.GAVE
        val owed = recount(t)
        c.data.changed()
        if (owed <= 0) cleared(c, t, e.player.uuid)
    }

    /**
     * The sum is met: the ending is A, or B if a grace was given and it is past the due day; the deciding deed is the clearing player's, or the
     * surety's for B (the grace is what made it); the lender comes to the stall in the morning.
     */
    private fun cleared(c: Ctx, t: Tale, by: UUID?) {
        val late = t.n[GRACE] != 0 && c.day > t.d0 + DUE
        t.n[ENDING] = code(if (late) Ending.B else Ending.A)
        val decider = (if (late) surety(t) else null) ?: by
        decider?.let { id -> t.part(id)?.let { it.did = it.did or Part.DECIDED } }
        c.why(t, "note.cleared")
        c.go(t, AT_WAIT)
    }

    private fun surety(t: Tale): UUID? = try {
        UUID.fromString(t.s[SURETY])
    } catch (e: IllegalArgumentException) {
        null
    }

    /** What each player has bought at the stall: `s[2]` is "abcd=6;ef01=3", the first four hex digits of the uuid and the Marks, for the last four players. Adds [add] to [player]'s and answers their total. */
    private fun tally(t: Tale, player: UUID, add: Int): Int {
        val tag = player.toString().take(4)
        val sums = LinkedHashMap<String, Int>()
        for (entry in t.s[TALLY].split(';')) {
            val at = entry.indexOf('=')
            if (at <= 0) continue
            sums[entry.substring(0, at)] = entry.substring(at + 1).toIntOrNull() ?: continue
        }
        val total = (sums.remove(tag) ?: 0) + add
        sums[tag] = total
        while (sums.size > 4) sums.remove(sums.keys.first())
        t.setS(TALLY, sums.entries.joinToString(";") { "${it.key}=${it.value}" })
        return total
    }

    /** The world's way (1.4): the note falls due on its day, whoever has taken it up. */
    override fun settle(c: Ctx, t: Tale) {
        when (t.step) {
            // Nobody took it up: the note falls due all the same, and a player may still take it up
            AT_ASK -> c.go(t, AT_GIVE)
            AT_GIVE -> fallsDue(c, t)
            AT_WAIT -> c.go(t, AT_SCENE)
            else -> c.end(t, planned(t))
        }
    }

    /** The due day has come with the sum not met: the gold put in has been given back, and what is owed says lease or seal (8.1, "The world's way"). */
    private fun fallsDue(c: Ctx, t: Tale) {
        t.n[GOLD] = 0
        val owed = recount(t)
        c.data.changed()
        if (owed <= 0) {
            // Met by what was bought and cut alone, and the day has come: the lender need not wait another morning
            t.n[ENDING] = code(if (t.n[GRACE] != 0 && c.day > t.d0 + DUE) Ending.B else Ending.A)
            c.why(t, "note.cleared")
            c.go(t, AT_SCENE)
            return
        }
        t.n[ENDING] = code(if (owed <= LEASE_AT) Ending.C else Ending.W)
        c.why(t, "note.due", owed.toString())
        c.go(t, AT_SCENE)
    }

    // ---- The scenes ----

    /** The people of the ending at the stall, or null if the stall, the keeper or the lender is not about and near. */
    private fun folk(c: Ctx, t: Tale): NoteStages.Folk? {
        val level = c.level
        val stall = stallOf(c, t) ?: return null
        val front = Vec3.atCenterOf(stall.customer.pos)
        val debtor = TaleBodies.body(level, t, "debtor")?.takeIf { it.distanceToSqr(Vec3.atCenterOf(stall.keeper.pos)) <= AT_STALL * AT_STALL } ?: return null
        val lender = TaleBodies.body(level, t, "lender")?.takeIf { it.distanceToSqr(front) <= LENDER_REACH * LENDER_REACH } ?: return null
        val neighbour: Townsperson? = TaleBodies.body(level, t, "neighbour")
        return NoteStages.Folk(level, stall, debtor, lender, neighbour, t.cast("debtor")?.name.orEmpty(), t.cast("lender")?.name.orEmpty())
    }

    /** How near the debtor's body is to her counter (blocks) to be at her stall, and how near the lender's is to be on his way to it. */
    private const val AT_STALL = 3.0
    private const val LENDER_REACH = 40.0

    override fun scene(c: Ctx, t: Tale): Stage? {
        // Outside the hours nothing is made (the ending's fire is looked for, the people counted): the stage would be let go at once
        if (t.step != AT_ASK && !Stage.within(c.level, NoteStages.HOURS)) return null
        if (!t.open) {
            // The ending seen, once, when no scene of step 3 showed it already
            if (t.id in NoteStall.shown) return null
            return folk(c, t)?.let { NoteStages.ending(c, it, t.ending) }
        }
        return when (t.step) {
            AT_ASK -> counting(c, t)
            AT_SCENE -> ending(c, t)
            else -> null
        }
    }

    /** S1 The Count at her stall, 10:00 to 16:00: she stays at her counter, a passer-by comes to comfort her. */
    private fun counting(c: Ctx, t: Tale): Stage? {
        val stall = stallOf(c, t) ?: return null
        val keeper = TaleBodies.body(c.level, t, "debtor")?.takeIf { it.distanceToSqr(Vec3.atCenterOf(stall.keeper.pos)) <= AT_STALL * AT_STALL } ?: return null
        return Sets.count(keeper, Sets.passerBy(keeper), hours = 10.0..16.0)
    }

    /** The scene of the ending the tale is going to, at step 3; when it has played the tale ends (and, if nobody saw it, the next close ends it). */
    private fun ending(c: Ctx, t: Tale): Stage? {
        val folk = folk(c, t) ?: return null
        val letter = planned(t)
        val stage = NoteStages.ending(c, folk, letter) ?: return null
        val level = c.level
        val capital = t.capital
        stage.then = { played ->
            if (played) {
                TaleEngine.withCtx(level, capital, "the end of the note tale #${t.id}") { later ->
                    if (t.open && t.step == AT_SCENE) {
                        NoteStall.shown += t.id
                        later.end(t, letter)
                    }
                }
            }
        }
        return stage
    }

    // ---- The consequences of an ending ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        val key = stallKey(t)
        val noSeal = t.n[NOSEAL] != 0
        when (ending) {
            Ending.A -> c.flip(t, "debtor", Trait.DILIGENT)
            Ending.C -> {
                c.flip(t, "debtor", Trait.GRUMPY)
                // The town's thanks to those who helped, a lesser one than the good ending's (6.6)
                for (part in t.parts) if (part.did != 0) c.favour(t, "debtor", part.player, Favour.TALE_HELPED)
                if (key != null && shows(c, t)) NoteStall.hold(c.level, key)
            }
            Ending.W -> {
                if (!noSeal) {
                    c.flip(t, "debtor", Trait.GRUMPY)
                    // The seal: the record now, the awning let down by her in the scene if one is to show it
                    if (key != null) NoteStall.seal(c, key, staged = shows(c, t))
                }
            }
        }
        if (ending == Ending.C || ending == Ending.W) brokenWord(c, t)
    }

    /** Whether a scene of the ending is to be played at the stall, so that the stall keeps its look until it shows it (a player is near, the people are about). */
    private fun shows(c: Ctx, t: Tale): Boolean {
        if (!Tales.scenes() || t.id in NoteStall.shown) return false
        val f = folk(c, t) ?: return false
        return c.level.getNearestPlayer(f.front.x, f.front.y, f.front.z, TaleLook.NEAR, false) != null
    }

    /** The surety's word is broken when the note runs out unpaid after a grace: a slight from the lender and the debtor, and ten off their standing here. */
    private fun brokenWord(c: Ctx, t: Tale) {
        if (t.n[GRACE] == 0) return
        val who = surety(t) ?: return
        val name = TaleEngine.playerName(c.level, who)
        c.why(t, "note.broken", name)
        for (slot in listOf("lender", "debtor")) c.resident(t, slot)?.takeIf { it.alive }?.let { TownTalk.slight(c.level, it, who, Slight.WORD_BROKEN) }
        val board = c.here.capital.boardAnchor
        val player = c.level.server.playerList.getPlayer(who)
        if (player != null) Standing.add(c.level, board, player, -STANDING_LOSS) else Standing.board(c.level, board)?.addStanding(who, -STANDING_LOSS)
    }

    // ---- Those who go ----

    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            // The keeper: the tale ends with the stall left to whoever the comings send, no seal
            "debtor" -> {
                t.n[NOSEAL] = 1
                c.data.changed()
                c.end(t, Ending.W)
            }
            "lender" -> holder(c, t)
            else -> {}
        }
    }

    /** The lender is gone and no noble or innkeeper stands in: a keeper of another stall holds the note, and with none the tale ends with no seal. */
    private fun holder(c: Ctx, t: Tale) {
        val cast = t.cast("lender") ?: return
        val taken = t.cast.mapTo(HashSet()) { it.resident }
        val pool = TaleCast.pool(c.level, c.here.capital.center).filter { it.role == TownRole.MERCHANT && it.id !in taken }
        val next = pool.minByOrNull { it.id }
        if (next == null) {
            t.n[NOSEAL] = 1
            c.why(t, "note.no_holder")
            c.data.changed()
            c.end(t, Ending.W)
            return
        }
        val wasHeld = cast.held
        cast.resident = next.id
        cast.name = next.name
        cast.gone = ""
        cast.held = false
        c.why(t, "note.holder", next.name)
        c.data.changed()
        TaleEngine.reindex()
        if (wasHeld || t.step == AT_SCENE) holdLender(c, t)
    }

    override fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {
        if (slot == "lender" && wasHeld && t.step == AT_SCENE) holdLender(c, t)
    }

    // ---- The talk ----

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    override fun asker(t: Tale): String = "debtor"

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val lender = TaleLines.lit(turn.name("lender"))
        val due = TaleLines.due(dueIn(t, turn.today))
        return when (beat) {
            "ask" -> TaleLines.facts("talk.detail.tale.note.ask", owed(t), lender, due)
            "passing" -> TaleLines.facts("talk.detail.tale.note.passing", lender, due)
            "rumour" -> TaleLines.facts("talk.detail.tale.note.rumour", TaleLines.lit(turn.name("debtor")), lender, due)
            "stand" -> when {
                t.step < AT_WAIT -> TaleLines.facts("talk.detail.tale.note.stand", SUM - owed(t), SUM, due)
                t.step == AT_WAIT -> TaleLines.facts("talk.detail.tale.note.stand.2", lender)
                planned(t) == Ending.A || planned(t) == Ending.B -> TaleLines.facts("talk.detail.tale.note.stand.met", lender)
                else -> TaleLines.facts("talk.detail.tale.note.stand.3", lender)
            }
            else -> null
        }
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = if (beat == "ask" || beat == "passing") Activity.SIGH else null

    override fun yes(turn: TaleTurn): TaleSay = TaleSay("tale.note.thanks")

    override fun options(turn: TaleTurn): List<TaleOption> {
        if (turn.step != AT_GIVE || turn.mine == null) return listOf()
        return when (turn.slot) {
            "debtor" -> coverings(turn)
            "lender" -> tools(turn)
            else -> listOf()
        }
    }

    // ---- Her script: the sums to cover, and where to go ----

    private fun coverings(turn: TaleTurn): List<TaleOption> {
        val owed = owed(turn.tale)
        if (owed <= 0) return listOf()
        val out = ArrayList<TaleOption>()
        for (marks in intArrayOf(6, 12)) if (marks < owed) out += cover("cover$marks", marks, rest = false)
        out += cover("rest", owed, rest = true)
        out += lender(turn)
        return out
    }

    private fun cover(id: String, marks: Int, rest: Boolean): TaleOption {
        val label = Component.translatable(if (rest) "talk.guildmark.choice.tale.note.cover_rest" else "talk.guildmark.choice.tale.note.cover", marks)
        return TaleOption(id, label) { turn ->
            val t = turn.tale
            if (t.step != AT_GIVE || owed(t) < marks || rest && owed(t) != marks) return@TaleOption null
            when (TaleOptions.check(turn, marks)) {
                TaleOptions.Put.SHORT -> return@TaleOption TaleReply(turn.family("short"), gesture = Activity.SHRUG)
                TaleOptions.Put.LATER -> return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
                else -> {}
            }
            // The reason first, so that what the next step adds is newer than it
            val why = Why("note.gave", listOf(turn.who, marks.toString()))
            t.addWhy(why)
            if (TaleOptions.put(turn, marks) != TaleOptions.Put.DONE) {
                if (t.why.lastOrNull() === why) t.why.removeAt(t.why.size - 1)
                return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
            }
            val paid = t.step != AT_GIVE
            val name = TaleLines.lit(turn.name("debtor"))
            val say = if (paid) turn.say("paid", TaleLines.facts("talk.detail.tale.note.paid", name))
            else turn.say("stand", TaleLines.facts("talk.detail.tale.note.stand", SUM - owed(t), SUM, TaleLines.due(dueIn(t, turn.today))))
            TaleReply(listOf(say), if (paid) Activity.THANK else Activity.NOD)
        }
    }

    /** "I'll go to Orrin.": she points the way to the lender, who keeps the square from one till five. */
    private fun lender(turn: TaleTurn): TaleOption {
        val name = turn.name("lender")
        return TaleOption("lender", Component.translatable("talk.guildmark.choice.tale.note.lender", first(name))) { t ->
            if (t.step != AT_GIVE) return@TaleOption null
            val door = t.ctx()?.let { c -> c.resident(t.tale, "lender")?.let { r -> c.here.places.lodgings[r.home]?.front?.pos } }
            TaleReply(
                listOf(t.say("where", TaleLines.facts("talk.detail.tale.note.where", TaleLines.lit(name)))),
                Activity.POINT, door,
            )
        }
    }

    // ---- His script: the tools ----

    private fun tools(turn: TaleTurn): List<TaleOption> {
        val player = turn.player ?: return listOf()
        val out = ArrayList<TaleOption>()
        out += hard()
        if (turn.tale.n[GRACE] == 0 && known(turn)) out += grace()
        val state = Factions.state(player)
        if (state.member(Faction.STARWATCH)) out += cut("sums")
        if (state.member(Faction.HEARTHSWORN)) out += cut("law")
        if (state.member(Faction.QUIET_HAND)) out += cut("word")
        return out
    }

    /** Whether the player is Known (25 or more) in this town. */
    private fun known(turn: TaleTurn): Boolean {
        val player = turn.player ?: return false
        val board = TaleOptions.board(turn) ?: return false
        return Standing.tier(turn.level, board, player) >= StandingTier.KNOWN
    }

    /** "How much is it?": the sum and the days. */
    private fun hard() = TaleOption("hard", Component.translatable("talk.guildmark.choice.tale.note.hard")) { turn ->
        val t = turn.tale
        if (t.step != AT_GIVE) return@TaleOption null
        TaleReply(
            listOf(turn.say("lender_hard", TaleLines.facts("talk.detail.tale.note.lender_hard", owed(t), TaleLines.due(dueIn(t, turn.today))))),
            Activity.EXPLAIN,
        )
    }

    /** "Give them a few days. I'll answer for it.": the due day moves to D8, and the player stands surety. */
    private fun grace() = TaleOption("grace", Component.translatable("talk.guildmark.choice.tale.note.grace")) { turn ->
        val t = turn.tale
        if (t.step != AT_GIVE || t.n[GRACE] != 0) return@TaleOption null
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val player = turn.player ?: return@TaleOption null
        if (!known(turn)) return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        t.n[GRACE] = 1
        t.by = t.d0 + DUE_GRACE
        t.setS(SURETY, player.uuid.toString())
        turn.mine?.let { it.did = it.did or Part.CHOSE }
        t.addWhy(Why("note.grace", listOf(turn.who, turn.name("lender"))))
        c.data.changed()
        TaleReply(
            listOf(turn.say("lender_grace", TaleLines.facts("talk.detail.tale.note.lender_grace", TaleLines.due(dueIn(t, turn.today))))),
            Activity.NOD,
        )
    }

    /** A faction's word takes 6 Marks off: once, and only one of the three applies (the second reads "It's been mended already."). */
    private fun cut(tool: String) = TaleOption(tool, Component.translatable("talk.guildmark.choice.tale.note.$tool")) { turn ->
        val t = turn.tale
        if (t.step != AT_GIVE) return@TaleOption null
        if (t.n[CUT] != 0) return@TaleOption TaleReply(turn.family("mended"), gesture = Activity.SHRUG)
        val c = turn.ctx() ?: return@TaleOption TaleReply("ask.think", gesture = Activity.THINK)
        val player = turn.player ?: return@TaleOption null
        t.n[CUT] = CUT_MARKS
        turn.mine?.let { it.did = it.did or Part.CHOSE }
        t.addWhy(Why("note.cut", listOf(turn.who)))
        val owed = recount(t)
        c.data.changed()
        if (owed <= 0) cleared(c, t, player.uuid)
        TaleReply(listOf(turn.say("lender_$tool", TaleLines.facts("talk.detail.tale.note.cut", CUT_MARKS))), Activity.NOD)
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val title = TaleKeys.title(id)
        val target = stallKey(t)?.let { BlockPos.of(it) }
        val name = first(t.cast("debtor")?.name.orEmpty())
        return when (t.step) {
            AT_ASK -> View(title, status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(name), target = target)
            AT_GIVE -> View(title, status = TaleKeys.status(id, AT_GIVE), statusArgs = listOf(owed(t).toString()), target = target)
            AT_WAIT -> View(title, status = TaleKeys.status(id, AT_WAIT), target = target)
            else -> View(title, status = TaleKeys.status(id, AT_SCENE), statusArgs = listOf(name), tone = View.Tone.DONE, target = target)
        }
    }
}
