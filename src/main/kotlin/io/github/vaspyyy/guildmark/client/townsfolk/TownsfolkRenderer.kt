package io.github.vaspyyy.guildmark.client.townsfolk

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.client.ui.TalkClient
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Townsfolk, each in their role's look, holding what they work with. What they say shows over their heads
 * while they say it. Children are drawn small; hot iron and lanterns glow.
 */
class TownsfolkRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<Townsperson, TownsfolkRenderState, TownsfolkModel>(context, TownsfolkModel(context.bakeLayer(TownsfolkModel.LAYER)), 0.5f) {

    init {
        addLayer(GlowLayer(this))
        // ---- Living World (LivingGoodsLayer.kt): the goods in hand ----
        addLayer(LivingGoodsLayer(this))
        // ---- end Living World ----
    }

    override fun createRenderState(): TownsfolkRenderState = TownsfolkRenderState()
    override fun getTextureLocation(state: TownsfolkRenderState): Identifier = state.texture

    override fun extractRenderState(entity: Townsperson, state: TownsfolkRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        val role = entity.role
        val look = TownsfolkClient.lookOf(entity)
        state.texture = look?.let { texture(it.texture) } ?: FALLBACK
        state.pieces = look?.parts ?: emptySet()
        state.child = role == TownRole.CHILD
        state.held = Props.held(role, entity.shownActivity, entity.lantern, state.pieces)
        // The Lanternrise: the lantern leaves their hands at the let-go, as its sky lantern rises from there
        if (entity.shownActivity == Activity.RELEASE && entity.tickCount - entity.shownStart >= FestivalSite.RELEASE_TICK) state.held -= "lantern"
        state.glows = "hot_iron" in state.held || "lantern" in state.held
        state.frame = if (entity.isSleeping) null else frame(entity, partialTicks)
        // ---- Living World (LivingProps.kt): its clips' props and goods, climbing by height ----
        LivingProps.extract(entity, state, partialTicks)
        // ---- end Living World ----
    }

    override fun scale(state: TownsfolkRenderState, poseStack: PoseStack) {
        if (state.child) poseStack.scale(Townsperson.CHILD_SCALE, Townsperson.CHILD_SCALE, Townsperson.CHILD_SCALE)
    }

    override fun getShadowRadius(state: TownsfolkRenderState): Float =
        super.getShadowRadius(state) * if (state.child) Townsperson.CHILD_SCALE else 1f

    // What they're saying shows over their heads in place of a name
    override fun shouldShowName(entity: Townsperson, distance: Double): Boolean =
        speaking(entity) && distance < SPEECH_RANGE * SPEECH_RANGE || super.shouldShowName(entity, distance)

    override fun getNameTag(entity: Townsperson): Component? =
        if (speaking(entity)) tag(entity.speech) else super.getNameTag(entity)

    /** Whether what [entity] says shows over their head: they're saying something, and not into this player's talk screen, which shows it all. */
    private fun speaking(entity: Townsperson): Boolean = entity.speech.isNotEmpty() && !TalkClient.talkingWith(entity.id)

    /** The hot iron in a smith's tongs and the flame in a lantern, bright in the dark. */
    private class GlowLayer(parent: RenderLayerParent<TownsfolkRenderState, TownsfolkModel>) : RenderLayer<TownsfolkRenderState, TownsfolkModel>(parent) {
        override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: TownsfolkRenderState, yRot: Float, xRot: Float) {
            if (!state.glows || state.isInvisible) return
            collector.order(1).submitModel(parentModel, state, poseStack, RenderTypes.eyes(GLOW), light, OverlayTexture.NO_OVERLAY, state.outlineColor)
        }
    }

    companion object {
        val FALLBACK: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/townsfolk/citizen_0.png")
        private val GLOW: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/townsfolk/townsfolk_glow.png")

        /** Blocks off a line can be read from. */
        private const val SPEECH_RANGE = 20.0

        /**
         * The most letters a line and its detail show over someone's head; a cut is made at a word if there's one in
         * the last [WORD] letters, and a detail left fewer than [LEAST_DETAIL] letters shows as an ellipsis alone.
         */
        private const val TAG_LETTERS = 48
        private const val WORD = 12
        private const val LEAST_DETAIL = 6

        /**
         * What [line] says over their head: the line, then its detail in grey, the whole cut at [TAG_LETTERS] letters
         * with an ellipsis. A line with no detail shows as it is (the talk screen shows a detail in full).
         */
        fun tag(line: String): Component {
            val detail = Speech.detail(line) ?: return Speech.said(line)
            val said = Speech.said(line).string
            val more = detail.string
            if (said.length + 1 + more.length <= TAG_LETTERS) return Speech.text(line)
            val room = TAG_LETTERS - said.length - 1
            return when {
                room >= LEAST_DETAIL -> Component.literal("$said ").append(Component.literal(cut(more, room)).withStyle(ChatFormatting.GRAY))
                said.length + 2 <= TAG_LETTERS -> Component.literal("$said ").append(Component.literal(ELLIPSIS).withStyle(ChatFormatting.GRAY))
                else -> Component.literal(cut(said, TAG_LETTERS))
            }
        }

        /** [text] cut to at most [most] letters, the ellipsis last: at the last word's end if one is near, else mid-word. */
        private fun cut(text: String, most: Int): String {
            if (text.length <= most) return text
            val end = most - ELLIPSIS.length
            val space = text.lastIndexOf(' ', end)
            val at = if (space > 0 && space >= end - WORD) space else end
            return text.substring(0, at).trimEnd(' ', ',', ';', ':', '.', '-') + ELLIPSIS
        }

        private const val ELLIPSIS = "…"

        /** Ticks to ease from one activity's clip into the next. */
        private const val FADE = 5f

        private val textures = HashMap<String, Identifier>()

        private fun texture(name: String): Identifier =
            textures.getOrPut(name) { Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/townsfolk/$name.png") }

        /** The clip [entity] is seen doing, easing in over whatever it cut short. */
        private fun frame(entity: Townsperson, partial: Float): CombatAnimator.Frame? {
            val now = entity.tickCount + partial
            val activity = entity.shownActivity
            val clip = clip(entity, activity) ?: return null
            val time = time(activity, clip, now - entity.shownStart)
            val fade = ((now - entity.changedAt) / FADE).coerceIn(0f, 1f)
            if (fade >= 1f) return CombatAnimator.Frame(clip, time, 1f, 0f, 0f, 0f, null)
            val last = entity.lastActivity
            val from = clip(entity, last)?.let { CombatAnimator.Frame(it, time(last, it, now - entity.lastStart), 1f, 0f, 0f, 0f, null) }
            return CombatAnimator.Frame(clip, time, fade * fade * (3f - 2f * fade), 0f, 0f, 0f, from)
        }

        /** Where in [clip] they are, [elapsed] ticks after starting it: loops go round, one-shots hold their last pose. */
        private fun time(activity: Activity, clip: Clip, elapsed: Float): Float =
            if (activity.loop) ((elapsed % clip.length) + clip.length) % clip.length else elapsed.coerceIn(0f, clip.length)

        private fun clip(entity: Townsperson, activity: Activity): Clip? = Clips.get("townsfolk/" + TownsfolkClient.clipName(entity, activity))
    }
}
