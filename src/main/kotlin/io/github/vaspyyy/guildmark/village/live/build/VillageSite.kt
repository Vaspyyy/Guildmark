package io.github.vaspyyy.guildmark.village.live.build

import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlocks
import io.github.vaspyyy.guildmark.economy.works.build.WorksFacingBlock
import io.github.vaspyyy.guildmark.homestead.Build
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.HomesteadRegistry
import io.github.vaspyyy.guildmark.homestead.HomesteadSite
import io.github.vaspyyy.guildmark.homestead.Laid
import io.github.vaspyyy.guildmark.homestead.Worksite
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Step
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.homestead.plan.TierPlan
import io.github.vaspyyy.guildmark.lamps.StreetLampBlock
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.ToppingOut
import io.github.vaspyyy.guildmark.village.live.VillageLamps
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A village project as it goes up in the world (living-world/villages/design.md 12.3): the blocks its builders recorded
 * ([VillageBuild]) laid one by one by a crew in working hours, by the village's own craftsfolk lent for the day and strangers
 * from the road making up the number ([BuilderCrew], as a homestead's and a capital's works are), and by the clock while nobody is
 * about to see it. Its stages are the ground and foundations, the walls, the roof, the openings, the fittings and the grounds
 * about, in that order, with four corner stakes first for a hall or a tower and a green bough on the roof of the three big projects.
 *
 * Each block goes in as the builders recorded it, shaped already by what is to stand beside it (BuildSink worked that out), so it
 * is set without the shapes about it being worked out again, and what stands at the end is what a direct build leaves. A step is
 * open only once what holds it up is laid ([VillageBuild.deps]) and its stage has come, so nothing floats at any step; the halves of a
 * door and the cells of a board go in together.
 *
 * It follows [io.github.vaspyyy.guildmark.economy.works.build.TownWorksSite]'s rules: a block only goes into air, plants or what grew
 * wild (and over what stood there when the builders recorded it, but not over a chest or a furnace someone has put since); whatever
 * holds a step up is waited for a day of work, and then the step is left out; whoever stands where a block goes is waited for, and
 * after ten seconds lifted onto it. It needs no materials: the crate in its yard is the place the crew carries from, beside heaps
 * of what the project is made of that go down as it is laid. The crew works from sunrise to a quarter to six with an hour off at
 * noon, and never at night.
 */
