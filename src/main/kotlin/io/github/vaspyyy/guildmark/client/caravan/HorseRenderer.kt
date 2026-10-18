package io.github.vaspyyy.guildmark.client.caravan

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.caravan.DraughtHorse
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.RigAnimator
import io.github.vaspyyy.guildmark.townsfolk.Activity
import net.minecraft.client.Minecraft
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A draught horse as this client draws it at one moment: its middle's ground at [x], [y], [z], facing [yaw], its nose
 * [pitch] up; where the points of its model's space (DraughtHorseMesh's: px, y down, the ground at 24) are in the world.
 */
internal class HorsePose {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var yaw = 0f
    var pitch = 0f
    private val q = Quaternionf()
    private val v = Vector3f()

    fun set(x: Double, y: Double, z: Double, yaw: Float, pitch: Float): HorsePose {
        this.x = x
        this.y = y
        this.z = z
        this.yaw = yaw
        this.pitch = pitch
        q.rotationY(Math.toRadians(180.0 - yaw).toFloat()).rotateX(Math.toRadians(pitch.toDouble()).toFloat())
        return this
    }

    /** Where [m] (px, the model's space) is in the world. */
    fun world(m: Vector3f, out: Vector3d): Vector3d {
        v.set(-m.x / 16f, 1.5f - m.y / 16f, m.z / 16f)
        q.transform(v)
        return out.set(x + v.x, y + v.y, z + v.z)
    }
}

/**
 * What this client keeps of one horse from tick to tick: how far it has walked (its gaits play by distance), how
 * fast it's going, and its clips' weights eased in and out (the stand's idle, grazing and the nosebag; the walk,
 * the strain in the collar and the bolt; the lie), the one-shot it's playing (a rear, a hit, the collapse), and
 * where each of them was last tick, for the sounds on their moments.
 */
internal class HorseLook {
    var fresh = true

    /** Blocks walked, and last tick's. */
    var stride = 0f
    var strideO = 0f
    var speed = 0f

    var walk = 0f
    var walkO = 0f
    var strain = 0f
    var strainO = 0f
    var bolt = 0f
    var boltO = 0f
    var graze = 0f
    var grazeO = 0f
    var bag = 0f
    var bagO = 0f
    var lie = 0f
    var lieO = 0f

    /** The server's count of moves begun, and the one-shot playing and the tick (the horse's own) it began. */
    var serial = Int.MIN_VALUE
    var shot: DraughtHorse.Move? = null
    var shotAt = 0

    /** Where each clip was last tick, for its moments. */
    var walkWas = 0f
    var boltWas = 0f
    var standWas = 0f
    var standClip = ""
    var shotWas = -1f

    /** What's under its hooves, looked at now and then. */
    var ground = CaravanSounds.Ground.EARTH
}

/** What a draught horse's model needs to pose it. */
class HorseRenderState : EntityRenderState() {
    var yaw = 0f
    var pitch = 0f
    var coat = 0

    /** The stand's loops, by time: idle under all, grazing and the nosebag over it as they're eased in. */
    var idleTime = 0f
    var grazeTime = 0f
    var grazeWeight = 0f
    var bagTime = 0f
    var bagWeight = 0f

    /** The gaits, by distance: the walk (and the strain in the collar, at the walk's phase) and the bolt. */
    var walkTime = 0f
    var walkWeight = 0f
    var strainWeight = 0f
    var boltTime = 0f
    var boltWeight = 0f

    /** Lying dead, and a one-shot over everything (a rear, a hit, the collapse). */
    var lieWeight = 0f
    var shot: Clip? = null
    var shotTime = 0f
    var shotWeight = 0f

    /** Its nosebag on; its reins tied up at the saddle; its traces cut; dead (the reins cut too). */
    var nosebag = false
    var reinsTied = false
    var cut = false
    var dead = false
}

/**
 * The client half of the draught horses, ticked from DraughtHorse.clientTick: its gaits by the ground it covers,
 * leaning into the collar uphill and as the wagon starts off (from the flick of the reins), its moves eased into
 * one another, and the sounds on its clips' moments (the hooves on whatever they're on, the bells on the collar with
 * the walk, the snorts, the neighs, coming down).
 */
internal object HorseClient {
    fun look(h: DraughtHorse): HorseLook = h.look as? HorseLook ?: HorseLook().also { h.look = it }

