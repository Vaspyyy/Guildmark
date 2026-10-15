package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * The inn on the main road, 13 across and 9 deep, drawn facing north: a stone ground floor under a timbered upper
 * storey that juts out over the forecourt, and a long steep roof. Downstairs the common room, a great fireplace at
 * one end, tables and chairs, the stair up, and the bar across the far corner with the innkeeper behind it; upstairs
 * a passage along the back and three little rooms with a bed each.
 */
internal class FarmInn(f: Frame, b: FarmBuilding, r: Random) : FarmPiece(f, b, r) {
    override fun draw() {
        val top = 2 * STORY - 1
        val floor = STORY - 1
        val door = w / 2
        val palette = ROOFS[r.nextInt(ROOFS.size)]
        val stoneAbove = r.nextInt(4) == 0
        val mirror = r.nextBoolean()
        val bedColors = IntArray(3) { r.nextInt(BED_COLORS.size) }
        val signColor = SIGN_COLORS[r.nextInt(SIGN_COLORS.size)]
        val tableColor = Dye.entries[r.nextInt(16)]
        val boards = B(M.PLANKS, v = r.nextInt(2))
        fun mu(u: Int) = if (mirror) w - 1 - u else u
        fun mdir(dir: Dir) = if (mirror && dir.alongX) dir.opposite else dir
        val infill = if (stoneAbove) STONE else PLASTER

        // The floors: boards down below, the upper storey's floor (jutting a block out in front) over the ground floor
        f.fill(1, -1, 1, w - 2, -1, d - 2, B(M.PLANKS, v = 1))
        f.fill(1, floor, -1, w - 2, floor, d - 2, boards)

        // The ground floor in stone between timbers
        for (u in 0 until w) for (v in 0 until d) {
            if (!onRing(u, v)) continue
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            val post = corner || ((v == 0 || v == d - 1) && u % 4 == 0) || ((u == 0 || u == w - 1) && v == d / 2)
            for (y in 0 until floor) f.set(u, y, v, if (post) TIMBER else if (y == 0) FOOTING else STONE)
        }
        // The upper storey over it, in plaster (or stone) and timber, jutting out a block in front: a beam under it, posts, a beam on top
        for (u in 0 until w) for (v in -1 until d) {
            val onFront = v == -1 || v == d - 1
            val onSide = u == 0 || u == w - 1
            if (!onFront && !onSide) continue
            val beamAxis = if (onFront) Axis.X else Axis.Z
            val corner = onSide && onFront
            val post = corner || (onFront && u % 4 == 0) || (onSide && (v == d / 2 || v == 0))
            for (y in floor..top) {
                val block = when {
                    corner -> TIMBER
                    y == floor || y == top -> B(M.TIMBER, axis = beamAxis)
                    post -> TIMBER
                    else -> infill
                }
                f.set(u, y, v, block)
            }
        }
        // The jutting front's brackets, and lanterns hung from it to light the forecourt
        for (u in intArrayOf(0, w / 2 - 2, w / 2 + 2, w - 1)) f.set(u, floor - 1, -1, B(M.PLANK_STAIRS, Dir.S, top = true))
        for (u in intArrayOf(3, 9)) f.set(u, floor - 1, -1, B(M.LANTERN, top = true))
        Buildings.roof(f, w, d, -1, top, palette, false, infill)
        gablet(door, 2, top, palette, infill)

        // The front door under its bracket, windows either side, a banner by it, benches under the windows
        f.door(door, 0, 0)
        f.set(door, 2, 0, TIMBER)
        for (u in intArrayOf(2, w - 3)) {
            window(u, 1, 0, Dir.N, height = 2)
            f.set(u, 0, -1, chair(Dir.N))
        }
        f.set(door - 1, 1, -1, B(M.BANNER, Dir.N, color = signColor))
        f.set(door + 1, 1, -1, B(M.BANNER, Dir.N, color = signColor))
        // The back door, windows, a torch to see by
        f.door(door, 0, d - 1, Dir.N)
        f.set(door, 2, d - 1, TIMBER)
        for (u in intArrayOf(2, w - 3)) window(u, 1, d - 1, Dir.S, height = 2)
        f.set(door + 1, 1, d, B(M.TORCH, Dir.S))
        f.set(door - 1, 1, d, B(M.TORCH, Dir.S))
        // The sides: windows on the end away from the fire, and all along the upper storey
        for (v in intArrayOf(2, d - 3)) window(mu(w - 1), 1, v, mdir(Dir.E), height = 2)
        for (u in intArrayOf(2, door, w - 3)) {
            window(u, STORY + 1, -1, Dir.N, height = 2)
            window(u, STORY + 1, d - 1, Dir.S, height = 2)
        }
        for (v in intArrayOf(2, d - 3)) for (u in intArrayOf(0, w - 1)) window(u, STORY + 1, v, if (u == 0) Dir.W else Dir.E, height = 2)

        // The great fireplace in the gable end, its chimney up through the roof
        val hv = d / 2
        for (dv in -1..1) for (y in 0..2) f.set(mu(1), y, hv + dv, FOOTING)
        f.set(mu(1), 0, hv, B(M.CAMPFIRE))
        f.set(mu(1), 1, hv, AIR)
        chimney(mu(0), hv, 0, top + (d + 1) / 2 + 2)

        // The stair up along the back wall, stone under it
        for (k in 0..3) {
            f.set(mu(1 + k), k, d - 2, B(M.PLANK_STAIRS, mdir(Dir.E)))
            for (y in 0 until k) f.set(mu(1 + k), y, d - 2, if (y == 0) B(M.BARREL, Dir.N) else B(M.PLANKS))
        }
        f.set(mu(2), floor, d - 2, AIR)
        f.set(mu(3), floor, d - 2, AIR)

        // The common room: tables with candles and chairs about them, lantern light from the ceiling
        for (tu in intArrayOf(3, 4)) {
            f.set(mu(tu), 0, 2, TABLE)
            f.set(mu(tu), 1, 2, B(M.CANDLE))
            f.set(mu(tu), 0, 1, chair(Dir.S))
            f.set(mu(tu), 0, 3, chair(Dir.N))
            f.set(mu(tu), 0, d - 4, TABLE)
            f.set(mu(tu), 0, d - 5, chair(Dir.S))
        }
        f.set(mu(3), 1, d - 4, B(M.CARPET, color = tableColor))
        f.set(mu(4), 1, d - 4, B(M.CANDLE))
        f.set(mu(2), 0, d - 4, chair(mdir(Dir.E)))
        f.set(mu(5), 0, d - 4, chair(mdir(Dir.W)))
        for (tu in intArrayOf(8, 9)) {
            f.set(mu(tu), 0, 2, TABLE)
            f.set(mu(tu), 1, 2, B(M.CANDLE))
            f.set(mu(tu), 0, 1, chair(Dir.S))
        }
        f.set(mu(10), 0, 2, chair(mdir(Dir.W)))
        f.set(mu(7), 0, 2, chair(mdir(Dir.E)))
        for (u in intArrayOf(3, 9)) for (v in intArrayOf(3, d - 3)) f.set(u, floor - 1, v, B(M.LANTERN, top = true))

        // The bar across the back corner: a counter, stools along it, shelves of barrels behind, the innkeeper
        val bar = d - 4
        for (u in 7..w - 2) f.set(mu(u), 0, bar, B(M.PLANKS))
        for (u in 8..w - 3) f.set(mu(u), 0, bar - 1, chair(Dir.S))
        f.set(mu(8), 1, bar, B(M.CANDLE))
        f.set(mu(w - 3), 1, bar, B(M.CANDLE))
        for (u in 8..w - 3) {
            f.set(mu(u), 0, d - 2, B(M.BARREL, Dir.N))
            f.set(mu(u), 1, d - 2, if (u % 2 == 0) B(M.BOOKSHELF) else B(M.BARREL, Dir.N))
        }
        f.set(mu(w - 2), 1, d - 2, B(M.BARREL, Dir.N))
        f.chest(mu(w - 2), 0, d - 2, mdir(Dir.W), Loot.TAVERN)
        f.resident(Folk.INNKEEPER, mu(w - 4), 0, bar + 1, facing = Dir.N)
        f.spawn(Spawn.CAT, mu(2), 0, hv - 2)

        // Upstairs: rooms across the front, a passage behind them with a door to each
        for (v in 0..d - 5) for (y in STORY..STORY + 2) {
            for (u in intArrayOf(4, 8)) f.set(u, y, v, B(M.PLANKS))
        }
        for (u in 1..w - 2) for (y in STORY..STORY + 2) f.set(u, y, d - 4, B(M.PLANKS))
        for (room in 0..2) {
            val u0 = 1 + room * 4
            val du = u0 + 1
            f.door(du, STORY, d - 4, Dir.N)
            f.bed(du, STORY, 1, Dir.N, BED_COLORS[bedColors[room]])
            f.set(u0, STORY, 0, B(M.BARREL, Dir.S))
            f.chest(u0 + 2, STORY, d - 5, Dir.N, Loot.HOUSE)
            f.fill(u0, top, 2, u0 + 2, top, 2, B(M.TIMBER, axis = Axis.X))
            f.set(du, top - 1, 2, B(M.LANTERN, top = true))
        }
        f.fill(1, top, d - 3, w - 2, top, d - 3, B(M.TIMBER, axis = Axis.X))
        for (u in intArrayOf(3, 9)) f.set(u, top - 1, d - 3, B(M.LANTERN, top = true))
    }

