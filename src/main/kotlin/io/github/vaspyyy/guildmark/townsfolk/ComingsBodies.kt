package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.CountryRoad
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Use
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.Neighbours
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A household's bodies as it comes to a capital or leaves it with a player near enough to see it (living-world/
 * design-influence.md section 6), for [Comings]:
 * - **Coming in** (new, or back after the hard times): set down out of sight on the country road a little way out from
 *   a gate a player is within [SEEN] blocks of, the first grown-up at the shafts of a handcart heaped with the
 *   household's goods ([Handcarts.household]) and the rest alongside, they walk up the road, in at the gate and through
 *   the streets to their door, stopping to look about them (LOOK_ABOUT) once inside the gate and now and then on the
 *   way. At the door the cart is left at its bay, the listeners hear of them, and the neighbours come over
 *   ([Neighbours.at]: a welcome and SHOW_IN, or for those back, a hug, whose household half is played here: [embrace]);
 *   one of them says "So this is the place" ("comings.arrive"; "comings.home" for those back); and they carry their
 *   things in off the cart a piece at a time (CARRY, the children the bundles) and are home. The empty cart goes once
 *   nobody's looking, and their day takes them up.
 * - **Leaving**: home from wherever they are to their door; their cart brought round to its bay while nobody's looking
 *   that way; the household's goods fetched out of the house and packed on it (PACK); the listeners told and the
 *   neighbours seeing them off (a hug, then waving after them); "comings.leave"; and away with the cart along the
 *   streets to the nearest gate and out onto the road, where they're taken away once no one can see them.
 *
 * Each plays once, whoever's watching, and never longer than [MOST] ticks; with nobody near any of them for [ALONE]
 * ticks it's finished out of sight (those coming in are home as their day has them; those leaving are gone). Nothing
 * here is saved: after a restart those who came are simply the town's, and the bodies of those gone are dropped as they
 * load (Residents.onJoin). The way is worked out once, from the city's plan (its streets and gates, and the country
 * road out of the gate), never by looking at the world.
 */
internal object ComingsBodies {
    /** Which way a household is going. */
    enum class Kind { ARRIVE, LEAVE, RETURN }

    /** How near a gate (or a leaving household) a player must be for the coming or going to be played. */
    const val SEEN = 96.0

    /** Nearer than this to a player, nothing appears or goes; and the view's half-width (the cosine) a player is taken to see. */
    private const val CLOSE = 24.0
    private const val VIEW = 0.35

    /** The longest a coming or going plays (ticks), and how long with nobody near before it's finished out of sight. */
    private const val MOST = 7200
    private const val ALONE = 300

    /** Steps along the way between the points walked to. */
    private const val STRIDE = 7

    /** The nearest row of the country road out from the gate a household is set down on, and how much further for the farthest. */
    private const val ROAD_NEAR = 6
    private const val ROAD_FAR = 30

    /** Set down in sight after all, it must be at least this far from every player. */
    private const val FAR_ENOUGH = 48.0

    /** Further behind than this, the one at the cart waits for them. */
    private const val LAG = 8.0

    /** Blocks past the end of the country road a household leaving walks on, straight out; and how many more such legs, watched still. */
    private const val BEYOND = 16
    private const val ON = 6

    /** How long (ticks) the neighbours are given at the door when they've come over; and when nobody has. */
    private const val NEIGHBOURS = 320
    private const val NOBODY = 50

    /** The longest the household waits for nobody to be looking where its cart is to be brought round. */
    private const val CART_WAIT = 600

    private enum class Stage { WAIT, ROAD, LOOK, STREETS, BAY, DOOR, CARRY, GATHER, CART, PACK, FAREWELL, OUT }

    private val parties = LinkedHashMap<Int, Party>()

    /** Residents (by id) whose bodies are in a coming or going now. */
    private val busy = HashMap<Int, Party>()

    /** The bodies of those gone that are still about: taken away as soon as nobody can see them (with the game time they went). */
    private val going = ArrayList<Pair<Townsperson, Long>>()

    /** Whether resident [id] is coming or going just now. */
    fun busy(id: Int): Boolean = busy.containsKey(id)

    /** What's playing just now, in words (for `/guildmark comings`). */
    fun describe(capital: Long): List<String> = parties.values.filter { it.h.capital == capital }.map { p ->
        val what = when (p.kind) {
            Kind.ARRIVE -> "coming in"
            Kind.RETURN -> "coming back"
            Kind.LEAVE -> "leaving"
        }
        "Household #${p.h.id} ($what by the ${p.gate.name} gate): ${p.stage.name.lowercase()}, ${p.crew.size} of them, ${p.age / 20}s in"
    }

    /** The server has stopped: everything here goes (it's never saved). */
    fun forget() {
        parties.clear()
        busy.clear()
        going.clear()
    }

    // ---- Starting ----

    /**
     * [h] (just in by the record, or back) comes in at [gate] (null: whichever a player is within [SEEN] blocks of,
     * nearest their home), its bodies set down after [delay] ticks: true if it's going to be played. False if there's
     * no gate a player is near, nowhere out of sight on its road to set them down, or no way from it to their door.
     */
    fun come(level: ServerLevel, places: CityPlaces, h: Household, kind: Kind, gate: Dir?, delay: Int = 0): Boolean {
        if (parties.containsKey(h.id)) return true
        val home = places.lodgings[h.home] ?: return false
        val door = home.front ?: return false
        val plan = places.census.plan
        val gates = if (gate != null) listOf(gate) else watched(level, places, door.pos)
        for (g in gates) {
            val road = plan.outskirts.roads.firstOrNull { it.side == g } ?: continue
            val start = setDown(level, places, road) ?: continue
            val inward = streets(places, plan.sidePos(g, 0, plan.outer + CityPlan.GATE_OUT + 1), door, toDoor = true) ?: continue
            // Up the road from where they're set down to the gate, then in along the streets
            val way = ArrayList<BlockPos>()
            var i = start.second - STRIDE
            while (i > 0) {
                way += row(level, places, road, i)
                i -= STRIDE
            }
            way += row(level, places, road, 0)
            way += inward
            val gateAt = way.indexOfFirst { p -> places.capital.local(p.x, p.z).let { (x, z) -> plan.within(x, z) } }.takeIf { it >= 0 } ?: (way.size - 1)
            val party = Party(level, h, kind, places, home, door, g, way, gateAt, level.gameTime + delay, start.first)
            parties[h.id] = party
            for (id in h.members) busy[id] = party
            return true
        }
        return false
    }

