package io.github.vaspyyy.guildmark.road

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.quest.Expeditions
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.animal.equine.TraderLlama
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import java.util.UUID
import kotlin.math.min

/** Which road a merchant or traveller is on, which way it's going, and when it set out. */
data class TrafficState(val road: Int = -1, val forward: Boolean = true, val born: Long = 0L) {
    companion object {
        val CODEC: Codec<TrafficState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("road").forGetter(TrafficState::road),
                Codec.BOOL.fieldOf("forward").forGetter(TrafficState::forward),
                Codec.LONG.fieldOf("born").forGetter(TrafficState::born),
            ).apply(i, ::TrafficState)
        }
    }
}

/**
 * Life on the roads: merchant caravans (a wandering trader and two pack llamas) and lone travellers
 * set out along finished roads near players, walk them to the next village and leave. Some are
 * waylaid by bandits along the way (see [Bandits]).
 */
object Traffic {
    /** Each player rolls for new traffic this often, in ticks. */
    private const val SPAWN_INTERVAL = 600
    private const val SPAWN_CHANCE = 3
    private const val MAX_NEAR_PLAYER = 2
    /** New traffic appears on a stretch of road this far from the player: close, but not on top of them. */
    private const val SPAWN_MIN = 40.0
    private const val SPAWN_MAX = 96.0
    /** With nobody this close, traffic quietly leaves the world rather than walking on in unloaded land. */
    private const val LEAVE_RANGE = 128.0
    private const val MAX_AGE = 48000L
    private const val AMBUSH_PERCENT = 30

    /** Traffic entities currently loaded, per dimension. Rebuilt from entity data as they load. */
    private val active = HashMap<ResourceKey<Level>, MutableSet<UUID>>()

    private fun ids(level: ServerLevel): MutableSet<UUID> = active.getOrPut(level.dimension()) { HashSet() }

    fun onJoin(entity: Entity) {
        val level = entity.level() as? ServerLevel ?: return
        if (entity.hasData(ModAttachments.TRAFFIC)) ids(level).add(entity.uuid)
    }

    /** Called every player tick: now and then, put a caravan or traveller on a road near them. */
    fun trySpawn(level: ServerLevel, player: Player) {
        if (player.tickCount % SPAWN_INTERVAL != 0 || level.random.nextInt(SPAWN_CHANCE) != 0 || !level.isBrightOutside) return
        val near = ids(level).count { id -> level.getEntity(id)?.let { it.distanceTo(player) < LEAVE_RANGE } == true }
        if (near >= MAX_NEAR_PLAYER) return
        spawnNear(level, player, level.random.nextInt(100) < AMBUSH_PERCENT)
    }

    /** Put a caravan or traveller on a road 40 to 96 blocks from [player]. False if no road is that close. */
    fun spawnNear(level: ServerLevel, player: Player, ambush: Boolean): Boolean {
        val stretches = mutableListOf<Pair<Road, Int>>()
        for (road in RoadNetwork.get(level).roads) {
            if (!road.finished) continue
            road.points.forEachIndexed { i, point ->
                val dx = point.x - player.x
                val dz = point.z - player.z
                val distance = Math.sqrt(dx * dx + dz * dz)
                if (distance in SPAWN_MIN..SPAWN_MAX) stretches.add(Pair(road, i))
            }
        }
        val (road, index) = stretches.randomOrNull() ?: return false
        val forward = level.random.nextBoolean()
        val points = if (forward) road.points else road.points.reversed()
        val start = if (forward) index else road.points.size - 1 - index
        if (start >= points.size - 2) return false
        val spot = Travellers.surface(level, points[start])
        if (level.chunkSource.getChunkNow(spot.x shr 4, spot.z shr 4) == null) return false

        val mob: PathfinderMob = (if (level.random.nextInt(5) < 3) createMerchant(level, spot) else createTraveller(level, spot)) ?: return false
        mob.setData(ModAttachments.TRAFFIC, TrafficState(road.id, forward, level.gameTime))
        mob.setData(ModAttachments.TRAVEL_PROGRESS, TravelProgress(start + 1))
        if (ambush) {
            mob.setData(ModAttachments.AMBUSH_AT, min(start + 2 + level.random.nextInt(3), points.size - 2))
        }
        level.addFreshEntity(mob)
        if (mob is WanderingTrader) repeat(2) { addPackLlama(level, mob) }
        return true
    }

    /** Every 10 ticks: walk each loaded traveller along its road, and send off any that are done. */
    fun tick(level: ServerLevel) {
        val ids = ids(level)
        if (ids.isEmpty()) return
        val network = RoadNetwork.get(level)
        for (id in ids.toList()) {
            val mob = level.getEntity(id) as? PathfinderMob
            if (mob == null || !mob.isAlive) {
                ids.remove(id)
                continue
            }
            val state = mob.getData(ModAttachments.TRAFFIC)
            val road = network.road(state.road)
            val nobodyNear = level.players().none { it.distanceTo(mob) < LEAVE_RANGE }
            if (road == null || nobodyNear || level.gameTime - state.born > MAX_AGE) {
                leave(level, mob)
                continue
            }
            if (Bandits.tick(level, mob)) {
                steer(mob, null)
                continue
            }
            val points = if (state.forward) road.points else road.points.reversed()
            if (Travellers.walk(level, mob, points) { steer(mob, it) }) leave(level, mob)
        }
    }

    private fun steer(mob: PathfinderMob, target: BlockPos?) {
        when (mob) {
            is WanderingTrader -> mob.setWanderTarget(target)
            is Villager -> {
                Expeditions.keepOnTheRoad(mob)
                if (target != null) mob.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(target, 0.5f, 1))
                else mob.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            }
            else -> if (target != null) mob.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 0.6)
        }
    }

    /** Reached the village (or nobody's around to see): the traveller and its llamas go on their way. */
    private fun leave(level: ServerLevel, mob: PathfinderMob) {
        if (mob is WanderingTrader) {
            level.getEntitiesOfClass(TraderLlama::class.java, AABB(mob.blockPosition()).inflate(24.0)) { it.leashHolder === mob }
                .forEach { it.discard() }
        }
        mob.discard()
        ids(level).remove(mob.uuid)
    }

    private fun createMerchant(level: ServerLevel, spot: BlockPos): WanderingTrader? {
        val trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.EVENT) ?: return null
        trader.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        trader.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)
        // We send it off ourselves when it reaches the village
        trader.despawnDelay = 0
        trader.customName = Component.translatable("entity.guildmark.merchant", QuestGenerator.nameFor(level.random.nextLong()))
        return trader
    }

    private fun createTraveller(level: ServerLevel, spot: BlockPos): Villager? {
        val villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT) ?: return null
        villager.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        villager.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)
        villager.customName = Component.translatable("entity.guildmark.traveller", QuestGenerator.nameFor(level.random.nextLong()))
        return villager
    }

    private fun addPackLlama(level: ServerLevel, trader: WanderingTrader) {
        val llama = EntityTypes.TRADER_LLAMA.create(level, EntitySpawnReason.EVENT) ?: return
        val spot = trader.blockPosition().offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2)
        llama.snapTo(spot.x + 0.5, trader.y, spot.z + 0.5)
        llama.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)
        llama.setLeashedTo(trader, true)
        level.addFreshEntity(llama)
    }
}
