package io.github.vaspyyy.guildmark.client.caravan

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.economy.Good
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.EnumMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the wagons draw their load and their wrecks' lots with: the part of WagonMesh.load() each good heaps as (by
 * PIECE_FOR, a crate for anything it doesn't name) and the broken one a wreck's lot shows for it, how tall each
 * stands, and the goods themselves, to show in the open ones.
 */
internal object WagonDraw {
    /** Every part of WagonMesh.load(): the whole pieces, in PIECES' order, then the wreck's broken ones. */
    val PARTS: List<String> = WagonMesh.PIECES.map { it.part } +
        listOf("sack_torn", "flour_torn", "coal_torn", "crate_broken", "barrel_broken", "basket_spilled")

    private val INDEX: Map<String, Int> = PARTS.withIndex().associate { (i, name) -> name to i }
    private val CRATE = INDEX.getValue("crate")
    private val CRATE_BROKEN = INDEX.getValue("crate_broken")

    /** How tall each broken piece stands (px): what a lot heaped on it rests on. */
    private val BROKEN_HEIGHT: Map<String, Float> = mapOf(
        "sack_torn" to 3f, "flour_torn" to 3f, "coal_torn" to 3f, "crate_broken" to 4.6f, "barrel_broken" to 5.6f, "basket_spilled" to 6.4f,
    )

    /** Which of [PARTS] (and of WagonMesh.PIECES) [g] heaps as on the bed. */
    fun index(g: Good): Int = WagonMesh.PIECE_FOR[g.id]?.let { INDEX[it] } ?: CRATE

    fun piece(g: Good): WagonMesh.Piece = WagonMesh.PIECES[index(g)]

    /** Which of [PARTS] a wreck's lot of [g] shows: its piece broken open. */
    fun broken(g: Good): Int = WagonMesh.WRECK_PIECE_FOR[piece(g).part]?.let { INDEX[it] } ?: CRATE_BROKEN

    /** How tall part [index] of [PARTS] stands (px). */
    fun height(index: Int): Float =
        if (index < WagonMesh.PIECES.size) WagonMesh.PIECES[index].height else BROKEN_HEIGHT[PARTS[index]] ?: 4f

    private val stacks = EnumMap<Good, ItemStack>(Good::class.java)

    /** [g] as an item, to show in an open crate, basket or fish box; empty if it has none. */
    fun stack(g: Good): ItemStack = stacks.getOrPut(g) { g.item?.let { ItemStack(it) } ?: ItemStack.EMPTY }
}

/** What a wagon's renderer needs to draw it at one moment (WagonClient works it out tick by tick). */
class WagonRenderState : EntityRenderState() {
    /** The back axle's ground (the state's own x, y and z are moved to the middle of the bed, for its shadow). */
    var backX = 0.0
    var backY = 0.0
    var backZ = 0.0

    /** Degrees: which way it heads, its nose up, its lean (its right side up). */
    var yaw = 0f
    var pitch = 0f
    var roll = 0f

    /** Radians each wheel has turned rolling forward: back right, back left, front right, front left. */
    val spin = FloatArray(4)

    /** Radians: the front steered (+ to its right) and rocking on its turntable; the shafts turned about their hinges (+ lowers the tips). */
    var steer = 0f
    var rock = 0f
    var shafts = 0f

    /** Radians: the tilt's sway; the lantern's turn at its hook (its swing, with the wagon's lean undone); the lantern lit. */
    var sway = 0f
    var lanternX = 0f
    var lanternZ = 0f
    var lit = false

    /** The pennant's ripple: its phase (radians) and how big it is. */
    var flap = 0f
    var flapSize = 0f

    /** A trading house's wagon's pennant colour (RGB), or -1 for the caravans' painted one. */
    var tint = -1

    /** Radians: the brake lever's lean and the tailboard's (90 degrees shut). */
    var brake = 0f
    var tail = 0f

    /** Radians it has gone over (WagonMesh.WRECK_ROLL at the end), and whether onto its left side. */
    var tip = 0f
    var tipLeft = false

    /** The band's arrows standing in its boards (bits 0 to 5), and whether in its right side. */
    var arrows = 0
    var arrowsRight = false

    /** The traces drawn: each strap stretched from one point to the other (px, the wagon's space). */
    var straps = 0
    val strapFrom = Array(MAX_STRAPS) { Vector3f() }
    val strapTo = Array(MAX_STRAPS) { Vector3f() }

