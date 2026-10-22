package io.github.vaspyyy.guildmark.client.tales

import io.github.vaspyyy.guildmark.tales.TaleSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent

/** The client's half of TaleSounds: a sting played to the one at this client. Only call this when the level is client side. */
object TaleSoundsClient {
    /**
     * Plays [sound] as the UI's, at [volume] times the sound's own. A sound the pack has no file for (its sounds.json entry
     * is not there) is not an error: its stand-in plays instead, at the stand-in's own volume.
     */
    fun play(sound: TaleSounds.Sound, volume: Float, pitch: Float) {
        val sounds = Minecraft.getInstance().soundManager
        if (sounds.getSoundEvent(sound.id) != null) {
            sounds.play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(sound.id), pitch, sound.volume * volume))
        } else {
            sounds.play(SimpleSoundInstance.forUI(sound.standIn(), pitch, sound.standInVolume * volume))
        }
    }
}
