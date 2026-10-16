package io.github.vaspyyy.guildmark.magic

import io.github.vaspyyy.guildmark.client.magic.MagicClient
import io.github.vaspyyy.guildmark.companion.CompanionEntity
import io.github.vaspyyy.guildmark.companion.CompanionFighting
import io.github.vaspyyy.guildmark.registry.ModParticles
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/** What a spell in flight is: how it flies and what it does where it lands. */
enum class BoltKind(
    val id: String,
    /** Blocks a tick. */
    val speed: Double,
    val gravity: Double,
    /** Ticks before it gives out. */
    val life: Int,
    /** How much of the way toward its target it turns each tick (0 flies straight). */
    val turn: Double,
    /** How big it's drawn. */
    val scale: Float,
) {
    ARC_BOLT("arc_bolt", 1.5, 0.0, 30, 0.05, 0.7f),
    SPLINTER("splinter", 1.3, 0.025, 18, 0.0, 0.5f),
    RIME_LANCE("rime_lance", 2.0, 0.0, 24, 0.0, 0.9f),
    UNDERTOW("undertow", 0.55, 0.0, 44, 0.0, 0.9f),
    STAR("star", 1.7, 0.0, 30, 0.0, 1.8f),
    WAYFLAME("wayflame", 0.7, 0.0, 90, 0.14, 0.7f);

    companion object {
        fun byOrdinal(i: Int): BoltKind = entries.getOrElse(i) { ARC_BOLT }
    }
}

/**
 * A sorcery or prayer in flight: an arc bolt, a splinter, a rime lance, Undertow's orb, a falling star or
 * a seeking ember. It flies the same on both sides (a synced target to bend toward), draws its own trail,
 * and leaves what it does to [Magic] when it lands: a rime lance runs through foes, an orb opens into a
 * vortex, a star crashes down on everything around.
 */
class SpellBolt : ThrowableItemProjectile {
    /** Its blow, worked out from the caster's power when it was cast. Server side. */
    private var damage = 0f
    private var spell: Spell = Spell.ARC_BOLT
    /** A charged cast's share of its full charge. */
    private var charged = 0f
    private val pierced = IntOpenHashSet()
    private var spent = false

    constructor(type: EntityType<out SpellBolt>, level: Level) : super(type, level)

    constructor(level: Level, owner: LivingEntity, kind: BoltKind, spell: Spell, at: Vec3, damage: Float, charged: Float = 0f) :
        super(MagicRegistry.SPELL_BOLT.get(), at.x, at.y, at.z, level, stackFor(kind)) {
        setOwner(owner)
        entityData.set(DATA_KIND, kind.ordinal)
        this.spell = spell
        this.damage = damage
        this.charged = charged
    }

    val kind: BoltKind get() = BoltKind.byOrdinal(entityData.get(DATA_KIND))

