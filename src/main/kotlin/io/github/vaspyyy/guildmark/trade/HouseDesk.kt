package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus

/** The House's desk (build part G): until its screen lands, the owner hears that it keeps no books yet, as F2's stub had it. */
object HouseDesk {
    fun register(modBus: IEventBus) {}

    internal fun open(level: ServerLevel, player: ServerPlayer, w: WorksRec, scribe: Townsperson?): Boolean {
        if (scribe != null) {
            scribe.lookAt(player)
            scribe.attend(player, 40)
            if (scribe.director == null) scribe.act(Activity.NOD)
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.house.no_books").withStyle(ChatFormatting.GRAY))
        return true
    }
}
