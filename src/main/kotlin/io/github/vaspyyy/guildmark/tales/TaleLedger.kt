package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.Residents
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.players.NameAndId
import java.util.UUID

/**
 * The commands that show what a tale left behind (living-world/stories/design.md 12.4, Part E), appended to [TaleCommand.sub]:
 * `tale chronicle [all]` (the chronicle of the capital you stand in, or of every capital, newest first, with the sentence each
 * ending says and the reasons still on file), `tale flips` and `tale unflip <n>` (the trait flips of 6.5, numbered, and one put
 * back), and `tale owed [player] [pay|clear]` (the pay and refunds waiting for players who were away, 7.4: listed, paid now to a
 * player who is on, or dropped). The ones that only read answer with the tales off and say in grey what is off; the ones that write
 * say why they will not, except `owed pay` and `owed clear`, which need tales built in and no more, as the login pay does.
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
            tale.then(Commands.literal("flips").executes { flips(it) })
            tale.then(
                Commands.literal("unflip").then(
                    Commands.argument("n", IntegerArgumentType.integer(1, TaleLimits.FLIP_LOG))
                        .executes { unflip(it, IntegerArgumentType.getInteger(it, "n")) },
                ),
            )
            tale.then(
                Commands.literal("owed")
                    .executes { owed(it, null, "") }
                    .then(
                        Commands.argument("player", GameProfileArgument.gameProfile())
                            .executes { owed(it, profiles(it), "") }
                            .then(Commands.literal("pay").executes { owed(it, profiles(it), "pay") })
                            .then(Commands.literal("clear").executes { owed(it, profiles(it), "clear") }),
                    ),
            )
        }
    }

    private fun profiles(context: CommandContext<CommandSourceStack>): List<NameAndId> = GameProfileArgument.getGameProfiles(context, "player").toList()

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

    // ---- The flips ----

    private fun flips(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        if (!Tales.READY) return 0
        val level = source.level
        val data = TaleData.get(level)
        if (data.flips.isEmpty()) return TaleCommand.say(source, TaleCommand.grey("No tale has changed anyone's ways."))
        TaleCommand.say(source, TaleCommand.header("Trait flips (${data.flips.size}, oldest first)"))
        val residents = Residents.get(level)
        data.flips.forEachIndexed { i, f ->
            val who = residents[f.resident]?.name ?: "resident ${f.resident}"
            val change = if (f.from.isEmpty()) "took up ${f.to}" else "${f.from} to ${f.to}"
            val tale = data[f.tale]?.let { "tale ${f.tale} (${it.template})" } ?: "tale ${f.tale}"
            TaleCommand.say(
                source,
                TaleCommand.line().append(TaleCommand.label("${i + 1}. ")).append(TaleCommand.plain("$who: $change")).append(TaleCommand.grey(", day ${f.day}, $tale")),
            )
        }
        TaleCommand.say(source, TaleCommand.grey("/guildmark tale unflip <n> puts one back."))
        return data.flips.size
    }

    private fun unflip(context: CommandContext<CommandSourceStack>, n: Int): Int {
        val source = context.source
        TaleCommand.off()?.let { return TaleCommand.fail(source, it) }
        val done = TaleFlips.unflip(source.level, n)
        return if (done.ok) TaleCommand.say(source, TaleCommand.plain(done.text)) else TaleCommand.fail(source, done.text)
    }

    // ---- What is owed ----

    /** [who] (null: everyone) with [action]: "" to list what waits, "pay" to pay it now to a player who is on, "clear" to drop it. */
    private fun owed(context: CommandContext<CommandSourceStack>, who: List<NameAndId>?, action: String): Int {
        val source = context.source
        val level = source.level
        if (action.isEmpty()) {
            TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        }
        if (!Tales.READY) return if (action.isEmpty()) 0 else TaleCommand.fail(source, "Tales are not ready in this build.")
        val data = TaleData.get(level)
        val ids = who?.map { it.id }?.toSet()
        val lines = data.owed.filter { ids == null || it.player in ids }
        when (action) {
            "pay" -> {
                var paid = 0
                for (p in who.orEmpty()) {
                    val mine = data.owed.count { it.player == p.id }
                    val player = level.server.playerList.getPlayer(p.id)
                    when {
                        mine == 0 -> TaleCommand.say(source, TaleCommand.grey("${p.name} is owed nothing."))
                        player == null -> TaleCommand.fail(source, "${p.name} is not on: what they are owed waits for their next login.")
                        else -> {
                            Tales.owed(level, player)
                            paid += mine
                            TaleCommand.say(source, TaleCommand.plain("Paid ${p.name} $mine line${if (mine == 1) "" else "s"}."))
                        }
                    }
                }
                return paid
            }
            "clear" -> {
                data.owed.removeAll(lines.toSet())
                if (lines.isNotEmpty()) data.changed()
                return TaleCommand.say(source, TaleCommand.plain("Dropped ${lines.size} line${if (lines.size == 1) "" else "s"} of pay and refunds."))
            }
        }
        if (lines.isEmpty()) return TaleCommand.say(source, TaleCommand.grey(if (who == null) "No pay or refund is waiting for anyone." else "Nothing is waiting for them."))
        TaleCommand.say(source, TaleCommand.header("Owed (${lines.size})"))
        for (l in lines) {
            val what = if (l.marks > 0) "${l.marks} Marks given back" else "${l.points} points of pay, ended ${l.ending}"
            TaleCommand.say(
                source,
                TaleCommand.line().append(TaleCommand.label(name(level, l.player) + ": ")).append(TaleCommand.plain(what))
                    .append(TaleCommand.grey(" (${l.template}, tale ${l.tale})")),
            )
        }
        TaleCommand.say(source, TaleCommand.grey("/guildmark tale owed <player> pay | clear"))
        return lines.size
    }

    private fun name(level: ServerLevel, player: UUID): String = TaleWords.leadName(level, player).ifEmpty { player.toString().take(8) }
}
