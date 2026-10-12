package io.github.vaspyyy.guildmark.combat

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.network.MovePayload
import io.github.vaspyyy.guildmark.network.ReleaseChargePayload
import io.github.vaspyyy.guildmark.network.StartMovePayload
import io.github.vaspyyy.guildmark.network.StrikePayload
import io.github.vaspyyy.guildmark.network.VitalsPayload
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.PlayLevelSoundEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent
import net.neoforged.neoforge.network.PacketDistributor
import java.lang.reflect.Field
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/**
 * The server half of the new combat. Clients predict their own moves and report where their swings land;
 * the server checks every request against stamina, timing and reach, applies the hits through vanilla's
 * attack (so enchantments, durability and stats still work), and owns stamina, i-frames, poise and parries.
 */
object Combat {
    fun enabled(): Boolean = GuildmarkConfig.COMBAT.get()

    /** Vanilla scales damage by the attack cooldown; our moves have their own timing, so it's topped up before each hit. */
    private val attackStrengthTicker: Field by lazy {
        LivingEntity::class.java.getDeclaredField("attackStrengthTicker").apply { isAccessible = true }
    }

    /** A hit being applied right now, so the damage, knockback, crit, sweep and sound hooks know it's ours. */
    private class Strike(val player: ServerPlayer, val target: LivingEntity, val move: Move, val multiplier: Float, val knockback: Float)
    private var striking: Strike? = null

    fun state(player: Player): CombatState = player.getData(ModAttachments.COMBAT)

    /** How much harder than its base damage the move landing on [target] from [attacker] hits (1 for anything else). */
    fun strikeMultiplier(target: LivingEntity, attacker: Entity?): Float =
        striking?.takeIf { it.target === target && it.player === attacker }?.multiplier ?: 1f

    /** Keeps [player] from harm for [ticks] while a boss's cutscene has their camera. */
    fun shieldForScene(player: ServerPlayer, ticks: Int) {
        state(player).sceneUntil = player.level().gameTime + ticks
    }

    // ---- Requests from clients ----

    fun onStartMove(sender: Player, payload: StartMovePayload) {
        val player = sender as? ServerPlayer ?: return
        if (!enabled() || !player.isAlive) return
        var move = Move.byId(payload.move) ?: return
        val s = state(player)
        val now = player.level().gameTime
        if (move == Move.ROLL || move == Move.ROLL_HEAVY) move = Weapons.rollFor(player)
        if (!allowed(player, move, s, now)) {
            s.ackSeq = max(s.ackSeq, payload.seq)
            PacketDistributor.sendToPlayer(player, MovePayload(player.id, move.ordinal, payload.seq, MovePayload.REJECT, 0f))
            return
        }
        s.spendStamina(move.stamina, now)
        s.focus = (s.focus - move.focus).coerceAtLeast(0f)
        if (move.isAttack && player.isUsingItem) player.stopUsingItem()

        // A guard counter needs a blow just blocked; otherwise it's an ordinary slash
        var actual = if (move == Move.GUARD_COUNTER && s.guardCounterUntil + 4 < now) Move.LIGHT_1 else move
        var critical: LivingEntity? = null
        if (actual.canBecomeCritical) {
            findCritical(player)?.let { (target, crit) ->
                actual = crit
                critical = target
            }
        }
        val holding = actual.chargeAt >= 0 && payload.flags and StartMovePayload.HOLDING != 0
        s.start(actual, now, payload.seq, if (actual.isAttack) Weapons.tempo(player) else 1f, holding)
        s.ackSeq = max(s.ackSeq, payload.seq)
        critical?.let {
            s.criticalTarget = it.id
            s.criticalHits = 0
            Stance.hold(it, player, actual)
        }
        // The client shows the move it guessed; correct it when the guess was wrong, and name a critical's victim
        val shown = when {
            payload.flags and StartMovePayload.GUESS_RIPOSTE != 0 -> Move.RIPOSTE
            payload.flags and StartMovePayload.GUESS_BACKSTAB != 0 -> Move.BACKSTAB
            else -> move
        }
        if (actual != shown || actual.isCritical) {
            PacketDistributor.sendToPlayer(player, MovePayload(player.id, actual.ordinal, payload.seq, MovePayload.OVERRIDE, 0f, critical?.id ?: -1))
        }

        // Everyone else sees the move and hears it
        val extra = if (actual.isRoll) payload.yaw else 0f
        PacketDistributor.sendToPlayersTrackingEntity(player, MovePayload(player.id, actual.ordinal, payload.seq, if (holding) MovePayload.CHARGING else 0, extra))
        moveSound(actual)?.let { player.level().playSound(player, player.x, player.y, player.z, it, SoundSource.PLAYERS, 0.9f, 0.9f + player.random.nextFloat() * 0.2f) }
        if (actual.isCritical) {
            player.level().playSound(null, player.x, player.y, player.z, ModSounds.SWING_HEAVY.get(), SoundSource.PLAYERS, 1f, 0.8f)
        }
    }

