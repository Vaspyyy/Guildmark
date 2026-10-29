package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.build.TownWorksSite
import net.minecraft.server.level.ServerLevel

/** The House's yard and map while a player is near (build part G): what the House's run tells it. Filled in by the yard's own commit. */
object YardShow {
    fun register() {}

    internal fun second(level: ServerLevel, w: WorksRec, site: TownWorksSite, near: Boolean) {}

    fun hired(level: ServerLevel, h: HouseRec, c: Carter) {}

    fun left(level: ServerLevel, h: HouseRec, c: Carter) {}

    fun loading(level: ServerLevel, h: HouseRec, c: Carter, trip: Trip) {}

    fun departed(level: ServerLevel, h: HouseRec, trip: Trip) {}

    fun settled(level: ServerLevel, h: HouseRec, trip: Trip) {}

    fun back(level: ServerLevel, h: HouseRec, trip: Trip, c: Carter?) {}
}
