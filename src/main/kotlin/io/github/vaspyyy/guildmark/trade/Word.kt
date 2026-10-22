package io.github.vaspyyy.guildmark.trade

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.Suppliers
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import kotlin.math.hypot
import kotlin.math.max

/**
 * What the world keeps of the Word (living-world/trader/design.md, Part 1), as the saved data `guildmark:trade_word`: for each capital
 * a ring of its twelve latest snapshots, oldest first (three days of them at four a day), and the reports players have filed at the
 * boards. It is made the first time a snapshot is taken; looking at it makes nothing ([peek]).
 */
class TradeWord(
    internal val rings: MutableMap<Long, Ring> = HashMap(),
    internal val reports: MutableList<Report> = ArrayList(),
) : SavedData() {
    /** One capital's snapshots, oldest first, and its name as it was last found (the Word never has to go looking for it twice). */
    class Ring(val capital: Long) {
        var name = ""
        val sights = ArrayList<Sight>()
    }

    /**
     * A sight [by] a player ([byName]) took of the capital [about] and filed at the board of the capital [at] at [filed] (the overworld's
     * clock): it is that board's row for [about] for as long as it is fresher than the row the ring would give.
     */
    class Report(val at: Long, val about: Long, val sight: Sight, val by: UUID, val byName: String, val filed: Long)

    fun ring(capital: Long): Ring = rings.getOrPut(capital) { Ring(capital) }

    fun changed() = setDirty()

    companion object {
        private fun ringTag(ring: Ring): CompoundTag {
            val tag = CompoundTag()
            tag.putLong("capital", ring.capital)
            if (ring.name.isNotEmpty()) tag.putString("name", ring.name)
            val list = ListTag()
            for (sight in ring.sights) list.add(sight.toTag())
            tag.put("sights", list)
            return tag
        }

        private fun ringOf(tag: CompoundTag): Ring {
            val ring = Ring(tag.getLongOr("capital", 0L))
            ring.name = tag.getStringOr("name", "")
            val list = tag.getListOrEmpty("sights")
            // A sight kept for another list of goods is let go: it is only news
            for (i in 0 until list.size) Sight.fromTag(list.getCompoundOrEmpty(i))?.let { ring.sights.add(it) }
            return ring
        }

        private fun reportTag(report: Report): CompoundTag {
            val tag = CompoundTag()
            tag.putLong("at", report.at)
            tag.putLong("about", report.about)
            tag.putString("by", report.by.toString())
            tag.putString("name", report.byName)
            tag.putLong("filed", report.filed)
            tag.put("sight", report.sight.toTag())
            return tag
        }

        private fun reportOf(tag: CompoundTag): Report? {
            val by = try {
                UUID.fromString(tag.getStringOr("by", ""))
            } catch (e: IllegalArgumentException) {
                return null
            }
            val sight = Sight.fromTag(tag.getCompoundOrEmpty("sight")) ?: return null
            return Report(tag.getLongOr("at", 0L), tag.getLongOr("about", 0L), sight, by, tag.getStringOr("name", ""), tag.getLongOr("filed", 0L))
        }

        private val CODEC: Codec<TradeWord> = RecordCodecBuilder.create { i ->
            i.group(
                CompoundTag.CODEC.listOf().optionalFieldOf("rings", listOf()).forGetter { w -> w.rings.values.filter { it.sights.isNotEmpty() }.map(::ringTag) },
                CompoundTag.CODEC.listOf().optionalFieldOf("reports", listOf()).forGetter { w -> w.reports.map(::reportTag) },
            ).apply(i) { rings, reports ->
                val word = TradeWord()
                for (tag in rings) ringOf(tag).let { word.rings[it.capital] = it }
                for (tag in reports) reportOf(tag)?.let { word.reports.add(it) }
                word
            }
        }

        internal val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "trade_word"), Supplier { TradeWord() }, CODEC)

        /** The Word's record, made if there is none yet (a snapshot is about to be written to it). */
        fun get(level: ServerLevel): TradeWord = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** The Word's record if one was ever written, and nothing made if not: for a look that only reads, so that an empty `trade_word.dat` is never saved by looking. */
        fun peek(level: ServerLevel): TradeWord? = level.server.overworld().dataStorage.get(TYPE)
    }
}

