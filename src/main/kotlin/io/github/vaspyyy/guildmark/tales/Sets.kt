package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.cooking.vessel.OvenBlock
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlock
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotStyle
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.Seasons
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
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Await
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Barrier
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Beat
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Fx
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Hold
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Rise
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Say
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage.Stand
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.streets.Streets
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.atan2
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
 * it ([Stage.Part.station]); someone passing walks, at the pace of a stroll. The six clips of Part G (GRIEVE, WRING_HANDS,
 * SHAKE_HANDS, FEED_FIRE, HOBBLE, LAY_POSY) are made against measured places (guide/tl_gc.md): where a set plays one of them it
 * puts the body there exactly ([Stand]), and the two drawn standing are never played on a seat ([fit]).
 */
object Sets {
    // ---- Props ----

    /** The vanilla stand-in of each look of the carried props (13.2.3), held where [TaleArt] has not drawn the look. */
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
     * What a body holds for the look [look] of the carried props (13.2.3): the drawn `tale_prop` of that look, which is Part E's
     * ([TaleArt.prop]), or [standIn], the vanilla item that does for it, for a look that is not drawn.
     */
    fun prop(look: String, standIn: Item): ItemStack = TaleArt.prop(look, standIn)

    /** The prop of [look] with its own stand-in (a look that is not one of the twelve is a sheet of paper). */
    fun prop(look: String): ItemStack = prop(look, STAND_INS[look] ?: Items.PAPER)

    // ---- Finding people and places ----

