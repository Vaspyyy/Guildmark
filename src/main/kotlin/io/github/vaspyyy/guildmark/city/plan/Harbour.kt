package io.github.vaspyyy.guildmark.city.plan

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A capital's harbour, on the side of the city ([Layout.harbour], east or west in its frame) that opens
 * onto water: a stone quay along the shore below the walls, reached from the water gate across the
 * harbour square and down a broad flight of steps; warehouses, the harbourmaster's house and the fish
 * market along it; piers and a mole out over the water with cranes and a lighthouse; and the ships and
 * boats tied up at them.
 *
 * It's laid out in its own terms: "along" runs down the wall and "out" away from the middle of the city,
 * so the wall's outer face stands at out = [CityPlan.outer] and the water gate's passage at along = 0.
 * Everything is worked out once in [lay]; [draw] only reads it, a piece of land at a time.
 */
class Harbour(private val plan: CityPlan) {
    private val side: Dir = plan.layout.harbour ?: Dir.E
    /** Down the wall (the way "along" counts up), and back up it. */
    private val down: Dir = plan.sideDir(side, 1)
    private val up: Dir = down.opposite
    private val outer = plan.outer
    private val reach = plan.reach
    private val land = plan.land

    /** Standing height on the sea's surface. */
    val sea: Int = land.sea

    /** Standing height on the quay: a block or two over the water, and never above the town. */
    val quay: Int = min(0, sea + 2)

    // What's laid in each column the harbour may use: along the wall from -reach to reach, out from the wall's face to reach
    private val span = reach - outer
    private val lays = ByteArray((2 * reach + 1) * span)
    private val levels = ByteArray(lays.size)
    /** A column's detail: which way its steps climb, or whether a deck plank is a pier's edge beam. */
    private val details = ByteArray(lays.size)
    private val faces = BooleanArray(lays.size)

    // The land as the world made it, looked up while laying out and kept
    private val known = BooleanArray(lays.size)
    private val heights = ByteArray(lays.size)
    private val wets = BooleanArray(lays.size)

    /** The quay's front (the last column of land), its ends down the wall, and the back of the quayside. */
    private var front = 0
    private var from = 0
    private var to = 0
    private var back = 0
    /** Where the square's steps start down, and the fronts and backs of the sheds. */
    private var stepsTop = 0
    private var shedFront = 0
    private var shedBack = 0

    private val piers = ArrayList<Pier>()
    private val sheds = ArrayList<Shed>()
    private val ships = ArrayList<Ship>()
    private val boats = ArrayList<Boat>()
    private val things = ArrayList<Thing>()
    private var light: Light? = null

    /** Everything the harbour draws, for a quick look whether a piece of land has any of it. */
    private var bounds = Rect(0, 0, -1, -1)

    /** The columns the harbour lays (quays, piers, moorings) on the plan; null if it found no sea to lay them by. */
    var area: Rect? = null
        private set

    /** Lays the harbour out on the plan: called once, after the walls and the town. */
    fun lay(random: Random) {
        val half = min(outer - TOWER_CLEAR, outer * 3 / 5)
        // Where the sea begins, row by row down the wall
        val shore = IntArray(2 * half + 1) { shoreAt(it - half) }
        val near = (-CENTRAL..CENTRAL).map { shore[it + half] }.filter { it <= reach }.sorted()
        // No sea in front of the water gate: the land there is left as it is
        if (near.size < CENTRAL) return
        front = (near[near.size * 4 / 5] - 1).coerceIn(outer + MIN_DEPTH, reach - MIN_WATER)
        back = max(outer + 2, front - MAX_DEPTH + 1)

        // The quay runs on down the wall each way as long as the sea stays near its front
        val ends = IntArray(2)
        for ((k, step) in intArrayOf(-1, 1).withIndex()) {
            var a = 0
            var bad = 0
            var last = 0
            while (abs(a + step) <= half) {
                a += step
                if (shore[a + half] > front + 1 + DREDGE) {
                    if (++bad > RAGGED) break
                } else {
                    bad = 0
                    last = a
                }
            }
            ends[k] = last
        }
        from = min(ends[0], -MIN_HALF)
        to = max(ends[1], MIN_HALF)

        quayside()
        sheds(random)
        piers(random)
        lighthouse()
        landings(random)
        berths(random)
        boats(random)
        furnish(random)
        edges()
        mark()
    }

    // ---- Laying it out ----

    /**
     * The quayside, from the back of it out to the quay's front: a lane along the foot of the walls, a row
     * of yards for the sheds and houses, and the quay itself; in the middle the harbour square, from the
     * water gate down a flight of steps to the water.
     */
    private fun quayside() {
        val lane = back + LANE - 1
        val wharf = front - WHARF + 1
        val gateMouth = outer + CityPlan.GATE_OUT + 1
        // The square's upper terrace runs out past the gatehouse; then the steps down to the quay
        stepsTop = max(lane + 1, gateMouth + 4)
        shedFront = wharf - 1
        shedBack = max(lane + 1, shedFront - MAX_SHED + 1)
        val drop = -quay
        for (a in from..to) for (o in back..front) {
            val street = abs(a) <= 2 && o >= gateMouth
            if (!street && !clearOfWalls(a, o)) continue
            val inSquare = abs(a) <= SQUARE
            when {
                o == front -> put(a, o, Lay.FRONT, quay)
                o >= wharf -> put(a, o, if (inSquare) Lay.SQUARE else Lay.WHARF, quay)
                inSquare && o < stepsTop -> put(a, o, Lay.SQUARE, 0)
                inSquare && o < stepsTop + drop -> put(a, o, Lay.STEPS, -(o - stepsTop), side.opposite.ordinal)
                inSquare -> put(a, o, Lay.SQUARE, quay)
                o <= lane -> put(a, o, Lay.LANE, 0)
                else -> put(a, o, Lay.YARD, quay)
            }
        }
    }

    /**
     * The row of buildings along the quay, out from the square each way: the harbourmaster's house on
     * one side of it and the fish market on the other, then warehouses, sheds and the odd chandlery,
     * with alleys up to the lane every few.
     */
    private fun sheds(random: Random) {
        if (shedFront - shedBack + 1 < MIN_SHED) return
        val masterDown = random.nextBoolean()
        for (sign in intArrayOf(-1, 1)) {
            val end = (if (sign < 0) -from else to) - END_ROOM
            var t = SQUARE + 1
            var count = 0
            var sinceAlley = 0
            while (true) {
                val kind = when {
                    count == 0 -> if ((sign > 0) == masterDown) ShedKind.HARBOURMASTER else ShedKind.FISH_MARKET
                    random.nextInt(6) == 0 -> ShedKind.CHANDLERY
                    random.nextInt(5) < 3 -> ShedKind.WAREHOUSE
                    else -> ShedKind.SHED
                }
                val width = kind.widths[random.nextInt(kind.widths.size)]
                if (t + width - 1 > end) break
                val a0 = if (sign > 0) t else -(t + width - 1)
                val a1 = if (sign > 0) t + width - 1 else -t
                sheds.add(Shed(kind, a0, a1, shedBack, shedFront, random.nextLong()))
                for (a in a0..a1) for (o in shedBack..shedFront) put(a, o, Lay.LOT, quay)
                t += width
                count++
                sinceAlley++
                if (sinceAlley >= 3 || sinceAlley >= 2 && random.nextBoolean()) {
                    if (t + ALLEY - 1 <= end) alley(if (sign > 0) t else -(t + ALLEY - 1))
                    t += ALLEY
                    sinceAlley = 0
                }
            }
        }
        // Which sides back onto a neighbour, and which looks onto the square
        for (shed in sheds) {
            val f = Frame(NullCanvas, plan.sideRect(side, shed.a0, shed.a1, shed.o0, shed.o1), side)
            for ((k, u) in intArrayOf(-1, f.w).withIndex()) {
                val a = along(f.x(u, f.d / 2), f.z(u, f.d / 2))
                shed.shared[k] = index(a, shed.o0)?.let { lays[it].toInt() == Lay.LOT.ordinal } == true
                if (abs(a) == SQUARE) shed.square = if (k == 0) Dir.W else Dir.E
            }
        }
    }

