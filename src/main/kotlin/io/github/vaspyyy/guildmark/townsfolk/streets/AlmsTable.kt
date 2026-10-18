package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.festival.FestivalRestores
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.scenes.Alms
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.Queue
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The alms table (living-world/design-influence.md section 5, M6 Part D2): on a day a twentieth of a capital's meals
 * have gone without, or in HARD times, it's decided at eleven, and at noon a noble of the town (with none about, the
 * innkeeper) carries a trestle out to the market's well and sets it up ([AlmsKeeper]: SET_UP, a block at a time), sets
 * the soup pot on it (PUT_DOWN) and the bowls out one by one (ARRANGE), and stands behind it to ladle (LADLE) for the
 * hungry, who line up in front of it (townsfolk/scenes/Alms, which this is the [Alms.Source] of), saying a word now and
 * then (`streets.alms_keep`). The pot's level is what's in it ([AlmsTableBlockEntity.soup], drawn by the client), down a
 * bowl with each ladleful. At three in the afternoon, or once the pot's been empty a while, they gather the bowls in,
 * lift the pot off, take the trestle down and carry it away. Out of every player's sight it's simply up, or gone.
 *
 * The house's kitchen spares the soup ([KITCHEN], more the hungrier the town): the town's own food, so it changes
 * nothing in the market. What a player gives at the table (food in hand, used on it; the whole stack while sneaking) is
 * real food added: it goes into the market for the town's next meal ([Economy.given], tallied GIVEN), counts as
 * supplied ([Fortunes.note]), tops the pot up, and earns the player a small favour of each who eats there that day
 * ([TownTalk.fed]). It's all checked here on the server, one table a capital.
 *
 * The table is [AlmsTableBlock]'s three blocks under the record of what stood there ([Streets.ALMS], run: the day and
 * which block), so whatever happens it's put back as it was. Its day is kept in [StreetsData]. All of it waits on
 * [Fortunes.enabled], but for `/guildmark streets alms`'s tests (this session's).
 */
object AlmsTable : Alms.Source {
    /** Bowls a pot holds at most. */
    const val POT = 32

    /** The bowls the house's kitchen spares: a dozen, a bowl more for each four meals the town went without, two dozen at most. */
    private const val KITCHEN = 12
    private const val KITCHEN_MOST = 24

    /** The share of a day's meals gone without that sets a table up. */
    private const val HUNGRY = 0.05

    /** The hours: decided from eleven, set up at noon, cleared at three, and gone by four whatever's happened. */
    private val DECIDE = Days.clock(11.0)
    private val NOON = Days.clock(12.0)
    private val CLEAR = Days.clock(15.0)
    private val GONE = Days.clock(16.0)

    /** Ticks the pot stands empty before it's cleared away, waiting on a gift. */
    private const val SPENT = 600L

    /** How far off a keeper is fetched from, and ticks between tries at finding one. */
    private const val FETCH = 96.0
    private const val HIRE_EVERY = 200L

    /** A capital's table as it's being kept just now, in memory: its keeper, and since when its pot's been empty. */
    private class Keeping {
        var keeper: AlmsKeeper? = null
        var hiredAt = Long.MIN_VALUE / 2
        var spentAt = -1L
        var noRoom = false

        /** Residents who set off for the table today and couldn't get to it: not fetched again. */
        val couldnt = HashSet<Int>()
    }

    private val keeping = HashMap<Long, Keeping>()

    /** The streets command's tables, by capital column: the game time each is cleared at. This session's only. */
    private val forced = HashMap<Long, Long>()

    // ---- The record ----

    /** The run of [day]'s table's [part], as its blocks' records carry it. */
    fun run(day: Long, part: TablePart): Long = day * 4 + part.ordinal

    /** Whether [column]'s table is the streets command's just now. */
    private fun test(level: ServerLevel, column: Long): Boolean {
        val until = forced[column] ?: return false
        return Streets.testing && level.gameTime < until + LATE
    }

    /** Whether anything here runs for [column]: the times, or the command's tests. */
    private fun on(level: ServerLevel, column: Long): Boolean = Fortunes.enabled() || test(level, column)

    /**
     * Whether the table's block at [pos] ([run]) is wanted still: today's table (the test's whatever the day) while it's
     * up, or while its keeper's clearing it away.
     */
    fun wants(level: ServerLevel, column: Long, pos: BlockPos, run: Long): Boolean {
        if (!on(level, column)) return false
        val t = StreetsData.get(level)[column] ?: return false
        if (Math.floorDiv(run, 4L) != t.almsDay) return false
        if (t.almsDay != Days.dayOf(level.overworldClockTime) && !test(level, column)) return false
        return t.almsState == StreetsData.ALMS_UP || t.almsState == StreetsData.ALMS_CLEARED && keeping[column]?.keeper?.alive == true
    }

    // ---- For the hungry (townsfolk/scenes/Alms) ----

    override fun table(level: ServerLevel, capital: Long): Alms.Table? {
        val column = Streets.column(capital)
        if (!on(level, column)) return null
        val t = StreetsData.get(level)[column] ?: return null
        if (t.almsState != StreetsData.ALMS_UP) return null
        val pos = t.almsPos ?: return null
        if (!Streets.mine(level, pos, column, Streets.ALMS)) return null
        val serving = t.soup > 0 && keeping[column]?.keeper?.serving == true
        return Alms.Table(pos, t.almsFront, serving)
    }

