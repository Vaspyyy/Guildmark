package io.github.vaspyyy.guildmark.cooking.town

import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.vessel.SpitBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StationProps
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotMode
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenLines
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.MoverType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Why a kitchen scene ended: it was played out, or something sent the keeper back to their day. */
internal enum class Ending {
    /** Played to the end. */
    DONE,

    /** The keeper was spoken to (or a dish was held out to them): the scene hands them back and the usual chain answers. */
    SPOKEN_TO,

    /** The keeper was hurt. */
    HURT,

    /** Ninety seconds old. */
    TIMEOUT,

    /** No player within reach any more. */
    NO_PLAYER,

    /** Town Kitchens (or the roast) was switched off. */
    SWITCHED_OFF,

    /** The way was lost: a path that went nowhere, or the pot, spit or oven gone. */
    LOST,

    /** Something else had the keeper for more than a moment (they ran from a monster). */
    TAKEN,
}

/** What a scene works with, as [KitchenScenes] found it: the blocks, the bowls on the shelf now, and what the tasting says (0 nothing, 1 salt, 2 right). */
internal class Stage(val pot: BlockPos?, val spit: BlockPos?, val oven: BlockPos?, val counter: BlockPos?, val bowls: Int, val verdict: Int)

/** The roast of one rest day at one tavern: whether the cry has been made and the roast carved (shared by the scenes that day). */
internal class RoastDay {
    var day = Long.MIN_VALUE
    var cried = false
    var carved = false
}

/** Where the keeper stands to work at a block: the cell, the exact point in it (a hand's breadth toward the work) and the way to face. */
internal class Stand(val cell: BlockPos, val point: Vec3, val yaw: Float)

internal object Stands {
    /** How far toward the work, in the cell, the keeper stands (a body is 0.6 wide, so a full block's face is reached at 0.2). */
    private const val TOWARD = 0.12

    /** A stand beside the block at [work], facing it: the side [prefer] first, else the open side nearest [from]; null if no side is open. */
    fun at(level: ServerLevel, work: BlockPos, from: Vec3, prefer: Direction? = null): Stand? {
        var best: Direction? = null
        var bestKey = Double.MAX_VALUE
        for (side in Direction.Plane.HORIZONTAL) {
            val cell = work.relative(side)
            if (!open(level, cell)) continue
            val key = cell.distToCenterSqr(from.x, from.y, from.z) + if (side == prefer) -1000.0 else 0.0
            if (key < bestKey) {
                bestKey = key
                best = side
            }
        }
        return best?.let { facing(work, it) }
    }

    /** A stand on the first of [sides] of the block at [work] that is open, facing it; null if none is. */
    fun on(level: ServerLevel, work: BlockPos, sides: List<Direction>): Stand? =
        sides.firstOrNull { open(level, work.relative(it)) }?.let { facing(work, it) }

    /** The stand in the cell on [side] of [work], [TOWARD] of the way toward it and facing it. */
    private fun facing(work: BlockPos, side: Direction): Stand {
        val cell = work.relative(side)
        val toward = side.opposite
        return Stand(cell, Vec3(cell.x + 0.5 + toward.stepX * TOWARD, cell.y.toDouble(), cell.z + 0.5 + toward.stepZ * TOWARD), toward.toYRot())
    }

    /** Where someone stood already: back to the very spot, the way they faced. */
    fun back(origin: Vec3, yaw: Float): Stand = Stand(BlockPos.containing(origin), origin, yaw)

    /** Whether a body can stand in [cell] and the floor is not a hazard. */
    fun open(level: ServerLevel, cell: BlockPos): Boolean {
        if (!Places.standable(level, cell)) return false
        if (level.getBlockState(cell).`is`(BlockTags.FIRE)) return false
        val floor = level.getBlockState(cell.below())
        return !floor.`is`(Blocks.MAGMA_BLOCK) && !floor.`is`(Blocks.CACTUS) && !floor.`is`(BlockTags.CAMPFIRES)
    }
}

/**
 * What a keeper says in a kitchen scene (living-world/cook/design.md 7.3, 7.5, 11.5): a family of Part J's, said as a take
 * (or as the wording's own text, over the keeper's head) through [KitchenLines.say]; while that says nothing (it is a stub until J
 * lands) the scene's own wording of the same family, kept in the language file, so the scene reads in full whatever else is built.
 */
