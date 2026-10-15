package io.github.vaspyyy.guildmark.client.wardfire

import io.github.vaspyyy.guildmark.client.atlas.MapScreen
import io.github.vaspyyy.guildmark.client.ui.GrowScreen
import io.github.vaspyyy.guildmark.client.ui.LedgerScreen
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.network.OpenWardfirePayload
import io.github.vaspyyy.guildmark.network.WardfireActionPayload
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.wardfire.WardfireNames
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.min

/**
 * Sitting at a Wardfire. The world stays in view behind a warm shade from the left, where a small
 * panel names the fire and offers what can be done here: grow stronger with the guild XP banked, travel
 * to another fire, wait for morning or nightfall (when you're alone in the world), open the Guild Ledger
 * to learn and forget skill tree nodes, or get up and go.
 */
class WardfireScreen(private val pos: BlockPos, private val canWait: Boolean) : Screen(Component.translatable("gui.guildmark.wardfire")) {
    private val name: Component = Component.literal(WardfireNames.of(pos))
    private var ticks = 0
    private var waitButton: Ui.KitButton? = null
    private var growButton: Ui.KitButton? = null
    private var panelX = 0
    private var panelY = 0
    private var panelHeight = 0

    override fun init() {
        val buttons = ArrayList<Ui.KitButton>()
        val grow = Ui.button(growLabel(), BUTTON_WIDTH) { minecraft.gui.setScreen(GrowScreen(pos, this)) }
        growButton = grow
        buttons += grow
        buttons += Ui.button(Component.translatable("gui.guildmark.wardfire.travel"), BUTTON_WIDTH) {
            minecraft.gui.setScreen(MapScreen())
        }
        if (canWait) {
            val wait = Ui.button(waitLabel(), BUTTON_WIDTH) {
                ClientPacketDistributor.sendToServer(WardfireActionPayload(pos, WardfireActionPayload.WAIT))
            }
            waitButton = wait
            buttons += wait
        }
        buttons += Ui.button(Component.translatable("gui.guildmark.wardfire.ledger"), BUTTON_WIDTH) {
            minecraft.gui.setScreen(LedgerScreen(pos, this))
        }
        buttons += Ui.button(Component.translatable("gui.guildmark.wardfire.leave"), BUTTON_WIDTH) { onClose() }

        panelHeight = HEAD + buttons.size * (BUTTON_HEIGHT + 4) + 10
        panelX = min(PANEL_LEFT, width / 12)
        panelY = (height - panelHeight) / 2
        for ((i, button) in buttons.withIndex()) {
            button.setPosition(panelX + (PANEL_WIDTH - BUTTON_WIDTH) / 2, panelY + HEAD + i * (BUTTON_HEIGHT + 4))
            addRenderableWidget(button)
        }
    }

    /** Grow stronger, with the stat raises banked guild XP (and any owed from before) will pay for. */
    private fun growLabel(): Component {
        val player = minecraft.player ?: return Component.translatable("gui.guildmark.ledger.grow")
        val progress = player.getData(ModAttachments.GUILD_PROGRESS)
        val raises = progress.levelsBanked() + player.getData(ModAttachments.SHEET).owed(progress.level)
        return if (raises > 0) Component.translatable("gui.guildmark.ledger.grow.count", raises) else Component.translatable("gui.guildmark.ledger.grow")
    }

    private fun waitLabel(): Component = Component.translatable(
        if (minecraft.level?.isDarkOutside == true) "gui.guildmark.wardfire.wait_morning" else "gui.guildmark.wardfire.wait_night"
    )

    override fun added() {
        super.added()
        Ui.open()
    }

    override fun tick() {
        ticks++
        val player = minecraft.player
        // Struck, or carried off: the rest is over
        if (player == null || !player.isAlive || player.hurtTime > 0 || player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            onClose()
            return
        }
        waitButton?.message = waitLabel()
        growButton?.message = growLabel()
    }

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    /** Subtitles are drawn over this screen, not hidden under its shade. */
    override fun isInGameUi(): Boolean = true

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // A warm dusk from the left, the fire's side of the screen, fading out toward the middle
        val k = min(1f, (ticks + a) / 10f)
        val reach = width * 3 / 5
        val strips = 24
        for (i in 0 until strips) {
            val x0 = reach * i / strips
            val x1 = reach * (i + 1) / strips
            val fade = 1f - i / strips.toFloat()
            graphics.fill(x0, 0, x1, height, ARGB.color((150 * k * fade * fade).toInt(), 22, 12, 6))
        }
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        Ui.panel(graphics, panelX, panelY, PANEL_WIDTH, panelHeight)
        val center = panelX + PANEL_WIDTH / 2
        Ui.centeredHeading(graphics, font, name, center, panelY + 9, Ui.GOLD_BRIGHT)
        graphics.centeredText(font, Component.translatable("gui.guildmark.wardfire.kind"), center, panelY + 22, Ui.TEXT_SOFT)
        Ui.divider(graphics, panelX + 12, panelY + 33, PANEL_WIDTH - 24)
        graphics.centeredText(font, Component.translatable("gui.guildmark.wardfire.rested"), center, panelY + 40, Ui.TEXT)
        val player = minecraft.player
        val flask = player?.getData(ModAttachments.FLASK)
        if (flask != null && flask.owned) {
            graphics.centeredText(font, Component.translatable("gui.guildmark.wardfire.flask", flask.charges, flask.max), center, panelY + 51, Ui.GOLD)
        }
        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    companion object {
        private const val PANEL_LEFT = 28
        private const val PANEL_WIDTH = 176
        private const val BUTTON_WIDTH = 140
        private const val BUTTON_HEIGHT = 20
        /** Where the buttons start below the panel's top. */
        private const val HEAD = 66
        /** How far from the fire the menu stays open. */
        private const val REACH = 7.0

        fun open(payload: OpenWardfirePayload) {
            val minecraft = net.minecraft.client.Minecraft.getInstance()
            minecraft.gui.setScreen(WardfireScreen(payload.pos, payload.has(OpenWardfirePayload.CAN_WAIT)))
        }
    }
}
