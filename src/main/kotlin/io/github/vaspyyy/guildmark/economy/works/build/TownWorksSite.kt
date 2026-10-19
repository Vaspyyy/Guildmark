package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.WorldCanvas
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Wares
import io.github.vaspyyy.guildmark.economy.WaresBlock
import io.github.vaspyyy.guildmark.economy.WaresBlockEntity
import io.github.vaspyyy.guildmark.economy.WaresRegistry
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksKinds
import io.github.vaspyyy.guildmark.homestead.Build
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.HomesteadSite
import io.github.vaspyyy.guildmark.homestead.HomesteadTiers
import io.github.vaspyyy.guildmark.homestead.Laid
import io.github.vaspyyy.guildmark.homestead.Worksite
import io.github.vaspyyy.guildmark.homestead.plan.Packed
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Step
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.homestead.plan.TierPlan
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.ToppingOut
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.Container
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ComposterBlock
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A town works as it goes up in the world (living-world/design-influence.md sections 2, 3 and 9): its drawing's
 * build ([plan], see WorksPlans) worked through step by step by its crew while someone is near to see it, and by the
 * clock while nobody is. Built as a homestead's tier is ([HomesteadSite]: the daylight work clock at
 * townWorksBuildSpeed, a stage only begun once the one before is done, its bill spread over its steps), with a town's
 * care for what's already there:
 *
 *  - Its materials come out of its yard's record (Works.take: what the wagons and players brought), a whole unit at
 *    a time as the last runs out.
 *  - It only builds into air, over what grew wild (on the land outside the walls; inside them only plants and snow),
 *    over its own stakes, scaffolding and earlier steps, and over the town's own blocks its drawing names
 *    ([WorksDrawing.replaces]: the bakery's floor its flue goes through), which it keeps ([BuildRec.found]) to put
 *    back if it's ever taken down. What it carries out of its way (the bakery's barrels and chest) goes with whatever
 *    is in it. Anything else in the way of a step holds that step up ([Laid.BLOCKED]), for a day of work at most:
 *    after that the step is left out and the works goes on (no works waits for ever).
 *  - Whoever stands where a block goes is waited for, and after ten seconds lifted onto it.
 */
class TownWorksSite(override val level: ServerLevel, val rec: BuildRec, val planned: WorksPlans.Planned) : Worksite {
    val drawing: WorksDrawing get() = planned.drawing
    override val plan: TierPlan get() = planned.plan
    val frame: WorksFrame get() = planned.frame
    val build: Build get() = rec.build
    val id: Int get() = rec.id
    val kind: WorksKind get() = rec.kind
    private val style: CityStyle = CityStyle.entries[rec.style.coerceIn(0, CityStyle.entries.size - 1)]

    /** Out in the country (the tower by its road): the land's own growth is cleared as a homestead's is. Inside the town, only plants and snow. */
    private val countryside: Boolean = kind == WorksKind.TOWER

    // ---- As its crew sees it (Worksite) ----

    override val key: Int get() = -1 - rec.id

    /** Its labourers (the master builder is no builder of the crew's: he's [caller]). */
    override val crew: Int get() = WorksKinds.crew(kind)

    /** The residents named to labour here today (WorksBuild counts them each second): strangers make up the rest. */
    var named: Int = 0

    override val strangers: Int get() = (crew - named).coerceAtLeast(0)
    override val work: Double get() = build.work
    override val reach: Int get() = build.reach

    /** The yard's crate (the post's place while there's no crate). */
    override val crate: BlockPos get() = rec.yard.firstOrNull() ?: rec.post ?: frame.world(drawing.door.x, drawing.y0, drawing.door.z)

    override val out: Direction get() = frame.front

    /** Standing before the crate, on the side it faces. */
    override fun crateFront(): Vec3 {
        val c = crate
        val state = level.getBlockState(c)
        val facing = if (state.hasProperty(WorksFacingBlock.FACING)) state.getValue(WorksFacingBlock.FACING) else frame.front
        val stand = c.relative(facing)
        return Vec3(stand.x + 0.5, stand.y.toDouble(), stand.z + 0.5)
    }

    /** Before its front door. */
    override fun gate(): Vec3 {
        val d = drawing.door
        return frame.centre(d.x.toDouble(), (drawing.y0 + d.y).toDouble(), d.z.toDouble())
    }

    /** Where its strangers come from and go back to: down the street out from its front, out of the way. */
    override fun road(): BlockPos {
        val d = drawing.door
        for (out in intArrayOf(18, 12, 6)) {
            val x = frame.worldX(d.x, d.z - out)
            val z = frame.worldZ(d.x, d.z - out)
            if (!level.isLoaded(BlockPos(x, drawing.y0, z))) continue
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            val pos = BlockPos(x, y, z)
            if (abs(y - drawing.y0) <= 6 && level.getBlockState(pos.below()).fluidState.isEmpty && level.getBlockState(pos).isAir && level.getBlockState(pos.above()).isAir) return pos
        }
        return frame.world(d.x, drawing.y0 + d.y, d.z)
    }

    override fun progress(): Component {
        val percent = if (total == 0) 100 else laid * 100 / total
        return Component.translatable(
            "message.guildmark.works.builder_progress", Component.translatable(kind.key),
            Component.translatable("gui.guildmark.works.stage.${stage.key}"), percent,
        )
    }

    /** The master builder, while he's about: he calls the stages and the crew cheers them. */
    override fun caller(): Townsperson? = MasterBuilder.body(level, id)

    /** A works' crew comes in without a handbell (the master rings his at the opening): the first only says they're here. */
    override fun arrive(p: Townsperson, road: BlockPos) {
        p.say("homestead_arrive_${1 + level.random.nextInt(3)}", force = true)
    }

