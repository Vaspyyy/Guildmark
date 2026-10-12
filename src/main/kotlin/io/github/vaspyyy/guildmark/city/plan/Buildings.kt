package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/** Draws the city's buildings, each in its own frame (facing north) and from its own seed. */
class Buildings(private val plan: CityPlan, private val canvas: Canvas) {
    fun draw(b: Building) {
        val f = Frame(canvas, b.lot, b.front, b.y)
        val r = Random(b.seed)
        when (b.kind) {
            Kind.HOUSE, Kind.WORKSHOP -> townhouse(f, b, r)
            Kind.CATHEDRAL, Kind.PALACE, Kind.MANOR -> Landmarks(f, b, r).draw()
            else -> GreatBuildings(f, b, r).draw()
        }
    }

    /**
     * A townhouse: a stone ground floor with one or two storeys of timber and plaster over it (or stone
     * all the way up), a steep gabled roof, a chimney, shutters on the upper windows, and the upper
     * storeys sometimes jutting out over the street. Workshops are set up round their trade.
     */
    private fun townhouse(f: Frame, b: Building, r: Random) {
        val w = f.w
        val d = f.d
        val floors = when {
            w >= 7 && d >= 8 && r.nextInt(10) < 3 -> 3
            w <= 5 && r.nextInt(5) == 0 -> 1
            else -> 2
        }
        val top = floors * STORY - 1
        val stone = r.nextInt(4) == 0
        val jetty = floors >= 2 && !stone && r.nextInt(5) < 2
        val roof = r.nextInt(3)
        val gableToStreet = w <= 7 || (w < d && r.nextBoolean())
        val chimney = r.nextInt(3) != 0
        val bed = b.kind == Kind.HOUSE && r.nextInt(100) < BED_CHANCE
        val front = if (jetty) -1 else 0
        val sides = b.shared

        // Clear the inside, then the floors
        f.fill(1, 0, 1, w - 2, top - 1, d - 2, AIR)
        if (jetty) f.fill(1, STORY - 1, 0, w - 2, top - 1, 0, AIR)
        f.fill(1, -1, 1, w - 2, -1, d - 2, B(M.PLANKS, v = 1))
        for (floor in 1 until floors) f.fill(1, floor * STORY - 1, if (jetty) 0 else 1, w - 2, floor * STORY - 1, d - 2, B(M.PLANKS))
        f.fill(1, top, front + 1, w - 2, top, d - 2, B(M.PLANKS))

        // The ground storey, in stone
        for (u in 0 until w) for (v in 0 until d) {
            if (u != 0 && u != w - 1 && v != 0 && v != d - 1) continue
            for (y in 0 until STORY - 1) f.set(u, y, v, STONE)
        }
        // Timber and plaster above, or stone with beams at each floor
        for (u in 0 until w) for (v in front until d) {
            val onFront = v == front || v == d - 1
            val onSide = u == 0 || u == w - 1
            if (!onFront && !onSide) continue
            val corner = onFront && onSide
            val along = if (onFront) u else v - front
            val length = if (onFront) w else d - front
            val post = corner || (length >= 7 && length % 2 == 1 && along == length / 2)
            for (y in STORY - 1..top) {
                val beam = (y + 1) % STORY == 0
                val b = when {
                    corner -> B(M.TIMBER)
                    beam -> B(M.TIMBER, axis = if (onFront) Axis.X else Axis.Z)
                    stone -> STONE
                    post -> B(M.TIMBER)
                    else -> PLASTER
                }
                f.set(u, y, v, b)
            }
        }
        // Brackets under the jutting front
        if (jetty) for (u in listOf(0, w / 2, w - 1)) f.set(u, STORY - 2, -1, B(M.PLANK_STAIRS, Dir.S, top = true))

        // Door, windows and shutters
        val door = b.door
        f.door(door, 0, 0)
        f.set(door, 2, 0, if (stone) B(M.WALL_TRIM) else STONE)
        for (u in listOf(1, w - 2)) if (kotlin.math.abs(u - door) > 0) f.set(u, 1, 0, WINDOW)
        if (!sides[Dir.W.ordinal]) for (v in listOf(2, d - 3)) f.set(0, 1, v, WINDOW)
        if (!sides[Dir.E.ordinal]) for (v in listOf(2, d - 3)) f.set(w - 1, 1, v, WINDOW)
        if (!sides[Dir.S.ordinal]) f.set(w / 2, 1, d - 1, WINDOW)
        for (floor in 1 until floors) {
            val y = floor * STORY
            var u = 1
            while (u <= w - 2) {
                if (isPost(u, w)) {
                    u++
                    continue
                }
                var end = u
                while (end + 1 <= w - 2 && !isPost(end + 1, w)) end++
                for (x in u..end) for (h in 0..1) f.set(x, y + h, front, WINDOW)
                for (h in 0..1) {
                    f.set(u - 1, y + h, front - 1, B(M.SHUTTER, Dir.N, open = true))
                    f.set(end + 1, y + h, front - 1, B(M.SHUTTER, Dir.N, open = true))
                }
                u = end + 1
            }
            if (!sides[Dir.W.ordinal]) for (v in listOf(2, d - 3)) for (h in 0..1) f.set(0, y + h, v, WINDOW)
            if (!sides[Dir.E.ordinal]) for (v in listOf(2, d - 3)) for (h in 0..1) f.set(w - 1, y + h, v, WINDOW)
            if (!sides[Dir.S.ordinal]) for (h in 0..1) f.set(w / 2, y + h, d - 1, WINDOW)
        }
        // A torch by the door
        f.set(door + 1, 2, -1, B(M.TORCH, Dir.N))

        roof(f, w, d, front, top, roof, gableToStreet, if (stone) STONE else PLASTER)

        // Inside
        if (floors > 1) for (y in 0..(floors - 1) * STORY) f.set(w - 2, y, d - 2, B(M.LADDER, Dir.N))
        if (chimney) {
            f.set(1, 0, d - 2, B(M.FURNACE, Dir.E))
            for (y in 1..top + 3) f.set(1, y, d - 2, STONE)
            f.set(1, top + 4, d - 2, B(M.CAMPFIRE))
        }
        val trade = b.trade
        if (trade != null) {
            f.set(1, 0, 1, B(trade.site, Dir.S))
            f.set(w - 2, 0, 1, B(M.BARREL, Dir.S))
            // The trade's colours over the door
            f.set(door, 2, -1, B(M.BANNER, Dir.N, color = TRADE_COLORS[trade.ordinal]))
        } else {
            f.set(1, 0, 1, B(M.CRAFTING_TABLE))
            f.set(w - 2, 0, 1, B(M.BARREL, Dir.S))
            // A bed downstairs, where whoever lives here can reach it (villagers don't climb ladders)
            if (bed) f.bed(w / 2, 0, d - 3, Dir.S, BED_COLORS[r.nextInt(BED_COLORS.size)])
            else if (w >= 6 && d >= 6) {
                val tv = d / 2
                f.set(w / 2, 0, tv, B(M.FENCE))
                f.set(w / 2, 1, tv, B(M.CARPET, color = Dye.entries[r.nextInt(16)]))
                f.set(w / 2 - 1, 0, tv, B(M.PLANK_STAIRS, Dir.W))
                if (w / 2 + 1 < w - 1) f.set(w / 2 + 1, 0, tv, B(M.PLANK_STAIRS, Dir.E))
            }
        }
        f.set(w / 2, STORY - 2, d / 2 - 1, B(M.LANTERN, top = true))
        if (bed) f.spawn(Spawn.VILLAGER, w / 2, 0, 1)
        if (floors > 1) {
            val y = STORY
            f.chest(w - 2, y, 1, Dir.S, Loot.HOUSE)
            f.set(1, y, 1, if (r.nextBoolean()) B(M.BOOKSHELF) else B(M.BARREL, Dir.S))
            f.set(w / 2, y + STORY - 2, d / 2 - 1, B(M.LANTERN, top = true))
        }
        // Every other workshop has someone working in it; the rest wait for a villager to take them on
        if (trade != null && b.id % 2 == 0) f.spawn(Spawn.VILLAGER, w / 2, 0, d / 2)
        // Townsfolk in some of the houses no villager has: someone working at the bench (now and then a
        // noble instead), and maybe a child
        if (b.kind == Kind.HOUSE && !bed && r.nextInt(3) == 0) {
            if (r.nextInt(5) == 0) f.resident(Folk.NOBLE, w / 2, 0, 1)
            else f.resident(Folk.CITIZEN, w / 2, 0, 1, work = f.at(1, 0, 1))
            if (r.nextBoolean()) f.resident(Folk.CHILD, w / 2, 0, 2)
        }
    }

