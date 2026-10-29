package io.github.vaspyyy.guildmark.economy.works

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.cooking.shop.CookShops
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.Route
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.homestead.Materials
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.Houses
import io.github.vaspyyy.guildmark.trade.Stores
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import java.util.UUID

/**
 * What players' Marks can have built in a capital (living-world/design-influence.md section 1): a second oven, a
 * granary and then a great one, a watchtower on a road in, a stall of one's own, a cookshop, and a trader's Carriers' Store and then
 * Counting House. [per]: one for the town, one for each road in, or one for each player; [after]: the works it follows on from (for a
 * works of each player, the player's own: a House follows on from one's own Store).
 */
enum class WorksKind(val id: String, val per: Per, val grade: Int, val after: WorksKind?) {
    OVEN("oven", Per.TOWN, 1, null),
    GRANARY("granary", Per.TOWN, 1, null),
    GREAT_GRANARY("great_granary", Per.TOWN, 2, GRANARY),
    TOWER("tower", Per.ROAD, 1, null),
    STALL("stall", Per.PLAYER, 1, null),
    COOKSHOP("cookshop", Per.PLAYER, 1, null),
    STORE("store", Per.PLAYER, 1, null),
    COUNTING_HOUSE("counting_house", Per.PLAYER, 2, STORE);

    enum class Per { TOWN, ROAD, PLAYER }

    /** The lang key of its name ("A second oven"). */
    val key: String get() = "gui.guildmark.works.kind.$id"

    companion object {
        fun byId(id: String): WorksKind? = entries.firstOrNull { it.id == id }
    }
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
) {
    /** All the Marks pledged toward it. */
    val pledged: Int get() = pledges.sumOf { it.marks }
}

/** A works not wanted yet, shown greyed, and what would make it wanted ([why], a lang key, with [args]). */
class NotYet(val kind: WorksKind, val road: Int, val why: String, val args: List<String>)

/** A tower's watch over its road: how far along the road it stands, how far it sees, and where. */
class Watch(val along: Double, val sight: Double, val pos: BlockPos)

/** Why something a player asked of a works can't be done: a lang key and its arguments. */
class Refusal(val key: String, val args: List<Any> = listOf())

/**
 * A capital's works (living-world/design-influence.md sections 1 to 3): what's wanted, the pledges toward it, the
 * building, and what each does once it stands. The API between M6's parts (section 10).
 *
 * A works is a record first ([WorksData], `town_works`). At each market close the town asks for what the day showed
 * a need of ([WorksWants]); anyone pledges Marks toward it, and once the pledges reach its price it's funded and its
 * bill goes out on the wagons ([WorksFreight]); players may bring materials to the yard's crate; the building (Part
 * B) reports its site, takes materials out of the yard, and reports each stage and the finish; and from the finish
 * the works does its work: the ovens bake by how many there are ([Ovens]), the granary gives and fills ([Reserve]),
 * the tower keeps its watch ([WorksWatch]) and a stall of one's own sells ([OwnStalls]). [Listener] hears each step.
 *
 * Until [READY] is set (M6 is wired in), and with townWorks off, nothing is wanted or built and every works does
 * nothing: the game is as it was. Switched off, the records are kept for when it's on again.
 */
object Works {
    /** Part A is wired in, with all of M6 switched on. False would turn it off: nothing here runs or touches a world. */
    private const val READY = true

    /** Whether works are wanted, paid for and built (townWorks, with the markets). */
    fun enabled(): Boolean = READY && Markets.enabled() && setting { GuildmarkConfig.TOWN_WORKS.get() }

    /** Whether the works' records are there to read and keep (Part A wired in), whatever the switch. */
    internal fun ready(): Boolean = READY

    private inline fun setting(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }

    /** `/guildmark works rush`: the builders at ten times the pace and held-up freight round the long way ten times as soon (not kept). */
    internal var rush = false

    /** What a works eases, for [Listener.eased]: the mill kept turning, bread the old ovens couldn't have baked, a band kept off. */
    const val MILL = "mill"
    const val BREAD = "bread"
    const val BAND = "band"

    /** Market days a paid-for works may stand with no site before it's called off and its pledges paid back. */
    const val SITE_LOST_DAYS = 3

    /** How far round its lot a works' yard reaches, for [at]. */
    const val YARD = 4

    private fun data(level: ServerLevel): WorksData = WorksData.get(level)

    /** Every works in the capital whose heart is [capital], in any state. */
    fun all(level: ServerLevel, capital: Long): List<WorksView> {
        if (!READY) return listOf()
        val data = data(level)
        return data.of(capital).map { view(level, it) }
    }

    /** The works [capital] offers [player]: every works but other players' stalls and cookshops not yet built (only its owner sees one offered). */
    fun offered(level: ServerLevel, capital: Long, player: UUID?): List<WorksView> =
        all(level, capital).filter { it.kind.per != WorksKind.Per.PLAYER || it.state == WorksState.DONE || it.owner == player }

    /** The works [capital] doesn't want yet, for [player] (a stall is theirs alone), and what would make it. */
    fun notYet(level: ServerLevel, capital: Long, player: UUID?): List<NotYet> {
        if (!enabled()) return listOf()
        val entry = Markets.get(level)[BlockPos.of(capital)] ?: return listOf()
        val data = data(level)
        return WorksWants.notYet(level, entry, data, data.town(capital), player)
    }

