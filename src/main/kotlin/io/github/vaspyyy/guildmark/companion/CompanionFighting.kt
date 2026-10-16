package io.github.vaspyyy.guildmark.companion

import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.combat.Move
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.faction.SwornKnight
import io.github.vaspyyy.guildmark.faction.WardLight
import io.github.vaspyyy.guildmark.magic.BoltKind
import io.github.vaspyyy.guildmark.magic.Magic
import io.github.vaspyyy.guildmark.magic.MagicRegistry
import io.github.vaspyyy.guildmark.magic.Spell
import io.github.vaspyyy.guildmark.magic.SpellBolt
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModParticles
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.github.vaspyyy.guildmark.story.StoryNpc
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.animal.golem.AbstractGolem
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What an act does when it lands. */
enum class Effect { BLOW, ARROW, BOLT, LANCE, FAN, BLINK, HEAL, WARD, FLARE, STEP }

/**
 * One thing a companion does in a fight: the clip it plays (one of the player's own, under
 * assets/guildmark/animations/), when it lands ([at], through [until] for a blow), and what it does then. Clients
 * are told which act started (CompanionEntity.DATA_ACT) and play its clip from [clipFrom] ticks in.
 */
class CompanionAct(
    val id: Int,
    val clip: String,
    val length: Int,
    val at: Int,
    val effect: Effect,
    val minRange: Double = 0.0,
    val maxRange: Double = 3.0,
    /** Multiplies their attack (the weapon in hand counts) for a blow, or sets a spell's strength. */
    val damage: Float = 1f,
    val reach: Double = 3.0,
    /** Half the swing's arc in degrees. */
    val arc: Float = 60f,
    val until: Int = at,
    /** Ticks they wait after it before the next. */
    val cooldown: Int = 10,
    /** Ticks before this one can be used again at all (a heal, a ward, a blink). */
    val rest: Int = 0,
    val lunge: Float = 0f,
    val lungeAt: Int = 0,
    val lungeTicks: Int = 1,
    val clipFrom: Int = 0,
    /** They turn to keep facing their target until this tick; after, the act is committed. */
    val tracking: Int = at,
    val stance: Float = 0f,
    /** The player's move whose swing it sounds like. */
    val move: Move? = null,
)

/**
 * How the companions fight. Bram cleaves with greatsword combos at the front; Ysolde hangs back, mends the player
 * with her censer, wards them when a boss turns up and swings it at anything that comes close; Kestrel shoots from
 * range and has the dagger out up close; Lucan casts arc bolts, rime lances and splinter fans, and blinks away from
 * anyone who closes in. Every blow and spell lands on foes only (see [isFoe]): never on the player, townsfolk or
 * anyone's pets.
 */
object CompanionFighting {
    // ---- Bram ----
    private val GS_LIGHT_2 = CompanionAct(2, "player/greatsword/light_2", 20, 8, Effect.BLOW, maxRange = 3.0, damage = 0.75f, reach = 3.4, arc = 80f, until = 10,
        cooldown = 14, lunge = 0.15f, lungeAt = 6, stance = 18f, move = Move.GS_LIGHT_2)
    private val GS_LIGHT_1 = CompanionAct(1, "player/greatsword/light_1", 20, 8, Effect.BLOW, maxRange = 3.0, damage = 0.7f, reach = 3.4, arc = 70f, until = 10,
        cooldown = 6, lunge = 0.15f, lungeAt = 6, stance = 18f, move = Move.GS_LIGHT_1)
    private val GS_HEAVY = CompanionAct(3, "player/greatsword/heavy", 28, 12, Effect.BLOW, maxRange = 3.0, damage = 1.2f, reach = 3.5, arc = 40f, until = 14,
        cooldown = 24, lunge = 0.25f, lungeAt = 11, stance = 34f, move = Move.GS_HEAVY)
    private val GS_RUNNING = CompanionAct(4, "player/greatsword/running", 20, 7, Effect.BLOW, minRange = 3.5, maxRange = 7.0, damage = 0.9f, reach = 3.4,
        until = 9, cooldown = 16, rest = 60, lunge = 0.45f, lungeAt = 2, lungeTicks = 4, stance = 24f, move = Move.GS_RUNNING)

