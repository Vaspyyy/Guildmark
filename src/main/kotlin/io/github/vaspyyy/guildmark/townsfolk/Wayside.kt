package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Townsfolk getting out of the way of something big coming along a street or a road (a caravan's wagon, a rider
 * at more than a walk): whoever is on its way steps off to the side, turns to watch it go by (with a wave for the
 * driver now and then, and a cheer from the children) and carries on with what they were doing once it's past.
 * Nothing about it is saved.
 */
object Wayside {
    /** How long someone stands aside after the last word that something is coming their way. */
    private const val HOLD = 30L

    /** The longest anyone stands aside for one thing, however long it takes to pass (one held up in the road). */
    private const val MOST = 600L

    /** The room a body wants beyond the edge of the way. */
    private const val ROOM = 0.6

    /** How far above or below the way someone can be and still be on it (a stair, a slope). */
    private const val RISE = 2.5

    /** How far off the edge of the way someone standing aside still counts as watching it go by. */
    private const val WATCHING = 3.0

    /** How close whatever is coming must be for someone to cross in front of it to the far side: closer, they don't. */
    private const val CLOSE = 7.0

    /** How close its front comes before someone standing aside waves. */
    private const val WAVE_AT = 6.0

    /** How far round about to look for somewhere to stand when there's no room to either side. */
    private const val ROUND = 5

    /** The most points of a way looked at, whatever is passed. */
    private const val POINTS = 64

    /** How often riders are looked at, how fast one must be going (blocks a tick) to have way made, how far ahead
     * (in ticks of riding, and at most in blocks) and how wide. */
    private const val RIDE_EVERY = 4
    private const val RIDING = 0.16
    private const val RIDE_AHEAD = 40.0
    private const val RIDE_FAR = 18.0
    private const val RIDE_WIDTH = 1.8

    /** The pace and width of the wagon `/guildmark capital makeway` sends along (a heavy horse's walk). */
    const val WAGON_PACE = 0.12
    private const val WAGON_WIDTH = 2.4

    /** How far a wagon reaches back from its front (the horse's head to the tailboard) and how far ahead it's cleared. */
    private const val WAGON_LENGTH = 6.0
    private const val WAGON_AHEAD = 8.0

    /** Where each rider's mount was last looked at. */
    private val ridden = HashMap<UUID, Vec3>()

    /** The make-believe wagons of `/guildmark capital makeway` on their way. */
    private class Drive(val level: ServerLevel, val from: Vec3, val ux: Double, val uz: Double, val blocks: Double) {
        var gone = 0.0
        var ticks = 0
    }

    private val drives = ArrayList<Drive>()

    /** Registers the ticking of the make-believe wagons; called once from Townsfolk.init. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (drives.isNotEmpty()) drive(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            drives.clear()
            ridden.clear()
        }
    }

    /**
     * Clears [way] of townsfolk: the line something [width] blocks wide covers from its back to where it will be in a
     * few seconds, back first, with [front] where its front is now, which they turn to watch. Call it every few ticks
     * while it moves; each call keeps those it has caught standing aside a little longer. With [friendly], the grown-ups
     * wave or nod to it now and then (a wagon's driver); the children wave and cheer at anything. Someone at their post
     * or their work steps back to it after. Anyone seated, asleep, up a ladder, tied up or in someone else's charge (see
     * [Townsperson.director]) is left where they are.
     */
    fun clear(level: ServerLevel, way: List<Vec3>, width: Double, front: Vec3, friendly: Boolean = true) {
        if (way.size < 2) return
        val points = if (way.size > POINTS) way.subList(0, POINTS) else way
        val half = width / 2 + ROOM
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (v in points) {
            minX = min(minX, v.x); minY = min(minY, v.y); minZ = min(minZ, v.z)
            maxX = max(maxX, v.x); maxY = max(maxY, v.y); maxZ = max(maxZ, v.z)
        }
        val reach = half + WATCHING
        val box = AABB(minX - reach, minY - RISE, minZ - reach, maxX + reach, maxY + RISE, maxZ + reach)
        val now = level.gameTime
        for (p in level.getEntitiesOfClass(Townsperson::class.java, box) { it.isAlive }) {
            val near = near(points, p.position()) ?: continue
            val aside = p.director as? Aside
            if (aside != null) {
                if (near.off >= reach) continue
                aside.keep(now, front)
                // Still on it (another one coming, a bend): somewhere else to stand
                if (near.off < half && aside.onWay(points, half)) aside(level, p, points, near, half, front)?.let { aside.go(p, it) }
                continue
            }
            if (near.off >= half || !free(p)) continue
            val spot = aside(level, p, points, near, half, front) ?: continue
            // Someone at their post or their work comes back to it after
            val back = if (p.routine.anchored) p.blockPosition() else null
            Aside(spot, front, now, friendly, back).also { p.director = it }.go(p, spot)
        }
    }

