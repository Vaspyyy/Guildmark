package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * The buildings that keep the farm's beasts and crops, each drawn facing north in its own frame: the great
 * barn, the inn's stable, the henhouse and the open shelter out in a pasture.
 */
internal class FarmSteadings(f: Frame, b: FarmBuilding, r: Random) : FarmPiece(f, b, r) {
    override fun draw() {
        when (b.kind) {
            FarmKind.BARN -> barn()
            FarmKind.STABLE -> stable()
            FarmKind.COOP -> coop()
            else -> shelter()
        }
    }

    /**
     * The great barn: tall walls of boards between dark timbers on a stone footing, a wide opening in the front
     * with gates across it, and a long roof. Inside, a threshing floor down the middle between a bay of hay on
     * one hand and stalls with cows on the other, hayloft above the bays and a ladder up to it.
     */
    private fun barn() {
        val top = 6
        val palette = BARN_ROOFS[r.nextInt(BARN_ROOFS.size)]
        val boards = B(M.PLANKS, v = r.nextInt(2))
        val hayNoise = r.nextInt(16)
        val stallsLeft = r.nextBoolean()
        val bay = 4
        val a0 = 1 + bay
        val a1 = w - 2 - bay
        fun mu(u: Int) = if (stallsLeft) w - 1 - u else u
        fun mdir(dir: Dir) = if (stallsLeft && dir.alongX) dir.opposite else dir

        // The floor, the walls, the loft's floors over the bays and the roof
        f.fill(1, -1, 1, w - 2, -1, d - 2, B(M.PLANKS, v = 1))
        walls(0, top) { u, v, y ->
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            val post = corner || (v == 0 && (u == a0 - 1 || u == a1 + 1)) || (if (v == 0 || v == d - 1) u % 4 == 0 else v % 4 == 0)
            when {
                y <= 1 -> FOOTING
                y == top -> if (corner) TIMBER else B(M.TIMBER, axis = if (v == 0 || v == d - 1) Axis.X else Axis.Z)
                post -> TIMBER
                else -> boards
            }
        }
        f.fill(1, 4, 1, a0 - 1, 4, d - 2, B(M.PLANKS))
        f.fill(a1 + 1, 4, 1, w - 2, 4, d - 2, B(M.PLANKS))
        Buildings.roof(f, w, d, 0, top, palette, false, boards)

        // The wide opening, a timber lintel, gates across it, and a hayloft door above
        for (u in a0..a1) {
            for (y in 0..3) f.set(u, y, 0, AIR)
            f.set(u, 4, 0, B(M.TIMBER, axis = Axis.X))
            f.set(u, 0, 0, B(M.FENCE_GATE, Dir.N))
            if (u in a0 + 1..a1 - 1) f.set(u, 5, 0, AIR)
        }
        f.set(w / 2, 6, -1, B(M.LANTERN, top = true))
        f.set(w / 2, 7, -1, B(M.FENCE))
        // Torches on the other walls, to light the ground under the eaves
        f.set(w / 2, 2, d, B(M.TORCH, Dir.S))
        f.set(-1, 2, d / 2, B(M.TORCH, Dir.W))
        f.set(w, 2, d / 2, B(M.TORCH, Dir.E))

        // Down the middle: tie beams over the threshing floor, lanterns hung from them, a chest and barrels at the far end
        for (v in intArrayOf(2, d - 3)) {
            f.fill(1, top, v, w - 2, top, v, B(M.TIMBER, axis = Axis.X))
            f.set(w / 2, top - 1, v, B(M.LANTERN, top = true))
        }
        f.chest(a1, 0, d - 2, Dir.N, Loot.HOUSE)
        for (u in a0 + 1..a1 - 1) f.set(u, 0, d - 2, B(M.BARREL, Dir.N))
        for (y in 0..5) f.set(a0, y, d - 2, B(M.LADDER, Dir.N))

        // The hay bay: bales piled in lumps, as high as the loft
        for (u in 1..a0 - 1) for (v in 1..d - 2) {
            if (v == d - 2 && u == a0 - 1) continue
            val height = ((u * 7 + v * 13 + hayNoise) % 5).coerceAtMost(3) + if (u == 1 || v == d - 2) 1 else 0
            for (y in 0 until height.coerceAtMost(3)) f.set(mu(u), y, v, B(M.HAY))
        }
        f.set(mu(a0 - 2), 3, d / 2, B(M.LANTERN, top = true))
        // The stalls: a fence between them and a gate to each, a cow in each, hay and water at the wall
        val mid = d / 2
        for (v in 1..d - 2) f.set(mu(a1 + 1), 0, v, if (v == (1 + mid) / 2 || v == (mid + d - 1) / 2) B(M.FENCE_GATE, mdir(Dir.E)) else B(M.FENCE))
        for (u in a1 + 2..w - 2) f.set(mu(u), 0, mid, B(M.FENCE))
        for (range in listOf(1 until mid, mid + 1..d - 2)) {
            val v = (range.first + range.last) / 2
            f.set(mu(w - 2), 0, v, B(M.HAY))
            f.set(mu(w - 2), 0, range.last, B(M.CAULDRON, v = 2))
            f.spawn(Spawn.COW, mu(a1 + 3), 0, v, mdir(Dir.E))
            f.set(mu(a1 + 3), 3, v, B(M.LANTERN, top = true))
        }

        // The hayloft: bales on the floors, a lantern, the loft's hay seen from the gable window
        for (u in 1..a0 - 1) for (v in 1..d - 2 step 2) f.set(mu(u), 5, v, B(M.HAY))
        for (u in a1 + 1..w - 2) for (v in 2..d - 2 step 3) f.set(mu(u), 5, v, B(M.HAY))
        f.set(mu(a0 - 1), 5, 1, B(M.LANTERN))
        f.set(mu(a1 + 1), 5, d - 2, B(M.LANTERN))
    }

