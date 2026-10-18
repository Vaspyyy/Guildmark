package io.github.vaspyyy.guildmark.economy.works

import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.UUID

/**
 * What players' Marks can have built in a capital (living-world/design-influence.md section 1): a second oven, a
 * granary and then a great one, a watchtower on a road in, and a stall of one's own. [per]: one for the town, one for
 * each road in, or one for each player; [after]: the works it follows on from.
 */
enum class WorksKind(val id: String, val per: Per, val grade: Int, val after: WorksKind?) {
    OVEN("oven", Per.TOWN, 1, null),
    GRANARY("granary", Per.TOWN, 1, null),
    GREAT_GRANARY("great_granary", Per.TOWN, 2, GRANARY),
    TOWER("tower", Per.ROAD, 1, null),
    STALL("stall", Per.PLAYER, 1, null);

    enum class Per { TOWN, ROAD, PLAYER }
}

/** Where a works is: wanted (taking pledges), funded, being built, or done. */
enum class WorksState { WANTED, FUNDED, BUILDING, DONE }

/** Marks [player] (known as [name]) put toward a works on market day [day]. */
class Pledge(val player: UUID, val name: String, val marks: Int, val day: Long)

/** Where a works goes: its drawing's origin and facing, its lot, its crate, its post; [planned] for a version-2 lot. */
class WorksSite(val origin: BlockPos, val facing: Direction, val lot: BoundingBox, val crate: BlockPos, val post: BlockPos, val planned: Boolean)

/**
 * A works as the building, the screen, the fortunes and the talk see it. [road] is -1 but for a tower, [owner] null
 * but for a stall; [parts] the price's parts (wages, materials, filling, pitch); [bill], [here] and [coming] are by
 * [Stuff.PAID]; [why] the lang key of what made it wanted; [wanted], [begun] and [done] market days, -1 until then.
 */
class WorksView(
    val id: Int, val capital: Long, val kind: WorksKind, val road: Int, val owner: UUID?,
    val state: WorksState, val site: WorksSite?, val price: Int, val parts: Map<String, Int>,
    val pledges: List<Pledge>, val patron: UUID?,
    val bill: IntArray, val here: IntArray, val coming: IntArray,
    val wanted: Long, val why: String, val begun: Long, val done: Long,
    val stage: Stage?, val laid: Int, val total: Int,
)

/** A works not wanted yet, shown greyed, and what would make it wanted ([why], a lang key, with [args]). */
class NotYet(val kind: WorksKind, val road: Int, val why: String, val args: List<String>)

/** A tower's watch over its road: how far along the road it stands, how far it sees, and where. */
class Watch(val along: Double, val sight: Double, val pos: BlockPos)

/** Why something a player asked of a works can't be done: a lang key and its arguments. */
class Refusal(val key: String, val args: List<Any> = listOf())

/**
 * A capital's works (living-world/design-influence.md sections 1 to 3): what's wanted, the pledges toward it, the
 * building, and what each does once it stands. The API between M6's parts (section 10). Until Part A fills it in,
 * nothing is wanted or built and every works does nothing.
 */
object Works {
    /** Whether works are wanted, paid for and built (townWorks, with the markets). */
    fun enabled(): Boolean = false

    /** Every works in the capital whose heart is [capital], in any state. */
    fun all(level: ServerLevel, capital: Long): List<WorksView> = listOf()

    /** The works [capital] doesn't want yet, for [player] (a stall is theirs alone), and what would make it. */
    fun notYet(level: ServerLevel, capital: Long, player: UUID?): List<NotYet> = listOf()

    fun get(level: ServerLevel, id: Int): WorksView? = null

    /** The works whose lot, yard or post holds [pos]. */
    fun at(level: ServerLevel, pos: BlockPos): WorksView? = null

    /** [player] pledges [marks] toward works [id]: null if taken, else why not. */
    fun pledge(level: ServerLevel, player: ServerPlayer, id: Int, marks: Int): Refusal? = Refusal("gui.guildmark.works.off")

    /** [player] takes back what they pledged toward works [id] while it's still unfunded: the Marks given back. */
    fun withdraw(level: ServerLevel, player: ServerPlayer, id: Int): Int = 0

    /** [player] brings [stack] toward works [id]'s bill: the units taken. */
    fun bring(level: ServerLevel, player: ServerPlayer, id: Int, stack: ItemStack): Int = 0

    /** Pays [player] back what called-off works owe them. */
    fun owed(level: ServerLevel, player: ServerPlayer) {}

    // ---- What the building (Part B) reports ----

    /** Works [id] has its site worked out, or none can be found ([site] null). */
    fun sited(level: ServerLevel, id: Int, site: WorksSite?) {}

    /** The builders take [n] of [stuff] out of works [id]'s yard: how many there were to take. */
    fun take(level: ServerLevel, id: Int, stuff: Stuff, n: Int): Int = 0

    /** Works [id] is at [stage], [laid] of its [total] pieces in place. */
    fun staged(level: ServerLevel, id: Int, stage: Stage, laid: Int, total: Int) {}

    /** Works [id] stands finished. */
    fun finished(level: ServerLevel, id: Int) {}

    // ---- What the works do ----

    /** The ovens [capital]'s works have added (the plan's own are the Town's). */
    fun ovens(level: ServerLevel, capital: Long): Int = 0

    /** The granary's room by good, or null with none. */
    fun reserve(level: ServerLevel, capital: Long): DoubleArray? = null

    /** The watch kept over [road] into [capital] by its tower, or null with none. */
    fun watch(level: ServerLevel, capital: Long, road: Int): Watch? = null

    /** Where [player]'s own stall stands in [capital]'s square, if they have one built. */
    fun stall(level: ServerLevel, capital: Long, player: UUID): WorksSite? = null

    // ---- The labourers: the brainstorm thread asks, the building answers ----

    /** The residents of [capital] wanted at the works on market day [day]. */
    fun labourers(level: ServerLevel, capital: Long, day: Long): List<Int> = listOf()

    /** Where [capital]'s labourers report for work. */
    fun workAt(level: ServerLevel, capital: Long): BlockPos? = null

    /** Gives a labourer their work at the site (Part B sets it). */
    var crew: Crew? = null

    interface Crew {
        fun labour(level: ServerLevel, p: Townsperson): Director?
    }

    private val listeners = ArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    /** Hears a works' life: wanted, pledged to, funded, begun, at each stage, finished, and what it eased. */
    interface Listener {
        fun wanted(level: ServerLevel, w: WorksView) {}
        fun pledged(level: ServerLevel, w: WorksView, player: UUID, marks: Int) {}
        fun funded(level: ServerLevel, w: WorksView) {}
        fun begun(level: ServerLevel, w: WorksView) {}
        fun staged(level: ServerLevel, w: WorksView, stage: Stage) {}
        fun finished(level: ServerLevel, w: WorksView) {}

        /** What [w] eased: "mill", "bread" or "band". */
        fun eased(level: ServerLevel, w: WorksView, what: String) {}
    }
}
