package io.github.vaspyyy.guildmark.client.magic

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.magic.AttunePayload
import io.github.vaspyyy.guildmark.magic.School
import io.github.vaspyyy.guildmark.magic.Spell
import io.github.vaspyyy.guildmark.magic.Spellbook
import io.github.vaspyyy.guildmark.progression.Talents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.Optional
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Attuning spells, at a Wardfire (or anywhere in a world without them). The slots run along the top, in the
 * order the spell key cycles them; below, every spell of both schools, the ones not yet learned dark. A click
 * on a learned spell attunes it into the next free slot or lets it go, a click on a slot empties it, and
 * Confirm makes it so.
 */
class AttuneScreen(private val restAt: BlockPos?, private val back: Screen?) : Screen(Component.translatable("gui.guildmark.attune")) {
    /** The spells lined up, by id, in slot order; null until the spellbook is first read. */
    private var picked: MutableList<String>? = null
    /** The attuned list last seen from the server: when it changes (a confirm landing), the lineup follows it. */
    private var seen: List<String>? = null
    private var ticks = 0
    private var panelX = 0
    private var panelY = 0
    private var confirm: Ui.KitButton? = null

    private fun lineup(): MutableList<String>? {
        val player = minecraft.player ?: return null
        val attuned = Spellbook.get(player).attuned
        if (picked == null || attuned != seen) {
            picked = attuned.toMutableList()
            seen = attuned
        }
        return picked
    }

    private fun slots(): Int = minecraft.player?.let(Spellbook::slots) ?: Spellbook.BASE_SLOTS

    // ---- Setup ----

    override fun init() {
        panelX = (width - PANEL_WIDTH) / 2
        panelY = max(4, (height - PANEL_HEIGHT) / 2)
        val footY = panelY + PANEL_HEIGHT - 28
        val center = panelX + PANEL_WIDTH / 2
        confirm = addRenderableWidget(Ui.button(Component.translatable("gui.guildmark.attune.confirm"), 96) { confirm() }.also {
            it.setPosition(center - 100, footY)
        })
        addRenderableWidget(Ui.button(Component.translatable("gui.guildmark.attune.back"), 96) { onClose() }.also {
            it.setPosition(center + 4, footY)
        })
        refresh()
    }

    private fun refresh() {
        val player = minecraft.player ?: return
        confirm?.active = lineup() != Spellbook.get(player).attuned
    }

    private fun confirm() {
        val lineup = lineup() ?: return
        ClientPacketDistributor.sendToServer(AttunePayload(Optional.ofNullable(restAt), lineup.toList()))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        val player = minecraft.player ?: return false
        val lineup = lineup() ?: return false
        val x = event.x().toInt()
        val y = event.y().toInt()
        slotAt(x, y)?.let { i ->
            if (i < lineup.size) {
                lineup.removeAt(i)
                Ui.select()
                refresh()
            }
            return true
        }
        val spell = spellAt(x, y) ?: return false
        val book = Spellbook.get(player)
        when {
            !book.knows(spell) -> return true
            spell.id in lineup -> lineup.remove(spell.id)
            lineup.size < slots() -> lineup.add(spell.id)
            else -> return true
        }
        Ui.select()
        refresh()
        return true
    }

    override fun added() {
        super.added()
        Ui.open()
    }

