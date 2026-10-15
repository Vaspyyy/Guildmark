package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * The small things about a farmyard: the cart, the haystack, the water trough, the woodpile, and the compost corner
 * where a farmer works. Each is drawn in the frame of its prop, facing north (ahead is -v, to the right +u).
 *
 * The farms put some of these in tight corners (a woodpile against a garden fence, a haystack at a yard's edge, a
 * compost heap in the back corner of a plot), so those keep to a narrow patch: the haystack to the spot and the row
 * behind it, three across, the woodpile to the line through it, the compost to the spot, the cell ahead and the one
 * on its right. The cart is the widest, five across, and keeps a block back from the front of its reach.
 */
internal class FarmYard(private val f: Frame, private val r: Random) {
    fun draw(p: FarmProp) {
        when (p.kind) {
            FarmPropKind.CART -> cart(p.v)
            FarmPropKind.HAYSTACK -> haystack(p.v)
            FarmPropKind.TROUGH -> trough()
            FarmPropKind.WOODPILE -> woodpile()
            else -> compost(p.v)
        }
    }

    /** Bare earth under [u], [v], where wheels and boots have worn the grass away. */
    private fun worn(u: Int, v: Int) = f.set(u, -1, v, TRACK)

    /** A bale of hay, lying along [axis]. */
    private fun bale(axis: Axis) = B(M.HAY, axis = axis)

    private fun randomAxis() = if (r.nextBoolean()) Axis.X else Axis.Z

    /** Sets [b] on the cart, whose own rows [v] are counted from the middle of its bed ([BACK] behind the spot). */
    private fun part(u: Int, y: Int, v: Int, b: B) = f.set(u, y, v + BACK, b)

    /**
     * A two-wheeled cart standing tilted on its shafts: a plank bed over the axle between two round wheels of dark
     * planks, a block lower in front, with low boards round it, loaded by [variant]: pumpkins, hay, or sacks and
     * barrels. The bed stands a block behind the spot, so that the shafts reach just ahead of the spot and nothing
     * of it is more than a block in front of it.
     */
    private fun cart(variant: Int) {
        for (v in -1..1) for (u in intArrayOf(-2, 2)) f.set(u, -1, v + BACK, TRACK)
        for (u in intArrayOf(-2, 2)) wheel(u)
        for (u in -1..1) {
            part(u, 1, 0, B(M.LOG, axis = Axis.X))
            for (v in 0..1) part(u, 2, v, B(M.PLANKS, v = 1))
            part(u, 1, -1, B(M.PLANKS, v = 1))
        }
        for (u in intArrayOf(-1, 1)) {
            part(u, 2, -1, BOARD)
            for (v in 0..1) part(u, 3, v, BOARD)
        }
        part(0, 3, 1, BOARD)
        // The shafts, lying down to the ground ahead and joined by a bar
        for (u in intArrayOf(-1, 1)) {
            part(u, 1, -2, FENCE)
            part(u, 0, -2, FENCE)
        }
        part(0, 0, -2, FENCE)
        when (Math.floorMod(variant, 3)) {
            0 -> harvest()
            1 -> hay()
            else -> sacks()
        }
    }

    /** A wheel at [u]: a plus of planks round a log hub, its corners cut with stairs so that it looks round. */
    private fun wheel(u: Int) {
        val rim = B(M.PLANKS)
        for ((v, y) in listOf(0 to 0, 0 to 2, -1 to 1, 1 to 1)) part(u, y, v, rim)
        part(u, 1, 0, B(M.LOG, axis = Axis.X))
        part(u, 2, -1, B(M.PLANK_STAIRS, Dir.S))
        part(u, 2, 1, B(M.PLANK_STAIRS, Dir.N))
        part(u, 0, -1, B(M.PLANK_STAIRS, Dir.S, top = true))
        part(u, 0, 1, B(M.PLANK_STAIRS, Dir.N, top = true))
    }

    /** The height of the first free block over the bed at row [v]: the front row is a block lower. */
    private fun bed(v: Int) = if (v == -1) 2 else 3

    /** Hay heaped on the cart, a little uneven. */
    private fun hay() {
        for (u in -1..1) for (v in -1..1) {
            if (u != 0 && v != 0 && r.nextInt(4) == 0) continue
            part(u, bed(v), v, bale(randomAxis()))
        }
        for ((u, v) in listOf(0 to 0, 0 to 1, 1 to 0, -1 to 0)) {
            if (u == 0 && v == 0 || r.nextInt(3) != 0) part(u, bed(v) + 1, v, bale(Axis.Y))
        }
    }

    /** Pumpkins and melons from the field. */
    private fun harvest() {
        for (u in -1..1) for (v in -1..1) {
            if (r.nextInt(5) == 0) continue
            part(u, bed(v), v, B(M.PUMPKIN, v = if (r.nextInt(3) == 0) 3 else 0))
        }
        if (r.nextBoolean()) part(0, 4, 0, B(M.PUMPKIN))
    }

