package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Items
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Stewpot seen (living-world/cook/design.md 1.5, 11.1): an iron pot on a chain over a fire, at a camp or on a hearth with a
 * crane. Everything it draws is worked from the station's [StationAnim] and the frame's partial tick:
 *
 *  - the pot slides down its chain a pixel for each notch of heat and sways on it when anything knocks it (a heat change, an item
 *    landing, a stir, a call); at a hearth the crane swings it off the fire and back for a change of heat, and the surface, the
 *    chunks and the soot ride with it, so they are drawn inside the pot's own pose;
 *  - the soot grows over the pot in three stains, crossfading from one to the next;
 *  - each ingredient is a chunk afloat at the surface: it arcs in from the side of the cook who threw it, dips and bobs, settles,
 *    drifts round with the stir, and when it leaves it sinks and shrinks where it lay;
 *  - the surface is the liquid's disc, tinted toward the dish, turning with the stir, glinting, dulled by a missing seasoning,
 *    browning at the rim for a stick, with rings from every plop, bubbles (few, big and slow when it thickens), foam climbing the
 *    rim in a boil-over, a grey film that a skim sweeps away, a dark crust when it is burnt, and, when it is ready, a pale gold
 *    ring that tightens to the ripe moment and opens again.
 *
 * The bowls of the inn's pot stand on the hearth's shelf and are set down or taken up as the count eases.
 */
class StewpotRenderer(context: BlockEntityRendererProvider.Context) : StationRenderer<StewpotBlockEntity>(context) {
    private val bowlId: Int = BuiltInRegistries.ITEM.getId(Items.BOWL)

    override fun draw(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val p = state.partial
        val t = anim.time(p)
        val ph = anim.wander(p)
        val light = state.lightCoords
        val hearth = anim.hearth
        val craneDeg = if (hearth) anim.crane.at(p) else 0f

        if (hearth) {
            poseStack.pushPose()
            aboutPost(poseStack, craneDeg)
            CookPieces.solid(collector, poseStack, CookPieces.CRANE, light)
            poseStack.popPose()
            shelf(state, anim, poseStack, collector)
        }

        // The pot's own pose: swung on the crane, let down its chain, swaying from the hook
        poseStack.pushPose()
        if (hearth) aboutPost(poseStack, craneDeg)
        Gfx.at(poseStack, 0f, -anim.heat.at(p) * CookGeo.NOTCH_STEP, 0f)
        Gfx.at(poseStack, 8f, CookGeo.HANG_Y, 8f)
        poseStack.rotateDegrees(Axis.XP, anim.swayX.at(p))
        poseStack.rotateDegrees(Axis.ZP, anim.swayZ.at(p))
        Gfx.at(poseStack, -8f, -CookGeo.HANG_Y, -8f)

        CookPieces.solid(collector, poseStack, CookPieces.POT, light)
        soot(state, anim, poseStack, collector, light)
        val w = anim.fill.at(p)
        val sy = surfaceY(w) + 0.035f * sin(ph * 0.19f) * anim.energy.at(p)
        chunks(state, anim, poseStack, collector, t, ph, sy)
        surface(state, anim, poseStack, collector, t, ph, w, sy)
        poseStack.popPose()
    }

    /** Into the frame of the crane: turned about its post (the arm, and the pot hanging from it, swing together). */
    private fun aboutPost(poseStack: PoseStack, degrees: Float) {
        if (abs(degrees) < 0.001f) return
        Gfx.at(poseStack, CookGeo.CRANE_X, 0f, CookGeo.CRANE_Z)
        poseStack.rotateDegrees(Axis.YP, degrees)
        Gfx.at(poseStack, -CookGeo.CRANE_X, 0f, -CookGeo.CRANE_Z)
    }

    /** The height of the surface at notch 0 for [w] eighths of water: a ladleful (6.7) up to full (9.4), and a film of it below that. */
    private fun surfaceY(w: Float): Float =
        if (w < 1f) 6.35f + 0.35f * w else CookGeo.SURFACE_LOW + (CookGeo.SURFACE_HIGH - CookGeo.SURFACE_LOW) * ((w - 1f) / 7f)

    // ---- The soot ----