class VillageSite(
    override val level: ServerLevel,
    val rec: VillageBuild,
    /** Told when a step is laid (the saved file is to be written); [VillageBuilds.changed] unless another is given (a test's). */
    private val changed: Runnable? = null,
) : Worksite {
    val project: Advance = Advance.entries[rec.project.coerceIn(0, Advance.entries.size - 1)]
    val build: Build get() = rec.build
    val id: Int get() = rec.id
    val bell: BlockPos get() = BlockPos.of(rec.village)

    private val places: Array<BlockPos> = Array(rec.size) { BlockPos.of(rec.pos[it]) }
    private val states: Array<BlockState> = Array(rec.size) { stateOf(it) }
    private val stages: Array<Stage> = Array(rec.size) { i ->
        var s = Stage.entries.size - 1
        while (s > 0 && rec.stageStarts[s] > i) s--
        Stage.entries[s]
    }

    override val plan: TierPlan = TierPlan(
        0,
        List(rec.size) { i ->
            val kind = rec.kind(i)
            val step = when (kind) {
                VillageBuild.Kinds.STAKE -> StepKind.STAKE
                VillageBuild.Kinds.PULL -> StepKind.PULL_STAKE
                VillageBuild.Kinds.TOP -> StepKind.TOPPING
                VillageBuild.Kinds.UNTOP -> StepKind.UNTOP
                else -> StepKind.BLOCK
            }
            Step(step, stages[i], places[i].x, places[i].y, places[i].z, look(kind, states[i]), stuff = if (kind == VillageBuild.Kinds.BLOCK) stuffOf(states[i]) else Stuff.FREE)
        },
        listOf(),
    )

    // ---- As its crew sees it (Worksite) ----

    override val key: Int get() = KEY - rec.id

    /** The hands it wants: the village's own craftsfolk lent for the day ([named] of them are about) and strangers making up the number, always one or more (the foreman). */
    override val crew: Int get() = crewOf(project)

    /** The residents lent to it today who are about it now (counted each second by [VillageBuilds]). */
    var named: Int = 0

    override val strangers: Int get() = max(1, crew - named)

    override val work: Double get() = build.work

    /** The materials of a village's project are not counted: all it has is there to be laid. */
    override val reach: Int get() = total

    override val short: Stuff? get() = null

    override val total: Int get() = places.size
    override val laid: Int get() = build.step + build.done.cardinality()
    override val finished: Boolean get() = build.step >= places.size
    override val stage: Stage get() = plan.stageOf(build.step)
    private val stageEnd: Int get() = plan.stageStarts[stage.ordinal + 1]

    override fun isDone(i: Int): Boolean = i < build.step || build.done.get(i)
    override fun step(i: Int): Step = plan.steps[i]
    override fun at(i: Int): BlockPos = places[i]
    override fun stateFor(i: Int): BlockState = states[i]

    /** The supply crate in its yard (the works' own crate block, so a crew has somewhere to fetch from and carry out of). */
    override val crate: BlockPos get() = BlockPos.of(rec.yard.crate)

    override val out: Direction get() = rec.yard.outDir

    /** Standing before the crate, on the side it faces. */
    override fun crateFront(): Vec3 {
        val c = crate
        val state = if (level.isLoaded(c)) level.getBlockState(c) else null
        val facing = if (state != null && state.hasProperty(WorksFacingBlock.FACING)) state.getValue(WorksFacingBlock.FACING) else out.opposite
        val stand = c.relative(facing)
        return Vec3(stand.x + 0.5, stand.y.toDouble(), stand.z + 0.5)
    }

    override fun gate(): Vec3 {
        val g = BlockPos.of(rec.yard.gate)
        return Vec3(g.x + 0.5, g.y.toDouble(), g.z + 0.5)
    }

    /** Where its strangers come in from and go back to: the road's end, or open ground out from the village; its gate if neither is about. */
    override fun road(): BlockPos {
        val r = BlockPos.of(rec.yard.road)
        if (!level.isPositionEntityTicking(r)) return BlockPos.containing(gate())
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, r)
    }

    override fun progress(): Component {
        val percent = if (total == 0) 100 else (laid * 100L / total).toInt()
        return Component.translatable("message.guildmark.village_build.progress", project.title, Component.translatable("gui.guildmark.homestead.stage.${stage.key}"), percent)
    }

    /** The day the crew last rang in: a crew comes in to the foreman's bell once a day, at sunrise; coming back later in the day they only say so. */
    private var rang = Long.MIN_VALUE

    override fun arrive(p: Townsperson, road: BlockPos) {
        val today = Math.floorDiv(level.overworldClockTime, 24000L)
        if (rang == today) {
            p.say("village_build_back", force = true)
            return
        }
        rang = today
        level.playSound(null, road, HomesteadRegistry.ARRIVE.get(), SoundSource.NEUTRAL, 1.4f, 1f)
        p.say("homestead_arrive_${1 + level.random.nextInt(3)}", force = true)
    }

    /** A stranger on the crew is given a name in the village's own style (one lent has their own), and every one of them the village's ways ([VillageCrew.Builder]). */
    override fun builder(p: Townsperson, road: BlockPos, lent: Boolean): BuilderCrew.Builder {
        if (!lent && p.customName == null) {
            val seed = rec.id * 7919L + p.id * 31L + level.gameTime / 24000L
            val style = CityStyle.forBiome(level.getBiome(bell))
            val woman = Names.woman(p.role, p.look, seed)
            p.customName = Component.literal(Names.given(style, woman, seed) + " " + Names.family(style, seed / 3))
            p.isCustomNameVisible = false
        }
        return VillageCrew.Builder(p, key, road, lent)
    }

    /** The town gathered round the topping out has its own cheer, not this one as well. */
    override fun cheer(stage: Stage): SoundEvent? = if (stage == Stage.TOPPING && ToppingOut.gathered(level, key)) null else HomesteadRegistry.CHEER.get()

    /** A building's stages are called with the homestead's words; the palisade's and the walls' with the village's own. */
    override fun stageLine(stage: Stage): String? = when (project) {
        Advance.GUILD_HALL, Advance.ARCHER_TOWER, Advance.KEEP -> if (stage == Stage.GROUNDWORK) "village_stage_groundwork" else "homestead_stage_${stage.key}"
        Advance.PALISADE -> if (stage == Stage.WALLS) "village_stage_posts" else null
        Advance.STONE_WALLS -> when (stage) {
            Stage.WALLS -> "village_stage_courses"
            Stage.ROOF -> "village_stage_caps"
            Stage.TOPPING -> "homestead_stage_topping"
            else -> null
        }
        else -> null
    }

    override fun needLine(stuff: Stuff): String? = null

    override fun doneLine(random: RandomSource): String = "village_done_${project.id}"

    // ---- The work clock ----

    /** Set when a stage has just been finished, for the crew to celebrate. */
    var finishedStage: Stage? = null

    private var lastStage: Stage = stage

    /** When the topping out began (not saved: after a restart it is not waited for again). */
    private var toppingFrom = Long.MIN_VALUE / 2

    /** Whether the topping out waits a little for the town gathering round it to see the bough go up. */
    fun awaitingTown(): Boolean = stage == Stage.TOPPING && level.gameTime - toppingFrom in 0 until GATHER && ToppingOut.gathered(level, key)

    /** Whether it is time for the crew to be at it: its first sunrise has come (or the clock never runs, or a day and a half has gone). */
    fun opened(): Boolean {
        if (rec.opens == Long.MIN_VALUE || !ticking()) return true
        return level.overworldClockTime >= rec.opens || level.gameTime - rec.born > 36000L
    }

    private fun ticking(): Boolean = level.gameRules.get(GameRules.ADVANCE_TIME)

    /** Winds the work clock on to now: each tick of working hours since it was last wound lets the crew lay a little more. */
    fun wind() {
        val now = level.gameTime
        val day = level.overworldClockTime
        if (build.clock < 0 || now < build.clock) {
            build.clock = now
            build.dayTime = day
            return
        }
        val worked = if (!opened()) 0L else if (!ticking()) now - build.clock else working(build.dayTime, day)
        build.clock = now
        build.dayTime = day
        if (worked <= 0) return
        rec.accrued += worked
        val left = (total - laid).toDouble()
        build.work = min(build.work + worked * rate(), left)
    }

    /** Steps a tick of work lays: the whole project in its days of working hours, at villageBuildSpeed. */
    fun rate(): Double = total / (daysOf(project) * WORKING / speed())

    /** Whether the project has had three times its days of working hours and is still not done (it is then finished out of sight; see [VillageBuilds]). */
    fun overdue(): Boolean = rec.accrued > 3.0 * daysOf(project) * WORKING / speed()

    /** The crew's day: sunrise to a quarter to six, the noon hour in it (with the clock standing still, always). */
    override fun workday(): Boolean = !ticking() || Math.floorMod(level.overworldClockTime, 24000L) < AFTERNOON_TO

    /** Whether it is the noon hour, when the crew downs tools for their dinner. */
    fun onBreak(): Boolean = ticking() && Math.floorMod(level.overworldClockTime, 24000L) in MORNING until AFTERNOON_FROM

    // ---- Handing out steps ----

    /** Who has each step in hand (a builder's entity id). */
    private val claims = HashMap<Int, Int>()

    /** For each step, the first of the things that go in together it is a part of (a door's halves, a board's cells), or -1. */
    private val leader = IntArray(places.size) { -1 }

    /** For the first part of each such thing, the others, in their order. */
    private val members = HashMap<Int, IntArray>()

    /** Steps someone was standing in the way of, and since when. */
    private val waiting = HashMap<Int, Long>()

    /** Steps held up by something that was put there, and when they were first (game time, clock time); they are left out after a day of work. */
    private val held = HashMap<Int, LongArray>()

    /** Being finished at once (`village build rush`, a switch turned off): whoever is in the way is lifted at once. */
    var hurry = false

    /** Where something that is not the village's holds a step up, for the commands. */
    var blocked: BlockPos? = null
        private set

    init {
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        for (i in places.indices) {
            if (rec.kind(i) != VillageBuild.Kinds.BLOCK) continue
            VillageOrder.groupOf(places[i], states[i])?.let { groups.getOrPut(it) { ArrayList() }.add(i) }
        }
        for (g in groups.values) {
            if (g.size < 2) continue
            val first = g.min()
            for (i in g) if (i != first) leader[i] = first
            members[first] = g.filter { it != first }.sorted().toIntArray()
        }
    }

    override fun open(i: Int): Boolean {
        if (i < build.step || i >= stageEnd || isDone(i) || claims.containsKey(i)) return false
        if (awaitingTown() || onBreak()) return false
        // A part of something that goes in together goes in with its first part
        val first = leader[i]
        if (first >= 0 && !isDone(first)) return false
        if (!held(i)) return false
        members[i]?.let { for (m in it) if (!held(m)) return false }
        held[i]?.let { if (level.gameTime - it[0] in 0 until RETRY_BLOCKED) return false }
        val since = waiting[i]
        if (since != null && level.gameTime - since <= 40) return false
        // Land that is not loaded is not worked: nothing is loaded for a step
        return level.isLoaded(places[i])
    }

    /** Whether what holds step [i] up is in (or nothing has to be, or it is a part of the same thing). */
    private fun held(i: Int): Boolean {
        val d = rec.deps[i]
        return d < 0 || isDone(d) || leader[i] >= 0 && (d == leader[i] || leader[d] == leader[i]) || leader[d] == i
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
                if (others.none { it.distManhattan(places[i]) < 6 }) {
                    first = i
                    break
                }
            }
            i++
        }
        if (first < 0) first = fallback
        if (first < 0) return IntArray(0)
        val anchor = places[first]
        val out = ArrayList<Int>()
        out.add(first)
        var j = first + 1
        while (j < min(end, first + 64) && out.size < most) {
            if (open(j)) {
                val pos = places[j]
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
        // In the noon hour what is in hand is finished off (the clock stands still for it); after that, the dinner
        if (!free && (build.work < 1.0 && !onBreak() || awaitingTown())) return Laid.LATER
        val outcome = place(i, fx)
        if (outcome != Laid.DONE) {
            if (outcome == Laid.BLOCKED && heldUp(i)) {
                // Held up a whole day of work: left out, and the project goes on
                done(i, free)
                return Laid.DONE
            }
            return outcome
        }
        done(i, free)
        // The rest of what goes in with it
        members[i]?.let { for (m in it) if (!isDone(m) && place(m, fx) == Laid.DONE) done(m, true) }
        return Laid.DONE
    }

    /** Step [i] is held up: since when it first was, and whether that is a day of work ago (time standing still: a day of ticks). */
    private fun heldUp(i: Int): Boolean {
        val now = longArrayOf(level.gameTime, level.overworldClockTime)
        val since = held.getOrPut(i) { now }
        val worked = if (!ticking()) now[0] - since[0] else working(since[1], now[1])
        return worked >= WORKING
    }

    private fun done(i: Int, free: Boolean) {
        claims.remove(i)
        waiting.remove(i)
        held.remove(i)
        if (i == build.step) {
            build.step++
            while (build.step < places.size && build.done.get(build.step)) {
                build.done.clear(build.step)
                build.step++
            }
        } else {
            build.done.set(i)
        }
        if (!free) build.work = max(0.0, build.work - 1.0)
        val now = stage
        if (now != lastStage && now == Stage.TOPPING) toppingFrom = level.gameTime
        if (now != lastStage || finished) {
            if (lastStage.ordinal > build.cheered) {
                build.cheered = lastStage.ordinal
                finishedStage = lastStage
            }
            lastStage = now
        }
        if (changed != null) changed.run() else VillageBuilds.changed(level)
    }

    /** Leaves step [i] out without laying it (`village build rush`, where nothing will do). */
    fun skip(i: Int) {
        if (!isDone(i)) done(i, true)
    }

    private fun place(i: Int, fx: Boolean): Laid {
        val pos = places[i]
        if (level.isOutsideBuildHeight(pos)) return Laid.DONE
        if (!level.isLoaded(pos)) return Laid.WAIT
        return when (rec.kind(i)) {
            VillageBuild.Kinds.STAKE -> {
                if (roomy(pos) && roomy(pos.above())) {
                    set(pos, STAKE)
                    set(pos.above(), FLAG)
                    if (fx) dust(pos, STAKE, 12)
                }
                Laid.DONE
            }
            VillageBuild.Kinds.PULL -> {
                for (p in listOf(pos.above(), pos)) {
                    val state = level.getBlockState(p)
                    if (state.`is`(Blocks.SPRUCE_FENCE) || state.`is`(Blocks.BANNER.pick(DyeColor.WHITE))) {
                        if (fx) level.levelEvent(2001, p, Block.getId(state))
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS)
                    }
                }
                Laid.DONE
            }
            VillageBuild.Kinds.TOP -> {
                if (level.getBlockState(pos).isAir) {
                    set(pos, BOUGH)
                    if (fx) dust(pos, BOUGH, 16)
                }
                Laid.DONE
            }
            VillageBuild.Kinds.UNTOP -> {
                val state = level.getBlockState(pos)
                if (state.`is`(Blocks.OAK_LEAVES) && state.getValue(LeavesBlock.PERSISTENT)) {
                    if (fx) level.levelEvent(2001, pos, Block.getId(state))
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                }
                Laid.DONE
            }
            else -> block(i, pos, fx)
        }
    }

    /** One block of the project, as it was recorded (a new lamp post's lantern as the village's lamps have it now). */
    private fun block(i: Int, pos: BlockPos, fx: Boolean): Laid {
        val want = lamp(states[i])
        // A block that can't be read is laid as nothing
        if (want == VillageBuild.UNKNOWN) return Laid.DONE
        val here = level.getBlockState(pos)
        if (same(here, want)) return Laid.DONE
        if (want.isAir) {
            if (here.isAir) return Laid.DONE
            if (!takeable(i, pos, here)) return blockedAt(pos)
            if (fx) level.levelEvent(2001, pos, Block.getId(here))
            level.setBlock(pos, want, FLAGS)
            return Laid.DONE
        }
        if (!here.isAir && !takeable(i, pos, here)) return blockedAt(pos)
        val clear = clearFor(i, pos, want)
        if (clear != Laid.DONE) return clear
        set(pos, want)
        if (fx) {
            dust(pos, want, 10)
            val sound = want.soundType
            level.playSound(null, pos, sound.placeSound, SoundSource.BLOCKS, (sound.volume + 1f) / 2f, sound.pitch * 0.8f)
        }
        return Laid.DONE
    }

    /** A lamp post's lantern, of a project of lamp posts: the street lamp or the plain lantern as the village's lamps take them now (VillageLamps.lantern). */
    private fun lamp(state: BlockState): BlockState {
        if (project != Advance.LAMP_POSTS) return state
        val lantern = state.block is StreetLampBlock || state.`is`(Blocks.LANTERN) && !state.getValue(LanternBlock.HANGING)
        return if (lantern) VillageLamps.lantern() else state
    }

    private fun blockedAt(pos: BlockPos): Laid {
        blocked = pos
        return Laid.BLOCKED
    }

    /**
     * Whether the block [here] at [pos] may be built over for step [i]: air, plants, snow and the wild, the stakes and the bough
     * it put up itself, and what stood there when the builders recorded the project (the ground, a palisade it replaces, a tree
     * it clears), but not what holds things or was put there since (a chest, a furnace).
     */
    private fun takeable(i: Int, pos: BlockPos, here: BlockState): Boolean = when {
        here.isAir -> true
        VillageOrder.soft(here) -> true
        HomesteadSite.wild(here) -> true
        (here.`is`(Blocks.SPRUCE_FENCE) || here.`is`(Blocks.BANNER.pick(DyeColor.WHITE))) && rec.kind(i) == VillageBuild.Kinds.BLOCK && isStake(pos) -> true
        rec.swaps(i) -> !here.hasBlockEntity()
        else -> false
    }

    /** Whether [pos] is where one of this project's stakes stands (its post or its banner). */
    private fun isStake(pos: BlockPos): Boolean {
        for (j in places.indices) {
            if (rec.kind(j) != VillageBuild.Kinds.STAKE) continue
            if (places[j] == pos || places[j].above() == pos) return true
        }
        return false
    }

    /**
     * Whether what is [here] is as good as [want]: the same block, its open doors and gates, a chest or barrel's lid, and a fence,
     * wall, pane or stair in whatever shape what has been built beside it since has given it.
     */
    private fun same(here: BlockState, want: BlockState): Boolean {
        if (here == want) return true
        val b = here.block
        if (b !== want.block) return false
        return b is DoorBlock || b is TrapDoorBlock || b is FenceGateBlock || b is BarrelBlock || b is ChestBlock || b is CrossCollisionBlock || b is WallBlock || b is StairBlock
    }

    /**
     * Makes room at [pos] for [want]: whoever stands where a solid block goes is waited for, and after ten seconds lifted onto it (one of
     * the crew asks them to mind out the first time); a beast or one of the crew is stepped up at once.
     */
    private fun clearFor(i: Int, pos: BlockPos, want: BlockState): Laid {
        if (want.getCollisionShape(level, pos).isEmpty) return Laid.DONE
        val inside = level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos)) { it.isAlive && !it.isSpectator }
        for (e in inside) {
            if (e is Player && !hurry) {
                val first = !waiting.containsKey(i)
                val since = waiting.getOrPut(i) { level.gameTime }
                if (first) mindOut(e)
                if (level.gameTime - since < LIFT_AFTER) return Laid.WAIT
            }
            e.teleportTo(e.x, pos.y + 1.0, e.z)
        }
        return Laid.DONE
    }

    /** The nearest of the crew asks [player] to mind out of the way. */
    private fun mindOut(player: Player) {
        val b = BuilderCrew.builders(key).filter { !it.leaving }.minByOrNull { it.p.distanceToSqr(player) } ?: return
        if (b.p.distanceToSqr(player) > 12.0 * 12.0) return
        b.p.lookAt(player)
        b.p.say("village_build_mind_${1 + level.random.nextInt(2)}", force = true)
    }

    // ---- Small helpers ----

    private fun set(pos: BlockPos, state: BlockState) {
        level.setBlock(pos, state, FLAGS)
    }

    private fun roomy(pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.isAir || state.canBeReplaced() && state.fluidState.isEmpty
    }

    private fun dust(pos: BlockPos, state: BlockState, n: Int) {
        if (state.isAir) return
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, state), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, n, 0.3, 0.3, 0.3, 0.08)
    }

    /** The world box the project reaches (for the commands, the crew's being there and the topping out), with the bough and the stakes. */
    val box: BoundingBox by lazy {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var z0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE; var z1 = Int.MIN_VALUE
        for (p in places) {
            x0 = min(x0, p.x); y0 = min(y0, p.y); z0 = min(z0, p.z)
            x1 = max(x1, p.x); y1 = max(y1, p.y); z1 = max(z1, p.z)
        }
        if (places.isEmpty()) BoundingBox(bell) else BoundingBox(x0, y0, z0, x1, y1, z1)
    }

    /** Whether the land it stands on and its yard are loaded (the clock and the crew lay only then). */
    fun loaded(): Boolean {
        val b = box
        if (!level.isLoaded(BlockPos(b.minX(), b.minY(), b.minZ())) || !level.isLoaded(BlockPos(b.maxX(), b.minY(), b.maxZ())) ||
            !level.isLoaded(BlockPos(b.minX(), b.minY(), b.maxZ())) || !level.isLoaded(BlockPos(b.maxX(), b.minY(), b.minZ()))
        ) return false
        return yard().all { level.isLoaded(it) }
    }

    /** The crate and the heaps of its yard. */
    fun yard(): List<BlockPos> = listOf(crate) + rec.yard.heaps.map { BlockPos.of(it) }

    /** Where the bough goes, if the project has one. */
    fun bough(): BlockPos? {
        for (i in places.indices) if (rec.kind(i) == VillageBuild.Kinds.TOP) return places[i]
        return null
    }

    /** How many steps of each kind of material are still to be laid, and how many there are in all (for the heaps in the yard, which go down as they are laid). */
    fun left(): IntArray {
        val counts = IntArray(Stuff.PAID.size)
        for (i in build.step until places.size) {
            if (isDone(i)) continue
            val s = plan.steps[i].stuff
            if (s != Stuff.FREE) counts[s.ordinal]++
        }
        return counts
    }

    private fun stateOf(i: Int): BlockState = when (rec.kind(i)) {
        VillageBuild.Kinds.STAKE -> STAKE
        VillageBuild.Kinds.TOP -> BOUGH
        VillageBuild.Kinds.PULL, VillageBuild.Kinds.UNTOP -> Blocks.AIR.defaultBlockState()
        else -> rec.state(i)
    }

    companion object {
        /** A site's crew key is this less its id (a homestead's is from 0 up, a capital works' is -1 less its id). */
        const val KEY = -1_000_000

        /** Blocks are set as recorded, already in the shape their neighbours give them: the shapes about them are not worked out again. */
        const val FLAGS = Block.UPDATE_ALL or Block.UPDATE_KNOWN_SHAPE

        /** Ticks a held-up step waits before it is tried again; ticks the topping out waits for the town to gather round it; ticks a player in the way is waited for. */
        private const val RETRY_BLOCKED = 600L
        private const val GATHER = 300L
        private const val LIFT_AFTER = 200L

        /** The ticks of a day the crew works: from sunrise to noon and from an hour after noon to a quarter to six (the clock's tick 0 is sunrise). */
        const val MORNING = 6000
        const val AFTERNOON_FROM = 7000
        const val AFTERNOON_TO = 11_700
        const val WORKING = (MORNING + AFTERNOON_TO - AFTERNOON_FROM).toLong()

        val STAKE: BlockState = Blocks.SPRUCE_FENCE.defaultBlockState()
        val FLAG: BlockState = Blocks.BANNER.pick(DyeColor.WHITE).defaultBlockState()
        val BOUGH: BlockState = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)

        /** Days of work at speed 1.0 and the hands wanted, by project (design 12.3). */
        fun daysOf(project: Advance): Double = when (project) {
            Advance.LAMP_POSTS -> 1.0
            Advance.GUILD_HALL -> 2.0
            Advance.PALISADE -> 3.0
            Advance.ARCHER_TOWER -> 2.0
            Advance.MARKET -> 2.0
            Advance.STONE_WALLS -> 4.0
            Advance.KEEP -> 5.0
            Advance.TRADE_ROAD -> 1.0
        }

        fun crewOf(project: Advance): Int = when (project) {
            Advance.LAMP_POSTS -> 3
            Advance.GUILD_HALL -> 5
            Advance.PALISADE -> 6
            Advance.ARCHER_TOWER -> 4
            Advance.MARKET -> 5
            Advance.STONE_WALLS -> 8
            Advance.KEEP -> 10
            Advance.TRADE_ROAD -> 3
        }

        /** The work clock's pace (GuildmarkConfig villageBuildSpeed): 2 builds in half the time. */
        fun speed(): Double = try {
            io.github.vaspyyy.guildmark.GuildmarkConfig.VILLAGE_BUILD_SPEED.get().coerceIn(0.1, 20.0)
        } catch (e: IllegalStateException) {
            1.0
        }

        /** Whether the crew is at work at [m] ticks into the day (the day starts at sunrise): not at noon, nor after a quarter to six. */
        fun working(m: Long): Boolean = m in 0 until MORNING || m in AFTERNOON_FROM until AFTERNOON_TO

        /** Ticks of working hours between two times of day (time that goes back counts for nothing). */
        fun working(from: Long, to: Long): Long = max(0L, upTo(to) - upTo(from))

        private fun upTo(t: Long): Long {
            val m = Math.floorMod(t, 24000L)
            val inDay = min(m, MORNING.toLong()) + max(0L, min(m, AFTERNOON_TO.toLong()) - AFTERNOON_FROM)
            return Math.floorDiv(t, 24000L) * WORKING + inDay
        }

        /** What the crew sees of a block for its work: timber is sawn and nailed, the rest set. */
        private fun look(kind: Int, s: BlockState): B {
            if (kind != VillageBuild.Kinds.BLOCK) return B.AIR
            return when {
                s.isAir -> B.AIR
                s.`is`(BlockTags.LOGS) -> B(M.TIMBER)
                s.`is`(BlockTags.PLANKS) || s.`is`(BlockTags.WOODEN_STAIRS) || s.`is`(BlockTags.WOODEN_SLABS) || s.`is`(BlockTags.WOODEN_FENCES) ||
                    s.`is`(BlockTags.FENCE_GATES) || s.`is`(BlockTags.WOODEN_TRAPDOORS) || s.block is LadderBlock -> B(M.PLANKS)
                s.`is`(BlockTags.DOORS) -> B(M.DOOR)
                else -> B(M.FOUNDATION)
            }
        }

        /** What a block is made of, as far as the crew's carrying goes: it fetches timber, stone and the rest from the crate, but not earth and the like. */
        fun stuffOf(s: BlockState): Stuff = when {
            s.isAir -> Stuff.FREE
            s.`is`(BlockTags.LOGS) || s.`is`(BlockTags.PLANKS) || s.`is`(BlockTags.WOODEN_STAIRS) || s.`is`(BlockTags.WOODEN_SLABS) ||
                s.`is`(BlockTags.WOODEN_FENCES) || s.`is`(BlockTags.FENCE_GATES) || s.`is`(BlockTags.WOODEN_TRAPDOORS) || s.`is`(BlockTags.DOORS) ||
                s.block is LadderBlock || s.`is`(Blocks.BOOKSHELF) || s.`is`(Blocks.LECTERN) || s.`is`(Blocks.BARREL) || s.`is`(Blocks.CRAFTING_TABLE) ||
                s.`is`(Blocks.CHEST) -> Stuff.TIMBER
            s.`is`(Blocks.GLASS_PANE) || s.`is`(Blocks.GLASS) -> Stuff.GLASS
            s.block is LanternBlock || s.block is StreetLampBlock || s.`is`(Blocks.IRON_CHAIN) || s.`is`(Blocks.IRON_BARS) -> Stuff.IRON
            s.`is`(BlockTags.WOOL) || s.`is`(BlockTags.WOOL_CARPETS) || s.`is`(BlockTags.BANNERS) -> Stuff.WOOL
            s.`is`(BlockTags.DIRT) || s.`is`(Blocks.DIRT_PATH) || s.`is`(BlockTags.SAND) || s.`is`(Blocks.GRAVEL) || s.`is`(Blocks.MUD) -> Stuff.FREE
            else -> Stuff.STONE
        }

        /** The yard's heap for [stuff]: the works' own heap block (they share one block entity), or null for earth. */
        fun heapOf(stuff: Stuff): BlockState? {
            val kind = io.github.vaspyyy.guildmark.economy.works.build.YardStuff.of(stuff) ?: return null
            return WorksBlocks.yard(kind).get().defaultBlockState()
        }
    }
}