/**
 * The Word (living-world/trader/design.md, Part 1): what each capital knew of itself four times a day, and how late the news of it
 * reaches another. [register] has the market hours watched for the snapshots; [view] is what a board shows a capital; [delay] is how old
 * news must be before a board has it. A trader's own sights, their Tally, are kept apart ([Tally]); the Word is the world's.
 *
 * With `tradeWord` off ([TradeConfig.word]) nothing here runs: no snapshot is taken or written, no row is given, and the records stay as
 * they were for when it is on again.
 */
object Word {
    /** Capitals within this many blocks of a board are on its slate, nearest first, and the slate holds this many rows. */
    const val REACH = 1500.0
    const val ROWS = 8

    /** A snapshot is taken at every sixth market hour (clock midnight, six, noon and eighteen), and twelve are kept. */
    const val SNAP = 6L
    const val KEPT = 12

    /** The game ticks in an hour of the market's clock. */
    const val HOUR = 1000L

    /** Whoever hears the Word change, subscribed once at setup with [listen]: the Haul Board (part C) re-chalks its rows, the rumours look for a glut. */
    interface Listener {
        /** The rows [view] would give the board of the capital whose heart is [capital] are not the rows it gave last (a newer snapshot has reached their delay, or a report was filed there). */
        fun updated(level: ServerLevel, capital: BlockPos) {}

        /** [entry]'s market was snapshotted as [sight]. */
        fun taken(level: ServerLevel, entry: Markets.Entry, sight: Sight) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun listen(listener: Listener) {
        listeners.add(listener)
    }

    /**
     * One row of a board's slate: what it knows of the capital [capital] (named [name], [distance] blocks off), which is [sight] as the town was
     * at [tick] on the overworld's clock, [age] hours before the moment the row was read. A [seeded] row is a new Word's first, made
     * from a capital whose ring has no snapshot yet as old as the delay (so a board is useful at once) and said to be exactly as old as
     * the delay: the state is the freshest there is. [by] names the player whose report this row is, if one is.
     */
    class Row(val capital: BlockPos, val name: String, val distance: Int, val sight: Sight, val tick: Long, val age: Double, val seeded: Boolean, val by: String?)

    /** A fixed age, in hours, every row is made to read (`trade word age`, for testing); null for the delay that distance makes. Not kept: a restart ends it. */
    @Volatile
    var fixed: Double? = null

    // ---- The delay and the view ----

    /**
     * How old the news of a town [distance] blocks off must be before a board has it, in hours: twelve and a little for every block (the config's
     * tradeWordBase and tradeWordPerBlock), less what [player]'s standing gives them toward fresher news. The perk is taken off in one line.
     */
    fun delay(distance: Double, player: ServerPlayer? = null): Double {
        fixed?.let { return it }
        val hours = TradeConfig.wordBase() + TradeConfig.wordPerBlock() * distance
        return max(0.0, hours - (player?.let { Rungs.perk(it, Perk.WORD_FRESH) } ?: 0.0))
    }

    /**
     * The slate of the board at the capital whose heart is [viewer], as [player] reads it (or as it is chalked for nobody, with [player] null):
     * a row for each capital within [REACH] blocks that has a snapshot, nearest first, the eight nearest at most. A row is the latest snapshot
     * at least [delay] old; where there is none that old yet it is the oldest there is, said to be as old as the delay allows; and a report filed
     * at the board counts in place of either while it is fresher. Nothing at all when the Word is off.
     */
    fun view(level: ServerLevel, viewer: BlockPos, player: ServerPlayer? = null): List<Row> {
        if (!TradeConfig.word()) return listOf()
        val data = TradeWord.peek(level) ?: return listOf()
        val now = level.overworldClockTime
        val rows = ArrayList<Row>()
        for (ring in data.rings.values) {
            if (ring.capital == viewer.asLong() || ring.sights.isEmpty()) continue
            val there = BlockPos.of(ring.capital)
            val distance = hypot((there.x - viewer.x).toDouble(), (there.z - viewer.z).toDouble())
            if (distance > REACH) continue
            rows.add(row(level, data, viewer, ring, distance, player, now))
        }
        return rows.sortedBy { it.distance }.take(ROWS)
    }

    /** The row of the board at [viewer] for the capital whose heart is [about], if the board has one. */
    fun row(level: ServerLevel, viewer: BlockPos, about: Long, player: ServerPlayer? = null): Row? = view(level, viewer, player).firstOrNull { it.capital.asLong() == about }

