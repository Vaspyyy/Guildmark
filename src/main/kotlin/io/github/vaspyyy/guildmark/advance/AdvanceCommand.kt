package io.github.vaspyyy.guildmark.advance

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.quest.QuestType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Testing commands for the nearest board's village: `/guildmark advance <points>` funds the current
 * project, `/guildmark advance reset` starts the chain over (already built structures stay).
 */
object AdvanceCommand {
    private fun nearestAnchor(source: CommandSourceStack): BlockPos? {
        val player = source.playerOrException
        val level = source.level
        val board = BlockPos.betweenClosedStream(player.blockPosition().offset(-8, -4, -8), player.blockPosition().offset(8, 4, 8))
            .filter { level.getBlockState(it).block is QuestBoardBlock }
            .findFirst().map { it.immutable() }.orElse(null) ?: return null
        return QuestBoardBlock.anchorPos(board, level.getBlockState(board))
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("note").then(
                        Commands.argument("type", StringArgumentType.word())
                            .suggests { _, builder -> SharedSuggestionProvider.suggest(QuestType.entries.map { it.serializedName }, builder) }
                            .executes { context ->
                                val source = context.source
                                val level = source.level
                                val anchor = nearestAnchor(source)
                                val type = QuestType.entries.firstOrNull { it.serializedName == StringArgumentType.getString(context, "type") }
                                val note = type?.let { QuestGenerator.generateOfType(level.random, it, QuestGenerator.tierAt(source.playerOrException.blockX, source.playerOrException.blockZ)) }
                                val board = anchor?.let { level.getBlockEntity(it) as? QuestBoardBlockEntity }
                                if (board == null || note == null) {
                                    source.sendFailure(Component.translatable("commands.guildmark.no_note"))
                                    0
                                } else {
                                    board.updateNote(note)
                                    1
                                }
                            }
                    )
                )
                .then(
                    Commands.literal("advance")
                        .then(
                            Commands.argument("points", IntegerArgumentType.integer(1, 10000)).executes { context ->
                                val source = context.source
                                val anchor = nearestAnchor(source)
                                if (anchor == null) {
                                    source.sendFailure(Component.translatable("commands.guildmark.no_board"))
                                    0
                                } else {
                                    Advances.addPoints(source.level, anchor, IntegerArgumentType.getInteger(context, "points"), source.playerOrException)
                                    1
                                }
                            }
                        )
                        .then(
                            Commands.literal("reset").executes { context ->
                                val source = context.source
                                val board = nearestAnchor(source)?.let { source.level.getBlockEntity(it) as? QuestBoardBlockEntity }
                                if (board == null) {
                                    source.sendFailure(Component.translatable("commands.guildmark.no_board"))
                                    0
                                } else {
                                    board.setAdvanceProgress(0, 0)
                                    source.sendSuccess({ Component.translatable("commands.guildmark.advance_reset") }, false)
                                    1
                                }
                            }
                        )
                )
        )
    }
}
