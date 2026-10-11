package io.github.vaspyyy.guildmark.village

import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.guild.TownControl
import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
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
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import java.util.UUID

/**
 * Every village holds a festival one day in seven (each on its own day): its contracts pay a quarter
 * more, villagers celebrate around the bell, a wandering trader comes for the day, and fireworks go up
 * over the bell at dusk. Which festival it is depends on the village.
 */
object Festivals {
    private const val EVERY_DAYS = 7
    private const val PAY_BONUS_PERCENT = 25
    private const val KINDS = 4
    private const val DUSK_START = 12500L
    private const val DUSK_END = 15000L

    /** Player, and the village and day they were last told about a festival. */
    private val greeted = HashMap<UUID, Pair<BlockPos, Long>>()
    /** The day each village last had its festival trader. */
    private val traderDay = HashMap<BlockPos, Long>()

    private fun seed(anchor: BlockPos): Int = (anchor.x * 73856093) xor (anchor.z * 19349663)

    fun isFestival(anchor: BlockPos, day: Long): Boolean = Math.floorMod(day + seed(anchor), EVERY_DAYS.toLong()) == 0L || forced[anchor] == day

    /** Villages holding a festival today because an op said so (for testing). */
    private val forced = HashMap<BlockPos, Long>()

    fun force(anchor: BlockPos, day: Long) {
        forced[anchor] = day
    }

    fun name(anchor: BlockPos): Component = Component.translatable("festival.guildmark.${Math.floorMod(seed(anchor) / EVERY_DAYS, KINDS)}")

    /** Extra pay for a contract handed in at a village on its festival day. [boardPos] may be any cell of the board. */
    fun bonus(level: ServerLevel, boardPos: BlockPos, reward: Int): Int {
        val state = level.getBlockState(boardPos)
        val anchor = if (state.block is QuestBoardBlock) QuestBoardBlock.anchorPos(boardPos, state) else boardPos
        return if (isFestival(anchor, VillageBoards.day(level))) reward * PAY_BONUS_PERCENT / 100 else 0
    }

    /** Every 100 ticks per player: the festivities in the village they're in, if it's that day. */
    fun tick(level: ServerLevel, player: ServerPlayer) {
        if (level.dimension() != Level.OVERWORLD) return
        val anchor = VillageBoards.villageBoard(level, player.blockPosition()) ?: return
        val today = VillageBoards.day(level)
        if (!isFestival(anchor, today)) return
        val bell = TownControl.bell(level, anchor) ?: anchor
        val random = level.random

        if (greeted[player.uuid] != anchor to today) {
            greeted[player.uuid] = anchor to today
            player.sendSystemMessage(Component.translatable("message.guildmark.festival", name(anchor), VillageNames.of(anchor), PAY_BONUS_PERCENT).withStyle { it.withColor(0xE8C26A) })
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0f, 1.0f)
        }
        for (villager in level.getEntitiesOfClass(Villager::class.java, AABB(bell).inflate(24.0))) {
            if (random.nextInt(4) != 0 || villager.isSleeping) continue
            level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.6f, 0.9f + random.nextFloat() * 0.3f)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.x, villager.y + 2.1, villager.z, 4, 0.3, 0.2, 0.3, 0.0)
        }
        val time = level.overworldClockTime % 24000L
        if (traderDay[anchor] != today && time in 1000L until DUSK_START) {
            traderDay[anchor] = today
            if (level.getEntitiesOfClass(WanderingTrader::class.java, AABB(bell).inflate(48.0)).isEmpty()) spawnTrader(level, bell)
        }
        if (time in DUSK_START until DUSK_END) repeat(1 + random.nextInt(2)) { firework(level, bell) }
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

    private val COLORS = intArrayOf(0xE8C26A, 0xC23A48, 0x3A7FC2, 0x5FBF5F, 0xB05FD0, 0xF0F0F0, 0xF08A3A)

    private fun firework(level: ServerLevel, bell: BlockPos) {
        val random = level.random
        val shape = FireworkExplosion.Shape.values().let { it[random.nextInt(it.size)] }
        val colors = IntList.of(COLORS[random.nextInt(COLORS.size)], COLORS[random.nextInt(COLORS.size)])
        val explosion = FireworkExplosion(shape, colors, IntList.of(COLORS[random.nextInt(COLORS.size)]), random.nextBoolean(), random.nextBoolean())
        val stack = ItemStack(Items.FIREWORK_ROCKET)
        stack.set(DataComponents.FIREWORKS, Fireworks(2 + random.nextInt(2), listOf(explosion)))
        val spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, bell.offset(random.nextInt(13) - 6, 0, random.nextInt(13) - 6))
        level.addFreshEntity(FireworkRocketEntity(level, spot.x + 0.5, spot.y + 0.5, spot.z + 0.5, stack))
    }
}
