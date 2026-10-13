package io.github.vaspyyy.guildmark.client.ui.title

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.LevelLoadTracker
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.sounds.Music
import net.minecraft.sounds.Musics
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.event.SelectMusicEvent
import net.neoforged.neoforge.common.NeoForge
import java.lang.reflect.Field

/**
 * Puts Guildmark's menus in place: its title, loading and message screens as the vanilla ones open (and
 * the vanilla title screen back with the setting off), the painted dusk behind the other menus, and its
 * theme as the menu music. Guildmark Settings > Interface turns the screens and the music off apart.
 */
object TitleMenu {
    private val THEME by lazy { Music(ModSounds.MUSIC_MENU, 20, 600, true) }

    private val FADING = field(TitleScreen::class.java, "fading")
    private val TRACKER = field(LevelLoadingScreen::class.java, "loadTracker")
    private val REASON = field(LevelLoadingScreen::class.java, "reason")
    /** The message under the Mojang logo at startup, before Guildmark's pictures have loaded. */
    private const val STARTUP = "gui.loadingMinecraft"

    /** From joining a world until its loading screen shows: a loading screen then is an arrival, not a respawn. */
    private var joining = false

    /** Whether Guildmark's own menus are on. */
    fun enabled(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.TITLE_SCREEN.get()

    private fun music(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.MENU_MUSIC.get()

    fun register() {
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening::class.java, ::onOpening)
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Closing::class.java) { event: ScreenEvent.Closing ->
            if (event.screen is LevelLoadingScreen) joining = false
        }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn::class.java) { _ -> joining = true }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> joining = false }
        NeoForge.EVENT_BUS.addListener(SelectMusicEvent::class.java, ::onSelectMusic)
    }

    private fun onOpening(event: ScreenEvent.Opening) {
        GuildPanorama.install()
        val screen = event.newScreen ?: return
        val swapped = if (enabled()) dressed(screen) else undressed(screen)
        if (swapped != null) event.newScreen = swapped
    }

    /**
     * Guildmark's screen in place of [screen], or null to leave it be. Only the vanilla classes themselves:
     * a mod's own kind of title or loading screen keeps its look.
     */
    private fun dressed(screen: Screen): Screen? = when (screen.javaClass) {
        TitleScreen::class.java -> GuildTitleScreen(read(FADING, screen) == true)
        LevelLoadingScreen::class.java -> {
            val tracker = read(TRACKER, screen) as? LevelLoadTracker
            val reason = read(REASON, screen) as? LevelLoadingScreen.Reason
            if (tracker == null || reason != LevelLoadingScreen.Reason.OTHER) null
            else GuildLoadingScreen(tracker, reason, Minecraft.getInstance().level == null || joining).also { joining = false }
        }
        GenericMessageScreen::class.java -> if ((screen.title.contents as? TranslatableContents)?.key == STARTUP) null else GuildMessageScreen(screen.title)
        else -> null
    }

    /** The vanilla title screen back once Guildmark's menus are switched off. */
    private fun undressed(screen: Screen): Screen? = if (screen is GuildTitleScreen) TitleScreen() else null

    private fun onSelectMusic(event: SelectMusicEvent) {
        if (event.music === Musics.MENU && music()) event.music = THEME
    }

    private fun field(owner: Class<*>, name: String): Field? = try {
        owner.getDeclaredField(name).also { it.isAccessible = true }
    } catch (e: Exception) {
        Guildmark.LOGGER.warn("Can't read {}.{}, so that screen keeps its vanilla look", owner.simpleName, name, e)
        null
    }

    private fun read(field: Field?, from: Any): Any? = try {
        field?.get(from)
    } catch (e: ReflectiveOperationException) {
        null
    }
}
