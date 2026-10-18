package io.github.vaspyyy.guildmark.economy.works

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRec
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.GoodsCodec
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import kotlin.math.max

/**
 * What a wagon carries for a capital's works besides its load, kept in its record (CaravanRec.freight): materials for
 * works [works] by Stuff.PAID ([stuff]), or goods for the town granary by Good ([goods]): the first filling of the
 * granary works [works], or with [works] -1 what the good trade brings the granary. Lost with the wagon if it's
 * waylaid, and landed in the yard or the bins when it gets in (see [WorksFreight]).
 */
class Freight(val works: Int, val stuff: IntArray, val goods: DoubleArray) {
    /** Units of materials in it. */
    val units: Int get() = stuff.sum()

    companion object {
        val CODEC: Codec<Freight> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("works", -1).forGetter(Freight::works),
                WorksData.STUFF.optionalFieldOf("stuff", IntArray(Stuff.PAID.size)).forGetter(Freight::stuff),
                GoodsCodec.CODEC.optionalFieldOf("goods", DoubleArray(Good.entries.size)).forGetter(Freight::goods),
            ).apply(i, ::Freight)
        }
    }
}

/** A works' materials on a wagon on its way in, as its screen shows them: the wagon, its road, what it carries for the works by Stuff.PAID, and when it's due in (clock time). */
class Shipment(val caravan: Long, val road: Int, val stuff: IntArray, val due: Long)

/**
 * The works' freight (design-influence.md section 2, "Materials on the wagons"). A paid-for works' bill goes out on the
 * next wagons, [PER_WAGON] units a wagon, in the order the stages want it: stone for the footings first. A granary's
 * first filling comes a sixth a wagon on the wagons after it opens, and while the market is well stocked each caravan
 * brings the granary a little more (see [Reserve.trade]). A wagon's freight is taken on as it sets out (the hook in
 * Caravans.depart); it lands when the wagon gets in, and is lost if it's waylaid, to go again on the next wagon at no
 * one's cost (it's never salvage). With the wagons switched off it comes in at the caravan hours all the same; and
 * freight held up [LONG_WAY] hours comes round the long way at the next caravan hour, so no works waits for ever.
 */
internal object WorksFreight : Caravans.Listener {
    /** Units of materials a wagon carries for the works. */
    const val PER_WAGON = 160

    /** Market hours a works may go without freight landing, while it still wants some, before it comes round the long way. */
    const val LONG_WAY = 72L

    /** The materials in the order the stages want them: stone for the footings, the frame's timber, then iron, glass and wool for the fitting. */
    private val ORDER = listOf(Stuff.STONE, Stuff.TIMBER, Stuff.IRON, Stuff.GLASS, Stuff.WOOL).map { Stuff.PAID.indexOf(it) }

    /** The phases a wagon's freight is on its way in. */
    private val ON_WAY = setOf(Phase.WAITING, Phase.ROLLING, Phase.STRUCK)

    /** A wagon held at the start of a Closed road, its freight held up with it. */
    private val HELD = setOf(Phase.PLANNED, Phase.WAITING)

    /** What's on its way to a capital's works already: materials and granary goods by works id. */
    private class Coming {
        val stuff = HashMap<Int, IntArray>()
        val goods = HashMap<Int, DoubleArray>()

        fun stuff(works: Int): IntArray = stuff[works] ?: IntArray(Stuff.PAID.size)
        fun goods(works: Int): DoubleArray = goods[works] ?: DoubleArray(Good.entries.size)

        /** All the granary goods on their way, its filling and its trade. */
        fun allGoods(): DoubleArray = DoubleArray(Good.entries.size).also { out -> for (a in goods.values) for (i in out.indices) out[i] += a[i] }
    }

    /** [capital]'s freight on its way in, on any wagon but [except]. */
    private fun coming(level: ServerLevel, capital: Long, except: CaravanRec? = null): Coming {
        val out = Coming()
        for (c in CaravanData.get(level).caravans) {
            if (c === except || c.capital != capital || c.phase !in ON_WAY) continue
            for (f in c.freight) {
                val s = out.stuff.getOrPut(f.works) { IntArray(Stuff.PAID.size) }
                for (i in s.indices) s[i] += f.stuff[i]
                val g = out.goods.getOrPut(f.works) { DoubleArray(Good.entries.size) }
                for (i in g.indices) g[i] += f.goods[i]
            }
        }
        return out
    }

    /** Works [id]'s materials on the wagons on their way in, by Stuff.PAID (the view's `coming`). */
    fun onWay(level: ServerLevel, capital: Long, id: Int): IntArray = coming(level, capital).stuff(id)

