package io.github.vaspyyy.guildmark.client.season

import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.client.renderer.WeatherEffectRenderer
import net.minecraft.core.BlockPos
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * Winter's snowfall in the temperate lands, where the game would have it rain: once the frame's weather is worked
 * out, its rain columns are made snow columns the way vanilla makes its own (same seeds, same drift, the same light),
 * so the snow falls as it does in the north and the shader pack lights it as snow. The rain's hiss goes with it.
 */
object SeasonWeather {
    private val random = RandomSource.create()

    /** Whether rain is falling as snow round the camera this frame (and so its sound is kept quiet). */
    @Volatile
    private var snowing = false

    fun register() {
        NeoForge.EVENT_BUS.addListener(ExtractLevelRenderStateEvent::class.java, ::onExtract)
        NeoForge.EVENT_BUS.addListener(PlaySoundEvent::class.java) { event ->
            if (!snowing) return@addListener
            val sound = event.sound ?: return@addListener
            val path = sound.identifier.path
            if (sound.identifier.namespace == "minecraft" && (path == "weather.rain" || path == "weather.rain.above")) event.sound = null
        }
    }

    /** Whether the rain falls as snow where the camera is. */
    private fun turns(level: Level, at: BlockPos): Boolean {
        if (!SeasonClient.active || !SeasonClient.snow || level.dimension() != Level.OVERWORLD) return false
        if (SeasonClient.date.season != Season.WINTER) return false
        return Seasons.temperate(level.getBiome(at).value()) >= 0.5f
    }

    private fun onExtract(event: ExtractLevelRenderStateEvent) {
        val level = event.level
        val weather = event.renderState.weatherRenderState
        val camera = BlockPos.containing(event.camera.position())
        snowing = level.getRainLevel(1f) > 0f && turns(level, camera)
        if (!snowing || weather.rainColumns.isEmpty()) return
        val partial = event.deltaTracker.getGameTimeDeltaPartialTick(false)
        val time = level.gameTime
        for (rain in weather.rainColumns) weather.snowColumns.add(snow(rain, time, partial))
        weather.rainColumns.clear()
    }

    /** A rain column as vanilla would have made it snow. */
    private fun snow(rain: WeatherEffectRenderer.ColumnInstance, time: Long, partial: Float): WeatherEffectRenderer.ColumnInstance {
        val x = rain.x()
        val z = rain.z()
        random.setSeed(((x * x * 3121 + x * 45238971) xor (z * z * 418711 + z * 13761)).toLong())
        val t = (time and 131071L).toInt() + partial
        val u = (random.nextDouble() + t * 0.01f * random.nextGaussian().toFloat()).toFloat()
        val jitter = (random.nextDouble() + t * random.nextGaussian().toFloat() * 0.001f).toFloat()
        val v = -((time and 511L) + partial) / 512f
        val light = rain.lightCoords()
        val lit = LightCoordsUtil.pack((LightCoordsUtil.block(light) * 3 + 15) / 4, (LightCoordsUtil.sky(light) * 3 + 15) / 4)
        return WeatherEffectRenderer.ColumnInstance(x, z, rain.bottomY(), rain.topY(), u, v + jitter, lit)
    }

    fun reset() {
        snowing = false
    }
}
