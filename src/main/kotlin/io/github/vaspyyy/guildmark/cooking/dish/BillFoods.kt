package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * The foods the standard bills name (living-world/cook/design.md B.3, the `name`s of B.1), as the Cook's Book draws and
 * words them: the item that stands for each, and what each brings to a pot. The role data itself is the server's and is
 * not sent to a client (B.2), so the Book has this small table of its own, the base file's own numbers. It is only used
 * to show the first guess (the standard bill) and to put a name to what a cook has found out ("less carrot", from a role
 * that came out too high); it never holds the world's ideal.
 */
object BillFoods {
    private class Food(val item: String, val roles: Map<Role, Double>)

    private val FOODS: Map<String, Food> = mapOf(
        "wheat" to Food("minecraft:wheat", mapOf(Role.BODY to 1.0)),
        "potato" to Food("minecraft:potato", mapOf(Role.BODY to 0.8, Role.GREEN to 0.2)),
        "carrot" to Food("minecraft:carrot", mapOf(Role.GREEN to 0.6, Role.SWEET to 0.4)),
        "beetroot" to Food("minecraft:beetroot", mapOf(Role.GREEN to 0.5, Role.SWEET to 0.3, Role.SAVOUR to 0.2)),
        "apple" to Food("minecraft:apple", mapOf(Role.SWEET to 0.8, Role.GREEN to 0.2)),
        "honey" to Food("minecraft:honey_bottle", mapOf(Role.SWEET to 1.0)),
        "beef" to Food("minecraft:beef", mapOf(Role.MEAT to 1.0)),
        "pork" to Food("minecraft:porkchop", mapOf(Role.MEAT to 0.8, Role.RICH to 0.2)),
        "mutton" to Food("minecraft:mutton", mapOf(Role.MEAT to 0.8, Role.SAVOUR to 0.2)),
        "cod" to Food("minecraft:cod", mapOf(Role.SEA to 1.0)),
        "salmon" to Food("minecraft:salmon", mapOf(Role.SEA to 0.8, Role.RICH to 0.2)),
        "egg" to Food("minecraft:egg", mapOf(Role.RICH to 1.0)),
        "milk" to Food("minecraft:milk_bucket", mapOf(Role.RICH to 1.0)),
        "mushroom" to Food("minecraft:brown_mushroom", mapOf(Role.SAVOUR to 0.7, Role.GREEN to 0.3)),
        "bone" to Food("minecraft:bone", mapOf(Role.SAVOUR to 0.6, Role.MEAT to 0.4)),
        "herb" to Food("guildmark:herb_sprig", mapOf(Role.SAVOUR to 1.0)),
    )

    /** One of [food]: the item that stands for it, or an empty stack if it names none the game has. */
    fun stack(food: String, count: Int = 1): ItemStack {
        val id = FOODS[food]?.item?.let { Identifier.tryParse(it) } ?: return ItemStack.EMPTY
        return BuiltInRegistries.ITEM.getOptional(id).map { ItemStack(it, count) }.orElse(ItemStack.EMPTY)
    }

    /** How much of [role] one item of [food] brings (0 for a food the table does not know). */
    fun weight(food: String, role: Role): Double = FOODS[food]?.roles?.get(role) ?: 0.0

    /** The food of [kind]'s standard bill that carries most of [role] (count times weight), or null if none carries any. */
    fun heaviest(kind: DishKind, role: Role): String? =
        kind.bill.entries.map { (food, count) -> food to count * weight(food, role) }.filter { it.second > 0.0 }.maxByOrNull { it.second }?.first

    /** How much of each role [kind]'s standard bill brings, as a share of the whole, in [Role] order (the bill as a mix). */
    fun share(kind: DishKind): DoubleArray {
        val total = DoubleArray(Role.entries.size)
        for ((food, count) in kind.bill) for (role in Role.entries) total[role.ordinal] += count * weight(food, role)
        val sum = total.sum()
        return if (sum > 0.0) DoubleArray(total.size) { total[it] / sum } else total
    }
}
