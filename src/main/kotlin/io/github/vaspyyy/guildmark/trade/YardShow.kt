package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.caravan.Bodies
import io.github.vaspyyy.guildmark.caravan.ClimbUp
import io.github.vaspyyy.guildmark.caravan.DraughtHorse
import io.github.vaspyyy.guildmark.caravan.Ground
import io.github.vaspyyy.guildmark.caravan.Train
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.build.CountingHouse
import io.github.vaspyyy.guildmark.economy.works.build.TownWorksSite
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.trade.carry.LedShow
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The trading house's yard and map while a player is near (living-world/trader/design.md, Part 5; build part G). [HouseLife] hands each second
 * of a standing House here ([second]); the House's run ([Houses]) says what its carters do ([hired], [loading], [departed], [back], [left]).
 *
 * - **The map.** The House's wagons on the road go on its map ([HouseMapBlockEntity.trips]): out from the House's town to the one each is
 *   bound for, from when it left the yard to when it gets in, and home again after its hour standing there, so the renderer moves each
 *   along its thread by its own clock. The list is sent only when it changes (a clock set forward moves a trip's start, and it is sent again).
 * - **The yard,** in working hours with its gate open and a player within [SEEN] blocks: the carters at the yard stand about it (four at most,
 *   on bodies of the townsfolk's that are nobody's residents, tagged [TAG] and never kept). One loading goes to and fro between the barrels
 *   and the wagon drawn up outside the gate with baskets (`LOAD_PACK`), its load heaping on the bed; the horse tethered beside it is backed
 *   into the shafts and the traces clipped (`HITCH`, `trade.harness_buckle`); the tailboard goes up, the carter climbs onto the bench, and
 *   once its hour is up the wagon rolls out down the street (`trade.cart_creak`) and is gone with a puff of dust a little way on. A carter
 *   back walks in through the gate and hails the yard (`trade.carter_hail`, their word of the road); the scribe rings the bell, pays the
 *   takings into the strongbox (its lid up, a clink) and pushes the counting frame's beads to the new sum ([HouseLife]'s scenes). With no
 *   scribe free, the bell rings, the lid lifts and the beads slide by themselves.
 *
 * Nothing here is saved, and nothing changes what the House does: with nobody near, at night, or with `tradeHouse` off, there are no bodies
 * (as before part G), and the map keeps on.
 */
object YardShow {
    /** Every carter's body's tag: one ever saved by mistake goes when it is loaded. */
    const val TAG = "guildmark.house_carter"

    /** How near a player must be for the yard to be shown; the most carters' bodies in it; ticks a yard goes without its second before it is let go. */
    private const val SEEN = 48.0
    private const val HANDS = 4
    private const val STALE = 60L

    /** Ticks a trip's start on the map may move before it is sent again (the clock and the game's time drift a little). */
    private const val MAP_SLACK = 40L

    /** Where the carters stand about the yard (the drawing's x, z): clear of the barrels, the bell and the way to the gate. */
    private val SPOTS = listOf(3 to 2, 6 to 2, 6 to 1, 3 to 1)

    /** The barrels they fill the baskets at, and where they stand to; the two leaves of the gate, the way in and out. */
    private val BARRELS = 2 to 2
    private val AT_BARRELS = 2 to 1
    private val GATEWAY = listOf(4 to 0, 5 to 0)

    /** The drawing's z of the wagon's back axle as it stands before the gate, and how far out down the street a carter walking in comes from. */
    private const val PARKED_Z = -1.85
    private const val STREET = 7.0

    /** How far the wagon rolls before it is gone, its pace and how fast it gets to it (blocks a tick). */
    private const val ROLL_OUT = 22.0
    private const val PACE = 0.16
    private const val ACCEL = 0.004

    /** The most ticks a departure is shown, and how long a carter back waits for the scribe before the yard does without them. */
    private const val MOST = 3000
    private const val SCRIBE_WAIT = 600

    /** The baskets a wagon is loaded with at most, and the bed's places each fills. */
    private const val BASKETS = 4
    private const val PER_BASKET = 4

    /**
     * Ticks: the horse led round and backed into the shafts; into the HITCH clip that the right trace is taken up in the hand (its `grab`:
     * the wagon draws it drawn up to the horse's shoulder from then, [Wagon.FLAG_TRACE_UP]) and that the traces are clipped (its `clip`).
     */
    private const val LEAD = 30
    private const val BACK = 30
    private const val TRACE_UP = 13
    private const val CLIPPED = 25

    /** Where a loader stands to heave a basket onto the let-down tailboard (LOAD_PACK's spot): on the wagon's middle line, this many px behind its back axle. */
    private const val LOAD_AT = 25f

    private val OUT = listOf("trade_carter_out_0", "trade_carter_out_1")
    private val HOME = listOf("trade_carter_back_0", "trade_carter_back_1")
    private val ROBBED = listOf("trade_carter_robbed_0", "trade_carter_robbed_1")
    private val HIRED = listOf("trade_carter_hired_0", "trade_carter_hired_1")
    private val SCRIBE_BACK = listOf("trade_scribe_back_0", "trade_scribe_back_1", "trade_scribe_back_2")

    /** What a carter at the yard does now and then, standing about; and one waiting for their wage. */
    private val IDLE = listOf(Activity.LOOK_ABOUT, Activity.STRETCH, Activity.MOP_BROW, Activity.YAWN)
    private val SULK = listOf(Activity.GRUMBLE, Activity.SIGH, Activity.ARMS_FOLDED)

