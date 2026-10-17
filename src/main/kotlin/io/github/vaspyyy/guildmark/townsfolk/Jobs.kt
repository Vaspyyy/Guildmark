package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.Vec3

/**
 * What each townsperson does next: their trade during work hours, errands and chats in free time, the tavern in
 * the evening, then home to bed. Places they need (the anvil, the oven, a bench, a bed) are found near where
 * they live and work, and remembered.
 */
object Jobs {
    /** Walking speeds (times their base speed). */
    const val WALK = 0.5
    const val STROLL = 0.38
    const val BRISK = 0.6
    const val RUN = 0.75

    fun next(p: Townsperson, phase: Phase): Task {
        if (p.watcher) return Watch(p)
        if (p.crowd) return Visitors.next(p) ?: Leisure.next(p, crowd = true)
        // The factions' own: the leaders at their post, the members at the faction's work in its seat
        FactionWork.next(p, phase)?.let { return it }
        if (Schedule.sheltering(p, phase)) return shelter(p)
        return when (phase) {
            Phase.WAKE -> wake(p)
            Phase.WORK -> work(p) ?: Leisure.next(p)
            Phase.FREE -> Leisure.next(p)
            Phase.EVENING -> evening(p)
            Phase.HOME -> goHome(p)
            Phase.SLEEP -> sleep(p)
        }
    }

    private fun level(p: Townsperson): ServerLevel = p.level() as ServerLevel

    /** The middle of the block at [pos], [up] blocks above its bottom. */
    fun top(pos: BlockPos, up: Double = 1.0): Vec3 = Vec3(pos.x + 0.5, pos.y + up, pos.z + 0.5)

    fun home(p: Townsperson): BlockPos = p.home ?: p.blockPosition().also { p.home = it }

    /** Where they work from: their station, else their post, else home. */
    private fun base(p: Townsperson): BlockPos = p.work ?: p.post ?: home(p)

    private fun r(p: Townsperson, n: Int): Int = p.random.nextInt(n)

    // --- Work -----------------------------------------------------------------------------------------------

    /** A bout of [p]'s trade at its place, or null if they've none or it can't be found. */
    fun work(p: Townsperson): Task? = when (p.role) {
        TownRole.SMITH -> smith(p)
        TownRole.BAKER -> baker(p)
        TownRole.MERCHANT -> merchant(p)
        TownRole.GUARD -> guard(p)
        TownRole.FARMER -> farmer(p)
        TownRole.FISHER -> fisher(p)
        TownRole.CHILD -> Leisure.child(p)
        TownRole.NOBLE -> Leisure.stroll(p)
        TownRole.INNKEEPER -> innkeeper(p)
        TownRole.BARD -> bard(p, evening = false)
        TownRole.PRIEST -> priest(p)
        TownRole.CITIZEN -> craft(p)
        // Away from their seat, faction members have no trade to keep (FactionWork has them at it there)
        TownRole.MAGE, TownRole.KNIGHT, TownRole.THIEF -> null
    }

    /** The block they work at: [Townsperson.work] if it still is one, else the nearest such block to home. */
    private fun station(p: Townsperson, key: String, r: Int, test: (BlockState) -> Boolean): BlockPos? {
        val level = level(p)
        p.work?.let { if (level.isLoaded(it) && test(level.getBlockState(it))) return it }
        return p.routine.recall(key, { test(level.getBlockState(it)) }) {
            Places.nearest(level, base(p), r, 4, p) { state, _ -> test(state) }
        }
    }

    /** A block of [test] within [r] of [near], remembered as [key]. */
    private fun nearby(p: Townsperson, key: String, near: BlockPos, r: Int, test: (BlockState, BlockPos) -> Boolean): BlockPos? {
        val level = level(p)
        return p.routine.recall(key, { test(level.getBlockState(it), it) }) {
            Places.nearest(level, near, r, 2, p, test)
        }
    }

    /** Where to stand at [station]: the post if they have one there, else a free side of it. */
    private fun spotAt(p: Townsperson, station: BlockPos): Spot? {
        val post = p.post
        if (post != null && post.distManhattan(station) <= 2) return Spot(post, p.postFacing ?: Direction.fromYRot(p.yawTo(top(station)).toDouble()))
        return Places.beside(level(p), station, p.blockPosition())
    }

