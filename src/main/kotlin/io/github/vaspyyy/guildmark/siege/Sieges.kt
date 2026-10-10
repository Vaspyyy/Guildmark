package io.github.vaspyyy.guildmark.siege

import io.github.vaspyyy.guildmark.advance.Advances
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.village.Standing
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID

/**
 * Monster sieges. Some nights a horde marches on a village from one side, in waves. Kill every wave
 * and the defenders are paid and the village thinks better of them; if the horde is still standing at
 * dawn, or it kills three villagers, the village is damaged: palisade logs and lamps torn down, its
 * current project's progress lost, and standing lost with everyone who was there.
 */
object Sieges {
    /** Players need this guild level before hordes start coming for their villages. */
    private const val MIN_LEVEL = 3
    private const val CHANCE_PERCENT = 20
    private const val WATCH_RANGE = 96.0
    private const val VILLAGERS_LOST_LIMIT = 3
    /** Night runs from about 13000 to 23000 ticks into the day; sieges start early in it. */
    private const val NIGHT_START = 13000L
    private const val NIGHT_START_WINDOW = 1500L
    private const val DAWN = 23000L

    private class Siege(
        val id: Int,
        val center: BlockPos,
        val anchor: BlockPos,
        val from: Direction,
        val tier: Int,
        val totalWaves: Int,
        /** Game time the horde gives up: dawn, or five minutes for a siege started in daylight. */
        val endsAt: Long,
    ) {
        var wave = 0
        val mobs = HashSet<UUID>()
        var spawnedTotal = 0
        var villagersLost = 0
        var waveStarted = 0L
        val bar = ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS)
    }

    private val sieges = HashMap<Int, Siege>()
    /** Village board anchor to the last day it was besieged, so it's at most once a night. */
    private val lastSiegeDay = HashMap<BlockPos, Long>()
    private var nextId = 1

    private fun timeOfDay(level: ServerLevel): Long = level.overworldClockTime % 24000L

    /** Player tick, every 400 ticks: early in the night, maybe send a horde at the village they're in. */
    fun maybeStart(level: ServerLevel, player: Player) {
        if (player.tickCount % 400 != 0 || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return
        val time = timeOfDay(level)
        if (time !in NIGHT_START..NIGHT_START + NIGHT_START_WINDOW) return
        if (Progression.get(player).level < MIN_LEVEL) return
        if (level.random.nextInt(100) >= CHANCE_PERCENT) return
        start(level, player.blockPosition())
    }

    /** Start a siege on the village nearest [near]. Returns false if there's no village with a board, or it's already under siege. */
    fun start(level: ServerLevel, near: BlockPos): Boolean {
        val bell = level.poiManager.findClosest({ it.`is`(PoiTypes.MEETING) }, near, 64, PoiManager.Occupancy.ANY).orElse(null) ?: return false
        val anchor = findBoard(level, bell) ?: return false
        val today = VillageBoards.day(level)
        if (lastSiegeDay[anchor] == today || sieges.values.any { it.anchor == anchor }) return false
        lastSiegeDay[anchor] = today

        val board = level.getBlockEntity(anchor) as? QuestBoardBlockEntity
        val waves = if ((board?.advanceIndex ?: 0) >= 3) 3 else 2
        val untilDawn = (DAWN - timeOfDay(level)).coerceAtLeast(0L)
        val endsAt = level.gameTime + if (untilDawn in 1L..12000L) untilDawn else 6000L
        val siege = Siege(nextId++, bell, anchor, Direction.Plane.HORIZONTAL.getRandomDirection(level.random), QuestGenerator.tierAt(bell.x, bell.z), waves, endsAt)
        sieges[siege.id] = siege
        level.playSound(null, bell, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64.0f, 1.0f)
        val message = Component.translatable("message.guildmark.siege_start", bell.x, bell.z, Component.translatable("direction.guildmark.${siege.from.serializedName}"))
        for (player in watchers(level, siege)) player.sendSystemMessage(message)
        nextWave(level, siege)
        return true
    }

    /** The board belonging to the village whose bell is at [bell]. */
    private fun findBoard(level: ServerLevel, bell: BlockPos): BlockPos? {
        for (pos in BlockPos.betweenClosed(bell.offset(-10, -5, -10), bell.offset(10, 5, 10))) {
            val state = level.getBlockState(pos)
            if (state.block is QuestBoardBlock) return QuestBoardBlock.anchorPos(pos.immutable(), state)
        }
        return null
    }

    private fun watchers(level: ServerLevel, siege: Siege): List<Player> =
        level.players().filter { it.blockPosition().closerThan(siege.center, WATCH_RANGE) && !it.isSpectator }

    private fun nextWave(level: ServerLevel, siege: Siege) {
        siege.wave++
        siege.waveStarted = level.gameTime
        val radius = Advances.villageRadius(level, siege.center)
        val gather = siege.center.relative(siege.from, radius + 18)
        val count = 5 + siege.tier * 2 + siege.wave
        repeat(count) {
            val x = gather.x + level.random.nextInt(13) - 6
            val z = gather.z + level.random.nextInt(13) - 6
            if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) return@repeat
            val spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            if (!level.getFluidState(spot.below()).isEmpty) return@repeat
            val mob = hordeType(level, siege).create(level, EntitySpawnReason.EVENT) ?: return@repeat
            mob.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)
            mob.setData(ModAttachments.SIEGE, siege.id)
            level.addFreshEntity(mob)
            siege.mobs.add(mob.uuid)
            siege.spawnedTotal++
        }
        if (siege.wave > 1) {
            val message = Component.translatable("message.guildmark.siege_wave", siege.wave, siege.totalWaves)
            for (player in watchers(level, siege)) player.sendSystemMessage(message)
        }
    }

    /** Zombies and skeletons, with spiders, and vindicators joining further from spawn. */
    private fun hordeType(level: ServerLevel, siege: Siege): EntityType<out Mob> {
        val roll = level.random.nextInt(10)
        return when {
            roll < 4 -> EntityTypes.ZOMBIE
            roll < 7 -> EntityTypes.SKELETON
            roll < 9 - siege.tier + 1 -> EntityTypes.SPIDER
            else -> EntityTypes.VINDICATOR
        }
    }

    /** Every 20 ticks: march the horde on the bell, send later waves, and settle sieges that are over. */
    fun tick(level: ServerLevel) {
        if (sieges.isEmpty()) return
        for (siege in sieges.values.toList()) {
            val alive = siege.mobs.mapNotNull { level.getEntity(it) as? Mob }.filter { it.isAlive }
            siege.mobs.retainAll(alive.map { it.uuid }.toSet())
            for (mob in alive) {
                if (mob.target == null && mob.navigation.isDone) mob.navigation.moveTo(siege.center.x + 0.5, siege.center.y.toDouble(), siege.center.z + 0.5, 1.0)
            }

            val watchers = watchers(level, siege)
            siege.bar.name = Component.translatable("gui.guildmark.siege_bar", siege.wave, siege.totalWaves, alive.size)
            siege.bar.progress = if (siege.spawnedTotal == 0) 0.0f else (alive.size.toFloat() / (5 + siege.tier * 2 + siege.wave)).coerceIn(0.0f, 1.0f)
            for (player in siege.bar.players.toList()) if (player !in watchers) siege.bar.removePlayer(player)
            for (player in watchers) if (player is ServerPlayer && player !in siege.bar.players) siege.bar.addPlayer(player)

            val waveCleared = alive.isEmpty() || (alive.size <= 2 && level.gameTime - siege.waveStarted > 2400L)
            when {
                siege.villagersLost >= VILLAGERS_LOST_LIMIT -> end(level, siege, won = false)
                level.gameTime >= siege.endsAt -> end(level, siege, won = alive.isEmpty())
                waveCleared && siege.wave < siege.totalWaves -> nextWave(level, siege)
                alive.isEmpty() -> end(level, siege, won = true)
            }
        }
    }

    /** A villager died: if it was in a besieged village, the defence is failing. */
    fun onDeath(level: ServerLevel, victim: LivingEntity) {
        if (victim !is Villager) return
        for (siege in sieges.values) {
            if (victim.blockPosition().closerThan(siege.center, WATCH_RANGE)) siege.villagersLost++
        }
    }

    private fun end(level: ServerLevel, siege: Siege, won: Boolean) {
        sieges.remove(siege.id)
        siege.bar.removeAllPlayers()
        val defenders = watchers(level, siege)
        if (won) {
            val marks = 6 + siege.tier * 4 + siege.totalWaves * 2
            for (player in defenders) {
                val paid = Progression.reward(player, marks)
                player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), paid), Prediction.SERVER_ONLY)
                Progression.addXp(player, marks * Progression.XP_PER_MARK)
                Standing.add(level, siege.anchor, player, 10)
                player.sendSystemMessage(Component.translatable("message.guildmark.siege_won", paid))
            }
            level.playSound(null, siege.center, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.NEUTRAL, 2.0f, 1.0f)
            GuildNews.add(level, "news.guildmark.siege_won", siege.center.x.toString(), siege.center.z.toString())
        } else {
            damage(level, siege)
            for (player in defenders) {
                Standing.add(level, siege.anchor, player, -10)
                player.sendSystemMessage(Component.translatable("message.guildmark.siege_lost"))
            }
            GuildNews.add(level, "news.guildmark.siege_lost", siege.center.x.toString(), siege.center.z.toString())
        }
        // Stragglers melt away at the end
        for (id in siege.mobs) (level.getEntity(id) as? Mob)?.let { if (!won) it.discard() }
    }

    /** The horde tears down part of the palisade and some lamps, and the village's current project stalls. */
    private fun damage(level: ServerLevel, siege: Siege) {
        val radius = Advances.villageRadius(level, siege.center) + 10
        for (pos in BlockPos.betweenClosed(siege.center.offset(-radius, -6, -radius), siege.center.offset(radius, 10, radius))) {
            val state = level.getBlockState(pos)
            val breakable = state.`is`(Blocks.STRIPPED_SPRUCE_LOG) || state.`is`(Blocks.LANTERN)
            if (breakable && level.random.nextInt(3) == 0) level.destroyBlock(pos.immutable(), false)
        }
        (level.getBlockEntity(siege.anchor) as? QuestBoardBlockEntity)?.let { it.setAdvanceProgress(it.advanceIndex, 0) }
    }
}
