package io.github.vaspyyy.guildmark.client.ambience

import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.client.atlas.AtlasClient
import io.github.vaspyyy.guildmark.network.AtlasMark
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.entity.BellBlockEntity
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The bells that strike the hour. The nearest bell within earshot is the town's: a city's (a capital, a
 * village grown into a city, or a bell with a crowd of townsfolk round it) rings the quarter chime and then
 * the great bell, a village's its own smaller bell. A Minecraft hour is under a minute, so the bells keep the
 * old hours of the day instead of every hour: dawn, noon and dusk (six, twelve and six) are struck in full,
 * the city's chime first, and mid-morning, mid-afternoon and nightfall (nine, three and nine) get three
 * softer strokes. A town wakes its sleepers at dawn the same way. The bell is seen to swing with each stroke.
 */
class Bells {
    private class Bell(val pos: BlockPos, val great: Boolean)

    private class Stroke(val at: Int, val sound: Identifier, val bell: BlockPos)

    private var known: List<Bell> = emptyList()
    private val strokes = ArrayDeque<Stroke>()
    private var lastHour = -1
    private var lastTime = 0L
    private val random = RandomSource.create()

    fun reset() {
        known = emptyList()
        strokes.clear()
        lastHour = -1
    }

    fun tick(level: ClientLevel, player: Player, ticks: Int, volume: Float, capital: Boolean) {
        if (ticks % FIND_EVERY == 1) find(level, player)
        val time = level.overworldClockTime
        val hour = CityAmbience.hourOf(time)
        if (lastHour >= 0 && hour != lastHour) {
            val step = time - lastTime
            // The clock ticking on into the next hour, or the morning after a night's sleep; not a /time jump
            val struck = (hour == (lastHour + 1) % 24 && step in 0..MAX_STEP) || (hour == 6 && time % 24000L < WAKE_WINDOW && step > MAX_STEP)
            if (struck && hour in RUNG_HOURS) toll(player, hour, ticks, capital)
        }
        lastHour = hour
        lastTime = time
        while (strokes.isNotEmpty() && strokes.first().at <= ticks) {
            val stroke = strokes.removeFirst()
            // One that came due while the city was hushed is let go, rather than rung late on top of the next
            if (ticks - stroke.at > LATE) continue
            CitySounds.play(stroke.sound, Vec3.atCenterOf(stroke.bell), volume)
            // The bell swings as it's struck, if it's near enough to be seen
            if (stroke.sound != CitySounds.BELL_CHIME) {
                (level.getBlockEntity(stroke.bell) as? BellBlockEntity)?.triggerEvent(1, Direction.Plane.HORIZONTAL.getRandomDirection(random).get3DDataValue())
            }
        }
    }

    private fun toll(player: Player, hour: Int, ticks: Int, capital: Boolean) {
        val bell = nearest(player) ?: return
        val great = bell.great || capital
        val full = hour in FULL_HOURS
        val count = if (full) (hour + 11) % 12 + 1 else 3
        var at = ticks
        if (great && full) {
            strokes.addLast(Stroke(at, CitySounds.BELL_CHIME, bell.pos))
            at += CHIME_TICKS
        }
        // The full strike (the great bell's is in tune with the chime) or the softer one, the same all through
        val sound = when {
            great -> if (full) CitySounds.BELL_GREAT else CitySounds.BELL_GREAT_SOFT
            else -> if (full) CitySounds.BELL_TOWN else CitySounds.BELL_TOWN_SOFT
        }
        val gap = if (great) GREAT_GAP else TOWN_GAP
        repeat(count) {
            strokes.addLast(Stroke(at, sound, bell.pos))
            at += gap
        }
    }

    private fun nearest(player: Player): Bell? =
        known.filter { it.pos.distToCenterSqr(player.position()) < (if (it.great) GREAT_REACH else TOWN_REACH).let { r -> r * r } }
            .minByOrNull { it.pos.distToCenterSqr(player.position()) }

    /** The bells in the loaded chunks round the player, each judged by the town it rings for. */
    private fun find(level: ClientLevel, player: Player) {
        val bells = ArrayList<BlockPos>()
        val cx = SectionPos.blockToSectionCoord(player.blockX)
        val cz = SectionPos.blockToSectionCoord(player.blockZ)
        for (x in cx - SEARCH_CHUNKS..cx + SEARCH_CHUNKS) {
            for (z in cz - SEARCH_CHUNKS..cz + SEARCH_CHUNKS) {
                val chunk = level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false) ?: continue
                for (entity in chunk.blockEntities.values) if (entity is BellBlockEntity) bells.add(entity.blockPos)
            }
        }
        // Cities the player has found: the capitals, and villages that have grown into cities
        val cities = AtlasClient.marks().filter { it.kind == AtlasPlace.VILLAGE && (it.has(AtlasMark.CAPITAL) || it.size >= CITY_SIZE) }.map { it.pos }
        known = bells.map { bell ->
            val great = cities.any { it.distSqr(bell) < CITY_SPREAD * CITY_SPREAD } ||
                level.getEntitiesOfClass(LivingEntity::class.java, AABB(bell).inflate(48.0, 24.0, 48.0)) { Surroundings.townsfolk(it) }.size >= GREAT_FOLK
            Bell(bell, great)
        }
    }

    companion object {
        private const val FIND_EVERY = 100
        private const val SEARCH_CHUNKS = 10
        /** How far each bell carries (as its sounds.json entry says), in blocks. */
        private const val GREAT_REACH = 160.0
        private const val TOWN_REACH = 96.0
        /** A bell this near a city's board is the city's; elsewhere it takes this many townsfolk about it. */
        private const val CITY_SPREAD = 112
        private const val GREAT_FOLK = 20
        /** The advances a village has built by the time it's called a city (VillageNames.size). */
        private const val CITY_SIZE = 8
        /** The hours the bells keep, and of them dawn, noon and dusk, struck in full. */
        private val RUNG_HOURS = setOf(6, 9, 12, 15, 18, 21)
        private val FULL_HOURS = setOf(6, 12, 18)
        /** The most the clock may move between two looks for an hour to count as struck, and how soon after dawn a waking counts. */
        private const val MAX_STEP = 1200L
        private const val WAKE_WINDOW = 400L
        /** Ticks from the chime's start to the first stroke (its last note sounds at about five seconds), and between strokes. */
        private const val CHIME_TICKS = 155
        private const val GREAT_GAP = 48
        private const val TOWN_GAP = 40
        private const val LATE = 10
    }
}
