package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.companion.Companions
import io.github.vaspyyy.guildmark.faction.FactionRegistry
import io.github.vaspyyy.guildmark.faction.FactionState
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatState
import io.github.vaspyyy.guildmark.combat.Move
import io.github.vaspyyy.guildmark.combat.MoveKind
import io.github.vaspyyy.guildmark.magic.MagicRegistry
import io.github.vaspyyy.guildmark.magic.Spellbook
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.wardfire.WardensFlask
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.tags.EntityTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.Tags
import net.neoforged.neoforge.common.damagesource.DamageContainer
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent
import net.neoforged.neoforge.event.entity.living.LivingEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A player's [Build], kept until the skills or sheet it came from are replaced. */
class BuildCache {
    var skills: SkillState? = null
    var sheet: CharacterSheet? = null
    var factions: FactionState? = null
    var build: Build = Build.EMPTY
}

/** Skill XP earned since the last count, and the timers of a few bonuses. Server side, never saved. */
class SkillBuffer {
    val pending = DoubleArray(Skill.entries.size)
    var regenTimer = 0
    /** Where sneaking near foes last gave stealth XP: more needs the player to have moved on. */
    var sneakFrom: Vec3? = null
    /** Already told that there's guild XP enough to grow at a Wardfire. */
    var toldReady = false
    /** An arrow that may come back: its item and how many the player had when it flew. */
    var refundItem: ItemStack = ItemStack.EMPTY
    var refundCount = -1
    /** Potions' worth of alchemy XP in store, and when the store last grew (see [Work.onBrewed]). */
    var brews = 6
    var brewsAt = 0L
}

/**
 * What the stats, skills and tree nodes do. Attribute bonuses become modifiers; the rest are read here
 * (damage dealt and taken, healing, food, potions, bows) and by the combat, crafts and contracts.
 */
object Talents {
    fun build(player: Player): Build {
        val cache = player.getData(ModAttachments.BUILD)
        val skills = player.getData(ModAttachments.SKILLS)
        val sheet = player.getData(ModAttachments.SHEET)
        val factions = player.getData(FactionRegistry.FACTIONS)
        if (cache.skills !== skills || cache.sheet !== sheet || cache.factions !== factions) {
            cache.build = Build.of(skills, sheet, factions)
            cache.skills = skills
            cache.sheet = sheet
            cache.factions = factions
        }
        return cache.build
    }

    operator fun get(player: Player, bonus: Bonus): Double = build(player)[bonus]

    fun sheet(player: Player): CharacterSheet = player.getData(ModAttachments.SHEET)