    fun tick(h: DraughtHorse) {
        val look = look(h)
        val first = look.fresh
        look.fresh = false
        val dx = h.x - h.xOld
        val dz = h.z - h.zOld
        val moved = sqrt(dx * dx + dz * dz).toFloat().coerceAtMost(1f)
        look.strideO = look.stride
        look.stride += moved
        if (look.stride > STRIDE_WRAP) {
            look.stride -= STRIDE_WRAP
            look.strideO -= STRIDE_WRAP
        }
        look.speed = if (first) moved else look.speed + (moved - look.speed) * 0.4f

        // A move begun: a one-shot starts over
        val move = h.move
        if (h.serial != look.serial) {
            look.serial = h.serial
            if (!move.loop) {
                look.shot = move
                look.shotAt = h.tickCount
                look.shotWas = -1f
            } else if (move == DraughtHorse.Move.LIE && look.shot != DraughtHorse.Move.COLLAPSE) {
                look.shot = null
            }
            if (first && move == DraughtHorse.Move.LIE) look.shot = null
        }

        look.walkO = look.walk
        look.strainO = look.strain
        look.boltO = look.bolt
        look.grazeO = look.graze
        look.bagO = look.bag
        look.lieO = look.lie
        val bolting = move == DraughtHorse.Move.BOLT
        val going = smooth((look.speed - 0.004f) / 0.031f)
        val gaitOk = move == DraughtHorse.Move.WALK || move == DraughtHorse.Move.BOLT || move == DraughtHorse.Move.IDLE
        look.bolt = ease(look.bolt, if (bolting) 1f else 0f, 0.3f, first)
        look.walk = ease(look.walk, if (bolting || !gaitOk) 0f else going, 0.2f, first)
        look.strain = ease(look.strain, if (straining(h)) 1f else 0f, 0.08f, first)
        look.graze = ease(look.graze, if (move == DraughtHorse.Move.GRAZE) 1f else 0f, 1f / 12f, first)
        look.bag = ease(look.bag, if (move == DraughtHorse.Move.NOSEBAG) 1f else 0f, 1f / 12f, first)
        look.lie = ease(look.lie, if (move == DraughtHorse.Move.LIE) 1f else 0f, 0.25f, first)

        moments(h, look)
    }

    private fun ease(now: Float, target: Float, rate: Float, first: Boolean): Float = if (first) target else Mth.approach(now, target, rate)

    /**
     * Leaning into the collar: as the server says (a climb, the wagon starting off), but from the flick of the reins on,
     * not before, while the driver urges it on.
     */
    private fun straining(h: DraughtHorse): Boolean {
        if (!h.has(DraughtHorse.FLAG_STRAIN)) return false
        val wagon = h.level().getEntity(h.wagonId) as? Wagon ?: return true
        val driver = WagonClient.driver(wagon) ?: return true
        if (driver.shownActivity != Activity.DRIVE_URGE) return true
        return driver.tickCount - driver.shownStart >= CrewMoves.URGE_FLICK
    }

    /** Fills [s] for drawing [h] at [partial] (its renderer, and the wagon's to find its hames and tugs). */
    fun fill(h: DraughtHorse, s: HorseRenderState, partial: Float) {
        val look = look(h)
        s.yaw = h.getYRot(partial)
        s.pitch = -h.getXRot(partial)
        s.coat = h.coat
        val age = h.tickCount + partial + (h.id % 97) * 13f
        s.idleTime = loop(age, IDLE_LENGTH)
        s.grazeTime = loop(age, GRAZE_LENGTH)
        s.grazeWeight = Mth.lerp(partial, look.grazeO, look.graze)
        s.bagTime = loop(age, BAG_LENGTH)
        s.bagWeight = Mth.lerp(partial, look.bagO, look.bag)
        val stride = Mth.lerp(partial, look.strideO, look.stride)
        s.walkTime = loop(stride / DraughtHorseMesh.WALK_STRIDE * WALK_LENGTH, WALK_LENGTH)
        s.walkWeight = Mth.lerp(partial, look.walkO, look.walk)
        s.strainWeight = Mth.lerp(partial, look.strainO, look.strain)
        s.boltTime = loop(stride / DraughtHorseMesh.BOLT_STRIDE * BOLT_LENGTH, BOLT_LENGTH)
        s.boltWeight = Mth.lerp(partial, look.boltO, look.bolt)
        s.lieWeight = Mth.lerp(partial, look.lieO, look.lie)
        val shot = look.shot?.let { Clips.get(clipName(it)) }
        if (shot != null) {
            val t = (h.tickCount - look.shotAt + partial).coerceIn(0f, shot.length)
            s.shot = shot
            s.shotTime = t
            s.shotWeight = shot.weight(t)
        } else {
            s.shot = null
            s.shotWeight = 0f
        }
        s.nosebag = h.has(DraughtHorse.FLAG_NOSEBAG)
        s.reinsTied = h.has(DraughtHorse.FLAG_REINS_TIED)
        s.cut = h.has(DraughtHorse.FLAG_CUT)
        s.dead = h.has(DraughtHorse.FLAG_DEAD)
    }

