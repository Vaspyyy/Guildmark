package io.github.vaspyyy.guildmark.client.mill

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.mill.MillBlock
import io.github.vaspyyy.guildmark.mill.MillBlockEntity
import io.github.vaspyyy.guildmark.mill.MillKind
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * The sounds by a mill while it turns, near and quiet: the sails' timbers creaking as they go round, the millstones
 * rumbling inside a mill while it grinds, and a wheel's paddles splashing through its stream. Each is a loop placed at the
 * mill (the stones inside it), louder and a little higher the faster the mill turns and fading out as it stops; only the
 * few mills nearest the player are heard.
 */
object MillSounds {
    private val SAILS = sound("mill.sails")
    private val STONES = sound("mill.stones")
    private val WHEEL = sound("mill.wheel")

    private data class Key(val pos: Long, val sound: Int)

    private val loops = HashMap<Key, Loop>()
    private val retry = HashMap<Key, Int>()
    private var level: ClientLevel? = null
    private var ticks = 0

    private fun sound(name: String): SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))

    /** Every client tick: keeps the loops of the mills near the player going, as loud as each is turning. */
    fun tick() {
        val minecraft = Minecraft.getInstance()
        val current = minecraft.level
        val player = minecraft.player
        if (current == null || player == null) {
            if (level != null) reset()
            return
        }
        if (current !== level) {
            reset()
            level = current
        }
        if (minecraft.isPaused) return
        ticks++
        if (ticks % LOOK != 0) return
        val ear = player.eyePosition
        MillBlockEntity.LOADED.removeIf { it.isRemoved || it.level !== current }
        val near = MillBlockEntity.LOADED.filter { it.blockPos.distToCenterSqr(ear) < HEAR * HEAR }
            .sortedBy { it.blockPos.distToCenterSqr(ear) }
            .take(MILLS)
        val wanted = HashSet<Key>()
        for (mill in near) {
            val state = mill.blockState
            if (!state.hasProperty(MillBlock.FACING)) continue
            val facing = state.getValue(MillBlock.FACING)
            val hub = Vec3.atCenterOf(mill.blockPos)
            val back = facing.opposite
            if (mill.kind == MillKind.SAILS) {
                val turning = (mill.speed / MillBlockEntity.SAIL_SPEED).coerceIn(0f, 1f)
                drive(Key(mill.blockPos.asLong(), 0), SAILS, hub, 0.85f * turning, 0.85f + 0.15f * turning, wanted)
                // The stones only grind once the sails are well under way, inside the mill under the cap
                val grinding = smooth((turning - 0.5f) / 0.5f)
                drive(Key(mill.blockPos.asLong(), 1), STONES, along(hub, back, STONES_BACK).add(0.0, -STONES_DOWN, 0.0), 0.8f * grinding,
                    0.9f + 0.1f * turning, wanted)
            } else {
                val turning = (mill.speed / MillBlockEntity.WHEEL_WORK).coerceIn(0f, 1f)
                val splash = if (mill.speed > 0f) 0.45f + 0.55f * turning else 0f
                drive(Key(mill.blockPos.asLong(), 2), WHEEL, along(hub, facing, 0.5).add(0.0, -1.5, 0.0), splash, 0.75f + 0.25f * turning, wanted)
                // The wheel turns all the time, slowly; the stones grind only once it's working hard
                val grinding = smooth((turning - 0.5f) / 0.5f)
                drive(Key(mill.blockPos.asLong(), 1), STONES, along(hub, back, WHEEL_STONES_BACK).add(0.0, 1.0, 0.0), 0.8f * grinding,
                    0.9f + 0.1f * turning, wanted)
            }
        }
        // Whatever isn't wanted any more fades out and stops by itself
        val entries = loops.entries.iterator()
        while (entries.hasNext()) {
            val (key, loop) = entries.next()
            if (key !in wanted) loop.target = 0f
            if (loop.isStopped) entries.remove()
        }
    }

    /** Stops listening: every loop fades out by itself (or stops with the sound engine on leaving the world). */
    fun reset() {
        for (loop in loops.values) loop.target = 0f
        loops.clear()
        retry.clear()
        level = null
    }

    private fun drive(key: Key, sound: SoundEvent, at: Vec3, volume: Float, pitch: Float, wanted: MutableSet<Key>) {
        wanted.add(key)
        val manager = Minecraft.getInstance().soundManager
        val loop = loops[key]
        if (loop != null && !loop.isStopped && manager.isActive(loop)) {
            loop.target = volume
            loop.goal = pitch
            return
        }
        loops.remove(key)
        if (volume < 0.02f || (retry[key] ?: 0) > ticks) return
        val fresh = Loop(sound, at)
        fresh.target = volume
        fresh.goal = pitch
        if (manager.play(fresh) == SoundEngine.PlayResult.NOT_STARTED) retry[key] = ticks + RETRY else loops[key] = fresh
    }

    private fun along(at: Vec3, way: Direction, blocks: Double): Vec3 =
        at.add(way.stepX * blocks, way.stepY * blocks, way.stepZ * blocks)

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** One mill's sound, going round and round at one spot, easing toward [target] and [goal]; silent a while, it stops. */
    private class Loop(sound: SoundEvent, at: Vec3) : AbstractTickableSoundInstance(sound, SoundSource.BLOCKS, SoundInstance.createUnseededRandom()) {
        var target = 0f
        var goal = 1f
        private var silent = 0

        init {
            looping = true
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0f
            pitch = 1f
            x = at.x
            y = at.y
            z = at.z
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            volume += (target - volume) * if (target > volume) FADE_IN else FADE_OUT
            if (abs(target - volume) < 0.002f) volume = target
            pitch += (goal - pitch) * PITCH_EASE
            silent = if (volume <= 0.001f) silent + 1 else 0
            if (silent > STOP_AFTER) stop()
        }
    }

    // Ticks between looks at the mills; how far off a mill is heard (its sounds carry 16 to 28 blocks); how many at once
    private const val LOOK = 10
    private const val HEAR = 32.0
    private const val MILLS = 3

    // Where the stones are: in a windmill, under the cap in the middle of the tower; in a watermill, just inside its wall
    private const val STONES_BACK = 5.0
    private const val STONES_DOWN = 6.0
    private const val WHEEL_STONES_BACK = 3.0

    private const val RETRY = 100
    private const val FADE_IN = 0.03f
    private const val FADE_OUT = 0.05f
    private const val PITCH_EASE = 0.05f
    private const val STOP_AFTER = 100
}
