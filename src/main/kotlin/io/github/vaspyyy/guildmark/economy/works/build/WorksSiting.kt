package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.FarmKind
import io.github.vaspyyy.guildmark.city.plan.Frame
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.NullCanvas
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Use
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksSite
import io.github.vaspyyy.guildmark.homestead.HomesteadSite
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where each works goes (living-world/design-influence.md section 3's "Where"), worked out once (and once a day while
 * none is found), a few hundred lots a second at most and only in loaded land:
 *
 *  - A capital drawn since there were works (Layout version 2) keeps lots for them: the granary's south of the
 *    bakery, the oven's bay in the bakery itself, and two stall pitches at the market square's south corners
 *    (CityPlan.worksLots). They're used while they're as the plan left them.
 *  - Anywhere else a works is sited on what the world shows: on ground the plan left free (FREE, GARDEN, YARD or
 *    CLEARING) at its planned height, every block it will fill air or a plant, the ground under it solid, out from
 *    the market hall within 48 blocks; a granary then by the farms within 32 of a mill.
 *  - The second oven goes into the bakery's other back corner when everything there is still the plan's (its
 *    barrels and chest are carried with what's in them), else into a lean-to against the bakery's side or back on
 *    free ground; else it waits, what's in the way outlined to whoever is near, until someone clears it.
 *  - A stall takes a clear 3 by 3 of the square's paving, not before a door or on the avenue.
 *  - A tower stands by its road 128 to 256 blocks out from the farm edge (at the farm edge for the plan's own road),
 *    on the highest ground within 16 blocks of the road, facing it, its path out to it; sited once a player has come
 *    within 128 blocks of where it would go.
 *  - The great granary is the granary's second bay, in the granary's own frame.
 *
 * A site set by `works site <id> here` (Works.siteHere) is taken as given: the drawing centred on it.
 */
internal object WorksSiting {
    /** Candidate lots looked at on the plan, and in the world, in one works' second. */
    private const val PLAN_BUDGET = 6000
    private const val WORLD_BUDGET = 40

    /** How far out from the market hall a works is looked for, and from a mill for a granary. */
    private const val NEAR_HALL = 48
    private const val NEAR_MILL = 32

    /** How near a player must have come to where a tower would stand for it to be sited. */
    private const val TOWER_SEEN = 128.0

    /** Ticks between looks at an oven whose bay and lean-tos were all in the way (someone may clear them). */
    private const val RETRY_OVEN = 100L

    /** The ground a works may be sited on where the plan has no lot for it. */
    private val FREE_USES = setOf(Use.FREE, Use.GARDEN, Use.YARD, Use.CLEARING)

    /** The works whose siting reads the city's plan at every look. */
    private val ON_PLAN = setOf(WorksKind.OVEN, WorksKind.GRANARY, WorksKind.STALL, WorksKind.COOKSHOP)

    /** A lot to try: the drawing's (0, 0) at [ox], [oz] in the city's frame, its front toward [front]. */
    private class Cand(val ox: Int, val oz: Int, val front: Dir)

    /** A search under way: its lots, best first, and how far through them it is. */
    private class Search(val cands: List<Cand>) {
        var next = 0
    }

    private val searches = HashMap<Int, Search>()

    /** The market day each works' last look came to nothing (looked for again the next day). */
    private val failed = HashMap<Int, Long>()

    /** What holds a works' site up (the oven's bay), outlined to those near, and shown on its screen. */
    private val inWay = HashMap<Int, BlockPos>()

    /** When each works was last looked for (game time). */
    private val looked = HashMap<Int, Long>()

    fun reset() {
        searches.clear()
        failed.clear()
        inWay.clear()
        looked.clear()
    }

    /** What holds works [id]'s site up, if anything does (for its screen). */
    fun inWay(id: Int): BlockPos? = inWay[id]

    /** Whether [site] is the one the building worked out for [rec] (not one set elsewhere since). */
    fun ours(rec: BuildRec, site: WorksSite): Boolean {
        if (!rec.sited) return false
        val p = WorksPlans.of(rec)
        val d = p.drawing
        return site.facing == rec.front && site.origin == p.frame.world(d.heart.x, d.y0 + d.heart.y, d.heart.z)
    }

    /** Works [id]'s site forgotten (it's being looked for again). */
    fun forget(id: Int) {
        searches.remove(id)
        failed.remove(id)
        inWay.remove(id)
        looked.remove(id)
    }

    /**
     * Looks for [w]'s site, or takes the one it was given ([w].site, from `works site <id> here`), and tells the works
     * (Works.sited): the site, or none once a day while none is found.
     */
    fun look(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece) {
        val given = w.site
        if (given != null) {
            adopt(level, w, rec, capital, given)
            return
        }
        val day = Works.today(level, w.capital)
        val retry = w.kind == WorksKind.OVEN || w.kind == WorksKind.TOWER
        if (failed[w.id] == day && !retry) return
        val now = level.gameTime
        if (retry && failed.containsKey(w.id) && now - (looked[w.id] ?: 0L) in 0 until RETRY_OVEN) {
            outline(level, w.id)
            return
        }
        looked[w.id] = now
        // These are sited on the city's plan, which is laid out off the server's thread with the town's places: until
        // those are known they wait, as for land not loaded, rather than lay the plan out here
        if (w.kind in ON_PLAN && CityPlaces.of(level, capital) == null && !CityPlaces.failed(capital)) return
        val found = when (w.kind) {
            WorksKind.OVEN -> oven(level, w, rec, capital)
            WorksKind.GRANARY -> granary(level, w, rec, capital)
            WorksKind.GREAT_GRANARY -> great(level, w, rec, capital)
            WorksKind.TOWER -> tower(level, w, rec, capital)
            WorksKind.STALL -> stall(level, w, rec, capital)
            WorksKind.COOKSHOP -> cookshop(level, w, rec, capital)
        } ?: return
        if (found) {
            forget(w.id)
            return
        }
        searches.remove(w.id)
        if (failed[w.id] != day) {
            failed[w.id] = day
            Works.sited(level, w.id, null)
        }
        outline(level, w.id)
    }

    // ---- The oven ----

    /** The bay, or a lean-to, or nothing; null while the bakery's land isn't loaded. */
    private fun oven(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece): Boolean? {
        val plan = capital.plan
        val bakery = plan.buildings.firstOrNull { it.kind == Kind.BAKERY } ?: return false
        val bay = frameOn(capital, bakery.lot, bakery.front, Rect(0, 0, 0, 0))
        val y0 = capital.center.y + bakery.y
        val area = bay.box(Rect(-6, -4, 17, 17), y0, y0)
        if (!loaded(level, area)) return null
        val planned = (plan.layout.version >= 2)
        val first = trial(level, w, capital, bay, y0, 0)
        val held = first.inWay()
        if (held == null && !taken(level, w, first.box)) {
            settle(level, w, rec, capital, bay, y0, 0, planned)
            return true
        }
        // A lean-to against the bakery's side or its back, on free ground: in the bakery's own frame, out from each wall
        for ((u0, v0, out) in LEAN_TOS) {
            val frame = WorksFrame(bay.world(u0, y0, v0), bay.direction(out))
            val t = trial(level, w, capital, frame, y0, 1)
            if (!loaded(level, t.box.inflatedBy(Works.YARD, 0, Works.YARD))) continue
            if (t.inWay() != null || taken(level, w, t.box)) continue
            if (!walkable(level, frame.world(t.drawing.door.x, y0, t.drawing.door.z))) continue
            settle(level, w, rec, capital, frame, y0, 1, false)
            return true
        }
        if (held != null) inWay[w.id] = held
        return false
    }

    /**
     * The lean-to's frames in the bakery's own (its front to the north, twelve by twelve), its back wall (z 4) on the
     * bakery's: the near back corner first on each side, then along.
     */
    private val LEAN_TOS: List<Triple<Int, Int, Dir>> = buildList {
        for (v0 in 10 downTo 5) add(Triple(-4, v0, Dir.W))
        for (v0 in 6 downTo 1) add(Triple(15, v0, Dir.E))
        for (u0 in 5..10) add(Triple(u0, 15, Dir.S))
    }

    // ---- The granary ----

    private fun granary(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece): Boolean? {
        val plan = capital.plan
        val lot = plan.worksLots.firstOrNull { it.kind == WorksKind.GRANARY.id }
        if (lot != null) {
            val frame = frameOn(capital, lot.rect, lot.front, Granary.LOT)
            val y0 = capital.center.y + lot.y
            val t = trial(level, w, capital, frame, y0, 0)
            if (!loaded(level, t.box.inflatedBy(Works.YARD, 0, Works.YARD))) return null
            if (t.inWay() == null && !taken(level, w, t.box)) {
                settle(level, w, rec, capital, frame, y0, 0, true)
                return true
            }
        }
        val search = searches.getOrPut(w.id) {
            val hall = plan.buildings.firstOrNull { it.kind == Kind.MARKET_HALL }?.lot ?: plan.market
            val anchors = ArrayList<Pair<CityPlan.Pos, Int>>()
            anchors.add(CityPlan.Pos((hall.x0 + hall.x1) / 2, (hall.z0 + hall.z1) / 2) to NEAR_HALL)
            for (m in plan.outskirts.buildings) {
                if (m.kind == FarmKind.WINDMILL || m.kind == FarmKind.MILL) anchors.add(CityPlan.Pos((m.lot.x0 + m.lot.x1) / 2, (m.lot.z0 + m.lot.z1) / 2) to NEAR_MILL)
            }
            Search(around(anchors, Granary.LOT))
        }
        return find(level, w, rec, capital, search, Granary.LOT, Granary.DOOR, street = true)
    }

    /** The great granary: the granary's second bay, in its frame. */
    private fun great(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece): Boolean? {
        val g = WorksData.get(level).done(w.capital, WorksKind.GRANARY) ?: return false
        val grec = WorksBuildData.get(level)[g.id]?.takeIf { it.sited && !it.removed } ?: return false
        val t = trial(level, w, capital, grec.frame, grec.y0, 0, grec)
        if (!loaded(level, t.box.inflatedBy(Works.YARD, 0, Works.YARD))) return null
        val held = t.inWay()
        if (held != null) {
            inWay[w.id] = held
            return false
        }
        settle(level, w, rec, capital, grec.frame, grec.y0, 0, grec.planned, like = grec)
        return true
    }

    // ---- The stall ----

    private fun stall(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece): Boolean? {
        val plan = capital.plan
        var waiting = false
        for (lot in plan.worksLots.filter { it.kind == WorksKind.STALL.id }) {
            val frame = frameOn(capital, lot.rect, lot.front, Stall.LOT)
            val y0 = capital.center.y + lot.y
            val t = trial(level, w, capital, frame, y0, 0)
            if (!loaded(level, t.box.inflatedBy(2, 0, 2))) {
                waiting = true
                continue
            }
            if (t.inWay() == null && !taken(level, w, t.box)) {
                settle(level, w, rec, capital, frame, y0, 0, true)
                return true
            }
        }
        if (waiting) return null
        val search = searches.getOrPut(w.id) { Search(pitches(plan, Stall.LOT)) }
        return find(level, w, rec, capital, search, Stall.LOT, Stall.DOOR, street = true, uses = setOf(Use.SQUARE))
    }

    // ---- The cookshop ----

    /**
     * A clear 5 by 4 of the market square, its back to the nearest edge first, as a stall finds its pitch but with no lot of the plan's to
     * try first, and with its buyers' ground (all five cells before the counter) and its keeper's way in (round the flank to the alley's near
     * end) left free to walk on. A middling capital's square has no clear 5 by 4 against its edge (its stalls and wells and the houses on its
     * south rim are there), so the spots away from the edge are tried after: the strips beside the avenue, with the row before the counter
     * on the avenue's edge (the avenue's own ground, three out from its middle, stays clear). One that would face the back of a stall is
     * not sited, for the row before its counter is not free there.
     */
    private fun cookshop(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece): Boolean? {
        val plan = capital.plan
        val search = searches.getOrPut(w.id) { Search(pitches(plan, Cookshop.LOT, Int.MAX_VALUE, AVENUE_EDGE)) }
        return find(
            level, w, rec, capital, search, Cookshop.LOT, Cookshop.DOOR, street = true, uses = setOf(Use.SQUARE),
            walks = Cookshop.FRONT + Cookshop.FLANK,
        )
    }

    /** How far from the middle of the avenue its own ground goes: the row before a cookshop's counter may reach the cell just beyond. */
    private const val AVENUE_EDGE = 3

    /**
     * The spots of the market square a stall or a cookshop could take ([lot]: 3 by 3, or 5 by 4), those nearest its edge
     * first: clear of the avenue down its middle and of the ground before any door, the pitch's front onto the square, and its back
     * no more than [near] from the square's edge (a stall's two). The lot stays more than four from the avenue's middle (clear of its
     * own ground, its lamps and a cell besides); the row before it more than [frontBand] (four, as a stall's does).
     */
    private fun pitches(plan: CityPlan, lot: Rect, near: Int = 2, frontBand: Int = 4): List<Cand> {
        val m = plan.market
        val doors = HashSet<Long>()
        for (b in plan.buildings) {
            val f = Frame(NullCanvas, b.lot, b.front)
            for (u in b.door - 3..b.door + 3) for (v in -3..-1) doors.add(key(f.x(u, v), f.z(u, v)))
        }
        val out = ArrayList<Pair<Int, Cand>>()
        for (x in m.x0..m.x1) for (z in m.z0..m.z1) for (front in Dir.entries) {
            val pf = planFrame(x, z, front)
            var ok = true
            for (lx in lot.x0..lot.x1) for (lz in lot.z0 - 1..lot.z1) {
                val px = pf.worldX(lx, lz)
                val pz = pf.worldZ(lx, lz)
                if (!m.contains(px, pz) || abs(px) <= (if (lz < lot.z0) frontBand else 4) || key(px, pz) in doors || plan.grid[px, pz] != Use.SQUARE) ok = false
            }
            if (!ok) continue
            // Its back to the nearest edge: nearer the edge first
            val bx = pf.worldX(0, lot.z1 + 1)
            val bz = pf.worldZ(0, lot.z1 + 1)
            val edge = min(min(bx - m.x0, m.x1 - bx), min(bz - m.z0, m.z1 - bz))
            if (edge > near) continue
            out.add((edge.coerceAtLeast(0) * 1000 + abs(x) + abs(z - (m.z0 + m.z1) / 2)) to Cand(x, z, front))
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    // ---- The tower ----

    /** By its road: null until a player has come near where it would go (and its land is loaded). */
    private fun tower(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece): Boolean? {
        val legs = CaravanRoutes.legs(level, w.capital) ?: return null
        val leg = legs.firstOrNull { it.road == w.road } ?: return false
        val far = if (leg.road < 0) (0..5).map { leg.farms - 6.0 - it * 6.0 } else (0..16).map { leg.farms - 128.0 - it * 8.0 }
        val alongs = far.filter { it >= 8.0 && it <= leg.length - 8.0 }
        if (alongs.isEmpty()) return false
        val mid = leg.at(alongs[alongs.size / 2])
        if (level.players().none { !it.isSpectator && it.distanceToSqr(mid.x, it.y, mid.z) < TOWER_SEEN * TOWER_SEEN }) return null
        class Pick(val frame: WorksFrame, val y0: Int, val path: IntArray, val along: Double)
        var best: Pick? = null
        var anyLoaded = false
        for (a in alongs) {
            val p = leg.at(a)
            val q = leg.at(min(a + 4.0, leg.length))
            var dx = q.x - p.x
            var dz = q.z - p.z
            val len = sqrt(dx * dx + dz * dz)
            if (len < 1e-3) continue
            dx /= len
            dz /= len
            for (side in intArrayOf(-1, 1)) for (off in intArrayOf(8, 11, 14, 16)) {
                // Out from the road, facing back to it
                val cx = p.x - dz * side * off
                val cz = p.z + dx * side * off
                val front = nearest(dz * side, -dx * side)
                val f0 = WorksFrame(BlockPos.ZERO, front)
                val ox = Math.floor(cx).toInt() - f0.worldX(0, 2)
                val oz = Math.floor(cz).toInt() - f0.worldZ(0, 2)
                val frame = WorksFrame(BlockPos(ox, 0, oz), front)
                val ground = towerGround(level, capital, leg, frame) ?: continue
                anyLoaded = true
                val path = towerPath(level, leg, frame) ?: continue
                val pick = best
                if (pick == null || ground > pick.y0 || ground == pick.y0 && abs(a - alongs[alongs.size / 2]) < abs(pick.along - alongs[alongs.size / 2])) {
                    best = Pick(frame, ground, path, a)
                }
            }
        }
        // Highest first, the rest in turn if the highest won't do as the world stands
        val pick = best ?: return if (anyLoaded) false else null
        val frame = WorksFrame(BlockPos(pick.frame.origin.x, pick.y0, pick.frame.origin.z), pick.frame.front)
        val t = trial(level, w, capital, frame, pick.y0, 0, path = pick.path)
        if (t.inWay() != null || taken(level, w, t.box)) return false
        settle(level, w, rec, capital, frame, pick.y0, 0, false, path = pick.path)
        return true
    }

    /** The cardinal direction nearest ([x], [z]). */
    private fun nearest(x: Double, z: Double): Direction =
        if (abs(x) > abs(z)) (if (x > 0) Direction.EAST else Direction.WEST) else (if (z > 0) Direction.SOUTH else Direction.NORTH)

    /**
     * The standing height a tower in [frame] would level its ground to (its middle's), if the ground round it will
     * do: loaded, the land's own, dry, off the road and out of the town, and no more than three up or down anywhere.
     */
    private fun towerGround(level: ServerLevel, capital: CapitalPiece, leg: CaravanRoutes.Leg, frame: WorksFrame): Int? {
        val box = frame.box(Rect(-3, -1, 3, 5), 0, 0)
        if (!loaded(level, box)) return null
        val centre = height(level, frame.worldX(0, 2), frame.worldZ(0, 2))
        for (x in -3..3) for (z in -1..5) {
            val wx = frame.worldX(x, z)
            val wz = frame.worldZ(x, z)
            if (capital.inTown(wx, wz) || leg.distance(wx + 0.5, wz + 0.5) < 4.0) return null
            val h = height(level, wx, wz)
            if (abs(h - centre) > 3) return null
            val top = BlockPos(wx, h - 1, wz)
            val state = level.getBlockState(top)
            if (!level.getFluidState(top).isEmpty || !level.getFluidState(top.above()).isEmpty || !HomesteadSite.wild(state)) return null
        }
        return centre
    }

    /** The heights of a path from a tower's door in [frame] straight out to its road, or null if it doesn't get there within 16 blocks. */
    private fun towerPath(level: ServerLevel, leg: CaravanRoutes.Leg, frame: WorksFrame): IntArray? {
        val out = ArrayList<Int>()
        for (i in 0 until 16) {
            val wx = frame.worldX(0, -1 - i)
            val wz = frame.worldZ(0, -1 - i)
            if (!level.isLoaded(BlockPos(wx, 0, wz))) return null
            if (leg.distance(wx + 0.5, wz + 0.5) < 2.0) return out.toIntArray()
            val h = height(level, wx, wz)
            if (!level.getFluidState(BlockPos(wx, h - 1, wz)).isEmpty) return null
            out.add(h)
        }
        return null
    }

    // ---- Searching the plan, then the world ----

    /** The frame of the drawing laid at [ox], [oz] of the city's own frame, its front toward [front] (the city's frame, unturned). */
    private fun planFrame(ox: Int, oz: Int, front: Dir): WorksFrame = WorksFrame(BlockPos(ox, 0, oz), CityStyle.direction(front))

    /** Lots round each of [anchors] out to its radius, nearest first, each facing every way: the drawing's [lot] centred on each column. */
    private fun around(anchors: List<Pair<CityPlan.Pos, Int>>, lot: Rect): List<Cand> {
        val cx = (lot.x0 + lot.x1) / 2
        val cz = (lot.z0 + lot.z1) / 2
        val out = ArrayList<Cand>()
        for ((a, r) in anchors) {
            val ring = ArrayList<Pair<Int, Int>>()
            for (dx in -r..r) for (dz in -r..r) if (dx * dx + dz * dz <= r * r) ring.add(dx to dz)
            ring.sortBy { (dx, dz) -> dx * dx + dz * dz }
            for ((dx, dz) in ring) for (front in Dir.entries) {
                val f0 = planFrame(0, 0, front)
                out.add(Cand(a.x + dx - f0.worldX(cx, cz), a.z + dz - f0.worldZ(cx, cz), front))
            }
        }
        return out
    }

    /**
     * Works through [search]'s lots for one [lot] will stand on: on the plan, all of it on one of [uses] at one
     * height, the ground before its [door] a street (or free ground, if not [street]); in the world, loaded, clear
     * and on solid ground, and the drawing laid there with nothing in its way, and each of [walks] (pins outside the lot: the ground before its
     * door, a way in) somewhere to stand. Null while there are lots still to try; false when they're all tried.
     */
    private fun find(
        level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece, search: Search, lot: Rect, door: Pin,
        street: Boolean, uses: Set<Use> = FREE_USES, walks: List<Pin> = emptyList(),
    ): Boolean? {
        val plan = capital.plan
        var planned = 0
        var tried = 0
        while (search.next < search.cands.size) {
            if (planned >= PLAN_BUDGET || tried >= WORLD_BUDGET) return null
            val c = search.cands[search.next++]
            planned++
            val pf = planFrame(c.ox, c.oz, c.front)
            val h = planFit(plan, pf, lot, uses) ?: continue
            val dx = pf.worldX(door.x, door.z)
            val dz = pf.worldZ(door.x, door.z)
            val before = plan.grid[dx, dz]
            if (!before.street && (street || before !in uses)) continue
            tried++
            val y0 = capital.center.y + h
            val frame = WorksFrame(capital.world(c.ox, y0, c.oz), capital.direction(c.front))
            if (!worldFit(level, frame, lot, y0)) continue
            if (walks.any { !walkable(level, frame.world(it.x, y0, it.z)) }) continue
            val t = trial(level, w, capital, frame, y0, 0)
            if (t.inWay() != null || taken(level, w, t.box)) continue
            settle(level, w, rec, capital, frame, y0, 0, false)
            return true
        }
        return false
    }

    /** The plan's height all over [lot] laid in [pf], if every column of it is one of [uses] at that height. */
    private fun planFit(plan: CityPlan, pf: WorksFrame, lot: Rect, uses: Set<Use>): Int? {
        var h0 = Int.MIN_VALUE
        for (x in lot.x0..lot.x1) for (z in lot.z0..lot.z1) {
            val px = pf.worldX(x, z)
            val pz = pf.worldZ(x, z)
            if (plan.grid[px, pz] !in uses) return null
            val h = plan.level(px, pz)
            if (h0 == Int.MIN_VALUE) h0 = h else if (h != h0) return null
        }
        return h0
    }

    /** Whether [lot] laid in [frame] stands clear in the world: loaded, open sky over ground at [y0], a plant at most where it stands. */
    private fun worldFit(level: ServerLevel, frame: WorksFrame, lot: Rect, y0: Int): Boolean {
        if (!loaded(level, frame.box(lot, y0, y0))) return false
        for (x in lot.x0..lot.x1) for (z in lot.z0..lot.z1) {
            val wx = frame.worldX(x, z)
            val wz = frame.worldZ(x, z)
            if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz) != y0) return false
            val under = BlockPos(wx, y0 - 1, wz)
            val ground = level.getBlockState(under)
            if (!ground.isFaceSturdy(level, under, Direction.UP) || !ground.fluidState.isEmpty) return false
            if (!free(level, under.above())) return false
        }
        return true
    }

    /** Whether [pos] holds nothing but air or a plant anyone may build over (FestivalRestores.free's rule). */
    private fun free(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.isLoaded(pos)) return false
        val state = level.getBlockState(pos)
        return state.isAir || state.canBeReplaced() && state.fluidState.isEmpty && !state.hasBlockEntity() &&
            state.block !is net.minecraft.world.level.block.DoublePlantBlock
    }

    /** Whether someone can stand at [pos]: room for them, and ground under it. */
    private fun walkable(level: ServerLevel, pos: BlockPos): Boolean =
        free(level, pos) && free(level, pos.above()) && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)

    /** Whether all the land [box] covers is loaded. */
    private fun loaded(level: ServerLevel, box: BoundingBox): Boolean =
        level.isLoaded(BlockPos(box.minX(), box.minY(), box.minZ())) && level.isLoaded(BlockPos(box.maxX(), box.minY(), box.maxZ())) &&
            level.isLoaded(BlockPos(box.minX(), box.minY(), box.maxZ())) && level.isLoaded(BlockPos(box.maxX(), box.minY(), box.minZ()))

    /** The standing height of a world column (over the land, or over the water). */
    private fun height(level: ServerLevel, x: Int, z: Int): Int = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)

    /** Whether [box] (with room for its yard) runs into another works of the capital's. */
    private fun taken(level: ServerLevel, w: WorksRec, box: BoundingBox): Boolean {
        val mine = box.inflatedBy(2)
        for (o in WorksData.get(level).of(w.capital)) {
            if (o.id == w.id) continue
            // The great granary stands in its granary's lot
            if (w.kind == WorksKind.GREAT_GRANARY && o.kind == WorksKind.GRANARY || w.kind == WorksKind.GRANARY && o.kind == WorksKind.GREAT_GRANARY) continue
            val site = o.site ?: continue
            if (site.lot.intersects(mine)) return true
        }
        return false
    }

    // ---- Settling it ----

    /** [w]'s drawing laid in [frame] on ground at [y0] as a dry run, its record as it would be ([like]: the granary its bay builds on). */
    private fun trial(
        level: ServerLevel, w: WorksRec, capital: CapitalPiece, frame: WorksFrame, y0: Int, variant: Int, like: BuildRec? = null,
        path: IntArray = IntArray(0),
    ): TownWorksSite {
        val t = BuildRec(w.id, w.kind, w.capital)
        fill(level, w, t, capital, frame, y0, variant, false, like, path)
        return TownWorksSite(level, t, WorksPlans.trial(t))
    }

    /** [r] as [w] laid in [frame] on ground at [y0]: in the capital's style, its roof's colours its own (or [like]'s), a stall in its owner's colour. */
    private fun fill(
        level: ServerLevel, w: WorksRec, r: BuildRec, capital: CapitalPiece, frame: WorksFrame, y0: Int, variant: Int, planned: Boolean,
        like: BuildRec?, path: IntArray,
    ) {
        r.origin = BlockPos(frame.origin.x, y0, frame.origin.z)
        r.front = frame.front
        r.y0 = y0
        r.variant = variant
        r.path = path
        r.planned = planned
        r.style = like?.style ?: capital.style.ordinal
        r.palette = like?.palette ?: Math.floorMod(w.capital xor (w.id * 31L), 3L).toInt()
        w.owner?.let { r.color = StallDesk.colorOf(level, it) }
    }

    /** [w] sited: its record filled in and kept, and the works told. */
    private fun settle(
        level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece, frame: WorksFrame, y0: Int, variant: Int, planned: Boolean,
        like: BuildRec? = null, path: IntArray = IntArray(0),
    ) {
        fill(level, w, rec, capital, frame, y0, variant, planned, like, path)
        if (like != null) rec.sitedLike(like)
        rec.sited = true
        WorksPlans.forget(rec.id)
        WorksBuildData.get(level).changed()
        Works.sited(level, w.id, siteOf(level, rec))
    }

    /** The site the works is told of: the drawing's heart, its lot, where its crate and post go. */
    fun siteOf(level: ServerLevel, rec: BuildRec): WorksSite {
        val p = WorksPlans.of(rec)
        val d = p.drawing
        val f = p.frame
        val origin = f.world(d.heart.x, d.y0 + d.heart.y, d.heart.z)
        val lot = f.box(d.lot, d.y0 + d.low, d.y0 + d.high)
        val crate = rec.yard.firstOrNull() ?: d.yard.firstOrNull()?.let { f.world(it.x, d.y0 + it.y, it.z) } ?: origin
        val pins = d.posts.map { f.world(it.x, d.y0 + it.y, it.z) }
        val post = rec.post ?: pins.firstOrNull { free(level, it) && free(level, it.above()) } ?: pins.firstOrNull() ?: origin
        return WorksSite(origin, f.front, lot, crate, post, rec.planned)
    }

    /**
     * A site given by `works site <id> here`: the drawing centred on its lot, its front the way it faces, on its
     * ground (an oven given a site away from its bakery is a lean-to).
     */
    private fun adopt(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece, site: WorksSite) {
        val variant = if (w.kind == WorksKind.OVEN) 1 else 0
        val y0 = site.lot.minY()
        val shape = BuildRec(w.id, w.kind, w.capital).also { it.variant = variant; it.y0 = y0 }
        val lot = WorksPlans.drawing(shape).lot
        val cx = (lot.x0 + lot.x1) / 2
        val cz = (lot.z0 + lot.z1) / 2
        val f0 = WorksFrame(BlockPos.ZERO, site.facing)
        val mid = site.lot.center
        val frame = WorksFrame(BlockPos(mid.x - f0.worldX(cx, cz), y0, mid.z - f0.worldZ(cx, cz)), site.facing)
        settle(level, w, rec, capital, frame, y0, variant, site.planned)
        forget(w.id)
    }

    /** [rect] of the city's plan (a building's lot, a works lot) as the frame of a drawing whose own lot is [lot], its front toward [front]. */
    private fun frameOn(capital: CapitalPiece, rect: Rect, front: Dir, lot: Rect): WorksFrame {
        val f = Frame(NullCanvas, rect, front)
        val px = f.x(-lot.x0, -lot.z0)
        val pz = f.z(-lot.x0, -lot.z0)
        return WorksFrame(capital.world(px, 0, pz), capital.direction(front))
    }

    // ---- What's in the way, shown ----

    /** What holds works [id]'s site up, outlined in red dust to those within 32 blocks. */
    private fun outline(level: ServerLevel, id: Int) {
        val pos = inWay[id] ?: return
        if (!level.isLoaded(pos)) return
        val near = level.players().filter { !it.isSpectator && it.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) < 32.0 * 32.0 }
        if (near.isEmpty()) return
        val dust = DustParticleOptions(0xD03020, 1.2f)
        for (i in 0..3) for (k in 0..1) for (j in 0..1) {
            val t = i / 3.0
            for ((x, y, z) in listOf(Triple(t, k.toDouble(), j.toDouble()), Triple(k.toDouble(), t, j.toDouble()), Triple(k.toDouble(), j.toDouble(), t))) {
                for (p in near) level.sendParticles(p, dust, true, false, pos.x + x, pos.y + y, pos.z + z, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