    private fun loop(t: Float, length: Float): Float = ((t % length) + length) % length

    fun clipName(move: DraughtHorse.Move): String = "draught_horse/" + move.name.lowercase()

    private fun smooth(x: Float): Float {
        val k = x.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    // ---- Its sounds, on its clips' moments ----

    private fun moments(h: DraughtHorse, look: HorseLook) {
        val level = h.level()
        val player = Minecraft.getInstance().player
        val near = player != null && player.distanceToSqr(h) < HEAR * HEAR
        if (h.tickCount % 5 == 0) look.ground = CaravanSounds.ground(level, h.x, h.y, h.z)
        val walkNow = loop(look.stride / DraughtHorseMesh.WALK_STRIDE * WALK_LENGTH, WALK_LENGTH)
        val boltNow = loop(look.stride / DraughtHorseMesh.BOLT_STRIDE * BOLT_LENGTH, BOLT_LENGTH)
        val walkFrom = look.walkWas
        val boltFrom = look.boltWas
        look.walkWas = walkNow
        look.boltWas = boltNow
        // The one-shot over everything
        val shotMove = look.shot
        var shotOn = false
        if (shotMove != null) {
            val clip = Clips.get(clipName(shotMove))
            val t = (h.tickCount - look.shotAt).toFloat()
            if (clip != null && t <= clip.length) {
                shotOn = clip.weight(t.coerceAtMost(clip.length)) > 0.5f
                if (near) fire(level, h, look, shotMove.name.lowercase(), look.shotWas, t, clip.length, wrap = false)
            }
            look.shotWas = t
        }
        if (!near) return
        // The gaits: the hooves by the ground covered, the bells on the collar with the walk
        if (look.bolt > 0.5f) {
            fire(level, h, look, "bolt", boltFrom, boltNow, BOLT_LENGTH, wrap = true)
            if (passed(0f, boltFrom, boltNow, BOLT_LENGTH)) bells(level, h, 0.35f)
        } else if (look.walk > 0.35f) {
            fire(level, h, look, "walk", walkFrom, walkNow, WALK_LENGTH, wrap = true)
            if (passed(0f, walkFrom, walkNow, WALK_LENGTH) || passed(WALK_LENGTH / 2f, walkFrom, walkNow, WALK_LENGTH)) bells(level, h, 0.22f * look.walk)
        }
        // Standing: whichever of its stand's loops shows most
        if (look.walk < 0.5f && look.bolt < 0.5f && look.lie < 0.5f && !shotOn) {
            val age = h.tickCount + (h.id % 97) * 13f
            val (name, length) = when {
                look.bag > 0.5f -> "nosebag" to BAG_LENGTH
                look.graze > 0.5f -> "graze" to GRAZE_LENGTH
                else -> "idle" to IDLE_LENGTH
            }
            val now = loop(age, length)
            if (name == look.standClip) fire(level, h, look, name, look.standWas, now, length, wrap = true)
            look.standClip = name
            look.standWas = now
        } else {
            look.standClip = ""
        }
    }

    /** Every moment of clip [name] in (from, to], played. */
    private fun fire(level: Level, h: DraughtHorse, look: HorseLook, name: String, from: Float, to: Float, length: Float, wrap: Boolean) {
        val moments = DraughtHorseMesh.MOMENTS[name] ?: return
        if (from == to) return
        val weight = when (name) {
            "walk" -> look.walk
            "bolt" -> look.bolt
            else -> 1f
        }
        for (m in moments) {
            val hit = if (wrap) passed(m.tick, from, to, length) else m.tick > from && m.tick <= to
            if (!hit) continue
            val at = if (m.what == "hoof" || m.what == "stamp") Vec3(h.x, h.y + 0.1, h.z) else head(h)
            CaravanSounds.horse(level, at, name, m.what, look.ground, weight)
        }
    }

    /** Whether [tick] (or a lap of it) lies in (from, to] of a loop [length] long. */
    private fun passed(tick: Float, from: Float, to: Float, length: Float): Boolean {
        val end = if (to < from) to + length else to
        return tick > from && tick <= end || tick + length > from && tick + length <= end
    }

    private fun head(h: DraughtHorse): Vec3 {
        val yaw = Math.toRadians(h.yRot.toDouble())
        return Vec3(h.x - sin(yaw) * 1.1, h.y + 1.7, h.z + cos(yaw) * 1.1)
    }

    /** The bells on its collar. */
    private fun bells(level: Level, h: DraughtHorse, volume: Float) {
        val yaw = Math.toRadians(h.yRot.toDouble())
        val at = Vec3(h.x - sin(yaw) * 0.9, h.y + 1.6, h.z + cos(yaw) * 0.9)
        CaravanSounds.play(level, at, "bells", volume, 0.95f + level.random.nextFloat() * 0.1f)
    }

    fun removed(h: DraughtHorse) {
        h.look = null
    }

    private const val HEAR = 32.0
    private const val STRIDE_WRAP = 100_000f
    private const val WALK_LENGTH = 32f
    private const val BOLT_LENGTH = 16f
    private const val IDLE_LENGTH = 240f
    private const val GRAZE_LENGTH = 120f
    private const val BAG_LENGTH = 100f
}

/**
 * The heavy horse in its collar and hames (art/scripts/draught_horse.py), its clips played on its rig as the Dusk
 * Rider's steed's are: the stand's loops under its gaits, the one-shots over those. Its reins run from the rein rings
 * on the saddle to the bit wherever its head is; what it wears and what's cut show as it is.
 */
class HorseModel(root: ModelPart) : EntityModel<HorseRenderState>(root, RenderTypes::entityCutout) {
    private val rig = RigAnimator(root, DraughtHorseMesh.RIG)
    private val find = root.createPartLookup()
    private fun part(name: String): ModelPart = find.apply(name) ?: error("No part $name")
    private val body = part("body")
    private val neck = part("neck")
    private val head = part("head")
    private val collar = part("collar")
    private val nosebag = part("nosebag")
    private val reinsTied = part("reins_tied")
    private val traceRCut = part("trace_r_cut")
    private val traceLCut = part("trace_l_cut")
    private val reinRCut = part("rein_r_cut")
    private val reinLCut = part("rein_l_cut")
    private val reinR = part("rein_r")
    private val reinL = part("rein_l")
    private val q = Quaternionf()
    private val turn = Quaternionf()
    private val euler = Vector3f()
    private val v = Vector3f()
    private val bit = Vector3f()
    /** The state it was last posed for: the body and the wagon's look at it ask for the same pose in a frame. */
    private var posed: HorseRenderState? = null

