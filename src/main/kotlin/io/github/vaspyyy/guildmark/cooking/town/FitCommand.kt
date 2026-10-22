package io.github.vaspyyy.guildmark.cooking.town

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.cooking.town.KitchenFit.Finding
import io.github.vaspyyy.guildmark.economy.Suppliers
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark cook fit [here|all|full|preview]` (operators only; living-world/cook/design.md 7.2 and 10.3): the kitchens'
 * retrofit by hand, with players about, under the `cook` root that `CookCommand` makes.
 *
 *  - `cook fit` and `cook fit here` fit the capital you stand in: each block goes in with a puff and its own sound. A capital drawn
 *    since the plans have the kitchens (plan version 3) has them already and is said so.
 *  - `cook fit all` does it for every capital within sight of a player.
 *  - `cook fit full` also tries the Spit, which the round never does; `cook fit preview` only says what would be done.
 *  - `here` and `all` take `full` or `preview` after them (`cook fit all preview`).
 *
 * It reports each tavern's and bakery's parts, where they are and why any was kept, and the totals as `Furnish.Report` does:
 * made, kept, no room, waiting. With Town Kitchens off it only says what it would do. The first line names the capital's plan
 * version. Run in a capital that has been fitted, it changes nothing (the blocks are the pots, oven and slates already).
 */
internal object FitCommand {
    /** How far round a player `cook fit all` looks for capitals. */
    private const val SIGHT = 128

    fun register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(fit()),
                ),
        )
    }

    // ---- The grammar ----

    private fun fit(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("fit")
        .executes { run(it, here = true, FitMode.SEEN) }
        .then(scope("here", here = true))
        .then(scope("all", here = false))
        .then(Commands.literal("full").executes { run(it, here = true, FitMode.FULL) })
        .then(Commands.literal("preview").executes { run(it, here = true, FitMode.PREVIEW) })

    private fun scope(word: String, here: Boolean): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(word)
        .executes { run(it, here, FitMode.SEEN) }
        .then(Commands.literal("full").executes { run(it, here, FitMode.FULL) })
        .then(Commands.literal("preview").executes { run(it, here, FitMode.PREVIEW) })

    // ---- Running it ----

    private fun run(context: CommandContext<CommandSourceStack>, here: Boolean, mode: FitMode): Int {
        val source = context.source
        val level = source.level
        val capitals: List<CapitalPiece>
        if (here) {
            val player = source.player ?: return fail(source, "Run this as a player, standing in a capital, or use `cook fit all`.")
            capitals = listOfNotNull(Capitals.covering(level, player.blockPosition()))
            if (capitals.isEmpty()) return fail(source, "You're not in a capital.")
        } else {
            capitals = within(level)
            if (capitals.isEmpty()) return fail(source, "No capital is within sight of a player.")
        }
        var made = 0
        for (capital in capitals) made += describe(source, level, capital, KitchenFit.fit(level, capital, mode))
        if (capitals.size > 1) say(source, line().append(label("Capitals looked at: ")).append(plain("${capitals.size}, ${if (mode == FitMode.PREVIEW) "would make" else "made"} $made in all")))
        return made.coerceAtLeast(1)
    }

    /** The capitals within [SIGHT] blocks of any player, each once. */
    private fun within(level: ServerLevel): List<CapitalPiece> {
        val found = LinkedHashMap<BlockPos, CapitalPiece>()
        for (player in level.players()) {
            val pos = player.blockPosition()
            for (capital in Capitals.around(level, pos.x - SIGHT, pos.z - SIGHT, pos.x + SIGHT, pos.z + SIGHT)) found.putIfAbsent(capital.center, capital)
        }
        return found.values.toList()
    }

    // ---- What it says ----

    /** Says what came of one capital; returns how many blocks were made (or would be, in a preview). */
    private fun describe(source: CommandSourceStack, level: ServerLevel, capital: CapitalPiece, result: KitchenFit.Result): Int {
        val place = Suppliers.place(level, capital.center).ifEmpty { "the capital at ${capital.center.x}, ${capital.center.z}" }
        val noPlan = capital.layout == null
        say(source, header("Kitchen fit of $place: ${if (noPlan) "no plan" else "plan version ${result.version}"}"))
        if (!result.old) {
            say(
                source,
                grey(
                    if (noPlan) "  This capital was saved by an older version of the mod and has no plan, so there is nothing to fit."
                    else "  This capital was drawn with the kitchens (plan version ${KitchenFit.KITCHENS} or later), so there is nothing to fit.",
                ),
            )
            return 0
        }
        if (!result.places) {
            say(source, grey("  Its places are still being worked out: try again in a second."))
            return 0
        }
        when {
            result.off -> say(source, line().append(label("Town Kitchens is off: ")).append(plain("nothing is changed; this is what a run would do.")))
            result.mode == FitMode.PREVIEW -> say(source, grey("  A preview: nothing is changed."))
            result.mode == FitMode.FULL -> say(source, grey("  Fitting in full, the spit as well, with a puff and a sound where each block goes."))
            else -> say(source, grey("  Fitting, with a puff and a sound where each block goes."))
        }
        val preview = result.mode == FitMode.PREVIEW
        var lodging: Int? = null
        for (item in result.items) {
            if (lodging != item.lodging.id) {
                lodging = item.lodging.id
                say(source, line().append(label("${item.lodging.kind.lowercase().replaceFirstChar { it.uppercase() }} #${item.lodging.id}: ")).append(grey("at ${item.lodging.box.minX()}, ${item.lodging.box.minZ()} to ${item.lodging.box.maxX()}, ${item.lodging.box.maxZ()}")))
            }
            val what = when (item.finding) {
                Finding.READY -> if (preview) "would make it" else "ready"
                Finding.MADE -> "made"
                Finding.DONE -> "already there"
                Finding.CHANGED -> "kept as it is"
                Finding.NO_ROOM -> "no room"
                Finding.WAITING -> "waiting"
                Finding.LATER -> "not tried"
            }
            val colour = when (item.finding) {
                Finding.READY, Finding.MADE -> ChatFormatting.GREEN
                Finding.NO_ROOM, Finding.CHANGED -> ChatFormatting.RED
                Finding.WAITING -> ChatFormatting.YELLOW
                else -> ChatFormatting.GRAY
            }
            say(
                source,
                line().append(plain("  ${item.part.label} at ${item.pos.x} ${item.pos.y} ${item.pos.z}: "))
                    .append(Component.literal(what).withStyle(colour)).append(grey(" - ${item.note}")),
            )
        }
        if (result.items.isEmpty()) say(source, grey("  No tavern or bakery to fit."))
        val r = result.report
        say(
            source,
            line().append(label(if (preview) "Would make: " else "Made: ")).append(plain("${r.made}")).append(grey(" / "))
                .append(label("kept: ")).append(plain("${r.kept}")).append(grey(" / "))
                .append(label("no room: ")).append(plain("${r.noRoom}")).append(grey(" / "))
                .append(label("waiting: ")).append(plain("${r.waiting}")),
        )
        return r.made
    }

    // ---- Words ----

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
