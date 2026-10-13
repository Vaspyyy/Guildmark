package io.github.vaspyyy.guildmark.client.region

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.region.RegionData
import io.github.vaspyyy.guildmark.region.RegionKind
import net.minecraft.client.Minecraft
import net.minecraft.sounds.Music
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.neoforged.neoforge.client.event.SelectMusicEvent

/**
 * Each land's own theme, in place of the usual music. The game still chooses when music plays: a theme
 * waits out whatever is already playing, then comes in its turn, a little sooner just after you've come
 * into a new land. Crossing from one land with a theme into another fades the old theme out so the new
 * one can follow. A boss's music, the underwater music and any other mod's choice are left alone.
 */
object RegionMusic {
    /** The usual quiet between tracks (ticks), and how soon a new land's theme comes on after arriving. */
    private const val MIN_DELAY = 2400
    private const val MAX_DELAY = 7200
    private const val QUICK_MIN = 20
    private const val QUICK_MAX = 200
    /** How long after arriving in a land its theme comes on quickly (ticks). */
    private const val QUICK_FOR = 20 * 90
    /** Ticks to fade a theme out on leaving its land. */
    private const val FADE = 50

    private var ticks = 0L
    private var arrived = Long.MIN_VALUE / 2
    private var fadeLeft = 0
    private var fadeFrom = 1f

    private val usual = HashMap<RegionKind, Music>()
    private val quick = HashMap<RegionKind, Music>()

    private fun enabled(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.REGION_MUSIC.get()

    private fun music(kind: RegionKind, soon: Boolean): Music? {
        val theme = RegionData.THEMES[kind] ?: return null
        return if (soon) quick.getOrPut(kind) { Music(theme, QUICK_MIN, QUICK_MAX, false) }
            else usual.getOrPut(kind) { Music(theme, MIN_DELAY, MAX_DELAY, false) }
    }

    /** The player went from a [before] land into a [now] one (either null for none). */
    fun entered(before: RegionKind?, now: RegionKind?) {
        val old = before?.takeIf { it.theme }
        val new = now?.takeIf { it.theme }
        if (old == new) return
        if (new != null) arrived = ticks
        // The old land's theme makes way for the new one's
        val minecraft = Minecraft.getInstance()
        val playing = old?.let { music(it, false) }
        if (new != null && playing != null && enabled() && fadeLeft == 0 && minecraft.musicManager.isPlayingMusic(playing)) {
            fadeLeft = FADE
            fadeFrom = minecraft.musicVolume
        }
    }

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
        // A boss's music (or its silence) has been chosen, or another mod has had its say
        if (event.isCanceled || event.music !== event.originalMusic) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (!enabled() || player.level().dimension() != Level.OVERWORLD || player.isUnderWater) return
        if (minecraft.gui.screen()?.backgroundMusic != null) return
        val kind = RegionClient.current?.kind?.takeIf { it.theme } ?: return
        val music = music(kind, ticks - arrived < QUICK_FOR) ?: return
        event.music = music
    }
}
