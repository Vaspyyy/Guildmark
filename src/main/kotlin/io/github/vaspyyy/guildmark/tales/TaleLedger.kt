package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * The command that shows what a tale left behind (living-world/stories/design.md 12.4, Part E), appended to [TaleCommand.sub]:
 * `tale chronicle [all]` (the chronicle of the capital you stand in, or of every capital, newest first, with the sentence each
 * ending says and the reasons still on file). It answers with the tales off and says in grey what is off.
 */
object TaleLedger {
    /** Adds the commands to the `tale` tree. Called once, from [Tales.register], before the commands are built. */
    fun register() {
        TaleCommand.sub.add { tale ->
            tale.then(
                Commands.literal("chronicle")
                    .executes { chronicle(it, false) }
                    .then(Commands.literal("all").executes { chronicle(it, true) }),
            )
        }
    }

    // ---- The chronicle ----

    private fun chronicle(context: CommandContext<CommandSourceStack>, all: Boolean): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        if (!Tales.READY) return 0
        val level = source.level
        val data = TaleData.get(level)
        if (all) {
            var shown = 0
            for ((capital, town) in data.towns().entries.sortedBy { it.key }) {
                if (town.chronicle.isNotEmpty()) shown += lines(source, data, capital, town)
            }
            if (shown == 0) TaleCommand.say(source, TaleCommand.grey("No tale has ended in any capital yet."))
            return shown
        }
        val here = TaleCommand.here(context) ?: return 0
        val town = data.find(here.key)
        if (town == null || town.chronicle.isEmpty()) return TaleCommand.say(source, TaleCommand.grey("No tale has ended in ${here.name} yet."))
        return lines(source, data, here.key, town)
    }

    /** Prints [town]'s chronicle, newest first, each ending's sentence and (while its tale is still kept) the reasons it holds; the lines printed. */
    private fun lines(source: CommandSourceStack, data: TaleData, capital: Long, town: TaleData.Town): Int {
        val level = source.level
        val name = TaleWords.town(level, capital).ifEmpty { "the capital at $capital" }
        TaleCommand.say(source, TaleCommand.header("Chronicle of $name"))
        for (l in town.chronicle.asReversed()) {
            val row = TaleCommand.line()
                .append(TaleCommand.label("day ${l.day} "))
                .append(TaleCommand.plain("${l.template} ${l.ending}: "))
                .append(TaleWords.sentence(l).withStyle(ChatFormatting.WHITE))
            if (l.lead.isNotEmpty()) row.append(TaleCommand.grey(" (${l.lead})"))
            TaleCommand.say(source, row)
            val kept = data.all().firstOrNull { it.phase == Tale.Phase.ENDED && it.capital == capital && it.template == l.template && it.ended == l.day }
            for (why in kept?.why.orEmpty()) {
                val reason = Component.translatable("fortune.guildmark.reason.tale.why.${why.kind}", *why.args.toTypedArray())
                TaleCommand.say(source, TaleCommand.grey("   because ").append(reason.withStyle(ChatFormatting.GRAY)))
            }
        }
        return town.chronicle.size
    }
}
