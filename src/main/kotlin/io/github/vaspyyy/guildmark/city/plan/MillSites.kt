package io.github.vaspyyy.guildmark.city.plan

import kotlin.math.max
import kotlin.math.sqrt

/**
 * One of a capital's mills as its plan lays it out: a windmill ([windmill]) or the watermill's wheel, the [hub] its sails
 * or its wheel turn on (in the city's frame), the columns its drawing can reach ([bounds]), and for a windmill which of
 * the capital's windmills it is ([rank], of [count]). A windmill whose sails would sweep through something the city
 * builds beside it (the eaves of a house next door) keeps its sails built of blocks, never turning: [crowd] is the
 * first block in their way.
 */
class MillSite(val windmill: Boolean, val hub: At, val bounds: Rect, val rank: Int, val count: Int, val crowd: At?) {
    /** Whether its sails or its wheel are the hub the game draws turning, rather than built of blocks. */
    val turns: Boolean get() = crowd == null

    /**
     * What the city builds round this mill: every block it sets over the mill's [bounds] (the last one set wherever two
     * fall on the same block), with this mill turning if [turning] and built of blocks if not, and every other built of
     * blocks. Drawn both ways, the blocks that differ are what an older capital's mill changes when it starts turning.
     */
    fun drawn(plan: CityPlan, turning: Boolean): Map<At, B> {
        val canvas = Recorder(bounds) { it != hub || !turning }
        CityBuilder(plan, canvas).draw()
        return canvas.blocks
    }

    /** Keeps every block set, and nothing else; [block] says which mills are built of blocks. */
    private class Recorder(override val clip: Rect, private val block: (At) -> Boolean) : Canvas {
        val blocks = HashMap<At, B>()

        override fun set(x: Int, y: Int, z: Int, b: B) {
            if (clip.contains(x, z)) blocks[At(x, y, z)] = b
        }

        override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) {}
        override fun clearing(x: Int, z: Int, y: Int) {}
        override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) {}
        override fun board(x: Int, y: Int, z: Int, facing: Dir) {}
        override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) {}
        override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: Dir?, home: At?) {}
        override fun blockMill(hub: At): Boolean = block(hub)
    }

    companion object {
        /** How far the turning sails reach from the middle of their hub, in blocks. */
        const val SAIL_REACH = 8.5

        /** Every mill of [plan]: its windmills in the order it laid them out, then its watermills' wheels. */
        fun of(plan: CityPlan): List<MillSite> {
            val windmills = plan.outskirts.buildings.filter { it.kind == FarmKind.WINDMILL }
            val sites = ArrayList<MillSite>()
            windmills.forEachIndexed { i, b -> sites.add(MillSite(true, FarmWindmill.hub(b), b.bounds, i, windmills.size, crowd(plan, b))) }
            for (p in plan.outskirts.props) if (p.kind == FarmPropKind.WHEEL) sites.add(MillSite(false, FarmWheel.hub(p), p.bounds, 0, 1, null))
            return sites
        }

        /**
         * Where [b]'s sails turn, in the city's frame: the row in front of the hub's (they turn in its back third, behind
         * the lamps on their posts and clear of the cart), every block of it they sweep.
         */
        fun swept(b: FarmBuilding): List<At> {
            val f = Frame(NullCanvas, b.lot, b.front, b.y)
            val cu = f.w / 2
            val hy = FarmWindmill.TOP + 3
            val reach = SAIL_REACH.toInt() + 1
            val cells = ArrayList<At>()
            for (u in cu - reach..cu + reach) for (y in hy - reach..hy + reach) {
                // The nearest corner or edge of the block to the hub's middle
                val du = max(0.0, max(u - (cu + 0.5), cu + 0.5 - (u + 1)))
                val dy = max(0.0, max(y - (hy + 0.5), hy + 0.5 - (y + 1)))
                if (sqrt(du * du + dy * dy) < SAIL_REACH) cells.add(f.at(u, y, FarmWindmill.SAIL_V - 1))
            }
            return cells
        }

        /** The first block the city builds where [b]'s sails would turn (the lamps they pass behind aside), if there is one. */
        private fun crowd(plan: CityPlan, b: FarmBuilding): At? {
            val cells = swept(b)
            val clip = Rect(cells.minOf { it.x }, cells.minOf { it.z }, cells.maxOf { it.x }, cells.maxOf { it.z })
            val canvas = Recorder(clip) { true }
            CityBuilder(plan, canvas).draw()
            return cells.firstOrNull { val c = canvas.blocks[it]; c != null && c.m != M.AIR && c.m != M.LANTERN }
        }

        /** Whether two of the plan's blocks are the same block, facing the same way and in every other respect. */
        fun alike(a: B, b: B): Boolean = a.m == b.m && a.dir == b.dir && a.top == b.top && a.axis == b.axis && a.color == b.color &&
            a.v == b.v && a.part == b.part && a.open == b.open
    }
}