    // ---- Ysolde ----
    private val HEAL = CompanionAct(10, "player/censer/raise", 26, 12, Effect.HEAL, maxRange = 12.0, cooldown = 12, rest = 140)
    private val WARD = CompanionAct(11, "player/censer/raise", 26, 12, Effect.WARD, maxRange = 12.0, cooldown = 12, rest = 1200)
    private val FLARE = CompanionAct(12, "player/censer/swing", 14, 5, Effect.FLARE, maxRange = 3.5, damage = 1.5f, cooldown = 16)

    // ---- Kestrel ----
    private val SHOT = CompanionAct(20, "companion/bow_release", 26, 18, Effect.ARROW, minRange = 3.5, maxRange = 26.0, cooldown = 10, clipFrom = 18, tracking = 18)
    private val DAGGER_3 = CompanionAct(23, "player/dagger/light_3", 9, 3, Effect.BLOW, maxRange = 2.6, damage = 1.0f, reach = 2.8, arc = 25f, until = 4,
        cooldown = 14, lunge = 0.15f, lungeAt = 1, stance = 8f, move = Move.DAGGER_LIGHT_3)
    private val DAGGER_2 = CompanionAct(22, "player/dagger/light_2", 9, 3, Effect.BLOW, maxRange = 2.6, damage = 0.8f, reach = 2.6, arc = 45f,
        cooldown = 2, lunge = 0.1f, lungeAt = 1, stance = 6f, move = Move.DAGGER_LIGHT_2)
    private val DAGGER_1 = CompanionAct(21, "player/dagger/light_1", 9, 3, Effect.BLOW, maxRange = 2.6, damage = 0.8f, reach = 2.6, arc = 45f,
        cooldown = 2, lunge = 0.1f, lungeAt = 1, stance = 6f, move = Move.DAGGER_LIGHT_1)
    private val BACKSTEP = CompanionAct(24, "player/backstep", 9, 1, Effect.STEP, maxRange = 3.0, cooldown = 4, rest = 80, move = Move.BACKSTEP)

    // ---- Lucan ----
    private val ARC_BOLT = CompanionAct(30, "player/staff/cast", 16, 6, Effect.BOLT, minRange = 2.0, maxRange = 24.0, damage = 1.0f, cooldown = 14)
    private val RIME_LANCE = CompanionAct(31, "player/staff/charged", 26, 12, Effect.LANCE, minRange = 5.0, maxRange = 28.0, damage = 1.8f, cooldown = 20, rest = 70)
    private val SPLINTER = CompanionAct(32, "player/staff/fan", 18, 7, Effect.FAN, minRange = 2.0, maxRange = 14.0, damage = 0.5f, cooldown = 18, rest = 60)
    private val FLICKER = CompanionAct(33, "player/staff/flicker", 12, 3, Effect.BLINK, maxRange = 3.0, cooldown = 6, rest = 100)

    private val ALL = listOf(GS_LIGHT_1, GS_LIGHT_2, GS_HEAVY, GS_RUNNING, HEAL, WARD, FLARE, SHOT, DAGGER_1, DAGGER_2, DAGGER_3, BACKSTEP, ARC_BOLT, RIME_LANCE, SPLINTER, FLICKER)
    private val BY_ID: Map<Int, CompanionAct> = ALL.associateBy { it.id }

    fun byId(id: Int): CompanionAct? = BY_ID[id]

    /** Ticks a creature the player (or the companion) struck, or was struck by, still counts as a foe. */
    private const val FOE_MEMORY = 200

    /** What they carry: Kestrel's bow, or her dagger up close. */
    fun weapon(kind: CompanionKind, dagger: Boolean): ItemStack = when (kind) {
        CompanionKind.BRAM -> ItemStack(ModItems.IRON_GREATSWORD.get())
        CompanionKind.YSOLDE -> ItemStack(MagicRegistry.IRON_CENSER.get())
        CompanionKind.KESTREL -> if (dagger) ItemStack(ModItems.IRON_DAGGER.get()) else ItemStack(Items.BOW)
        CompanionKind.LUCAN -> ItemStack(MagicRegistry.AMETHYST_STAFF.get())
    }

    // ---- Who is a foe ----

