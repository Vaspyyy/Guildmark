package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * The Empty Chair's once-a-second care (living-world/stories/design.md 8.2), Tales T4, while Town Tales is on and a player is online:
 *
 *  - **The doorstep.** Until the laying (steps 0 to 3), from 17:00 to 20:00 while a player is within 64 blocks of the mourner's door, the
 *    mourner is held there, seated if there is a seat by it: S1 plays on it at step 0 ([ChairScenes.count]), and the tracker points there.
 *  - **The gathering.** On the laying's day from half past four, while a player is within 64 blocks of the place, the mourner, the priest and
 *    the first friend who comes are held a few steps from where the stone stands or will stand, walking there at their own pace; the other
 *    friends who come walk up when the laying takes them.
 *  - **The laying not seen.** At a quarter to eight on its day (or later), with something done and no laying playing, the tale ends with what
 *    was done ([Chair.finish]): the stone, the posy and the candles are set at once. With nothing done it waits for the close (W).
 *
 * `tale chair lay` ([force]) has the laying now, whatever the hour, for two game hours; if it has not been seen through by then the tale ends
 * as above. What it keeps (the marks, the laying playing, a forced laying) is memory and lost on a restart; the holds are the engine's.
 */
internal object ChairWatch {
    /** The doorstep's hours, the hour the cast gather at the place on the laying's day, and the hour a laying not seen is over. */
    private const val DOOR_FROM = 17.0
    private const val DOOR_TO = 20.0
    private const val GATHER = 16.5
    private const val OVER = 19.75

    /** How near a player must be for the mourner to be held at the door, or the cast at the place. */
    private const val NEAR = 64.0

    /** How long `tale chair lay` has the laying at any hour, in ticks (two game hours). */
    private const val FORCED = 2000L

    private class Memo {
        var forcedUntil = 0L
        val marks = HashMap<String, BlockPos>()
        var stage: Stage? = null
    }

    private val memos = HashMap<Int, Memo>()
    private var now = 0L

