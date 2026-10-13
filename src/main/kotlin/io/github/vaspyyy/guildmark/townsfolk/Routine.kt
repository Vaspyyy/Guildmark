package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/** The parts of a townsperson's day. */
enum class Phase {
    /** Up and about at home. */
    WAKE,
    /** At work (children at play, nobles out strolling, guards on their watch). */
    WORK,
    /** Free time in the day: errands, chats, the market, a sit on a bench. */
    FREE,
    /** The tavern, or the square if the town has no tavern. */
    EVENING,
    /** Heading home. */
    HOME,
    /** In bed. */
    SLEEP,
}

/**
 * The timetable. Times are in the day's ticks (0 is six in the morning, 6000 noon, 12000 six in the evening,
 * 18000 midnight), offset a little per person ([Townsperson.shift]) so a street doesn't all move at once.
 */
object Schedule {
    fun time(p: Townsperson): Int = Math.floorMod(p.level().overworldClockTime + p.shift, 24000L).toInt()

    fun phase(p: Townsperson): Phase {
        val t = time(p)
        return when (p.role) {
            TownRole.GUARD -> if (p.nightWatch) when {
                t >= 12000 -> Phase.WORK
                t < 1000 -> Phase.HOME
                t < 10000 -> Phase.SLEEP
                else -> Phase.FREE
            } else when {
                t < 12500 -> Phase.WORK
                t < 15000 -> Phase.EVENING
                t < 16000 -> Phase.HOME
                else -> Phase.SLEEP
            }
            TownRole.INNKEEPER -> when {
                t < 2500 -> Phase.SLEEP
                t < 3000 -> Phase.WAKE
                t < 19000 -> Phase.WORK
                t < 19500 -> Phase.HOME
                else -> Phase.SLEEP
            }
            TownRole.BARD -> when {
                t < 1500 -> Phase.SLEEP
                t < 2000 -> Phase.WAKE
                t < 9500 -> Phase.WORK
                t < 17000 -> Phase.EVENING
                t < 17500 -> Phase.HOME
                else -> Phase.SLEEP
            }
            // Out on the water before dawn
            TownRole.FISHER -> when {
                t >= 23000 || t < 8000 -> Phase.WORK
                t < 10000 -> Phase.FREE
                t < 14000 -> Phase.EVENING
                t < 14500 -> Phase.HOME
                else -> Phase.SLEEP
            }
            // The bread is in the oven long before the shop opens
            TownRole.BAKER -> when {
                t >= 22500 || t < 8500 -> Phase.WORK
                t < 10000 -> Phase.FREE
                t < 14000 -> Phase.EVENING
                t < 15000 -> Phase.HOME
                else -> Phase.SLEEP
            }
            TownRole.CHILD -> when {
                t >= 23500 || t < 1000 -> Phase.WAKE
                t < 11000 -> Phase.WORK
                t < 12500 -> Phase.HOME
                else -> Phase.SLEEP
            }
            else -> when {
                t >= 23500 || t < 1000 -> Phase.WAKE
                t < 10000 -> Phase.WORK
                t < 15000 -> Phase.EVENING
                t < 16000 -> Phase.HOME
                else -> Phase.SLEEP
            }
        }
    }

    /** Whether the weather keeps [p] indoors: rain sends those who work or idle outside home. */
    fun sheltering(p: Townsperson, phase: Phase): Boolean {
        if (!p.level().isRaining || phase != Phase.WORK && phase != Phase.FREE) return false
        return when (p.role) {
            TownRole.FARMER, TownRole.FISHER, TownRole.CHILD, TownRole.NOBLE, TownRole.BARD -> true
            TownRole.CITIZEN -> phase == Phase.FREE || p.work == null
            else -> phase == Phase.FREE
        }
    }

    /** After dark and before dawn, when those out carry a lantern. */
    fun dark(p: Townsperson): Boolean {
        val t = Math.floorMod(p.level().overworldClockTime, 24000L)
        return t in 12800..23200
    }
}

/**
 * Runs a townsperson's day: one [Task] at a time, the next chosen by [Jobs] for the part of the day it is and
 * their role. A new part of the day ends whatever they were at.
 */
class Routine(private val p: Townsperson) {
    var task: Task? = null
        private set
    var phase: Phase? = null
        private set

    /** Whether they're keeping still at work, on a seat or in bed. */
    val anchored: Boolean get() = task?.anchored == true

    /** Places found once and remembered: beds, anvils, ovens, fishing spots. */
    private val known = HashMap<String, BlockPos>()
    private val missed = HashMap<String, Long>()

    fun tick() {
        val level = p.level() as? ServerLevel ?: return
        if (phase == null || p.tickCount % 20 == 0) {
            val now = Schedule.phase(p)
            if (now != phase) {
                val first = phase == null
                phase = now
                if (!first) end()
            }
            p.lantern = !p.isSleeping && Schedule.dark(p) && now != Phase.SLEEP &&
                (p.role == TownRole.GUARD || task !is Sit && task !is Work)
        }
        val other = p.attending
        if (other != null && task?.anchored != true) {
            // Stopped in the street by a player
            p.navigation.stop()
            p.lookAt(other)
            if (!p.activity.overlay) p.act(Activity.NONE)
            return
        }
        val current = task ?: Jobs.next(p, phase ?: Phase.FREE).also {
            task = it
            it.start()
        }
        val going = current.tick()
        // A task may hand over to another (tag!), in which case that one's already started
        if (task !== current) return
        if (!going) {
            current.stop()
            task = current.then
            task?.start()
        }
    }

    /** Drops whatever they were doing (fleeing, fighting, a new part of the day). */
    fun interrupt() = end()

    /** Swaps whatever they're doing for [next], which starts at once. */
    fun replace(next: Task) {
        task?.stop()
        task = next
        next.start()
    }

    private fun end() {
        task?.stop()
        task = null
    }

    /**
     * The place remembered as [key], if [valid] still holds for it; otherwise looked for again with [find],
     * though not more than once in a while when there's nothing to find.
     */
    fun recall(key: String, valid: (BlockPos) -> Boolean, find: () -> BlockPos?): BlockPos? {
        known[key]?.let { if (p.level().isLoaded(it) && valid(it)) return it else known.remove(key) }
        val now = p.level().gameTime
        if (now < (missed[key] ?: Long.MIN_VALUE)) return null
        val found = find()
        if (found == null) missed[key] = now + 600 + p.random.nextInt(600) else known[key] = found
        return found
    }

    /** Forgets [key], so it's looked for afresh. */
    fun forget(key: String) {
        known.remove(key)
    }
}