    /** Whether [p] can be moved out of the way: on their feet, and nobody else's to move. */
    private fun free(p: Townsperson): Boolean =
        p.director == null && p.onGround() && !p.isSleeping && p.seat == null && p.captiveOf < 0 && !p.isLeader

    /** Each tick for each player: one riding through a town at more than a walk has the way ahead made for them. */
    fun ride(level: ServerLevel, player: ServerPlayer) {
        if (player.tickCount % RIDE_EVERY != 0) return
        val mount = player.vehicle as? LivingEntity
        if (mount == null) {
            if (ridden.isNotEmpty()) ridden.remove(player.uuid)
            return
        }
        val at = mount.position()
        val last = ridden.put(player.uuid, at) ?: return
        if (!mount.onGround() || !Days.enabled()) return
        val dx = (at.x - last.x) / RIDE_EVERY
        val dz = (at.z - last.z) / RIDE_EVERY
        val speed = sqrt(dx * dx + dz * dz)
        if (speed < RIDING || speed > 2.0 || Towns.around(level, player.uuid, player.blockPosition()) == null) return
        val ahead = min(RIDE_FAR, speed * RIDE_AHEAD)
        clear(level, line(at, dx / speed, dz / speed, 1.5, ahead), RIDE_WIDTH, at, friendly = false)
    }

    /** Points a block or so apart along a straight way through [at] heading ([ux], [uz]), [back] behind it to [ahead] in front. */
    private fun line(at: Vec3, ux: Double, uz: Double, back: Double, ahead: Double): List<Vec3> {
        val steps = max(2, (back + ahead).toInt())
        return (0..steps).map { i ->
            val d = -back + (back + ahead) * i / steps
            Vec3(at.x + ux * d, at.y, at.z + uz * d)
        }
    }

    /**
     * Sends a make-believe wagon from [player]'s feet the way they're looking, [blocks] far at a wagon's pace, a puff of
     * dust marking its front: anyone on its way makes way for it as they would for a caravan's. For testing.
     */
    fun makeWay(player: ServerPlayer, blocks: Int) {
        val look = player.getViewVector(1f)
        val flat = sqrt(look.x * look.x + look.z * look.z)
        val (ux, uz) = if (flat < 1e-3) 0.0 to 1.0 else look.x / flat to look.z / flat
        drives.add(Drive(player.level(), player.position(), ux, uz, blocks.toDouble()))
    }

