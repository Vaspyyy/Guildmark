package io.github.vaspyyy.guildmark.economy

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A capital's imports on wagons that come in along its roads, and what befalls them on the way
 * (living-world/design-road.md). The saved records, the timetable, the loads and the rolls are kept here; the wagons,
 * horses, crews, wrecks and drivers seen on the road (caravan/) read them and report back only through what follows,
 * so the same caravan comes out the same whether anyone watches it or not.
 *
 * As it stands this is the interface only, fixed so that the records and the bodies can be built at once: no caravan
 * sets out yet, and every call does nothing.
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

    /** Hears what happens to caravans whether or not their bodies are about (to make, move or drop them). */
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

    /** Every caravan whose leg comes within [range] blocks of [pos]. */
    fun near(level: ServerLevel, pos: BlockPos, range: Double): List<View> = listOf()

    /** The bodies of caravan [id] were held up for [ticks] (something in the road, or started further back): the record waits too. */
    fun waited(id: Long, ticks: Int) {}

    /** Caravan [id]'s bodies have reached its strike point: how it comes out, unless a player joins the fight (see [fought]). */
    fun struck(id: Long): Strike? = null

    /** A fight at caravan [id] that a player joined has ended: [waylaid] or fought off, [kills] waylayers dead, the horse with it or not. */
    fun fought(id: Long, waylaid: Boolean, kills: Int, horseDead: Boolean) {}

    /** Caravan [id]'s wagon has pulled up at its stand: its load goes into the market. */
    fun reached(id: Long) {}

    /** Every wreck within [range] blocks of [pos]. */
    fun wrecks(level: ServerLevel, pos: BlockPos, range: Double): List<Wreck> = listOf()

    /** [player] takes lot [lot] of wreck [wreck]: the goods, marked as off that wagon, or nothing if it's gone. */
    fun take(player: ServerPlayer, wreck: Long, lot: Int): ItemStack = ItemStack.EMPTY

    /** Every waylaid wagon's driver at a gate within [range] blocks of [pos]. */
    fun drivers(level: ServerLevel, pos: BlockPos, range: Double): List<Driver> = listOf()

    /** [player] has heard driver [caravan]'s story: the camp goes on their map. True the first time. */
    fun told(player: ServerPlayer, caravan: Long): Boolean = false
}
