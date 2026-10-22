package io.github.vaspyyy.guildmark.cooking.meal

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.Family
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes

/**
 * A meal's effect, or the cold's (living-world/cook/design.md 4.2): one class for the five, told apart by [family]
 * (Warmth, Hearty, Steady, Provisioned; null for Chilled). The amplifier carries the strength, as [Meals.Boon] writes
 * it, and the numbers are read from it by [Meals] where the game's own code asks (stamina, poise, food) and by the
 * effect's own tick where it acts alone: Warmth keeps the frost off, Provisioned gives food back. Hearty is a
 * plain vanilla attribute effect. Every one ends at its next tick when its switch is off ("off = as it was").
 */
class CookEffect(category: MobEffectCategory, color: Int, val family: Family?) : MobEffect(category, color) {
    init {
        // Hearty: +1 maximum health a step of amplifier, the vanilla way (the amount times amplifier + 1), so its
        // amplifier is the health it gives less one (4.2)
        if (family == Family.HEARTY) addAttributeModifier(Attributes.MAX_HEALTH, HEARTY_MODIFIER, 1.0, AttributeModifier.Operation.ADD_VALUE)
    }

    /** Warmth every tick, so powder snow never builds a frost; the rest once a second. */
    override fun shouldApplyEffectTickThisTick(duration: Int, amplifier: Int): Boolean = family == Family.WARMTH || duration % 20 == 0

    override fun applyEffectTick(level: ServerLevel, entity: LivingEntity, amplifier: Int): Boolean {
        // With its switch off the effect goes (the game removes it when this says false); Warmth looks once a second
        if ((family != Family.WARMTH || entity.tickCount % 20 == 0) && !on()) return false
        when (family) {
            Family.WARMTH -> if (entity.ticksFrozen > 0) entity.ticksFrozen = 0
            Family.PROVISIONED -> if (entity is ServerPlayer) Meals.provision(entity)
            else -> {}
        }
        return true
    }

    /** Whether the effect's switch is on: Hearthcraft for a meal, Winter Chill for the cold. */
    private fun on(): Boolean = if (family == null) Cooking.winterChill() else Cooking.enabled()

    /** A Steady meal moves maximum stamina, which only the talents' own refresh can set: it is asked for, and done on the player's next tick. */
    override fun onEffectStarted(entity: LivingEntity, amplifier: Int) {
        if (entity !is ServerPlayer) return
        if (family == Family.STEADY) Meals.steadyChanged(entity)
        if (family == Family.PROVISIONED) Meals.rationed(entity)
    }

    companion object {
        /** The id Hearty's maximum health is put in place with. */
        val HEARTY_MODIFIER: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "effect.hearty")
    }
}