internal object SceneLines {
    private const val SAY = "say.guildmark."

    /** The scene's own wordings of each family, as complete language keys. */
    private val OWN: Map<String, List<String>> = mapOf(
        "kitchen.scene.salt" to listOf("say.guildmark.talk.kitchen.scene_own.salt_0", "say.guildmark.talk.kitchen.scene_own.salt_1"),
        "kitchen.scene.right" to listOf(
            "say.guildmark.talk.kitchen.scene_own.right_0", "say.guildmark.talk.kitchen.scene_own.right_1", "say.guildmark.talk.kitchen.scene_own.right_2",
        ),
        "kitchen.scene.hot" to listOf(
            "say.guildmark.talk.kitchen.scene_own.hot_0", "say.guildmark.talk.kitchen.scene_own.hot_1", "say.guildmark.talk.kitchen.scene_own.hot_2",
        ),
        "kitchen.scene.bread" to listOf(
            "say.guildmark.talk.kitchen.scene_own.bread_0", "say.guildmark.talk.kitchen.scene_own.bread_1", "say.guildmark.talk.kitchen.scene_own.bread_2",
        ),
        "kitchen.scene.roast" to listOf(
            "say.guildmark.talk.kitchen.scene_own.roast_0", "say.guildmark.talk.kitchen.scene_own.roast_1", "say.guildmark.talk.kitchen.scene_own.roast_2",
        ),
    )

    /** Has [p] say [family] now because of [why]; whether anyone was near enough to hear. */
    fun say(p: Townsperson, family: String, why: String): Boolean {
        if (KitchenLines.say(p, family, why, force = true)) return true
        val words = OWN[family] ?: return false
        return p.say(words[p.random.nextInt(words.size)].removePrefix(SAY), force = true)
    }
}

/**
 * A kitchen scene (living-world/cook/design.md 7.3, 7.5): a keeper taken off their day for a minute to cook, as a [Director]
 * in the way the lamplighter's round is. [kind] says which: the innkeeper at the house pot (walks to it, stirs two loops,
 * tastes, ladles two or three bowls onto its shelf, goes back), the baker with the peel at the Bake oven (and, in a town's
 * day, a loaf sliced at the counter after), the baker slicing alone, or the rest-day roast (the innkeeper turns the Spit, wipes
 * her brow and carves at noon). It works the pot's and the spit's own state ([StewpotBlockEntity.showHouse],
 * [SpitBlockEntity.turning]) and gives it back as it was whenever it ends.
 *
 * It ends at once, handing the keeper back to their day, when the keeper is spoken to (or a dish is held out to them: the
 * back door and the inn's order screen then answer in the same click, as [interact] returns false), hurt, taken by something
 * else, or ninety seconds old; when no player is within reach; when Town Kitchens is switched off; or when a block it works at
 * is gone. [ended] hears how it ended, once.
 *
 * The client plays the clips' own sounds, steam and crumbs from their moments, and opens the oven's door and turns the spit's
 * crank for a keeper in the right activity within three blocks and facing it (`TownKitchen`), so the scene stands the keeper
 * there; what the server adds is only what the client does not: the pot's swirl and shelf, the pour and the bowl's clink, the
 * roast on the spit (its fire, its joints) and its flare, the keeper's words.
 *
 * [forced] is a scene played by the command: it ignores the clock (the roast carves after two turns of the rod). [roast] is the
 * roast of the day, with the clock of its carving ([carveAt], an offset into the town's day) for a scene of the natural round;
 * [chain] has a baker who has drawn the tray slice a loaf at the counter after.
 */