    private fun drive(server: MinecraftServer) {
        drives.removeIf { d ->
            d.gone += WAGON_PACE
            d.ticks++
            val front = Vec3(d.from.x + d.ux * min(d.gone, d.blocks), d.from.y, d.from.z + d.uz * min(d.gone, d.blocks))
            if (d.ticks % 4 == 0 && d.level.server === server) {
                if (d.gone <= d.blocks) {
                    d.level.sendParticles(ParticleTypes.POOF, front.x, front.y + 0.2, front.z, 2, 0.5, 0.1, 0.5, 0.01)
                    d.level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, front.x, front.y + 0.1, front.z, 1, 0.6, 0.0, 0.6, 0.002)
                }
                // Its back follows on behind until it's past the end too
                val back = min(WAGON_LENGTH, d.gone)
                val ahead = if (d.gone < d.blocks) min(WAGON_AHEAD, d.blocks - d.gone) else 0.0
                if (back + ahead > 0.5) clear(d.level, line(front, d.ux, d.uz, back, ahead), WAGON_WIDTH, front)
            }
            d.gone > d.blocks + WAGON_LENGTH || d.level.server !== server
        }
    }

    /** Where [at] comes nearest to [way] at about its height: the point, the way's heading there, and how far off it is. */
    private class Near(val x: Double, val z: Double, val dx: Double, val dz: Double, val off: Double)

    private fun near(way: List<Vec3>, at: Vec3): Near? {
        var best: Near? = null
        for (i in 0 until way.size - 1) {
            val a = way[i]
            val b = way[i + 1]
            val sx = b.x - a.x
            val sz = b.z - a.z
            val length2 = sx * sx + sz * sz
            val t = if (length2 < 1e-6) 0.0 else (((at.x - a.x) * sx + (at.z - a.z) * sz) / length2).coerceIn(0.0, 1.0)
            val px = a.x + sx * t
            val pz = a.z + sz * t
            if (abs(at.y - (a.y + (b.y - a.y) * t)) > RISE) continue
            val off = sqrt((at.x - px) * (at.x - px) + (at.z - pz) * (at.z - pz))
            if (best != null && off >= best.off) continue
            val length = sqrt(length2)
            best = if (length < 1e-3) Near(px, pz, 0.0, 0.0, off) else Near(px, pz, sx / length, sz / length, off)
        }
        return best
    }

    /**
     * Somewhere off [way] for [p] to stand while it goes by: straight out to the side they're on, a little farther if
     * need be, then to the other side (unless its [front] is too close to cross in front of), else the nearest
     * standing room round about that's clear of it.
     */
    private fun aside(level: ServerLevel, p: Townsperson, way: List<Vec3>, near: Near, half: Double, front: Vec3): BlockPos? {
        // The way's left hand, and the side of it they're on
        val nx = -near.dz
        val nz = near.dx
        val side = (p.x - near.x) * nx + (p.z - near.z) * nz
        val first = if (side > 0.05) 1.0 else if (side < -0.05) -1.0 else if (p.random.nextBoolean()) 1.0 else -1.0
        val sides = if (p.position().distanceToSqr(front) < CLOSE * CLOSE) listOf(first) else listOf(first, -first)
        for (s in sides) for (k in 0..3) {
            val d = half + 0.5 + k * 0.75
            val spot = ground(level, BlockPos.containing(near.x + nx * s * d, p.y, near.z + nz * s * d)) ?: continue
            if (clearOf(way, spot, half)) return spot
        }
        // No room to either side (a gateway, a narrow street): the nearest spot round about, never across its front
        val base = p.blockPosition()
        var best: BlockPos? = null
        var score = Double.MAX_VALUE
        for (dx in -ROUND..ROUND) for (dz in -ROUND..ROUND) {
            val spot = ground(level, base.offset(dx, 0, dz)) ?: continue
            if (!clearOf(way, spot, half)) continue
            val across = ((spot.x + 0.5 - near.x) * nx + (spot.z + 0.5 - near.z) * nz) * first < 0
            if (across && sides.size == 1) continue
            val s = sqrt((dx * dx + dz * dz).toDouble()) + if (across) 4.0 else 0.0
            if (s < score) {
                score = s
                best = spot
            }
        }
        return best
    }

    /** Whether someone standing at [spot] is clear of [way]. */
    private fun clearOf(way: List<Vec3>, spot: BlockPos, half: Double): Boolean =
        (near(way, Vec3.atBottomCenterOf(spot))?.off ?: Double.MAX_VALUE) >= half + 0.2

    /** Standing room at [pos], or a block above or below it. */
    private fun ground(level: ServerLevel, pos: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, 1, -1)) {
            val at = pos.above(dy)
            if (Places.standable(level, at)) return at
        }
        return null
    }

    /**
     * Standing aside at [spot] until whatever is coming has gone by: there at a brisk walk (the children run), turned
     * to watch it, then back to their day where they left it. The task they were at carries on, after a walk [back]
     * to where they stood if they were at their post or their work.
     */
    private class Aside(
        private var spot: BlockPos, private var front: Vec3, private val since: Long, private val friendly: Boolean, private val back: BlockPos?,
    ) : Director {
        private var until = since + HOLD
        private var tries = 0
        private var there = false
        private var waved = false
        private var returning = false

        fun keep(now: Long, front: Vec3) {
            if (now - since > MOST) return
            until = max(until, now + HOLD)
            this.front = front
        }

        /** Whether where they're standing aside (or making for) is on [way]. */
        fun onWay(way: List<Vec3>, half: Double): Boolean = !clearOf(way, spot, half)

        fun go(p: Townsperson, to: BlockPos) {
            spot = to
            there = false
            returning = false
            tries = 0
            walk(p, spot)
        }

        private fun walk(p: Townsperson, to: BlockPos) {
            p.navigation.moveTo(to.x + 0.5, to.y.toDouble(), to.z + 0.5, if (p.role == TownRole.CHILD) Jobs.RUN else Jobs.BRISK)
        }

        private fun at(p: Townsperson, pos: BlockPos): Boolean {
            val dx = pos.x + 0.5 - p.x
            val dz = pos.z + 0.5 - p.z
            return dx * dx + dz * dz < 0.36
        }

        override fun tick(p: Townsperson) {
            val now = p.level().gameTime
            if ((now - since) % 100 == 0L) Claims.held(p)
            if (now > until || now - since > MOST) return goBack(p)
            if (returning) go(p, spot)
            if (!there) {
                when {
                    at(p, spot) -> there = true
                    p.navigation.isDone -> if (++tries > 3) there = true else walk(p, spot)
                }
                if (!there) return
                p.navigation.stop()
            }
            p.lookAt(front)
            if (waved || p.position().distanceToSqr(front) > WAVE_AT * WAVE_AT) return
            waved = true
            // Nobody waves with their arms full
            if (p.activity != Activity.NONE && p.activity != Activity.STROLL) return
            val r = p.random.nextInt(12)
            when {
                p.role == TownRole.CHILD -> p.act(if (r < 6) Activity.WAVE else Activity.CHEER)
                !friendly -> if (r < 2) p.act(Activity.NOD)
                p.role == TownRole.GUARD || p.role == TownRole.KNIGHT -> if (r < 6) p.act(Activity.NOD)
                r < 4 -> p.act(Activity.WAVE)
                r < 7 -> p.act(Activity.NOD)
            }
        }

        /** It's gone by: back to their post first, if they were at one. */
        private fun goBack(p: Townsperson) {
            val back = back ?: return release(p)
            if (!returning) {
                returning = true
                tries = 0
                walk(p, back)
                return
            }
            when {
                at(p, back) -> {
                    p.navigation.stop()
                    p.setPos(back.x + 0.5, p.y, back.z + 0.5)
                    release(p)
                }
                p.navigation.isDone -> if (++tries > 3) {
                    // No way back to it: their day goes on from here
                    release(p)
                    p.routine.interrupt()
                } else walk(p, back)
            }
        }

        override fun interact(p: Townsperson, player: ServerPlayer): Boolean = false

        /** Back to their day, which picks up where it was. */
        fun release(p: Townsperson) {
            if (p.director === this) p.director = null
            p.navigation.stop()
        }
    }
}
