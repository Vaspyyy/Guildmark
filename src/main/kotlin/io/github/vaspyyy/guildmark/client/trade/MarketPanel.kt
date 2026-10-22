package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.MarketView
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * A section of the market screen's list (living-world/trader/design.md, Parts 2 and 3): the Hauls section beside the Orders, the
 * Carriers section at the hall's counter. The screen is one big class, so it walks a list of these between the orders and the
 * goods instead of growing a section for each system: it asks each how tall it is, draws it, and passes it the mouse. A section keeps
 * no state of the screen's; what it needs of the server it is sent in the [MarketView] (or in a payload of its own), and what it draws
 * it draws from that.
 *
 * Sections are listed in [MarketPanels.ALL], one line each. None is listed yet, so the screen is as it was.
 */
interface MarketPanel {
    /**
     * Where the screen is when it asks something of a section: the [view] it was sent, its [font], the list's box and its rows' columns
     * (the list is clipped to [top] to [bottom], a row's text starts at [textX] and is [textWidth] wide, the buttons are
     * [buttonWidth] wide on the right edge of [x] to [x] + [width]) and the screen's age in [ticks].
     */
    class Frame(
        val view: MarketView,
        val font: Font,
        val x: Int,
        val width: Int,
        val textX: Int,
        val textWidth: Int,
        val buttonWidth: Int,
        val top: Int,
        val bottom: Int,
        val ticks: Int,
    )

    /** Whether this section belongs on the screen of the shop that [view] is for. Asked once, when the screen opens. */
    fun shownAt(view: MarketView): Boolean = true

    /** How tall the section is now, its heading included; 0 leaves it out of the list altogether (then it is neither drawn nor clicked). */
    fun height(view: MarketView): Int

    /** Draws the section with its top at [y] (the list is clipped to its box already), at [t] ticks of the screen's life. */
    fun render(graphics: GuiGraphicsExtractor, frame: Frame, y: Int, mouseX: Int, mouseY: Int, t: Float)

    /** A left click on the section, whose top is at [y]: whether it was used. [shift] is whether shift was held. */
    fun click(frame: Frame, y: Int, mouseX: Double, mouseY: Double, shift: Boolean): Boolean = false

    /** The tooltip for the point under the mouse, each line with its colour, or null if the section has nothing to say there. */
    fun tooltip(frame: Frame, y: Int, mouseX: Double, mouseY: Double): List<Pair<Component, Int>>? = null
}

/**
 * The sections one market screen shows, in order. The screen holds one of these and has it do the walking: height, drawing, clicks and
 * tooltips each go down the list, giving a section its place in it.
 */
class MarketPanels private constructor(private val list: List<MarketPanel>) {
    /** How tall they all are together. */
    fun height(view: MarketView): Int = list.sumOf { it.height(view) }

    /** Draws the sections one under another from [y]; answers how tall they were in all. */
    fun render(graphics: GuiGraphicsExtractor, frame: MarketPanel.Frame, y: Int, mouseX: Int, mouseY: Int, t: Float): Int {
        var at = y
        for (panel in list) {
            val h = panel.height(frame.view)
            if (h > 0 && at + h > frame.top && at < frame.bottom) panel.render(graphics, frame, at, mouseX, mouseY, t)
            at += h
        }
        return at - y
    }

    /** Offers a click to the section it fell on, the sections starting at [y]; whether one used it. */
    fun click(frame: MarketPanel.Frame, y: Int, mouseX: Double, mouseY: Double, shift: Boolean): Boolean {
        if (mouseY < frame.top || mouseY >= frame.bottom) return false
        var at = y
        for (panel in list) {
            val h = panel.height(frame.view)
            if (h > 0 && mouseY >= at && mouseY < at + h && panel.click(frame, at, mouseX, mouseY, shift)) return true
            at += h
        }
        return false
    }

    /** The tooltip of the section the mouse is over, the sections starting at [y], if it has one. */
    fun tooltip(frame: MarketPanel.Frame, y: Int, mouseX: Double, mouseY: Double): List<Pair<Component, Int>>? {
        if (mouseY < frame.top || mouseY >= frame.bottom) return null
        var at = y
        for (panel in list) {
            val h = panel.height(frame.view)
            if (h > 0 && mouseY >= at && mouseY < at + h) return panel.tooltip(frame, at, mouseX, mouseY)
            at += h
        }
        return null
    }

    companion object {
        /**
         * Every section, in the order they sit in the list. Each part adds one line after its own comment: A its Hauls section
         * (`HaulsPanel`), D1 its Carriers section (`CarriersPanel`).
         */
        private val ALL: List<MarketPanel> = listOf(
            // A: the Hauls section, beside the Orders

            // D: the Carriers section, at the hall's counter
            CarriersPanel,
        )

        /** The sections for the screen of the shop that [view] is for. */
        fun at(view: MarketView): MarketPanels = MarketPanels(ALL.filter { it.shownAt(view) })
    }
}
