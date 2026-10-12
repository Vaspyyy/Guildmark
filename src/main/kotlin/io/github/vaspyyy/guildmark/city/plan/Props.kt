package io.github.vaspyyy.guildmark.city.plan

import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.WALL_IN
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The street furniture and the things that make a city work: lamps, trees, market stalls, the well,
 * the fountain in the plaza, the bell villagers gather at, the quest board, the Wardfire, and the
 * golems keeping the gates.
 */
class Props(private val plan: CityPlan, private val canvas: Canvas) {
    fun draw() {
        for (p in plan.props) {
            if (!canvas.touches(Rect(p.x - 4, p.z - 4, p.x + 4, p.z + 4))) continue
            when (p.kind) {
                PropKind.LAMP -> lamp(p.x, p.z)
                PropKind.TREE -> tree(p.x, 0, p.z, 4, Random(plan.seed * 31 + p.x * 7919L + p.z * 104729L))
                PropKind.STALL -> stall(p)
                PropKind.WELL -> well(p.x, p.z)
                PropKind.BENCH -> bench(p)
                PropKind.PLANTER -> planter(p.x, p.z)
                PropKind.TARGET -> target(p.x, p.z)
                PropKind.HAY_CART -> hayCart(p)
            }
        }
        fountain()
        bell(plan.bell.x, plan.bell.z)
        canvas.board(plan.board.x, 0, plan.board.z, plan.boardFacing)
        wardfire(plan.wardfire.x, plan.wardfire.z)
        guards()
    }

    private fun lamp(x: Int, z: Int) {
        canvas.set(x, 0, z, B(M.WALL_POST))
        canvas.set(x, 1, z, B(M.FENCE))
        canvas.set(x, 2, z, B(M.FENCE))
        canvas.set(x, 3, z, B(M.LANTERN))
    }

    /** A broad-leaved tree standing on [y]: a trunk [height] and a bit, a rounded crown, leaves that never fall. */
    private fun tree(x: Int, y: Int, z: Int, height: Int, r: Random) {
        val trunk = height + r.nextInt(3)
        for (k in 0 until trunk) canvas.set(x, y + k, z, B(M.LOG))
        val top = y + trunk
        for (dx in -3..3) for (dz in -3..3) for (dy in -2..2) {
            val reach = (dx * dx + dz * dz) / 7.3 + (dy * dy) / 4.8
            if (reach > 1.0 || (dx == 0 && dz == 0 && dy < 0)) continue
            // Ragged at the edges
            if (reach > 0.7 && r.nextInt(4) == 0) continue
            canvas.set(x + dx, top + dy, z + dz, B(M.LEAVES))
        }
        canvas.set(x, top, z, B(M.LOG))
    }

    /** A raised bed of dressed stone at a corner of the plaza, with a young tree in it. */
    private fun planter(x: Int, z: Int) {
        for (dx in -1..1) for (dz in -1..1) {
            canvas.set(x + dx, 0, z + dz, if (dx == 0 && dz == 0) B(M.GRASS) else B(M.FINE))
        }
        for (k in 1..3) canvas.set(x, k, z, B(M.LOG))
        for (dx in -2..2) for (dz in -2..2) for (dy in 0..2) {
            val reach = (dx * dx + dz * dz) / 5.0 + ((dy - 1) * (dy - 1)) / 2.2
            if (reach > 1.0 || (dx == 0 && dz == 0 && dy == 0)) continue
            canvas.set(x + dx, 3 + dy, z + dz, B(M.LEAVES))
        }
        for ((dx, dz) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) canvas.set(x + dx, 1, z + dz, B(M.FLOWER_POT, v = abs(dx + 2 * dz)))
    }

    /**
     * A market stall facing the plaza: a counter with a trade's tools in the middle and goods either
     * side, posts holding up a striped awning, and the trader standing behind.
     */
    private fun stall(p: Prop) {
        val front = p.facing
        val along = front.cw
        fun at(a: Int, b: Int, y: Int, block: B) =
            canvas.set(p.x + along.dx * a - front.dx * b, y, p.z + along.dz * a - front.dz * b, block)

        val kind = Math.floorMod(p.v, STALL_TRADES.size)
        val stripe = STALL_COLORS[kind]
        for (a in listOf(-2, 2)) for (b in 0..1) for (y in 0..2) at(a, b, y, B(M.FENCE))
        for (a in -1..1) at(a, 0, 0, if (a == 0) B(STALL_TRADES[kind], front) else B(M.PLANK_SLAB, top = true))
        val (left, right) = STALL_GOODS[kind]
        at(-1, 0, 1, left)
        at(1, 0, 1, right)
        at(-1, 1, 0, B(M.BARREL, front))
        for (a in -2..2) for (b in -1..1) at(a, b, 3, B(M.WOOL, color = if (Math.floorMod(a, 2) == 0) stripe else Dye.WHITE))
        // Half the stalls are kept; villagers looking for work take on the others
        if (kind % 2 == 0) canvas.spawn(Spawn.VILLAGER, p.x - front.dx, 0, p.z - front.dz, front)
    }

    /** A well: a stone curb round deep water, posts at the corners and a little roof of slabs. */
    private fun well(x: Int, z: Int) {
        for (dx in -1..1) for (dz in -1..1) {
            if (dx == 0 && dz == 0) {
                for (y in -4..-1) canvas.set(x, y, z, B(M.WATER))
                canvas.set(x, -5, z, B(M.STONE))
                canvas.set(x, 2, z, B(M.CHAIN))
            } else {
                canvas.set(x + dx, 0, z + dz, B(M.STONE))
                if (dx != 0 && dz != 0) for (y in 1..2) canvas.set(x + dx, y, z + dz, B(M.FENCE))
            }
            canvas.set(x + dx, 3, z + dz, B(M.STONE_SLAB))
        }
    }

