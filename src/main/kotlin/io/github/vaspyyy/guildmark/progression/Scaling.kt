package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.combat.WeaponClass
import io.github.vaspyyy.guildmark.combat.Weapons
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TridentItem

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
    fun kindOf(stack: ItemStack): WeaponKind {
        if (stack.isEmpty) return WeaponKind.UNARMED
        return when (Weapons.classOf(stack)) {
            WeaponClass.GREATSWORD, WeaponClass.AXE, WeaponClass.HAMMER -> WeaponKind.HEAVY
            WeaponClass.STRAIGHT_SWORD, WeaponClass.DAGGER, WeaponClass.CURVED_SWORD, WeaponClass.SPEAR -> WeaponKind.BLADE
            null -> when {
                stack.item is TridentItem -> WeaponKind.HEAVY
                stack.item is BowItem || stack.item is CrossbowItem -> WeaponKind.BOW
                else -> WeaponKind.OTHER
            }
        }
    }

    /** The stats [stack]'s damage follows, and how strongly. */
    fun grades(stack: ItemStack): List<Pair<Stat, Grade>> {
        if (stack.isEmpty) return listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.D)
        return when (Weapons.classOf(stack)) {
            // The mace is the hammer for a quick arm; the war hammers want all the strength there is
            WeaponClass.HAMMER -> listOf(Stat.MIGHT to if (stack.`is`(Items.MACE)) Grade.B else Grade.A)
            WeaponClass.GREATSWORD -> listOf(Stat.MIGHT to Grade.B, Stat.FINESSE to Grade.D)
            WeaponClass.AXE -> listOf(Stat.MIGHT to Grade.C, Stat.FINESSE to Grade.D)
            WeaponClass.SPEAR -> listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.C)
            WeaponClass.CURVED_SWORD -> listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.B)
            WeaponClass.DAGGER -> listOf(Stat.MIGHT to Grade.E, Stat.FINESSE to Grade.B)
            WeaponClass.STRAIGHT_SWORD -> listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.C)
            null -> when {
                stack.item is TridentItem -> listOf(Stat.MIGHT to Grade.D, Stat.FINESSE to Grade.D, Stat.FAITH to Grade.E)
                stack.item is BowItem -> listOf(Stat.FINESSE to Grade.B)
                stack.item is CrossbowItem -> listOf(Stat.FINESSE to Grade.D)
                // Tools and anything else that hits a little harder than a fist
                stack.has(DataComponents.ATTRIBUTE_MODIFIERS) -> listOf(Stat.MIGHT to Grade.E)
                else -> listOf()
            }
        }
    }

    /** Extra share of damage [stack] deals for the stats on [sheet]. */
    fun bonus(sheet: CharacterSheet, stack: ItemStack): Double =
        grades(stack).sumOf { (stat, grade) -> grade.factor * Stat.effective(sheet.stat(stat)) / Stat.FULL }
}