    fun onReleaseCharge(sender: Player, payload: ReleaseChargePayload) {
        val player = sender as? ServerPlayer ?: return
        val s = state(player)
        val move = s.move ?: return
        if (payload.seq != s.seq || move.chargeAt < 0 || s.charge >= 0) return
        s.charge = Mth.clamp(payload.charge, 0, move.maxCharge)
        PacketDistributor.sendToPlayersTrackingEntity(player, MovePayload(player.id, move.ordinal, s.seq, MovePayload.RELEASE, s.charge.toFloat()))
    }

    fun onStrike(sender: Player, payload: StrikePayload) {
        val player = sender as? ServerPlayer ?: return
        if (!enabled() || !player.isAlive) return
        val s = state(player)
        val move = s.move ?: return
        if (payload.seq != s.seq || !move.isAttack) return
        val level = player.level() as ServerLevel
        val now = level.gameTime
        // Generous timing: the client's clock runs a little ahead of or behind ours
        val t = s.elapsed(now)
        val charge = s.charge.coerceAtLeast(0)
        if (t < move.hitFrom - 5 || t > move.hitTo + 10 + (if (s.charge < 0) move.maxCharge else 0)) return
        val chargeBonus = if (move.maxCharge > 0) charge.toFloat() / move.maxCharge else 0f
        for (id in payload.targets.take(8)) {
            if (!s.struck.add(id)) continue
            val target = level.getEntity(id) as? LivingEntity ?: continue
            if (!canReach(player, target, move)) continue
            val multiplier = move.damage + 0.7f * chargeBonus
            strike(player, target, move, multiplier, move.knockback + 0.5f * chargeBonus, move.stance * (1f + 0.6f * chargeBonus))
        }
    }

    private fun allowed(player: ServerPlayer, move: Move, s: CombatState, now: Long): Boolean {
        if (!move.requestable || player.isSpectator || player.isPassenger || player.isFallFlying || player.isSleeping) return false
        if (move.isAttack && Weapons.classOf(player.mainHandItem) == null) return false
        if (move.stamina > 0f && s.stamina < 1f && !player.isCreative) return false
        if (move.focus > 0f && s.focus < move.focus && !player.isCreative) return false
        if (!s.busy(now)) return true
        val current = s.move ?: return true
        // Forced and critical moves play out in full; otherwise a move may cut in at its chain or dodge point
        if (!current.requestable && current != Move.PARRY) return false
        val from = if (move.isRoll) current.dodgeFrom else current.chainFrom
        return s.elapsed(now) >= from - 3
    }

    private fun canReach(player: ServerPlayer, target: LivingEntity, move: Move): Boolean {
        if (target === player || !target.isAlive || target.isSpectator || target.isInvulnerable) return false
        if (target is OwnableEntity && target.owner === player) return false
        val dx = target.x - player.x
        val dz = target.z - player.z
        val flat = Math.sqrt(dx * dx + dz * dz) - target.bbWidth / 2
        if (flat > move.reach + 1.5) return false
        if (target.y > player.eyeY + 2.5 || target.boundingBox.maxY < player.y - 2.5) return false
        if (move.arc < 180f && flat > 0.6) {
            val angle = angleTo(player.yRot, dx, dz)
            if (angle > move.arc + 35f) return false
        }
        return player.hasLineOfSight(target) || flat < 1.0
    }

