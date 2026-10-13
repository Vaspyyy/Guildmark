package io.github.vaspyyy.guildmark.client.ui.title

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The painted dusk behind Guildmark's menus: far mountains with the sun setting in a pass, the capital
 * on its hill, and a Wardfire burning in front. The layers slide at their own depth as time passes and
 * as the mouse moves; the fire, its embers, the chimney smoke, the windows and the stars are drawn live
 * on top. The art comes from art/scripts/title_art.py and is laid out on a 1920 by 1080 canvas that's
 * scaled to cover the screen.
 */
object Backdrop {
    private const val CANVAS_W = 1920f
    private const val CANVAS_H = 1080f

    /** How far the layers wander with time and with the mouse, at full depth (canvas pixels). */
    private const val DRIFT_X = 34f
    private const val DRIFT_X2 = 10f
    private const val DRIFT_Y = 6f
    private const val MOUSE_X = 30f
    private const val MOUSE_Y = 10f

    private fun texture(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/title/$name.png")

    /**
     * A painted layer: drawn [w] by [h] canvas pixels with its corner at [x], [y], from a texture of [texW]
     * by [texH]; [depth] is how much of the drift it follows (0 stays put, 1 moves the most). A [tile] layer
     * repeats sideways every [tile] canvas pixels and scrolls at [speed] canvas pixels a second.
     */
    private class Layer(
        name: String, val x: Float, val y: Float, val w: Int, val h: Int, val texW: Int, val texH: Int, val depth: Float,
        val additive: Boolean = false, val tile: Int = 0, val speed: Float = 0f,
    ) {
        val id = texture(name)
    }

    private val SKY = Layer("sky", -96f, -40f, 2112, 1160, 1056, 580, 0.03f)
    private val CLOUDS = Layer("clouds", -96f, 40f, 2112, 400, 1024, 200, 0.08f, tile = 2048, speed = -4.5f)
    private val MOUNTAINS = Layer("mountains_far", -96f, 420f, 2112, 600, 2112, 600, 0.14f)
    private val HILLS = Layer("hills", -96f, 640f, 2112, 480, 2112, 480, 0.3f)
    private val CAPITAL = Layer("capital", -96f, 360f, 2112, 760, 2112, 760, 0.5f)
    private val LIGHTS = Layer("capital_lights", -96f, 360f, 2112, 760, 1056, 380, 0.5f, additive = true)
    private val LIGHTS_2 = Layer("capital_lights2", -96f, 360f, 2112, 760, 1056, 380, 0.5f, additive = true)
    // In front of the city, so the mist lies across the foot of its walls
    private val MIST = Layer("mist", -96f, 820f, 2112, 256, 1024, 128, 0.55f, tile = 2048, speed = -9f)
    private val FOREGROUND = Layer("foreground", -96f, 440f, 2112, 680, 2112, 680, 1f)

    private val VIGNETTE = texture("vignette")
    private val FLAME = texture("flame")
    private val GLOW = texture("glow")
    private val EMBER = texture("ember")
    private val STAR = texture("star")
    private val SMOKE = texture("smoke")

    /** Where the Wardfire burns: the middle of its bed of ash, where the sword is planted. */
    private const val FIRE_X = 430f
    private const val FIRE_Y = 975f
    private const val FLAME_FRAMES = 8
    private const val FLAME_W = 84
    private const val FLAME_H = 140

    /** The tops of the chimneys on the capital's roofs, where the smoke comes from. The last is on the keep's hall. */
    private val CHIMNEYS = listOf(1198f to 892f, 1293f to 872f, 1343f to 819f, 1464f to 805f, 1552f to 873f, 1629f to 752f)

    private class Ember(var x: Float, var y: Float, var vx: Float, var vy: Float, val life: Float, val size: Float, val phase: Float) {
        var age = 0f
    }

    private class Puff(var x: Float, var y: Float, val life: Float, val spin: Float) {
        var age = 0f
    }

    private class Star(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float, val warm: Boolean)

    private val random = Random(0x6A11D)
    private val embers = ArrayList<Ember>()
    private val puffs = ArrayList<Puff>()
    private val stars: List<Star> = Random(1187).let { r ->
        List(46) { Star(-60f + r.nextFloat() * 2040f, 16f + r.nextFloat() * r.nextFloat() * 360f, 5f + r.nextFloat() * 8f, 0.6f + r.nextFloat() * 1.6f, r.nextFloat() * 6.28f, r.nextInt(5) == 0) }
    }
    private var emberDebt = 0f
    private var smokeDebt = 0f

    /** Seconds the scene has been running, and when it was last drawn. */
    private var clock = 0.0
    private var drawnAt = 0L
    /** Where the mouse has pulled the scene, eased toward where it is. */
    private var lookX = 0f
    private var lookY = 0f

    /**
     * Draws the scene over the whole [width] by [height] screen. [mouseX] and [mouseY] (in GUI pixels) pull
     * the layers a little; pass negative values to leave the mouse out of it. [shown] below 1 fades it
     * toward black.
     */
    fun draw(graphics: GuiGraphicsExtractor, width: Int, height: Int, mouseX: Float = -1f, mouseY: Float = -1f, shown: Float = 1f) {
        advance(width, height, mouseX, mouseY)
        val scale = max(width / CANVAS_W, height / CANVAS_H)
        // Centred sideways; on a screen wider than the painting, more is cut from the sky than the ground
        val left = (width - CANVAS_W * scale) / 2f
        val top = (height - CANVAS_H * scale) * 0.7f
        val t = clock.toFloat()
        val shiftX = DRIFT_X * wave(t, 95f) + DRIFT_X2 * wave(t, 37f, 1.3f) + lookX
        val shiftY = DRIFT_Y * wave(t, 61f, 0.4f) + lookY
        val scene = Scene(graphics, left, top, scale, shiftX, shiftY)

        scene.layer(SKY)
        drawStars(scene, t)
        scene.layer(CLOUDS, scroll = t)
        scene.layer(MOUNTAINS)
        scene.layer(HILLS)
        scene.layer(CAPITAL)
        scene.layer(LIGHTS, light = 0.78f + 0.22f * flicker(t * 0.55f, 3))
        scene.layer(LIGHTS_2, light = 0.78f + 0.22f * flicker(t * 0.45f, 11))
        drawSmoke(scene)
        scene.layer(MIST, scroll = t)
        scene.layer(FOREGROUND)
        drawFire(scene, t)
        drawEmbers(scene)
        graphics.blit(RenderPipelines.GUI_TEXTURED, VIGNETTE, 0, 0, 0f, 0f, width, height, 320, 180, 320, 180)
        if (shown < 1f) graphics.fill(0, 0, width, height, ARGB.color(1f - shown.coerceIn(0f, 1f), 0xFF000000.toInt()))
    }

    /** One frame of drawing: where the canvas sits on the screen and how far it has drifted. */
    private class Scene(val graphics: GuiGraphicsExtractor, val left: Float, val top: Float, val scale: Float, val shiftX: Float, val shiftY: Float) {
        fun layer(layer: Layer, scroll: Float = 0f, light: Float = 1f) {
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(left + (layer.x + shiftX * layer.depth) * scale, top + (layer.y + shiftY * layer.depth) * scale)
            pose.scale(scale, scale)
            val srcW = if (layer.tile > 0) layer.w * layer.texW / layer.tile else layer.texW
            val srcH = layer.texH
            // A tiling layer scrolls by sliding its texture along, wrapping round
            val u = if (layer.tile > 0) floorMod(scroll * layer.speed * layer.texW / layer.tile, layer.texW.toFloat()) else 0f
            val color = if (layer.additive) glow(light, 1f, 1f, 1f) else -1
            graphics.blit(if (layer.additive) ADDITIVE else RenderPipelines.GUI_TEXTURED, layer.id, 0, 0, u, 0f, layer.w, layer.h, srcW, srcH, layer.texW, layer.texH, color)
            pose.popMatrix()
        }

        /** [texture] ([texW] by [texH], or the part from [u], [v] of [srcW] by [srcH]) centred on canvas point [x], [y], [w] by [h] big, moving at [depth]. */
        fun sprite(texture: Identifier, x: Float, y: Float, depth: Float, w: Float, h: Float, color: Int, additive: Boolean,
                   u: Float = 0f, v: Float = 0f, srcW: Int = 0, srcH: Int = 0, texW: Int, texH: Int, rotate: Float = 0f) {
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(left + (x + shiftX * depth) * scale, top + (y + shiftY * depth) * scale)
            if (rotate != 0f) pose.rotate(rotate)
            // Drawn as a 64-unit quad and scaled, so sizes and positions can fall between pixels
            pose.scale(scale * w / 64f, scale * h / 64f)
            graphics.blit(if (additive) ADDITIVE else RenderPipelines.GUI_TEXTURED, texture, -32, -32, u, v, 64, 64,
                if (srcW > 0) srcW else texW, if (srcH > 0) srcH else texH, texW, texH, color)
            pose.popMatrix()
        }
    }

    // ---- Animation ----

    private fun advance(width: Int, height: Int, mouseX: Float, mouseY: Float) {
        val now = Util.getMillis()
        val away = now - drawnAt
        drawnAt = now
        if (away > 2000L) {
            // Coming back to the scene: let the fire have been burning a while already
            embers.clear()
            puffs.clear()
            repeat(80) { step(0.05f) }
            lookX = target(mouseX, width, MOUSE_X)
            lookY = target(mouseY, height, MOUSE_Y)
            return
        }
        val dt = (away / 1000f).coerceIn(0f, 0.1f)
        step(dt)
        val ease = 1f - exp(-dt * 2.2f)
        lookX += (target(mouseX, width, MOUSE_X) - lookX) * ease
        lookY += (target(mouseY, height, MOUSE_Y) - lookY) * ease
    }

    /** How far the mouse pulls the scene: the layers lean away from the cursor, as if looking past them. */
    private fun target(mouse: Float, size: Int, reach: Float): Float =
        if (mouse < 0f || size <= 0) 0f else (0.5f - (mouse / size).coerceIn(0f, 1f)) * 2f * reach

    private fun step(dt: Float) {
        clock += dt
        val t = clock.toFloat()
        // Embers: a steady stream from the fire, more when it flares
        emberDebt += dt * (11f + 7f * flicker(t * 1.7f, 5))
        while (emberDebt >= 1f) {
            emberDebt -= 1f
            embers.add(Ember(
                FIRE_X + (random.nextFloat() - 0.5f) * 46f, FIRE_Y - 30f - random.nextFloat() * 40f,
                (random.nextFloat() - 0.4f) * 18f, -(48f + random.nextFloat() * 70f),
                2.2f + random.nextFloat() * 3.4f, 3.5f + random.nextFloat() * 5f, random.nextFloat() * 6.28f,
            ))
        }
        val iterator = embers.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            e.age += dt
            if (e.age >= e.life) {
                iterator.remove()
                continue
            }
            // A light breeze carries them toward the city; each one sways on its own
            e.vx += (7f + 9f * sin(t * 0.7f + e.phase) - e.vx * 0.3f) * dt
            e.vy *= 1f - 0.18f * dt
            e.x += (e.vx + sin(t * 2.3f + e.phase) * 9f) * dt
            e.y += e.vy * dt
        }
        smokeDebt += dt * CHIMNEYS.size * 1.4f
        while (smokeDebt >= 1f) {
            smokeDebt -= 1f
            val (x, y) = CHIMNEYS[random.nextInt(CHIMNEYS.size)]
            puffs.add(Puff(x + (random.nextFloat() - 0.5f) * 4f, y, 5f + random.nextFloat() * 3f, (random.nextFloat() - 0.5f) * 0.4f))
        }
        val puffIterator = puffs.iterator()
        while (puffIterator.hasNext()) {
            val p = puffIterator.next()
            p.age += dt
            if (p.age >= p.life) {
                puffIterator.remove()
                continue
            }
            p.x += (5f + p.age * 2.5f) * dt
            p.y -= (13f - p.age * 0.8f) * dt
        }
    }

