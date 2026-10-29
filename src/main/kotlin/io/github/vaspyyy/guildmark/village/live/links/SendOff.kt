package io.github.vaspyyy.guildmark.village.live.links

import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRec
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Lookout
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Pull
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.village.live.Link
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.hypot

/**
 * What a village sees of its wagons (living-world/villages/design.md section 9.4): its own people at its road end, played while a
 * player is within [PLAYER] blocks of its bell. Nothing waits for these scenes and nothing depends on them: the goods are in the
 * record ([VillageWagons]) whichever way, and the scenes show what the record says, when it says it.
 *
 * - **The send-off.** A little before a wagon of one of the village's roads sets out from the start of its leg ([LEAD_OUT] clock
 *   ticks before `Caravans.nextWagon`), the village's helpers at the wagon (`Hands.Crew.wagon`: its carters) wheel their handcart
 *   out to the road end, heaped with a sack of each good the village has in glut (`Economy.exports`: what a wagon may take), park
 *   it beside the road and wait, and the second counts the sacks ("Count them twice."). When the wagon sets out they heave aboard
 *   the sacks of what it really took (its record's [CaravanRec.fromVillage]), "That's the lot.", wave it off and wheel the rest back
 *   to the green. If none sets out by then (another road's turn after all): "No wagon again.", and back.
 * - **The return.** A little before the village's wagon is back at the start of its leg ([LEAD_IN] before its record's end), they
 *   go out with the cart empty and wait, facing the road it comes in by. When it is back ([VillageWagons.home] has posted the
 *   goods) they take down a sack of each good it brought and heap the cart (the second: "Count them twice."; the first: "That's the
 *   lot."), pull it in to the baker's door (else the green) and carry the sacks in one at a time. A waylaid wagon never comes:
 *   they wait out its time ([waylaid]), "No wagon again.", and go back with the cart empty.
 * - **The crowd.** As the wagon is due ([CROWD] before), up to five of the village's folk with nothing pressing come out too
 *   ([Greeters]): they wave it in ("The wagon's in sight!") or off, count the sacks, and go back to their day.
 *
 * Each body is a [Director] of its scene. A scene ends at once (everyone back to their day, a cart let go where it stands, to go
 * once nobody looks) when one is hurt or set on, nobody is near any more, the wagons are switched off, or it has gone on for
 * [MOST] ticks. With carts off ([Handcarts.enabled]) the carters carry a sack each. Looked for every 200 ticks ([look], from
 * [VillageLinks]) in live villages only.
 */
object SendOff {
    /** How near the bell (blocks) a player must be for the scenes to be played. */
    const val PLAYER = 96.0

    /** Clock ticks before a wagon sets out that the carters set off with the cart, and before one is back that they go to meet it. */
    const val LEAD_OUT = 800L
    const val LEAD_IN = 800L

    /** Clock ticks before the wagon is due that the folk come out to see it. */
    const val CROWD = 400L

    /** Clock ticks past the time it was due that they wait for a wagon that does not come. */
    const val LATE_AFTER = 400L

    /** How far along its leg (blocks) a wagon coming in is in sight of the road end. */
    const val SIGHT = 40.0

    /** The longest a scene goes on (game ticks), and how long with nobody near before it ends. */
    const val MOST = 9600L
    const val AWAY = 200L

    /** How near the road end (blocks) a carter must be to go out. */
    private const val REACH = 96.0

    /** A road end further from the bell than this (blocks) is not the village's: the leg starts out of sight. */
    private const val END = 64.0

    /** Sacks on a cart at most (Handcarts' bed: six on the floor, two on top). */
    const val SACKS = 8

    /** How far out past the village's edge (blocks) the road end is, on a road whose leg starts out of sight. */
    private const val EDGE = 6

    /** Below this much of a good, no sack of it. */
    private const val EPS = 0.01

    /** How a hand speaks across the road end. */
    internal const val SPEAK = 32.0

    /** The building whose door the return's sacks are carried to. */
    private const val BAKERY = "BAKERY"

    private val scenes = ArrayList<Scene>()

    /** The scenes started, by a name of their wagon, and when (game ticks): one is played once. */
    private val played = HashMap<String, Long>()

    /** A village's wagon that was waylaid, and the clock time it was due back: its return is waited for in vain. */
    private class Late(val caravan: Long, val capital: Long, val road: Int, val due: Long)

    private val late = HashMap<Long, Late>()

    // ---- When ----