    fun get(level: ServerLevel, id: Int): WorksView? {
        if (!READY) return null
        return data(level).works[id]?.let { view(level, it) }
    }

    /** The works whose lot, yard or post holds [pos]. */
    fun at(level: ServerLevel, pos: BlockPos): WorksView? {
        if (!READY) return null
        val w = data(level).works.values.firstOrNull { w -> w.site?.let { holds(it, pos) } == true } ?: return null
        return view(level, w)
    }

    private fun holds(site: WorksSite, pos: BlockPos): Boolean =
        pos == site.post || pos == site.crate || site.lot.inflatedBy(YARD, 1, YARD).isInside(pos)

    // ---- Players ----

    /** [player] pledges [marks] toward works [id]: null if taken, else why not. */
    fun pledge(level: ServerLevel, player: ServerPlayer, id: Int, marks: Int): Refusal? {
        if (!enabled()) return Refusal("gui.guildmark.works.off")
        val data = data(level)
        val w = data.works[id] ?: return Refusal("gui.guildmark.works.why.gone")
        if (w.paid) return Refusal("gui.guildmark.works.why.paid")
        if (w.kind.per == WorksKind.Per.PLAYER && w.owner != player.uuid) return Refusal("gui.guildmark.works.why.owner")
        if (marks <= 0) return Refusal("gui.guildmark.works.why.none")
        // "The rest" and anything over it come to what it still wants
        val n = minOf(marks, w.price - w.pledged)
        if (n <= 0) return Refusal("gui.guildmark.works.why.paid")
        if (TownControl.marks(player) < n) return Refusal("gui.guildmark.works.why.purse", listOf(n))
        TownControl.pay(player, n)
        w.pledges.add(PledgeRec(player.uuid, player.name.string, n, today(level, w.capital), false))
        data.changed()
        pledged(level, data, w, player.uuid, n)
        return null
    }

    /** [player] takes back what they pledged toward works [id] while it's still unfunded: the Marks given back. */
    fun withdraw(level: ServerLevel, player: ServerPlayer, id: Int): Int {
        if (!READY) return 0
        val data = data(level)
        val w = data.works[id] ?: return 0
        if (w.paid) return 0
        val theirs = w.pledges.filter { it.player == player.uuid }
        if (theirs.isEmpty()) return 0
        w.pledges.removeAll(theirs)
        data.changed()
        val marks = theirs.filter { !it.free }.sumOf { it.marks }
        if (marks > 0) Trading.give(player, ModItems.GUILD_MARK.get(), marks)
        return marks
    }

    /**
     * [player] brings [stack] toward works [id]'s bill: the units taken. Only a works being paid for and built takes
     * materials, and only as many whole items as its bill still wants (counted the homestead's way: a log is four
     * timber); [stack] is shrunk by what's taken and the rest stays in the hand. Nothing is paid for it.
     */
    fun bring(level: ServerLevel, player: ServerPlayer, id: Int, stack: ItemStack): Int {
        if (!enabled()) return 0
        val data = data(level)
        val w = data.works[id]?.takeIf { it.open } ?: return 0
        val (stuff, per) = Materials.of(stack) ?: return 0
        val i = Stuff.PAID.indexOf(stuff)
        if (i < 0 || per <= 0) return 0
        val wants = w.bill[i] - w.here[i] - w.used[i]
        val items = minOf(stack.count, wants / per)
        if (items <= 0) return 0
        stack.shrink(items)
        val units = items * per
        w.here[i] += units
        w.brought[i] += units
        data.changed()
        val v = view(level, w)
        tell(v, "brought materials") { it.brought(level, v, player.uuid, units) }
        return units
    }

    /** Pays [player] back what called-off works owe them. */
    fun owed(level: ServerLevel, player: ServerPlayer) {
        if (!READY) return
        val data = data(level)
        val owed = data.owed.remove(player.uuid) ?: return
        data.changed()
        if (owed.marks <= 0) return
        Trading.give(player, ModItems.GUILD_MARK.get(), owed.marks)
        player.sendSystemMessage(Component.translatable("message.guildmark.works.owed", owed.marks).withStyle(ChatFormatting.GOLD))
    }

    // ---- What the building (Part B) reports ----

    /** Works [id] has its site worked out, or none can be found ([site] null). */
    fun sited(level: ServerLevel, id: Int, site: WorksSite?) {
        if (!enabled()) return
        val data = data(level)
        val w = data.works[id] ?: return
        w.site = site
        if (site != null) {
            w.noRoom = -1L
            w.siteLost = -1L
        } else {
            val day = today(level, w.capital)
            w.noRoom = day
            if (w.paid && w.state != WorksState.DONE && w.siteLost < 0) w.siteLost = day
        }
        data.changed()
    }

    /** The builders take [n] of [stuff] out of works [id]'s yard: how many there were to take. */
    fun take(level: ServerLevel, id: Int, stuff: Stuff, n: Int): Int {
        if (!enabled() || n <= 0) return 0
        val data = data(level)
        val w = data.works[id] ?: return 0
        val i = Stuff.PAID.indexOf(stuff)
        if (i < 0) return 0
        val k = minOf(n, w.here[i])
        if (k <= 0) return 0
        w.here[i] -= k
        w.used[i] += k
        data.changed()
        return k
    }

