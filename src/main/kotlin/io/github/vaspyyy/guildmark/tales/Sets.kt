package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Fx
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Hold
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The set pieces every tale's scenes are made of (living-world/stories/design.md 4.3): a function for each that takes the
 * bodies that play it and answers a [Stage], so a helper writing the next template composes and does not invent. A template's
 * `scene` finds its people ([TaleBodies.body], [passerBy], [freeAdults]), calls a set with them and may set [Stage.then] to move
 * its step on; the stage is played by [TaleLook] (or by `tale scene`). Each set names the hours it may play in (a template
 * passes its own), and none of them moves a body that is not part of it: the parts are people where their day has them.
 *
 * The parts are played as the people do their work: a keeper or a sitter stays at their post or in their seat and goes back to
 * it ([Stage.Part.station]); someone passing walks, at the pace of a stroll. Where a clip is not drawn yet (GRIEVE, LAY_POSY...)
 * the body stands in the rest pose for its length, so the timing reads the same once it is.
 */
object Sets {
    // ---- Props ----

    /** The vanilla stand-in of each look of the carried props (13.2.3) until the drawn `tale_prop` is in. */
    private val STAND_INS: Map<String, Item> = mapOf(
        "note_sealed" to Items.PAPER,
        "ransom_pouch" to Items.BUNDLE,
        "slate" to Items.PAPER,
        "thread_paper" to Items.PAPER,
        "locket" to Items.GOLD_NUGGET,
        "blanket" to Items.WOOL.pick(DyeColor.BLUE),
        "washing" to Items.WOOL.pick(DyeColor.WHITE),
        "lantern_paper_dark" to Items.LANTERN,
        "lantern_paper_lit" to Items.LANTERN,
        "miners_lamp_dark" to Items.LANTERN,
        "miners_lamp_lit" to Items.LANTERN,
        "posy" to Items.POPPY,
    )

    /**
     * What a body holds for the look [look] of the carried props (13.2.3): [standIn], the vanilla item that does for it, until
     * the drawn `tale_prop` is in (then this one function gives it, and nothing that calls it changes).
     */
    fun prop(look: String, standIn: Item): ItemStack = ItemStack(standIn)

    /** The prop of [look] with its own stand-in (a look that is not one of the twelve is a sheet of paper). */
    fun prop(look: String): ItemStack = prop(look, STAND_INS[look] ?: Items.PAPER)

    // ---- Finding people and places ----

    /**
     * The adults near [around] (within [r] blocks) who are free to be a part of a scene ([Aside.free]; with [station], a worker at
     * their post or someone seated is free too), residents first and then the nearest, leaving out [except] and the fighters.
     */
    fun freeAdults(level: ServerLevel, around: Vec3, r: Double, except: Collection<Townsperson> = listOf(), station: Boolean = false): List<Townsperson> =
        level.getEntitiesOfClass(Townsperson::class.java, AABB.ofSize(around, 2 * r, 16.0, 2 * r)) {
            it.role.adult && !it.fights && it !in except && it.distanceToSqr(around) <= r * r && Aside.free(it, seated = station, station = station)
        }.sortedWith(compareBy<Townsperson>({ if (it.resident >= 0) 0 else 1 }, { it.distanceToSqr(around) }))

    /** The nearest free adult of the street near [near], other than [near] and [except]: the passer-by of a scene. */
    fun passerBy(near: Townsperson, except: Collection<Townsperson> = listOf(), r: Double = 24.0): Townsperson? =
        freeAdults(near.level() as ServerLevel, near.position(), r, except + near).firstOrNull()

    /** A place to stand [gap] blocks from [of] on the side [from] is on, or the nearest block that can be stood on; null if none. */
    fun beside(level: ServerLevel, of: Vec3, from: Vec3, gap: Double): BlockPos? {
        var dx = from.x - of.x
        var dz = from.z - of.z
        val len = sqrt(dx * dx + dz * dz)
        if (len < 0.1) {
            dx = 1.0
            dz = 0.0
        } else {
            dx /= len
            dz /= len
        }
        return standable(level, BlockPos.containing(of.x + dx * gap, of.y, of.z + dz * gap))
    }

