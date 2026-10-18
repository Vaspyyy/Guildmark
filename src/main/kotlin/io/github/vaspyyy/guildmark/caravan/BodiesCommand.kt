package io.github.vaspyyy.guildmark.caravan

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRec
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import kotlin.math.max

/**
 * Testing commands for the caravans' bodies (Part B), added under `/guildmark caravan` beside Part A's: each sets a
 * wagon out through the same records as any, placed so the part to see happens by you.
 *
 * `caravan here` sets a wagon out on the road nearest you, 60 blocks up it, to roll past you (its strike, if it has
 * one behind that, passed by). `caravan fight [waylaid|beaten|dice]` sets one out to be struck about 20 blocks up the
 * road from you, by the road's band (a camp made near you if it has none), played out as the dice or the word say
 * unless you join in. `caravan stand` sets one out 70 blocks short of its stand, to pull in and unload. `caravan gate`
 * brings the last waylaid driver's body to the gate's place, by you, to be talked to. `caravan bodies` lists what's
 * out (`bodies clear` lets it all go).
 */
internal object BodiesCommand {
    /** How near one of a capital's roads you must stand to work on it from outside the capital (as Part A's). */
    private const val ROADS = 640.0

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("caravan")
                        .then(Commands.literal("here").executes { here(it) })
                        .then(
                            Commands.literal("fight")
                                .executes { fight(it, null) }
                                .then(
                                    Commands.argument("outcome", StringArgumentType.word())
                                        .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("waylaid", "beaten", "dice"), b) }
                                        .executes { fight(it, StringArgumentType.getString(it, "outcome")) },
                                ),
                        )
                        .then(Commands.literal("stand").executes { stand(it) })
                        .then(Commands.literal("gate").executes { gate(it) })
                        .then(
                            Commands.literal("bodies")
                                .executes { bodies(it) }
                                .then(Commands.literal("clear").executes { clear(it) }),
                        ),
                ),
        )
    }

    // ---- Where you are ----

    private class Road(val entry: Markets.Entry, val leg: CaravanRoutes.Leg, val name: String)

    /** The road nearest you of the capital you're in, or whose road you're on. */
    private fun road(source: CommandSourceStack): Road? {
        val level = source.level
        val player = source.playerOrException
        if (level.dimension() != Level.OVERWORLD) {
            fail(source, "The wagons run in the overworld.")
            return null
        }
        if (!Caravans.enabled()) {
            fail(source, "The wagons are switched off (settings: Caravans, with Capital Markets).")
            return null
        }
        val pos = player.blockPosition()
        val piece = Capitals.covering(level, pos) ?: nearest(level, pos)
        if (piece == null) {
            fail(source, "You're not in a capital, nor on a road into one that's been worked out (stand in the capital first).")
            return null
        }
        val entry = Markets.of(level, piece)
        if (entry == null) {
            fail(source, "Its market isn't open yet: try again in a second.")
            return null
        }
        val legs = CaravanRoutes.legs(level, piece.center.asLong())
        if (legs.isNullOrEmpty()) {
            fail(source, if (legs == null) "Its ways in are still being worked out: try again in a second." else "It has no way in for wagons.")
            return null
        }
        val leg = legs.minBy { it.distance(player.x, player.z) }
        return Road(entry, leg, VillageNames.of(piece.boardAnchor))
    }

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

    /** A wagon set out on [road] now, its middle [R] blocks along it; its own strike passed by if it lies behind. */
    private fun setOut(level: ServerLevel, road: Road, R: Double, strike: Boolean): CaravanRec? {
        val c = Caravans.spawn(level, road.entry, road.leg)
        if (c.phase != Phase.ROLLING) return c
        val t = level.overworldClockTime
        val at = R.coerceIn(0.0, c.length - 8.0)
        c.setOut = t - Caravans.ticksFor(at)
        c.held = 0L
        if (!strike || c.strike >= 0.0 && c.strike <= at + 6.0) {
            if (c.struck == 0) c.struck = 1
        }
        CaravanData.get(level).changed()
        return c
    }

    // ---- The commands ----

    private fun here(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val road = road(source) ?: return 0
        val along = road.leg.nearest(player.x, player.z)
        val c = setOut(level, road, max(0.0, along - 60.0), strike = true) ?: return 0
        if (c.phase == Phase.WAITING) return say(source, grey("Wagon #${c.id} waits at the start of the road for its escort (an escort contract is out for it)."))
        say(source, plain("Wagon #${c.id} to ${road.name} is on the road ${max(0.0, along - 60.0).toInt()} blocks along, coming your way."))
        val strike = Caravans.view(c.id)?.strike
        say(source, grey(if (strike == null) "No strike ahead of it." else "Its strike lies ${(strike - along).toInt()} blocks on from you (${c.place})."))
        return 1
    }

    private fun fight(context: CommandContext<CommandSourceStack>, outcome: String?): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (!Caravans.ambushes()) return fail(source, "Road ambushes are switched off (settings: Road Ambushes, with Caravans and Outlaws).")
        val waylaid = when (outcome) {
            null, "dice" -> null
            "waylaid" -> true
            "beaten" -> false
            else -> return fail(source, "The outcome is waylaid, beaten or dice.")
        }
        val road = road(source) ?: return 0
        val leg = road.leg
        val camps = OutlawCamps.get(level)
        val data = CaravanData.get(level)
        val band: OutlawBand = CaravanRoutes.band(level, leg)
            ?: data.roadOrNull(road.entry.market.capital, leg.road)?.band?.takeIf { it >= 0 }?.let { camps.band(it) }?.takeIf { !it.slain }
            ?: camps.createNear(level, player.blockPosition())
            ?: return fail(source, "No band works this road, and no ground near you suits a camp for one.")
        val along = leg.nearest(player.x, player.z)
        // The records strike 12 blocks on from where it is: about 20 short of you
        val c = setOut(level, road, max(0.0, along - 32.0), strike = false) ?: return 0
        c.strike = -1.0
        val why = Caravans.ambush(level, c, band, waylaid)
        if (why != null) return fail(source, "Wagon #${c.id} can't be struck: $why.")
        say(source, plain("Wagon #${c.id} comes up the road toward you, to be struck about ${(along - c.strike).toInt()} blocks short of you (${c.place}), by ").append(band.title().copy().withStyle(ChatFormatting.RED)).append(plain(".")))
        say(source, grey(when (waylaid) {
            true -> "It will be waylaid, unless you join in: hit one of the band and the fight is yours."
            false -> "It will be beaten off, unless you join in: hit one of the band and the fight is yours."
            null -> "The dice decide, unless you join in: hit one of the band and the fight is yours."
        }))
        return 1
    }

    private fun stand(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val road = road(source) ?: return 0
        val c = setOut(level, road, road.leg.length - 70.0, strike = false) ?: return 0
        if (c.phase == Phase.WAITING) return say(source, grey("Wagon #${c.id} waits at the start of the road for its escort."))
        val stand = road.leg.stand
        return say(source, plain("Wagon #${c.id} is 70 blocks short of its stand in ${road.name} (at ${stand.x.toInt()} ${stand.y.toInt()} ${stand.z.toInt()}), coming in to unload."))
    }

    private fun gate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        if (level.dimension() != Level.OVERWORLD) return fail(source, "The wagons run in the overworld.")
        val ahead = player.position().add(player.lookAngle.multiply(1.0, 0.0, 1.0).normalize().scale(4.0))
        val rec = Caravans.driverTo(level, BlockPos.containing(ahead)) ?: return fail(source, "No waylaid wagon's driver anywhere: `caravan fight waylaid` or `caravan wreck` makes one.")
        val d = Caravans.drivers(level, rec.at, 2.0).firstOrNull { it.caravan == rec.caravan } ?: return fail(source, "Couldn't bring them.")
        if (!Bodies.gateNow(level, d)) return fail(source, "Couldn't bring them there (no ground?).")
        return say(source, plain("The driver of wagon #${d.caravan} is here, shaken. Speak to them."))
    }

    private fun bodies(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val shows = Bodies.shows()
        if (shows.isEmpty()) say(source, grey("No caravan's bodies are out (they come within 128 blocks of a player, never within 48 of one)."))
        for (s in shows) say(source, plain(s.describe()))
        return say(source, grey(Bodies.summary()))
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        Bodies.clear(quiet = false)
        return say(context.source, plain("Every caravan body let go; they come again as you near them."))
    }

    private fun plain(s: String) = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String) = Component.literal(s).withStyle(ChatFormatting.GRAY)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
