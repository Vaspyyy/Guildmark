package io.github.vaspyyy.guildmark.homestead.plan

import io.github.vaspyyy.guildmark.city.plan.At
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Building
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.FarmBuilding
import io.github.vaspyyy.guildmark.city.plan.FarmBuildings
import io.github.vaspyyy.guildmark.city.plan.FarmKind
import io.github.vaspyyy.guildmark.city.plan.FarmProp
import io.github.vaspyyy.guildmark.city.plan.FarmPropKind
import io.github.vaspyyy.guildmark.city.plan.FarmProps
import io.github.vaspyyy.guildmark.city.plan.Folk
import io.github.vaspyyy.guildmark.city.plan.Frame
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.Land
import io.github.vaspyyy.guildmark.city.plan.Landmarks
import io.github.vaspyyy.guildmark.city.plan.Loot
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Spawn
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where everything on a homestead stands, in the plot's own frame: x runs across the plot (east, as the city plan
 * names it), z from the front edge (0, where the Charter Post stands, the road outside it to the north) to the
 * back, and y is the world's own height. The post stands at ([POST_X], 0), the supply crate at ([CRATE_X], 0), the
 * gate between them on x = 0.
 */
object PlotShape {
    const val POST_X = -2
    const val CRATE_X = 2

    /** The whole of the land a charter takes, the manor's included: nobody else may claim in it. */
    val RESERVED = Rect(-24, 0, 24, 48)

    /** The highest tier: the manor. */
    const val TOP_TIER = 3

    /** The plot a tier has the use of, growing as the homestead does (the staked plot and the cottage share one). */
    fun plot(tier: Int): Rect = when {
        tier <= 1 -> Rect(-10, 0, 10, 20)
        tier == 2 -> Rect(-19, 0, 19, 37)
        else -> RESERVED
    }

    /** The four corners of a tier's plot, where its stakes stand. */
    fun corners(tier: Int): List<Pair<Int, Int>> = plot(tier).let { listOf(it.x0 to it.z0, it.x1 to it.z0, it.x0 to it.z1, it.x1 to it.z1) }
}

/**
 * The land under a plot as it was when it was claimed, so that a tier's plan always comes out the same however the
 * ground has been built on since: standing heights and wet columns over [PlotShape.RESERVED] (anything asked for
 * outside it is taken from the nearest edge).
 */
class PlotLand(val heights: IntArray, val wets: BooleanArray) : Land {
    init {
        require(heights.size == SIZE * SIZE && wets.size == SIZE * SIZE)
    }

    override fun height(x: Int, z: Int): Int = heights[index(x, z)]
    override fun wet(x: Int, z: Int): Boolean = wets[index(x, z)]
    override val sea: Int get() = heights[index(0, 0)]

    /** The land's height smoothed over the 3x3 round a column. */
    fun smooth(x: Int, z: Int): Int {
        var sum = 0
        for (dx in -1..1) for (dz in -1..1) sum += height(x + dx, z + dz)
        return Math.floorDiv(sum + 4, 9)
    }

    /** The middle height of the land under [r] (its median). */
    fun lie(r: Rect): Int {
        val all = IntArray(r.width * r.depth)
        var n = 0
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) all[n++] = height(x, z)
        all.sort()
        return all[n / 2]
    }

    /** How far the land under [r] strays from [y] at most. */
    fun stray(r: Rect, y: Int): Int {
        var most = 0
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) most = max(most, abs(height(x, z) - y))
        return most
    }

    /** Whether any column under [r] is under water. */
    fun wet(r: Rect): Boolean {
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) if (wet(x, z)) return true
        return false
    }

    companion object {
        const val SIZE = 49

        /** The cell of a column, clamped into the reserved square. */
        fun index(x: Int, z: Int): Int =
            (x.coerceIn(PlotShape.RESERVED.x0, PlotShape.RESERVED.x1) - PlotShape.RESERVED.x0) * SIZE +
                (z.coerceIn(PlotShape.RESERVED.z0, PlotShape.RESERVED.z1) - PlotShape.RESERVED.z0)

        /** Land read column by column with [read] (standing height, wet) for every column of the reserved square. */
        fun read(read: (x: Int, z: Int) -> Pair<Int, Boolean>): PlotLand {
            val heights = IntArray(SIZE * SIZE)
            val wets = BooleanArray(SIZE * SIZE)
            for (x in PlotShape.RESERVED.x0..PlotShape.RESERVED.x1) for (z in PlotShape.RESERVED.z0..PlotShape.RESERVED.z1) {
                val (h, w) = read(x, z)
                heights[index(x, z)] = h
                wets[index(x, z)] = w
            }
            return PlotLand(heights, wets)
        }
    }
}

