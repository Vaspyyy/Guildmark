package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * The homes of the country, each drawn facing north in its own frame: the cottages along the roads by the
 * gates, one room and a hearth under a steep roof, and the farmhouses of the farmsteads, a floor and a loft
 * with a porch before the door. Walls, roofs and layouts are drawn from the building's own seed, so a hamlet's
 * cottages are all different.
 */
internal class FarmHomes(f: Frame, b: FarmBuilding, r: Random) : FarmPiece(f, b, r) {
    override fun draw() = if (b.kind == FarmKind.COTTAGE) cottage() else farmhouse()

    /**
     * A cottage: a stone footing under walls of plaster and timber, stone or logs, a steep roof of thatch, tile
     * or dark timber, a chimney, shuttered windows and a lantern by the door. Inside, open to the rafters: a hearth
     * in one corner, a bed in the other, a table, and a bench to work at with someone working at it, now and then.
     */
    private fun cottage() {
        val top = 3
        val door = (w - 1) / 2
        val walling = Walling.entries[WALLINGS[r.nextInt(WALLINGS.size)]]
        val palette = ROOFS[r.nextInt(ROOFS.size)]
        val gableToStreet = if (w != d) w < d else r.nextBoolean()
        val hipped = r.nextInt(5) == 0
        val mirror = r.nextBoolean()
        val tenant = r.nextInt(100) < 55
        val child = r.nextInt(100) < 45
        val loom = r.nextInt(3) == 0
        val bedColor = BED_COLORS[r.nextInt(BED_COLORS.size)]
        val tableColor = Dye.entries[r.nextInt(16)]
        fun mu(u: Int) = if (mirror) w - 1 - u else u
        fun mdir(dir: Dir) = if (mirror && dir.alongX) dir.opposite else dir

        // The floor, the walls and the roof
        f.fill(1, -1, 1, w - 2, -1, d - 2, B(M.PLANKS, v = 1))
        walls(0, top) { u, v, y -> wall(walling, u, v, y, top) }
        if (hipped) hip(top, palette) else Buildings.roof(f, w, d, 0, top, palette, gableToStreet, gable(walling))

        // The door with a timber lintel, windows each side of it and in the other walls, a lantern by it
        f.door(door, 0, 0)
        f.set(door, 2, 0, B(M.TIMBER, axis = Axis.X))
        for (u in intArrayOf(door - 2, door + 2)) if (u in 1..w - 2) window(u, 1, 0, Dir.N)
        window(0, 1, d / 2, Dir.W)
        window(w - 1, 1, d / 2, Dir.E)
        window(w / 2, 1, d - 1, Dir.S)
        f.set(door + 1, 1, -1, B(M.TORCH, Dir.N))

        // The hearth in a back corner, its chimney up past the ridge
        val hu = mu(1)
        val hv = d - 2
        val span = if (hipped) minOf(w, d) else if (gableToStreet) w else d
        f.set(hu, 0, hv, B(M.CAMPFIRE))
        f.set(mu(2), 0, hv, FOOTING)
        f.set(hu, 0, hv - 1, FOOTING)
        chimney(hu, hv, 2, top + span / 2 + 2)

        // A table in a nook by the fire, a bed in the other corner with a chest by it, the bench and a barrel by the door
        val tu = mu(2)
        val tv = hv - 1
        f.set(tu, 0, tv, TABLE)
        f.set(tu, 1, tv, B(M.CARPET, color = tableColor))
        f.set(mu(3), 0, tv, chair(mdir(Dir.W)))
        f.set(tu, 0, tv - 1, chair(Dir.S))
        f.bed(mu(w - 2), 0, d - 3, Dir.S, bedColor)
        f.chest(mu(w - 3), 0, hv, Dir.N, Loot.HOUSE)
        val bench = mu(w - 2)
        f.set(bench, 0, 1, if (loom) B(M.LOOM, Dir.S) else B(M.CRAFTING_TABLE))
        f.set(mu(1), 0, 1, B(M.BARREL, Dir.S))

        // Light from the beams
        for (v in intArrayOf(2, d - 3)) f.fill(1, top, v, w - 2, top, v, B(M.TIMBER, axis = Axis.X))
        f.set(w / 2, top - 1, 2, B(M.LANTERN, top = true))
        f.set(w / 2, top - 1, d - 3, B(M.LANTERN, top = true))

        // Whoever works at the bench, and a child by the fire
        if (tenant) f.resident(Folk.CITIZEN, mu(w - 3), 0, 1, work = f.at(bench, 0, 1))
        if (tenant && child) f.resident(Folk.CHILD, mu(3), 0, hv - 2)
    }

