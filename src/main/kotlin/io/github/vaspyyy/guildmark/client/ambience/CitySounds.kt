package io.github.vaspyyy.guildmark.client.ambience

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3

/**
 * The city's sounds: the ambience.* entries in assets/guildmark/sounds.json, made by
 * art/audio/make_ambience.py. Only this player's client plays them, so they're played by name rather than
 * registered.
 */
object CitySounds {
    private fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ambience.$name")

    // Beds, played unpositioned under everything
    val CROWD_DAY = id("crowd_day")
    val CROWD_LIGHT = id("crowd_light")
    val HARBOUR_WATER = id("harbour_water")
    val WIND_WALLS = id("wind_walls")

    // Each bell struck full and struck softer, one file each: a run of strokes keeps to one of them
    val BELL_GREAT = id("bell_great")
    val BELL_GREAT_SOFT = id("bell_great_soft")
    val BELL_TOWN = id("bell_town")
    val BELL_TOWN_SOFT = id("bell_town_soft")
    val BELL_CHIME = id("bell_chime")
    val SHIP_BELL = id("ship_bell")

    val ANVIL_WORK = id("anvil_work")
    val BELLOWS = id("bellows")
    val QUENCH = id("quench")
    val GRINDSTONE = id("grindstone")
    val SAW = id("saw")
    val WOOD_KNOCK = id("wood_knock")

    val HAWKER = id("hawker")
    val COINS = id("coins")
    val CART = id("cart")

    val SPLASH = id("splash")
    val GULL = id("gull")
    val CREAK = id("creak")

    val GUST = id("gust")
    val FLAG = id("flag")

    /** The night watch calling the hour, from ten at night to five in the morning (by the 24-hour clock). */
    val WATCH: Map<Int, Identifier> = mapOf(
        22 to id("watch_ten"), 23 to id("watch_eleven"), 0 to id("watch_midnight"), 1 to id("watch_one"),
        2 to id("watch_two"), 3 to id("watch_three"), 4 to id("watch_four"), 5 to id("watch_five"),
    )

    /** Plays [sound] once at [at], fading out with distance as far as its sounds.json entry carries. */
    fun play(sound: Identifier, at: Vec3, volume: Float, pitch: Float = 1f): SoundInstance? {
        if (volume < 0.01f) return null
        val instance = SimpleSoundInstance(
            sound, SoundSource.AMBIENT, volume, pitch, SoundInstance.createUnseededRandom(),
            false, 0, SoundInstance.Attenuation.LINEAR, at.x, at.y, at.z, false,
        )
        Minecraft.getInstance().soundManager.play(instance)
        return instance
    }
}
