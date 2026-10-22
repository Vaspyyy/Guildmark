package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.CombatHud
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.guild.RivalGuild
import io.github.vaspyyy.guildmark.network.TrackedOrder
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.story.MainQuest
import io.github.vaspyyy.guildmark.story.MainQuestState
import net.minecraft.world.level.Level
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.locale.Language
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The on-screen contract tracker: the contracts in your inventory, soonest deadline first, each with
 * its progress, days left and an arrow pointing where to go next; then the orders you've taken as a supplier at the
 * capitals (see [io.github.vaspyyy.guildmark.economy.Orders]), as the server sends them.
 */
object ContractTracker : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "contract_tracker")
    private val PANEL: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tracker/panel")
    private val ARROW: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tracker/arrow")

    private const val MAX_SHOWN = 3
    private const val WIDTH = 150
    private const val ROW_HEIGHT = 24
    private const val PADDING = 5
    /** Closer than this, the target counts as reached and the arrow is hidden. */
    private const val NEAR = 6.0
    // The panel is the interface's dark one since the HD pass, so the rows are inked with its palette (they were brown for the parchment it was)
    private val INK = Ui.TEXT
    private val FADED_INK = Ui.TEXT_SOFT
    private val DONE_INK = Ui.GREEN
    private val LATE_INK = Ui.RED

    /** Toggled with the tracker key or in Guildmark's settings, and kept between games. */
    var shown: Boolean
        get() = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.CONTRACT_TRACKER.get()
        set(value) {
            if (!GuildmarkClientConfig.SPEC.isLoaded) return
            GuildmarkClientConfig.CONTRACT_TRACKER.set(value)
            GuildmarkClientConfig.SPEC.save()
        }

    private class Entry(val stack: ItemStack, val note: QuestNote, val state: ContractState)

    /**
     * One line of the tracker: a contract, a rival's challenge, the main story's next step, an order, or a tale (then [tale] says how
     * it is drawn and moves, see [TaleTracker]). [key] names it while rows glide to their places.
     */
    private class Row(val icon: ItemStack, val title: Component, val status: Component, val color: Int, val target: BlockPos?, val kind: Int, val tale: TaleTracker.Line? = null) {
        var key: String = ""
    }

    /** Somewhere the tracker points, for the compass and the map; [lines] are what the map's tooltip says under its title, if the row has more to tell. */
    class Waypoint(val pos: BlockPos, val title: Component, val kind: Int, val lines: List<Component> = listOf())

    const val CONTRACT = 0
    const val CHALLENGE = 1
    const val STORY = 2
    const val ORDER = 3
    const val TALE = 4

    private val SHARD_ICON: ItemStack by lazy { MainQuest.shard(0) }
    private val STORY_INK = 0xFFB892E8.toInt()

    /** The orders the player holds as a supplier, at every capital, as the server last sent them. */
    var orders: List<TrackedOrder> = listOf()

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (!shown || minecraft.gui.hud.isHidden) return

        // The tales' rows keep a time of their own, and a tale that has just left stays a moment to fade (TaleTracker)
        TaleTracker.frame(minecraft)
        val live = rows(player)
        TaleTracker.observe(live.mapNotNull { it.tale })
        val rows = withGhosts(live)
        // Below the combat bars when they're showing
        val top = 4 + CombatHud.reservedHeight()

        val shownEntries = rows.take(MAX_SHOWN)
        val extra = rows.size - shownEntries.size
        // The panel grows and shrinks to its rows rather than jumping, and is gone when it is down to nothing
        val height = TaleTracker.height(if (rows.isEmpty()) 0 else PADDING * 2 + shownEntries.size * ROW_HEIGHT - 4 + if (extra > 0) 10 else 0)
        if (height <= 0) {
            renderParty(graphics, minecraft, player, top)
            return
        }
        val left = 4
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, WIDTH, height)
        // The rows are drawn inside the panel as it is now, so a row that comes while it is still growing is uncovered with it
        graphics.enableScissor(left, top, left + WIDTH, top + height)

        val font = minecraft.font
        shownEntries.forEachIndexed { i, entry ->
            val y = TaleTracker.slide(entry.key, top + PADDING + i * ROW_HEIGHT)
            val x = left + PADDING
            val textWidth = WIDTH - PADDING * 2 - 20 - 26
            val tale = entry.tale
            var fade = 1f
            if (tale != null) {
                fade = TaleTracker.draw(graphics, font, tale, x, y, textWidth)
            } else {
                graphics.item(entry.icon, x, y + 2)

                val title = font.substrByWidth(entry.title, textWidth)
                graphics.text(font, Language.getInstance().getVisualOrder(title), x + 20, y + 1, INK, false)
                graphics.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(entry.status, textWidth)), x + 20, y + 11, entry.color, false)
            }

            val target = entry.target ?: return@forEachIndexed
            if (fade < 0.05f) return@forEachIndexed
            val dx = target.x + 0.5 - player.x
            val dz = target.z + 0.5 - player.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance < NEAR) return@forEachIndexed
            val arrowX = left + WIDTH - PADDING - 13
            drawArrow(graphics, arrowX, y + 6, Math.toDegrees(atan2(dz, dx)).toFloat() - 90.0f - player.yRot, fade)
            val label = if (distance >= 1000) "%.1fk".format(distance / 1000) else "${distance.toInt()}"
            graphics.text(font, Component.literal(label), arrowX - font.width(label) / 2, y + 13, if (tale != null) ARGB.color(fade, TaleTracker.DISTANCE_INK) else FADED_INK, false)
        }
        if (extra > 0) {
            graphics.text(font, Component.translatable("gui.guildmark.tracker.more", extra), left + PADDING, top + height - PADDING - 8, FADED_INK, false)
        }
        graphics.disableScissor()
        TaleTracker.sweep()
        renderParty(graphics, minecraft, player, top + height + 3)
    }

    /** The rows to draw: the live ones, and a tale that has just left, fading where it stood. */
    private fun withGhosts(live: List<Row>): List<Row> {
        val ghosts = TaleTracker.ghosts()
        if (ghosts.isEmpty()) return live
        val rows = live.toMutableList()
        val start = live.count { it.kind == CHALLENGE || it.kind == STORY }
        val tales = live.count { it.kind == TALE }
        for (ghost in ghosts) {
            val row = Row(TaleTracker.QUILL, ghost.title, ghost.status, TaleTracker.inkOf(ghost.tone), null, TALE, ghost)
            row.key = "t${ghost.id}"
            rows.add(start + min(ghost.index, tales), row)
        }
        return rows
    }

    /** Rival challenges first, then the main story, then the tales followed, then the contracts carried, soonest deadline first, then the orders taken, soonest due first. */
    private fun rows(player: Player): List<Row> {
        val entries = (0 until player.inventory.containerSize)
            .map { player.inventory.getItem(it) }
            .mapNotNull { stack ->
                val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: return@mapNotNull null
                val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: return@mapNotNull null
                Entry(stack, note, state)
            }
            .sortedBy { it.state.deadline }
        val rows = mutableListOf<Row>()
        challengeRows(player).forEach { rows.add(it) }
        storyRow(player)?.let { rows.add(it) }
        // A tale is a row of its own and takes up the contract its step is working, so one tale is one row
        val folded = HashSet<Entry>()
        rows.addAll(taleRows(player, entries, folded))
        for (entry in entries) {
            if (entry in folded) continue
            val (status, color) = status(player, entry)
            rows.add(Row(entry.stack, entry.note.title(), status, color, target(player, entry), CONTRACT))
        }
        rows.addAll(orderRows(player))
        return keyed(rows)
    }

    /**
     * The tales the player follows, as the server last sent them (`ModAttachments.TALES`), each as one row, after the story. A row
     * takes up the contract its step is working when that is in the pack (a note whose chain, `tale:<tale>:<step>`, names this tale
     * and this step): the count of it rides at the end of the row's status, and the row says so when it is done (and points at the
     * board) or has lapsed. A contract that no row asks for stays a row of its own, so nothing the player carries is hidden.
     */
    private fun taleRows(player: Player, entries: List<Entry>, folded: MutableSet<Entry>): List<Row> {
        val tales = player.getData(ModAttachments.TALES)
        if (tales.isEmpty()) return listOf()
        val level = player.level()
        return tales.mapIndexed { index, tale ->
            val entry = entries.firstOrNull { e -> TaleTracker.chain(e.note.chain)?.let { it.first == tale.id && it.second == tale.step } == true }
            var board: BlockPos? = null
            val count = entry?.let { e ->
                folded.add(e)
                val lapsed = e.state.isExpired(level.gameTime)
                val done = isDone(player, e)
                if (done && !lapsed && e.state.dimension == level.dimension()) board = e.state.boardPos
                val have = when (e.note.type) {
                    QuestType.FETCH -> Contracts.countFetchItems(player, e.note)
                    QuestType.HUNT, QuestType.CLEAR, QuestType.CHAMPION, QuestType.TRIAL, QuestType.CHART -> e.state.progress
                    else -> -1
                }
                TaleTracker.Count(min(have, e.note.count).coerceAtLeast(0), if (have >= 0) e.note.count else 0, done, lapsed)
            }
            val line = TaleTracker.line(tale, count, index)
            val place = board ?: if (tale.dimension == level.dimension()) tale.place else null
            Row(TaleTracker.QUILL, line.title, line.status, TaleTracker.inkOf(line.tone), place, TALE, line)
        }
    }

    /** Names the rows, so that each keeps its place while the others come and go: a tale by its id, any other by what it says (a second of the same words gets a number). */
    private fun keyed(rows: List<Row>): List<Row> {
        val seen = HashMap<String, Int>()
        for (row in rows) {
            val name = row.tale?.let { "t${it.id}" } ?: "${row.kind}:${row.title.string}"
            val n = (seen[name] ?: 0) + 1
            seen[name] = n
            row.key = if (n == 1) name else "$name#$n"
        }
        return rows
    }

    /**
     * The orders the player holds: the good and how many are wanted at which business, then how many are still to
     * come, where, and by when (a standing order's week met, done until the next).
     */
    private fun orderRows(player: Player): List<Row> {
        if (orders.isEmpty()) return listOf()
        val level = player.level()
        val now = Math.floorDiv(level.overworldClockTime, 1000L)
        val here = level.dimension() == Level.OVERWORLD
        return orders.sortedBy { it.due }.map { o ->
            val good = Good.byId(o.good)
            val icon = good?.item?.let { ItemStack(it) } ?: ItemStack(Items.PAPER)
            val name = Component.translatable((good?.key ?: "good.guildmark.wheat") + ".many")
            val shop = Component.translatable(Shop.byId(o.shop)?.key ?: "shop.guildmark.produce")
            val title = if (o.week > 0) Component.translatable("gui.guildmark.tracker.order.standing", o.amount, name, shop, o.week, Orders.WEEKS)
                else Component.translatable("gui.guildmark.tracker.order", o.amount, name, shop)
            val days = Orders.daysLeft(o.due, now)
            val left = (o.amount - o.delivered).coerceAtLeast(0)
            val (status, color) = when {
                left == 0 -> Component.translatable("gui.guildmark.tracker.order.met", o.place) to DONE_INK
                else -> Component.translatable(
                    "gui.guildmark.tracker.order.status", left, o.place,
                    when (days) {
                        0 -> Component.translatable("gui.guildmark.tracker.order.tonight")
                        1 -> Component.translatable("gui.guildmark.tracker.order.tomorrow")
                        else -> Component.translatable("gui.guildmark.tracker.days", days)
                    },
                ) to if (days <= 1) LATE_INK else FADED_INK
            }
            Row(icon, title, status, color, if (here && left > 0) o.pos else null, ORDER)
        }
    }

    /** Where the tracker's rows point, in the dimension the player is in. */
    fun waypoints(player: Player): List<Waypoint> = rows(player).mapNotNull { row -> row.target?.let { Waypoint(it, row.title, row.kind, row.tale?.tip ?: listOf()) } }

    /** The party: each other member's name, health and how far away they are. */
    private fun renderParty(graphics: GuiGraphicsExtractor, minecraft: Minecraft, player: Player, top: Int) {
        val mates = player.getData(ModAttachments.PARTY)
        if (mates.isEmpty()) return
        val font = minecraft.font
        val left = 4
        val height = PADDING * 2 + 10 + mates.size * 12
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, WIDTH, height)
        graphics.text(font, Component.translatable("gui.guildmark.tracker.party"), left + PADDING, top + PADDING, FADED_INK, false)
        mates.forEachIndexed { i, uuid ->
            val y = top + PADDING + 11 + i * 12
            val name = minecraft.connection?.getPlayerInfo(uuid)?.profile?.name ?: "?"
            graphics.text(font, Component.literal(font.plainSubstrByWidth(name, 70)), left + PADDING, y, INK, false)
            val mate = player.level().getPlayerByUUID(uuid)
            if (mate == null) {
                val far = Component.translatable("gui.guildmark.tracker.party.far")
                graphics.text(font, far, left + WIDTH - PADDING - font.width(far), y, FADED_INK, false)
                return@forEachIndexed
            }
            // Health bar, red when low
            val barX = left + PADDING + 74
            val barWidth = 40
            val fraction = (mate.health / mate.maxHealth).coerceIn(0f, 1f)
            graphics.fill(barX, y + 2, barX + barWidth, y + 6, 0xFF3A2A1A.toInt())
            graphics.fill(barX, y + 2, barX + (barWidth * fraction).toInt(), y + 6, if (fraction < 0.35f) LATE_INK else DONE_INK)
            val distance = "${mate.distanceTo(player).toInt()}m"
            graphics.text(font, Component.literal(distance), left + WIDTH - PADDING - font.width(distance), y, FADED_INK, false)
        }
    }

    /** Rival challenges against villages the player's guild holds: beat the captain at the bell in time. */
    private fun challengeRows(player: Player): List<Row> = player.getData(ModAttachments.CHALLENGES).map { challenge ->
        val rival = RivalGuild.byKey(challenge.rival)
        val icon = ItemStack(Blocks.BANNER.pick(rival?.color ?: net.minecraft.world.item.DyeColor.WHITE))
        val title = Component.translatable("gui.guildmark.tracker.challenge", rival?.displayName ?: "?", challenge.village)
        val status = if (challenge.daysLeft <= 0) Component.translatable("gui.guildmark.tracker.challenge.today")
            else Component.translatable("gui.guildmark.tracker.status", Component.translatable("gui.guildmark.tracker.challenge.duel"),
                Component.translatable("gui.guildmark.tracker.days", challenge.daysLeft))
        Row(icon, title, status, LATE_INK, if (player.level().dimension() == Level.OVERWORLD) challenge.anchor else null, CHALLENGE)
    }

    /** The main story's next step, while it's under way. */
    private fun storyRow(player: Player): Row? {
        val state = player.getData(ModAttachments.MAIN_QUEST)
        if (!state.active || player.level().dimension() != Level.OVERWORLD) return null
        val title = Component.translatable("gui.guildmark.tracker.story", state.found, MainQuestState.SHARDS)
        val courtier = state.nextCourtier
        val status: Component = when {
            courtier == null -> Component.translatable("gui.guildmark.tracker.story.throne")
            state.target.isEmpty -> Component.translatable("gui.guildmark.tracker.story.rank", AdventurerRank.of(courtier.minRank).letter)
            else -> Component.translatable("gui.guildmark.tracker.story.vault", courtier.title)
        }
        return Row(SHARD_ICON, title, status, STORY_INK, state.target.orElse(null), STORY)
    }

    /** An arrow centred on (x, y), turned [degrees] clockwise from straight ahead, [alpha] visible (a tale's arrow comes and goes with its row). */
    private fun drawArrow(graphics: GuiGraphicsExtractor, x: Int, y: Int, degrees: Float, alpha: Float = 1f) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.rotate(Mth.wrapDegrees(degrees) * Mth.DEG_TO_RAD)
        if (alpha >= 1f) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, -5, -5, 11, 11)
        else graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, -5, -5, 11, 11, ARGB.white(alpha))
        pose.popMatrix()
    }

    private fun isDone(player: Player, entry: Entry): Boolean = when (entry.note.type) {
        QuestType.FETCH -> Contracts.countFetchItems(player, entry.note) >= entry.note.count
        QuestType.HUNT, QuestType.CLEAR, QuestType.CHAMPION, QuestType.LAIR, QuestType.TRIAL, QuestType.CHART, QuestType.SALVAGE, QuestType.SLAY, QuestType.BOUNTY, QuestType.RESCUE, QuestType.CARAVAN -> entry.state.progress >= entry.note.count
        QuestType.DELIVER, QuestType.ESCORT -> false
    }

    private fun status(player: Player, entry: Entry): Pair<Component, Int> {
        val gameTime = player.level().gameTime
        if (entry.state.isExpired(gameTime)) return Pair(Component.translatable("gui.guildmark.tracker.expired"), LATE_INK)
        if (isDone(player, entry)) return Pair(Component.translatable("gui.guildmark.tracker.return"), DONE_INK)
        val days = entry.state.daysLeft(gameTime)
        val progress: Component = when (entry.note.type) {
            QuestType.FETCH -> Component.translatable("gui.guildmark.tracker.count", Contracts.countFetchItems(player, entry.note), entry.note.count)
            QuestType.HUNT, QuestType.CLEAR, QuestType.CHAMPION, QuestType.TRIAL, QuestType.CHART -> Component.translatable("gui.guildmark.tracker.count", entry.state.progress, entry.note.count)
            QuestType.DELIVER, QuestType.ESCORT -> Component.translatable("gui.guildmark.tracker.travel")
            QuestType.LAIR -> Component.translatable("gui.guildmark.tracker.lair")
            QuestType.SALVAGE -> Component.translatable("gui.guildmark.tracker.salvage", entry.state.label)
            QuestType.SLAY -> Component.translatable("gui.guildmark.tracker.slay", entry.state.labelText())
            QuestType.BOUNTY -> Component.translatable("gui.guildmark.tracker.bounty", entry.state.labelText())
            // Freed, they're headed home; until then, to the camp that holds them
            QuestType.RESCUE -> if (entry.state.destination?.closerThan(entry.state.boardPos, 96.0) == true) {
                Component.translatable("gui.guildmark.tracker.rescue_home", entry.state.label)
            } else {
                Component.translatable("gui.guildmark.tracker.rescue", entry.state.label)
            }
            // To the start of the road to meet the wagon, then along with it
            QuestType.CARAVAN -> Component.translatable(if (entry.state.progress >= 1) "gui.guildmark.tracker.caravan_walk" else "gui.guildmark.tracker.caravan_meet", entry.state.labelText())
        }
        val text = Component.translatable("gui.guildmark.tracker.status", progress, Component.translatable("gui.guildmark.tracker.days", days))
        return Pair(text, if (days <= 1) LATE_INK else FADED_INK)
    }

    /** Where to head next: the board once the work is done, otherwise the job's place if it has one. */
    private fun target(player: Player, entry: Entry): BlockPos? {
        val state = entry.state
        if (player.level().dimension() != state.dimension) return null
        if (isDone(player, entry)) return state.boardPos
        return when (entry.note.type) {
            QuestType.CLEAR -> state.boardPos
            QuestType.DELIVER, QuestType.ESCORT, QuestType.CHAMPION, QuestType.LAIR, QuestType.TRIAL, QuestType.SALVAGE, QuestType.SLAY, QuestType.BOUNTY, QuestType.RESCUE, QuestType.CARAVAN -> state.destination
            // Any isle no one's charted will do, wherever it is
            QuestType.FETCH, QuestType.HUNT, QuestType.CHART -> null
        }
    }
}
