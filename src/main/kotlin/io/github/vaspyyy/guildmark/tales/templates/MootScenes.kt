package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Await
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Fx
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the Bell Moot shows (living-world/stories/design.md 8.7, 4.3), every scene at the market board, where the crier cries:
 *
 *  - **quarrel** (before the moot sits, 11:00 to 17:00): S3's crowd variant, `for` and `against` arguing a few steps out from the board, the
 *    crier coming up to hush them if free, up to five of the street in a half ring; once played through the two go home and are let go.
 *  - **call** (while the moot sits, 12:00 to 14:00): the crier at the crying spot rings the handbell and calls for someone to speak
 *    (`tale.moot.call`), looks about the crowd and calls again; the two sides stand either side of them, `for` pointing at the works, `against`
 *    with folded arms; the free folk about the square stand in a half ring before the board and look on, nodding or grumbling.
 *  - **the verdict** (the ending's scene, played as the word is spoken if a player is near): A the crier says the town gives (`tale.moot.good`,
 *    the Marks in grey), rings the handbell, and the ring claps and cheers, one of them ringing the town's bell if it has one near; B
 *    (`tale.moot.half`) claps and grumbles; C grumbles and the crier sighs (the crier says `tale.moot.deferred` only when no word was spoken:
 *    the talk said it otherwise); W the shouting match, S3 with the crier's `tale.moot.broke` as the hush. Nothing is shown for a works paid
 *    for or called off before the moot met.
 *
 * Every body walks to where it plays at its own pace and goes back to its day after; a scene that cannot be taken is not played (the record
 * is the truth). The marks are worked out from the board ([Marks]), so [MootSquare] holds the people where the scenes want them.
 */
internal object MootScenes {
    /** The hours the quarrel plays in, the moot sits in, and a verdict is shown in (the W comes at two). */
    val QUARREL: ClosedFloatingPointRange<Double> = 11.0..17.0
    val SITS: ClosedFloatingPointRange<Double> = 12.0..14.0
    private val VERDICT: ClosedFloatingPointRange<Double> = 11.0..18.0

    /** How near the board the crowd is found, and how many stand in its ring (a stage's bystanders). */
    private const val CROWD_NEAR = 20.0
    private const val RING = Stage.EXTRAS

    /** How far before the crier the ring stands, and the two sides stand to either side of them. */
    private const val RING_OUT = 5.0
    private const val SIDE = 3.0

    // ---- Where it all is ----

    /**
     * The places of the moot, from the board ([spot] the crying spot, [out] the way it faces, [right] across it): the sides' marks at the moot
     * ([forAt], [againstAt]: three steps to either side of the crier, a step out), their marks for the quarrel before it ([forQuarrel],
     * [againstQuarrel]: four steps out, three apart), and the middle of the crowd's ring ([crowd]).
     */
    class Marks(val spot: BlockPos, val facing: Direction) {
        val out: Vec3 = Vec3(facing.stepX.toDouble(), 0.0, facing.stepZ.toDouble())
        val right: Vec3 = Vec3(-out.z, 0.0, out.x)
        val centre: Vec3 = Vec3.atBottomCenterOf(spot)

        fun at(along: Double, across: Double): Vec3 = centre.add(out.scale(along)).add(right.scale(across))

        val crowd: Vec3 get() = at(RING_OUT, 0.0).add(0.0, 1.5, 0.0)
        val forAt: Vec3 get() = at(1.0, SIDE)
        val againstAt: Vec3 get() = at(1.0, -SIDE)
        val forQuarrel: Vec3 get() = at(4.0, 1.5)
        val againstQuarrel: Vec3 get() = at(4.0, -1.5)

        /** [v] as a block someone can stand on, or null. */
        fun stand(level: ServerLevel, v: Vec3): BlockPos? = Sets.standable(level, BlockPos.containing(v))
    }

    /** The moot's marks in [places]: the crier's own spot before the board. */
    fun marks(places: CityPlaces): Marks = Crier.cryingSpot(places).let { Marks(it.pos, it.facing) }

    // ---- Making the parts ----

    private fun walker(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves)

    private fun eye(p: Townsperson): () -> Vec3? = { p.eyePosition }

