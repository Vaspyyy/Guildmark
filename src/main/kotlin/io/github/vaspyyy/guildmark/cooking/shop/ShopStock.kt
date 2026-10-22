package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * The 18 stacks of a cookshop's Larder (living-world/cook/design.md 6.4): a view of the shop's record, not a copy. The Larder
 * block entity is given this as its `backing` and every container call goes straight to [ShopRec.stock], so the shop's stock is
 * part of the market's save, the selling hours never need the Larder's chunk, and breaking the Larder block loses nothing.
 * It opens for the shop's owner and their party only ([CookShops.mayOpen]); it takes dishes only. Any change marks the ledger
 * to be saved and the shop's plates and slate to be dressed again at the next look ([ShopRec.touched]).
 */
class ShopStock(private val level: ServerLevel, val shop: ShopRec) : Container {
    override fun getContainerSize(): Int = ShopRec.SLOTS

    override fun isEmpty(): Boolean = shop.stock.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = shop.stock.getOrElse(slot) { ItemStack.EMPTY }

    override fun removeItem(slot: Int, count: Int): ItemStack {
        val stack = shop.stock.getOrNull(slot) ?: return ItemStack.EMPTY
        if (stack.isEmpty || count <= 0) return ItemStack.EMPTY
        val taken = stack.split(count)
        if (stack.isEmpty) shop.stock[slot] = ItemStack.EMPTY
        if (!taken.isEmpty) setChanged()
        return taken
    }

    /**
     * Takes nothing: the one caller that would take a Larder's stacks without counting them is the builders emptying a place
     * they are taking down, and the shop's stock is the shop's, not the block's (a works taken down pays the stock back to its owner
     * itself, [CookShops.close]).
     */
    override fun removeItemNoUpdate(slot: Int): ItemStack = ItemStack.EMPTY

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot !in 0 until ShopRec.SLOTS) return
        shop.stock[slot] = if (stack.isEmpty) ItemStack.EMPTY else stack
        setChanged()
    }

    override fun setChanged() {
        shop.touched = true
        Kitchens.get(level).changed()
    }

    /** Its owner and their party only (the Larder block says "The cookshop's larder is its owner's." to anyone else). */
    override fun stillValid(player: Player): Boolean = CookShops.mayOpen(level, shop, player)

    /** Dishes only. */
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = stack.item is DishItem

    /**
     * Puts as much of [stack] into the stock as fits, onto equal stacks first and then into empty slots, and takes it off [stack];
     * what does not fit stays in it. The portions put in (the owner handing a dish to the keeper, the link of a Larder that was
     * filled in the moment before it was given the stock).
     */
    fun insert(stack: ItemStack): Int {
        if (stack.isEmpty || stack.item !is DishItem) return 0
        var moved = 0
        for (pass in 0..1) {
            for (slot in shop.stock.indices) {
                if (stack.isEmpty) break
                val there = shop.stock[slot]
                if (pass == 0) {
                    if (there.isEmpty || !ItemStack.isSameItemSameComponents(there, stack)) continue
                    val n = minOf(there.maxStackSize - there.count, stack.count)
                    if (n <= 0) continue
                    there.grow(n)
                    stack.shrink(n)
                    moved += n
                } else {
                    if (!there.isEmpty) continue
                    val n = minOf(stack.maxStackSize, stack.count)
                    shop.stock[slot] = stack.copyWithCount(n)
                    stack.shrink(n)
                    moved += n
                }
            }
        }
        if (moved > 0) setChanged()
        return moved
    }

    /** The portions in the stock, of every kind. */
    fun portions(): Int = shop.stock.sumOf { if (it.isEmpty) 0 else it.count }

    /** Clears nothing, for the same reason as [removeItemNoUpdate]: a block being cleared away does not clear the shop. */
    override fun clearContent() {}
}
