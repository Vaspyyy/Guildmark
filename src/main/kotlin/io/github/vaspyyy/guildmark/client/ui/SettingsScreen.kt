package io.github.vaspyyy.guildmark.client.ui

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.SpriteIconButton
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.sounds.SoundManager
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Util
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.gui.widget.ModsButton
import net.neoforged.neoforge.common.ModConfigSpec
import java.util.Optional
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Guildmark's settings, in the kit's look: what this player sees and hears (Interface), the rules of
 * the worlds they play (World), and the mods Guildmark plays well with (Companions), with SeedGen's
 * switch, each companion's own settings, and in a dev run which of them load next. Opened from the
 * crest by the Mods button on the title screen and the pause menu, and from Mods > Guildmark > Config.
 */
class SettingsScreen(private val parent: Screen?) : Screen(Component.translatable("guildmark.configuration.title")) {
    enum class Page(val key: String) { INTERFACE("interface"), WORLD("world"), COMPANIONS("companions") }

    private var page = lastPage
    private var panelX = 0
    private var panelY = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var bodyX = 0
    private var bodyTop = 0
    private var bodyWidth = 0
    private var bodyBottom = 0
    private var ticks = 0

    private val rows = ArrayList<Row>()
    /** The page's own widgets, drawn clipped to the body and only taking the mouse inside it. */
    private val rowWidgets = HashSet<GuiEventListener>()
    /** How far down the page the body shows, and where the wheel is gliding it to. */
    private var scrolled = 0.0
    private var scrollTo = 0.0
    private var glidedAt = 0L
    /** The scrollbar's thumb is being dragged. */
    private var draggingBar = false
    private val tabs = ArrayList<Tab>()

    /** Something hovering explains, found while drawing. */
    private class Hot(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val tip: Component)

    private val hots = ArrayList<Hot>()
    /** Settings moved by a slider, saved as the screen goes rather than at every step of a drag. */
    private val unsaved = HashSet<ModConfigSpec>()

    // ---- Rows ----

