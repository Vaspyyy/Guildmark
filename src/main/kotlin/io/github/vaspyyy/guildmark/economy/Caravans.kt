package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.atlas.Atlas
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.outlaw.Crime
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamp
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.quest.CaravanEscorts
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.RoadTopics
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BiomeTags
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A capital's imports on wagons that come in along its roads, and what befalls them on the way
 * (living-world/design-road.md). The saved records, the timetable, the loads and the rolls are kept here; the wagons,
 * horses, crews, wrecks and drivers seen on the road (caravan/) read them and report back only through what follows,
 * so the same caravan comes out the same whether anyone watches it or not.
 *
 * Each of the day's three caravans (9, 12 and 15 o'clock) is a wagon on one of the capital's roads in turn (see
 * [CaravanRoutes] for the ways in). It's planned within the hour it must set out, loaded when it does with what
 * [Economy.bring] would have brought at its hour, and rolls in at [PACE]: its distance is its pace times the time since
 * it set out, less what it was held up. Whether it's struck on the way, and how that comes out, is rolled from numbers
 * fixed by its capital, hour and road; setting out, the strike and arrival are settled as the clock passes them (a
 * second at a time while the market is caught up, in the market's hourly steps while it catches up), so a skipped
 * night comes out as a watched one. While its bodies are about they act each of those out first, and the record and
 * the market's hour wait for them (a little while at most). Times in the records are the overworld's clock ticks.
 */
object Caravans {
    /** How far a wagon goes in a tick, in blocks: a heavy horse's walk, the same for every wagon, seen or not. */
    const val PACE = 0.12

    /** Where a caravan is in its day. */
    enum class State {
        /** At the start of its leg, waiting for its escort to come (see [View.escort]). */
        WAITING,

        /** On its way in along its leg. */
        ROLLING,

        /** Being waylaid at its strike point, the fight being played out or fought. */
        STRUCK,

        /** Taken by the band; the wreck lies by the road (see [wrecks]) and the driver is on their way to the gate. */
        WAYLAID,

        /** At its stand in the capital, unloading, then standing an hour with the horse in its nosebag. */
        STANDING,

        /** Driving back out the way it came, empty but for the exports heaped on its bed. */
        LEAVING,
    }

    /** A point of a caravan's leg: where it is, and how far along the leg from its start. */
    class Point(val pos: Vec3, val along: Double)

    /**
     * A caravan as its bodies need it: [id]; the [capital] it's bound for (its bell); its [leg], from the far end to its
     * stand, fixed once worked out; how far along it the record has it [now]; its [state]; what it carries ([load], in
     * the order it's heaped, and [exports] on the way out); its [guards] and [wagons] (two in a Perilous road's convoy);
     * the player escorting it, once they've met it ([escort]); how far along the leg it will be struck ([strike], null
     * if it won't be); a [seed] for its looks (the driver's and guards' faces, the horse's coat); and the game time it
     * must be done by ([deadline]), arrived or waylaid, whatever its bodies are doing.
     */
    class View(
        val id: Long, val capital: BlockPos, val leg: List<Point>, val now: Double, val state: State,
        val load: List<Pair<Good, Int>>, val exports: List<Pair<Good, Int>>, val guards: Int, val wagons: Int,
        val escort: UUID?, val strike: Double?, val seed: Long, val deadline: Long,
    )

    /** What becomes of the horse when a wagon is waylaid. */
    enum class Horse { BOLTS, CUT_DOWN }

    /**
     * How a strike comes out, as rolled when the caravan set out: [waylaid] or fought off, the [horse] (when waylaid),
     * what the band carries off ([taken]) and how many [waylayers] run in, and the band's id among the outlaw bands.
     */
    class Strike(val waylaid: Boolean, val horse: Horse, val taken: List<Pair<Good, Int>>, val waylayers: Int, val band: Int)

    /**
     * A wreck by the road where a wagon was waylaid: where it lies ([pos], turned [yaw]), the capital it was bound for,
     * its [lots] (a crate or a sack each; null once taken), whether the horse lies dead in its traces, and the game time
     * it's cleared away by ([until]).
     */
    class Wreck(val id: Long, val capital: BlockPos, val pos: Vec3, val yaw: Float, val lots: List<Pair<Good, Int>?>, val horseDead: Boolean, val until: Long)

    /**
     * The driver of a waylaid wagon, back at the capital's gate: the caravan, where they sit ([at]), until when, what
     * was lost, and the band's camp, which they mark on the map of anyone who asks.
     */
    class Driver(val caravan: Long, val capital: BlockPos, val at: BlockPos, val until: Long, val lost: List<Pair<Good, Int>>, val camp: BlockPos?)

    /**
     * Hears what happens to caravans whether or not their bodies are about (to make, move or drop them). [struck] is
     * heard once a strike is settled, with how it came out: at once off screen, at the end of the fight on screen.
     */
    interface Listener {
        fun setOut(level: ServerLevel, caravan: View) {}
        fun struck(level: ServerLevel, caravan: View, strike: Strike) {}
        fun arrived(level: ServerLevel, caravan: View) {}
        fun ended(level: ServerLevel, caravan: Long) {}
        fun wreckCleared(level: ServerLevel, wreck: Long) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        listeners.add(listener)
    }

    /** Where on the road a wagon can be waylaid, as the stalls' reasons, the crier and the townsfolk name it ([Event.place]). */
    val PLACES: List<String> = listOf("ford", "woods", "hill", "crossroads", "road")

    // ---- Timing ----

    /** Clock ticks in a market hour, and in a day. */
    private const val HOUR = 1000L
    private const val DAY = 24000L

    /** How long a wagon stands at its stand once it's in, the horse in its nosebag. */
    private const val STAND = 1000L

    /** How long past its time a wagon waits at the start of its leg for its escort; a Closed road's waits until the evening ([EVENING], into the clock's day). */
    private const val ESCORT_WAIT = 2000L
    private const val EVENING = 12000L

    /** Hours after its hour that a wagon must be in by, arrived or waylaid. */
    private const val LATE = 6L

    /** How long a fight off screen holds a wagon up when it's beaten off, and the longest one on screen goes before the dice finish it. */
    private const val FIGHT = 600L
    private const val FIGHT_MOST = 2400L

    /** How fast a waylaid wagon's driver runs for the gate, in blocks a tick. */
    private const val RUN = 0.25

    /** How long a wreck lies by the road, and how long a band keeps what it took before it's sold. */
    private const val WRECK = 4 * DAY
    private const val LOOT = 7 * DAY

    /** A band waylays no wagon within this long of its last strike. */
    private const val BAND_REST = 2 * DAY

    /** Market hours a capital's market must be open before a band may come to settle by one of its roads (open question 3). */
    private const val FOUND_AFTER = 5 * 24L

    /** How long a waylaid wagon's trouble lasts at the least, and the most it can go on while no wagon gets in by its road (market hours). */
    private const val WAYLAID_LEAST = 48L
    private const val WAYLAID_MOST = 14 * 24L
    private const val WAYLAID_AFTER = 24L

    /** How many wagons' worth of imports a relief wagon may carry: two days of them. */
    private const val RELIEF = 6

    /** Game ticks the bodies count as about after they last said so; how long the record waits for them to act an event out; how stale an event may be for them to act it at all. */
    private const val SHOWN = 100L
    private const val GRACE = 600L
    private const val FRESH = 600L

    /** How near the start of its leg its escort must come to meet a wagon, and how near its stand to see it in. */
    const val MEET = 16.0
    const val SEEN_IN = 32.0

    /** How near a wreck's lot a player must stand to take it, and how far off everyone must be for a wreck to be cleared away. */
    private const val REACH = 6.0
    private const val CLEAR = 48.0

    /** The most a crate or a sack holds, and the most lots a wreck has. */
    private const val LOT = 8
    private const val LOTS = 12

    /** How far off the road a wreck lies, and the driver sits by the gate. */
    private const val VERGE = 3.5
    private const val BY_GATE = 2.5

    /** Market hours of the timetable looked at ahead. */
    private const val AHEAD = 12L

    /** How far along the road either side of the strike point its lie is read, and how much higher it must be to be a hill. */
    private const val HILL_SPAN = 48.0
    private const val HILL_RISE = 6.0
    private const val WET_SPAN = 8.0
    private const val CROSSING = 16

    /** Server ticks between looks at the records, and the tick of those it falls on (clear of the markets' own). */
    private const val TICK = 20
    private const val TICK_AT = 7

    // ---- Switches ----

    /** Whether the wagons carry the imports (caravans; with markets on). Off, the goods come at the hour as they always have. */
    fun enabled(): Boolean = Markets.enabled() && setting { GuildmarkConfig.CARAVANS.get() }

    /** Whether bands waylay wagons, and roads keep their peril (roadAmbushes, with caravans and outlaws on). */
    fun ambushes(): Boolean = enabled() && Outlaws.enabled() && setting { GuildmarkConfig.ROAD_AMBUSHES.get() }

    /** Whether a troubled road's capital posts escort notes and wanted notes on its band (caravanEscorts). */
    fun escorts(): Boolean = enabled() && setting { GuildmarkConfig.CARAVAN_ESCORTS.get() }

    /** Whether a wreck holds the goods it spilled (roadSalvage); off, it's scenery and its goods are lost. */
    fun salvage(): Boolean = enabled() && setting { GuildmarkConfig.ROAD_SALVAGE.get() }

    private inline fun setting(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }

    private var registered = false

    /** Starts the wagons on their roads, and the road's talk among the townsfolk: once, at setup. */
    fun register() {
        if (registered) return
        registered = true
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        // The capitals with a market found a few a tick, ahead of the comings, the works and the wagons asking for them
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { CaravanRoutes.ahead(it.server.overworld()) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) {
            CaravanRoutes.forget()
            // A second world opened the same game heals its bands from its own first day
            healed = Long.MIN_VALUE
        }
        Topics.register(RoadTopics)
    }

    /**
     * A road line for [p] to say in the one-line talk (to [player]) and in gossip (to whoever's by), for the hook in
     * Lines.talk and Lines.gossip (design-road.md section 7): the weightiest thing the road gives them to say just now,
     * a spoken line with its facts after it in grey, or null. The talk itself asks [RoadTopics] as a topic source.
     */
    fun talk(p: Townsperson, player: ServerPlayer? = null): String? = if (enabled()) RoadTopics.line(p, player) else null

    // ---- What the bodies call (design-road.md section 7) ----

    /** Every caravan whose leg comes within [range] blocks of [pos]. */
    fun near(level: ServerLevel, pos: BlockPos, range: Double): List<View> {
        if (level.dimension() != Level.OVERWORLD || !enabled()) return listOf()
        val t = level.overworldClockTime
        return CaravanData.get(level).caravans.mapNotNull { c ->
            if (c.phase == Phase.PLANNED) return@mapNotNull null
            val leg = CaravanRoutes.cached(c.capital, c.road) ?: return@mapNotNull null
            if (!leg.near(pos.x + 0.5, pos.z + 0.5, range)) return@mapNotNull null
            view(level, c, t)
        }
    }

    /** The bodies of caravan [id] were held up for [ticks] (something in the road, or started further back): the record waits too. */
    fun waited(id: Long, ticks: Int) {
        if (ticks <= 0) return
        val level = overworld() ?: return
        val data = CaravanData.get(level)
        val c = data.caravan(id) ?: return
        when (c.phase) {
            Phase.ROLLING -> c.held += ticks
            Phase.LEAVING -> c.until += ticks
            else -> return
        }
        data.changed()
    }

    /** Caravan [id]'s bodies have reached its strike point: how it comes out, unless a player joins the fight (see [fought]). */
    fun struck(id: Long): Strike? {
        val level = overworld() ?: return null
        val data = CaravanData.get(level)
        val c = data.caravan(id) ?: return null
        val entry = Markets.get(level)[BlockPos.of(c.capital)] ?: return null
        if (c.phase == Phase.STRUCK) return OutlawCamps.get(level).band(c.band)?.let { dice(data, c, it) }
        if (c.phase != Phase.ROLLING || c.struck != 0 || c.strike < 0) return null
        return reachStrike(level, data, entry, c, level.overworldClockTime, onScreen = true)
    }

    /** A fight at caravan [id] that a player joined has ended: [waylaid] or fought off, [kills] waylayers dead, the horse with it or not. */
    fun fought(id: Long, waylaid: Boolean, kills: Int, horseDead: Boolean) {
        val level = overworld() ?: return
        val data = CaravanData.get(level)
        val c = data.caravan(id)?.takeIf { it.phase == Phase.STRUCK } ?: return
        val entry = Markets.get(level)[BlockPos.of(c.capital)] ?: return
        val t = level.overworldClockTime
        resolve(level, data, entry, c, t, waylaid, kills.coerceAtLeast(0), horseDead, fought = true, delay = t - c.struckAt)
    }

    /** Caravan [id]'s wagon has pulled up at its stand: its load goes into the market. */
    fun reached(id: Long) {
        val level = overworld() ?: return
        val data = CaravanData.get(level)
        val c = data.caravan(id)?.takeIf { it.phase == Phase.ROLLING } ?: return
        if (c.struck == 0 && c.strike >= 0) return
        val t = level.overworldClockTime
        if (distance(c, t) < c.length - ARRIVE_SLACK) return
        val entry = Markets.get(level)[BlockPos.of(c.capital)] ?: return
        arrive(level, data, entry, c, t)
    }

    /** How far short of its stand a wagon's bodies may say it's in. */
    private const val ARRIVE_SLACK = 6.0

    /** Every wreck within [range] blocks of [pos]. */
    fun wrecks(level: ServerLevel, pos: BlockPos, range: Double): List<Wreck> = wrecksOf(level, pos, range, piles = false)

    /** [player] takes lot [lot] of wreck [wreck]: the goods, marked as off that wagon, or nothing if it's gone. */
    fun take(player: ServerPlayer, wreck: Long, lot: Int): ItemStack {
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD || !salvage() || player.isSpectator) return ItemStack.EMPTY
        val data = CaravanData.get(level)
        val w = data.wrecks.firstOrNull { it.id == wreck } ?: return ItemStack.EMPTY
        val cargo = w.lots.getOrNull(lot)?.takeIf { it.count > 0 } ?: return ItemStack.EMPTY
        if (flat(player.position(), Vec3(w.x, w.y, w.z)) > REACH + 2.0) return ItemStack.EMPTY
        if (cargo.good.item == null) return ItemStack.EMPTY
        val count = cargo.count
        cargo.count = 0
        val name = data.capitalOrNull(cargo.capital)?.name.orEmpty()
        data.takes.add(TakeRec(player.uuid, player.name.string, cargo.capital, cargo.caravan, cargo.good, count, level.overworldClockTime))
        data.changed()
        return Marked.stack(cargo.good, count, WagonMark(cargo.capital, cargo.caravan, name))
    }

    /** Every waylaid wagon's driver at a gate within [range] blocks of [pos]. */
    fun drivers(level: ServerLevel, pos: BlockPos, range: Double): List<Driver> {
        if (level.dimension() != Level.OVERWORLD) return listOf()
        val t = level.overworldClockTime
        val camps = OutlawCamps.get(level)
        return CaravanData.get(level).drivers.filter { t in it.from..it.until && flat(it.at, pos) <= range }.map { d ->
            Driver(d.caravan, BlockPos.of(d.bell), d.at, gameAt(level, d.until), d.lost.map { it.good to it.count }, camps.band(d.band)?.takeIf { !it.slain }?.pos)
        }
    }

    /** [player] has heard driver [caravan]'s story: the camp goes on their map. True the first time. */
    fun told(player: ServerPlayer, caravan: Long): Boolean {
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD) return false
        val data = CaravanData.get(level)
        val d = data.drivers.firstOrNull { it.caravan == caravan } ?: return false
        if (!d.told.add(player.uuid)) return false
        data.changed()
        val band = OutlawCamps.get(level).band(d.band)?.takeIf { !it.slain } ?: return true
        Atlas.note(player, AtlasPlace(AtlasPlace.OUTLAW_CAMP, Level.OVERWORLD, band.pos, ref = band.id, found = level.gameTime))
        return true
    }

    // ---- Added for the bodies ----

    /**
     * Caravan [id]'s bodies are about (call it every tick or every few while they are): its strike and its arrival
     * wait for them to act them out ([struck], [reached]), a little while at most, and so does its market's hour.
     */
    fun shown(id: Long) {
        val level = overworld() ?: return
        CaravanData.get(level).caravan(id)?.shown = level.gameTime
    }

    /** The fight at caravan [id], which no player joined, has been played out on screen: the dice's result stands. */
    fun played(id: Long) {
        val level = overworld() ?: return
        val data = CaravanData.get(level)
        val c = data.caravan(id)?.takeIf { it.phase == Phase.STRUCK } ?: return
        val entry = Markets.get(level)[BlockPos.of(c.capital)] ?: return
        val band = OutlawCamps.get(level).band(c.band)
        val strike = band?.let { dice(data, c, it) }
        val t = level.overworldClockTime
        resolve(level, data, entry, c, t, strike?.waylaid == true, 0, strike?.horse == Horse.CUT_DOWN, fought = false, delay = t - c.struckAt)
    }

    /** Every broken band's loot pile within [range] blocks of [pos], as wrecks are (its lots marked as off the wagons they were taken from). */
    fun piles(level: ServerLevel, pos: BlockPos, range: Double): List<Wreck> = wrecksOf(level, pos, range, piles = true)

    // ---- Added by Part B, the bodies ----

    /** Caravan [id] as its bodies see it now (to follow it tick by tick), or null once its record has ended. */
    fun view(id: Long): View? {
        val level = overworld() ?: return null
        if (!enabled()) return null
        val c = CaravanData.get(level).caravan(id)?.takeIf { it.phase != Phase.PLANNED } ?: return null
        return view(level, c, level.overworldClockTime)
    }

    /** Caravan [id]'s way in (where the farms and the gate fall along it), or null. */
    fun leg(id: Long): CaravanRoutes.Leg? {
        val level = overworld() ?: return null
        val c = CaravanData.get(level).caravan(id) ?: return null
        return CaravanRoutes.cached(c.capital, c.road)
    }

    /**
     * Whether caravan [id] would be struck if its bodies reached its strike point now (its band is still there and
     * may strike), so they can brace for it a little before they get there; [struck] still has the last word.
     */
    fun strikes(id: Long): Boolean {
        if (!ambushes()) return false
        val level = overworld() ?: return false
        val data = CaravanData.get(level)
        val c = data.caravan(id) ?: return false
        if (c.phase != Phase.ROLLING || c.struck != 0 || c.strike < 0) return false
        val band = OutlawCamps.get(level).band(c.band) ?: return false
        return allowed(level, data, band, level.overworldClockTime)
    }

    /** Where caravan [id]'s wreck lies, or would if it's waylaid at its strike point (on the verge, turned as the road), and its yaw; null with no strike. */
    fun verge(id: Long): Pair<Vec3, Float>? {
        val level = overworld() ?: return null
        val c = CaravanData.get(level).caravan(id)?.takeIf { it.strike >= 0.0 } ?: return null
        return Vec3(c.wx, c.wy, c.wz) to c.wyaw
    }

    /**
     * A waylayer fell in a real fight carrying off [count] of [good] from caravan [caravan]: that much of what the band
     * took drops on the road (marked as off the wagon, as a wreck's lots are), if the band still has it; else nothing.
     */
    fun dropped(level: ServerLevel, caravan: Long, good: Good, count: Int): ItemStack {
        if (count <= 0 || !salvage() || good.item == null) return ItemStack.EMPTY
        val data = CaravanData.get(level)
        for (band in data.bands) {
            val cargo = band.loot.firstOrNull { it.caravan == caravan && it.good == good && it.count > 0 } ?: continue
            val n = min(count, cargo.count)
            cargo.count -= n
            if (cargo.count <= 0) band.loot.remove(cargo)
            data.changed()
            val name = data.capitalOrNull(cargo.capital)?.name.orEmpty()
            return Marked.stack(good, n, WagonMark(cargo.capital, caravan, name))
        }
        return ItemStack.EMPTY
    }

    private fun wrecksOf(level: ServerLevel, pos: BlockPos, range: Double, piles: Boolean): List<Wreck> {
        if (level.dimension() != Level.OVERWORLD) return listOf()
        val at = Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        return CaravanData.get(level).wrecks.filter { it.pile == piles && flat(Vec3(it.x, it.y, it.z), at) <= range }.map { w ->
            Wreck(
                w.id, BlockPos.of(w.bell), Vec3(w.x, w.y, w.z), w.yaw, w.lots.map { if (it.count > 0) it.good to it.count else null },
                w.horseDead, gameAt(level, w.until),
            )
        }
    }

    // ---- The market's side (Markets) ----

    /**
     * [entry]'s market works out [hour]: first what befell its wagons up to the hour, then the morning's fading of its
     * roads, then the timetable (the wagons that set out within the hour, loaded when they do). True if this hour's
     * caravan comes by wagon (it's on the road, got in already or was lost), so the market doesn't bring its goods.
     */
    fun hour(level: ServerLevel, entry: Markets.Entry, hour: Long): Boolean {
        val data = CaravanData.get(level)
        val center = entry.market.capital
        if (!enabled()) {
            flush(level, data, entry)
            return false
        }
        settle(level, data, entry, hour * HOUR)
        val cap = data.capital(center, hour)
        if (cap.name.isEmpty()) CaravanRoutes.capital(level, center)?.let { cap.name = VillageNames.of(it.boardAnchor) }
        cap.handled.removeIf { it < hour - 48 }
        cap.covered.removeIf { it < hour - 48 }
        if (Math.floorMod(hour, 24L) == 0L) RoadPeril.morning(data, center, hour)
        val legs = CaravanRoutes.legs(level, center)
        if (legs != null) {
            survey(level, data, cap, hour)
            found(level, data, cap, legs, hour)
            plan(level, data, entry, cap, legs, hour)
        }
        data.changed()
        return hour in cap.handled
    }

    /**
     * Whether [entry]'s market may work out [hour] now: not while one of its wagons whose bodies are about is waiting
     * for them to act out a strike or its arrival that falls by then (a little while at most).
     */
    fun ready(level: ServerLevel, entry: Markets.Entry, hour: Long): Boolean {
        if (!enabled()) return true
        val t = hour * HOUR
        val now = level.overworldClockTime
        return CaravanData.get(level).caravans.none { c ->
            val waits = c.capital == entry.market.capital && c.phase == Phase.ROLLING && due(c).let { at -> at <= t && bodiesAct(level, c, at, now) }
            if (waits && c.waiting < 0) c.waiting = level.gameTime
            waits
        }
    }

    /**
     * Time set back [hours] for [m]: its wagons not yet in are called off and their goods come in at once; the
     * timetable from the new hour on is to plan again, and everything else on its roads keeps the time it had left.
     */
    fun rewind(m: Market, hours: Long) {
        val level = overworld() ?: return
        val data = CaravanData.get(level)
        val shift = hours * HOUR
        val kept = HashSet<Long>()
        for (c in data.caravans.filter { it.capital == m.capital }) {
            if (c.phase in setOf(Phase.WAITING, Phase.ROLLING, Phase.STRUCK)) {
                deliver(m, c.load)
                kept.addAll(c.slots)
            }
            callOff(level, data, c)
        }
        data.capitalOrNull(m.capital)?.let { cap ->
            cap.handled.removeIf { it > m.hour && it !in kept }
            cap.covered.removeIf { it > m.hour }
            val news = cap.news.map { RoadNews(it.hour - hours, it.kind, it.road, it.names) }
            cap.news.clear()
            cap.news.addAll(news)
            for (k in cap.cool.keys.toList()) cap.cool[k] = cap.cool.getValue(k) - shift
        }
        for (w in data.wrecks.filter { it.capital == m.capital }) w.until -= shift
        val drivers = data.drivers.filter { it.capital == m.capital }
        data.drivers.removeAll(drivers)
        drivers.mapTo(data.drivers) { DriverRec(it.caravan, it.capital, it.bell, it.road, it.at, it.from - shift, it.until - shift, it.lost, it.band, it.told) }
        val takes = data.takes.filter { it.capital == m.capital }
        data.takes.removeAll(takes)
        takes.mapTo(data.takes) { TakeRec(it.player, it.name, it.capital, it.caravan, it.good, it.count, it.at - shift) }
        for (r in data.roads.filter { it.capital == m.capital }) r.struck -= shift
        data.changed()
    }

    // ---- Settling what befalls the wagons ----

    /** Once a second: the wagons of every market that's caught up, the escorts meeting theirs; now and then the wrecks, drivers, salvage kept and the bands' wounds. */
    private fun tick(server: MinecraftServer) {
        if (server.tickCount % TICK != TICK_AT) return
        val level = server.overworld()
        if (!Markets.enabled()) return
        val data = CaravanData.get(level)
        val markets = Markets.get(level)
        if (!enabled()) {
            for (entry in markets.all()) if (data.caravans.any { it.capital == entry.market.capital }) flush(level, data, entry)
            return
        }
        val t = level.overworldClockTime
        val now = Markets.hourOf(level)
        for (c in data.caravans.toList()) {
            val entry = markets[BlockPos.of(c.capital)]
            if (entry == null) {
                callOff(level, data, c)
                continue
            }
            // A market still catching up settles its wagons in its own hourly steps
            if (entry.market.hour < now) continue
            if (c.phase == Phase.WAITING) meet(level, data, c, t)
            advance(level, data, entry, c, t)
        }
        if (server.tickCount % (TICK * 5) == TICK_AT) {
            wrecks(level, data, t)
            data.drivers.removeIf { t > it.until }
            Marked.lapse(level, data, t)
            heal(level, data)
            CaravanEscorts.sweep(level, data, t)
        }
    }

    /** Everything that befalls the wagons of [entry]'s market up to clock time [t]. */
    private fun settle(level: ServerLevel, data: CaravanData, entry: Markets.Entry, t: Long) {
        for (c in data.caravans.filter { it.capital == entry.market.capital }) advance(level, data, entry, c, t)
    }

    /** Settles what befalls [c] up to clock time [t], each thing at the time it falls. */
    private fun advance(level: ServerLevel, data: CaravanData, entry: Markets.Entry, c: CaravanRec, t: Long) {
        var steps = 0
        while (steps++ < 16 && c in data.caravans) {
            val at = due(c)
            if (at > t) return
            if (c.phase == Phase.ROLLING && bodiesAct(level, c, at, t)) {
                if (c.waiting < 0) c.waiting = level.gameTime
                return
            }
            step(level, data, entry, c, at)
        }
    }

    /** The next thing that falls to [c], as clock time. */
    private fun due(c: CaravanRec): Long = when (c.phase) {
        Phase.PLANNED -> c.setOut
        Phase.ROLLING -> min(deadline(c), c.setOut + c.held + travel(if (c.struck == 0 && c.strike >= 0) c.strike else c.length))
        Phase.STRUCK -> min(c.until, deadline(c))
        Phase.WAITING, Phase.WAYLAID, Phase.STANDING, Phase.LEAVING -> c.until
    }

    /** The clock time [c] must be in by, arrived or waylaid: its hour plus [LATE] (from when it really set out, for one that waited). */
    private fun deadline(c: CaravanRec): Long = c.setOut + travel(c.length) + LATE * HOUR

    /**
     * Whether [c]'s bodies are about to act out the strike or arrival that falls to it at [at] (it's now [t]): the
     * record waits for them a little while; not once that's run out, the event is long past (the clock jumped), or
     * it's the wagon's deadline.
     */
    private fun bodiesAct(level: ServerLevel, c: CaravanRec, at: Long, t: Long): Boolean {
        if (level.gameTime - c.shown > SHOWN) return false
        if (t - at > FRESH || at >= deadline(c)) return false
        return c.waiting < 0 || level.gameTime - c.waiting < GRACE
    }

    /** What falls to [c] at [at], by where it is in its day. */
    private fun step(level: ServerLevel, data: CaravanData, entry: Markets.Entry, c: CaravanRec, at: Long) {
        when (c.phase) {
            Phase.PLANNED -> depart(level, data, entry, c, at)
            Phase.WAITING -> if (c.escortOnly && !c.met) withdraw(level, data, c) else go(level, data, c, at, alone = !c.met)
            Phase.ROLLING -> {
                if (c.struck == 0 && c.strike >= 0) reachStrike(level, data, entry, c, at, onScreen = false)
                // At its deadline whatever its bodies are doing; or at its stand
                if (c.phase == Phase.ROLLING && (at >= deadline(c) || c.struck != 0 || c.strike < 0)) {
                    if (at >= deadline(c) || at >= c.setOut + c.held + travel(c.length)) arrive(level, data, entry, c, at)
                }
            }
            Phase.STRUCK -> {
                val strike = OutlawCamps.get(level).band(c.band)?.let { dice(data, c, it) }
                resolve(level, data, entry, c, at, strike?.waylaid == true, 0, strike?.horse == Horse.CUT_DOWN, fought = false, delay = at - c.struckAt)
            }
            Phase.WAYLAID -> end(level, data, c)
            Phase.STANDING -> {
                c.phase = Phase.LEAVING
                c.until = at + travel(c.length)
            }
            Phase.LEAVING -> end(level, data, c)
        }
        data.changed()
    }

    // ---- The timetable ----

    /**
     * The wagons that set out within [hour]'s hour for [cap]: each of the day's three caravans still to plan goes
     * by the road whose turn it is (a Closed road's by an open one if there is one), with as many guards as the road's
     * stage asks; on a Perilous road it joins the day's noon convoy; on a Closed road with no other it waits for an
     * escort until the evening.
     */
    private fun plan(level: ServerLevel, data: CaravanData, entry: Markets.Entry, cap: CapitalRec, legs: List<CaravanRoutes.Leg>, hour: Long) {
        for (t in hour + 1..hour + AHEAD) {
            if (!Economy.caravan(clockOf(t)) || t in cap.handled) continue
            val (leg, only) = legFor(data, cap.capital, legs, t)
            if (t * HOUR - travel(leg.length) >= (hour + 1) * HOUR) continue
            val stage = RoadPeril.stage(data, cap.capital, leg.road)
            cap.handled.add(t)
            if (stage == RoadPeril.Stage.PERILOUS) {
                convoy(level, data, entry, cap, leg, t)
                continue
            }
            val c = make(level, data, entry, leg, t, Run.WAGON, t * HOUR - travel(leg.length), stage.guards, 1)
            c.escortOnly = only
            c.slots = listOf(t)
        }
    }

    /**
     * The leg slot [t]'s caravan goes by: the road whose turn it is, or the next not Closed; and whether it can go
     * only with an escort. A Closed road whose note someone holds keeps its turn, its wagon waiting for them.
     */
    private fun legFor(data: CaravanData, capital: Long, legs: List<CaravanRoutes.Leg>, t: Long): Pair<CaravanRoutes.Leg, Boolean> {
        val turn = turn(legs, t)
        val first = legs[turn]
        if (RoadPeril.stage(data, capital, first.road) != RoadPeril.Stage.CLOSED) return first to false
        if (data.escorts.any { it.capital == capital && it.road == first.road && it.state == EscortState.OPEN && it.caravan < 0 }) return first to true
        for (k in 1 until legs.size) {
            val other = legs[(turn + k) % legs.size]
            if (RoadPeril.stage(data, capital, other.road) != RoadPeril.Stage.CLOSED) return other to false
        }
        return first to true
    }

    /** Whose turn slot [t] is, of [legs]: the day's three wagons go by each road in turn. */
    private fun turn(legs: List<CaravanRoutes.Leg>, t: Long): Int =
        Math.floorMod(Math.floorDiv(t, 24L) * 3 + (clockOf(t) - 9) / 3, legs.size.toLong()).toInt()

    /**
     * When the next wagon by [road] into the capital whose heart is [capital] sets out from the start of its leg, as
     * clock time: one on its way to the start or waiting there already, else the next slot of the timetable whose
     * turn the road has (a Perilous road's at the day's noon convoy; a Closed road's if someone takes its note).
     * Null while its legs aren't worked out, or none falls to it in the next two days.
     */
    fun nextWagon(level: ServerLevel, capital: Long, road: Int): Long? {
        val data = CaravanData.get(level)
        data.caravans.filter { it.capital == capital && it.road == road && (it.phase == Phase.PLANNED || it.phase == Phase.WAITING) && it.run != Run.TEST }
            .minOfOrNull { it.setOut }?.let { return it }
        val legs = CaravanRoutes.legs(level, capital) ?: return null
        val leg = legs.firstOrNull { it.road == road } ?: return null
        val cap = data.capitalOrNull(capital)
        val now = Markets.hourOf(level)
        val t0 = level.overworldClockTime
        for (t in now + 1..now + 48) {
            if (!Economy.caravan(clockOf(t)) || cap != null && t in cap.handled) continue
            if (legs[turn(legs, t)].road != road) {
                // Another road's slot, unless that one's Closed and this one isn't: then it comes this way
                val (diverted, _) = legFor(data, capital, legs, t)
                if (diverted.road != road) continue
            }
            val due = if (RoadPeril.stage(data, capital, road) == RoadPeril.Stage.PERILOUS) max(t - clockOf(t) + 12, t) else t
            val out = due * HOUR - travel(leg.length)
            if (out >= t0) return out
        }
        return null
    }

    /** When the next wagon by [road] is due in at the stand of the capital whose heart is [capital] (clock time), or null (see [nextWagon]). */
    fun dueIn(level: ServerLevel, capital: Long, road: Int): Long? {
        val out = nextWagon(level, capital, road) ?: return null
        val leg = CaravanRoutes.cached(capital, road) ?: return null
        return out + travel(leg.length)
    }

    /** Slot [t]'s caravan on a Perilous road: it goes with the day's convoy, due at noon (or at its own hour, past noon). */
    private fun convoy(level: ServerLevel, data: CaravanData, entry: Markets.Entry, cap: CapitalRec, leg: CaravanRoutes.Leg, t: Long) {
        val due = max(t - clockOf(t) + 12, t)
        val waiting = data.caravans.firstOrNull { it.capital == cap.capital && it.road == leg.road && it.run == Run.CONVOY && it.hour == due && it.phase == Phase.PLANNED }
        if (waiting != null) {
            waiting.slots = waiting.slots + t
            return
        }
        val c = make(level, data, entry, leg, due, Run.CONVOY, due * HOUR - travel(leg.length), RoadPeril.CONVOY_GUARDS, RoadPeril.CONVOY_WAGONS)
        c.slots = listOf(t)
        cap.covered.add(t)
    }

    /** A new caravan's record for [leg], due at market [hour]: its seed, and its roll against the road's band. */
    private fun make(
        level: ServerLevel, data: CaravanData, entry: Markets.Entry, leg: CaravanRoutes.Leg, hour: Long, run: Run, setOut: Long, guards: Int, wagons: Int,
    ): CaravanRec {
        val id = data.id()
        var seed = entry.market.capital * -0x61c8864680b583ebL xor hour * 0x2545F4914F6CDD1DL xor (leg.road + 7L) * 0x5851F42D4C957F2DL
        if (run == Run.RELIEF || run == Run.TEST) seed = seed xor id * 0x632BE59BD9B4E019L
        val c = CaravanRec(
            id, entry.market.capital, leg.bell.asLong(), leg.road, hour, run, seed, Phase.PLANNED, setOut, 0L, 0L, leg.length,
            DoubleArray(Good.entries.size), DoubleArray(Good.entries.size), guards, wagons,
        )
        roll(level, data, c, leg)
        data.caravans.add(c)
        data.changed()
        return c
    }

    /**
     * [c] sets out at [at]: what its slots would have brought is its load (a convoy's a fifth less, and it takes on the
     * day's later wagons of its road); it waits at the start for an escort who took its road's note, or, on a Closed
     * road, for one who might yet.
     */
    private fun depart(level: ServerLevel, data: CaravanData, entry: Markets.Entry, c: CaravanRec, at: Long) {
        if (c.run == Run.WAGON || c.run == Run.CONVOY) {
            if (c.run == Run.CONVOY) claim(level, data, c)
            val first = c.slots.any { clockOf(it) == 9 }
            val load = Economy.bring(entry.market, entry.town, first, coming(data, c), max(1, c.slots.size))
            val share = if (c.run == Run.CONVOY) RoadPeril.CONVOY_SHARE else 1.0
            for (g in Good.entries) c.load[g.ordinal] = load[g.ordinal] * share
            c.freight = io.github.vaspyyy.guildmark.economy.works.WorksFreight.load(level, entry, c)
        }
        c.phase = Phase.ROLLING
        if (escorts()) CaravanEscorts.bind(data, c)
        if (c.job != null || c.escortOnly) {
            c.phase = Phase.WAITING
            c.until = if (c.escortOnly) Math.floorDiv(at, DAY) * DAY + EVENING else at + ESCORT_WAIT
            if (c.until <= at) c.until = at + ESCORT_WAIT
        }
        val view = view(level, c, at)
        listeners.forEach { it.setOut(level, view) }
    }

    /** A convoy setting out takes on the later wagons of the day that fall to its road while it's still Perilous. */
    private fun claim(level: ServerLevel, data: CaravanData, c: CaravanRec) {
        val cap = data.capitalOrNull(c.capital) ?: return
        val legs = CaravanRoutes.legs(level, c.capital) ?: return
        if (RoadPeril.stage(data, c.capital, c.road) != RoadPeril.Stage.PERILOUS) return
        val day = c.hour - clockOf(c.hour)
        val more = ArrayList<Long>()
        for (clock in intArrayOf(9, 12, 15)) {
            val s = day + clock
            if (s < c.hour - 3 || s in cap.handled || s in c.slots) continue
            val (leg, only) = legFor(data, c.capital, legs, s)
            if (leg.road != c.road || only) continue
            cap.handled.add(s)
            cap.covered.add(s)
            more.add(s)
        }
        if (more.isNotEmpty()) c.slots = c.slots + more
    }

    /** What's on the road to [c]'s capital already, not yet in, by good: counted as there when another wagon is loaded. */
    private fun coming(data: CaravanData, c: CaravanRec): DoubleArray {
        val out = DoubleArray(Good.entries.size)
        for (o in data.caravans) {
            if (o === c || o.capital != c.capital || o.phase !in setOf(Phase.WAITING, Phase.ROLLING, Phase.STRUCK)) continue
            for (g in Good.entries) out[g.ordinal] += o.load[g.ordinal]
        }
        return out
    }

    /** [c] goes from the start of its leg at [at]: with its escort, or [alone] once it's waited long enough (the escort's job is off). */
    private fun go(level: ServerLevel, data: CaravanData, c: CaravanRec, at: Long, alone: Boolean) {
        c.phase = Phase.ROLLING
        c.setOut = at
        c.held = 0L
        c.waiting = -1L
        if (alone && c.job != null) {
            CaravanEscorts.failed(level, data, c, "alone")
            c.job = null
            c.holder = null
        }
    }

    /** A Closed road's wagon that nobody came to escort by evening: it never goes, and what it carried never comes. */
    private fun withdraw(level: ServerLevel, data: CaravanData, c: CaravanRec) {
        if (c.job != null) CaravanEscorts.failed(level, data, c, "lapsed")
        end(level, data, c)
    }

    /** [c]'s escort is with it at the start of its leg: they're off together, and the band won't let it by without a fight. */
    private fun meet(level: ServerLevel, data: CaravanData, c: CaravanRec, t: Long) {
        val holder = c.holder ?: return
        val player = level.server.playerList.getPlayer(holder) ?: return
        if (player.level() !== level || player.isSpectator || !player.isAlive) return
        val leg = CaravanRoutes.cached(c.capital, c.road) ?: run {
            CaravanRoutes.legs(level, c.capital)
            return
        }
        if (flat(player.position(), leg.start) > MEET) return
        c.met = true
        go(level, data, c, t, alone = false)
        CaravanRoutes.band(level, leg)?.takeIf { it.id == c.band || c.band < 0 }?.let { band ->
            c.band = band.id
            c.reach = CaravanRoutes.reach(leg, band)
            aim(level, data, c, leg, band, CaravanRoutes.strikeAt(leg, band), escorted = true)
        }
        CaravanEscorts.met(level, data, c, player, leg)
        data.changed()
    }

    // ---- The strike ----

    /** Settles, as [c]'s record is made, the gate its driver would run to and, if the road has a band, whether and where it strikes. */
    private fun roll(level: ServerLevel, data: CaravanData, c: CaravanRec, leg: CaravanRoutes.Leg) {
        val side = if (c.seed and 1L == 0L) 1.0 else -1.0
        val gate = leg.at(leg.gate)
        val (gx, gz) = normal(leg, leg.gate)
        c.gate = BlockPos.containing(gate.x + gx * BY_GATE * side, gate.y, gate.z + gz * BY_GATE * side).asLong()
        if (!ambushes()) return
        CaravanRoutes.settle(level, leg)
        val band = CaravanRoutes.band(level, leg) ?: return
        c.band = band.id
        c.reach = CaravanRoutes.reach(leg, band)
        aim(level, data, c, leg, band, CaravanRoutes.strikeAt(leg, band), escorted = false)
    }

    /** Whether [c] will be struck at [point] (its dice against the odds as they stand), and if so where the wreck would lie and what the place is like. */
    private fun aim(level: ServerLevel, data: CaravanData, c: CaravanRec, leg: CaravanRoutes.Leg, band: OutlawBand, point: Double, escorted: Boolean) {
        val p = chance(level, data, c, band, point, escorted)
        c.watched = io.github.vaspyyy.guildmark.economy.works.WorksWatch.aimed(level, c, band, point, rolls(c.seed)[0], p, escorted)
        if (rolls(c.seed)[0] >= p) {
            c.strike = -1.0
            return
        }
        spot(level, c, leg, point)
    }

    /** [c]'s strike falls at [point] along [leg]: what the place is like, and the verge its wreck would lie on. */
    private fun spot(level: ServerLevel, c: CaravanRec, leg: CaravanRoutes.Leg, point: Double) {
        c.strike = point
        c.place = place(level, leg, point)
        val side = if (c.seed and 2L == 0L) 1.0 else -1.0
        val at = leg.at(point)
        val (nx, nz) = normal(leg, point)
        c.wx = at.x + nx * VERGE * side
        c.wy = at.y
        c.wz = at.z + nz * VERGE * side
        c.wyaw = leg.yaw(point)
    }

    /**
     * The odds of [band] striking [c] at [point] (section 2's table): 0.15, less the further its camp lies off the
     * road, more by night, in rain or a storm and on a road that's been good to it, less for each guard over two, at
     * most 0.6; with an escort along at least an even chance on a Troubled or Perilous road and a certainty on a
     * Closed one; never within two days of its last strike, nor the day after it raided a village.
     */
    fun chance(level: ServerLevel, data: CaravanData, c: CaravanRec, band: OutlawBand, point: Double, escorted: Boolean): Double {
        val at = c.setOut + c.held + travel(point)
        if (!allowed(level, data, band, at)) return 0.0
        var p = 0.15
        if (c.reach > 96.0) p *= (1.0 - 0.75 * (c.reach - 96.0) / 160.0).coerceAtLeast(0.25)
        val clock = clockOf(Math.floorDiv(at, HOUR))
        if (clock >= 19 || clock < 6) p *= 1.5
        val felt = Markets.conditions(level, Math.floorDiv(at, HOUR), BlockPos.of(c.capital))
        if (felt.thunder) p *= 1.5 else if (felt.rain) p *= 1.2
        p *= 0.85.pow((c.guards - 2).coerceAtLeast(0))
        val peril = data.roadOrNull(c.capital, c.road)?.peril ?: 0
        p *= 1.0 + peril / 4.0
        p = p.coerceAtMost(0.6)
        p *= io.github.vaspyyy.guildmark.economy.works.WorksWatch.wary(level, c, band, point, at)
        if (escorted) {
            val stage = RoadPeril.stage(peril)
            if (stage == RoadPeril.Stage.CLOSED) p = 1.0 else if (stage.troubled) p = max(p, 0.5)
        }
        return p
    }

    /** Whether [band] may strike at clock time [at]: not within two days of its last strike, nor the day it raided a village or the day after. */
    private fun allowed(level: ServerLevel, data: CaravanData, band: OutlawBand, at: Long): Boolean {
        if (band.slain) return false
        val rec = data.bandOrNull(band.id)
        if (rec != null && at - rec.struck < BAND_REST) return false
        return !(band.raided >= 0 && Math.floorDiv(at, DAY) - band.raided in 0..1)
    }

    /** [band]'s strength on the road: its camp's roll (or three and its tier, unbuilt), one more for each wagon taken, one less for each waylayer lost and not made up; 2 to 8. */
    fun strength(band: OutlawBand, rec: BandRec?): Int {
        val base = if (band.built) band.members.size else 3 + band.tier
        return (base + (rec?.wagons ?: 0) - (rec?.wounds ?: 0)).coerceIn(2, 8)
    }

    /** The odds of [band] taking [c]: its strength against the guards (and a little for each driver), half again as strong. */
    fun odds(data: CaravanData, c: CaravanRec, band: OutlawBand): Double {
        val b = strength(band, data.bandOrNull(band.id)).toDouble()
        val g = c.guards + 0.3 * c.wagons + io.github.vaspyyy.guildmark.economy.works.WorksWatch.riders(c)
        return b / (b + 1.5 * g)
    }

    /** How [c]'s strike by [band] comes out by the dice: the same every time it's asked. */
    private fun dice(data: CaravanData, c: CaravanRec, band: OutlawBand): Strike {
        val u = rolls(c.seed)
        val waylaid = if (c.rigged != 0) c.rigged > 0 else u[1] < odds(data, c, band)
        val horse = if (u[2] < 0.4) Horse.CUT_DOWN else Horse.BOLTS
        val taken = if (waylaid) taken(c.load, 0.5 + 0.2 * u[3]) else listOf()
        val waylayers = min(6, strength(band, data.bandOrNull(band.id)) - 1)
        return Strike(waylaid, horse, taken, waylayers, band.id)
    }

    /** The dice fixed by [seed]: whether it's struck, who wins, the horse, how much is taken, a crate smashed. */
    private fun rolls(seed: Long): DoubleArray {
        val r = java.util.Random(seed)
        return DoubleArray(5) { r.nextDouble() }
    }

    /** What a band carries off of [load]: [share] of it by count, the dearest goods by the unit first. */
    private fun taken(load: DoubleArray, share: Double): List<Pair<Good, Int>> {
        val units = Good.entries.associateWith { floor(load[it.ordinal] + 1e-6).toInt() }.filterValues { it > 0 }
        var left = Math.round(units.values.sum() * share).toInt()
        val out = ArrayList<Pair<Good, Int>>()
        for ((g, n) in units.entries.sortedByDescending { it.key.base }) {
            if (left <= 0) break
            val k = min(n, left)
            out.add(g to k)
            left -= k
        }
        return out
    }

    /** [c] reaches its strike point at [at]: struck, if the band's still there and may, and on screen the fight begins; off screen it's settled at once. */
    private fun reachStrike(level: ServerLevel, data: CaravanData, entry: Markets.Entry, c: CaravanRec, at: Long, onScreen: Boolean): Strike? {
        c.waiting = -1L
        val band = OutlawCamps.get(level).band(c.band)
        if (!ambushes() || band == null || !allowed(level, data, band, at)) {
            c.struck = 1
            data.changed()
            return null
        }
        if (onScreen) {
            // The record waited at the strike point for its bodies: that wait is the wagon's too
            val reached = c.setOut + c.held + travel(c.strike)
            if (at > reached) c.held += at - reached
        }
        c.struck = 2
        c.struckAt = at
        val strike = dice(data, c, band)
        if (onScreen) {
            c.phase = Phase.STRUCK
            c.until = at + FIGHT_MOST
            data.changed()
            return strike
        }
        resolve(level, data, entry, c, at, strike.waylaid, 0, strike.horse == Horse.CUT_DOWN, fought = false, delay = FIGHT)
        return strike
    }

    /**
     * How [c]'s strike came out at [at]: [waylaid] (the band carries off half or more of the load, dearest first, and
     * the rest spills in a wreck by the road; the market loses all of it and leans those goods dear; the driver runs
     * for the gate) or fought off (a crate smashed at most, and on it goes, [delay] late). [kills] waylayers fell,
     * [horseDead] if it was cut down, [fought] if a player joined in. The road's peril and the band's strength follow.
     */
    private fun resolve(
        level: ServerLevel, data: CaravanData, entry: Markets.Entry, c: CaravanRec, at: Long, waylaid: Boolean, kills: Int, horseDead: Boolean,
        fought: Boolean, delay: Long,
    ) {
        val m = entry.market
        val camps = OutlawCamps.get(level)
        val band = camps.band(c.band)
        val rec = data.band(c.band)
        val road = data.road(c.capital, c.road)
        val hour = Math.floorDiv(at, HOUR)
        val u = rolls(c.seed)
        rec.wounds += kills
        rec.struck = at
        road.struck = at
        road.band = c.band
        c.kills += kills
        c.fought = c.fought || fought
        c.waylaid = waylaid
        c.horseDead = waylaid && horseDead
        c.waiting = -1L
        val strike: Strike
        if (waylaid) {
            val taken = taken(c.load, 0.5 + 0.2 * u[3])
            val spilled = c.load.copyOf()
            for ((g, n) in taken) spilled[g.ordinal] = (spilled[g.ordinal] - n).coerceAtLeast(0.0)
            // Everything on the wagon is lost to the market, so the freight doesn't fall for it
            for (g in Good.entries) if (c.load[g.ordinal] > 0.0) m.tally(Tally.LOST, g, c.load[g.ordinal])
            val lost = Good.entries.filter { c.load[it.ordinal] >= 1.0 }
            rec.wagons++
            taken.mapTo(rec.loot) { (g, n) -> Cargo(g, n, c.capital, c.id, at) }
            while (rec.loot.size > 48) rec.loot.removeAt(0)
            road.pending = true
            RoadPeril.change(data, c.capital, c.road, RoadPeril.WAYLAID, hour)
            waylay(m, lost, c, hour, RoadPeril.stage(road.peril))
            wreck(level, data, c, spilled, at)
            val gateAlong = CaravanRoutes.cached(c.capital, c.road)?.gate ?: c.length
            val run = ceil((gateAlong - c.strike).coerceAtLeast(0.0) / RUN).toLong()
            // Off screen the fight is still to be played out; on screen it's over and they run at once
            val from = at + (if (at == c.struckAt) delay.coerceAtLeast(0L) else 0L) + run
            val cargo = Good.entries.filter { c.load[it.ordinal] >= 1.0 }.map { Cargo(it, floor(c.load[it.ordinal]).toInt(), c.capital, c.id, at) }
            data.drivers.add(DriverRec(c.id, c.capital, c.bell, c.road, BlockPos.of(c.gate), from, nextEvening(from), cargo, c.band, HashSet()))
            c.phase = Phase.WAYLAID
            c.until = from
            if (band != null) {
                camps.named(level, band)
                camps.commit(level, band, Crime.CARAVAN)
            }
            if (c.job != null) CaravanEscorts.failed(level, data, c, "waylaid")
            strike = Strike(true, if (horseDead) Horse.CUT_DOWN else Horse.BOLTS, taken, band?.let { min(6, strength(it, rec) - 1) } ?: 1, c.band)
        } else {
            // A crate smashed in the fight, now and then: the dearest the wagon carries
            if (u[4] < 0.5) {
                Good.entries.filter { c.load[it.ordinal] >= 1.0 }.maxByOrNull { it.base }?.let { g ->
                    val n = min(c.load[g.ordinal], LOT.toDouble())
                    c.load[g.ordinal] -= n
                    m.tally(Tally.LOST, g, n)
                }
            }
            RoadPeril.change(data, c.capital, c.road, if (c.fought) RoadPeril.HELPED else RoadPeril.BEATEN_OFF, hour)
            c.phase = Phase.ROLLING
            c.held += delay.coerceAtLeast(0L)
            strike = Strike(false, Horse.BOLTS, listOf(), band?.let { min(6, strength(it, rec) - 1) } ?: 1, c.band)
        }
        data.changed()
        Markets.get(level).changed()
        val view = view(level, c, at)
        listeners.forEach { it.struck(level, view, strike) }
    }

    /**
     * The market feels a waylaid wagon: a WAYLAID event on the goods it lost ([lost]) and those of one waylaid before
     * it, leaning them dear by the road's [stage], until a wagon gets in by that road and a day after (two days at the
     * least); and its news, for the board and the crier.
     */
    private fun waylay(m: Market, lost: List<Good>, c: CaravanRec, hour: Long, stage: RoadPeril.Stage) {
        val old = m.waylaid()
        val goods = lost.toSet() + (old?.goods ?: setOf())
        m.events.removeIf { it.kind == EventKind.WAYLAID }
        m.events.add(Event(EventKind.WAYLAID, null, stage.factor, hour, hour + WAYLAID_MOST, goods, c.place))
        val dearest = lost.maxByOrNull { it.base * c.load[it.ordinal] }
        m.say("news.guildmark.market.event.${EventKind.WAYLAID.id}", dearest)
    }

    /** The waylaid wagons' trouble at [m] ends with the next hour (its good news cried), the road being safe again. */
    internal fun overWaylaid(m: Market) {
        val e = m.waylaid() ?: return
        m.events.remove(e)
        m.events.add(e.until(m.hour + 1))
    }

    /** The wreck of [c] at its strike point: the [spilled] goods in crates and sacks (none if salvage is off), unless it was struck out of sight on the plan's own road. */
    private fun wreck(level: ServerLevel, data: CaravanData, c: CaravanRec, spilled: DoubleArray, at: Long) {
        if (c.road < 0) return
        val lots = ArrayList<Cargo>()
        if (salvage()) {
            val goods = Good.entries.filter { it.item != null && spilled[it.ordinal] >= 1.0 }
            val units = goods.sumOf { floor(spilled[it.ordinal]).toInt() }
            val size = max(LOT, ceil(units / LOTS.toDouble()).toInt())
            for (g in goods.sortedByDescending { spilled[it.ordinal] }) {
                var n = floor(spilled[g.ordinal]).toInt()
                while (n > 0 && lots.size < LOTS) {
                    val k = min(n, size)
                    lots.add(Cargo(g, k, c.capital, c.id, at))
                    n -= k
                }
            }
        }
        data.wrecks.add(WreckRec(data.id(), c.id, c.capital, c.bell, c.road, c.wx, c.wy, c.wz, c.wyaw, lots, c.horseDead, at, at + WRECK, false, c.band))
    }

    // ---- Arrival ----

    /** [c] is in at [at]: its load into the market, its road's trouble on its way out, its escort seen in, and it stands an hour before leaving. */
    private fun arrive(level: ServerLevel, data: CaravanData, entry: Markets.Entry, c: CaravanRec, at: Long) {
        val m = entry.market
        deliver(m, c.load)
        if (c.slots.any { clockOf(it) == 9 }) {
            val out = DoubleArray(Good.entries.size)
            for ((g, n) in Economy.exports(m, entry.town)) out[g.ordinal] = n
            c.exports = out
        }
        c.phase = Phase.STANDING
        c.until = at + STAND
        c.waiting = -1L
        val road = data.road(c.capital, c.road)
        if (road.pending) {
            road.pending = false
            // A wagon in by the road that was robbed: the trouble lasts a day more (two at the least), once every such road has had one
            if (data.roads.none { it.capital == c.capital && it.pending }) {
                m.waylaid()?.let { e ->
                    m.events.remove(e)
                    m.events.add(e.until(min(e.until, max(e.since + WAYLAID_LEAST, Math.floorDiv(at, HOUR) + WAYLAID_AFTER))))
                }
            }
        }
        var escort: UUID? = null
        if (c.met && c.job != null) {
            val holder = c.holder?.let { level.server.playerList.getPlayer(it) }
            val leg = CaravanRoutes.cached(c.capital, c.road)
            val seen = holder != null && holder.level() === level && leg != null && flat(holder.position(), leg.stand) <= SEEN_IN
            CaravanEscorts.arrived(level, data, c, seen)
            if (seen) {
                RoadPeril.change(data, c.capital, c.road, RoadPeril.ESCORTED, Math.floorDiv(at, HOUR))
                escort = c.holder
            }
        }
        data.changed()
        Markets.get(level).changed()
        val v = view(level, c, at)
        val seenIn = View(v.id, v.capital, v.leg, v.now, v.state, v.load, v.exports, v.guards, v.wagons, escort, v.strike, v.seed, v.deadline)
        listeners.forEach { it.arrived(level, seenIn) }
    }

    /** [load] into [m], at once: the flour to the bakehouse, the rest to the market, all of it brought by the caravans. */
    private fun deliver(m: Market, load: DoubleArray) {
        for (g in Good.entries) {
            val n = load[g.ordinal]
            if (n <= 0.0) continue
            m.add(Economy.site(g), g, n)
            m.tally(Tally.IMPORTED, g, n)
        }
        load.fill(0.0)
    }

    /** [c] is done: its record goes. */
    private fun end(level: ServerLevel, data: CaravanData, c: CaravanRec) {
        if (!data.caravans.remove(c)) return
        data.changed()
        listeners.forEach { it.ended(level, c.id) }
    }

    /**
     * [c] is called off before it's in (time set back, the wagons switched off, its market gone): an escort's job on
     * it goes with it, so their contract isn't left waiting on a wagon that isn't coming, and its record goes.
     */
    private fun callOff(level: ServerLevel, data: CaravanData, c: CaravanRec) {
        if (c.job != null) CaravanEscorts.failed(level, data, c, "called")
        end(level, data, c)
    }

    /** The wagons are switched off: whatever of [entry]'s is still on the road comes in at once, and the records go. */
    private fun flush(level: ServerLevel, data: CaravanData, entry: Markets.Entry) {
        for (c in data.caravans.filter { it.capital == entry.market.capital }) {
            if (c.phase in setOf(Phase.WAITING, Phase.ROLLING, Phase.STRUCK)) deliver(entry.market, c.load)
            callOff(level, data, c)
        }
    }

    // ---- Wrecks, the band's wounds, a broken band's roads ----

    /** Wrecks at their end, or emptied, are cleared once nobody's within [CLEAR]: what's left goes back to the market. */
    private fun wrecks(level: ServerLevel, data: CaravanData, t: Long) {
        for (w in data.wrecks.toList()) {
            if (t < w.until && !w.emptied) continue
            val pos = Vec3(w.x, w.y, w.z)
            if (level.players().any { !it.isSpectator && flat(it.position(), pos) < CLEAR }) continue
            data.wrecks.remove(w)
            val markets = Markets.get(level)
            for (lot in w.lots) if (lot.count > 0) markets[BlockPos.of(lot.capital)]?.market?.add(Economy.site(lot.good), lot.good, lot.count.toDouble())
            markets.changed()
            data.changed()
            listeners.forEach { it.wreckCleared(level, w.id) }
        }
    }

    private var healed = Long.MIN_VALUE

    /** Once a day each band makes up one of the waylayers it lost. */
    private fun heal(level: ServerLevel, data: CaravanData) {
        val day = VillageBoards.day(level)
        if (day == healed) return
        healed = day
        for (b in data.bands) {
            if (b.wounds <= 0 || b.healed == day) continue
            b.wounds--
            b.healed = day
            data.changed()
        }
    }

    /** How many hands [band] musters when its camp is built: [usual], with those it hired on its takings and less those it lost on the road (counted in from then on). */
    fun hands(level: ServerLevel, band: OutlawBand, usual: Int): Int {
        val data = CaravanData.get(level)
        val rec = data.bandOrNull(band.id) ?: return usual
        val n = (usual + rec.wagons - rec.wounds).coerceIn(1, 6)
        rec.wagons = 0
        rec.wounds = 0
        data.changed()
        return n
    }

    /**
     * Whether a band works [road] (it's a capital's road, and a band is its band): the old pillager ambushes of
     * traders and travellers keep off it, the band's wagons being trouble enough (open question 4).
     */
    fun banded(level: ServerLevel, road: Int): Boolean {
        if (!ambushes() || road < 0) return false
        val data = CaravanData.get(level)
        if (data.roads.any { it.road == road && it.band >= 0 }) return true
        for (cap in data.capitals) {
            val leg = CaravanRoutes.legs(level, cap.capital)?.firstOrNull { it.road == road } ?: continue
            if (CaravanRoutes.band(level, leg) != null) return true
        }
        return false
    }

    /** Whether [band] works a capital's road (so the news has it doing to the wagons only what it really does). */
    fun works(level: ServerLevel, band: OutlawBand): Boolean = ambushes() && worked(level, CaravanData.get(level), band).isNotEmpty()

    /** The roads [band] works, by capital: those it last struck, and those it's the nearest band to (within reach of the camp). */
    internal fun worked(level: ServerLevel, data: CaravanData, band: OutlawBand): Map<Long, List<Int>> {
        val out = LinkedHashMap<Long, MutableList<Int>>()
        for (rec in data.roads) if (rec.band == band.id) out.getOrPut(rec.capital) { mutableListOf() }.add(rec.road)
        for (cap in data.capitals) {
            val legs = CaravanRoutes.legs(level, cap.capital) ?: continue
            for (leg in legs) {
                val reach = CaravanRoutes.reach(leg, band)
                if (reach > CaravanRoutes.BAND_REACH) continue
                val other = CaravanRoutes.band(level, leg)
                if (other != null && other.id != band.id && CaravanRoutes.reach(leg, other) < reach) continue
                val list = out.getOrPut(cap.capital) { mutableListOf() }
                if (leg.road !in list) list.add(leg.road)
            }
        }
        return out
    }

    /** A relief wagon for [entry]'s capital by [road] (or its first), setting out now with up to two days' imports. False if there's no way in to send it by. */
    internal fun relief(level: ServerLevel, data: CaravanData, entry: Markets.Entry, road: Int): Boolean {
        if (!enabled()) return false
        val legs = CaravanRoutes.legs(level, entry.market.capital) ?: return false
        val leg = legs.firstOrNull { it.road == road } ?: legs.firstOrNull() ?: return false
        val t = level.overworldClockTime
        val hour = Math.floorDiv(t + travel(leg.length) + HOUR - 1, HOUR)
        val c = make(level, data, entry, leg, hour, Run.RELIEF, t, RoadPeril.Stage.OPEN.guards, 1)
        Economy.bring(entry.market, entry.town, true, coming(data, c), RELIEF).copyInto(c.load)
        return true
    }

    /** [band]'s loot, what it carried off in the last [LOOT] days, in a pile by its camp's loot under a tarp, marked as off its wagons. */
    internal fun pile(level: ServerLevel, data: CaravanData, band: OutlawBand) {
        val rec = data.bandOrNull(band.id) ?: return
        val t = level.overworldClockTime
        val loot = rec.loot.filter { t - it.at <= LOOT && it.count > 0 }
        rec.loot.clear()
        if (!band.built || loot.isEmpty() || !salvage()) return
        val spot = OutlawCamp.layout(band.pos, band.facing).chores[1]
        val lots = loot.groupBy { Triple(it.good, it.capital, it.caravan) }.flatMap { (key, list) ->
            var n = list.sumOf { it.count }
            val out = ArrayList<Cargo>()
            while (n > 0) {
                val k = min(n, LOT * 2)
                out.add(Cargo(key.first, k, key.second, key.third, list.first().at))
                n -= k
            }
            out
        }.take(LOTS)
        val first = lots.first()
        val bell = data.caravans.firstOrNull { it.capital == first.capital }?.bell ?: first.capital
        data.wrecks.add(
            WreckRec(data.id(), first.caravan, first.capital, bell, -1, spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, band.facing.toYRot(), lots.toMutableList(), false, t, t + WRECK, true, band.id),
        )
        data.changed()
    }

    // ---- A capital with no road, and a quiet capital's band (open questions 1 and 3) ----

    /** With [CaravanRoutes.PAVE_ROADS], a capital with no road of its own asks for one to its nearest village, every few days until it has one. */
    private fun survey(level: ServerLevel, data: CaravanData, cap: CapitalRec, hour: Long) {
        if (!CaravanRoutes.PAVE_ROADS || CaravanRoutes.roads(level, cap.capital).isNotEmpty()) return
        val day = Math.floorDiv(hour, 24L)
        if (cap.surveyed >= 0 && day - cap.surveyed < SURVEY_DAYS) return
        cap.surveyed = day
        data.changed()
        val piece = CaravanRoutes.capital(level, cap.capital) ?: return
        CaravanRoutes.survey(level, piece.bell) { }
    }

    /** Days between asking for a road for a capital that has none. */
    private const val SURVEY_DAYS = 3L

    /**
     * Once, when [cap]'s market is [FOUND_AFTER] hours old and no band works any of its roads: a band settles by one,
     * in a cell that has none, so every capital sees trouble.
     */
    private fun found(level: ServerLevel, data: CaravanData, cap: CapitalRec, legs: List<CaravanRoutes.Leg>, hour: Long) {
        if (cap.founded || !ambushes() || hour - cap.born < FOUND_AFTER) return
        cap.founded = true
        data.changed()
        legs.forEach { CaravanRoutes.settle(level, it) }
        if (legs.any { CaravanRoutes.band(level, it) != null }) return
        val leg = legs.firstOrNull { it.road >= 0 } ?: legs.first()
        val random = java.util.Random(cap.capital xor 0x5EEDL)
        val camps = OutlawCamps.get(level)
        for (attempt in 0 until 4) {
            val along = if (leg.road < 0) 0.0 else leg.length * (0.4 + 0.1 * (attempt / 2))
            val p = leg.at(along)
            val (nx, nz) = normal(leg, along)
            val side = if ((attempt + random.nextInt(2)) % 2 == 0) 1.0 else -1.0
            val off = 128.0 + random.nextInt(65)
            if (camps.settleAt(level, Mth.floor(p.x + nx * off * side), Mth.floor(p.z + nz * off * side)) != null) return
        }
    }

    // ---- The lie of the land ----

    /** What the road is like [along] [leg]: at a ford (water by it), at a crossroads, in the woods, on a hill, or just on the road in. */
    private fun place(level: ServerLevel, leg: CaravanRoutes.Leg, along: Double): String {
        if (wet(level, leg, along)) return "ford"
        val at = leg.at(along)
        val x = Mth.floor(at.x)
        val z = Mth.floor(at.z)
        if (RoadNetwork.get(level).roads.any { r -> r.id != leg.road && r.points.any { kotlin.math.abs(it.x - x) <= CROSSING && kotlin.math.abs(it.z - z) <= CROSSING } }) return "crossroads"
        val biome = level.getBiome(BlockPos(x, Mth.floor(at.y) + 1, z))
        if (biome.`is`(BiomeTags.IS_FOREST) || biome.`is`(BiomeTags.IS_TAIGA) || biome.`is`(BiomeTags.IS_JUNGLE)) return "woods"
        val low = min(leg.at(max(0.0, along - HILL_SPAN)).y, leg.at(min(leg.length, along + HILL_SPAN)).y)
        if (at.y - low >= HILL_RISE) return "hill"
        return "road"
    }

    /** Whether there's water by the road near [along]: read from the ground where it's loaded, from the land's shape where it isn't. */
    private fun wet(level: ServerLevel, leg: CaravanRoutes.Leg, along: Double): Boolean {
        val generator = level.chunkSource.generator
        val random = level.chunkSource.randomState()
        var d = -WET_SPAN
        while (d <= WET_SPAN) {
            val a = (along + d).coerceIn(0.0, leg.length)
            val p = leg.at(a)
            val (nx, nz) = normal(leg, a)
            for (side in doubleArrayOf(-3.0, 0.0, 3.0)) {
                val x = Mth.floor(p.x + nx * side)
                val z = Mth.floor(p.z + nz * side)
                if (level.chunkSource.getChunkNow(x shr 4, z shr 4) != null) {
                    val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
                    if (!level.getFluidState(BlockPos(x, top - 1, z)).isEmpty) return true
                } else {
                    val surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random)
                    val floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random)
                    if (surface > floor) return true
                }
            }
            d += 4.0
        }
        return false
    }

    // ---- For the testing commands (CaravanCommand) ----

    /** How far ahead of a wagon a strike called up by hand falls: a few seconds' drive. */
    private const val AMBUSH_AHEAD = 12.0

    /** The hour a wagon setting out now by [leg] is due in, at clock time [t]. */
    private fun dueHour(leg: CaravanRoutes.Leg, t: Long): Long = Math.floorDiv(t + travel(leg.length) + HOUR - 1, HOUR)

    /** A wagon by [leg] into [entry]'s capital setting out now, loaded as the next hour's caravan would be and rolled against the road's band as any is. */
    internal fun spawn(level: ServerLevel, entry: Markets.Entry, leg: CaravanRoutes.Leg): CaravanRec {
        val data = CaravanData.get(level)
        val t = level.overworldClockTime
        val stage = RoadPeril.stage(data, entry.market.capital, leg.road)
        val c = make(level, data, entry, leg, dueHour(leg, t), Run.TEST, t, stage.guards, 1)
        Economy.bring(entry.market, entry.town, false, coming(data, c)).copyInto(c.load)
        depart(level, data, entry, c, t)
        data.changed()
        return c
    }

    /**
     * [c] is struck within seconds by [band], a few blocks ahead of where it is now (from the start of its leg if it's
     * still to go), [band]'s rest since its last strike let go; it comes out [waylaid] or beaten off as asked, or by the
     * dice if that's null. Null if it's set, else why it can't be.
     */
    internal fun ambush(level: ServerLevel, c: CaravanRec, band: OutlawBand, waylaid: Boolean?): String? {
        val data = CaravanData.get(level)
        val leg = CaravanRoutes.cached(c.capital, c.road) ?: return "its road isn't worked out yet"
        if (c.phase != Phase.PLANNED && c.phase != Phase.WAITING && c.phase != Phase.ROLLING) return "it isn't on its way (it's ${c.phase.id})"
        if (band.slain) return "that band is broken"
        val t = level.overworldClockTime
        if (band.raided >= 0 && Math.floorDiv(t, DAY) - band.raided in 0..1) return "that band raided a village today or yesterday, and lies low"
        val point = (distance(c, t) + AMBUSH_AHEAD).coerceAtMost(leg.length - ARRIVE_SLACK - 1.0).coerceAtLeast(1.0)
        data.band(band.id).struck = Long.MIN_VALUE / 2
        c.band = band.id
        c.reach = CaravanRoutes.reach(leg, band)
        c.struck = 0
        spot(level, c, leg, point)
        // In its road's tower's sight it's seen from there, as a strike the dice put there would be
        c.watched = io.github.vaspyyy.guildmark.economy.works.WorksWatch.struck(level, c, point, t)
        c.rigged = when (waylaid) {
            true -> 1
            false -> -1
            null -> 0
        }
        data.changed()
        return null
    }

    /** [c] is in at its stand now, wherever it was on its way (a strike still ahead of it is passed by). False if it isn't on its way. */
    internal fun arriveNow(level: ServerLevel, c: CaravanRec): Boolean {
        val data = CaravanData.get(level)
        val entry = Markets.get(level)[BlockPos.of(c.capital)] ?: return false
        val t = level.overworldClockTime
        if (c.phase == Phase.PLANNED) depart(level, data, entry, c, t)
        if (c.phase == Phase.WAITING) go(level, data, c, t, alone = !c.met)
        if (c.phase != Phase.ROLLING) return false
        if (c.struck == 0) c.struck = 1
        arrive(level, data, entry, c, t)
        return true
    }

    /**
     * A wagon by [leg] into [entry]'s capital, loaded as the next hour's, waylaid by [band] at [pos] now: the band
     * carries off its share, the rest lies in a wreck at [pos] turned [yaw] (on a paved road, with salvage on), its
     * driver runs for the gate, and the market, the road and the band feel it as they would any.
     */
    internal fun wreckAt(level: ServerLevel, entry: Markets.Entry, leg: CaravanRoutes.Leg, band: OutlawBand, pos: Vec3, yaw: Float): CaravanRec {
        val data = CaravanData.get(level)
        val t = level.overworldClockTime
        val stage = RoadPeril.stage(data, entry.market.capital, leg.road)
        val c = make(level, data, entry, leg, dueHour(leg, t), Run.TEST, t, stage.guards, 1)
        Economy.bring(entry.market, entry.town, false, coming(data, c)).copyInto(c.load)
        val point = leg.nearest(pos.x, pos.z)
        c.phase = Phase.ROLLING
        c.setOut = t - travel(point)
        c.band = band.id
        c.reach = CaravanRoutes.reach(leg, band)
        spot(level, c, leg, point)
        c.wx = pos.x
        c.wy = pos.y
        c.wz = pos.z
        c.wyaw = yaw
        c.struck = 2
        c.struckAt = t
        resolve(level, data, entry, c, t, true, 0, rolls(c.seed)[2] < 0.4, fought = false, delay = 0L)
        return c
    }

    /** The last waylaid wagon's driver comes to [pos] at once, and sits there until the next evening. Null if there's none. */
    internal fun driverTo(level: ServerLevel, pos: BlockPos): DriverRec? {
        val data = CaravanData.get(level)
        val d = data.drivers.maxByOrNull { it.from } ?: return null
        val t = level.overworldClockTime
        val moved = DriverRec(d.caravan, d.capital, d.bell, d.road, pos, t, max(d.until, nextEvening(t)), d.lost, d.band, d.told)
        data.drivers.remove(d)
        data.drivers.add(moved)
        data.changed()
        return moved
    }

    /** Clock ticks to go [blocks] at [PACE] (for the commands' times). */
    internal fun ticksFor(blocks: Double): Long = travel(blocks)

    // ---- Small helpers ----

    /** The overworld of the server running now, if one is. */
    internal fun overworld(): ServerLevel? = ServerLifecycleHooks.getCurrentServer()?.overworld()

    /** [c] as its bodies see it at clock time [t]. */
    private fun view(level: ServerLevel, c: CaravanRec, t: Long): View {
        val leg = CaravanRoutes.cached(c.capital, c.road)
        return View(
            c.id, BlockPos.of(c.bell), leg?.points ?: listOf(), distance(c, t), state(c.phase), goods(c.load), goods(c.exports), c.guards, c.wagons,
            c.holder.takeIf { c.met }, c.strike.takeIf { it >= 0.0 && c.struck != 1 }, c.seed, gameAt(level, deadline(c)),
        )
    }

    private fun state(phase: Phase): State = when (phase) {
        Phase.PLANNED, Phase.WAITING -> State.WAITING
        Phase.ROLLING -> State.ROLLING
        Phase.STRUCK -> State.STRUCK
        Phase.WAYLAID -> State.WAYLAID
        Phase.STANDING -> State.STANDING
        Phase.LEAVING -> State.LEAVING
    }

    /** How far along its leg the record has [c] at clock time [t] (for a waylaid one, its driver running for the gate). */
    fun distance(c: CaravanRec, t: Long): Double = when (c.phase) {
        Phase.PLANNED, Phase.WAITING -> 0.0
        Phase.ROLLING -> ((t - c.setOut - c.held) * PACE).coerceIn(0.0, if (c.struck == 0 && c.strike >= 0) c.strike else c.length)
        Phase.STRUCK -> c.strike.coerceAtLeast(0.0)
        Phase.WAYLAID -> {
            val gate = CaravanRoutes.cached(c.capital, c.road)?.gate ?: c.length
            (gate - (c.until - t) * RUN).coerceIn(c.strike.coerceAtLeast(0.0), max(gate, c.strike))
        }
        Phase.STANDING -> c.length
        Phase.LEAVING -> ((c.until - t) * PACE).coerceIn(0.0, c.length)
    }

    /** A load as its whole units, the biggest heaps first. */
    private fun goods(a: DoubleArray): List<Pair<Good, Int>> = Good.entries
        .mapNotNull { g -> floor(a[g.ordinal] + 1e-6).toInt().takeIf { it > 0 }?.let { g to it } }
        .sortedByDescending { it.second }

    /** Ticks to go [blocks] at [PACE]. */
    private fun travel(blocks: Double): Long = ceil(blocks / PACE).toLong()

    /** The clock's hour (0 at midnight) of market hour [hour]. */
    fun clockOf(hour: Long): Int = Math.floorMod(hour + 6, 24L).toInt()

    /** The game time clock time [clock] falls at, as things stand. */
    fun gameAt(level: ServerLevel, clock: Long): Long = level.gameTime + (clock - level.overworldClockTime)

    /** 18:00 of the day after clock time [t]'s. */
    private fun nextEvening(t: Long): Long = Math.floorDiv(t, DAY) * DAY + DAY + EVENING

    /** The first 18:00 at or after clock time [t]: that day's evening, or the next day's once it's past. */
    fun evening(t: Long): Long {
        val same = Math.floorDiv(t, DAY) * DAY + EVENING
        return if (same >= t) same else same + DAY
    }

    /** Across [leg] [along] it, to its left: a unit (x, z). */
    private fun normal(leg: CaravanRoutes.Leg, along: Double): Pair<Double, Double> {
        val a = leg.at((along - 1.0).coerceAtLeast(0.0))
        val b = leg.at((along + 1.0).coerceAtMost(leg.length))
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = sqrt(dx * dx + dz * dz)
        return if (len < 1e-6) 1.0 to 0.0 else (-dz / len) to (dx / len)
    }

    private fun flat(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun flat(a: BlockPos, b: BlockPos): Double = flat(Vec3.atCenterOf(a), Vec3.atCenterOf(b))
}