    /**
     * A labourer or a stranger making up the crew: a works' own builder, with the mason's and the carpenter's jobs
     * too. A stranger is given a name in the capital's own style (a labourer has their own).
     */
    override fun builder(p: Townsperson, road: BlockPos, lent: Boolean): BuilderCrew.Builder {
        if (!lent && p.customName == null) {
            val seed = id * 7919L + p.id * 31L + level.gameTime / 24000L
            val woman = Names.woman(p.role, p.look, seed)
            p.customName = Component.literal(Names.given(style, woman, seed) + " " + Names.family(style, seed / 3))
            p.isCustomNameVisible = false
        }
        return WorksBuilder(p, key, road, lent)
    }

    /**
     * The crew's cheer at a stage's end: a town's crowd, not a homestead's handful. At the topping out with the town
     * gathered round, the town's cheer is the one heard (ToppingOut's), not this as well.
     */
    override fun cheer(stage: Stage): SoundEvent? = if (stage == Stage.TOPPING && ToppingOut.gathered(level, id)) null else WorksBlocks.CHEER.get()

    // ---- Materials ----

    /** Units of each paid material one step takes: the bill spread over its steps of that material. */
    private val bill = WorksKinds.bill(kind)
    private val per = DoubleArray(Stuff.PAID.size) { s -> if (plan.needs[s] == 0) 0.0 else bill[s].toDouble() / plan.needs[s] }

    /** Who has each step in hand (a builder's entity id). */
    private val claims = HashMap<Int, Int>()

    /** The other half of a door: both go in together. */
    private val partner = IntArray(plan.steps.size) { -1 }

    /** The lowest pole of each column of scaffolding. */
    private val poleBase = java.util.BitSet()

    /** Steps someone was standing in the way of, and since when. */
    private val waiting = HashMap<Int, Long>()

    /** The material the yard ran out of, if it has. */
    override var short: Stuff? = null
        private set

    /** Where something of the town's holds a step up, and which step; for the screen and the master's words. */
    var blocked: BlockPos? = null
        private set
    private var blockedStep = -1

    /** The stage the build is in, as last seen (to tell when one is finished). */
    private var lastStage: Stage = plan.stageOf(build.step)

    /** Set when a stage has just been finished, for the crew to celebrate. */
    var finishedStage: Stage? = null

    /** The steps by their places in the world, packed: a move's other end, a step's own place. */
    private val byPlace = HashMap<Long, Int>()

    init {
        for ((i, s) in plan.steps.withIndex()) if (s.kind == StepKind.BLOCK) byPlace[Packed.of(s.x, s.y, s.z)] = i
        for ((i, s) in plan.steps.withIndex()) {
            if (s.kind != StepKind.BLOCK || s.b.m != M.DOOR) continue
            val other = Packed.of(s.x, if (s.b.part == 1) s.y - 1 else s.y + 1, s.z)
            byPlace[other]?.let { j -> if (plan.steps[j].b.m == M.DOOR) partner[i] = j }
        }
        for (kind in listOf(StepKind.SCAFFOLD, StepKind.UNSCAFFOLD)) {
            val lowest = HashMap<Long, Int>()
            for ((i, s) in plan.steps.withIndex()) if (s.kind == kind) {
                val col = BlockPos.asLong(s.x, 0, s.z)
                val j = lowest[col]
                if (j == null || plan.steps[j].y > s.y) lowest[col] = i
            }
            for (i in lowest.values) poleBase.set(i)
        }
    }

    // ---- Where the build stands ----

    override val total: Int get() = plan.steps.size
    override val finished: Boolean get() = build.step >= plan.steps.size
    override val laid: Int get() = build.step + build.done.cardinality()
    override fun isDone(i: Int): Boolean = i < build.step || build.done.get(i)
    override val stage: Stage get() = plan.stageOf(build.step)
    private val stageEnd: Int get() = plan.stageStarts[stage.ordinal + 1]
    override fun step(i: Int): Step = plan.steps[i]
    override fun at(i: Int): BlockPos {
        val s = plan.steps[i]
        return frame.world(s.x, s.y, s.z)
    }

    /** The world box its lot covers. */
    val box get() = frame.box(drawing.lot, drawing.y0 + drawing.low, drawing.y0 + drawing.high)

    /** Whether all of its ground (and its yard round it) is loaded, so it can be built, looked over or taken down. */
    fun loaded(): Boolean {
        val b = box.inflatedBy(Works.YARD, 0, Works.YARD)
        return level.isLoaded(BlockPos(b.minX(), b.minY(), b.minZ())) && level.isLoaded(BlockPos(b.maxX(), b.minY(), b.maxZ())) &&
            level.isLoaded(BlockPos(b.minX(), b.minY(), b.maxZ())) && level.isLoaded(BlockPos(b.maxX(), b.minY(), b.minZ()))
    }

    /** How much of each paid material is still to be used, in units. */
    fun left(): IntArray {
        val counts = IntArray(Stuff.PAID.size)
        for (i in build.step until plan.steps.size) {
            if (isDone(i)) continue
            val s = plan.steps[i].stuff
            if (s != Stuff.FREE) counts[s.ordinal]++
        }
        return IntArray(Stuff.PAID.size) { s -> max(0, ceil(counts[s] * per[s] - build.credit[s] - 1e-6).toInt()) }
    }

    // ---- The work clock ----