    /** A spot of the half ring before the crier, the [i]th of [n]. */
    private fun ringSpot(level: ServerLevel, m: Marks, i: Int, n: Int): BlockPos? {
        val spread = if (n <= 1) 0.0 else (i - (n - 1) / 2.0) * (PI * 0.75 / (n - 1))
        val dir = m.out.scale(cos(spread)).add(m.right.scale(sin(spread)))
        return Sets.standable(level, BlockPos.containing(m.centre.add(dir.scale(RING_OUT))))
    }

    /** The free folk about the board who stand in the ring, leaving out [cast]. */
    private fun crowd(level: ServerLevel, m: Marks, cast: List<Townsperson>): List<Townsperson> =
        Sets.freeAdults(level, m.centre, CROWD_NEAR, cast).take(RING)

    // ---- The tale's asks ----

    /** The scene due now ([Moot.scene]): the verdict once the tale has ended; the call while the moot sits; the quarrel before it. */
    fun scene(c: Ctx, t: Tale): Stage? {
        val level = c.level
        val m = marks(c.here.places)
        if (!t.open) return verdict(c, t, m)
        val forBody = TaleBodies.body(level, t, Moot.FOR)
        val againstBody = TaleBodies.body(level, t, Moot.AGAINST)
        val crier = TaleBodies.body(level, t, Moot.SPEAKER)
        if (t.n[Moot.SITS] == 1) {
            val sitting = MootSquare.forced(t) || Stage.within(level, SITS)
            if (!sitting || crier == null || crier.distanceToSqr(m.centre) > 8.0 * 8.0) return null
            return call(level, m, crier, forBody, againstBody, Moot.works(level, t)?.site?.post, hours = if (MootSquare.forced(t)) null else SITS)
        }
        if (t.n[Moot.BITS] and Moot.QUARRELLED != 0 || !Stage.within(level, QUARREL)) return null
        if (forBody == null || againstBody == null) return null
        // Both at their marks before the board (MootSquare holds them there while a player is near)
        val near = 4.0 * 4.0
        if (forBody.position().distanceToSqr(m.forQuarrel) > near || againstBody.position().distanceToSqr(m.againstQuarrel) > near) return null
        val stage = quarrel(c, t, m, forBody, againstBody, crier?.takeIf { it.distanceToSqr(m.centre) <= CROWD_NEAR * CROWD_NEAR }, QUARREL) ?: return null
        val id = t.id
        stage.then = { done -> if (done && "retorted" in stage.passed) quarrelled(level, id) }
        return stage
    }

    /** The quarrel was seen through: it is in the record, and the sides are let go to their days. */
    private fun quarrelled(level: ServerLevel, id: Int) {
        val data = TaleData.get(level)
        val t = data[id]?.takeIf { it.open } ?: return
        t.n[Moot.BITS] = t.n[Moot.BITS] or Moot.QUARRELLED
        data.changed()
        MootSquare.letGo(level, t)
    }

    // ---- S3, the crowd variant ----

    /**
     * The two sides of tale [t] at it before the board: S3 with the crowd's half ring, `for`'s shout the reason in grey (`tale.moot.for`),
     * `against`'s retort the price (`tale.moot.against`), `for` pointing at the works' site (else the board), the crier the third if [third].
     */
    fun quarrel(c: Ctx, t: Tale, m: Marks, a: Townsperson, b: Townsperson, third: Townsperson?, hours: ClosedFloatingPointRange<Double>?, hush: Sets.Line? = null): Stage? {
        val level = c.level
        val name = Moot.first(t.cast(Moot.FOR)?.name)
        val reason = Moot.argument(level, t)
        val shout = if (reason == null) TaleLines.facts("talk.detail.tale.moot.for_need", TaleLines.lit(name))
        else TaleLines.facts("talk.detail.tale.moot.for", TaleLines.lit(name), TaleLines.lit(reason))
        val retort = TaleLines.facts("talk.detail.tale.moot.against", TaleLines.lit(Moot.first(t.cast(Moot.AGAINST)?.name)), Moot.price(c, t))
        val toward = Moot.works(level, t)?.site?.post?.let { Vec3.atCenterOf(it) } ?: Vec3.atCenterOf(c.here.places.marketBoard.pos)
        val watchers = crowd(level, m, listOfNotNull(a, b, third))
        return Sets.quarrel(
            a, b, third, watchers, shout = Sets.Line("tale.moot.for", shout), retort = Sets.Line("tale.moot.against", retort),
            toward = toward, crowd = true, hours = hours, hush = hush,
        )
    }