    override fun setupAnim(state: HorseRenderState) {
        if (state === posed) return
        posed = state
        poseNow(state)
    }

    /** Poses it for [state], whatever it was posed for last. */
    fun poseNow(state: HorseRenderState) {
        posed = state
        resetPose()
        rig.begin()
        layer(IDLE, state.idleTime, 1f)
        layer(GRAZE, state.grazeTime, state.grazeWeight)
        layer(NOSEBAG, state.bagTime, state.bagWeight)
        layer(WALK, state.walkTime, state.walkWeight)
        layer(STRAIN, state.walkTime, state.strainWeight)
        layer(BOLT, state.boltTime, state.boltWeight)
        layer(LIE, 0f, state.lieWeight)
        state.shot?.let { rig.layer(it, state.shotTime, state.shotWeight) }
        rig.finish()
        nosebag.visible = state.nosebag
        reinsTied.visible = state.reinsTied && !state.dead
        traceRCut.visible = state.cut || state.dead
        traceLCut.visible = state.cut || state.dead
        reinRCut.visible = state.dead
        reinLCut.visible = state.dead
        reinR.visible = !state.dead
        reinL.visible = !state.dead
        if (!state.dead) {
            rein(reinR, DraughtHorseMesh.TERRET_RIGHT, DraughtHorseMesh.BIT_RIGHT)
            rein(reinL, DraughtHorseMesh.TERRET_LEFT, DraughtHorseMesh.BIT_LEFT)
        }
    }

    private fun layer(name: String, time: Float, weight: Float) {
        if (weight <= 0.001f) return
        Clips.get(name)?.let { rig.layer(it, time, weight) }
    }

