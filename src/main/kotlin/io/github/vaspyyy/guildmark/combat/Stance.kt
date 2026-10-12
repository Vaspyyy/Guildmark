package io.github.vaspyyy.guildmark.combat

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.network.StaggerPayload
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.Tags
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Collections
import java.util.WeakHashMap

/**
 * Creature stance: every hit wears it down, and when it breaks the creature staggers and is open to a
 * riposte. A parry staggers the same way. While staggered a creature can't move or hurt anyone.
 */
object Stance {
    /** Stance broken by hits: open to a riposte. */
    const val BROKEN = 0
    /** Knocked off balance by a parry: open to a riposte. */
    const val PARRIED = 1
    /** Held in a riposte. */
    const val HELD = 2
    /** Held in a backstab. */
    const val HELD_BACK = 3

    const val BREAK_TICKS = 50
    const val PARRY_TICKS = 40
    /** Stance recovers fully after this many ticks without a hit. */
    private const val RECOVER_AFTER = 80

    private val SLOWED = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "stagger")
    private val staggered: MutableSet<LivingEntity> = Collections.newSetFromMap(WeakHashMap())

    private fun state(entity: LivingEntity): StanceState = entity.getData(ModAttachments.STANCE)

    fun maxStance(entity: LivingEntity): Float =
        (entity as? CombatMob)?.maxStance ?: (10f + entity.maxHealth * 1.2f).coerceAtMost(300f)

    /** Bosses from other games' worth of size (the Wither, the dragon) and players never stagger this way. */
    fun immune(entity: LivingEntity): Boolean =
        entity is Player || (entity !is CombatMob && entity.typeHolder().`is`(Tags.EntityTypes.BOSSES))

    fun isStaggered(entity: LivingEntity): Boolean =
        entity.hasData(ModAttachments.STANCE) && state(entity).staggerUntil > entity.level().gameTime

    /** Staggered by a broken stance or a parry, and not already being finished off. */
    fun isOpen(entity: LivingEntity): Boolean {
        if (!isStaggered(entity)) return false
        val s = state(entity)
        return (s.kind == BROKEN || s.kind == PARRIED) && s.heldBy < 0
    }

    /** Wears down [target]'s stance by [amount]; breaks it when it runs out. */
    fun damage(target: LivingEntity, amount: Float) {
        if (amount <= 0f || immune(target) || !target.isAlive) return
        val level = target.level() as? ServerLevel ?: return
        val s = state(target)
        val now = level.gameTime
        if (s.staggerUntil > now) return
        if (now - s.lastHit > RECOVER_AFTER) s.damage = 0f
        s.lastHit = now
        s.damage += amount
        if (s.damage >= maxStance(target)) {
            s.damage = 0f
            stagger(target, BREAK_TICKS, BROKEN)
            level.playSound(null, target.x, target.y, target.z, ModSounds.STANCE_BREAK.get(), SoundSource.HOSTILE, 1f, 0.9f + level.random.nextFloat() * 0.2f)
            level.sendParticles(ParticleTypes.CRIT, target.x, target.getY(0.7), target.z, 14, 0.3, 0.3, 0.3, 0.25)
        }
    }

    /** Stagger [target] for [ticks]: it stops in place, can't hurt anyone, and players see it reel. */
    fun stagger(target: LivingEntity, ticks: Int, kind: Int) {
        if (immune(target)) return
        val level = target.level() as? ServerLevel ?: return
        val s = state(target)
        s.staggerUntil = level.gameTime + ticks
        s.kind = kind
        if (kind != HELD && kind != HELD_BACK) s.heldBy = -1
        target.getAttribute(Attributes.MOVEMENT_SPEED)?.let {
            if (!it.hasModifier(SLOWED)) it.addTransientModifier(AttributeModifier(SLOWED, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        }
        freeze(target)
        (target as? CombatMob)?.onStaggered(ticks, kind)
        staggered.add(target)
        PacketDistributor.sendToPlayersTrackingEntity(target, StaggerPayload(target.id, ticks, kind))
    }

    /** [player] grabs [target] for a riposte or backstab: it's held in place in front of them for the whole move. */
    fun hold(target: LivingEntity, player: Player, move: Move) {
        val kind = if (move == Move.BACKSTAB) HELD_BACK else HELD
        stagger(target, move.length + 4, kind)
        state(target).heldBy = player.id
        // Draw the victim in front of the blade, facing the player for a riposte and away for a backstab
        val look = Vec3.directionFromRotation(0f, player.yRot)
        val distance = 1.1 + target.bbWidth / 2
        val x = player.x + look.x * distance
        val z = player.z + look.z * distance
        val moved = target.boundingBox.move(x - target.x, 0.0, z - target.z)
        if (target.level().noCollision(target, moved)) target.teleportTo(x, target.y, z)
        val yaw = if (kind == HELD_BACK) player.yRot else player.yRot + 180f
        target.setYRot(yaw)
        target.setYHeadRot(yaw)
        target.setYBodyRot(yaw)
        target.setDeltaMovement(Vec3.ZERO)
    }

    /** Ends a stagger early, for example once a riposte is over. */
    fun release(target: LivingEntity) {
        if (!target.hasData(ModAttachments.STANCE)) return
        val s = state(target)
        s.staggerUntil = 0L
        s.heldBy = -1
        unfreeze(target)
        staggered.remove(target)
        (target as? CombatMob)?.onStaggered(0, s.kind)
        PacketDistributor.sendToPlayersTrackingEntity(target, StaggerPayload(target.id, 0, s.kind))
    }

    fun tick(level: ServerLevel) {
        if (staggered.isEmpty()) return
        val now = level.gameTime
        val iterator = staggered.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            if (entity.level() !== level) continue
            val s = state(entity)
            if (entity.isRemoved || !entity.isAlive || s.staggerUntil <= now) {
                unfreeze(entity)
                s.heldBy = -1
                iterator.remove()
                continue
            }
            freeze(entity)
        }
    }

    private fun freeze(entity: LivingEntity) {
        if (entity is Mob) entity.navigation.stop()
        if (entity.isUsingItem) entity.stopUsingItem()
        if (entity is Creeper) entity.setSwellDir(-1)
        val motion = entity.deltaMovement
        entity.setDeltaMovement(motion.x * 0.3, motion.y.coerceAtMost(0.0), motion.z * 0.3)
    }

    private fun unfreeze(entity: Entity) {
        (entity as? LivingEntity)?.getAttribute(Attributes.MOVEMENT_SPEED)?.removeModifier(SLOWED)
    }
}

/**
 * A creature built for the new combat: it says how sturdy its stance is, reacts to staggers with its own
 * animation, and tells parries and backstabs when they may land.
 */
interface CombatMob {
    val maxStance: Float

    /** [ticks] of stagger began (0: it ended early). Interrupt the current attack and play the reaction. */
    fun onStaggered(ticks: Int, kind: Int)

    /** Whether the attack under way can be parried right now. */
    fun canBeParried(): Boolean = true

    /** Whether a player behind it may backstab it right now (not mid-attack, not a boss). */
    fun canBeBackstabbed(): Boolean = true

    /** Poise damage the blow just dealt does to a player. */
    fun poiseDamage(): Float

    /** Multiplies the stamina a player's shield loses blocking this creature. */
    fun guardDamage(): Float = 1f
}
