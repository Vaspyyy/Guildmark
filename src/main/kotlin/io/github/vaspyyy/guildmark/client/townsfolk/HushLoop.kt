package io.github.vaspyyy.guildmark.client.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The murmur behind a bolted door while a village shelters (`village.hush`, Villages V12b; the shelter is village/live/trouble's
 * Sheltering): one loop at the door of a house where [HUDDLE] or more cower, not one each, heard to 12 blocks (the sound's own
 * reach). It fades in as they settle and out as they come out. The huddle is found from those within earshot showing COWER
 * ([TownsfolkClient.tick] tells each one), within [NEAR] blocks of its middle; the door is the nearest door block to them.
 */
internal object HushLoop {
    private val HUSH: SoundEvent = SoundEvent.createFixedRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village.hush"), 12f)

    /** How many cowering together make the murmur. */
    private const val HUDDLE = 2

    /** Blocks from a huddle's middle that count as in it; how far its door may be from it. */
    private const val NEAR = 6.0
    private const val DOOR = 8

    /** Blocks within which a huddle already has its loop. */
    private const val HAS_LOOP = 8.0

    private val loops = ArrayList<Loop>()

    /** [p], within earshot, is showing COWER: a huddle of [HUDDLE] or more about them with no loop yet gets one. Looked at a tick in 20 for each. */
    fun heard(p: Townsperson) {
        if ((p.tickCount + p.id) % 20 != 0) return
        val sounds = Minecraft.getInstance().soundManager
        loops.removeIf { it.isStopped || !sounds.isActive(it) }
        val huddle = cowering(p.level(), p.position())
        if (huddle.size < HUDDLE) return
        val middle = middle(huddle) ?: return
        if (loops.any { it.near(middle, HAS_LOOP) }) return
        val loop = Loop(p.level(), middle, door(p.level(), middle))
        if (sounds.play(loop) != SoundEngine.PlayResult.NOT_STARTED) loops += loop
    }

    /** Those showing COWER within [NEAR] blocks of [at]. */
    private fun cowering(level: Level, at: Vec3): List<Townsperson> =
        level.getEntitiesOfClass(Townsperson::class.java, AABB.ofSize(at, NEAR * 2, 6.0, NEAR * 2)) {
            it.isAlive && it.shownActivity == Activity.COWER && it.distanceToSqr(at) <= NEAR * NEAR
        }

    /** The middle of [huddle], at head height when crouched; null for nobody. */
    private fun middle(huddle: List<Townsperson>): Vec3? {
        if (huddle.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (p in huddle) {
            x += p.x
            y += p.y
            z += p.z
        }
        return Vec3(x / huddle.size, y / huddle.size + 0.8, z / huddle.size)
    }

    /** The middle of the nearest door's lower half to [at], within [DOOR] blocks; [at] itself if there is none. */
    private fun door(level: Level, at: Vec3): Vec3 {
        val c = BlockPos.containing(at)
        var best: BlockPos? = null
        var bestD = Double.MAX_VALUE
        val cursor = BlockPos.MutableBlockPos()
        for (dx in -DOOR..DOOR) for (dz in -DOOR..DOOR) for (dy in -1..1) {
            cursor.set(c.x + dx, c.y + dy, c.z + dz)
            if (!level.getBlockState(cursor).`is`(BlockTags.DOORS)) continue
            val d = cursor.distToCenterSqr(at)
            if (d < bestD) {
                bestD = d
                best = cursor.immutable()
            }
        }
        return best?.let { Vec3.atCenterOf(it) } ?: at
    }

    /** One house's murmur: at its door, a little louder for a bigger huddle, gone once fewer than [HUDDLE] cower there. */
    private class Loop(private val level: Level, private val huddle: Vec3, at: Vec3) :
        AbstractTickableSoundInstance(HUSH, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        private var want = 0.6f

        init {
            looping = true
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0.01f
            x = at.x
            y = at.y
            z = at.z
        }

        fun near(p: Vec3, r: Double): Boolean = p.distanceToSqr(huddle) < r * r

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (level.gameTime % 10L == 0L) {
                val n = cowering(level, huddle).size
                want = if (n >= HUDDLE && Minecraft.getInstance().level === level) (0.5f + 0.08f * n).coerceAtMost(0.9f) else 0f
            }
            volume += (want - volume) * if (want > volume) 0.02f else 0.05f
            if (want == 0f && volume < 0.01f) stop()
        }
    }
}
