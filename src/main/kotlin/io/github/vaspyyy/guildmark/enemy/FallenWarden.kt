package io.github.vaspyyy.guildmark.enemy

import io.github.vaspyyy.guildmark.combat.Blow
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.combat.FightGoal
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.network.BossCuePayload
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.world.BossEvent
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Halvard, the Fallen Warden: the knight who held the King's Road, still kneeling at his post. He wakes
 * when someone comes near, rises through his introduction and fights with a greatsword: wide sweeps, a
 * delayed overhead, a three-blow chain and a charge for anyone who keeps their distance. At half health
 * he kneels again as the crown's fire takes him, then fights on with leaping slams and rolling flame.
 */
class FallenWarden(type: EntityType<out FallenWarden>, level: Level) : CombatEnemy(type, level) {
    override val attacks: List<EnemyAttack> get() = ATTACKS
    override val animations: String get() = "fallen_warden"
    override val isBoss: Boolean get() = true
    override val maxStance: Float get() = 170f
    override val waitRange: Double get() = 3.2
    override val chaseSpeed: Double get() = if (phaseTwo) 1.15 else 1.0
    override val cooldownScale: Float get() = if (phaseTwo) 0.7f else 1f

    /** Kneeling at his post, waiting for someone to come near. */
    val dormant: Boolean get() = flag(DORMANT)
    /** The fight is on (after his introduction). */
    val fighting: Boolean get() = flag(FIGHTING)
    /** The crown's fire has taken him. */
    val phaseTwo: Boolean get() = flag(PHASE_TWO)

    /** Where he keeps watch; he returns here when nobody is left to fight. */
    var home: BlockPos = BlockPos.ZERO
    private var lonely = 0
    private val recentHits = ArrayDeque<Int>()
    private var landing: Vec3? = null

    private val bossEvent = ServerBossEvent(
        Mth.createInsecureUUID(random), Component.translatable(BOSS_KEY), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS,
    )

    init {
        xpReward = 250
    }

    override fun canFight(): Boolean = fighting && !dormant

    override fun registerGoals() {
        goalSelector.addGoal(2, FightGoal(this))
        targetSelector.addGoal(1, HurtByTargetGoal(this))
        targetSelector.addGoal(2, NearestAttackableTargetGoal(this, Player::class.java, false))
    }

    override fun finalizeSpawn(level: ServerLevelAccessor, difficulty: DifficultyInstance, spawnReason: EntitySpawnReason, groupData: SpawnGroupData?): SpawnGroupData? {
        val data = super.finalizeSpawn(level, difficulty, spawnReason, groupData)
        home = blockPosition()
        setFlag(DORMANT, true)
        return data
    }

    // ---- Every tick ----

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
        if (dormant) {
            navigation.stop()
            deltaMovement = Vec3(0.0, deltaMovement.y, 0.0)
            if (tickCount % 5 == 0) {
                val waker = level.players().firstOrNull {
                    it.isAlive && !it.isSpectator && !it.isCreative && it.distanceToSqr(this) < WAKE_RANGE * WAKE_RANGE && hasLineOfSight(it)
                }
                if (waker != null) awaken(level)
            }
            return
        }
        // The introduction is over: the fight begins
        if (!fighting && attack == null) {
            setFlag(FIGHTING, true)
            cooldown = 10
        }

        val fighters = level.players().filter { it.isAlive && !it.isSpectator && it.distanceToSqr(this) < FIGHT_RANGE * FIGHT_RANGE }
        if (fighting) {
            for (player in bossEvent.players.toList()) if (player !in fighters) bossEvent.removePlayer(player)
            for (player in fighters) if (player is ServerPlayer && player !in bossEvent.players) bossEvent.addPlayer(player)
        }
        if (fighters.none { !it.isCreative }) {
            if (++lonely >= 200) rest(level)
            return
        }
        lonely = 0
        if (home != BlockPos.ZERO && attack == null && distanceToSqr(home.x + 0.5, home.y.toDouble(), home.z + 0.5) > LEASH * LEASH) {
            level.sendParticles(ParticleTypes.SOUL, x, y + 1.5, z, 30, 0.5, 1.0, 0.5, 0.05)
            teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
        }
        if (!fighting) return

