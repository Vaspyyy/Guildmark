package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.townsfolk.Lookout
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/** The works at their work (design section 3, "Afterwards"): the flue, the granary's doors, the beacon, the awning. */
internal object WorksAtWork {
    /** The towers' beacons, for the lookouts. */
    object Beacon : Lookout.Beacon {
        override fun at(level: ServerLevel, w: WorksView): BlockPos? = null

        override fun light(level: ServerLevel, w: WorksView, by: Townsperson) {}
    }

    fun everyTick(level: ServerLevel) {}

    /** Each second of a standing works whose land is loaded. */
    fun second(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece, near: Boolean) {}

    fun reset() {}

    /** The tower's beacon has its first fire at its topping out (lit before them all if [near]). */
    fun firstFire(level: ServerLevel, rec: BuildRec, site: TownWorksSite, near: Boolean) {}

    /** [player] uses a stall's counter at [pos]. */
    fun usePitch(level: ServerLevel, player: ServerPlayer, pos: BlockPos) {}
}
