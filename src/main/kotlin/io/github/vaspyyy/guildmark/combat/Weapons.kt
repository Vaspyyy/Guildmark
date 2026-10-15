package io.github.vaspyyy.guildmark.combat

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Talents
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.ItemAbilities

/**
 * How a held weapon fights: its own light combo, heavy, running, jumping and counter attacks and its weapon
 * skill. The dodges, the flask and the criticals are everyone's. A class's moves are timed for a weapon of
 * attack speed [pace]; slower weapons in it play them slower and quicker ones quicker. The items in a class
 * are those in the item tag guildmark:weapon_class/[id], so datapacks can sort other mods' weapons.
 */
enum class WeaponClass(
    val id: String,
    val pace: Float,
    val lights: List<Move>,
    val heavy: Move,
    val running: Move,
    val jump: Move,
    val counter: Move,
    val skill: Move,
    /** Held in both hands: the off hand closes on the grip, unless it's holding something. */
    val twoHanded: Boolean = false,
    /** Its swings and impacts sound this much higher or lower. */
    val pitch: Float = 1f,
    /** How hard its blows land on screen: -1 for the lightest, 0 for most, up to 2 for the heaviest. */
    val heft: Int = 0,
    /** Ripostes and backstabs with it hit this much harder. */
    val critical: Float = 1f,
) {
    STRAIGHT_SWORD(
        "straight_sword", 1.6f, listOf(Move.LIGHT_1, Move.LIGHT_2, Move.LIGHT_3),
        Move.HEAVY, Move.RUNNING, Move.JUMP, Move.GUARD_COUNTER, Move.SKILL,
    ),
    GREATSWORD(
        "greatsword", 0.8f, listOf(Move.GS_LIGHT_1, Move.GS_LIGHT_2),
        Move.GS_HEAVY, Move.GS_RUNNING, Move.GS_JUMP, Move.GS_COUNTER, Move.GS_SKILL,
        twoHanded = true, pitch = 0.8f, heft = 1,
    ),
    DAGGER(
        "dagger", 2.2f, listOf(Move.DAGGER_LIGHT_1, Move.DAGGER_LIGHT_2, Move.DAGGER_LIGHT_3, Move.DAGGER_LIGHT_4),
        Move.DAGGER_HEAVY, Move.DAGGER_RUNNING, Move.DAGGER_JUMP, Move.DAGGER_COUNTER, Move.DAGGER_SKILL,
        pitch = 1.3f, heft = -1, critical = 1.3f,
    ),
    CURVED_SWORD(
        "curved_sword", 1.8f, listOf(Move.SABRE_LIGHT_1, Move.SABRE_LIGHT_2, Move.SABRE_LIGHT_3),
        Move.SABRE_HEAVY, Move.SABRE_RUNNING, Move.SABRE_JUMP, Move.SABRE_COUNTER, Move.SABRE_SKILL,
        pitch = 1.12f,
    ),
    AXE(
        "axe", 0.9f, listOf(Move.AXE_LIGHT_1, Move.AXE_LIGHT_2, Move.AXE_LIGHT_3),
        Move.AXE_HEAVY, Move.AXE_RUNNING, Move.AXE_JUMP, Move.AXE_COUNTER, Move.AXE_SKILL,
        pitch = 0.92f,
    ),
    HAMMER(
        "hammer", 0.65f, listOf(Move.HAMMER_LIGHT_1, Move.HAMMER_LIGHT_2),
        Move.HAMMER_HEAVY, Move.HAMMER_RUNNING, Move.HAMMER_JUMP, Move.HAMMER_COUNTER, Move.HAMMER_SKILL,
        twoHanded = true, pitch = 0.72f, heft = 2,
    ),
    SPEAR(
        "spear", 1.0f, listOf(Move.SPEAR_LIGHT_1, Move.SPEAR_LIGHT_2, Move.SPEAR_LIGHT_3),
        Move.SPEAR_HEAVY, Move.SPEAR_RUNNING, Move.SPEAR_JUMP, Move.SPEAR_COUNTER, Move.SPEAR_SKILL,
        pitch = 1.05f,
    );

    /** The moves only this class makes. */
    val moves: Set<Move> by lazy { (lights + listOf(heavy, running, jump, counter, skill)).toSet() }

    /** The weapons that fight this way. */
    val tag: TagKey<Item> = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "weapon_class/$id"))

    /** The light attack after [move] in the combo, or the first. */
    fun lightAfter(move: Move?): Move {
        val i = lights.indexOf(move)
        // Running, jumping and counter attacks open the combo, so the next press carries on from there
        val next = if (i >= 0) i + 1 else if (move != null && move in moves) 1 else 0
        return lights[next % lights.size]
    }

    companion object {
        private val OWNER: Map<Move, WeaponClass> by lazy { entries.flatMap { c -> c.moves.map { it to c } }.toMap() }

        /** The class [move] belongs to, or null for a move every weapon shares. */
        fun of(move: Move): WeaponClass? = OWNER[move]

        /** Greatswords, daggers and sabres are swords to enchant, and the mace a hammer: the narrower tags are asked first. */
        val ORDER = listOf(GREATSWORD, DAGGER, CURVED_SWORD, HAMMER, SPEAR, AXE, STRAIGHT_SWORD)
    }
}

object Weapons {
    /** Armor points above which rolls turn into the slower heavy roll. */
    const val HEAVY_ROLL_ARMOR = 16

    /** Vanilla's spears keep most of their damage for the charge; their thrusts here hit this much harder than the jab. */
    private const val SPEAR_THRUST = 1.7f

    /** The weapon class [stack] fights as, or null if it keeps vanilla's swing (tools, bows, tridents...). */
    fun classOf(stack: ItemStack): WeaponClass? {
        if (stack.isEmpty) return null
        for (c in WeaponClass.ORDER) if (stack.`is`(c.tag)) return c
        // Most modded swords answer to the sweep ability
        return if (stack.canPerformAction(ItemAbilities.SWORD_SWEEP)) WeaponClass.STRAIGHT_SWORD else null
    }

    /**
     * How fast moves play with the held weapon: at full speed with a weapon as quick as its class's pace,
     * down to 75% for a slower one, then quicker with Finesse and the tree's tempo nodes.
     */
    fun tempo(player: Player): Float {
        val pace = (classOf(player.mainHandItem) ?: WeaponClass.STRAIGHT_SWORD).pace
        val weapon = Mth.clamp((player.getAttributeValue(Attributes.ATTACK_SPEED) / pace).toFloat(), 0.75f, 1.15f)
        return Mth.clamp(weapon * (1f + Talents[player, Bonus.TEMPO].toFloat()), 0.6f, 1.4f)
    }

    /** How much harder than its own attack damage [stack] hits with the moves. */
    fun damageFactor(stack: ItemStack): Float =
        if (stack.has(DataComponents.KINETIC_WEAPON) && classOf(stack) == WeaponClass.SPEAR) SPEAR_THRUST else 1f

    /** Armor points a player can wear before rolls turn heavy: more with Endurance and the armour nodes. */
    fun rollLoad(player: Player): Int = HEAVY_ROLL_ARMOR + Talents[player, Bonus.ROLL_LOAD].toInt()

    /** Rolls get slower and shorter in heavy armour (always, under Iron Fortress). */
    fun rollFor(player: Player): Move =
        if (player.armorValue > rollLoad(player) || Talents.build(player).has(Bonus.HEAVY_ROLL)) Move.ROLL_HEAVY else Move.ROLL
}
