package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/** The kinds of building along a harbour's quay, and the widths each may be across its front. */
enum class ShedKind(val widths: IntArray) {
    HARBOURMASTER(intArrayOf(9, 11)),
    FISH_MARKET(intArrayOf(11, 13)),
    WAREHOUSE(intArrayOf(11, 13)),
    SHED(intArrayOf(9, 11)),
    CHANDLERY(intArrayOf(7)),
}

/**
 * The harbour's buildings, each drawn facing the water in its own frame (see [Frame]) on the quay: stone
 * warehouses and timber sheds full of goods, the harbourmaster's house with its lookout, the fish market,
 * and the chandlers' shops. [shared] says which sides (west and east in the frame) back onto another
 * building, so they get no windows; [square] which side, if any, looks onto the harbour square.
 */
class HarbourBuildings(private val f: Frame, private val r: Random, private val shared: BooleanArray, private val square: Dir?) {
    private val w = f.w
    private val d = f.d

    fun draw(kind: ShedKind) {
        when (kind) {
            ShedKind.HARBOURMASTER -> harbourmaster()
            ShedKind.FISH_MARKET -> fishMarket()
            ShedKind.WAREHOUSE -> warehouse()
            ShedKind.SHED -> shed()
            ShedKind.CHANDLERY -> chandlery()
        }
    }

    // ---- Warehouses ----

    /**
     * A stone warehouse with its gable to the quay: a wide doorway under a timber lintel, the loft door
     * over it and a hoist beam at the apex with its chain and hook; inside, goods stacked down both sides
     * and up in the loft under the roof.
     */
    private fun warehouse() {
        val top = 2 * STORY - 1
        val mid = w / 2
        val opening = if (w >= 13) 2 else 1
        clear(top)
        floorAt(-1, B(M.PLANKS, v = 1))
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            val pilaster = (u == 0 || u == w - 1) && v % 4 == 0
            for (y in 0..top) f.set(u, y, v, when {
                y == 0 -> DARK
                corner || pilaster -> TRIM
                y == STORY - 1 -> TRIM
                else -> STONE
            })
        }
        floorAt(STORY - 1, B(M.PLANKS))
        Buildings.roof(f, w, d, 0, top, r.nextInt(3), true, STONE)
        // The doorway, its lintel, and the lamps either side
        for (u in mid - opening..mid + opening) for (y in 0..2) f.set(u, y, 0, AIR)
        for (u in mid - opening - 1..mid + opening + 1) f.set(u, 3, 0, B(M.TIMBER, axis = Axis.X))
        for (u in listOf(mid - opening - 1, mid + opening + 1)) f.set(u, 2, -1, B(M.LANTERN))
        for (u in listOf(mid - opening - 1, mid + opening + 1)) f.set(u, 1, -1, B(M.WALL_POST))
        // The loft door with its shutters open, and the hoist over it
        for (y in STORY..STORY + 1) {
            f.set(mid, y, 0, AIR)
            f.set(mid - 1, y, -1, B(M.SHUTTER, Dir.N, open = true))
            f.set(mid + 1, y, -1, B(M.SHUTTER, Dir.N, open = true))
        }
        val beam = top + (w + 1) / 2 - 2
        for (v in -2..0) f.set(mid, beam, v, B(M.TIMBER, axis = Axis.Z))
        for (y in STORY + 3 until beam) f.set(mid, y, -2, B(M.CHAIN))
        f.set(mid, STORY + 2, -2, B(M.HOOK))
        sideWindows(listOf(2, STORY + 1))

