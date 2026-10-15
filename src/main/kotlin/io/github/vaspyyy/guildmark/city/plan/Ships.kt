package io.github.vaspyyy.guildmark.city.plan

import java.util.Random
import kotlin.math.abs
import kotlin.math.min

/**
 * Ships and boats tied up in a harbour, each drawn bow first in its own frame (see [Frame]) with its
 * heights counted from the sea's surface: y = -1 is the water's top layer, so a hull drawn down from
 * there sits in the water with its waterline at the sea.
 */
class Ships(private val f: Frame, private val r: Random) {
    /** Across the beam, and from the tip of the bowsprit to the stern. */
    private val w = f.w
    private val d = f.d
    private val mid = w / 2

    /**
     * A ship: a planked hull curving in to the stem, a dark wale along it, the deck with its hatch and
     * cargo, a raised stern castle with the master's cabin under it (and on a [big] one a forecastle
     * too), masts of logs with yards and white sails, a bowsprit, the owners' flag and lanterns.
     */
    fun ship(big: Boolean) {
        val bow = BOWSPRIT
        val castle = d - if (big) 7 else 5
        // The hull, layer by layer: narrower below the water, the deck and the wale at the top
        for (v in bow until d) {
            val half = half(v - bow, big)
            for (u in mid - half..mid + half) {
                val off = abs(u - mid)
                if (off <= half - 2 && v in bow + 2..d - 2) f.set(u, -3, v, HULL)
                if (off <= half - 1 && v > bow) f.set(u, -2, v, HULL)
                f.set(u, -1, v, HULL)
                f.set(u, 0, v, HULL)
                f.set(u, 1, v, if (off == half) B(M.TIMBER, axis = Axis.Z) else DECK)
                if (off == half) f.set(u, 2, v, RAIL)
            }
        }
        // The stem rising at the bow, the bowsprit out over the water
        for (y in 1..3) f.set(mid, y, bow, B(M.TIMBER))
        for (v in 1 until bow) f.set(mid, 3, v, B(M.TIMBER, axis = Axis.Z))
        f.set(mid, 3, 0, B(M.FENCE))
        f.set(mid, 4, bow, B(M.LANTERN))
        if (big) forecastle(bow)
        sternCastle(castle, big)

        // Masts with their yards and sails: a big ship's fore and main masts carry a topsail over the course, a small one a single great sail
        val main = bow + (d - bow) * 9 / 20
        mast(main, if (big) BIG_MAST else SMALL_MAST, topsail = big, flag = true)
        if (big) mast(bow + (d - bow) / 5 + 1, BIG_MAST - 5, topsail = true, flag = false)

        // On deck: the hatch to the hold, barrels and crates lashed by the rails, a coil of rope
        val hatch = main + 2
        for (u in mid - 1..mid) for (v in hatch..hatch + 1) f.set(u, 2, v, B(M.TRAPDOOR, Dir.N))
        val by = half(castle - 1 - bow, big) - 1
        f.set(mid - by, 2, castle - 1, B(M.BARREL))
        f.set(mid + by, 2, castle - 1, B(M.CRATE))
        f.set(mid + by, 3, castle - 1, B(M.CRATE))
        f.set(mid - by, 2, main - 2, B(M.CARPET, color = Dye.BROWN))
        if (r.nextBoolean()) f.set(mid + by, 2, main - 2, B(M.BARREL, Dir.E))
        // A hand left aboard to mind her
        f.resident(Folk.CITIZEN, mid, 2, main + 4)
    }

    /** How far the hull reaches either side of the keel, [v] blocks back from the stem. */
    private fun half(v: Int, big: Boolean): Int {
        val most = w / 2
        val stern = d - BOWSPRIT - 1 - v
        val fore = TAPER.getOrElse(v) { most }
        val aft = if (stern == 0) most - 1 else most
        return min(min(fore, most), aft)
    }

    /** The raised deck over the bow, with its rail and a lantern on the stem. */
    private fun forecastle(bow: Int) {
        for (v in bow until bow + FORECASTLE) {
            val half = half(v - bow, true)
            for (u in mid - half..mid + half) {
                val off = abs(u - mid)
                f.set(u, 2, v, if (off == half) B(M.TIMBER, axis = Axis.Z) else DECK)
                f.set(u, 3, v, if (off == half || v == bow + FORECASTLE - 1 && off > 0) RAIL else AIR)
            }
        }
        // Steps down to the main deck
        f.set(mid, 2, bow + FORECASTLE, B(M.PLANK_STAIRS, Dir.N))
    }

