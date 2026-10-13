package io.github.vaspyyy.guildmark.dragon

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.Difficulty
import net.minecraft.world.level.Level
import kotlin.math.sqrt

/**
 * Testing commands: `/guildmark dragon here` sets an awake dragon a few blocks in front of you with its home there
 * and no roost, `/guildmark dragon roost` raises a roost with its nest and dragon on the best summit loaded near
 * you (a hill will do; it moves nothing of yours), and `/guildmark dragon nearest` tells you where the nearest
 * roost is and how far off.
 */
object DragonCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("dragon")
                        .then(Commands.literal("here").executes { here(it) })
                        .then(Commands.literal("roost").executes { roost(it) })
                        .then(Commands.literal("nearest").executes { nearest(it) }),
                ),
        )
    }

    private fun here(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        if (source.level.difficulty == Difficulty.PEACEFUL) {
            source.sendFailure(Component.translatable("commands.guildmark.dragon.peaceful"))
            return 0
        }
        val dragon = DragonRoosts.get(source.level).spawnHere(source.level, player)
        if (dragon == null) {
            source.sendFailure(Component.translatable("commands.guildmark.dragon.no_room"))
            return 0
        }
        source.sendSuccess({ Component.translatable("commands.guildmark.dragon.here", dragon.title) }, true)
        return 1
    }

    private fun roost(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.translatable("commands.guildmark.dragon.overworld"))
            return 0
        }
        if (level.difficulty == Difficulty.PEACEFUL) {
            source.sendFailure(Component.translatable("commands.guildmark.dragon.peaceful"))
            return 0
        }
        val pos = DragonRoosts.get(level).createNear(level, player)
        if (pos == null) {
            source.sendFailure(Component.translatable("commands.guildmark.dragon.no_roost"))
            return 0
        }
        val distance = sqrt(player.distanceToSqr(pos.x + 0.5, player.y, pos.z + 0.5)).toInt()
        source.sendSuccess({ Component.translatable("commands.guildmark.dragon.roost", pos.x, pos.y, pos.z, distance) }, true)
        return 1
    }

    private fun nearest(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val pos = DragonRoosts.get(level).nearest(level, player.blockPosition())
        if (pos == null) {
            source.sendFailure(Component.translatable("commands.guildmark.dragon.none"))
            return 0
        }
        val distance = sqrt(player.distanceToSqr(pos.x + 0.5, player.y, pos.z + 0.5)).toInt()
        source.sendSuccess({ Component.translatable("commands.guildmark.dragon.nearest", pos.x, pos.y, pos.z, distance) }, false)
        return 1
    }
}