    private fun smith(p: Townsperson): Task? {
        val level = level(p)
        val anvil = station(p, "anvil", 16) { it.`is`(BlockTags.ANVIL) } ?: return null
        val spot = spotAt(p, anvil) ?: return null
        val strikes = 6 + r(p, 7)
        val hammering = Work(p, spot, top(anvil, 0.95), listOf(
            Step(Activity.HAMMER, 24 * strikes, line = if (r(p, 3) == 0) "smith_${r(p, 3)}" else null),
            Step(Activity.NONE, 20 + r(p, 30)),
        ), anvil)
        when (r(p, 10)) {
            in 0..4 -> nearby(p, "cauldron", anvil, 6) { state, _ -> state.`is`(Blocks.WATER_CAULDRON) }?.let { cauldron ->
                Places.beside(level, cauldron, spot.pos)?.let { at ->
                    hammering.andThen(Work(p, at, top(cauldron, 0.6), listOf(Step(Activity.QUENCH), Step(Activity.NONE, 20)), cauldron))
                }
            }
            in 5..7 -> nearby(p, "forge", anvil, 8) { state, pos -> forge(state) || level.getFluidState(pos).`is`(FluidTags.LAVA) }?.let { forge ->
                Places.beside(level, forge, spot.pos)?.let { at ->
                    hammering.andThen(Work(p, at, top(forge, 0.5), listOf(Step(Activity.TEND, 40 * (2 + r(p, 3)))), forge))
                }
            }
        }
        return hammering
    }

    private fun forge(state: BlockState): Boolean =
        state.`is`(Blocks.BLAST_FURNACE) || state.`is`(Blocks.FURNACE) || state.`is`(BlockTags.CAMPFIRES)

    fun oven(state: BlockState): Boolean = state.`is`(Blocks.SMOKER) || state.`is`(Blocks.FURNACE)

    /** A worktop: a crafting table, a barrel, a raised slab, or a plank block with nothing on it, at hip height. */
    private fun counter(level: ServerLevel, state: BlockState, pos: BlockPos, floorY: Int): Boolean {
        if (pos.y != floorY || !Places.free(level, pos.above())) return false
        return state.`is`(Blocks.CRAFTING_TABLE) || state.`is`(Blocks.BARREL) ||
            state.block is SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.TOP ||
            state.`is`(BlockTags.PLANKS)
    }

    private fun baker(p: Townsperson): Task? {
        val level = level(p)
        val oven = station(p, "oven", 16) { oven(it) } ?: return null
        val ovenSpot = Places.beside(level, oven, p.blockPosition()) ?: return null
        val baking = Work(p, ovenSpot, top(oven, 0.5), listOf(
            Step(Activity.OVEN, line = if (r(p, 3) == 0) "baker_${r(p, 3)}" else null),
            Step(Activity.NONE, 30 + r(p, 40)),
        ), oven)
        val counter = nearby(p, "counter", oven, 6) { state, pos -> pos != oven && counter(level, state, pos, ovenSpot.pos.y) }
        val kneadSpot = counter?.let { Places.beside(level, it, ovenSpot.pos) }
        if (counter == null || kneadSpot == null) return baking
        return Work(p, kneadSpot, top(counter, 0.95), listOf(Step(Activity.KNEAD, 30 * (4 + r(p, 5)))), counter).andThen(baking)
    }

    private fun merchant(p: Townsperson): Task? {
        val level = level(p)
        val spot = p.post?.let { Spot(it, p.postFacing ?: Direction.NORTH) }
            ?: station(p, "stall", 12) { it.`is`(Blocks.BARREL) }?.let { Places.beside(level, it, p.blockPosition()) }
            ?: return null
        val counter = top(spot.pos.relative(spot.facing), 1.0)
        return Work(p, spot, counter, { i ->
            val near = level.getNearestPlayer(counter.x, counter.y, counter.z, 10.0, false)
            when {
                i >= 6 -> null
                near != null && near.distanceToSqr(counter) < 36.0 && r(p, 3) == 0 -> Step(Activity.BECKON)
                near != null && r(p, 2) == 0 -> Step(Activity.CALL_OUT, line = "merchant_${r(p, 6)}")
                r(p, 3) == 0 -> Step(Activity.NONE, 60 + r(p, 80))
                else -> Step(Activity.ARRANGE, 60 * (1 + r(p, 2)))
            }
        })
    }