    // ---- Drawing the live parts ----

    private fun drawStars(scene: Scene, t: Float) {
        for (star in stars) {
            val twinkle = 0.5f + 0.5f * sin(t * star.speed + star.phase)
            val k = (0.25f + 0.75f * twinkle * twinkle * twinkle) * 0.8f
            val color = if (star.warm) glow(k, 1f, 0.86f, 0.7f) else glow(k, 0.8f, 0.86f, 1f)
            scene.sprite(STAR, star.x, star.y, SKY.depth, star.size, star.size, color, true, texW = 16, texH = 16)
        }
    }

    private fun drawSmoke(scene: Scene) {
        for (p in puffs) {
            val k = p.age / p.life
            val fade = min(k / 0.15f, 1f) * (1f - k) * (1f - k)
            val size = 12f + 44f * k
            val color = ARGB.color((fade * 0.32f * 255).toInt(), 122, 106, 140)
            scene.sprite(SMOKE, p.x, p.y, CAPITAL.depth, size, size, color, false, texW = 64, texH = 64, rotate = p.spin * p.age)
        }
    }

    private fun drawFire(scene: Scene, t: Float) {
        val flare = 0.82f + 0.18f * flicker(t * 2.6f, 7)
        // The firelight spreads over the stones and the ground, then a hotter core round the blade
        scene.sprite(GLOW, FIRE_X, FIRE_Y - 40f, 1f, 560f * flare, 460f * flare, glow(0.42f * flare, 1f, 0.55f, 0.2f), true, texW = 128, texH = 128)
        scene.sprite(GLOW, FIRE_X, FIRE_Y - 50f, 1f, 190f, 210f, glow(0.5f * flare, 1f, 0.72f, 0.35f), true, texW = 128, texH = 128)
        // The flames: crossfading frames, so they move smoothly however fast the game draws
        val f = t * 10f
        val frame = floor(f).toInt()
        val blend = f - frame
        for ((index, weight) in listOf(frame to 1f - blend, frame + 1 to blend)) {
            if (weight <= 0.01f) continue
            val u = (index.mod(FLAME_FRAMES) * 96).toFloat()
            // Taller as it flares, but always standing on the ash
            val h = FLAME_H * flare
            scene.sprite(FLAME, FIRE_X, FIRE_Y - h / 2f, 1f, FLAME_W * (0.96f + 0.04f * flare), h, glow(weight, 1f, 1f, 1f), true,
                u = u, srcW = 96, srcH = 160, texW = 96 * FLAME_FRAMES, texH = 160)
        }
    }

