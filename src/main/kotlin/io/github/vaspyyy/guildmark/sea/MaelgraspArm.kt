package io.github.vaspyyy.guildmark.sea

import io.github.vaspyyy.guildmark.ship.Cog
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.entity.PartEntity
import org.joml.Vector3f
import org.joml.Vector3fc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One of the Maelgrasp's arms, risen from the sea beside a ship it has seized. It towers over her rail, swaying, and
 * on its master's word ([Maelgrasp]) it rears and slams down across her deck, sweeps low along it knocking everyone
 * in its way overboard, snatches someone up and hurls them into the sea, or drapes itself over her rail and holds on,
 * hauling her over. Whoever it has caught can struggle free (crouch, again and again) or be cut free by the others.
 * Hacked through, it's severed where it was struck: the length cut off falls into the sea and the stump sinks away.
 *
 * Its shape is worked out by [Tentacle], alike on the server and on every client, from the few numbers it carries: so
 * its boxes ([SeaPart]s, one at each joint) are where it's drawn. Blows on it hurt it and its master alike.
 */
class MaelgraspArm(type: EntityType<out MaelgraspArm>, level: Level) : Monster(type, level) {
    enum class Mode(val attacking: Boolean = false, val holding: Boolean = false) {
        RISE,
        IDLE,
        REAR(attacking = true),
        SLAM(attacking = true),
        LINGER(attacking = true),
        DRAW_BACK(attacking = true),
        SWEEP(attacking = true),
        REACH(attacking = true),
        LIFT(attacking = true),
        SQUEEZE(attacking = true),
        FLING(attacking = true),
        DRAPE(holding = true),
        HOLD(holding = true),
        RECOVER,
        WITHDRAW,
        LIMP,
    }

    /** How it lies: its tip and the way it comes at it (in its own frame, see [Tentacle]), and how it curls, ripples and grips. */
    class Pose {
        val tip = Vector3f(0f, -3.5f, 0.6f)
        val from = Vector3f(0f, -1f, 0f)
        var curl = 0.9f
        var ripple = 0.3f
        var grip = 0f

        fun copy(other: Pose) {
            tip.set(other.tip)
            from.set(other.from)
            curl = other.curl
            ripple = other.ripple
            grip = other.grip
        }
    }

    /** This tick's pose and last tick's, on either side (on a client, as last heard from the server). */
    val pose = Pose()
    val poseO = Pose()

    /** Its joints as it lies this tick. */
    val shape = Tentacle.Shape()

    /** The boxes along it, one at each joint past its root. */
    private val parts: Array<SeaPart> = Array(Tentacle.SEGMENTS) { i ->
        val w = 2f * Tentacle.RADII[i + 1] + PART_MARGIN
        SeaPart(this, w, w) { _, level, source, amount -> struckAt(i + 1, level, source, amount) }
    }

    // ---- On the server ----

    private var shipId = -1
    private var modeTicks = 0
    /** How long it holds on, this time. */
    private var holdFor = 0
    /** Where across her deck (forward of her middle) it's going to slam, or where a sweep starts. */
    private var aimZ = 0.0
    /** Who it's reaching for, and who it has caught. */
    private var prey: Entity? = null
    private var seized: Entity? = null
    private var seizedTicks = 0
    /** Damage it's taken since it caught someone: enough, and it lets them go. */
    private var gripHurt = 0f
    private var struggles = 0
    private var lastStruggle = -100
    private var releasing = false
    /** Everyone a sweep has already struck. */
    private val swept = HashSet<Int>()
    /** Where it was when a move it makes along a set path began. */
    private val startTip = Vector3f()
    private val startFrom = Vector3f()
    private var startCurl = 0f
    /** The joint the last blow on it landed at: hacked through, it's severed there. */
    private var lastStruck = 4
    private var reloaded = false

    // ---- On a client ----

    /** The length hacked off it, as it lay the moment it was severed (its joints, in its frame), and when. */
    var severed: FloatArray? = null
        private set
    var severedAt = 0
        private set

    /** The mode it was last seen in, for what marks a change (the blow of a slam landing); the client's effects keep it. */
    var seenMode = Mode.RISE

    /** The tick this client saw it catch whoever it holds, or -1: their struggles in its coils keep time with its squeezes. */
    var caughtAt = -1
        private set