    /** Every 200 ticks (from [VillageLinks]): a scene started for each live village with a player near whose wagon is due. */
    fun look(level: ServerLevel) {
        if (!VillageLinks.enabled()) return
        val clock = level.overworldClockTime
        val now = level.gameTime
        played.values.removeIf { now - it > MOST * 2 }
        late.values.removeIf { clock > it.due + LATE_AFTER }
        val data = CaravanData.get(level)
        for (rec in Villages.awake(level)) {
            if (rec.state != Live.LIVE) continue
            if (scenes.any { it.key == rec.key }) continue
            val places = Villages.places(level, rec.key) ?: continue
            if (!near(level, BlockPos.of(rec.key))) continue
            val links = VillageLinks.running(level, rec.key)
            if (links.isEmpty()) continue
            // One of its wagons coming back
            val back = data.caravans.filter { it.village == rec.key && it.phase == Phase.LEAVING && it.until - clock in 0..LEAD_IN }.minByOrNull { it.until }
            val backBy = back?.let { c -> links.firstOrNull { it.capital == c.capital && it.road == c.road } }
            if (back != null && backBy != null) {
                start(level, rec, places, backBy, false, back.id, back.until)
                continue
            }
            // One that was waylaid, due back now
            val l = late[rec.key]
            val lateBy = l?.let { w -> links.firstOrNull { it.capital == w.capital && it.road == w.road } }
            if (l != null && lateBy != null && l.due - clock in 0..LEAD_IN) {
                start(level, rec, places, lateBy, false, l.caravan, l.due)
                continue
            }
            // One setting out
            for (link in links) {
                // A Closed road's wagon goes only with an escort, and takes nothing
                if (RoadPeril.stage(data, link.capital, link.road) == RoadPeril.Stage.CLOSED) continue
                val out = Caravans.nextWagon(level, link.capital, link.road) ?: continue
                if (out - clock !in 0..LEAD_OUT) continue
                if (start(level, rec, places, link, true, -1L, out) != null) break
            }
        }
    }

    /** Each tick (from [VillageLinks]): the scenes under way looked over. */
    fun tick(level: ServerLevel) {
        if (scenes.isEmpty()) return
        scenes.removeIf { !it.tick(level) }
    }

    /** The wagon [c] of the village [key] was waylaid: its return, when it was due, is waited for in vain. */
    fun waylaid(level: ServerLevel, key: Long, c: CaravanRec) {
        // Due back as the timetable had it: in at the stand on the hour, an hour there, and the leg back
        val due = c.hour * HOUR + HOUR + (c.hour * HOUR - c.setOut) + c.held
        if (due <= level.overworldClockTime) return
        late[key] = Late(c.id, c.capital, c.road, due)
    }

    /**
     * The carters go out to meet the village [key]'s wagon [caravan] now (`village cart wagon`), if a player is near: the return
     * as it is played when a wagon is back. Null if nobody goes (nobody near, no road end, nobody free).
     */
    fun meet(level: ServerLevel, key: Long, caravan: Long): Scene? {
        if (!VillageLinks.enabled() || scenes.any { it.key == key }) return null
        val rec = Villages.byKey(level, key)?.takeIf { it.state == Live.LIVE } ?: return null
        val places = Villages.places(level, key) ?: return null
        if (!near(level, BlockPos.of(key))) return null
        val trip = VillageWagons.book(level, key)?.trips?.lastOrNull { it.caravan == caravan } ?: return null
        val link = VillageLinks.running(level, key).firstOrNull { it.capital == trip.capital && it.road == trip.road } ?: return null
        return start(level, rec, places, link, false, caravan, level.overworldClockTime)
    }

    /** What the village [key]'s people are at by the road now, in a few words, or null. */
    fun doing(key: Long): String? = scenes.firstOrNull { it.key == key }?.doing

    /** A watch on the village [key]'s wagon due soonest (one coming back, else one setting out), for folk called out to it ([Greeters.call]). */
    fun watch(level: ServerLevel, key: Long): Watch? {
        if (!VillageLinks.enabled()) return null
        val rec = Villages.byKey(level, key) ?: return null
        val clock = level.overworldClockTime
        val data = CaravanData.get(level)
        val links = VillageLinks.running(level, key)
        data.caravans.filter { it.village == key && it.phase == Phase.LEAVING }.minByOrNull { it.until }?.let { c ->
            val link = links.firstOrNull { it.capital == c.capital && it.road == c.road } ?: return null
            return WagonWatch(key, link, false, c.id, c.until, seen(rec, link))
        }
        for (link in links) {
            val c = data.caravans.firstOrNull { it.capital == link.capital && it.road == link.road && wagon(it) && it.phase == Phase.PLANNED && it.setOut - clock <= LEAD_OUT }
                ?: continue
            return WagonWatch(key, link, true, c.id, c.setOut, seen(rec, link))
        }
        return null
    }

    /** Everyone let go (the server stopping). */
    fun forget() {
        for (s in scenes) s.end()
        scenes.clear()
        played.clear()
        late.clear()
    }

    /** A scene for [rec]'s wagon by [link]: setting out at [time] ([out]), or [caravan] back at [time]. Null if none is played. */
    private fun start(level: ServerLevel, rec: VillageRec, places: VillagePlaces, link: Link, out: Boolean, caravan: Long, time: Long): Scene? {
        val name = (if (out) "out " else "in ") + rec.key + " " + time
        if (name in played) return null
        val end = end(level, rec, link) ?: return null
        if (!level.isPositionEntityTicking(end.cart)) return null
        played[name] = level.gameTime
        // Out: a sack of each good in glut; nothing to take, and the carters stay at home
        val goods = if (out) glut(level, rec.key) else emptyList()
        val carters = if (out && goods.isEmpty()) emptyList() else carters(level, places, end.cart)
        // Out with nobody to load it, the folk see the wagon off only where they can see it
        if (carters.isEmpty() && out && !end.seen) return null
        val watch = WagonWatch(rec.key, link, out, caravan, time, end.seen)
        val scene = Scene(rec.key, link, end, out, watch, time, level.gameTime, goods)
        for ((i, p) in carters.withIndex()) {
            val hand = Hand(scene, p, i == 0)
            p.standUp()
            p.routine.interrupt()
            p.navigation.stop()
            p.director = hand
            scene.hands += hand
        }
        scenes += scene
        return scene
    }

