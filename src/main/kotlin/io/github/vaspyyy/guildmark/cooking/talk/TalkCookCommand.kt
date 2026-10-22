package io.github.vaspyyy.guildmark.cooking.talk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.townsfolk.CapitalCommand
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Memory
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Street
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Feasted
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenRumours
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenTopics
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenWords
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * Part J's testing commands (operators only; living-world/cook/design.md 8.6, 10.3), under the `cook` root that `CookCommand`
 * makes, on the townsperson you are looking at (as `/guildmark talk` finds them):
 *
 *  - `cook talk [occasion]` lists what the kitchen's topics offer them for you now, with each one's weight, factors and score
 *    (as `talk topics` does for all), the ones they would not say to someone they know as little as you, and, for an
 *    innkeeper or a baker, the choice "About the kitchen..." and what it would answer. For `chat` it asks as the street does
 *    of a chat between neighbours (the one nearest them, no player in it), which is where a good supper is told.
 *  - `cook talk say <family>` has them say each wording of a kitchen family in turn, as takes if the family is recorded and as
 *    text if not, and prints the wordings.
 *  - `cook rumour hallmark|cookoff|burnt [dish]` starts that rumour at your feet, seen by those near.
 *  - `cook relish plain|sound|fine|hallmarked [dish]` gives them the favour and the feast as if they had eaten a dish of that
 *    tier from you.
 *
 * `/guildmark talk topics`, `/guildmark memory` and `/guildmark rumour list` show the rest with no change.
 */
object TalkCookCommand {
    /** The most topics `cook talk` lists. */
    private const val SHOWN = 12

    /** How far from them `cook talk chat` looks for the neighbour they would chat with (blocks). */
    private const val CHAT_REACH = 8.0

