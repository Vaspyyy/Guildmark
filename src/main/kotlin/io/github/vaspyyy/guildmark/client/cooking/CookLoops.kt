package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import kotlin.math.abs

/**
 * The four loops of the fires (living-world/cook/design.md 9.5, 11.2): the pot's simmer, the pan's sizzle, the oven's hum and the
 * spit's creak. Each is one sound that goes round and round at its station while the station asks for it ([StationAnim.loopLevel]:
 * how hot it is, whether it has something on it), swelling in as the fire takes and dying away as it goes, and it is the game's own
 * sound engine that makes it quieter with distance, by the sound's own `attenuation_distance` (24 blocks for the simmer, 20 for the rest).
 * What is added here is the rule that no more than [MAX] are running at once: the nearest three stations that want one, with some
 * grip on the ones already running so two at the same distance do not trade places. A loop that has sat silent for a couple of seconds
 * stops, and is started afresh when it is wanted again.
 *
 * It obeys Cooking Sounds (the option's 0 to 100 scales every loop, 0 silences them) and the master switch. A sound with no entry in
 * `sounds.json` is never started (it would be silent anyway).
 */
object CookLoops {
    /** No more than this many are running (and so audible) at once (9.5). */
    const val MAX = 3

    /** Shares of the gap to the wanted volume closed each tick: a loop swells in over a second or two and dies away a little quicker. */
    private const val FADE_IN = 0.045f
    private const val FADE_OUT = 0.06f

    /** Ticks a loop sits silent before it stops. */
    private const val STOP_AFTER = 50

    /** Ticks before a loop the engine would not start is tried again. */
    private const val RETRY_AFTER = 60L

    /** How far each is heard, blocks: the `attenuation_distance` of its sound. */
    private fun reach(kind: Station): Double = if (kind == Station.STEWPOT) 24.0 else 20.0

    private fun name(kind: Station): String = when (kind) {
        Station.STEWPOT -> "cook.simmer"
        Station.HOB -> "cook.sizzle"
        Station.OVEN -> "cook.oven_hum"
        Station.SPIT -> "cook.spit_creak"
    }

    /** One station's loop. */
    private class Loop(val key: Long, event: SoundEvent, x: Double, y: Double, z: Double) :
        AbstractTickableSoundInstance(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom()) {
        /** The volume it is easing toward (0 to 1, before the option's scale, which is applied each tick). */
        var target = 0f
        private var quiet = 0

        init {
            looping = true
            relative = false
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0f
            this.x = x
            this.y = y
            this.z = z
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val aim = target * StationFx.soundScale()
            volume += (aim - volume) * if (aim > volume) FADE_IN else FADE_OUT
            if (abs(aim - volume) < 0.002f) volume = aim
            quiet = if (volume <= 0.001f && aim <= 0.001f) quiet + 1 else 0
            if (quiet > STOP_AFTER) stop()
        }

        /** How loud it is now. */
        fun heard(): Float = volume

        fun halt() {
            stop()
        }
    }

    private class Want(val anim: StationAnim, val d2: Double)

    private val running = HashMap<Long, Loop>()
    private val retry = HashMap<Long, Long>()
    private val wanted = ArrayList<Want>()

    /** How many loops are audible now (running, and not faded to nothing). */
    fun audible(): Int = running.values.count { it.heard() > 0.02f && !it.isStopped }

    fun reset() {
        for (loop in running.values) loop.halt()
        running.clear()
        retry.clear()
        wanted.clear()
    }

    /** Each client tick: chooses which stations' loops run and how loud each wants to be. */
    fun tick(level: ClientLevel, anims: List<StationAnim>) {
        val mc = Minecraft.getInstance()
        running.values.removeIf { it.isStopped }
        val camera = mc.gameRenderer.mainCamera()
        val on = Cooking.enabled() && StationFx.soundScale() > 0.01f && camera.isInitialized
        wanted.clear()
        if (on) {
            val at = camera.position()
            for (anim in anims) {
                val kind = anim.kind ?: continue
                if (anim.loopLevel < 0.03f) continue
                val dx = at.x - (anim.pos.x + 0.5)
                val dy = at.y - (anim.pos.y + 0.5)
                val dz = at.z - (anim.pos.z + 0.5)
                var d2 = dx * dx + dy * dy + dz * dz
                val limit = reach(kind) + 1.0
                if (d2 > limit * limit) continue
                // The ones that are running have some grip on their place
                if (running.containsKey(anim.pos.asLong())) d2 *= 0.7
                wanted.add(Want(anim, d2))
            }
            if (wanted.size > 1) wanted.sortBy { it.d2 }
        }
        for (loop in running.values) loop.target = 0f
        var chosen = 0
        for (want in wanted) {
            if (chosen >= MAX) break
            chosen++
            val anim = want.anim
            val key = anim.pos.asLong()
            var loop = running[key]
            if (loop == null) {
                // Every place is taken while the old ones fade out
                if (running.size >= MAX) continue
                val last = retry[key]
                if (last != null && level.gameTime - last < RETRY_AFTER) continue
                loop = start(mc, level, anim) ?: continue
            }
            loop.target = anim.loopLevel.coerceIn(0f, 1f)
        }
        if (!on && running.isNotEmpty() && running.values.all { it.heard() <= 0.001f }) reset()
    }

    private fun start(mc: Minecraft, level: ClientLevel, anim: StationAnim): Loop? {
        val kind = anim.kind ?: return null
        val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name(kind))
        // An event with no entry in sounds.json is silent: nothing to start
        if (mc.soundManager.getSoundEvent(id) == null) {
            retry[anim.pos.asLong()] = level.gameTime
            return null
        }
        val event = try {
            CookSounds.get(name(kind))
        } catch (e: NoSuchElementException) {
            return null
        }
        val loop = Loop(anim.pos.asLong(), event, anim.pos.x + 0.5, anim.pos.y + 0.6, anim.pos.z + 0.5)
        if (mc.soundManager.play(loop) == SoundEngine.PlayResult.NOT_STARTED) {
            retry[anim.pos.asLong()] = level.gameTime
            return null
        }
        running[loop.key] = loop
        return loop
    }
}
