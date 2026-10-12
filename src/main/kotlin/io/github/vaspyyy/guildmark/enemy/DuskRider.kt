package io.github.vaspyyy.guildmark.enemy

import io.github.vaspyyy.guildmark.combat.Blow
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.field.NightRiders
import io.github.vaspyyy.guildmark.network.BossCuePayload
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
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
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import java.util.EnumSet
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Dusk Rider, a field boss that rides out at night: a hollow knight of a fallen order on a black destrier,
 * a halberd in one hand and a lantern of cold light in the other. A horn sounds when it comes for someone.
 * It fights at the gallop, riding past with its halberd held out low like a scythe and charging with it
 * couched like a lance, wheeling round between passes. Up close it cleaves and sweeps, rears to stamp down on
 * anyone in front of it and kicks out at anyone behind; from afar it casts wisps of cold light from its
 * lantern. Wounded past half, cold fire takes its steed: it rides faster, leaves burning tracks and casts
 * three wisps at a time. A rider that came with the night goes with it: at dawn it fades away, and once
 * everyone it fought has fallen it rides off.
 */
class DuskRider(type: EntityType<out DuskRider>, level: Level) : CombatEnemy(type, level) {
    override val attacks: List<EnemyAttack> get() = ATTACKS
    override val animations: String get() = ANIMATIONS
    override val isBoss: Boolean get() = true
    override val maxStance: Float get() = 240f
    override val waitRange: Double get() = 9.0
    override val chaseSpeed: Double get() = if (ignited) 1.6 else 1.45
    override val cooldownScale: Float get() = if (ignited) 0.7f else 1f

    /** It has found someone and the fight is on. */
    val fighting: Boolean get() = flag(FIGHTING)
    /** Past half its health: cold fire in its steed. */
    val ignited: Boolean get() = flag(IGNITED)
    /** It's fading away (the dawn, or a fight won). */
    val fading: Boolean get() = flag(FADING)

    /** The player it rode out for, or null for one that rides where it likes (from an egg). */
    var quarry: UUID? = null
    /** Came with the night (see NightRiders): it fades at dawn, rides on when nobody is near, and is gone after a reload. */
    var boundToNight = false
    /** Read back from a save (a reload, or the land it rode in loading again). */
    private var reloaded = false
    /** Which of the riders' names it answers to. */
    var nameIndex: Int = 0
        private set
    private var lonely = 0
    /** Seconds in a row it has had no way to get at whoever it's after. */
    private var stranded = 0
    /** Ticks it keeps to itself after giving up a fight it couldn't win (unless someone hurts it). */
    private var aloof = 0
    private var aimSide = 1.0
    private val trail = ArrayDeque<Ember>()
    /** The players in this fight, each by the entity they were when they joined it (dying makes a new one). */
    private val foes = HashMap<UUID, Int>()

    /** On a client: how far the steed has gone and how fast it's going (its gaits play by these). */
    var stride = 0f
        private set
    var strideO = 0f
        private set
    var gaitSpeed = 0f
        private set
    var gaitSpeedO = 0f
        private set
    private var fadeTicks = 0