    /** Bends toward [target] in flight, if it seeks at all. */
    fun seek(target: LivingEntity?) {
        entityData.set(DATA_TARGET, target?.id ?: -1)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_KIND, 0)
        builder.define(DATA_TARGET, -1)
    }

    override fun getDefaultItem(): Item = MagicRegistry.SPELL_BOLT_ITEM.get()

    override fun getDefaultGravity(): Double = kind.gravity

    override fun tick() {
        super.tick()
        if (isRemoved) return
        val level = level()
        val kind = kind
        if (kind.turn > 0.0) {
            val target = level.getEntity(entityData.get(DATA_TARGET)) as? LivingEntity
            val speed = deltaMovement.length().coerceAtLeast(0.05)
            if (target != null && target.isAlive) {
                val want = target.position().add(0.0, target.bbHeight * 0.55, 0.0).subtract(position()).normalize().scale(speed)
                deltaMovement = deltaMovement.add(want.subtract(deltaMovement).scale(kind.turn)).normalize().scale(speed)
            }
        }
        if (level.isClientSide) {
            trail(level, kind)
            return
        }
        if (tickCount > kind.life) {
            land(level as ServerLevel, position(), null)
            discard()
        }
    }

    private fun trail(level: Level, kind: BoltKind) {
        val back = deltaMovement.scale(-0.5)
        fun puff(particle: ParticleOptions, spread: Double, speed: Double = 0.0) = level.addParticle(
            particle,
            x + back.x * random.nextDouble() + (random.nextDouble() - 0.5) * spread,
            y + 0.2 + back.y * random.nextDouble() + (random.nextDouble() - 0.5) * spread,
            z + back.z * random.nextDouble() + (random.nextDouble() - 0.5) * spread,
            (random.nextDouble() - 0.5) * speed, (random.nextDouble() - 0.5) * speed, (random.nextDouble() - 0.5) * speed,
        )
        when (kind) {
            BoltKind.ARC_BOLT -> {
                puff(MagicRegistry.ARCANE_MOTE.get(), 0.2, 0.02)
                if (random.nextInt(3) == 0) puff(ParticleTypes.ELECTRIC_SPARK, 0.25, 0.1)
            }
            BoltKind.SPLINTER -> if (random.nextInt(2) == 0) puff(MagicRegistry.ARCANE_MOTE.get(), 0.1, 0.01)
            BoltKind.RIME_LANCE -> {
                puff(ParticleTypes.SNOWFLAKE, 0.2, 0.03)
                puff(MagicRegistry.ARCANE_MOTE.get(), 0.15, 0.01)
            }
            BoltKind.UNDERTOW -> {
                puff(ParticleTypes.REVERSE_PORTAL, 0.4, 0.05)
                if (random.nextInt(2) == 0) puff(MagicRegistry.ARCANE_MOTE.get(), 0.3, 0.02)
            }
            BoltKind.STAR -> {
                puff(ParticleTypes.END_ROD, 0.6, 0.04)
                puff(MagicRegistry.ARCANE_MOTE.get(), 0.8, 0.02)
                if (random.nextInt(2) == 0) puff(ParticleTypes.FIREWORK, 0.5, 0.05)
            }
            BoltKind.WAYFLAME -> {
                puff(ModParticles.WARD_EMBER.get(), 0.15, 0.02)
                if (random.nextInt(2) == 0) puff(ParticleTypes.SMALL_FLAME, 0.1, 0.01)
                if (random.nextInt(4) == 0) puff(ParticleTypes.SMOKE, 0.1, 0.01)
            }
        }
    }

    /** A star's crash shakes the ground under those near it (only the client hears of an entity going). */
    override fun onClientRemoval() {
        super.onClientRemoval()
        if (kind == BoltKind.STAR) MagicClient.onStarLanded(position())
    }

    // A spell passes by its caster, the caster's own creatures, and other spells
    override fun canHitEntity(target: Entity): Boolean {
        // getOwner(): inside a projectile, plain owner is the EntityReference field rather than the caster
        val caster = getOwner()
        if (!super.canHitEntity(target) || target is SpellBolt || target === caster || pierced.contains(target.id)) return false
        if (target is OwnableEntity && target.owner === caster) return false
        // A companion's bolts pass by everyone on their side
        if (caster is CompanionEntity && target is LivingEntity && !CompanionFighting.isFoe(caster, target)) return false
        return true
    }

    override fun onHitEntity(hit: EntityHitResult) {
        val level = level() as? ServerLevel ?: return
        val struck = hit.entity as? LivingEntity ?: return
        val caster = getOwner() as? LivingEntity
        if (kind == BoltKind.RIME_LANCE) {
            // It runs through a few foes, freezing each, before it shatters
            pierced.add(struck.id)
            if (Magic.hurt(caster, struck, damage, spell, this)) {
                struck.ticksFrozen = struck.ticksFrozen.coerceAtLeast(struck.ticksRequiredToFreeze + 60 + (80 * charged).toInt())
                struck.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 60 + (60 * charged).toInt(), 2), caster ?: this)
            }
            level.sendParticles(ParticleTypes.SNOWFLAKE, struck.x, struck.getY(0.6), struck.z, 14, 0.3, 0.4, 0.3, 0.05)
            play(level, MagicRegistry.RIME_IMPACT.get(), 0.8f)
            damage *= 0.8f
            if (pierced.size >= PIERCES) {
                land(level, hit.location, null)
                discard()
            }
            return
        }
        land(level, hit.location, struck)
        discard()
    }

    override fun onHitBlock(hit: BlockHitResult) {
        super.onHitBlock(hit)
        val level = level() as? ServerLevel ?: return
        // A little off the face it struck, so what opens there isn't inside the wall
        land(level, hit.location.add(Vec3.atLowerCornerOf(hit.direction.unitVec3i).scale(0.3)), null)
        discard()
    }

    /** It lands at [at], on [struck] if it hit a creature. Once only. */
    private fun land(level: ServerLevel, at: Vec3, struck: LivingEntity?) {
        if (spent) return
        spent = true
        val caster = getOwner() as? LivingEntity
        when (kind) {
            BoltKind.ARC_BOLT -> {
                struck?.let { Magic.hurt(caster, it, damage, spell, this) }
                level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), at.x, at.y, at.z, 14, 0.2, 0.2, 0.2, 0.08)
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 8, 0.15, 0.15, 0.15, 0.25)
                play(level, MagicRegistry.ARC_BOLT_IMPACT.get(), 0.9f)
            }
            BoltKind.SPLINTER -> {
                struck?.let { Magic.hurt(caster, it, damage, spell, this) }
                level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), at.x, at.y, at.z, 5, 0.1, 0.1, 0.1, 0.05)
                play(level, MagicRegistry.SPLINTER_IMPACT.get(), 0.6f)
            }
            BoltKind.RIME_LANCE -> {
                level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 20, 0.3, 0.3, 0.3, 0.08)
                level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), at.x, at.y, at.z, 8, 0.2, 0.2, 0.2, 0.05)
                play(level, MagicRegistry.RIME_IMPACT.get(), 1f)
            }
            BoltKind.UNDERTOW -> {
                struck?.let { Magic.hurt(caster, it, damage * 2f, spell, this) }
                if (caster != null) SpellZone.open(level, caster, SpellZone.VORTEX, spell, at, damage)
            }
            BoltKind.STAR -> if (caster != null) Magic.starfall(level, caster, spell, at, damage, charged)
            BoltKind.WAYFLAME -> {
                // It bursts into flame on whatever is close by
                if (caster != null) {
                    for (foe in level.getEntitiesOfClass(LivingEntity::class.java, AABB(at, at).inflate(1.6))) {
                        if (foe !== struck && !Magic.isFoe(caster, foe)) continue
                        if (Magic.hurt(caster, foe, if (foe === struck) damage else damage * 0.5f, spell, this)) foe.igniteForSeconds(5f)
                    }
                }
                level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 18, 0.35, 0.35, 0.35, 0.05)
                level.sendParticles(ModParticles.WARD_EMBER.get(), at.x, at.y, at.z, 16, 0.4, 0.4, 0.4, 0.04)
                level.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.02)
                play(level, MagicRegistry.WAYFLAME_IMPACT.get(), 1f)
            }
        }
    }

    private fun play(level: ServerLevel, sound: SoundEvent, volume: Float) {
        level.playSound(null, x, y, z, sound, SoundSource.PLAYERS, volume, 0.9f + random.nextFloat() * 0.2f)
    }

    companion object {
        /** Foes a rime lance runs through before it shatters. */
        private const val PIERCES = 3

        private val DATA_KIND: EntityDataAccessor<Int> = SynchedEntityData.defineId(SpellBolt::class.java, EntityDataSerializers.INT)
        private val DATA_TARGET: EntityDataAccessor<Int> = SynchedEntityData.defineId(SpellBolt::class.java, EntityDataSerializers.INT)

        /** The sprite [kind] is drawn with, picked by items/spell_bolt.json. */
        fun stackFor(kind: BoltKind): ItemStack = ItemStack(MagicRegistry.SPELL_BOLT_ITEM.get()).also {
            it.set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(kind.id), listOf()))
        }
    }
}
