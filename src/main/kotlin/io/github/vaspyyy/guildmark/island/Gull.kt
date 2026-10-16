package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.ship.Cog
import io.github.vaspyyy.guildmark.ship.Wind
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A gull: a herring gull, a great black-backed gull (more of them in the cold seas) or a young one, mottled brown. Gulls
 * keep to the islands: they stand about on the cliff tops and roofs and ledges, preen, peck about and squabble, throw
 * back their heads in the long call (and the colony answers), ride the swell, and wheel over their island on the wind.
 * Out over the water they fold up and plunge for fish. They hang in the wind behind a ship under way, and beg at anyone
 * holding a fish; a fish left lying about they'll snatch. Walk up on them and they're off; take an egg from a nest, or
 * hurt one of them, and the colony comes at you, swooping at your head. At dusk they come home to roost.
 *
 * What it's doing is three things kept in step with every client: [mode] (on its feet, on the water, flying, or hanging
 * on the wind), [action] (a scripted move: preening, the long call, a takeoff, a landing, a dive...) and when that
 * action began, so the model (client/island/GullModel) poses it from the same clock its moves are made by. A takeoff, a
 * landing and a dive move it along set paths, tick by tick, timed to the model's [TAKEOFF_LIFT], [LAND_TOUCH],
 * [DIVE_HIT] and [DIVE_SURFACE] marks. Its moves go to clients every tick and are played back step by step, a tick
 * behind (see its type's update interval), which is why a client reads the action's clock a tick late ([actionTime]).
 */
class Gull(type: EntityType<out Gull>, level: Level) : Mob(type, level) {
    enum class Mode { STAND, FLOAT, FLY, HOVER }

    /** What it's flying for. */
    private enum class Plan { WHEEL, ROAM, FISH, SHIP, BEG, SNATCH, MOB, LANDING, CLIFF }

    // ---- On the server ----

    private var home: BlockPos? = null
    private var plan = Plan.WHEEL
    private var planTicks = 0
    /** How long this plan lasts before it thinks again. */
    private var planFor = 400
    private var modeTicks = 0
    private var actionTicks = 0
    /** Ticks it sits, or floats, before it's off again. */
    private var restFor = 600
    private var nextIdle = 60
    private var nextCry = 200
    private var nextScan = 0
    /** Ticks until it answers a neighbour's call, or takes fright at a neighbour's (-1: not waiting to). */
    private var answerIn = -1
    private var spookIn = -1
    private var fallTicks = 0
    private var eatIn = -1
    private var lookAt: Player? = null

    // Wheeling: round [wheelCentre] at [wheelRadius], [wheelHeight] over the land or sea below, one way round or the other
    private var wheelCentre = Vec3.ZERO
    private var wheelRadius = 16.0
    private var wheelHeight = 12.0
    private var wheelTurn = 1.0
    /** Where it's making for (roaming, fishing, hanging over a cliff), and what it's after: a ship, a fish, a player. */
    private var aim = Vec3.ZERO
    private var quarry = -1
    private var hoverSide = 0.0
    // Mobbing a thief
    private var mobbing: UUID? = null
    private var mobNest: BlockPos? = null
    private var mobUntil = 0L
    private var swoop = 0
    private var swoopAway = Vec3.ZERO
    private var lastStrike = -100
    // Coming in to land, and the landing
    private var landAt: Vec3? = null
    private var landOnWater = false
    private var landFrom = Vec3.ZERO
    private var landTo = Vec3.ZERO
    // A plunge
    private var diveFrom = Vec3.ZERO
    private var diveTo = Vec3.ZERO
    // A walk
    private var walkTo: Vec3? = null
    private var walkTicks = 0

    // ---- On a client: how it moves, eased from tick to tick (last tick's and this one's) ----

    var flyingO = 0f
        private set
    var flying = 0f
        private set
    var spreadO = 0f
        private set
    var spread = 0f
        private set
    var flapO = 0f
        private set
    var flap = 0f
        private set
    var flapPhaseO = 0f
        private set
    var flapPhase = 0f
        private set
    var bankO = 0f
        private set
    var bank = 0f
        private set
    var hoverO = 0f
        private set
    var hover = 0f
        private set
    var floatingO = 0f
        private set
    var floating = 0f
        private set
    var beakO = 0f
        private set
    var beak = 0f
        private set
    var walkO = 0f
        private set
    var walk = 0f
        private set
    var walkPhaseO = 0f
        private set
    var walkPhase = 0f
        private set
    /** Degrees its body is pitched in flight, nose up: climbing, gliding down. */
    var pitchO = 0f
        private set
    var pitch = 0f
        private set
    private var cryTicks = 0

    init {
        isNoGravity = true
        xpReward = 1
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_MODE, Mode.STAND.ordinal.toByte())
        builder.define(DATA_ACTION, NONE.toByte())
        builder.define(DATA_ACTION_AT, 0)
        builder.define(DATA_VARIANT, HERRING.toByte())
        builder.define(DATA_FISH, false)
    }

    var mode: Mode
        get() = MODES[entityData.get(DATA_MODE).toInt().coerceIn(0, MODES.size - 1)]
        private set(value) {
            if (value != mode) {
                entityData.set(DATA_MODE, value.ordinal.toByte())
                modeTicks = 0
            }
        }

    /** What it's doing (see [ACTION_TICKS]), or [NONE]. */
    val action: Int
        get() = entityData.get(DATA_ACTION).toInt()

    /** Ticks into its action. A client sees its moves a tick late, so it reads the action's clock a tick late too. */
    fun actionTime(partial: Float): Float {
        if (action == NONE) return 0f
        val since = ((level().gameTime and 0x3FFFFFFF).toInt() - entityData.get(DATA_ACTION_AT)) + partial
        return (if (level().isClientSide()) since - CLIENT_LAG else since).coerceAtLeast(0f)
    }

    var variant: Int
        get() = entityData.get(DATA_VARIANT).toInt()
        private set(value) = entityData.set(DATA_VARIANT, value.toByte())

    /** A fish crosswise in its beak. */
    var fish: Boolean
        get() = entityData.get(DATA_FISH)
        private set(value) = entityData.set(DATA_FISH, value)

    private fun act(what: Int) {
        entityData.set(DATA_ACTION_AT, (level().gameTime and 0x3FFFFFFF).toInt())
        entityData.set(DATA_ACTION, what.toByte())
        actionTicks = 0
    }

    // ---- Coming into the world ----

    override fun finalizeSpawn(level: ServerLevelAccessor, difficulty: DifficultyInstance, reason: EntitySpawnReason, data: SpawnGroupData?): SpawnGroupData? {
        val result = super.finalizeSpawn(level, difficulty, reason, data)
        if (home == null) home = blockPosition()
        val cold = level.getBiome(blockPosition()).value().baseTemperature < COLD
        val roll = random.nextFloat()
        variant = when {
            roll < YOUNG_SHARE -> YOUNG
            roll < YOUNG_SHARE + (if (cold) 0.45f else 0.2f) -> BLACKBACK
            else -> HERRING
        }
        restFor = 100 + random.nextInt(1200)
        nextIdle = 20 + random.nextInt(120)
        return result
    }

    /** Fresh out of the egg in the nest at [nest], which is its home now. */
    fun hatched(nest: BlockPos) {
        home = nest
        variant = YOUNG
        restFor = 1200 + random.nextInt(1200)
        setPersistenceRequired()
    }

    // ---- Every tick ----

    override fun tick() {
        super.tick()
        if (level().isClientSide()) clientTick()
    }

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        tickAction(level)
        if (eatIn > 0 && --eatIn == 0) fish = false
        when (mode) {
            Mode.STAND -> standTick(level)
            Mode.FLOAT -> floatTick(level)
            Mode.FLY -> flyTick(level)
            Mode.HOVER -> hoverTick(level)
        }
        modeTicks++
        planTicks++
    }

    private fun tickAction(level: ServerLevel) {
        val a = action
        if (a == NONE) return
        actionTicks++
        when (a) {
            CALL -> if (actionTicks == CALL_AT) say(level, IslandRegistry.GULL_CALL.get(), 1f, beak = false)
            THREAT -> if (actionTicks == THREAT_AT) say(level, IslandRegistry.GULL_SQUABBLE.get(), 0.9f, beak = false)
            LAND -> if (actionTicks == FLARE_AT) level.playSound(null, x, y, z, IslandRegistry.GULL_FLAP.get(), SoundSource.NEUTRAL, 0.5f, voice())
        }
        if (actionTicks >= ACTION_TICKS[a]) entityData.set(DATA_ACTION, NONE.toByte())
    }

    // ---- On its feet ----

    private fun standTick(level: ServerLevel) {
        val a = action
        if (a == TAKEOFF) {
            spring(level, false)
            return
        }
        // Walking, or still; and always its weight on its feet, so it knows when there's nothing under them
        var vx = 0.0
        var vz = 0.0
        val to = walkTo
        if (to != null && a == NONE) {
            val dx = to.x - x
            val dz = to.z - z
            val far = sqrt(dx * dx + dz * dz)
            if (far < 0.12 || ++walkTicks > WALK_GIVE_UP) walkTo = null
            else {
                vx = dx / far * WALK_SPEED
                vz = dz / far * WALK_SPEED
                yRot = Mth.rotLerp(0.35f, yRot, yawOf(dx, dz))
                yBodyRot = yRot
                // Never off an edge or into the sea on foot
                if (!footing(level, x + vx * 5.0, y, z + vz * 5.0)) {
                    walkTo = null
                    vx = 0.0
                    vz = 0.0
                }
            }
        }
        val fall = if (onGround()) -0.08 else max(deltaMovement.y - GRAVITY, -0.6)
        move(MoverType.SELF, Vec3(vx, fall, vz))
        deltaMovement = Vec3(vx, if (onGround()) 0.0 else fall, vz)
        if (isInWater) {
            mode = Mode.FLOAT
            return
        }
        if (!onGround()) {
            // Knocked off its perch, or the perch gone: it opens its wings
            if (++fallTicks > 3) {
                walkTo = null
                mode = Mode.FLY
                deltaMovement = Vec3(deltaMovement.x, -0.1, deltaMovement.z)
                choose(level)
            }
            return
        }
        fallTicks = 0
        if (a != NONE) return
        if (scanDue()) {
            val threat = threat(level, false)
            if (threat != null) {
                flee(level, threat)
                return
            }
            if (lookAround(level)) return
        }
        if (spookIn > 0 && --spookIn == 0) {
            flee(level, null)
            return
        }
        lookAt?.let { if (it.isAlive && distanceToSqr(it) < LOOK_RANGE * LOOK_RANGE) lookControl.setLookAt(it, 20f, 20f) else lookAt = null }
        if (answerIn > 0 && --answerIn == 0) {
            act(CALL)
            return
        }
        if (--nextIdle <= 0) idle(level, onWater = false)
        if (modeTicks > restFor && !roosting(level)) takeOff(level, null)
    }

    /** Whether a gull could stand at ([px], [py], [pz]): solid footing under it, room for it, and not water. */
    private fun footing(level: ServerLevel, px: Double, py: Double, pz: Double): Boolean {
        val feet = BlockPos.containing(px, py + 0.1, pz)
        if (!level.hasChunkAt(feet.x, feet.z)) return false
        val below = feet.below()
        val under = level.getBlockState(below)
        // A step down onto a lower ledge would be a jump: it flies that, it doesn't walk it
        if (!under.isFaceSturdy(level, below, net.minecraft.core.Direction.UP) && !level.getBlockState(feet).`is`(IslandRegistry.GULL_NEST.get())) return false
        if (!level.getFluidState(feet).isEmpty) return false
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty || level.getBlockState(feet).`is`(IslandRegistry.GULL_NEST.get())
    }

    /** Something to do, standing about or riding the swell. */
    private fun idle(level: ServerLevel, onWater: Boolean) {
        val night = level.isDarkOutside
        nextIdle = (if (night) 120 else 40) + random.nextInt(if (night) 300 else 140)
        val roll = random.nextInt(100)
        when {
            roll < 26 -> act(PREEN)
            roll < 40 -> act(RUFFLE)
            roll < 58 && !night -> {
                act(CALL)
                callOut(level)
            }
            roll < 76 && !onWater -> act(PECK)
            !onWater -> wander(level)
            else -> yRot += (random.nextFloat() - 0.5f) * 90f
        }
    }

    /** A few steps somewhere along the ledge, or the roof, or the beach. */
    private fun wander(level: ServerLevel) {
        for (attempt in 0 until 6) {
            val angle = random.nextDouble() * 2.0 * PI
            val far = 1.0 + random.nextDouble() * 3.0
            val tx = x + cos(angle) * far
            val tz = z + sin(angle) * far
            if (footing(level, tx, y, tz) && footing(level, (x + tx) / 2.0, y, (z + tz) / 2.0)) {
                walkTo = Vec3(tx, y, tz)
                walkTicks = 0
                return
            }
        }
    }

    /** It throws back its head and calls, and the gulls round about may take it up. */
    private fun callOut(level: ServerLevel) {
        for (other in level.getEntitiesOfClass(Gull::class.java, boundingBox.inflate(ANSWER_RANGE)) { it !== this && it.isAlive }) {
            if (other.action == NONE && other.answerIn < 0 && (other.mode == Mode.STAND || other.mode == Mode.FLOAT) && random.nextFloat() < ANSWER_CHANCE) {
                other.answerIn = 6 + random.nextInt(34)
            }
        }
    }

    /** Looks about it: at someone near, at a fish lying about, at a neighbour too close. True if it's off after something. */
    private fun lookAround(level: ServerLevel): Boolean {
        var nearest: Player? = null
        var best = LOOK_RANGE * LOOK_RANGE
        for (player in level.players()) {
            if (player.isSpectator) continue
            val d = distanceToSqr(player)
            if (d < best) {
                best = d
                nearest = player
            }
        }
        lookAt = nearest
        if (!fish && !roosting(level)) {
            val food = fishNear(level, SNATCH_RANGE)
            if (food != null) {
                quarry = food.id
                takeOff(level, Plan.SNATCH)
                return true
            }
        }
        // Too close to a neighbour, now and then it squares up to it
        if (random.nextInt(SQUABBLE_ODDS) == 0) {
            val rival = level.getEntitiesOfClass(Gull::class.java, boundingBox.inflate(SQUABBLE_RANGE)) { it !== this && it.isAlive && it.mode == Mode.STAND && it.action == NONE }.firstOrNull()
            if (rival != null) {
                faceTowards(rival.x - x, rival.z - z)
                rival.faceTowards(x - rival.x, z - rival.z)
                act(THREAT)
                rival.act(THREAT)
                // Whichever gives way leaves
                val loser = if (random.nextBoolean()) this else rival
                loser.restFor = loser.modeTicks + ACTION_TICKS[THREAT] + 10
            }
        }
        return false
    }

    private fun faceTowards(dx: Double, dz: Double) {
        val yaw = yawOf(dx, dz)
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
    }

    // ---- On the water ----

    private fun floatTick(level: ServerLevel) {
        val a = action
        val surface = surface(level, x, y, z)
        if (surface == null) {
            if (a != DIVE) mode = Mode.STAND
            return
        }
        if (a == TAKEOFF) {
            spring(level, true)
            return
        }
        // Paddling about, slowly, and turning now and then; the swell carries it a little
        val paddle = if (a == NONE && (tickCount / 80 + id) % 3 != 0) PADDLE else 0.0
        if (a == NONE && random.nextInt(60) == 0) yRot += (random.nextFloat() - 0.5f) * 70f
        val vx = forwardX(yRot) * paddle
        val vz = forwardZ(yRot) * paddle
        move(MoverType.SELF, Vec3(vx, 0.0, vz))
        if (horizontalCollision) yRot += 150f + random.nextFloat() * 60f
        setPos(x, surface, z)
        deltaMovement = Vec3(vx, 0.0, vz)
        yBodyRot = yRot
        if (a != NONE) return
        if (scanDue()) {
            val threat = threat(level, true)
            if (threat != null) {
                flee(level, threat)
                return
            }
            if (lookAround(level)) return
        }
        if (spookIn > 0 && --spookIn == 0) {
            flee(level, null)
            return
        }
        if (answerIn > 0 && --answerIn == 0) {
            act(CALL)
            return
        }
        if (--nextIdle <= 0) idle(level, onWater = true)
        if (modeTicks > restFor && !level.isDarkOutside && !level.isThundering) takeOff(level, null)
    }

    /** The top of the water at ([px], [pz]) near [py], or null if it isn't on water. */
    private fun surface(level: ServerLevel, px: Double, py: Double, pz: Double): Double? {
        val cursor = BlockPos.MutableBlockPos()
        val bx = Mth.floor(px)
        val bz = Mth.floor(pz)
        if (!level.hasChunkAt(bx, bz)) return null
        val top = Mth.floor(py + 0.6)
        for (by in top downTo top - 2) {
            cursor.set(bx, by, bz)
            val fluid = level.getFluidState(cursor)
            if (fluid.`is`(FluidTags.WATER)) {
                cursor.set(bx, by + 1, bz)
                // Water over it: that's under the surface, not on it
                if (level.getFluidState(cursor).`is`(FluidTags.WATER)) return null
                cursor.set(bx, by, bz)
                return by + fluid.getHeight(level, cursor).toDouble()
            }
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty) return null
        }
        return null
    }

    // ---- Up into the air ----

    /** Crouches and springs up into the air (into the wind, unless it's getting away from something), to go about [then]. */
    private fun takeOff(level: ServerLevel, then: Plan?) {
        walkTo = null
        act(TAKEOFF)
        plan = then ?: Plan.WHEEL
        planTicks = 0
        if (plan == Plan.WHEEL) wheelHome(level)
        if (then == null || then == Plan.WHEEL) faceYaw(Wind.from(level) + (random.nextFloat() - 0.5f) * 40f)
    }

    /** Off, away from [from], with a cry of alarm; the gulls round it take fright too. */
    private fun flee(level: ServerLevel, from: Entity?) {
        if (action == TAKEOFF) return
        walkTo = null
        act(TAKEOFF)
        plan = Plan.WHEEL
        planTicks = 0
        wheelHome(level)
        if (from != null) faceYaw(yawOf(x - from.x, z - from.z))
        if (random.nextInt(3) != 0) say(level, IslandRegistry.GULL_ALARM.get(), 0.9f)
        for (other in level.getEntitiesOfClass(Gull::class.java, boundingBox.inflate(SPOOK_SPREAD)) { it !== this && it.isAlive }) {
            if ((other.mode == Mode.STAND || other.mode == Mode.FLOAT) && other.action != TAKEOFF && other.spookIn < 0) other.spookIn = 2 + random.nextInt(10)
        }
        spookIn = -1
    }

    /** The spring: still until its feet leave the ground (or the water), then up and away on the first great downbeats. */
    private fun spring(level: ServerLevel, fromWater: Boolean) {
        if (actionTicks <= TAKEOFF_LIFT) {
            deltaMovement = Vec3.ZERO
            if (fromWater) surface(level, x, y, z)?.let { setPos(x, it, z) }
            return
        }
        val v = Vec3(forwardX(yRot) * LIFT_FORWARD, LIFT_UP, forwardZ(yRot) * LIFT_FORWARD)
        deltaMovement = v
        move(MoverType.SELF, v)
        mode = Mode.FLY
        spookIn = -1
        answerIn = -1
        level.playSound(null, x, y, z, IslandRegistry.GULL_FLAP.get(), SoundSource.NEUTRAL, 0.7f, voice())
        if (fromWater) {
            level.playSound(null, x, y, z, IslandRegistry.GULL_SPLASH.get(), SoundSource.NEUTRAL, 0.5f, 1.3f)
            level.sendParticles(ParticleTypes.SPLASH, x, y, z, 10, 0.3, 0.05, 0.3, 0.1)
        }
    }

    private fun faceYaw(yaw: Float) {
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
    }

    // ---- Flying ----

    private fun flyTick(level: ServerLevel) {
        when (action) {
            LAND -> {
                landing(level)
                return
            }
            DIVE -> {
                plunge(level)
                return
            }
            TAKEOFF -> {
                // Climbing out on hard beats: straight on and up
                val v = deltaMovement.scale(0.92).add(forwardX(yRot) * 0.03, 0.022, forwardZ(yRot) * 0.03)
                deltaMovement = v
                move(MoverType.SELF, v)
                if (horizontalCollision) faceYaw(yRot + 120f + random.nextFloat() * 120f)
                return
            }
        }
        if (scanDue()) notice(level)
        if (plan != Plan.MOB && plan != Plan.SNATCH && --nextCry <= 0) {
            nextCry = 160 + random.nextInt(360)
            if (!level.isDarkOutside) say(level, IslandRegistry.GULL_CRY.get(), 0.8f)
        }
        when (plan) {
            Plan.WHEEL -> wheel(level)
            Plan.ROAM -> roam(level)
            Plan.FISH -> hunt(level)
            Plan.SNATCH -> snatch(level)
            Plan.MOB -> mob(level)
            Plan.LANDING -> comeIn(level)
            // Coming up to a ship, a beggar's mark or a cliff, it hangs there
            Plan.SHIP, Plan.BEG, Plan.CLIFF -> {
                val at = hoverPoint(level)
                if (at == null) choose(level)
                else {
                    fly(level, at, APPROACH_SPEED, STEER)
                    if (position().distanceToSqr(at) < 3.0 * 3.0) mode = Mode.HOVER
                }
            }
        }
    }

    /**
     * Flies on toward [target] at [speed] blocks a tick, turning no faster than [steer] lets it (a bird banks round, it
     * doesn't spin), slowing as it comes up on it, and keeping [clearance] over the land and the sea ahead.
     */
    private fun fly(level: ServerLevel, target: Vec3, speed: Double, steer: Double, clearance: Double = CLEARANCE) {
        val to = target.subtract(position())
        val far = to.length()
        var want = if (far < 1.0e-3) Vec3.ZERO else to.scale(min(speed, max(far * 0.3, 0.06)) / far)
        // A look ahead for the land rising: up and over it
        val ahead = position().add(deltaMovement.scale(LOOK_AHEAD))
        val floor = floorAt(level, ahead.x, ahead.z, ahead.y)
        val under = floorAt(level, x, z, y)
        val low = max(floor, under) + clearance - min(ahead.y, y)
        if (low > 0) want = Vec3(want.x, max(want.y, min(0.35, 0.08 + low * 0.08)), want.z)
        var v = deltaMovement.add(want.subtract(deltaMovement).scale(steer))
        val sp = v.length()
        if (sp > MAX_SPEED) v = v.scale(MAX_SPEED / sp)
        deltaMovement = v
        move(MoverType.SELF, v)
        if (horizontalCollision) {
            // Struck something: up and round
            deltaMovement = Vec3(-v.x * 0.4, 0.2, -v.z * 0.4)
            wheelTurn = -wheelTurn
        }
        val flat = sqrt(v.x * v.x + v.z * v.z)
        if (flat > 0.02) {
            yRot = Mth.rotLerp(0.3f, yRot, yawOf(v.x, v.z))
            yBodyRot = yRot
            yHeadRot = yRot
        }
    }

    /** The height it must keep over ([px], [pz]): the land or the sea there, or the sea if nobody's near enough to know. */
    private fun floorAt(level: ServerLevel, px: Double, pz: Double, py: Double): Double {
        val bx = Mth.floor(px)
        val bz = Mth.floor(pz)
        if (!level.hasChunkAt(bx, bz)) return level.seaLevel.toDouble()
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz).toDouble()
    }

    /** Wheels over its home. */
    private fun wheelHome(level: ServerLevel) {
        val h = home ?: blockPosition()
        wheelCentre = Vec3(h.x + 0.5 + random.nextGaussian() * 6.0, 0.0, h.z + 0.5 + random.nextGaussian() * 6.0)
        wheelRadius = 9.0 + random.nextDouble() * 15.0
        wheelHeight = 7.0 + random.nextDouble() * 14.0
        wheelTurn = if (random.nextBoolean()) 1.0 else -1.0
        planFor = 300 + random.nextInt(600)
    }

    /** Round and round on the wind, rising and falling a little as it goes round. */
    private fun wheel(level: ServerLevel) {
        val angle = atan2(z - wheelCentre.z, x - wheelCentre.x) + wheelTurn * WHEEL_LEAD
        val ground = max(floorAt(level, wheelCentre.x, wheelCentre.z, y), level.seaLevel.toDouble())
        val rise = sin(angle * 2.0 + id) * 2.0
        val target = Vec3(wheelCentre.x + cos(angle) * wheelRadius, ground + wheelHeight + rise, wheelCentre.z + sin(angle) * wheelRadius)
        fly(level, target, WHEEL_SPEED, WHEEL_STEER)
        if (planTicks > planFor) choose(level)
    }

    /** Off along the coast somewhere, then round and round over it. */
    private fun roam(level: ServerLevel) {
        fly(level, aim, ROAM_SPEED, STEER)
        val dx = aim.x - x
        val dz = aim.z - z
        if (dx * dx + dz * dz < 5.0 * 5.0 || planTicks > 600) {
            wheelCentre = Vec3(aim.x, 0.0, aim.z)
            wheelRadius = 7.0 + random.nextDouble() * 9.0
            wheelHeight = (aim.y - floorAt(level, aim.x, aim.z, aim.y)).coerceIn(5.0, 16.0)
            wheelTurn = if (random.nextBoolean()) 1.0 else -1.0
            plan = Plan.WHEEL
            planTicks = 0
            planFor = 200 + random.nextInt(400)
        }
    }

    /** What next, in the air. */
    private fun choose(level: ServerLevel) {
        planTicks = 0
        val h = home ?: blockPosition()
        val farHome = horizontal(position(), Vec3.atCenterOf(h))
        val roll = random.nextInt(100)
        plan = when {
            roosting(level) || farHome > HOME_LEASH -> if (farHome > HOME_LEASH && !roosting(level)) Plan.WHEEL else Plan.LANDING
            roll < 22 -> Plan.LANDING
            roll < 45 && fishingWater(level) -> Plan.FISH
            roll < 62 -> Plan.ROAM
            roll < 70 && cliffEdge(level) -> Plan.CLIFF
            else -> Plan.WHEEL
        }
        when (plan) {
            Plan.WHEEL -> wheelHome(level)
            Plan.ROAM -> {
                aim = roamPoint(level, h)
                planFor = 600
            }
            Plan.LANDING -> landAt = null
            Plan.CLIFF -> planFor = 120 + random.nextInt(200)
            else -> {}
        }
    }

    private fun roamPoint(level: ServerLevel, h: BlockPos): Vec3 {
        var best: Vec3? = null
        for (attempt in 0 until 8) {
            val angle = random.nextDouble() * 2.0 * PI
            val far = 12.0 + random.nextDouble() * (ROAM_RANGE - 12.0)
            val px = h.x + 0.5 + cos(angle) * far
            val pz = h.z + 0.5 + sin(angle) * far
            if (!level.hasChunkAt(Mth.floor(px), Mth.floor(pz))) continue
            val floor = floorAt(level, px, pz, y)
            val p = Vec3(px, max(floor, level.seaLevel.toDouble()) + 5.0 + random.nextDouble() * 8.0, pz)
            best = p
            // Along the shore or out over the water, rather than inland
            if (floor <= level.seaLevel + 3) break
        }
        return best ?: Vec3(h.x + 0.5, h.y + 12.0, h.z + 0.5)
    }

    // ---- Fishing ----

    /** Whether there's open water below or near to fish in. */
    private fun fishingWater(level: ServerLevel): Boolean {
        val spot = waterSpot(level) ?: return false
        aim = spot
        planFor = 400
        return true
    }

    /** A place on open water near it, deep enough to plunge into and with nothing over it: its surface. */
    private fun waterSpot(level: ServerLevel): Vec3? {
        for (attempt in 0 until 8) {
            val angle = random.nextDouble() * 2.0 * PI
            val far = 6.0 + random.nextDouble() * 18.0
            val px = Mth.floor(x + cos(angle) * far)
            val pz = Mth.floor(z + sin(angle) * far)
            if (!level.hasChunkAt(px, pz)) continue
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz) - 1
            val pos = BlockPos(px, top, pz)
            val fluid = level.getFluidState(pos)
            if (!fluid.`is`(FluidTags.WATER)) continue
            if (!level.getFluidState(pos.below()).`is`(FluidTags.WATER) || !level.getFluidState(pos.below(2)).`is`(FluidTags.WATER)) continue
            return Vec3(px + 0.5, top + fluid.getHeight(level, pos).toDouble(), pz + 0.5)
        }
        return null
    }

    /** Up over the water it means to fish, and when it's over it and high enough, down it goes. */
    private fun hunt(level: ServerLevel) {
        val over = Vec3(aim.x, aim.y + DIVE_HEIGHT, aim.z)
        fly(level, over, HUNT_SPEED, STEER)
        val dx = aim.x - x
        val dz = aim.z - z
        val height = y - aim.y
        if (dx * dx + dz * dz < DIVE_REACH * DIVE_REACH && height in 4.0..14.0) {
            diveFrom = position()
            diveTo = aim
            act(DIVE)
            return
        }
        if (planTicks > planFor) choose(level)
    }

    /** The plunge: faster and faster down onto the water, under it, and up again with a fish or without. */
    private fun plunge(level: ServerLevel) {
        val t = actionTicks.toDouble()
        when {
            t <= DIVE_HIT -> {
                val u = t / DIVE_HIT
                val drop = u * u
                // On along its line a little, and down ever faster
                val p = Vec3(Mth.lerp(u, diveFrom.x, diveTo.x), Mth.lerp(drop, diveFrom.y, diveTo.y), Mth.lerp(u, diveFrom.z, diveTo.z))
                deltaMovement = p.subtract(position())
                setPos(p)
                if (actionTicks == DIVE_HIT.toInt()) {
                    level.playSound(null, x, y, z, IslandRegistry.GULL_SPLASH.get(), SoundSource.NEUTRAL, 1f, voice())
                    level.sendParticles(ParticleTypes.SPLASH, x, y + 0.1, z, 24, 0.25, 0.1, 0.25, 0.25)
                    level.sendParticles(ParticleTypes.BUBBLE, x, y - 0.4, z, 10, 0.2, 0.2, 0.2, 0.05)
                }
            }
            t < DIVE_SURFACE -> {
                deltaMovement = Vec3.ZERO
                setPos(diveTo.x, diveTo.y - UNDER, diveTo.z)
            }
            else -> {
                // Up it comes, riding the water, with a fish or without
                setPos(diveTo.x, diveTo.y, diveTo.z)
                mode = Mode.FLOAT
                level.sendParticles(ParticleTypes.SPLASH, x, y + 0.1, z, 8, 0.2, 0.05, 0.2, 0.1)
                if (random.nextFloat() < CATCH) {
                    fish = true
                    eatIn = 50 + random.nextInt(90)
                }
                restFor = 60 + random.nextInt(200)
                nextIdle = 40 + random.nextInt(60)
            }
        }
    }

    /** A fish lying about within [range], on the ground or bobbing on the water, not already being gone for. */
    private fun fishNear(level: ServerLevel, range: Double): ItemEntity? =
        level.getEntitiesOfClass(ItemEntity::class.java, boundingBox.inflate(range)) { it.isAlive && it.item.`is`(ItemTags.FISHES) }
            .minByOrNull { distanceToSqr(it) }

    /** Down on a fish lying about and off with it. */
    private fun snatch(level: ServerLevel) {
        val item = level.getEntity(quarry) as? ItemEntity
        if (item == null || !item.isAlive || fish || planTicks > 240) {
            choose(level)
            return
        }
        fly(level, item.position().add(0.0, 0.3, 0.0), SNATCH_SPEED, 0.2, clearance = 0.2)
        if (distanceToSqr(item) < 1.2 * 1.2) {
            val stack = item.item
            stack.shrink(1)
            if (stack.isEmpty) item.discard() else item.setItem(stack)
            fish = true
            eatIn = 80 + random.nextInt(120)
            say(level, IslandRegistry.GULL_CRY.get(), 1f)
            deltaMovement = deltaMovement.add(0.0, 0.25, 0.0)
            plan = Plan.WHEEL
            planTicks = 0
            wheelHome(level)
        }
    }

    // ---- Ships, beggars and cliffs: hanging on the wind ----

    /** Looks about it in the air: a ship to follow, someone with a fish, a fish lying about. */
    private fun notice(level: ServerLevel) {
        if (roosting(level) || plan == Plan.MOB || plan == Plan.SNATCH || plan == Plan.LANDING && landAt != null) return
        if (!fish) {
            val food = fishNear(level, SNATCH_RANGE)
            if (food != null && random.nextInt(3) != 0) {
                quarry = food.id
                plan = Plan.SNATCH
                planTicks = 0
                return
            }
        }
        if (plan == Plan.SHIP || plan == Plan.BEG) return
        // Someone holding a fish: it comes and begs for it
        for (player in level.players()) {
            if (player.isSpectator || distanceToSqr(player) > BEG_RANGE * BEG_RANGE) continue
            if (holdsFish(player) && random.nextInt(3) == 0 && followers(level, Plan.BEG, player.id) < 2) {
                quarry = player.id
                hoverSide = (random.nextDouble() - 0.5) * 2.5
                plan = Plan.BEG
                planTicks = 0
                planFor = 300 + random.nextInt(300)
                return
            }
        }
        // A ship under way: in behind her, on the wind
        if (random.nextInt(3) != 0) return
        for (vessel in level.getEntitiesOfClass(Entity::class.java, boundingBox.inflate(SHIP_RANGE)) { it is Cog || it is AbstractBoat }) {
            if (vessel.deltaMovement.horizontalDistance() < SHIP_UNDER_WAY) continue
            if (followers(level, Plan.SHIP, vessel.id) >= SHIP_FOLLOWERS) continue
            quarry = vessel.id
            hoverSide = (random.nextDouble() - 0.5) * if (vessel is Cog) 5.0 else 2.0
            plan = Plan.SHIP
            planTicks = 0
            planFor = 400 + random.nextInt(800)
            return
        }
    }

    private fun holdsFish(player: Player): Boolean = player.mainHandItem.`is`(ItemTags.FISHES) || player.offhandItem.`is`(ItemTags.FISHES)

    private fun followers(level: ServerLevel, what: Plan, target: Int): Int =
        level.getEntitiesOfClass(Gull::class.java, boundingBox.inflate(48.0)) { it.plan == what && it.quarry == target && it !== this }.size

    /** Whether there's a cliff edge, or a high point, near home to hang over on the wind; it remembers where. */
    private fun cliffEdge(level: ServerLevel): Boolean {
        val h = home ?: return false
        for (attempt in 0 until 6) {
            val px = h.x + random.nextInt(25) - 12
            val pz = h.z + random.nextInt(25) - 12
            if (!level.hasChunkAt(px, pz)) continue
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz)
            if (top < level.seaLevel + 8) continue
            val into = Wind.from(level)
            // Out from the edge on the windward side, where the wind comes up the cliff
            aim = Vec3(px + 0.5 + forwardX(into) * 2.0, top + 2.5 + random.nextDouble() * 3.0, pz + 0.5 + forwardZ(into) * 2.0)
            return true
        }
        return false
    }

    /** Where it hangs just now, or null when there's nothing to hang on any more. */
    private fun hoverPoint(level: ServerLevel): Vec3? {
        if (planTicks > planFor) return null
        when (plan) {
            Plan.SHIP -> {
                val vessel = level.getEntity(quarry) ?: return null
                if (!vessel.isAlive || vessel.deltaMovement.horizontalDistance() < SHIP_UNDER_WAY * 0.6) return null
                val h = home
                if (h != null && horizontal(position(), Vec3.atCenterOf(h)) > SHIP_LEASH) return null
                val lead = vessel.deltaMovement.scale(4.0)
                return if (vessel is Cog) vessel.localToWorld(hoverSide, 4.5 + sin(planTicks * 0.031 + id) * 0.8, -7.0 - abs(hoverSide) * 0.4).add(lead)
                else Vec3(vessel.x - forwardX(vessel.yRot) * 2.5 + hoverSide, vessel.y + 2.6, vessel.z - forwardZ(vessel.yRot) * 2.5).add(lead)
            }
            Plan.BEG -> {
                val player = level.getEntity(quarry) as? Player ?: return null
                if (!player.isAlive || !holdsFish(player) || distanceToSqr(player) > (BEG_RANGE + 6) * (BEG_RANGE + 6)) return null
                val look = player.lookAngle
                val flat = sqrt(look.x * look.x + look.z * look.z).coerceAtLeast(0.1)
                return player.eyePosition.add(look.x / flat * 2.4 + hoverSide * look.z / flat, 1.1 + sin(planTicks * 0.05 + id) * 0.3, look.z / flat * 2.4 - hoverSide * look.x / flat)
            }
            Plan.CLIFF -> return aim
            else -> return null
        }
    }

    /** Hanging on the wind behind a ship, before a beggar's mark, over a cliff: a spring toward its point, damped. */
    private fun hoverTick(level: ServerLevel) {
        val at = hoverPoint(level)
        if (at == null || action == LAND || action == DIVE) {
            mode = Mode.FLY
            choose(level)
            return
        }
        if (scanDue()) notice(level)
        if (plan != Plan.SHIP && plan != Plan.BEG && plan != Plan.CLIFF) {
            mode = Mode.FLY
            return
        }
        val to = at.subtract(position())
        // Working its wings to hold its place, a little unsteady on the gusts
        val gust = Wind.strength(level)
        val wobble = Vec3(sin(tickCount * 0.13 + id) * 0.012, sin(tickCount * 0.21 + id * 2) * 0.01, cos(tickCount * 0.11 + id) * 0.012).scale(0.5 + gust)
        var v = deltaMovement.scale(HOVER_DAMP).add(to.scale(HOVER_PULL)).add(wobble)
        val sp = v.length()
        if (sp > HOVER_MAX) v = v.scale(HOVER_MAX / sp)
        deltaMovement = v
        move(MoverType.SELF, v)
        // Facing into the wind, or the way the ship goes
        val face = when (plan) {
            Plan.SHIP -> level.getEntity(quarry)?.yRot ?: Wind.from(level)
            Plan.BEG -> lookYaw(level) ?: yRot
            else -> Wind.from(level)
        }
        yRot = Mth.rotLerp(0.12f, yRot, face)
        yBodyRot = yRot
        yHeadRot = yRot
        if (--nextCry <= 0) {
            nextCry = (if (plan == Plan.BEG) 40 else 120) + random.nextInt(if (plan == Plan.BEG) 80 else 260)
            say(level, IslandRegistry.GULL_CRY.get(), if (plan == Plan.BEG) 1f else 0.8f)
        }
        if (plan == Plan.BEG && !fish) fishNear(level, 6.0)?.let {
            // Thrown it a fish: down after it
            quarry = it.id
            plan = Plan.SNATCH
            planTicks = 0
            mode = Mode.FLY
        }
    }

    /** Begging, it faces whoever has the fish. */
    private fun lookYaw(level: ServerLevel): Float? {
        val player = level.getEntity(quarry) as? Player ?: return null
        return yawOf(player.x - x, player.z - z)
    }

    // ---- Mobbing ----

    /** Goes for [thief], who has robbed the nest at [nest], for [ticks]. */
    fun mob(thief: Player, nest: BlockPos, ticks: Int) {
        mobbing = thief.uuid
        mobNest = nest
        mobUntil = level().gameTime + ticks
        swoop = 0
        walkTo = null
        val level = level() as? ServerLevel ?: return
        if (mode == Mode.STAND || mode == Mode.FLOAT) {
            if (action != TAKEOFF) {
                act(TAKEOFF)
                faceYaw(yawOf(thief.x - x, thief.z - z))
            }
        } else if (mode == Mode.HOVER) mode = Mode.FLY
        plan = Plan.MOB
        planTicks = 0
        if (random.nextInt(2) == 0) say(level, IslandRegistry.GULL_ALARM.get(), 1f)
    }

    /** Round behind them and up, then down at their head, a jab in passing, and up and away to come round again. */
    private fun mob(level: ServerLevel) {
        val thief = mobbing?.let { level.getPlayerByUUID(it) }
        val nest = mobNest
        if (thief == null || !thief.isAlive || thief.isSpectator || level.gameTime > mobUntil ||
            (nest != null && thief.distanceToSqr(Vec3.atCenterOf(nest)) > MOB_GIVE_UP * MOB_GIVE_UP)
        ) {
            mobbing = null
            choose(level)
            return
        }
        val head = thief.eyePosition
        when (swoop) {
            0 -> {
                val back = Vec3(-forwardX(thief.yRot), 0.0, -forwardZ(thief.yRot))
                val wait = head.add(back.scale(6.0)).add(sin(id.toDouble()) * 3.0, 4.0 + (id % 3), cos(id.toDouble()) * 3.0)
                fly(level, wait, 0.45, 0.14)
                if (position().distanceToSqr(wait) < 2.5 * 2.5 || planTicks > 80) {
                    swoop = 1
                    planTicks = 0
                    if (random.nextInt(2) == 0) say(level, IslandRegistry.GULL_ALARM.get(), 1f)
                }
            }
            1 -> {
                fly(level, head, SWOOP_SPEED, 0.22, clearance = 0.4)
                if (position().distanceToSqr(head) < STRIKE_REACH * STRIKE_REACH) {
                    if (tickCount - lastStrike > STRIKE_GAP) {
                        lastStrike = tickCount
                        thief.hurtServer(level, damageSources().mobAttack(this), STRIKE)
                    }
                    swoop = 2
                    planTicks = 0
                    val on = deltaMovement.multiply(1.0, 0.0, 1.0).normalize()
                    swoopAway = position().add(on.scale(8.0)).add(0.0, 5.0, 0.0)
                } else if (planTicks > 60) {
                    swoop = 0
                    planTicks = 0
                }
            }
            else -> {
                fly(level, swoopAway, 0.5, 0.16)
                if (planTicks > 20) {
                    swoop = 0
                    planTicks = 0
                }
            }
        }
    }

    // ---- Coming in to land ----

    /** Picks somewhere to land, and comes in on it from downwind; when it's close, the landing (see [landing]). */
    private fun comeIn(level: ServerLevel) {
        var spot = landAt
        if (spot == null || planTicks % 40 == 39 && !stillFree(level, spot)) {
            spot = landingSpot(level)
            landAt = spot
            if (spot == null) {
                plan = Plan.WHEEL
                wheelHome(level)
                return
            }
        }
        val dx = spot.x - x
        val dz = spot.z - z
        val flat = sqrt(dx * dx + dz * dz)
        val speed = deltaMovement.length()
        val reach = (speed * LAND_TOUCH / 2.0).coerceIn(1.6, 4.5)
        val heading = deltaMovement.horizontalDistance()
        val toward = if (heading > 1.0e-3 && flat > 1.0e-3) (deltaMovement.x * dx + deltaMovement.z * dz) / (heading * flat) else 0.0
        if (flat < reach && y - spot.y in 0.2..5.0 && toward > 0.4) {
            landFrom = position()
            landTo = spot
            act(LAND)
            return
        }
        // Out a little way and in at it, low over the last stretch
        val target = if (flat > 9.0) Vec3(spot.x - dx / flat * 6.0, spot.y + 3.0, spot.z - dz / flat * 6.0) else Vec3(spot.x, spot.y + 1.0, spot.z)
        fly(level, target, if (flat > 9.0) APPROACH_SPEED else FINAL_SPEED, if (flat > 9.0) STEER else 0.18, clearance = if (flat > 9.0) CLEARANCE else 0.5)
        if (planTicks > 500) {
            landAt = null
            planTicks = 0
        }
    }

    /** Somewhere to land: its own nest, if it has one; a perch near home, high up for choice; or the water. */
    private fun landingSpot(level: ServerLevel): Vec3? {
        val h = home ?: blockPosition()
        landOnWater = false
        if (level.hasChunkAt(h.x, h.z) && level.getBlockState(h).`is`(IslandRegistry.GULL_NEST.get())) {
            val nest = Vec3(h.x + 0.5, h.y + NEST_TOP, h.z + 0.5)
            if (stillFree(level, nest)) return nest
        }
        var best: Vec3? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (attempt in 0 until 14) {
            val px = h.x + random.nextInt(25) - 12
            val pz = h.z + random.nextInt(25) - 12
            if (!level.hasChunkAt(px, pz)) continue
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz)
            val ground = BlockPos(px, top - 1, pz)
            val state = level.getBlockState(ground)
            if (!level.getFluidState(ground).isEmpty) {
                if (random.nextInt(4) == 0 && level.getFluidState(ground).`is`(FluidTags.WATER)) {
                    val at = Vec3(px + 0.5, ground.y + level.getFluidState(ground).getHeight(level, ground).toDouble(), pz + 0.5)
                    if (best == null) {
                        best = at
                        landOnWater = true
                    }
                }
                continue
            }
            if (!perchable(level, ground, state)) continue
            val at = Vec3(px + 0.5, top.toDouble(), pz + 0.5)
            if (!stillFree(level, at)) continue
            // Up high, and near home, for choice
            val score = top - level.seaLevel - 0.15 * horizontal(at, Vec3.atCenterOf(h)) + random.nextDouble() * 4.0
            if (score > bestScore) {
                bestScore = score
                best = at
                landOnWater = false
            }
        }
        return best
    }

    private fun perchable(level: ServerLevel, ground: BlockPos, state: BlockState): Boolean {
        if (!state.isFaceSturdy(level, ground, net.minecraft.core.Direction.UP)) return false
        // Not in a tree, nor on a fire
        if (state.`is`(net.minecraft.tags.BlockTags.LEAVES) || state.`is`(net.minecraft.tags.BlockTags.FIRE) || state.`is`(net.minecraft.tags.BlockTags.CAMPFIRES)) return false
        val above = ground.above()
        return level.getBlockState(above).getCollisionShape(level, above).isEmpty && level.getBlockState(above.above()).getCollisionShape(level, above.above()).isEmpty
    }

    /** Whether nobody's standing on [spot] (another gull) or close by it (a player). */
    private fun stillFree(level: ServerLevel, spot: Vec3): Boolean {
        val box = AABB(spot.x - 0.4, spot.y - 0.2, spot.z - 0.4, spot.x + 0.4, spot.y + 0.6, spot.z + 0.4)
        if (level.getEntitiesOfClass(Gull::class.java, box) { it !== this }.isNotEmpty()) return false
        return level.players().none { !it.isSpectator && it.distanceToSqr(spot) < 3.0 * 3.0 }
    }

    /** The landing: flaring, slowing, on an arc down onto the spot, its feet touching at [LAND_TOUCH]; then it settles. */
    private fun landing(level: ServerLevel) {
        val t = actionTicks.toDouble()
        if (t < LAND_TOUCH) {
            val u = t / LAND_TOUCH
            val ease = 1.0 - (1.0 - u) * (1.0 - u)
            val p = Vec3(
                Mth.lerp(ease, landFrom.x, landTo.x),
                Mth.lerp(ease, landFrom.y, landTo.y) + sin(PI * u) * FLARE_RISE,
                Mth.lerp(ease, landFrom.z, landTo.z),
            )
            deltaMovement = p.subtract(position())
            setPos(p)
            val dx = landTo.x - landFrom.x
            val dz = landTo.z - landFrom.z
            if (dx * dx + dz * dz > 0.01) {
                yRot = Mth.rotLerp(0.3f, yRot, yawOf(dx, dz))
                yBodyRot = yRot
                yHeadRot = yRot
            }
            return
        }
        setPos(landTo)
        deltaMovement = Vec3.ZERO
        landAt = null
        mode = if (landOnWater) Mode.FLOAT else Mode.STAND
        if (landOnWater) {
            level.playSound(null, x, y, z, IslandRegistry.GULL_SPLASH.get(), SoundSource.NEUTRAL, 0.4f, 1.4f)
            level.sendParticles(ParticleTypes.SPLASH, x, y + 0.05, z, 8, 0.25, 0.02, 0.25, 0.05)
        }
        restFor = (if (roosting(level)) 2400 else 200) + random.nextInt(1000)
        nextIdle = 30 + random.nextInt(80)
    }

    /** Dusk, night, or a storm: home to roost, or stay put. */
    private fun roosting(level: ServerLevel): Boolean = level.isDarkOutside || level.isThundering

    // ---- What it takes fright at ----

    private fun scanDue(): Boolean {
        if (--nextScan > 0) return false
        nextScan = SCAN_EVERY
        return true
    }

    /** Something coming too close: someone walking up on it (they can creep a little closer), a monster, a boat or ship. */
    private fun threat(level: ServerLevel, onWater: Boolean): Entity? {
        for (player in level.players()) {
            if (player.isSpectator) continue
            val reach = if (player.isShiftKeyDown) SPOOK_CREEP else SPOOK
            if (distanceToSqr(player) < reach * reach && (player.vehicle == null || onWater)) return player
        }
        val near = level.getEntities(this, boundingBox.inflate(if (onWater) SPOOK_BOAT else SPOOK_MONSTER)) {
            it is Monster || (onWater && (it is Cog || it is AbstractBoat) && it.deltaMovement.horizontalDistance() > 0.02)
        }
        return near.firstOrNull()
    }

    // ---- Hurt ----

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean {
        val hurt = super.hurtServer(level, source, damage)
        if (!hurt) return false
        feathers(level, 6)
        if (!isAlive) return true
        if ((mode == Mode.STAND || mode == Mode.FLOAT) && action != TAKEOFF && action != DIVE) flee(level, source.entity)
        // Strike one, and the rest come at you
        val attacker = source.entity as? Player
        if (attacker != null && !attacker.isCreative) {
            var mobbers = 0
            for (other in level.getEntitiesOfClass(Gull::class.java, boundingBox.inflate(RALLY_RANGE)) { it !== this && it.isAlive && it.mobbing == null }) {
                if (mobbers >= RALLY_MOBBERS) break
                other.mob(attacker, blockPosition(), RALLY_TICKS + random.nextInt(80))
                mobbers++
            }
        }
        return true
    }

    private fun feathers(level: ServerLevel, count: Int) {
        level.sendParticles(ItemParticleOption(ParticleTypes.ITEM, Items.FEATHER), x, y + 0.3, z, count, 0.2, 0.2, 0.2, 0.06)
    }

    override fun die(source: DamageSource) {
        super.die(source)
        (level() as? ServerLevel)?.let { feathers(it, 14) }
    }

    // ---- Sounds ----

    /** Cries out (its beak opening on clients, unless the action opens it). */
    private fun say(level: ServerLevel, sound: SoundEvent, volume: Float, beak: Boolean = true) {
        level.playSound(null, x, y, z, sound, SoundSource.NEUTRAL, volume, voice())
        if (beak) level.broadcastEntityEvent(this, CRY_EVENT)
    }

    /** The black-backed gulls' voices are deeper, the young ones' thinner. */
    private fun voice(): Float = (when (variant) {
        BLACKBACK -> 0.82f
        YOUNG -> 1.18f
        else -> 1f
    }) * (0.94f + random.nextFloat() * 0.12f)

    override fun handleEntityEvent(event: Byte) {
        if (event == CRY_EVENT) cryTicks = CRY_TICKS else super.handleEntityEvent(event)
    }

    override fun getAmbientSound(): SoundEvent? = null
    override fun getHurtSound(source: DamageSource): SoundEvent = IslandRegistry.GULL_ALARM.get()
    override fun getDeathSound(): SoundEvent = IslandRegistry.GULL_CRY.get()
    override fun getVoicePitch(): Float = voice()
    override fun playStepSound(pos: BlockPos, state: BlockState) {}

    // ---- On a client ----

    private fun clientTick() {
        flyingO = flying
        spreadO = spread
        flapO = flap
        flapPhaseO = flapPhase
        bankO = bank
        hoverO = hover
        floatingO = floating
        beakO = beak
        walkO = walk
        walkPhaseO = walkPhase
        pitchO = pitch
        val m = mode
        val a = action
        val vx = x - xo
        val vy = y - yo
        val vz = z - zo
        val flat = sqrt(vx * vx + vz * vz)
        val air = m == Mode.FLY || m == Mode.HOVER
        flying = approach(flying, if (air) 1f else 0f, 0.3f)
        spread = approach(spread, if (air) 1f else 0f, 0.25f)
        // How hard it beats: climbing, slow, hanging on the wind, and a few beats now and then between glides
        var wantFlap = 0f
        if (air) {
            val climb = (vy * 5.0).toFloat()
            val slow = ((0.3 - flat) * 2.0).toFloat().coerceAtLeast(0f)
            wantFlap = (0.08f + climb + slow).coerceIn(0f, 1f)
            if (m == Mode.HOVER) wantFlap = max(wantFlap, 0.3f + 0.15f * sin(tickCount * 0.07f + id))
            if ((tickCount + id * 7) % 110 < 16) wantFlap = max(wantFlap, 0.5f)
            if (a == TAKEOFF) wantFlap = 1f
        }
        flap = approach(flap, wantFlap, 0.15f)
        flapPhase = (flapPhase + 0.33f + 0.42f * flap) % TAU
        // Banked into its turns
        val turn = Mth.wrapDegrees(yRot - yRotO)
        bank = approach(bank, if (air && a != LAND && a != DIVE) (turn / BANK_TURN).coerceIn(-1f, 1f) else 0f, 0.2f)
        hover = approach(hover, if (m == Mode.HOVER) 1f else 0f, 0.1f)
        floating = approach(floating, if (m == Mode.FLOAT && a != DIVE) 1f else 0f, 0.25f)
        val stepping = if (m == Mode.STAND) (flat / WALK_SPEED).toFloat().coerceIn(0f, 1f) else 0f
        walk = approach(walk, stepping, 0.3f)
        walkPhase = (walkPhase + (flat * STRIDE).toFloat()) % TAU
        // Nose down in a glide, up in a climb (a takeoff, a landing and a dive the model poses itself)
        val wantPitch = if (air && a != LAND && a != DIVE && a != TAKEOFF) (atan2(vy, max(flat, 0.08)) * Mth.RAD_TO_DEG).toFloat().coerceIn(-40f, 30f) else 0f
        pitch = approach(pitch, wantPitch, 0.2f)
        if (cryTicks > 0) cryTicks--
        beak = approach(beak, if (cryTicks > 0) 1f else 0f, 0.5f)
        // A little wake as it paddles
        if (m == Mode.FLOAT && flat > 0.005 && tickCount % 9 == 0) {
            level().addParticle(ParticleTypes.FISHING, x - forwardX(yRot) * 0.3, y + 0.05, z - forwardZ(yRot) * 0.3, 0.0, 0.0, 0.0)
        }
    }

    // ---- The rest ----

    override fun travel(input: Vec3) {}
    override fun isPushedByFluid(): Boolean = false
    override fun causeFallDamage(fallDistance: Double, multiplier: Float, source: DamageSource): Boolean = false
    override fun isPushable(): Boolean = false
    override fun pushEntities() {}
    override fun canBeLeashed(): Boolean = false
    override fun canRide(vehicle: Entity): Boolean = false
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false
    override fun getMaxHeadYRot(): Int = 100

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putInt("Variant", variant)
        output.putBoolean("Fish", fish)
        home?.let { output.store("Home", BlockPos.CODEC, it) }
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        variant = input.getIntOr("Variant", HERRING).coerceIn(0, 2)
        fish = input.getBooleanOr("Fish", false)
        home = input.read("Home", BlockPos.CODEC).orElse(null)
    }

    companion object {
        private val DATA_MODE: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Gull::class.java, EntityDataSerializers.BYTE)
        private val DATA_ACTION: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Gull::class.java, EntityDataSerializers.BYTE)
        private val DATA_ACTION_AT: EntityDataAccessor<Int> = SynchedEntityData.defineId(Gull::class.java, EntityDataSerializers.INT)
        private val DATA_VARIANT: EntityDataAccessor<Byte> = SynchedEntityData.defineId(Gull::class.java, EntityDataSerializers.BYTE)
        private val DATA_FISH: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(Gull::class.java, EntityDataSerializers.BOOLEAN)
        private val MODES = Mode.entries.toTypedArray()

        // The actions, numbered as client/island/GullRenderState numbers them
        const val NONE = 0
        const val PREEN = 1
        const val CALL = 2
        const val PECK = 3
        const val RUFFLE = 4
        const val THREAT = 5
        const val TAKEOFF = 6
        const val LAND = 7
        const val DIVE = 8

        /** How long each action lasts (ticks), as GullModel.ACTION_TICKS has it. */
        val ACTION_TICKS = intArrayOf(0, 70, 64, 22, 30, 44, 22, 26, 50)
        /** The model's marks: the feet leaving the ground, touching down, the beak hitting the water, coming up again. */
        const val TAKEOFF_LIFT = 8
        const val LAND_TOUCH = 17.0
        const val DIVE_HIT = 20.0
        const val DIVE_SURFACE = 34.0

        const val HERRING = 0
        const val BLACKBACK = 1
        const val YOUNG = 2

        /** Ticks behind the server a client sees a gull's moves. */
        private const val CLIENT_LAG = 1f
        private const val CRY_EVENT: Byte = 81
        private const val CRY_TICKS = 12
        private const val TAU = (PI * 2.0).toFloat()

        /** Every so many ticks it looks about it (staggered by when each was made). */
        private const val SCAN_EVERY = 10
        private const val COLD = 0.3f
        private const val YOUNG_SHARE = 0.15f

        // On its feet and on the water
        private const val WALK_SPEED = 0.055
        private const val WALK_GIVE_UP = 120
        private const val GRAVITY = 0.06
        private const val PADDLE = 0.012
        private const val CALL_AT = 5
        private const val THREAT_AT = 4
        private const val FLARE_AT = 3
        private const val ANSWER_RANGE = 12.0
        private const val ANSWER_CHANCE = 0.35f
        private const val LOOK_RANGE = 8.0
        private const val SQUABBLE_RANGE = 1.3
        private const val SQUABBLE_ODDS = 30
        /** How close someone can come (creeping, closer), a monster, a moving boat; how far a fright spreads through the colony. */
        private const val SPOOK = 4.5
        private const val SPOOK_CREEP = 2.2
        private const val SPOOK_MONSTER = 4.0
        private const val SPOOK_BOAT = 6.0
        private const val SPOOK_SPREAD = 6.0

        // Into the air
        private const val LIFT_FORWARD = 0.16
        private const val LIFT_UP = 0.3

        // Flying: blocks a tick, how hard it steers, how far it keeps over the land and sea, how far ahead it looks
        private const val MAX_SPEED = 0.62
        private const val WHEEL_SPEED = 0.3
        private const val ROAM_SPEED = 0.42
        private const val HUNT_SPEED = 0.36
        private const val APPROACH_SPEED = 0.36
        private const val FINAL_SPEED = 0.26
        private const val SNATCH_SPEED = 0.42
        private const val SWOOP_SPEED = 0.58
        private const val STEER = 0.1
        private const val WHEEL_STEER = 0.07
        /** How far round the circle ahead of it a wheeling gull aims (radians). */
        private const val WHEEL_LEAD = 0.55
        private const val CLEARANCE = 3.0
        private const val LOOK_AHEAD = 10.0
        /** Degrees a tick of turning that bank it right over. */
        private const val BANK_TURN = 7f
        private const val STRIDE = 9.0
        private const val HOME_LEASH = 72.0
        private const val ROAM_RANGE = 44.0

        // Fishing: how high it goes over the water, how near over the spot it must be, how deep it goes, how often it comes up with one
        private const val DIVE_HEIGHT = 8.0
        private const val DIVE_REACH = 3.0
        private const val UNDER = 0.45
        private const val CATCH = 0.6f
        private const val SNATCH_RANGE = 14.0

        // Hanging on the wind
        private const val HOVER_DAMP = 0.8
        private const val HOVER_PULL = 0.05
        private const val HOVER_MAX = 0.75
        private const val BEG_RANGE = 10.0
        private const val SHIP_RANGE = 40.0
        private const val SHIP_UNDER_WAY = 0.12
        private const val SHIP_FOLLOWERS = 3
        private const val SHIP_LEASH = 140.0

        // Mobbing
        private const val STRIKE = 1f
        private const val STRIKE_REACH = 1.4
        private const val STRIKE_GAP = 30
        private const val MOB_GIVE_UP = 40.0
        private const val MOB_CALL = 24.0
        private const val MOB_TICKS = 240
        private const val MOB_MOST = 6
        private const val RALLY_RANGE = 16.0
        private const val RALLY_MOBBERS = 4
        private const val RALLY_TICKS = 100

        // Landing: how high over its nest's floor it stands, how much it rises as it flares
        private const val NEST_TOP = 0.25
        private const val FLARE_RISE = 0.35

        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 4.0)
            .add(Attributes.MOVEMENT_SPEED, 0.25)
            .add(Attributes.FLYING_SPEED, 0.5)
            .add(Attributes.FOLLOW_RANGE, 16.0)

        /** [thief] has taken an egg from the nest at [nest]: the gulls round about come at them, the nearest few swooping. */
        fun robbed(level: ServerLevel, nest: BlockPos, thief: Player) {
            val gulls = level.getEntitiesOfClass(Gull::class.java, AABB(nest).inflate(MOB_CALL)) { it.isAlive }
                .sortedBy { it.distanceToSqr(Vec3.atCenterOf(nest)) }
            for ((i, gull) in gulls.withIndex()) {
                if (i < MOB_MOST) gull.mob(thief, nest, MOB_TICKS + level.random.nextInt(120))
                else if (gull.mode == Mode.STAND || gull.mode == Mode.FLOAT) gull.spookIn = 2 + level.random.nextInt(12)
            }
        }

        private fun approach(from: Float, to: Float, rate: Float): Float {
            val next = from + (to - from) * rate
            return if (abs(to - next) < 0.001f) to else next
        }

        private fun forwardX(yaw: Float): Double = -sin(yaw * Mth.DEG_TO_RAD).toDouble()
        private fun forwardZ(yaw: Float): Double = cos(yaw * Mth.DEG_TO_RAD).toDouble()
        private fun yawOf(dx: Double, dz: Double): Float = (atan2(dz, dx) * Mth.RAD_TO_DEG).toFloat() - 90f

        private fun horizontal(a: Vec3, b: Vec3): Double {
            val dx = a.x - b.x
            val dz = a.z - b.z
            return sqrt(dx * dx + dz * dz)
        }
    }
}
