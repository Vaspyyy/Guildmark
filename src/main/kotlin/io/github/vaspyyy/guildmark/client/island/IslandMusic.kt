package io.github.vaspyyy.guildmark.client.island

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.island.IslandRegistry
import net.minecraft.client.Minecraft
import net.minecraft.sounds.Music
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.neoforged.neoforge.client.event.SelectMusicEvent

/**
 * The isles' own theme, in an island's waters, in place of the sea's (or any other land's). As with the regions' themes
 * the game still chooses when music plays: coming into an island's waters fades out whatever theme is playing so the
 * isles' can follow a little sooner than usual, and leaving them fades the isles' theme out for the sea's. A boss's
 * music, the underwater music and any other mod's choice are left alone.
 */
object IslandMusic {
    private const val MIN_DELAY = 2400
    private const val MAX_DELAY = 6000
    private const val QUICK_MIN = 40
    private const val QUICK_MAX = 240
    /** How long after arriving its theme comes on quickly (ticks). */
    private const val QUICK_FOR = 20 * 90
    /** Ticks to fade the old theme out. */
    private const val FADE = 50

    private var ticks = 0L
    private var arrived = Long.MIN_VALUE / 2
    private var fadeLeft = 0
    private var fadeFrom = 1f

    private val usual by lazy { Music(IslandRegistry.MUSIC, MIN_DELAY, MAX_DELAY, false) }
    private val quick by lazy { Music(IslandRegistry.MUSIC, QUICK_MIN, QUICK_MAX, false) }

    private fun enabled(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.REGION_MUSIC.get()

    /** The player came into an island's waters ([at]), or left them. */
    fun changed(at: Boolean) {
        if (at) arrived = ticks
        if (!enabled()) return
        val minecraft = Minecraft.getInstance()
        val manager = minecraft.musicManager
        // Whatever was playing makes way: the sea's theme for the isles' on arriving, the isles' for the sea's on leaving
        val playing = if (at) !manager.isPlayingMusic(usual) && !manager.isPlayingMusic(quick) && playingAny() else manager.isPlayingMusic(usual) || manager.isPlayingMusic(quick)
        if (playing && fadeLeft == 0) {
            fadeLeft = FADE
            fadeFrom = minecraft.musicVolume
        }
    }

    /** Whether any music is playing just now (the music manager has a track going). */
    private fun playingAny(): Boolean = Minecraft.getInstance().musicManager.currentMusicTranslationKey != null

    fun tick() {
        ticks++
        if (fadeLeft <= 0) return
        val minecraft = Minecraft.getInstance()
        fadeLeft--
        if (fadeLeft == 0) {
            minecraft.musicManager.stopPlaying()
            minecraft.soundManager.updateCategoryVolume(SoundSource.MUSIC, minecraft.musicVolume)
        } else {
            minecraft.soundManager.updateCategoryVolume(SoundSource.MUSIC, fadeFrom * fadeLeft / FADE)
        }
    }

    fun reset() {
        if (fadeLeft > 0) {
            fadeLeft = 0
            val minecraft = Minecraft.getInstance()
            minecraft.soundManager.updateCategoryVolume(SoundSource.MUSIC, minecraft.musicVolume)
        }
        arrived = Long.MIN_VALUE / 2
    }

    fun onSelectMusic(event: SelectMusicEvent) {
        if (event.music !== event.originalMusic) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (!enabled() || IslandClient.current == null || player.level().dimension() != Level.OVERWORLD || player.isUnderWater) return
        if (minecraft.gui.screen()?.backgroundMusic != null) return
        event.music = if (ticks - arrived < QUICK_FOR) quick else usual
    }
}
