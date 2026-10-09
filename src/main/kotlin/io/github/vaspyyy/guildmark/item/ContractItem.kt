package io.github.vaspyyy.guildmark.item

import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level

/** A quest note torn off a board. Right-click to reread it. */
class ContractItem(properties: Item.Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val note = player.getItemInHand(hand).get(ModDataComponents.QUEST_NOTE.get())
        if (level.isClientSide() && note != null) {
            ClientHooks.openNote(note, null)
        }
        return InteractionResult.SUCCESS
    }
}
