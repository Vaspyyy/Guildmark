package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * Grumbling at the board (living-world/design-influence.md section 5): in LEAN or HARD times, passers-by stop at the
 * market board, stand a while taking in what's chalked up on it (hand at the chin, a tilt of the head: ADMIRE),
 * and grumble (GRUMBLE), saying something of the times ("Hard times. Everyone's pinching."), then sigh or shrug and
 * go on; anyone else at the board nods along. Two at the board at most, one stopping every quarter of a minute, and
 * nobody twice in a while.
 */
internal object Grumbling {
    /** How near the board a passer-by must be to stop at it, and how near a player must be to the board. */
    private const val NEAR = 20.0
    private const val SEEN = 48.0

    /** The most at the board at once, the fewest ticks between one stopping and the next, and before the same one stops again. */
    private const val MOST = 2
    private const val EVERY = 300L
    private const val AGAIN = 6000L

    private class Board {
        val members = LinkedHashSet<Townsperson>()
        val spots = HashMap<Townsperson, BlockPos>()
        var last = Long.MIN_VALUE / 2
    }

    private val boards = HashMap<Long, Board>()
    private val stopped = WeakHashMap<Townsperson, Long>()

    /** Once a second for each player in a capital in lean or hard times: a passer-by may stop at its board. */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) {
        val times = Fortunes.times(level, here.key)
        if (times != Times.LEAN && times != Times.HARD) return
        val board = here.places.marketBoard.pos
        if (player.distanceToSqr(Vec3.atCenterOf(board)) > SEEN * SEEN) return
        start(level, here, times, force = false)
    }

    /** Someone passing [here]'s board stops at it and grumbles at [times]; with [force], whatever the count. Who did. */
    fun start(level: ServerLevel, here: Scenes.Here, times: Times, force: Boolean): Townsperson? {
        val now = level.gameTime
        val b = boards.getOrPut(here.key) { Board() }
        b.members.removeIf { !it.isAlive || it.isRemoved || it.director == null }
        b.spots.keys.retainAll(b.members)
        if (!force && (b.members.size >= MOST || now - b.last < EVERY)) return null
        val board = here.places.marketBoard
        val face = Vec3.atCenterOf(board.pos).add(0.0, 0.8, 0.0)
        val p = level.getEntitiesOfClass(Townsperson::class.java, AABB(board.pos).inflate(NEAR, 6.0, NEAR)) {
            it.role.adult && it.role != TownRole.GUARD && it !in b.members && Aside.free(it) &&
                (force || now - (stopped[it] ?: Long.MIN_VALUE / 2) >= AGAIN)
        }.minByOrNull { it.distanceToSqr(face) } ?: return null
        val spot = spot(level, b, board.pos, board.facing, p) ?: return null
        val other = b.members.firstOrNull()
        val r = p.random
        val fallback = if (times == Times.HARD) "fortune.hard" else "fortune.lean"
        val moves = ArrayList<Move>()
        moves += GoTo({ spot }, Jobs.STROLL, near = 0.8, most = 400)
        moves += Turn({ face })
        moves += Play(Activity.ADMIRE, 80 + r.nextInt(60), { face })
        if (other != null && other.isAlive && other.distanceToSqr(p) < 25.0) {
            // Someone at it already: a look at them and a nod, the same thought
            moves += Play(Activity.NOD, at = { other.eyePosition })
        }
        moves += Play(Activity.GRUMBLE, at = { face }, first = { who -> Words.about(level, who, Kind.FORTUNE, fallback, "grumbling at the market board") { it.tone <= 0 } })
        moves += Play(if (r.nextInt(2) == 0) Activity.SIGH else Activity.SHRUG, at = { face })
        val aside = Aside(moves, most = 900)
        if (!Aside.take(p, aside)) return null
        b.members += p
        b.spots[p] = spot
        b.last = now
        stopped[p] = now
        aside.onEnd = {
            b.members.remove(p)
            b.spots.remove(p)
        }
        return p
    }

    /**
     * Where [p] stands to read the board at [at] (facing [facing]): two or three steps out in front of it, a little to
     * one side, clear of the crier's place in the middle and of anyone reading it already. Null if there's no ground.
     */
    private fun spot(level: ServerLevel, b: Board, at: BlockPos, facing: net.minecraft.core.Direction, p: Townsperson): BlockPos? {
        val side = facing.clockWise
        val offsets = listOf(2 to -2, 2 to 2, 3 to -1, 3 to 1, 2 to -3, 2 to 3).shuffled(java.util.Random(p.random.nextLong()))
        for ((out, across) in offsets) {
            val base = at.relative(facing, out).relative(side, across)
            for (dy in intArrayOf(0, 1, -1)) {
                val pos = base.above(dy)
                if (!level.isLoaded(pos) || !Places.standable(level, pos)) continue
                if (b.spots.values.any { it.distManhattan(pos) <= 1 }) continue
                return pos
            }
        }
        return null
    }

    fun forget() {
        boards.clear()
        stopped.clear()
    }
}