    /**
     * The pieces of the load drawn: each one's part (WagonDraw.PARTS), where it stands (px) and its turn (degrees, a
     * yRot of minus that), whether it's on the stack on the ground (drawn as the wagon heads, not as it leans), and the
     * goods showing in it if it's open.
     */
    var pieces = 0
    val kind = IntArray(MAX_PIECES)
    val px = FloatArray(MAX_PIECES)
    val py = FloatArray(MAX_PIECES)
    val pz = FloatArray(MAX_PIECES)
    val turn = FloatArray(MAX_PIECES)
    val ground = BooleanArray(MAX_PIECES)
    val goods = arrayOfNulls<ItemStackRenderState>(MAX_PIECES)

    companion object {
        const val MAX_PIECES = Wagon.SLOTS * 2
        const val MAX_STRAPS = 4
    }
}

/**
 * The caravan's wagon (art/scripts/wagon.py, see WagonMesh): its wheels turned by the ground each covered, the front
 * steered toward the horse and rocking on its turntable, the shafts kept through the horse's tugs, the tilt swaying,
 * the lantern hanging straight from its hook and swinging, the pennant rippling, the brake lever and the tailboard
 * where they are, its tail chains holding the tailboard let down.
 */
class WagonModel(root: ModelPart) : EntityModel<WagonRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()
    private fun part(name: String): ModelPart = find.apply(name) ?: error("No part $name")
    private val wheels = listOf("wheel_rr", "wheel_rl", "wheel_fr", "wheel_fl").map { part(it) }
    private val front = part("front")
    private val shafts = part("shafts")
    private val tilt = part("tilt")
    private val lantern = part("lantern")
    private val pennant = part("pennant")
    private val pennant1 = part("pennant_1")
    private val pennant2 = part("pennant_2")
    private val brake = part("brake_lever")
    private val tailboard = part("tailboard")
    private val chains = listOf(part("tail_chain_r"), part("tail_chain_l"))
    private val q = Quaternionf()
    private val euler = Vector3f()

    override fun setupAnim(state: WagonRenderState) {
        super.setupAnim(state)
        // Each wheel as far round as it rolled, the wagon's nose-up undone (it turns them by nothing it rolled)
        val pitch = Math.toRadians(state.pitch.toDouble()).toFloat()
        for (k in 0 until 4) wheels[k].xRot = state.spin[k] + pitch
        // The front steered about its kingpin and rocking about its own length
        q.rotationY(state.steer).rotateZ(state.rock).getEulerAnglesZYX(euler)
        front.xRot = euler.x
        front.yRot = euler.y
        front.zRot = euler.z
        shafts.xRot = state.shafts
        tilt.zRot = state.sway
        lantern.xRot = state.lanternX
        lantern.zRot = state.lanternZ
        // The pennant streams back, rippling from its root to its tip
        pennant.yRot = pennant.initialPose.yRot + ripple(0, state)
        pennant1.yRot = ripple(1, state)
        pennant2.yRot = ripple(2, state)
        // A House's pennant is drawn on its own, white and tinted (WagonRenderer)
        pennant.visible = state.tint == -1
        brake.xRot = state.brake
        tailboard.xRot = state.tail
        // The chains hold the tailboard let down; shut, they hang inside out of sight
        val chained = state.tail < CHAINS_UNDER
        for (c in chains) c.visible = chained
    }

    companion object {
        private fun layer(name: String) = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "caravan_wagon"), name)

        /** The wagon itself, its load's pieces, the wreck, the band's loot pile and the straps (one texture). */
        val LAYER = layer("main")
        val LOAD = layer("load")
        val WRECK = layer("wreck")
        val LOOT = layer("loot")
        val STRAPS = layer("straps")

        /** Radians the pennant's staff end, its first piece and its second ripple at most (the art's 14 and 22 degrees). */
        private val PENNANT_SWAY = Math.toRadians(8.0).toFloat()
        private val PENNANT_1 = Math.toRadians(14.0).toFloat()
        private val PENNANT_2 = Math.toRadians(22.0).toFloat()

        /** Radians piece [k] of the pennant (0 at the staff) turns about its root beyond its first pose as it ripples in [state]. */
        fun ripple(k: Int, state: WagonRenderState): Float = state.flapSize * when (k) {
            0 -> PENNANT_SWAY * sin(state.flap * 0.5f + 0.7f)
            1 -> PENNANT_1 * sin(state.flap)
            else -> PENNANT_2 * sin(state.flap - 1.1f)
        }

        /** The tail chains show with the tailboard let down further than this. */
        private val CHAINS_UNDER = Math.toRadians(30.0).toFloat()
    }
}

