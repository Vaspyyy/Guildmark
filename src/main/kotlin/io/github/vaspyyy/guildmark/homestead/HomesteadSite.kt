package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.WorldCanvas
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Spawn
import io.github.vaspyyy.guildmark.field.TrollCamp
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Step
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.homestead.plan.TierPlan
import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How laying a step went. */
enum class Laid {
    /** It's in. */
    DONE,
    /** Someone is standing where it goes; it waits for them to move. */
    WAIT,
    /** Something of someone's that holds things (a chest, a furnace) is in the way; it waits for that to be moved. */
    BLOCKED,
    /** The supply crate has none of what it's made of. */
    NEED,
    /** The crew hasn't the time for it yet (see [HomesteadSite.wind]). */
    LATER,
}

/**
 * A homestead's build as it goes up in the world: the tier's plan ([plan], see BuildOrder) worked through step by step,
 * by the crew while someone is about to watch (BuilderCrew) and by the time they would have taken while nobody is.
 *
 * The work clock counts daylight only, as the crew works sunrise to sunset; at the normal pace a tier takes its
 * [HomesteadTiers.Tier.days] of daylight. A step is only laid when the clock has allowed it and the supply crate holds
 * what it's made of: the tier's bill of materials is spread over its steps, and the crew takes a plank, a block or an
 * ingot out of the crate as the last one runs out. A stage (see [Stage]) is only started when the one before is
 * finished, so the roof never goes on before the walls, whoever lays it; within a stage the crew lays what each of them
 * has in hand, so steps can be done out of order ([Build.done]).
 *
 * Laying a step is safe to do again: what's already in place stays, a beast already let out isn't let out twice. The
 * land is only ever cut, filled and cleared where it grew wild; anything someone made that stands where a block goes is
 * knocked out and dropped as an item, unless it holds things, when the step waits ([Laid.BLOCKED]) and the ledger says
 * where.
 */
class HomesteadSite(val level: ServerLevel, val homestead: Homestead, val plan: TierPlan) {
    val build: Build get() = homestead.build
    val tier: HomesteadTiers.Tier = HomesteadTiers.of(plan.tier) ?: HomesteadTiers.TIERS.last()
    private val frame = homestead.frame
    private val style: CityStyle = homestead.cityStyle

    /** Units of each paid material one step takes: the tier's bill spread over its steps of that material. */
    private val per = DoubleArray(Stuff.PAID.size) { s -> if (plan.needs[s] == 0) 0.0 else tier.bill[s].toDouble() / plan.needs[s] }

    /** Who has each step in hand (a builder's entity id). */
    private val claims = HashMap<Int, Int>()

    /** The other half of a bed or a door: both go in together. */
    private val partner = IntArray(plan.steps.size) { -1 }

    /** The lowest pole of each column of scaffolding, which is carried down to the ground if the ground is lower. */
    private val poleBase = java.util.BitSet()

    /** Steps someone was standing in the way of, and since when. */
    private val waiting = HashMap<Int, Long>()

    /** Where something that holds things is in the way of a step, if anywhere. */
    var blocked: BlockPos? = null
        private set
    private var blockedStep = -1

    /** The material the crate ran out of, if it has. */
    var short: Stuff? = null
        private set

    /** The stage the build is in, as last seen (to tell when one is finished). */
    private var lastStage: Stage = plan.stageOf(build.step)

    /** Set when a stage has just been finished, for the crew to celebrate. */
    var finishedStage: Stage? = null

