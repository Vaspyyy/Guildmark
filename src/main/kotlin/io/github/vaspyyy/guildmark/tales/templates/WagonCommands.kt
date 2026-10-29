package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.outlaw.Captives
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleOptions
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.phys.Vec3

/**
 * The Fourth Wagon's testing commands (living-world/stories/design.md 12.4), game masters only. Each step command does what the talk (or the
 * camp, or the close) does, through the same code, and takes the tale up for you first if you have not:
 *
 *  - `/guildmark tale wagon`: the wagon of the capital you stand in, from the record: the strike, the band and its camp, the price, the cast and
 *    where the hand's body is, the way chosen, the flags, what the watch has in hand, and the ending it would have now. It changes nothing.
 *  - `/guildmark tale wagon strike`: the next wagon (or convoy) of the timetable on its way into the town is waylaid within seconds by the band
 *    that works its road, as `caravan ambush ... waylaid` has it: the trouble the recipe of 12.5 makes, for `tale day` or the close to seed.
 *  - `tale wagon told`: you ask the captain of the camp (the step's MEET): the camp is on your map, the price is told.
 *  - `tale wagon way <go|pay|watch>`: you choose the way with the kin (go: the rescue note is yours; pay: the price is the rider's to take) or
 *    ask the captain for the watch (it rides only for a player the town knows, 25 or more at its board).
 *  - `tale wagon pay`: you pay the band's price (36 Marks from your pack), as to the rider at the parley spot; the band lets the hand go.
 *  - `tale wagon free`: you cut the hand loose, as at the stake (their body must be about: within 96 blocks of the camp; else the record has
 *    them free).
 *  - `tale wagon home`: the hand is home at the board now, as if walked there (freed by your knife if nobody freed them).
 *  - `tale wagon ride`: the watch rides now, as at the close (sent on your word first, if it was not).
 *  - `tale wagon dawn`: the watch's return is now, whatever the hour: stand near the gate to see it walked in.
 *  - `/guildmark tale scene wagon_wait`, `wagon_parley` and `wagon_reunion`: the waiting at the gate, the parley and the reunion, played now
 *    before you with whoever is free near you, whatever the hour or the switches. No tale is needed or touched.
 */
internal object WagonCommands {
    private val WAYS = listOf("go", "pay", "watch")