    private fun innkeeper(p: Townsperson): Task? {
        val level = level(p)
        val spot = p.post?.let { Spot(it, p.postFacing ?: Direction.NORTH) } ?: Spot(home(p), Direction.fromYRot(p.yRot.toDouble()))
        val bar = top(spot.pos.relative(spot.facing), 1.0)
        return Work(p, spot, bar, { i ->
            val near = level.getNearestPlayer(bar.x, bar.y, bar.z, 6.0, false)
            when {
                i >= 8 -> null
                near != null && r(p, 2) == 0 -> Step(Activity.POUR, line = "inn_${r(p, 4)}")
                r(p, 6) == 0 -> Step(Activity.ARRANGE, 60, turned = true)
                r(p, 5) == 0 -> Step(Activity.POUR)
                else -> Step(Activity.WIPE, 40 * (2 + r(p, 3)))
            }
        })
    }

    private fun priest(p: Townsperson): Task? {
        val level = level(p)
        val lectern = station(p, "lectern", 24) { it.`is`(Blocks.LECTERN) }
        val spot = (lectern?.let { spotAt(p, it) }) ?: p.post?.let { Spot(it, p.postFacing ?: Direction.NORTH) } ?: return null
        val look = lectern?.let { top(it, 1.0) } ?: top(spot.pos.relative(spot.facing), 1.0)
        return Work(p, spot, look, listOf(
            Step(Activity.PRAY, 120 * (2 + r(p, 3))),
            Step(Activity.TALK, 60 * (2 + r(p, 2)), turned = true, line = "priest_${r(p, 3)}"),
            Step(Activity.NONE, 40, turned = true),
        ), lectern)
    }

    private fun farmer(p: Townsperson): Task? {
        val level = level(p)
        val composter = station(p, "composter", 16) { it.`is`(Blocks.COMPOSTER) } ?: return null
        val crops = Places.crops(level, composter, 10, ripe = false)
        if (crops.isEmpty() || r(p, 8) == 0) {
            val at = Places.beside(level, composter, p.blockPosition()) ?: return null
            return Work(p, at, top(composter, 0.9), listOf(Step(Activity.ARRANGE, 60 * (1 + r(p, 2)), line = if (r(p, 4) == 0) "farmer_${r(p, 3)}" else null)), composter)
        }
        val crop = crops[r(p, crops.size)]
        val at = Places.besideCrop(level, crop, p.blockPosition()) ?: return null
        val state = level.getBlockState(crop)
        val block = state.block as? CropBlock ?: return null
        if (block.isMaxAge(state)) {
            return Work(p, at, top(crop, 0.2), listOf(Step(Activity.HARVEST), Step(Activity.NONE, 20)), crop) { step, tick ->
                // The crop comes up on the pluck, and is set growing again
                if (step.activity == Activity.HARVEST && tick == 20) {
                    val now = level.getBlockState(crop)
                    if (now.block === block) {
                        level.setBlock(crop, block.getStateForAge(0), Block.UPDATE_ALL)
                        level.levelEvent(2001, crop, Block.getId(now))
                    }
                }
            }
        }
        return Work(p, at, top(crop, 0.1), listOf(Step(Activity.HOE, 32 * (3 + r(p, 3)), line = if (r(p, 5) == 0) "farmer_${r(p, 3)}" else null)), crop)
    }

