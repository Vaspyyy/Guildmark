package io.github.vaspyyy.guildmark.enemy

import io.github.vaspyyy.guildmark.combat.Blow
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.combat.FightGoal
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.field.FieldBosses
import io.github.vaspyyy.guildmark.network.BossCuePayload
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
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
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * A hill troll, a field boss that keeps a camp of its own in the wild. By day it sleeps sitting by its fire
 * and a quiet step can get close enough for a first blow that lands twice as hard; by night it prowls the
 * camp. It fights with a club cut from a tree: wide sweeps, an overhead smash that splits the ground, a
 * shoulder charge for anyone who keeps away, boulders torn up and thrown, and a stamp for anyone behind it.
 * Wounded past half, it beats its chest and roars, then pounds the ground in rolling shockwaves.
 */
class HillTroll(type: EntityType<out HillTroll>, level: Level) : CombatEnemy(type, level) {
    override val attacks: List<EnemyAttack> get() = ATTACKS
    override val animations: String get() = "hill_troll"
    override val isBoss: Boolean get() = true
    override val maxStance: Float get() = 220f
    override val waitRange: Double get() = 3.4
    override val chaseSpeed: Double get() = if (enraged) 1.2 else 1.05
    override val cooldownScale: Float get() = if (enraged) 0.7f else 1f

    /** Asleep by its fire. */
    val dormant: Boolean get() = flag(DORMANT)
    /** It has noticed someone and the fight is on. */
    val fighting: Boolean get() = flag(FIGHTING)
    /** Past half its health: faster, and it pounds the ground. */
    val enraged: Boolean get() = flag(ENRAGED)

    /** Its camp, which it never strays far from and goes back to when nobody is left to fight. */
    var home: BlockPos = BlockPos.ZERO
    /** The field boss site it belongs to (see FieldBosses), or -1 for one summoned by hand. */
    var site: Int = -1
    /** Which way it faces asleep in its nest: toward its fire. */
    var homeYaw: Float = 0f
    /** Which of the troll names it answers to. */
    var nameIndex: Int = 0
        private set
    private var lonely = 0
    private var restless = 0
    private val recentHits = ArrayDeque<Int>()
    private var thrown = false
    /** The players in this fight, each by the entity they were when they joined it (dying makes a new one). */
    private val foes = HashMap<UUID, Int>()
    /** Ticks it has been heading home without getting there. */
    private var homeward = 0
    /** It let someone go at the edge of its ground and is stomping home, paying nobody any mind. */
    private var returning = false
    /** Loaded from disk: it goes back to its nest as soon as the land there is loaded. */
    private var backToBed = false

    private val bossEvent = ServerBossEvent(
        Mth.createInsecureUUID(random), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10,
    )

    init {
        xpReward = 300
    }

    /** "Gnarltooth, Hill Troll". */
    val title: Component get() = Component.translatable(BOSS_KEY, Component.translatable("$NAME_KEY.$nameIndex"))

