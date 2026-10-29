package io.github.vaspyyy.guildmark.cooking

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark cook perf` (living-world/cook/design.md 9.5, 10.3): prints the lines [CookStats] holds, one for each part
 * that registered a readout (vessels ticking, scenes running, particles alive and the rest), and adds the one count only
 * this part keeps: the payloads Hearthcraft sent in the last minute ([sent], called where each is sent). Op only, as the
 * whole `cook` tree is. In a single-player game the client's readouts (particles, loops, the plates) show too, since the
 * client registered them in the same game; on a dedicated server only the server's do.
 */
object PerfCommand {
    /** The payloads it counts, by the name `cook perf` gives them. */
    enum class Payload(val label: String) { ACTION("cook actions"), SERVED("meals served") }

    /** The last minute, in one-second buckets: the second each bucket holds, and its count of each payload. */
    private const val SECONDS = 60
    private val second = LongArray(SECONDS) { -1L }
    private val counts = Array(Payload.entries.size) { IntArray(SECONDS) }

    /** Counts one [payload] sent now (to any number of players: one call is one send). Cheap: an array write. */
    @Synchronized
    fun sent(payload: Payload) {
        val now = System.currentTimeMillis() / 1000L
        val i = (now % SECONDS).toInt()
        if (second[i] != now) {
            second[i] = now
            for (c in counts) c[i] = 0
        }
        counts[payload.ordinal][i]++
    }

    /** How many of [payload] were sent in the last [SECONDS] seconds. */
    @Synchronized
    private fun lastMinute(payload: Payload): Int {
        val now = System.currentTimeMillis() / 1000L
        var n = 0
        for (i in 0 until SECONDS) if (second[i] > now - SECONDS) n += counts[payload.ordinal][i]
        return n
    }

    @Synchronized
    private fun clear() {
        second.fill(-1L)
        for (c in counts) c.fill(0)
    }

    /** Part M4's registration: the `perf` branch under `cook`, its own readout, and the clearing of its counts. */
    fun register() {
        Cooking.onForget { clear() }
        CookStats.readout("payloads in the last minute") {
            Payload.entries.joinToString(", ") { "${lastMinute(it)} ${it.label}" }
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("perf").executes { perf(it) }),
                ),
        )
    }

    private fun perf(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val lines = CookStats.lines()
        say(source, Component.literal("== Hearthcraft perf ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
        if (!Cooking.enabled()) say(source, Component.literal("Hearthcraft is off: the counts below stand still.").withStyle(ChatFormatting.GRAY))
        for (line in lines) {
            val cut = line.indexOf(": ")
            if (cut < 0) {
                say(source, Component.literal(line).withStyle(ChatFormatting.WHITE))
                continue
            }
            say(
                source,
                Component.literal(line.substring(0, cut + 2)).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(line.substring(cut + 2)).withStyle(ChatFormatting.WHITE)),
            )
        }
        return lines.size
    }

    private fun say(source: CommandSourceStack, c: Component) {
        source.sendSuccess({ c }, false)
    }
}
