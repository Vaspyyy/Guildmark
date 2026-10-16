package io.github.vaspyyy.guildmark.outlaw

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.Difficulty
import net.minecraft.world.level.Level
import kotlin.math.sqrt

/**
 * Testing commands: `/guildmark outlaws here` makes camp for a band on open ground 24 to 36 blocks from you,
 * `/guildmark outlaws nearest` tells you where the nearest band at large is camped, how many there are and what's on
 * the captain's head, and `/guildmark outlaws crime` has that band do something now, as it might on any day.
 */
object OutlawCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("outlaws")
                        .then(Commands.literal("here").executes { here(it) })
                        .then(Commands.literal("nearest").executes { nearest(it) })
                        .then(Commands.literal("crime").executes { crime(it) }),
                ),
        )
    }

    private fun here(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.overworld"))
            return 0
        }
        if (level.difficulty == Difficulty.PEACEFUL) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.peaceful"))
            return 0
        }
        val band = OutlawCamps.get(level).createNear(level, player.blockPosition())
        if (band == null) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.no_room"))
            return 0
        }
        val pos = band.pos
        val distance = sqrt(player.distanceToSqr(pos.x + 0.5, player.y, pos.z + 0.5)).toInt()
        source.sendSuccess({ Component.translatable("commands.guildmark.outlaws.here", band.title(), pos.x, pos.y, pos.z, distance) }, true)
        return 1
    }

    private fun nearest(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val band = OutlawCamps.get(source.level).nearest(player.blockPosition())
        if (band == null) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.none"))
            return 0
        }
        val pos = band.pos
        val distance = sqrt(player.distanceToSqr(pos.x + 0.5, player.y, pos.z + 0.5)).toInt()
        source.sendSuccess({
            Component.translatable("commands.guildmark.outlaws.nearest", band.title(), pos.x, pos.y, pos.z, distance, band.members.size, band.bounty)
        }, false)
        return 1
    }

    private fun crime(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val camps = OutlawCamps.get(level)
        val band = camps.nearest(player.blockPosition())
        if (band == null) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.none"))
            return 0
        }
        val crime = Crime.entries[level.random.nextInt(Crime.entries.size)]
        camps.commit(level, band, crime)
        source.sendSuccess({
            Component.translatable("commands.guildmark.outlaws.crime", band.title(), Component.translatable("outlaw.guildmark.crime.${crime.id}"), band.bounty)
        }, true)
        return 1
    }
}