    /**
     * Whether [entity] is someone [c] fights: monsters, and whatever is after their player or them, or that either has
     * just traded blows with. Never a player, a companion, townsfolk, villagers, golems or anyone's pets, a neutral
     * creature nobody has angered, or a test light of a trial.
     */
    fun isFoe(c: CompanionEntity, entity: LivingEntity): Boolean {
        if (entity === c || !entity.isAlive || entity.isSpectator || entity.isInvulnerable) return false
        if (entity is Player || entity is CompanionEntity || entity is Townsperson || entity is StoryNpc || entity is AbstractVillager ||
            entity is AbstractGolem || entity is ArmorStand || entity is SwornKnight) return false
        if (entity is WardLight) return entity.owner == null && entity.summoner != null
        if (entity is OwnableEntity && entity.owner is Player || entity is TamableAnimal && entity.isTame) return false
        val owner = c.player
        if (entity is Mob && (entity.target === c || owner != null && entity.target === owner)) return true
        if (recently(owner, entity) || c.lastHurtByMob === entity && c.tickCount - c.lastHurtByMobTimestamp < FOE_MEMORY) return true
        if (entity is NeutralMob && (entity as? Mob)?.target == null) return false
        if (entity is CombatEnemy && !entity.canFight()) return false
        return entity is Enemy
    }

    private fun recently(owner: Player?, entity: LivingEntity): Boolean {
        if (owner == null) return false
        return owner.lastHurtMob === entity && owner.tickCount - owner.lastHurtMobTimestamp < FOE_MEMORY ||
            owner.lastHurtByMob === entity && owner.tickCount - owner.lastHurtByMobTimestamp < FOE_MEMORY
    }

    /** Who they'll fight next, if what they're fighting is gone: whoever's after their player or them first. */
    fun pickTarget(level: ServerLevel, c: CompanionEntity) {
        val current = c.target
        val owner = c.player
        if (current != null && keep(c, current, owner)) return
        c.target = null
        if (c.isDowned || c.isTalking || owner != null && Companions.holdingBack(owner)) return
        val following = c.mode == CompanionEntity.Mode.FOLLOW && owner != null && owner.level() === level
        val around: Entity = if (following) owner else c
        val range = if (following) 14.0 else 8.0
        val melee = c.kind.style == Style.BLADE || c.kind.style == Style.PRAYER
        var best: LivingEntity? = null
        var bestScore = Double.MAX_VALUE
        for (mob in level.getEntitiesOfClass(Mob::class.java, around.boundingBox.inflate(range, 6.0, range))) {
            if (!isFoe(c, mob)) continue
            // Bram and Ysolde leave creepers to the player, and what flies out of reach
            if (melee && (mob is Creeper || mob.y - c.y > 3.5)) continue
            val after = mob.target === owner || mob.target === c
            // Standing guard, they only answer what comes for them or the player
            if (!following && !after && mob.distanceToSqr(c) > 36.0) continue
            if (!c.hasLineOfSight(mob) && mob.distanceToSqr(c) > 16.0) continue
            val score = mob.distanceToSqr(around) * (if (after) 0.4 else 1.0)
            if (score < bestScore) {
                bestScore = score
                best = mob
            }
        }
        c.target = best
    }

    private fun keep(c: CompanionEntity, target: LivingEntity, owner: Player?): Boolean {
        if (!target.isAlive || target.level() !== c.level() || !isFoe(c, target)) return false
        if (owner != null && Companions.holdingBack(owner)) return false
        val anchor: Entity = if (c.mode == CompanionEntity.Mode.FOLLOW && owner != null && owner.level() === c.level()) owner else c
        return target.distanceToSqr(anchor) < 24.0 * 24.0
    }

    // ---- The act under way ----

    fun inRange(c: CompanionEntity, act: CompanionAct, target: LivingEntity): Boolean = gap(c, target) in act.minRange..act.maxRange + 0.5

    /** Blocks between [c] and [target], edge to edge. */
    private fun gap(c: CompanionEntity, target: LivingEntity): Double = (c.distanceTo(target) - target.bbWidth / 2 - c.bbWidth / 2).toDouble()

