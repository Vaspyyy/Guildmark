package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.festival.FestivalRegistry
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Fx
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Hold
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Rise
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scenes of A Lantern for Nell (living-world/stories/design.md 8.9), made of Tales B's moves (`Stage`) for the template's
 * `scene` and for `tale scene lantern_*`. Each takes the bodies it is given and decides nothing: the record is the template's.
 *
 * The paper lantern is the carried prop (`lantern_paper_dark`, `lantern_paper_lit`: [Sets.prop]); the clips draw a carried stack
 * only in the hands-before-them pose (CARRY), so it is held up in both hands to be seen, and put away (out of the hands) for the rest.
 * As it is let go it is the townsfolk's own lantern that the Lanternrise's clip draws (RELEASE, `fest_release`), and the sky lantern
 * leaves the hands at tick 22 of it, as the festival's own do (`FestivalSite.lanterns`).
 */
internal object LanternScenes {
    /** The doorstep's hours, a day before the festival (10:00 to 17:00). */
    val DOOR_HOURS: ClosedFloatingPointRange<Double> = 10.0..17.0

    /** Ticks they gaze after their lantern as it rises (the town's rise for `FestivalSite.LANTERN_TIME`, from the let-go at 22). */
    private const val GAZE = (FestivalSite.LANTERN_TIME - FestivalSite.RELEASE_TICK).toInt()

    // ---- The doorstep (S1, a variant) ----

    /**
     * S1 at the doorstep (8.9 step 0): [old], at their door (seated on the step or a bench there if there is one), gets up and holds the
     * paper lantern ([lit] once the candle is in it) before them, looking along the way to the square at [toward]; sighs, holds it a
     * while longer. About when that is done [passer] (the neighbour, else a free adult of the street) walks up to two blocks, nods and
     * says a comforting word (`tale.set.comfort`); [old] turns to them, listens, waves it off and sighs, looks along the way once more and
     * puts the lantern back in the lap. About 35 seconds; [old] goes back to their seat as it ends. With no passer-by they are alone.
     */
    fun door(old: Townsperson, passer: Townsperson?, toward: Vec3, lit: Boolean, hours: ClosedFloatingPointRange<Double>? = DOOR_HOURS): Stage {
        val level = old.level() as ServerLevel
        val spot = passer?.let { Sets.beside(level, old.position(), it.position(), 2.0) }
        val look = lookAlong(old, toward)
        val o = ArrayList<Move>()
        o += Rise()
        o += Hold(lantern(lit))
        o += Play(Activity.CARRY, 120, at = look)
        o += Play(Activity.SIGH, at = look)
        o += Play(Activity.CARRY, 80, at = look)
        val parts = ArrayList<Stage.Part>()
        if (passer != null && spot != null) {
            o += Barrier("meet", most = 240, pose = Activity.CARRY, at = eye(passer))
            o += Turn(eye(passer))
            o += Barrier("said", most = 260, pose = Activity.CARRY, at = eye(passer))
            o += Play(Activity.WAVE_OFF, at = eye(passer))
            o += Play(Activity.SIGH, at = eye(passer))
            // The walk up ends about as the first long look does (eight ticks a block at a stroll); the barrier takes up the rest
            val delay = (250 - 8 * passer.distanceTo(old)).toInt().coerceAtLeast(0)
            val p = ArrayList<Move>()
            p += Wait({ _, t -> t >= delay }, most = delay + 1)
            p += GoTo({ spot }, Jobs.STROLL, near = 1.2, most = 500)
            p += Turn(eye(old))
            p += Barrier("meet", most = 240, at = eye(old))
            p += Play(Activity.NOD, at = eye(old))
            p += Say("tale.set.comfort", gesture = Activity.TALK, at = eye(old))
            p += Barrier("said", most = 260, at = eye(old))
            p += Play(Activity.NOD, at = eye(old))
            parts += Stage.Part("passer", passer, false, p)
        }
        o += Play(Activity.CARRY, 100, at = look)
        o += Hold(ItemStack.EMPTY)
        parts.add(0, seatedPart(Lantern.OLD, old, o))
        return Stage("lantern door", parts, hours, old.position())
    }

    // ---- The candle (step 1) ----

