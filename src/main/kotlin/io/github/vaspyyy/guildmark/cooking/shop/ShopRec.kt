package io.github.vaspyyy.guildmark.cooking.shop

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.cooking.DishKind
import net.minecraft.core.UUIDUtil
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * A cookshop's record (living-world/cook/design.md 6.4, 9.1): the 18 stacks of its Larder, the Marks in its till, how many
 * portions of each kind it sold today and what is owed back to its owner. Held for each capital in
 * [io.github.vaspyyy.guildmark.cooking.market.Kitchens.shops], by owner, so the hourly sale never needs the shop's chunk.
 * Real from the first commit because the ledger's codec holds it; Part H takes the file over and adds the logic.
 */
class ShopRec(val owner: UUID, val stock: Array<ItemStack>, var till: Int, val sold: IntArray, val returns: MutableList<ItemStack>) {
    companion object {
        /** The slots of a shop's Larder. */
        const val SLOTS = 18

        /** A new, empty shop for [owner]. */
        fun empty(owner: UUID): ShopRec = ShopRec(owner, Array(SLOTS) { ItemStack.EMPTY }, 0, IntArray(DishKind.entries.size), ArrayList())

        val CODEC: Codec<ShopRec> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter { it.owner },
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("stock", listOf()).forGetter { it.stock.toList() },
                Codec.INT.optionalFieldOf("till", 0).forGetter { it.till },
                Codec.INT.listOf().optionalFieldOf("sold", listOf()).forGetter { it.sold.toList() },
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("returns", listOf()).forGetter { it.returns.toList() },
            ).apply(i) { owner, stock, till, sold, returns ->
                val kept = Array(SLOTS) { stock.getOrElse(it) { ItemStack.EMPTY } }
                val counts = IntArray(DishKind.entries.size) { sold.getOrElse(it) { 0 } }
                ShopRec(owner, kept, till, counts, returns.toMutableList())
            }
        }
    }
}
