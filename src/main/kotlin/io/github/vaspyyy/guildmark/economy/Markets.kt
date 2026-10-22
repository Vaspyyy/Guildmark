package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.plan.FarmKind
import io.github.vaspyyy.guildmark.city.plan.FarmPropKind
import io.github.vaspyyy.guildmark.city.plan.Folk
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.Trade
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Residents
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.function.Supplier
import kotlin.random.Random

/**
 * Every capital's market (see [Market]) and the town it serves (see [Town]: its people by trade as they were when a
 * player last looked in), kept with the overworld. A capital's market opens the first time a player sets foot on
 * its ground; from then on it's worked out an hour of the overworld's clock at a time, whether anyone's there or
 * not, and catches up after a night's sleep or a /time add (the last [CATCH_UP] hours of it at most).
 */
class Markets(private val entries: MutableMap<Long, Entry> = HashMap()) : SavedData() {
    // What each market's goods cost to bring in, from where the others stand (not kept; see Carriage)
    init {
        Carriage.reckon(entries.values)
    }

    /** A capital's market and the town it serves; [looked] is the game time its people were last counted. */
    class Entry(val market: Market, var town: Town, var looked: Long = Long.MIN_VALUE / 2) {
        val capital: BlockPos get() = BlockPos.of(market.capital)

        /** The world as the market last felt it: its weather, season and any feast (not saved; see [felt]). */
        var felt: Conditions? = null
    }

    operator fun get(capital: BlockPos): Entry? = entries[capital.asLong()]

    fun all(): Collection<Entry> = entries.values

    fun changed() = setDirty()

    /** Opens [places]' capital's market afresh, as a town keeps one, from this hour on. */
    fun open(level: ServerLevel, places: CityPlaces): Entry {
        val town = townOf(level, places)
        val market = Market(places.capital.center.asLong())
        Economy.open(market, town)
        market.hour = hourOf(level)
        val entry = Entry(market, town, level.gameTime)
        entries[market.capital] = entry
        Carriage.reckon(entries.values)
        setDirty()
        return entry
    }

