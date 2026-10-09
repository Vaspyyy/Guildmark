package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

object ModCreativeTabs {
    val TABS: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Guildmark.MOD_ID)

    val MAIN: DeferredHolder<CreativeModeTab, CreativeModeTab> = TABS.register("main") { ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.guildmark"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon { ModItems.GUILD_MARK.get().defaultInstance }
            .displayItems { _, output ->
                output.accept(ModItems.QUEST_BOARD.get())
                output.accept(ModItems.GUILD_MARK.get())
            }
            .build()
    }
}
