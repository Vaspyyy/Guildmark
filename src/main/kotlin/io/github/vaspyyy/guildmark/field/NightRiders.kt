package io.github.vaspyyy.guildmark.field

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.enemy.DuskRider
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Dusk Riders that ride out at night. At dusk it's settled, for each player, whether a rider comes for them
 * that night and when. At that hour, if they're out under the open sky in the wild (past the land round where
 * the world began, and away from villages), a horn sounds in the dark and a rider comes for them from out of
 * sight. It goes with the night. Nothing here is saved: after a restart each player's night is settled afresh.
 */
object NightRiders {
    /** A player's night: which night it is, and the time of day a rider comes for them (-1: none, or sent). */
    private class Plan(val night: Long, var at: Long)

    private val plans = HashMap<UUID, Plan>()

    fun enabled(): Boolean = try {
        GuildmarkConfig.DUSK_RIDERS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** Once a second, in the overworld. */
    fun tick(level: ServerLevel) {
        if (level.dimension() != Level.OVERWORLD || !enabled() || level.difficulty == Difficulty.PEACEFUL) return
        val clock = level.overworldClockTime
        val time = clock % DAY
        if (time < DUSK || time > LAST_CALL) return
        val night = clock / DAY
        // Last night's plans are done with
        plans.values.removeIf { it.night != night }
        for (player in level.players()) {
            val plan = plans[player.uuid]?.takeIf { it.night == night } ?: Plan(night, plan(level)).also { plans[player.uuid] = it }
            if (plan.at < 0 || time < plan.at) continue
            // Not out in the wild yet, or nowhere for a rider to come from: it tries again a little later
            plan.at = if (fits(level, player) && send(level, player, false) != null) -1 else time + RETRY
        }
    }

    /** The time a rider comes tonight, or -1. */
    private fun plan(level: ServerLevel): Long =
        if (level.random.nextFloat() < CHANCE) PLAN_FROM + level.random.nextInt((PLAN_TO - PLAN_FROM).toInt()) else -1L

    /** Whether [player] is out where a rider would come for them. */
    private fun fits(level: ServerLevel, player: ServerPlayer): Boolean {
        if (!player.isAlive || player.isSpectator || player.isCreative || player.isSleeping) return false
        val pos = player.blockPosition()
        if (!level.canSeeSky(pos)) return false
        if (QuestGenerator.tierAt(pos.x, pos.z) < 2 && sqrt(pos.x.toDouble() * pos.x + pos.z.toDouble() * pos.z) < WILD_FROM) return false
        if (nearVillage(level, pos)) return false
        return level.getEntitiesOfClass(DuskRider::class.java, player.boundingBox.inflate(RIDER_GAP)).isEmpty()
    }

    /** A village bell within [VILLAGE_GAP] of [pos], in land that's loaded (looking further would read it from disk). */
    private fun nearVillage(level: ServerLevel, pos: BlockPos): Boolean {
        for (cx in ((pos.x - VILLAGE_GAP) shr 4)..((pos.x + VILLAGE_GAP) shr 4)) for (cz in ((pos.z - VILLAGE_GAP) shr 4)..((pos.z + VILLAGE_GAP) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) continue
            val bell = level.poiManager.getInChunk({ it.`is`(PoiTypes.MEETING) }, ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
                .anyMatch { it.pos.closerThan(pos, VILLAGE_GAP.toDouble()) }
            if (bell) return true
        }
        return false
    }

    /**
     * Sends a rider for [player] from open ground out of sight, 56 to 80 blocks off, with a horn heard far and
     * wide; or, [near] (for testing), 20 to 30 blocks off. One that came with the [night] fades at dawn. Null if
     * there's nowhere for it to come from.
     */
    fun send(level: ServerLevel, player: ServerPlayer, near: Boolean, night: Boolean = !near): DuskRider? {
        val random = level.random
        val (min, max) = if (near) 20 to 30 else 56 to 80
        repeat(24) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = min + random.nextInt(max - min + 1)
            val x = player.blockX + (cos(angle) * distance).toInt()
            val z = player.blockZ + (sin(angle) * distance).toInt()
            if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) return@repeat
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            // Only where the world is running, or it would stand there frozen
            if (!level.isPositionEntityTicking(top)) return@repeat
            if (top.y <= level.minY + 1 || !level.getFluidState(top).isEmpty || !level.getFluidState(top.below()).isEmpty) return@repeat
            if (!level.getBlockState(top.below()).isFaceSturdy(level, top.below(), Direction.UP)) return@repeat
            val rider = ModEntities.DUSK_RIDER.get().create(level, EntitySpawnReason.EVENT) ?: return null
            // Facing the one it came for
            val yaw = Math.toDegrees(atan2(-(player.x - (top.x + 0.5)), player.z - (top.z + 0.5))).toFloat()
            rider.snapTo(top.x + 0.5, top.y.toDouble(), top.z + 0.5, yaw, 0f)
            if (!level.noCollision(rider)) return@repeat
            rider.yBodyRot = yaw
            rider.yHeadRot = yaw
            rider.finalizeSpawn(level, level.getCurrentDifficultyAt(top), EntitySpawnReason.EVENT, null)
            rider.scaleTo(QuestGenerator.tierAt(top.x, top.z))
            rider.quarry = player.uuid
            rider.boundToNight = night
            level.addFreshEntity(rider)
            level.playSound(null, rider.x, rider.y + 2.0, rider.z, ModSounds.RIDER_HORN.get(), SoundSource.HOSTILE, HORN_VOLUME, 1f)
            player.sendOverlayMessage(Component.translatable("message.guildmark.dusk_rider_horn"))
            return rider
        }
        return null
    }

