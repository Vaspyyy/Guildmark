package io.github.vaspyyy.guildmark.magic

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/**
 * Testing commands: `/guildmark spells learn <spell|all>` teaches spells (attuning them while slots are free),
 * `/guildmark spells forget <spell|all>` forgets them again (the two starters stay), and
 * `/guildmark spells list` says what you know and have attuned.
 */
object MagicCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("spells")
                        .then(Commands.literal("learn").then(spellArgument().executes { learn(it) }))
                        .then(Commands.literal("forget").then(spellArgument().executes { forget(it) }))
                        .then(Commands.literal("list").executes { list(it) }),
                ),
        )
    }

    private fun spellArgument() = Commands.argument("spell", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(Spell.entries.map { it.id } + "all", builder) }

    /** The spells the argument names, or null (after saying so) when it names none. */
    private fun spells(context: CommandContext<CommandSourceStack>): List<Spell>? {
        val id = StringArgumentType.getString(context, "spell")
        if (id == "all") return Spell.entries
        val spell = Spell.byId(id)
        if (spell == null) context.source.sendFailure(Component.translatable("commands.guildmark.spells.unknown", id))
        return spell?.let(::listOf)
    }

    private fun learn(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val spells = spells(context) ?: return 0
        val learned = spells.count { Spellbook.learn(player, it) }
        context.source.sendSuccess({ Component.translatable("commands.guildmark.spells.learned", learned) }, true)
        return learned
    }

    private fun forget(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val spells = spells(context)?.filter { it !in Spell.STARTERS } ?: return 0
        spells.forEach { Spellbook.forget(player, it) }
        context.source.sendSuccess({ Component.translatable("commands.guildmark.spells.forgot", spells.size) }, true)
        return spells.size
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val book = Spellbook.get(player)
        val known = Component.empty()
        for ((i, spell) in book.knownSpells().withIndex()) {
            if (i > 0) known.append(", ")
            known.append(spell.title.copy().withColor(if (book.attuned(spell)) spell.school.color else 0x7D7466))
        }
        context.source.sendSuccess({
            Component.translatable("commands.guildmark.spells.list", book.attuned.size, Spellbook.slots(player), known)
        }, false)
        return book.known.size
    }
}