    private fun row(level: ServerLevel, data: TradeWord, viewer: BlockPos, ring: TradeWord.Ring, distance: Double, player: ServerPlayer?, now: Long): Row {
        val cutoff = now - (delay(distance, player) * HOUR).toLong()
        val pick = ring.sights.lastOrNull { it.tick <= cutoff }
        var sight = pick ?: ring.sights.first()
        var tick = if (pick != null) pick.tick else cutoff
        var seeded = pick == null
        var by: String? = null
        val report = data.reports.lastOrNull { it.at == viewer.asLong() && it.about == ring.capital && it.sight.tick > tick }
        if (report != null) {
            sight = report.sight
            tick = report.sight.tick
            seeded = false
            by = report.byName
        }
        val age = max(0L, now - tick) / HOUR.toDouble()
        return Row(BlockPos.of(ring.capital), ring.name, distance.toInt(), sight, tick, age, seeded, by)
    }

    /** The name of the capital whose heart is [capital], as the Word last noted it ("" while nobody has named it). */
    fun nameOf(level: ServerLevel, capital: Long): String = TradeWord.peek(level)?.rings?.get(capital)?.name ?: ""

    /**
     * The name of the capital whose heart is [capital], looked for where it is kept: the roads' note of it, else the orders'. Only if [ground] says
     * so does it go as far as the ground, which may load a chunk: a trader standing in the town may ask that, the Word's own passes never do. It
     * is asked once for a capital and the answer kept ([nameOf], [named]), never every tick. "" if it is not to be found.
     */
    fun found(level: ServerLevel, capital: Long, ground: Boolean = false): String {
        if (Caravans.enabled()) CaravanData.get(level).capitalOrNull(capital)?.name?.takeIf { it.isNotEmpty() }?.let { return it }
        val at = BlockPos.of(capital)
        Orders.get(level)[at]?.name?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (ground) Suppliers.place(level, at) else ""
    }

    /** Notes [name] as the name of the capital whose heart is [capital], if the Word keeps a ring for it that has none yet. */
    fun named(level: ServerLevel, capital: Long, name: String) {
        if (name.isEmpty()) return
        val data = TradeWord.peek(level) ?: return
        val ring = data.rings[capital] ?: return
        if (ring.name.isNotEmpty()) return
        ring.name = name
        data.changed()
    }

    // ---- Snapshots ----

    /**
     * Takes [entry]'s snapshot now, stamped with its market's hour, and files it in the capital's ring (a second in the same hour replaces
     * the first). Gives it.
     */
    fun snap(level: ServerLevel, data: TradeWord, entry: Markets.Entry): Sight {
        val tick = entry.market.hour * HOUR
        val sight = Sight.of(level, entry, tick, tickets(level, entry.capital))
        val ring = data.ring(entry.market.capital)
        if (ring.name.isEmpty()) ring.name = found(level, entry.market.capital)
        if (ring.sights.lastOrNull()?.tick == tick) ring.sights.removeAt(ring.sights.size - 1)
        ring.sights.add(sight)
        while (ring.sights.size > KEPT) ring.sights.removeAt(0)
        // A report older than the ring itself could never be a fresher row than any, and is let go
        val stale = level.overworldClockTime - KEPT * SNAP * HOUR
        data.reports.removeAll { it.sight.tick < stale }
        data.changed()
        for (l in listeners) l.taken(level, entry, sight)
        return sight
    }

    /** The haul tickets the capital whose heart is [capital] has out, as a sight keeps them: what is still to be taken of each, and what that pays. */
    fun tickets(level: ServerLevel, capital: BlockPos): List<Sight.Ticket> = TallyLinks.open(level, capital)

    /** Snapshots every opened capital now (`trade word snap`). Gives how many. */
    fun snapAll(level: ServerLevel): Int {
        val markets = Markets.get(level).all().filter { it.market.hour != Long.MIN_VALUE }
        if (markets.isEmpty()) return 0
        val data = TradeWord.get(level)
        for (entry in markets) snap(level, data, entry)
        return markets.size
    }

    /**
     * Fills every ring from the markets as they stand (`trade word seed`): twelve snapshots a quarter of a day apart, the last of them now,
     * all the same state, so that a board has a row at any delay up to three days. Gives how many capitals.
     */
    fun seed(level: ServerLevel): Int {
        val markets = Markets.get(level).all().filter { it.market.hour != Long.MIN_VALUE }
        if (markets.isEmpty()) return 0
        val data = TradeWord.get(level)
        for (entry in markets) {
            val ring = data.ring(entry.market.capital)
            if (ring.name.isEmpty()) ring.name = found(level, entry.market.capital)
            val now = Sight.of(level, entry, entry.market.hour * HOUR, tickets(level, entry.capital))
            ring.sights.clear()
            for (k in KEPT - 1 downTo 0) ring.sights.add(now.stamped(now.tick - k * SNAP * HOUR))
            for (l in listeners) l.taken(level, entry, now)
        }
        data.changed()
        return markets.size
    }