    /**
     * A farmhouse: a floor and a loft under a long roof, a chimney on one gable end, and a porch over the door.
     * Downstairs the hall: a fire at one end, a long table and benches, the kitchen bench, beds and a chest at the
     * far end; a ladder to the loft, where more beds lie under the rafters.
     */
    private fun farmhouse() {
        val top = 4
        val door = w / 2
        val walling = Walling.entries[FARM_WALLINGS[r.nextInt(FARM_WALLINGS.size)]]
        val palette = FARM_ROOFS[r.nextInt(FARM_ROOFS.size)]
        val mirror = r.nextBoolean()
        val tenant = r.nextInt(100) < 70
        val child = r.nextInt(100) < 50
        val loom = r.nextInt(3) == 0
        val bedColor = BED_COLORS[r.nextInt(BED_COLORS.size)]
        val loftColor = BED_COLORS[r.nextInt(BED_COLORS.size)]
        fun mu(u: Int) = if (mirror) w - 1 - u else u
        fun mdir(dir: Dir) = if (mirror && dir.alongX) dir.opposite else dir

        // The floor, the walls, the loft's floor and the roof
        f.fill(1, -1, 1, w - 2, -1, d - 2, B(M.PLANKS, v = 1))
        walls(0, top) { u, v, y -> wall(walling, u, v, y, top) }
        floorAt(top, B(M.PLANKS))
        Buildings.roof(f, w, d, 0, top, palette, false, gable(walling))

        // The door, a porch over it on two posts with a lantern hung under its roof, a window either side with a shutter
        // on the door's side only (the yard's corners are left to the woodpile and the compost), windows at the back
        f.door(door, 0, 0)
        f.set(door, 2, 0, B(M.TIMBER, axis = Axis.X))
        for (u in intArrayOf(door - 1, door + 1)) for (y in 0..2) f.set(u, y, -2, B(M.FENCE))
        for (u in door - 1..door + 1) {
            f.set(u, 3, -2, B(M.ROOF_STAIRS, Dir.S, v = palette))
            f.set(u, 4, -1, B(M.ROOF_STAIRS, Dir.S, v = palette))
        }
        f.set(door - 1, 3, -1, B(M.LANTERN, top = true))
        for (side in intArrayOf(-1, 1)) {
            window(door + 2 * side, 1, 0, Dir.N, shutters = false, height = 2)
            for (y in 1..2) f.set(door + side, y, -1, B(M.SHUTTER, Dir.N, open = true))
        }
        for (u in intArrayOf(w / 3, w - 1 - w / 3)) window(u, 1, d - 1, Dir.S, height = 2)
        window(mu(w - 1), 1, d / 2, mdir(Dir.E), height = 2)

        // The fire on the gable end, its chimney up past the ridge, a flat of stone before it
        val hv = d / 2
        for (dv in -1..1) for (y in 0..1) f.set(mu(1), y, hv + dv, FOOTING)
        f.set(mu(1), 0, hv, B(M.CAMPFIRE))
        f.set(mu(1), 1, hv, AIR)
        for (dv in -1..1) f.set(mu(1), 2, hv + dv, FOOTING)
        chimney(mu(0), hv, 0, top + d / 2 + 2)

        // The long table with benches by the fire, the kitchen bench and barrel by the door, beds at the far end
        val tc = w / 2
        for (dv in -1..1) f.set(tc, 0, hv + dv, TABLE)
        f.set(tc, 1, hv, B(M.CANDLE))
        f.set(tc - 1, 0, hv, chair(mdir(Dir.E)))
        f.set(tc + 1, 0, hv - 1, chair(mdir(Dir.W)))
        f.set(tc + 1, 0, hv + 1, chair(mdir(Dir.W)))
        val bench = mu(w - 2)
        f.set(bench, 0, 1, if (loom) B(M.LOOM, Dir.S) else B(M.CRAFTING_TABLE))
        f.set(mu(1), 0, 1, B(M.BARREL, Dir.S))
        f.bed(mu(w - 2), 0, d - 3, Dir.S, bedColor)
        if (w >= 9) f.bed(mu(w - 3), 0, d - 3, Dir.S, loftColor)
        f.chest(mu(w - 2), 0, d - 4, mdir(Dir.W), Loot.HOUSE)

        // The ladder to the loft, and the loft under the rafters: beds, a chest, a lantern from the ridge
        for (y in 0..top + 1) f.set(mu(1), y, d - 2, B(M.LADDER, Dir.N))
        f.bed(mu(2), top + 1, d - 3, Dir.S, loftColor)
        f.bed(mu(3), top + 1, d - 3, Dir.S, bedColor)
        f.chest(mu(w - 3), top + 1, d - 2, Dir.N, Loot.HOUSE)
        f.set(w / 2, top + (d + 1) / 2 - 1, d / 2, B(M.LANTERN, top = true))

        // Light from the beams downstairs
        for (v in intArrayOf(2, d - 3)) f.set(w / 2, top - 1, v, B(M.LANTERN, top = true))

        if (tenant) f.resident(Folk.CITIZEN, mu(w - 3), 0, 1, work = f.at(bench, 0, 1))
        if (tenant && child) f.resident(Folk.CHILD, mu(2), 0, hv + 2)
    }

    companion object {
        /** The walls a cottage gets, by the index of [Walling]: plaster and timber half the time, stone and logs the rest. */
        val WALLINGS = intArrayOf(0, 0, 0, 0, 0, 1, 1, 2, 2, 2)
        /** The roofs: mostly thatch, then dark timber, slate, and now and then red tile. */
        val ROOFS = intArrayOf(
            ROOF_THATCH, ROOF_THATCH, ROOF_THATCH, ROOF_THATCH, ROOF_THATCH, ROOF_WOOD, ROOF_WOOD, ROOF_SLATE, ROOF_SLATE, ROOF_RED,
        )
        /** A farmhouse is more often plastered, and roofed in tile. */
        val FARM_WALLINGS = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 2)
        val FARM_ROOFS = intArrayOf(ROOF_RED, ROOF_RED, ROOF_RED, ROOF_SLATE, ROOF_SLATE, ROOF_THATCH, ROOF_THATCH, ROOF_WOOD, ROOF_WOOD, ROOF_THATCH)
    }
}
