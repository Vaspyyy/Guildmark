package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.Worksite
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * One of a works' crew, a labourer lent by the town or a stranger making up the number: a homestead's builder
 * (BuilderCrew.Builder), and a mason and a carpenter besides (living-world/design-influence.md section 3, "The crew").
 *
 * At the work: now and then a stone is dressed with the chisel before it goes in, and a course is pointed with the
 * trowel after, a trowel's worth out of the mortar tub each time (with the tub empty there's no pointing until it's
 * mixed again). Each stone set beds down with its own sound, a beam now and then with a thud.
 *
 * Between steps, one job of the yard's at a time, each one of the crew's at once and each only now and then: the mortar
 * mixed when the tub runs low, stone dressed at its heap, a beam carried two to it from the timber stack to where
 * the frame is going up (the one who leads with it fetches the other, who keeps his end two blocks behind), and,
 * while the granary's doors and bins go in, its hoist hauled hand over hand with what goes up next on the rope. Their
 * strokes' sounds are the clips' own, on their moments (client/works/WorksMoments); what changes in the world (the
 * tub filled, the load lifted, the beam set down) changes on the server.
 */
class WorksBuilder(p: Townsperson, id: Int, road: BlockPos, lent: Boolean) : BuilderCrew.Builder(p, id, road, lent) {
    private enum class Job { MIX, CHISEL, BEAM, HOIST }

    /** The job of the yard's in hand, and how far into it ([phase], from the chore's tick [mark]). */
    private var job: Job? = null
    private var phase = 0
    private var mark = 0

    /** Where they stand for it, and what it's at (the tub, the heap, the hoist). */
    private var spot: Vec3 = Vec3.ZERO
    private var at: BlockPos = BlockPos.ZERO

    /** A beam's other carrier, and whether this one leads with it; [asked] to take the other end, by [mate]. */
    private var mate: WorksBuilder? = null
    private var leads = false
    private var asked = false

    /** Where the beam is going down. */
    private var drop: Vec3 = Vec3.ZERO

    /** The last game time they were idle looking for work (a beam's leader takes only someone free just now). */
    private var idleAt = Long.MIN_VALUE / 2

    /** Whether they're up on the scaffold or a roof (for the other carrier). */
    private val high: Boolean get() = aloft

    // ---- At the work ----

    /** A works' stakes are knocked in to its own sound. */
    override fun ownSound(site: Worksite, i: Int): Boolean {
        val kind = site.step(i).kind
        if (kind != StepKind.STAKE && kind != StepKind.PULL_STAKE) return false
        val level = site.level as? ServerLevel ?: return false
        WorksBlocks.play(level, site.at(i), WorksBlocks.STAKE)
        return true
    }

    /** A stone, now and then, dressed with the chisel before it goes in (on the ground: not up on the scaffold). */
    override fun dress(site: Worksite, i: Int): Activity? {
        val step = site.step(i)
        if (step.kind != StepKind.BLOCK || step.stuff != Stuff.STONE || aloft) return null
        return if (p.random.nextInt(4) == 0) Activity.CHISEL else null
    }

    /** A stone's course pointed with the trowel once it's in, half the time: a trowel's worth out of the tub, if it has any. */
    override fun finish(site: Worksite, i: Int): Activity? {
        val s = site as? TownWorksSite ?: return null
        val step = s.step(i)
        if (step.kind != StepKind.BLOCK || step.stuff != Stuff.STONE || p.random.nextInt(2) != 0) return null
        val tub = WorksYard.tub(s.level, s.rec)?.let { s.level.getBlockEntity(it) as? MortarTubBlockEntity } ?: return null
        return if (tub.scoop()) Activity.TROWEL else null
    }

    override fun extraTicks(clip: Activity): Int = when (clip) {
        Activity.CHISEL -> Activity.CHISEL.ticks * 2
        else -> clip.ticks
    }

    /** The chisel and mallet, the trowel and its hawk, are the clips' own: nothing else in hand. */
    override fun tool(clip: Activity?): ItemStack = ItemStack.EMPTY

