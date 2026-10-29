package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Memorials
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * The Fourth Wagon's scenes (living-world/stories/design.md 8.3): the kin waiting at the gate (S1's variant), the parley with the band's rider
 * by the camp, the reunion at the board (S6's variant: the kin thanks whoever brought them home), and the cairn at the wreck for the ending W
 * (S7, `Sets.laying`). The waiting is the tale's own seed scene; the reunion is queued by [WagonWatch] as the hand reaches the kin; the parley
 * is played by [WagonWatch] where it stands (no capital's stage reaches the road); the cairn is the ending's scene.
 */
internal object WagonScenes {
    /** The tales whose S1 variant has been played this run (once is enough: the gate does not need watching twice for the street). */
    private val waited = HashSet<Int>()

    private fun eye(b: Townsperson): () -> Vec3? = { b.takeIf { it.isAlive && !it.isRemoved }?.eyePosition }

    private fun eye(p: ServerPlayer?, or: Townsperson): () -> Vec3? =
        { p?.takeIf { it.isAlive && !it.isRemoved }?.eyePosition ?: or.eyePosition.add(or.lookAngle.scale(2.0)) }

    /** How near the gate's front the kin must be for the waiting to be played. */
    private const val AT_GATE = 8.0

    // ---- S1: waiting at the gate ----

    /** The tale's seed scene: the waiting, while the band still has the hand. */
    fun waiting(c: Ctx, t: Tale): Stage? {
        if (t.id in waited || Wagon.freed(t) || Wagon.flag(t, Wagon.RODE)) return null
        val kin = TaleBodies.body(c.level, t, Wagon.KIN)?.takeIf { it.tale == t.id } ?: return null
        val stage = waiting(c, t, kin, 15.0..20.0) ?: return null
        stage.then = { played -> if (played) waited += t.id }
        return stage
    }

    /**
     * The kin at the gate's front (15:00 to 20:00) looks down the road the wagon came by (LOOKOUT), sighs and wrings their hands; a passer-by
     * of the street comes up with a comforting word (`tale.set.comfort`) and the kin answers with who they wait for (`tale.wagon.passing`), and
     * looks out again. About thirty seconds. Null if the kin is not by the gate.
     */
    fun waiting(c: Ctx, t: Tale, kin: Townsperson, hours: ClosedFloatingPointRange<Double>?): Stage? {
        val level = c.level
        val front = Wagon.front(c, t)
        if (kin.distanceToSqr(Vec3.atCenterOf(front)) > AT_GATE * AT_GATE) return null
        val far = WayOut.outward(level, c.here, front, 20).lastOrNull() ?: front.relative(c.here.capital.direction(Wagon.gate(t)), 16)
        return waitingAt(kin, Vec3.atCenterOf(far).add(0.0, 1.0, 0.0), Wagon.given(Wagon.handName(t)), hours)
    }

    /** The waiting itself: [kin] looks [down] the road for [hand] (a given name, for the grey words). */
    fun waitingAt(kin: Townsperson, down: Vec3, hand: String, hours: ClosedFloatingPointRange<Double>?): Stage {
        val level = kin.level() as ServerLevel
        val road = { down }
        val passer = Sets.passerBy(kin, r = 24.0)
        val by = passer?.let { Sets.beside(level, kin.position(), it.position(), 1.6) }
        val k = ArrayList<Move>()
        k += Turn(road)
        k += Play(Activity.LOOKOUT, 120, at = road)
        k += Play(Activity.SIGH, at = road)
        val parts = ArrayList<Stage.Part>()
        if (passer != null && by != null) {
            k += Stage.Barrier("sighed", 900, Activity.LOOKOUT, road)
            k += Stage.Barrier("meet", 500, Activity.LOOKOUT, eye(passer))
            k += Turn(eye(passer))
            k += Stage.Barrier("said", 300, Activity.LISTEN, eye(passer))
            k += Stage.Say(
                "tale.wagon.passing", TaleLines.facts("talk.detail.tale.wagon.passing", TaleLines.lit(hand)),
                gesture = Activity.WRING_HANDS, at = eye(passer),
            )
            k += Stage.Barrier("answered", 200, Activity.SIGH, eye(passer))
            val p = ArrayList<Move>()
            p += Stage.Barrier("sighed", 900)
            p += GoTo({ by }, Jobs.STROLL, near = 1.2, most = 500)
            p += Turn(eye(kin))
            p += Stage.Barrier("meet", 500, at = eye(kin))
            p += Play(Activity.NOD, at = eye(kin))
            p += Stage.Say("tale.set.comfort", gesture = Activity.TALK, at = eye(kin))
            p += Stage.Barrier("said", 300, at = eye(kin))
            p += Stage.Barrier("answered", 300, Activity.LISTEN, eye(kin))
            p += Play(Activity.NOD, at = eye(kin))
            parts += Stage.Part("passer", passer, essential = false, moves = p)
        } else {
            k += Play(Activity.WRING_HANDS, at = road)
        }
        k += Turn(road)
        k += Play(Activity.LOOKOUT, 100, at = road)
        parts.add(0, Stage.Part(Wagon.KIN, kin, moves = k))
        return Stage("wagon_waiting", parts, hours, kin.position())
    }

    // ---- The parley ----

    /**
     * The band's rider has the price ([player] gave it in the talk): they weigh the pouch (COUNT), nod to the player, turn to the camp and give
     * the sign (POINT, and at that beat [free]: the band lets the hand go), wave the player off, and ride back to the camp with the pouch (the
     * walk to [away], out of sight of the town). About twelve seconds.
     */
    fun parley(rider: Townsperson, player: ServerPlayer?, camp: Vec3, away: BlockPos, free: () -> Unit): Stage {
        val you = eye(player, rider)
        val fire = { camp.add(0.0, 1.2, 0.0) }
        var freed = false
        val m = ArrayList<Move>()
        m += Stage.Hold(Sets.prop("ransom_pouch"))
        m += Turn(you)
        m += Play(Activity.COUNT, 60, at = you)
        m += Play(Activity.NOD, at = you)
        m += Turn(fire, 20)
        m += Stage.Beat(Activity.POINT, at = fire) { _, tick ->
            if (!freed && tick >= 8) {
                freed = true
                free()
            }
        }
        // Whatever cut the beat short, the band's word is given
        m += Now { _ ->
            if (!freed) {
                freed = true
                free()
            }
        }
        m += Turn(you)
        m += Play(Activity.WAVE_OFF, at = you)
        m += GoTo({ away }, Jobs.WALK, near = 1.5, overlay = Activity.CARRY, most = 400)
        m += Stage.Hold(ItemStack.EMPTY)
        return Stage("wagon_parley", listOf(Stage.Part(Wagon.RIDER, rider, moves = m)), null, rider.position())
    }

    // ---- S6: the reunion ----

    /**
     * S6 The Reunion (4.3) for the wagon: the [kin] looks out for the [hand] (LOOKOUT); as they come near the kin starts forward and runs to them,
     * and both EMBRACE, each with a take of `tale.set.home`, the green about them; then the kin turns to [player] (whoever brought them home)
     * and thanks them (`tale.wagon.thanks`, THANK) and bows. The [watchers] (up to three) CLAP and CHEER; the [tippers] (the captain and the
     * watch, at C) tip their hats. [quiet] is the ransom's: the hand hobbles in, the kin walks to them, one watcher, who claps and nods. About
     * twenty-five seconds. Null if there is nowhere to stand.
     */
    fun reunion(
        kin: Townsperson, hand: Townsperson, player: ServerPlayer?, watchers: List<Townsperson>, tippers: List<Townsperson>, quiet: Boolean,
    ): Stage? {
        val level = kin.level() as ServerLevel
        if (Sets.standable(level, hand.blockPosition()) == null && Sets.standable(level, kin.blockPosition()) == null) return null
        val joy = { p: Townsperson -> Stage.Fx.play(level, Stage.Fx.Kind.HAPPY, p.position().add(0.0, 1.5, 0.0)) }
        val you = { player?.takeIf { it.isAlive && !it.isRemoved }?.eyePosition }
        val w = ArrayList<Move>()
        w += Turn(eye(hand))
        w += Wait({ p, _ -> hand.isAlive && hand.distanceTo(p) < 14.0 }, most = 1500, pose = Activity.LOOKOUT, at = eye(hand))
        if (!quiet) w += Play(Activity.JUMP, at = eye(hand))
        w += GoTo({ Sets.beside(level, hand.position(), kin.position(), 1.4) }, if (quiet) Jobs.WALK else Jobs.BRISK, near = 1.1, most = 400)
        w += Turn(eye(hand))
        w += Stage.Barrier("hug", most = 300, at = eye(hand))
        var wSaid = false
        w += Stage.Beat(Activity.EMBRACE, at = eye(hand)) { p, tick ->
            if (!wSaid && tick >= 4) wSaid = Words.sayWith(p, "tale.set.home", why = "a reunion") != null
            if (tick % 12 == 0) joy(p)
        }
        w += Stage.Barrier("hugged", most = 120, at = eye(hand))
        if (player != null) {
            w += Turn(you)
            w += Stage.Say("tale.wagon.thanks", TaleLines.facts("talk.detail.tale.wagon.thanks", TaleLines.lit(Wagon.given(hand.name.string))), gesture = Activity.THANK, at = you)
            w += Play(Activity.BOW, at = you)
        }
        val r = ArrayList<Move>()
        r += GoTo(
            { if (hand.distanceTo(kin) > 10.0) Sets.beside(level, kin.position(), hand.position(), 8.0) else null }, Jobs.WALK, near = 1.0,
            overlay = if (quiet) Activity.HOBBLE else Activity.NONE, most = 900,
        )
        r += Turn(eye(kin))
        r += Stage.Barrier("hug", most = 1800, at = eye(kin))
        var rSaid = false
        r += Stage.Beat(Activity.EMBRACE, at = eye(kin)) { p, tick ->
            if (!rSaid && tick >= 24) rSaid = Words.sayWith(p, "tale.set.home", why = "a reunion") != null
            if (tick % 12 == 6) joy(p)
        }
        r += Stage.Barrier("hugged", most = 120, at = eye(kin))
        r += Play(Activity.NOD, at = eye(kin))
        val parts = listOf(Stage.Part(Wagon.KIN, kin, moves = w), Stage.Part(Wagon.HAND, hand, moves = r))
        val extras = ArrayList<Stage.Part>()
        val cheer = watchers.filter { it !== kin && it !== hand && it !in tippers }.take(if (quiet) 1 else 3)
        for ((i, o) in cheer.withIndex()) {
            val m = ArrayList<Move>()
            m += Turn(eye(hand))
            m += Stage.Await("hug", 2400, Activity.GAZE, eye(hand))
            m += Wait({ _, tick -> tick >= 5 * i }, most = 20)
            m += Play(Activity.CLAP, at = eye(hand))
            m += Play(if (quiet) Activity.NOD else Activity.CHEER, at = eye(hand))
            extras += Stage.Part("watcher${i + 1}", o, essential = false, moves = m)
        }
        for ((i, o) in tippers.filter { it !== kin && it !== hand }.take(2).withIndex()) {
            val m = ArrayList<Move>()
            m += Turn(eye(hand))
            m += Stage.Await("hug", 2400, Activity.STAND_GUARD, eye(hand))
            m += Wait({ _, tick -> tick >= 8 * i }, most = 20)
            m += Play(Activity.TIP_HAT, at = eye(kin))
            extras += Stage.Part("tipper${i + 1}", o, essential = false, moves = m)
        }
        return Stage("wagon_reunion", parts, null, kin.position(), extras = extras)
    }

    // ---- The ending, seen ----

    /**
     * The ending's scene: at W the kin goes to the wreck where the cairn now stands and lays a stone on it (`Sets.laying`, with the
     * cairn's words, `tale.wagon.cairn`), by day and only if the kin is within 48 blocks of it; the reunion was the scene of A, B and C.
     */
    fun ending(c: Ctx, t: Tale): Stage? {
        if (t.ending != Ending.W) return null
        val kin = TaleBodies.body(c.level, t, Wagon.KIN) ?: return null
        val wreck = Wagon.wreck(t)
        val memorial = Memorials.of(c, t).firstOrNull { it.kind == "cairn" }
        val stone = memorial?.pos ?: wreck
        if (kin.distanceToSqr(Vec3.atCenterOf(stone)) > 48.0 * 48.0) return null
        val prayer = Sets.Line("tale.wagon.cairn", TaleLines.facts("talk.detail.tale.wagon.cairn", TaleLines.lit(Wagon.handName(t))))
        return Sets.laying(kin, stone, prayer = prayer, memorial = memorial)
    }

    /** The server stopped: what was played is forgotten. */
    fun forget() = waited.clear()
}