/**
 * A caravan's wagon on the road: as WagonModel poses it, standing on its four wheels about the back axle, with the
 * traces from the swingletree to the horse's hames as the horse is drawn this frame (or along the road once cut),
 * its load heaped on the bed from what it really carries (the goods showing in the open crates), the stack by the
 * back wheel as it's unloaded, the band's arrows in its boards, the lantern's glass glowing at night, and going over
 * onto its side when it's shoved.
 */
class WagonRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Wagon, WagonRenderState>(context) {
    private val model = WagonModel(context.bakeLayer(WagonModel.LAYER))
    // Parts drawn on their own are their own copies: a part drawn alone is reset to its first pose as it's drawn
    private val lantern: ModelPart = context.bakeLayer(WagonModel.LAYER).getChild("lantern")
    private val parts: List<ModelPart> = context.bakeLayer(WagonModel.LOAD).let { load -> WagonDraw.PARTS.map { load.getChild(it) } }
    private val arrowParts: List<ModelPart> = context.bakeLayer(WagonModel.WRECK).createPartLookup().let { find ->
        (0 until ARROW_COUNT).map { find.apply("w_arrow$it") ?: error("No part w_arrow$it") }
    }
    private val trace: ModelPart = context.bakeLayer(WagonModel.STRAPS).getChild("trace")
    // A House's pennant: each of its three pieces from a copy of its own, the next piece hidden, so each is drawn alone and turned as it ripples
    private val flags: List<ModelPart> = listOf("pennant", "pennant_1", "pennant_2").mapIndexed { k, name ->
        context.bakeLayer(WagonModel.LAYER).createPartLookup().let { find ->
            val part = find.apply(name) ?: error("No part $name")
            if (k < 2) (find.apply("pennant_${k + 1}") ?: error("No part pennant_${k + 1}")).visible = false
            part
        }
    }
    private val items: ItemModelResolver = context.itemModelResolver
    private val measure = HorseMeasure(context)
    private val horsePose = HorsePose()
    private val pose = WagonPose()
    private val wv = Vector3d()
    private val lv = Vector3f()
    private val q = Quaternionf()
    private val back = Quaternionf()

    init {
        shadowRadius = 1.5f
    }

    override fun createRenderState(): WagonRenderState = WagonRenderState()

    override fun extractRenderState(w: Wagon, s: WagonRenderState, partialTicks: Float) {
        super.extractRenderState(w, s, partialTicks)
        val look = WagonClient.look(w)
        val partial = partialTicks
        WagonClient.pose(w, partial, pose)
        s.backX = pose.x
        s.backY = pose.y
        s.backZ = pose.z
        s.yaw = pose.yaw
        s.pitch = pose.pitch
        s.roll = pose.roll
        for (k in 0 until 4) s.spin[k] = Mth.lerp(partial, look.spinO[k], look.spin[k])
        s.steer = rad(Mth.lerp(partial, look.steerO, look.steer))
        s.rock = rad(Mth.lerp(partial, look.rockO, look.rock))
        s.sway = rad(Mth.lerp(partial, look.swayO, look.sway))
        s.flap = Mth.lerp(partial, look.flapO, look.flap)
        s.flapSize = look.flapSize
        s.tint = w.tint
        s.brake = rad(Mth.lerp(partial, look.brakeO, look.brake))
        s.tail = rad(Mth.lerp(partial, look.tailO, look.tail))
        // Going over: faster as it falls, from when this client saw it shoved
        s.tip = if (look.tipping >= 0) WagonClient.tipAngle(look.tipping + partial) else 0f
        s.tipLeft = look.tipMirror
        // The lantern hangs straight down from its hook whichever way the wagon stands (and part way as it goes over), swinging
        val tipZ = (if (s.tipLeft) -s.tip else s.tip).coerceIn(-LANTERN_TIP, LANTERN_TIP)
        s.lanternX = rad(s.pitch) + rad(Mth.lerp(partial, look.swingXO, look.swingX))
        s.lanternZ = -rad(s.roll) - tipZ + rad(Mth.lerp(partial, look.swingZO, look.swingZ))
        s.lit = w.has(Wagon.FLAG_LANTERN)
        s.arrows = w.arrows
        s.arrowsRight = look.arrowsMirror
        harness(w, s, look, partial)
        load(w, s, look, partial)
        // Its shadow under the middle of the bed, not the back axle
        pose.world(0f, 0f, SHADOW_Z, wv)
        s.x = wv.x
        s.y = wv.y
        s.z = wv.z
    }