    override fun stroke(level: ServerLevel, site: Worksite, i: Int, clip: Activity, beat: Int) {
        hold(ItemStack.EMPTY)
    }

    /** A stone set beds down on its mortar; a beam, now and then, goes in with a thud. */
    override fun laid(level: ServerLevel, site: Worksite, i: Int) {
        val step = site.step(i)
        if (step.kind != StepKind.BLOCK) return
        when (step.stuff) {
            Stuff.STONE -> {
                WorksBlocks.play(level, site.at(i), WorksBlocks.STONE_SET)
                val yard = yards.getOrPut(id) { Yard() }
                yard.newest = site.at(i)
                yard.newestAt = level.gameTime
            }
            Stuff.TIMBER -> if (step.b.m == M.TIMBER && p.random.nextInt(3) == 0) WorksBlocks.play(level, site.at(i), WorksBlocks.BEAM_DOWN, 0.6f)
            else -> {}
        }
    }

    // ---- The yard's jobs ----

    override fun chores(level: ServerLevel, site: Worksite): Boolean {
        val now = level.gameTime
        idleAt = now
        val s = site as? TownWorksSite ?: return false
        // Asked to take the other end of a beam: at it if the one who asked still has it in hand
        if (asked) {
            asked = false
            val lead = mate
            if (lead != null && lead.job == Job.BEAM && lead.leads && lead.mate === this && !lead.leaving && !aloft) {
                start(Job.BEAM, lead.at)
                leads = false
                spot = lead.besideHeap(level)
                return true
            }
            mate = null
        }
        if (aloft || mate != null) return false
        val yard = yards.getOrPut(id) { Yard() }
        yard.look(level, s)
        // The tub low and stone still to lay: the mortar mixed (whoever's free first)
        val tub = WorksYard.tub(level, s.rec)
        if (tub != null && yard.stone && !holds(yard.mixer, Job.MIX)) {
            val be = level.getBlockEntity(tub) as? MortarTubBlockEntity
            val stand = behind(level, tub)
            if (be != null && be.mortar < MortarTubBlockEntity.FULL / 2 && stand != null) {
                yard.mixer = p.id
                start(Job.MIX, tub)
                spot = Vec3.atBottomCenterOf(stand)
                return true
            }
        }
        // A beam carried over to where the frame goes up, by two of them
        if (yard.timber && now - yard.carried > BEAM_EVERY && p.random.nextInt(2) == 0) {
            val heap = heap(level, s, YardStuff.TIMBER)
            val other = BuilderCrew.builders(id).firstOrNull { it !== this && it is WorksBuilder && it.free(now) } as? WorksBuilder
            val stand = heap?.let { Places.beside(level, it, p.blockPosition()) }
            if (heap != null && other != null && stand != null) {
                yard.carried = now
                start(Job.BEAM, heap)
                leads = true
                mate = other
                other.mate = this
                other.asked = true
                spot = Vec3.atBottomCenterOf(stand.pos)
                drop = dropFor(level, s, yard)
                return true
            }
        }
        // Stone dressed at its heap
        if (yard.stone && !holds(yard.chiseller, Job.CHISEL) && now - yard.chiselled > CHISEL_EVERY && p.random.nextInt(3) == 0) {
            val heap = heap(level, s, YardStuff.STONE)
            val stand = heap?.let { Places.beside(level, it, p.blockPosition()) }
            if (heap != null && stand != null) {
                yard.chiseller = p.id
                yard.chiselled = now
                start(Job.CHISEL, heap)
                spot = Vec3.atBottomCenterOf(stand.pos)
                return true
            }
        }
        // The hoist hauled while what goes up high goes in (the granary's doors and its bins)
        val hoist = yard.hoist
        if (hoist != null && (s.stage == Stage.OPENINGS || s.stage == Stage.FITTING) && !holds(yard.hoister, Job.HOIST) && now - yard.hoisted > HOIST_EVERY) {
            val be = level.getBlockEntity(hoist) as? WorksHoistBlockEntity
            val stand = be?.let { footing(level, hoist, it.drop) }
            if (be != null && stand != null) {
                yard.hoister = p.id
                yard.hoisted = now
                start(Job.HOIST, hoist)
                spot = Vec3.atBottomCenterOf(stand)
                return true
            }
        }
        return false
    }

