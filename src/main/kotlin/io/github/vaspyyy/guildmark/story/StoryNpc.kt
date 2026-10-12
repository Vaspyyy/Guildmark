package io.github.vaspyyy.guildmark.story

import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.EnumSet

/**
 * One of the story's named characters, with their own look (see StoryNpcModel). They wander near home,
 * can't be hurt, never despawn, and talk to whoever uses them. A few (Maren at a vault) only stay a while.
 */
class StoryNpc(type: EntityType<out StoryNpc>, level: Level) : PathfinderMob(type, level) {
    var character: String
        get() = entityData.get(CHARACTER)
        set(value) = entityData.set(CHARACTER, value)

    /** Game time this character leaves the world, or -1 to stay. */
    var leaveAt: Long = -1

    /** Who this character is talking with, and the game time they stop waiting for them to say something. */
    private var listener: Player? = null
    private var listenUntil = 0L

    init {
        isPermanentlyInvulnerable = true
        setPersistenceRequired()
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(CHARACTER, "")
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, ListenGoal())
        goalSelector.addGoal(2, LookAtPlayerGoal(this, Player::class.java, 8.0f, 0.6f))
        goalSelector.addGoal(3, WaterAvoidingRandomStrollGoal(this, 0.45, 0.004f))
        goalSelector.addGoal(4, RandomLookAroundGoal(this))
    }

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        if (leaveAt >= 0 && level.gameTime >= leaveAt) Characters.fade(level, this)
    }

    /** Stops and turns to [player] for a conversation. */
    fun listen(player: Player) {
        listener = player
        listenUntil = level().gameTime + LISTEN_TICKS
        navigation.stop()
    }

    /** The conversation with [player] is over. */
    fun stopListening(player: Player) {
        if (listener === player) listener = null
    }

    private fun listening(): Boolean {
        val player = listener ?: return false
        if (!player.isAlive || player.level() !== level() || level().gameTime > listenUntil || distanceToSqr(player) > 144.0) {
            listener = null
            return false
        }
        return true
    }

    /** Stands still and faces whoever they're talking with. */
    private inner class ListenGoal : Goal() {
        init {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK))
        }

        override fun canUse(): Boolean = listening()
        override fun start() = navigation.stop()
        override fun requiresUpdateEveryTick(): Boolean = true

        override fun tick() {
            listener?.let { lookControl.setLookAt(it, 30f, 30f) }
        }
    }

    override fun removeWhenFarAway(distance: Double): Boolean = false
    override fun isPushable(): Boolean = false

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        val level = level()
        if (level is ServerLevel && player is ServerPlayer) Characters.talk(level, player, this)
        return InteractionResult.SUCCESS
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putString("character", character)
        output.putLong("leave_at", leaveAt)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        character = input.getStringOr("character", "")
        leaveAt = input.getLongOr("leave_at", -1)
    }

    companion object {
        private val CHARACTER: EntityDataAccessor<String> = SynchedEntityData.defineId(StoryNpc::class.java, EntityDataSerializers.STRING)
        /** The longest a character waits on someone who opened a conversation and walked off without closing it. */
        private const val LISTEN_TICKS = 1200L

        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.5)
    }
}
