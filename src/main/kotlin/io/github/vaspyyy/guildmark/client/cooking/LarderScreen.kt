package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.dish.Freshness
import io.github.vaspyyy.guildmark.cooking.dish.LarderBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.util.Util
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import kotlin.math.sin

/**
 * The Larder's chest (living-world/cook/design.md 3.2): the game's two-row chest, with the one thing a larder is for
 * shown on it. Under every dish, in the Larder and in your pack, a thin bar of how much of its keeping is left, worked
 * out from the day it was cooked exactly as the tooltip does (green and full on the day it is cooked, turning toward
 * amber as it nears stale, amber through stale, and an empty red stub that breathes once it has gone off), so the
 * half-rate ageing is something to watch: a dish in the pack shortens, the same dish in here hardly moves. Above the
 * window, in words, what the Larder holds and how it is keeping. Nothing here is typed or invented: every figure is a
 * stack's own.
 */
class LarderScreen(menu: ChestMenu, inventory: Inventory, title: Component) : ContainerScreen(menu, inventory, title) {
    override fun extractSlot(graphics: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int) {
        super.extractSlot(graphics, slot, mouseX, mouseY)
        if (!Cooking.spoilage()) return
        val level = Minecraft.getInstance().level ?: return
        val stack = slot.item
        val look = Look.of(stack, Freshness.today(level)) ?: return
        val x = slot.x + 2
        val y = slot.y + 13
        graphics.fill(x, y, x + 13, y + 2, ARGB.color(1f, 0x000000))
        val full = if (look.stage == Stage.GONE) 1 else Mth.ceil(look.life * 13f).coerceIn(1, 13)
        val alpha = if (look.stage == Stage.GONE) 0.65f + 0.35f * sin(Util.getMillis() / 380.0).toFloat() else 1f
        graphics.fill(x, y, x + full, y + 1, ARGB.color(alpha, look.color))
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val y = topPos - 11
        if (y < 2) return
        graphics.centeredText(font, header(), leftPos + imageWidth / 2, y, Ui.TEXT_SOFT)
    }

    /** What the Larder holds, in words: how many portions are fresh, stale and gone off, after the one line of what a larder does. */
    private fun header(): Component {
        val level = Minecraft.getInstance().level
        val keeps = Component.translatable("gui.guildmark.cook.larder.keeps")
        if (!Cooking.spoilage()) return Component.translatable("gui.guildmark.cook.larder.off")
        if (level == null) return keeps
        var fresh = 0
        var stale = 0
        var gone = 0
        for (i in 0 until LarderBlockEntity.SIZE) {
            val stack = menu.slots[i].item
            if (stack.isEmpty || stack.item !is DishItem) continue
            when (Freshness.stageOf(level, stack)) {
                Stage.FRESH -> fresh += stack.count
                Stage.STALE -> stale += stack.count
                Stage.GONE -> gone += stack.count
            }
        }
        if (fresh + stale + gone == 0) return keeps
        val line: MutableComponent = keeps.copy()
        if (fresh > 0) line.append(" · ").append(Component.translatable("gui.guildmark.cook.larder.fresh", fresh).withColor(Ui.GREEN and 0xFFFFFF))
        if (stale > 0) line.append(" · ").append(Component.translatable("gui.guildmark.cook.larder.stale", stale).withColor(Ui.GOLD and 0xFFFFFF))
        if (gone > 0) line.append(" · ").append(Component.translatable("gui.guildmark.cook.larder.gone", gone).withColor(Ui.RED and 0xFFFFFF))
        return line
    }

    /** How a dish is keeping: its stage, the share of its keeping left (1 on the day it is cooked, 0 once it has gone off) and the colour of its bar. */
    private class Look(val stage: Stage, val life: Float, val color: Int) {
        companion object {
            private const val GREEN = 0x4FBF4A
            private const val LIME = 0xB5CC3A
            private const val AMBER = 0xE3A92E
            private const val ORANGE = 0xD9622B
            private const val RED = 0xC0392B

            fun of(stack: ItemStack, today: Long): Look? {
                val item = stack.item as? DishItem ?: return null
                if (item.scorched) return null
                val data = stack.get(CookRegistry.DISH.get()) ?: DishData.PLAIN
                val age = Freshness.age(today, data)
                val limit = Freshness.limits(item.kind, data.keep)
                val stage = Freshness.stageFor(age, item.kind, data.keep)
                val span = limit[1] + 1
                val life = ((span - age).toFloat() / span).coerceIn(0f, 1f)
                val color = when (stage) {
                    Stage.FRESH -> mix(GREEN, LIME, if (limit[0] <= 0) 1f else age.toFloat() / limit[0])
                    Stage.STALE -> mix(AMBER, ORANGE, if (limit[1] <= limit[0]) 1f else (age - limit[0]).toFloat() / (limit[1] - limit[0]))
                    Stage.GONE -> RED
                }
                return Look(stage, life, color)
            }

            private fun mix(from: Int, to: Int, t: Float): Int = ARGB.srgbLerp(t.coerceIn(0f, 1f), from, to) and 0xFFFFFF
        }
    }
}
