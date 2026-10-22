package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleEscort
import io.github.vaspyyy.guildmark.tales.TaleGuard
import io.github.vaspyyy.guildmark.tales.TaleLeave
import io.github.vaspyyy.guildmark.tales.TaleTalk
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The child tale's once-a-second look at the world (living-world/stories/design.md 8.4), which no step of the table can be: a child goes
 * out at three in the afternoon without any step happening, and is found by a player coming near. Everything here is memory: the record
 * ([Child]'s numbers and flags, the cast's `held` flags) is the truth, and what this keeps (the marks it has put people at, the clearing it
 * found, who began the walk) is worked out again from it after a restart.
 *
 * - **The child.** From 15:00 (or a forced start) the child is held at a mark: the wardfire's clearing if a wardfire stands within
 *   [Child.CLEARING] blocks of the place on the road (only loaded land is looked at, so it is looked for again as land loads), else the
 *   road's edge. They sit if there is a bench or a stair to sit on, shiver once the light is gone, and carry a cloth bundle.
 * - **The find.** The first player within [FIND] blocks of the child, at the mark, finds them: the player takes the tale up, the bit is
 *   set, and the tale is at the walk home. A child nobody speaks to asks on their own after ten seconds.
 * - **The gate.** The parent waits at the gate from dusk, and from the find; the guard stands at it while he is to be asked.
 * - **The rumour.** From half past three the parent has missed the child, and the town hears of it.
 */
internal object ChildWatch {
    /** The player who began each tale's walk home (the deciding deed of A and B), by tale. */
    val walkers = HashMap<Int, UUID>()

    /** The wardfire each tale's child was found a clearing at. */
    val clearings = HashMap<Int, BlockPos>()

    /** The `tale:slot`s this run has put at a mark; asked again after a restart, and when a stand-in takes a slot. */
    val placed = HashSet<String>()

    /** The mark and the clip each child was last held with, by tale. */
    private val marks = HashMap<Int, BlockPos>()
    private val clips = HashMap<Int, Activity?>()

    /** The tales whose clearing has been looked for with all the land round the place loaded. */
    private val looked = HashSet<Int>()

    /** When the child of each tale was found (game time), and the tales whose child has asked of their own accord. */
    private val foundAt = HashMap<Int, Long>()
    private val prompted = HashSet<Int>()

    /** How near a player must be to find the child, how near the child must be to their mark for that, how near the player for the prompt. */
    private const val FIND = 6.0
    private const val AT_MARK = 16.0
    private const val PROMPT_AFTER = 200L
    private const val PROMPT_NEAR = 16.0

    /** How near the gate the parent's or the guard's day body must be to be held there, if a player could see it go. */
    private const val GATE_NEAR = 44.0

    /** How far to the side of the road the child sits when no wardfire is near, and how far from a wardfire. */
    private const val EDGE = 3.0
    private const val BESIDE_FIRE = 2

    // ---- Once a second ----

    /** From the tick, once a second: each open child tale is looked at, guarded. */
    fun watch(server: MinecraftServer) {
        if (!Tales.live) return
        val level = server.overworld()
        for (t in TaleEngine.openTales(level)) {
            if (t.template != Child.id) continue
            val c = TaleEngine.ctx(level, t.capital) ?: continue
            TaleGuard.run(c, t, "the watch on the road") { see(c, t) }
        }
    }

    private fun see(c: Ctx, t: Tale) {
        val level = c.level
        // The parent has missed the child, and the town hears of it
        if (!Child.flag(t, Child.RUMOUR) && Child.missed(level, t)) {
            Child.set(t, Child.RUMOUR)
            c.data.changed()
            TaleTalk.rumour(level, t)
        }
        if (!Tales.scenes()) return
        if (t.step < Child.AT_WALK) child(c, t)
        if (t.step == Child.AT_GUARD) guard(c, t)
        if (t.step >= Child.AT_WALK || Child.missed(level, t) && Child.clock(level) >= Child.DUSK) parent(c, t)
        if (t.step < Child.AT_WALK) find(c, t)
        if (t.step == Child.AT_WALK) {
            bundle(level, t)
            prompt(level, t)
        }
    }