    /** One line in `Tales.register`: the commands and scenes, and the open chairs seen to once a second. */
    fun register() {
        ChairCommands.register()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> memos.clear() }
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % 20 != 7 || !Tales.live) return
        val level = server.overworld()
        if (level.players().isEmpty()) return
        now = level.gameTime
        val open = TaleEngine.openTales(level)
        for (t in open.toList()) {
            if (t.template != Chair.id || !t.open) continue
            TaleLook.guarded(level, "the chair's second") { second(level, t) }
        }
        if (memos.size > 8) memos.keys.retainAll(open.map { it.id }.toSet())
    }

    /** The hour of the clock, 2 to 26 (a market day starts at two in the morning). */
    private fun hour(level: ServerLevel): Double = Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0

    /** Whether `tale chair lay` has the laying of [t] at any hour now. */
    fun forced(t: Tale): Boolean = (memos[t.id]?.forcedUntil ?: 0L) > now

    /** `tale chair lay`: the laying of [t] may be played now and for two game hours, and the cast gather for it; false if it is not at the laying. */
    fun force(c: Ctx, t: Tale): Boolean {
        if (!t.open || t.step != Chair.AT_LAY) return false
        now = c.level.gameTime
        memos.getOrPut(t.id) { Memo() }.forcedUntil = now + FORCED
        return true
    }

    /** The laying of [t] is on the stage (waiting to be taken, or playing): the ending waits for it. */
    fun playing(t: Tale, stage: Stage) {
        memos.getOrPut(t.id) { Memo() }.stage = stage
    }

    /** [slot] of tale [id] is someone else now: their mark is worked out again. */
    fun forget(id: Int, slot: String) {
        memos[id]?.marks?.remove(slot)
    }

    /** [t] ended: nothing of it is kept. */
    fun ended(@Suppress("UNUSED_PARAMETER") level: ServerLevel, t: Tale) {
        memos.remove(t.id)
    }

    private fun busy(memo: Memo): Boolean = memo.stage?.let { it.started && !it.finished } == true

    private fun second(level: ServerLevel, t: Tale) {
        val c = TaleEngine.ctx(level, t.capital) ?: return
        val memo = memos.getOrPut(t.id) { Memo() }
        if (busy(memo)) return
        val hour = hour(level)
        val today = c.day
        val forced = memo.forcedUntil > now
        if (t.step == Chair.AT_LAY) {
            if (memo.forcedUntil != 0L && !forced) {
                // A `tale chair lay` whose laying was not seen through: it ends with what was done
                memo.forcedUntil = 0L
                if (Chair.letter(t) != Ending.W) {
                    Chair.finish(c, t)
                    return
                }
            }
            val lay = Chair.layDay(t)
            if (!forced && Chair.letter(t) != Ending.W && (today > lay || today == lay && hour >= OVER)) {
                Chair.finish(c, t)
                return
            }
        }
        if (!Tales.scenes()) {
            release(c, t, memo, listOf(Chair.MOURNER, Chair.PRIEST) + Chair.FRIENDS)
            return
        }
        if (t.step < Chair.AT_LAY) doorstep(c, t, memo, hour) else gather(c, t, memo, hour, today, forced)
    }

    /** Steps 0 to 3: the mourner at their door in the doorstep's hours, while a player is near; about their day otherwise. */
    private fun doorstep(c: Ctx, t: Tale, memo: Memo, hour: Double) {
        val level = c.level
        release(c, t, memo, listOf(Chair.PRIEST) + Chair.FRIENDS)
        val door = Chair.door(t, Chair.MOURNER)
        if (door == null || hour < DOOR_FROM || hour >= DOOR_TO || !near(level, door)) {
            release(c, t, memo, listOf(Chair.MOURNER))
            return
        }
        if (t.cast(Chair.MOURNER)?.held == true && memo.marks[Chair.MOURNER] == door) return
        val body = TaleBodies.body(level, t, Chair.MOURNER)
        val seat = body?.let { Places.seat(level, door, 3, it) }
        put(c, t, memo, Chair.MOURNER, door, if (seat != null) Activity.SIT else null)
    }

    /** Step 4: on the laying's day from half past four (or a forced laying), the mourner, the priest and the first friend who comes, near the stone. */
    private fun gather(c: Ctx, t: Tale, memo: Memo, hour: Double, today: Long, forced: Boolean) {
        val level = c.level
        val letter = Chair.letter(t)
        val place = Chair.stoneAt(c, t)
        val lay = Chair.layDay(t)
        val all = listOf(Chair.MOURNER, Chair.PRIEST) + Chair.FRIENDS
        val due = forced || today == lay && hour >= GATHER && hour < OVER
        if (letter == Ending.W || place == null || !due || !near(level, place)) {
            release(c, t, memo, all)
            return
        }
        val slots = listOf(Chair.MOURNER, Chair.PRIEST) + Chair.attending(t, letter).take(1)
        release(c, t, memo, all - slots.toSet())
        for ((i, slot) in slots.withIndex()) {
            val mark = memo.marks[slot] ?: markFor(level, t, slot, place, i) ?: continue
            put(c, t, memo, slot, mark, null)
        }
    }

    /** Where [slot] waits for the laying: a few steps from the stone on the side they come from, each a little further out. */
    private fun markFor(level: ServerLevel, t: Tale, slot: String, place: BlockPos, i: Int): BlockPos? {
        val centre = Vec3.atCenterOf(place)
        val from = TaleBodies.body(level, t, slot)?.position() ?: centre.add(3.0, 0.0, 0.0)
        return Sets.beside(level, centre, from, 3.0 + i * 0.8)
    }

    private fun near(level: ServerLevel, at: BlockPos): Boolean = level.getNearestPlayer(at.x + 0.5, at.y + 0.5, at.z + 0.5, NEAR, false) != null

    /** [slot] held at [at] playing [clip], asked again only when the mark changes. */
    private fun put(c: Ctx, t: Tale, memo: Memo, slot: String, at: BlockPos, clip: Activity?) {
        val cast = t.cast(slot) ?: return
        if (cast.gone.isNotEmpty() || cast.held && memo.marks[slot] == at) return
        if (c.hold(t, slot, at, clip)) memo.marks[slot] = at
    }

    /** [slots] let go to their days, if they are held. */
    private fun release(c: Ctx, t: Tale, memo: Memo, slots: Collection<String>) {
        for (slot in slots) {
            if (t.cast(slot)?.held != true) continue
            c.release(t, slot)
            memo.marks.remove(slot)
        }
    }
}
