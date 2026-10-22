package io.github.vaspyyy.guildmark.cooking.meal

import io.github.vaspyyy.guildmark.cooking.Family
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory

/**
 * A meal's effect, or the cold's (living-world/cook/design.md 4.2): one class for the five, told apart by [family]
 * (Warmth, Hearty, Steady, Provisioned; null for Chilled). The amplifier carries the tier and a variant bit. Part F's;
 * until it lands, each is an effect that nothing applies and that does nothing if it is applied by hand.
 */
class CookEffect(category: MobEffectCategory, color: Int, val family: Family?) : MobEffect(category, color)