    /** Degrees between facing [yaw] and the flat direction ([dx], [dz]). */
    fun angleTo(yaw: Float, dx: Double, dz: Double): Float {
        val look = Vec3.directionFromRotation(0f, yaw)
        val length = Math.sqrt(dx * dx + dz * dz)
        if (length < 1e-4) return 0f
        val dot = (look.x * dx + look.z * dz) / length
        return Math.toDegrees(acos(Mth.clamp(dot, -1.0, 1.0))).toFloat()
    }

    /** Applies one blow of [move] from [player] to [target] through vanilla's attack, then wears down its stance. */
    private fun strike(player: ServerPlayer, target: LivingEntity, move: Move, multiplier: Float, knockback: Float, stance: Float) {
        val level = player.level() as ServerLevel
        val before = target.health
        val previous = striking
        striking = Strike(player, target, move, multiplier, knockback)
        try {
            attackStrengthTicker.setInt(player, 1000)
            target.damageCooldownTime = 0
            player.attack(target)
        } finally {
            striking = previous
        }
        if (target.health >= before && target.isAlive) return
        val s = state(player)
        s.lastCombat = level.gameTime
        s.focus = min(s.maxFocus, s.focus + 1.5f)
        if (target.isAlive) Stance.damage(target, stance)
        val sound = when {
            move.isCritical -> ModSounds.CRITICAL.get()
            armored(target) -> ModSounds.HIT_ARMOR.get()
            else -> ModSounds.HIT_FLESH.get()
        }
        // The striker's own client already played its hit; criticals are the server's, so everyone hears those
        level.playSound(if (move.isCritical) null else player, target.x, target.getY(0.6), target.z, sound, SoundSource.PLAYERS, 1f, 0.9f + level.random.nextFloat() * 0.2f)
        level.sendParticles(ParticleTypes.CRIT, target.x, target.getY(0.6), target.z, if (move.isCritical) 18 else 5, 0.25, 0.25, 0.25, 0.3)
    }

    private fun armored(target: LivingEntity): Boolean =
        target.armorValue >= 6 || target is AbstractSkeleton || target is CombatMob

    // ---- Criticals ----

