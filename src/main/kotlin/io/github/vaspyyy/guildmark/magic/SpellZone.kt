package io.github.vaspyyy.guildmark.magic

import io.github.vaspyyy.guildmark.registry.ModParticles
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ground a spell lies on for a while. Undertow's vortex drags foes in toward its heart and wears at them;
 * Hallowed Ground burns the foes that stand in its ring and mends the friends. Both draw their ring for
 * everyone to see, and give out after a few seconds. Never saved: they're over long before a world closes.
 */
class SpellZone(type: EntityType<out SpellZone>, level: Level) : Entity(type, level) {
    private var caster: LivingEntity? = null
    private var spell: Spell = Spell.UNDERTOW
    /** Each pulse's blow (and, on hallowed ground, its mending). */
    private var power = 0f

    init {
        isNoGravity = true
        noPhysics = true
    }

    val kind: Int get() = entityData.get(DATA_KIND)
    val radius: Float get() = entityData.get(DATA_RADIUS)
    private val lifetime: Int get() = entityData.get(DATA_LIFE)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(DATA_KIND, VORTEX)
        builder.define(DATA_RADIUS, 4f)
        builder.define(DATA_LIFE, 60)
    }

    override fun tick() {
        super.tick()
        val level = level()
        if (level.isClientSide) {
            draw(level)
            return
        }
        val server = level as ServerLevel
        val owner = caster
        if (owner == null || !owner.isAlive || tickCount > lifetime || owner.level() !== level) {
            discard()
            return
        }
        when (kind) {
            VORTEX -> vortex(server, owner)
            HALLOWED -> hallowed(server, owner)
        }
    }

    /** Foes are dragged toward the middle every tick, and hurt a little twice a second. */
    private fun vortex(level: ServerLevel, owner: LivingEntity) {
        val r = radius.toDouble()
        val fade = (1f - tickCount / lifetime.toFloat()).coerceIn(0.3f, 1f)
        for (foe in level.getEntitiesOfClass(LivingEntity::class.java, AABB(position(), position()).inflate(r, 2.5, r))) {
            if (!Magic.isFoe(owner, foe)) continue
            val pull = position().subtract(foe.position())
            val distance = pull.horizontalDistance()
            if (distance > r || distance < 0.3) continue
            val strength = PULL * fade * (1.0 - distance / r * 0.5) * (1.0 - foe.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)).coerceAtLeast(0.2)
            foe.deltaMovement = foe.deltaMovement.add(pull.x / distance * strength, 0.0, pull.z / distance * strength)
            foe.needsSync = true
            if (tickCount % 10 == 0) Magic.hurt(owner, foe, power, spell, this)
        }
    }

    /** Foes in the ring burn; the caster and their friends in it mend. */
    private fun hallowed(level: ServerLevel, owner: LivingEntity) {
        if (tickCount % 10 != 0) return
        val r = radius.toDouble()
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java, AABB(position(), position()).inflate(r, 2.0, r))) {
            if (entity.position().subtract(position()).horizontalDistance() > r) continue
            if (Magic.isFoe(owner, entity)) {
                if (Magic.hurt(owner, entity, power, spell, this)) entity.igniteForSeconds(2f)
            } else if (tickCount % 20 == 0 && Magic.isFriend(owner, entity)) {
                Magic.heal(owner, entity, power * 0.5f)
            }
        }
    }

    /** The ring and what rises from it, on the watching clients. */
    private fun draw(level: Level) {
        val r = radius.toDouble()
        val spin = tickCount * (if (kind == VORTEX) 0.25 else 0.04)
        val count = if (kind == VORTEX) 3 else 2
        for (i in 0 until count) {
            val angle = spin + random.nextDouble() * Math.PI * 2
            val at = if (kind == VORTEX) r * (0.3 + random.nextDouble() * 0.7) else r
            val px = x + cos(angle) * at
            val pz = z + sin(angle) * at
            if (kind == VORTEX) {
                // Motes swirl in toward the heart
                val inward = Vec3(x - px, 0.0, z - pz).normalize().scale(0.12)
                val around = Vec3(-(z - pz), 0.0, x - px).normalize().scale(0.18)
                level.addParticle(MagicRegistry.ARCANE_MOTE.get(), px, y + 0.15 + random.nextDouble() * 0.6, pz, inward.x + around.x, 0.01, inward.z + around.z)
                if (random.nextInt(2) == 0) level.addParticle(ParticleTypes.REVERSE_PORTAL, px, y + 0.3, pz, 0.0, 0.05, 0.0)
            } else {
                level.addParticle(ModParticles.WARD_EMBER.get(), px, y + 0.1, pz, 0.0, 0.02 + random.nextDouble() * 0.02, 0.0)
                if (random.nextInt(3) == 0) {
                    val inner = random.nextDouble() * r
                    val a = random.nextDouble() * Math.PI * 2
                    level.addParticle(ParticleTypes.END_ROD, x + cos(a) * inner, y + 0.1, z + sin(a) * inner, 0.0, 0.03, 0.0)
                }
            }
        }
        if (kind == VORTEX && random.nextInt(3) == 0) level.addParticle(ParticleTypes.PORTAL, x, y + 0.5, z, 0.0, 0.0, 0.0)
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false

    override fun isPickable(): Boolean = false

    override fun isPushable(): Boolean = false

    override fun getPistonPushReaction(): PushReaction = PushReaction.IGNORE_ENTITY

    override fun isIgnoringBlockTriggers(): Boolean = true

    override fun canUsePortal(allowPassengers: Boolean): Boolean = false

    override fun teleport(transition: TeleportTransition): Entity? = null

    override fun readAdditionalSaveData(input: ValueInput) {}

    override fun addAdditionalSaveData(output: ValueOutput) {}

    companion object {
        const val VORTEX = 0
        const val HALLOWED = 1

        /** How hard the vortex drags, in blocks a tick. */
        private const val PULL = 0.09

        private val DATA_KIND: EntityDataAccessor<Int> = SynchedEntityData.defineId(SpellZone::class.java, EntityDataSerializers.INT)
        private val DATA_RADIUS: EntityDataAccessor<Float> = SynchedEntityData.defineId(SpellZone::class.java, EntityDataSerializers.FLOAT)
        private val DATA_LIFE: EntityDataAccessor<Int> = SynchedEntityData.defineId(SpellZone::class.java, EntityDataSerializers.INT)

        /** Lays [kind] down at [at] for [caster], each pulse worth [power]. */
        fun open(level: ServerLevel, caster: LivingEntity, kind: Int, spell: Spell, at: Vec3, power: Float) {
            val zone = SpellZone(MagicRegistry.SPELL_ZONE.get(), level)
            zone.caster = caster
            zone.spell = spell
            zone.power = power
            zone.entityData.set(DATA_KIND, kind)
            zone.entityData.set(DATA_RADIUS, if (kind == VORTEX) 5f else 4f)
            zone.entityData.set(DATA_LIFE, if (kind == VORTEX) 60 else 160)
            zone.setPos(at.x, if (kind == HALLOWED) at.y else at.y - 0.4, at.z)
            level.addFreshEntity(zone)
            val sound = if (kind == VORTEX) MagicRegistry.UNDERTOW_OPEN.get() else MagicRegistry.HALLOWED_GROUND.get()
            level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, 1f, 1f)
            if (kind == VORTEX) level.playSound(null, at.x, at.y, at.z, MagicRegistry.UNDERTOW_LOOP.get(), SoundSource.PLAYERS, 0.8f, 1f)
        }
    }
}
