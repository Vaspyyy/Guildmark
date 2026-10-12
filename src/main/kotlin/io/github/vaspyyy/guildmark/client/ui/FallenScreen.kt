package io.github.vaspyyy.guildmark.client.ui

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.registry.ModSounds
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.neoforged.neoforge.client.event.ScreenEvent
import kotlin.math.max
import kotlin.math.min

/**
 * The death screen: the world darkens, a bell tolls, FALLEN is carved across the screen, and a torn
 * slip of the guild's roll names what killed you before a wax seal is pressed onto it. The buttons
 * (rise again, or leave for the title screen) work as the vanilla screen's do.
 */
class FallenScreen(private val cause: Component?, private val hardcore: Boolean, private val player: LocalPlayer) :
    Screen(Component.translatable(if (hardcore) "gui.guildmark.fallen.hardcore" else "gui.guildmark.fallen")) {

    private val buttons = ArrayList<Button>()
    private var ticks = 0
    /** Respawn asked for: Rise stays off while it's on its way. */
    private var rising = false
    private val epitaph: Component = Component.translatable(
        if (hardcore) "gui.guildmark.fallen.epitaph_hardcore" else "gui.guildmark.fallen.epitaph.${player.random.nextInt(EPITAPHS)}"
    )

    override fun init() {
        buttons.clear()
        val rise = Component.translatable(if (hardcore) "deathScreen.spectate" else "gui.guildmark.fallen.rise")
        val top = slipTop() + SLIP_HEIGHT + 30
        buttons += addRenderableWidget(Ui.KitButton(width / 2 - 100, top, 200, 20, rise) { button ->
            player.respawn()
            rising = true
            button.active = false
        })
        buttons += addRenderableWidget(Ui.KitButton(width / 2 - 100, top + 24, 200, 20, Component.translatable("deathScreen.titleScreen")) {
            minecraft.reportingContext.draftReportHandled(minecraft, this, Runnable { leave() }, true)
        })
        enableButtons()
    }

    private fun enableButtons() {
        buttons.forEachIndexed { i, button -> button.active = ticks >= BUTTONS_AT && !(i == 0 && rising) }
    }

    private fun slipTop(): Int = height / 2 - 18

    private fun leave() {
        if (hardcore) {
            toTitle()
            return
        }
        val confirm = DeathScreen.TitleConfirmScreen(
            BooleanConsumer { quit ->
                if (quit) toTitle()
                else {
                    player.respawn()
                    rising = true
                    // Back to this screen (not a fresh one, which would toll again) until the respawn lands
                    minecraft.gui.setScreen(this)
                }
            },
            Component.translatable("deathScreen.quit.confirm"),
            CommonComponents.EMPTY,
            Component.translatable("deathScreen.titleScreen"),
            Component.translatable("gui.guildmark.fallen.rise"),
        )
        minecraft.gui.setScreen(confirm)
        confirm.setDelay(20)
    }

    private fun toTitle() {
        minecraft.level?.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE)
        minecraft.disconnectWithSavingScreen()
        minecraft.gui.setScreen(TitleScreen())
    }

    override fun tick() {
        super.tick()
        ticks++
        if (ticks == 1) Ui.play(ModSounds.UI_DEATH_TOLL.get(), 1f, 0.9f)
        if (ticks == SEAL_AT) Ui.play(ModSounds.UI_SEAL.get(), 0.95f, 0.8f)
        if (ticks == BUTTONS_AT) enableButtons()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val t = ticks + a
        // The world sinks into a red dark: heavier at the edges, and the middle band darkest of all
        val k = min(1f, t / 30f)
        graphics.fillGradient(0, 0, width, height / 2, ARGB.color((150 * k).toInt(), 18, 4, 6), ARGB.color((210 * k).toInt(), 6, 2, 3))
        graphics.fillGradient(0, height / 2, width, height, ARGB.color((210 * k).toInt(), 6, 2, 3), ARGB.color((170 * k).toInt(), 24, 6, 6))
        val band = min(1f, max(0f, (t - 6f) / 20f))
        val mid = height / 2 - 64
        graphics.fillGradient(0, mid - 24, width, mid + 18, 0, ARGB.color((150 * band).toInt(), 0, 0, 0))
        graphics.fillGradient(0, mid + 18, width, mid + 60, ARGB.color((150 * band).toInt(), 0, 0, 0), 0)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val t = ticks + a

        // FALLEN, carved large, settling into place as it fades in
        val title = Ui.heading(title)
        val fade = Mth.clamp((t - 8f) / 24f, 0f, 1f)
        if (fade > 0f) {
            val scale = 3f + 0.35f * (1f - fade) * (1f - fade)
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(width / 2f, height / 2f - 64f)
            pose.scale(scale, scale)
            val w = font.width(title)
            graphics.text(font, title, -w / 2, -4, ARGB.color((255 * fade).toInt(), 178, 40, 32), true)
            pose.popMatrix()
        }

        // The torn slip from the guild's roll, naming what happened
        val slipIn = Mth.clamp((t - 18f) / 10f, 0f, 1f)
        if (slipIn > 0f) {
            val slipWidth = min(260, width - 40)
            val x = width / 2 - slipWidth / 2
            val y = slipTop() + ((1f - slipIn) * 10f).toInt()
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLIP, x, y, slipWidth, SLIP_HEIGHT, ARGB.white(slipIn))
            val ink = ARGB.color((255 * slipIn).toInt(), 58, 38, 20)
            val lines = font.split(cause ?: Component.translatable("gui.guildmark.fallen.unknown"), slipWidth - 44)
            var lineY = y + SLIP_HEIGHT / 2 - lines.size * 5 + 1
            for (line in lines.take(3)) {
                graphics.text(font, line, width / 2 - font.width(line) / 2 - 8, lineY, ink, false)
                lineY += 10
            }
            // The seal comes down hard and settles
            if (t >= SEAL_AT) {
                val press = Mth.clamp((t - SEAL_AT) / 4f, 0f, 1f)
                val size = (19 * (1.8f - 0.8f * press)).toInt()
                val cx = x + slipWidth - 22
                val cy = y + SLIP_HEIGHT / 2
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SEAL, cx - size / 2, cy - size / 2, size, size, ARGB.white(0.4f + 0.6f * press))
            }
        }

        val words = Mth.clamp((t - 30f) / 16f, 0f, 1f)
        if (words > 0f) {
            graphics.centeredText(font, epitaph, width / 2, slipTop() + SLIP_HEIGHT + 12, ARGB.color((255 * words).toInt(), 0xB4, 0xA8, 0x8E))
        }
    }

    override fun shouldCloseOnEsc(): Boolean = false
    override fun isPauseScreen(): Boolean = false
    override fun isAllowedInPortal(): Boolean = true

    companion object {
        private val SLIP = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "death/slip")
        private val SEAL = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "death/seal")
        private const val SLIP_HEIGHT = 44
        private const val SEAL_AT = 34
        private const val BUTTONS_AT = 40
        private const val EPITAPHS = 6

        private val causeField by lazy {
            runCatching { DeathScreen::class.java.getDeclaredField("causeOfDeath").apply { isAccessible = true } }.getOrNull()
        }

        /** Swaps the vanilla death screen for this one as it opens. */
        fun onScreenOpening(event: ScreenEvent.Opening) {
            val screen = event.newScreen as? DeathScreen ?: return
            if (GuildmarkClientConfig.SPEC.isLoaded && !GuildmarkClientConfig.DEATH_SCREEN.get()) return
            val minecraft = net.minecraft.client.Minecraft.getInstance()
            val player = minecraft.player ?: return
            val cause = runCatching { causeField?.get(screen) as? Component }.getOrNull()
            val hardcore = minecraft.level?.levelData?.isHardcore ?: false
            event.newScreen = FallenScreen(cause, hardcore, player)
        }
    }
}
