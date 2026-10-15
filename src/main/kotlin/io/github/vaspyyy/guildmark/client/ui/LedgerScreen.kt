package io.github.vaspyyy.guildmark.client.ui

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.TreePayload
import io.github.vaspyyy.guildmark.progression.Blocked
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.CharacterSheet
import io.github.vaspyyy.guildmark.progression.Effect
import io.github.vaspyyy.guildmark.progression.GuildProgress
import io.github.vaspyyy.guildmark.progression.Levelling
import io.github.vaspyyy.guildmark.progression.Node
import io.github.vaspyyy.guildmark.progression.NodeKind
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.SkillGroup
import io.github.vaspyyy.guildmark.progression.SkillState
import io.github.vaspyyy.guildmark.progression.SkillTree
import io.github.vaspyyy.guildmark.progression.Stat
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.Locale
import java.util.Optional
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Guild Ledger (G): who you've grown into. Down the left, your level, banked guild XP and tree
 * points, your seven stats and sixteen skills; filling the rest, the skill tree, dragged about and zoomed
 * with the mouse. Clicking a node plans it (or the shortest way to it), and Learn spends the points. At a
 * Wardfire, or anywhere in a world without them, nodes can be forgotten for Guild Marks and the stats
 * raised (see [GrowScreen]). Opened from a fire, closing it goes back to [back].
 */
class LedgerScreen(private val restAt: BlockPos? = null, private val back: Screen? = null) : Screen(Component.translatable("gui.guildmark.ledger")) {
    /** Nodes clicked but not yet learned, in an order where each touches the tree or one before it. */
    private val planned = ArrayList<String>()
    /** Nodes just sent to be learned, shown as learned until the server's word comes back. */
    private var sent: List<String> = listOf()
    private var sentAt = 0
    /** The sheet the plan was last checked against. */
    private var checked: CharacterSheet? = null

    private var ticks = 0
    private var pressed = false
    private var dragged = 0.0
    private var hovered: Node? = null
    /** The nodes a click on [hovered] would plan, when it isn't next to the tree yet. */
    private var hoverPath: List<Node>? = null
    private var hoveredSkill: Skill? = null
    private var asking: Ask? = null
    private var states: Map<String, State> = mapOf()

    private var sideX = 0
    private var sideY = 0
    private var sideW = 0
    private var sideH = 0
    private var density = Density.FULL
    private var viewX0 = 0
    private var viewY0 = 0
    private var viewX1 = 0
    private var viewY1 = 0
    private val midX: Float get() = (viewX0 + viewX1) / 2f
    private val midY: Float get() = (viewY0 + viewY1) / 2f

    private var learnButton: Ui.KitButton? = null
    private var clearButton: Ui.KitButton? = null
    private var resetButton: Ui.KitButton? = null
    private var growButton: Ui.KitButton? = null
    private val askButtons = ArrayList<Ui.KitButton>()

    /** A question waiting on a yes (forgetting costs Marks). */
    private class Ask(val text: Component, val yes: Component, val action: () -> Unit)

    /** One line of a tooltip, in its colour. */
    private class Line(val text: Component, val color: Int)

    /** Something in the sidebar the cursor can rest on. */
    private class Hot(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val lines: List<Line>, val skill: Skill? = null)

    private val hots = ArrayList<Hot>()

    /** How tightly the sidebar packs, from what the screen's height allows (heights without the foot). */
    private enum class Density(val needs: Int) {
        FULL(343),
        /** Rows a pixel tighter, and the skills in two columns of icons and levels. */
        COMPACT(248),
        /** The stats in two columns and the skills in three; the Marks left out. */
        TINY(193),
    }

    private enum class State { LEARNED, PLANNED, OPEN, BARRED, LOCKED }

    /** Forgetting and growing are done at a Wardfire, or anywhere in a world without them. */
    private val canRest: Boolean get() = restAt != null || !GuildmarkConfig.WARDFIRES.get()

    // ---- The player's tree as it stands, worked out once a frame ----

    private class Standing(
        val progress: GuildProgress,
        val skills: SkillState,
        val sheet: CharacterSheet,
        /** Learned and just sent nodes, without the root. */
        val learned: Set<String>,
        /** [learned] and the plan. */
        val owned: Set<String>,
        /** Points left once the plan is paid for. */
        val points: Int,
    ) {
        /** Stat raises Grow stronger can make now: a level per lot of banked XP, and any owed from before. */
        val raises: Int get() = progress.levelsBanked() + sheet.owed(progress.level)
    }

    private fun standing(): Standing? {
        val player = minecraft.player ?: return null
        val progress = player.getData(ModAttachments.GUILD_PROGRESS)
        val skills = player.getData(ModAttachments.SKILLS)
        val sheet = player.getData(ModAttachments.SHEET)
        if (sent.isNotEmpty() && (ticks - sentAt > SENT_TICKS || sent.all { it in sheet.nodes })) sent = listOf()
        val learned = HashSet(sheet.nodes).apply { addAll(sent) }
        if (sheet !== checked) {
            // Something was learned or forgotten: keep only the plan that still reaches the tree
            checked = sheet
            prune(learned)
        }
        val owned = HashSet(learned).apply { addAll(planned) }
        val points = SkillTree.available(progress, skills, sheet) - sent.count { it !in sheet.nodes } - planned.size
        return Standing(progress, skills, sheet, learned, owned, points)
    }

    /** Drops planned nodes already [learned] or no longer reaching it, keeping the rest in an order that learns. */
    private fun prune(learned: Set<String>) {
        val owned = HashSet(learned)
        val rest = planned.filterTo(ArrayList()) { it !in learned }
        val kept = ArrayList<String>()
        var grew = true
        while (grew) {
            grew = false
            val it = rest.iterator()
            while (it.hasNext()) {
                val node = SkillTree.node(it.next())
                if (node == null) {
                    it.remove()
                } else if (SkillTree.touches(node, owned)) {
                    kept.add(node.id)
                    owned.add(node.id)
                    it.remove()
                    grew = true
                }
            }
        }
        planned.clear()
        planned.addAll(kept)
    }