    override fun ladle(level: ServerLevel, capital: Long, to: Townsperson): Boolean {
        val keeper = keeping[Streets.column(capital)]?.keeper ?: return false
        return keeper.ladle(to)
    }

    override fun taken(level: ServerLevel, capital: Long, by: Townsperson) {
        val column = Streets.column(capital)
        if (!on(level, column)) return
        val data = StreetsData.get(level)
        val t = data[column] ?: return
        if (t.almsState != StreetsData.ALMS_UP) return
        // Today's table only, and standing (a record left from another day, or with the times since switched off, counts nothing)
        if (t.almsDay != Days.dayOf(level.overworldClockTime) && !test(level, column)) return
        if (!Streets.mine(level, t.almsPos ?: return, column, Streets.ALMS)) return
        t.served++
        val r = by.resident
        if (r >= 0 && r !in t.fed) {
            t.fed += r
            // A favour for each who gave here today
            val resident = Residents.get(level)[r]
            if (resident != null) for (giver in t.givers) favour(level, resident, giver)
        }
        data.changed()
    }

    private fun favour(level: ServerLevel, resident: io.github.vaspyyy.guildmark.townsfolk.Resident, player: UUID) {
        try {
            TownTalk.fed(level, resident, player)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("Couldn't count the alms table's favour", e)
        }
    }

    /** The players who gave at [capital]'s table today (for the talk's FED favour, and anyone else who asks). */
    fun givers(level: ServerLevel, capital: Long): Set<UUID> {
        val t = StreetsData.get(level)[Streets.column(capital)] ?: return setOf()
        return if (t.almsDay == Days.dayOf(level.overworldClockTime)) t.givers.toSet() else setOf()
    }

    /** The residents who ate at [capital]'s table today. */
    fun fed(level: ServerLevel, capital: Long): Set<Int> {
        val t = StreetsData.get(level)[Streets.column(capital)] ?: return setOf()
        return if (t.almsDay == Days.dayOf(level.overworldClockTime)) t.fed.toSet() else setOf()
    }

    // ---- Players at the table ----

