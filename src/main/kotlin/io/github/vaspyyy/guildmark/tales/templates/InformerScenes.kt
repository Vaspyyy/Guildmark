package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.templates.Informer.FRIEND
import io.github.vaspyyy.guildmark.tales.templates.Informer.SCOUT
import io.github.vaspyyy.guildmark.tales.templates.Informer.SERGEANT
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * The Sergeant's Friend's scenes (living-world/stories/design.md 8.10): S1 The Count (the sergeant at the gate, a slate in hand, counting the
 * wagons and looking down the road: the tale's seed scene), the Dead Drop (the friend hands a folded paper to the band's scout on the verge of
 * the road at dusk: played by [InformerWatch] where it stands, as no capital's stage reaches the road), the Naming (ending A: the sergeant names
 * the friend before the watch in the gate yard, and the friend walks home alone) and the Handshake (ending B: the sergeant comes to the friend's
 * door at dusk and takes their hand). The Naming and the Handshake are queued by [InformerWatch] as the two reach their marks.
 */
internal object InformerScenes {
    /** The tales whose Count has been played this run (once is enough). */
    private val counted = HashSet<Int>()

    private fun eye(b: Townsperson): () -> Vec3? = { b.takeIf { it.isAlive && !it.isRemoved }?.eyePosition }

    private fun eye(p: ServerPlayer?, or: Townsperson): () -> Vec3? =
        { p?.takeIf { it.isAlive && !it.isRemoved }?.eyePosition ?: or.eyePosition.add(or.lookAngle.scale(2.0)) }

    /** How near the post the sergeant must be for the Count to be played. */
    private const val AT_POST = 4.0

    // ---- S1: The Count ----

    /** The tale's seed scene: the Count, while the sergeant stands at the gate (the asking and the meeting). */
    fun slate(c: Ctx, t: Tale): Stage? {
        if (t.id in counted) return null
        val sergeant = TaleBodies.body(c.level, t, SERGEANT)?.takeIf { it.tale == t.id } ?: return null
        val post = InformerWatch.post(c, t)
        if (sergeant.distanceToSqr(Vec3.atCenterOf(post)) > AT_POST * AT_POST) return null
        val front = Informer.front(c, t)
        val far = WayOut.outward(c.level, c.here, front, 24).lastOrNull() ?: front.relative(c.here.capital.direction(Informer.gate(t)), 16)
        val stage = slateAt(sergeant, Vec3.atCenterOf(far).add(0.0, 1.0, 0.0), 10.0..17.0)
        stage.then = { played -> if (played) counted += t.id }
        return stage
    }

    /**
     * [sergeant] takes out a slate and counts on it (COUNT), sighs, looks [down] the road (LOOKOUT), counts again and sighs again, and puts the
     * slate away: the days the wagons went, and the days the band knew. About twenty seconds.
     */
    fun slateAt(sergeant: Townsperson, down: Vec3, hours: ClosedFloatingPointRange<Double>?): Stage {
        val road = { down }
        val slate = { sergeant.position().add(sergeant.lookAngle.scale(0.6)).add(0.0, 1.0, 0.0) }
        val m = ArrayList<Move>()
        m += Stage.Hold(Sets.prop("slate"))
        m += Play(Activity.COUNT, 120, at = slate)
        m += Play(Activity.SIGH, at = slate)
        m += Turn(road)
        m += Play(Activity.LOOKOUT, 100, at = road)
        m += Play(Activity.COUNT, 60, at = slate)
        m += Play(Activity.SIGH, at = road)
        m += Stage.Hold(ItemStack.EMPTY)
        return Stage("informer_slate", listOf(Stage.Part(SERGEANT, sergeant, moves = m)), hours, sergeant.position())
    }

    // ---- The Dead Drop ----

