package io.github.vaspyyy.guildmark.season

import io.github.vaspyyy.guildmark.client.season.AlmanacScreen
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level

/** The Almanac: right-click to open the year at a glance, the festivals and what grows when. */
class AlmanacItem(properties: Item.Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide()) AlmanacScreen.open()
        return InteractionResult.SUCCESS
    }
}
