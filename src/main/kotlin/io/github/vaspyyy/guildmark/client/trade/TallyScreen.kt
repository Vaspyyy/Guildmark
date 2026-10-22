package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.network.TallyActPayload
import io.github.vaspyyy.guildmark.network.TallyView
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.floor
import kotlin.math.min

/**
 * The Tally's screen (living-world/trader/design.md, Part 1): the trader's book open on the world, five pages and a ribbon. It is only a frame round the
 * [TallyStage], which draws the book (on a [GuiPen]) and answers the mouse and keys; what the book knows and remembers is its [TallyBook]. While it is open
 * it asks the server for fresh news every few seconds and the server's answer is given to [update].
 */
class TallyScreen(view: TallyView) : Screen(Component.translatable("item.guildmark.tally")) {
    private val opened = Util.getMillis()

    private val env = object : Env {
        override fun sound(name: String, pitch: Float, volume: Float) {
            if (!menuSounds()) return
            Ui.play(TradeSounds.get(name), pitch, volume)
        }

        override fun send(action: Int, about: Long) {
            ClientPacketDistributor.sendToServer(TallyActPayload(action, about))
        }

        override fun stand(): Stand? = Minecraft.getInstance().player?.let { Stand(it.x, it.z) }

        override fun select() {
            Ui.select()
        }
    }

    private val book = TallyBook(view, env)
    private val stage = TallyStage(book)

    /** Seconds since the screen opened. */
    private fun elapsed(): Double = (Util.getMillis() - opened) / 1000.0

    /** The server sent a newer sight of the world. */
    fun update(view: TallyView) {
        book.t = elapsed()
        book.update(view)
    }

    override fun isPauseScreen(): Boolean = false

    /** Subtitles are drawn over this screen, not hidden under its shade. */
    override fun isInGameUi(): Boolean = true

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    override fun tick() {
        book.t = elapsed()
        book.refresh()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val fadeIn = min(1f, (Util.getMillis() - opened) / 300f)
        graphics.fill(0, 0, width, height, ARGB.color(fadeIn * 0.7f, 0x06060B))
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        book.t = elapsed()
        stage.draw(GuiPen(graphics, font), width, height, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (stage.click(floor(event.x()).toInt(), floor(event.y()).toInt(), event.button())) return true
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY != 0.0 && stage.wheel(scrollY > 0.0)) return true
        return super.mouseScrolled(x, y, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (minecraft.options.keyInventory.matches(event)) {
            onClose()
            return true
        }
        if (stage.key(event.key())) return true
        return super.keyPressed(event)
    }

    private companion object {
        /** Whether the screens make their own sounds (Guildmark Settings > Interface): as the kit's `Ui` decides it. */
        fun menuSounds(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.MENU_SOUNDS.get()
    }
}