    /** Winds the work clock on to now: each tick of daylight since it was last wound lets the crew lay a little more. */
    fun wind() {
        val now = level.gameTime
        val day = level.overworldClockTime
        if (build.clock < 0 || now < build.clock) {
            build.clock = now
            build.dayTime = day
            return
        }
        val worked = if (!level.gameRules.get(GameRules.ADVANCE_TIME)) now - build.clock else HomesteadSite.daylight(build.dayTime, day)
        build.clock = now
        build.dayTime = day
        if (worked <= 0) return
        val left = (total - laid).toDouble()
        build.work = min(build.work + worked * rate(), min(left, build.reach.toDouble()))
    }

    /** Steps a tick of work lays: the whole build in its days of daylight, at townWorksBuildSpeed (ten times with `works rush`). */
    fun rate(): Double = total / (WorksKinds.days(kind) * HomesteadTiers.WORKDAY / Works.pace().coerceIn(0.05, 1000.0))

    override fun workday(): Boolean =
        !level.gameRules.get(GameRules.ADVANCE_TIME) || Math.floorMod(level.overworldClockTime, 24000L) < HomesteadTiers.WORKDAY - 300

    /**
     * Looks at what's in the yard ([here], by Stuff.PAID) and works out how far it goes: how many of the steps still
     * to lay, in order, it pays for before something runs out ([short] says what).
     */
    fun survey(here: IntArray) {
        val have = DoubleArray(Stuff.PAID.size) { here.getOrElse(it) { 0 } + build.credit[it] }
        val used = DoubleArray(Stuff.PAID.size)
        var reach = 0
        short = null
        for (i in build.step until plan.steps.size) {
            if (isDone(i)) continue
            val s = plan.steps[i].stuff
            if (s != Stuff.FREE) {
                used[s.ordinal] += per[s.ordinal]
                if (used[s.ordinal] > have[s.ordinal] + 1e-9) {
                    short = s
                    break
                }
            }
            reach++
        }
        if (reach != build.reach) {
            build.reach = reach
            WorksBuildData.get(level).changed()
        }
        if (build.work > reach) build.work = reach.toDouble()
    }

    /** Takes what a step of [stuff] is made of out of the yard (whole units as the last runs out); false if there's none. */
    private fun charge(stuff: Stuff): Boolean {
        if (stuff == Stuff.FREE) return true
        val s = stuff.ordinal
        val credit = build.credit
        credit[s] -= per[s]
        while (credit[s] < -1e-9) {
            val want = ceil(-credit[s] - 1e-9).toInt().coerceAtLeast(1)
            val got = Works.take(level, id, stuff, want)
            if (got <= 0) {
                credit[s] += per[s]
                short = stuff
                return false
            }
            credit[s] += got.toDouble()
        }
        return true
    }

    private fun refund(stuff: Stuff) {
        if (stuff != Stuff.FREE) build.credit[stuff.ordinal] += per[stuff.ordinal]
    }

    // ---- Handing out steps ----

    override fun open(i: Int): Boolean {
        if (i < build.step || i >= stageEnd || isDone(i) || claims.containsKey(i)) return false
        rec.blocked[i]?.let { if (level.gameTime - it[0] in 0 until RETRY_BLOCKED) return false }
        val since = waiting[i]
        return since == null || level.gameTime - since > 40
    }

    override fun claim(who: Int, others: List<BlockPos>, most: Int): IntArray {
        val end = stageEnd
        var first = -1
        var fallback = -1
        var looked = 0
        var i = build.step
        while (i < end && looked < 96) {
            if (open(i)) {
                looked++
                if (fallback < 0) fallback = i
                val pos = at(i)
                if (others.none { it.distManhattan(pos) < 6 }) {
                    first = i
                    break
                }
            }
            i++
        }
        if (first < 0) first = fallback
        if (first < 0) return IntArray(0)
        val stuff = plan.steps[first].stuff
        if (stuff != Stuff.FREE && short == stuff && build.reach <= 0) return IntArray(0)
        val anchor = at(first)
        val out = ArrayList<Int>()
        out.add(first)
        var j = first + 1
        while (j < min(end, first + 64) && out.size < most) {
            if (open(j)) {
                val pos = at(j)
                if (abs(pos.x - anchor.x) <= 3 && abs(pos.z - anchor.z) <= 3 && abs(pos.y - anchor.y) <= 3) out.add(j)
            }
            j++
        }
        for (k in out) claims[k] = who
        return out.toIntArray()
    }

    override fun release(who: Int) {
        claims.entries.removeIf { it.value == who }
    }

    override fun release(who: Int, i: Int) {
        if (claims[i] == who) claims.remove(i)
    }

    override fun workingAt(except: Int): List<BlockPos> {
        val seen = HashMap<Int, Int>()
        for ((i, who) in claims) if (who != except) seen.merge(who, i, ::minOf)
        return seen.values.map(::at)
    }

    // ---- Laying ----

    override fun lay(i: Int, fx: Boolean, free: Boolean): Laid {
        if (isDone(i)) return Laid.DONE
        if (!free && build.work < 1.0) return Laid.LATER
        val step = plan.steps[i]
        if (!free && !charge(step.stuff)) return Laid.NEED
        val outcome = place(i, step, fx)
        if (outcome != Laid.DONE) {
            if (!free) refund(step.stuff)
            if (outcome == Laid.BLOCKED && heldUp(i)) {
                // Held up a whole day of work: left out, and the works goes on
                done(i, free)
                return Laid.DONE
            }
            return outcome
        }
        done(i, free)
        val other = partner[i]
        if (other >= 0 && !isDone(other)) {
            val o = plan.steps[other]
            if (free || charge(o.stuff)) {
                if (place(other, o, fx) == Laid.DONE) done(other, true) else if (!free) refund(o.stuff)
            }
        }
        return Laid.DONE
    }

