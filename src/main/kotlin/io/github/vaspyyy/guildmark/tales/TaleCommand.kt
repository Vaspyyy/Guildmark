package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark tale`, the testing commands for tales (living-world/stories/design.md 12.4), game masters only, run standing
 * in a capital. This is the root and nothing more: every part adds the subcommands that show its own work by appending to
 * [sub] (`TaleCommand.sub += { it.then(Commands.literal("show").executes { ... }) }`), so no two parts edit one function,
 * and uses [off], [here] and the text helpers below as `/guildmark fortune` uses its own. The bare command says whether
 * tales are going, and in a capital whether one is open: while they are not ready it is one grey line.
 */
object TaleCommand {
    /**
     * What each part adds to the `tale` command: a function given the `tale` literal, to hang its subcommands on. Appended
     * to when the part registers (from [Tales.register]), which is before the commands are built, and read when they are.
     */
    val sub: MutableList<(LiteralArgumentBuilder<CommandSourceStack>) -> Unit> = ArrayList()

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val tale = Commands.literal("tale")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .executes { show(it) }
        for (add in sub) add(tale)
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(tale),
        )
    }

    // ---- The capital you stand in ----

    /** The capital a command is run in, with its market. */
    internal class Here(val capital: CapitalPiece, val entry: Markets.Entry) {
        /** The key tales go by, as the fortunes do: the capital's heart. */
        val key: Long get() = entry.market.capital
        val name: String get() = VillageNames.of(capital.boardAnchor)
    }

    /** The capital [context]'s player stands in, with its market; null (and why, said) if there isn't one. */
    internal fun here(context: CommandContext<CommandSourceStack>): Here? {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val entry = Markets.of(level, capital)
        if (entry == null) {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        return Here(capital, entry)
    }

    /** Why tales aren't going, or null if they are: the first thing that is off, in the words of the settings screen. */
    internal fun off(): String? = when {
        !Tales.READY -> "Tales are not ready in this build."
        Tales.enabled() -> null
        !Markets.enabled() -> "The markets are switched off (setting: Capital Markets), so tales are too."
        !Days.enabled() -> "The capitals' living days are switched off (setting: Living Capitals), so their people have no troubles to tell."
        !Talk.enabled() -> "Talking with townsfolk is switched off (setting: Talking with Townsfolk), so a tale could not be asked."
        else -> "Tales are off (setting: Town Tales)."
    }

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        off()?.let { return say(source, grey(it)) }
        val here = here(context) ?: return 0
        say(source, header("Tales in ${here.name}"))
        say(source, plain("No tale here."))
        say(source, grey("This build has no tale engine yet, so none can be open."))
        return 1
    }

    // ---- Text ----

    internal fun line(): MutableComponent = Component.empty()
    internal fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    internal fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    internal fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    internal fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    /** Sends [c] to the one who ran the command; 1, so a command can end with `return say(...)`. */
    internal fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    /** Sends [message] as a failure; 0. */
    internal fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