    /** An alley between the sheds from the quay up to the lane, stepping up where the lane stands higher. */
    private fun alley(a0: Int) {
        val drop = -quay
        for (a in a0 until a0 + ALLEY) for (o in back + LANE..shedFront) {
            val i = index(a, o) ?: continue
            if (lays[i].toInt() != Lay.YARD.ordinal) continue
            val k = o - (back + LANE)
            if (k < drop) put(a, o, Lay.STEPS, -k, side.opposite.ordinal) else put(a, o, Lay.ALLEY, quay)
        }
    }

    /**
     * The mole, straight out from the square, and timber piers spaced down the quay each way, each out
     * over the sea as far as it goes (and as it wants to), with room round it.
     */
    private fun piers(random: Random) {
        pier(0, MOLE_HALF, true, MOLE_MIN + random.nextInt(MOLE_MAX - MOLE_MIN + 1))
        val spacing = PIER_SPACING + random.nextInt(5)
        for (sign in intArrayOf(-1, 1)) {
            val end = if (sign < 0) -from else to
            var t = spacing
            while (t <= end - PIER_END_ROOM) {
                val half = if (random.nextInt(3) == 0) 2 else 1
                pier(sign * t, half, false, PIER_MIN + random.nextInt(PIER_MAX - PIER_MIN + 1))
                t += spacing + random.nextInt(5) - 2
            }
        }
    }

    private fun pier(mid: Int, half: Int, mole: Boolean, wanted: Int) {
        var end = front
        while (end - front < wanted && end + 1 <= reach - 2) {
            val o = end + 1
            val open = (mid - half - 2..mid + half + 2).all { a -> isSea(a, o) || o <= front + DREDGE && isFree(a, o) }
            if (!open) break
            end = o
        }
        if (end - front < MIN_PIER) return
        piers.add(Pier(mid - half, mid + half, end, mole))
        for (a in mid - half..mid + half) for (o in front + 1..end) {
            val edge = a == mid - half || a == mid + half
            val dry = !isSea(a, o)
            val lay = when {
                mole -> Lay.MOLE
                dry -> Lay.BOARDWALK
                edge && (Math.floorMod(o - front, PILE_EVERY) == 1 || o == end) -> Lay.PILE
                else -> Lay.DECK
            }
            put(a, o, lay, quay, if (edge) 1 else 0)
        }
    }

    /**
     * The lighthouse, on a round head at the end of the longest pier (the mole if it's near as long):
     * room to walk round it, and the sea all round the head.
     */
    private fun lighthouse() {
        val best = piers.maxByOrNull { it.end - front + if (it.mole) MOLE_PREFERENCE else 0 } ?: return
        val mid = best.mid
        for (radius in doubleArrayOf(HEAD_RADIUS, HEAD_RADIUS - 1.0)) {
            val co = best.end - radius.toInt() + 1
            val r = radius.toInt() + 1
            val cells = ArrayList<Pair<Int, Int>>()
            var fits = true
            for (da in -r..r) for (dOut in -r..r) {
                if (da * da + dOut * dOut > radius * radius) continue
                val a = mid + da
                val o = co + dOut
                if (a in best.a0..best.a1 && o <= best.end) continue
                if (!isSea(a, o) || !isFree(a, o)) fits = false
                cells.add(Pair(a, o))
            }
            if (!fits) continue
            for ((a, o) in cells) put(a, o, Lay.MOLE, quay)
            // The tower's foot is stone right through, whatever the pier was
            for (da in -r..r) for (dOut in -r..r) if (da * da + dOut * dOut <= radius * radius) put(mid + da, co + dOut, Lay.MOLE, quay)
            light = Light(mid, co, radius)
            return
        }
    }

    /**
     * Landing stages down the quay between the piers: steps down from the quay to a stone stage just
     * over the water, where the boats come in.
     */
    private fun landings(random: Random) {
        for (sign in intArrayOf(-1, 1)) {
            val end = if (sign < 0) -from else to
            var t = SQUARE + 4 + random.nextInt(6)
            while (t + LANDING_WIDTH <= end - 4) {
                val a0 = if (sign > 0) t else -(t + LANDING_WIDTH - 1)
                val a1 = a0 + LANDING_WIDTH - 1
                val clear = (a0 - 3..a1 + 3).all { a -> (front + 1..front + 3).all { o -> isSea(a, o) && isFree(a, o) } }
                if (clear) {
                    for (a in a0..a1) {
                        put(a, front, Lay.STEPS, quay, side.opposite.ordinal)
                        for (o in front + 1..front + 2) put(a, o, Lay.LANDING, sea + 1)
                    }
                    t += LANDING_EVERY + random.nextInt(8)
                } else t += 3
            }
        }
    }

    /**
     * Ships tied up alongside the piers, bow out to sea, or along the quay where the piers are too short:
     * the bigger ones nearest the square, with a gangway across to the pier.
     */
    private fun berths(random: Random) {
        val wanted = when (plan.tier) {
            Tier.MEDIUM -> 1 + random.nextInt(2)
            Tier.LARGE -> 2
            Tier.GRAND -> 2 + random.nextInt(2)
        }
        val order = piers.sortedBy { abs(it.mid) + if (it.mole) 0 else 1 }
        var big = true
        for (pier in order) {
            if (ships.size >= wanted) break
            for (sign in if (random.nextBoolean()) intArrayOf(1, -1) else intArrayOf(-1, 1)) {
                if (ships.size >= wanted) break
                val beam = if (big) BIG_BEAM else SMALL_BEAM
                val length = if (big) BIG_LENGTH + random.nextInt(3) else SMALL_LENGTH + random.nextInt(3)
                val a0 = if (sign > 0) pier.a1 + 2 else pier.a0 - 1 - beam
                val a1 = a0 + beam - 1
                val o0 = front + 3
                val o1 = o0 + length - 1
                if (o1 > pier.end + 3 || o1 > reach - 2) continue
                if (!openWater(a0 - 1, a1 + 1, o0 - 1, o1 + 1)) continue
                val ship = Ship(a0, a1, o0, o1, side, big, random.nextLong(), if (sign > 0) a0 - 1 else a1 + 1)
                ships.add(ship)
                moor(a0 - 1, a1 + 1, o0 - 1, o1 + 1)
                big = random.nextInt(3) != 0
                break
            }
        }
        // Too few piers long enough: tie up along the quay instead
        var t = SQUARE + 6
        while (ships.size < wanted && t < max(-from, to)) {
            for (sign in intArrayOf(1, -1)) {
                if (ships.size >= wanted) break
                val beam = if (big) BIG_BEAM else SMALL_BEAM
                val length = if (big) BIG_LENGTH else SMALL_LENGTH
                val a0 = if (sign > 0) t else -(t + length - 1)
                val a1 = a0 + length - 1
                val o0 = front + 2
                val o1 = o0 + beam - 1
                if (o1 > reach - 2 || a0 < from + 2 || a1 > to - 2) continue
                if (!openWater(a0 - 1, a1 + 1, o0 - 1, o1 + 1)) continue
                ships.add(Ship(a0, a1, o0, o1, if (sign > 0) up else down, big, random.nextLong(), o0 - 1))
                moor(a0 - 1, a1 + 1, o0 - 1, o1 + 1)
                big = false
            }
            t += 4
        }
    }

