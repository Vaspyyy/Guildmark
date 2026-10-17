package io.github.vaspyyy.guildmark.outlaw

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.Difficulty
import net.minecraft.world.level.Level
import kotlin.math.sqrt

/**
 * Testing commands: `/guildmark outlaws here` makes camp for a band on open ground 24 to 36 blocks from you,
 * `/guildmark outlaws nearest` tells you where the nearest band at large is camped, how many there are and what's on
 * the captain's head, `/guildmark outlaws crime` has that band do something now, as it might on any day,
 * `/guildmark outlaws raid` sends that band to raid the village you're in, and `/guildmark outlaws captive` has them
 * take someone from the village nearest you and hold them in their camp.
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
                        .then(Commands.literal("crime").executes { crime(it) })
                        .then(Commands.literal("raid").executes { raid(it) })
                        .then(Commands.literal("captive").executes { captive(it) }),
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
        val crime = Crime.DAILY[level.random.nextInt(Crime.DAILY.size)]
        camps.commit(level, band, crime)
        source.sendSuccess({
            Component.translatable("commands.guildmark.outlaws.crime", band.title(), Component.translatable("outlaw.guildmark.crime.${crime.id}"), band.bounty)
        }, true)
        return 1
    }

    private fun raid(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val band = OutlawCamps.get(level).nearest(player.blockPosition())?.takeIf { it.built && !it.slain }
        if (band == null) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.none"))
            return 0
        }
        val bell = Raids.nearestBell(level, player.blockPosition(), 96)
        if (bell == null) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.no_village"))
            return 0
        }
        if (!Raids.start(level, band, bell)) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.no_raid"))
            return 0
        }
        source.sendSuccess({ Component.translatable("commands.guildmark.outlaws.raid", band.title()) }, true)
        return 1
    }

    private fun captive(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val band = OutlawCamps.get(level).nearest(player.blockPosition())?.takeIf { it.built && !it.slain }
        if (band == null) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.none"))
            return 0
        }
        val bell = Raids.nearestBell(level, player.blockPosition(), 400)
        if (bell == null) {
            source.sendFailure(Component.translatable("commands.guildmark.outlaws.no_village"))
            return 0
        }
        val role = listOf(TownRole.CITIZEN, TownRole.FARMER, TownRole.FISHER).let { it[level.random.nextInt(it.size)] }
        val captive = Captives.take(level, band, Raids.strangerName(level), Captives.homeOf(level, bell), role, level.random.nextInt(role.looks))
        val pos = band.pos
        source.sendSuccess({ Component.translatable("commands.guildmark.outlaws.captive", band.title(), captive.name, pos.x, pos.y, pos.z) }, true)
        return 1
    }
}
