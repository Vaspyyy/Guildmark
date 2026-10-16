package io.github.vaspyyy.guildmark.ship

import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import java.util.Optional

/**
 * Ships: the cog (see [Cog]), its item, and remembering where each player last left theirs so the compass
 * and the map can point the way back to it. Companions travelling with a player go aboard with them and
 * step off with them (CompanionEntity.keepUp).
 */
object Ships {
    fun register(modBus: IEventBus) {
        ShipRegistry.ITEMS.register(modBus)
        ShipRegistry.ENTITIES.register(modBus)
        ShipRegistry.SOUNDS.register(modBus)
        ShipRegistry.COMPONENTS.register(modBus)
        ShipRegistry.ATTACHMENTS.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) event.accept(ShipRegistry.COG_ITEM.get())
        }
    }

    fun berth(player: Player): Berth = player.getData(ShipRegistry.BERTH)

    /** [player] stepped off [cog]: if she's theirs, that's where she's berthed now. */
    fun left(player: ServerPlayer, cog: Cog) {
        if (cog.owner == player.uuid && !cog.isRemoved) berthed(player, cog)
    }

    /** Remembers [cog] as where [player]'s ship lies. */
    fun berthed(player: ServerPlayer, cog: Cog) {
        val name = cog.customName?.string ?: ""
        player.setData(ShipRegistry.BERTH, Berth(Optional.of(GlobalPos.of(cog.level().dimension(), cog.blockPosition())), name))
    }

    /** [cog] is gone (packed away or wrecked): its owner's compass stops pointing at where she lay. */
    fun gone(cog: Cog) {
        val owner = cog.owner ?: return
        val player = cog.level().server?.playerList?.getPlayer(owner) ?: return
        val at = berth(player).at.orElse(null) ?: return
        if (at.dimension() == cog.level().dimension() && at.pos().distSqr(cog.blockPosition()) < NEAR * NEAR) player.setData(ShipRegistry.BERTH, Berth())
    }

    /** How far from where she was berthed a ship can be and still be taken for the one the compass shows. */
    private const val NEAR = 24
}