    /** Step [i] is held up: since when it first was, and whether that's a day of work ago (time standing still: a day of ticks). */
    private fun heldUp(i: Int): Boolean {
        val now = longArrayOf(level.gameTime, level.overworldClockTime)
        val since = rec.blocked.getOrPut(i) { now.also { WorksBuildData.get(level).changed() } }
        val worked = if (!level.gameRules.get(GameRules.ADVANCE_TIME)) now[0] - since[0] else HomesteadSite.daylight(since[1], now[1])
        return worked >= HomesteadTiers.WORKDAY
    }

    private fun done(i: Int, free: Boolean) {
        claims.remove(i)
        waiting.remove(i)
        if (rec.blocked.remove(i) != null && i == blockedStep) {
            blocked = null
            blockedStep = -1
        }
        if (i == build.step) {
            build.step++
            while (build.step < plan.steps.size && build.done.get(build.step)) {
                build.done.clear(build.step)
                build.step++
            }
        } else {
            build.done.set(i)
        }
        if (!free) {
            build.work = max(0.0, build.work - 1.0)
            build.reach = max(0, build.reach - 1)
        }
        val now = stage
        if (now != lastStage || finished) {
            if (lastStage.ordinal > build.cheered) {
                build.cheered = lastStage.ordinal
                finishedStage = lastStage
            }
            lastStage = now
        }
        WorksBuildData.get(level).changed()
    }

    /** Leaves step [i] out without laying it (`works stage`, and a step held up a day). */
    fun skip(i: Int) {
        if (!isDone(i)) done(i, true)
    }

    private fun place(i: Int, step: Step, fx: Boolean): Laid {
        val pos = frame.world(step.x, step.y, step.z)
        if (level.isOutsideBuildHeight(pos)) return Laid.DONE
        return when (step.kind) {
            StepKind.PULL_STAKE -> {
                val state = level.getBlockState(pos)
                if (state.`is`(BlockTags.FENCES) || state.`is`(BlockTags.BANNERS)) {
                    if (fx) level.levelEvent(2001, pos, Block.getId(state))
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                }
                unstring()
                Laid.DONE
            }
            StepKind.STAKE -> {
                val post = state(step.fill ?: B(M.FENCE), pos)
                val flag = state(step.b, pos.above())
                if (roomy(pos) && roomy(pos.above())) {
                    set(pos, post)
                    set(pos.above(), flag)
                    if (fx) {
                        WorksBlocks.play(level, pos, WorksBlocks.STAKE, 0.9f)
                        dust(pos, post, 12)
                    }
                }
                // The line strung between the stakes once they're all in
                if (plan.steps.withIndex().none { (j, s) -> s.kind == StepKind.STAKE && j != i && !isDone(j) }) string()
                Laid.DONE
            }
            StepKind.GROUND -> ground(i, step, pos, fx)
            StepKind.BANK -> {
                bank(pos, fx)
                Laid.DONE
            }
            StepKind.BLOCK -> block(i, step, pos, fx)
            StepKind.SCAFFOLD -> {
                if (roomy(pos)) {
                    if (poleBase.get(i)) {
                        var below = pos.below()
                        var n = 0
                        while (n++ < 12 && below.y > level.minY && roomy(below) && !level.getBlockState(below).`is`(Blocks.SCAFFOLDING)) {
                            set(below, SCAFFOLD)
                            below = below.below()
                        }
                    }
                    set(pos, SCAFFOLD)
                    if (fx) sound(pos, SoundEvents.SCAFFOLDING_PLACE, 0.8f)
                }
                Laid.DONE
            }
            StepKind.UNSCAFFOLD -> {
                if (level.getBlockState(pos).`is`(Blocks.SCAFFOLDING)) {
                    if (fx) level.levelEvent(2001, pos, Block.getId(SCAFFOLD))
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                    if (poleBase.get(i)) {
                        var below = pos.below()
                        var n = 0
                        while (n++ < 12 && level.getBlockState(below).`is`(Blocks.SCAFFOLDING)) {
                            level.setBlock(below, Blocks.AIR.defaultBlockState(), FLAGS)
                            below = below.below()
                        }
                    }
                }
                Laid.DONE
            }
            StepKind.TOPPING -> {
                if (level.getBlockState(pos).isAir) {
                    val bough = state(step.b, pos)
                    set(pos, bough)
                    if (fx) dust(pos, bough, 16)
                }
                Laid.DONE
            }
            StepKind.UNTOP -> {
                val state = level.getBlockState(pos)
                if (state.`is`(BlockTags.LEAVES)) {
                    if (fx) level.levelEvent(2001, pos, Block.getId(state))
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                }
                Laid.DONE
            }
            // A works has no shipping crates or beasts of its own
            StepKind.SHIPPING, StepKind.SPAWN -> Laid.DONE
        }
    }

