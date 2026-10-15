package io.github.vaspyyy.guildmark.client.ui

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.combat.WeaponClass
import io.github.vaspyyy.guildmark.combat.Weapons
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.loot.Affix
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Scaling
import io.github.vaspyyy.guildmark.progression.SkillTree
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.progression.Title
import io.github.vaspyyy.guildmark.story.MainQuestState
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ProjectileWeaponItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The character screen (K): you as you stand, in your gear, and what it all adds up to. Your vitals;
 * your defence, with the poise that keeps you on your feet and whether your armour still lets you roll
 * quickly; your offence with the weapon in hand; the powers of the relics you wear; where you stand
 * with the guild; and how far your journey has come. Hover anything for what it means.
 */
class CharacterScreen : Screen(Component.translatable("gui.guildmark.status")) {
    private var panelX = 0
    private var panelY = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var side = 0
    private var middle = 0
    private var pad = PAD
    private var gap = GAP
    private var ticks = 0

    /** One line of a section: what it is, its value, and what hovering it explains. */
    private class Row(val label: Component, val value: Component, val color: Int, val tip: Component?, val bar: Float = -1f)

    private class Section(val title: Component, val rows: List<Row>)

    /** Something hovering explains, found while drawing. */
    private class Hot(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val tip: Component?, val stack: ItemStack? = null)

    private val hots = ArrayList<Hot>()

    override fun init() {
        panelWidth = min(MAX_WIDTH, width - 16)
        panelHeight = min(MAX_HEIGHT, height - 24)
        panelX = (width - panelWidth) / 2
        panelY = (height - panelHeight) / 2 + 4
        // On a narrow screen the margins and the middle column give way first, so the lines keep their words
        val narrow = panelWidth < NARROW
        pad = if (narrow) PAD_NARROW else PAD
        gap = if (narrow) GAP_NARROW else GAP
        val inner = panelWidth - pad * 2
        middle = (inner * 0.34f).toInt().coerceIn(MIDDLE_MIN, MIDDLE_MAX)
        side = (inner - middle - gap * 2) / 2
        if (side < SIDE_MIN) {
            middle = max(MIDDLE_MIN, inner - gap * 2 - SIDE_MIN * 2)
            side = (inner - middle - gap * 2) / 2
        }
    }

    override fun added() {
        super.added()
        Ui.open()
    }

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    override fun tick() {
        ticks++
    }

    override fun isPauseScreen(): Boolean = false

    /** Subtitles are drawn over this screen, not hidden under its shade. */
    override fun isInGameUi(): Boolean = true