    /** One line of a page, [height] tall, with the widgets it places at offsets from its corner. */
    private abstract inner class Row(var height: Int) {
        val parts = ArrayList<Part>()
        /** Down from the top of the page, and on the screen as scrolled. */
        var top = 0
        var y = 0

        open fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int) {}
    }

    private class Part(val widget: AbstractWidget, val dx: Int, val dy: Int, val fromRight: Boolean)

    private inner class Header(private val title: Component) : Row(HEADER) {
        override fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int) {
            Ui.headingAt(graphics, font, title, x, y + 4)
            graphics.fill(x, y + 15, x + w, y + 16, RULE)
        }
    }

    private inner class Note(text: Component, private val color: Int, width: Int) : Row(0) {
        private val lines: List<FormattedCharSequence> = font.split(text, width - 4)

        init {
            height = lines.size * 10 + 6
        }

        override fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int) {
            for ((i, line) in lines.withIndex()) graphics.text(font, line, x + 2, y + 3 + i * 10, color, false)
        }
    }

    /** A setting: its name on the left, its control on the right, and hovering it says what it does. */
    private inner class Setting(private val label: Component, private val tip: Component?, control: AbstractWidget) : Row(SETTING) {
        init {
            parts.add(Part(control, control.width, (SETTING - control.height) / 2, true))
        }

        override fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int) {
            val control = parts[0].widget
            val room = w - control.width - 12
            val full = label.string
            val shown = if (font.width(full) <= room) full else font.plainSubstrByWidth(full, room - font.width(ELLIPSIS)).trimEnd() + ELLIPSIS
            graphics.text(font, shown, x + 2, y + (SETTING - 8) / 2, if (control.active) Ui.TEXT else Ui.TEXT_SOFT, false)
            tip?.let { hots.add(Hot(x, y, x + w, y + SETTING, it)) }
        }
    }

    /** A companion mod: its name and how it stands, what it does with Guildmark, and what can be done about it. */
    private inner class Card(private val mod: CompanionMod, width: Int) : Row(0) {
        private val name = Component.translatable("gui.guildmark.settings.companion.${mod.id}.name")
        private val about: List<FormattedCharSequence> = font.split(Component.translatable("gui.guildmark.settings.companion.${mod.id}"), width - 4)
        private val switchLabel: Component?
        private val switchTip: Component?

        init {
            height = 14 + about.size * 10 + 4 + CARD_BUTTONS + 8
            val buttonsY = 14 + about.size * 10 + 4
            var left = 2
            if (mod.loaded && mod.hasSettings()) {
                val settings = Ui.KitButton(0, 0, 64, CARD_BUTTONS, Component.translatable("gui.guildmark.settings.open")) {
                    mod.settings(this@SettingsScreen)?.let { minecraft.gui.setScreen(it) }
                }
                parts.add(Part(settings, left, buttonsY, false))
                left += settings.width + 4
            }
            if (!mod.loaded && !DevRuns.offered) {
                val get = Ui.KitButton(0, 0, 64, CARD_BUTTONS, Component.translatable("gui.guildmark.settings.get")) {
                    ConfirmLinkScreen.confirmLinkNow(this@SettingsScreen, mod.page)
                }
                parts.add(Part(get, left, buttonsY, false))
            }
            // SeedGen's own switch, or in a dev run whether the next one loads this mod
            var label: Component? = null
            var tip: Component? = null
            if (mod == CompanionMod.SEEDGEN && mod.loaded && SeedGen.enabled() != null) {
                label = Component.translatable("gui.guildmark.settings.seedgen")
                tip = Component.translatable("gui.guildmark.settings.seedgen.tip")
                val switch = Switch(label, { SeedGen.enabled() == true }) { SeedGen.setEnabled(it) }
                parts.add(Part(switch, switch.width, buttonsY + (CARD_BUTTONS - switch.height) / 2, true))
            } else if (DevRuns.offered && mod.devKey != null) {
                label = Component.translatable("gui.guildmark.settings.load")
                tip = Component.translatable("gui.guildmark.settings.load.tip", mod.devKey)
                val switch = Switch(label, { DevRuns.chosen(mod) }) { DevRuns.choose(mod, it) }
                parts.add(Part(switch, switch.width, buttonsY + (CARD_BUTTONS - switch.height) / 2, true))
            }
            switchLabel = label
            switchTip = tip
        }

        private fun status(): Pair<Component, Int> {
            val key = when {
                // Iris won't load without Sodium, and the dev runs leave it out then
                mod == CompanionMod.IRIS && DevRuns.offered && DevRuns.chosen(mod) && !DevRuns.chosen(CompanionMod.SODIUM) -> "needs_sodium"
                DevRuns.pending(mod) -> if (DevRuns.chosen(mod)) "loads_next" else "gone_next"
                !mod.loaded -> when {
                    !DevRuns.offered -> "missing"
                    mod.devKey != null || CompanionMod.DISTANT_HORIZONS.loaded -> "off"
                    else -> "with_dh"
                }
                mod == CompanionMod.SEEDGEN && !CompanionMod.DISTANT_HORIZONS.loaded -> "needs_dh"
                mod == CompanionMod.SEEDGEN -> when (SeedGen.enabled()) {
                    true -> "on"
                    false -> "off"
                    null -> "loaded"
                }
                else -> "loaded"
            }
            val color = when (key) {
                "loaded", "on" -> Ui.GREEN
                "loads_next", "gone_next" -> Ui.GOLD_BRIGHT
                "needs_dh", "needs_sodium" -> Ui.RED
                "off" -> Ui.TEXT_SOFT
                else -> Ui.TEXT_FAINT
            }
            return Component.translatable("gui.guildmark.settings.status.$key") to color
        }

        override fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int) {
            Ui.headingAt(graphics, font, name, x + 2, y + 1, Ui.GOLD)
            val (status, color) = status()
            val statusX = x + w - font.width(status) - 2
            graphics.text(font, status, statusX, y + 3, color, false)
            graphics.fill(statusX - 7, y + 5, statusX - 3, y + 9, color)
            for ((i, line) in about.withIndex()) graphics.text(font, line, x + 2, y + 15 + i * 10, Ui.TEXT_SOFT, false)
            val switch = parts.lastOrNull()?.takeIf { it.fromRight }?.widget ?: return
            val label = switchLabel ?: return
            val labelX = switch.x - 6 - font.width(label)
            graphics.text(font, label, labelX, switch.y + (switch.height - 8) / 2, if (switch.active) Ui.TEXT else Ui.TEXT_SOFT, false)
            switchTip?.let { hots.add(Hot(labelX - 2, switch.y - 3, switch.x + switch.width, switch.y + switch.height + 3, it)) }
        }
    }

    // ---- Building a page ----

    /** A switch for a setting in [spec], saved as soon as it's flipped. */
    private fun toggle(value: ModConfigSpec.BooleanValue, spec: ModConfigSpec, editable: Boolean): Setting {
        val label = label(value)
        val switch = Switch(label, { spec.isLoaded && value.get() }) {
            value.set(it)
            spec.save()
        }
        switch.active = editable
        return Setting(label, tip(value), switch)
    }

    /** A slider for a number in [spec], moving through [steps]; saved as the screen closes. */
    private fun slider(value: ModConfigSpec.ConfigValue<out Number>, spec: ModConfigSpec, editable: Boolean, steps: List<Double>, text: (Double) -> String, set: (Double) -> Unit): Setting {
        val label = label(value)
        val start = if (spec.isLoaded) value.get().toDouble() else value.default.toDouble()
        val slider = Slider(label, steps, start, text) { set(it); unsaved.add(spec) }
        slider.active = editable
        return Setting(label, tip(value), slider)
    }

    private fun label(value: ModConfigSpec.ConfigValue<*>): Component = Component.translatable("guildmark.configuration.${value.path.last()}")

    private fun tip(value: ModConfigSpec.ConfigValue<*>): Component? = value.spec.comment?.takeIf { it.isNotBlank() }?.let { Component.literal(it) }

    private fun header(key: String) = Header(Component.translatable("gui.guildmark.settings.section.$key"))

    private fun interfacePage(width: Int): List<Row> {
        val c = GuildmarkClientConfig
        val spec = c.SPEC
        val city = listOf(c.CITY_SOUNDS, c.CITY_BELLS, c.CITY_CROWDS, c.CITY_WORKSHOPS, c.CITY_HARBOUR, c.CITY_WIND)
        val listed = listOf(c.COMPASS, c.CONTRACT_TRACKER, c.PLACE_BANNERS, c.REGION_TITLES, c.REGION_ATMOSPHERE, c.COMBAT_HUD, c.DEATH_SCREEN, c.TITLE_SCREEN, c.CAMERA_SHAKE, c.CUTSCENES, c.DIALOGUE_CAMERA, c.MENU_SOUNDS, c.MENU_MUSIC, c.REGION_MUSIC, c.ISLAND_SOUNDS) + city
        return listOf(
            header("screen"),
            toggle(c.COMPASS, spec, true),
            toggle(c.CONTRACT_TRACKER, spec, true),
            toggle(c.PLACE_BANNERS, spec, true),
            toggle(c.REGION_TITLES, spec, true),
            toggle(c.REGION_ATMOSPHERE, spec, true),
            toggle(c.COMBAT_HUD, spec, true),
            toggle(c.DEATH_SCREEN, spec, true),
            toggle(c.TITLE_SCREEN, spec, true),
            header("camera"),
            toggle(c.CAMERA_SHAKE, spec, true),
            toggle(c.CUTSCENES, spec, true),
            toggle(c.DIALOGUE_CAMERA, spec, true),
            header("sound"),
            toggle(c.MENU_SOUNDS, spec, true),
            toggle(c.MENU_MUSIC, spec, true),
            toggle(c.REGION_MUSIC, spec, true),
            slider(c.ISLAND_SOUNDS, spec, true, PERCENTS, ::percent) { c.ISLAND_SOUNDS.set(it.roundToInt()) },
            header("city"),
        ) + city.map { value -> slider(value, spec, true, PERCENTS, ::percent) { value.set(it.roundToInt()) } } + others(c, spec, listed, true)
    }

    private fun worldPage(width: Int): List<Row> {
        val c = GuildmarkConfig
        val spec = c.SPEC
        val local = minecraft.singleplayerServer?.isPublished == true
        val note = when {
            !spec.isLoaded -> "unloaded"
            minecraft.currentServer != null && !local -> "online"
            local -> "lan"
            else -> "local"
        }
        // As NeoForge's own settings screen allows: only where nobody else is playing by these rules
        val editable = note == "local"
        val listed = listOf(
            c.COMBAT, c.LOSE_MARKS, c.PAY_MULTIPLIER, c.XP_MULTIPLIER, c.SKILL_XP_MULTIPLIER, c.RELIC_CACHE_CHANCE, c.HANDBOOK, c.AUTO_BOARDS, c.WARDFIRES,
            c.DUNGEONS, c.FIELD_BOSSES, c.CAPITALS, c.ISLANDS, c.DUSK_RIDERS, c.DRAGONS, c.SEA_MONSTERS, c.SIEGES, c.SIEGE_CHANCE, c.BANDITS, c.OUTLAWS, c.RIVALS, c.CHARACTERS,
            c.TOWNSFOLK_CROWDS, c.TOWNSFOLK_CROWD_DENSITY, c.REGIONS, c.REGION_TERRAIN, c.REGION_ENEMIES,
        )
        val rows = arrayListOf<Row>(Note(Component.translatable("gui.guildmark.settings.world.$note"), if (editable) Ui.TEXT_FAINT else Ui.GOLD, width))
        if (!spec.isLoaded) return rows
        rows += listOf(
            header("combat"),
            toggle(c.COMBAT, spec, editable),
            toggle(c.LOSE_MARKS, spec, editable),
            header("rewards"),
            slider(c.PAY_MULTIPLIER, spec, editable, MULTIPLIERS, ::times) { c.PAY_MULTIPLIER.set(it) },
            slider(c.XP_MULTIPLIER, spec, editable, MULTIPLIERS, ::times) { c.XP_MULTIPLIER.set(it) },
            slider(c.SKILL_XP_MULTIPLIER, spec, editable, MULTIPLIERS, ::times) { c.SKILL_XP_MULTIPLIER.set(it) },
            slider(c.RELIC_CACHE_CHANCE, spec, editable, CHANCES, ::percentOf) { c.RELIC_CACHE_CHANCE.set(it) },
            toggle(c.HANDBOOK, spec, editable),
            header("world"),
            toggle(c.AUTO_BOARDS, spec, editable),
            toggle(c.WARDFIRES, spec, editable),
            toggle(c.DUNGEONS, spec, editable),
            toggle(c.FIELD_BOSSES, spec, editable),
            toggle(c.CAPITALS, spec, editable),
            toggle(c.ISLANDS, spec, editable),
            toggle(c.REGIONS, spec, editable),
            toggle(c.REGION_TERRAIN, spec, editable),
            toggle(c.TOWNSFOLK_CROWDS, spec, editable),
            slider(c.TOWNSFOLK_CROWD_DENSITY, spec, editable, MULTIPLIERS.filter { it in 0.25..3.0 }, ::times) { c.TOWNSFOLK_CROWD_DENSITY.set(it) },
            toggle(c.DUSK_RIDERS, spec, editable),
            toggle(c.DRAGONS, spec, editable),
            toggle(c.SEA_MONSTERS, spec, editable),
            header("threats"),
            toggle(c.SIEGES, spec, editable),
            slider(c.SIEGE_CHANCE, spec, editable, PERCENTS, ::percent) { c.SIEGE_CHANCE.set(it.roundToInt()) },
            toggle(c.BANDITS, spec, editable),
            toggle(c.OUTLAWS, spec, editable),
            toggle(c.RIVALS, spec, editable),
            toggle(c.REGION_ENEMIES, spec, editable),
            header("story"),
            toggle(c.CHARACTERS, spec, editable),
        )
        rows += others(c, spec, listed, editable)
        return rows
    }

    /**
     * Any setting [holder] declares that the page above doesn't place yet, found by looking through its
     * fields, so one added later is never out of reach.
     */
    private fun others(holder: Any, spec: ModConfigSpec, listed: List<ModConfigSpec.ConfigValue<*>>, editable: Boolean): List<Row> {
        val rows = ArrayList<Row>()
        val declared = holder.javaClass.declaredFields.mapNotNull { field ->
            if (!ModConfigSpec.ConfigValue::class.java.isAssignableFrom(field.type)) return@mapNotNull null
            try {
                field.isAccessible = true
                field.get(holder) as? ModConfigSpec.ConfigValue<*>
            } catch (e: ReflectiveOperationException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }
        for (value in declared) {
            if (listed.any { it === value }) continue
            val row: Row = when (value) {
                is ModConfigSpec.BooleanValue -> toggle(value, spec, editable)
                is ModConfigSpec.IntValue -> {
                    val range = value.spec.getRange<Int>() ?: continue
                    slider(value, spec, editable, linear(range.min.toDouble(), range.max.toDouble(), true), ::plain) { value.set(it.roundToInt()) }
                }
                is ModConfigSpec.DoubleValue -> {
                    val range = value.spec.getRange<Double>() ?: continue
                    slider(value, spec, editable, linear(range.min, range.max, false), ::plain) { value.set(it) }
                }
                else -> continue
            }
            if (rows.isEmpty()) rows.add(header("other"))
            rows.add(row)
        }
        return rows
    }

    private fun companionsPage(width: Int): List<Row> {
        val rows = arrayListOf<Row>(Note(Component.translatable("gui.guildmark.settings.companions.note"), Ui.TEXT_FAINT, width))
        if (DevRuns.offered) rows.add(Note(Component.translatable("gui.guildmark.settings.companions.dev"), Ui.GOLD, width))
        for (mod in CompanionMod.entries) rows.add(Card(mod, width))
        return rows
    }

    // ---- The screen ----

    override fun init() {
        panelWidth = min(MAX_WIDTH, width - 16)
        panelHeight = min(MAX_HEIGHT, height - 20)
        panelX = (width - panelWidth) / 2
        panelY = (height - panelHeight) / 2 + 4
        bodyX = panelX + PAD
        bodyTop = panelY + BODY_TOP
        bodyWidth = panelWidth - PAD * 2 - SCROLLBAR
        bodyBottom = panelY + panelHeight - FOOTER

        tabs.clear()
        val labels = Page.entries.map { Ui.heading(Component.translatable("gui.guildmark.settings.page.${it.key}")) }
        val tabWidths = labels.map { font.width(it) + 12 }
        var tabX = panelX + (panelWidth - tabWidths.sum() - TAB_GAP * (labels.size - 1)) / 2
        for ((i, which) in Page.entries.withIndex()) {
            val tab = Tab(which, labels[i], tabWidths[i])
            tab.setPosition(tabX, panelY + TABS_Y)
            tabs.add(addRenderableWidget(tab))
            tabX += tabWidths[i] + TAB_GAP
        }

        rows.clear()
        rows.addAll(when (page) {
            Page.INTERFACE -> interfacePage(bodyWidth)
            Page.WORLD -> worldPage(bodyWidth)
            Page.COMPANIONS -> companionsPage(bodyWidth)
        })
        rowWidgets.clear()
        var top = 0
        for (row in rows) {
            row.top = top
            top += row.height
            for (part in row.parts) rowWidgets.add(addWidget(part.widget))
        }
        // Coming back from a companion's own settings (or a resize) keeps the place on the page
        scrollTo = scrollTo.coerceIn(0.0, maxScroll())
        scrolled = scrollTo
        draggingBar = false
        place()

        val done = Ui.KitButton(width / 2 - 60, panelY + panelHeight - 26, 120, 20, CommonComponents.GUI_DONE) { onClose() }
        addRenderableWidget(done)
    }

    private fun bodyHeight(): Int = bodyBottom - bodyTop

    private fun maxScroll(): Double = max(0, (rows.lastOrNull()?.let { it.top + it.height } ?: 0) - bodyHeight()).toDouble()

    /**
     * Puts every row where the scroll has it. Widgets out of the body stay where Tab can reach them (and
     * reaching one scrolls it into view); drawing is clipped to the body, and so is the mouse.
     */
    private fun place() {
        val offset = scrolled.roundToInt()
        val settled = scrollTo.roundToInt()
        for (row in rows) {
            row.y = bodyTop + row.top - offset
            for (part in row.parts) {
                val widget = part.widget
                widget.setPosition(if (part.fromRight) bodyX + bodyWidth - part.dx else bodyX + part.dx, row.y + part.dy)
                // A slider scrolled out of sight mustn't go on taking arrow keys or a drag
                val y = bodyTop + row.top - settled + part.dy
                if (getFocused() === widget && (y + widget.height <= bodyTop || y >= bodyBottom)) {
                    clearFocus()
                    setDragging(false)
                }
            }
        }
    }

    private fun scrollBy(by: Double) {
        scrollTo = (scrollTo + by).coerceIn(0.0, maxScroll())
    }

    /** Eases the page toward where the wheel sent it. */
    private fun glide() {
        val now = Util.getMillis()
        val dt = (now - glidedAt).coerceIn(0L, 100L)
        glidedAt = now
        val gap = scrollTo - scrolled
        scrolled = if (abs(gap) < 0.5) scrollTo else scrolled + gap * (1.0 - exp(-dt / GLIDE_MS))
    }

    private fun turnTo(next: Page) {
        if (next == page) return
        page = next
        lastPage = next
        scrolled = 0.0
        scrollTo = 0.0
        rebuildWidgets()
        // Chosen from the keyboard: stay on its tab, so the next key doesn't land on the first one
        if (minecraft.lastInputType.isKeyboard) setInitialFocus(tabs[page.ordinal])
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY != 0.0) scrollBy(-scrollY * WHEEL_STEP)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            InputConstants.KEY_PAGEUP -> { scrollBy(-(bodyHeight() - PAGE_OVERLAP).toDouble()); return true }
            InputConstants.KEY_PAGEDOWN -> { scrollBy((bodyHeight() - PAGE_OVERLAP).toDouble()); return true }
        }
        return super.keyPressed(event)
    }

    // The page's widgets can be partly scrolled out of the body: only the part inside it takes the mouse
    override fun getChildAt(x: Double, y: Double): Optional<GuiEventListener> {
        val inBody = y >= bodyTop && y < bodyBottom
        return Optional.ofNullable(children().firstOrNull { (inBody || it !in rowWidgets) && it.isMouseOver(x, y) })
    }

    // Reached from the keyboard, a setting's whole row is scrolled into view
    override fun setFocused(focused: GuiEventListener?) {
        super.setFocused(focused)
        if (focused == null || focused !in rowWidgets || !minecraft.lastInputType.isKeyboard) return
        val row = rows.firstOrNull { r -> r.parts.any { it.widget === focused } } ?: return
        if (row.top < scrollTo) scrollTo = row.top.toDouble()
        else if (row.top + row.height > scrollTo + bodyHeight()) scrollTo = (row.top + row.height - bodyHeight()).toDouble()
        scrollTo = scrollTo.coerceIn(0.0, maxScroll())
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overScrollbar(event.x(), event.y())) {
            draggingBar = true
            dragBarTo(event.y())
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (draggingBar) {
            dragBarTo(event.y())
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (draggingBar) {
            draggingBar = false
            return true
        }
        return super.mouseReleased(event)
    }

    private fun scrollbarX(): Int = bodyX + bodyWidth + SCROLLBAR - 3

    private fun thumbHeight(): Int {
        val h = bodyHeight()
        val content = h + maxScroll()
        return (h * h / content).toInt().coerceIn(min(16, h), h)
    }

    private fun overScrollbar(x: Double, y: Double): Boolean =
        maxScroll() > 0 && x >= scrollbarX() - 3 && x < scrollbarX() + 5 && y >= bodyTop && y < bodyBottom

    /** Drags the thumb so its middle follows the cursor. */
    private fun dragBarTo(mouseY: Double) {
        val travel = bodyHeight() - thumbHeight()
        if (travel <= 0) return
        val k = ((mouseY - bodyTop - thumbHeight() / 2.0) / travel).coerceIn(0.0, 1.0)
        scrollTo = k * maxScroll()
        scrolled = scrollTo
    }

    override fun added() {
        super.added()
        Ui.open()
    }

    override fun removed() {
        for (spec in unsaved) if (spec.isLoaded) spec.save()
        unsaved.clear()
        super.removed()
    }

    override fun onClose() {
        Ui.close()
        minecraft.gui.setScreen(parent)
    }

    override fun tick() {
        ticks++
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (minecraft.level == null) super.extractBackground(graphics, mouseX, mouseY, a)
        else {
            val k = min(1f, (ticks + a) / 5f)
            graphics.fill(0, 0, width, height, ARGB.color((150 * k).toInt(), 0, 0, 0))
            minecraft.gui.hud.extractDeferredSubtitles()
        }
        Ui.panel(graphics, panelX, panelY, panelWidth, panelHeight)
        // The name on a plate across the top edge, the pages under it
        val name = Ui.heading(title)
        val plateWidth = font.width(name) + 24
        val center = panelX + panelWidth / 2
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.NAME_PLATE, center - plateWidth / 2, panelY - 7, plateWidth, 15)
        graphics.text(font, name, center - plateWidth / 2 + 12, panelY - 4 + Ui.HEADING_RISE, Ui.GOLD_BRIGHT, true)
        Ui.divider(graphics, panelX + 16, panelY + DIVIDER_Y, panelWidth - 32, gem = false)
        Ui.divider(graphics, panelX + 16, panelY + panelHeight - FOOTER + 1, panelWidth - 32, gem = false)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (!draggingBar) glide()
        place()
        hots.clear()
        // The page, cut off cleanly at the body's top and bottom as it scrolls
        graphics.enableScissor(panelX + 4, bodyTop, panelX + panelWidth - 4, bodyBottom)
        for (row in rows) if (row.y + row.height > bodyTop && row.y < bodyBottom) row.draw(graphics, bodyX, row.y, bodyWidth)
        for (row in rows) for (part in row.parts) part.widget.extractRenderState(graphics, mouseX, mouseY, a)
        graphics.disableScissor()
        drawScrollbar(graphics, mouseX, mouseY)
        // The tabs and Done
        super.extractRenderState(graphics, mouseX, mouseY, a)
        if (mouseY < bodyTop || mouseY >= bodyBottom || draggingBar) return
        val hot = hots.lastOrNull { mouseX >= it.x0 && mouseX < it.x1 && mouseY >= it.y0 && mouseY < it.y1 } ?: return
        drawTip(graphics, hot.tip, mouseX, mouseY)
    }

    private fun drawScrollbar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val last = maxScroll()
        if (last <= 0) return
        val x = scrollbarX()
        val travel = bodyHeight() - thumbHeight()
        graphics.fill(x, bodyTop, x + 2, bodyBottom, TRACK)
        val top = bodyTop + (travel * (scrolled / last)).roundToInt()
        val lit = draggingBar || overScrollbar(mouseX.toDouble(), mouseY.toDouble())
        graphics.fill(x, top, x + 2, top + thumbHeight(), if (lit) Ui.GOLD_BRIGHT else Ui.GOLD)
    }

    /** What a line means, in a small dark panel by the cursor. */
    private fun drawTip(graphics: GuiGraphicsExtractor, tip: Component, mouseX: Int, mouseY: Int) {
        val lines = font.split(tip, TIP_WIDTH)
        val w = (lines.maxOfOrNull { font.width(it) } ?: 0) + 16
        val h = lines.size * 10 + 10
        var x = mouseX + 10
        if (x + w > width - 4) x = (mouseX - 10 - w).coerceAtLeast(4)
        val y = (mouseY + 12).let { if (it + h > height - 4) mouseY - 6 - h else it }.coerceAtLeast(4)
        graphics.nextStratum()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, x, y, w, h)
        for ((i, line) in lines.withIndex()) graphics.text(font, line, x + 8, y + 6 + i * 10, Ui.TEXT, false)
    }

    // ---- Widgets ----

    /** A page's name along the top; the page shown is lit, with a gem under it. */
    private inner class Tab(private val target: Page, message: Component, width: Int) :
        Button(0, 0, width, 13, message, Button.OnPress { }, Button.CreateNarration { it.get() }) {

        override fun onPress(input: InputWithModifiers) = turnTo(target)

        override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
            val current = target == page
            val color = when {
                current -> Ui.GOLD_BRIGHT
                isHoveredOrFocused -> Ui.GOLD
                else -> Ui.TEXT_SOFT
            }
            graphics.text(font, message, x + (width - font.width(message)) / 2, y + Ui.HEADING_RISE, color, true)
            if (current) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND, x + width / 2 - 2, y + 11, 5, 5)
        }

        override fun playDownSound(manager: SoundManager) {
            if (target != page) Ui.turn(manager)
        }
    }

    companion object {
        /** The page the screen opens on: the one last looked at. */
        private var lastPage = Page.INTERFACE

        private const val MAX_WIDTH = 340
        private const val MAX_HEIGHT = 262
        private const val PAD = 14
        private const val SCROLLBAR = 6
        private const val TABS_Y = 11
        private const val TAB_GAP = 8
        private const val DIVIDER_Y = 28
        private const val BODY_TOP = 34
        private const val FOOTER = 32
        private const val HEADER = 19
        private const val SETTING = 18
        private const val CARD_BUTTONS = 16
        /** Pixels a turn of the wheel scrolls, what Page Up and Down leave in view of the last page, and how quickly a scroll glides. */
        private const val WHEEL_STEP = 24.0
        private const val PAGE_OVERLAP = 24
        private const val GLIDE_MS = 45.0
        private const val TIP_WIDTH = 190
        private const val ELLIPSIS = "…"

        private val RULE = 0x50D9B46A
        private val TRACK = 0x60D9B46A

        /** The contract pay and guild XP multipliers: fine steps near 1, coarser ones out at the ends. */
        private val MULTIPLIERS = listOf(0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.6, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
        private val PERCENTS = (0..20).map { it * 5.0 }
        private val CHANCES = (0..20).map { it / 100.0 } + listOf(0.25, 0.3, 0.4, 0.5, 0.75, 1.0)

        private fun linear(min: Double, max: Double, whole: Boolean): List<Double> =
            (0..20).map { min + (max - min) * it / 20.0 }.map { if (whole) it.roundToInt().toDouble() else it }.distinct()

        private fun trimmed(value: Double): String =
            if (abs(value - value.roundToInt()) < 1e-6) value.roundToInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.', ',')

        private fun times(value: Double) = trimmed(value) + "×"
        private fun percent(value: Double) = "${value.roundToInt()}%"
        private fun percentOf(value: Double) = "${(value * 100).roundToInt()}%"
        private fun plain(value: Double) = trimmed(value)

        val CREST: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ui/crest")

        /**
         * The crest that opens these settings, put at the end of the row of small buttons the Mods
         * button sits in on the title screen and the pause menu (the row stays centred).
         */
        fun onScreenInit(event: ScreenEvent.Init.Post) {
            val screen = event.screen
            if (screen !is TitleScreen && screen !is PauseScreen) return
            val mods = event.listenersList.filterIsInstance<ModsButton>().firstOrNull() ?: return
            val row = event.listenersList.filterIsInstance<AbstractWidget>().filter { it.y == mods.y && it.width == mods.width && it.height == mods.height }
            val crest = SpriteIconButton.builder(Component.translatable("guildmark.configuration.title"), { Minecraft.getInstance().gui.setScreen(SettingsScreen(screen)) }, true)
                .width(mods.width)
                .sprite(CREST, 15, 15)
                .withTootip()
                .build()
            val shift = (crest.width + 4) / 2
            crest.setPosition(row.maxOf { it.x + it.width } + 4 - shift, mods.y)
            for (widget in row) widget.x -= shift
            event.addListener(crest)
        }
    }
}

