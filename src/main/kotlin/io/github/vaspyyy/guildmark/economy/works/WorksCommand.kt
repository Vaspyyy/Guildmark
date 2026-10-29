package io.github.vaspyyy.guildmark.economy.works

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.cooking.shop.CookShops
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.Town
import io.github.vaspyyy.guildmark.economy.works.build.WorksBuild
import io.github.vaspyyy.guildmark.economy.works.build.WorksLedger
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.trade.Houses
import io.github.vaspyyy.guildmark.trade.Stores
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.UUID

/**
 * Testing commands for a capital's works (design-influence.md section 9), for ops, run standing in the capital.
 * `/guildmark works` lists every works there: its id, kind and road, its state, price, pledges and patron, its stage
 * and site, and what it does now; then the works not wanted yet and what would make them. `propose <kind> [road]`
 * has one wanted now, whatever the need (a tower by the road named, or the nearest; a stall for you); `fund <id>
 * [marks]` is an op's free pledge, marked as one, of the rest of its price if no Marks are named; `start <id>` has
 * all its materials in its yard now and its builders seen to at once; `finish <id>` lays it at once; `stage <id>
 * <stage>` lays it free up to that stage (with no site yet, only puts it there as the builders would report it);
 * `materials <id>` is its bill, its yard, the wagons bringing the rest, and how far its builders are; `rush` has the
 * builders work ten times as fast and held-up freight come round the long way ten times as soon, on and off; `site
 * <id> [here]` has the builders look for its site again, or puts it where you stand; `remove <id>` takes it down and
 * pays every pledge back; `stall` opens your own stall's screen, standing at it; and `effect` is what the works do
 * here now: the ovens and the bake, the granary by good, each road's watch, and the stalls of players' own. As the
 * works screen and the yard's crate do, `pledge <id> <marks>`, `withdraw <id>` and `bring <id>` ask a pledge from
 * your purse, taking it back while it's unfunded, and the materials in your hand to the yard. A works' name in the
 * list opens its screen.
 *
 * Registered only once the works are wired in ([Works.ready]): until then there is no `works` command.
 */
