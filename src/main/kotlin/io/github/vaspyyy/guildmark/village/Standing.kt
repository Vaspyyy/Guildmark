package io.github.vaspyyy.guildmark.village

import io.github.vaspyyy.guildmark.advance.Advances
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.gossip.GossipType
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import kotlin.math.roundToInt

/**
 * How well a village knows a player. Earned by finishing that village's contracts (a point per Guild
 * Mark of reward), lost by letting them expire.
 *
 * @param bonusPercent extra Guild Marks on every contract turned in here
 * @param gossip lasting good word among the village's villagers, which vanilla turns into trade discounts
 */
enum class StandingTier(val threshold: Int, val bonusPercent: Int, val gossip: Int) {
    STRANGER(0, 0, 0),
    KNOWN(25, 5, 2),
    TRUSTED(75, 10, 5),
    HONORED(200, 20, 10),
    HERO(400, 30, 20);

    val title: Component get() = Component.translatable("standing.guildmark.${name.lowercase()}")
    val next: StandingTier? get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun of(points: Int): StandingTier = entries.last { points >= it.threshold }
    }
}

object Standing {
    /** The board (anchor cell) that represents the village a board cell belongs to. */
    fun board(level: Level, boardPos: BlockPos): QuestBoardBlockEntity? {
        if (!level.isLoaded(boardPos)) return null
        val state = level.getBlockState(boardPos)
        if (state.block !is QuestBoardBlock) return null
        return level.getBlockEntity(QuestBoardBlock.anchorPos(boardPos, state)) as? QuestBoardBlockEntity
    }

    fun points(level: Level, boardPos: BlockPos, player: Player): Int = board(level, boardPos)?.standingOf(player.uuid) ?: 0

    fun tier(level: Level, boardPos: BlockPos, player: Player): StandingTier = StandingTier.of(points(level, boardPos, player))

    /** Extra Guild Marks this village adds to a reward. */
    fun bonus(level: Level, boardPos: BlockPos, player: Player, reward: Int): Int =
        (reward * tier(level, boardPos, player).bonusPercent / 100.0).roundToInt()

    /** Change [player]'s standing with the village of this board, announcing a new tier and spreading the word. */
    fun add(level: ServerLevel, boardPos: BlockPos, player: Player, amount: Int) {
        val board = board(level, boardPos) ?: return
        val before = StandingTier.of(board.standingOf(player.uuid))
        val after = StandingTier.of(board.addStanding(player.uuid, amount))
        if (after == before) return
        if (after > before) {
            player.sendSystemMessage(Component.translatable("message.guildmark.standing_up", after.title))
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0f, 1.0f)
        } else {
            player.sendSystemMessage(Component.translatable("message.guildmark.standing_down", after.title))
        }
        spreadWord(level, Advances.villageCenter(level, board.blockPos), player, after)
    }

    /**
     * Villagers remember the player as a hero of the village (the lasting gossip vanilla gives for curing
     * a zombie villager), scaled to their standing. Vanilla's own trading lowers prices from it.
     */
    private fun spreadWord(level: ServerLevel, center: BlockPos, player: Player, tier: StandingTier) {
        for (villager in level.getEntitiesOfClass(Villager::class.java, AABB(center).inflate(64.0, 24.0, 64.0))) {
            val gossips = villager.gossips
            val current = gossips.getReputation(player.uuid) { it == GossipType.MAJOR_POSITIVE } / GossipType.MAJOR_POSITIVE.weight
            when {
                tier.gossip > current -> gossips.add(player.uuid, GossipType.MAJOR_POSITIVE, tier.gossip - current)
                tier.gossip < current -> gossips.remove(player.uuid, GossipType.MAJOR_POSITIVE, current - tier.gossip)
            }
        }
    }
}
