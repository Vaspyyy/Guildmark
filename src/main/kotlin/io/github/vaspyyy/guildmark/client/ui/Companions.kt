package io.github.vaspyyy.guildmark.client.ui

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.gui.screens.Screen
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The mods Guildmark plays well with, as its settings show them. None is needed: each adds to what
 * Guildmark does while it's there. [devKey] is the switch build.gradle reads for the dev runs (see [DevRuns]).
 */
enum class CompanionMod(val id: String, val modId: String, val devKey: String?, page: String) {
    DISTANT_HORIZONS("distant_horizons", "distanthorizons", "distant_horizons", "https://modrinth.com/mod/distanthorizons"),
    SEEDGEN("seedgen", "seedrenderer", null, "https://modrinth.com/mod/distant-horizons-seedgen"),
    TECTONIC("tectonic", "tectonic", "tectonic", "https://modrinth.com/mod/tectonic"),
    SODIUM("sodium", "sodium", "sodium", "https://modrinth.com/mod/sodium");

    val page: URI = URI.create(page)

    val loaded: Boolean get() = ModList.get().isLoaded(modId)

    /** Whether the mod brings a settings screen of its own. */
    fun hasSettings(): Boolean {
        if (this == SEEDGEN) return SeedGen.hasScreen()
        val container = ModList.get().getModContainerById(modId).orElse(null) ?: return false
        return IConfigScreenFactory.getForMod(container.modInfo).isPresent
    }

    /** The mod's own settings screen, returning to [parent] when it closes. */
    fun settings(parent: Screen): Screen? {
        if (this == SEEDGEN) return SeedGen.screen(parent)
        val container = ModList.get().getModContainerById(modId).orElse(null) ?: return null
        val factory = IConfigScreenFactory.getForMod(container.modInfo).orElse(null) ?: return null
        return try {
            factory.createScreen(container, parent)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("Could not open {}'s settings", modId, e)
            null
        }
    }
}

/**
 * SeedGen's master switch and its settings screen, which it only offers from inside Distant Horizons'
 * own settings. Guildmark doesn't build against SeedGen, so both are reached by name; should a later
 * SeedGen move them, the switch and the button just don't show.
 */
object SeedGen {
    private const val CONFIG = "mobius.seedrenderer.config.SeedGenConfig"
    private const val SCREEN = "mobius.seedrenderer.config.SeedGenConfigScreen"

    private fun type(name: String): Class<*>? =
        if (!CompanionMod.SEEDGEN.loaded) null
        else try { Class.forName(name, true, SeedGen::class.java.classLoader) } catch (e: ReflectiveOperationException) { null } catch (e: LinkageError) { null }

    private val settings: Method? by lazy { try { type(CONFIG)?.getMethod("get") } catch (e: ReflectiveOperationException) { null } }
    private val switch: Field? by lazy { try { type(CONFIG)?.getField("enabled") } catch (e: ReflectiveOperationException) { null } }
    private val screenType: Class<*>? by lazy { type(SCREEN) }

    /** SeedGen's settings as it holds them (the same object its own screen changes). */
    private fun config(): Any? = try { settings?.invoke(null) } catch (e: ReflectiveOperationException) { null }

    /** Whether SeedGen draws the far land, or null if that can't be read. */
    fun enabled(): Boolean? {
        val config = config() ?: return null
        return try { switch?.getBoolean(config) } catch (e: ReflectiveOperationException) { null }
    }

    /** Turns SeedGen on or off and saves its settings, as its own screen does; it takes effect the next time a world is entered. */
    fun setEnabled(on: Boolean): Boolean {
        val config = config() ?: return false
        val switch = switch ?: return false
        return try {
            switch.setBoolean(config, on)
            // Its own switch does the same: drawing past Distant Horizons' limit only works while SeedGen is on
            if (!on) try { config.javaClass.getField("extendedDistance").setBoolean(config, false) } catch (e: ReflectiveOperationException) { }
            config.javaClass.getMethod("save").invoke(config)
            true
        } catch (e: ReflectiveOperationException) {
            Guildmark.LOGGER.warn("Could not change SeedGen's switch", e)
            false
        }
    }

    fun hasScreen(): Boolean = screenType != null

    fun screen(parent: Screen): Screen? = try {
        screenType?.getConstructor(Screen::class.java)?.newInstance(parent) as? Screen
    } catch (e: ReflectiveOperationException) {
        Guildmark.LOGGER.warn("Could not open SeedGen's settings", e)
        null
    }
}

/**
 * Which companions the dev runs load when the game next starts. build.gradle reads the file this
 * keeps (run/config/guildmark/dev-runs.properties) whenever the game is started from Gradle, and
 * `-Pwith_<name>=true|false` on the command line still wins over it. Only offered outside a release.
 */
object DevRuns {
    val offered: Boolean = !FMLEnvironment.isProduction()

    private val file: Path by lazy { FMLPaths.CONFIGDIR.get().resolve(Guildmark.MOD_ID).resolve("dev-runs.properties") }
    private var choices: Properties? = null

    private fun choices(): Properties = choices ?: Properties().also { read ->
        try {
            if (Files.exists(file)) Files.newBufferedReader(file).use { read.load(it) }
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Could not read {}", file, e)
        }
        choices = read
    }

    /** Whether the next dev run loads [mod]: as chosen here, or as this run does when nothing's been chosen. */
    fun chosen(mod: CompanionMod): Boolean {
        val key = mod.devKey ?: return mod.loaded
        return choices().getProperty(key)?.trim()?.let { it != "false" } ?: mod.loaded
    }

    /** True when the next run will load [mod] and this one hasn't, or the other way round. */
    fun pending(mod: CompanionMod): Boolean = offered && mod.devKey != null && chosen(mod) != mod.loaded

    fun choose(mod: CompanionMod, load: Boolean) {
        val key = mod.devKey ?: return
        val chosen = choices()
        // Every switch is written down, so the file reads as what the next run will be
        for (other in CompanionMod.entries) other.devKey?.let { if (chosen.getProperty(it) == null) chosen.setProperty(it, other.loaded.toString()) }
        chosen.setProperty(key, load.toString())
        try {
            Files.createDirectories(file.parent)
            val lines = arrayListOf(
                "# The companion mods Guildmark's dev runs load, as chosen in Guildmark Settings > Companions.",
                "# build.gradle reads this whenever the game starts from Gradle; -Pwith_<name>=true|false still wins.",
            )
            for (name in chosen.stringPropertyNames().sorted()) lines.add("$name=${chosen.getProperty(name)}")
            Files.write(file, lines)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Could not write {}", file, e)
        }
    }
}