    /**
     * [player] gives the [good] in [hand] at the table whose middle is [middle]: one, or the whole stack while sneaking,
     * as much as the pot has room for. Checked here: the table must be up and serving its pot.
     */
    fun give(level: ServerLevel, middle: BlockPos, player: ServerPlayer, hand: InteractionHand, good: Good) {
        val record = FestivalRestores.recordAt(level, middle) ?: return
        if (BlockPos.getY(record.site) != Streets.ALMS) return
        val column = Streets.column(record.site)
        val data = StreetsData.get(level)
        val t = data[column] ?: return
        val be = level.getBlockEntity(middle) as? AlmsTableBlockEntity
        if (!on(level, column) || t.almsPos != middle || t.almsState != StreetsData.ALMS_UP || be == null || !be.pot) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.streets.alms_closed"))
            return
        }
        val stack = player.getItemInHand(hand)
        if (stack.isEmpty || Good.of(stack.item) != good || good.food <= 0.0) return
        val room = t.cap - t.soup
        if (room <= 0) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.streets.alms_full"))
            return
        }
        val want = if (player.isShiftKeyDown) stack.count else 1
        val units = min(want, max(1, ceil(room / good.food).toInt()))
        val meals = units * good.food
        val bowls = min(room, (floor(t.given + meals) - floor(t.given)).toInt())
        if (!player.isCreative) stack.shrink(units)
        t.given += meals
        t.soup = min(t.cap, t.soup + bowls)
        if (player.uuid !in t.givers) t.givers += player.uuid
        data.changed()
        if (t.soup > 0) keeping[column]?.spentAt = -1L
        be.set(t.soup, t.cap, true, min(AlmsTableBlockEntity.BOWLS, t.soup).coerceAtLeast(be.bowls))
        be.gift(level.gameTime)
        level.playSound(null, middle, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.35f, 1.3f + level.random.nextFloat() * 0.2f)
        // Into the market for the town's next meal, and counted as supplied
        market(level, column)?.let { entry ->
            Economy.given(entry.market, good, units.toDouble())
            Markets.get(level).changed()
            Fortunes.note(level, entry.market.capital, "supplied", 1, 3, about = player.uuid)
        }
        // A favour from each who's eaten here today already
        for (id in t.fed.toList()) Residents.get(level)[id]?.let { favour(level, it, player.uuid) }
        keeping[column]?.keeper?.thank(player)
        player.sendOverlayMessage(
            if (bowls > 0) Component.translatable("message.guildmark.streets.alms_given", bowls)
            else Component.translatable("message.guildmark.streets.alms_given_little"),
        )
    }

    /** [player] speaks to the table at [middle] (empty-handed): its keeper has a word for them. */
    fun spoken(level: ServerLevel, middle: BlockPos, player: ServerPlayer) {
        val record = FestivalRestores.recordAt(level, middle) ?: return
        if (BlockPos.getY(record.site) != Streets.ALMS) return
        keeping[Streets.column(record.site)]?.keeper?.greet(player)
    }

    /** The market of [column]'s capital, if it has one open. */
    private fun market(level: ServerLevel, column: Long): Markets.Entry? {
        Streets.here(column)?.let { h -> return Markets.get(level)[h.capital.center] }
        return Markets.get(level).all().firstOrNull { Streets.column(it.market.capital) == column }
    }

    // ---- Once a second, a player in the capital ----

    fun look(level: ServerLevel, column: Long, h: Scenes.Here, now: Long) {
        val time = level.overworldClockTime
        val today = Days.dayOf(time)
        val clock = Days.offsetOf(time)
        if (forced[column]?.let { !Streets.testing || now > it + LATE } == true) forced.remove(column)
        val test = test(level, column)
        val on = Fortunes.enabled() || test
        val data = StreetsData.get(level)
        var t = data[column]
        // A table no longer wanted (its day's over, or the times are off): cleared away
        if (t != null && (t.almsState == StreetsData.ALMS_PLANNED || t.almsState == StreetsData.ALMS_UP) && (!on || !test && t.almsDay != today)) {
            t.almsState = StreetsData.ALMS_CLEARED
            data.changed()
        }
        // Decided from eleven: a hungry day, or hard times
        if (on && !test && clock >= DECIDE && clock < CLEAR && (t == null || t.almsDay != today)) {
            val why = why(level, h)
            if (why != null) {
                t = t ?: data.town(column)
                plan(level, h, t, today, why.first, why.second, null)
                data.changed()
            }
        }
        t ?: return
        val pos = t.almsPos ?: return
        val k = keeping.getOrPut(column) { Keeping() }
        when (t.almsState) {
            StreetsData.ALMS_PLANNED -> if (clock >= NOON) {
                t.almsState = StreetsData.ALMS_UP
                data.changed()
            }
            StreetsData.ALMS_UP -> {
                val over = if (test) now >= (forced[column] ?: now) else clock >= CLEAR
                if (t.soup <= 0 && k.spentAt < 0) k.spentAt = now
                val spent = t.soup <= 0 && now - k.spentAt > SPENT
                if (over || spent || k.noRoom) {
                    t.almsState = StreetsData.ALMS_CLEARED
                    data.changed()
                }
            }
        }
        val seen = Streets.seen(level, pos, Streets.SIGHT)
        val keeper = k.keeper?.takeIf { it.alive }
        if (keeper == null) k.keeper = null
        when (t.almsState) {
            StreetsData.ALMS_UP -> when {
                keeper != null -> {}
                seen -> hire(level, column, h, t, k, now)
                else -> standUp(level, column, t, k)
            }
            StreetsData.ALMS_CLEARED -> if (standing(level, column, t)) {
                val late = if (test) now > (forced[column] ?: now) + LATE else clock >= GONE || t.almsDay != today
                when {
                    keeper != null && !late -> {}
                    seen && !late -> hire(level, column, h, t, k, now)
                    else -> {
                        keeper?.let { it.leave() }
                        takeDown(level, column, t)
                    }
                }
            }
        }
    }

    /** Why [h]'s capital needs a table today: hard times, or a twentieth of its meals gone without (and the meals missed). */
    private fun why(level: ServerLevel, h: Scenes.Here): Pair<String, Double>? {
        val entry = Markets.get(level)[h.capital.center]
        val missed = entry?.let { e ->
            val yesterday = e.market.hungry.lastOrNull() ?: 0.0
            val today = Good.FOODS.sumOf { e.market.today(Tally.SHORT, it) * it.food }
            max(yesterday, today)
        } ?: 0.0
        if (Fortunes.times(level, h.key) == Times.HARD) return "hard times" to missed
        val meals = (entry?.town?.people ?: 0) * Economy.MEALS
        if (meals <= 0.0 || missed < 1.0 || missed / meals < HUNGRY) return null
        return String.format(Locale.ROOT, "%.0f meals gone without, %.0f%% of a day's", missed, 100.0 * missed / meals) to missed
    }

    /** Today's table for [t]'s capital laid out: by the market's well (the nearest to [near], if given), with the kitchen's soup. */
    private fun plan(level: ServerLevel, h: Scenes.Here, t: StreetsData.Town, day: Long, why: String, missed: Double, near: BlockPos?): Boolean {
        val k = keeping.getOrPut(Streets.column(h.capital)) { Keeping() }
        k.spentAt = -1L
        k.noRoom = false
        k.couldnt.clear()
        t.almsDay = day
        t.served = 0
        t.given = 0.0
        t.givers.clear()
        t.fed.clear()
        t.almsWhy = why
        t.cap = POT
        t.soup = min(KITCHEN_MOST, KITCHEN + (missed / 4).toInt())
        val site = site(level, h, near)
        if (site == null) {
            // Nowhere to set it up today: noted for the streets command
            t.almsState = StreetsData.ALMS_CLEARED
            t.almsPos = null
            t.almsWhy = "$why, but there's no room by the market's wells"
            return false
        }
        t.almsPos = site.first
        t.almsFront = site.second
        t.almsState = StreetsData.ALMS_PLANNED
        return true
    }

    /** Where the table goes: by one of the market's wells, three blocks out from it toward the square, its front away from it. */
    private fun site(level: ServerLevel, h: Scenes.Here, near: BlockPos?): Pair<BlockPos, Direction>? {
        val capital = h.capital
        val market = h.places.census.plan.market
        val square = market.grow(3)
        val middle = capital.world((market.x0 + market.x1) / 2, 0, (market.z0 + market.z1) / 2)
        val to = near ?: middle
        val wells = h.places.wells.filter { w -> capital.local(w.x, w.z).let { (lx, lz) -> square.contains(lx, lz) } }
            .sortedBy { flat(it, to) }
        for (well in wells) {
            val dirs = Direction.Plane.HORIZONTAL.toList().sortedByDescending { d -> d.stepX * (middle.x - well.x) + d.stepZ * (middle.z - well.z) }
            for (d in dirs) fit(level, well, d)?.let { return it to d }
        }
        return null
    }

    private fun flat(a: BlockPos, b: BlockPos): Int = (a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z)

    /** The table's middle, three blocks from [well] toward [front], if there's room for it, its keeper behind it and a line in front. */
    private fun fit(level: ServerLevel, well: BlockPos, front: Direction): BlockPos? {
        val along = AlmsTableBlock.along(front)
        for (dy in intArrayOf(0, 1, -1)) {
            val middle = well.relative(front, 3).above(dy)
            val parts = TablePart.entries.map { middle.relative(along, it.offset) }
            if (parts.any { !room(level, it) }) continue
            if (!Places.standable(level, middle.relative(front.opposite))) continue
            val line = Queue.lay(level, middle.relative(front), front, 4, open = true) ?: continue
            if (line.size < 4) continue
            return middle
        }
        return null
    }

    /** Whether a block of the table may stand at [pos]: loaded, room for it (and over it), on firm ground. */
    private fun room(level: ServerLevel, pos: BlockPos): Boolean =
        level.isLoaded(pos) && FestivalRestores.free(level, pos, solid = false) && Places.floor(level, pos.below()) &&
            !FestivalRestores.ours(level.getBlockState(pos)) && level.getBlockState(pos.above()).let { it.isAir || it.canBeReplaced() }

    // ---- Setting it up and taking it down ----

    /** Where [part] of [t]'s table stands. */
    fun partAt(t: StreetsData.Town, part: TablePart): BlockPos? = t.almsPos?.relative(AlmsTableBlock.along(t.almsFront), part.offset)

    /** Whether [part] of [t]'s table is standing. */
    fun up(level: ServerLevel, column: Long, t: StreetsData.Town, part: TablePart): Boolean {
        val pos = partAt(t, part) ?: return false
        return Streets.mine(level, pos, column, Streets.ALMS, run(t.almsDay, part))
    }

    /** Whether any of [t]'s table is standing. */
    private fun standing(level: ServerLevel, column: Long, t: StreetsData.Town): Boolean = TablePart.entries.any { up(level, column, t, it) }

    /** [part] of [t]'s table put up now; true if it stands. */
    fun put(level: ServerLevel, column: Long, t: StreetsData.Town, part: TablePart, sound: Boolean): Boolean {
        val pos = partAt(t, part) ?: return false
        if (t.almsState != StreetsData.ALMS_UP) return false
        if (!Streets.place(level, pos, AlmsTableBlock.state(t.almsFront, part), column, Streets.ALMS, run(t.almsDay, part), solid = true)) return false
        if (part == TablePart.MIDDLE) (level.getBlockEntity(pos) as? AlmsTableBlockEntity)?.set(t.soup, t.cap, false, 0)
        if (sound) level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.8f, 0.85f + level.random.nextFloat() * 0.1f)
        return true
    }

    /** [part] of [t]'s table taken down now, what stood there put back. */
    fun strike(level: ServerLevel, column: Long, t: StreetsData.Town, part: TablePart, sound: Boolean) {
        val pos = partAt(t, part) ?: return
        if (!Streets.mine(level, pos, column, Streets.ALMS)) return
        Streets.restore(level, pos, column, Streets.ALMS)
        if (sound) level.playSound(null, pos, SoundEvents.WOOD_BREAK, SoundSource.NEUTRAL, 0.7f, 0.9f + level.random.nextFloat() * 0.1f)
    }

    /** The middle's pot and bowls, if it's standing. */
    fun pot(level: ServerLevel, t: StreetsData.Town): AlmsTableBlockEntity? {
        val pos = t.almsPos ?: return null
        if (!level.isLoaded(pos)) return null
        return level.getBlockEntity(pos) as? AlmsTableBlockEntity
    }

    /** Bowls set out on the table for a pot with [soup] in it. */
    fun bowlsFor(soup: Int): Int = min(AlmsTableBlockEntity.BOWLS, soup)

    /** Out of everyone's sight: the table simply up, its pot on it and its bowls out. */
    private fun standUp(level: ServerLevel, column: Long, t: StreetsData.Town, k: Keeping) {
        for (part in TablePart.entries) if (!up(level, column, t, part) && !put(level, column, t, part, sound = false)) {
            k.noRoom = true
            return
        }
        pot(level, t)?.set(t.soup, t.cap, true, bowlsFor(t.soup))
    }

    /** Out of everyone's sight (or late): the table simply gone, everything put back. */
    private fun takeDown(level: ServerLevel, column: Long, t: StreetsData.Town) {
        for (part in TablePart.entries) strike(level, column, t, part, sound = false)
    }

    /** Someone to keep the table: a noble of the town, else its innkeeper, the nearest that can be had; none, tried again soon. */
    private fun hire(level: ServerLevel, column: Long, h: Scenes.Here, t: StreetsData.Town, k: Keeping, now: Long) {
        if (now - k.hiredAt < HIRE_EVERY) return
        k.hiredAt = now
        val pos = t.almsPos ?: return
        val at = Vec3.atCenterOf(pos)
        val bodies = Residents.get(level).of(h.capital.center).asSequence()
            .filter { it.alive && (it.role == TownRole.NOBLE || it.role == TownRole.INNKEEPER) && it.id !in k.couldnt }
            .mapNotNull { r -> (r.uuid?.let { level.getEntity(it) } as? Townsperson)?.takeIf { able(level, it, r.id) && it.distanceToSqr(at) < FETCH * FETCH } }
            .toList()
        val p = bodies.filter { it.role == TownRole.NOBLE }.minByOrNull { it.distanceToSqr(at) } ?: bodies.minByOrNull { it.distanceToSqr(at) } ?: return
        val keeper = AlmsKeeper(column, p)
        if (p.isSleeping) p.stopSleeping()
        p.standUp()
        p.routine.interrupt()
        p.navigation.stop()
        p.director = keeper
        k.keeper = keeper
    }

    /** Whether [p] (resident [id]) can be taken off what they're doing to keep the table. */
    private fun able(level: ServerLevel, p: Townsperson, id: Int): Boolean =
        p.isAlive && !p.isRemoved && p.resident == id && p.director == null && p.captiveOf < 0 && !p.isLeader && !p.watcher &&
            !p.crowd && !p.isPassenger && p.attending == null && p.target == null && p.activity != Activity.FLEE &&
            level.isPositionEntityTicking(p.blockPosition())

    /** The soup's run out (the last bowl ladled): the table waits a while on a gift before it's cleared. */
    internal fun ranOut(column: Long, now: Long) {
        keeping[column]?.let { if (it.spentAt < 0) it.spentAt = now }
    }

    /** Resident [id] set off for [column]'s table and couldn't get to it: someone else is fetched next time. */
    internal fun couldnt(column: Long, id: Int) {
        if (id >= 0) keeping[column]?.couldnt?.add(id)
    }

    // ---- The streets command ----

    /**
     * `/guildmark streets alms`: a table set up now by the market's well nearest [at], whatever the hour and the
     * times, kept for [ticks] and then cleared away. Why not, if it can't be.
     */
    fun force(level: ServerLevel, h: Scenes.Here, at: BlockPos, ticks: Long): String? {
        val column = Streets.column(h.capital)
        val data = StreetsData.get(level)
        val t = data.town(column)
        if (standing(level, column, t)) return "there's a table up already: /guildmark streets alms clear takes it away"
        Streets.testing = true
        forced[column] = level.gameTime + ticks
        keeping[column]?.keeper?.leave()
        keeping.remove(column)
        val ok = plan(level, h, t, Days.dayOf(level.overworldClockTime), "the streets command's", 24.0, at)
        if (ok) t.almsState = StreetsData.ALMS_UP
        data.changed()
        if (!ok) return "there's no room by the market's wells"
        look(level, column, h, level.gameTime)
        return null
    }

    /** `/guildmark streets alms clear`: [h]'s table cleared away now. */
    fun clear(level: ServerLevel, h: Scenes.Here): Boolean {
        val column = Streets.column(h.capital)
        val data = StreetsData.get(level)
        val t = data[column] ?: return false
        forced.remove(column)
        keeping[column]?.keeper?.leave()
        keeping.remove(column)
        val was = standing(level, column, t)
        takeDown(level, column, t)
        if (t.almsState == StreetsData.ALMS_PLANNED || t.almsState == StreetsData.ALMS_UP) t.almsState = StreetsData.ALMS_CLEARED
        data.changed()
        return was
    }

    /** For the streets command: [h]'s table today, and why. */
    fun status(level: ServerLevel, column: Long, h: Scenes.Here): String? {
        val t = StreetsData.get(level)[column] ?: return null
        val today = Days.dayOf(level.overworldClockTime)
        if (t.almsDay != today && !test(level, column)) return null
        val state = when (t.almsState) {
            StreetsData.ALMS_PLANNED -> "to be set up at noon"
            StreetsData.ALMS_UP -> if (keeping[column]?.keeper?.serving == true) "serving" else "up (its keeper on the way)"
            StreetsData.ALMS_CLEARED -> if (standing(level, column, t)) "being cleared away" else "cleared away"
            else -> "none"
        }
        val pos = t.almsPos?.toShortString() ?: "nowhere"
        return "alms table at $pos: $state; ${t.soup} of ${t.cap} bowls in the pot, ${t.served} served, " +
            "${t.givers.size} gave (%.1f meals); why: ${t.almsWhy}".format(Locale.ROOT, t.given)
    }

    fun forget() {
        keeping.clear()
        forced.clear()
    }

    /** Ticks a test's table is kept on the books after it's over, for it to be cleared away where it's seen. */
    private const val LATE = 1200L
}

