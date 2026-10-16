package io.github.vaspyyy.guildmark.client.sea

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.sea.Maelgrasp
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB

/**
 * The Maelgrasp's head, where it lies: circling far down under the ship as the omens come, then up out of the sea beside
 * her, facing her. Everything it does with its face comes from a few stamps the server sends (when it last bit, spewed
 * ink, roared or was struck in the eye) and the eased look and rise each client keeps, turned here into the eye's turn,
 * its pupil and lids, its beak, its lunge and its siphon for [MaelgraspModel] to pose. The veins under its skin and the
 * ring round its eye burn on a glow layer: faint lights circling in the deep under her keel before it comes up.
 */
class MaelgraspRenderer(context: EntityRendererProvider.Context) :
    LivingEntityRenderer<Maelgrasp, MaelgraspRenderState, MaelgraspModel>(context, MaelgraspModel(context.bakeLayer(MaelgraspModel.LAYER)), 0f) {

    init {
        addLayer(object : RenderLayer<MaelgraspRenderState, MaelgraspModel>(this) {
            override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: MaelgraspRenderState, yRot: Float, xRot: Float) {
                val a = (state.glow * (1f + RAGE_GLOW * state.rage)).coerceIn(0f, 1f)
                if (a < 0.01f) return
                collector.order(1).submitModel(
                    parentModel, state, poseStack, RenderTypes.eyes(GLOW), light, OverlayTexture.NO_OVERLAY, ARGB.white(a), null, state.outlineColor,
                )
            }
        })
    }

    override fun createRenderState(): MaelgraspRenderState = MaelgraspRenderState()
    override fun getTextureLocation(state: MaelgraspRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: Maelgrasp, state: MaelgraspRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        // It faces just where the server has it face (its eye and beak are found from that), not a body turning after it
        state.bodyRot = entity.getYRot(partialTicks)
        state.yRot = 0f
        state.xRot = 0f
        state.time = entity.tickCount + partialTicks
        state.rise = Mth.lerp(partialTicks, entity.riseO, entity.riseN)
        state.lookYaw = Mth.lerp(partialTicks, entity.lookYawO, entity.lookYawN)
        state.lookPitch = Mth.lerp(partialTicks, entity.lookPitchO, entity.lookPitchN)
        state.rage = entity.rage
        state.dying = if (entity.deathTime > 0) ((entity.deathTime + partialTicks) / Maelgrasp.DEATH_TICKS).coerceIn(0f, 1f) else 0f
        state.hasRedOverlay = entity.hurtTime > 0 && entity.deathTime <= 0

        val bite = entity.since(entity.biteAt, partialTicks)
        val roar = entity.since(entity.roarAt, partialTicks)
        val ink = entity.since(entity.inkAt, partialTicks)
        val flinch = entity.since(entity.flinchAt, partialTicks)

        // Its beak: gaping as it rears to bite and slamming shut as it strikes, or opened wide on a roar
        val (biteBeak, lunge) = bite(bite)
        state.beak = maxOf(biteBeak, roarBeak(roar))
        state.lunge = maxOf(lunge, if (roar in 0f..ROAR_TICKS) ROAR_LUNGE * hump(roar, 4f, 26f, ROAR_TICKS) else 0f)
        // Its siphon flaring and pulsing as it spews
        state.ink = if (ink in 0f..INK_FADE) {
            when {
                ink < Maelgrasp.INK_SPRAY_FROM -> ink / Maelgrasp.INK_SPRAY_FROM
                ink < Maelgrasp.INK_SPRAY_TO -> 1f
                else -> 1f - (ink - Maelgrasp.INK_SPRAY_TO) / (INK_FADE - Maelgrasp.INK_SPRAY_TO)
            }.coerceIn(0f, 1f)
        } else 0f

        // Its lids: blinking now and then, screwed shut when it's struck in the eye, half shut and clouding as it dies
        val blink = entity.tickCount - entity.blinkAt + partialTicks
        var lid = when {
            blink < 0f -> 0f
            blink < BLINK_SHUT -> blink / BLINK_SHUT
            blink < BLINK_SHUT + BLINK_HOLD -> 1f
            blink < BLINK_SHUT + BLINK_HOLD + BLINK_OPEN -> 1f - (blink - BLINK_SHUT - BLINK_HOLD) / BLINK_OPEN
            else -> 0f
        }
        if (flinch in 0f..FLINCH_TICKS) lid = maxOf(lid, if (flinch < FLINCH_HOLD) 1f else 1f - 0.6f * (flinch - FLINCH_HOLD) / (FLINCH_TICKS - FLINCH_HOLD))
        if (entity.deathTime <= 0 && state.rise < 0.05f) lid = maxOf(lid, 0.15f)
        state.lid = lid.coerceIn(0f, 1f)

        // Its pupil: a narrow bar by day, opened wide in the dark and as it strikes, pinched shut when struck
        val level = entity.level()
        var pupil = if (level.isDarkOutside) NIGHT_PUPIL else DAY_PUPIL
        pupil -= RAGE_PINCH * state.rage
        if (bite in 0f..Maelgrasp.BITE_TICKS.toFloat()) pupil = Mth.lerp(hump(bite, 2f, 14f, Maelgrasp.BITE_TICKS.toFloat()), pupil, 1f)
        if (flinch in 0f..FLINCH_TICKS) pupil = Mth.lerp(1f - flinch / FLINCH_TICKS, pupil, 0.05f)
        state.pupil = pupil.coerceIn(0f, 1f)

        // Its veins and the ring round its eye: faint by day, bright by night, guttering out as it dies
        var glow = if (level.isDarkOutside) NIGHT_GLOW else DAY_GLOW
        if (entity.phase == Maelgrasp.Phase.OMEN) glow *= OMEN_GLOW
        state.glow = (glow * (1f - (state.dying / GLOW_DIES).coerceIn(0f, 1f))).coerceIn(0f, 1f)
    }

    /** Its beak and lunge [t] ticks into a bite: rearing back with its beak wide, the lunge, the crunch, and back. */
    private fun bite(t: Float): Pair<Float, Float> {
        if (t < 0f || t > Maelgrasp.BITE_TICKS) return Pair(0f, 0f)
        val land = Maelgrasp.BITE_LAND.toFloat()
        val beak = when {
            t < BITE_OPEN -> ease(t / BITE_OPEN)
            t < land - 2f -> 1f
            t < land -> 1f - (t - (land - 2f)) / 2f
            t < land + 6f -> 0.15f * hump(t, land, land + 2f, land + 6f)
            else -> 0f
        }
        val lunge = when {
            t < BITE_OPEN -> 0f
            t < land -> ease((t - BITE_OPEN) / (land - BITE_OPEN)).let { it * it }
            else -> 1f - ease((t - land) / (Maelgrasp.BITE_TICKS - land))
        }
        return Pair(beak, lunge)
    }

    private fun roarBeak(t: Float): Float = if (t in 0f..ROAR_TICKS) hump(t, 6f, 30f, ROAR_TICKS) else 0f

    // Its origin is its waterline under its middle: undo the lift every model gets to stand its feet on the ground
    override fun scale(state: MaelgraspRenderState, poseStack: PoseStack) {
        poseStack.translate(0f, MODEL_LIFT, 0f)
    }

    // Slain, it sinks (the server lowers it); it doesn't topple
    override fun getFlipDegrees(): Float = 0f

    // Its mantle and the short arms round its beak reach past its box
    override fun getBoundingBoxForCulling(entity: Maelgrasp, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(entity, partialTicks).inflate(3.0, 4.5, 3.0)

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/sea/maelgrasp.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/sea/maelgrasp_glow.png")

        /** What LivingEntityRenderer lifts every model by, so its feet (24 px down) stand on the ground. */
        private const val MODEL_LIFT = 1.501f
        /** The ticks of its blink: shutting, shut, opening. */
        private const val BLINK_SHUT = 2.5f
        private const val BLINK_HOLD = 2f
        private const val BLINK_OPEN = 4.5f
        /** Struck in the eye: ticks its lids are screwed shut, then come half open again by. */
        private const val FLINCH_HOLD = 10f
        private const val FLINCH_TICKS = 24f
        /** The beak's opening as it rears to bite; a roar's length and how far forward it heaves its head. */
        private const val BITE_OPEN = 8f
        private const val ROAR_TICKS = 42f
        private const val ROAR_LUNGE = 0.3f
        /** The siphon's last pulses after the spray stops. */
        private const val INK_FADE = 32f
        /** Its pupil by day and by night, and how much its fury pinches it. */
        private const val DAY_PUPIL = 0.3f
        private const val NIGHT_PUPIL = 0.8f
        private const val RAGE_PINCH = 0.15f
        /** How brightly its veins burn by day and by night, under the ship before it comes up, and in its fury. */
        private const val DAY_GLOW = 0.45f
        private const val NIGHT_GLOW = 0.9f
        private const val OMEN_GLOW = 0.7f
        private const val RAGE_GLOW = 0.4f
        /** How far into its dying the light has gone out of it. */
        private const val GLOW_DIES = 0.6f

        private fun ease(u: Float): Float {
            val k = u.coerceIn(0f, 1f)
            return k * k * (3f - 2f * k)
        }

        /** 0 to 1 and back: up from [from] to [peak], down again by [to]. */
        private fun hump(t: Float, from: Float, peak: Float, to: Float): Float = when {
            t <= from || t >= to -> 0f
            t < peak -> ease((t - from) / (peak - from))
            else -> 1f - ease((t - peak) / (to - peak))
        }
    }
}
