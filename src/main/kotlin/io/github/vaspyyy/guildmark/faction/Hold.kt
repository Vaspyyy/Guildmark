package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.enemy.HollowSoldier
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Long Night: a Wardfire on the road out of the city, two sworn knights standing at it, and the player with them
 * from nightfall. Three waves of Hollows come out of the dark for the fire; every one that reaches it drains it, and
 * the bar overhead shows how much it has left. It going out loses the night (try again the next), and so does
 * leaving it; the last wave falling wins it.
 */
class Hold(player: ServerPlayer) : TrialRun(player) {
    private var fire: BlockPos? = null
    private var lookedAt = -1000
    /** Looks for ground to raise the fire on that found none, each looking a little further out. */
    private var misses = 0
    private val knights = ArrayList<SwornKnight>()
    private val hollows = ArrayList<HollowSoldier>()
    private var night = false
    private var wave = 0
    private var strength = FULL
    private var nextWaveAt = -1L
    private var lostNight = -1L
    private var told = false
    private var drainedAt = 0
    private var won = false
    private val bar = ServerBossEvent(UUID.randomUUID(), Component.translatable("bossbar.guildmark.hold"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10)

    override fun tick(level: ServerLevel, stack: ItemStack, note: QuestNote, state: ContractState): Boolean {
        val destination = state.destination ?: return true
        val fire = fire ?: locate(level, destination) ?: return true
        val at = Vec3.atBottomCenterOf(fire)
        val distance = sqrt(player.distanceToSqr(at))
        if (won || state.progress >= note.count) return distance < FAR
        val clock = level.overworldClockTime
        if (!night) {
            if (distance > FAR) {
                dismiss()
                return true
            }
            if (player.tickCount % 20 != 0) return true
            if (distance < GATHER) stand(level, fire)
            if (distance < START && !told) {
                told = true
                player.sendSystemMessage(Component.translatable("message.guildmark.hold_wait"))
            }
            val time = Math.floorMod(clock, DAY)
            if (time in DUSK until DAWN && clock / DAY != lostNight && distance < START && player.isAlive && !player.isSpectator) begin(level, fire)
            return true
        }

        // The night under way
        if (!player.isAlive || distance > LEAVE) {
            lose(level, "message.guildmark.hold_far", clock)
            return true
        }
        hollows.removeIf { hollow ->
            // One that wandered off is lost to the dark, not left roaming after the player
            val strayed = hollow.isAlive && !hollow.isRemoved && hollow.distanceToSqr(at) > STRAY * STRAY
            if (strayed) hollow.discard()
            strayed || !hollow.isAlive || hollow.isRemoved
        }
        knights.removeIf { !it.isAlive || it.isRemoved }
        // The Hollows at the fire drain it; the rest come for it, and for whoever stands in their way
        var draining = 0
        for (hollow in hollows) {
            if (hollow.distanceToSqr(at) < DRAIN_RANGE * DRAIN_RANGE) draining++
            val target = hollow.target
            if (target == null || !target.isAlive) {
                if (player.tickCount % 20 == 0) hollow.navigation.moveTo(at.x, at.y, at.z, 1.0)
                if (hollow.distanceTo(player) < 10f) hollow.target = player
            }
        }
        if (draining > 0) {
            strength -= DRAIN * draining
            if (player.tickCount - drainedAt > 30) {
                drainedAt = player.tickCount
                level.playSound(null, at.x, at.y, at.z, FactionRegistry.HOLD_DRAIN.get(), SoundSource.HOSTILE, 0.8f, 0.9f + level.random.nextFloat() * 0.2f)
                level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.6, at.z, 6, 0.3, 0.2, 0.3, 0.02)
            }
        }
        bar.progress = (strength / FULL).coerceIn(0f, 1f)
        if (strength <= 0f) {
            lose(level, "message.guildmark.hold_lost", clock)
            return true
        }
        if (hollows.isEmpty()) {
            if (wave >= WAVES.size) {
                win(level, stack)
                return true
            }
            if (nextWaveAt < 0) nextWaveAt = level.gameTime + BREATHER
            else if (level.gameTime >= nextWaveAt) {
                nextWaveAt = -1
                send(level, fire)
            }
        }
        return true
    }

