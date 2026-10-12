package io.github.vaspyyy.guildmark.city.plan

import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.CITADEL_PARAPET
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.CITADEL_THICK
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.CITADEL_TOWER_RADIUS
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.GATE_HALF
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.GATE_OUT
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.INNER_GATES
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.INNER_PARAPET
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.INNER_THICK
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.INNER_TOWER_RADIUS
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.OUTER_CORNER_RADIUS
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.RING_ROAD
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.WALL_THICK
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws a [CityPlan] onto a [Canvas]. The ground, walls and towers are worked out column by column,
 * so each column's blocks come from that column alone; buildings and street furniture are drawn
 * whole wherever they touch the canvas, which throws away whatever falls outside it.
 */
class CityBuilder(private val plan: CityPlan, private val canvas: Canvas) {
    private val grid = plan.grid
    private val clip = canvas.clip
    private val outer = plan.outer
    private val inner = plan.inner
    private val upper = plan.upper
    private val high = plan.high
    private val walk = plan.walk

    /** Standing heights on the inner wall's walk and the citadel's, and the tops of the outer gatehouses. */
    private val innerWalk = upper + INNER_PARAPET
    private val citadelWalk = high + CITADEL_PARAPET
    private val gateTop = walk + 7

    /** Wall-top railing left open where a flight of steps comes up. */
    private val landings = HashSet<Long>()

    init {
        for (s in plan.stairs) {
            val top = s.length
            val lx = s.x + s.up.dx * top
            val lz = s.z + s.up.dz * top
            // The railing is on the wall's inner edge, a step in from the landing
            val (ox, oz) = outward(lx, lz)
            landings.add(key(lx + ox, lz + oz))
        }
    }

    fun draw() {
        val x0 = max(clip.x0, -plan.reach)
        val x1 = min(clip.x1, plan.reach)
        val z0 = max(clip.z0, -plan.reach)
        val z1 = min(clip.z1, plan.reach)
        for (x in x0..x1) for (z in z0..z1) ground(x, z)
        for (x in x0..x1) for (z in z0..z1) fortification(x, z)
        for (s in plan.stairs) wallStair(s)
        val buildings = Buildings(plan, canvas)
        for (b in plan.buildings) {
            if (canvas.touches(b.lot.grow(3))) buildings.draw(b)
        }
        Props(plan, canvas).draw()
        plan.harbour?.draw(canvas)
        plan.outskirts.draw(canvas)
    }

    // ---- The ground ----

    private fun ground(x: Int, z: Int) {
        val y = plan.level(x, z)
        when (grid[x, z]) {
            Use.CLEARING, Use.GLACIS -> canvas.clearing(x, z, y)
            Use.WALL, Use.TOWER, Use.GATE, Use.KEEP_WALL, Use.INNER_WALL -> canvas.ground(x, z, 0, FOUNDATION, FOUNDATION)
            Use.LOT -> canvas.ground(x, z, y, FOUNDATION, FOUNDATION)
            Use.PLAZA -> canvas.ground(x, z, y, plazaPaving(x, z), FILL)
            Use.COURT -> canvas.ground(x, z, y, courtPaving(x, z), FILL)
            Use.AVENUE -> canvas.ground(x, z, y, avenuePaving(x, z), FILL)
            Use.SQUARE -> canvas.ground(x, z, y, squarePaving(x, z), FILL)
            // The great stair stands on the plaza; the steps up from the inner gates are cut down into the old town
            Use.STAIR -> canvas.ground(x, z, if (plan.stair.contains(x, z)) upper else 0, B(M.PAVE_STREET), FILL)
            Use.LANE, Use.POMERIUM -> canvas.ground(x, z, y, LANE, FILL)
            Use.YARD, Use.FREE -> canvas.ground(x, z, y, YARD, FILL)
            Use.GARDEN -> canvas.ground(x, z, y, GRASS, FILL)
            Use.TRAINING -> canvas.ground(x, z, y, SAND, FILL)
            Use.ROAD -> canvas.ground(x, z, y, B(M.PAVE_LANE, v = 1), FILL)
            // Outside the walls, the harbour and the farms draw their own
            else -> {}
        }
        // A sloping plinth along the foot of the outer walls
        if (grid[x, z] == Use.GLACIS && max(abs(x), abs(z)) == outer + 1 && y <= 1) {
            val (ox, oz) = outward(x, z)
            if (grid[x - ox, z - oz] == Use.WALL) canvas.set(x, 0, z, B(M.WALL_STAIRS, dirOf(-ox, -oz)))
        }
    }

