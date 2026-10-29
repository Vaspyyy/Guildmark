package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.client.works.WorksPaint
import io.github.vaspyyy.guildmark.trade.DoorBoardBlockEntity
import io.github.vaspyyy.guildmark.trade.NameBoardBlockEntity
import io.github.vaspyyy.guildmark.trade.StoreLife
import io.github.vaspyyy.guildmark.trade.TradeBlocks
import net.minecraft.client.gui.Font
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** What a trader's name board needs drawn for one frame: its swing, its lettering, and the owner's pennant on its pole. */
class NameBoardRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH

    /** A Counting House's board (in the House's colour, [colour] of HouseArt's six) rather than a Store's. */
    var house = false
    var colour = 0
    var swing = 0f
    var lines: List<FormattedCharSequence> = emptyList()
    var widths: IntArray = IntArray(0)

    /** The pennant: shown at all; its pole's foot from this block's corner (blocks); how high it is hoisted; how far it flies out (0 limp to 1). */
    var pennant = false
    var poleX = 0f
    var poleY = 0f
    var poleZ = 0f
    var hoist = 0f
    var spread = 1f
    var flyX = 0
    var flyZ = 0
    var rgb = 0xFFFFFF
    var poleLight = 0
    var time = 0f
    var seed = 0f
}

/**
 * The Carriers' Store's name board (art stream X3's `store_name_board`: its board [StoreArt.boardsMesh] "board"), swinging slowly on its
 * two rings in two sways that never keep step ([StoreArt.boardSwing], twice as far in rain), and lettered on both faces with its owner's
 * name over "Carriers' Store" ([StoreArt.NAME_LINES]: each line centred and shrunk to fit, cream letters with a dark edge). The owner's
 * pennant flies from the pole by the door, drawn from this board's record of it: run up hand over hand the moment the clerk hauls on
 * the halyard ([StoreLife.RAISE_TICKS]), limp as it climbs and flying out as it reaches the top, then streaming in the wind with a
 * ripple running down it. It wears the cloth of the stalls' own pennants, in the owner's colour.
 *
 * A Counting House's board (`house_name_board`) is drawn by the same: [HouseArt.boardsMesh]'s board in the one of its six colours nearest
 * the owner's ([HouseArt.nearest]), a counting frame at each end, lettered with the owner's name over "Counting House"; it flies no pennant.
 */
class NameBoardRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<NameBoardBlockEntity, NameBoardRenderState> {
    private val font: Font = context.font()
    private val board: ModelPart = context.bakeLayer(TradeBlocksClient.STORE_BOARDS).getChild("board")
    private val houseBoards: Array<ModelPart> = context.bakeLayer(TradeBlocksClient.HOUSE_BOARDS).let { root ->
        Array(HouseArt.COLOUR_NAMES.size) { root.getChild("board_${HouseArt.COLOUR_NAMES[it]}") }
    }

    override fun createRenderState(): NameBoardRenderState = NameBoardRenderState()

    override fun extractRenderState(
        be: NameBoardBlockEntity, state: NameBoardRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        val pos = be.blockPos
        state.facing = WorksPaint.facing(be.blockState)
        state.house = be.blockState.`is`(TradeBlocks.HOUSE_BOARD.get())
        val dye = Dye.entries[be.color.coerceIn(0, Dye.entries.size - 1)]
        state.colour = HouseArt.nearest(dye.rgb)
        val time = WorksPaint.time(level.gameTime, partialTicks)
        state.time = time
        state.seed = (Math.floorMod(pos.asLong() * 31L, 997L)).toFloat()
        val wet = level.isRainingAt(pos.above())
        state.swing = if (state.house) HouseArt.boardSwing(time, state.seed * 0.013f, wet) else StoreArt.boardSwing(time, state.seed * 0.013f, wet)
        val words = words(be, state.house)
        state.lines = words.lines
        state.widths = words.widths
        // The pennant, once it has been run up
        val pole = be.pole
        state.pennant = pole != null && be.raised != 0L
        if (pole == null || be.raised == 0L) return
        state.poleX = (pole.x - pos.x + 0.5f)
        state.poleY = (pole.y - pos.y).toFloat()
        state.poleZ = (pole.z - pos.z + 0.5f)
        state.flyX = be.fly.stepX
        state.flyZ = be.fly.stepZ
        state.rgb = dye.rgb
        state.poleLight = LightCoordsUtil.getLightCoords(level, pole.above(be.top))
        val age = if (be.raised <= 1L) WorksPaint.NEVER else WorksPaint.since(level.gameTime, be.raised, partialTicks)
        val u = (age / StoreLife.RAISE_TICKS).coerceIn(0f, 1f)
        // Hand over hand: up in pulls, a little hitch at each, easing in at the top
        val climb = (WorksPaint.smooth(u) + HEAVE * sin(u * PI.toFloat() * 2f * PULLS) * u * (1f - u)).coerceIn(0f, 1f)
        state.hoist = WorksPaint.lerp(LOW, be.top + TOP, climb)
        state.spread = WorksPaint.lerp(LIMP, 1f, WorksPaint.smooth((u - 0.55f) / 0.45f))
    }