        // Half his health gone: the crown's fire takes him
        if (!phaseTwo && attack !== KNEEL && health <= maxHealth * 0.5f) {
            if (attack != null) stopAttack(true)
            startAttack(KNEEL)
            return
        }
        // Crowded and pressed hard: he stamps everyone off
        while (recentHits.isNotEmpty() && tickCount - recentHits.first() > 40) recentHits.removeFirst()
        val target = target
        if (attack == null && target != null && recentHits.size >= 3 && distanceTo(target) < 4f) {
            recentHits.clear()
            startAttack(STOMP)
        }
    }

    /** Someone came close: he stirs, and everyone near sees his introduction. */
    fun awaken(level: ServerLevel) {
        if (!dormant) return
        setFlag(DORMANT, false)
        startAttack(RISE)
        cue(level, BossCuePayload.INTRO)
    }

    /** Nobody left to fight: he goes back to his post, whole again, and kneels. */
    private fun rest(level: ServerLevel) {
        lonely = 0
        if (attack != null) stopAttack(true)
        bossEvent.removeAllPlayers()
        if (home != BlockPos.ZERO) teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
        health = maxHealth
        setFlag(FIGHTING, false)
        setFlag(PHASE_TWO, false)
        setFlag(DORMANT, true)
        target = null
        level.sendParticles(ParticleTypes.SOUL, x, y + 1.5, z, 30, 0.5, 1.0, 0.5, 0.05)
    }

    private fun cue(level: ServerLevel, cue: Int) {
        for (player in level.players()) {
            if (player is ServerPlayer && player.distanceToSqr(this) < CUE_RANGE * CUE_RANGE) PacketDistributor.sendToPlayer(player, BossCuePayload(id, cue))
        }
    }

    // ---- Attacks ----

    override fun usable(attack: EnemyAttack, target: LivingEntity, distance: Double): Boolean {
        if (attack.phase > (if (phaseTwo) 2 else 1)) return false
        if (attack === STOMP) {
            // For whoever slips behind him
            return distance < 2.5 && Combat.angleTo(yRot, target.x - x, target.z - z) > 100f
        }
        return super.usable(attack, target, distance)
    }

    override fun onAttackStart(attack: EnemyAttack) {
        when (attack) {
            CHARGE -> playSound(ModSounds.WARDEN_ROAR.get(), 1.2f, 1.1f)
            FLAME_SLAM, FLAME_WAVE -> playSound(ModSounds.WARDEN_FLAME.get(), 1.2f, 0.9f)
            else -> {}
        }
    }

    override fun onAttackTick(level: ServerLevel, attack: EnemyAttack, tick: Int) {
        for (blow in attack.blows) {
            if (tick == blow.at - 3 && blow.arc < 180f && attack !== CHARGE && blow.group < 0) {
                level.playSound(null, x, y, z, ModSounds.WARDEN_SWING.get(), SoundSource.HOSTILE, 1.3f, 0.9f + random.nextFloat() * 0.15f)
            }
        }
        val look = Vec3.directionFromRotation(0f, yRot)
        when (attack) {
            OVERHEAD -> {
                if (tick == 8) glint(level, ParticleTypes.FLAME, 4.4, 0.4, 8)
                if (tick == 20) impact(level, position().add(look.scale(3.2)), 1.2f, 18)
            }
            COMBO -> if (tick == 34) impact(level, position().add(look.scale(4.0)), 0.6f, 6)
            CHARGE -> if (tick in 13..23 && tick % 3 == 0) {
                level.playSound(null, x, y, z, ModSounds.WARDEN_STEP.get(), SoundSource.HOSTILE, 1.2f, 1.1f)
                level.sendParticles(ParticleTypes.CLOUD, x, y + 0.2, z, 4, 0.4, 0.05, 0.4, 0.02)
            }
            STOMP -> if (tick == 9) impact(level, position(), 1.3f, 24)
            FLAME_SLAM -> flameSlam(level, tick)
            FLAME_WAVE -> if (tick in 18..27) {
                if (tick == 18) level.playSound(null, x, y, z, ModSounds.WARDEN_FLAME.get(), SoundSource.HOSTILE, 1.4f, 1.1f)
                val front = position().add(look.scale(2.0 + (tick - 18) * 1.0))
                val side = Vec3(-look.z, 0.0, look.x)
                for (i in -2..2) {
                    val at = front.add(side.scale(i * 0.35))
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, y + 0.3, at.z, 3, 0.1, 0.25, 0.1, 0.01)
                }
            }
            RISE -> {
                if (tick == 140) impact(level, position().add(look.scale(1.2)), 0.8f, 10)
                if (tick == 170) {
                    level.playSound(null, x, y, z, ModSounds.WARDEN_ROAR.get(), SoundSource.HOSTILE, 2f, 0.9f)
                    level.sendParticles(ParticleTypes.SOUL, x, y + 2.0, z, 40, 0.8, 1.0, 0.8, 0.06)
                }
            }
            KNEEL -> {
                if (tick == 0) cue(level, BossCuePayload.PHASE_TWO)
                if (tick in 20 until 45 && tick % 4 == 0) level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.5, z, 10, 0.6, 1.0, 0.6, 0.02)
                if (tick == 45) {
                    setFlag(PHASE_TWO, true)
                    level.playSound(null, x, y, z, ModSounds.WARDEN_FLAME.get(), SoundSource.HOSTILE, 1.6f, 0.8f)
                }
                if (tick == 60) {
                    level.playSound(null, x, y, z, ModSounds.WARDEN_ROAR.get(), SoundSource.HOSTILE, 2f, 0.8f)
                    ring(level, 1.0, 6.0, ParticleTypes.SOUL_FIRE_FLAME)
                }
            }
            else -> {}
        }
    }

    /** The leap: up, across to where the target stood, and down in a burst of fire, then a ring of it. */
    private fun flameSlam(level: ServerLevel, tick: Int) {
        if (tick == 10) {
            val target = target
            val wanted = target?.position() ?: position()
            val offset = wanted.subtract(position())
            val flat = sqrt(offset.x * offset.x + offset.z * offset.z)
            // Land just short of the target, never more than seven blocks away
            val reach = min(7.0, (flat - 1.5).coerceAtLeast(0.0))
            landing = if (flat > 0.01) position().add(offset.x / flat * reach, 0.0, offset.z / flat * reach) else position()
            deltaMovement = Vec3(deltaMovement.x, 0.6, deltaMovement.z)
            needsSync = true
        }
        val spot = landing
        if (tick in 10..23 && spot != null) {
            val left = (24 - tick).coerceAtLeast(1)
            deltaMovement = Vec3((spot.x - x) / left, deltaMovement.y, (spot.z - z) / left)
        }
        if (tick == 24) {
            landing = null
            impact(level, position(), 1.5f, 30)
            ring(level, 1.0, 4.0, ParticleTypes.SOUL_FIRE_FLAME)
        }
        if (tick in 28..32) ring(level, 4.0 + (tick - 28) * 0.9, 4.6 + (tick - 28) * 0.9, ParticleTypes.SOUL_FIRE_FLAME)
    }

    /** A blade or boots meeting the ground: a boom, dust and broken earth. */
    private fun impact(level: ServerLevel, at: Vec3, volume: Float, count: Int) {
        level.playSound(null, at.x, at.y, at.z, ModSounds.WARDEN_SLAM.get(), SoundSource.HOSTILE, volume * 1.5f, 0.9f + random.nextFloat() * 0.1f)
        val ground: BlockState = level.getBlockState(BlockPos.containing(at.x, at.y - 0.5, at.z))
        if (!ground.isAir) level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1, at.z, count * 3, 0.8, 0.1, 0.8, 0.15)
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, count, 0.6, 0.05, 0.6, 0.04)
    }

    private fun ring(level: ServerLevel, from: Double, to: Double, particle: net.minecraft.core.particles.ParticleOptions) {
        val radius = (from + to) / 2
        val points = (radius * 10).toInt().coerceAtLeast(8)
        for (i in 0 until points) {
            val a = i * Math.PI * 2 / points
            level.sendParticles(particle, x + cos(a) * radius, y + 0.2, z + sin(a) * radius, 1, (to - from) / 4, 0.1, (to - from) / 4, 0.01)
        }
    }

    // ---- Damage ----

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount)
        if (dormant) {
            if (source.entity is Player) awaken(level)
            return false
        }
        // Untouchable while he rises and while the fire takes him
        if (attack === RISE || attack === KNEEL) return false
        val hurt = super.hurtServer(level, source, amount)
        if (hurt) recentHits.addLast(tickCount)
        return hurt
    }

    override fun isPushable(): Boolean = !dormant && super.isPushable()

    override fun die(source: DamageSource) {
        val level = level()
        if (level is ServerLevel) {
            cue(level, BossCuePayload.DEATH)
            bossEvent.removeAllPlayers()
            level.sendParticles(ParticleTypes.SOUL, x, y + 1.5, z, 20, 0.6, 1.2, 0.6, 0.05)
            // A lair's boss leaves its hoard on the altar; one met elsewhere carries his own
            if (!hasData(ModAttachments.LAIR_BOSS)) {
                spawnAtLocation(level, Relics.roll(level, 4, Rarity.RARE))
                spawnAtLocation(level, ItemStack(ModItems.GUILD_MARK.get(), 20))
            }
        }
        super.die(source)
    }

    /** He sinks to one knee and lingers while he speaks his last, then goes in a gust of souls. */
    override fun tickDeath() {
        deathTime++
        val level = level() as? ServerLevel ?: return
        if (deathTime % 4 == 0) level.sendParticles(ParticleTypes.SOUL, x, y + 1.2, z, 3, 0.4, 0.6, 0.4, 0.02)
        if (deathTime >= DEATH_TICKS && !isRemoved) {
            level.sendParticles(ParticleTypes.SOUL, x, y + 1.5, z, 60, 0.6, 1.2, 0.6, 0.05)
            level.broadcastEntityEvent(this, 60.toByte())
            remove(RemovalReason.KILLED)
        }
    }

    override fun startSeenByPlayer(player: ServerPlayer) {
        super.startSeenByPlayer(player)
        if (fighting) bossEvent.addPlayer(player)
    }

    override fun stopSeenByPlayer(player: ServerPlayer) {
        super.stopSeenByPlayer(player)
        bossEvent.removePlayer(player)
    }

    override fun getAmbientSound(): SoundEvent? = if (dormant) null else ModSounds.WARDEN_AMBIENT.get()
    override fun getHurtSound(source: DamageSource): SoundEvent = ModSounds.WARDEN_HURT.get()
    override fun getDeathSound(): SoundEvent = ModSounds.WARDEN_DEATH.get()
    override fun getSoundVolume(): Float = 1.5f

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        playSound(ModSounds.WARDEN_STEP.get(), 0.8f, 1f)
    }

    // ---- Saving ----

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.store("home", BlockPos.CODEC, home)
    }

    /** A fight never survives a reload: he's back at his post, waiting. */
    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        home = input.read("home", BlockPos.CODEC).orElse(blockPosition())
        setFlag(DORMANT, true)
        setFlag(FIGHTING, false)
        setFlag(PHASE_TWO, false)
        health = maxHealth
    }

    companion object {
        const val BOSS_KEY = "boss.guildmark.fallen_warden"
        private const val DORMANT = 2
        private const val FIGHTING = 4
        private const val PHASE_TWO = 8
        private const val WAKE_RANGE = 14.0
        private const val FIGHT_RANGE = 40.0
        private const val CUE_RANGE = 48.0
        private const val LEASH = 30.0
        /** Ticks his death takes, while he says his last (the client's death scene is timed to it). */
        const val DEATH_TICKS = 90

        // Timings match art/scripts/enemy_anims.py
        val SWEEP = EnemyAttack(1, "sweep", 32, listOf(Blow(14, 1.0f, 4.3, 75f, 35f, knockback = 0.6f)), listOf(0..10),
            maxRange = 4.0, weight = 4, cooldown = 20)
        val OVERHEAD = EnemyAttack(2, "overhead", 40, listOf(
            Blow(20, 1.6f, 4.6, 22f, 60f, parryable = false, knockback = 0.8f, guard = 2f),
            Blow(20, 0.5f, 2.2, 180f, 25f, parryable = false, knockback = 0.6f, forward = 3.2),
        ), listOf(0..15), maxRange = 4.4, weight = 3, cooldown = 28, turn = 8f)
        val COMBO = EnemyAttack(3, "combo", 52, listOf(
            Blow(12, 0.9f, 4.2, 75f, 30f),
            Blow(22, 0.9f, 4.2, 75f, 30f),
            Blow(34, 1.2f, 5.0, 18f, 40f, knockback = 0.8f),
        ), listOf(0..9, 15..19, 26..31), maxRange = 4.0, weight = 3, cooldown = 30, lunge = 0.5f, lungeAt = 32, lungeTicks = 2)
        val CHARGE = EnemyAttack(4, "charge", 40, listOf(Blow(16, 1.3f, 2.4, 70f, 50f, parryable = false, knockback = 1.2f, until = 24)),
            listOf(0..12), minRange = 6.0, maxRange = 16.0, weight = 4, cooldown = 24, lunge = 0.62f, lungeAt = 13, lungeTicks = 11, turn = 6f)
        val STOMP = EnemyAttack(5, "stomp", 24, listOf(Blow(9, 0.7f, 3.2, 180f, 30f, parryable = false, knockback = 1.3f)), emptyList(),
            maxRange = 2.5, weight = 3, cooldown = 14)
        val FLAME_SLAM = EnemyAttack(6, "flame_slam", 50, listOf(
            Blow(24, 1.4f, 4.0, 180f, 60f, parryable = false, knockback = 1.0f, fire = 3),
            Blow(32, 0.9f, 7.5, 180f, 30f, parryable = false, knockback = 0.8f, inner = 4.0, fire = 4),
        ), listOf(0..10), minRange = 2.5, maxRange = 10.0, weight = 3, cooldown = 30, phase = 2)
        val FLAME_WAVE = EnemyAttack(7, "flame_wave", 40, listOf(Blow(16, 1.0f, 4.3, 75f, 35f, knockback = 0.6f)) +
            (0..9).map { Blow(18 + it, 0.8f, 1.5, 180f, 20f, parryable = false, knockback = 0.5f, forward = 2.0 + it, fire = 3, group = 1) },
            listOf(0..12), maxRange = 10.0, weight = 3, cooldown = 26, phase = 2)
        /** His introduction: he rises from his knee and pulls his sword from the ground. */
        val RISE = EnemyAttack(20, "rise", 200, emptyList(), emptyList(), weight = 0, cooldown = 10)
        /** The change of phase: he kneels as the fire takes him, then throws everyone back. */
        val KNEEL = EnemyAttack(21, "kneel", 80, listOf(Blow(60, 0.4f, 6.0, 180f, 0f, parryable = false, knockback = 1.6f)), emptyList(),
            weight = 0, cooldown = 10)
        val ATTACKS = listOf(SWEEP, OVERHEAD, COMBO, CHARGE, STOMP, FLAME_SLAM, FLAME_WAVE, RISE, KNEEL)

        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 300.0)
            .add(Attributes.ATTACK_DAMAGE, 9.0)
            .add(Attributes.ARMOR, 10.0)
            .add(Attributes.ARMOR_TOUGHNESS, 4.0)
            .add(Attributes.MOVEMENT_SPEED, 0.24)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FOLLOW_RANGE, 40.0)
            .add(Attributes.STEP_HEIGHT, 1.0)
    }
}
