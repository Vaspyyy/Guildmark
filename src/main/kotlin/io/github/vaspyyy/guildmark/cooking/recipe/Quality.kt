package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.Tier
import net.minecraft.server.level.ServerPlayer

/** How well a batch comes out (living-world/cook/design.md 2.5). Part A's; until it lands, every score is zero. */
object Quality {
    /** How good a mix is, 0 to 1, against the [ideal] (both in [io.github.vaspyyy.guildmark.cooking.Role] order; stub: 0). */
    fun mix(share: DoubleArray, ideal: DoubleArray): Double = 0.0

    /** The quality of a batch from its [mix], [timing], [attention] and [care] and the cook's [bonus] (stub: 0). */
    fun q(mix: Double, timing: Double, attention: Double, care: Double, bonus: Double): Double = 0.0

    /** The tier a quality earns: the highest whose [Tier.q] it reaches. */
    fun tier(q: Double): Tier = Tier.entries.lastOrNull { q >= it.q } ?: Tier.SPOILT

    /** The bonus [player]'s COOKING level and the tree's two cooking bonuses give a batch (stub: 0). */
    fun bonus(player: ServerPlayer): Double = 0.0
}
