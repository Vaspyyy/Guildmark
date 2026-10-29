package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * The Bell Moot's testing commands (living-world/stories/design.md 12.4), game masters only:
 *
 *  - `/guildmark tale moot`: the moot of the capital you stand in, from the record: its works (kind, price, pledged, the town's gift it would
 *    give), the sides heard, whether the quarrel has played and the moot sits, the Marks pledged in the tale, and each reason the crier would
 *    offer now with what it would carry (A, B or C). It reads; it changes nothing.
 *  - `/guildmark tale moot sit`: the moot sits now, for two game hours, whatever the day and hour (the tale goes on to the speaking from the
 *    sides' accounts; a tale still at the crier's ask waits for its yes). The two o'clock breaking is held off meanwhile.
 *  - `/guildmark tale moot say <reason|vague>`: you speak at the moot with that reason (a kind from `tale moot`, `road_<side>` for a road), or
 *    the vague word: you take the tale up if you have not, it goes on to the speaking, sits, and your word ends it as the talk would.
 *  - `/guildmark tale scene moot_quarrel`, `moot_call`, `moot_given`, `moot_half`, `moot_deferred`, `moot_broke`: the scenes, played now
 *    before you with whoever is free near you (you stand for the board), whatever the hour or the switches. No tale is needed or touched.
 */
