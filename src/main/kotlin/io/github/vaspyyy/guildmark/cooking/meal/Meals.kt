package io.github.vaspyyy.guildmark.cooking.meal

import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

/**
 * Meals that matter, and Winter Chill (living-world/cook/design.md section 4). Part F's; the readers below are the
 * three hooks the combat and talents code will call, and they answer "no change" until it lands.
 */
object Meals {
    /** The family's effect of a dish of [kind] with [data] eaten by [player] at [stage] (4.1; stub: nothing). */
    fun eat(player: ServerPlayer, kind: DishKind, data: DishData, stage: Stage) {}

    /** The extra stamina regeneration [player] has from a meal and the cold (4.2, 4.3; stub: 0). */
    fun regen(player: Player): Float = 0f

    /** The extra poise [player] has from a meal (4.2; stub: 0). */
    fun poise(player: Player): Float = 0f

    /** The extra stamina [player] can hold from a meal (4.2; stub: 0). */
    fun maxStamina(player: Player): Float = 0f

    /** Whether [player] is Chilled (4.3; stub: never). */
    fun chilled(player: Player): Boolean = false

    /** Part F's registration: the effect listener and the Winter Chill check (stub: nothing). */
    fun register() {}
}
