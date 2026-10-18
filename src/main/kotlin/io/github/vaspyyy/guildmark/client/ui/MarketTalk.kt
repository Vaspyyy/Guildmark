package io.github.vaspyyy.guildmark.client.ui

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.network.TalkAskPayload
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/**
 * The market screen's Talk button (living-world/design-voices.md section 2): a tab on the panel's right edge, level
 * with the keeper's plate, that asks the keeper for a talk ([TalkAskPayload]). The server answers with the talk
 * screen in the market screen's place, the camera staying where it is. It shows only with a keeper the client can
 * see, and with townsfolk talk switched on. MarketScreen draws it ([draw]) and passes it its clicks ([click]), giving
 * both the panel's right edge and the top of the keeper's row.
 */
object MarketTalk {
    /** How tall the tab is. */
    private const val HEIGHT = 15

    /** How far the tab reaches in over the panel's edge, so it sits on it. */
    private const val OVERLAP = 3

    /** Ticks after a press before another counts: the server's answer replaces the screen. */
    private const val AGAIN = 20

    /** What the tab says. */
    private val LABEL: Component = Component.translatable("gui.guildmark.market.talk")

    /** The game time of the last press. */
    private var askedAt = Long.MIN_VALUE / 2

    /** Whether the tab shows for [keeper]: a keeper the client can see, and townsfolk talk switched on. */
    fun shows(keeper: Int): Boolean {
        if (keeper < 0 || GuildmarkConfig.SPEC.isLoaded && !GuildmarkConfig.TOWNSFOLK_TALK.get()) return false
        return Minecraft.getInstance().level?.getEntity(keeper) is Townsperson
    }

    /** How wide the tab is, its word and a margin either side. */
    private fun width(): Int = Minecraft.getInstance().font.width(LABEL) + 18

    /** Whether ([x], [y]) is on the tab, on the panel's [right] edge from [top]. */
    private fun over(right: Int, top: Int, x: Double, y: Double): Boolean {
        val left = right - OVERLAP
        return x >= left && x < left + width() && y >= top && y < top + HEIGHT
    }

    /** Draws the tab on the panel's [right] edge from [top], come in [k] of the way (0 to 1) as the panel has. */
    fun draw(graphics: GuiGraphicsExtractor, keeper: Int, right: Int, top: Int, mouseX: Int, mouseY: Int, k: Float) {
        if (k <= 0f || !shows(keeper)) return
        val font = Minecraft.getInstance().font
        val x = right - OVERLAP
        val w = width()
        val lit = k >= 1f && over(right, top, mouseX.toDouble(), mouseY.toDouble())
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(true, lit), x, top, w, HEIGHT, ARGB.white(k))
        if (k < 0.05f) return
        val color = if (lit) Ui.GOLD_BRIGHT else Ui.TEXT
        graphics.text(font, LABEL, x + (w - font.width(LABEL)) / 2, top + (HEIGHT - 8) / 2, if (k >= 1f) color else ARGB.multiplyAlpha(color, k), false)
    }

    /** A left click at ([x], [y]): true if it was on the tab, and the keeper has been asked for a talk. */
    fun click(keeper: Int, right: Int, top: Int, x: Double, y: Double): Boolean {
        if (!shows(keeper) || !over(right, top, x, y)) return false
        val now = Minecraft.getInstance().level?.gameTime ?: return true
        if (now - askedAt < AGAIN) return true
        askedAt = now
        Ui.select()
        ClientPacketDistributor.sendToServer(TalkAskPayload(keeper))
        return true
    }
}
