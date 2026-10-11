package io.github.vaspyyy.guildmark.guild

import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.ai.goal.target.TargetGoal
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.EnumSet
import java.util.UUID

/**
 * A villager sworn into a player's guild. It keeps its villager looks (and trade, and clothes) but swaps
 * the villager brain for a fighter's: it follows its leader, attacks whatever hurts them or what they
 * fight, and goes after nearby monsters. Its leader can tell it to hold position or follow again.
 */
class GuildMember(type: EntityType<out Villager>, level: Level) : Villager(type, level) {
    var leader: UUID? = null
    var guild: Int = -1
    var following: Boolean = true
    /** Where a member holding position stands guard; it wanders back here after a fight. */
    var post: BlockPos? = null
    /** Errands survived (see Errands): each makes the member tougher and harder-hitting. */
    var veteran: Int = 0
        set(value) {
            field = value
            getAttribute(Attributes.MAX_HEALTH)?.let { health ->
                health.removeModifier(VETERAN_HEALTH)
                if (value > 0) health.addPermanentModifier(AttributeModifier(VETERAN_HEALTH, 4.0 * value, AttributeModifier.Operation.ADD_VALUE))
            }
            getAttribute(Attributes.ATTACK_DAMAGE)?.let { attack ->
                attack.removeModifier(VETERAN_ATTACK)
                if (value > 0) attack.addPermanentModifier(AttributeModifier(VETERAN_ATTACK, 1.0 * value, AttributeModifier.Operation.ADD_VALUE))
            }
        }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, MeleeAttackGoal(this, 0.75, true))
        goalSelector.addGoal(3, FollowLeaderGoal(this))
        goalSelector.addGoal(4, GuardPostGoal(this))
        goalSelector.addGoal(7, LookAtPlayerGoal(this, Player::class.java, 8.0f))
        goalSelector.addGoal(8, RandomLookAroundGoal(this))
        targetSelector.addGoal(1, HurtByTargetGoal(this, Player::class.java, GuildMember::class.java))
        targetSelector.addGoal(2, DefendLeaderGoal(this))
        targetSelector.addGoal(3, NearestAttackableTargetGoal(this, Monster::class.java, true) { target, _ -> target !is Creeper })
    }

    /** No villager brain: no jobs, beds, gossip runs or panicking. The goals above do the thinking. */
    override fun customServerAiStep(level: ServerLevel) {}

    override fun removeWhenFarAway(distance: Double): Boolean = false

    fun leaderPlayer(): Player? = leader?.let { level().getPlayerByUUID(it) }

    /** The leader toggles follow and hold; anyone else just gets a nod. */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        if (level().isClientSide()) return InteractionResult.SUCCESS
        if (player.uuid != leader) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.member_not_yours", name))
            return InteractionResult.SUCCESS
        }
        following = !following
        post = if (following) null else blockPosition()
        if (!following) navigation.stop()
        player.sendOverlayMessage(Component.translatable(if (following) "message.guildmark.member_follow" else "message.guildmark.member_hold", name))
        playSound(SoundEvents.VILLAGER_YES, 1.0f, 1.0f)
        return InteractionResult.SUCCESS
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.storeNullable("guild_leader", UUIDUtil.CODEC, leader)
        output.putInt("guild_id", guild)
        output.putBoolean("following", following)
        output.storeNullable("post", BlockPos.CODEC, post)
        output.putInt("veteran", veteran)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        leader = input.read("guild_leader", UUIDUtil.CODEC).orElse(null)
        guild = input.getIntOr("guild_id", -1)
        following = input.getBooleanOr("following", true)
        post = input.read("post", BlockPos.CODEC).orElse(null)
        veteran = input.getIntOr("veteran", 0)
    }

    /** Keep up with the leader, and catch up by teleport if left far behind. */
    private class FollowLeaderGoal(private val member: GuildMember) : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            val leader = member.leaderPlayer() ?: return false
            return member.following && member.target == null && !leader.isSpectator && member.distanceTo(leader) > 6.0f
        }

        override fun canContinueToUse(): Boolean = canUse() && member.distanceTo(member.leaderPlayer()!!) > 3.0f

        override fun tick() {
            val leader = member.leaderPlayer() ?: return
            member.lookControl.setLookAt(leader, 10.0f, member.maxHeadXRot.toFloat())
            if (member.distanceTo(leader) > 28.0f && leader.onGround()) {
                member.teleportTo(leader.x, leader.y, leader.z)
                member.navigation.stop()
            } else {
                member.navigation.moveTo(leader, 0.7)
            }
        }

        override fun stop() {
            member.navigation.stop()
        }
    }

    /** A member on guard walks back to its post once nothing needs fighting. */
    private class GuardPostGoal(private val member: GuildMember) : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            val post = member.post ?: return false
            return !member.following && member.target == null && !post.closerToCenterThan(member.position(), 6.0)
        }

        override fun canContinueToUse(): Boolean {
            val post = member.post ?: return false
            return canUse() && !post.closerToCenterThan(member.position(), 2.5)
        }

        override fun tick() {
            val post = member.post ?: return
            if (member.navigation.isDone) member.navigation.moveTo(post.x + 0.5, post.y.toDouble(), post.z + 0.5, 0.7)
        }

        override fun stop() {
            member.navigation.stop()
        }
    }

    /** Go for whatever just hurt the leader, or whatever the leader is fighting (never another player). */
    private class DefendLeaderGoal(private val member: GuildMember) : TargetGoal(member, false) {
        private var picked: LivingEntity? = null
        private var lastSeen = 0

        override fun canUse(): Boolean {
            val leader = member.leaderPlayer() ?: return false
            val attacker = leader.lastHurtByMob
            val fought = leader.lastHurtMob
            val candidate = when {
                attacker != null && leader.lastHurtByMobTimestamp != lastSeen && attacker !is Player && attacker !is GuildMember -> attacker
                fought is Mob && fought !is GuildMember && fought.isAlive -> fought
                else -> null
            } ?: return false
            if (candidate.distanceTo(member) > 24.0f) return false
            picked = candidate
            return true
        }

        override fun start() {
            member.target = picked
            member.leaderPlayer()?.let { lastSeen = it.lastHurtByMobTimestamp }
            super.start()
        }
    }

    companion object {
        private val VETERAN_HEALTH = Identifier.fromNamespaceAndPath("guildmark", "veteran_health")
        private val VETERAN_ATTACK = Identifier.fromNamespaceAndPath("guildmark", "veteran_attack")

        fun createAttributes(): AttributeSupplier.Builder = Villager.createAttributes()
            .add(Attributes.MAX_HEALTH, 30.0)
            .add(Attributes.ATTACK_DAMAGE, 3.0)
            .add(Attributes.ARMOR, 4.0)
            .add(Attributes.FOLLOW_RANGE, 24.0)
    }
}