    // ---- The child ----

    /** Holds the child at their mark (the clearing, else the road's edge), with the clip that suits the hour; asked again when either changes. */
    private fun child(c: Ctx, t: Tale) {
        val level = c.level
        if (!Child.left(level, t)) return
        val cast = t.cast(Child.CHILD) ?: return
        if (cast.resident < 0 || cast.gone.isNotEmpty()) return
        val spot = Child.place(t)
        look(level, t, spot)
        val mark = markFor(c, t, spot)
        val clip = clipFor(level, mark)
        val key = key(t, Child.CHILD)
        if (key in placed && marks[t.id] == mark && clips[t.id] == clip) {
            bundle(level, t)
            return
        }
        if (Tales.hold(level, t, Child.CHILD, mark, clip)) {
            placed += key
            marks[t.id] = mark
            clips[t.id] = clip
        }
        bundle(level, t)
    }

    /** Looks for a wardfire within the clearing's reach of the place once all the land round it is loaded; never reads what is not loaded. */
    private fun look(level: ServerLevel, t: Tale, spot: BlockPos) {
        if (t.id in looked || t.id in clearings) return
        val r = Child.CLEARING
        for (cx in ((spot.x - r) shr 4)..((spot.x + r) shr 4)) for (cz in ((spot.z - r) shr 4)..((spot.z + r) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) return
        }
        looked += t.id
        WardfireSites.nearest(level, spot, r)?.let { clearings[t.id] = it }
    }

    /** Where the child sits: beside the wardfire if one was found, else at the road's edge; the place itself while the land is not loaded to stand on. */
    private fun markFor(c: Ctx, t: Tale, spot: BlockPos): BlockPos {
        val level = c.level
        val fire = clearings[t.id]
        if (fire != null) {
            for ((dx, dz) in listOf(BESIDE_FIRE to 0, -BESIDE_FIRE to 0, 0 to BESIDE_FIRE, 0 to -BESIDE_FIRE)) {
                Sets.standable(level, fire.offset(dx, 0, dz))?.let { return it }
            }
            return fire
        }
        val run = WayOut.run(level, c.here, spot)
        val edge = BlockPos.containing(spot.x + 0.5 - run.z * EDGE, spot.y.toDouble(), spot.z + 0.5 + run.x * EDGE)
        return Sets.standable(level, edge) ?: Sets.standable(level, spot) ?: spot
    }

    /** SHIVER once the light is gone, SIT by day when there is somewhere to sit within three blocks of the mark, else standing. */
    private fun clipFor(level: ServerLevel, mark: BlockPos): Activity? = when {
        Child.clock(level) >= Child.DUSK -> Activity.SHIVER
        Places.nearest(level, mark, 3, 2, null) { state, pos -> Places.isSeat(level, state, pos) } != null -> Activity.SIT
        else -> null
    }

    /** The cloth bundle in the child's arms, put there once their tale body is about. */
    private fun bundle(level: ServerLevel, t: Tale) {
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return
        if (body.tale != t.id || !body.carried.isEmpty) return
        body.carried = ItemStack(Items.BUNDLE)
        body.carriedUntil = 0
    }

    // ---- The find ----

    /** The first player within six blocks of the child, who is at their mark, finds them. */
    private fun find(c: Ctx, t: Tale) {
        val level = c.level
        if (Child.flag(t, Child.FOUND) || !Child.left(level, t)) return
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return
        val mark = marks[t.id] ?: return
        if (body.tale != t.id || body.distanceToSqr(Vec3.atCenterOf(mark)) > AT_MARK * AT_MARK) return
        val who = level.players().filter { it.isAlive && !it.isSpectator && it.distanceToSqr(body) <= FIND * FIND }
            .minByOrNull { it.distanceToSqr(body) } ?: return
        found(c, t, who)
    }