    /**
     * The candle set in the lantern (8.9 step 1): [old] takes the paper lantern up from the lap and holds it before them, looking down
     * into it; a match is struck and the wick takes (`Fx.CANDLE`, the `tale.candle_light` sound, at the lantern), and the lantern in
     * their hands glows; they look at it a while, turn to [player] and nod. About 10 seconds. A one-person scene, played as the talk
     * that gave the candle ends.
     */
    fun light(old: Townsperson, player: Player?): Stage {
        val o = ArrayList<Move>()
        o += Rise()
        o += Hold(lantern(false))
        o += Play(Activity.CARRY, 40, at = { inHands(old) })
        o += Fx(Fx.Kind.CANDLE, at = { inHands(it) }, ticks = 12)
        o += Hold(lantern(true))
        o += Play(Activity.CARRY, 80, at = { inHands(old) })
        if (player != null) {
            o += Turn({ player.eyePosition })
            o += Play(Activity.NOD, at = { player.eyePosition })
        }
        o += Play(Activity.CARRY, 40, at = { inHands(old) })
        o += Hold(ItemStack.EMPTY)
        return Stage("lantern candle", listOf(seatedPart(Lantern.OLD, old, o)), null, old.position())
    }

    // ---- The let-go ----

    /**
     * The tale's own let-go (8.9 ending B, the fountain without a festival, the window of C): [old] looks up, lets the lantern go
     * (RELEASE: the sky lantern leaves the hands at tick 22, as the town's do, `tale.lantern.release` said as it goes), and gazes after
     * it as it rises for the sixteen seconds the town's do; then turns to [player], if there is one near, says `tale.lantern.thanks` and
     * thanks them. About 25 seconds. [line] is said before the let-go, to the player ([Lantern]'s window, `tale.lantern.window`), with
     * the lantern held up lit in the hands.
     */
    fun letGo(old: Townsperson, player: Player?, line: String? = null, thanks: Boolean = true): Stage {
        val sky = { above(old) }
        val o = ArrayList<Move>()
        o += Rise()
        if (line != null) {
            o += Hold(lantern(true))
            if (player != null) {
                o += Turn({ player.eyePosition })
                o += Say(line, gesture = Activity.CARRY, at = { player.eyePosition })
            } else {
                o += Play(Activity.CARRY, 60, at = sky)
            }
        }
        o += Hold(ItemStack.EMPTY)
        o += Turn(sky)
        o += release(old)
        o += Play(Activity.GAZE, GAZE, at = sky)
        if (thanks && player != null) {
            o += Turn({ player.eyePosition })
            o += Say("tale.lantern.thanks", at = { player.eyePosition })
            o += Play(Activity.THANK, at = { player.eyePosition })
        }
        return Stage("lantern let-go", listOf(seatedPart(Lantern.OLD, old, o)), null, old.position())
    }

    /**
     * After the town's release (8.9 ending A): [old] has let their lantern go with the town's (the festival's hooks played it on the
     * very tick the town's did) and gazes after it while it rises; then turns to [player], says `tale.lantern.thanks` and thanks them.
     */
    fun afterRelease(old: Townsperson, player: Player?): Stage {
        val sky = { above(old) }
        val o = ArrayList<Move>()
        // The clip they let go with plays through first (it is the festival's hooks', not the scene's)
        o += Until({ it.activity != Activity.RELEASE || it.actDone }, Activity.RELEASE.ticks)
        o += Play(Activity.GAZE, GAZE - Activity.RELEASE.ticks, at = sky)
        if (player != null) {
            o += Turn({ player.eyePosition })
            o += Say("tale.lantern.thanks", at = { player.eyePosition })
            o += Play(Activity.THANK, at = { player.eyePosition })
        }
        return Stage("lantern risen", listOf(seatedPart(Lantern.OLD, old, o)), null, old.position())
    }

    // ---- The lantern kept dark (W) ----