    override fun chore(level: ServerLevel, site: Worksite, t: Int): Boolean {
        val s = site as? TownWorksSite ?: return end()
        if (t > TIMEOUT) return end()
        return when (job) {
            Job.MIX -> mix(level, t)
            Job.CHISEL -> chisel(level, t)
            Job.BEAM -> if (leads) lead(level, t) else follow(level, t)
            Job.HOIST -> hoist(level, s, t)
            null -> false
        }
    }

    /** Their job cut short (a cheer, sent home, handed back): its claim given up, the other carrier told. */
    override fun released() {
        val yard = yards[id]
        when (job) {
            Job.MIX -> if (yard?.mixer == p.id) yard.mixer = -1
            Job.CHISEL -> if (yard?.chiseller == p.id) yard.chiseller = -1
            Job.HOIST -> {
                if (yard?.hoister == p.id) yard.hoister = -1
                (p.level().getBlockEntity(at) as? WorksHoistBlockEntity)?.let { it.hang(it.drop) }
            }
            else -> {}
        }
        val other = mate
        mate = null
        if (other != null && other.mate === this) other.mate = null
        job = null
        phase = 0
        asked = false
        leads = false
        val clip = p.activity
        if (clip == Activity.MIX_MORTAR || clip == Activity.CHISEL || clip == Activity.HOIST || clip == Activity.CARRY_BEAM) p.act(Activity.NONE)
    }

    private fun start(what: Job, where: BlockPos) {
        job = what
        at = where
        phase = 0
        mark = 0
        hold(ItemStack.EMPTY)
        if (!p.activity.overlay) p.act(Activity.NONE)
    }

    private fun end(): Boolean {
        released()
        return false
    }

    /** Free to take the other end of a beam: idle just now, on the ground, and nothing else asked of them. */
    private fun free(now: Long): Boolean = job == null && !asked && mate == null && now - idleAt <= 12 && !high && !leaving

    /** Walks to [spot]; once there, set right on it. */
    private fun there(level: ServerLevel, t: Int, near: Double = 0.9): Boolean {
        if (!walkTo(level, spot, near) && t < 300) return false
        p.navigation.stop()
        if (p.position().distanceToSqr(spot.x, p.y, spot.z) > 0.0025) p.setPos(spot.x, p.y, spot.z)
        return true
    }

    private fun faceAt(target: Vec3) {
        p.face(p.yawTo(target))
        p.lookAt(target)
    }

    // The mortar: stirred through, round and round, behind the tub; full at the end
    private fun mix(level: ServerLevel, t: Int): Boolean {
        val be = level.getBlockEntity(at) as? MortarTubBlockEntity ?: return end()
        val tub = Vec3.atCenterOf(at)
        when (phase) {
            0 -> if (there(level, t)) {
                phase = 1
                mark = t
                p.act(Activity.MIX_MORTAR)
            }
            else -> {
                faceAt(tub.add(0.0, -0.4, 0.0))
                if (t - mark >= Activity.MIX_MORTAR.ticks * MIX_ROUNDS) {
                    be.mix()
                    return end()
                }
            }
        }
        return true
    }

    // A stone dressed at its heap: a few rounds of the chisel
    private fun chisel(level: ServerLevel, t: Int): Boolean {
        if (!level.getBlockState(at).`is`(WorksBlocks.YARD_STONE.get())) return end()
        when (phase) {
            0 -> if (there(level, t)) {
                phase = 1
                mark = t
                p.act(Activity.CHISEL)
            }
            else -> {
                faceAt(Vec3.atCenterOf(at).add(0.0, -0.3, 0.0))
                if (t - mark >= Activity.CHISEL.ticks * CHISEL_ROUNDS) return end()
            }
        }
        return true
    }

