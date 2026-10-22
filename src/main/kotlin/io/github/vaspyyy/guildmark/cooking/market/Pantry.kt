package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.ServedPayload
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Random
import java.util.UUID

/**
 * The pantry's serving at the meal hours (living-world/cook/design.md 5.3, 7.4): what players sold at the inn's and the
 * bakery's back doors and gave to the alms table is waiting in the doors' lots, and at 7, 12 and 18 the town eats from them,
 * oldest first, up to a third of the day's cap of each door in place of plain meals. The portions served are what
 * [Kitchens.step] gives the market as `served`, so each portion is counted once, here. Along with the serving go the two
 * things the town sees of it: a [ServedPayload] to the players near the capital, so the diners can be shown eating it, and a
 * sample of up to four residents whose day has them at table, who relish the cook's work (a record on the resident, so it
 * works with the capital unloaded).
 */
object Pantry {
    /** The doors that serve: the inn, the bakery and the alms table. A cookshop sells from its own larder (6.4). */
    val SERVED: List<Door> = listOf(Door.INN, Door.BAKERY, Door.ALMS)

    /** The clock hours the cookshops sell their hour at (6.4); what they sold waits for the next meal hour. */
    val SHOP_CLOCKS: Set<Int> = setOf(8, 12, 16)

    /** The most diners a meal hour's sample picks, and the most residents looked at to find them. */
    private const val DINERS = 4
    private const val LOOKS = 12

    /** How far from a capital's centre a player is told what its inn served (7.4). */
    private const val RANGE = 128.0

    /** Hours after a meal that a player who comes into range is still told of it. */
    private const val STALE = 2L

    /** What a resident is doing when their day has them at table: breakfast, lunch, supper and the tavern of an evening. */
    private val AT_TABLE = setOf(Doing.BREAKFAST, Doing.LUNCH, Doing.SUPPER, Doing.TAVERN)

    /** Which meal clock hour [clock] is: 0 at 7:00, 1 at noon, 2 at 18:00; -1 for no meal. */
    fun meal(clock: Int): Int = when (clock) {
        7 -> 0
        12 -> 1
        18 -> 2
        else -> -1
    }

    /** The window of a meal for the clients: three for each town day, the meal's number added. */
    fun window(day: Long, clock: Int): Int = (day * 3L + meal(clock).coerceAtLeast(0)).toInt()

    /**
     * What an inn and a bakery served at a meal hour, for the clients: the [window] of the meal, [counts] of each kind in
     * [io.github.vaspyyy.guildmark.cooking.DishKind] order and the [best] tier of each (a [Tier] ordinal; -1 for none).
     */
    class Served(val capital: Long, val window: Int, val hour: Long, val counts: IntArray, val best: ByteArray)

    /** The last meal each capital served, in memory only (a restart forgets it; the next meal hour sends a new one). */
    private val last = HashMap<Long, Served>()

    /** The last three meal hours' counts of each capital, which [Rec.served] sums. */
    private val recent = HashMap<Long, ArrayDeque<IntArray>>()

    /** The inn's box of each capital, once known (it does not move). */
    private val boxes = HashMap<Long, BoundingBox>()

    /** The window each player was last told of, by capital: they are told again when they come back into range. */
    private val told = HashMap<UUID, HashMap<Long, Int>>()

    /** Readouts for `cook perf`: meal hours served, portions served, and the longest a serving took (nanoseconds). */
    var meals = 0
        private set
    var portions = 0L
        private set
    var slowest = 0L
        private set

    init {
        Cooking.onForget {
            last.clear()
            recent.clear()
            boxes.clear()
            told.clear()
            meals = 0
            portions = 0L
            slowest = 0L
        }
    }

    /**
     * Serves the meal at clock hour [clock] of the town's [day] (market hour [hour]) from [rec]'s lots: each door's turn, oldest
     * lot first, up to a third of its day's cap (the alms table's Gruel after its dishes). With the back doors off ([doors]
     * false) only the alms table serves, since nothing is sold at the others. Gives the portions served, which is what the
     * town does not eat as plain meals.
     */
    fun serve(level: ServerLevel, rec: Rec, entry: Markets.Entry, hour: Long, day: Long, clock: Int, doors: Boolean = true): Int {
        val started = System.nanoTime()
        val people = entry.town.people
        val counts = IntArray(Rec.KINDS)
        val best = ByteArray(Rec.KINDS) { (-1).toByte() }
        val eaten = ArrayList<Lot>()
        var served = 0
        for (door in SERVED) {
            if (!doors && door != Door.ALMS) continue
            var room = Doors.serving(door, people)
            val each = rec.lots[door.ordinal].iterator()
            while (room > 0 && each.hasNext()) {
                val lot = each.next()
                val n = minOf(lot.portions, room)
                if (n > 0) {
                    lot.portions -= n
                    room -= n
                    served += n
                    eaten.add(Lot(lot.kind, lot.tier, lot.cook, lot.cookName, n, lot.day))
                    if (door != Door.ALMS) {
                        counts[lot.kind.ordinal] += n
                        if (lot.tier.ordinal > best[lot.kind.ordinal]) best[lot.kind.ordinal] = lot.tier.ordinal.toByte()
                    }
                }
                if (lot.portions <= 0) each.remove()
            }
            // Gruel has no kind, so no lot: the table's pot gives it out after the dishes
            if (door == Door.ALMS && room > 0 && rec.gruel > 0) {
                val g = minOf(rec.gruel, room)
                rec.gruel -= g
                served += g
            }
        }
        remember(rec, counts)
        if (served > 0) {
            meals++
            portions += served
        }
        if (counts.any { it > 0 }) {
            val meal = Served(rec.key, window(day, clock), hour, counts, best)
            last[rec.key] = meal
            // An hour worked out late, in catching up, is shown to no one: it was a while ago
            if (Markets.hourOf(level) - hour <= STALE) announce(level, meal)
        }
        if (eaten.isNotEmpty()) relish(level, rec, eaten, hour, day)
        val took = System.nanoTime() - started
        if (took > slowest) slowest = took
        return served
    }

