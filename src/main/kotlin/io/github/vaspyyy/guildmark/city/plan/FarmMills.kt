package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * The watermill on its bank beside a bridge, drawn facing north with its door on the road: a long house of stone
 * below and timber above, its wheel (a prop) turning in the stream past one end. Downstairs the millstone by the
 * wheel's axle, sacks of flour, and a baker at the oven; up the ladder, the loft where the grain is kept.
 */
internal class FarmMill(f: Frame, b: FarmBuilding, r: Random) : FarmPiece(f, b, r) {
    override fun draw() {
        val top = 2 * STORY - 1
        val floor = STORY - 1
        val door = w / 2
        // The wheel is at the right end (as the building faces) or the left; everything's drawn wheel-right and mirrored
        val wheelRight = f.turn(Dir.E) == Dir.entries[b.v]
        fun mu(u: Int) = if (wheelRight) u else w - 1 - u
        fun mdir(dir: Dir) = if (!wheelRight && dir.alongX) dir.opposite else dir
        val palette = ROOFS[r.nextInt(ROOFS.size)]
        val infill = if (r.nextInt(3) == 0) STONE else PLASTER
        val boards = B(M.PLANKS, v = r.nextInt(2))
        val baker = r.nextInt(100) < 75
        val hv = d / 2

        // The floors, the stone ground floor, the timbered storey over it, the roof
        f.fill(1, -1, 1, w - 2, -1, d - 2, B(M.PLANKS, v = 1))
        f.fill(1, floor, 1, w - 2, floor, d - 2, boards)
        walls(0, floor - 1) { u, v, y ->
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            when {
                corner -> TIMBER
                y == 0 -> FOOTING
                else -> STONE
            }
        }
        walls(floor, top) { u, v, y ->
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            val axis = if (v == 0 || v == d - 1) Axis.X else Axis.Z
            val post = corner || ((v == 0 || v == d - 1) && u % 4 == 0)
            when {
                corner -> TIMBER
                y == floor || y == top -> B(M.TIMBER, axis = axis)
                post -> TIMBER
                else -> infill
            }
        }
        Buildings.roof(f, w, d, 0, top, palette, false, infill)

        // The door on the road with a torch, windows each side; above it the loft door under its hoist arm, a window at each end
        f.door(door, 0, 0)
        f.set(door, 2, 0, TIMBER)
        f.set(door + 1, 1, -1, B(M.TORCH, Dir.N))
        for (u in intArrayOf(door - 2, door + 2)) window(u, 1, 0, Dir.N)
        for (y in STORY..STORY + 1) {
            f.set(door, y, 0, AIR)
            for (side in intArrayOf(-1, 1)) f.set(door + side, y, -1, B(M.SHUTTER, Dir.N, open = true))
        }
        for (u in intArrayOf(1, w - 2)) window(u, STORY + 1, 0, Dir.N)
        f.set(door, top - 1, -1, B(M.FENCE))
        f.set(door, top - 1, -2, B(M.FENCE))
        f.set(door, top - 2, -2, B(M.CHAIN))
        f.set(door, top - 3, -2, B(M.CHAIN))
        for (u in intArrayOf(2, w - 3)) window(u, 1, d - 1, Dir.S)
        for (v in intArrayOf(1, d - 2)) window(mu(0), STORY + 1, v, mdir(Dir.W), height = 2)
        window(mu(w - 1), STORY + 1, hv, mdir(Dir.E), height = 2, shutters = false)

        // The stove at the far end: its chimney up the gable, the oven, a counter to work at
        f.set(mu(1), 0, hv, B(M.SMOKER, mdir(Dir.E)))
        f.set(mu(1), 0, hv + 2, B(M.PLANKS))
        f.set(mu(1), 0, hv - 2, B(M.BARREL, Dir.S))
        chimney(mu(0), hv, 0, top + d / 2 + 3)
        if (baker) f.resident(Folk.BAKER, mu(2), 0, hv, work = f.at(mu(1), 0, hv))

        // The millstone by the wheel's axle, flour sacks and barrels along the wall, a table and a chest
        val wu = mu(w - 2)
        f.set(wu, 0, hv, B(M.LOG, axis = Axis.X))
        f.set(mu(w - 3), 0, hv, B(M.GRINDSTONE, mdir(Dir.W)))
        f.set(mu(w - 3), 1, hv, B(M.BARREL))
        for (v in intArrayOf(1, d - 2)) {
            f.set(wu, 0, v, B(M.WOOL, color = Dye.WHITE))
            f.set(wu, 1, v, B(M.WOOL, color = Dye.WHITE))
        }
        f.set(wu, 0, hv - 1, B(M.BARREL, mdir(Dir.W)))
        f.set(wu, 0, hv + 1, B(M.BARREL, mdir(Dir.W)))
        f.set(mu(w - 3), 0, 1, B(M.HAY))
        val tu = mu(4)
        f.set(tu, 0, hv - 1, TABLE)
        f.set(tu, 1, hv - 1, B(M.CANDLE))
        f.set(tu, 0, hv - 2, chair(Dir.S))
        f.set(mu(5), 0, hv - 1, chair(mdir(Dir.W)))
        f.chest(mu(w - 3), 0, d - 2, Dir.N, Loot.MARKET)
        f.spawn(Spawn.CAT, mu(3), 0, d - 2)
        // The ladder up to the loft, lanterns from the ceiling
        for (y in 0..STORY) f.set(mu(4), y, d - 2, B(M.LADDER, Dir.N))
        for (u in intArrayOf(2, w - 3)) f.set(mu(u), floor - 1, hv, B(M.LANTERN, top = true))
        f.set(mu(4), floor - 1, 1, B(M.LANTERN, top = true))

        // The loft: sacks and hay, a chest, lanterns from the tie beams
        for (v in intArrayOf(2, d - 3)) f.fill(1, top, v, w - 2, top, v, B(M.TIMBER, axis = Axis.X))
        f.set(mu(3), top - 1, 2, B(M.LANTERN, top = true))
        f.set(mu(w - 4), top - 1, d - 3, B(M.LANTERN, top = true))
        for (v in 1..d - 2 step 2) {
            f.set(wu, STORY, v, B(M.WOOL, color = Dye.WHITE))
            f.set(mu(1), STORY, v, B(M.HAY))
        }
        f.set(mu(2), STORY, 1, B(M.HAY))
        f.chest(mu(5), STORY, d - 2, Dir.N, Loot.MARKET)
    }

