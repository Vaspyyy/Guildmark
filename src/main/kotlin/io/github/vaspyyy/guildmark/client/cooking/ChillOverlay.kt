package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.ambience.AmbienceLoop
import io.github.vaspyyy.guildmark.client.season.SeasonMote
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.season.SeasonRegistry
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ParticleStatus
import net.minecraft.util.ARGB
import net.minecraft.util.RandomSource
import net.minecraft.util.Util
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.GuiLayer
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What Winter Chill, and a meal taking hold, look and sound like to the one who has them (living-world/cook/design.md
 * 4.1, 4.3, 9.4), drawn only for the local player and only from what the game has told this client (which effects they
 * have and for how long), so nothing here is a guess and nothing is sent.
 *
 *  - **The vignette**: `textures/gui/chill.png`, frost at the screen's edges, creeping in over a second or two while
 *    Chilled holds, breathing between 0.2 and 0.35 of its strength, and thawing away over a second when it goes.
 *  - **The tint**: when a meal's effect takes hold (or is made longer), the screen's edges glow in the family's colour,
 *    a soft frame that is strongest at once and breathes as it fades: warm amber for three seconds for Warmth, stronger
 *    when it is lifting the frost, a briefer glow for the other three.
 *  - **The wind**: the `cook.chill` loop, faint, swelling and fading with the frost, at the Hearthcraft sounds' volume.
 *  - **The breath**: quicker puffs of the winter breath ([SeasonRegistry.BREATH]) from the mouth, in front of the eyes in
 *    first person too, at the Hearthcraft particles' say.
 *
 * The `chillOverlay` option turns the vignette and the tints off; the effects stay. Part F's; [CookClient.register]
 * calls it, so no shared file is edited, and a dedicated server never loads it.
 */
