package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Memorials
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * The Empty Chair's testing commands (living-world/stories/design.md 12.4), game masters only. Each step command does what the talk does,
 * through the same code, and takes the tale up for you first if you have not:
 *
 *  - `/guildmark tale chair`: the chair of the capital you stand in, from the record: the dead (and when they died), the mourner, who says
 *    the words, each friend with their word (said or not, which of the three) and their tie in grey, the candles, the place, the laying's day,
 *    the ending it would have now and the stone. It reads; it changes nothing.
 *  - `/guildmark tale chair word <friend1|friend2|friend3|all>`: you hear that friend's word (or all three).
 *  - `/guildmark tale chair candles`: you put as many candles from your pack as are still wanted into the mourner's hands.
 *  - `/guildmark tale chair place <temple|well|door>`: you choose the place with the mourner.
 *  - `/guildmark tale chair lay`: the laying is today and may be played at any hour for two game hours: the tale goes on to it if it was not
 *    there, the cast gather at the stone, and the laying plays once the mourner is there and you are near; seen through, it ends the tale as
 *    the dusk's would. Not seen through in those two hours, the tale ends with what was done (the stone, posy and candles set at once).
 *  - `/guildmark tale scene chair_count` and `chair_laying`: the doorstep and an A's laying, played now before you with whoever is free near
 *    you (the laying at a stand-in stone two blocks ahead: no block is set, the candles are only their flames), whatever the hour or the
 *    switches. No tale is needed or touched.
 */
internal object ChairCommands {
    private val WORDS = listOf("friend1", "friend2", "friend3", "all")
    private val PLACES = mapOf("temple" to Chair.TEMPLE, "well" to Chair.WELL, "door" to Chair.DOOR)