    /** One block of the drawing. */
    private fun block(i: Int, step: Step, pos: BlockPos, fx: Boolean): Laid {
        val local = Packed.of(step.x, step.y, step.z)
        val want = state(step.b, pos)
        val here = level.getBlockState(pos)
        if (same(here, want)) return Laid.DONE
        if (step.b.m == M.AIR) {
            if (here.isAir) return Laid.DONE
            // Something the drawing carries elsewhere: set down there first, with whatever is in it
            drawing.moves.entries.firstOrNull { it.value == local }?.let { (to, _) ->
                val j = byPlace[to]
                if (j != null && !isDone(j)) {
                    val o = plan.steps[j]
                    if (block(j, o, frame.world(o.x, o.y, o.z), fx) == Laid.DONE) done(j, true)
                }
            }
            val now = level.getBlockState(pos)
            if (now.isAir) return Laid.DONE
            if (!mayTake(i, pos, now, local)) return Laid.BLOCKED
            if (fx) level.levelEvent(2001, pos, Block.getId(now))
            empty(pos)
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
            return Laid.DONE
        }
        if (!here.isAir && !mayTake(i, pos, here, local)) return Laid.BLOCKED
        val clear = clearFor(i, pos, want)
        if (clear != Laid.DONE) return clear
        val from = drawing.moves[local]?.let { f -> frame.world(Packed.x(f), Packed.y(f), Packed.z(f)) }
        if (!here.isAir) empty(pos)
        set(pos, want)
        if (from != null) carry(from, pos)
        settle(step, pos, want)
        joinUp(pos)
        if (fx) {
            dust(pos, want, 10)
            if (step.stuff == Stuff.STONE && (step.stage == Stage.FOOTINGS || step.stage == Stage.WALLS)) {
                // A dressed stone bedded on its mortar
                WorksBlocks.play(level, pos, WorksBlocks.STONE_SET)
            } else {
                val sound = want.soundType
                level.playSound(null, pos, sound.placeSound, SoundSource.BLOCKS, (sound.volume + 1f) / 2f, sound.pitch * 0.8f)
            }
        }
        return Laid.DONE
    }

    /**
     * Whether the block [here] at [pos] (the drawing's [local] place) may be taken out for step [i] ([takeable]). If
     * not, the step is held up ([blocked]).
     */
    private fun mayTake(i: Int, pos: BlockPos, here: BlockState, local: Long): Boolean {
        val ok = takeable(pos, here, local)
        if (!ok) {
            blocked = pos
            blockedStep = i
        }
        return ok
    }

    /**
     * Whether the block [here] at [pos] (the drawing's [local] place) may be taken out: the wild (out in the
     * country), plants and snow, the works' own scaffolding, stakes and blocks, the town's own blocks the drawing
     * names (unless they hold something that isn't to be carried), and the works' own ground under its lot, dug out
     * or built into (a cellar).
     */
    private fun takeable(pos: BlockPos, here: BlockState, local: Long): Boolean = when {
        here.isAir -> true
        soft(here) -> true
        countryside && HomesteadSite.wild(here) -> true
        here.`is`(Blocks.SCAFFOLDING) || here.`is`(Blocks.TRIPWIRE) -> true
        ours(here) -> true
        local in drawing.replaces || drawing.moves.containsValue(local) -> !holding(pos) || drawing.moves.containsValue(local)
        pos.y < drawing.y0 && !here.hasBlockEntity() && here.fluidState.isEmpty -> true
        else -> false
    }

    /** Plants, snow and the like: what the town's own ground grows and anyone may walk through. */
    private fun soft(state: BlockState): Boolean =
        state.canBeReplaced() && state.fluidState.isEmpty && !state.hasBlockEntity() ||
            state.`is`(BlockTags.FLOWERS) || state.`is`(BlockTags.SAPLINGS) || state.`is`(Blocks.SNOW)

    /** The works' own blocks, wherever they stand. */
    private fun ours(state: BlockState): Boolean = WorksBlocks.ours(state)

    /** Whether something at [pos] holds things (a chest with anything in it, or still to be filled by its loot). */
    private fun holding(pos: BlockPos): Boolean {
        val be = level.getBlockEntity(pos) ?: return false
        if (be is RandomizableContainer && be.lootTable != null) return true
        return be is Container && !be.isEmpty
    }

    /** Takes out what's in a container at [pos] before it goes (it was carried, or held nothing). */
    private fun empty(pos: BlockPos) {
        val be = level.getBlockEntity(pos) ?: return
        if (be is RandomizableContainer) be.setLootTable(null)
        if (be is Container) for (k in 0 until be.containerSize) be.removeItemNoUpdate(k)
    }

    /** What's in the container at [from] carried into the one just set down at [to]: its loot still to come, or its things. */
    private fun carry(from: BlockPos, to: BlockPos) {
        val src = level.getBlockEntity(from) ?: return
        val dst = level.getBlockEntity(to) ?: return
        val table = (src as? RandomizableContainer)?.lootTable
        if (src is RandomizableContainer && table != null && dst is RandomizableContainer) {
            dst.setLootTable(table, src.lootTableSeed)
            src.setLootTable(null)
        } else if (src is Container && dst is Container) {
            for (k in 0 until min(src.containerSize, dst.containerSize)) {
                val stack = src.removeItemNoUpdate(k)
                if (!stack.isEmpty) dst.setItem(k, stack)
            }
        }
        dst.setChanged()
        src.setChanged()
    }