    override fun keyPressed(event: KeyEvent): Boolean {
        if (KEY.matches(event)) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    // ---- What's shown ----

    private fun number(value: Double): String =
        if (abs(value - value.roundToInt()) < 0.05) value.roundToInt().toString() else "%.1f".format(value)

    private fun row(id: String, value: String, color: Int = Ui.TEXT, vararg tipArgs: Any): Row = Row(
        Component.translatable("gui.guildmark.status.$id"), Component.literal(value), color,
        Component.translatable("gui.guildmark.status.$id.tip", *tipArgs),
    )

    private fun vitals(player: LocalPlayer): Section {
        val rows = arrayListOf(row("health", "${number(player.health.toDouble())} / ${number(player.maxHealth.toDouble())}", Ui.RED))
        if (CombatClient.active()) {
            rows.add(row("stamina", CombatClient.maxStamina.roundToInt().toString(), Ui.GREEN))
            rows.add(row("focus", CombatClient.maxFocus.roundToInt().toString(), Ui.BLUE))
        }
        return Section(Component.translatable("gui.guildmark.status.vitals"), rows)
    }

    private fun defence(player: LocalPlayer): Section {
        val armor = player.armorValue
        val toughness = player.getAttributeValue(Attributes.ARMOR_TOUGHNESS)
        // As Combat works it out: base 25, plus armour, toughness and the build's poise
        val poise = (25.0 + armor * 1.5 + toughness * 2.0 + Talents[player, Bonus.POISE]).coerceAtLeast(5.0)
        val rows = arrayListOf(
            row("armor", armor.toString()),
            row("toughness", number(toughness)),
        )
        if (CombatClient.active()) {
            rows.add(row("poise", poise.roundToInt().toString()))
            val always = Talents.build(player).has(Bonus.HEAVY_ROLL)
            val heavy = always || armor > Weapons.rollLoad(player)
            rows.add(Row(
                Component.translatable("gui.guildmark.status.roll"),
                Component.translatable(if (heavy) "gui.guildmark.status.roll.heavy" else "gui.guildmark.status.roll.quick"),
                if (heavy) Ui.RED else Ui.GREEN,
                if (always) Component.translatable("gui.guildmark.status.roll.always.tip")
                else Component.translatable("gui.guildmark.status.roll.tip", Weapons.rollLoad(player), armor),
            ))
        }
        return Section(Component.translatable("gui.guildmark.status.defence"), rows)
    }

    /**
     * Attack damage as the server works it out. The server never sends this one to the client, so it's
     * put together here the same way: the base value, plus what's held and worn, the skill tree's flat
     * damage and any effects on you.
     */
    private fun attackDamage(player: LocalPlayer): Double {
        val instance = player.getAttribute(Attributes.ATTACK_DAMAGE) ?: return 1.0
        // Keyed by id, as the attribute itself keeps them, so nothing counts twice
        val modifiers = LinkedHashMap<Identifier, AttributeModifier>()
        for (modifier in instance.modifiers) modifiers[modifier.id()] = modifier
        for (slot in EquipmentSlot.entries) {
            player.getItemBySlot(slot).forEachModifier(slot) { attribute, modifier ->
                if (attribute.`is`(Attributes.ATTACK_DAMAGE)) modifiers[modifier.id()] = modifier
            }
        }
        val build = Talents[player, Bonus.ATTACK_DAMAGE]
        if (build != 0.0) {
            val id = Talents.modifierId(Bonus.ATTACK_DAMAGE)
            modifiers[id] = AttributeModifier(id, build, Bonus.ATTACK_DAMAGE.operation)
        }
        for (effect in player.activeEffects) {
            effect.effect.value().createModifiers(effect.amplifier) { attribute, modifier ->
                if (attribute.`is`(Attributes.ATTACK_DAMAGE)) modifiers[modifier.id()] = modifier
            }
        }
        var base = instance.baseValue
        for (modifier in modifiers.values) if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) base += modifier.amount()
        var total = base
        for (modifier in modifiers.values) if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) total += base * modifier.amount()
        for (modifier in modifiers.values) if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) total *= 1.0 + modifier.amount()
        return Attributes.ATTACK_DAMAGE.value().sanitizeValue(total)
    }

    private fun offence(player: LocalPlayer): Section {
        val rows = arrayListOf(
            row("attack", number(attackDamage(player))),
            row("speed", number(player.getAttributeValue(Attributes.ATTACK_SPEED))),
        )
        if (CombatClient.active()) {
            val weapon = Weapons.classOf(player.mainHandItem)
            val style = when (weapon) {
                WeaponClass.STRAIGHT_SWORD -> "sword"
                null -> "plain"
            }
            rows.add(Row(
                Component.translatable("gui.guildmark.status.style"), Component.translatable("gui.guildmark.status.style.$style"), Ui.TEXT,
                Component.translatable("gui.guildmark.status.style.$style.tip"),
            ))
            // Only a weapon the moves work with has a tempo
            if (weapon != null) rows.add(row("tempo", "${(Weapons.tempo(player) * 100).roundToInt()}%"))
        }
        // The stats the weapon in hand grows with, and what they add to it now
        val stack = player.mainHandItem
        val grades = Scaling.grades(stack)
        if (grades.isNotEmpty()) {
            val sheet = Talents.sheet(player)
            val bonus = (Scaling.bonus(sheet, stack) * 100).roundToInt()
            val named = grades.joinToString(", ") { (stat, grade) -> stat.title.string + " " + grade.name }
            rows.add(Row(
                Component.translatable("gui.guildmark.status.scaling"), Component.literal("+$bonus%"), Ui.GOLD,
                Component.translatable("gui.guildmark.status.scaling.tip", named, bonus),
            ))
        }
        return Section(Component.translatable("gui.guildmark.status.offence"), rows)
    }

    private fun guild(player: LocalPlayer): Section {
        val progress = player.getData(ModAttachments.GUILD_PROGRESS)
        val rows = arrayListOf(
            Row(
                Component.translatable("gui.guildmark.status.rank"), Component.literal(progress.rank.letter), progress.rank.color,
                Component.translatable("gui.guildmark.status.rank.tip"),
            ),
            row("level", progress.level.toString()),
        )
        if (progress.isMaxLevel()) rows.add(Row(Component.empty(), Component.empty(), Ui.GOLD, Component.translatable("gui.guildmark.status.xp.max"), 1f))
        else rows.add(Row(
            Component.empty(), Component.empty(), Ui.GOLD,
            Component.translatable("gui.guildmark.status.xp.tip", progress.xp, progress.xpToNext()),
            progress.xp.toFloat() / max(1, progress.xpToNext()),
        ))
        val points = SkillTree.available(progress, player.getData(ModAttachments.SKILLS), Talents.sheet(player)).coerceAtLeast(0)
        rows.add(row("points", points.toString(), if (points > 0) Ui.GOLD_BRIGHT else Ui.TEXT))
        rows.add(row("marks", TownControl.marks(player).toString(), Ui.GOLD))
        return Section(Component.translatable("gui.guildmark.status.guild"), rows)
    }

    /** The shards of the crown taken so far, and the milestones reached. */
    private fun journey(player: LocalPlayer): Section {
        val quest = player.getData(ModAttachments.MAIN_QUEST)
        val shards = if (quest.finished) Row(
            Component.translatable("gui.guildmark.status.shards"), Component.translatable("gui.guildmark.status.shards.broken"), Ui.GOLD_BRIGHT,
            Component.translatable("gui.guildmark.status.shards.broken.tip"),
        ) else Row(
            Component.translatable("gui.guildmark.status.shards"), Component.literal("${quest.found} / ${MainQuestState.SHARDS}"),
            if (quest.started) Ui.GOLD else Ui.TEXT_FAINT,
            Component.translatable(if (quest.started) "gui.guildmark.status.shards.tip" else "gui.guildmark.status.shards.none.tip"),
        )
        // Every milestone the server has told us is done, leaving out the tab's root
        val milestones = player.connection.advancements.progress().count { (holder, progress) ->
            val id = holder.id()
            progress.isDone && id.namespace == Guildmark.MOD_ID && id.path.startsWith("guildmark/") && id.path != "guildmark/root"
        }
        return Section(Component.translatable("gui.guildmark.status.journey"), listOf(shards, row("milestones", milestones.toString())))
    }

    /**
     * The affixes at work on what's worn and held, with how many pieces carry each. A weapon's powers
     * work in the main hand (and a bow's or crossbow's from the off hand too, on what it shoots); armour's
     * only when it's worn.
     */
    private fun relics(player: LocalPlayer): Section {
        val counts = LinkedHashMap<Affix, Int>()
        for (slot in WORN) {
            val stack = player.getItemBySlot(slot)
            val relic = stack.get(ModDataComponents.RELIC.get()) ?: continue
            for (id in relic.affixes) {
                val affix = Affix.byId(id) ?: continue
                val working = when {
                    !affix.weapon -> slot in ARMOR
                    slot == EquipmentSlot.MAINHAND -> true
                    else -> slot == EquipmentSlot.OFFHAND && stack.item is ProjectileWeaponItem && !affix.meleeOnly
                }
                if (working) counts[affix] = (counts[affix] ?: 0) + 1
            }
        }
        val rows = counts.map { (affix, count) ->
            val line = affix.line
            Row(Component.literal(line.string.substringBefore(':')), Component.literal(if (count > 1) "x$count" else ""), Ui.GOLD, line)
        }.ifEmpty {
            listOf(Row(Component.translatable("gui.guildmark.status.relics.none"), Component.empty(), Ui.TEXT_FAINT, Component.translatable("gui.guildmark.status.relics.none.tip")))
        }
        return Section(Component.translatable("gui.guildmark.status.relics"), rows)
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val k = min(1f, (ticks + a) / 5f)
        graphics.fill(0, 0, width, height, ARGB.color((150 * k).toInt(), 0, 0, 0))
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val player = minecraft.player ?: return
        hots.clear()
        Ui.panel(graphics, panelX, panelY, panelWidth, panelHeight)

        // Your name on a plate across the top edge, and who you are to the guild under it
        val name = Ui.heading(player.name)
        val plateWidth = font.width(name) + 24
        val center = panelX + panelWidth / 2
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.NAME_PLATE, center - plateWidth / 2, panelY - 7, plateWidth, 15)
        graphics.text(font, name, center - plateWidth / 2 + 12, panelY - 4 + Ui.HEADING_RISE, Ui.GOLD_BRIGHT, true)
        val progress = player.getData(ModAttachments.GUILD_PROGRESS)
        val parts = ArrayList<String>()
        Title.byId(player.getData(ModAttachments.TITLE))?.let { parts.add(it.label.string) }
        parts.add(Component.translatable("gui.guildmark.status.adventurer", progress.rank.title).string)
        parts.add(Component.translatable("gui.guildmark.status.guild_level", progress.level).string)
        val about = font.plainSubstrByWidth(parts.joinToString("  ·  "), panelWidth - 24)
        graphics.text(font, about, center - font.width(about) / 2, panelY + 13, Ui.TEXT_SOFT, false)
        Ui.divider(graphics, panelX + 16, panelY + 25, panelWidth - 32)

        val top = panelY + BODY_TOP
        val bottom = panelY + panelHeight - 9
        val left = panelX + pad
        val mid = left + side + gap
        val right = mid + middle + gap

        drawColumn(graphics, listOf(guild(player), journey(player), relics(player)), left, top, side, bottom)
        drawDoll(graphics, player, mid, top, middle, bottom - top, mouseX, mouseY)
        drawColumn(graphics, listOf(vitals(player), defence(player), offence(player)), right, top, side, bottom)

        val hot = hots.lastOrNull { mouseX >= it.x0 && mouseX < it.x1 && mouseY >= it.y0 && mouseY < it.y1 } ?: return
        val stack = hot.stack
        if (stack != null) {
            if (!stack.isEmpty) graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY)
            return
        }
        hot.tip?.let { drawTip(graphics, it, mouseX, mouseY) }
    }

    /**
     * Sections one under another. A section with no room left even for its heading and a line is folded
     * into a last faint line, and hovering that lists what's in it.
     */
    private fun drawColumn(graphics: GuiGraphicsExtractor, sections: List<Section>, x: Int, top: Int, w: Int, bottom: Int) {
        var y = top
        for ((i, section) in sections.withIndex()) {
            if (y + HEADING + ROW <= bottom) {
                y = drawSection(graphics, section, x, y, w, bottom)
                continue
            }
            if (y + ROW > bottom) return
            val rest = sections.drop(i)
            val tip = rest.joinToString("\n") { hidden ->
                hidden.title.string + ": " + hidden.rows.filter { it.label.string.isNotEmpty() }.joinToString(", ") { row ->
                    if (row.value.string.isEmpty()) row.label.string else row.label.string + " " + row.value.string
                }
            }
            val names = rest.joinToString(", ") { it.title.string }
            val line = font.plainSubstrByWidth(Component.translatable("gui.guildmark.status.also", names).string, w - 2)
            graphics.text(font, line, x + 2, y, Ui.TEXT_FAINT, false)
            hots.add(Hot(x, y - 1, x + w, y + ROW - 1, Component.literal(tip)))
            return
        }
    }

    /**
     * A heading and its rows; returns where the next section starts. When the rows run out of room, the
     * last line that fits says how many more there are, and hovering it names them.
     */
    private fun drawSection(graphics: GuiGraphicsExtractor, section: Section, x: Int, y: Int, w: Int, bottom: Int): Int {
        Ui.headingAt(graphics, font, section.title, x, y, Ui.GOLD)
        graphics.fill(x, y + 10, x + w, y + 11, RULE)
        var cy = y + HEADING
        for ((i, row) in section.rows.withIndex()) {
            if (cy + ROW > bottom) break
            if (i < section.rows.size - 1 && cy + ROW * 2 > bottom) {
                val rest = section.rows.drop(i)
                graphics.text(font, Component.translatable("gui.guildmark.status.more", rest.size), x + 2, cy, Ui.TEXT_FAINT, false)
                hots.add(Hot(x, cy - 1, x + w, cy + ROW - 1, Component.literal(rest.joinToString(", ") { it.label.string })))
                cy += ROW
                break
            }
            if (row.bar >= 0f) {
                // A progress bar in place of a line
                graphics.fill(x + 2, cy + 1, x + w, cy + 5, BAR_BACK)
                graphics.fill(x + 2, cy + 1, x + 2 + ((w - 2) * row.bar.coerceIn(0f, 1f)).toInt(), cy + 5, row.color)
                hots.add(Hot(x, cy - 1, x + w, cy + 7, row.tip))
                cy += 8
                continue
            }
            val value = font.width(row.value)
            val room = w - value - 8
            val full = row.label.string
            // A label too long for its line ends in an ellipsis rather than mid-word
            val label = if (font.width(full) <= room) full else font.plainSubstrByWidth(full, room - font.width(ELLIPSIS)).trimEnd() + ELLIPSIS
            graphics.text(font, label, x + 2, cy, if (row.color == Ui.TEXT_FAINT) Ui.TEXT_FAINT else Ui.TEXT_SOFT, false)
            graphics.text(font, row.value, x + w - value, cy, row.color, false)
            hots.add(Hot(x, cy - 1, x + w, cy + ROW - 1, row.tip))
            cy += ROW
        }
        return cy + SECTION_GAP
    }

    /** You, turning to follow the cursor, with what you wear down one side and what you hold down the other. */
    private fun drawDoll(graphics: GuiGraphicsExtractor, player: LocalPlayer, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        Ui.inset(graphics, x, y, w, h)
        // A pool of warm light to stand in
        val glow = min(w, h) - 8
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.GLOW, x + w / 2 - glow / 2, y + h / 2 - glow / 2, glow, glow)
        val slotTop = y + 6
        for ((i, slot) in ARMOR.withIndex()) drawSlot(graphics, player, slot, x + 4, slotTop + i * 21)
        drawSlot(graphics, player, EquipmentSlot.MAINHAND, x + w - 22, slotTop)
        drawSlot(graphics, player, EquipmentSlot.OFFHAND, x + w - 22, slotTop + 21)
        val x0 = x + 24
        val x1 = x + w - 24
        val y0 = y + 4
        val y1 = y + h - 4
        val size = min((x1 - x0) * 0.6f, (y1 - y0) / 2.3f).toInt().coerceAtLeast(20)
        InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x0, y0, x1, y1, size, 0.0625f, mouseX.toFloat(), mouseY.toFloat(), player)
    }

    private fun drawSlot(graphics: GuiGraphicsExtractor, player: LocalPlayer, slot: EquipmentSlot, x: Int, y: Int) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SOCKET, x, y, 18, 18)
        val stack = player.getItemBySlot(slot)
        if (stack.isEmpty) {
            EMPTY[slot]?.let { graphics.blitSprite(RenderPipelines.GUI_TEXTURED, it, x + 1, y + 1, 16, 16, 0.45f) }
        } else {
            graphics.item(stack, x + 1, y + 1)
            graphics.itemDecorations(font, stack, x + 1, y + 1)
        }
        hots.add(Hot(x, y, x + 18, y + 18, null, stack))
    }

    /** What a line means, in a small dark panel by the cursor. */
    private fun drawTip(graphics: GuiGraphicsExtractor, tip: Component, mouseX: Int, mouseY: Int) {
        val lines = font.split(tip, TIP_WIDTH)
        val w = (lines.maxOfOrNull { font.width(it) } ?: 0) + 16
        val h = lines.size * 10 + 10
        var x = mouseX + 10
        if (x + w > width - 4) x = max(4, mouseX - 10 - w)
        val y = (mouseY - 6).coerceIn(4, max(4, height - h - 4))
        graphics.nextStratum()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, x, y, w, h)
        for ((i, line) in lines.withIndex()) graphics.text(font, line, x + 8, y + 6 + i * 10, Ui.TEXT, false)
    }

    companion object {
        val KEY = KeyMapping("key.guildmark.character", InputConstants.KEY_K, KeyMapping.Category(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "main")))

        private const val MAX_WIDTH = 440
        private const val MAX_HEIGHT = 228
        private const val PAD = 14
        private const val GAP = 12
        /** Below this panel width the margins tighten and the middle column narrows. */
        private const val NARROW = 380
        private const val PAD_NARROW = 9
        private const val GAP_NARROW = 7
        private const val MIDDLE_MIN = 80
        private const val MIDDLE_MAX = 150
        /** The side columns are kept at least this wide while the middle can still give way. */
        private const val SIDE_MIN = 100
        private const val ELLIPSIS = "…"
        private const val BODY_TOP = 34
        private const val HEADING = 14
        private const val ROW = 10
        private const val SECTION_GAP = 6
        private const val TIP_WIDTH = 170

        private val RULE = 0x50D9B46A
        private val BAR_BACK = 0xFF15131A.toInt()

        private val ARMOR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        private val WORN = ARMOR + EquipmentSlot.MAINHAND + EquipmentSlot.OFFHAND
        private val EMPTY = mapOf(
            EquipmentSlot.HEAD to Identifier.withDefaultNamespace("container/slot/helmet"),
            EquipmentSlot.CHEST to Identifier.withDefaultNamespace("container/slot/chestplate"),
            EquipmentSlot.LEGS to Identifier.withDefaultNamespace("container/slot/leggings"),
            EquipmentSlot.FEET to Identifier.withDefaultNamespace("container/slot/boots"),
            EquipmentSlot.MAINHAND to Identifier.withDefaultNamespace("container/slot/sword"),
            EquipmentSlot.OFFHAND to Identifier.withDefaultNamespace("container/slot/shield"),
        )
    }
}
