package io.github.vaspyyy.guildmark.village.live.folk

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import java.util.UUID

/**
 * The test of living-world/villages/design.md section 6.2 that leaves a trading hall alone: players keep villagers on purpose (a
 * hall's cells, a breeder, an iron farm's pen, a villager in a boat on its way) and a townsperson would walk out of them. So each
 * candidate is asked once whether it could get away: a search over what a villager can walk, from its feet, over at most [CELLS]
 * cells (open air above a floor; a step of one block up or down; wooden doors passable; iron doors, fences, walls, fence gates,
 * closed trapdoors and water not), for a cell [FAR] blocks from where it stands, across the ground at any height. If it finds none
 * the villager is **confined** and stays a vanilla villager, and so do the beds and job blocks it holds.
 *
 * The search goes outwards a ring at a time (the design had it depth first; going outwards finds a way out in the fewest cells, so
 * four hundred is enough for a street), and it never loads a chunk: a cell in a chunk that is not loaded is open ground, the way out.
 *
 * It is a simple test, and where it is wrong it is wrong on the side of leaving a villager alone, except for one case: a walled
 * hall more than ten blocks across counts as open, and a villager in one is adopted (a nametag, the `guildmark_keep_vanilla` tag or
 * `village release` puts it back). A search that spends its four hundred cells without getting away counts as confined.
 */
object Confined {
    /** The most cells a search takes in, and how far across the ground a villager must be able to get. */
    const val CELLS = 400
    const val FAR = 10

    /** How long a verdict is believed, in ticks: a hall opened up is looked at again in a minute. */
    private const val KEEP = 1200L

    private class Verdict(val at: Long, val confined: Boolean)

    private val verdicts = HashMap<UUID, Verdict>()

    /** Whether the villager [who] standing at [feet] is confined, from a verdict made less than a minute ago if there is one. */
    fun confined(level: ServerLevel, who: UUID, feet: BlockPos): Boolean {
        val now = level.gameTime
        val old = verdicts[who]
        if (old != null && now >= old.at && now - old.at < KEEP) return old.confined
        val confined = !canGetAway(level, feet)
        if (verdicts.size > 2048) verdicts.clear()
        verdicts[who] = Verdict(now, confined)
        return confined
    }

    /** Forgets the verdicts (when the server stops). */
    fun forget() = verdicts.clear()

    /** Whether a villager standing at [start] can walk to a cell [FAR] blocks away across the ground. */
    fun canGetAway(level: ServerLevel, start: BlockPos): Boolean {
        val tried = HashSet<Long>()
        val queue = ArrayDeque<BlockPos>()
        tried.add(start.asLong())
        queue.add(start)
        var taken = 0
        val far = FAR * FAR
        while (queue.isNotEmpty() && taken < CELLS) {
            val p = queue.removeFirst()
            taken++
            for (dir in Direction.Plane.HORIZONTAL) {
                for (dy in STEPS) {
                    val q = p.offset(dir.stepX, dy, dir.stepZ)
                    if (!tried.add(q.asLong())) continue
                    // Past the edge of what is loaded is open ground: nothing is loaded to find out
                    if (!level.isLoaded(q) || !level.isLoaded(q.above(2))) return true
                    if (!fits(level, p, q, dy)) continue
                    val dx = q.x - start.x
                    val dz = q.z - start.z
                    if (dx * dx + dz * dz >= far) return true
                    queue.add(q)
                }
            }
        }
        return false
    }

    /** Whether a villager at [p] can step to [q], [dy] above or below (0, 1 or -1): room for its body there and in the way, and a floor. */
    private fun fits(level: ServerLevel, p: BlockPos, q: BlockPos, dy: Int): Boolean {
        if (!passable(level, q) || !passable(level, q.above())) return false
        // Up a step the head clears the cell above; down one it clears the cell beside
        if (dy > 0 && !passable(level, p.above(2))) return false
        if (dy < 0 && !passable(level, q.above(2))) return false
        // A floor: something to stand on below, or a carpet or a snow layer that is the floor itself
        return !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty ||
            !level.getBlockState(q).getCollisionShape(level, q).isEmpty
    }

    /** Whether a villager can be in the cell [pos]: air, or a block it steps over, or a wooden door (it opens those). */
    private fun passable(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.block is DoorBlock) return state.`is`(BlockTags.WOODEN_DOORS)
        if (state.block is FenceGateBlock) return false
        val shape = state.getCollisionShape(level, pos)
        if (shape.isEmpty) return state.fluidState.isEmpty
        return shape.max(Direction.Axis.Y) <= STEP
    }

    private val STEPS = intArrayOf(0, 1, -1)

    /** How high a block may be and still be walked over (a villager's step). */
    private const val STEP = 0.6
}