    /** Drops every snapshot, report and note of the Word (`trade word clear`). Gives how many snapshots went. */
    fun clear(level: ServerLevel): Int {
        val data = TradeWord.peek(level) ?: return 0
        val count = data.rings.values.sumOf { it.sights.size }
        data.rings.clear()
        data.reports.clear()
        data.changed()
        shown.clear()
        return count
    }

    // ---- The hours ----

    /**
     * Registers part B: the Word's watch on the markets' hours (the snapshots), its commands, and (each after its own comment) the Tally and the
     * rumours that tell the Word in the street. The talk that tells them ([io.github.vaspyyy.guildmark.townsfolk.talk.sources.TradeTopics]) is one of
     * the talk sources, `TalkSources.ALL`'s.
     */
    fun register(modBus: IEventBus) {
        WordCommand.init()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> reset() }
        Tally.register(modBus)
        TradeRumours.register()
    }

    /** Server ticks between looks at the markets, and between namings of rings. */
    private const val LOOK = 20
    private const val STAND = 100

    /** The rows the board of each capital last had, to tell when they change (not kept): the hash of what [view] gave for nobody. */
    private val shown = HashMap<Long, Int>()

    private fun tick(server: MinecraftServer) {
        // A beat after the markets' own run, which is at the start of each second
        if (server.tickCount % LOOK != LOOK / 2) return
        if (!TradeConfig.life() || !TradeConfig.word()) return
        val level = server.overworld()
        val markets = Markets.get(level)
        if (markets.all().none { it.market.hour != Long.MIN_VALUE }) return
        keep(level, markets)
        if (listeners.isNotEmpty()) announce(level, markets)
        if (server.tickCount % STAND == LOOK / 2) name(level)
    }

    /** Gives a name to each ring that has none yet, once its capital has one to be found. */
    private fun name(level: ServerLevel) {
        val data = TradeWord.peek(level) ?: return
        for (ring in data.rings.values) {
            if (ring.name.isNotEmpty()) continue
            ring.name = found(level, ring.capital)
            if (ring.name.isNotEmpty()) data.changed()
        }
    }

    /** Takes the snapshot of each market whose hour has reached the next one due: the sixth hour after the last, or the first for a ring with none. */
    private fun keep(level: ServerLevel, markets: Markets) {
        var data: TradeWord? = null
        for (entry in markets.all()) {
            val hour = entry.market.hour
            if (hour == Long.MIN_VALUE) continue
            val ring = TradeWord.peek(level)?.rings?.get(entry.market.capital)
            // Time set back: nothing in the ring may be from after the market's own hour
            if (ring != null && ring.sights.any { it.tick > hour * HOUR + HOUR - 1 }) {
                ring.sights.removeAll { it.tick > hour * HOUR + HOUR - 1 }
                TradeWord.get(level).changed()
            }
            val last = ring?.sights?.lastOrNull()?.tick ?: Long.MIN_VALUE
            if (last >= Math.floorDiv(hour, SNAP) * SNAP * HOUR) continue
            if (data == null) data = TradeWord.get(level)
            snap(level, data, entry)
        }
    }

    /** Tells the listeners which boards' rows are not what they were. */
    private fun announce(level: ServerLevel, markets: Markets) {
        for (entry in markets.all()) {
            val key = entry.market.capital
            val rows = view(level, entry.capital)
            var h = 1
            for (r in rows) h = 31 * h + (r.capital.asLong() xor r.tick).hashCode() + (r.by?.hashCode() ?: 0)
            val was = shown.put(key, h)
            if (was != null && was != h) for (l in listeners) l.updated(level, entry.capital)
        }
    }

    /** Forgets what is only held in memory: the rows last told (the server stopped). */
    fun reset() {
        shown.clear()
    }
}

/** The trade books if any were ever written, and nothing made if not: for a look that only reads (see [TradeBooks.get], which makes the record and has it saved at once, even empty). */
internal fun peekBooks(level: ServerLevel): TradeBooks? = level.server.overworld().dataStorage.get(TradeBooks.TYPE)
