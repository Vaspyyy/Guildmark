package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.caravan.DraughtHorse
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.client.caravan.DraughtHorseMesh
import io.github.vaspyyy.guildmark.client.caravan.HorseClient
import io.github.vaspyyy.guildmark.client.caravan.HorseModel
import io.github.vaspyyy.guildmark.client.caravan.HorsePose
import io.github.vaspyyy.guildmark.client.caravan.HorseRenderState
import io.github.vaspyyy.guildmark.client.caravan.WagonClient
import io.github.vaspyyy.guildmark.client.caravan.WagonPose
import io.github.vaspyyy.guildmark.client.caravan.WagonRenderState
import io.github.vaspyyy.guildmark.client.caravan.WagonRenderer
import io.github.vaspyyy.guildmark.network.WagonSyncPayload
import io.github.vaspyyy.guildmark.network.WagonUsePayload
import io.github.vaspyyy.guildmark.trade.TradeSounds
import io.github.vaspyyy.guildmark.trade.carry.LedShow
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.function.BiConsumer
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Carrier's Wagon on the client (living-world/trader/design.md, Part 3; build part D3): the client's own entry for this part, beside the
 * others, so the start-up code of the client is left as it was. It hears what the server says of each trader's wagon ([WagonSyncPayload]) and
 * draws what the caravans' wagon and horse do not: the lead rein, the pennant in its owner's colour and the name board.
 */
@Mod(value = Guildmark.MOD_ID, dist = [Dist.CLIENT])
class CarrierWagonClientMod(modBus: IEventBus) {
    init {
        WagonSyncPayload.received = { ReinRenderer.heard(it) }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(CarrierWagonArt.LAYER) { CarrierWagonArt.active().mesh() }
        }
        modBus.addListener(RegisterRenderStateModifiersEvent::class.java) { event ->
            event.registerEntityModifier(WagonRenderer::class.java, BiConsumer<Wagon, WagonRenderState> { w, s -> ReinRenderer.modify(w, s) })
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> ReinRenderer.tick() }
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent::class.java) { event: SubmitCustomGeometryEvent -> ReinRenderer.submit(event) }
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered::class.java, ReinRenderer::onUse)
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> ReinRenderer.clear() }
    }
}

/**
 * What the client draws and hears of a trader's wagon (part D3), its wheels, sway, brake, tailboard and lantern being the caravans' wagon's own:
 * - **The lead rein**, from the hand of whoever leads it to the ring of the horse's bit: drawn tight while the horse is pulling and slack (a
 *   sagging loop) as it slows; with nobody leading it, it hangs from the bit to the ground.
 * - **The dressing** ([CarrierWagonArt], behind its one call for X2's art): the pennant on the wagon's staff in its owner's colour (the caravans' own
 *   cloth is hidden on a trader's wagon), furled in the yard, run up the staff as it is rolled out, then streaming in the same wind as the tilt; the
 *   name board on each side of the bed with the owner's name on it; the guard's cushion on the bench and the pad on the let-down tailboard.
 * - **The creak** of its wheels (`trade.cart_creak`, as the Chapman's Cart has it): at the pitch of its speed over the pulled walk's, louder the
 *   fuller it is.
 * - **A hand on it:** the client finds whether the horse's head (an empty hand) or the tailboard is looked at, close, and says so
 *   ([WagonUsePayload]); the server decides what comes of it.
 */
internal object ReinRenderer {
    /** What this client knows of one trader's wagon: what the server last said, the rein's slack, and its creak. */
    private class Known(var p: WagonSyncPayload) {
        var heard = 0L
        var slack = 1f
        var slackO = 1f
        var creak: Creak? = null
    }

    private val known = HashMap<Int, Known>()
    private var ticks = 0L

    private val pose = WagonPose()
    private val horsePose = HorsePose()
    private var horseModel: HorseModel? = null
    private val horseState = HorseRenderState()
    private val vf = Vector3f()
    private val vd = Vector3d()

