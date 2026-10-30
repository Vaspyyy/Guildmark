package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.townsfolk.DeathSaves
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalkData
import io.github.vaspyyy.guildmark.village.live.build.VillageBuilds
import io.github.vaspyyy.guildmark.village.live.days.BellKeeper
import io.github.vaspyyy.guildmark.village.live.days.VillageLife
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import io.github.vaspyyy.guildmark.village.live.folk.VillageKeepsakes
import io.github.vaspyyy.guildmark.village.live.grow.GrowthData
import io.github.vaspyyy.guildmark.village.live.grow.VillageGrowth
import io.github.vaspyyy.guildmark.village.live.links.VillageLinks
import io.github.vaspyyy.guildmark.village.live.links.VillageWagons
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster
import io.github.vaspyyy.guildmark.village.live.talk.VillageCry
import io.github.vaspyyy.guildmark.village.live.trouble.Shelter
import io.github.vaspyyy.guildmark.village.live.trouble.Trouble
import io.github.vaspyyy.guildmark.village.live.trouble.TroubleData
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.npc.villager.Villager
import java.util.UUID

/**
 * What `village check` finds (living-world/villages/design.md 14.5, "Nothing softlocks"): one [Row] for each of the table's thirty
 * systems, in its order, each with what it found in this world now and the bound that keeps it from waiting for ever. A row is
 * [Status.OK] (nothing waiting), [Status.WAITING] (something under way, inside its bound), [Status.LATE] (something past its bound:
 * the thing to tell), [Status.OFF] (its setting is off, so nothing of it runs) or [Status.NOTE] (nothing kept to look at: the row
 * holds by how the system is made, and V11's harness or the game shows it).
 *
 * Everything is read from what is in memory and the stores already open; nothing is loaded or written that would not be otherwise
 * (a store whose setting is off is not opened, so a world with a part off gets no file for it from a check).
 */
object VillageCheck {
    enum class Status { OK, WAITING, LATE, OFF, NOTE }

    /** A row of 14.5: the [system], what came of the look ([status], [found]) and what bounds it. */
    class Row(val system: String, val status: Status, val found: String, val bound: String)

