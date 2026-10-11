package io.github.vaspyyy.guildmark.guild

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/** What the rival guilds do in the world. */
object Rivals {
    fun daily(level: ServerLevel, today: Long) {}

    fun onSiegeStart(level: ServerLevel, holding: Holding, center: BlockPos) {}
}
