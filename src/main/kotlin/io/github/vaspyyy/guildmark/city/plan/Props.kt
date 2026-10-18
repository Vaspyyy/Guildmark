package io.github.vaspyyy.guildmark.city.plan

import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.INNER_GATES
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.WALL_THICK
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A lantern's [B.v] for a street lamp: a lantern standing on a lamp post, which the game builds as a street lamp
 * (lamps/StreetLampBlock) that is lit at dusk and put out at dawn, while Street Lamps is switched on.
 */
const val STREET_LAMP = 1

/**
 * The street furniture and the things that make a city work: lamps, trees, market stalls, the well,
 * the fountain in the plaza, the bell villagers gather at, the quest board, the Wardfire, and the
 * golems keeping the gates.
 */
class Props(private val plan: CityPlan, private val world: Canvas) {
    /** The canvas raised to the ground the prop being drawn stands on. */
    private var canvas: Canvas = world

    fun draw() {
        for (p in plan.props) {
            if (!world.touches(Rect(p.x - 4, p.z - 4, p.x + 4, p.z + 4))) continue
            canvas = Raised(world, plan.level(p.x, p.z))
            when (p.kind) {
                PropKind.LAMP -> lamp(p.x, p.z)
                PropKind.TREE -> tree(p.x, 0, p.z, 4, Random(plan.seed * 31 + p.x * 7919L + p.z * 104729L))
                PropKind.STALL -> stall(p)
                PropKind.WELL -> well(p.x, p.z)
                PropKind.BENCH -> bench(p)
                PropKind.PLANTER -> planter(p.x, p.z)
                PropKind.TARGET -> target(p.x, p.z)
                PropKind.HAY_CART -> hayCart(p)
                PropKind.RING -> ring(p)
            }
        }
        canvas = Raised(world, plan.upper)
        fountain()
        canvas = Raised(world, plan.level(plan.bell.x, plan.bell.z))
        bell(plan.bell.x, plan.bell.z)
        world.board(plan.board.x, plan.level(plan.board.x, plan.board.z), plan.board.z, plan.boardFacing)
        world.marketBoard(plan.marketBoard.x, plan.level(plan.marketBoard.x, plan.marketBoard.z), plan.marketBoard.z, plan.marketBoardFacing)
        canvas = Raised(world, plan.level(plan.wardfire.x, plan.wardfire.z))
        wardfire(plan.wardfire.x, plan.wardfire.z)
        canvas = world
        guards()
        watch()
        // The city's bard plays in the plaza by day, south of the fountain, and lodges at the tavern
        val bard = At(plan.fountain.x, plan.upper, plan.fountain.z + plan.fountainRadius + 3)
        world.resident(Folk.BARD, bard, post = bard, facing = Dir.N, home = plan.inside(Kind.TAVERN, 2, Buildings.STORY, 13))
    }

