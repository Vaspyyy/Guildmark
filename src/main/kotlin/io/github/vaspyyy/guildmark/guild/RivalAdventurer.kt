package io.github.vaspyyy.guildmark.guild

import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.BossEvent
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.item.component.SwingAnimation

/**
 * An adventurer of a rival guild (see RivalGuild for who they are and RivalModel for how they look).
 * They hang around village boards, take notes off boards their guild holds, fight monsters, and fight
 * back if attacked. A captain is the guild's champion in a fight for a village: tougher, armed with a
 * relic, and with a few tricks of their own.
 */
class RivalAdventurer(type: EntityType<out RivalAdventurer>, level: Level) : PathfinderMob(type, level) {
    var guildKey: String
        get() = entityData.get(GUILD)
        set(value) = entityData.set(GUILD, value)

    var captain: Boolean
        get() = entityData.get(CAPTAIN)
        set(value) = entityData.set(CAPTAIN, value)

    val rival: RivalGuild get() = RivalGuild.byKey(guildKey) ?: RivalGuild.IRON_HOUNDS

    /** The board of the village this adventurer is at. */
    var anchor: BlockPos = BlockPos.ZERO
    /** Game time this adventurer moves on, or -1 to stay (captains leave when their fight is over). */
    var leaveAt: Long = -1
    /** For a captain: the player guild it is fighting for the village. */
    var foe: Int = -1
    /** For a captain: true if it defends its guild's village against [foe]; false if it came to take [foe]'s village. */
    var defending: Boolean = false
    private var tookNote = false
    private var bashTimer = 80
    private var rallied = false
    private var healed = false
    private var lonelyTicks = 0

    private val bossEvent = ServerBossEvent(Mth.createInsecureUUID(random), Component.empty(), BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.NOTCHED_10)

