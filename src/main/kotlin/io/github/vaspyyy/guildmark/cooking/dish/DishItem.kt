package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.Consumable
import java.util.function.UnaryOperator

/**
 * A portion of a dish (living-world/cook/design.md 3.1): one item for each of the twelve [DishKind]s, and the two that
 * belong to no kind, Gruel and Scorched mess ([kind] null). One item is one portion; its tier, day and cook are the
 * component `guildmark:dish`. It is food, with a consumable of its own (a bowl takes 1.6 seconds, anything else 1.2) and,
 * for the pot dishes, the bowl back; its food, and the cosmetic stage its model reads, follow how it has kept (3.2).
 */
class DishItem(properties: Properties, val kind: DishKind?) : Item(properties) {
    /** Scorched mess, the one dish that cannot be eaten and does not keep: a ruin is a ruin (2.1). */
    val scorched: Boolean by lazy { this === CookRegistry.SCORCHED_MESS.get() }

    /**
     * The one thing a dish does in a pack: now and then (every [Freshness.RECHECK] ticks, staggered by who holds it, so a
     * full pack is not looked at all at once) work out how it has kept and write it down if it changed (3.2, 9.5). The
     * rest of the time it costs the compare below, once a stack a tick, and nothing in a chest, a hopper or on the
     * ground, where nothing ticks: the model of a dish lying there shows how it was when it was last held.
     */
    override fun inventoryTick(stack: ItemStack, level: ServerLevel, entity: Entity, slot: EquipmentSlot?) {
        super.inventoryTick(stack, level, entity, slot)
        if ((level.gameTime + entity.id) % Freshness.RECHECK != 0L) return
        Freshness.sync(level, stack)
    }

    companion object {
        /**
         * How a dish item is made (stack size, food, use...): Part D's to tune, so the registry never needs to change.
         * Stacks of 8, rations 16. Its food is a Plain, fresh portion's: what a dish with no component of its own is
         * (3.1); a stack that has one carries the food of its tier and its stage ([Freshness.sync], [Dishes.make]).
         * The pot dishes and Gruel are served in a bowl, which comes back. Scorched mess is the same item as Gruel here
         * (the registry gives both the same maker) and is stopped from being eaten by the item itself.
         */
        fun properties(kind: DishKind?): UnaryOperator<Item.Properties> = UnaryOperator { properties ->
            properties
                .stacksTo(if (kind == DishKind.LOAF || kind == DishKind.STRIPS) 16 else 8)
                .food(DishFood.food(kind, Tier.PLAIN, Stage.FRESH), consumable(kind))
                .also { if (DishFood.bowl(kind)) it.usingConvertsTo(Items.BOWL) }
        }

        /**
         * The consumable of [kind] (3.3): 1.6 seconds for a bowl and 1.2 for a solid, drunk for Broth and eaten for the
         * rest. It makes no sound of its own, because the game's repeats every few ticks, and the dish's is one long
         * recording that [DishEating] plays once; a solid sends up crumbs of itself, and a bowl does not.
         */
        private fun consumable(kind: DishKind?): Consumable = Consumable.builder()
            .consumeSeconds(DishFood.seconds(kind))
            .animation(if (kind == DishKind.BROTH) ItemUseAnimation.DRINK else ItemUseAnimation.EAT)
            .sound(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.EMPTY))
            .hasConsumeParticles(!DishFood.bowl(kind))
            .build()
    }
}