    override fun onClose() {
        Ui.close()
        val to = back
        if (to != null && minecraft.player?.isAlive == true) minecraft.gui.setScreen(to) else super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    /** Subtitles are drawn over this screen, not hidden under its shade. */
    override fun isInGameUi(): Boolean = true

    override fun tick() {
        ticks++
        val player = minecraft.player
        // Struck, or carried off from the fire: the rest is over
        if (player == null || !player.isAlive || restAt != null && (player.hurtTime > 0 || player.distanceToSqr(Vec3.atCenterOf(restAt)) > REACH * REACH)) {
            Ui.close()
            minecraft.gui.setScreen(null)
            return
        }
        refresh()
    }

    // ---- Where things are ----

    private fun slotsLeft(): Int = panelX + (PANEL_WIDTH - (Spellbook.MAX_SLOTS * CELL + (Spellbook.MAX_SLOTS - 1) * GAP)) / 2

    private fun slotAt(x: Int, y: Int): Int? {
        val top = panelY + SLOTS_TOP
        if (y < top || y >= top + CELL) return null
        val i = (x - slotsLeft()).floorDiv(CELL + GAP)
        if (i !in 0 until Spellbook.MAX_SLOTS || x - slotsLeft() - i * (CELL + GAP) >= CELL) return null
        return i
    }

    private fun rowTop(school: School): Int = panelY + ROWS_TOP + school.ordinal * ROW

    private fun spellsLeft(): Int = panelX + PAD + LABEL_WIDTH

    private fun spellAt(x: Int, y: Int): Spell? {
        for (school in School.entries) {
            val top = rowTop(school)
            if (y < top || y >= top + CELL) continue
            val i = (x - spellsLeft()).floorDiv(CELL + GAP)
            if (x - spellsLeft() - i * (CELL + GAP) >= CELL) return null
            return Spell.entries.filter { it.school == school }.getOrNull(i)
        }
        return null
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val k = min(1f, (ticks + a) / 5f)
        graphics.fill(0, 0, width, height, ARGB.color((150 * k).toInt(), 6, 4, 2))
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val player = minecraft.player ?: return
        val lineup = lineup() ?: return
        val book = Spellbook.get(player)
        val slots = slots()
        Ui.panel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT)
        val center = panelX + PANEL_WIDTH / 2
        val x = panelX + PAD
        val w = PANEL_WIDTH - PAD * 2
        Ui.centeredHeading(graphics, font, Component.translatable("gui.guildmark.attune"), center, panelY + 9, Ui.GOLD_BRIGHT)
        graphics.centeredText(font, Component.translatable("gui.guildmark.attune.slots", lineup.size, slots), center, panelY + 23, Ui.TEXT_SOFT)
        Ui.divider(graphics, x, panelY + 34, w)

        var tip: List<Pair<Component, Int>>? = null
        var tipY = 0
        // The slots, in the order the spell key cycles them; those the tree hasn't opened yet faint
        val top = panelY + SLOTS_TOP
        for (i in 0 until Spellbook.MAX_SLOTS) {
            val sx = slotsLeft() + i * (CELL + GAP)
            val open = i < slots
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SLOT, sx, top, CELL, CELL, if (open) 1f else 0.3f)
            val spell = lineup.getOrNull(i)?.let(Spell::byId)
            val hovered = mouseX >= sx && mouseX < sx + CELL && mouseY >= top && mouseY < top + CELL
            if (spell != null) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SpellHud.icon(spell), sx + 1, top + 1, 16, 16)
                if (hovered) graphics.outline(sx, top, CELL, CELL, Ui.GOLD_BRIGHT)
            }
            if (hovered) {
                tip = when {
                    spell != null -> spellTip(spell, inSlot = true)
                    open -> listOf(Component.translatable("gui.guildmark.attune.empty") to Ui.TEXT_SOFT)
                    else -> listOf(Component.translatable("gui.guildmark.attune.locked") to Ui.TEXT_SOFT)
                }
                tipY = top
            }
        }
        Ui.divider(graphics, x, panelY + SLOTS_TOP + CELL + 8, w, gem = false)

        // Every spell, by school: learned ones bright, attuned ones framed in their school's colour
        for (school in School.entries) {
            val rowY = rowTop(school)
            graphics.text(font, school.title, x, rowY + (CELL - 8) / 2, school.color, false)
            for ((i, spell) in Spell.entries.filter { it.school == school }.withIndex()) {
                val sx = spellsLeft() + i * (CELL + GAP)
                val known = book.knows(spell)
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SLOT, sx, rowY, CELL, CELL)
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SpellHud.icon(spell), sx + 1, rowY + 1, 16, 16, if (known) -1 else UNKNOWN_TINT)
                val hovered = mouseX >= sx && mouseX < sx + CELL && mouseY >= rowY && mouseY < rowY + CELL
                when {
                    spell.id in lineup -> graphics.outline(sx, rowY, CELL, CELL, if (hovered) Ui.GOLD_BRIGHT else school.color)
                    hovered && known -> graphics.outline(sx, rowY, CELL, CELL, Ui.TEXT_SOFT)
                }
                if (hovered) {
                    tip = spellTip(spell, inSlot = false)
                    tipY = rowY
                }
            }
        }
        val hint = Component.translatable("gui.guildmark.attune.hint", MagicClient.NEXT_SPELL.translatedKeyMessage)
        for ((i, line) in font.split(hint, w).take(2).withIndex()) {
            graphics.centeredText(font, line, center, panelY + HINT_TOP + i * 10, Ui.TEXT_FAINT)
        }
        super.extractRenderState(graphics, mouseX, mouseY, a)
        tip?.let { drawTip(graphics, it, tipY) }
    }

    /** What [spell] is, what it takes, and what a click does to it. */
    private fun spellTip(spell: Spell, inSlot: Boolean): List<Pair<Component, Int>> {
        val player = minecraft.player ?: return listOf()
        val lineup = lineup() ?: return listOf()
        val known = Spellbook.get(player).knows(spell)
        val lines = arrayListOf<Pair<Component, Int>>(spell.title to if (known) spell.school.color else Ui.TEXT_SOFT)
        if (!known) {
            lines.add(Component.translatable("gui.guildmark.attune.unknown") to Ui.TEXT_FAINT)
            lines.add(Component.translatable("gui.guildmark.attune.unknown.${spell.school.id}") to Ui.TEXT_FAINT)
            return lines
        }
        lines.add(spell.description to Ui.TEXT_SOFT)
        lines.add(Component.translatable("gui.guildmark.attune.cost", Talents.focusCost(player, spell.move).roundToInt()) to Ui.BLUE)
        val met = Talents.sheet(player).stat(spell.school.stat) >= spell.need
        lines.add(Component.translatable("gui.guildmark.attune.needs", spell.school.stat.title, spell.need) to if (met) Ui.TEXT_FAINT else Ui.RED)
        if (spell.chargeable) lines.add(Component.translatable("gui.guildmark.attune.charge") to Ui.TEXT_FAINT)
        val action = when {
            inSlot || spell.id in lineup -> Component.translatable("gui.guildmark.attune.release") to Ui.GOLD
            lineup.size < slots() -> Component.translatable("gui.guildmark.attune.add") to Ui.GREEN
            else -> Component.translatable("gui.guildmark.attune.full") to Ui.RED
        }
        lines.add(action)
        return lines
    }

    /** The lines in a small dark panel beside the panel, level with the row at [rowY]. */
    private fun drawTip(graphics: GuiGraphicsExtractor, lines: List<Pair<Component, Int>>, rowY: Int) {
        val wrapped = lines.flatMap { (text, color) -> font.split(text, TIP_WIDTH).map { it to color } }
        if (wrapped.isEmpty()) return
        val w = wrapped.maxOf { font.width(it.first) } + 16
        val h = wrapped.size * 10 + 10
        var x = panelX + PANEL_WIDTH + 4
        if (x + w > width - 4) x = max(4, panelX - 4 - w)
        val y = (rowY - 4).coerceIn(4, max(4, height - h - 4))
        graphics.nextStratum()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, x, y, w, h)
        for ((i, line) in wrapped.withIndex()) graphics.text(font, line.first, x + 8, y + 6 + i * 10, line.second, false)
    }

    companion object {
        private const val PANEL_WIDTH = 252
        private const val PANEL_HEIGHT = 196
        private const val PAD = 16
        /** A slot, at its art's size, and the room between two. */
        private const val CELL = 18
        private const val GAP = 6
        private const val SLOTS_TOP = 44
        private const val ROWS_TOP = 80
        private const val ROW = 26
        private const val LABEL_WIDTH = 66
        private const val HINT_TOP = 134
        private const val TIP_WIDTH = 184
        private const val REACH = 6.0
        /** A spell not yet learned: its icon a dark shape. */
        private val UNKNOWN_TINT = 0xFF2E2A26.toInt()
    }
}
