package io.github.vaspyyy.guildmark.client.shader

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.settings.KeyModifier
import net.neoforged.neoforge.common.NeoForge
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Optional
import java.util.TreeMap
import kotlin.io.path.isRegularFile

/**
 * Guildmark's own shader pack: a dark fantasy look of muted colour, deep shadows, heavy mist and strong shafts of
 * light. It ships inside the jar under shaderpack/Guildmark and is copied out to the game's shaderpacks folder
 * whenever a mod that runs packs from there is loaded, and again whenever the shipped pack changes, so the shader
 * mod lists it as "Guildmark".
 */
object GuildmarkShaders {
    const val PACK = "Guildmark"
    private const val ROOT = "shaderpack/$PACK/"
    /** Written into the copy: which shipped pack it holds, so an unchanged one isn't written again. */
    private const val STAMP = "guildmark-pack.txt"

    /** Mods that run OptiFine-style packs out of the shaderpacks folder: Iris on OpenGL, Vitrail on Vulkan. */
    private val SHADER_MODS = listOf("iris", "vitrail")

    val shaderModLoaded: Boolean get() = SHADER_MODS.any { ModList.get().isLoaded(it) }

    /** What install() did, for Iris to act on once the game has finished loading. */
    private enum class Outcome { UNCHANGED, FIRST, UPDATED }
    private var outcome = Outcome.UNCHANGED
    private var settled = false

    /** Keys an Iris binding moves to when it sits on one of Guildmark's: the first that nothing else uses. */
    private val SPARE_KEYS = intArrayOf(InputConstants.KEY_J, InputConstants.KEY_U, InputConstants.KEY_Y, InputConstants.KEY_Z)

