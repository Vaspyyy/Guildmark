package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.client.works.WorksPaint
import io.github.vaspyyy.guildmark.trade.HouseMapBlockEntity
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.hypot

/** What the map of the roads needs drawn for one frame: its pins, its threads and its wagons, in the parchment's own model space. */
class HouseMapRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH

    var pins = 0
    val pinX = FloatArray(MOST)
    val pinY = FloatArray(MOST)
    val pinSize = IntArray(MOST)
    val pinScale = FloatArray(MOST)
    val pinGlint = FloatArray(MOST)

    var threads = 0
    val threadX = FloatArray(MOST + TRIPS)
    val threadY = FloatArray(MOST + TRIPS)
    val threadAngle = FloatArray(MOST + TRIPS)
    val threadLength = FloatArray(MOST + TRIPS)
    val threadHot = BooleanArray(MOST + TRIPS)

    var wagons = 0
    var colour = 0
    val wagonX = FloatArray(TRIPS)
    val wagonY = FloatArray(TRIPS)
    val wagonScale = FloatArray(TRIPS)
    val wagonTilt = FloatArray(TRIPS)
    val wagonFlip = FloatArray(TRIPS)
    val wheelTurn = FloatArray(TRIPS)
    val flagYaw = FloatArray(TRIPS)
    val flagRoll = FloatArray(TRIPS)

    var upper = 0

    companion object {
        const val MOST = HouseMapBlockEntity.PINS
        const val TRIPS = HouseMapBlockEntity.TRIPS
    }
}

/**
 * The Counting House's map of the roads (art stream X3's `house_map`, its pieces [HouseArt.mapMesh]), on the back wall of the counting
 * room: the parchment framed so that every town the owner knows fits it, north up and east to the viewer's right ([HouseArt.mapScale],
 * [HouseArt.mapX], [HouseArt.mapY]); a brass pin for each town ([HouseMapBlockEntity.pins]: the House's own the largest in its gilt ring,
 * the towns where they trade, those they have stood in and those they know only by word, smaller and smaller), popping in when it is set
 * ([HouseArt.pinPop]) and shrinking away when it is taken out, with a glint crossing each now and then; a thread from the House's pin to
 * each town where they trade, run out from the House as the pin is set ([HouseArt.threadGrow]); and, for each trip under way (part G's),
 * a crimson thread along its road and a wagon in the House's colour rolling along it by the trip's own clock: tilted to the road, facing
 * the way it goes, hopping and rocking as its wheels roll, its pennant flying. When a pin comes or goes the map eases to its new framing
 * rather than jumping.
 */
class HouseMapRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<HouseMapBlockEntity, HouseMapRenderState> {
    private val root = context.bakeLayer(TradeBlocksClient.HOUSE_MAP)
    private val pinParts: Array<ModelPart> = Array(HouseArt.PIN_D.size) { root.getChild("pin_$it") }
    private val glint: ModelPart = root.getChild("glint")
    private val thread: ModelPart = root.getChild("thread")
    private val threadHot: ModelPart = root.getChild("thread_hot")
    private val wheel: ModelPart = root.getChild("wheel")
    private val wagonParts: Array<ModelPart> = Array(HouseArt.COLOUR_NAMES.size) { root.getChild("wagon_${HouseArt.COLOUR_NAMES[it]}") }
    private val flagParts: Array<ModelPart> = Array(HouseArt.COLOUR_NAMES.size) { root.getChild("flag_${HouseArt.COLOUR_NAMES[it]}") }

    override fun createRenderState(): HouseMapRenderState = HouseMapRenderState()

    /** How the map was framed: eased from ([fx], [fz], [fk]) toward ([tx], [tz], [tk]) from game time [since]; the renderer's own, kept on the map. */
    private class View(var fx: Float, var fz: Float, var fk: Float, var tx: Float, var tz: Float, var tk: Float, var since: Long)