    private fun soot(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, light: Int) {
        if (!state.near) return
        val s = anim.soot.at(state.partial).coerceIn(0f, 3f)
        val k = floor(s).toInt()
        val f = s - k
        when {
            k >= 3 -> CookPieces.faded(collector, poseStack, CookPieces.SOOT_3, light, 1f, 1)
            k == 0 -> if (f > 0.02f) CookPieces.faded(collector, poseStack, CookPieces.SOOT_1, light, f, 1)
            state.close -> {
                // Two stains, one coming as the other goes
                CookPieces.faded(collector, poseStack, CookPieces.SOOT[k - 1], light, 1f - f, 1)
                CookPieces.faded(collector, poseStack, CookPieces.SOOT[k], light, f, 1)
            }
            else -> CookPieces.faded(collector, poseStack, CookPieces.SOOT[if (f < 0.5f) k - 1 else k], light, 1f, 1)
        }
    }

    // ---- The chunks ----

    private fun chunks(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double, ph: Float, sy: Float) {
        val v = anim.view
        val p = state.partial
        val n = v.items.size
        val leaving = anim.leaving
        if (n == 0 && leaving.isEmpty()) return
        if (!state.near && n > 0 && anim.plops.isEmpty()) {
            // Far off a chunk is a speck: the bare pictures, at rest
            val far = Sprites(n)
            val orbit = anim.orbit.at(p)
            for (i in 0 until n) {
                val id = v.items[i]
                val at = anim.chunkAt(i, id, orbit)
                far.add(CookItems.flat(id, resolver, state.level), at[0], sy + 0.08f, at[1], 0.4f * i, 0f, 0f, HALF, 0xFFE8E8E8.toInt())
            }
            far.submit(state, collector, poseStack)
            return
        }
        val sprites = Sprites(n + leaving.size)
        val level = state.level
        val orbit = anim.orbit.at(p)
        val energy = anim.energy.at(p)
        val cooked = anim.brown.at(p).coerceIn(0f, 1f)
        val br = anim.tintR.at(p) / 255f
        val bg = anim.tintG.at(p) / 255f
        val bb = anim.tintB.at(p) / 255f
        val rim = CookGeo.POT_TOP
        val flyTicks = StationAnim.PLOP_FLY.toFloat()
        val bobTicks = StationAnim.PLOP_BOB.toFloat()

        for (i in 0 until n) {
            val id = v.items[i]
            val flat = CookItems.flat(id, resolver, level) ?: continue
            var plop: Plop? = null
            for (q in anim.plops) {
                val best = plop
                if (q.index == i && (best == null || q.at > best.at)) plop = q
            }
            val rest = anim.chunkAt(i, id, orbit)
            val seed = Mix.unit(id, i)
            val phase = ph * (0.09f + 0.06f * seed) + seed * Gfx.TAU
            val sign = if (i % 2 == 0) 1f else -1f
            var x = rest[0]
            var z = rest[1]
            var y = sy + 0.07f + 0.06f * sin(phase) * (0.4f + energy)
            var half = HALF
            var fresh = 0f
            var yaw = seed * Gfx.TAU + orbit * 0.3f * sign
            var tx = 0.2f * sin(phase * 1.3f) * (0.35f + energy)
            var tz = 0.2f * cos(phase) * (0.35f + energy)
            if (plop != null) {
                val age = (t - plop.at).toFloat()
                if (age < 0f) continue
                if (age < flyTicks) {
                    // In the air: from over the rim on the cook's side, in an arc, spinning
                    val u = age / flyTicks
                    val sx = 8f + plop.dx * 6.6f
                    val sz = 8f + plop.dz * 6.6f
                    x = sx + (rest[0] - sx) * u
                    z = sz + (rest[1] - sz) * u
                    y = (rim + 5.2f) + (sy + 0.1f - (rim + 5.2f)) * u + 3.4f * sin(PI.toFloat() * u)
                    half = HALF * (1.3f - 0.3f * u) * min(1f, 0.4f + 2.5f * u)
                    yaw += u * 9f
                    tx = 0.55f * (1f - u)
                    tz = -0.4f * (1f - u)
                } else {
                    val a = age - flyTicks
                    if (a < bobTicks) {
                        // It goes under, comes up, and bobs smaller and smaller
                        y -= 0.8f * exp(-a / 8f) * sin(a * 0.45f)
                        fresh = exp(-a / 32f)
                        half *= 1f + 0.1f * exp(-a / 5f)
                        tx += 0.25f * exp(-a / 10f) * sin(a * 0.5f)
                    }
                }
            }
            // The colour: bright and plain when it lands, taking the broth's as it cooks
            val m = (0.14f + 0.36f * cooked - 0.1f * fresh).coerceIn(0f, 0.6f)
            val k = 0.95f - 0.12f * cooked
            sprites.add(
                flat, x, y, z, yaw, if (state.near) tx else 0f, if (state.near) tz else 0f, half,
                Gfx.argb(1f, Gfx.mix(k, br * 1.05f, m), Gfx.mix(k, bg * 1.05f, m), Gfx.mix(k, bb * 1.05f, m)),
            )
        }

        // What left: it sinks where it lay and shrinks to nothing
        for (l in leaving) {
            val flat = CookItems.flat(l.item, resolver, level) ?: continue
            val u = ((t - l.at).toFloat() / StationAnim.LEAVE_TICKS).coerceIn(0f, 1f)
            val s = 1f - u * u
            sprites.add(
                flat, l.x + 0.25f * sin(u * 3f), sy + 0.05f - 1.6f * u, l.z, u * 2f, 0.2f * u, -0.2f * u, HALF * s,
                Gfx.argb(1f, Gfx.mix(0.9f, br, 0.5f * u), Gfx.mix(0.9f, bg, 0.5f * u), Gfx.mix(0.9f, bb, 0.5f * u)),
            )
        }
        sprites.submit(state, collector, poseStack)
    }