    companion object {
        /** A mill's roofs: slate, dark timber, tile, thatch. */
        val ROOFS = intArrayOf(ROOF_SLATE, ROOF_SLATE, ROOF_WOOD, ROOF_WOOD, ROOF_RED, ROOF_THATCH)
    }
}

/**
 * The windmill on its rise, drawn facing the road: a round stone tower, a narrower timbered tower on it, and a
 * pointed cap whose axle carries four sails on a hub the game draws turning while the mill grinds, a little out in
 * front of the yard's cart and the lamps' posts. Where the canvas builds this mill of blocks ([Canvas.blockMill]) the
 * sails are built block by block instead, turned a little from the upright so they look to be turning. Inside a mill
 * room with the millstone, sacks and a ladder up through the floors to the cap.
 */
internal class FarmWindmill(f: Frame, b: FarmBuilding, r: Random) : FarmPiece(f, b, r) {
    private val cu = w / 2
    private val cv = d / 2

    /** How far, squared, a column is from the tower's middle. */
    private fun far(u: Int, v: Int) = (u - cu) * (u - cu) + (v - cv) * (v - cv)

    /** Sets [block] at every column of the tower at [y] whose squared distance from its middle is in [near]. */
    private fun round(near: IntRange, y: Int, block: (u: Int, v: Int) -> B?) {
        for (u in 0 until w) for (v in 0 until d) if (far(u, v) in near) block(u, v)?.let { f.set(u, y, v, it) }
    }

