package io.github.vaspyyy.guildmark.client.festival

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.festival.FestivalDance
import io.github.vaspyyy.guildmark.festival.FestivalPhase
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.neoforged.neoforge.client.event.SelectMusicEvent
import kotlin.math.abs

/**
 * The band's music: a set is four phrases of eight bars and a finale of four (art/audio/make_festival_music.py), and
 * each phrase starts on the bar the server's dance is on, so the dancers step, turn and bow with it. It plays in the
 * square at full strength and dies away over the streets round it (unplaced, so it doesn't swing about as you turn),
 * and while you're in earshot of a festival the game's own music makes way for it. Someone coming in mid-phrase hears
 * the band from the next.
 */
object FestivalMusic {
    /** One phrase playing, its volume eased toward [target] each tick. */
    private class Phrase(sound: Identifier, val site: Long) :
        AbstractTickableSoundInstance(SoundEvent.createVariableRangeEvent(sound), SoundSource.RECORDS, SoundInstance.createUnseededRandom()) {
        var target = 0f

        init {
            looping = false
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
            volume = 0f
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            volume += (target - volume) * 0.15f
            if (abs(target - volume) < 0.002f) volume = target
        }
    }

    private val playing = ArrayList<Phrase>()
    /** The set the phrases started belong to (its start tick), and the last phrase started in it. */
    private var setStart = FestivalClient.NO_SET
    private var started = -1

    /** Fading the game's music out for the band's. */
    private var fadeLeft = 0
    private var fadeFrom = 1f

    /** Where each phrase starts in a set (ticks), the finale last. */
    private val STARTS = IntArray(5) { it * FestivalDance.PHRASE_BARS * FestivalDance.BAR_TICKS }
    private const val SET_TICKS = FestivalDance.SET_BARS * FestivalDance.BAR_TICKS
    /** A phrase begun more than this late is waited out instead. */
    private const val LATE = 8
    private const val FADE = 50

    private fun gain(): Float = if (!GuildmarkClientConfig.SPEC.isLoaded) 1f else GuildmarkClientConfig.FESTIVAL_MUSIC.get() / 100f

    /** How loud a festival's music is where the player is: full in its square, nothing a street or two off. */
    fun volumeAt(site: FestivalClient.Site, x: Double, y: Double, z: Double): Float {
        val full = maxOf(16.0, site.square * 0.75)
        val gone = full + 48.0
        val d = site.distance(x, z)
        val far = ((gone - d) / (gone - full)).coerceIn(0.0, 1.0)
        val high = ((40.0 - abs(y - site.y)) / 16.0).coerceIn(0.0, 1.0)
        val t = far * high
        return (t * t * (3 - 2 * t)).toFloat()
    }

    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        val site = FestivalClient.nearest(player.x, player.z, 64.0)
        val volume = if (site == null) 0f else volumeAt(site, player.x, player.y, player.z) * gain()
        playing.removeIf { it.isStopped }
        for (phrase in playing) phrase.target = if (phrase.site == site?.key) volume else 0f
        fade(minecraft)
        if (site == null) return
        val age = site.setAge()
        if (age < 0 || age >= SET_TICKS) {
            started = -1
            return
        }
        if (site.setStart != setStart) {
            setStart = site.setStart
            started = -1
        }
        val n = STARTS.indexOfLast { age >= it }
        if (n <= started) return
        started = n
        if (age - STARTS[n] > LATE || volume <= 0.001f) return
        val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "festival.${site.kind.id}.${if (n == 4) "finale" else n.toString()}")
        val phrase = Phrase(id, site.key)
        phrase.target = volume
        minecraft.soundManager.play(phrase)
        playing.add(phrase)
        // The game's own music makes way
        if (n == 0 && fadeLeft == 0 && minecraft.musicManager.currentMusicTranslationKey != null) {
            fadeLeft = FADE
            fadeFrom = minecraft.musicVolume
        }
    }

    private fun fade(minecraft: Minecraft) {
        if (fadeLeft <= 0) return
        fadeLeft--
        if (fadeLeft == 0) {
            minecraft.musicManager.stopPlaying()
            minecraft.soundManager.updateCategoryVolume(SoundSource.MUSIC, minecraft.musicVolume)
        } else {
            minecraft.soundManager.updateCategoryVolume(SoundSource.MUSIC, fadeFrom * fadeLeft / FADE)
        }
    }

    /** No new music from the game while a festival's going on round you. */
    fun onSelectMusic(event: SelectMusicEvent) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (gain() <= 0f) return
        val site = FestivalClient.nearest(player.x, player.z, 32.0) ?: return
        if (site.phase.ordinal > FestivalPhase.DUSK.ordinal || site.phase == FestivalPhase.SETUP) return
        if (volumeAt(site, player.x, player.y, player.z) < 0.05f) return
        if (minecraft.gui.screen()?.backgroundMusic != null) return
        event.overrideMusic(null)
    }

    fun reset() {
        for (phrase in playing) Minecraft.getInstance().soundManager.stop(phrase)
        playing.clear()
        started = -1
        setStart = FestivalClient.NO_SET
        if (fadeLeft > 0) {
            fadeLeft = 0
            val minecraft = Minecraft.getInstance()
            minecraft.soundManager.updateCategoryVolume(SoundSource.MUSIC, minecraft.musicVolume)
        }
    }
}