    // ---- The bowls on the house pot's shelf ----

    private fun shelf(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val count = anim.bowls.at(state.partial)
        if (count < 0.02f) return
        val flat = CookItems.flat(bowlId, resolver, state.level) ?: return
        val sprites = Sprites(8)
        for (j in 0 until 8) {
            val presence = (count - j).coerceIn(0f, 1f)
            if (presence <= 0f) continue
            val col = j % 4
            val layer = j / 4
            val drop = 1f - presence
            sprites.add(
                flat, 3.6f + 3.0f * col, 1.95f + 0.8f * layer + 2.6f * drop * drop, 1.9f, 0.35f * col + 0.5f * layer, 0f, 0f,
                1.35f * (0.55f + 0.45f * presence), 0xFFFFFFFF.toInt(),
            )
        }
        sprites.submit(state, collector, poseStack)
    }

    // ---- The surface and what lies on it ----

    private fun surface(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double, ph: Float, w: Float, sy: Float) {
        val p = state.partial
        val visible = (w * 2.5f).coerceIn(0f, 1f)
        val crust = anim.crust.at(p)
        val ringA = anim.ringA.at(p)
        val film = anim.film.at(p)
        if (visible < 0.02f && crust < 0.02f && ringA < 0.02f) return

        val energy = anim.energy.at(p)
        val gloss = anim.gloss.at(p)
        val dull = anim.dull.at(p)
        val thick = anim.thick.at(p)
        val foam = anim.foam.at(p)
        val swirl = anim.swirl.at(p)
        val spin = anim.spin.at(p)
        val close = state.close
        val near = state.near

        // The liquid's colour: the dish's tint, lightened when it shines, dull when it wants salt, thick and brown when it sticks, dark when it burns
        var r = anim.tintR.at(p) / 255f
        var g = anim.tintG.at(p) / 255f
        var b = anim.tintB.at(p) / 255f
        r = Gfx.mix(r, 0.55f, 0.55f * dull)
        g = Gfx.mix(g, 0.5f, 0.55f * dull)
        b = Gfx.mix(b, 0.43f, 0.55f * dull)
        r = Gfx.mix(r, 0.62f, 0.3f * thick)
        g = Gfx.mix(g, 0.46f, 0.3f * thick)
        b = Gfx.mix(b, 0.28f, 0.3f * thick)
        r = Gfx.mix(r, 1f, 0.2f * gloss)
        g = Gfx.mix(g, 1f, 0.2f * gloss)
        b = Gfx.mix(b, 1f, 0.2f * gloss)
        val dark = 1f - 0.55f * crust
        val base = Gfx.argb(0.86f * visible, r * dark, g * dark, b * dark)
        val half = CookGeo.SURFACE_R

        // How long since the last answer or miss
        val answerAge = (t - anim.answeredAt).toFloat() / ANSWER_TICKS
        val missAge = (t - anim.missedAt).toFloat() / MISS_TICKS
        val answered = anim.answered != null && answerAge in 0f..1f
        val missed = anim.missed != null && missAge in 0f..1f

        // The layer of the liquid itself, and what rides on it
        if (visible >= 0.02f) {
            state.layer(collector, 2, poseStack, Gfx.SURFACE) { pen ->
                pen.blob(8f, sy, 8f, half, half, spin, base)
                if (swirl > 0.12f) pen.blob(8f, sy + 0.012f, 8f, half * 0.96f, half * 0.96f, spin * 0.55f + 1.1f, Gfx.argb(0.3f * swirl * visible, r, g, b))

                if (close && (gloss > 0.05f || energy > 0.3f)) glints(pen, ph, sy, (0.1f + 0.4f * gloss + 0.1f * energy) * visible)
                if (near) for (rp in anim.ripples) ripple(pen, rp, (t - rp.at).toFloat(), sy, r, g, b)
                if (close && energy > 0.33f && crust < 0.5f) bubbles(pen, ph, anim, sy, energy, thick, r, g, b)
                if (foam > 0.02f) foam(pen, ph, foam, sy, r, g, b)
                if (thick > 0.05f) wisp(pen, sy, thick, ph)
                if (answered) answer(pen, answerAge, sy)
                if (missed) pen.blob(8f, sy + 0.1f, 8f, half, half, 0.4f, Gfx.argb(0.5f * (1f - missAge), 0.22f, 0.1f, 0.04f))
            }
        }

        // The grey film of a SKIM, and the swipe that clears it
        if (film > 0.02f) {
            val wipe = anim.wipe.at(p)
            val spread = 0.55f + 0.45f * film.coerceAtMost(1f)
            val alpha = (film * 1.15f).coerceAtMost(1f) * 0.95f
            val colour = Gfx.argb(alpha, 1f, 1f, 1f)
            state.layer(collector, 3, poseStack, Gfx.FILM) { pen ->
                val hs = half * spread
                if (wipe <= 0.001f) {
                    pen.flat(8f, sy + 0.09f, 8f, hs, hs, 0f, 0f, 0f, 1f, 1f, colour)
                } else {
                    // The skimmer sweeps from the -x side to the +x side, taking the film with it
                    val keep = 1f - wipe
                    pen.flat(8f + hs * wipe, sy + 0.09f, 8f, hs * keep, hs, 0f, wipe, 0f, 1f, 1f, colour)
                }
            }
        }

        // The crust of a burnt pot, and the tar bubbling up through it
        if (crust > 0.02f) {
            state.layer(collector, 4, poseStack, Gfx.CRUST) { pen ->
                pen.flat(8f, sy + 0.12f, 8f, half, half, 0.6f, 0f, 0f, 1f, 1f, Gfx.argb(0.97f * crust, 1f, 1f, 1f))
            }
            if (close && crust > 0.5f) {
                state.layer(collector, 5, poseStack, Gfx.SURFACE) { pen -> blisters(pen, ph, sy + 0.16f, crust) }
            }
        }

        // The ring of a ready pot
        if (ringA > 0.02f) {
            val phase = anim.ringPhase(t).let { if (it >= 0f) it else anim.ringLast }
            val x = abs(phase - 0.5f) * 2f
            val radius = 1.0f + 2.1f * x.pow(1.15f)
            val hh = radius / RING_RADIUS
            val bright = 0.5f + 0.5f * (1f - x).pow(2f)
            val flash = if (x < 0.07f) 0.45f * (1f - x / 0.07f) else 0f
            val a = (ringA * (0.5f + 0.5f * bright) + flash * ringA).coerceAtMost(1f)
            state.layer(collector, 6, poseStack, Gfx.RING) { pen ->
                pen.flat(8f, sy + 0.14f, 8f, hh, hh, 0f, 0f, 0f, 1f, 1f, Gfx.argb(a, 1f, 1f, 1f))
                if (x < 0.3f) {
                    val h2 = hh * 1.5f
                    pen.flat(8f, sy + 0.15f, 8f, h2, h2, 0.5f, 0f, 0f, 1f, 1f, Gfx.argb(a * 0.35f * (1f - x / 0.3f), 1f, 0.95f, 0.8f))
                }
            }
        }
    }

