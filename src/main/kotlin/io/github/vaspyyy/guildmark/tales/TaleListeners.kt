package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.homestead.plan.Stage as BuildStage
import io.github.vaspyyy.guildmark.townsfolk.Household
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * The listeners that carry what other systems hear to the tales (living-world/stories/design.md 10.0 and 10.1): the households
 * come and go (through [Tales], which is the comings' listener), the wagons are struck on the road, the works go up. Each is
 * guarded by its system, one volatile read when tales are off, and changes nothing the system does.
 */
internal object TaleListeners {
    /** A household [what] ("arrived", "left" or "returned"): the engine's handling of a cast member's going first, then the tale hears of it. */
    fun household(level: ServerLevel, what: String, h: Household) {
        if (!Tales.live) return
        if (what == "left") TaleGone.left(level, h)
        TaleEvents.tell(level, Event.Household(what, h))
    }

    /**
     * The road: a strike on one of the town's own wagons (a wagon or a convoy; not a relief wagon, nor a test) is told to the
     * tale of the capital it was bound for, and a waylaid one is also kept in the town's list of the last days' strikes, for the
     * tales that read them (the wagon, the informer). The record has the road and the run; the listener's view carries neither.
     */
    object Road : Caravans.Listener {
        override fun struck(level: ServerLevel, caravan: Caravans.View, strike: Caravans.Strike) {
            if (!Tales.live) return
            val rec = CaravanData.get(level).caravan(caravan.id) ?: return
            if (rec.run != Run.WAGON && rec.run != Run.CONVOY) return
            val day = TaleEngine.today(level, rec.capital)
            if (strike.waylaid) {
                val data = TaleData.get(level)
                val town = data.town(rec.capital)
                town.waylaid.add(TaleData.Waylaid(rec.id, strike.band, rec.road, day))
                TaleKeep.trimTown(town, day)
                data.changed()
            }
            TaleEvents.tell(level, Event.Wagon(rec.capital, rec.id, strike.band, rec.road, strike.waylaid, day))
        }
    }

    /** The works: every step of a works' life is told to the tale of its capital, which reads the [WorksView] (the moot reads `funded`, `removed` and `finished`). */
    object Building : Works.Listener {
        override fun wanted(level: ServerLevel, w: WorksView) = told(level, "wanted", w)

        override fun pledged(level: ServerLevel, w: WorksView, player: UUID, marks: Int) = told(level, "pledged", w, player, marks)

        override fun funded(level: ServerLevel, w: WorksView) = told(level, "funded", w)

        override fun begun(level: ServerLevel, w: WorksView) = told(level, "begun", w)

        override fun staged(level: ServerLevel, w: WorksView, stage: BuildStage) = told(level, "staged", w)

        override fun finished(level: ServerLevel, w: WorksView) = told(level, "finished", w)

        override fun eased(level: ServerLevel, w: WorksView, what: String) = told(level, "eased", w)

        override fun removed(level: ServerLevel, w: WorksView) = told(level, "removed", w)

        override fun brought(level: ServerLevel, w: WorksView, player: UUID, units: Int) = told(level, "brought", w, player, units)

        private fun told(level: ServerLevel, what: String, w: WorksView, player: UUID? = null, marks: Int = 0) {
            if (!Tales.live) return
            TaleEvents.tell(level, Event.Works(what, w, player, marks))
        }
    }
}