object WorksCommand {
    /** The roads by the gate they come in at, as the commands name them (as `/guildmark caravan` does). */
    private val SIDES = mapOf("north" to "N", "east" to "E", "south" to "S", "west" to "W")

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("works")
                        .executes { list(it) }
                        .then(
                            Commands.literal("propose").then(
                                Commands.argument("kind", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(WorksKind.entries.map { it.id }, b) }
                                    .executes { propose(it, StringArgumentType.getString(it, "kind"), null) }
                                    .then(
                                        Commands.argument("road", StringArgumentType.word())
                                            .suggests { _, b -> SharedSuggestionProvider.suggest(SIDES.keys, b) }
                                            .executes { propose(it, StringArgumentType.getString(it, "kind"), StringArgumentType.getString(it, "road")) },
                                    ),
                            ),
                        )
                        .then(
                            Commands.literal("fund").then(
                                id().executes { fund(it, id(it), null) }
                                    .then(Commands.argument("marks", IntegerArgumentType.integer(1, 100000)).executes { fund(it, id(it), IntegerArgumentType.getInteger(it, "marks")) }),
                            ),
                        )
                        .then(Commands.literal("start").then(id().executes { start(it, id(it)) }))
                        .then(Commands.literal("finish").then(id().executes { finish(it, id(it)) }))
                        .then(
                            Commands.literal("stage").then(
                                id().then(
                                    Commands.argument("stage", StringArgumentType.word())
                                        .suggests { _, b -> SharedSuggestionProvider.suggest(Stage.entries.map { it.key }, b) }
                                        .executes { stage(it, id(it), StringArgumentType.getString(it, "stage")) },
                                ),
                            ),
                        )
                        .then(Commands.literal("materials").then(id().executes { materials(it, id(it)) }))
                        .then(Commands.literal("rush").executes { rush(it) })
                        .then(
                            Commands.literal("site").then(
                                id().executes { site(it, id(it), false) }
                                    .then(Commands.literal("here").executes { site(it, id(it), true) }),
                            ),
                        )
                        .then(Commands.literal("remove").then(id().executes { remove(it, id(it)) }))
                        // The works screen's own asks, until it's in (part B): a pledge from your purse, taking it back, and materials from your hand
                        .then(
                            Commands.literal("pledge").then(
                                id().then(Commands.argument("marks", IntegerArgumentType.integer(1, 100000)).executes { pledge(it, id(it), IntegerArgumentType.getInteger(it, "marks")) }),
                            ),
                        )
                        .then(Commands.literal("withdraw").then(id().executes { withdraw(it, id(it)) }))
                        .then(Commands.literal("bring").then(id().executes { bring(it, id(it)) }))
                        .then(Commands.literal("stall").executes { stall(it) })
                        .then(Commands.literal("effect").executes { effect(it) }),
                ),
        )
    }

    /** A works' id, suggesting those of the capital you stand in. */
    private fun id(): RequiredArgumentBuilder<CommandSourceStack, Int> =
        Commands.argument("id", IntegerArgumentType.integer(1)).suggests { c, b ->
            val level = c.source.level
            val piece = c.source.player?.let { Capitals.covering(level, it.blockPosition()) }
            SharedSuggestionProvider.suggest(piece?.let { p -> Works.records(level, p.center.asLong()).map { it.id.toString() } } ?: listOf(), b)
        }

    private fun id(context: CommandContext<CommandSourceStack>): Int = IntegerArgumentType.getInteger(context, "id")

    // ---- Where you are ----

    /** The capital you stand in: its piece, its market and its name. */
    private class Here(val piece: CapitalPiece, val entry: Markets.Entry, val name: String) {
        val capital: Long get() = entry.market.capital
    }

    private fun here(source: CommandSourceStack): Here? {
        val level = source.level
        if (level.dimension() != Level.OVERWORLD) {
            fail(source, "The capitals and their works are in the overworld.")
            return null
        }
        val piece = Capitals.covering(level, source.playerOrException.blockPosition())
        if (piece == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val entry = Markets.of(level, piece)
        if (entry == null) {
            fail(source, if (CityPlaces.failed(piece)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        return Here(piece, entry, VillageNames.of(piece.boardAnchor))
    }

    /** Whether the works are on; if not, why not, said (for the commands that change something). */
    private fun on(source: CommandSourceStack): Boolean {
        if (Works.enabled()) return true
        fail(source, off())
        return false
    }

    private fun off(): String =
        if (!Markets.enabled()) "The markets are switched off (setting: Capital Markets), and the town's works with them."
        else "The town's works are switched off (setting: Town Works): their records are kept, and nothing is wanted, built or done until it's on again."

    /** Works [id]'s record, or null (and why, said). */
    private fun record(source: CommandSourceStack, id: Int): WorksRec? {
        val w = Works.record(source.level, id)
        if (w == null) fail(source, "There's no works #$id (`/guildmark works` lists those of the capital you stand in).")
        return w
    }

    /** The market of [w]'s capital. */
    private fun entryOf(level: ServerLevel, w: WorksRec): Markets.Entry? = Markets.get(level)[BlockPos.of(w.capital)]

    // ---- works ----

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = here(source) ?: return 0
        if (!Works.enabled()) say(source, grey(off()))
        val c = Conditions(here.entry.market.hour)
        say(source, header("The works of ${here.name}: day ${c.day}, ${clock(c.clock)}"))
        val records = Works.records(level, here.capital)
        if (records.isEmpty()) {
            say(source, grey("None asked for yet: the town asks at each market close (two o'clock) for what the day showed a need of. `works propose <kind>` asks now; `works effect` shows how things stand."))
        }
        for (w in records) works(source, level, here.entry, w)
        val not = Works.notYet(level, here.capital, source.player?.uuid)
        if (not.isNotEmpty()) {
            say(source, label("Not wanted yet:"))
            for (n in not) {
                val what = Component.translatable(n.kind.key).withStyle(ChatFormatting.GRAY)
                if (n.kind == WorksKind.TOWER) what.append(grey(" (${road(level, here.capital, n.road)})"))
                say(source, line().append(grey("   ")).append(what).append(grey(": ")).append(Works.text(n.why, n.args).copy().withStyle(ChatFormatting.GRAY)))
            }
        }
        return 1
    }

    /** [w]'s lines in the list: what and whose, its state, its price and pledges, why it was wanted, its days and stage, its site, and what it does now. */
    private fun works(source: CommandSourceStack, level: ServerLevel, entry: Markets.Entry, w: WorksRec) {
        val v = Works.get(level, w.id) ?: return
        val name = if (Works.enabled()) WorksLedger.link(w.id, title(level, w)) else title(level, w)
        say(source, line().append(label("#${w.id} ")).append(name).append(grey(": ${state(w)}")))
        val parts = w.parts.entries.joinToString(", ") { "${it.key} ${it.value}" }
        val patron = v.patron?.let { p -> v.pledges.firstOrNull { it.player == p }?.name ?: name(level, p) }
        val free = w.pledges.filter { it.free }.sumOf { it.marks }
        say(
            source,
            grey(
                "   price ${w.price} ($parts); pledged ${w.pledged} by ${v.pledges.size}" +
                    (if (free > 0) ", $free of it an op's free pledge" else "") + (patron?.let { ", patron $it" } ?: ""),
            ),
        )
        say(source, line().append(grey("   wanted on day ${w.wanted}: ")).append(Works.text(w.why).copy().withStyle(ChatFormatting.GRAY)))
        if (w.paid) {
            val days = listOfNotNull(
                w.funded.takeIf { it >= 0 }?.let { "paid for on day $it" },
                w.begun.takeIf { it >= 0 }?.let { "begun on day $it" },
                w.done.takeIf { it >= 0 }?.let { "done on day $it" },
                w.stage?.let { "at its ${it.key}" + if (w.total > 0) " (${w.laid} of ${w.total} laid)" else "" },
            )
            if (days.isNotEmpty()) say(source, grey("   " + days.joinToString(", ")))
        }
        say(source, grey("   " + site(w)))
        say(source, line().append(grey("   now: ")).append(plain(doing(level, entry, w))))
        if (w.eased.isNotEmpty()) say(source, grey("   last eased: " + w.eased.entries.sortedBy { it.value }.joinToString(", ") { "${it.key} on day ${it.value}" }))
    }

    /** [w] by name, with its road (a tower's) or its owner (a stall's). */
    private fun title(level: ServerLevel, w: WorksRec): MutableComponent {
        val out = Component.translatable(w.kind.key).withStyle(ChatFormatting.WHITE)
        when (w.kind.per) {
            WorksKind.Per.ROAD -> out.append(grey(" (${road(level, w.capital, w.road)})"))
            WorksKind.Per.PLAYER -> out.append(grey(" (${w.owner?.let { owner -> w.pledges.lastOrNull { it.player == owner }?.name ?: name(level, owner) } ?: "nobody"}'s)"))
            WorksKind.Per.TOWN -> {}
        }
        return out
    }

    private fun state(w: WorksRec): String = when (w.state) {
        WorksState.WANTED -> "wanted, taking pledges"
        WorksState.FUNDED -> "paid for, its materials coming"
        WorksState.BUILDING -> "being built"
        WorksState.DONE -> "standing"
    }

    /** Where [w] goes, or why it has no site yet. */
    private fun site(w: WorksRec): String {
        val s = w.site ?: return when {
            w.noRoom >= 0 && w.siteLost >= 0 -> "site: none found since day ${w.noRoom}; called off when day ${w.siteLost + Works.SITE_LOST_DAYS} closes if there's still none, every pledge paid back"
            w.noRoom >= 0 -> "site: none found (day ${w.noRoom}); the builders look again"
            else -> "site: none yet (the builders find it; `works site ${w.id} here` puts it where you stand)"
        }
        return "site: ${pos(s.origin)} facing ${s.facing.serializedName}, lot ${s.lot.xSpan}x${s.lot.zSpan}, crate at ${pos(s.crate)}, post at ${pos(s.post)}" +
            if (s.planned) " (the plan's own lot)" else ""
    }

    /** What [w] does now, in a line. */
    private fun doing(level: ServerLevel, entry: Markets.Entry?, w: WorksRec): String {
        if (w.state == WorksState.WANTED) return "nothing yet: it wants ${w.price - w.pledged} Marks more"
        if (w.open) return "nothing yet: ${w.here.sum() + w.used.sum()} of its ${w.bill.sum()} units of materials in (`works materials ${w.id}`)"
        if (!Works.enabled()) return "nothing while the town's works are switched off"
        if (entry == null) return "nothing: its capital's market isn't open"
        val m = entry.market
        val town = entry.town
        return when (w.kind) {
            WorksKind.OVEN -> {
                val day = Ovens.bake(m, town) * tools(m) * Economy.BAKE_HOURS
                "bakes beside the plan's: ${Ovens.working(town)} ovens at work (the plan's alone ${Ovens.working(town, added = false)}), up to %.0f loaves over the baking hours against the town's %.0f a day".format(day, town.use(Good.BREAD)) +
                    if (Comings.enabled() && w.hired < 0) "; its journeyman baker hasn't come yet" else ""
            }
            WorksKind.GRANARY, WorksKind.GREAT_GRANARY -> {
                val room = Reserve.room(town)
                val held = Good.entries.filter { room[it.ordinal] > 0.0 }.joinToString(", ") { g -> "%.0f of %.0f %s".format(m[Site.GRANARY, g], room[g.ordinal], g.id) }
                val fill = w.fill.sum()
                "holds $held" + (if (fill > 1e-6) "; %.0f of its first filling still to come".format(fill) else "") +
                    if (w.kind == WorksKind.GRANARY) (if (w.gave) "; it has given grain" else "; it hasn't given grain yet") else ""
            }
            WorksKind.TOWER -> {
                val watch = Works.watch(level, w.capital, w.road) ?: return "a tower on a road whose way in isn't worked out yet"
                val clock = Conditions(m.hour).clock
                "keeps watch from ${watch.along.toInt()} blocks along the road (${pos(watch.pos)}), seeing ${watch.sight.toInt()} either way; ${Works.watchmen(level, w.capital, w.road, clock)} watchmen up there now"
            }
            WorksKind.STALL -> {
                val owner = w.owner ?: return "a stall with no owner"
                val s = OwnStalls.get(level).find(w.capital)?.pitches?.get(owner) ?: return "no stall in the market's records"
                val goods = s.goods().joinToString(", ") { "${s.whole(it)} ${it.id}" }.ifEmpty { "nothing on it" }
                "sells for ${s.name}: $goods; %.1f Marks in its till".format(s.till) + if (s.wages > 1e-6) ", its keeper owed %.2f".format(s.wages) else ""
            }
            WorksKind.COOKSHOP -> CookShops.describe(level, w)
            WorksKind.STORE -> Stores.describe(level, w)
            WorksKind.COUNTING_HOUSE -> Houses.describe(level, w)
        }
    }

    private fun propose(context: CommandContext<CommandSourceStack>, id: String, roadName: String?): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val here = here(source) ?: return 0
        if (!on(source)) return 0
        val kind = WorksKind.byId(id) ?: return fail(source, "No works called $id: ${WorksKind.entries.joinToString(", ") { it.id }}.")
        var road = -1
        if (kind == WorksKind.TOWER) {
            val legs = CaravanRoutes.legs(level, here.capital) ?: return fail(source, "Its ways in are still being worked out: try again in a second.")
            if (legs.isEmpty()) return fail(source, "${here.name} has no way in for wagons, so no road for a tower to watch.")
            val leg = if (roadName == null) legs.minBy { it.distance(player.x, player.z) } else {
                val side = SIDES[roadName] ?: return fail(source, "No road called $roadName: north, east, south or west.")
                legs.firstOrNull { it.side.name == side } ?: return fail(source, "${here.name} has no road in by the $roadName gate: " + legs.joinToString(", ") { side(it) })
            }
            road = leg.road
        } else if (roadName != null) {
            return fail(source, "Only a watchtower is for a road.")
        }
        val owner = if (kind.per == WorksKind.Per.PLAYER) player.uuid else null
        kind.after?.let { first ->
            // For a works of each player's, the one it follows on from is theirs too (a House needs one's own Store)
            if (Works.records(level, here.capital).none { it.kind == first && it.state == WorksState.DONE && (kind.per != WorksKind.Per.PLAYER || it.owner == owner) }) {
                return fail(source, "The ${kind.id} follows on from ${if (kind.per == WorksKind.Per.PLAYER) "your own " else "the "}${first.id}: propose and finish that first.")
            }
        }
        // The most of each a capital or a player takes (one they have here already is answered below, as any works' is)
        val data = WorksData.get(level)
        if (kind == WorksKind.STORE && data.find(here.capital, kind, owner = player.uuid) == null && !WorksWants.storeRoom(data, here.capital, player.uuid)) {
            return fail(source, "A capital takes ${WorksWants.STORES_A_TOWN} Carriers' Stores and a player has ${WorksWants.STORES_A_PLAYER} in all the world.")
        }
        if (kind == WorksKind.COUNTING_HOUSE && data.find(here.capital, kind, owner = player.uuid) == null && !WorksWants.houseRoom(data, player.uuid)) {
            return fail(source, "A player has ${WorksWants.HOUSES_A_PLAYER} Counting House in all the world.")
        }
        val w = Works.proposeNow(level, here.capital, kind, road, owner)
        if (w == null) {
            val had = Works.records(level, here.capital).firstOrNull {
                it.kind == kind && (kind.per != WorksKind.Per.ROAD || it.road == road) && (kind.per != WorksKind.Per.PLAYER || it.owner == owner)
            }
            return fail(source, "There's one asked for here already" + (had?.let { ": #${it.id}, ${state(it)}" } ?: "."))
        }
        say(source, line().append(plain("Works #${w.id}: ")).append(title(level, w)).append(plain(" wanted in ${here.name} now, price ${w.price} (" + w.parts.entries.joinToString(", ") { "${it.key} ${it.value}" } + ").")))
        say(source, grey("Pledge toward it at its post or on the works screen, or `works fund ${w.id}` for an op's free pledge."))
        return 1
    }

    private fun fund(context: CommandContext<CommandSourceStack>, id: Int, marks: Int?): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!on(source)) return 0
        val w = record(source, id) ?: return 0
        if (w.paid) return fail(source, "#$id is paid for already: ${state(w)}.")
        val n = Works.fundFree(level, player, w, marks)
        say(source, plain("An op's free pledge of $n Marks toward #$id, marked as one: it took none of yours and is never paid back. Pledged ${w.pledged} of ${w.price}."))
        if (w.paid) say(source, plain("It's paid for: its materials go out on the next wagons (`works materials $id`; `works start $id` has them in now)."))
        return 1
    }

    private fun start(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!on(source)) return 0
        val w = record(source, id) ?: return 0
        if (w.state == WorksState.DONE) return fail(source, "#$id stands finished already.")
        Works.startNow(level, player, w)
        if (!w.open) return fail(source, "#$id couldn't be started: ${state(w)}.")
        WorksBuild.start(level, id)
        say(source, plain("#$id is paid for, and all its materials are in its yard now: ${stuff(w.here)}."))
        if (w.site == null) {
            say(source, grey("It has no site yet: the builders look for one each second in loaded land (`works site $id here` puts it where you stand), and its crew comes once it has one."))
        } else {
            say(source, grey("Its master builder and crew come while a player's within ${WorksBuild.NEAR.toInt()} blocks of it, from sunrise to sunset (the town's labourers among them take an hour off at noon), and the clock lays the rest whenever its land is loaded. `works rush` is ten times as fast; `works stage $id <stage>` lays it free up to a stage, `works finish $id` all of it."))
        }
        return 1
    }

    private fun finish(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!on(source)) return 0
        val w = record(source, id) ?: return 0
        if (w.state == WorksState.DONE) return fail(source, "#$id stands finished already.")
        Works.finishNow(level, player, w)
        if (w.state != WorksState.DONE) return fail(source, "#$id couldn't be finished: ${state(w)}.")
        WorksBuild.start(level, id)
        say(source, line().append(plain("#$id stands finished, laid at once: ")).append(title(level, w)).append(plain(".")))
        say(source, line().append(grey("   now: ")).append(plain(doing(level, entryOf(level, w), w))))
        if (w.site == null) say(source, grey("   It had no site: it does its work all the same, with no building (a stall of your own stands at the capital's centre; `works site <id> here` before finishing puts it where you stand)."))
        else say(source, grey("   Its blocks went in at once where its land is loaded (else they go in as soon as it is), and its master builder opens it with whoever's about."))
        return 1
    }

    private fun stage(context: CommandContext<CommandSourceStack>, id: Int, key: String): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!on(source)) return 0
        val w = record(source, id) ?: return 0
        val stage = Stage.entries.firstOrNull { it.key == key } ?: return fail(source, "No stage called $key: ${Stage.entries.joinToString(", ") { it.key }}.")
        if (w.state == WorksState.DONE) return fail(source, "#$id stands finished already.")
        if (!w.paid) Works.fundFree(level, player, w, null)
        if (!w.open) return fail(source, "#$id couldn't be put at a stage: ${state(w)}.")
        if (WorksBuild.stageTo(level, id, stage)) {
            say(source, plain("#$id is laid free up to its ${stage.key}, the stages before it built as the builders would have them: now if its land is loaded, else as soon as it is. The crew goes on from there."))
            return 1
        }
        // No site to lay it on yet: only its record is put at the stage, as the builders would report it
        Works.stageNow(level, player, w, stage)
        if (w.stage != stage) return fail(source, "#$id couldn't be put at a stage: ${state(w)}.")
        say(source, plain("#$id has no site yet, so nothing's laid: it's at its ${stage.key} as the builders would report it (${state(w)}), and is built from the start once it has a site."))
        return 1
    }

    private fun materials(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val level = source.level
        val w = record(source, id) ?: return 0
        val v = Works.get(level, id) ?: return 0
        say(source, line().append(Component.literal("== #$id's materials: ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)).append(title(level, w)).append(Component.literal(" ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)))
        say(source, grey("stuff: bill, in the yard, used, on the wagons, still to send   (of it brought by players; lost on the road and sent again)"))
        for ((i, s) in Stuff.PAID.withIndex()) {
            if (w.bill[i] == 0 && w.here[i] == 0 && w.used[i] == 0) continue
            val left = if (w.open) (w.bill[i] - w.here[i] - w.used[i] - v.coming[i]).coerceAtLeast(0) else 0
            say(source, line().append(label(s.key)).append(plain(": ${w.bill[i]}, ${w.here[i]}, ${w.used[i]}, ${v.coming[i]}, $left")).append(grey("   (${w.brought[i]} brought; ${w.lost[i]} lost)")))
        }
        val ships = Works.shipments(level, id)
        if (ships.isEmpty()) say(source, grey(if (w.open) "No wagon on its way with any of it yet." else "Nothing more to come."))
        for (s in ships) {
            say(source, grey("   wagon #${s.caravan} by ${road(level, w.capital, s.road)}: ${stuff(s.stuff)}, due in at ${clock(Caravans.clockOf(Math.floorDiv(s.due, 1000L)))}"))
        }
        if (w.fill.any { it > 1e-6 }) {
            say(source, grey("   its first filling still to come: " + Good.entries.filter { w.fill[it.ordinal] > 1e-6 }.joinToString(", ") { "%.0f %s".format(w.fill[it.ordinal], it.id) }))
        }
        val m = entryOf(level, w)?.market
        if (m != null && (w.open || w.fill.any { it > 1e-6 }) && w.landed > Long.MIN_VALUE / 4) {
            val wait = if (Works.rush) WorksFreight.LONG_WAY / 10 else WorksFreight.LONG_WAY
            say(source, grey("   freight last landed ${m.hour - w.landed} market hours ago; after $wait without any, what's still wanted comes round the long way"))
        }
        for (line in WorksBuild.describe(level, id)) say(source, grey(line))
        return 1
    }

    private fun rush(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        Works.rush = !Works.rush
        return say(
            source,
            plain(
                if (Works.rush) "Rush on: the builders work ten times as fast (pace %.1f), and held-up freight comes round the long way after %d market hours.".format(Works.pace(), WorksFreight.LONG_WAY / 10)
                else "Rush off: the builders work at the setting's pace (%.1f), and held-up freight waits %d market hours.".format(Works.pace(), WorksFreight.LONG_WAY),
            ),
        )
    }

    private fun site(context: CommandContext<CommandSourceStack>, id: Int, here: Boolean): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!on(source)) return 0
        val w = record(source, id) ?: return 0
        if (w.state == WorksState.DONE) return fail(source, "#$id stands already: its site is where it stands.")
        if (!here) {
            Works.siteAgain(level, w)
            return say(source, plain("#$id's site is forgotten: the builders look for one again."))
        }
        // A tower stands out on its road, beyond the capital; anything else in the capital
        if (level.dimension() != Level.OVERWORLD) return fail(source, "The capitals and their works are in the overworld.")
        if (w.kind != WorksKind.TOWER && Capitals.covering(level, player.blockPosition())?.center?.asLong() != w.capital) return fail(source, "Stand in #$id's capital to site it there.")
        Works.siteHere(level, player, w)
        return say(source, plain("#$id's ${site(w)}."))
    }

    private fun remove(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val level = source.level
        val w = record(source, id) ?: return 0
        val title = title(level, w)
        val back = w.pledges.filter { !it.free }.sumOf { it.marks }
        if (!Works.remove(level, id)) return fail(source, "There's no works #$id.")
        say(
            source,
            line().append(plain("Works #$id taken down: ")).append(title).append(
                plain(if (back > 0) "; $back Marks paid back to those who pledged them (owed to anyone not on until they next join)." else "; nothing to pay back."),
            ),
        )
        say(source, grey("Its blocks come down to what its drawing found there: now where its land is loaded, else as soon as it is."))
        return 1
    }

    /** `works pledge`: [marks] of your own toward works [id], as the works screen pledges them (Works.pledge). */
    private fun pledge(context: CommandContext<CommandSourceStack>, id: Int, marks: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val before = Works.record(level, id)?.pledged ?: 0
        val why = Works.pledge(level, player, id, marks)
        if (why != null) {
            source.sendFailure(Works.text(why.key, why.args.map { it.toString() }))
            return 0
        }
        val w = Works.record(level, id) ?: return 1
        say(source, plain("You pledged ${w.pledged - before} Marks toward #$id from your purse. Pledged ${w.pledged} of ${w.price}."))
        if (w.paid) say(source, plain("It's paid for: its materials go out on the next wagons (`works materials $id`)."))
        return 1
    }

    /** `works withdraw`: what you pledged toward works [id] back while it's unfunded (Works.withdraw). */
    private fun withdraw(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val w = record(source, id) ?: return 0
        if (w.paid) return fail(source, "#$id is paid for: pledges can't be taken back now.")
        if (w.pledges.none { it.player == player.uuid }) return fail(source, "You've pledged nothing toward #$id.")
        val marks = Works.withdraw(level, player, id)
        return say(source, plain("Your pledges toward #$id are taken back: $marks Marks back in your purse (an op's free pledge gives none). Pledged ${w.pledged} of ${w.price}."))
    }

    /** `works bring`: what's in your hand toward works [id]'s bill, as its yard's crate takes it (Works.bring). */
    private fun bring(context: CommandContext<CommandSourceStack>, id: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!on(source)) return 0
        val w = record(source, id) ?: return 0
        if (!w.open) return fail(source, "#$id takes materials only once it's paid for and until it's built: ${state(w)}.")
        val stack = player.mainHandItem
        if (stack.isEmpty) return fail(source, "Hold the materials in your main hand.")
        val name = stack.hoverName.copy()
        val units = Works.bring(level, player, id, stack)
        if (units <= 0) {
            source.sendFailure(Component.literal("#$id's bill doesn't want that: ").append(name).append(Component.literal(" (`works materials $id` shows what it still wants).")))
            return 0
        }
        return say(source, line().append(plain("#$id's yard took $units units of ")).append(name).append(plain(" (what it still wanted; the rest stays in your hand). In the yard now: ${stuff(w.here)}.")))
    }

    private fun stall(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val here = here(source) ?: return 0
        val s = OwnStalls.get(level).find(here.capital)?.pitches?.get(player.uuid)
            ?: return fail(source, "You have no stall of your own in ${here.name}: `works propose stall`, `works site <id> here` and `works finish <id>` set one up with no builders.")
        if (player.position().distanceToSqr(Vec3.atCenterOf(s.at)) > StallDesk.REACH * StallDesk.REACH) return fail(source, "Your stall stands at ${pos(s.at)}: go to it first.")
        StallDesk.openPitch(level, player, here.piece)
        return 1
    }

    private fun effect(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = here(source) ?: return 0
        val m = here.entry.market
        val town = here.entry.town
        val c = Conditions(m.hour)
        say(source, header("What the works do in ${here.name}: day ${c.day}, ${clock(c.clock)}"))
        if (!Works.enabled()) say(source, grey(off() + " The bake is the bakers' as it always was, the granary neither gives nor fills, and towers don't count."))
        ovens(source, m, town)
        granary(source, m, town)
        roads(source, level, here)
        stalls(source, level, here)
        return 1
    }

    /** The ovens, and what they can bake: by the ovens with the works on, by the bakers as before with them off. */
    private fun ovens(source: CommandSourceStack, m: Market, town: Town) {
        val planned = Ovens.planned(town)
        say(source, line().append(label("Ovens: ")).append(plain("${town.ovens} (the plan's $planned, the works' ${town.ovens - planned}); ${Ovens.working(town)} at work, with ${town.bakers} bakers")))
        val tools = tools(m)
        val old = town.bakers * Economy.BAKE * tools * m.factor(Maker.OVENS, Good.BREAD)
        val now = if (Works.enabled()) Ovens.bake(m, town) * tools else old
        say(
            source,
            line().append(label("The bake: "))
                .append(plain("up to %.0f loaves an hour, %.0f over the %d baking hours, against the town's %.0f a day".format(now, now * Economy.BAKE_HOURS, Economy.BAKE_HOURS.toInt(), town.use(Good.BREAD))))
                .append(grey(if (Works.enabled()) " (the bakers' old bake would be %.0f an hour)".format(old) else " (the bakers' bake: the works are off)")),
        )
        m.events.firstOrNull { it.kind == EventKind.OVEN_CRACKED }?.let { e ->
            say(source, grey("   an oven cracked: " + (if (Works.enabled()) "one of them" else "the bake") + " at x%.2f, %d h left".format(e.factor, e.until - m.hour)))
        }
        if (tools < 1.0) say(source, grey("   worn tools: x${Economy.WORN}"))
        say(source, grey("   bread: %.0f in the market against %.0f kept; %.0f went short yesterday".format(m.stock(Good.BREAD), town.target(Good.BREAD), m.yesterday(Tally.SHORT, Good.BREAD))))
    }

    /** The town granary by good: what it holds against its room, what it took in and gave today, and when it gives. */
    private fun granary(source: CommandSourceStack, m: Market, town: Town) {
        val grade = town.granary
        if (grade <= 0) {
            say(source, line().append(label("Granary: ")).append(grey("none")))
            return
        }
        say(source, line().append(label("Granary: ")).append(plain(if (grade >= 2) "the great granary" else "the granary")).append(grey(if (Reserve.open(town)) "" else " (shut: the works are off)")))
        val room = Reserve.room(town)
        for (g in Good.entries) {
            if (room[g.ordinal] <= 0.0 && m[Site.GRANARY, g] <= 1e-6) continue
            say(
                source,
                grey(
                    "   %s: %.0f of %.0f (%.0f%%); today %.0f stored, %.0f given out".format(
                        g.id, m[Site.GRANARY, g], room[g.ordinal], 100.0 * Reserve.full(m, town, g), m.today(Tally.STORED, g), m.today(Tally.RELEASED, g),
                    ),
                ),
            )
        }
        val millDay = town.mills * Economy.MILL * Economy.MILL_HOURS
        say(
            source,
            grey(
                "   it gives the mill grain while the mill holds under half a day's (%.0f of %.0f now) and the market's wheat is under half its target (%.0f%% now)".format(
                    m[Site.MILL, Good.WHEAT], millDay, 100.0 * m.stock(Good.WHEAT) / town.target(Good.WHEAT),
                ),
            ),
        )
    }

    /** Each road in: its tower and its watch, where a band would strike, and the watchmen up there now. */
    private fun roads(source: CommandSourceStack, level: ServerLevel, here: Here) {
        val legs = CaravanRoutes.legs(level, here.capital)
        if (legs == null) {
            say(source, line().append(label("Roads: ")).append(grey("still being worked out")))
            return
        }
        if (legs.isEmpty()) {
            say(source, line().append(label("Roads: ")).append(grey("no way in for wagons")))
            return
        }
        val clock = Conditions(here.entry.market.hour).clock
        for (leg in legs) {
            val out = line().append(label("The ${side(leg)}: "))
            val watch = Works.watch(level, here.capital, leg.road)
            if (watch == null) {
                out.append(grey("no tower standing"))
            } else {
                out.append(plain("a tower's watch from ${watch.along.toInt()} of ${leg.length.toInt()} blocks along, seeing ${watch.sight.toInt()} either way; ${Works.watchmen(level, here.capital, leg.road, clock)} watchmen up now"))
            }
            val band = CaravanRoutes.band(level, leg)
            if (band != null) {
                val natural = CaravanRoutes.natural(leg, band)
                val at = CaravanRoutes.strikeAt(leg, band)
                out.append(grey(if (watch != null && kotlin.math.abs(at - natural) > 0.5) "; a band would strike at %.0f, out of its sight (at %.0f without it)".format(at, natural) else "; a band would strike at %.0f".format(at)))
            }
            say(source, out)
        }
    }

    /** The stalls of players' own in the square. */
    private fun stalls(source: CommandSourceStack, level: ServerLevel, here: Here) {
        val pitches = OwnStalls.get(level).find(here.capital)?.pitches?.values.orEmpty()
        if (pitches.isEmpty()) {
            say(source, line().append(label("Stalls of their own: ")).append(grey("none")))
            return
        }
        for (s in pitches) {
            val goods = s.goods().joinToString(", ") { "${s.whole(it)} ${it.id}" }.ifEmpty { "nothing on it" }
            say(
                source,
                line().append(label("${s.name}'s stall: ")).append(plain(goods))
                    .append(grey("; %.1f Marks in its till".format(s.till) + (if (s.wages > 1e-6) ", its keeper owed %.2f".format(s.wages) else "") + ", at ${pos(s.at)}")),
            )
        }
    }

    // ---- Text ----

    /** [road] into [capital] by its gate ("the north road"), or the plan's own road from the farms. */
    private fun road(level: ServerLevel, capital: Long, road: Int): String {
        val leg = CaravanRoutes.cached(capital, road) ?: CaravanRoutes.legs(level, capital)?.firstOrNull { it.road == road }
        return when {
            leg != null -> "the " + side(leg)
            road < 0 -> "the plan's own road"
            else -> "road $road"
        }
    }

    /** [leg]'s road by its gate: "north road". */
    private fun side(leg: CaravanRoutes.Leg): String = (SIDES.entries.firstOrNull { it.value == leg.side.name }?.key ?: "") + " road"

    /** [player]'s name if they're on, else a short form of their id. */
    private fun name(level: ServerLevel, player: UUID): String = level.server.playerList.getPlayer(player)?.name?.string ?: player.toString().take(8)

    /** Units of materials by Stuff.PAID, as words. */
    private fun stuff(units: IntArray): String = Stuff.PAID.indices.filter { units[it] > 0 }.joinToString(", ") { "${units[it]} ${Stuff.PAID[it].key}" }.ifEmpty { "none" }

    private fun tools(m: Market): Double = if (m.stock(Good.TOOLS) >= 1.0) 1.0 else Economy.WORN

    private fun pos(p: BlockPos): String = "${p.x} ${p.y} ${p.z}"

    private fun clock(h: Int): String = "%02d:00".format(h)

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