    /** Rings of paving round the fountain, a border at the plaza's edge. */
    private fun plazaPaving(x: Int, z: Int): B {
        val f = plan.fountain
        val ring = max(abs(x), if (z < f.z) abs(z - f.z) else abs(z))
        val round = sqrt(((x - f.x) * (x - f.x) + (z - f.z) * (z - f.z)).toDouble())
        return when {
            abs(x) >= plan.plaza - 1 || z >= plan.plaza - 1 || z <= plan.plazaNorth -> B(M.PAVE_PLAZA, v = 1)
            ring > plan.plaza + 4 -> B(M.PAVE_PLAZA, v = 1)
            round.toInt() % 4 == 0 -> B(M.PAVE_PLAZA, v = 1)
            (x + z) and 1 == 0 && round < plan.fountainRadius + 5 -> B(M.PAVE_PLAZA, v = 2)
            else -> B(M.PAVE_PLAZA)
        }
    }

    private fun courtPaving(x: Int, z: Int): B = if ((x + z) and 3 == 0) B(M.PAVE_PLAZA, v = 2) else B(M.PAVE_PLAZA)

    /** The squares: big flags of stone laid in a grid. */
    private fun squarePaving(x: Int, z: Int): B =
        if (Math.floorMod(x, 5) == 0 || Math.floorMod(z, 5) == 0) B(M.PAVE_STREET, v = 1) else B(M.PAVE_PLAZA)

    /** Avenues have a kerb of a darker stone along each side. */
    private fun avenuePaving(x: Int, z: Int): B {
        val across = if (abs(x) <= 5 && abs(z) > 5) abs(x) else abs(z)
        return if (across == 3) B(M.PAVE_STREET, v = 1) else B(M.PAVE_STREET)
    }

    // ---- Walls, towers and gates ----

    private fun fortification(x: Int, z: Int) {
        // The outer gatehouses first: their towers and passages sit across the wall line
        for (side in plan.outerGates) if (outerGate(side, x, z)) return
        when (grid[x, z]) {
            Use.WALL -> outerWall(x, z)
            Use.TOWER -> tower(x, z)
            Use.INNER_WALL -> innerWall(x, z)
            Use.KEEP_WALL -> citadelWall(x, z)
            Use.GATE -> if (plan.citadel.contains(x, z)) citadelGate(x, z) else innerGate(x, z)
            Use.STAIR -> if (plan.stair.contains(x, z)) grandStair(x, z) else cutting(x, z)
            Use.POMERIUM -> tunnelSide(x, z)
            Use.GLACIS -> towerBanner(x, z)
            else -> {}
        }
        citadelGateFront(x, z)
        innerGateFront(x, z)
    }

    /** The city's banner hanging down the outer face of each square tower on the walls. */
    private fun towerBanner(x: Int, z: Int) {
        for (t in plan.towers) {
            val side = t.side ?: continue
            if (x == t.x + side.dx * 4 && z == t.z + side.dz * 4) canvas.set(x, walk + 2, z, B(M.BANNER, side, color = BANNER_COLOR))
        }
    }

