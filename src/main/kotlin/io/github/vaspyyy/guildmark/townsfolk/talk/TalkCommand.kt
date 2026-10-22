package io.github.vaspyyy.guildmark.townsfolk.talk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.townsfolk.CapitalCommand
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.FortuneTopics
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.UUID
import kotlin.math.abs

/**
 * Testing commands for memory and rumours (living-world/design-voices.md section 7), on the townsperson you're looking
 * at as `/guildmark capital` finds them, or the capital you're in.
 *
 * `/guildmark memory` says what they remember of you, the opinion worked out (and from what) and the familiarity;
 * `memory set <opinion>` makes them think that of you now (it fades back over ten days), `memory meet` and `memory name`
 * are a talk and an introduction, `memory forget` wipes you from their mind, and `memory town` counts how many of the
 * town have met you, know your name, are friends or wary of you. For M6: `memory laboured <works>` gives them a
 * building day on that works today (their own line about it, for a month: Part B's Labour.kt records the real ones),
 * and `memory fed` has them eat at your alms table today (the FED favour, as Part D2's table will give it).
 *
 * `/guildmark rumour list` is the capital's rumours with their kind, whom they're about, heat, how many know them and
 * in which versions; `rumour show <id>` who knows one, from whom and where; `rumour seed <kind> [player] [heat]` starts
 * one, known by the townsperson you're looking at; `rumour spread [hours]` works out the next hours' talk now (up to 72)
 * and says how many knew each rumour every six hours of it, and how long it took; `rumour tell` has them tell you their
 * hottest; and
 * `rumour clear [id]` lets one go, or all of them.
 *
 * For M6's talk of the works and the times: `rumour household gone|back` starts the word of the looked-at
 * townsperson's household gone in hard times or come home (as Part D3's comings will), and `rumour household new [why]`
 * or `home` has that household talk as newcomers or as those come home for its first days (the households' talk is
 * heard though the comings are off, until the server stops); `rumour eased <works> mill|bread|band` has a standing works
 * ease the town today; and `rumour reason <kind> [facts]` notes a fortune reason in the capital as if it had happened,
 * with facts from the town (or given, split by commas), counted from the next close or now with `fortune step`.
 */
object TalkCommand {
    /** The most knowers `rumour show` lists. */
    private const val SHOWN = 24

    /** `rumour spread` counts the knowers after every this many hours. */
    private const val BLOCK = 6

    private val KINDS = listOf(
        "deed.town", "deed.saved", "deed.rescue", "deed.dragon", "deed.beast", "deed.escort", "slight.assault", "slight.murder",
        "slight.theft", "death", "newcomer", "festival.won", "homestead.raised", "market.event.blight", "market.hungry",
        "road.waylaid", "road.relief", "guild.advance", "trade.short", "trade.glut", "trade.hauled",
    )

    /** Kinds of good news and of bad besides the deeds, slights, outlaws and the market's events and their ends (see [Rumours]). */
    private val GOOD = setOf(
        "festival.won", "homestead.raised", "order.filled", "salvage.brought", "road.clear", "road.relief",
        "market.event.bumper", "market.event.good_catch",
    )
    private val BAD = setOf("death", "siege.lost", "road.waylaid", "market.hungry", "order.lapsed", "salvage.kept")

    /** `rumour household`: what befalls the household, and why newcomers came. */
    private val HOUSEHOLD = listOf("gone", "back", "new", "home")
    private val WHY = listOf("replace", "grow", "trade")

    /** `rumour eased`: what a standing works eases (Works.MILL, BREAD, BAND). */
    private val EASES = listOf(Works.MILL, Works.BREAD, Works.BAND)

    /** `rumour reason`: market days a reason noted is kept for. */
    private const val REASON_DAYS = 3