    init {
        val at = HashMap<Long, Int>()
        for ((i, s) in plan.steps.withIndex()) if (s.kind == StepKind.BLOCK) at[BlockPos.asLong(s.x, s.y, s.z)] = i
        for ((i, s) in plan.steps.withIndex()) {
            if (s.kind != StepKind.BLOCK) continue
            val other = when (s.b.m) {
                M.DOOR -> BlockPos.asLong(s.x, if (s.b.part == 1) s.y - 1 else s.y + 1, s.z)
                M.BED -> {
                    val d = CityStyle.direction(s.b.dir ?: Dir.N)
                    val sign = if (s.b.part == 1) -1 else 1
                    BlockPos.asLong(s.x + d.stepX * sign, s.y, s.z + d.stepZ * sign)
                }
                else -> continue
            }
            at[other]?.let { j -> if (plan.steps[j].b.m == s.b.m) partner[i] = j }
        }
        // Each pole's lowest step, putting it up and taking it down
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

    val total: Int get() = plan.steps.size
    val finished: Boolean get() = build.step >= plan.steps.size

    /** Steps laid so far. */
    val laid: Int get() = build.step + build.done.cardinality()

    fun isDone(i: Int): Boolean = i < build.step || build.done.get(i)

    /** The stage being worked on: the first one with a step still to lay. */
    val stage: Stage get() = plan.stageOf(build.step)

    private val stageEnd: Int get() = plan.stageStarts[stage.ordinal + 1]

    fun step(i: Int): Step = plan.steps[i]

    /** Where step [i] is in the world. */
    fun at(i: Int): BlockPos {
        val s = plan.steps[i]
        return frame.world(s.x, s.y, s.z)
    }

    /** How much of each paid material is still to be used, in units ([Stuff.PAID] order). */
    fun left(): IntArray {
        val n = IntArray(Stuff.PAID.size)
        val counts = IntArray(Stuff.PAID.size)
        for (i in build.step until plan.steps.size) {
            if (isDone(i)) continue
            val s = plan.steps[i].stuff
            if (s != Stuff.FREE) counts[s.ordinal]++
        }
        for (s in n.indices) n[s] = max(0, Math.ceil(counts[s] * per[s] - build.credit[s] - 1e-6).toInt())
        return n
    }

    // ---- The work clock ----

    /**
     * Winds the work clock on to now: each tick of daylight since it was last wound (every tick, with the time of day
     * standing still) lets the crew lay a little more, up to what's left and what the crate pays for.
     */
    fun wind() {
        val now = level.gameTime
        val day = level.overworldClockTime
        if (build.clock < 0 || now < build.clock) {
            build.clock = now
            build.dayTime = day
            return
        }
        val worked = if (!level.gameRules.get(GameRules.ADVANCE_TIME)) now - build.clock else daylight(build.dayTime, day)
        build.clock = now
        build.dayTime = day
        if (worked <= 0) return
        val left = (total - laid).toDouble()
        build.work = min(build.work + worked * rate(), min(left, build.reach.toDouble()))
    }

    /** The crew working ten times their pace (/guildmark homestead rush). */
    var rushed = false

    /** Steps a tick of work lays. */
    fun rate(): Double = total / (tier.days * HomesteadTiers.WORKDAY / speed()) * if (rushed) 10.0 else 1.0

    /** Whether it's working hours: the crew comes at sunrise and goes home at sunset (always, if the sun stands still). */
    fun workday(): Boolean =
        !level.gameRules.get(GameRules.ADVANCE_TIME) || Math.floorMod(level.overworldClockTime, 24000L) < HomesteadTiers.WORKDAY - 300

    // ---- Materials ----

    private fun crate(): GoodsCrateBlockEntity? = level.getBlockEntity(homestead.crate) as? GoodsCrateBlockEntity

    /**
     * Looks in the supply crate and works out how far what's in it goes: how many of the steps still to lay, in order,
     * it pays for before something runs out ([short] says what).
     */
    fun survey() {
        val crate = crate() ?: return
        val stock = Materials.count(crate)
        val have = DoubleArray(Stuff.PAID.size) { stock[it] + build.credit[it] }
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
            Homesteads.get(level).changed()
        }
        // Nothing to do until something comes: the clock can't save up meanwhile
        if (build.work > reach) build.work = reach.toDouble()
    }

    /** Takes what step [i] is made of out of the crate (or the last of what was taken); false if there's none. */
    private fun charge(stuff: Stuff): Boolean {
        if (stuff == Stuff.FREE) return true
        val s = stuff.ordinal
        val credit = build.credit
        credit[s] -= per[s]
        while (credit[s] < -1e-9) {
            val crate = crate()
            val units = if (crate == null) 0 else Materials.take(crate, stuff)
            if (units == 0) {
                credit[s] += per[s]
                short = stuff
                return false
            }
            credit[s] += units.toDouble()
        }
        return true
    }

