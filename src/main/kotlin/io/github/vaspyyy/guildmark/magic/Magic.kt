package io.github.vaspyyy.guildmark.magic

import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.progression.Stat
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.registry.ModParticles
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.EntityTypeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The two schools of magic. A spell is cast like any combat move (see combat/Combat): the client starts
 * the cast, the server checks it ([problem]) and, at the cast's release, makes the spell happen
 * ([release]). How strong it is comes from the catalyst, the school's stat and the tree ([power]); what it
 * does to each creature it reaches comes from [hurt] and [heal]. Everything about magic lives in this package
 * and client/magic; the mod's main class only calls [register].
 */
object Magic {
    fun register(modBus: IEventBus) {
        MagicRegistry.ITEMS.register(modBus)
        MagicRegistry.ENTITIES.register(modBus)
        MagicRegistry.SOUNDS.register(modBus)
        MagicRegistry.PARTICLES.register(modBus)
        MagicRegistry.EFFECTS.register(modBus)
        MagicRegistry.COMPONENTS.register(modBus)
        MagicRegistry.ATTACHMENTS.register(modBus)
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToServer(AttunePayload.TYPE, AttunePayload.STREAM_CODEC, AttunePayload::handle)
                .playToServer(QuickCastPayload.TYPE, QuickCastPayload.STREAM_CODEC, QuickCastPayload::handle)
        }
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey != ModCreativeTabs.MAIN.key) return@addListener
            MagicRegistry.TAB.forEach { event.accept(it.get()) }
            for (spell in Spell.entries) {
                val scroll = if (spell.school == School.SORCERY) MagicRegistry.SORCERY_SCROLL.get() else MagicRegistry.PRAYER_SCROLL.get()
                event.accept(scroll.of(spell))
            }
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { MagicCommand.register(it.dispatcher) }
    }

    // ---- Whether a spell can be cast ----

    /** Why [player] can't cast [spell] right now (a message key), or null when they can. Both sides ask. */
    fun problem(player: Player, spell: Spell): String? = when {
        !Spellbook.get(player).attuned(spell) -> "message.guildmark.spell.unattuned"
        CatalystItem.handFor(player, spell.school) == null -> "message.guildmark.spell.no_catalyst.${spell.school.id}"
        Talents.sheet(player).stat(spell.school.stat) < spell.need -> "message.guildmark.spell.needs"
        else -> null
    }

    /** How fast casts play: quicker with the tree's casting nodes. */
    fun tempo(player: Player): Float = (1f + Talents[player, Bonus.CAST_SPEED].toFloat()).coerceIn(0.6f, 1.5f)

    /**
     * How strong [player]'s [spell] is: the catalyst's power, up to twice that as the school's stat climbs
     * to its greatest, and more with the school's tree nodes.
     */
    fun power(player: Player, spell: Spell, catalyst: CatalystItem): Float {
        val stat = Stat.effective(Talents.sheet(player).stat(spell.school.stat)) / Stat.FULL
        return (catalyst.power * (1.0 + stat) * max(0.1, 1.0 + Talents[player, spell.school.bonus])).toFloat()
    }

    /** The sound of [spell] leaving the catalyst. */
    fun castSound(spell: Spell): SoundEvent = when (spell) {
        Spell.ARC_BOLT -> MagicRegistry.ARC_BOLT_CAST.get()
        Spell.SPLINTER_HAIL -> MagicRegistry.SPLINTER_CAST.get()
        Spell.RIME_LANCE -> MagicRegistry.RIME_CAST.get()
        Spell.UNDERTOW -> MagicRegistry.ARC_BOLT_CAST.get()
        Spell.FLICKER -> MagicRegistry.FLICKER.get()
        Spell.STARBREAK -> MagicRegistry.STAR_CALL.get()
        Spell.HEARTHLIGHT -> MagicRegistry.HEARTHLIGHT.get()
        Spell.FLARE -> MagicRegistry.FLARE.get()
        Spell.VIGIL -> MagicRegistry.VIGIL_SOUND.get()
        Spell.WAYFLAME -> MagicRegistry.WAYFLAME_CAST.get()
        Spell.HALLOWED_GROUND -> MagicRegistry.HALLOWED_GROUND.get()
        Spell.DAWNBURST -> MagicRegistry.DAWNBURST.get()
    }

    /** The sound of a cast gathering while its button is held. */
    fun chargeSound(school: School): SoundEvent =
        if (school == School.SORCERY) MagicRegistry.ARCANE_CHARGE.get() else MagicRegistry.PRAYER_CHARGE.get()

    // ---- Casting ----

    /**
     * [spell] goes off, at its cast's release: [charged] is the share of a full charge it was held for. The
     * caster's own client already played its cast sound when [predicted]; everyone else hears it from here.
     */
    fun release(player: ServerPlayer, spell: Spell, charged: Float, predicted: Boolean) {
        val level = player.level() as ServerLevel
        val catalyst = CatalystItem.held(player, spell.school)
        if (catalyst == null) {
            // Put away mid-cast: the spell gutters out
            fizzle(level, player)
            return
        }
        val power = power(player, spell, catalyst)
        level.playSound(if (predicted) player else null, player.x, player.y, player.z, castSound(spell), SoundSource.PLAYERS, 1f, 0.95f + level.random.nextFloat() * 0.1f)
        val from = launchPoint(player, spell)
        val aim = aimPoint(player)
        val look = aim.subtract(from).normalize()
        val grow = 1f + 0.6f * charged
        when (spell) {
            Spell.ARC_BOLT -> shoot(level, player, BoltKind.ARC_BOLT, spell, from, look, 6f * power).seek(aimTarget(player, 24.0, 18f))
            Spell.SPLINTER_HAIL -> for (i in -2..2) {
                shoot(level, player, BoltKind.SPLINTER, spell, from, look.yRot(Math.toRadians(i * 9.0).toFloat()).add(0.0, 0.04, 0.0), 3f * power)
            }
            Spell.RIME_LANCE -> shoot(level, player, BoltKind.RIME_LANCE, spell, from, look, 10f * grow * power, charged)
            Spell.UNDERTOW -> shoot(level, player, BoltKind.UNDERTOW, spell, from, look, 2f * power)
            Spell.FLICKER -> blink(level, player)
            Spell.STARBREAK -> callStar(level, player, spell, 16f * grow * power, charged)
            Spell.HEARTHLIGHT -> hearthlight(level, player, 6f * power)
            Spell.FLARE -> flare(level, player, spell, 6f * power)
            Spell.VIGIL -> vigil(level, player, power)
            Spell.WAYFLAME -> shoot(level, player, BoltKind.WAYFLAME, spell, from, look, 8f * power).seek(aimTarget(player, 28.0, 40f))
            Spell.HALLOWED_GROUND -> SpellZone.open(level, player, SpellZone.HALLOWED, spell, groundAhead(level, player), 1f * power)
            Spell.DAWNBURST -> dawnburst(level, player, spell, 14f * grow * power)
        }
    }

    /** What follows a spell's release while its cast plays on: Flicker's caster arriving where they blinked to. */
    fun afterRelease(player: ServerPlayer, spell: Spell, since: Float): Boolean {
        if (spell != Spell.FLICKER) return true
        if (since < BLINK_TICKS) return false
        val level = player.level() as ServerLevel
        level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), player.x, player.getY(0.5), player.z, 20, 0.25, 0.5, 0.25, 0.04)
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.x, player.getY(0.5), player.z, 12, 0.2, 0.4, 0.2, 0.05)
        return true
    }

    /** Without the action combat a cast is quick: it goes off at once, and the catalyst rests a moment after. */
    fun quickCast(player: ServerPlayer, spell: Spell) {
        if (Combat.enabled() || !player.isAlive || player.isSpectator) return
        if (problem(player, spell) != null) return
        val hand = CatalystItem.handFor(player, spell.school) ?: return
        val stack = player.getItemInHand(hand)
        if (player.cooldowns.isOnCooldown(stack)) return
        player.cooldowns.addCooldown(stack, quickCooldown(spell))
        release(player, spell, if (spell.chargeable) 0.5f else 0f, predicted = false)
    }

    /** Ticks a catalyst rests after a quick cast of [spell]. */
    fun quickCooldown(spell: Spell): Int = spell.move.length + (spell.move.focus * 2f).toInt()

    fun fizzle(level: ServerLevel, player: Player) {
        level.playSound(null, player.x, player.y, player.z, MagicRegistry.FIZZLE.get(), SoundSource.PLAYERS, 0.7f, 1f)
        level.sendParticles(ParticleTypes.SMOKE, player.x, player.getY(0.7), player.z, 6, 0.2, 0.2, 0.2, 0.01)
    }

    // ---- The spells ----

    private fun shoot(level: ServerLevel, player: ServerPlayer, kind: BoltKind, spell: Spell, from: Vec3, direction: Vec3, damage: Float, charged: Float = 0f): SpellBolt {
        val bolt = SpellBolt(level, player, kind, spell, from, damage, charged)
        bolt.shoot(direction.x, direction.y, direction.z, kind.speed.toFloat(), 0f)
        level.addFreshEntity(bolt)
        return bolt
    }

    /** Flicker: the client carries its own player a few blocks on (see client/magic); here, the shimmer left behind. */
    private fun blink(level: ServerLevel, player: ServerPlayer) {
        level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), player.x, player.getY(0.5), player.z, 24, 0.3, 0.6, 0.3, 0.05)
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.x, player.getY(0.5), player.z, 16, 0.3, 0.6, 0.3, 0.08)
    }

    /** Starbreak: a star falls on the foe or the ground the caster looks at. */
    private fun callStar(level: ServerLevel, player: ServerPlayer, spell: Spell, damage: Float, charged: Float) {
        val target = aimTarget(player, STAR_RANGE, 8f)
        val point = target?.position() ?: run {
            val hit = player.pick(STAR_RANGE, 0f, false)
            if (hit.type == HitResult.Type.MISS) player.position().add(player.lookAngle.multiply(1.0, 0.0, 1.0).normalize().scale(12.0)) else hit.location
        }
        val ground = groundBelow(level, point)
        val look = player.lookAngle.multiply(1.0, 0.0, 1.0).normalize()
        val from = ground.add(-look.x * 5.0, STAR_HEIGHT, -look.z * 5.0)
        val star = SpellBolt(level, player, BoltKind.STAR, spell, from, damage, charged)
        val down = ground.subtract(from).normalize()
        star.shoot(down.x, down.y, down.z, BoltKind.STAR.speed.toFloat(), 0f)
        level.addFreshEntity(star)
        level.playSound(null, ground.x, ground.y, ground.z, MagicRegistry.STAR_FALL.get(), SoundSource.PLAYERS, 2.5f, 1f)
        level.sendParticles(ParticleTypes.END_ROD, ground.x, ground.y + 0.2, ground.z, 12, 1.2, 0.05, 1.2, 0.01)
    }

    /** The star lands at [at]: a crash that hits every foe around, hardest at the heart. */
    fun starfall(level: ServerLevel, caster: LivingEntity, spell: Spell, at: Vec3, damage: Float, charged: Float) {
        val radius = STAR_RADIUS + charged
        for (foe in foesNear(level, caster, at, radius)) {
            val distance = foe.position().distanceTo(at)
            val falloff = if (distance < 2.0) 1f else (1f - ((distance - 2.0) / (radius - 2.0)).toFloat() * 0.5f).coerceAtLeast(0.4f)
            if (hurt(caster, foe, damage * falloff, spell, caster)) throwBack(foe, at, 0.9 + 0.5 * charged)
        }
        level.levelEvent(LevelEvent.PARTICLES_SMASH_ATTACK, BlockPos.containing(at).below(), 750)
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 2, 0.6, 0.2, 0.6, 0.0)
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.5, at.z, 40, 0.3, 0.3, 0.3, 0.35)
        level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), at.x, at.y + 0.5, at.z, 50, radius * 0.3, 0.4, radius * 0.3, 0.1)
        level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y + 0.5, at.z, 20, 0.4, 0.4, 0.4, 0.25)
        ring(level, ParticleTypes.END_ROD, at.add(0.0, 0.2, 0.0), 32, 0.45)
        level.playSound(null, at.x, at.y, at.z, MagicRegistry.STAR_IMPACT.get(), SoundSource.PLAYERS, 3f, 1f)
    }

    /** Hearthlight: the caster and those beside them mend. */
    private fun hearthlight(level: ServerLevel, player: ServerPlayer, amount: Float) {
        for (friend in level.getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(HEAL_RADIUS))) {
            if (friend !== player && (!isFriend(player, friend) || friend.distanceTo(player) > HEAL_RADIUS)) continue
            heal(player, friend, if (friend === player) amount else amount * 0.75f)
            level.sendParticles(ModParticles.WARD_EMBER.get(), friend.x, friend.getY(0.4), friend.z, 18, 0.35, 0.5, 0.35, 0.02)
            level.sendParticles(ParticleTypes.END_ROD, friend.x, friend.getY(0.6), friend.z, 6, 0.3, 0.5, 0.3, 0.02)
        }
        ring(level, ModParticles.WARD_EMBER.get(), player.position().add(0.0, 0.15, 0.0), 24, 0.12)
    }

    /** Flare: a gout of holy fire, a short cone ahead. */
    private fun flare(level: ServerLevel, player: ServerPlayer, spell: Spell, damage: Float) {
        val look = player.lookAngle
        for (foe in level.getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(FLARE_REACH))) {
            if (!isFoe(player, foe)) continue
            val to = foe.position().add(0.0, foe.bbHeight * 0.5, 0.0).subtract(player.eyePosition)
            if (to.length() > FLARE_REACH + foe.bbWidth / 2) continue
            if (Math.toDegrees(acos(to.normalize().dot(look).coerceIn(-1.0, 1.0))) > FLARE_ARC) continue
            if (!player.hasLineOfSight(foe)) continue
            if (hurt(player, foe, damage, spell, player)) foe.igniteForSeconds(4f)
        }
        val from = launchPoint(player, spell)
        for (i in 0 until 26) {
            val spread = look.add((level.random.nextDouble() - 0.5) * 0.45, (level.random.nextDouble() - 0.5) * 0.3, (level.random.nextDouble() - 0.5) * 0.45).normalize()
            val speed = 0.25 + level.random.nextDouble() * 0.25
            level.sendParticles(if (i % 3 == 0) ModParticles.WARD_EMBER.get() else ParticleTypes.FLAME, from.x, from.y, from.z, 0, spread.x, spread.y, spread.z, speed)
        }
        level.sendParticles(ParticleTypes.SMOKE, from.x, from.y, from.z, 5, 0.1, 0.1, 0.1, 0.02)
    }

    /** Vigil: a vow that hardens the caster for a while, longer the stronger they are. */
    private fun vigil(level: ServerLevel, player: ServerPlayer, power: Float) {
        val ticks = (VIGIL_TICKS * (0.75f + 0.25f * power)).toInt()
        player.addEffect(MobEffectInstance(MagicRegistry.VIGIL, ticks, 0, false, true, true))
        level.sendParticles(ModParticles.WARD_EMBER.get(), player.x, player.getY(0.5), player.z, 30, 0.4, 0.7, 0.4, 0.03)
        ring(level, ParticleTypes.END_ROD, player.position().add(0.0, 1.0, 0.0), 20, 0.06)
    }

    /** Dawnburst: dawn light bursts out all around, throwing foes back and searing the dead. */
    private fun dawnburst(level: ServerLevel, player: ServerPlayer, spell: Spell, damage: Float) {
        val at = player.position()
        for (foe in foesNear(level, player, at, DAWN_RADIUS)) {
            val distance = foe.distanceTo(player)
            val falloff = (1f - distance / DAWN_RADIUS.toFloat() * 0.4f).coerceIn(0.6f, 1f)
            if (hurt(player, foe, damage * falloff, spell, player)) throwBack(foe, at, 1.3)
        }
        ring(level, ParticleTypes.END_ROD, at.add(0.0, 1.0, 0.0), 40, 0.55)
        ring(level, ModParticles.WARD_EMBER.get(), at.add(0.0, 0.3, 0.0), 40, 0.4)
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1.2, at.z, 30, 0.2, 0.4, 0.2, 0.3)
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.6, at.z, 20, 0.6, 0.3, 0.6, 0.08)
    }

    // ---- What spells do to creatures ----

    /**
     * [caster]'s [spell] hurts [target] for [amount] (its power already counted): prayers sear the dead half
     * again as hard, and the caster's nodes against the dead and bosses count as they do for weapons.
     */
    fun hurt(caster: LivingEntity?, target: LivingEntity, amount: Float, spell: Spell, direct: Entity?): Boolean {
        val level = target.level() as? ServerLevel ?: return false
        if (!target.isAlive || target === caster) return false
        var damage = amount
        val undead = target.typeHolder().`is`(EntityTypeTags.UNDEAD)
        if (caster is Player) {
            var bonus = 0.0
            if (undead) bonus += Talents[caster, Bonus.UNDEAD_DAMAGE]
            if (Talents.isBoss(target)) bonus += Talents[caster, Bonus.BOSS_DAMAGE]
            damage *= max(0.1, 1.0 + bonus).toFloat()
        }
        if (spell.school == School.PRAYER && undead) damage *= UNDEAD_FACTOR
        val types = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
        val key = if (spell.school == School.SORCERY) MagicRegistry.SORCERY_DAMAGE else MagicRegistry.PRAYER_DAMAGE
        val type = types.get(key).orElse(null) ?: types.getOrThrow(DamageTypes.MAGIC)
        val source = if (direct != null && direct !== caster) DamageSource(type, direct, caster) else DamageSource(type, caster)
        // Each splinter and pulse lands, however close together
        target.damageCooldownTime = 0
        return target.hurtServer(level, source, damage)
    }

    /** [caster] mends [target] by [amount]; prayers grow with the healing they do. */
    fun heal(caster: LivingEntity, target: LivingEntity, amount: Float) {
        val missing = target.maxHealth - target.health
        if (missing <= 0f) return
        target.heal(amount)
        if (caster is ServerPlayer) Skills.gain(caster, School.PRAYER.skill, min(amount, missing) * 0.8)
    }

    /** Monsters, and anything out for [caster]'s blood or that they're fighting. Never a player: spells don't catch friends. */
    fun isFoe(caster: LivingEntity, entity: LivingEntity): Boolean {
        if (entity === caster || !entity.isAlive || entity.isSpectator || entity is Player) return false
        if (entity is OwnableEntity && entity.owner === caster) return false
        if (entity is Enemy) return true
        if (entity is Mob && entity.target === caster) return true
        return caster.lastHurtMob === entity && caster.tickCount - caster.lastHurtMobTimestamp < FOE_MEMORY
    }

    /** Players and the caster's own creatures, whom prayers mend. */
    fun isFriend(caster: LivingEntity, entity: LivingEntity): Boolean {
        if (!entity.isAlive || entity.isSpectator) return false
        return entity === caster || entity is Player || entity is OwnableEntity && entity.owner === caster
    }

    private fun foesNear(level: ServerLevel, caster: LivingEntity, at: Vec3, radius: Double): List<LivingEntity> =
        level.getEntitiesOfClass(LivingEntity::class.java, AABB(at, at).inflate(radius, radius * 0.6, radius)) {
            isFoe(caster, it) && it.position().distanceTo(at) <= radius + it.bbWidth / 2
        }

    private fun throwBack(foe: LivingEntity, from: Vec3, strength: Double) {
        val away = foe.position().subtract(from).multiply(1.0, 0.0, 1.0)
        if (away.lengthSqr() < 1e-4) return
        val resist = 1.0 - foe.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)
        if (resist <= 0.0) return
        val push = away.normalize().scale(strength * resist)
        foe.deltaMovement = foe.deltaMovement.add(push.x, 0.25 * strength * resist, push.z)
        foe.needsSync = true
    }

    // ---- Aiming ----

    /** Where a spell leaves from: the head of the catalyst, about, in the hand that holds it. */
    private fun launchPoint(player: Player, spell: Spell): Vec3 {
        val hand = CatalystItem.handFor(player, spell.school) ?: InteractionHand.MAIN_HAND
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val yaw = Math.toRadians(player.yRot.toDouble())
        val right = Vec3(-cos(yaw), 0.0, -sin(yaw)).scale(if (arm == HumanoidArm.RIGHT) 1.0 else -1.0)
        return player.eyePosition.add(player.lookAngle.scale(0.6)).add(right.scale(0.3)).add(0.0, -0.25, 0.0)
    }

    /** What the crosshair rests on, as far as a spell flies: a creature, a block, or a point well ahead. */
    private fun aimPoint(player: Player): Vec3 {
        val eye = player.eyePosition
        val far = eye.add(player.lookAngle.scale(AIM_RANGE))
        aimTarget(player, AIM_RANGE, 3f)?.let { return it.position().add(0.0, it.bbHeight * 0.55, 0.0) }
        val hit = player.level().clip(ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return if (hit.type == HitResult.Type.MISS) far else hit.location
    }

    /** The foe nearest the line of [player]'s gaze within [cone] degrees and [range] blocks, that they can see. */
    fun aimTarget(player: Player, range: Double, cone: Float): LivingEntity? {
        val eye = player.eyePosition
        val look = player.lookAngle
        var best: LivingEntity? = null
        var bestAngle = cone.toDouble()
        for (entity in player.level().getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(range))) {
            if (!isFoe(player, entity)) continue
            val to = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0).subtract(eye)
            val distance = to.length()
            if (distance > range || distance < 0.5) continue
            val angle = Math.toDegrees(acos(to.normalize().dot(look).coerceIn(-1.0, 1.0)))
            if (angle >= bestAngle || !player.hasLineOfSight(entity)) continue
            bestAngle = angle
            best = entity
        }
        return best
    }

    /** The ground a little ahead of [player], where Hallowed Ground is laid. */
    private fun groundAhead(level: ServerLevel, player: Player): Vec3 {
        val look = player.lookAngle.multiply(1.0, 0.0, 1.0).normalize()
        return groundBelow(level, player.position().add(look.scale(1.5)).add(0.0, 1.0, 0.0))
    }

    /** The top of the first solid block at or below [at] (a few blocks down at most), or [at] itself over a drop. */
    private fun groundBelow(level: ServerLevel, at: Vec3): Vec3 {
        val hit = level.clip(ClipContext(at.add(0.0, 0.5, 0.0), at.add(0.0, -6.0, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
        return if (hit.type == HitResult.Type.MISS) at else hit.location
    }

    /** A ring of [particle] flung out flat from [at]. */
    private fun ring(level: ServerLevel, particle: ParticleOptions, at: Vec3, count: Int, speed: Double) {
        for (i in 0 until count) {
            val a = i * Math.PI * 2 / count
            level.sendParticles(particle, at.x, at.y, at.z, 0, cos(a), 0.02, sin(a), speed)
        }
    }

    /** Ticks after Flicker's release before its caster has arrived. */
    const val BLINK_TICKS = 3
    /** The speed of Flicker's blink, blocks a tick, for [BLINK_TICKS] ticks. */
    const val BLINK_SPEED = 2.3

    private const val UNDEAD_FACTOR = 1.5f
    private const val AIM_RANGE = 48.0
    private const val STAR_RANGE = 32.0
    private const val STAR_HEIGHT = 18.0
    private const val STAR_RADIUS = 4.5
    private const val HEAL_RADIUS = 6.0
    private const val FLARE_REACH = 5.0
    private const val FLARE_ARC = 45f
    private const val DAWN_RADIUS = 6.0
    private const val VIGIL_TICKS = 800
    /** Ticks a creature the caster struck still counts as a foe. */
    private const val FOE_MEMORY = 200
}