    // ---- Where ----

    /**
     * Where the village meets a wagon by [link]: on the road ([road]: where the wagon stands at the start of its leg, or where the
     * road leaves the village when the leg starts out of sight, [seen] false), ground beside it for the cart ([cart]) and the second
     * carter ([mate]), and a point further out along the road ([out]: the way the wagon goes and comes).
     */
    class End(val road: Vec3, val cart: BlockPos, val mate: BlockPos, val out: Vec3, val seen: Boolean)

    private fun end(level: ServerLevel, rec: VillageRec, link: Link): End? {
        val leg = CaravanRoutes.cached(link.capital, link.road) ?: return null
        val bell = BlockPos.of(rec.key)
        val road: Vec3
        val out: Vec3
        val seen = seen(rec, link)
        if (seen) {
            road = leg.start
            out = leg.at(ALONG)
        } else {
            // The leg starts far out (a long road): the road end is where the road leaves the village
            val way = RoadNetwork.get(level.server.overworld()).road(link.road)?.pointsFrom(bell) ?: return null
            val edge = (rec.radius + EDGE).toDouble()
            val i = way.indexOfFirst { flat(it, bell) >= edge }
            if (i < 0 || flat(way[i], bell) > END) return null
            road = Vec3.atBottomCenterOf(way[i])
            out = Vec3.atBottomCenterOf(way.getOrElse(i + 3) { way.last() })
        }
        var dx = out.x - road.x
        var dz = out.z - road.z
        val len = hypot(dx, dz).coerceAtLeast(0.01)
        dx /= len
        dz /= len
        // Beside the road, on the village's side first
        val sx = -dz
        val sz = dx
        val toBell = (bell.x + 0.5 - road.x) * sx + (bell.z + 0.5 - road.z) * sz
        for (side in if (toBell >= 0) doubleArrayOf(1.0, -1.0) else doubleArrayOf(-1.0, 1.0)) {
            for (d in doubleArrayOf(3.0, 4.0, 2.5, 5.0)) {
                val cart = ground(level, BlockPos.containing(road.x + sx * side * d, road.y + 0.5, road.z + sz * side * d)) ?: continue
                val mate = ground(level, BlockPos.containing(road.x + sx * side * (d - 1.2) + dx * 2.0, road.y + 0.5, road.z + sz * side * (d - 1.2) + dz * 2.0)) ?: cart
                return End(road, cart, mate, out, seen)
            }
        }
        return null
    }

    /** Whether the wagons' leg by [link] starts near enough [rec]'s bell for the village to see the wagon stand there. */
    private fun seen(rec: VillageRec, link: Link): Boolean {
        val leg = CaravanRoutes.cached(link.capital, link.road) ?: return false
        val bell = BlockPos.of(rec.key)
        val dx = leg.start.x - (bell.x + 0.5)
        val dz = leg.start.z - (bell.z + 0.5)
        return hypot(dx, dz) <= END
    }