    companion object {
        /** Server ticks between runs, and between looks at where the players are. */
        private const val TICK = 20
        private const val LOOK = 100

        /** Game ticks between counts of a capital's people while someone's there. */
        private const val RECOUNT = 1200L

        /** The most hours worked out to catch up, and the most worked out in one run for all markets together. */
        const val CATCH_UP = 72L
        private const val STEPS = 48

        private val ENTRY: Codec<Entry> = RecordCodecBuilder.create { i ->
            i.group(
                Market.CODEC.fieldOf("market").forGetter(Entry::market),
                Town.CODEC.fieldOf("town").forGetter(Entry::town),
            ).apply(i) { market, town -> Entry(market, town) }
        }

        private val CODEC: Codec<Markets> = RecordCodecBuilder.create { i ->
            i.group(
                ENTRY.listOf().optionalFieldOf("markets", listOf()).forGetter { it.entries.values.toList() },
            ).apply(i) { list -> Markets(list.associateBy { it.market.capital }.toMutableMap()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "markets"), Supplier { Markets() }, CODEC)

        fun get(level: ServerLevel): Markets = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        fun enabled(): Boolean = try {
            GuildmarkConfig.MARKETS.get()
        } catch (e: IllegalStateException) {
            true
        }

        fun register() {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
            VillageMarkets.register()
        }

        /** The hour of the overworld's clock it is, counted from the world's first (0 is six in the morning). */
        fun hourOf(level: ServerLevel): Long = Math.floorDiv(level.overworldClockTime, 1000L)

        /** [capital]'s market, opened now if it isn't yet and its places are known; null while they're worked out. */
        fun of(level: ServerLevel, capital: CapitalPiece): Entry? {
            val data = get(level)
            data[capital.center]?.let { return it }
            val places = CityPlaces.of(level, capital) ?: return null
            return data.open(level, places)
        }

        /**
         * The market kept under [key]: a capital's (the key of its centre) or a village's (the key of its bell), or null if none has
         * opened there. Opens nothing and writes nothing. A village's is found only while villages have markets (see [VillageMarkets]).
         */
        fun find(level: ServerLevel, key: Long): Entry? = get(level).entries[key] ?: VillageMarkets.find(level, key)

        private fun tick(server: MinecraftServer) {
            if (server.tickCount % TICK != 0 || !enabled()) return
            val level = server.overworld()
            val data = get(level)
            if (server.tickCount % LOOK == 0) {
                look(level, data)
                VillageMarkets.look(level)
            }
            // The villages work out their hours after the capitals, with the steps the capitals leave
            VillageMarkets.run(level, run(level, data))
        }

        /** Opens the market of every capital a player stands on, and counts its people again now and then. */
        private fun look(level: ServerLevel, data: Markets) {
            for (player in level.players()) {
                val capital = Furnish.capitalAt(level, player.blockPosition()) ?: continue
                val places = CityPlaces.of(level, capital) ?: continue
                val entry = data[capital.center]
                if (entry == null) {
                    data.open(level, places)
                    continue
                }
                if (level.gameTime - entry.looked < RECOUNT) continue
                entry.town = townOf(level, places)
                entry.looked = level.gameTime
                Carriage.reckon(data.entries.values)
                data.setDirty()
            }
        }

        /** Works out the hours every market is behind, [STEPS] of them at most this run; the rest wait for the next. Gives the steps left. */
        private fun run(level: ServerLevel, data: Markets): Int {
            val now = hourOf(level)
            var steps = STEPS
            for (entry in data.entries.values) {
                val m = entry.market
                // Time set back: the market carries on from the new hour, and what's under way keeps the days it had left;
                // the town's fortunes and works go back with it, so the clock's next close is their next (the comings, who
                // go by the clock with the markets off too, take themselves back at their next look: Comings.setBack)
                if (m.hour > now) {
                    val hours = m.hour - now
                    val days = Conditions(m.hour).day - Conditions(now).day
                    rewind(m, hours)
                    Fortunes.back(level, m.capital, days, hours)
                    Works.back(level, m.capital, days, hours)
                    data.setDirty()
                    continue
                }
                if (now - m.hour > CATCH_UP) m.hour = now - CATCH_UP
                // A wagon being watched acts its arrival or its waylaying out first, and the hour waits for it
                while (m.hour < now && steps > 0 && Caravans.ready(level, entry, m.hour + 1)) {
                    step(level, entry, m.hour + 1)
                    steps--
                    data.setDirty()
                }
            }
            return steps
        }

        /**
         * Works out [entry]'s market's [hour] in the overworld's weather and the season, its players' own stalls
         * selling in it, and its wagons on the road: what got in or was waylaid by the hour, and those setting out in
         * it (see [Caravans]), whose goods come when they get in rather than at the hour.
         */
        fun step(level: ServerLevel, entry: Entry, hour: Long) {
            val felt = conditions(level, hour, entry.capital).spending(Fortunes.spend(level, entry.market.capital))
            entry.felt = felt
            val carried = Caravans.hour(level, entry, hour)
            // What the inn, the bakery and the alms table serve at a meal hour the town does not eat as plain meals (cooking/market)
            val served = Kitchens.step(level, entry, hour)
            Economy.hour(entry.market, entry.town, felt, random(entry.market, hour), OwnStalls.seller(level, entry), carried, served = served, afar = true)
            // The town's works: at the close, what the day showed a need of; every hour, what they did and their freight
            if (felt.clock == Economy.NEW_DAY) Works.day(level, entry)
            Works.hour(level, entry, hour, carried)
            // Its fortunes after the works, so the close reads the day the works finished
            Fortunes.day(level, entry)
            // Its tales after its fortunes, which they note their endings to (tales/)
            Tales.day(level, entry)
        }

        /** The world in [entry]'s market's hour as it feels it there (the season and any feast with it), for why its prices stand where they do. */
        fun felt(entry: Entry): Conditions {
            val hour = entry.market.hour
            entry.felt?.takeIf { it.hour == hour }?.let { return it }
            val server = ServerLifecycleHooks.getCurrentServer() ?: return Conditions(hour)
            return conditions(server.overworld(), hour, entry.capital).also { entry.felt = it }
        }

        /**
         * Takes [m] back [hours], what it has under way and its news with it; its wagons not yet in are called off and
         * their goods come in at once (see [Caravans.rewind]).
         */
        fun rewind(m: Market, hours: Long) {
            m.hour -= hours
            Caravans.rewind(m, hours)
            val events = m.events.map { it.back(hours) }
            m.events.clear()
            m.events.addAll(events)
            val news = m.news.map { News(it.hour - hours, it.key, it.good, it.value) }
            m.news.clear()
            m.news.addAll(news)
        }

        /** The world in [hour]: the overworld's weather now, and the season and festivals as [at], a capital's heart, feels them. */
        fun conditions(level: ServerLevel, hour: Long, at: BlockPos? = null): Conditions = Seasonal.conditions(level, hour, at)

        /** The same for the same market and hour, so catching up comes out as it would have hour by hour. */
        private fun random(m: Market, hour: Long): Random = Random(m.capital * -0x61c8864680b583ebL xor hour * 0x2545F4914F6CDD1DL)

        /**
         * [places]' capital as its market sees it: the plan's people still living (the dead aren't replaced in the
         * count until someone new is taken in) and anyone taken in since, with the barns, coops, mills and fruit trees
         * its country was laid out with and the fletchers its workshops were set up for.
         */
        fun townOf(level: ServerLevel, places: CityPlaces): Town {
            val census = places.census
            val center = places.capital.center
            val residents = Residents.get(level)
            val living = IntArray(Folk.entries.size)
            val built = IntArray(Folk.entries.size)
            census.residents.forEachIndexed { i, r ->
                built[r.folk.ordinal]++
                if (residents.planned(center, i)?.alive != false) living[r.folk.ordinal]++
            }
            var others = 0
            for (r in residents.of(center)) {
                if (r.planned >= 0 || !r.alive) continue
                val folk = CityPlaces.folkOf(r.role)
                if (folk != null) living[folk.ordinal]++ else others++
            }
            val plan = census.plan
            val fletchers = plan.buildings.count { it.trade == Trade.FLETCHER }
            val barns = plan.outskirts.buildings.count { it.kind == FarmKind.BARN }
            val coops = plan.outskirts.buildings.count { it.kind == FarmKind.COOP }
            val mills = plan.outskirts.buildings.count { it.kind == FarmKind.WINDMILL || it.kind == FarmKind.MILL }
            val trees = plan.outskirts.props.count { it.kind == FarmPropKind.FRUIT_TREE }
            // An oven in each bakery and watermill, and any the town's works have added (see economy/works)
            val ovens = plan.buildings.count { it.kind == Kind.BAKERY } + plan.outskirts.buildings.count { it.kind == FarmKind.MILL }
            fun town(n: IntArray, extra: Int, planned: Town?, ovens: Int, granary: Int) = Town(
                people = n.sum() + extra, children = n[Folk.CHILD.ordinal], farmers = n[Folk.FARMER.ordinal],
                fishers = n[Folk.FISHER.ordinal], bakers = n[Folk.BAKER.ordinal], smiths = n[Folk.SMITH.ordinal],
                fletchers = fletchers, innkeepers = n[Folk.INNKEEPER.ordinal], guards = n[Folk.GUARD.ordinal],
                barns = barns, coops = coops, mills = mills, trees = trees, planned = planned, ovens = ovens, granary = granary,
            )
            val capital = center.asLong()
            return town(living, others, town(built, 0, null, ovens, 0), ovens + Works.ovens(level, capital), Works.grade(level, capital))
        }
    }
}