    fun tickAct(level: ServerLevel, c: CompanionEntity) {
        val a = c.act
        if (a == null) {
            if (c.kind.style == Style.PRAYER && !c.still && c.tickCount % 5 == 0) support(level, c)
            return
        }
        if (Stance.isStaggered(c)) {
            c.stopAct()
            return
        }
        val t = c.actTick
        val target = c.actTarget
        if (target != null && target.isAlive && target !== c && t <= a.tracking) face(c, target, 20f)
        if (a.lunge > 0f && t >= a.lungeAt && t < a.lungeAt + a.lungeTicks) {
            val look = Vec3.directionFromRotation(0f, c.yRot)
            val push = a.lunge * (if (c.onGround()) 1.0 else 0.3)
            c.deltaMovement = Vec3(look.x * push, c.deltaMovement.y, look.z * push)
        }
        a.move?.let { move -> if (t == (a.at - 3).coerceAtLeast(0)) Combat.moveSound(move)?.let { c.playSound(it, 0.8f, Combat.pitch(move)) } }
        if (a.effect == Effect.ARROW) c.draw(t < a.at)
        when {
            a.effect == Effect.BLOW -> if (t in a.at..a.until) blow(level, c, a)
            t == a.at -> land(level, c, a, target)
        }
        c.advanceAct()
    }

    /** Kestrel puts the dagger away once there's room to shoot again. */
    fun tickHolding(c: CompanionEntity) {
        if (c.kind != CompanionKind.KESTREL || !c.closeIn || c.act != null) return
        val target = c.target
        if (target == null || gap(c, target) > 4.5) c.holdDagger(false)
    }

    private fun face(c: CompanionEntity, target: Entity, step: Float) {
        val wanted = Math.toDegrees(Math.atan2(-(target.x - c.x), target.z - c.z)).toFloat()
        c.yRot += Mth.clamp(Mth.wrapDegrees(wanted - c.yRot), -step, step)
        c.yBodyRot = c.yRot
        c.yHeadRot = c.yRot
    }

    /** A blow of [a], on every foe it reaches that it hasn't hit yet. */
    private fun blow(level: ServerLevel, c: CompanionEntity, a: CompanionAct) {
        val damage = c.getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() * a.damage
        val box = c.boundingBox.inflate(a.reach + 1.0, 1.0, a.reach + 1.0)
        for (victim in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
            if (victim === c || !victim.isAlive || c.struck.contains(victim.id)) continue
            if (victim !== c.actTarget && !isFoe(c, victim) || victim is Player) continue
            val dx = victim.x - c.x
            val dz = victim.z - c.z
            val flat = sqrt(dx * dx + dz * dz) - victim.bbWidth / 2
            if (flat > a.reach) continue
            if (victim.y > c.y + c.bbHeight + 0.5 || victim.boundingBox.maxY < c.y - 0.5) continue
            if (a.arc < 180f && flat > 0.6 && Combat.angleTo(c.yRot, dx, dz) > a.arc) continue
            if (flat > 1.0 && !c.hasLineOfSight(victim)) continue
            c.struck.add(victim.id)
            val source = c.damageSources().mobAttack(c)
            victim.damageCooldownTime = 0
            if (!victim.hurtServer(level, source, damage)) continue
            val r = c.yRot * Mth.DEG_TO_RAD
            victim.knockback(0.35, sin(r).toDouble(), -cos(r).toDouble(), source, damage)
            if (victim.isAlive) Stance.damage(victim, a.stance)
            c.setLastHurtMob(victim)
            val sound = if (victim.armorValue >= 6) ModSounds.HIT_ARMOR.get() else ModSounds.HIT_FLESH.get()
            level.playSound(null, victim.x, victim.getY(0.6), victim.z, sound, SoundSource.NEUTRAL, 0.9f, 0.9f + level.random.nextFloat() * 0.2f)
            level.sendParticles(ParticleTypes.CRIT, victim.x, victim.getY(0.6), victim.z, 4, 0.25, 0.25, 0.25, 0.3)
        }
    }