    /** Ground to stand on at [at], or a block or two above or below it; null where that is not loaded (nothing is loaded for a scene). */
    internal fun ground(level: ServerLevel, at: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, -1, 1, -2, 2)) {
            val pos = at.above(dy)
            if (!level.isLoaded(pos)) return null
            if (Places.standable(level, pos)) return pos
        }
        return null
    }

    internal fun near(level: ServerLevel, at: BlockPos): Boolean =
        level.getNearestPlayer(at.x + 0.5, at.y.toDouble(), at.z + 0.5, PLAYER, false) != null

    private fun flat(a: BlockPos, b: BlockPos): Double = hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble())

    // ---- Who and what ----

    /** A sack of each good the village [key] has in glut, the most first. */
    private fun glut(level: ServerLevel, key: Long): List<Good> {
        val v = VillageMarkets.rec(level, key) ?: return emptyList()
        return Economy.exports(v.entry.market, v.entry.town).filter { it.second > EPS && !Handcarts.stack(it.first).isEmpty }
            .sortedByDescending { it.second }.map { it.first }.take(SACKS)
    }

    /** The village's helpers at the wagon today who are free to go out to [at]: its carters first, two at most. */
    private fun carters(level: ServerLevel, places: VillagePlaces, at: BlockPos): List<Townsperson> {
        val residents = Residents.get(level)
        val c = Vec3.atCenterOf(at)
        return Hands.of(level, places).wagon.mapNotNull { id -> residents[id]?.uuid?.let { level.getEntity(it) as? Townsperson } }
            .filter { able(level, it) && it.distanceToSqr(c) < REACH * REACH }
            .sortedWith(compareBy({ if (it.life?.carter == true) 0 else 1 }, { it.resident }))
            .take(2)
    }

    /** Whether [p] can be taken off what they are at for the wagon: up and about, in nobody's charge, hands empty, in no danger. */
    private fun able(level: ServerLevel, p: Townsperson): Boolean {
        if (!p.isAlive || p.isRemoved || p.director != null || p.captiveOf >= 0 || p.isLeader || p.watcher || p.crowd || p.isPassenger) return false
        if (p.isSleeping || p.attending != null || p.target != null || p.hurtTime > 0 || p.activity == Activity.FLEE) return false
        if (!p.carried.isEmpty || Lookout.up(p) || p.routine.task is Pull) return false
        return level.isPositionEntityTicking(p.blockPosition())
    }

    internal fun wagon(c: CaravanRec): Boolean = c.run == Run.WAGON || c.run == Run.CONVOY

    /** Says [line] across the road end, for as long as the take lasts. */
    internal fun say(p: Townsperson, line: String) {
        val ticks = TalkLines.ticks(line).toLong()
        p.say(line, force = true, hearing = SPEAK, ticks = if (ticks > 0) ticks else Townsperson.SPEECH_TICKS)
    }

    internal fun face(p: Townsperson, at: Vec3) {
        p.face(p.yawTo(at))
        p.lookAt(at)
    }

    /** Clock ticks in a market hour; blocks along the leg the "further out" point is. */
    private const val HOUR = 1000L
    private const val ALONG = 10.0

    // ---- A scene ----

    /** Where a hand is in their part. */
    enum class Stage { START, PARK, SIDE, WAIT, HEAVE, TAKE_DOWN, AFTER, FETCH, TAKE, BACK, PARK_HOME, UNLOAD, CARRY, DONE }

    /**
     * One wagon's scene at the village [key]'s road end: setting out at [time] ([out]) or back at [time]. Its carters ([hands], the
     * first of whom pulls the cart) and its crowd ([gathering]) play it out; [watch] says what the wagon is at.
     */
    class Scene internal constructor(
        val key: Long, val link: Link, val end: End, val out: Boolean, internal val watch: WagonWatch, val time: Long, private val started: Long,
        goods: List<Good>,
    ) {
        internal val hands = ArrayList<Hand>()

        /** What is heaped on the cart, in order; the sacks still to be heaved aboard or taken down. */
        internal val heap = ArrayList<Good>(goods)
        internal val pending = ArrayDeque<Good>()

        /** What came of the wagon, once it did. */
        internal var event: What? = null
        internal var cart = Handcarts.enabled()
        internal var gathering: Gathering? = null
        private var called = false
        private var away = 0L
        private var home: Pair<BlockPos, BlockPos>? = null
        internal var counted = false
        internal var handed = 0
        internal var took = 0

        internal val lead: Hand? get() = hands.firstOrNull { it.lead && !it.gone }

        /** In a few words, what they are at (for `village links`). */
        val doing: String
            get() {
                val who = if (hands.isEmpty()) "the folk are" else "the carters are"
                return when {
                    event == What.LATE -> "$who at the road end: the wagon has not come"
                    hands.any { it.stage >= Stage.BACK } -> if (out) "the carters are coming back from seeing a wagon off" else "the carters are bringing the wagon's goods in"
                    out -> "$who out at the road end to see a wagon off"
                    else -> "$who out at the road end to meet the wagon"
                }
            }

        /** Looked over each tick; false once it is over. */
        internal fun tick(level: ServerLevel): Boolean {
            hands.removeIf { it.gone }
            val now = level.gameTime
            if (gathering?.done == true) gathering = null
            if (hands.isEmpty() && (called && gathering == null)) return false
            if (now % 10L != 0L) return true
            // Someone taken off them meanwhile (a customer, a fright, gone from the world): out of the scene
            for (h in hands) if (h.p.isRemoved || !h.p.isAlive || h.p.director !== h) h.release()
            if (!VillageLinks.enabled() || now - started > MOST || Villages.byKey(level, key)?.state.let { it == null || it == Live.LOST }) {
                end()
                return false
            }
            if (!near(level, BlockPos.of(key))) {
                if (away == 0L) away = now
                if (now - away > AWAY) {
                    end()
                    return false
                }
            } else {
                away = 0L
            }
            val clock = level.overworldClockTime
            if (!called && time - clock <= CROWD) {
                called = true
                // Out of sight, a wagon setting out is seen off by its carters alone
                if (!out || end.seen) gathering = Greeters.gather(level, key, end.cart, end.road, GreetWhy.WAGON, watch, hands.isNotEmpty(), hands.map { it.p.resident }.toSet())
            }
            if (event == null) {
                val what = watch.what(level)
                if (what.settled) settle(what)
            }
            return true
        }

        /** The wagon did what it did: the sacks to heave or take down are set out, and whoever waits at the road end plays it. */
        private fun settle(what: What) {
            event = what
            pending.clear()
            when (what) {
                What.OFF -> {
                    val gave = watch.gave
                    if (cart && gave != null) for (g in heap) if (gave[g.ordinal] > EPS) pending.add(g)
                }
                What.IN -> {
                    watch.brought.orEmpty().entries.filter { it.value > EPS }.sortedByDescending { it.value }
                        .mapNotNull { Good.byId(it.key) }.filter { !Handcarts.stack(it).isEmpty }.take(SACKS).forEach { pending.add(it) }
                }
                else -> {}
            }
            for (h in hands) {
                if (h.gone || h.stage != Stage.WAIT || h.moves.walking) continue
                h.moves.clear()
                react(h, what)
            }
        }

        /** Everyone let go at once. */
        internal fun end() {
            for (h in hands.toList()) h.release()
            hands.clear()
            gathering?.release()
            gathering = null
        }

        internal fun drop(h: Hand) {
            h.release()
        }

        /** The cart heaped with [heap]. */
        private fun show() {
            val l = lead ?: return
            if (cart) Handcarts.load(l.p, heap.map { Handcarts.stack(it) })
        }

        private fun bed(): Vec3? = lead?.let { Handcarts.bed(it.p) }

        private fun given(g: Good): Boolean = (watch.gave?.getOrNull(g.ordinal) ?: 0.0) > EPS

        // ---- Each hand's part, when they are free for the next of it ----

        internal fun next(level: ServerLevel, h: Hand) {
            val p = h.p
            when (h.stage) {
                Stage.START -> setOff(h)
                Stage.PARK -> {
                    Handcarts.unhitch(p)
                    h.moves.overlay = Activity.NONE
                    p.act(Activity.NONE)
                    h.moves.wait = 15
                    h.stage = Stage.SIDE
                }
                Stage.SIDE -> {
                    h.stage = Stage.WAIT
                    Handcarts.side(p, end.road)?.let { h.moves.go(it, Jobs.WALK, exact = true) }
                }
                Stage.WAIT -> wait(level, h)
                Stage.HEAVE -> heave(level, h)
                Stage.TAKE_DOWN -> takeDown(h)
                Stage.AFTER -> h.stage = if (h.lead && cart && Handcarts.parked(p) != null) Stage.FETCH else Stage.BACK
                Stage.FETCH -> {
                    h.stage = Stage.TAKE
                    Handcarts.fetch(p)?.let { h.moves.go(it, 0.3, exact = true) }
                }
                Stage.TAKE -> take(h)
                Stage.BACK -> back(level, h)
                Stage.PARK_HOME -> {
                    Handcarts.unhitch(p)
                    Handcarts.headFor(p, null)
                    h.moves.overlay = Activity.NONE
                    p.act(Activity.NONE)
                    h.moves.wait = 10
                    h.stage = if (out) Stage.DONE else Stage.UNLOAD
                }
                Stage.UNLOAD -> unload(level, h)
                Stage.CARRY -> {
                    // At the door: the sack set down
                    val p0 = h.p
                    h.moves.overlay = Activity.NONE
                    h.moves.beats += Beat(Activity.PUT_DOWN, Activity.PUT_DOWN.ticks + 4, Vec3.atCenterOf(home(level).second)) {
                        p0.carried = ItemStack.EMPTY
                        h.holding = null
                    }
                    h.stage = Stage.UNLOAD
                }
                Stage.DONE -> drop(h)
            }
        }

        /** Off from the village: the first with the cart (heaped for a send-off), the second beside them; with carts off, a sack each. */
        private fun setOff(h: Hand) {
            val p = h.p
            if (h.lead && cart) {
                val got = Handcarts.hitch(p)
                if (got != null && !Handcarts.pulling(p)) {
                    // Their own cart stands parked in sight: they walk to its shafts and take it up there
                    if (++h.tries <= 4) {
                        Handcarts.fetch(p)?.let { h.moves.go(it, Jobs.WALK, exact = true) } ?: run { h.moves.wait = 20 }
                        return
                    }
                    Handcarts.leave(p)
                    cart = false
                } else if (got != null) {
                    h.tries = 0
                    show()
                    h.moves.overlay = Handcarts.PULL
                    p.act(Handcarts.PULL)
                    h.stage = Stage.PARK
                    h.moves.go(end.cart, Trades.LADEN, near = 1.0)
                    return
                }
                // No room for a cart here: they carry what they can
                if (++h.tries <= 3) {
                    h.moves.wait = 20
                    return
                }
                cart = false
            }
            if (!cart && out) {
                heap.getOrNull(hands.indexOf(h))?.let { g ->
                    h.holding = g
                    p.carried = Handcarts.stack(g)
                    h.moves.overlay = Activity.CARRY_SACK
                }
            }
            h.stage = Stage.WAIT
            h.moves.go(if (h.lead) end.cart else end.mate, Trades.LADEN, near = 1.0)
        }

        /** At the road end, waiting: facing the road (the wagon, once it is in sight), a look down it now and then; the sacks counted once. */
        private fun wait(level: ServerLevel, h: Hand) {
            val p = h.p
            event?.let {
                react(h, it)
                return
            }
            h.moves.overlay = if (h.holding != null) Activity.CARRY_SACK else Activity.NONE
            val at = watch.wagon(level) ?: end.out
            face(p, at)
            val b = bed()
            if (!h.lead && out && cart && !counted && heap.isNotEmpty() && b != null && lead?.stage == Stage.WAIT) {
                counted = true
                h.moves.beats += Beat(Activity.COUNT, Activity.COUNT.ticks, b, "cart_count_2")
                h.moves.beats += Beat(Activity.NONE, 10, at)
                return
            }
            if (p.random.nextInt(8) == 0) {
                h.moves.beats += Beat(Activity.LOOK_ABOUT, Activity.LOOK_ABOUT.ticks, at)
            } else {
                p.act(h.moves.overlay)
                h.moves.wait = 20
            }
        }

        private fun react(h: Hand, what: What) {
            when (what) {
                What.OFF -> h.stage = Stage.HEAVE
                What.IN -> h.stage = Stage.TAKE_DOWN
                What.LATE -> after(h, null, true)
                else -> after(h, null, false)
            }
        }

        /** A sack heaved aboard the wagon setting out (each of what it took, the first and second by turns); none left, and on. */
        private fun heave(level: ServerLevel, h: Hand) {
            val p = h.p
            val at = watch.wagon(level) ?: end.road
            if (cart) {
                val g = pending.removeFirstOrNull()
                if (g != null) {
                    h.moves.beats += Beat(Activity.HAND_OVER, Activity.HAND_OVER.ticks + 4, at) {
                        heap.remove(g)
                        show()
                        handed++
                    }
                    return
                }
            } else {
                val g = h.holding
                if (g != null && given(g)) {
                    h.moves.beats += Beat(Activity.HAND_OVER, Activity.HAND_OVER.ticks + 4, at) {
                        p.carried = ItemStack.EMPTY
                        h.holding = null
                        h.moves.overlay = Activity.NONE
                        handed++
                    }
                    return
                }
            }
            after(h, level, false)
        }

        /** A sack taken down from the wagon that is back and heaped on the cart (with carts off, one carried each); none left, and on. */
        private fun takeDown(h: Hand) {
            val p = h.p
            val at = end.road
            if (cart) {
                val g = pending.removeFirstOrNull()
                if (g != null) {
                    val line = if (!h.lead && !counted) "cart_count_2" else null
                    if (line != null) counted = true
                    h.moves.beats += Beat(Activity.RECEIVE, Activity.RECEIVE.ticks, at, line) { p.carried = Handcarts.stack(g) }
                    h.moves.beats += Beat(Activity.PUT_DOWN, Activity.PUT_DOWN.ticks + 4, bed() ?: at) {
                        p.carried = ItemStack.EMPTY
                        heap.add(g)
                        show()
                        took++
                    }
                    return
                }
            } else if (h.holding == null) {
                val g = pending.removeFirstOrNull()
                if (g != null) {
                    h.moves.beats += Beat(Activity.RECEIVE, Activity.RECEIVE.ticks, at) {
                        p.carried = Handcarts.stack(g)
                        h.holding = g
                        took++
                    }
                    return
                }
            }
            after(h, null, false)
        }

        /** The end of it at the road end: "That's the lot." and a wave, a shrug for nothing, or "No wagon again." for a wagon that never came. */
        private fun after(h: Hand, level: ServerLevel?, late: Boolean) {
            val b = h.moves.beats
            val at = level?.let { watch.wagon(it) } ?: end.out
            when {
                late -> {
                    b += Beat(Activity.LOOK_ABOUT, Activity.LOOK_ABOUT.ticks, end.out)
                    b += Beat(Activity.SIGH, Activity.SIGH.ticks, end.out, if (h.lead) "cart_late" else null)
                    b += Beat(Activity.SHRUG, Activity.SHRUG.ticks, end.out)
                }
                event == What.OFF -> {
                    if (h.lead) b += if (handed > 0) Beat(Activity.NOD, Activity.NOD.ticks, at, "cart_count_1") else Beat(Activity.SHRUG, Activity.SHRUG.ticks, at)
                    b += Beat(Activity.WAVE, Activity.WAVE.ticks, at)
                    b += Beat(Activity.WAVE, Activity.WAVE.ticks, at)
                }
                event == What.IN -> {
                    if (h.lead) b += if (took > 0) Beat(Activity.NOD, Activity.NOD.ticks, bed() ?: at, "cart_count_1") else Beat(Activity.SHRUG, Activity.SHRUG.ticks, at)
                }
                else -> b += Beat(Activity.SHRUG, Activity.SHRUG.ticks, at)
            }
            h.stage = Stage.AFTER
        }

        /** The cart taken up again for the way back. */
        private fun take(h: Hand) {
            val p = h.p
            Handcarts.parked(p)?.let { p.face(it.yRot) }
            Handcarts.hitch(p)
            if (Handcarts.pulling(p)) {
                h.tries = 0
                h.moves.overlay = Handcarts.PULL
                p.act(Handcarts.PULL)
                h.stage = Stage.BACK
                return
            }
            if (++h.tries > 4) {
                // It will not be taken up (somebody's in the way of it): it is left, and goes once nobody looks
                Handcarts.leave(p)
                cart = false
                h.stage = Stage.BACK
                return
            }
            h.moves.wait = 10
        }

        /** Back into the village: the cart to the green (a send-off) or the baker's door (a return), the second walking with it. */
        private fun back(level: ServerLevel, h: Hand) {
            val p = h.p
            val (bay, drop) = home(level)
            if (h.lead && cart && Handcarts.pulling(p)) {
                Handcarts.headFor(p, bay)
                h.moves.overlay = Handcarts.PULL
                h.stage = Stage.PARK_HOME
                h.moves.go(bay, Trades.LADEN, near = 1.0)
                return
            }
            h.moves.overlay = if (h.holding != null) Activity.CARRY_SACK else Activity.NONE
            if (out) {
                h.stage = Stage.DONE
                h.moves.go(bay, Jobs.WALK, near = 3.0)
            } else {
                h.stage = Stage.UNLOAD
                h.moves.go(if (h.holding != null) drop else bay, if (h.holding != null) Trades.LADEN else Jobs.WALK, near = 2.0)
            }
        }

        /** The return's sacks off the cart and in at the door, one at a time, both carters by turns. */
        private fun unload(level: ServerLevel, h: Hand) {
            val p = h.p
            val drop = home(level).second
            if (h.holding != null) {
                h.moves.overlay = Activity.CARRY_SACK
                h.stage = Stage.CARRY
                h.moves.go(drop, Trades.LADEN, near = 1.5)
                return
            }
            val l = lead
            if (!cart || heap.isEmpty() || l == null) {
                h.stage = Stage.DONE
                return
            }
            // The cart not in yet: the second waits for it
            if (Handcarts.parked(l.p) == null) {
                h.moves.wait = 20
                return
            }
            val bed = Handcarts.bed(l.p) ?: Vec3.atCenterOf(drop)
            val from = if (h.lead) Vec3.atCenterOf(drop) else bed.add(bed.subtract(Vec3.atCenterOf(drop)))
            val side = Handcarts.side(l.p, from)
            if (side != null && p.blockPosition().distSqr(side) > 2.0) {
                h.moves.go(side, Jobs.WALK, exact = true)
                return
            }
            val g = heap.removeAt(heap.size - 1)
            show()
            h.moves.beats += Beat(Activity.PICK_UP, Activity.PICK_UP.ticks + 2, bed) {
                p.carried = Handcarts.stack(g)
                h.holding = g
            }
        }

        /** Where the cart is left on the way back, and where the sacks are carried to: the baker's door for a return, else the green. */
        private fun home(level: ServerLevel): Pair<BlockPos, BlockPos> {
            home?.let { return it }
            val places = Villages.places(level, key)
            val green = places?.market ?: BlockPos.of(key)
            val by = lead?.p
            var found: Pair<BlockPos, BlockPos>? = null
            if (!out && places != null) {
                val door = places.lodgings.values.filter { it.kind == BAKERY && it.doors.isNotEmpty() }.minByOrNull { it.box.center.distSqr(green) }?.doors?.first()
                if (door != null && level.isLoaded(door.pos)) Handcarts.bay(level, door, by)?.let { found = it to door.outside }
            }
            if (found == null && level.isLoaded(green)) {
                for (d in Direction.Plane.HORIZONTAL) {
                    val bay = Handcarts.bay(level, green, d, by) ?: continue
                    found = bay to green
                    break
                }
            }
            return (found ?: (green to green)).also { home = it }
        }
    }

    /** One of a [Scene]'s carters: the first ([lead]) pulls the cart. */
    class Hand internal constructor(internal val scene: Scene, val p: Townsperson, val lead: Boolean) : Director {
        internal val moves = Moves(p)
        internal var stage = Stage.START
        internal var holding: Good? = null
        internal var tries = 0
        var gone = false
            private set

        override fun tick(p: Townsperson) {
            if (gone) return
            val level = p.level() as? ServerLevel ?: return
            if (!p.isAlive || p.hurtTime > 0 || p.target != null || p.activity == Activity.FLEE || moves.stuck) {
                release()
                return
            }
            if (moves.tick()) return
            scene.next(level, this)
        }

        /** Busy: a look for whoever speaks to them, and on with it. */
        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            p.lookAt(player)
            return true
        }

        /** Back to their day; a cart in hand or parked is let go where it stands (it goes once nobody looks). */
        internal fun release() {
            if (gone) return
            gone = true
            moves.stop()
            if (lead) {
                Handcarts.headFor(p, null)
                if (Handcarts.pulling(p) || Handcarts.parked(p) != null) Handcarts.leave(p)
            }
            p.carried = ItemStack.EMPTY
            if (p.director === this) p.director = null
            p.act(Activity.NONE)
            p.navigation.stop()
            p.routine.interrupt()
        }
    }
}