    /** Two points of light that drift over a shining surface. */
    private fun glints(pen: Pen, ph: Float, sy: Float, alpha: Float) {
        for (k in 0..1) {
            val ang = ph * (0.021f + 0.013f * k) + k * 2.4f
            val rr = 1.15f + 0.5f * sin(ph * 0.037f + k * 1.3f)
            val size = 0.34f - 0.1f * k
            val a = alpha * (0.7f + 0.3f * sin(ph * 0.11f + k * 2f))
            pen.flatColour(8f + cos(ang) * rr - 0.3f, sy + 0.06f, 8f + sin(ang) * rr * 0.8f, size, size, 0.785f, Gfx.argb(a, 1f, 1f, 0.95f))
        }
    }

    /** A ring spreading from a plop, as far as the pot's wall lets it. */
    private fun ripple(pen: Pen, rp: Ripple, age: Float, sy: Float, r: Float, g: Float, b: Float) {
        val u = age / StationAnim.RIPPLE_LIFE
        if (u < 0f || u > 1f) return
        val grow = 1f - (1f - u) * (1f - u)
        val radius = 0.25f + (1.3f + 1.4f * rp.power.coerceAtMost(1.5f)) * grow
        val alpha = 0.62f * (1f - u).pow(1.3f) * rp.power.coerceAtMost(1f)
        val width = 0.2f + 0.12f * u
        val colour = Gfx.argb(alpha, Gfx.mix(r, 1f, 0.55f), Gfx.mix(g, 1f, 0.55f), Gfx.mix(b, 1f, 0.55f))
        val segs = 12
        for (s in 0 until segs) {
            val a0 = Gfx.TAU * s / segs
            val a1 = Gfx.TAU * (s + 1) / segs
            val mid = (a0 + a1) * 0.5f
            val mx = rp.x + cos(mid) * radius - 8f
            val mz = rp.z + sin(mid) * radius - 8f
            if (mx * mx + mz * mz > INSIDE * INSIDE) continue
            pen.ring(rp.x, sy + 0.05f, rp.z, radius - width * 0.5f, radius + width * 0.5f, a0, a1, 1, colour)
        }
    }

