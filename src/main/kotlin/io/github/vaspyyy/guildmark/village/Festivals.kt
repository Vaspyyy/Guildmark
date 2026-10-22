package io.github.vaspyyy.guildmark.village

import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.festival.Festivities
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.village.live.VillageFeast
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import java.util.UUID

/**
 * Every village holds a market day one day in seven (each on its own day): its contracts pay a quarter more, a
 * wandering trader comes for the day and the villagers are in good spirits round the bell. On the day of one of the
 * four grand festivals (festival/Festivities) the market gives way to the festival, whose contracts pay the same
 * quarter more.
 *
 * (This was once a weekly "festival" with fireworks. The grand festivals are the special days now; the market keeps the
 * old rhythm, the trader and the bonus, and the callers that ask for them.)
 */
object Festivals {
    private const val EVERY_DAYS = 7
    private const val PAY_BONUS_PERCENT = 25
    private const val TRADER_UNTIL = 12000L

    /** Player, and the village and day they were last told about a market day. */
    private val greeted = HashMap<UUID, Pair<BlockPos, Long>>()
    /** The day each village last had its market trader. */
    private val traderDay = HashMap<BlockPos, Long>()
    /** The 100-tick slot each village was last lively in, so a crowd of players doesn't multiply it. */
    private val showSlot = HashMap<BlockPos, Long>()

    private fun seed(anchor: BlockPos): Int = (anchor.x * 73856093) xor (anchor.z * 19349663)

    /** Whether it's [anchor]'s market day on [day] (a grand festival day isn't: the festival has the square). */
    fun isFestival(anchor: BlockPos, day: Long): Boolean = isMarketDay(anchor, day)

    fun isMarketDay(anchor: BlockPos, day: Long): Boolean =
        Math.floorMod(day + seed(anchor), EVERY_DAYS.toLong()) == 0L || forced[anchor] == day

    /** Villages holding a market today because an op said so (for testing). */
    private val forced = HashMap<BlockPos, Long>()

    fun force(anchor: BlockPos, day: Long) {
        forced[anchor] = day
    }

    /**
     * Extra pay for a contract handed in at a village on its market day, or anywhere on a grand festival's day.
     * [boardPos] may be any cell of the board.
     */
    fun bonus(level: ServerLevel, boardPos: BlockPos, reward: Int): Int {
        val state = level.getBlockState(boardPos)
        val anchor = if (state.block is QuestBoardBlock) QuestBoardBlock.anchorPos(boardPos, state) else boardPos
        val festive = isMarketDay(anchor, VillageBoards.day(level)) || Festivities.grandDay(level)
        return if (festive) reward * PAY_BONUS_PERCENT / 100 else 0
    }

    /** Every 100 ticks per player: the market in the village they're in, if it's that day. */
    fun tick(level: ServerLevel, player: ServerPlayer) {
        if (level.dimension() != Level.OVERWORLD) return
        // A grand festival day belongs to the festival
        if (Festivities.grandDay(level)) return
        val anchor = VillageBoards.villageBoard(level, player.blockPosition()) ?: return
        val today = VillageBoards.day(level)
        if (!isMarketDay(anchor, today)) return
        val bell = TownControl.bell(level, anchor) ?: anchor
        val random = level.random

        if (greeted[player.uuid] != anchor to today) {
            greeted[player.uuid] = anchor to today
            player.sendSystemMessage(Component.translatable("message.guildmark.market_day", VillageNames.of(anchor), PAY_BONUS_PERCENT).withStyle { it.withColor(0xE8C26A) })
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 1.0f, 1.0f)
        }
        val slot = level.gameTime / 100
        if (showSlot[anchor] == slot) return
        showSlot[anchor] = slot
        for (villager in level.getEntitiesOfClass(Villager::class.java, AABB(bell).inflate(24.0))) {
            if (random.nextInt(8) != 0 || villager.isSleeping) continue
            level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.5f, 0.9f + random.nextFloat() * 0.3f)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.x, villager.y + 2.1, villager.z, 3, 0.3, 0.2, 0.3, 0.0)
        }
        // And its own townspeople, who clap and cheer
        VillageFeast.market(level, bell, random)
        val time = Math.floorMod(level.overworldClockTime, 24000L)
        if (traderDay[anchor] != today && time in 1000L until TRADER_UNTIL) {
            traderDay[anchor] = today
            if (level.getEntitiesOfClass(WanderingTrader::class.java, AABB(bell).inflate(48.0)).isEmpty()) spawnTrader(level, bell)
        }
    }

    private fun spawnTrader(level: ServerLevel, bell: BlockPos) {
        val trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.EVENT) ?: return
        val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bell.offset(level.random.nextInt(9) - 4, 0, level.random.nextInt(9) - 4))
        trader.snapTo(ground.x + 0.5, ground.y.toDouble(), ground.z + 0.5, level.random.nextFloat() * 360f, 0f)
        trader.finalizeSpawn(level, level.getCurrentDifficultyAt(ground), EntitySpawnReason.EVENT, null)
        trader.setDespawnDelay(12000)
        trader.setWanderTarget(bell)
        level.addFreshEntity(trader)
    }
}