    /** A bench of three seats looking toward [Prop.facing]. */
    private fun bench(p: Prop) {
        val along = p.facing.cw
        for (a in -1..1) canvas.set(p.x + along.dx * a, 0, p.z + along.dz * a, B(M.PLANK_STAIRS, p.facing.opposite))
    }

    /** An archery butt: a target on a straw bale. */
    private fun target(x: Int, z: Int) {
        canvas.set(x, 0, z, B(M.HAY))
        canvas.set(x, 1, z, B(M.TARGET))
    }

    /** A cart heaped with hay, its shafts down on the cobbles toward [Prop.facing]. */
    private fun hayCart(p: Prop) {
        val ahead = p.facing
        val side = ahead.cw
        for (b in 0..1) {
            val x = p.x - ahead.dx * b
            val z = p.z - ahead.dz * b
            canvas.set(x, 0, z, B(M.PLANK_SLAB, top = true))
            canvas.set(x, 1, z, B(M.HAY))
        }
        val wx = p.x - ahead.dx
        val wz = p.z - ahead.dz
        canvas.set(wx, 2, wz, B(M.HAY))
        canvas.set(wx + side.dx, 0, wz + side.dz, B(M.TRAPDOOR, side, open = true))
        canvas.set(wx - side.dx, 0, wz - side.dz, B(M.TRAPDOOR, side.opposite, open = true))
        canvas.set(p.x + ahead.dx, 0, p.z + ahead.dz, B(M.FENCE))
    }

    /**
     * The fountain in the middle of the plaza: a round basin with a stone rim, a column with a bowl on
     * top, and water spilling over the bowl's lip into the basin. Lanterns stand on the rim.
     */
    private fun fountain() {
        if (!canvas.touches(Rect(-FOUNTAIN - 1, -FOUNTAIN - 1, FOUNTAIN + 1, FOUNTAIN + 1))) return
        for (x in -FOUNTAIN..FOUNTAIN) for (z in -FOUNTAIN..FOUNTAIN) {
            val dist = sqrt((x * x + z * z).toDouble())
            when {
                dist > FOUNTAIN + 0.5 -> {}
                dist > FOUNTAIN - 0.5 -> canvas.set(x, 0, z, B(M.FINE))
                else -> {
                    canvas.set(x, 0, z, B(M.WATER))
                    canvas.set(x, -1, z, B(M.FINE))
                }
            }
        }
        for (y in 0..3) canvas.set(0, y, 0, B(M.FINE_PILLAR))
        for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) canvas.set(dx, 3, dz, B(M.FINE_SLAB))
        canvas.set(0, 4, 0, B(M.WATER))
        for (dir in Dir.entries) canvas.set(dir.dx * FOUNTAIN, 1, dir.dz * FOUNTAIN, B(M.LANTERN))
    }

    /** The city's bell in a little timber frame: the meeting place villagers gather at. */
    private fun bell(x: Int, z: Int) {
        if (!canvas.touches(Rect(x - 2, z - 2, x + 2, z + 2))) return
        for (dx in listOf(-1, 1)) for (y in 0..2) canvas.set(x + dx, y, z, B(M.TIMBER))
        for (dx in -1..1) canvas.set(x + dx, 3, z, B(M.TIMBER, axis = Axis.X))
        canvas.set(x - 1, 4, z, B(M.ROOF_STAIRS, Dir.E, v = 0))
        canvas.set(x, 4, z, B(M.ROOF_SLAB, v = 0))
        canvas.set(x + 1, 4, z, B(M.ROOF_STAIRS, Dir.W, v = 0))
        canvas.set(x, 2, z, B(M.BELL, Dir.S, top = true))
    }

    /** The city's Wardfire on a low dais of stone. */
    private fun wardfire(x: Int, z: Int) {
        if (!canvas.touches(Rect(x - 2, z - 2, x + 2, z + 2))) return
        for (dx in -1..1) for (dz in -1..1) {
            canvas.set(x + dx, 0, z + dz, if (dx == 0 && dz == 0) B(M.WARDFIRE) else B(M.FINE_SLAB))
        }
    }

    /** Iron golems keeping each gate, and two more in the plaza. */
    private fun guards() {
        for (side in plan.gates) {
            val p = plan.sidePos(side, 4, WALL_IN - 3)
            canvas.spawn(Spawn.GOLEM, p.x, 0, p.z, side)
        }
        canvas.spawn(Spawn.GOLEM, -8, 0, -6, Dir.S)
        canvas.spawn(Spawn.GOLEM, 8, 0, 6, Dir.N)
    }

    companion object {
        /** The fountain's rim, out from the middle of the plaza. */
        const val FOUNTAIN = 5
        /** What each kind of stall sells: its trade's tools stand in the middle of the counter. */
        val STALL_TRADES = listOf(M.COMPOSTER, M.BARREL, M.SMOKER, M.LOOM, M.FLETCHING_TABLE, M.CAULDRON)
        val STALL_COLORS = listOf(Dye.GREEN, Dye.LIGHT_BLUE, Dye.RED, Dye.PURPLE, Dye.YELLOW, Dye.BROWN)
        val STALL_GOODS = listOf(
            B(M.HAY) to B(M.FLOWER_POT, v = 1),
            B(M.CAULDRON, v = 2) to B(M.LANTERN),
            B(M.HAY) to B(M.CANDLE),
            B(M.WOOL, color = Dye.RED) to B(M.WOOL, color = Dye.YELLOW),
            B(M.HAY) to B(M.BARREL, Dir.N),
            B(M.WOOL, color = Dye.BROWN) to B(M.CANDLE),
        )
    }
}