    /** Works [id] is at [stage], [laid] of its [total] pieces in place. */
    fun staged(level: ServerLevel, id: Int, stage: Stage, laid: Int, total: Int) {
        if (!enabled()) return
        val data = data(level)
        val w = data.works[id] ?: return
        if (!w.open) return
        w.laid = laid
        w.total = total
        begin(level, data, w)
        val moved = w.stage != stage
        w.stage = stage
        data.changed()
        if (moved) {
            val v = view(level, w)
            tell(v, "staged") { it.staged(level, v, stage) }
        }
    }

    /** Works [id] stands finished. */
    fun finished(level: ServerLevel, id: Int) {
        if (!enabled()) return
        val data = data(level)
        val w = data.works[id] ?: return
        if (!w.open) return
        begin(level, data, w)
        w.state = WorksState.DONE
        w.done = today(level, w.capital)
        w.siteLost = -1L
        data.changed()
        open(level, data, w)
        val v = view(level, w)
        tell(v, "finished") { it.finished(level, v) }
    }

    // ---- What the works do ----

    /** The ovens [capital]'s works have added (the plan's own are the Town's). */
    fun ovens(level: ServerLevel, capital: Long): Int {
        if (!READY) return 0
        return if (data(level).done(capital, WorksKind.OVEN) != null) 1 else 0
    }

    /** The granary's room by good, or null with none. */
    fun reserve(level: ServerLevel, capital: Long): DoubleArray? {
        if (!READY) return null
        val grade = grade(data(level), capital)
        if (grade <= 0) return null
        val entry = Markets.get(level)[BlockPos.of(capital)] ?: return null
        return WorksKinds.room(grade, entry.town)
    }

    /** The grade of the town granary [capital]'s works have built: 0 none, 1 the granary, 2 the great granary. */
    fun grade(level: ServerLevel, capital: Long): Int = if (READY) grade(data(level), capital) else 0

    private fun grade(data: WorksData, capital: Long): Int =
        data.of(capital).filter { it.state == WorksState.DONE && (it.kind == WorksKind.GRANARY || it.kind == WorksKind.GREAT_GRANARY) }.maxOfOrNull { it.kind.grade } ?: 0

    /** The watch kept over [road] into [capital] by its tower, or null with none. */
    fun watch(level: ServerLevel, capital: Long, road: Int): Watch? {
        val w = standing(level, capital, WorksKind.TOWER, road) ?: return null
        val leg = CaravanRoutes.cached(capital, road) ?: CaravanRoutes.legs(level, capital)?.firstOrNull { it.road == road } ?: return null
        return WorksWatch.place(w, leg)
    }

    /** The watchmen at [road]'s tower into [capital] at [clock] (the thread's guards on watch): none without a tower; two by day and one by night, or one by day and none by night with fewer than four guards in town. */
    fun watchmen(level: ServerLevel, capital: Long, road: Int, clock: Int): Int {
        if (standing(level, capital, WorksKind.TOWER, road) == null) return 0
        return WorksWatch.watchmen(level, capital, clock)
    }

    /** Where [player]'s own stall stands in [capital]'s square, if they have one built. */
    fun stall(level: ServerLevel, capital: Long, player: UUID): WorksSite? {
        if (!READY) return null
        return data(level).find(capital, WorksKind.STALL, owner = player)?.takeIf { it.state == WorksState.DONE }?.site
    }

    /** Where [player]'s own cookshop stands in [capital]'s square, if they have one built (the shop code asks, living-world/cook/design.md 6.4). */
    fun cookshop(level: ServerLevel, capital: Long, player: UUID): WorksSite? {
        if (!READY) return null
        return data(level).find(capital, WorksKind.COOKSHOP, owner = player)?.takeIf { it.state == WorksState.DONE }?.site
    }

    /** Where [player]'s own Carriers' Store stands in [capital], if they have one built (the Store's yard, for the trader's carts and wagons). */
    fun store(level: ServerLevel, capital: Long, player: UUID): WorksSite? {
        if (!READY) return null
        return data(level).find(capital, WorksKind.STORE, owner = player)?.takeIf { it.state == WorksState.DONE }?.site
    }

    /** Where [player]'s own Counting House stands in [capital], if they have one built. */
    fun house(level: ServerLevel, capital: Long, player: UUID): WorksSite? {
        if (!READY) return null
        return data(level).find(capital, WorksKind.COUNTING_HOUSE, owner = player)?.takeIf { it.state == WorksState.DONE }?.site
    }

    /**
     * Where the carters fetch from [capital]'s works of [kind] (the granary's grain): its first door as it was built
     * (the granary's), or its ovens' mouths (the oven's); else the middle of its lot's front, on the side it faces, at
     * its foot; null while it has no site or doesn't stand.
     */
    fun door(level: ServerLevel, capital: Long, kind: WorksKind): BlockPos? {
        if (!READY) return null
        val w = data(level).done(capital, kind) ?: return null
        val site = w.site ?: return null
        io.github.vaspyyy.guildmark.economy.works.build.WorksAtWork.door(level, w.id)?.let { return it }
        val lot = site.lot
        val x = (lot.minX() + lot.maxX()) / 2
        val z = (lot.minZ() + lot.maxZ()) / 2
        return when (site.facing) {
            Direction.NORTH -> BlockPos(x, lot.minY(), lot.minZ())
            Direction.SOUTH -> BlockPos(x, lot.minY(), lot.maxZ())
            Direction.WEST -> BlockPos(lot.minX(), lot.minY(), z)
            else -> BlockPos(lot.maxX(), lot.minY(), z)
        }
    }