    // The beam's leader: to the timber stack, the other end taken up with him, over to the frame and down
    private fun lead(level: ServerLevel, t: Int): Boolean {
        val other = mate
        when (phase) {
            0 -> if (there(level, t)) {
                phase = 1
                mark = t
            }
            1 -> {
                faceAt(Vec3.atCenterOf(at))
                if (other == null || other.job != Job.BEAM) {
                    // Nobody came for the other end (they'd taken work of their own): it waits for another time
                    if (t - mark > 60) return end()
                } else if (other.phase >= 1) {
                    phase = 2
                    mark = t
                    p.act(Activity.PICK_UP)
                } else if (t - mark > 200) return end()
            }
            2 -> if (t - mark >= Activity.PICK_UP.ticks) {
                if (other == null) return end()
                phase = 3
                mark = t
                p.act(Activity.CARRY_BEAM)
            }
            3 -> {
                if (p.activity != Activity.CARRY_BEAM) p.act(Activity.CARRY_BEAM)
                // The other end dropped (he was called away): down it goes here
                if (other == null || other.job != Job.BEAM || walkTo(level, drop, 1.2) || t - mark > 400) {
                    p.navigation.stop()
                    phase = 4
                    mark = t
                    p.act(Activity.PUT_DOWN)
                }
            }
            else -> {
                if (t - mark == PUT_DOWN_AT) {
                    // One thud from the beam's middle, both ends in it
                    val middle = if (other != null && other.job == Job.BEAM) p.position().add(other.p.position()).scale(0.5) else p.position()
                    WorksBlocks.play(level, BlockPos.containing(middle), WorksBlocks.BEAM_DOWN)
                }
                if (t - mark >= Activity.PUT_DOWN.ticks) return end()
            }
        }
        return true
    }

    // The beam's other end: beside him at the stack, taken up when he takes his, and kept two blocks behind him
    private fun follow(level: ServerLevel, t: Int): Boolean {
        val lead = mate?.takeIf { it.job == Job.BEAM && it.mate === this }
        if (lead == null) {
            // He's gone: what's on the shoulder is put down where they stand
            if (phase == 3) {
                phase = 4
                mark = t
                p.navigation.stop()
                p.act(Activity.PUT_DOWN)
            } else if (phase != 4) return end()
        }
        when (phase) {
            0 -> if (there(level, t)) phase = 1
            1 -> {
                faceAt(Vec3.atCenterOf(at))
                if (lead != null && lead.phase >= 2) {
                    phase = 2
                    mark = t
                    p.act(Activity.PICK_UP)
                }
            }
            2 -> if (t - mark >= Activity.PICK_UP.ticks) {
                phase = 3
                mark = t
                p.act(Activity.CARRY_BEAM)
            }
            3 -> {
                val him = lead ?: return true
                if (p.activity != Activity.CARRY_BEAM) p.act(Activity.CARRY_BEAM)
                if (him.phase >= 4) {
                    p.navigation.stop()
                    phase = 4
                    mark = t
                    p.act(Activity.PUT_DOWN)
                    return true
                }
                val ahead = him.p.position()
                val here = p.position()
                val away = here.subtract(ahead)
                val flat = Vec3(away.x, 0.0, away.z)
                val goal = if (flat.lengthSqr() < 1e-4) ahead else ahead.add(flat.normalize().scale(BEHIND))
                val gap = Math.sqrt(flat.lengthSqr())
                when {
                    gap > 9.0 -> {
                        // Fallen far behind (a corner, a fence): caught up, out of sight of nobody but in a puff
                        puff(level)
                        p.teleportTo(goal.x, ahead.y, goal.z)
                    }
                    gap > BEHIND + 0.4 && t % 5 == 0 -> p.navigation.moveTo(goal.x, ahead.y, goal.z, if (gap > 4.0) 0.95 else 0.75)
                    gap <= BEHIND -> p.navigation.stop()
                }
            }
            else -> if (t - mark >= Activity.PUT_DOWN.ticks) return end()
        }
        return true
    }