    /** Sacks of grain (white wool, tied), a barrel or two, and a bale. */
    private fun sacks() {
        val cloth = if (r.nextInt(3) == 0) Dye.LIGHT_GRAY else Dye.WHITE
        for (v in -1..1) {
            part(-1, bed(v), v, if (v == 0) B(M.BARREL) else B(M.WOOL, color = cloth))
            part(1, bed(v), v, if (v == 1) B(M.BARREL) else B(M.WOOL, color = cloth))
        }
        part(0, 3, 0, B(M.WOOL, color = cloth))
        part(0, 3, 1, B(M.WOOL, color = cloth))
        part(0, 2, -1, bale(Axis.X))
        part(if (r.nextBoolean()) -1 else 1, 4, 0, B(M.WOOL, color = cloth))
    }

    /**
     * A haystack on the spot and the row behind it, three across: a rick, stepped up to a point, or (for variant
     * 1, now one, now the other) a pyramid of bales; the earth under it bare.
     */
    private fun haystack(variant: Int) {
        for (u in -1..1) for (v in 0..1) f.set(u, -1, v, B(M.SOIL))
        if (variant == 1 && r.nextBoolean()) bales() else rick()
    }

    private fun rick() {
        for (u in -1..1) for (v in 0..1) {
            if (u != 0 && v == 1 && r.nextInt(4) == 0) continue
            f.set(u, 0, v, bale(if (u == 0) Axis.Y else if ((u + v) % 2 == 0) Axis.X else Axis.Z))
        }
        for ((u, v) in listOf(1 to 0, -1 to 0, 0 to 1)) if (r.nextInt(4) != 0) f.set(u, 1, v, bale(Axis.Y))
        f.set(0, 1, 0, bale(Axis.Y))
        f.set(0, 2, 0, bale(Axis.Y))
    }

    private fun bales() {
        for (u in -1..1) for (v in 0..1) f.set(u, 0, v, bale(Axis.Z))
        val side = if (r.nextBoolean()) -1 else 0
        for (u in side..side + 1) for (v in 0..1) f.set(u, 1, v, bale(Axis.Z))
        val top = side + r.nextInt(2)
        for (v in 0..1) f.set(top, 2, v, bale(Axis.Z))
    }

    /**
     * A water trough for the beasts: a pair of full cauldrons end to end, a hay bale beside them for a feed, and a
     * hitching rail of two posts and a bar behind.
     */
    private fun trough() {
        val flip = if (r.nextBoolean()) 1 else -1
        f.set(-flip, 0, 0, FULL_CAULDRON)
        f.set(0, 0, 0, FULL_CAULDRON)
        f.set(flip, 0, 0, bale(Axis.X))
        for (u in -1..1) worn(u, 0)
        worn(0, -1)
        for (u in intArrayOf(-1, 1)) for (y in 0..1) f.set(u, y, 1, FENCE)
        f.set(0, 1, 1, FENCE)
    }

    /**
     * A woodpile on the line through the spot, three long, the logs laid across it so that their ends show to either
     * side: three high in the middle, two at the ends, the top log of the middle crossed, on a bare patch of earth.
     */
    private fun woodpile() {
        for (v in -1..1) {
            f.set(0, -1, v, B(M.SOIL))
            for (y in 0 until if (v == 0) 3 else 2) f.set(0, y, v, B(M.LOG, axis = if (y == 2) Axis.Z else Axis.X))
        }
    }

    /**
     * The compost heap a farmer works from, in the back corner of a plot: the composter itself, a full cauldron
     * before it (a barrel with a lantern on it, in the larger corner, [variant] 1), and the farmer to its right
     * on a trodden patch of earth, who tends whatever crops are within reach of it.
     */
    private fun compost(variant: Int) {
        for ((u, v) in listOf(0 to 0, 1 to 0, 0 to -1)) worn(u, v)
        f.set(0, 0, 0, COMPOSTER)
        if (variant == 1) {
            f.set(0, 0, -1, B(M.BARREL))
            f.set(0, 1, -1, B(M.LANTERN))
        } else {
            f.set(0, 0, -1, FULL_CAULDRON)
        }
        // Room to stand: two blocks clear above the farmer's feet
        for (y in 0..2) f.set(1, y, 0, AIR)
        f.resident(Folk.FARMER, 1, 0, 0, work = f.at(0, 0, 0))
    }

    companion object {
        /** How far behind its spot a cart's bed stands. */
        const val BACK = 1

        val AIR = B.AIR
        val FENCE = B(M.FENCE)
        val BOARD = B(M.PLANK_SLAB)
        val TRACK = B(M.TRACK)
        val FULL_CAULDRON = B(M.CAULDRON, v = 2)
        val COMPOSTER = B(M.COMPOSTER)
    }
}
