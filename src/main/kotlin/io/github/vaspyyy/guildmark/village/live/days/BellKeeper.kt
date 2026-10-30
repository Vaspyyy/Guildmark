package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Step
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.Work
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BellAttachType
import java.util.Random

/**
 * Who holds a village's bell, and the dusk bell (living-world/villages/design.md 5.4): one resident for the week, picked by the week's
 * seed from the grown-up folk who are not of the watch, those who are [Trait.DEVOUT] or [Trait.CHATTY] first. The same list, in the
 * same order, all week: if the holder dies or leaves, the next on it holds the bell from then on. At dusk (half past five, a stretch
 * of their day from five) the holder goes to the bell, waits for the hour and strikes it twice, once a day however many hold it in
 * turn. The holder is for the other parts too ([holder]): whoever calls from the bell, or rings it hard, is the holder.
 */
object BellKeeper {
    /**
     * The hour the dusk bell rings, and the strokes of it (ticks into the ringing): on the strikes of the hand at the bell, tick 6 of
     * each 30 of the strike's clip (ring_great_bell, art/scripts/village_clips.py), two of them.
     */
    const val DUSK = 17.5
    private const val FIRST = 6
    private const val SECOND = 36
    private const val RINGING = 60

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

    // --- The dusk bell ---

    /** The town day each village's (by its centre) dusk bell last rang, in memory: it rings once a day. */
    private val rung = HashMap<Long, Long>()

    /** The town day the dusk bell of the village at [centre] last rang, or [Long.MIN_VALUE] if it has not since the game began. */
    fun rungOn(centre: BlockPos): Long = rung[centre.asLong()] ?: Long.MIN_VALUE

    /** Forgets the days the bells rang (the server stopping: another world's villages may stand at the same places). */
    fun forget() = rung.clear()

    /** The clock's hour as [Days] reckons it (17.5 is half past five). */
    private fun hour(level: ServerLevel): Double = (Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0

    /**
     * [p]'s errand to the bell (the bellman's stretch): out to a place by it, a look about until [DUSK], two strokes of the bell with
     * the bellman's arm, and a moment after. Null when the bell is gone, it has rung today or there is nowhere to stand by it
     * ([now]: ring it at once, whatever the hour and whether it has rung).
     */
    fun errand(p: Townsperson, life: Life, now: Boolean = false): Task? {
        val level = p.level() as? ServerLevel ?: return null
        val bell = life.places.bell
        if (!level.isLoaded(bell) || level.getBlockState(bell).block !is BellBlock) return null
        val key = life.places.centre.asLong()
        val day = Days.dayOf(level.overworldClockTime)
        if (!now && rung[key] == day) return null
        val spot = standAt(level, bell, p.blockPosition(), p) ?: return null
        var base = -1
        val plan = { i: Int ->
            if (base < 0 && !now && hour(level) < DUSK) {
                Step(Activity.NONE, 100)
            } else {
                if (base < 0) base = i
                when (i - base) {
                    // The bell's own strokes, not a handbell (Townsperson.greatBell)
                    0 -> Step(Activity.RING_BELL, RINGING).also { p.greatBell = true }
                    1 -> Step(Activity.NONE, 30)
                    else -> null
                }
            }
        }
        return Work(p, spot, Jobs.top(bell, 0.4), plan, onTick = { s, t ->
            if (s.activity == Activity.RING_BELL && (t == FIRST || t == SECOND)) ring(level, bell, key, day, spot.facing)
        })
    }

    /** One stroke of the bell at [bell]. */
    private fun ring(level: ServerLevel, bell: BlockPos, key: Long, day: Long, from: Direction) {
        rung[key] = day
        (level.getBlockState(bell).block as? BellBlock)?.attemptToRing(level, bell, from)
    }

    /**
     * Where to stand to ring the bell at [bell]: a block beside it (under it, if it hangs high), the nearest to [from] of the nearest,
     * and before any other one straight off a side the hand strikes it from as a vanilla bell is struck ([struckFrom]): not through a
     * post of its frame or into the bar it hangs from.
     */
    internal fun standAt(level: ServerLevel, bell: BlockPos, from: BlockPos, who: Entity): Spot? {
        val state = level.getBlockState(bell)
        var best: Spot? = null
        var bestScore = Double.MAX_VALUE
        for (dy in 0 downTo -5) for (dx in -2..2) for (dz in -2..2) {
            val across = dx * dx + dz * dz
            if (across == 0) continue
            val pos = bell.offset(dx, dy, dz)
            if (!Places.standable(level, pos) || !Claims.free(level, pos, who)) continue
            val score = across * 10.0 - dy * 4.0 + pos.distSqr(from) / 100.0 + if (struckFrom(state, dx, dz)) 0.0 else ASIDE
            if (score < bestScore) {
                bestScore = score
                best = Spot(pos, Direction.getApproximateNearest(-dx.toDouble(), 0.0, -dz.toDouble()))
            }
        }
        return best
    }

    /** What a stand off a side the bell is not struck from costs, in [standAt]'s reckoning: more than a block farther off. */
    private const val ASIDE = 25.0

    /**
     * Whether the bell [state] is struck from a block [dx], [dz] off it, as vanilla's hit rings it: one standing between its posts
     * straight off its open face (a bell on the floor), straight off a side its bar does not run to (on a wall), from anywhere (hung
     * from above).
     */
    private fun struckFrom(state: BlockState, dx: Int, dz: Int): Boolean {
        if (state.block !is BellBlock) return true
        val attached = state.getValue(BellBlock.ATTACHMENT)
        if (attached == BellAttachType.CEILING) return true
        if (dx != 0 && dz != 0) return false
        val along = (if (dx != 0) Direction.Axis.X else Direction.Axis.Z) == state.getValue(BellBlock.FACING).axis
        return if (attached == BellAttachType.FLOOR) along else !along
    }
}