        // Goods down both sides, an aisle up the middle; the ladder and the strongbox at the back
        for (v in 2..d - 3) for (u in listOf(1, w - 2)) {
            val high = r.nextInt(3)
            for (y in 0..high) f.set(u, y, v, cargo())
        }
        for (v in 3..d - 4 step 2) for (u in listOf(2, w - 3)) if (r.nextBoolean()) f.set(u, 0, v, cargo())
        f.chest(1, 0, d - 2, Dir.E, Loot.HARBOUR)
        f.set(2, 0, d - 2, B(M.BARREL, Dir.N))
        for (y in 0..STORY) f.set(w - 2, y, d - 2, B(M.LADDER, Dir.N))
        f.set(mid, STORY - 2, 3, B(M.LANTERN, top = true))
        f.set(mid, STORY - 2, d - 4, B(M.LANTERN, top = true))
        // The loft: sacks and barrels along the back, more by the loft door to be lowered
        for (u in 1..w - 3) if (r.nextInt(3) > 0) f.set(u, STORY, d - 2, cargo())
        f.set(mid - 1, STORY, 1, B(M.CRATE))
        f.set(mid + 1, STORY, 1, B(M.HAY))
        f.set(mid, top - 1, d / 2, B(M.LANTERN, top = true))
        if (r.nextBoolean()) f.spawn(Spawn.VILLAGER, mid, 0, d / 2)
    }

    /**
     * A timber shed with its ridge along the quay: dark boards between posts on a stone sill, its front open
     * in two broad bays under a long beam, goods inside and a loft over the back.
     */
    private fun shed() {
        val top = STORY + 2
        val loft = 3
        val mid = w / 2
        clear(top)
        floorAt(-1, B(M.PAVE_YARD))
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            val onFront = v == 0 || v == d - 1
            val onSide = u == 0 || u == w - 1
            val post = (onFront && (onSide || u == mid)) || (onSide && v % 3 == 0)
            for (y in 0..top) f.set(u, y, v, when {
                y == 0 && !(v == 0 && !post) -> STONE
                y == top || y == loft && !(v == 0) -> B(M.TIMBER, axis = if (onFront) Axis.X else Axis.Z)
                post -> B(M.TIMBER)
                else -> B(M.PLANKS, v = 1)
            })
        }
        // The two bays across the front, open to the quay
        for (u in 1 until w - 1) if (u != mid) for (y in 0 until top - 1) f.set(u, y, 0, AIR)
        for (u in 0 until w) f.set(u, top - 1, 0, B(M.TIMBER, axis = Axis.X))
        Buildings.roof(f, w, d, 0, top, r.nextInt(3), false, B(M.PLANKS, v = 1))
        sideWindows(listOf(loft + 1))
        // The loft over the back half, up a ladder
        f.fill(1, loft, d / 2, w - 2, loft, d - 2, B(M.PLANKS))
        for (y in 0..loft) f.set(1, y, d / 2 + 1, B(M.LADDER, Dir.E))
        for (u in 2..w - 2) if (r.nextInt(3) > 0) f.set(u, loft + 1, d - 2, cargo())
        for (u in 2..w - 3) for (v in d / 2 + 1..d - 2) if (r.nextInt(4) == 0) f.set(u, 0, v, cargo())
        // Goods below, a cart, a lantern on each beam
        for (u in listOf(2, w - 3)) {
            f.set(u, 0, 2, cargo())
            f.set(u, 1, 2, cargo())
        }
        f.chest(w - 2, 0, d - 2, Dir.W, Loot.HARBOUR)
        f.set(mid, loft - 1, 2, B(M.LANTERN, top = true))
        f.set(mid, top - 1, d - 3, B(M.LANTERN, top = true))
        f.set(mid, loft - 1, d / 2 + 1, B(M.LANTERN, top = true))
    }

    /** Something to stack: a crate, a barrel or a bale. */
    private fun cargo(): B = when (r.nextInt(5)) {
        0, 1 -> B(M.CRATE)
        2, 3 -> B(M.BARREL)
        else -> B(M.HAY)
    }

    // ---- The harbourmaster's house ----

    /**
     * The harbourmaster's house: stone below and timber above, three floors under a steep roof with a
     * square stone lookout on the corner over the square, the harbour's flag flying from it. The office
     * is on the ground floor (the charts, the ledger, the strongbox), the harbourmaster's rooms upstairs.
     */
    private fun harbourmaster() {
        val floors = 3
        val top = floors * STORY - 1
        val door = w / 2
        clear(top)
        floorAt(-1, B(M.PLANKS, v = 1))
        for (k in 1 until floors) floorAt(k * STORY - 1, B(M.PLANKS))
        floorAt(top, B(M.PLANKS))
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            for (y in 0..STORY - 2) f.set(u, y, v, if (y == 0) DARK else STONE)
        }
        timberStorey(STORY - 1, top)
        Buildings.roof(f, w, d, 0, top, r.nextInt(2), false, PLASTER)
        // The door under a stone hood, the harbour's banner over it
        f.door(door, 0, 0, hingeRight = true)
        f.set(door, 2, 0, TRIM)
        for (u in door - 1..door + 1) f.set(u, 3, -1, B(M.STONE_SLAB, top = true))
        f.set(door - 1, 1, -1, B(M.LANTERN))
        f.set(door + 1, 2, -1, B(M.BANNER, Dir.N, color = CityBuilder.BANNER_COLOR))
        // Windows: small below, broad on the timbered floors, with shutters
        for (u in listOf(1, 2, w - 3, w - 2)) if (u != door) f.set(u, 1, 0, WINDOW)
        for (k in 1 until floors) for (u in listOf(1, 2, door, w - 3, w - 2)) for (h in 0..1) {
            f.set(u, k * STORY + h, 0, WINDOW)
        }
        for (k in 1 until floors) for (h in 0..1) {
            f.set(0, k * STORY + h, -1, B(M.SHUTTER, Dir.N, open = true))
            f.set(w - 1, k * STORY + h, -1, B(M.SHUTTER, Dir.N, open = true))
        }
        sideWindows(listOf(1, STORY, STORY + 1, 2 * STORY, 2 * STORY + 1))

        // The office: the harbourmaster's desk, the charts and the ledger, the strongbox
        val desk = 3
        f.set(door - 1, 0, desk, TABLE)
        f.set(door, 0, desk, TABLE)
        f.set(door - 1, 1, desk, B(M.CANDLE))
        f.set(door, 0, desk + 1, chair(Dir.N))
        f.set(1, 0, desk, B(M.CARTOGRAPHY_TABLE))
        f.set(w - 2, 0, 2, B(M.LECTERN, Dir.W))
        for (u in 1..3) for (y in 0..1) f.set(u, y, d - 2, B(M.BOOKSHELF))
        f.chest(w - 3, 0, d - 2, Dir.N, Loot.HARBOUR)
        f.set(door, STORY - 2, d / 2, B(M.LANTERN, top = true))
        f.spawn(Spawn.VILLAGER, door, 0, desk + 2, Dir.N)
        // Up the back to the rooms above: the harbourmaster's bed, a chest, a lamp in each
        for (y in 0 until top) f.set(w - 2, y, d - 2, B(M.LADDER, Dir.N))
        f.bed(1, STORY, d - 2, Dir.E, Dye.BLUE)
        f.chest(1, STORY, d - 3, Dir.E, Loot.HOUSE)
        f.set(door, 2 * STORY - 2, d / 2, B(M.LANTERN, top = true))
        f.set(door, STORY, 1, B(M.CARTOGRAPHY_TABLE))
        for (u in 1..2) f.set(u, 2 * STORY, d - 2, B(M.BARREL, Dir.N))
        f.set(door, top - 1, d / 2, B(M.LANTERN, top = true))

        // The lookout on the corner over the square: stone up past the eaves, windows all round, a hipped cap and the flag
        val left = square != Dir.E
        val u0 = if (left) 0 else w - LOOKOUT
        val lookout = top + LOOKOUT_RISE
        for (u in u0 until u0 + LOOKOUT) for (v in 0 until LOOKOUT) {
            val rim = u == u0 || u == u0 + LOOKOUT - 1 || v == 0 || v == LOOKOUT - 1
            for (y in 0..lookout) {
                val b = when {
                    !rim -> if (y > top) AIR else null
                    y == 0 -> DARK
                    y == top || y == lookout -> TRIM
                    // Windows looking out every way from the lookout room
                    y in lookout - 3..lookout - 2 && (u == u0 + LOOKOUT / 2 || v == LOOKOUT / 2) -> WINDOW
                    else -> STONE
                }
                if (b != null) f.set(u, y, v, b)
            }
            if (!rim) f.set(u, lookout, v, B(M.PLANKS))
        }
        // In from the top floor, and the ladder on up through a hatch
        f.set(if (left) u0 + LOOKOUT - 1 else u0, 2 * STORY, 2, AIR)
        f.set(if (left) u0 + LOOKOUT - 1 else u0, 2 * STORY + 1, 2, AIR)
        for (y in 2 * STORY until lookout) f.set(u0 + 1, y, LOOKOUT - 2, B(M.LADDER, Dir.N))
        f.set(u0 + 1, lookout, LOOKOUT - 2, B(M.TRAPDOOR, Dir.N, top = true))
        f.set(u0 + 2, lookout - 1, 1, B(M.LANTERN, top = true))
        hip(u0 - 1, -1, u0 + LOOKOUT, LOOKOUT, lookout + 1, ROOF_SLATE)
        val pole = u0 + LOOKOUT / 2 - (if (left) 1 else 0)
        for (y in lookout + 3..lookout + 7) f.set(pole, y, LOOKOUT / 2 - 1, B(M.FENCE))
        for (k in 1..2) for (y in lookout + 6..lookout + 7) f.set(pole + (if (left) -k else k), y, LOOKOUT / 2 - 1, B(M.WOOL, color = CityBuilder.BANNER_COLOR))
    }

    // ---- The fish market ----

    /**
     * The fish market: an open hall under a broad hipped roof on stone piers, two rows of stalls down it
     * with the day's catch in barrels and tubs, lanterns on chains from the tie beams, the fishmongers at
     * their counters, and the cold store across the back.
     */
    private fun fishMarket() {
        val top = STORY
        val mid = w / 2
        f.fill(0, -1, 0, w - 1, -1, d - 1, B(M.PAVE_PLAZA, v = 2))
        f.fill(0, 0, 0, w - 1, top, d - 1, AIR)
        val piersU = setOf(0, mid, w - 1)
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            val pier = (v == 0 || v == d - 1) && u in piersU || (u == 0 || u == w - 1) && (v % 4 == 0 || v == d - 1)
            if (v == d - 1) {
                for (y in 0 until top) f.set(u, y, v, if (y == 0) DARK else STONE)
            } else if (pier) for (y in 0 until top) f.set(u, y, v, if (y == 0) DARK else STONE)
            f.set(u, top, v, B(M.TIMBER, axis = if (v == 0 || v == d - 1) Axis.X else Axis.Z))
        }
        // Tie beams across, the roof over all
        for (v in 4 until d - 1 step 4) for (u in 1 until w - 1) f.set(u, top, v, B(M.TIMBER, axis = Axis.X))
        hip(-1, -1, w, d, top + 1, r.nextInt(2))
        // The stalls down both sides of the aisle: counters with the catch on them, the fishmongers behind
        for (v in 2..d - 3) for (u in listOf(2, w - 3)) {
            val stall = (v - 2) % 4
            f.set(u, 0, v, if (stall == 3) AIR else TABLE)
            when (stall) {
                0 -> f.set(u, 1, v, B(M.CANDLE))
                1 -> f.set(u, 0, v, B(M.CAULDRON, v = 2))
                2 -> f.set(u, 0, v, B(M.BARREL))
            }
            val behind = if (u < mid) u - 1 else u + 1
            if (stall == 1) f.set(behind, 0, v, B(M.BARREL, if (u < mid) Dir.E else Dir.W))
        }
        for (u in listOf(1, w - 2)) f.spawn(Spawn.VILLAGER, u, 0, 4, if (u < mid) Dir.E else Dir.W)
        // The cold store along the back: barrels of salt and ice, the fisherman's own barrel by the door
        for (u in 1..w - 2) if (u != mid) f.set(u, 0, d - 2, B(M.BARREL, Dir.N))
        for (u in 1..w - 2 step 2) f.set(u, 1, d - 2, B(M.BARREL))
        f.chest(mid, 0, d - 2, Dir.N, Loot.HARBOUR)
        f.set(mid, 0, d - 4, B(M.BARREL))
        for (v in listOf(3, d - 4)) {
            for (y in top - 1 until top) f.set(mid, y, v, B(M.CHAIN))
            f.set(mid, top - 2, v, B(M.LANTERN, top = true))
        }
        f.spawn(Spawn.CAT, mid, 0, d / 2)
    }

    // ---- The chandlery ----

    /**
     * A chandler's shop, narrow and tall with its gable to the quay: a counter in the window with rope,
     * lamps and tar behind it, barrels of stores, and the chandler's room upstairs.
     */
    private fun chandlery() {
        val top = 2 * STORY - 1
        val door = w - 2
        clear(top)
        floorAt(-1, B(M.PLANKS, v = 1))
        floorAt(STORY - 1, B(M.PLANKS))
        floorAt(top, B(M.PLANKS))
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            for (y in 0..STORY - 2) f.set(u, y, v, if (y == 0) DARK else STONE)
        }
        timberStorey(STORY - 1, top)
        Buildings.roof(f, w, d, 0, top, r.nextInt(3), true, PLASTER)
        f.door(door, 0, 0)
        for (u in 1..3) f.set(u, 1, 0, WINDOW)
        for (u in 1..w - 2) for (h in 0..1) if (u != w / 2) f.set(u, STORY + h, 0, WINDOW)
        f.set(door, 2, -1, B(M.BANNER, Dir.N, color = Dye.BROWN))
        f.set(1, 2, -1, B(M.LANTERN))
        sideWindows(listOf(STORY, STORY + 1))
        // The counter in the window, rope and stores behind it
        for (u in 1..3) f.set(u, 0, 2, TABLE)
        f.set(2, 1, 2, B(M.LANTERN))
        f.set(1, 0, 4, B(M.BARREL, Dir.E))
        f.set(1, 1, 4, B(M.CARPET, color = Dye.BROWN))
        for (v in 5..d - 3) f.set(1, 0, v, if (v % 2 == 0) B(M.BARREL, Dir.E) else B(M.CRATE))
        f.set(2, 0, 4, B(M.BARREL))
        f.spawn(Spawn.VILLAGER, 2, 0, 3, Dir.N)
        f.set(w / 2, STORY - 2, d / 2, B(M.LANTERN, top = true))
        for (y in 0 until STORY) f.set(w - 2, y, d - 2, B(M.LADDER, Dir.N))
        f.bed(1, STORY, d - 2, Dir.E, Dye.BROWN)
        f.chest(w - 3, STORY, d - 2, Dir.N, Loot.HOUSE)
        f.set(w / 2, top - 1, d / 2, B(M.LANTERN, top = true))
    }

    // ---- Pieces ----

    private fun onRing(u: Int, v: Int): Boolean = u == 0 || u == w - 1 || v == 0 || v == d - 1

    /** Everything inside the walls cleared from the floor up to [top]. */
    private fun clear(top: Int) = f.fill(1, 0, 1, w - 2, top, d - 2, AIR)

    /** A floor (or a ceiling) right across the inside at [y]. */
    private fun floorAt(y: Int, b: B) = f.fill(1, y, 1, w - 2, y, d - 2, b)

    /** Windows down the sides at each of [heights], every fourth block, on the sides that don't back onto a neighbour. */
    private fun sideWindows(heights: List<Int>) {
        for (v in 2 until d - 1 step 4) for (y in heights) {
            if (!shared[0]) f.set(0, y, v, WINDOW)
            if (!shared[1]) f.set(w - 1, y, v, WINDOW)
        }
    }

    /** Timber framing and plaster from [y0] up to [y1] round the footprint, posts at the corners and every fourth block. */
    private fun timberStorey(y0: Int, y1: Int) {
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            val onFront = v == 0 || v == d - 1
            val onSide = u == 0 || u == w - 1
            val post = (onFront && u % 4 == 0) || (onSide && v % 4 == 0)
            for (y in y0..y1) {
                f.set(u, y, v, when {
                    onFront && onSide -> B(M.TIMBER)
                    (y + 1) % STORY == 0 -> B(M.TIMBER, axis = if (onFront) Axis.X else Axis.Z)
                    post -> B(M.TIMBER)
                    else -> PLASTER
                })
            }
        }
    }

    /** A hipped roof over [u0], [v0] to [u1], [v1], sloping in from every side from [y]. */
    private fun hip(u0: Int, v0: Int, u1: Int, v1: Int, y: Int, palette: Int) {
        var k = 0
        while (true) {
            val a0 = u0 + k
            val a1 = u1 - k
            val b0 = v0 + k
            val b1 = v1 - k
            if (a1 - a0 <= 1 || b1 - b0 <= 1) {
                for (u in a0..a1) for (v in b0..b1) {
                    f.set(u, y + k, v, B(M.ROOF, v = palette))
                    f.set(u, y + k + 1, v, B(M.ROOF_SLAB, v = palette))
                }
                return
            }
            for (u in a0..a1) {
                f.set(u, y + k, b0, B(M.ROOF_STAIRS, Dir.S, v = palette))
                f.set(u, y + k, b1, B(M.ROOF_STAIRS, Dir.N, v = palette))
            }
            for (v in b0 + 1 until b1) {
                f.set(a0, y + k, v, B(M.ROOF_STAIRS, Dir.E, v = palette))
                f.set(a1, y + k, v, B(M.ROOF_STAIRS, Dir.W, v = palette))
            }
            k++
        }
    }

    /** A seat, looking toward [looks]. */
    private fun chair(looks: Dir): B = B(M.PLANK_STAIRS, looks.opposite)

    companion object {
        const val STORY = Buildings.STORY
        const val ROOF_SLATE = 0
        /** The harbourmaster's lookout: its width, and how far it rises over the eaves. */
        const val LOOKOUT = 4
        const val LOOKOUT_RISE = 5

        val AIR = B.AIR
        val STONE = B(M.STONE)
        val TRIM = B(M.WALL_TRIM)
        val DARK = B(M.WALL_DARK)
        val PLASTER = B(M.PLASTER)
        val WINDOW = B(M.WINDOW)
        val TABLE = B(M.PLANK_SLAB, top = true)
    }
}
