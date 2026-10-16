package io.github.vaspyyy.guildmark.faction

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Testing commands: `/guildmark faction list` shows where you stand with each faction, `/guildmark faction set
 * <faction> <chapters>` puts you that far along its questline (0 leaves it), `/guildmark faction reset` makes every
 * faction forget you, and `/guildmark faction seat` says where the seats are in the capital you're in.
 */
object FactionCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("faction")
                        .then(Commands.literal("list").executes(::list))
                        .then(
                            Commands.literal("set").then(
                                Commands.argument("faction", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(Faction.entries.map { it.id }, builder) }
                                    .then(Commands.argument("chapters", IntegerArgumentType.integer(0, Faction.RANKS)).executes(::set)),
                            ),
                        )
                        .then(Commands.literal("reset").executes(::reset))
                        .then(Commands.literal("seat").executes(::seat)),
                ),
        )
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val state = Factions.state(source.playerOrException)
        for (faction in Faction.entries) {
            val chapters = state.chapters(faction)
            val detail = when {
                state.isShut(faction) -> Component.translatable("command.guildmark.faction.shut")
                chapters == 0 -> Component.translatable("command.guildmark.faction.none")
                else -> Component.literal("$chapters/${Faction.RANKS}, ").append(faction.rankTitle(chapters))
            }
            source.sendSuccess({ Component.translatable("command.guildmark.faction.list", faction.title, detail) }, false)
        }
        return 1
    }

    private fun set(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val name = StringArgumentType.getString(context, "faction")
        val faction = Faction.byId(name)
        if (faction == null) {
            source.sendFailure(Component.translatable("command.guildmark.faction.unknown", name))
            return 0
        }
        val chapters = IntegerArgumentType.getInteger(context, "chapters")
        val state = Factions.state(player)
        Factions.set(player, state.withChapters(faction, chapters).copy(shut = state.shut - faction.id))
        source.sendSuccess({ Component.translatable("command.guildmark.faction.set", faction.title, chapters, Faction.RANKS) }, true)
        return 1
    }

    private fun reset(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        Factions.set(player, FactionState())
        source.sendSuccess({ Component.translatable("command.guildmark.faction.reset", player.displayName) }, true)
        return 1
    }

    private fun seat(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val at = BlockPos.containing(source.position)
        val capital = Capitals.covering(level, at)
        val layout = capital?.let { FactionSeats.layout(it) }
        if (capital == null || layout == null) {
            source.sendFailure(Component.translatable("command.guildmark.faction.no_capital"))
            return 0
        }
        fun where(faction: Faction): Component = layout.seats[faction]?.leader?.pos?.let { Component.literal("${it.x} ${it.y} ${it.z}") }
            ?: Component.literal("-")
        source.sendSuccess({
            Component.translatable(
                "command.guildmark.faction.seat", capital.center.x, capital.center.z,
                where(Faction.STARWATCH), where(Faction.HEARTHSWORN), where(Faction.QUIET_HAND),
            )
        }, false)
        return 1
    }
}