    private fun refund(stuff: Stuff) {
        if (stuff != Stuff.FREE) build.credit[stuff.ordinal] += per[stuff.ordinal]
    }

    // ---- Handing out steps ----

    /** Whether step [i] can be taken in hand now: in the stage being worked, not done, not someone else's. */
    fun open(i: Int): Boolean {
        if (i < build.step || i >= stageEnd || isDone(i) || claims.containsKey(i)) return false
        if (i == blockedStep && blocked != null) return false
        val since = waiting[i]
        return since == null || level.gameTime - since > 40
    }

    /**
     * Up to [most] steps for builder [who] to lay together: the first open one (away from where the others are working,
     * if there's a choice) and the open ones near it, in order.
     */
    fun claim(who: Int, others: List<BlockPos>, most: Int = 6): IntArray {
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

    /** Lets go of whatever [who] had in hand. */
    fun release(who: Int) {
        claims.entries.removeIf { it.value == who }
    }

    fun release(who: Int, i: Int) {
        if (claims[i] == who) claims.remove(i)
    }

    /** Where the others are working (the first step each has in hand). */
    fun workingAt(except: Int): List<BlockPos> {
        val seen = HashMap<Int, Int>()
        for ((i, who) in claims) if (who != except) seen.merge(who, i, ::minOf)
        return seen.values.map(::at)
    }

    // ---- Laying ----

    /**
     * Lays step [i] if the clock and the crate allow: [fx] shows it going in (dust and its sound). [free] lays it
     * whatever the clock and the crate say (the test commands).
     */
    fun lay(i: Int, fx: Boolean, free: Boolean = false): Laid {
        if (isDone(i)) return Laid.DONE
        if (!free && build.work < 1.0) return Laid.LATER
        val step = plan.steps[i]
        if (!free && !charge(step.stuff)) return Laid.NEED
        val outcome = place(i, step, fx)
        if (outcome != Laid.DONE) {
            if (!free) refund(step.stuff)
            return outcome
        }
        done(i, free)
        // A bed's other half, a door's: in with it
        val other = partner[i]
        if (other >= 0 && !isDone(other)) {
            val o = plan.steps[other]
            if (free || charge(o.stuff)) {
                if (place(other, o, fx) == Laid.DONE) done(other, true) else if (!free) refund(o.stuff)
            }
        }
        return Laid.DONE
    }

    private fun done(i: Int, free: Boolean) {
        claims.remove(i)
        waiting.remove(i)
        if (i == blockedStep) {
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
        Homesteads.get(level).changed()
    }

    /** Skips step [i] without laying it (the ledger's "build round it", when something can't be moved). */
    fun skip(i: Int) {
        if (!isDone(i)) done(i, true)
    }

    /** The step something unmovable is in the way of, if any. */
    val blockedAt: Int get() = if (blocked != null) blockedStep else -1

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
                Laid.DONE
            }
            StepKind.STAKE -> {
                // A stake is only knocked in where there's room: it never waits on anything
                val post = state(step.fill ?: B(M.FENCE), pos)
                val flag = state(step.b, pos.above())
                if (roomy(pos) && roomy(pos.above())) {
                    set(pos, post)
                    set(pos.above(), flag)
                    if (fx) {
                        sound(pos, HomesteadRegistry.STAKE.get(), 0.9f)
                        dust(pos, post, 12)
                    }
                }
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
                    // The foot of a pole goes down to whatever it can stand on
                    if (poleBase.get(i)) {
                        var below = pos.below()
                        var n = 0
                        while (n++ < 12 && below.y > level.minY && roomy(below) && !level.getBlockState(below).`is`(Blocks.SCAFFOLDING)) {
                            set(below, SCAFFOLD)
                            below = below.below()
                        }
                    }
                    set(pos, SCAFFOLD)
                    if (fx) {
                        sound(pos, SoundEvents.SCAFFOLDING_PLACE, 0.8f)
                        if (level.random.nextInt(3) == 0) sound(pos, HomesteadRegistry.SCAFFOLD.get(), 0.6f)
                    }
                }
                Laid.DONE
            }
            StepKind.UNSCAFFOLD -> {
                if (level.getBlockState(pos).`is`(Blocks.SCAFFOLDING)) {
                    if (fx) level.levelEvent(2001, pos, Block.getId(SCAFFOLD))
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                    // Last of a pole: its foot too, where it was carried down below the plan's ground
                    if (poleBase.get(i)) {
                        var below = pos.below()
                        var n = 0
                        while (n++ < 12 && level.getBlockState(below).`is`(Blocks.SCAFFOLDING)) {
                            level.setBlock(below, Blocks.AIR.defaultBlockState(), FLAGS)
                            below = below.below()
                        }
                    }
                    if (fx && level.random.nextInt(3) == 0) sound(pos, HomesteadRegistry.SCAFFOLD.get(), 0.6f)
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
            StepKind.SHIPPING -> {
                val crate = HomesteadRegistry.SHIPPING_CRATE.get().defaultBlockState()
                    .setValue(GoodsCrateBlock.FACING, frame.direction(Dir.N))
                if (level.getBlockState(pos).`is`(HomesteadRegistry.SHIPPING_CRATE.get())) return Laid.DONE
                val clear = clearFor(i, pos, crate)
                if (clear != Laid.DONE) return clear
                set(pos, crate)
                if (fx) {
                    dust(pos, crate, 14)
                    sound(pos, SoundEvents.WOOD_PLACE, 1f)
                }
                Laid.DONE
            }
            StepKind.SPAWN -> {
                spawn(i, step, pos, fx)
                Laid.DONE
            }
        }
    }

