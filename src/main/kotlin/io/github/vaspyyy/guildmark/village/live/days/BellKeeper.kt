package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.Random

/**
 * Who holds a village's bell (living-world/villages/design.md 5.4): one resident for the week, picked by the week's seed from
 * the grown-up folk who are not of the watch, those who are [Trait.DEVOUT] or [Trait.CHATTY] first. The same list, in the
 * same order, all week: if the holder dies or leaves, the next on it holds the bell from then on. They ring it at dusk
 * (a stretch of their day, 17:30), the bellman cries from it at noon on market days (the bellman's own part), and an alarm
 * has it rung hard.
 */
object BellKeeper {
    /** The grown-up folk of the village at [centre] who could hold its bell, the first on the week's list first. */
    fun list(level: ServerLevel, centre: BlockPos, day: Long): List<Resident> = list(Residents.get(level).byCentre(centre), centre, day)

    fun list(folk: List<Resident>, centre: BlockPos, day: Long): List<Resident> {
        val week = Math.floorDiv(day, 7L)
        val key = centre.asLong()
        return folk.filter { it.alive && it.id >= 0 && it.role.adult && it.role != TownRole.GUARD }
            .sortedWith(compareBy<Resident>({ if (it.has(Trait.DEVOUT) || it.has(Trait.CHATTY)) 0 else 1 }, { rank(week, key, it.id) }, { it.id }))
    }

    /** The resident id who holds the bell of the village at [centre] on town day [day], or null if it has no one to. */
    fun holder(level: ServerLevel, centre: BlockPos, day: Long): Int? = list(level, centre, day).firstOrNull()?.id

    /** A scrambled number for [id] under the seed [a] and the village [key]: the order they stand in. */
    fun rank(a: Long, key: Long, id: Int): Long = Random(a * -7046029254386353131L xor key * 0x632BE59BD9B4E019L xor id * 0x2545F491L).nextLong()
}
