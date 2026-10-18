package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * How much trouble each road into a capital is in (design-road.md section 3): a count from 0 to [MOST] that a band's
 * successes push up and an escort, a fight won with a player's help, a broken band or quiet days bring down, read as
 * a [Stage]. The stage sets how the road's wagons go (three a day with two guards or three, one noon convoy, or only
 * with an escort) and what the crier and the capital's board say of it.
 */
object RoadPeril {
    /** What a road's peril means for its wagons, and how dear a waylaid load leans the market ([factor], see [EventKind.WAYLAID]). */
    enum class Stage(val id: String, val least: Int, val factor: Double, val guards: Int) {
        /** Three wagons a day, two guards each. */
        OPEN("open", 0, 0.6, 2),

        /** Three guards a wagon; an escort note and a wanted note go up on the capital's board, and the crier says why. */
        TROUBLED("troubled", 3, 0.5, 3),

        /** The road's wagons go together as one noon convoy of two wagons and four guards, with a fifth less. */
        PERILOUS("perilous", 6, 0.4, 3),

        /** No wagon goes but with an escort. */
        CLOSED("closed", 9, 0.25, 3);

        /** Troubled or worse: the board posts its notes and the townsfolk grumble. */
        val troubled: Boolean get() = this >= TROUBLED

        companion object {
            fun of(peril: Int): Stage = entries.last { peril >= it.least }
        }
    }

    /** The most peril a road can have. */
    const val MOST = 12

    /** What waylaying a wagon, beating a band off with the guards alone or with a player's help, and seeing one in under escort does to its road. */
    const val WAYLAID = 3
    const val BEATEN_OFF = 1
    const val HELPED = -2
    const val ESCORTED = -3

    /** A Perilous road's convoy: its wagons, its guards, and the share of what its wagons would have carried that it takes. */
    const val CONVOY_WAGONS = 2
    const val CONVOY_GUARDS = 4
    const val CONVOY_SHARE = 0.8

    /** Clock ticks with no strike on a road before it starts to fade, a point each morning. */
    private const val QUIET = 2 * 24000L

    /** Road news kept per capital for the crier. */
    private const val NEWS = 16

    fun stage(peril: Int): Stage = Stage.of(peril)

    /** The stage of [road] into the capital whose heart is [capital]. */
    fun stage(data: CaravanData, capital: Long, road: Int): Stage = Stage.of(data.roadOrNull(capital, road)?.peril ?: 0)

    /** [road]'s peril moves by [by] at market [hour]; a worse stage, or the road clear again, is news for the crier. */
    fun change(data: CaravanData, capital: Long, road: Int, by: Int, hour: Long) =
        set(data, capital, road, data.road(capital, road).peril + by, hour)

    /** [road]'s peril is [peril] from market [hour] (for the commands, a broken band, and the fading). */
    fun set(data: CaravanData, capital: Long, road: Int, peril: Int, hour: Long) {
        val rec = data.road(capital, road)
        val before = Stage.of(rec.peril)
        rec.peril = peril.coerceIn(0, MOST)
        val after = Stage.of(rec.peril)
        data.changed()
        if (after == before) return
        when {
            after > before -> tell(data, capital, after.id, road, hour, band(rec.band))
            after == Stage.OPEN -> tell(data, capital, "clear", road, hour, band(rec.band))
        }
        rec.told = after.ordinal
    }

    /** The words the crier names [band] by, if it's known by a name yet. */
    private fun band(id: Int): List<String> {
        if (id < 0) return listOf()
        val level = Caravans.overworld() ?: return listOf()
        val band = OutlawCamps.get(level).band(id)?.takeIf { it.built || it.named } ?: return listOf()
        return listOf("${Outlaws.NAME_KEY}.${band.name}", "${Outlaws.EPITHET_KEY}.${band.epithet}")
    }

    /** A piece of road news at market [hour] for the capital whose heart is [capital]: [kind] on [road], naming [names]. */
    fun tell(data: CaravanData, capital: Long, kind: String, road: Int, hour: Long, names: List<String> = listOf()) {
        val cap = data.capital(capital, hour)
        cap.news.add(RoadNews(hour, kind, road, names))
        while (cap.news.size > NEWS) cap.news.removeAt(0)
        data.changed()
    }

    /** Each morning (market [hour], six o'clock): a road two days or more without a strike fades a point. */
    fun morning(data: CaravanData, capital: Long, hour: Long) {
        val day = Math.floorDiv(hour, 24L)
        val t = hour * 1000L
        for (rec in data.roads.filter { it.capital == capital && it.peril > 0 }) {
            if (t - rec.struck < io.github.vaspyyy.guildmark.economy.works.WorksWatch.quiet(capital, rec.road, QUIET) || rec.faded == day) continue
            rec.faded = day
            set(data, capital, rec.road, rec.peril - 1, hour)
        }
    }

    /**
     * [band]'s captain has fallen: every road it worked goes back to open, the waylaid wagons' trouble in those
     * markets ends (its good news cried with the rest), a relief wagon sets out at once with up to two days' imports,
     * and what the band carried off lies marked in a pile at its camp.
     */
    fun broken(level: ServerLevel, band: OutlawBand) {
        val data = CaravanData.get(level)
        val hour = Markets.hourOf(level)
        val worked = Caravans.worked(level, data, band)
        val names = this.band(band.id)
        for ((capital, roads) in worked) {
            for (road in roads) {
                val rec = data.road(capital, road)
                rec.pending = false
                set(data, capital, road, 0, hour)
                rec.band = -1
            }
            val entry = Markets.get(level)[BlockPos.of(capital)] ?: continue
            Caravans.overWaylaid(entry.market)
            // The crier and the townsfolk name the band that was broken
            if (Caravans.relief(level, data, entry, roads.first())) tell(data, capital, "relief", roads.first(), hour, names)
        }
        Caravans.pile(level, data, band)
        data.changed()
    }

    /** [band] gave up its camp before it was ever built: its roads' trouble ends as if it had moved on, with no relief and no pile. */
    fun gaveUp(level: ServerLevel, band: OutlawBand) {
        val data = CaravanData.get(level)
        val hour = Markets.hourOf(level)
        for ((capital, roads) in Caravans.worked(level, data, band)) {
            for (road in roads) {
                val rec = data.road(capital, road)
                rec.pending = false
                set(data, capital, road, 0, hour)
                rec.band = -1
            }
            Markets.get(level)[BlockPos.of(capital)]?.let { Caravans.overWaylaid(it.market) }
        }
        data.bands.removeIf { it.id == band.id }
        data.changed()
    }
}