    override fun draw() {
        val palette = ROOFS[r.nextInt(ROOFS.size)]
        val upper = if (r.nextInt(3) == 0) STONE else PLASTER
        val cap = B(M.PLANKS, v = 1)
        // Turned a little the other way from the cart in the yard, so that the lowest sail hangs clear of it
        val degrees = -(12.0 + r.nextInt(24))
        val cloth = CLOTHS[r.nextInt(CLOTHS.size)]
        val front = cv - 3
        val axle = TOP + 3

        // The mill room's floor and ceiling, the tower of stone below and plaster above between timber bands, the cap of boards, its cone
        round(0..5, -1) { _, _ -> B(M.PLANKS, v = 1) }
        round(0..5, FLOOR) { _, _ -> B(M.PLANKS) }
        round(0..5, TOP) { _, _ -> B(M.PLANKS) }
        for (y in 0 until FLOOR) round(8..10, y) { _, _ -> if (y <= 1) FOOTING else STONE }
        round(8..10, FLOOR) { u, v -> beam(u, v) }
        for (y in FLOOR + 1 until TOP) round(8..10, y) { _, _ -> upper }
        round(8..10, TOP) { u, v -> beam(u, v) }
        for (y in TOP + 1..axle) round(4..5, y) { _, _ -> cap }
        cone(palette, axle + 1)

        // The door and the windows, a torch and two lamps in front
        f.door(cu, 0, front)
        f.set(cu, 2, front, TIMBER)
        f.set(cu - 1, 1, front - 1, B(M.TORCH, Dir.N))
        for (du in intArrayOf(-3, 3)) {
            f.set(cu + du, 1, cv, WINDOW)
            f.set(cu + du, 2, cv, WINDOW)
            for (y in FLOOR + 2..FLOOR + 3) f.set(cu + du, y, cv, WINDOW)
        }
        for (y in 1..2) f.set(cu, y, cv + 3, WINDOW)
        for (y in FLOOR + 2..FLOOR + 3) f.set(cu, y, cv + 3, WINDOW)
        for (y in FLOOR + 2..FLOOR + 3) f.set(cu, y, front, WINDOW)
        for (du in intArrayOf(-2, 2)) f.set(cu + du, TOP + 2, cv, WINDOW)
        for (u in intArrayOf(cu - 3, cu + 3)) {
            f.set(u, 0, 0, B(M.FENCE))
            f.set(u, 1, 0, B(M.FENCE))
            f.set(u, 2, 0, B(M.LANTERN))
        }

        // The mill room: the millstone in the middle, sacks, barrels and a chest round it; a pier at the back for the ladder
        f.set(cu, 0, cv, B(M.GRINDSTONE, Dir.N))
        f.set(cu, 1, cv, B(M.BARREL))
        for (y in 0 until TOP) f.set(cu, y, cv + 2, if (y < FLOOR) STONE else B(M.PLANKS))
        for (y in 0..TOP + 1) f.set(cu, y, cv + 1, B(M.LADDER, Dir.N))
        f.set(cu + 2, 0, cv, B(M.WOOL, color = Dye.WHITE))
        f.set(cu + 2, 1, cv, B(M.WOOL, color = Dye.WHITE))
        f.set(cu + 2, 0, cv - 1, B(M.WOOL, color = Dye.WHITE))
        f.set(cu - 2, 0, cv, B(M.BARREL, Dir.E))
        f.set(cu - 2, 0, cv + 1, B(M.BARREL, Dir.E))
        f.set(cu - 2, 1, cv, B(M.HAY))
        f.chest(cu + 1, 0, cv + 1, Dir.N, Loot.MARKET)
        f.set(cu - 1, FLOOR - 1, cv, B(M.LANTERN, top = true))
        f.set(cu + 1, FLOOR - 1, cv - 1, B(M.LANTERN, top = true))
        // The room above, up the ladder: sacks, barrels, a stack of hay, lanterns from the ceiling
        f.set(cu + 2, FLOOR + 1, cv, B(M.WOOL, color = Dye.WHITE))
        f.set(cu + 2, FLOOR + 1, cv + 1, B(M.WOOL, color = Dye.WHITE))
        f.set(cu - 2, FLOOR + 1, cv, B(M.BARREL, Dir.E))
        f.set(cu - 2, FLOOR + 1, cv - 1, B(M.HAY))
        f.set(cu, FLOOR + 1, cv - 2, B(M.BARREL, Dir.S))
        f.set(cu - 1, TOP - 1, cv, B(M.LANTERN, top = true))
        f.set(cu + 1, TOP - 1, cv - 1, B(M.LANTERN, top = true))

        // The cap: the axle through its front wall to the hub, lanterns from its roof over the little room. The turning
        // sails bring their own length of axle out of the cap's wall to the hub
        val turning = !f.canvas.blockMill(f.at(cu, axle, SAIL_V))
        for (v in SAIL_V + 1..cv - 1) f.set(cu, axle, v, if (turning && v < SHAFT_V) AIR else B(M.LOG, axis = Axis.Z))
        f.set(cu - 1, axle, cv, B(M.LANTERN, top = true))
        f.set(cu + 1, axle, cv, B(M.LANTERN, top = true))
        if (turning) f.set(cu, axle, SAIL_V, B(M.SAILS, Dir.N, color = cloth)) else sails(cu, axle, SAIL_V, degrees, cloth)
    }

    /** A timber beam round the tower at this column, running along the wall. */
    private fun beam(u: Int, v: Int): B = B(M.TIMBER, axis = if (kotlin.math.abs(v - cv) >= kotlin.math.abs(u - cu)) Axis.X else Axis.Z)

