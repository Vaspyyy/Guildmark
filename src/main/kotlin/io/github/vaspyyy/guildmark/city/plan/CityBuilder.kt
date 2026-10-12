package io.github.vaspyyy.guildmark.city.plan

import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.CLEAR
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.CORNER_RADIUS
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.INNER
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.KEEP_WARD
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.PLAZA
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.REACH
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.WALL_IN
import io.github.vaspyyy.guildmark.city.plan.CityPlan.Companion.WALL_OUT
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Draws a [CityPlan] onto a [Canvas]. The ground, walls and towers are worked out column by column,
 * so each column's blocks come from that column alone; buildings and street furniture are drawn
 * whole wherever they touch the canvas, which throws away whatever falls outside it.
 */
class CityBuilder(private val plan: CityPlan, private val canvas: Canvas) {
    private val grid = plan.grid
    private val clip = canvas.clip
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
        val x0 = max(clip.x0, -REACH)
        val x1 = minOf(clip.x1, REACH)
        val z0 = max(clip.z0, -REACH)
        val z1 = minOf(clip.z1, REACH)
        for (x in x0..x1) for (z in z0..z1) ground(x, z)
        for (x in x0..x1) for (z in z0..z1) fortification(x, z)
        for (s in plan.stairs) wallStair(s)
        val buildings = Buildings(plan, canvas)
        for (b in plan.buildings) {
            if (canvas.touches(b.lot.grow(3))) buildings.draw(b)
        }
        Props(plan, canvas).draw()
    }

    // ---- The ground ----

    private fun ground(x: Int, z: Int) {
        when (val use = grid[x, z]) {
            Use.OUTSIDE -> {}
            Use.CLEARING -> canvas.clearing(x, z)
            Use.WALL, Use.TOWER, Use.GATE, Use.KEEP_WALL, Use.LOT -> canvas.ground(x, z, FOUNDATION, FOUNDATION)
            Use.PLAZA -> canvas.ground(x, z, plazaPaving(x, z), FILL)
            Use.COURT -> canvas.ground(x, z, courtPaving(x, z), FILL)
            Use.AVENUE -> canvas.ground(x, z, avenuePaving(x, z), FILL)
            Use.LANE, Use.POMERIUM -> canvas.ground(x, z, if (grid[x, z] == Use.LANE && max(abs(x), abs(z)) > WALL_OUT) B(M.PAVE_LANE, v = 1) else LANE, FILL)
            Use.YARD -> canvas.ground(x, z, YARD, FILL)
            Use.GARDEN -> canvas.ground(x, z, GRASS, FILL)
            Use.FREE -> canvas.ground(x, z, YARD, FILL)
        }
        // A sloping plinth along the foot of the walls, outside
        if (grid[x, z] == Use.CLEARING && max(abs(x), abs(z)) == WALL_OUT + 1) {
            val (ox, oz) = outward(x, z)
            val inner = grid[x - ox, z - oz]
            if (inner == Use.WALL) canvas.set(x, 0, z, B(M.WALL_STAIRS, dirOf(-ox, -oz)))
        }
    }

    /** Rings of paving round the fountain, a border at the plaza's edge. */
    private fun plazaPaving(x: Int, z: Int): B {
        val ring = max(abs(x), abs(z))
        val round = sqrt((x * x + z * z).toDouble())
        return when {
            ring >= PLAZA - 1 -> B(M.PAVE_PLAZA, v = 1)
            round.toInt() % 4 == 0 -> B(M.PAVE_PLAZA, v = 1)
            (x + z) and 1 == 0 && round < 10 -> B(M.PAVE_PLAZA, v = 2)
            else -> B(M.PAVE_PLAZA)
        }
    }

    private fun courtPaving(x: Int, z: Int): B = if ((x + z) and 3 == 0) B(M.PAVE_PLAZA, v = 2) else B(M.PAVE_PLAZA)

    /** Avenues have a kerb of a darker stone along each side. */
    private fun avenuePaving(x: Int, z: Int): B {
        val across = if (abs(x) <= 3 && abs(z) > 3) abs(x) else abs(z)
        return if (across == 3) B(M.PAVE_STREET, v = 1) else B(M.PAVE_STREET)
    }

    // ---- Walls, towers and gates ----

    private fun fortification(x: Int, z: Int) {
        // Gates first: their towers and passages sit across the wall line
        for (side in plan.gates) if (gate(side, x, z)) return
        when (grid[x, z]) {
            Use.WALL -> wall(x, z)
            Use.TOWER -> tower(x, z)
            Use.KEEP_WALL -> wardWall(x, z)
            Use.CLEARING -> towerBanner(x, z)
            else -> {}
        }
        if (abs(x) <= 6 && z in KEEP_WARD.z1 - 3..KEEP_WARD.z1 + 1) wardGate(x, z)
    }

    /** The city's banner hanging down the outer face of each tower on the walls. */
    private fun towerBanner(x: Int, z: Int) {
        for (t in plan.towers) {
            val side = t.side ?: continue
            if (x == t.x + side.dx * 4 && z == t.z + side.dz * 4) canvas.set(x, MID_ROOF - 2, z, B(M.BANNER, side, color = BANNER_COLOR))
        }
    }

    /** The curtain wall: solid to the wall walk, a parapet with merlons outside and a railing inside. */
    private fun wall(x: Int, z: Int) {
        val ring = max(abs(x), abs(z))
        val depth = ring - WALL_IN
        val along = if (abs(x) > abs(z)) z else x
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1..WALK - 1) canvas.set(x, y, z, if (y == WALK - 1 && (depth == 0 || depth == 3)) TRIM else WALL)
        when (depth) {
            0 -> if (key(x, z) !in landings) {
                canvas.set(x, WALK, z, B(M.WALL_POST))
                if (Math.floorMod(along, 8) == 4) canvas.set(x, WALK + 1, z, B(M.LANTERN))
            }
            3 -> {
                canvas.set(x, WALK, z, WALL)
                if (Math.floorMod(along, 3) != 0) canvas.set(x, WALK + 1, z, WALL)
            }
        }
    }

    private fun tower(x: Int, z: Int) {
        for (t in plan.towers) {
            when (t.kind) {
                TowerKind.CORNER -> if (CityPlan.inRound(x - t.x, z - t.z, CORNER_RADIUS)) return cornerTower(t, x, z)
                TowerKind.MID -> if (abs(x - t.x) <= 3 && abs(z - t.z) <= 3) return midTower(t, x, z)
            }
        }
    }

    /**
     * A round tower at a corner: solid to the wall walk, a guard room above it opening onto both walls,
     * and a tall slate cone with a pennant on top.
     */
    private fun cornerTower(t: Tower, x: Int, z: Int) {
        val dx = x - t.x
        val dz = z - t.z
        val r = sqrt((dx * dx + dz * dz).toDouble())
        val shell = r > CORNER_RADIUS - 2
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until WALK) canvas.set(x, y, z, WALL)
        // Doorways from both wall walks
        val walkway = isWalkway(x, z)
        for (y in WALK until CORNER_EAVES) {
            val b = when {
                !shell -> AIR
                walkway && y < WALK + 2 -> AIR
                // Arrow slits looking out
                y == WALK + 2 && (dx == 0 || dz == 0) && r > CORNER_RADIUS - 1 && (dx * t.x > 0 || dz * t.z > 0) -> B(M.WINDOW)
                y == CORNER_EAVES - 1 -> TRIM
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        // Inside: a lantern hanging in the middle, a barrel and a bench
        if (dx == 0 && dz == 0) canvas.set(x, CORNER_EAVES - 1, z, B(M.LANTERN, top = true))
        // The roof: a cone overhanging the walls, hollow, with a ceiling under it
        val roof = CORNER_RADIUS + 1
        for (k in 0..CONE_HEIGHT) {
            val radius = roof - k * (roof / CONE_HEIGHT)
            if (r > radius + 0.01) break
            val y = CORNER_EAVES + k
            val edge = k == 0 || r > radius - 1.3
            if (edge) canvas.set(x, y, z, if (k == 0 && r > CORNER_RADIUS) B(M.ROOF_SLAB, v = ROOF_SLATE) else B(M.ROOF, v = ROOF_SLATE))
        }
        if (dx == 0 && dz == 0) {
            val top = CORNER_EAVES + CONE_HEIGHT + 1
            canvas.set(x, top, z, B(M.FENCE))
            canvas.set(x, top + 1, z, B(M.FENCE))
            canvas.set(x, top + 2, z, B(M.FENCE))
        }
        // A pennant off the pole, streaming outward
        val (ox, oz) = Pair(Integer.signum(t.x), Integer.signum(t.z))
        if (dx == ox && dz == 0 || dx == 0 && dz == oz) {
            val top = CORNER_EAVES + CONE_HEIGHT + 2
            canvas.set(x, top + 1, z, B(M.WOOL, color = BANNER_COLOR))
        }
    }

    /** Is this column on the wall walk (the two middle rows of the curtain wall's top)? */
    private fun isWalkway(x: Int, z: Int): Boolean {
        val ax = abs(x)
        val az = abs(z)
        return (ax - WALL_IN in 1..2 && az <= CityPlan.CORNER_TOWER) || (az - WALL_IN in 1..2 && ax <= CityPlan.CORNER_TOWER)
    }

    /** A square tower on the wall: a guard room over the wall walk and a crenellated top. */
    private fun midTower(t: Tower, x: Int, z: Int) {
        val side = t.side ?: return
        val along = if (side.alongX) z - t.z else x - t.x
        val out = if (side.alongX) abs(x) - WALL_IN else abs(z) - WALL_IN
        val rim = abs(along) == 3 || out == 0 || out == 6
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until WALK) canvas.set(x, y, z, WALL)
        for (y in WALK..MID_ROOF) {
            val b = when {
                y == MID_ROOF -> WALL
                !rim -> if (y == MID_ROOF - 1 && along == 0 && out == 3) B(M.LANTERN, top = true) else AIR
                // Through the tower along the wall walk
                abs(along) == 3 && out in 1..2 && y < WALK + 2 -> AIR
                y == WALK + 2 && out == 6 && along == 0 -> B(M.WINDOW)
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        // A ladder up to the roof in a back corner, through a hatch
        if (along == 2 && out == 5) {
            for (y in WALK until MID_ROOF) canvas.set(x, y, z, B(M.LADDER, side.opposite))
            canvas.set(x, MID_ROOF, z, B(M.TRAPDOOR, side.opposite, top = true))
        }
        if (rim && (Math.floorMod(along + out, 2) == 0)) canvas.set(x, MID_ROOF + 1, z, WALL)
    }

    /**
     * A gatehouse: a tower either side of an arched way through the wall, a portcullis drawn up into
     * the arch, and a fighting top over it between the towers. Returns whether ([x], [z]) is part of it.
     */
    private fun gate(side: Dir, x: Int, z: Int): Boolean {
        val along = if (side.alongX) z else x
        val out = (if (side.alongX) abs(x) else abs(z)) - WALL_IN
        val sameSide = if (side.alongX) x * side.dx > 0 else z * side.dz > 0
        if (!sameSide || abs(along) > 8 || out !in 0..8) return false
        if (out == 8) {
            // Out in front: torches either side of the arch and the city's banners on the towers
            if (abs(along) == 3) canvas.set(x, 3, z, B(M.TORCH, side))
            if (abs(along) == 5) canvas.set(x, WALK + 2, z, B(M.BANNER, side, color = BANNER_COLOR))
            return true
        }
        if (abs(along) <= 2) {
            gatePassage(side, x, z, along, out)
            return true
        }
        val rim = abs(along) == 3 || abs(along) == 8 || out == 0 || out == 7
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until WALK) canvas.set(x, y, z, if (y == WALK - 1) TRIM else WALL)
        for (y in WALK until GATE_TOP) {
            val b = when {
                y == GATE_TOP - 1 -> WALL
                !rim -> AIR
                // Doorways from the wall walk and out onto the top of the arch
                abs(along) == 8 && out in 1..2 && y < WALK + 2 -> AIR
                abs(along) == 3 && out in 3..4 && y < WALK + 2 -> AIR
                y == WALK + 2 && out == 7 && abs(along) in 5..6 -> B(M.WINDOW)
                else -> WALL
            }
            canvas.set(x, y, z, b)
        }
        if (rim && Math.floorMod(along + out, 2) == 0) canvas.set(x, GATE_TOP, z, WALL)
        if (!rim && abs(along) == 5 && out == 3) canvas.set(x, GATE_TOP - 2, z, B(M.LANTERN, top = true))
        // A ladder up through a hatch onto the roof
        if (abs(along) == 4 && out == 6) {
            for (y in WALK until GATE_TOP - 1) canvas.set(x, y, z, B(M.LADDER, side.opposite))
            canvas.set(x, GATE_TOP - 1, z, B(M.TRAPDOOR, side.opposite, top = true))
        }
        return true
    }

    /** The way through: paved, arched, lit, with the portcullis teeth showing at the top of the arch. */
    private fun gatePassage(side: Dir, x: Int, z: Int, along: Int, out: Int) {
        canvas.ground(x, z, B(M.PAVE_STREET), FILL)
        for (y in 0 until ARCH) {
            val b = when {
                y == ARCH - 1 && abs(along) == 2 -> B(M.WALL_STAIRS, plan.sideDir(side, Integer.signum(along)), top = true)
                out == 6 && y >= ARCH - 3 -> B(M.BARS)
                else -> AIR
            }
            canvas.set(x, y, z, b)
        }
        if (along == 0 && (out == 1 || out == 4)) canvas.set(x, ARCH - 1, z, B(M.LANTERN, top = true))
        for (y in ARCH until WALK) canvas.set(x, y, z, if (y == ARCH || y == WALK - 1) TRIM else WALL)
        // The fighting top: a railing on the town side, merlons on the field side
        when (out) {
            0 -> canvas.set(x, WALK, z, B(M.WALL_POST))
            7 -> {
                canvas.set(x, WALK, z, WALL)
                if (Math.floorMod(along, 2) == 0) canvas.set(x, WALK + 1, z, WALL)
            }
        }
    }

    /** The keep ward's curtain: lower than the city walls, with merlons on the town side. */
    private fun wardWall(x: Int, z: Int) {
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until WARD_WALK) canvas.set(x, y, z, WALL)
        val outer = x == KEEP_WARD.x0 || x == KEEP_WARD.x1 || z == KEEP_WARD.z1
        if (outer) {
            canvas.set(x, WARD_WALK, z, WALL)
            val along = if (z == KEEP_WARD.z1) x else z
            if (Math.floorMod(along, 2) == 0) canvas.set(x, WARD_WALK + 1, z, WALL)
        }
    }

    /** The ward's own gate: a pair of small towers either side of an arch, inside the curtain. */
    private fun wardGate(x: Int, z: Int) {
        val inGate = abs(x) <= 2 && z in KEEP_WARD.z1 - 1..KEEP_WARD.z1
        val towerCell = abs(x) in 3..6 && z in KEEP_WARD.z1 - 3..KEEP_WARD.z1
        if (inGate) {
            for (y in 0 until WARD_ARCH) canvas.set(x, y, z, if (y == WARD_ARCH - 1 && abs(x) == 2) B(M.WALL_STAIRS, if (x > 0) Dir.E else Dir.W, top = true) else AIR)
            for (y in WARD_ARCH until WARD_WALK) canvas.set(x, y, z, WALL)
            canvas.set(x, WARD_WALK, z, WALL)
            if (z == KEEP_WARD.z1 && x and 1 == 0) canvas.set(x, WARD_WALK + 1, z, WALL)
            if (x == 0 && z == KEEP_WARD.z1 - 1) canvas.set(x, WARD_ARCH - 1, z, B(M.LANTERN, top = true))
            return
        }
        // A banner either side of the arch, facing the town
        if (abs(x) == 4 && z == KEEP_WARD.z1 + 1) canvas.set(x, WARD_TOWER - 3, z, B(M.BANNER, Dir.S, color = BANNER_COLOR))
        if (!towerCell) return
        val rim = abs(x) == 3 || abs(x) == 6 || z == KEEP_WARD.z1 - 3 || z == KEEP_WARD.z1
        canvas.ground(x, z, FOUNDATION, FOUNDATION)
        canvas.set(x, 0, z, B(M.WALL_DARK))
        for (y in 1 until WARD_TOWER) canvas.set(x, y, z, if (rim || y == WARD_TOWER - 1) WALL else AIR)
        if (rim && (x + z) and 1 == 0) canvas.set(x, WARD_TOWER, z, WALL)
        if (abs(x) == 4 && z == KEEP_WARD.z1 - 2) canvas.set(x, WARD_TOWER - 2, z, B(M.LANTERN, top = true))
    }

    /** A flight of steps up the inside of the wall, solid underneath, with a landing at the top. */
    private fun wallStair(s: WallStair) {
        for (k in 0..s.length) {
            val x = s.x + s.up.dx * k
            val z = s.z + s.up.dz * k
            if (!clip.contains(x, z)) continue
            if (k == s.length) {
                for (y in 0 until WALK) canvas.set(x, y, z, WALL)
                continue
            }
            for (y in 0 until k) canvas.set(x, y, z, WALL)
            canvas.set(x, k, z, B(M.WALL_STAIRS, s.up))
        }
    }

    /** The step outward from the middle of the city, along whichever axis this column is further out on. */
    private fun outward(x: Int, z: Int): Pair<Int, Int> =
        if (abs(x) >= abs(z)) Pair(Integer.signum(x), 0) else Pair(0, Integer.signum(z))

    private fun dirOf(dx: Int, dz: Int): Dir = Dir.entries.first { it.dx == dx && it.dz == dz }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    companion object {
        /** Standing height on the wall walk. */
        const val WALK = 10
        const val CORNER_EAVES = 17
        const val CONE_HEIGHT = 9
        const val MID_ROOF = 14
        const val ARCH = 6
        const val GATE_TOP = 16
        const val WARD_WALK = 7
        const val WARD_ARCH = 4
        const val WARD_TOWER = 11
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
    }
}