    /** The id of the attribute modifier [bonus] is put in place with. */
    fun modifierId(bonus: Bonus): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "build/${bonus.id}")

    /** Puts the build in place: attribute modifiers, stamina, focus and the flask's size. On login, respawn and every change. */
    fun apply(player: Player) {
        val build = build(player)
        val before = player.maxHealth
        for (bonus in Bonus.entries) {
            val attribute = bonus.attribute ?: continue
            val instance = player.getAttribute(attribute) ?: continue
            val id = modifierId(bonus)
            val amount = build[bonus]
            val existing = instance.getModifier(id)
            // Unchanged modifiers are left alone, so the client isn't sent attributes it already has
            if (abs(amount) < 1e-9) {
                if (existing != null) instance.removeModifier(id)
            } else if (existing == null || existing.amount() != amount || existing.operation() != bonus.operation) {
                // Saved with the player, so health loads against the right maximum at login
                instance.addOrReplacePermanentModifier(AttributeModifier(id, amount, bonus.operation))
            }
        }
        // Health the build adds comes filled: after a respawn, a stat raised or a node learned
        val after = player.maxHealth
        if (player.isAlive && after > before) player.health = min(after, player.health + (after - before))
        if (player.health > after) player.health = after
        val combat = Combat.state(player)
        combat.maxStamina = CombatState.MAX_STAMINA + build[Bonus.STAMINA].toFloat().coerceAtLeast(-50f)
        combat.maxFocus = CombatState.MAX_FOCUS + build[Bonus.FOCUS].toFloat().coerceAtLeast(-20f)
        combat.stamina = min(combat.stamina, combat.maxStamina)
        combat.focus = min(combat.focus, combat.maxFocus)
        WardensFlask.resize(player)
        // Spell slots lost with a node: the last spells attuned fall out
        if (player is ServerPlayer) Spellbook.fit(player)
    }

    // ---- For the combat ----

    /** Stamina [move] costs [player]. */
    fun staminaCost(player: Player, move: Move): Float {
        val build = build(player)
        val change = when {
            move.isRoll -> build[Bonus.ROLL_COST]
            move.isAttack -> build[Bonus.ATTACK_COST]
            else -> 0.0
        }
        return (move.stamina * (1.0 + change)).toFloat().coerceAtLeast(0f)
    }

    fun focusCost(player: Player, move: Move): Float {
        val change = when (move.kind) {
            MoveKind.SKILL -> build(player)[Bonus.SKILL_COST]
            MoveKind.SPELL -> build(player)[Bonus.SPELL_COST]
            else -> 0.0
        }
        // Lucan, Sworn and beside them, shares the weight of a spell
        val shared = if (move.kind == MoveKind.SPELL) Companions.focusFactor(player) else 1f
        return (move.focus * (1.0 + change)).toFloat().coerceAtLeast(0f) * shared
    }

    /** How much harder [move] hits, from the nodes for ripostes, backstabs, weapon skills, charged heavies and guard counters. */
    fun moveDamage(player: Player, move: Move): Float {
        val build = build(player)
        val bonus = when {
            move == Move.RIPOSTE -> build[Bonus.RIPOSTE_DAMAGE]
            move == Move.BACKSTAB -> build[Bonus.BACKSTAB_DAMAGE]
            move.kind == MoveKind.SKILL -> build[Bonus.SKILL_DAMAGE]
            move.kind == MoveKind.HEAVY -> build[Bonus.CHARGED_DAMAGE]
            move.kind == MoveKind.COUNTER -> build[Bonus.COUNTER_DAMAGE]
            else -> 0.0
        }
        return (1.0 + bonus).toFloat().coerceAtLeast(0.1f)
    }

    fun stanceDamage(player: Player): Float = (1.0 + build(player)[Bonus.STANCE_DAMAGE]).toFloat().coerceAtLeast(0.1f)

    // ---- Damage ----

    fun isBoss(entity: LivingEntity): Boolean =
        entity.typeHolder().`is`(Tags.EntityTypes.BOSSES) || entity.hasData(ModAttachments.LAIR_BOSS)

    /** Magic, for the resistances: and spells, which reckon their own bonuses (see magic/Magic.hurt). */
    private fun isMagic(source: DamageSource): Boolean =
        source.`is`(DamageTypeTags.WITCH_RESISTANT_TO) || source.`is`(DamageTypes.WITHER) || source.`is`(DamageTypes.DRAGON_BREATH) ||
            source.`is`(MagicRegistry.IS_SPELL)

    /** Before damage lands: the attacker's bonuses and the victim's resistances. */
    fun onIncomingDamage(event: LivingIncomingDamageEvent) {
        val victim = event.entity
        if (victim.level().isClientSide) return
        val source = event.source
        var amount = event.amount.toDouble()
        val attacker = source.entity as? Player
        if (attacker != null && attacker !== victim) amount *= dealt(attacker, victim, source)
        if (victim is Player && !source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) amount *= taken(victim, source)
        if (amount.toFloat() != event.amount) event.amount = amount.toFloat().coerceAtLeast(0f)
    }

    private fun dealt(player: Player, victim: LivingEntity, source: DamageSource): Double {
        // Thorns, magic and lightning (the relics' own tricks) take no bonuses
        if (source.`is`(DamageTypes.THORNS) || isMagic(source) || source.`is`(DamageTypes.LIGHTNING_BOLT)) return 1.0
        val build = build(player)
        val sheet = sheet(player)
        val direct = source.directEntity
        val weapon = source.weaponItem ?: ItemStack.EMPTY
        var bonus = 0.0
        when {
            direct === player -> {
                val kind = Scaling.kindOf(weapon)
                bonus += build[Bonus.MELEE_DAMAGE]
                if (kind != WeaponKind.BOW) kind.bonus?.let { bonus += build[it] }
                bonus += Scaling.bonus(sheet, weapon)
            }
            direct is AbstractArrow -> {
                // Arrows and bolts take the bow's bonuses; a thrown trident its own
                val kind = Scaling.kindOf(weapon)
                bonus += if (kind == WeaponKind.BOW) build[Bonus.ARROW_DAMAGE] else kind.bonus?.let { build[it] } ?: 0.0
                bonus += Scaling.bonus(sheet, weapon)
            }
            // Fire, falling blocks, explosions the player set off: as they are
            else -> return 1.0
        }
        if (victim.typeHolder().`is`(EntityTypeTags.UNDEAD)) bonus += build[Bonus.UNDEAD_DAMAGE]
        if (isBoss(victim)) bonus += build[Bonus.BOSS_DAMAGE]
        if (victim is Animal || victim.typeHolder().`is`(EntityTypeTags.ARTHROPOD)) bonus += build[Bonus.BEAST_DAMAGE]
        if (victim.health < victim.maxHealth * 0.3f) bonus += build[Bonus.EXECUTE_DAMAGE]
        if (victim.health >= victim.maxHealth) bonus += build[Bonus.FIRST_STRIKE]
        if (player.health < player.maxHealth * 0.35f) bonus += build[Bonus.DESPERATE_DAMAGE]
        if (victim is Mob && victim.target !== player) bonus += build[Bonus.AMBUSH_DAMAGE]
        var multiplier = max(0.1, 1.0 + bonus)
        val twice = build[Bonus.DOUBLE_STRIKE]
        if (twice > 0 && player.random.nextDouble() < twice) {
            multiplier *= 2.0
            (player.level() as? ServerLevel)?.sendParticles(ParticleTypes.ENCHANTED_HIT, victim.x, victim.getY(0.6), victim.z, 12, 0.3, 0.3, 0.3, 0.2)
        }
        return multiplier
    }

    private fun taken(player: Player, source: DamageSource): Double {
        val build = build(player)
        var change = build[Bonus.DAMAGE_TAKEN]
        if (source.`is`(DamageTypeTags.IS_FIRE)) change += build[Bonus.FIRE_TAKEN]
        if (source.`is`(DamageTypeTags.IS_EXPLOSION)) change += build[Bonus.BLAST_TAKEN]
        if (source.`is`(DamageTypeTags.IS_PROJECTILE)) change += build[Bonus.PROJECTILE_TAKEN]
        if (isMagic(source)) change += build[Bonus.MAGIC_TAKEN]
        // Vigil's vow
        if (player.hasEffect(MagicRegistry.VIGIL)) change -= VIGIL_GUARD
        return (1.0 + change).coerceIn(0.1, 3.0)
    }

    /** After damage lands: lifesteal and thorns, and the skill XP the blow earned. */
    fun onDamaged(event: LivingDamageEvent.Post) {
        val victim = event.entity
        if (victim.level().isClientSide) return
        val dealt = event.inflictedDamage
        if (dealt <= 0f) return
        val source = event.source
        val attacker = source.entity as? ServerPlayer
        if (attacker != null && attacker !== victim) {
            if (source.directEntity === attacker) {
                val steal = build(attacker)[Bonus.LIFESTEAL]
                if (steal > 0) attacker.heal((dealt * steal).toFloat())
            }
            Skills.onHit(attacker, victim, source, dealt)
        }
        if (victim is ServerPlayer) {
            val hitter = source.entity as? LivingEntity
            val thorns = build(victim)[Bonus.THORNS]
            if (thorns > 0 && hitter != null && hitter !== victim && source.directEntity === hitter) {
                val level = victim.level() as ServerLevel
                hitter.hurtServer(level, level.damageSources().thorns(victim), (dealt * thorns).toFloat())
            }
            val absorbed = event.getReduction(DamageContainer.Reduction.ARMOR)
            if (absorbed > 0f && hitter != null) Skills.gain(victim, Skill.ARMOR, absorbed * 1.5)
        }
    }

    /** Undying: once between rests a killing blow leaves the player standing with some health. */
    fun onDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (event.source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return
        if (!build(player).has(Bonus.UNDYING)) return
        val sheet = sheet(player)
        if (sheet.undyingSpent) return
        event.isCanceled = true
        player.health = max(1f, player.maxHealth * 0.3f)
        player.setData(ModAttachments.SHEET, sheet.copy(undyingSpent = true))
        player.invulnerableTime = 40
        val level = player.level()
        level.playSound(null, player.x, player.y, player.z, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.7f, 0.7f)
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.x, player.y + 1.0, player.z, 40, 0.4, 0.6, 0.4, 0.3)
        player.sendOverlayMessage(Component.translatable("message.guildmark.undying").withColor(0xE8C26A))
    }

    /** A rest at a Wardfire: Undying is ready again. */
    fun onRest(player: ServerPlayer) {
        val sheet = sheet(player)
        if (sheet.undyingSpent) player.setData(ModAttachments.SHEET, sheet.copy(undyingSpent = false))
    }

    // ---- Every tick ----

    fun tick(player: ServerPlayer) {
        val buffer = player.getData(ModAttachments.SKILL_BUFFER)
        val build = build(player)
        // Health mends slowly out of a fight
        val regen = build[Bonus.REGEN]
        if (regen > 0 && ++buffer.regenTimer >= REGEN_TICKS) {
            buffer.regenTimer = 0
            if (player.health < player.maxHealth && !Combat.state(player).inCombat(player.level().gameTime)) player.heal(regen.toFloat())
        }
        // An arrow that came back: if one was really used, the quiver gets it again
        if (buffer.refundCount >= 0) {
            val count = countOf(player, buffer.refundItem)
            if (count < buffer.refundCount) player.inventory.add(buffer.refundItem.copyWithCount(1))
            buffer.refundCount = -1
            buffer.refundItem = ItemStack.EMPTY
        }
    }

    private fun countOf(player: Player, item: ItemStack): Int {
        var n = 0
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (ItemStack.isSameItemSameComponents(stack, item)) n += stack.count
        }
        return n
    }

    // ---- Stealth, XP orbs, food, potions, bows ----

    /** Sneaking players are harder to notice. */
    fun onVisibility(event: LivingEvent.LivingVisibilityEvent) {
        val player = event.entity as? Player ?: return
        if (!player.isCrouching) return
        val stealth = build(player)[Bonus.STEALTH]
        if (stealth > 0) event.modifyVisibility(1.0 - min(0.85, stealth))
    }

    fun onPickupXp(event: PlayerXpEvent.PickupXp) {
        val player = event.entity as? ServerPlayer ?: return
        val bonus = build(player)[Bonus.XP_ORBS]
        if (bonus <= 0) return
        val orb = event.orb
        val extra = orb.value * bonus
        val whole = floor(extra).toInt() + (if (player.random.nextDouble() < extra - floor(extra)) 1 else 0)
        // Paid apart from the orb: a merged orb pays its value once per touch, so raising it would compound
        if (whole > 0) player.giveExperiencePoints(whole)
    }

    /** Food fills more and can heal; potions you drink last longer and sometimes come out stronger. */
    fun onFinishUsing(event: LivingEntityUseItemEvent.Finish) {
        val player = event.entity as? ServerPlayer ?: return
        val stack = event.item
        val build = build(player)
        stack.get(DataComponents.FOOD)?.let { food ->
            val extra = food.saturation() * build[Bonus.FOOD_SATURATION]
            if (extra > 0) player.foodData.setSaturation(min(player.foodData.saturationLevel + extra.toFloat(), player.foodData.foodLevel.toFloat()))
            val heal = build[Bonus.FOOD_HEAL]
            if (heal > 0) player.heal(heal.toFloat())
        }
        if (!stack.`is`(Items.POTION)) return
        val contents = stack.get(DataComponents.POTION_CONTENTS) ?: return
        val longer = build[Bonus.POTION_DURATION]
        val stronger = build[Bonus.POTION_POTENCY]
        if (longer <= 0 && stronger <= 0) return
        for (effect in contents.allEffects) {
            if (effect.effect.value().category == MobEffectCategory.HARMFUL || effect.effect.value().isInstantaneous) continue
            val duration = (effect.duration * (1.0 + longer)).toInt()
            val amplifier = if (stronger > 0 && player.random.nextDouble() < stronger) min(effect.amplifier + 1, 3) else effect.amplifier
            player.addEffect(MobEffectInstance(effect.effect, duration, amplifier, effect.isAmbient, effect.isVisible, effect.showIcon()))
        }
    }

    /** Bows draw (and crossbows load) faster. */
    fun onUsingTick(event: LivingEntityUseItemEvent.Tick) {
        val player = event.entity as? Player ?: return
        val item = event.item.item
        if (item !is BowItem && item !is CrossbowItem) return
        val faster = build(player)[Bonus.DRAW_SPEED]
        if (faster > 0 && player.random.nextDouble() < min(0.6, faster) && event.duration > 1) event.duration = event.duration - 1
    }

    /** Now and then an arrow is found again: given back next tick if the shot used one. */
    fun onArrowLoose(event: ArrowLooseEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.isCreative || !event.hasAmmo() || event.charge < 3) return
        val chance = build(player)[Bonus.ARROW_REFUND]
        if (chance <= 0 || player.random.nextDouble() >= chance) return
        val ammo = player.getProjectile(event.bow)
        if (ammo.isEmpty) return
        val buffer = player.getData(ModAttachments.SKILL_BUFFER)
        buffer.refundItem = ammo.copyWithCount(1)
        buffer.refundCount = countOf(player, ammo)
    }

    /** Ticks between Regen's mending. */
    private const val REGEN_TICKS = 100
    /** The share of damage Vigil keeps off. */
    private const val VIGIL_GUARD = 0.2
}
