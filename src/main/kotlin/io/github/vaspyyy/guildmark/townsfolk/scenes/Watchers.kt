package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Watchers (living-world/design-influence.md section 5): townsfolk passing within [NEAR] blocks of a works going up
 * stop a while to watch it. Each steps back off its lot on the side they came by, turns to it, takes it in (GAZE),
 * points at where the work's going on (POINT: as high as it's got) and stands with their arms folded (ARMS_FOLDED)
 * before going on their way. The first of a group says something of it (the talk's works topics: "Hear that? They're
 * at the granary."); the others nod along. A few at a time at each works, one stopping every few seconds at most,
 * and nobody twice in a while.
 */
internal object Watchers {
    /** How near a works someone must pass to stop and watch it, and how near a player must be to the works. */
    private const val NEAR = 12.0
    private const val SEEN = 64.0

    /** The most watching one works at once, and the fewest ticks between one stopping and the next. */
    private const val MOST = 3
    private const val EVERY = 160L

    /** The fewest ticks before the same person stops to watch again. */
    private const val AGAIN = 6000L

    /** A works as its watchers see it: its id, its lot, and how far up it's got (0 to 1). */
    class Site(val id: Int, val lot: BoundingBox, val progress: Double)

    private class Watching {
        val members = LinkedHashSet<Townsperson>()
        var last = Long.MIN_VALUE / 2
    }

    private val watching = HashMap<Int, Watching>()
    private val stopped = WeakHashMap<Townsperson, Long>()

    fun site(w: WorksView): Site? {
        val lot = w.site?.lot ?: return null
        return Site(w.id, lot, if (w.total > 0) (w.laid.toDouble() / w.total).coerceIn(0.0, 1.0) else 0.5)
    }

    /** Once a second for each player in a capital: someone passing a works going up near them may stop to watch it. */
    fun look(level: ServerLevel, player: ServerPlayer, here: Scenes.Here) {
        for (w in Scenes.works(level, here.key)) {
            if (w.state != WorksState.BUILDING) continue
            val site = site(w) ?: continue
            if (player.distanceToSqr(Vec3.atCenterOf(site.lot.center)) > SEEN * SEEN) continue
            start(level, site, force = false)
        }
    }

    /** Someone passing [site] stops to watch it, if anyone's free to; with [force], whatever the count. The one who stopped. */
    fun start(level: ServerLevel, site: Site, force: Boolean): Townsperson? {
        val now = level.gameTime
        val group = watching.getOrPut(site.id) { Watching() }
        group.members.removeIf { !it.isAlive || it.isRemoved || it.director == null }
        if (!force && (group.members.size >= MOST || now - group.last < EVERY)) return null
        val lot = site.lot
        val center = Vec3.atCenterOf(lot.center)
        val box = AABB.of(lot).inflate(NEAR, 4.0, NEAR)
        val p = level.getEntitiesOfClass(Townsperson::class.java, box) {
            it.role.adult && it.role != TownRole.GUARD && !lot.isInside(it.blockPosition()) && Aside.free(it) &&
                (force || now - (stopped[it] ?: Long.MIN_VALUE / 2) >= AGAIN)
        }.minWithOrNull(compareBy<Townsperson>({ if (it.resident >= 0) 0 else 1 }, { it.distanceToSqr(center) })) ?: return null
        val spot = vantage(level, p, lot) ?: p.blockPosition()
        val talker = group.members.firstOrNull()
        val aside = Aside(part(level, p, site, spot, talker), most = 1200)
        if (!Aside.take(p, aside)) return null
        group.members += p
        group.last = now
        stopped[p] = now
        aside.onEnd = { group.members.remove(p) }
        return p
    }

    /** Where the work's going on, to look and point at: over the lot's middle, as high as it's got. */
    private fun aim(site: Site): Vec3 {
        val lot = site.lot
        return Vec3((lot.minX() + lot.maxX() + 1) / 2.0, lot.minY() + 1.0 + (lot.getYSpan() - 1) * site.progress * 0.9, (lot.minZ() + lot.maxZ() + 1) / 2.0)
    }

    /** [p]'s part: back off the lot, a look, a point and (the first of them) a word, arms folded a while, and on their way. */
    private fun part(level: ServerLevel, p: Townsperson, site: Site, spot: BlockPos, talker: Townsperson?): List<Move> {
        val aim = { aim(site) }
        val r = p.random
        val moves = ArrayList<Move>()
        moves += GoTo({ spot }, Jobs.STROLL, near = 1.0, most = 400)
        moves += Turn(aim)
        moves += Play(Activity.GAZE, Activity.GAZE.ticks * (1 + r.nextInt(2)), aim)
        moves += Play(Activity.POINT, at = aim, first = { who ->
            // With no works topic on offer, only the wording true at any stage ("It'll be a fine sight when it's done.")
            if (talker == null) Words.about(level, who, Kind.WORKS, "works.building", "watching the works go up", takes = setOf(1))
        })
        if (talker != null && talker.isAlive && talker.distanceToSqr(p) < 64.0) {
            moves += Play(Activity.NOD, at = { talker.eyePosition })
        }
        moves += Play(Activity.ARMS_FOLDED, Activity.ARMS_FOLDED.ticks * (2 + r.nextInt(2)), aim)
        if (r.nextInt(2) == 0) moves += Play(Activity.GAZE, Activity.GAZE.ticks, aim)
        return moves
    }

    /** A place [p] can stand to watch [lot] from: 6 to 9 blocks off its edge on their side, off the lot, on the ground. */
    private fun vantage(level: ServerLevel, p: Townsperson, lot: BoundingBox): BlockPos? {
        val nx = p.x.coerceIn(lot.minX().toDouble(), lot.maxX() + 1.0)
        val nz = p.z.coerceIn(lot.minZ().toDouble(), lot.maxZ() + 1.0)
        var dx = p.x - nx
        var dz = p.z - nz
        var len = sqrt(dx * dx + dz * dz)
        if (len < 0.5) {
            dx = p.x - (lot.minX() + lot.maxX() + 1) / 2.0
            dz = p.z - (lot.minZ() + lot.maxZ() + 1) / 2.0
            len = sqrt(dx * dx + dz * dz)
            if (len < 0.5) {
                dx = 1.0
                dz = 0.0
                len = 1.0
            }
        }
        val ux = dx / len
        val uz = dz / len
        repeat(4) {
            val back = 6 + p.random.nextInt(4)
            val side = p.random.nextInt(7) - 3
            val x = floor(nx + ux * back - uz * side).toInt()
            val z = floor(nz + uz * back + ux * side).toInt()
            for (dy in intArrayOf(0, 1, -1, 2, -2, 3, -3)) {
                val pos = BlockPos(x, p.blockY + dy, z)
                if (lot.isInside(pos) || !level.isLoaded(pos)) continue
                if (Places.standable(level, pos)) return pos
            }
        }
        return null
    }

    fun forget() {
        watching.clear()
        stopped.clear()
    }
}
