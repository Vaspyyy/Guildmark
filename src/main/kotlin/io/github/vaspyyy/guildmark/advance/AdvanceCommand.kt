package io.github.vaspyyy.guildmark.advance

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/** `/guildmark advance <points>`: fund the nearest board's village project, for testing. */
object AdvanceCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("advance").then(
                        Commands.argument("points", IntegerArgumentType.integer(1, 10000)).executes { context ->
                            val source = context.source
                            val player = source.playerOrException
                            val level = source.level
                            val board = BlockPos.betweenClosedStream(player.blockPosition().offset(-8, -4, -8), player.blockPosition().offset(8, 4, 8))
                                .filter { level.getBlockState(it).block is QuestBoardBlock }
                                .findFirst().map { it.immutable() }.orElse(null)
                            if (board == null) {
                                source.sendFailure(Component.translatable("commands.guildmark.no_board"))
                                0
                            } else {
                                Advances.addPoints(level, QuestBoardBlock.anchorPos(board, level.getBlockState(board)), IntegerArgumentType.getInteger(context, "points"), player)
                                1
                            }
                        }
                    )
                )
        )
    }
}