/** An on/off switch in the kit's look: a dark track that fills with gold, its knob sliding across. */
class Switch(private val label: Component, private val read: () -> Boolean, private val write: (Boolean) -> Unit) :
    Button(0, 0, WIDTH, HEIGHT, label, Button.OnPress { }, Button.CreateNarration { it.get() }) {
    private var knob = if (read()) 1f else 0f

    override fun onPress(input: InputWithModifiers) {
        if (active) write(!read())
    }

    override fun createNarrationMessage(): MutableComponent = CommonComponents.optionStatus(label, read())

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val on = read()
        knob += ((if (on) 1f else 0f) - knob) * 0.4f
        if (abs(knob - (if (on) 1f else 0f)) < 0.02f) knob = if (on) 1f else 0f
        val font = Minecraft.getInstance().font
        val lit = active && isHoveredOrFocused
        val word = Component.translatable(if (on) "gui.guildmark.settings.on" else "gui.guildmark.settings.off")
        val wordColor = when {
            !active -> Ui.TEXT_FAINT
            on -> Ui.GOLD_BRIGHT
            else -> Ui.TEXT_SOFT
        }
        val trackX = x + width - TRACK_WIDTH
        graphics.text(font, word, trackX - 5 - font.width(word), y + (height - 8) / 2, wordColor, false)
        val top = y + (height - TRACK_HEIGHT) / 2
        graphics.fill(trackX, top, trackX + TRACK_WIDTH, top + TRACK_HEIGHT, if (lit) Ui.GOLD_BRIGHT else EDGE)
        graphics.fill(trackX + 1, top + 1, trackX + TRACK_WIDTH - 1, top + TRACK_HEIGHT - 1, BACK)
        val fill = ((TRACK_WIDTH - 2) * knob).roundToInt()
        if (fill > 0) graphics.fill(trackX + 1, top + 1, trackX + 1 + fill, top + TRACK_HEIGHT - 1, if (active) FILL else FILL_FAINT)
        val knobX = trackX + 1 + ((TRACK_WIDTH - 2 - KNOB) * knob).roundToInt()
        graphics.fill(knobX, top + 1, knobX + KNOB, top + TRACK_HEIGHT - 1, if (!active) KNOB_FAINT else if (on) Ui.GOLD_BRIGHT else KNOB_OFF)
        graphics.fill(knobX + 1, top + 2, knobX + KNOB - 1, top + 3, if (!active) KNOB_FAINT else if (on) SHINE else KNOB_SHINE)
    }

    override fun playDownSound(manager: SoundManager) = Ui.click(manager)

    companion object {
        const val WIDTH = 48
        const val HEIGHT = 12
        private const val TRACK_WIDTH = 22
        private const val TRACK_HEIGHT = 10
        private const val KNOB = 8
        private val EDGE = 0xFF5A4B30.toInt()
        private val BACK = 0xFF15131A.toInt()
        private val FILL = 0xFF6A5226.toInt()
        private val FILL_FAINT = 0xFF33302E.toInt()
        private val KNOB_OFF = 0xFF5D5A66.toInt()
        private val KNOB_SHINE = 0xFF7F7C8A.toInt()
        private val KNOB_FAINT = 0xFF3A3840.toInt()
        private val SHINE = 0xFFFFF0C4.toInt()
    }
}

