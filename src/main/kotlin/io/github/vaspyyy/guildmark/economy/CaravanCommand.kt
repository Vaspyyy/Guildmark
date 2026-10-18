package io.github.vaspyyy.guildmark.economy

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.quest.CaravanEscorts
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.math.sqrt

/**
 * Testing commands for the wagons and the trouble on the road (design-road.md section 6), run in a capital or on one of
 * its roads; everything they do goes through the same records as the wagons' own day, so what follows is what would.
 * `/guildmark caravan` (or `caravan list`) is every caravan into the capital: its road, hour, how far in, state, guards,
 * load, strike point and odds, then its wrecks, drivers and escort jobs. `caravan spawn [road]` sets a wagon out now,
 * loaded as the next hour's; `caravan ambush [band] [outcome]` has the nearest caravan struck within seconds (by that
 * band, the road's, or `near`: a camp made nearby), waylaid, beaten off or by the dice; `caravan arrive` brings it in
 * now; `caravan wreck` waylays a wagon at your feet, its driver running for the gate; `caravan take [lot]` takes a lot
 * of the wreck or loot pile you stand by, as the bodies' right-click will; `caravan escort` hands you the escort
 * contract for the next wagon on the nearest road, as if taken from the board; `caravan driver` brings the last waylaid
 * driver to you and has them tell you what was lost. `/guildmark road` is the roads into the capital (band, peril,
 * stage, last strike, next wagon); `road peril <n>` sets the nearest road's, and `road clear` ends every road's trouble.
 */
