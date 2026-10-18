package io.github.vaspyyy.guildmark.client.festival

import io.github.vaspyyy.guildmark.client.season.SeasonBanner
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalPayload
import io.github.vaspyyy.guildmark.festival.FestivalPhase
import io.github.vaspyyy.guildmark.festival.FestivalRegistry
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.Level
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import net.neoforged.neoforge.client.event.SelectMusicEvent
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The client's side of the festivals: the ones going on nearby, as the server last said (where, what part of the day,
 * whether the band is playing and how far into its set), and what hangs off them: the band's music in time with the
 * dance ([FestivalMusic]), the banner as you come into the square, the decorations' renderer and the sky lanterns,
 * and the Sunhearth's wheel burning at dusk.
 */
object FestivalClient {
    /** A festival nearby. */
    class Site(val key: Long) {
        var kind = FestivalKind.RIBBONWAKE
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var square = 0
        var phase = FestivalPhase.SETUP
        var crown: BlockPos? = null
        var town = ""
        /** The client tick its current set began on (as near as can be told), or [NO_SET]. */
        var setStart = NO_SET
        var bannered = false

        /** How many ticks into its set the band is, or -1. */
        fun setAge(): Long = if (setStart == NO_SET) -1 else ticks - setStart

        fun distance(x: Double, z: Double): Double = hypot(x - this.x, z - this.z)
    }

    const val NO_SET = Long.MIN_VALUE

    private val sites = HashMap<Long, Site>()
    private var level: Level? = null

    /** Client ticks (not paused), the music's clock. */
    var ticks = 0L
        private set

    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(FestivalRegistry.ORNAMENT_ENTITY.get(), ::FestivalOrnamentRenderer)
        }
        modBus.addListener(RegisterParticleProvidersEvent::class.java) { event ->
            event.registerSpriteSet(FestivalRegistry.SKY_LANTERN.get()) { sprites -> SkyLanternParticle.Provider(sprites) }
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(SelectMusicEvent::class.java, FestivalMusic::onSelectMusic)
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
    }

    fun receive(payload: FestivalPayload) {
        if (payload.gone) {
            sites.remove(payload.site)
            return
        }
        val site = sites.getOrPut(payload.site) { Site(payload.site) }
        site.kind = FestivalKind.byOrdinal(payload.kind)
        site.x = payload.x
        site.y = payload.y
        site.z = payload.z
        site.square = payload.square
        site.phase = FestivalPhase.entries.getOrElse(payload.phase) { FestivalPhase.FAIR }
        site.crown = if (payload.crown == Long.MIN_VALUE) null else BlockPos.of(payload.crown)
        site.town = payload.town
        if (payload.setAge < 0) site.setStart = NO_SET
        else {
            // The same set as before unless the start has moved by more than the network's wobble
            val start = ticks - payload.setAge
            if (site.setStart == NO_SET || kotlin.math.abs(site.setStart - start) > 30) site.setStart = start
        }
    }

    /** Every festival the client knows of. */
    fun all(): Collection<Site> = sites.values

    /** The festival whose centrepiece crown is at [pos], if any. */
    fun byCrown(pos: BlockPos): Site? = sites.values.firstOrNull { it.crown == pos }

    /** The nearest festival to ([x], [z]) within [range] blocks of its square's edge. */
    fun nearest(x: Double, z: Double, range: Double): Site? =
        sites.values.filter { it.distance(x, z) < it.square + range }.minByOrNull { it.distance(x, z) }

    private fun tick() {
        val minecraft = Minecraft.getInstance()
        val current = minecraft.level
        if (current !== level) {
            // A new world, or another dimension: what the server said before is gone
            level = current
            sites.clear()
            FestivalMusic.reset()
        }
        if (current == null || minecraft.isPaused) return
        ticks++
        val player = minecraft.player ?: return
        // Out of earshot for long (the server stops telling) or far off: forgotten
        sites.values.removeIf { it.distance(player.x, player.z) > it.square + 200 }
        for (site in sites.values) {
            if (!site.bannered && site.phase.ordinal <= FestivalPhase.DUSK.ordinal && site.distance(player.x, player.z) < site.square + 20) {
                site.bannered = true
                SeasonBanner.showFestival(site.kind, site.town)
            }
            if (site.kind == FestivalKind.SUNHEARTH && site.phase == FestivalPhase.DUSK) wheelFire(current, site)
        }
        FestivalMusic.tick(minecraft)
    }

    /** At dusk the Sunhearth's wheel burns: flames run round its rim, sparks fly off it. */
    private fun wheelFire(level: Level, site: Site) {
        val crown = site.crown ?: return
        val random = level.random
        if (random.nextInt(2) != 0) return
        // The wheel stands upright, spinning, and slowly turns about the pole (as FestivalOrnamentRenderer draws it)
        val (spin, face) = FestivalOrnamentRenderer.wheelAngles(level.gameTime.toDouble())
        val a = spin + random.nextInt(8) * PI / 4 + random.nextGaussian() * 0.08
        val r = FestivalOrnamentRenderer.WHEEL_RADIUS
        val x = crown.x + 0.5 + cos(a) * r * cos(face)
        val z = crown.z + 0.5 + cos(a) * r * sin(face)
        val y = crown.y + 0.5 + FestivalOrnamentRenderer.WHEEL_LIFT + sin(a) * r
        level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.01, 0.0)
        if (random.nextInt(6) == 0) level.addParticle(ParticleTypes.LAVA, x, y, z, 0.0, 0.0, 0.0)
        if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.SMALL_FLAME, x, y + 0.1, z, 0.0, 0.02, 0.0)
    }

    private fun reset() {
        sites.clear()
        level = null
        FestivalMusic.reset()
    }
}