    /** The rows, in 14.5's order, for the overworld [level] now. */
    fun rows(level: ServerLevel): List<Row> {
        val out = ArrayList<Row>()
        val on = Villages.enabled()
        val all = if (on || hasRecords(level)) Villages.all(level) else listOf()
        val now = level.gameTime
        val town = VillageBoards.day(level)
        val hour = Markets.hourOf(level)
        val c = Conditions(hour)
        val awake = all.filter { it.state == Live.LIVE || it.state == Live.SLEEPING }
        fun row(system: String, status: Status, found: String, bound: String) = out.add(Row(system, status, found, bound))
        fun off(system: String, setting: String, bound: String) = row(system, Status.OFF, "$setting is off: nothing of it runs", bound)

        // Census
        if (!on) off("Census", "Living Villages", "recount in 6,000 ticks")
        else {
            val live = all.filter { it.state == Live.LIVE }
            val ages = live.map { if (it.counted < 0L || now < it.counted) Long.MAX_VALUE else now - it.counted }
            val late = ages.count { it > 2 * VillageTick.RECOUNT }
            val due = ages.count { it >= VillageTick.RECOUNT }
            val oldest = ages.filter { it != Long.MAX_VALUE }.maxOrNull()
            row(
                "Census", if (late > 0) Status.LATE else if (due > 0) Status.WAITING else Status.OK,
                "${live.size} live villages" + (oldest?.let { ", the oldest count ${ticks(it)} ago" } ?: "") + (if (due > 0) "; $due due a recount" else "") +
                    (if (late > 0) "; $late not counted in twice the time" else ""),
                "a live village is counted again every 6,000 ticks; a chunk not loaded is counted as far as it goes",
            )
        }

        // Registry
        val lost = all.filter { it.state == Live.LOST }
        val lostLate = lost.count { it.lostDay >= 0L && town - it.lostDay > VillageTick.LOST_DAYS + 1 }
        row(
            "Registry", if (lostLate > 0) Status.LATE else if (lost.isNotEmpty()) Status.WAITING else Status.OK,
            "${all.size} villages, ${lost.size} lost" + (lost.maxOfOrNull { town - it.lostDay }?.let { ", the longest $it days" } ?: "") +
                if (lostLate > 0) "; $lostLate kept past their 30 days" else "",
            "a lost village's folk are given back and its record dropped after 30 days; its bell put back finds it",
        )

        // Adoption
        if (!on || !VillageAdopt.folkOn()) off("Adoption", if (!on) "Living Villages" else "Village Folk", "1,000 ticks")
        else {
            val swaps = all.sumOf { VillageAdopt.swapping(it.key) }
            row(
                "Adoption", if (swaps > 0) Status.WAITING else Status.OK,
                if (swaps > 0) "$swaps villagers on their way to being bodies (each waits for its keepsake to be saved)" else "no villager waiting to be taken in",
                "a villager that can't be made a body is left as it is and looked at again later; a swap waits at most 2,400 ticks for its save",
            )
        }

        // Keepsakes, the detached villager, beds
        val keys = all.mapTo(HashSet()) { it.key }
        val folk = if (all.isEmpty()) listOf() else Residents.get(level).all().filter { it.alive && it.capital.asLong() in keys }
        if (on && VillageAdopt.folkOn()) {
            val bare = folk.count { it.craft.isNotEmpty() && !VillageKeepsakes.has(level, it.id) }
            row(
                "Keepsakes", if (bare > 0) Status.NOTE else Status.OK,
                if (bare > 0) "$bare residents with a trade have no keepsake: their old wares are hidden, and giving them back makes a fresh villager of their trade" else "every resident with a trade has their keepsake",
                "none needed",
            )
        } else {
            off("Keepsakes", if (!on) "Living Villages" else "Village Folk", "none needed")
        }
        row("The detached villager", Status.NOTE, "the kept villager is held in its keepsake and made only to trade (V2): no villager is parked in the world", "decided in V2")
        val homeless = folk.count { it.bed == null }
        row("Beds", if (homeless > 0) Status.NOTE else Status.OK, if (homeless > 0) "$homeless residents have no bed: they sleep at the barn or on the benches, and do not have children" else "every resident has a bed", "none needed")

        // Days
        row("Days", if (VillageLife.enabled()) Status.NOTE else Status.OFF, "a body with no path turns up where it is going with nobody within 40 blocks, or stands idle", "100 to 200 ticks")

        // Market, carts
        val markets = VillageMarkets.enabled()
        if (!markets) {
            off("Market", "Village Markets", "72 hours")
            off("Carts", "Village Markets", "9:00 the same day")
        } else {
            var behind = 0
            var far = 0
            var mostBehind = 0L
            var noCall = 0
            for (rec in awake) {
                val v = VillageMarkets.rec(level, rec.key) ?: continue
                val b = hour - v.entry.market.hour
                if (b > 0L) behind++
                if (b > 72L + 12L) far++
                mostBehind = maxOf(mostBehind, b)
                // By the market's own hour (a market catching up has not reached today's nine yet)
                val mc = Conditions(v.entry.market.hour)
                if (mc.clock in 10..23 && v.cart.decided != PedlarRoster.NONE && v.cart.decided < mc.day) noCall++
            }
            row(
                "Market", if (far > 0) Status.LATE else if (behind > 0) Status.WAITING else Status.OK,
                "${awake.size} awake villages; $behind behind the clock" + (if (mostBehind > 0) ", the furthest $mostBehind hours" else "") +
                    (if (far > 0) "; $far more than a catch-up and a half behind" else "") + "; a paused village's market stands still",
                "caught up at 12 hours a run, 72 at most",
            )
            row(
                "Carts", if (noCall > 0) Status.LATE else Status.OK,
                if (noCall > 0) "$noCall awake villages have no call for their day past its 10:00" else "every awake village's call for its day is made (or it is not yet 9:00 there)",
                "the call is a record made at 9:00 whether or not he is seen; a dead pedlar's slot is retired for a week",
            )
        }

        // Wagons, links, fortunes
        if (!VillageLinks.enabled()) {
            off("Wagons", "Village Wagons (or the markets, or the caravans)", "the wagon's own timetable")
            off("Links", "Village Wagons", "4 days")
        } else {
            val books = VillageWagons.store(level).books
            val ahead = books.values.count { it.lastIn != PedlarRoster.NONE && it.lastIn > hour }
            row(
                "Wagons", if (ahead > 0) Status.LATE else Status.OK,
                "${books.size} villages keep a wagon book" + if (ahead > 0) "; $ahead with a wagon in the future (the clock set back: put right at the next look)" else "",
                "a wagon waylaid, called off or flushed goes home at once; the village is never owed a wagon",
            )
            val linked = all.count { it.links.isNotEmpty() }
            row("Links", Status.NOTE, "$linked villages have a wagon road", "the pedlar takes up his round after 4 days with the road shut")
        }
        row("Fortunes", if (markets) Status.NOTE else Status.OFF, "a village with no market open notes nothing", "none needed")

        // Bellman, talk
        if (!VillageCry.enabled()) off("Bellman", "Village Bellman", "1 day")
        else {
            val none = awake.count { Residents.get(level).byCentre(BlockPos.of(it.key)).any { r -> r.alive } && BellKeeper.holder(level, BlockPos.of(it.key), c.day) == null }
            row(
                "Bellman", if (none > 0) Status.WAITING else Status.OK,
                if (none > 0) "$none awake villages with folk have nobody holding the bell today" else "every awake village with folk has its bell held today",
                "the next on the week's list holds it the next morning; with no resident, no cry",
            )
        }
        if (VillageCry.enabled() && markets) {
            val talk = TownTalkData.get(level)
            val paused = all.filter { it.state == Live.PAUSED }
            // The talk is stepped only up to the market's hour, which stands still while paused: a story begun past it is talk
            // that went on (the talk's own hour may be past it, moved up to the last day's hours with nothing stepped)
            val running = paused.count { rec ->
                val e = talk[rec.key] ?: return@count false
                val m = VillageMarkets.rec(level, rec.key) ?: return@count false
                e.rumours.values.any { it.at > m.entry.market.hour }
            }
            row(
                "Talk", if (running > 0) Status.LATE else Status.OK,
                "${paused.size} paused villages; nothing is told in them past their market's hour" + if (running > 0) " but in $running" else "",
                "none needed: the minds keep their acquaintances",
            )
        } else {
            off("Talk", "Village Bellman (or Village Markets)", "none needed")
        }

        // Lamps, festival
        row("Lamps", if (VillageLamps.working()) Status.NOTE else Status.OFF, "the clock lights and puts out each lamp at its phase, keeper or none", "by 19:00 and 07:30")
        row("Festival", Status.NOTE, "the troupe walks in for any part nobody fills", "the festival's day")

        // Trouble: shelter, watch, siege, raid, the dead, mending, golems
        val trouble = Trouble.on()
        if (!trouble) {
            for ((s, b) in listOf(
                "Shelter" to "6,000 ticks", "Watch" to "none needed", "Siege" to "as today", "Raid and captive" to "as today", "The dead" to "none needed",
                "Mending" to "3 days", "Golems" to "1 night",
            )) off(s, "Village Trouble", b)
        } else {
            val calls = all.mapNotNull { Shelter.of(it.key) }
            val overdue = calls.count { now > it.until + 600L }
            row(
                "Shelter", if (overdue > 0) Status.LATE else if (calls.isNotEmpty()) Status.WAITING else Status.OK,
                if (calls.isEmpty()) "no village is sheltering" else "${calls.size} sheltering, the longest ${ticks(calls.maxOf { now - it.at })}" + if (overdue > 0) "; $overdue past their 6,000 ticks" else "",
                "the call ends 10 seconds after the bell's ground is clear of monsters, or at 6,000 ticks",
            )
            row("Watch", Status.NOTE, "with nobody raised, the player holds the village as today", "none needed")
            row("Siege", Status.NOTE, "the siege's own timers", "as today")
            val kept = TroubleData.peek(level)?.villages ?: emptyMap()
            val taken = kept.values.sumOf { it.taken.size }
            row("Raid and captive", if (taken > 0) Status.WAITING else Status.OK, if (taken > 0) "$taken residents held by raiders" else "nobody held by raiders", "as today: the freed walk home, or are let go where they stand")
            val waiting = kept.values.sumOf { it.waiting.size }
            row("The dead", if (waiting > 0) Status.WAITING else Status.OK, if (waiting > 0) "$waiting of the dead wait for their candle (laid at the next dawn it can be)" else "no candle waiting", "none needed")
            val mends = kept.filter { it.value.damage.isNotEmpty() }
            val mendLate = mends.count { it.value.mendFrom != TroubleData.NONE && town - it.value.mendFrom > 3L + 1L }
            row(
                "Mending", if (mendLate > 0) Status.LATE else if (mends.isNotEmpty()) Status.WAITING else Status.OK,
                if (mends.isEmpty()) "nothing to mend" else "${mends.size} villages have blocks to mend (${mends.values.sumOf { it.damage.size }} in all)" + if (mendLate > 0) "; $mendLate past their three days" else "",
                "the blocks are put back out of sight after three days",
            )
            row("Golems", Status.NOTE, "no open ground for a golem: the next night", "1 night")
        }

        // Building
        if (!VillageBuilds.enabled()) off("Building", "Village Builds", "3x days")
        else {
            val sites = VillageBuilds.all(level)
            row(
                "Building", if (sites.isNotEmpty()) Status.WAITING else Status.OK,
                if (sites.isEmpty()) "no site going up" else "${sites.size} sites going up, the oldest begun ${ticks(sites.maxOf { now - it.born })} ago" + (sites.count { it.rushing }.takeIf { it > 0 }?.let { "; $it being finished" } ?: ""),
                "a blocked step is left out after a day; the site is finished out of sight at three times its days with nobody within 48 blocks",
            )
        }

        // Growth, newcomers
        if (!VillageGrowth.enabled()) {
            off("Growth", "Village Growth", "7 days")
            off("Newcomers", "Village Growth", "none needed")
        } else {
            val today = VillageGrowth.closing(level.overworldClockTime)
            val growth = GrowthData.get(level).villages.values
            val empty = growth.filter { it.state.zero >= 0L }
            val emptyLate = empty.count { today - maxOf(it.state.zero, it.state.arrived) > 7L + 1L }
            row(
                "Growth", if (emptyLate > 0) Status.LATE else if (empty.isNotEmpty()) Status.WAITING else Status.OK,
                if (empty.isEmpty()) "no village is empty" else "${empty.size} villages have nobody" + if (emptyLate > 0) "; $emptyLate with no newcomer in a week" else "",
                "a village at zero takes a newcomer every 4 days, never fewer than one a week",
            )
            row("Newcomers", Status.NOTE, "with no free home none come (the empty-village rule above)", "none needed")
        }

        // The master switch
        if (on) row("The master switch", Status.OK, "Living Villages is on", "one night")
        else {
            val bodies = bodies(level, keys)
            row(
                "The master switch", if (bodies > 0) Status.WAITING else Status.OK,
                if (bodies > 0) "Living Villages is off: $bodies of the villages' bodies still about, to be given back at night" else "Living Villages is off: none of the villages' bodies is about",
                "sites finished out of sight, shelters ended, the pedlar gone; the folk given back at night, a few at a time",
            )
        }

        // Exempt, confined, two instances
        row("Exempt villagers", Status.NOTE, "a village whose villagers are all exempt stays vanilla", "none needed")
        row("Confined villagers", Status.NOTE, "an open hall called confined stays vanilla; a confined one taken in goes back with a name tag, the tag or `village release`", "none needed")
        val twice = twice(level, folk.mapTo(HashSet()) { it.id })
        row("Two instances of a body", if (twice > 0) Status.LATE else Status.OK, if (twice > 0) "$twice residents are in the world twice" else "no resident is in the world twice", "on load: onJoin keeps the villager")
        return out
    }