    /** Works [id]'s materials on each wagon on its way in. */
    fun shipments(level: ServerLevel, capital: Long, id: Int): List<Shipment> = CaravanData.get(level).caravans
        .filter { it.capital == capital && it.phase in ON_WAY }
        .mapNotNull { c ->
            val stuff = IntArray(Stuff.PAID.size)
            for (f in c.freight) if (f.works == id) for (i in stuff.indices) stuff[i] += f.stuff[i]
            if (stuff.sum() <= 0) null else Shipment(c.id, c.road, stuff, c.setOut + c.held + Caravans.ticksFor(c.length))
        }

    /**
     * What [c] takes on for its capital's works as it sets out (the hook in Caravans.depart): what their bills still
     * want that isn't on its way already, [PER_WAGON] units for each of its wagons, oldest works first; the granaries'
     * first fillings, a sixth for each wagon; and the granary's share of the trade for each caravan it carries.
     */
    fun load(level: ServerLevel, entry: Markets.Entry, c: CaravanRec): List<Freight> {
        if (!Works.enabled()) return listOf()
        return pack(WorksData.get(level), entry, c.wagons, max(1, c.slots.size), coming(level, c.capital, c))
    }

    /** One caravan's freight for [entry]'s works, [wagons] wagons carrying [slots] caravans' trade, with [coming] on its way already. */
    private fun pack(data: WorksData, entry: Markets.Entry, wagons: Int, slots: Int, coming: Coming): List<Freight> {
        val out = ArrayList<Freight>()
        val capital = entry.market.capital
        var room = PER_WAGON * wagons
        for (w in data.of(capital)) {
            if (!w.open || room <= 0) continue
            val on = coming.stuff(w.id)
            val stuff = IntArray(Stuff.PAID.size)
            for (i in ORDER) {
                val n = minOf(room, w.bill[i] - w.here[i] - w.used[i] - on[i]).coerceAtLeast(0)
                stuff[i] = n
                room -= n
            }
            if (stuff.sum() > 0) out.add(Freight(w.id, stuff, DoubleArray(Good.entries.size)))
        }
        val town = entry.town
        if (town.granary <= 0) return out
        val granaries = data.of(capital).filter { it.state == WorksState.DONE && (it.kind == WorksKind.GRANARY || it.kind == WorksKind.GREAT_GRANARY) }
        // What's still owed of each first filling and not on a wagon yet, so the trade leaves room for it
        val owed = DoubleArray(Good.entries.size)
        for (w in granaries) {
            val on = coming.goods(w.id)
            for (i in owed.indices) owed[i] += (w.fill[i] - on[i]).coerceAtLeast(0.0)
        }
        val trade = Reserve.trade(entry.market, town, coming.allGoods(), owed, slots)
        for (w in granaries) {
            val on = coming.goods(w.id)
            val each = WorksKinds.filling(w.kind, town)
            val goods = DoubleArray(Good.entries.size) { i -> minOf(each[i] / Reserve.FILLING_CARAVANS * wagons, w.fill[i] - on[i]).coerceAtLeast(0.0) }
            if (goods.any { it > 1e-6 }) out.add(Freight(w.id, IntArray(Stuff.PAID.size), goods))
        }
        if (trade.any { it > 1e-6 }) out.add(Freight(-1, IntArray(Stuff.PAID.size), trade))
        return out
    }

    /**
     * [freight] in at [entry]'s capital in market [hour]: materials into their works' yards, as far as each bill
     * still wants them (what players brought meanwhile left the wagon carrying more than was wanted); goods into the
     * granary's bins as far as there's room (STORED), the rest to the market; each first filling the less for it.
     */
    private fun land(data: WorksData, entry: Markets.Entry, freight: List<Freight>, hour: Long) {
        val m = entry.market
        val room = Reserve.room(entry.town)
        for (f in freight) {
            val w = data.works[f.works]
            if (w != null && f.units > 0) {
                if (w.open) for (i in f.stuff.indices) w.here[i] += minOf(f.stuff[i], w.bill[i] - w.here[i] - w.used[i]).coerceAtLeast(0)
                w.landed = hour
            }
            if (f.goods.none { it > 1e-6 }) continue
            if (w != null) {
                for (i in f.goods.indices) w.fill[i] = (w.fill[i] - f.goods[i]).coerceAtLeast(0.0)
                w.landed = hour
            }
            for (g in Good.entries) {
                val n = f.goods[g.ordinal]
                if (n <= 0.0) continue
                val kept = minOf(n, (room[g.ordinal] - m[Site.GRANARY, g]).coerceAtLeast(0.0))
                if (kept > 0.0) {
                    m.add(Site.GRANARY, g, kept)
                    m.tally(Tally.STORED, g, kept)
                }
                if (n - kept > 1e-9) {
                    m.add(Site.MARKET, g, n - kept)
                    m.tally(Tally.IMPORTED, g, n - kept)
                }
            }
        }
        data.changed()
    }