    /** The outer wall: solid to the wall walk, a parapet with merlons outside and a railing inside. */
    private fun outerWall(x: Int, z: Int) {
        val ring = max(abs(x), abs(z))
        val depth = ring - (outer - WALL_THICK + 1)
        val along = if (abs(x) > abs(z)) z else x
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until walk) canvas.set(x, y, z, if (y == walk - 1 && (depth == 0 || depth == 3)) TRIM else WALL)
        when (depth) {
            0 -> if (key(x, z) !in landings) {
                canvas.set(x, walk, z, B(M.WALL_POST))
                if (Math.floorMod(along, 8) == 4) canvas.set(x, walk + 1, z, B(M.LANTERN))
            }
            3 -> {
                canvas.set(x, walk, z, WALL)
                if (Math.floorMod(along, 3) != 0) canvas.set(x, walk + 1, z, WALL)
            }
        }
    }

    /** The old town's wall: holding up its terrace, then standing [INNER_PARAPET] over it, with merlons on the lower town's side. */
    private fun innerWall(x: Int, z: Int) {
        val ring = max(abs(x), abs(z))
        val depth = ring - (inner - INNER_THICK + 1)
        val side = Survey.sideOf(x, z)
        val along = if (side.alongX) z else x
        if (side in INNER_GATES && abs(along) <= INNER_GATE_HALF) return innerGateTower(x, z, along, depth)
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until innerWalk) {
            val b = when {
                y == innerWalk - 1 && depth != 1 -> TRIM
                // A band of dressed stone outside at the terrace's height
                y == upper - 1 && depth == 2 -> TRIM
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        when (depth) {
            0 -> if (Math.floorMod(along, 10) == 5 && key(x, z) !in landings) {
                canvas.set(x, innerWalk, z, B(M.WALL_POST))
                canvas.set(x, innerWalk + 1, z, B(M.LANTERN))
            }
            2 -> {
                canvas.set(x, innerWalk, z, WALL)
                if (Math.floorMod(along, 2) == 0) canvas.set(x, innerWalk + 1, z, WALL)
            }
        }
    }

    /** The citadel's wall: up from the lower town or the old town to its walk, [CITADEL_PARAPET] over its courtyard. */
    private fun citadelWall(x: Int, z: Int) {
        val c = plan.citadel
        val depth = min(min(x - c.x0, c.x1 - x), min(z - c.z0, c.z1 - z))
        if (abs(x) <= CITADEL_GATE_HALF && z > c.z1 - CITADEL_THICK) return citadelGate(x, z)
        val along = if (x - c.x0 == depth || c.x1 - x == depth) z else x
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until citadelWalk) {
            val b = when {
                y == citadelWalk - 1 && depth != 1 -> TRIM
                y == high - 1 && depth == 0 -> TRIM
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        when (depth) {
            0 -> {
                canvas.set(x, citadelWalk, z, WALL)
                if (Math.floorMod(along, 2) == 0) canvas.set(x, citadelWalk + 1, z, WALL)
            }
            2 -> if (Math.floorMod(along, 10) == 5 && key(x, z) !in landings) {
                canvas.set(x, citadelWalk, z, B(M.WALL_POST))
                canvas.set(x, citadelWalk + 1, z, B(M.LANTERN))
            }
        }
    }

    private fun tower(x: Int, z: Int) {
        for (t in plan.towers) {
            when (t.kind) {
                TowerKind.CORNER -> if (CityPlan.inRound(x - t.x, z - t.z, OUTER_CORNER_RADIUS)) {
                    // Doorways where the two middle rows of the walls' tops meet it
                    val ax = abs(x)
                    val az = abs(z)
                    val door = (ax - (outer - WALL_THICK + 1) in 1..2 && az <= outer) || (az - (outer - WALL_THICK + 1) in 1..2 && ax <= outer)
                    return roundTower(t, x, z, OUTER_CORNER_RADIUS, walk, walk + 8, 10, door)
                }
                TowerKind.MID -> if (abs(x - t.x) <= 3 && abs(z - t.z) <= 3) return midTower(t, x, z)
                TowerKind.INNER -> if (CityPlan.inRound(x - t.x, z - t.z, INNER_TOWER_RADIUS)) {
                    val ax = abs(x)
                    val az = abs(z)
                    val door = (ax - (inner - INNER_THICK + 1) in 0..1 && az <= inner) || (az - (inner - INNER_THICK + 1) in 0..1 && ax <= inner)
                    return roundTower(t, x, z, INNER_TOWER_RADIUS, innerWalk, innerWalk + 5, 6, door)
                }
                TowerKind.CITADEL -> if (CityPlan.inRound(x - t.x, z - t.z, CITADEL_TOWER_RADIUS)) {
                    val c = plan.citadel
                    val door = min(min(x - c.x0, c.x1 - x), min(z - c.z0, c.z1 - z)) in 1..2
                    return roundTower(t, x, z, CITADEL_TOWER_RADIUS, citadelWalk, citadelWalk + 7, 8, door)
                }
            }
        }
    }

    /**
     * A round tower: solid to the walk it stands on, a guard room above opening onto the walks either side,
     * and a tall slate cone with a pennant on top. [door] says whether this column of the shell is a doorway.
     */
    private fun roundTower(t: Tower, x: Int, z: Int, radius: Double, floor: Int, eaves: Int, cone: Int, door: Boolean) {
        val dx = x - t.x
        val dz = z - t.z
        val r = sqrt((dx * dx + dz * dz).toDouble())
        val shell = r > radius - 2
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until floor) canvas.set(x, y, z, WALL)
        for (y in floor until eaves) {
            val b = when {
                !shell -> AIR
                door && y < floor + 2 -> AIR
                // Arrow slits looking out
                y == floor + 2 && (dx == 0 || dz == 0) && r > radius - 1 && (dx * t.x > 0 || dz * t.z > 0) -> B(M.WINDOW)
                y == eaves - 1 -> TRIM
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        if (dx == 0 && dz == 0) canvas.set(x, eaves - 1, z, B(M.LANTERN, top = true))
        // The roof: a cone overhanging the walls, hollow
        val roof = radius + 1
        for (k in 0..cone) {
            val rk = roof - k * (roof / cone)
            if (r > rk + 0.01) break
            val y = eaves + k
            if (k == 0 || r > rk - 1.3) canvas.set(x, y, z, if (k == 0 && r > radius) B(M.ROOF_SLAB, v = ROOF_SLATE) else B(M.ROOF, v = ROOF_SLATE))
        }
        if (dx == 0 && dz == 0) for (k in 1..3) canvas.set(x, eaves + cone + k, z, B(M.FENCE))
        // A pennant off the pole, streaming outward
        val ox = Integer.signum(t.x)
        val oz = Integer.signum(t.z)
        if (dx == ox && dz == 0 || dx == 0 && dz == oz) canvas.set(x, eaves + cone + 3, z, B(M.WOOL, color = BANNER_COLOR))
    }

    /** A square tower on the outer wall: a guard room over the wall walk and a crenellated top. */
    private fun midTower(t: Tower, x: Int, z: Int) {
        val side = t.side ?: return
        val along = if (side.alongX) z - t.z else x - t.x
        val out = (if (side.alongX) abs(x) else abs(z)) - (outer - WALL_THICK + 1)
        val rim = abs(along) == 3 || out == 0 || out == 6
        val roof = walk + 5
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until walk) canvas.set(x, y, z, WALL)
        for (y in walk..roof) {
            val b = when {
                y == roof -> WALL
                !rim -> if (y == roof - 1 && along == 0 && out == 3) B(M.LANTERN, top = true) else AIR
                // Through the tower along the wall walk
                abs(along) == 3 && out in 1..2 && y < walk + 2 -> AIR
                y == walk + 2 && out == 6 && along == 0 -> B(M.WINDOW)
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        // A ladder up to the roof in a back corner, through a hatch
        if (along == 2 && out == 5) {
            for (y in walk until roof) canvas.set(x, y, z, B(M.LADDER, side.opposite))
            canvas.set(x, roof, z, B(M.TRAPDOOR, side.opposite, top = true))
        }
        if (rim && Math.floorMod(along + out, 2) == 0) canvas.set(x, roof + 1, z, WALL)
    }

    /**
     * An outer gatehouse: a tower either side of an arched way through the wall, a portcullis drawn up into
     * the arch, and a fighting top over it between the towers. Returns whether ([x], [z]) is part of it.
     */
    private fun outerGate(side: Dir, x: Int, z: Int): Boolean {
        val along = if (side.alongX) z else x
        val out = (if (side.alongX) abs(x) else abs(z)) - (outer - WALL_THICK + 1)
        val sameSide = if (side.alongX) x * side.dx > 0 else z * side.dz > 0
        if (!sameSide || abs(along) > GATE_HALF || out !in 0..GATE_DEPTH) return false
        if (out == GATE_DEPTH) {
            // Out in front: torches either side of the arch and the city's banners on the towers
            if (abs(along) == 3) canvas.set(x, max(3, plan.level(x, z) + 2), z, B(M.TORCH, side))
            if (abs(along) == 5) canvas.set(x, walk + 2, z, B(M.BANNER, side, color = BANNER_COLOR))
            return grid[x, z] == Use.GATE
        }
        if (abs(along) <= 2) {
            gatePassage(side, x, z, along, out)
            return true
        }
        val rim = abs(along) == 3 || abs(along) == GATE_HALF || out == 0 || out == GATE_DEPTH - 1
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until walk) canvas.set(x, y, z, if (y == walk - 1) TRIM else WALL)
        for (y in walk until gateTop) {
            val b = when {
                y == gateTop - 1 -> WALL
                !rim -> AIR
                // Doorways from the wall walk and out onto the top of the arch
                abs(along) == GATE_HALF && out in 1..2 && y < walk + 2 -> AIR
                abs(along) == 3 && out in 5..6 && y < walk + 2 -> AIR
                (y == walk + 2 || y == walk + 3) && out == GATE_DEPTH - 1 && abs(along) in 5..6 -> B(M.WINDOW)
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        if (rim && Math.floorMod(along + out, 2) == 0) canvas.set(x, gateTop, z, WALL)
        if (!rim && abs(along) == 5 && out == 5) canvas.set(x, gateTop - 2, z, B(M.LANTERN, top = true))
        // A ladder up through a hatch onto the roof
        if (abs(along) == 4 && out == GATE_DEPTH - 2) {
            for (y in walk until gateTop - 1) canvas.set(x, y, z, B(M.LADDER, side.opposite))
            canvas.set(x, gateTop - 1, z, B(M.TRAPDOOR, side.opposite, top = true))
        }
        return true
    }

    /** The way through: paved, arched, lit, with the portcullis teeth showing at the top of the arch. */
    private fun gatePassage(side: Dir, x: Int, z: Int, along: Int, out: Int) {
        canvas.ground(x, z, 0, B(M.PAVE_STREET), FILL)
        for (y in 0 until ARCH) {
            val b = when {
                y == ARCH - 1 && abs(along) == 2 -> B(M.WALL_STAIRS, plan.sideDir(side, Integer.signum(along)), top = true)
                out == GATE_DEPTH - 3 && y >= ARCH - 3 -> B(M.BARS)
                else -> AIR
            }
            canvas.set(x, y, z, b)
        }
        if (along == 0 && (out == 2 || out == GATE_DEPTH - 5)) canvas.set(x, ARCH - 1, z, B(M.LANTERN, top = true))
        for (y in ARCH until walk) canvas.set(x, y, z, if (y == ARCH || y == walk - 1) TRIM else WALL)
        // The fighting top: a railing on the town side, merlons on the field side
        when (out) {
            0 -> canvas.set(x, walk, z, B(M.WALL_POST))
            GATE_DEPTH - 1 -> {
                canvas.set(x, walk, z, WALL)
                if (Math.floorMod(along, 2) == 0) canvas.set(x, walk + 1, z, WALL)
            }
        }
    }

    /** The tower over an old-town gate, astride the inner wall, rising over its walk with a crenellated top. */
    private fun innerGateTower(x: Int, z: Int, along: Int, depth: Int) {
        val top = innerWalk + 6
        val rim = abs(along) == INNER_GATE_HALF || depth == 0 || depth == 2
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until innerWalk) canvas.set(x, y, z, if (y == upper - 1 && depth == 2) TRIM else WALL)
        for (y in innerWalk until top) {
            val b = when {
                y == top - 1 -> WALL
                !rim -> AIR
                // Through the tower along the walk
                abs(along) == INNER_GATE_HALF && depth in 0..1 && y < innerWalk + 2 -> AIR
                y == innerWalk + 2 && depth == 2 && abs(along) in 3..4 -> B(M.WINDOW)
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        if (rim && Math.floorMod(along + depth, 2) == 0) canvas.set(x, top, z, WALL)
    }

    /** The city's banners on the old-town gate towers, hanging over the lower town's side. */
    private fun innerGateFront(x: Int, z: Int) {
        if (max(abs(x), abs(z)) != inner + 1) return
        val side = Survey.sideOf(x, z)
        val along = if (side.alongX) z else x
        if (side in INNER_GATES && abs(along) == 4) canvas.set(x, innerWalk + 3, z, B(M.BANNER, side, color = BANNER_COLOR))
    }

    /**
     * The way through an old-town gate: an arch through the foot of the inner wall under its tower, then a
     * vaulted passage under the ring road behind it, lit, its walls of dressed stone.
     */
    private fun innerGate(x: Int, z: Int) {
        val side = Survey.sideOf(x, z)
        val along = if (side.alongX) z else x
        val ring = max(abs(x), abs(z))
        val arch = min(INNER_ARCH, upper - 1)
        canvas.ground(x, z, 0, B(M.PAVE_STREET), FILL)
        for (y in 0 until arch) canvas.set(x, y, z, if (y == arch - 1 && abs(along) == 2) B(M.WALL_STAIRS, plan.sideDir(side, Integer.signum(along)), top = true) else AIR)
        if (ring > inner - INNER_THICK) {
            // Under the tower: the arch's head, the tower's room over the walk, the portcullis on the outer face
            val depth = ring - (inner - INNER_THICK + 1)
            for (y in arch until innerWalk) canvas.set(x, y, z, if (y == arch) TRIM else WALL)
            val top = innerWalk + 6
            for (y in innerWalk until top - 1) canvas.set(x, y, z, if (depth == 1) AIR else WALL)
            canvas.set(x, top - 1, z, WALL)
            if ((depth == 0 || depth == 2) && Math.floorMod(along + depth, 2) == 0) canvas.set(x, top, z, WALL)
            if (depth == 2 && arch >= 3) for (y in arch - 2 until arch) canvas.set(x, y, z, B(M.BARS))
            if (depth == 1 && along == 0) canvas.set(x, top - 2, z, B(M.LANTERN, top = true))
        } else {
            // Under the ring road
            for (y in arch until upper - 1) canvas.set(x, y, z, if (y == arch) TRIM else WALL)
            canvas.set(x, upper - 1, z, LANE)
            if (along == 0 && ring == inner - INNER_THICK - 2) canvas.set(x, arch - 1, z, B(M.LANTERN, top = true))
        }
    }

    /** The walls of the passage under the old town's ring road. */
    private fun tunnelSide(x: Int, z: Int) {
        val side = Survey.sideOf(x, z)
        if (side !in INNER_GATES) return
        val along = if (side.alongX) z else x
        val ring = max(abs(x), abs(z))
        if (abs(along) != 3 || ring !in inner - INNER_THICK - RING_ROAD + 1..inner - INNER_THICK) return
        canvas.set(x, -1, z, B(M.WALL_DARK))
        for (y in 0 until upper - 1) canvas.set(x, y, z, WALL)
    }

    /** The flight of steps up from an old-town gate, cut down into the old town between walls of dressed stone. */
    private fun cutting(x: Int, z: Int) {
        val side = Survey.sideOf(x, z)
        val along = if (side.alongX) z else x
        val ring = max(abs(x), abs(z))
        val k = inner - INNER_THICK - RING_ROAD - ring
        if (abs(along) == 3) {
            canvas.set(x, 0, z, B(M.WALL_DARK))
            for (y in 1 until upper) canvas.set(x, y, z, if (y == upper - 1) TRIM else WALL)
            canvas.set(x, upper, z, B(M.WALL_POST))
            if (Math.floorMod(ring, 6) == 0) canvas.set(x, upper + 1, z, B(M.LANTERN))
            return
        }
        for (y in 0 until k) canvas.set(x, y, z, WALL)
        canvas.set(x, k, z, B(M.WALL_STAIRS, side.opposite))
    }

    /** The great stair down from the citadel's gate: a balustraded landing, and a flight down either side to the plaza. */
    private fun grandStair(x: Int, z: Int) {
        val s = plan.stair
        val k = abs(x) - 3
        if (k <= 0) {
            for (y in upper until high - 1) canvas.set(x, y, z, if (z == s.z1 && y == high - 2) TRIM else WALL)
            canvas.set(x, high - 1, z, B(M.PAVE_PLAZA, v = 1))
            if (z == s.z1) canvas.set(x, high, z, if (abs(x) == 3) B(M.FINE_PILLAR) else B(M.WALL_POST))
            if (z == s.z1 && abs(x) == 3) canvas.set(x, high + 1, z, B(M.LANTERN))
            return
        }
        val step = high - k
        for (y in upper until step) canvas.set(x, y, z, WALL)
        canvas.set(x, step, z, B(M.WALL_STAIRS, if (x > 0) Dir.W else Dir.E))
        if (z == s.z1) canvas.set(x, step + 1, z, B(M.WALL_POST))
    }

    /**
     * The citadel's gatehouse, in the middle of its south wall: towers either side of an arch at the
     * courtyard's height, opening onto the stair's landing.
     */
    private fun citadelGate(x: Int, z: Int) {
        val c = plan.citadel
        val depth = c.z1 - z
        val top = citadelWalk + 7
        val arch = high + 5
        canvas.set(x, 0, z, B(M.WALL_DARK))
        if (abs(x) <= 2) {
            for (y in 1 until high - 1) canvas.set(x, y, z, WALL)
            canvas.set(x, high - 1, z, B(M.PAVE_PLAZA, v = 1))
            for (y in high until arch) {
                val b = when {
                    y == arch - 1 && abs(x) == 2 -> B(M.WALL_STAIRS, if (x > 0) Dir.W else Dir.E, top = true)
                    depth == 0 && y >= arch - 2 -> B(M.BARS)
                    else -> AIR
                }
                canvas.set(x, y, z, b)
            }
            for (y in arch until top) canvas.set(x, y, z, if (y == arch || y == citadelWalk - 1) TRIM else WALL)
            if (depth == 1 && x == 0) canvas.set(x, arch - 1, z, B(M.LANTERN, top = true))
        } else {
            val rim = abs(x) == CITADEL_GATE_HALF || depth == 0 || depth == CITADEL_THICK - 1
            for (y in 1 until citadelWalk) canvas.set(x, y, z, if (y == high - 1 && depth == 0) TRIM else WALL)
            for (y in citadelWalk until top) {
                val b = when {
                    y == top - 1 -> WALL
                    !rim -> AIR
                    abs(x) == CITADEL_GATE_HALF && depth in 1..2 && y < citadelWalk + 2 -> AIR
                    (y == citadelWalk + 2 || y == citadelWalk + 3) && depth == 0 && abs(x) == 4 -> B(M.WINDOW)
                    else -> WALL
                }
                canvas.set(x, y, z, b)
            }
        }
        if ((depth == 0 || depth == CITADEL_THICK - 1 || abs(x) == CITADEL_GATE_HALF) && Math.floorMod(x + depth, 2) == 0) canvas.set(x, top, z, WALL)
    }

    /** Banners and torches on the citadel gatehouse's face, over the landing. */
    private fun citadelGateFront(x: Int, z: Int) {
        if (z != plan.citadel.z1 + 1 || abs(x) > 5) return
        if (abs(x) == 3) canvas.set(x, high + 2, z, B(M.TORCH, Dir.S))
        if (abs(x) == 5) canvas.set(x, citadelWalk + 2, z, B(M.BANNER, Dir.S, color = BANNER_COLOR))
    }

    /** A flight of steps up the inside of a wall, solid underneath, with a landing at the top. */
    private fun wallStair(s: WallStair) {
        for (k in 0..s.length) {
            val x = s.x + s.up.dx * k
            val z = s.z + s.up.dz * k
            if (!clip.contains(x, z)) continue
            if (k == s.length) {
                for (y in s.y until s.y + s.length) canvas.set(x, y, z, WALL)
                continue
            }
            for (y in s.y until s.y + k) canvas.set(x, y, z, WALL)
            canvas.set(x, s.y + k, z, B(M.WALL_STAIRS, s.up))
        }
    }

    /** The step outward from the middle of the city, along whichever axis this column is further out on. */
    private fun outward(x: Int, z: Int): Pair<Int, Int> =
        if (abs(x) >= abs(z)) Pair(Integer.signum(x), 0) else Pair(0, Integer.signum(z))

    private fun dirOf(dx: Int, dz: Int): Dir = Dir.entries.first { it.dx == dx && it.dz == dz }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    companion object {
        /** How far out the outer gatehouse reaches, counted from the wall's inner face. */
        const val GATE_DEPTH = WALL_THICK + GATE_OUT
        const val ARCH = 7
        const val INNER_ARCH = 5
        const val INNER_GATE_HALF = 5
        const val CITADEL_GATE_HALF = 6
        const val ROOF_SLATE = 0
        val BANNER_COLOR = Dye.BLUE

        val AIR = B.AIR
        val WALL = B(M.WALL)
        val TRIM = B(M.WALL_TRIM)
        val FOUNDATION = B(M.FOUNDATION)
        val FILL = B(M.FILL)
        val LANE = B(M.PAVE_LANE)
        val YARD = B(M.PAVE_YARD)
        val GRASS = B(M.GRASS)
        val SAND = B(M.SOIL, v = 1)
    }
}