internal class KitchenScene(
    val kind: KitchenSceneKind,
    private val stage: Stage,
    private val forced: Boolean,
    private val roast: RoastDay,
    private val carveAt: Int,
    private val chain: Boolean,
    private val ended: (KitchenScene, Ending) -> Unit,
) : Director {
    companion object {
        /** A scene is never older than this many ticks (90 s). */
        const val MAX_TICKS = 1800

        /** The most blocks a player can be from the keeper before the scene has no one to be played to. */
        private const val AWAY = 64.0

        /** The two loops of the stir. */
        private const val STIR_TICKS = 160

        /** A bowl's tick of the ladle clip at which it is set on the shelf (the clip hands the bowl out then), and at which the ladle pours. */
        private const val GIVE = 21
        private const val POUR = 13

        /** The roast's window ends here (13:00), as an offset into the town's day. */
        private val WINDOW_END = Days.clock(13.0)

        /** The slices the roast is carved into, one joint of the roast's each. */
        private const val SLICES = 3

        /** The tick of the carve clip at which the slice parts (its `carve` moment, 19.5), and the clip's length. */
        private const val CUT = 20
        private const val CARVE_TICKS = 50

        /** The farthest a scene walks a keeper from where it takes them (blocks); a stand farther than this is no scene. */
        private const val FAR = 22.0
    }

    // ---- What the scene is ----

    private var done = false
    private var age = 0
    private var lastTick = -1
    private var walker: Walker? = null
    private var origin = Vec3.ZERO
    private var originYaw = 0f
    private val plan = ArrayDeque<Step>()
    private var step: Step? = null
    private var failed = false

    /** The bowls on the house pot's shelf as this scene leaves them (the shelf it found, and what it ladled). */
    var bowls = stage.bowls
        private set

    /** Whether the roast was carved by this scene. */
    var carvedHere = false
        private set

    /** The game tick this scene began, for whoever keeps its cool-down. */
    var began = 0L
        private set

    /** The keeper's id, so the table of running scenes can be told. */
    var keeper: java.util.UUID? = null
        private set

    // ---- Beginning ----

    /**
     * Takes [p] off their day for this scene, as the lamplighter's round takes its keeper: false (and nothing changed) if there
     * is no way to stand where the scene needs.
     */
    fun start(level: ServerLevel, p: Townsperson): Boolean {
        origin = p.position()
        originYaw = p.yRot
        if (!build(level, p)) return false
        walker = Walker(p)
        keeper = p.uuid
        began = level.gameTime
        p.routine.interrupt()
        p.navigation.stop()
        p.act(Activity.NONE)
        p.director = this
        return true
    }

    private fun build(level: ServerLevel, p: Townsperson): Boolean {
        val here = p.position()
        val home = Stands.back(origin, originYaw)
        when (kind) {
            KitchenSceneKind.TAVERN -> {
                val pos = stage.pot ?: return false
                val stand = Stands.at(level, pos, here)?.takeIf { near(it) } ?: return false
                val look = Vec3.atCenterOf(pos).add(0.0, 0.3, 0.0)
                plan.add(Go(stand, Jobs.BRISK))
                plan.add(Do(Activity.STIR, STIR_TICKS, stand.yaw, look) { t, lv, _ -> if (t == 0) swirl(lv, STIR_TICKS + 6) })
                plan.add(Run { lv, _ -> swirl(lv, 6) })
                plan.add(Do(Activity.TASTE, 40, stand.yaw, look) { t, _, who -> if (t == 32) verdict(who) })
                val n = min(2 + p.random.nextInt(2), 8 - bowls).coerceAtLeast(1)
                for (i in 0 until n) {
                    plan.add(Do(Activity.LADLE, 30, stand.yaw, look) { t, lv, _ -> ladle(lv, pos, t) })
                    if (i < n - 1) plan.add(Do(Activity.NONE, 6, stand.yaw, look))
                }
                plan.add(Run { _, who -> if (who.random.nextInt(4) == 0) SceneLines.say(who, "kitchen.scene.hot", "pot") })
                plan.add(Go(home, Jobs.WALK))
            }
            KitchenSceneKind.PEEL -> {
                val pos = stage.oven ?: return false
                val facing = level.getBlockState(pos).let { if (it.hasProperty(StationProps.FACING)) it.getValue(StationProps.FACING) else null }
                val stand = Stands.at(level, pos, here, facing)?.takeIf { near(it) } ?: return false
                val look = Vec3.atCenterOf(pos)
                plan.add(Go(stand, Jobs.BRISK))
                plan.add(Do(Activity.PEEL, 50, stand.yaw, look) { t, _, who -> if (t == 24 && who.random.nextInt(3) == 0) SceneLines.say(who, "kitchen.scene.bread", "peel") })
                if (chain) stage.counter?.let { counter -> Stands.at(level, counter, here)?.takeIf { near(it) }?.let { slicing(it, counter, walk = true) } }
                plan.add(Go(home, Jobs.WALK))
            }
            KitchenSceneKind.SLICE -> {
                val counter = stage.counter ?: return false
                val stand = Stands.at(level, counter, here)?.takeIf { near(it) } ?: return false
                plan.add(Go(stand, Jobs.BRISK))
                slicing(stand, counter, walk = false)
                plan.add(Go(home, Jobs.WALK))
            }
            KitchenSceneKind.ROAST -> {
                val pos = stage.spit ?: return false
                val facing = level.getBlockState(pos).let { if (it.hasProperty(StationProps.FACING)) it.getValue(StationProps.FACING).clockWise else null }
                val stand = Stands.at(level, pos, here, facing)?.takeIf { near(it) } ?: return false
                plan.add(Go(stand, Jobs.BRISK))
                plan.add(Roasting(stand.yaw, Vec3.atCenterOf(pos).add(0.0, 0.2, 0.0), pos))
                plan.add(Run { lv, _ -> spit(lv)?.showRoast(false, 0) })
                plan.add(Go(home, Jobs.WALK))
            }
        }
        return true
    }

    /** Whether [stand] is within [FAR] blocks of where the scene took the keeper. */
    private fun near(stand: Stand): Boolean = stand.point.distanceToSqr(origin) <= FAR * FAR

    /** A loaf sliced at [counter] from [at]: a step to it first if [walk], the loaf under the hand, the cuts, the hand empty again. */
    private fun slicing(at: Stand, counter: BlockPos, walk: Boolean) {
        val look = Vec3.atCenterOf(counter).add(0.0, 0.5, 0.0)
        if (walk) plan.add(Go(at, Jobs.WALK))
        plan.add(Run { _, who -> who.carried = ItemStack(Items.BREAD) })
        plan.add(Do(Activity.SLICE, 40, at.yaw, look))
        plan.add(Run { _, who -> who.carried = ItemStack.EMPTY })
    }

    // ---- The Director ----

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        if (done) return
        // Back from something that had them meanwhile (running from a monster), or hurt: the day is theirs again
        if (lastTick >= 0 && p.tickCount - lastTick > 5) return finish(level, p, Ending.TAKEN)
        lastTick = p.tickCount
        if (p.hurtTime > 0) return finish(level, p, Ending.HURT)
        // Spoken to by anything else than a click: they stop and the usual chain answers
        if (p.attending != null) return finish(level, p, Ending.SPOKEN_TO)
        age++
        if (age > MAX_TICKS) return finish(level, p, Ending.TIMEOUT)
        if (age % 20 == 0) {
            if (!Cooking.townKitchens() || kind == KitchenSceneKind.ROAST && !Cooking.roast()) return finish(level, p, Ending.SWITCHED_OFF)
            if (level.getNearestPlayer(p, AWAY) == null) return finish(level, p, Ending.NO_PLAYER)
            if (!stageHolds(level)) return finish(level, p, Ending.LOST)
        }
        var guard = 0
        while (guard++ < 8) {
            val current = step ?: plan.removeFirstOrNull()?.also {
                step = it
                it.begin(level, p)
            } ?: return finish(level, p, Ending.DONE)
            if (!current.tick(level, p)) return
            current.end(level, p)
            step = null
            if (failed) return finish(level, p, Ending.LOST)
        }
    }

    /** A click on the keeper: the scene is over and they are back with their day, and the click goes on to the inn's order screen or the talk. */
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        val level = p.level() as? ServerLevel ?: return false
        finish(level, p, Ending.SPOKEN_TO)
        return false
    }

    /** Ends the scene as if [p] had been spoken to (the back door does this for a dish held out, before it looks at who has the keeper). */
    fun handBack(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        finish(level, p, Ending.SPOKEN_TO)
    }

    /** The keeper is gone (unloaded or dead) with the scene unfinished: the pot and the spit are put back as they were. */
    fun abandon(level: ServerLevel) {
        if (done) return
        done = true
        cleanup(level)
        ended(this, Ending.LOST)
    }

    /** Ends the scene: the pot and the spit as they were, the keeper handed back to their day. */
    private fun finish(level: ServerLevel, p: Townsperson, why: Ending) {
        if (done) return
        done = true
        cleanup(level)
        if (p.director === this) p.director = null
        walker?.stop()
        p.act(Activity.NONE)
        p.navigation.stop()
        p.routine.interrupt()
        ended(this, why)
    }

    // ---- The blocks ----

    private fun pot(level: ServerLevel): StewpotBlockEntity? {
        val pos = stage.pot ?: return null
        if (!level.isLoaded(pos)) return null
        return (level.getBlockEntity(pos) as? StewpotBlockEntity)?.takeIf { it.mode == StewpotMode.HOUSE }
    }

    private fun spit(level: ServerLevel): SpitBlockEntity? {
        val pos = stage.spit ?: return null
        if (!level.isLoaded(pos)) return null
        return level.getBlockEntity(pos) as? SpitBlockEntity
    }

    /** Whether the block the scene works at is still there. */
    private fun stageHolds(level: ServerLevel): Boolean = when (kind) {
        KitchenSceneKind.TAVERN -> pot(level) != null
        KitchenSceneKind.ROAST -> spit(level) != null
        KitchenSceneKind.PEEL -> stage.oven?.let { level.isLoaded(it) && level.getBlockEntity(it) != null } == true
        KitchenSceneKind.SLICE -> true
    }

    /** The pot's surface swirls for [ticks] from now (0 stops it): one packet for the whole stir. */
    private fun swirl(level: ServerLevel, ticks: Int) {
        val pot = pot(level) ?: return
        pot.showHouse(level.gameTime + ticks, bowls)
    }

    /** The pour of a bowl at tick [t] of the ladle clip, and its setting down on the shelf. */
    private fun ladle(level: ServerLevel, pos: BlockPos, t: Int) {
        val at = Vec3.atCenterOf(pos)
        val random = level.random
        if (t == POUR) level.playSound(null, at.x, at.y, at.z, CookSounds.get("cook.serve"), SoundSource.BLOCKS, 0.7f, 0.95f + random.nextFloat() * 0.1f)
        if (t == GIVE) {
            bowls = min(bowls + 1, 8)
            val pot = pot(level) ?: return
            pot.showHouse(if (pot.swirlUntil > level.gameTime) pot.swirlUntil else 0L, bowls)
            level.playSound(null, at.x, at.y, at.z, CookSounds.get("cook.plate"), SoundSource.BLOCKS, 0.6f, 0.9f + random.nextFloat() * 0.2f)
        }
    }

    /** The tasting's verdict, if the tasting is to say one. */
    private fun verdict(p: Townsperson) {
        when (stage.verdict) {
            1 -> SceneLines.say(p, "kitchen.scene.salt", "taste")
            2 -> SceneLines.say(p, "kitchen.scene.right", "taste")
        }
    }

    /** Puts the pot and the spit back: no swirl, the rod still, no roast. The bowls stay on the shelf (the table of kitchens takes them off in time). */
    private fun cleanup(level: ServerLevel) {
        pot(level)?.let { it.showHouse(if (it.swirlUntil > level.gameTime) level.gameTime else it.swirlUntil, bowls) }
        spit(level)?.let {
            it.turning = false
            it.showRoast(false, 0)
        }
    }

    // ---- The steps ----

    private abstract inner class Step {
        open fun begin(level: ServerLevel, p: Townsperson) {}

        /** One tick of it; true once it is over. */
        abstract fun tick(level: ServerLevel, p: Townsperson): Boolean

        open fun end(level: ServerLevel, p: Townsperson) {}
    }

    /** Something done at once, between two steps. */
    private inner class Run(private val what: (ServerLevel, Townsperson) -> Unit) : Step() {
        override fun tick(level: ServerLevel, p: Townsperson): Boolean {
            what(level, p)
            return true
        }
    }

    /**
     * Walking to [to]: on the path at [speed] until it is within a block and a bit, then eased onto the very spot with the body
     * turning to [Stand.yaw] as it settles, so the keeper arrives and faces the work with neither a snap nor a slide.
     */
    private inner class Go(private val to: Stand, private val speed: Double) : Step() {
        private var gliding = false
        private var walked = 0
        private var glided = 0

        override fun begin(level: ServerLevel, p: Townsperson) {
            p.act(Activity.NONE)
            if (flat(p) > NEAR) walker?.go(to.cell, speed, near = NEAR - 0.1) else gliding = true
        }

        private fun flat(p: Townsperson): Double {
            val dx = to.point.x - p.x
            val dz = to.point.z - p.z
            return sqrt(dx * dx + dz * dz)
        }

        override fun tick(level: ServerLevel, p: Townsperson): Boolean {
            if (!gliding) {
                if (++walked > 260) {
                    failed = true
                    return true
                }
                when (walker?.tick() ?: Walker.State.FAILED) {
                    Walker.State.WALKING -> return false
                    Walker.State.FAILED -> {
                        failed = true
                        return true
                    }
                    Walker.State.ARRIVED -> gliding = true
                }
            }
            return glide(p)
        }

        private fun glide(p: Townsperson): Boolean {
            val dx = to.point.x - p.x
            val dz = to.point.z - p.z
            val d = sqrt(dx * dx + dz * dz)
            p.face(Mth.approachDegrees(p.yRot, to.yaw, 24f))
            if (d > 0.02) {
                val step = min(d, (d * 0.3).coerceIn(0.025, 0.09))
                p.move(MoverType.SELF, Vec3(dx / d * step, 0.0, dz / d * step))
            }
            glided++
            if (d <= 0.03 && abs(Mth.wrapDegrees(to.yaw - p.yRot)) < 1.5f || glided > 80) {
                p.face(to.yaw)
                return true
            }
            return false
        }
    }

    /** [what] for [ticks] ticks (a one-shot clip is given its own length), facing [yaw] and looking at [look]; [on] hears each tick of it. */
    private inner class Do(
        private val what: Activity,
        private val ticks: Int,
        private val yaw: Float,
        private val look: Vec3?,
        private val on: (Int, ServerLevel, Townsperson) -> Unit = { _, _, _ -> },
    ) : Step() {
        private var t = 0

        override fun begin(level: ServerLevel, p: Townsperson) {
            p.act(what)
            p.face(yaw)
        }

        override fun tick(level: ServerLevel, p: Townsperson): Boolean {
            p.face(yaw)
            look?.let { p.lookAt(it) }
            on(t, level, p)
            return ++t >= ticks
        }
    }

    /**
     * The roast: the rod turned by the crank in bouts, the brow wiped between them, and the roast carved (three slices onto
     * bowls) at noon, or after the second bout in a scene played by the command. The carve is made from the Spit's front, as
     * its clip is (or its back, if the front is shut): the turning ends there and the scene walks her round and carves
     * ([toFront]); only with both long sides shut does she carve from the crank end. The Spit shows the roast, lit and with its three
     * joints, from the first turn ([SpitBlockEntity.showRoast]; its fat drips as any lit spit's does), a joint less at each slice;
     * it shows it turns while she cranks and not while she wipes or carves. Fat flares now and then; the cry is made once a day,
     * when a player first comes within twelve blocks.
     */
    private inner class Roasting(private val yaw: Float, private val look: Vec3, private val spitPos: BlockPos) : Step() {
        private var phase = RoastPhase.TURN
        private var phaseAge = 0
        private var sinceWipe = 0
        private var bouts = 0
        private var sliced = 0
        private var carveNext = false
        private var t = 0

        override fun begin(level: ServerLevel, p: Townsperson) {
            roast.day = Days.dayOf(level.overworldClockTime)
            spit(level)?.showRoast(true, SLICES)
            turn(level, p)
        }

        override fun end(level: ServerLevel, p: Townsperson) {
            spit(level)?.turning = false
        }

        private fun turn(level: ServerLevel, p: Townsperson) {
            phase = RoastPhase.TURN
            phaseAge = 0
            sinceWipe = 0
            spit(level)?.turning = true
            p.act(Activity.TURN_SPIT)
        }

        private fun wipe(level: ServerLevel, p: Townsperson) {
            phase = RoastPhase.WIPE
            phaseAge = 0
            spit(level)?.turning = false
            p.act(Activity.MOP_BROW)
        }

        private fun carve(level: ServerLevel, p: Townsperson) {
            phase = RoastPhase.CARVE
            phaseAge = 0
            spit(level)?.turning = false
            p.act(Activity.CARVE)
        }

        /** Whether it is time to carve: noon in the town's day, or the second bout done in a scene played by the command. */
        private fun due(level: ServerLevel): Boolean =
            if (carveAt >= 0 && !forced) Days.offsetOf(level.overworldClockTime) >= carveAt else bouts >= 2

        override fun tick(level: ServerLevel, p: Townsperson): Boolean {
            p.face(yaw)
            p.lookAt(look)
            t++
            if (t % 10 == 0) cry(level, p)
            phaseAge++
            when (phase) {
                RoastPhase.TURN -> {
                    sinceWipe++
                    fat(level, p)
                    val bout = if (forced) (if (bouts == 0) 200 else 120) else 480
                    // Noon cuts a long bout short (at the next two seconds' mark, after at least four seconds of it), so the carving is at noon
                    val noon = !forced && phaseAge >= 80 && due(level)
                    if ((phaseAge >= bout || noon) && phaseAge % 40 == 0) {
                        bouts++
                        if (!forced && Days.offsetOf(level.overworldClockTime) >= WINDOW_END) return true
                        if (due(level)) {
                            carveNext = true
                            if (sinceWipe >= 200) wipe(level, p) else if (toFront(level)) return true else carve(level, p)
                        } else {
                            wipe(level, p)
                        }
                    }
                }
                RoastPhase.WIPE -> if (phaseAge >= 40) {
                    if (carveNext || due(level)) {
                        if (toFront(level)) return true
                        carve(level, p)
                    } else {
                        turn(level, p)
                    }
                }
                RoastPhase.CARVE -> if (phaseAge == CUT) {
                    spit(level)?.let { it.showRoast(true, it.roastJoints - 1) }
                } else if (phaseAge >= CARVE_TICKS) {
                    sliced++
                    if (sliced >= SLICES) {
                        roast.carved = true
                        carvedHere = true
                        return true
                    }
                    carve(level, p)
                }
            }
            return false
        }

        /**
         * The carve from the Spit's front (its facing side) or else its back, where the clip is made for: a walk round to it, the
         * three slices, a joint off the rod at each cut, and the roast marked carved; put first in the plan, after this step.
         * False, and nothing changed, when neither side is open or the spit has no facing.
         */
        private fun toFront(level: ServerLevel): Boolean {
            val state = level.getBlockState(spitPos)
            if (!state.hasProperty(StationProps.FACING)) return false
            val front = state.getValue(StationProps.FACING)
            val stand = Stands.on(level, spitPos, listOf(front, front.opposite))?.takeIf { near(it) } ?: return false
            val steps = ArrayList<Step>()
            steps.add(Go(stand, Jobs.WALK))
            repeat(SLICES) {
                steps.add(Do(Activity.CARVE, CARVE_TICKS, stand.yaw, look) { t, lv, _ -> if (t == CUT) spit(lv)?.let { it.showRoast(true, it.roastJoints - 1) } })
            }
            steps.add(Run { _, _ ->
                roast.carved = true
                carvedHere = true
            })
            for (s in steps.asReversed()) plan.addFirst(s)
            return true
        }

        /** The cry of the day, when a player has come within twelve blocks. */
        private fun cry(level: ServerLevel, p: Townsperson) {
            if (roast.cried || level.getNearestPlayer(p, 12.0) == null) return
            if (SceneLines.say(p, "kitchen.scene.roast", "roast")) roast.cried = true
        }

        /** Fat off the joint onto the coals: a small flare now and again (the drips are the lit spit's own, drawn by each client). */
        private fun fat(level: ServerLevel, p: Townsperson) {
            val random = p.random
            val x = spitPos.x + 0.5
            val y = spitPos.y.toDouble()
            val z = spitPos.z + 0.5
            if (random.nextInt(240) == 0) {
                level.sendParticles(ParticleTypes.SMALL_FLAME, x, y + 0.3, z, 4, 0.14, 0.05, 0.14, 0.01)
                level.playSound(null, x, y, z, CookSounds.get("cook.flare"), SoundSource.BLOCKS, 0.5f, 0.95f + random.nextFloat() * 0.1f)
            }
        }
    }
}

/** What the keeper is at in the roast. */
private enum class RoastPhase { TURN, WIPE, CARVE }

/** How near, in blocks, a keeper walks before the last of the way is eased in. */
private const val NEAR = 1.3
