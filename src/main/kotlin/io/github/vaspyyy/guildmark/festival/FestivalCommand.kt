package io.github.vaspyyy.guildmark.festival

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Testing commands: `/guildmark festival start <festival>` puts a festival on now in the town you're in (on a clock of
 * its own, from the start of its morning), in place of anything going on there; `/guildmark festival stop` calls off
 * the nearest; `/guildmark festival phase <part>` moves a started festival on (or back) to the fair, the prize-giving,
 * the evening or the teardown; `/guildmark festival info` says what's on, when, who's taking part and what's up.
 */
object FestivalCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("festival")
                        .then(Commands.literal("info").executes { info(it) })
                        .then(Commands.literal("stop").executes { stop(it) })
                        .then(
                            Commands.literal("start").then(
                                Commands.argument("festival", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(FestivalKind.entries.map { it.id }, builder) }
                                    .executes { start(it) },
                            ),
                        )
                        .then(
                            Commands.literal("phase").then(
                                Commands.argument("phase", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(FestivalPhase.entries.map { it.id }, builder) }
                                    .executes { phase(it) },
                            ),
                        ),
                ),
        )
    }

    private fun here(context: CommandContext<CommandSourceStack>): BlockPos = BlockPos.containing(context.source.position)

    private fun start(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val kind = FestivalKind.byId(StringArgumentType.getString(context, "festival"))
        if (kind == null) {
            source.sendFailure(Component.translatable("commands.guildmark.festival.unknown"))
            return 0
        }
        if (!Seasons.festivalsEnabled()) {
            source.sendFailure(Component.translatable("commands.guildmark.festival.off"))
            return 0
        }
        val site = Festivities.start(source.level, here(context), kind)
        if (site == null) {
            source.sendFailure(Component.translatable("commands.guildmark.festival.no_town"))
            return 0
        }
        source.sendSuccess({ Component.translatable("commands.guildmark.festival.started", kind.title, site.townName, site.plan.pieces.size, site.plan.dance?.n ?: 0) }, true)
        return 1
    }

    private fun stop(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val site = Festivities.nearest(here(context))
        if (site == null) {
            source.sendFailure(Component.translatable("commands.guildmark.festival.none"))
            return 0
        }
        Festivities.stop(site)
        source.sendSuccess({ Component.translatable("commands.guildmark.festival.stopped", site.kind.title, site.townName) }, true)
        return 1
    }

    private fun phase(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val phase = FestivalPhase.byId(StringArgumentType.getString(context, "phase"))
        val site = Festivities.nearest(here(context))
        if (phase == null || site == null || !site.forced) {
            source.sendFailure(Component.translatable("commands.guildmark.festival.not_started"))
            return 0
        }
        site.jump(source.level, phase)
        source.sendSuccess({ Component.translatable("commands.guildmark.festival.phase", site.kind.title, phase.title) }, true)
        return 1
    }

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val date = Seasons.today(level)
        val today = FestivalCalendar.on(date)
        val (next, ahead) = FestivalCalendar.next(date.plus(1))
        source.sendSuccess({
            Component.translatable(
                "commands.guildmark.festival.calendar",
                today?.title ?: Component.translatable("commands.guildmark.festival.calendar.none"), next.title, ahead + 1,
                if (Seasons.festivalsEnabled()) "on" else "off",
            )
        }, false)
        val site = Festivities.nearest(here(context))
        if (site == null) {
            source.sendSuccess({ Component.translatable("commands.guildmark.festival.none") }, false)
            return 1
        }
        for (line in site.info(level)) source.sendSuccess({ line }, false)
        return 1
    }
}
