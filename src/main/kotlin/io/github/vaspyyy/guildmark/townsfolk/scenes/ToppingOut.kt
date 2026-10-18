package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The topping out (living-world/design-influence.md section 3, "Ceremonies"), as the town sees it: when a works'
 * TOPPING stage begins (heard through [Works.listen]) with a player near enough for its crew to be there, idle folk
 * within [NEAR] blocks drift over by ones and twos, stand round it in a loose ring looking up at it, and clap and
 * cheer each time the master builder calls it (his HURRAH, the TOAST: seen on the lot, or told by the building
 * through [cheer]); the children jump about. After [WINDOW] ticks they go back to their day. The master builder and
 * the ceremony itself are the building's (Part B's).
 */
internal object ToppingOut : Works.Listener {
    /** How far idle folk drift over from, and how near a player must be (the crew's there only then). */
    private const val NEAR = 48.0
    private const val CREW = 72.0

    /** How long the gathering lasts, the most at it, and how many more drift over each second. */
    private const val WINDOW = 1800L
    private const val MOST = 12
    private const val EACH = 2

    /** Ticks after a cheer before another can be heard on the lot. */
    private const val CHEERS_APART = 60L

    private val CALLS = setOf(Activity.HURRAH, Activity.TOAST, Activity.FEST_HURRAH)

    class Gathering(val id: Int, val lot: BoundingBox, val until: Long) {
        val members = LinkedHashSet<Townsperson>()

        /** How many cheers have gone up, and when the last did. */
        var cue = 0
        var cued = Long.MIN_VALUE / 2
        var looked = Long.MIN_VALUE

        val center: Vec3 get() = Vec3((lot.minX() + lot.maxX() + 1) / 2.0, lot.minY().toDouble(), (lot.minZ() + lot.maxZ() + 1) / 2.0)

        /** Where to look: up at the top of the works. */
        val top: Vec3 get() = Vec3((lot.minX() + lot.maxX() + 1) / 2.0, lot.maxY() - 1.0, (lot.minZ() + lot.maxZ() + 1) / 2.0)
    }

    private val gatherings = HashMap<Int, Gathering>()

    override fun staged(level: ServerLevel, w: WorksView, stage: Stage) {
        if (stage != Stage.TOPPING || !Works.enabled()) return
        val lot = w.site?.lot ?: return
        if (!Scenes.seen(level, lot.center, CREW)) return
        start(level, w.id, lot)
    }

    /** The town gathers at the works [id] on [lot] for its topping out, from now for [WINDOW] ticks. */
    fun start(level: ServerLevel, id: Int, lot: BoundingBox): Gathering {
        gatherings[id]?.let { if (level.gameTime <= it.until) return it }
        gatherings.values.removeIf { level.gameTime > it.until + 200 }
        return Gathering(id, lot, level.gameTime + WINDOW).also { gatherings[id] = it }
    }

    /**
     * The building's master builder calls the topping out (his HURRAH) or raises the toast at works [works]: everyone
     * gathered claps and cheers now. Without it, a HURRAH or TOAST seen on the lot does as well.
     */
    fun cheer(level: ServerLevel, works: Int) {
        val g = gatherings[works] ?: return
        g.cue++
        g.cued = level.gameTime
    }

    /** Once a second for each player near a topping out: a cheer heard from the lot, and a couple more drifting over. */
    fun look(level: ServerLevel, player: ServerPlayer) {
        if (gatherings.isEmpty()) return
        val now = level.gameTime
        for (g in gatherings.values.toList()) {
            if (now > g.until) {
                end(g)
                continue
            }
            if (player.distanceToSqr(g.center) > CREW * CREW || g.looked == now) continue
            g.looked = now
            g.members.removeIf { !it.isAlive || it.isRemoved || it.director == null }
            hear(level, g)
            if (g.members.size >= MOST) continue
            val box = AABB.of(g.lot).inflate(NEAR, 8.0, NEAR)
            level.getEntitiesOfClass(Townsperson::class.java, box) { !g.lot.isInside(it.blockPosition()) && it.role != TownRole.GUARD && Aside.free(it) }
                .sortedBy { it.distanceToSqr(g.center) }.take(EACH).forEach { join(level, g, it) }
        }
    }

