package io.github.vaspyyy.guildmark.dragon

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.combat.Blow
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.network.BossCuePayload
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.world.BossEvent
import net.minecraft.world.Difficulty
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.BodyRotationControl
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A dragon of the high peaks, the land's late-game field boss: one of the Unbowed, the old fire-drakes that never
 * knelt to the Hollow King. Each keeps a nest on a summit, sleeps there by night and by day now and then takes wing
 * to circle its peak. Anyone it spots (or who wakes it) gets a roar and a fight in three phases:
 *
 * - On the ground it bites, rakes with a claw, whips its tail round at anyone on its flanks, rears up to stamp,
 *   beats its wings to throw people back and sweeps a stream of fire across whoever is in front of it. Every so
 *   often, or when its foe is somewhere it can't walk to, it takes off, makes a pass breathing fire down along
 *   the ground, and lands near them again, or dives on them talons first.
 * - Past 60% it roars and fights more from the air: wider circles, strafing runs, bolts of fire spat from a
 *   hover, and a dive to finish each flight.
 * - Past 25% the fire under its scales breaks through: it comes down for good, faster and more savage, and its
 *   flames leave the ground burning longer.
 *
 * Fire it leaves on the ground burns whoever stands in it; nothing in the world is set alight. Like every field
 * boss it fights only players, lets go and heals if they all fall or get clear, gives up on anyone who hides
 * from it, and keeps no fight through a reload.
 */
class Dragon(type: EntityType<out Dragon>, level: Level) : CombatEnemy(type, level) {
    override val attacks: List<EnemyAttack> get() = ATTACKS
    override val animations: String get() = ANIMATIONS
    override val isBoss: Boolean get() = true
    override val maxStance: Float get() = 420f
    override val waitRange: Double get() = 4.0
    override val chaseSpeed: Double get() = if (enraged) 1.3 else 1.15
    override val cooldownScale: Float get() = if (enraged) 0.6f else if (phaseTwo) 0.8f else 1f

    /** It has found someone and the fight is on. */
    val fighting: Boolean get() = flag(FIGHTING)
    /** On the wing (no gravity; it steers itself). */
    val flying: Boolean get() = flag(FLYING)
    /** Curled up asleep on its nest. */
    val asleep: Boolean get() = flag(ASLEEP)
    /** Past 60%: it fights more from the air. */
    val phaseTwo: Boolean get() = flag(PHASE_TWO)
    /** Past 25%: the fire under its scales shows through and it stays down to fight. */
    val enraged: Boolean get() = flag(ENRAGED)
    /** The pitch its fire is aimed at, in degrees below level (synced, for the flames drawn on clients). */
    val aimPitch: Float get() = entityData.get(DATA_AIM)

    /** Where it nests, and which way it lies there. A dragon with no nest (from an egg) makes its home where it first stands. */
    var home: BlockPos? = null
    var homeYaw = 0f
    /** Its roost in DragonRoosts, or -1. */
    var roost = -1
    /** Which of the dragons' names it answers to. */
    var nameIndex: Int = 0
        private set

    private var reloaded = false
    /** The players in this fight, each by the entity they were when they joined it (dying makes a new one). */
    private val foes = HashMap<UUID, Int>()
    private var lonely = 0
    /** Seconds in a row it hasn't seen whoever it's after. */
    private var unseen = 0
    /** Ticks it keeps to itself after giving up a fight. */
    private var aloof = 0
    private var greeted = false
    private var pendingPhaseTwo = false
    private var pendingEnrage = false

    // On the ground
    private var groundTicks = 0
    private var groundLimit = 400
    /** Ticks it has walked toward its foe without getting anywhere. */
    private var stuck = 0
    private var headwayFrom: Vec3? = null
    /** Ticks until it next takes wing to circle its peak, when out of a fight. */
    private var restTicks = 1200

    // On the wing
    private var air = AirMove.NONE
    private val airPlan = ArrayDeque<AirMove>()
    private var airTicks = 0
    private var airTimer = 0
    private var airGoal: Vec3 = Vec3.ZERO
    private var passStart: Vec3 = Vec3.ZERO
    private var passEnd: Vec3 = Vec3.ZERO
    private var passDir: Vec3 = Vec3.ZERO
    private var breathed = false
    private var circleCenter: Vec3 = Vec3.ZERO
    private var circleRadius = 22.0
    private var circleHeight = 14.0
    private var circleDir = 1.0
    private var circleSwept = 0.0
    private var circleGoal = Math.PI * 2
    private var circleLast = 0.0
    private var landing: Vec3? = null
    private var landFrom: Vec3 = Vec3.ZERO
    private var landTries = 0
    private var diveTo: Vec3? = null
    private var patrolTicks = 0

    // Fire
    private val patches = ArrayDeque<Patch>()
    /** Its fire is landing right now (for the poise it wears). */
    private var searing = false
    /** Knocked out of the sky, and not down yet. */
    private var crashing = false
    /** When each creature was last burnt by its breath, so a stream lands every few ticks rather than every tick. */
    private val singed = HashMap<Int, Int>()

    // On a client: its gait, and the lean of its flight
    var stride = 0f
        private set
    var strideO = 0f
        private set
    var gaitSpeed = 0f
        private set
    var gaitSpeedO = 0f
        private set
    var flightPitch = 0f
        private set
    var flightPitchO = 0f
        private set
    var flightRoll = 0f
        private set
    var flightRollO = 0f
        private set
    /** Smoothed speed through the air and rate of climb, in blocks a tick (the flight loops blend by these). */
    var airSpeed = 0f
        private set
    var climb = 0f
        private set
    /** How far it has settled into sleep: 0 awake, 1 curled up. */
    var sleepBlend = 0f
        private set
    var sleepBlendO = 0f
        private set
    /** How far its pose has gone over from its loops on foot to its loops on the wing: 0 on the ground, 1 in the air. */
    var airBlend = 0f
        private set
    var airBlendO = 0f
        private set
    /** Ticks its flight loops run ahead of its age, so the hover takes up its wings where the takeoff leaves them. */
    var airPhase = 0
        private set
    /** The last tick the camera was shaken for (see DragonClient). */
    var shookAt = -1
    private var sighted = false

    private val bossEvent = ServerBossEvent(
        Mth.createInsecureUUID(random), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10,
    )

    /** A patch of ground its fire has caught: it burns whoever stands in it for a while. */
    private class Patch(val pos: Vec3, val radius: Double, val until: Int)

    /** What it's doing on the wing. */
    private enum class AirMove { NONE, CLIMB, CIRCLE, STRAFE_TO, STRAFE_RUN, HOVER, DIVE_TO, LAND_TO, PATROL, HOME }

    init {
        xpReward = 800
        moveControl = DragonMoveControl(this)
    }

    /** "Vorthrax the Cinder-Crowned". */
    val title: Component get() = Component.translatable("$NAME_KEY.$nameIndex")

    override fun canFight(): Boolean = fighting