    /**
     * The adults near [around] (within [r] blocks) who are free to be a part of a scene ([Aside.free]; with [station], a worker at
     * their post or someone seated is free too), residents first and then the nearest, leaving out [except] and the fighters.
     */
    fun freeAdults(level: ServerLevel, around: Vec3, r: Double, except: Collection<Townsperson> = listOf(), station: Boolean = false): List<Townsperson> =
        level.getEntitiesOfClass(Townsperson::class.java, AABB.ofSize(around, 2 * r, 16.0, 2 * r)) {
            it.role.adult && !it.fights && it.tale < 0 && it !in except && it.distanceToSqr(around) <= r * r && Aside.free(it, seated = station, station = station)
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

    /**
     * Whether [state] is a fire a paper can be burnt in: an oven or a smoker, a furnace, or a campfire that is lit. Also (Tales T1a, after
     * Hearthcraft I1, whose plan version 3 puts them where the tavern's hearth campfire and the bakery's furnace were) a Bake oven and a Stewpot
     * in the hearth style, the house pot on its crane in the tavern's hearth: like the furnace and the hearth campfire they replace they take a
     * paper lit or not (the pot is lit only from 10:00 to 24:00, the old campfire burned all day).
     */
    fun fire(state: BlockState): Boolean =
        Jobs.oven(state) || state.`is`(Blocks.BLAST_FURNACE) || (state.`is`(Blocks.CAMPFIRE) || state.`is`(Blocks.SOUL_CAMPFIRE)) && state.getValue(CampfireBlock.LIT) ||
            state.block is OvenBlock || state.block is StewpotBlock && state.getValue(StewpotBlock.STYLE) == StewpotStyle.HEARTH

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

    /** The flat unit step from [from] towards [to] (east if they are the same place). */
    private fun heading(from: Vec3, to: Vec3): Vec3 {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val len = sqrt(dx * dx + dz * dz)
        return if (len < 0.1) Vec3(1.0, 0.0, 0.0) else Vec3(dx / len, 0.0, dz / len)
    }

    /** A block to stand on [along] blocks from [base] in the direction [dir] and [across] blocks to the left of it; null if there is none. */
    private fun spotAt(level: ServerLevel, base: Vec3, dir: Vec3, along: Double, across: Double): BlockPos? =
        standable(level, BlockPos.containing(base.x + dir.x * along - dir.z * across, base.y, base.z + dir.z * along + dir.x * across))

    /** The place [gap] blocks from [of] on the line towards [from] (on the line [came] gives, if they stand on one spot): exactly, not by the block. */
    private fun apart(of: Townsperson, from: Townsperson, gap: Double, came: Vec3? = null): Vec3 {
        val dx = from.x - of.x
        val dz = from.z - of.z
        val d = if (came != null && dx * dx + dz * dz < 0.04) came else heading(of.position(), from.position())
        return Vec3(of.x + d.x * gap, from.y, of.z + d.z * gap)
    }

    /** The clips drawn standing (GRIEVE, WRING_HANDS): there is no seated version of either. */
    private val STANDING: Set<Activity> = setOf(Activity.GRIEVE, Activity.WRING_HANDS)

    /** [clip] as [p] can play it: one drawn standing is SIT for someone on a seat (the Count's rule, 13.3), never played with the body on a seat. */
    fun fit(p: Townsperson, clip: Activity): Activity = if (p.seat != null && clip in STANDING) Activity.SIT else clip

    /**
     * A line a template gives a set to have someone say: the tale family it names (`tale.<template>.<beat>`, 4.5), the [detail] in grey
     * under it (what changes: a name, a place) and the [args] that follow the wording. Said through [Words]: with the street talk off,
     * for a family not recorded yet or with no player in hearing it is nothing, and the clips play all the same.
     */
    class Line(val family: String, val detail: String? = null, val args: List<String> = listOf())

    /** [p] says [line] now, if there is one. */
    private fun speak(p: Townsperson, line: Line?, why: String) {
        if (line != null) Words.sayWith(p, line.family, line.args, line.detail, why)
    }

    /** Runs a template's own callback from inside a body's tick: one that fails is logged and the scene goes on (a tick that throws takes the server down). */
    private fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("A tale's scene ({}) failed", what, e)
        }
    }

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
     * About 15 seconds. With [burnAt] (an oven, a smoker, a furnace, a Bake oven, a hearth pot or a lit campfire: see [fire]) it is the Burning, the note's
     * good ending: the taker (who leaves a post or a seat for it: [Rise]) carries the paper to the fire and stands in the cell before
     * it, in the middle of the mouth and half a block from the front face, which is where FEED_FIRE is made to be played; the paper is in
     * the hand before the clip, takes at tick 14 with flame, a ribbon of smoke and ash at the paper, and is gone from the hand by 24; the
     * giver and the [onlookers] (the first two) cheer and clap. About 25 seconds. Null if [burnAt] is not a fire a body can stand in
     * front of. With [shake] it is the Mending's and the Handshake's ending instead: after the handing (none if [item] is empty: the
     * giver just comes up) the two shake hands (SHAKE_HANDS), standing exactly a block apart, middle to middle, facing, and playing the
     * clip on the same tick; a taker who is seated gets up first (STAND_UP: [Rise]), and the day takes them from there when it is over.
     * Otherwise the taker may be at a post or in a seat, and stays. The giver and the onlookers walk.
     * Seeds: the note, the wagon (the ransom), the locket (returned), the lute (redeemed), the fence (the Mending), the informer (the Handshake).
     */
    fun handing(
        giver: Townsperson, taker: Townsperson, item: ItemStack, burnAt: BlockPos? = null, onlookers: List<Townsperson> = listOf(),
        hours: ClosedFloatingPointRange<Double>? = 8.0..21.0, shake: Boolean = false,
    ): Stage? {
        val level = giver.level() as ServerLevel
        val meet = beside(level, taker.position(), giver.position(), 1.0) ?: return null
        val burn = burnAt?.let { fire -> Places.beside(level, fire, taker.blockPosition())?.let { Burn(fire, it) } ?: return null }
        val shaking = shake && burn == null
        val handed = !item.isEmpty
        // At a post or in a seat the taker leaves it to walk to the fire, and gets up to shake hands (the day takes them back)
        val rising = burn != null && (taker.seat != null || taker.routine.anchored) || shaking && taker.seat != null
        // The line the giver comes by, for the handshake's place if the taker (getting up in front of the seat) ends on the giver
        val came = heading(taker.position(), giver.position())
        val g = ArrayList<Move>()
        if (handed) g += Hold(item)
        g += GoTo({ meet }, Jobs.WALK, near = 0.8, overlay = if (handed) Activity.CARRY else Activity.NONE, most = 400)
        if (shaking) {
            // Exactly a block from the taker, on the line they came by, once the taker is on their feet
            if (rising) g += Barrier("up", most = 400)
            g += Stand({ apart(taker, giver, 1.0, came) }, eye(taker))
        } else {
            g += Turn(eye(taker))
        }
        g += Barrier("hand", most = 300, at = eye(taker))
        if (handed) {
            g += Beat(Activity.HAND_OVER, at = eye(taker)) { p, tick -> if (tick == 16) Stage.give(p, null) }
            g += Barrier("handed", most = 120, at = eye(taker))
        }
        val k = ArrayList<Move>()
        if (rising) {
            k += Rise()
            if (shaking) k += Barrier("up", most = 400)
        }
        k += Turn(eye(giver))
        k += Barrier("hand", most = 300, at = eye(giver))
        if (handed) {
            k += Beat(Activity.RECEIVE, at = eye(giver)) { p, tick -> if (tick == 12) Stage.give(p, item) }
            k += Barrier("handed", most = 120, at = eye(giver))
        }
        val parts = ArrayList<Stage.Part>()
        if (shaking) {
            // The handshake: both play the one clip on the same tick, a block apart, their right hands meeting in the middle
            if (handed) k += Hold(ItemStack.EMPTY)
            g += Barrier("clasp", most = 200, at = eye(taker))
            g += Play(Activity.SHAKE_HANDS, at = eye(taker))
            g += Play(Activity.NOD, at = eye(taker))
            k += Barrier("clasp", most = 200, at = eye(giver))
            k += Play(Activity.SHAKE_HANDS, at = eye(giver))
            k += Play(Activity.NOD, at = eye(giver))
        } else {
            k += Play(Activity.COUNT, 60)
            k += Play(Activity.NOD, at = eye(giver))
        }
        if (burn == null) {
            if (!shaking) {
                g += Play(Activity.NOD, at = eye(taker))
                k += Hold(ItemStack.EMPTY)
            }
        } else {
            // The Burning: the paper to the fire, and the giver and the onlookers glad of it
            k += GoTo({ burn.stand.pos }, Jobs.WALK, near = 0.6, overlay = Activity.CARRY, most = 400)
            k += Stand({ burn.spot }, { burn.fire }, overlay = Activity.CARRY)
            k += Beat(Activity.FEED_FIRE, at = { burn.fire }) { p, tick ->
                // The paper takes at tick 14 (flame, smoke and ash at the paper, then every few ticks while it burns) and is gone from the hand by 24
                if (tick >= Burn.TAKES && tick <= Burn.LAST && (tick - Burn.TAKES) % Fx.STEP == 0) {
                    Fx.play(p.level() as ServerLevel, Fx.Kind.BURN, burn.flame(p), tick - Burn.TAKES)
                }
                if (tick == Burn.GONE) Stage.give(p, null)
            }
            k += Barrier("burnt", most = 400, at = { burn.fire })
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
        return Stage(if (burn != null) "burning" else if (shaking) "shaking" else "handing", parts, hours, taker.position())
    }

    /**
     * The fire a paper is burnt in: the block ([at], whose middle is [fire]), the cell to stand in before it ([stand], with the way to face)
     * and [spot], the exact place FEED_FIRE is made for: the middle of that cell, half a block from the fire's front face and in line with
     * the middle of its mouth. The paper meets the flame 7 px ahead of her middle and 9.8 px above the floor ([flame]).
     */
    private class Burn(val at: BlockPos, val stand: Spot) {
        val spot: Vec3 = Vec3(stand.pos.x + 0.5, stand.pos.y.toDouble(), stand.pos.z + 0.5)
        val fire: Vec3 = Vec3.atCenterOf(at)

        /** Where the paper takes, for [p] standing on the spot facing the fire. */
        fun flame(p: Townsperson): Vec3 = Vec3(p.x + stand.facing.stepX * AHEAD, p.y + UP, p.z + stand.facing.stepZ * AHEAD)

        companion object {
            /** Blocks ahead of her middle and above the floor that the paper meets the flame (7 px and 9.8 px). */
            const val AHEAD = 7.0 / 16.0
            const val UP = 9.8 / 16.0

            /** The ticks of the clip: the paper takes at 14, is gone from the hand at 24, and the flame is seen to 34. */
            const val TAKES = 14
            const val GONE = 24
            const val LAST = 34
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

    // ---- S2 The Calling ----

    /**
     * S2 The Calling (4.3): [caller] goes to [at] (a gate, a bridge, a door) and calls [name] along the road they look down ([toward];
     * else on past the place): LOOK_ABOUT, CALL_OUT (the name in the grey detail of `tale.set.call`), a wait with an eye on the road,
     * SEARCH a few steps along, CALL_OUT again. Then the [helpers] (the first two) hurry up, the first asks which way
     * (`tale.set.helper`), the caller POINTs, and the helpers SEARCH off in different directions while the caller wrings their hands and
     * the third call is a shout; the helpers are back by the end, shaking their heads. About 45 seconds. Null if there is nowhere to
     * stand at [at]. Seeds: the child, the brother.
     */
    fun calling(
        caller: Townsperson, name: String, at: BlockPos, toward: Vec3? = null, helpers: List<Townsperson> = listOf(),
        hours: ClosedFloatingPointRange<Double>? = 7.0..21.0,
    ): Stage? {
        val level = caller.level() as ServerLevel
        val spot = standable(level, at) ?: return null
        val centre = Vec3.atCenterOf(spot)
        val dir = if (toward != null) heading(centre, toward) else heading(caller.position(), centre)
        val look = toward ?: Vec3(centre.x + dir.x * 8.0, centre.y + 1.0, centre.z + dir.z * 8.0)
        val one = spotAt(level, centre, dir, 0.0, 3.0) ?: spot
        val two = spotAt(level, centre, dir, 1.0, -3.0) ?: spot
        val call = Line("tale.set.call", detail = name)
        val aids = helpers.filter { it !== caller }.take(2)
        val c = ArrayList<Move>()
        c += GoTo({ spot }, Jobs.BRISK, near = 1.0, most = 800)
        c += Turn({ look })
        c += Play(Activity.LOOK_ABOUT)
        c += Beat(Activity.CALL_OUT, at = { look }) { p, tick -> if (tick == 4) speak(p, call, "calling out") }
        c += Wait({ _, _ -> false }, most = 100, pose = Activity.LOOKOUT, at = { look })
        c += Play(Activity.SEARCH, 40)
        c += GoTo({ one }, Jobs.STROLL, near = 0.8, most = 300)
        c += Beat(Activity.CALL_OUT, at = { look }) { p, tick -> if (tick == 4) speak(p, call, "calling out") }
        if (aids.isNotEmpty()) {
            // The helpers have waited for the second call; now they come, and the caller wrings their hands until the first has asked
            c += Barrier("second", most = 100)
            c += Barrier("asked", most = 700, pose = Activity.WRING_HANDS, at = eye(aids[0]))
            c += Turn(eye(aids[0]))
            c += Play(Activity.POINT, at = { look })
            c += Barrier("pointed", most = 200, at = { look })
        }
        c += Play(Activity.WRING_HANDS, 60)
        c += GoTo({ two }, Jobs.STROLL, near = 0.8, most = 300)
        c += Beat(Activity.SHOUT, at = { look }) { p, tick -> if (tick == 4) speak(p, call, "calling out") }
        if (aids.isNotEmpty()) {
            c += Barrier("back", most = 900, pose = Activity.WRING_HANDS, at = eye(aids[0]))
            c += Turn(eye(aids[0]))
        }
        c += Play(Activity.SIGH)
        val parts = ArrayList<Stage.Part>()
        parts += walker("caller", caller, c)
        for ((k, h) in aids.withIndex()) {
            val sign = if (k == 0) 1.0 else -1.0
            val away = spotAt(level, centre, dir, 4.0, 9.0 * sign) ?: spotAt(level, centre, dir, 8.0, 0.0) ?: spot
            val m = ArrayList<Move>()
            m += Barrier("second", most = 3000)
            m += GoTo({ beside(level, caller.position(), h.position(), 3.0) }, Jobs.BRISK, near = 1.5, most = 600)
            m += Turn(eye(caller))
            if (k == 0) m += Beat(Activity.TALK, 40, at = eye(caller)) { p, tick -> if (tick == 4) speak(p, Line("tale.set.helper"), "asking the way") }
            m += Barrier("asked", most = 300, at = eye(caller))
            m += Turn({ look })
            m += Barrier("pointed", most = 200, at = { look })
            m += GoTo({ away }, Jobs.WALK, near = 1.0, most = 500)
            m += Play(Activity.SEARCH, 60)
            m += GoTo({ beside(level, caller.position(), h.position(), 3.0) }, Jobs.WALK, near = 1.5, most = 500)
            m += Turn(eye(caller))
            m += Barrier("back", most = 900, at = eye(caller))
            m += Play(Activity.SHRUG)
            m += Play(Activity.SIGH)
            parts += walker("helper${k + 1}", h, m, essential = false)
        }
        return Stage("calling", parts, hours, centre)
    }

    // ---- S3 The Quarrel ----

    /**
     * S3 The Quarrel (4.3): [a] and [b] go to marks three blocks apart, facing. A turns and SHOUTs ([shout]), B answers with folded
     * arms and a wave-off; A POINTs ([point]: at what it is about, [toward], with the thing itself in the grey detail); B SHOUTs
     * ([retort]); A recoils; both grumble. A [third] comes up and SHUSHes, and nods; then A and B turn their backs and each go to
     * their own door. [watchers] look on from where they stand (gaze, then folded arms, one may grumble); with [crowd] (the moot) up
     * to five stand in a half ring and call out, clapping and grumbling. The watchers are bystanders, not parts: they are not counted
     * against the five and nothing waits for them. The third's SHUSH carries the words of [hush] if there are any (the fence). About 30 seconds. Null if there is nowhere for the marks. Seeds: the fence, the moot, the locket.
     */
    fun quarrel(
        a: Townsperson, b: Townsperson, third: Townsperson? = null, watchers: List<Townsperson> = listOf(), shout: Line? = null,
        point: Line? = null, retort: Line? = null, toward: Vec3? = null, crowd: Boolean = false,
        hours: ClosedFloatingPointRange<Double>? = 8.0..20.0, hush: Line? = null,
    ): Stage? {
        val level = a.level() as ServerLevel
        val mid = Vec3((a.x + b.x) / 2.0, (a.y + b.y) / 2.0, (a.z + b.z) / 2.0)
        val axis = heading(a.position(), b.position())
        val markA = standable(level, BlockPos.containing(mid.x - axis.x * 1.5, mid.y, mid.z - axis.z * 1.5)) ?: return null
        val markB = standable(level, BlockPos.containing(mid.x + axis.x * 1.5, mid.y, mid.z + axis.z * 1.5)) ?: return null
        val centre = Vec3(mid.x, mid.y + 1.0, mid.z)
        val thing = { toward ?: Vec3(mid.x - axis.z * 6.0, mid.y + 1.0, mid.z + axis.x * 6.0) }
        val last = if (third != null) "hushed" else "retorted"
        val doorA = { a.home?.let { standable(level, it) } }
        val doorB = { b.home?.let { standable(level, it) } }
        val backA = { Vec3(2 * a.x - b.x, a.y + 1.0, 2 * a.z - b.z) }
        val backB = { Vec3(2 * b.x - a.x, b.y + 1.0, 2 * b.z - a.z) }
        val am = ArrayList<Move>()
        am += GoTo({ markA }, Jobs.STROLL, near = 0.7, most = 500)
        am += Turn(eye(b))
        am += Barrier("face", most = 600, at = eye(b))
        am += Beat(Activity.SHOUT, at = eye(b)) { p, tick -> if (tick == 4) speak(p, shout, "a quarrel") }
        am += Barrier("shouted", most = 200, at = eye(b))
        am += Barrier("waved", most = 400, at = eye(b))
        am += Beat(Activity.POINT, at = thing) { p, tick -> if (tick == 6) speak(p, point, "a quarrel") }
        am += Barrier("pointed", most = 200, at = eye(b))
        am += Barrier("retorted", most = 400, at = eye(b))
        am += Play(Activity.RECOIL, at = eye(b))
        am += Play(Activity.GRUMBLE)
        if (third != null) am += Barrier("hushed", most = 900, pose = Activity.ARMS_FOLDED, at = eye(third))
        am += Turn(backA)
        am += GoTo(doorA, Jobs.WALK, near = 2.0, most = 600)
        val bm = ArrayList<Move>()
        bm += GoTo({ markB }, Jobs.STROLL, near = 0.7, most = 500)
        bm += Turn(eye(a))
        bm += Barrier("face", most = 600, at = eye(a))
        bm += Barrier("shouted", most = 200, at = eye(a))
        bm += Play(Activity.ARMS_FOLDED, 80, at = eye(a))
        bm += Play(Activity.WAVE_OFF, at = eye(a))
        bm += Barrier("waved", most = 400, at = eye(a))
        bm += Barrier("pointed", most = 400, pose = Activity.ARMS_FOLDED, at = eye(a))
        bm += Beat(Activity.SHOUT, at = eye(a)) { p, tick -> if (tick == 4) speak(p, retort, "a quarrel") }
        bm += Barrier("retorted", most = 200, at = eye(a))
        bm += Play(Activity.GRUMBLE)
        if (third != null) bm += Barrier("hushed", most = 900, pose = Activity.ARMS_FOLDED, at = eye(third))
        bm += Turn(backB)
        bm += GoTo(doorB, Jobs.WALK, near = 2.0, most = 600)
        val parts = ArrayList<Stage.Part>()
        parts += walker("a", a, am)
        parts += walker("b", b, bm)
        if (third != null) {
            val tm = ArrayList<Move>()
            tm += Barrier("retorted", most = 2400)
            tm += GoTo({ beside(level, mid, third.position(), 2.6) }, Jobs.BRISK, near = 1.0, most = 600)
            tm += Turn({ centre })
            // Tales T1a: the third's words with the SHUSH (the fence's `tale.fence.third`), when a template gives them
            tm += if (hush == null) Play(Activity.SHUSH, at = { centre }) else Beat(Activity.SHUSH, at = { centre }) { p, tick -> if (tick == 4) speak(p, hush, "a quarrel") }
            tm += Barrier("hushed", most = 900, at = { centre })
            tm += Play(Activity.NOD, at = { centre })
            parts += walker("third", third, tm, essential = false)
        }
        // The ones who watch: from where they stand, or in a half ring on one side of the two
        val folk = watchers.filter { it !== a && it !== b && it !== third }.take(if (crowd) Stage.EXTRAS else 3)
        val extras = ArrayList<Stage.Part>()
        for ((i, w) in folk.withIndex()) {
            val m = ArrayList<Move>()
            if (crowd) {
                val spread = if (folk.size <= 1) 0.0 else (i - (folk.size - 1) / 2.0) * (PI * 0.8 / (folk.size - 1))
                val dx = -axis.z * cos(spread) - axis.x * sin(spread)
                val dz = axis.x * cos(spread) - axis.z * sin(spread)
                val ring = standable(level, BlockPos.containing(mid.x + dx * 5.0, mid.y, mid.z + dz * 5.0))
                if (ring != null) m += GoTo({ ring }, Jobs.STROLL, near = 1.0, most = 500)
            }
            m += Turn({ centre })
            m += Await("shouted", 1500, Activity.GAZE, { centre })
            m += Await("waved", 600, Activity.ARMS_FOLDED, { centre })
            m += Await("pointed", 600, Activity.ARMS_FOLDED, { centre })
            if (crowd) {
                m += Wait({ _, t -> t >= 6 * i }, most = 40)
                m += Play(if (i % 2 == 0) Activity.GRUMBLE else Activity.CLAP, at = { centre })
            } else if (i == 0) {
                m += Await("retorted", 600, Activity.ARMS_FOLDED, { centre })
                m += Play(Activity.GRUMBLE, at = { centre })
            }
            m += Await(last, 1500, Activity.ARMS_FOLDED, { centre })
            extras += walker("watcher${i + 1}", w, m, essential = false)
        }
        return Stage("quarrel", parts, hours, centre, extras = extras)
    }

    // ---- S5 The Laying ----

    /** What the Laying lays the posy on: the memorial's block, the nearest bench or step (the memorial found no room), or nothing set. */
    private enum class Laid { BLOCK, STEPS, NONE }

    /**
     * How far from the stone block's middle the mourner kneels: LAY_POSY is made for 24.5 px from the plinth's front face, which is two
     * blocks; for a cairn 23 px from its step's front face, which is 2.75 (guide/tl_gc.md).
     */
    private const val STONE_REACH = 2.0
    private const val CAIRN_REACH = 2.75

    /** The streets' band for a tale's blocks (Part F's `Streets.TALES`, beside `FLAPS`), with the memorial's id as the run. */
    private const val TALES_BAND = -2044

    /**
     * The ticks of LAY_POSY the set acts on: the posy is down at 14 (the set calls Part F's [Memorials.posy] at 15), it leaves her
     * hands at 18, and she is back on her mark at 38.5 (the lantern is set at 39).
     */
    private const val POSY_DOWN = 15
    private const val POSY_GONE = 18
    private const val LAMP = 39

    /**
     * S5 The Laying (4.3): [mourner] with a posy in hand and up to four [others] (the first of whom is [priest], if the town has one)
     * go to the memorial at [stone] (the place of a stone or a cairn: where it stands, or where it would). The mourner kneels on the
     * side the block faces ([front], else the block's own `facing`, else the side they came from), exactly two blocks from the
     * stone's middle and in line with it (2.75 for a cairn): the place LAY_POSY is made for; the others stand on an arc before it. The
     * priest PRAYs ([prayer], a take) while the mourner KNEELs and the others bow their heads; the mourner LAYs the posy (it lands at tick
     * 14 of the clip and leaves her hands at 18). At tick 15 the set calls [Memorials.posy] for the [memorial] (Part F puts the posy on
     * the stone), and [onLaid] hears, with whether it is dusk or later and whether the posy went on the steps; at dusk, as she stands
     * and steps back, a vanilla lantern is set in the cell in front of the stone (the second one for a cairn) through [Streets.place]. If
     * the memorial found no room ([TaleData.Memorial.pos] null) the posy goes instead in a flower pot of the season on the nearest bench
     * or step, and [onLaid] says so for the template to tell it ("There was no room, so they laid it on the steps", 6.4). With no
     * [memorial] (a stand-in), or with `townTaleMemorials` off, no block is set at all. Then the others SIGH and the first of them
     * embraces the mourner. About 50 seconds. Null if there is nowhere to stand by the stone. Seeds: the chair, the wagon (a cairn),
     * the brother (a cairn).
     */
    fun laying(
        mourner: Townsperson, stone: BlockPos, others: List<Townsperson> = listOf(), priest: Townsperson? = null, prayer: Line? = null,
        memorial: TaleData.Memorial? = null, front: Direction? = null, onLaid: ((dusk: Boolean, onSteps: Boolean) -> Unit)? = null,
        hours: ClosedFloatingPointRange<Double>? = 8.0..21.0,
    ): Stage? {
        val level = mourner.level() as ServerLevel
        val cairn = memorial?.kind == "cairn"
        val rec = memorial?.takeIf { memorialsOn() }
        val steps = if (rec != null && !rec.placed && rec.pos == null) stepsNear(level, stone) else null
        val laid = when {
            rec == null -> Laid.NONE
            rec.placed -> Laid.BLOCK
            steps != null -> Laid.STEPS
            else -> Laid.NONE
        }
        val target = steps ?: stone
        val centre = Vec3.atCenterOf(target)
        // The side she kneels on: the way the block faces, else the way a bench is sat, else the side she came from
        val facing = front ?: when {
            laid == Laid.BLOCK -> facingOf(level, target)
            steps != null -> level.getBlockState(steps).takeIf { Places.isSeat(level, it, steps) }?.let { Places.seatFacing(it) }
            else -> null
        }
        val came = Direction.getApproximateNearest(mourner.x - centre.x, 0.0, mourner.z - centre.z)
        val sides = if (facing != null) listOf(facing) else listOf(came, came.clockWise, came.counterClockWise, came.opposite)
        val reach = if (cairn) CAIRN_REACH else STONE_REACH
        val (side, markCell) = sides.firstNotNullOfOrNull { d ->
            exactly(level, BlockPos.containing(centre.x + d.stepX * reach, centre.y, centre.z + d.stepZ * reach))?.let { d to it }
        } ?: return null
        val markAt = Vec3(centre.x + side.stepX * reach, markCell.y.toDouble(), centre.z + side.stepZ * reach)
        val folk = ArrayList<Townsperson>()
        if (priest != null && priest !== mourner) folk += priest
        for (o in others) if (o !== mourner && o !in folk && folk.size < TaleLimits.STAGE_PARTS - 1) folk += o
        // The others stand on an arc before the stone, to either side of the mourner, nearer the stone than the cairn's wide foot allows
        val arc = if (cairn) 3.4 else 2.6
        val faceAngle = atan2(side.stepZ.toDouble(), side.stepX.toDouble())
        val marks = folk.indices.map { i ->
            val angle = faceAngle + (if (i % 2 == 0) 1 else -1) * (i / 2 + 1) * 0.7
            standable(level, BlockPos.containing(centre.x + cos(angle) * arc, centre.y, centre.z + sin(angle) * arc))
        }
        val posy = prop("posy")
        val embracer = folk.firstOrNull { it !== priest }
        val at = { centre }
        val column = rec?.let { Streets.column(it.capital) } ?: 0L
        val run = rec?.id?.toLong() ?: 0L
        var dusk = false
        val m = ArrayList<Move>()
        m += Hold(posy)
        m += GoTo({ markCell }, Jobs.WALK, near = 0.6, overlay = Activity.CARRY, most = 700)
        m += Stand({ markAt }, at, overlay = Activity.CARRY)
        m += Barrier("ring", most = 800, at = at)
        // KNEEL and then LAY_POSY with nothing between: the clip starts on the kneel's own pose, and ends standing, on idle
        m += Play(Activity.KNEEL, 120, at = at)
        m += Beat(Activity.LAY_POSY, at = at) { p, tick ->
            if (tick == POSY_DOWN) {
                dusk = Stage.within(level, 17.0..22.0)
                attempt("laid") {
                    when (laid) {
                        Laid.BLOCK -> rec?.let { Memorials.posy(level, it.id) }
                        Laid.STEPS -> steps?.let { Streets.place(level, it.above(), potFor(level), column, TALES_BAND, run, solid = false) }
                        Laid.NONE -> Unit
                    }
                    onLaid?.invoke(dusk, laid == Laid.STEPS)
                }
            }
            if (tick == POSY_GONE) Stage.give(p, null)
            if (tick == LAMP && laid == Laid.BLOCK && dusk) attempt("lantern") { lamp(level, target.relative(side, if (cairn) 2 else 1), column, run) }
        }
        m += Barrier("laid", most = 200, at = at)
        if (embracer != null) {
            m += Barrier("embrace", most = 500, at = eye(embracer))
            m += Play(Activity.EMBRACE, at = eye(embracer))
        }
        m += Play(Activity.SIGH)
        val parts = ArrayList<Stage.Part>()
        parts += walker("mourner", mourner, m)
        for ((i, o) in folk.withIndex()) {
            val mark = marks[i]
            val moves = ArrayList<Move>()
            if (mark != null) moves += GoTo({ mark }, Jobs.WALK, near = 0.8, most = 700)
            moves += Turn(at)
            moves += Barrier("ring", most = 800, at = at)
            if (o === priest) moves += Beat(Activity.PRAY, at = at) { p, tick -> if (tick == 6) speak(p, prayer, "a laying") }
            else moves += Play(Activity.PRAY, 120, at = at)
            moves += Barrier("laid", most = 200, at = at)
            if (o === embracer) {
                moves += GoTo({ beside(level, mourner.position(), o.position(), 1.2) }, Jobs.WALK, near = 0.9, most = 300)
                moves += Turn(eye(mourner))
                moves += Barrier("embrace", most = 500, at = eye(mourner))
                moves += Play(Activity.EMBRACE, at = eye(mourner))
            }
            moves += Play(Activity.SIGH)
            parts += walker(if (o === priest) "priest" else "other${i + 1}", o, moves, essential = false)
        }
        return Stage("laying", parts, hours, centre)
    }

    /** Whether the tales may set blocks for their memorials (townTaleMemorials); true before the world's config is read, as every switch answers. */
    private fun memorialsOn(): Boolean = try {
        GuildmarkConfig.TOWN_TALE_MEMORIALS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** The way the block at [pos] faces (its carved face looks that way), if it is a block with a facing: Part F's memorial is. */
    private fun facingOf(level: ServerLevel, pos: BlockPos): Direction? {
        val state = level.getBlockState(pos)
        return if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) state.getValue(BlockStateProperties.HORIZONTAL_FACING) else null
    }

    /** [base], or the block a step above or below it, if a body can stand there: the exact column, never a neighbour's (a clip made against a measured distance needs it). */
    private fun exactly(level: ServerLevel, base: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, 1, -1)) {
            val pos = base.above(dy)
            if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
        }
        return null
    }

    /** The nearest bench or step to [around] that a pot can stand on (a seat, else a stair or a slab whose top is a full block up, with air above it) within 16 blocks; null if there is none. */
    private fun stepsNear(level: ServerLevel, around: BlockPos): BlockPos? {
        val open = { pos: BlockPos -> level.getBlockState(pos.above()).isAir }
        return Places.nearest(level, around, 16, 3, null) { state, pos -> Places.isSeat(level, state, pos) && open(pos) }
            ?: Places.nearest(level, around, 16, 3, null) { state, pos ->
                (state.block is StairBlock || state.block is SlabBlock && state.getValue(SlabBlock.TYPE) != net.minecraft.world.level.block.state.properties.SlabType.BOTTOM) && open(pos)
            }
    }

    /** The season's flower in a pot: the posy laid where there is no stone (Part F's own posy, set at the memorial, is its to choose). */
    private fun potFor(level: ServerLevel): BlockState {
        val pot = when (Seasons.today(level).season) {
            Season.SPRING -> Blocks.POTTED_PINK_TULIP
            Season.SUMMER -> Blocks.POTTED_DANDELION
            Season.AUTUMN -> Blocks.POTTED_ORANGE_TULIP
            Season.WINTER -> Blocks.POTTED_LILY_OF_THE_VALLEY
        }
        return pot.defaultBlockState()
    }

    /** A vanilla lantern in [cell], set through the streets' record as the memorial's own (so it is kept as the memorial is), with the small flame of its lighting. */
    private fun lamp(level: ServerLevel, cell: BlockPos, column: Long, run: Long) {
        val state = Blocks.LANTERN.defaultBlockState()
        if (!state.canSurvive(level, cell)) return
        if (Streets.place(level, cell, state, column, TALES_BAND, run, solid = true)) Fx.play(level, Fx.Kind.CANDLE, Vec3.atCenterOf(cell))
    }

    // ---- S6 The Reunion ----

    /**
     * S6 The Reunion (4.3): [waiting] stands looking out for [returned] (LOOKOUT); when they are near the waiting JUMPs, runs to them and
     * both EMBRACE, each saying a take of `tale.set.home` ("You're home. You're home!"), the villagers' green about them; the [watchers]
     * (bystanders, up to three) CLAP and CHEER; then the waiting turns to [player], if there is one, THANKs and BOWs. [returned] walks up
     * to the waiting if they are far off (a made figure set down at the gate, a resident the player has brought: see [TaleEscort]).
     * About 20 seconds. Null if there is nowhere to stand. Seeds: the wagon, the child, the brother, the locket.
     */
    fun reunion(
        waiting: Townsperson, returned: Townsperson, player: ServerPlayer? = null, watchers: List<Townsperson> = listOf(),
        hours: ClosedFloatingPointRange<Double>? = 7.0..21.0,
    ): Stage? {
        val level = waiting.level() as ServerLevel
        val home = Line("tale.set.home")
        val you = { player?.takeIf { it.isAlive }?.eyePosition }
        val joy = { p: Townsperson -> Fx.play(level, Fx.Kind.HAPPY, p.position().add(0.0, 1.5, 0.0)) }
        val w = ArrayList<Move>()
        w += Turn(eye(returned))
        w += Wait({ p, _ -> returned.isAlive && returned.distanceTo(p) < 14.0 }, most = 1500, pose = Activity.LOOKOUT, at = eye(returned))
        w += Play(Activity.JUMP, at = eye(returned))
        w += GoTo({ beside(level, returned.position(), waiting.position(), 1.4) }, Jobs.BRISK, near = 1.1, most = 400)
        w += Turn(eye(returned))
        w += Barrier("hug", most = 300, at = eye(returned))
        var wSaid = false
        w += Beat(Activity.EMBRACE, at = eye(returned)) { p, tick ->
            if (!wSaid && tick >= 4) wSaid = Words.sayWith(p, home.family, why = "a reunion") != null
            if (tick % 12 == 0) joy(p)
        }
        w += Barrier("hugged", most = 120, at = eye(returned))
        if (player != null) {
            w += Turn(you)
            w += Play(Activity.THANK, at = you)
            w += Play(Activity.BOW, at = you)
        }
        val r = ArrayList<Move>()
        r += GoTo({ if (returned.distanceTo(waiting) > 10.0) beside(level, waiting.position(), returned.position(), 8.0) else null }, Jobs.WALK, near = 1.0, most = 900)
        r += Turn(eye(waiting))
        r += Barrier("hug", most = 1800, at = eye(waiting))
        var rSaid = false
        r += Beat(Activity.EMBRACE, at = eye(waiting)) { p, tick ->
            // Their word comes after the other's is done
            if (!rSaid && tick >= 24) rSaid = Words.sayWith(p, home.family, why = "a reunion") != null
            if (tick % 12 == 6) joy(p)
        }
        r += Barrier("hugged", most = 120, at = eye(waiting))
        r += Play(Activity.NOD, at = eye(waiting))
        val parts = ArrayList<Stage.Part>()
        parts += walker("waiting", waiting, w)
        parts += walker("returned", returned, r)
        val extras = ArrayList<Stage.Part>()
        for ((i, o) in watchers.filter { it !== waiting && it !== returned }.take(3).withIndex()) {
            val m = ArrayList<Move>()
            m += Turn(eye(returned))
            m += Await("hug", 2400, Activity.GAZE, eye(returned))
            m += Wait({ _, t -> t >= 5 * i }, most = 20)
            m += Play(Activity.CLAP, at = eye(returned))
            m += Play(Activity.CHEER, at = eye(returned))
            extras += walker("watcher${i + 1}", o, m, essential = false)
        }
        return Stage("reunion", parts, hours, waiting.position(), extras = extras)
    }
}