    fun register() {
        if (!shaderModLoaded) return
        outcome = install()
        if (ModList.get().isLoaded("iris")) {
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> settle() }
        }
    }

    private fun install(): Outcome {
        val contents = ModList.get().getModFileById(Guildmark.MOD_ID)?.file?.contents ?: return Outcome.UNCHANGED
        val files = TreeMap<String, ByteArray>()
        try {
            contents.visitContent(ROOT) { path, resource ->
                val name = path.removePrefix(ROOT)
                if (name.isNotEmpty() && !name.endsWith("/")) files[name] = resource.readAllBytes()
            }
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Could not read Guildmark's shader pack from the jar", e)
            return Outcome.UNCHANGED
        }
        if (files.isEmpty()) return Outcome.UNCHANGED

        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, bytes) in files) {
            digest.update(name.toByteArray())
            digest.update(0)
            digest.update(bytes)
        }
        val stamp = digest.digest().joinToString("") { "%02x".format(it) }

        val target: Path = FMLPaths.GAMEDIR.get().resolve("shaderpacks").resolve(PACK)
        val stampFile = target.resolve(STAMP)
        val previous = try {
            if (stampFile.isRegularFile()) Files.readString(stampFile).trim() else null
        } catch (_: Exception) {
            null
        }
        if (previous == stamp && files.keys.all { target.resolve(it).isRegularFile() }) return Outcome.UNCHANGED

        try {
            // Files an older Guildmark shipped and this one doesn't would still be read by the shader mod
            if (previous != null && Files.isDirectory(target)) {
                Files.walk(target).use { walk ->
                    walk.filter { Files.isRegularFile(it) }.toList().forEach { file ->
                        val name = target.relativize(file).toString().replace('\\', '/')
                        if (name != STAMP && name !in files) Files.deleteIfExists(file)
                    }
                }
            }
            for ((name, bytes) in files) {
                val file = target.resolve(name)
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
            Files.writeString(stampFile, stamp + System.lineSeparator())
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Could not copy Guildmark's shader pack to {}", target, e)
            return Outcome.UNCHANGED
        }
        Guildmark.LOGGER.info("Copied Guildmark's shader pack to {}", target)
        return if (previous == null) Outcome.FIRST else Outcome.UPDATED
    }

    /**
     * Once the game is up: Iris's keys are moved off Guildmark's, a first copy is chosen in Iris when no pack has
     * been chosen yet there, and a pack Iris is running that this launch rewrote is reloaded, since Iris may have
     * read the old files first.
     */
    private fun settle() {
        val mc = Minecraft.getInstance()
        if (settled || !mc.isGameLoadFinished) return
        settled = true
        moveIrisKeys(mc)
        if (outcome == Outcome.UNCHANGED) return
        val name = Iris.packName()
        when {
            outcome == Outcome.FIRST && name == null -> Iris.choose(PACK)
            name == PACK && Iris.enabled() -> Iris.reload()
        }
    }

    /**
     * Iris binds R to reload the pack and K to turn shaders on and off, which are Guildmark's weapon skill and
     * character sheet, and a press reaches every binding on its key: a weapon skill would also reload the pack and
     * hang the game mid-fight. So an Iris binding that shares a key with one of Guildmark's moves off it, the reload
     * to no key at all and the others to a spare key.
     */
    private fun moveIrisKeys(mc: Minecraft) {
        val mappings = mc.options.keyMappings
        val ours = mappings.filter { it.name.startsWith("key.${Guildmark.MOD_ID}.") && !it.isUnbound }
        var moved = false
        for (theirs in mappings) {
            if (!theirs.name.startsWith("iris.") || theirs.isUnbound) continue
            val clash = ours.firstOrNull { theirs.same(it) } ?: continue
            val to = if (theirs.name.endsWith(".reload")) {
                InputConstants.UNKNOWN
            } else {
                SPARE_KEYS.map { InputConstants.Type.KEYBOARD.getOrCreate(it) }
                    .firstOrNull { key -> mappings.none { it.matches(key) } } ?: InputConstants.UNKNOWN
            }
            Guildmark.LOGGER.info(
                "Moved Iris's {} off {}, which is Guildmark's {}, to {}",
                theirs.name, theirs.translatedKeyMessage.string, clash.name, to.displayName.string,
            )
            theirs.setKeyModifierAndCode(KeyModifier.NONE, to)
            moved = true
        }
        if (moved) {
            KeyMapping.resetMapping()
            mc.options.save()
        }
    }

    /** Iris's own settings, reached by name: it has no API for choosing a pack, and Guildmark doesn't need it to build. */
    private object Iris {
        private val iris: Class<*>? by lazy {
            try {
                Class.forName("net.irisshaders.iris.Iris")
            } catch (_: Throwable) {
                null
            }
        }

        private fun config(): Any? = iris?.getMethod("getIrisConfig")?.invoke(null)

        fun packName(): String? = try {
            val config = config()
            (config?.javaClass?.getMethod("getShaderPackName")?.invoke(config) as? Optional<*>)?.orElse(null) as? String
        } catch (e: Throwable) {
            Guildmark.LOGGER.debug("Could not read Iris's chosen shader pack", e)
            null
        }

        fun enabled(): Boolean = try {
            val config = config()
            config?.javaClass?.getMethod("areShadersEnabled")?.invoke(config) == true
        } catch (_: Throwable) {
            false
        }

        fun choose(pack: String) {
            try {
                val config = config() ?: return
                config.javaClass.getMethod("setShaderPackName", String::class.java).invoke(config, pack)
                config.javaClass.getMethod("setShadersEnabled", Boolean::class.javaPrimitiveType).invoke(config, true)
                config.javaClass.getMethod("save").invoke(config)
                reload()
            } catch (e: Throwable) {
                Guildmark.LOGGER.warn("Could not choose Guildmark's shader pack in Iris; pick it under Video Settings > Shader Packs", e)
            }
        }

        fun reload() {
            try {
                iris?.getMethod("reload")?.invoke(null)
            } catch (e: Throwable) {
                Guildmark.LOGGER.warn("Could not reload Iris's shader pack", e)
            }
        }
    }
}