    private fun drawEmbers(scene: Scene) {
        for (e in embers) {
            val k = e.age / e.life
            val fade = min(k / 0.08f, 1f) * (1f - k * k)
            // White-gold as they leave the fire, cooling to a deep orange
            val g = 0.82f - 0.42f * k
            val b = 0.45f - 0.35f * k
            val twinkle = 0.75f + 0.25f * sin(e.age * 13f + e.phase)
            scene.sprite(EMBER, e.x, e.y, 1f, e.size, e.size, glow(fade * twinkle, 1f, g, b), true, texW = 16, texH = 16)
        }
    }

    // ---- Helpers ----

    /** The pipeline that adds light rather than covering what's under it. */
    private val ADDITIVE = RenderPipelines.GUI_NAUSEA_OVERLAY

    /** A colour for additive drawing: the light [k] strong, in the given tint. */
    private fun glow(k: Float, r: Float, g: Float, b: Float): Int {
        val v = k.coerceIn(0f, 1f) * 255f
        return ARGB.color(255, (v * r).toInt(), (v * g).toInt(), (v * b).toInt())
    }

    private fun wave(t: Float, period: Float, phase: Float = 0f): Float = sin(t * 2f * PI.toFloat() / period + phase)

    /** Smooth noise in 0..1 made of a few unrelated waves, so nothing visibly repeats. */
    private fun flicker(t: Float, seed: Int): Float {
        val s = seed * 1.618f
        val v = sin(t * 1.0f + s) * 0.5f + sin(t * 2.31f + s * 2.1f) * 0.3f + sin(t * 5.17f + s * 0.7f) * 0.2f
        return (v * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    private fun floorMod(value: Float, by: Float): Float {
        val r = value % by
        return if (r < 0f) r + by else r
    }
}
