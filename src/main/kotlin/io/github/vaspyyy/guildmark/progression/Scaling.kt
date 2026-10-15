package io.github.vaspyyy.guildmark.progression

import net.minecraft.core.component.DataComponents
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TridentItem
import net.neoforged.neoforge.common.ItemAbilities

/** How strongly a weapon's damage follows a stat, S (most) to E (least). */
enum class Grade(val factor: Double) {
    S(1.0), A(0.8), B(0.6), C(0.4), D(0.25), E(0.1);
}

/** Which skill a weapon trains and which damage bonus it takes. */
enum class WeaponKind(val skill: Skill?, val bonus: Bonus?) {
    BLADE(Skill.BLADES, Bonus.SWORD_DAMAGE),
    HEAVY(Skill.HEAVY_ARMS, Bonus.HEAVY_DAMAGE),
    BOW(Skill.ARCHERY, Bonus.ARROW_DAMAGE),
    UNARMED(null, Bonus.UNARMED_DAMAGE),
    OTHER(null, null),
}

/**
 * Stat scaling: each weapon's damage grows with one or two stats, by its grade in each. At [Stat.MAX] a
 * grade adds its whole factor (S doubles the damage); the soft caps make the first points count most.
 */
object Scaling {
    fun kindOf(stack: ItemStack): WeaponKind = when {
        stack.isEmpty -> WeaponKind.UNARMED
        stack.`is`(ItemTags.SWORDS) || stack.`is`(ItemTags.SPEARS) -> WeaponKind.BLADE
        stack.`is`(ItemTags.AXES) || stack.`is`(Items.MACE) || stack.item is TridentItem -> WeaponKind.HEAVY
        stack.item is BowItem || stack.item is CrossbowItem -> WeaponKind.BOW
        // Most modded swords answer to the sweep ability
        stack.canPerformAction(ItemAbilities.SWORD_SWEEP) -> WeaponKind.BLADE
        else -> WeaponKind.OTHER
    }

    /** The stats [stack]'s damage follows, and how strongly. */
    fun grades(stack: ItemStack): List<Pair<Stat, Grade>> = when {
        stack.isEmpty -> listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.D)
        stack.`is`(Items.MACE) -> listOf(Stat.MIGHT to Grade.B)
        stack.item is TridentItem -> listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.D, Stat.FAITH to Grade.E)
        stack.`is`(ItemTags.AXES) -> listOf(Stat.MIGHT to Grade.C, Stat.FINESSE to Grade.D)
        stack.`is`(ItemTags.SPEARS) -> listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.C)
        stack.item is BowItem -> listOf(Stat.FINESSE to Grade.B)
        stack.item is CrossbowItem -> listOf(Stat.FINESSE to Grade.D)
        stack.`is`(ItemTags.SWORDS) || stack.canPerformAction(ItemAbilities.SWORD_SWEEP) -> listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.C)
        // Tools and anything else that hits a little harder than a fist
        stack.has(DataComponents.ATTRIBUTE_MODIFIERS) -> listOf(Stat.MIGHT to Grade.E)
        else -> listOf()
    }

    /** Extra share of damage [stack] deals for the stats on [sheet]. */
    fun bonus(sheet: CharacterSheet, stack: ItemStack): Double =
        grades(stack).sumOf { (stat, grade) -> grade.factor * Stat.effective(sheet.stat(stat)) / Stat.FULL }
}