    /** Keeps [counts] among the last three meals' of [rec], and sets [Rec.served] to their sum. */
    private fun remember(rec: Rec, counts: IntArray) {
        val three = recent.getOrPut(rec.key) { ArrayDeque() }
        three.addLast(counts.copyOf())
        while (three.size > 3) three.removeFirst()
        for (k in rec.served.indices) rec.served[k] = three.sumOf { it[k] }
    }

    /**
     * Up to four residents whose day has them at the tavern or at table in this hour relish the cook's work: for each, a lot
     * is drawn from what was eaten (the more portions, the likelier) and, if it is Sound or better and has a cook,
     * [TownTalk.relished] is told of it. The residents are drawn by a seed of the capital, the day and the hour, so the same
     * hours give the same diners; their days are worked out from their records, so no one need be about.
     */
    private fun relish(level: ServerLevel, rec: Rec, eaten: List<Lot>, hour: Long, day: Long) {
        val cooked = eaten.filter { it.cook != null }
        if (cooked.none { it.tier.ordinal >= Tier.SOUND.ordinal }) return
        val folk = Residents.get(level).of(BlockPos.of(rec.key)).filter { it.alive }
        if (folk.isEmpty()) return
        val rng = Random(rec.key * 31L + day * 7L + hour)
        val offset = Days.offsetOf(hour * 1000L + 500L)
        val weight = cooked.sumOf { it.portions }.coerceAtLeast(1)
        val picked = HashSet<Int>()
        var tries = 0
        while (picked.size < DINERS && tries < LOOKS) {
            tries++
            val r = folk[rng.nextInt(folk.size)]
            if (r.id in picked) continue
            if (Days.plan(r, day, false, false).at(offset).doing !in AT_TABLE) continue
            picked.add(r.id)
            var draw = rng.nextInt(weight)
            val lot = cooked.firstOrNull { draw -= it.portions; draw < 0 } ?: cooked.last()
            if (lot.tier.ordinal >= Tier.SOUND.ordinal) TownTalk.relished(level, r, lot.cook!!, lot.tier)
        }
    }

    // ---- The clients ----

    /** The box of the capital keyed [key]'s inn, if it is known now (its places are worked out off the main thread the first time, and only from loaded land). */
    fun innBox(level: ServerLevel, key: Long): BoundingBox? {
        boxes[key]?.let { return it }
        val piece = Furnish.capitalAt(level, BlockPos.of(key)) ?: return null
        val places = CityPlaces.of(level, piece) ?: return null
        val inn = places.lodgings.values.firstOrNull { it.kind in CityPlaces.TAVERNS } ?: return null
        boxes[key] = inn.box
        return inn.box
    }

    /** Tells every player within range of [meal]'s capital who has not been told of it yet. */
    private fun announce(level: ServerLevel, meal: Served) {
        val players = level.players()
        if (players.isEmpty()) return
        val centre = BlockPos.of(meal.capital)
        if (players.none { near(it, centre) }) return
        val box = innBox(level, meal.capital) ?: return
        for (player in players) {
            if (!near(player, centre)) continue
            val seen = told.getOrPut(player.uuid) { HashMap() }
            if (seen[meal.capital] == meal.window) continue
            seen[meal.capital] = meal.window
            PacketDistributor.sendToPlayer(player, ServedPayload(meal.capital, meal.window, box, meal.counts, meal.best))
        }
    }

    private fun near(player: ServerPlayer, centre: BlockPos): Boolean {
        val dx = player.x - (centre.x + 0.5)
        val dz = player.z - (centre.z + 0.5)
        return dx * dx + dz * dz <= RANGE * RANGE
    }

    /**
     * Every few seconds: a player who has come into range of a capital whose inn served lately is told of it (and told again
     * if they leave and come back), and what is too old to show is let go.
     */
    fun tick(server: MinecraftServer) {
        if (last.isEmpty()) return
        val level = server.overworld()
        val now = Markets.hourOf(level)
        var gone: ArrayList<Long>? = null
        for (meal in last.values) {
            if (now - meal.hour > STALE || now < meal.hour) {
                (gone ?: ArrayList<Long>().also { gone = it }).add(meal.capital)
                continue
            }
            val centre = BlockPos.of(meal.capital)
            for (player in level.players()) {
                if (!near(player, centre)) told[player.uuid]?.remove(meal.capital)
            }
            announce(level, meal)
        }
        gone?.let { keys ->
            for (key in keys) {
                last.remove(key)
                for (seen in told.values) seen.remove(key)
            }
        }
    }

    /** A player has left: nothing more is kept of what they were told. */
    fun left(player: UUID) {
        told.remove(player)
    }

    /** How many capitals' last meals are still being shown (for the readout). */
    fun showing(): Int = last.size
}
