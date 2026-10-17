package io.github.vaspyyy.guildmark.season

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks

/**
 * Testing commands: `/guildmark season info` says the date, the phase, how the crops are doing where you stand and
 * how much the land there feels the seasons; `/guildmark season set <season> [day]` moves the calendar to that day
 * of this year; `/guildmark season next` to the first day of the next season. Everyone sees the change at once.
 */
object SeasonCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("season")
                        .then(Commands.literal("info").executes { info(it) })
                        .then(Commands.literal("next").executes { next(it) })
                        .then(
                            Commands.literal("set").then(
                                Commands.argument("season", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(Season.entries.map { it.id }, builder) }
                                    .executes { set(it, 1) }
                                    .then(
                                        Commands.argument("day", IntegerArgumentType.integer(1, 60))
                                            .executes { set(it, IntegerArgumentType.getInteger(it, "day")) },
                                    ),
                            ),
                        ),
                ),
        )
    }

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val date = Seasons.today(level)
        val pos = net.minecraft.core.BlockPos.containing(source.position)
        source.sendSuccess({
            Component.translatable("commands.guildmark.season.info", date.title, date.year, date.phase.of(date.season), date.daysLeft, date.calendarDay)
        }, false)
        val weight = Seasons.temperate(level, pos)
        val sheltered = SeasonCrops.sheltered(level, pos)
        val paces = listOf(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.PUMPKIN_STEM, Blocks.MELON_STEM)
            .joinToString(", ") { block -> "%s %.2f".format(block.name.string, SeasonCrops.pace(level, pos, block.defaultBlockState())) }
        source.sendSuccess({ Component.translatable("commands.guildmark.season.here", "%.2f".format(weight), sheltered.toString(), paces) }, false)
        if (!Seasons.enabled()) source.sendSuccess({ Component.translatable("commands.guildmark.season.off") }, false)
        return 1
    }

    private fun next(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val date = Seasons.today(source.level)
        val season = date.season.next
        Seasons.setDate(source.level, season, 1)
        source.sendSuccess({ Component.translatable("commands.guildmark.season.set", Seasons.today(source.level).title) }, true)
        return 1
    }

    private fun set(context: CommandContext<CommandSourceStack>, day: Int): Int {
        val source = context.source
        val season = Season.byId(StringArgumentType.getString(context, "season"))
        if (season == null) {
            source.sendFailure(Component.translatable("commands.guildmark.season.unknown"))
            return 0
        }
        Seasons.setDate(source.level, season, day)
        source.sendSuccess({ Component.translatable("commands.guildmark.season.set", Seasons.today(source.level).title) }, true)
        return 1
    }
}