    // ---- The call ----

    /**
     * The moot sits: [crier] at the crying spot rings and calls (`tale.moot.call`), looks over the crowd and calls again; [forSide] and
     * [againstSide] (when about) at their marks to either side turn to them, `for` pointing at [works] (the board if none), `against` with
     * folded arms and a shrug; the free folk about stand in a half ring before the board, gazing, nodding or grumbling. About a minute.
     */
    fun call(
        level: ServerLevel, m: Marks, crier: Townsperson, forSide: Townsperson?, againstSide: Townsperson?, works: BlockPos?,
        hours: ClosedFloatingPointRange<Double>?,
    ): Stage {
        val crowdAt = { m.crowd }
        val thing = works?.let { Vec3.atCenterOf(it) } ?: m.centre.add(m.out.scale(-2.0)).add(0.0, 1.2, 0.0)
        val k = ArrayList<Move>()
        m.stand(level, m.centre)?.let { spot -> if (crier.distanceToSqr(Vec3.atBottomCenterOf(spot)) > 1.0) k += GoTo({ spot }, Jobs.WALK, near = 0.6, most = 400) }
        k += Turn(crowdAt)
        k += Barrier("set", most = 400, at = crowdAt)
        k += Beat(Activity.RING_BELL, 60, at = crowdAt) { _, _ -> }
        k += Say("tale.moot.call", gesture = Activity.ANNOUNCE, at = crowdAt)
        k += Barrier("called", most = 200, at = crowdAt)
        k += Wait({ _, tick -> tick >= 120 }, most = 121, pose = Activity.LOOK_ABOUT, at = crowdAt)
        k += Say("tale.moot.call", gesture = Activity.ANNOUNCE, at = crowdAt)
        k += Wait({ _, tick -> tick >= 160 }, most = 161, pose = Activity.ARMS_FOLDED, at = crowdAt)
        val parts = ArrayList<Stage.Part>()
        parts += walker("crier", crier, k)
        if (forSide != null) {
            val f = ArrayList<Move>()
            m.stand(level, m.forAt)?.let { spot -> f += GoTo({ spot }, Jobs.STROLL, near = 0.8, most = 400) }
            f += Turn(eye(crier))
            f += Barrier("set", most = 400, at = eye(crier))
            f += Barrier("called", most = 300, pose = Activity.LISTEN, at = eye(crier))
            f += Play(Activity.POINT, at = { thing })
            f += Wait({ _, tick -> tick >= 200 }, most = 201, pose = Activity.GAZE, at = crowdAt)
            parts += walker("for", forSide, f, essential = false)
        }
        if (againstSide != null) {
            val g = ArrayList<Move>()
            m.stand(level, m.againstAt)?.let { spot -> g += GoTo({ spot }, Jobs.STROLL, near = 0.8, most = 400) }
            g += Turn(eye(crier))
            g += Barrier("set", most = 400, at = eye(crier))
            g += Barrier("called", most = 300, pose = Activity.ARMS_FOLDED, at = eye(crier))
            g += Play(Activity.SHRUG, at = crowdAt)
            g += Wait({ _, tick -> tick >= 200 }, most = 201, pose = Activity.ARMS_FOLDED, at = crowdAt)
            parts += walker("against", againstSide, g, essential = false)
        }
        val folk = crowd(level, m, listOfNotNull(crier, forSide, againstSide))
        val extras = ArrayList<Stage.Part>()
        for ((i, o) in folk.withIndex()) {
            val e = ArrayList<Move>()
            ringSpot(level, m, i, folk.size)?.let { spot -> e += GoTo({ spot }, Jobs.STROLL, near = 1.0, most = 500) }
            e += Turn(eye(crier))
            e += Await("called", 900, Activity.GAZE, eye(crier))
            e += Wait({ _, tick -> tick >= 8 * i }, most = 8 * i + 1)
            e += Play(if (i % 2 == 0) Activity.NOD else Activity.GRUMBLE, at = eye(crier))
            e += Await("never", 280, Activity.LISTEN, eye(crier))
            extras += walker("ring${i + 1}", o, e, essential = false)
        }
        return Stage("moot_call", parts, hours, m.centre, most = 1500, extras = extras)
    }

    // ---- The verdict ----

