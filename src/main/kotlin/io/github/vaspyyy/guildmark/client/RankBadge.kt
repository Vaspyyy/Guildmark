package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** The gold-rimmed shield with an adventurer's rank letter on it. */
object RankBadge {
    const val WIDTH = 22
    const val HEIGHT = 24
    private val SPRITE: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ledger/rank_badge")

    fun draw(graphics: GuiGraphicsExtractor, font: Font, rank: AdventurerRank, x: Int, y: Int) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITE, x, y, WIDTH, HEIGHT)
        val letter = Component.literal(rank.letter)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x + WIDTH / 2.0f, y + 5.0f)
        pose.scale(1.5f, 1.5f)
        graphics.text(font, letter, -font.width(letter) / 2, 0, rank.color, false)
        pose.popMatrix()
    }
}