    init {
        setPersistenceRequired()
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(GUILD, RivalGuild.IRON_HOUNDS.key)
        builder.define(CAPTAIN, false)
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, MeleeAttackGoal(this, 1.0, true))
        goalSelector.addGoal(4, MoveTowardsRestrictionGoal(this, 0.6))
        goalSelector.addGoal(5, WaterAvoidingRandomStrollGoal(this, 0.5))
        goalSelector.addGoal(6, LookAtPlayerGoal(this, Player::class.java, 8.0f))
        goalSelector.addGoal(7, RandomLookAroundGoal(this))
        targetSelector.addGoal(1, HurtByTargetGoal(this, RivalAdventurer::class.java))
        targetSelector.addGoal(3, NearestAttackableTargetGoal(this, Monster::class.java, true) { target, _ -> target !is Creeper })
    }

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        if (captain) captainTick(level)
        else if (!tookNote && tickCount % 100 == 0) {
            if (target == null && navigation.isDone && tickCount % 400 == 0) navigation.moveTo(anchor.x + 0.5, anchor.y.toDouble(), anchor.z + 0.5, 0.6)
            tryTakeNote(level)
        }
        if (leaveAt >= 0 && level.gameTime >= leaveAt && target == null) leave(level)
    }

    /** At a board their guild holds, an adventurer takes the first note they reach. */
    private fun tryTakeNote(level: ServerLevel) {
        if (Holdings.get(level).at(anchor)?.guild != rival.id) return
        for (pos in BlockPos.betweenClosed(blockPosition().offset(-3, -2, -3), blockPosition().offset(3, 2, 3))) {
            val cell = level.getBlockEntity(pos) as? QuestBoardBlockEntity ?: continue
            val note = cell.note ?: continue
            tookNote = true
            // Not every rival bothers; boards they hold still keep most of their notes
            if (random.nextInt(5) >= 2) return
            cell.updateNote(null)
            swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT)
            playSound(SoundEvents.BOOK_PAGE_TURN, 1.0f, 1.0f)
            val message = Component.translatable("message.guildmark.rival_took_note", displayName, rival.displayName, note.title())
            for (player in level.getEntitiesOfClass(Player::class.java, AABB(blockPosition()).inflate(24.0))) player.sendSystemMessage(message)
            return
        }
    }

    private fun captainTick(level: ServerLevel) {
        bossEvent.progress = health / maxHealth
        val enemy = target
        if (enemy != null) {
            lonelyTicks = 0
            if (--bashTimer <= 0 && distanceTo(enemy) < 4.0f) {
                bashTimer = 100 + random.nextInt(60)
                swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT)
                val push = Vec3(enemy.x - x, 0.0, enemy.z - z).normalize().scale(1.4)
                enemy.push(push.x, 0.45, push.z)
                enemy.needsSync = true
                enemy.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 60, 1))
                enemy.hurtServer(level, damageSources().mobAttack(this), 4.0f)
                playSound(SoundEvents.SHIELD_BLOCK.value(), 1.2f, 0.7f)
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, enemy.x, enemy.y + 1.0, enemy.z, 1, 0.0, 0.0, 0.0, 0.0)
            }
            if (!rallied && health < maxHealth * 0.5f) {
                rallied = true
                shout(level, "rally")
                repeat(2) { Rivals.spawnAdventurer(level, rival, anchor, blockPosition(), 1200)?.let { it.target = enemy } }
            }
            if (!healed && health < maxHealth * 0.3f) {
                healed = true
                shout(level, "heal")
                heal(30f)
                addEffect(MobEffectInstance(MobEffects.REGENERATION, 200, 1))
                playSound(SoundEvents.GENERIC_DRINK.value(), 1.0f, 1.0f)
                level.sendParticles(ParticleTypes.HEART, x, y + 2.0, z, 6, 0.4, 0.3, 0.4, 0.0)
            }
        } else {
            val foePlayer = level.getEntitiesOfClass(Player::class.java, AABB(blockPosition()).inflate(32.0)) { !it.isSpectator && !it.isCreative && Guilds.get(level).ledBy(it.uuid)?.id == foe }.firstOrNull()
            if (foePlayer != null) target = foePlayer
            else if (++lonelyTicks > 1200) leave(level)
            // Wandered off chasing someone: walk back to the bell
            else if (navigation.isDone && !blockPosition().closerThan(home, 24.0)) navigation.moveTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5, 0.8)
        }
    }

    /** A captain calls out (to everyone near). */
    fun shout(level: ServerLevel, line: String) {
        val message = Component.translatable("gui.guildmark.character.says", displayName, Component.translatable("rival.guildmark.captain.$line"))
        for (player in level.getEntitiesOfClass(Player::class.java, AABB(blockPosition()).inflate(32.0))) player.sendSystemMessage(message)
    }

    private fun leave(level: ServerLevel) {
        level.sendParticles(ParticleTypes.POOF, x, y + 1.0, z, 12, 0.3, 0.6, 0.3, 0.02)
        discard()
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        val level = level()
        if (level is ServerLevel && target == null) {
            val line = if (captain) "rival.guildmark.captain.greet" else "rival.guildmark.${rival.key}.line${random.nextInt(LINES)}"
            player.sendSystemMessage(Component.translatable("gui.guildmark.character.says", displayName, Component.translatable(line, VillageNames.of(anchor))))
            playSound(SoundEvents.VILLAGER_AMBIENT, 0.6f, 0.8f + random.nextFloat() * 0.3f)
        }
        return InteractionResult.SUCCESS
    }

    /** For a captain: the bell it guards, so it can find its way back after a chase. */
    var home: BlockPos = BlockPos.ZERO

    override fun die(source: DamageSource) {
        super.die(source)
        val level = level()
        // Another mod can cancel the death; only a real one counts
        if (level is ServerLevel && captain && dead) Rivals.onCaptainDefeated(level, this, source.entity ?: killCredit)
    }

    /** Captains only exist while their fight is on: they aren't saved, and come back when the leader does. */
    override fun shouldBeSaved(): Boolean = !captain && super.shouldBeSaved()

    override fun startSeenByPlayer(player: ServerPlayer) {
        super.startSeenByPlayer(player)
        if (captain) {
            bossEvent.name = displayName
            bossEvent.color = when (rival) {
                RivalGuild.IRON_HOUNDS -> BossEvent.BossBarColor.WHITE
                RivalGuild.SCARLET_LEDGER -> BossEvent.BossBarColor.RED
                RivalGuild.GILDED_STAG -> BossEvent.BossBarColor.YELLOW
                RivalGuild.ASHEN_CROWS -> BossEvent.BossBarColor.PURPLE
            }
            bossEvent.addPlayer(player)
        }
    }

    override fun stopSeenByPlayer(player: ServerPlayer) {
        super.stopSeenByPlayer(player)
        bossEvent.removePlayer(player)
    }

    override fun removeWhenFarAway(distance: Double): Boolean = false

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putString("rival", guildKey)
        output.putBoolean("captain", captain)
        output.store("anchor", BlockPos.CODEC, anchor)
        output.putLong("leave_at", leaveAt)
        output.putInt("foe", foe)
        output.putBoolean("defending", defending)
        output.putBoolean("took_note", tookNote)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        guildKey = input.getStringOr("rival", RivalGuild.IRON_HOUNDS.key)
        captain = input.getBooleanOr("captain", false)
        anchor = input.read("anchor", BlockPos.CODEC).orElse(BlockPos.ZERO)
        leaveAt = input.getLongOr("leave_at", -1)
        foe = input.getIntOr("foe", -1)
        defending = input.getBooleanOr("defending", false)
        tookNote = input.getBooleanOr("took_note", false)
    }

    companion object {
        const val LINES = 4
        private val GUILD: EntityDataAccessor<String> = SynchedEntityData.defineId(RivalAdventurer::class.java, EntityDataSerializers.STRING)
        private val CAPTAIN: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(RivalAdventurer::class.java, EntityDataSerializers.BOOLEAN)

        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 40.0)
            .add(Attributes.ATTACK_DAMAGE, 5.0)
            .add(Attributes.ARMOR, 4.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.FOLLOW_RANGE, 32.0)
    }
}
