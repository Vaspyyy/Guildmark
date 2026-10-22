package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * A labourer's building day (living-world/design-influence.md section 3, "The labourers"). On a day
 * [Works.labourers] names one of a capital's spare citizens, [Hands] lends them, and their day is a building day
 * ([Doing.BUILD]): they walk to [Works.workAt] at seven, work till sunset with an hour at noon, and go home.
 *
 * At the site the building's crew ([Works.crew], Part B's) gives them their work, as a [Director] set straight on
 * them that has charge of them for as long as it likes ([lend]). The director needn't watch the clock or the town:
 * every second it's looked at here, and they're let go (the director taken off them, [Works.Crew.release] told, their
 * day picking up from the stretch they're in) when the stretch is over (noon, sunset), when they're hurt, fight or
 * run, when their body goes, or when something else takes charge of them (a player talking with them). The director
 * lets them go itself by setting [Townsperson.director] to null, as directors do. Until the crew gives them work, or
 * whenever it gives none, they wait about the site as a crew does between loads: arms folded by the yard, an eye on
 * the work, a word with the others, a sit, a mopped brow.
 *
 * They're residents all through: they keep their names and homes, are let go at sunset rather than vanishing, and
 * can be hurt like anyone in town.
 */
object Labour {
    /** The hour the work clock stops: daylight's end ([io.github.vaspyyy.guildmark.homestead.Homestead.WORKDAY]). */
    const val SUNSET = 18.0

    /** How near the place they report at a labourer must be to be at the site, and how near they stand about it. */
    private const val AT_SITE = 10.0
    private const val ABOUT = 5

    /** Those working at the site under the crew's director just now, and that director. */
    private val lent = WeakHashMap<Townsperson, Director>()

    /** When each was last given work, so a crew that lets them go at once isn't asked again every tick. */
    private val asked = WeakHashMap<Townsperson, Long>()

    /** The fewest ticks between one director given and the crew being asked again. */
    private const val ASK_EVERY = 40L

    /** The game tick the lent were last looked at (halved, so `now - looked` can't overflow before the first look). */
    private var looked = Long.MIN_VALUE / 2

    /** Where [life]'s capital's labourers report for work, if anywhere. */
    fun site(p: Townsperson, life: Life): BlockPos? {
        val level = p.level() as? ServerLevel ?: return null
        return Works.workAt(level, life.places.centre.asLong())
    }

    /** A building stretch's first part: to the site, if they aren't there. */
    fun report(p: Townsperson, life: Life): Task? {
        val at = site(p, life) ?: return null
        if (p.blockPosition().distSqr(at) <= AT_SITE * AT_SITE) return null
        return Ways.to(p, life.places, at, Jobs.WALK, near = 2.5)
    }

    /**
     * What fills a building stretch: the work the crew gives them, or waiting about the site for it. With no site to
     * go to (the works done, or called off), their day is as any other working day of theirs.
     */
    fun work(p: Townsperson, life: Life): Task {
        val level = p.level() as ServerLevel
        // The agenda's looked at only every second: past the stretch's end, nothing more begins
        if (Days.stretch(life, level.overworldClockTime).doing != Doing.BUILD) return Idle(p, 20, chatty = false)
        val at = site(p, life) ?: return Acts.filler(p, life, Doing.WORK)
        if (p.blockPosition().distSqr(at) > AT_SITE * AT_SITE) return Ways.to(p, life.places, at, Jobs.WALK, near = 2.5)
        val now = level.gameTime
        if (now - (asked[p] ?: Long.MIN_VALUE / 2) >= ASK_EVERY) {
            val director = Works.crew?.labour(level, p)
            if (director != null) {
                asked[p] = now
                return Lending(p, director)
            }
        }
        return waitAbout(p, life, at)
    }

    /**
     * Every second, from the players' ticks ([io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes.tick]): the lent are
     * let go whose stretch is over, who are hurt or fighting, or whom something else has charge of.
     */
    fun tick(level: ServerLevel) {
        if (lent.isEmpty()) return
        val now = level.gameTime
        if (now - looked < 20 && now >= looked) return
        looked = now
        for ((p, director) in lent.entries.toList()) {
            if (p.level() !== level) continue
            when {
                !p.isAlive || p.isRemoved -> let(level, p, director, fresh = false)
                // The building let them go, or a player stopped them to talk: either way, not the crew's now
                p.director !== director -> let(level, p, director, fresh = false)
                p.hurtTime > 0 || p.target != null || p.activity == Activity.FLEE -> let(level, p, director, fresh = true)
                else -> {
                    val life = Days.life(p)
                    if (life == null || Days.stretch(life, level.overworldClockTime).doing != Doing.BUILD) let(level, p, director, fresh = true)
                }
            }
        }
    }

    /** Whether [p] is at work for the crew just now. */
    fun lentNow(p: Townsperson): Boolean = lent[p]?.let { p.director === it } == true

    /** [p] is the crew's to direct: [director] has charge of them from the next tick. */
    private fun lend(p: Townsperson, director: Director) {
        p.navigation.stop()
        p.director = director
        lent[p] = director
    }

    /** [p] is let go from [director]: the crew is told, and (if [fresh]) their day picks up from the stretch they're in. */
    private fun let(level: ServerLevel, p: Townsperson, director: Director, fresh: Boolean) {
        lent.remove(p)
        val mine = p.director === director
        if (mine) p.director = null
        try {
            Works.crew?.release(level, p)
        } finally {
            if (mine && fresh && p.isAlive) {
                p.navigation.stop()
                p.routine.interrupt()
                if (!p.activity.overlay) p.act(Activity.NONE)
            }
        }
    }

    /** Hands [p] to the crew's director as it starts (the director has them from the next tick); done at once. */
    private class Lending(p: Townsperson, private val director: Director) : Task(p) {
        override val instant: Boolean get() = true

        override fun start() {
            if (p.director == null) lend(p, director)
        }

        override fun run(): Boolean = false
    }

    // --- Waiting about the site ---

    /**
     * Between loads, or with no work given yet: a while stood by the yard with their arms folded and an eye on the
     * work, a mopped brow, a word now and then ("Nearly our turn."); or a word with another of the crew, or a sit.
     * (Also `/guildmark capital scene labour`'s, about a stand-in site.)
     */
    internal fun waitAbout(p: Townsperson, life: Life, at: BlockPos): Task {
        val level = p.level() as ServerLevel
        val works = Works.at(level, at)?.takeIf { it.state == WorksState.BUILDING || it.state == WorksState.FUNDED }
        val heart = works?.site?.lot?.center ?: at
        when (p.random.nextInt(6)) {
            0 -> Social.chat(p)?.let { return it }
            1 -> Places.seat(level, at, ABOUT + 3, p)?.let { return Sit(p, it, 240 + p.random.nextInt(240), listOf(Activity.SIT, Activity.SIT, Activity.SIT_TALK)) }
        }
        val stand = stand(level, p, at, works?.site?.lot) ?: return Idle(p, 100 + p.random.nextInt(100), Activity.ARMS_FOLDED)
        val look = Vec3(heart.x + 0.5, heart.y + 1.5, heart.z + 0.5)
        val facing = Direction.fromYRot(yaw(stand, look).toDouble())
        val first = Step(Activity.ARMS_FOLDED, Activity.ARMS_FOLDED.ticks * (2 + p.random.nextInt(3)))
        val steps = listOf(
            first,
            Step(if (p.random.nextInt(3) == 0) Activity.MOP_BROW else Activity.NONE, 40),
            Step(Activity.GAZE, Activity.GAZE.ticks * (1 + p.random.nextInt(2))),
            Step(Activity.ARMS_FOLDED, Activity.ARMS_FOLDED.ticks),
        )
        return Work(p, Spot(stand, facing), look, steps, onTick = { s, t ->
            // The homestead crew's own words for waiting, now and then: a bark, so with the street talk on, and one
            // of them at a time (the lines are voiced)
            if (s === first && t == 60 && p.random.nextInt(3) == 0 && Words.on() && !Words.over(p)) p.say("homestead_wait_${1 + p.random.nextInt(3)}")
        })
    }

    /** Somewhere to stand about [at], off the works' lot, nobody else's; null if nowhere will do. */
    private fun stand(level: ServerLevel, p: Townsperson, at: BlockPos, lot: net.minecraft.world.level.levelgen.structure.BoundingBox?): BlockPos? {
        repeat(10) {
            val x = at.x + p.random.nextInt(ABOUT * 2 + 1) - ABOUT
            val z = at.z + p.random.nextInt(ABOUT * 2 + 1) - ABOUT
            for (dy in intArrayOf(0, 1, -1, 2, -2)) {
                val pos = BlockPos(x, at.y + dy, z)
                if (lot != null && lot.isInside(pos)) continue
                if (level.isLoaded(pos) && Places.standable(level, pos) && Claims.free(level, pos, p)) return pos
            }
        }
        return null
    }

    private fun yaw(from: BlockPos, to: Vec3): Float =
        (kotlin.math.atan2(to.z - (from.z + 0.5), to.x - (from.x + 0.5)) * (180.0 / Math.PI)).toFloat() - 90f
}