    /** The Wardfire on the road: one already standing at [destination], or one raised there when it's near and loaded. */
    private fun locate(level: ServerLevel, destination: BlockPos): BlockPos? {
        if (player.distanceToSqr(Vec3.atCenterOf(destination)) > FAR * FAR || player.tickCount - lookedAt < 100) return null
        lookedAt = player.tickCount
        if (!Places.loaded(level, destination, 20)) return null
        val wardfire = ModBlocks.WARDFIRE.get()
        // Somewhere tidy if it can, further out each time it can't; in the end, any firm dry ground will do
        val found = BlockPos.betweenClosedStream(destination.offset(-14, -10, -14), destination.offset(14, 10, 14))
            .filter { level.getBlockState(it).`is`(wardfire) }
            .findFirst().map { it.immutable() }.orElse(null)
            ?: WardfireSites.placeNear(level, destination, 0, 12 + 8 * misses.coerceAtMost(3))
            ?: if (misses >= 3) WardfireSites.placeAnywhere(level, destination, 24) else null
        if (found == null) misses++
        fire = found
        return found
    }

    /** The two knights take their places either side of the fire, facing out. */
    private fun stand(level: ServerLevel, fire: BlockPos) {
        knights.removeIf { !it.isAlive || it.isRemoved }
        if (knights.size >= 2 || !Places.loaded(level, fire, 6)) return
        for (side in knights.size until 2) {
            val angle = Math.PI * side + 0.6
            val x = fire.x + (cos(angle) * 2.5).toInt()
            val z = fire.z + (sin(angle) * 2.5).toInt()
            val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            val post = if (Places.standable(level, ground)) ground else FactionSeats.standNear(level, ground) ?: continue
            val yaw = Math.toDegrees(Math.atan2(-(post.x - fire.x).toDouble(), (post.z - fire.z).toDouble())).toFloat()
            SwornKnight.create(level, post, yaw, SwornKnight.Mode.ALLY)?.let { knights.add(it) }
        }
    }

    private fun begin(level: ServerLevel, fire: BlockPos) {
        night = true
        wave = 0
        strength = FULL
        nextWaveAt = level.gameTime + BREATHER
        bar.progress = 1f
        bar.name = Component.translatable("bossbar.guildmark.hold")
        bar.addPlayer(player)
        stand(level, fire)
        player.sendSystemMessage(Component.translatable("message.guildmark.hold_begin"))
        level.playSound(null, player.x, player.y, player.z, FactionRegistry.HOLD_HORN.get(), SoundSource.HOSTILE, 2f, 1f)
        for (knight in knights) knight.salute(level)
    }

    /** The next wave comes out of the dark, each of them rising where the shadows are. */
    private fun send(level: ServerLevel, fire: BlockPos) {
        val count = WAVES[wave]
        wave++
        bar.name = Component.translatable("bossbar.guildmark.hold_wave", wave, WAVES.size)
        player.sendOverlayMessage(Component.translatable("message.guildmark.hold_wave", wave, WAVES.size))
        level.playSound(null, player.x, player.y, player.z, FactionRegistry.HOLD_HORN.get(), SoundSource.HOSTILE, 1.6f, 0.85f + 0.08f * wave)
        val random = level.random
        val from = random.nextDouble() * Math.PI * 2
        var sent = 0
        var tries = 0
        while (sent < count && tries++ < count * 6) {
            // They come mostly from one side, spread a little
            val angle = from + (random.nextDouble() - 0.5) * 1.6
            val distance = 16.0 + random.nextDouble() * 8.0
            val x = fire.x + (cos(angle) * distance).toInt()
            val z = fire.z + (sin(angle) * distance).toInt()
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            if (!Places.standable(level, ground)) continue
            val hollow = ModEntities.HOLLOW_SOLDIER.get().create(level, EntitySpawnReason.EVENT) ?: continue
            hollow.snapTo(ground.x + 0.5, ground.y.toDouble(), ground.z + 0.5, random.nextFloat() * 360f, 0f)
            hollow.finalizeSpawn(level, level.getCurrentDifficultyAt(ground), EntitySpawnReason.EVENT, null)
            hollow.target = player
            level.addFreshEntity(hollow)
            level.sendParticles(ParticleTypes.SOUL, ground.x + 0.5, ground.y + 0.8, ground.z + 0.5, 12, 0.3, 0.6, 0.3, 0.03)
            level.sendParticles(ParticleTypes.LARGE_SMOKE, ground.x + 0.5, ground.y + 0.3, ground.z + 0.5, 8, 0.4, 0.2, 0.4, 0.01)
            hollows.add(hollow)
            sent++
        }
    }