    /** Works [id]'s materials on each wagon on its way in, and when each is due (for its screen: "on which wagon"). */
    fun shipments(level: ServerLevel, id: Int): List<Shipment> {
        if (!READY) return listOf()
        val w = data(level).works[id] ?: return listOf()
        return WorksFreight.shipments(level, w.capital, id)
    }

    /** The pace of the builders' work clock (Part B winds it by this): townWorksBuildSpeed, ten times as fast with `works rush`. */
    fun pace(): Double {
        val speed = try {
            GuildmarkConfig.TOWN_WORKS_BUILD_SPEED.get()
        } catch (e: IllegalStateException) {
            1.0
        }
        return speed * (if (rush) 10.0 else 1.0)
    }

    /** A why or a not-yet as words: [key] with [args], an argument starting with `#` being a lang key itself (the road's name). */
    fun text(key: String, args: List<String> = listOf()): Component =
        Component.translatable(key, *args.map<String, Any> { if (it.startsWith("#")) Component.translatable(it.drop(1)) else it }.toTypedArray())

    // ---- The labourers: the brainstorm thread asks, the building answers ----

    /** The residents of [capital] wanted at the works on market day [day] (Part B: a few of its spare citizens a day). */
    fun labourers(level: ServerLevel, capital: Long, day: Long): List<Int> =
        io.github.vaspyyy.guildmark.economy.works.build.WorksBuild.labourers(level, capital, day)

    /** Where [capital]'s labourers report for work (Part B: before the crate of the works going up). */
    fun workAt(level: ServerLevel, capital: Long): BlockPos? =
        io.github.vaspyyy.guildmark.economy.works.build.WorksBuild.workAt(level, capital)

    /** Gives a labourer their work at the site (Part B sets it). */
    var crew: Crew? = null

    interface Crew {
        fun labour(level: ServerLevel, p: Townsperson): Director?

        /**
         * [p] is let go from the director [labour] gave them: the stretch is over (noon, sunset), they were hurt or
         * fought, or the director set [Townsperson.director] to null itself. Whatever they held of the site's (a
         * claim, a block in hand) is to be given back here. Their body is the thread's again from the next tick.
         */
        fun release(level: ServerLevel, p: Townsperson) {}
    }

    private val listeners = ArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    /**
     * Every listener hears [what] of [w] ([hear]): one that fails is logged and the rest still hear it, so it never
     * stops the server's tick or the pledge that set it off (as Comings.tell).
     */
    private inline fun tell(w: WorksView, what: String, hear: (Listener) -> Unit) {
        for (l in listeners) {
            try {
                hear(l)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Something hearing that works #{} was {} failed", w.id, what, e)
            }
        }
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

        /** [w] is gone from the records: called off (no site found) or taken down (`works remove`), every pledge paid back. The building takes its blocks down. */
        fun removed(level: ServerLevel, w: WorksView) {}

        /** [player] brought [units] of materials (by [Stuff.PAID]'s count) to [w]'s yard (M6 Part E: its crew's thanks). */
        fun brought(level: ServerLevel, w: WorksView, player: UUID, units: Int) {}
    }

    // ---- Setting up ----

