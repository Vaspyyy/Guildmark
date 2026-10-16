package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.atlas.Atlas
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.island.IslandFinder
import io.github.vaspyyy.guildmark.island.Islands
import io.github.vaspyyy.guildmark.island.plan.IslandKind
import io.github.vaspyyy.guildmark.island.plan.IslandNames
import io.github.vaspyyy.guildmark.island.plan.IslandPlan
import io.github.vaspyyy.guildmark.island.plan.IslandSpec
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent

/**
 * Contracts out at sea: charting isles no one has seen, and salvage on the isles themselves (the captain's chest in a
 * wreck's cabin, the smugglers' strongbox at the back of their cove, the chest on a drowned shrine's altar). A board
 * posts them only when there's open sea within reach of it with isles out on it, and salvage only of a kind of isle
 * that's really there. A salvage contract picks its isle when it's taken, one the taker hasn't sighted yet if there's
 * any such in reach, and is done the moment its holder opens that chest.
 */
object SeaWork {
    /** How far from its board sea work reaches, in blocks. */
    const val RANGE = 1600

    /** Isles a board must have out on its sea before it asks for any charting. */
    private const val CHART_ISLES = 4

    /** How many of the nearest isles of a kind are looked at before a board gives up on salvage of that kind. */
    private const val LOOK = 2

    /** The kinds of isle there's salvage on, and so the targets a salvage note may name (guildmark:wreck and so on). */
    private val SALVAGE_KINDS = listOf(IslandKind.WRECK, IslandKind.COVE, IslandKind.SHRINE)

    /** The kind of isle a salvage note's [target] names, if it names one. */
    fun kindOf(target: Identifier): IslandKind? =
        if (target.namespace != Guildmark.MOD_ID) null else SALVAGE_KINDS.firstOrNull { it.name.lowercase() == target.path }

    /** What sea work a board has to offer: any charting, and the kinds of isle there's salvage on. */
    class Reach(val chart: Boolean, val salvage: Set<IslandKind>) {
        companion object {
            val NONE = Reach(false, emptySet())
        }
    }

    /** Each board's sea, once it's been looked at (it never changes, bar the islands setting). */
    private val reaches = HashMap<Pair<ResourceKey<Level>, Long>, Reach>()

    /** The sea work the board at [board] can post. Worked out the first time it's asked, then kept. */
    fun reach(level: ServerLevel, board: BlockPos): Reach = reaches.getOrPut(level.dimension() to board.asLong()) {
        val isles = IslandFinder.around(level, board, RANGE)
        // Charting wants open sea with isles yet to grow on it; salvage, one isle of its kind that's really there
        Reach(
            chart = Islands.enabled() && isles.size >= CHART_ISLES,
            salvage = SALVAGE_KINDS.filter { kind -> isles.asSequence().filter { it.kind == kind }.take(LOOK).any { IslandFinder.stands(level, it) } }.toSet(),
        )
    }

    fun forget() = reaches.clear()

    /** An isle a salvage contract sends someone to, and the chest at its heart. */
    class Isle(val spec: IslandSpec, val chest: BlockPos) {
        val name: String get() = IslandNames.of(spec)
    }

    /**
     * The isle a salvage contract of [kind] taken at [board] sends [player] to: the nearest that stands with its chest
     * still in its plan, first of those they haven't sighted, then of the rest. Null if there's none in reach.
     */
    fun chooseIsle(level: ServerLevel, board: BlockPos, kind: IslandKind, player: Player): Isle? {
        val dimension = level.dimension()
        val sighted = Atlas.get(player).places
            .filter { it.kind == AtlasPlace.ISLAND && it.dimension == dimension }
            .mapTo(HashSet()) { it.pos.x to it.pos.z }
        // Nearest first, and a stable sort keeps them so within the fresh and the known
        val isles = IslandFinder.around(level, board, RANGE).filter { it.kind == kind }.sortedBy { (it.cx to it.cz) in sighted }
        for (spec in isles) {
            if (!IslandFinder.stands(level, spec)) continue
            val heart = IslandPlan.of(spec).heart() ?: continue
            return Isle(spec, BlockPos(heart[0], heart[1], heart[2]))
        }
        return null
    }

    /** [player] sighted [count] isles they'd never seen: the first chart contract they carry that wants more counts them. */
    fun onCharted(player: Player, count: Int) {
        val gameTime = player.level().gameTime
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            if (note.type != QuestType.CHART) continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.isExpired(gameTime) || state.progress >= note.count) continue
            val progress = (state.progress + count).coerceAtMost(note.count)
            Contracts.setProgress(stack, progress)
            player.sendOverlayMessage(Component.translatable("message.guildmark.progress", note.title(), progress, note.count))
            return
        }
    }

    /** Opening a chest (or barrel): it may be the one a salvage contract sent its holder for. */
    fun onUseBlock(event: PlayerInteractEvent.RightClickBlock) {
        val level = event.level as? ServerLevel ?: return
        if (level.getBlockEntity(event.pos) !is RandomizableContainer) return
        searched(level, event.entity, event.pos)
    }

    /** Breaking it open does as well. */
    fun onBreak(event: BreakBlockEvent) {
        val level = event.level as? ServerLevel ?: return
        if (level.getBlockEntity(event.pos) !is RandomizableContainer) return
        searched(level, event.player, event.pos)
    }

    /** [player] got into the chest at [pos]: every salvage contract of theirs that sent them to it is done. */
    private fun searched(level: ServerLevel, player: Player, pos: BlockPos) {
        val gameTime = level.gameTime
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            if (note.type != QuestType.SALVAGE) continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.progress >= note.count || state.isExpired(gameTime) || state.dimension != level.dimension() || state.destination != pos) continue
            Contracts.setProgress(stack, note.count)
            player.sendOverlayMessage(Component.translatable("message.guildmark.salvaged", state.label))
            level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.6f)
        }
    }
}
