package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.economy.works.build.WorksAtWork
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3

/**
 * A tower's watch coming to a strike it sees (living-world/design-influence.md section 1, "The watchtower"): when a
 * wagon is struck within its road's tower's sight (the record's `watched`, as the works aimed it), the horn sounds
 * from the tower's deck and its beacon flares (economy/works/build/WorksAtWork.strike, its lookouts pointing and
 * calling), and the watchmen on duty (Works.watchmen: two by day and one by night, fewer with few guards in town)
 * come running from it to stand with the wagon's guards. In a fight nobody joins they trade blows with the band as
 * the guards do (the dice settle it, the watch counted in its odds already); in one a player has joined they go for
 * the band for real. After, the band beaten off, they cheer and walk back to their tower; the wagon taken, they fall
 * back toward it; either way gone once nobody sees them. They're the town's guards on watch, not its residents:
 * bodies made for the fight, never kept ([TAG]).
 */
internal class Watchmen private constructor(private val way: Waylay, private val home: Vec3) {
    /** One watchman: moved by the watch (their director keeps their day's round from them). */
    private class Man(val p: Townsperson, val k: Int) : Director {
        var next = 0

        override fun tick(p: Townsperson) {}

        /** Busy: a look for whoever speaks to them. */
        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            p.lookAt(player)
            return true
        }
    }

    private val level: ServerLevel get() = way.level
    private val men = ArrayList<Man>()
    private var t = 0
    private var decidedAt = -1
    private var beaten = false

    /** Whether any of the watch is still about (the fight's bodies keep going while they are). */
    val busy: Boolean get() = men.isNotEmpty()

    /** Whether [e] is one of the watch. */
    fun has(e: Entity): Boolean = men.any { it.p === e }

    /** One of the watch still standing. */
    fun standing(e: Entity): Boolean = men.any { it.p === e && it.p.isAlive }

    /** Each tick, from the fight's. */
    fun tick() {
        t++
        men.removeIf { it.p.isRemoved }
        if (men.isEmpty()) return
        when {
            decidedAt >= 0 -> after()
            way.real -> real()
            else -> played()
        }
    }

    /** A fight nobody joins: run in to their places, then blows traded with the nearest of the band. */
    private fun played() {
        val foes = way.foes()
        for (m in men) {
            if (!m.p.isAlive) continue
            val (spot, yaw) = way.watchSpot(m.k)
            val d = m.p.position().distanceTo(spot)
            if (d > 1.4) {
                if ((t + m.k * 7) % 10 == 0) m.p.navigation.moveTo(spot.x, spot.y, spot.z, if (d > 6.0) RUN else 0.8)
                // Out of the trees and there by now: put there, once nobody's looking
                if (t > RUN_MOST && d > 3.0 && !seen(m.p.position(), 32.0)) m.p.snapTo(spot.x, spot.y, spot.z, yaw, 0f)
                continue
            }
            m.p.navigation.stop()
            val foe = foes.minByOrNull { it.distanceToSqr(m.p) }
            if (foe == null) {
                m.p.face(Mth.approachDegrees(m.p.yBodyRot, yaw, 18f))
                if (m.p.activity != Activity.STAND_GUARD && (m.p.activity.loop || m.p.actDone)) m.p.act(Activity.STAND_GUARD)
                continue
            }
            m.p.lookAt(foe)
            m.p.face(Mth.approachDegrees(m.p.yBodyRot, m.p.yawTo(foe.position()), 18f))
            if (t < m.next) {
                if ((m.p.activity == Activity.SPAR_GUARD || m.p.activity == Activity.SPAR_ATTACK) && m.p.actTicks > 28 || m.p.activity == Activity.NONE) m.p.act(Activity.STAND_GUARD)
                continue
            }
            m.next = t + 34 + level.random.nextInt(24)
            if (level.random.nextBoolean()) {
                m.p.act(Activity.SPAR_ATTACK)
                sound(m.p.position().add(0.0, 1.3, 0.0), SoundEvents.PLAYER_ATTACK_WEAK, 0.6f, 1.0f)
            } else {
                m.p.act(Activity.SPAR_GUARD)
                sound(m.p.position().add(0.0, 1.2, 0.0), SoundEvents.SHIELD_BLOCK.value(), 0.6f, 0.85f + level.random.nextFloat() * 0.3f)
            }
        }
    }

    /** A fight a player has joined: each goes for the nearest of the band (their own fighting does the rest). */
    private fun real() {
        if (t % 10 != 0) return
        val foes = way.foes()
        for (m in men) {
            if (!m.p.isAlive) continue
            val target = m.p.target?.takeIf { it.isAlive && foes.any { f -> f === it } }
            if (target == null) m.p.target = foes.minByOrNull { it.distanceToSqr(m.p) }
        }
    }

    /** The fight's decided: beaten off, a cheer; taken, they fall back. Either way back toward the tower. */
    fun decided(waylaid: Boolean) {
        if (decidedAt >= 0) return
        decidedAt = t
        beaten = !waylaid
        for (m in men) {
            m.p.target = null
            m.p.navigation.stop()
            m.p.act(if (beaten) (if (m.k % 2 == 0) Activity.HURRAH else Activity.CHEER) else Activity.STAGGER)
        }
    }

    /** After: off back to the tower, gone once nobody sees (or they're there, or it's taken too long). */
    private fun after() {
        val since = t - decidedAt
        for (m in men.toList()) {
            if (!m.p.isAlive) {
                men.remove(m)
                continue
            }
            if (since < (if (beaten) 50 else 30)) continue
            if ((since + m.k * 9) % 40 == 0) m.p.navigation.moveTo(home.x, home.y, home.z, if (beaten) 0.6 else RUN)
            val there = m.p.position().distanceTo(home) < 4.0
            if (there || since > AFTER_MOST || since > 120 && !seen(m.p.position(), 40.0)) {
                if (seen(m.p.position(), 24.0)) level.sendParticles(ParticleTypes.POOF, m.p.x, m.p.y + 0.6, m.p.z, 4, 0.2, 0.3, 0.2, 0.01)
                m.p.discard()
                men.remove(m)
            }
        }
    }

    /** Its bodies go at once (nobody near, the server stopping). */
    fun gone() {
        for (m in men) m.p.discard()
        men.clear()
    }

    /** Whether a player is within [range] of [at] looking that way (or very near). */
    private fun seen(at: Vec3, range: Double): Boolean = level.players().any { player ->
        if (player.isSpectator) return@any false
        val to = at.subtract(player.eyePosition)
        val d = to.length()
        d < range && (d < 12.0 || player.lookAngle.dot(to.scale(1.0 / d)) > 0.45)
    }

    private fun sound(at: Vec3, event: net.minecraft.sounds.SoundEvent, volume: Float, pitch: Float) {
        level.playSound(null, at.x, at.y, at.z, event, SoundSource.HOSTILE, volume, pitch)
    }

    companion object {
        /** Every watchman's tag, kept if a body is ever saved by mistake, so it goes again when loaded. */
        const val TAG = "guildmark.watchman"

        /** How fast they run in and how long they have to; the longest they're about after the fight. */
        private const val RUN = 1.15
        private const val RUN_MOST = 400
        private const val AFTER_MOST = 1200

        /** How near the wagon the tower may be for them to come out of its door; else they're met coming down the road. */
        private const val DOOR = 40.0
        private const val MET = 30.0

        /**
         * [way]'s strike, if its record says it fell in its tower's sight and the town has watchmen on duty: the horn
         * and the beacon, and the watch on its way. Null otherwise (and with the town's works off).
         */
        fun out(way: Waylay): Watchmen? {
            if (!Works.enabled()) return null
            val level = way.level
            val c = CaravanData.get(level).caravan(way.show.id)?.takeIf { it.watched } ?: return null
            val rec = Works.standing(level, c.capital, WorksKind.TOWER, c.road) ?: return null
            val tower: WorksView = Works.get(level, rec.id) ?: return null
            val site = tower.site ?: return null
            val wagon = way.show.wagonAt()
            val clock = ((Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0).toInt()
            val n = Works.watchmen(level, c.capital, c.road, clock)
            // Nobody on watch at this hour: no horn, and nobody comes
            if (n <= 0) return null
            WorksAtWork.strike(level, tower, BlockPos.containing(wagon))
            val door = Vec3.atBottomCenterOf(site.origin)
            val watch = Watchmen(way, door)
            val from = start(level, wagon, door) ?: return null
            for (k in 0 until n) {
                val at = from.add((k - (n - 1) / 2.0) * 1.2, 0.0, 0.0)
                val p = spawn(level, at, c.seed * 13L + k, Mth.wrapDegrees(Show.yawOf(wagon.x - at.x, wagon.z - at.z))) ?: continue
                val m = Man(p, k)
                p.director = m
                watch.men.add(m)
                val (spot, _) = way.watchSpot(k)
                p.navigation.moveTo(spot.x, spot.y, spot.z, RUN)
            }
            return if (watch.men.isEmpty()) null else watch
        }

        /** Where the watch starts from: the tower's door if it's near and loaded, else a way down the road toward it. */
        private fun start(level: ServerLevel, wagon: Vec3, door: Vec3): Vec3? {
            val d = door.distanceTo(wagon)
            if (d < DOOR && level.isPositionEntityTicking(BlockPos.containing(door))) return door
            val toward = door.subtract(wagon).let { Vec3(it.x, 0.0, it.z) }
            if (toward.lengthSqr() < 1e-4) return null
            val dir = toward.normalize()
            for (out in doubleArrayOf(MET, MET - 8.0, MET - 14.0)) {
                val x = wagon.x + dir.x * out
                val z = wagon.z + dir.z * out
                val pos = BlockPos.containing(x, wagon.y, z)
                if (!level.isPositionEntityTicking(pos)) continue
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z)).toDouble()
                if (y < wagon.y - 10 || y > wagon.y + 10) continue
                return Vec3(x, y, z)
            }
            return null
        }

        /** One watchman at [at] facing [yaw]: a town guard of the watch, named, going after nobody until told. */
        private fun spawn(level: ServerLevel, at: Vec3, seed: Long, yaw: Float): Townsperson? {
            val role = TownRole.GUARD
            val look = Math.floorMod(seed, role.looks.toLong()).toInt()
            val p = Townsfolk.create(level, BlockPos.containing(at), role, look, yaw, EntitySpawnReason.EVENT) ?: return null
            p.snapTo(at.x, at.y, at.z, yaw, 0f)
            p.yHeadRot = yaw
            p.yBodyRot = yaw
            val woman = Names.woman(role, p.look, seed)
            p.customName = Component.literal(Names.given(null, woman, seed) + " " + Names.family(null, seed / 3))
            p.isCustomNameVisible = false
            p.addTag(TAG)
            p.home = BlockPos.containing(at)
            // No running from the band, and nothing gone after on their own: the watch says when they fight
            p.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
            p.targetSelector.removeAllGoals { true }
            return if (level.addFreshEntity(p)) p else null
        }
    }
}