    // ---- The shafts and the traces ----

    /**
     * The shafts kept through the horse's tugs and the traces run from the swingletree's hooks to its hames, wherever
     * the horse is drawn this frame; cut from it, the shafts go down onto the road and the traces lie along it.
     */
    private fun harness(w: Wagon, s: WagonRenderState, look: WagonLook, partial: Float) {
        // The front as it's steered and rocks
        q.rotationY(s.steer).rotateZ(s.rock)
        back.set(q).conjugate()
        val horse = WagonClient.horseOf(w)
        if (horse != null && WagonClient.harnessed(w, horse)) {
            measure.measure(horse, partial)
            val p = partial.toDouble()
            horsePose.set(Mth.lerp(p, horse.xOld, horse.x), Mth.lerp(p, horse.yOld, horse.y), Mth.lerp(p, horse.zOld, horse.z), measure.yaw, measure.pitch)
            val e = (elevation(measure.tugRight) + elevation(measure.tugLeft)) * 0.5f
            look.follow = (SHAFTS_REST - e).coerceIn(SHAFTS_UP, SHAFTS_DOWN)
            hook(-1f, s.strapFrom[0])
            hook(1f, s.strapFrom[1])
            horsePose.world(measure.hameRight, wv)
            pose.local(wv.x, wv.y, wv.z, s.strapTo[0])
            horsePose.world(measure.hameLeft, wv)
            pose.local(wv.x, wv.y, wv.z, s.strapTo[1])
            s.straps = 2
        } else {
            // Ahead along the road from the hooks, down onto it and lying a little in from the shafts
            val fx = -sin(s.steer)
            val fz = -cos(s.steer)
            val rx = -cos(s.steer)
            val rz = sin(s.steer)
            for ((i, side) in SIDES.withIndex()) {
                val from = hook(side, s.strapFrom[i * 2])
                val touch = s.strapTo[i * 2].set(from.x + fx * LAID_DROP, ROAD_Y, from.z + fz * LAID_DROP)
                s.strapFrom[i * 2 + 1].set(touch)
                s.strapTo[i * 2 + 1].set(touch.x + fx * LAID_LENGTH + rx * side * LAID_IN, ROAD_Y, touch.z + fz * LAID_LENGTH + rz * side * LAID_IN)
            }
            s.straps = 4
        }
        val down = smooth(Mth.lerp(partial, look.downO, look.down))
        s.shafts = Mth.lerp(down, look.follow, SHAFTS_DOWN)
    }

    /** Radians up from the shafts' hinge line the horse's tug at [tug] (px, its model's space) is, in the front's own frame. */
    private fun elevation(tug: Vector3f): Float {
        horsePose.world(tug, wv)
        pose.local(wv.x, wv.y, wv.z, lv)
        lv.sub(0f, WagonMesh.KINGPIN_Y, -WagonMesh.WHEELBASE)
        back.transform(lv)
        lv.sub(0f, SHAFTS_Y, SHAFTS_Z)
        return atan2(-lv.y, -lv.z)
    }

    /** The swingletree's hook on [side] (-1 its right, 1 its left) in the wagon's space, the front turned as it is. */
    private fun hook(side: Float, out: Vector3f): Vector3f {
        out.set(side * WagonMesh.SWINGLE_HALF, WagonMesh.SWINGLE[1] - WagonMesh.KINGPIN_Y, WagonMesh.SWINGLE[2] + WagonMesh.WHEELBASE)
        q.transform(out)
        return out.add(0f, WagonMesh.KINGPIN_Y, -WagonMesh.WHEELBASE)
    }

    // ---- The load ----

