package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Role
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.AddServerReloadListenersEvent

/**
 * What an ingredient brings to a pot (living-world/cook/design.md 2.2): its share of each of the seven [Role]s, from the
 * JSON data files in the folder `cook_roles` that a modpack can extend (see [CookRolesLoader] and [RoleTable]). The table
 * is server data, read again by `/reload`, and nothing in it is sent to a client. Anything it does not name is not an
 * ingredient.
 */
object Roles {
    @Volatile
    private var table: RoleTable = RoleTable.EMPTY

    /** What [stack] brings to a pot, role to weight (the weights add up to 1); null is "That's not for the pot." */
    fun of(stack: ItemStack): Map<Role, Double>? = food(stack)?.roles

    /**
     * The share of each role, in [Role] order and summing to 1, that [items] put together make: every item counts as many
     * times as its stack holds, and one that is not an ingredient counts for nothing. All zero if none is.
     */
    fun share(items: List<ItemStack>): DoubleArray {
        val parts = ArrayList<Pair<Food, Int>>(items.size)
        for (stack in items) {
            val food = food(stack) ?: continue
            parts.add(food to stack.count)
        }
        return RoleTable.mix(parts)
    }

    /** The food [stack] is in the merged table (its name, its roles, what it gives back), or null if it is not for the pot. */
    fun food(stack: ItemStack): Food? {
        if (stack.isEmpty) return null
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        return table.of(id) { tag -> inTag(stack, tag) }
    }

    /**
     * What the cook is handed back when [stack] goes into a vessel (the empty bucket of a milk bucket, the bottle of a honey
     * bottle): a single item, or the empty stack if it gives nothing back.
     */
    fun returns(stack: ItemStack): ItemStack {
        val id = food(stack)?.returns ?: return ItemStack.EMPTY
        val key = Identifier.tryParse(id) ?: return ItemStack.EMPTY
        val item = BuiltInRegistries.ITEM.getOptional(key).orElse(null) ?: return ItemStack.EMPTY
        return ItemStack(item)
    }

    /** The merged table as it stands (every pack's files from the last reload). */
    fun current(): RoleTable = table

    /** The table the loader has just made. */
    internal fun install(next: RoleTable) {
        table = next
    }

    private fun inTag(stack: ItemStack, tag: String): Boolean {
        val id = Identifier.tryParse(tag) ?: return false
        return stack.`is`(TagKey.create(Registries.ITEM, id))
    }

    /**
     * Part A's registration: the `cook_roles` reload listener, the recipe commands (`cook roles`, `ideal`, `rate`, `quality`,
     * `xp`), the clearing of the table when the server stops and its line for `cook perf`.
     */
    fun register() {
        NeoForge.EVENT_BUS.addListener(AddServerReloadListenersEvent::class.java) { event ->
            event.addListener(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cook_roles"), CookRolesLoader())
        }
        RecipeCommand.init()
        Cooking.onForget { install(RoleTable.EMPTY) }
        CookStats.readout("recipes") {
            val t = table
            "${t.foods.size} foods (${t.itemCount} items, ${t.tagCount} tags) from ${t.files} files; " +
                "${Recognise.runs} recognised, ${Ideal.made} ideals worked out, ${CookXp.paid} batches paid"
        }
    }
}