    private fun fisher(p: Townsperson): Task? {
        val level = level(p)
        val spot = p.post?.let { Spot(it, p.postFacing ?: Direction.NORTH) } ?: p.routine.recall("bank", { Places.standable(level, it) }) {
            Places.bank(level, base(p), 24, p)?.pos
        }?.let { pos ->
            Direction.Plane.HORIZONTAL.firstOrNull { Places.water(level, pos.relative(it).below()) || Places.water(level, pos.relative(it)) }?.let { Spot(pos, it) }
        } ?: return null
        val water = Vec3(spot.pos.x + 0.5 + spot.facing.stepX * 3.0, spot.pos.y - 0.5, spot.pos.z + 0.5 + spot.facing.stepZ * 3.0)
        val waits = 3 + r(p, 5)
        return Work(p, spot, water, listOf(
            Step(Activity.CAST),
            Step(Activity.FISH, 80 * waits),
            Step(Activity.REEL, line = if (r(p, 3) == 0) "fisher_${r(p, 3)}" else null),
            Step(Activity.NONE, 30 + r(p, 40)),
        ))
    }

    /** A guard's watch: at their post a while, or a walk of their beat. */
    fun guard(p: Townsperson): Task {
        val post = p.post
        if (post != null && r(p, 2) == 0) {
            val facing = p.postFacing ?: Direction.NORTH
            return Work(p, Spot(post, facing), top(post.relative(facing, 4), 1.6), listOf(
                Step(Activity.STAND_GUARD, 120 * (3 + r(p, 4))),
            ))
        }
        return Patrol(p, beat(p))
    }

    /** A guard's beat: their own if set, else a few points round their post in the streets. */
    private fun beat(p: Townsperson): List<BlockPos> {
        if (p.patrol.isNotEmpty()) return p.patrol
        val level = level(p)
        val center = p.post ?: home(p)
        val points = ArrayList<BlockPos>()
        repeat(4) {
            Places.street(level, center, 32, p.random, minR = 8)?.takeIf { pt -> p.town?.contains(pt) != false }?.let { points.add(it) }
        }
        return points
    }

    private fun bard(p: Townsperson, evening: Boolean): Task? {
        val level = level(p)
        val square = p.town?.square
        val post = p.post
        if (!evening && post != null) {
            val facing = p.postFacing
            return Perform(p, post, if (facing != null) post.relative(facing, 4) else square ?: post)
        }
        val at = (if (evening) Social.tavern(p) ?: square else square ?: home(p)) ?: return null
        val spot = Places.street(level, at, 5, p.random, tries = 6)
            ?: Places.nearest(level, at, 3, 2) { _, pos -> Places.standable(level, pos) } ?: at
        return Perform(p, spot, at)
    }

    private fun craft(p: Townsperson): Task? {
        if (r(p, 2) == 0) return null
        val level = level(p)
        val bench = station(p, "bench", 12) {
            it.`is`(Blocks.LOOM) || it.`is`(Blocks.FLETCHING_TABLE) || it.`is`(Blocks.CARTOGRAPHY_TABLE) ||
                it.`is`(Blocks.STONECUTTER) || it.`is`(Blocks.CRAFTING_TABLE)
        } ?: return null
        val spot = spotAt(p, bench) ?: return null
        return Work(p, spot, top(bench, 0.95), listOf(Step(Activity.ARRANGE, 60 * (3 + r(p, 4))), Step(Activity.NONE, 40)), bench)
    }

    // --- The rest of the day ------------------------------------------------------------------------------

    private fun wake(p: Townsperson): Task {
        val home = home(p)
        if (p.distanceToSqr(Vec3.atBottomCenterOf(home)) > 64.0) return Walk(p, home, WALK, near = 3.0)
        if (p.role.adult && r(p, 3) == 0) Leisure.sweep(p)?.let { return it }
        return Idle(p, 100 + r(p, 200))
    }

    /** An evening out: the tavern, or the square where there's none. */
    fun evening(p: Townsperson): Task {
        if (p.role == TownRole.BARD) bard(p, evening = true)?.let { return it }
        if (!p.role.adult) return goHome(p)
        val level = level(p)
        val tavern = Social.tavern(p)
        if (tavern != null) {
            if (p.distanceToSqr(Vec3.atBottomCenterOf(tavern)) > 100.0) return Walk(p, tavern, BRISK, near = 4.0)
            val seat = if (r(p, 3) != 0) Places.seat(level, tavern, 9, p) else null
            if (seat != null) return Sit(p, seat, 500 + r(p, 800), listOf(Activity.SIT, Activity.SIT_DRINK, Activity.SIT_DRINK, Activity.SIT_TALK))
            return Mingle(p, 400 + r(p, 500), listOf(Activity.DRINK, Activity.DRINK, Activity.TALK, Activity.LISTEN, Activity.LAUGH, Activity.CHEER), tavern = true)
        }
        // No tavern: the square, the benches round it, the neighbours
        val square = p.town?.square
        if (square != null && p.distanceToSqr(Vec3.atBottomCenterOf(square)) > 225.0) {
            Places.street(level, square, 8, p.random)?.let { return Walk(p, it, STROLL, overlay = Activity.STROLL) }
        }
        return Leisure.next(p)
    }

