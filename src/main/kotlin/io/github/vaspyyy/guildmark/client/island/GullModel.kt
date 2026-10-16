package io.github.vaspyyy.guildmark.client.island

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A gull: a herring gull, a great black-backed gull or a young one (three textures, one model), a little under a block and a half across the
 * wings. Models are in pixels with y down and the bird facing -z (its right is -x); the origin is the ground under its feet when it stands,
 * and [BODY_Y] is how high the middle of its body is over them: the renderer pitches and rolls the bird in flight about that point (the model
 * never rolls or pitches the whole bird itself, apart from what it does with its own body: crouching, a flare, a plunge).
 *
 * The bird is a tree of parts hung from `body`: seven stepped slices of torso; a neck in two links carrying the head (skull, hooked bill, lower
 * bill, two eyes and a fish for after a dive); a tail of three feathers that fans; two legs of three links; and two wings, each of an arm, a
 * forearm and a hand with its own four primaries and a flap of secondaries on the arm and the forearm, so a wing folds at its shoulder, elbow
 * and wrist (a Z, as a real wing folds, the black tips crossing over the tail), spreads, beats with the hand and the tip lagging the arm,
 * flexing its primaries and fanning them, and glides with a little dihedral.
 *
 * What [GullRenderState] says: `flying`, `spread`, `flap` and `flapPhase` (the beat), `bank` (the outer wing sweeps up), `hover`, `floating`
 * (riding low on the water), `headYaw`, `headPitch` and `beak` (where it looks and how wide it gapes), `walk` and `walkPhase` (the gait), and
 * `action` with `actionTime` (a scripted action from the start: [ACTION_TICKS] says how long each lasts; every action is blended in over its
 * first ticks and out over its last, so none pops). It also holds its own small stirrings: the head turning from look to look, blinking and
 * the tail flicking, which die away when an action or a walk has the bird.
 *
 * Every pose is made by the functions below, which come from one place: art/scripts/island_gull_anim.py (edit it there). That script writes
 * these functions as Kotlin, runs them as they are for the previews, and checks this compiled class against them over thousands of poses. The
 * geometry and the texture come from island_gull_geo.py and island_gull_paint.py: the texture is HD (the model declares 64x64 and the PNGs
 * are painted at twice that). It is drawn with [RenderTypes.entityCutout] (the wings, the tail and the feet are cut to shape in the texture's
 * alpha, and that type does not cull back faces, so a thin card is seen from both sides).
 */
