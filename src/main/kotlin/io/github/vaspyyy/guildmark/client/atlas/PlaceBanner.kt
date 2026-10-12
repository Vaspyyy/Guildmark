package io.github.vaspyyy.guildmark.client.atlas

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.network.AtlasMark
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.min

/**
 * A place's name across the upper screen: large with "discovered" under it the first time you find
 * somewhere, and again (quieter, without the sound) when you come back after a while away.
 */
object PlaceBanner : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "place_banner")

    private const val FADE_IN = 0.6f
    private const val FADE_OUT = 1.2f
    /** A place's name shows again on arrival only after this long (game ticks) since it last did. */
    private const val REPEAT = 20L * 60 * 5

    private class Banner(val title: Component, val subtitle: Component, val start: Long, val seconds: Float)

    private var banner: Banner? = null
    /** The place the player is in, so arriving is noticed once. */
    private var inside: BlockPos? = null
    private val lastShown = HashMap<BlockPos, Long>()

    fun discovered(mark: AtlasMark) {
        val level = Minecraft.getInstance().level ?: return
        val subtitle = if (mark.kind == AtlasPlace.WARDFIRE) Component.translatable("atlas.guildmark.kindled")
            else Component.translatable("atlas.guildmark.discovered", AtlasClient.kindName(mark))
        show(mark, subtitle, 5.5f)
        inside = mark.pos
        lastShown[mark.pos] = level.gameTime
        Ui.play(ModSounds.UI_DISCOVER.get(), 1f, 0.8f)
    }

    private fun show(mark: AtlasMark, subtitle: Component, seconds: Float) {
        banner = Banner(AtlasClient.name(mark), subtitle, Util.getMillis(), seconds)
    }

    private fun arriveRadius(mark: AtlasMark): Double = when (mark.kind) {
        AtlasPlace.VILLAGE -> 40.0
        AtlasPlace.THRONE -> 32.0
        AtlasPlace.WARDFIRE -> 10.0
        else -> 20.0
    }

    /** Every tick: notice the player walking into a place they've found before. */
    fun tick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val marks = AtlasClient.marks()
        fun flatDistance(pos: BlockPos): Double {
            val dx = pos.x + 0.5 - player.x
            val dz = pos.z + 0.5 - player.z
            return Math.sqrt(dx * dx + dz * dz)
        }
        val current = inside?.let { pos -> marks.firstOrNull { it.pos == pos } }
        if (current == null || flatDistance(current.pos) > arriveRadius(current) * 2) inside = null
        if (inside != null) return
        val mark = marks.filter { flatDistance(it.pos) < arriveRadius(it) }.minByOrNull { flatDistance(it.pos) } ?: return
        inside = mark.pos
        val now = player.level().gameTime
        val last = lastShown[mark.pos]
        if (last != null && now - last in 0 until REPEAT) return
        lastShown[mark.pos] = now
        val subtitle = if (mark.holder.isNotEmpty()) Component.translatable("atlas.guildmark.held_place", AtlasClient.kindName(mark), mark.holder)
            else AtlasClient.kindName(mark)
        show(mark, subtitle, 4f)
    }

    fun reset() {
        banner = null
        inside = null
        lastShown.clear()
    }

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val shown = banner ?: return
        val minecraft = Minecraft.getInstance()
        val time = (Util.getMillis() - shown.start) / 1000f
        if (time > shown.seconds) {
            banner = null
            return
        }
        if (minecraft.gui.hud.isHidden) return
        val alpha = min(min(time / FADE_IN, (shown.seconds - time) / FADE_OUT), 1f).coerceIn(0f, 1f)
        if (alpha < 0.02f) return
        val font = minecraft.font
        val centerX = graphics.guiWidth() / 2
        val top = (graphics.guiHeight() * 0.24f).toInt()

        // The name in the carved capitals at twice the size
        val title = Ui.heading(shown.title)
        val width = font.width(title)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(centerX.toFloat(), top.toFloat())
        pose.scale(2f, 2f)
        graphics.text(font, title, -width / 2, Ui.HEADING_RISE, ARGB.color(alpha, Ui.GOLD_BRIGHT), true)
        pose.popMatrix()

        val rule = min(width * 2 + 48, graphics.guiWidth() - 40)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIVIDER, centerX - rule / 2, top + 23, rule, 3, alpha)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND, centerX - 2, top + 22, 5, 5, alpha)
        val subtitle = shown.subtitle
        graphics.text(font, subtitle, centerX - font.width(subtitle) / 2, top + 30, ARGB.color(alpha, Ui.TEXT_SOFT), true)
    }
}
