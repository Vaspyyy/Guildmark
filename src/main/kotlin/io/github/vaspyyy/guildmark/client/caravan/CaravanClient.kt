package io.github.vaspyyy.guildmark.client.caravan

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.caravan.Bodies
import io.github.vaspyyy.guildmark.caravan.DraughtHorse
import io.github.vaspyyy.guildmark.caravan.Slide
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.caravan.WreckLot
import io.github.vaspyyy.guildmark.client.townsfolk.LivingMoments
import io.github.vaspyyy.guildmark.client.townsfolk.LivingProps
import io.github.vaspyyy.guildmark.client.townsfolk.LivingSchedule
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkModel
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderState
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderer
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.entity.NoopRenderer
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.util.context.ContextKey
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The client half of the caravans' bodies: the renderers of the wagon, its horse, the wreck and the loot pile and
 * their model layers; the crew drawn on their seats as the wagon is drawn this frame (sliding along the bench, down
 * off the tailboard and up onto it), the reins in the driver's hands running to the horse's rings however the front
 * is steered, the nosebag in the driver's hand; the crew's clips' sounds (LivingMoments hands them to CaravanSounds).
 */
object CaravanClient {
    /** Which wagon each of the crew rides and which place in its crew they are, by their entity id. */
    private class Ride(val wagon: Wagon, val index: Int)

    private val rides = Int2ObjectOpenHashMap<Ride>()

