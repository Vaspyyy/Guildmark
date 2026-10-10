package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.registry.ModDataComponents
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

    /** Toggled with the tracker key. */
    var shown = true

    private class Entry(val stack: ItemStack, val note: QuestNote, val state: ContractState)

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (!shown) return

        val entries = (0 until player.inventory.containerSize)
            .map { player.inventory.getItem(it) }
            .mapNotNull { stack ->
                val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: return@mapNotNull null
                val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: return@mapNotNull null
                Entry(stack, note, state)
            }
            .sortedBy { it.state.deadline }
        if (entries.isEmpty()) return

        val shownEntries = entries.take(MAX_SHOWN)
        val extra = entries.size - shownEntries.size
        val height = PADDING * 2 + shownEntries.size * ROW_HEIGHT - 4 + if (extra > 0) 10 else 0
        val left = 4
        val top = 4
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, WIDTH, height)

        val font = minecraft.font
        shownEntries.forEachIndexed { i, entry ->
            val y = top + PADDING + i * ROW_HEIGHT
            val x = left + PADDING
            graphics.item(entry.stack, x, y + 2)

            val textWidth = WIDTH - PADDING * 2 - 20 - 26
            val title = font.substrByWidth(entry.note.title(), textWidth)
            graphics.text(font, Language.getInstance().getVisualOrder(title), x + 20, y + 1, INK, false)
            val (status, color) = status(player, entry)
            graphics.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(status, textWidth)), x + 20, y + 11, color, false)

            val target = target(player, entry) ?: return@forEachIndexed
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
        QuestType.HUNT, QuestType.CLEAR, QuestType.CHAMPION -> entry.state.progress >= entry.note.count
        QuestType.DELIVER, QuestType.ESCORT -> false
    }

    private fun status(player: Player, entry: Entry): Pair<Component, Int> {
        val gameTime = player.level().gameTime
        if (entry.state.isExpired(gameTime)) return Pair(Component.translatable("gui.guildmark.tracker.expired"), LATE_INK)
        if (isDone(player, entry)) return Pair(Component.translatable("gui.guildmark.tracker.return"), DONE_INK)
        val days = entry.state.daysLeft(gameTime)
        val progress: Component = when (entry.note.type) {
            QuestType.FETCH -> Component.translatable("gui.guildmark.tracker.count", Contracts.countFetchItems(player, entry.note), entry.note.count)
            QuestType.HUNT, QuestType.CLEAR, QuestType.CHAMPION -> Component.translatable("gui.guildmark.tracker.count", entry.state.progress, entry.note.count)
            QuestType.DELIVER, QuestType.ESCORT -> Component.translatable("gui.guildmark.tracker.travel")
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
            QuestType.DELIVER, QuestType.ESCORT, QuestType.CHAMPION -> state.destination
            QuestType.FETCH, QuestType.HUNT -> null
        }
    }
}