    /** Turns [rein] (pivoted at its ring on the saddle, [ring]) to run to the bit ring [to] as the head is posed, stretched to reach. */
    private fun rein(rein: ModelPart, ring: DraughtHorseMesh.Point, to: DraughtHorseMesh.Point) {
        // The bit ring in the body's frame: up from the head through the neck
        bit.set(to.x, to.y, to.z)
        into(head, bit)
        into(neck, bit)
        v.set(bit).sub(ring.x, ring.y, ring.z)
        val length = v.length()
        if (length < 1e-3f) return
        v.div(length)
        q.rotationTo(0f, 1f, 0f, v.x, v.y, v.z)
        q.getEulerAnglesZYX(euler)
        rein.xRot = euler.x
        rein.yRot = euler.y
        rein.zRot = euler.z
        rein.yScale = length / DraughtHorseMesh.REIN_LENGTH
    }

    /** [p] from [part]'s frame to its parent's, as the part is posed now. */
    private fun into(part: ModelPart, p: Vector3f): Vector3f {
        turn.rotationZYX(part.zRot, part.yRot, part.xRot).transform(p)
        return p.add(part.x, part.y, part.z)
    }

    /** Where [point] is in the model's own space (px) as it's posed now. */
    fun point(point: DraughtHorseMesh.Point, out: Vector3f): Vector3f {
        out.set(point.x, point.y, point.z)
        when (point.part) {
            "collar" -> {
                into(collar, out)
                into(body, out)
            }
            "head" -> {
                into(head, out)
                into(neck, out)
                into(body, out)
            }
            "neck" -> {
                into(neck, out)
                into(body, out)
            }
            else -> into(body, out)
        }
        return out
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "draught_horse"), "main")
        private const val IDLE = "draught_horse/idle"
        private const val GRAZE = "draught_horse/graze"
        private const val NOSEBAG = "draught_horse/nosebag"
        private const val WALK = "draught_horse/walk"
        private const val STRAIN = "draught_horse/strain"
        private const val BOLT = "draught_horse/bolt"
        private const val LIE = "draught_horse/lie"
    }
}

/** A caravan's heavy horse: in its coat, as its clips pose it. */
class HorseRenderer(context: EntityRendererProvider.Context) : EntityRenderer<DraughtHorse, HorseRenderState>(context) {
    private val model = HorseModel(context.bakeLayer(HorseModel.LAYER))

    init {
        shadowRadius = 0.9f
    }

    override fun createRenderState(): HorseRenderState = HorseRenderState()

    override fun extractRenderState(horse: DraughtHorse, state: HorseRenderState, partialTicks: Float) {
        super.extractRenderState(horse, state, partialTicks)
        HorseClient.fill(horse, state, partialTicks)
    }

    override fun submit(state: HorseRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.YP, 180f - state.yaw)
        poseStack.rotateDegrees(Axis.XP, state.pitch)
        poseStack.translate(0f, 1.5f, 0f)
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModel(model, state, poseStack, texture(state.coat), state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor)
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    // Its box is its middle: its head and its tail reach past it, more so lying down
    override fun getBoundingBoxForCulling(horse: DraughtHorse, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(horse, partialTicks).inflate(1.4, 0.5, 1.4)

    companion object {
        private val TEXTURES: List<Identifier> = DraughtHorseMesh.COATS.map {
            Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/caravan/draught_horse_$it.png")
        }

        fun texture(coat: Int): Identifier = TEXTURES[Math.floorMod(coat, TEXTURES.size)]
    }
}

/** Where a horse's hames and tugs are (px of its model's space) as it's posed this frame: for the wagon's shafts and traces. */
internal class HorseMeasure(context: EntityRendererProvider.Context) {
    private val model = HorseModel(context.bakeLayer(HorseModel.LAYER))
    private val state = HorseRenderState()
    val hameRight = Vector3f()
    val hameLeft = Vector3f()
    val tugRight = Vector3f()
    val tugLeft = Vector3f()

    /** Poses [h] as it's drawn at [partial] and finds its points. */
    fun measure(h: DraughtHorse, partial: Float) {
        HorseClient.fill(h, state, partial)
        model.poseNow(state)
        model.point(DraughtHorseMesh.HAME_RIGHT, hameRight)
        model.point(DraughtHorseMesh.HAME_LEFT, hameLeft)
        model.point(DraughtHorseMesh.TUG_RIGHT, tugRight)
        model.point(DraughtHorseMesh.TUG_LEFT, tugLeft)
    }

    /** The yaw and pitch it's drawn at, as [measure] found them. */
    val yaw: Float get() = state.yaw
    val pitch: Float get() = state.pitch
}