    /** A riposte (on a staggered foe in front) or backstab (on an unready foe from behind) that a light attack can become. */
    private fun findCritical(player: ServerPlayer): Pair<LivingEntity, Move>? {
        if (!player.onGround()) return null
        val level = player.level() as ServerLevel
        var best: Pair<LivingEntity, Move>? = null
        var bestDistance = Double.MAX_VALUE
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(3.0))) {
            if (entity === player || !entity.isAlive || entity is Player || Stance.immune(entity)) continue
            val dx = entity.x - player.x
            val dz = entity.z - player.z
            val distance = Math.sqrt(dx * dx + dz * dz) - entity.bbWidth / 2
            if (distance > 2.2 || abs(entity.y - player.y) > 1.2) continue
            if (angleTo(player.yRot, dx, dz) > 50f) continue
            val move = when {
                Stance.isOpen(entity) -> Move.RIPOSTE
                canBackstab(player, entity) -> Move.BACKSTAB
                else -> continue
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = entity to move
            }
        }
        return best
    }

    private fun canBackstab(player: ServerPlayer, entity: LivingEntity): Boolean {
        if (entity !is Mob || entity.bbHeight !in 0.5f..2.6f) return false
        if (entity !is Enemy && entity !is CombatMob && entity.target !== player) return false
        if ((entity as? CombatMob)?.canBeBackstabbed() == false) return false
        // Behind it: the creature faces away from the player
        val angle = angleTo(entity.yBodyRot, player.x - entity.x, player.z - entity.z)
        return angle > 125f
    }

    private fun tickCritical(player: ServerPlayer, s: CombatState, move: Move, t: Float) {
        val level = player.level() as ServerLevel
        val target = level.getEntity(s.criticalTarget) as? LivingEntity
        val hits = if (move == Move.RIPOSTE) Move.RIPOSTE_HITS else intArrayOf(move.hitFrom)
        val damage = if (move == Move.RIPOSTE) Move.RIPOSTE_DAMAGE else floatArrayOf(move.damage)
        while (s.criticalHits < hits.size && t >= hits[s.criticalHits]) {
            val i = s.criticalHits++
            if (target == null || !target.isAlive) continue
            val last = i == hits.size - 1
            strike(player, target, move, damage[i], if (last) 1.4f else 0f, 0f)
            if (last && target.isAlive) Stance.release(target)
        }
    }

    // ---- Every tick ----

    fun tick(player: ServerPlayer) {
        if (!enabled()) return
        val s = state(player)
        val level = player.level() as ServerLevel
        val now = level.gameTime
        s.move?.let { move ->
            if (move.isCritical) tickCritical(player, s, move, s.elapsed(now))
            if (!s.busy(now)) {
                if (move.isCritical) (level.getEntity(s.criticalTarget) as? LivingEntity)?.let { if (it.isAlive) Stance.release(it) }
                s.clear()
            }
        }
        if (player.tickCount % 10 == 0) {
            s.hunted = level.getEntitiesOfClass(Mob::class.java, player.boundingBox.inflate(20.0)) { it.isAlive && it.target === player }.isNotEmpty()
        }
        val inCombat = s.inCombat(now)
        if (player.isCreative || player.isSpectator) {
            s.stamina = s.maxStamina
            s.focus = s.maxFocus
        } else if (player.isSprinting && inCombat) {
            s.spendStamina(CombatState.SPRINT_COST, now)
        } else if (now - s.lastSpend >= CombatState.REGEN_DELAY) {
            val guarding = player.isUsingItem && player.useItem.has(DataComponents.BLOCKS_ATTACKS)
            s.stamina = min(s.maxStamina, s.stamina + CombatState.REGEN_PER_TICK * (if (guarding) 0.35f else 1f))
        }
        s.focus = min(s.maxFocus, s.focus + CombatState.FOCUS_PER_TICK)
        sendVitals(player, s, now, inCombat)
    }

    private fun sendVitals(player: ServerPlayer, s: CombatState, now: Long, inCombat: Boolean) {
        val flags = (if (inCombat) VitalsPayload.IN_COMBAT else 0) or (if (s.guardCounterUntil > now) VitalsPayload.GUARD_COUNTER else 0)
        val changed = abs(s.stamina - s.sentStamina) > 0.05f || abs(s.focus - s.sentFocus) > 0.05f ||
            flags != s.sentFlags || s.maxStamina != s.sentMaxStamina
        if (!changed && player.tickCount % 40 != 0) return
        s.sentStamina = s.stamina
        s.sentFocus = s.focus
        s.sentFlags = flags
        s.sentMaxStamina = s.maxStamina
        PacketDistributor.sendToPlayer(player, VitalsPayload(s.stamina, s.maxStamina, s.focus, s.maxFocus, flags, s.ackSeq))
    }

    /** Makes [player] do [move] right now, whatever they were doing (a stagger, a guard break, a parry). */
    fun force(player: ServerPlayer, move: Move) {
        val s = state(player)
        val now = player.level().gameTime
        val current = s.move
        if (current != null && current.isCritical && s.busy(now)) return
        if (move != Move.PARRY && player.isUsingItem) player.stopUsingItem()
        s.start(move, now, s.seq, 1f)
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, MovePayload(player.id, move.ordinal, s.seq, MovePayload.FORCED, 0f))
    }

    /** Poise: base 25, plus armour and toughness. Heavy moves add hyper armour while they swing. */
    private fun poise(player: ServerPlayer, s: CombatState, now: Long): Float {
        val base = 25f + player.armorValue * 1.5f + player.getAttributeValue(Attributes.ARMOR_TOUGHNESS).toFloat() * 2f
        val move = s.move ?: return base
        val t = s.elapsed(now)
        return if (move.poise > 1f && s.busy(now) && t >= 2f && t <= move.hitTo + 1) base * move.poise else base
    }

    // ---- Damage hooks ----

    fun onIncomingDamage(event: LivingIncomingDamageEvent) {
        val victim = event.entity
        val level = victim.level() as? ServerLevel ?: return
        val source = event.source
        val attacker = source.entity
        val now = level.gameTime
        // Watching a boss's cutscene: nothing lands on someone who can't see it coming (until they strike first)
        if (attacker is ServerPlayer && attacker !== victim) state(attacker).sceneUntil = 0L
        if (victim is ServerPlayer && now < state(victim).sceneUntil && !source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.isCanceled = true
            return
        }
        if (!enabled()) return
        // A staggered or held creature can't land a blow
        if (attacker is LivingEntity && attacker !is Player && source.directEntity === attacker && Stance.isStaggered(attacker)) {
            event.isCanceled = true
            return
        }
        striking?.let { if (victim === it.target && attacker === it.player) event.amount = event.amount * it.multiplier }
        if (victim is ServerPlayer) {
            val s = state(victim)
            if (now >= s.invulnerableFrom && now <= s.invulnerableUntil && dodgeable(source)) {
                event.isCanceled = true
                return
            }
            if (tryParry(victim, s, source, now)) {
                event.isCanceled = true
                return
            }
            if (attacker is LivingEntity && attacker !== victim) s.lastCombat = now
        }
        if (attacker is ServerPlayer && attacker !== victim) state(attacker).lastCombat = now
    }

    /** Rolls dodge blows, arrows and blasts, but not falling, burning, drowning or the void. */
    private fun dodgeable(source: DamageSource): Boolean =
        !source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY) &&
            (source.entity != null || source.directEntity != null || source.`is`(DamageTypeTags.IS_EXPLOSION))

    /** A melee blow in the first moments of raising a shield is parried: no damage, and the attacker reels. */
    private fun tryParry(player: ServerPlayer, s: CombatState, source: DamageSource, now: Long): Boolean {
        if (!player.isUsingItem || !player.useItem.has(DataComponents.BLOCKS_ATTACKS)) return false
        if (player.ticksUsingItem > CombatState.PARRY_WINDOW) return false
        if (source.`is`(DamageTypeTags.IS_PROJECTILE) || source.`is`(DamageTypeTags.IS_EXPLOSION)) return false
        val attacker = source.directEntity as? LivingEntity ?: return false
        if (attacker === player || attacker.distanceTo(player) > 5f + attacker.bbWidth) return false
        if ((attacker as? CombatMob)?.canBeParried() == false) return false
        if (angleTo(player.yHeadRot, attacker.x - player.x, attacker.z - player.z) > 75f) return false
        val level = player.level() as ServerLevel
        level.playSound(null, player.x, player.y, player.z, ModSounds.PARRY.get(), SoundSource.PLAYERS, 1f, 0.95f + level.random.nextFloat() * 0.1f)
        val between = player.position().add(attacker.position()).scale(0.5)
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, between.x, player.getY(0.6), between.z, 16, 0.25, 0.25, 0.25, 0.4)
        if (attacker is ServerPlayer) force(attacker, Move.STAGGER) else Stance.stagger(attacker, Stance.PARRY_TICKS, Stance.PARRIED)
        s.stamina = min(s.maxStamina, s.stamina + 10f)
        s.lastCombat = now
        force(player, Move.PARRY)
        return true
    }

    /** Blocking costs stamina; a shield that runs out of it breaks the guard and lets most of the blow through. */
    fun onShieldBlock(event: LivingShieldBlockEvent) {
        if (!enabled()) return
        val player = event.entity as? ServerPlayer ?: return
        if (!event.blocked || event.blockedDamage <= 0f) return
        val s = state(player)
        val level = player.level() as ServerLevel
        val now = level.gameTime
        val source = event.damageSource
        val cost = if (source.`is`(DamageTypeTags.IS_PROJECTILE)) 4f + event.blockedDamage * 2f
        else (8f + event.blockedDamage * 3.5f) * ((source.entity as? CombatMob)?.guardDamage() ?: 1f)
        s.lastCombat = now
        if (s.stamina >= cost || player.isCreative) {
            s.spendStamina(cost, now)
            s.guardCounterUntil = now + CombatState.GUARD_COUNTER_WINDOW
            level.playSound(null, player.x, player.y, player.z, ModSounds.GUARD.get(), SoundSource.PLAYERS, 0.7f, 0.9f + level.random.nextFloat() * 0.2f)
            return
        }
        s.spendStamina(s.stamina, now)
        event.blockedDamage = event.blockedDamage * 0.4f
        val shield = player.useItem
        level.playSound(null, player.x, player.y, player.z, ModSounds.GUARD_BREAK.get(), SoundSource.PLAYERS, 1f, 1f)
        force(player, Move.GUARD_BREAK)
        player.cooldowns.addCooldown(shield, 40)
    }

    /** Big enough blows in a row stagger a player, interrupting whatever they were doing. */
    fun onDamaged(event: LivingDamageEvent.Post) {
        if (!enabled()) return
        val player = event.entity as? ServerPlayer ?: return
        if (event.inflictedDamage <= 0f || !player.isAlive) return
        val attacker = event.source.entity as? LivingEntity ?: return
        if (attacker === player) return
        val s = state(player)
        val now = player.level().gameTime
        if (now - s.lastPoiseHit > 60) s.poiseDamage = 0f
        s.lastPoiseHit = now
        s.poiseDamage += (attacker as? CombatMob)?.poiseDamage() ?: (event.inflictedDamage * 6f)
        if (s.poiseDamage >= poise(player, s, now)) {
            s.poiseDamage = 0f
            player.level().playSound(null, player.x, player.y, player.z, ModSounds.STAGGER.get(), SoundSource.PLAYERS, 1f, 1f)
            force(player, Move.STAGGER)
        }
    }

    /** Our hits use the move's knockback instead of the full vanilla shove. */
    fun onKnockBack(event: LivingKnockBackEvent) {
        val strike = striking ?: return
        if (event.entity === strike.target) event.strength = event.strength * strike.knockback
    }

    /** Vanilla crits and sweeps are replaced by the moves' own damage and arcs. */
    fun onCriticalHit(event: CriticalHitEvent) {
        if (striking != null) event.isCriticalHit = false
    }

    fun onSweep(event: SweepAttackEvent) {
        if (striking != null) event.isSweeping = false
    }

    /** Vanilla's swish sounds give way to our own hit sounds while one of our blows lands. */
    fun onSound(event: PlayLevelSoundEvent.AtPosition) {
        if (striking == null) return
        val sound = event.sound?.value() ?: return
        if (sound in VANILLA_ATTACK_SOUNDS) event.isCanceled = true
    }

    private val VANILLA_ATTACK_SOUNDS: Set<SoundEvent> by lazy {
        setOf(
            SoundEvents.PLAYER_ATTACK_STRONG, SoundEvents.PLAYER_ATTACK_WEAK, SoundEvents.PLAYER_ATTACK_KNOCKBACK,
            SoundEvents.PLAYER_ATTACK_CRIT, SoundEvents.PLAYER_ATTACK_SWEEP,
        )
    }

    private fun moveSound(move: Move): SoundEvent? = when (move) {
        Move.LIGHT_1, Move.LIGHT_2, Move.LIGHT_3, Move.RUNNING, Move.JUMP -> ModSounds.SWING_LIGHT.get()
        Move.HEAVY, Move.GUARD_COUNTER -> ModSounds.SWING_HEAVY.get()
        Move.SKILL -> ModSounds.WHIRL.get()
        Move.ROLL, Move.ROLL_HEAVY -> ModSounds.ROLL.get()
        Move.BACKSTEP -> ModSounds.BACKSTEP.get()
        else -> null
    }

    /** Resets a player's move and refills them, e.g. after respawning. */
    fun reset(player: Player) {
        val s = state(player)
        s.clear()
        s.stamina = s.maxStamina
        s.invulnerableUntil = 0L
        s.sentStamina = -1f
    }

    /** True while [entity] (a player) is mid-move in a way that should block vanilla attacks, for other systems. */
    fun isBusy(entity: Entity): Boolean = entity is ServerPlayer && state(entity).busy(entity.level().gameTime)
}