    /** Part J's registration: the `talk`, `rumour` and `relish` branches under `cook`, the line `cook perf` prints and the clearing of its counters. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        CookStats.readout("Kitchen talk") { readout() }
        Cooking.onForget {
            KitchenTopics.asks = 0L
            KitchenTopics.nanos = 0L
            KitchenRumours.begun = 0L
            Feasted.kept = 0L
        }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(talk())
                        .then(rumour())
                        .then(relish()),
                ),
        )
    }

    private fun talk(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("talk")
        .executes { topics(it, Occasion.OPENER) }
        .then(
            Commands.literal("say").then(
                Commands.argument("family", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(KitchenWords.COUNT.keys, builder) }
                    .executes { sayFamily(it, StringArgumentType.getString(it, "family")) },
            ),
        )
        .then(
            Commands.argument("occasion", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(Occasion.entries.map { it.name.lowercase(Locale.ROOT) }, builder) }
                .executes { context ->
                    val name = StringArgumentType.getString(context, "occasion")
                    val occasion = Occasion.entries.firstOrNull { it.name.equals(name, true) } ?: return@executes fail(context.source, "No such occasion: $name.")
                    topics(context, occasion)
                },
        )

    private fun rumour(): LiteralArgumentBuilder<CommandSourceStack> {
        val node = Commands.literal("rumour")
        for (what in listOf("hallmark", "cookoff")) {
            node.then(
                Commands.literal(what)
                    .executes { rumour(it, what, null) }
                    .then(dishArgument().executes { rumour(it, what, StringArgumentType.getString(it, "dish")) }),
            )
        }
        return node.then(Commands.literal("burnt").executes { rumour(it, "burnt", null) })
    }

    private fun relish(): LiteralArgumentBuilder<CommandSourceStack> {
        val node = Commands.literal("relish")
        for (tier in listOf(Tier.PLAIN, Tier.SOUND, Tier.FINE, Tier.HALLMARKED)) {
            node.then(
                Commands.literal(tier.name.lowercase(Locale.ROOT))
                    .executes { relish(it, tier, null) }
                    .then(dishArgument().executes { relish(it, tier, StringArgumentType.getString(it, "dish")) }),
            )
        }
        return node
    }

    private fun dishArgument() = Commands.argument("dish", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(DishKind.entries.map { it.id }, builder) }

    // ---- cook talk ----

    private fun topics(context: CommandContext<CommandSourceStack>, occasion: Occasion): Int {
        val source = context.source
        val player = source.playerOrException
        val p = person(source, player) ?: return 0
        val level = source.level
        // A chat is between neighbours with no player in it: asked as the street asks it, of the nearest townsperson to them
        val other = if (occasion == Occasion.CHAT) {
            level.getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(CHAT_REACH)) { it !== p && it.isAlive }.minByOrNull { it.distanceToSqr(p) }
        } else {
            null
        }
        val ask = if (occasion == Occasion.CHAT) Street.chat(level, p, other) else Talk.ask(level, p, player, occasion)
        val offered = Topics.offered(ask).filter { it.id.startsWith("kitchen.") }
        val scored = offered.filter { ask.familiarity.reaches(it.least) }.map { Topics.score(ask, it) }.sortedByDescending { it.score }
        val hidden = offered.filter { !ask.familiarity.reaches(it.least) }
        val with = if (occasion == Occasion.CHAT) ", with ${other?.name?.string ?: "no one near"}" else ""
        say(
            source,
            header("${p.name.string} (${p.role.id}), ${occasion.name.lowercase(Locale.ROOT)}$with").append(
                grey(" ${ask.familiarity.name.lowercase(Locale.ROOT)}, ${scored.size} kitchen topics" + if (hidden.isEmpty()) "" else ", ${hidden.size} they would not say yet"),
            ),
        )
        if (scored.isEmpty() && hidden.isEmpty()) say(source, grey("  Nothing from the kitchen: ${why(ask, p)}."))
        for (s in scored.take(SHOWN)) {
            say(
                source,
                line().append(grey("%.2f ".format(Locale.ROOT, s.score))).append(plain(s.topic.id)).append(
                    grey(" ${s.topic.kind.name.lowercase(Locale.ROOT)} w%.2f f%.2f l%.2f n%.2f".format(Locale.ROOT, s.topic.weight, s.familiarity, s.leaning, s.freshness)),
                ),
            )
            s.topic.say.parts.firstOrNull()?.let { say(source, grey("    ").append(said(it))) }
        }
        if (scored.size > SHOWN) say(source, grey("  ...and ${scored.size - SHOWN} more."))
        for (topic in hidden) say(source, line().append(grey("  not yet: ")).append(plain(topic.id)).append(grey(" (needs them to know you as ${topic.least.name.lowercase(Locale.ROOT)})")))
        if (occasion != Occasion.CHAT) choice(source, p, player)
        return 1
    }

    /** The choice "About the kitchen..." for [p] and what it would answer, if it is on offer. */
    private fun choice(source: CommandSourceStack, p: Townsperson, player: ServerPlayer) {
        val ask = Talk.ask(source.level, p, player, Occasion.ANSWER)
        val choice = Topics.choices(ask).firstOrNull { it.id == KitchenTopics.ASK } ?: return
        say(source, line().append(label("Choice: ")).append(Component.empty().append(choice.label).withStyle(ChatFormatting.WHITE)).append(grey("  pressing %.2f".format(Locale.ROOT, choice.pressing))))
        val answer = Topics.answer(ask, choice.id) ?: return say(source, grey("  (it would answer nothing now)"))
        answer.parts.forEachIndexed { i, part -> say(source, grey("  ${i + 1}. ").append(said(part))) }
    }

    /** Why a speaker has no kitchen topic for [ask], in a few facts. */
    private fun why(ask: Ask, p: Townsperson): String = listOf(
        "Kitchen Talk ${if (Cooking.talk()) "on" else "off"}",
        "back doors ${if (Cooking.doors()) "on" else "off"}",
        if (ask.life?.places is CityPlaces) "a capital's folk" else "not a capital's folk (a village has no back door)",
        p.role.id,
    ).joinToString(", ")

    /** What [part] says, its detail after it. */
    private fun said(part: Utterance.Part): Component =
        if (part.line.isEmpty()) part.detail?.let(Speech::said) ?: Component.empty() else Speech.text(Speech.withDetail(part.line, part.detail))

    // ---- cook talk say ----

    private fun sayFamily(context: CommandContext<CommandSourceStack>, family: String): Int {
        val source = context.source
        val player = source.playerOrException
        val count = KitchenWords.COUNT[family] ?: return fail(source, "No such kitchen family: $family (tab completes them).")
        val p = person(source, player) ?: return 0
        val lines = (0 until count).map { "talk.${family}_$it" }
        Talk.recite(p, lines)
        val recorded = (TalkLines.FAMILIES[family]?.count ?: 0) > 0
        val seconds = lines.sumOf { Talk.ticks(p, it) + 20 } / 20
        say(source, grey("$family: $count wordings, ${if (recorded) "as takes" else "as text, no take recorded"}, about $seconds seconds in all."))
        for (key in lines) say(source, grey("  ").append(Speech.text(key)))
        return 1
    }

    // ---- cook rumour ----

