package io.github.vaspyyy.guildmark.client.streets

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.works.WorksArt
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTableBlock
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTableBlockEntity
import net.minecraft.client.model.geom.ModelLayerLocation
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
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/** What the alms table's pot, stew, bowls and ladle need for one frame. */
class AlmsTableRenderState : BlockEntityRenderState() {
    var front: Direction = Direction.NORTH
    var pot = false
    /** px the pot (and all in it) is still above the table as it's set down. */
    var potDrop = 0f
    /** How full the pot is, 0 to 1, as it's seen (easing to the soup that's in it). */
    var fill = 0f
    /** The bowls stacked on the table (three at most), and px the top one is still above its place as it's set down. */
    var bowls = 0
    var bowlDrop = 0f
    /** Game time with the frame's fraction (wrapped every hour, for the simmer). */
    var time = 0f
    /** Ticks since the keeper started ladling, and since a gift was tipped in. */
    var ladling = 1e9f
    var given = 1e9f
}

/**
 * The alms table's pot and bowls (M6 Part D2, townsfolk/streets/AlmsTable), drawn over its middle block from Part F's
 * art (client/works: WorksMesh.alms, placed by WorksArt's alms numbers in the trestle's north-facing model space and
 * turned as its blockstate turns it): the pot on the keeper's right, set down onto the table as it comes; the stew in
 * it as high as there's soup (rising as a gift is tipped in, going down as it's ladled out), simmering; the ladle
 * standing in it but while the keeper holds it (LADLE: they lift the top bowl off the stack as they take it up, and
 * it rings out from where it dips); a gift's ring and splash; and the bowls stacked on the keeper's left, each set
 * down as it comes. The steam is the block's own (AlmsTableBlock.animateTick); the pot's simmer is heard near it
 * ([StreetsClient]), which also keeps the moments this times things from.
 */
class AlmsTableRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<AlmsTableBlockEntity, AlmsTableRenderState> {
    private val root: ModelPart = context.bakeLayer(LAYER)
    private val pot = root.getChild("pot")
    private val stew = Array(WorksArt.STEW_FRAMES) { root.getChild("stew_$it") }
    private val ripple = root.getChild("ripple")
    private val drop = root.getChild("drop")
    private val bowl = root.getChild("bowl")
    private val bowlUnder = root.getChild("bowl_under")
    private val ladle = root.getChild("ladle")

    override fun createRenderState(): AlmsTableRenderState = AlmsTableRenderState()

    override fun extractRenderState(
        be: AlmsTableBlockEntity, state: AlmsTableRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        val now = level.gameTime
        state.front = be.blockState.getValue(AlmsTableBlock.FACING)
        state.pot = be.pot
        state.potDrop = if (be.pot) WorksArt.drop(since(now, be.potAt, partialTicks)) else 0f
        val shown = if (be.shown < 0f) be.soup.toFloat() else be.shown
        state.fill = (shown / be.cap.coerceAtLeast(1)).coerceIn(0f, 1f)
        state.time = (now % HOUR).toFloat() + partialTicks
        state.ladling = since(now, be.ladleAt, partialTicks)
        state.given = since(now, be.giftAt, partialTicks)
        // The stack: a bowl short from the keeper's take to the end of the move, till the server's count has it
        var n = min(be.bowls, WorksArt.BOWLS_MAX)
        if (state.ladling >= WorksArt.LADLE_TAKE && state.ladling < WorksArt.LADLE_LEN) n = min(n, be.bowlsAtLadle - 1)
        state.bowls = n.coerceAtLeast(0)
        // The top one set down: just set out, or the stack made up again as the move ends
        var set = since(now, be.bowlAt, partialTicks)
        val after = state.ladling - WorksArt.LADLE_LEN
        if (after >= 0f && be.bowls >= be.bowlsAtLadle) set = min(set, after)
        state.bowlDrop = WorksArt.drop(set)
    }

    override fun submit(state: AlmsTableRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = state.lightCoords
        poseStack.pushPose()
        // The trestle's model space, turned about the block's middle as its blockstate turns it
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, 180f - state.front.toYRot())
        poseStack.translate(-0.5f, 0f, -0.5f)
        if (state.pot) {
            val down = state.potDrop
            draw(pot, WorksArt.POT_X, WorksArt.ALMS_TOP + down, WorksArt.POT_Z, poseStack, collector, light)
            // The ladle stands in it, but while the keeper holds it (theirs is the move's own)
            if (state.ladling >= WorksArt.LADLE_LEN) {
                val rest = WorksArt.LADLE_REST
                draw(ladle, rest[0], rest[1] + down, rest[2], poseStack, collector, light)
            }
            if (state.fill > 0.002f) {
                val top = WorksArt.stewY(state.fill) + WorksArt.stewBob(state.time) + down
                draw(stew[WorksArt.stewFrame(state.time)], WorksArt.POT_X, top, WorksArt.POT_Z, poseStack, collector, light)
                // Where the ladle dips, ringing out
                val dip = state.ladling - WorksArt.LADLE_DIP
                if (dip >= 0f && dip < WorksArt.RIPPLE_TICKS) {
                    draw(ripple, WorksArt.DIP_X, top + LIFT, WorksArt.DIP_Z, poseStack, collector, light, WorksArt.rippleScale(dip))
                }
                // A gift tipped in: its ring from the middle, and its splash
                if (state.given < WorksArt.SPLASH_TICKS) {
                    draw(ripple, WorksArt.POT_X, top + 2 * LIFT, WorksArt.POT_Z, poseStack, collector, light, WorksArt.splashScale(state.given))
                    for (j in 0 until WorksArt.SPLASH_DROPS) {
                        val d = WorksArt.splashDrop(j, state.given) ?: continue
                        draw(drop, WorksArt.POT_X + d[0], top + d[1], WorksArt.POT_Z + d[2], poseStack, collector, light)
                    }
                }
            }
        }
        // The stack on the keeper's left, the top one set down as it comes
        for (i in 0 until state.bowls) {
            val top = i == state.bowls - 1
            val y = WorksArt.bowlY(i) + if (top) state.bowlDrop else 0f
            draw(if (top) bowl else bowlUnder, WorksArt.BOWLS_X, y, WorksArt.BOWLS_Z, poseStack, collector, light)
        }
        poseStack.popPose()
    }

    /** [part] at the model point (x, y, z) px, spread [spread] times across (a ring). */
    private fun draw(part: ModelPart, x: Float, y: Float, z: Float, poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, spread: Float = 1f) {
        poseStack.pushPose()
        poseStack.translate(x / 16f, y / 16f, z / 16f)
        if (spread != 1f) poseStack.scale(spread, 1f, spread)
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModelPart(part, poseStack, TYPE, light, OverlayTexture.NO_OVERLAY, null)
        poseStack.popPose()
    }

    // The pot and its stew stand over the block (its rim at 18 px)
    override fun getRenderBoundingBox(be: AlmsTableBlockEntity): AABB = AABB(be.blockPos).expandTowards(0.0, 0.4, 0.0)

    companion object {
        /** WorksMesh.alms's layer (registered by [StreetsClient]). */
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "alms_table"), "main")

        private val TYPE: RenderType = RenderTypes.entityCutout(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, WorksArt.ALMS_TEXTURE))

        /** px a ring sits over the stew, so the two don't fight. */
        private const val LIFT = 0.02f

        private const val HOUR = 72000L

        /** Ticks (with the frame's fraction) since [at], by this client's clock. */
        private fun since(now: Long, at: Long, partialTicks: Float): Float = (now - at).coerceIn(0L, 1_000_000L) + partialTicks
    }
}
