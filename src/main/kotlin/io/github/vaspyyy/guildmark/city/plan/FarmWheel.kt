package io.github.vaspyyy.guildmark.city.plan

/**
 * The watermill's wheel, in the stream just past the mill's end wall. The prop stands at its hub, facing away from
 * the mill (the wall is one block behind it) with the stream running across: an eight-spoked wheel seven across on a
 * log axle that goes through the mill's wall to the millstone. The game draws it turning on its hub, so the plan only
 * keeps the stream's water and the air clear wherever it sweeps. Where the canvas builds mills of blocks
 * ([Canvas.blockMill]) it is built block by block instead: an outer face of timber rim and fence spokes and a rim of
 * paddle boards behind it (with spokes of its own, so that there is no room to stand between the two).
 * [FarmProp.v] is how far the mill's floor stands over the water's surface; the axle keeps to the floor where it can,
 * but is kept low enough that the paddles dip into the stream.
 */
internal class FarmWheel(private val f: Frame) {
    fun draw(p: FarmProp) {
        // The surface of the stream is at -1 in this frame, its top water at -2; the mill's floor is at v - 1
        val hub = hubHeight(p)
        if (f.canvas.blockMill(f.at(0, hub, 0))) {
            for ((u, y) in RIM) {
                f.set(u, hub + y, -1, B(M.TIMBER, axis = Axis.Z))
                f.set(u, hub + y, 0, B(M.PLANKS, v = 1))
            }
            for ((u, y) in SPOKES) for (v in -1..0) f.set(u, hub + y, v, FENCE)
            // The axle through the hub and the wall, a stub of it beyond the wheel
            for (v in -2..1) f.set(0, hub, v, B(M.LOG, axis = Axis.Z))
            return
        }
        // Where it turns: the stream's own water under its surface, air over it; the stub of the axle beyond the wheel
        // is the wheel's, and the axle goes on through the wall behind the hub
        for ((u, y) in SWEEP) for (v in -1..0) f.set(u, hub + y, v, if (hub + y <= TOP_WATER) WATER else B.AIR)
        f.set(0, hub, -2, B.AIR)
        f.set(0, hub, 0, B(M.WATER_WHEEL, Dir.N))
        f.set(0, hub, 1, B(M.LOG, axis = Axis.Z))
    }

    companion object {
        val FENCE = B(M.FENCE)
        val WATER = B(M.WATER)

        /** The height of the stream's top water in the wheel's frame. */
        const val TOP_WATER = -2

        /** How high the hub stands in the wheel's frame. */
        fun hubHeight(p: FarmProp): Int = (p.v - 1).coerceIn(0, 1)

        /** Where [p]'s wheel turns: its hub, in the city's frame. */
        fun hub(p: FarmProp): At = Frame(NullCanvas, Rect(p.x, p.z, p.x, p.z), p.facing, p.y).at(0, hubHeight(p), 0)

        /** The rim of the wheel, an octagon: the cells (across, up) at about three blocks from the hub. */
        val RIM: List<Pair<Int, Int>> = buildList {
            for (u in -3..3) for (y in -3..3) {
                val d = Math.sqrt((u * u + y * y).toDouble())
                if (d >= 2.5 && d < 3.5) add(u to y)
            }
        }

        /** The spokes: the four straight ones and the four on the diagonals, from the hub to the rim. */
        val SPOKES: List<Pair<Int, Int>> = buildList {
            for (k in 1..2) {
                for ((du, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) add(du * k to dy * k)
            }
            for ((du, dy) in listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)) add(du to dy)
        }

        /** Every cell (across, up) the turning wheel passes through: all that comes within three and a half blocks of the hub's middle. */
        val SWEEP: List<Pair<Int, Int>> = buildList {
            for (u in -3..3) for (y in -3..3) {
                val a = maxOf(Math.abs(u) - 0.5, 0.0)
                val b = maxOf(Math.abs(y) - 0.5, 0.0)
                if (a * a + b * b < REACH * REACH) add(u to y)
            }
        }

        /** How far the turning wheel reaches from the hub's middle, in blocks. */
        const val REACH = 3.5
    }
}