    private fun blocked(node: Node, s: Standing): Blocked? =
        SkillTree.blocked(node, s.progress, s.skills, s.sheet, s.owned, s.points)

    private fun stateOf(node: Node, s: Standing): State = when {
        node.id == SkillTree.ROOT || node.id in s.learned -> State.LEARNED
        node.id in s.owned -> State.PLANNED
        else -> when (blocked(node, s)) {
            null -> State.OPEN
            Blocked.NOT_CONNECTED, Blocked.LEARNED -> State.LOCKED
            else -> State.BARRED
        }
    }

    /**
     * The shortest run of nodes from what's owned out to [target], each one's skill or rank met, in an
     * order that can be learned; null if there's none.
     */
    private fun pathTo(target: Node, s: Standing): List<Node>? {
        if (target.id == SkillTree.ROOT || target.id in s.owned) return null
        if (!SkillTree.meetsRequirement(target, s.progress, s.skills)) return null
        val parent = HashMap<Node, Node>()
        val seen = HashSet<Node>()
        val queue = ArrayDeque<Node>()
        seen.add(SkillTree.root)
        queue.add(SkillTree.root)
        for (id in s.owned) SkillTree.node(id)?.let { if (seen.add(it)) queue.add(it) }
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            for (next in at.links) {
                if (!seen.add(next) || !SkillTree.meetsRequirement(next, s.progress, s.skills)) continue
                parent[next] = at
                if (next === target) {
                    val path = ArrayList<Node>()
                    var step: Node? = next
                    while (step != null && step !== SkillTree.root && step.id !in s.owned) {
                        path.add(step)
                        step = parent[step]
                    }
                    path.reverse()
                    return path
                }
                queue.add(next)
            }
        }
        return null
    }

    // ---- Setup ----

    override fun init() {
        sideW = (width * 0.28f).toInt().coerceIn(SIDE_MIN, SIDE_MAX)
        sideX = MARGIN
        sideY = MARGIN
        sideH = height - MARGIN * 2
        val foot = if (canRest) GROW_FOOT else READY_FOOT
        density = Density.entries.firstOrNull { sideH >= it.needs + foot } ?: Density.TINY
        viewX0 = sideX + sideW + MARGIN
        viewY0 = MARGIN
        viewX1 = width - MARGIN
        viewY1 = height - MARGIN
        if (!placed) {
            zoom = (min(viewX1 - viewX0, viewY1 - viewY0) / (2f * FIT_RADIUS)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            placed = true
        }

        val center = (viewX0 + viewX1) / 2
        val buttonY = viewY1 - 28
        learnButton = addRenderableWidget(Ui.button(Component.empty(), 100) { learn() }.also { it.setPosition(center - 103, buttonY) })
        clearButton = addRenderableWidget(Ui.button(Component.translatable("gui.guildmark.tree.clear"), 70) {
            planned.clear()
            refreshButtons()
        }.also { it.setPosition(center + 3, buttonY) })
        if (canRest) {
            resetButton = addRenderableWidget(Ui.button(Component.empty(), RESET_WIDTH) { askReset() }.also {
                it.setPosition(viewX1 - RESET_WIDTH - 8, viewY0 + 8)
            })
            growButton = addRenderableWidget(Ui.button(Component.empty(), sideW - 20) {
                minecraft.gui.setScreen(GrowScreen(restAt, this))
            }.also { it.setPosition(sideX + 10, sideY + sideH - 27) })
        }
        asking?.let { showAsk(it) }
        refreshButtons()
    }

    private fun refreshButtons() {
        val s = standing() ?: return
        val idle = asking == null
        learnButton?.let {
            it.visible = planned.isNotEmpty() && idle
            it.message = Component.translatable("gui.guildmark.tree.learn", planned.size)
        }
        clearButton?.visible = planned.isNotEmpty() && idle
        resetButton?.let {
            it.visible = s.sheet.nodes.isNotEmpty() && idle
            it.message = Component.translatable("gui.guildmark.tree.reset", Levelling.resetCost(s.sheet))
        }
        growButton?.let {
            it.active = idle
            it.message = if (s.raises > 0) Component.translatable("gui.guildmark.ledger.grow.count", s.raises)
            else Component.translatable("gui.guildmark.ledger.grow")
        }
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
        if (player == null || !player.isAlive) {
            onClose()
            return
        }
        // Struck or carried off from the fire: the ledger stays open, but forgetting and growing are over
        if (restAt != null && (player.hurtTime > 0 || player.distanceToSqr(Vec3.atCenterOf(restAt)) > REACH * REACH)) {
            val away = LedgerScreen()
            away.planned.addAll(planned)
            minecraft.gui.setScreen(away)
            return
        }
        refreshButtons()
    }

    // ---- Doing things ----

    private fun learn() {
        if (planned.isEmpty()) return
        // A request holds at most 64 nodes; the server takes them in order
        for (part in planned.chunked(TreePayload.MAX_IDS)) {
            ClientPacketDistributor.sendToServer(TreePayload(TreePayload.LEARN, part, Optional.empty()))
        }
        sent = planned.toList()
        sentAt = ticks
        planned.clear()
        refreshButtons()
    }

    /** A left click on [node]: plan it, or the way to it, or take it out of the plan. */
    private fun plan(node: Node, s: Standing) {
        when (stateOf(node, s)) {
            State.PLANNED -> unplan(node.id)
            State.LEARNED -> {}
            else -> {
                val path = pathTo(node, s)
                when {
                    path == null -> Ui.hover()
                    path.size > s.points -> say(if (s.points <= 0) "gui.guildmark.tree.no_points" else "gui.guildmark.tree.too_far", path.size, max(0, s.points))
                    else -> {
                        for (step in path) planned.add(step.id)
                        Ui.select()
                    }
                }
            }
        }
        refreshButtons()
    }

    /** Takes [id] out of the plan, with whatever planned beyond it no longer reaches the tree. */
    private fun unplan(id: String) {
        planned.remove(id)
        val player = minecraft.player ?: return
        prune(HashSet(player.getData(ModAttachments.SHEET).nodes).apply { addAll(sent) })
        Ui.hover()
    }

    private fun say(key: String, vararg args: Any) {
        minecraft.player?.sendOverlayMessage(Component.translatable(key, *args).withColor(Ui.RED and 0xFFFFFF))
        Ui.hover()
    }

    private fun askForget(node: Node, s: Standing) {
        val player = minecraft.player ?: return
        when {
            node.id in sent -> {}
            !canRest -> say("gui.guildmark.tree.forget.rest")
            !SkillTree.connected(s.sheet.nodes - node.id) -> say("gui.guildmark.tree.forget.held")
            !player.isCreative && TownControl.marks(player) < Levelling.UNLEARN_COST -> say("message.guildmark.tree_marks", Levelling.UNLEARN_COST)
            else -> ask(Ask(
                Component.translatable("gui.guildmark.tree.forget.ask", node.title, Levelling.UNLEARN_COST),
                Component.translatable("gui.guildmark.tree.forget"),
            ) {
                ClientPacketDistributor.sendToServer(TreePayload(TreePayload.UNLEARN, listOf(node.id), Optional.ofNullable(restAt)))
            })
        }
    }

    private fun askReset() {
        val s = standing() ?: return
        val player = minecraft.player ?: return
        val cost = Levelling.resetCost(s.sheet)
        if (!player.isCreative && TownControl.marks(player) < cost) {
            say("message.guildmark.tree_marks", cost)
            return
        }
        ask(Ask(Component.translatable("gui.guildmark.tree.reset.ask", s.sheet.nodes.size, cost), Component.translatable("gui.guildmark.tree.reset.yes")) {
            planned.clear()
            ClientPacketDistributor.sendToServer(TreePayload(TreePayload.RESET, listOf(), Optional.ofNullable(restAt)))
        })
    }

    private fun ask(question: Ask) {
        asking = question
        showAsk(question)
        Ui.page()
        refreshButtons()
    }

    private fun showAsk(question: Ask) {
        for (b in askButtons) removeWidget(b)
        askButtons.clear()
        val cx = (viewX0 + viewX1) / 2
        val cy = (viewY0 + viewY1) / 2
        val yes = Ui.button(question.yes, 84) {
            question.action()
            closeAsk()
        }
        val no = Ui.button(Component.translatable("gui.guildmark.tree.keep"), 84) { closeAsk() }
        yes.setPosition(cx - 88, cy + 12)
        no.setPosition(cx + 4, cy + 12)
        askButtons.add(addRenderableWidget(yes))
        askButtons.add(addRenderableWidget(no))
    }

    private fun closeAsk() {
        asking = null
        for (b in askButtons) removeWidget(b)
        askButtons.clear()
        refreshButtons()
    }

    // ---- Input ----

    override fun keyPressed(event: KeyEvent): Boolean {
        if (asking != null) {
            if (event.isEscape()) {
                closeAsk()
                return true
            }
            return super.keyPressed(event)
        }
        if (KEY.matches(event)) {
            onClose()
            return true
        }
        val step = PAN_STEP / zoom
        when (event.key()) {
            InputConstants.KEY_SPACE -> {
                centerX = 0f
                centerY = 0f
            }
            InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> zoomAt(1, midX.toDouble(), midY.toDouble())
            InputConstants.KEY_MINUS, KEY_NUMPAD_MINUS -> zoomAt(-1, midX.toDouble(), midY.toDouble())
            InputConstants.KEY_LEFT -> pan(-step, 0f)
            InputConstants.KEY_RIGHT -> pan(step, 0f)
            InputConstants.KEY_UP -> pan(0f, -step)
            InputConstants.KEY_DOWN -> pan(0f, step)
            else -> return super.keyPressed(event)
        }
        return true
    }

    private fun inView(x: Double, y: Double): Boolean = x >= viewX0 && x < viewX1 && y >= viewY0 && y < viewY1

    private fun overWidget(x: Double, y: Double): Boolean = children().any {
        it is AbstractWidget && it.visible && x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height
    }

    private fun hotAt(x: Double, y: Double): Hot? = hots.lastOrNull { x >= it.x0 && x < it.x1 && y >= it.y0 && y < it.y1 }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (asking != null) return true
        val x = event.x()
        val y = event.y()
        hotAt(x, y)?.skill?.let {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) focus(it)
            return true
        }
        if (!inView(x, y)) return false
        when (event.button()) {
            InputConstants.MOUSE_BUTTON_LEFT -> {
                pressed = true
                dragged = 0.0
            }
            InputConstants.MOUSE_BUTTON_RIGHT -> {
                val s = standing() ?: return true
                val node = nodeAt(x, y) ?: return true
                when (stateOf(node, s)) {
                    State.PLANNED -> unplan(node.id)
                    State.LEARNED -> if (node.id != SkillTree.ROOT) askForget(node, s)
                    else -> {}
                }
                refreshButtons()
            }
            else -> return false
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (!pressed) return super.mouseDragged(event, dx, dy)
        pan((-dx / zoom).toFloat(), (-dy / zoom).toFloat())
        dragged += abs(dx) + abs(dy)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (!pressed || event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseReleased(event)
        pressed = false
        if (dragged < DRAG_SLOP && asking == null) {
            val s = standing() ?: return true
            nodeAt(event.x(), event.y())?.let { plan(it, s) }
        }
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0 || asking != null) return true
        val steps = if (scrollY > 0) 1 else -1
        if (inView(x, y)) zoomAt(steps, x, y) else zoomAt(steps, midX.toDouble(), midY.toDouble())
        return true
    }

    private fun pan(dx: Float, dy: Float) {
        centerX = (centerX + dx).coerceIn(-TREE_RADIUS, TREE_RADIUS)
        centerY = (centerY + dy).coerceIn(-TREE_RADIUS, TREE_RADIUS)
    }

    /** Zooms in ([steps] > 0) or out about the point ([x], [y]) on screen, which stays where it is. */
    private fun zoomAt(steps: Int, x: Double, y: Double) {
        val before = zoom
        zoom = (zoom * ZOOM_STEP.pow(steps)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (zoom == before) return
        val tx = centerX + ((x - midX) / before).toFloat()
        val ty = centerY + ((y - midY) / before).toFloat()
        centerX = tx - ((x - midX) / zoom).toFloat()
        centerY = ty - ((y - midY) / zoom).toFloat()
    }

    /** Puts [skill]'s branch in the middle of the view. */
    private fun focus(skill: Skill) {
        val middle = SkillTree.nodes.values.firstOrNull { it.skill == skill && it.id.endsWith("/m4") } ?: return
        centerX = middle.x
        centerY = middle.y
        zoom = max(zoom, FOCUS_ZOOM)
        Ui.page()
    }

    private fun sx(x: Float): Float = midX + (x - centerX) * zoom
    private fun sy(y: Float): Float = midY + (y - centerY) * zoom

    private fun nodeAt(x: Double, y: Double): Node? {
        if (!inView(x, y) || overWidget(x, y)) return null
        val tx = centerX + ((x - midX) / zoom).toFloat()
        val ty = centerY + ((y - midY) / zoom).toFloat()
        var best: Node? = null
        var bestD = Float.MAX_VALUE
        for (node in SkillTree.nodes.values) {
            val dx = node.x - tx
            val dy = node.y - ty
            val d = dx * dx + dy * dy
            // Small sockets are a little easier to hit when zoomed out
            val r = node.kind.size / 2f + 2f / zoom
            if (d <= r * r && d < bestD) {
                best = node
                bestD = d
            }
        }
        return best
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        graphics.fill(0, 0, width, height, ARGB.color(min(1f, (ticks + a) / 5f) * 0.8f, 0x06060B))
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val s = standing() ?: return
        val mx = mouseX.toDouble()
        val my = mouseY.toDouble()
        val node = if (asking == null && (!pressed || dragged < DRAG_SLOP)) nodeAt(mx, my) else null
        hovered = node
        hoverPath = if (node != null && node.id !in s.owned) pathTo(node, s)?.takeIf { it.size > 1 } else null
        val all = HashMap<String, State>(SkillTree.nodes.size * 2)
        for (n in SkillTree.nodes.values) all[n.id] = stateOf(n, s)
        states = all
        drawTree(graphics, s, a)
        hots.clear()
        drawSidebar(graphics, s)
        drawHints(graphics, s)
        asking?.let { drawAsk(graphics, it) }
        super.extractRenderState(graphics, mouseX, mouseY, a)
        if (asking != null) return
        if (node != null) {
            hoveredSkill = null
            drawTip(graphics, nodeLines(node, s), mouseX, mouseY)
            return
        }
        val hot = hotAt(mx, my)
        hoveredSkill = hot?.skill
        if (hot != null) drawTip(graphics, hot.lines, mouseX, mouseY)
    }

    private fun drawTree(graphics: GuiGraphicsExtractor, s: Standing, a: Float) {
        Ui.inset(graphics, viewX0, viewY0, viewX1 - viewX0, viewY1 - viewY0)
        graphics.enableScissor(viewX0 + 2, viewY0 + 2, viewX1 - 2, viewY1 - 2)
        drawSky(graphics)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(midX - centerX * zoom, midY - centerY * zoom)
        pose.scale(zoom, zoom)
        val pulse = 0.6f + 0.4f * sin((ticks + a) * 0.15f)

        // The lines first, so the sockets sit over their ends
        for ((from, to) in EDGES) {
            val sa = states.getValue(from.id)
            val sb = states.getValue(to.id)
            val litA = sa == State.LEARNED || sa == State.PLANNED
            val litB = sb == State.LEARNED || sb == State.PLANNED
            when {
                litA && litB -> edge(graphics, from, to, EDGE_LIT, if (sa == State.PLANNED || sb == State.PLANNED) pulse else 1f)
                litA && sb == State.OPEN || litB && sa == State.OPEN -> edge(graphics, from, to, EDGE_OPEN, 1f)
                else -> edge(graphics, from, to, EDGE, 1f)
            }
        }

        val branch = hoveredSkill?.id
        val path = hoverPath?.toHashSet()
        for (node in SkillTree.nodes.values) {
            val state = states.getValue(node.id)
            val size = node.kind.size
            pose.pushMatrix()
            pose.translate(node.x, node.y)
            val glow = when {
                node === hovered -> 1f
                path != null && node in path -> 0.5f
                state == State.PLANNED -> 0.75f * pulse
                branch != null && node.branch == branch -> 0.5f
                else -> 0f
            }
            if (glow > 0f) centered(graphics, HOVER, (size * GLOW_SHARE).roundToInt(), glow)
            centered(graphics, socket(node.kind, state), size, 1f)
            if (node.kind != NodeKind.ROOT) {
                val alpha = when (state) {
                    State.LEARNED, State.PLANNED -> 1f
                    State.OPEN -> 0.9f
                    State.BARRED -> 0.55f
                    State.LOCKED -> 0.4f
                }
                centered(graphics, sprite(node.icon), (size * ICON_SHARE).roundToInt(), alpha)
            }
            pose.popMatrix()
        }
        pose.popMatrix()
        drawLabels(graphics)
        graphics.disableScissor()
    }

    /** The dark behind the tree: a field of faint stars, and a haze about the root that drifts slower than the tree. */
    private fun drawSky(graphics: GuiGraphicsExtractor) {
        val w = viewX1 - viewX0
        val h = viewY1 - viewY0
        val u = Math.floorMod((centerX * zoom * SKY_DRIFT).roundToInt() * TEXELS, SKY_TEXELS).toFloat()
        val v = Math.floorMod((centerY * zoom * SKY_DRIFT).roundToInt() * TEXELS, SKY_TEXELS).toFloat()
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, viewX0, viewY0, u, v, w, h, w * TEXELS, h * TEXELS, SKY_TEXELS, SKY_TEXELS)
        val n = (NEBULA_SIZE * (0.6f + 0.8f * zoom)).roundToInt()
        val nx = (midX - centerX * zoom * NEBULA_DRIFT - n / 2f).roundToInt()
        val ny = (midY - centerY * zoom * NEBULA_DRIFT - n / 2f).roundToInt()
        graphics.blit(RenderPipelines.GUI_TEXTURED, NEBULA, nx, ny, 0f, 0f, n, n, NEBULA_TEXELS, NEBULA_TEXELS, NEBULA_TEXELS, NEBULA_TEXELS, ARGB.white(0.8f))
    }

    /** Each branch's name past its keystone, while most of the tree is in view. */
    private fun drawLabels(graphics: GuiGraphicsExtractor) {
        if (zoom >= LABEL_ZOOM) return
        val fade = ((LABEL_ZOOM - zoom) / 0.12f).coerceIn(0f, 1f)
        val color = ARGB.color(fade * 0.9f, Ui.TEXT_SOFT)
        for (label in LABELS) {
            val w = font.width(label.text)
            // Set out from the tree: left of the anchor on the left side, right of it on the right
            val x = sx(label.x) - w / 2f + label.cos * w / 2f
            val y = sy(label.y) - 4f + label.sin * 5f
            graphics.text(font, label.text, x.roundToInt(), y.roundToInt(), color, false)
        }
    }

    /** [sprite] drawn [size] square on the middle of the pose's origin, also when [size] is odd. */
    private fun centered(graphics: GuiGraphicsExtractor, sprite: Identifier, size: Int, alpha: Float) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(-size / 2f, -size / 2f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 0, 0, size, size, alpha)
        pose.popMatrix()
    }

    /** A line from [from] to [to]: the sprite laid along it, [EDGE_WIDTH] across, centred on it. */
    private fun edge(graphics: GuiGraphicsExtractor, from: Node, to: Node, sprite: Identifier, alpha: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1f) return
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(from.x, from.y)
        pose.rotate(atan2(dy, dx))
        pose.translate(0f, -EDGE_WIDTH / 2f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 0, 0, length.roundToInt(), EDGE_WIDTH, alpha)
        pose.popMatrix()
    }

    private fun socket(kind: NodeKind, state: State): Identifier {
        val suffix = when (state) {
            State.LEARNED, State.PLANNED -> "_lit"
            State.OPEN -> "_open"
            else -> ""
        }
        return when (kind) {
            NodeKind.ROOT -> ROOT
            NodeKind.MINOR -> sprite("minor$suffix")
            NodeKind.NOTABLE -> sprite("notable$suffix")
            NodeKind.KEYSTONE -> sprite("keystone$suffix")
        }
    }

    private fun drawHints(graphics: GuiGraphicsExtractor, s: Standing) {
        val key = if (canRest && s.sheet.nodes.isNotEmpty()) "gui.guildmark.tree.hint.rest" else "gui.guildmark.tree.hint"
        val room = viewX1 - viewX0 - 16 - (if (resetButton?.visible == true) RESET_WIDTH + 8 else 0)
        val hint = font.plainSubstrByWidth(Component.translatable(key).string, room)
        backing(graphics, viewX0 + 8, viewY0 + 8, font.width(hint))
        graphics.text(font, hint, viewX0 + 8, viewY0 + 8, Ui.TEXT_FAINT, false)
        if (planned.isNotEmpty() && asking == null) {
            val line = Component.translatable("gui.guildmark.tree.planned", planned.size, max(0, s.points))
            val center = (viewX0 + viewX1) / 2
            val w = font.width(line)
            backing(graphics, center - w / 2, viewY1 - 40, w)
            graphics.centeredText(font, line, center, viewY1 - 40, Ui.GOLD)
        }
    }

    /** A dark band behind a line of text laid over the tree, so the nodes under it don't swallow it. */
    private fun backing(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int) {
        graphics.fill(x - 4, y - 3, x + w + 4, y + 11, TEXT_BACK)
    }

    // ---- The sidebar ----

    private fun drawSidebar(graphics: GuiGraphicsExtractor, s: Standing) {
        val player = minecraft.player ?: return
        Ui.panel(graphics, sideX, sideY, sideW, sideH)
        val x = sideX + 10
        val w = sideW - 20
        val center = sideX + sideW / 2
        val progress = s.progress
        var y = sideY + 8
        Ui.centeredHeading(graphics, font, Component.translatable("gui.guildmark.ledger"), center, y, Ui.GOLD_BRIGHT)
        y += 13
        val rank = Component.literal(progress.rank.letter).withColor(progress.rank.color and 0xFFFFFF)
        graphics.centeredText(font, Component.translatable("gui.guildmark.ledger.level", progress.level, rank), center, y, Ui.TEXT_SOFT)
        y += 12

        // Banked guild XP against the next level's price
        val ready = s.raises
        row(graphics, x, y, w, Component.translatable("gui.guildmark.ledger.xp"), Component.literal(grouped(progress.xp)), if (ready > 0) Ui.GOLD_BRIGHT else Ui.GOLD)
        hots.add(Hot(x, y - 1, x + w, y + 13, xpLines(progress, s)))
        y += 10
        val share = if (progress.isMaxLevel()) 1f else (progress.xp.toFloat() / max(1, progress.xpToNext())).coerceIn(0f, 1f)
        graphics.fill(x, y, x + w, y + 3, BAR_BACK)
        graphics.fill(x, y, x + (w * share).toInt(), y + 3, if (ready > 0) Ui.GOLD_BRIGHT else Ui.GOLD)
        y += 7
        if (ready > 0 && !canRest) {
            val text = Component.translatable("gui.guildmark.ledger.ready", ready).string
            graphics.text(font, font.plainSubstrByWidth(text, w), x, y, Ui.GOLD_BRIGHT, false)
            y += 10
        }
        val points = max(0, s.points)
        row(graphics, x, y, w, Component.translatable("gui.guildmark.ledger.points"), Component.literal(points.toString()), if (points > 0) Ui.GOLD_BRIGHT else Ui.TEXT)
        hots.add(Hot(x, y - 1, x + w, y + 9, listOf(
            Line(Component.translatable("gui.guildmark.ledger.points.tip", progress.level - 1, s.skills.milestones(), s.sheet.nodes.size), Ui.TEXT),
            Line(Component.translatable("gui.guildmark.ledger.points.how"), Ui.TEXT_FAINT),
        )))
        y += 10
        if (density != Density.TINY) {
            row(graphics, x, y, w, Component.translatable("gui.guildmark.ledger.marks"), Component.literal(TownControl.marks(player).toString()), Ui.GOLD)
            y += 12
        } else {
            y += 2
        }

        Ui.divider(graphics, x, y, w)
        y += 6
        Ui.headingAt(graphics, font, Component.translatable("gui.guildmark.ledger.stats"), x, y, Ui.GOLD)
        y += 13
        val rowH = if (density == Density.FULL) 10 else 9
        val stats = Stat.entries
        if (density == Density.TINY) {
            val colW = (w - 4) / 2
            for ((i, stat) in stats.withIndex()) statRow(graphics, s, stat, x + (i % 2) * (colW + 4), y + (i / 2) * rowH, colW, rowH, true)
            y += (stats.size + 1) / 2 * rowH
        } else {
            for (stat in stats) {
                statRow(graphics, s, stat, x, y, w, rowH, false)
                y += rowH
            }
        }
        y += 3
        Ui.divider(graphics, x, y, w)
        y += 6
        Ui.headingAt(graphics, font, Component.translatable("gui.guildmark.ledger.skills"), x, y, Ui.GOLD)
        y += 13
        val skills = Skill.entries
        if (density == Density.FULL) {
            for (skill in skills) {
                skillRow(graphics, s, skill, x, y, w, rowH, false)
                y += rowH
            }
        } else {
            // Columns of icons and levels; the names are on hover
            val columns = if (density == Density.TINY) 3 else 2
            val perColumn = (skills.size + columns - 1) / columns
            val colW = (w - 4 * (columns - 1)) / columns
            for ((i, skill) in skills.withIndex()) {
                skillRow(graphics, s, skill, x + (i / perColumn) * (colW + 4), y + (i % perColumn) * rowH, colW, rowH, true)
            }
            y += perColumn * rowH
        }
        drawTotals(graphics, s, x, y + 3, w)
    }

    /** What the learned nodes add up to, under the skills, in as many lines as the room above the foot holds. */
    private fun drawTotals(graphics: GuiGraphicsExtractor, s: Standing, x: Int, top: Int, w: Int) {
        val lines = totalLines(s)
        if (lines.isEmpty()) return
        val bottom = sideY + sideH - (if (canRest) GROW_FOOT else MARGIN)
        val room = (bottom - top - TOTALS_HEAD) / 10
        if (room < 2) return
        Ui.divider(graphics, x, top, w)
        Ui.headingAt(graphics, font, Component.translatable("gui.guildmark.ledger.totals"), x, top + 6, Ui.GOLD)
        var y = top + TOTALS_HEAD
        val shown = if (lines.size <= room) lines else lines.take(room - 1)
        for (line in shown) {
            graphics.text(font, cut(line.text.string, w), x, y, line.color, false)
            y += 10
        }
        if (shown.size < lines.size) {
            graphics.text(font, Component.translatable("gui.guildmark.ledger.totals.more", lines.size - shown.size), x, y, Ui.TEXT_FAINT, false)
            y += 10
        }
        hots.add(Hot(x, top + 6, x + w, y, listOf(Line(Component.translatable("gui.guildmark.ledger.totals"), Ui.GOLD)) + lines))
    }

    /** The learned nodes' effects summed bonus by bonus, in the order the bonuses are declared. */
    private fun totalLines(s: Standing): List<Line> {
        val sums = HashMap<Triple<Bonus, Skill?, SkillGroup?>, Double>()
        for (effect in SkillTree.effects(s.learned)) sums.merge(Triple(effect.bonus, effect.skill, effect.group), effect.amount, Double::plus)
        return sums.map { (key, amount) -> Effect(key.first, amount, key.second, key.third) }
            .filter { abs(it.amount) > 1e-9 }
            .sortedWith(compareBy<Effect>({ it.bonus.ordinal }, { it.skill?.ordinal ?: -1 }, { it.group?.ordinal ?: -1 }))
            .map { Line(it.line(), if (it.helps) Ui.GREEN else Ui.RED) }
    }

    private fun statRow(graphics: GuiGraphicsExtractor, s: Standing, stat: Stat, x: Int, y: Int, w: Int, rowH: Int, short: Boolean) {
        val value = s.sheet.stat(stat)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("stat/${stat.id}"), x, y - 1, ROW_ICON, ROW_ICON)
        row(graphics, x + 12, y, w - 12, if (short) Component.empty() else stat.title, Component.literal(value.toString()), stat.color)
        hots.add(Hot(x, y - 1, x + w, y + rowH - 1, statLines(stat, value)))
    }

    private fun skillRow(graphics: GuiGraphicsExtractor, s: Standing, skill: Skill, x: Int, y: Int, w: Int, rowH: Int, short: Boolean) {
        val level = s.skills.level(skill)
        val lit = hoveredSkill == skill
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("icon/${skill.id}"), x, y - 1, ROW_ICON, ROW_ICON)
        row(graphics, x + 12, y, w - 12, if (short) Component.empty() else skill.title, Component.literal(level.toString()),
            if (lit) Ui.GOLD_BRIGHT else Ui.TEXT, lit)
        // How far to the next level, as a hairline under the row
        val barX = x + 12
        val barW = w - 12
        graphics.fill(barX, y + rowH - 2, barX + barW, y + rowH - 1, BAR_BACK)
        graphics.fill(barX, y + rowH - 2, barX + (barW * s.skills.progress(skill)).toInt(), y + rowH - 1, SKILL_BAR)
        hots.add(Hot(x, y - 1, x + w, y + rowH - 1, skillLines(skill, s), skill))
    }

    /** A label on the left and its value on the right, the label cut short to make room. */
    private fun row(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, label: Component, value: Component, color: Int, bright: Boolean = false) {
        val vw = font.width(value)
        val full = label.string
        if (full.isNotEmpty()) graphics.text(font, cut(full, w - vw - 4), x, y, if (bright) Ui.TEXT else Ui.TEXT_SOFT, false)
        graphics.text(font, value, x + w - vw, y, color, false)
    }

    /** [text] as it fits in [room], cut short with an ellipsis if it doesn't. */
    private fun cut(text: String, room: Int): String =
        if (font.width(text) <= room) text else font.plainSubstrByWidth(text, room - font.width(ELLIPSIS)).trimEnd() + ELLIPSIS

    private fun grouped(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    // ---- What hovering says ----

    private fun effectLines(effects: List<Effect>): List<Line> = effects.map { Line(it.line(), if (it.helps) Ui.GREEN else Ui.RED) }

    private fun xpLines(progress: GuildProgress, s: Standing): List<Line> {
        if (progress.isMaxLevel()) return listOf(Line(Component.translatable("gui.guildmark.ledger.xp.max", grouped(progress.xp)), Ui.GOLD))
        val lines = arrayListOf(Line(Component.translatable("gui.guildmark.ledger.xp.tip", grouped(progress.xp), grouped(progress.xpToNext()), progress.level + 1), Ui.TEXT))
        val banked = progress.levelsBanked()
        if (banked > 0) lines.add(Line(Component.translatable("gui.guildmark.ledger.xp.banked", banked), Ui.GOLD_BRIGHT))
        val owed = s.sheet.owed(progress.level)
        if (owed > 0) lines.add(Line(Component.translatable("gui.guildmark.ledger.xp.owed", owed), Ui.GOLD_BRIGHT))
        val where = if (GuildmarkConfig.WARDFIRES.get()) "gui.guildmark.ledger.xp.where" else "gui.guildmark.ledger.xp.where.anywhere"
        lines.add(Line(Component.translatable(where), Ui.TEXT_FAINT))
        return lines
    }

    private fun statLines(stat: Stat, value: Int): List<Line> {
        val lines = arrayListOf(Line(stat.title, stat.color), Line(stat.description, Ui.TEXT_SOFT))
        if (value > Stat.BASE) {
            lines.add(Line(Component.translatable("gui.guildmark.ledger.stat.gives", value), Ui.TEXT_FAINT))
            lines.addAll(effectLines(stat.effectsAt(value)))
        }
        lines.add(Line(Component.translatable("gui.guildmark.ledger.stat.caps", Stat.SOFT, Stat.HARD), Ui.TEXT_FAINT))
        return lines
    }

    private fun skillLines(skill: Skill, s: Standing): List<Line> {
        val level = s.skills.level(skill)
        val lines = arrayListOf(
            Line(skill.title, Ui.GOLD),
            Line(skill.description, Ui.TEXT_SOFT),
            Line(Component.translatable("gui.guildmark.ledger.skill.level", level, Skill.MAX, (s.skills.progress(skill) * 100).toInt()), Ui.TEXT),
        )
        if (level > 1) lines.add(Line(skill.passiveAt(level).line(), Ui.GREEN))
        Skill.MILESTONES.firstOrNull { it > level }?.let { lines.add(Line(Component.translatable("gui.guildmark.ledger.skill.milestone", it), Ui.TEXT_FAINT)) }
        lines.add(Line(Component.translatable("gui.guildmark.ledger.skill.look"), Ui.TEXT_FAINT))
        return lines
    }

    private fun nodeLines(node: Node, s: Standing): List<Line> {
        val lines = ArrayList<Line>()
        val titleColor = when (node.kind) {
            NodeKind.KEYSTONE, NodeKind.ROOT -> Ui.GOLD_BRIGHT
            NodeKind.NOTABLE -> Ui.GOLD
            NodeKind.MINOR -> Ui.TEXT
        }
        lines.add(Line(node.title, titleColor))
        if (node.kind != NodeKind.ROOT) {
            val where = node.skill?.title ?: Component.translatable("gui.guildmark.tree.branch.${node.branch}")
            lines.add(Line(Component.translatable("gui.guildmark.tree.kind.${node.kind.name.lowercase(Locale.ROOT)}", where), Ui.TEXT_FAINT))
        }
        lines.addAll(effectLines(node.effects))
        node.skill?.let { skill ->
            if (node.requires > 0) {
                val met = s.skills.level(skill) >= node.requires
                lines.add(Line(Component.translatable("gui.guildmark.tree.needs", skill.title, node.requires), if (met) Ui.TEXT_FAINT else Ui.RED))
            }
        }
        node.rank?.let { rank ->
            if (rank.ordinal > 0) {
                val met = s.progress.adventurerRank >= rank.ordinal
                lines.add(Line(Component.translatable("gui.guildmark.tree.needs_rank", rank.letter), if (met) Ui.TEXT_FAINT else Ui.RED))
            }
        }
        node.flavour?.let { lines.add(Line(it.copy().withStyle { style -> style.withItalic(true) }, Ui.TEXT_SOFT)) }
        status(node, s)?.let { lines.add(it) }
        return lines
    }

    /** What a click on [node] would do, or why it can't be had yet. */
    private fun status(node: Node, s: Standing): Line? = when (states[node.id] ?: stateOf(node, s)) {
        State.LEARNED -> when {
            node.id == SkillTree.ROOT -> null
            canRest -> Line(Component.translatable("gui.guildmark.tree.status.learned.rest", Levelling.UNLEARN_COST), Ui.GREEN)
            else -> Line(Component.translatable("gui.guildmark.tree.status.learned"), Ui.GREEN)
        }
        State.PLANNED -> Line(Component.translatable("gui.guildmark.tree.status.planned"), Ui.GOLD)
        State.OPEN -> Line(Component.translatable("gui.guildmark.tree.status.open"), Ui.GOLD_BRIGHT)
        State.BARRED -> if (blocked(node, s) == Blocked.NO_POINTS) Line(Component.translatable("gui.guildmark.tree.status.no_points"), Ui.RED) else null
        State.LOCKED -> {
            val path = pathTo(node, s)
            when {
                path == null -> if (SkillTree.meetsRequirement(node, s.progress, s.skills)) Line(Component.translatable("gui.guildmark.tree.status.blocked"), Ui.TEXT_FAINT) else null
                path.size > s.points -> Line(Component.translatable("gui.guildmark.tree.status.far", path.size, max(0, s.points)), Ui.RED)
                else -> Line(Component.translatable("gui.guildmark.tree.status.path", path.size), Ui.GOLD_BRIGHT)
            }
        }
    }

    /** Lines in a small dark panel by the cursor, each wrapped to [TIP_WIDTH]. */
    private fun drawTip(graphics: GuiGraphicsExtractor, lines: List<Line>, mouseX: Int, mouseY: Int) {
        var wrapped = lines.flatMap { line -> font.split(line.text, TIP_WIDTH).map { it to line.color } }
        if (wrapped.isEmpty()) return
        // A long list (a big tree's totals) is cut to the screen's height
        val most = max(2, (height - 18) / 10)
        if (wrapped.size > most) wrapped = wrapped.take(most - 1) + (Component.literal(ELLIPSIS).visualOrderText to Ui.TEXT_FAINT)
        val w = wrapped.maxOf { font.width(it.first) } + 16
        val h = wrapped.size * 10 + 10
        var x = mouseX + 12
        if (x + w > width - 4) x = mouseX - 12 - w
        val y = (mouseY - 6).coerceIn(4, max(4, height - h - 4))
        graphics.nextStratum()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, x, y, w, h)
        for ((i, line) in wrapped.withIndex()) graphics.text(font, line.first, x + 8, y + 6 + i * 10, line.second, false)
    }

    private fun drawAsk(graphics: GuiGraphicsExtractor, question: Ask) {
        val cx = (viewX0 + viewX1) / 2
        val cy = (viewY0 + viewY1) / 2
        val lines = font.split(question.text, ASK_WIDTH - 24)
        val h = lines.size * 10 + 48
        graphics.nextStratum()
        graphics.fill(viewX0, viewY0, viewX1, viewY1, SHADE)
        Ui.panel(graphics, cx - ASK_WIDTH / 2, cy + 40 - h, ASK_WIDTH, h)
        for ((i, line) in lines.withIndex()) graphics.centeredText(font, line, cx, cy + 50 - h + i * 10, Ui.TEXT)
    }

    /** A branch's name, set out past its keystone at ([x], [y]) in tree units, facing out along ([cos], [sin]). */
    private class Label(val x: Float, val y: Float, val cos: Float, val sin: Float, val text: Component)

    companion object {
        val KEY = KeyMapping("key.guildmark.ledger", InputConstants.KEY_G, KeyMapping.Category(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "main")))

        private fun sprite(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tree/$name")

        private val ROOT = sprite("root")
        private val HOVER = sprite("hover")
        private val EDGE = sprite("edge")
        private val EDGE_OPEN = sprite("edge_open")
        private val EDGE_LIT = sprite("edge_lit")
        private val BACKGROUND = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/tree/background.png")
        private val NEBULA = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/tree/nebula.png")
        /** Texels per GUI pixel in the sky, its tile's size in texels, and the haze's. */
        private const val TEXELS = 2
        private const val SKY_TEXELS = 512
        private const val NEBULA_TEXELS = 512
        /** The haze's size in GUI pixels at zoom 0.5. */
        private const val NEBULA_SIZE = 360
        /** How much the stars and the haze move as the tree does. */
        private const val SKY_DRIFT = 0.2f
        private const val NEBULA_DRIFT = 0.5f

        private const val MARGIN = 6
        private const val SIDE_MIN = 132
        private const val SIDE_MAX = 172
        /** Room kept under the sidebar's rows: for Grow stronger at rest, or for the levels-ready line. */
        private const val GROW_FOOT = 32
        private const val READY_FOOT = 16
        private const val RESET_WIDTH = 116
        private const val ASK_WIDTH = 232
        private const val EDGE_WIDTH = 3
        /** The sidebar's stat and skill icons: half their art's size, so they stay crisp at GUI scale 2. */
        private const val ROW_ICON = 8
        /** Share of a socket its icon fills, and how far round it the glow reaches. */
        private const val ICON_SHARE = 0.62f
        private const val GLOW_SHARE = 1.7f
        private const val TIP_WIDTH = 184
        /** The divider and heading over the tree's totals. */
        private const val TOTALS_HEAD = 19
        private const val ELLIPSIS = "…"
        /** How far from the fire the ledger still counts as at rest (as the server checks). */
        private const val REACH = 6.0
        private const val SENT_TICKS = 40
        private const val DRAG_SLOP = 4.0
        private const val PAN_STEP = 48f
        private const val KEY_NUMPAD_MINUS = 86
        /** The track under a bar: a shade lighter than the panel, so a short bar reads as part of a whole. */
        private val BAR_BACK = 0xFF2A2530.toInt()
        private val SKILL_BAR = 0xC0D9B46A.toInt()
        private val SHADE = 0x90000000.toInt()
        private val TEXT_BACK = 0xC0050509.toInt()

        private const val MIN_ZOOM = 0.28f
        private const val MAX_ZOOM = 2.4f
        private const val ZOOM_STEP = 1.18f
        private const val FOCUS_ZOOM = 0.9f
        /** Below this zoom the branches are named. */
        private const val LABEL_ZOOM = 0.7f
        /** Tree units shown from the middle when the ledger first opens, and how far the view can wander. */
        private const val FIT_RADIUS = 300f
        private const val TREE_RADIUS = 560f
        private val LABEL_RADIUS = SkillTree.BRANCH_START + SkillTree.ROW * 8.1f + 34f

        // Where the view was left, for next time
        private var centerX = 0f
        private var centerY = 0f
        private var zoom = 0.5f
        private var placed = false

        /** Every link once. */
        private val EDGES: List<Pair<Node, Node>> by lazy {
            val seen = HashSet<String>()
            val out = ArrayList<Pair<Node, Node>>()
            for (node in SkillTree.nodes.values) for (other in node.links) {
                val key = if (node.id < other.id) node.id + "|" + other.id else other.id + "|" + node.id
                if (seen.add(key)) out.add(node to other)
            }
            out
        }

        /** One label a branch, along the line from the root through its first node. */
        private val LABELS: List<Label> by lazy {
            SkillTree.nodes.values.filter { it.id.endsWith("/e") }.map { first ->
                val angle = atan2(first.y, first.x)
                val c = cos(angle)
                val s = sin(angle)
                val text = first.skill?.title ?: Component.translatable("gui.guildmark.tree.branch.${first.branch}")
                Label(c * LABEL_RADIUS, s * LABEL_RADIUS, c, s, text)
            }
        }
    }
}