    /** [base], or a block a step above, below or to a side of it, that can be stood on; null if none. */
    fun standable(level: ServerLevel, base: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, 1, -1, 2, -2)) {
            val pos = base.above(dy)
            if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
        }
        for (d in Direction.Plane.HORIZONTAL) {
            for (dy in intArrayOf(0, 1, -1)) {
                val pos = base.relative(d).above(dy)
                if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
            }
        }
        return null
    }

    /** Whether [state] is a fire a paper can be burnt in: an oven or a smoker, a furnace, or a campfire that is lit. */
    fun fire(state: BlockState): Boolean =
        Jobs.oven(state) || state.`is`(Blocks.BLAST_FURNACE) || (state.`is`(Blocks.CAMPFIRE) || state.`is`(Blocks.SOUL_CAMPFIRE)) && state.getValue(CampfireBlock.LIT)

    /** The nearest fire (see [fire]) within [r] blocks of [at], if any. */
    fun fireNear(level: ServerLevel, at: BlockPos, r: Int = 16): BlockPos? = Places.nearest(level, at, r, 4, null) { state, _ -> fire(state) }

    /** The nearest bell within [r] blocks of [at], if any. */
    fun bellNear(level: ServerLevel, at: BlockPos, r: Int = 14): BlockPos? = Places.nearest(level, at, r, 4, null) { state, _ -> state.`is`(Blocks.BELL) }

    /** A part for someone who stays where they stand: a worker at their post, or someone seated, is played in place and goes back to it. */
    private fun still(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves, station = body.seat != null || body.routine.anchored, seated = body.seat != null)

    /** A part for someone who walks about in it: not at a post, not seated. */
    private fun walker(slot: String, body: Townsperson, moves: List<Move>, essential: Boolean = true): Stage.Part =
        Stage.Part(slot, body, essential, moves)

    private fun eye(p: Townsperson): () -> Vec3? = { p.eyePosition }

    // ---- S1 The Count ----

    /**
     * S1 The Count (4.3): [keeper], alone with their trouble, at their post or in their seat, counts coins that are not enough:
     * PAUSE_WORK, COUNT, COUNT, SIGH, COUNT, SHRUG, GRIEVE (a seated keeper sits it out: GRIEVE stands). About when the round
     * is done a passer-by ([passer], the nearest free adult of the street) walks up to two blocks, nods and says a comforting
     * word (`tale.set.comfort`); the keeper turns, listens, waves it off and sighs, and goes round once more. About 40 seconds.
     * With no passer-by the keeper goes round alone. Seeds: the note, the lute, the chair, the wagon.
     */
    fun count(keeper: Townsperson, passer: Townsperson? = null, hours: ClosedFloatingPointRange<Double>? = 8.0..18.0): Stage {
        val level = keeper.level() as ServerLevel
        val seated = keeper.seat != null
        val spot = passer?.let { beside(level, keeper.position(), it.position(), 2.0) }
        val k = ArrayList<Move>()
        k += Play(Activity.COUNT, 120)
        k += Play(Activity.SIGH)
        k += Play(Activity.COUNT, 60)
        k += Play(Activity.SHRUG)
        k += Play(if (seated) Activity.SIT else Activity.GRIEVE, 80)
        val parts = ArrayList<Stage.Part>()
        if (passer != null && spot != null) {
            k += Barrier("meet", most = 260, at = eye(passer))
            k += Turn(eye(passer))
            k += Barrier("said", most = 260, pose = if (seated) Activity.SIT else Activity.LISTEN, at = eye(passer))
            k += Play(Activity.WAVE_OFF, at = eye(passer))
            k += Play(Activity.SIGH)
            // The walk up is timed to end as the round does (about eight ticks a block at a stroll), the barrier takes up the rest
            val delay = (260 - 8 * passer.distanceTo(keeper)).toInt().coerceAtLeast(0)
            val p = ArrayList<Move>()
            p += Wait({ _, t -> t >= delay }, most = delay + 1)
            p += GoTo({ spot }, Jobs.STROLL, near = 1.2, most = 500)
            p += Turn(eye(keeper))
            p += Barrier("meet", most = 260, at = eye(keeper))
            p += Play(Activity.NOD, at = eye(keeper))
            p += Say("tale.set.comfort", gesture = Activity.TALK, at = eye(keeper))
            p += Barrier("said", most = 260, at = eye(keeper))
            p += Play(Activity.NOD, at = eye(keeper))
            parts += walker("passer", passer, p)
        }
        k += Play(Activity.COUNT, 60)
        k += Play(Activity.SIGH)
        parts.add(0, still("keeper", keeper, k))
        return Stage("count", parts, hours, keeper.position())
    }

    // ---- S4 The Handing ----

    /**
     * S4 The Handing (4.3): [giver] walks to a block from [taker] with [item] in hand, turns, and holds it out (HAND_OVER, the
     * item in the hand for the first half of it); [taker] RECEIVEs it as the giver lets go, counts it over (COUNT) and nods.
     * About 15 seconds. With [burnAt] (an oven, a smoker, a furnace or a lit campfire: see [fireNear]) it is the Burning, the note's
     * good ending: the taker carries the paper to the fire, stands before it and puts it in (FEED_FIRE, with flame, a ribbon of
     * smoke and ash), and the giver and the [onlookers] (the first two) cheer and clap. About 25 seconds. Null if [burnAt] is not a
     * fire a body can stand in front of. The taker may be at a post or in a seat; the giver and the onlookers walk.
     * Seeds: the note, the wagon (the ransom), the locket (returned), the lute (redeemed).
     */
    fun handing(
        giver: Townsperson, taker: Townsperson, item: ItemStack, burnAt: BlockPos? = null, onlookers: List<Townsperson> = listOf(),
        hours: ClosedFloatingPointRange<Double>? = 8.0..21.0,
    ): Stage? {
        val level = giver.level() as ServerLevel
        val meet = beside(level, taker.position(), giver.position(), 1.0) ?: return null
        val burn = burnAt?.let { fire -> Places.beside(level, fire, taker.blockPosition())?.let { Burn(fire, it) } ?: return null }
        val g = ArrayList<Move>()
        g += Hold(item)
        g += GoTo({ meet }, Jobs.WALK, near = 0.8, overlay = Activity.CARRY, most = 400)
        g += Turn(eye(taker))
        g += Barrier("hand", most = 300, at = eye(taker))
        g += Beat(Activity.HAND_OVER, at = eye(taker)) { p, tick -> if (tick == 16) Stage.give(p, null) }
        g += Barrier("handed", most = 120, at = eye(taker))
        val k = ArrayList<Move>()
        k += Turn(eye(giver))
        k += Barrier("hand", most = 300, at = eye(giver))
        k += Beat(Activity.RECEIVE, at = eye(giver)) { p, tick -> if (tick == 12) Stage.give(p, item) }
        k += Barrier("handed", most = 120, at = eye(giver))
        k += Play(Activity.COUNT, 60)
        k += Play(Activity.NOD, at = eye(giver))
        val parts = ArrayList<Stage.Part>()
        if (burn == null) {
            g += Play(Activity.NOD, at = eye(taker))
            k += Hold(ItemStack.EMPTY)
        } else {
            // The Burning: the paper to the fire, and the giver and the onlookers glad of it
            val fireAt = Vec3.atCenterOf(burn.at)
            k += GoTo({ burn.stand.pos }, Jobs.WALK, near = 0.5, overlay = Activity.CARRY, most = 400)
            k += Turn({ fireAt })
            k += Beat(Activity.FEED_FIRE, at = { fireAt }) { p, tick ->
                if (tick == 16) Stage.give(p, null)
                if (tick >= 12 && tick % Fx.STEP == 0) Fx.play(p.level() as ServerLevel, Fx.Kind.BURN, burn.flame, tick - 12)
            }
            k += Barrier("burnt", most = 400, at = { fireAt })
            k += Play(Activity.NOD, at = eye(giver))
            g += Turn(eye(taker))
            g += Barrier("burnt", most = 1200, pose = Activity.ARMS_FOLDED, at = eye(taker))
            g += Play(Activity.CHEER)
            g += Play(Activity.CLAP)
            for ((i, o) in onlookers.take(2).withIndex()) {
                val m = ArrayList<Move>()
                m += Turn(eye(taker))
                m += Barrier("burnt", most = 1200, pose = Activity.GAZE, at = eye(taker))
                m += Wait({ _, tick -> tick >= 4 * (i + 1) }, most = 20)
                m += Play(Activity.CHEER)
                m += Play(Activity.CLAP)
                parts += walker("onlooker${i + 1}", o, m, essential = false)
            }
        }
        parts.add(0, walker("giver", giver, g))
        parts.add(1, still("taker", taker, k))
        return Stage(if (burn == null) "handing" else "burning", parts, hours, taker.position())
    }

    /** The fire a paper is burnt in: the block, the place to stand before it, and the flame's place over its mouth, at the hands. */
    private class Burn(val at: BlockPos, val stand: Spot) {
        val flame: Vec3 = Vec3.atCenterOf(stand.pos).let { from ->
            val d = Vec3.atCenterOf(at).subtract(from).normalize()
            from.add(d.x * 0.7, 0.95, d.z * 0.7)
        }
    }

    // ---- S7 The Cheer ----

    /**
     * S7 The Cheer (4.3): [toaster] stands and raises a toast (TOAST, `tale.set.toast`) to a loose ring of [others] (the first
     * four: a stage has five parts at most) who have turned to them; when the line is done they CHEER, CLAP and HURRAH a beat
     * apart, petals fall over them, and the town's own villager cheer sounds twice, pitched apart. If a [bell] is near one of the
     * others goes to it and rings it (RING_BELL). About 20 seconds. Seeds: the note, the lute (the concert's end), the moot, the fence.
     */
    fun cheer(
        toaster: Townsperson, others: List<Townsperson>, bell: BlockPos? = null, hours: ClosedFloatingPointRange<Double>? = 9.0..21.0,
    ): Stage {
        val level = toaster.level() as ServerLevel
        val crowd = others.take(TaleLimits.STAGE_PARTS - 1)
        val centre = if (crowd.isEmpty()) toaster.position() else Vec3(crowd.sumOf { it.x } / crowd.size, toaster.y, crowd.sumOf { it.z } / crowd.size)
        val toward = Math.atan2(centre.z - toaster.z, centre.x - toaster.x)
        val above = { Vec3(toaster.x, toaster.y + 1.6, toaster.z) }
        val ringer = if (bell != null) crowd.lastOrNull() else null
        val parts = ArrayList<Stage.Part>()
        val t = ArrayList<Move>()
        t += Turn({ centre })
        t += Barrier("ring", most = 400)
        t += Beat(Activity.TOAST, at = { centre }) { p, tick -> if (tick == 4) Words.sayWith(p, "tale.set.toast", why = "a toast") }
        t += Wait({ p, _ -> p.speech.isEmpty() }, most = 120, at = { centre })
        t += Barrier("toasted", most = 300)
        t += Beat(Activity.CLAP) { _, tick ->
            if (tick == 0) {
                Fx.play(level, Fx.Kind.PETALS, above())
                Fx.sound(level, SoundEvents.VILLAGER_CELEBRATE, above(), 1.0f, 1.0f)
            }
        }
        t += Beat(Activity.HURRAH) { _, tick ->
            if (tick == 0) Fx.sound(level, SoundEvents.VILLAGER_CELEBRATE, above(), 1.0f, 1.18f)
            if (tick == 12 || tick == 28) Fx.play(level, Fx.Kind.PETALS, above())
        }
        parts += still("toaster", toaster, t)
        val ring = crowd.filter { it !== ringer }
        for ((i, o) in crowd.withIndex()) {
            val m = ArrayList<Move>()
            if (o === ringer && bell != null) {
                val bellAt = Vec3.atCenterOf(bell)
                val stand = beside(level, bellAt, o.position(), 1.4)
                if (stand != null) m += GoTo({ stand }, Jobs.WALK, near = 0.8, most = 400)
                m += Turn({ bellAt })
                m += Barrier("ring", most = 400, at = { bellAt })
                m += Barrier("toasted", most = 300, pose = Activity.NONE, at = { bellAt })
                m += Beat(Activity.RING_BELL, 60, at = { bellAt }) { _, tick ->
                    if (tick == 6 || tick == 36) Fx.sound(level, SoundEvents.BELL_BLOCK, bellAt, 1.6f, 1.0f)
                }
            } else {
                val n = ring.size
                val slotNo = ring.indexOf(o)
                val angle = toward + (slotNo - (n - 1) / 2.0) * 0.8
                val spot = standable(level, BlockPos.containing(toaster.x + cos(angle) * 3.0, toaster.y, toaster.z + sin(angle) * 3.0))
                if (spot != null && o.distanceToSqr(toaster) > 12.0) m += GoTo({ spot }, Jobs.WALK, near = 0.9, most = 400)
                m += Turn(eye(toaster))
                m += Barrier("ring", most = 400, at = eye(toaster))
                m += Barrier("toasted", most = 300, pose = Activity.NONE, at = eye(toaster))
                m += Wait({ _, tick -> tick >= 3 * slotNo }, most = 20)
                m += Play(Activity.CHEER)
                m += Play(Activity.CLAP)
                m += Play(Activity.HURRAH)
            }
            parts += walker("cheer${i + 1}", o, m, essential = false)
        }
        return Stage("cheer", parts, hours, toaster.position())
    }
}
