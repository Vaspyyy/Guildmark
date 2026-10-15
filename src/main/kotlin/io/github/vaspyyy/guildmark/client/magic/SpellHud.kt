package io.github.vaspyyy.guildmark.client.magic

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.magic.CatalystItem
import io.github.vaspyyy.guildmark.magic.Magic
import io.github.vaspyyy.guildmark.magic.Spell
import io.github.vaspyyy.guildmark.magic.Spellbook
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.roundToInt

/**
 * The spell's quick slot, low on the left beside the flask's: the spell a cast press makes, greyed when it
 * can't be cast as things stand (no catalyst of its school in hand, or too little of its stat), with the
 * focus it costs. A row of pips above counts the spells attuned and marks the one picked, and the spell's
 * name shows over it for a moment when the pick changes. It shows while a catalyst is in hand.
 */
object SpellHud : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "spell_slot")
    private val SLOT = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/flask_slot")

    private const val SIZE = 24
    private const val MARGIN = 6
    private const val FOCUS_COLOR = 0xFF86A9E6.toInt()
    /** Ticks the spell's name shows after the pick changes, the last of them fading. */
    private const val NAME_TICKS = 50
    private const val NAME_FADE = 12

    fun icon(spell: Spell): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "spell/${spell.id}")

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.gui.hud.isHidden || player.isSpectator) return
        val since = MagicClient.ticks - MagicClient.changedAt
        if (!CatalystItem.holdsAny(player) && since > NAME_TICKS) return
        val spell = MagicClient.chosen(player) ?: return
        val x = MARGIN + if (player.getExistingDataOrNull(ModAttachments.FLASK)?.owned == true) SIZE + 4 else 0
        val y = graphics.guiHeight() - SIZE - 1
        val ready = Magic.problem(player, spell) == null
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, x, y, SIZE, SIZE)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon(spell), x + 4, y + 4, 16, 16, if (ready) -1 else 0xFF6A6A6A.toInt())
        val font = minecraft.font
        val cost = Talents.focusCost(player, spell.move).roundToInt().toString()
        graphics.text(font, cost, x + SIZE - 3 - font.width(cost), y + SIZE - 11, if (ready) FOCUS_COLOR else 0xFF7D7466.toInt(), true)

        // A pip for each spell attuned, the one picked bright
        val attuned = Spellbook.get(player).attunedSpells()
        if (attuned.size > 1) {
            val width = attuned.size * 4 - 1
            val left = x + (SIZE - width) / 2
            for ((i, each) in attuned.withIndex()) {
                val color = if (each == spell) each.school.color else ARGB.color(0x90, each.school.color and 0xFFFFFF)
                graphics.fill(left + i * 4, y - 3, left + i * 4 + 3, y - 1, color)
            }
        }
        if (since <= NAME_TICKS) {
            val alpha = ((NAME_TICKS - since).toFloat() / NAME_FADE).coerceIn(0f, 1f)
            if (alpha > 0.05f) graphics.text(font, spell.title, x, y - 13, ARGB.color(alpha, spell.school.color and 0xFFFFFF), true)
        }
    }
}
