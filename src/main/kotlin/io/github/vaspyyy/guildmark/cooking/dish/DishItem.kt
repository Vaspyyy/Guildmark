package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.Consumable
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 * A portion of a dish (living-world/cook/design.md 3.1): one item for each of the twelve [DishKind]s, and the two that
 * belong to no kind, Gruel and Scorched mess ([kind] null). One item is one portion; its tier, day and cook are the
 * component `guildmark:dish`. It is food, with a consumable of its own (a bowl takes 1.6 seconds, Gruel 2.4, anything else 1.2) and,
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

    /**
     * Using a dish starts the game's own eating (3.3), after bringing the stack up to date, so that the food it is
     * eaten as and the food the talents add to are those of how it has kept; then, on the server, the mouthful's sound
     * and the pose are asked for ([DishEating.begin]). Scorched mess is not food: it says so and does nothing.
     */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (scorched) {
            if (!level.isClientSide) player.sendOverlayMessage(Component.translatable("message.guildmark.cook.scorched"))
            return InteractionResult.FAIL
        }
        if (level is ServerLevel) Freshness.sync(level, player.getItemInHand(hand))
        val result = super.use(level, player, hand)
        if (player is ServerPlayer && player.isUsingItem && player.usedItemHand == hand) DishEating.begin(player, kind)
        return result
    }

    /**
     * The portion is eaten: the game feeds the player, hands the bowl back and takes one portion off the stack; then the
     * hunger of a gone-off portion, and the meal's effect, which Part F gives ([DishEating.finish]). How the dish had
     * kept is worked out again here, from its day, and never taken from what the stack last said.
     */
    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        if (scorched || level !is ServerLevel || entity !is ServerPlayer) return super.finishUsingItem(stack, level, entity)
        val stage = Freshness.sync(level, stack)
        val data = Dishes.dataOf(stack) ?: DishData.PLAIN
        val left = super.finishUsingItem(stack, level, entity)
        DishEating.finish(entity, kind, data, stage)
        return left
    }

    /** "Fine Drover's Stew": the tier first, in its colour (see [DishTooltip]). */
    override fun getName(stack: ItemStack): Component = DishTooltip.name(stack, super.getName(stack))

    /** How it has kept, who cooked it, the meal it gives and what is coming (see [DishTooltip]). */
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, builder, flag)
        DishTooltip.append(stack, context.level(), builder)
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
         * The consumable of [kind] (3.3): 1.6 seconds for a bowl (2.4 for Gruel) and 1.2 for a solid, drunk for Broth and eaten for the
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