// ---- What the wagon is at ----

/** What the folk at a road end make of what they are waiting for. */
enum class What(val settled: Boolean) {
    /** Not yet: it is coming, or due. */
    COMING(false),

    /** In sight, coming in. */
    SIGHT(false),

    /** Set out from the road end (or gone by): waved off. */
    OFF(true),

    /** Back at the road end with its goods. */
    IN(true),

    /** It was due, and never came. */
    LATE(true),

    /** Nothing came of it (no wagon this way after all, or the record is gone). */
    NONE(true),
}

/** What a scene or a crowd is waiting on: what it is at, and where it is if it can be seen. */
interface Watch {
    fun what(level: ServerLevel): What

    /** Where it is now, if it is in sight. */
    fun wagon(level: ServerLevel): Vec3?
}

/**
 * The village [key]'s wagon by [link]: setting out at about [time] ([out]; [caravan] is found once its record is planned), or the
 * record [caravan] back at [time]. Read from the records, at most once a tick: what it took ([gave]) once it set out, what it brought
 * ([brought]) once it is back, from the village's book.
 */
class WagonWatch(val key: Long, val link: Link, val out: Boolean, var caravan: Long, val time: Long, val seen: Boolean) : Watch {
    private var at = Long.MIN_VALUE
    private var last = What.COMING
    private var sighted = false
    var gave: DoubleArray? = null
        private set
    var brought: Map<String, Double>? = null
        private set

