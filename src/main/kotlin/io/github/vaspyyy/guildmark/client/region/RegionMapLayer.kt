package io.github.vaspyyy.guildmark.client.region

import com.mojang.blaze3d.platform.NativeImage
import io.github.vaspyyy.guildmark.client.atlas.MapData
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.region.RegionKind
import io.github.vaspyyy.guildmark.region.RegionMap
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.world.level.Level
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The regions on the world map (M): their borders dotted in ink over the land you've seen, and the names
 * of those you've been in lettered across them in spaced capitals, larger and fainter the further out
 * the map is zoomed, gone when it's close in. The cursor's line under the map names the region too.
 */
object RegionMapLayer {
    private const val STEP = RegionMap.STEP
    private const val INK_R = 58f
    private const val INK_G = 38f
    private const val INK_B = 20f
    private const val INK_ALPHA = 0.5f

    /** New regions came in: the map's tiles need painting again to show their borders. */
    fun changed() {
        MapData.repaintAll()
    }

    private fun overworld(): Boolean = Minecraft.getInstance().level?.dimension() == Level.OVERWORLD

    /**
     * Dots the borders between regions over one tile of the map as MapData paints it: tile ([tx], [tz]),
     * a pixel every [step] samples. Each pixel goes to the region the four lattice points round it most
     * lean toward, so a border runs smoothly between them rather than in steps.
     */
    fun paint(image: NativeImage, tx: Int, tz: Int, step: Int) {
        if (!overworld() || RegionClient.plans().isEmpty()) return
        val size = image.width
        val blocks = MapData.SAMPLE * step
        val x0 = tx * MapData.TILE_BLOCKS
        val z0 = tz * MapData.TILE_BLOCKS

        // The lattice under the tile, with a point to spare round it
        val li = Math.floorDiv(x0 - STEP / 2, STEP) - 1
        val lj = Math.floorDiv(z0 - STEP / 2, STEP) - 1
        val lw = MapData.TILE_BLOCKS / STEP + 4
        val ids = LongArray(lw * lw)
        var any = false
        for (j in 0 until lw) for (i in 0 until lw) {
            val id = RegionClient.regionAt(li + i, lj + j)?.id ?: 0L
            ids[j * lw + i] = id
            if (id != 0L) any = true
        }
        if (!any) return

        // Each pixel's region (one row and column past the tile, to find its edges)
        val span = size + 1
        val regions = LongArray(span * span)
        val votes = LongArray(4)
        val weights = FloatArray(4)
        for (py in 0 until span) for (px in 0 until span) {
            val u = (x0 + px * blocks + blocks / 2f - STEP / 2f) / STEP - li
            val v = (z0 + py * blocks + blocks / 2f - STEP / 2f) / STEP - lj
            val i = floor(u).toInt().coerceIn(0, lw - 2)
            val j = floor(v).toInt().coerceIn(0, lw - 2)
            val fu = (u - i).coerceIn(0f, 1f)
            val fv = (v - j).coerceIn(0f, 1f)
            var count = 0
            fun vote(id: Long, weight: Float) {
                if (id == 0L || weight <= 0f) return
                for (k in 0 until count) if (votes[k] == id) {
                    weights[k] += weight
                    return
                }
                votes[count] = id
                weights[count] = weight
                count++
            }
            vote(ids[j * lw + i], (1 - fu) * (1 - fv))
            vote(ids[j * lw + i + 1], fu * (1 - fv))
            vote(ids[(j + 1) * lw + i], (1 - fu) * fv)
            vote(ids[(j + 1) * lw + i + 1], fu * fv)
            var best = 0L
            var bestWeight = -1f
            for (k in 0 until count) if (weights[k] > bestWeight) {
                best = votes[k]
                bestWeight = weights[k]
            }
            regions[py * span + px] = best
        }

        // Ink where a pixel's region differs from the next one's, in broken dots, over land only
        for (py in 0 until size) for (px in 0 until size) {
            val here = regions[py * span + px]
            if (here == 0L) continue
            val right = regions[py * span + px + 1]
            val down = regions[(py + 1) * span + px]
            if ((right == 0L || right == here) && (down == 0L || down == here)) continue
            val wx = x0 + px * blocks
            val wz = z0 + py * blocks
            if (hash(Math.floorDiv(wx, 6), Math.floorDiv(wz, 6)) and 3 == 0) continue
            val under = image.getPixel(px, py)
            if ((under ushr 24) < 80) continue
            ink(image, px, py, under)
        }
    }

