package io.github.vaspyyy.guildmark.city.plan

/**
 * Everything a city's plan sets down where its people will use it, worked out by drawing its buildings, its
 * street furniture, the harbour and the farms into a canvas that keeps only what matters to them: each building's
 * doors, ladders, beds, seats, tables, work blocks and stores; the market's stalls and its displays of goods; wells
 * and benches; the crops, gathered into fields; and who the plan houses where. All of it in the city's own frame,
 * the same every time for the same plan, so it holds for a capital built long before anyone asked.
 */
class Census(val plan: CityPlan) {
    /** A door's lower half, opening toward [inward]. */
    data class Door(val at: At, val inward: Dir)

    /** A ladder up the wall at [x], [z], from [bottom] to [top], its rungs facing [facing] (the climber faces the other way). */
    data class Ladder(val x: Int, val z: Int, val bottom: Int, val top: Int, val facing: Dir)

    /** A bed's head block, the head toward [toward]; the foot is the block behind it. */
    data class Bed(val head: At, val toward: Dir) {
        val foot: At get() = At(head.x - toward.dx, head.y, head.z - toward.dz)
    }

    /** A chair or bench: a stair's low half, sat on facing [facing] (away from its back). */
    data class Seat(val at: At, val facing: Dir)

    /** A block someone works at (an anvil, an oven, a loom, a lectern...), facing [dir] if it faces anywhere. */
    data class Station(val at: At, val m: M, val dir: Dir?)

    /** Somewhere goods are kept: a chest (with what the plan filled it with) or a barrel. */
    data class Store(val at: At, val loot: Loot?, val dir: Dir?)

    /** One of the townsfolk as the plan sets them down (see [Canvas.resident]), in the building [lodge] if they live in one. */
    data class Planned(val folk: Folk, val at: At, val work: At?, val post: At?, val facing: Dir?, val home: At?, val lodge: Int)

    /** One building's fittings. [id] is the plan's building id, or [FARM] plus its place in the country's list. */
    class Lodge(val id: Int, val kind: String, val lot: Rect, val y: Int, val front: Dir) {
        val doors = ArrayList<Door>()
        val ladders = ArrayList<Ladder>()
        val beds = ArrayList<Bed>()
        val seats = ArrayList<Seat>()
        val tables = ArrayList<At>()
        val stations = ArrayList<Station>()
        val stores = ArrayList<Store>()
        val residents = ArrayList<Planned>()

        /** The lowest and highest blocks drawn for it. */
        var bottom = Int.MAX_VALUE
        var top = Int.MIN_VALUE

        val farm: Boolean get() = id >= FARM

        /** The floors people stand on, from the ground up: its own height and the foot and head of every ladder. */
        val floors: List<Int> by lazy {
            val ys = sortedSetOf(y)
            for (l in ladders) {
                ys.add(l.bottom)
                ys.add(l.top)
            }
            ys.toList()
        }

        override fun toString(): String = "$kind#$id"
    }

    /** A market stall: the prop, where its keeper stands (facing the customers), where customers stand, the counter's three cells. */
    class Stall(val prop: Prop, val keeper: At, val customer: At, val facing: Dir, val counter: List<At>, val goods: List<At>, val trade: M)

    /** A run of crops close together, the strip of a field or a kitchen garden, and the farm building nearest it. */
    class Field(val crops: List<At>, val bounds: Rect, val kinds: IntArray, val lodge: Int?)

    /** A display of the market's goods (see [Canvas.wares]): what it shows, its front, and what the spot held before (null: nothing). */
    data class Display(val at: At, val wares: Wares, val facing: Dir, val was: B?)

    /** A woodpile out in the country: the bottom of its middle logs, the way its three run, and the building it's by. */
    data class Woodpile(val at: At, val along: Dir, val lodge: Int?)

    val lodges = LinkedHashMap<Int, Lodge>()
    val residents = ArrayList<Planned>()
    val villagers = ArrayList<At>()
    val stalls = ArrayList<Stall>()
    val displays = ArrayList<Display>()
    val wells = ArrayList<At>()
    val lamps = ArrayList<At>()
    val benches = ArrayList<Seat>()
    val fields = ArrayList<Field>()
    val woodpiles = ArrayList<Woodpile>()

    /** Crops by column (see [M.CROP]: v picks the crop and, by fours, how far from ripe it starts). */
    private val crops = HashMap<Long, Pair<At, Int>>()

