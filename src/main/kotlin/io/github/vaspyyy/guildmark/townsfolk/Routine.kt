package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

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
            // The Quiet Hand keep a thief's hours: up late, dice in the attic, the taproom, then the night's work
            TownRole.THIEF -> when {
                t < 4000 -> Phase.SLEEP
                t < 4500 -> Phase.WAKE
                t < 11000 -> Phase.WORK
                t < 15500 -> Phase.EVENING
                t < 22000 -> Phase.FREE
                t < 22500 -> Phase.HOME
                else -> Phase.SLEEP
            }
            // The Starwatch read on into the night, when the sky shows, and nod off over their books before dawn
            TownRole.MAGE -> when {
                t < 1500 -> Phase.SLEEP
                t < 2000 -> Phase.WAKE
                t < 11500 -> Phase.WORK
                t < 14500 -> Phase.EVENING
                t < 20000 -> Phase.FREE
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
 * Runs a townsperson's day: one [Task] at a time. A capital's people follow the day's agenda ([Days]), each
 * stretch's parts in turn ([Acts]); a new stretch lets what they're at finish (or a little longer) before they
 * move on. Everyone else has the next task chosen by [Jobs] for the part of the day it is and their role, and a
 * new part of the day ends whatever they were at.
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

    /** Tick by which what they were at when a new stretch began must be done; 0 if nothing is winding down. */
    private var windDown = 0
    private var startedAt = 0

    /** A new part of their day has begun, and what they're at should come to an end soon (getting up off a seat). */
    val windingDown: Boolean get() = windDown != 0

    fun tick() {
        val level = p.level() as? ServerLevel ?: return
        val life = Days.life(p)
        if (phase == null || p.tickCount % 20 == 0) {
            val now = if (life != null) agenda(level, life) else Schedule.phase(p).also { now ->
                if (now != phase) {
                    val first = phase == null
                    phase = now
                    if (!first) end()
                }
            }
            phase = now
            val indoors = (life ?: p.life)?.places?.lodgingAt(p.blockPosition())?.contains(p.blockPosition()) == true
            // Thieves keep to the dark, and the leaders to their post
            p.lantern = !p.isSleeping && Schedule.dark(p) && now != Phase.SLEEP && p.role != TownRole.THIEF && !p.isLeader && !indoors &&
                (p.role == TownRole.GUARD || task !is Sit && task !is Work)
        }
        if (windDown != 0 && p.tickCount >= windDown) end()
        val other = p.attending
        if (other != null && task?.anchored != true) {
            // Stopped in the street by a player
            p.navigation.stop()
            p.lookAt(other)
            if (!p.activity.overlay) p.act(Activity.NONE)
            return
        }
        val current = task ?: (if (life != null) Days.next(p, life, life.stretch ?: Days.stretch(life, level.overworldClockTime)) else Jobs.next(p, phase ?: Phase.FREE)).also {
            task = it
            windDown = 0
            startedAt = p.tickCount
            it.start()
        }
        val going = current.tick()
        // A task may hand over to another (tag!), in which case that one's already started
        if (task !== current) return
        if (!going) {
            current.stop()
            // Something that ended as soon as it began: somewhere they couldn't get to, a seat already taken
            if (life != null && !current.instant && current !is Climb && (current !is Walk || current.failed)) {
                if (p.tickCount - startedAt <= 5) life.balked++ else life.balked = 0
            }
            task = current.then
            startedAt = p.tickCount
            task?.start()
        }
    }

    /** Keeps [life] in step with their agenda; a new stretch winds down what they were at. Gives the part of the day it is. */
    private fun agenda(level: ServerLevel, life: Life): Phase {
        val stretch = Days.stretch(life, level.overworldClockTime)
        val was = life.stretch
        if (stretch !== was) {
            if (was != null && was.doing == stretch.doing) life.stretch = stretch
            else {
                Days.begin(p, life, stretch)
                if (was != null) windDown(stretch.doing)
            }
        }
        return stretch.doing.phase
    }

    /** A new stretch began: standing about or on the way somewhere ends now; anything else gets a little longer to finish. */
    private fun windDown(doing: Doing) {
        val current = task ?: return
        // Already in bed for the night that's starting
        if (current is Sleep && doing == Doing.SLEEP) return
        if (current is Idle || current is Walk) end() else windDown = p.tickCount + WIND_DOWN
    }

    /** Drops whatever they were doing (fleeing, fighting, a new part of the day). */
    fun interrupt() = end()

    /** Swaps whatever they're doing for [next], which starts at once. */
    fun replace(next: Task) {
        task?.stop()
        task = next
        windDown = 0
        startedAt = p.tickCount
        next.start()
    }

    private fun end() {
        task?.stop()
        task = null
        windDown = 0
        p.carried = ItemStack.EMPTY
        p.opened = null
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

    private companion object {
        /** Ticks what they were at may run on into a new stretch of their day. */
        const val WIND_DOWN = 200
    }
}