object ChillOverlay : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "chill")
    private val FROST: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/chill.png")
    private val WIND: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cook.chill")
    private const val SIZE = 256

    /** The frost's strength when it holds, at the dimmest and the brightest of its breath (4.3). */
    private const val DIM = 0.20f
    private const val BRIGHT = 0.35f

    /** Seconds the frost takes to creep in, and to thaw. */
    private const val CREEP = 1.8f
    private const val THAW = 1.0f

    /** How loud the wind is at its fullest, before the option's share. */
    private const val WIND_LEVEL = 0.8f

    private const val ICE = 0xFFFFFF

    /** How much frost counts as "there", for a Warmth meal that is lifting it. */
    private const val THAWING = 0.15f

    /** How many bands the soft frame of a tint is drawn in, and how far in it reaches (a share of the screen's height). */
    private const val GLOW_STEPS = 14
    private const val GLOW_REACH = 0.15f

    /**
     * A family's tint when its meal takes: the effect, the colour (the one its tooltip line has, which is the icon's made a little
     * lighter for Steady and Provisioned so that it still shows over a night sky and dark grass), how strong it starts and how many
     * seconds it lasts. A night is when the cold is about, so 0.28 is what shows there.
     */
    private class Glow(val effect: Holder<MobEffect>, val colour: Int, val peak: Float, val seconds: Float) {
        /** The effect's time left and its amplifier at the last tick, or -1 when it was not there. */
        var last = -1
        var amp = -1
    }

    private val WARMTH = Glow(CookRegistry.WARMTH, 0xE8893C, 0.28f, 3f)
    private val GLOWS = listOf(
        WARMTH,
        Glow(CookRegistry.HEARTY, 0xC24B3F, 0.28f, 1.8f),
        Glow(CookRegistry.STEADY, 0x6F9BE0, 0.28f, 1.8f),
        Glow(CookRegistry.PROVISIONED, 0xB08A5A, 0.28f, 1.8f),
    )

    /** A Warmth meal that lifts the frost glows this strongly (4.3: the vignette thaws and a warm tint breathes for three seconds). */
    private const val LIFTING = 0.40f

    private val random = RandomSource.create()

    /** How far the frost has crept in, 0 to 1, eased each frame toward whether Chilled holds. */
    private var frost = 0f

    /** The tint now showing: how much of its time is left (1 to 0), and its colour, strength and length. */
    private var glow = 0f
    private var glowColour = 0
    private var glowPeak = 0f
    private var glowFor = 1f

    private var lastFrame = 0L
    private var wind: AmbienceLoop? = null
    private var windRetry = 0
    private var puff = 0

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterGuiLayersEvent::class.java) { event -> event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ID, this) }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
    }

    private fun reset() {
        frost = 0f
        glow = 0f
        lastFrame = 0L
        for (g in GLOWS) {
            g.last = -1
            g.amp = -1
        }
        wind?.target = 0f
        wind = null
    }

    /** Whether the options let the frost and the tints be drawn. */
    private fun shown(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.CHILL_OVERLAY.get()

    // ---- Each tick: what the game said ----

    private fun tick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        val level = minecraft.level
        if (player == null || level == null) {
            reset()
            return
        }
        // A meal takes (or is made longer): its tint, and more of Warmth's if the frost is being lifted. Not in the first two seconds of a world, when effects are only being told
        for (g in GLOWS) {
            val held = player.getEffect(g.effect)
            val left = held?.duration ?: -1
            val amp = held?.amplifier ?: -1
            if (player.tickCount > 40 && left >= 0 && (g.last < 0 || left > g.last + 10 || amp != g.amp)) {
                glow = 1f
                glowColour = g.colour
                glowPeak = if (g === WARMTH && frost > THAWING) LIFTING else g.peak
                glowFor = g.seconds
            }
            g.last = left
            g.amp = amp
        }
        if (minecraft.isPaused) return
        val chilled = player.hasEffect(CookRegistry.CHILLED)
        wind(minecraft, chilled)
        if (chilled) breathe(minecraft, player)
    }

    /** The wind loop swells while the cold holds and fades when it goes; not started at all if the sound has no line yet or its volume is 0. */
    private fun wind(minecraft: Minecraft, chilled: Boolean) {
        val share = if (GuildmarkClientConfig.SPEC.isLoaded) GuildmarkClientConfig.COOK_SOUNDS.get() / 100f else 1f
        val target = if (chilled) share * WIND_LEVEL else 0f
        val manager = minecraft.soundManager
        val loop = wind
        if (loop != null && !loop.isStopped && manager.isActive(loop)) {
            loop.target = target
            return
        }
        wind = null
        if (target < 0.01f || (windRetry > 0 && --windRetry > 0)) return
        // A sound with no entry in sounds.json is left alone, which would only fill the log
        if (manager.getSoundEvent(WIND) == null) return
        val fresh = AmbienceLoop(WIND)
        fresh.target = target
        if (manager.play(fresh) == SoundEngine.PlayResult.NOT_STARTED) windRetry = 100 else wind = fresh
    }

    /**
     * A shivering breath, quicker than the winter's own (one in about 1.3 seconds against one in three): a pair of puffs
     * from the mouth. In first person the game draws no one's own breath, so these are put just in front of the eyes,
     * small, drifting forward.
     */
    private fun breathe(minecraft: Minecraft, player: Player) {
        if (!GuildmarkClientConfig.SPEC.isLoaded || !GuildmarkClientConfig.COOK_PARTICLES.get()) return
        if (minecraft.options.particles().get() == ParticleStatus.MINIMAL || player.isUnderWater || player.isInvisible) return
        if (--puff > 0) return
        puff = 22 + random.nextInt(14)
        val first = minecraft.options.cameraType == CameraType.FIRST_PERSON
        val look = player.getViewVector(1f)
        val eye = player.getEyePosition(1f)
        val ahead = if (first) 0.5 else 0.32
        val px = eye.x + look.x * ahead
        val py = eye.y - (if (first) 0.2 else 0.12) + look.y * 0.2
        val pz = eye.z + look.z * ahead
        repeat(2) {
            val mote = minecraft.particleEngine.createParticle(
                SeasonRegistry.BREATH.get(), px, py, pz,
                look.x * 0.035 + (random.nextDouble() - 0.5) * 0.012, 0.005, look.z * 0.035 + (random.nextDouble() - 0.5) * 0.012,
            ) as? SeasonMote
            if (first) mote?.sized(0.55f)
        }
    }

    // ---- Each frame: the frost and the tint ----

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val now = Util.getMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        val hidden = minecraft.gui.hud.isHidden || !shown()
        // The frost creeps in over CREEP seconds and thaws over THAW; the tint runs down its own length
        val on = !hidden && player.hasEffect(CookRegistry.CHILLED)
        frost = if (on) min(1f, frost + dt / CREEP) else max(0f, frost - dt / THAW)
        glow = max(0f, glow - dt / glowFor)
        if (hidden || (frost <= 0.002f && glow <= 0.002f)) return
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val time = now / 1000f
        val tau = 2f * PI.toFloat()
        if (frost > 0.002f) {
            // Breathing: a slow swell between the dimmest and the brightest, with a faster flutter as of a shiver
            val swell = 0.5f + 0.5f * sin(time * tau / 4.2f)
            val flutter = 0.5f + 0.5f * sin(time * tau / 0.9f)
            val strength = DIM + (BRIGHT - DIM) * (0.8f * swell + 0.2f * flutter)
            val eased = smooth(frost)
            // Wider than the screen at first, closing to its edges as it gathers; on thawing it draws back out
            paint(graphics, width, height, 1f + 0.3f * (1f - eased) + 0.012f * swell, ARGB.color(strength * eased, ICE))
        }
        if (glow > 0.002f) {
            val inward = smooth((1f - glow) / 0.12f)
            val outward = smooth(glow / 0.5f)
            val breath = 0.78f + 0.22f * sin(time * tau / 1.5f)
            edges(graphics, width, height, glowColour, glowPeak * inward * outward * breath, GLOW_REACH + 0.03f * sin(time * tau / 1.5f))
        }
    }

    /** The frost drawn [grow] times the screen's size from its middle, tinted [color]. */
    private fun paint(graphics: GuiGraphicsExtractor, width: Int, height: Int, grow: Float, color: Int) {
        val w = (width * grow).roundToInt()
        val h = (height * grow).roundToInt()
        graphics.blit(RenderPipelines.GUI_TEXTURED, FROST, (width - w) / 2, (height - h) / 2, 0f, 0f, w, h, SIZE, SIZE, SIZE, SIZE, color)
    }

    /**
     * A soft frame of [rgb] at the screen's edges: [alpha] at the very edge, fading inward over [reach] of the screen's
     * height in [GLOW_STEPS] bands (each band four plain strips, so it costs a few dozen small fills for the two or
     * three seconds a tint lasts).
     */
    private fun edges(graphics: GuiGraphicsExtractor, width: Int, height: Int, rgb: Int, alpha: Float, reach: Float) {
        val depth = max(2, (height * reach).roundToInt())
        val steps = min(depth, GLOW_STEPS)
        for (i in 0 until steps) {
            val a = depth * i / steps
            val b = depth * (i + 1) / steps
            if (b <= a) continue
            val fade = 1f - i / steps.toFloat()
            val color = ARGB.color(alpha * fade * fade, rgb)
            graphics.fill(a, a, width - a, b, color)
            graphics.fill(a, height - b, width - a, height - a, color)
            graphics.fill(a, b, b, height - b, color)
            graphics.fill(width - b, b, width - a, height - b, color)
        }
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