    /** Timber houses have a post in the middle of a front at least seven wide and odd; windows go round it. */
    private fun isPost(u: Int, w: Int): Boolean = w >= 7 && w % 2 == 1 && u == w / 2

    companion object {
        /** Floor to floor. */
        const val STORY = 4
        /** How many houses in a hundred have a bed (and someone living in them). */
        const val BED_CHANCE = 30

        val AIR = B.AIR
        val STONE = B(M.STONE)
        val PLASTER = B(M.PLASTER)
        val WINDOW = B(M.WINDOW)
        val BED_COLORS = listOf(Dye.RED, Dye.BLUE, Dye.GREEN, Dye.YELLOW, Dye.WHITE, Dye.BROWN, Dye.CYAN)
        val TRADE_COLORS = listOf(Dye.GRAY, Dye.LIME, Dye.WHITE, Dye.ORANGE, Dye.BROWN, Dye.GREEN, Dye.LIGHT_BLUE, Dye.RED)

        /**
         * A steep gabled roof over a w by d building whose walls end at [top], overhanging by a block all
         * round; [front] is where the front wall stands (-1 for a jutting front). With [gableToStreet]
         * the ridge runs front to back and the gable faces the street; the gable ends are filled with [infill].
         */
        fun roof(f: Frame, w: Int, d: Int, front: Int, top: Int, palette: Int, gableToStreet: Boolean, infill: B) {
            val stairs = { dir: Dir -> B(M.ROOF_STAIRS, dir, v = palette) }
            if (gableToStreet) {
                var k = 0
                while (true) {
                    val left = k - 1
                    val right = w - k
                    val y = top + k
                    if (left > right) break
                    if (left == right) {
                        for (v in front - 1..d) {
                            f.set(left, y, v, B(M.ROOF, v = palette))
                            f.set(left, y + 1, v, B(M.ROOF_SLAB, v = palette))
                        }
                        break
                    }
                    for (v in front - 1..d) {
                        f.set(left, y, v, stairs(Dir.E))
                        f.set(right, y, v, stairs(Dir.W))
                    }
                    // The gable ends under this course
                    if (k >= 1) for (u in k..w - 1 - k) for (v in listOf(front, d - 1)) {
                        f.set(u, y, v, if (u == w / 2 && k == 2 && w >= 5) WINDOW else infill)
                    }
                    if (left + 1 == right) break
                    k++
                }
            } else {
                var k = 0
                while (true) {
                    val near = front - 1 + k
                    val far = d - k
                    val y = top + k
                    if (near > far) break
                    if (near == far) {
                        for (u in -1..w) {
                            f.set(u, y, near, B(M.ROOF, v = palette))
                            f.set(u, y + 1, near, B(M.ROOF_SLAB, v = palette))
                        }
                        break
                    }
                    for (u in -1..w) {
                        f.set(u, y, near, stairs(Dir.S))
                        f.set(u, y, far, stairs(Dir.N))
                    }
                    if (k >= 1) for (v in near + 1 until far) for (u in listOf(0, w - 1)) {
                        f.set(u, y, v, if (v == (front + d) / 2 && k == 2) WINDOW else infill)
                    }
                    if (near + 1 == far) break
                    k++
                }
            }
        }
    }
}