    private fun win(level: ServerLevel, stack: ItemStack) {
        won = true
        night = false
        bar.removeAllPlayers()
        player.sendSystemMessage(Component.translatable("message.guildmark.hold_won"))
        level.playSound(null, player.x, player.y, player.z, FactionRegistry.HOLD_DAWN.get(), SoundSource.PLAYERS, 1.5f, 1f)
        for (knight in knights) knight.salute(level)
        Trials.complete(player, stack)
    }

    private fun lose(level: ServerLevel, key: String, clock: Long) {
        night = false
        lostNight = clock / DAY
        told = false
        bar.removeAllPlayers()
        player.sendSystemMessage(Component.translatable(key))
        level.playSound(null, player.x, player.y, player.z, FactionRegistry.HOLD_DRAIN.get(), SoundSource.HOSTILE, 1.2f, 0.7f)
        // The rest of them melt back into the dark
        for (hollow in hollows) {
            if (!hollow.isAlive) continue
            level.sendParticles(ParticleTypes.SOUL, hollow.x, hollow.y + 1.0, hollow.z, 10, 0.3, 0.6, 0.3, 0.02)
            hollow.discard()
        }
        hollows.clear()
        for (knight in knights) knight.reset()
    }

    private fun dismiss() {
        for (knight in knights) if (knight.isAlive) knight.discard()
        knights.clear()
    }

    override fun stop(level: ServerLevel) {
        bar.removeAllPlayers()
        dismiss()
        for (hollow in hollows) if (hollow.isAlive) hollow.discard()
        hollows.clear()
    }

    companion object {
        private const val DAY = 24000L
        private const val DUSK = 13000L
        private const val DAWN = 23000L
        /** How near the knights come out, the night begins, the player has to stay; how far before it's all put away. */
        private const val GATHER = 40.0
        private const val START = 12.0
        private const val LEAVE = 40.0
        private const val FAR = 96.0
        /** Hollows further than this from the fire have wandered off and are lost. */
        private const val STRAY = 48.0
        private const val DRAIN_RANGE = 3.0
        private const val FULL = 100f
        /** The fire's strength a Hollow at it drains each tick. */
        private const val DRAIN = 0.2f
        /** Ticks between one wave falling and the next coming. */
        private const val BREATHER = 100L
        private val WAVES = intArrayOf(4, 6, 8)

        /** Sets up a newly taken Long Night: a place on the road out of the city, past its farms. */
        fun begin(level: ServerLevel, player: Player, state: ContractState): ContractState? {
            val capital = Capitals.covering(level, state.boardPos) ?: return null
            val centre = capital.center
            val dx = (state.boardPos.x - centre.x).toDouble()
            val dz = (state.boardPos.z - centre.z).toDouble()
            val toward = if (dx * dx + dz * dz < 4.0) centre.offset(0, 0, 1000) else BlockPos.containing(centre.x + dx * 50, centre.y.toDouble(), centre.z + dz * 50)
            val way = Capitals.wayOut(capital, toward)
            if (way.size < 2) return null
            val end = way.last()
            val back = way[maxOf(0, way.size - 5)]
            var ox = (end.x - back.x).toDouble()
            var oz = (end.z - back.z).toDouble()
            val length = sqrt(ox * ox + oz * oz)
            if (length < 0.5) {
                ox = end.x - centre.x.toDouble()
                oz = end.z - centre.z.toDouble()
            }
            val norm = sqrt(ox * ox + oz * oz).coerceAtLeast(1.0)
            val spot = BlockPos.containing(end.x + ox / norm * ROAD_ON, end.y.toDouble(), end.z + oz / norm * ROAD_ON)
            player.sendSystemMessage(Component.translatable("message.guildmark.hold_road"))
            return state.copy(destination = spot)
        }

        /** Blocks along the road past the city's last farms. */
        private const val ROAD_ON = 30.0
    }
}