    /**
     * A little gable over the middle of the front, its wall flush with the front wall (a window in it) and its roof
     * rising out of the main roof's front slope, the two meeting in valleys. [c] is its middle, [h] how many blocks it
     * spreads each side of that at its foot; [top] is the main roof's eave.
     */
    private fun gablet(c: Int, h: Int, top: Int, palette: Int, infill: B) {
        val base = top + 1
        for (k in 0..h) for (u in c - h + k..c + h - k) f.set(u, base + k, -1, if (u == c && k < h) WINDOW else infill)
        for (k in 0..h + 1) {
            val y = base + k
            // The main roof's front slope stands at y = top + v + 2 over row v: only what's above it shows
            for (v in -1..y - top - 2) {
                if (k == h + 1) {
                    f.set(c, y, v, B(M.ROOF, v = palette))
                    f.set(c, y + 1, v, B(M.ROOF_SLAB, v = palette))
                } else {
                    f.set(c - h - 1 + k, y, v, B(M.ROOF_STAIRS, Dir.E, v = palette))
                    f.set(c + h + 1 - k, y, v, B(M.ROOF_STAIRS, Dir.W, v = palette))
                }
            }
        }
    }

    companion object {
        /** The inn's roofs: dark timber or slate, tile, and now and then thatch. */
        val ROOFS = intArrayOf(ROOF_WOOD, ROOF_WOOD, ROOF_SLATE, ROOF_SLATE, ROOF_RED, ROOF_RED, ROOF_THATCH)
        /** The colours of the banners by the door. */
        val SIGN_COLORS = listOf(Dye.RED, Dye.YELLOW, Dye.BLUE, Dye.GREEN, Dye.PURPLE, Dye.ORANGE)
    }
}