    /** The verdict of the ended tale [t], as its ending's scene: null for a works paid for or called off before the moot met, or no crier about. */
    private fun verdict(c: Ctx, t: Tale, m: Marks): Stage? {
        val level = c.level
        val v = t.n[Moot.VERDICT]
        if (v == Moot.PAID || v == Moot.REMOVED) return null
        val crier = TaleBodies.body(level, t, Moot.SPEAKER)?.takeIf { it.distanceToSqr(m.centre) <= CROWD_NEAR * CROWD_NEAR } ?: return null
        val forBody = TaleBodies.body(level, t, Moot.FOR)?.takeIf { it.distanceToSqr(m.centre) <= CROWD_NEAR * CROWD_NEAR }
        val againstBody = TaleBodies.body(level, t, Moot.AGAINST)?.takeIf { it.distanceToSqr(m.centre) <= CROWD_NEAR * CROWD_NEAR }
        val gift = if (t.n[Moot.GIFT] > 0) TaleLines.facts("talk.detail.tale.moot.gift", t.n[Moot.GIFT], TaleLines.lit(Moot.word(t))) else null
        return when (t.ending) {
            Ending.A -> given(level, m, crier, forBody, againstBody, gift, full = true, hours = VERDICT)
            Ending.B -> given(level, m, crier, forBody, againstBody, gift, full = false, hours = VERDICT)
            Ending.C -> deferred(level, m, crier, forBody, againstBody, said = t.n[Moot.BITS] and Moot.SPOKE != 0, hours = VERDICT)
            Ending.W -> {
                if (forBody == null || againstBody == null) return null
                quarrel(c, t, m, forBody, againstBody, crier, VERDICT, hush = Sets.Line("tale.moot.broke"))
            }
            else -> null
        }
    }

    /**
     * A or B: the crier turns to the ring and says the town gives ([full]: `tale.moot.good`, else `tale.moot.half`, [gift] the Marks in grey),
     * and rings the handbell for an A; `for` cheers (nods for a B), `against` nods (shrugs); the ring claps and cheers for an A, claps and
     * grumbles by turns for a B, and for an A one of them goes to the town's bell, if one is near, and rings it.
     */
    fun given(
        level: ServerLevel, m: Marks, crier: Townsperson, forSide: Townsperson?, againstSide: Townsperson?, gift: String?, full: Boolean,
        hours: ClosedFloatingPointRange<Double>?,
    ): Stage {
        val crowdAt = { m.crowd }
        val above = { Vec3(crier.x, crier.y + 2.2, crier.z) }
        val k = ArrayList<Move>()
        k += Turn(crowdAt)
        k += Barrier("set", most = 300, at = crowdAt)
        k += Say(if (full) "tale.moot.good" else "tale.moot.half", detail = gift, gesture = Activity.ANNOUNCE, at = crowdAt)
        k += Barrier("said", most = 200, at = crowdAt)
        if (full) {
            k += Beat(Activity.RING_BELL, 60, at = crowdAt) { _, tick -> if (tick == 10) Fx.play(level, Fx.Kind.PETALS, above()) }
        } else {
            k += Play(Activity.NOD, at = crowdAt)
        }
        k += Play(Activity.BOW, at = crowdAt)
        val parts = ArrayList<Stage.Part>()
        parts += walker("crier", crier, k)
        if (forSide != null) {
            val f = ArrayList<Move>()
            f += Turn(eye(crier))
            f += Barrier("set", most = 300, at = eye(crier))
            f += Barrier("said", most = 300, pose = Activity.LISTEN, at = eye(crier))
            if (full) {
                f += Play(Activity.CHEER, at = eye(crier))
                f += Play(Activity.CLAP, at = eye(crier))
            } else {
                f += Play(Activity.NOD, at = eye(crier))
            }
            parts += walker("for", forSide, f, essential = false)
        }
        if (againstSide != null) {
            val g = ArrayList<Move>()
            g += Turn(eye(crier))
            g += Barrier("set", most = 300, at = eye(crier))
            g += Barrier("said", most = 300, pose = Activity.ARMS_FOLDED, at = eye(crier))
            g += Play(if (full) Activity.NOD else Activity.SHRUG, at = eye(crier))
            parts += walker("against", againstSide, g, essential = false)
        }
        val folk = crowd(level, m, listOfNotNull(crier, forSide, againstSide))
        val bell = if (full) Sets.bellNear(level, m.spot) else null
        val ringer = if (bell != null) folk.lastOrNull() else null
        val ring = folk.filter { it !== ringer }
        val extras = ArrayList<Stage.Part>()
        for ((i, o) in ring.withIndex()) {
            val e = ArrayList<Move>()
            ringSpot(level, m, i, ring.size)?.let { spot -> e += GoTo({ spot }, Jobs.WALK, near = 1.0, most = 400) }
            e += Turn(eye(crier))
            e += Await("said", 900, Activity.GAZE, eye(crier))
            e += Wait({ _, tick -> tick >= 4 * i }, most = 4 * i + 1)
            if (full) {
                e += Play(Activity.CLAP, at = eye(crier))
                e += Play(if (i % 2 == 0) Activity.CHEER else Activity.HURRAH, at = eye(crier))
            } else {
                e += Play(if (i % 2 == 0) Activity.CLAP else Activity.GRUMBLE, at = eye(crier))
                if (i % 2 == 0) e += Play(Activity.NOD, at = eye(crier))
            }
            extras += walker("ring${i + 1}", o, e, essential = false)
        }
        if (ringer != null && bell != null) {
            val bellAt = Vec3.atCenterOf(bell)
            val r = ArrayList<Move>()
            Sets.beside(level, bellAt, ringer.position(), 1.4)?.let { stand -> r += GoTo({ stand }, Jobs.BRISK, near = 0.8, most = 400) }
            r += Turn({ bellAt })
            r += Barrier("said", most = 900, at = { bellAt })
            r += Beat(Activity.RING_BELL, 60, at = { bellAt }) { _, tick ->
                if (tick == 6 || tick == 36) Fx.sound(level, SoundEvents.BELL_BLOCK, bellAt, 1.6f, 1.0f)
            }
            parts += walker("bell", ringer, r, essential = false)
        }
        return Stage(if (full) "moot_given" else "moot_half", parts, hours, m.centre, most = 1200, extras = extras)
    }