    private val bossEvent = ServerBossEvent(
        Mth.createInsecureUUID(random), Component.empty(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_10,
    )

    /** A patch of cold fire left on the ground by its burning steed. */
    private class Ember(val pos: Vec3, val until: Int)

    init {
        xpReward = 300
        moveControl = RiderMoveControl(this)
    }

    /** "Aldric Vane, the Dusk Rider". */
    val title: Component get() = Component.translatable(BOSS_KEY, Component.translatable("$NAME_KEY.$nameIndex"))

    override fun canFight(): Boolean = fighting && !fading

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, RiderFightGoal(this))
        goalSelector.addGoal(5, RideForQuarryGoal(this))
        goalSelector.addGoal(6, object : WaterAvoidingRandomStrollGoal(this, 0.5) {
            override fun canUse(): Boolean = !fighting && !fading && quarry == null && attack == null && super.canUse()
        })
        goalSelector.addGoal(8, LookAtPlayerGoal(this, Player::class.java, 16f))
        // It picks its targets itself (see fight): players only, and only those in the fight
    }

    override fun finalizeSpawn(level: ServerLevelAccessor, difficulty: DifficultyInstance, spawnReason: EntitySpawnReason, groupData: SpawnGroupData?): SpawnGroupData? {
        val data = super.finalizeSpawn(level, difficulty, spawnReason, groupData)
        nameIndex = random.nextInt(NAMES)
        setPersistenceRequired()
        bossEvent.name = title
        return data
    }

    /** Toughens it for land [tier] (1 near spawn, up to 3 far out). */
    fun scaleTo(tier: Int) {
        val extra = (tier - 1).coerceAtLeast(0)
        if (extra == 0) return
        getAttribute(Attributes.MAX_HEALTH)?.addOrReplacePermanentModifier(AttributeModifier(TIER_HEALTH, 0.35 * extra, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        getAttribute(Attributes.ATTACK_DAMAGE)?.addOrReplacePermanentModifier(AttributeModifier(TIER_DAMAGE, 0.15 * extra, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        health = maxHealth
    }

    // ---- Every tick ----

    override fun tick() {
        // The night it came with is long over
        if (reloaded && boundToNight && !level().isClientSide) {
            discard()
            return
        }
        super.tick()
        if (level().isClientSide) {
            // Its gaits play by how far it has gone, and blend by how fast it's going
            val dx = x - xo
            val dz = z - zo
            val moved = min(sqrt(dx * dx + dz * dz).toFloat(), MAX_STRIDE)
            strideO = stride
            stride += moved
            gaitSpeedO = gaitSpeed
            gaitSpeed += (moved - gaitSpeed) * 0.35f
            fadeTicks = if (fading) fadeTicks + 1 else 0
            hoofbeats()
        }
    }

    /** On a client: a hoof meeting the ground at each footfall of the gait it's in, in step with its legs. */
    private fun hoofbeats() {
        if (!isAlive || fading || !onGround() || gaitSpeed < WALK_FROM) return
        val galloping = gaitSpeed >= GALLOP_FROM
        val length = if (galloping) GALLOP_STRIDE else WALK_STRIDE
        val falls = if (galloping) GALLOP_FALLS else WALK_FALLS
        val from = strideO / length
        val to = stride / length
        for (fall in falls) {
            if (floor(to - fall) > floor(from - fall)) {
                level().playLocalSound(x, y, z, ModSounds.RIDER_HOOF.get(), SoundSource.HOSTILE, if (galloping) 1.1f else 0.5f,
                    0.9f + random.nextFloat() * 0.2f, false)
            }
        }
    }

    /** On a client: how much of it is left as it fades (1 whole, 0 gone). */
    fun fade(partial: Float): Float = if (!fading) 1f else 1f - ((fadeTicks + partial) / FADE_TICKS).coerceIn(0f, 1f)

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
        burnTrail(level)
        if (fading || attack === VANISH) return
        if (boundToNight) {
            // It rides with the night: at dawn it rears once and is gone
            if (level.isBrightOutside) {
                depart(level, true)
                return
            }
            // Nobody within reach of its horn: it has ridden on
            if (tickCount % 40 == 0 && level.players().none { it.isAlive && !it.isSpectator && it.distanceToSqr(this) < GONE_RANGE * GONE_RANGE }) {
                discard()
                return
            }
        }
        if (fighting) fight(level) else prowl(level)
    }

    /** Out of a fight: it rides for its quarry (or where it likes), and the first player it sees close by starts one. */
    private fun prowl(level: ServerLevel) {
        target = null
        if (aloof > 0) {
            aloof--
            return
        }
        if (tickCount % 5 != 0) return
        val seen = level.players()
            .filter { fair(it) && it.distanceToSqr(this) < NOTICE_RANGE * NOTICE_RANGE && hasLineOfSight(it) }
            .minByOrNull { it.distanceToSqr(this) }
        if (seen != null) startFight(level, seen)
    }

    /** In a fight: it picks whom to ride down among the players in it, and runs down anyone who flees. */
    private fun fight(level: ServerLevel) {
        // Anyone it sees close by joins the fight
        if (tickCount % 5 == 0) {
            for (player in level.players()) {
                if (fair(player) && player.distanceToSqr(this) < NOTICE_RANGE * NOTICE_RANGE && hasLineOfSight(player)) join(player)
            }
        }
        val alive = livingFoes(level)
        // Everyone it was fighting has fallen or gone: one that came with the night has what it came for and rides off
        if (alive.isEmpty()) {
            if (boundToNight) depart(level, false) else calmDown(level)
            return
        }
        val watchers = level.players().filter { it.isAlive && !it.isSpectator && it.distanceToSqr(this) < FIGHT_RANGE * FIGHT_RANGE }
        for (player in bossEvent.players.toList()) if (player !in watchers) bossEvent.removePlayer(player)
        for (player in watchers) if (player is ServerPlayer && player !in bossEvent.players) bossEvent.addPlayer(player)
        val near = alive.filter { it.distanceToSqr(this) < CHASE_RANGE * CHASE_RANGE }
        if (near.isEmpty()) {
            // They've got clean away: it gives the fight up if they don't come back
            target = null
            if (++lonely >= LOST_TICKS) calmDown(level)
            return
        }
        lonely = 0
        // It keeps after whoever it's after while they're in reach; otherwise it goes for the nearest
        val current = target as? Player
        if (current == null || current !in near) target = near.minBy { it.distanceToSqr(this) }
        // Up a pillar, across a gorge or out on the water, where it can't ride: it won't wait there to be shot.
        // One that came with the night rides off into it; any other gives up the fight and keeps to itself a while
        if (tickCount % 20 == 0) {
            val after = target
            stranded = if (after == null || canGetAt(after)) 0 else stranded + 1
            if (stranded >= STRANDED_SECONDS) {
                if (boundToNight) depart(level, false)
                else {
                    calmDown(level)
                    aloof = ALOOF_TICKS
                }
                return
            }
        }
        if (Stance.isStaggered(this)) return

        // Half its health gone: cold fire takes its steed
        if (!ignited && attack !== IGNITE && health <= maxHealth * 0.5f) {
            if (attack != null) stopAttack(true)
            startAttack(IGNITE)
        }
    }

    /** Players it would fight. */
    private fun fair(player: Player): Boolean = player.isAlive && !player.isSpectator && !player.isCreative

    /** Whether it can reach [target]: close enough for the halberd, or by a way it can ride. */
    private fun canGetAt(target: LivingEntity): Boolean {
        val dx = target.x - x
        val dz = target.z - z
        if (dx * dx + dz * dz < 25.0 && target.y - y < REACH_ABOVE && target.y - y > -REACH_BELOW) return true
        return navigation.createPath(target, 1)?.canReach() == true
    }

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
        stranded = 0
        aloof = 0
        cooldown = 20
        bossEvent.name = title
        level.playSound(null, x, y, z, ModSounds.RIDER_NEIGH.get(), SoundSource.HOSTILE, 3f, 0.9f)
    }

    /** The fight is over: it rides on, whole again. */
    private fun calmDown(level: ServerLevel) {
        lonely = 0
        stranded = 0
        if (attack != null) stopAttack(true)
        bossEvent.removeAllPlayers()
        foes.clear()
        trail.clear()
        health = maxHealth
        setFlag(FIGHTING, false)
        setFlag(IGNITED, false)
        target = null
        level.playSound(null, x, y, z, ModSounds.RIDER_SNORT.get(), SoundSource.HOSTILE, 1.6f, 0.9f)
    }

    /** It rears once and fades away: with the [dawn], or because nobody it fought is left standing. It leaves nothing behind. */
    private fun depart(level: ServerLevel, dawn: Boolean) {
        // Nothing holds it back once it's going, not even a broken stance
        Stance.release(this)
        if (fighting || !dawn) {
            val message = Component.translatable(if (dawn) "message.guildmark.dusk_rider_dawn" else "message.guildmark.dusk_rider_departs", title)
            for (player in level.players()) {
                if (player is ServerPlayer && player.distanceToSqr(this) < FIGHT_RANGE * FIGHT_RANGE) player.sendSystemMessage(message)
            }
        }
        if (attack != null) stopAttack(true)
        bossEvent.removeAllPlayers()
        foes.clear()
        setFlag(FIGHTING, false)
        target = null
        startAttack(VANISH)
    }

    /** The cold fire its burning steed leaves on the ground: it burns and slows whoever stands in it. */
    private fun burnTrail(level: ServerLevel) {
        while (trail.isNotEmpty() && trail.first().until <= tickCount) trail.removeFirst()
        if (trail.isEmpty()) return
        for (ember in trail) {
            if ((tickCount + ember.until) % 5 == 0) {
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, ember.pos.x, ember.pos.y + 0.15, ember.pos.z, 2, 0.35, 0.05, 0.35, 0.01)
            }
        }
        if (tickCount % 10 != 0) return
        val damage = getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() * 0.2f
        // Fire, so armour softens it and a potion of fire resistance keeps it off
        val source = DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.IN_FIRE), this)
        for (player in level.players()) {
            if (!player.isAlive || player.isCreative || player.isSpectator) continue
            val touching = trail.any { ember ->
                val dx = player.x - ember.pos.x
                val dz = player.z - ember.pos.z
                dx * dx + dz * dz < 1.1 && player.y > ember.pos.y - 1.0 && player.y < ember.pos.y + 1.5
            }
            if (!touching) continue
            if (player.hurtServer(level, source, damage)) player.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 40, 1), this)
        }
    }

    // ---- Attacks ----

    override fun usable(attack: EnemyAttack, target: LivingEntity, distance: Double): Boolean {
        if (attack.phase > (if (ignited) 2 else 1)) return false
        val angle = Combat.angleTo(yRot, target.x - x, target.z - z)
        if (attack === KICK) return distance < 3.5 && angle > 110f
        if (attack === REAR && angle > 50f) return false
        if ((attack === RIDE_BY || attack === CHARGE || attack === LANTERN) && !hasLineOfSight(target)) return false
        // Someone up out of the halberd's reach gets its wisps, however near
        if (attack === LANTERN && target.y - y >= REACH_ABOVE) return distance <= attack.maxRange
        return super.usable(attack, target, distance)
    }

    override fun onAttackStart(attack: EnemyAttack) {
        when (attack) {
            RIDE_BY -> {
                // It passes its target on the left, so the halberd held out on its right finds them
                aimSide = 1.0
                playSound(ModSounds.RIDER_SNORT.get(), 1.4f, 1.0f)
            }
            CHARGE, REAR, IGNITE -> playSound(ModSounds.RIDER_NEIGH.get(), 2.2f, if (attack === IGNITE) 0.8f else 1.0f)
            LANTERN -> playSound(ModSounds.RIDER_WISP.get(), 1.0f, 0.7f)
            VANISH -> playSound(ModSounds.RIDER_NEIGH.get(), 2.4f, 0.85f)
            else -> {}
        }
    }

    override fun onAttackTick(level: ServerLevel, attack: EnemyAttack, tick: Int) {
        for (blow in attack.blows) {
            if (tick == blow.at - 3 && attack !== IGNITE) {
                level.playSound(null, x, y, z, ModSounds.RIDER_SWING.get(), SoundSource.HOSTILE, 1.3f, 0.85f + random.nextFloat() * 0.15f)
            }
        }
        val look = Vec3.directionFromRotation(0f, yRot)
        when (attack) {
            RIDE_BY -> {
                // Lines up a pass beside its target rather than through it
                val target = target
                if (target != null && tick <= 9) {
                    val tx = target.x - x
                    val tz = target.z - z
                    val d = sqrt(tx * tx + tz * tz).coerceAtLeast(0.1)
                    // Its left of the target, as seen riding at it: the target ends up on its right
                    val ax = target.x - (-tz / d) * PASS_OFFSET * aimSide
                    val az = target.z - (tx / d) * PASS_OFFSET * aimSide
                    turnToward(ax, az, 12f)
                }
                gallop(level, tick, 8, 26)
            }
            CHARGE -> {
                if (tick == 8) level.playSound(null, x, y, z, ModSounds.RIDER_NEIGH.get(), SoundSource.HOSTILE, 2.6f, 0.9f)
                if (tick == 17) impact(level, position().add(look.scale(1.5)), 1.0f)
                gallop(level, tick, 18, 40)
            }
            CLEAVE -> if (tick == 16) impact(level, position().add(look.scale(3.2)), 0.8f)
            REAR -> if (tick == 20) {
                impact(level, position().add(look.scale(1.8)), 1.4f)
                level.sendParticles(ParticleTypes.CLOUD, x + look.x * 1.8, y + 0.2, z + look.z * 1.8, 16, 0.8, 0.05, 0.8, 0.03)
            }
            KICK -> if (tick == 12) impact(level, position().add(look.scale(-2.0)), 1.0f)
            LANTERN -> {
                val casts = if (ignited) intArrayOf(20, 26, 32) else intArrayOf(20)
                if (tick in casts) castWisp(level, tick != 20)
            }
            IGNITE -> {
                if (tick == 26) {
                    setFlag(IGNITED, true)
                    level.playSound(null, x, y, z, ModSounds.RIDER_IGNITE.get(), SoundSource.HOSTILE, 2.4f, 1.0f)
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.5, z, 60, 0.8, 1.0, 0.8, 0.05)
                }
                if (tick == 34) {
                    impact(level, position().add(look.scale(1.8)), 1.6f)
                    ring(level, position(), 3.0)
                    ring(level, position(), 5.0)
                }
            }
            VANISH -> {
                if (tick == FADE_AT) {
                    setFlag(FADING, true)
                    level.playSound(null, x, y, z, ModSounds.RIDER_VANISH.get(), SoundSource.HOSTILE, 2f, 1f)
                }
                if (tick == FADE_AT + FADE_TICKS.toInt()) {
                    level.sendParticles(ParticleTypes.SOUL, x, y + 1.5, z, 30, 0.8, 1.0, 0.8, 0.02)
                    discard()
                }
            }
            else -> {}
        }
    }

    /** Dust kicked up through the ride from [from] to [to] and, once it's burning, a track of cold fire. */
    private fun gallop(level: ServerLevel, tick: Int, from: Int, to: Int) {
        if (tick < from || tick > to) return
        if (tick % 2 == 0) level.sendParticles(ParticleTypes.CLOUD, x, y + 0.1, z, 2, 0.4, 0.02, 0.4, 0.01)
        if (ignited && tick % 2 == 0) trail.addLast(Ember(position(), tickCount + EMBER_TICKS))
    }

    private fun turnToward(tx: Double, tz: Double, step: Float) {
        val wanted = Math.toDegrees(atan2(-(tx - x), tz - z)).toFloat()
        yRot += Mth.clamp(Mth.wrapDegrees(wanted - yRot), -step, step)
        yBodyRot = yRot
        yHeadRot = yRot
    }

    /** A wisp of cold light from the lantern, after its target; the second and third go a little wide. */
    private fun castWisp(level: ServerLevel, wide: Boolean) {
        val target = target ?: return
        val r = yRot * Mth.DEG_TO_RAD
        // The lantern is in its left hand, held up before it
        val from = Vec3(x - cos(r) * -0.7 - sin(r) * 1.2, y + 2.6, z - sin(r) * -0.7 + cos(r) * 1.2)
        val aim = target.position().add(0.0, target.bbHeight * 0.6, 0.0).subtract(from).normalize()
        val spread = if (wide) Vec3((random.nextDouble() - 0.5) * 0.6, 0.15, (random.nextDouble() - 0.5) * 0.6) else Vec3.ZERO
        val wisp = LanternWisp(level, this, from, target)
        wisp.deltaMovement = aim.add(spread).normalize().scale(LanternWisp.SPEED)
        level.addFreshEntity(wisp)
        level.playSound(null, from.x, from.y, from.z, ModSounds.RIDER_WISP.get(), SoundSource.HOSTILE, 1.4f, 1.0f + random.nextFloat() * 0.2f)
    }

    /** Hooves or the halberd meeting the ground. */
    private fun impact(level: ServerLevel, at: Vec3, volume: Float) {
        level.playSound(null, at.x, at.y, at.z, ModSounds.RIDER_SLAM.get(), SoundSource.HOSTILE, volume * 1.5f, 0.9f + random.nextFloat() * 0.1f)
        val ground = level.getBlockState(BlockPos.containing(at.x, at.y - 0.5, at.z))
        if (!ground.isAir) level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1, at.z, 30, 0.7, 0.1, 0.7, 0.15)
        if (ignited) level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.2, at.z, 14, 0.6, 0.1, 0.6, 0.03)
    }

    private fun ring(level: ServerLevel, at: Vec3, radius: Double) {
        val points = (radius * 10).toInt().coerceAtLeast(8)
        for (i in 0 until points) {
            val a = i * Math.PI * 2 / points
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x + cos(a) * radius, at.y + 0.2, at.z + sin(a) * radius, 1, 0.1, 0.05, 0.1, 0.01)
        }
    }

    // ---- Damage ----

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount)
        // Untouchable while cold fire takes it, and while it fades away
        if (attack === IGNITE || attack === VANISH || fading) return false
        val player = attacker(source)
        // Out of a fight only a player can hurt it: no golem, trap or cactus wears it down for anyone
        if (!fighting && player == null) return false
        // Whoever it would fight back (a player in creative can still test it, but it pays them no mind)
        val by = player?.takeIf { fair(it) }
        val hurt = super.hurtServer(level, source, amount)
        if (hurt && by != null) {
            if (!fighting) startFight(level, by)
            else {
                join(by)
                // Whoever hurts it from closer than the one it's after gets its attention
                val current = target
                if (current == null || distanceToSqr(by) + 16.0 < distanceToSqr(current)) target = by
            }
        }
        return hurt
    }

    // It won't ride off a cliff after anyone
    override fun getMaxFallDistance(): Int = minOf(super.getMaxFallDistance(), 4)

    // Nor out of the world it rides in
    override fun canUsePortal(ignorePassenger: Boolean): Boolean = false

    override fun die(source: DamageSource) {
        if (isRemoved || dead) return
        super.die(source)
        // Something kept it from dying after all
        if (!dead) return
        val level = level() as? ServerLevel ?: return
        bossEvent.removeAllPlayers()
        setFlag(FIGHTING, false)
        foes.clear()
        trail.clear()
        NightRiders.onSlain(level, this)
        // Everyone near sees it fall
        for (player in level.players()) {
            if (player is ServerPlayer && player.distanceToSqr(this) < CUE_RANGE * CUE_RANGE) {
                PacketDistributor.sendToPlayer(player, BossCuePayload(id, BossCuePayload.FELLED))
                Combat.shieldForScene(player, BossCuePayload.sceneTicks(BossCuePayload.FELLED))
            }
        }
    }

    /** The steed's legs buckle and it lies down with its rider slumped over its neck; then both are gone. */
    override fun tickDeath() {
        deathTime++
        val level = level() as? ServerLevel ?: return
        if (deathTime == 18 || deathTime == 36) level.playSound(null, x, y, z, ModSounds.RIDER_SLAM.get(), SoundSource.HOSTILE, 1.6f, 0.7f)
        if (deathTime >= DEATH_TICKS && !isRemoved) {
            level.sendParticles(ParticleTypes.SOUL, x, y + 1.0, z, 40, 1.2, 0.6, 1.2, 0.03)
            level.sendParticles(ParticleTypes.POOF, x, y + 1.0, z, 30, 1.2, 0.6, 1.2, 0.03)
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

    override fun getMaxHeadYRot(): Int = 50

    override fun getAmbientSound(): SoundEvent? = ModSounds.RIDER_SNORT.get()
    override fun getHurtSound(source: DamageSource): SoundEvent = ModSounds.RIDER_HURT.get()
    override fun getDeathSound(): SoundEvent = ModSounds.RIDER_DEATH.get()
    override fun getSoundVolume(): Float = 1.6f

    // Its hooves are heard in step with its legs (see hoofbeats)
    override fun playStepSound(pos: BlockPos, state: BlockState) {}

    // ---- Saving ----

    override fun checkDespawn() {
        if (level().difficulty == Difficulty.PEACEFUL) discard()
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putInt("rider_name", nameIndex)
        output.putBoolean("bound_to_night", boundToNight)
    }

    /** A fight never survives a reload: it's whole again and waiting. One that came with the night is gone. */
    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        nameIndex = input.getIntOr("rider_name", 0).coerceIn(0, NAMES - 1)
        boundToNight = input.getBooleanOr("bound_to_night", false)
        reloaded = true
        bossEvent.name = title
        if (health > 0f) {
            setFlag(FIGHTING, false)
            setFlag(IGNITED, false)
            setFlag(FADING, false)
            health = maxHealth
        }
    }

    companion object {
        const val ANIMATIONS = "dusk_rider"
        const val BOSS_KEY = "boss.guildmark.dusk_rider"
        const val NAME_KEY = "boss.guildmark.rider_name"
        /** How many names a rider might have (lang keys boss.guildmark.rider_name.0 and on). */
        const val NAMES = 12
        private const val FIGHTING = 2
        private const val IGNITED = 4
        private const val FADING = 8
        private const val NOTICE_RANGE = 32.0
        /** How near players see its health. */
        private const val FIGHT_RANGE = 48.0
        /** How far it will run down someone in its fight. */
        private const val CHASE_RANGE = 96.0
        /** Ticks with everyone in its fight out of reach before it gives the fight up. */
        private const val LOST_TICKS = 200
        /** Seconds it will go with no way to get at whoever it's after before it gives the fight up. */
        private const val STRANDED_SECONDS = 10
        private const val ALOOF_TICKS = 300
        /** How far above its feet its blows still land (a little short of the furthest they reach). */
        private const val REACH_ABOVE = 3.5
        /** And how far below them (into a pit beside it). */
        private const val REACH_BELOW = 2.0
        /** With nobody this near, a night rider has ridden on for good. */
        private const val GONE_RANGE = 160.0
        private const val CUE_RANGE = 48.0
        /** How far beside its target it rides past. */
        private const val PASS_OFFSET = 1.6
        /** Blocks a tick its gaits can follow (faster, and the legs would blur). */
        private const val MAX_STRIDE = 0.6f
        /** Blocks the steed covers in one loop of each gait, and where in each loop a hoof lands (art/scripts/dusk_rider_anims.py). */
        const val WALK_STRIDE = 2.4f
        const val GALLOP_STRIDE = 4.2f
        private val WALK_FALLS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f)
        private val GALLOP_FALLS = floatArrayOf(0f, 0.1f, 0.3f, 0.4f)
        /** The speeds (blocks a tick) at which its walk, then its gallop, take over (as in DuskRiderModel). */
        private const val WALK_FROM = 0.04f
        private const val GALLOP_FROM = 0.195f
        /** Ticks the cold fire it leaves behind burns. */
        private const val EMBER_TICKS = 100
        /** When in its vanishing it starts to fade, and for how long. */
        private const val FADE_AT = 16
        private const val FADE_TICKS = 12f
        /** Ticks its death takes: the steed down on its knees, then lying still. */
        const val DEATH_TICKS = 70
        private val TIER_HEALTH = net.minecraft.resources.Identifier.fromNamespaceAndPath("guildmark", "rider_tier_health")
        private val TIER_DAMAGE = net.minecraft.resources.Identifier.fromNamespaceAndPath("guildmark", "rider_tier_damage")

        // Timings match art/scripts/dusk_rider_anims.py
        /** A pass at the gallop, the halberd held out low on its right. */
        val RIDE_BY = EnemyAttack(1, "ride_by", 40, listOf(Blow(12, 1.2f, 3.4, 110f, 30f, knockback = 0.8f, until = 26)), emptyList(),
            minRange = 5.0, maxRange = 16.0, weight = 4, cooldown = 18, lunge = 0.62f, lungeAt = 8, lungeTicks = 18)
        /** It rears, then charges straight through with the halberd couched like a lance. */
        val CHARGE = EnemyAttack(2, "charge", 56, listOf(Blow(20, 1.5f, 2.4, 60f, 45f, parryable = false, knockback = 1.8f, until = 40, forward = 1.2)),
            listOf(0..17), minRange = 9.0, maxRange = 26.0, weight = 3, cooldown = 30, lunge = 0.72f, lungeAt = 18, lungeTicks = 22, turn = 8f)
        val CLEAVE = EnemyAttack(3, "cleave", 34, listOf(Blow(16, 1.35f, 4.2, 55f, 40f, guard = 1.8f)), listOf(0..12),
            maxRange = 3.8, weight = 4, cooldown = 20, turn = 10f)
        val SWEEP = EnemyAttack(4, "sweep", 38, listOf(Blow(19, 1.0f, 4.4, 180f, 30f, parryable = false, knockback = 1.1f)), listOf(0..12),
            maxRange = 4.0, weight = 3, cooldown = 24)
        /** For anyone close in front: it rears and stamps down. */
        val REAR = EnemyAttack(5, "rear", 40, listOf(Blow(20, 1.3f, 3.2, 180f, 40f, parryable = false, knockback = 1.2f, forward = 1.8)), listOf(0..10),
            maxRange = 3.2, weight = 3, cooldown = 26)
        /** For anyone behind: both hind hooves. */
        val KICK = EnemyAttack(6, "kick", 30, listOf(Blow(12, 1.1f, 3.0, 180f, 35f, parryable = false, knockback = 1.6f, forward = -2.0)), emptyList(),
            maxRange = 3.5, weight = 5, cooldown = 16)
        /** Wisps of cold light from its lantern (one, or three once it burns). */
        val LANTERN = EnemyAttack(7, "lantern", 44, emptyList(), listOf(0..19), minRange = 5.0, maxRange = 24.0, weight = 3, cooldown = 36, turn = 10f)
        /** Past half its health: it rears, lifts the lantern, and cold fire takes its steed; it lands in a burst of it. */
        val IGNITE = EnemyAttack(20, "ignite", 60, listOf(Blow(34, 0.4f, 6.0, 180f, 0f, parryable = false, knockback = 1.8f)), emptyList(),
            weight = 0, cooldown = 10)
        /** Dawn, or a fight won: it rears once and fades away. */
        val VANISH = EnemyAttack(21, "vanish", 30, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val ATTACKS = listOf(RIDE_BY, CHARGE, CLEAVE, SWEEP, REAR, KICK, LANTERN, IGNITE, VANISH)

        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 280.0)
            .add(Attributes.ATTACK_DAMAGE, 9.0)
            .add(Attributes.ARMOR, 10.0)
            .add(Attributes.ARMOR_TOUGHNESS, 2.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FOLLOW_RANGE, 96.0)
            .add(Attributes.STEP_HEIGHT, 1.1)
    }
}

