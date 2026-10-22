package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Passers-by who keep a town's streets busy round each player without filling the world with entities: when
 * fewer townsfolk are about than the hour calls for, a few more come walking in from round a corner, out of
 * sight. They go about their errands for a few minutes and slip away again when nobody's looking. Busy by day,
 * quieter of an evening, only the watch and a few late walkers with lanterns at night, and fewer in the rain.
 */
object Crowds {
    /** The most passers-by at once, however many players are about. */
    private const val MAX = 72

    private val live: MutableSet<Townsperson> = Collections.newSetFromMap(WeakHashMap())

    private val DAY = listOf(
        TownRole.CITIZEN to 40, TownRole.CHILD to 14, TownRole.NOBLE to 8, TownRole.GUARD to 8, TownRole.MERCHANT to 6,
        TownRole.FARMER to 6, TownRole.FISHER to 4, TownRole.BARD to 3, TownRole.PRIEST to 3, TownRole.SMITH to 2, TownRole.BAKER to 2,
    )
    private val EVENING = listOf(
        TownRole.CITIZEN to 55, TownRole.GUARD to 14, TownRole.NOBLE to 10, TownRole.BARD to 5, TownRole.MERCHANT to 5,
        TownRole.FARMER to 4, TownRole.FISHER to 3, TownRole.PRIEST to 2, TownRole.SMITH to 1, TownRole.BAKER to 1,
    )
    private val NIGHT = listOf(TownRole.GUARD to 70, TownRole.CITIZEN to 30)

    fun enabled(): Boolean = try {
        GuildmarkConfig.TOWNSFOLK_CROWDS.get()
    } catch (e: IllegalStateException) {
        true
    }

    private fun density(): Double = try {
        GuildmarkConfig.TOWNSFOLK_CROWD_DENSITY.get()
    } catch (e: IllegalStateException) {
        1.0
    }

    /**
     * Each tick for each player: a greeting now and then, the scenes round them (M6's: see [Scenes]), and more
     * passers-by if the streets round them are quiet.
     */
    fun tick(level: ServerLevel, player: ServerPlayer) {
        if (player.tickCount % 10 == 0) Social.greet(level, player)
        Scenes.tick(level, player)
        if (player.tickCount % 40 != 7 || player.isSpectator || !enabled()) return
        val town = Towns.around(level, player.uuid, player.blockPosition()) ?: return
        if (town.village) return
        live.removeIf { it.isRemoved }
        if (live.size >= MAX) return
        val about = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(40.0, 16.0, 40.0)) { it.isAlive }.size
        val wanted = (wanted(level, town) * density()).roundToInt()
        if (about >= wanted) return
        repeat(min(2, wanted - about)) { bringIn(level, player, town) }
    }

    /**
     * How many townsfolk should be about a player in [town] at this hour; with the fortunes on, thinner in hard times
     * and busier in good ([Fortunes.busy]: 0.7 times in HARD times to 1.25 in THRIVING).
     */
    private fun wanted(level: ServerLevel, town: Town): Double {
        val time = Math.floorMod(level.overworldClockTime, 24000L)
        val base = when {
            time < 11000 -> 16
            time < 14000 -> 11
            time < 23000 -> 4
            else -> 8
        }
        val wanted = if (level.isRaining) (base + 1) / 2 else base
        if (!Fortunes.enabled()) return wanted.toDouble()
        return wanted * Fortunes.busy(level, town.center.asLong()).coerceIn(0.5, 1.5)
    }

    /** One passer-by, 18 to 40 blocks off in the streets, never popping up in plain sight. */
    private fun bringIn(level: ServerLevel, player: ServerPlayer, town: Town) {
        val random = level.random
        val pos = Places.street(level, player.blockPosition(), 40, random, minR = 18, tries = 6) ?: return
        if (!town.contains(pos) || !level.isPositionEntityTicking(pos)) return
        val toward = Vec3.atBottomCenterOf(pos).add(0.0, 1.0, 0.0).subtract(player.eyePosition)
        if (toward.length() < 30.0 && toward.normalize().dot(player.getViewVector(1f)) > 0.2) return
        val role = pick(level, random)
        val person = Townsfolk.create(level, pos, role, reason = EntitySpawnReason.EVENT) ?: return
        person.crowd = true
        person.town = town
        person.leaveAt = level.gameTime + 2400 + random.nextInt(3600)
        if (role == TownRole.GUARD) person.nightWatch = Math.floorMod(level.overworldClockTime, 24000L) >= 12000
        level.addFreshEntity(person)
        live.add(person)
    }

    private fun pick(level: ServerLevel, random: RandomSource): TownRole {
        val time = Math.floorMod(level.overworldClockTime, 24000L)
        val table = when {
            time < 11000 || time >= 23500 -> DAY
            time < 14500 -> EVENING
            else -> NIGHT
        }
        var roll = random.nextInt(table.sumOf { it.second })
        for ((role, weight) in table) {
            roll -= weight
            if (roll < 0) return role
        }
        return TownRole.CITIZEN
    }

    /**
     * A passer-by whose time is up, or with no player anywhere near, leaves: only once no player is close by or
     * looking their way, so nobody sees them vanish.
     */
    fun maybeLeave(level: ServerLevel, p: Townsperson) {
        if (level.getNearestPlayer(p, 72.0) != null && level.gameTime < p.leaveAt) return
        for (player in level.players()) {
            val dist = player.distanceToSqr(p)
            if (dist < 100.0) return
            if (dist < 56.0 * 56.0) {
                val toward = p.position().add(0.0, 1.0, 0.0).subtract(player.eyePosition).normalize()
                if (toward.dot(player.getViewVector(1f)) > 0.3 && player.hasLineOfSight(p)) return
            }
        }
        p.discard()
    }
}