    /**
     * Bubbles rising and breaking: each of up to eight keeps its own rhythm and place from the way it was numbered, so they never
     * jump, more as it boils, and, as it thickens, fewer, bigger, slower and beige.
     */
    private fun bubbles(pen: Pen, ph: Float, anim: StationAnim, sy: Float, energy: Float, thick: Float, r: Float, g: Float, b: Float) {
        val level = ((energy - 0.33f) / 0.5f).coerceIn(0f, 1f)
        val count = max(if (energy > 0.4f) 1 else 0, Math.round(MAX_BUBBLES * level * (1f - 0.55f * thick)))
        val speed = (0.55f + 0.9f * energy) * (1f - 0.7f * thick)
        val br = Gfx.mix(Gfx.mix(r, 1f, 0.45f), 0.88f, thick)
        val bg = Gfx.mix(Gfx.mix(g, 1f, 0.45f), 0.76f, thick)
        val bb = Gfx.mix(Gfx.mix(b, 1f, 0.45f), 0.52f, thick)
        for (i in 0 until count) {
            val period = (24f + 16f * Mix.unit(i, 1)) / speed
            val clock = ph / period + Mix.unit(i, 2)
            val cycle = floor(clock).toInt()
            val u = clock - cycle
            val ang = Mix.unit(i, cycle * 3 + 3) * Gfx.TAU
            val rad = 0.3f + 2.05f * kotlin.math.sqrt(Mix.unit(i, cycle * 3 + 4))
            val x = 8f + cos(ang) * rad
            val z = 8f + sin(ang) * rad
            val size = (0.26f + 0.2f * Mix.unit(i, cycle * 3 + 5)) * (1f + 0.9f * thick)
            if (u < 0.8f) {
                val grow = Gfx.smooth(u / 0.8f)
                val s = size * (0.25f + 0.75f * grow)
                pen.blob(x, sy + 0.07f, z, s, s, 0f, Gfx.argb(0.7f, br, bg, bb))
                pen.flatColour(x - s * 0.3f, sy + 0.08f, z - s * 0.3f, s * 0.28f, s * 0.28f, 0.785f, Gfx.argb(0.8f, 1f, 1f, 1f))
            } else {
                // It breaks: a ring opens and goes
                val k = (u - 0.8f) / 0.2f
                val s = size * (1.1f + 0.9f * k)
                pen.blob(x, sy + 0.07f, z, s, s, 0f, Gfx.argb(0.4f * (1f - k), br, bg, bb))
            }
        }
    }