/** What a tier gives the Living World to work with, in the plot's frame (see Homestead.places). */
class TierPlaces(
    /** The crop beds inside the field fences. */
    val fields: List<Rect>,
    /** The ground inside the pens' fences. */
    val pens: List<Rect>,
    /** Where shipping crates stand: (x, z) at the ground. */
    val shipping: List<Pair<Int, Int>>,
)

/** A lot a claim needs fairly level land for: it's refused when the land under [lot] strays more than [most] from [y]. */
class LevelCheck(val lot: Rect, val y: Int, val most: Int, val what: String)

/**
 * A homestead's buildings and grounds, tier by tier, drawn into a [PlanCanvas]: a cottage in a fenced garden first;
 * then a farmhouse and a great barn either side of it with a field, a pen with its coop and a yard with a well behind;
 * last a manor at the back between the field and the pen, an orchard down one side, a stable, a second field and a
 * wayside shrine. Each tier only adds: nothing an earlier one put up is ever drawn over, and the land an earlier tier
 * levelled or built on is never banked again. The buildings are the country's own (FarmBuildings, FarmProps) and a
 * capital's manor (Landmarks); this lays out the ground between them. [seed] turns the farmstead round (the farmhouse
 * on the left or the right) and is every building's seed; [owner] colours the stakes' banners.
 */
class HomesteadLayout(private val seed: Long, private val land: PlotLand, private val owner: Dye = Dye.RED) {
    private val mirror = (seed ushr 11) and 1L == 1L

    private fun mx(x: Int) = if (mirror) -x else x
    private fun mdir(d: Dir) = if (mirror && d.alongX) d.opposite else d
    private fun rect(x0: Int, z0: Int, x1: Int, z1: Int) = if (mirror) Rect(-x1, z0, -x0, z1) else Rect(x0, z0, x1, z1)

    // ---- Where things go ----

    private val cottageLot = Rect(-4, 8, 4, 14)
    private val garden = Rect(-7, 2, 7, 20)
    private val well = rect(-9, 17, -9, 17)
    private val farmhouseLot = rect(-16, 4, -10, 12)
    private val barnLot = rect(10, 4, 16, 18)
    private val yard = Rect(-9, 21, 9, 25)
    private val fieldA = rect(-17, 22, -11, 35)
    private val penRing = rect(10, 22, 18, 36)
    private val coopLot = rect(14, 32, 16, 34)
    private val manorLot = Rect(-7, 27, 7, 47)
    private val fieldB = rect(-17, 40, -11, 46)
    private val stableLot = rect(13, 41, 23, 45)

    // ---- The heights everything stands at: the yard steps once from the garden, the manor once from the yard ----

    private val gy = land.lie(garden)
    private val wy = land.lie(well.grow(1))
    private val hy = land.lie(farmhouseLot.grow(2))
    private val by = land.lie(barnLot.grow(2))
    private val yy = land.lie(yard).coerceIn(gy - 1, gy + 1)
    private val fy = land.lie(fieldA.grow(1))
    private val cy = land.lie(coopLot.grow(1))
    private val my = land.lie(manorLot).coerceIn(yy - 1, yy + 1)
    private val sy = land.lie(stableLot.grow(1))
    private val fy2 = land.lie(fieldB.grow(1))

    /**
     * What a claim needs of the land: every lot that is levelled, and how far its land may stray from the level it
     * gets before a building would stand on a cliff or in a pit (the fields, which only lie on it, may stray more).
     */
    fun checks(): List<LevelCheck> = listOf(
        LevelCheck(garden, gy, 4, "cottage"), LevelCheck(farmhouseLot.grow(2), hy, 4, "farmhouse"),
        LevelCheck(barnLot.grow(2), by, 4, "barn"), LevelCheck(yard, yy, 4, "yard"),
        LevelCheck(fieldA.grow(1), fy, 5, "field"), LevelCheck(coopLot.grow(1), cy, 4, "coop"),
        LevelCheck(manorLot, my, 5, "manor"), LevelCheck(stableLot.grow(1), sy, 4, "stable"),
        LevelCheck(fieldB.grow(1), fy2, 5, "field"),
    )

