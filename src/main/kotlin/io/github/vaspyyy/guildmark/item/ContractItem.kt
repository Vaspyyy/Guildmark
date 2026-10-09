package io.github.vaspyyy.guildmark.item

import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level

/** A quest note torn off a board. Right-click to reread it; use it on any board to turn it in. */
class ContractItem(properties: Item.Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val note = stack.get(ModDataComponents.QUEST_NOTE.get())
        if (level.isClientSide() && note != null) {
            ClientHooks.openContract(note, stack.get(ModDataComponents.CONTRACT_STATE.get()))
        }
        return InteractionResult.SUCCESS
    }
}
