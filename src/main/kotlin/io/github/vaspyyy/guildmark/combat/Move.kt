package io.github.vaspyyy.guildmark.combat

import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Talents
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.ItemAbilities

/**
 * Everything one combat move does, counted in ticks from its start at normal speed. Client and server
 * both read these, so a move times the same on both sides. Heavier weapons play moves slower (see [Weapons.tempo]).
 */
enum class Move(
    /** The animation, under assets/guildmark/neoforge/animations/entity/player/ (and first_person/). */
    val anim: String,
    val length: Int,
    /** The ticks in which the swing can land (inclusive), or -1 for moves that don't hit. */
    val hitFrom: Int = -1,
    val hitTo: Int = -1,
    val stamina: Float = 0f,
    val focus: Float = 0f,
    /** Multiplies the weapon's own damage. */
    val damage: Float = 1f,
    /** Stance damage dealt to whatever it hits. */
    val stance: Float = 0f,
    /** Multiplies the knockback the hit deals. */
    val knockback: Float = 1f,
    val reach: Double = 3.2,
    /** Half the swing's arc in degrees; 180 hits all around. */
    val arc: Float = 60f,
    /** From this tick the next attack can follow (a buffered press starts it here). */
    val chainFrom: Int = length,
    /** From this tick a roll can cut the move short. */
    val dodgeFrom: Int = length,
    /** How much of the player's walking input still applies during the move. */
    val moveSpeed: Float = 1f,
    /** A forward push at [lungeAt], in blocks per tick. */
    val lunge: Float = 0f,
    val lungeAt: Int = 0,
    /** Ticks the player can't be hurt in, or -1. */
    val iFrom: Int = -1,
    val iTo: Int = -1,
    /** Holding the attack button pauses the move at this tick for up to [maxCharge] ticks. */
    val chargeAt: Int = -1,
    val maxCharge: Int = 0,
    /** Hyper armour: the player's poise is multiplied by this while the move winds up and swings. */
    val poise: Float = 1f,
    /** Whether the client may ask for this move; the rest only start on the server's say-so. */
    val requestable: Boolean = true,
) {
    LIGHT_1("light_1", 13, 4, 5, stamina = 12f, stance = 12f, knockback = 0.35f, chainFrom = 7, dodgeFrom = 7, moveSpeed = 0.3f, lunge = 0.12f, lungeAt = 2),
    LIGHT_2("light_2", 13, 4, 5, stamina = 12f, stance = 12f, knockback = 0.35f, chainFrom = 7, dodgeFrom = 7, moveSpeed = 0.3f, lunge = 0.12f, lungeAt = 2),
    LIGHT_3("light_3", 17, 6, 7, stamina = 15f, damage = 1.3f, stance = 18f, knockback = 0.8f, reach = 3.5, arc = 25f, chainFrom = 11, dodgeFrom = 10, moveSpeed = 0.25f, lunge = 0.3f, lungeAt = 4),
    HEAVY("heavy", 22, 9, 10, stamina = 22f, damage = 1.6f, stance = 28f, reach = 3.4, arc = 45f, chainFrom = 15, dodgeFrom = 13, moveSpeed = 0.15f, lunge = 0.2f, lungeAt = 8, chargeAt = 6, maxCharge = 14, poise = 1.8f),
    RUNNING("running", 15, 4, 6, stamina = 15f, damage = 1.25f, stance = 18f, knockback = 0.9f, reach = 3.4, arc = 50f, chainFrom = 10, dodgeFrom = 9, moveSpeed = 0.5f, lunge = 0.5f, lungeAt = 1),
    JUMP("jump", 16, 5, 8, stamina = 18f, damage = 1.4f, stance = 26f, knockback = 0.7f, reach = 3.0, chainFrom = 12, dodgeFrom = 12, moveSpeed = 0.6f, poise = 1.5f),
    GUARD_COUNTER("guard_counter", 18, 7, 8, stamina = 18f, damage = 1.5f, stance = 34f, knockback = 1.1f, reach = 3.4, arc = 50f, chainFrom = 13, dodgeFrom = 12, moveSpeed = 0.2f, lunge = 0.25f, lungeAt = 5, poise = 1.5f),
    SKILL("skill", 24, 8, 12, stamina = 20f, focus = 12f, damage = 1.6f, stance = 30f, knockback = 1.2f, reach = 3.4, arc = 180f, chainFrom = 18, dodgeFrom = 17, moveSpeed = 0.2f, lunge = 0.15f, lungeAt = 7, poise = 1.8f),
    ROLL("roll", 12, stamina = 18f, chainFrom = 9, dodgeFrom = 10, moveSpeed = 0f, iFrom = 1, iTo = 8),
    ROLL_HEAVY("roll_heavy", 15, stamina = 18f, chainFrom = 12, dodgeFrom = 13, moveSpeed = 0f, iFrom = 2, iTo = 7),
    BACKSTEP("backstep", 9, stamina = 12f, chainFrom = 6, dodgeFrom = 7, moveSpeed = 0f, iFrom = 1, iTo = 4),
    /** A draught from the Warden's Flask: slow on your feet until it's down, and staggered before [DRINK_AT], nothing is drunk. */
    DRINK("drink", 30, chainFrom = 26, dodgeFrom = 22, moveSpeed = 0.35f),
    RIPOSTE("riposte", 32, 12, 22, damage = 1.5f, knockback = 0.1f, moveSpeed = 0f, iFrom = 0, iTo = 32, requestable = false),
    BACKSTAB("backstab", 28, 12, 12, damage = 2.5f, moveSpeed = 0f, iFrom = 0, iTo = 28, requestable = false),
    PARRY("parry", 9, moveSpeed = 0.4f, chainFrom = 3, dodgeFrom = 3, requestable = false),
    STAGGER("stagger", 14, moveSpeed = 0f, requestable = false),
    GUARD_BREAK("guard_break", 26, moveSpeed = 0f, requestable = false);

    val hits: Boolean get() = hitFrom >= 0
    val isRoll: Boolean get() = this == ROLL || this == ROLL_HEAVY || this == BACKSTEP
    val isAttack: Boolean get() = hits && requestable
    val isCritical: Boolean get() = this == RIPOSTE || this == BACKSTAB
    /** Light-type presses that can turn into a riposte or backstab when the server finds an opening. */
    val canBecomeCritical: Boolean get() = this == LIGHT_1 || this == LIGHT_2 || this == LIGHT_3 || this == RUNNING || this == GUARD_COUNTER

    companion object {
        private val BY_ID = entries.toTypedArray()
        fun byId(id: Int): Move? = BY_ID.getOrNull(id)

        /** The tick a flask draught takes hold. Knocked out of the move before it, the draught stays in the flask. */
        const val DRINK_AT = 15

        /** The riposte's two blows: the stab and the wrench free. */
        val RIPOSTE_HITS = intArrayOf(12, 22)
        val RIPOSTE_DAMAGE = floatArrayOf(1.2f, 1.8f)

        /** Roll and backstep speed per tick, in blocks; they glide to a stop after the listed ticks. */
        fun dodgeSpeeds(move: Move): FloatArray = when (move) {
            ROLL -> floatArrayOf(0.55f, 0.62f, 0.62f, 0.58f, 0.52f, 0.46f, 0.38f, 0.3f, 0.2f)
            ROLL_HEAVY -> floatArrayOf(0.4f, 0.44f, 0.44f, 0.4f, 0.36f, 0.3f, 0.24f, 0.18f, 0.12f)
            BACKSTEP -> floatArrayOf(0.5f, 0.48f, 0.4f, 0.3f, 0.18f)
            else -> FloatArray(0)
        }
    }
}

