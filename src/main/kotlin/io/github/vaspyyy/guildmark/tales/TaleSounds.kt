package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.tales.TaleSoundsClient
import io.github.vaspyyy.guildmark.homestead.HomesteadRegistry
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3

/**
 * The tales' seven sounds, and the one place that names them (art/audio/make_tale_sfx.py makes the files; the sound is
 * `guildmark:townsfolk.tale.<name>`, `sounds/townsfolk/tale/<name>1.ogg` and on, its subtitle
 * `subtitles.guildmark.townsfolk.tale.<name>`).
 *
 * Four are the banner's stings, played to the one who has the tale, by the client: [ui]. Three happen in the street, played
 * where the thing is done, by the server: [at]. Each has a stand-in, the sound that played before its files were made.
 * The client asks its own sound manager whether a sting's sound is in the pack and plays the stand-in if it is not (as
 * TalkScreen asks for a voice); a server cannot ask a client's pack, so the street's sounds follow [OWN], the ones
 * whose files are merged, and a sound taken out of it plays as its stand-in again with nothing else changed.
 */
object TaleSounds {
    /**
     * A tale's sound: [event] is its name under `townsfolk.` in sounds.json; [volume] the volume its files play at (they
     * are levelled in the file, so 1 unless a sound is meant to sit under the rest); [standIn] and [standInVolume] what
     * plays when its files are not there.
     */
    enum class Sound(val event: String, val volume: Float, private val stand: () -> SoundEvent, val standInVolume: Float) {
        /** a quill's scratch, two soft bell notes a fifth apart, a breath of strings: the banner coming up (client) */
        BEGIN("tale.begin", 1f, { ModSounds.UI_PAGE.get() }, 1f),

        /** one soft bell note and a page turned: a step done (client) */
        STEP("tale.step", 0.6f, { ModSounds.UI_PAGE.get() }, 0.5f),

        /** three bell notes rising to the home note, a warm held chord, a seal pressed into wax (client) */
        END_GOOD("tale.end_good", 1f, { ModSounds.UI_SEAL.get() }, 1f),

        /** one low muted bell, a second note falling a minor third under it, a hush (client) */
        END_ILL("tale.end_ill", 1f, { ModSounds.UI_DEATH_TOLL.get() }, 0.6f),

        /** a paper catching: a short rasp, a soft whump, a thin crackle of it curling, a last settling (the street) */
        NOTE_BURN("tale.note_burn", 1f, { SoundEvents.FIRE_AMBIENT }, 1f),

        /** a match struck, the wick taking with a soft "fp", a warm low hum (the street) */
        CANDLE_LIGHT("tale.candle_light", 1f, { SoundEvents.FLINTANDSTEEL_USE }, 0.5f),

        /** a mended wheel dropped onto its axle: a creak, a wooden thunk, the cart settling (the street) */
        CART_MEND("tale.cart_mend", 1f, { HomesteadRegistry.HAMMER.get() }, 1f);

        /** `guildmark:townsfolk.tale.<name>`, the sound's id in sounds.json */
        val id: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.$event")

        /** the sound that plays in its place until its files are made */
        fun standIn(): SoundEvent = stand()
    }

    /** The sounds whose files are merged into sounds.json: all seven. Take one out and it plays as its stand-in. */
    private val OWN: Set<Sound> = Sound.entries.toSet()

    /** Each sound's own event, a direct holder with no registry entry, as the crier's and the lamplighters' are */
    private val HOLDERS: Map<Sound, Holder<SoundEvent>> =
        Sound.entries.associateWith { Holder.direct(SoundEvent.createVariableRangeEvent(it.id)) }

    /**
     * Plays [sound] to the one at this client, as the UI's, with [volume] as a multiple of its own. Client side only (it
     * reaches into the client's sound manager): call it where the banner is drawn, never from the server.
     */
    fun ui(sound: Sound, volume: Float = 1f, pitch: Float = 1f) {
        TaleSoundsClient.play(sound, volume, pitch)
    }

    /**
     * Plays [sound] where it happens, to every player near enough to hear it, with a little variation in its pitch so two
     * of the same in a street are not the same. [volume] is a multiple of the sound's own. A sound that is not in [OWN]
     * plays as its stand-in.
     */
    fun at(level: ServerLevel, pos: Vec3, sound: Sound, volume: Float = 1f) {
        val pitch = 0.96f + level.random.nextFloat() * 0.08f
        if (sound in OWN) {
            level.playSound(null, pos.x, pos.y, pos.z, HOLDERS.getValue(sound), SoundSource.NEUTRAL, sound.volume * volume, pitch)
        } else {
            level.playSound(null, pos.x, pos.y, pos.z, sound.standIn(), SoundSource.NEUTRAL, sound.standInVolume * volume, pitch)
        }
    }

    /** As [at], from the middle of a block */
    fun at(level: ServerLevel, pos: BlockPos, sound: Sound, volume: Float = 1f) {
        at(level, Vec3.atCenterOf(pos), sound, volume)
    }
}