    /** The pointed roof of the cap from [base] up, ring on ring of stairs round filled roof, closing to a point with a finial. */
    private fun cone(palette: Int, base: Int) {
        val limits = intArrayOf(10, 5, 2, 0)
        for (k in limits.indices) {
            val y = base + k
            val limit = limits[k]
            for (u in 0 until w) for (v in 0 until d) {
                if (far(u, v) > limit) continue
                val edge = far(u + 1, v) > limit || far(u - 1, v) > limit || far(u, v + 1) > limit || far(u, v - 1) > limit
                val du = u - cu
                val dv = v - cv
                when {
                    k == limits.size - 1 -> {
                        f.set(u, y, v, B(M.ROOF, v = palette))
                        f.set(u, y + 1, v, B(M.ROOF_SLAB, v = palette))
                        f.set(u, y + 2, v, B(M.FENCE))
                    }
                    edge -> {
                        val toward = if (kotlin.math.abs(du) > kotlin.math.abs(dv)) (if (du > 0) Dir.W else Dir.E) else (if (dv > 0) Dir.N else Dir.S)
                        f.set(u, y, v, B(M.ROOF_STAIRS, toward, v = palette))
                    }
                    else -> f.set(u, y, v, B(M.ROOF, v = palette))
                }
            }
        }
    }

    /**
     * Four sails round the hub at ([hu], [hy]) in the plane [sv], the first turned [degrees] from the horizontal (up
     * if above zero) and the rest a quarter turn apart: a wooden whip along each and a lattice of [cloth] beside it,
     * rasterised a block at a time.
     */
    private fun sails(hu: Int, hy: Int, sv: Int, degrees: Double, cloth: Dye) {
        val whips = HashSet<Pair<Int, Int>>()
        val slats = HashSet<Pair<Int, Int>>()
        val span = SAIL + 4
        for (arm in 0..3) {
            val a = Math.toRadians(degrees + 90.0 * arm)
            val cx = Math.cos(a)
            val cy = Math.sin(a)
            for (du in -span..span) for (dy in -span..span) {
                // Along the arm and across it, to the side the cloth is on
                val along = du * cx + dy * cy
                val across = -du * cy + dy * cx
                when {
                    along in -0.5..SAIL + 0.5 && Math.abs(across) <= 0.5 -> whips.add(Pair(hu + du, hy + dy))
                    // The frame: a rail along the outer edge of the cloth, and a bar across the tip
                    along in 1.5..SAIL.toDouble() && across > CLOTH_WIDTH - 0.5 && across <= CLOTH_WIDTH -> whips.add(Pair(hu + du, hy + dy))
                    along > SAIL - 0.5 && along <= SAIL + 0.5 && across > 0.5 && across <= CLOTH_WIDTH -> whips.add(Pair(hu + du, hy + dy))
                    along in 1.5..SAIL.toDouble() && across > 0.5 && across <= CLOTH_WIDTH && Math.round(along).toInt() % 2 == 0 ->
                        slats.add(Pair(hu + du, hy + dy))
                }
            }
        }
        for ((u, y) in slats) if (Pair(u, y) !in whips) f.set(u, y, sv, B(M.WOOL, color = cloth))
        for ((u, y) in whips) f.set(u, y, sv, B(M.PLANKS))
        f.set(hu, hy, sv, B(M.LOG, axis = Axis.Z))
    }

    companion object {
        /** The mill room's ceiling, the top of the lower tower (and the floor of the cap), the sails' length and the plane they turn in. */
        const val FLOOR = 3
        const val TOP = 7
        const val SAIL = 8
        const val SAIL_V = 1
        const val CLOTH_WIDTH = 3.5

        /** The row of the cap's front wall, where the axle goes in: turning sails bring the axle from there to the hub. */
        const val SHAFT_V = 4

        /** Where [b]'s sails turn: their hub, in the city's frame. */
        fun hub(b: FarmBuilding): At {
            val f = Frame(NullCanvas, b.lot, b.front, b.y)
            return f.at(f.w / 2, TOP + 3, SAIL_V)
        }

        /** A windmill's cone: dark timber, slate, thatch, tile. */
        val ROOFS = intArrayOf(ROOF_WOOD, ROOF_SLATE, ROOF_SLATE, ROOF_THATCH, ROOF_RED)
        /** The sailcloth: bleached, grey or dark, now and then red. */
        val CLOTHS = listOf(Dye.WHITE, Dye.WHITE, Dye.WHITE, Dye.LIGHT_GRAY, Dye.LIGHT_GRAY, Dye.GRAY, Dye.RED)
    }
}