    /** Rowing boats by the landing stages and the piers' roots. */
    private fun boats(random: Random) {
        val wanted = BOATS + random.nextInt(BOATS)
        val spots = ArrayList<Boat>()
        for (pier in piers) for (sign in intArrayOf(-1, 1)) {
            val a0 = if (sign > 0) pier.a1 + 2 else pier.a0 - 4
            spots.add(Boat(a0, a0 + 2, front + 2, front + 6, side, 0L))
        }
        for (a in from..to) {
            val i = index(a, front + 1) ?: continue
            if (lays[i].toInt() != Lay.LANDING.ordinal || lays[index(a + 1, front + 1)!!].toInt() == Lay.LANDING.ordinal) continue
            // Lying along the quay just past the stage's end
            spots.add(Boat(a + 2, a + 6, front + 1, front + 3, if (random.nextBoolean()) down else up, 0L))
        }
        spots.shuffle(random)
        for (spot in spots) {
            if (boats.size >= wanted) break
            if (!openWater(spot.a0 - 1, spot.a1 + 1, spot.o0, spot.o1 + 1)) continue
            boats.add(Boat(spot.a0, spot.a1, spot.o0, spot.o1, spot.bow, random.nextLong()))
            moor(spot.a0, spot.a1, spot.o0, spot.o1)
        }
    }

    /**
     * What stands about the quayside: bollards along the quay's edge, lamps along the quay and the lane
     * and down the piers, cranes over the water, goods stacked outside the warehouses, fish stalls on
     * the square, and the townsfolk.
     */
    private fun furnish(random: Random) {
        val wharf = front - WHARF + 1
        // Cranes on the quay's edge halfway between the piers nearest the square, their jibs over the water
        val cranes = ArrayList<Int>()
        val sorted = piers.sortedBy { it.mid }
        for (k in 0 until sorted.size - 1) {
            val a = (sorted[k].a1 + sorted[k + 1].a0) / 2
            if ((a - 2..a + 2).all { lays[at(it, front)].toInt() == Lay.FRONT.ordinal } && (a - 2..a + 2).all { isSea(it, front + 1) && isFree(it, front + 1) }) cranes.add(a)
        }
        for (a in cranes.sortedBy { abs(it) }.take(CRANES)) things.add(Thing(ThingKind.CRANE, a, front, side))
        val taken = HashSet<Int>()
        for (t in things) for (a in t.a - 2..t.a + 2) taken.add(a)
        // Bollards along the edge, lamps a little back from it
        for (a in from + 1 until to) {
            if (lays[at(a, front)].toInt() != Lay.FRONT.ordinal || a in taken) continue
            val beside = (a - 1..a + 1).any { lays[at(it, front)].toInt() != Lay.FRONT.ordinal || index(it, front + 1)?.let { j -> lays[j].toInt() in PIER_LAYS } == true }
            if (beside) continue
            if (Math.floorMod(a, BOLLARD_EVERY) == 0) things.add(Thing(ThingKind.BOLLARD, a, front, side))
            if (Math.floorMod(a + LAMP_EVERY / 2, LAMP_EVERY) == 0) things.add(Thing(ThingKind.LAMP, a, front - 2, side))
        }
        // Lamps along the lane under the walls, and at the corners of the square's steps
        for (a in from..to) {
            if (abs(a) <= SQUARE || Math.floorMod(a, LAMP_EVERY) != 0) continue
            if (lays[at(a, back)].toInt() == Lay.LANE.ordinal) things.add(Thing(ThingKind.LAMP, a, back, side))
        }
        for (sign in intArrayOf(-1, 1)) {
            things.add(Thing(ThingKind.PILLAR_LAMP, sign * (SQUARE - 1), stepsTop - 1, side))
            things.add(Thing(ThingKind.PILLAR_LAMP, sign * (SQUARE - 1), wharf - 1, side))
        }
        // Down the piers: lamps on posts along the edges, bollards, goods
        for (pier in piers) {
            for (o in front + 4..pier.end step PIER_LAMP_EVERY) {
                things.add(Thing(ThingKind.POST_LAMP, if ((o / PIER_LAMP_EVERY) % 2 == 0) pier.a0 else pier.a1, o, side))
            }
            if (light?.let { it.a == pier.mid } != true) things.add(Thing(ThingKind.POST_LAMP, pier.a1, pier.end, side))
            if (!pier.mole) for (o in front + 3..pier.end - 2 step 5) {
                if (random.nextInt(3) == 0) things.add(Thing(ThingKind.GOODS, if (random.nextBoolean()) pier.a0 else pier.a1, o, side, random.nextInt(GOODS_KINDS)))
            }
        }
        // Goods stacked by the warehouses' doors, fish stalls on the square
        for (shed in sheds) {
            if (shed.kind == ShedKind.WAREHOUSE || shed.kind == ShedKind.SHED) {
                val a = if (random.nextBoolean()) shed.a0 + 1 else shed.a1 - 1
                things.add(Thing(ThingKind.GOODS, a, shedFront + 2, side, random.nextInt(GOODS_KINDS)))
            }
        }
        val lower = stepsTop - quay
        if (wharf - 2 - lower >= 4) for (sign in intArrayOf(-1, 1)) {
            for ((k, o) in listOf(lower + 2, wharf - 3).withIndex()) {
                things.add(Thing(ThingKind.STALL, sign * 7, o, if (sign > 0) up else down, k + (if (sign > 0) 2 else 0)))
            }
        }
        // Townsfolk about the quay, and a cat or two; dockers to carry the goods about, and fishers out on the piers
        things.add(Thing(ThingKind.FOLK, -4, wharf + 2, side))
        things.add(Thing(ThingKind.FOLK, 5, front - 3, side))
        things.add(Thing(ThingKind.CAT, if (random.nextBoolean()) 9 else -9, wharf + 1, side))
        things.add(Thing(ThingKind.DOCKER, 4, wharf + 2, side))
        things.add(Thing(ThingKind.DOCKER, -6, front - 3, side))
        fishers()
    }

