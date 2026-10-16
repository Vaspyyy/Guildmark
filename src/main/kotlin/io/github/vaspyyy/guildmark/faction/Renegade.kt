package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.combat.Blow
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.combat.FightGoal
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.magic.BoltKind
import io.github.vaspyyy.guildmark.magic.MagicRegistry
import io.github.vaspyyy.guildmark.magic.Spell
import io.github.vaspyyy.guildmark.magic.SpellBolt
import io.github.vaspyyy.guildmark.network.BossCuePayload
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Places
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.BossEvent
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
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
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Silvan Marrow, the Unbound: once a Beacon of the Starwatch, who read the forbidden star chart and left. He waits in
 * a ring of standing stones on high ground (the old observatory) and fights with sorcery: an arc bolt that bends
 * after you, a fan of splinters, a rime lance drawn back over his shoulder (the long wind-up is the warning), a
 * palm-blast for whoever crowds him and a backhand of the staff, and he blinks away when pressed. He calls up
 * ward-lights that shoot. At half his strength he cries out to the sky, and the stars start falling where circles of
 * light mark the ground. The Starwatch's last chapter (see Trials).
 */
class Renegade(type: EntityType<out Renegade>, level: Level) : CombatEnemy(type, level) {
    override val attacks: List<EnemyAttack> get() = ATTACKS
    override val animations: String get() = "renegade"
    override val isBoss: Boolean get() = true
    override val maxStance: Float get() = 120f
    override val waitRange: Double get() = 7.0
    override val chaseSpeed: Double get() = 1.0
    override val cooldownScale: Float get() = if (phaseTwo) 0.75f else 1f

    /** Waiting among his stones for someone to come. */
    val dormant: Boolean get() = flag(DORMANT)
    /** The fight is on (after he's said his piece). */
    val fighting: Boolean get() = flag(FIGHTING)
    /** He has called the stars down. */
    val phaseTwo: Boolean get() = flag(PHASE_TWO)

    /** The middle of his ring. */
    var home: BlockPos = BlockPos.ZERO
    private var lonely = 0
    private val recentHits = ArrayDeque<Int>()
    private val sentries = ArrayList<WardLight>()
    /** Where the stars will fall, marked on the ground while they come. */
    private var marks: List<Vec3> = emptyList()

    private val bossEvent = ServerBossEvent(
        Mth.createInsecureUUID(random), Component.translatable(BOSS_KEY), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS,
    )

    init {
        xpReward = 220
    }

    override fun canFight(): Boolean = fighting && !dormant

    override fun registerGoals() {
        goalSelector.addGoal(2, FightGoal(this))
        targetSelector.addGoal(1, HurtByTargetGoal(this))
        targetSelector.addGoal(2, NearestAttackableTargetGoal(this, Player::class.java, false))
    }

    override fun finalizeSpawn(level: ServerLevelAccessor, difficulty: DifficultyInstance, spawnReason: EntitySpawnReason, groupData: SpawnGroupData?): SpawnGroupData? {
        val data = super.finalizeSpawn(level, difficulty, spawnReason, groupData)
        populateDefaultEquipmentSlots(level.random, difficulty)
        home = blockPosition()
        setFlag(DORMANT, true)
        return data
    }