    /** A block just laid made ready: a display set up for the capital's market, the works' own blocks told what they are. */
    private fun settle(step: Step, pos: BlockPos, state: BlockState) {
        val be = level.getBlockEntity(pos)
        when {
            step.b.m == M.WARES && be is WaresBlockEntity -> be.setUp(BlockPos.of(rec.capital), Wares.entries[Math.floorMod(step.b.v, Wares.entries.size)])
            be is WorksHoistBlockEntity -> be.hang(dropBelow(pos))
            be is WorksAwningBlockEntity -> be.color(rec.color)
        }
        if (state.block is CampfireBlock && fxFire) level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.8f, 0.9f)
    }

    /** Whether lighting a fire shows (it's always a step laid by hand or the clock: yes). */
    private val fxFire = true

    /**
     * How far the hoist at [pos] has its rope to the ground, in blocks: its falls hang in front of it (in the block it
     * faces), down to the ground there, 16 px a block under its own floor (as client/works draws it).
     */
    fun dropBelow(pos: BlockPos): Int {
        val state = level.getBlockState(pos)
        val facing = if (state.hasProperty(WorksFacingBlock.FACING)) state.getValue(WorksFacingBlock.FACING) else Direction.NORTH
        val cursor = pos.relative(facing).mutable()
        var n = 0
        while (n < 24 && cursor.y > level.minY) {
            val below = cursor.below()
            val under = level.getBlockState(below)
            if (!under.getCollisionShape(level, below).isEmpty && !under.`is`(Blocks.SCAFFOLDING)) break
            n++
            cursor.move(Direction.DOWN)
        }
        return n.coerceIn(1, 24)
    }

    /**
     * Whether what's [here] is as good as [want]: the same block, its open doors and lit fires, a display's look and a
     * barrel's lid left as they are (the works' life opens and closes them), a stall's display whatever goods it shows.
     */
    private fun same(here: BlockState, want: BlockState): Boolean {
        if (here == want) return true
        val b = here.block
        if (b is WaresBlock && want.block is WaresBlock) return kind == WorksKind.STALL || b === want.block
        if (b !== want.block) return false
        return b is DoorBlock || b is TrapDoorBlock || b is FenceGateBlock || b is CampfireBlock || b is BarrelBlock || b is ChestBlock ||
            b is ComposterBlock || ours(here)
    }

    /** Makes room at [pos] for [want]: whoever stands where a solid block goes is waited for, and after ten seconds lifted onto it. */
    private fun clearFor(i: Int, pos: BlockPos, want: BlockState): Laid {
        if (!want.getCollisionShape(level, pos).isEmpty) {
            val inside = level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos)) { it.isAlive && !it.isSpectator }
            for (e in inside) {
                if (e is Player) {
                    val since = waiting.getOrPut(i) { level.gameTime }
                    if (level.gameTime - since < 200) return Laid.WAIT
                }
                e.teleportTo(e.x, pos.y + 1.0, e.z)
            }
        }
        return Laid.DONE
    }

    /** A column levelled to stand at the step's height (the tower's ground): only the land's own growth comes away. */
    private fun ground(i: Int, step: Step, pos: BlockPos, fx: Boolean): Laid {
        val top = min(level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x, pos.z), pos.y + CLEAR_ABOVE)
        val cursor = BlockPos.MutableBlockPos(pos.x, pos.y - 1, pos.z)
        while (cursor.y < top) {
            val state = level.getBlockState(cursor)
            if (!state.isAir && !HomesteadSite.wild(state) && !soft(state) && !ours(state)) {
                blocked = cursor.immutable()
                blockedStep = i
                return Laid.BLOCKED
            }
            cursor.move(Direction.UP)
        }
        cursor.set(pos.x, pos.y, pos.z)
        while (cursor.y < top) {
            if (!level.getBlockState(cursor).isAir) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS)
            cursor.move(Direction.UP)
        }
        val surface = state(step.b, pos)
        val under = pos.below()
        val was = level.getBlockState(under)
        if (was != surface && (was.isAir || HomesteadSite.wild(was) || soft(was))) set(under, surface)
        val fill = style.state(step.fill ?: B(M.SOIL), 0)
        cursor.set(pos.x, pos.y - 2, pos.z)
        var depth = 0
        while (depth++ < MAX_FILL && cursor.y > level.minY && WorldCanvas.loose(level.getBlockState(cursor)) && !level.getBlockState(cursor).hasBlockEntity()) {
            set(cursor.immutable(), fill)
            cursor.move(Direction.DOWN)
        }
        if (fx) {
            dust(under, if (was.isAir) surface else was, 8)
            level.playSound(null, under, surface.soundType.placeSound, SoundSource.BLOCKS, 0.7f, 0.85f + level.random.nextFloat() * 0.2f)
        }
        return Laid.DONE
    }

    /** A column at the edge of a levelled lot, cut or built up toward its height; only the wild is touched. */
    private fun bank(pos: BlockPos, fx: Boolean) {
        val surfaceTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x, pos.z)
        val cursor = BlockPos.MutableBlockPos(pos.x, surfaceTop - 1, pos.z)
        while (cursor.y > level.minY && WorldCanvas.loose(level.getBlockState(cursor))) cursor.move(Direction.DOWN)
        val land = level.getBlockState(cursor)
        if (!HomesteadSite.wild(land)) return
        val top = if (land.`is`(BlockTags.SUBSTRATE_OVERWORLD) || land.`is`(BlockTags.SAND)) land.trySetValue(BlockStateProperties.SNOWY, false) else style.palette.ground.defaultBlockState()
        val landY = cursor.y
        cursor.set(pos.x, pos.y, pos.z)
        val clearTo = min(surfaceTop, pos.y + CLEAR_ABOVE)
        while (cursor.y < clearTo) {
            val state = level.getBlockState(cursor)
            if (!state.isAir && HomesteadSite.wild(state)) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS)
            cursor.move(Direction.UP)
        }
        val subsoil = style.palette.subsoil.defaultBlockState()
        for (y in landY + 1 until pos.y - 1) {
            cursor.set(pos.x, y, pos.z)
            if (WorldCanvas.loose(level.getBlockState(cursor))) set(cursor.immutable(), subsoil)
        }
        val under = pos.below()
        val was = level.getBlockState(under)
        if (was.isAir || HomesteadSite.wild(was)) set(under, top)
        if (fx && level.random.nextInt(2) == 0) dust(under, top, 5)
    }

    // ---- The stakes' line ----

    /** A line strung along the ground between the stakes (string pegged out), only over open ground. */
    private fun string() {
        if (rec.strings.isNotEmpty()) return
        val stakes = drawing.stakes
        if (stakes.size < 2) return
        val xs = stakes.map { it.first }
        val zs = stakes.map { it.second }
        val x0 = xs.min(); val x1 = xs.max(); val z0 = zs.min(); val z1 = zs.max()
        val y = drawing.y0
        for (x in x0..x1) for (z in z0..z1) {
            val edge = x == x0 || x == x1 || z == z0 || z == z1
            if (!edge || (x to z) in stakes) continue
            val pos = frame.world(x, y, z)
            if (!level.getBlockState(pos).isAir || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue
            level.setBlock(pos, Blocks.TRIPWIRE.defaultBlockState(), FLAGS)
            rec.strings.add(pos.asLong())
        }
        WorksBuildData.get(level).changed()
    }

    /** The line taken up again. */
    fun unstring() {
        if (rec.strings.isEmpty()) return
        for (p in rec.strings) {
            val pos = BlockPos.of(p)
            if (level.isLoaded(pos) && level.getBlockState(pos).`is`(Blocks.TRIPWIRE)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
        }
        rec.strings.clear()
        WorksBuildData.get(level).changed()
    }

    // ---- The siting's dry run ----

    /**
     * Whether it can go up here as the world stands: every block it lays or clears is air, a plant, the wild (out in
     * the country), its own or already as drawn, or the town's that it's drawn to go over (holding nothing, unless
     * it's carried with what's in it); the ground it levels is the land's own; and the ground under its lot holds.
     * The first block in the way, or null if it fits. Only asked with its ground loaded.
     */
    fun inWay(): BlockPos? {
        for (s in plan.steps) {
            val pos = frame.world(s.x, s.y, s.z)
            if (level.isOutsideBuildHeight(pos)) continue
            when (s.kind) {
                StepKind.BLOCK -> {
                    val here = level.getBlockState(pos)
                    if (here.isAir || same(here, state(s.b, pos))) continue
                    if (!takeable(pos, here, Packed.of(s.x, s.y, s.z))) return pos
                }
                StepKind.GROUND -> {
                    val top = min(level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x, pos.z), pos.y + CLEAR_ABOVE)
                    val cursor = BlockPos.MutableBlockPos(pos.x, pos.y - 1, pos.z)
                    while (cursor.y < top) {
                        val state = level.getBlockState(cursor)
                        if (!state.isAir && !HomesteadSite.wild(state) && !soft(state) && !ours(state)) return cursor.immutable()
                        cursor.move(Direction.UP)
                    }
                }
                StepKind.STAKE, StepKind.SCAFFOLD, StepKind.TOPPING -> {
                    // Set only where there's room: never in the way
                }
                else -> {}
            }
        }
        if (countryside) return null
        val lot = drawing.lot
        for (x in lot.x0..lot.x1) for (z in lot.z0..lot.z1) {
            val local = Packed.of(x, drawing.y0 - 1, z)
            if (byPlace.containsKey(local)) continue
            val under = frame.world(x, drawing.y0 - 1, z)
            val state = level.getBlockState(under)
            if (!state.isFaceSturdy(level, under, Direction.UP) || !state.fluidState.isEmpty) return under
        }
        return null
    }

    // ---- Before and after ----

    /**
     * Keeps what stands where it's to build, before it begins ([BuildRec.found]): put back if it's ever taken down. Only
     * once, with all its ground loaded.
     */
    fun surveyFound() {
        if (rec.surveyed || !loaded()) return
        for (s in plan.steps) {
            if (s.kind != StepKind.BLOCK) continue
            val pos = frame.world(s.x, s.y, s.z)
            val state = level.getBlockState(pos)
            if (!state.isAir) rec.found[pos.asLong()] = state
        }
        rec.surveyed = true
        WorksBuildData.get(level).changed()
    }

    /**
     * Takes it down to what its drawing found there (`works remove`, or called off): everything of its own out, what
     * it built over put back, what it carried carried back. Its yard, post and plaque go too. False if its ground
     * isn't loaded (it waits for that).
     */
    fun takeDown(): Boolean {
        if (!loaded()) return false
        unstring()
        // Backwards: the last laid comes away first
        for (i in plan.steps.indices.reversed()) {
            val s = plan.steps[i]
            val pos = frame.world(s.x, s.y, s.z)
            when (s.kind) {
                StepKind.BLOCK -> {
                    val here = level.getBlockState(pos)
                    val want = state(s.b, pos)
                    val found = rec.found[pos.asLong()]
                    val local = Packed.of(s.x, s.y, s.z)
                    if (s.b.m == M.AIR) {
                        // A cleared place gets back what was found there (carried back, if the drawing carried it off)
                        if (found != null && here.isAir) {
                            set(pos, found)
                            drawing.moves.entries.firstOrNull { it.value == local }?.let { (to, _) -> carry(frame.world(Packed.x(to), Packed.y(to), Packed.z(to)), pos) }
                        }
                        continue
                    }
                    if (!same(here, want) && !ours(here)) continue
                    if (drawing.moves.containsKey(local)) {
                        // Set down here from elsewhere: it goes back there (below), carrying its things
                        val f = drawing.moves.getValue(local)
                        val back = frame.world(Packed.x(f), Packed.y(f), Packed.z(f))
                        val was = rec.found[back.asLong()]
                        if (was != null && level.getBlockState(back).isAir) {
                            set(back, was)
                            carry(pos, back)
                        }
                    }
                    empty(pos)
                    set(pos, found ?: Blocks.AIR.defaultBlockState())
                }
                StepKind.STAKE, StepKind.PULL_STAKE -> for (p in listOf(pos, pos.above())) {
                    val here = level.getBlockState(p)
                    if (here.`is`(BlockTags.FENCES) || here.`is`(BlockTags.BANNERS)) level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS)
                }
                StepKind.SCAFFOLD, StepKind.UNSCAFFOLD -> if (level.getBlockState(pos).`is`(Blocks.SCAFFOLDING)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                StepKind.TOPPING, StepKind.UNTOP -> if (level.getBlockState(pos).`is`(BlockTags.LEAVES)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                else -> {}
            }
        }
        return true
    }

    /**
     * What of it is gone since it was finished (broken, burnt, taken): the steps whose block is missing, where it would
     * go into air (anything else stands there now, and stays). The morning's mending lays these again.
     */
    fun missing(): List<Int> {
        val out = ArrayList<Int>()
        for ((i, s) in plan.steps.withIndex()) {
            if (s.kind != StepKind.BLOCK || s.b.m == M.AIR) continue
            val pos = frame.world(s.x, s.y, s.z)
            if (!level.isLoaded(pos)) continue
            // Only what goes up for good: not the GROUNDWORK's carrying out, not a stall's display (its keeper's)
            if (s.stage == Stage.GROUNDWORK || s.b.m == M.WARES && kind == WorksKind.STALL) continue
            if (level.getBlockState(pos).isAir) out.add(i)
        }
        return out
    }

    /** Lays step [i] again into air, as mending (no charge, no clock): true if it went in. */
    fun mend(i: Int, fx: Boolean): Boolean {
        val s = plan.steps[i]
        val pos = frame.world(s.x, s.y, s.z)
        if (!level.getBlockState(pos).isAir) return false
        val want = state(s.b, pos)
        if (clearFor(i, pos, want) != Laid.DONE) return false
        set(pos, want)
        settle(s, pos, want)
        joinUp(pos)
        if (fx) {
            dust(pos, want, 10)
            level.playSound(null, pos, want.soundType.placeSound, SoundSource.BLOCKS, 0.9f, want.soundType.pitch * 0.85f)
        }
        return true
    }

    // ---- Small helpers ----

    override fun stateFor(i: Int): BlockState {
        val step = plan.steps[i]
        val pos = at(i)
        return when (step.kind) {
            StepKind.SCAFFOLD, StepKind.UNSCAFFOLD -> SCAFFOLD
            StepKind.STAKE -> state(step.fill ?: B(M.FENCE), pos)
            else -> state(step.b, pos)
        }
    }

    /** The real block for the drawing's [b] at [pos]: a marker as the works' own block, a display as the setting has it, the rest in the capital's style, turned. */
    fun state(b: B, pos: BlockPos): BlockState {
        if (b.v == WorksDrawing.MARK) {
            val facing = frame.direction(b.dir ?: Dir.N)
            return when (b.m) {
                M.HOOK -> WorksBlocks.HOIST.get().defaultBlockState().setValue(WorksFacingBlock.FACING, facing)
                M.CAULDRON -> WorksBlocks.BEACON.get().defaultBlockState().setValue(WorksFacingBlock.FACING, facing)
                M.CRATE -> WorksBlocks.PITCH.get().defaultBlockState().setValue(WorksFacingBlock.FACING, facing)
                M.WOOL -> WorksBlocks.AWNING.get().defaultBlockState().setValue(WorksFacingBlock.FACING, facing)
                else -> Blocks.AIR.defaultBlockState()
            }
        }
        if (b.m == M.WARES && !WaresRegistry.enabled()) {
            // Displays switched off: sacks and barrels instead
            return when (Wares.entries[Math.floorMod(b.v, Wares.entries.size)]) {
                Wares.RESERVE_GRAIN, Wares.GRAIN -> Blocks.HAY_BLOCK.defaultBlockState()
                else -> Blocks.BARREL.defaultBlockState()
            }
        }
        return style.state(b, hash(pos)).rotate(frame.rotation)
    }

    /** The patron's colour for a stall's cloth. */
    val patron: Dye get() = Dye.entries[rec.color.coerceIn(0, Dye.entries.size - 1)]

    private fun set(pos: BlockPos, state: BlockState) {
        level.setBlock(pos, state, FLAGS)
    }

    private fun joinUp(pos: BlockPos) {
        reshape(pos)
        for (d in Direction.Plane.HORIZONTAL) reshape(pos.relative(d))
        reshape(pos.above())
        reshape(pos.below())
    }

    private fun reshape(pos: BlockPos) {
        val state = level.getBlockState(pos)
        val block = state.block
        if (block !is CrossCollisionBlock && block !is WallBlock && block !is StairBlock && block !is FenceGateBlock) return
        val fixed = Block.updateFromNeighbourShapes(state, level, pos)
        if (fixed != state && !fixed.isAir) level.setBlock(pos, fixed, FLAGS)
    }

    private fun roomy(pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.isAir || state.canBeReplaced() && state.fluidState.isEmpty
    }

    private fun dust(pos: BlockPos, state: BlockState, n: Int) {
        if (state.isAir) return
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, state), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, n, 0.3, 0.3, 0.3, 0.08)
    }

    private fun sound(pos: BlockPos, event: SoundEvent, volume: Float) {
        level.playSound(null, pos, event, SoundSource.BLOCKS, volume, 0.9f + level.random.nextFloat() * 0.2f)
    }

    companion object {
        private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

        /** How far over a levelled column the growth is cleared. */
        private const val CLEAR_ABOVE = 24

        /** Deepest a dip under a levelled column is filled. */
        private const val MAX_FILL = 40

        /** Ticks a held-up step waits before it's tried again. */
        private const val RETRY_BLOCKED = 600L

        private val SCAFFOLD: BlockState = Blocks.SCAFFOLDING.defaultBlockState()
            .setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, false)

        private fun hash(pos: BlockPos): Int = (Mth.getSeed(pos.x, pos.y, pos.z) ushr 8).toInt()
    }
}