/** How a held item fights. Only one class has its own move set so far; more come with Phase 4. */
enum class WeaponClass {
    STRAIGHT_SWORD;
}

object Weapons {
    /** Armor points above which rolls turn into the slower heavy roll. */
    const val HEAVY_ROLL_ARMOR = 16

    /** The weapon class [stack] fights as, or null if it keeps vanilla attacks (tools, maces, spears, bows...). */
    fun classOf(stack: ItemStack): WeaponClass? = when {
        stack.isEmpty -> null
        stack.`is`(ItemTags.SWORDS) || stack.`is`(ItemTags.AXES) -> WeaponClass.STRAIGHT_SWORD
        // Most modded swords answer to the sweep ability
        stack.canPerformAction(ItemAbilities.SWORD_SWEEP) -> WeaponClass.STRAIGHT_SWORD
        else -> null
    }

    /**
     * How fast moves play with the held weapon: swords at full speed, axes and slower weapons down to 70%,
     * then quicker with Finesse and the tree's tempo nodes.
     */
    fun tempo(player: Player): Float {
        val weapon = Mth.clamp((player.getAttributeValue(Attributes.ATTACK_SPEED) / 1.6).toFloat(), 0.7f, 1.15f)
        return Mth.clamp(weapon * (1f + Talents[player, Bonus.TEMPO].toFloat()), 0.6f, 1.4f)
    }

    /** Armor points a player can wear before rolls turn heavy: more with Endurance and the armour nodes. */
    fun rollLoad(player: Player): Int = HEAVY_ROLL_ARMOR + Talents[player, Bonus.ROLL_LOAD].toInt()

    /** Rolls get slower and shorter in heavy armour (always, under Iron Fortress). */
    fun rollFor(player: Player): Move =
        if (player.armorValue > rollLoad(player) || Talents.build(player).has(Bonus.HEAVY_ROLL)) Move.ROLL_HEAVY else Move.ROLL
}
