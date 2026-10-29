package io.github.vaspyyy.guildmark.village.live.links

import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What the villagers at the road end have come out to meet. */
enum class GreetWhy {
    /** A capital's wagon coming in. */
    WAGON,

    /** The pedlar and their cart. */
    PEDLAR,
}

/**
 * Hands out the villagers who go to the road end to meet a cart or a wagon, wave and count the sacks (living-world/
 * villages/design.md section 9.4). The name and signature are the contract of design section 19.3: part V7 makes the
 * greeters, who are directors of their own ([Greeter]).
 *
 * Up to [MOST] of the village's own folk with nothing pressing ([Aside.free]: up and about, in nobody's charge, idle enough to
 * break off) within [SEEK] blocks come, the nearest first, children too (the grown-ups have the words); not its watch, and one
 * gathering at a time for a village. They stand in an arc on the village's side of the road end and face the road.
 */
object Greeters {
    /** The most folk who come out, and how far (blocks) from the road end they may be. */
    const val MOST = 5
    const val SEEK = 48.0

    private val gatherings = HashMap<Long, Gathering>()

    /**
     * Calls up to five villagers of the village [key] with nothing pressing to [at], for [why]: for a wagon, the village's wagon due
     * soonest ([SendOff.watch]: one coming back, else one setting out; none due and nobody comes); for the pedlar, the pedlar walking
     * in past [at].
     */
    fun call(level: ServerLevel, key: Long, at: BlockPos, why: GreetWhy) {
        val watch: Watch = when (why) {
            GreetWhy.WAGON -> SendOff.watch(level, key) ?: return
            GreetWhy.PEDLAR -> if (VillageMarkets.pedlars()) PedlarWatch(at, level.gameTime) else return
        }
        gather(level, key, at, Vec3.atCenterOf(at), why, watch, quiet = false, exclude = setOf())
    }

    /**
     * The folk of [key] called to [at] facing [road] to wait on [watch] (the carters' scene's own, [SendOff]); [quiet] while the
     * carters have the words; [exclude] are residents who are busy with it already. Null if nobody comes.
     */
    internal fun gather(
        level: ServerLevel, key: Long, at: BlockPos, road: Vec3, why: GreetWhy, watch: Watch, quiet: Boolean, exclude: Set<Int>,
    ): Gathering? {
        gatherings.values.removeIf { it.done }
        if (gatherings.containsKey(key)) return null
        val c = Vec3.atCenterOf(at)
        val folk = Residents.get(level).byCentre(BlockPos.of(key)).asSequence()
            .filter { it.alive && it.id !in exclude }
            .mapNotNull { r -> r.uuid?.let { level.getEntity(it) as? Townsperson } }
            .filter { it.role != TownRole.GUARD && it.distanceToSqr(c) < SEEK * SEEK && level.isPositionEntityTicking(it.blockPosition()) && Aside.free(it) }
            .sortedBy { it.distanceToSqr(c) }
            .take(MOST)
            .toList()
        if (folk.isEmpty()) return null
        val gathering = Gathering(key, at, road, why, watch, quiet, level.gameTime)
        val spots = spots(level, at, road, folk.size)
        val speaker = folk.firstOrNull { it.role.adult }
        for ((i, p) in folk.withIndex()) {
            val greeter = Greeter(gathering, p, spots.getOrElse(i) { at }, p === speaker, i * 6)
            p.standUp()
            p.routine.interrupt()
            p.navigation.stop()
            p.director = greeter
            gathering.folk += greeter
        }
        gatherings[key] = gathering
        return gathering
    }

    /** Whether some of the village [key]'s folk are out at the road end now (for `village links`). */
    fun out(key: Long): Int = gatherings[key]?.folk?.count { !it.gone } ?: 0

    /** Everyone let go (the server stopping). */
    fun forget() {
        for (g in gatherings.values) g.release()
        gatherings.clear()
    }

    /** Places for [n] to stand in an arc a few blocks back from [at], on the side away from [road]: ground, one each. */
    private fun spots(level: ServerLevel, at: BlockPos, road: Vec3, n: Int): List<BlockPos> {
        var ux = at.x + 0.5 - road.x
        var uz = at.z + 0.5 - road.z
        val len = hypot(ux, uz)
        if (len < 0.5) {
            ux = 1.0
            uz = 0.0
        } else {
            ux /= len
            uz /= len
        }
        val out = ArrayList<BlockPos>()
        for (ring in 0 until 3) {
            val r = 2.5 + ring * 1.5
            for (a in ARC) {
                val x = ux * cos(a) - uz * sin(a)
                val z = ux * sin(a) + uz * cos(a)
                val spot = SendOff.ground(level, BlockPos.containing(at.x + 0.5 + x * r, at.y + 0.5, at.z + 0.5 + z * r)) ?: continue
                if (spot != at && spot !in out) out += spot
                if (out.size >= n) return out
            }
        }
        return out
    }

    /** The arc's turns (radians) from straight back. */
    private val ARC = doubleArrayOf(0.0, 0.6, -0.6, 1.2, -1.2)
}