    init {
        val canvas = Recorder()
        val buildings = Buildings(plan, canvas)
        for (b in plan.buildings) {
            canvas.lodge = lodgeOf(b)
            buildings.draw(b)
        }
        plan.outskirts.buildings.forEachIndexed { i, fb -> lodges[FARM + i] = Lodge(FARM + i, fb.kind.name, fb.lot, fb.y, fb.front) }
        canvas.lodge = null
        Props(plan, canvas).draw()
        plan.harbour?.draw(canvas)
        plan.outskirts.draw(canvas)
        canvas.finish()
        for (p in plan.props) when (p.kind) {
            PropKind.STALL -> stalls.add(stall(p))
            PropKind.WELL -> wells.add(At(p.x, plan.level(p.x, p.z), p.z))
            PropKind.LAMP -> lamps.add(At(p.x, plan.level(p.x, p.z) + 3, p.z))
            else -> {}
        }
        for (p in plan.outskirts.props) when (p.kind) {
            FarmPropKind.WELL, FarmPropKind.TROUGH -> wells.add(At(p.x, p.y, p.z))
            FarmPropKind.WOODPILE -> woodpiles.add(Woodpile(At(p.x, p.y, p.z), p.facing, farmNear(p.x, p.z, WOODPILE_REACH)))
            else -> {}
        }
        gatherFields()
    }

    private fun lodgeOf(b: Building): Lodge = lodges.getOrPut(b.id) { Lodge(b.id, b.kind.name, b.lot, b.y, b.front) }

    /** The country building whose walls come nearest [x], [z], within [r] blocks: its lodge id. */
    private fun farmNear(x: Int, z: Int, r: Int): Int? {
        var best: Int? = null
        var nearest = Int.MAX_VALUE
        plan.outskirts.buildings.forEachIndexed { i, fb ->
            val d = maxOf(maxOf(fb.lot.x0 - x, 0, x - fb.lot.x1), maxOf(fb.lot.z0 - z, 0, z - fb.lot.z1))
            if (d <= r && d < nearest) {
                nearest = d
                best = FARM + i
            }
        }
        return best
    }

    /** The building whose ground [x], [z] is: a city lot, or the lot of one of the country's buildings. */
    fun lodgeAt(x: Int, z: Int): Lodge? {
        if (plan.grid.inside(x, z) && plan.grid[x, z] == Use.LOT) lodges[plan.grid.owner(x, z)]?.let { return it }
        plan.outskirts.buildings.forEachIndexed { i, fb -> if (fb.lot.contains(x, z)) return lodges[FARM + i] }
        return null
    }

    private fun stall(p: Prop): Stall {
        val front = p.facing
        val along = front.cw
        fun at(a: Int, b: Int, y: Int): At {
            val gy = plan.level(p.x, p.z) + y
            return At(p.x + along.dx * a - front.dx * b, gy, p.z + along.dz * a - front.dz * b)
        }
        val kind = Math.floorMod(p.v, Props.STALL_TRADES.size)
        return Stall(
            p, keeper = at(0, 1, 0), customer = at(0, -1, 0), facing = front,
            counter = listOf(at(-1, 0, 0), at(0, 0, 0), at(1, 0, 0)), goods = listOf(at(-1, 0, 1), at(1, 0, 1)),
            trade = Props.STALL_TRADES[kind],
        )
    }

