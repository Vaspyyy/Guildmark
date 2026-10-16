package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.CombatHud
import io.github.vaspyyy.guildmark.guild.RivalGuild
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
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The on-screen contract tracker: the contracts in your inventory, soonest deadline first, each with
 * its progress, days left and an arrow pointing where to go next.
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
    private val INK = 0xFF2B1D0E.toInt()
    private val FADED_INK = 0xFF6B5236.toInt()
    private val DONE_INK = 0xFF2E6B1F.toInt()
    private val LATE_INK = 0xFF9A2A1A.toInt()

    /** Toggled with the tracker key or in Guildmark's settings, and kept between games. */
    var shown: Boolean
        get() = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.CONTRACT_TRACKER.get()
        set(value) {
            if (!GuildmarkClientConfig.SPEC.isLoaded) return
            GuildmarkClientConfig.CONTRACT_TRACKER.set(value)
            GuildmarkClientConfig.SPEC.save()
        }

    private class Entry(val stack: ItemStack, val note: QuestNote, val state: ContractState)

    /** One line of the tracker: a contract, a rival's challenge, or the main story's next step. */
    private class Row(val icon: ItemStack, val title: Component, val status: Component, val color: Int, val target: BlockPos?, val kind: Int)

    /** Somewhere the tracker points, for the compass and the map. */
    class Waypoint(val pos: BlockPos, val title: Component, val kind: Int)

    const val CONTRACT = 0
    const val CHALLENGE = 1
    const val STORY = 2

    private val SHARD_ICON: ItemStack by lazy { MainQuest.shard(0) }
    private val STORY_INK = 0xFF6A3A9A.toInt()

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (!shown || minecraft.gui.hud.isHidden) return

        val rows = rows(player)
        // Below the combat bars when they're showing
        val top = 4 + CombatHud.reservedHeight()
        if (rows.isEmpty()) {
            renderParty(graphics, minecraft, player, top)
            return
        }

        val shownEntries = rows.take(MAX_SHOWN)
        val extra = rows.size - shownEntries.size
        val height = PADDING * 2 + shownEntries.size * ROW_HEIGHT - 4 + if (extra > 0) 10 else 0
        val left = 4
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, WIDTH, height)

        val font = minecraft.font
        shownEntries.forEachIndexed { i, entry ->
            val y = top + PADDING + i * ROW_HEIGHT
            val x = left + PADDING
            graphics.item(entry.icon, x, y + 2)

            val textWidth = WIDTH - PADDING * 2 - 20 - 26
            val title = font.substrByWidth(entry.title, textWidth)
            graphics.text(font, Language.getInstance().getVisualOrder(title), x + 20, y + 1, INK, false)
            graphics.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(entry.status, textWidth)), x + 20, y + 11, entry.color, false)

            val target = entry.target ?: return@forEachIndexed
            val dx = target.x + 0.5 - player.x
            val dz = target.z + 0.5 - player.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance < NEAR) return@forEachIndexed
            val arrowX = left + WIDTH - PADDING - 13
            drawArrow(graphics, arrowX, y + 6, Math.toDegrees(atan2(dz, dx)).toFloat() - 90.0f - player.yRot)
            val label = if (distance >= 1000) "%.1fk".format(distance / 1000) else "${distance.toInt()}"
            graphics.text(font, Component.literal(label), arrowX - font.width(label) / 2, y + 13, FADED_INK, false)
        }
        if (extra > 0) {
            graphics.text(font, Component.translatable("gui.guildmark.tracker.more", extra), left + PADDING, top + height - PADDING - 8, FADED_INK, false)
        }
        renderParty(graphics, minecraft, player, top + height + 3)
    }

    /** Rival challenges first, then the main story, then the contracts carried, soonest deadline first. */
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
        for (entry in entries) {
            val (status, color) = status(player, entry)
            rows.add(Row(entry.stack, entry.note.title(), status, color, target(player, entry), CONTRACT))
        }
        return rows
    }

    /** Where the tracker's rows point, in the dimension the player is in. */
    fun waypoints(player: Player): List<Waypoint> = rows(player).mapNotNull { row -> row.target?.let { Waypoint(it, row.title, row.kind) } }

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

    /** An arrow centred on (x, y), turned [degrees] clockwise from straight ahead. */
    private fun drawArrow(graphics: GuiGraphicsExtractor, x: Int, y: Int, degrees: Float) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.rotate(Mth.wrapDegrees(degrees) * Mth.DEG_TO_RAD)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, -5, -5, 11, 11)
        pose.popMatrix()
    }

    private fun isDone(player: Player, entry: Entry): Boolean = when (entry.note.type) {
        QuestType.FETCH -> Contracts.countFetchItems(player, entry.note) >= entry.note.count
        QuestType.HUNT, QuestType.CLEAR, QuestType.CHAMPION, QuestType.LAIR, QuestType.TRIAL -> entry.state.progress >= entry.note.count
        QuestType.DELIVER, QuestType.ESCORT -> false
    }

    private fun status(player: Player, entry: Entry): Pair<Component, Int> {
        val gameTime = player.level().gameTime
        if (entry.state.isExpired(gameTime)) return Pair(Component.translatable("gui.guildmark.tracker.expired"), LATE_INK)
        if (isDone(player, entry)) return Pair(Component.translatable("gui.guildmark.tracker.return"), DONE_INK)
        val days = entry.state.daysLeft(gameTime)
        val progress: Component = when (entry.note.type) {
            QuestType.FETCH -> Component.translatable("gui.guildmark.tracker.count", Contracts.countFetchItems(player, entry.note), entry.note.count)
            QuestType.HUNT, QuestType.CLEAR, QuestType.CHAMPION, QuestType.TRIAL -> Component.translatable("gui.guildmark.tracker.count", entry.state.progress, entry.note.count)
            QuestType.DELIVER, QuestType.ESCORT -> Component.translatable("gui.guildmark.tracker.travel")
            QuestType.LAIR -> Component.translatable("gui.guildmark.tracker.lair")
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
            QuestType.DELIVER, QuestType.ESCORT, QuestType.CHAMPION, QuestType.LAIR, QuestType.TRIAL -> state.destination
            QuestType.FETCH, QuestType.HUNT -> null
        }
    }
}