    override fun what(level: ServerLevel): What {
        val now = level.gameTime
        if (now == at || last.settled) return last
        at = now
        last = look(level)
        return last
    }

    private fun look(level: ServerLevel): What {
        val clock = level.overworldClockTime
        val data = CaravanData.get(level)
        if (out) {
            val c = if (caravan >= 0) data.caravan(caravan) else data.caravans.firstOrNull {
                it.capital == link.capital && it.road == link.road && SendOff.wagon(it) && abs(it.setOut - time) < WITHIN
            }
            if (c != null) {
                caravan = c.id
                if (c.phase == Phase.PLANNED || c.phase == Phase.WAITING && c.escortOnly) return if (clock > time + SendOff.LATE_AFTER * 3) What.NONE else What.COMING
                gave = if (c.village == key) c.fromVillage.copyOf() else DoubleArray(Good.entries.size)
                return What.OFF
            }
            if (caravan >= 0) {
                // Its record ended before it was looked at again: the book has what it took
                val trip = VillageWagons.book(level, key)?.trips?.lastOrNull { it.caravan == caravan }
                gave = DoubleArray(Good.entries.size).also { a -> trip?.gave?.forEach { (id, n) -> Good.byId(id)?.let { a[it.ordinal] = n } } }
                return What.OFF
            }
            return if (clock > time + SendOff.LATE_AFTER) What.NONE else What.COMING
        }
        // Back: the book says once it is
        val trip = VillageWagons.book(level, key)?.trips?.lastOrNull { it.caravan == caravan } ?: return What.NONE
        when (trip.end) {
            VillageWagons.HOME, VillageWagons.CALLED -> {
                brought = trip.brought
                return What.IN
            }
            VillageWagons.WAYLAID, VillageWagons.WITHDRAWN -> return if (clock >= time + SendOff.LATE_AFTER) What.LATE else What.COMING
        }
        val c = data.caravan(caravan) ?: return What.NONE
        if (seen && c.phase == Phase.LEAVING && Caravans.distance(c, clock) <= SendOff.SIGHT) sighted = true
        return if (sighted) What.SIGHT else What.COMING
    }