    private fun goHome(p: Townsperson): Task {
        val home = home(p)
        if (p.distanceToSqr(Vec3.atBottomCenterOf(home)) <= 9.0) return Idle(p, 100 + r(p, 100), chatty = false)
        // Some leave the tavern the worse for wear
        val tipsy = p.role.adult && p.routine.phase == Phase.HOME && Social.tavern(p)?.let { p.distanceToSqr(Vec3.atBottomCenterOf(it)) < 225.0 } == true && r(p, 4) == 0
        val overlay = when {
            tipsy -> Activity.STAGGER
            Schedule.dark(p) && p.role != TownRole.GUARD -> Activity.LANTERN
            else -> Activity.NONE
        }
        return Walk(p, home, if (tipsy) 0.36 else WALK, near = 1.5, overlay = overlay)
    }

    private fun sleep(p: Townsperson): Task {
        val level = level(p)
        val home = home(p)
        if (p.distanceToSqr(Vec3.atBottomCenterOf(home)) > 400.0) return goHome(p)
        val bed = p.routine.recall("bed", { pos ->
            val state = level.getBlockState(pos)
            state.`is`(BlockTags.BEDS) && Claims.free(level, pos, p)
        }) { Places.bed(level, home, p) }
        if (bed != null) return Sleep(p, bed)
        return Idle(p, 400, chatty = false)
    }

    private fun shelter(p: Townsperson): Task {
        val home = home(p)
        if (p.distanceToSqr(Vec3.atBottomCenterOf(home)) > 9.0) return Walk(p, home, BRISK, near = 1.5)
        return Idle(p, 200 + r(p, 200), chatty = false)
    }
}

/** Free time: errands across town, a sweep of the doorstep, a sit on a bench, a chat, a song in the square. */
object Leisure {
    fun next(p: Townsperson, crowd: Boolean = false): Task {
        val level = p.level() as ServerLevel
        Social.performingBard(p)?.let { if (p.random.nextInt(3) != 0) return Audience(p, it) }
        if (p.role == TownRole.CHILD) return child(p)
        if (p.role == TownRole.NOBLE && p.random.nextInt(3) != 0) return stroll(p)
        if (p.role == TownRole.GUARD) return Patrol(p, listOfNotNull(Places.street(level, p.blockPosition(), 28, p.random, minR = 10)))
        if (p.role == TownRole.BARD && crowd && p.random.nextInt(2) == 0) {
            Places.street(level, p.blockPosition(), 4, p.random)?.let { return Perform(p, it, p.blockPosition()) }
        }
        return when (p.random.nextInt(20)) {
            in 0..7 -> errand(p, crowd)
            in 8..9 -> if (crowd) errand(p, true) else sweep(p) ?: errand(p, false)
            in 10..12 -> Places.seat(level, p.blockPosition(), 12, p)?.let { Sit(p, it, 300 + p.random.nextInt(500), listOf(Activity.SIT)) } ?: errand(p, crowd)
            in 13..16 -> Social.chat(p) ?: Idle(p, 60 + p.random.nextInt(120))
            else -> Idle(p, 60 + p.random.nextInt(160))
        }
    }