/**
 * A slider in the kit's look that moves through set [steps], so a value always lands somewhere sensible;
 * the arrow keys move it a step at a time.
 */
class Slider(private val label: Component, private val steps: List<Double>, start: Double, private val text: (Double) -> String, private val set: (Double) -> Unit) :
    AbstractSliderButton(0, 0, WIDTH, HEIGHT, Component.empty(), 0.0) {
    private var current = start

    init {
        value = steps.indexOf(nearest(start)).toDouble() / (steps.size - 1)
        updateMessage()
    }

    private fun nearest(target: Double): Double = steps.minBy { abs(it - target) }

    private fun index(): Int = (value * (steps.size - 1)).roundToInt().coerceIn(0, steps.size - 1)

    override fun updateMessage() {
        message = Component.literal(text(current))
    }

    override fun applyValue() {
        val next = steps[index()]
        value = index().toDouble() / (steps.size - 1)
        if (next == current) return
        current = next
        set(next)
    }

    override fun createNarrationMessage(): MutableComponent = Component.translatable("gui.narrate.slider", CommonComponents.optionNameValue(label, message))

    override fun keyPressed(event: KeyEvent): Boolean {
        if (isActive && canChangeValue && (event.isLeft || event.isRight)) {
            val i = (index() + if (event.isLeft) -1 else 1).coerceIn(0, steps.size - 1)
            setValue(i.toDouble() / (steps.size - 1))
            return true
        }
        return super.keyPressed(event)
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val lit = active && (isHoveredOrFocused || dragging)
        graphics.fill(x, y, x + width, y + height, if (lit) Ui.GOLD_BRIGHT else EDGE)
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, BACK)
        val knobX = x + 1 + ((width - 2 - KNOB) * value).roundToInt()
        if (knobX > x + 1) graphics.fill(x + 1, y + 1, knobX, y + height - 1, if (active) FILL else FILL_FAINT)
        graphics.fill(knobX, y + 1, knobX + KNOB, y + height - 1, if (!active) KNOB_FAINT else if (lit) Ui.GOLD_BRIGHT else Ui.GOLD)
        val font = Minecraft.getInstance().font
        graphics.centeredText(font, message, x + width / 2, y + (height - 8) / 2 + 1, if (active) Ui.TEXT else Ui.TEXT_FAINT)
        handleCursor(graphics)
    }

    override fun onDrag(event: MouseButtonEvent, dx: Double, dy: Double) {
        if (visible) super.onDrag(event, dx, dy)
    }

    override fun onRelease(event: MouseButtonEvent) {
        dragging = false
        Ui.click(Minecraft.getInstance().soundManager)
    }

    companion object {
        const val WIDTH = 110
        const val HEIGHT = 12
        private const val KNOB = 6
        private val EDGE = 0xFF5A4B30.toInt()
        private val BACK = 0xFF15131A.toInt()
        private val FILL = 0xFF4A3A1E.toInt()
        private val FILL_FAINT = 0xFF2A2826.toInt()
        private val KNOB_FAINT = 0xFF3A3840.toInt()
    }
}
