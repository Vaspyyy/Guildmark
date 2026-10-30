package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * Whose turn it is to speak where a village's moments put folk talking near one another (the horn's shouts and the lines at the
 * doors, a birth's well-wishers, a newcomer's welcome): a voiced line holds the floor round where it is said ([NEAR] blocks) for
 * as long as its take lasts and a breath after ([BREATH]), and a line wanting the floor near it waits for that. A scene that has
 * a line to come at a move's moment asks [freeBy] before it starts the move; a line said through [say] with the floor taken waits
 * its turn ([WAIT] at most), said as soon as the floor is free and still unsaid if it never is.
 *
 * Memory only, and only a handful at a time: each line said through it is one, gone once its time is past. Nothing is looked at
 * while nothing waits.
 */
object Turns {
    /** How near another's line must be for a line to wait for it. */
    const val NEAR = 16.0

    /** The breath between one line's end and the next one's start. */
    const val BREATH = 6

    /** The longest a line said through [say] waits for its turn. */
    const val WAIT = 100

    private class Held(val dimension: ResourceKey<Level>, val at: Vec3, val until: Long, val by: Int)

    private class Waiting(val p: Townsperson, val ticks: Int, val most: Long, val say: () -> Boolean)

    private val held = ArrayList<Held>()
    private val waiting = ArrayList<Waiting>()

    /** At setup (from [Villages.register]): the lines waiting their turn, and the floor forgotten at a server stop. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (waiting.isNotEmpty()) tick() }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            held.clear()
            waiting.clear()
        }
    }

    /** The game time from which the floor at [at] in [level] is free (now, if it is free now), [p]'s own lines aside. */
    fun freeFrom(level: ServerLevel, at: Vec3, p: Townsperson? = null): Long {
        val now = level.gameTime
        held.removeIf { it.until <= now }
        var out = now
        val dimension = level.dimension()
        for (h in held) if (h.dimension == dimension && h.by != p?.id && h.at.distanceToSqr(at) < NEAR * NEAR) out = maxOf(out, h.until)
        return out
    }

    /** Whether the floor round [p] will be free [ticks] from now, when a line of theirs would begin (a move's moment away). */
    fun freeBy(p: Townsperson, ticks: Int = 0): Boolean {
        val level = p.level() as? ServerLevel ?: return true
        return freeFrom(level, p.position(), p) <= level.gameTime + ticks
    }

    /** [p] has begun a line [ticks] long: the floor round them is theirs until it ends and a breath after. */
    fun hold(p: Townsperson, ticks: Int) {
        val level = p.level() as? ServerLevel ?: return
        held += Held(level.dimension(), p.position(), level.gameTime + ticks + BREATH, p.id)
    }

    /**
     * [p]'s line [ticks] long said by [say] (true if it was said) now if the floor round them is free, else as soon as it is (within
     * [WAIT] ticks, while they live): true if it was said or waits its turn.
     */
    fun say(p: Townsperson, ticks: Int, say: () -> Boolean): Boolean {
        val level = p.level() as? ServerLevel ?: return false
        if (waiting.none { it.p === p } && freeFrom(level, p.position(), p) <= level.gameTime) {
            if (!say()) return false
            hold(p, ticks)
            return true
        }
        // One line waiting at a time for each: a newer one takes the place of one still waiting
        waiting.removeIf { it.p === p }
        waiting += Waiting(p, ticks, level.gameTime + WAIT, say)
        return true
    }

    /** The lines waiting their turn: each said once the floor round its speaker is free, in the order they came. */
    private fun tick() {
        val it = waiting.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val level = w.p.level() as? ServerLevel
            if (level == null || !w.p.isAlive || w.p.isRemoved || level.gameTime > w.most) {
                it.remove()
                continue
            }
            if (freeFrom(level, w.p.position(), w.p) > level.gameTime) continue
            it.remove()
            if (w.say()) hold(w.p, w.ticks)
        }
    }
}
