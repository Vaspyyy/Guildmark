package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.cooking.dish.LarderMenus
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.world.inventory.ChestMenu
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent

/**
 * What Part D registers on the client (living-world/cook/design.md 3.2, 3.4): the Larder's chest screen. The Cook's Book
 * screen is opened by the item, the way the Almanac's is (a call behind `level.isClientSide`), and needs no registration.
 * [CookClient.register] calls this, so D edits no shared file. If the Larder's menu could not be registered (see
 * [LarderMenus.registered]) there is nothing to attach a screen to, and nothing is.
 */
object CookBookClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterMenuScreensEvent::class.java) { event ->
            if (LarderMenus.registered) {
                val screen = MenuScreens.ScreenConstructor<ChestMenu, LarderScreen> { menu, inventory, title -> LarderScreen(menu, inventory, title) }
                event.register(LarderMenus.LARDER.get(), screen)
            }
        }
    }
}
