package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.util.LandRandomPos
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.EnumSet
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The pack animals that are loaded just now, and what they do for their owners (living-world/trader/design.md, Part 3, "The pack
 * animal"). An animal is a pack animal while it wears a [PackKit]; it is found here from the moment it joins the level (goals are not
 * saved, so every load adopts it again) until it leaves. The server is the only place any of it is decided.
 *
 * What a pack animal does, all of it a goal on the animal's own AI so that vanilla's panic, leads and riding still come first:
 * - It follows ([PackKit.Mode.FOLLOW]): once its owner is more than [START] blocks off it walks to within [STOP], at the owner's own
 *   pace and a little quicker the further behind it is, trotting when it is far behind, and stands when the owner stands. It finds its
 *   way as vanilla animals do (round walls, up one-block steps, never into water the owner is not in). If it makes no headway for
 *   five seconds it stops trying for a few and tries again; it never teleports of its own accord.
 * - It stays ([PackKit.Mode.STAY]): it stands where it was told, walks back if something moves it, and does not wander.
 * - Its bell rings once for every stride it takes, a bell by a trot and a bell by a walk, so it rings faster the faster it goes and not at
 *   all while it stands or when it is hushed.
 * - The whistle ([whistle]) calls the animals within [WHISTLE_RANGE] blocks or tells them to stay; a called animal that has not come after
 *   a while and cannot be seen is put beside its owner (the one time one is moved at all).
 * - The Hunt (build part E) frightens them through [alarm], [bolt] and [settle].
 *
 * With carrying switched off ([TradeConfig.carry]) nothing here acts: the animals stand as animals do and their chests are nobody's
 * laden goods, but every chest still opens.
 */
object PackFollow {
    /** How far a whistle reaches, in blocks. */
    const val WHISTLE_RANGE = 64.0

    /** Owner farther than this and the animal sets off; it walks until it is within [STOP]; and trots if it is more than [HURRY] behind. */
    private const val START = 6.0
    private const val STOP = 4.0
    private const val HURRY = 14.0

    /** Ticks without closing in before it gives up for [PAUSE] ticks, and how far it must close in to count. */
    private const val STUCK = 100L
    private const val PAUSE = 80L
    private const val HEADWAY = 1.5

    /** A called animal that is still more than this many blocks off after [RECALL] ticks and is out of its owner's sight is put beside them. */
    private const val FAR = 8.0
    private const val RECALL = 400L

    /** Ticks between two looks at the panniers (what shows of them is worked out from the chest this often). */
    private const val POLL = 10

    /** The bell: blocks of walking to a ring, at a walk and at a trot, and the blocks a tick above which it is a trot (the client swings the bell by the same). */
    internal const val WALK_STRIDE = 1.6
    internal const val TROT_STRIDE = 2.4
    internal const val TROT_SPEED = 0.3

    /** How long a bolted animal runs, at the longest, before it goes home, and how long it may take to get there. */
    const val BOLT_TICKS = 400
    private const val HOME_TICKS = 1200L

    /** The Hunt's horn: how many ticks the animals stand with their heads up. */
    const val ALARM_TICKS = 160