/**
 * The keeper of the alms table (see [AlmsTable]), the director of their townsperson meanwhile: off to the table with
 * the trestle on their shoulder, setting it up a block at a time (SET_UP), the pot on it (PUT_DOWN) and the bowls out
 * (ARRANGE); then behind it facing the line, ladling a bowl for each who holds out their hands (LADLE), now and then
 * calling the shy over (`streets.alms_keep`), and bowing to a player who gives; and when it's done, the bowls gathered
 * in, the pot lifted off (PICK_UP) and the trestle taken down and carried off. Whatever they're at, they do what the
 * table needs next as it stands, so a keeper taken over halfway (or fetched to a table already up) picks up where it
 * is. Hurt, or out of every player's sight a while, they go back to their day and the table's left to the clock.
 */
private class AlmsKeeper(private val column: Long, private val p: Townsperson) : Director {
    private enum class S { GO, BUILD, POT, BOWLS, SERVE, LADLE, GATHER, LIFT, STRIKE, OFF, DONE }

    private var s = S.GO
    private var t = 0
    private var age = 0
    private var walker: Walker? = null
    private var lastTick = -1
    private var unseen = 0
    private var started = false
    private var tries = 0

    /** Whom they're ladling for, and when they last called out. */
    private var to: Townsperson? = null
    private var called = 0L

