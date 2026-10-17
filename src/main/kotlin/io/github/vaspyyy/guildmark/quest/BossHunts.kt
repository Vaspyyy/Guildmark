package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.dragon.Dragon
import io.github.vaspyyy.guildmark.dragon.DragonRoosts
import io.github.vaspyyy.guildmark.enemy.HillTroll
import io.github.vaspyyy.guildmark.field.FieldBosses
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * Contracts on the field bosses that keep a place of their own: a hill troll asleep by its camp fire, a dragon on its
 * roost. Camps and roosts are only raised once someone has come upon them, so these are beasts the land already knows
 * of. A board posts such a job only while one lives within reach of it; taking it names the nearest, and its fall
 * counts for one job held close by on it, the one closest to running out.
 */
object BossHunts {
    /** How far from its board a hunt reaches: a day's walk for a troll, further for a dragon on its peak. */
    private const val TROLL_RANGE = 1000.0
    private const val DRAGON_RANGE = 2500.0

    val TROLL: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hill_troll")
    val DRAGON: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "dragon")

    /** Whether a hunt's [target] names a beast there are hunts for. */
    fun isQuarry(target: Identifier): Boolean = target == TROLL || target == DRAGON

    /** A beast a board can send someone after: where it lies, its camp or roost, and its name ("#" and a lang key). */
    class Quarry(val pos: BlockPos, val site: Int, val label: String)

    /** The living beasts of the kind [target] names within reach of the board at [board], nearest first. */
    fun quarries(level: ServerLevel, board: BlockPos, target: Identifier): List<Quarry> {
        // Camps and roosts are all in the overworld
        if (level.dimension() != Level.OVERWORLD) return listOf()
        return when (target) {
            TROLL -> if (!FieldBosses.enabled()) listOf() else FieldBosses.get(level).sites
                .filter { it.built && !it.slain && it.boss != null && Expeditions.horizontalDistance(it.pos, board) < TROLL_RANGE }
                .sortedBy { Expeditions.horizontalDistance(it.pos, board) }
                .map { Quarry(it.pos, it.id, "#${HillTroll.NAME_KEY}.${it.name}") }
            DRAGON -> if (!DragonRoosts.enabled()) listOf() else DragonRoosts.get(level).roosts
                .filter { it.built && !it.slain && it.dragon != null && Expeditions.horizontalDistance(it.pos, board) < DRAGON_RANGE }
                .sortedBy { Expeditions.horizontalDistance(it.pos, board) }
                .map { Quarry(it.pos, it.id, "#${Dragon.NAME_KEY}.${it.name}") }
            else -> listOf()
        }
    }

    /**
     * The beast of [target] from the camp or roost [site] fell. One beast, one job: of the hunts on it that [players]
     * hold, the one closest to running out is done, and any others still want a beast of their own.
     */
    fun onSlain(players: List<Player>, target: Identifier, site: Int) {
        var done: Pair<Player, ItemStack>? = null
        var soonest = Long.MAX_VALUE
        for (player in players) {
            val inventory = player.inventory
            for (slot in 0 until inventory.containerSize) {
                val stack = inventory.getItem(slot)
                val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
                if (note.type != QuestType.SLAY || note.target != target) continue
                val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
                if (state.site != site || state.progress >= note.count || state.isExpired(player.level().gameTime)) continue
                if (state.deadline < soonest) {
                    soonest = state.deadline
                    done = player to stack
                }
            }
        }
        val (player, stack) = done ?: return
        val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: return
        Contracts.setProgress(stack, note.count)
        player.sendOverlayMessage(Component.translatable("message.guildmark.quarry_slain", note.title()))
    }
}
