package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.CapitalCommand
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * `/guildmark trade say [key]` (trader design 12.15, the voices; operators only): the townsperson you look at (as
 * `/guildmark capital` finds them: up to 16 blocks off, else the nearest within 4) says the trade's voice lines in their
 * own voice, to hear the takes. `say <key>` is one line (`trade_hold_0`, `crier_hauls_2`; a talk wording such as
 * `talk.trade.short_1` too), `say <start of a key>` every line that starts so (`trade_hold` is the leader's three calls,
 * `trade_scribe` the scribe's seven), and `say` alone every line of the trade in turn: the merchant's, the keeper's, the
 * crier's, the street's, the outlaws', the guards', the Store's and the House's, each as long as it lasts and a second
 * more, with its key and words in chat as it is said. `say stop` ends a run; a new one ends the player's last.
 *
 * They say it as the game has them say it: in the voice their look gives them (a man, a woman, an old man or a child),
 * borrowing the man's, lower, for the old man and the woman's, higher, for a child where the line has no take of theirs; a
 * faction leader keeps their own voice and is refused. The talk families (`talk.trade.*`) are left out of the run;
 * `/guildmark talk family trade.short` says a family's. Nothing is kept: it changes the speech over their head, the chat
 * and the one run in hand. Tab completion lists the keys and the starts of them.
 *
 * [Trade.register] calls [init]. The `trade` literal is the one [TradeCommand] has, with the same permission, so Brigadier
 * merges them by name and neither file edits the other.
 */
object TradeSayCommand {
    /** A second of quiet after each line, on top of how long it lasts, in ticks. */
    private const val GAP = 20

    /** How near a player must be for a line to be said at all, in blocks: as far as the crier's cries carry (48, which is the client's own limit too). */
    private const val HEARING = 48.0

    /** One run: [person] says [keys] to [player] in turn, the next at tick [due] of the server. */
    private class Run(val player: UUID, val person: Townsperson, val keys: List<String>, var next: Int, var due: Long)

    private val runs = HashMap<UUID, Run>()

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> runs.clear() }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("trade")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("say")
                                .executes { say(it, null) }
                                .then(
                                    Commands.argument("key", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(suggestions(), builder) }
                                        .executes { say(it, StringArgumentType.getString(it, "key")) },
                                ),
                        ),
                ),
        )
    }

    /** The trade's lines that have takes, by key: `trade_*`, the crier's `crier_hauls*` and `crier_house`. */
    private fun keys(): List<String> =
        TalkLines.OTHERS.keys.filter { it.startsWith("trade_") || it.startsWith("crier_hauls") || it == "crier_house" }.sorted()

    /** The talk wordings of the trade's families (`talk.trade.short_0` and the rest). */
    private fun wordings(): List<String> =
        TalkLines.FAMILIES.filterKeys { it.startsWith("trade.") }.flatMap { (family, f) -> (0 until f.count).map { "talk.${family}_$it" } }

    /** A key without its number: `trade_hold_0` is of `trade_hold`. */
    private fun family(key: String): String = key.replace(Regex("_\\d+$"), "")

    private fun suggestions(): List<String> {
        val keys = keys()
        return listOf("stop") + keys.map { family(it) }.distinct() + keys + wordings()
    }

    private fun say(context: CommandContext<CommandSourceStack>, name: String?): Int {
        val source = context.source
        val player = source.playerOrException
        if (name == "stop") {
            return if (runs.remove(player.uuid) != null) tell(source, "Stopped.") else fail(source, "Nothing is being said.")
        }
        val p = CapitalCommand.lookedAt(player) ?: return fail(source, "Look at a townsperson (up to 16 blocks off), or stand right by one.")
        if (p.isLeader) return fail(source, "${p.name.string} is a faction leader, who keeps their own voice: look at someone else.")
        val every = keys()
        val picked = when {
            name == null -> every
            else -> (every + wordings()).filter { it == name }.ifEmpty { (every + wordings()).filter { it.startsWith(name) } }
        }
        if (picked.isEmpty()) {
            return fail(source, "No line of the trade starts with $name. They start: ${every.map { family(it) }.distinct().take(8).joinToString(", ")}...")
        }
        runs[player.uuid] = Run(player.uuid, p, picked, 0, source.server.tickCount.toLong())
        val seconds = picked.sumOf { Talk.ticks(p, it) + GAP } / 20
        return tell(
            source,
            "${p.name.string} (${Say.timbre(p)}) says ${if (picked.size == 1) picked[0] else "${picked.size} lines"}, " +
                "about $seconds seconds. /guildmark trade say stop ends it.",
            picked.size,
        )
    }

    /** Says each run's next line when it is due; nothing to look at while there is none. */
    private fun tick(server: MinecraftServer) {
        if (runs.isEmpty()) return
        val now = server.tickCount.toLong()
        val each = runs.values.iterator()
        while (each.hasNext()) {
            val r = each.next()
            if (now < r.due) continue
            val player = server.playerList.getPlayer(r.player)
            val p = r.person
            if (player == null || !p.isAlive || p.isRemoved || player.level() !== p.level()) {
                each.remove()
                continue
            }
            if (r.next >= r.keys.size) {
                each.remove()
                player.sendSystemMessage(Component.literal("Done: ${r.keys.size} lines.").withStyle(ChatFormatting.GRAY))
                continue
            }
            val key = r.keys[r.next++]
            val length = Talk.ticks(p, key)
            p.say(key, force = true, hearing = HEARING, ticks = length + GAP.toLong())
            player.sendSystemMessage(Component.literal("$key: ").withStyle(ChatFormatting.GRAY).append(Speech.text(key)))
            r.due = now + length + GAP
        }
    }

    private fun tell(source: CommandSourceStack, message: String, result: Int = 1): Int {
        source.sendSuccess({ Component.literal(message).withStyle(ChatFormatting.GRAY) }, false)
        return result
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