    private fun rumour(context: CommandContext<CommandSourceStack>, what: String, dish: String?): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        if (!Cooking.talk()) return fail(source, "Kitchen Talk is switched off (setting: Kitchen Talk): the kitchen starts no rumours.")
        val kind = if (dish == null) null else DishKind.byId(dish) ?: return fail(source, "No such dish: $dish.")
        val here = player.blockPosition()
        val made = when (what) {
            "hallmark" -> KitchenRumours.hallmark(level, here, player, kind ?: DishKind.PIE)
            "cookoff" -> KitchenRumours.cookoff(level, here, player, kind ?: DishKind.ROAST)
            else -> KitchenRumours.burnt(level, here)
        }
        if (made == null) {
            say(
                source,
                plain("No rumour begun: no townsperson near enough saw it (${if (what == "cookoff") "48" else "16"} blocks, in sight), or it heated the same one already going round (see /guildmark rumour list)."),
            )
            return 0
        }
        val dishName = when (what) {
            "hallmark" -> " of " + (kind ?: DishKind.PIE).id
            "cookoff" -> " with " + (kind ?: DishKind.ROAST).id
            else -> ""
        }
        say(
            source,
            line().append(label("Rumour #${made.id}: ")).append(plain("kitchen.$what$dishName, heat %.2f, known first by those who saw it. Ask them for news, or watch the street.".format(Locale.ROOT, made.heat))),
        )
        if (what == "hallmark") say(source, grey("  Stand by the innkeeper or the baker to have it told \"at the inn\" or \"at the bakery\"."))
        return 1
    }

    // ---- cook relish ----

    private fun relish(context: CommandContext<CommandSourceStack>, tier: Tier, dish: String?): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        if (!Cooking.talk()) return fail(source, "Kitchen Talk is switched off (setting: Kitchen Talk): nothing is relished.")
        if (!Memory.enabled()) return fail(source, "Memory is switched off (setting: Townsfolk Remember You): nothing is kept.")
        if (r.id < 0) return fail(source, "${p.name.string} has no record: they keep nothing past the day.")
        val kind = if (dish == null) null else DishKind.byId(dish) ?: return fail(source, "No such dish: $dish.")
        TownTalk.relished(level, r, player.uuid, tier, kind)
        val what = when (tier) {
            Tier.SOUND -> "a small favour (counted once a day with the alms table's, up to the cap)"
            Tier.FINE, Tier.HALLMARKED -> "a favour (counted once a day with the alms table's, up to the cap)"
            else -> "nothing"
        }
        say(source, plain("${p.name.string} ate a ${tier.name.lowercase(Locale.ROOT)} dish of yours${kind?.let { " (${it.id})" } ?: ""}: $what, and a feast they remember for a week if it was Sound or better."))
        val feasts = Feasted.among(TownTalk.felt(level, r))
        if (feasts.isEmpty()) {
            say(source, grey("  They keep no feast now."))
        } else {
            say(source, grey("  They keep: " + feasts.joinToString(", ") { f -> "a feast on day ${f.day} (${Feasted.kind(f)?.id ?: "kind not told"}${if (Feasted.of(f, player.uuid)) ", yours" else ""})" }))
        }
        say(source, grey("  /guildmark memory shows their opinion of you."))
        return 1
    }

    // ---- Which townsperson ----

    private fun person(source: CommandSourceStack, player: ServerPlayer): Townsperson? =
        CapitalCommand.lookedAt(player) ?: null.also { fail(source, "Look at a townsperson (up to 16 blocks off), or stand right by one.") }

    private fun resident(source: CommandSourceStack, player: ServerPlayer): Pair<Townsperson, Resident>? {
        val p = person(source, player) ?: return null
        val r = Residents.get(source.level).of(p) ?: p.life?.resident
        if (r == null) {
            fail(source, "${p.name.string} has no record: a passer-by not yet taken in.")
            return null
        }
        return p to r
    }

    // ---- cook perf ----

    private fun readout(): String {
        val each = if (KitchenTopics.asks > 0) KitchenTopics.nanos / KitchenTopics.asks / 1000.0 else 0.0
        return "${KitchenTopics.asks} asks of the kitchen's topics at ${"%.1f".format(Locale.ROOT, each)} us each, ${KitchenRumours.begun} rumours begun, ${Feasted.kept} feasts kept"
    }

    // ---- Words ----

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("$s:").withStyle(ChatFormatting.GOLD)

    private fun say(source: CommandSourceStack, c: Component) {
        source.sendSuccess({ c }, false)
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