    /**
     * The inn's stable, its gable to the road: a tack room at the front with a chest and barrels, then two loose
     * boxes, each behind its own gate, with a horse, a rack of hay and a trough, under a steep roof on lantern-lit beams.
     */
    private fun stable() {
        val top = 3
        val palette = STABLE_ROOFS[r.nextInt(STABLE_ROOFS.size)]
        val boards = B(M.PLANKS, v = r.nextInt(2))
        val mirror = r.nextBoolean()
        val mid = w / 2
        fun mu(u: Int) = if (mirror) w - 1 - u else u
        fun mdir(dir: Dir) = if (mirror && dir.alongX) dir.opposite else dir
        val box = (d - 5) / 2
        val gates = intArrayOf(2, 3 + box)

        // The floor, with straw in the boxes, the walls (posts where the boxes part), the roof
        f.fill(1, -1, 1, w - 2, -1, d - 2, B(M.PLANKS, v = 1))
        f.fill(1, -1, gates[0] + 1, w - 2, -1, d - 2, B(M.HAY))
        walls(0, top) { u, v, y ->
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            val post = corner || ((u == 0 || u == w - 1) && v in gates)
            when {
                y == 0 -> FOOTING
                y == top -> if (corner) TIMBER else B(M.TIMBER, axis = if (v == 0 || v == d - 1) Axis.X else Axis.Z)
                post -> TIMBER
                else -> boards
            }
        }
        Buildings.roof(f, w, d, 0, top, palette, true, boards)

        // The front, open above a low gate with a post and a lantern at each corner; windows in the sides
        for (u in 1..w - 2) for (y in 0..2) f.set(u, y, 0, if (y == 0) B(M.FENCE) else AIR)
        f.set(mid, 0, 0, B(M.FENCE_GATE, Dir.N))
        for (u in intArrayOf(0, w - 1)) {
            f.set(u, 0, -1, B(M.FENCE))
            f.set(u, 1, -1, B(M.FENCE))
            f.set(u, 2, -1, B(M.LANTERN))
        }
        f.set(mu(1), 0, -1, B(M.HAY))
        for (v in intArrayOf((gates[0] + gates[1]) / 2 + 1, d - 3)) {
            f.set(0, 1, v, WINDOW)
            f.set(w - 1, 1, v, WINDOW)
        }
        f.set(mid, 2, d, B(M.TORCH, Dir.S))

        // The tack room: a chest and barrels along the wall, a runner down the aisle
        f.chest(mu(w - 2), 0, 1, mdir(Dir.W), Loot.STABLES)
        f.set(mu(1), 0, 1, B(M.BARREL, Dir.E))
        f.set(mid, 0, 1, B(M.CARPET, color = Dye.BROWN))
        // The boxes: a fence and a gate across each, hay at one wall and water at the other, a horse
        var first = gates[0] + 1
        for (gate in gates) {
            for (u in 1..w - 2) f.set(u, 0, gate, if (u == mid) B(M.FENCE_GATE, Dir.N) else B(M.FENCE))
            f.fill(1, top, gate, w - 2, top, gate, B(M.TIMBER, axis = Axis.X))
            f.set(mid, top - 1, gate, B(M.LANTERN, top = true))
            val last = if (gate == gates[0]) gates[1] - 1 else d - 2
            f.set(mu(1), 0, last, B(M.HAY))
            f.set(mu(w - 2), 0, last, B(M.CAULDRON, v = 2))
            f.spawn(Spawn.HORSE, mid, 0, (first + last) / 2, Dir.N)
            first = gates[1] + 1
        }
        f.fill(1, top, d - 2, w - 2, top, d - 2, B(M.TIMBER, axis = Axis.X))
    }

