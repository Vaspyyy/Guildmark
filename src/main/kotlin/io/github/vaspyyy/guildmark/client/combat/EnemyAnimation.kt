package io.github.vaspyyy.guildmark.client.combat

import io.github.vaspyyy.guildmark.client.model.EnemyRenderState
import io.github.vaspyyy.guildmark.combat.CombatEnemy

/**
 * Plays a [CombatEnemy]'s attacks on its model with the same clips and animator as the player's moves.
 * Its clips live in assets/guildmark/animations/<creature>/ (art/scripts/enemy_anims.py).
 */
object EnemyAnimation {
    /** Fills in the frame for whatever attack [entity] is seen doing, easing out of one it cut short. */
    fun extract(entity: CombatEnemy, state: EnemyRenderState, partial: Float) {
        val group = entity.animations
        val cut = entity.cutShortAttack(partial)?.let { (attack, time, fade) ->
            val clip = Clips.get(group + "/" + attack.anim) ?: return@let null
            val weight = clip.weight(time) * fade
            if (weight <= 0f) null else CombatAnimator.Frame(clip, time, weight, 0f, 0f, 0f, null)
        }
        val shown = entity.shownAttack(partial)
        val clip = shown?.let { Clips.get(group + "/" + it.first.anim) }
        state.frame = if (shown != null && clip != null) {
            val time = shown.second
            CombatAnimator.Frame(clip, time, clip.weight(time), 0f, 0f, 0f, cut?.takeIf { time < clip.blendIn * 2f })
        } else cut
    }

    /** Holds the creature in [name]'s pose at [time] (kneeling at its post, or dying), over anything else it was doing. */
    fun hold(state: EnemyRenderState, name: String, time: Float) {
        val clip = Clips.get(name) ?: return
        val t = time.coerceIn(0f, clip.length)
        val under = state.frame?.let { CombatAnimator.Frame(it.clip, it.time, it.weight, 0f, 0f, 0f, null) }
        state.frame = CombatAnimator.Frame(clip, t, clip.weight(t), 0f, 0f, 0f, under)
    }
}