    /** A rider fell: it leaves its bridle and its spoils where it fell. */
    fun onSlain(level: ServerLevel, rider: DuskRider) {
        val tier = QuestGenerator.tierAt(rider.blockX, rider.blockZ)
        if (level.gameRules.get(GameRules.MOB_DROPS)) {
            rider.spawnAtLocation(level, Relics.roll(level, tier + 2, if (tier >= 2) Rarity.RARE else Rarity.UNCOMMON))
            rider.spawnAtLocation(level, ItemStack(ModItems.GUILD_MARK.get(), 16 + tier * 8 + level.random.nextInt(8)))
            rider.spawnAtLocation(level, ItemStack(ModItems.DUSK_BRIDLE.get()))
            if (level.random.nextFloat() < 0.35f) rider.spawnAtLocation(level, Relics.cache(Rarity.UNCOMMON))
        }
        val heroes = level.players().filter { it.isAlive && it.distanceToSqr(rider) < HERO_RANGE * HERO_RANGE }
        for (player in heroes) {
            (player as? ServerPlayer)?.sendSystemMessage(Component.translatable("message.guildmark.dusk_rider_slain", rider.title))
        }
        GuildNews.add(level, "news.guildmark.field_boss", heroes.joinToString(", ") { it.name.string }.ifEmpty { "?" },
            "#${DuskRider.NAME_KEY}.${rider.nameIndex}", rider.blockX.toString(), rider.blockZ.toString())
    }

    private const val DAY = 24000L
    private const val DUSK = 13000L
    /** The hours a rider might come, at the latest by midnight. */
    private const val PLAN_FROM = 13500L
    private const val PLAN_TO = 18000L
    /** After this hour it's too late for a rider to come tonight. */
    private const val LAST_CALL = 20000L
    /** Clock ticks before trying again, when the player wasn't out in the wild. */
    private const val RETRY = 200L
    /** The chance a rider comes for a player on any one night. */
    private const val CHANCE = 0.3f
    /** Near the world's origin, the land under this many blocks out is kept free of riders. */
    private const val WILD_FROM = 600.0
    private const val VILLAGE_GAP = 96
    /** A rider never comes for someone with one already this near. */
    private const val RIDER_GAP = 160.0
    /** Its horn carries 16 blocks for each of these. */
    private const val HORN_VOLUME = 12f
    private const val HERO_RANGE = 48.0
}