    /**
     * Fishers at the ends of the piers nearest the square, then along their sides and round the lighthouse's
     * head, each facing open water with nothing moored in it; at most two to a pier.
     */
    private fun fishers() {
        val wanted = when (plan.tier) {
            Tier.MEDIUM -> 2
            Tier.LARGE -> 3
            Tier.GRAND -> 5
        }
        val order = piers.sortedBy { abs(it.mid) }
        val spots = ArrayList<Pair<Pier?, Thing>>()
        for (pier in order) if (light?.a != pier.mid) spots.add(Pair(pier, Thing(ThingKind.FISHER, pier.mid, pier.end, side)))
        light?.let { l ->
            val r = l.radius.toInt()
            spots.add(Pair(null, Thing(ThingKind.FISHER, l.a, l.o + r, side)))
            spots.add(Pair(null, Thing(ThingKind.FISHER, l.a + r, l.o, down)))
            spots.add(Pair(null, Thing(ThingKind.FISHER, l.a - r, l.o, up)))
        }
        for (pier in order) {
            spots.add(Pair(pier, Thing(ThingKind.FISHER, pier.a1, pier.end - 2, down)))
            spots.add(Pair(pier, Thing(ThingKind.FISHER, pier.a0, pier.end - 3, up)))
        }
        val taken = HashSet<Long>()
        for (t in things) {
            taken.add(cellKey(t.a, t.o))
            // Goods stack out over the next column too
            if (t.kind == ThingKind.GOODS) taken.add(cellKey(t.a, t.o + 1))
        }
        val perPier = HashMap<Pier, Int>()
        var placed = 0
        for ((pier, spot) in spots) {
            if (placed >= wanted) break
            if (pier != null && (perPier[pier] ?: 0) >= 2) continue
            if (!fishable(spot, taken)) continue
            things.add(spot)
            taken.add(cellKey(spot.a, spot.o))
            if (pier != null) perPier[pier] = (perPier[pier] ?: 0) + 1
            placed++
        }
    }

    /**
     * Whether someone can fish from [spot]: standing on a pier, the mole or a landing, clear of the lighthouse and
     * of anything else standing there, with three blocks of open sea ahead.
     */
    private fun fishable(spot: Thing, taken: Set<Long>): Boolean {
        val i = index(spot.a, spot.o) ?: return false
        if (lays[i].toInt() !in PIER_LAYS || cellKey(spot.a, spot.o) in taken) return false
        light?.let { l ->
            val da = spot.a - l.a
            val dOut = spot.o - l.o
            if (da * da + dOut * dOut <= (TOWER_RADIUS + 1.0) * (TOWER_RADIUS + 1.0)) return false
        }
        for (k in 1..3) {
            val a = spot.a + k * alongStep(spot.facing)
            val o = spot.o + k * outStep(spot.facing)
            if (!isSea(a, o) || !isFree(a, o)) return false
        }
        return true
    }

    /**
     * A roof for those who work out on the quay, to go to in the rain and at night: just inside the warehouse,
     * shed or fish market nearest [a] down the quay. Null if the quay has none.
     */
    private fun shelter(a: Int): At? {
        val shed = sheds.filter { it.kind == ShedKind.WAREHOUSE || it.kind == ShedKind.SHED || it.kind == ShedKind.FISH_MARKET }
            .minByOrNull { abs((it.a0 + it.a1) / 2 - a) } ?: return null
        val mid = (shed.a0 + shed.a1) / 2
        return At(x(mid, shedFront - 2), quay, z(mid, shedFront - 2))
    }

    private fun cellKey(a: Int, o: Int): Long = (a.toLong() shl 32) or (o.toLong() and 0xFFFFFFFFL)

    /**
     * Where the quayside meets the land it was cut into or built out over: stone faces where it stands over
     * lower ground or the water, retaining walls where higher ground runs up against it, and a railing along
     * any drop worth falling down.
     */
    private fun edges() {
        // Retaining walls first: they change the columns' heights
        for (a in from - 1..to + 1) for (o in back..front) {
            val i = index(a, o) ?: continue
            val lay = lays[i].toInt()
            if (lay !in LAND_LAYS) continue
            val y = levels[i].toInt()
            var top = y
            for (d in Dir.entries) {
                val na = a + alongStep(d)
                val no = o + outStep(d)
                val j = index(na, no) ?: continue
                if (no <= outer + 1 || lays[j].toInt() != Lay.NONE.ordinal || wet(na, no)) continue
                top = max(top, min(height(na, no), y + MAX_RETAIN))
            }
            if (top >= y + 2) {
                lays[i] = Lay.REVETMENT.ordinal.toByte()
                levels[i] = top.toByte()
            }
        }
        for (a in from - 1..to + 1) for (o in back..front + 1) {
            val i = index(a, o) ?: continue
            if (lays[i].toInt() == Lay.NONE.ordinal) continue
            val y = levels[i]
            var rail = false
            for (d in Dir.entries) {
                val na = a + alongStep(d)
                val no = o + outStep(d)
                val j = index(na, no) ?: continue
                val other = lays[j].toInt()
                val lower = if (other == Lay.NONE.ordinal) wet(na, no) || height(na, no) < y - 1 else levels[j] < y - 1
                if (lower) faces[i] = true
                // A railing where the drop is to dry ground or a yard, not to the water or a building's wall
                val drop = if (other == Lay.NONE.ordinal) (if (wet(na, no)) 0 else y - height(na, no)) else if (other in RAILED_BELOW) y - levels[j] else 0
                if (drop >= 2) rail = true
            }
            if (rail && lays[i].toInt() in RAILED) details[i] = RAIL
        }
    }

    /** Claims the harbour's columns on the plan. */
    private fun mark() {
        var minX = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (a in -reach..reach) for (o in outer + 1..reach) {
            val lay = Lay.entries[lays[at(a, o)].toInt()]
            if (lay == Lay.NONE) continue
            val x = x(a, o)
            val z = z(a, o)
            plan.grid[x, z] = lay.use
            minX = min(minX, x)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxZ = max(maxZ, z)
        }
        if (minX <= maxX) {
            val laid = Rect(minX, minZ, maxX, maxZ)
            area = laid
            bounds = laid.grow(BOUNDS_MARGIN)
        }
    }

    /** Marks open water where a ship or boat lies, digging it out where the shore ran into it. */
    private fun moor(a0: Int, a1: Int, o0: Int, o1: Int) {
        for (a in a0..a1) for (o in o0..o1) if (isFree(a, o)) put(a, o, if (isSea(a, o)) Lay.MOORING else Lay.DREDGED, sea)
    }

    /** Whether the sea stands over all of a stretch of water, with nothing else there yet. */
    private fun openWater(a0: Int, a1: Int, o0: Int, o1: Int): Boolean {
        if (o1 > reach - 1) return false
        for (a in a0..a1) for (o in o0..o1) if (!isSea(a, o) || !isFree(a, o)) return false
        return true
    }