    override fun extractRenderState(
        be: HouseMapBlockEntity, state: HouseMapRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        state.pins = 0
        state.threads = 0
        state.wagons = 0
        val level = be.level ?: return
        state.facing = WorksPaint.facing(be.blockState)
        state.upper = LightCoordsUtil.getLightCoords(level, be.blockPos.above())
        state.colour = HouseArt.nearest(Dye.entries[be.colour.coerceIn(0, Dye.entries.size - 1)].rgb)
        val now = level.gameTime
        val time = WorksPaint.time(now, partialTicks)
        val pins = be.pins
        if (pins.isEmpty()) return

        // The framing: the towns still pinned and the ends of the trips, eased to when that changes
        var x0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var z0 = Float.MAX_VALUE
        var z1 = -Float.MAX_VALUE
        fun take(capital: Long) {
            val p = BlockPos.of(capital)
            x0 = minOf(x0, p.x.toFloat())
            x1 = maxOf(x1, p.x.toFloat())
            z0 = minOf(z0, p.z.toFloat())
            z1 = maxOf(z1, p.z.toFloat())
        }
        for (p in pins) if (p.gone == 0L) take(p.capital)
        for (t in be.trips) {
            take(t.from)
            take(t.to)
        }
        if (x0 > x1) for (p in pins) take(p.capital)
        val tx = (x0 + x1) / 2f
        val tz = (z0 + z1) / 2f
        val tk = HouseArt.mapScale(x1 - x0, z1 - z0)
        val view = (be.view as? View) ?: View(tx, tz, tk, tx, tz, tk, 0L).also { be.view = it }
        if (view.tx != tx || view.tz != tz || view.tk != tk) {
            val u = WorksPaint.smooth((now - view.since + partialTicks) / EASE)
            view.fx = WorksPaint.lerp(view.fx, view.tx, u)
            view.fz = WorksPaint.lerp(view.fz, view.tz, u)
            view.fk = WorksPaint.lerp(view.fk, view.tk, u)
            view.tx = tx
            view.tz = tz
            view.tk = tk
            view.since = now
        }
        val u = WorksPaint.smooth((now - view.since + partialTicks) / EASE)
        val cx = WorksPaint.lerp(view.fx, view.tx, u)
        val cz = WorksPaint.lerp(view.fz, view.tz, u)
        val k = WorksPaint.lerp(view.fk, view.tk, u)

        // The pins, and the threads from the House's own to the towns where the owner trades
        val home = pins.firstOrNull { it.size >= 3 && it.gone == 0L }
        val homeX = home?.let { HouseArt.mapX(BlockPos.of(it.capital).x.toFloat(), cx, k) } ?: 0f
        val homeY = home?.let { HouseArt.mapY(BlockPos.of(it.capital).z.toFloat(), cz, k) } ?: 0f
        for ((i, p) in pins.withIndex()) {
            if (state.pins >= HouseMapRenderState.MOST) break
            val at = BlockPos.of(p.capital)
            val x = HouseArt.mapX(at.x.toFloat(), cx, k)
            val y = HouseArt.mapY(at.z.toFloat(), cz, k)
            val born = if (p.born <= 1L) WorksPaint.NEVER else WorksPaint.since(now, p.born, partialTicks)
            val fade = if (p.gone == 0L) 1f else 1f - WorksPaint.smooth(WorksPaint.since(now, p.gone, partialTicks) / HouseArt.POP_TICKS)
            val scale = HouseArt.pinPop(born) * fade
            if (home != null && p !== home && p.size == TRADES) {
                val laid = if (p.born <= 1L && home.born <= 1L) WorksPaint.NEVER else WorksPaint.since(now, maxOf(p.born, home.born), partialTicks) - THREAD_AFTER
                val grow = HouseArt.threadGrow(laid) * fade
                if (grow > 0f) addThread(state, homeX, homeY, x, y, grow, false)
            }
            if (scale <= 0f) continue
            val n = state.pins++
            state.pinX[n] = x
            state.pinY[n] = y
            state.pinSize[n] = p.size.coerceIn(0, HouseArt.PIN_D.size - 1)
            state.pinScale[n] = scale
            state.pinGlint[n] = if (born >= HouseArt.POP_TICKS && fade >= 1f) HouseArt.glint(time, i) else 0f
        }

        // The trips under way: the road in use, and a wagon on it by the trip's clock
        for ((j, t) in be.trips.withIndex()) {
            if (state.wagons >= HouseMapRenderState.TRIPS) break
            val a = BlockPos.of(t.from)
            val b = BlockPos.of(t.to)
            val ax = HouseArt.mapX(a.x.toFloat(), cx, k)
            val ay = HouseArt.mapY(a.z.toFloat(), cz, k)
            val bx = HouseArt.mapX(b.x.toFloat(), cx, k)
            val by = HouseArt.mapY(b.z.toFloat(), cz, k)
            val length = hypot(bx - ax, by - ay)
            if (length < 1e-3f) continue
            val age = WorksPaint.since(now, t.start, partialTicks)
            addThread(state, ax, ay, bx, by, HouseArt.threadGrow(age), true)
            val progress = (age / t.ticks.coerceAtLeast(1)).coerceIn(0f, 1f)
            val dx = (bx - ax) / length
            val dy = (by - ay) / length
            val gone = progress * length
            val phase = 0.9f * j
            val n = state.wagons++
            state.wagonX[n] = ax + dx * gone
            state.wagonY[n] = ay + dy * gone + HouseArt.wagonBob(gone, phase)
            state.wagonScale[n] = maxOf(0.01f, HouseArt.pinPop(age))
            state.wagonTilt[n] = HouseArt.wagonTilt(dx, dy) + HouseArt.wagonRock(gone, phase)
            state.wagonFlip[n] = HouseArt.wagonFlip(dx)
            // The way rolled toward the viewer's right (the map's -x)
            state.wheelTurn[n] = HouseArt.wheelTurn(-dx * gone)
            val run = if (progress < 1f) 1f else 0f
            state.flagYaw[n] = HouseArt.flagYaw(time, phase, run)
            state.flagRoll[n] = HouseArt.flagRoll(time, phase, run)
        }
    }