    /** Crops within a step of each other make one field. */
    private fun gatherFields() {
        val left = HashSet(crops.keys)
        while (left.isNotEmpty()) {
            val first = left.first()
            left.remove(first)
            val queue = ArrayDeque<Long>().apply { add(first) }
            val members = ArrayList<At>()
            val kinds = IntArray(4)
            var x0 = Int.MAX_VALUE
            var z0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE
            var z1 = Int.MIN_VALUE
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                val (at, v) = crops[k] ?: continue
                members.add(at)
                kinds[Math.floorMod(v, 4)]++
                x0 = minOf(x0, at.x); z0 = minOf(z0, at.z); x1 = maxOf(x1, at.x); z1 = maxOf(z1, at.z)
                for (dx in -2..2) for (dz in -2..2) {
                    if (dx == 0 && dz == 0) continue
                    val n = key(at.x + dx, at.z + dz)
                    if (left.remove(n)) queue.add(n)
                }
            }
            val bounds = Rect(x0, z0, x1, z1)
            val cx = (x0 + x1) / 2
            val cz = (z0 + z1) / 2
            val near = lodges.values.filter { it.farm }.minByOrNull {
                val lx = (it.lot.x0 + it.lot.x1) / 2 - cx
                val lz = (it.lot.z0 + it.lot.z1) / 2 - cz
                lx * lx + lz * lz
            }
            fields.add(Field(members, bounds, kinds, near?.id))
        }
    }

    /** Keeps what people use as the plan is drawn, for the building being drawn if there is one. */
    private inner class Recorder : Canvas {
        override val clip = Rect(-plan.reach, -plan.reach, plan.reach, plan.reach)
        var lodge: Lodge? = null

        private val ladders = HashMap<Long, MutableList<Pair<Int, Dir>>>()
        private val stairs = HashMap<At, Dir>()
        private val outside = HashMap<At, Dir>()
        private val fences = HashSet<At>()
        private val carpets = HashSet<At>()
        private val slabs = ArrayList<At>()

        private fun where(x: Int, z: Int): Lodge? = lodge ?: lodgeAt(x, z)

        override fun set(x: Int, y: Int, z: Int, b: B) {
            val m = b.m
            if (m == M.AIR) return
            when (m) {
                M.CROP -> {
                    crops[key(x, z)] = At(x, y, z) to b.v
                    return
                }
                M.WATER -> return
                else -> {}
            }
            val l = where(x, z) ?: run {
                // Out in the streets and the country, only benches matter here
                if ((m == M.PLANK_STAIRS || m == M.STONE_STAIRS || m == M.FINE_STAIRS) && !b.top && b.dir != null) outside[At(x, y, z)] = b.dir
                return
            }
            if (y < l.bottom) l.bottom = y
            if (y > l.top) l.top = y
            val at = At(x, y, z)
            val dir = b.dir
            when (m) {
                M.DOOR -> if (b.part == 0 && dir != null) l.doors.add(Door(at, dir))
                M.LADDER -> if (dir != null) ladders.getOrPut(key(x, z)) { ArrayList() }.add(y to dir)
                M.BED -> if (b.part == 1 && dir != null) l.beds.add(Bed(at, dir))
                M.PLANK_STAIRS, M.STONE_STAIRS, M.FINE_STAIRS -> if (!b.top && dir != null) stairs[at] = dir
                M.FENCE -> fences.add(at)
                M.CARPET -> carpets.add(at)
                M.PLANK_SLAB -> if (b.top) slabs.add(at)
                M.BARREL -> l.stores.add(Store(at, null, dir))
                M.ANVIL, M.GRINDSTONE, M.SMITHING_TABLE, M.STONECUTTER, M.CARTOGRAPHY_TABLE, M.FLETCHING_TABLE, M.LOOM,
                M.CAULDRON, M.BREWING_STAND, M.COMPOSTER, M.LECTERN, M.CRAFTING_TABLE, M.FURNACE, M.SMOKER,
                M.BLAST_FURNACE, M.HAY, M.CRATE, M.BOOKSHELF,
                M.STEWPOT, M.HOB, M.BAKE_OVEN, M.SPIT, M.PREP_BOARD, M.PLATE, M.LARDER, M.MENU_BOARD -> l.stations.add(Station(at, m, dir))
                // A tavern's fire is a station too (and only there), so the kitchens' retrofit finds an old capital's hearth from its plan
                M.CAMPFIRE -> if (l.kind == Kind.TAVERN.name) l.stations.add(Station(at, m, dir))
                else -> {}
            }
        }

        override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) {}
        override fun clearing(x: Int, z: Int, y: Int) {}

        override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) {
            where(x, z)?.stores?.add(Store(At(x, y, z), loot, facing))
        }

        override fun board(x: Int, y: Int, z: Int, facing: Dir) {}

        override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) {
            if (what == Spawn.VILLAGER) villagers.add(At(x, y, z))
        }

        override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: Dir?, home: At?) {
            val l = (home ?: at).let { where(it.x, it.z) }
            val planned = Planned(folk, at, work, post, facing, home, l?.id ?: -1)
            residents.add(planned)
            l?.residents?.add(planned)
        }

        override fun wares(x: Int, y: Int, z: Int, wares: Wares, facing: Dir, was: B?) {
            displays.add(Display(At(x, y, z), wares, facing, was))
            set(x, y, z, B(M.WARES, facing, v = wares.ordinal))
        }

        /** Ladders into runs, stairs into seats (not the steps of a flight), fences with a cloth on into tables. */
        fun finish() {
            for ((k, rungs) in ladders) {
                val x = (k shr 32).toInt()
                val z = k.toInt()
                val l = lodgeAt(x, z) ?: continue
                val ys = rungs.map { it.first }.sorted()
                var start = ys.first()
                var prev = start
                for (y in ys.drop(1) + Int.MAX_VALUE) {
                    if (y == prev + 1) {
                        prev = y
                        continue
                    }
                    if (prev > start) l.ladders.add(Ladder(x, z, start, prev, rungs.first().second))
                    start = y
                    prev = y
                }
            }
            for ((at, dir) in stairs) {
                if (flight(stairs, at, dir)) continue
                val l = lodgeAt(at.x, at.z) ?: continue
                l.seats.add(Seat(at, dir.opposite))
            }
            for ((at, dir) in outside) if (!flight(outside, at, dir)) benches.add(Seat(at, dir.opposite))
            for (f in fences) if (At(f.x, f.y + 1, f.z) in carpets) lodgeAt(f.x, f.z)?.tables?.add(f)
            for (s in slabs) lodgeAt(s.x, s.z)?.tables?.add(s)
        }

        /** A step of a flight rather than a seat: another step up behind it, or down in front. */
        private fun flight(steps: Map<At, Dir>, at: At, dir: Dir): Boolean =
            steps[At(at.x + dir.dx, at.y + 1, at.z + dir.dz)] == dir || steps[At(at.x - dir.dx, at.y - 1, at.z - dir.dz)] == dir
    }

    companion object {
        /** Country buildings' ids start here, past any city building's. */
        const val FARM = 1_000_000

        /** How far from a building's walls a woodpile can be and still be its own (the back of a cottage's plot). */
        private const val WOODPILE_REACH = 12

        fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    }
}