    /**
     * The henhouse: a small hut on a stone footing, plastered or boarded between its timbers, with a steep little roof,
     * a door to the front and a nest of straw. The cells beside it, where the hens run, are left clear.
     */
    private fun coop() {
        val top = 2
        val palette = COOP_ROOFS[r.nextInt(COOP_ROOFS.size)]
        val plastered = r.nextInt(3) != 0
        val hipped = r.nextBoolean()
        val infill = if (plastered) PLASTER else B(M.PLANKS, v = r.nextInt(2))
        walls(0, top) { u, v, y ->
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            when {
                y == 0 -> FOOTING
                y == top -> if (corner) TIMBER else B(M.TIMBER, axis = if (v == 0 || v == d - 1) Axis.X else Axis.Z)
                corner -> TIMBER
                else -> infill
            }
        }
        if (hipped) hip(top, palette, cap = false) else Buildings.roof(f, w, d, 0, top, palette, true, infill)
        f.set(w / 2, -1, d / 2, B(M.HAY))
        f.set(w / 2, 0, d / 2, B(M.HAY))
        f.door(w / 2, 0, 0)
        f.set(w - 1, 1, d / 2, WINDOW)
        f.set(w / 2, 1, d - 1, WINDOW)
        f.set(w, 1, d / 2 - 1, B(M.SHUTTER, Dir.E, open = true))
        f.set(w, 1, d / 2 + 1, B(M.SHUTTER, Dir.E, open = true))
        f.set(w / 2, top + 1, d / 2, B(M.LANTERN, top = true))
        f.set(w / 2 + 1, 1, -1, B(M.TORCH, Dir.N))
        f.set(w - 1, 1, d, B(M.TORCH, Dir.S))
    }

    /**
     * The shelter in a pasture: a roof on posts with its back and one side boarded against the weather, open to the
     * field on the others, a hayrack along the back wall and a lantern from the beam.
     */
    private fun shelter() {
        val top = 3
        val palette = BARN_ROOFS[r.nextInt(BARN_ROOFS.size)]
        val boards = B(M.PLANKS, v = r.nextInt(2))
        val closed = if (r.nextBoolean()) 0 else w - 1
        val open = w - 1 - closed
        fun mu(u: Int) = if (closed == 0) u else w - 1 - u
        // Posts at the corners and along the open side, the boarded back and side, beams across
        for (u in 0 until w) for (v in 0 until d) {
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            val backWall = v == d - 1
            val sideWall = u == closed && v in 1..d - 2
            val post = corner || (u == open && v == d / 2)
            for (y in 0..top) {
                val block = when {
                    y == top && (v == 0 || v == d - 1) -> if (corner) TIMBER else B(M.TIMBER, axis = Axis.X)
                    y == top && (u == 0 || u == w - 1) -> if (corner) TIMBER else B(M.TIMBER, axis = Axis.Z)
                    post -> TIMBER
                    (backWall || sideWall) && y == 0 -> FOOTING
                    backWall || sideWall -> boards
                    else -> null
                }
                if (block != null) f.set(u, y, v, block)
            }
        }
        Buildings.roof(f, w, d, 0, top, palette, false, boards)
        // The open side keeps a low rail
        for (v in 1..d - 2) if (v != d / 2) f.set(open, 0, v, B(M.FENCE))
        // The hayrack and a trough along the back, a lantern from a beam across the middle
        for (u in 1..w - 2) f.set(u, 0, d - 2, if (u == mu(w - 2)) B(M.CAULDRON, v = 2) else B(M.HAY))
        f.set(mu(1), 1, d - 2, B(M.HAY))
        f.fill(1, top, d / 2, w - 2, top, d / 2, B(M.TIMBER, axis = Axis.X))
        f.set(w / 2, top - 1, d / 2, B(M.LANTERN, top = true))
        f.set(w / 2, 2, d, B(M.TORCH, Dir.S))
        f.set(closed - (if (closed == 0) 1 else -1), 2, d / 2, B(M.TORCH, if (closed == 0) Dir.W else Dir.E))
        f.set(open + (if (open == 0) -1 else 1), 2, -1, B(M.LANTERN, top = true))
    }

    companion object {
        /** A barn's roofs: dark timber most often, then slate, thatch and red tile. */
        val BARN_ROOFS = intArrayOf(ROOF_WOOD, ROOF_WOOD, ROOF_WOOD, ROOF_SLATE, ROOF_SLATE, ROOF_THATCH, ROOF_THATCH, ROOF_RED)
        /** The stable and the henhouse are roofed to match the farm: thatch, dark timber, tile or slate. */
        val STABLE_ROOFS = intArrayOf(ROOF_WOOD, ROOF_WOOD, ROOF_THATCH, ROOF_THATCH, ROOF_SLATE, ROOF_RED)
        val COOP_ROOFS = intArrayOf(ROOF_THATCH, ROOF_THATCH, ROOF_THATCH, ROOF_WOOD, ROOF_RED, ROOF_SLATE)
    }
}
