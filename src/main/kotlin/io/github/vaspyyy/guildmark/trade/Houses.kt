package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRec
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Lot
import io.github.vaspyyy.guildmark.economy.Lots
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.guild.ErrandKind
import io.github.vaspyyy.guildmark.guild.Errands
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.block.VillageBoards
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The trading house (living-world/trader/design.md, Part 5; the building is build part F2's, what it does is G's): a trader's Counting House,
 * in a capital where they keep a Store, with a purse and up to six hired carters who run wagons on the tickets for them. Its records are
 * [TradeHouses]; the planner that picks each trip is [HousePlanner]; the desk is [HouseDesk], the yard and the map [YardShow], the operators'
 * commands [HouseCommand].
 *
 * - **The hours.** Once a market hour, on the pattern of the tickets' own run ([HaulRun]), each House works out the hours since it last did, up
 *   to [Markets.CATCH_UP] of them (its trips keep the time they had left over the rest, and over a clock set back). In each: its wagons that
 *   are loaded leave the yard; one whose caravan record has gone (the wagons switched off, the clock set back) gets in on its own clock; a
 *   carter back from a trip is at the yard again; at the market day's close the wages are paid from the purse (a carter unpaid three days in a
 *   row leaves); and each idle carter is sent where the planner says, if the purse holds what the trip costs and the House's town is open.
 * - **A trip.** The tickets are booked from the purse (a deposit held, as a player's is), the goods bought at the House's town's stalls with
 *   the purse ([Lots.buy], [Lots.bought]: they go on the House's books, not the owner's), and after an hour's loading the wagon leaves. Its leg
 *   into the town it is bound for is a caravan record ([Caravans.house], `Run.HOUSE`), struck by the same dice as any wagon, once a trip; it
 *   gets in after `distance / PACE` and the goods go to the tickets ([Hauls.fill] for a [Taker.House]: the pay and the deposit's share into the
 *   purse), what is left over to the stalls there; it stands an hour and the carter is back at the yard after the road home. Waylaid ([struck]),
 *   half to seven tenths of the load is gone, the wright's bill of [REPAIR] Marks comes from the purse, the carter limps in with the rest and is
 *   a day longer on the way home, and the bookings not filled are let go with their deposits given back.
 * - **Away.** The House runs while its owner is off, for [TradeConfig.houseOffline] days after their last time on; then the carters wait for
 *   their master, idle and unpaid, until they are back. The owner hears what it earned meanwhile when they are next on.
 *
 * With `tradeHouse` off ([TradeConfig.house]) no trip is sent and no wage falls due; the wagons on the road still get in and their carters come
 * home, and the purse can still be emptied at the desk. Nothing here makes a record by looking: whatever only reads uses [TradeHouses.peek].
 */
object Houses {
    /** The wright's bill for a waylaid wagon (Marks), and the extra day its carter is on the way home. */
    const val REPAIR = 10
    const val DOWN = 24000L

    /** The float the desk leaves in the purse when the profit is taken out. */
    const val FLOAT = 30

    /** The most guards a carter's wagon takes beyond the one included, and the most a wagon carries in all (guild members riding with it among them). */
    const val EXTRA_GUARDS = 2
    const val MOST_GUARDS = 4

    /** A carter unpaid this many days in a row leaves. */
    const val UNPAID_LEAVE = 3

    /** Clock ticks of a market hour and a day; the hour a loaded wagon takes to leave the yard. */
    private const val HOUR = 1000L
    private const val DAY = 24000L

    /** Ticks between the runs; the most hours worked out in one run for all the Houses together. */
    private const val TICK = 20
    private const val TICK_AT = 3
    private const val STEPS = 48

    /** Hours past a wagon's time to get in that its caravan record is waited for before the House settles the trip by its own clock. */
    private const val RECORD_WAIT = 8L

    private var registered = false

    /** Registers the House (build part G): the hourly run, the word to an owner back, the caravans heard, the carters counted for the tickets' rate, the desk, the yard and the commands. */
    fun register(modBus: IEventBus) {
        if (registered) return
        registered = true
        val bus = NeoForge.EVENT_BUS
        bus.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % TICK == TICK_AT) tick(it.server) }
        bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::loggedIn) }
        Caravans.listen(object : Caravans.Listener {
            override fun ended(level: ServerLevel, caravan: Long) = recordEnded(level, caravan)
        })
        Hauls.carterCount = { activeCarters() }
        HouseDesk.register(modBus)
        YardShow.register()
        HouseCommand.init()
    }

    // ---- From the works (F2's calls, kept) ----

    /**
     * [owner]'s Counting House in the capital whose heart is [capital] has opened (its works finished): known as [name] in their [colour], its door
     * at [origin] facing [facing]. Its record is made (or, for one taken down and raised again, opened again with what it had), the House goes in
     * the owner's book, the ladder looks again, and the guild hears a trading house has opened (unless [quiet]: one that stood before it kept books).
     */
    fun opening(level: ServerLevel, capital: Long, owner: UUID, name: String, colour: Int, origin: BlockPos, facing: Direction, quiet: Boolean = false) {
        val books = TradeBooks.get(level)
        books.of(owner).addHouse(BlockPos.of(capital))
        books.changed()
        val data = TradeHouses.get(level)
        val fresh = data.find(capital, owner) == null
        val h = data.find(capital, owner) ?: HouseRec(owner, capital).also { data.houses.add(it) }
        // One open House a player: another of theirs taken down long since and not wound up yet goes now
        data.houses.removeAll { it !== h && it.owner == owner && it.closed && it.trips.isEmpty() }
        h.name = name
        h.colour = colour.coerceIn(0, 15)
        h.origin = origin
        h.facing = facing
        h.closed = false
        val hour = Markets.hourOf(level)
        if (h.opened == TradeBooks.NEVER) h.opened = Suppliers.today(level)
        if (h.lastOn == Long.MIN_VALUE) h.lastOn = hour
        if (h.totals.since == Long.MIN_VALUE) h.totals.since = hour
        data.changed()
        if (fresh && !quiet) GuildNews.add(level, NEWS_OPENED, placeOf(level, capital).ifEmpty { "?" })
        level.server.playerList.getPlayer(owner)?.let { Rungs.check(level, it) }
    }

    /** A Counting House [w] that stood before the House kept books (finished under build part F2): its record is made now, quietly, when its owner first opens its desk. */
    internal fun adopt(level: ServerLevel, w: WorksRec): HouseRec? {
        val owner = w.owner ?: return null
        TradeHouses.peek(level)?.find(w.capital, owner)?.let { return it }
        val site = w.site
        opening(level, w.capital, owner, StoreLife.name(level, w, owner), StallDesk.colorOf(level, owner), site?.origin ?: BlockPos.of(w.capital), site?.facing ?: Direction.NORTH, quiet = true)
        return TradeHouses.peek(level)?.find(w.capital, owner)
    }

    /** [owner]'s Counting House in [capital] is gone (its works taken down): struck out of their book; its carters leave, and it winds up once its wagons are in. */
    fun closing(level: ServerLevel, capital: Long, owner: UUID) {
        TradeBooks.peek(level)?.let { books ->
            val book = books[owner]
            if (book != null && book.hasHouse(BlockPos.of(capital))) {
                book.removeHouse(BlockPos.of(capital))
                books.changed()
            }
        }
        val data = TradeHouses.peek(level) ?: return
        val h = data.find(capital, owner) ?: return
        h.closed = true
        // A wagon still loading never leaves: its goods go back to the town's stalls and its bookings are let go; the carters not out leave
        for (t in h.trips.filter { it.stage == Trip.Stage.LOADING }) unload(level, h, t)
        h.carters.removeAll { it.state != Carter.State.ROLLING }
        data.changed()
        windUp(level, data, h)
    }

    /**
     * [player] at [w]'s desk (its counting frame, its ledger or its scribe [scribe]): its owner has the House's desk ([HouseDesk]), the scribe
     * turning to them. False, with nothing done, for anyone but its owner.
     */
    internal fun desk(level: ServerLevel, player: ServerPlayer, w: WorksRec, scribe: Townsperson?): Boolean {
        if (w.owner != player.uuid) return false
        return HouseDesk.open(level, player, w, scribe)
    }

    /** The line the Works screen gives a standing Counting House: its carters and its purse, once it keeps books. */
    fun doing(level: ServerLevel, capital: Long, owner: UUID?): Component {
        val h = owner?.let { TradeHouses.peek(level)?.find(capital, it) } ?: return Component.translatable("gui.guildmark.works.doing.counting_house")
        if (h.carters.isEmpty()) return Component.translatable("gui.guildmark.works.doing.counting_house.purse", h.purse)
        return Component.translatable("gui.guildmark.works.doing.counting_house.carters", h.carters.size, h.working(), h.purse)
    }

    /** What `works` says of a standing Counting House: whether the scribe it sent for has come, and its purse and carters. */
    internal fun describe(level: ServerLevel, w: WorksRec): String {
        val h = w.owner?.let { TradeHouses.peek(level)?.find(w.capital, it) }
        val books = if (h == null) "" else "; its purse holds ${h.purse} Marks, ${h.carters.size} carter${if (h.carters.size == 1) "" else "s"}, ${h.working()} out"
        return "a Counting House" + (if (Comings.enabled() && w.hired < 0) "; its scribe hasn't come yet" else "") + books
    }

    // ---- What the other parts read ----

    /** The guild news a House opening chalks: "A trading house has opened in [town]." */
    const val NEWS_OPENED = "news.guildmark.trade.house"

    /** [owner]'s open House, if they have one, read without making any record. */
    fun of(level: ServerLevel, owner: UUID): HouseRec? = TradeHouses.peek(level)?.of(owner)

    /** [owner]'s House's colour (a dye id), if they have one open: what their wagons, the map's and the led wagon's pennant fly. */
    fun colourOf(level: ServerLevel, owner: UUID): Int? = of(level, owner)?.colour

    /** What [owner]'s House in [capital] holds in its purse, if it keeps books (has a record), for the counting frame. */
    fun purseOf(level: ServerLevel, owner: UUID, capital: Long): Int? = TradeHouses.peek(level)?.find(capital, owner)?.purse

    /** The House carters out with a wagon in the world (loading or on the road), for the tickets' posting rate ([Hauls.carterCount]). */
    fun activeCarters(): Int {
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return 0
        return TradeHouses.peek(level)?.houses?.sumOf { it.working() } ?: 0
    }

    /** The RGB colour of dye [colour], for a wagon's pennant. */
    fun rgb(colour: Int): Int = Dye.entries[colour.coerceIn(0, Dye.entries.size - 1)].rgb

    /** The most carters a House may keep now: the setting, or a quarter of the open capitals (at least one, at most six) if it is left as it comes. */
    fun most(level: ServerLevel): Int {
        val open = if (Markets.enabled()) Markets.get(level).all().size else 0
        val set = TradeConfig.carters(open)
        // tradeCarters 0 is no carters at all; otherwise a House of one carter is always allowed (design: max(1, open / 4))
        return if (TradeConfig.carters(1 shl 20) <= 0) 0 else max(1, set)
    }

    /** The name of the capital whose heart is [capital]. */
    fun placeOf(level: ServerLevel, capital: Long): String = Stores.placeOf(level, capital).ifEmpty { Word.nameOf(level, capital) }

    // ---- The desk's and the commands' doings ----

    /** Why [h] cannot hire another carter now, as a key under `gui.guildmark.trade.house.refused.`, or null if it can. */
    fun hireRefusal(level: ServerLevel, h: HouseRec): String? = when {
        !TradeConfig.house() -> "off"
        h.closed -> "closed"
        most(level) <= 0 -> "none"
        h.carters.size >= most(level) -> "most"
        else -> null
    }

    /** [h] hires a carter: a new name and face, at the yard. Gives them, or null if it may not ([hireRefusal]) and [force] does not say otherwise. */
    fun hire(level: ServerLevel, h: HouseRec, force: Boolean = false): Carter? {
        if (!force && hireRefusal(level, h) != null) return null
        if (h.closed) return null
        val id = h.nextCarter++
        val seed = h.owner.mostSignificantBits xor (h.capital * 0x2545F4914F6CDD1DL) xor (id * 0x632BE59BD9B4E019L)
        val woman = Names.woman(TownRole.CITIZEN, lookOf(seed), seed)
        val name = Names.given(null, woman, seed) + " " + Names.family(null, seed / 3)
        val c = Carter(id, name, seed, Suppliers.today(level))
        h.carters.add(c)
        TradeHouses.peek(level)?.changed()
        YardShow.hired(level, h, c)
        return c
    }

    /** The look (of the townsfolk's ordinary folk) a carter of seed [seed] has. */
    fun lookOf(seed: Long): Int = Math.floorMod(seed * 31L + 7919L, TownRole.CITIZEN.looks.toLong()).toInt()

    /** [h] lets carter [c] go: at once if they are at the yard (false if they are out with a wagon, who go when they are back). */
    fun dismiss(level: ServerLevel, h: HouseRec, c: Carter): Boolean {
        if (c.state == Carter.State.LOADING || c.state == Carter.State.ROLLING) {
            c.leaving = true
            TradeHouses.peek(level)?.changed()
            return false
        }
        h.carters.remove(c)
        TradeHouses.peek(level)?.changed()
        YardShow.left(level, h, c)
        return true
    }

    /** The extra guards carter [c] takes on their wagon ([n], 0 to [EXTRA_GUARDS]). */
    fun guards(level: ServerLevel, c: Carter, n: Int) {
        c.guards = n.coerceIn(0, EXTRA_GUARDS)
        TradeHouses.peek(level)?.changed()
    }

    /** [n] Marks into [h]'s purse ([Houses.put], from the owner); carters waiting for their wage are paid what they are owed at once. */
    fun putIn(level: ServerLevel, h: HouseRec, n: Int) {
        if (n <= 0) return
        h.purse += n
        h.totals.put += n
        payOwed(level, h)
        TradeHouses.peek(level)?.changed()
    }

    /** How much of [h]'s purse its owner may take out now: all but the [FLOAT], or all of it once the House is off or closed. */
    fun takeable(h: HouseRec): Int = if (!TradeConfig.house() || h.closed) max(0, h.purse) else max(0, h.purse - FLOAT)

    /** [n] Marks out of [h]'s purse (no more than [takeable]); gives what was taken. */
    fun takeOut(level: ServerLevel, h: HouseRec, n: Int): Int {
        val k = min(n, takeable(h))
        if (k <= 0) return 0
        h.purse -= k
        h.totals.taken += k
        TradeHouses.peek(level)?.changed()
        return k
    }

    /** The purse set to [n] by an operator (`trade house purse`): nothing is counted as put in or taken out. */
    fun setPurse(level: ServerLevel, h: HouseRec, n: Int) {
        h.purse = n.coerceAtLeast(0)
        payOwed(level, h)
        TradeHouses.peek(level)?.changed()
    }

    /** Carters waiting for their wage are paid what they are owed, if the purse holds it, and are at the yard again. */
    private fun payOwed(level: ServerLevel, h: HouseRec) {
        val wage = TradeConfig.carterWage()
        for (c in h.carters) {
            if (c.unpaid <= 0) continue
            val owed = c.unpaid * wage
            if (h.purse < owed) continue
            h.purse -= owed
            h.totals.wages += owed
            c.unpaid = 0
            if (c.state == Carter.State.UNPAID) c.state = Carter.State.IDLE
        }
    }

    // ---- The hours ----

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val data = TradeHouses.peek(level) ?: return
        if (data.houses.isEmpty() || !Markets.enabled()) return
        val now = Markets.hourOf(level)
        var steps = STEPS
        for (h in data.houses.toList()) {
            if (steps <= 0) break
            steps -= run(level, data, h, now, steps)
        }
        data.houses.removeAll { it.closed && it.trips.isEmpty() && it.carters.isEmpty() && it.purse <= 0 }
    }

    /** Works out the hours [h] is behind [now], [steps] at most; gives how many it did. */
    private fun run(level: ServerLevel, data: TradeHouses, h: HouseRec, now: Long, steps: Int): Int {
        if (h.hour == Long.MIN_VALUE) {
            h.hour = now
            if (h.lastOn == Long.MIN_VALUE) h.lastOn = now
            data.changed()
            return 0
        }
        // Time set back: everything keeps the time it had left
        if (now < h.hour) {
            shift(h, now - h.hour)
            data.changed()
            return 0
        }
        if (now - h.hour > Markets.CATCH_UP) {
            shift(h, now - Markets.CATCH_UP - h.hour)
            data.changed()
        }
        var worked = 0
        while (h.hour < now && worked < steps) {
            h.hour++
            try {
                hour(level, data, h, h.hour)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A trading house's hour could not be worked out", e)
            }
            data.changed()
            worked++
        }
        return worked
    }

    /** Moves every time [h] keeps by [hours] (back, if negative), so its trips keep the time they had left. */
    internal fun shift(h: HouseRec, hours: Long) {
        val ticks = hours * HOUR
        for (t in h.trips) {
            t.loadAt += ticks
            t.departAt += ticks
            t.arriveAt += ticks
            if (t.backAt != 0L) t.backAt += ticks
        }
        if (h.lastOn != Long.MIN_VALUE) h.lastOn += hours
        if (h.totals.since != Long.MIN_VALUE) h.totals.since += hours
        h.hour += hours
    }

    /** Whether [h]'s carters are waiting for their master: their owner has been off longer than the setting's days. */
    fun away(h: HouseRec, hour: Long = h.hour): Boolean = h.lastOn != Long.MIN_VALUE && hour - h.lastOn > TradeConfig.houseOffline() * 24L

    /** One market [hour] of [h]'s. */
    private fun hour(level: ServerLevel, data: TradeHouses, h: HouseRec, hour: Long) {
        val t = hour * HOUR
        val owner = level.server.playerList.getPlayer(h.owner)
        if (owner != null) {
            h.lastOn = hour
            h.awayPurse = h.purse
        }
        // The wagons: loaded ones leave, those whose record went get in on their own clock, carters home again
        for (trip in h.trips.toList()) {
            when (trip.stage) {
                Trip.Stage.LOADING -> if (t >= trip.departAt) depart(level, h, trip)
                Trip.Stage.OUT -> if (t >= trip.arriveAt && !recordWaits(level, trip, t)) settle(level, h, trip, trip.arriveAt, null, null)
                Trip.Stage.BACK -> if (t >= trip.backAt) home(level, h, trip)
            }
        }
        if (h.closed) {
            windUp(level, data, h)
            return
        }
        val on = TradeConfig.house()
        val away = away(h, hour)
        // The wages, at the market day's close
        if (Caravans.clockOf(hour) == Economy.NEW_DAY) {
            val day = Math.floorDiv(hour, 24L)
            if (h.paidDay != day) {
                h.paidDay = day
                if (on && !away) wages(level, h)
            }
        }
        h.idleWhy = when {
            !on -> "off"
            away -> "away"
            else -> ""
        }
        if (!on || away) return
        send(level, h, hour)
    }

    /** Whether [trip]'s caravan record is still on the road and is to be waited for (it gets in itself), a while at most. */
    private fun recordWaits(level: ServerLevel, trip: Trip, t: Long): Boolean {
        if (trip.record < 0) return false
        val c = CaravanData.get(level).caravans.firstOrNull { it.id == trip.record } ?: return false
        if (c.run != Run.HOUSE) return false
        return t < trip.arriveAt + RECORD_WAIT * HOUR
    }

    /** The wages at a day's close: each carter's from the purse, and the extra guards'; a carter it cannot pay waits for it, and after three days leaves. */
    private fun wages(level: ServerLevel, h: HouseRec) {
        val wage = TradeConfig.carterWage()
        for (c in h.carters.toList()) {
            if (h.purse >= wage) {
                h.purse -= wage
                h.totals.wages += wage
                c.unpaid = 0
                if (c.state == Carter.State.UNPAID) c.state = Carter.State.IDLE
                continue
            }
            c.unpaid++
            if (c.state == Carter.State.IDLE) c.state = Carter.State.UNPAID
            if (c.unpaid >= UNPAID_LEAVE && c.state == Carter.State.UNPAID) {
                h.carters.remove(c)
                YardShow.left(level, h, c)
                level.server.playerList.getPlayer(h.owner)?.sendSystemMessage(
                    Component.translatable("message.guildmark.trade.house.carter_left", c.name, placeOf(level, h.capital)).withStyle(ChatFormatting.GOLD),
                )
            }
        }
        // The extra guards, at their wage a day each (a half Mark carried over to the next day)
        val guards = h.carters.sumOf { it.guards }
        if (guards > 0) {
            h.guardCarry += guards * TradeConfig.guardWage()
            val owed = floor(h.guardCarry + 1e-9).toInt()
            if (h.purse >= owed) {
                h.purse -= owed
                h.totals.guardWages += owed
                h.guardCarry -= owed
            } else {
                // No purse for them: the guards are let go, and only the one each wagon has stays
                for (c in h.carters) c.guards = 0
                h.guardCarry = 0.0
            }
        }
    }

    /** Each idle carter of [h] sent where the planner says, while the purse holds the trip and the town is open. */
    private fun send(level: ServerLevel, h: HouseRec, hour: Long) {
        val idle = h.carters.filter { it.free }
        if (idle.isEmpty()) return
        val entry = Markets.get(level)[BlockPos.of(h.capital)] ?: run { h.idleWhy = "closed"; return }
        if (!TradeConfig.hauls()) {
            h.idleWhy = "tickets"
            return
        }
        val t = hour * HOUR
        for (c in idle) {
            val reserve = h.carters.size * TradeConfig.carterWage()
            val plans = HousePlanner.plans(level, h, entry, t, h.purse - reserve)
            if (plans.isEmpty()) {
                if (h.idleWhy.isEmpty()) h.idleWhy = HousePlanner.lastWhy
                return
            }
            var sent = false
            for (plan in plans.take(3)) {
                if (go(level, h, c, entry, plan, t)) {
                    sent = true
                    break
                }
            }
            if (!sent) {
                h.idleWhy = "tickets"
                return
            }
        }
    }

    /**
     * Carter [c] of [h] is sent on [plan] at clock time [t]: its tickets booked from the purse, its goods bought at the House's town's stalls,
     * and loading for the hour. False, with everything as it was, if nothing of it could be booked and bought.
     */
    private fun go(level: ServerLevel, h: HouseRec, c: Carter, entry: Markets.Entry, plan: HousePlanner.Plan, t: Long): Boolean {
        val trip = Trip(h.nextTrip, c.id, plan.dest, plan.name, plan.distance)
        val taker = Taker.House(h.owner)
        val booked = LinkedHashMap<Good, Int>()
        // The tickets first: what of them is still to be had, as the board stands now
        for (s in plan.serves) {
            val deposit = Hauls.deposit(level, s.ticket, s.units)
            if (h.purse - trip.deposit - deposit < plan.cost) continue
            if (Hauls.book(level, s.ticket, taker, s.units, h.name) != null) continue
            val b = HaulData.peek(level)?.find(s.ticket)?.second?.let { Hauls.bookingOf(it, taker) } ?: continue
            trip.tickets.add(TripTicket(s.ticket, s.good, b.units, b.deposit))
            trip.deposit += b.deposit
            booked[s.good] = (booked[s.good] ?: 0) + b.units
        }
        if (trip.tickets.isEmpty()) return false
        // What is left of the plan once the board has had its say must still pay for the goods
        val each = plan.serves.associate { it.ticket to it.each }
        val pay = trip.tickets.sumOf { it.units * (each[it.ticket] ?: 0.0) }
        val cost = plan.lines.sumOf { l -> if (l.units <= 0) 0.0 else l.cost.toDouble() * min(l.units, booked[l.good] ?: 0) / l.units }
        if (pay - cost < HousePlanner.LEAST) {
            for (b in trip.tickets) Hauls.release(level, h.owner, b.ticket, refund = true, house = true)
            return false
        }
        h.purse -= trip.deposit
        // The goods, lot by lot at the town's stalls, as a player buys them
        val m = entry.market
        for ((g, want) in booked) {
            var have = 0
            var guard = 0
            while (have < want && guard++ < 200) {
                val lot = Lots.buy(m, entry.town, g) ?: break
                if (lot.marks > h.purse) break
                Lots.bought(m, g, lot)
                h.purse -= lot.marks
                trip.cost += lot.marks
                have += lot.count
            }
            trip.load[g.ordinal] += have
            trip.bought[g.ordinal] += have
        }
        Markets.get(level).changed()
        if (trip.units() <= 0) {
            // Nothing to be had here after all: the bookings go back to the board, the deposits with them
            for (b in trip.tickets) {
                val back = Hauls.release(level, h.owner, b.ticket, refund = true, house = true)
                if (back > 0) h.purse += back
            }
            return false
        }
        h.nextTrip++
        trip.stage = Trip.Stage.LOADING
        trip.loadAt = t
        trip.departAt = t + HOUR
        trip.arriveAt = trip.departAt + Caravans.ticksFor(plan.distance)
        trip.guards = 1 + c.guards
        h.trips.add(trip)
        h.totals.cost += trip.cost
        h.totals.deposit += trip.deposit
        c.state = Carter.State.LOADING
        c.trip = trip.id
        h.idleWhy = ""
        TradeHouses.peek(level)?.changed()
        YardShow.loading(level, h, c, trip)
        return true
    }

    /** [trip]'s goods go back to the House's town's stalls (a wagon that never left) and its bookings are let go with their deposits. */
    private fun unload(level: ServerLevel, h: HouseRec, trip: Trip) {
        Markets.get(level)[BlockPos.of(h.capital)]?.let { entry ->
            val got = sell(level, entry, trip.load, far = false)
            h.purse += got
            trip.sold += got
            h.totals.sold += got
        }
        for (b in trip.tickets) {
            val back = Hauls.release(level, h.owner, b.ticket, refund = true, house = true)
            if (back > 0) {
                h.purse += back
                trip.refund += back
                h.totals.refund += back
            }
        }
        h.trips.remove(trip)
        h.carter(trip.carter)?.let { c ->
            c.state = Carter.State.IDLE
            c.trip = -1
        }
    }

    /** [trip] leaves the yard: its wagon's leg into the town it is bound for becomes a caravan record (if the wagons are on), with its guards. */
    private fun depart(level: ServerLevel, h: HouseRec, trip: Trip) {
        val c = h.carter(trip.carter)
        trip.guards = (1 + (c?.guards ?: 0) + riders(level, h.owner)).coerceAtMost(MOST_GUARDS)
        trip.stage = Trip.Stage.OUT
        c?.state = Carter.State.ROLLING
        val load = DoubleArray(Good.entries.size) { trip.load[it].toDouble() }
        trip.record = Caravans.house(level, trip.dest, BlockPos.of(h.capital), trip.arriveAt, load, trip.guards, rgb(h.colour), h.name)
        // A road in longer than the way there gets the wagon in later: the House's clock (and the map) keeps the record's time
        if (trip.record >= 0) CaravanData.get(level).caravans.firstOrNull { it.id == trip.record }?.let { trip.arriveAt = max(trip.arriveAt, it.setOut + Caravans.ticksFor(it.length)) }
        YardShow.departed(level, h, trip)
    }

    /** How many of [owner]'s guild members are out riding with their House's wagons ([ErrandKind.RIDE]): each is one more guard. */
    private fun riders(level: ServerLevel, owner: UUID): Int {
        val today = VillageBoards.day(level)
        return Errands.get(level).errands.count { it.leader == owner && it.kind == ErrandKind.RIDE && today < it.returnDay }
    }

    // ---- From the caravans ----

    /** House wagon [c] got into its town at [at] (`Caravans.arrive`): its trip is settled with what it carries. */
    fun arrived(level: ServerLevel, c: CaravanRec, at: Long) {
        val (h, trip) = tripOf(level, c.id) ?: return
        if (trip.stage != Trip.Stage.OUT) return
        val load = IntArray(Good.entries.size) { min(trip.load[it], floor(c.load[it] + 1e-6).toInt()) }
        settle(level, h, trip, at, load, null)
    }

    /**
     * House wagon [c] was waylaid at [at] (`Caravans.resolve`): the band took [taken]; the wright's bill comes from the purse, the loss goes in the
     * book, and the carter limps in with what is left, a day longer on the way home.
     */
    fun struck(level: ServerLevel, c: CaravanRec, taken: List<Pair<Good, Int>>, at: Long) {
        val (h, trip) = tripOf(level, c.id) ?: return
        if (trip.stage != Trip.Stage.OUT) return
        val load = IntArray(Good.entries.size) { min(trip.load[it], floor(c.load[it] + 1e-6).toInt()) }
        val gone = IntArray(Good.entries.size)
        for ((g, n) in taken) {
            val k = min(n, load[g.ordinal])
            load[g.ordinal] -= k
            gone[g.ordinal] += k
        }
        settle(level, h, trip, at, load, gone)
    }

    /** A caravan record ended (`Caravans.end`, or called off): a House trip it carried and that is not in yet goes on by its own clock. */
    private fun recordEnded(level: ServerLevel, caravan: Long) {
        val (_, trip) = tripOf(level, caravan) ?: return
        if (trip.stage == Trip.Stage.OUT) trip.record = -1L
    }

    private fun tripOf(level: ServerLevel, record: Long): Pair<HouseRec, Trip>? {
        if (record < 0) return null
        val data = TradeHouses.peek(level) ?: return null
        for (h in data.houses) for (t in h.trips) if (t.record == record) return h to t
        return null
    }

    /**
     * [trip] is in (or limped in) at [at] with [load] (what its wagon still carries, all of it if null), the band having taken [gone] if it was
     * waylaid: the tickets are filled and paid into the purse, the bookings not filled let go with their deposits, what is over sold at the
     * stalls there, the book written, and the carter on the road home.
     */
    private fun settle(level: ServerLevel, h: HouseRec, trip: Trip, at: Long, load: IntArray?, gone: IntArray?) {
        if (trip.stage != Trip.Stage.OUT) return
        if (load == null && trip.record >= 0) {
            // Settled on the House's own clock with its record still about (it is long late): the record carries nothing more, so a band can take nothing twice
            CaravanData.get(level).caravans.firstOrNull { it.id == trip.record && it.run == Run.HOUSE }?.load?.fill(0.0)
            trip.record = -1L
        }
        val have = load ?: trip.load.copyOf()
        val from = BlockPos.of(h.capital)
        val taker = Taker.House(h.owner)
        for (b in trip.tickets) {
            val n = min(b.units, have[b.good.ordinal])
            if (n > 0) {
                val d = Hauls.fill(level, b.ticket, n, taker, from)
                if (d != null) {
                    trip.pay += d.pay
                    trip.refund += d.refund
                    have[b.good.ordinal] -= d.units
                }
            }
            // What of the booking did not come in goes back to the board, its deposit given back (a wreck or a ticket gone loses the House nothing more)
            val back = Hauls.release(level, h.owner, b.ticket, refund = true, house = true)
            if (back > 0) trip.refund += back
        }
        // What is left over goes to the stalls there (a dealer's share if it came far), or, with no market there, back to the House's own town's
        val entry = Markets.get(level)[BlockPos.of(trip.dest)] ?: Markets.get(level)[from]
        if (entry != null && have.any { it > 0 }) trip.sold += sell(level, entry, have, far = entry.capital != from && trip.distance >= TradeConfig.haulMin())
        trip.load.fill(0)
        if (gone != null) {
            trip.waylaid = true
            trip.lost = gone.sum()
            trip.repair = REPAIR
        }
        h.purse += trip.pay + trip.refund + trip.sold - trip.repair
        val t = h.totals
        t.pay += trip.pay
        t.refund += trip.refund
        t.sold += trip.sold
        t.repair += trip.repair
        t.trips++
        if (trip.waylaid) t.waylaid++
        val c = h.carter(trip.carter)
        if (c != null) {
            c.trips++
            c.earned += trip.net
            c.robbed = trip.waylaid
        }
        h.note(BookLine(Math.floorDiv(at, HOUR), c?.name ?: "", trip.destName, trip.bought.copyOf(), trip.cost + trip.deposit, trip.pay + trip.refund + trip.sold, trip.waylaid, trip.lost, trip.repair))
        trip.stage = Trip.Stage.BACK
        trip.arriveAt = at
        trip.backAt = at + STAND + Caravans.ticksFor(trip.distance) + if (trip.waylaid) DOWN else 0L
        TradeHouses.peek(level)?.changed()
        YardShow.settled(level, h, trip)
        if (trip.waylaid) level.server.playerList.getPlayer(h.owner)?.sendSystemMessage(
            Component.translatable("message.guildmark.trade.house.waylaid", c?.name ?: "?", trip.destName, trip.lost, REPAIR).withStyle(ChatFormatting.GOLD),
        )
    }

    /** The wagon's stand at the town before it drives out (the caravans' own). */
    private const val STAND = 1000L

    /** [have] sold lot by lot to [entry]'s stalls (at a dealer's share if it came [far]); what they will not take is left with the town for nothing. Gives the Marks. */
    private fun sell(level: ServerLevel, entry: Markets.Entry, have: IntArray, far: Boolean): Int {
        val m = entry.market
        val share = if (far) max(Lots.SELL_SHARE, TradeConfig.dealerShare()) else Lots.SELL_SHARE
        var marks = 0
        for (g in Good.entries) {
            var n = have[g.ordinal]
            var guard = 0
            while (n > 0 && guard++ < 200) {
                val extra = if (far) Hauls.room(entry, g) else 0.0
                val lot = Lots.sell(m, entry.town, g, share, extra) ?: break
                val sold = if (lot.count <= n) lot else Lot(n, floor(lot.marks.toDouble() * n / lot.count).toInt())
                Lots.sold(m, g, sold)
                marks += sold.marks
                n -= sold.count
            }
            if (n > 0) {
                m.add(Economy.site(g), g, n.toDouble())
                m.tally(Tally.IMPORTED, g, n.toDouble())
            }
            have[g.ordinal] = 0
        }
        Markets.get(level).changed()
        return marks
    }

    /** [trip]'s carter is back at the yard: free again (or, unpaid meanwhile, waiting for their wage; or gone, if they were let go). */
    private fun home(level: ServerLevel, h: HouseRec, trip: Trip) {
        h.trips.remove(trip)
        val c = h.carter(trip.carter)
        if (c != null) {
            c.trip = -1
            c.state = if (c.unpaid > 0) Carter.State.UNPAID else Carter.State.IDLE
            if (h.closed || c.leaving || c.unpaid >= UNPAID_LEAVE) {
                h.carters.remove(c)
                YardShow.left(level, h, c)
            }
        }
        TradeHouses.peek(level)?.changed()
        YardShow.back(level, h, trip, c)
    }

    /** A House taken down, its wagons in: what its purse holds goes to its owner (kept for them if they are away), and its record goes. */
    private fun windUp(level: ServerLevel, data: TradeHouses, h: HouseRec) {
        if (!h.closed || h.trips.isNotEmpty()) return
        h.carters.clear()
        if (h.purse > 0) {
            val player = level.server.playerList.getPlayer(h.owner) ?: return
            Trading.give(player, ModItems.GUILD_MARK.get(), h.purse)
            player.sendSystemMessage(Component.translatable("message.guildmark.trade.house.wound_up", placeOf(level, h.capital), h.purse).withStyle(ChatFormatting.GOLD))
            h.purse = 0
        }
        data.houses.remove(h)
        data.changed()
    }

    // ---- Words ----

    /** An owner back on: what their House earned while they were away (the purse since they were last on), and a House wound up meanwhile pays out. */
    private fun loggedIn(player: ServerPlayer) {
        val level = player.level().server.overworld()
        val data = TradeHouses.peek(level) ?: return
        for (h in data.houses.filter { it.owner == player.uuid }.toList()) {
            if (h.closed) {
                windUp(level, data, h)
                continue
            }
            val hours = h.hour - h.lastOn
            if (h.lastOn != Long.MIN_VALUE && hours >= 12) {
                val earned = h.purse - h.awayPurse
                val days = min(hours, TradeConfig.houseOffline() * 24L) / 24.0
                player.sendSystemMessage(
                    Component.translatable(
                        if (away(h)) "message.guildmark.trade.house.back_waited" else "message.guildmark.trade.house.back",
                        placeOf(level, h.capital), "%.1f".format(java.util.Locale.ROOT, days), earned,
                    ).withStyle(ChatFormatting.GOLD),
                )
            }
            h.lastOn = h.hour
            h.awayPurse = h.purse
            data.changed()
        }
    }

    // ---- For the commands ----

    /** Marks an hour of [h]'s settled: what the House's money did over it is [h]'s totals then and now. */
    fun snapshot(h: HouseRec): HouseTotals = h.totals.copy()

    /** Whether every House is caught up to [now] (for `trade house step`). */
    internal fun caughtUp(level: ServerLevel, now: Long): Boolean = TradeHouses.peek(level)?.houses?.all { it.hour == Long.MIN_VALUE || it.hour >= now } ?: true

    /** Days in [hours]. */
    internal fun days(hours: Long): Double = hours / 24.0

    internal fun ceilHours(ticks: Long): Long = ceil(ticks / HOUR.toDouble()).toLong()
}