    /** What the deaths' saves have done (m7 leftovers item 3, here for `village check`). */
    fun deaths(): String {
        val due = DeathSaves.pending()
        return "${DeathSaves.saves} saves after a death since the server started" + (if (DeathSaves.saves > 0) ", the last ${String.format(java.util.Locale.ROOT, "%.1f", DeathSaves.lastNanos / 1e6)} ms on the server thread" else "") +
            if (due != null) "; one due at tick $due" else ""
    }

    /** Whether the registry has been opened (a world with Living Villages off and no record yet gets no file from a check). */
    private fun hasRecords(level: ServerLevel): Boolean = Villages.enabled() || VillageData.peek(level)?.records?.isNotEmpty() == true

    /** The bodies about now of the residents of the villages [keys]. */
    private fun bodies(level: ServerLevel, keys: Set<Long>): Int {
        if (keys.isEmpty()) return 0
        val residents = Residents.get(level)
        return level.allEntities.count { e -> e is Townsperson && !e.isRemoved && residents.of(e)?.capital?.asLong()?.let { it in keys } == true }
    }

    /**
     * The residents among [ids] who are in the world twice: two bodies, or a body and the villager they were taken in from (the
     * villager's id kept in their keepsake), which a game killed between a save and a swap could leave.
     */
    private fun twice(level: ServerLevel, ids: Set<Int>): Int {
        if (ids.isEmpty()) return 0
        val residents = Residents.get(level)
        val bodies = HashMap<Int, Int>()
        val villagers = HashSet<UUID>()
        for (e in level.allEntities) {
            if (e.isRemoved) continue
            if (e is Townsperson) {
                val r = residents.of(e) ?: continue
                if (r.id in ids) bodies.merge(r.id, 1, Int::plus)
            } else if (e is Villager) {
                villagers += e.uuid
            }
        }
        var n = bodies.values.count { it > 1 }
        if (villagers.isNotEmpty()) for (id in bodies.keys) {
            val from = VillageKeepsakes.get(level, id)?.getStringOr(VillageKeepsakes.FROM, "") ?: continue
            if (from.isNotEmpty() && runCatching { UUID.fromString(from) }.getOrNull() in villagers) n++
        }
        return n
    }

    private fun ticks(t: Long): String = when {
        t < 1200L -> "${t / 20L} s"
        t < 72000L -> "${t / 1200L} min"
        else -> String.format(java.util.Locale.ROOT, "%.1f days", t / 24000.0)
    }
}
