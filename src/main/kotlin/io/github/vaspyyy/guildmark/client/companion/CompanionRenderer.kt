package io.github.vaspyyy.guildmark.client.companion

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.Clip
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkLooks
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkModel
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderState
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderer
import io.github.vaspyyy.guildmark.companion.CompanionEntity
import io.github.vaspyyy.guildmark.companion.Effect
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.HumanoidArm
import java.util.WeakHashMap

/**
 * A companion, in their own look of the townsfolk model, with the real weapon in hand. Everything they do plays a
 * clip: their fighting moves are the player's own (the greatsword's combos, the censer's swing, the dagger, the
 * staff), and the rest are theirs (art/scripts/companion_anims.py): sitting by a fire and getting up, going down and
 * being helped up, a nod or a frown at what the player did, the bow's release over vanilla's draw, and each one's way
 * of standing about. One eases into the next. A short line shows over their head while they say it.
 */
class CompanionRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<CompanionEntity, TownsfolkRenderState, TownsfolkModel>(context, TownsfolkModel(context.bakeLayer(TownsfolkModel.LAYER)), 0.5f) {

    override fun createRenderState(): TownsfolkRenderState = TownsfolkRenderState()
    override fun getTextureLocation(state: TownsfolkRenderState): Identifier = state.texture

    override fun extractRenderState(entity: CompanionEntity, state: TownsfolkRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        val look = TownsfolkLooks.BY_ROLE["companion"]?.getOrNull(entity.kind.ordinal)
        state.texture = look?.let { texture(it.texture) } ?: TownsfolkRenderer.FALLBACK
        // Kestrel's knife comes out of its sheath into her hand
        val pieces = look?.parts ?: emptySet()
        state.pieces = if (entity.mainHandItem.`is`(ModItems.IRON_DAGGER.get())) pieces - "knife" else pieces
        state.child = false
        state.glows = false
        val now = entity.tickCount + partialTicks
        val playing = choose(entity, now, state.walkAnimationSpeed > 0.08f)
        state.frame = frame(entity, playing, now)
        // Lucan reads while he waits
        state.held = if (playing?.name == "companion/idle_lucan") BOOK else emptySet()
        state.swingAnimation = 0f
    }

    override fun getArmPose(mob: CompanionEntity, arm: HumanoidArm): HumanoidModel.ArmPose {
        // The bow drawn, and held up through the release (whose clip adds to this pose)
        if (arm == mob.mainArm && (mob.isDrawing || mob.shownAct?.effect == Effect.ARROW && mob.tickCount - mob.shownActStart < (mob.shownAct?.length ?: 0))) {
            return HumanoidModel.ArmPose.BOW_AND_ARROW
        }
        return super.getArmPose(mob, arm)
    }

    // A short line shows over their head while they say it; anything longer is on their player's HUD
    override fun shouldShowName(entity: CompanionEntity, distance: Double): Boolean =
        overhead(entity) != null && distance < SPEECH_RANGE * SPEECH_RANGE || super.shouldShowName(entity, distance)

    override fun getNameTag(entity: CompanionEntity): Component? = overhead(entity) ?: super.getNameTag(entity)

    private fun overhead(entity: CompanionEntity): Component? {
        val key = entity.speech
        if (key.isEmpty()) return null
        val lang = "character.guildmark.${entity.kind.id}.$key"
        if (!Language.getInstance().has(lang)) return null
        return Component.translatable(lang).takeIf { it.string.length <= OVERHEAD_LETTERS }
    }

    /** A clip being played: [start] is when it began (in the entity's ticks); [hold] the stretch a once-through clip loops at its end. */
    private class Playing(val name: String, val start: Float, val loop: Boolean, val ownBlend: Boolean = false, val hold: Float = -1f) {
        fun same(other: Playing?): Boolean = other != null && other.name == name && other.start == start
    }

    /** What each companion was last seen playing, and what before it, to ease from one into the next. */
    private class Shown {
        var now: Playing? = null
        var last: Playing? = null
        var lastFade = 1f
        var changedAt = -100f
    }

    private val shown = WeakHashMap<CompanionEntity, Shown>()