    private fun isFree(a: Int, o: Int): Boolean = index(a, o)?.let { lays[it].toInt() == Lay.NONE.ordinal } ?: false

    private fun put(a: Int, o: Int, lay: Lay, level: Int, detail: Int = 0) {
        val i = index(a, o) ?: return
        lays[i] = lay.ordinal.toByte()
        levels[i] = level.toByte()
        details[i] = detail.toByte()
    }

    /**
     * Whether a column is far enough from the walls, towers and gatehouses to build on: a block clear of
     * the walls, two of the towers and gatehouses, whatever they turn out to be.
     */
    private fun clearOfWalls(a: Int, o: Int): Boolean {
        val x = x(a, o)
        val z = z(a, o)
        for (dx in -2..2) for (dz in -2..2) {
            val use = plan.grid[x + dx, z + dz]
            val near = max(abs(dx), abs(dz))
            if (use == Use.WALL && near <= 1 || (use == Use.TOWER || use == Use.GATE) && near <= 2) return false
        }
        return true
    }

    /** The first column out from the walls where the open sea begins along this row, or past [reach] if it doesn't. */
    private fun shoreAt(a: Int): Int {
        for (o in outer + 1..reach - 2) if (isSea(a, o) && isSea(a, o + 1) && isSea(a, o + 2)) return o
        return reach + 1
    }

    /** Whether the sea itself (not a pond or a stream) stands over a column. */
    private fun isSea(a: Int, o: Int): Boolean = wet(a, o) && abs(height(a, o) - sea) <= 1

    private fun survey(a: Int, o: Int): Int? {
        val i = index(a, o) ?: return null
        if (!known[i]) {
            val x = x(a, o)
            val z = z(a, o)
            heights[i] = land.height(x, z).coerceIn(-120, 120).toByte()
            wets[i] = land.wet(x, z)
            known[i] = true
        }
        return i
    }

    private fun height(a: Int, o: Int): Int = survey(a, o)?.let { heights[it].toInt() } ?: 0
    private fun wet(a: Int, o: Int): Boolean = survey(a, o)?.let { wets[it] } ?: false

    // ---- Drawing ----

    /** Draws whatever of the harbour falls in [canvas]'s clip. */
    fun draw(canvas: Canvas) {
        if (!canvas.touches(bounds)) return
        val clip = canvas.clip
        // The ground first, column by column: laying it clears whatever stood over it
        for (x in max(clip.x0, bounds.x0)..min(clip.x1, bounds.x1)) for (z in max(clip.z0, bounds.z0)..min(clip.z1, bounds.z1)) {
            ground(canvas, x, z)
        }
        for (shed in sheds) {
            val lot = plan.sideRect(side, shed.a0, shed.a1, shed.o0, shed.o1)
            if (canvas.touches(lot.grow(3))) HarbourBuildings(Frame(canvas, lot, side, quay), Random(shed.seed), shed.shared, shed.square).draw(shed.kind)
        }
        light?.let { if (canvas.touches(plan.sideRect(side, it.a - 6, it.a + 6, it.o - 6, it.o + 6))) lighthouse(canvas, it) }
        for (ship in ships) {
            val lot = plan.sideRect(side, ship.a0, ship.a1, ship.o0, ship.o1)
            if (canvas.touches(lot.grow(6))) Ships(Frame(canvas, lot, ship.bow, sea), Random(ship.seed)).ship(ship.big)
            gangway(canvas, ship)
        }
        for (boat in boats) {
            val lot = plan.sideRect(side, boat.a0, boat.a1, boat.o0, boat.o1)
            if (canvas.touches(lot.grow(2))) Ships(Frame(canvas, lot, boat.bow, sea), Random(boat.seed)).boat()
        }
        for (t in things) if (canvas.touches(plan.sideRect(side, t.a - 6, t.a + 6, t.o - 6, t.o + 6))) thing(canvas, t)
    }

    /** The ground in one column: paving, steps, the quay's face, a pier's deck and its pile. */
    private fun ground(canvas: Canvas, x: Int, z: Int) {
        val a = along(x, z)
        val o = out(x, z)
        val i = index(a, o) ?: return
        val y = levels[i].toInt()
        val fill = if (faces[i]) B(M.WALL) else FILL
        when (Lay.entries[lays[i].toInt()]) {
            Lay.NONE, Lay.MOORING -> {}
            Lay.LANE -> canvas.ground(x, z, y, B(M.PAVE_LANE), fill)
            Lay.ALLEY -> canvas.ground(x, z, y, B(M.PAVE_LANE, v = 1), fill)
            Lay.SQUARE -> canvas.ground(x, z, y, squarePaving(a, o), fill)
            Lay.STEPS -> canvas.ground(x, z, y, B(M.WALL_STAIRS, Dir.entries[details[i].toInt()]), B(M.WALL))
            Lay.LOT -> canvas.ground(x, z, y, B(M.FOUNDATION), B(M.FOUNDATION))
            Lay.YARD -> canvas.ground(x, z, y, B(M.PAVE_YARD), fill)
            Lay.WHARF -> canvas.ground(x, z, y, wharfPaving(a, o), fill)
            Lay.FRONT -> canvas.ground(x, z, y, B(M.WALL_TRIM), B(M.WALL))
            Lay.REVETMENT -> {
                canvas.ground(x, z, y, B(M.WALL_TRIM), B(M.WALL))
                canvas.set(x, y, z, B(M.WALL_POST))
            }
            Lay.MOLE -> canvas.ground(x, z, y, molePaving(a, o), B(M.WALL))
            Lay.PILE -> {
                canvas.ground(x, z, y - 1, B(M.LOG), B(M.LOG))
                canvas.set(x, y - 1, z, deck(i))
            }
            Lay.DECK -> canvas.set(x, y - 1, z, deck(i))
            Lay.BOARDWALK -> canvas.ground(x, z, y, deck(i), FILL)
            Lay.LANDING -> canvas.ground(x, z, y, B(M.STONE), B(M.STONE))
            Lay.DREDGED -> {
                canvas.clearing(x, z, sea - DREDGE_DEPTH)
                for (k in sea - DREDGE_DEPTH until sea) canvas.set(x, k, z, B(M.WATER))
            }
        }
        if (details[i] == RAIL && lays[i].toInt() in RAILED) canvas.set(x, y, z, B(M.WALL_POST))
    }

    /** A pier's deck: an edge beam down each side, planks across between them. */
    private fun deck(i: Int): B = if (details[i].toInt() == 1) B(M.TIMBER, axis = axisOf(side)) else B(M.PLANKS)

    /** Smooth flags with a border, and a broad band of darker stone down the middle from the gate to the mole. */
    private fun squarePaving(a: Int, o: Int): B = when {
        abs(a) == SQUARE -> B(M.PAVE_PLAZA, v = 1)
        abs(a) <= 2 -> B(M.PAVE_STREET, v = if (abs(a) == 2) 1 else 0)
        Math.floorMod(o, 4) == 0 -> B(M.PAVE_PLAZA, v = 1)
        else -> B(M.PAVE_PLAZA)
    }

