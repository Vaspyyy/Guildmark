package io.github.vaspyyy.guildmark.townsfolk.talk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.CapitalCommand
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * Testing commands for townsfolk talk (living-world/design-voices.md section 7), on the townsperson you're looking at
 * (as `/guildmark capital` finds them): `/guildmark talk topics [occasion]` lists every topic they have for you now,
 * with its weight, factors and score, best first; `say <id>` has them say a take of a family, or a line as written;
 * `family <family>` has them say each of its wordings in turn, to check the takes and their lengths; `open` starts a
 * talk even if they're busy. For the street ([Street]): `bark` has them notice you as in passing, now; `chat` has them
 * start a chat with whoever's nearest; `street` lists what's been said round you in the last minute, and why.
 */
object TalkDebug {
    /** The most topics `topics` lists. */
    private const val SHOWN = 12

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("talk")
                        .then(
                            Commands.literal("topics")
                                .executes { topics(it, Occasion.OPENER) }
                                .then(
                                    Commands.argument("occasion", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(Occasion.entries.map { it.name.lowercase(Locale.ROOT) }, builder) }
                                        .executes { context ->
                                            val name = StringArgumentType.getString(context, "occasion")
                                            val occasion = Occasion.entries.firstOrNull { it.name.equals(name, true) }
                                                ?: return@executes fail(context.source, "No such occasion: $name.")
                                            topics(context, occasion)
                                        },
                                ),
                        )
                        .then(
                            Commands.literal("say")
                                .then(
                                    Commands.argument("id", StringArgumentType.greedyString())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(TalkLines.FAMILIES.keys, builder) }
                                        .executes { say(it, StringArgumentType.getString(it, "id")) },
                                ),
                        )
                        .then(
                            Commands.literal("family")
                                .then(
                                    Commands.argument("family", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(TalkLines.FAMILIES.keys, builder) }
                                        .executes { family(it, StringArgumentType.getString(it, "family")) },
                                ),
                        )
                        .then(Commands.literal("open").executes { open(it) })
                        .then(Commands.literal("bark").executes { bark(it) })
                        .then(Commands.literal("chat").executes { chat(it) })
                        .then(Commands.literal("street").executes { street(it) }),
                ),
        )
    }

    /** `bark`: they notice you now, as they would in passing (whatever the hour since the last time, and the chances). */
    private fun bark(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val p = person(source, player) ?: return 0
        return say(source, Street.barkNow(source.level, player, p)).let { 1 }
    }

    /** `chat`: they start a chat with whoever's nearest and free. */
    private fun chat(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val p = person(source, player) ?: return 0
        return say(source, Street.chatNow(p)).let { 1 }
    }

    /** `street`: what's been said round you in the last minute and why, the rates, and how the one you look at stands with you. */
    private fun street(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        for (line in Street.report(source.level, player, CapitalCommand.lookedAt(player))) say(source, line)
        return 1
    }

    private fun topics(context: CommandContext<CommandSourceStack>, occasion: Occasion): Int {
        val source = context.source
        val player = source.playerOrException
        val p = person(source, player) ?: return 0
        val ask = Talk.ask(source.level, p, player, occasion)
        val scored = Topics.all(ask)
        say(source, Component.literal("${p.name.string}, ${occasion.name.lowercase(Locale.ROOT)}: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal("${ask.familiarity.name.lowercase(Locale.ROOT)}, ${Topics.registered.size} sources, ${scored.size} topics").withStyle(ChatFormatting.GRAY)))
        if (scored.isEmpty()) return say(source, Component.literal("Nothing to say: they nod.").withStyle(ChatFormatting.GRAY)).let { 1 }
        for (s in scored.take(SHOWN)) {
            val first = s.topic.say.parts.firstOrNull()
            val line = Component.literal("%.2f ".format(Locale.ROOT, s.score)).withStyle(if (s.score >= Topics.LEAST) ChatFormatting.GREEN else ChatFormatting.DARK_GRAY)
                .append(Component.literal(s.topic.id).withStyle(ChatFormatting.WHITE))
                .append(
                    Component.literal(
                        " ${s.topic.kind.name.lowercase(Locale.ROOT)} w%.2f f%.2f l%.2f n%.2f".format(Locale.ROOT, s.topic.weight, s.familiarity, s.leaning, s.freshness),
                    ).withStyle(ChatFormatting.GRAY),
                )
            say(source, line)
            if (first != null) say(source, Component.literal("   ").append(if (first.line.isEmpty()) first.detail?.let(Speech::said) ?: Component.empty() else Speech.text(Speech.withDetail(first.line, first.detail))))
        }
        if (scored.size > SHOWN) say(source, Component.literal("...and ${scored.size - SHOWN} more.").withStyle(ChatFormatting.GRAY))
        return 1
    }

    private fun say(context: CommandContext<CommandSourceStack>, id: String): Int {
        val source = context.source
        val player = source.playerOrException
        val p = person(source, player) ?: return 0
        val ask = Talk.ask(source.level, p, player, Occasion.OPENER)
        val line = if (id in TalkLines.FAMILIES) Topics.take(ask, id) ?: return fail(source, "$id has no takes recorded.") else id
        p.say(line, force = true, ticks = (TalkLines.ticks(Speech.key(line)).takeIf { it > 0 } ?: 40) + 20L)
        Topics.spoke(p, player, Speech.key(line))
        return say(source, Component.literal("${Speech.key(line)}: ").withStyle(ChatFormatting.GRAY).append(Speech.text(line))).let { 1 }
    }

    private fun family(context: CommandContext<CommandSourceStack>, family: String): Int {
        val source = context.source
        val player = source.playerOrException
        val p = person(source, player) ?: return 0
        val f = TalkLines.FAMILIES[family] ?: return fail(source, "$family has no takes recorded.")
        val lines = (0 until f.count).map { "talk.${family}_$it" }
        Talk.recite(p, lines)
        val total = lines.sumOf { (TalkLines.ticks(it).takeIf { t -> t > 0 } ?: 40) + 20 }
        return say(source, Component.literal("$family: ${f.count} wordings, about ${total / 20} seconds in all${if (f.named) ", named" else ""}; ${f.timbres.sorted().joinToString()}").withStyle(ChatFormatting.GRAY)).let { 1 }
    }

    private fun open(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val p = person(source, player) ?: return 0
        if (!Talk.open(source.level, player, p, null, true)) return fail(source, "No talk opened: talking is off, the talk screen isn't in this build yet, or they can't talk just now.")
        return 1
    }

    private fun person(source: CommandSourceStack, player: ServerPlayer): Townsperson? =
        CapitalCommand.lookedAt(player) ?: null.also { fail(source, "Look at a townsperson (up to 16 blocks off), or stand right by one.") }

    private fun say(source: CommandSourceStack, c: Component) = source.sendSuccess({ c }, false)

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