    fun register() {
        TaleSceneCommand.add("chair_count", ::count)
        TaleSceneCommand.add("chair_laying", ::laying)
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("chair").executes { show(it) }
                    .then(
                        Commands.literal("word").then(
                            Commands.argument("friend", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(WORDS, b) }
                                .executes { word(it, StringArgumentType.getString(it, "friend")) },
                        ),
                    )
                    .then(Commands.literal("candles").executes { candles(it) })
                    .then(
                        Commands.literal("place").then(
                            Commands.argument("place", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(PLACES.keys, b) }
                                .executes { place(it, StringArgumentType.getString(it, "place")) },
                        ),
                    )
                    .then(Commands.literal("lay").executes { lay(it) }),
            )
        }
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    private fun where(pos: BlockPos?): String = pos?.toShortString() ?: "nowhere known"

    /** The chair tale of the capital the command's player stands in (the open one, else the last), with its context. */
    private fun found(context: CommandContext<CommandSourceStack>): Pair<Tale, Ctx>? {
        val source = context.source
        val here = TaleCommand.here(context) ?: return null
        val level = source.level
        TaleEngine.use(level)
        val tales = TaleData.get(level).all().filter { it.template == Chair.id && it.capital == here.key }.sortedBy { it.id }
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) {
            TaleCommand.say(source, TaleCommand.plain("No Empty Chair is on record here ('/kill' a grown townsperson who is no guard, then 'tale day'; or 'tale start chair')."))
            return null
        }
        val c = TaleEngine.ctx(level, t.capital)
        if (c == null) {
            TaleCommand.say(source, TaleCommand.plain("The capital's places are still being worked out: try again in a second."))
            return null
        }
        return t to c
    }

    /** The open chair tale and the player, taken up by them if they have not; null (and said why) if it cannot be. */
    private fun open(context: CommandContext<CommandSourceStack>): Triple<Tale, Ctx, ServerPlayer>? {
        val source = context.source
        val player = source.playerOrException
        val (t, c) = found(context) ?: return null
        if (!t.open) {
            TaleCommand.say(source, TaleCommand.plain("The chair #${t.id} is over (ended ${t.ending})."))
            return null
        }
        val status = t.part(player.uuid)?.status
        if (status != Part.Status.IN && status != Part.Status.DONE && !Tales.join(c.level, t, player)) {
            TaleCommand.say(source, TaleCommand.plain("You could not take the chair up (Tales off, or two tales taken up already)."))
            return null
        }
        return Triple(t, c, player)
    }

    // ---- tale chair ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val (t, c) = found(context) ?: return 0
        val level = c.level
        TaleCommand.say(source, TaleCommand.header("The Empty Chair"))
        val state = if (t.open) "open, at step ${t.step} (D${c.day - t.d0}), the laying on day ${Chair.layDay(t)} (today is ${c.day})" else "ended ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
        val residents = Residents.get(level)
        val dead = Chair.deadOf(level, t)
        val ago = dead?.let { VillageBoards.day(level) - residents.diedOn(it) }
        TaleCommand.say(source, line("The dead", Chair.dead(t) + (if (dead != null) " (#${dead.id}, a ${dead.role.id}), died $ago days ago" else " (no longer on record)")))
        val mourner = c.resident(t, Chair.MOURNER)
        TaleCommand.say(source, line("Mourner", (t.cast(Chair.MOURNER)?.name ?: "none") + (mourner?.let { ", home #${it.home}, door ${where(Chair.door(t, Chair.MOURNER))}" } ?: "") + held(t, Chair.MOURNER)))
        val priest = c.resident(t, Chair.PRIEST)
        TaleCommand.say(source, line("The words", if (priest == null) "nobody (no priest and nobody free)" else priest.name + (if (priest.role == TownRole.PRIEST) ", the temple's priest" else ", who has lived here longest") + held(t, Chair.PRIEST)))
        for (slot in Chair.FRIENDS) {
            val cast = t.cast(slot)
            val said = if (Chair.heard(t, slot)) "said" else "not yet"
            val tie = Chair.tie(level, c.here.places, t, slot)?.let { Speech.said(it).string }.orEmpty()
            TaleCommand.say(source, line("  $slot", "${cast?.name ?: "none"}${if (cast?.gone?.isNotEmpty() == true) " (gone)" else ""}: ${Chair.family(t, slot).substringAfterLast('.')}, $said; door ${where(Chair.door(t, slot))}" + held(t, slot)))
            if (tie.isNotEmpty()) TaleCommand.say(source, TaleCommand.grey("      $tie"))
        }
        TaleCommand.say(source, line("Words", "${Chair.words(t.n[Chair.N_WORDS])} of 3"))
        TaleCommand.say(source, line("Candles", "${t.n[Chair.N_CANDLES]} of 3"))
        val place = PLACES.entries.firstOrNull { it.value == t.n[Chair.N_PLACE] }?.key
        TaleCommand.say(source, line("Place", if (place == null) "not chosen (the stone would go where a stone belongs: a temple's door first)" else "$place, at ${where(Chair.place(t))}"))
        val choices = PLACES.entries.filter { Chair.placeFor(c, t, it.value) != null }.joinToString(", ") { it.key }
        TaleCommand.say(source, TaleCommand.grey("      the places here: ${choices.ifEmpty { "none" }}"))
        if (t.open) TaleCommand.say(source, line("It would end", Chair.letter(t) + if (Chair.letter(t) == Ending.W) " (at D7's close)" else ""))
        val rec = Memorials.of(c, t).firstOrNull { it.names == listOf(Chair.dead(t)) }
            ?: TaleData.get(level).memorials.firstOrNull { it.capital == t.capital && it.template == Chair.id && it.names == listOf(Chair.dead(t)) }
        TaleCommand.say(source, line("The stone", if (rec == null) "not set yet" else "#${rec.id} at ${where(rec.pos)}, ${if (rec.placed) "standing" else "waiting"}"))
        if (ChairWatch.forced(t)) TaleCommand.say(source, TaleCommand.grey("A 'tale chair lay' has the laying at any hour now."))
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off: nobody is held and no laying is played."))
        return 1
    }

    private fun held(t: Tale, slot: String): String = if (t.cast(slot)?.held == true) " (held)" else ""

    // ---- The steps ----

    private fun word(context: CommandContext<CommandSourceStack>, which: String): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        val slots = if (which == "all") Chair.FRIENDS else listOf(which).filter { it in Chair.FRIENDS }
        if (slots.isEmpty()) return TaleCommand.say(source, TaleCommand.plain("No friend '$which': friend1, friend2, friend3 or all.")).let { 0 }
        var heard = 0
        for (slot in slots) if (Chair.word(c, t, player, slot)) heard++
        TaleCommand.say(source, TaleCommand.plain("You heard $heard word${if (heard == 1) "" else "s"}: ${Chair.words(t.n[Chair.N_WORDS])} of 3 now; the tale is at step ${t.step}" + if (t.open) "." else ", and ended ${t.ending}."))
        return if (heard > 0) 1 else 0
    }

    private fun candles(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        val before = t.n[Chair.N_CANDLES]
        if (Chair.give(c, t, player) == null) {
            return TaleCommand.say(source, TaleCommand.plain(if (before >= Chair.ALL) "The mourner has three candles already." else "No candle was taken: you have none in your pack (any colour), or the tale is not at the gathering yet.")).let { 0 }
        }
        TaleCommand.say(source, TaleCommand.plain("You gave ${t.n[Chair.N_CANDLES] - before} candle(s): ${t.n[Chair.N_CANDLES]} of 3; the tale is at step ${t.step}."))
        return 1
    }

    private fun place(context: CommandContext<CommandSourceStack>, which: String): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        val place = PLACES[which] ?: return TaleCommand.say(source, TaleCommand.plain("No place '$which': temple, well or door.")).let { 0 }
        val at = Chair.choose(c, t, player, place)
            ?: return TaleCommand.say(source, TaleCommand.plain(if (t.n[Chair.N_PLACE] != 0) "The place is chosen already." else "The town has no $which to set a stone by.")).let { 0 }
        TaleCommand.say(source, TaleCommand.plain("You chose the $which: the stone will stand near ${at.toShortString()}; the tale is at step ${t.step}."))
        return 1
    }

    private fun lay(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, _) = open(context) ?: return 0
        if (Chair.letter(t) == Ending.W) {
            return TaleCommand.say(source, TaleCommand.plain("Nothing is done yet: there is nothing to lay. It ends W at D7's close ('tale end W' now).")).let { 0 }
        }
        if (t.step < Chair.AT_LAY) c.go(t, Chair.AT_LAY)
        Chair.layToday(c, t)
        if (!ChairWatch.force(c, t)) return TaleCommand.say(source, TaleCommand.plain("The chair #${t.id} could not be brought to its laying.")).let { 0 }
        TaleCommand.say(
            source,
            TaleCommand.plain(
                "The laying is today, at any hour for two game hours: the cast gather at ${where(Chair.stoneAt(c, t))}; stay near and it plays once the mourner is there " +
                    "(it would end ${Chair.letter(t)})." + if (!Tales.scenes()) " Town tale scenes are off: it ends at once instead." else "",
            ),
        )
        if (!Tales.scenes()) Chair.finish(c, t)
        return 1
    }

    // ---- tale scene chair_* ----

    private fun name(p: Townsperson): String = TaleSceneCommand.name(p)

    private fun count(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0, station = true)
        val mourner = free.firstOrNull { it.seat != null } ?: free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val passer = Sets.passerBy(mourner)
        return TaleSceneCommand.begin(
            level, ChairScenes.count(mourner, passer, hours = null),
            "${name(mourner)} sits and grieves" + if (passer != null) "; ${name(passer)} comes to comfort them, and is told how quiet the house is." else " (nobody else free to comfort them).",
        )
    }

    private fun laying(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val mourner = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val look = player.lookAngle
        val dir = Direction.getApproximateNearest(look.x, 0.0, look.z)
        val stone = Sets.standable(level, player.blockPosition().relative(dir, 2)) ?: throw TaleSceneCommand.Unplayable("Nowhere to stand two blocks ahead of you for a stone.")
        val rest = free.drop(1)
        val priest = rest.firstOrNull { it.role == TownRole.PRIEST } ?: rest.firstOrNull()
        val friends = rest.filter { it !== priest }.take(3)
        // She kneels to a side of the stone, so not on you
        val stage = listOf(dir.clockWise, dir.counterClockWise, dir).firstNotNullOfOrNull { side ->
            ChairScenes.laying(level, mourner, stone, null, priest, friends, Chair.ALL, mourner.name.string, player, hours = null, front = side)
        }
        return TaleSceneCommand.begin(
            level, stage,
            "${friends.size} light a candle each at a stand-in stone ${stone.toShortString()} (no block is set); " +
                (if (priest != null) "${name(priest)} prays; " else "") + "${name(mourner)} kneels and lays a posy, and thanks you.",
        )
    }
}
