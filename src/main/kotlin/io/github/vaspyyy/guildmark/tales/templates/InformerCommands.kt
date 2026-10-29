package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * The Sergeant's Friend's testing commands (living-world/stories/design.md 12.4), game masters only. Each step command does what the talk (or
 * the drop, or the close) does, through the same code, and takes the tale up for you first if you have not:
 *
 *  - `/guildmark tale informer`: the informer of the capital you stand in, from the record: the strikes, the road and the band, the cast (who
 *    has been told, who tried, whose tell heard) and where they are, the sergeant's last answer, the way, the flags, what the watch has in hand,
 *    what the close would do tonight, and the ending it would have now. It changes nothing.
 *  - `tale informer strike`: the next wagon (or convoy) of the timetable on its way into the town is waylaid within seconds by the band that
 *    works its road (the road of the town's last strike first), as `caravan ambush ... waylaid` has it. Twice, and `tale day` (or the close)
 *    seeds the informer.
 *  - `tale informer tell <a|b|friend>`: the tell of one of the three is heard, as if you turned to them (all three heard is the meeting done).
 *  - `tale informer slip <a|b|friend>`: you let slip a wagon is coming to one of the three (the meeting is done, and the test is set).
 *  - `tale informer test`: the decoy goes now, as at the close: the friend told, the band strikes it; anyone else is clear.
 *  - `tale informer drop`: the dead drop is tried now, whatever the hour (the friend told, at the test; stand within 48 blocks of the drop).
 *  - `tale informer catch`: you catch the friend at the drop, as if you spoke to them there.
 *  - `tale informer choose <named|hushed|watch>`: you choose with the sergeant (hushed: you speak to the friend first, and they go to the
 *    sergeant themselves). `tale informer hush`, `own` and `gate`: the route B one step at a time.
 *  - `tale informer scene`: the ending's scene is in its hours now: stand in the gate yard (A) or by the friend's door (B).
 *  - `/guildmark tale scene informer_slate`, `informer_drop`, `informer_naming` and `informer_handshake`: the Count, the Dead Drop, the Naming
 *    and the Handshake, played now before you with whoever is free near you, whatever the hour or the switches. No tale is needed or touched.
 */
internal object InformerCommands {
    private val MEN = mapOf("a" to Informer.NEIGHBOUR_A, "b" to Informer.NEIGHBOUR_B, "friend" to Informer.FRIEND)
    private val WAYS = listOf("named", "hushed", "watch")

    fun register() {
        TaleSceneCommand.add("informer_slate", ::slateScene)
        TaleSceneCommand.add("informer_drop", ::dropScene)
        TaleSceneCommand.add("informer_naming", ::namingScene)
        TaleSceneCommand.add("informer_handshake", ::handshakeScene)
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("informer").executes { show(it) }
                    .then(Commands.literal("strike").executes { strike(it) })
                    .then(
                        Commands.literal("tell").then(
                            Commands.argument("who", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(MEN.keys, b) }
                                .executes { tell(it, StringArgumentType.getString(it, "who")) },
                        ),
                    )
                    .then(
                        Commands.literal("slip").then(
                            Commands.argument("who", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(MEN.keys, b) }
                                .executes { slip(it, StringArgumentType.getString(it, "who")) },
                        ),
                    )
                    .then(Commands.literal("test").executes { test(it) })
                    .then(Commands.literal("drop").executes { drop(it) })
                    .then(Commands.literal("catch").executes { catch(it) })
                    .then(
                        Commands.literal("choose").then(
                            Commands.argument("way", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(WAYS, b) }
                                .executes { choose(it, StringArgumentType.getString(it, "way")) },
                        ),
                    )
                    .then(Commands.literal("hush").executes { hush(it) })
                    .then(Commands.literal("own").executes { own(it) })
                    .then(Commands.literal("gate").executes { gate(it) })
                    .then(Commands.literal("scene").executes { scene(it) }),
            )
        }
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    private fun where(pos: BlockPos?): String = pos?.toShortString() ?: "nowhere known"

    private fun say(source: CommandSourceStack, text: String): Int = TaleCommand.say(source, TaleCommand.plain(text))

    /** The informer of the capital the command's player stands in (the open one, else the last), with its context. */
    private fun found(context: CommandContext<CommandSourceStack>): Pair<Tale, Ctx>? {
        val source = context.source
        val here = TaleCommand.here(context) ?: return null
        val level = source.level
        TaleEngine.use(level)
        val tales = TaleData.get(level).all().filter { it.template == Informer.id && it.capital == here.key }.sortedBy { it.id }
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) {
            say(source, "No Sergeant's Friend is on record here ('tale informer strike' twice, then 'tale day'; or 'tale start informer').")
            return null
        }
        val c = TaleEngine.ctx(level, t.capital)
        if (c == null) {
            say(source, "The capital's places are still being worked out: try again in a second.")
            return null
        }
        return t to c
    }

    /** The open informer and the player, taken up by them if they have not; null (and said why) if it cannot be. */
    private fun open(context: CommandContext<CommandSourceStack>): Triple<Tale, Ctx, ServerPlayer>? {
        val source = context.source
        val player = source.playerOrException
        val (t, c) = found(context) ?: return null
        if (!t.open) {
            say(source, "The informer #${t.id} is over (ended ${t.ending}).")
            return null
        }
        val status = t.part(player.uuid)?.status
        if (status != Part.Status.IN && status != Part.Status.DONE && !Tales.join(c.level, t, player)) {
            say(source, "You could not take the informer up (Tales off, or two tales taken up already).")
            return null
        }
        if (!t.open) {
            say(source, "The informer #${t.id} ended ${t.ending} as you took it up.")
            return null
        }
        return Triple(t, c, player)
    }

    private fun after(source: CommandSourceStack, t: Tale) =
        say(source, "The tale is at step ${t.step}" + if (t.open) " (it would end ${Informer.outcome(t)})." else ", and ended ${t.ending}.")

    // ---- tale informer ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val (t, c) = found(context) ?: return 0
        val level = c.level
        TaleCommand.say(source, TaleCommand.header("The Sergeant's Friend"))
        val state = if (t.open) "open, at step ${t.step} (D${c.day - t.d0}, the step's last day ${t.by}; today is ${c.day})" else "ended ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
        val strikes = if (Informer.flag(t, Informer.MADE)) "made up by a forced start (no wagon was struck)" else "${Informer.strikes(t)} wagons waylaid over ${Informer.span(t)} market day(s)"
        TaleCommand.say(source, line("The trouble", "$strikes, on the ${Informer.side(t)} road (road ${Informer.road(t)}, the ${Informer.gate(t).name.lowercase()} gate, the gate's front at ${where(Informer.front(c, t))})"))
        val band = Informer.band(level, t)
        TaleCommand.say(
            source,
            line(
                "The band",
                "${Informer.bandWord(t)} (#${Informer.bandId(t)})" + (band?.let {
                    ", camp at ${it.pos.toShortString()}${if (it.slain) ", broken" else ""}${if (!it.built) ", not built yet" else ""}" +
                        if (Caravans.works(level, it)) ", working the town's roads" else ", working none of the town's roads"
                } ?: ", no longer on record"),
            ),
        )
        for (slot in listOf(Informer.SERGEANT) + Informer.THREE) {
            val cast = t.cast(slot)
            val r = c.resident(t, slot)
            val body = TaleBodies.body(level, t, slot)
            val at = when {
                body == null -> "no body about"
                body.tale == t.id -> "the tale's at ${body.blockPosition().toShortString()}"
                else -> "about their day at ${body.blockPosition().toShortString()}"
            }
            val bit = Informer.bitOf(slot)
            val marks = if (bit == 0) "" else listOfNotNull(
                if (Informer.told(t) and bit != 0) "told" else "not told",
                if (Informer.pending(t) == bit) "not yet tried" else null,
                if (Informer.heardTells(t) and bit != 0) "tell heard" else null,
            ).joinToString(", ", prefix = " [", postfix = "]")
            TaleCommand.say(
                source,
                line(
                    "  $slot",
                    "${cast?.name ?: "none"}${if (cast?.gone?.isNotEmpty() == true) " (gone: ${cast.gone})" else ""}" +
                        (r?.let { ", home #${it.home}${if (!it.alive) ", dead" else ""}" } ?: "") + (if (cast?.held == true) " (held)" else "") + marks + "; $at",
                ),
            )
        }
        val answer = when (Informer.answer(t)) {
            Informer.CLEAR -> "${Informer.names(t, Informer.answerOf(t))} clear"
            Informer.STRUCK -> "the decoy struck: ${Informer.names(t, Informer.answerOf(t))} talks"
            Informer.LOWLIE -> "the band lay low (${Informer.low(t)} close(s) in a row)"
            Informer.RETURNED -> "the band lay low twice: ${Informer.names(t, Informer.answerOf(t))} may be told again"
            Informer.AT_DROP -> "the friend caught at the drop"
            else -> "none yet"
        }
        TaleCommand.say(source, line("The last answer", answer))
        val way = when (Informer.way(t)) {
            Informer.WAY_A -> "named before the watch (A)"
            Informer.WAY_B -> "owned up to the sergeant (B)"
            Informer.WAY_C -> "only the road (C)"
            else -> "not chosen"
        } + if (Informer.chosen(t) >= 0) ", on day ${Informer.chosen(t)}" else ""
        TaleCommand.say(source, line("The way", way))
        val flags = listOf(
            Informer.SEEN to "the leak shown", Informer.CAUGHT to "caught at the drop", Informer.HUSH to "spoken to first", Informer.OWNED to "owned up",
            Informer.SELF to "the sergeant tried the neighbours", Informer.MADE to "made up", Informer.GONE to "the friend gone",
            Informer.BROKEN to "the band broken", Informer.QUIET to "the roads quiet",
        ).filter { Informer.flag(t, it.first) }.joinToString(", ") { it.second }
        TaleCommand.say(source, line("Flags", flags.ifEmpty { "none" }))
        val (drop, scout) = InformerWatch.dropMarks(c, t)
        TaleCommand.say(source, line("The drop", "${where(drop)}, the scout from ${where(scout)}; the post at ${where(InformerWatch.post(c, t))}; the gate yard at ${where(Informer.yard(c, t))}"))
        TaleCommand.say(source, line("The watch", InformerWatch.state(t)))
        TaleCommand.say(source, line("Tonight", InformerTrap.tonight(c, t)))
        if (t.open) TaleCommand.say(source, line("It would end", Informer.outcome(t)))
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off: nobody is held, and no decoy goes."))
        return 1
    }

    // ---- tale informer strike ----

    private fun strike(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        if (!Caravans.ambushes()) return TaleCommand.fail(source, "Road ambushes are switched off (settings: Road Ambushes, with Caravans and Outlaws).")
        val data = CaravanData.get(level)
        val camps = OutlawCamps.get(level)
        val last = TaleData.get(level).find(here.key)?.waylaid?.lastOrNull()
        val coming = data.caravans
            .filter { it.capital == here.key && (it.run == Run.WAGON || it.run == Run.CONVOY) && (it.phase == Phase.PLANNED || it.phase == Phase.WAITING || it.phase == Phase.ROLLING) }
            .sortedBy { it.setOut }
        // The road of the town's last strike first: the seed wants two on one road by one band
        val c = coming.firstOrNull { last != null && it.road == last.road } ?: coming.firstOrNull()
            ?: return TaleCommand.fail(source, "No wagon of the timetable is on its way into the town just now (a test wagon of `caravan spawn` is never a tale's): wait for the next and try again.")
        val leg = CaravanRoutes.cached(c.capital, c.road) ?: return TaleCommand.fail(source, "Its road isn't worked out yet.")
        val band = last?.takeIf { it.road == c.road }?.band?.let { camps.band(it) }?.takeIf { !it.slain && it.built }
            ?: data.roadOrNull(c.capital, c.road)?.band?.takeIf { it >= 0 }?.let { camps.band(it) }?.takeIf { !it.slain && it.built }
            ?: CaravanRoutes.band(level, leg)
            ?: return TaleCommand.fail(source, "No band works that road: `/guildmark caravan ambush near` makes a camp by you first.")
        val why = Caravans.ambush(level, c, band, true)
        if (why != null) return TaleCommand.fail(source, "Wagon #${c.id} can't be struck: $why.")
        val day = TaleEngine.today(level, here.key)
        val before = TaleData.get(level).find(here.key)?.waylaid.orEmpty()
            .filter { it.road == c.road && it.band == band.id && day - it.day in 0..Informer.WINDOW }.map { it.caravan }.distinct().size
        say(
            source,
            "Wagon #${c.id} (a ${c.run.id} of the timetable) by the ${here.capital.direction(leg.side).name.lowercase()} road will be waylaid by band #${band.id} within seconds" +
                (if (c.phase != Phase.ROLLING) ", once it sets out" else "") + ": ${before + 1} of the 2 the informer wants on that road in ${Informer.WINDOW} market days." +
                if (before + 1 >= 2) " Then `tale day` (or the close) seeds the informer." else " Strike another on the same road once this one is waylaid.",
        )
        return 1
    }

    // ---- The steps ----

    private fun man(source: CommandSourceStack, who: String): String? {
        val slot = MEN[who]
        if (slot == null) TaleCommand.fail(source, "No one '$who': a, b or friend.")
        return slot
    }

    private fun tell(context: CommandContext<CommandSourceStack>, who: String): Int {
        val source = context.source
        val slot = man(source, who) ?: return 0
        val (t, c, _) = open(context) ?: return 0
        if (t.step != Informer.AT_MEET && t.step != Informer.AT_TEST) return say(source, "The three tell only at the meeting and the test (step ${t.step}).").let { 0 }
        Informer.heardFrom(c, t, Informer.bitOf(slot))
        say(source, "You heard ${Informer.name(t, slot)}'s tell.")
        after(source, t)
        return 1
    }

    private fun slip(context: CommandContext<CommandSourceStack>, who: String): Int {
        val source = context.source
        val slot = man(source, who) ?: return 0
        val (t, c, player) = open(context) ?: return 0
        if (!Informer.slipTo(c, t, player, slot)) {
            val why = when {
                t.step != Informer.AT_MEET && t.step != Informer.AT_TEST -> "the tale is not at the meeting or the test (step ${t.step})"
                Informer.pending(t) != 0 -> "a test is set already (${Informer.names(t, Informer.pending(t))}): `tale informer test` runs it"
                else -> "${Informer.name(t, slot)} has been told already"
            }
            return say(source, "You can't let it slip now: $why.").let { 0 }
        }
        say(source, "You let slip to ${Informer.name(t, slot)} that a wagon is coming on the ${Informer.side(t)} road: the decoy goes at the close (`tale informer test` sends it now).")
        after(source, t)
        return 1
    }

    private fun test(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, _) = open(context) ?: return 0
        if (!InformerTrap.test(c, t)) return say(source, "Nobody is told and untried (`tale informer slip <a|b|friend>` first).").let { 0 }
        val said = when (Informer.answer(t)) {
            Informer.STRUCK -> "The band struck the decoy and was beaten off: ${Informer.names(t, Informer.answerOf(t))} talks. The sergeant asks who you told."
            Informer.CLEAR -> "Nothing struck the decoy: ${Informer.names(t, Informer.answerOf(t))} is clear."
            Informer.LOWLIE -> "The band lies low (it raided a village today or yesterday): no test counted; it is tried again at the close."
            Informer.RETURNED -> "The band lay low twice: the test is given back, and ${Informer.names(t, Informer.answerOf(t))} may be told again."
            else -> "The test ran."
        }
        say(source, said)
        after(source, t)
        return 1
    }

    private fun drop(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, _) = open(context) ?: return 0
        if (t.step != Informer.AT_TEST || Informer.pending(t) != Informer.BIT_FRIEND) return say(source, "The drop is tried only at the test, with the friend told and untried (`tale informer slip friend`).").let { 0 }
        InformerWatch.again(t)
        val (at, scout) = InformerWatch.dropMarks(c, t)
        say(
            source,
            "The friend goes to the drop at ${where(at)} now (the scout comes from ${where(scout)}): stand within 48 blocks of it, out of their sight, and speak to them there to catch them" +
                (if (!Tales.scenes()) "; Town tale scenes are off, so nobody goes (`tale informer catch` catches them on the record)." else "."),
        )
        return 1
    }

    private fun catch(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (!Informer.caught(c, t, player)) return say(source, "The friend can be caught only at the test, told and untried (`tale informer slip friend`).").let { 0 }
        InformerWatch.dropCaught(c, t)
        say(source, "You caught ${Informer.name(t, Informer.FRIEND)} at the drop: the leak is shown, and the sergeant asks who you told.")
        after(source, t)
        return 1
    }

    private fun choose(context: CommandContext<CommandSourceStack>, which: String): Int {
        val source = context.source
        if (which !in WAYS) return TaleCommand.fail(source, "No way '$which': named, hushed or watch.")
        val (t, c, player) = open(context) ?: return 0
        if (t.step != Informer.AT_CHOOSE || !Informer.flag(t, Informer.SEEN)) return say(source, "The way can be chosen only once the leak is shown (step ${t.step}).").let { 0 }
        when (which) {
            "named" -> {
                Informer.nameFriend(c, t, player)
                say(source, "You named ${Informer.name(t, Informer.FRIEND)} before the watch: the Naming plays in the gate yard at ${where(Informer.yard(c, t))} (10:00 to 16:00; `tale informer scene` has it now).")
            }
            "hushed" -> {
                Informer.hushFirst(c, t, player)
                Informer.owns(c, t, player)
                say(source, "You spoke to ${Informer.name(t, Informer.FRIEND)} first, and they went to the sergeant themselves: the Handshake plays at their door at ${where(Informer.doorOf(c, t, Informer.FRIEND))} (17:30 to 20:30; `tale informer scene` has it now).")
            }
            else -> {
                Informer.roadOnly(c, t, player)
                say(source, "You told the sergeant only the road: they double the watch on it.")
            }
        }
        after(source, t)
        return 1
    }

    private fun hush(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (!Informer.hushFirst(c, t, player)) return say(source, "You can say so only once the leak is shown, and once (step ${t.step}).").let { 0 }
        say(source, "The sergeant lets you speak to ${Informer.name(t, Informer.FRIEND)} first: they are at home (`tale informer own` or `gate`).")
        after(source, t)
        return 1
    }

    private fun own(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (!Informer.owns(c, t, player)) return say(source, "Speak to the friend first (`tale informer hush`).").let { 0 }
        say(source, "${Informer.name(t, Informer.FRIEND)} goes to the sergeant themselves: the Handshake is next.")
        after(source, t)
        return 1
    }

    private fun gate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (!Informer.flag(t, Informer.HUSH) || !Informer.nameFriend(c, t, player)) return say(source, "Speak to the friend first (`tale informer hush`).").let { 0 }
        say(source, "You send ${Informer.name(t, Informer.FRIEND)} to the gate: the Naming is next.")
        after(source, t)
        return 1
    }

    private fun scene(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, _) = open(context) ?: return 0
        if (!InformerWatch.now(t)) return say(source, "No scene is to come (the way is not chosen, or it was only the road).").let { 0 }
        val at = if (Informer.way(t) == Informer.WAY_A) Informer.yard(c, t) else Informer.doorOf(c, t, Informer.FRIEND)
        say(
            source,
            "The scene's hours are now: stand within 48 blocks of ${where(at)}" +
                (if (!Tales.scenes()) "; Town tale scenes are off, so the record ends it at once." else "; not played in two minutes, the record ends it."),
        )
        return 1
    }

    // ---- tale scene informer_* ----

    private fun name(p: Townsperson): String = TaleSceneCommand.name(p)

    private fun ahead(player: ServerPlayer, blocks: Double): Vec3 {
        val look = player.lookAngle
        val len = kotlin.math.sqrt(look.x * look.x + look.z * look.z).coerceAtLeast(0.01)
        return player.position().add(look.x / len * blocks, 0.0, look.z / len * blocks)
    }

    private fun slateScene(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val sergeant = free.firstOrNull { it.role == TownRole.GUARD } ?: free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val stage = InformerScenes.slateAt(sergeant, ahead(player, 16.0).add(0.0, 1.5, 0.0), hours = null)
        return TaleSceneCommand.begin(level, stage, "${name(sergeant)} counts on a slate, sighs, looks out the way you face, counts again, and puts it away.")
    }

    private fun dropScene(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val friend = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val scout = free.drop(1).maxByOrNull { it.distanceToSqr(friend) } ?: throw TaleSceneCommand.Unplayable("Only one person free within 24 blocks of you: it needs two.")
        val at = friend.blockPosition()
        val away = Sets.standable(level, BlockPos.containing(ahead(player, 20.0))) ?: scout.blockPosition()
        val stage = InformerScenes.drop(friend, scout, at, away) {}
        return TaleSceneCommand.begin(level, stage, "${name(friend)} looks about; ${name(scout)} comes to them, takes a folded paper, and walks off the way you face.")
    }

    private fun namingScene(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val sergeant = free.firstOrNull { it.role == TownRole.GUARD } ?: free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val friend = free.firstOrNull { it !== sergeant && it.role != TownRole.GUARD } ?: free.firstOrNull { it !== sergeant }
            ?: throw TaleSceneCommand.Unplayable("Only one person free within 24 blocks of you: it needs two.")
        val guards = free.filter { it !== sergeant && it !== friend && it.role == TownRole.GUARD }.take(2)
        val watchers = free.filter { it !== sergeant && it !== friend && it !in guards }.take(2)
        val home = Sets.standable(level, BlockPos.containing(ahead(player, -12.0)))
        val stage = InformerScenes.naming(sergeant, friend, player, home, guards, watchers)
        return TaleSceneCommand.begin(level, stage, "${name(sergeant)} points at ${name(friend)} and names them; ${name(friend)} answers and walks off behind you.")
    }

    private fun handshakeScene(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val friend = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val sergeant = free.drop(1).firstOrNull { it.role == TownRole.GUARD } ?: free.drop(1).firstOrNull()
            ?: throw TaleSceneCommand.Unplayable("Only one person free within 24 blocks of you: it needs two.")
        val watchers = free.filter { it !== sergeant && it !== friend }.take(2)
        val stage = InformerScenes.handshake(friend, sergeant, watchers)
        return TaleSceneCommand.begin(level, stage, "${name(sergeant)} walks up to ${name(friend)}; they stand a block apart, shake hands, and ${name(sergeant)} forgives them.")
    }
}