    /** A board's lettering, worked out again only when its name (or the language) changes. */
    private class Words(val key: String, val lines: List<FormattedCharSequence>, val widths: IntArray)

    private fun words(be: NameBoardBlockEntity, house: Boolean): Words {
        val key = "${be.name}|$house|${Language.getInstance().hashCode()}"
        (be.words as? Words)?.let { if (it.key == key) return it }
        val kind = Component.translatable(if (house) HOUSE else STORE)
        val texts = if (be.name.isEmpty()) listOf(kind) else listOf(Component.literal(be.name), kind)
        val lines = texts.map { it.visualOrderText }
        return Words(key, lines, IntArray(lines.size) { font.width(lines[it]) }).also { be.words = it }
    }

    override fun submit(state: NameBoardRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = state.lightCoords
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        // The board on its rings, swinging out toward the street and back
        val look = if (state.house) HOUSE_LOOK else STORE_LOOK
        poseStack.translate(look.x * WorksPaint.PX, look.y * WorksPaint.PX, look.z * WorksPaint.PX)
        poseStack.rotateDegrees(Axis.XP, state.swing)
        poseStack.pushPose()
        poseStack.scale(-1f, -1f, 1f)
        if (state.house) collector.submitModelPart(houseBoards[state.colour.coerceIn(0, houseBoards.size - 1)], poseStack, HOUSE_CUTOUT, light, OverlayTexture.NO_OVERLAY, null)
        else collector.submitModelPart(board, poseStack, CUTOUT, light, OverlayTexture.NO_OVERLAY, null)
        poseStack.popPose()
        // Its lettering, on the front (toward the street) and the back
        poseStack.scale(WorksPaint.PX, WorksPaint.PX, WorksPaint.PX)
        letterFaces(poseStack, collector, look, state.lines, state.widths, light)
        poseStack.popPose()
        if (state.pennant) pennant(state, poseStack, collector)
    }

    /** Where a board hangs and how it is lettered: its rings' line (px), its drop under them, its lines, how far out the letters stand, their ink and edge. */
    private class Look(val x: Float, val y: Float, val z: Float, val drop: Float, val lines: Array<FloatArray>, val textZ: Float, val ink: Int, val edge: Int)

    private fun letterFaces(poseStack: PoseStack, collector: SubmitNodeCollector, look: Look, lines: List<FormattedCharSequence>, widths: IntArray, light: Int) {
        for ((i, line) in lines.withIndex()) {
            val spec = look.lines.getOrNull(i) ?: break
            val width = widths[i].coerceAtLeast(1).toFloat()
            val s = min(spec[1] / CAP, spec[2] / width)
            val top = -look.drop - spec[0]
            for (back in listOf(false, true)) {
                poseStack.pushPose()
                poseStack.translate(0f, top, if (back) look.textZ else -look.textZ)
                if (!back) poseStack.rotateDegrees(Axis.YP, 180f)
                poseStack.scale(s, -s, s)
                collector.submitText(poseStack, -width / 2f + EDGE / s, EDGE / s, line, false, Font.DisplayMode.POLYGON_OFFSET, light, look.edge, 0, 0)
                poseStack.translate(0f, 0f, EDGE_FORWARD / s)
                collector.submitText(poseStack, -width / 2f, 0f, line, false, Font.DisplayMode.POLYGON_OFFSET, light, look.ink, 0, 0)
                poseStack.popPose()
            }
        }
    }