    /**
     * A caravan hour at [entry]'s capital: with no wagon carrying it ([carried] false: the wagons switched off, or
     * the roads not worked out yet), what a wagon would have brought comes in at the hour; and works that have gone
     * [LONG_WAY] hours without freight while they still want it have the rest come round the long way, off any
     * wagon held at the start of a Closed road too.
     */
    fun hour(level: ServerLevel, data: WorksData, entry: Markets.Entry, hour: Long, carried: Boolean) {
        val capital = entry.market.capital
        if (!carried) land(data, entry, pack(data, entry, 1, 1, coming(level, capital)), hour)
        val wait = if (Works.rush) LONG_WAY / 10 else LONG_WAY
        for (w in data.of(capital)) {
            val wants = w.open && Stuff.PAID.indices.any { w.bill[it] - w.here[it] - w.used[it] > 0 } ||
                w.state == WorksState.DONE && w.fill.any { it > 1e-6 }
            if (!wants || hour - w.landed < wait) continue
            longWay(level, data, entry, w, hour)
        }
    }

    /** The rest of [w]'s freight comes round the long way now: off the wagons held at the start of their road, and whatever no wagon on the road is bringing. */
    private fun longWay(level: ServerLevel, data: WorksData, entry: Markets.Entry, w: WorksRec, hour: Long) {
        val caravans = CaravanData.get(level)
        for (c in caravans.caravans) {
            if (c.capital != w.capital || c.phase !in HELD || c.freight.none { it.works == w.id }) continue
            c.freight = c.freight.filter { it.works != w.id }
            caravans.changed()
        }
        val on = coming(level, w.capital)
        val stuff = IntArray(Stuff.PAID.size) { i -> (w.bill[i] - w.here[i] - w.used[i] - on.stuff(w.id)[i]).coerceAtLeast(0) }
        val goods = if (w.state == WorksState.DONE) DoubleArray(Good.entries.size) { i -> (w.fill[i] - on.goods(w.id)[i]).coerceAtLeast(0.0) } else DoubleArray(Good.entries.size)
        land(data, entry, listOf(Freight(w.id, stuff, goods)), hour)
        w.landed = hour
    }

    // ---- What befalls the wagons ----

    private fun record(level: ServerLevel, id: Long): CaravanRec? = CaravanData.get(level).caravans.firstOrNull { it.id == id }

    /** A wagon in: its freight lands, and a tower that kept the band off it is thanked. */
    override fun arrived(level: ServerLevel, caravan: Caravans.View) {
        if (!Works.enabled()) return
        val c = record(level, caravan.id) ?: return
        val data = WorksData.get(level)
        if (data.deterred.remove(c.id)) {
            data.changed()
            Works.eased(level, c.capital, WorksKind.TOWER, c.road, Works.BAND)
        }
        if (c.freight.isEmpty()) return
        val entry = Markets.get(level)[BlockPos.of(c.capital)] ?: return
        land(data, entry, c.freight, entry.market.hour)
        c.freight = listOf()
        CaravanData.get(level).changed()
        Markets.get(level).changed()
    }

    /**
     * A strike settled: waylaid, the freight is lost (its works count it, and it goes again on the next wagon, since
     * a wagon waylaid isn't on its way any more) and the road is remembered for a tower; beaten off with a tower's
     * watch riding out, the tower is thanked.
     */
    override fun struck(level: ServerLevel, caravan: Caravans.View, strike: Caravans.Strike) {
        if (!Works.enabled()) return
        val c = record(level, caravan.id) ?: return
        val data = WorksData.get(level)
        if (strike.waylaid) {
            for (f in c.freight) data.works[f.works]?.let { w -> for (i in w.lost.indices) w.lost[i] += f.stuff[i] }
            data.town(c.capital).waylaid.add(c.road)
            data.changed()
        } else if (c.watched) {
            Works.eased(level, c.capital, WorksKind.TOWER, c.road, Works.BAND)
        }
    }

    /** A wagon's record gone: a strike its tower kept off is forgotten with it. */
    override fun ended(level: ServerLevel, caravan: Long) {
        if (!Works.ready()) return
        val data = WorksData.get(level)
        if (data.deterred.remove(caravan)) data.changed()
    }
}