    private fun lamp(x: Int, z: Int) {
        canvas.set(x, 0, z, B(M.WALL_POST))
        canvas.set(x, 1, z, B(M.FENCE))
        canvas.set(x, 2, z, B(M.FENCE))
        canvas.set(x, 3, z, B(M.LANTERN, v = STREET_LAMP))
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
        // The goods on the counter: displays of what the stall sells where there are any, the old props otherwise
        val (left, right) = STALL_GOODS[kind]
        val (onLeft, onTrade, onRight) = STALL_WARES[kind]
        for ((a, was, wares) in listOf(Triple(-1, left, onLeft), Triple(0, null, onTrade), Triple(1, right, onRight))) {
            if (wares != null) canvas.wares(p.x + along.dx * a, 1, p.z + along.dz * a, wares, front, was)
            else if (was != null) at(a, 0, 1, was)
        }
        at(-1, 1, 0, B(M.BARREL, front))
        for (a in -2..2) for (b in -1..1) at(a, b, 3, B(M.WOOL, color = if (Math.floorMod(a, 2) == 0) stripe else Dye.WHITE))
        // Townsfolk keep every stall, so every stall trades with the market; in capitals generated before they did,
        // villagers keep half of them
        val behind = At(p.x - front.dx, 0, p.z - front.dz)
        if (kind % 2 == 0 && plan.layout.version < 1) canvas.spawn(Spawn.VILLAGER, behind.x, 0, behind.z, front)
        else canvas.resident(Folk.MERCHANT, behind, post = behind, facing = front)
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
        val f = plan.fountain
        val radius = plan.fountainRadius
        if (!canvas.touches(Rect(f.x - radius - 1, f.z - radius - 1, f.x + radius + 1, f.z + radius + 1))) return
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val dist = sqrt((dx * dx + dz * dz).toDouble())
            val x = f.x + dx
            val z = f.z + dz
            when {
                dist > radius + 0.5 -> {}
                dist > radius - 0.5 -> canvas.set(x, 0, z, B(M.FINE))
                else -> {
                    canvas.set(x, 0, z, B(M.WATER))
                    canvas.set(x, -1, z, B(M.FINE))
                }
            }
        }
        for (y in 0..3) canvas.set(f.x, y, f.z, B(M.FINE_PILLAR))
        for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) canvas.set(f.x + dx, 3, f.z + dz, B(M.FINE_SLAB))
        canvas.set(f.x, 4, f.z, B(M.WATER))
        for (dir in Dir.entries) canvas.set(f.x + dir.dx * radius, 1, f.z + dir.dz * radius, B(M.LANTERN))
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

    /**
     * A sparring ring: a square of fence posts with a gap to step in by, toward [Prop.facing], and straw
     * dummies at two of its corners.
     */
    private fun ring(p: Prop) {
        for (dx in -3..3) for (dz in -3..3) {
            if (maxOf(abs(dx), abs(dz)) != 3) continue
            val gap = p.facing.dx * dx + p.facing.dz * dz == 3 && abs(dx * p.facing.dz + dz * p.facing.dx) <= 1
            if (!gap) canvas.set(p.x + dx, 0, p.z + dz, B(M.FENCE))
        }
        for ((dx, dz) in listOf(-2 to -2, 2 to 2)) {
            canvas.set(p.x + dx, 0, p.z + dz, B(M.HAY))
            canvas.set(p.x + dx, 1, p.z + dz, B(M.FENCE))
        }
    }

    /** Iron golems keeping every gate, two in the plaza and two at the citadel's gate. */
    private fun guards() {
        for (side in plan.outerGates) {
            val p = plan.sidePos(side, 4, plan.outer - WALL_THICK - 2)
            world.spawn(Spawn.GOLEM, p.x, 0, p.z, side)
        }
        for (side in INNER_GATES) {
            val p = plan.sidePos(side, 5, plan.inner + 3)
            world.spawn(Spawn.GOLEM, p.x, 0, p.z, side)
        }
        val f = plan.fountain
        world.spawn(Spawn.GOLEM, f.x - plan.fountainRadius - 2, plan.upper, f.z - 2, Dir.S)
        world.spawn(Spawn.GOLEM, f.x + plan.fountainRadius + 2, plan.upper, f.z + 2, Dir.N)
        for (sx in intArrayOf(-1, 1)) world.spawn(Spawn.GOLEM, sx * 2, plan.high, plan.citadel.z1 + 2, Dir.S)
    }

    /**
     * The city watch, townsfolk guards at their posts: a pair just inside each outer gate looking out down the
     * road, a pair before each of the old town's gates, two on the landing before the citadel's gate, and one up
     * on each side of the outer walls beside its gatehouse. A third or so keep the night watch; the others stand
     * down at their posts after the evening.
     */
    private fun watch() {
        for (side in plan.outerGates) for (along in intArrayOf(-3, 3)) post(plan.sidePos(side, along, plan.outer - WALL_THICK - 1), 0, side)
        for (side in INNER_GATES) for (along in intArrayOf(-4, 4)) post(plan.sidePos(side, along, plan.inner + 2), 0, side)
        for (x in intArrayOf(-2, 2)) post(CityPlan.Pos(x, plan.citadel.z1 + 2), plan.high, Dir.S)
        for (side in Dir.entries) post(plan.sidePos(side, CityPlan.GATE_HALF + 3, plan.outer - WALL_THICK + 2), plan.walk, side)
    }

    private fun post(p: CityPlan.Pos, y: Int, facing: Dir) {
        val at = At(p.x, y, p.z)
        world.resident(Folk.GUARD, at, post = at, facing = facing)
    }

    companion object {
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

        /**
         * The displays of goods on each kind of stall's counter, in place of its [STALL_GOODS]: on the left, on top
         * of the trade's block in the middle, and on the right (null: the old prop stays, or nothing).
         */
        val STALL_WARES = listOf(
            Triple(Wares.GRAIN, null, Wares.ROOTS),
            Triple(Wares.FISH, Wares.APPLES, null),
            Triple(Wares.BEEF_PORK, Wares.MUTTON_CHICKEN, null),
            Triple(Wares.WOOL, null, Wares.HIDES),
            Triple(Wares.FLETCHING, null, null),
            Triple(Wares.CHANDLERY, null, null),
        )
    }
}