    /** What tiers up to [tier] give the Living World: fields, pens and shipping crates. */
    fun places(tier: Int): TierPlaces {
        val fields = ArrayList<Rect>()
        val pens = ArrayList<Rect>()
        val shipping = ArrayList<Pair<Int, Int>>()
        if (tier >= 1) fields.add(KITCHEN_BED)
        if (tier >= 2) {
            fields.add(fieldA)
            pens.add(penRing.grow(-1))
            shipping.add(mx(7) to 21)
        }
        if (tier >= 3) {
            fields.add(fieldB)
            shipping.add(mx(-12) to 37)
        }
        return TierPlaces(fields, pens, shipping)
    }

    // ---- The ground as the layout has left it ----

    /** The standing height of every column after what has been drawn so far (the earlier tiers' included). */
    private val terrain = IntArray(PlotLand.SIZE * PlotLand.SIZE)

    /** Columns something has been levelled or built on: no bank of a later lot is cut into them. */
    private val owned = BooleanArray(PlotLand.SIZE * PlotLand.SIZE)

    /** The tier being drawn. */
    private var tier = 0

    private fun height(x: Int, z: Int): Int = terrain[PlotLand.index(x, z)]
    private fun owned(x: Int, z: Int): Boolean = owned[PlotLand.index(x, z)]

    /**
     * Draws what tier [tier] adds (1 the cottage, 2 the farmstead, 3 the manor; 0 only stakes the plot). The tiers
     * before it are walked through first, drawing nothing, so that this one knows the ground they left.
     */
    fun draw(tier: Int, c: PlanCanvas) {
        land.heights.copyInto(terrain)
        owned.fill(false)
        // The post and the crate at the front stand on the land as it was
        owned[PlotLand.index(PlotShape.POST_X, 0)] = true
        owned[PlotLand.index(PlotShape.CRATE_X, 0)] = true
        for (t in 0 until tier) drawTier(t, Tracked(Discard))
        drawTier(tier, Tracked(c))
    }

    private fun drawTier(t: Int, c: PlanCanvas) {
        tier = t
        stakes(t, c)
        when (t) {
            1 -> cottage(c)
            2 -> farmstead(c)
            3 -> manor(c)
        }
    }

    /** A canvas a tier is drawn through, keeping [terrain] and [owned] up to date as it goes. */
    private inner class Tracked(private val c: PlanCanvas) : PlanCanvas by c {
        override fun set(x: Int, y: Int, z: Int, b: B) {
            owned[PlotLand.index(x, z)] = true
            c.set(x, y, z, b)
        }

        override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) {
            val i = PlotLand.index(x, z)
            owned[i] = true
            terrain[i] = y
            c.ground(x, z, y, surface, fill)
        }

        override fun clearing(x: Int, z: Int, y: Int) {
            val i = PlotLand.index(x, z)
            if (owned[i]) return
            terrain[i] = y
            c.clearing(x, z, y)
        }

