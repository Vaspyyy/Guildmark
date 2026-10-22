package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer

/**
 * The following's commands under `/guildmark tale` (living-world/stories/design.md 14.8, Part D): `tale rows [player]` shows what a
 * player's tracker holds and what the record says it should hold, and how many times it has been sent. Game masters only, like the
 * rest of `tale`. It only reads: nothing is written to any tale, and with tales not in the build it answers in one grey line.
 */
internal object TaleFollowCommands {
    /** Appends the following's subcommands to the `tale` command (one line of `Tales.register` calls this through [TaleRows.register]). */
    fun add() {
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("rows")
                    .executes { rows(it, it.source.playerOrException) }
                    .then(Commands.argument("player", EntityArgument.player()).executes { rows(it, EntityArgument.getPlayer(it, "player")) }),
            )
        }
    }

    private fun say(source: CommandSourceStack, c: Component): Int = TaleCommand.say(source, c)

    private fun labelled(label: String, text: String): MutableComponent = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    /** True, and says so, while tales are not in this build: a command then answers in one grey line and touches nothing. */
    private fun notReady(source: CommandSourceStack): Boolean {
        if (Tales.READY) return false
        say(source, TaleCommand.grey(TaleCommand.off() ?: "Tales are not ready in this build."))
        return true
    }

    // ---- tale rows ----

    /** `tale rows [player]`: the rows [player]'s tracker holds, against the rows the record would give them now, and how often they were sent. */
    private fun rows(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val source = context.source
        if (notReady(source)) return 1
        TaleCommand.off()?.let { say(source, TaleCommand.grey(it)) }
        val held = player.getData(ModAttachments.TALES)
        say(source, TaleCommand.header("Tale rows of ${player.name.string}"))
        say(source, labelled("On the tracker", if (held.size == 1) "1 row" else "${held.size} rows"))
        val times = TaleRows.sentTo(player)
        say(source, labelled("Sent since they joined", if (times == 1) "once" else "$times times") .append(TaleCommand.grey("  (once for each change)")))
        for (r in held) say(source, describe(r))
        if (!Tales.live) {
            say(source, TaleCommand.grey("Tales are off, so no row is built and none is kept."))
            return 1
        }
        val fresh = TaleRows.build(source.level.server.overworld(), player)
        if (fresh == held) {
            say(source, TaleCommand.grey("The tracker holds what the record says."))
        } else {
            say(source, TaleCommand.grey("The record says otherwise; within two seconds the tracker will hold:"))
            for (r in fresh) say(source, describe(r))
            if (fresh.isEmpty()) say(source, TaleCommand.grey("  no rows: ${player.name.string} is not IN a tale."))
        }
        return 1
    }

    /** One row on one line: the tale, its words as the viewer's own language has them, the ink, the days left and the place. */
    private fun describe(r: TaleRow): MutableComponent {
        val ink = when (r.tone) {
            TaleRow.RED -> "red"
            TaleRow.GREEN -> "green"
            else -> "amber"
        }
        val place = r.place?.let { "at ${it.x} ${it.y} ${it.z}" } ?: "no place"
        return TaleCommand.line()
            .append(TaleCommand.label("  #${r.id} ${r.template}, step ${r.step}: "))
            .append(Component.translatable(r.title, *r.titleArgs.toTypedArray()).withStyle(net.minecraft.ChatFormatting.WHITE))
            .append(TaleCommand.grey(" / "))
            .append(Component.translatable(r.status, *r.statusArgs.toTypedArray()).withStyle(net.minecraft.ChatFormatting.WHITE))
            .append(TaleCommand.grey("  ($ink, ${r.days} day${if (r.days == 1) "" else "s"} left, $place)"))
    }
}