    /** Setts laid in bands across the quay, a kerb of darker stone along its front. */
    private fun wharfPaving(a: Int, o: Int): B = when {
        o == front - 1 -> B(M.PAVE_STREET, v = 1)
        Math.floorMod(a, 6) == 0 -> B(M.PAVE_STREET, v = 1)
        else -> B(M.PAVE_STREET)
    }

    /** The mole's top: a kerb down each side, flags between. */
    private fun molePaving(a: Int, o: Int): B {
        val edge = Dir.entries.any { d -> index(a + alongStep(d), o + outStep(d))?.let { lays[it].toInt() != Lay.MOLE.ordinal } ?: true }
        return if (edge) B(M.WALL_TRIM) else if (Math.floorMod(o, 5) == 0) B(M.PAVE_STREET, v = 1) else B(M.PAVE_STREET)
    }

    // ---- The lighthouse ----

    /**
     * A round stone lighthouse on the pier's head: a door at its foot, a ladder up the inside past slit
     * windows, a railed gallery round the lantern room, the fire behind glass, and a slate cap.
     */
    private fun lighthouse(canvas: Canvas, l: Light) {
        val y0 = quay
        val gallery = y0 + SHAFT
        val r = TOWER_RADIUS
        val reachOut = (r + 2).toInt()
        val inward = side.opposite
        for (da in -reachOut..reachOut) for (dOut in -reachOut..reachOut) {
            val dist = sqrt((da * da + dOut * dOut).toDouble())
            val a = l.a + da
            val o = l.o + dOut
            fun at(y: Int, b: B) = canvas.set(x(a, o), y, z(a, o), b)
            if (dist <= r + 0.5) {
                val shell = dist > r - 1.0
                for (y in y0 until gallery) {
                    val b = when {
                        !shell -> B.AIR
                        // The door, facing back down the pier, right through the wall
                        dOut < 0 && da == 0 && y < y0 + 2 -> B.AIR
                        y == y0 -> B(M.WALL_DARK)
                        // Slit windows, but none just over the door where its lamp hangs
                        (y - y0) % 4 == 3 && (da == 0 || dOut == 0) && y > y0 + 2 && !(dOut < 0 && da == 0 && y == y0 + 3) -> B(M.WINDOW)
                        (y - y0) % BAND == 0 -> B(M.WALL_TRIM)
                        else -> B(M.FINE)
                    }
                    at(y, b)
                }
                at(gallery, B(M.WALL_TRIM))
            }
            // The gallery round the lantern room, on a corbelled ring
            if (dist <= r + 1.5 && dist > r + 0.5) {
                at(gallery - 1, B(M.WALL_STAIRS, dirTo(da, dOut).opposite, top = true))
                at(gallery, B(M.WALL_SLAB, top = true))
                at(gallery + 1, B(M.FENCE))
            }
            // The lantern room: glass round the fire, under a slate cap
            if (dist <= r - 0.5) {
                val rim = dist > r - 1.5
                for (y in gallery + 1..gallery + 3) at(y, if (rim) (if (da == 0 || dOut == 0 || abs(da) == abs(dOut)) B(M.GLASS) else B(M.WALL_TRIM)) else B.AIR)
            }
            for (k in 0..CAP) {
                val rk = r + 0.5 - k * (r + 0.5) / CAP
                if (dist > rk + 0.01) break
                if (k == 0 || dist > rk - 1.3) at(gallery + 4 + k, if (k == 0 && dist > r - 0.5) B(M.ROOF_SLAB, v = ROOF_SLATE) else B(M.ROOF, v = ROOF_SLATE))
            }
        }
        fun at(da: Int, dOut: Int, y: Int, b: B) = canvas.set(x(l.a + da, l.o + dOut), y, z(l.a + da, l.o + dOut), b)
        // The fire, and the light hanging over the door
        at(0, 0, gallery, B(M.WALL_TRIM))
        at(0, 0, gallery + 1, B(M.CAMPFIRE))
        for (y in gallery + 4 + CAP + 1..gallery + 4 + CAP + 2) at(0, 0, y, B(M.FENCE))
        at(0, -r.toInt() - 1, y0 + 2, B(M.LANTERN, top = true).facing(inward))
        // A ladder up the far wall to a hatch in the gallery floor, a lamp halfway up
        val ladder = r.toInt() - 1
        for (y in y0 until gallery) at(0, ladder, y, B(M.LADDER, inward))
        at(0, ladder, gallery, B(M.TRAPDOOR, inward, top = true))
        // Two lamps up the shaft, each hung from a beam across it clear of the ladder
        for (y in intArrayOf(y0 + 3, y0 + SHAFT / 2)) {
            for (da in -ladder..ladder) at(da, 0, y + 1, B(M.TIMBER, axis = if (side.alongX) Axis.Z else Axis.X))
            at(0, 0, y, B(M.LANTERN, top = true))
        }
    }

    /** The way from the middle of something to one of its cells, the nearest of the four. */
    private fun dirTo(da: Int, dOut: Int): Dir = if (abs(da) > abs(dOut)) (if (da > 0) down else up) else (if (dOut > 0) side else side.opposite)

    // ---- On the quay ----

    private fun thing(canvas: Canvas, t: Thing) {
        fun at(da: Int, dOut: Int, y: Int, b: B) = canvas.set(x(t.a + da, t.o + dOut), y, z(t.a + da, t.o + dOut), b)
        val y = levelAt(t.a, t.o)
        when (t.kind) {
            ThingKind.BOLLARD -> at(0, 0, y, B(M.WALL_POST))
            ThingKind.LAMP -> {
                at(0, 0, y, B(M.WALL_POST))
                at(0, 0, y + 1, B(M.FENCE))
                at(0, 0, y + 2, B(M.FENCE))
                at(0, 0, y + 3, B(M.LANTERN))
            }
            ThingKind.PILLAR_LAMP -> {
                at(0, 0, y, B(M.WALL_DARK))
                at(0, 0, y + 1, B(M.WALL_TRIM))
                at(0, 0, y + 2, B(M.WALL_POST))
                at(0, 0, y + 3, B(M.LANTERN))
            }
            ThingKind.POST_LAMP -> {
                at(0, 0, y, B(M.FENCE))
                at(0, 0, y + 1, B(M.FENCE))
                at(0, 0, y + 2, B(M.LANTERN))
            }
            ThingKind.CRANE -> crane(canvas, t, y)
            ThingKind.GOODS -> goods(canvas, t, y)
            ThingKind.STALL -> stall(canvas, t, y)
            ThingKind.FOLK -> canvas.spawn(Spawn.VILLAGER, x(t.a, t.o), y, z(t.a, t.o), t.facing)
            ThingKind.CAT -> canvas.spawn(Spawn.CAT, x(t.a, t.o), y, z(t.a, t.o), t.facing)
            ThingKind.DOCKER -> canvas.resident(Folk.CITIZEN, At(x(t.a, t.o), y, z(t.a, t.o)), home = shelter(t.a))
            ThingKind.FISHER -> {
                val post = At(x(t.a, t.o), y, z(t.a, t.o))
                canvas.resident(Folk.FISHER, post, post = post, facing = t.facing, home = shelter(t.a))
            }
        }
    }