    // The hoist: hauled hand over hand from beside the rope's foot, with the next thing that goes up high on it
    private fun hoist(level: ServerLevel, s: TownWorksSite, t: Int): Boolean {
        val be = level.getBlockEntity(at) as? WorksHoistBlockEntity ?: return end()
        val foot = Vec3.atBottomCenterOf(at.below(be.drop))
        when (phase) {
            0 -> if (there(level, t)) {
                phase = 1
                mark = t
                be.lift(load(s), be.drop, Activity.HOIST.ticks * HOIST_ROUNDS)
                p.act(Activity.HOIST)
            }
            else -> {
                p.face(p.yawTo(foot))
                p.lookAt(Vec3.atCenterOf(at))
                if (t - mark >= Activity.HOIST.ticks * HOIST_ROUNDS) {
                    be.hang(be.drop)
                    return end()
                }
            }
        }
        return true
    }

    // ---- Where ----

    /** Behind the tub (the side it doesn't face), where MIX_MORTAR is played; or any side free. */
    private fun behind(level: ServerLevel, tub: BlockPos): BlockPos? {
        val state = level.getBlockState(tub)
        val facing = if (state.hasProperty(WorksFacingBlock.FACING)) state.getValue(WorksFacingBlock.FACING) else Direction.NORTH
        val back = tub.relative(facing.opposite)
        if (Places.standable(level, back)) return back
        return Places.beside(level, tub, p.blockPosition())?.pos
    }

    /** For the one following: two blocks along from the leader's place by the stack. */
    private fun besideHeap(level: ServerLevel): Vec3 {
        val mine = BlockPos.containing(spot)
        val out = Direction.getApproximateNearest(spot.x - (at.x + 0.5), 0.0, spot.z - (at.z + 0.5))
        val side = if (out.axis.isHorizontal) out.clockWise else Direction.EAST
        for (d in listOf(side, side.opposite)) {
            val two = mine.relative(d, 2)
            if (Places.standable(level, two)) return Vec3.atBottomCenterOf(two)
            val one = mine.relative(d)
            if (Places.standable(level, one)) return Vec3.atBottomCenterOf(one)
        }
        return spot
    }

    /** Where the beam goes down: on the ground before the next timber to go in, or before the works' door. */
    private fun dropFor(level: ServerLevel, s: TownWorksSite, yard: Yard): Vec3 {
        val next = yard.timberAt
        if (next != null) {
            val ground = BlockPos(next.x, groundUnder(level, next), next.z)
            for (pos in listOf(ground.relative(s.out), ground.relative(s.out, 2), ground.relative(s.out.clockWise), ground.relative(s.out.counterClockWise), ground)) {
                if (Places.standable(level, pos)) return Vec3.atBottomCenterOf(pos)
            }
        }
        return s.gate()
    }

    /** Somewhere to stand beside the hoist's rope where it reaches the ground, nearest the yard. */
    private fun footing(level: ServerLevel, hoist: BlockPos, drop: Int): BlockPos? {
        val foot = hoist.below(drop)
        val ground = BlockPos(foot.x, groundUnder(level, foot), foot.z)
        return Direction.Plane.HORIZONTAL.stream().toList()
            .map { ground.relative(it) }
            .filter { Places.standable(level, it) }
            .minByOrNull { it.distSqr(p.blockPosition()) }
    }

    /** What goes up on the rope: the next thing of the stage to go in high up, as it will. */
    private fun load(s: TownWorksSite): ItemStack {
        val end = s.plan.stageStarts[s.stage.ordinal + 1]
        var any: ItemStack = ItemStack.EMPTY
        for (i in s.build.step until end) {
            if (s.isDone(i) || s.step(i).kind != StepKind.BLOCK) continue
            val item = s.stateFor(i).block.asItem()
            if (item == Items.AIR) continue
            if (s.at(i).y - s.drawing.y0 >= 3) return ItemStack(item)
            if (any.isEmpty) any = ItemStack(item)
        }
        return if (any.isEmpty) ItemStack(Items.BARREL) else any
    }

    /** The yard's heap of [stuff] at [s], if it stands. */
    private fun heap(level: ServerLevel, s: TownWorksSite, stuff: YardStuff): BlockPos? {
        val block = WorksBlocks.yard(stuff).get()
        return s.rec.yard.firstOrNull { level.isLoaded(it) && level.getBlockState(it).`is`(block) && (level.getBlockEntity(it) as? WorksYardBlockEntity)?.here?.let { n -> n > 0 } == true }
    }

