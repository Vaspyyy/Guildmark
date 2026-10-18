package io.github.vaspyyy.guildmark.economy.works.build

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.entity.player.CustomClickActionEvent

/** The works' screen, on the server: who has which works open, what they're sent, and what they ask. */
object WorksLedger {
    fun register(modBus: IEventBus) {}

    fun onClick(event: CustomClickActionEvent) {}

    fun close(player: ServerPlayer) {}

    fun reset() {}

    /** Works [id] is gone: anyone looking at it is told. */
    fun gone(level: ServerLevel, id: Int) {}

    /** Works [id] has changed: anyone looking at it is sent it again. */
    fun changed(level: ServerLevel, id: Int) {}

    /** [player] used the works' post, crate or yard at [pos]. */
    fun openAt(level: ServerLevel, player: ServerPlayer, pos: BlockPos) {}
}