    /**
     * The stern castle from [castle] aft: the master's cabin under it, windows across the stern and a
     * door onto the main deck, a railed deck on top with a lantern at the taffrail and a ladder up to it.
     */
    private fun sternCastle(castle: Int, big: Boolean) {
        val roof = 4
        for (v in castle until d) {
            val half = half(v - BOWSPRIT, big)
            for (u in mid - half..mid + half) {
                val off = abs(u - mid)
                val wall = off == half || v == castle || v == d - 1
                for (y in 2 until roof) {
                    f.set(u, y, v, when {
                        !wall -> AIR
                        // Windows across the stern, and down the sides
                        v == d - 1 && off == 1 && y == 3 -> B(M.WINDOW)
                        off == half && v == castle + 2 && y == 3 -> B(M.WINDOW)
                        else -> HULL
                    })
                }
                f.set(u, roof, v, if (off == half) B(M.TIMBER, axis = Axis.Z) else DECK)
                if (off == half || v == d - 1) f.set(u, roof + 1, v, RAIL)
            }
        }
        // The front of the castle: a door in the middle, windows either side
        f.door(mid, 2, castle, inward = Dir.S)
        for (u in listOf(mid - 2, mid + 2)) f.set(u, 3, castle, B(M.WINDOW))
        f.set(mid - 1, roof + 1, castle, RAIL)
        f.set(mid + 1, roof + 1, castle, RAIL)
        // A ladder up the front to the castle's deck
        val ladder = mid + 1
        for (y in 2..roof) f.set(ladder, y, castle - 1, B(M.LADDER, Dir.N))
        f.set(ladder, roof + 1, castle, AIR)
        // The stern lantern, and the cabin: the master's bunk, his chest and charts, a lamp
        f.set(mid, roof + 1, d - 1, B(M.FENCE))
        f.set(mid, roof + 2, d - 1, B(M.LANTERN))
        if (d - castle >= 6) {
            f.bed(mid - 1, 2, d - 3, Dir.S, Dye.BLUE)
            f.chest(mid + 1, 2, d - 2, Dir.N, Loot.HARBOUR)
            f.set(mid + 1, 2, castle + 2, B(M.CARTOGRAPHY_TABLE))
        } else {
            f.chest(mid - 1, 2, d - 2, Dir.N, Loot.HARBOUR)
        }
        f.set(mid, roof - 1, castle + 2, B(M.LANTERN, top = true))
    }

    /**
     * A mast of logs at [v] rising [height] over the deck, with a yard across it near the top and a sail
     * hanging from it; with a [topsail], a lower yard with the course under it and a fighting top over it.
     * A pennant streams off the masthead, the owners' colours on the [flag]ship's main mast.
     */
    private fun mast(v: Int, height: Int, topsail: Boolean, flag: Boolean) {
        val top = 1 + height
        for (y in -2..top) f.set(mid, y, v, B(M.LOG))
        val yard = w / 2 + 1
        val upper = top - 1
        if (topsail) {
            val lower = LOWER_YARD
            yard(v, lower, yard)
            sail(v, lower - 1, SAIL_FOOT, yard - 1)
            // The fighting top over the lower yard: a railed platform round the mast
            for (u in mid - 1..mid + 1) for (dv in -1..1) {
                if (u == mid && dv == 0) continue
                f.set(u, lower + 1, v + dv, B(M.PLANK_SLAB, top = true))
                f.set(u, lower + 2, v + dv, RAIL)
            }
            yard(v, upper, yard - 1)
            sail(v, upper - 1, lower + 3, yard - 2)
        } else {
            yard(v, upper, yard)
            sail(v, upper - 1, SAIL_FOOT, yard - 1)
        }
        val color = if (flag) FLAGS[r.nextInt(FLAGS.size)] else Dye.WHITE
        f.set(mid + 1, top, v, B(M.WOOL, color = color))
        if (flag) f.set(mid + 2, top, v, B(M.WOOL, color = color))
    }

    /** A yard across the mast at [y], [half] either side of it. */
    private fun yard(v: Int, y: Int, half: Int) {
        for (u in mid - half..mid + half) if (u != mid) f.set(u, y, v, B(M.TIMBER, axis = Axis.X))
    }

    /** A sail hanging just forward of the mast from [y1] down to [y0], [half] either side of it, its foot cut a little narrower. */
    private fun sail(v: Int, y1: Int, y0: Int, half: Int) {
        for (y in y0..y1) {
            val reach = if (y == y0) half - 1 else half
            for (u in mid - reach..mid + reach) f.set(u, y, v - 1, B(M.WOOL))
        }
    }

    /**
     * A rowing boat, three wide and five long: a planked bottom just in the water, low sides of slabs round
     * it, a thwart across the middle and the oars shipped along the sides.
     */
    fun boat() {
        for (v in 0 until d) for (u in 0 until w) {
            val end = v == 0 || v == d - 1
            if (end && u != mid) continue
            val side = u != mid
            f.set(u, -1, v, when {
                v == 0 -> B(M.PLANK_STAIRS, Dir.S, top = true)
                v == d - 1 -> B(M.PLANK_STAIRS, Dir.N, top = true)
                else -> B(M.PLANKS)
            })
            f.set(u, 0, v, if (end || side) B(M.PLANK_SLAB) else AIR)
        }
        f.set(mid, 0, d / 2, B(M.TRAPDOOR, Dir.N))
        if (r.nextBoolean()) f.set(mid, 0, d - 2, B(M.BARREL))
        else f.set(mid, 0, 1, B(M.CARPET, color = Dye.BROWN))
    }

    companion object {
        /** How far the hull reaches out from the keel, from the stem back. */
        val TAPER = intArrayOf(0, 1, 2, 2, 3, 3, 4)
        /** The bowsprit's length out in front of the stem. */
        const val BOWSPRIT = 3
        const val FORECASTLE = 4
        const val BIG_MAST = 19
        const val SMALL_MAST = 15
        /** The lower yard's height over the water, and the foot of the sails (clear of heads on deck). */
        const val LOWER_YARD = 9
        const val SAIL_FOOT = 4

        val AIR = B.AIR
        val HULL = B(M.PLANKS, v = 1)
        val DECK = B(M.PLANKS)
        val RAIL = B(M.FENCE)
        val FLAGS = listOf(Dye.RED, Dye.BLUE, Dye.YELLOW, Dye.GREEN, Dye.PURPLE, Dye.ORANGE)
    }
}