    private fun ink(image: NativeImage, x: Int, y: Int, under: Int) {
        val alpha = INK_ALPHA
        val ua = (under ushr 24) / 255f * (1 - alpha)
        val out = alpha + ua
        fun mix(ink: Float, shift: Int): Int = ((ink * alpha + ((under shr shift) and 0xFF) * ua) / out).toInt().coerceIn(0, 255)
        image.setPixel(x, y, ((out * 255).toInt().coerceIn(0, 255) shl 24) or (mix(INK_R, 16) shl 16) or (mix(INK_G, 8) shl 8) or mix(INK_B, 0))
    }

    private fun hash(x: Int, z: Int): Int {
        var h = x * 374761393 + z * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    /**
     * The names of the regions the player has been in, lettered across the map under everything else on
     * it. [centerX] and [centerZ] are the world spot in the window's middle, [scale] GUI pixels per block.
     */
    fun drawNames(graphics: GuiGraphicsExtractor, font: Font, centerX: Double, centerZ: Double, scale: Double, left: Int, top: Int, right: Int, bottom: Int) {
        if (!overworld()) return
        // Faint and large far out, gone close in
        val zoom = ln(scale) / ln(2.0)
        val alpha = when {
            zoom <= -1.0 -> 0.8f
            zoom <= 0.0 -> (0.8 - (zoom + 1) * 0.25).toFloat()
            zoom <= 1.0 -> (0.55 - zoom * 0.35).toFloat()
            else -> 0f
        }
        if (alpha <= 0.05f) return
        val big = scale > 0.7
        val size = if (big) 2f else 1f
        val gap = if (big) 2 else 3
        val midX = (left + right) / 2.0
        val midY = (top + bottom) / 2.0
        val taken = ArrayList<IntArray>()
        val shown = RegionClient.plans().flatMap { it.regions }.filter { RegionClient.found(it.id) }
        for (region in shown) {
            val kind = RegionKind.byId(region.kind) ?: continue
            val sx = midX + (region.labelX + 0.5 - centerX) * scale
            val sy = midY + (region.labelZ + 0.5 - centerZ) * scale
            val letters = region.name.uppercase().map { Ui.heading(Component.literal(it.toString())) }
            val widths = letters.map { font.width(it) }
            val width = ((widths.sum() + gap * (letters.size - 1)) * size).roundToInt()
            val height = (Ui.HEADING_HEIGHT * size).roundToInt()
            val x = (sx - width / 2.0).roundToInt()
            val y = (sy - height / 2.0).roundToInt()
            if (x + width < left || x > right || y + height < top || y > bottom) continue
            val box = intArrayOf(x - 4, y - 2, x + width + 4, y + height + 2)
            if (taken.any { box[0] < it[2] && it[0] < box[2] && box[1] < it[3] && it[1] < box[3] }) continue
            taken.add(box)
            val color = ARGB.color(alpha, kind.ink)
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x.toFloat(), y.toFloat())
            pose.scale(size, size)
            var at = 0
            for ((index, letter) in letters.withIndex()) {
                graphics.text(font, letter, at, Ui.HEADING_RISE, color, false)
                at += widths[index] + gap
            }
            pose.popMatrix()
        }
    }

    /** [coordinates] with the name of the region at ([x], [z]) before them, if the player has been there. */
    fun withRegion(x: Int, z: Int, coordinates: Component): Component {
        if (!overworld()) return coordinates
        val region = RegionClient.regionAtBlock(x, z) ?: return coordinates
        if (!RegionClient.found(region.id)) return coordinates
        return Component.translatable("gui.guildmark.map.cursor_region", region.name, coordinates)
    }
}