/** A horse's turning: no faster than [TURN] degrees a tick, so it wheels rather than spins. */
class RiderMoveControl(mob: DuskRider) : MoveControl<DuskRider>(mob) {
    override fun rotlerp(a: Float, b: Float, max: Float): Float = super.rotlerp(a, b, min(max, TURN))

    private companion object {
        const val TURN = 16f
    }
}

/**
 * How the Dusk Rider fights: between attacks it wheels round its target at the gallop rather than circling on
 * foot, and once an attack is ready it rides in for it.
 */
class RiderFightGoal(private val mob: DuskRider) : Goal() {
    private var side = 1.0
    private var repath = 0

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean = mob.target?.isAlive == true && mob.canFight()

    override fun canContinueToUse(): Boolean = canUse()

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun start() {
        side = if (mob.random.nextBoolean()) 1.0 else -1.0
        repath = 0
    }

    override fun stop() {
        mob.navigation.stop()
    }

    override fun tick() {
        val target = mob.target ?: return
        if (mob.attack != null || Stance.isStaggered(mob)) {
            repath = 0
            return
        }
        mob.lookControl.setLookAt(target, 30f, 30f)
        val distance = mob.distanceTo(target) - target.bbWidth / 2 - mob.bbWidth / 2
        val seen = mob.sensing.hasLineOfSight(target)
        if (mob.cooldown <= 0 && seen) {
            mob.chooseAttack(target, distance.toDouble())?.let {
                mob.navigation.stop()
                mob.startAttack(it)
                return
            }
        }
        if (--repath > 0) return
        repath = 8
        if (mob.cooldown <= 0 || !seen || distance > mob.waitRange + 8.0) {
            mob.navigation.moveTo(target, mob.chaseSpeed)
            return
        }
        // Waiting for its next pass: it wheels round its target, now and then turning the other way
        if (mob.random.nextInt(10) == 0) side = -side
        val angle = atan2(mob.z - target.z, mob.x - target.x) + side * 0.8
        val r = mob.waitRange + 1.5
        mob.navigation.moveTo(target.x + cos(angle) * r, target.y, target.z + sin(angle) * r, mob.chaseSpeed * 0.8)
    }
}

/** Until a fight starts, it rides at a canter toward the player it came out for. */
class RideForQuarryGoal(private val mob: DuskRider) : Goal() {
    private var repath = 0

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean = !mob.fighting && !mob.fading && mob.attack == null && quarry() != null

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        repath = 0
    }

    override fun stop() {
        mob.navigation.stop()
    }

    override fun tick() {
        if (--repath > 0) return
        repath = 20
        val quarry = quarry() ?: return
        mob.navigation.moveTo(quarry, 0.85)
    }

    private fun quarry(): Player? {
        val id = mob.quarry ?: return null
        return mob.level().getPlayerByUUID(id)?.takeIf { it.isAlive && !it.isSpectator }
    }
}