    /**
     * [h] (just gone by the record) leaves with [bodies], those of its people about: true if it's going to be played,
     * false if there's no door to go from or no way out to a gate (they're simply gone).
     */
    fun go(level: ServerLevel, places: CityPlaces, h: Household, bodies: List<Townsperson>): Boolean {
        if (parties.containsKey(h.id)) return true
        val home = places.lodgings[h.home] ?: return false
        val door = home.front ?: return false
        val plan = places.census.plan
        val gates = plan.outerGates.filter { g -> plan.outskirts.roads.any { it.side == g } }
            .sortedBy { g -> front(places, g).distSqr(door.pos) }
        for (g in gates) {
            val road = plan.outskirts.roads.first { it.side == g }
            val outward = streets(places, plan.sidePos(g, 0, plan.outer + CityPlan.GATE_OUT + 1), door, toDoor = false) ?: continue
            val way = ArrayList<BlockPos>(outward)
            var i = 0
            while (i < road.rows) {
                way += row(level, places, road, i)
                i += STRIDE
            }
            way += row(level, places, road, road.rows - 1)
            // On straight out past the farms
            val exit = road.at(road.rows - 1, 0)
            for (k in intArrayOf(BEYOND / 2, BEYOND)) way += ground(level, places, exit.x + g.dx * k, road.heights[road.rows - 1], exit.z + g.dz * k)
            val gateAt = way.indexOfFirst { p -> places.capital.local(p.x, p.z).let { (x, z) -> !plan.within(x, z) } }.takeIf { it >= 0 } ?: (way.size - 1)
            val party = Party(level, h, Kind.LEAVE, places, home, door, g, way, gateAt, level.gameTime, null)
            for (p in bodies.sortedWith(compareBy({ if (it.role.adult) 0 else 1 }, { it.resident }))) {
                if (!p.isAlive || p.isRemoved) continue
                take(p)
                party.crew += Hand(party, p, p.resident)
            }
            if (party.crew.isEmpty()) return false
            parties[h.id] = party
            for (id in h.members) busy[id] = party
            enter(party, Stage.GATHER)
            return true
        }
        return false
    }

    /** [p] in our charge from now: up, standing, and out of anything a scene had them at. */
    private fun take(p: Townsperson) {
        if (p.isSleeping) p.stopSleeping()
        if (p.seat != null) p.standUp()
        (p.director as? Aside)?.end(p, drop = true)
        p.routine.interrupt()
        p.navigation.stop()
        p.carried = ItemStack.EMPTY
    }

    /** The gates (with a road) a player is within [SEEN] blocks of the front of, nearest [home] first. */
    private fun watched(level: ServerLevel, places: CityPlaces, home: BlockPos): List<Dir> {
        val plan = places.census.plan
        return plan.outerGates.filter { g -> plan.outskirts.roads.any { it.side == g } }
            .filter { g -> front(places, g).let { f -> level.getNearestPlayer(f.x + 0.5, f.y + 0.5, f.z + 0.5, SEEN, false) != null } }
            .sortedBy { front(places, it).distSqr(home) }
    }

    /** Just outside the gatehouse on [gate]'s side, on the road. */
    private fun front(places: CityPlaces, gate: Dir): BlockPos {
        val plan = places.census.plan
        val p = plan.sidePos(gate, 0, plan.outer + CityPlan.GATE_OUT + 1)
        return places.capital.world(p.x, plan.level(p.x, p.z), p.z)
    }

    /**
     * Where on [road] to set a household down to walk in: the nearest row from [ROAD_NEAR] out that's loaded, ticking
     * and out of everyone's sight; failing that, the farthest at least [FAR_ENOUGH] from every player. With its row.
     */
    private fun setDown(level: ServerLevel, places: CityPlaces, road: CountryRoad): Pair<BlockPos, Int>? {
        val last = minOf(road.rows - 1, ROAD_NEAR + ROAD_FAR)
        if (last < ROAD_NEAR) return null
        var far: Pair<BlockPos, Int>? = null
        for (i in ROAD_NEAR..last) {
            val at = stand(level, row(level, places, road, i)) ?: continue
            if (!level.isPositionEntityTicking(at)) continue
            if (hidden(level, at)) return at to i
            if (level.players().none { it.distanceToSqr(Vec3.atCenterOf(at)) < FAR_ENOUGH * FAR_ENOUGH }) far = at to i
        }
        return far
    }

    /** The middle of [road]'s row [i] (its first is at the foot of the glacis), at its standing height. */
    private fun row(level: ServerLevel, places: CityPlaces, road: CountryRoad, i: Int): BlockPos {
        val p = road.at(i, 0)
        val at = places.capital.world(p.x, road.heights[i], p.z)
        return stand(level, at) ?: at
    }

