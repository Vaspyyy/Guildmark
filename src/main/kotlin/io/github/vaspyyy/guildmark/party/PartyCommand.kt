package io.github.vaspyyy.guildmark.party

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.GameProfileArgument

/** /party invite, join, leave, kick and list. Anyone can use these. */
object PartyCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("party")
                .executes { context ->
                    Parties.list(context.source.server, context.source.playerOrException)
                    1
                }
                .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes { context ->
                    if (Parties.invite(context.source.server, context.source.playerOrException, EntityArgument.getPlayer(context, "player"))) 1 else 0
                }))
                .then(Commands.literal("join").then(Commands.argument("leader", EntityArgument.player()).executes { context ->
                    if (Parties.join(context.source.server, context.source.playerOrException, EntityArgument.getPlayer(context, "leader"))) 1 else 0
                }))
                .then(Commands.literal("leave").executes { context ->
                    if (Parties.leave(context.source.server, context.source.playerOrException)) 1 else 0
                })
                .then(Commands.literal("kick").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { context ->
                    val profile = GameProfileArgument.getGameProfiles(context, "player").firstOrNull() ?: return@executes 0
                    if (Parties.kick(context.source.server, context.source.playerOrException, profile.id, net.minecraft.network.chat.Component.literal(profile.name))) 1 else 0
                }))
                .then(Commands.literal("list").executes { context ->
                    Parties.list(context.source.server, context.source.playerOrException)
                    1
                })
        )
    }
}
