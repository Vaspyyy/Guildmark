package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent

/**
 * Hearthcraft Sounds (the `cookSounds` option) for the sounds a station makes when it is worked (living-world/cook/design.md 11.2): the
 * plop of a thing put in, the knock of the knife, the chime of a call, the flare of a fire taking. The server plays those, the same for
 * everyone in range, so all the client can do is turn them down as they arrive: a one-shot `guildmark:cook.*` sound on the Blocks
 * channel is played at the option's share of its volume (the option is 0 to 100), or not at all when the option is 0. At 100, the
 * default, this does nothing.
 *
 * What it leaves alone: a loop (it sets its own volume each tick, [CookLoops]); the other channels (a diner's spoon is on the
 * Players channel and the town kitchens' sounds on the Friendly Creatures one, and those play and scale themselves, [TownKitchen.play]);
 * every sound that is not Hearthcraft's own.
 */
object CookSoundGate {
    /** A sound heard again at a share of its volume; the gate does not turn one of these down a second time. */
    private class Quieter(sound: SoundInstance, share: Float) : SimpleSoundInstance(
        sound.identifier, sound.source, sound.volume * share, sound.pitch, SoundInstance.createUnseededRandom(),
        false, sound.delay, sound.attenuation, sound.x, sound.y, sound.z, sound.isRelative,
    )

    /** A sound is about to play: quietens or silences it if it is one of the stations' one-shots. */
    fun onPlay(event: PlaySoundEvent) {
        val sound = event.sound ?: return
        if (sound.isLooping || sound.source != SoundSource.BLOCKS || sound is Quieter) return
        val id = sound.identifier
        if (id.namespace != Guildmark.MOD_ID || !id.path.startsWith("cook.")) return
        val share = StationFx.soundScale()
        if (share >= 0.99f) return
        event.sound = if (share <= 0.01f) null else Quieter(sound, share)
    }
}
