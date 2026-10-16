package io.github.vaspyyy.guildmark.client.island

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * A bed that goes round and round at one spot on an island (a lighthouse's turning lamp, a colony's clamour, a shrine's
 * drone), fading with distance as far as its sounds.json entry carries. It eases toward [target], and once it has sat
 * silent for a few seconds it stops, to be started afresh when it's wanted again.
 */
class SpotLoop(sound: SoundEvent, at: Vec3) : AbstractTickableSoundInstance(sound, SoundSource.AMBIENT, SoundInstance.createUnseededRandom()) {
    var target = 0f
    private var silentTicks = 0

    init {
        looping = true
        attenuation = SoundInstance.Attenuation.LINEAR
        volume = 0f
        x = at.x
        y = at.y
        z = at.z
    }

    override fun canStartSilent(): Boolean = true

    override fun tick() {
        volume += (target - volume) * if (target > volume) FADE_IN else FADE_OUT
        if (abs(target - volume) < 0.002f) volume = target
        silentTicks = if (volume <= 0.001f) silentTicks + 1 else 0
        if (silentTicks > STOP_AFTER) stop()
    }

    companion object {
        private const val FADE_IN = 0.03f
        private const val FADE_OUT = 0.05f
        private const val STOP_AFTER = 100
    }
}