    /** One block of the plan. */
    private fun block(i: Int, step: Step, pos: BlockPos, fx: Boolean): Laid {
        val want = state(step.b, pos)
        val here = level.getBlockState(pos)
        if (here == want) return Laid.DONE
        if (step.b.m == M.AIR) {
            if (here.isAir) return Laid.DONE
            val clear = clearFor(i, pos, want)
            if (clear != Laid.DONE) return clear
            if (!level.getBlockState(pos).isAir) {
                if (fx) level.levelEvent(2001, pos, Block.getId(level.getBlockState(pos)))
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
            }
            return Laid.DONE
        }
        val clear = clearFor(i, pos, want)
        if (clear != Laid.DONE) return clear
        set(pos, want)
        joinUp(pos)
        if (fx) {
            dust(pos, want, 10)
            val sound = want.soundType
            level.playSound(null, pos, sound.placeSound, SoundSource.BLOCKS, (sound.volume + 1f) / 2f, sound.pitch * 0.8f)
        }
        return Laid.DONE
    }

    /**
     * Makes room at [pos] for [want]: wild growth and the land itself give way, someone's simple block is knocked out
     * as an item, anything that holds things holds the step up. Whoever stands where a solid block goes is waited for,
     * and after a while lifted onto it.
     */
    private fun clearFor(i: Int, pos: BlockPos, want: BlockState): Laid {
        val here = level.getBlockState(pos)
        if (!here.isAir && !wild(here)) {
            if (here.hasBlockEntity() || here.`is`(HomesteadRegistry.CHARTER_POST.get()) || here.`is`(HomesteadRegistry.SUPPLY_CRATE.get())) {
                blocked = pos
                blockedStep = i
                return Laid.BLOCKED
            }
            level.destroyBlock(pos, true)
        }
        if (!want.getCollisionShape(level, pos).isEmpty) {
            val box = AABB(pos)
            val inside = level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && !it.isSpectator }
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

    /** A column of ground levelled to stand at the step's height: what grew above cleared, the dip under it filled. */
    private fun ground(i: Int, step: Step, pos: BlockPos, fx: Boolean): Laid {
        val top = min(level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x, pos.z), pos.y + CLEAR_ABOVE)
        val cursor = BlockPos.MutableBlockPos(pos.x, pos.y - 1, pos.z)
        // Nothing of anyone's that holds things comes away with the ground
        while (cursor.y < top) {
            val state = level.getBlockState(cursor)
            if (!state.isAir && !wild(state) && (state.hasBlockEntity() || state.`is`(HomesteadRegistry.CHARTER_POST.get()))) {
                blocked = cursor.immutable()
                blockedStep = i
                return Laid.BLOCKED
            }
            cursor.move(Direction.UP)
        }
        cursor.set(pos.x, pos.y, pos.z)
        while (cursor.y < top) {
            val state = level.getBlockState(cursor)
            if (!state.isAir) {
                if (wild(state)) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS) else level.destroyBlock(cursor, true)
            }
            cursor.move(Direction.UP)
        }
        val surface = state(step.b, pos)
        val under = pos.below()
        val was = level.getBlockState(under)
        if (was != surface) {
            if (!was.isAir && !wild(was)) level.destroyBlock(under, true)
            set(under, surface)
        }
        val fill = style.state(step.fill ?: B(M.SOIL), 0)
        cursor.set(pos.x, pos.y - 2, pos.z)
        var depth = 0
        while (depth++ < MAX_FILL && cursor.y > level.minY && WorldCanvas.loose(level.getBlockState(cursor))) {
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
        if (!wild(land)) return
        val naturalTop = land.`is`(BlockTags.SUBSTRATE_OVERWORLD) || land.`is`(BlockTags.SAND) || land.`is`(Blocks.SNOW_BLOCK)
        val top = if (naturalTop) land.trySetValue(BlockStateProperties.SNOWY, false) else style.palette.ground.defaultBlockState()
        val landY = cursor.y
        // Cut down to the bank's height
        cursor.set(pos.x, pos.y, pos.z)
        val clearTo = min(surfaceTop, pos.y + CLEAR_ABOVE)
        while (cursor.y < clearTo) {
            val state = level.getBlockState(cursor)
            if (!state.isAir && wild(state)) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS)
            cursor.move(Direction.UP)
        }
        // Built up to it
        val subsoil = style.palette.subsoil.defaultBlockState()
        for (y in landY + 1 until pos.y - 1) {
            cursor.set(pos.x, y, pos.z)
            if (WorldCanvas.loose(level.getBlockState(cursor))) set(cursor.immutable(), subsoil)
        }
        val under = pos.below()
        val was = level.getBlockState(under)
        if (was.isAir || wild(was)) set(under, top)
        if (fx && level.random.nextInt(2) == 0) dust(under, top, 5)
    }

    private fun spawn(i: Int, step: Step, pos: BlockPos, fx: Boolean) {
        val tag = "guildmark.homestead.${homestead.id}.$i"
        val box = AABB(pos).inflate(48.0)
        if (level.getEntitiesOfClass(Mob::class.java, box) { tag in it.entityTags() }.isNotEmpty()) return
        val what = step.spawn ?: return
        val mob: Mob = when (what) {
            Spawn.HORSE -> EntityTypes.HORSE.create(level, EntitySpawnReason.EVENT)
            Spawn.CAT -> EntityTypes.CAT.create(level, EntitySpawnReason.EVENT)
            Spawn.COW -> EntityTypes.COW.create(level, EntitySpawnReason.EVENT)
            Spawn.SHEEP -> EntityTypes.SHEEP.create(level, EntitySpawnReason.EVENT)
            Spawn.PIG -> EntityTypes.PIG.create(level, EntitySpawnReason.EVENT)
            Spawn.CHICKEN -> EntityTypes.CHICKEN.create(level, EntitySpawnReason.EVENT)
            else -> null
        } ?: return
        val yaw = frame.direction(step.facing).toYRot()
        mob.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, yaw, 0f)
        mob.yHeadRot = yaw
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null)
        mob.setPersistenceRequired()
        mob.addTag(tag)
        mob.addTag(HomesteadProtection.STOCK_TAG)
        level.addFreshEntity(mob)
        if (fx) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x + 0.5, pos.y + 0.8, pos.z + 0.5, 8, 0.4, 0.4, 0.4, 0.0)
    }

    // ---- Small helpers ----

    /** The block step [i] puts in (what a builder carries for it). */
    fun stateFor(i: Int): BlockState {
        val step = plan.steps[i]
        val pos = at(i)
        return when (step.kind) {
            StepKind.SCAFFOLD, StepKind.UNSCAFFOLD -> SCAFFOLD
            StepKind.SHIPPING -> HomesteadRegistry.SHIPPING_CRATE.get().defaultBlockState()
            StepKind.STAKE -> state(step.fill ?: B(M.FENCE), pos)
            StepKind.GROUND -> state(step.b, pos)
            else -> state(step.b, pos)
        }
    }

    private fun state(b: B, pos: BlockPos): BlockState = style.state(b, hash(pos)).rotate(frame.rotation)

    private fun set(pos: BlockPos, state: BlockState) {
        level.setBlock(pos, state, FLAGS)
    }

    /** Fences, walls, panes and stairs join up with what's round them once laid, and what's round them with them. */
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

    /** Room for a stake or a pole: nothing there but air or what grows wild and soft. */
    private fun roomy(pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.isAir || state.canBeReplaced() && state.fluidState.isEmpty
    }

    private fun dust(pos: BlockPos, state: BlockState, n: Int) {
        if (state.isAir) return
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, state), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, n, 0.3, 0.3, 0.3, 0.08)
    }

    private fun sound(pos: BlockPos, event: net.minecraft.sounds.SoundEvent, volume: Float) {
        level.playSound(null, pos, event, SoundSource.BLOCKS, volume, 0.9f + level.random.nextFloat() * 0.2f)
    }

    /** A rocket from [at] in the owner's colour and gold. */
    fun firework(at: Vec3, big: Boolean) {
        val dye = net.minecraft.world.item.DyeColor.byId(homestead.color)
        val colors = IntList.of(dye.fireworkColor, 0xE8C26A)
        val shape = if (big) FireworkExplosion.Shape.LARGE_BALL else FireworkExplosion.Shape.STAR
        val stack = ItemStack(Items.FIREWORK_ROCKET)
        stack.set(DataComponents.FIREWORKS, Fireworks(1 + level.random.nextInt(2), listOf(FireworkExplosion(shape, colors, IntList.of(0xFFFFFF), true, big))))
        level.addFreshEntity(FireworkRocketEntity(level, at.x, at.y, at.z, stack))
    }

    companion object {
        private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

        /** How far over a levelled column the growth is cleared. */
        private const val CLEAR_ABOVE = 24

        /** Deepest a dip under a levelled column is filled; deeper is a cave, left alone. */
        private const val MAX_FILL = 40

        private val SCAFFOLD: BlockState = Blocks.SCAFFOLDING.defaultBlockState()
            .setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, false)

        /** The work clock's pace (GuildmarkConfig homesteadBuildSpeed): 2 builds in half the time. */
        fun speed(): Double = try {
            GuildmarkConfig.HOMESTEAD_BUILD_SPEED.get().coerceIn(0.05, 100.0)
        } catch (e: IllegalStateException) {
            1.0
        }

        /** What the builders may clear or build over: the wild as it grew, and their own scaffolding. */
        fun wild(state: BlockState): Boolean = TrollCamp.wild(state) || state.`is`(Blocks.SCAFFOLDING) || WorldCanvas.loose(state) && !state.hasBlockEntity()

        private fun hash(pos: BlockPos): Int = (Mth.getSeed(pos.x, pos.y, pos.z) ushr 8).toInt()

        /** Ticks of daylight (the first [HomesteadTiers.WORKDAY] of each day) between two times of day. */
        fun daylight(from: Long, to: Long): Long = max(0L, upTo(to) - upTo(from))

        private fun upTo(t: Long): Long = Math.floorDiv(t, 24000L) * HomesteadTiers.WORKDAY + min(Math.floorMod(t, 24000L), HomesteadTiers.WORKDAY.toLong())
    }
}