    fun register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("memory")
                        .executes { memory(it) }
                        .then(
                            Commands.literal("set").then(
                                Commands.argument("opinion", IntegerArgumentType.integer(-100, 100))
                                    .executes { set(it, IntegerArgumentType.getInteger(it, "opinion")) },
                            ),
                        )
                        .then(Commands.literal("meet").executes { meet(it, named = false) })
                        .then(Commands.literal("name").executes { meet(it, named = true) })
                        .then(Commands.literal("forget").executes { forget(it) })
                        .then(Commands.literal("town").executes { town(it) })
                        .then(
                            Commands.literal("laboured").then(
                                Commands.argument("works", IntegerArgumentType.integer(0)).executes { laboured(it, IntegerArgumentType.getInteger(it, "works")) },
                            ),
                        )
                        .then(Commands.literal("fed").executes { fed(it) }),
                )
                .then(
                    Commands.literal("rumour")
                        .then(Commands.literal("list").executes { list(it) })
                        .then(
                            Commands.literal("show").then(
                                Commands.argument("id", IntegerArgumentType.integer(1)).executes { show(it, IntegerArgumentType.getInteger(it, "id")) },
                            ),
                        )
                        .then(
                            Commands.literal("seed").then(
                                Commands.argument("kind", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(KINDS, builder) }
                                    .executes { seed(it, null, 0.8f) }
                                    .then(
                                        Commands.argument("player", EntityArgument.player())
                                            .executes { seed(it, EntityArgument.getPlayer(it, "player"), 0.8f) }
                                            .then(
                                                Commands.argument("heat", FloatArgumentType.floatArg(0f, 1f))
                                                    .executes { seed(it, EntityArgument.getPlayer(it, "player"), FloatArgumentType.getFloat(it, "heat")) },
                                            ),
                                    ),
                            ),
                        )
                        .then(
                            Commands.literal("spread")
                                .executes { spread(it, 24) }
                                .then(
                                    Commands.argument("hours", IntegerArgumentType.integer(1, 72))
                                        .executes { spread(it, IntegerArgumentType.getInteger(it, "hours")) },
                                ),
                        )
                        .then(Commands.literal("tell").executes { tell(it) })
                        .then(
                            Commands.literal("clear")
                                .executes { clear(it, null) }
                                .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes { clear(it, IntegerArgumentType.getInteger(it, "id")) }),
                        )
                        .then(
                            Commands.literal("household").then(
                                Commands.argument("what", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(HOUSEHOLD, b) }
                                    .executes { household(it, StringArgumentType.getString(it, "what"), null) }
                                    .then(
                                        Commands.argument("why", StringArgumentType.word())
                                            .suggests { _, b -> SharedSuggestionProvider.suggest(WHY, b) }
                                            .executes { household(it, StringArgumentType.getString(it, "what"), StringArgumentType.getString(it, "why")) },
                                    ),
                            ),
                        )
                        .then(
                            Commands.literal("eased").then(
                                Commands.argument("works", IntegerArgumentType.integer(0)).then(
                                    Commands.argument("what", StringArgumentType.word())
                                        .suggests { _, b -> SharedSuggestionProvider.suggest(EASES, b) }
                                        .executes { eased(it, IntegerArgumentType.getInteger(it, "works"), StringArgumentType.getString(it, "what")) },
                                ),
                            ),
                        )
                        .then(
                            Commands.literal("reason").then(
                                Commands.argument("kind", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(FortuneReasons.KINDS, b) }
                                    .executes { reason(it, null) }
                                    .then(Commands.argument("facts", StringArgumentType.greedyString()).executes { reason(it, StringArgumentType.getString(it, "facts")) }),
                            ),
                        ),
                ),
        )
    }

    // ---- Memory ----

    private fun memory(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        val day = Memory.day(level)
        say(source, header("What ${who(p, r)} remembers of you"))
        if (!Memory.enabled()) say(source, grey("Memory is switched off (setting: Townsfolk Remember You): everyone's a stranger, and nothing is dropped."))
        if (r.id < 0) say(source, grey("Someone with no record (a visitor): what passes with them is kept for today only, and they know what most of the town knows."))
        val familiarity = TownTalk.familiarity(level, r, player)
        val tier = TownTalk.standing(level, r.capital, player.uuid)
        val a = TownTalk.kept(level, r, player.uuid)
        val about = about(level, r, player.uuid)
        val seen = a?.let { Memory.deeds(it, day) }.orEmpty()
        val deeds = Memory.deedScore(seen, about.map { it.first.rumour })
        val opinion = Memory.opinion(r, a, day, deeds, tier)
        say(source, line().append(label("Familiarity: ")).append(plain(familiarity.name.lowercase())).append(label("   Opinion: ")).append(plain("$opinion")))
        val parts = ArrayList<String>()
        parts.add("leaning %+d".format(Memory.leaning(r)))
        parts.add("standing %+d (%s)".format(Memory.standing(tier), tier?.name?.lowercase() ?: "unknown"))
        if (a != null) {
            parts.add("days %+d".format(3 * minOf(a.days, 5)))
            parts.add("trades %+d".format(minOf(a.trades, 10)))
            parts.add("favour %+.1f".format(10 * Memory.faded(a.favour, day, Memory.FAVOUR_HALF)))
            parts.add("slights %+.1f".format(-10 * Memory.slights(a, day)))
            val set = Memory.faded(a.set, day, Memory.SET_HALF)
            if (abs(set) > 0.05) parts.add("set %+.1f".format(set))
        }
        parts.add("deeds %+d".format(5 * deeds))
        say(source, grey("  " + parts.joinToString(", ")))
        if (a == null) {
            say(source, grey(if (about.isEmpty()) "They've never met you, nor seen you do anything." else "They've never met you, but they've heard of you."))
        } else {
            val met = if (a.met >= 0) "day ${a.met} (${day - a.met} days ago)" else "never: they've only seen you"
            say(source, line().append(label("Met: ")).append(plain(met)).append(label("   Name: ")).append(plain(if (TownTalk.name(level, r, player.uuid)) "known" else "not known")))
            if (a.seen >= 0) say(source, line().append(label("Last talked or dealt: ")).append(plain("day ${a.seen}, on ${a.days} days in all")))
            if (a.trades > 0) say(source, line().append(label("Trades: ")).append(plain("${a.trades}, %.0f Marks spent (as it weighs now)".format(Memory.faded(a.spent, day, Memory.SPENT_HALF)))))
            val worst = Memory.worst(a, day)
            say(
                source,
                line().append(label("Favour: ")).append(plain("%.2f".format(Memory.faded(a.favour, day, Memory.FAVOUR_HALF))))
                    .append(label("   Slights: ")).append(plain("%.2f".format(Memory.slights(a, day)) + (worst?.let { " (worst: ${it.name.lowercase()})" } ?: ""))),
            )
            if (seen.isNotEmpty()) say(source, line().append(label("Saw you: ")).append(plain(seen.joinToString(", ") { "${it.kind} on day ${it.day} (${tone(it.tone)})" })))
        }
        if (about.isNotEmpty()) {
            say(source, label("Heard of you:"))
            for ((k, h) in about) say(source, line().append(plain("  #${k.rumour.id} ${k.rumour.kind}")).append(grey(" version ${h.warp}, " + whence(level, h))))
        }
        val felt = TownTalk.felt(level, r)
        if (felt.isNotEmpty()) {
            val text = felt.joinToString(", ") { f ->
                f.experience.name.lowercase() + (if (f.experience == Experience.LABOURED) " on works #${f.about}" else "") + " (day ${f.day})"
            }
            say(source, line().append(label("Lately: ")).append(plain(text)))
        }
        if (a != null && a.thanked.isNotEmpty()) {
            val text = a.thanked.joinToString(", ") { t -> t.favour.name.lowercase() + if (t.favour == Favour.FUNDED) " (works #${t.key})" else " (day ${t.key})" }
            say(source, line().append(label("Thanked you for: ")).append(plain(text)))
        }
        return 1
    }

    private fun laboured(context: CommandContext<CommandSourceStack>, works: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        if (!Memory.enabled()) return fail(source, "Memory is switched off (setting: Townsfolk Remember You).")
        if (r.id < 0) return fail(source, "${who(p, r)} has no record: they keep nothing past the day.")
        val w = Works.get(level, works) ?: return fail(source, "There's no works #$works (`/guildmark works` lists them).")
        TownTalk.laboured(level, r, w.id)
        say(source, plain("${who(p, r)} has had a building day on works #${w.id} (${w.kind.id}, ${w.state.name.lowercase()}) today: their own line about it while it's going up, and for a month after their last day."))
        return 1
    }

    private fun fed(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        if (!Memory.enabled()) return fail(source, "Memory is switched off (setting: Townsfolk Remember You).")
        TownTalk.fed(level, r, player.uuid)
        say(source, plain("${who(p, r)} ate at your alms table today: a small favour, counted once a day and only while their favour for you (the Opinion line's) is under %+.0f.".format(10 * Memory.DAILY_CAP)))
        return 1
    }

    private fun set(context: CommandContext<CommandSourceStack>, target: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        if (!Memory.enabled()) return fail(source, "Memory is switched off (setting: Townsfolk Remember You).")
        if (r.id < 0) return fail(source, "${who(p, r)} has no record: they keep nothing past the day.")
        val data = TownTalkData.get(level)
        val entry = data[r.capital] ?: data.open(r.capital, Spread.hourOf(level), Memory.day(level).toLong())
        val day = Memory.day(level)
        val a = Memory.acq(entry.mind(r.id, true)!!, player.uuid, day, true)!!
        val now = TownTalk.knows(level, r, player.uuid)?.opinion ?: 0
        Memory.set(a, day, now, target)
        data.changed()
        say(source, plain("${who(p, r)} now thinks $target of you (it fades back over ten days): ${TownTalk.familiarity(level, r, player).name.lowercase()}."))
        return 1
    }

    private fun meet(context: CommandContext<CommandSourceStack>, named: Boolean): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        if (!Memory.enabled()) return fail(source, "Memory is switched off (setting: Townsfolk Remember You).")
        if (named) TownTalk.named(level, r, player) else TownTalk.met(level, r, player)
        say(source, plain(if (named) "You've told ${who(p, r)} your name." else "You and ${who(p, r)} have talked today."))
        return 1
    }

    private fun forget(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        val data = TownTalkData.get(level)
        val entry = data[r.capital]
        val mind = entry?.minds?.get(r.id)
        if (entry == null || mind == null) return fail(source, "${who(p, r)} has nothing of you to forget.")
        mind.known.remove(player.uuid)
        mind.heard.keys.removeIf { entry.rumours[it]?.rumour?.about == player.uuid }
        data.changed()
        say(source, plain("${who(p, r)} has forgotten you, and everything they'd heard of you."))
        return 1
    }

    private fun town(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val capital = capital(source, player) ?: return 0
        val people = Residents.get(level).of(capital).filter { it.alive }
        val entry = TownTalkData.get(level)[capital]
        var met = 0
        var named = 0
        val counts = HashMap<Familiarity, Int>()
        for (r in people) {
            val a = entry?.minds?.get(r.id)?.known?.get(player.uuid)
            if (a != null && a.met >= 0) met++
            if (TownTalk.name(level, r, player.uuid)) named++
            counts.merge(TownTalk.familiarity(level, r, player), 1, Int::plus)
        }
        say(source, header("How ${name(capital)} knows you"))
        say(source, line().append(label("People: ")).append(plain("${people.size}")).append(label("   Met you: ")).append(plain("$met")).append(label("   Know your name: ")).append(plain("$named")))
        say(source, line().append(label("By familiarity: ")).append(plain(Familiarity.entries.filter { (counts[it] ?: 0) > 0 }.joinToString(", ") { "${it.name.lowercase()} ${counts[it]}" })))
        val friends = counts[Familiarity.FRIEND] ?: 0
        val wary = (counts[Familiarity.WARY] ?: 0) + (counts[Familiarity.HOSTILE] ?: 0)
        say(source, line().append(label("Friends: ")).append(plain("$friends")).append(label("   Wary or worse: ")).append(plain("$wary")))
        return 1
    }

    /** The rumours about [player] that [r] knows, as [TownTalk.known] gives them. */
    private fun about(level: ServerLevel, r: Resident, player: UUID): List<Pair<Known, Heard>> =
        TownTalk.known(level, r).filter { it.rumour.about == player }.map { it to it.heard }

    // ---- Rumours ----

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val capital = capital(source, player) ?: return 0
        val entry = TownTalkData.get(level)[capital] ?: return fail(source, "Nothing has been said in ${name(capital)} yet.")
        val hour = Spread.hourOf(level)
        val living = Residents.get(level).of(capital).count { it.alive }
        say(source, header("Rumours of ${name(capital)}: ${entry.rumours.size} going (hour $hour, ${entry.arrivals.size} on their way in)"))
        if (!Rumours.enabled()) say(source, grey("Rumours are switched off (setting: Rumours Get Round): nothing goes from mouth to mouth."))
        val talk = TownCry.pick(entry, Spread.view(capital), hour, keep = false)
        for (s in entry.rumours.values.sortedByDescending { Rumours.heat(it, hour) }) {
            val r = s.rumour
            val versions = IntArray(Rumours.TALL + 1)
            var knowers = 0
            for (m in entry.minds.values) m.heard[r.id]?.let {
                knowers++
                versions[it.warp]++
            }
            val heat = Rumours.heat(s, hour)
            val text = line()
                .append(clickable("#${r.id}", "/guildmark rumour show ${r.id}"))
                .append(plain(" ${r.kind}"))
                .append(grey(about(level, r)))
                .append(plain("  heat %.2f".format(heat)).withStyle(if (heat < Rumours.OLD) ChatFormatting.DARK_GRAY else ChatFormatting.WHITE))
                .append(grey(" ${r.fade.name.lowercase()}${if (r.scope == Scope.REGION) ", region" else ""}, ${by(s)}${if (s.cried) ", cried" else ""}${if (s === talk) ", talk of the town" else ""}"))
                .append(plain("  known by $knowers/$living"))
                .append(grey(" (" + versions.indices.filter { versions[it] > 0 }.joinToString(" ") { "v$it:${versions[it]}" } + ")"))
            say(source, text)
        }
        return 1
    }

    private fun show(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val capital = capital(source, player) ?: return 0
        val entry = TownTalkData.get(level)[capital] ?: return fail(source, "Nothing has been said in ${name(capital)} yet.")
        val s = entry.rumours[id] ?: return fail(source, "There's no rumour #$id in ${name(capital)}.")
        val r = s.rumour
        val hour = Spread.hourOf(level)
        say(source, header("Rumour #$id: ${r.kind}"))
        say(source, line().append(label("Says: ")).append(words(r, Rumours.TRUE)))
        say(source, line().append(label("About: ")).append(plain(about(level, r).ifEmpty { " nobody" }.trim())).append(label("   Facts: ")).append(plain(r.args.joinToString(", ").ifEmpty { "none" })))
        say(
            source,
            line().append(label("Born: ")).append(plain("hour ${r.born} (${hour - r.born} hours ago)"))
                .append(label("   Heat: ")).append(plain("%.2f now".format(Rumours.heat(s, hour)) + (if (s.full > 0f) ", %.2f once cried".format(Rumours.cool(s.full, r.fade, s.at, hour)) else "")))
                .append(grey("  ${r.fade.name.lowercase()} (x${r.fade.daily} a day), ${r.scope.name.lowercase()}, ${tone(r.tone)}, ${by(s)}${if (s.cried) ", cried" else ""}")),
        )
        r.origin?.let { say(source, line().append(label("Where: ")).append(at(it))) }
        val residents = Residents.get(level)
        val knowers = entry.minds.entries.mapNotNull { (rid, m) -> m.heard[id]?.let { rid to it } }.sortedBy { it.second.hour }
        val living = residents.of(capital).count { it.alive }
        say(source, label("Known by ${knowers.size} of $living:"))
        for ((rid, h) in knowers.take(SHOWN)) {
            val name = residents[rid]?.name ?: "#$rid"
            say(source, line().append(plain("  $name")).append(grey(" version ${h.warp}, " + whence(level, h) + (if (h.told > 0) ", told ${h.told}" else ""))))
        }
        if (knowers.size > SHOWN) say(source, grey("  and ${knowers.size - SHOWN} more"))
        val players = entry.players.entries.mapNotNull { (uuid, heard) -> heard[id]?.let { uuid to it } }
        if (players.isNotEmpty()) {
            say(source, line().append(label("Players who've heard it: ")).append(plain(players.joinToString(", ") { (uuid, v) -> "${playerName(level, uuid)} (version $v)" })))
        }
        return 1
    }

    private fun seed(context: CommandContext<CommandSourceStack>, about: ServerPlayer?, heat: Float): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val kind = StringArgumentType.getString(context, "kind")
        val (p, r) = resident(source, player) ?: return 0
        if (r.id < 0) return fail(source, "${who(p, r)} has no record: start it with someone who lives here.")
        val tone = when {
            kind.startsWith("deed.") || kind.startsWith("market.over.") || kind in GOOD -> 1
            kind.startsWith("slight.") || kind.startsWith("outlaws.") || kind.startsWith("market.event.") || kind in BAD -> -1
            else -> 0
        }
        val args = if (about != null) listOf(Witness.named(about.name.string)) else listOf(Witness.named(r.name))
        val fade = if (heat >= 0.8f) Fade.BIG else Fade.SMALL
        val (s, new) = TownTalk.begin(level, r.capital, kind, about?.uuid, if (about == null) r.id else -1, args, heat, fade, Scope.TOWN, tone, p.blockPosition(), Rumours.TOWN, listOf(r.id to Venue.SEEN))
        val what = if (new) "Started rumour #${s.rumour.id}" else "Heated rumour #${s.rumour.id} (the same thing was going round already)"
        say(source, line().append(plain("$what: $kind, heat %.2f, known by ${who(p, r)}. ".format(Rumours.heat(s, Spread.hourOf(level))))).append(clickable("[spread 24 hours]", "/guildmark rumour spread 24")))
        return 1
    }

    private fun spread(context: CommandContext<CommandSourceStack>, hours: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val capital = capital(source, player) ?: return 0
        val entry = TownTalkData.get(level)[capital] ?: return fail(source, "Nothing has been said in ${name(capital)} yet.")
        if (!Rumours.enabled()) say(source, grey("Rumours are switched off (setting: Rumours Get Round); spreading anyway, as asked."))
        fun knowers(id: Int): Int = entry.minds.values.count { id in it.heard }
        // How many know each rumour now, then after every few hours (the clock time each count is at)
        val ids = entry.rumours.keys.toList()
        val curve = ids.associateWith { arrayListOf(knowers(it)) }
        val hour = Spread.hourOf(level)
        val marks = ArrayList<String>()
        val tally = Spread.Tally()
        var done = 0
        while (done < hours) {
            val n = minOf(BLOCK, hours - done)
            Spread.ahead(level, entry, n, done + 1, tally)
            done += n
            marks.add("%02d:00".format(Math.floorMod(hour + done + 1 + 6, 24L)))
            for (id in ids) curve[id]?.add(knowers(id))
        }
        TownTalkData.get(level).changed()
        val living = Residents.get(level).of(capital).count { it.alive }
        say(source, header("The next $hours hours' talk in ${name(capital)}, worked out now"))
        say(source, grey("How many of the $living know each: now, then at " + marks.joinToString(", ")))
        for (s in entry.rumours.values.sortedByDescending { Rumours.heat(it, hour + hours) }) {
            val id = s.rumour.id
            val counts = curve[id] ?: continue
            say(
                source,
                line().append(clickable("#$id", "/guildmark rumour show $id")).append(plain(" ${s.rumour.kind}  "))
                    .append(plain(counts.joinToString(" -> ")))
                    .append(grey("  heat %.2f now, %.2f by then".format(Rumours.heat(s, hour), Rumours.heat(s, hour + hours)))),
            )
        }
        val ms = tally.nanos / 1_000_000.0
        say(
            source,
            line().append(label("Took: ")).append(plain("%.1f ms for ${tally.hours} hours (%.2f ms an hour)".format(ms, ms / maxOf(1, tally.hours))))
                .append(label("   Tellings tried: ")).append(plain("${tally.tries} (%d an hour)".format(tally.tries / maxOf(1, tally.hours))))
                .append(label("   Heard: ")).append(plain("${tally.heard}")),
        )
        return 1
    }

    private fun tell(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        val known = TownTalk.known(level, r).firstOrNull() ?: return fail(source, "${who(p, r)} has no news to tell.")
        TownTalk.told(level, r, player, known.rumour.id)
        val lead = when (known.heard.venue) {
            Venue.SEEN -> "saw it with their own eyes"
            Venue.CRIER -> "had it from the crier"
            Venue.AFAR -> "had it from afar"
            else -> "heard it (${known.heard.venue.name.lowercase()})"
        }
        say(source, line().append(label("${who(p, r)} ($lead), version ${known.heard.warp}: ")).append(words(known.rumour, known.heard.warp)))
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>, id: Int?): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val capital = capital(source, player) ?: return 0
        val data = TownTalkData.get(level)
        val entry = data[capital] ?: return fail(source, "Nothing has been said in ${name(capital)} yet.")
        if (id != null) {
            if (!entry.rumours.containsKey(id)) return fail(source, "There's no rumour #$id in ${name(capital)}.")
            Rumours.forget(entry, id)
            say(source, plain("Rumour #$id is forgotten."))
        } else {
            val n = entry.rumours.size
            for (k in entry.rumours.keys.toList()) Rumours.forget(entry, k)
            entry.arrivals.clear()
            say(source, plain("All $n rumours of ${name(capital)} are forgotten."))
        }
        data.changed()
        return 1
    }

    // ---- The works, the times and the households (M6) ----

    /**
     * `rumour household`: the looked-at townsperson's household (everyone living in their home) [what]: gone in hard
     * times or come home (the word of it starts with the neighbours, as Witness.gone and back start it for Part D3's
     * comings), or newcomers ([why] they came) or come home, talking of it themselves for their first days.
     */
    private fun household(context: CommandContext<CommandSourceStack>, what: String, why: String?): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val (p, r) = resident(source, player) ?: return 0
        if (r.id < 0) return fail(source, "${who(p, r)} has no record: try someone who lives here.")
        if (what !in HOUSEHOLD) return fail(source, "Say what befalls them: " + HOUSEHOLD.joinToString(", "))
        if (why != null && (what != "new" || why !in WHY)) return fail(source, "Only newcomers say why they came: " + WHY.joinToString(", "))
        // The looked-at townsperson first, so the word names their family
        val members = listOf(r.id) + if (r.home >= 0) Residents.get(level).of(r.capital).filter { it.alive && it.home == r.home && it.id != r.id }.map { it.id } else listOf()
        val capital = r.capital.asLong()
        val h = Household(
            if (r.home >= 0) r.home else -r.id, capital, r.home, members, Conditions(Markets.hourOf(level)).day,
            when (what) {
                "gone" -> "hard"
                "new" -> why ?: "grow"
                else -> "return"
            },
        )
        FortuneTopics.trying = true
        val family = r.family.ifEmpty { who(p, r) }
        when (what) {
            "gone", "back" -> {
                if (what == "gone") Witness.gone(level, h) else Witness.back(level, h)
                val news = if (what == "gone") "have left in hard times" else "are home again"
                say(
                    source,
                    line().append(plain("The $family family (${members.size}) $news: their neighbours know first, and the town talks of it while the word is warm. "))
                        .append(clickable("[rumours]", "/guildmark rumour list")).append(plain(" ")).append(clickable("[spread 24 hours]", "/guildmark rumour spread 24")),
                )
            }
            else -> {
                FortuneTopics.tried.removeIf { it.capital == capital && it.members.any { m -> m in members } }
                FortuneTopics.tried.add(h)
                val talk = if (what == "new") "newcomers (${h.reason})" else "home again"
                say(source, plain("The $family family (${members.size}) talk as $talk for their first days, from today (until the server stops)."))
            }
        }
        if (!Comings.enabled()) say(source, grey("The comings are off: the households' talk is heard anyway, for this test, until the server stops."))
        return 1
    }

    /** `rumour eased`: standing works [works] eased [what] today, as its effect would (heard by Fortunes and WorksTopics). */
    private fun eased(context: CommandContext<CommandSourceStack>, works: Int, what: String): Int {
        val source = context.source
        val level = source.level
        if (!Works.enabled()) return fail(source, "The works are off (Works.READY, townWorks): nothing eases anything.")
        if (what !in EASES) return fail(source, "Say what it eased: " + EASES.joinToString(", "))
        val w = Works.get(level, works) ?: return fail(source, "There's no works #$works (`/guildmark works` lists them).")
        if (w.state != WorksState.DONE || w.kind == WorksKind.STALL) return fail(source, "Works #$works isn't one of the town's standing: only those raised ease anything.")
        // Each eases only what it does in the market and on the road (Works.day, WorksFreight)
        val fits = when (what) {
            Works.MILL -> w.kind == WorksKind.GRANARY || w.kind == WorksKind.GREAT_GRANARY
            Works.BREAD -> w.kind == WorksKind.OVEN
            else -> w.kind == WorksKind.TOWER
        }
        if (!fits) return fail(source, "A ${w.kind.id} doesn't ease the $what: a granary keeps the mill turning, the second oven the bread coming, a watchtower sees a band off.")
        Works.eased(level, w.capital, w.kind, w.road, what)
        say(source, plain("Works #${w.id} (${w.kind.id}) eased the $what today: talked of for three days, and noted in the fortunes at the next close."))
        return 1
    }

    /**
     * `rumour reason`: a fortune reason of the kind given, noted in the capital as if it had happened (Fortunes.note,
     * added up by its kind's rule), with facts from the town: its roads, its dead, its works, a festival, the player's
     * own name; or the [facts] given, split by commas, in the order the reason keeps them (see FortuneReasons).
     */
    private fun reason(context: CommandContext<CommandSourceStack>, facts: String?): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val kind = StringArgumentType.getString(context, "kind")
        if (!Fortunes.enabled()) return fail(source, "The fortunes are off (Fortunes.READY, townFortunes, the markets): nothing is noted.")
        if (kind !in FortuneReasons.KINDS) return fail(source, "No reason called $kind: " + FortuneReasons.KINDS.joinToString(", "))
        val capital = capital(source, player) ?: return 0
        val (points, sample) = sample(level, capital, kind, player)
        val args = facts?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: sample
        val about = player.uuid.takeIf { kind == FortuneReasons.SUPPLIED || kind == FortuneReasons.ESCORTED }
        Fortunes.note(level, capital.asLong(), kind, points, REASON_DAYS, args, about)
        say(
            source,
            line().append(plain("Noted $kind %+d for $REASON_DAYS days in ${name(capital)}, facts [${args.joinToString(", ")}]. It counts from the next close, or now: ".format(points)))
                .append(clickable("[fortune step]", "/guildmark fortune step")),
        )
        return 1
    }

    /** A reason of [kind]'s points, and facts for it as they'd be in [capital] now. */
    private fun sample(level: ServerLevel, capital: BlockPos, kind: String, player: ServerPlayer): Pair<Int, List<String>> {
        val at = capital.asLong()
        val side = FortuneReasons.side(at, 0)
        val me = player.name.string
        val people = Residents.get(level).of(capital)
        val works = Works.all(level, at)
        val standing = works.filter { it.state == WorksState.DONE && it.kind != WorksKind.STALL }.maxByOrNull { it.done }
        val family = people.firstOrNull { it.alive && it.family.isNotEmpty() }?.family ?: "Thatcher"
        return when (kind) {
            FortuneReasons.HUNGRY -> -2 to listOf("6", "5")
            FortuneReasons.FED -> 1 to listOf<String>()
            FortuneReasons.DEAR -> -2 to listOf("160", "bread")
            FortuneReasons.CHEAP -> 1 to listOf("82", "bread")
            FortuneReasons.WANTING -> -2 to listOf("candle", "tools")
            FortuneReasons.IDLE -> -2 to listOf("mill", "ovens")
            FortuneReasons.ROAD -> -2 to listOf("perilous", side)
            FortuneReasons.WAYLAID -> -1 to listOf("ford", side)
            FortuneReasons.RELIEF -> 2 to listOf(side)
            FortuneReasons.ESCORTED, FortuneReasons.SUPPLIED -> 1 to listOf(me)
            FortuneReasons.PLENTY -> 1 to listOf("bumper:wheat", "good_catch")
            FortuneReasons.TROUBLE -> -2 to listOf("blight:wheat", "mill_broken", "oven_cracked", "murrain", "poor_catch")
            FortuneReasons.FEVER -> -2 to listOf<String>()
            FortuneReasons.MOURNING -> -1 to listOfNotNull(people.filter { !it.alive && it.died >= 0 }.maxByOrNull { it.died }?.name ?: people.firstOrNull()?.name)
            FortuneReasons.CRIME -> -1 to listOf("theft")
            FortuneReasons.FESTIVAL -> 3 to listOf(FestivalKind.entries.first().id, "fed")
            FortuneReasons.BUILDING -> 1 to works.filter { it.state == WorksState.BUILDING && it.kind != WorksKind.STALL }.map { it.kind.id }.distinct().ifEmpty { listOf("granary") }
            FortuneReasons.RAISED -> 2 to listOf(standing?.kind?.id ?: "granary", me)
            FortuneReasons.EASED -> 1 to when (val k = standing?.kind) {
                null -> listOf("granary", Works.MILL, me, "any")
                WorksKind.OVEN -> listOf("oven", Works.BREAD, me, "any")
                WorksKind.TOWER -> listOf("tower", Works.BAND, me, FortuneReasons.side(at, standing?.road ?: -1))
                else -> listOf(k.id, Works.MILL, me, "any")
            }
            FortuneReasons.NEWCOMERS -> 1 to listOf("grow", family)
            FortuneReasons.LEAVING -> -1 to listOf("hard", family)
            FortuneReasons.HAULED -> 1 to listOf(side)
            FortuneReasons.TALE -> 1 to listOf("note.paid", family, me)
            FortuneReasons.HELD -> 2 to listOf<String>()
            FortuneReasons.LOST -> -3 to listOf<String>()
            else -> -1 to listOf<String>()
        }
    }

    // ---- Finding them ----

    /** The townsperson [player] is looking at (or the nearest close by), and their record (a visitor's day-long one if they have none). */
    private fun resident(source: CommandSourceStack, player: ServerPlayer): Pair<Townsperson, Resident>? {
        val p = CapitalCommand.lookedAt(player)
        if (p == null) {
            fail(source, "Look at a townsperson first.")
            return null
        }
        val r = Residents.get(source.level).of(p) ?: p.life?.resident
        if (r == null) {
            fail(source, "${p.name.string} has no record: a passer-by not yet taken in.")
            return null
        }
        return p to r
    }

    /** The capital of the townsperson looked at, else the one [player] stands in. */
    private fun capital(source: CommandSourceStack, player: ServerPlayer): BlockPos? {
        CapitalCommand.lookedAt(player)?.let { p -> (Residents.get(source.level).of(p) ?: p.life?.resident)?.let { return it.capital } }
        Furnish.capitalAt(source.level, player.blockPosition())?.let { return it.center }
        fail(source, "You're not in a capital, nor looking at anyone who lives in one.")
        return null
    }

    // ---- Words ----

    private fun who(p: Townsperson, r: Resident): String = if (r.id >= 0) r.name else p.name.string

    private fun name(capital: BlockPos): String = Spread.view(capital).name ?: "the capital at ${capital.x} ${capital.z}"

    private fun playerName(level: ServerLevel, uuid: UUID): String = level.server.playerList.getPlayer(uuid)?.name?.string ?: uuid.toString().take(8)

    /** Whom [r] is about, for a list: the player and the resident, if any. */
    private fun about(level: ServerLevel, r: Rumour): String {
        val parts = ArrayList<String>()
        r.about?.let { parts.add(playerName(level, it)) }
        if (r.resident >= 0) parts.add(Residents.get(level)[r.resident]?.name ?: "#${r.resident}")
        return if (parts.isEmpty()) "" else " about " + parts.joinToString(" and ")
    }

    /** What [r] says as version [warp] tells it, in words if it has them (see [RumourWords]), else its kind and facts. */
    private fun words(r: Rumour, warp: Int): MutableComponent {
        val detail = RumourWords.detail(r, warp)?.let { Speech.detail("~$it") }
        if (detail != null) return detail.withStyle(ChatFormatting.WHITE)
        val facts = r.args.map { arg ->
            when {
                arg.startsWith("=") -> Component.literal(arg.substring(1))
                arg.isEmpty() || arg.toIntOrNull() != null -> Component.literal(arg)
                else -> Component.translatable(arg)
            }
        }
        val out = Component.literal(r.kind).withStyle(ChatFormatting.WHITE)
        if (facts.isNotEmpty()) {
            out.append(Component.literal(" ["))
            facts.forEachIndexed { i, c ->
                if (i > 0) out.append(Component.literal(", "))
                out.append(c)
            }
            out.append(Component.literal("]"))
        }
        return out
    }

    /** How [h] was come by: from whom, where, and when. */
    private fun whence(level: ServerLevel, h: Heard): String {
        val from = when (h.from) {
            Rumours.SAW -> "first hand"
            Rumours.CRIER -> "from the crier"
            Rumours.FAR -> "from afar"
            else -> "from " + (Residents.get(level)[h.from]?.name ?: "#${h.from}")
        }
        val ago = Spread.hourOf(level) - h.hour
        return "$from, ${h.venue.name.lowercase()}, ${if (ago <= 0) "this hour" else "$ago hours ago"}"
    }

    private fun by(s: Story): String = when (s.by) {
        Rumours.GUILD -> "guild news"
        Rumours.MARKET -> "market news"
        Rumours.AFAR -> "from afar"
        else -> "town"
    }

    private fun tone(tone: Int): String = when {
        tone > 0 -> "good"
        tone < 0 -> "bad"
        else -> "neither"
    }

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    /** [text] to click to run [command]. */
    private fun clickable(text: String, command: String): MutableComponent = Component.literal(text).withStyle {
        it.withColor(ChatFormatting.AQUA)
            .withClickEvent(ClickEvent.RunCommand(command))
            .withHoverEvent(HoverEvent.ShowText(Component.literal(command)))
    }

    /** A position to click to go to. */
    private fun at(pos: BlockPos): MutableComponent = clickable("[${pos.x} ${pos.y} ${pos.z}]", "/tp @s ${pos.x} ${pos.y} ${pos.z}")

    private fun say(source: CommandSourceStack, c: Component) = source.sendSuccess({ c }, false)

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