    /** Off across town on some errand, maybe with a crate or a sack, then a look round when there. */
    fun errand(p: Townsperson, crowd: Boolean): Task {
        val level = p.level() as ServerLevel
        val town = p.town
        val target = Places.street(level, p.blockPosition(), 40, p.random, minR = 14)?.takeIf { town == null || town.contains(it) }
            ?: return Idle(p, 60 + p.random.nextInt(80))
        val overlay = when {
            Schedule.dark(p) -> Activity.LANTERN
            !p.role.adult || p.role == TownRole.NOBLE || p.role == TownRole.PRIEST -> Activity.NONE
            p.random.nextInt(4) == 0 -> Activity.CARRY
            p.random.nextInt(5) == 0 -> Activity.CARRY_SACK
            else -> Activity.NONE
        }
        val speed = if (overlay == Activity.CARRY || overlay == Activity.CARRY_SACK) 0.42 else Jobs.WALK
        return Walk(p, target, speed, near = 2.0, overlay = overlay).andThen(if (crowd && p.random.nextInt(2) == 0) null else Idle(p, 40 + p.random.nextInt(100)))
    }

    /** Sweeping the street outside home. */
    fun sweep(p: Townsperson): Task? {
        val level = p.level() as ServerLevel
        val home = Jobs.home(p)
        val ground = Places.street(level, home, 5, p.random, tries = 8) ?: return null
        val facing = Direction.Plane.HORIZONTAL.getRandomDirection(p.random)
        return Work(p, Spot(ground, facing), Jobs.top(ground.relative(facing), 0.0), listOf(Step(Activity.SWEEP, 30 * (4 + p.random.nextInt(5)))))
    }

    /** A noble's turn about town: a slow stroll, then a long look at the sights. */
    fun stroll(p: Townsperson): Task {
        val level = p.level() as ServerLevel
        val center = p.town?.square?.takeIf { p.random.nextInt(3) == 0 } ?: p.blockPosition()
        val target = Places.street(level, center, 30, p.random, minR = 8)?.takeIf { p.town?.contains(it) != false }
            ?: return Idle(p, 100, Activity.ADMIRE)
        return Walk(p, target, Jobs.STROLL, near = 2.0, overlay = Activity.STROLL)
            .andThen(if (p.random.nextInt(3) == 0) Social.chat(p) ?: Idle(p, 100 + p.random.nextInt(200), Activity.ADMIRE) else Idle(p, 100 + p.random.nextInt(200), Activity.ADMIRE))
    }

    /** Children at play: tag, hopping about, following an adventurer. */
    fun child(p: Townsperson): Task {
        val level = p.level() as ServerLevel
        Social.adventurer(p)?.let { if (p.random.nextInt(3) == 0) return Follow(p, it, 300 + p.random.nextInt(400)) }
        Social.playmate(p)?.let { if (p.random.nextInt(2) == 0) return Chase(p, it) }
        val center = p.town?.square?.takeIf { p.random.nextInt(3) == 0 } ?: Jobs.home(p)
        return Play(p, center)
    }
}

/** A guard walking their beat with the spear up, standing watch at each stop and saluting passing adventurers. */
class Patrol(p: Townsperson, private val points: List<BlockPos>) : Task(p) {
    private val walker = Walker(p)
    private var index = 0
    private var standing = 0
    private var saluted = 0

    override fun start() {
        if (points.isEmpty()) return
        index = points.indices.minBy { p.distanceToSqr(Vec3.atBottomCenterOf(points[it])) }
        walker.go(points[index], 0.45, near = 2.0)
        p.act(Activity.PATROL)
    }

    override fun run(): Boolean {
        if (points.isEmpty()) return false
        if (standing > 0) {
            standing--
            val passer = p.level().getNearestPlayer(p, 5.0)
            if (passer != null && age - saluted > 400) {
                saluted = age
                p.act(Activity.SALUTE)
                p.say("guard_${p.random.nextInt(5)}")
            }
            if (passer != null) p.lookAt(passer)
            if (p.activity == Activity.SALUTE && p.actDone) p.act(Activity.STAND_GUARD)
            if (standing == 0) {
                if (++index >= points.size) return false
                walker.go(points[index], 0.45, near = 2.0)
                p.act(Activity.PATROL)
            }
            return true
        }
        when (walker.tick()) {
            Walker.State.WALKING -> {}
            Walker.State.FAILED, Walker.State.ARRIVED -> {
                standing = 100 + p.random.nextInt(140)
                p.act(Activity.STAND_GUARD)
            }
        }
        return age < 6000
    }

    override fun stop() {
        walker.stop()
        p.act(Activity.NONE)
    }
}