    override fun createBodyControl(): BodyRotationControl = object : BodyRotationControl(this) {
        // Its body always turns with it; only its neck looks about
        override fun clientTick() {
            yBodyRot = yRot
        }
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_AIM, 20f)
    }

    override fun registerGoals() {
        // It needs no goals: it decides everything itself (see customServerAiStep)
    }

    override fun finalizeSpawn(level: ServerLevelAccessor, difficulty: DifficultyInstance, spawnReason: EntitySpawnReason, groupData: SpawnGroupData?): SpawnGroupData? {
        val data = super.finalizeSpawn(level, difficulty, spawnReason, groupData)
        nameIndex = random.nextInt(NAMES)
        setPersistenceRequired()
        bossEvent.name = title
        return data
    }

    /** Toughens it for land [tier] (dragons nest only out where the land is tier 2 or more). */
    fun scaleTo(tier: Int) {
        val extra = (tier - 1).coerceAtLeast(0)
        if (extra == 0) return
        getAttribute(Attributes.MAX_HEALTH)?.addOrReplacePermanentModifier(AttributeModifier(TIER_HEALTH, 0.35 * extra, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        getAttribute(Attributes.ATTACK_DAMAGE)?.addOrReplacePermanentModifier(AttributeModifier(TIER_DAMAGE, 0.15 * extra, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        health = maxHealth
    }

    /** Puts it to sleep on its nest at [pos], lying the way [yaw] points. */
    fun settleAt(pos: BlockPos, yaw: Float, sleeping: Boolean) {
        home = pos
        homeYaw = yaw
        setFlag(ASLEEP, sleeping)
    }

    // ---- Every tick ----

    override fun tick() {
        super.tick()
        if (level().isClientSide) clientTick()
    }

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        if (reloaded) {
            reloaded = false
            afterReload()
        }
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
        if (crashing && (onGround() || isInWater)) {
            crashing = false
            impact(level, position(), 2f, true)
        }
        burnPatches(level)
        if (fighting) fight(level) else idle(level)
        if (flying) fly(level)
    }

    // A flier steers itself: no gravity and no drag, only the velocity its flight sets each tick
    override fun travel(input: Vec3) {
        if (!flying || isDeadOrDying) {
            super.travel(input)
            return
        }
        move(MoverType.SELF, deltaMovement)
        resetFallDistance()
    }

    override fun causeFallDamage(fallDistance: Double, multiplier: Float, source: DamageSource): Boolean = false

    private fun setFlying(on: Boolean) {
        setFlag(FLYING, on)
        isNoGravity = on
        if (on) {
            navigation.stop()
            moveControl.setWait()
            setFlag(ASLEEP, false)
        } else {
            air = AirMove.NONE
            airPlan.clear()
        }
    }

    // ---- In a fight ----

    private fun fight(level: ServerLevel) {
        // Anyone it sees close by joins the fight
        if (tickCount % 5 == 0) {
            for (player in level.players()) {
                if (fair(player) && player.distanceToSqr(this) < NOTICE_AWAKE * NOTICE_AWAKE && hasLineOfSight(player)) join(player)
            }
        }
        val alive = livingFoes(level)
        if (alive.isEmpty()) {
            calmDown(level)
            return
        }
        val watchers = level.players().filter { it.isAlive && !it.isSpectator && it.distanceToSqr(this) < FIGHT_RANGE * FIGHT_RANGE }
        for (player in bossEvent.players.toList()) if (player !in watchers) bossEvent.removePlayer(player)
        for (player in watchers) if (player !in bossEvent.players) bossEvent.addPlayer(player)
        val home = home
        val near = alive.filter { it.distanceToSqr(this) < CHASE_RANGE * CHASE_RANGE && (home == null || flat(it.position(), home) < LEASH) }
        if (near.isEmpty()) {
            // They've got clear of its peak: it gives the fight up if they don't come back
            target = null
            if (++lonely >= LOST_TICKS) calmDown(level)
            return
        }
        lonely = 0
        val current = target as? Player
        if (current == null || current !in near) target = near.minBy { it.distanceToSqr(this) }
        val target = target ?: return
        // Gone to ground where it can't see them (a cave, a roof): it won't wait there to be shot at
        if (tickCount % 20 == 0) {
            unseen = if (hasLineOfSight(target)) 0 else unseen + 1
            if (unseen >= UNSEEN_SECONDS) {
                calmDown(level)
                aloof = ALOOF_TICKS
                return
            }
        }
        if (Stance.isStaggered(this)) return
        if (!phaseTwo && health <= maxHealth * PHASE_TWO_AT) pendingPhaseTwo = true
        if (!enraged && health <= maxHealth * ENRAGE_AT) pendingEnrage = true
        if (attack != null) return
        if (pendingEnrage) {
            // It comes down to fight to the end
            if (!flying) startAttack(ENRAGE)
            else if (air != AirMove.LAND_TO) {
                airPlan.clear()
                enterAir(level, AirMove.LAND_TO)
            }
            return
        }
        if (pendingPhaseTwo) {
            startAttack(if (flying) PHASE_ROAR_AIR else PHASE_ROAR)
            return
        }
        if (!greeted) {
            startAttack(if (flying) ROAR_AIR else ROAR)
            return
        }
        if (!flying) groundFight(level, target)
    }

    private fun groundFight(level: ServerLevel, target: LivingEntity) {
        groundTicks++
        val distance = distanceTo(target) - target.bbWidth / 2 - bbWidth / 2
        val seen = sensing.hasLineOfSight(target)
        lookControl.setLookAt(target, 30f, 30f)
        headway(target, distance)
        val above = target.y - y
        val unreachable = above > REACH_ABOVE || above < -REACH_BELOW || stuck >= STUCK_LIMIT
        if (wantsToFly(distance, unreachable)) {
            startAttack(TAKEOFF)
            return
        }
        if (cooldown <= 0 && seen) {
            chooseAttack(target, distance.toDouble())?.let {
                startAttack(it)
                return
            }
        }
        if (distance > waitRange || cooldown <= 0) {
            moveControl.setWantedPosition(target.x, target.y, target.z, chaseSpeed)
        } else {
            // Waiting its turn: it squares up to its foe
            moveControl.setWait()
            face(target, 5f)
        }
    }

    /** Keeps count of how long it has walked at its foe without getting any nearer. */
    private fun headway(target: LivingEntity, distance: Float) {
        if (tickCount % 20 != 0) return
        val from = headwayFrom
        headwayFrom = position()
        if (from == null || distance <= waitRange + 2.0) {
            stuck = 0
            return
        }
        stuck = if (from.distanceTo(position()) < 1.0) stuck + 20 else 0
    }

    private fun wantsToFly(distance: Float, unreachable: Boolean): Boolean {
        if (!roomToFly()) return false
        if (isInWater || isInLava) return true
        if (enraged) return unreachable
        return unreachable || distance > FLY_AFTER_DISTANCE || groundTicks >= groundLimit
    }

    /** Open sky over it, enough to spread its wings and rise. */
    private fun roomToFly(): Boolean = level().noCollision(this, boundingBox.move(0.0, 5.0, 0.0).inflate(1.5, 0.0, 1.5))

    override fun chooseAttack(target: LivingEntity, distance: Double): EnemyAttack? {
        if (flying) return null
        // Anyone round its flanks or behind it gets the tail
        val angle = Combat.angleTo(yRot, target.x - x, target.z - z)
        if (angle > 100f && distance <= TAIL_SWEEP.maxRange && random.nextInt(3) != 0) return TAIL_SWEEP
        return super.chooseAttack(target, distance)
    }

    override fun usable(attack: EnemyAttack, target: LivingEntity, distance: Double): Boolean {
        if (flying) return false
        val angle = Combat.angleTo(yRot, target.x - x, target.z - z)
        when (attack) {
            BITE, CLAW, STOMP -> if (angle > 60f) return false
            WING_BUFFET -> if (angle > 45f) return false
            BREATH -> if (angle > 70f || !hasLineOfSight(target)) return false
            TAIL_SWEEP -> if (angle < 50f && distance > 1.5) return false
        }
        // Up on a ledge out of reach of its jaws, only its fire gets there
        if (target.y - y > REACH_ABOVE) return attack === BREATH && distance <= attack.maxRange
        return super.usable(attack, target, distance)
    }

    /** Players it would fight. */
    private fun fair(player: Player): Boolean = player.isAlive && !player.isSpectator && !player.isCreative

    private fun join(player: Player) {
        if (fair(player)) foes[player.uuid] = player.id
    }

    /** The players in the fight still alive and about; anyone who has died since they joined it is let go. */
    private fun livingFoes(level: ServerLevel): List<Player> {
        val alive = ArrayList<Player>()
        val it = foes.entries.iterator()
        while (it.hasNext()) {
            val (uuid, id) = it.next()
            val player = level.getPlayerByUUID(uuid)
            if (player == null || player.id != id || !fair(player)) it.remove() else alive.add(player)
        }
        return alive
    }

    /** The player behind [source]: by their own hand, an arrow of theirs or one of their animals. */
    private fun attacker(source: DamageSource): Player? = when (val by = source.entity) {
        is Player -> by
        is OwnableEntity -> by.owner as? Player
        else -> null
    }

    private fun startFight(level: ServerLevel, by: Player?) {
        if (by != null) {
            join(by)
            target = by
        }
        if (fighting) return
        setFlag(FIGHTING, true)
        lonely = 0
        unseen = 0
        stuck = 0
        groundTicks = 0
        groundLimit = nextGroundLimit()
        cooldown = 30
        greeted = false
        bossEvent.name = title
        if (asleep) {
            setFlag(ASLEEP, false)
            if (attack == null && !flying) startAttack(WAKE)
        }
        if (flying) {
            airPlan.clear()
            planFlight(level)
        }
    }

    /** The fight is over: it heals, flies home and settles on its nest. */
    private fun calmDown(level: ServerLevel) {
        lonely = 0
        unseen = 0
        stuck = 0
        if (attack != null) stopAttack(true)
        bossEvent.removeAllPlayers()
        foes.clear()
        patches.clear()
        health = maxHealth
        setFlag(FIGHTING, false)
        setFlag(PHASE_TWO, false)
        setFlag(ENRAGED, false)
        pendingPhaseTwo = false
        pendingEnrage = false
        target = null
        restTicks = REST_MIN + random.nextInt(REST_SPREAD)
        if (flying) {
            airPlan.clear()
            enterAir(level, AirMove.HOME)
        }
        level.playSound(null, x, y, z, DragonRegistry.GROWL.get(), SoundSource.HOSTILE, 3f, 0.8f)
    }

    // ---- Out of a fight ----

    private fun idle(level: ServerLevel) {
        target = null
        if (aloof > 0) aloof--
        if (attack != null) return
        val home = home ?: blockPosition().also {
            this.home = it
            homeYaw = yRot
        }
        if (flying) {
            if (air == AirMove.NONE) planFlight(level)
            notice(level)
            return
        }
        val away = flat(position(), home)
        if (away > 3.5 || abs(y - home.y) > 2.5) {
            if ((away > 18.0 || abs(y - home.y) > 2.5) && roomToFly()) startAttack(TAKEOFF)
            else moveControl.setWantedPosition(home.x + 0.5, home.y.toDouble(), home.z + 0.5, 0.7)
            notice(level)
            return
        }
        // At home: it lies the way it likes, and sleeps the night through
        moveControl.setWait()
        yRot += Mth.clamp(Mth.wrapDegrees(homeYaw - yRot), -2f, 2f)
        val night = !level.isBrightOutside
        if (night && !asleep) setFlag(ASLEEP, true)
        if (!night && asleep && random.nextInt(400) == 0) setFlag(ASLEEP, false)
        if (!asleep && --restTicks <= 0) {
            // Off to circle its peak a while
            restTicks = REST_MIN + random.nextInt(REST_SPREAD)
            if (roomToFly()) startAttack(TAKEOFF)
        }
        notice(level)
    }

    /** The first player it spots starts a fight: from far off on the wing, less far awake, close by asleep (closer still if they creep). */
    private fun notice(level: ServerLevel) {
        if (aloof > 0 || tickCount % 10 != 0) return
        val seen = level.players()
            .filter { fair(it) && it.distanceToSqr(this) < noticeRange(it).let { r -> r * r } && hasLineOfSight(it) }
            .minByOrNull { it.distanceToSqr(this) }
        if (seen != null) startFight(level, seen)
    }

    private fun noticeRange(player: Player): Double = when {
        flying -> NOTICE_FLYING
        asleep -> if (player.isCrouching) NOTICE_ASLEEP_CREEPING else NOTICE_ASLEEP
        else -> NOTICE_AWAKE
    }

    /** Back from a save: whole, out of any fight, and on its way home. */
    private fun afterReload() {
        if (!onGround() && !isInWater) {
            setFlying(true)
            air = AirMove.NONE
        }
    }

    // ---- On the wing ----

    /** What it does with this flight: a fight's passes and dives, or a turn round its peak and home. */
    private fun planFlight(level: ServerLevel) {
        airPlan.clear()
        airTicks = 0
        val first: AirMove
        if (fighting) {
            first = AirMove.CLIMB
            when {
                enraged -> airPlan.addAll(listOf(AirMove.STRAFE_TO, AirMove.LAND_TO))
                phaseTwo -> {
                    airPlan.addAll(listOf(AirMove.CIRCLE, AirMove.STRAFE_TO, AirMove.HOVER, AirMove.STRAFE_TO))
                    airPlan.add(AirMove.DIVE_TO)
                }
                else -> {
                    airPlan.add(AirMove.STRAFE_TO)
                    airPlan.add(if (random.nextBoolean()) AirMove.CIRCLE else AirMove.HOVER)
                    airPlan.add(if (random.nextInt(3) == 0) AirMove.DIVE_TO else AirMove.LAND_TO)
                }
            }
        } else {
            first = if (home != null && flat(position(), home!!) < 64.0 && patrolTicks == 0 && random.nextInt(3) != 0) AirMove.PATROL else AirMove.HOME
            if (first == AirMove.PATROL) airPlan.add(AirMove.HOME)
        }
        enterAir(level, first)
    }

    private fun nextAirMove(level: ServerLevel) {
        val next = airPlan.removeFirstOrNull()
        if (next != null) {
            enterAir(level, next)
            return
        }
        if (!fighting) {
            enterAir(level, AirMove.HOME)
            return
        }
        // A flight in a fight always ends on the ground: diving on them if it can, else landing near
        val target = target
        enterAir(level, if (target != null && hasLineOfSight(target) && !enraged && random.nextBoolean()) AirMove.DIVE_TO else AirMove.LAND_TO)
    }

    private fun enterAir(level: ServerLevel, move: AirMove) {
        air = move
        airTimer = 0
        val target = target
        val here = position()
        when (move) {
            AirMove.NONE -> {}
            AirMove.CLIMB -> {
                val look = Vec3.directionFromRotation(0f, yRot)
                val floor = groundAt(level, x, z) ?: blockY
                airGoal = Vec3(x + look.x * 10, max(y + 8.0, floor + 14.0), z + look.z * 10)
            }
            AirMove.CIRCLE -> {
                val center = target?.position() ?: home?.let { Vec3.atBottomCenterOf(it) } ?: here
                circleCenter = center
                circleRadius = if (fighting) 22.0 + random.nextInt(6) else 34.0 + random.nextInt(10)
                circleHeight = if (fighting) 14.0 else 22.0
                circleDir = if (random.nextBoolean()) 1.0 else -1.0
                circleSwept = 0.0
                circleGoal = if (fighting) Math.PI * (1.0 + random.nextDouble()) else Math.PI * 2 * (1.5 + random.nextDouble())
                circleLast = atan2(z - center.z, x - center.x)
            }
            AirMove.PATROL -> {
                enterAir(level, AirMove.CIRCLE)
                air = AirMove.PATROL
                patrolTicks = PATROL_MIN + random.nextInt(PATROL_SPREAD)
            }
            AirMove.STRAFE_TO -> {
                if (target == null) return nextAirMove(level)
                // A pass straight over them, from the side it's on: it swings out first if it's too close to line up
                var dx = target.x - x
                var dz = target.z - z
                val d = sqrt(dx * dx + dz * dz)
                if (d < 6.0) {
                    val look = Vec3.directionFromRotation(0f, yRot)
                    dx = look.x
                    dz = look.z
                } else {
                    dx /= d
                    dz /= d
                }
                passDir = Vec3(dx, 0.0, dz)
                val height = target.y + STRAFE_HEIGHT
                passStart = clearOf(level, Vec3(target.x - dx * PASS_BEFORE, height, target.z - dz * PASS_BEFORE), STRAFE_HEIGHT.toInt() - 2)
                passEnd = clearOf(level, Vec3(target.x + dx * PASS_AFTER, height, target.z + dz * PASS_AFTER), STRAFE_HEIGHT.toInt() - 2)
                airGoal = passStart
                breathed = false
            }
            AirMove.STRAFE_RUN -> airGoal = passEnd
            AirMove.HOVER -> {
                if (target == null) return nextAirMove(level)
                val away = here.subtract(target.position()).let { Vec3(it.x, 0.0, it.z) }.let { if (it.lengthSqr() < 1.0) Vec3(1.0, 0.0, 0.0) else it.normalize() }
                airGoal = clearOf(level, Vec3(target.x + away.x * HOVER_DISTANCE, target.y + HOVER_HEIGHT, target.z + away.z * HOVER_DISTANCE), 6)
                breathed = false
            }
            AirMove.DIVE_TO -> {
                if (target == null) return nextAirMove(level)
                val away = here.subtract(target.position()).let { Vec3(it.x, 0.0, it.z) }.let { if (it.lengthSqr() < 1.0) Vec3(1.0, 0.0, 0.0) else it.normalize() }
                airGoal = clearOf(level, Vec3(target.x + away.x * 18, target.y + 12, target.z + away.z * 18), 8)
            }
            AirMove.LAND_TO -> {
                landTries = 0
                landing = findLanding(level, target?.position() ?: here)
                airGoal = landing?.add(0.0, 5.0, 0.0) ?: here
            }
            AirMove.HOME -> {
                val home = home
                if (home == null) {
                    enterAir(level, AirMove.LAND_TO)
                    return
                }
                landing = Vec3.atBottomCenterOf(home)
                airGoal = landing!!.add(0.0, 6.0, 0.0)
            }
        }
    }

    /** Every tick on the wing: steers by what it's doing, and starts its air attacks when they line up. */
    private fun fly(level: ServerLevel) {
        resetFallDistance()
        val a = attack
        // Taking off, landing and diving move it themselves
        if (a === TAKEOFF || a === LAND || a === DIVE) return
        airTicks++
        airTimer++
        val target = if (fighting) target else null
        if (fighting && airTicks > MAX_AIR_TICKS && air != AirMove.LAND_TO) {
            airPlan.clear()
            enterAir(level, AirMove.LAND_TO)
        }
        when (air) {
            AirMove.NONE -> {
                steer(level, position(), 0.0)
                if (fighting) planFlight(level)
            }
            AirMove.CLIMB -> {
                steer(level, airGoal, CRUISE)
                if (y >= airGoal.y - 1.5 || airTimer > 80) nextAirMove(level)
            }
            AirMove.CIRCLE, AirMove.PATROL -> {
                if (air == AirMove.CIRCLE && fighting && target != null) circleCenter = circleCenter.lerp(target.position(), 0.05)
                val angle = atan2(z - circleCenter.z, x - circleCenter.x)
                circleSwept += abs(Mth.wrapDegrees(Math.toDegrees(angle - circleLast))) * Math.PI / 180
                circleLast = angle
                val ahead = angle + circleDir * 0.55
                val goal = clearOf(level, Vec3(circleCenter.x + cos(ahead) * circleRadius, circleCenter.y + circleHeight, circleCenter.z + sin(ahead) * circleRadius), 8)
                steer(level, goal, CIRCLE_SPEED)
                val done = if (air == AirMove.PATROL) --patrolTicks <= 0 else circleSwept >= circleGoal || airTimer > 400
                if (done) {
                    patrolTicks = 0
                    nextAirMove(level)
                }
            }
            AirMove.STRAFE_TO -> {
                steer(level, airGoal, CRUISE)
                if (flat(position(), airGoal) < 7.0 || airTimer > 240) enterAir(level, AirMove.STRAFE_RUN)
            }
            AirMove.STRAFE_RUN -> {
                steer(level, airGoal, STRAFE_SPEED)
                // Its fire starts well short of them, so the stream rolls over where they stand
                if (target != null && !breathed && a == null) {
                    val ahead = (target.x - x) * passDir.x + (target.z - z) * passDir.z
                    if (ahead in BREATH_LEAD_MIN..BREATH_LEAD_MAX && hasLineOfSight(target)) {
                        breathed = true
                        startAttack(AIR_BREATH)
                    }
                }
                if ((flat(position(), airGoal) < 6.0 && a !== AIR_BREATH) || airTimer > 260) nextAirMove(level)
            }
            AirMove.HOVER -> {
                val there = position().distanceTo(airGoal) < 3.5
                steer(level, airGoal, if (there) 0.05 else 0.6)
                if (there && target != null && a == null && !breathed && airTimer > 20 && hasLineOfSight(target)) {
                    breathed = true
                    startAttack(if (phaseTwo || enraged) FIREBALL_VOLLEY else FIREBALL)
                } else if ((breathed && a == null) || airTimer > 240) nextAirMove(level)
            }
            AirMove.DIVE_TO -> {
                steer(level, airGoal, CRUISE)
                if (target == null) {
                    nextAirMove(level)
                    return
                }
                val across = flat(position(), target.position())
                val drop = y - target.y
                if (a == null && airTimer > 10 && across in 8.0..30.0 && drop >= 5.0 && hasLineOfSight(target)) startAttack(DIVE)
                else if (airTimer > 220) enterAir(level, AirMove.LAND_TO)
            }
            AirMove.LAND_TO, AirMove.HOME -> {
                if (landing == null && airTimer % 20 == 0) {
                    landing = findLanding(level, target?.position() ?: position())
                    if (landing == null && ++landTries >= 3) landing = groundBelow(level)
                    landing?.let { airGoal = it.add(0.0, 5.0, 0.0) }
                }
                val near = flat(position(), airGoal)
                steer(level, airGoal, if (near < 12.0) 0.3 + near * 0.04 else CRUISE)
                if (landing != null && a == null && near < 2.5 && abs(y - airGoal.y) < 3.5) startAttack(LAND)
                // Stuck circling a spot it can't get down to: try another
                if (airTimer > 300) {
                    landing = null
                    airTimer = 0
                }
            }
        }
        faceFlight(target)
    }

    /** Eases its velocity toward [dest] at up to [speed] blocks a tick, keeping clear of the ground under it and ahead. */
    private fun steer(level: ServerLevel, dest: Vec3, speed: Double) {
        val here = position()
        val to = dest.subtract(here)
        val dist = to.length()
        var want = if (dist < 0.01) Vec3.ZERO else to.scale(min(speed, 0.1 + dist * 0.12) / dist)
        val v = deltaMovement
        val ahead = here.add(v.x * 12, 0.0, v.z * 12)
        val floor = max(groundAt(level, here.x, here.z) ?: level.minY, groundAt(level, ahead.x, ahead.z) ?: level.minY)
        if (here.y < floor + MIN_CLEARANCE && dist > 4.0) want = Vec3(want.x * 0.6, max(want.y, 0.35), want.z * 0.6)
        var dv = want.subtract(v)
        if (dv.length() > ACCEL) dv = dv.normalize().scale(ACCEL)
        deltaMovement = v.add(dv)
        // Clipped a cliff or a tree: up and over
        if (horizontalCollision) deltaMovement = Vec3(deltaMovement.x * 0.5, max(deltaMovement.y, 0.4), deltaMovement.z * 0.5)
    }

    /** Faces where it's flying, or its foe while it hovers. */
    private fun faceFlight(target: LivingEntity?) {
        val v = deltaMovement
        val speed = v.horizontalDistance()
        val wanted = when {
            speed > 0.2 -> yawTo(v.x, v.z)
            target != null -> yawTo(target.x - x, target.z - z)
            else -> yRot
        }
        yRot += Mth.clamp(Mth.wrapDegrees(wanted - yRot), -TURN_AIR, TURN_AIR)
        yBodyRot = yRot
        if (target != null) lookControl.setLookAt(target, 30f, 30f) else yHeadRot = yRot
    }

    /** Somewhere near [near] it can come down: firm dry ground with room for it, a few blocks from them. */
    private fun findLanding(level: ServerLevel, near: Vec3): Vec3? {
        val start = random.nextInt(8)
        for (r in doubleArrayOf(8.0, 11.0, 5.0, 15.0)) for (k in 0 until 8) {
            val a = (start + k) * Math.PI / 4
            val x = near.x + cos(a) * r
            val z = near.z + sin(a) * r
            val spot = landable(level, x, z) ?: continue
            if (abs(spot.y - near.y) > 6.0 && fighting) continue
            return spot
        }
        return null
    }

    /** The ground straight under it, if it's somewhere to land. */
    private fun groundBelow(level: ServerLevel): Vec3? = landable(level, x, z)

    /** The top of the ground at [x], [z] if it can land there: loaded, dry, near enough flat and open above. */
    private fun landable(level: ServerLevel, x: Double, z: Double): Vec3? {
        val ground = groundAt(level, x, z) ?: return null
        val pos = BlockPos.containing(x, ground.toDouble(), z)
        if (!level.getFluidState(pos.below()).isEmpty || !level.getFluidState(pos).isEmpty) return null
        if (level.getBlockState(pos.below()).isAir) return null
        for (dx in intArrayOf(-2, 2)) for (dz in intArrayOf(-2, 2)) {
            val corner = groundAt(level, x + dx, z + dz) ?: return null
            if (abs(corner - ground) > 2) return null
        }
        val box = AABB(x - 1.8, ground + 0.6, z - 1.8, x + 1.8, ground + 4.5, z + 1.8)
        if (!level.noCollision(this, box)) return null
        return Vec3(x, ground.toDouble(), z)
    }

    /** [point], raised if need be to keep [clearance] blocks over the ground under it. */
    private fun clearOf(level: ServerLevel, point: Vec3, clearance: Int): Vec3 {
        val ground = groundAt(level, point.x, point.z) ?: return point
        return if (point.y < ground + clearance) Vec3(point.x, (ground + clearance).toDouble(), point.z) else point
    }

    /** The height of the ground at [x], [z] (the first open block), or null where the land isn't loaded. */
    private fun groundAt(level: ServerLevel, x: Double, z: Double): Int? {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        if (!level.hasChunk(bx shr 4, bz shr 4)) return null
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
    }

    private fun nextGroundLimit(): Int = if (phaseTwo) 200 + random.nextInt(120) else 360 + random.nextInt(200)

    // ---- Attacks ----

    override fun onAttackStart(attack: EnemyAttack) {
        val level = level() as? ServerLevel ?: return
        when (attack) {
            WAKE -> setFlag(ASLEEP, false)
            ROAR, ROAR_AIR -> greeted = true
            PHASE_ROAR, PHASE_ROAR_AIR -> {
                greeted = true
                pendingPhaseTwo = false
            }
            ENRAGE -> {
                greeted = true
                pendingEnrage = false
                pendingPhaseTwo = false
                setFlag(PHASE_TWO, true)
            }
            BREATH -> {
                aimAt(target, GROUND_MOUTH_UP, GROUND_MOUTH_FORWARD, 8f, 40f)
                level.playSound(null, x, y, z, DragonRegistry.INHALE.get(), SoundSource.HOSTILE, 3f, 1f)
            }
            AIR_BREATH -> {
                entityData.set(DATA_AIM, AIR_BREATH_PITCH)
                level.playSound(null, x, y, z, DragonRegistry.INHALE.get(), SoundSource.HOSTILE, 3f, 1.15f)
            }
            LAND -> landFrom = position()
            else -> {}
        }
    }

    override fun onAttackTick(level: ServerLevel, attack: EnemyAttack, tick: Int) {
        when (attack) {
            WAKE -> if (tick == 14) sound(level, DragonRegistry.GROWL.get(), 3f, 0.9f)
            ROAR -> if (tick == 8) roar(level, false)
            PHASE_ROAR -> {
                if (tick == 8) roar(level, true)
                if (tick == 12) setFlag(PHASE_TWO, true)
                // Its second phase opens on the wing
                if (tick == attack.length - 1) groundTicks = groundLimit
            }
            ROAR_AIR -> if (tick == 6) roar(level, false)
            PHASE_ROAR_AIR -> {
                if (tick == 6) roar(level, true)
                if (tick == 10) {
                    setFlag(PHASE_TWO, true)
                    planFlight(level)
                }
            }
            BITE -> if (tick == 15) sound(level, DragonRegistry.BITE.get(), 2.5f, 0.9f + random.nextFloat() * 0.15f)
            CLAW -> if (tick == 15) sound(level, DragonRegistry.SWIPE.get(), 2.5f, 0.9f)
            TAIL_SWEEP -> if (tick == 19) sound(level, DragonRegistry.SWIPE.get(), 3f, 0.7f)
            STOMP -> if (tick == 22) impact(level, ahead(2.6), 1.6f, true)
            WING_BUFFET -> {
                if (tick == 15) sound(level, DragonRegistry.WING_FLAP.get(), 4f, 0.75f)
                if (tick == 18) gust(level)
            }
            BREATH -> {
                if (tick < 24) aimAt(target, GROUND_MOUTH_UP, GROUND_MOUTH_FORWARD, 8f, 40f)
                if (tick == BREATH_FROM) sound(level, DragonRegistry.BREATH.get(), 4f, 0.95f)
                if (tick in BREATH_FROM..BREATH_TO) breathe(level, tick, false)
            }
            AIR_BREATH -> {
                if (tick == AIR_BREATH_FROM - 2) sound(level, DragonRegistry.BREATH.get(), 4.5f, 0.9f)
                if (tick in AIR_BREATH_FROM..AIR_BREATH_TO) breathe(level, tick, true)
            }
            FIREBALL -> if (tick == 18) spit(level, 0)
            FIREBALL_VOLLEY -> when (tick) {
                16 -> spit(level, 0)
                26 -> spit(level, 1)
                36 -> spit(level, 2)
            }
            TAKEOFF -> takeoff(level, tick)
            LAND -> land(level, tick)
            DIVE -> dive(level, tick)
            ENRAGE -> {
                if (tick == 10) roar(level, true)
                if (tick == 30) {
                    setFlag(ENRAGED, true)
                    sound(level, DragonRegistry.FIRE_BURST.get(), 4f, 0.7f)
                    level.sendParticles(ParticleTypes.FLAME, x, y + 2.5, z, 80, 1.6, 1.2, 1.6, 0.08)
                    level.sendParticles(ParticleTypes.LAVA, x, y + 2.5, z, 30, 1.4, 1.0, 1.4, 0.1)
                }
                if (tick == 36) {
                    impact(level, ahead(2.0), 2f, true)
                    // A ring of fire rolls out from where it lands
                    for (ring in doubleArrayOf(4.0, 6.5)) {
                        val count = (ring * 2).toInt()
                        for (i in 0 until count) {
                            val a = i * Math.PI * 2 / count
                            patchAt(level, Vec3(x + cos(a) * ring, y, z + sin(a) * ring), 1.7, ENRAGE_PATCH_TICKS)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    /** Takes off: a crouch, a leap that throws up the dust, a great downbeat, then two more beats to climb clear. */
    private fun takeoff(level: ServerLevel, tick: Int) {
        if (tick == 13) sound(level, DragonRegistry.WING_FLAP.get(), 4f, 0.8f)
        if (tick == 10) {
            dust(level, position(), 3.5, 24)
            setFlying(true)
            val look = Vec3.directionFromRotation(0f, yRot)
            deltaMovement = Vec3(look.x * 0.12, 0.8, look.z * 0.12)
            planFlight(level)
        }
        if (tick > 10 && flying) {
            val v = deltaMovement
            deltaMovement = Vec3(v.x * 0.92, max(0.16, v.y - 0.045), v.z * 0.92)
        }
        if (tick == 21 || tick == 29) sound(level, DragonRegistry.WING_FLAP.get(), 3.5f, 0.85f)
    }

    /** Comes down on [landing]: a hovering descent, touching down on tick 14 with a thud that throws dust about. */
    private fun land(level: ServerLevel, tick: Int) {
        val at = landing ?: groundBelow(level) ?: Vec3(x, y - 4.0, z)
        if (tick < LAND_AT) {
            val k = smooth((tick + 1f) / LAND_AT)
            val want = landFrom.lerp(at, k.toDouble())
            deltaMovement = want.subtract(position())
            if (tick == 7 || tick == 13) sound(level, DragonRegistry.WING_FLAP.get(), 3f, 0.9f)
        }
        if (tick == LAND_AT) {
            setFlying(false)
            deltaMovement = Vec3.ZERO
            landed(level)
        }
    }

    /** Folds its wings and drops on its foe from on high, landing talons first on tick 28. */
    private fun dive(level: ServerLevel, tick: Int) {
        val target = target
        if (tick < DIVE_FROM) {
            deltaMovement = deltaMovement.scale(0.82).add(0.0, 0.025, 0.0)
            if (target != null) turnToward(target.x, target.z, 12f)
            return
        }
        if (tick == DIVE_FROM) {
            val aim = if (target != null) {
                val lead = Vec3(target.x - target.xo, 0.0, target.z - target.zo).scale(6.0)
                target.position().add(lead)
            } else position().add(Vec3.directionFromRotation(0f, yRot).scale(14.0))
            diveTo = landable(level, aim.x, aim.z) ?: groundAt(level, aim.x, aim.z)?.let { Vec3(aim.x, it.toDouble(), aim.z) } ?: aim
            sound(level, DragonRegistry.FLYBY.get(), 4f, 0.8f)
        }
        val to = diveTo ?: position()
        if (tick < DIVE_AT) {
            deltaMovement = to.subtract(position()).scale(1.0 / (DIVE_AT - tick))
            turnToward(to.x, to.z, 20f)
        }
        if (tick == DIVE_AT) {
            setFlying(false)
            deltaMovement = Vec3.ZERO
            landed(level)
        }
    }

    /** Down on the ground again: the thud, and its time on foot starts over. */
    private fun landed(level: ServerLevel) {
        impact(level, position(), 1.8f, true)
        groundTicks = 0
        groundLimit = nextGroundLimit()
        stuck = 0
        headwayFrom = null
        airTicks = 0
        landing = null
        // Home again: it settles on its nest
        val home = home
        if (!fighting && home != null && flat(position(), home) < 4.0) restTicks = REST_MIN + random.nextInt(REST_SPREAD)
    }

    private fun turnToward(tx: Double, tz: Double, step: Float) {
        yRot += Mth.clamp(Mth.wrapDegrees(yawTo(tx - x, tz - z) - yRot), -step, step)
        yBodyRot = yRot
        yHeadRot = yRot
    }

    /** A roar that carries for a hundred blocks; anyone close by is shaken by it. */
    private fun roar(level: ServerLevel, mighty: Boolean) {
        level.playSound(null, x, y, z, DragonRegistry.ROAR.get(), SoundSource.HOSTILE, if (mighty) 7f else 6f, if (mighty) 0.85f else 0.95f)
        for (player in level.players()) {
            if (fair(player) && player.distanceToSqr(this) < ROAR_RANGE * ROAR_RANGE) {
                player.addEffect(MobEffectInstance(MobEffects.SLOWNESS, if (mighty) 50 else 30, 1), this)
            }
        }
    }

    /** Its wings' downbeat: a gust in a wedge in front of it (the blow does the throwing back). */
    private fun gust(level: ServerLevel) {
        val look = Vec3.directionFromRotation(0f, yRot)
        for (i in 1..6) {
            val d = i * 1.4
            level.sendParticles(ParticleTypes.CLOUD, x + look.x * d, y + 0.6, z + look.z * d, 6, 0.3 + i * 0.25, 0.3, 0.3 + i * 0.25, 0.08)
        }
    }

    /** Feet, tail or body meeting the ground hard: the sound, dust, and burning cinders once it's enraged. */
    private fun impact(level: ServerLevel, at: Vec3, volume: Float, quake: Boolean) {
        level.playSound(null, at.x, at.y, at.z, DragonRegistry.SLAM.get(), SoundSource.HOSTILE, volume * 2f, 0.85f + random.nextFloat() * 0.1f)
        dust(level, at, if (quake) 2.8 else 1.6, if (quake) 40 else 20)
        if (enraged) level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.2, at.z, 24, 1.4, 0.1, 1.4, 0.04)
    }

    private fun dust(level: ServerLevel, at: Vec3, spread: Double, count: Int) {
        val ground = level.getBlockState(BlockPos.containing(at.x, at.y - 0.5, at.z))
        if (!ground.isAir) level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1, at.z, count, spread, 0.1, spread, 0.15)
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, count / 3, spread, 0.05, spread, 0.04)
    }

    private fun sound(level: ServerLevel, event: SoundEvent, volume: Float, pitch: Float) {
        level.playSound(null, x, y, z, event, SoundSource.HOSTILE, volume, pitch)
    }

    private fun ahead(distance: Double): Vec3 = position().add(Vec3.directionFromRotation(0f, yRot).scale(distance))

    // ---- Fire ----

    /** Points its fire from a mouth [up] blocks high and [forward] ahead at [target]'s feet, within [min] to [max] degrees below level. */
    private fun aimAt(target: LivingEntity?, up: Double, forward: Double, min: Float, max: Float) {
        if (target == null) return
        val dist = (sqrt((target.x - x) * (target.x - x) + (target.z - z) * (target.z - z)) - forward).coerceAtLeast(1.0)
        val drop = y + up - (target.y + 0.4)
        entityData.set(DATA_AIM, Math.toDegrees(atan2(drop, dist)).toFloat().coerceIn(min, max))
    }

    /**
     * Where the fire comes out on [tick] of a breath: its mouth, in front of it, swept from its right to its
     * left across the ground breath (the same path the clip moves its head along, art/scripts/dragon_anims.py).
     */
    fun mouth(attack: EnemyAttack, tick: Float): Pair<Vec3, Vec3> {
        val air = attack === AIR_BREATH
        val sweep = if (air) 0f else breathSweep(tick)
        val base = Vec3.directionFromRotation(0f, yRot)
        val head = Vec3.directionFromRotation(0f, yRot + sweep)
        // Aimed lower than the clip's own stoop, its head tips down and its mouth with it
        val lower = if (air) 0.0 else (aimPitch - BREATH_HEAD_PITCH).toDouble()
        val forward = if (air) AIR_MOUTH_FORWARD else GROUND_MOUTH_FORWARD - lower * MOUTH_BACK
        val up = if (air) AIR_MOUTH_UP else GROUND_MOUTH_UP - lower * MOUTH_DROP
        val from = Vec3(x + base.x * NECK_BASE + head.x * (forward - NECK_BASE), y + up, z + base.z * NECK_BASE + head.z * (forward - NECK_BASE))
        return from to Vec3.directionFromRotation(aimPitch, yRot + sweep)
    }

    /**
     * The stream of fire on this [tick]: it burns everyone along it (no more than once every few ticks each),
     * stops at the first wall, and leaves the ground it lands on burning.
     */
    private fun breathe(level: ServerLevel, tick: Int, air: Boolean) {
        if (tick % 2 == 0 && !air) aimAt(target, GROUND_MOUTH_UP, GROUND_MOUTH_FORWARD, 8f, 40f)
        if (tick % 2 != 0) return
        val (from, dir) = mouth(if (air) AIR_BREATH else BREATH, tick.toFloat())
        val range = if (air) AIR_BREATH_RANGE else BREATH_RANGE
        val hit = level.clip(ClipContext(from, from.add(dir.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this))
        val end = hit.location
        val length = from.distanceTo(end).coerceAtLeast(0.1)
        val box = AABB(from, end).inflate(3.0)
        val damage = getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() * BREATH_DAMAGE
        for (victim in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
            if (victim === this || !victim.isAlive || !canHit(victim)) continue
            if (tickCount - (singed[victim.id] ?: -100) < SINGE_TICKS) continue
            val centre = victim.position().add(0.0, victim.bbHeight / 2.0, 0.0)
            val along = centre.subtract(from).dot(dir).coerceIn(0.0, length)
            val closest = from.add(dir.scale(along))
            val reach = 0.7 + 1.9 * (along / range) + victim.bbWidth / 2.0
            if (closest.distanceTo(centre) > reach) continue
            // Not through walls
            val seen = level.clip(ClipContext(from, centre, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
            if (seen.type != HitResult.Type.MISS) continue
            singed[victim.id] = tickCount
            burn(level, victim, damage, BREATH_BURN)
        }
        if (tick % 4 == 0 && hit.type == HitResult.Type.BLOCK && level.getFluidState(hit.blockPos).isEmpty) {
            patchAt(level, end, if (enraged) 2.2 else 1.8, if (enraged) ENRAGE_PATCH_TICKS else PATCH_TICKS)
        }
        if (singed.size > 64) singed.entries.removeIf { tickCount - it.value > SINGE_TICKS }
    }

    /** Burns [victim] for [damage] with dragonfire: no shield takes it, armour softens it, and a fire-resistance potion halves it. */
    fun burn(level: ServerLevel, victim: LivingEntity, damage: Float, seconds: Float) {
        val amount = if (victim.hasEffect(MobEffects.FIRE_RESISTANCE)) damage * 0.5f else damage
        searing = true
        val hurt = try {
            victim.hurtServer(level, fireSource(level), amount)
        } finally {
            searing = false
        }
        if (hurt) victim.igniteForSeconds(seconds)
    }

    /** Whether its fire burns [victim]: the same creatures its blows land on. */
    fun scorches(victim: LivingEntity): Boolean = canHit(victim)

    // Fire wears little at a player's footing: standing in it hurts, but it doesn't knock them reeling
    override fun poiseDamage(): Float = if (searing) SEAR_POISE else super.poiseDamage()

    private fun fireSource(level: ServerLevel): DamageSource {
        val types = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
        val type = types.get(DRAGON_FIRE).orElse(null) ?: types.getOrThrow(DamageTypes.IN_FIRE)
        return DamageSource(type, this)
    }

    /** A bolt of fire from its mouth at its foe, led a little; the second and third of a volley go wide of the first. */
    private fun spit(level: ServerLevel, index: Int) {
        val target = target ?: return
        val look = Vec3.directionFromRotation(0f, yRot)
        val from = Vec3(x + look.x * HOVER_MOUTH_FORWARD, y + HOVER_MOUTH_UP, z + look.z * HOVER_MOUTH_FORWARD)
        val flight = from.distanceTo(target.position()) / DragonFireball.SPEED
        val lead = Vec3(target.x - target.xo, 0.0, target.z - target.zo).scale(flight)
        val wide = when (index) {
            1 -> Vec3(-look.z, 0.0, look.x).scale(2.5)
            2 -> Vec3(look.z, 0.0, -look.x).scale(2.5)
            else -> Vec3.ZERO
        }
        val aim = target.position().add(0.0, target.bbHeight * 0.3, 0.0).add(lead).add(wide)
        val bolt = DragonFireball(level, this, from)
        bolt.deltaMovement = aim.subtract(from).normalize().scale(DragonFireball.SPEED)
        level.addFreshEntity(bolt)
        level.playSound(null, from.x, from.y, from.z, DragonRegistry.SPIT.get(), SoundSource.HOSTILE, 3f, 0.9f + random.nextFloat() * 0.15f)
    }

    /** Leaves the ground at [at] burning for [ticks]: its own fire bolts call this where they burst. */
    fun patchAt(level: ServerLevel, at: Vec3, radius: Double, ticks: Int) {
        // Settle it on the ground under the point
        val ground = groundAt(level, at.x, at.z)?.toDouble()
        val y = if (ground != null && abs(ground - at.y) < 3.0) ground else at.y
        patches.addLast(Patch(Vec3(at.x, y, at.z), radius, tickCount + ticks))
        while (patches.size > MAX_PATCHES) patches.removeFirst()
    }

    /** The burning ground: flames on it, and whoever stands in it burns. */
    private fun burnPatches(level: ServerLevel) {
        while (patches.isNotEmpty() && patches.first().until <= tickCount) patches.removeFirst()
        if (patches.isEmpty()) return
        for (patch in patches) {
            if ((tickCount + patch.until) % 4 == 0) {
                val r = patch.radius * 0.5
                level.sendParticles(ParticleTypes.FLAME, patch.pos.x, patch.pos.y + 0.15, patch.pos.z, 3, r, 0.05, r, 0.01)
                level.sendParticles(ParticleTypes.SMOKE, patch.pos.x, patch.pos.y + 0.4, patch.pos.z, 1, r, 0.1, r, 0.01)
                if (random.nextInt(6) == 0) level.sendParticles(ParticleTypes.LAVA, patch.pos.x, patch.pos.y + 0.1, patch.pos.z, 1, r, 0.0, r, 0.0)
            }
        }
        if (tickCount % 10 != 0) return
        val damage = getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() * PATCH_DAMAGE
        for (player in level.players()) {
            if (!fair(player)) continue
            val inside = patches.any { patch ->
                val dx = player.x - patch.pos.x
                val dz = player.z - patch.pos.z
                dx * dx + dz * dz < patch.radius * patch.radius && player.y > patch.pos.y - 1.0 && player.y < patch.pos.y + 1.6
            }
            if (inside) burn(level, player, damage, 2f)
        }
    }

    // ---- Damage ----

    /** Untouchable while it roars its way into a new phase. */
    private val transitioning: Boolean get() = attack === PHASE_ROAR || attack === PHASE_ROAR_AIR || attack === ENRAGE

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount)
        // Nothing the land itself does harms it: walls, falls, crowding, drowning
        if (source.`is`(DamageTypes.IN_WALL) || source.`is`(DamageTypes.FALL) || source.`is`(DamageTypes.CRAMMING) ||
            source.`is`(DamageTypes.FLY_INTO_WALL) || source.`is`(DamageTypes.DROWN)) return false
        if (transitioning) return false
        val player = attacker(source)
        // Out of a fight only a player can hurt it
        if (!fighting && player == null) return false
        val by = player?.takeIf { fair(it) }
        // Caught asleep, it takes the first blows hard
        val dealt = if (asleep && by != null) amount * SLEEPING_BLOW else amount
        val hurt = super.hurtServer(level, source, dealt)
        if (hurt && by != null) {
            if (!fighting) startFight(level, by)
            else {
                join(by)
                val current = target
                if (current == null || distanceToSqr(by) + 25.0 < distanceToSqr(current)) target = by
            }
        }
        return hurt
    }

    /** Its stance broken: it reels, and if it was on the wing it comes crashing down. */
    override fun onStaggered(ticks: Int, kind: Int) {
        super.onStaggered(ticks, kind)
        if (ticks > 0 && flying) {
            setFlying(false)
            crashing = true
        }
    }

    override fun canUsePortal(ignorePassenger: Boolean): Boolean = false

    override fun canBeLeashed(): Boolean = false

    override fun isPushable(): Boolean = false

    override fun die(source: DamageSource) {
        if (isRemoved || dead) return
        super.die(source)
        if (!dead) return
        val level = level() as? ServerLevel ?: return
        bossEvent.removeAllPlayers()
        setFlag(FIGHTING, false)
        // It falls out of the sky
        if (flying) setFlying(false)
        foes.clear()
        patches.clear()
        DragonRoosts.get(level).onSlain(level, this)
        for (player in level.players()) {
            if (player.distanceToSqr(this) < CUE_RANGE * CUE_RANGE) {
                PacketDistributor.sendToPlayer(player, BossCuePayload(id, BossCuePayload.FELLED))
                Combat.shieldForScene(player, FELLED_SCENE)
            }
        }
    }

    /** It thrashes, its legs give and it crashes down; a last breath of smoke, and it's gone to cinders. */
    override fun tickDeath() {
        deathTime++
        val level = level() as? ServerLevel ?: return
        if (deathTime == DEATH_SLAM) impact(level, position(), 2.2f, true)
        if (deathTime > DEATH_SLAM && deathTime % 4 == 0) level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1.5, z, 3, 1.6, 0.6, 1.6, 0.01)
        if (deathTime >= DEATH_TICKS && !isRemoved) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1.5, z, 60, 2.2, 1.0, 2.2, 0.03)
            level.sendParticles(ParticleTypes.FLAME, x, y + 1.0, z, 60, 2.2, 0.8, 2.2, 0.02)
            level.sendParticles(ParticleTypes.POOF, x, y + 1.0, z, 40, 2.2, 0.8, 2.2, 0.03)
            level.broadcastEntityEvent(this, 60.toByte())
            remove(RemovalReason.KILLED)
        }
    }

    override fun startSeenByPlayer(player: ServerPlayer) {
        super.startSeenByPlayer(player)
        if (isAlive && fighting && player.distanceToSqr(this) < FIGHT_RANGE * FIGHT_RANGE) bossEvent.addPlayer(player)
    }

    override fun stopSeenByPlayer(player: ServerPlayer) {
        super.stopSeenByPlayer(player)
        bossEvent.removePlayer(player)
    }

    override fun getMaxHeadYRot(): Int = 60

    override fun getAmbientSound(): SoundEvent? = if (asleep) DragonRegistry.SNORE.get() else DragonRegistry.GROWL.get()
    override fun getAmbientSoundInterval(): Int = 200
    override fun getHurtSound(source: DamageSource): SoundEvent = DragonRegistry.HURT.get()
    override fun getDeathSound(): SoundEvent = DragonRegistry.DEATH.get()
    override fun getSoundVolume(): Float = 4f

    // Its feet are heard in step with its legs (see footfalls)
    override fun playStepSound(pos: BlockPos, state: BlockState) {}

    // ---- On a client ----

    private fun clientTick() {
        // First seen already asleep or on the wing, it's shown so at once rather than settling into it
        if (!sighted) {
            sighted = true
            sleepBlend = if (asleep) 1f else 0f
            airBlend = if (flying) 1f else 0f
        }
        val dx = x - xo
        val dy = y - yo
        val dz = z - zo
        val moved = min(sqrt(dx * dx + dz * dz).toFloat(), MAX_STRIDE)
        strideO = stride
        gaitSpeedO = gaitSpeed
        if (!flying) stride += moved
        gaitSpeed += ((if (flying) 0f else moved) - gaitSpeed) * 0.3f
        sleepBlendO = sleepBlend
        val sleepy = asleep && shownAttack(0f) == null && deathTime <= 0
        sleepBlend = (sleepBlend + if (sleepy) 0.04f else -0.08f).coerceIn(0f, 1f)
        airBlendO = airBlend
        airBlend = (airBlend + if (flying) 0.15f else -0.15f).coerceIn(0f, 1f)
        shownAttack(0f)?.let { (attack, t) -> if (attack === TAKEOFF) airPhase = Math.floorMod(t.toInt() - TAKEOFF_HOVER - tickCount, HOVER_LOOP) }
        // It leans into its flight: nose down as it drops, banked into its turns
        flightPitchO = flightPitch
        flightRollO = flightRoll
        if (flying && deathTime <= 0) {
            val h = sqrt(dx * dx + dz * dz).toFloat()
            val pitch = Math.toDegrees(atan2(-dy, max(h.toDouble(), 0.05))).toFloat().coerceIn(-30f, 55f) * min(1f, h / 0.45f)
            val turn = Mth.wrapDegrees(yRot - yRotO)
            val roll = (turn * 4.5f).coerceIn(-45f, 45f) * min(1f, h / 0.4f)
            flightPitch += (pitch - flightPitch) * 0.15f
            flightRoll += (roll - flightRoll) * 0.12f
            airSpeed += (h - airSpeed) * 0.2f
            climb += (dy.toFloat() - climb) * 0.2f
            wingbeats()
        } else {
            flightPitch *= 0.8f
            flightRoll *= 0.8f
            airSpeed *= 0.8f
            climb *= 0.8f
            footfalls()
        }
        fireFx()
    }

    /** How much each of its flight loops shows (hover, flap, glide), as the model blends them. */
    fun flightWeights(): Triple<Float, Float, Float> {
        val hover = 1f - smoothstep(0.18f, 0.42f, airSpeed)
        val flap = (1f - hover) * smoothstep(-0.06f, 0.04f, climb)
        return Triple(hover, flap, 1f - hover - flap)
    }

    /** A wingbeat heard at each downbeat of whichever flight loop leads (art/scripts/dragon_anims.py). */
    private fun wingbeats() {
        val a = shownAttack(0f)?.first
        if (a === TAKEOFF || a === LAND || a === DIVE) return
        val (hover, flap, _) = flightWeights()
        val beat = when {
            hover >= 0.5f -> (tickCount + airPhase) % HOVER_LOOP == HOVER_BEAT
            flap >= 0.5f -> (tickCount + airPhase) % FLY_LOOP == FLY_BEAT
            else -> false
        }
        if (beat) level().playLocalSound(x, y, z, DragonRegistry.WING_FLAP.get(), SoundSource.HOSTILE, 3f, 0.85f + random.nextFloat() * 0.15f, false)
    }

    /** A foot meeting the ground at each footfall of its walk, in step with its legs. */
    private fun footfalls() {
        if (!isAlive || !onGround() || gaitSpeed < WALK_FROM) return
        val from = strideO / WALK_STRIDE
        val to = stride / WALK_STRIDE
        for (fall in FOOTFALLS) {
            if (floor(to - fall) > floor(from - fall)) {
                level().playLocalSound(x, y, z, DragonRegistry.STEP.get(), SoundSource.HOSTILE, 1.4f, 0.85f + random.nextFloat() * 0.15f, false)
            }
        }
    }

    /** Its fire, drawn here so it flows smoothly: smoke as it draws breath, the stream, and the glow when enraged. */
    private fun fireFx() {
        val level = level()
        val shown = shownAttack(0f)
        if (shown != null) {
            val (attack, t) = shown
            when (attack) {
                BREATH -> {
                    if (t < BREATH_FROM - 4 && tickCount % 2 == 0) {
                        val (from, _) = mouth(BREATH, BREATH_FROM.toFloat())
                        level.addParticle(ParticleTypes.SMOKE, from.x, from.y + 0.3, from.z, 0.0, 0.06, 0.0)
                        if (t > 10f) level.addParticle(ParticleTypes.SMALL_FLAME, from.x, from.y, from.z, 0.0, 0.02, 0.0)
                    }
                    if (t >= BREATH_FROM && t <= BREATH_TO) stream(BREATH, t)
                }
                AIR_BREATH -> if (t >= AIR_BREATH_FROM && t <= AIR_BREATH_TO) stream(AIR_BREATH, t)
                else -> {}
            }
        }
        if (enraged && isAlive && random.nextInt(2) == 0) {
            val px = x + (random.nextDouble() - 0.5) * bbWidth * 1.4
            val pz = z + (random.nextDouble() - 0.5) * bbWidth * 1.4
            level.addParticle(if (random.nextInt(4) == 0) ParticleTypes.LAVA else ParticleTypes.SMALL_FLAME, px, y + 1.0 + random.nextDouble() * 2.6, pz, 0.0, 0.03, 0.0)
        }
    }

    private fun stream(attack: EnemyAttack, t: Float) {
        val (from, dir) = mouth(attack, t)
        val level = level()
        val flame = DragonRegistry.FLAME.get()
        repeat(8) {
            val speed = 0.8 + random.nextDouble() * 0.5
            val v = dir.add((random.nextDouble() - 0.5) * 0.18, (random.nextDouble() - 0.5) * 0.14, (random.nextDouble() - 0.5) * 0.18).normalize().scale(speed)
            val lead = random.nextDouble() * 0.8
            level.addParticle(flame, true, true, from.x + v.x * lead, from.y + v.y * lead, from.z + v.z * lead, v.x, v.y, v.z)
        }
        if (random.nextInt(2) == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, from.x + dir.x * 3, from.y + dir.y * 3, from.z + dir.z * 3, dir.x * 0.3, 0.05, dir.z * 0.3)
    }

    // ---- Saving ----

    override fun checkDespawn() {
        if (level().difficulty == Difficulty.PEACEFUL) discard()
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putInt("dragon_name", nameIndex)
        output.storeNullable("dragon_home", BlockPos.CODEC, home)
        output.putFloat("dragon_home_yaw", homeYaw)
        output.putInt("dragon_roost", roost)
    }

    /** A fight never survives a reload: it's whole again and on its way back to its nest. */
    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        nameIndex = input.getIntOr("dragon_name", 0).coerceIn(0, NAMES - 1)
        home = input.read("dragon_home", BlockPos.CODEC).orElse(null)
        homeYaw = input.getFloatOr("dragon_home_yaw", 0f)
        roost = input.getIntOr("dragon_roost", -1)
        reloaded = true
        bossEvent.name = title
        if (health > 0f) {
            setFlag(FIGHTING, false)
            setFlag(PHASE_TWO, false)
            setFlag(ENRAGED, false)
            setFlag(FLYING, false)
            isNoGravity = false
            health = maxHealth
        }
    }

    companion object {
        const val ANIMATIONS = "dragon"
        const val NAME_KEY = "boss.guildmark.dragon_name"
        /** How many names a dragon might have (lang keys boss.guildmark.dragon_name.0 and on). */
        const val NAMES = 10

        private const val FIGHTING = 2
        private const val FLYING = 4
        private const val ASLEEP = 8
        private const val PHASE_TWO = 16
        private const val ENRAGED = 32

        private const val PHASE_TWO_AT = 0.6f
        private const val ENRAGE_AT = 0.25f
        /** How much harder blows land on it while it sleeps. */
        private const val SLEEPING_BLOW = 2f

        // Ranges, in blocks
        private const val NOTICE_AWAKE = 40.0
        private const val NOTICE_FLYING = 64.0
        private const val NOTICE_ASLEEP = 14.0
        private const val NOTICE_ASLEEP_CREEPING = 6.0
        /** How near players see its health. */
        private const val FIGHT_RANGE = 96.0
        /** How far it will go after someone in its fight. */
        private const val CHASE_RANGE = 96.0
        /** How far from its nest a fight may go before it lets them go. */
        private const val LEASH = 112.0
        private const val ROAR_RANGE = 18.0
        private const val CUE_RANGE = 64.0
        /** Ticks its fall holds the camera (the dragon's longer take in client/combat/Cutscenes.kt), and shields those watching. */
        private const val FELLED_SCENE = 118
        /** How far above and below its feet its jaws and claws still reach. */
        private const val REACH_ABOVE = 4.5
        private const val REACH_BELOW = 6.0
        /** A foe further off than this on foot, and it flies to them. */
        private const val FLY_AFTER_DISTANCE = 22f

        // Times, in ticks unless named for seconds
        private const val LOST_TICKS = 200
        private const val UNSEEN_SECONDS = 20
        private const val ALOOF_TICKS = 600
        private const val STUCK_LIMIT = 60
        private const val MAX_AIR_TICKS = 1400
        private const val REST_MIN = 2400
        private const val REST_SPREAD = 3600
        private const val PATROL_MIN = 900
        private const val PATROL_SPREAD = 900
        const val DEATH_TICKS = 100
        /** The tick of its death on which it hits the ground. */
        const val DEATH_SLAM = 44

        // Flight, in blocks and blocks a tick
        private const val CRUISE = 0.85
        private const val STRAFE_SPEED = 0.78
        private const val CIRCLE_SPEED = 0.8
        private const val ACCEL = 0.07
        private const val TURN_AIR = 9f
        private const val MIN_CLEARANCE = 7
        private const val STRAFE_HEIGHT = 10.0
        private const val PASS_BEFORE = 38.0
        private const val PASS_AFTER = 30.0
        /** How far ahead along its pass its foe is when it draws breath for a strafing run. */
        private const val BREATH_LEAD_MIN = 18.0
        private const val BREATH_LEAD_MAX = 32.0
        private const val HOVER_DISTANCE = 16.0
        private const val HOVER_HEIGHT = 9.0

        // Fire
        private const val BREATH_RANGE = 16.0
        private const val AIR_BREATH_RANGE = 24.0
        private const val AIR_BREATH_PITCH = 50f
        /** A breath's hit, and the burning ground's, as shares of its bite. */
        private const val BREATH_DAMAGE = 0.45f
        private const val PATCH_DAMAGE = 0.15f
        private const val BREATH_BURN = 3f
        private const val SINGE_TICKS = 8
        private const val PATCH_TICKS = 140
        private const val ENRAGE_PATCH_TICKS = 200
        private const val MAX_PATCHES = 48
        private const val SEAR_POISE = 4f

        /**
         * Where its mouth is, in blocks from its feet (up) and ahead of its middle (forward), while it breathes on
         * the ground, on the wing and from a hover; the ground breath swings its head about the base of its neck.
         * Measured from the clips (art/scripts/dragon_anims.py).
         */
        const val GROUND_MOUTH_FORWARD = 5.5
        const val GROUND_MOUTH_UP = 3.0
        const val AIR_MOUTH_FORWARD = 4.4
        const val AIR_MOUTH_UP = 1.8
        const val HOVER_MOUTH_FORWARD = 5.4
        const val HOVER_MOUTH_UP = 3.3
        const val NECK_BASE = 2.3
        /** Degrees below level the ground breath's clip holds its head through the stream. */
        const val BREATH_HEAD_PITCH = 20f
        /** Blocks its mouth draws back and drops for each degree its head is aimed below the clip's (see DragonModel). */
        private const val MOUTH_BACK = 0.026
        private const val MOUTH_DROP = 0.03

        /** The ticks of the ground breath's stream, and the strafing breath's (as the clips have them). */
        const val BREATH_FROM = 26
        const val BREATH_TO = 58
        const val AIR_BREATH_FROM = 18
        const val AIR_BREATH_TO = 48
        private const val LAND_AT = 14
        private const val DIVE_FROM = 12
        private const val DIVE_AT = 28

        /** Its head's swing across the ground breath: 30 degrees to its right at the start, 30 to its left at the end. */
        fun breathSweep(tick: Float): Float = 30f - 60f * ((tick - BREATH_FROM) / (BREATH_TO - BREATH_FROM)).coerceIn(0f, 1f)

        // Its gait and wings (art/scripts/dragon_anims.py)
        /** Blocks it covers in one loop of its walk, and where in the loop each foot lands. */
        const val WALK_STRIDE = 3.5f
        private val FOOTFALLS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f)
        private const val WALK_FROM = 0.03f
        private const val MAX_STRIDE = 0.6f
        const val HOVER_LOOP = 20
        private const val HOVER_BEAT = 7
        const val FLY_LOOP = 30
        private const val FLY_BEAT = 9
        /** The takeoff's wings match the hover's this many ticks behind them, through its last ticks. */
        private const val TAKEOFF_HOVER = 22

        private val TIER_HEALTH = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "dragon_tier_health")
        private val TIER_DAMAGE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "dragon_tier_damage")
        /** Its fire's damage type (data/guildmark/damage_type/dragon_fire.json): no shield blocks it. */
        val DRAGON_FIRE: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "dragon_fire"))

        private val DATA_AIM: EntityDataAccessor<Float> = SynchedEntityData.defineId(Dragon::class.java, EntityDataSerializers.FLOAT)

        private fun smooth(k: Float): Float {
            val c = k.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        fun smoothstep(from: Float, to: Float, x: Float): Float = smooth((x - from) / (to - from))

        private fun yawTo(dx: Double, dz: Double): Float = Math.toDegrees(atan2(-dx, dz)).toFloat()

        private fun flat(a: Vec3, b: BlockPos): Double = flat(a, Vec3.atBottomCenterOf(b))

        private fun flat(a: Vec3, b: Vec3): Double {
            val dx = a.x - b.x
            val dz = a.z - b.z
            return sqrt(dx * dx + dz * dz)
        }

        // Timings match art/scripts/dragon_anims.py
        val BITE = EnemyAttack(1, "bite", 32, listOf(Blow(16, 1.3f, 2.6, 45f, 30f, knockback = 0.7f, forward = 4.4)), listOf(0..13),
            maxRange = 4.6, weight = 5, cooldown = 14, lunge = 0.25f, lungeAt = 12, lungeTicks = 4, turn = 10f)
        val CLAW = EnemyAttack(2, "claw", 36, listOf(Blow(18, 1.15f, 3.6, 80f, 35f, knockback = 1.0f, forward = 2.4)), listOf(0..14),
            maxRange = 3.6, weight = 4, cooldown = 18, turn = 10f)
        val TAIL_SWEEP = EnemyAttack(3, "tail_sweep", 46, listOf(Blow(22, 1.2f, 7.0, 180f, 40f, parryable = false, knockback = 1.8f, until = 26, forward = -1.0)),
            listOf(0..16), maxRange = 5.0, weight = 3, cooldown = 24)
        val STOMP = EnemyAttack(4, "stomp", 42, listOf(Blow(22, 1.4f, 5.2, 180f, 50f, parryable = false, knockback = 1.4f, forward = 2.6)), listOf(0..12),
            maxRange = 4.0, weight = 3, cooldown = 24)
        val WING_BUFFET = EnemyAttack(5, "wing_buffet", 38, listOf(Blow(18, 0.5f, 9.0, 70f, 60f, parryable = false, knockback = 3.2f, forward = 1.0, guard = 2f)),
            listOf(0..14), maxRange = 7.0, weight = 2, cooldown = 30)
        val BREATH = EnemyAttack(6, "breath", 72, emptyList(), listOf(0..24), minRange = 3.0, maxRange = 18.0, weight = 4, cooldown = 30, turn = 8f)
        val ROAR = EnemyAttack(7, "roar", 50, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val PHASE_ROAR = EnemyAttack(8, "roar", 50, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val ROAR_AIR = EnemyAttack(9, "roar_air", 40, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val PHASE_ROAR_AIR = EnemyAttack(10, "roar_air", 40, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val TAKEOFF = EnemyAttack(11, "takeoff", 30, emptyList(), emptyList(), weight = 0, cooldown = 4)
        val LAND = EnemyAttack(12, "land", 28, listOf(Blow(LAND_AT, 0.8f, 4.5, 180f, 30f, parryable = false, knockback = 1.2f)), emptyList(),
            weight = 0, cooldown = 16)
        val AIR_BREATH = EnemyAttack(13, "air_breath", 56, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val FIREBALL = EnemyAttack(14, "fireball", 36, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val FIREBALL_VOLLEY = EnemyAttack(15, "fireball_volley", 46, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val DIVE = EnemyAttack(16, "dive", 44, listOf(Blow(DIVE_AT, 1.5f, 4.8, 180f, 60f, parryable = false, knockback = 2.2f)), emptyList(),
            weight = 0, cooldown = 16)
        val ENRAGE = EnemyAttack(17, "enrage", 64, listOf(Blow(36, 0.9f, 7.0, 180f, 60f, parryable = false, knockback = 2.0f, fire = 4)), emptyList(),
            weight = 0, cooldown = 16)
        val WAKE = EnemyAttack(18, "wake", 40, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val ATTACKS = listOf(
            BITE, CLAW, TAIL_SWEEP, STOMP, WING_BUFFET, BREATH, ROAR, PHASE_ROAR, ROAR_AIR, PHASE_ROAR_AIR,
            TAKEOFF, LAND, AIR_BREATH, FIREBALL, FIREBALL_VOLLEY, DIVE, ENRAGE, WAKE,
        )

        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 450.0)
            .add(Attributes.ATTACK_DAMAGE, 10.0)
            .add(Attributes.ARMOR, 12.0)
            .add(Attributes.ARMOR_TOUGHNESS, 4.0)
            .add(Attributes.MOVEMENT_SPEED, 0.26)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FOLLOW_RANGE, 96.0)
            .add(Attributes.STEP_HEIGHT, 2.1)
    }
}

/** A heavy beast's turning on the ground: no faster than [TURN] degrees a tick. */
class DragonMoveControl(mob: Dragon) : MoveControl<Dragon>(mob) {
    override fun rotlerp(a: Float, b: Float, max: Float): Float = super.rotlerp(a, b, min(max, TURN))

    private companion object {
        const val TURN = 9f
    }
}