    /** The moment [a] goes off: an arrow loosed, a spell cast, a prayer said, a step back. */
    private fun land(level: ServerLevel, c: CompanionEntity, a: CompanionAct, target: LivingEntity?) {
        when (a.effect) {
            Effect.ARROW -> if (target != null) shoot(level, c, target)
            Effect.BOLT -> if (target != null) bolt(level, c, BoltKind.ARC_BOLT, Spell.ARC_BOLT, target, a.damage, 0f).seek(target)
            Effect.LANCE -> if (target != null) bolt(level, c, BoltKind.RIME_LANCE, Spell.RIME_LANCE, target, a.damage, 0.5f)
            Effect.FAN -> if (target != null) for (i in -2..2) bolt(level, c, BoltKind.SPLINTER, Spell.SPLINTER_HAIL, target, a.damage, 0f, i * 9.0)
            Effect.BLINK -> blink(level, c, target)
            Effect.HEAL -> heal(level, c, target ?: c)
            Effect.WARD -> ward(level, c, target ?: c.player ?: return)
            Effect.FLARE -> flare(level, c, a)
            Effect.STEP -> step(c, target)
            Effect.BLOW -> {}
        }
    }

    private fun shoot(level: ServerLevel, c: CompanionEntity, target: LivingEntity) {
        val arrow = CompanionArrow(level, c, c.mainHandItem)
        arrow.setBaseDamage(c.getAttributeValue(Attributes.ATTACK_DAMAGE))
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED
        arrow.isCritArrow = c.random.nextInt(4) == 0
        // A little ahead of a moving target, and up a touch for the drop
        val lead = target.deltaMovement.multiply(1.0, 0.0, 1.0).scale(c.distanceTo(target) / ARROW_SPEED.toDouble())
        val dx = target.x + lead.x - c.x
        val dz = target.z + lead.z - c.z
        val dy = target.getY(0.5) - arrow.y
        val flat = sqrt(dx * dx + dz * dz)
        arrow.shoot(dx, dy + flat * 0.12, dz, ARROW_SPEED, 1.5f)
        level.addFreshEntity(arrow)
        c.playSound(SoundEvents.ARROW_SHOOT, 1f, 1f / (c.random.nextFloat() * 0.4f + 0.8f))
    }

    /** A sorcery from the head of Lucan's staff, at [target] (turned [spread] degrees for a fan). */
    private fun bolt(level: ServerLevel, c: CompanionEntity, kind: BoltKind, spell: Spell, target: LivingEntity, share: Float, charged: Float, spread: Double = 0.0): SpellBolt {
        val yaw = Math.toRadians(c.yRot.toDouble())
        val right = Vec3(-cos(yaw), 0.0, -sin(yaw))
        val from = c.eyePosition.add(c.lookAngle.scale(0.6)).add(right.scale(0.3)).add(0.0, -0.25, 0.0)
        var aim = target.position().add(0.0, target.bbHeight * 0.55, 0.0).subtract(from).normalize()
        if (spread != 0.0) aim = aim.yRot(Math.toRadians(spread).toFloat()).add(0.0, 0.04, 0.0)
        val damage = c.getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() * share
        val bolt = SpellBolt(level, c, kind, spell, from, damage, charged)
        bolt.shoot(aim.x, aim.y, aim.z, kind.speed.toFloat(), 0f)
        level.addFreshEntity(bolt)
        if (spread == 0.0 || spread > 17.0) level.playSound(null, c.x, c.y, c.z, Magic.castSound(spell), SoundSource.NEUTRAL, 1f, 0.95f + level.random.nextFloat() * 0.1f)
        return bolt
    }

