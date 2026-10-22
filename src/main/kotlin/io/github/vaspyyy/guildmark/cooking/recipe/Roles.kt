package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.Role
import net.minecraft.world.item.ItemStack

/**
 * What an ingredient brings to a pot (living-world/cook/design.md 2.2): its share of each of the seven [Role]s, from the
 * JSON data files in the folder `cook_roles` that a modpack can extend. Part A's; until it lands, nothing is an ingredient.
 */
object Roles {
    /** What [stack] brings to a pot, role to weight; null is "That's not for the pot." (stub: null). */
    fun of(stack: ItemStack): Map<Role, Double>? = null

    /** The share of each role, in [Role] order and summing to 1, that [items] put together make (stub: all zero). */
    fun share(items: List<ItemStack>): DoubleArray = DoubleArray(Role.entries.size)

    /** Part A's registration: the `cook_roles` reload listener and the recipe commands (stub: nothing). */
    fun register() {}
}