    /** Foam climbing the rim: a cream sheet that rises with a ring of lumps round it, and lies over the rim when it is full. */
    private fun foam(pen: Pen, ph: Float, foam: Float, sy: Float, r: Float, g: Float, b: Float) {
        val e = Gfx.smooth(foam)
        val rim = CookGeo.POT_TOP
        val y = sy + 0.12f + (rim - 0.15f - sy - 0.12f) * e
        val cream = Gfx.argb((foam * 3f).coerceAtMost(1f) * 0.92f, Gfx.mix(0.96f, r, 0.1f), Gfx.mix(0.94f, g, 0.1f), Gfx.mix(0.88f, b, 0.1f))
        val hs = CookGeo.SURFACE_R * (0.4f + 0.6f * e)
        pen.blob(8f, y, 8f, hs, hs, ph * 0.02f, cream)
        val n = 9
        for (k in 0 until n) {
            val ang = k * Gfx.TAU / n + 0.4f * Mix.unit(k, 77)
            val rr = 1.2f + 1.35f * e
            val bob = 1f + 0.2f * sin(ph * 0.31f + k * 1.7f)
            val s = (0.45f + 0.35f * Mix.unit(k, 78)) * bob * (0.5f + 0.5f * e)
            pen.blob(8f + cos(ang) * rr, y + 0.03f, 8f + sin(ang) * rr, s, s, 0f, cream)
        }
        if (foam > 0.82f) {
            // Over the rim
            val over = ((foam - 0.82f) / 0.18f).coerceIn(0f, 1f)
            for (k in 0 until 8) {
                val ang = k * Gfx.TAU / 8 + 0.2f
                val s = (0.5f + 0.3f * Mix.unit(k, 79)) * (0.6f + 0.4f * over)
                pen.blob(8f + cos(ang) * 3.2f, rim + 0.06f, 8f + sin(ang) * 3.2f, s, s, 0f, Gfx.argb(0.95f * over, 0.97f, 0.95f, 0.9f))
            }
        }
    }

    /** The brown that creeps round the surface's edge as a pot sticks. */
    private fun wisp(pen: Pen, sy: Float, thick: Float, ph: Float) {
        val a = (0.4f + 0.55f * thick).coerceAtMost(0.95f)
        val end = -1.5708f + Gfx.TAU * thick.coerceAtMost(1f)
        val shimmer = 0.85f + 0.15f * sin(ph * 0.2f)
        pen.ring(8f, sy + 0.09f, 8f, 2.15f, 2.75f, -1.5708f, end, 16, Gfx.argb(a * shimmer, 0.5f, 0.3f, 0.14f), Gfx.argb(a * 0.2f, 0.62f, 0.4f, 0.2f))
    }

    /** A call answered: a ring of gold opens over the surface and goes. */
    private fun answer(pen: Pen, u: Float, sy: Float) {
        val radius = 0.4f + 2.3f * (1f - (1f - u) * (1f - u))
        pen.ring(8f, sy + 0.11f, 8f, radius - 0.14f, radius + 0.14f, 0f, Gfx.TAU, 16, Gfx.argb(0.7f * (1f - u), 1f, 0.88f, 0.5f))
    }

    /** Black blisters rising through a crust and breaking, slowly. */
    private fun blisters(pen: Pen, ph: Float, y: Float, crust: Float) {
        for (i in 0 until 3) {
            val clock = ph / (46f + 14f * i) + Mix.unit(i, 90)
            val cycle = floor(clock).toInt()
            val u = clock - cycle
            val ang = Mix.unit(i, cycle * 2 + 91) * Gfx.TAU
            val rad = 0.4f + 1.9f * Mix.unit(i, cycle * 2 + 92)
            val s = (0.3f + 0.25f * Mix.unit(i, cycle * 2 + 93)) * (if (u < 0.85f) Gfx.smooth(u / 0.85f) else 1f + (u - 0.85f) * 4f)
            val alpha = (if (u < 0.85f) 0.9f else 0.9f * (1f - (u - 0.85f) / 0.15f)) * crust
            pen.blob(8f + cos(ang) * rad, y, 8f + sin(ang) * rad, s, s, 0f, Gfx.argb(alpha, 0.08f, 0.06f, 0.05f))
            pen.flatColour(8f + cos(ang) * rad - s * 0.3f, y + 0.01f, 8f + sin(ang) * rad - s * 0.3f, s * 0.25f, s * 0.25f, 0.785f, Gfx.argb(alpha * 0.55f, 0.75f, 0.7f, 0.65f))
        }
    }

    companion object {
        /** An ingredient's picture is this far from its middle to each edge, px. */
        private const val HALF = 0.88f

        /** The most bubbles on a surface at once. */
        private const val MAX_BUBBLES = 8

        /** The surface's disc reaches this far from the middle, px, where a ring of ripples is let run. */
        private const val INSIDE = 2.7f

        /** The `ring` texture's ring lies this far out on its quad (a fraction of the half-size). */
        private const val RING_RADIUS = 0.81f

        private const val ANSWER_TICKS = 12f
        private const val MISS_TICKS = 34f
    }
}