class GullModel(root: ModelPart) : EntityModel<GullRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    private val body = part("body")
    private val torso = part("torso")
    private val neck1 = part("neck1")
    private val neck2 = part("neck2")
    private val head = part("head")
    private val jaw = part("jaw")
    private val bill = part("bill")
    private val fish = part("fish")
    private val tail = part("tail")
    private val tailL = part("tail_l")
    private val tailR = part("tail_r")

    private val legs = arrayOf(part("leg_l"), part("leg_r"))
    private val shin = arrayOf(part("shin_l"), part("shin_r"))
    private val foot = arrayOf(part("foot_l"), part("foot_r"))
    private val eye = arrayOf(part("eye_l"), part("eye_r"))
    private val arm = arrayOf(part("arm_l"), part("arm_r"))
    private val fore = arrayOf(part("fore_l"), part("fore_r"))
    private val hand = arrayOf(part("hand_l"), part("hand_r"))
    private val armS = arrayOf(part("arm_l_s"), part("arm_r_s"))
    private val foreS = arrayOf(part("fore_l_s"), part("fore_r_s"))
    private val pri = arrayOf(part("pri_l1"), part("pri_l2"), part("pri_l3"), part("pri_l4"), part("pri_r1"), part("pri_r2"), part("pri_r3"), part("pri_r4"))

    override fun setupAnim(state: GullRenderState) {
        super.setupAnim(state)
        poseAll(state)
    }

    // What the animation does to a part: turn it, move it, scale it (set, not added to), show or hide it
    private fun turn(p: ModelPart, x: Float, y: Float, z: Float) {
        p.xRot += x
        p.yRot += y
        p.zRot += z
    }

    private fun shift(p: ModelPart, x: Float, y: Float, z: Float) {
        p.x += x
        p.y += y
        p.z += z
    }

    private fun squash(p: ModelPart, x: Float, y: Float, z: Float) {
        p.xScale = x
        p.yScale = y
        p.zScale = z
    }

    private fun show(p: ModelPart, on: Boolean) {
        p.visible = on
    }

    private fun clamp(x: Float, lo: Float, hi: Float): Float {
        return min(hi, max(lo, x))
    }

    private fun mix(a: Float, b: Float, t: Float): Float {
        return a + (b - a) * t
    }

    /**
     * 0 at [a] and below, 1 at [b] and above, eased between.
     */
    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = clamp((x - a) / (b - a), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 0 before [a], eased up to 1 at [b], held to [c], eased down to 0 at [d].
     */
    private fun bump(x: Float, a: Float, b: Float, c: Float, d: Float): Float {
        return smooth(a, b, x) * (1f - smooth(c, d, x))
    }

    /**
     * A soft bump 1 at [c], gone [w] either side.
     */
    private fun pulse(x: Float, c: Float, w: Float): Float {
        val t = clamp(1f - abs(x - c) / w, 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun fract(x: Float): Float {
        return x - floor(x)
    }

    /**
     * A square-ish wave from -1 to 1 with quick soft flanks (a bird's head going from one look to the next).
     */
    private fun dart(x: Float): Float {
        return 2f * smooth(-0.25f, 0.25f, sin(x)) - 1f
    }

    /**
     * [x] brought into -pi..pi.
     */
    private fun wrap(x: Float): Float {
        return x - 2f * PI_F * floor((x + PI_F) / (2f * PI_F))
    }

    /**
     * The wing's elevation through a beat: 1 at the top, -1 at the bottom; the downstroke (top to bottom) is quicker than the upstroke.
     */
    private fun stroke(ph: Float): Float {
        return cos(ph) + 0.15f * sin(2f * ph)
    }

    /**
     * How closed the eyes are (0 open, 1 shut): two blinks of different rhythm laid over each other.
     */
    private fun blink(t: Float): Float {
        val a = fract(t / 57f)
        val b = fract(t / 91f + 0.37f)
        return max(max(0f, 1f - abs(a - 0.03f) / 0.03f), max(0f, 1f - abs(b - 0.025f) / 0.025f))
    }

    /**
     * Ticks into action [a] if that is the one running (else 0).
     */
    private fun atOf(s: GullRenderState, a: Int): Float {
        return if (s.action == a) s.actionTime else 0f
    }

    /**
     * How fully action [a] holds the pose: 0 when another or none runs, eased in over its first [tin] ticks and out over its last [tout].
     */
    private fun ev(s: GullRenderState, a: Int, tin: Float, tout: Float): Float {
        if (s.action != a) {
            return 0f
        }
        return smooth(0f, tin, s.actionTime) * (1f - smooth(ACTION_TICKS[a].toFloat() - tout, ACTION_TICKS[a].toFloat(), s.actionTime))
    }

    /**
     * 0 when nothing is going on with the bird (idle stirring is allowed), up to 1 when an action has it.
     */
    private fun busy(s: GullRenderState): Float {
        var b = 0f
        if (s.action != NONE) {
            b = ev(s, s.action, 6f, 6f)
        }
        return max(b, clamp(s.walk, 0f, 1f))
    }

    private fun wAir(s: GullRenderState): Float {
        var a = clamp(s.flying, 0f, 1f)
        a = mix(a, smooth(TAKEOFF_LIFT, TAKEOFF_LIFT + 5f, atOf(s, TAKEOFF)), ev(s, TAKEOFF, 1.5f, 5f))
        a = mix(a, 1f - smooth(LAND_TOUCH - 3f, LAND_TOUCH + 0.5f, atOf(s, LAND)), ev(s, LAND, 1.5f, 4f))
        a = mix(a, 1f, ev(s, DIVE, 2f, 6f))
        return a
    }

    private fun wFloat(s: GullRenderState): Float {
        return (1f - wAir(s)) * clamp(s.floating, 0f, 1f)
    }

    private fun wGround(s: GullRenderState): Float {
        return (1f - wAir(s)) * (1f - clamp(s.floating, 0f, 1f))
    }

    private fun wHover(s: GullRenderState): Float {
        return clamp(s.hover, 0f, 1f) * wAir(s) * (1f - ev(s, DIVE, 2f, 6f))
    }

    private fun diveSpread(at: Float): Float {
        return 1f - 0.9f * smooth(1f, 7f, at) + 0.9f * smooth(27f, 38f, at)
    }

    private fun spreadEff(s: GullRenderState, side: Int): Float {
        var sp = clamp(s.spread, 0f, 1f)
        sp = mix(sp, smooth(1f, 6.5f, atOf(s, TAKEOFF)), ev(s, TAKEOFF, 1.5f, 5f))
        sp = mix(sp, 1f - smooth(LAND_TOUCH + 0.5f, LAND_TOUCH + 8f, atOf(s, LAND)), ev(s, LAND, 1.5f, 4f))
        sp = mix(sp, diveSpread(atOf(s, DIVE)), ev(s, DIVE, 2f, 8f))
        sp = max(sp, 0.6f * ev(s, THREAT, 5f, 5f))
        sp = max(sp, 0.45f * preenLift(s, side))
        return sp
    }

    private fun flapEff(s: GullRenderState): Float {
        var fl = clamp(s.flap, 0f, 1f)
        fl = mix(fl, smooth(4f, 7f, atOf(s, TAKEOFF)), ev(s, TAKEOFF, 1.5f, 5f))
        fl = mix(fl, 1f - smooth(14f, 19f, atOf(s, LAND)), ev(s, LAND, 1.5f, 4f))
        fl = mix(fl, smooth(30f, 36f, atOf(s, DIVE)), ev(s, DIVE, 2f, 8f))
        return fl
    }

    private fun phaseEff(s: GullRenderState): Float {
        var ph = s.flapPhase
        val tk = 2.9f * smooth(7f, 12f, atOf(s, TAKEOFF)) + (2f * PI_F - 2.9f) * smooth(12.5f, 19.5f, atOf(s, TAKEOFF))
        ph = ph + wrap(tk - ph) * ev(s, TAKEOFF, 1f, 1f) * (1f - smooth(17f, 21f, atOf(s, TAKEOFF)))
        val ln = 4f * PI_F * smooth(0f, 16f, atOf(s, LAND))
        ph = ph + wrap(ln - ph) * ev(s, LAND, 1f, 1f) * (1f - smooth(17f, 22f, atOf(s, LAND)))
        val dv = 4f * PI_F * smooth(30f, 46f, atOf(s, DIVE))
        ph = ph + wrap(dv - ph) * ev(s, DIVE, 1f, 1f) * (1f - smooth(44f, 49f, atOf(s, DIVE)))
        return ph
    }

    private fun calm(s: GullRenderState): Float {
        return 1f - busy(s)
    }

    /**
     * Where a resting bird looks (radians, + to its right): it holds each look a while and flicks to the next.
     */
    private fun idleLook(s: GullRenderState): Float {
        return 0.5f * dart(0.071f * s.time + 0.4f) + 0.3f * dart(0.043f * s.time + 2f) + 0.1f * dart(0.19f * s.time + 5f)
    }

    /**
     * Where it holds its head up and down (radians, + down).
     */
    private fun idleTip(s: GullRenderState): Float {
        return 0.12f * dart(0.057f * s.time + 1.1f) + 0.06f * dart(0.13f * s.time)
    }

    /**
     * How far the wing on [side] is lifted for the beak to go under it (the right wing in the first half of the preen, the left in the second).
     */
    private fun preenLift(s: GullRenderState, side: Int): Float {
        val at = atOf(s, PREEN)
        val v = ev(s, PREEN, 4f, 5f)
        return v * (bump(at, 13f, 18f, 26f, 31f) * (1f - side.toFloat()) + bump(at, 46f, 51f, 59f, 64f) * side.toFloat())
    }

    /**
     * The long call: five notes, each a wide gape.
     */
    private fun callGape(s: GullRenderState): Float {
        val at = atOf(s, CALL)
        var g = 0f
        for (k in 0 until 5) {
            g = max(g, pulse(at, 20f + 8.5f * k.toFloat(), 3.4f))
        }
        return g * ev(s, CALL, 4f, 4f)
    }

    /**
     * Pecking: two quick jabs at the ground.
     */
    private fun peckDip(s: GullRenderState): Float {
        val at = atOf(s, PECK)
        return max(pulse(at, 6.5f, 3.2f), pulse(at, 14f, 3.2f)) * ev(s, PECK, 2f, 3f)
    }

    /**
     * The threat: three jabs of the head and beak.
     */
    private fun jab(s: GullRenderState): Float {
        val at = atOf(s, THREAT)
        return max(max(pulse(at, 14f, 2.6f), pulse(at, 25f, 2.6f)), pulse(at, 35f, 2.6f)) * ev(s, THREAT, 5f, 5f)
    }

    /**
     * The ruffle's shudder: a fast wave that swells and dies.
     */
    private fun shake(s: GullRenderState): Float {
        val at = atOf(s, RUFFLE)
        return bump(at, 3f, 6f, 17f, 23f) * sin(1.9f * at) * ev(s, RUFFLE, 2f, 3f)
    }

    private fun puff(s: GullRenderState): Float {
        val at = atOf(s, RUFFLE)
        return bump(at, 0f, 4f, 19f, 29f) * ev(s, RUFFLE, 1f, 1f)
    }

    /**
     * TAKEOFF: how far the bird has sunk to spring (1 at the lowest, at tick 7), 0 once it has sprung.
     */
    private fun crouch(s: GullRenderState): Float {
        val at = atOf(s, TAKEOFF)
        return smooth(0f, 7f, at) * (1f - smooth(7f, 9f, at)) * ev(s, TAKEOFF, 1f, 4f)
    }

    /**
     * TAKEOFF: the stretch of the legs as the feet leave the ground (tick 8).
     */
    private fun spring(s: GullRenderState): Float {
        val at = atOf(s, TAKEOFF)
        return smooth(6.5f, 8.5f, at) * (1f - smooth(8.5f, 13f, at)) * ev(s, TAKEOFF, 1f, 4f)
    }

    /**
     * LAND: the flare, body up and feet forward, from the first ticks to the touchdown (tick 17), then it eases off.
     */
    private fun flare(s: GullRenderState): Float {
        val at = atOf(s, LAND)
        return smooth(2f, 7f, at) * (1f - smooth(LAND_TOUCH + 0.5f, LAND_TOUCH + 7f, at)) * ev(s, LAND, 1.5f, 4f)
    }

    /**
     * LAND: the dip of the legs taking the weight at the touchdown.
     */
    private fun settle(s: GullRenderState): Float {
        val at = atOf(s, LAND)
        return pulse(at, LAND_TOUCH + 2f, 3.5f) * ev(s, LAND, 1.5f, 4f)
    }

    private fun bodyPitch(s: GullRenderState): Float {
        var p = wGround(s) * STAND_PITCH + wFloat(s) * FLOAT_PITCH + wAir(s) * AIR_PITCH
        p += -0.3f * wHover(s)
        p += 0.34f * peckDip(s) + 0.16f * ev(s, THREAT, 5f, 5f) - 0.1f * smooth(11f, 17f, atOf(s, CALL)) * ev(s, CALL, 6f, 6f)
        p += 0.12f * crouch(s) - 0.2f * spring(s) - 0.45f * flare(s)
        p += 0.03f * sin(0.12f * s.time) * wFloat(s)
        return p
    }

    /**
     * How far the body is moved down (px): floating, crouching, bobbing with the beat and the stride.
     */
    private fun bodyDy(s: GullRenderState): Float {
        val f = flapEff(s) * wAir(s)
        val ph = phaseEff(s)
        var d = FLOAT_SINK * wFloat(s)
        d += wFloat(s) * (0.22f * sin(0.11f * s.time) + 0.12f * sin(0.23f * s.time + 1f))
        d += 0.3f * f * cos(ph - 0.4f)
        d += 0.9f * crouch(s) - 0.5f * spring(s) + 0.5f * settle(s)
        d += 0.3f * ev(s, THREAT, 5f, 5f) + 0.6f * peckDip(s)
        d += 0.12f * shake(s) * sin(3.7f * s.time)
        d += 0.1f * clamp(s.walk, 0f, 1f) * cos(2f * s.walkPhase)
        return d
    }

    private fun bodyDz(s: GullRenderState): Float {
        return -0.55f * peckDip(s) - 0.2f * jab(s)
    }

    private fun bodyPose(s: GullRenderState) {
        val sh = shake(s)
        turn(body, bodyPitch(s), 0.17f * sh, 0.05f * sh)
        shift(body, 0f, bodyDy(s), bodyDz(s))
        val pf = 1f + 0.09f * puff(s)
        squash(torso, pf, pf, pf)
    }

    private fun legPose(s: GullRenderState, side: Int) {
        val sg = if (side == 0) 1f else -1f
        val a = wAir(s)
        val th = bodyPitch(s)
        val c = cos(th)
        val sn = sin(th)
        val hy = 24f - BODY_Y + bodyDy(s) + HIP_Y * c - HIP_Z * sn
        val hz = bodyDz(s) + HIP_Y * sn + HIP_Z * c
        val wk = clamp(s.walk, 0f, 1f) * (1f - a)
        val u = fract((s.walkPhase + side.toFloat() * PI_F) / (2f * PI_F))
        var zf = 0f
        if (u < 0.58f) {
            zf = -1f + 2f * u / 0.58f
        } else {
            zf = 1f - 2f * smooth(0.58f, 1f, u)
        }
        val lift = sin(PI_F * clamp((u - 0.58f) / 0.42f, 0f, 1f))
        val ty = SOLE_Y - LIFT * wk * lift
        val tz = HIP_Z - 0.4f + STRIDE * wk * zf
        val dy = ty - hy
        val dz = tz - hz
        val d = clamp(sqrt(dy * dy + dz * dz), abs(L1 - L2) + 0.05f, L1 + L2 - 0.02f)
        val a1 = acos(clamp((L1 * L1 + d * d - L2 * L2) / (2f * L1 * d), -1f, 1f))
        val al1 = atan2(dz, dy) + a1
        val ay = hy + L1 * cos(al1)
        val az = hz + L1 * sin(al1)
        val al2 = atan2(tz - az, ty - ay)
        var x1 = al1 - th
        var x2 = al2 - al1
        var x3 = -al2 + 0.35f * wk * lift
        var p1 = LEG_TUCK[0]
        var p2 = LEG_TUCK[1]
        var p3 = LEG_TUCK[2]
        val hv = wHover(s)
        p1 = mix(p1, LEG_DANGLE[0] + 0.1f * sin(0.37f * s.time + side.toFloat()), hv)
        p2 = mix(p2, LEG_DANGLE[1] + 0.12f * sin(0.37f * s.time + side.toFloat() + 1f), hv)
        p3 = mix(p3, LEG_DANGLE[2], hv)
        val lf = flare(s)
        p1 = mix(p1, LEG_FLARE[0], lf)
        p2 = mix(p2, LEG_FLARE[1], lf)
        p3 = mix(p3, LEG_FLARE[2], lf)
        x1 = mix(x1, p1, a)
        x2 = mix(x2, p2, a)
        x3 = mix(x3, p3, a)
        turn(legs[side], x1, 0f, -sg * 0.07f)
        turn(shin[side], x2, 0f, 0f)
        turn(foot[side], x3, 0f, 0f)
        val fw = 1f - 0.5f * a * (1f - lf)
        squash(foot[side], fw, 1f, 1f)
    }

    private fun neckHead(s: GullRenderState) {
        val a = wAir(s)
        val f = wFloat(s)
        val g = wGround(s)
        var n1x = g * STAND_NECK[0] + f * FLOAT_NECK[0] + a * AIR_NECK[0]
        var n2x = g * STAND_NECK[1] + f * FLOAT_NECK[1] + a * AIR_NECK[1]
        var hx = g * STAND_NECK[2] + f * FLOAT_NECK[2] + a * AIR_NECK[2]
        var n1y = 0f
        var n2y = 0f
        var hy = 0f
        var gape = clamp(s.beak, 0f, 1f)
        var squint = 0f
        val look = s.headYaw * DEG + (idleLook(s) * (1f - 0.5f * a) + 0.35f * s.bank) * calm(s)
        val tip = s.headPitch * DEG + idleTip(s) * calm(s)
        val hover = wHover(s)
        hx += 0.35f * hover
        n1x += -0.12f * tip
        n2x += -0.3f * tip
        hx += -0.58f * tip
        n1y += 0.25f * look
        n2y += 0.35f * look
        hy += 0.4f * look
        val pk = peckDip(s)
        val lk = ev(s, PECK, 3f, 4f)
        n1x += 0.3f * lk + 0.32f * pk
        n2x += 0.2f * lk + 0.5f * pk
        hx += 0.3f * lk + 0.5f * pk
        val at = atOf(s, CALL)
        val cv = ev(s, CALL, 3f, 6f)
        val bow = bump(at, 0f, 5f, 9f, 14f) * cv
        val up = smooth(11f, 17f, at) * (1f - smooth(54f, 62f, at)) * cv
        val cg = callGape(s)
        n1x += 0.35f * bow - 0.45f * up
        n2x += 0.3f * bow + 0.45f * up
        hx += 0.6f * bow - 1.05f * up - 0.25f * cg
        gape = max(gape, 0.95f * cg + 0.3f * up + 0.2f * bow)
        val pa = atOf(s, PREEN)
        val pv = ev(s, PREEN, 4f, 5f)
        val pr = bump(pa, 3f, 9f, 30f, 36f)
        val pl = bump(pa, 36f, 42f, 62f, 68f)
        val toward = (pr - pl) * pv
        val work = pv * (pr + pl) * sin(0.55f * pa)
        val nib = pv * (pr + pl) * (0.5f + 0.5f * sin(1.7f * pa))
        n1y += 0.55f * toward + 0.1f * work
        n2y += 0.8f * toward + 0.16f * work
        hy += 0.75f * toward + 0.22f * work
        n1x += 0.3f * pv * (pr + pl)
        n2x += 0.1f * pv * (pr + pl)
        hx += 0.55f * pv * (pr + pl) + 0.1f * nib
        gape = max(gape, 0.3f * nib)
        squint = max(squint, 0.55f * pv * (pr + pl))
        val tv = ev(s, THREAT, 5f, 5f)
        val jb = jab(s)
        n1x += 0.3f * tv + 0.25f * jb
        n2x += 0.45f * tv
        hx += 0.15f * tv + 0.3f * jb
        gape = max(gape, 0.7f * jb + 0.15f * tv)
        val sh = shake(s)
        hy += 0.25f * sh
        n2y += 0.12f * sh
        squint = max(squint, 0.5f * ev(s, RUFFLE, 2f, 3f))
        val tc = crouch(s)
        val sp = spring(s)
        n1x += 0.25f * tc - 0.25f * sp
        hx += 0.15f * tc
        val ln = flare(s)
        n1x += -0.3f * ln
        n2x += 0.2f * ln
        hx += 0.35f * ln
        val dv = ev(s, DIVE, 3f, 8f)
        val dd = bump(atOf(s, DIVE), 0f, 6f, 24f, 32f) * dv
        n1x += -0.3f * dd
        n2x += 0.45f * dd
        hx += 0.35f * dd
        gape = max(gape, 0.12f * dd)
        turn(neck1, n1x, n1y, 0f)
        turn(neck2, n2x, n2y, 0f)
        turn(head, hx, hy, 0f - 0.18f * s.bank * a)
        turn(jaw, 0.75f * gape, 0f, 0f)
        turn(bill, -0.16f * gape, 0f, 0f)
        val cs = 1f + 0.14f * cg
        squash(neck2, cs, 1f, cs)
        squash(head, 1f / cs, 1f, 1f / cs)
        val bl = max(blink(s.time), squint)
        for (k in 0 until 2) {
            squash(eye[k], 1f, max(0.12f, 1f - 0.9f * bl), 1f)
        }
        show(fish, s.fish)
    }

    private fun wingPose(s: GullRenderState, side: Int) {
        val sg = if (side == 0) 1f else -1f
        val sp = spreadEff(s, side)
        val f = flapEff(s) * clamp(sp * 2f, 0f, 1f)
        val ph = phaseEff(s)
        val a = wAir(s)
        val o = sg * clamp(s.bank, -1f, 1f) * a
        val hv = wHover(s)
        val eArm = smooth(0.12f, 1f, sp)
        val eEl = smooth(0f, 0.8f, sp)
        val eWr = smooth(0f, 0.75f, sp)
        val eFl = smooth(0f, 0.6f, sp)
        var arA = 0.15f + f * (0.42f * stroke(ph) - 0.06f) + o * 0.16f
        var foA = 0.14f + f * (0.6f * stroke(ph - 0.5f) - 0.08f) + o * 0.16f
        var haA = -0.1f + f * (0.8f * stroke(ph - 1.05f) - 0.1f) + o * 0.1f
        val fx = clamp(0.5f - 0.5f * stroke(ph - 1f), 0f, 1f) * f
        val tv = ev(s, THREAT, 5f, 5f)
        arA += 0.5f * tv + 0.1f * tv * sin(1.3f * s.time + side.toFloat())
        foA += 0.35f * tv
        haA += -0.25f * tv
        val sh = shake(s)
        arA += 0.1f * sh * sin(1.3f + side.toFloat()) + 0.1f * puff(s)
        val ln = flare(s)
        arA += 0.2f * ln
        haA += 0.2f * ln
        arA += 0.07f * hv * sin(0.21f * s.time + side.toFloat() * 2f)
        haA += hv * (0.2f * sin(0.55f * s.time + side.toFloat() * 1.7f) + 0.08f * sin(0.9f * s.time))
        val soar = (1f - f) * a * (1f - hv)
        arA += 0.03f * soar * sin(0.07f * s.time + side.toFloat() * 1.3f)
        haA += 0.05f * soar * sin(0.11f * s.time + side.toFloat() * 2.1f)
        val armB = -0.1f - 0.1f * f * sin(ph) - 0.12f * o
        val foreB = 0.04f + 0.3f * fx
        val handB = 0.22f + 0.45f * fx - 0.1f * o - 0.45f * ln - 0.15f * tv
        val armC = -0.2f * f * sin(ph) + 0.35f * ln
        val handC = -0.25f * f * sin(ph - 0.8f) + 0.2f * ln
        val armA = mix(FOLD_ARM[2], arA, eArm)
        val armBb = mix(FOLD_ARM[1], armB, eArm)
        val armCc = mix(FOLD_ARM[0], armC, eArm)
        val foreA = mix(FOLD_FORE[2], foA - arA, eEl)
        val foreBb = mix(FOLD_FORE[1], foreB, eEl)
        val handA = mix(FOLD_HAND[2], haA - foA, eWr)
        val handBb = mix(FOLD_HAND[1], handB, eWr)
        val handCc = mix(FOLD_HAND[0], handC, eWr)
        turn(arm[side], -armCc, -sg * armBb, -sg * armA)
        shift(arm[side], sg * FOLD_POS[0] * (1f - eArm), FOLD_POS[1] * (1f - eArm), FOLD_POS[2] * (1f - eArm))
        turn(fore[side], -FOLD_FORE[0] * (1f - eEl), -sg * foreBb, -sg * foreA)
        turn(hand[side], -handCc, -sg * handBb, -sg * handA)
        shift(fore[side], 0f, FOLD_FORE[3] * (1f - eEl), 0f)
        shift(hand[side], 0f, FOLD_HAND[3] * (1f - eWr), 0f)
        val camber = -0.1f + 0.18f * f * sin(ph - 0.3f) - 0.25f * ln
        turn(armS[side], mix(FOLD_FLAP, camber, eFl), 0f, 0f)
        turn(foreS[side], mix(FOLD_FLAP, camber, eFl), 0f, 0f)
        for (k in 0 until 4) {
            val i = side * 4 + k
            val fan = FAN[k] * (0.85f + 0.55f * fx - 0.35f * f * max(0f, sin(ph)) + 0.5f * ln + 0.3f * tv + 0.4f * hv)
            val bend = FLEX[k] * (0.1f + 0.34f * f * sin(ph - 0.2f) + 0.08f * soar * sin(0.17f * s.time + k.toFloat()))
            turn(pri[i], 0f, -sg * fan * eWr, -sg * bend * eWr)
        }
    }

    private fun tailPose(s: GullRenderState) {
        val f = flapEff(s)
        val ph = phaseEff(s)
        val a = wAir(s)
        val hv = wHover(s)
        val ln = flare(s)
        var pitch = 0.08f * wGround(s) - 0.25f * wFloat(s) + 0.03f * a - 0.1f * f * cos(ph - 0.5f)
        pitch += 0.4f * hv + 0.45f * ln - 0.25f * ev(s, THREAT, 5f, 5f) - 0.15f * crouch(s) + 0.3f * spring(s)
        pitch += -0.15f * peckDip(s) - 0.1f * callGape(s)
        val flick = pulse(fract(s.time / 141f + 0.2f), 0.5f, 0.012f) * calm(s) * (1f - a)
        pitch += -0.3f * flick
        val fan = 0.05f + 0.1f * a + 0.35f * hv + 0.3f * ln + 0.3f * ev(s, THREAT, 5f, 5f) + 0.15f * spring(s)
        val sh = shake(s)
        turn(tail, pitch, -0.25f * clamp(s.bank, -1f, 1f) * a + 0.35f * sh + 0.3f * flick * sin(2.2f * s.time), 0f)
        turn(tailL, 0f, fan, 0f)
        turn(tailR, 0f, -fan, 0f)
    }

    private fun poseAll(s: GullRenderState) {
        bodyPose(s)
        for (side in 0 until 2) {
            legPose(s, side)
            wingPose(s, side)
        }
        neckHead(s)
        tailPose(s)
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "gull"), "main")

        /**
         * How long each action lasts, in ticks, indexed by the action constants of [GullRenderState] (0, `NONE`, is not an action: its entry is
         * unused). The entity runs `actionTime` from 0 to this and then back to `NONE`: the pose blends in over the first few ticks and out over
         * the last few, so ending an action there never pops.
         */
        val ACTION_TICKS = intArrayOf(0, 70, 64, 22, 30, 44, 22, 26, 50)

        /** How high the middle of the body is over the soles of the feet when it stands (px): the point the renderer pitches and rolls the bird about. */
        const val BODY_Y = 3.5f

        /** How far the body sinks while it floats (px): it rides with its belly in the water, so the entity sits it on the surface at its feet's level. */
        const val FLOAT_SINK = 3f

        /** Ticks into TAKEOFF at which the feet leave the ground: the entity starts to rise then (before it, the bird only crouches and springs). */
        const val TAKEOFF_LIFT = 8f

        /** Ticks into LAND at which the feet touch the ground (before it, the bird flares in the air; after it, it settles and folds its wings). */
        const val LAND_TOUCH = 17f

        /** Ticks into DIVE at which the beak enters the water, and at which the bird comes up again (wings folded in between, then opening to climb out). */
        const val DIVE_HIT = 20f
        const val DIVE_SURFACE = 34f

        private const val PI_F = 3.1415927f

        // The numbers the animation uses (the same as in island_gull_anim.py)
        private val AIR_NECK = floatArrayOf(1.25f, -0.6f, -0.45f)
        private const val AIR_PITCH = -0.02f
        private const val CALL = GullRenderState.CALL
        private const val DEG = 0.0174532925f
        private const val DIVE = GullRenderState.DIVE
        private val FAN = floatArrayOf(0.46f, 0.3f, 0.15f, 0f)
        private val FLEX = floatArrayOf(0.45f, 0.65f, 0.85f, 1f)
        private val FLOAT_NECK = floatArrayOf(0.6f, -0.55f, -0.05f)
        private const val FLOAT_PITCH = -0.12f
        private val FOLD_ARM = floatArrayOf(0f, 1.45f, -0.9f)
        private const val FOLD_FLAP = -2.9f
        private val FOLD_FORE = floatArrayOf(0f, -2.9f, 0f, 0.5f)
        private val FOLD_HAND = floatArrayOf(0f, 2.9f, 0f, 0.5f)
        private val FOLD_POS = floatArrayOf(0.3f, 0f, 0f)
        private const val HIP_Y = 0.8f
        private const val HIP_Z = 0.5f
        private const val L1 = 1.3f
        private const val L2 = 1.5f
        private const val LAND = GullRenderState.LAND
        private val LEG_DANGLE = floatArrayOf(0.3f, 0.35f, -0.2f)
        private val LEG_FLARE = floatArrayOf(-0.6f, 0.5f, -0.3f)
        private val LEG_TUCK = floatArrayOf(1.45f, -0.1f, 1.7f)
        private const val LIFT = 0.8f
        private const val NONE = GullRenderState.NONE
        private const val PECK = GullRenderState.PECK
        private const val PREEN = GullRenderState.PREEN
        private const val RUFFLE = GullRenderState.RUFFLE
        private const val SOLE_Y = 23.75f
        private val STAND_NECK = floatArrayOf(0.85f, -0.75f, -0.02f)
        private const val STAND_PITCH = -0.2f
        private const val STRIDE = 0.9f
        private const val TAKEOFF = GullRenderState.TAKEOFF
        private const val THREAT = GullRenderState.THREAT

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            buildGull1(root)
            return LayerDefinition.create(mesh, 64, 64)
        }

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        private fun buildGull1(p: PartDefinition) {
            val body = p.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0f, 20.5f, 0f))
            body.addOrReplaceChild("torso", CubeListBuilder.create().texOffs(55, 7).addBox(-1f, -0.1f, -3.5f, 2f, 1f, 1f).texOffs(60, 3).addBox(-0.5f, -0.6f, -3.5f, 1f, 2f, 1f).texOffs(33, 0).addBox(-1.5f, -0.9f, -2.5f, 3f, 2f, 1f).texOffs(41, 0).addBox(-1f, -1.4f, -2.5f, 2f, 3f, 1f).texOffs(47, 0).addBox(-1.5f, -1f, -1.5f, 3f, 2f, 1f).texOffs(55, 0).addBox(-1f, -1.5f, -1.5f, 2f, 3f, 1f).texOffs(33, 3).addBox(-1.5f, -1f, -0.5f, 3f, 2f, 1f).texOffs(46, 3).addBox(-1f, -1.5f, -0.5f, 2f, 3f, 1f).texOffs(52, 4).addBox(-1.5f, -0.85f, 0.5f, 3f, 1.5f, 1f).texOffs(0, 4).addBox(-1f, -1.35f, 0.5f, 2f, 2.5f, 1f).texOffs(31, 6).addBox(-1.25f, -0.7f, 1.5f, 2.5f, 1f, 1f).texOffs(18, 6).addBox(-0.75f, -1.2f, 1.5f, 1.5f, 2f, 1f).texOffs(21, 10).addBox(-1f, -0.55f, 2.5f, 2f, 0.5f, 1f).texOffs(14, 0).addBox(-0.5f, -1.05f, 2.5f, 1f, 1.5f, 1f), PartPose.offset(0f, 0f, 0f))
            val neck1 = body.addOrReplaceChild("neck1", CubeListBuilder.create().texOffs(0, 0).addBox(-1.25f, -1.5f, -0.75f, 2.5f, 2f, 1.5f).texOffs(8, 0).addBox(-0.75f, -1.5f, -1.25f, 1.5f, 2f, 2.5f), PartPose.offset(0f, -0.8f, -2.4f))
            val neck2 = neck1.addOrReplaceChild("neck2", CubeListBuilder.create().texOffs(6, 5).addBox(-1f, -1.5f, -0.5f, 2f, 2f, 1f).texOffs(39, 4).addBox(-0.5f, -1.5f, -1f, 1f, 2f, 2f), PartPose.offset(0f, -1.3f, 0f))
            val head = neck2.addOrReplaceChild("head", CubeListBuilder.create().texOffs(16, 0).addBox(-1f, -0.75f, -1.75f, 2f, 1.5f, 2.5f).texOffs(25, 0).addBox(-0.75f, -1f, -1.75f, 1.5f, 2f, 2.5f), PartPose.offset(0f, -1.3f, 0f))
            head.addOrReplaceChild("bill", CubeListBuilder.create().texOffs(32, 10).addBox(-0.5f, -0.5f, -1f, 1f, 1f, 1f).texOffs(11, 5).addBox(-0.5f, -0.5f, -1.5f, 1f, 0.5f, 0.5f).texOffs(30, 5).addBox(-0.25f, -0.5f, -2f, 0.5f, 1f, 0.5f), PartPose.offset(0f, 0.15f, -1.75f))
            head.addOrReplaceChild("jaw", CubeListBuilder.create().texOffs(16, 11).addBox(-0.5f, -0.25f, -0.75f, 1f, 0.5f, 1f).texOffs(40, 11).addBox(-0.5f, -0.25f, -1.25f, 1f, 0.5f, 0.5f).texOffs(43, 11).addBox(-0.25f, -0.25f, -1.75f, 0.5f, 1f, 0.5f), PartPose.offset(0f, 0.5f, -1f))
            val fish = head.addOrReplaceChild("fish", CubeListBuilder.create().texOffs(15, 4).addBox(-2.2f, -0.5f, -0.5f, 4.5f, 1f, 1f), PartPose.offset(0f, 0.5f, -2.75f))
            fish.addOrReplaceChild("fish_tail", CubeListBuilder.create().texOffs(43, 4).addBox(0f, -0.5f, -0.25f, 1f, 1f, 0.5f, FACES_TBFK), PartPose.offset(2.2f, 0f, 0f))
            head.addOrReplaceChild("eye_l", CubeListBuilder.create().texOffs(23, 6).addBox(0.85f, -0.5f, -0.25f, 0.5f, 1f, 0.5f), PartPose.offset(0f, -0.2f, -1f))
            head.addOrReplaceChild("eye_r", CubeListBuilder.create().texOffs(38, 11).addBox(-1.35f, -0.5f, -0.25f, 0.5f, 1f, 0.5f), PartPose.offset(0f, -0.2f, -1f))
            val tail = body.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(4, 10).addBox(-0.75f, -0.25f, 0f, 1.5f, 0.5f, 2.5f, FACES_TB), PartPose.offset(0f, -0.2f, 2.8f))
            tail.addOrReplaceChild("tail_l", CubeListBuilder.create().texOffs(28, 0).addBox(0f, -0.25f, 0f, 1f, 0.5f, 2.5f, FACES_TB), PartPose.offset(0.75f, 0f, 0f))
            tail.addOrReplaceChild("tail_r", CubeListBuilder.create().texOffs(59, 0).addBox(-1f, -0.25f, 0f, 1f, 0.5f, 2.5f, FACES_TB), PartPose.offset(-0.75f, 0f, 0f))
            val arm_l = body.addOrReplaceChild("arm_l", CubeListBuilder.create().texOffs(43, 7).addBox(0f, -0.25f, -1f, 2.5f, 0.5f, 2f, FACES_TB).texOffs(50, 3).addBox(0f, -0.75f, -1f, 2.5f, 0.5f, 1f, FACES_T).texOffs(10, 9).addBox(0f, -0.5f, -1.5f, 2.5f, 1f, 0.5f), PartPose.offset(0.9f, -1.1f, -0.9f))
            arm_l.addOrReplaceChild("arm_l_s", CubeListBuilder.create().texOffs(37, 9).addBox(0f, -0.25f, 0f, 2.5f, 0.5f, 1.5f, FACES_TB), PartPose.offset(0f, 0f, 0.9f))
            val fore_l = arm_l.addOrReplaceChild("fore_l", CubeListBuilder.create().texOffs(52, 9).addBox(0f, -0.25f, -1f, 2.5f, 0.5f, 1.5f, FACES_TB).texOffs(41, 8).addBox(0f, -0.75f, -1f, 2.5f, 0.5f, 1f, FACES_T), PartPose.offset(2.5f, 0f, 0f))
            fore_l.addOrReplaceChild("fore_l_s", CubeListBuilder.create().texOffs(47, 9).addBox(0f, -0.25f, 0f, 2.5f, 0.5f, 1.5f, FACES_TB), PartPose.offset(0f, 0f, 0.4f))
            val hand_l = fore_l.addOrReplaceChild("hand_l", CubeListBuilder.create().texOffs(5, 0).addBox(-0.2f, -0.25f, -0.8f, 2f, 0.5f, 1.5f, FACES_TB), PartPose.offset(2.5f, 0f, 0f))
            hand_l.addOrReplaceChild("pri_l1", CubeListBuilder.create().texOffs(25, 10).addBox(0f, -0.25f, -0.5f, 3.5f, 0.5f, 1f, FACES_TB), PartPose.offset(0.5f, 0.12f, 0.9f))
            hand_l.addOrReplaceChild("pri_l2", CubeListBuilder.create().texOffs(21, 9).addBox(0f, -0.25f, -0.5f, 4f, 0.5f, 1f, FACES_TB), PartPose.offset(0.9f, 0.08f, 0.5f))
            hand_l.addOrReplaceChild("pri_l3", CubeListBuilder.create().texOffs(32, 8).addBox(0f, -0.25f, -0.5f, 4.5f, 0.5f, 1f, FACES_TB), PartPose.offset(1.3f, 0.04f, 0.1f))
            hand_l.addOrReplaceChild("pri_l4", CubeListBuilder.create().texOffs(0, 8).addBox(0f, -0.25f, -0.5f, 5f, 0.5f, 1f, FACES_TB), PartPose.offset(1.5f, 0f, -0.3f))
            val leg_l = body.addOrReplaceChild("leg_l", CubeListBuilder.create().texOffs(25, 5).addBox(-0.75f, -0.25f, -0.75f, 1.5f, 1.5f, 1.5f), PartPose.offset(0.9f, 0.8f, 0.5f))
            val shin_l = leg_l.addOrReplaceChild("shin_l", CubeListBuilder.create().texOffs(36, 10).addBox(-0.25f, -0.25f, -0.25f, 0.5f, 2f, 0.5f), PartPose.offset(0f, 1.3f, 0f))
            shin_l.addOrReplaceChild("foot_l", CubeListBuilder.create().texOffs(21, 0).addBox(-1f, -0.25f, -1.75f, 2f, 0.5f, 2f, FACES_TB), PartPose.offset(0f, 1.5f, 0f))
            val arm_r = body.addOrReplaceChild("arm_r", CubeListBuilder.create().texOffs(48, 7).addBox(-2.5f, -0.25f, -1f, 2.5f, 0.5f, 2f, FACES_TB).texOffs(4, 4).addBox(-2.5f, -0.75f, -1f, 2.5f, 0.5f, 1f, FACES_T).texOffs(16, 9).addBox(-2.5f, -0.5f, -1.5f, 2.5f, 1f, 0.5f), PartPose.offset(-0.9f, -1.1f, -0.9f))
            arm_r.addOrReplaceChild("arm_r_s", CubeListBuilder.create().texOffs(42, 9).addBox(-2.5f, -0.25f, 0f, 2.5f, 0.5f, 1.5f, FACES_TB), PartPose.offset(0f, 0f, 0.9f))
            val fore_r = arm_r.addOrReplaceChild("fore_r", CubeListBuilder.create().texOffs(0, 10).addBox(-2.5f, -0.25f, -1f, 2.5f, 0.5f, 1.5f, FACES_TB).texOffs(60, 8).addBox(-2.5f, -0.75f, -1f, 2.5f, 0.5f, 1f, FACES_T), PartPose.offset(-2.5f, 0f, 0f))
            fore_r.addOrReplaceChild("fore_r_s", CubeListBuilder.create().texOffs(57, 9).addBox(-2.5f, -0.25f, 0f, 2.5f, 0.5f, 1.5f, FACES_TB), PartPose.offset(0f, 0f, 0.4f))
            val hand_r = fore_r.addOrReplaceChild("hand_r", CubeListBuilder.create().texOffs(26, 11).addBox(-2f, -0.25f, -0.8f, 2f, 0.5f, 1.5f, FACES_TB), PartPose.offset(-2.5f, 0f, 0f))
            hand_r.addOrReplaceChild("pri_r1", CubeListBuilder.create().texOffs(9, 11).addBox(-3.5f, -0.25f, -0.5f, 3.5f, 0.5f, 1f, FACES_TB), PartPose.offset(-0.5f, 0.12f, 0.9f))
            hand_r.addOrReplaceChild("pri_r2", CubeListBuilder.create().texOffs(29, 9).addBox(-4f, -0.25f, -0.5f, 4f, 0.5f, 1f, FACES_TB), PartPose.offset(-0.9f, 0.08f, 0.5f))
            hand_r.addOrReplaceChild("pri_r3", CubeListBuilder.create().texOffs(0, 9).addBox(-4.5f, -0.25f, -0.5f, 4.5f, 0.5f, 1f, FACES_TB), PartPose.offset(-1.3f, 0.04f, 0.1f))
            hand_r.addOrReplaceChild("pri_r4", CubeListBuilder.create().texOffs(22, 8).addBox(-5f, -0.25f, -0.5f, 5f, 0.5f, 1f, FACES_TB), PartPose.offset(-1.5f, 0f, -0.3f))
            val leg_r = body.addOrReplaceChild("leg_r", CubeListBuilder.create().texOffs(12, 6).addBox(-0.75f, -0.25f, -0.75f, 1.5f, 1.5f, 1.5f), PartPose.offset(-0.9f, 0.8f, 0.5f))
            val shin_r = leg_r.addOrReplaceChild("shin_r", CubeListBuilder.create().texOffs(0, 11).addBox(-0.25f, -0.25f, -0.25f, 0.5f, 2f, 0.5f), PartPose.offset(0f, 1.3f, 0f))
            shin_r.addOrReplaceChild("foot_r", CubeListBuilder.create().texOffs(58, 6).addBox(-1f, -0.25f, -1.75f, 2f, 0.5f, 2f, FACES_TB), PartPose.offset(0f, 1.5f, 0f))
        }

        private val FACES_T: Set<Direction> = EnumSet.of(Direction.DOWN)
        private val FACES_TB: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP)
        private val FACES_TBFK: Set<Direction> = EnumSet.of(Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH)
    }
}