    /** A HURRAH or a TOAST just begun on the lot (the master builder's, when the building doesn't say so itself). */
    private fun hear(level: ServerLevel, g: Gathering) {
        val now = level.gameTime
        if (now - g.cued < CHEERS_APART) return
        val calling = level.getEntitiesOfClass(Townsperson::class.java, AABB.of(g.lot).inflate(3.0)) { it.activity in CALLS && it.actTicks < 25 && it !in g.members }
        if (calling.isNotEmpty()) {
            g.cue++
            g.cued = now
        }
    }

    /** [p] drifts over to [g] and stands in the ring; true if they came. */
    fun join(level: ServerLevel, g: Gathering, p: Townsperson): Boolean {
        val spot = ring(level, g, p) ?: p.blockPosition()
        val look = { g.top }
        val wait = p.random.nextInt(30)
        val aside = Aside(listOf(
            Wait({ _, t -> t >= wait }, wait + 1),
            GoTo({ spot }, Jobs.WALK, near = 1.2, most = 500),
            Turn(look),
            Gathered(g),
        ), most = (WINDOW + 600).toInt(), fresh = true)
        if (!Aside.take(p, aside)) return false
        g.members += p
        aside.onEnd = { g.members.remove(p) }
        return true
    }

    /** A place in the ring round [g] on [p]'s side: a little way off the lot, on the ground. */
    private fun ring(level: ServerLevel, g: Gathering, p: Townsperson): BlockPos? {
        val c = g.center
        val base = atan2(p.z - c.z, p.x - c.x)
        val reach = maxOf(g.lot.getXSpan(), g.lot.getZSpan()) / 2.0 + 4.0
        repeat(5) {
            val a = base + (p.random.nextDouble() - 0.5) * 0.9
            val d = reach + p.random.nextDouble() * 4.0
            val x = floor(c.x + cos(a) * d).toInt()
            val z = floor(c.z + sin(a) * d).toInt()
            for (dy in intArrayOf(0, 1, -1, 2, -2, 3, -3)) {
                val pos = BlockPos(x, p.blockY + dy, z)
                if (!g.lot.isInside(pos) && level.isLoaded(pos) && Places.standable(level, pos)) return pos
            }
        }
        return null
    }

    /** Stood in the ring, looking up at the works: arms folded or gazing, and a clap or a cheer at each call. */
    private class Gathered(private val g: Gathering) : Move() {
        private var heard = 0
        private var react = -1
        private var next = 0

        override fun start(p: Townsperson, a: Aside) {
            heard = g.cue
            p.act(if (p.role == TownRole.CHILD) Activity.PLAY else Activity.GAZE)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            val now = p.level().gameTime
            if (now > g.until) return false
            val top = g.top
            p.lookAt(top)
            if (g.cue > heard && react < 0) {
                heard = g.cue
                // Not all at once: a ripple round the ring
                react = t + p.random.nextInt(10)
            }
            if (react in 0..t) {
                react = -1
                p.act(when {
                    p.role == TownRole.CHILD -> Activity.JUMP
                    p.random.nextInt(3) == 0 -> Activity.CHEER
                    else -> Activity.CLAP
                })
                next = t + 30 + p.random.nextInt(20)
                return true
            }
            if (t >= next && (p.activity.loop || p.actDone)) {
                // A clap goes on a little after the call; then back to watching
                p.act(when {
                    p.activity == Activity.CLAP && p.random.nextInt(2) == 0 -> Activity.CLAP
                    p.role == TownRole.CHILD -> if (p.random.nextInt(4) == 0) Activity.JUMP else Activity.PLAY
                    p.random.nextInt(3) == 0 -> Activity.ARMS_FOLDED
                    else -> Activity.GAZE
                })
                next = t + 80 + p.random.nextInt(80)
            }
            return true
        }
    }

    /** [g] is over: everyone gathered goes back to their day. */
    private fun end(g: Gathering) {
        gatherings.remove(g.id)
        for (p in g.members.toList()) (p.director as? Aside)?.end(p, drop = true)
        g.members.clear()
    }

    /** The gathering at works [id], if one's on. */
    fun at(id: Int): Gathering? = gatherings[id]

    fun forget() = gatherings.clear()
}