    /**
     * The Dead Drop: [friend], at the [drop] spot on the verge, looks about them (LOOK_ABOUT); the [scout] comes down the road to them, looks out,
     * and as they meet the friend hands over a folded paper (HAND_OVER, and [handed] on the beat it passes) which the scout takes (RECEIVE); the
     * scout walks back up the road to [away], and the friend looks about once more. About twenty-five seconds after the scout sets out.
     */
    fun drop(friend: Townsperson, scout: Townsperson, drop: BlockPos, away: BlockPos, handed: () -> Unit): Stage {
        val level = friend.level() as ServerLevel
        val beside = Sets.beside(level, Vec3.atCenterOf(drop), scout.position(), 1.4) ?: drop
        var passed = false
        val f = ArrayList<Move>()
        f += Play(Activity.LOOK_ABOUT)
        f += Stage.Barrier("met", 700, Activity.LOOK_ABOUT, eye(scout))
        f += Turn(eye(scout))
        f += Stage.Hold(Sets.prop("thread_paper"))
        f += Stage.Beat(Activity.HAND_OVER, at = eye(scout)) { _, tick ->
            if (!passed && tick >= 16) {
                passed = true
                handed()
            }
        }
        f += Stage.Hold(ItemStack.EMPTY)
        f += Stage.Barrier("taken", 120, at = eye(scout))
        f += Play(Activity.LOOK_ABOUT)
        val s = ArrayList<Move>()
        s += GoTo({ beside }, Jobs.WALK, near = 1.1, most = 600)
        s += Turn(eye(friend))
        s += Play(Activity.LOOKOUT, 40, at = eye(friend))
        s += Stage.Barrier("met", 300, at = eye(friend))
        s += Play(Activity.RECEIVE, at = eye(friend))
        s += Stage.Hold(Sets.prop("thread_paper"))
        s += Stage.Barrier("taken", 120, at = eye(friend))
        s += GoTo({ away }, Jobs.BRISK, near = 1.5, most = 500)
        s += Stage.Hold(ItemStack.EMPTY)
        return Stage("informer_drop", listOf(Stage.Part(FRIEND, friend, moves = f), Stage.Part(SCOUT, scout, moves = s)), null, friend.position())
    }

    // ---- The Naming (A) ----

    /**
     * The Naming in the gate yard: the [sergeant] turns to the [friend] and points at them (POINT), the friend recoils (RECOIL); the sergeant says
     * it (`tale.informer.named`), the friend answers (`tale.informer.sorry`, SIGH) and walks home to their door ([home]) alone. Up to two
     * [guards] of the watch stand by with folded arms, and up to two [watchers] of the street look on. [player] (the one who named them) is
     * looked at once by the sergeant at the end. About twenty-five seconds.
     */
    fun naming(sergeant: Townsperson, friend: Townsperson, player: ServerPlayer?, home: BlockPos?, guards: List<Townsperson>, watchers: List<Townsperson>): Stage {
        val sm = ArrayList<Move>()
        sm += Turn(eye(friend))
        sm += Stage.Barrier("face", 80, Activity.ARMS_FOLDED, eye(friend))
        sm += Play(Activity.POINT, at = eye(friend))
        sm += Stage.Barrier("pointed", 60, at = eye(friend))
        sm += Stage.Say("tale.informer.named", gesture = Activity.ARMS_FOLDED, at = eye(friend))
        sm += Stage.Barrier("said", 200, Activity.ARMS_FOLDED, eye(friend))
        sm += Stage.Barrier("answered", 300, Activity.LISTEN, eye(friend))
        sm += Play(Activity.SIGH, at = eye(friend))
        if (player != null) {
            sm += Turn(eye(player, sergeant))
            sm += Play(Activity.NOD, at = eye(player, sergeant))
        }
        val fm = ArrayList<Move>()
        fm += Turn(eye(sergeant))
        fm += Stage.Barrier("face", 80, at = eye(sergeant))
        fm += Stage.Barrier("pointed", 60, at = eye(sergeant))
        fm += Play(Activity.RECOIL, at = eye(sergeant))
        fm += Stage.Barrier("said", 400, Activity.LISTEN, eye(sergeant))
        fm += Stage.Say("tale.informer.sorry", gesture = Activity.SIGH, at = eye(sergeant))
        fm += Stage.Barrier("answered", 200, at = eye(sergeant))
        if (home != null) fm += GoTo({ home }, Jobs.STROLL, near = 1.5, most = 900)
        val parts = arrayListOf(Stage.Part(SERGEANT, sergeant, moves = sm), Stage.Part(FRIEND, friend, moves = fm))
        for ((i, g) in guards.filter { it !== sergeant && it !== friend }.take(2).withIndex()) {
            val m = ArrayList<Move>()
            m += Turn(eye(friend))
            m += Play(Activity.ARMS_FOLDED, 300, at = eye(friend))
            parts += Stage.Part("guard${i + 1}", g, essential = false, moves = m)
        }
        val extras = ArrayList<Stage.Part>()
        for ((i, o) in watchers.filter { it !== sergeant && it !== friend && it !in guards }.take(2).withIndex()) {
            val m = ArrayList<Move>()
            m += Turn(eye(friend))
            m += Stage.Await("answered", 900, Activity.GAZE, eye(friend))
            extras += Stage.Part("watcher${i + 1}", o, essential = false, moves = m)
        }
        return Stage("informer_naming", parts, null, sergeant.position(), extras = extras)
    }