    /** Whether they're still at it. */
    val alive: Boolean get() = s != S.DONE && p.isAlive && !p.isRemoved && p.director === this

    /** Whether they're behind the table serving now. */
    val serving: Boolean get() = alive && (s == S.SERVE || s == S.LADLE)

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        if (s == S.DONE) return
        // Back from something that had them meanwhile, or hurt: the table's the clock's
        if (lastTick >= 0 && p.tickCount - lastTick > 5 || p.hurtTime > 0) return finish()
        lastTick = p.tickCount
        if (++age > MOST) return finish()
        if (p.tickCount % 40 == 0) {
            unseen = if (level.getNearestPlayer(p, Aside.REACH) == null) unseen + 40 else 0
            if (unseen > UNSEEN) return finish()
        }
        val data = StreetsData.get(level)
        val town = data[column] ?: return finish()
        val pos = town.almsPos ?: return finish()
        val front = town.almsFront
        val behind = pos.relative(front.opposite)
        val wantUp = town.almsState == StreetsData.ALMS_UP
        if (!wantUp && town.almsState != StreetsData.ALMS_CLEARED) return finish()
        // Spoken to: they stop to answer (see interact)
        p.attending?.let {
            p.navigation.stop()
            p.lookAt(it)
            return
        }
        val walker = walker ?: Walker(p).also { walker = it }
        if (!started) {
            started = true
            go(level, town, behind)
        }
        t++
        val ahead = Vec3(pos.x + 0.5, pos.y + AlmsTableBlock.TOP, pos.z + 0.5)
        when (s) {
            S.GO -> {
                if (!p.carried.isEmpty && p.activity != Activity.CARRY_BLOCK) p.act(Activity.CARRY_BLOCK)
                val state = walker.tick()
                if (state == Walker.State.WALKING && t < 1200) return
                walker.stop()
                if (p.blockPosition().distManhattan(behind) > 2) {
                    AlmsTable.couldnt(column, p.resident)
                    return finish()
                }
                p.setPos(behind.x + 0.5, p.y, behind.z + 0.5)
                next(level, town)
            }
            S.BUILD -> {
                face(front, ahead)
                // A block of the trestle at each stroke of setting it up: its ends, then its middle
                val part = when (t) {
                    BUILD_AT[0] -> TablePart.LEFT
                    BUILD_AT[1] -> TablePart.RIGHT
                    BUILD_AT[2] -> TablePart.MIDDLE
                    else -> null
                }
                if (part != null && !AlmsTable.up(level, column, town, part)) AlmsTable.put(level, column, town, part, sound = true)
                if (t >= Activity.SET_UP.ticks) {
                    if (TablePart.entries.any { !AlmsTable.up(level, column, town, it) } && ++tries >= 3) {
                        // No room after all (someone in the way, the ground gone): it's not to be today
                        town.almsState = StreetsData.ALMS_CLEARED
                        town.almsWhy = town.almsWhy + "; there wasn't room to set it up"
                        data.changed()
                    }
                    next(level, town)
                }
            }
            S.POT -> {
                face(front, ahead)
                if (t == POT_AT) {
                    p.carried = ItemStack.EMPTY
                    AlmsTable.pot(level, town)?.set(town.soup, town.cap, true, 0)
                    level.playSound(null, pos, SoundEvents.LANTERN_PLACE, SoundSource.NEUTRAL, 0.8f, 0.6f)
                }
                if (t >= Activity.PUT_DOWN.ticks + 4) next(level, town)
            }
            S.BOWLS -> {
                face(front, ahead)
                val be = AlmsTable.pot(level, town) ?: return next(level, town)
                val want = AlmsTable.bowlsFor(town.soup)
                if (t % BOWL_EVERY == BOWL_EVERY / 2 && be.bowls < want) {
                    be.set(town.soup, town.cap, true, be.bowls + 1)
                    Streets.sound(level, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, "streets.bowl", BOWL_REACH, 0.45f, 1.1f)
                }
                if (t >= Activity.ARRANGE.ticks || be.bowls >= want && t >= 20) next(level, town)
            }
            S.SERVE -> {
                if (!wantUp) return next(level, town)
                // Kept where they belong: back behind it if they've been pushed off
                if (p.blockPosition().distManhattan(behind) > 1) return go(level, town, behind)
                val head = pos.relative(front)
                face(front, Vec3(head.x + 0.5, head.y + 1.5, head.z + 0.5))
                val be = AlmsTable.pot(level, town)
                if (be != null && (be.soup != town.soup || be.bowls != AlmsTable.bowlsFor(town.soup) || !be.pot)) {
                    be.set(town.soup, town.cap, true, AlmsTable.bowlsFor(town.soup))
                }
                // Calling the shy over now and then, with soup to give and nobody at the table
                val now = level.gameTime
                if (town.soup > 0 && now - called > CALL_EVERY && p.random.nextInt(4) == 0) {
                    called = now
                    val hearer = Words.hearer(p)
                    if (hearer != null) Words.say(p, hearer, Words.of(p, hearer, "streets.alms_keep", CALL), "calling the hungry to the alms table")
                }
                if (p.activity != Activity.NONE && p.activity != Activity.ARMS_FOLDED && p.actDone) p.act(Activity.NONE)
            }
            S.LADLE -> {
                val who = to
                if (who != null && who.isAlive) p.lookAt(Vec3(who.x, who.eyeY - 0.3, who.z)) else face(front, ahead)
                if (t == SAY_AT && p.random.nextInt(3) == 0) {
                    val hearer = Words.hearer(p)
                    if (hearer != null) Words.say(p, hearer, Words.of(p, hearer, "streets.alms_keep", HANDING), "ladling at the alms table")
                }
                if (t == DIP_AT) {
                    // The ladle dips: a bowl's worth out of the pot
                    town.soup = (town.soup - 1).coerceAtLeast(0)
                    data.changed()
                    if (town.soup == 0) AlmsTable.ranOut(column, level.gameTime)
                    AlmsTable.pot(level, town)?.let { be ->
                        be.set(town.soup, town.cap, true, AlmsTable.bowlsFor(town.soup))
                        be.ladle(level.gameTime)
                    }
                    Streets.sound(level, pos.x + 0.5, pos.y + 1.1, pos.z + 0.5, "streets.ladle", LADLE_REACH, 0.8f, 0.95f + p.random.nextFloat() * 0.1f)
                }
                if (t >= Activity.LADLE.ticks) {
                    to = null
                    s = S.SERVE
                    t = 0
                    p.act(Activity.NONE)
                }
            }
            S.GATHER -> {
                face(front, ahead)
                val be = AlmsTable.pot(level, town) ?: return next(level, town)
                if (t % BOWL_EVERY == BOWL_EVERY / 2 && be.bowls > 0) {
                    be.set(be.soup, be.cap, be.pot, be.bowls - 1)
                    Streets.sound(level, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, "streets.bowl", BOWL_REACH, 0.4f, 0.95f)
                }
                if (be.bowls == 0 && t >= 20 || t >= Activity.ARRANGE.ticks * 2) next(level, town)
            }
            S.LIFT -> {
                face(front, ahead)
                if (t == LIFT_AT) {
                    AlmsTable.pot(level, town)?.let { it.set(it.soup, it.cap, false, 0) }
                    p.carried = ItemStack(Items.CAULDRON)
                    level.playSound(null, pos, SoundEvents.LANTERN_BREAK, SoundSource.NEUTRAL, 0.7f, 0.6f)
                }
                if (t >= Activity.PICK_UP.ticks + 4) next(level, town)
            }
            S.STRIKE -> {
                face(front, ahead)
                val part = when (t) {
                    BUILD_AT[0] -> TablePart.MIDDLE
                    BUILD_AT[1] -> TablePart.RIGHT
                    BUILD_AT[2] -> TablePart.LEFT
                    else -> null
                }
                if (part != null) {
                    AlmsTable.strike(level, column, town, part, sound = true)
                    p.carried = ItemStack(Items.OAK_PLANKS)
                }
                if (t >= Activity.SET_UP.ticks) next(level, town)
            }
            S.OFF -> {
                if (p.activity != Activity.CARRY_BLOCK && !p.carried.isEmpty) p.act(Activity.CARRY_BLOCK)
                if (walker.tick() != Walker.State.WALKING || t > 400) finish()
            }
            S.DONE -> {}
        }
    }

    /** Off to their place behind the table, carrying the trestle if it's still to go up. */
    private fun go(level: ServerLevel, town: StreetsData.Town, behind: BlockPos) {
        s = S.GO
        t = 0
        val building = town.almsState == StreetsData.ALMS_UP && TablePart.entries.any { !AlmsTable.up(level, column, town, it) }
        p.carried = if (building) ItemStack(Items.OAK_PLANKS) else ItemStack.EMPTY
        p.act(if (building) Activity.CARRY_BLOCK else Activity.NONE)
        walker?.go(behind, Jobs.WALK, exact = true)
    }

    /** On to whatever the table needs next as it stands: built, its pot on and its bowls out, then served; or cleared. */
    private fun next(level: ServerLevel, town: StreetsData.Town) {
        t = 0
        val be = AlmsTable.pot(level, town)
        val missing = TablePart.entries.any { !AlmsTable.up(level, column, town, it) }
        if (town.almsState == StreetsData.ALMS_UP) {
            when {
                missing -> {
                    s = S.BUILD
                    p.carried = ItemStack(Items.OAK_PLANKS)
                    p.act(Activity.SET_UP)
                }
                be != null && !be.pot -> {
                    s = S.POT
                    p.carried = ItemStack(Items.CAULDRON)
                    p.act(Activity.PUT_DOWN)
                }
                be != null && be.bowls < AlmsTable.bowlsFor(town.soup) -> {
                    s = S.BOWLS
                    p.carried = ItemStack.EMPTY
                    p.act(Activity.ARRANGE)
                }
                else -> {
                    s = S.SERVE
                    p.carried = ItemStack.EMPTY
                    p.act(Activity.NONE)
                }
            }
            return
        }
        when {
            be != null && be.bowls > 0 -> {
                s = S.GATHER
                p.carried = ItemStack.EMPTY
                p.act(Activity.ARRANGE)
            }
            be != null && be.pot -> {
                s = S.LIFT
                p.act(Activity.PICK_UP)
            }
            TablePart.entries.any { AlmsTable.up(level, column, town, it) } -> {
                s = S.STRIKE
                p.act(Activity.SET_UP)
            }
            else -> off(level)
        }
    }

    /** The trestle carried off a little way, toward home. */
    private fun off(level: ServerLevel) {
        s = S.OFF
        t = 0
        val home = p.life?.home?.front?.outside
        val to = home?.takeIf { it.distSqr(p.blockPosition()) < 48.0 * 48.0 } ?: p.blockPosition().relative(p.direction.opposite, 8)
        walker?.go(to, Jobs.WALK, near = 2.0)
        if (!p.carried.isEmpty) p.act(Activity.CARRY_BLOCK)
    }

    /** Facing the table's front (looking at [at]), turning a little at a time. */
    private fun face(front: Direction, at: Vec3) {
        p.lookAt(at)
        p.face(Mth.approachDegrees(p.yBodyRot, front.toYRot(), Aside.TURN))
    }

    /** One of the hungry holds out their hands: a ladleful for them, if they're at the table serving. */
    fun ladle(who: Townsperson): Boolean {
        if (!serving || s == S.LADLE) return false
        to = who
        s = S.LADLE
        t = 0
        p.act(Activity.LADLE)
        return true
    }

    /** A player gave at the table: a bow to them. */
    fun thank(player: ServerPlayer) {
        if (s != S.SERVE) return
        p.lookAt(player.eyePosition)
        p.act(Activity.BOW)
    }

    /** A player speaks to the table: a word for them. */
    fun greet(player: ServerPlayer) {
        if (!serving) return
        p.lookAt(player.eyePosition)
        Words.say(p, player, Words.of(p, player, "streets.alms_keep", CALL), "spoken to at the alms table", force = true)
    }

    /** Spoken to at the table: a word of the soup; anywhere else, they talk as they would (and go on after). */
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        if (!serving) return false
        p.attend(player)
        greet(player)
        return true
    }

    /** Sent away (the table's cleared without them): back to their day. */
    fun leave() = finish()

    /** Done (or no longer theirs): back to their day, with whatever's in hand carried off. */
    private fun finish() {
        if (s == S.DONE) return
        s = S.DONE
        walker?.stop()
        if (p.director === this) p.director = null
        if (!p.carried.isEmpty) p.carriedUntil = p.tickCount + CARRY_AWAY
        if (p.activity == Activity.CARRY_BLOCK || p.activity == Activity.LADLE || p.activity == Activity.SET_UP || p.activity == Activity.ARRANGE) p.act(Activity.NONE)
        p.navigation.stop()
        p.routine.interrupt()
    }

    companion object {
        /** The longest anyone keeps the table, and ticks out of every player's sight before they leave it to the clock. */
        const val MOST = 12000
        const val UNSEEN = 400

        /** The ticks of SET_UP a block of the trestle goes up (or comes down) on. */
        val BUILD_AT = intArrayOf(12, 30, 48)

        /** Ticks between one bowl and the next set out (or gathered in). */
        const val BOWL_EVERY = 8

        /** The ticks of LADLE they speak on, and the ladle dips on. */
        const val SAY_AT = 2
        const val DIP_AT = 10

        /** The tick of PUT_DOWN the pot touches the table on, and of PICK_UP it lifts off on; ticks the trestle's carried off for at the end. */
        const val POT_AT = 10
        const val LIFT_AT = 9
        const val CARRY_AWAY = 160

        /** Ticks between calls to the shy. */
        const val CALL_EVERY = 500L

        /** The keeper's takes: those for calling people over, and those for handing a bowl. */
        val CALL = setOf(2)
        val HANDING = setOf(0, 1)

        /** How far the ladle and the bowls are heard (their sounds.json reach). */
        const val LADLE_REACH = 16f
        const val BOWL_REACH = 12f
    }
}