    /**
     * Registers the works' freight with the wagons, the `/guildmark works` commands (once [READY]: until then there's
     * no such command, as before), the paying back of what's owed as players join, and the clearing up when the
     * server stops. Wired in at the mod's setup.
     */
    fun register() {
        Caravans.listen(WorksFreight)
        if (READY) WorksCommand.init()
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            (event.entity as? ServerPlayer)?.let { owed(it.level(), it) }
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> rush = false }
    }

    // ---- The market's side (Markets.step) ----

    /**
     * [entry]'s market has worked out [hour] (its caravan [carried] by wagon or not): what the hour shows for the
     * day's close (the wheat's lowest, an oven cracked, a blight on the wheat), what the works did in it (the granary
     * gave the mill grain, the second oven baked what the first couldn't have), and at a caravan hour the freight
     * that comes without a wagon or round the long way.
     */
    internal fun hour(level: ServerLevel, entry: Markets.Entry, hour: Long, carried: Boolean) {
        if (!enabled()) return
        val data = data(level)
        val m = entry.market
        val town = entry.town
        val rec = data.town(m.capital)
        val c = Conditions(hour)
        rec.low = minOf(rec.low, m.stock(Good.WHEAT) / town.target(Good.WHEAT))
        if (m.events.any { it.kind == EventKind.OVEN_CRACKED && (it.good == null || it.good == Good.BREAD) }) rec.cracked = true
        if (m.events.any { it.kind == EventKind.BLIGHT && it.good == Good.WHEAT }) rec.blighted = true
        if (!c.restDay) {
            // The carters brought the granary's grain to the mill this hour
            if (Economy.carting(c.clock) && m.carted[Route.RESERVE.ordinal] > 1e-6) {
                data.done(m.capital, WorksKind.GRANARY)?.let { it.gave = true }
                (data.done(m.capital, WorksKind.GREAT_GRANARY) ?: data.done(m.capital, WorksKind.GRANARY))?.let { eased(level, data, it, MILL, c.day) }
            }
            // The ovens baked more than the plan's own could have
            if (Economy.baking(c.clock, Economy.ahead(c.day))) data.done(m.capital, WorksKind.OVEN)?.let { oven ->
                val tools = if (m.stock(Good.TOOLS) >= 1.0) 1.0 else Economy.WORN
                if (m.worked[Maker.OVENS.ordinal] > Ovens.bake(m, town, added = false) * tools + 1e-6) eased(level, data, oven, BREAD, c.day)
            }
        }
        if (Economy.caravan(c.clock)) WorksFreight.hour(level, data, entry, hour, carried)
        data.changed()
    }

    /**
     * [entry]'s market has just closed a day (two o'clock): the town asks for what the day showed a need of; a paid-for
     * works that's stood [SITE_LOST_DAYS] days with no site is called off and paid back; newcomers sent for by works
     * just built are asked after; and the day's records start afresh. A day already closed (`market run` lived
     * through) isn't closed twice; with the clock set back, the days go back with it (see [back]).
     */
    internal fun day(level: ServerLevel, entry: Markets.Entry) {
        if (!enabled()) return
        val data = data(level)
        val m = entry.market
        val rec = data.town(m.capital)
        val day = Conditions(m.hour).day - 1
        // On ahead of the clock (`market run`), each day the market closes is the works' next, however often it's run on
        // from the same hour, as the fortunes' are; the dates kept (a want's, a lost site's) stay the market's own days
        val closed = if (m.hour > Markets.hourOf(level)) maxOf(day, rec.closed + 1) else day
        if (closed <= rec.closed) {
            rec.begin()
            data.changed()
            return
        }
        rec.closed = closed
        rec.short = ((rec.short shl 1) or (if (WorksWants.shortDay(m, entry.town)) 1 else 0)) and 0xFF
        for (want in WorksWants.wants(level, entry, data, rec)) propose(level, data, m.capital, want.kind, want.road, want.owner, want.why, day)
        for (w in data.of(m.capital)) {
            if (w.paid && w.state != WorksState.DONE && w.siteLost >= 0 && day - w.siteLost >= SITE_LOST_DAYS) {
                remove(level, w.id, "message.guildmark.works.called_off")
                continue
            }
            hire(level, data, w)
        }
        rec.begin()
        data.changed()
    }

    /**
     * The clock set back (from Markets.run, [capital]'s market going back [hours] with it): its works' days go back
     * [days] market days, the last closed and every day they date by (wanted, paid for, begun and done, a site not
     * found or lost, each thing last eased, each pledge's), and the hour their freight last landed, so the clock's next
     * close is their next and nothing waits the longer. Days lived on ahead by a market run stay ahead.
     */
    internal fun back(level: ServerLevel, capital: Long, days: Long, hours: Long) {
        if (!ready()) return
        val data = data(level)
        data.towns[capital]?.let { if (it.closed > Long.MIN_VALUE / 4) it.closed -= days }
        // Nothing goes before the clock's first day, where -1 is "none"
        fun earlier(d: Long): Long = if (d >= 0) maxOf(0L, d - days) else d
        for (w in data.of(capital)) {
            w.wanted -= days
            w.noRoom = earlier(w.noRoom)
            w.siteLost = earlier(w.siteLost)
            w.funded = earlier(w.funded)
            w.begun = earlier(w.begun)
            w.done = earlier(w.done)
            if (w.landed > Long.MIN_VALUE / 4) w.landed -= hours
            w.eased.replaceAll { _, d -> d - days }
            w.pledges.replaceAll { PledgeRec(it.player, it.name, it.marks, it.day - days, it.free) }
        }
        data.changed()
    }

    // ---- A works' life ----

    /** A new works of [kind] wanted in [capital] (for [road] or [owner]), its price fixed now, asked for on market [day] because of [why]. */
    private fun propose(level: ServerLevel, data: WorksData, capital: Long, kind: WorksKind, road: Int, owner: UUID?, why: String, day: Long): WorksRec? {
        val town = Markets.get(level)[BlockPos.of(capital)]?.town ?: return null
        val parts = WorksKinds.parts(kind, town)
        val w = WorksRec(data.next++, capital, kind, if (kind.per == WorksKind.Per.ROAD) road else -1, if (kind.per == WorksKind.Per.PLAYER) owner else null,
            parts.values.sum(), parts, WorksKinds.bill(kind), day, why)
        data.works[w.id] = w
        data.changed()
        val v = view(level, w)
        tell(v, "wanted") { it.wanted(level, v) }
        return w
    }

    /** [player]'s [marks] went toward [w]: heard, and if that's its price, it's funded. */
    private fun pledged(level: ServerLevel, data: WorksData, w: WorksRec, player: UUID, marks: Int) {
        val v = view(level, w)
        tell(v, "pledged to") { it.pledged(level, v, player, marks) }
        if (w.pledged >= w.price) fund(level, data, w)
    }

    /** [w] is paid for: its order goes out with the wagons (the long way's clock starting now). */
    private fun fund(level: ServerLevel, data: WorksData, w: WorksRec) {
        if (w.paid) return
        w.state = WorksState.FUNDED
        w.funded = today(level, w.capital)
        w.landed = hourOf(level, w.capital)
        if (w.site == null && w.noRoom >= 0 && w.siteLost < 0) w.siteLost = w.funded
        data.changed()
        val v = view(level, w)
        tell(v, "funded") { it.funded(level, v) }
    }

    /** [w]'s builders have begun (or it's laid at once): heard the first time. */
    private fun begin(level: ServerLevel, data: WorksData, w: WorksRec) {
        if (w.state != WorksState.FUNDED) return
        w.state = WorksState.BUILDING
        w.begun = today(level, w.capital)
        data.changed()
        val v = view(level, w)
        tell(v, "begun") { it.begun(level, v) }
    }

    /** [owner]'s name as [w]'s pledges knew them, else as they are on now, else none: what a Store or a House is known by. */
    private fun ownerName(level: ServerLevel, w: WorksRec, owner: UUID): String =
        w.pledges.lastOrNull { it.player == owner }?.name ?: level.server.playerList.getPlayer(owner)?.name?.string ?: ""

    /** [w] stands: from now it does its work. */
    private fun open(level: ServerLevel, data: WorksData, w: WorksRec) {
        val entry = Markets.get(level)[BlockPos.of(w.capital)]
        when (w.kind) {
            WorksKind.OVEN -> entry?.let { it.town = it.town.with(ovens = it.town.ovens + 1) }
            WorksKind.GRANARY, WorksKind.GREAT_GRANARY -> {
                if (entry != null) {
                    entry.town = entry.town.with(granary = grade(data, w.capital))
                    WorksKinds.filling(w.kind, entry.town).copyInto(w.fill)
                }
                w.landed = hourOf(level, w.capital)
            }
            WorksKind.TOWER -> {}
            WorksKind.COOKSHOP -> CookShops.open(level, w)
            WorksKind.STORE -> w.owner?.let { owner ->
                val site = w.site
                Stores.opening(level, w.capital, owner, ownerName(level, w, owner), StallDesk.colorOf(level, owner), site?.origin ?: BlockPos.of(w.capital), site?.facing ?: Direction.NORTH)
            }
            WorksKind.COUNTING_HOUSE -> w.owner?.let { owner ->
                val site = w.site
                Houses.opening(level, w.capital, owner, ownerName(level, w, owner), StallDesk.colorOf(level, owner), site?.origin ?: BlockPos.of(w.capital), site?.facing ?: Direction.NORTH)
            }
            WorksKind.STALL -> w.owner?.let { owner ->
                val site = w.site
                val name = w.pledges.lastOrNull { it.player == owner }?.name ?: level.server.playerList.getPlayer(owner)?.name?.string ?: ""
                val place = CaravanRoutes.capital(level, w.capital)?.let { VillageNames.of(it.boardAnchor) } ?: ""
                OwnStalls.opening(level, w.capital, owner, name, StallDesk.colorOf(level, owner), place, site?.origin ?: BlockPos.of(w.capital), site?.facing ?: Direction.NORTH)
            }
        }
        hire(level, data, w)
        data.changed()
        Markets.get(level).changed()
    }

    /** [w], standing, is taken out of the town's counts: its oven, its grade of granary (the grain beyond the room left going to the market), its stall. */
    private fun close(level: ServerLevel, data: WorksData, w: WorksRec) {
        val entry = Markets.get(level)[BlockPos.of(w.capital)]
        when (w.kind) {
            WorksKind.OVEN -> entry?.let { it.town = it.town.with(ovens = (it.town.ovens - 1).coerceAtLeast(it.town.planned?.ovens ?: 0)) }
            WorksKind.GRANARY, WorksKind.GREAT_GRANARY -> if (entry != null) {
                entry.town = entry.town.with(granary = grade(data, w.capital))
                // The town's grain stays the town's: what the bins left standing can't hold goes to the market
                val room = Reserve.room(entry.town)
                for (g in Good.entries) {
                    val extra = entry.market[Site.GRANARY, g] - room[g.ordinal]
                    if (extra <= 1e-9) continue
                    entry.market.take(Site.GRANARY, g, extra)
                    entry.market.add(Site.MARKET, g, extra)
                }
            }
            WorksKind.TOWER -> {}
            WorksKind.COOKSHOP -> CookShops.close(level, w)
            WorksKind.STORE -> w.owner?.let { Stores.closing(level, w.capital, it) }
            WorksKind.COUNTING_HOUSE -> w.owner?.let { Houses.closing(level, w.capital, it) }
            WorksKind.STALL -> w.owner?.let { OwnStalls.closing(level, w.capital, it) }
        }
        Markets.get(level).changed()
    }

    /**
     * Asks after the newcomer [w] sent for once built: a journeyman baker for the oven, a keeper for the stall (see
     * [Comings.hire]); one who has died is followed by whoever the comings send in their place, once they're in.
     */
    private fun hire(level: ServerLevel, data: WorksData, w: WorksRec) {
        if (w.state != WorksState.DONE) return
        if (w.hired >= 0 && Residents.get(level)[w.hired]?.alive == true) return
        val role = when (w.kind) {
            WorksKind.OVEN -> TownRole.BAKER
            WorksKind.STALL, WorksKind.COOKSHOP, WorksKind.STORE, WorksKind.COUNTING_HOUSE -> TownRole.MERCHANT
            else -> return
        }
        val id = Comings.hire(level, w.capital, role, w.id)
        if (id < 0 || id == w.hired) return
        w.hired = id
        data.changed()
    }

    /** [w] did its work, once a market [day] for each thing it eases: heard. */
    private fun eased(level: ServerLevel, data: WorksData, w: WorksRec, what: String, day: Long) {
        if (w.eased[what] == day) return
        w.eased[what] = day
        data.changed()
        val v = view(level, w)
        tell(v, "easing the town") { it.eased(level, v, what) }
    }

    /** [capital]'s standing works of [kind] (for [road]) eased [what] today. */
    internal fun eased(level: ServerLevel, capital: Long, kind: WorksKind, road: Int, what: String) {
        val w = standing(level, capital, kind, road) ?: return
        eased(level, data(level), w, what, today(level, capital))
    }

    /** [capital]'s works of [kind] (for [road]) standing done and counting: null with the works off. */
    internal fun standing(level: ServerLevel, capital: Long, kind: WorksKind, road: Int = -1): WorksRec? {
        if (!enabled()) return null
        return data(level).find(capital, kind, road)?.takeIf { it.state == WorksState.DONE }
    }

    /**
     * Works [id] is gone from the records: called off or taken down. Standing, it's taken out of the town's counts;
     * every pledge but an op's free one is paid back whole (into the pack of a player who's on, otherwise owed until
     * they next join, use the board or open a works), each told why with [message]. False if there's no such works.
     */
    internal fun remove(level: ServerLevel, id: Int, message: String = "message.guildmark.works.taken_down"): Boolean {
        val data = data(level)
        val w = data.works.remove(id) ?: return false
        if (w.state == WorksState.DONE) close(level, data, w)
        for ((player, marks) in w.pledges.filter { !it.free }.groupBy { it.player }.mapValues { (_, list) -> list.sumOf { it.marks } }) {
            if (marks <= 0) continue
            val online = level.server.playerList.getPlayer(player)
            if (online != null) {
                Trading.give(online, ModItems.GUILD_MARK.get(), marks)
                online.sendSystemMessage(Component.translatable(message, Component.translatable(w.kind.key), marks).withStyle(ChatFormatting.GOLD))
            } else {
                val name = w.pledges.last { it.player == player }.name
                data.owed.getOrPut(player) { Owed(name, 0) }.marks += marks
            }
        }
        data.changed()
        val v = view(level, w)
        tell(v, "removed") { it.removed(level, v) }
        return true
    }

    // ---- For the commands (WorksCommand) ----

    /**
     * `works propose`: [kind] wanted now in [capital], whatever the need; for [road] (a tower) or [owner] (a stall). [why] is the lang key of
     * what asked for it: the command's by default (Tales T4: the Bell Moot passes its own, `gui.guildmark.works.why.moot`).
     */
    internal fun proposeNow(
        level: ServerLevel, capital: Long, kind: WorksKind, road: Int, owner: UUID?, why: String = "gui.guildmark.works.why.proposed",
    ): WorksRec? {
        val data = data(level)
        if (data.find(capital, kind, road, owner) != null) return null
        return propose(level, data, capital, kind, road, owner, why, today(level, capital))
    }

    /** `works fund`: an op's free pledge of [marks] toward [w] (the rest of its price if null), marked as one: it took no Marks and is never paid back. */
    internal fun fundFree(level: ServerLevel, player: ServerPlayer, w: WorksRec, marks: Int?): Int {
        if (w.paid) return 0
        val data = data(level)
        val n = minOf(marks ?: Int.MAX_VALUE, w.price - w.pledged)
        if (n <= 0) {
            fund(level, data, w)
            return 0
        }
        w.pledges.add(PledgeRec(player.uuid, player.name.string, n, today(level, w.capital), true))
        data.changed()
        pledged(level, data, w, player.uuid, n)
        return n
    }

    /** Whose a town's own gift is: no player's (Tales T4). */
    internal val TOWN: UUID = UUID(0L, 0L)

    /**
     * Tales T4 (the Bell Moot, living-world/stories/design.md 6.3): the town's own gift of [marks] toward [w], a free pledge under the nil uuid
     * and the name "the town", which counts toward the price and is never paid back. Once a works: none if the town has given to it already,
     * or it is paid for. The Marks taken (no more than it still wants), 0 if none.
     */
    internal fun fundTown(level: ServerLevel, w: WorksRec, marks: Int): Int {
        if (w.paid || marks <= 0 || w.pledges.any { it.player == TOWN }) return 0
        val data = data(level)
        val n = minOf(marks, w.price - w.pledged)
        if (n <= 0) return 0
        w.pledges.add(PledgeRec(TOWN, "the town", n, today(level, w.capital), true))
        data.changed()
        pledged(level, data, w, TOWN, n)
        return n
    }

    /** `works start`: [w] paid for if it isn't, and every material still to come in its yard now (the crew comes with the building). */
    internal fun startNow(level: ServerLevel, player: ServerPlayer, w: WorksRec) {
        if (!w.paid) fundFree(level, player, w, null)
        if (!w.open) return
        for (i in w.here.indices) w.here[i] = (w.bill[i] - w.used[i]).coerceAtLeast(0)
        w.landed = hourOf(level, w.capital)
        data(level).changed()
    }

    /** `works finish`: [w] laid at once: paid for if it isn't, its bill used, begun and finished. */
    internal fun finishNow(level: ServerLevel, player: ServerPlayer, w: WorksRec) {
        if (!w.paid) fundFree(level, player, w, null)
        if (!w.open) return
        for (i in w.here.indices) {
            w.used[i] = maxOf(w.used[i], w.bill[i])
            w.here[i] = 0
        }
        w.laid = w.total
        finished(level, w.id)
    }

    /** `works site <id> here`: [w]'s site where [player] stands, a lot of its kind's size facing the way they face (until the building sites it itself). */
    internal fun siteHere(level: ServerLevel, player: ServerPlayer, w: WorksRec) {
        val pos = player.blockPosition()
        val facing = player.direction
        val (sx, sz) = when (w.kind) {
            WorksKind.OVEN -> 4 to 4
            WorksKind.GRANARY, WorksKind.GREAT_GRANARY -> 12 to 11
            WorksKind.TOWER -> 5 to 5
            WorksKind.STALL -> 3 to 3
            WorksKind.COOKSHOP -> 5 to 4
            WorksKind.STORE, WorksKind.COUNTING_HOUSE -> 9 to 8
        }
        val lot = BoundingBox(pos.x - sx / 2, pos.y, pos.z - sz / 2, pos.x - sx / 2 + sx - 1, pos.y + 7, pos.z - sz / 2 + sz - 1)
        val front = pos.relative(facing, maxOf(sx, sz) / 2 + 1)
        sited(level, w.id, WorksSite(BlockPos(lot.minX(), lot.minY(), lot.minZ()), facing, lot, front.relative(facing.clockWise), front, false))
    }

    /** `works site <id>`: [w]'s site forgotten, for the building's finder to look again. */
    internal fun siteAgain(level: ServerLevel, w: WorksRec) {
        w.site = null
        w.noRoom = -1L
        data(level).changed()
    }

    /** `works stage`: [w] at [stage] now, as the building would report it. */
    internal fun stageNow(level: ServerLevel, player: ServerPlayer, w: WorksRec, stage: Stage) {
        if (!w.paid) fundFree(level, player, w, null)
        staged(level, w.id, stage, w.laid, w.total)
    }

    /** The record of works [id], for the commands. */
    internal fun record(level: ServerLevel, id: Int): WorksRec? = data(level).works[id]

    // ---- For the talk (M6 Part E) ----

    /** The market day works [id] last eased each thing ([MILL], [BREAD], [BAND]): empty for none, or until [READY]. */
    fun lastEased(level: ServerLevel, id: Int): Map<String, Long> {
        if (!READY) return mapOf()
        return data(level).works[id]?.eased?.toMap() ?: mapOf()
    }

    /** What works [id]'s bill still wants in its yard, by [Stuff.PAID] (bill less what's here and what's used): empty until [READY]. */
    fun wanting(level: ServerLevel, id: Int): IntArray {
        if (!READY) return IntArray(0)
        val w = data(level).works[id] ?: return IntArray(0)
        return IntArray(w.bill.size) { (w.bill[it] - w.here[it] - w.used[it]).coerceAtLeast(0) }
    }

    /** Every works record of [capital], for the commands. */
    internal fun records(level: ServerLevel, capital: Long): List<WorksRec> = data(level).of(capital)

    // ---- Views and days ----

    /** [w] as the other parts see it. */
    private fun view(level: ServerLevel, w: WorksRec): WorksView {
        val pledges = LinkedHashMap<UUID, Pledge>()
        for (p in w.pledges) {
            val had = pledges[p.player]
            pledges[p.player] = if (had == null) Pledge(p.player, p.name, p.marks, p.day) else Pledge(p.player, p.name, had.marks + p.marks, had.day)
        }
        val list = pledges.values.toList()
        // The patron pledged most (the earlier on a tie); a stall's is its owner
        val patron = if (w.kind.per == WorksKind.Per.PLAYER) w.owner else list.withIndex().maxWithOrNull(compareBy({ it.value.marks }, { -it.index }))?.value?.player
        return WorksView(
            w.id, w.capital, w.kind, w.road, w.owner, w.state, w.site, w.price, w.parts, list, patron,
            w.bill.copyOf(), w.here.copyOf(), WorksFreight.onWay(level, w.capital, w.id),
            w.wanted, w.why, w.begun, w.done, w.stage, w.laid, w.total,
        )
    }

    /** [capital]'s market hour: the hour its market has worked out to, or the clock's while it has none. */
    private fun hourOf(level: ServerLevel, capital: Long): Long {
        val m = Markets.get(level)[BlockPos.of(capital)]?.market
        return if (m != null && m.hour != Long.MIN_VALUE) m.hour else Markets.hourOf(level)
    }

    /** [capital]'s market day now. */
    internal fun today(level: ServerLevel, capital: Long): Long = Conditions(hourOf(level, capital)).day
}
