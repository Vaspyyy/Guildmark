package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.wardfire.Wardfires
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * The Brother Under the Hill's testing commands (living-world/stories/design.md 12.4), game masters only. Each step command does what the talk
 * (or the mine, the fire, the close) does, through the same code, and takes the tale up for you first if you have not. They find the tale
 * you are in wherever you stand (out at the mine too), else the open brother of the capital you stand in, else the only open one:
 *
 *  - `/guildmark tale brother`: the tale from the record: the shortage, the mine and its fire, the cast and where their bodies are, the way, the
 *    fee, where the one below is, the flags, what the watch has in hand, and the ending it would have now. It changes nothing.
 *  - `tale brother mine`: the mine, made now if the tale has none (as the first yes makes it), on your map.
 *  - `tale brother told`: the captain tells you where they were seen going (the step's MEET): the mine on your map.
 *  - `tale brother way <down|hire|fire>`: you choose the way with the smith.
 *  - `tale brother find`: you found them below (the deed of the way down), as coming within six blocks of them does.
 *  - `tale brother walk`: "Can you walk? Come with me.": they follow you (their body must be about: in the mine, within 64 blocks).
 *  - `tale brother up`: they are up out of the mine, led by you, as at the mouth.
 *  - `tale brother alone`: "Can you find your way?": out of the mine, they walk home by themselves (at the forge by first light).
 *  - `tale brother home`: they are at the forge door, as if walked there: the homecoming plays if the smith is there and you are near.
 *  - `tale brother pay [marks]`: you give the delvers Marks from your pack (the rest of the 40 if you say no number), as at the tavern.
 *  - `tale brother kindle`: you kindle the mine's fire (as at the fire, if its land is loaded; else the record has it kindled).
 *  - `tale brother clear`: the mine's boss falls, as at the end of the fight (the lair itself is not touched): they walk up, C freed.
 *  - `tale brother dawn`: the dawn (the delvers' Return, or the walk home at first light) is now, whatever the hour.
 *  - `/guildmark tale scene brother_forge`, `brother_calling`, `brother_rise`, `brother_return`, `brother_home` and `brother_laying`: the
 *    scenes, played now before you with whoever is free near you, whatever the hour or the switches. No tale is needed or touched.
 */
internal object BrotherCommands {
    private val WAYS = listOf("down", "hire", "fire")

    fun register() {
        TaleSceneCommand.add("brother_forge", ::forgeScene)
        TaleSceneCommand.add("brother_calling", ::callingScene)
        TaleSceneCommand.add("brother_rise", ::riseScene)
        TaleSceneCommand.add("brother_return", ::returnScene)
        TaleSceneCommand.add("brother_home", ::homeScene)
        TaleSceneCommand.add("brother_laying", ::layingScene)
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("brother").executes { show(it) }
                    .then(Commands.literal("mine").executes { mine(it) })
                    .then(Commands.literal("told").executes { told(it) })
                    .then(
                        Commands.literal("way").then(
                            Commands.argument("way", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(WAYS, b) }
                                .executes { way(it, StringArgumentType.getString(it, "way")) },
                        ),
                    )
                    .then(Commands.literal("find").executes { find(it) })
                    .then(Commands.literal("walk").executes { walk(it) })
                    .then(Commands.literal("up").executes { up(it) })
                    .then(Commands.literal("alone").executes { alone(it) })
                    .then(Commands.literal("home").executes { home(it) })
                    .then(
                        Commands.literal("pay").executes { pay(it, null) }
                            .then(Commands.argument("marks", IntegerArgumentType.integer(1, Brother.SUM)).executes { pay(it, IntegerArgumentType.getInteger(it, "marks")) }),
                    )
                    .then(Commands.literal("kindle").executes { kindle(it) })
                    .then(Commands.literal("clear").executes { clear(it) })
                    .then(Commands.literal("dawn").executes { dawn(it) }),
            )
        }
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    private fun where(pos: BlockPos?): String = pos?.toShortString() ?: "nowhere known"

    private fun say(source: CommandSourceStack, text: String): Int = TaleCommand.say(source, TaleCommand.plain(text))

    /**
     * The brother tale the command is about, with its context: the open one you have a part in, wherever you stand; else the capital's you
     * stand in (the open one, else the last); else the only open one in the world. Null (and said why) if none.
     */
    private fun found(context: CommandContext<CommandSourceStack>): Pair<Tale, Ctx>? {
        val source = context.source
        val level = source.level
        TaleEngine.use(level)
        val player = source.player
        val tales = TaleData.get(level).all().filter { it.template == Brother.id }.sortedBy { it.id }
        val t = (if (player != null) tales.lastOrNull { it.open && it.part(player.uuid) != null } else null)
            ?: (if (player != null && Capitals.covering(level, player.blockPosition()) != null) {
                val here = TaleCommand.here(context) ?: return null
                tales.filter { it.capital == here.key }.let { mine -> mine.lastOrNull { it.open } ?: mine.lastOrNull() }
            } else null)
            ?: tales.filter { it.open }.singleOrNull()
        if (t == null) {
            say(source, "No Brother Under the Hill is on record for you here ('tale start brother' in a capital: it makes up a shortage of tools if none stands).")
            return null
        }
        val c = TaleEngine.ctx(level, t.capital)
        if (c == null) {
            say(source, "The capital's places are still being worked out: try again in a second.")
            return null
        }
        return t to c
    }

    /** The open brother tale and the player, taken up by them if they have not; null (and said why) if it cannot be. */
    private fun open(context: CommandContext<CommandSourceStack>): Triple<Tale, Ctx, ServerPlayer>? {
        val source = context.source
        val player = source.playerOrException
        val (t, c) = found(context) ?: return null
        if (!t.open) {
            say(source, "The brother #${t.id} is over (ended ${t.ending}).")
            return null
        }
        if (!Brother.joined(t.part(player.uuid)) && !Tales.join(c.level, t, player)) {
            say(source, "You could not take the tale up (Tales off, or two tales taken up already).")
            return null
        }
        if (!t.open) {
            say(source, "The brother #${t.id} ended ${t.ending} as you took it up.")
            return null
        }
        return Triple(t, c, player)
    }

    private fun after(source: CommandSourceStack, t: Tale) =
        say(source, "The tale is at step ${t.step}" + if (t.open) " (it would end ${Brother.outcome(t)} now)." else ", and ended ${t.ending}.")

    private fun whereWord(t: Tale): String = when (Brother.where(t)) {
        Brother.BELOW -> "below, in the mine"
        Brother.LED -> "being led up (by ${t.s[Brother.S_LEADER].ifEmpty { "someone" }})"
        Brother.OUT -> "out of the mine"
        Brother.HOME -> "home"
        else -> "lost"
    }

    // ---- tale brother ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val (t, c) = found(context) ?: return 0
        val level = c.level
        TaleCommand.say(source, TaleCommand.header("The Brother Under the Hill"))
        val state = if (t.open) "open, at step ${t.step} (D${c.day - t.d0}, the step's last day ${t.by}; today is ${c.day})" else "ended ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
        val short = if (t.why.firstOrNull()?.kind == "brother.start_coal") "coal" else "tools"
        TaleCommand.say(source, line("The shortage", short + if (Brother.flag(t, Brother.FORCED)) " (made up by a forced start: the fortunes were not touched)" else " (wanting in the town's fortunes)"))
        val lair = Brother.lair(level, t)
        val mine = when {
            Brother.flag(t, Brother.NOMINE) -> "none could be made"
            t.n[Brother.N_LAIR] == 0 -> "none yet (the first yes makes one)"
            lair == null -> "#${t.n[Brother.N_LAIR]}, no longer on record"
            else -> "#${lair.id} at ${lair.pos.toShortString()}, " + (if (lair.delve) "a dug mine" else "a surface ring") + ", rank ${lair.rank}" +
                (if (lair.cleared) ", cleared" else "") + (if (Brother.flag(t, Brother.MADE)) ", made by the tale" else ", standing before it") +
                "; the mark " + (BrotherMine.mark(level, lair, Brother.side(t), c.here.capital.center)?.toShortString() ?: "not known (its first hall is not built or not loaded)")
        }
        TaleCommand.say(source, line("The mine", mine))
        val fire = Brother.fireAt(level, t)
        val fireWord = when {
            fire == null -> "not found (its land is not loaded, or a ring's fire is not raised yet)"
            Brother.flag(t, Brother.LIT) -> "at ${fire.toShortString()}, kindled by ${t.s[Brother.S_KINDLER].ifEmpty { "someone" }}"
            BrotherMine.lit(level, fire) -> "at ${fire.toShortString()}, burning (lit before the tale: the fire is no way)"
            else -> "at ${fire.toShortString()}, cold"
        }
        TaleCommand.say(source, line("Its fire", fireWord))
        for (cast in t.cast) {
            val r = if (cast.resident >= 0) c.resident(t, cast.slot) else null
            val body = TaleBodies.body(level, t, cast.slot)
            val at = when {
                body == null -> "no body about"
                body.tale == t.id -> "the tale's at ${body.blockPosition().toShortString()} (${body.director?.javaClass?.simpleName ?: "free"})"
                else -> "about their day at ${body.blockPosition().toShortString()}"
            }
            TaleCommand.say(
                source,
                line(
                    "  ${cast.slot}",
                    "${cast.name.ifEmpty { "none" }}${if (cast.gone.isNotEmpty()) " (gone: ${cast.gone})" else ""}" +
                        (if (cast.resident < 0) ", a figure" else r?.let { ", home #${it.home}${if (!it.alive) ", dead" else ""}" } ?: "") +
                        (if (cast.held) " (held)" else "") + "; $at",
                ),
            )
        }
        val way = when (Brother.way(t)) {
            Brother.DOWN -> "down for them (A)"
            Brother.HIRE -> "the delvers (B)"
            Brother.FIRE -> "the old fire (C)"
            else -> "not chosen"
        }
        TaleCommand.say(source, line("The way", way))
        TaleCommand.say(
            source,
            line("The fee", "${Brother.sum(t)} Marks, ${t.n[Brother.N_PAID]} put in" + (if (Brother.flag(t, Brother.WHOLE)) " (whole: the party goes at the close; paid by ${t.s[Brother.S_PAYER]})" else "") +
                t.parts.filter { it.escrow > 0 }.joinToString("") { "; ${TaleEngine.playerName(level, it.player)} holds ${it.escrow} in escrow" }),
        )
        TaleCommand.say(source, line("They are", whereWord(t) + if (t.n[Brother.N_DAY] > 0) "; the day named: ${t.n[Brother.N_DAY]}" else ""))
        val flags = listOf(
            Brother.FOUND to "found", Brother.LIT to "the fire kindled", Brother.GONE to "the party went", Brother.ALONE to "walking home alone",
            Brother.FREED to "freed", Brother.NOMINE to "no mine", Brother.FORCED to "a forced start", Brother.MADE to "the mine made",
            Brother.LATE to "a leading ran on past D5", Brother.MAPPED to "on every map", Brother.SMITH_GONE to "the smith gone",
            Brother.WHOLE to "the fee whole", Brother.ROSE to "came up to the fire", Brother.LAID to "laid", Brother.WAITS to "told to wait",
            Brother.LAMPED to "the lantern hung",
        ).filter { Brother.flag(t, it.first) }.joinToString(", ") { it.second }
        TaleCommand.say(source, line("Flags", flags.ifEmpty { "none" }))
        TaleCommand.say(source, line("The watch", BrotherWatch.state(t)))
        if (t.open) TaleCommand.say(source, line("It would end", Brother.outcome(t) + if (Brother.outcome(t) == "W") " (at D5's close, with nothing in motion: they are lost below)" else ""))
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off: nobody is held, the mine is as any lair, and its fire as any fire."))
        else if (!Tales.scenes()) TaleCommand.say(source, TaleCommand.grey("Town tale scenes are off: nobody is held or walked; the record does each step (the delvers through the smith's talk)."))
        return 1
    }

    // ---- The steps ----

    private fun mine(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        val lair = Brother.makeMine(c, t) ?: return say(source, "No mine could be made (no spot within 120 to 220 blocks): the tale ended ${t.ending}.").let { 0 }
        Brother.note(c.level, player, lair)
        say(source, "The old mine #${lair.id} is at ${lair.pos.toShortString()} (" + (if (lair.delve) "a dug mine: they wait in its first hall" else "a surface ring: they wait by its altar") + "); it is on your map.")
        after(source, t)
        return 1
    }

    private fun told(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (!Brother.tellMine(c, t, player)) return say(source, "The captain has nothing to tell now (step ${t.step}).").let { 0 }
        say(source, "The captain told you: seen at dusk going toward the old workings; the mine is on your map at ${where(Brother.lair(c.level, t)?.pos)}.")
        after(source, t)
        return 1
    }

    private fun way(context: CommandContext<CommandSourceStack>, which: String): Int {
        val source = context.source
        val w = when (which) {
            "down" -> Brother.DOWN
            "hire" -> Brother.HIRE
            "fire" -> Brother.FIRE
            else -> return TaleCommand.fail(source, "No way '$which': down, hire or fire.")
        }
        val (t, c, player) = open(context) ?: return 0
        if (t.step == Brother.AT_MEET) Brother.tellMine(c, t, player)
        if (!t.open) return say(source, "The tale ended ${t.ending}.").let { 0 }
        if (!Brother.choose(c, t, player, w)) {
            return say(
                source,
                if (w == Brother.FIRE && Brother.fireLit(c.level, t)) "The mine's fire burns already: it is no way now." else "That way can't be chosen now (step ${t.step}; they are ${whereWord(t)}).",
            ).let { 0 }
        }
        say(
            source,
            when (w) {
                Brother.DOWN -> "You will go down for them: they wait in the mine at ${where(Brother.lair(c.level, t)?.pos)} (`tale brother find`, `walk`, `up`)."
                Brother.HIRE -> "You will hire the delvers: their leader waits at the tavern's door from 6:00 to 20:00 (`tale brother pay` pays now)."
                else -> "You will light the old fire by the mine: it is at ${where(Brother.fireAt(c.level, t))} (`tale brother kindle` kindles it now)."
            },
        )
        after(source, t)
        return 1
    }

    private fun find(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (Brother.where(t) != Brother.BELOW || Brother.flag(t, Brother.GONE) || Brother.flag(t, Brother.FREED) || t.step > Brother.AT_DEED) {
            return say(source, "They are not waiting below to be found (they are ${whereWord(t)}).").let { 0 }
        }
        val body = TaleBodies.body(c.level, t, Brother.BROTHER)?.takeIf { it.tale == t.id && it.distanceTo(player) < 16f }
        BrotherWatch.foundBy(c, t, player, body)
        say(source, "You found them below" + (if (body != null) "" else " (on the record: their body is not near you)") + ". `tale brother walk` (by them) or `tale brother up`.")
        after(source, t)
        return 1
    }

    private fun walk(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (!Tales.scenes()) return say(source, "Town tale scenes are off: nobody walks. `tale brother up` and `home` do it on the record.").let { 0 }
        if (!BrotherWatch.walk(c, t, player)) {
            val lair = Brother.lair(c.level, t)
            val mark = lair?.let { BrotherMine.mark(c.level, it, Brother.side(t), c.here.capital.center) }
            return say(source, "They can't walk with you now: their body must be about (within 64 blocks of their mark, ${where(mark ?: lair?.pos)}), and they ${whereWord(t)}.").let { 0 }
        }
        say(source, "They follow you at their own pace to the forge door at ${where(Brother.forgeOr(c, t))}; up out of the mouth is the deed.")
        after(source, t)
        return 1
    }

    private fun up(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (Brother.where(t) >= Brother.OUT || Brother.flag(t, Brother.FREED) || Brother.flag(t, Brother.GONE)) {
            return say(source, "They can't be led up now (they are ${whereWord(t)}).").let { 0 }
        }
        t.setS(Brother.S_LEADER, player.gameProfile.name)
        Brother.up(c, t, player.uuid)
        say(source, "They are up out of the mine, led by you: walk them home (`tale brother walk`), or `tale brother alone`.")
        after(source, t)
        return 1
    }

    private fun alone(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        BrotherWatch.cancelWalk(c.level, t)
        if (!Brother.alone(c, t, player)) return say(source, "They can't find their own way now (they are ${whereWord(t)}${if (Brother.flag(t, Brother.ALONE)) ", on their way already" else ""}).").let { 0 }
        say(source, "They walk home by themselves: at the forge door by first light (`tale brother dawn` has it now).")
        after(source, t)
        return 1
    }

    private fun home(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (Brother.where(t) == Brother.HOME) return say(source, "They are home already.").let { after(source, t); 0 }
        if (Brother.flag(t, Brother.FREED) || Brother.flag(t, Brother.GONE)) return say(source, "They are not yours to walk home (${if (Brother.flag(t, Brother.GONE)) "the delvers have them" else "they came up by themselves"}).").let { 0 }
        BrotherWatch.cancelWalk(c.level, t)
        if (Brother.where(t) < Brother.OUT) {
            t.setS(Brother.S_LEADER, player.gameProfile.name)
            Brother.up(c, t, player.uuid)
        }
        t.part(player.uuid)?.let { it.did = it.did or Part.WALKED }
        if (t.open) BrotherWatch.bringHome(c, t)
        if (t.open) Brother.arrived(c, t)
        say(source, "They are home at the forge door, on the record: the homecoming plays if the smith and they are both there and you are near, else the record ends it within two minutes.")
        after(source, t)
        return 1
    }

    private fun pay(context: CommandContext<CommandSourceStack>, marks: Int?): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (t.step !in Brother.AT_MEET..Brother.AT_DEED || Brother.flag(t, Brother.WHOLE) || Brother.flag(t, Brother.GONE) || Brother.flag(t, Brother.FREED) || Brother.where(t) != Brother.BELOW) {
            return say(source, "The delvers take no Marks now (step ${t.step}; the fee ${if (Brother.flag(t, Brother.WHOLE)) "is whole" else "stands at ${t.n[Brother.N_PAID]} of ${Brother.sum(t)}"}; they are ${whereWord(t)}).").let { 0 }
        }
        val owing = Brother.owing(t)
        val m = (marks ?: owing).coerceIn(1, owing)
        if (TownControl.marks(player) < m) return say(source, "You have fewer than $m Marks.").let { 0 }
        // As the delver's option puts it in: the reason first, the way (the deed's step, so the Marks are held there), the Marks in escrow, and the tale hears it
        t.addWhy(Why("brother.gave", listOf(player.gameProfile.name, m.toString())))
        Brother.deed(c, t, player, Brother.HIRE)
        val part = t.part(player.uuid)
        if (!t.open || t.step != Brother.AT_DEED || part == null) return say(source, "The fee can't be paid now (step ${t.step}).").let { 0 }
        TownControl.pay(player, m)
        part.escrow += m
        c.data.changed()
        Tales.tell(c.level, Event.Gave(t.id, player, m))
        say(
            source,
            "You gave the delvers $m Marks (${t.n[Brother.N_PAID]} of ${Brother.sum(t)})" +
                if (Brother.flag(t, Brother.WHOLE)) ": the fee is whole; the party goes down at the close (`tale day`), and brings them up the street at first light." else ".",
        )
        after(source, t)
        return 1
    }

    private fun kindle(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        val level = c.level
        val lair = Brother.lair(level, t) ?: return say(source, "The tale has no mine yet (`tale brother mine`).").let { 0 }
        if (Brother.flag(t, Brother.LIT)) return say(source, "The mine's fire is kindled already.").let { 0 }
        val fire = BrotherMine.fireOf(level, lair)
        if (fire != null) {
            val state = level.getBlockState(fire)
            if (Wardfires.isLit(state)) return say(source, "The mine's fire at ${fire.toShortString()} burns already (lit before the tale): it is no way.").let { 0 }
            Wardfires.kindle(level, fire, state, player)
        } else {
            Brother.kindled(c, t, lair.pos, player, found = false)
        }
        if (!Brother.flag(t, Brother.LIT)) return say(source, "The kindling was not the tale's (step ${t.step}; they are ${whereWord(t)}).").let { 0 }
        say(
            source,
            "You kindled the mine's fire" + (if (fire == null) " on the record (its land is not loaded)" else " at ${fire.toShortString()}") +
                ": with a player near, they make for it now; else the record has them up at the close (`tale day`), home at 7:00.",
        )
        after(source, t)
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (Brother.where(t) > Brother.LED || Brother.flag(t, Brother.FREED) || Brother.flag(t, Brother.GONE)) return say(source, "They are not below (they are ${whereWord(t)}).").let { 0 }
        BrotherWatch.cancelWalk(c.level, t)
        Brother.cleared(c, t, listOf(player.uuid))
        say(source, "The mine's boss fell (on the record: the lair is not touched): they walk up to the mouth, and the tale ends C as they get there (at once with nobody near).")
        after(source, t)
        return 1
    }

    private fun dawn(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, _) = open(context) ?: return 0
        if (!BrotherWatch.dawn(c, t)) return say(source, "No dawn is to come: nobody is on the way home (step ${t.step}; they are ${whereWord(t)}).").let { 0 }
        say(
            source,
            "The dawn is now: stand within 64 blocks of the forge door at ${where(Brother.forgeOr(c, t))} to see them come up the street" +
                (if (!Tales.scenes()) "; Town tale scenes are off, so the record ends it at once." else "; with nobody near, the record ends it."),
        )
        return 1
    }

    // ---- tale scene brother_* ----

    private fun name(p: Townsperson): String = TaleSceneCommand.name(p)

    private fun ahead(player: ServerPlayer, blocks: Double): Vec3 {
        val look = player.lookAngle
        val len = kotlin.math.sqrt(look.x * look.x + look.z * look.z).coerceAtLeast(0.01)
        return player.position().add(look.x / len * blocks, 0.0, look.z / len * blocks)
    }

    private fun free(level: ServerLevel, player: ServerPlayer, n: Int, what: String): List<Townsperson> {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        if (free.size < n) throw TaleSceneCommand.Unplayable(if (free.isEmpty()) "Nobody free within 24 blocks of you." else "Only ${free.size} free within 24 blocks of you: $what needs $n.")
        return free
    }

    private fun forgeScene(level: ServerLevel, player: ServerPlayer): String {
        val smith = free(level, player, 1, "it").first()
        val stage = BrotherScenes.forgeAt(smith, ahead(player, 16.0).add(0.0, 1.5, 0.0))
        return TaleSceneCommand.begin(level, stage, "${name(smith)} sets the work down, looks off the way you face and wrings their hands; whoever passes comes up with a word.")
    }

    private fun callingScene(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player, 1, "it")
        val smith = free.first()
        val at = Sets.standable(level, BlockPos.containing(ahead(player, 4.0))) ?: smith.blockPosition()
        val stage = BrotherScenes.callingAt(smith, "Jon", at, ahead(player, 24.0).add(0.0, 1.5, 0.0), free.getOrNull(1), free.getOrNull(2))
        return TaleSceneCommand.begin(level, stage, "${name(smith)} calls a name down the road the way you face; a neighbour comes and asks (if two are free), and a third points the way.")
    }

    private fun riseScene(level: ServerLevel, player: ServerPlayer): String {
        val b = free(level, player, 1, "it").first()
        val fire = Sets.standable(level, BlockPos.containing(ahead(player, 8.0))) ?: throw TaleSceneCommand.Unplayable("Nowhere to stand eight blocks ahead of you.")
        val stage = BrotherScenes.rise(b, fire, player)
        return TaleSceneCommand.begin(level, stage, "${name(b)} looks about, sees the glow eight blocks ahead of you, makes for it with a dark lamp held out, lights the lamp at the flame and turns to you.")
    }

    private fun returnScene(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player, 3, "the Return")
        val smith = free[0]
        val home = free.drop(1).maxByOrNull { it.distanceToSqr(smith) } ?: free[1]
        val rest = free.filter { it !== smith && it !== home }
        val away = Sets.standable(level, BlockPos.containing(ahead(player, -12.0))) ?: home.blockPosition()
        val stage = BrotherScenes.ret(smith, home, rest[0], rest.getOrNull(1), rest.getOrNull(2), player, away)
        return TaleSceneCommand.begin(level, stage, "${name(home)} comes up to ${name(smith)} between the delvers, torches lit; the first tells ${name(smith)}, who runs to them; the delvers salute and go.")
    }

    private fun homeScene(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player, 2, "the homecoming")
        val smith = free[0]
        val home = free.drop(1).maxByOrNull { it.distanceToSqr(smith) } ?: free[1]
        val watchers = free.filter { it !== smith && it !== home }.take(3)
        val stage = BrotherScenes.homecoming(smith, home, player, watchers)
        return TaleSceneCommand.begin(level, stage, "${name(smith)} looks out for ${name(home)}, who comes at a hobble; ${name(smith)} runs to them and they embrace; then ${name(smith)} thanks you.")
    }

    private fun layingScene(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player, 1, "it")
        val smith = free[0]
        val stone = Sets.standable(level, BlockPos.containing(ahead(player, 4.0))) ?: throw TaleSceneCommand.Unplayable("Nowhere four blocks ahead of you.")
        val stage = BrotherScenes.laying(smith, stone, free.getOrNull(1), free.getOrNull(2), "Jon", null)
        return TaleSceneCommand.begin(level, stage, "${name(smith)} kneels four blocks ahead of you and lays a posy; the others stand by; then ${name(smith)} says a word to the stone (nothing is set there).")
    }
}
