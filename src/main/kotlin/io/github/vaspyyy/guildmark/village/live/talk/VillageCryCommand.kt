package io.github.vaspyyy.guildmark.village.live.talk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownKind
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.days.BellKeeper
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster
import io.github.vaspyyy.guildmark.advance.Advance
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark village cry [now]` and `/guildmark village news [clear]` (the villages design, sections 8.2 and 15.2), run by operators
 * in a village, or beside one:
 *
 * - `cry`: the day's word at the village's noon, line by line as the bellman would cry it, whether it is due today and why (market
 *   day, news, or neither: no cry), who holds the bell, whether it has been cried today, and whether there is a market board to chalk;
 * - `cry now`: the bellman goes to the bell and cries it now, whatever the hour (nothing is kept as told, so noon cries it again);
 * - `news`: what the bellman has told and on which day, the projects finished lately and the candles laid, the newest first;
 * - `news clear`: all that is forgotten, so what is fresh is cried again.
 *
 * Each ends in plain words: what a number is and where it came from. Off with Village Bellman: nothing is cried or kept.
 */
object VillageCryCommand {
    /** How far (blocks) the nearest village is looked for when the sender is not in one. */
    private const val NEAREST = 128

    /** How many told things `news` lists. */
    private const val SHOWN = 12

    private var started = false

    /** At setup (Bellman.register): the commands and a line of `village info`. */
    fun init() {
        if (started) return
        started = true
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        VillageInfoCommand.section { level, rec -> lines(level, rec) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village")
                        .then(Commands.literal("cry").executes { show(it) }.then(Commands.literal("now").executes { now(it) }))
                        .then(Commands.literal("news").executes { news(it) }.then(Commands.literal("clear").executes { clear(it) })),
                ),
        )
    }

    // ---- The commands ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec, places) = target(source) ?: return 0
        val c = Conditions(Markets.hourOf(level))
        val word = Bellman.word(level, rec, places, c)
        say(source, header("The word at ${rec.name}, day ${c.day}"))
        val due = when {
            word.market && word.news -> "yes: it is market day, and there is news"
            word.market -> "yes: it is market day"
            word.news -> "yes: there is news"
            else -> "no: not market day and no news, so the bell is not rung at noon"
        }
        say(source, line().append(label("Due today: ")).append(plain(due)))
        val cried = VillageCry.criedOn(level, rec.key)
        say(source, line().append(label("Cried: ")).append(plain(if (cried == c.day) "today" else if (cried == PedlarRoster.NONE) "never yet" else "last on day $cried")))
        say(source, line().append(label("Bellman: ")).append(plain(holder(level, places, c.day))))
        val board = Bellman.boardOf(rec.key)
        say(source, line().append(label("Board: ")).append(plain(if (board != null) "the market board at ${at(board.pos)}, chalked after the cry" else "none seen: the bellman only cries")))
        word.cries.forEachIndexed { i, cry -> say(source, line().append(grey("${i + 1}. ")).append(cry.text.copy()).append(grey("  (${cry.line})"))) }
        if (word.told.isNotEmpty()) say(source, line().append(label("Told by it: ")).append(plain(word.told.joinToString(", "))))
        say(
            source,
            grey(
                "The word is built from the records at noon of market hour ${c.day * 24 + 6}: the village's own news of the last ${VillageCry.FRESH} days, " +
                    "word from outside, the roads, the market, the cart, the calendar, a price and the village's times, at most ${VillageCry.MOST} " +
                    "between the opening and the close. Its own news and word from outside are cried once each. 'now' has the bellman cry it now.",
            ),
        )
        return 1
    }

    private fun now(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec, places) = target(source) ?: return 0
        val day = Days.dayOf(level.overworldClockTime)
        val id = BellKeeper.holder(level, places.centre, day) ?: return fail(source, "Nobody holds the bell of ${rec.name} this week.")
        val r = Residents.get(level)[id] ?: return fail(source, "The bell's holder is not in the records.")
        val p = r.uuid?.let { level.getEntity(it) as? Townsperson }?.takeIf { it.isAlive }
            ?: return fail(source, "${r.name} holds the bell but is not about (not in a loaded chunk).")
        val life = Days.life(p)?.takeIf { it.places.kind == TownKind.VILLAGE } ?: return fail(source, "${r.name} has no village day just now.")
        val task = Bellman.round(p, life, now = true)
            ?: return fail(source, "${r.name} cannot cry: the bell is gone, or there is nowhere to stand by it.")
        p.routine.replace(task)
        say(source, grey("${r.name} goes to the bell to cry the word now (nothing is kept as told: it is cried again at noon)."))
        return 1
    }

    private fun news(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec, _) = target(source) ?: return 0
        say(source, header("What the bellman of ${rec.name} has told"))
        val told = VillageCry.toldAt(level, rec.key).entries.sortedByDescending { it.value }
        if (told.isEmpty()) say(source, grey("Nothing yet."))
        for (e in told.take(SHOWN)) say(source, line().append(label("Day ${e.value}: ")).append(plain(e.key)))
        if (told.size > SHOWN) say(source, grey("and ${told.size - SHOWN} more"))
        val built = VillageCry.builtAt(level, rec.key)
        if (built.isNotEmpty()) {
            say(source, line().append(label("Finished: ")).append(plain(built.reversed().joinToString(", ") { b -> "${Advance.entries.getOrNull(b.project)?.id ?: "?"} (day ${b.day})" })))
        }
        val candles = VillageCryData.get(level).peek(rec.key)?.candles.orEmpty()
        if (candles.isNotEmpty()) say(source, line().append(label("Candles: ")).append(plain(candles.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} (day ${it.value})" })))
        say(source, grey("Each is kept 14 days after it was told, by the day it happened ('dead.<resident>', 'siege.held.<day>', 'missed.<day>' and so on). 'clear' forgets them, so what is fresh is cried again."))
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec, _) = target(source) ?: return 0
        VillageCry.forget(level, rec.key)
        say(source, grey("${rec.name}'s bellman has forgotten what was told and that they cried today: what is fresh is cried again."))
        return 1
    }

    // ---- What is said ----

    /** The line this part adds to `village info`. */
    private fun lines(level: ServerLevel, rec: VillageRec): List<Component> {
        if (!VillageCry.enabled() || rec.state == Live.LOST) return listOf()
        val places = Villages.places(level, rec.key) ?: return listOf()
        val c = Conditions(Markets.hourOf(level))
        val word = Bellman.word(level, rec, places, c)
        val cried = VillageCry.criedOn(level, rec.key)
        val due = if (word.due) "cried at noon (${word.cries.size - 2} things)" else "no cry today"
        return listOf(line().append(label("Bellman: ")).append(plain("$due" + if (cried == c.day) ", cried" else "")).append(grey(" (village cry)")))
    }

    /** Who holds the bell, in words. */
    private fun holder(level: ServerLevel, places: VillagePlaces, day: Long): String {
        val r = BellKeeper.holder(level, places.centre, day)?.let { Residents.get(level)[it] } ?: return "nobody this week"
        val about = r.uuid?.let { level.getEntity(it) } != null
        return "${r.name} (#${r.id})" + if (about) "" else ", not about"
    }

    // ---- Which village ----

    /** The village the sender stands in, or the nearest bell within [NEAREST] blocks, with its places; null, having said so, if none. */
    private fun target(source: CommandSourceStack): Triple<ServerLevel, VillageRec, VillagePlaces>? {
        val level = source.level
        if (level.dimension() != Level.OVERWORLD) {
            fail(source, "Villages are counted in the overworld only.")
            return null
        }
        if (!VillageCry.enabled()) {
            fail(source, "Village bellmen are off just now (settings: Living Villages and Village Bellman are both needed).")
            return null
        }
        val here = BlockPos.containing(source.position)
        val rec = Villages.at(level, here) ?: Villages.near(level, here, NEAREST).firstOrNull()
        if (rec == null) {
            fail(source, "No village within $NEAREST blocks.")
            return null
        }
        val places = Villages.places(level, rec.key)
        if (places == null) {
            fail(source, "${rec.name}'s places are not counted in this session yet: `village census now` first.")
            return null
        }
        return Triple(level, rec, places)
    }

    // ---- Words ----

    private fun at(pos: BlockPos): String = "${pos.x} ${pos.y} ${pos.z}"
    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GOLD)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