    fun register() {
        TaleSceneCommand.add("wagon_wait", ::waitScene)
        TaleSceneCommand.add("wagon_parley", ::parleyScene)
        TaleSceneCommand.add("wagon_reunion", ::reunionScene)
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("wagon").executes { show(it) }
                    .then(Commands.literal("strike").executes { strike(it) })
                    .then(Commands.literal("told").executes { told(it) })
                    .then(
                        Commands.literal("way").then(
                            Commands.argument("way", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(WAYS, b) }
                                .executes { way(it, StringArgumentType.getString(it, "way")) },
                        ),
                    )
                    .then(Commands.literal("pay").executes { pay(it) })
                    .then(Commands.literal("free").executes { free(it) })
                    .then(Commands.literal("home").executes { home(it) })
                    .then(Commands.literal("ride").executes { ride(it) })
                    .then(Commands.literal("dawn").executes { dawn(it) }),
            )
        }
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    private fun where(pos: BlockPos?): String = pos?.toShortString() ?: "nowhere known"

    private fun say(source: CommandSourceStack, text: String): Int = TaleCommand.say(source, TaleCommand.plain(text))

    /** The wagon tale of the capital the command's player stands in (the open one, else the last), with its context. */
    private fun found(context: CommandContext<CommandSourceStack>): Pair<Tale, Ctx>? {
        val source = context.source
        val here = TaleCommand.here(context) ?: return null
        val level = source.level
        TaleEngine.use(level)
        val tales = TaleData.get(level).all().filter { it.template == Wagon.id && it.capital == here.key }.sortedBy { it.id }
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) {
            say(source, "No Fourth Wagon is on record here ('tale wagon strike' waylays the next wagon of the timetable, then 'tale day'; or 'tale start wagon').")
            return null
        }
        val c = TaleEngine.ctx(level, t.capital)
        if (c == null) {
            say(source, "The capital's places are still being worked out: try again in a second.")
            return null
        }
        return t to c
    }

    /** The open wagon tale and the player, taken up by them if they have not; null (and said why) if it cannot be. */
    private fun open(context: CommandContext<CommandSourceStack>): Triple<Tale, Ctx, ServerPlayer>? {
        val source = context.source
        val player = source.playerOrException
        val (t, c) = found(context) ?: return null
        if (!t.open) {
            say(source, "The wagon #${t.id} is over (ended ${t.ending}).")
            return null
        }
        val status = t.part(player.uuid)?.status
        if (status != Part.Status.IN && status != Part.Status.DONE && !Tales.join(c.level, t, player)) {
            say(source, "You could not take the wagon up (Tales off, or two tales taken up already).")
            return null
        }
        if (!t.open) {
            say(source, "The wagon #${t.id} ended ${t.ending} as you took it up.")
            return null
        }
        return Triple(t, c, player)
    }

    private fun after(source: CommandSourceStack, t: Tale) =
        say(source, "The tale is at step ${t.step}" + if (t.open) " (it would end ${Wagon.outcome(t)})." else ", and ended ${t.ending}.")

    // ---- tale wagon ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val (t, c) = found(context) ?: return 0
        val level = c.level
        TaleCommand.say(source, TaleCommand.header("The Fourth Wagon"))
        val state = if (t.open) "open, at step ${t.step} (D${c.day - t.d0}, the step's last day ${t.by}; today is ${c.day})" else "ended ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
        val strike = if (Wagon.caravan(t) < 0) "made up by a forced start (no wagon, no driver)" else "wagon #${Wagon.caravan(t)}" +
            (Wagon.driverAt(level, t)?.let { ", its driver at the gate (${it.toShortString()})" } ?: ", its driver no longer at the gate")
        TaleCommand.say(source, line("The strike", "$strike, on the ${Wagon.side(t)} road (road ${Wagon.road(t)}, the ${Wagon.gate(t).name.lowercase()} gate)"))
        TaleCommand.say(source, line("The wreck", where(Wagon.wreck(t))))
        val band = Wagon.band(level, t)
        TaleCommand.say(
            source,
            line(
                "The band",
                "${Wagon.bandWord(t)} (#${Wagon.bandId(t)})" + (band?.let {
                    ", camp at ${it.pos.toShortString()}${if (it.slain) ", broken" else ""}${if (!it.built) ", not built yet" else ""}, holding ${it.captives.size}; parley spot about ${Wagon.parley(t, it).toShortString()}"
                } ?: ", no longer on record"),
            ),
        )
        val price = when {
            Wagon.flag(t, Wagon.PAID) -> "${Wagon.price(t)} Marks, paid"
            Wagon.flag(t, Wagon.LAPSED) -> "${Wagon.price(t)} Marks, lapsed"
            Wagon.priceStands(level, t) -> "${Wagon.price(t)} Marks, standing until dusk on day ${t.d0 + Wagon.PRICE_DAYS}"
            else -> "${Wagon.price(t)} Marks, no longer asked"
        }
        TaleCommand.say(source, line("The price", price))
        for (slot in listOf(Wagon.KIN, Wagon.HAND, Wagon.CAPTAIN)) {
            val cast = t.cast(slot)
            val r = c.resident(t, slot)
            val body = TaleBodies.body(level, t, slot)
            val at = when {
                body == null -> "no body about"
                body.bound -> "knelt in the camp at ${body.blockPosition().toShortString()}"
                body.captiveOf >= 0 -> "walking home at ${body.blockPosition().toShortString()}"
                body.tale == t.id -> "the tale's at ${body.blockPosition().toShortString()}"
                else -> "about their day at ${body.blockPosition().toShortString()}"
            }
            TaleCommand.say(
                source,
                line(
                    "  $slot",
                    "${cast?.name ?: "none"}${if (cast?.gone?.isNotEmpty() == true) " (gone: ${cast.gone})" else ""}" +
                        (r?.let { ", home #${it.home}${if (!it.alive) ", dead" else ""}" } ?: "") + (if (cast?.held == true) " (held)" else "") + "; $at",
                ),
            )
        }
        if (t.cast(Wagon.HAND) != null) {
            TaleCommand.say(source, TaleCommand.grey("      the band's record: " + if (!WagonCamp.held(level, t)) "none" else if (WagonCamp.freedOnRecord(level, t)) "freed" else "held"))
        }
        val way = when (Wagon.way(t)) {
            Wagon.WAY_A -> "go for them (A)"
            Wagon.WAY_B -> "pay the price (B)"
            Wagon.WAY_C -> "the watch (C)"
            else -> "not chosen"
        }
        TaleCommand.say(source, line("The way", way))
        val flags = listOf(
            Wagon.TOLD to "told", Wagon.TAKEN to "taken", Wagon.KNIFE to "cut loose", Wagon.PAID to "paid", Wagon.RELEASED to "let go",
            Wagon.HOME to "home", Wagon.RODE to "the watch rode (in on day ${Wagon.comes(t)})", Wagon.SLAIN to "the band broken",
            Wagon.LAPSED to "the price lapsed", Wagon.KIN_GONE to "the kin gone", Wagon.MADE to "made up",
        ).filter { Wagon.flag(t, it.first) }.joinToString(", ") { it.second }
        TaleCommand.say(source, line("Flags", flags.ifEmpty { "none" }))
        TaleCommand.say(source, line("The watch", WagonWatch.state(t)))
        val player = source.player
        if (player != null) {
            val note = TaleOptions.carried(player, t, Wagon.AT_FREE)
            val points = Wagon.board(level, t)?.let { Standing.points(level, it, player) } ?: 0
            TaleCommand.say(source, line("You", "standing $points at the board (the watch rides for ${StandingTier.KNOWN.threshold}); " + if (note != null) "you carry the rescue note" else "no rescue note"))
        }
        if (t.open) TaleCommand.say(source, line("It would end", Wagon.outcome(t) + if (Wagon.outcome(t) == "W") " (at the last close: the band moves the hand on)" else ""))
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off: nobody is held, the rider and the watch stay away, and the hand is the band's as any captive is."))
        return 1
    }

    // ---- tale wagon strike ----

    private fun strike(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        if (!Caravans.ambushes()) return TaleCommand.fail(source, "Road ambushes are switched off (settings: Road Ambushes, with Caravans and Outlaws).")
        val data = CaravanData.get(level)
        val c = data.caravans
            .filter { it.capital == here.key && (it.run == Run.WAGON || it.run == Run.CONVOY) && (it.phase == Phase.PLANNED || it.phase == Phase.WAITING || it.phase == Phase.ROLLING) }
            .minByOrNull { it.setOut }
            ?: return TaleCommand.fail(source, "No wagon of the timetable is on its way into the town just now (a test wagon of `caravan spawn` is never a tale's): wait for the next and try again.")
        val leg = CaravanRoutes.cached(c.capital, c.road) ?: return TaleCommand.fail(source, "Its road isn't worked out yet.")
        val camps = OutlawCamps.get(level)
        val band = data.roadOrNull(c.capital, c.road)?.band?.takeIf { it >= 0 }?.let { camps.band(it) }?.takeIf { !it.slain && it.built }
            ?: CaravanRoutes.band(level, leg)
            ?: camps.band(c.band)?.takeIf { !it.slain && it.built }
            ?: return TaleCommand.fail(source, "No band works that road: `/guildmark caravan ambush near` makes a camp by you first.")
        val why = Caravans.ambush(level, c, band, true)
        if (why != null) return TaleCommand.fail(source, "Wagon #${c.id} can't be struck: $why.")
        say(
            source,
            "Wagon #${c.id} (a ${c.run.id} of the timetable) by the ${here.capital.direction(leg.side).name.lowercase()} road will be waylaid by band #${band.id} within seconds" +
                (if (c.phase != Phase.ROLLING) ", once it sets out" else "") + ". Then `tale day` (or the close) seeds the wagon, with a household of two free.",
        )
        return 1
    }

    // ---- The steps ----

    private fun told(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (Wagon.flag(t, Wagon.TOLD) && t.step != Wagon.AT_MEET) return say(source, "The camp and the price are told already.").let { after(source, t); 0 }
        if (!Wagon.tellCamp(c, t, player)) return say(source, "The tale is not at the meeting (step ${t.step}).").let { 0 }
        say(source, "The captain told you of the camp (on your map) and the price, ${Wagon.price(t)} Marks.")
        after(source, t)
        return 1
    }

    private fun way(context: CommandContext<CommandSourceStack>, which: String): Int {
        val source = context.source
        if (which !in WAYS) return TaleCommand.fail(source, "No way '$which': go, pay or watch.")
        val (t, c, player) = open(context) ?: return 0
        if (t.step == Wagon.AT_MEET) Wagon.tellCamp(c, t, player)
        if (t.step != Wagon.AT_CHOOSE && t.step != Wagon.AT_FREE) return say(source, "The way can't be chosen now (step ${t.step}).").let { 0 }
        when (which) {
            "go" -> {
                if (!Wagon.goFor(c, t, player)) return say(source, "You can't go for them now (they are free, or the watch has ridden).").let { 0 }
                say(source, if (giveNote(c, t, player)) "You will go for them: the rescue note is in your pack." else "You will go for them (no note: you carry one, or there is no camp to lead to).")
            }
            "pay" -> {
                if (!Wagon.payWay(c, t, player)) return say(source, "The price no longer stands.").let { 0 }
                say(source, "You will pay: the rider waits at the parley spot from 6:00 to 20:00 (`tale wagon pay` pays now).")
            }
            else -> {
                val points = Wagon.sendWatch(c, t, player)
                if (points < 0) return say(source, "The watch can't be sent now (it is sent already, or the hand is free).").let { 0 }
                if (Wagon.way(t) != Wagon.WAY_C) {
                    say(source, "The captain won't send the watch on your word: $points at this board, ${StandingTier.KNOWN.threshold} wanted (`/guildmark advance standing <points>` adds some).")
                    return 0
                }
                say(source, "The watch rides at the next close (`tale wagon ride` sends it now).")
            }
        }
        after(source, t)
        return 1
    }

    /** The rescue note, put in [player]'s pack as the kin would hand it (the board's own contract, `Contracts.take` from the hall). */
    private fun giveNote(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        val level = c.level
        if (t.step != Wagon.AT_FREE || TaleOptions.carried(player, t, Wagon.AT_FREE) != null) return false
        val board = Wagon.board(level, t) ?: return false
        val note = Wagon.rescueNote(level, t) ?: return false
        val stack = Contracts.take(note, level, board, Direction.NORTH, player, fromHall = true) ?: return false
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0f, 1.0f)
        player.sendSystemMessage(Wagon.noteSaid(t))
        return true
    }

    private fun pay(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        val price = Wagon.price(t)
        if (t.step !in Wagon.AT_MEET..Wagon.AT_FREE || !Wagon.priceStands(c.level, t)) return say(source, "The price no longer stands (paid, lapsed, or the hand is free).").let { 0 }
        if (TownControl.marks(player) < price) return say(source, "You have fewer than $price Marks.").let { 0 }
        val part = t.partFor(player.uuid) ?: return say(source, "The tale has no room for you.").let { 0 }
        // As the rider's option puts it in: the reason first, the Marks in escrow, and the tale hears it
        t.addWhy(Why("wagon.paid", listOf(player.gameProfile.name, price.toString())))
        TownControl.pay(player, price)
        part.escrow += price
        c.data.changed()
        Tales.tell(c.level, Event.Gave(t.id, player, price))
        say(source, "You paid $price Marks: the rider gives the band the word (the parley plays if they are at the spot, else the record lets the hand go within 30 seconds).")
        after(source, t)
        return 1
    }

    private fun free(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (Wagon.freed(t) || Wagon.flag(t, Wagon.RODE)) return say(source, "The hand is out of the band's hands already.").let { 0 }
        val body = WagonCamp.bound(c.level, t)
        if (body != null) {
            Captives.interact(c.level, player, body)
            say(source, "You cut ${body.name.string} loose: they keep close behind you; walk them to the board at ${where(Wagon.board(c.level, t))}.")
        } else {
            if (!WagonCamp.freeOnRecord(c.level, t)) return say(source, "The band has no record of the hand.").let { 0 }
            say(source, "The hand's body isn't about (come within 96 blocks of the camp to see it): the record has them free, by your knife.")
        }
        // The watch sees the record within the second; with Tales off it would not, so it is done here
        Wagon.knifed(c, t, player.uuid)
        after(source, t)
        return 1
    }

    private fun home(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (Wagon.flag(t, Wagon.HOME)) return say(source, "The hand is home already.").let { after(source, t); 0 }
        if (Wagon.flag(t, Wagon.RODE)) return say(source, "The watch rode: `tale wagon dawn` brings them in.").let { 0 }
        if (!Wagon.freed(t)) {
            WagonCamp.freeOnRecord(c.level, t)
            Wagon.knifed(c, t, player.uuid)
        }
        if (!t.open) return say(source, "The wagon ended ${t.ending}.").let { 0 }
        t.partFor(player.uuid)?.let { it.did = it.did or Part.WALKED or Part.DECIDED }
        Wagon.home(c, t)
        say(source, "The hand is home at the board, on the record: the reunion plays if both are there and you are near, else the record ends it within a minute.")
        after(source, t)
        return 1
    }

    private fun ride(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, player) = open(context) ?: return 0
        if (Wagon.flag(t, Wagon.RODE)) return say(source, "The watch has ridden already: `tale wagon dawn` brings them in.").let { 0 }
        if (Wagon.way(t) != Wagon.WAY_C) {
            if (t.step == Wagon.AT_MEET) Wagon.tellCamp(c, t, player)
            val points = Wagon.sendWatch(c, t, player)
            if (Wagon.way(t) != Wagon.WAY_C) {
                return say(
                    source,
                    if (points < 0) "The watch can't be sent now (step ${t.step}, or the hand is free)." else "The captain won't send the watch on your word: $points at this board, ${StandingTier.KNOWN.threshold} wanted.",
                ).let { 0 }
            }
        }
        if (t.step != Wagon.AT_FREE) return say(source, "The tale is not at the deed (step ${t.step}).").let { 0 }
        WagonCamp.ride(c, t)
        say(source, "The watch rode: the band let the hand go. They come in at seven by the ${Wagon.side(t)} road (`tale wagon dawn` has it now).")
        after(source, t)
        return 1
    }

    private fun dawn(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (t, c, _) = open(context) ?: return 0
        if (!WagonWatch.dawn(c, t)) return say(source, "The watch has not ridden (`tale wagon ride`).").let { 0 }
        say(
            source,
            "The watch comes in now: stand within 48 blocks of the gate at ${where(Wagon.front(c, t))} to see it walked in" +
                (if (!Tales.scenes()) "; Town tale scenes are off, so the record ends it at once." else "; with nobody near, the record ends it."),
        )
        return 1
    }

    // ---- tale scene wagon_* ----

    private fun name(p: Townsperson): String = TaleSceneCommand.name(p)

    private fun ahead(player: ServerPlayer, blocks: Double): Vec3 {
        val look = player.lookAngle
        val len = kotlin.math.sqrt(look.x * look.x + look.z * look.z).coerceAtLeast(0.01)
        return player.position().add(look.x / len * blocks, 0.0, look.z / len * blocks)
    }

    private fun waitScene(level: ServerLevel, player: ServerPlayer): String {
        val kin = Sets.freeAdults(level, player.position(), 24.0).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val down = ahead(player, 16.0).add(0.0, 1.5, 0.0)
        val stage = WagonScenes.waitingAt(kin, down, "Ash", hours = null)
        return TaleSceneCommand.begin(level, stage, "${name(kin)} looks down the road the way you face, sighs, and is comforted by whoever passes (if anyone is free).")
    }

    private fun parleyScene(level: ServerLevel, player: ServerPlayer): String {
        val rider = Sets.freeAdults(level, player.position(), 24.0).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val camp = ahead(player, 14.0)
        val away = Sets.standable(level, BlockPos.containing(ahead(player, 10.0))) ?: rider.blockPosition()
        val stage = WagonScenes.parley(rider, player, camp, away) {}
        return TaleSceneCommand.begin(level, stage, "${name(rider)} takes a pouch, weighs it, nods to you, signals ahead of you, and walks off that way with it.")
    }

    private fun reunionScene(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val kin = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val hand = free.drop(1).maxByOrNull { it.distanceToSqr(kin) } ?: throw TaleSceneCommand.Unplayable("Only one person free within 24 blocks of you: it needs two.")
        val watchers = free.filter { it !== kin && it !== hand }.take(3)
        val stage = WagonScenes.reunion(kin, hand, player, watchers, listOf(), quiet = false)
        return TaleSceneCommand.begin(level, stage, "${name(kin)} waits for ${name(hand)}, runs to them and they embrace; then ${name(kin)} thanks you.")
    }
}