    private fun addThread(state: HouseMapRenderState, x0: Float, y0: Float, x1: Float, y1: Float, grow: Float, hot: Boolean) {
        if (state.threads >= state.threadX.size) return
        val length = hypot(x1 - x0, y1 - y0) * grow.coerceIn(0f, 1f)
        if (length < 1e-3f) return
        val n = state.threads++
        state.threadX[n] = x0
        state.threadY[n] = y0
        state.threadAngle[n] = Math.toDegrees(atan2(y1 - y0, x1 - x0).toDouble()).toFloat()
        state.threadLength[n] = length
        state.threadHot[n] = hot
    }

    override fun submit(state: HouseMapRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (state.pins == 0 && state.wagons == 0) return
        val light = maxOf(state.lightCoords, state.upper)
        val z = HouseArt.MAP_Z
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        // The threads under the pins, the pins, and the wagons over both
        for (n in 0 until state.threads) {
            atPx(poseStack, state.threadX[n], state.threadY[n], z) {
                poseStack.rotateDegrees(Axis.ZP, state.threadAngle[n])
                poseStack.scale(state.threadLength[n], 1f, 1f)
                drawPart(poseStack, collector, if (state.threadHot[n]) threadHot else thread, CUTOUT, light)
            }
        }
        for (n in 0 until state.pins) {
            val size = state.pinSize[n]
            atPx(poseStack, state.pinX[n], state.pinY[n], z) {
                poseStack.scale(state.pinScale[n], state.pinScale[n], state.pinScale[n])
                drawPart(poseStack, collector, pinParts[size], CUTOUT, light)
            }
            val g = state.pinGlint[n]
            if (g > 0.02f) {
                val off = HouseArt.PIN_D[size] * 0.2f
                atPx(poseStack, state.pinX[n] + off, state.pinY[n] + off, z) {
                    poseStack.scale(g, g, g)
                    drawPart(poseStack, collector, glint, CUTOUT, light)
                }
            }
        }
        val colour = state.colour.coerceIn(0, wagonParts.size - 1)
        for (n in 0 until state.wagons) wagon(state, n, poseStack, collector, colour, light)
        poseStack.popPose()
    }

    /** A wagon: tilted to its road; its wheels in the tilted frame, rolling; the wagon and its pennant turned about up to face the way it goes. */
    private fun wagon(state: HouseMapRenderState, n: Int, poseStack: PoseStack, collector: SubmitNodeCollector, colour: Int, light: Int) {
        val s = state.wagonScale[n]
        atPx(poseStack, state.wagonX[n], state.wagonY[n], HouseArt.MAP_Z + HouseArt.WAGON_DZ * s) {
            poseStack.scale(s, s, s)
            poseStack.rotateDegrees(Axis.ZP, state.wagonTilt[n])
            for (wx in WHEELS) {
                atPx(poseStack, wx, HouseArt.WHEEL_Y, -HouseArt.WHEEL_Z) {
                    poseStack.rotateDegrees(Axis.ZP, state.wheelTurn[n])
                    drawPart(poseStack, collector, wheel, CUTOUT, light)
                }
            }
            poseStack.rotateDegrees(Axis.YP, state.wagonFlip[n])
            drawPart(poseStack, collector, wagonParts[colour], CUTOUT, light)
            atPx(poseStack, HouseArt.FLAG_X, HouseArt.FLAG_Y, 0f) {
                poseStack.rotateDegrees(Axis.YP, state.flagYaw[n])
                poseStack.rotateDegrees(Axis.ZP, state.flagRoll[n])
                drawPart(poseStack, collector, flagParts[colour], CUTOUT, light)
            }
        }
    }

    // The panel is three blocks across and two high, this block the middle of its bottom row
    override fun getRenderBoundingBox(be: HouseMapBlockEntity): AABB = AABB(be.blockPos).inflate(1.0, 0.0, 1.0).expandTowards(0.0, 1.0, 0.0)

    companion object {
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(HouseArt.MAP_TEXTURE))

        /** The pin size of the towns where the owner trades (those a thread runs to); ticks after a pin is set that its thread starts out. */
        private const val TRADES = 2
        private const val THREAD_AFTER = 4f

        /** Ticks the map takes to ease to a new framing. */
        private const val EASE = 30f

        private val WHEELS = floatArrayOf(HouseArt.WHEEL_X0, HouseArt.WHEEL_X1)
    }
}
