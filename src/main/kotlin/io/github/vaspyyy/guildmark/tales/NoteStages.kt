package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.ShutStall
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Await
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Fx
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Hold
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Rise
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Stand
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.streets.Awnings
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * The scenes of the Marlow note's four endings (living-world/stories/design.md 8.1), Tales T1a: what the lender and the keeper do at her
 * stall on the morning the note is settled. Each is a [Stage] made from the sets' parts (`Sets.prop`, `Sets.fit`, `Sets.fireNear`, the
 * walls of [Stage]) but with the stall's own geometry, which the sets' general handing does not have: the keeper stays at her counter
 * and the lender stands at the customer's place across it, two blocks off, as a shopper does (the handing of goods over a counter), so
 * nobody is walked onto the counter. They are played by `TaleLook` for the tale at step 3 (the note's SCENE) and again, once, for the
 * tale that has just ended by a command or the world's way with a player near; `tale scene note|paper|lease|seal` plays any by hand.
 *
 * - **A, the Burning:** the lender hands her the sealed note over the counter and says they are quits; she counts it, leaves her counter and
 *   carries it to the nearest oven, hearth or stove (`Sets.fire`) and feeds it to the fire (FEED_FIRE: flame, ash and a ribbon of smoke,
 *   `tale.note_burn`), says it is done; the neighbour at her stall and two passers who came to the fire cheer and clap. With no fire in
 *   reach, the quiet paper of B with the same glad faces.
 * - **B, the quiet paper:** the same handing, no fire: the note is tucked away, she says it is paid, and the neighbour claps.
 * - **C, the lease:** the lender says the stall is his and hands her the paper to sign; she reads, signs (SCRIBE) and hands it back; he stamps
 *   it (STAMP, the seal pressed) and the slate on her stall is chalked "his lease". No cheer.
 * - **W, the Seal:** the lender comes to the stall and points up at the awning; she says her piece and lets it down herself (SHUT_STALL, the
 *   cord pulled at tick 6, the record sealed and the awning rolled by `ShutStall.letDown`), stands a moment grieving (GRIEVE); two passers
 *   who came up look on. Afterwards her own day has her sit by the shut stall as a sold-out stall's keeper does.
 */
internal object NoteStages {
    /** The hours the lender is about the stalls for it, and the hours a note's ending is played in. */
    val HOURS: ClosedFloatingPointRange<Double> = 9.0..17.0

    /** How far a fire is looked for from the keeper (blocks), and how far the passers who look on are drawn from (blocks). */
    private const val FIRE_REACH = 24
    private const val WATCH_REACH = 18.0

    /** The tick of SHUT_STALL the cord gives on (the stall's own routine pulls at the same one). */
    private const val PULL_AT = 6

    /** The tick of STAMP the seal is pressed. */
    private const val STAMP_AT = 8

    /** The people and the place of one ending's scene. */
    class Folk(
        val level: ServerLevel,
        val stall: CityPlaces.Stall,
        val debtor: Townsperson,
        val lender: Townsperson,
        val neighbour: Townsperson?,
        val debtorName: String,
        val lenderName: String,
    ) {
        /** The stall's key: its keeper's place. */
        val key: Long get() = stall.keeper.pos.asLong()

        /** Where a shopper stands to be served: the lender's place at the counter. */
        val front: Vec3 get() = Vec3.atCenterOf(stall.customer.pos)

        /** Where the keeper reaches for the awning's cord: up over the counter's middle (the stall's own routine looks there). */
        val awning: Vec3
            get() {
                val middle = stall.counter.getOrNull(stall.counter.size / 2) ?: stall.keeper.pos.relative(stall.keeper.facing)
                return Jobs.top(middle, 1.0).add(0.0, 1.2, 0.0)
            }
    }

    /** The scene of the ending [letter] ("A", "B", "C" or "W") for [f], or null if there is nowhere to play it. [c] is the engine's world for the pieces that change the record. */
    fun ending(c: Ctx?, f: Folk, letter: String): Stage? = when (letter) {
        Ending.A -> burning(f)
        Ending.B -> paper(f, glad = false)
        Ending.C -> lease(f)
        else -> seal(c, f)
    }

    // ---- The pieces ----

    /** A part for someone who stays where they stand: a keeper at the counter, or someone seated, is played in place and goes back to it. */
    private fun still(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves, station = body.seat != null || body.routine.anchored, seated = body.seat != null)

    /** A part for someone who walks about in it. */
    private fun walker(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves)

    private fun eye(p: Townsperson): () -> Vec3? = { p.eyePosition }

    /** Up to [n] free people of the street near the stall, other than the cast, who come to look on. */
    private fun crowd(f: Folk, n: Int): List<Townsperson> =
        Sets.freeAdults(f.level, f.front, WATCH_REACH, listOfNotNull(f.debtor, f.lender, f.neighbour)).take(n)

    /** The lender comes to the shopper's place, holding the paper if [carry], turns to the keeper and waits for her. */
    private fun arrive(f: Folk, carry: Boolean): List<Move> = listOf(
        GoTo({ f.stall.customer.pos }, Jobs.WALK, near = 0.7, overlay = if (carry) Activity.CARRY else Activity.NONE, most = 600),
        Turn(eye(f.debtor)),
        Barrier("met", most = 700, at = eye(f.debtor)),
    )

    /** The keeper turns to the lender as he comes, and waits for him. */
    private fun await(f: Folk): List<Move> = listOf(Turn(eye(f.lender)), Barrier("met", most = 700, at = eye(f.lender)))

    /** The lender holds a paper out over the counter (HAND_OVER, the paper out of his hand at tick 16). */
    private fun handOver(f: Folk): List<Move> = listOf(
        Beat(Activity.HAND_OVER, at = eye(f.debtor)) { p, tick -> if (tick == 16) Stage.give(p, null) },
        Barrier("handed", most = 120, at = eye(f.debtor)),
    )

    /** The keeper takes it (RECEIVE, the paper in her hand at tick 12). */
    private fun receive(f: Folk, paper: ItemStack): List<Move> = listOf(
        Beat(Activity.RECEIVE, at = eye(f.lender)) { p, tick -> if (tick == 12) Stage.give(p, paper) },
        Barrier("handed", most = 120, at = eye(f.lender)),
    )

    /**
     * A look-on of the street who comes up to within [gap] blocks of [near] to watch [whom], glances to what they see (GAZE) until the
     * barrier [barrier] is passed, and then does [then] in turn with the others ([i]); a bystander, not a part, whom nothing waits for.
     */
    private fun looker(f: Folk, who: Townsperson, i: Int, near: Vec3, gap: Double, whom: Townsperson, barrier: String, then: List<Move>): Stage.Part {
        val spot = Sets.beside(f.level, near, who.position(), gap)
        val m = ArrayList<Move>()
        if (spot != null) m += GoTo({ spot }, Jobs.STROLL, near = 1.0, most = 500)
        m += Turn(eye(whom))
        m += Await(barrier, 1800, Activity.GAZE, eye(whom))
        m += Wait({ _, tick -> tick >= 4 * (i + 1) }, most = 20)
        m += then
        return walker("watcher${i + 1}", who, m, essential = false)
    }

    /** The neighbour, at their own stall, who looks on across the way (GAZE) until [barrier] is passed, and then does [then]. */
    private fun neighbour(f: Folk, barrier: String, then: List<Move>): Stage.Part? {
        val n = f.neighbour ?: return null
        val m = ArrayList<Move>()
        m += Turn(eye(f.debtor))
        m += Await(barrier, 1800, Activity.GAZE, eye(f.debtor))
        m += then
        return still("neighbour", n, m, essential = false)
    }

    // ---- A, the Burning ----

    /**
     * The fire a paper is burnt in, as `Sets.handing` has it: the block ([at], whose middle is [fire]), the cell to stand in before it
     * ([stand]) and [spot], the exact place FEED_FIRE is made for: the middle of that cell, half a block from the fire's front face.
     */
    private class Burn(val at: BlockPos, val stand: Spot) {
        val spot: Vec3 = Vec3(stand.pos.x + 0.5, stand.pos.y.toDouble(), stand.pos.z + 0.5)
        val fire: Vec3 = Vec3.atCenterOf(at)

        /** Where the paper takes, for [p] standing on the spot facing the fire (7 px ahead of her middle and 9.8 px above the floor). */
        fun flame(p: Townsperson): Vec3 = Vec3(p.x + stand.facing.stepX * AHEAD, p.y + UP, p.z + stand.facing.stepZ * AHEAD)

        companion object {
            const val AHEAD = 7.0 / 16.0
            const val UP = 9.8 / 16.0

            /** The ticks of the clip: the paper takes at 14, is gone from the hand at 24, and the flame is seen to 34. */
            const val TAKES = 14
            const val GONE = 24
            const val LAST = 34
        }
    }

    private fun burning(f: Folk): Stage? {
        val level = f.level
        val burn = Sets.fireNear(level, f.debtor.blockPosition(), FIRE_REACH)
            ?.let { fire -> Places.beside(level, fire, f.debtor.blockPosition())?.let { Burn(fire, it) } }
            ?: return paper(f, glad = true)
        val note = Sets.prop("note_sealed")
        val paid = TaleLines.facts("talk.detail.tale.note.paid", TaleLines.lit(f.debtorName))
        val l = ArrayList<Move>()
        l += Hold(note)
        l += arrive(f, carry = true)
        l += handOver(f)
        l += Say("tale.note.quits", gesture = Activity.NONE, at = eye(f.debtor))
        l += Turn(eye(f.debtor))
        l += Barrier("burnt", most = 1500, pose = Activity.ARMS_FOLDED, at = eye(f.debtor))
        l += Play(Activity.CHEER)
        l += Play(Activity.CLAP)
        val d = ArrayList<Move>()
        d += await(f)
        d += receive(f, note)
        // She counts it over, nods, and takes it to the fire
        d += Play(Activity.COUNT, 60)
        d += Play(Activity.NOD, at = eye(f.lender))
        d += Rise()
        d += GoTo({ burn.stand.pos }, Jobs.WALK, near = 0.6, overlay = Activity.CARRY, most = 400)
        d += Stand({ burn.spot }, { burn.fire }, overlay = Activity.CARRY)
        d += Beat(Activity.FEED_FIRE, at = { burn.fire }) { p, tick ->
            // The paper takes at tick 14 (flame, smoke and ash at the paper, then every few ticks while it burns) and is gone from the hand by 24
            if (tick >= Burn.TAKES && tick <= Burn.LAST && (tick - Burn.TAKES) % Fx.STEP == 0) {
                Fx.play(p.level() as ServerLevel, Fx.Kind.BURN, burn.flame(p), tick - Burn.TAKES)
            }
            if (tick == Burn.GONE) Stage.give(p, null)
        }
        d += Fx(Fx.Kind.HAPPY, { p -> p.position().add(0.0, 1.9, 0.0) })
        d += Say("tale.note.paid", detail = paid, gesture = Activity.NONE, at = eye(f.lender))
        d += Barrier("burnt", most = 400, at = eye(f.lender))
        d += Play(Activity.NOD, at = eye(f.lender))
        val parts = listOf(walker("lender", f.lender, l), still("debtor", f.debtor, d))
        // The neighbour at their own stall, and two passers who hurry to the fire to see her do it, cheer with the lender
        val extras = ArrayList<Stage.Part>()
        neighbour(f, "burnt", listOf(Play(Activity.CHEER), Play(Activity.CLAP)))?.let { extras += it }
        for ((i, who) in crowd(f, 2).withIndex()) {
            extras += looker(f, who, i, burn.fire, 3.0, f.debtor, "burnt", listOf(Play(Activity.CHEER), Play(Activity.CLAP)))
        }
        return Stage("burning", parts, HOURS, f.front, extras = extras)
    }

    // ---- B, the quiet paper (and A with no fire) ----

    /**
     * The note handed over the counter with no fire: she counts it, puts it away and says it is paid; the neighbour claps. With [glad], the
     * glad faces of the Burning for an A with no fire in reach: two passers who came up cheer as well, and she is happy (green sparks).
     */
    private fun paper(f: Folk, glad: Boolean): Stage? {
        val note = Sets.prop("note_sealed")
        val paid = TaleLines.facts("talk.detail.tale.note.paid", TaleLines.lit(f.debtorName))
        val l = ArrayList<Move>()
        l += Hold(note)
        l += arrive(f, carry = true)
        l += handOver(f)
        l += Say("tale.note.quits", gesture = Activity.NONE, at = eye(f.debtor))
        l += Turn(eye(f.debtor))
        l += Barrier("tucked", most = 1200, pose = Activity.ARMS_FOLDED, at = eye(f.debtor))
        l += Play(Activity.NOD, at = eye(f.debtor))
        val d = ArrayList<Move>()
        d += await(f)
        d += receive(f, note)
        d += Play(Activity.COUNT, 60)
        // She folds it away
        d += Hold(ItemStack.EMPTY)
        d += Fx(Fx.Kind.HAPPY, { p -> p.position().add(0.0, 1.9, 0.0) })
        d += Say("tale.note.paid", detail = paid, gesture = Activity.NONE, at = eye(f.lender))
        d += Barrier("tucked", most = 600, at = eye(f.lender))
        d += Play(Activity.NOD, at = eye(f.lender))
        val parts = ArrayList<Stage.Part>()
        parts += walker("lender", f.lender, l)
        parts += still("debtor", f.debtor, d)
        val clap = if (glad) listOf(Play(Activity.CHEER), Play(Activity.CLAP)) else listOf(Play(Activity.CLAP))
        val extras = ArrayList<Stage.Part>()
        neighbour(f, "tucked", clap)?.let { extras += it }
        if (glad) {
            for ((i, who) in crowd(f, 2).withIndex()) {
                extras += looker(f, who, i, f.front, 4.0, f.debtor, "tucked", clap)
            }
        }
        return Stage(if (glad) "burning" else "paper", parts, HOURS, f.front, extras = extras.take(Stage.EXTRAS))
    }

    // ---- C, the lease ----

    private fun lease(f: Folk): Stage? {
        val paper = ItemStack(Items.PAPER)
        val surname = NoteStall.surname(f.lenderName)
        val line = TaleLines.facts("talk.detail.tale.note.lease", TaleLines.lit(surname))
        val l = ArrayList<Move>()
        l += Hold(paper)
        l += arrive(f, carry = true)
        l += Say("tale.note.lease", detail = line, gesture = Activity.NONE, at = eye(f.debtor))
        l += Barrier("said", most = 600, at = eye(f.debtor))
        l += handOver(f)
        l += Turn(eye(f.debtor))
        l += Barrier("signed", most = 1500, pose = Activity.ARMS_FOLDED, at = eye(f.debtor))
        l += Beat(Activity.RECEIVE, at = eye(f.debtor)) { p, tick -> if (tick == 12) Stage.give(p, paper) }
        l += Barrier("returned", most = 120, at = eye(f.debtor))
        l += Beat(Activity.STAMP, at = eye(f.debtor)) { p, tick ->
            if (tick == STAMP_AT) {
                val level = p.level() as ServerLevel
                // The seal pressed, and the stall's slate chalked as the keeper is seen with the paper
                Stage.Fx.sound(level, ModSounds.UI_SEAL.get(), f.front.add(0.0, 1.2, 0.0), 0.9f, 1.0f)
                Awnings.chalk(level, f.stall, surname)
                NoteStall.chalk(level, f.key, surname)
            }
        }
        l += Hold(ItemStack.EMPTY)
        l += Barrier("stamped", most = 200, at = eye(f.debtor))
        l += Play(Activity.NOD, at = eye(f.debtor))
        val d = ArrayList<Move>()
        d += await(f)
        d += Barrier("said", most = 700, pose = Activity.LISTEN, at = eye(f.lender))
        d += receive(f, paper)
        d += Beat(Activity.SCRIBE, 100, at = { f.front.add(0.0, 0.8, 0.0) }) { _, _ -> }
        d += Beat(Activity.HAND_OVER, at = eye(f.lender)) { p, tick -> if (tick == 16) Stage.give(p, null) }
        d += Barrier("returned", most = 120, at = eye(f.lender))
        d += Barrier("signed", most = 100, at = eye(f.lender))
        d += Barrier("stamped", most = 400, pose = Activity.ARMS_FOLDED, at = eye(f.lender))
        d += Play(Activity.SIGH)
        val parts = ArrayList<Stage.Part>()
        parts += walker("lender", f.lender, l)
        parts += still("debtor", f.debtor, d)
        val extras = ArrayList<Stage.Part>()
        neighbour(f, "stamped", listOf(Play(Activity.NOD)))?.let { extras += it }
        return Stage("lease", parts, HOURS, f.front, extras = extras)
    }

    // ---- W, the Seal ----

    private fun seal(c: Ctx?, f: Folk): Stage? {
        val level = f.level
        val closed = TaleLines.facts("talk.detail.tale.note.sealed", TaleLines.lit(f.debtorName), NoteStall.DAYS)
        val l = ArrayList<Move>()
        l += GoTo({ f.stall.customer.pos }, Jobs.WALK, near = 0.7, most = 600)
        l += Turn(eye(f.debtor))
        l += Barrier("met", most = 700, at = eye(f.debtor))
        // He is exact, not cruel: he lifts a hand to the awning, and says nothing
        l += Beat(Activity.POINT, at = { f.awning }) { _, _ -> }
        l += Barrier("pointed", most = 120, at = { f.awning })
        l += Turn(eye(f.debtor))
        l += Barrier("shut", most = 600, pose = Activity.ARMS_FOLDED, at = { f.awning })
        l += Play(Activity.NOD, at = eye(f.debtor))
        val d = ArrayList<Move>()
        d += await(f)
        d += Barrier("pointed", most = 400, at = eye(f.lender))
        d += Say("tale.note.sealed", detail = closed, gesture = Activity.SHRUG, at = eye(f.lender))
        d += Turn({ f.awning })
        // She lets it down herself: the cord gives at tick 6, the record is sealed there and the awning rolls
        d += Beat(Activity.SHUT_STALL, at = { f.awning }) { p, tick ->
            if (tick == PULL_AT) {
                val lvl = p.level() as ServerLevel
                if (c != null) NoteStall.seal(c, f.key)
                ShutStall.letDown(lvl, f.stall, p, lvl.gameTime + 1200L)
            }
        }
        d += Barrier("shut", most = 200)
        // She stands a moment, grieving (a seated keeper sits it out), before her day has her sit by the shut stall
        d += Play(Sets.fit(f.debtor, Activity.GRIEVE), 80)
        val parts = ArrayList<Stage.Part>()
        parts += walker("lender", f.lender, l)
        parts += still("debtor", f.debtor, d)
        // Two passers come up and look on
        val extras = ArrayList<Stage.Part>()
        for ((i, who) in crowd(f, 2).withIndex()) {
            extras += looker(f, who, i, f.front, 4.5, f.debtor, "shut", listOf(Play(Activity.SIGH)))
        }
        return Stage("seal", parts, HOURS, f.front, extras = extras)
    }

    // ---- tale scene note|paper|lease|seal ----

    /**
     * Adds the note's four ending scenes to `tale scene` (one line in [NoteStall.register]): `note` the Burning (the lender, the keeper of the
     * nearest stall, the stall next to it and two passers), `paper` the quiet paper, `lease` and `seal`. They are played near you, whatever the
     * switches say, with whoever is free, and nothing is written to any tale; a Seal by command lets the awning down for a minute, and a lease
     * chalks the slate for ninety seconds.
     */
    fun register() {
        TaleSceneCommand.add("note") { level, player -> manual(level, player, Ending.A, "hands the sealed note over the counter, and the keeper burns it") }
        TaleSceneCommand.add("paper") { level, player -> manual(level, player, Ending.B, "hands the note over the counter, and the keeper tucks it away") }
        TaleSceneCommand.add("lease") { level, player -> manual(level, player, Ending.C, "brings the lease to be signed, and chalks the slate") }
        TaleSceneCommand.add("seal") { level, player -> manual(level, player, Ending.W, "comes to the stall and points at the awning, and the keeper lets it down") }
    }

    /** The scene of the ending [letter] at the market stall nearest [player] within 24 blocks, with whoever keeps it and the nearest free adult as the lender. */
    private fun manual(level: ServerLevel, player: ServerPlayer, letter: String, did: String): String {
        val capital = Capitals.covering(level, player.blockPosition()) ?: throw TaleSceneCommand.Unplayable("You are not in a capital.")
        val places = CityPlaces.of(level, capital) ?: throw TaleSceneCommand.Unplayable("Its places are still being worked out: try again in a second.")
        val stall = places.stalls.filter { it.keeper.pos.distSqr(player.blockPosition()) <= 24.0 * 24.0 }.minByOrNull { it.keeper.pos.distSqr(player.blockPosition()) }
            ?: throw TaleSceneCommand.Unplayable("No market stall within 24 blocks of you.")
        val keeper = Acts.keeper(level, stall) as? Townsperson ?: throw TaleSceneCommand.Unplayable("Nobody keeps that stall just now.")
        val front = Vec3.atCenterOf(stall.customer.pos)
        val lender = Sets.freeAdults(level, front, 24.0, listOf(keeper)).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks to be the lender.")
        val next = places.stalls.filter { it !== stall }.minByOrNull { it.keeper.pos.distSqr(stall.keeper.pos) }
            ?.takeIf { it.keeper.pos.distSqr(stall.keeper.pos) <= NEIGHBOUR_REACH * NEIGHBOUR_REACH }
            ?.let { Acts.keeper(level, it) as? Townsperson }
            ?.takeIf { it !== keeper && it !== lender }
        val f = Folk(level, stall, keeper, lender, next, TaleSceneCommand.name(keeper), TaleSceneCommand.name(lender))
        val stage = ending(null, f, letter) ?: throw TaleSceneCommand.Unplayable("Nowhere to play it at that stall.")
        return TaleSceneCommand.begin(level, stage, "${TaleSceneCommand.name(lender)} $did (${TaleSceneCommand.name(keeper)}'s stall).")
    }

    /** How far the next stall's keeper is from the stall (blocks) for them to be the neighbour who looks on. */
    const val NEIGHBOUR_REACH = 14.0
}
