package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.magic.BoltKind
import io.github.vaspyyy.guildmark.magic.MagicRegistry
import io.github.vaspyyy.guildmark.magic.Spell
import io.github.vaspyyy.guildmark.magic.SpellBolt
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.townsfolk.Places
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * A ward-light: a soft orb of starlight hanging in the air, bobbing and circling a point. The Starwatch's lights over
 * the plaza are a test: only their own player's sorcery puts one out (anything else passes through it with a
 * shimmer). The renegade's are sentries, called up beside him to shoot arc bolts at his foes, which any blow puts out.
 */
class WardLight(type: EntityType<out WardLight>, level: Level) : Mob(type, level) {
    /** The player whose test this light is; null for a sentry. */
    var owner: UUID? = null
    /** Who called it up to fight (the renegade), on the server. */
    var summoner: LivingEntity? = null
    /** The point it circles; a sentry circles its summoner instead. */
    var anchor: Vec3? = null
    private val phase = random.nextFloat() * Mth.TWO_PI
    private val radius = 0.5 + random.nextDouble() * 0.7
    private var shootIn = 30 + random.nextInt(30)
    private var life = 0
    private var warnedAt = -100

    /** A sentry (tinted, and dangerous), as clients see it. */
    val sentry: Boolean get() = entityData.get(DATA_SENTRY)

    init {
        setNoGravity(true)
        noPhysics = true
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_SENTRY, false)
    }

    override fun tick() {
        super.tick()
        val level = level()
        if (level.isClientSide) {
            if (random.nextInt(3) == 0) {
                level.addParticle(MagicRegistry.ARCANE_MOTE.get(), x + (random.nextDouble() - 0.5) * 0.5, y + 0.3 + (random.nextDouble() - 0.5) * 0.5,
                    z + (random.nextDouble() - 0.5) * 0.5, 0.0, -0.01, 0.0)
            }
            if (random.nextInt(10) == 0) level.addParticle(ParticleTypes.END_ROD, x, y + 0.3, z, (random.nextDouble() - 0.5) * 0.02, -0.02, (random.nextDouble() - 0.5) * 0.02)
            return
        }
        val summoner = summoner
        if (summoner != null && (!summoner.isAlive || ++life > SENTRY_LIFE)) {
            fade(level as ServerLevel)
            return
        }
        // Circling its point, rising and falling a little: set where it is each tick, and the clients follow
        val centre = summoner?.position()?.add(cos(phase.toDouble()) * 2.2, 2.6, sin(phase.toDouble()) * 2.2) ?: anchor ?: position().also { anchor = it }
        val t = (tickCount + phase * 40f) * 0.045
        val at = centre.add(cos(t) * radius, sin(t * 1.7) * 0.35, sin(t) * radius)
        setPos(at.x, at.y, at.z)
        deltaMovement = Vec3.ZERO
        if (summoner != null) shoot(level as ServerLevel, summoner)
    }

    /** A sentry looses an arc bolt at its summoner's foe now and then, when it has a clear shot. */
    private fun shoot(level: ServerLevel, summoner: LivingEntity) {
        if (--shootIn > 0) return
        shootIn = 50 + random.nextInt(30)
        val foe = (summoner as? Mob)?.target ?: return
        if (!foe.isAlive || foe.distanceTo(this) > 24f || !hasLineOfSight(foe)) return
        val from = position().add(0.0, 0.3, 0.0)
        val to = foe.position().add(0.0, foe.bbHeight * 0.55, 0.0).subtract(from).normalize()
        val bolt = SpellBolt(level, summoner, BoltKind.ARC_BOLT, Spell.ARC_BOLT, from, SENTRY_DAMAGE)
        bolt.shoot(to.x, to.y, to.z, (BoltKind.ARC_BOLT.speed * 0.6).toFloat(), 0f)
        bolt.seek(foe)
        level.addFreshEntity(bolt)
        level.playSound(null, x, y, z, FactionRegistry.WARD_LIGHT_SHOOT.get(), SoundSource.HOSTILE, 0.8f, 0.9f + random.nextFloat() * 0.2f)
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount)
        val by = source.entity
        val owner = owner
        if (owner == null) {
            // A sentry: any foe's blow or spell puts it out, never its own side's
            if (by == null || by === summoner || by is WardLight || by !is Player && (by as? Mob)?.target !== summoner) return false
            burst(level)
            return true
        }
        if (by is Player && by.uuid == owner && source.`is`(MagicRegistry.SORCERY_DAMAGE)) {
            burst(level)
            Trials.credit(by, "ward_lights")
            return true
        }
        // Anything else passes through it
        level.sendParticles(ParticleTypes.END_ROD, x, y + 0.3, z, 6, 0.15, 0.15, 0.15, 0.03)
        level.playSound(null, x, y, z, FactionRegistry.WARD_LIGHT_PASS.get(), SoundSource.NEUTRAL, 0.7f, 1f + random.nextFloat() * 0.2f)
        if (by is Player && by.uuid == owner && tickCount - warnedAt > 60) {
            warnedAt = tickCount
            by.sendOverlayMessage(Component.translatable("message.guildmark.ward_light_immune"))
        }
        return false
    }

    /** It goes out in a burst of light. */
    private fun burst(level: ServerLevel) {
        level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), x, y + 0.3, z, 24, 0.2, 0.2, 0.2, 0.12)
        level.sendParticles(ParticleTypes.END_ROD, x, y + 0.3, z, 14, 0.1, 0.1, 0.1, 0.18)
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, if (owner == null) 0xFFB46CFF.toInt() else 0xFFA8C8FF.toInt()), x, y + 0.3, z, 1, 0.0, 0.0, 0.0, 0.0)
        level.playSound(null, x, y, z, FactionRegistry.WARD_LIGHT_BREAK.get(), SoundSource.NEUTRAL, 1f, 0.9f + random.nextFloat() * 0.2f)
        discard()
    }

    /** A sentry whose time is up thins away. */
    private fun fade(level: ServerLevel) {
        level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), x, y + 0.3, z, 10, 0.2, 0.2, 0.2, 0.03)
        discard()
    }

    override fun getAmbientSound(): SoundEvent = FactionRegistry.WARD_LIGHT_HUM.get()
    override fun getAmbientSoundInterval(): Int = 70
    override fun getSoundVolume(): Float = 0.5f
    override fun isPushable(): Boolean = false
    override fun removeWhenFarAway(distance: Double): Boolean = false
    override fun canBeLeashed(): Boolean = false
    override fun shouldShowName(): Boolean = false


    companion object {
        private val DATA_SENTRY: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(WardLight::class.java, EntityDataSerializers.BOOLEAN)
        /** Ticks a sentry lasts. */
        private const val SENTRY_LIFE = 440
        private const val SENTRY_DAMAGE = 5f

        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 4.0)
            .add(Attributes.FOLLOW_RANGE, 24.0)

        /** A test light for [player], circling [at]. */
        fun test(level: ServerLevel, player: Player, at: Vec3): WardLight? {
            val light = FactionRegistry.WARD_LIGHT.get().create(level, EntitySpawnReason.EVENT) ?: return null
            light.owner = player.uuid
            light.anchor = at
            light.snapTo(at.x, at.y, at.z, 0f, 0f)
            level.addFreshEntity(light)
            level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), at.x, at.y + 0.3, at.z, 12, 0.3, 0.3, 0.3, 0.02)
            return light
        }

        /** A sentry called up beside [summoner]. */
        fun sentry(level: ServerLevel, summoner: LivingEntity): WardLight? {
            val light = FactionRegistry.WARD_LIGHT.get().create(level, EntitySpawnReason.EVENT) ?: return null
            light.summoner = summoner
            light.entityData.set(DATA_SENTRY, true)
            val at = summoner.position().add(cos(light.phase.toDouble()) * 2.2, 2.6, sin(light.phase.toDouble()) * 2.2)
            light.snapTo(at.x, at.y, at.z, 0f, 0f)
            level.addFreshEntity(light)
            level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), at.x, at.y + 0.3, at.z, 18, 0.3, 0.3, 0.3, 0.05)
            level.playSound(null, at.x, at.y, at.z, FactionRegistry.WARD_LIGHT_SPAWN.get(), SoundSource.HOSTILE, 1f, 1f)
            return light
        }
    }
}

