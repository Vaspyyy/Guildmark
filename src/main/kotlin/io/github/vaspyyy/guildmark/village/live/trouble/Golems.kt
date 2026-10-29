package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A village's iron golems (living-world/villages/design.md 11.5; 16.1 moment 30). A vanilla village gets its golems from its
 * villagers' panic and numbers; a village whose folk are townsfolk has no villagers to panic, so its record keeps the count up: at
 * night, if the golems within its radius and ten more of the bell are fewer than one for every ten folk (one at the least, three at
 * the most), one is made on open ground within 12 blocks of the bell where no player is within 48 blocks, at most one a night (the
 * night is kept in [TroubleData.Kept.golemNight]). Nothing is seen: there is one there in the morning, near the bell. The golems
 * that are there stay and fight as they always have, and are no residents: the record does not count them. A village that keeps its
 * vanilla villagers makes its own. With `villageGolems` off nothing is made.
 */
object Golems {
    /** Folk to a golem, and the least and most a village keeps. */
    private const val PER = 10
    private const val LEAST = 1
    private const val MOST = 3

    /** How far from the bell a golem is set down, and how far a player must be from the spot. */
    private const val WITHIN = 12
    private const val UNSEEN = 48.0

    /** The golems [rec] wants: one for every ten of its folk, one to three. */
    fun wanted(level: ServerLevel, rec: VillageRec): Int {
        val folk = Residents.get(level).byCentre(BlockPos.of(rec.key)).count { it.alive }
        return (folk / PER).coerceIn(LEAST, MOST)
    }

    /** The golems about within [rec]'s radius and ten more of its bell (where the land is loaded). */
    fun count(level: ServerLevel, rec: VillageRec): Int {
        val bell = BlockPos.of(rec.key)
        val r = rec.radius + 10.0
        return level.getEntities(EntityTypes.IRON_GOLEM, AABB(bell).inflate(r, 16.0, r)) { it.isAlive }.size
    }

    /** [rec] on town day [day]: at night, once, a golem made if it has too few and nobody would see it come. */
    internal fun look(level: ServerLevel, rec: VillageRec, day: Long) {
        if (!Trouble.golemsOn() || !Trouble.night(level) || !VillageAdopt.adopting(rec)) return
        val kept = TroubleData.peek(level)?.peek(rec.key)
        if (kept?.golemNight == day) return
        val bell = BlockPos.of(rec.key)
        if (!level.isPositionEntityTicking(bell)) return
        if (count(level, rec) >= wanted(level, rec)) return
        val at = spot(level, rec, unseen = true) ?: return
        if (!make(level, at)) return
        val data = TroubleData.get(level)
        data.of(rec.key).golemNight = day
        data.setDirty()
        Trouble.log(level, rec, "an iron golem made at ${at.toShortString()} (by the record, at night)")
    }

    /** `village golem`: the top-up now, whatever the hour or the night: the golem made, or why not. */
    fun now(level: ServerLevel, rec: VillageRec): String {
        if (!Trouble.golemsOn()) return "Village Golems (villageGolems) is off: no golem is made."
        val have = count(level, rec)
        val want = wanted(level, rec)
        if (have >= want) return "$have golem${if (have == 1) "" else "s"} about, and ${rec.name} wants $want: none made."
        val at = spot(level, rec, unseen = false) ?: return "No open ground within $WITHIN blocks of the bell (or it is not loaded): none made."
        if (!make(level, at)) return "The golem could not be set down at ${at.toShortString()}."
        Trouble.log(level, rec, "an iron golem made at ${at.toShortString()} (by the command)")
        return "An iron golem made at ${at.toShortString()} ($have of $want before)."
    }

    /** Open ground within [WITHIN] blocks of [rec]'s bell with room for a golem: with [unseen], only where no player is within [UNSEEN] blocks. */
    private fun spot(level: ServerLevel, rec: VillageRec, unseen: Boolean): BlockPos? {
        val bell = BlockPos.of(rec.key)
        val places = Villages.places(level, rec.key)
        val start = Math.floorMod(rec.key ushr 7, 12L).toInt()
        for (r in intArrayOf(6, 8, 4, 10, WITHIN)) for (k in 0 until 12) {
            val a = (start + k) * Math.PI / 6.0
            val col = BlockPos(bell.x + (cos(a) * r).roundToInt(), bell.y, bell.z + (sin(a) * r).roundToInt())
            for (dy in intArrayOf(0, 1, -1, 2, -2, 3, -3)) {
                val at = col.above(dy)
                if (!level.isLoaded(at) || !Places.standable(level, at)) continue
                if (places?.lodgingAt(at) != null) break
                if (!level.noCollision(EntityTypes.IRON_GOLEM.getSpawnAABB(at.x + 0.5, at.y.toDouble(), at.z + 0.5))) break
                if (unseen && level.getNearestPlayer(at.x + 0.5, at.y + 0.5, at.z + 0.5, UNSEEN, false) != null) break
                return at
            }
        }
        return null
    }

    /** A golem set down at [at], facing the bell's way, a village's own (not one a player built). */
    private fun make(level: ServerLevel, at: BlockPos): Boolean {
        val golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.MOB_SUMMONED) ?: return false
        golem.snapTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5, 0f, 0f)
        golem.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.MOB_SUMMONED, null)
        golem.setPlayerCreated(false)
        return level.addFreshEntity(golem)
    }

    /** What `village golem` and `village info` say of [rec]'s golems. */
    fun line(level: ServerLevel, rec: VillageRec): String {
        val have = count(level, rec)
        val want = wanted(level, rec)
        val night = TroubleData.peek(level)?.peek(rec.key)?.golemNight?.takeIf { it != TroubleData.NONE }
        val keeps = when {
            !Trouble.golemsOn() -> "Village Golems is off"
            !VillageAdopt.adopting(rec) -> "its vanilla villagers make their own"
            else -> "kept up at night" + (night?.let { ", the last made on the night of day $it" } ?: "")
        }
        return "$have about of $want wanted ($keeps)"
    }
}
