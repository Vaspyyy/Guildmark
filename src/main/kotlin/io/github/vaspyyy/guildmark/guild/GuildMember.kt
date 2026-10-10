package io.github.vaspyyy.guildmark.guild

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

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, MeleeAttackGoal(this, 0.75, true))
        goalSelector.addGoal(3, FollowLeaderGoal(this))
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
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        leader = input.read("guild_leader", UUIDUtil.CODEC).orElse(null)
        guild = input.getIntOr("guild_id", -1)
        following = input.getBooleanOr("following", true)
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
        fun createAttributes(): AttributeSupplier.Builder = Villager.createAttributes()
            .add(Attributes.MAX_HEALTH, 30.0)
            .add(Attributes.ATTACK_DAMAGE, 3.0)
            .add(Attributes.ARMOR, 4.0)
            .add(Attributes.FOLLOW_RANGE, 24.0)
    }
}