    /** The ground at [x], [z] of the city's frame: the land's own height there if it's loaded, else [y] over the city's. */
    private fun ground(level: ServerLevel, places: CityPlaces, x: Int, y: Int, z: Int): BlockPos {
        val at = places.capital.world(x, y, z)
        if (!level.hasChunk(at.x shr 4, at.z shr 4)) return at
        return BlockPos(at.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.x, at.z), at.z)
    }

    /** A block to stand on at [at], or a step above or below it; null if there's none (or its land isn't loaded). */
    private fun stand(level: ServerLevel, at: BlockPos): BlockPos? {
        if (!level.hasChunk(at.x shr 4, at.z shr 4)) return null
        for (dy in intArrayOf(0, 1, -1, 2, -2)) {
            val pos = at.above(dy)
            if (Places.standable(level, pos)) return pos
        }
        return null
    }

    /**
     * The way along the streets between the gate's front at [gate] (the city's frame) and the street before [door],
     * from the gate if [toDoor], else from the door: points [STRIDE] steps apart, in the world. Null if there's none.
     */
    private fun streets(places: CityPlaces, gate: CityPlan.Pos, door: CityPlaces.Door, toDoor: Boolean): List<BlockPos>? {
        val plan = places.census.plan
        val (dx, dz) = places.capital.local(door.outside.x, door.outside.z)
        // The street cells nearest the door
        val near = HashSet<Long>()
        for (r in 0..6) {
            for (x in dx - r..dx + r) for (z in dz - r..dz + r) {
                if (maxOf(abs(x - dx), abs(z - dz)) != r) continue
                if (walkable(plan, x, z)) near.add(key(x, z))
            }
            if (near.isNotEmpty() && r >= 1) break
        }
        if (near.isEmpty()) return null
        val path = if (toDoor) route(plan, gate, near) else {
            // From the street cell nearest the door
            val first = near.minBy { k -> val x = (k shr 32).toInt(); val z = k.toInt(); (x - dx) * (x - dx) + (z - dz) * (z - dz) }
            route(plan, CityPlan.Pos((first shr 32).toInt(), first.toInt()), hashSetOf(key(gate.x, gate.z)))
        } ?: return null
        val points = ArrayList<BlockPos>()
        for ((i, p) in path.withIndex()) {
            val last = i == path.size - 1
            if (i % STRIDE != 0 && !last) continue
            points += places.capital.world(p.x, plan.level(p.x, p.z), p.z)
        }
        return points
    }

    /** The shortest walk in [plan] from [start] to the nearest of [goals] along the streets and through the gates, step by step. */
    private fun route(plan: CityPlan, start: CityPlan.Pos, goals: Set<Long>): List<CityPlan.Pos>? {
        val grid = plan.grid
        val r = grid.reach
        val size = 2 * r + 1
        if (!grid.inside(start.x, start.z) || goals.isEmpty()) return null
        val from = IntArray(size * size) { -1 }
        val queue = IntArray(size * size)
        var head = 0
        var tail = 0
        val s = (start.x + r) * size + (start.z + r)
        from[s] = s
        queue[tail++] = s
        while (head < tail) {
            val i = queue[head++]
            val x = i / size - r
            val z = i % size - r
            if (key(x, z) in goals) {
                val path = ArrayList<CityPlan.Pos>()
                var k = i
                while (true) {
                    path.add(CityPlan.Pos(k / size - r, k % size - r))
                    val back = from[k]
                    if (back == k) break
                    k = back
                }
                path.reverse()
                return path
            }
            for (d in Dir.entries) {
                val nx = x + d.dx
                val nz = z + d.dz
                if (!walkable(plan, nx, nz)) continue
                val n = (nx + r) * size + (nz + r)
                if (from[n] >= 0) continue
                // Never more than a step up or down at a time
                if (abs(grid.height(nx, nz) - grid.height(x, z)) > 1) continue
                from[n] = i
                queue[tail++] = n
            }
        }
        return null
    }

    /** Along the streets, and through a gate's passage but never round the side of its towers (as the plan's own way out). */
    private fun walkable(plan: CityPlan, x: Int, z: Int): Boolean {
        if (!plan.grid.inside(x, z)) return false
        val use = plan.grid[x, z]
        return use.street || (use == Use.GATE && (abs(x) <= 2 || abs(z) <= 2))
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** Whether nobody could see [at] now: no player within [CLOSE] blocks, nor one within [SEEN] looking its way with a clear line to it. */
    private fun hidden(level: ServerLevel, at: Vec3): Boolean {
        for (player in level.players()) {
            val d = player.distanceToSqr(at)
            if (d < CLOSE * CLOSE) return false
            if (d > SEEN * SEEN) continue
            val eye = player.eyePosition
            val to = at.subtract(eye).normalize()
            if (player.getViewVector(1f).dot(to) < VIEW) continue
            if (level.clip(ClipContext(eye, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS) return false
        }
        return true
    }

    private fun hidden(level: ServerLevel, at: BlockPos): Boolean = hidden(level, Vec3.atCenterOf(at).add(0.0, 0.5, 0.0))

    private fun hidden(p: Townsperson): Boolean = hidden(p.level() as ServerLevel, p.position().add(0.0, 1.0, 0.0))

    // ---- The household's half of a hug ----

    /** [member] hugs [neighbour] back, if they're one of a household at its door in our charge: true if it's played. */
    fun embrace(level: ServerLevel, member: Townsperson, neighbour: Townsperson): Boolean {
        val party = busy[member.resident] ?: return false
        if (party.level !== level || party.stage != Stage.DOOR && party.stage != Stage.FAREWELL) return false
        val hand = party.crew.firstOrNull { it.p === member } ?: return false
        if (hand.hugBy != null) return false
        hand.hugBy = neighbour
        return true
    }

    // ---- Each tick ----

    /**
     * Each server tick: every coming and going a step on. With comings and goings off ([on] false) each is finished at
     * once, and those leaving (or gone and still about) are let go to their day instead: the households away are home
     * again (see Comings.restore).
     */
    fun tick(level: ServerLevel, on: Boolean) {
        if (going.isNotEmpty() && !on) {
            for ((p, _) in going) if (!p.isRemoved && p.isAlive) (p.director as? Hand)?.let { release(it) }
            going.clear()
        }
        if (going.isNotEmpty() && level.gameTime % 20 == 3L) {
            going.removeIf { (p, since) ->
                val gone = p.isRemoved || !p.isAlive || hidden(p) || level.gameTime - since > MOST
                if (gone && !p.isRemoved) p.discard()
                gone
            }
        }
        if (parties.isEmpty()) return
        for (party in parties.values.toList()) {
            if (party.level !== level) continue
            if (!on) finish(party, off = true) else step(party)
        }
    }

    private fun step(party: Party) {
        val level = party.level
        party.age++
        party.t++
        if (party.stage == Stage.WAIT) {
            if (level.gameTime < party.start) return
            if (!setOff(party)) return finish(party)
            enter(party, Stage.ROAD)
            return
        }
        // Those whose bodies have gone are out of it
        party.crew.removeIf { h -> !h.p.isAlive || h.p.isRemoved }
        if (party.crew.isEmpty()) return finish(party)
        // Back in our charge after a word with a player (or anything else that had them meanwhile)
        for (h in party.crew) if (h.p.director == null) h.p.director = h
        if (party.age % 20 == 0) {
            val near = party.crew.any { level.getNearestPlayer(it.p, SEEN) != null }
            party.alone = if (near) 0 else party.alone + 20
        }
        if (party.alone >= ALONE || party.age > MOST) return finish(party)
        val puller = party.puller ?: return finish(party)
        when (party.stage) {
            Stage.ROAD, Stage.STREETS -> if (puller.idle) {
                if (puller.lost && party.tries++ < 3) {
                    puller.lost = false
                    puller.set(Lead(if (party.stage == Stage.ROAD) party.gateAt + 1 else party.way.size))
                } else if (puller.lost) return finish(party)
                else enter(party, if (party.stage == Stage.ROAD) Stage.LOOK else Stage.BAY)
            }
            Stage.LOOK -> if (party.t >= LOOK_TICKS) enter(party, Stage.STREETS)
            Stage.BAY -> {
                if (!party.parked && puller.idle) {
                    Handcarts.unhitch(puller.p)
                    Handcarts.headFor(puller.p, null)
                    party.parked = true
                    puller.set(Stand(-1, { street(party) }))
                }
                if (party.parked && party.crew.all { it.waiting } || party.t > BAY_TICKS) enter(party, Stage.DOOR)
            }
            Stage.DOOR -> {
                if (!party.said && party.t % 30 == 10 && party.t < 300) {
                    party.said = say(party, if (party.kind == Kind.RETURN) "comings.home" else "comings.arrive", "come to their new door")
                }
                if (party.t >= (if (party.came) NEIGHBOURS else NOBODY) && party.crew.none { it.hugBy != null }) enter(party, Stage.CARRY)
            }
            Stage.CARRY -> {
                for ((k, h) in party.crew.withIndex()) if (h.idle) unload(party, h, k)
                if (party.crew.all { it.home } || party.t > WORK_TICKS) finish(party)
            }
            Stage.GATHER -> {
                if (party.crew.all { it.waiting } || party.t > GATHER_TICKS) {
                    // Whoever couldn't get home in time goes from wherever they are
                    for (h in party.crew.filter { !it.waiting }) drop(party, h)
                    if (party.crew.isEmpty()) return finish(party)
                    enter(party, Stage.CART)
                }
            }
            Stage.CART -> {
                val bay = party.bay
                if (bay == null || !Handcarts.enabled()) enter(party, Stage.PACK)
                else if (party.t % 10 == 0 && hidden(level, bay) || party.t > CART_WAIT) {
                    // Brought round while nobody was looking: behind them as they face the door, so it stands clear of it
                    puller.face(Vec3.atCenterOf(party.door.pos))
                    if (Handcarts.hitch(puller.p) != null) {
                        Handcarts.unhitch(puller.p)
                        Handcarts.load(puller.p, listOf())
                        party.cart = true
                    }
                    enter(party, Stage.PACK)
                }
            }
            Stage.PACK -> {
                for ((k, h) in party.crew.withIndex()) if (h.idle) pack(party, h, k)
                if (party.load.isEmpty() && party.crew.all { it.waiting } || party.t > WORK_TICKS) enter(party, Stage.FAREWELL)
            }
            Stage.FAREWELL -> {
                if (!party.said && party.t % 30 == 10 && party.t < 300) party.said = say(party, "comings.leave", "leaving in hard times")
                if (party.t >= (if (party.came) NEIGHBOURS else NOBODY) && party.crew.none { it.hugBy != null }) enter(party, Stage.OUT)
            }
            Stage.OUT -> {
                if (puller.idle && puller.lost && party.wp < party.way.size && party.tries++ < 3) {
                    puller.lost = false
                    puller.set(Lead(party.way.size))
                } else if (puller.idle && !puller.lost && party.wp >= party.way.size && party.on++ < ON) {
                    // Past the end of the road and watched still: on away from the town, the rest after them
                    puller.set(Go({ on(party, it) }, Jobs.STROLL, 1.8, overlay = Handcarts.PULL))
                } else if (puller.idle) {
                    puller.set(Stand(-1, { if (party.wp >= party.way.size) Vec3.atCenterOf(on(party, it)).add(0.0, 1.2, 0.0) else ahead(party) }))
                }
                // Taken away once they're out of the gate and nobody can see them
                if (party.age % 10 == 0 && party.wp >= party.gateAt && party.crew.all { hidden(it.p) }) finish(party)
            }
            Stage.WAIT -> {}
        }
    }

    /** [party] on to [stage]: what each of them does in it. */
    private fun enter(party: Party, stage: Stage) {
        party.stage = stage
        party.t = 0
        val puller = party.puller ?: return
        val rest = party.crew.filter { it !== puller }
        when (stage) {
            Stage.ROAD -> {
                puller.set(Lead(party.gateAt + 1))
                rest.forEachIndexed { k, h -> h.set(Trail(k, about = true)) }
            }
            Stage.LOOK -> {
                // In at the gate: they stop and take the place in
                puller.set(Stand(LOOK_TICKS, { ahead(party) }))
                rest.forEach { h -> h.set(Play(Activity.LOOK_ABOUT, { ahead(party) }), Stand(LOOK_TICKS, { ahead(party) })) }
            }
            Stage.STREETS -> {
                puller.set(Lead(party.way.size))
                rest.forEachIndexed { k, h -> h.set(Trail(k, about = true)) }
            }
            Stage.BAY -> {
                party.bay = Handcarts.bay(party.level, party.door, puller.p)
                val bay = party.bay
                if (bay != null && Handcarts.pulling(puller.p)) {
                    Handcarts.headFor(puller.p, bay)
                    puller.set(Go({ bay }, Jobs.STROLL, 0.8, Handcarts.PULL))
                } else puller.set(Go({ spot(party, 0) }, Jobs.WALK, 0.9))
                rest.forEachIndexed { k, h -> h.set(Go({ spot(party, k + 1) }, Jobs.WALK, 0.9), Stand(-1, { street(party) })) }
            }
            Stage.DOOR -> {
                tell(party)
                party.came = Neighbours.at(party.level, party.h, neighbours(party.kind), party.crew.map { it.p })
                for (h in party.crew) h.set(Stand(-1, { street(party) }, about = party.kind == Kind.ARRIVE))
            }
            Stage.CARRY -> {
                party.said = false
                for (h in party.crew) h.set()
            }
            Stage.GATHER -> {
                party.bay = Handcarts.bay(party.level, party.door, puller.p)
                puller.set(Go({ party.bay ?: spot(party, 0) }, Jobs.WALK, 0.8), Stand(-1, { Vec3.atCenterOf(party.door.pos) }))
                rest.forEachIndexed { k, h -> h.set(Go({ spot(party, k + 1) }, Jobs.WALK, 0.9), Stand(-1, { Vec3.atCenterOf(party.door.pos) })) }
            }
            Stage.CART -> {}
            Stage.PACK -> {
                party.load.clear()
                party.load.addAll(Handcarts.household(party.h.members.size))
                party.heaped.clear()
                for (h in party.crew) h.set()
            }
            Stage.FAREWELL -> {
                party.said = false
                tell(party)
                party.came = Neighbours.at(party.level, party.h, Neighbours.Kind.LEFT, party.crew.map { it.p })
                for (h in party.crew) if (!h.waiting) h.set(Stand(-1, { street(party) }))
            }
            Stage.OUT -> {
                party.tries = 0
                party.wp = 0
                if (party.cart && Handcarts.parked(puller.p) != null) {
                    puller.set(
                        Go({ Handcarts.fetch(it.p) }, Jobs.WALK, 0.45, exact = true),
                        Do { Handcarts.hitch(it.p) },
                        Lead(party.way.size),
                    )
                } else puller.set(Lead(party.way.size))
                rest.forEachIndexed { k, h ->
                    // A wave back to the neighbours seeing them off, from those with their hands free, and after the cart
                    if (h.p.carried.isEmpty) h.set(Play(Activity.WAVE, { street(party) }), Trail(k, about = false)) else h.set(Trail(k, about = false))
                }
            }
            Stage.WAIT -> {}
        }
    }

    /**
     * [party]'s bodies set down on the road: the first grown-up at the front with the cart behind them heaped with the
     * household's goods (or, with no cart, a piece in each of their arms), the rest a little further back. False if
     * none of them could be.
     */
    private fun setOff(party: Party): Boolean {
        val level = party.level
        val spot = party.spot ?: return false
        if (!level.isPositionEntityTicking(spot) || !hidden(level, spot) && level.players().any { it.distanceToSqr(Vec3.atCenterOf(spot)) < FAR_ENOUGH * FAR_ENOUGH }) {
            // Somebody's come to stand where they'd be set down meanwhile: they're simply in, as the record has it
            return false
        }
        val residents = Residents.get(level)
        val toward = party.way.firstOrNull() ?: return false
        val yaw = yaw(spot, toward)
        // Behind the first, further out along the road
        val back = Vec3.atCenterOf(spot).subtract(Vec3.atCenterOf(toward)).normalize()
        val members = party.h.members.mapNotNull { residents[it] }.filter { it.alive }
            .sortedWith(compareBy({ if (it.role.adult) 0 else 1 }, { it.id }))
        val later = ArrayList<Int>()
        for ((k, r) in members.withIndex()) {
            // Someone whose body is about already is in as they are
            if (Comings.body(level, r) != null) continue
            val off = if (k == 0) Vec3.ZERO else Vec3(back.x * (2.5 + 1.4 * ((k + 1) / 2)) + back.z * (if (k % 2 == 0) 1.0 else -1.0),
                0.0, back.z * (2.5 + 1.4 * ((k + 1) / 2)) - back.x * (if (k % 2 == 0) 1.0 else -1.0))
            val at = stand(level, BlockPos.containing(Vec3.atBottomCenterOf(spot).add(off))) ?: spot
            val p = Comings.body(level, party.places, r, at, yaw)
            if (p == null) {
                later += r.id
                continue
            }
            p.director = null
            take(p)
            party.crew += Hand(party, p, r.id)
        }
        if (later.isNotEmpty()) Comings.later(level, party.h.capital, later)
        if (party.crew.isEmpty()) return false
        for (h in party.crew) h.p.director = h
        val puller = party.puller ?: return false
        val goods = Handcarts.household(party.h.members.size)
        if (Handcarts.hitch(puller.p) != null) {
            party.cart = true
            Handcarts.load(puller.p, goods)
            party.heaped.addAll(goods)
        } else {
            // No cart: what they can carry in their arms, a piece each
            val pieces = ArrayDeque(goods)
            for (h in party.crew) {
                val piece = (if (h.adult) pieces.removeFirstOrNull() else pieces.firstOrNull { it.`is`(Items.BUNDLE) }?.also { pieces.remove(it) }) ?: continue
                h.p.carried = piece
            }
        }
        return true
    }

    /** [h] (the [k]th of them) at the door with things still on the cart: off with the next for them, and in with it. */
    private fun unload(party: Party, h: Hand, k: Int) {
        val puller = party.puller ?: return
        if (h.home) return
        if (!party.cart || Handcarts.parked(puller.p) == null) {
            // Whatever's in their arms goes in, and so do they
            h.set(
                Go({ party.door.inside }, Jobs.WALK, 0.6, Activity.CARRY),
                Go({ room(party, k) }, Jobs.WALK, 0.8, Activity.CARRY),
                Play(Activity.PUT_DOWN, { null }) { it.p.carried = ItemStack.EMPTY },
                Do { it.home = true },
                Stand(-1, { null }),
            )
            return
        }
        val next = next(party.heaped, h.adult)
        if (next == null) {
            h.set(Go({ party.door.inside }, Jobs.WALK, 0.6), Go({ room(party, k) }, Jobs.WALK, 0.8), Do { it.home = true }, Stand(-1, { null }))
            return
        }
        val door = Vec3.atCenterOf(party.door.pos)
        h.set(
            Go({ Handcarts.side(puller.p, door) ?: party.bay ?: spot(party, k) }, Jobs.WALK, 0.7),
            Play(Activity.PICK_UP, { Handcarts.bed(puller.p) }) {
                // Taken off the cart now: whatever suits them that's still there
                val piece = next(party.heaped, h.adult)
                if (piece != null) {
                    party.heaped.remove(piece)
                    Handcarts.load(puller.p, party.heaped)
                    it.p.carried = piece
                }
            },
            Go({ party.door.inside }, Jobs.WALK, 0.6, Activity.CARRY),
            Go({ room(party, k) }, Jobs.WALK, 0.8, Activity.CARRY),
            Play(Activity.PUT_DOWN, { null }) { it.p.carried = ItemStack.EMPTY },
            Go({ spot(party, k) }, Jobs.WALK, 0.9),
        )
    }

    /** [h] (the [k]th of them) at the door with things still to pack: into the house for the next, and out to the cart with it. */
    private fun pack(party: Party, h: Hand, k: Int) {
        val puller = party.puller ?: return
        if (h.waiting) return
        if (next(party.load, h.adult) == null) {
            h.set(Go({ spot(party, k) }, Jobs.WALK, 0.9), Stand(-1, { street(party) }))
            return
        }
        val door = Vec3.atCenterOf(party.door.pos)
        val cart = party.cart && Handcarts.parked(puller.p) != null
        val fetch = Do {
            val piece = next(party.load, it.adult)
            if (piece != null) {
                party.load.remove(piece)
                it.p.carried = piece
            }
        }
        if (!cart) {
            // No cart: a piece in each of their arms to take with them
            h.set(
                Go({ party.door.inside }, Jobs.WALK, 0.6), Go({ room(party, k) }, Jobs.WALK, 0.8), Stand(20 + k * 7, { null }), fetch,
                Go({ spot(party, k) }, Jobs.WALK, 0.9, Activity.CARRY), Stand(-1, { street(party) }),
            )
            return
        }
        h.set(
            Go({ party.door.inside }, Jobs.WALK, 0.6),
            Go({ room(party, k) }, Jobs.WALK, 0.8),
            Stand(20 + k * 7, { null }),
            fetch,
            Go({ Handcarts.side(puller.p, door) ?: party.bay ?: spot(party, k) }, Jobs.WALK, 0.7, Activity.CARRY),
            Play(Activity.PACK, { Handcarts.bed(puller.p) }) {
                val piece = it.p.carried
                if (!piece.isEmpty) {
                    party.heaped += piece
                    Handcarts.load(puller.p, party.heaped)
                    it.p.carried = ItemStack.EMPTY
                }
            },
        )
    }

    /** The next piece of [goods] for someone to carry: a grown-up takes the last heaped on; a child only a bundle. */
    private fun next(goods: List<ItemStack>, adult: Boolean): ItemStack? =
        if (adult) goods.lastOrNull() else goods.lastOrNull { it.`is`(Items.BUNDLE) }

    /** The listeners hear of [party]'s household (once). */
    private fun tell(party: Party) {
        if (party.told) return
        party.told = true
        Comings.tell(party.level, party.h, party.kind)
    }

    private fun neighbours(kind: Kind): Neighbours.Kind = when (kind) {
        Kind.ARRIVE -> Neighbours.Kind.ARRIVED
        Kind.RETURN -> Neighbours.Kind.RETURNED
        Kind.LEAVE -> Neighbours.Kind.LEFT
    }

    /** One of [party]'s grown-ups says a take of [family] to whoever's in hearing: true if it was said. */
    private fun say(party: Party, family: String, why: String): Boolean {
        val speaker = party.crew.filter { it.adult && it.hugBy == null }.minByOrNull { if (it === party.puller && party.crew.size > 1) 1 else 0 } ?: return false
        return Words.say(speaker.p, family, why)
    }

    /**
     * [h] can't get home in time to leave with the rest: they go from wherever they are, once no one can see them
     * (still in our charge meanwhile, so their day doesn't take them up again).
     */
    private fun drop(party: Party, h: Hand) {
        party.crew.remove(h)
        going += h.p to party.level.gameTime
    }

    /**
     * [party] is over, played or not: the listeners told if they weren't, and the bodies home (coming) or gone (leaving).
     * [off]: comings and goings were switched off, and those leaving stay (they're let go to their day, untold of).
     */
    private fun finish(party: Party, off: Boolean = false) {
        parties.remove(party.h.id)
        for (id in party.h.members) if (busy[id] === party) busy.remove(id)
        val puller = party.puller
        if (party.kind == Kind.LEAVE && !off) {
            tell(party)
            // Gone: at once where nobody can see, else as soon as nobody can (walking on as they were meanwhile)
            for (h in party.crew) if (hidden(h.p)) h.p.discard() else going += h.p to party.level.gameTime
            return
        }
        if (party.kind != Kind.LEAVE) tell(party)
        if (party.stage == Stage.WAIT) {
            // Never set down: their bodies are made at home, out of sight, when a player next comes
            Comings.later(party.level, party.h.capital, party.h.members)
            return
        }
        puller?.let { Handcarts.headFor(it.p, null) }
        puller?.let { Handcarts.leave(it.p) }
        // Still on their way when it ended: whoever nobody can see is home at once (made there out of sight, as for
        // those come in with nobody near), the rest go on home as their day has them
        val onTheWay = party.kind != Kind.LEAVE && party.stage in ON_THE_WAY
        val home = ArrayList<Int>()
        for (h in party.crew) {
            release(h)
            // Whatever's still in their arms is set down at home
            h.p.carried = ItemStack.EMPTY
            if (onTheWay && hidden(h.p)) {
                h.p.discard()
                home += h.id
            }
        }
        if (home.isNotEmpty()) Comings.later(party.level, party.h.capital, home)
    }

    private val ON_THE_WAY = setOf(Stage.ROAD, Stage.LOOK, Stage.STREETS)

    /** [h] out of our charge, to their day. */
    private fun release(h: Hand) {
        val p = h.p
        if (p.director === h) p.director = null
        p.navigation.stop()
        if (!p.activity.overlay || p.activity == Activity.CARRY || p.activity == Handcarts.PULL) p.act(Activity.NONE)
        p.routine.interrupt()
    }

    // ---- Where ----

    /** The street before [party]'s door, a few blocks out: what they look at, waiting there. */
    private fun street(party: Party): Vec3 = Vec3.atCenterOf(party.door.outside.relative(party.door.inward.opposite, 5)).add(0.0, 1.0, 0.0)

    /** [BEYOND] blocks on from where [h] is, away from the town out of [party]'s gate, on the ground. */
    private fun on(party: Party, h: Hand): BlockPos {
        val (x, z) = party.places.capital.local(h.p.blockX, h.p.blockZ)
        val y = h.p.blockY - party.places.capital.center.y
        return ground(party.level, party.places, x + party.gate.dx * BEYOND, y, z + party.gate.dz * BEYOND)
    }

    /** The next point of the way, a little up: where they look as they stop on it. */
    private fun ahead(party: Party): Vec3 {
        val p = party.way.getOrNull(minOf(party.wp + 1, party.way.size - 1)) ?: party.door.pos
        return Vec3.atCenterOf(p).add(0.0, 1.2, 0.0)
    }

    /** Where the [k]th of [party] stands outside the door: a step or two out, side by side. */
    private fun spot(party: Party, k: Int): BlockPos {
        val door = party.door
        val out = door.inward.opposite
        val along = door.inward.clockWise
        val base = door.outside.relative(out)
        val tries = when (k % 4) {
            0 -> listOf(base, base.relative(out))
            1 -> listOf(base.relative(along.opposite), base.relative(out).relative(along.opposite))
            2 -> listOf(base.relative(along, 2), base.relative(out).relative(along, 2))
            else -> listOf(base.relative(out), base.relative(out, 2))
        }
        for (t in tries) stand(party.level, t)?.let { return it }
        return door.outside
    }

    /** A spot just inside [party]'s home on its ground floor for the [k]th of them (to set a piece down at, or fetch one from). */
    private fun room(party: Party, k: Int): BlockPos {
        val door = party.door
        val spots = party.room ?: run {
            val found = ArrayList<BlockPos>()
            val side = door.inward.clockWise
            for (a in 1..3) for (b in intArrayOf(0, 1, -1, 2, -2)) {
                val pos = door.inside.relative(door.inward, a).relative(side, b)
                if (!party.home.contains(pos)) continue
                stand(party.level, pos)?.takeIf { abs(it.y - door.inside.y) <= 1 && party.home.contains(it) }?.let { found += it }
            }
            found.ifEmpty { listOf(door.inside) }.also { party.room = it }
        }
        return spots[Math.floorMod(k * 3 + party.h.id, spots.size)]
    }

    private fun yaw(from: BlockPos, to: BlockPos): Float =
        (Math.toDegrees(Math.atan2((to.z - from.z).toDouble(), (to.x - from.x).toDouble())) - 90.0).toFloat()

    private const val LOOK_TICKS = 90
    private const val BAY_TICKS = 400
    private const val GATHER_TICKS = 1200
    private const val WORK_TICKS = 2400

    // ---- A household on the move, and each of them ----

    private class Party(
        val level: ServerLevel,
        val h: Household,
        val kind: Kind,
        val places: CityPlaces,
        val home: CityPlaces.Lodging,
        val door: CityPlaces.Door,
        val gate: Dir,
        /** Coming in, from the road up to the street before the door; leaving, from that street out along the road. */
        val way: List<BlockPos>,
        /** The first point of [way] inside the walls (coming) or outside them (leaving). */
        val gateAt: Int,
        /** The game time their bodies are set down at (coming). */
        val start: Long,
        /** Where they're set down (coming). */
        val spot: BlockPos?,
    ) {
        val crew = ArrayList<Hand>()
        var stage = Stage.WAIT
        var t = 0
        var age = 0
        var alone = 0
        var told = false
        var said = false
        var came = false
        var cart = false
        var parked = false
        var tries = 0

        /** Legs walked on past the end of the way, leaving. */
        var on = 0

        /** The point of [way] the one at the head is making for. */
        var wp = 0

        /** Where the cart stands by the door. */
        var bay: BlockPos? = null

        /** Leaving: what's still to be packed. */
        val load = ArrayList<ItemStack>()

        /** What's on the cart now. */
        val heaped = ArrayList<ItemStack>()

        var room: List<BlockPos>? = null

        /** The one at the cart's shafts (or at the head): the first grown-up. */
        val puller: Hand? get() = crew.firstOrNull { it.p.role.adult } ?: crew.firstOrNull()
    }

    /** One of a household on the move, in our charge: their [Act]s in turn. */
    private class Hand(val party: Party, val p: Townsperson, val id: Int) : Director {
        val adult: Boolean get() = p.role.adult
        val walker = Walker(p)
        private val queue = ArrayDeque<Act>()
        private var act: Act? = null
        private var started = false

        /** A neighbour hugging them just now (see [embrace]), and how far into it they are. */
        var hugBy: Townsperson? = null
        private var hugT = 0

        /** A player who spoke to them, and how long they've been nodding to them. */
        private var nodTo: ServerPlayer? = null
        private var nodT = 0

        /** Their last walk couldn't be walked. */
        var lost = false

        /** In their new home, done. */
        var home = false

        /** Nothing more to do. */
        val idle: Boolean get() = act == null && queue.isEmpty()

        /** Standing where they're to wait, until the next stage. */
        val waiting: Boolean get() = queue.isEmpty() && act?.forever == true

        /** From now on, [acts] in turn (nothing, with none). */
        fun set(vararg acts: Act) {
            act?.stop(this)
            act = null
            started = false
            queue.clear()
            queue.addAll(acts)
        }

        override fun tick(p: Townsperson) {
            val by = hugBy
            if (by != null) {
                if (hugT == 0) {
                    p.navigation.stop()
                    p.act(Activity.EMBRACE)
                }
                if (by.isAlive) {
                    p.lookAt(by.eyePosition)
                    p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(by.position()), 18f))
                }
                if (++hugT >= Activity.EMBRACE.ticks + 4) {
                    hugBy = null
                    hugT = 0
                    act?.start(this)
                }
                return
            }
            val player = nodTo
            if (player != null) {
                if (nodT == 0) {
                    p.navigation.stop()
                    p.act(Activity.NOD)
                }
                p.lookAt(player.eyePosition)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(player.position()), 18f))
                if (++nodT >= Activity.NOD.ticks + 10) {
                    nodTo = null
                    nodT = 0
                    act?.start(this)
                }
                return
            }
            var guard = 0
            while (guard++ < 4) {
                val current = act ?: queue.removeFirstOrNull()?.also {
                    act = it
                    started = false
                } ?: return
                if (!started) {
                    started = true
                    current.start(this)
                }
                if (current.tick(this)) return
                current.stop(this)
                act = null
            }
        }

        /** Busy moving house: a nod to whoever speaks to them, and on with it. */
        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            if (hugBy == null && nodTo == null) {
                nodTo = player
                nodT = 0
            }
            return true
        }

        fun face(at: Vec3) {
            val yaw = p.yawTo(at)
            p.face(yaw)
            p.yBodyRot = yaw
            p.yHeadRot = yaw
        }
    }

    /** One thing one of them does. */
    private abstract class Act {
        /** Standing until the stage moves on. */
        open val forever: Boolean get() = false

        open fun start(h: Hand) {}

        /** One tick of it; false once it's done. */
        abstract fun tick(h: Hand): Boolean

        open fun stop(h: Hand) {}

        /** Keeps [want] playing (a carry, the shafts in hand) once a one-shot's done. */
        protected fun keep(h: Hand, want: Activity) {
            if (h.p.activity != want && (h.p.activity.loop || h.p.actDone)) h.p.act(want)
        }

        /** What they hold as they go: the shafts if they've the cart in hand, their arms full if they carry something. */
        protected fun holding(h: Hand, overlay: Activity): Activity = when {
            Handcarts.pulling(h.p) -> Handcarts.PULL
            !h.p.carried.isEmpty -> Activity.CARRY
            overlay == Handcarts.PULL || overlay == Activity.CARRY -> Activity.NONE
            else -> overlay
        }
    }

    /** A walk to wherever [to] says as it starts. */
    private class Go(
        private val to: (Hand) -> BlockPos?, private val speed: Double, private val near: Double = 1.0,
        private val overlay: Activity = Activity.NONE, private val exact: Boolean = false, private val most: Int = 900,
    ) : Act() {
        private var target: BlockPos? = null
        private var t = 0

        override fun start(h: Hand) {
            t = 0
            target = to(h)
            val at = target ?: return
            h.p.act(holding(h, overlay))
            h.walker.go(at, speed, near, exact)
        }

        override fun tick(h: Hand): Boolean {
            if (target == null) return false
            if (++t > most) {
                h.lost = true
                return false
            }
            keep(h, holding(h, overlay))
            return when (h.walker.tick()) {
                Walker.State.WALKING -> true
                Walker.State.ARRIVED -> false
                Walker.State.FAILED -> {
                    h.lost = true
                    false
                }
            }
        }

        override fun stop(h: Hand) = h.walker.stop()
    }

    /** The one at the head (with the cart) along the way's points up to [until], waiting for the rest when they fall behind. */
    private class Lead(private val until: Int) : Act() {
        private var waiting = false

        override fun start(h: Hand) {
            waiting = false
            go(h)
        }

        private fun go(h: Hand) {
            val party = h.party
            if (party.wp < until) h.walker.go(party.way[party.wp], Jobs.STROLL, 1.8)
        }

        override fun tick(h: Hand): Boolean {
            val party = h.party
            if (party.wp >= until) return false
            keep(h, holding(h, Activity.NONE))
            val behind = party.crew.any { it !== h && !it.lost && it.hugBy == null && it.p.distanceToSqr(h.p) > LAG * LAG }
            if (behind) {
                if (!waiting) {
                    waiting = true
                    h.walker.stop()
                }
                return true
            }
            if (waiting) {
                waiting = false
                go(h)
            }
            when (h.walker.tick()) {
                Walker.State.WALKING -> {}
                Walker.State.ARRIVED -> {
                    party.wp++
                    go(h)
                }
                Walker.State.FAILED -> {
                    h.lost = true
                    return false
                }
            }
            return party.wp < until
        }

        override fun stop(h: Hand) = h.walker.stop()
    }

    /**
     * The [k]th of the rest, beside the cart (the first two, one each side) or behind it, keeping up with the one at
     * the head; now and then (if [about]) stopping to look about them, new to the place.
     */
    private class Trail(private val k: Int, private val about: Boolean) : Act() {
        private var target: BlockPos? = null
        private var pause = 0
        private var next = 0

        override val forever: Boolean get() = true

        override fun start(h: Hand) {
            target = null
            pause = 0
            next = 160 + h.p.random.nextInt(200)
        }

        override fun tick(h: Hand): Boolean {
            val lead = h.party.puller?.p ?: return false
            if (lead === h.p) return false
            if (pause > 0) {
                if (--pause == 0) h.p.act(holding(h, Activity.NONE))
                return true
            }
            val spot = spot(h, lead)
            val d = h.p.position().distanceToSqr(spot)
            if (about && h.p.carried.isEmpty && --next <= 0 && d < 3.0 * 3.0) {
                next = 240 + h.p.random.nextInt(260)
                if (h.p.random.nextInt(3) == 0) {
                    h.walker.stop()
                    target = null
                    h.p.act(Activity.LOOK_ABOUT)
                    pause = Activity.LOOK_ABOUT.ticks
                    return true
                }
            }
            val pos = BlockPos.containing(spot)
            val now = target
            if (d > 1.6 * 1.6 && (now == null || now.distSqr(pos) > 2.5)) {
                target = pos
                h.walker.go(pos, if (d > 6.0 * 6.0) Jobs.BRISK else Jobs.WALK, 0.9)
            }
            if (target != null) {
                if (h.walker.tick() != Walker.State.WALKING) target = null
            } else {
                h.p.face(Mth.approachDegrees(h.p.yBodyRot, lead.yBodyRot, 10f))
            }
            keep(h, holding(h, Activity.NONE))
            return true
        }

        override fun stop(h: Hand) = h.walker.stop()

        /** Their place by the one at the head: beside the cart's bed, or behind it. */
        private fun spot(h: Hand, lead: Townsperson): Vec3 {
            val a = Math.toRadians(lead.yBodyRot.toDouble())
            val fx = -sin(a)
            val fz = cos(a)
            val cart = Handcarts.pulling(lead)
            val back = if (k < 2) (if (cart) 2.0 else 1.6) else (if (cart) 4.6 else 3.2)
            val side = (if (k < 2) (if (cart) 1.5 else 1.0) else 0.7) * (if (k % 2 == 0) 1.0 else -1.0)
            // Their left is (fz, -fx)
            val want = Vec3(lead.x - fx * back + fz * side, lead.y, lead.z - fz * back - fx * side)
            val level = h.p.level()
            val at = BlockPos.containing(want)
            for (dy in intArrayOf(0, 1, -1)) if (Places.standable(level, at.above(dy))) return Vec3(want.x, at.y + dy.toDouble(), want.z)
            return Vec3(lead.x - fx * back, lead.y, lead.z - fz * back)
        }
    }

    /** Stands [ticks] (or, -1, until the stage moves on) facing whatever [at] says, looking about now and then if [about]. */
    private class Stand(private val ticks: Int, private val at: (Hand) -> Vec3?, private val about: Boolean = false) : Act() {
        private var t = 0
        private var next = 0

        override val forever: Boolean get() = ticks < 0

        override fun start(h: Hand) {
            t = 0
            next = 40 + h.p.random.nextInt(80)
            h.p.navigation.stop()
            h.p.act(holding(h, Activity.NONE))
        }

        override fun tick(h: Hand): Boolean {
            t++
            at(h)?.let {
                h.p.lookAt(it)
                h.p.face(Mth.approachDegrees(h.p.yBodyRot, h.p.yawTo(it), 12f))
            }
            val base = holding(h, Activity.NONE)
            if (about && base == Activity.NONE && --next <= 0) {
                next = 120 + h.p.random.nextInt(160)
                if (h.p.random.nextBoolean() && h.p.activity == Activity.NONE) h.p.act(Activity.LOOK_ABOUT)
            }
            keep(h, base)
            return ticks < 0 || t < ticks
        }
    }

    /** A one-shot played facing whatever [at] says; [done] as it ends. */
    private class Play(private val activity: Activity, private val at: (Hand) -> Vec3?, private val done: (Hand) -> Unit = {}) : Act() {
        private var t = 0

        override fun start(h: Hand) {
            t = 0
            h.p.navigation.stop()
            at(h)?.let { h.face(it) }
            h.p.act(activity)
        }

        override fun tick(h: Hand): Boolean {
            at(h)?.let { h.p.lookAt(it) }
            if (++t < activity.ticks) return true
            done(h)
            return false
        }
    }

    /** Something done at once, in its turn. */
    private class Do(private val what: (Hand) -> Unit) : Act() {
        override fun tick(h: Hand): Boolean {
            what(h)
            return false
        }
    }
}
