package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.meal.Meals
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects

/**
 * The server's half of eating a dish (living-world/cook/design.md 3.3). The game does the eating itself: the consumable
 * of [DishItem] takes 1.2 or 1.6 seconds (Gruel 2.4), the food bar fills from the stack's own `FOOD` (which [Freshness] keeps right for
 * the stage), and the bowl comes back through the use remainder. What a dish adds is here: when a player begins to eat,
 * the one long recording of the mouthful and the pose the other parts draw for everyone who sees ([begin]); when the
 * portion is eaten, the meal's effect that Part F gives, and the hunger of a portion that has gone off ([finish]).
 */
object DishEating {
    /** How loud the mouthful is. It is the cook's own sound, so it carries as far as a cook's other sounds do. */
    private const val VOLUME = 0.9f

    /** The sound of eating a portion of [kind] ([kind] null is Gruel): a gulp for Broth, a spoon for what is served in a bowl, a bite for the rest. */
    fun soundName(kind: DishKind?): String = when {
        kind == DishKind.BROTH -> "cook.drink"
        DishFood.bowl(kind) -> "cook.eat.spoon"
        else -> "cook.eat.bite"
    }

    /** The pose of eating a portion of [kind] (3.3): Part E draws it on the player and on whoever sees them. */
    fun action(kind: DishKind?): CookAction = when {
        kind == DishKind.BROTH -> CookAction.DRINK
        DishFood.bowl(kind) -> CookAction.EAT_SPOON
        else -> CookAction.EAT_BITE
    }

    /**
     * [player] has begun to eat a portion of [kind]. The consumable's own sound is silent (the game repeats it every few
     * ticks, and a dish's is one long recording), so the mouthful is played here, once, for everyone near, and the pose
     * is asked for. A dish is food whether or not Hearthcraft is on, so its sound is always played; the pose is the
     * cook's, and is asked for only while it is on. A meal that takes longer than a bowl's (Gruel) has its recording
     * played that much slower, and lower, so its clinks keep to the slower spoonfuls.
     */
    fun begin(player: ServerPlayer, kind: DishKind?) {
        val level = player.level()
        val slow = DishFood.seconds(DishKind.STEW) / DishFood.seconds(kind)
        level.playSound(
            null, player.x, player.y, player.z, CookSounds.get(soundName(kind)), SoundSource.PLAYERS,
            VOLUME, (0.96f + level.random.nextFloat() * 0.08f) * if (DishFood.bowl(kind)) slow else 1f,
        )
        if (Cooking.enabled()) CookActions.play(player, action(kind), player.blockPosition())
    }

    /**
     * [player] has eaten a portion of [kind] with [data] that was at [stage] when they began. The food and the bowl are
     * the game's, already done. A portion that has gone off makes them hungry, as rotten flesh does (3.2); a kind with a
     * meal gives it through Part F (which scales it for a stale portion and gives none for a gone-off one). Gruel has
     * no meal, and with Hearthcraft off a dish is only food.
     */
    fun finish(player: ServerPlayer, kind: DishKind?, data: DishData, stage: Stage) {
        if (stage == Stage.GONE) player.addEffect(MobEffectInstance(MobEffects.HUNGER, DishFood.HUNGER_TICKS, 0))
        if (kind != null && Cooking.enabled()) Meals.eat(player, kind, data, stage)
    }
}
