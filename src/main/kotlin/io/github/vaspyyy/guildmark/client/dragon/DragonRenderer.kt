package io.github.vaspyyy.guildmark.client.dragon

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.dragon.Dragon
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB

/**
 * A dragon, drawn at twice its model's size. Its eyes burn gold; its throat and the seams of its chest light
 * up as it draws breath for fire; and once it's enraged every molten seam over it glows. On the wing it leans
 * into its flight, nose down as it drops and banked into its turns.
 */
class DragonRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<Dragon, DragonRenderState, DragonModel>(context, DragonModel(context.bakeLayer(DragonModel.LAYER)), 3.0f) {

    init {
        addLayer(object : RenderLayer<DragonRenderState, DragonModel>(this) {
            override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: DragonRenderState, yRot: Float, xRot: Float) {
                if (state.eyes <= 0f) return
                // Each glow over the last as it comes on, so the eyes burn as brightly through them all
                val calm = (1f - state.rage) * (1f - state.breathGlow)
                glow(poseStack, collector, light, state, GLOW, calm)
                glow(poseStack, collector, light, state, GLOW_BREATH, (1f - state.rage) * state.breathGlow)
                glow(poseStack, collector, light, state, GLOW_2, state.rage)
            }

            private fun glow(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: DragonRenderState, texture: Identifier, strength: Float) {
                val a = strength * state.eyes
                if (a < 0.01f) return
                collector.order(1).submitModel(
                    parentModel, state, poseStack, RenderTypes.eyes(texture), light, OverlayTexture.NO_OVERLAY, ARGB.white(a), null, state.outlineColor,
                )
            }
        })
    }

    override fun createRenderState(): DragonRenderState = DragonRenderState()
    override fun getTextureLocation(state: DragonRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: Dragon, state: DragonRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.stride = Mth.lerp(partialTicks, entity.strideO, entity.stride)
        state.gaitSpeed = Mth.lerp(partialTicks, entity.gaitSpeedO, entity.gaitSpeed)
        state.airBlend = Mth.lerp(partialTicks, entity.airBlendO, entity.airBlend)
        state.airPhase = entity.airPhase.toFloat()
        val (hover, flap, glide) = entity.flightWeights()
        state.hover = hover
        state.flap = flap
        state.glide = glide
        state.sleepBlend = Mth.lerp(partialTicks, entity.sleepBlendO, entity.sleepBlend)
        state.flightPitch = Mth.lerp(partialTicks, entity.flightPitchO, entity.flightPitch)
        state.flightRoll = Mth.lerp(partialTicks, entity.flightRollO, entity.flightRoll)
        state.hasRedOverlay = entity.hurtTime > 0 && entity.deathTime <= 0
        state.dying = if (entity.deathTime > 0) entity.deathTime + partialTicks else -1f
        // The attack it's seen doing, over the one it cut short as that fades
        state.cut = null
        state.attack = null
        state.attackWeight = 0f
        state.cutWeight = 0f
        entity.cutShortAttack(partialTicks)?.let { (attack, time, fade) ->
            val clip = Clips.get(clipName(attack)) ?: return@let
            state.cut = clip
            state.cutTime = time
            state.cutWeight = clip.weight(time) * fade
        }
        val shown = entity.shownAttack(partialTicks)
        shown?.let { (attack, time) ->
            val clip = Clips.get(clipName(attack)) ?: return@let
            state.attack = clip
            state.attackTime = time
            state.attackWeight = clip.weight(time)
        }
        // Reeling, for as long as its stance stays broken
        state.stagger = null
        state.staggerWeight = 0f
        CombatClient.staggerOf(entity, partialTicks)?.let { (into, total) ->
            val clip = Clips.get(DragonModel.STAGGER) ?: return@let
            val t = into / total.coerceAtLeast(1) * clip.length
            state.stagger = clip
            state.staggerTime = t
            state.staggerWeight = clip.weight(t)
        }

        // Its head follows its fire's aim as it breathes on the ground
        val attack = shown?.first
        val t = shown?.second ?: 0f
        state.aimDown = if (attack === Dragon.BREATH) {
            (entity.aimPitch - Dragon.BREATH_HEAD_PITCH) * rise(t, AIM_FROM, Dragon.BREATH_FROM.toFloat()) * (1f - rise(t, Dragon.BREATH_TO.toFloat(), Dragon.BREATH_TO + 8f)) * state.attackWeight
        } else 0f

        // Its eyes: shut in sleep and for the first moments of waking, dimming and shutting as it dies
        state.lidsShut = (shown == null && state.sleepBlend > 0.5f) || (attack === Dragon.WAKE && t < WAKE_EYES) || state.dying > DEAD_EYES
        state.eyes = when {
            state.lidsShut -> 0f
            state.dying >= 0f -> 1f - rise(state.dying, DIM_EYES, DEAD_EYES)
            else -> 1f
        }
        state.breathGlow = if (attack != null) breathGlow(attack, t) else 0f
        state.rage = when {
            !entity.enraged -> 0f
            attack === Dragon.ENRAGE -> rise(t, 29f, 35f)
            else -> 1f
        }
    }

    /** How brightly its throat glows [t] ticks into [attack]: building as it draws breath, out as the fire stops. */
    private fun breathGlow(attack: EnemyAttack, t: Float): Float = when (attack) {
        Dragon.BREATH -> rise(t, 2f, Dragon.BREATH_FROM - 4f) * (1f - rise(t, Dragon.BREATH_TO.toFloat(), Dragon.BREATH_TO + 8f))
        Dragon.AIR_BREATH -> rise(t, 0f, Dragon.AIR_BREATH_FROM - 4f) * (1f - rise(t, Dragon.AIR_BREATH_TO.toFloat(), Dragon.AIR_BREATH_TO + 6f))
        Dragon.FIREBALL -> rise(t, 4f, 16f) * (1f - rise(t, 19f, 26f))
        Dragon.FIREBALL_VOLLEY -> rise(t, 4f, 14f) * (1f - rise(t, 37f, 44f))
        else -> 0f
    }

    private fun rise(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun clipName(attack: EnemyAttack): String = clipNames.getOrPut(attack) { Dragon.ANIMATIONS + "/" + attack.anim }

    // Nose down as it drops and banked into its turns, about the middle of its body
    override fun setupRotations(state: DragonRenderState, poseStack: PoseStack, bodyRot: Float, entityScale: Float) {
        super.setupRotations(state, poseStack, bodyRot, entityScale)
        if (state.flightPitch == 0f && state.flightRoll == 0f) return
        poseStack.translate(0f, PIVOT, 0f)
        poseStack.rotateDegrees(Axis.XP, -state.flightPitch)
        poseStack.rotateDegrees(Axis.ZP, -state.flightRoll)
        poseStack.translate(0f, -PIVOT, 0f)
    }

    // Its wings span some sixteen blocks and its neck and tail reach far past its body
    override fun getBoundingBoxForCulling(entity: Dragon, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(entity, partialTicks).inflate(9.0, 5.0, 9.0)

    // It has its own fall in its death clip
    override fun getFlipDegrees(): Float = 0f

    override fun scale(state: DragonRenderState, poseStack: PoseStack) {
        poseStack.scale(SCALE, SCALE, SCALE)
    }

    companion object {
        const val SCALE = 2.0f
        /** Blocks above its feet it leans about in flight: the middle of its body. */
        private const val PIVOT = 3.0f
        /** The tick the ground breath's clip starts to lower its head onto its aim. */
        private const val AIM_FROM = 22f
        /** Its eyes open this many ticks into waking. */
        private const val WAKE_EYES = 4f
        /** Its eyes dim from this tick of its death, and shut on this one. */
        private const val DIM_EYES = 68f
        private const val DEAD_EYES = 86f
        private val clipNames = HashMap<EnemyAttack, String>()
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/dragon.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/dragon_glow.png")
        private val GLOW_BREATH = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/dragon_glow_breath.png")
        private val GLOW_2 = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/dragon_glow2.png")
    }
}