    /** The standing height where a thing stands. */
    private fun levelAt(a: Int, o: Int): Int = index(a, o)?.let { levels[it].toInt() } ?: quay

    /**
     * A crane on the quay's edge: a timber tower on four posts with a little roof, its jib out over the
     * water on braces, a chain down from the end of it with a hook and a crate on the hook.
     */
    private fun crane(canvas: Canvas, t: Thing, y: Int) {
        val f = Frame(canvas, plan.sideRect(side, t.a - 1, t.a + 1, t.o - 2, t.o), side, y)
        for (u in listOf(0, 2)) for (v in listOf(0, 2)) for (k in 0..CRANE_HEIGHT) f.set(u, k, v, B(M.TIMBER))
        for (u in 0..2) for (v in 0..2) {
            if (u == 1 && v == 1) continue
            f.set(u, CRANE_HEIGHT - 3, v, if (u == 1 || v == 1) B(M.TIMBER, axis = if (u == 1) Axis.X else Axis.Z) else B(M.TIMBER))
            f.set(u, CRANE_HEIGHT + 1, v, B(M.PLANK_SLAB))
        }
        f.set(1, CRANE_HEIGHT + 1, 1, B(M.PLANKS))
        // The jib: a beam from the back of the tower out past its front over the water
        for (v in -CRANE_JIB..2) f.set(1, CRANE_HEIGHT, v, B(M.TIMBER, axis = Axis.Z))
        f.set(1, CRANE_HEIGHT - 1, -1, B(M.PLANK_STAIRS, Dir.S, top = true))
        f.set(1, CRANE_HEIGHT - 1, 0, B(M.PLANK_STAIRS, Dir.S, top = true))
        // The winding drum inside the tower, its rope up over the jib and down to the hook
        f.set(1, 1, 1, B(M.LOG, axis = Axis.X))
        f.set(0, 1, 1, B(M.TRAPDOOR, Dir.W, open = true))
        f.set(2, 1, 1, B(M.TRAPDOOR, Dir.E, open = true))
        val hook = CRANE_HEIGHT - CRANE_DROP
        for (k in hook + 1 until CRANE_HEIGHT) f.set(1, k, -CRANE_JIB, B(M.CHAIN))
        f.set(1, hook, -CRANE_JIB, B(M.HOOK))
        f.set(1, hook - 1, -CRANE_JIB, B(M.CRATE))
        // A lamp in the tower on a chain from the jib
        for (k in CRANE_HEIGHT - 3 until CRANE_HEIGHT) f.set(1, k, 1, B(M.CHAIN))
        f.set(1, CRANE_HEIGHT - 4, 1, B(M.LANTERN, top = true))
    }

    /** A stack of goods: crates, barrels, sacks or rope, by a door or on a pier. */
    private fun goods(canvas: Canvas, t: Thing, y: Int) {
        fun at(da: Int, dOut: Int, k: Int, b: B) = canvas.set(x(t.a + da, t.o + dOut), y + k, z(t.a + da, t.o + dOut), b)
        when (t.v) {
            0 -> {
                at(0, 0, 0, B(M.CRATE))
                at(0, 0, 1, B(M.CRATE))
                at(0, 1, 0, B(M.CRATE))
            }
            1 -> {
                at(0, 0, 0, B(M.BARREL))
                at(0, 1, 0, B(M.BARREL, side))
                at(0, 0, 1, B(M.BARREL))
            }
            2 -> {
                at(0, 0, 0, B(M.HAY, axis = axisOf(side)))
                at(0, 1, 0, B(M.CRATE))
            }
            else -> {
                at(0, 0, 0, B(M.CARPET, color = Dye.BROWN))
                at(0, 1, 0, B(M.BARREL))
            }
        }
    }

    /** A fish stall: a counter under a striped awning on posts, the catch in barrels and tubs, the fishmonger behind. */
    private fun stall(canvas: Canvas, t: Thing, y: Int) {
        val front = t.facing
        val across = front.cw
        fun at(a: Int, b: Int, k: Int, block: B) =
            canvas.set(x(t.a, t.o) + across.dx * a - front.dx * b, y + k, z(t.a, t.o) + across.dz * a - front.dz * b, block)
        val stripe = STALL_COLORS[Math.floorMod(t.v, STALL_COLORS.size)]
        for (a in listOf(-2, 2)) for (b in 0..1) for (k in 0..2) at(a, b, k, B(M.FENCE))
        at(-1, 0, 0, B(M.CAULDRON, v = 2))
        at(0, 0, 0, B(M.PLANK_SLAB, top = true))
        at(1, 0, 0, B(M.BARREL, front))
        at(0, 0, 1, B(M.CANDLE))
        at(-1, 1, 0, B(M.BARREL))
        at(1, 1, 0, B(M.SMOKER, front))
        for (a in -2..2) for (b in -1..1) at(a, b, 3, B(M.WOOL, color = if (Math.floorMod(a, 2) == 0) stripe else Dye.WHITE))
        // Fishmongers keep every stall, crying the catch and selling it from the market's stock; in capitals
        // generated before they did, villagers keep half of them
        val behind = At(x(t.a, t.o) - front.dx, y, z(t.a, t.o) - front.dz)
        if (t.v % 2 == 0 && plan.layout.version < 1) canvas.spawn(Spawn.VILLAGER, behind.x, y, behind.z, front)
        else canvas.resident(Folk.MERCHANT, behind, post = behind, facing = front)
    }

    /** A gangway of planks from a ship's deck across to the pier or the quay. */
    private fun gangway(canvas: Canvas, ship: Ship) {
        val mid = if (ship.bow == side) (ship.o0 + ship.o1) / 2 else (ship.a0 + ship.a1) / 2
        for (k in -1..0) {
            val (a, o) = if (ship.bow == side) Pair(ship.gangway, mid + k) else Pair(mid + k, ship.gangway)
            if (canvas.clip.contains(x(a, o), z(a, o))) canvas.set(x(a, o), sea + 1, z(a, o), B(M.PLANK_SLAB, top = true))
        }
    }

    // ---- The harbour's own frame ----

    private fun x(a: Int, o: Int): Int = if (side.alongX) o * side.dx else a
    private fun z(a: Int, o: Int): Int = if (side.alongX) a else o * side.dz
    private fun along(x: Int, z: Int): Int = if (side.alongX) z else x
    private fun out(x: Int, z: Int): Int = x * side.dx + z * side.dz

    /** How far a step [d] goes along the wall, and out from it. */
    private fun alongStep(d: Dir): Int = if (side.alongX) d.dz else d.dx
    private fun outStep(d: Dir): Int = d.dx * side.dx + d.dz * side.dz

    private fun at(a: Int, o: Int): Int = (a + reach) * span + (o - outer - 1)
    private fun index(a: Int, o: Int): Int? = if (abs(a) <= reach && o in outer + 1..reach) at(a, o) else null

