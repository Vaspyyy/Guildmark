package io.github.vaspyyy.guildmark.client.ambience

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import kotlin.math.abs

/**
 * A bed that goes round and round under the city while it's wanted (a stereo loop, so unpositioned). It eases
 * toward [target], and once it has sat silent for a few seconds it stops, to be started afresh when it's
 * wanted again.
 */
class AmbienceLoop(sound: Identifier) :
    AbstractTickableSoundInstance(SoundEvent.createVariableRangeEvent(sound), SoundSource.AMBIENT, SoundInstance.createUnseededRandom()) {
    /** The volume to ease toward, set each tick by the ambience. */
    var target = 0f
    private var silentTicks = 0

    init {
        looping = true
        relative = true
        attenuation = SoundInstance.Attenuation.NONE
        volume = 0f
    }

    // It starts from nothing and fades in
    override fun canStartSilent(): Boolean = true

    override fun tick() {
        volume += (target - volume) * if (target > volume) FADE_IN else FADE_OUT
        if (abs(target - volume) < 0.002f) volume = target
        silentTicks = if (volume <= 0.001f) silentTicks + 1 else 0
        if (silentTicks > STOP_AFTER) stop()
    }

    companion object {
        /** Shares of the gap closed each tick: a bed swells in over a few seconds and dies away a little quicker. */
        private const val FADE_IN = 0.025f
        private const val FADE_OUT = 0.04f
        private const val STOP_AFTER = 100
    }
}