        override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) {
            owned[PlotLand.index(x, z)] = true
            c.chest(x, y, z, facing, loot)
        }
    }

    // ---- The stakes ----

    /** The plot's corners staked out for [tier], and the last tier's stakes pulled up where the plot has grown. */
    private fun stakes(tier: Int, c: PlanCanvas) {
        val now = PlotShape.corners(tier)
        if (tier >= 2) for ((x, z) in PlotShape.corners(tier - 1)) {
            if ((x to z) in now) continue
            val y = stakeHeight(x, z)
            c.piece(PieceKind.STAKE, Rect(x, z, x, z), y)
            c.set(x, y, z, B.AIR)
            c.set(x, y + 1, z, B.AIR)
        }
        for ((x, z) in now) {
            val y = stakeHeight(x, z)
            c.piece(PieceKind.STAKE, Rect(x, z, x, z), y)
            c.set(x, y, z, B(M.FENCE))
            c.set(x, y + 1, z, B(M.BANNER, color = owner))
        }
    }

    /** The height a stake at the corner [x], [z] stands at: the land's own, as nothing ever levels a corner. */
    fun stakeHeight(x: Int, z: Int): Int = land.height(x, z)

    // ---- Tier 1: the cottage ----

    private fun cottage(c: PlanCanvas) {
        val y = gy
        // The garden levelled, the cottage's lot laid with a footing; the land banked round them
        c.piece(PieceKind.YARD, garden, y)
        level(c, garden, y, B(M.GRASS))
        for (x in cottageLot.x0..cottageLot.x1) for (z in cottageLot.z0..cottageLot.z1) c.ground(x, z, y, B(M.FOUNDATION), SOIL)
        // The path in from the front: through the gate to the door
        c.piece(PieceKind.PATH, Rect(0, 0, 0, 7), y)
        trail(c, listOf(0 to 0, 0 to 1, 0 to 2), mapOf(2 to y))
        for (z in 3..7) path(c, 0, z, y)

        c.piece(PieceKind.HOUSE, cottageLot, y)
        FarmBuildings(Frame(c, cottageLot, Dir.N, y), FarmBuilding(FarmKind.COTTAGE, cottageLot, Dir.N, y, seed), Random(seed)).draw()

        // Flowers either side of the path, a vegetable patch and the compost behind the house, a woodpile at its side
        c.piece(PieceKind.GARDEN, garden, y)
        val r = Random(seed * 31 + 7)
        for (x in -6..6) for (z in 3..6) {
            if (abs(x) < 2) continue
            if (r.nextInt(5) == 0) continue
            c.set(x, y, z, B(M.FLOWER, v = r.nextInt(5)))
        }
        for (x in KITCHEN_BED.x0..KITCHEN_BED.x1) for (z in KITCHEN_BED.z0..KITCHEN_BED.z1) {
            c.set(x, y - 1, z, B(M.FARMLAND))
            c.set(x, y, z, B(M.CROP, v = (x + 3) / 3 % 4 + 4 * r.nextInt(2)))
        }
        props(c).let { p ->
            p.draw(FarmProp(FarmPropKind.COMPOST, -5, 18, y, Dir.E, 0))
            p.draw(FarmProp(FarmPropKind.WOODPILE, 6, 13, y, Dir.N))
        }

        // The fence round it all, a gate in front with a lamp post each side of it
        c.piece(PieceKind.FENCE, garden, y)
        fenceRing(c, garden, y, gates = listOf(0 to 2), gateFacing = Dir.N)
        props(c).let { p ->
            p.draw(FarmProp(FarmPropKind.LAMP, -1, 2, y))
            p.draw(FarmProp(FarmPropKind.LAMP, 1, 2, y))
        }

        // A well out to one side, behind the corner of the garden
        c.piece(PieceKind.PROP, well.grow(1), wy)
        level(c, well.grow(1), wy, B(M.GRASS))
        props(c).draw(FarmProp(FarmPropKind.WELL, well.x0, well.z0, wy))
    }

    // ---- Tier 2: the farmstead ----

    private fun farmstead(c: PlanCanvas) {
        // The farmhouse facing in across the front, the barn facing it from the other side, the yard behind the
        // cottage, the field levelled, the coop's corner of the pen
        c.piece(PieceKind.YARD, farmhouseLot.grow(2), hy)
        level(c, farmhouseLot.grow(2), hy, B(M.GRASS))
        footing(c, farmhouseLot, hy)
        c.piece(PieceKind.YARD, barnLot.grow(2), by)
        level(c, barnLot.grow(2), by, B(M.GRASS))
        footing(c, barnLot, by)
        c.piece(PieceKind.YARD, yard, yy)
        level(c, yard, yy, B(M.GRASS))
        c.piece(PieceKind.FIELD, fieldA.grow(1), fy)
        level(c, fieldA.grow(1), fy, B(M.GRASS))
        c.piece(PieceKind.YARD, coopLot.grow(1), cy)
        level(c, coopLot.grow(1), cy, B(M.GRASS))
        footing(c, coopLot, cy)

        // Lanes: along the front behind the post and the crate, to each door, out back through the garden, and
        // from the yard's corners to the gates of the field and the pen
        c.piece(PieceKind.PATH, Rect(-9, 1, 9, 1), height(0, 1))
        trail(c, (0 downTo -9).map { it to 1 })
        trail(c, (0..9).map { it to 1 })
        trail(c, (1..8).map { mx(-8) to it } + (mx(-9) to 8))
        trail(c, (1..11).map { mx(8) to it } + (mx(9) to 11))
        path(c, 0, 19, gy)
        path(c, 0, 20, gy)
        path(c, 0, 21, yy)
        trail(c, (25..28).map { mx(-9) to it }, mapOf(3 to fy))
        trail(c, (25..28).map { mx(9) to it }, mapOf(3 to height(mx(10), 28)))

        c.piece(PieceKind.HOUSE, farmhouseLot, hy)
        FarmBuildings(Frame(c, farmhouseLot, mdir(Dir.E), hy), FarmBuilding(FarmKind.FARMHOUSE, farmhouseLot, mdir(Dir.E), hy, seed + 1), Random(seed + 1)).draw()
        c.piece(PieceKind.HOUSE, barnLot, by)
        FarmBuildings(Frame(c, barnLot, mdir(Dir.W), by), FarmBuilding(FarmKind.BARN, barnLot, mdir(Dir.W), by, seed + 2), Random(seed + 2)).draw()
        c.piece(PieceKind.HOUSE, coopLot, cy)
        FarmBuildings(Frame(c, coopLot, mdir(Dir.W), cy), FarmBuilding(FarmKind.COOP, coopLot, mdir(Dir.W), cy, seed + 3), Random(seed + 3)).draw()

        // The pen: a fence over the ground as it lies, a gate onto the yard's lane, a trough
        c.piece(PieceKind.PEN, penRing, land.lie(penRing))
        fenceRing(c, penRing, null, gates = listOf(mx(10) to 28), gateFacing = mdir(Dir.W))
        props(c).draw(FarmProp(FarmPropKind.TROUGH, mx(12), 24, height(mx(12), 24), Dir.N))

        // The field: rows of crops either side of a channel of water down the middle, fenced, a gate onto the lane
        c.piece(PieceKind.FIELD, fieldA, fy)
        crops(c, fieldA, fy, mx(-14), Random(seed * 17 + 3))
        c.piece(PieceKind.FENCE, fieldA.grow(1), fy)
        fenceRing(c, fieldA.grow(1), fy, gates = listOf(mx(-10) to 28), gateFacing = mdir(Dir.E))

        // The yard's things, a haystack by the farmhouse and one in the pen, and the back gate of the garden
        c.piece(PieceKind.PROP, yard, yy)
        props(c).let { p ->
            p.draw(FarmProp(FarmPropKind.WELL, mx(-3), 23, yy))
            p.draw(FarmProp(FarmPropKind.CART, mx(4), 23, yy, Dir.N, 1))
            p.draw(FarmProp(FarmPropKind.HAYSTACK, mx(-14), 17, pad(c, mx(-14), 17), Dir.N, 0))
            p.draw(FarmProp(FarmPropKind.HAYSTACK, mx(16), 26, height(mx(16), 26), Dir.N, 1))
        }
        c.piece(PieceKind.FENCE, garden, gy)
        c.set(0, gy, 20, B(M.FENCE_GATE, Dir.S))

        // The beasts: sheep or pigs and a pair of cows in the pen, hens by the coop
        c.piece(PieceKind.PEN, penRing, land.lie(penRing))
        val r = Random(seed * 13 + 5)
        val penned = if (r.nextBoolean()) Spawn.SHEEP else Spawn.PIG
        for ((x, z) in listOf(12 to 27, 13 to 28, 14 to 27)) c.spawn(penned, mx(x), height(mx(x), z), z, Dir.entries[r.nextInt(4)])
        for ((x, z) in listOf(11 to 30, 16 to 29)) c.spawn(Spawn.COW, mx(x), height(mx(x), z), z, Dir.entries[r.nextInt(4)])
        for (z in 33..35) c.spawn(Spawn.CHICKEN, mx(12), height(mx(12), z), z, Dir.entries[r.nextInt(4)])
    }

    // ---- Tier 3: the manor ----

    private fun manor(c: PlanCanvas) {
        c.piece(PieceKind.YARD, manorLot, my)
        level(c, manorLot, my, B(M.GRASS))
        c.piece(PieceKind.YARD, stableLot.grow(1), sy)
        level(c, stableLot.grow(1), sy, B(M.GRASS))
        footing(c, stableLot, sy)
        c.piece(PieceKind.FIELD, fieldB.grow(1), fy2)
        level(c, fieldB.grow(1), fy2, B(M.GRASS))

        // The lane on across the yard to the manor's gate; the ways down either side of it, past the fields' gates
        // on the one hand and on to the stable on the other
        c.piece(PieceKind.PATH, Rect(-9, 22, 9, 46), my)
        for (z in 22..25) path(c, 0, z, yy)
        path(c, 0, 26, height(0, 26).coerceIn(max(yy, my) - 1, min(yy, my) + 1))
        val fieldSide = mx(-9)
        trail(c, (28..44).map { fieldSide to it }, mapOf(0 to fy, 15 to fy2))
        val stableSide = mx(9)
        trail(c, (28..43).map { stableSide to it } + (10..12).map { mx(it) to 43 }, mapOf(18 to sy))

        c.piece(PieceKind.HOUSE, manorLot, my)
        Landmarks(Frame(c, manorLot, Dir.N, my), Building(0, Kind.MANOR, manorLot, Dir.N, seed + 4, my), Random(seed + 4)).draw()
        c.piece(PieceKind.HOUSE, stableLot, sy)
        FarmBuildings(Frame(c, stableLot, mdir(Dir.W), sy), FarmBuilding(FarmKind.STABLE, stableLot, mdir(Dir.W), sy, seed + 5), Random(seed + 5)).draw()

        // The second field, behind the first
        c.piece(PieceKind.FIELD, fieldB, fy2)
        crops(c, fieldB, fy2, mx(-14), Random(seed * 19 + 11))
        c.piece(PieceKind.FENCE, fieldB.grow(1), fy2)
        fenceRing(c, fieldB.grow(1), fy2, gates = listOf(mx(-10) to 43), gateFacing = mdir(Dir.E))

        // An orchard down the far side; trees along the near side and a wayside shrine among them
        val r = Random(seed * 23 + 13)
        for ((k, z) in listOf(6, 12, 18, 24, 30, 36, 42).withIndex()) {
            val x = mx(-21)
            val ty = height(x, z)
            c.piece(PieceKind.TREE, Rect(x - 2, z - 2, x + 2, z + 2), ty)
            props(c).draw(FarmProp(FarmPropKind.FRUIT_TREE, x, z, ty, Dir.N, (k + r.nextInt(2)) % 5))
        }
        for ((k, z) in listOf(6, 16).withIndex()) {
            val x = mx(21)
            val ty = height(x, z)
            c.piece(PieceKind.TREE, Rect(x - 2, z - 2, x + 2, z + 2), ty)
            props(c).draw(FarmProp(FarmPropKind.TREE, x, z, ty, Dir.N, (k + r.nextInt(3)) % 3))
        }
        val shrine = Rect(mx(21) - 1, 29, mx(21) + 1, 31)
        val shy = land.lie(shrine)
        c.piece(PieceKind.PROP, shrine, shy)
        level(c, shrine, shy, B(M.GRASS))
        props(c).draw(FarmProp(FarmPropKind.SHRINE, mx(21), 30, shy, mdir(Dir.W), r.nextInt(4)))
    }

    // ---- Groundwork ----

    private fun props(c: PlanCanvas) = FarmProps(c, seed)

    /**
     * [r] made level at [y] with [surface] on top, the land banked toward it for two columns round: within this
     * tier's plot, and never over ground something has been levelled or built on.
     */
    private fun level(c: PlanCanvas, r: Rect, y: Int, surface: B) {
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) c.ground(x, z, y, surface, SOIL)
        val plot = PlotShape.plot(tier)
        for (x in r.x0 - 2..r.x1 + 2) for (z in r.z0 - 2..r.z1 + 2) {
            if (r.contains(x, z) || !plot.contains(x, z) || owned(x, z)) continue
            val out = max(max(r.x0 - x, x - r.x1), max(r.z0 - z, z - r.z1))
            val h = height(x, z)
            if (abs(h - y) < 2) continue
            c.clearing(x, z, y + (h - y) * out / 3)
        }
    }

    /** A building's lot laid with its stone footing at [y]. */
    private fun footing(c: PlanCanvas, lot: Rect, y: Int) {
        for (x in lot.x0..lot.x1) for (z in lot.z0..lot.z1) c.ground(x, z, y, B(M.FOUNDATION), SOIL)
    }

    /** The three by three round [x], [z] levelled where it lies, for something small to stand on; its height. */
    private fun pad(c: PlanCanvas, x: Int, z: Int): Int {
        val r = Rect(x - 1, z - 1, x + 1, z + 1)
        val y = land.lie(r)
        level(c, r, y, B(M.GRASS))
        return y
    }

    private fun path(c: PlanCanvas, x: Int, z: Int, y: Int) = c.ground(x, z, y, B(M.PAVE_LANE, v = 1), SOIL)

    /**
     * A path over [cells] in order, following the ground and stepping at most one up or down from cell to cell
     * where it can, coming within a step of the height [pins] gives for some of them (cell index to height). Cells
     * already levelled keep their height.
     */
    private fun trail(c: PlanCanvas, cells: List<Pair<Int, Int>>, pins: Map<Int, Int> = emptyMap()) {
        val n = cells.size
        val fixed = BooleanArray(n) { owned(cells[it].first, cells[it].second) }
        val holds = ArrayList<Triple<Int, Int, Int>>()
        for (i in 0 until n) if (fixed[i]) holds.add(Triple(i, height(cells[i].first, cells[i].second), 0))
        for ((i, h) in pins) holds.add(Triple(i, h, 1))
        val h = IntArray(n)
        for (i in 0 until n) {
            var lo = Int.MIN_VALUE
            var hi = Int.MAX_VALUE
            for ((j, at, slack) in holds) {
                val d = abs(i - j)
                lo = max(lo, at - slack - d)
                hi = min(hi, at + slack + d)
            }
            val t = height(cells[i].first, cells[i].second)
            h[i] = if (fixed[i] || lo > hi) t else t.coerceIn(lo, hi)
        }
        for (i in n - 2 downTo 0) if (!fixed[i]) h[i] = h[i].coerceIn(h[i + 1] - 1, h[i + 1] + 1)
        for (i in 0 until n) path(c, cells[i].first, cells[i].second, h[i])
    }

    /**
     * Fences round the edge of [r] at height [y] (null: on the ground as it lies, each post's column made good under
     * it), with gates at [gates] that swing on the way [gateFacing] says.
     */
    private fun fenceRing(c: PlanCanvas, r: Rect, y: Int?, gates: List<Pair<Int, Int>>, gateFacing: Dir) {
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            if (x != r.x0 && x != r.x1 && z != r.z0 && z != r.z1) continue
            val h = y ?: height(x, z).also { c.ground(x, z, it, B(M.GRASS), SOIL) }
            if ((x to z) in gates) c.set(x, h, z, B(M.FENCE_GATE, if (x == r.x0 || x == r.x1) gateFacing else Dir.N))
            else c.set(x, h, z, B(M.FENCE))
        }
    }

    /**
     * Crops in rows the length of [r] at height [y], a channel of water down the column [water] to keep them, each
     * row of a crop of its own (wheat, carrots, potatoes, beetroot), some nearly ripe and some still green.
     */
    private fun crops(c: PlanCanvas, r: Rect, y: Int, water: Int, rand: Random) {
        val kinds = IntArray(r.width) { rand.nextInt(4) }
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            if (x == water) {
                c.set(x, y - 1, z, B(M.WATER))
                c.set(x, y - 2, z, B(M.SOIL))
                continue
            }
            c.set(x, y - 1, z, B(M.FARMLAND))
            c.set(x, y, z, B(M.CROP, v = kinds[x - r.x0] + 4 * rand.nextInt(3)))
        }
    }

    /** A canvas that keeps nothing: the earlier tiers are walked through it for the ground they leave. */
    private object Discard : PlanCanvas {
        override val clip: Rect = PlotShape.RESERVED.grow(4)
        override fun piece(kind: PieceKind, lot: Rect, y: Int) {}
        override fun set(x: Int, y: Int, z: Int, b: B) {}
        override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) {}
        override fun clearing(x: Int, z: Int, y: Int) {}
        override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) {}
        override fun board(x: Int, y: Int, z: Int, facing: Dir) {}
        override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) {}
        override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: Dir?, home: At?) {}
    }

    companion object {
        private val SOIL = B(M.SOIL)

        /** The cottage's vegetable patch, behind the house. */
        private val KITCHEN_BED = Rect(-3, 17, 5, 18)
    }
}
