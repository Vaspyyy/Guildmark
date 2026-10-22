package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.ModList
import net.neoforged.fml.ModLoadingContext
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * The Larder's menu type (living-world/cook/design.md 1.1, 6.4): a chest of two rows over the Larder's 18 slots, whose
 * slots take dishes only. It has a registry of its own, put on the mod bus by [Dishes.register] so that no shared
 * registry file is edited.
 */
object LarderMenus {
    val MENUS: DeferredRegister<MenuType<*>> = DeferredRegister.create(Registries.MENU, Guildmark.MOD_ID)

    val LARDER: DeferredHolder<MenuType<*>, MenuType<LarderMenu>> =
        MENUS.register("larder") { -> MenuType({ id, inventory -> LarderMenu.client(id, inventory) }, FeatureFlags.VANILLA_SET) }

    /** Whether the registry reached the mod bus. If it could not, a Larder cannot be opened (it says so) and nothing else is affected. */
    var registered = false
        private set

    /**
     * Puts the registry on the mod bus of the mod being loaded. [Dishes.register] runs inside the mod's constructor
     * (from `Cooking.register`), where the loading context knows which mod that is, so the part needs no bus handed to it;
     * failing that, the bus is asked of the mod's own container.
     */
    fun register() {
        val bus = busOf { ModLoadingContext.get().activeContainer } ?: busOf { ModList.get().getModContainerById(Guildmark.MOD_ID).orElse(null) }
        if (bus == null) {
            Guildmark.LOGGER.warn("The Larder's menu could not be registered (no mod bus to put it on); a Larder cannot be opened")
            return
        }
        MENUS.register(bus)
        registered = true
    }

    private fun busOf(container: () -> ModContainer?): IEventBus? = try {
        container()?.eventBus
    } catch (e: RuntimeException) {
        null
    }
}

/**
 * A Larder's chest: two rows of nine, each slot taking dishes only (hoppers and shift-clicks go through the same slots).
 * The container is the Larder itself, on the server, and a stand-in on the client; what keeps the days is the Larder's.
 */
class LarderMenu(type: MenuType<*>, id: Int, inventory: Inventory, container: Container) : ChestMenu(type, id, inventory, container, 2) {
    init {
        for (i in 0 until LarderBlockEntity.SIZE) {
            val old = slots[i]
            val slot = object : Slot(container, i, old.x, old.y) {
                override fun mayPlace(stack: ItemStack): Boolean = stack.item is DishItem
            }
            slot.index = i
            slots[i] = slot
        }
    }

    companion object {
        fun client(id: Int, inventory: Inventory): LarderMenu =
            LarderMenu(LarderMenus.LARDER.get(), id, inventory, SimpleContainer(LarderBlockEntity.SIZE))
    }
}
