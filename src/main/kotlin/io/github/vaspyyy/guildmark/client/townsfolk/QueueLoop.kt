package io.github.vaspyyy.guildmark.client.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The bread queue's grumble (`streets.queue`; the line is townsfolk/scenes/BreadQueue's): one loop at a line's middle
 * while [LINE] or more stand in it, not one each. It fades in as the line forms, follows its middle as it moves up, and
 * fades out as it thins. The line is found from those within earshot showing QUEUE ([TownsfolkClient.tick] tells each
 * one), within [NEAR] blocks of its middle.
 */
internal object QueueLoop {
    private val QUEUE: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "streets.queue"))

    /** How many standing in line make it grumble. */
    private const val LINE = 3

    /** Blocks from a line's middle that count as standing in it. */
    private const val NEAR = 6.0

    /** Blocks within which a line already has its loop. */
    private const val HAS_LOOP = 10.0

    private val loops = ArrayList<Loop>()

    /** [p], within earshot, is showing QUEUE: a line of [LINE] or more about them with no loop yet gets one. Looked at a tick in 20 for each. */
    fun heard(p: Townsperson) {
        if ((p.tickCount + p.id) % 20 != 0) return
        val sounds = Minecraft.getInstance().soundManager
        loops.removeIf { it.isStopped || !sounds.isActive(it) }
        if (loops.any { it.near(p.position(), HAS_LOOP) }) return
        val line = standing(p.level(), p.position())
        if (line.size < LINE) return
        val loop = Loop(p.level(), middle(line))
        if (sounds.play(loop) != SoundEngine.PlayResult.NOT_STARTED) loops += loop
    }

    /** Those showing QUEUE within [NEAR] blocks of [at]. */
    private fun standing(level: Level, at: Vec3): List<Townsperson> =
        level.getEntitiesOfClass(Townsperson::class.java, AABB.ofSize(at, NEAR * 2, 6.0, NEAR * 2)) {
            it.isAlive && it.shownActivity == Activity.QUEUE && it.distanceToSqr(at) <= NEAR * NEAR
        }

    /** The middle of [line], at head height. */
    private fun middle(line: List<Townsperson>): Vec3 {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (p in line) {
            x += p.x
            y += p.y
            z += p.z
        }
        return Vec3(x / line.size, y / line.size + 1.5, z / line.size)
    }

    /** One line's grumble: louder as it's longer, moving with its middle, gone once it's thinned to under [LINE]. */
    private class Loop(private val level: Level, at: Vec3) :
        AbstractTickableSoundInstance(QUEUE, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        private var want = 0.7f

        init {
            looping = true
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0.01f
            x = at.x
            y = at.y
            z = at.z
        }

        fun near(p: Vec3, r: Double): Boolean = p.distanceToSqr(x, y, z) < r * r

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (level.gameTime % 10L == 0L) {
                val line = standing(level, Vec3(x, y, z))
                want = if (line.size >= LINE && Minecraft.getInstance().level === level) (0.55f + 0.08f * line.size).coerceAtMost(1f) else 0f
                if (line.isNotEmpty()) {
                    val m = middle(line)
                    x = m.x
                    y = m.y
                    z = m.z
                }
            }
            volume += (want - volume) * if (want > volume) 0.03f else 0.05f
            if (want == 0f && volume < 0.01f) stop()
        }
    }
}