object CaravanCommand {
    /** How near one of a capital's roads you must stand to work on it from outside the capital. */
    private const val ROADS = 640.0

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("caravan")
                        .executes { list(it) }
                        .then(Commands.literal("list").executes { list(it) })
                        .then(
                            Commands.literal("spawn")
                                .executes { spawn(it, null) }
                                .then(
                                    Commands.argument("road", StringArgumentType.word())
                                        .suggests { _, b -> SharedSuggestionProvider.suggest(SIDES.keys, b) }
                                        .executes { spawn(it, StringArgumentType.getString(it, "road")) },
                                ),
                        )
                        .then(
                            Commands.literal("ambush")
                                .executes { ambush(it, null, null) }
                                .then(
                                    Commands.argument("band", StringArgumentType.word())
                                        .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("road", "near"), b) }
                                        .executes { ambush(it, StringArgumentType.getString(it, "band"), null) }
                                        .then(
                                            Commands.argument("outcome", StringArgumentType.word())
                                                .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("waylaid", "beaten", "dice"), b) }
                                                .executes { ambush(it, StringArgumentType.getString(it, "band"), StringArgumentType.getString(it, "outcome")) },
                                        ),
                                ),
                        )
                        .then(Commands.literal("arrive").executes { arrive(it) })
                        .then(Commands.literal("wreck").executes { wreck(it) })
                        .then(
                            Commands.literal("take")
                                .executes { take(it, -1) }
                                .then(Commands.argument("lot", IntegerArgumentType.integer(0, 63)).executes { take(it, IntegerArgumentType.getInteger(it, "lot")) }),
                        )
                        .then(Commands.literal("escort").executes { escort(it) })
                        .then(Commands.literal("driver").executes { driver(it) }),
                )
                .then(
                    Commands.literal("road")
                        .executes { roads(it) }
                        .then(Commands.literal("peril").then(Commands.argument("peril", IntegerArgumentType.integer(0, RoadPeril.MOST)).executes { peril(it, IntegerArgumentType.getInteger(it, "peril")) }))
                        .then(Commands.literal("clear").executes { clear(it) }),
                ),
        )
    }

    /** The roads by the gate they come in at, as the commands name them. */
    private val SIDES = mapOf("north" to "N", "east" to "E", "south" to "S", "west" to "W")

    // ---- Where you are ----

    /** The capital you're in, or whose road you're on: its piece, its market, its ways in, and its name. */
    private class Here(val piece: CapitalPiece, val entry: Markets.Entry, val legs: List<CaravanRoutes.Leg>, val name: String) {
        val capital: Long get() = entry.market.capital
    }

    private fun here(source: CommandSourceStack): Here? {
        val level = source.level
        val player = source.playerOrException
        if (level.dimension() != Level.OVERWORLD) {
            fail(source, "The wagons run in the overworld.")
            return null
        }
        if (!Caravans.enabled()) say(source, grey("The wagons are switched off (settings: Caravans, with Capital Markets): the goods come at the hour and nothing on the roads moves."))
        val pos = player.blockPosition()
        val piece = Capitals.covering(level, pos) ?: nearest(level, pos)
        if (piece == null) {
            fail(source, "You're not in a capital, nor on a road into one that's been worked out (stand in the capital first).")
            return null
        }
        val entry = Markets.of(level, piece)
        if (entry == null) {
            fail(source, "Its market isn't open yet: its places are still being worked out. Try again in a second.")
            return null
        }
        val legs = CaravanRoutes.legs(level, piece.center.asLong())
        if (legs == null) {
            fail(source, "Its ways in are still being worked out: try again in a second.")
            return null
        }
        if (legs.isEmpty()) {
            fail(source, "It has no way in for wagons (see the log): its goods come at the hour.")
            return null
        }
        return Here(piece, entry, legs, VillageNames.of(piece.boardAnchor))
    }

    /** The capital one of whose worked-out roads passes nearest [pos], within [ROADS]. */
    private fun nearest(level: ServerLevel, pos: BlockPos): CapitalPiece? {
        var best: Long? = null
        var bestD = ROADS
        for (cap in CaravanData.get(level).capitals) {
            for (leg in CaravanRoutes.legs(level, cap.capital) ?: continue) {
                val d = leg.distance(pos.x + 0.5, pos.z + 0.5)
                if (d < bestD) {
                    bestD = d
                    best = cap.capital
                }
            }
        }
        return best?.let { CaravanRoutes.capital(level, it) }
    }

    /** Of [here]'s ways in, the one passing nearest [player]. */
    private fun nearestLeg(here: Here, player: ServerPlayer): CaravanRoutes.Leg = here.legs.minBy { it.distance(player.x, player.z) }

    /** Of [here]'s caravans, the one nearest [player] along its leg now, of those still on their way (or about to be). */
    private fun nearestCaravan(level: ServerLevel, here: Here, player: ServerPlayer): CaravanRec? {
        val t = level.overworldClockTime
        return CaravanData.get(level).caravans
            .filter { it.capital == here.capital && (it.phase == Phase.PLANNED || it.phase == Phase.WAITING || it.phase == Phase.ROLLING) }
            .minByOrNull { c ->
                val at = CaravanRoutes.cached(c.capital, c.road)?.at(Caravans.distance(c, t)) ?: return@minByOrNull Double.MAX_VALUE
                flat(player.x - at.x, player.z - at.z)
            }
    }

    // ---- caravan ----

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = here(source) ?: return 0
        val data = CaravanData.get(level)
        val t = level.overworldClockTime
        say(source, header("The wagons of ${here.name}: day ${Math.floorDiv(t, 24000L) + 1}, ${clock(Caravans.clockOf(Math.floorDiv(t, 1000L)))}"))
        val caravans = data.caravans.filter { it.capital == here.capital }
        if (caravans.isEmpty()) say(source, grey("No caravan on the roads now. The day's three are due in at 9, 12 and 15 o'clock, setting out as long before as their road takes; `caravan spawn` sends one now."))
        for (c in caravans.sortedBy { it.setOut }) {
            say(source, caravanLine(level, data, c, t))
            say(source, line().append(grey("   load: ")).append(plain(goods(c.load).ifEmpty { "nothing" }))
                .append(if (c.exports.any { it >= 1.0 }) grey("   going back with: ").append(plain(goods(c.exports))) else Component.empty()))
            say(source, strikeLine(level, data, c))
        }
        val wrecks = data.wrecks.filter { it.capital == here.capital }
        for (w in wrecks) {
            val kind = if (w.pile) "Loot pile" else "Wreck"
            val left = w.lots.withIndex().filter { it.value.count > 0 }.joinToString(", ") { (k, lot) -> "[$k] ${lot.count} ${lot.good.id}" }.ifEmpty { "emptied" }
            say(source, line().append(label("$kind #${w.id} ")).append(plain("at ${w.x.toInt()} ${w.y.toInt()} ${w.z.toInt()}" + if (w.horseDead) ", the horse dead in its traces" else ""))
                .append(grey("  ${ago(w.until - t)} left; lots: ")).append(plain(left)))
        }
        for (d in data.drivers.filter { it.capital == here.capital }) {
            val state = if (t < d.from) "running for the gate, there in ${seconds(d.from - t)}" else "sat at ${d.at.x} ${d.at.y} ${d.at.z} for ${ago(d.until - t)}"
            say(source, line().append(label("Driver of #${d.caravan} ")).append(plain(state)).append(grey("; lost ${d.lost.joinToString(", ") { "${it.count} ${it.good.id}" }}; told ${d.told.size}")))
        }
        for (e in data.escorts.filter { it.capital == here.capital }) {
            val leg = here.legs.firstOrNull { it.road == e.road }
            say(source, line().append(label("Escort job ")).append(plain("on the ${leg?.let { side(it) } ?: "road ${e.road}"}, held by ${e.name}: ${e.state.id}"))
                .append(grey(if (e.caravan >= 0) " (wagon #${e.caravan})" else " (no wagon yet; lapses ${clock(Caravans.clockOf(Math.floorDiv(e.until, 1000L)))})")))
        }
        return 1
    }

    private fun caravanLine(level: ServerLevel, data: CaravanData, c: CaravanRec, t: Long): Component {
        val leg = CaravanRoutes.cached(c.capital, c.road)
        val at = Caravans.distance(c, t)
        val stage = RoadPeril.stage(data, c.capital, c.road)
        val out = line().append(label("#${c.id} ")).append(plain("${c.run.id} by the ${leg?.let { side(it) } ?: "plan's own road"} (${stage.id}), ${c.phase.id}, due ${clock(Caravans.clockOf(c.hour))}"))
            .append(grey("   ${at.toInt()} of ${c.length.toInt()} blocks, ${c.guards} guards" + (if (c.wagons > 1) ", ${c.wagons} wagons" else "")))
        if (c.phase == Phase.PLANNED) out.append(grey(", sets out in ${seconds(c.setOut - t)}"))
        if (c.phase == Phase.WAITING) out.append(grey(", waiting at the start until ${clock(Caravans.clockOf(Math.floorDiv(c.until, 1000L)))}"))
        if (c.holder != null) out.append(grey(", escort ${c.holder?.let { level.server.playerList.getPlayer(it)?.name?.string } ?: "away"}" + if (c.met) " (met)" else ""))
        return out
    }

    private fun strikeLine(level: ServerLevel, data: CaravanData, c: CaravanRec): Component {
        val band = OutlawCamps.get(level).band(c.band)
        val out = line().append(grey("   "))
        if (band == null) return out.append(grey(if (c.band >= 0) "its band is gone" else "no band works its road"))
        out.append(grey("band: ")).append(named(band)).append(grey(" #${band.id}, camp ${c.reach.toInt()} off the road; "))
        when {
            c.struck == 2 -> out.append(plain(if (c.waylaid) "struck and waylaid" else "struck and beaten off"))
            c.struck == 1 -> out.append(plain("passed its strike point unstruck"))
            c.strike < 0 -> out.append(plain("won't strike it"))
            else -> {
                out.append(plain("strikes at ${c.strike.toInt()} blocks (${c.place})"))
                out.append(grey(" — chance now %.0f%%, waylaid %.0f%%".format(Caravans.chance(level, data, c, band, c.strike, c.met) * 100, Caravans.odds(data, c, band) * 100)))
                if (c.rigged != 0) out.append(grey(if (c.rigged > 0) ", rigged waylaid" else ", rigged beaten off"))
            }
        }
        return out
    }

    private fun spawn(context: CommandContext<CommandSourceStack>, road: String?): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val here = here(source) ?: return 0
        val leg = if (road == null) nearestLeg(here, player) else {
            val side = SIDES[road] ?: return fail(source, "No road called $road: north, east, south or west.")
            here.legs.firstOrNull { it.side.name == side } ?: return fail(source, "${here.name} has no road in by the $road gate: " + here.legs.joinToString(", ") { side(it) })
        }
        val c = Caravans.spawn(level, here.entry, leg)
        say(source, plain("Wagon #${c.id} sets out now by the ${side(leg)}, ${leg.length.toInt()} blocks, in at its stand in ${seconds(Caravans.ticksFor(leg.length))} of game time."))
        say(source, line().append(grey("   load: ")).append(plain(goods(c.load).ifEmpty { "nothing (the market wants nothing brought just now)" })))
        say(source, strikeLine(level, CaravanData.get(level), c))
        return 1
    }

    private fun ambush(context: CommandContext<CommandSourceStack>, which: String?, outcome: String?): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val here = here(source) ?: return 0
        if (!Caravans.ambushes()) return fail(source, "Road ambushes are switched off (settings: Road Ambushes, with Caravans and Outlaws).")
        val c = nearestCaravan(level, here, player) ?: return fail(source, "No caravan on its way into ${here.name}: `caravan spawn` sends one.")
        val leg = CaravanRoutes.cached(c.capital, c.road) ?: return fail(source, "Its road isn't worked out yet.")
        val camps = OutlawCamps.get(level)
        val data = CaravanData.get(level)
        val band: OutlawBand = when {
            which == null || which == "road" ->
                data.roadOrNull(c.capital, c.road)?.band?.takeIf { it >= 0 }?.let { camps.band(it) }?.takeIf { !it.slain }
                    ?: CaravanRoutes.band(level, leg)
                    ?: camps.band(c.band)?.takeIf { !it.slain }
                    ?: return fail(source, "No band works the ${side(leg)}: name one by its number, or `near` to make a camp by you.")
            which == "near" -> camps.createNear(level, player.blockPosition()) ?: return fail(source, "No ground near you suits a camp: try somewhere flatter.")
            else -> which.toIntOrNull()?.let { camps.band(it) }?.takeIf { !it.slain } ?: return fail(source, "No band at large numbered $which (see /guildmark outlaws).")
        }
        val waylaid = when (outcome) {
            null, "dice" -> null
            "waylaid" -> true
            "beaten" -> false
            else -> return fail(source, "The outcome is waylaid, beaten or dice.")
        }
        val why = Caravans.ambush(level, c, band, waylaid)
        if (why != null) return fail(source, "Wagon #${c.id} can't be struck: $why.")
        val t = level.overworldClockTime
        val ahead = (c.strike - Caravans.distance(c, t)).coerceAtLeast(0.0)
        say(source, line().append(plain("Wagon #${c.id} on the ${side(leg)} is struck by ")).append(named(band))
            .append(plain(" ${ahead.toInt()} blocks ahead (${c.place}), in about ${seconds(Caravans.ticksFor(ahead))}" + if (c.phase != Phase.ROLLING) ", once it sets out." else ".")))
        say(source, grey(when (waylaid) {
            true -> "It will be waylaid."
            false -> "It will be beaten off."
            null -> "The dice decide: waylaid %.0f%% of the time.".format(Caravans.odds(data, c, band) * 100)
        }))
        return 1
    }

    private fun arrive(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = here(source) ?: return 0
        val c = nearestCaravan(level, here, source.playerOrException) ?: return fail(source, "No caravan on its way into ${here.name}.")
        val load = goods(c.load)
        if (!Caravans.arriveNow(level, c)) return fail(source, "Wagon #${c.id} can't get in now (it's ${c.phase.id}).")
        return say(source, plain("Wagon #${c.id} is in at its stand: ${load.ifEmpty { "nothing" }} into the market."))
    }

    private fun wreck(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val here = here(source) ?: return 0
        if (!Caravans.ambushes()) return fail(source, "Road ambushes are switched off (settings: Road Ambushes, with Caravans and Outlaws).")
        val leg = nearestLeg(here, player)
        val camps = OutlawCamps.get(level)
        val band = CaravanRoutes.band(level, leg)
            ?: CaravanData.get(level).roadOrNull(here.capital, leg.road)?.band?.takeIf { it >= 0 }?.let { camps.band(it) }?.takeIf { !it.slain }
            ?: camps.createNear(level, player.blockPosition())
            ?: return fail(source, "No band works the ${side(leg)}, and no ground near you suits a camp for one.")
        val c = Caravans.wreckAt(level, here.entry, leg, band, player.position(), player.yRot)
        val data = CaravanData.get(level)
        say(source, line().append(plain("A wagon to ${here.name} waylaid here on the ${side(leg)} by ")).append(named(band))
            .append(plain(" (#${c.id}, ")).append(Component.translatable("road.guildmark.place.${c.place}").withStyle(ChatFormatting.WHITE)).append(plain(").")))
        val wreck = data.wrecks.lastOrNull { it.caravan == c.id }
        when {
            leg.road < 0 -> say(source, grey("That's the plan's own road, out of sight: no wreck lies on it."))
            wreck == null -> say(source, grey("No wreck: the wagon's goods are all lost."))
            wreck.lots.isEmpty() -> say(source, grey("The wreck holds nothing (Road Salvage is off, or the band took everything)."))
            else -> say(source, grey("The wreck's lots: " + wreck.lots.withIndex().joinToString(", ") { (k, lot) -> "[$k] ${lot.count} ${lot.good.id}" } + ". `caravan take` takes the next."))
        }
        data.drivers.lastOrNull { it.caravan == c.id }?.let { say(source, grey("Its driver runs for the gate, there in ${seconds(it.from - level.overworldClockTime)}.")) }
        return 1
    }

    private fun take(context: CommandContext<CommandSourceStack>, lot: Int): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!Caravans.salvage()) return fail(source, "Road salvage is switched off (settings: Road Salvage): a wreck is scenery.")
        val pos = player.blockPosition()
        val w = (Caravans.wrecks(level, pos, 8.0) + Caravans.piles(level, pos, 8.0)).minByOrNull { flat(it.pos.x - player.x, it.pos.z - player.z) }
            ?: return fail(source, "No wreck or loot pile within 8 blocks.")
        val k = if (lot >= 0) lot else w.lots.indexOfFirst { it != null }
        if (k < 0 || w.lots.getOrNull(k) == null) return fail(source, "That lot is gone. Left: " + w.lots.withIndex().filter { it.value != null }.joinToString(", ") { "[${it.index}] ${it.value!!.second} ${it.value!!.first.id}" }.ifEmpty { "nothing" })
        val stack = Caravans.take(player, w.id, k)
        if (stack.isEmpty) return fail(source, "Couldn't take it: stand closer.")
        val what = "${stack.count} ${stack.hoverName.string}"
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        return say(source, plain("Took lot [$k]: $what, marked as off the wagon. Hold it out to a keeper in its capital to hand it in, or keep it."))
    }

    private fun escort(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val here = here(source) ?: return 0
        if (!Caravans.escorts()) return fail(source, "Caravan escorts are switched off (settings: Caravan Escorts).")
        val leg = here.legs.filter { it.road >= 0 }.minByOrNull { it.distance(player.x, player.z) }
            ?: return fail(source, "${here.name}'s only way in is the plan's own road, out of sight: no escort notes go up for it.")
        val data = CaravanData.get(level)
        val hour = Markets.hourOf(level)
        if (!RoadPeril.stage(data, here.capital, leg.road).troubled) {
            RoadPeril.set(data, here.capital, leg.road, RoadPeril.Stage.TROUBLED.least, hour)
            data.road(here.capital, leg.road).struck = level.overworldClockTime
            say(source, grey("The ${side(leg)} was open: it's Troubled now (peril ${RoadPeril.Stage.TROUBLED.least}), as escort notes go up only then."))
        }
        val board = board(level, here.piece) ?: return fail(source, "Couldn't find ${here.name}'s quest board (is it loaded?).")
        val out = Caravans.nextWagon(level, here.capital, leg.road) ?: return fail(source, "No wagon falls to the ${side(leg)} in the next two days.")
        val note = CaravanEscorts.noteFor(level, here.capital, leg, board) ?: return fail(source, "No wagon falls to the ${side(leg)} in the next two days.")
        val stack = Contracts.take(note.copy(rank = 0), level, board, Direction.NORTH, player) ?: return 0
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        val start = leg.start
        return say(source, plain("You hold the escort contract for the ${side(leg)}: meet the wagon at ${start.x.toInt()} ${start.y.toInt()} ${start.z.toInt()} " +
            "(${flat(player.x - start.x, player.z - start.z).toInt()} blocks off) before it sets out at ${clock(Caravans.clockOf(Math.floorDiv(out, 1000L)))}."))
    }

    /** The cell of [piece]'s quest board its notes and contracts go by: its anchor, or the board block nearest it. */
    private fun board(level: ServerLevel, piece: CapitalPiece): BlockPos? {
        val anchor = piece.boardAnchor
        for (dy in intArrayOf(0, 1, 2, -1)) {
            val pos = anchor.above(dy)
            if (level.getBlockState(pos).block is NoticeBoardBlock && MarketFetch.at(level, pos) != null) return pos
        }
        return null
    }

    private fun driver(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val d = Caravans.driverTo(level, player.blockPosition()) ?: return fail(source, "No waylaid wagon's driver anywhere: `caravan wreck` makes one.")
        say(source, plain("The driver of wagon #${d.caravan} comes to you, and tells it: they took ${d.lost.joinToString(", ") { "${it.count} ${it.good.id}" }.ifEmpty { "nothing" }}."))
        val first = Caravans.told(player, d.caravan)
        val band = OutlawCamps.get(level).band(d.band)?.takeIf { !it.slain }
        return say(source, grey(when {
            band == null -> "The band that took it is gone."
            first -> "Its camp, at ${band.pos.x} ${band.pos.z}, is marked on your map."
            else -> "They've told you before: its camp is on your map already."
        }))
    }

    // ---- road ----

    private fun roads(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = here(source) ?: return 0
        val data = CaravanData.get(level)
        val t = level.overworldClockTime
        val camps = OutlawCamps.get(level)
        say(source, header("The roads into ${here.name}"))
        for (leg in here.legs) {
            val rec = data.roadOrNull(here.capital, leg.road)
            val peril = rec?.peril ?: 0
            val stage = RoadPeril.stage(peril)
            val band = rec?.band?.takeIf { it >= 0 }?.let { camps.band(it) } ?: CaravanRoutes.band(level, leg)
            val out = line().append(label("The ${side(leg)}")).append(grey(if (leg.road < 0) " (the plan's own, from the farms in)" else " (road ${leg.road}, ${leg.length.toInt()} blocks driven)"))
                .append(plain(": peril $peril, ${stage.id}"))
            if (rec != null && rec.struck > Long.MIN_VALUE / 4) out.append(grey(", last struck ${ago(t - rec.struck)} ago"))
            if (rec?.pending == true) out.append(grey(", no wagon in since"))
            say(source, out)
            val second = line().append(grey("   "))
            if (band == null) second.append(grey("no band works it")) else {
                second.append(grey("band: ")).append(named(band))
                    .append(grey(" #${band.id}${if (band.built) "" else " (camp not built yet)"}${if (band.named || band.built) ", bounty ${band.bounty}" else ""}, ${CaravanRoutes.reach(leg, band).toInt()} off the road"))
            }
            val due = Caravans.dueIn(level, here.capital, leg.road)
            second.append(grey(if (due != null) "; next wagon due in at ${clock(Caravans.clockOf(Math.floorDiv(due, 1000L)))}" else "; no wagon due by it in the next two days"))
            say(source, second)
        }
        // The road news the crier goes by: what, on which road, when, and whom it names (a band's captain, or the finders)
        val news = data.capitalOrNull(here.capital)?.news.orEmpty().takeLast(6)
        if (news.isNotEmpty()) {
            val out = line().append(label("Road news: "))
            news.forEachIndexed { k, n ->
                if (k > 0) out.append(grey("; "))
                out.append(grey("${n.kind} on ${here.legs.firstOrNull { it.road == n.road }?.let { side(it) } ?: "the road"} at ${clock(Caravans.clockOf(n.hour))}"))
                if (n.names.isNotEmpty()) {
                    out.append(grey(" ("))
                    n.names.forEachIndexed { j, name ->
                        if (j > 0) out.append(grey(", "))
                        // A band's names are lang keys; the finders of salvage are players' names as they are
                        out.append((if (name.startsWith("outlaw.guildmark.")) Component.translatable(name) else Component.literal(name)).withStyle(ChatFormatting.GRAY))
                    }
                    out.append(grey(")"))
                }
            }
            say(source, out)
        }
        return 1
    }

    /** [band] by its captain's name, once it has one. */
    private fun named(band: OutlawBand): Component =
        if (band.built || band.named) band.title().copy().withStyle(ChatFormatting.RED) else Component.literal("a band not named yet").withStyle(ChatFormatting.RED)

    private fun peril(context: CommandContext<CommandSourceStack>, peril: Int): Int {
        val source = context.source
        val level = source.level
        val here = here(source) ?: return 0
        val leg = nearestLeg(here, source.playerOrException)
        val data = CaravanData.get(level)
        val before = RoadPeril.stage(data, here.capital, leg.road)
        RoadPeril.set(data, here.capital, leg.road, peril, Markets.hourOf(level))
        // As if it had just been struck, so it doesn't fade by the next morning
        if (peril > 0) data.road(here.capital, leg.road).struck = level.overworldClockTime
        val after = RoadPeril.stage(data, here.capital, leg.road)
        return say(source, plain("The ${side(leg)}: peril $peril, ${after.id}" + if (after != before) " (was ${before.id}; the crier tells it at noon)." else "."))
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = here(source) ?: return 0
        val data = CaravanData.get(level)
        val hour = Markets.hourOf(level)
        for (leg in here.legs) {
            val rec = data.road(here.capital, leg.road)
            rec.pending = false
            RoadPeril.set(data, here.capital, leg.road, 0, hour)
            rec.band = -1
        }
        Caravans.overWaylaid(here.entry.market)
        data.changed()
        Markets.get(level).changed()
        return say(source, plain("Every road into ${here.name} is clear, and the waylaid wagons' trouble ends with the hour."))
    }

    // ---- Text ----

    /** [leg]'s road by its gate: "north road". */
    private fun side(leg: CaravanRoutes.Leg): String = (SIDES.entries.firstOrNull { it.value == leg.side.name }?.key ?: "") + " road"

    private fun goods(load: DoubleArray): String = Good.entries.filter { load[it.ordinal] >= 1.0 }.sortedByDescending { load[it.ordinal] }
        .joinToString(", ") { "${load[it.ordinal].toInt()} ${it.id}" }

    private fun clock(h: Int): String = "%02d:00".format(h)

    /** [ticks] of the clock as hours and minutes ("2 h 10 min"). */
    private fun ago(ticks: Long): String {
        val minutes = (ticks.coerceAtLeast(0L) * 60 / 1000)
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    /** [ticks] of game time as real seconds. */
    private fun seconds(ticks: Long): String {
        val s = ticks.coerceAtLeast(0L) / 20
        return if (s >= 120) "${s / 60} min ${s % 60} s" else "$s s"
    }

    private fun flat(dx: Double, dz: Double): Double = sqrt(dx * dx + dz * dz)

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
