package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.village.Festivals
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageBudget
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.keptList
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster.NONE
import io.github.vaspyyy.guildmark.village.live.market.VillageTown
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.function.Supplier
import kotlin.random.Random

/**
 * Every village's market (the villages design, section 7): a [Market] and a [Town] in a [Markets.Entry], as a capital has,
 * worked an hour at a time by the same [Economy.hour], but kept in a store of its own ("village_markets") and keyed by the
 * bell, so that nothing which walks the capitals' markets ever meets a village's, and a capital's prices do not move when a
 * village's market opens (the villages are receivers in [Carriage], never sources). [Markets.find] answers for both.
 *
 * What differs from a capital's market is where its goods come from and go to. A village has no abstract caravans
 * (`afar = false`); its goods move by the calls of its cart ([Economy.call]): the pedlar's, on his rounds and on market day
 * (see [PedlarRoster]), and the wagon's, which V7 posts as [Mail] and this applies at the mail's own hour. A village's market
 * opens when its first census is done, the town it serves is counted again every [RECOUNT] ticks while a player is near
 * (see [VillageTown]), and its hours are worked out for the villages awake, nearest a player first, out of the steps the
 * capitals' run leaves, [VillageBudget.STEPS] at most a tick.
 *
 * With the switch off ([enabled] false) none of it runs, nothing is written, and [Markets.find] answers for the capitals alone.
 */
class VillageMarkets(private val records: MutableMap<Long, Rec> = HashMap()) : SavedData() {
    /** One village's market, the cart that calls on it, and the mail waiting for its hour. */
    class Rec(val entry: Markets.Entry, val cart: Cart = Cart(), val mail: MutableList<Mail> = ArrayList()) {
        val key: Long get() = entry.market.capital
    }