    override fun canFight(): Boolean = fighting && !dormant

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, FightGoal(this))
        goalSelector.addGoal(6, object : WaterAvoidingRandomStrollGoal(this, 0.6) {
            // Prowls by night; by day it's only heading home to sleep
            override fun canUse(): Boolean = !dormant && !fighting && !returning && attack == null && !level().isBrightOutside && super.canUse()
        })
        goalSelector.addGoal(7, object : LookAtPlayerGoal(this, Player::class.java, 12f) {
            override fun canUse(): Boolean = !dormant && super.canUse()
        })
        // It picks its targets itself (see fight): players only, and only those in the fight
    }

    override fun finalizeSpawn(level: ServerLevelAccessor, difficulty: DifficultyInstance, spawnReason: EntitySpawnReason, groupData: SpawnGroupData?): SpawnGroupData? {
        val data = super.finalizeSpawn(level, difficulty, spawnReason, groupData)
        home = blockPosition()
        nameIndex = random.nextInt(NAMES)
        setHomeTo(home, ROAM)
        setFlag(DORMANT, true)
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

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
        if (backToBed) {
            if (!level.isLoaded(home)) return
            backToBed = false
            settle()
        }
        if (dormant) {
            navigation.stop()
            deltaMovement = Vec3(0.0, deltaMovement.y, 0.0)
            if (tickCount % 5 == 0) {
                // Asleep in the daylight; a sneaking step gets close enough to strike first
                val waker = level.players().firstOrNull {
                    val range = if (it.isCrouching) CROUCH_WAKE_RANGE else WAKE_RANGE
                    fair(it) && it.distanceToSqr(this) < range * range && hasLineOfSight(it)
                }
                if (waker != null) awaken(level, waker)
                else if (!level.isBrightOutside && tickCount % 100 == 0) rise(level)
                else if (tickCount % 60 == 0) snore(level)
            }
            return
        }
        if (fighting) fight(level) else prowl(level)
    }

    /** Out of a fight: by night it prowls its camp, and anyone who comes close starts one; by day it goes back to sleep. */
    private fun prowl(level: ServerLevel) {
        target = null
        if (returning) {
            // It let someone go at the edge of its ground: it stomps home, paying nobody any mind till it's there
            if (isNearHome(ROAM.toDouble())) returning = false
            else {
                goHome(level, 1.0)
                return
            }
        }
        if (tickCount % 5 == 0) {
            val seen = level.players()
                .filter { fair(it) && it.distanceToSqr(this) < NOTICE_RANGE * NOTICE_RANGE && hasLineOfSight(it) }
                .minByOrNull { it.distanceToSqr(this) }
            if (seen != null) {
                startFight(level, seen)
                return
            }
        }
        if (attack != null) return
        // Once the sun is up it heads back to its fire and goes to sleep
        if (level.isBrightOutside) {
            if (isNearHome(3.0)) {
                homeward = 0
                if (++restless > 100) settle()
            } else {
                restless = 0
                goHome(level, 0.8)
            }
        } else {
            restless = 0
            if (!isNearHome(ROAM + 4.0)) goHome(level, 0.8) else homeward = 0
        }
    }

    /** In a fight: it picks whom to go for among the players in it, and never strays far from its camp. */
    private fun fight(level: ServerLevel) {
        // Anyone it sees close by joins the fight
        if (tickCount % 5 == 0) {
            for (player in level.players()) {
                if (fair(player) && player.distanceToSqr(this) < NOTICE_RANGE * NOTICE_RANGE && hasLineOfSight(player)) join(player)
            }
        }
        val alive = livingFoes(level)
        // Everyone it was fighting is dead or gone: the fight is over
        if (alive.isEmpty()) {
            calmDown(level)
            return
        }
        val watchers = level.players().filter { it.isAlive && !it.isSpectator && it.distanceToSqr(this) < FIGHT_RANGE * FIGHT_RANGE }
        for (player in bossEvent.players.toList()) if (player !in watchers) bossEvent.removePlayer(player)
        for (player in watchers) if (player is ServerPlayer && player !in bossEvent.players) bossEvent.addPlayer(player)
        val near = alive.filter { it.distanceToSqr(this) < FIGHT_RANGE * FIGHT_RANGE }
        if (near.isEmpty()) {
            // They've got away: it heads back to its fire, and gives the fight up if they don't come back
            target = null
            if (attack == null) goHome(level, 0.9)
            if (++lonely >= 200) calmDown(level)
            return
        }
        lonely = 0
        // It keeps after whoever it's after while they're in reach; otherwise it goes for the nearest
        val current = target as? Player
        if (current == null || current !in near) target = near.minBy { it.distanceToSqr(this) }
        if (Stance.isStaggered(this)) return

        // Never far from its camp: past the edge of its ground it lets them go and stomps home, whole again.
        // A fight at arm's length gets a little further before it gives up.
        if (attack == null && !isNearHome(LEASH)) {
            val close = target?.let { distanceTo(it) < 6f } == true
            if (!close || !isNearHome(LEASH + 12.0)) {
                calmDown(level)
                returning = true
                return
            }
        }

        // Half its health gone: it beats its chest and roars
        if (!enraged && attack !== ROAR && health <= maxHealth * 0.5f) {
            if (attack != null) stopAttack(true)
            startAttack(ROAR)
            return
        }
        // Crowded and hit hard: it stamps everyone off
        while (recentHits.isNotEmpty() && tickCount - recentHits.first() > 40) recentHits.removeFirst()
        val target = target
        if (attack == null && target != null && recentHits.size >= 3 && distanceTo(target) < 4.5f) {
            recentHits.clear()
            startAttack(STOMP)
        }
    }

    private fun isNearHome(range: Double): Boolean =
        home == BlockPos.ZERO || distanceToSqr(home.x + 0.5, home.y.toDouble(), home.z + 0.5) < range * range

    /**
     * Heads for its nest. If it can't get there (led down a cliff, walled out), it turns up there when nobody's
     * looking; and if its nest has been built over, it makes a new one where it stands.
     */
    private fun goHome(level: ServerLevel, speed: Double) {
        if (home == BlockPos.ZERO) return
        if (homeward++ % 20 == 0) navigation.moveTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5, speed)
        if (homeward > STRANDED_TICKS && (homeward > STRANDED_TICKS * 4 || level.players().none { it.distanceToSqr(this) < 32.0 * 32.0 })) {
            homeward = 0
            returning = false
            navigation.stop()
            if (nestFree()) {
                snapTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5, homeYaw, 0f)
            } else {
                home = blockPosition()
                homeYaw = yRot
                setHomeTo(home, ROAM)
            }
            yBodyRot = yRot
            yHeadRot = yRot
        }
    }

    /** Whether it would fit in its nest (a little above the floor, so a carpet of moss doesn't count against it). */
    private fun nestFree(): Boolean =
        level().noCollision(this, getDimensions(pose).makeBoundingBox(home.x + 0.5, home.y + 0.2, home.z + 0.5))

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

    /** Woken: it starts with a grunt and heaves itself up, and roars once it's on its feet (WAKE's tick 44). */
    fun awaken(level: ServerLevel, by: Player?) {
        if (!dormant) return
        setFlag(DORMANT, false)
        startFight(level, by)
        startAttack(WAKE)
        level.playSound(null, x, y, z, ModSounds.TROLL_GRUNT.get(), SoundSource.HOSTILE, 2.0f, 0.9f)
    }

    private fun startFight(level: ServerLevel, by: Player?) {
        if (by != null) {
            join(by)
            target = by
        }
        if (fighting) return
        setFlag(FIGHTING, true)
        returning = false
        homeward = 0
        lonely = 0
        cooldown = 10
        bossEvent.name = title
        FieldBosses.get(level).onWoken(level, this)
    }

    /** The fight is over: it lumbers home, whole again. */
    private fun calmDown(level: ServerLevel) {
        lonely = 0
        if (attack != null) stopAttack(true)
        bossEvent.removeAllPlayers()
        foes.clear()
        recentHits.clear()
        health = maxHealth
        setFlag(FIGHTING, false)
        setFlag(ENRAGED, false)
        target = null
        homeward = 0
        navigation.moveTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5, 0.9)
        level.playSound(null, x, y, z, ModSounds.TROLL_GRUNT.get(), SoundSource.HOSTILE, 1.4f, 0.8f)
    }

    /** Night has fallen: it heaves itself up to prowl its camp till morning. */
    private fun rise(level: ServerLevel) {
        setFlag(DORMANT, false)
        restless = 0
        startAttack(WAKE)
        level.playSound(null, x, y, z, ModSounds.TROLL_GRUNT.get(), SoundSource.HOSTILE, 1.6f, 0.8f)
    }

    /** Sits down in its nest facing the fire and falls asleep; if the nest has been built over, it sleeps where it is. */
    private fun settle() {
        restless = 0
        homeward = 0
        returning = false
        navigation.stop()
        if (home != BlockPos.ZERO && nestFree()) snapTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5, homeYaw, 0f)
        yBodyRot = yRot
        yHeadRot = yRot
        setFlag(DORMANT, true)
    }

    private fun snore(level: ServerLevel) {
        level.playSound(null, x, y, z, ModSounds.TROLL_SNORE.get(), SoundSource.HOSTILE, 1.2f, 0.9f + random.nextFloat() * 0.15f)
        val look = Vec3.directionFromRotation(0f, yRot)
        level.sendParticles(ParticleTypes.POOF, x + look.x * 1.2, y + 2.4, z + look.z * 1.2, 2, 0.1, 0.05, 0.1, 0.005)
    }

    // ---- Attacks ----

    override fun usable(attack: EnemyAttack, target: LivingEntity, distance: Double): Boolean {
        if (attack.phase > (if (enraged) 2 else 1)) return false
        if (attack === STOMP) return distance < 3.0 && Combat.angleTo(yRot, target.x - x, target.z - z) > 100f
        if (attack === THROW && !hasLineOfSight(target)) return false
        // A charge that would carry it off its ground is one it doesn't start
        if (attack === CHARGE && home != BlockPos.ZERO) {
            val run = target.position().subtract(position()).horizontal().normalize().scale(CHARGE_RUN)
            if (Vec3(home.x + 0.5, y, home.z + 0.5).distanceToSqr(position().add(run)) > LEASH * LEASH) return false
        }
        return super.usable(attack, target, distance)
    }

    override fun onAttackStart(attack: EnemyAttack) {
        thrown = false
        when (attack) {
            CHARGE, POUND -> playSound(ModSounds.TROLL_ROAR.get(), 1.6f, 1.1f)
            SMASH, THROW -> playSound(ModSounds.TROLL_GRUNT.get(), 1.4f, 0.9f)
            else -> {}
        }
    }

    override fun onAttackTick(level: ServerLevel, attack: EnemyAttack, tick: Int) {
        for (blow in attack.blows) {
            if (tick == blow.at - 3 && blow.arc < 180f && attack !== CHARGE) {
                level.playSound(null, x, y, z, ModSounds.TROLL_SWING.get(), SoundSource.HOSTILE, 1.4f, 0.85f + random.nextFloat() * 0.1f)
            }
        }
        val look = Vec3.directionFromRotation(0f, yRot)
        when (attack) {
            SWEEP -> if (tick == 17) dust(level, position().add(look.scale(3.0)), 8)
            SMASH -> {
                if (tick == 10) playSound(ModSounds.TROLL_GRUNT.get(), 1.2f, 1.2f)
                if (tick == 24) {
                    impact(level, position().add(look.scale(4.0)), 1.6f, 30)
                    crack(level, position().add(look.scale(4.0)), 2.2)
                }
            }
            STOMP -> if (tick == 12) impact(level, position(), 1.4f, 26)
            THROW -> throwBoulder(level, tick, look)
            CHARGE -> if (tick in 14..28 && tick % 3 == 0) {
                level.playSound(null, x, y, z, ModSounds.TROLL_STEP.get(), SoundSource.HOSTILE, 1.4f, 1.15f)
                dust(level, position(), 5)
            }
            POUND -> {
                if (tick == 22 || tick == 40) impact(level, position().add(look.scale(2.0)), 1.7f, 30)
                if (tick in 24..30 || tick in 42..48) {
                    val k = if (tick <= 30) tick - 24 else tick - 42
                    ring(level, position().add(look.scale(2.0)), 2.5 + k * 0.8, BlockParticleOption(ParticleTypes.BLOCK, groundBelow(level)))
                }
            }
            ROAR -> {
                if (tick in 8..26 && tick % 6 == 2) level.playSound(null, x, y, z, ModSounds.TROLL_STEP.get(), SoundSource.HOSTILE, 1.6f, 0.7f)
                if (tick == 30) level.playSound(null, x, y, z, ModSounds.TROLL_ROAR.get(), SoundSource.HOSTILE, 3f, 0.8f)
                if (tick == 34) {
                    ring(level, position(), 3.0, ParticleTypes.CLOUD)
                    ring(level, position(), 5.0, ParticleTypes.CLOUD)
                }
                if (tick == 40) setFlag(ENRAGED, true)
            }
            WAKE -> {
                if (tick == 20) dust(level, position(), 12)
                if (tick == 44) level.playSound(null, x, y, z, ModSounds.TROLL_ROAR.get(), SoundSource.HOSTILE, 2.4f, 1.0f)
            }
            else -> {}
        }
    }

    /** Tears a boulder from the ground, lifts it and hurls it at its target. */
    private fun throwBoulder(level: ServerLevel, tick: Int, look: Vec3) {
        val hand = position().add(look.scale(1.6))
        if (tick == 16) {
            val ground = groundBelow(level)
            level.playSound(null, hand.x, y, hand.z, ground.soundType.breakSound, SoundSource.HOSTILE, 1.6f, 0.7f)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, ground), hand.x, y + 0.2, hand.z, 30, 0.6, 0.1, 0.6, 0.15)
        }
        if (tick == 32 && !thrown) {
            thrown = true
            val target = target ?: return
            val from = position().add(look.scale(0.6)).add(0.0, 4.2, 0.0)
            val boulder = TrollBoulder(level, this, from)
            // Lead the target a little and lob it so it comes down on them
            val aim = target.position().add(target.deltaMovement.scale(8.0)).add(0.0, target.bbHeight * 0.5, 0.0)
            val dx = aim.x - from.x
            val dz = aim.z - from.z
            val flat = Math.sqrt(dx * dx + dz * dz)
            val speed = 1.25
            val ticks = (flat / speed).coerceAtLeast(4.0)
            val vy = (aim.y - from.y) / ticks + 0.5 * TrollBoulder.GRAVITY * ticks
            boulder.deltaMovement = Vec3(dx / ticks, vy, dz / ticks)
            level.addFreshEntity(boulder)
            level.playSound(null, x, y, z, ModSounds.TROLL_THROW.get(), SoundSource.HOSTILE, 1.6f, 0.9f)
        }
    }

    private fun groundBelow(level: ServerLevel): BlockState {
        val state = level.getBlockState(blockPosition().below())
        return if (state.isAir) Blocks.DIRT.defaultBlockState() else state
    }

    /** The club or a foot meeting the ground: a boom, dust and broken earth. */
    private fun impact(level: ServerLevel, at: Vec3, volume: Float, count: Int) {
        level.playSound(null, at.x, at.y, at.z, ModSounds.TROLL_SLAM.get(), SoundSource.HOSTILE, volume * 1.5f, 0.85f + random.nextFloat() * 0.1f)
        val ground = level.getBlockState(BlockPos.containing(at.x, at.y - 0.5, at.z))
        if (!ground.isAir) level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1, at.z, count * 3, 0.9, 0.1, 0.9, 0.15)
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, count, 0.7, 0.05, 0.7, 0.04)
    }

    private fun dust(level: ServerLevel, at: Vec3, count: Int) {
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, count, 0.6, 0.05, 0.6, 0.02)
    }

    /** A line of torn earth running on from the smash. */
    private fun crack(level: ServerLevel, at: Vec3, length: Double) {
        val look = Vec3.directionFromRotation(0f, yRot)
        val ground = groundBelow(level)
        var d = 0.0
        while (d <= length) {
            val p = at.add(look.scale(d))
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, ground), p.x, p.y + 0.1, p.z, 6, 0.2, 0.05, 0.2, 0.1)
            d += 0.5
        }
    }

    private fun ring(level: ServerLevel, at: Vec3, radius: Double, particle: ParticleOptions) {
        val points = (radius * 10).toInt().coerceAtLeast(8)
        for (i in 0 until points) {
            val a = i * Math.PI * 2 / points
            level.sendParticles(particle, at.x + cos(a) * radius, at.y + 0.2, at.z + sin(a) * radius, 1, 0.1, 0.05, 0.1, 0.01)
        }
    }

    // ---- Damage ----

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount)
        val player = attacker(source)
        // Whoever it would fight back (a player in creative can still test it, but it pays them no mind)
        val by = player?.takeIf { fair(it) }
        var damage = amount
        if (dormant) {
            // Only a player wakes it; caught asleep, a blow struck by hand lands twice as hard
            if (player == null) return false
            if (source.isDirect && source.entity === player) damage *= 2f
            awaken(level, by)
        } else if (attack === WAKE || attack === ROAR) {
            // It shrugs off blows while it gets up and while it roars
            return false
        }
        val hurt = super.hurtServer(level, source, damage)
        if (hurt && by != null) {
            recentHits.addLast(tickCount)
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

    // It won't throw itself down a cliff after anyone
    override fun getMaxFallDistance(): Int = minOf(super.getMaxFallDistance(), 4)

    override fun isPushable(): Boolean = !dormant && super.isPushable()

    override fun die(source: DamageSource) {
        if (isRemoved || dead) return
        super.die(source)
        // Something kept it from dying after all
        if (!dead) return
        val level = level() as? ServerLevel ?: return
        bossEvent.removeAllPlayers()
        setFlag(FIGHTING, false)
        foes.clear()
        FieldBosses.get(level).onSlain(level, this)
        // Everyone near sees it fall
        for (player in level.players()) {
            if (player is ServerPlayer && player.distanceToSqr(this) < CUE_RANGE * CUE_RANGE) {
                PacketDistributor.sendToPlayer(player, BossCuePayload(id, BossCuePayload.FELLED))
                Combat.shieldForScene(player, BossCuePayload.sceneTicks(BossCuePayload.FELLED))
            }
        }
    }

    /** It drops to its knees and pitches forward, and lies still a while before it's gone. */
    override fun tickDeath() {
        deathTime++
        val level = level() as? ServerLevel ?: return
        if (deathTime == 28) {
            level.playSound(null, x, y, z, ModSounds.TROLL_SLAM.get(), SoundSource.HOSTILE, 2f, 0.7f)
            val look = Vec3.directionFromRotation(0f, yRot)
            impact(level, position().add(look.scale(2.5)), 1.2f, 30)
        }
        if (deathTime >= DEATH_TICKS && !isRemoved) {
            level.sendParticles(ParticleTypes.POOF, x, y + 1.0, z, 40, 1.2, 0.6, 1.2, 0.04)
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

    override fun getAmbientSound(): SoundEvent? = if (dormant) null else ModSounds.TROLL_GRUNT.get()
    override fun getHurtSound(source: DamageSource): SoundEvent = ModSounds.TROLL_HURT.get()
    override fun getDeathSound(): SoundEvent = ModSounds.TROLL_DEATH.get()
    override fun getSoundVolume(): Float = 1.6f
    override fun getVoicePitch(): Float = 0.9f + random.nextFloat() * 0.1f

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        playSound(ModSounds.TROLL_STEP.get(), 1.0f, 1f)
    }

    // ---- Saving ----

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.store("home", BlockPos.CODEC, home)
        output.putFloat("home_yaw", homeYaw)
        output.putInt("site", site)
        output.putInt("troll_name", nameIndex)
    }

    /** A fight never survives a reload: it's back asleep in its nest, whole again. */
    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        home = input.read("home", BlockPos.CODEC).orElse(blockPosition())
        homeYaw = input.getFloatOr("home_yaw", yRot)
        site = input.getIntOr("site", -1)
        nameIndex = input.getIntOr("troll_name", 0).coerceIn(0, NAMES - 1)
        setHomeTo(home, ROAM)
        bossEvent.name = title
        if (health > 0f) {
            setFlag(DORMANT, true)
            setFlag(FIGHTING, false)
            setFlag(ENRAGED, false)
            health = maxHealth
            backToBed = true
        }
    }

    companion object {
        const val BOSS_KEY = "boss.guildmark.hill_troll"
        const val NAME_KEY = "boss.guildmark.troll_name"
        /** How many names a troll might have (lang keys boss.guildmark.troll_name.0 and on). */
        const val NAMES = 16
        private const val DORMANT = 2
        private const val FIGHTING = 4
        private const val ENRAGED = 8
        private const val WAKE_RANGE = 12.0
        /** How near someone sneaking can get before it wakes: close enough to strike first, just. */
        private const val CROUCH_WAKE_RANGE = 3.5
        private const val NOTICE_RANGE = 18.0
        private const val FIGHT_RANGE = 40.0
        private const val ROAM = 10
        /** How far from its nest it will go in a fight. */
        private const val LEASH = 32.0
        /** How far its charge carries it. */
        private const val CHARGE_RUN = 8.0
        /** Ticks it tries to walk home before it's taken as stuck and turns up there when nobody's looking. */
        private const val STRANDED_TICKS = 600
        /** How near players are to see it fall. */
        private const val CUE_RANGE = 48.0
        /** Ticks its death takes: down on its knees, then flat on its face. */
        const val DEATH_TICKS = 70
        private val TIER_HEALTH = net.minecraft.resources.Identifier.fromNamespaceAndPath("guildmark", "troll_tier_health")
        private val TIER_DAMAGE = net.minecraft.resources.Identifier.fromNamespaceAndPath("guildmark", "troll_tier_damage")

        // Timings match art/scripts/enemy_anims.py
        val SWEEP = EnemyAttack(1, "sweep", 36, listOf(Blow(17, 1.0f, 5.0, 80f, 40f, knockback = 0.9f)), listOf(0..12),
            maxRange = 4.6, weight = 4, cooldown = 22)
        val SMASH = EnemyAttack(2, "smash", 46, listOf(
            Blow(24, 1.35f, 5.2, 24f, 70f, parryable = false, knockback = 0.9f, guard = 2f),
            Blow(24, 0.6f, 2.6, 180f, 30f, parryable = false, knockback = 0.8f, forward = 4.0),
        ), listOf(0..16), maxRange = 5.0, weight = 3, cooldown = 30, turn = 8f)
        val STOMP = EnemyAttack(3, "stomp", 28, listOf(Blow(12, 0.8f, 3.6, 180f, 35f, parryable = false, knockback = 1.4f)), emptyList(),
            maxRange = 3.0, weight = 3, cooldown = 16)
        val THROW = EnemyAttack(4, "throw", 50, emptyList(), listOf(0..30), minRange = 7.0, maxRange = 24.0, weight = 3, cooldown = 40, turn = 10f)
        val CHARGE = EnemyAttack(5, "charge", 44, listOf(Blow(16, 1.15f, 2.8, 70f, 55f, parryable = false, knockback = 1.5f, until = 28)),
            listOf(0..12), minRange = 7.0, maxRange = 18.0, weight = 3, cooldown = 30, lunge = 0.55f, lungeAt = 14, lungeTicks = 14, turn = 5f)
        val POUND = EnemyAttack(6, "pound", 60, listOf(
            Blow(22, 1.2f, 4.0, 180f, 45f, parryable = false, knockback = 1.0f, forward = 2.0),
            Blow(26, 0.7f, 7.5, 180f, 25f, parryable = false, knockback = 0.8f, inner = 3.5, forward = 2.0, group = 1),
            Blow(40, 1.2f, 4.0, 180f, 45f, parryable = false, knockback = 1.0f, forward = 2.0),
            Blow(44, 0.7f, 7.5, 180f, 25f, parryable = false, knockback = 0.8f, inner = 3.5, forward = 2.0, group = 2),
        ), listOf(0..14, 28..34), maxRange = 6.0, weight = 3, cooldown = 34, phase = 2)
        /** Past half its health: it beats its chest and roars, throwing everyone back. */
        val ROAR = EnemyAttack(20, "roar", 64, listOf(Blow(34, 0.3f, 6.5, 180f, 0f, parryable = false, knockback = 1.8f)), emptyList(),
            weight = 0, cooldown = 10)
        /** Woken: it heaves itself up off the ground, grabs its club and roars. */
        val WAKE = EnemyAttack(21, "wake", 60, emptyList(), emptyList(), weight = 0, cooldown = 10)
        val ATTACKS = listOf(SWEEP, SMASH, STOMP, THROW, CHARGE, POUND, ROAR, WAKE)

        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 360.0)
            .add(Attributes.ATTACK_DAMAGE, 10.0)
            .add(Attributes.ARMOR, 6.0)
            .add(Attributes.ARMOR_TOUGHNESS, 2.0)
            .add(Attributes.MOVEMENT_SPEED, 0.23)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FOLLOW_RANGE, 40.0)
            .add(Attributes.STEP_HEIGHT, 1.5)
    }
}