    /**
     * The lantern stays dark (8.9 ending W, at dusk on the festival's day): [old] at their door takes the unlit lantern up from the lap and
     * holds it, looking up at the sky where the town's are rising; says `tale.lantern.dark` (to [player] if one is near), sighs, and
     * puts it back in the lap. About 15 seconds.
     */
    fun dark(old: Townsperson, player: Player?): Stage {
        val sky = { above(old) }
        val o = ArrayList<Move>()
        o += Rise()
        o += Hold(lantern(false))
        o += Play(Activity.CARRY, 100, at = sky)
        o += Say("tale.lantern.dark", gesture = Activity.CARRY, at = if (player != null) ({ player.eyePosition }) else sky)
        o += Play(Activity.SIGH)
        o += Play(Activity.CARRY, 80, at = sky)
        o += Hold(ItemStack.EMPTY)
        return Stage("lantern dark", listOf(seatedPart(Lantern.OLD, old, o)), null, old.position())
    }

    // ---- The lantern going up ----

    /** The RELEASE clip, the sky lantern leaving [old]'s hands at tick 22 and the release line said as it begins. */
    fun release(old: Townsperson): Move = Beat(Activity.RELEASE, at = { above(old) }) { p, t ->
        if (t == 0) Words.sayWith(p, "tale.lantern.release", why = "a tale: tale.lantern.release")
        if (t.toLong() == FestivalSite.RELEASE_TICK) rise(p)
    }

    /**
     * A sky lantern from [p]'s hands, just where the RELEASE clip has it at tick 22 (`FestivalSite.LET_GO_*`), so the one in the sky takes
     * over from the one they held; the townsfolk's own lantern is put away with it, so it is not drawn in the hand again as they gaze.
     */
    fun rise(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        p.lantern = false
        val scale = if (p.role == TownRole.CHILD) Townsperson.CHILD_SCALE.toDouble() else 1.0
        val yaw = p.yBodyRot * PI / 180
        val right = FestivalSite.LET_GO_RIGHT * scale
        val ahead = FestivalSite.LET_GO_FORWARD * scale
        level.sendParticles(
            FestivalRegistry.SKY_LANTERN.get(), true, true,
            p.x - sin(yaw) * ahead - cos(yaw) * right, p.y + FestivalSite.LET_GO_UP * scale, p.z + cos(yaw) * ahead - sin(yaw) * right,
            0, 0.0, 1.0, 0.0, 0.0,
        )
    }

    // ---- Places to look ----

    /** The paper lantern, dark or lit, as a body holds it. */
    fun lantern(lit: Boolean): ItemStack = Sets.prop(if (lit) "lantern_paper_lit" else "lantern_paper_dark")

    /** A point a little way up along the way from [p] to [toward], at the height of the eyes: where they look down the street. */
    private fun lookAlong(p: Townsperson, toward: Vec3): () -> Vec3 = {
        val dx = toward.x - p.x
        val dz = toward.z - p.z
        val d = Math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.1)
        Vec3(p.x + dx / d * 12.0, p.eyeY + 0.6, p.z + dz / d * 12.0)
    }

    /** The lantern held before [p], at the height of the hands. */
    private fun inHands(p: Townsperson): Vec3 {
        val yaw = p.yBodyRot * PI / 180
        return Vec3(p.x - sin(yaw) * 0.45, p.y + 0.95, p.z + cos(yaw) * 0.45)
    }

    /** The sky a little ahead of [p] (where a lantern let go is seen rising). */
    private fun above(p: Townsperson): Vec3 {
        val yaw = p.yBodyRot * PI / 180
        return Vec3(p.x - sin(yaw) * 3.0, p.eyeY + 9.0, p.z + cos(yaw) * 3.0)
    }

    private fun eye(p: Townsperson): () -> Vec3? = { p.eyePosition }

    /** Waits, doing nothing to the clip they are in, until [done] holds or [most] ticks: a clip begun before the scene took them plays out. */
    private class Until(private val done: (Townsperson) -> Boolean, private val most: Int) : Move() {
        override fun tick(p: Townsperson, a: io.github.vaspyyy.guildmark.townsfolk.scenes.Aside, t: Int): Boolean = t < most && !done(p)
    }

    /** [p]'s part: one sitting on the doorstep or a bench is taken where they sit (and [Rise]s for the lantern). */
    private fun seatedPart(slot: String, p: Townsperson, moves: List<Move>): Stage.Part =
        Stage.Part(slot, p, true, moves, station = p.seat != null, seated = p.seat != null)
}