    /** The load on the bed, settling on piece by piece, and the stack by the back wheel on the ground as it lies. */
    private fun load(w: Wagon, s: WagonRenderState, look: WagonLook, partial: Float) {
        var n = 0
        // The places under a piece on top: their goods don't show
        var covered = 0
        for (k in TOP_FROM until Wagon.SLOTS) {
            if (look.load[k] == null || look.loadSince[k] < 0) continue
            for (u in WagonMesh.SLOTS[k].under) covered = covered or (1 shl u)
        }
        for (k in 0 until Wagon.SLOTS) {
            val g = look.load[k] ?: continue
            val since = look.loadSince[k]
            if (since < 0) continue
            val t = ((since + partial) / WagonLook.SETTLE).coerceIn(0f, 1f)
            val place = WagonMesh.SLOTS[k]
            val rest = Mth.lerp(partial, look.restO[k], look.rest[k])
            val y = WagonMesh.FLOOR_TOP - rest - SETTLE_DROP * (1f - t) * (1f - t)
            n = piece(w, s, n, g, place.x, y, place.z, place.yaw, false, covered and (1 shl k) == 0)
        }
        var under = 0
        for (k in STACK_PLACES until Wagon.SLOTS) if (look.stack[k] != null) under = under or (1 shl (k - STACK_PLACES))
        for (k in 0 until Wagon.SLOTS) {
            val g = look.stack[k] ?: continue
            val t = ((look.stackSince[k] + partial) / WagonLook.STACK_SETTLE).coerceIn(0f, 1f)
            val at = Wagon.STACK_XZ[k % STACK_PLACES]
            val groundY = look.stackGround[k % STACK_PLACES].let { if (it.isNaN()) s.backY else it }
            val on = if (k >= STACK_PLACES) Mth.lerp(partial, look.stackRestO[k], look.stackRest[k]) else 0f
            val y = -((groundY - s.backY).toFloat() * 16f + on) - STACK_DROP * (1f - t) * (1f - t)
            n = piece(w, s, n, g, at[0], y, at[1], STACK_TURN[k], true, k >= STACK_PLACES || under and (1 shl k) == 0)
        }
        s.pieces = n
    }

    /** Adds a piece of [g] standing at [x], [y], [z] turned [turn], the goods showing if it's [open]; the count so far. */
    private fun piece(w: Entity, s: WagonRenderState, n: Int, g: Good, x: Float, y: Float, z: Float, turn: Float, ground: Boolean, open: Boolean): Int {
        if (n >= WagonRenderState.MAX_PIECES) return n
        val kind = WagonDraw.index(g)
        s.kind[n] = kind
        s.px[n] = x
        s.py[n] = y
        s.pz[n] = z
        s.turn[n] = turn
        s.ground[n] = ground
        s.goods[n] = null
        if (open && WagonMesh.PIECES[kind].shows.isNotEmpty()) {
            val stack = WagonDraw.stack(g)
            if (!stack.isEmpty) {
                val goods = ItemStackRenderState()
                items.updateForNonLiving(goods, stack, ItemDisplayContext.FIXED, w)
                if (!goods.isEmpty) s.goods[n] = goods
            }
        }
        return n + 1
    }

    // ---- Drawing it ----

