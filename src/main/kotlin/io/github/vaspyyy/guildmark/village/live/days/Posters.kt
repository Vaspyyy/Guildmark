package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Who pins the village's note on the board (living-world/villages/design.md 5.4 and 6.5): once a day one resident with an
 * errand walks to the board with a note and nails it up. Which resident is the day's seed's to say, the same all day.
 */
object Posters {
    /**
     * The resident id who pins the note of the village at [centre] on town day [day], or null if it has no one to: one of the
     * grown-up folk who are not of the watch, in the order the day's seed gives them, the holder of the bell last (they have
     * their own errand at dusk).
     */
    fun pick(level: ServerLevel, centre: BlockPos, day: Long): Int? = pick(Residents.get(level).byCentre(centre), centre, day)

    fun pick(folk: List<Resident>, centre: BlockPos, day: Long): Int? {
        val key = centre.asLong()
        val bell = BellKeeper.list(folk, centre, day).firstOrNull()?.id
        val able = folk.filter { it.alive && it.id >= 0 && it.role.adult && it.role != TownRole.GUARD }
        return able.sortedWith(compareBy<Resident>({ if (it.id == bell) 1 else 0 }, { BellKeeper.rank(day, key xor 0x5DEECE66DL, it.id) }, { it.id })).firstOrNull()?.id
    }
}
