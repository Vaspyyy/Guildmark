package io.github.vaspyyy.guildmark.sea

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.ship.Cog
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.Difficulty

/**
 * Testing commands: `/guildmark sea gnashfins` sends a pack of Gnashfins for you from out of sight (out on open water),
 * `/guildmark sea maelgrasp` has the Maelgrasp come for the cog you're aboard with its omens first (`now` to skip them),
 * and `/guildmark sea calm` sends every sea monster near you away.
 */
object SeaCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("sea")
                        .then(Commands.literal("gnashfins").executes { gnashfins(it) })
                        .then(
                            Commands.literal("maelgrasp")
                                .executes { maelgrasp(it, true) }
                                .then(Commands.literal("now").executes { maelgrasp(it, false) }),
                        )
                        .then(Commands.literal("calm").executes { calm(it) }),
                ),
        )
    }

    private fun gnashfins(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        if (source.level.difficulty == Difficulty.PEACEFUL) {
            source.sendFailure(Component.translatable("commands.guildmark.sea.peaceful"))
            return 0
        }
        val count = SeaHunts.spawnPack(source.level, player, 4)
        if (count == 0) {
            source.sendFailure(Component.translatable("commands.guildmark.sea.no_water"))
            return 0
        }
        source.sendSuccess({ Component.translatable("commands.guildmark.sea.gnashfins", count) }, true)
        return count
    }

    private fun maelgrasp(context: CommandContext<CommandSourceStack>, omen: Boolean): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        if (level.difficulty == Difficulty.PEACEFUL) {
            source.sendFailure(Component.translatable("commands.guildmark.sea.peaceful"))
            return 0
        }
        val ship = player.vehicle as? Cog
        if (ship == null) {
            source.sendFailure(Component.translatable("commands.guildmark.sea.not_aboard"))
            return 0
        }
        if (level.getEntitiesOfClass(Maelgrasp::class.java, ship.boundingBox.inflate(64.0)).isNotEmpty()) {
            source.sendFailure(Component.translatable("commands.guildmark.sea.already"))
            return 0
        }
        if (Maelgrasp.summon(level, ship, omen) == null) {
            source.sendFailure(Component.translatable("commands.guildmark.sea.no_water"))
            return 0
        }
        source.sendSuccess({ Component.translatable(if (omen) "commands.guildmark.sea.maelgrasp" else "commands.guildmark.sea.maelgrasp_now") }, true)
        return 1
    }

    private fun calm(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val box = net.minecraft.world.phys.AABB.ofSize(source.position, 160.0, 96.0, 160.0)
        var gone = 0
        for (fish in level.getEntitiesOfClass(Gnashfin::class.java, box)) {
            fish.discard()
            gone++
        }
        for (arm in level.getEntitiesOfClass(MaelgraspArm::class.java, box)) arm.discard()
        for (boss in level.getEntitiesOfClass(Maelgrasp::class.java, box)) {
            boss.discard()
            gone++
        }
        SeaHunts.clear()
        source.sendSuccess({ Component.translatable("commands.guildmark.sea.calm", gone) }, true)
        return gone
    }
}