    /** The owner's pennant on its pole: the cloth in columns out from the pole, each a little further on in the ripple running down it. */
    private fun pennant(state: NameBoardRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val fx = state.flyX.toFloat()
        val fz = state.flyZ.toFloat()
        val sx = -fz
        val sz = fx
        val color = ARGB.color(255, state.rgb)
        collector.submitCustomGeometry(poseStack, CLOTH) { pose, buffer ->
            var lastOut = 0f
            var lastSide = 0f
            var lastTop = 0f
            var lastBottom = 0f
            var lastV = CLOTH_V0
            for (k in 0..COLUMNS) {
                val along = k.toFloat() / COLUMNS
                val out = POLE_R + LENGTH * along * WorksPaint.lerp(0.45f, 1f, state.spread)
                val ripple = sin(along * RIPPLE_WAVES - state.time * RIPPLE_SPEED + state.seed) * RIPPLE * along * (0.4f + 0.6f * state.spread)
                val sag = SAG * along * along * (1f - state.spread) + DROOP * along * along
                val high = HEIGHT * (1f - TAPER * along)
                val top = state.hoist - sag
                val bottom = top - high
                val v = CLOTH_V0 + (CLOTH_V1 - CLOTH_V0) * along
                if (k > 0) {
                    val shade = ARGB.scaleRGB(color, 0.86f + 0.14f * ((ripple - lastSide) / (out - lastOut).coerceAtLeast(1e-3f) * 4f).coerceIn(-1f, 1f))
                    put(buffer, pose, state, lastOut, lastSide, lastTop, CLOTH_U0, lastV, shade, fx, fz, sx, sz)
                    put(buffer, pose, state, out, ripple, top, CLOTH_U0, v, shade, fx, fz, sx, sz)
                    put(buffer, pose, state, out, ripple, bottom, CLOTH_U1, v, shade, fx, fz, sx, sz)
                    put(buffer, pose, state, lastOut, lastSide, lastBottom, CLOTH_U1, lastV, shade, fx, fz, sx, sz)
                }
                lastOut = out
                lastSide = ripple
                lastTop = top
                lastBottom = bottom
                lastV = v
            }
        }
    }

    private fun put(
        buffer: VertexConsumer, pose: PoseStack.Pose, s: NameBoardRenderState, out: Float, side: Float, y: Float, u: Float, v: Float, color: Int,
        fx: Float, fz: Float, sx: Float, sz: Float,
    ) {
        buffer.addVertex(pose, s.poleX + fx * out + sx * side, s.poleY + y, s.poleZ + fz * out + sz * side)
            .setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(s.poleLight).setNormal(pose, sx, 0f, sz)
    }

    // The crossbar reaches over its neighbours, and the pennant flies from a pole blocks away
    override fun getRenderBoundingBox(be: NameBoardBlockEntity): AABB {
        val box = AABB(be.blockPos).inflate(0.4, 0.1, 0.4)
        val pole = be.pole ?: return box
        val f = be.fly
        val flag = AABB(pole).expandTowards(0.0, be.top + 1.0, 0.0).expandTowards(f.stepX * (LENGTH + 0.4), 0.0, f.stepZ * (LENGTH + 0.4)).inflate(0.3)
        return box.minmax(flag)
    }

    override fun shouldRenderOffScreen(): Boolean = true