    override fun wagon(level: ServerLevel): Vec3? {
        if (!seen || caravan < 0) return null
        val c = CaravanData.get(level).caravan(caravan) ?: return null
        if (c.phase == Phase.PLANNED) return null
        val leg = CaravanRoutes.cached(c.capital, c.road) ?: return null
        val along = Caravans.distance(c, level.overworldClockTime)
        if (along > SendOff.SIGHT) return null
        return leg.at(along).add(0.0, 1.2, 0.0)
    }

    private companion object {
        /** How near (clock ticks) a planned record's setting out must be to the time looked for to be the wagon looked for. */
        const val WITHIN = 1500L
    }
}

// ---- A body's moves ----

/** One move of a body's in a scene: [act] for [ticks], facing [at], saying [line]; [then] is done as it begins. */
class Beat(val act: Activity, val ticks: Int, val at: Vec3? = null, val line: String? = null, val then: (() -> Unit)? = null)

/** A body's moves in a scene: the walk under way, a wait, and the beats queued, each played for its length. */
class Moves(private val p: Townsperson) {
    private var walker: Walker? = null
    private var goal: BlockPos? = null
    private var speed = Jobs.WALK
    private var near = 1.5
    private var exact = false
    private var fails = 0
    internal var wait = 0
    internal val beats = ArrayDeque<Beat>()