    override fun submit(s: WagonRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.translate(s.backX - s.x, s.backY - s.y, s.backZ - s.z)
        // The stack by the wheel: as the wagon heads, but on the ground, not leaning with it
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.YP, 180f - s.yaw)
        poseStack.scale(-1f, -1f, 1f)
        for (i in 0 until s.pieces) if (s.ground[i]) piece(s, i, poseStack, collector)
        poseStack.popPose()
        // The wagon about its back axle as it stands; going over, about the wheels it goes over on
        poseStack.translate(0.0, Wagon.REAR_RADIUS, 0.0)
        poseStack.rotateDegrees(Axis.YP, 180f - s.yaw)
        poseStack.rotateDegrees(Axis.XP, s.pitch)
        poseStack.rotateDegrees(Axis.ZP, s.roll)
        poseStack.translate(0.0, -Wagon.REAR_RADIUS, 0.0)
        poseStack.scale(-1f, -1f, 1f)
        if (s.tip != 0f) {
            val x = (if (s.tipLeft) -WagonMesh.TIP_X else WagonMesh.TIP_X) / 16f
            poseStack.translate(x, WagonMesh.TIP_Y / 16f, 0f)
            poseStack.rotate(Axis.ZP, if (s.tipLeft) -s.tip else s.tip)
            poseStack.translate(-x, -WagonMesh.TIP_Y / 16f, 0f)
        }
        collector.submitModel(model, s, poseStack, TEXTURE, s.lightCoords, OverlayTexture.NO_OVERLAY, s.outlineColor)
        if (s.tint != -1) pennant(s, poseStack, collector)
        // The lantern's glass lit, turned as the lantern hangs
        if (s.lit) {
            val hook = WagonMesh.LANTERN_HOOK
            poseStack.pushPose()
            poseStack.translate(hook[0] / 16f, hook[1] / 16f, hook[2] / 16f)
            poseStack.rotate(q.rotationZYX(s.lanternZ, 0f, s.lanternX))
            poseStack.translate(-hook[0] / 16f, -hook[1] / 16f, -hook[2] / 16f)
            collector.order(1).submitModelPart(lantern, poseStack, GLOW, s.lightCoords, OverlayTexture.NO_OVERLAY, null, -1, s.outlineColor)
            poseStack.popPose()
        }
        // The band's arrows, in the side they were shot at; those in the tilt sway with it
        for (k in 0 until ARROW_COUNT) {
            if (s.arrows and (1 shl k) == 0) continue
            poseStack.pushPose()
            if (k in IN_TILT) {
                poseStack.translate(0f, TILT_Y / 16f, 0f)
                poseStack.rotate(Axis.ZP, s.sway)
                poseStack.translate(0f, -TILT_Y / 16f, 0f)
            }
            if (s.arrowsRight) poseStack.scale(-1f, 1f, 1f)
            collector.submitModelPart(arrowParts[k], poseStack, CUTOUT, s.lightCoords, OverlayTexture.NO_OVERLAY, null, -1, s.outlineColor)
            poseStack.popPose()
        }
        for (i in 0 until s.pieces) if (!s.ground[i]) piece(s, i, poseStack, collector)
        for (i in 0 until s.straps) strap(s, s.strapFrom[i], s.strapTo[i], poseStack, collector)
        poseStack.popPose()
        super.submit(s, poseStack, collector, camera)
    }

    /**
     * A trading house's pennant (its tint): the three pieces of white wool tinted, each turned about its root as WagonModel ripples the
     * painted one, unless something else flies its own on this wagon (a trader's led wagon hides the caravans' pennant).
     */
    private fun pennant(s: WagonRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val hidden = s.getModelPartVisibility()
        if (hidden != null && hidden.containsKey("pennant") && !hidden.getBoolean("pennant")) return
        val colour = ARGB.color(255, s.tint)
        poseStack.pushPose()
        for ((k, part) in flags.withIndex()) {
            val turn = WagonModel.ripple(k, s)
            // Turned about its own root: the part puts itself there in its first pose as it is drawn
            poseStack.pushPose()
            poseStack.translate(part.x / 16f, part.y / 16f, part.z / 16f)
            poseStack.rotate(Axis.YP.rotation(turn))
            poseStack.translate(-part.x / 16f, -part.y / 16f, -part.z / 16f)
            collector.submitModelPart(part, poseStack, FLAG, s.lightCoords, OverlayTexture.NO_OVERLAY, null, colour, s.outlineColor)
            poseStack.popPose()
            // And the next piece hangs from this one as it is turned
            poseStack.translate(part.x / 16f, part.y / 16f, part.z / 16f)
            poseStack.rotate(q.rotationZYX(part.zRot, part.yRot + turn, part.xRot))
        }
        poseStack.popPose()
    }

    /** Piece [i] of the load, and the goods showing on top of it if it's an open one. */
    private fun piece(s: WagonRenderState, i: Int, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val kind = s.kind[i]
        poseStack.pushPose()
        poseStack.translate(s.px[i] / 16f, s.py[i] / 16f, s.pz[i] / 16f)
        poseStack.rotateDegrees(Axis.YP, -s.turn[i])
        collector.submitModelPart(parts[kind], poseStack, CUTOUT, s.lightCoords, OverlayTexture.NO_OVERLAY, null, -1, s.outlineColor)
        val goods = s.goods[i]
        if (goods != null) {
            val piece = WagonMesh.PIECES[kind]
            for ((j, show) in piece.shows.withIndex()) {
                poseStack.pushPose()
                poseStack.translate(show[0] / 16f, (-piece.height - 0.25f - 0.15f * j) / 16f, show[1] / 16f)
                poseStack.rotateDegrees(Axis.YP, -show[2])
                poseStack.rotateDegrees(Axis.XP, show[3])
                // Flat, face up (y is down in here)
                poseStack.rotateDegrees(Axis.XP, -90f)
                poseStack.scale(piece.size, piece.size, piece.size)
                goods.submit(poseStack, collector, s.lightCoords, OverlayTexture.NO_OVERLAY, s.outlineColor)
                poseStack.popPose()
            }
        }
        poseStack.popPose()
    }

    /** A trace stretched from [from] to [to] (px, the wagon's space). */
    private fun strap(s: WagonRenderState, from: Vector3f, to: Vector3f, poseStack: PoseStack, collector: SubmitNodeCollector) {
        lv.set(to).sub(from)
        val length = lv.length()
        if (length < 0.05f) return
        lv.div(length)
        poseStack.pushPose()
        poseStack.translate(from.x / 16f, from.y / 16f, from.z / 16f)
        poseStack.rotate(q.rotationTo(0f, 1f, 0f, lv.x, lv.y, lv.z))
        poseStack.scale(1f, length / STRAP_LENGTH, 1f)
        collector.submitModelPart(trace, poseStack, CUTOUT, s.lightCoords, OverlayTexture.NO_OVERLAY, null, -1, s.outlineColor)
        poseStack.popPose()
    }

    // Its box is round the back axle: the shafts reach six blocks ahead of it, the stack and the tip a few to the side
    override fun getBoundingBoxForCulling(w: Wagon, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(w, partialTicks).inflate(REACH, 1.5, REACH)

    companion object {
        val TEXTURE: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/caravan/wagon.png")
        private val GLOW_TEXTURE: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/caravan/wagon_glow.png")
        val CUTOUT: RenderType = RenderTypes.entityCutout(TEXTURE)
        private val GLOW: RenderType = RenderTypes.eyes(GLOW_TEXTURE)

        /** A House's pennant: vanilla's white wool, tinted with the House's colour. */
        private val FLAG: RenderType = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/block/white_wool.png"))

        /** The band's six arrows ("w_arrow0" to 5 of the wreck, posed in the standing wagon's space); 3 and 4 are in the tilt. */
        private const val ARROW_COUNT = 6
        private val IN_TILT = setOf(3, 4)
        private const val TILT_Y = -26f

        /** Where under it its shadow falls (px ahead of the back axle, -z) and how far its box reaches past the axle's. */
        private const val SHADOW_Z = -16f
        private const val REACH = 6.5

        /**
         * The shafts' turn (radians, + lowers the tips): as they rest with the horse at its standard place (its tugs up
         * this far from the hinge line, 10.5 px over it and 51 ahead); the most they lift; and down on the road.
         */
        private val SHAFTS_REST = atan2(10.5, 51.0).toFloat()
        private const val SHAFTS_UP = -0.35f
        private const val SHAFTS_DOWN = 0.317f
        /** The shafts' hinge line in the front's own frame (the "shafts" part's place on "front"). */
        private const val SHAFTS_Y = 4f
        private const val SHAFTS_Z = -4f

        /** Cut traces: down onto the road this far ahead of the hooks, then lying on it this much further, drawing in this far. */
        private const val LAID_DROP = 13f
        private const val LAID_LENGTH = 46f
        private const val LAID_IN = 3.5f
        private const val ROAD_Y = -0.35f
        private val SIDES = floatArrayOf(-1f, 1f)

        /** A strap's own length (WagonMesh.straps(): 16 px along +y). */
        private const val STRAP_LENGTH = 16f

        /** Radians of the overturn the lantern hangs straight through (past that it goes over with the wagon). */
        private const val LANTERN_TIP = 0.6f

        /** The places on the bed from which they're on top of others; the stack's places on the ground. */
        private const val TOP_FROM = 12
        private const val STACK_PLACES = 8

        /** Px a piece drops from as it's set on the bed, and onto the stack. */
        private const val SETTLE_DROP = 5f
        private const val STACK_DROP = 4f

        /** Degrees each piece of the stack is turned, set down by hand. */
        private val STACK_TURN = FloatArray(Wagon.SLOTS) { ((it * 37) % 11 - 5) * 1.6f }

        private fun rad(deg: Float): Float = Math.toRadians(deg.toDouble()).toFloat()

        private fun smooth(x: Float): Float {
            val k = x.coerceIn(0f, 1f)
            return k * k * (3f - 2f * k)
        }
    }
}