    /** The clip [entity] is in now, by what matters most: down, getting up, sitting, a move, a gesture, talking, idling. */
    private fun choose(entity: CompanionEntity, now: Float, moving: Boolean): Playing? {
        val gesture = entity.shownGesture
        val gestureAge = now - entity.shownGestureStart
        val gesturing = gesture != CompanionEntity.Gesture.NONE && gestureAge < gesture.length
        if (entity.isDowned) return Playing("companion/downed", entity.downedSince.toFloat(), loop = false, hold = DOWNED_HOLD)
        if (gesturing && (gesture == CompanionEntity.Gesture.STAND_UP || gesture == CompanionEntity.Gesture.REVIVE)) {
            return Playing(gesture.clip, entity.shownGestureStart.toFloat(), loop = false)
        }
        if (entity.isSitting) return Playing("companion/sit_fire", entity.sittingSince.toFloat(), loop = true)
        val act = entity.shownAct
        if (act != null && now - entity.shownActStart < act.length) {
            val from = entity.shownActStart + act.clipFrom.toFloat()
            // Kestrel's draw is vanilla's bow pose; the release is a clip of its own
            if (now >= from) return Playing(act.clip, from, loop = false, ownBlend = true)
            return null
        }
        if (gesturing) return Playing(gesture.clip, entity.shownGestureStart.toFloat(), loop = false)
        if (entity.isTalking) return Playing("townsfolk/talk", 0f, loop = true)
        if (entity.isDrawing || moving) return null
        return Playing("companion/idle_${entity.kind.id}", 0f, loop = true)
    }

    private fun frame(entity: CompanionEntity, playing: Playing?, now: Float): CombatAnimator.Frame? {
        val seen = shown.getOrPut(entity) { Shown() }
        val changed = if (playing == null) seen.now != null else !playing.same(seen.now)
        if (changed) {
            // Whatever was showing eases out from where it had got to
            seen.lastFade = fadeOf(seen, now)
            seen.last = seen.now
            seen.now = playing
            seen.changedAt = now
        }
        val fade = fadeOf(seen, now)
        val from = seen.last?.takeIf { fade < 1f }?.let { last -> single(last, now, seen.lastFade) }
        if (playing == null) {
            // Back to plain standing and walking, out of the last clip
            return from?.let { CombatAnimator.Frame(it.clip, it.time, it.weight * (1f - fade), 0f, 0f, 0f, null) }
        }
        val clip = Clips.get(playing.name) ?: return from
        val time = timeIn(playing, clip, now)
        val weight = (if (playing.ownBlend) clip.weight(time) else 1f) * (if (playing.ownBlend) 1f else smooth(fade))
        return CombatAnimator.Frame(clip, time, weight, 0f, 0f, 0f, from)
    }

    /** How far into its fade the newest clip is. */
    private fun fadeOf(seen: Shown, now: Float): Float {
        val incoming = seen.now?.let { Clips.get(it.name) }
        val length = maxOf(FADE, incoming?.blendIn ?: 0f)
        return ((now - seen.changedAt) / length).coerceIn(0f, 1f)
    }

    private fun single(playing: Playing, now: Float, weight: Float): CombatAnimator.Frame? {
        val clip = Clips.get(playing.name) ?: return null
        val time = timeIn(playing, clip, now)
        val w = if (playing.ownBlend) clip.weight(time) else 1f
        return CombatAnimator.Frame(clip, time, w * maxOf(weight, 0.0001f).coerceAtMost(1f), 0f, 0f, 0f, null)
    }

    /** Where in [clip] [playing] is: loops go round, a held clip loops its last stretch, others stop on their last pose. */
    private fun timeIn(playing: Playing, clip: Clip, now: Float): Float {
        val elapsed = now - playing.start
        return when {
            playing.loop -> ((elapsed % clip.length) + clip.length) % clip.length
            playing.hold >= 0f && playing.hold < clip.length && elapsed > playing.hold -> playing.hold + (elapsed - playing.hold) % (clip.length - playing.hold)
            else -> elapsed.coerceIn(0f, clip.length)
        }
    }

    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    private companion object {
        /** Blocks off a line over their head can be read from, and how long a line may be to go there. */
        const val SPEECH_RANGE = 20.0
        const val OVERHEAD_LETTERS = 52
        /** Ticks to ease from one clip into the next, at least. */
        const val FADE = 5f
        /** Where the downed clip has them on one knee, breathing: it loops from here to its end. */
        const val DOWNED_HOLD = 16f
        val BOOK = setOf("book")

        val textures = HashMap<String, Identifier>()

        fun texture(name: String): Identifier =
            textures.getOrPut(name) { Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/townsfolk/$name.png") }
    }
}
