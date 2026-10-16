package io.github.vaspyyy.guildmark.companion

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.story.Characters
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component

/**
 * Testing commands: `/guildmark companion list` shows where each companion stands with you, `meet <companion>` has
 * them walk up as if to your fire, `join <companion>` has them travel with you at once, `loyalty <companion> <n>`
 * and `chapter <companion> <n>` set how far along you are with them (3 chapters is Sworn), and `reset` makes them
 * all forget you.
 */
object CompanionCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        fun companion(): RequiredArgumentBuilder<CommandSourceStack, String> = Commands.argument("companion", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(CompanionKind.entries.map { it.id }, builder) }
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("companion")
                        .then(Commands.literal("list").executes(::list))
                        .then(Commands.literal("meet").then(companion().executes(::meet)))
                        .then(Commands.literal("join").then(companion().executes(::join)))
                        .then(Commands.literal("loyalty").then(companion().then(
                            Commands.argument("loyalty", IntegerArgumentType.integer(CompanionKind.LEAST, CompanionKind.MOST)).executes(::loyalty),
                        )))
                        .then(Commands.literal("chapter").then(companion().then(
                            Commands.argument("chapters", IntegerArgumentType.integer(0, 3)).executes(::chapter),
                        )))
                        .then(Commands.literal("reset").executes(::reset)),
                ),
        )
    }

    private fun kind(context: CommandContext<CommandSourceStack>): CompanionKind? {
        val name = StringArgumentType.getString(context, "companion")
        return CompanionKind.byId(name) ?: run {
            context.source.sendFailure(Component.translatable("command.guildmark.companion.unknown", name))
            null
        }
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val state = Companions.state(player)
        for (kind in CompanionKind.entries) {
            val status = when {
                state.isSworn(kind) && state.isActive(kind) -> "sworn_travelling"
                state.isSworn(kind) -> "sworn"
                kind.id in state.leftOn -> "left"
                state.isActive(kind) -> if (state.following) "travelling" else "waiting"
                state.hasJoined(kind) -> "parted"
                state.hasMet(kind) -> "met"
                else -> "unmet"
            }
            val chapters = Characters.chapters(player, kind.character)
            source.sendSuccess({
                Component.translatable("command.guildmark.companion.list", kind.title, Component.translatable("command.guildmark.companion.$status"),
                    state.loyalty(kind), chapters, kind.character.steps.size)
            }, false)
        }
        return 1
    }

    private fun meet(context: CommandContext<CommandSourceStack>): Int {
        val kind = kind(context) ?: return 0
        val player = context.source.playerOrException
        Companions.meetNow(player, kind)
        context.source.sendSuccess({ Component.translatable("command.guildmark.companion.meet", kind.title) }, true)
        return 1
    }

    private fun join(context: CommandContext<CommandSourceStack>): Int {
        val kind = kind(context) ?: return 0
        val player = context.source.playerOrException
        Companions.takeAlong(player, kind, GlobalPos.of(player.level().dimension(), player.blockPosition()))
        Companions.refresh(player, kind)
        context.source.sendSuccess({ Component.translatable("command.guildmark.companion.join", kind.title) }, true)
        return 1
    }

    private fun loyalty(context: CommandContext<CommandSourceStack>): Int {
        val kind = kind(context) ?: return 0
        val player = context.source.playerOrException
        val value = IntegerArgumentType.getInteger(context, "loyalty")
        val state = Companions.state(player)
        Companions.set(player, state.withLoyalty(kind, value).copy(asked = state.asked - kind.id))
        context.source.sendSuccess({ Component.translatable("command.guildmark.companion.loyalty", kind.title, value) }, true)
        return 1
    }

    private fun chapter(context: CommandContext<CommandSourceStack>): Int {
        val kind = kind(context) ?: return 0
        val player = context.source.playerOrException
        val chapters = IntegerArgumentType.getInteger(context, "chapters").coerceAtMost(kind.character.steps.size)
        val story = player.getData(ModAttachments.STORY)
        player.setData(ModAttachments.STORY, if (chapters == 0) story - kind.character.id else story + (kind.character.id to chapters))
        val state = Companions.state(player)
        val sworn = if (chapters >= kind.character.steps.size) state.sworn + kind.id else state.sworn - kind.id
        Companions.set(player, state.copy(sworn = sworn, asked = state.asked - kind.id))
        context.source.sendSuccess({ Component.translatable("command.guildmark.companion.chapter", kind.title, chapters, kind.character.steps.size) }, true)
        return 1
    }

    private fun reset(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        Companions.reset(player)
        context.source.sendSuccess({ Component.translatable("command.guildmark.companion.reset", player.displayName) }, true)
        return 1
    }
}