    override fun populateDefaultEquipmentSlots(random: RandomSource, difficulty: DifficultyInstance) {
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack(MagicRegistry.ECHO_STAFF.get()))
        setDropChance(EquipmentSlot.MAINHAND, 0f)
    }

    // ---- Every tick ----

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
        sentries.removeIf { !it.isAlive || it.isRemoved }
        if (dormant) {
            navigation.stop()
            deltaMovement = Vec3(0.0, deltaMovement.y, 0.0)
            if (tickCount % 5 == 0) {
                val waker = level.players().firstOrNull {
                    it.isAlive && !it.isSpectator && !it.isCreative && it.distanceToSqr(this) < WAKE_RANGE * WAKE_RANGE && hasLineOfSight(it)
                }
                if (waker != null) awaken(level, waker)
            }
            return
        }
        // He's said his piece: the fight begins
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
        if (Stance.isStaggered(this) || !fighting) return
        // Strayed from his stones: back in a blink
        if (attack == null && home != BlockPos.ZERO && distanceToSqr(Vec3.atBottomCenterOf(home)) > LEASH * LEASH) {
            startAttack(BLINK)
            return
        }
        // Half his strength gone: he calls the stars down
        if (!phaseTwo && attack !== CALL && health <= maxHealth * 0.5f) {
            if (attack != null) stopAttack(true)
            startAttack(CALL)
            cue(level, BossCuePayload.PHASE_TWO, 0)
            return
        }
        // Pressed hard, or crowded too long: away
        while (recentHits.isNotEmpty() && tickCount - recentHits.first() > 50) recentHits.removeFirst()
        val target = target
        if (attack == null && target != null && (recentHits.size >= 3 || distanceTo(target) < 2.2f && random.nextInt(40) == 0)) {
            recentHits.clear()
            startAttack(BLINK)
        }
        // The circles of the falling stars glow until they land
        if (marks.isNotEmpty() && tickCount % 3 == 0) for (mark in marks) ring(level, mark, STAR_RADIUS, ParticleTypes.END_ROD, 20)
    }

    /** Someone came into his ring: he turns to them and has his say. */
    fun awaken(level: ServerLevel, by: Player?) {
        if (!dormant) return
        setFlag(DORMANT, false)
        if (by != null) {
            target = by
            yRot = Math.toDegrees(Math.atan2(-(by.x - x), by.z - z)).toFloat()
            yBodyRot = yRot
            yHeadRot = yRot
        }
        startAttack(INTRO)
        cue(level, BossCuePayload.INTRO, INTRO_SCENE)
    }

    /** Nobody left to fight: back to the middle of his ring, whole again, waiting. */
    private fun rest(level: ServerLevel) {
        lonely = 0
        if (attack != null) stopAttack(true)
        bossEvent.removeAllPlayers()
        for (light in sentries) light.discard()
        sentries.clear()
        marks = emptyList()
        if (home != BlockPos.ZERO) teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
        health = maxHealth
        setFlag(FIGHTING, false)
        setFlag(PHASE_TWO, false)
        setFlag(DORMANT, true)
        target = null
        level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), x, y + 1.2, z, 30, 0.5, 1.0, 0.5, 0.05)
    }

    private fun cue(level: ServerLevel, cue: Int, shield: Int) {
        for (player in level.players()) {
            if (player is ServerPlayer && player.distanceToSqr(this) < CUE_RANGE * CUE_RANGE) {
                PacketDistributor.sendToPlayer(player, BossCuePayload(id, cue))
                if (shield > 0) Combat.shieldForScene(player, shield)
            }
        }
    }

    // ---- Attacks ----

    override fun usable(attack: EnemyAttack, target: LivingEntity, distance: Double): Boolean {
        if (attack.phase > (if (phaseTwo) 2 else 1)) return false
        if (attack === SUMMON && sentries.size >= MAX_SENTRIES) return false
        if (attack === STARFALL && marks.isNotEmpty()) return false
        return super.usable(attack, target, distance) && (attack.maxRange < 4.0 || hasLineOfSight(target))
    }

    override fun onAttackStart(attack: EnemyAttack) {
        val level = level() as? ServerLevel ?: return
        val sound = when (attack) {
            BOLT -> MagicRegistry.ARC_BOLT_CAST.get()
            FAN -> MagicRegistry.SPLINTER_CAST.get()
            LANCE -> MagicRegistry.ARCANE_CHARGE.get()
            SUMMON, CALL -> MagicRegistry.STAR_CALL.get()
            STARFALL -> MagicRegistry.STAR_CALL.get()
            else -> null
        } ?: return
        level.playSound(null, x, y, z, sound, SoundSource.HOSTILE, 1.2f, 0.85f + random.nextFloat() * 0.1f)
    }

    override fun onAttackTick(level: ServerLevel, attack: EnemyAttack, tick: Int) {
        val target = target
        when (attack) {
            BOLT -> if (tick == 12 && target != null) bolt(level, target)
            FAN -> if (tick == 16 && target != null) fan(level, target)
            LANCE -> {
                if (tick in 8..28 && tick % 2 == 0) level.sendParticles(ParticleTypes.SNOWFLAKE, staffHead().x, staffHead().y, staffHead().z, 2, 0.1, 0.1, 0.1, 0.01)
                if (tick == 22) glint(level, ParticleTypes.ELECTRIC_SPARK, 2.3, 0.4)
                if (tick == 30 && target != null) lance(level, target)
            }
            BLINK -> if (tick == 8) blink(level, target)
            SUMMON -> {
                if (tick in 6..22 && tick % 2 == 0) ring(level, position().add(0.0, 2.6, 0.0), 1.2, MagicRegistry.ARCANE_MOTE.get(), 8)
                if (tick == 24) repeat(2) { WardLight.sentry(level, this)?.let { sentries.add(it) } }
            }
            STARFALL -> {
                if (tick == 2 && target != null) mark(target)
                if (tick in 0..30 && tick % 3 == 0) level.sendParticles(ParticleTypes.END_ROD, staffHead().x, staffHead().y + 0.4, staffHead().z, 3, 0.15, 0.3, 0.15, 0.05)
                if (tick == 34) starfall(level)
            }
            STRIKE -> if (tick == 7) level.playSound(null, x, y, z, MagicRegistry.SPLINTER_IMPACT.get(), SoundSource.HOSTILE, 0.8f, 0.6f)
            SHOVE -> {
                if (tick == 8) level.playSound(null, x, y, z, FactionRegistry.RENEGADE_SHOVE.get(), SoundSource.HOSTILE, 1.2f, 1f)
                if (tick == 10) {
                    val look = Vec3.directionFromRotation(0f, yRot)
                    level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), x + look.x * 1.2, y + 1.3, z + look.z * 1.2, 20, 0.3, 0.3, 0.3, 0.2)
                }
            }
            CALL -> {
                if (tick in 4..22 && tick % 2 == 0) ring(level, position().add(0.0, 0.2, 0.0), 1.0 + tick * 0.25, MagicRegistry.ARCANE_MOTE.get(), 16)
                if (tick == 24) {
                    ring(level, position().add(0.0, 0.3, 0.0), 6.5, ParticleTypes.END_ROD, 48)
                    level.playSound(null, x, y, z, MagicRegistry.STAR_IMPACT.get(), SoundSource.HOSTILE, 2f, 1.2f)
                    setFlag(PHASE_TWO, true)
                }
            }
            INTRO -> if (tick == 50) level.playSound(null, x, y, z, FactionRegistry.RENEGADE_STONES.get(), SoundSource.HOSTILE, 1.5f, 1f)
        }
    }

    /** Where a spell leaves his staff: up at its head, in his right hand. */
    private fun staffHead(): Vec3 {
        val look = Vec3.directionFromRotation(0f, yRot)
        return position().add(look.x * 0.7 - look.z * 0.35, 2.0, look.z * 0.7 + look.x * 0.35)
    }

    private fun aimAt(target: LivingEntity, from: Vec3): Vec3 = target.position().add(0.0, target.bbHeight * 0.55, 0.0).subtract(from).normalize()

    private fun damage(share: Float): Float = getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() * share

    private fun cast(level: ServerLevel, kind: BoltKind, spell: Spell, from: Vec3, direction: Vec3, share: Float, speed: Double = kind.speed): SpellBolt {
        val bolt = SpellBolt(level, this, kind, spell, from, damage(share))
        bolt.shoot(direction.x, direction.y, direction.z, speed.toFloat(), 0f)
        level.addFreshEntity(bolt)
        return bolt
    }

    private fun bolt(level: ServerLevel, target: LivingEntity) {
        val from = staffHead()
        cast(level, BoltKind.ARC_BOLT, Spell.ARC_BOLT, from, aimAt(target, from), 0.7f, BoltKind.ARC_BOLT.speed * 0.85).seek(target)
    }

    private fun fan(level: ServerLevel, target: LivingEntity) {
        val from = staffHead()
        val aim = aimAt(target, from)
        for (i in -2..2) {
            val a = Math.toRadians(i * 12.0)
            val direction = Vec3(aim.x * cos(a) - aim.z * sin(a), aim.y, aim.x * sin(a) + aim.z * cos(a)).normalize()
            cast(level, BoltKind.SPLINTER, Spell.SPLINTER_HAIL, from, direction, 0.35f)
        }
    }

    private fun lance(level: ServerLevel, target: LivingEntity) {
        val from = staffHead()
        cast(level, BoltKind.RIME_LANCE, Spell.RIME_LANCE, from, aimAt(target, from), 1.1f)
        level.playSound(null, x, y, z, MagicRegistry.RIME_CAST.get(), SoundSource.HOSTILE, 1.4f, 0.8f)
    }

    /** He's gone, and stands somewhere else in his ring: away from [from], facing them. */
    private fun blink(level: ServerLevel, from: LivingEntity?) {
        val centre = if (home == BlockPos.ZERO) blockPosition() else home
        var spot: BlockPos? = null
        repeat(14) {
            if (spot != null) return@repeat
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 3.0 + random.nextDouble() * 5.5
            val x = centre.x + (cos(angle) * distance).toInt()
            val z = centre.z + (sin(angle) * distance).toInt()
            val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            if (!Places.standable(level, ground) || Math.abs(ground.y - centre.y) > 4) return@repeat
            if (from != null && from.distanceToSqr(Vec3.atBottomCenterOf(ground)) < 36.0) return@repeat
            spot = ground
        }
        val to = spot ?: centre
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + 1.2, z, 30, 0.4, 0.9, 0.4, 0.05)
        level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), x, y + 1.2, z, 16, 0.4, 0.9, 0.4, 0.05)
        level.playSound(null, x, y, z, MagicRegistry.FLICKER.get(), SoundSource.HOSTILE, 1.2f, 0.8f)
        teleportTo(to.x + 0.5, to.y.toDouble(), to.z + 0.5)
        if (from != null) {
            yRot = Math.toDegrees(Math.atan2(-(from.x - x), from.z - z)).toFloat()
            yBodyRot = yRot
            yHeadRot = yRot
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + 1.2, z, 30, 0.4, 0.9, 0.4, 0.05)
        level.playSound(null, x, y, z, MagicRegistry.FLICKER.get(), SoundSource.HOSTILE, 1.2f, 1.1f)
        cooldown = 0
    }

    /** Circles on the ground where the stars will fall: one where [target] stands, the rest round them. */
    private fun mark(target: LivingEntity) {
        val level = level() as? ServerLevel ?: return
        val spots = ArrayList<Vec3>()
        spots.add(Vec3(target.x, ground(level, target.x, target.z, target.y), target.z))
        repeat(STARS - 1) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 5.0 + random.nextDouble() * 5.0
            val x = target.x + cos(angle) * distance
            val z = target.z + sin(angle) * distance
            spots.add(Vec3(x, ground(level, x, z, target.y), z))
        }
        marks = spots
    }

    private fun ground(level: ServerLevel, x: Double, z: Double, near: Double): Double {
        val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(x, near, z)).y.toDouble()
        return if (Math.abs(top - near) < 6.0) top + 0.1 else near
    }

    /** The stars come down on the marks. */
    private fun starfall(level: ServerLevel) {
        for (mark in marks) {
            val from = mark.add((random.nextDouble() - 0.5) * 4.0, 18.0, (random.nextDouble() - 0.5) * 4.0)
            val down = mark.subtract(from).normalize()
            cast(level, BoltKind.STAR, Spell.STARBREAK, from, down, 1.0f)
            level.playSound(null, mark.x, mark.y, mark.z, MagicRegistry.STAR_FALL.get(), SoundSource.HOSTILE, 2f, 0.9f + random.nextFloat() * 0.2f)
        }
        val landed = marks
        marks = emptyList()
        // The glow stays on the marks a moment longer, as the stars come
        for (mark in landed) ring(level, mark, STAR_RADIUS, ParticleTypes.END_ROD, 28)
    }

    private fun ring(level: ServerLevel, at: Vec3, radius: Double, particle: net.minecraft.core.particles.ParticleOptions, count: Int) {
        for (i in 0 until count) {
            val a = Math.PI * 2 * i / count
            level.sendParticles(particle, at.x + cos(a) * radius, at.y + 0.1, at.z + sin(a) * radius, 1, 0.0, 0.02, 0.0, 0.0)
        }
    }

    // ---- Damage ----

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount)
        if (dormant) {
            (source.entity as? Player)?.let { awaken(level, it) }
            return false
        }
        // Untouchable while he speaks and while he calls the stars
        if (attack === INTRO || attack === CALL) return false
        // His own spells and sentries never touch him, nor a fall after a blink
        if (source.entity === this || source.entity is WardLight || source.`is`(DamageTypeTags.IS_FALL)) return false
        val hurt = super.hurtServer(level, source, amount)
        if (hurt) recentHits.addLast(tickCount)
        return hurt
    }

    override fun canHit(victim: LivingEntity): Boolean = victim !is WardLight && super.canHit(victim)

    override fun isPushable(): Boolean = !dormant && super.isPushable()

    override fun die(source: DamageSource) {
        if (isRemoved || dead) return
        super.die(source)
        if (!dead) return
        val level = level() as? ServerLevel ?: return
        cue(level, BossCuePayload.DEATH, DEATH_SCENE)
        bossEvent.removeAllPlayers()
        setFlag(FIGHTING, false)
        for (light in sentries) light.discard()
        sentries.clear()
        marks = emptyList()
        spawnAtLocation(level, Relics.roll(level, 4, Rarity.RARE))
        spawnAtLocation(level, ItemStack(ModItems.GUILD_MARK.get(), 18))
    }

    /** He staggers, sinks to his knees and slumps over his staff while he says his last; then the stars take him. */
    override fun tickDeath() {
        deathTime++
        val level = level() as? ServerLevel ?: return
        if (deathTime % 4 == 0) level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), x, y + 1.0, z, 3, 0.4, 0.6, 0.4, 0.02)
        if (deathTime >= DEATH_TICKS && !isRemoved) {
            level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), x, y + 1.2, z, 60, 0.5, 1.0, 0.5, 0.08)
            level.sendParticles(ParticleTypes.END_ROD, x, y + 1.2, z, 30, 0.3, 0.8, 0.3, 0.12)
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

    override fun remove(reason: RemovalReason) {
        super.remove(reason)
        bossEvent.removeAllPlayers()
        for (light in sentries) if (light.isAlive) light.discard()
    }

    override fun getAmbientSound(): SoundEvent? = if (dormant) null else FactionRegistry.RENEGADE_AMBIENT.get()
    override fun getHurtSound(source: DamageSource): SoundEvent = FactionRegistry.RENEGADE_HURT.get()
    override fun getDeathSound(): SoundEvent = FactionRegistry.RENEGADE_DEATH.get()
    override fun getSoundVolume(): Float = 1.3f

    /**
     * Where he waits: a ring of standing stones on the highest ground near where Idris said, built the first time the
     * player comes near, and him in it while they're close.
     */
    class Hunt(player: ServerPlayer) : TrialRun(player) {
        private var boss: Renegade? = null
        private var warned = false

        override fun tick(level: ServerLevel, stack: ItemStack, note: QuestNote, state: ContractState): Boolean {
            if (player.tickCount % 10 != 0) return true
            val destination = state.destination ?: return true
            val dx = player.x - (destination.x + 0.5)
            val dz = player.z - (destination.z + 0.5)
            val flat = sqrt(dx * dx + dz * dz)
            if (state.label != RING) {
                if (flat < BUILD && Places.loaded(level, destination, 40)) {
                    val centre = build(level, destination)
                    Contracts.setState(stack, state.copy(destination = centre, label = RING))
                }
                return true
            }
            val boss = boss?.takeIf { !it.isRemoved }
            if (boss == null) {
                this.boss = null
                if (state.progress >= note.count) return true
                if (flat < WARN && !warned) {
                    warned = true
                    player.sendSystemMessage(Component.translatable("message.guildmark.renegade_near"))
                    level.playSound(null, destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5, FactionRegistry.RENEGADE_STONES.get(), SoundSource.HOSTILE, 2f, 1f)
                }
                if (flat < SPAWN && Places.loaded(level, destination, 12)) this.boss = summon(level, destination)
            } else if (flat > GONE && boss.isAlive && !boss.fighting) {
                boss.discard()
                this.boss = null
                warned = false
            }
            return true
        }

        private fun summon(level: ServerLevel, centre: BlockPos): Renegade? {
            val renegade = FactionRegistry.RENEGADE.get().create(level, EntitySpawnReason.EVENT) ?: return null
            val yaw = Math.toDegrees(Math.atan2(-(player.x - centre.x - 0.5), player.z - centre.z - 0.5)).toFloat()
            renegade.snapTo(centre.x + 0.5, centre.y.toDouble(), centre.z + 0.5, yaw, 0f)
            renegade.yHeadRot = yaw
            renegade.yBodyRot = yaw
            renegade.finalizeSpawn(level, level.getCurrentDifficultyAt(centre), EntitySpawnReason.EVENT, null)
            level.addFreshEntity(renegade)
            return renegade
        }

        override fun stop(level: ServerLevel) {
            // One still on his feet goes; one falling finishes his fall
            boss?.let { if (it.isAlive) it.discard() }
            boss = null
        }
    }

    companion object {
        const val BOSS_KEY = "boss.guildmark.renegade"
        private const val DORMANT = 2
        private const val FIGHTING = 4
        private const val PHASE_TWO = 8
        private const val WAKE_RANGE = 13.0
        private const val FIGHT_RANGE = 40.0
        private const val CUE_RANGE = 48.0
        private const val LEASH = 14.0
        private const val MAX_SENTRIES = 3
        private const val STARS = 4
        private const val STAR_RADIUS = 4.5
        /** How long his introduction keeps those watching out of the fight, and his death (Cutscenes times its scenes to them). */
        const val INTRO_SCENE = 104
        const val DEATH_SCENE = 80
        const val DEATH_TICKS = 70

        /** The contract's label once his ring is built (its destination is then the ring's middle). */
        private const val RING = "ring"
        private const val BUILD = 112.0
        private const val WARN = 48.0
        private const val SPAWN = 32.0
        private const val GONE = 72.0

        // Timings match art/scripts/renegade_anims.py (each spell leaves the staff on its release tick)
        val BOLT = EnemyAttack(1, "bolt", 24, emptyList(), listOf(0..11), minRange = 2.5, maxRange = 24.0, weight = 4, cooldown = 18, turn = 20f)
        val FAN = EnemyAttack(2, "fan", 30, emptyList(), listOf(0..15), minRange = 2.0, maxRange = 14.0, weight = 3, cooldown = 24, turn = 16f)
        val LANCE = EnemyAttack(3, "lance", 44, emptyList(), listOf(0..27), minRange = 5.0, maxRange = 28.0, weight = 2, cooldown = 30, turn = 10f)
        val BLINK = EnemyAttack(4, "blink", 18, emptyList(), emptyList(), weight = 0, cooldown = 6)
        val SUMMON = EnemyAttack(5, "summon", 36, emptyList(), listOf(0..24), maxRange = 30.0, weight = 2, cooldown = 30)
        val STARFALL = EnemyAttack(6, "starfall", 56, emptyList(), listOf(0..30), maxRange = 30.0, weight = 3, cooldown = 34, phase = 2)
        val STRIKE = EnemyAttack(7, "strike", 20, listOf(Blow(9, 1.0f, 2.8, 70f, 22f)), listOf(0..6), maxRange = 2.8, weight = 3, cooldown = 12)
        val SHOVE = EnemyAttack(8, "shove", 24, listOf(Blow(10, 0.35f, 3.3, 60f, 45f, parryable = false, knockback = 1.9f)), listOf(0..8),
            maxRange = 3.0, weight = 3, cooldown = 14)
        /** His introduction: he turns to face whoever came, arms spread as he speaks. */
        val INTRO = EnemyAttack(20, "intro", INTRO_SCENE - 4, emptyList(), emptyList(), weight = 0, cooldown = 10)
        /** The change of phase: he raises his staff to the sky and the burst throws everyone back. */
        val CALL = EnemyAttack(21, "summon", 40, listOf(Blow(24, 0.3f, 7.0, 180f, 0f, parryable = false, knockback = 1.6f)), emptyList(),
            weight = 0, cooldown = 10)
        val ATTACKS = listOf(BOLT, FAN, LANCE, BLINK, SUMMON, STARFALL, STRIKE, SHOVE, INTRO, CALL)

        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 260.0)
            .add(Attributes.ATTACK_DAMAGE, 10.0)
            .add(Attributes.ARMOR, 4.0)
            .add(Attributes.MOVEMENT_SPEED, 0.26)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)
            .add(Attributes.FOLLOW_RANGE, 40.0)
            .add(Attributes.STEP_HEIGHT, 1.0)

        /** Sets up a newly taken hunt: the old observatory lies some way off from the city, in no set direction. */
        fun begin(level: ServerLevel, player: Player, state: ContractState): ContractState {
            val random = level.random
            val from = state.boardPos
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 340.0 + random.nextDouble() * 140.0
            val spot = BlockPos.containing(from.x + cos(angle) * distance, from.y.toDouble(), from.z + sin(angle) * distance)
            return state.copy(destination = spot)
        }

        // ---- The ring of stones ----

        /**
         * Builds the old observatory near [near]: on the highest ground about (where the land is level enough for a
         * ring), a floor of old tiles worn into the turf with a star inlaid at its heart, and ten standing stones round
         * it, some tall, some broken, a few still bridged, crystals growing in the cracks. Returns its middle.
         */
        fun build(level: ServerLevel, near: BlockPos): BlockPos {
            val random = RandomSource.create(near.asLong())
            var best = surface(level, near.x, near.z)
            var bestY = best.y
            for (gx in -4..4) for (gz in -4..4) {
                val at = surface(level, near.x + gx * 6, near.z + gz * 6)
                if (at.y <= bestY || !level.getBlockState(at.below()).fluidState.isEmpty) continue
                if (!even(level, at)) continue
                best = at
                bestY = at.y
            }
            val centre = best
            // Clear the ring of trees and brush
            for (dx in -RADIUS - 2..RADIUS + 2) for (dz in -RADIUS - 2..RADIUS + 2) {
                if (dx * dx + dz * dz > (RADIUS + 2) * (RADIUS + 2)) continue
                val top = surface(level, centre.x + dx, centre.z + dz)
                for (dy in 0..12) {
                    val pos = top.above(dy)
                    val state = level.getBlockState(pos)
                    if (state.isAir) continue
                    if (state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES) || state.canBeReplaced()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                }
            }
            // The floor: tiles worn into the ground, ragged at the edge, a star at its heart
            for (dx in -FLOOR..FLOOR) for (dz in -FLOOR..FLOOR) {
                val d2 = dx * dx + dz * dz
                if (d2 > FLOOR * FLOOR + 1 || d2 > (FLOOR - 1) * (FLOOR - 1) && random.nextInt(2) == 0) continue
                val top = surface(level, centre.x + dx, centre.z + dz)
                val ground = top.below()
                if (!level.getBlockState(ground).fluidState.isEmpty || level.getBlockEntity(ground) != null) continue
                val spoke = dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz)
                val block = when {
                    d2 == 0 -> Blocks.CHISELED_DEEPSLATE
                    d2 <= 2 -> Blocks.POLISHED_DEEPSLATE
                    spoke && d2 <= 16 -> Blocks.DEEPSLATE_TILES
                    random.nextInt(6) == 0 -> Blocks.MOSS_BLOCK
                    random.nextInt(5) == 0 -> Blocks.CRACKED_DEEPSLATE_TILES
                    else -> if (spoke) Blocks.POLISHED_DEEPSLATE else Blocks.DEEPSLATE_BRICKS
                }
                level.setBlock(ground, block.defaultBlockState(), FLAGS)
            }
            // The stones
            val heights = IntArray(STONES)
            val bases = arrayOfNulls<BlockPos>(STONES)
            for (i in 0 until STONES) {
                val angle = Math.PI * 2 * i / STONES
                val x = centre.x + Math.round(cos(angle) * RADIUS).toInt()
                val z = centre.z + Math.round(sin(angle) * RADIUS).toInt()
                val base = surface(level, x, z)
                bases[i] = base
                val broken = random.nextInt(4) == 0
                val height = if (broken) 1 + random.nextInt(2) else 3 + random.nextInt(2)
                heights[i] = height
                for (dy in 0 until height) {
                    val block = when {
                        dy == height - 1 && !broken -> Blocks.CHISELED_DEEPSLATE
                        random.nextInt(4) == 0 -> Blocks.CRACKED_DEEPSLATE_BRICKS
                        else -> Blocks.DEEPSLATE_BRICKS
                    }
                    level.setBlock(base.above(dy), block.defaultBlockState(), FLAGS)
                }
                // Crystals in the cracks of a few, catching the light like stars
                if (random.nextInt(3) == 0) level.setBlock(base.above(height), Blocks.AMETHYST_CLUSTER.defaultBlockState(), FLAGS)
                else if (broken && random.nextInt(2) == 0) level.setBlock(base.above(height), Blocks.COBBLED_DEEPSLATE_SLAB.defaultBlockState(), FLAGS)
            }
            // A few still bridged at the top, as the ring once was all round
            for (i in 0 until STONES step 3) {
                val j = (i + 1) % STONES
                val a = bases[i] ?: continue
                val b = bases[j] ?: continue
                if (heights[i] < 3 || heights[j] < 3) continue
                val top = minOf(a.y + heights[i], b.y + heights[j])
                val steps = maxOf(Math.abs(b.x - a.x), Math.abs(b.z - a.z))
                for (s in 0..steps) {
                    val x = a.x + Math.round((b.x - a.x) * s / steps.toDouble()).toInt()
                    val z = a.z + Math.round((b.z - a.z) * s / steps.toDouble()).toInt()
                    val pos = BlockPos(x, top, z)
                    if (level.getBlockState(pos).isAir) level.setBlock(pos, Blocks.DEEPSLATE_BRICK_SLAB.defaultBlockState(), FLAGS)
                }
            }
            return centre
        }

        /** The first open block over the ground at [x], [z]. */
        private fun surface(level: ServerLevel, x: Int, z: Int): BlockPos =
            level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))

        /** Whether the land round [at] is level enough for the ring. */
        private fun even(level: ServerLevel, at: BlockPos): Boolean {
            var low = at.y
            var high = at.y
            for (i in 0 until 8) {
                val a = Math.PI * i / 4
                val y = surface(level, at.x + (cos(a) * RADIUS).toInt(), at.z + (sin(a) * RADIUS).toInt()).y
                low = minOf(low, y)
                high = maxOf(high, y)
            }
            return high - low <= 5
        }

        private const val RADIUS = 9
        private const val FLOOR = 7
        private const val STONES = 10
        private const val FLAGS = Block.UPDATE_ALL
    }
}