    /**
     * C: the ring grumbles, the crier sighs; if no word was spoken ([said] false: the works was put off at the close, or by `tale end`) the
     * crier says the question stays (`tale.moot.deferred`) first. `for` sighs, `against` nods.
     */
    fun deferred(
        level: ServerLevel, m: Marks, crier: Townsperson, forSide: Townsperson?, againstSide: Townsperson?, said: Boolean,
        hours: ClosedFloatingPointRange<Double>?,
    ): Stage {
        val crowdAt = { m.crowd }
        val k = ArrayList<Move>()
        k += Turn(crowdAt)
        k += Barrier("set", most = 300, at = crowdAt)
        if (!said) k += Say("tale.moot.deferred", gesture = Activity.ANNOUNCE, at = crowdAt)
        k += Barrier("said", most = 200, at = crowdAt)
        k += Play(Activity.SIGH, at = crowdAt)
        k += Play(Activity.SHRUG, at = crowdAt)
        val parts = ArrayList<Stage.Part>()
        parts += walker("crier", crier, k)
        for ((slot, side, clip) in listOf(Triple("for", forSide, Activity.SIGH), Triple("against", againstSide, Activity.NOD))) {
            if (side == null) continue
            val s = ArrayList<Move>()
            s += Turn(eye(crier))
            s += Barrier("set", most = 300, at = eye(crier))
            s += Barrier("said", most = 300, pose = Activity.ARMS_FOLDED, at = eye(crier))
            s += Play(clip, at = eye(crier))
            parts += walker(slot, side, s, essential = false)
        }
        val folk = crowd(level, m, listOfNotNull(crier, forSide, againstSide))
        val extras = ArrayList<Stage.Part>()
        for ((i, o) in folk.withIndex()) {
            val e = ArrayList<Move>()
            ringSpot(level, m, i, folk.size)?.let { spot -> e += GoTo({ spot }, Jobs.STROLL, near = 1.0, most = 400) }
            e += Turn(eye(crier))
            e += Await("said", 900, Activity.GAZE, eye(crier))
            e += Wait({ _, tick -> tick >= 6 * i }, most = 6 * i + 1)
            e += Play(if (i % 3 == 2) Activity.SHRUG else Activity.GRUMBLE, at = eye(crier))
            extras += walker("ring${i + 1}", o, e, essential = false)
        }
        return Stage("moot_deferred", parts, hours, m.centre, most = 900, extras = extras)
    }
}