/** A bard playing a song at [spot], facing [toward], then taking a bow. */
class Perform(p: Townsperson, private val spot: BlockPos, private val toward: BlockPos) : Task(p) {
    private val walker = Walker(p)
    private var playing = false
    private var song = 0
    private var bowAt = 0

    override val anchored: Boolean get() = playing

    override fun start() {
        p.act(Activity.NONE)
        walker.go(spot, Jobs.WALK, near = 1.0)
    }

    override fun run(): Boolean {
        if (!playing) {
            when (walker.tick()) {
                Walker.State.WALKING -> return true
                Walker.State.FAILED, Walker.State.ARRIVED -> {
                    playing = true
                    song = 600 + p.random.nextInt(300)
                    bowAt = age + song
                    if (toward != spot) p.face(p.yawTo(Vec3.atBottomCenterOf(toward)))
                    p.say("bard_0")
                    p.act(Activity.LUTE)
                }
            }
            return true
        }
        if (age == bowAt) {
            p.act(Activity.SALUTE)
            p.say("bard_1", force = true)
        }
        if (age < bowAt) p.level().getNearestPlayer(p, 10.0)?.let { if (age % 80 < 40) p.lookAt(it) }
        return age < bowAt + 80
    }

    override fun stop() {
        walker.stop()
        p.act(Activity.NONE)
    }
}

/** Standing about with others: drinking, talking, laughing (the tavern, or the square on a warm evening). */
class Mingle(p: Townsperson, private val ticks: Int, private val moods: List<Activity>, private val tavern: Boolean) : Task(p) {
    private var nextMood = 0

    override val leisure: Boolean get() = true

    override fun start() {
        p.navigation.stop()
    }

    override fun run(): Boolean {
        if (age >= nextMood && (p.activity.loop || p.actDone)) {
            val mood = moods[p.random.nextInt(moods.size)]
            p.act(mood)
            nextMood = age + if (mood.loop) mood.ticks * (1 + p.random.nextInt(3)) else mood.ticks
            when (mood) {
                Activity.CHEER -> p.say(if (tavern) "tavern_${listOf(0, 1, 2, 4)[p.random.nextInt(4)]}" else "reply_5")
                Activity.LAUGH -> p.say(if (tavern && p.random.nextBoolean()) "tavern_3" else "laugh_0")
                Activity.TALK -> if (p.random.nextInt(3) == 0) p.say(Lines.gossip(p))
                else -> {}
            }
        }
        val other = Social.nearestFolk(p, 4.0)
        if (other != null) {
            p.face(p.yawTo(other.position()))
            p.lookAt(other.eyePosition)
        }
        return age < ticks
    }

    override fun stop() {
        p.act(Activity.NONE)
    }
}

/** A child running about near [center], hopping, twirling and waving. */
class Play(p: Townsperson, private val center: BlockPos) : Task(p) {
    private val walker = Walker(p)
    private var legs = 3 + p.random.nextInt(3)
    private var pause = 0

    override val leisure: Boolean get() = true

    override fun start() {
        leg()
    }

    private fun leg() {
        val level = p.level() as ServerLevel
        val target = Places.street(level, center, 10, p.random, tries = 6) ?: Places.street(level, p.blockPosition(), 8, p.random, tries = 6)
        if (target == null) {
            legs = 0
            return
        }
        p.act(Activity.PLAY)
        walker.go(target, Jobs.RUN, near = 1.5)
    }

    override fun run(): Boolean {
        if (legs <= 0) return false
        if (pause > 0) {
            if (--pause == 0) {
                if (--legs <= 0) return false
                leg()
            }
            return true
        }
        when (walker.tick()) {
            Walker.State.WALKING -> {}
            Walker.State.FAILED, Walker.State.ARRIVED -> {
                p.act(listOf(Activity.JUMP, Activity.SPIN, Activity.WAVE, Activity.JUMP)[p.random.nextInt(4)])
                if (p.random.nextInt(4) == 0) p.say("child_${listOf(1, 3, 4, 5)[p.random.nextInt(4)]}")
                pause = p.activity.ticks + 10 + p.random.nextInt(30)
            }
        }
        return true
    }

    override fun stop() {
        walker.stop()
        p.act(Activity.NONE)
    }
}