    /** What's laid in a column, and what that is on the plan. */
    private enum class Lay(val use: Use) {
        NONE(Use.OUTSIDE),
        /** The lane along the foot of the walls, and the alleys up to it between the sheds. */
        LANE(Use.QUAY), ALLEY(Use.QUAY),
        /** The harbour square, and the street out of the water gate into it. */
        SQUARE(Use.QUAY),
        /** A flight of steps down from one level to the next. */
        STEPS(Use.QUAY),
        /** Where a shed or a house stands, and the yards round them. */
        LOT(Use.QUAY), YARD(Use.QUAY),
        /** The quay's paving, and its front: a kerb on a stone face going down into the water. */
        WHARF(Use.QUAY), FRONT(Use.QUAY),
        /** A retaining wall holding back higher ground. */
        REVETMENT(Use.QUAY),
        /** A stone mole or pier head, built up from the sea bed. */
        MOLE(Use.PIER),
        /** A timber pier's deck, over a pile driven into the sea bed or between them, or over a bit of shore. */
        PILE(Use.PIER), DECK(Use.PIER), BOARDWALK(Use.PIER),
        /** A landing stage just over the water, at the foot of the quay. */
        LANDING(Use.PIER),
        /** Open water where a ship or a boat lies, and the same dug out of the shore. */
        MOORING(Use.MOORING), DREDGED(Use.MOORING),
    }

    /** A pier out over the water: [a0]..[a1] across, from the quay's front out to [end]. A [mole] is stone. */
    private class Pier(val a0: Int, val a1: Int, val end: Int, val mole: Boolean) {
        val mid: Int get() = (a0 + a1) / 2
    }

    /** The lighthouse's middle and its head's radius. */
    private class Light(val a: Int, val o: Int, val radius: Double)

    /**
     * A building on the quay, on its lot from [a0] to [a1] down the wall and [o0] to [o1] out from it,
     * facing the water: which of its sides (west and east in its own frame) back onto a neighbour, and
     * which looks onto the square.
     */
    private class Shed(val kind: ShedKind, val a0: Int, val a1: Int, val o0: Int, val o1: Int, val seed: Long) {
        val shared = BooleanArray(2)
        var square: Dir? = null
    }

    /**
     * A ship lying from [a0] to [a1] down the wall and [o0] to [o1] out, its bow toward [bow]; [big] ones
     * have two masts. The gangway crosses at [gangway] (along the wall for a ship alongside a pier, out
     * from it for one along the quay).
     */
    private class Ship(val a0: Int, val a1: Int, val o0: Int, val o1: Int, val bow: Dir, val big: Boolean, val seed: Long, val gangway: Int)

    /** A rowing boat lying from [a0] to [a1] down the wall and [o0] to [o1] out, its bow toward [bow]. */
    private class Boat(val a0: Int, val a1: Int, val o0: Int, val o1: Int, val bow: Dir, val seed: Long)

    private enum class ThingKind { BOLLARD, LAMP, PILLAR_LAMP, POST_LAMP, CRANE, GOODS, STALL, FOLK, CAT, DOCKER, FISHER }

    /** Something standing on the quayside at [a], [o], facing [facing]. */
    private class Thing(val kind: ThingKind, val a: Int, val o: Int, val facing: Dir, val v: Int = 0)

    companion object {
        /** The quay's front is at least this far out from the walls' face, and the quayside at most this deep. */
        const val MIN_DEPTH = 26
        const val MAX_DEPTH = 28
        /** Open water wanted past the quay's front, inside the city's reach. */
        const val MIN_WATER = 12
        /** Widths across the quayside: the lane under the walls, and the quay in front of the sheds. */
        const val LANE = 4
        const val WHARF = 8
        /** The sheds are at most this deep, and need at least this much room. */
        const val MAX_SHED = 14
        const val MIN_SHED = 9
        const val ALLEY = 3
        /** Room left at each end of the quay past the last shed. */
        const val END_ROOM = 4
        /** The harbour square reaches this far each way from the middle of the water gate. */
        const val SQUARE = 12
        /** Rows either side of the gate whose shore sets the quay's line. */
        const val CENTRAL = 24
        /** How far the shore may lie past the quay's front (dug out to the sea), and for how many rows in a row. */
        const val DREDGE = 4
        const val RAGGED = 3
        const val DREDGE_DEPTH = 3
        /** The quay runs at least this far each way from the gate, and stops this short of a corner of the walls. */
        const val MIN_HALF = 36
        const val TOWER_CLEAR = 12
        /** Highest a retaining wall is built against the land behind it. */
        const val MAX_RETAIN = 8

        /** The mole's half-width and length; the piers' spacing, lengths and room at the quay's ends. */
        const val MOLE_HALF = 3
        const val MOLE_MIN = 24
        const val MOLE_MAX = 40
        const val PIER_SPACING = 20
        const val PIER_MIN = 16
        const val PIER_MAX = 34
        const val MIN_PIER = 10
        const val PIER_END_ROOM = 8
        const val PILE_EVERY = 3
        /** How much longer than the mole a pier must be to take the lighthouse from it. */
        const val MOLE_PREFERENCE = 6

        /** The lighthouse: its head's radius, the tower's, its shaft's height, the bands up it and its cap. */
        const val HEAD_RADIUS = 5.5
        const val TOWER_RADIUS = 3.5
        const val SHAFT = 18
        const val BAND = 6
        const val CAP = 5
        const val ROOF_SLATE = 0

        const val LANDING_WIDTH = 4
        const val LANDING_EVERY = 22

        /** Ships' beams, and their lengths from the bowsprit's tip to the stern. */
        const val BIG_BEAM = 9
        const val BIG_LENGTH = 28
        const val SMALL_BEAM = 7
        const val SMALL_LENGTH = 22
        const val BOATS = 3

        const val CRANES = 2
        const val CRANE_HEIGHT = 8
        const val CRANE_JIB = 4
        const val CRANE_DROP = 4
        const val BOLLARD_EVERY = 6
        const val LAMP_EVERY = 12
        const val PIER_LAMP_EVERY = 8
        const val GOODS_KINDS = 4

        /** The detail marking a column with a railing along it. */
        const val RAIL: Byte = 2

        /** Room round the harbour's columns for what stands out over their edges. */
        const val BOUNDS_MARGIN = 8

        val FILL = B(M.FILL)
        val STALL_COLORS = listOf(Dye.BLUE, Dye.LIGHT_BLUE, Dye.CYAN, Dye.WHITE)

        private val LAND_LAYS = setOf(Lay.LANE, Lay.ALLEY, Lay.SQUARE, Lay.YARD, Lay.WHARF).map { it.ordinal }.toSet()
        private val RAILED = setOf(Lay.LANE, Lay.ALLEY, Lay.SQUARE, Lay.YARD, Lay.WHARF).map { it.ordinal }.toSet()
        private val RAILED_BELOW = setOf(Lay.YARD, Lay.ALLEY, Lay.WHARF, Lay.SQUARE, Lay.LANE).map { it.ordinal }.toSet()
        private val PIER_LAYS = setOf(Lay.MOLE, Lay.PILE, Lay.DECK, Lay.BOARDWALK, Lay.LANDING).map { it.ordinal }.toSet()
    }
}