    private fun found(c: Ctx, t: Tale, player: ServerPlayer) {
        val level = c.level
        Child.set(t, Child.FOUND)
        t.partFor(player.uuid)?.let { it.did = it.did or Part.WALKED }
        Tales.join(level, t, player)
        foundAt[t.id] = level.gameTime
        c.why(t, "child.found", player.gameProfile.name, Child.childName(t))
        c.data.changed()
        c.go(t, Child.AT_WALK)
    }

    /** A child nobody has spoken to asks on their own after ten seconds, once. */
    private fun prompt(level: ServerLevel, t: Tale) {
        if (t.id in prompted || Child.flag(t, Child.WALKING)) return
        val at = foundAt[t.id] ?: return
        if (level.gameTime - at < PROMPT_AFTER) return
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return
        if (level.getNearestPlayer(body, PROMPT_NEAR) == null) return
        prompted += t.id
        Words.sayWith(body, "tale.child.found", why = "a lost child")
    }

    // ---- The gate ----

    /** The parent waits at the gate, looking down the road. */
    fun parent(c: Ctx, t: Tale) = gate(c, t, Child.PARENT, Activity.LOOKOUT)

    /** The guard stands at the gate while he is to be asked. */
    private fun guard(c: Ctx, t: Tale) = gate(c, t, Child.GUARD, Activity.STAND_GUARD)

    /**
     * Holds [slot] at the gate's front with [clip], once this run: if their day body is far from it and in sight they are left to their day
     * and tried again (a held body that is far and seen would walk out of the gate and be made again, in sight, at the mark).
     */
    private fun gate(c: Ctx, t: Tale, slot: String, clip: Activity) {
        val level = c.level
        val cast = t.cast(slot) ?: return
        if (cast.resident < 0 || cast.gone.isNotEmpty()) return
        val key = key(t, slot)
        if (key in placed) return
        val front = Child.front(t)
        val body = TaleBodies.body(level, t, slot)
        if (body != null && body.tale != t.id && body.distanceToSqr(Vec3.atCenterOf(front)) > GATE_NEAR * GATE_NEAR && !Comings.unseen(level, body.blockPosition())) return
        if (Tales.hold(level, t, slot, front, clip)) placed += key
    }

    // ---- The end ----

    /** The tale is ending: the walk is called off and the bundle put down. */
    fun letGo(c: Ctx, t: Tale) {
        walkers.remove(t.id)
        val body = TaleBodies.body(c.level, t, Child.CHILD) ?: return
        (body.director as? TaleEscort)?.cancel(body)
        if (body.carried.`is`(Items.BUNDLE)) {
            body.carried = ItemStack.EMPTY
            body.carriedUntil = 0
        }
    }

    /**
     * The child is brought in by the watch (C and W): if a player could see them they walk the road to the gate and are gone there, and
     * are made again at home out of sight; if not, the release does it (dropped where they stand and made again at home).
     */
    fun bringIn(c: Ctx, t: Tale) {
        val level = c.level
        val body = TaleBodies.body(level, t, Child.CHILD) ?: return
        val r = c.resident(t, Child.CHILD) ?: return
        val from = body.blockPosition()
        if (Comings.unseen(level, from)) return
        val way = WayOut.homeward(level, c.here, from)
        if (way.isEmpty()) return
        val capital = r.capital.asLong()
        body.navigation.stop()
        body.carried = ItemStack.EMPTY
        body.carriedUntil = 0
        body.director = TaleLeave(way) { p ->
            p.discard()
            Comings.later(level, capital, listOf(r.id))
        }
    }

    // ---- Forgetting ----

    private fun key(t: Tale, slot: String): String = "${t.id}:$slot"

    /** A tale is over: what this kept of it is let go. */
    fun forget(tale: Int) {
        walkers.remove(tale)
        clearings.remove(tale)
        marks.remove(tale)
        clips.remove(tale)
        looked.remove(tale)
        foundAt.remove(tale)
        prompted.remove(tale)
        placed.removeIf { it.startsWith("$tale:") }
    }

    /** The server stopped: all of it forgotten. */
    fun forgetAll() {
        walkers.clear()
        clearings.clear()
        placed.clear()
        marks.clear()
        clips.clear()
        looked.clear()
        foundAt.clear()
        prompted.clear()
    }
}