    /** Registers the renderers, the model layers, the crew's seating and the hooks; called once from the client's setup. */
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(Bodies.WAGON.get(), ::WagonRenderer)
            event.registerEntityRenderer(Bodies.DRAUGHT_HORSE.get(), ::HorseRenderer)
            event.registerEntityRenderer(Bodies.WRECK.get(), ::WreckRenderer)
            event.registerEntityRenderer(Bodies.WRECK_LOT.get()) { NoopRenderer<WreckLot>(it) }
            event.registerEntityRenderer(Bodies.LOOT_PILE.get(), ::LootPileRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(WagonModel.LAYER, WagonMesh::wagon)
            event.registerLayerDefinition(WagonModel.LOAD, WagonMesh::load)
            event.registerLayerDefinition(WagonModel.WRECK, WagonMesh::wreck)
            event.registerLayerDefinition(WagonModel.LOOT, WagonMesh::loot)
            event.registerLayerDefinition(WagonModel.STRAPS, WagonMesh::straps)
            event.registerLayerDefinition(HorseModel.LAYER, DraughtHorseMesh::horse)
        }
        modBus.addListener(RegisterRenderStateModifiersEvent::class.java) { event ->
            event.registerEntityModifier<Townsperson, TownsfolkRenderState>(TownsfolkRenderer::class.java) { p, state -> seat(p, state) }
        }
        Wagon.clientTick = WagonClient::tick
        Wagon.clientRemoved = WagonClient::removed
        DraughtHorse.clientTick = HorseClient::tick
        DraughtHorse.clientRemoved = HorseClient::removed
        LivingMoments.caravan = CaravanSounds::crew
        LivingProps.after = ::afterPose
        TownsfolkRenderer.lean = ::lean
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> rides.clear() }
    }

    /** [id] is crew member [index] of [w] (WagonClient, as the wagon's crew changes). */
    internal fun ride(id: Int, w: Wagon, index: Int) {
        rides.put(id, Ride(w, index))
    }

    /** [id] is no longer of [w]'s crew. */
    internal fun unride(id: Int, w: Wagon) {
        val ride = rides.get(id) ?: return
        if (ride.wagon === w) rides.remove(id)
    }

    // ---- The crew on their seats ----

    private val pose = WagonPose()
    private val at = Vector3d()

    /**
     * Draws [p] where the wagon they ride has their seat this frame (the wagon and its crew each move a tick behind,
     * and not in step), sliding along the bench, raised as the clips that are made from the bench play on the lower
     * tailboard; the reins only in the driver's hands, the nosebag in the driver's left while they carry it.
     */
    private fun seat(p: Townsperson, s: TownsfolkRenderState) {
        if (rides.isEmpty()) return
        val ride = rides.get(p.id) ?: return
        val w = ride.wagon
        if (w.isRemoved) return
        val i = ride.index
        val living = s.living
        if (i != DRIVER) living.shown.removeAll(REINS)
        if (i == DRIVER && w.has(Wagon.FLAG_NOSEBAG)) {
            living.shown.add(NOSEBAG)
            // Their left hand has it: no lantern in it meanwhile
            if ("lantern" in s.held) {
                s.held = s.held - "lantern"
                s.glows = "hot_iron" in s.held || living.wick && living.shown.any { it in LivingSchedule.GLOW }
            }
        }
        val seat = w.seat(i)
        val to = Wagon.seatOf(seat) ?: return
        val look = WagonClient.look(w)
        val partial = s.partialTick
        WagonClient.pose(w, partial, pose)
        var x = to.x
        var y = to.y
        var z = to.z
        var yaw = to.yaw
        // Along the bench from its other end (or along the tailboard to stand and hand down)
        val fromSeat = look.seatFrom[i]
        val from = Wagon.seatOf(fromSeat)
        val since = look.age - look.seatAt[i] + partial
        if (from != null && fromSeat != seat && since < Slide.SLIDE) {
            val k = smooth(since / Slide.SLIDE)
            x = Mth.lerp(k, from.x, to.x)
            y = Mth.lerp(k, from.y, to.y)
            z = Mth.lerp(k, from.z, to.z)
            yaw = Mth.rotLerp(k, from.yaw, to.yaw)
        }
        // Down off the tailboard and up onto it: the clips are made for the bench, higher up
        if (seat == Wagon.TAIL) {
            val t = p.tickCount - p.shownStart + partial
            y -= when (p.shownActivity) {
                Activity.JUMP_DOWN -> TAIL_RAISE * smooth((t - CrewMoves.JUMP_DOWN_AIR_FROM) / (CrewMoves.JUMP_DOWN_LAND - CrewMoves.JUMP_DOWN_AIR_FROM))
                Activity.CLIMB_UP -> TAIL_RAISE * (1f - smooth((t - CrewMoves.CLIMB_UP_HAUL_FROM) / (CrewMoves.CLIMB_UP_HAUL_TO - CrewMoves.CLIMB_UP_HAUL_FROM)))
                else -> 0f
            }
        }
        pose.world(x, y, z, at)
        s.x = at.x
        s.y = at.y
        s.z = at.z
        // Facing as the seat does on the wagon as it's drawn, the head still looking where it was
        val head = s.bodyRot + s.yRot
        val body = pose.yaw + yaw
        s.bodyRot = body
        s.yRot = Mth.clamp(Mth.wrapDegrees(head - body), -HEAD_TURN, HEAD_TURN)
        s.walkAnimationSpeed = 0f
        // Leaning with the wagon on a slope, about their seat: easing off as they jump down, and in as they climb up
        val t = p.tickCount - p.shownStart + partial
        val weight = when (p.shownActivity) {
            Activity.JUMP_DOWN -> 1f - smooth((t - CrewMoves.JUMP_DOWN_AIR_FROM) / (CrewMoves.JUMP_DOWN_LAND - CrewMoves.JUMP_DOWN_AIR_FROM))
            Activity.CLIMB_UP -> smooth((t - CrewMoves.CLIMB_UP_HAUL_FROM) / (CrewMoves.CLIMB_UP_HAUL_TO - CrewMoves.CLIMB_UP_HAUL_FROM))
            else -> 1f
        }
        if (weight > 0f && (abs(pose.pitch) > LEAN_LEAST || abs(pose.roll) > LEAN_LEAST)) {
            val turn = Math.toRadians(180.0 - pose.yaw).toFloat()
            s.setRenderData(
                LEAN,
                Quaternionf().rotationY(turn).rotateX(rad(pose.pitch * weight)).rotateZ(rad(pose.roll * weight)).rotateY(-turn),
            )
        }
        if (i == DRIVER && seat == Wagon.DRIVER) {
            val horse = WagonClient.horseOf(w)
            if (horse != null && WagonClient.harnessed(w, horse)) {
                s.setRenderData(REIN_STEER, Math.toRadians(Mth.lerp(partial, look.steerO, look.steer).toDouble()).toFloat())
            }
        }
    }

    /** Leans a seated crew member's pose with the wagon (TownsfolkRenderer, before they're turned their way). */
    private fun lean(state: TownsfolkRenderState, poseStack: PoseStack) {
        val q: Quaternionf = state.getRenderData(LEAN) ?: return
        poseStack.rotate(q)
    }

    // ---- After the townsfolk model is posed: the reins and the nosebag ----

    /** The parts of one townsfolk model this file poses. */
    private class Parts(model: TownsfolkModel) {
        private val find = model.root().createPartLookup()
        val right: List<ModelPart> = (1..3).mapNotNull { find.apply("rein_right_$it") }
        val left: List<ModelPart> = (1..3).mapNotNull { find.apply("rein_left_$it") }
        val nosebag: ModelPart? = find.apply(NOSEBAG)
    }

    private val parts = WeakHashMap<TownsfolkModel, Parts>()

    private fun afterPose(model: TownsfolkModel, state: TownsfolkRenderState) {
        val steer: Float? = state.getRenderData(REIN_STEER)
        val living = state.living
        val bag = NOSEBAG in living.shown && living.clip == null
        if (steer == null && !bag) return
        val mine = parts.getOrPut(model) { Parts(model) }
        if (steer != null && abs(steer) > STEER_LEAST) {
            reins(model, mine.right, model.rightArm, -1f, steer)
            reins(model, mine.left, model.leftArm, 1f, steer)
        }
        // Carried about between clips (LivingProps hangs it while one plays)
        if (bag) mine.nosebag?.let { hang(model, it, model.leftArm, sin(state.ageInTicks * 0.12f) * 0.05f + state.walkAnimationSpeed * 0.2f * sin(state.walkAnimationPos * 0.6662f)) }
    }

    private val qRoot = Quaternionf()
    private val qArm = Quaternionf()
    private val qBoth = Quaternionf()
    private val qPiece = Quaternionf()
    private val qTurn = Quaternionf()
    private val qTo = Quaternionf()
    private val fist = Vector3f()
    private val ring0 = Vector3f()
    private val ring1 = Vector3f()
    private val a = Vector3f()
    private val b = Vector3f()
    private val dir = Vector3f()
    private val euler = Vector3f()
    private var stretch = 1f

    /**
     * Swings the three pieces of one rein ([chain], held in [arm], on [side]: -1 the right rein, 1 the left) about the
     * fist and draws them out across the ground, so the rein's end, which the clip lays at the horse's ring with the
     * front straight, meets the ring where the front steered [steer] (radians) has taken it.
     */
    private fun reins(model: TownsfolkModel, chain: List<ModelPart>, arm: ModelPart, side: Float, steer: Float) {
        if (chain.isEmpty() || !chain[0].visible) return
        val root = model.root()
        qRoot.rotationZYX(root.zRot, root.yRot, root.xRot)
        qArm.rotationZYX(arm.zRot, arm.yRot, arm.xRot)
        qBoth.set(qRoot).mul(qArm)
        modelPoint(root, arm, chain[0], fist)
        ring(side, 0f, ring0).sub(fist)
        ring(side, steer, ring1).sub(fist)
        val h0 = sqrt(ring0.x * ring0.x + ring0.z * ring0.z)
        val h1 = sqrt(ring1.x * ring1.x + ring1.z * ring1.z)
        if (h0 < 1f) return
        qTurn.rotationY(atan2(ring1.x, ring1.z) - atan2(ring0.x, ring0.z))
        stretch = h1 / h0
        for (piece in chain) {
            if (!piece.visible) continue
            // Its two ends in the model's own space, as the clip laid it
            modelPoint(root, arm, piece, a)
            qPiece.set(qBoth).mul(qTo.rotationZYX(piece.zRot, piece.yRot, piece.xRot))
            dir.set(0f, 1f, 0f)
            qPiece.transform(dir)
            b.set(dir).mul(STRAP * piece.yScale).add(a)
            move(a)
            move(b)
            b.sub(a)
            val length = b.length()
            if (length < 1e-3f) continue
            b.div(length)
            // Turned from where it pointed to where it points now, its twist kept; then back into the arm's frame
            qPiece.premul(qTo.rotationTo(dir, b))
            qTo.set(qBoth).conjugate().mul(qPiece).getEulerAnglesZYX(euler)
            piece.xRot = euler.x
            piece.yRot = euler.y
            piece.zRot = euler.z
            a.sub(root.x, root.y, root.z)
            qRoot.conjugate(qTo).transform(a)
            a.sub(arm.x, arm.y, arm.z)
            qArm.conjugate(qTo).transform(a)
            piece.x = a.x
            piece.y = a.y
            piece.z = a.z
            piece.yScale = length / STRAP
        }
    }

    /** [p] swung about the fist and drawn out across the ground. */
    private fun move(p: Vector3f) {
        p.sub(fist)
        p.x *= stretch
        p.z *= stretch
        qTurn.transform(p)
        p.add(fist)
    }

    /** Where [piece]'s pivot is in the model's own space (where the root is placed), as it's posed now. */
    private fun modelPoint(root: ModelPart, arm: ModelPart, piece: ModelPart, out: Vector3f): Vector3f {
        out.set(piece.x, piece.y, piece.z)
        qArm.transform(out)
        out.add(arm.x, arm.y, arm.z)
        qRoot.transform(out)
        return out.add(root.x, root.y, root.z)
    }

    /**
     * The horse's rein ring on [side] where the front steered [steer] (radians) about its kingpin puts it, in the
     * driver's model space on the driver's seat (feet at 24 px on the seat's top, facing as the wagon does).
     */
    private fun ring(side: Float, steer: Float, out: Vector3f): Vector3f {
        // The wagon's space: the horse straight in the shafts (WagonMesh.TERRET), turned about the kingpin
        val kz = -WagonMesh.WHEELBASE
        val rx = side * WagonMesh.TERRET[0]
        val rz = -(WagonMesh.WHEELBASE + WagonMesh.HORSE_AHEAD) + WagonMesh.TERRET[2] - kz
        val c = kotlin.math.cos(steer)
        val sn = sin(steer)
        val x = rx * c + rz * sn
        val z = -rx * sn + rz * c + kz
        val seat = WagonMesh.SEATS.getValue("driver")
        return out.set(x - seat.x, -WagonMesh.TERRET[1] - seat.y + FEET, z - seat.z)
    }

    /** Turns [part] (held in [arm]) to hang straight down whichever way the root and the arm are turned, stirring by [sway]. */
    private fun hang(model: TownsfolkModel, part: ModelPart, arm: ModelPart, sway: Float) {
        val root = model.root()
        qTo.rotationZYX(root.zRot, root.yRot, root.xRot).mul(qArm.rotationZYX(arm.zRot, arm.yRot, arm.xRot)).conjugate()
        qTo.getEulerAnglesZYX(euler)
        part.xRot = euler.x + sway
        part.yRot = euler.y
        part.zRot = euler.z
    }

    private fun smooth(x: Float): Float {
        val k = x.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    private fun rad(deg: Float): Float = Math.toRadians(deg.toDouble()).toFloat()

    /** How the wagon leans its crew (a turn of the world's, made about the seat), passed from the seating to the renderer; degrees too few to bother with. */
    private val LEAN = ContextKey<Quaternionf>(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "caravan_lean"))
    private const val LEAN_LEAST = 0.05f

    /** The driver's place in the crew. */
    private const val DRIVER = 0

    /** The reins' props (the clips that climb down and up show them on whoever plays them), and the driver's nosebag. */
    private val REINS = listOf("rein_right_1", "rein_right_2", "rein_right_3", "rein_left_1", "rein_left_2", "rein_left_3")
    private const val NOSEBAG = "nosebag_held"

    /** How far the steered front turns the reins (radians), passed from the seating to the model. */
    private val REIN_STEER = ContextKey<Float>(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "caravan_rein_steer"))
    private const val STEER_LEAST = 0.002f

    /** Px the bench is higher than the tailboard (the clips down and up are made from the bench). */
    private const val TAIL_RAISE = CrewMoves.BENCH_DROP - CrewMoves.TAIL_DROP

    /** A rein piece's own length (px along +y), and the townsfolk model's feet over its origin. */
    private const val STRAP = 16f
    private const val FEET = 24f

    /** Degrees a seated head turns from the shoulders at most. */
    private const val HEAD_TURN = 70f
}
