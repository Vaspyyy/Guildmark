package io.github.vaspyyy.guildmark.client.homestead

import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.homestead.GoodsCrateBlock
import io.github.vaspyyy.guildmark.homestead.GoodsMenu
import io.github.vaspyyy.guildmark.homestead.Materials
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu

/**
 * A goods crate's chest: the supply crate with what's in it counted up in the builders' units under the title (a log
 * is four planks, a block of iron nine ingots), the shipping crate with a word on what it takes.
 */
class GoodsScreen(menu: ChestMenu, inventory: Inventory, title: Component) : ContainerScreen(menu, inventory, title) {
    private val kind = (menu as? GoodsMenu)?.kind ?: GoodsCrateBlock.Kind.SUPPLY

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val y = topPos - 11
        if (y < 2) return
        val line = when (kind) {
            GoodsCrateBlock.Kind.SUPPLY -> {
                val n = IntArray(Stuff.PAID.size)
                for (i in 0 until 27) {
                    val stack = menu.slots[i].item
                    val (stuff, units) = Materials.of(stack) ?: continue
                    n[stuff.ordinal] += units * stack.count
                }
                val parts = Stuff.PAID.map { Component.translatable("gui.guildmark.homestead.crate.count", n[it.ordinal], Component.translatable("gui.guildmark.homestead.stuff.${it.key}")) }
                Component.translatable("gui.guildmark.homestead.crate.supply", parts[0], parts[1], parts[2], parts[3], parts[4])
            }
            GoodsCrateBlock.Kind.SHIPPING -> Component.translatable("gui.guildmark.homestead.crate.shipping")
        }
        graphics.centeredText(font, line, leftPos + imageWidth / 2, y, Ui.TEXT_SOFT)
    }
}