/**
 * The Lantern Test: the lights left to break drift over the square before the library while the player is near,
 * each over open air a few blocks up; they go when the player wanders off and come back when they return.
 */
class WardLights(player: ServerPlayer) : TrialRun(player) {
    private val lights = ArrayList<WardLight>()

    override fun tick(level: ServerLevel, stack: ItemStack, note: QuestNote, state: ContractState): Boolean {
        if (player.tickCount % 10 != 0) return true
        val square = state.destination ?: return true
        lights.removeIf { !it.isAlive || it.isRemoved }
        val left = note.count - state.progress
        if (left <= 0) return false
        val distance = player.distanceToSqr(Vec3.atCenterOf(square))
        if (distance > HIDE * HIDE) {
            stop(level)
            return true
        }
        if (distance > SHOW * SHOW || lights.size >= left || !Places.loaded(level, square, 16)) return true
        repeat(left - lights.size) {
            spot(level, square)?.let { at -> WardLight.test(level, player, at)?.let { lights.add(it) } }
        }
        return true
    }

    /** Somewhere open over the square, a few blocks up. */
    private fun spot(level: ServerLevel, square: BlockPos): Vec3? {
        val random = level.random
        repeat(12) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 3.0 + random.nextDouble() * 9.0
            val x = square.x + 0.5 + cos(angle) * distance
            val z = square.z + 0.5 + sin(angle) * distance
            val base = BlockPos.containing(x, square.y.toDouble(), z)
            var ground = base
            // Up from the square's height to the first air, then a few blocks higher, all of it open
            for (dy in -2..6) {
                val at = base.above(dy)
                if (level.getBlockState(at).isAir && !level.getBlockState(at.below()).isAir) {
                    ground = at
                    break
                }
            }
            val lift = 2 + random.nextInt(3)
            if ((0..lift + 1).all { level.getBlockState(ground.above(it)).isAir }) return Vec3(x, ground.y + lift + 0.2, z)
        }
        return null
    }

    override fun stop(level: ServerLevel) {
        for (light in lights) if (light.isAlive) light.discard()
        lights.clear()
    }

    private companion object {
        const val SHOW = 40.0
        const val HIDE = 72.0
    }
}
