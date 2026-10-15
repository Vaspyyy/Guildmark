package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.ItemStack
import kotlin.math.floor
import kotlin.math.min

/**
 * Skill XP: earned by doing (see [Work] and the combat hooks), counted up as it comes and handed out
 * every [FLUSH_TICKS] so the client hears about it a couple of times a second at most.
 */
object Skills {
    fun get(player: Player): SkillState = player.getData(ModAttachments.SKILLS)

    fun level(player: Player, skill: Skill): Int = get(player).level(skill)

    /** [amount] skill XP for [skill], before the tree's and the config's multipliers. Server side. */
    fun gain(player: Player, skill: Skill, amount: Double) {
        if (amount <= 0.0 || player !is ServerPlayer || player.isCreative || player.isSpectator) return
        val multiplier = (1.0 + Talents.build(player).skillXp(skill)).coerceAtLeast(0.0) * GuildmarkConfig.SKILL_XP_MULTIPLIER.get()
        player.getData(ModAttachments.SKILL_BUFFER).pending[skill.ordinal] += amount * multiplier
    }

    fun tick(player: ServerPlayer) {
        if (player.tickCount % STEALTH_TICKS == 7) sneaking(player)
        if (player.tickCount % FLUSH_TICKS != 0) return
        val buffer = player.getData(ModAttachments.SKILL_BUFFER)
        var state: SkillState? = null
        val risen = ArrayList<Triple<Skill, Int, Int>>()
        for (skill in Skill.entries) {
            val pending = buffer.pending[skill.ordinal]
            if (pending < 1.0) continue
            val whole = floor(pending).toInt()
            buffer.pending[skill.ordinal] = pending - whole
            val current = state ?: get(player)
            val before = current.level(skill)
            if (before >= Skill.MAX) continue
            val xp = min(current.xp(skill).toLong() + whole, Skill.xpAt(Skill.MAX).toLong()).toInt()
            state = SkillState(current.xp + (skill.id to xp))
            val after = Skill.levelOf(xp)
            if (after > before) risen.add(Triple(skill, before, after))
        }
        val changed = state ?: return
        player.setData(ModAttachments.SKILLS, changed)
        if (risen.isEmpty()) return
        for ((skill, before, after) in risen) announce(player, skill, before, after)
        Talents.apply(player)
    }

    /** A skill rose from [before] to [after]: say so, and pay the guild XP and any milestone point it earned. */
    private fun announce(player: ServerPlayer, skill: Skill, before: Int, after: Int) {
        player.sendOverlayMessage(Component.translatable("message.guildmark.skill_up", skill.title, after).withColor(GOLD))
        val milestone = Skill.milestonesAt(after) > Skill.milestonesAt(before)
        val level = player.level()
        if (milestone) {
            player.sendSystemMessage(Component.translatable("message.guildmark.skill_milestone", skill.title, after, Component.keybind("key.guildmark.ledger")).withColor(GOLD))
            level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.5f, 1.2f)
        } else {
            level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.3f, 1.3f + 0.4f * after / Skill.MAX)
        }
        // Every skill level is worth guild XP equal to the level reached
        var xp = 0
        for (l in before + 1..after) xp += l
        Progression.addXp(player, xp)
    }

    // ---- Combat ----

    /** A blow [player] landed on [victim]: XP for the weapon's skill, and for stealth if it caught the victim unaware. */
    fun onHit(player: ServerPlayer, victim: LivingEntity, source: DamageSource, dealt: Float) {
        if (victim is ArmorStand || victim is Player && !victim.isAlive) return
        val xp = min(dealt, victim.maxHealth).toDouble().coerceAtMost(MAX_HIT_XP)
        val direct = source.directEntity
        val kind = Scaling.kindOf(source.weaponItem ?: ItemStack.EMPTY)
        when {
            direct is AbstractArrow -> gain(player, if (kind == WeaponKind.HEAVY) Skill.HEAVY_ARMS else Skill.ARCHERY, xp * 1.2)
            direct === player -> kind.skill?.let { if (it != Skill.ARCHERY) gain(player, it, xp) }
        }
        if (victim is Mob && victim.target !== player && player.isCrouching) gain(player, Skill.STEALTH, xp * 0.6)
    }

    /** A kill: a little more XP for the weapon's skill on top of the blows. */
    fun onKill(player: ServerPlayer, victim: LivingEntity, source: DamageSource) {
        if (victim is ArmorStand) return
        val xp = min(victim.maxHealth * 0.4, MAX_KILL_XP.toDouble())
        val kind = Scaling.kindOf(source.weaponItem ?: ItemStack.EMPTY)
        val skill = when {
            source.directEntity is AbstractArrow && kind != WeaponKind.HEAVY -> Skill.ARCHERY
            else -> kind.skill
        } ?: return
        gain(player, skill, xp)
    }

    /** Sneaking about near monsters that haven't noticed you. */
    private fun sneaking(player: ServerPlayer) {
        if (!player.isCrouching || player.isSpectator) return
        val near = player.level().getEntitiesOfClass(Mob::class.java, player.boundingBox.inflate(12.0)) { it is Enemy && it.isAlive }
        if (near.isEmpty() || near.any { it.target === player }) return
        gain(player, Skill.STEALTH, 1.0 + min(near.size, 4) * 0.25)
    }

    /** Ticks between skill XP counts. */
    private const val FLUSH_TICKS = 10
    private const val STEALTH_TICKS = 40
    private const val MAX_HIT_XP = 30.0
    private const val MAX_KILL_XP = 60
    private const val GOLD = 0xE8C26A
}
