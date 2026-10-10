package io.github.vaspyyy.guildmark.advance

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import io.github.vaspyyy.guildmark.progression.GuildProgress
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.road.Traffic
import io.github.vaspyyy.guildmark.siege.Sieges
import io.github.vaspyyy.guildmark.story.Characters
import io.github.vaspyyy.guildmark.story.StoryCharacter
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.village.Standing
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Testing commands for the nearest board's village: `/guildmark advance <points>` funds the current
 * project, `/guildmark advance reset` starts the chain over (already built structures stay),
 * `/guildmark standing <points>` changes your standing there, `/guildmark traffic [ambush]` sends a
 * caravan or traveller along a road 40 to 96 blocks away, `/guildmark character <id>` brings a named
 * character to the nearest village.
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

    private fun spawnTraffic(source: CommandSourceStack, ambush: Boolean): Int {
        if (Traffic.spawnNear(source.level, source.playerOrException, ambush)) return 1
        source.sendFailure(Component.translatable("commands.guildmark.no_road_near"))
        return 0
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
                    Commands.literal("rank").then(
                        Commands.argument("rank", StringArgumentType.word())
                            .suggests { _, builder -> SharedSuggestionProvider.suggest(AdventurerRank.entries.map { it.letter }, builder) }
                            .executes { context ->
                                val rank = AdventurerRank.entries.firstOrNull { it.letter.equals(StringArgumentType.getString(context, "rank"), true) }
                                val player = context.source.playerOrException
                                if (rank == null) {
                                    context.source.sendFailure(Component.translatable("commands.guildmark.no_rank"))
                                    0
                                } else {
                                    player.setData(ModAttachments.GUILD_PROGRESS, Progression.get(player).copy(adventurerRank = rank.ordinal))
                                    context.source.sendSuccess({ Component.translatable("commands.guildmark.rank", rank.title) }, false)
                                    1
                                }
                            }
                    )
                )
                .then(
                    Commands.literal("level").then(
                        Commands.argument("level", IntegerArgumentType.integer(1, GuildProgress.MAX_LEVEL)).executes { context ->
                            val player = context.source.playerOrException
                            val level = IntegerArgumentType.getInteger(context, "level")
                            player.setData(ModAttachments.GUILD_PROGRESS, Progression.get(player).copy(level = level, xp = 0))
                            context.source.sendSuccess({ Component.translatable("commands.guildmark.level", level) }, false)
                            1
                        }
                    )
                )
                .then(
                    Commands.literal("siege").executes { context ->
                        if (Sieges.start(context.source.level, context.source.playerOrException.blockPosition())) 1
                        else {
                            context.source.sendFailure(Component.translatable("commands.guildmark.no_siege"))
                            0
                        }
                    }
                )
                .then(
                    Commands.literal("character").then(
                        Commands.argument("id", StringArgumentType.word())
                            .suggests { _, builder -> SharedSuggestionProvider.suggest(StoryCharacter.entries.map { it.id }, builder) }
                            .executes { context ->
                                val source = context.source
                                val character = StoryCharacter.byId(StringArgumentType.getString(context, "id"))
                                val board = VillageBoards.villageBoard(source.level, source.playerOrException.blockPosition())
                                if (character == null || board == null || !Characters.spawn(source.level, character, board)) {
                                    source.sendFailure(Component.translatable("commands.guildmark.no_character"))
                                    0
                                } else 1
                            }
                    )
                )
                .then(
                    Commands.literal("traffic")
                        .executes { context -> spawnTraffic(context.source, false) }
                        .then(Commands.literal("ambush").executes { context -> spawnTraffic(context.source, true) })
                )
                .then(
                    Commands.literal("standing").then(
                        Commands.argument("points", IntegerArgumentType.integer(-1000, 1000)).executes { context ->
                            val source = context.source
                            val anchor = nearestAnchor(source)
                            if (anchor == null) {
                                source.sendFailure(Component.translatable("commands.guildmark.no_board"))
                                0
                            } else {
                                val player = source.playerOrException
                                Standing.add(source.level, anchor, player, IntegerArgumentType.getInteger(context, "points"))
                                source.sendSuccess({ Component.translatable("commands.guildmark.standing", Standing.points(source.level, anchor, player)) }, false)
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