    init {
        xpReward = 0
        isNoGravity = true
        noPhysics = true
        setPersistenceRequired()
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_MODE, Mode.RISE.ordinal.toByte())
        builder.define(DATA_BOSS, -1)
        builder.define(DATA_SLOT, 0.toByte())
        builder.define(DATA_YAW, 0f)
        builder.define(DATA_TIP, Vector3f(0f, -3.5f, 0.6f))
        builder.define(DATA_FROM, Vector3f(0f, -1f, 0f))
        builder.define(DATA_CURL, 0.9f)
        builder.define(DATA_RIPPLE, 0.3f)
        builder.define(DATA_GRIP, 0f)
        builder.define(DATA_CUT, Tentacle.SEGMENTS.toByte())
    }

    var mode: Mode
        get() = MODES[entityData.get(DATA_MODE).toInt().coerceIn(0, MODES.size - 1)]
        private set(value) {
            if (value != mode) entityData.set(DATA_MODE, value.ordinal.toByte())
            modeTicks = 0
        }

    /** The Maelgrasp's entity id, the place round the ship it rose at, and the way it faces (to her middle). */
    val bossId: Int get() = entityData.get(DATA_BOSS)
    val slot: Int get() = entityData.get(DATA_SLOT).toInt()
    val armYaw: Float get() = entityData.get(DATA_YAW)

    /** How many of its segments are left on it, root first: [Tentacle.SEGMENTS] till it's hacked through. */
    val cut: Int get() = entityData.get(DATA_CUT).toInt()

    /** Its own phase, so no two arms ripple and sway alike. */
    val phase: Float get() = slot * 1.37f + 0.5f

    /** Ripples keep the world's time, which every client shares with the server. */
    fun time(partial: Float): Float = (level().gameTime % RIPPLE_PERIOD).toFloat() + partial

    /** Free for its master to send at the ship. */
    val ready: Boolean get() = isAlive && mode == Mode.IDLE

    // ---- Parts ----

    override fun isMultipartEntity(): Boolean = true
    override fun getParts(): Array<out PartEntity<*>> = parts

    override fun recreateFromPacket(packet: ClientboundAddEntityPacket) {
        super.recreateFromPacket(packet)
        for (i in parts.indices) parts[i].setId(packet.id + i + 1)
    }

    private fun placeParts() {
        val origin = position()
        val yaw = armYaw
        for (i in parts.indices) parts[i].placeAt(shape.world(i + 1, origin, yaw))
    }

    // ---- Set going ----

    /** Rises at [slot] beside [ship] for [boss], tougher for land [tier]. */
    fun riseFor(boss: Maelgrasp, ship: Cog, slot: Int, tier: Int) {
        entityData.set(DATA_BOSS, boss.id)
        entityData.set(DATA_SLOT, slot.toByte())
        shipId = ship.id
        val extra = (tier - 1).coerceAtLeast(0)
        if (extra > 0) getAttribute(Attributes.MAX_HEALTH)?.addOrReplacePermanentModifier(AttributeModifier(TIER_HEALTH, 0.35 * extra, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        health = maxHealth
        (level() as? ServerLevel)?.let { follow(it, ship) }
        mode = Mode.RISE
    }

    /** Rears over [z] (forward of her middle) and slams down across her deck there. */
    fun slam(z: Double) {
        if (!ready) return
        aimZ = z
        mode = Mode.REAR
        playSound(SeaRegistry.REAR.get(), 1.4f, 0.9f + random.nextFloat() * 0.2f)
    }

    /** Draws back to one end of her deck and sweeps along it, low. Only an arm at her side can. */
    fun sweep() {
        if (!ready || spec.side == 0) return
        aimZ = if (spec.z >= 0.0) SWEEP_BOW else SWEEP_STERN
        swept.clear()
        mode = Mode.DRAW_BACK
        playSound(SeaRegistry.REAR.get(), 1.4f, 0.8f + random.nextFloat() * 0.15f)
    }

    /** Reaches for [victim], to snatch them up. */
    fun grab(victim: Entity) {
        if (!ready) return
        prey = victim
        mode = Mode.REACH
    }

    /** Drapes itself over her rail and holds on. */
    fun hold() {
        if (!ready) return
        holdFor = HOLD_MIN + random.nextInt(HOLD_MAX - HOLD_MIN)
        mode = Mode.DRAPE
    }

    /** Lets go of whatever it has and slides back under. */
    fun withdraw() {
        if (mode == Mode.WITHDRAW || isDeadOrDying) return
        (level() as? ServerLevel)?.let { release(it, null) }
        mode = Mode.WITHDRAW
        playSound(SeaRegistry.SINK.get(), 1.2f, 0.9f + random.nextFloat() * 0.2f)
    }

    /** Its master is dead: it goes slack and slides away into the deep. */
    fun goLimp() {
        if (mode == Mode.LIMP || isDeadOrDying) return
        (level() as? ServerLevel)?.let { release(it, null) }
        mode = Mode.LIMP
    }

    /** Whether it's draped over her rail holding on, and on which side (as its place round her). */
    val holding: Boolean get() = isAlive && mode == Mode.HOLD

    val spec: Maelgrasp.Slot get() = Maelgrasp.SLOTS[slot.coerceIn(0, Maelgrasp.SLOTS.size - 1)]

    // ---- Every tick ----

    override fun tick() {
        poseO.copy(pose)
        super.tick()
        val level = level()
        if (level is ServerLevel) serverTick(level) else clientTick()
        Tentacle.solve(shape, pose.tip.x, pose.tip.y, pose.tip.z, pose.from.x, pose.from.y, pose.from.z, pose.curl, pose.ripple, time(0f), phase)
        placeParts()
        yBodyRot = armYaw
        yHeadRot = armYaw
        yRot = armYaw
    }

    private fun clientTick() {
        pose.tip.set(entityData.get(DATA_TIP))
        pose.from.set(entityData.get(DATA_FROM))
        pose.curl = entityData.get(DATA_CURL)
        pose.ripple = entityData.get(DATA_RIPPLE)
        pose.grip = entityData.get(DATA_GRIP)
        val m = mode
        if (m == Mode.LIFT || m == Mode.SQUEEZE || m == Mode.FLING) {
            if (caughtAt < 0) caughtAt = tickCount
        } else {
            caughtAt = -1
        }
        clientEffects?.invoke(this)
    }

    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        // Hacked through: the length cut off falls away on its own, from where it was
        if (accessor == DATA_CUT && level().isClientSide && cut < Tentacle.SEGMENTS && severed == null) {
            severed = shape.joints.copyOf()
            severedAt = tickCount
        }
    }

    private fun serverTick(level: ServerLevel) {
        if (reloaded) {
            discard()
            return
        }
        modeTicks++
        val ship = level.getEntity(shipId) as? Cog
        if (ship != null && !ship.isRemoved) follow(level, ship)
        if (isDeadOrDying) {
            // The stump thrashes, and sinks
            val left = 1f - (deathTime / SINK_TICKS.toFloat()).coerceIn(0f, 1f)
            steer(SUNK_TIP, DOWN, 0.5f, 0.06f, ripple = 0.9f * left)
            sync()
            return
        }
        val boss = level.getEntity(bossId) as? Maelgrasp
        when {
            boss == null || boss.isRemoved -> withdraw()
            boss.isDeadOrDying -> goLimp()
            ship == null || ship.isRemoved -> withdraw()
        }
        if (ship == null || ship.isRemoved) {
            if (mode == Mode.WITHDRAW) withdrawing() else if (mode == Mode.LIMP) limp()
            sync()
            return
        }
        when (mode) {
            Mode.RISE -> rising(level, ship)
            Mode.IDLE -> idling(ship)
            Mode.REAR -> rearing(ship)
            Mode.SLAM -> slamming(level, ship, boss)
            Mode.LINGER -> lingering(ship)
            Mode.DRAW_BACK -> drawingBack(ship)
            Mode.SWEEP -> sweeping(level, ship, boss)
            Mode.REACH -> reaching(level, ship)
            Mode.LIFT -> lifting(level, ship, boss)
            Mode.SQUEEZE -> squeezing(level, ship, boss)
            Mode.FLING -> flinging(level, ship)
            Mode.DRAPE -> draping(level, ship)
            Mode.HOLD -> holdingOn(ship)
            Mode.RECOVER -> recovering(ship)
            Mode.WITHDRAW -> withdrawing()
            Mode.LIMP -> limp()
        }
        // Whoever it holds is gone (dead, or away some other way): its grip is empty
        val held = seized
        if (held != null && (!held.isAlive || held.vehicle !== this)) {
            seized = null
            if (mode == Mode.LIFT || mode == Mode.SQUEEZE) mode = Mode.RECOVER
        }
        if (random.nextInt(WRITHE_EVERY) == 0 && mode != Mode.WITHDRAW && mode != Mode.LIMP) {
            val at = shape.world(Tentacle.SEGMENTS / 2, position(), armYaw)
            level.playSound(null, at.x, at.y, at.z, SeaRegistry.WRITHE.get(), SoundSource.HOSTILE, 1f, 0.8f + random.nextFloat() * 0.3f)
        }
        sync()
    }

    /** Keeps its place beside the ship: where it breaks the surface at its slot, facing her middle. */
    private fun follow(level: ServerLevel, ship: Cog) {
        val s = spec
        val at = ship.localToWorld(s.x, 0.0, s.z)
        val surface = Seas.surface(level, at.x, at.z) ?: ship.y
        setPos(at.x, surface, at.z)
        val yaw = Mth.wrapDegrees(ship.yRot + s.turn)
        if (yaw != armYaw) entityData.set(DATA_YAW, yaw)
    }

    private fun sync() {
        entityData.set(DATA_TIP, Vector3f(pose.tip))
        entityData.set(DATA_FROM, Vector3f(pose.from))
        entityData.set(DATA_CURL, pose.curl)
        entityData.set(DATA_RIPPLE, pose.ripple)
        entityData.set(DATA_GRIP, pose.grip)
    }

    // ---- Its moves ----

    private fun rising(level: ServerLevel, ship: Cog) {
        val u = (modeTicks / RISE_TICKS.toFloat()).coerceIn(0f, 1f)
        if (modeTicks == 1) {
            level.playSound(null, x, y, z, SeaRegistry.BURST.get(), SoundSource.HOSTILE, 2f, 0.85f + random.nextFloat() * 0.3f)
            level.sendParticles(ParticleTypes.SPLASH, x, y + 0.2, z, 60, 1.2, 0.2, 1.2, 0.4)
            level.sendParticles(ParticleTypes.BUBBLE, x, y - 0.5, z, 30, 1.0, 0.5, 1.0, 0.1)
        }
        if (modeTicks % 3 == 0 && u < 0.6f) level.sendParticles(ParticleTypes.FALLING_WATER, x, y + 2.0 + u * 5.0, z, 12, 0.5, 1.5, 0.5, 0.0)
        if (u < 0.5f) {
            // Straight up out of the sea, streaming water, uncoiling as it comes
            val k = ease(u / 0.5f)
            pose.tip.set(SUNK_TIP).lerp(APEX, k)
            pose.from.set(DOWN)
            pose.curl = Mth.lerp(k, 0.9f, 0.05f)
            pose.ripple = 0.5f
        } else {
            // Then over, to lean in over her rail
            idlePose(ship, target, targetFrom)
            val k = ease((u - 0.5f) / 0.5f)
            pose.tip.set(APEX).lerp(target, k)
            pose.from.set(DOWN).lerp(targetFrom, k).let { if (it.lengthSquared() < 1.0e-4f) it.set(UP) else it.normalize() }
            pose.curl = Mth.lerp(k, 0.05f, IDLE_CURL)
            pose.ripple = Mth.lerp(k, 0.5f, IDLE_RIPPLE)
        }
        if (u >= 1f) mode = Mode.IDLE
    }

    private fun idling(ship: Cog) {
        idlePose(ship, target, targetFrom)
        steer(target, targetFrom, IDLE_CURL, 0.08f)
    }

    private fun rearing(ship: Cog) {
        val s = spec
        val z = if (s.side != 0) aimZ else s.z - s.oz * 1.2
        target.set(local(ship, s.x - s.ox * 0.9, REAR_HEIGHT, z))
        targetFrom.set(localDir(ship, s.ox * 0.5, -1.0, s.oz * 0.5))
        steer(target, targetFrom, 0.3f, 0.14f, ripple = 0.22f)
        if (modeTicks >= REAR_TICKS) begin(Mode.SLAM)
    }

    /** Down it comes, faster and faster, across her deck. */
    private fun slamming(level: ServerLevel, ship: Cog, boss: Maelgrasp?) {
        val u = (modeTicks / SLAM_TICKS.toFloat()).coerceIn(0f, 1f)
        slamPose(ship, target, targetFrom)
        pathTo(target, targetFrom, 0f, u * u)
        pose.ripple = 0.05f
        if (modeTicks < SLAM_TICKS) return
        // The blow: across her deck from the rail it came over to the tip
        val s = spec
        val (ax, az) = entry(aimZ)
        val tx = if (s.side != 0) -s.side * SLAM_ACROSS else 0.0
        val scale = boss?.damageScale ?: 1f
        for (victim in onDeck(ship, ax, az, tx, aimZ, SLAM_REACH)) {
            victim.hurtServer(level, damageSources().mobAttack(this), SLAM_DAMAGE * scale)
        }
        ship.strain(level, SLAM_HULL)
        val at = ship.localToWorld(tx * 0.5 + ax * 0.5, Seas.deckAt(aimZ) + 0.3, (aimZ + az) * 0.5)
        level.playSound(null, at.x, at.y, at.z, SeaRegistry.SLAM.get(), SoundSource.HOSTILE, 2f, 0.85f + random.nextFloat() * 0.25f)
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, PLANKS), at.x, at.y, at.z, 40, 1.2, 0.3, 0.6, 0.2)
        level.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.3, at.z, 50, 1.4, 0.3, 0.8, 0.4)
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, 8, 0.8, 0.1, 0.5, 0.02)
        mode = Mode.LINGER
    }

    private fun lingering(ship: Cog) {
        // Lying across the deck, heaving: now's the time to hack at it
        slamPose(ship, target, targetFrom)
        steer(target, targetFrom, 0f, 0.5f, ripple = 0.07f)
        if (modeTicks >= LINGER_TICKS) begin(Mode.RECOVER)
    }

    private fun drawingBack(ship: Cog) {
        val s = spec
        target.set(local(ship, s.side * 1.7, Seas.deckAt(aimZ) + 2.4, aimZ))
        targetFrom.set(localDir(ship, s.side * 1.0, 0.3, 0.0))
        steer(target, targetFrom, 0.15f, 0.15f, ripple = 0.15f)
        if (modeTicks >= DRAW_BACK_TICKS) {
            begin(Mode.SWEEP)
            playSound(SeaRegistry.SWEEP.get(), 2f, 0.9f + random.nextFloat() * 0.2f)
        }
    }

    /** Low along her deck from one end to the other: everyone in its way is struck and flung over the far side. */
    private fun sweeping(level: ServerLevel, ship: Cog, boss: Maelgrasp?) {
        val s = spec
        val u = (modeTicks / SWEEP_TICKS.toFloat()).coerceIn(0f, 1f)
        val e = u * u * (3f - 2f * u)
        val z = Mth.lerp(e.toDouble(), aimZ, -aimZ * 0.95)
        val x = s.side * Mth.lerp(e.toDouble(), 0.4, -0.3)
        pose.tip.set(local(ship, x, Seas.deckAt(z) + 0.95, z))
        pose.from.set(localDir(ship, s.side * 1.0, 0.25, 0.0)).normalize()
        pose.curl += (0f - pose.curl) * 0.3f
        pose.ripple = 0.1f
        val scale = boss?.damageScale ?: 1f
        for (victim in Seas.aboard(ship)) {
            if (victim.id in swept) continue
            val at = where(ship, victim)
            if (abs(at.z - z) > SWEEP_REACH) continue
            swept.add(victim.id)
            victim.hurtServer(level, damageSources().mobAttack(this), SWEEP_DAMAGE * scale)
            overboard(ship, victim, -s.side.toDouble())
            level.playSound(null, victim.x, victim.y, victim.z, SeaRegistry.HURL.get(), SoundSource.HOSTILE, 1f, 1.2f)
        }
        if (modeTicks % 2 == 0) {
            val at = ship.localToWorld(x, Seas.deckAt(z) + 0.6, z)
            level.sendParticles(ParticleTypes.SPLASH, at.x, at.y, at.z, 10, 0.5, 0.2, 0.5, 0.2)
        }
        if (modeTicks >= SWEEP_TICKS) begin(Mode.RECOVER)
    }

    /** Reaching for whoever it's after: catching them if they're still there when it gets there. */
    private fun reaching(level: ServerLevel, ship: Cog) {
        val victim = prey
        if (victim == null || !victim.isAlive || victim.distanceToSqr(this) > REACH_RANGE * REACH_RANGE) {
            begin(Mode.RECOVER)
            return
        }
        val s = spec
        val them = Tentacle.toLocal(victim.position().add(0.0, victim.bbHeight * 0.55, 0.0), position(), armYaw)
        target.set(them.x.toFloat(), them.y.toFloat(), them.z.toFloat())
        targetFrom.set(localDir(ship, s.ox * 0.7, 1.0, s.oz * 0.7))
        steer(target, targetFrom, 0.15f, 0.32f, ripple = 0.08f)
        if (modeTicks < REACH_TICKS) return
        val tip = shape.world(Tentacle.SEGMENTS - 1, position(), armYaw)
        if (tip.distanceToSqr(victim.position().add(0.0, victim.bbHeight * 0.55, 0.0)) < CATCH * CATCH) seize(level, victim) else begin(Mode.RECOVER)
    }

    private fun seize(level: ServerLevel, victim: Entity) {
        seized = victim
        prey = null
        gripHurt = 0f
        struggles = 0
        seizedTicks = 0
        if (!victim.startRiding(this, true, true)) {
            seized = null
            begin(Mode.RECOVER)
            return
        }
        level.playSound(null, victim.x, victim.y, victim.z, SeaRegistry.GRAB.get(), SoundSource.HOSTILE, 1.4f, 0.9f + random.nextFloat() * 0.2f)
        (victim as? ServerPlayer)?.sendOverlayMessage(Component.translatable("message.guildmark.sea.seized"))
        begin(Mode.LIFT)
    }

    /** Up and out over the water with them. */
    private fun lifting(level: ServerLevel, ship: Cog, boss: Maelgrasp?) {
        liftPose(ship, 0f)
        steer(target, targetFrom, 0.75f, 0.12f, ripple = 0.1f, grip = 1f)
        seizedTicks++
        if (seized == null) {
            begin(Mode.RECOVER)
            return
        }
        if (modeTicks >= LIFT_TICKS) begin(Mode.SQUEEZE)
        squeezeTick(level, boss)
    }

    private fun squeezing(level: ServerLevel, ship: Cog, boss: Maelgrasp?) {
        liftPose(ship, 1f)
        steer(target, targetFrom, 0.8f, 0.2f, ripple = 0.12f, grip = 1f)
        seizedTicks++
        if (seized == null) {
            begin(Mode.RECOVER)
            return
        }
        squeezeTick(level, boss)
        if (seizedTicks >= HURL_AFTER) {
            begin(Mode.FLING)
            playSound(SeaRegistry.REAR.get(), 1.2f, 1.2f)
        }
    }

    /** Crushing whoever it holds, now and then; struck hard enough, it lets them drop. */
    private fun squeezeTick(level: ServerLevel, boss: Maelgrasp?) {
        val held = seized ?: return
        if (gripHurt >= FREE_DAMAGE) {
            release(level, null)
            level.playSound(null, x, y, z, SeaRegistry.ARM_HURT.get(), SoundSource.HOSTILE, 1.5f, 0.8f)
            begin(Mode.RECOVER)
            return
        }
        if (seizedTicks % SQUEEZE_EVERY == SQUEEZE_EVERY - 1) {
            held.hurtServer(level, damageSources().mobAttack(this), SQUEEZE_DAMAGE * (boss?.damageScale ?: 1f))
            level.playSound(null, held.x, held.y, held.z, SeaRegistry.SQUEEZE.get(), SoundSource.HOSTILE, 1.2f, 0.85f + random.nextFloat() * 0.3f)
        }
    }

    /** A crack of the whip, and they're thrown far out into the sea. */
    private fun flinging(level: ServerLevel, ship: Cog) {
        val s = spec
        val u = (modeTicks / FLING_TICKS.toFloat()).coerceIn(0f, 1f)
        target.set(local(ship, s.x + s.ox * 5.5, 8.5, s.z + s.oz * 5.5))
        targetFrom.set(localDir(ship, -s.ox * 0.5, -1.0, -s.oz * 0.5))
        pathTo(target, targetFrom, 0.5f, u * u)
        pose.grip = 1f - u
        if (modeTicks == FLING_LET_GO) {
            val wx = Cog.rightX(ship.yRot) * s.ox + Cog.forwardX(ship.yRot) * s.oz
            val wz = Cog.rightZ(ship.yRot) * s.ox + Cog.forwardZ(ship.yRot) * s.oz
            release(level, Vec3(wx * HURL_SPEED, HURL_LIFT, wz * HURL_SPEED))
            level.playSound(null, x, y + 6.0, z, SeaRegistry.HURL.get(), SoundSource.HOSTILE, 1.8f, 0.9f + random.nextFloat() * 0.2f)
        }
        if (modeTicks >= FLING_TICKS) begin(Mode.RECOVER)
    }

    /** Over her rail, hooking on. */
    private fun draping(level: ServerLevel, ship: Cog) {
        holdPose(ship, target, targetFrom)
        steer(target, targetFrom, 0.2f, 0.16f, ripple = 0.08f, grip = if (modeTicks > DRAPE_TICKS / 2) 1f else 0f)
        if (modeTicks >= DRAPE_TICKS) {
            val at = shape.world(Tentacle.SEGMENTS, position(), armYaw)
            level.playSound(null, at.x, at.y, at.z, SeaRegistry.GRAB.get(), SoundSource.HOSTILE, 1.4f, 0.75f)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, PLANKS), at.x, at.y, at.z, 12, 0.4, 0.2, 0.4, 0.05)
            begin(Mode.HOLD)
        }
    }

    private fun holdingOn(ship: Cog) {
        holdPose(ship, target, targetFrom)
        steer(target, targetFrom, 0.2f, 0.4f, ripple = 0.05f, grip = 1f)
        if (modeTicks >= holdFor) begin(Mode.RECOVER)
    }

    private fun recovering(ship: Cog) {
        idlePose(ship, target, targetFrom)
        steer(target, targetFrom, IDLE_CURL, 0.12f)
        if (modeTicks >= RECOVER_TICKS) mode = Mode.IDLE
    }

    private fun withdrawing() {
        steer(SUNK_TIP, DOWN, 0.6f, 0.1f, ripple = 0.25f)
        if (modeTicks >= WITHDRAW_TICKS) discard()
    }

    private fun limp() {
        // Slack: it slumps into the water, and the sea takes it
        if (modeTicks < LIMP_TICKS) steer(SLUMPED_TIP, UP, 0f, 0.05f, ripple = 0f) else steer(SUNK_TIP, DOWN, 0f, 0.06f, ripple = 0f)
        if (modeTicks >= LIMP_TICKS + WITHDRAW_TICKS * 2) discard()
    }

    /** Into [next], remembering where it was for a move along a set path. */
    private fun begin(next: Mode) {
        startTip.set(pose.tip)
        startFrom.set(pose.from)
        startCurl = pose.curl
        mode = next
    }

    // ---- Poses, in the ship's own frame (to starboard, up, forward) turned into its own ----

    private val target = Vector3f()
    private val targetFrom = Vector3f()

    /** Leaning in over her rail a block or so inboard of where it rises, high above her deck, swaying. */
    private fun idlePose(ship: Cog, tip: Vector3f, from: Vector3f) {
        val s = spec
        val t = time(0f)
        val sway = sin(t * 0.045f + phase) * 0.7
        val bob = sin(t * 0.063f + phase * 1.3f) * 0.5
        tip.set(local(ship, s.x - s.ox * 1.1 - s.oz * sway, IDLE_HEIGHT + bob, s.z - s.oz * 1.1 + s.ox * sway))
        from.set(localDir(ship, s.ox * 0.6, 1.0, s.oz * 0.6))
    }

    /** Lying across her deck where it slammed down, its tip on the far side. */
    private fun slamPose(ship: Cog, tip: Vector3f, from: Vector3f) {
        val s = spec
        val x = if (s.side != 0) -s.side * SLAM_ACROSS else 0.0
        tip.set(local(ship, x, Seas.deckAt(aimZ) + 0.3, aimZ))
        from.set(localDir(ship, s.ox, 0.45, s.oz))
    }

    /** Out over the water, high, holding whoever it caught; shaking them about as it squeezes ([shake] 0 to 1). */
    private fun liftPose(ship: Cog, shake: Float) {
        val s = spec
        val t = time(0f)
        val jolt = sin(t * 0.9f) * 0.25 * shake
        target.set(local(ship, s.x + s.ox * 1.4 + jolt, LIFT_HEIGHT + sin(t * 0.13f) * 0.3, s.z + s.oz * 1.4 - jolt))
        targetFrom.set(localDir(ship, -s.ox * 0.2, -1.0, -s.oz * 0.2))
    }

    /** Hooked over her rail, its tip on her deck inside it. */
    private fun holdPose(ship: Cog, tip: Vector3f, from: Vector3f) {
        val s = spec
        when {
            s.side != 0 -> {
                val z = s.z.coerceIn(-3.2, 3.2)
                tip.set(local(ship, s.side * (Seas.beam(z) - 0.5), Seas.deckAt(z) + 0.3, z))
            }
            s.oz > 0 -> tip.set(local(ship, 0.0, 1.55, 3.4))
            else -> tip.set(local(ship, 0.0, 2.2, -4.1))
        }
        from.set(localDir(ship, s.ox * 0.6, 1.0, s.oz * 0.6))
    }

    /** Where (to starboard, forward) it comes in over her side for a blow at [z]. */
    private fun entry(z: Double): Pair<Double, Double> {
        val s = spec
        return when {
            s.side != 0 -> Pair(s.side * Seas.beam(z), z)
            s.oz > 0 -> Pair(0.0, 4.1)
            else -> Pair(0.0, -4.6)
        }
    }

    /** A point of [ship]'s own frame in its own. */
    private fun local(ship: Cog, sx: Double, sy: Double, sz: Double): Vector3f {
        val l = Tentacle.toLocal(ship.localToWorld(sx, sy, sz), position(), armYaw)
        return Vector3f(l.x.toFloat(), l.y.toFloat(), l.z.toFloat())
    }

    /** A direction in [ship]'s own frame in its own, made a unit long. */
    private fun localDir(ship: Cog, dx: Double, dy: Double, dz: Double): Vector3f {
        val wx = Cog.rightX(ship.yRot) * dx + Cog.forwardX(ship.yRot) * dz
        val wz = Cog.rightZ(ship.yRot) * dx + Cog.forwardZ(ship.yRot) * dz
        val r = armYaw * Mth.DEG_TO_RAD
        val v = Vector3f((-cos(r) * wx - sin(r) * wz).toFloat(), dy.toFloat(), (-sin(r) * wx + cos(r) * wz).toFloat())
        return if (v.lengthSquared() < 1.0e-6f) v.set(UP) else v.normalize()
    }

    /** Eases toward a pose at [rate] a tick. */
    private fun steer(tip: Vector3fc, from: Vector3fc, curl: Float, rate: Float, ripple: Float = IDLE_RIPPLE, grip: Float = 0f) {
        pose.tip.lerp(tip, rate)
        pose.from.lerp(from, rate)
        if (pose.from.lengthSquared() < 1.0e-4f) pose.from.set(from) else pose.from.normalize()
        pose.curl += (curl - pose.curl) * rate
        pose.ripple += (ripple - pose.ripple) * min(1f, rate * 1.5f)
        pose.grip += (grip - pose.grip) * 0.25f
    }

    /** [k] (0 to 1) of the way along a set path from where the move began to a pose. */
    private fun pathTo(tip: Vector3fc, from: Vector3fc, curl: Float, k: Float) {
        pose.tip.set(startTip).lerp(tip, k)
        pose.from.set(startFrom).lerp(from, k)
        if (pose.from.lengthSquared() < 1.0e-4f) pose.from.set(from) else pose.from.normalize()
        pose.curl = Mth.lerp(k, startCurl, curl)
    }

    // ---- Who's where ----

    /** Where [entity] is, in [ship]'s own frame: at their place aboard if they have one. */
    private fun where(ship: Cog, entity: Entity): Vec3 {
        val station = ship.stationOf(entity)
        return if (station != null) Vec3(station.x, station.y, station.z) else ship.worldToLocal(entity.position())
    }

    /** Everyone aboard within [reach] of the line across her deck from ([ax], [az]) to ([bx], [bz]). */
    private fun onDeck(ship: Cog, ax: Double, az: Double, bx: Double, bz: Double, reach: Double): List<Entity> {
        val out = ArrayList<Entity>()
        val dx = bx - ax
        val dz = bz - az
        val len2 = max(dx * dx + dz * dz, 1.0e-6)
        for (victim in Seas.aboard(ship)) {
            val at = where(ship, victim)
            val t = (((at.x - ax) * dx + (at.z - az) * dz) / len2).coerceIn(0.0, 1.0)
            val px = ax + dx * t - at.x
            val pz = az + dz * t - at.z
            if (px * px + pz * pz <= reach * reach) out.add(victim)
        }
        return out
    }

    /** Knocks [victim] off [ship] over her side toward [side] (1 starboard), out into the sea. */
    private fun overboard(ship: Cog, victim: Entity, side: Double) {
        val local = where(ship, victim)
        if (victim.vehicle === ship) victim.stopRiding()
        val z = local.z.coerceIn(-4.0, 3.8)
        val at = ship.localToWorld(side * (Seas.beam(z) + 0.8), 1.6, z)
        victim.teleportTo(at.x, at.y, at.z)
        victim.deltaMovement = Vec3(Cog.rightX(ship.yRot) * side * KNOCK_OUT, KNOCK_UP, Cog.rightZ(ship.yRot) * side * KNOCK_OUT)
        victim.needsSync = true
    }

    // ---- Holding someone ----

    override fun canAddPassenger(passenger: Entity): Boolean = passenger === seized && passengers.isEmpty()

    // Whoever it holds hangs in its coils, not sat on it
    override fun shouldRiderSit(): Boolean = false

    override fun positionRider(passenger: Entity, moveFunction: MoveFunction) {
        if (!hasPassenger(passenger)) return
        val g = shape.grip()
        val at = Tentacle.toWorld(g.x.toFloat(), g.y.toFloat(), g.z.toFloat(), position(), armYaw)
        moveFunction.accept(passenger, at.x, at.y - passenger.bbHeight * 0.55, at.z)
    }

    // Let go of, they drop from where they hung
    override fun getDismountLocationForPassenger(passenger: LivingEntity): Vec3 = passenger.position()

    override fun removePassenger(passenger: Entity) {
        super.removePassenger(passenger)
        if (passenger === seized) seized = null
    }

    /**
     * Whether it lets [entity] off it now (asked whenever they try to get off): only if it means to, they've struggled
     * free, or they're gone. Each crouch is one struggle; enough, and they tear loose and drop.
     */
    fun letsGo(entity: Entity): Boolean {
        if (level().isClientSide || releasing || entity !== seized || isRemoved || !isAlive || !entity.isAlive) return true
        if (entity is ServerPlayer && entity.hasDisconnected()) return true
        if (tickCount - lastStruggle > STRUGGLE_GAP) struggles++
        lastStruggle = tickCount
        if (struggles >= STRUGGLE_FREE) {
            seized = null
            (level() as? ServerLevel)?.let { it.playSound(null, entity.x, entity.y, entity.z, SeaRegistry.ARM_HURT.get(), SoundSource.HOSTILE, 1.2f, 1.1f) }
            if (mode == Mode.LIFT || mode == Mode.SQUEEZE) begin(Mode.RECOVER)
            return true
        }
        (entity as? ServerPlayer)?.sendOverlayMessage(Component.translatable("message.guildmark.sea.struggle", struggles, STRUGGLE_FREE))
        return false
    }

    /** Lets go of whoever it holds, flung off at [throwAt] (blocks a tick) or just dropped. */
    private fun release(level: ServerLevel, throwAt: Vec3?) {
        val held = seized ?: return
        releasing = true
        held.stopRiding()
        releasing = false
        seized = null
        if (throwAt != null) {
            held.deltaMovement = throwAt
            held.needsSync = true
        }
    }

    // ---- Struck ----

    private fun struckAt(joint: Int, level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (joint <= cut) lastStruck = joint
        return hurtServer(level, source, amount)
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean {
        if (mode == Mode.WITHDRAW || mode == Mode.LIMP || isDeadOrDying) return false
        if (source.`is`(DamageTypes.IN_WALL) || source.`is`(DamageTypes.DROWN)) return false
        // Nothing of the Maelgrasp's own hurts it
        val attacker = source.entity
        if (attacker is MaelgraspArm || attacker is Maelgrasp) return false
        return super.hurtServer(level, source, damage)
    }

    override fun actuallyHurt(level: ServerLevel, source: DamageSource, amount: Float) {
        val before = health
        super.actuallyHurt(level, source, amount)
        val dealt = before - health
        if (dealt <= 0f) return
        if (seized != null) gripHurt += dealt
        (level.getEntity(bossId) as? Maelgrasp)?.armWounded(level, source, dealt)
    }

    /** Hacked through: severed where it was struck, the rest falling into the sea while the stump writhes and sinks. */
    override fun die(source: DamageSource) {
        if (isRemoved || dead) return
        super.die(source)
        if (!dead) return
        val level = level() as? ServerLevel ?: return
        release(level, null)
        val at = lastStruck.coerceIn(CUT_MIN, CUT_MAX)
        val where = shape.world(at, position(), armYaw)
        entityData.set(DATA_CUT, at.toByte())
        level.playSound(null, where.x, where.y, where.z, SeaRegistry.SEVER.get(), SoundSource.HOSTILE, 2f, 0.9f + random.nextFloat() * 0.2f)
        level.sendParticles(ParticleTypes.SQUID_INK, where.x, where.y, where.z, 40, 0.4, 0.4, 0.4, 0.15)
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.NETHER_WART_BLOCK.defaultBlockState()), where.x, where.y, where.z, 30, 0.4, 0.4, 0.4, 0.2)
        (level.getEntity(bossId) as? Maelgrasp)?.armSevered(level, this, source)
    }

    override fun tickDeath() {
        deathTime++
        if (deathTime >= SINK_TICKS && !level().isClientSide && !isRemoved) {
            (level() as? ServerLevel)?.let { it.sendParticles(ParticleTypes.BUBBLE, x, y - 0.5, z, 30, 0.8, 0.4, 0.8, 0.1) }
            remove(RemovalReason.KILLED)
        }
    }

    // ---- What it isn't ----

    override fun travel(input: Vec3) {}
    override fun isPushedByFluid(): Boolean = false
    override fun canBreatheUnderwater(): Boolean = true
    override fun causeFallDamage(fallDistance: Double, multiplier: Float, source: DamageSource): Boolean = false
    override fun isPushable(): Boolean = false
    override fun pushEntities() {}
    override fun canBeLeashed(): Boolean = false
    override fun canUsePortal(ignorePassenger: Boolean): Boolean = false
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false
    override fun isAffectedByPotions(): Boolean = false

    override fun getHurtSound(source: DamageSource): SoundEvent = SeaRegistry.ARM_HURT.get()
    override fun getDeathSound(): SoundEvent = SeaRegistry.SINK.get()
    override fun playStepSound(pos: BlockPos, state: BlockState) {}

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putBoolean("rising", true)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        // The fight it was part of doesn't outlast the world being saved and loaded
        reloaded = input.getBooleanOr("rising", false)
    }

    companion object {
        private val DATA_MODE: EntityDataAccessor<Byte> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.BYTE)
        private val DATA_BOSS: EntityDataAccessor<Int> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.INT)
        private val DATA_SLOT: EntityDataAccessor<Byte> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.BYTE)
        private val DATA_YAW: EntityDataAccessor<Float> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.FLOAT)
        private val DATA_TIP: EntityDataAccessor<Vector3fc> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.VECTOR3)
        private val DATA_FROM: EntityDataAccessor<Vector3fc> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.VECTOR3)
        private val DATA_CURL: EntityDataAccessor<Float> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.FLOAT)
        private val DATA_RIPPLE: EntityDataAccessor<Float> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.FLOAT)
        private val DATA_GRIP: EntityDataAccessor<Float> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.FLOAT)
        private val DATA_CUT: EntityDataAccessor<Byte> = SynchedEntityData.defineId(MaelgraspArm::class.java, EntityDataSerializers.BYTE)
        private val MODES = Mode.entries.toTypedArray()
        private val PLANKS = Blocks.SPRUCE_PLANKS.defaultBlockState()
        private val TIER_HEALTH = SeaRegistry.id("maelgrasp_arm_tier_health")

        /** On a client, the water streaming off it and the sea churning where it rises, done each tick (see client/sea/SeaClient). */
        var clientEffects: ((MaelgraspArm) -> Unit)? = null

        fun createAttributes(): AttributeSupplier.Builder = createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 60.0)
            .add(Attributes.ATTACK_DAMAGE, 8.0)
            .add(Attributes.ARMOR, 4.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FOLLOW_RANGE, 32.0)

        /** Ticks of the world's time a ripple repeats over. */
        private const val RIPPLE_PERIOD = 24000L

        /** Blocks its boxes reach past its own girth. */
        private const val PART_MARGIN = 0.4f

        // ---- Its poses (blocks; in its own frame where they're Vector3fs) ----
        private val SUNK_TIP: Vector3fc = Vector3f(0f, -4f, 0.6f)
        private val APEX: Vector3fc = Vector3f(0f, 7.6f, -0.8f)
        private val SLUMPED_TIP: Vector3fc = Vector3f(0f, -1.2f, -3.5f)
        private val UP: Vector3fc = Vector3f(0f, 1f, 0f)
        private val DOWN: Vector3fc = Vector3f(0f, -1f, 0f)
        private const val IDLE_HEIGHT = 4.4
        private const val IDLE_CURL = 0.35f
        private const val IDLE_RIPPLE = 0.32f
        private const val REAR_HEIGHT = 7.0
        private const val LIFT_HEIGHT = 6.6
        /** How far across her deck past her middle a slam's tip comes down. */
        private const val SLAM_ACROSS = 1.25
        /** Where along her deck a sweep starts, from the bow or the stern. */
        private const val SWEEP_BOW = 3.7
        private const val SWEEP_STERN = -4.1

        // ---- Timing, in ticks ----
        private const val RISE_TICKS = 36
        private const val REAR_TICKS = 24
        private const val SLAM_TICKS = 6
        private const val LINGER_TICKS = 26
        private const val RECOVER_TICKS = 20
        private const val DRAW_BACK_TICKS = 22
        private const val SWEEP_TICKS = 16
        private const val REACH_TICKS = 14
        private const val LIFT_TICKS = 24
        private const val HURL_AFTER = 80
        private const val FLING_TICKS = 8
        private const val FLING_LET_GO = 5
        private const val DRAPE_TICKS = 22
        private const val HOLD_MIN = 120
        private const val HOLD_MAX = 200
        private const val WITHDRAW_TICKS = 30
        private const val LIMP_TICKS = 40
        /** Ticks its stump takes to sink away once it's hacked through (the length cut off falls and sinks as long). */
        const val SINK_TICKS = 60
        private const val SQUEEZE_EVERY = 20
        private const val WRITHE_EVERY = 90

        // ---- Its blows ----
        private const val SLAM_DAMAGE = 14f
        private const val SLAM_HULL = 2f
        private const val SLAM_REACH = 1.35
        private const val SWEEP_DAMAGE = 9f
        private const val SWEEP_REACH = 1.1
        private const val SQUEEZE_DAMAGE = 2f
        private const val KNOCK_OUT = 0.9
        private const val KNOCK_UP = 0.45
        private const val HURL_SPEED = 1.25
        private const val HURL_LIFT = 0.6
        /** Blocks from its tip someone must be, when it gets there, to be caught; and the farthest it reaches for anyone. */
        private const val CATCH = 1.9
        private const val REACH_RANGE = 13.0
        /** Damage that makes it let go of whoever it holds, and crouches that tear them free. */
        private const val FREE_DAMAGE = 15f
        private const val STRUGGLE_FREE = 5
        private const val STRUGGLE_GAP = 3
        /** The joints it can be severed at: never right at its root, nor its very tip. */
        private const val CUT_MIN = 3
        private const val CUT_MAX = 10

        private fun ease(u: Float): Float = u * u * (3f - 2f * u)
    }
}