    /** Played over the walk (pulling the cart, a sack on the shoulder); NONE for none. */
    internal var overlay: Activity = Activity.NONE

    /** The walk failed too often: the scene lets them go. */
    var stuck = false
        private set

    val walking: Boolean get() = walker != null

    fun go(to: BlockPos, speed: Double, near: Double = 1.5, exact: Boolean = false) {
        goal = to
        this.speed = speed
        this.near = near
        this.exact = exact
        walker?.stop()
        walker = Walker(p).also { it.go(to, speed, near, exact) }
    }

    /** One tick: true while a walk, a wait or a beat is under way. */
    fun tick(): Boolean {
        val w = walker
        if (w != null) {
            if (overlay != Activity.NONE) p.act(overlay)
            when (w.tick()) {
                Walker.State.WALKING -> return true
                Walker.State.ARRIVED -> {
                    w.stop()
                    walker = null
                    fails = 0
                    if (overlay == Activity.NONE) p.act(Activity.NONE)
                    return false
                }
                Walker.State.FAILED -> {
                    w.stop()
                    walker = null
                    if (++fails > FAILS) {
                        stuck = true
                        return true
                    }
                    goal?.let { go(it, speed, near, exact) }
                    return true
                }
            }
        }
        if (wait > 0) {
            wait--
            return true
        }
        val b = beats.removeFirstOrNull() ?: return false
        b.at?.let { SendOff.face(p, it) }
        p.act(b.act)
        b.line?.let { SendOff.say(p, it) }
        wait = b.ticks
        b.then?.invoke()
        return true
    }

    /** Whatever is queued dropped (the walk goes on). */
    fun clear() {
        beats.clear()
        wait = 0
    }

    fun stop() {
        walker?.stop()
        walker = null
        beats.clear()
        wait = 0
    }

    private companion object {
        const val FAILS = 5
    }
}