    /** Flicker: away from whoever crowds him, to somewhere he can stand. */
    private fun blink(level: ServerLevel, c: CompanionEntity, from: LivingEntity?) {
        val away = (if (from != null) c.position().subtract(from.position()) else c.lookAngle.reverse()).multiply(1.0, 0.0, 1.0)
        val dir = if (away.lengthSqr() < 1e-4) Vec3(1.0, 0.0, 0.0) else away.normalize()
        level.sendParticles(MagicRegistry.ARCANE_MOTE.get(), c.x, c.getY(0.5), c.z, 20, 0.3, 0.6, 0.3, 0.05)
        level.playSound(null, c.x, c.y, c.z, MagicRegistry.FLICKER.get(), SoundSource.NEUTRAL, 1f, 1f)
        for (distance in intArrayOf(6, 5, 4, 3)) for (turn in doubleArrayOf(0.0, 30.0, -30.0, 60.0, -60.0)) {
            val to = c.position().add(dir.yRot(Math.toRadians(turn).toFloat()).scale(distance.toDouble()))
            val pos = BlockPos.containing(to)
            for (dy in intArrayOf(0, 1, -1)) {
                val at = pos.above(dy)
                if (!c.canStandAt(at)) continue
                c.arriveAt(at)
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.getY(0.5), c.z, 14, 0.25, 0.5, 0.25, 0.05)
                return
            }
        }
    }

    private fun heal(level: ServerLevel, c: CompanionEntity, who: LivingEntity) {
        Magic.heal(c, who, 4f + who.maxHealth * 0.25f)
        level.sendParticles(ModParticles.WARD_EMBER.get(), who.x, who.getY(0.4), who.z, 18, 0.35, 0.5, 0.35, 0.02)
        level.sendParticles(ParticleTypes.END_ROD, who.x, who.getY(0.6), who.z, 6, 0.3, 0.5, 0.3, 0.02)
        level.playSound(null, c.x, c.y, c.z, MagicRegistry.HEARTHLIGHT.get(), SoundSource.NEUTRAL, 1f, 1f)
    }

    private fun ward(level: ServerLevel, c: CompanionEntity, who: LivingEntity) {
        who.addEffect(MobEffectInstance(MagicRegistry.VIGIL, WARD_TICKS, 0, false, true, true), c)
        level.sendParticles(ModParticles.WARD_EMBER.get(), who.x, who.getY(0.5), who.z, 30, 0.4, 0.7, 0.4, 0.03)
        level.playSound(null, c.x, c.y, c.z, MagicRegistry.VIGIL_SOUND.get(), SoundSource.NEUTRAL, 1f, 1f)
    }

    /** Ysolde's censer swung through a gout of holy fire, a short cone ahead. */
    private fun flare(level: ServerLevel, c: CompanionEntity, a: CompanionAct) {
        val look = Vec3.directionFromRotation(0f, c.yRot)
        val damage = c.getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() * a.damage
        for (foe in level.getEntitiesOfClass(LivingEntity::class.java, c.boundingBox.inflate(FLARE_REACH))) {
            if (!isFoe(c, foe)) continue
            val to = foe.position().add(0.0, foe.bbHeight * 0.5, 0.0).subtract(c.eyePosition)
            if (to.length() > FLARE_REACH + foe.bbWidth / 2) continue
            val flat = Vec3(to.x, 0.0, to.z)
            if (flat.lengthSqr() > 0.36 && Math.toDegrees(acos(flat.normalize().dot(look).coerceIn(-1.0, 1.0))) > FLARE_ARC) continue
            if (!c.hasLineOfSight(foe)) continue
            if (Magic.hurt(c, foe, damage, Spell.FLARE, c)) foe.igniteForSeconds(4f)
        }
        val from = c.eyePosition.add(look.scale(0.7)).add(0.0, -0.4, 0.0)
        for (i in 0 until 20) {
            val spread = look.add((level.random.nextDouble() - 0.5) * 0.5, (level.random.nextDouble() - 0.5) * 0.3, (level.random.nextDouble() - 0.5) * 0.5).normalize()
            level.sendParticles(if (i % 3 == 0) ModParticles.WARD_EMBER.get() else ParticleTypes.FLAME, from.x, from.y, from.z, 0, spread.x, spread.y, spread.z, 0.25 + level.random.nextDouble() * 0.2)
        }
        level.playSound(null, c.x, c.y, c.z, MagicRegistry.FLARE.get(), SoundSource.NEUTRAL, 1f, 1f)
    }

    /** A hop back out of reach. */
    private fun step(c: CompanionEntity, from: LivingEntity?) {
        val away = (if (from != null) c.position().subtract(from.position()) else c.lookAngle.reverse()).multiply(1.0, 0.0, 1.0)
        if (away.lengthSqr() < 1e-4) return
        val push = away.normalize().scale(0.75)
        c.deltaMovement = Vec3(push.x, 0.28, push.z)
        c.needsSync = true
    }

    // ---- Ysolde's prayers between blows ----

    /** The player badly hurt, herself, or a boss come for them: Ysolde prays. */
    private fun support(level: ServerLevel, c: CompanionEntity) {
        if (c.cooldown > 0 || c.isTalking) return
        val owner = c.player?.takeIf { it.isAlive && it.level() === level && !it.isSpectator }
        if (owner != null && c.ready(HEAL) && owner.health < owner.maxHealth * 0.6f && c.distanceToSqr(owner) < 144.0 && c.hasLineOfSight(owner)) {
            c.startAct(HEAL, owner)
            return
        }
        if (c.ready(HEAL) && c.health < c.maxHealth * 0.5f) {
            c.startAct(HEAL, c)
            return
        }
        if (owner != null && c.ready(WARD) && !owner.hasEffect(MagicRegistry.VIGIL) && c.distanceToSqr(owner) < 144.0 && bossNear(level, owner)) {
            c.startAct(WARD, owner)
        }
    }

    private fun bossNear(level: ServerLevel, owner: Player): Boolean =
        level.getEntitiesOfClass(LivingEntity::class.java, owner.boundingBox.inflate(BOSS_RANGE)) {
            it.isAlive && (Talents.isBoss(it) || it is CombatEnemy && it.isBoss && it.canFight())
        }.isNotEmpty()

    // ---- Choosing what to do ----

    /** How a companion fights the target they've picked: each in their own way (see the object's doc). */
    class FightGoal(private val c: CompanionEntity) : Goal() {
        private var repath = 0
        private var strafe = 1f
        private var strafeTicks = 0

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean = c.target?.isAlive == true && !c.still
        override fun canContinueToUse(): Boolean = canUse()
        override fun requiresUpdateEveryTick(): Boolean = true

        override fun stop() {
            c.navigation.stop()
            c.moveControl.strafe(0f, 0f)
            c.draw(false)
        }

        override fun tick() {
            val target = c.target ?: return
            if (c.act != null || Stance.isStaggered(c)) return
            c.lookControl.setLookAt(target, 30f, 30f)
            val d = gap(c, target)
            val seen = c.sensing.hasLineOfSight(target)
            when (c.kind.style) {
                Style.BLADE -> blade(target, d, seen)
                Style.PRAYER -> prayer(target, d, seen)
                Style.BOW -> bow(target, d, seen)
                Style.SORCERY -> sorcery(target, d, seen)
            }
        }

        private fun start(act: CompanionAct, target: LivingEntity, then: CompanionAct? = null) {
            c.moveControl.strafe(0f, 0f)
            c.startAct(act, target, then)
        }

        private fun blade(target: LivingEntity, d: Double, seen: Boolean) {
            if (c.cooldown <= 0 && seen) {
                when {
                    d <= GS_LIGHT_1.maxRange -> return if (c.random.nextInt(10) < 3) start(GS_HEAVY, target) else start(GS_LIGHT_1, target, GS_LIGHT_2)
                    d in GS_RUNNING.minRange..GS_RUNNING.maxRange && c.ready(GS_RUNNING) && c.random.nextInt(3) == 0 -> return start(GS_RUNNING, target)
                }
            }
            if (d > 2.0) chase(target, 1.15) else hold(target, 2.0)
        }

        private fun prayer(target: LivingEntity, d: Double, seen: Boolean) {
            if (c.cooldown <= 0 && seen && d <= FLARE.maxRange) return start(FLARE, target)
            // She keeps back behind the player, out of the press
            val owner = c.player
            if (owner != null && owner.level() === c.level() && d > 3.0) {
                val spot = c.formation(owner)
                if (c.distanceToSqr(spot) > 4.0) walk(spot, 1.1) else hold(target, 5.0)
            } else {
                keepAway(target, d, 4.0, 7.0)
            }
        }

        private fun bow(target: LivingEntity, d: Double, seen: Boolean) {
            if (d < 2.8) {
                if (c.ready(BACKSTEP) && c.random.nextInt(3) == 0) return start(BACKSTEP, target)
                c.holdDagger(true)
                if (c.cooldown <= 0) return start(DAGGER_1, target, DAGGER_2)
                return hold(target, 1.5)
            }
            if (c.closeIn) {
                if (d > 4.5) c.holdDagger(false) else return chase(target, 1.1)
            }
            if (c.cooldown <= 0 && seen && d in SHOT.minRange..SHOT.maxRange) return start(SHOT, target)
            if (!seen || d > 18.0) chase(target, 1.1) else keepAway(target, d, 7.0, 15.0)
        }

        private fun sorcery(target: LivingEntity, d: Double, seen: Boolean) {
            if (d < 2.5 && c.ready(FLICKER)) return start(FLICKER, target)
            if (c.cooldown <= 0 && seen) {
                val crowd = c.level().getEntitiesOfClass(Mob::class.java, target.boundingBox.inflate(4.0)) { it !== target && isFoe(c, it) }.size
                when {
                    crowd >= 1 && c.ready(SPLINTER) && d <= SPLINTER.maxRange -> return start(SPLINTER, target)
                    d >= RIME_LANCE.minRange && c.ready(RIME_LANCE) && c.random.nextInt(5) < 2 -> return start(RIME_LANCE, target)
                    d in ARC_BOLT.minRange..ARC_BOLT.maxRange -> return start(ARC_BOLT, target)
                }
            }
            if (!seen || d > 18.0) chase(target, 1.1) else keepAway(target, d, 6.0, 13.0)
        }

        private fun chase(target: LivingEntity, speed: Double) {
            c.moveControl.strafe(0f, 0f)
            if (--repath <= 0 || c.navigation.isDone) {
                repath = 6 + c.random.nextInt(5)
                c.navigation.moveTo(target, speed)
            }
        }

        private fun walk(to: Vec3, speed: Double) {
            if (--repath <= 0 || c.navigation.isDone) {
                repath = 8
                c.navigation.moveTo(to.x, to.y, to.z, speed)
            }
        }

        /** Close enough: circling a little, facing [target]. */
        private fun hold(target: LivingEntity, distance: Double) {
            c.navigation.stop()
            if (--strafeTicks <= 0) {
                strafeTicks = 20 + c.random.nextInt(40)
                strafe = if (c.random.nextBoolean()) 1f else -1f
            }
            val forward = if (gap(c, target) < distance - 1.0) -0.4f else 0f
            c.moveControl.strafe(forward, strafe * 0.35f)
            c.yRot = Mth.rotateIfNecessary(c.yRot, c.yHeadRot, 30f)
        }

        /** Between [near] and [far] of [target], backing off or closing in to keep it there. */
        private fun keepAway(target: LivingEntity, d: Double, near: Double, far: Double) {
            c.navigation.stop()
            if (--strafeTicks <= 0) {
                strafeTicks = 25 + c.random.nextInt(40)
                strafe = if (c.random.nextBoolean()) 1f else -1f
            }
            val forward = when {
                d < near -> -0.7f
                d > far -> 0.5f
                else -> 0f
            }
            c.moveControl.strafe(forward, strafe * 0.3f)
            c.yRot = Mth.rotateIfNecessary(c.yRot, c.yHeadRot, 30f)
        }
    }

    private const val ARROW_SPEED = 2.4f
    private const val WARD_TICKS = 600
    private const val FLARE_REACH = 4.5
    private const val FLARE_ARC = 50.0
    private const val BOSS_RANGE = 24.0
}

/**
 * Kestrel's arrows: they fly past friends (the player, townsfolk, anyone's pets) and, once she's Sworn, mark what
 * they hit (see Companions.onKestrelHit). Spawned as plain arrows on clients, and never picked up.
 */
class CompanionArrow(level: Level, shooter: CompanionEntity, weapon: ItemStack) : Arrow(level, shooter, ItemStack(Items.ARROW), weapon) {
    override fun canHitEntity(target: Entity): Boolean {
        // getOwner(): inside a projectile, plain owner is the EntityReference field rather than Kestrel
        val shooter = getOwner() as? CompanionEntity
        if (shooter != null && target is LivingEntity && !CompanionFighting.isFoe(shooter, target)) return false
        return super.canHitEntity(target)
    }

    override fun doPostHurtEffects(target: LivingEntity) {
        super.doPostHurtEffects(target)
        (getOwner() as? CompanionEntity)?.let { Companions.onKestrelHit(it, target) }
    }
}
