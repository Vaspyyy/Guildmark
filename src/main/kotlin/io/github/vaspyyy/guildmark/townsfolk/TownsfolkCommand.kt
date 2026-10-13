package io.github.vaspyyy.guildmark.townsfolk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/**
 * Testing commands: `/guildmark townsfolk spawn <role> [look]` puts a resident at your feet (they find their
 * workplace and bed nearby), `/guildmark townsfolk demo` clears the ground round you and builds a little square
 * with one of each trade, `/guildmark townsfolk zone [radius]` makes the area round you a town so passers-by
 * gather (`zone remove` undoes it), and `/guildmark townsfolk clear [radius]` sends everyone near you away.
 */
object TownsfolkCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("townsfolk")
                        .then(
                            Commands.literal("spawn").then(
                                Commands.argument("role", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(TownRole.entries.map { it.id }, builder) }
                                    .executes { spawn(it, -1) }
                                    .then(
                                        Commands.argument("look", IntegerArgumentType.integer(0, 7))
                                            .executes { spawn(it, IntegerArgumentType.getInteger(it, "look")) },
                                    ),
                            ),
                        )
                        .then(
                            Commands.literal("demo").executes { context ->
                                val source = context.source
                                val player = source.playerOrException
                                val count = DemoYard.build(source.level, player.blockPosition())
                                source.sendSuccess({ Component.translatable("commands.guildmark.townsfolk.demo", count, DemoYard.REACH + 2) }, true)
                                count
                            },
                        )
                        .then(
                            Commands.literal("zone")
                                .executes { zone(it, 48) }
                                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 256)).executes { zone(it, IntegerArgumentType.getInteger(it, "radius")) })
                                .then(
                                    Commands.literal("remove").executes { context ->
                                        val source = context.source
                                        if (TownZones.get(source.level).remove(source.playerOrException.blockPosition())) {
                                            source.sendSuccess({ Component.translatable("commands.guildmark.townsfolk.zone_removed") }, true)
                                            1
                                        } else {
                                            source.sendFailure(Component.translatable("commands.guildmark.townsfolk.no_zone"))
                                            0
                                        }
                                    },
                                ),
                        )
                        .then(
                            Commands.literal("clear")
                                .executes { clear(it, 64) }
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256)).executes { clear(it, IntegerArgumentType.getInteger(it, "radius")) }),
                        ),
                ),
        )
    }

    private fun spawn(context: CommandContext<CommandSourceStack>, look: Int): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "role")
        val role = TownRole.byName(name)
        if (role == null) {
            source.sendFailure(Component.translatable("commands.guildmark.townsfolk.unknown_role", name))
            return 0
        }
        val player = source.playerOrException
        val person = DemoYard.one(source.level, player.blockPosition(), role, look) ?: return 0
        source.sendSuccess({ Component.translatable("commands.guildmark.townsfolk.spawned", person.displayName) }, true)
        return 1
    }

    private fun zone(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        Townsfolk.registerTown(source.level, source.playerOrException.blockPosition(), radius)
        source.sendSuccess({ Component.translatable("commands.guildmark.townsfolk.zone", radius) }, true)
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val folk = source.level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(radius.toDouble())) { true }
        folk.forEach { it.discard() }
        source.sendSuccess({ Component.translatable("commands.guildmark.townsfolk.cleared", folk.size) }, true)
        return folk.size
    }
}
