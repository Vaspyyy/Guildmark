package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3

/**
 * The village's own sounds (living-world/villages/design.md 17.3, Part V12b): seven events `guildmark:village.<name>`, made by
 * `art/audio/make_village_audio.py` and listed in `sounds.json` through its fragment `art/audio/villages_sounds.json` (merged
 * by `art/lang/merge_lang.py`). Each is a direct holder with no registry entry, registered with its reach as a fixed-range
 * event (as the streets' and the topping out's are), so the server sends it only that far whatever its volume; its files'
 * `attenuation_distance` in `sounds.json` is the same reach, and `make_village_audio.py --audit` checks that the two agree.
 *
 * Who plays them: the rooster [VillageVisits], once at first light in a village a player is in. The others belong to the
 * moments of 16.1 that the later parts wire: BOLT_DOOR's `bolt` ([DOOR_BOLT]) and the shelter's whispering ([HUSH], a loop)
 * in a raid, LAY_CANDLE's `set` ([CANDLE_LAY]), CRADLE's `coo` ([BABY]), the village's cheers ([CHEER]) and the pedlar's
 * bell ([PEDLAR_BELL]); each sounds as soon as something calls [play] with it.
 */
object VillageSounds {
    /** An iron bolt shot home along its keeper and the door's clack (BOLT_DOOR's `bolt`, tick 30). */
    val DOOR_BOLT: Holder<SoundEvent> = sound("village.door_bolt", 16f)

    /** People whispering behind a bolted door: a loop of 6.6 or 7.6 seconds, faded in and out as the bread queue's is. */
    val HUSH: Holder<SoundEvent> = sound("village.hush", 12f)

    /** A village's cheer, 6 to 12 voices with children among them, and clapping. */
    val CHEER: Holder<SoundEvent> = sound("village.cheer", 32f)

    /** A candle set on the memorial stone, a match struck and the wick catching (LAY_CANDLE's `set`, tick 24). */
    val CANDLE_LAY: Holder<SoundEvent> = sound("village.candle_lay", 16f)

    /** The pedlar's small brass handbell, brighter than the works' and the crew's. */
    val PEDLAR_BELL: Holder<SoundEvent> = sound("village.pedlar_bell", 40f)

    /** A baby cooing in the crook of an arm (CRADLE's `coo`, ticks 16 and 46). */
    val BABY: Holder<SoundEvent> = sound("village.baby", 12f)

    /** A rooster crowing from a coop at first light. */
    val ROOSTER: Holder<SoundEvent> = sound("village.rooster", 56f)

    private fun sound(name: String, reach: Float): Holder<SoundEvent> =
        Holder.direct(SoundEvent.createFixedRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name), reach))

    /**
     * Plays [sound] at [at] for every player within its reach, at [volume] (its files are levelled, so 1 unless it is meant to sit
     * under the rest) with the moments' pitch jitter of 0.95 to 1.05, so two of the same in a lane are not the same.
     */
    fun play(level: ServerLevel, at: Vec3, sound: Holder<SoundEvent>, volume: Float = 1f) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume, 0.95f + level.random.nextFloat() * 0.1f)
    }
}