    companion object {
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(StoreArt.BOARDS_TEXTURE))
        private val HOUSE_CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(HouseArt.BOARDS_TEXTURE))

        private val STORE_LOOK = Look(
            StoreArt.BOARD_X, StoreArt.BOARD_Y, StoreArt.BOARD_Z, StoreArt.BOARD_DROP, StoreArt.NAME_LINES, StoreArt.NAME_TEXT_Z,
            (0xFF shl 24) or StoreArt.NAME_INK, (0xFF shl 24) or StoreArt.NAME_EDGE,
        )
        private val HOUSE_LOOK = Look(
            HouseArt.BOARD_X, HouseArt.BOARD_Y, HouseArt.BOARD_Z, HouseArt.BOARD_DROP, HouseArt.NAME_LINES, HouseArt.NAME_TEXT_Z,
            (0xFF shl 24) or HouseArt.NAME_INK, (0xFF shl 24) or HouseArt.NAME_EDGE,
        )

        /** The stalls' pennant cloth (art/scripts/stall_art.py): its cloth's share of the sheet, its length running out along the fly. */
        private val CLOTH: RenderType = RenderTypes.entityCutout(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/market/stall_pennant.png"))
        private const val CLOTH_U0 = 0f
        private const val CLOTH_U1 = 0.5f
        private const val CLOTH_V0 = 0f
        private const val CLOTH_V1 = 0.75f

        /** The board's second line, under its owner's name: a Store's, a House's. */
        const val STORE = "gui.guildmark.trade.store.board"
        const val HOUSE = "gui.guildmark.trade.house.board"

        /** A capital's height in font units; the dark edge's offset (px) under and right of each letter, and how far (px) the ink stands out before it. */
        private const val CAP = 7f
        private const val EDGE = 0.12f
        private const val EDGE_FORWARD = 0.006f

        /** The pennant (blocks): out from the pole's middle, its length and its height at the pole, narrowing to the tail. */
        private const val POLE_R = 0.14f
        private const val LENGTH = 1.15f
        private const val HEIGHT = 0.55f
        private const val TAPER = 0.3f

        /** Where it starts up the pole and how near the pole's top it ends; its pulls up, and the hitch at each. */
        private const val LOW = 1.4f
        private const val TOP = 0.9f
        private const val PULLS = 5f
        private const val HEAVE = 0.18f

        /** How far out it flies while it climbs, how far its tail sags then, and the droop it always has. */
        private const val LIMP = 0.35f
        private const val SAG = 0.45f
        private const val DROOP = 0.06f

        /** The cloth's columns, and the ripple running down it: how many waves along, how fast, how far (blocks). */
        private const val COLUMNS = 10
        private const val RIPPLE_WAVES = 7.5f
        private const val RIPPLE_SPEED = 0.32f
        private const val RIPPLE = 0.075f
    }
}

/** What the open and shut board needs drawn for one frame. */
class DoorBoardRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var turn = 0f
    var swing = 0f
}

/**
 * The open and shut board on its hook by the Store's door (art stream X3's `store_door_board`, [StoreArt.boardsMesh] "door_board"): it
 * turns round on its hook, a little past and back ([StoreArt.doorTurn]), when the clerk opens up in the morning (OPEN, a rising sun over
 * an open door) and when they bolt the door at dusk (SHUT, a moon over a barred one), and stirs on its hook in the air between.
 */
class DoorBoardRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<DoorBoardBlockEntity, DoorBoardRenderState> {
    private val board: ModelPart = context.bakeLayer(TradeBlocksClient.STORE_BOARDS).getChild("door_board")

    override fun createRenderState(): DoorBoardRenderState = DoorBoardRenderState()

    override fun extractRenderState(
        be: DoorBoardBlockEntity, state: DoorBoardRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        state.facing = WorksPaint.facing(be.blockState)
        val to = if (be.open) OPEN else SHUT
        val from = if (be.open) SHUT else OPEN
        state.turn = if (be.turned == 0L) to else StoreArt.doorTurn(WorksPaint.since(level.gameTime, be.turned, partialTicks), from, to)
        val time = WorksPaint.time(level.gameTime, partialTicks)
        state.swing = STIR * StoreArt.boardSwing(time, (Math.floorMod(be.blockPos.asLong(), 997L)) * 0.011f, level.isRainingAt(be.blockPos))
    }

    override fun submit(state: DoorBoardRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        poseStack.translate(StoreArt.DOOR_X * WorksPaint.PX, StoreArt.DOOR_Y * WorksPaint.PX, StoreArt.DOOR_Z * WorksPaint.PX)
        poseStack.rotateDegrees(Axis.YP, state.turn)
        poseStack.rotateDegrees(Axis.XP, state.swing)
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModelPart(board, poseStack, CUTOUT, state.lightCoords, OverlayTexture.NO_OVERLAY, null)
        poseStack.popPose()
    }

    override fun getRenderBoundingBox(be: DoorBoardBlockEntity): AABB = AABB(be.blockPos).inflate(0.2)

    companion object {
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(StoreArt.BOARDS_TEXTURE))
        private const val OPEN = 0f
        private const val SHUT = 180f

        /** How far it stirs on its hook, of the name board's swing. */
        private const val STIR = 0.35f
    }
}
