package io.github.vaspyyy.guildmark.client.season

import io.github.vaspyyy.guildmark.season.SeasonDate
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The land's colours through the year. Grass, ferns, vines, sugar cane, flowers' leaves and the tinted leaves keep
 * vanilla's colour for their biome and then take the season's turn on it: fresh light greens in spring, deep greens
 * in summer, golds, oranges and reds in autumn (each tree its own), pale and frosted in winter. Birch goes gold in
 * autumn; spruce only frosts. How strongly a place turns comes from [CLIMATE], a colour resolver like vanilla's own,
 * so it blends over biome borders and is cached with them: the jungle, the deserts, the savanna, the badlands and the
 * frozen north barely change.
 *
 * The meshing threads read [look] without locks; a new day swaps it for a new one and re-meshes the land round the
 * player a ring of chunks at a time. A getter that isn't the game's own world or a chunk being meshed (a far-view
 * mod's) gets vanilla's colours.
 */
object SeasonColours {
    /** How one kind of plant looks today. */
    class Shift(
        /** Hue the colour is pulled toward (degrees), and how hard (0 none, 1 all the way). */
        val hue: Float, val pull: Float,
        val saturation: Float, val value: Float,
        /** A colour mixed in over the top (frost, straw), and how much. */
        val tint: Int, val tintMix: Float,
        /** Autumn's tree-to-tree variety: the hue a tree is pulled toward runs from [hue] to [hue] + [spread]. */
        val spread: Float = 0f,
    ) {
        fun apply(rgb: Int, weight: Float, variety: Float): Int {
            if (weight <= 0.01f) return rgb
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val d = hi - lo
            var h = when {
                d < 1e-5f -> 0f
                hi == r -> 60f * (((g - b) / d) % 6f)
                hi == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (h < 0f) h += 360f
            var s = if (hi <= 0f) 0f else d / hi
            var v = hi
            val target = hue + spread * variety
            val dh = ((target - h + 540f) % 360f) - 180f
            h += dh * pull * weight
            s = (s * (1f + (saturation - 1f) * weight)).coerceIn(0f, 1f)
            v = (v * (1f + (value - 1f) * weight)).coerceIn(0f, 1f)
            // Back to rgb, without making anything: this runs for every tinted face of every chunk meshed
            h = ((h % 360f) + 360f) % 360f
            val c = v * s
            val x = c * (1f - abs((h / 60f) % 2f - 1f))
            val m = v - c
            var ro = m
            var go = m
            var bo = m
            when ((h / 60f).toInt()) {
                0 -> { ro += c; go += x }
                1 -> { ro += x; go += c }
                2 -> { go += c; bo += x }
                3 -> { go += x; bo += c }
                4 -> { ro += x; bo += c }
                else -> { ro += c; bo += x }
            }
            val k = tintMix * weight
            if (k > 0f) {
                ro += ((tint shr 16 and 255) / 255f - ro) * k
                go += ((tint shr 8 and 255) / 255f - go) * k
                bo += ((tint and 255) / 255f - bo) * k
            }
            return (0xFF shl 24) or (channel(ro) shl 16) or (channel(go) shl 8) or channel(bo)
        }

        private fun channel(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        /** Between this and [other], [t] of the way. */
        fun toward(other: Shift, t: Float): Shift {
            if (t <= 0f) return this
            if (t >= 1f) return other
            // The hue is pulled by how hard each pulls, toward the nearer of the two targets
            val dh = ((other.hue - hue + 540f) % 360f) - 180f
            return Shift(
                hue + dh * t, pull + (other.pull - pull) * t,
                saturation + (other.saturation - saturation) * t, value + (other.value - value) * t,
                mixColour(tint, other.tint, if (tintMix + other.tintMix <= 0f) 0.5f else (other.tintMix * t) / (tintMix * (1 - t) + other.tintMix * t)),
                tintMix + (other.tintMix - tintMix) * t, spread + (other.spread - spread) * t,
            )
        }

        /** Whether [other] turns a colour just as this does. */
        fun same(other: Shift): Boolean = hue == other.hue && pull == other.pull && saturation == other.saturation &&
            value == other.value && tint == other.tint && tintMix == other.tintMix && spread == other.spread
    }

    /** A day's looks: one shift for each kind of plant. */
    class Look(val active: Boolean, val grass: Shift, val foliage: Shift, val birch: Shift, val evergreen: Shift, val cane: Shift) {
        /** Whether [other] colours the land just as this does (the days of a season's middle third all look alike). */
        fun same(other: Look): Boolean = active == other.active && grass.same(other.grass) && foliage.same(other.foliage) &&
            birch.same(other.birch) && evergreen.same(other.evergreen) && cane.same(other.cane)
    }

    private val NONE = Shift(0f, 0f, 1f, 1f, 0, 0f)

    // ---- The seasons' keys, each at its season's middle ----
    private val GRASS = arrayOf(
        Shift(86f, 0.35f, 1.14f, 1.10f, 0, 0f),                 // spring: fresh, light, yellow-green
        Shift(104f, 0.18f, 1.06f, 0.88f, 0, 0f),                // summer: deep
        Shift(46f, 0.55f, 0.92f, 0.94f, 0xB8A060, 0.18f),       // autumn: gold going to straw
        Shift(95f, 0.1f, 0.42f, 1.06f, 0xC9D4D2, 0.40f),        // winter: pale, frosted
    )
    private val FOLIAGE = arrayOf(
        Shift(90f, 0.35f, 1.12f, 1.12f, 0, 0f),
        Shift(108f, 0.18f, 1.06f, 0.86f, 0, 0f),
        Shift(6f, 0.9f, 1.45f, 1.18f, 0, 0f, spread = 44f),     // reds, oranges and golds, tree by tree
        Shift(36f, 0.45f, 0.38f, 0.96f, 0xB6C2BF, 0.32f),       // the last brown leaves under frost
    )
    private val BIRCH = arrayOf(
        Shift(84f, 0.3f, 1.10f, 1.10f, 0, 0f),
        Shift(98f, 0.1f, 1.02f, 0.92f, 0, 0f),
        Shift(44f, 0.95f, 1.6f, 1.3f, 0, 0f, spread = 8f),      // birch gold
        Shift(48f, 0.4f, 0.4f, 1.02f, 0xC2CCC8, 0.36f),
    )
    private val EVERGREEN = arrayOf(
        Shift(118f, 0.1f, 1.06f, 1.04f, 0, 0f),
        Shift(118f, 0.0f, 1.0f, 0.96f, 0, 0f),
        Shift(118f, 0.0f, 0.95f, 0.96f, 0, 0f),
        Shift(140f, 0.12f, 0.8f, 1.06f, 0xC6D2D0, 0.24f),      // frosted needles
    )

    /** Today's look, read by the meshing threads. */
    @Volatile
    var look: Look = Look(false, NONE, NONE, NONE, NONE, NONE)
        private set

    /** The look for [date]: the two nearest seasons' keys, blended with a plateau at each season's middle third. */
    fun lookFor(date: SeasonDate, active: Boolean): Look {
        if (!active) return Look(false, NONE, NONE, NONE, NONE, NONE)
        val p = date.position - 0.5
        val k0 = Math.floorMod(floor(p).toInt(), 4)
        val k1 = (k0 + 1) % 4
        val f = (p - floor(p)).toFloat()
        val t = ((f - 1f / 6f) / (2f / 3f)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        fun blend(keys: Array<Shift>) = keys[k0].toward(keys[k1], t)
        val grass = blend(GRASS)
        return Look(true, grass, blend(FOLIAGE), blend(BIRCH), blend(EVERGREEN), NONE.toward(grass, 0.5f))
    }

    // ---- How much a place turns ----

    /**
     * For each column: in red, how much the seasons are felt there (Seasons.temperate); in green, a slow noise that
     * gives each tree its own autumn. Vanilla blends a resolver's channels over the biome blend radius.
     */
    val CLIMATE: ColorResolver = ColorResolver { biome, x, z ->
        val weight = Seasons.temperate(biome)
        val variety = variety(x, z)
        (channel255(weight) shl 16) or (channel255(variety) shl 8)
    }

    private fun channel255(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** Value noise in cells of [VARIETY_CELL] blocks, smoothly interpolated. */
    private fun variety(x: Double, z: Double): Float {
        val gx = x / VARIETY_CELL
        val gz = z / VARIETY_CELL
        val x0 = floor(gx).toInt()
        val z0 = floor(gz).toInt()
        val fx = (gx - x0).toFloat().let { it * it * (3f - 2f * it) }
        val fz = (gz - z0).toFloat().let { it * it * (3f - 2f * it) }
        val a = hash(x0, z0)
        val b = hash(x0 + 1, z0)
        val c = hash(x0, z0 + 1)
        val d = hash(x0 + 1, z0 + 1)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fz
    }

    private fun hash(x: Int, z: Int): Float {
        var h = x * 374761393 + z * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private const val VARIETY_CELL = 5.0

    // ---- The tint sources ----

    private enum class Kind { GRASS, FOLIAGE, BIRCH, EVERGREEN, CANE }

    /** A vanilla tint source with the season laid over it. */
    private class Seasonal(private val vanilla: BlockTintSource, private val kind: Kind) : BlockTintSource {
        override fun color(state: BlockState): Int = vanilla.color(state)

        override fun colorInWorld(state: BlockState, getter: BlockAndTintGetter, pos: BlockPos): Int =
            seasonal(vanilla.colorInWorld(state, getter, pos), getter, pos)

        override fun colorAsTerrainParticle(state: BlockState, getter: BlockAndTintGetter, pos: BlockPos): Int =
            seasonal(vanilla.colorAsTerrainParticle(state, getter, pos), getter, pos)

        override fun relevantProperties(): Set<Property<*>> = vanilla.relevantProperties()

        private fun seasonal(base: Int, getter: BlockAndTintGetter, pos: BlockPos): Int {
            val now = look
            if (!now.active || foreign(getter)) return base
            val climate = getter.getBlockTint(pos, CLIMATE)
            val weight = (climate shr 16 and 255) / 255f
            if (weight < 0.02f) return base
            val variety = (climate shr 8 and 255) / 255f
            val shift = when (kind) {
                Kind.GRASS -> now.grass
                Kind.FOLIAGE -> now.foliage
                Kind.BIRCH -> now.birch
                Kind.EVERGREEN -> now.evergreen
                Kind.CANE -> now.cane
            }
            return shift.apply(base, weight, variety)
        }
    }

    /** Getters of other mods' far views keep vanilla's colours (their land is drawn once and kept). */
    private val foreignGetters = ConcurrentHashMap<Class<*>, Boolean>()

    private fun foreign(getter: BlockAndTintGetter): Boolean = foreignGetters.computeIfAbsent(getter.javaClass) { type ->
        val name = type.name
        name.startsWith("com.seibel.") || name.contains("distanthorizons", ignoreCase = true) || name.contains("voxy", ignoreCase = true)
    }

    fun registerResolver(event: RegisterColorHandlersEvent.ColorResolvers) {
        event.register(CLIMATE)
    }

    fun registerSources(event: RegisterColorHandlersEvent.BlockTintSources) {
        val colors = event.blockColors
        fun wrap(kind: Kind, vararg blocks: Block) {
            for (block in blocks) {
                val vanilla = colors.getTintSources(block.defaultBlockState())
                if (vanilla.isEmpty()) continue
                // Flower beds keep their petals' own layer and turn only their leaves
                val wrapped = vanilla.mapIndexed { i, source -> if (vanilla.size > 1 && i == 0) source else Seasonal(source, kind) }
                event.register(wrapped, block)
            }
        }
        wrap(Kind.GRASS, Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.POTTED_FERN, Blocks.BUSH, Blocks.TALL_GRASS,
            Blocks.LARGE_FERN, Blocks.PINK_PETALS, Blocks.WILDFLOWERS)
        wrap(Kind.FOLIAGE, Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES, Blocks.VINE)
        wrap(Kind.BIRCH, Blocks.BIRCH_LEAVES)
        wrap(Kind.EVERGREEN, Blocks.SPRUCE_LEAVES)
        wrap(Kind.CANE, Blocks.SUGAR_CANE)
    }

    // ---- Changing over ----

    /** Chunk columns still to be re-meshed, nearest first, as ring numbers to go. */
    private var ring = -1
    private var ringCentreX = 0
    private var ringCentreZ = 0

    /** A new day (or a new season, a command, a login): today's look, and the land round about re-meshed if it changed. */
    fun update(date: SeasonDate, active: Boolean) {
        val next = lookFor(date, active)
        val was = look
        look = next
        if ((was.active || next.active) && !was.same(next)) startRemesh()
    }

    fun reset() {
        look = Look(false, NONE, NONE, NONE, NONE, NONE)
        ring = -1
    }

    private fun startRemesh() {
        val player = Minecraft.getInstance().player ?: return
        ringCentreX = SectionPos.blockToSectionCoord(player.blockX)
        ringCentreZ = SectionPos.blockToSectionCoord(player.blockZ)
        ring = 0
    }

    /** Every client tick: the next ring of chunks out, until the render distance is passed. */
    fun tick() {
        if (ring < 0) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: run { ring = -1; return }
        val reach = minecraft.options.renderDistance().get() + 1
        val minY = level.minSectionY
        val maxY = level.maxSectionY
        // Two rings a tick: the land round the player turns over in about a second
        repeat(2) {
            if (ring > reach) {
                ring = -1
                return
            }
            val r = ring
            val cx = ringCentreX
            val cz = ringCentreZ
            if (r == 0) {
                level.setSectionRangeDirty(cx, minY, cz, cx, maxY, cz)
            } else {
                level.setSectionRangeDirty(cx - r, minY, cz - r, cx + r, maxY, cz - r)
                level.setSectionRangeDirty(cx - r, minY, cz + r, cx + r, maxY, cz + r)
                level.setSectionRangeDirty(cx - r, minY, cz - r + 1, cx - r, maxY, cz + r - 1)
                level.setSectionRangeDirty(cx + r, minY, cz - r + 1, cx + r, maxY, cz + r - 1)
            }
            ring++
        }
    }

    private fun mixColour(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = a shr shift and 255
            val y = b shr shift and 255
            return (x + (y - x) * k).toInt() and 255
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