internal object MootCommands {
    fun register() {
        TaleSceneCommand.add("moot_quarrel", ::quarrel)
        TaleSceneCommand.add("moot_call", ::call)
        TaleSceneCommand.add("moot_given") { level, player -> verdict(level, player, Ending.A) }
        TaleSceneCommand.add("moot_half") { level, player -> verdict(level, player, Ending.B) }
        TaleSceneCommand.add("moot_deferred") { level, player -> verdict(level, player, Ending.C) }
        TaleSceneCommand.add("moot_broke", ::broke)
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("moot").executes { show(it) }
                    .then(Commands.literal("sit").executes { sit(it) })
                    .then(
                        Commands.literal("say").then(
                            Commands.argument("reason", StringArgumentType.word())
                                .suggests { ctx, b -> SharedSuggestionProvider.suggest(suggestions(ctx), b) }
                                .executes { say(it, StringArgumentType.getString(it, "reason")) },
                        ),
                    ),
            )
        }
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    /** The moot tale of the capital the command's player stands in (the open one, else the last), with its context. */
    private fun found(context: CommandContext<CommandSourceStack>): Pair<Tale, Ctx>? {
        val source = context.source
        val here = TaleCommand.here(context) ?: return null
        val level = source.level
        TaleEngine.use(level)
        val tales = TaleData.get(level).all().filter { it.template == Moot.id && it.capital == here.key }.sortedBy { it.id }
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) {
            TaleCommand.say(source, TaleCommand.plain("No moot is on record here (`tale start moot` seeds one)."))
            return null
        }
        val c = TaleEngine.ctx(level, t.capital)
        if (c == null) {
            TaleCommand.say(source, TaleCommand.plain("The capital's places are still being worked out: try again in a second."))
            return null
        }
        return t to c
    }

    // ---- tale moot ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val (t, c) = found(context) ?: return 0
        val level = c.level
        TaleCommand.say(source, TaleCommand.header("The Bell Moot"))
        val state = if (t.open) "open, at step ${t.step}, the moot on day ${t.d0 + Moot.MOOT_DAY} (today is ${c.day})" else "ended at ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
        val w = Moot.works(level, t)
        val price = Moot.price(c, t)
        TaleCommand.say(
            source,
            line(
                "Works",
                if (w != null) "${Moot.word(t)} (#${w.id}, ${w.state.name.lowercase()}), ${w.pledged} of ${w.price} Marks pledged, wanted on day ${w.wanted}"
                else "${Moot.word(t)}, not asked for yet (the lean variant): it would cost $price Marks",
            ),
        )
        TaleCommand.say(source, line("The town's gift", "${Moot.gift(price)} Marks for an A, ${Moot.gift(price) / 2} for a B" + if (t.n[Moot.GIFT] > 0) "; it gave ${t.n[Moot.GIFT]}" else ""))
        val bits = t.n[Moot.BITS]
        val heard = listOfNotNull("for".takeIf { bits and Moot.HEARD_FOR != 0 }, "against".takeIf { bits and Moot.HEARD_AGAINST != 0 })
        TaleCommand.say(source, line("Sides heard", heard.joinToString(" and ").ifEmpty { "neither" }))
        TaleCommand.say(source, line("The quarrel", if (bits and Moot.QUARRELLED != 0) "played" else "not played"))
        TaleCommand.say(source, line("The moot", if (t.n[Moot.SITS] == 1) "sits" + if (MootSquare.forced(t)) " (by `tale moot sit`)" else "" else "does not sit"))
        TaleCommand.say(source, line("Pledged in the tale", "${t.n[Moot.PLEDGED]} Marks" + if (t.n[Moot.PLEDGED] >= Moot.LIFT) " (lifts the verdict a step)" else ""))
        val offered = Moot.offered(level, t)
        if (offered.isEmpty()) TaleCommand.say(source, line("Offered", "nothing from the record: the vague word only (C, or B with a pledge)"))
        for (r in offered) {
            val letter = Moot.verdict(level, t, Moot.ident(r))
            val bears = if (Moot.bears(Moot.kind(t), r)) "bears" else "does not bear"
            TaleCommand.say(source, line("  ${Moot.ident(r)} (${r.points}, $bears)", "${FortuneReasons.text(r).string} -> $letter"))
        }
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off: the moot is neither called nor held."))
        return 1
    }

    // ---- tale moot sit ----

    private fun sit(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c) = found(context) ?: return 0
        if (!MootSquare.force(c, t)) {
            TaleCommand.say(source, TaleCommand.plain("The moot #${t.id} is over."))
            return 0
        }
        TaleCommand.say(source, TaleCommand.plain("The moot #${t.id} sits for two game hours" + if (t.step == Moot.AT_ASK) "; the crier still has to ask someone." else "."))
        return 1
    }

    // ---- tale moot say ----

    private fun suggestions(context: CommandContext<CommandSourceStack>): List<String> = try {
        val level = context.source.level
        val t = TaleData.get(level).all().filter { it.template == Moot.id && it.open }.maxByOrNull { it.id }
        (t?.let { Moot.reasons(level, it.capital).map { r -> Moot.ident(r) } }.orEmpty() + "vague").distinct()
    } catch (e: RuntimeException) {
        listOf("vague")
    }

    private fun say(context: CommandContext<CommandSourceStack>, said: String): Int {
        val source = context.source
        val player = source.playerOrException
        val (t, c) = found(context) ?: return 0
        if (!t.open) {
            TaleCommand.say(source, TaleCommand.plain("The moot #${t.id} is over."))
            return 0
        }
        if (!Tales.join(c.level, t, player)) {
            TaleCommand.say(source, TaleCommand.plain("You could not take the moot up (Tales off, or two tales taken up already)."))
            return 0
        }
        if (t.step < Moot.AT_CHOOSE) c.go(t, Moot.AT_CHOOSE)
        if (t.n[Moot.SITS] == 0) MootSquare.force(c, t)
        val word = if (said == "vague") null else said
        val r = word?.let { s -> Moot.reasons(c.level, c.capital).firstOrNull { Moot.ident(it) == s } }
        val letter = Moot.decide(c, t, player, word, r?.let { FortuneReasons.text(it).string } ?: said)
        TaleCommand.say(source, TaleCommand.plain(if (letter == null) "Your word was not taken." else "You spoke ($said): the moot ends $letter."))
        return if (letter == null) 0 else 1
    }

    // ---- tale scene moot_* ----

    private fun name(p: Townsperson): String = TaleSceneCommand.name(p)

    private fun free(level: ServerLevel, player: ServerPlayer): List<Townsperson> = Sets.freeAdults(level, player.position(), 24.0)

    /** The board stands where the player does, facing the way they look. */
    private fun marks(player: ServerPlayer): MootScenes.Marks = MootScenes.Marks(player.blockPosition(), player.direction)

    private fun quarrel(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player)
        if (free.size < 2) throw TaleSceneCommand.Unplayable("Two free adults are wanted within 24 blocks of you.")
        val (a, b) = free
        val third = free.getOrNull(2)
        val stage = Sets.quarrel(
            a, b, third, free.drop(3), shout = Sets.Line("tale.moot.for"), retort = Sets.Line("tale.moot.against"),
            toward = player.eyePosition, crowd = true, hours = null,
        )
        return TaleSceneCommand.begin(level, stage, "${name(a)} and ${name(b)} argue before you, the rest of the street in a half ring" + if (third != null) "; ${name(third)} hushes them." else ".")
    }

    private fun call(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player)
        val crier = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val m = marks(player)
        val stage = MootScenes.call(level, m, crier, free.getOrNull(1), free.getOrNull(2), null, hours = null)
        return TaleSceneCommand.begin(level, stage, "${name(crier)} walks to where you stand, rings the handbell and calls for someone to speak; the free folk about stand in a half ring.")
    }

    private fun verdict(level: ServerLevel, player: ServerPlayer, ending: String): String {
        val free = free(level, player)
        val crier = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val m = marks(player)
        val stage = when (ending) {
            Ending.A -> MootScenes.given(level, m, crier, free.getOrNull(1), free.getOrNull(2), null, full = true, hours = null)
            Ending.B -> MootScenes.given(level, m, crier, free.getOrNull(1), free.getOrNull(2), null, full = false, hours = null)
            else -> MootScenes.deferred(level, m, crier, free.getOrNull(1), free.getOrNull(2), said = false, hours = null)
        }
        val what = when (ending) {
            Ending.A -> "says the town gives and rings the handbell; the ring claps and cheers (one rings the town's bell if one is near)"
            Ending.B -> "says the town gives a part; the ring claps and grumbles"
            else -> "says the question stays and sighs; the ring grumbles"
        }
        return TaleSceneCommand.begin(level, stage, "${name(crier)} turns to the ring before you and $what.")
    }

    private fun broke(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player)
        if (free.size < 3) throw TaleSceneCommand.Unplayable("Three free adults are wanted within 24 blocks of you.")
        val (a, b, crier) = free
        val stage = Sets.quarrel(
            a, b, crier, free.drop(3), shout = Sets.Line("tale.moot.for"), retort = Sets.Line("tale.moot.against"),
            toward = Vec3(player.x, player.eyeY, player.z), crowd = true, hours = null, hush = Sets.Line("tale.moot.broke"),
        )
        return TaleSceneCommand.begin(level, stage, "${name(a)} and ${name(b)} shout each other down; ${name(crier)} ends the moot, badly, and they storm home.")
    }
}