    /** Extra reach for finding a way, added to an animal's follow range while it wears the kit, so a whistle from far off can find its route. */
    private val RANGE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "pack_range")
    private const val RANGE_BONUS = 32.0

    /** One loaded pack animal and what is known of it that is not in its kit. */
    class Rig(val animal: AbstractChestedHorse) {
        var follow: Goal? = null
        var hold: Goal? = null
        var scare: Goal? = null

        /** Where the animal and its owner were last together (or the whistle's point): where a bolted animal goes back to. */
        var home: BlockPos? = null
        var homeAt = 0L

        /** The bell: where it was last tick, and how far it has walked since it last rang. */
        var lastX = animal.x
        var lastZ = animal.z
        var walked = 0.0

        /** When a ring of the bell next turns the heads of the townsfolk about ([Notice]). */
        var noticeAt = 0L

        /** The owner's pace in blocks a tick, as last worked out, and where and when it was sampled. */
        var pace = 0.0
        var sample: Vec3? = null
        var sampleAt = 0L

        /** When it gave up following, and when it set off and how far it was then (to see whether it is getting anywhere). */
        var retryAt = 0L
        var checkAt = 0L
        var checkDist = 0.0

        /** A whistle: when it is answered, when it is looked at again to see whether it has come, and whether it hurries. */
        var answerAt = 0L
        var recallBy = 0L

        /** The Hunt: heads up until [alarmUntil] looking at [alarmFrom]; running from [boltFrom] until [boltUntil]; going [home] until [returnBy]. */
        var alarmUntil = 0L
        var alarmFrom: Vec3? = null
        var boltUntil = 0L
        var boltFrom: Vec3? = null
        var returnBy = 0L
        var returning = false

        fun now(): Long = animal.level().gameTime

        fun mode(): PackKit.Mode? = PackKit.of(animal)?.mode

        /** Whether the animal is free to be told what to do: alive, tame, not ridden, not led and not a foal. */
        fun idle(): Boolean = animal.isAlive && !animal.isVehicle && !animal.isPassenger && !animal.isLeashed && animal.isTamed && !animal.isBaby

        /** Whether the Hunt has hold of it: heads up, running, or going home. */
        fun frightened(): Boolean {
            val now = now()
            return alarmUntil > now || boltUntil > now || returning
        }

        /** Its owner, if they are here: online, in the animal's level and not a spectator. */
        fun owner(): ServerPlayer? {
            val level = animal.level() as? ServerLevel ?: return null
            val id = animal.ownerReference?.uuid ?: return null
            val player = level.server.playerList.getPlayer(id) ?: return null
            return if (player.level() === level && player.isAlive && !player.isSpectator) player else null
        }

        /** How fast [owner] is going, in blocks a tick, smoothed over the last second or so. */
        fun paceOf(owner: ServerPlayer): Double {
            val now = now()
            val here = owner.position()
            val last = sample
            if (last == null || now - sampleAt > 60) {
                sample = here
                sampleAt = now
                pace = 0.0
            } else if (now - sampleAt >= 5) {
                pace = pace * 0.5 + hypot(here.x - last.x, here.z - last.z) / (now - sampleAt) * 0.5
                sample = here
                sampleAt = now
            }
            return pace
        }

        /** The speed it should walk at, as a share of its own, to stay with an owner [distance] blocks off who is going [ownerPace] blocks a tick. */
        fun speedFor(distance: Double, ownerPace: Double, hurry: Boolean): Double {
            val perTick = max(animal.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.05) * 2.2
            var share = ownerPace / perTick + (distance - (START + STOP) / 2.0 + 0.5) * 0.1
            if (hurry || distance > HURRY) share = max(share, 1.0)
            return share.coerceIn(0.5, 1.5)
        }
    }

    private val rigs = LinkedHashMap<UUID, Rig>()
    private var snapshot: List<Rig> = listOf()

    /** The loaded pack animals in the order they were adopted. */
    fun all(): List<AbstractChestedHorse> = rigs.values.map { it.animal }

    fun rigOf(animal: AbstractChestedHorse): Rig? = rigs[animal.uuid]?.takeIf { it.animal === animal }

    /** Hooks the level and the clock: an animal with a kit is adopted as it joins and let go as it leaves, and the bell and the panniers are kept every tick. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent::class.java) { event ->
            val animal = event.entity as? AbstractChestedHorse
            if (animal != null && !event.level.isClientSide && PackKit.of(animal) != null) adopt(animal)
        }
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent::class.java) { event ->
            val animal = event.entity as? AbstractChestedHorse
            if (animal != null && !event.level.isClientSide) release(animal)
        }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            rigs.clear()
            snapshot = listOf()
        }
    }

    /** Makes [animal], which wears a kit, a pack animal of this session: its goals are added and it is looked after from now on. */
    fun adopt(animal: AbstractChestedHorse) {
        val before = rigs[animal.uuid]
        if (before != null && before.animal === animal) return
        val rig = Rig(animal)
        val kit = PackKit.of(animal)
        rig.home = kit?.anchor ?: animal.blockPosition()
        rig.follow = FollowGoal(rig).also { animal.goalSelector.addGoal(FOLLOW_PRIORITY, it) }
        rig.hold = HoldGoal(rig).also { animal.goalSelector.addGoal(HOLD_PRIORITY, it) }
        rig.scare = ScareGoal(rig).also { animal.goalSelector.addGoal(SCARE_PRIORITY, it) }
        animal.getAttribute(Attributes.FOLLOW_RANGE)?.addOrUpdateTransientModifier(AttributeModifier(RANGE, RANGE_BONUS, AttributeModifier.Operation.ADD_VALUE))
        animal.setPathfindingMalus(PathType.WATER, -1.0f)
        rigs[animal.uuid] = rig
        snapshot = rigs.values.toList()
    }

    /** Lets go of [animal]: its goals come off and it is no longer a pack animal of this session (its kit, if it still has one, is the harness's to take off). */
    fun release(animal: AbstractChestedHorse) {
        val rig = rigOf(animal) ?: return
        rig.follow?.let { animal.goalSelector.removeGoal(it) }
        rig.hold?.let { animal.goalSelector.removeGoal(it) }
        rig.scare?.let { animal.goalSelector.removeGoal(it) }
        animal.getAttribute(Attributes.FOLLOW_RANGE)?.removeModifier(RANGE)
        animal.setPathfindingMalus(PathType.WATER, PathType.WATER.malus)
        rigs.remove(animal.uuid)
        snapshot = rigs.values.toList()
    }

    /** Whether [animal] is one of the loaded pack animals. */
    fun isRigged(animal: AbstractChestedHorse): Boolean = rigOf(animal) != null

    /** The loaded pack animals that are [player]'s, in the player's level and within [radius] blocks of them, nearest first. */
    fun owned(player: ServerPlayer, radius: Double): List<AbstractChestedHorse> {
        if (rigs.isEmpty()) return listOf()
        val limit = radius * radius
        return rigs.values.map { it.animal }
            .filter { it.isAlive && it.level() === player.level() && it.ownerReference?.uuid == player.uuid && it.distanceToSqr(player) <= limit }
            .sortedBy { it.distanceToSqr(player) }
    }

    /** Every loaded pack animal that is [player]'s, wherever it is, nearest first in its own level (the commands' list). */
    fun ownedAnywhere(player: ServerPlayer): List<AbstractChestedHorse> =
        rigs.values.map { it.animal }.filter { it.isAlive && it.ownerReference?.uuid == player.uuid }

    /**
     * The panniers of [player]'s pack animals within [radius] blocks of them: the pack animal's [io.github.vaspyyy.guildmark.trade.Carrier].
     * None while carrying is off, so that goods in an animal's chest are not counted as the trader's.
     */
    fun holdings(player: ServerPlayer, radius: Double): List<Container> {
        if (rigs.isEmpty() || !TradeConfig.carry()) return listOf()
        return owned(player, radius).filter { it.hasChest() }.map { it.getInventory() }
    }

    // ---- The clock: the bell, the panniers, the whistle's answer and the Hunt's timers ----

    private fun tick(server: MinecraftServer) {
        if (snapshot.isEmpty()) return
        val on = TradeConfig.carry()
        for (rig in snapshot) {
            val animal = rig.animal
            if (animal.isRemoved || !animal.isAlive) continue
            val level = animal.level() as? ServerLevel ?: continue
            val kit = PackKit.of(animal)
            if (kit == null) {
                release(animal)
                continue
            }
            if (!on) {
                rig.lastX = animal.x
                rig.lastZ = animal.z
                rig.walked = 0.0
                continue
            }
            val now = level.gameTime
            if (rig.boltUntil != 0L && now >= rig.boltUntil) {
                rig.boltUntil = 0L
                begin(rig, now)
            }
            if (rig.alarmUntil != 0L && now >= rig.alarmUntil) rig.alarmUntil = 0L
            if (rig.returning && now >= rig.returnBy) rig.returning = false
            if (rig.answerAt != 0L && now >= rig.answerAt) answer(rig)
            if (rig.recallBy != 0L && now >= rig.recallBy) checkRecall(rig)
            bell(rig, kit, level)
            if ((animal.tickCount + animal.id) % POLL == 0) poll(rig, kit, now)
        }
    }

    /** The bell rings for every stride the animal walks, a trot's ring for a trot and a walk's for a walk, and not when it is hushed. */
    private fun bell(rig: Rig, kit: PackKit, level: ServerLevel) {
        val animal = rig.animal
        val moved = hypot(animal.x - rig.lastX, animal.z - rig.lastZ)
        rig.lastX = animal.x
        rig.lastZ = animal.z
        if (moved > 1.5 || !animal.onGround() || animal.isPassenger) {
            rig.walked = 0.0
            return
        }
        if (moved < 0.02) return
        rig.walked += moved
        val trot = moved > TROT_SPEED
        if (rig.walked < (if (trot) TROT_STRIDE else WALK_STRIDE)) return
        rig.walked = 0.0
        if (!kit.bell) return
        val pitch = 0.9f + animal.getRandom().nextFloat() * 0.2f
        level.playSound(null, animal, TradeSounds.get(if (trot) "trade.bell_trot" else "trade.bell_step"), SoundSource.NEUTRAL, if (trot) 0.7f else 0.55f, pitch)
        Notice.bell(rig)
    }

    /** Looks at the panniers: how full they are and what shows on them, and whether the bell is hushed; the kit is set only if something differs. */
    private fun poll(rig: Rig, kit: PackKit, now: Long) {
        val animal = rig.animal
        val chest = if (animal.hasChest()) animal.getInventory() else null
        val fill = chest?.let { PackKit.fillOf(it) } ?: 0
        val shown = chest?.let { PackKit.shownOf(it) } ?: listOf()
        val bell = rig.alarmUntil <= now
        if (fill != kit.fill || shown != kit.shown || bell != kit.bell) animal.setData(Carry.PACK_KIT, kit.copy(fill = fill, shown = shown, bell = bell))
    }

    /** A called animal answers: it looks at its owner and calls out, nearer ones first. */
    private fun answer(rig: Rig) {
        rig.answerAt = 0L
        val animal = rig.animal
        if (!animal.isAlive) return
        animal.playAmbientSound()
        rig.owner()?.let { animal.lookControl.setLookAt(it, 30f, 30f) }
    }

    /** A whistled animal that is still far off, cannot be seen by its owner and has had its time is put beside them; one that can be seen is left to get there. */
    private fun checkRecall(rig: Rig) {
        rig.recallBy = 0L
        val animal = rig.animal
        val owner = rig.owner() ?: return
        if (rig.mode() != PackKit.Mode.FOLLOW || !rig.idle()) return
        if (animal.distanceTo(owner) <= FAR) return
        if (owner.hasLineOfSight(animal)) {
            rig.recallBy = rig.now() + RECALL / 2
            return
        }
        bring(animal, owner)
    }

    // ---- Following, staying, and the Hunt's frights, as goals ----

    private const val FOLLOW_PRIORITY = 2
    private const val HOLD_PRIORITY = 3
    private const val SCARE_PRIORITY = 0

    /** Walks to the owner and stands near them. */
    private class FollowGoal(val rig: Rig) : Goal() {
        private var repath = 0

        init {
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK))
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun canUse(): Boolean {
            if (!TradeConfig.carry() || !rig.idle() || rig.mode() != PackKit.Mode.FOLLOW || rig.frightened()) return false
            if (rig.now() < rig.retryAt) return false
            val owner = rig.owner() ?: return false
            return rig.animal.distanceToSqr(owner) > START * START
        }

        override fun canContinueToUse(): Boolean {
            if (!TradeConfig.carry() || !rig.idle() || rig.mode() != PackKit.Mode.FOLLOW || rig.frightened()) return false
            if (rig.now() < rig.retryAt) return false
            val owner = rig.owner() ?: return false
            return rig.animal.distanceToSqr(owner) > STOP * STOP
        }

        override fun start() {
            repath = 0
            rig.checkAt = rig.now()
            rig.checkDist = rig.owner()?.let { rig.animal.distanceTo(it).toDouble() } ?: 0.0
        }

        override fun stop() {
            rig.animal.navigation.stop()
            // Arrived: a whistle's hurry is over
            val owner = rig.owner()
            if (owner != null && rig.animal.distanceToSqr(owner) <= (STOP + 1.0) * (STOP + 1.0)) rig.recallBy = 0L
        }

        override fun tick() {
            val owner = rig.owner() ?: return
            val animal = rig.animal
            val now = rig.now()
            animal.lookControl.setLookAt(owner, 30f, animal.maxHeadXRot.toFloat())
            val distance = animal.distanceTo(owner).toDouble()
            if (distance < 12.0 && now - rig.homeAt >= 20) {
                rig.home = animal.blockPosition()
                rig.homeAt = now
            }
            if (--repath > 0) return
            repath = 10
            val speed = rig.speedFor(distance, rig.paceOf(owner), rig.recallBy != 0L)
            if (!animal.navigation.moveTo(owner, speed)) toward(owner, speed)
            if (now - rig.checkAt >= STUCK) {
                // No nearer than it was five seconds ago, and the owner has not gone off from it: stand a while, then try again
                val closer = rig.checkDist - distance
                rig.checkAt = now
                rig.checkDist = distance
                if (closer < HEADWAY && rig.pace < 0.05) {
                    rig.retryAt = now + PAUSE
                    animal.navigation.stop()
                }
            }
        }

        /** No path to the owner: a step towards them on ground it can stand on. */
        private fun toward(owner: ServerPlayer, speed: Double) {
            val spot = LandRandomPos.getPosTowards(rig.animal, 12, 6, owner.position()) ?: return
            rig.animal.navigation.moveTo(spot.x, spot.y, spot.z, speed)
        }
    }

    /** Stands where it was told, and goes back to the place if something moves it. */
    private class HoldGoal(val rig: Rig) : Goal() {
        private var repath = 0

        init {
            setFlags(EnumSet.of(Goal.Flag.MOVE))
        }

        override fun canUse(): Boolean = TradeConfig.carry() && rig.idle() && rig.mode() == PackKit.Mode.STAY && !rig.frightened()

        override fun canContinueToUse(): Boolean = canUse()

        override fun start() {
            repath = 0
        }

        override fun stop() {
            rig.animal.navigation.stop()
        }

        override fun tick() {
            val animal = rig.animal
            val spot = PackKit.of(animal)?.anchor ?: return
            val dx = animal.x - (spot.x + 0.5)
            val dz = animal.z - (spot.z + 0.5)
            val away = dx * dx + dz * dz
            if (away > 2.5 * 2.5) {
                if (--repath <= 0) {
                    repath = 20
                    animal.navigation.moveTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, 0.6)
                }
            } else if (away < 1.2 * 1.2 && !animal.navigation.isDone) {
                animal.navigation.stop()
            }
        }
    }

    /** The Hunt: heads up at the horn, running from the band, then going home. It comes before panic. */
    private class ScareGoal(val rig: Rig) : Goal() {
        private var repath = 0

        init {
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK))
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun canUse(): Boolean = TradeConfig.carry() && rig.idle() && rig.frightened()

        override fun canContinueToUse(): Boolean = canUse()

        override fun start() {
            repath = 0
        }

        override fun stop() {
            rig.animal.navigation.stop()
        }

        override fun tick() {
            val animal = rig.animal
            val now = rig.now()
            when {
                rig.boltUntil > now -> {
                    val from = rig.boltFrom ?: animal.position()
                    if (--repath <= 0) {
                        repath = 15
                        val spot = LandRandomPos.getPosAway(animal, 16, 7, from) ?: return
                        animal.navigation.moveTo(spot.x, spot.y, spot.z, 1.6)
                    }
                }
                rig.alarmUntil > now -> {
                    animal.navigation.stop()
                    rig.alarmFrom?.let { animal.lookControl.setLookAt(it.x, it.y, it.z, 30f, 30f) }
                }
                rig.returning -> {
                    val home = rig.home
                    if (home == null) {
                        rig.returning = false
                        return
                    }
                    val dx = animal.x - (home.x + 0.5)
                    val dz = animal.z - (home.z + 0.5)
                    if (dx * dx + dz * dz <= 2.5 * 2.5) {
                        finishReturn(rig, home)
                    } else if (--repath <= 0) {
                        repath = 20
                        animal.navigation.moveTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5, 1.1)
                    }
                }
            }
        }
    }

    // ---- The Hunt's seams (build part E) ----

    /** Where the animal goes when the band has gone: the place it and its owner were last together. */
    private fun begin(rig: Rig, now: Long) {
        rig.returning = rig.home != null
        rig.returnBy = now + HOME_TICKS
    }

    /** It has got home: it stays there until it is called, as an animal told to stay does. */
    private fun finishReturn(rig: Rig, home: BlockPos) {
        rig.returning = false
        val kit = PackKit.of(rig.animal) ?: return
        val now = kit.copy(mode = PackKit.Mode.STAY, anchor = home)
        if (now != kit) rig.animal.setData(Carry.PACK_KIT, now)
        rig.animal.navigation.stop()
    }

    /**
     * The Hunt's horn has sounded: each pack animal of [player]'s within [WHISTLE_RANGE] blocks throws up its head and turns it towards
     * [from], and its bell goes quiet, for [ticks] ticks. Answers how many did.
     */
    fun alarm(player: ServerPlayer, from: Vec3, ticks: Int = ALARM_TICKS): Int {
        var n = 0
        for (animal in owned(player, WHISTLE_RANGE)) {
            val rig = rigOf(animal) ?: continue
            rig.alarmUntil = rig.now() + ticks
            rig.alarmFrom = from
            hush(rig)
            n++
        }
        return n
    }

    /**
     * The band is on [player]: each pack animal of theirs within [WHISTLE_RANGE] blocks bolts from [from] for up to [ticks] ticks, its bell
     * ringing fast and fading as it runs, and then goes home, to the place it and its owner were last together, where it stays until it is
     * called. [settle] sends them home sooner. Answers how many bolted.
     */
    fun bolt(player: ServerPlayer, from: Vec3, ticks: Int = BOLT_TICKS): Int {
        var n = 0
        for (animal in owned(player, WHISTLE_RANGE)) {
            val rig = rigOf(animal) ?: continue
            rig.alarmUntil = 0L
            rig.returning = false
            rig.boltFrom = from
            rig.boltUntil = rig.now() + ticks
            // Its bell is not hushed: it rings fast as the animal runs and fades with the distance
            animal.navigation.stop()
            n++
        }
        return n
    }

    /** The band has gone: every bolted animal of [player]'s that is still running goes home now. Answers how many. */
    fun settle(player: ServerPlayer): Int {
        var n = 0
        for (animal in ownedAnywhere(player)) {
            val rig = rigOf(animal) ?: continue
            if (rig.boltUntil == 0L) continue
            rig.boltUntil = 0L
            begin(rig, rig.now())
            n++
        }
        return n
    }

    /** Sets the kit's bell to hushed at once (the panniers' look is next polled within half a second). */
    private fun hush(rig: Rig) {
        val kit = PackKit.of(rig.animal) ?: return
        if (kit.bell) rig.animal.setData(Carry.PACK_KIT, kit.copy(bell = false))
    }

    // ---- What the commands ask ----

    /** How [animal] stands just now, in a few words, for `trade carry status`. */
    fun stateOf(animal: AbstractChestedHorse): String {
        val rig = rigOf(animal) ?: return "not a pack animal this session"
        val now = rig.now()
        return when {
            !TradeConfig.carry() -> "standing (carrying is off)"
            rig.boltUntil > now -> "bolting"
            rig.alarmUntil > now -> "alert, its bell hushed"
            rig.returning -> "going home"
            rig.mode() == PackKit.Mode.STAY -> "staying"
            rig.recallBy != 0L -> "answering the whistle"
            rig.retryAt > now -> "following, waiting (no way through)"
            else -> "following"
        }
    }

    /** Brings every loaded pack animal of [player]'s in their level to them, following, and lets go of any lead it was on; answers how many. */
    fun recall(player: ServerPlayer): Int = ownedAnywhere(player).count { bring(it, player) }

    /** As [recall]: an animal stuck for any reason (a lead, a doorway, a hole) is put on the ground beside its owner. */
    fun unstick(player: ServerPlayer): Int = recall(player)

    // ---- The whistle, and bringing an animal to its owner ----

    /**
     * Blows the whistle for [player]: every pack animal of theirs within [WHISTLE_RANGE] blocks is called to follow, or, if [stay], told
     * to stay where it stands. A called animal lifts its head, answers a moment later (the nearer the sooner) and trots to its owner. Answers
     * how many heard it.
     */
    fun whistle(player: ServerPlayer, stay: Boolean): Int {
        val level = player.level() as? ServerLevel ?: return 0
        level.playSound(null, player.x, player.y, player.z, TradeSounds.get(if (stay) "trade.whistle_stay" else "trade.whistle_come"), SoundSource.PLAYERS, 1.0f, 1.0f)
        val now = level.gameTime
        var heard = 0
        for (animal in owned(player, WHISTLE_RANGE)) {
            val rig = rigOf(animal) ?: continue
            val kit = PackKit.of(animal) ?: continue
            rig.alarmUntil = 0L
            rig.boltUntil = 0L
            rig.returning = false
            rig.retryAt = 0L
            if (stay) {
                rig.recallBy = 0L
                animal.navigation.stop()
                rig.home = animal.blockPosition()
                val told = kit.copy(mode = PackKit.Mode.STAY, anchor = animal.blockPosition())
                if (told != kit) animal.setData(Carry.PACK_KIT, told)
            } else {
                rig.recallBy = now + RECALL
                rig.answerAt = now + 4 + (animal.distanceTo(player) / 6.0f).toLong()
                rig.home = player.blockPosition()
                rig.homeAt = now
                animal.lookControl.setLookAt(player, 30f, 30f)
                val told = kit.copy(mode = PackKit.Mode.FOLLOW, anchor = player.blockPosition())
                if (told != kit) animal.setData(Carry.PACK_KIT, told)
            }
            heard++
        }
        val word = when {
            heard == 0 -> if (stay) "none_stay" else "none_come"
            heard == 1 -> if (stay) "one_stay" else "one_come"
            else -> if (stay) "many_stay" else "many_come"
        }
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.whistle.$word", heard))
        return heard
    }

    /**
     * Puts [animal] on the ground near [player], a few blocks behind them, and has it follow: for a called animal that cannot be seen, for
     * `trade carry recall` and for `trade carry unstick`. Answers whether there was room (the animal is left where it is if there was not).
     * The animal stays in its level; one in another dimension is not brought.
     */
    fun bring(animal: AbstractChestedHorse, player: ServerPlayer): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (animal.level() !== level) return false
        val spot = spotNear(level, animal, player) ?: return false
        animal.navigation.stop()
        animal.dropLeash()
        animal.setDeltaMovement(Vec3.ZERO)
        level.sendParticles(ParticleTypes.CLOUD, animal.x, animal.y + 0.5, animal.z, 4, 0.3, 0.3, 0.3, 0.01)
        animal.teleportTo(spot.x, spot.y, spot.z)
        animal.resetFallDistance()
        level.sendParticles(ParticleTypes.CLOUD, spot.x, spot.y + 0.5, spot.z, 6, 0.3, 0.3, 0.3, 0.01)
        rigOf(animal)?.let {
            it.alarmUntil = 0L
            it.boltUntil = 0L
            it.returning = false
            it.retryAt = 0L
            it.recallBy = 0L
            it.home = spot.let { s -> BlockPos.containing(s) }
        }
        val kit = PackKit.of(animal)
        if (kit != null && kit.mode != PackKit.Mode.FOLLOW) animal.setData(Carry.PACK_KIT, kit.copy(mode = PackKit.Mode.FOLLOW, anchor = BlockPos.containing(spot)))
        return true
    }

    /**
     * A place for [animal] to stand near [player]: three to five blocks off, behind them first and then to either side, on ground it fits
     * on, in loaded land. Null if there is none.
     */
    fun spotNear(level: ServerLevel, animal: AbstractChestedHorse, player: ServerPlayer): Vec3? {
        val dimensions = animal.getDimensions(animal.pose)
        val behind = Math.toRadians(player.yRot.toDouble()) + Math.PI
        val steps = doubleArrayOf(0.0, 0.5, -0.5, 1.0, -1.0, 1.6, -1.6, 2.2, -2.2)
        for (radius in intArrayOf(3, 4, 5, 2, 6)) {
            for (step in steps) {
                val a = behind + step
                val x = player.x - sin(a) * radius
                val z = player.z + cos(a) * radius
                val spot = standingAt(level, animal, dimensions, x, z, player.blockPosition().y) ?: continue
                return spot
            }
        }
        return null
    }

    /** The place on the column of ([x], [z]) near height [y] where a body of [dimensions] can stand on solid ground, in loaded land, not in water. */
    private fun standingAt(level: ServerLevel, animal: AbstractChestedHorse, dimensions: EntityDimensions, x: Double, z: Double, y: Int): Vec3? {
        val column = BlockPos.containing(x, y.toDouble(), z)
        if (!level.isLoaded(column)) return null
        for (dy in intArrayOf(0, 1, -1, 2, -2, 3, -3)) {
            val feet = column.offset(0, dy, 0)
            if (!level.isLoaded(feet)) continue
            if (!Places.floor(level, feet.below())) continue
            if (!level.getFluidState(feet).isEmpty) continue
            val box = dimensions.makeBoundingBox(x, feet.y.toDouble(), z)
            if (level.noCollision(animal, box)) return Vec3(x, feet.y.toDouble(), z)
        }
        return null
    }
}
