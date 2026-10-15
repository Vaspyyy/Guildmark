package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/** Draws a building of the country in its own frame, facing north (see [Frame]). */
class FarmBuildings(private val f: Frame, private val b: FarmBuilding, private val r: Random) {
    fun draw() {
        when (b.kind) {
            FarmKind.COTTAGE, FarmKind.FARMHOUSE -> FarmHomes(f, b, r).draw()
            FarmKind.BARN, FarmKind.STABLE, FarmKind.COOP, FarmKind.SHELTER -> FarmSteadings(f, b, r).draw()
            FarmKind.INN -> FarmInn(f, b, r).draw()
            FarmKind.MILL -> FarmMill(f, b, r).draw()
            FarmKind.WINDMILL -> FarmWindmill(f, b, r).draw()
        }
    }
}

/** What a house's walls are made of. */
internal enum class Walling { PLASTER, STONE, LOG }

/**
 * What the country's buildings are made from, shared by the pieces that draw them: the frame and its size,
 * walls and roofs, windows with their shutters, chimneys, and the small furniture.
 */
internal abstract class FarmPiece(protected val f: Frame, protected val b: FarmBuilding, protected val r: Random) {
    protected val w = f.w
    protected val d = f.d

    abstract fun draw()

    /** A floor (or a ceiling) right across the inside at [y]. */
    protected fun floorAt(y: Int, block: B) = f.fill(1, y, 1, w - 2, y, d - 2, block)

    protected fun onRing(u: Int, v: Int): Boolean = u == 0 || u == w - 1 || v == 0 || v == d - 1

    /** The outer walls from [y0] to [y1], each block chosen by [pick] from where it is (null leaves it as it is). */
    protected fun walls(y0: Int, y1: Int, pick: (u: Int, v: Int, y: Int) -> B?) {
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            for (y in y0..y1) pick(u, v, y)?.let { f.set(u, y, v, it) }
        }
    }

    /**
     * A window of one pane at [u], [y], [v] in the wall on [side] (the way the wall faces), [height] panes
     * tall, with open shutters either side of it outside.
     */
    protected fun window(u: Int, y: Int, v: Int, side: Dir, shutters: Boolean = true, height: Int = 1) {
        val along = side.cw
        for (h in 0 until height) {
            f.set(u, y + h, v, WINDOW)
            if (!shutters) continue
            f.set(u + side.dx + along.dx, y + h, v + side.dz + along.dz, B(M.SHUTTER, side, open = true))
            f.set(u + side.dx - along.dx, y + h, v + side.dz - along.dz, B(M.SHUTTER, side, open = true))
        }
    }

    /** A chimney stack of stone at [u], [v] from [y0] up to [y1], a fire burning on top of it. */
    protected fun chimney(u: Int, v: Int, y0: Int, y1: Int) {
        for (y in y0..y1) f.set(u, y, v, FOOTING)
        f.set(u, y1 + 1, v, B(M.CAMPFIRE))
    }

    /** A hipped roof over the walls, sloping in from every side, eaves a block out; [cap] puts a slab over its peak. */
    protected fun hip(top: Int, palette: Int, cap: Boolean = true) {
        var k = 0
        while (true) {
            val u0 = -1 + k
            val u1 = w - k
            val v0 = -1 + k
            val v1 = d - k
            if (u1 - u0 <= 1 || v1 - v0 <= 1) {
                for (u in u0..u1) for (v in v0..v1) {
                    f.set(u, top + k, v, B(M.ROOF, v = palette))
                    if (cap) f.set(u, top + k + 1, v, B(M.ROOF_SLAB, v = palette))
                }
                return
            }
            for (u in u0..u1) {
                f.set(u, top + k, v0, B(M.ROOF_STAIRS, Dir.S, v = palette))
                f.set(u, top + k, v1, B(M.ROOF_STAIRS, Dir.N, v = palette))
            }
            for (v in v0 + 1 until v1) {
                f.set(u0, top + k, v, B(M.ROOF_STAIRS, Dir.E, v = palette))
                f.set(u1, top + k, v, B(M.ROOF_STAIRS, Dir.W, v = palette))
            }
            k++
        }
    }

    /** A seat, looking toward [looks]. */
    protected fun chair(looks: Dir): B = B(M.PLANK_STAIRS, looks.opposite)

    /**
     * The block of a wall of this [walling] at [u], [v], [y] (counting up from the stone footing, the beam row
     * at [top]): timber posts at the corners and every third block along it between plaster, stone, or logs.
     */
    protected fun wall(walling: Walling, u: Int, v: Int, y: Int, top: Int): B {
        val broad = v == 0 || v == d - 1
        val corner = (u == 0 || u == w - 1) && broad
        val axis = if (broad) Axis.X else Axis.Z
        val along = if (broad) u else v
        val length = if (broad) w else d
        val post = corner || (length >= 6 && along % 3 == 0 && along <= length - 3)
        return when {
            y == 0 -> FOOTING
            walling == Walling.LOG -> if (corner) B(M.LOG) else B(M.LOG, axis = axis)
            y == top -> if (corner) TIMBER else B(M.TIMBER, axis = axis)
            walling == Walling.STONE -> if (corner) TIMBER else STONE
            post -> TIMBER
            else -> PLASTER
        }
    }

    /** The block that fills a gable over walls of this [walling]. */
    protected fun gable(walling: Walling): B = when (walling) {
        Walling.PLASTER -> PLASTER
        Walling.STONE -> STONE
        Walling.LOG -> B(M.PLANKS)
    }

    companion object {
        const val STORY = Buildings.STORY

        // The roof palettes: slate, red tile, dark timber, and thatch
        const val ROOF_SLATE = 0
        const val ROOF_RED = 1
        const val ROOF_WOOD = 2
        const val ROOF_THATCH = 3

        val AIR = B.AIR
        val STONE = B(M.STONE)
        val FOOTING = B(M.FOUNDATION)
        val TIMBER = B(M.TIMBER)
        val PLASTER = B(M.PLASTER)
        val WINDOW = B(M.WINDOW)
        val TABLE = B(M.PLANK_SLAB, top = true)
        val BED_COLORS = Buildings.BED_COLORS
    }
}