    /**
     * What a village's cart has done and will do: the day his round is next due ([next]), the visits made ([calls], which seed the
     * gaps), when the last call was made ([lastHour], [lastDay], and [by] whom) and what it took and brought ([took], [brought], by
     * good), the last day a visit was lost and why ([missed], [why]), today's verdict ([decided], [comes]) and the day whose call is
     * made ([called]), visits an op holds back ([held]) and when a wagon last called ([wagon], a market hour).
     */
    class Cart(
        var next: Long = NONE,
        var calls: Int = 0,
        var lastHour: Long = NONE,
        var lastDay: Long = NONE,
        var by: String = "",
        var took: Map<String, Double> = emptyMap(),
        var brought: Map<String, Double> = emptyMap(),
        var missed: Long = NONE,
        var why: String = "",
        var decided: Long = NONE,
        var comes: Boolean = false,
        var called: Long = NONE,
        var held: Int = 0,
        var wagon: Long = NONE,
    ) {
        /** The clock set back [days] days and [hours] hours: every date the cart keeps goes back with it. */
        fun back(days: Long, hours: Long) {
            if (next != NONE) next -= days
            if (lastHour != NONE) lastHour -= hours
            if (lastDay != NONE) lastDay -= days
            if (missed != NONE) missed -= days
            if (decided != NONE) decided -= days
            if (called != NONE) called -= days
            if (wagon != NONE) wagon -= hours
        }

        private class Rounds(val next: Long, val calls: Int, val held: Int, val wagon: Long)
        private class Last(val hour: Long, val day: Long, val by: String, val took: Map<String, Double>, val brought: Map<String, Double>)
        private class Today(val missed: Long, val why: String, val decided: Long, val comes: Boolean, val called: Long)

        companion object {
            private val GOODS: Codec<Map<String, Double>> = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)

            private val ROUNDS: MapCodec<Rounds> = RecordCodecBuilder.mapCodec { i ->
                i.group(
                    Codec.LONG.optionalFieldOf("next", NONE).forGetter(Rounds::next),
                    Codec.INT.optionalFieldOf("calls", 0).forGetter(Rounds::calls),
                    Codec.INT.optionalFieldOf("held", 0).forGetter(Rounds::held),
                    Codec.LONG.optionalFieldOf("wagon", NONE).forGetter(Rounds::wagon),
                ).apply(i, ::Rounds)
            }

            private val LAST: MapCodec<Last> = RecordCodecBuilder.mapCodec { i ->
                i.group(
                    Codec.LONG.optionalFieldOf("lastHour", NONE).forGetter(Last::hour),
                    Codec.LONG.optionalFieldOf("lastDay", NONE).forGetter(Last::day),
                    Codec.STRING.optionalFieldOf("by", "").forGetter(Last::by),
                    GOODS.optionalFieldOf("took", emptyMap()).forGetter(Last::took),
                    GOODS.optionalFieldOf("brought", emptyMap()).forGetter(Last::brought),
                ).apply(i, ::Last)
            }

            private val TODAY: MapCodec<Today> = RecordCodecBuilder.mapCodec { i ->
                i.group(
                    Codec.LONG.optionalFieldOf("missed", NONE).forGetter(Today::missed),
                    Codec.STRING.optionalFieldOf("why", "").forGetter(Today::why),
                    Codec.LONG.optionalFieldOf("decided", NONE).forGetter(Today::decided),
                    Codec.BOOL.optionalFieldOf("comes", false).forGetter(Today::comes),
                    Codec.LONG.optionalFieldOf("called", NONE).forGetter(Today::called),
                ).apply(i, ::Today)
            }

            val CODEC: Codec<Cart> = RecordCodecBuilder.create { i ->
                i.group(
                    ROUNDS.forGetter { Rounds(it.next, it.calls, it.held, it.wagon) },
                    LAST.forGetter { Last(it.lastHour, it.lastDay, it.by, it.took, it.brought) },
                    TODAY.forGetter { Today(it.missed, it.why, it.decided, it.comes, it.called) },
                ).apply(i) { r, l, t ->
                    Cart(r.next, r.calls, l.hour, l.day, l.by, l.took, l.brought, t.missed, t.why, t.decided, t.comes, t.called, r.held, r.wagon)
                }
            }
        }
    }

    /**
     * What a cart or a wagon did at a village at market [hour]: the goods it took away ([out]) and brought ([inn]) by the good's
     * place in [Good.entries], or [lost] (taken on the road: nothing is applied). Kept in the order posted and applied at the
     * mail's own hour by the village's step, so the village's hours stay in order however the capital's step is placed.
     */
    class Mail(val hour: Long, val out: DoubleArray, val inn: DoubleArray, val lost: Boolean = false) {
        companion object {
            private val GOODS: Codec<Map<String, Double>> = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)

            private fun byId(a: DoubleArray): Map<String, Double> = Good.entries.filter { a[it.ordinal] > 0.0 }.associate { it.id to a[it.ordinal] }

            private fun byOrdinal(m: Map<String, Double>): DoubleArray {
                val a = DoubleArray(Good.entries.size)
                for ((id, n) in m) Good.byId(id)?.let { a[it.ordinal] = n }
                return a
            }

            val CODEC: Codec<Mail> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("hour").forGetter(Mail::hour),
                    GOODS.optionalFieldOf("out", emptyMap()).forGetter { byId(it.out) },
                    GOODS.optionalFieldOf("inn", emptyMap()).forGetter { byId(it.inn) },
                    Codec.BOOL.optionalFieldOf("lost", false).forGetter(Mail::lost),
                ).apply(i) { hour, out, inn, lost -> Mail(hour, byOrdinal(out), byOrdinal(inn), lost) }
            }
        }
    }

    operator fun get(key: Long): Rec? = records[key]

    fun entry(key: Long): Markets.Entry? = records[key]?.entry

    fun all(): Collection<Rec> = records.values

    fun changed() = setDirty()

    companion object {
        /** Game ticks between the counts of a village's people while someone is near (a capital's, too). */
        private const val RECOUNT = 1200L

        private val REC: Codec<Rec> = RecordCodecBuilder.create { i ->
            i.group(
                Market.CODEC.fieldOf("market").forGetter { it.entry.market },
                Town.CODEC.fieldOf("town").forGetter { it.entry.town },
                Cart.CODEC.optionalFieldOf("cart", Cart()).forGetter(Rec::cart),
                keptList(Mail.CODEC).lenientOptionalFieldOf("mail", listOf()).forGetter { it.mail },
            ).apply(i) { market, town, cart, mail -> Rec(Markets.Entry(market, town), cart, ArrayList(mail)) }
        }

        private val CODEC: Codec<VillageMarkets> = RecordCodecBuilder.create { i ->
            i.group(
                keptList(REC).lenientOptionalFieldOf("villages", listOf()).forGetter { data -> data.records.values.sortedBy { it.key } },
            ).apply(i) { list -> VillageMarkets(list.associateByTo(HashMap()) { it.key }) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village_markets"), Supplier { VillageMarkets() }, CODEC)

        /** The overworld's village markets, whichever level asks. */
        fun get(level: ServerLevel): VillageMarkets = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        private fun flag(value: () -> Boolean): Boolean = try {
            value()
        } catch (e: IllegalStateException) {
            true
        }

        /** Whether villages have markets: Capital Markets, Living Villages and Village Markets all on. */
        fun enabled(): Boolean = Markets.enabled() && Villages.enabled() && flag { GuildmarkConfig.VILLAGE_MARKETS.get() }

        /** Whether the pedlar comes: the markets of the villages on, and Village Pedlar. */
        fun pedlars(): Boolean = enabled() && flag { GuildmarkConfig.VILLAGE_PEDLAR.get() }

        /** Whether villages have good times and hard: their markets on, and Village Fortunes (off, every village reads steady). */
        fun fortunes(): Boolean = enabled() && flag { GuildmarkConfig.VILLAGE_FORTUNES.get() }

        /** At setup: the registry's word of a village counted, and the lines of `village info`. */
        fun register() {
            Villages.listen(object : Villages.Listener {
                override fun counted(level: ServerLevel, rec: VillageRec, places: VillagePlaces) {
                    if (enabled()) opened(level, rec, places)
                }

                override fun dropped(level: ServerLevel, rec: VillageRec) {
                    val data = get(level)
                    if (data.records.remove(rec.key) != null) data.setDirty()
                }
            })
        }

        /** The market of the village [key] if there is one and villages have markets; the capitals' markets are [Markets.get]'s. */
        fun find(level: ServerLevel, key: Long): Markets.Entry? = if (enabled()) get(level).records[key]?.entry else null

        /** The market of the village [key] in the world now, for those that have no level in hand (V7's wagons). */
        fun entry(key: Long): Markets.Entry? {
            val server = ServerLifecycleHooks.getCurrentServer() ?: return null
            return find(server.overworld(), key)
        }

        /** Whether [entry] is a village's (kept here) and not a capital's. */
        fun owns(level: ServerLevel, entry: Markets.Entry): Boolean = get(level).records[entry.market.capital]?.entry === entry

        /** Marks the store [entry] is kept in as changed: the villages' if it is a village's, else the capitals'. */
        fun changed(level: ServerLevel, entry: Markets.Entry) {
            if (owns(level, entry)) get(level).setDirty() else Markets.get(level).changed()
        }

        // ---- Opening, and counting again ----

        /** Opens a market for the village [rec] now if it has none and its people can be counted; the census [places] it was told of. */
        private fun opened(level: ServerLevel, rec: VillageRec, places: VillagePlaces) {
            val data = get(level)
            if (rec.state == Live.LOST || rec.state == Live.PAUSED) return
            val have = data.records[rec.key]
            if (have == null) {
                val town = VillageTown.count(level, rec, places, null) ?: return
                make(level, data, rec, town)
            } else if (level.gameTime - have.entry.looked >= RECOUNT) {
                count(level, data, rec, places, have)
            }
        }

        /**
         * Opens the market of the village [rec] afresh, as a town keeps one: everything at what the town likes to hold, from this
         * hour on. Its people are counted now whether or not the whole of the village's ground is loaded. Another market of the
         * same village (the command `market open`) is replaced.
         */
        fun open(level: ServerLevel, rec: VillageRec, places: VillagePlaces): Markets.Entry {
            val data = get(level)
            val town = VillageTown.count(level, rec, places, null, needLoaded = false) ?: VillageTown.town(rec, places, VillageTown.Folk(), null)
            return make(level, data, rec, town)
        }

        private fun make(level: ServerLevel, data: VillageMarkets, rec: VillageRec, town: Town): Markets.Entry {
            // The first count is the town as it was found: the one every later count is measured against
            val counted = VillageTown.planning(town, town)
            val market = Market(rec.key)
            Economy.open(market, counted)
            market.hour = Markets.hourOf(level)
            val entry = Markets.Entry(market, counted, level.gameTime)
            data.records[rec.key] = Rec(entry)
            reckon(level)
            data.setDirty()
            return entry
        }

        private fun count(level: ServerLevel, data: VillageMarkets, rec: VillageRec, places: VillagePlaces, have: Rec) {
            val now = VillageTown.count(level, rec, places, have.entry.town.planned) ?: return
            have.entry.town = now
            have.entry.looked = level.gameTime
            reckon(level)
            data.setDirty()
        }

        /**
         * Once in a while (each 100 ticks, from [Markets]): the markets of the villages awake with a census are opened, and counted
         * again when it is time.
         */
        fun look(level: ServerLevel) {
            if (!enabled()) return
            for (rec in Villages.all(level)) {
                if (rec.state != Live.LIVE) continue
                val places = Villages.places(level, rec.key) ?: continue
                try {
                    opened(level, rec, places)
                } catch (e: RuntimeException) {
                    Guildmark.LOGGER.warn("The market of the village at {} could not be opened or counted", BlockPos.of(rec.key).toShortString(), e)
                }
            }
        }

        /** What bringing each good in costs the villages' markets, from the capitals' (see [Carriage.reckonVillages]). */
        private fun reckon(level: ServerLevel) {
            Carriage.reckonVillages(Markets.get(level).all(), get(level).records.values.map { it.entry })
        }

        // ---- The hours ----

        /**
         * Works out the hours the villages awake are behind, nearest a player first, [steps] of them at most (what the capitals' run
         * left) and what the villages' own budget allows; answers the steps left. A village's hours it cannot reach this run wait
         * for the next, as a capital's do, and an absence longer than [Markets.CATCH_UP] hours is caught up that far.
         */
        fun run(level: ServerLevel, steps: Int): Int {
            if (!enabled() || steps <= 0) return steps
            val data = get(level)
            if (data.records.isEmpty()) return steps
            val now = Markets.hourOf(level)
            var left = steps
            for (rec in Villages.awake(level)) {
                val v = data.records[rec.key] ?: continue
                val m = v.entry.market
                if (m.hour > now) {
                    setBack(level, v, now)
                    data.setDirty()
                    continue
                }
                if (now - m.hour > Markets.CATCH_UP) m.hour = now - Markets.CATCH_UP
                while (m.hour < now && left > 0 && VillageBudget.steps() > 0) {
                    try {
                        step(level, v, m.hour + 1)
                    } catch (e: RuntimeException) {
                        // A village whose hour cannot be worked out stands still for the run, not the server
                        Guildmark.LOGGER.warn("The market of the village at {} could not work out an hour", BlockPos.of(rec.key).toShortString(), e)
                        break
                    }
                    left--
                    VillageBudget.usedSteps(1)
                    data.setDirty()
                }
            }
            return left
        }

        /** The clock was set back to market [now]: the village's market carries on from the new hour and its dated things go back with it. */
        private fun setBack(level: ServerLevel, v: Rec, now: Long) {
            val m = v.entry.market
            val hours = m.hour - now
            val days = Conditions(m.hour).day - Conditions(now).day
            Markets.rewind(m, hours)
            v.cart.back(days, hours)
            v.mail.replaceAll { Mail(it.hour - hours, it.out, it.inn, it.lost) }
            Fortunes.back(level, m.capital, days, hours)
        }

        /**
         * Works out [v]'s market's [hour] in the weather and season of its own place, the mail due by it applied, and its cart's call
         * made in it if he comes on its day; at the close of the day its fortunes and then everything that keeps a daily rhythm
         * (the registry's [Villages.closeDay]) close.
         */
        fun step(level: ServerLevel, v: Rec, hour: Long) {
            val entry = v.entry
            val m = entry.market
            val felt = Markets.conditions(level, hour, entry.capital).spending(if (fortunes()) Fortunes.spend(level, m.capital) else 1.0)
            entry.felt = felt
            deliver(v, hour)
            Economy.hour(m, entry.town, felt, random(m, hour), { mk, town -> visit(level, v, mk, town, felt) }, carried = false, afar = false)
            if (felt.clock == Economy.NEW_DAY) close(level, v, felt.day)
        }

        /** The hour's random: the same for the same market and hour, so catching up comes out as it would have hour by hour. */
        private fun random(m: Market, hour: Long): Random = Random(m.capital * -0x61c8864680b583ebL xor hour * 0x2545F4914F6CDD1DL)

        /** The day closes: its fortunes, if they can be worked out, and then everyone who keeps a daily rhythm. */
        private fun close(level: ServerLevel, v: Rec, day: Long) {
            try {
                if (fortunes()) Fortunes.day(level, v.entry)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("The fortunes of the village at {} could not close day {}", BlockPos.of(v.key).toShortString(), day, e)
            }
            Villages.byKey(level, v.key)?.let { Villages.closeDay(level, it, day) }
        }

        // ---- Mail ----

        /** Posts [mail] to the village [key]'s market: V7's wagons call this; it is applied at the mail's own hour. */
        fun post(key: Long, mail: Mail) {
            val server = ServerLifecycleHooks.getCurrentServer() ?: return
            val level = server.overworld()
            if (!enabled()) return
            val data = get(level)
            val v = data.records[key] ?: return
            v.mail.add(mail)
            v.mail.sortBy { it.hour }
            data.setDirty()
        }

        /** Applies the mail due by [hour]: what the wagon took leaves the stock, what it brought joins it, tallied as a call's is. */
        private fun deliver(v: Rec, hour: Long) {
            if (v.mail.isEmpty()) return
            val m = v.entry.market
            val due = v.mail.filter { it.hour <= hour }
            if (due.isEmpty()) return
            v.mail.removeAll(due.toSet())
            for (mail in due) {
                if (mail.lost) continue
                for (g in Good.entries) {
                    val out = mail.out[g.ordinal]
                    if (out > 0.0) {
                        val took = m.take(Site.MARKET, g, out)
                        if (took > 0.0) m.tally(Tally.EXPORTED, g, took)
                    }
                    val inn = mail.inn[g.ordinal]
                    if (inn > 0.0) {
                        m.add(Economy.site(g), g, inn)
                        m.tally(Tally.IMPORTED, g, inn)
                    }
                }
                v.cart.wagon = maxOf(v.cart.wagon, mail.hour)
            }
        }

        // ---- The pedlar's call ----

        /** The pedlar's hook in the hour: the call he makes at the green is made in the hour [PedlarRoster.CALL_CLOCK] begins, if he comes today. */
        private fun visit(level: ServerLevel, v: Rec, m: Market, town: Town, c: Conditions) {
            if (c.clock != PedlarRoster.CALL_CLOCK) return
            if (v.cart.called == c.day) return
            if (!decide(level, v, c.day).comes) return
            call(v, m, town, c.hour, c.day, "pedlar")
        }

        /**
         * Whether the pedlar comes on [day] and why, decided once for the day (the body that walks in at half past eight and the call
         * of the record at nine hear the same): a visit made puts his next round [PedlarRoster.gap] days on, and so does a round lost.
         */
        fun decide(level: ServerLevel, v: Rec, day: Long): PedlarRoster.Why {
            val cart = v.cart
            if (cart.decided == day) return PedlarRoster.Why.of(cart.why) ?: if (cart.comes) PedlarRoster.Why.ROUND else PedlarRoster.Why.NOT_DUE
            val rec = Villages.byKey(level, v.key)
            val bell = v.key
            val why = if (!pedlars() || rec == null) PedlarRoster.Why.OFF else {
                val round = PedlarRoster.round(day, cart.next)
                val market = Festivals.isMarketDay(anchor(rec), day)
                val rest = Days.restDay(day)
                val wagon = rec.links.isNotEmpty() && cart.wagon != NONE && day - Conditions(cart.wagon).day <= PedlarRoster.WAGON_DAYS
                val closed = rec.links.isNotEmpty() && rec.links.all { closedRoad(level, it.capital, it.road) }
                val verdict = PedlarRoster.verdict(bell, day, rest, market, cart.next, wagon, closed, bandNear(level, rec), cart.held)
                // A visit held back or lost to a band counts as the round's, so he tries again a gap on
                if (verdict == PedlarRoster.Why.HELD) cart.held--
                if (verdict.comes) {
                    cart.calls++
                    cart.next = day + PedlarRoster.gap(bell, cart.calls)
                } else if ((verdict == PedlarRoster.Why.HELD || verdict == PedlarRoster.Why.BAND) && round) {
                    cart.next = day + PedlarRoster.gap(bell, cart.calls + 1)
                }
                if (verdict == PedlarRoster.Why.HELD || verdict == PedlarRoster.Why.BAND) cart.missed = day
                verdict
            }
            cart.decided = day
            cart.comes = why.comes
            cart.why = why.id
            return why
        }

        /** The anchor a village's market day is read by: its board's, else its bell. */
        fun anchor(rec: VillageRec): BlockPos = BlockPos.of(if (rec.board >= 0L) rec.board else rec.key)

        private fun closedRoad(level: ServerLevel, capital: Long, road: Int): Boolean =
            RoadPeril.stage(CaravanData.get(level), capital, road) == RoadPeril.Stage.CLOSED

        /** Whether a band that is not slain camps within the capitals' reach of the village, with road ambushes on. */
        fun bandNear(level: ServerLevel, rec: VillageRec): Boolean {
            if (!Caravans.ambushes()) return false
            val reach = CaravanRoutes.BAND_REACH
            val x = BlockPos.getX(rec.key) + 0.5
            val z = BlockPos.getZ(rec.key) + 0.5
            return OutlawCamps.get(level).bands.any { b ->
                if (b.slain) return@any false
                val dx = b.pos.x + 0.5 - x
                val dz = b.pos.z + 0.5 - z
                dx * dx + dz * dz <= reach * reach
            }
        }

        /** The cart calls on the village at market [hour] of [day] ([by] the pedlar, or the command): what it takes and brings is kept. */
        fun call(v: Rec, m: Market, town: Town, hour: Long, day: Long, by: String): Exchange {
            val cart = v.cart
            val hours = if (cart.lastHour == NONE) 24L else (hour - cart.lastHour).coerceAtLeast(1L)
            val ex = Economy.call(m, town, hours)
            cart.lastHour = hour
            cart.lastDay = day
            cart.called = day
            cart.by = by
            cart.took = ex.taken().associate { it.first.id to it.second }
            cart.brought = ex.brought().associate { it.first.id to it.second }
            return ex
        }
    }
}