    /** The moves' scene: the yard's moves are played by its own hands, not an [Aside] of the townsfolk's. */
    private val NOBODY: Aside by lazy { Aside(listOf()) }

    /** A House's yard while it is shown: works [works] of [owner] in [capital], as last seen at [site]. */
    private class Yard(val works: Int, val owner: UUID, val capital: Long, var level: ServerLevel, var site: TownWorksSite) {
        var seen = 0L
        var shown = false
        val hands = LinkedHashMap<Int, Hand>()
        var wagon: YardWagon? = null
        var map: List<HouseMapBlockEntity.MapTrip>? = null
        val homes = ArrayDeque<Home>()
    }

    /** A carter back ([carter], -1 for one who left on getting in), at [since]; [robbed], waylaid on the road. */
    private class Home(val carter: Int, val robbed: Boolean, val since: Long) {
        var started = false
    }

    private val yards = HashMap<Int, Yard>()
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        val bus = NeoForge.EVENT_BUS
        bus.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (level.dimension() == Level.OVERWORLD && yards.isNotEmpty()) tick(level)
        }
        bus.addListener(EntityJoinLevelEvent::class.java) { event ->
            if (event.loadedFromDisk() && TAG in event.entity.entityTags()) event.isCanceled = true
        }
        bus.addListener(ServerStoppedEvent::class.java) { _ ->
            for (y in yards.values.toList()) drop(y)
            yards.clear()
        }
    }

    /** Whether [e] is one of a yard's wagons or horses (the caravans' bodies keep only what someone owns). */
    fun owns(e: Entity): Boolean = yards.values.any { y -> y.wagon?.let { it.wagon === e || it.horse === e } == true }

    // ---- Each second of a standing House ----

    /** Each second of [w], a standing Counting House whose land is loaded at [site]; [near]: a player is about the works. */
    internal fun second(level: ServerLevel, w: WorksRec, site: TownWorksSite, near: Boolean) {
        if (site.drawing !is CountingHouse) return
        val owner = w.owner ?: return
        val h = TradeHouses.peek(level)?.find(w.capital, owner)
        val y = yards[w.id]
        if (h == null) {
            y?.let { drop(it); yards.remove(w.id) }
            return
        }
        val yard = y ?: Yard(w.id, owner, w.capital, level, site).also { yards[w.id] = it }
        yard.level = level
        yard.site = site
        yard.seen = level.gameTime
        map(level, yard, h)
        val show = near && TradeConfig.house() && working(level, site) && anyNear(level, centre(site, 4.5, 1.5))
        if (!show) {
            if (yard.shown) hide(yard)
            return
        }
        yard.shown = true
        hands(level, yard, h)
        if (yard.wagon == null) h.carters.firstOrNull { it.state == Carter.State.LOADING && yard.hands[it.id] != null }?.let { c ->
            h.trip(c.trip)?.let { trip -> if (trip.stage == Trip.Stage.LOADING) start(level, yard, h, c, trip) }
        }
        homes(level, w, yard, h)
    }

    /** Whether it is the House's working day ([StoreLife]'s hours) with its gate open: the yard is shown only then. */
    private fun working(level: ServerLevel, site: TownWorksSite): Boolean {
        val hour = Math.floorMod(level.overworldClockTime + 6000L, 24000L) / 1000.0
        if (hour < StoreLife.OPEN_FROM || hour >= StoreLife.OPEN_TO) return false
        val gate = StoreLife.at(site, CountingHouse.GATES.first())
        if (!level.isLoaded(gate)) return false
        val state = level.getBlockState(gate)
        return state.block is FenceGateBlock && state.getValue(FenceGateBlock.OPEN)
    }

    private fun anyNear(level: ServerLevel, at: Vec3): Boolean = level.players().any { !it.isSpectator && it.distanceToSqr(at) <= SEEN * SEEN }

    /** The yard's bodies all let go of (nobody near, night, the House off): the map keeps on. */
    private fun hide(y: Yard) {
        y.shown = false
        y.wagon?.let { it.finish(puff = false) }
        y.wagon = null
        for (hand in y.hands.values) hand.discard()
        y.hands.clear()
        y.homes.clear()
    }

    private fun drop(y: Yard) {
        hide(y)
        y.map = null
    }

    // ---- The map ----

    /** The House's trips on the road, as its map draws them: sent when they change. */
    private fun map(level: ServerLevel, y: Yard, h: HouseRec) {
        val trips = ArrayList<HouseMapBlockEntity.MapTrip>()
        for (t in h.trips) {
            when (t.stage) {
                Trip.Stage.OUT -> trips.add(HouseMapBlockEntity.MapTrip(h.capital, t.dest, Caravans.gameAt(level, t.departAt), (t.arriveAt - t.departAt).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()))
                Trip.Stage.BACK -> {
                    val from = t.arriveAt + STAND
                    trips.add(HouseMapBlockEntity.MapTrip(t.dest, h.capital, Caravans.gameAt(level, from), (t.backAt - from).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()))
                }
                Trip.Stage.LOADING -> {}
            }
        }
        val next = trips.take(HouseMapBlockEntity.TRIPS)
        val was = y.map
        if (was != null && same(was, next)) return
        val map = StoreLife.fitting<HouseMapBlockEntity>(level, y.site, CountingHouse.MAP_AT) ?: return
        y.map = next
        map.trips(next)
    }

    /** The hour a wagon stands in the town it went to before it starts home (as [Houses] has it). */
    private const val STAND = 1000L

    private fun same(a: List<HouseMapBlockEntity.MapTrip>, b: List<HouseMapBlockEntity.MapTrip>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val z = b[i]
            if (x.from != z.from || x.to != z.to || kotlin.math.abs(x.start - z.start) > MAP_SLACK || kotlin.math.abs(x.ticks - z.ticks) > MAP_SLACK) return false
        }
        return true
    }

    // ---- The carters in the yard ----

    /** A carter's body in the yard, in the yard's charge: what they are doing, a move at a time. */
    private class Hand(val yard: Yard, val carter: Int, val p: Townsperson, var spot: Int) : Director {
        private val moves = ArrayDeque<Move>()
        private var current: Move? = null
        private var t = 0
        var leaving = false
        var busy = false
        private var nextIdle = 0L

        val idle: Boolean get() = current == null && moves.isEmpty()

        fun then(m: Move): Hand {
            moves.addLast(m)
            return this
        }

        fun clear() {
            current?.stop(p)
            current = null
            moves.clear()
        }

        override fun tick(p: Townsperson) {
            var guard = 0
            while (guard++ < 6) {
                val m = current ?: moves.removeFirstOrNull()?.also {
                    current = it
                    t = 0
                    it.start(p, NOBODY)
                } ?: return stand(p)
                if (m.tick(p, NOBODY, t++)) return
                m.stop(p)
                current = null
            }
        }

        /** Nothing to do: about the yard, now and then a stretch or a look round (a grumble, waiting for the wage). */
        private fun stand(p: Townsperson) {
            if (busy || leaving) return
            val level = p.level() as? ServerLevel ?: return
            val now = level.gameTime
            if (now < nextIdle) return
            nextIdle = now + 120 + level.random.nextInt(240)
            val unpaid = TradeHouses.peek(level)?.find(yard.capital, yard.owner)?.carter(carter)?.state == Carter.State.UNPAID
            val pick = if (unpaid) SULK else IDLE
            p.act(pick[level.random.nextInt(pick.size)])
        }

        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            p.lookAt(player)
            p.attend(player, 40)
            if (idle) p.act(Activity.NOD)
            return true
        }

        fun discard() {
            clear()
            if (p.director === this) p.director = null
            if (!p.isRemoved) p.discard()
        }
    }

    /** The bodies brought up to the carters at the yard: those loading first, then the rest, four at most; any not wanted walk out. */
    private fun hands(level: ServerLevel, y: Yard, h: HouseRec) {
        val want = h.carters.filter { it.state != Carter.State.ROLLING }
            .sortedBy { if (it.state == Carter.State.LOADING) 0 else 1 }.take(HANDS)
        val wanted = want.map { it.id }.toSet()
        for (hand in y.hands.values.toList()) {
            if (hand.p.isRemoved || !hand.p.isAlive) {
                y.hands.remove(hand.carter)
                continue
            }
            if (hand.carter !in wanted && !hand.busy && !hand.leaving && y.wagon?.carter != hand.carter) walkOut(y, hand)
        }
        for (c in want) {
            if (y.hands.containsKey(c.id)) continue
            val spot = freeSpot(y) ?: break
            make(level, y, c, spot, SPOTS[spot].let { block(y.site, it.first, it.second) })
        }
    }

    private fun freeSpot(y: Yard): Int? = SPOTS.indices.firstOrNull { i -> y.hands.values.none { it.spot == i && !it.leaving } }

    /** Carter [c]'s body made at [at] (their face and name as the desk has them), in the yard's charge. */
    private fun make(level: ServerLevel, y: Yard, c: Carter, spot: Int, at: BlockPos): Hand? {
        val yaw = yawOf(y.site, 0.0, -1.0)
        val p = Townsfolk.create(level, at, TownRole.CITIZEN, Houses.lookOf(c.look), yaw, EntitySpawnReason.EVENT) ?: return null
        p.customName = Component.literal(c.name)
        p.isCustomNameVisible = false
        p.addTag(TAG)
        p.home = at
        // Nobody's to frighten or set on anyone: the yard says what they do
        p.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
        p.targetSelector.removeAllGoals { true }
        val hand = Hand(y, c.id, p, spot)
        p.director = hand
        if (!level.addFreshEntity(p)) return null
        y.hands[c.id] = hand
        return hand
    }

    /** [hand] walks out through the gate and down the street, and is gone. */
    private fun walkOut(y: Yard, hand: Hand) {
        hand.leaving = true
        hand.busy = false
        hand.clear()
        val gate = gateway(y, hand.carter)
        val away = streetBlock(y)
        hand.then(GoTo({ gate }, Jobs.WALK, 0.8, Activity.NONE, 300))
            .then(GoTo({ away }, Jobs.WALK, 1.2, Activity.NONE, 300))
            .then(Now { _ ->
                y.hands.remove(hand.carter)
                hand.discard()
            })
    }

    private fun gateway(y: Yard, carter: Int): BlockPos = GATEWAY[Math.floorMod(carter, GATEWAY.size)].let { block(y.site, it.first, it.second) }

    /** A little way down the street from the gate, where a carter walking in comes from; just outside the gate if that is no place to stand. */
    private fun streetBlock(y: Yard): BlockPos {
        val y0 = y.site.drawing.y0
        val c = centre(y.site, 4.5, -STREET)
        val ground = Ground.stand(y.level, c.x, c.z, y0 + 0.5)
        val clear = kotlin.math.abs(ground - y0) <= 3.0 && !Ground.solid(y.level, c.x, ground + 0.3, c.z) && !Ground.solid(y.level, c.x, ground + 1.3, c.z)
        return if (clear) BlockPos.containing(c.x, ground, c.z) else block(y.site, 4, -1)
    }

    // ---- From the House's run ----

    private fun yardOf(h: HouseRec): Yard? = yards.values.firstOrNull { it.capital == h.capital && it.owner == h.owner && it.shown }

    /** [c] hired: they come in through the gate to a spot in the yard and say they'll drive. */
    fun hired(level: ServerLevel, h: HouseRec, c: Carter) {
        val y = yardOf(h) ?: return
        if (y.hands.containsKey(c.id)) return
        val spot = freeSpot(y) ?: return
        val hand = make(level, y, c, spot, streetBlock(y)) ?: return
        val to = SPOTS[spot].let { block(y.site, it.first, it.second) }
        hand.busy = true
        hand.then(GoTo({ gateway(y, c.id) }, Jobs.WALK, 0.8, Activity.NONE, 300))
            .then(GoTo({ to }, Jobs.WALK, 0.6, Activity.NONE, 300))
            .then(Turn({ centre(y.site, 4.5, 4.0, 1.5) }))
            .then(Play(Activity.TIP_HAT, first = { p -> StoreLife.say(p, HIRED[level.random.nextInt(HIRED.size)]) }))
            .then(Now { _ -> hand.busy = false })
    }

    /** [c] let go (or gone for want of their wage): they walk out of the gate. */
    fun left(level: ServerLevel, h: HouseRec, c: Carter) {
        val y = yardOf(h) ?: return
        val hand = y.hands[c.id] ?: return
        if (y.wagon?.carter == c.id) return
        walkOut(y, hand)
    }

    /** [c] starts loading [trip]: the yard's next second takes it up. */
    fun loading(level: ServerLevel, h: HouseRec, c: Carter, trip: Trip) {}

    /** [trip]'s hour is up and it leaves: its wagon at the gate rolls out once its carter is up; a carter loading unseen walks out to it. */
    fun departed(level: ServerLevel, h: HouseRec, trip: Trip) {
        val y = yardOf(h) ?: return
        val wagon = y.wagon
        if (wagon != null && wagon.trip == trip.id) {
            wagon.go = true
            return
        }
        y.hands[trip.carter]?.let { walkOut(y, it) }
    }

    /** [trip] is in at the town it went to: the map takes it up at the next second. */
    fun settled(level: ServerLevel, h: HouseRec, trip: Trip) {}

    /** [trip]'s carter [c] is back (null, or no longer the House's, if they left on getting in): they walk in and the takings are counted. */
    fun back(level: ServerLevel, h: HouseRec, trip: Trip, c: Carter?) {
        val y = yardOf(h) ?: return
        val still = c != null && h.carter(c.id) === c
        y.homes.addLast(Home(if (still) c!!.id else -1, trip.waylaid, level.gameTime))
        if (!still || c == null || y.hands.containsKey(c.id)) return
        val spot = freeSpot(y) ?: return
        val hand = make(level, y, c, spot, streetBlock(y)) ?: return
        val to = SPOTS[spot].let { block(y.site, it.first, it.second) }
        val lines = if (trip.waylaid) ROBBED else HOME
        hand.busy = true
        hand.then(GoTo({ gateway(y, c.id) }, Jobs.WALK, 0.8, Activity.NONE, 300))
            .then(Play(if (trip.waylaid) Activity.SIGH else Activity.WAVE, first = { p ->
                level.playSound(null, p.x, p.y + 1.0, p.z, TradeSounds.get("trade.carter_hail"), SoundSource.NEUTRAL, 0.9f, 0.95f + level.random.nextFloat() * 0.1f)
                StoreLife.say(p, lines[level.random.nextInt(lines.size)])
            }))
            .then(GoTo({ to }, Jobs.WALK, 0.6, Activity.NONE, 300))
            .then(Now { _ -> hand.busy = false })
    }

    /** The takings of each carter back counted, once they are in the yard: the scribe's bell, the strongbox and the beads; or, with no scribe free, the yard's own. */
    private fun homes(level: ServerLevel, w: WorksRec, y: Yard, h: HouseRec) {
        val home = y.homes.firstOrNull() ?: return
        if (home.started) {
            if (!StoreLife.busy(w.id)) y.homes.removeFirst()
            return
        }
        // The carter in through the gate first
        val hand = if (home.carter >= 0) y.hands[home.carter] else null
        if (hand != null && hand.busy && level.gameTime - home.since < SCRIBE_WAIT) return
        val scribe = if (StoreLife.busy(w.id)) null else StoreLife.clerk(level, w, y.site)
        if (scribe == null) {
            if (level.gameTime - home.since < SCRIBE_WAIT && StoreLife.busy(w.id)) return
            y.homes.removeFirst()
            bySelf(level, y, h)
            return
        }
        home.started = true
        HouseLife.bellScene(level, w, y.site, scribe) {
            val next = WorksData.get(level).works[w.id] ?: return@bellScene
            val again = StoreLife.clerk(level, next, y.site) ?: return@bellScene bySelf(level, y, h)
            payIn(level, next, y.site, again) {
                StoreLife.clerk(level, next, y.site)?.let { HouseLife.sumsScene(level, next, y.site, it, true) }
            }
        }
    }

    /** The scribe pays the takings into the strongbox: says the wagon's back, lifts the lid, counts them in with a clink, and lets it fall. */
    private fun payIn(level: ServerLevel, w: WorksRec, site: TownWorksSite, scribe: Townsperson, then: () -> Unit) {
        val stand = StoreLife.at(site, CountingHouse.COFFER_STAND)
        val box = StoreLife.centre(site, CountingHouse.STRONGBOX_AT, 0.4)
        val boxPos = StoreLife.at(site, CountingHouse.STRONGBOX_AT)
        val strongbox = { StoreLife.fitting<StrongboxBlockEntity>(level, site, CountingHouse.STRONGBOX_AT) }
        val clink = { level.playSound(null, boxPos, TradeSounds.get("trade.pack_clink"), SoundSource.BLOCKS, 0.8f, 0.9f + level.random.nextFloat() * 0.2f) }
        StoreLife.send(
            w, scribe,
            listOf(
                Now { p -> StoreLife.say(p, SCRIBE_BACK[level.random.nextInt(SCRIBE_BACK.size)]) },
                GoTo({ stand }, Jobs.WALK, 0.6, Activity.NONE, 600),
                Turn({ box }),
                StoreLife.Handle(Activity.PICK_UP, box, LIFT) { strongbox()?.lift() },
                Play(Activity.HAND_OVER, at = { box }, first = { clink() }),
                Play(Activity.COUNT, 24, at = { box }, first = { clink() }),
                StoreLife.Handle(Activity.PUT_DOWN, box, LIFT) { strongbox()?.shut() },
            ),
            900,
        ) {
            strongbox()?.shut()
            then()
        }
    }

    /** The tick of the PICK_UP and PUT_DOWN clips the lid goes up or falls on. */
    private const val LIFT = 8

    /** No scribe to count it: the bell rings, the strongbox's lid lifts and falls with a clink, and the frame's beads slide to the purse. */
    private fun bySelf(level: ServerLevel, y: Yard, h: HouseRec) {
        val site = y.site
        StoreLife.fitting<HouseBellBlockEntity>(level, site, CountingHouse.BELL_AT)?.ring()
        StoreLife.fitting<StrongboxBlockEntity>(level, site, CountingHouse.STRONGBOX_AT)?.let { if (!it.open) it.lift(40) }
        level.playSound(null, StoreLife.at(site, CountingHouse.STRONGBOX_AT), TradeSounds.get("trade.pack_clink"), SoundSource.BLOCKS, 0.8f, 1f)
        val rows = StoreLife.fitting<CountingFrameBlockEntity>(level, site, CountingHouse.FRAME_AT)?.show(h.purse.toLong().coerceIn(0L, CountingFrameBlockEntity.MOST), show = true)
        if (!rows.isNullOrEmpty()) StoreLife.play(level, StoreLife.at(site, CountingHouse.FRAME_AT), "trade.beads", 0.7f)
    }

    // ---- The wagon at the gate ----

    /** [c] loading [trip] where it can be seen: the wagon drawn up at the gate, its horse beside it, and the carter to their baskets. */
    private fun start(level: ServerLevel, y: Yard, h: HouseRec, c: Carter, trip: Trip) {
        val hand = y.hands[c.id] ?: return
        val wagon = Bodies.WAGON.get().create(level, EntitySpawnReason.EVENT) ?: return
        val horse = Bodies.DRAUGHT_HORSE.get().create(level, EntitySpawnReason.EVENT) ?: return
        wagon.tint(Houses.rgb(h.colour))
        horse.coat(Math.floorMod(c.look, 3L).toInt())
        // The wagon's time to load: as many baskets as the hour left has room for
        val left = trip.departAt - level.overworldClockTime
        val baskets = if (left < 300L) 0 else min(BASKETS, (left / 220L).toInt()).coerceAtLeast(1)
        val yw = YardWagon(y, trip.id, c.id, wagon, horse, hand, trip.load.copyOf(), baskets)
        // Known as the yard's before they join the level, so the caravans' bodies never take them for strays
        y.wagon = yw
        yw.park(snap = true)
        if (!level.addFreshEntity(wagon) || !level.addFreshEntity(horse)) {
            y.wagon = null
            if (!wagon.isRemoved) wagon.discard()
            if (!horse.isRemoved) horse.discard()
            return
        }
        hand.busy = true
        hand.clear()
        if (baskets == 0) yw.heap(BASKETS)
    }

    /** The stages of the wagon at the gate. */
    private enum class Stage { LOAD, LEAD, BACK_IN, HITCH, CLIMB, WAIT, ROLL }

    /**
     * Trip [trip]'s wagon drawn up before the gate for carter [carter] ([hand]), with [horse]: its load [load] as the trip has it, in
     * [baskets] baskets. Its back axle stands at [rear0] heading out ([hx], [hz]); it rolls out [s] blocks once [go] (its hour is up).
     */
    private class YardWagon(
        val yard: Yard, val trip: Int, val carter: Int, val wagon: Wagon, val horse: DraughtHorse, val hand: Hand, val load: IntArray, val baskets: Int,
    ) {
        val level: ServerLevel get() = yard.level
        var stage = Stage.LOAD
        var age = 0
        var stageAge = 0
        var loaded = 0
        var go = false
        var hitched = false
        var seated = false
        var s = 0.0
        var v = 0.0
        private var creak = 0

        val rear0: Vec3 = centre(yard.site, 4.5, PARKED_Z)
        val out: Pair<Double, Double> = run {
            val a = centre(yard.site, 4.5, 0.0)
            val b = centre(yard.site, 4.5, -1.0)
            val dx = b.x - a.x
            val dz = b.z - a.z
            val len = hypot(dx, dz).coerceAtLeast(1e-6)
            (dx / len) to (dz / len)
        }
        val yaw: Float = LedShow.yawOf(out.first, out.second)
        private val ref = yard.site.drawing.y0.toDouble()
        private var tether = Vec3.ZERO
        private var leadTo = Vec3.ZERO

        /** The wagon placed [along] blocks out from where it stands (its horse in the shafts, once hitched). */
        fun place(along: Double, snap: Boolean) {
            val (hx, hz) = out
            val r = ground(rear0.x + hx * along, rear0.z + hz * along)
            val k = ground(r.x + hx * Train.WHEELBASE, r.z + hz * Train.WHEELBASE)
            val hp = ground(k.x + hx * Train.AHEAD, k.z + hz * Train.AHEAD)
            wagon.place(level, r, k, hp, r.y, k.y, snap)
            if (hitched) horse.place(level, hp, yaw, hp.y, snap)
        }

        private fun ground(x: Double, z: Double): Vec3 = Vec3(x, Ground.stand(level, x, z, ref + 0.6), z)

        /** Drawn up at the gate: the brake on, the tailboard down, the horse tethered by its right side with its nosebag on. */
        fun park(snap: Boolean) {
            place(0.0, snap)
            wagon.flag(Wagon.FLAG_BRAKE, true)
            wagon.flag(Wagon.FLAG_TAILBOARD_SHUT, false)
            tether = wagon.point(LedShow.TETHER_X, 0f, LedShow.TETHER_Z)
            horse.place(level, tether, yaw, wagon.y, snap)
            horse.move(DraughtHorse.Move.NOSEBAG)
            horse.flag(DraughtHorse.FLAG_NOSEBAG, true)
            horse.flag(DraughtHorse.FLAG_REINS_TIED, true)
        }

        /** The bed heaped with [n] baskets' worth of the trip's goods. */
        fun heap(n: Int) {
            val total = load.sum()
            if (total <= 0) return
            val places = min(Wagon.SLOTS, n * PER_BASKET)
            val slots = ArrayList<Good?>()
            val goods = Good.entries.filter { load[it.ordinal] > 0 }.sortedByDescending { load[it.ordinal] }
            for (g in goods) {
                val share = Math.round(places * load[g.ordinal] / total.toDouble()).toInt().coerceAtLeast(1)
                repeat(share) { if (slots.size < places) slots.add(g) }
            }
            while (slots.size < Wagon.SLOTS) slots.add(null)
            wagon.load(slots)
        }

        /** The wagon's tick, after everyone has moved; false once it is gone. */
        fun tick(): Boolean {
            if (wagon.isRemoved || horse.isRemoved || hand.p.isRemoved || !hand.p.isAlive) return false
            age++
            stageAge++
            if (age > MOST) return false
            when (stage) {
                Stage.LOAD -> load()
                Stage.LEAD -> lead()
                Stage.BACK_IN -> backIn()
                Stage.HITCH -> if (hand.idle) {
                    harness()
                    next(Stage.CLIMB)
                    climb()
                }
                Stage.CLIMB -> if (hand.idle) next(Stage.WAIT)
                Stage.WAIT -> if (go) roll()
                Stage.ROLL -> {
                    v = min(PACE, v + ACCEL)
                    s += v
                    place(s, snap = false)
                    if (++creak % 40 == 1) {
                        val at = wagon.point(0f, -12f, -20f)
                        level.playSound(null, at.x, at.y, at.z, TradeSounds.get("trade.cart_creak"), SoundSource.NEUTRAL, 0.7f, 0.9f + level.random.nextFloat() * 0.2f)
                    }
                    if (s >= ROLL_OUT) return false
                }
            }
            if (seated) sit()
            return true
        }

        private fun next(to: Stage) {
            stage = to
            stageAge = 0
        }

        /** A basket at a time from the barrels to the tail; once loaded (or its hour up), the horse. */
        private fun load() {
            if (!hand.idle) return
            if (loaded >= baskets || go) {
                heap(BASKETS)
                wagon.flag(Wagon.FLAG_TAILBOARD_SHUT, true)
                val at = wagon.point(0f, -12f, 20f)
                level.playSound(null, at.x, at.y, at.z, TradeSounds.get("trade.tailboard"), SoundSource.NEUTRAL, 0.8f, 1f)
                leadTo = wagon.point(0f, 0f, -83f - 24f)
                horse.flag(DraughtHorse.FLAG_NOSEBAG, false)
                horse.move(DraughtHorse.Move.WALK)
                next(Stage.LEAD)
                // The carter goes to the horse's head meanwhile
                val head = wagon.point(-18f, 0f, -96f)
                hand.then(GoTo({ BlockPos.containing(head.x, head.y + 0.2, head.z) }, Jobs.WALK, 0.9, Activity.NONE, 200))
                return
            }
            val site = yard.site
            val barrels = block(site, AT_BARRELS.first, AT_BARRELS.second)
            val barrel = centre(site, BARRELS.first.toDouble(), BARRELS.second.toDouble(), 0.6)
            // LOAD_PACK is made for one spot behind the tail, facing the wagon's way: walked to, and the last steps by hand
            val spot = wagon.point(0f, 0f, LOAD_AT)
            val stand = BlockPos.containing(spot.x, spot.y + 0.2, spot.z)
            val tail = wagon.point(0f, -16f, 22f)
            val ahead = wagon.point(0f, 0f, -83f)
            val good = Good.entries.filter { load[it.ordinal] > 0 }.maxByOrNull { (load[it.ordinal] * 31 + loaded * 17) % 97 }
            val basket = good?.item?.let(::ItemStack) ?: ItemStack(Items.WHEAT)
            val n = loaded + 1
            hand.then(GoTo({ barrels }, Jobs.WALK, 0.6, Activity.NONE, 300))
                .then(Turn({ barrel }))
                .then(Play(Activity.PICK_UP, at = { barrel }, first = { p -> p.carried = basket.copy() }))
                .then(GoTo({ stand }, Jobs.WALK, 0.6, Activity.CARRY_BASKET, 300))
                .then(StoreLife.Stand({ spot }, { wagon.yRot }, Activity.CARRY_BASKET))
                .then(Play(Activity.LOAD_PACK, at = { ahead }))
                .then(Now { p ->
                    p.carried = ItemStack.EMPTY
                    heap(n)
                    level.playSound(null, tail.x, tail.y, tail.z, TradeSounds.get("trade.sack_lift"), SoundSource.NEUTRAL, 0.7f, 0.9f + level.random.nextFloat() * 0.2f)
                })
            loaded++
        }

        /** The horse led round from its tether to before the shafts. */
        private fun lead() {
            val f = (stageAge / LEAD.toDouble()).coerceAtMost(1.0)
            val e = f * f * (3 - 2 * f)
            val at = tether.lerp(leadTo, e)
            val dx = leadTo.x - tether.x
            val dz = leadTo.z - tether.z
            val turn = if (hypot(dx, dz) < 1e-3) yaw else LedShow.yawOf(dx, dz)
            horse.place(level, at, Mth.rotLerp(e.toFloat(), turn, yaw), wagon.y, false)
            if (stageAge >= LEAD) {
                horse.move(DraughtHorse.Move.WALK)
                next(Stage.BACK_IN)
            }
        }

        /** Backed into the shafts; then the carter at its shoulder clips the traces. */
        private fun backIn() {
            val into = wagon.point(0f, 0f, -83f)
            val f = (stageAge / BACK.toDouble()).coerceAtMost(1.0)
            val e = f * f * (3 - 2 * f)
            horse.place(level, leadTo.lerp(into, e), yaw, wagon.y, false)
            if (stageAge < BACK) return
            horse.move(DraughtHorse.Move.IDLE)
            next(Stage.HITCH)
            hand.then(GoTo({ shoulder().let { BlockPos.containing(it.x, it.y + 0.2, it.z) } }, Jobs.WALK, 0.6, Activity.NONE, 200))
                .then(StoreLife.Stand({ shoulder() }, { atShoulder() }))
                .then(object : Move() {
                    override fun start(p: Townsperson, a: Aside) {
                        p.navigation.stop()
                        p.act(Activity.HITCH)
                    }

                    override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
                        // Held at its shoulder, square to it, as the horse stands (as the Bill's yard hand is)
                        val at = shoulder()
                        StoreLife.Stand.put(p, at.x, at.z, atShoulder())
                        if (t == TRACE_UP && !hitched) wagon.flag(Wagon.FLAG_TRACE_UP, true)
                        if (t == CLIPPED) harness()
                        return t < Activity.HITCH.ticks
                    }
                })
        }

        /** Where HITCH is made to be played: beside the horse's right shoulder, 1.0 block out from its middle line and 0.3 ahead, as it stands now (WagonBill's hitch). */
        private fun shoulder(): Vec3 {
            val (fx, fz) = LedShow.forward(horse.yRot)
            return Vec3(horse.x - fz * 1.0 + fx * 0.3, horse.y, horse.z + fx * 1.0 + fz * 0.3)
        }

        /** Facing the horse's side square from [shoulder]. */
        private fun atShoulder(): Float = Mth.wrapDegrees(horse.yRot - 90f)

        /** The horse in the shafts and the traces clipped. */
        fun harness() {
            if (hitched) return
            hitched = true
            wagon.horse(horse)
            horse.wagon(wagon)
            wagon.flag(Wagon.FLAG_TRACE_UP, false)
            horse.flag(DraughtHorse.FLAG_NOSEBAG, false)
            horse.flag(DraughtHorse.FLAG_REINS_TIED, true)
            horse.move(DraughtHorse.Move.IDLE)
            place(0.0, snap = false)
            level.playSound(null, horse.x, horse.y + 1.0, horse.z, TradeSounds.get("trade.harness_buckle"), SoundSource.NEUTRAL, 0.8f, 1f)
        }

        /** The carter round to the wheel and up onto the driver's end of the bench. */
        private fun climb() {
            val (at, turn) = beside(ClimbUp.CLIMB_RIGHT, -ClimbUp.CLIMB_BEHIND, ClimbUp.CLIMB_TURN)
            hand.then(GoTo({ BlockPos.containing(at.x, at.y + 0.2, at.z) }, Jobs.WALK, 0.5, Activity.NONE, 200))
                .then(object : Move() {
                    private var t0 = -1

                    override fun start(p: Townsperson, a: Aside) {
                        p.navigation.stop()
                        hold(p, true)
                    }

                    override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
                        if (t0 < 0) {
                            // The last steps to the wheel, by hand
                            val dx = at.x - p.x
                            val dz = at.z - p.z
                            val d = hypot(dx, dz)
                            if (d > 0.09 && t < 30) {
                                p.setPos(p.x + dx / d * 0.09, at.y, p.z + dz / d * 0.09)
                                return true
                            }
                            p.face(turn)
                            wagon.crew(0, p.id)
                            wagon.seat(0, Wagon.DRIVER)
                            seated = true
                            sit()
                            p.act(Activity.CLIMB_UP)
                            t0 = t
                            return true
                        }
                        if (t - t0 < Activity.CLIMB_UP.ticks) return true
                        p.act(Activity.DRIVE)
                        return false
                    }
                })
        }

        /** Its hour up and the carter on the bench: the brake off, a word, and away. */
        private fun roll() {
            next(Stage.ROLL)
            wagon.flag(Wagon.FLAG_BRAKE, false)
            horse.move(DraughtHorse.Move.WALK)
            hand.p.act(Activity.DRIVE_URGE)
            StoreLife.say(hand.p, OUT[level.random.nextInt(OUT.size)])
            val at = wagon.point(-11f, -24f, -40f)
            level.playSound(null, at.x, at.y, at.z, TradeSounds.get("trade.rein_slack"), SoundSource.NEUTRAL, 0.8f, 1f)
        }

        /** The driver on their seat as the wagon now stands. */
        private fun sit() {
            val p = hand.p
            val (at, seatYaw) = wagon.seatAt(Wagon.DRIVER)
            p.setPos(at.x, at.y, at.z)
            p.deltaMovement = Vec3.ZERO
            p.yRot = seatYaw
            p.yBodyRot = seatYaw
            p.yHeadRot = seatYaw + Mth.clamp(Mth.wrapDegrees(p.yHeadRot - seatYaw), -70f, 70f)
            if (stage == Stage.ROLL && p.activity != Activity.DRIVE && p.actDone) p.act(Activity.DRIVE)
        }

        /** Where one stands on the ground beside the driver's seat, [right] px to its right and [ahead] px ahead, facing [turn] from it (the caravans' crew's own reckoning). */
        private fun beside(right: Float, ahead: Float, turn: Float): Pair<Vec3, Float> {
            val seat = Wagon.seatOf(Wagon.DRIVER) ?: return wagon.position() to yaw
            val a = Math.toRadians(seat.yaw.toDouble())
            val ax = sin(a).toFloat()
            val az = -cos(a).toFloat()
            val rx = -cos(a).toFloat()
            val rz = -sin(a).toFloat()
            val point = wagon.point(seat.x + rx * right + ax * ahead, 0f, seat.z + rz * right + az * ahead)
            return Vec3(point.x, Ground.stand(level, point.x, point.z, wagon.y + 0.5), point.z) to Mth.wrapDegrees(wagon.yRot + seat.yaw + turn)
        }

        /** Gone: the bodies let go of (with a puff of dust where it was, if [puff]), and its carter with it. */
        fun finish(puff: Boolean) {
            if (puff && !wagon.isRemoved) LedShow.dust(level, wagon.point(0f, -20f, -16f), 14)
            if (wagon.crew(0) == hand.p.id) {
                wagon.crew(0, -1)
                wagon.seat(0, Wagon.OFF)
            }
            if (!wagon.isRemoved) wagon.discard()
            if (!horse.isRemoved) horse.discard()
            if (yard.hands[carter] === hand) yard.hands.remove(carter)
            hand.discard()
        }
    }

    /** Moved by hand (on the wagon) or let go to walk on their own. */
    private fun hold(p: Townsperson, byHand: Boolean) {
        p.noPhysics = byHand
        p.isNoGravity = byHand
        p.deltaMovement = Vec3.ZERO
        if (byHand) p.navigation.stop()
    }

    // ---- Each tick ----

    private fun tick(level: ServerLevel) {
        val now = level.gameTime
        for (y in yards.values.toList()) {
            if (y.level !== level) continue
            // Its land unloaded, or the works gone: let go
            if (now - y.seen > STALE) {
                drop(y)
                yards.remove(y.works)
                continue
            }
            val wagon = y.wagon ?: continue
            val going = try {
                wagon.tick()
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A trading house's yard wagon could not be placed", e)
                false
            }
            if (!going) {
                wagon.finish(puff = wagon.stage == Stage.ROLL)
                if (y.wagon === wagon) y.wagon = null
            }
        }
    }

    // ---- Where things are ----

    /** The middle of the drawing's ([x], [z]) at the yard's ground, [up] over it. */
    private fun centre(site: TownWorksSite, x: Double, z: Double, up: Double = 0.0): Vec3 = site.frame.centre(x, site.drawing.y0 + up, z)

    private fun block(site: TownWorksSite, x: Int, z: Int): BlockPos = site.frame.world(x, site.drawing.y0, z)

    /** The yaw facing the drawing's way ([dx], [dz]). */
    private fun yawOf(site: TownWorksSite, dx: Double, dz: Double): Float {
        val a = site.frame.centre(0.0, 0.0, 0.0)
        val b = site.frame.centre(dx, 0.0, dz)
        return LedShow.yawOf(b.x - a.x, b.z - a.z)
    }
}