    fun heard(p: WagonSyncPayload) {
        if (!p.alive) {
            known.remove(p.wagon)?.creak?.end()
            return
        }
        val k = known.getOrPut(p.wagon) { Known(p) }
        k.p = p
        k.heard = ticks
    }

    fun clear() {
        for (k in known.values) k.creak?.end()
        known.clear()
        horseModel = null
        dressing = null
    }

    /** A trader's wagon flies its owner's pennant, not the caravans'. */
    fun modify(w: Wagon, s: WagonRenderState) {
        if (known.containsKey(w.id)) s.overrideModelPartVisibility("pennant", false)
    }

    // ---- Each tick ----

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (minecraft.isPaused || known.isEmpty()) return
        ticks++
        val it = known.values.iterator()
        while (it.hasNext()) {
            val k = it.next()
            val w = level.getEntity(k.p.wagon) as? Wagon
            if (w == null) {
                if (ticks - k.heard > FORGET) {
                    k.creak?.end()
                    it.remove()
                }
                continue
            }
            k.slackO = k.slack
            k.slack = Mth.approach(k.slack, if (k.p.taut) 0f else 1f, if (k.p.taut) TIGHTEN else SLACKEN)
            creak(w, k, hypot(w.x - w.xo, w.z - w.zo) * 20.0)
        }
    }

    private fun creak(w: Wagon, k: Known, speed: Double) {
        var loop = k.creak
        if (loop == null || loop.isStopped) {
            if (speed < CREAK_FROM) return
            loop = Creak(w)
            k.creak = loop
            Minecraft.getInstance().soundManager.play(loop)
        }
        loop.speed = speed
        loop.laden = k.p.laden
    }

    /** The wheels' creak on one trader's wagon (the Chapman's Cart's loop, its pitch and volume worked the same way). */
    private class Creak(private val w: Wagon) : AbstractTickableSoundInstance(TradeSounds.get("trade.cart_creak"), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        var speed = 0.0
        var laden = 0f
        private var still = 0

        init {
            looping = true
            volume = 0f
            x = w.x
            y = w.y + 0.5
            z = w.z
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (w.isRemoved) return stop()
            x = w.x
            y = w.y + 0.5
            z = w.z
            val moving = (speed / CREAK_FULL).toFloat().coerceIn(0f, 1f)
            val target = moving * (CREAK_QUIET + (CREAK_LOUD - CREAK_QUIET) * laden.coerceIn(0f, 1f))
            volume += (target - volume) * if (target > volume) 0.25f else 0.12f
            if (volume < 0.003f && target <= 0f) volume = 0f
            pitch = (speed / PULLED_WALK).toFloat().coerceIn(CREAK_LOW, CREAK_HIGH)
            still = if (volume <= 0f) still + 1 else 0
            if (still > CREAK_LINGER) stop()
        }

        fun end() = stop()
    }

    // ---- A hand on it ----

    /**
     * The use key with the horse's head (and an empty hand) or the tailboard of a trader's wagon looked at closely, nearer than the block looked at:
     * the server is asked to take the lead (or let go) or open the hold.
     */
    fun onUse(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem || event.hand != InteractionHand.MAIN_HAND || known.isEmpty()) return
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (me.isSpectator) return
        val hit = minecraft.hitResult
        if (hit is EntityHitResult) return
        val eye = me.eyePosition
        val dir = me.lookAngle
        val blocked = if (hit != null && hit.type == HitResult.Type.BLOCK) hit.location.distanceTo(eye) else REACH
        var bestId = -1
        var bestPart = 0
        var bestAway = Double.MAX_VALUE
        fun consider(at: Vec3, aim: Double, id: Int, part: Int) {
            val along = (at.x - eye.x) * dir.x + (at.y - eye.y) * dir.y + (at.z - eye.z) * dir.z
            if (along < 0.2 || along > min(blocked + 0.6, REACH)) return
            val away = at.distanceTo(eye.add(dir.scale(along)))
            if (away < aim && away < bestAway) {
                bestAway = away
                bestId = id
                bestPart = part
            }
        }
        for (k in known.values) {
            val w = level.getEntity(k.p.wagon) as? Wagon ?: continue
            if (w.distanceToSqr(me) > NEAR_USE * NEAR_USE) continue
            consider(w.point(0f, -16f, 12f), TAIL_AIM, w.id, LedShow.TAIL)
            if (!me.mainHandItem.isEmpty) continue
            val h = level.getEntity(k.p.horse) as? DraughtHorse ?: continue
            val r = Math.toRadians(h.yRot.toDouble())
            consider(Vec3(h.x - sin(r) * HEAD_AHEAD, h.y + HEAD_UP, h.z + Math.cos(r) * HEAD_AHEAD), HEAD_AIM, w.id, LedShow.HEAD)
        }
        if (bestId < 0) return
        ClientPacketDistributor.sendToServer(WagonUsePayload(bestId, bestPart))
        event.isCanceled = true
        event.setSwingHand(true)
    }

    // ---- Drawing ----

    /** A corner of the rein drawn this frame: its place (from the frame's origin), its texture's corner, colour and light. */
    private class Corner(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float, val color: Int, val light: Int)

    private class Quads {
        val v = ArrayList<Corner>()

        fun quad(a: Vector3d, b: Vector3d, c: Vector3d, d: Vector3d, o: Vector3d, color: Int, light: Int) {
            v.add(corner(a, o, 0f, 0f, color, light))
            v.add(corner(b, o, 1f, 0f, color, light))
            v.add(corner(c, o, 1f, 1f, color, light))
            v.add(corner(d, o, 0f, 1f, color, light))
        }

        private fun corner(p: Vector3d, o: Vector3d, u: Float, vv: Float, color: Int, light: Int): Corner =
            Corner((p.x - o.x).toFloat(), (p.y - o.y).toFloat(), (p.z - o.z).toFloat(), u, vv, color, light)
    }

    /** The dressing's parts, baked once from [CarrierWagonArt.active]'s layer. */
    private class Dressing(root: ModelPart) {
        private fun find(root: ModelPart, name: String): ModelPart? = if (root.hasChild(name)) root.getChild(name) else null

        val boards: List<ModelPart> = listOfNotNull(find(root, "board_r"), find(root, "board_l"))
        val bench: ModelPart? = find(root, "seat_bench")
        val tail: ModelPart? = find(root, "seat_tail")
        val flags: List<ModelPart> = generateSequence(0) { it + 1 }.map { find(root, "flag_$it") }.takeWhile { it != null }.filterNotNull().toList()
    }

    private var dressing: Dressing? = null

    private fun dressing(): Dressing? = dressing ?: try {
        Dressing(Minecraft.getInstance().entityModels.bakeLayer(CarrierWagonArt.LAYER)).also { dressing = it }
    } catch (e: RuntimeException) {
        null
    }

    fun submit(event: SubmitCustomGeometryEvent) {
        if (known.isEmpty()) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val state = event.levelRenderState
        val camera = state.cameraRenderState.pos
        val partial = state.worldPartialTicks
        val o = Vector3d(floor(camera.x), floor(camera.y), floor(camera.z))
        val quads = Quads()
        val stack = event.poseStack
        val collector = event.submitNodeCollector
        val table = CarrierWagonArt.active()
        val parts = dressing()
        for (k in known.values) {
            val w = level.getEntity(k.p.wagon) as? Wagon ?: continue
            val dx = w.x - camera.x
            val dz = w.z - camera.z
            val far = dx * dx + dz * dz
            if (far > SEEN * SEEN) continue
            WagonClient.pose(w, partial, pose)
            rein(level, w, k, partial, quads, o)
            if (parts == null) continue
            val light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(pose.x, pose.y + 1.0, pose.z))
            stack.pushPose()
            // As the caravans' wagon is drawn: about its back axle as it stands, in its own space (px, y down, its front -z)
            stack.translate(pose.x - camera.x, pose.y - camera.y, pose.z - camera.z)
            stack.translate(0.0, Wagon.REAR_RADIUS, 0.0)
            stack.rotateDegrees(Axis.YP, 180f - pose.yaw)
            stack.rotateDegrees(Axis.XP, pose.pitch)
            stack.rotateDegrees(Axis.ZP, pose.roll)
            stack.translate(0.0, -Wagon.REAR_RADIUS, 0.0)
            stack.scale(-1f, -1f, 1f)
            dress(level, w, k, partial, parts, table, light, stack, collector, far < WORDS * WORDS, minecraft)
            stack.popPose()
        }
        val shown = quads.v
        if (shown.isEmpty()) return
        stack.pushPose()
        stack.translate(o.x - camera.x, o.y - camera.y, o.z - camera.z)
        collector.submitCustomGeometry(stack, RenderTypes.entityCutout(WOOL)) { p, buffer ->
            for (c in shown) {
                buffer.addVertex(p, c.x, c.y, c.z).setColor(c.color).setUv(c.u, c.v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(c.light).setNormal(p, 0f, 1f, 0f)
            }
        }
        stack.popPose()
    }

    /**
     * [w]'s dressing in its own space: the name boards with the owner's name written on them (read close to), the seats (the tailboard's pad only with
     * it let down), and the pennant in the owner's colour, run up the staff after [WagonSyncPayload.raised] and rippling in the tilt's wind.
     */
    private fun dress(
        level: ClientLevel, w: Wagon, k: Known, partial: Float, parts: Dressing, table: CarrierWagonArt.Table, light: Int, stack: PoseStack,
        collector: SubmitNodeCollector, words: Boolean, minecraft: Minecraft,
    ) {
        val type = RenderTypes.entityCutout(table.texture)
        for (b in parts.boards) collector.submitModelPart(b, stack, type, light, OverlayTexture.NO_OVERLAY, null, table.boardTint, 0)
        parts.bench?.let { collector.submitModelPart(it, stack, type, light, OverlayTexture.NO_OVERLAY, null, table.seatTint, 0) }
        val look = WagonClient.look(w)
        val tail = Mth.lerp(partial, look.tailO, look.tail)
        if (tail < TAIL_DOWN) parts.tail?.let { collector.submitModelPart(it, stack, type, light, OverlayTexture.NO_OVERLAY, null, table.seatTint, 0) }
        // The pennant: furled in the yard; run up the staff over a couple of seconds as it is rolled out; then streaming
        val raised = k.p.raised
        if (raised >= 0L && parts.flags.isNotEmpty()) {
            val up = ((level.gameTime + partial - raised) / RUN_UP).coerceIn(0f, 1f)
            val e = up * up * (3f - 2f * up)
            val flap = Mth.lerp(partial, look.flapO, look.flap)
            val size = look.flapSize * (0.35f + 0.65f * e)
            val colour = ARGB.color(255, k.p.rgb)
            stack.pushPose()
            stack.translate(0f, table.staffRun * (1f - e) / 16f, 0f)
            for ((i, seg) in parts.flags.withIndex()) {
                val hinge = table.ripples.getOrNull(i)
                if (hinge != null) {
                    stack.translate(hinge.a / 16f, 0f, hinge.b / 16f)
                    stack.rotate(Axis.YP.rotation(RIPPLE[i.coerceAtMost(RIPPLE.size - 1)] * size * sin(flap - i * 1.1f)))
                    stack.translate(-hinge.a / 16f, 0f, -hinge.b / 16f)
                }
                collector.submitModelPart(seg, stack, type, light, OverlayTexture.NO_OVERLAY, null, colour, 0)
            }
            stack.popPose()
        }
        // The owner's name on each board, facing out of its side and read from the back toward the front on the right
        if (!words || k.p.owner.isEmpty()) return
        val line = Component.literal(k.p.owner).visualOrderText
        val width = minecraft.font.width(line).coerceAtLeast(1)
        for (n in table.names) {
            val s = min(n.wide / width, n.high / 9f)
            stack.pushPose()
            stack.translate(n.x / 16f + n.side * NAME_OUT, n.y / 16f, n.z / 16f)
            // Its x along the board, its y up, its face out of the side: the font's down is the wagon's down once its y is turned over
            val q = Quaternionf().setFromNormalized(Matrix3f(Vector3f(0f, 0f, n.side), Vector3f(0f, -1f, 0f), Vector3f(n.side, 0f, 0f)))
            stack.rotate(q)
            stack.scale(s, -s, s)
            collector.submitText(stack, -width / 2f, -4.5f, line, false, Font.DisplayMode.POLYGON_OFFSET, light, INK, 0, 0)
            stack.popPose()
        }
    }

    /**
     * The lead rein: from the leader's hand to the horse's bit, tight while the horse pulls and sagging as it slows; with nobody on it, hanging
     * from the bit to the ground beside the horse's head.
     */
    private fun rein(level: ClientLevel, w: Wagon, k: Known, partial: Float, quads: Quads, o: Vector3d) {
        val h = level.getEntity(k.p.horse) as? DraughtHorse ?: return
        if (!WagonClient.harnessed(w, h)) return
        val bit = bit(h, partial) ?: return
        val holder = if (k.p.holder >= 0) level.getEntity(k.p.holder) as? Player else null
        if (holder != null) {
            val hand = holder.getRopeHoldPosition(partial)
            val from = Vector3d(hand.x, hand.y, hand.z)
            val slack = Mth.lerp(partial, k.slackO, k.slack)
            val length = from.distance(bit)
            val sag = (TAUT_SAG + (SLACK_SAG - TAUT_SAG) * slack) * length.toFloat() + slack * SLACK_MORE
            cord(level, from, bit, sag, quads, o, REIN)
        } else {
            val yaw = Math.toRadians(Mth.lerp(partial, h.yRotO, h.yRot).toDouble())
            val fx = -sin(yaw)
            val fz = Math.cos(yaw)
            val ground = Mth.lerp(partial.toDouble(), h.yOld, h.y) + 0.04
            val end = Vector3d(bit.x + fx * 0.25 + fz * 0.3, ground, bit.z + fz * 0.25 - fx * 0.3)
            cord(level, bit, end, 0.12f, quads, o, REIN)
        }
    }

    /** Where the ring of [h]'s bit (its near side) is as it is drawn this frame. */
    private fun bit(h: DraughtHorse, partial: Float): Vector3d? {
        val model = horseModel ?: try {
            HorseModel(Minecraft.getInstance().entityModels.bakeLayer(HorseModel.LAYER)).also { horseModel = it }
        } catch (e: RuntimeException) {
            return null
        }
        HorseClient.fill(h, horseState, partial)
        model.poseNow(horseState)
        model.point(DraughtHorseMesh.BIT_LEFT, vf)
        val p = partial.toDouble()
        horsePose.set(Mth.lerp(p, h.xOld, h.x), Mth.lerp(p, h.yOld, h.y), Mth.lerp(p, h.zOld, h.z), horseState.yaw, horseState.pitch)
        return horsePose.world(vf, Vector3d())
    }

    /** A cord from [a] to [b] sagging [sag] blocks at its middle: two thin strips crossed along it, so it reads from any side. */
    private fun cord(level: ClientLevel, a: Vector3d, b: Vector3d, sag: Float, quads: Quads, o: Vector3d, color: Int) {
        val mid = Vector3d(a).add(b).mul(0.5)
        val light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(mid.x, mid.y, mid.z))
        val points = Array(CORD_STEPS + 1) { i ->
            val t = i / CORD_STEPS.toDouble()
            Vector3d(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t - sag * 4.0 * t * (1.0 - t), a.z + (b.z - a.z) * t)
        }
        for (i in 0 until CORD_STEPS) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val dz = p1.z - p0.z
            val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
            // Across it on the level, and across that and the cord both
            var sx = -dz
            var sz = dx
            val sl = hypot(sx, sz)
            if (sl < 1e-6) {
                sx = 1.0
                sz = 0.0
            } else {
                sx /= sl
                sz /= sl
            }
            val ddx = dx / len
            val ddy = dy / len
            val ddz = dz / len
            var tx = ddy * sz
            var ty = ddz * sx - ddx * sz
            var tz = -ddy * sx
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6)
            tx /= tl
            ty /= tl
            tz /= tl
            val r = CORD_HALF
            quads.quad(
                Vector3d(p0.x - sx * r, p0.y, p0.z - sz * r), Vector3d(p0.x + sx * r, p0.y, p0.z + sz * r),
                Vector3d(p1.x + sx * r, p1.y, p1.z + sz * r), Vector3d(p1.x - sx * r, p1.y, p1.z - sz * r), o, color, light,
            )
            quads.quad(
                Vector3d(p0.x - tx * r, p0.y - ty * r, p0.z - tz * r), Vector3d(p0.x + tx * r, p0.y + ty * r, p0.z + tz * r),
                Vector3d(p1.x + tx * r, p1.y + ty * r, p1.z + tz * r), Vector3d(p1.x - tx * r, p1.y - ty * r, p1.z - tz * r), o, color, light,
            )
        }
    }

    // ---- Tuning ----

    /** The rein's cloth: vanilla's white wool, tinted dark leather. */
    private val WOOL = Identifier.withDefaultNamespace("textures/block/white_wool.png")

    /** Client ticks a wagon nothing has been heard of (and that is not here) is remembered; blocks within which it is drawn, and its boards read. */
    private const val FORGET = 400L
    private const val SEEN = 64.0
    private const val WORDS = 24.0

    /** How fast the rein draws tight and goes slack (a share a tick); how far it sags at its middle for each block long, taut and slack, and a slack one's more. */
    private const val TIGHTEN = 0.2f
    private const val SLACKEN = 0.08f
    private const val TAUT_SAG = 0.015f
    private const val SLACK_SAG = 0.11f
    private const val SLACK_MORE = 0.25f

    /** The rein: its pieces, its half-width (blocks) and its colour (dark leather). */
    private const val CORD_STEPS = 14
    private const val CORD_HALF = 0.022
    private val REIN = ARGB.color(255, 0x4A2F1C)

    /** The pennant's run up the staff (ticks), and how far each segment ripples at most (radians). */
    private const val RUN_UP = 40f
    private val RIPPLE = floatArrayOf(0.22f, 0.36f, 0.5f)

    /** The tailboard (degrees up from let down) below which its pad shows; how far out of the board the name is written (blocks), and its ink. */
    private const val TAIL_DOWN = 4f
    private const val NAME_OUT = 0.004f
    private val INK = ARGB.color(255, 0x2B1D10)

    /** A hand on it: how far it reaches, how near a wagon must be to be looked at, how close the look must pass the head and the tailboard, and where the head is. */
    private const val REACH = 4.5
    private const val NEAR_USE = 12.0
    private const val HEAD_AIM = 0.7
    private const val TAIL_AIM = 0.8
    private const val HEAD_AHEAD = 1.4
    private const val HEAD_UP = 1.7

    /** The creak: the speed it begins at and is loudest at (blocks a second), the pulled walk (pitch 1), its pitch limits, its volume when quiet and loud, ticks it lingers silent. */
    private const val CREAK_FROM = 0.4
    private const val CREAK_FULL = 1.6
    private const val PULLED_WALK = 3.67
    private const val CREAK_LOW = 0.5f
    private const val CREAK_HIGH = 1.6f
    private const val CREAK_QUIET = 0.35f
    private const val CREAK_LOUD = 0.8f
    private const val CREAK_LINGER = 40
}
