package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * The watch kept at a road's watchtower (living-world/design-influence.md section 3, "The works at work"). Where
 * [Works.watch] gives a tower, two of the capital's guards keep it by day and one of the night watch by night; with
 * fewer than four guards in town, one by day and none by night. Whose turn it is goes first come, first served, each
 * day (and each night, from one change of the watch at half past six to the next), so it's the guards who are about
 * who go. Their [Doing.WATCH] is kept there in place of their post or beat in the streets.
 *
 * They walk out to the tower (the bodies really go: out of everyone's sight, the walk is simply done), climb its
 * ladder to the platform, and keep the road from there: up at the parapet looking out along it (LOOKOUT), now and then
 * walking round the platform to another side; the first of them looking the road's way, out from town, the second
 * back along it. At dusk whoever's up there lights the beacon (LIGHT_BEACON), once a day, through the building's
 * [beacon] (the beacon itself is Part B's). The day watch eats up there at noon; when their watch is over they come
 * down the ladder ([down], from [Days.next]) before anything else of their day. Now and then one says it's quiet
 * ("streets.watch_quiet"), and [riders] has them call out when a band is seen on the road ("streets.watch_riders").
 *
 * A tower whose guards' bodies aren't about stands unmanned: nobody is made up to fill it. A tower with no ladder up
 * to its platform yet (or whose land isn't loaded) is watched from its foot.
 */
object Lookout {
    /**
     * A tower's beacon, as the building (Part B) has it: where its brazier stands, whether it's burning, and its
     * lighting. Set once at setup; with none set, the lookouts never light anything (they still keep the watch).
     */
    interface Beacon {
        /** Where works [w]'s beacon stands, to be lit; null if it has none yet. */
        fun at(level: ServerLevel, w: WorksView): BlockPos?

        /** Whether it's alight already (lit tonight by someone else, or by a player). */
        fun lit(level: ServerLevel, w: WorksView): Boolean = false

        /** [by] puts the flame to it: LIGHT_BEACON's [LIT_TICK]. */
        fun light(level: ServerLevel, w: WorksView, by: Townsperson)
    }

    /** The building's beacons (Part B sets it). */
    var beacon: Beacon? = null

    /** The tick of LIGHT_BEACON (Part F's clip) the flame goes in on, when [Beacon.light] is told. */
    const val LIT_TICK = 14

    /** Dusk, on the clock, when the beacon is lit: from the day watch's last half hour until dark. */
    private const val DUSK_FROM = 17.5
    private const val DUSK_TO = 19.5

    /** How far back the night's count goes from the day's: the morning's change of the watch (6:30) to the day's start (2:00). */
    private const val NIGHT_SHIFT = 4500L

    /** How long a capital's towers are known before they're asked for again, and a tower's platform before it's looked over again. */
    private const val KNOWN = 200L
    private const val SURVEYED = 1200L

    /** The fewest ticks between one lookout's "all quiet" and the next, at the same tower. */
    private const val QUIET_EVERY = 1800L

    // --- Whose turn it is ---

    /** One capital's turns at its towers: by road, the residents keeping each by day and by night. */
    private class Turns {
        var day = Long.MIN_VALUE
        val byDay = HashMap<Int, MutableList<Int>>()
        var night = Long.MIN_VALUE
        val byNight = HashMap<Int, MutableList<Int>>()
        var guards = 0
    }

    private val turns = HashMap<Long, Turns>()

    private class Known(val at: Long, val towers: List<WorksView>)

    private val known = HashMap<Long, Known>()

    /** The town's day for the day watch; for the night watch, the night (from 6:30 one morning to 6:30 the next). */
    private fun nightOf(time: Long): Long = Days.dayOf(time - NIGHT_SHIFT)

    /** The towers standing on [capital]'s roads that keep a watch now, one a road. */
    private fun towers(level: ServerLevel, capital: Long): List<WorksView> {
        val now = level.gameTime
        known[capital]?.let { if (now - it.at in 0 until KNOWN) return it.towers }
        val towers = Works.all(level, capital)
            .filter { it.kind == WorksKind.TOWER && it.state == WorksState.DONE && it.road >= 0 && it.site != null && Works.watch(level, capital, it.road) != null }
            .distinctBy { it.road }.sortedBy { it.road }
        known[capital] = Known(now, towers)
        return towers
    }

    /**
     * The road whose tower [life] (a guard) keeps this watch: theirs if they've had their turn given today (tonight, for
     * the night watch), else the first tower with a turn free; -1 for their post or beat in the streets.
     */
    fun post(level: ServerLevel, life: Life): Int {
        if (!Works.enabled() || life.resident.role != TownRole.GUARD || life.resident.id < 0) return -1
        val center = life.places.centre
        val capital = center.asLong()
        val towers = towers(level, capital)
        if (towers.isEmpty()) return -1
        val time = level.overworldClockTime
        val t = turns.getOrPut(capital) { Turns() }
        val today = Days.dayOf(time)
        if (t.day != today) {
            t.day = today
            t.byDay.clear()
            t.guards = Residents.get(level).of(center).count { it.alive && it.role == TownRole.GUARD }
        }
        val tonight = nightOf(time)
        if (t.night != tonight) {
            t.night = tonight
            t.byNight.clear()
        }
        val id = life.resident.id
        val mine = if (life.nightWatch) t.byNight else t.byDay
        val roads = towers.map { it.road }
        mine.entries.firstOrNull { id in it.value }?.let { if (it.key in roads) return it.key }
        val turns = when {
            t.guards < 4 -> if (life.nightWatch) 0 else 1
            else -> if (life.nightWatch) 1 else 2
        }
        if (turns == 0) return -1
        val residents = Residents.get(level)
        for (road in roads) {
            val keeping = mine.getOrPut(road) { ArrayList() }
            // The dead (or gone) give up their turn
            keeping.removeIf { other -> residents[other]?.alive != true }
            if (keeping.size < turns) {
                keeping += id
                return road
            }
        }
        return -1
    }

    /** Which tower's watch resident [id] of [capital] has today, for `/guildmark capital who`; -1 for none. */
    fun turnOf(capital: Long, id: Int, nightWatch: Boolean): Int {
        val t = turns[capital] ?: return -1
        val mine = if (nightWatch) t.byNight else t.byDay
        return mine.entries.firstOrNull { id in it.value }?.key ?: -1
    }

    // --- The tower ---

    /**
     * A tower as its lookouts know it: its lot, its ladder and the heights it lets off at, below and on the platform,
     * the platform's places to stand (at its edge, facing out, the road's way first) and where they were looked over.
     */
    private class Tower(val lot: BoundingBox, val ladder: CityPlaces.Ladder?, val bottom: Int, val top: Int, val deck: List<Spot>, val at: Long)

    private val surveyed = HashMap<Int, Tower>()

    /** [w]'s tower looked over (again, every [SURVEYED] ticks); null while its land isn't loaded. */
    private fun survey(level: ServerLevel, w: WorksView): Tower? {
        val site = w.site ?: return null
        val now = level.gameTime
        surveyed[w.id]?.let { if (now - it.at in 0 until SURVEYED) return it }
        val lot = site.lot
        if (!level.isLoaded(BlockPos(lot.minX(), lot.minY(), lot.minZ())) || !level.isLoaded(BlockPos(lot.maxX(), lot.maxY(), lot.maxZ()))) return null
        // The tallest ladder in the lot
        var best: CityPlaces.Ladder? = null
        val seen = HashSet<Long>()
        for (x in lot.minX()..lot.maxX()) for (z in lot.minZ()..lot.maxZ()) {
            var y = lot.minY()
            while (y <= lot.maxY()) {
                val pos = BlockPos(x, y, z)
                val state = level.getBlockState(pos)
                if (state.block !is LadderBlock || !seen.add(pos.asLong())) {
                    y++
                    continue
                }
                var top = y
                while (top + 1 <= lot.maxY() + 1 && level.getBlockState(BlockPos(x, top + 1, z)).block is LadderBlock) top++
                val ladder = CityPlaces.Ladder(x, z, y, top, state.getValue(LadderBlock.FACING))
                if (best == null || top - y > best.top - best.bottom) best = ladder
                y = top + 1
            }
        }
        val exits = best?.let { Ways.exits(level, it) }.orEmpty()
        val ladder = best?.takeIf { exits.size >= 2 && exits.last() - exits.first() >= 3 && Ways.clear(level, it, exits.first(), exits.last()) }
        val bottom = exits.firstOrNull() ?: lot.minY()
        val top = exits.lastOrNull() ?: lot.minY()
        val deck = if (ladder != null) deck(level, lot, ladder, top, w) else listOf()
        return Tower(lot, ladder, bottom, top, deck, now).also { surveyed[w.id] = it }
    }

    /** The platform's places to stand at [top]: its edge first, each facing out, the one looking out from town first. */
    private fun deck(level: ServerLevel, lot: BoundingBox, ladder: CityPlaces.Ladder, top: Int, w: WorksView): List<Spot> {
        val cx = (lot.minX() + lot.maxX()) / 2.0 + 0.5
        val cz = (lot.minZ() + lot.maxZ()) / 2.0 + 0.5
        val spots = ArrayList<Pair<Spot, Double>>()
        for (x in lot.minX()..lot.maxX()) for (z in lot.minZ()..lot.maxZ()) {
            if (x == ladder.x && z == ladder.z) continue
            val pos = BlockPos(x, top, z)
            if (pos == ladder.landing(top) || !Places.standable(level, pos)) continue
            val dx = x + 0.5 - cx
            val dz = z + 0.5 - cz
            val facing = if (abs(dx) >= abs(dz)) (if (dx >= 0) Direction.EAST else Direction.WEST) else (if (dz >= 0) Direction.SOUTH else Direction.NORTH)
            spots += Spot(pos, facing) to (dx * dx + dz * dz)
        }
        // Out from town: the way the road runs on past the tower
        val town = BlockPos.of(w.capital)
        val ox = cx - (town.x + 0.5)
        val oz = cz - (town.z + 0.5)
        val out = if (abs(ox) >= abs(oz)) (if (ox >= 0) Direction.EAST else Direction.WEST) else (if (oz >= 0) Direction.SOUTH else Direction.NORTH)
        return spots.sortedWith(compareBy<Pair<Spot, Double>>({ if (it.first.facing == out) 0 else if (it.first.facing == out.opposite) 1 else 2 }, { -it.second }))
            .map { it.first }.take(12)
    }

    /** The tower on [road] into [life]'s capital that keeps a watch, if it stands. */
    private fun tower(level: ServerLevel, life: Life, road: Int): WorksView? =
        towers(level, life.places.centre.asLong()).firstOrNull { it.road == road }

    // --- Up on the platform ---

    /** Someone up on a tower's platform: which tower and road, its ladder and heights, when they last ate up there. */
    private class Aloft(val works: Int, val road: Int, val ladder: CityPlaces.Ladder, val top: Int, val bottom: Int, val lot: BoundingBox) {
        var tries = 0
        var ate = Long.MIN_VALUE
    }

    private val aloft = WeakHashMap<Townsperson, Aloft>()

    /** The days the beacon was last lit at each tower (by works id), so it's lit once an evening. */
    private val lit = HashMap<Int, Long>()

    /** When each tower last heard "all quiet". */
    private val quiet = HashMap<Int, Long>()

    /** Times a guard couldn't get up a tower today, by resident id: twice, and they watch from its foot. */
    private val balked = HashMap<Int, Pair<Long, Int>>()

    /** Whether [p] is up on [up]'s platform still (not fallen or knocked off it). */
    private fun there(p: Townsperson, up: Aloft): Boolean =
        abs(p.y - up.top) < 2.0 && p.x >= up.lot.minX() - 1 && p.x <= up.lot.maxX() + 2 && p.z >= up.lot.minZ() - 1 && p.z <= up.lot.maxZ() + 2

    /**
     * What a guard does on a watch at a tower: out to it and up its ladder, or up there keeping the road; null when
     * it's not their turn at one (their post or beat, as ever).
     */
    fun keep(p: Townsperson, life: Life): Task? {
        val level = p.level() as? ServerLevel ?: return null
        val road = post(level, life)
        life.tower = road
        if (road < 0) return null
        val w = tower(level, life, road) ?: return null
        val up = aloft[p]?.takeIf { there(p, it) }
        if (up == null) aloft.remove(p)
        if (up != null && up.works != w.id) return climbDown(p, up)
        val t = survey(level, w)
        if (up != null) return onDeck(level, p, life, w, t, up)
        val ladder = t?.ladder
        val gaveUp = balked[life.resident.id]?.let { it.first == Days.dayOf(level.overworldClockTime) && it.second >= 2 } == true
        if (t == null || ladder == null || t.deck.isEmpty() || gaveUp) return atFoot(level, p, life, w)
        val landing = ladder.landing(t.bottom)
        val climb = Later(p) {
            if (p.blockPosition().distSqr(landing) > 4.0) {
                failed(level, life)
                null
            } else {
                Walk(p, landing, 0.45, exact = true).andThen(Climb(p, ladder, t.bottom, t.top)).andThen(Arrive(p, w, road, ladder, t, life))
            }
        }
        return Ways.to(p, life.places, landing, Jobs.WALK, near = 1.5).andThen(climb)
    }

    private fun failed(level: ServerLevel, life: Life) {
        val day = Days.dayOf(level.overworldClockTime)
        val was = balked[life.resident.id]?.takeIf { it.first == day }?.second ?: 0
        balked[life.resident.id] = day to was + 1
        if (balked.size > 256) balked.entries.removeIf { it.value.first != day }
    }

    /** Off the top of the ladder: on the platform now (if they got there; if not, it counts against the climb). */
    private class Arrive(p: Townsperson, private val w: WorksView, private val road: Int, private val ladder: CityPlaces.Ladder, private val t: Tower, private val life: Life) : Task(p) {
        override val instant: Boolean get() = true

        override fun start() {
            val up = Aloft(w.id, road, ladder, t.top, t.bottom, t.lot)
            if (there(p, up)) aloft[p] = up else failed(level, life)
        }

        override fun run(): Boolean = false
    }

    /** The watch kept at the tower's foot, by its door or post: no way up, or the land up there isn't loaded. */
    private fun atFoot(level: ServerLevel, p: Townsperson, life: Life, w: WorksView): Task {
        val site = w.site!!
        val stand = listOf(site.post, site.origin.relative(site.facing, 2), site.origin).firstOrNull { level.isLoaded(it) && Places.standable(level, it) } ?: site.post
        if (p.blockPosition().distSqr(stand) > 36.0) return Ways.to(p, life.places, stand, Jobs.WALK, near = 2.0)
        val out = outward(w, site.origin)
        return Work(p, Spot(stand, out), Jobs.top(stand.relative(out, 16), 1.6), listOf(
            Step(Activity.STAND_GUARD, Activity.STAND_GUARD.ticks * (2 + p.random.nextInt(3))),
            Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks * (1 + p.random.nextInt(2))),
        ))
    }

    /** The way out from town, past [at]. */
    private fun outward(w: WorksView, at: BlockPos): Direction {
        val town = BlockPos.of(w.capital)
        val dx = (at.x - town.x).toDouble()
        val dz = (at.z - town.z).toDouble()
        return if (abs(dx) >= abs(dz)) (if (dx >= 0) Direction.EAST else Direction.WEST) else (if (dz >= 0) Direction.SOUTH else Direction.NORTH)
    }

    /** Up on the platform: the beacon at dusk, or a while at the parapet looking out, mostly from their own side. */
    private fun onDeck(level: ServerLevel, p: Townsperson, life: Life, w: WorksView, t: Tower?, up: Aloft): Task {
        val today = Days.dayOf(level.overworldClockTime)
        val hour = hour(level)
        val beacon = beacon
        if (beacon != null && hour >= DUSK_FROM && hour < DUSK_TO && lit[w.id] != today && !beacon.lit(level, w)) {
            val at = beacon.at(level, w)
            val stand = at?.let { Places.beside(level, it, p.blockPosition()) }
            if (at != null && stand != null && abs(stand.pos.y - up.top) <= 1) {
                lit[w.id] = today
                val light = Step(Activity.LIGHT_BEACON)
                return Work(p, stand, Jobs.top(at, 0.6), listOf(light, Step(Activity.NONE, 20), Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks * 2)), onTick = { s, k ->
                    if (s === light && k == LIT_TICK) beacon.light(level, w, p)
                })
            }
        }
        val deck = t?.deck.orEmpty()
        if (deck.isEmpty()) return Idle(p, 200, Activity.LOOKOUT, chatty = false)
        // Theirs is the first side free of the other lookout; now and then a walk round to another
        val free = deck.filter { Claims.free(level, it.pos, p) }
        if (free.isEmpty()) return Idle(p, 100, Activity.LOOKOUT, chatty = false)
        val spot = if (p.random.nextInt(3) == 0) free[p.random.nextInt(free.size)] else free.first()
        val first = Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks * (3 + p.random.nextInt(4)))
        val steps = listOf(first, Step(Activity.NONE, 30 + p.random.nextInt(40)), Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks * (1 + p.random.nextInt(2))))
        return Work(p, spot, Jobs.top(spot.pos.relative(spot.facing, 24), 1.6), steps, onTick = { s, k ->
            if (s === first && k == 60) allQuiet(level, p, w)
        })
    }

    /** Now and then, to whoever's up there or below in hearing: all quiet on the road. */
    private fun allQuiet(level: ServerLevel, p: Townsperson, w: WorksView) {
        val now = level.gameTime
        if (now - (quiet[w.id] ?: Long.MIN_VALUE / 2) < QUIET_EVERY || p.random.nextInt(3) != 0) return
        if (Words.say(p, "streets.watch_quiet", "on watch at the tower")) quiet[w.id] = now
    }

    /** The clock's hour as Days reckons it (6.5 for half past six). */
    private fun hour(level: ServerLevel): Double = (Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0

    /** Down the ladder from [up]'s platform, and off the tower. */
    private fun climbDown(p: Townsperson, up: Aloft): Task {
        val landing = up.ladder.landing(up.top)
        return Walk(p, landing, 0.45, exact = true).andThen(Climb(p, up.ladder, up.top, up.bottom)).andThen(Acts.hold(p, ItemStack.EMPTY)).andThen(Later(p) {
            if (abs(p.y - up.bottom) < 1.5) aloft.remove(p)
            null
        })
    }

    /**
     * Before anything else of [life]'s day ([Days.next]): someone up a tower whose watch there is over comes down its
     * ladder first; at noon the day watch eats up there instead, and keeps looking out till the hour's done. Null for
     * everyone else (and at once, for everyone not up a tower).
     */
    fun down(p: Townsperson, life: Life, stretch: Stretch): Task? {
        if (aloft.isEmpty()) return null
        val up = aloft[p] ?: return null
        if (!there(p, up)) {
            aloft.remove(p)
            return null
        }
        val level = p.level() as? ServerLevel ?: return null
        if (stretch.doing == Doing.WATCH && post(level, life) == up.road) return null
        if (stretch.doing == Doing.LUNCH && life.tower == up.road) {
            life.step = life.script.size
            val today = Days.dayOf(level.overworldClockTime)
            if (up.ate != today) {
                up.ate = today
                return Acts.hold(p, ItemStack(Items.BREAD)).andThen(Idle(p, 300 + p.random.nextInt(200), Activity.EAT, chatty = false)).andThen(Acts.hold(p, ItemStack.EMPTY))
            }
            return Idle(p, 200, Activity.LOOKOUT, chatty = false)
        }
        // Stuck up there (the shaft blocked): their day goes on from where they stand
        if (++up.tries > 3) {
            aloft.remove(p)
            return null
        }
        return climbDown(p, up)
    }

    /** Whether [p] is up on a tower's platform. */
    fun up(p: Townsperson): Boolean = aloft[p]?.let { there(p, it) } == true || io.github.vaspyyy.guildmark.village.live.days.VillageWatch.up(p)

    /**
     * For `/guildmark capital scene lookout`, with no tower to keep: [p] keeps a watch where they stand, as they would
     * on a platform. A long look out from town (all quiet called), a walk round to the side and a look from there,
     * the beacon's torch dipped (LIGHT_BEACON, at nothing), and riders pointed out and called.
     */
    internal fun standIn(level: ServerLevel, p: Townsperson): Task {
        val town = p.town?.center ?: p.blockPosition()
        val here = p.blockPosition()
        val dx = (here.x - town.x).toDouble()
        val dz = (here.z - town.z).toDouble()
        val out = if (abs(dx) >= abs(dz)) (if (dx >= 0) Direction.EAST else Direction.WEST) else (if (dz >= 0) Direction.SOUTH else Direction.NORTH)
        val side = listOf(here.relative(out.clockWise, 2), here.relative(out.counterClockWise, 2)).firstOrNull { level.isLoaded(it) && Places.standable(level, it) } ?: here
        val far = { at: BlockPos -> Jobs.top(at.relative(out, 24), 1.6) }
        val first = Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks * 2)
        val looking = Work(p, Spot(here, out), far(here), listOf(first, Step(Activity.NONE, 30)), onTick = { s, k ->
            if (s === first && k == 60) Words.say(p, "streets.watch_quiet", "on watch (the scene command's)")
        })
        val round = Work(p, Spot(side, out), far(side), listOf(Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks * 2)))
        val light = Step(Activity.LIGHT_BEACON)
        val point = Step(Activity.POINT)
        val beacon = Work(p, Spot(here, out), far(here), listOf(light, Step(Activity.NONE, 20), Step(Activity.LOOKOUT, Activity.LOOKOUT.ticks), point), onTick = { s, k ->
            if (s === point && k == 0) Words.say(p, "streets.watch_riders", "riders on the road (the scene command's)", force = true)
        })
        return looking.andThen(round).andThen(beacon)
    }

    /**
     * Riders seen on [road] into [capital] (the caravans' band, or the building's horn): every lookout up that road's
     * tower turns to [at] (or out along the road), points, and calls it ("streets.watch_riders"). True if anyone did.
     */
    fun riders(level: ServerLevel, capital: Long, road: Int, at: BlockPos? = null): Boolean {
        var any = false
        for ((p, up) in aloft.entries.toList()) {
            if (up.road != road || p.level() !== level || !p.isAlive || !there(p, up)) continue
            val w = Works.get(level, up.works)?.takeIf { it.capital == capital } ?: continue
            val towards = at?.let { Vec3.atCenterOf(it) } ?: Vec3.atCenterOf(BlockPos.containing(p.position()).relative(outward(w, p.blockPosition()), 24))
            p.navigation.stop()
            p.face(p.yawTo(towards))
            p.lookAt(towards)
            p.act(Activity.POINT)
            Words.say(p, "streets.watch_riders", "riders on the road", force = true)
            any = true
        }
        return any
    }

    /** Everything forgotten, as the server stops. */
    fun forget() {
        turns.clear()
        known.clear()
        surveyed.clear()
        aloft.clear()
        lit.clear()
        quiet.clear()
        balked.clear()
    }
}
