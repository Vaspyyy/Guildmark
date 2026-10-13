package io.github.vaspyyy.guildmark.client.region

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.region.RegionKind
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.FogType
import net.neoforged.neoforge.client.event.ViewportEvent
import kotlin.math.max
import kotlin.math.min

/**
 * The air of the land around the player: the fog leans toward the land's colour and, in the wilder lands,
 * closes in (a green murk in the marshes, ash haze in the highlands, white on the peaks, violet in the
 * crystal deeps), and the land's own motes drift past (see RegionParticles). It goes by the biome under the
 * camera rather than the named region, so it follows the land closely; it eases from one land to the next
 * over a few seconds, and holds over a river or a beach rather than flicker.
 */
object RegionAtmosphere {
    /** Ticks to fade the air in or out. */
    private const val FADE = 60f

    /** A fog end past this many blocks has been switched off on purpose (Minecraft's own defaults to 1024). */
    private const val FOG_SWITCHED_OFF = 1_000_000f

    /** The land whose air is showing, and how strongly (0 to 1), this tick and last. */
    private var kind: RegionKind? = null
    private var strength = 0f
    private var lastStrength = 0f

    private fun enabled(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.REGION_ATMOSPHERE.get()

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        lastStrength = strength
        val wanted = if (level == null || minecraft.player == null || !enabled() || level.dimension() != Level.OVERWORLD) null
            else landHere(minecraft) ?: kind
        if (wanted == kind) strength = min(1f, strength + 1f / FADE)
        else {
            strength = max(0f, strength - 1f / FADE)
            // Faded out: take up the new land's air
            if (strength == 0f) kind = wanted
        }
        if (level != null && !minecraft.isPaused) RegionParticles.tick(kind, strength)
    }

    /** The kind of land at the camera: the crystal deeps by what the server says, the rest by the biome. Null over a seam. */
    private fun landHere(minecraft: Minecraft): RegionKind? {
        val level = minecraft.level ?: return null
        if (RegionClient.current?.kind == RegionKind.CRYSTAL) return RegionKind.CRYSTAL
        val camera = minecraft.gameRenderer.mainCamera()
        return RegionKind.ofBiome(level.getBiome(camera.blockPosition()))
    }

    private fun strength(partialTick: Float): Float = lastStrength + (strength - lastStrength) * partialTick

    fun reset() {
        kind = null
        strength = 0f
        lastStrength = 0f
    }

    /**
     * The fog's colour leans toward the land's, kept as bright as it was, so night stays dark and the
     * murk of a marsh reads as green rather than glowing.
     */
    fun onFogColor(event: ViewportEvent.ComputeFogColor) {
        val land = kind ?: return
        val fluid = event.camera.fluidInCamera
        if (fluid != FogType.NONE && fluid != FogType.ATMOSPHERIC) return
        val amount = land.fogTint * strength(event.partialTick)
        if (amount <= 0.001f) return
        val r = event.red
        val g = event.green
        val b = event.blue
        val bright = 0.299f * r + 0.587f * g + 0.114f * b
        val tr = ((land.fogColor shr 16) and 0xFF) / 255f
        val tg = ((land.fogColor shr 8) and 0xFF) / 255f
        val tb = (land.fogColor and 0xFF) / 255f
        val tintBright = max(0.299f * tr + 0.587f * tg + 0.114f * tb, 0.01f)
        val k = bright / tintBright
        event.red = r + (min(1f, tr * k) - r) * amount
        event.green = g + (min(1f, tg * k) - g) * amount
        event.blue = b + (min(1f, tb * k) - b) * amount
    }

    /**
     * In the wilder lands the haze closes in: the fog's far edge comes no further than the land's own.
     *
     * Not when another mod has switched the air's fog off by pushing it out of sight (Distant Horizons sets it
     * to about 4e19 blocks and shades its far lands with its own fog, which still takes the land's colour from
     * onFogColor). Easing down from there lands on 0 at full strength, as the land's few hundred blocks are lost
     * in the float, and Sodium then culls every chunk past the fog's end: the near world went blank.
     */
    fun onRenderFog(event: ViewportEvent.RenderFog) {
        val land = kind ?: return
        if (land.fogEnd <= 0f || event.type != FogType.ATMOSPHERIC || event.environment !is AtmosphericFogEnvironment) return
        val amount = strength(event.partialTick)
        if (amount <= 0.001f) return
        val end = event.farPlaneDistance
        if (end <= land.fogEnd || !end.isFinite() || end > FOG_SWITCHED_OFF) return
        event.farPlaneDistance = end + (land.fogEnd - end) * amount
    }
}