    /** Whether the one holding a job of the yard's ([who], an entity id) is still at it. */
    private fun holds(who: Int, what: Job): Boolean =
        who >= 0 && BuilderCrew.builders(id).any { it is WorksBuilder && it.p.id == who && it.job == what && !it.leaving && it.p.isAlive }

    /**
     * A works' yard as its crew shares it: who has each job, when each was last done, and what's still to build, looked
     * over every few seconds (stone in this stage or the next, timber in this one and where it goes, the hoist).
     */
    private class Yard {
        var mixer = -1
        var chiseller = -1
        var hoister = -1
        var carried = Long.MIN_VALUE / 2
        var chiselled = Long.MIN_VALUE / 2
        var hoisted = Long.MIN_VALUE / 2

        var looked = Long.MIN_VALUE / 2
        var stone = false
        var timber = false
        var timberAt: BlockPos? = null
        var hoist: BlockPos? = null

        /** The last stone set, and when (the master's plumb line goes to it). */
        var newest: BlockPos? = null
        var newestAt = Long.MIN_VALUE / 2

        fun look(level: ServerLevel, s: TownWorksSite) {
            val now = level.gameTime
            if (now - looked in 0 until 100L) return
            looked = now
            stone = false
            timber = false
            timberAt = null
            val plan = s.plan
            val end = plan.stageStarts[minOf(s.stage.ordinal + 2, Stage.entries.size)]
            for (i in s.build.step until end) {
                if (s.isDone(i)) continue
                val step = plan.steps[i]
                if (step.kind != StepKind.BLOCK) continue
                if (step.stuff == Stuff.STONE) stone = true
                if (step.stuff == Stuff.TIMBER && step.stage == s.stage && timberAt == null) {
                    timber = true
                    timberAt = s.at(i)
                }
                if (stone && timberAt != null) break
            }
            // The hoist, once its step is in
            if (hoist == null) {
                val i = plan.steps.indexOfFirst { it.kind == StepKind.BLOCK && it.b.m == M.HOOK && it.b.v == WorksDrawing.MARK }
                if (i >= 0 && s.isDone(i) && level.getBlockState(s.at(i)).`is`(WorksBlocks.HOIST.get())) hoist = s.at(i)
            } else if (!level.getBlockState(hoist!!).`is`(WorksBlocks.HOIST.get())) hoist = null
        }
    }

    companion object {
        /** Ticks a job of the yard's may take at most, walking there included. */
        private const val TIMEOUT = 600

        /** Rounds of each clip a job takes. */
        private const val MIX_ROUNDS = 3
        private const val CHISEL_ROUNDS = 3
        private const val HOIST_ROUNDS = 4

        /** The fewest ticks between one beam carried and the next, stone dressed at the heap, the hoist hauled. */
        private const val BEAM_EVERY = 1800L
        private const val CHISEL_EVERY = 1200L
        private const val HOIST_EVERY = 1200L

        /** How far behind the leader the other carrier keeps (the beam's prop is three blocks long). */
        private const val BEHIND = 2.0

        /** The tick of PUT_DOWN the load touches the ground. */
        private const val PUT_DOWN_AT = 11

        private val yards = HashMap<Int, Yard>()

        /** The server has stopped. */
        fun reset() {
            yards.clear()
        }

        /** The last stone the crew of [key] set, within the last minute (for the master's plumb line). */
        internal fun newest(key: Int, now: Long): BlockPos? = yards[key]?.takeIf { now - it.newestAt in 0 until 1200L }?.newest

        /** Whoever of [key]'s crew leads a beam across just now, if one is being carried. */
        internal fun beam(key: Int): Townsperson? =
            BuilderCrew.builders(key).firstOrNull { it is WorksBuilder && it.job == Job.BEAM && it.leads && it.phase == 3 }?.p

        /** Whether any of [key]'s crew is mixing mortar now. */
        internal fun mixing(key: Int): Boolean = BuilderCrew.builders(key).any { it is WorksBuilder && it.job == Job.MIX }
    }
}