    // ---- The Handshake (B) ----

    /**
     * The Handshake at the friend's door at dusk: the [friend] sits on their step (SIT) as the [sergeant] walks up; they get up (STAND_UP) and
     * sigh, the sergeant sighs; they stand a block apart and shake hands (SHAKE_HANDS, both); the sergeant says `tale.informer.forgive` and the
     * friend nods. Up to two [watchers] of the street look on. About twenty seconds.
     */
    fun handshake(friend: Townsperson, sergeant: Townsperson, watchers: List<Townsperson>): Stage {
        val level = friend.level() as ServerLevel
        val up = Sets.beside(level, friend.position(), sergeant.position(), 1.6)
        val apart = { Vec3.atBottomCenterOf(friend.blockPosition()).add(sergeant.position().subtract(friend.position()).normalize().scale(1.0)) }
        val fm = ArrayList<Move>()
        fm += Stage.Barrier("come", 600, Activity.SIT, eye(sergeant))
        fm += Play(Activity.STAND_UP)
        fm += Turn(eye(sergeant))
        fm += Play(Activity.SIGH, at = eye(sergeant))
        fm += Stage.Barrier("clasp", 120, at = eye(sergeant))
        fm += Play(Activity.SHAKE_HANDS, at = eye(sergeant))
        fm += Stage.Barrier("said", 300, Activity.LISTEN, eye(sergeant))
        fm += Play(Activity.NOD, at = eye(sergeant))
        val sm = ArrayList<Move>()
        if (up != null) sm += GoTo({ up }, Jobs.STROLL, near = 1.2, most = 500)
        sm += Turn(eye(friend))
        sm += Stage.Barrier("come", 600, at = eye(friend))
        sm += Play(Activity.SIGH, at = eye(friend))
        sm += Stage.Stand(apart, eye(friend))
        sm += Stage.Barrier("clasp", 120, at = eye(friend))
        sm += Play(Activity.SHAKE_HANDS, at = eye(friend))
        sm += Stage.Say("tale.informer.forgive", gesture = Activity.TALK, at = eye(friend))
        sm += Stage.Barrier("said", 200, at = eye(friend))
        val extras = ArrayList<Stage.Part>()
        for ((i, o) in watchers.filter { it !== sergeant && it !== friend }.take(2).withIndex()) {
            val m = ArrayList<Move>()
            m += Turn(eye(friend))
            m += Stage.Await("said", 900, Activity.GAZE, eye(friend))
            extras += Stage.Part("watcher${i + 1}", o, essential = false, moves = m)
        }
        val parts = listOf(Stage.Part(FRIEND, friend, moves = fm), Stage.Part(SERGEANT, sergeant, moves = sm))
        return Stage("informer_handshake", parts, null, friend.position(), extras = extras)
    }

    /** The server stopped: what was played is forgotten. */
    fun forget() = counted.clear()
}
