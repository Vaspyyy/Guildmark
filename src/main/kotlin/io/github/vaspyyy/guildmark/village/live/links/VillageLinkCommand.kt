package io.github.vaspyyy.guildmark.village.live.links

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.village.live.Link
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster.NONE
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
import kotlin.math.sqrt

/**
 * `/guildmark village links [refresh]` and `/guildmark village cart wagon` (the villages design, section 15.2), run by operators in
 * a village, or beside one:
 *
 * - `links`: the capitals whose wagon roads end here, each with its road's stage, the leg's length, whether its wagons run yet and
 *   when the next one sets out; with none, the roads that do come here and where they lead. Then the village's wagons: whether they
 *   are its supply, when one last came in, a relief owed, the last trips (what each took and brought, how it ended), the news they
 *   brought, and what its people are at by the road now. `refresh` works every village's links out again now.
 * - `cart wagon` runs the wagon's hooks now: one of the village's wagons on its trip brings the village its goods now (as one
 *   called back does: its record goes on for the capital alone); with none out, a wagon's round trip is made in the record (the
 *   village's glut out, the capital's goods and news in, no capital's record touched). With a player near, the carters go out to
 *   the road end to meet it.
 *
 * `village info` gets the links in a line.
 *
 * The words are the plain English of the other `village` commands. Each ends in plain words: what a number is and where it came from.
 */
object VillageLinkCommand {
    /** How far (blocks) the nearest village is looked for when the sender is not in one. */
    private const val NEAREST = 128

    /** Trips shown by `village links`. */
    private const val TRIPS_SHOWN = 4

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        VillageInfoCommand.section { level, rec -> lines(level, rec) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village")
                        .then(
                            Commands.literal("links").executes { show(it) }
                                .then(Commands.literal("refresh").executes { refresh(it) }),
                        )
                        // `village cart` itself is V4's (VillageCartCommand): the node is merged with its own
                        .then(Commands.literal("cart").then(Commands.literal("wagon").executes { wagon(it) })),
                ),
        )
    }

    // ---- The commands ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec) = target(source) ?: return 0
        say(source, header("The roads of ${rec.name}"))
        for (c in report(level, rec)) say(source, c)
        say(
            source,
            grey(
                "A village is linked to a capital when one of its wagon roads ends within ${VillageLinks.NEAR} blocks of the village's bell. " +
                    "The links are worked out once a day and whenever a road is laid or finished; 'refresh' works them out now. " +
                    "'/guildmark village cart wagon' runs a wagon's call now.",
            ),
        )
        return 1
    }

    private fun refresh(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec) = target(source) ?: return 0
        val before = rec.links.toList()
        VillageLinks.refresh(level)
        val after = rec.links.toList()
        val linked = Villages.all(level).count { it.links.isNotEmpty() }
        say(source, line().append(label("Links worked out again: ")).append(plain("$linked village${if (linked == 1) "" else "s"} linked to a capital")))
        say(
            source,
            line().append(label("${rec.name}: ")).append(
                plain(
                    when {
                        before == after -> "${after.size} link${if (after.size == 1) "" else "s"}, as before"
                        else -> "${before.size} link${if (before.size == 1) "" else "s"} before, ${after.size} now"
                    },
                ),
            ),
        )
        return 1
    }

    private fun wagon(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec) = target(source) ?: return 0
        val links = VillageLinks.running(level, rec.key)
        if (links.isEmpty()) {
            return fail(
                source,
                if (VillageLinks.linked(level, rec.key).isEmpty()) "No capital's road ends at ${rec.name} (see /guildmark village links): the pedlar is its only cart."
                else "No wagon runs to ${rec.name} yet: its capital's market is not open, or its wagons' way is not worked out (see /guildmark village links).",
            )
        }
        if (VillageMarkets.rec(level, rec.key) == null) return fail(source, "${rec.name} has no market yet, so no wagon calls.")
        val on = CaravanData.get(level).caravans.filter { it.village == rec.key && (it.phase == Phase.ROLLING || it.phase == Phase.STANDING || it.phase == Phase.LEAVING) }
            .minByOrNull { it.setOut }
        val trip = (if (on != null) VillageWagons.bringIn(level, on) else VillageWagons.trial(level, rec, links.first()))
            ?: return fail(source, "No wagon could call on ${rec.name} just now.")
        val from = name(level, trip.capital)
        if (on != null) {
            say(source, line().append(label("Wagon #${on.id} of $from brought in now: ")).append(plain("it took ${goods(trip.gave)} as it set out, and brings ${goods(trip.brought)}${own(trip)}")))
            say(source, grey("(as a wagon called back brings it, from the capital's surplus and the supply from afar; its record goes on for $from alone and brings the village nothing more)"))
        } else {
            say(source, line().append(label("A wagon's call on ${rec.name} now, from $from by road ${trip.road}: ")).append(plain("it takes ${goods(trip.gave)} and brings ${goods(trip.brought)}")))
            say(source, grey("(in the record alone: all of the village's glut goes, as no capital's load bounds it, and what comes is the supply from afar a wagon brings; no capital's wagon is touched)"))
        }
        val news = VillageNews.heard(level, rec.key).size
        val scene = SendOff.meet(level, rec.key, trip.caravan)
        say(
            source,
            grey(
                "The goods leave and reach the village's market at its next hour; it has $news piece${if (news == 1) "" else "s"} of news not yet cried. " +
                    if (scene != null) "Its carters go out to the road end to meet the wagon." else "Nobody goes out to meet it (that needs a player within ${SendOff.PLAYER.toInt()} blocks of the bell, the road end loaded and a carter free).",
            ),
        )
        return 1
    }

    // ---- What is said ----

    /** The links' lines for `village links`. */
    private fun report(level: ServerLevel, rec: VillageRec): List<Component> {
        val out = ArrayList<Component>()
        val links = VillageLinks.linked(level, rec.key)
        if (links.isEmpty()) {
            out += line().append(label("Capitals: ")).append(plain("none: the pedlar is the village's only cart"))
            for (c in roads(level, rec)) out += c
            return out
        }
        for (link in links) out += link(level, link)
        out += wagons(level, rec)
        return out
    }

    /** The village's wagons: whether they are its supply, the last in, a relief owed, the last trips, the news, and the road end now. */
    private fun wagons(level: ServerLevel, rec: VillageRec): List<Component> {
        val out = ArrayList<Component>()
        val day = Days.dayOf(level.overworldClockTime)
        val supply = when {
            VillageWagons.shut(level, rec) -> "every road is Closed: no wagon goes but with an escort (it takes nothing), and the pedlar does not come either"
            VillageWagons.serves(level, rec, day) -> "the wagons (a road is open, or one came in within ${PedlarRoster.WAGON_DAYS} days): the pedlar comes on market day only"
            else -> "the pedlar on their round: no road is open and no wagon came in within ${PedlarRoster.WAGON_DAYS} days"
        }
        out += line().append(label("Supply: ")).append(plain(supply))
        val book = VillageWagons.book(level, rec.key)
        if (book == null) {
            out += line().append(label("Wagons: ")).append(plain("none has called yet"))
            return out
        }
        val last = if (book.lastIn == NONE) "none has come in with the capital's goods yet" else "the last came in on ${at(book.lastIn)}"
        out += line().append(label("Wagons: ")).append(plain(last + if (book.relief) "; a relief is owed (a band on its road was broken): the next brings ${VillageWagons.RELIEF_TIMES} caravans' share" else ""))
        for (t in book.trips.takeLast(TRIPS_SHOWN).reversed()) out += trip(level, t)
        val heard = VillageNews.heard(level, rec.key)
        if (heard.isNotEmpty()) {
            val by = heard.groupingBy { it.by }.eachCount().entries.joinToString(", ") { "${it.value} by the ${it.key}" }
            out += line().append(label("News: ")).append(plain("${heard.size} piece${if (heard.size == 1) "" else "s"} not yet cried ($by)"))
        }
        val doing = SendOff.doing(rec.key) ?: Greeters.out(rec.key).takeIf { it > 0 }?.let { "$it of its folk are out at the road end" }
        if (doing != null) out += line().append(label("At the road end: ")).append(plain(doing))
        return out
    }

    /** One trip of the book: which wagon, when, what it took and brought, and how it ended. */
    private fun trip(level: ServerLevel, t: VillageWagons.Trip): Component {
        val who = if (t.caravan == VillageWagons.TRIAL) "A call by command" else "Wagon #${t.caravan}"
        val text = StringBuilder("from ${name(level, t.capital)} by road ${t.road}, set out ${at(t.out)} taking ${goods(t.gave)}")
        when (t.end) {
            VillageWagons.OUT -> text.append("; still out")
            VillageWagons.HOME -> text.append("; back ${at(t.back)} with ${goods(t.brought)}${own(t)}")
            VillageWagons.CALLED -> text.append("; called back ${at(t.back)} with ${goods(t.brought)}${own(t)}")
            VillageWagons.WAYLAID -> text.append("; waylaid${if (t.place.isNotEmpty()) " (${t.place})" else ""}: the village's goods went with it and it brought nothing")
            VillageWagons.WITHDRAWN -> text.append("; never set out (a Closed road's wagon nobody came to escort)")
        }
        return line().append(label("  $who: ")).append(plain(text.toString()))
    }

    /** " (n of it the capital's own surplus)" for a trip that brought some of it. */
    private fun own(t: VillageWagons.Trip): String = if (t.fromCapital.isEmpty()) "" else " (${goods(t.fromCapital)} of it the capital's own surplus)"

    private fun goods(map: Map<String, Double>): String =
        if (map.isEmpty()) "nothing" else map.entries.sortedByDescending { it.value }.joinToString(", ") { "%.1f %s".format(it.value, it.key) }

    /** Market hour [hour] as "day D at HH:00". */
    private fun at(hour: Long): String {
        val c = Conditions(hour)
        return "day ${c.day} at ${"%02d".format(c.clock)}:00"
    }

    /** One link: the capital, the road's stage, the leg, and its wagons. */
    private fun link(level: ServerLevel, link: Link): Component {
        val data = CaravanData.get(level)
        val stage = RoadPeril.stage(data, link.capital, link.road)
        val peril = data.roadOrNull(link.capital, link.road)?.peril ?: 0
        val leg = CaravanRoutes.cached(link.capital, link.road)
        val open = Markets.get(level)[BlockPos.of(link.capital)] != null
        val text = StringBuilder()
        text.append("road ${link.road}, ${stage.id} (peril $peril of ${RoadPeril.MOST}); the leg is ${link.length} blocks")
        when {
            !open -> text.append("; its market is not open, so no wagon comes")
            leg == null -> text.append("; its wagons' way is not worked out yet (it is once the capital's wagons ask for it)")
            else -> Caravans.nextWagon(level, link.capital, link.road)?.let { t ->
                val c = Conditions(Math.floorDiv(t, 1000L))
                text.append("; the next wagon sets out from the road end on day ${c.day} at ${"%02d".format(c.clock)}:00")
            } ?: text.append("; no wagon falls to this road in the next two days")
        }
        return line().append(label("${name(level, link.capital)}: ")).append(plain(text.toString()))
    }

    /** For an unlinked village, the roads that come to it and where they lead (9.6). */
    private fun roads(level: ServerLevel, rec: VillageRec): List<Component> {
        val bell = BlockPos.of(rec.key)
        val roads = RoadNetwork.get(level.server.overworld()).roads.filter { it.touches(bell, VillageLinks.NEAR.toDouble()) }.sortedBy { it.id }
        if (roads.isEmpty()) return listOf(line().append(label("Roads: ")).append(plain("none come here yet (a Trade Road built from here may join a capital's)")))
        val capitals = Markets.get(level).all().map { it.market.capital }
        return roads.map { road ->
            val far = road.otherEnd(bell)
            val capital = capitals.firstOrNull { flat(BlockPos.of(it), far) <= VillageLinks.NEAR }
            val other = Villages.near(level, far, VillageLinks.NEAR).firstOrNull()
            val to = when {
                capital != null -> "to ${name(level, capital)}, a capital (linked at the next look, once its wagons have worked their way out)"
                other != null -> "to ${other.name}, a village"
                else -> "to ${far.x}, ${far.z}"
            }
            line().append(label("Road ${road.id}: ")).append(plain(to + if (road.finished) "" else ", not finished"))
        }
    }

    /** The lines `village info` adds: the links in one. */
    private fun lines(level: ServerLevel, rec: VillageRec): List<Component> {
        if (!VillageLinks.enabled()) return emptyList()
        val links = VillageLinks.linked(level, rec.key)
        if (links.isEmpty()) return listOf(line().append(label("Links: ")).append(plain("none (the pedlar is its only cart)")))
        val data = CaravanData.get(level)
        val text = links.joinToString("; ") { "${name(level, it.capital)} by road ${it.road} (${RoadPeril.stage(data, it.capital, it.road).id})" }
        return listOf(line().append(label("Links: ")).append(plain(text)))
    }

    /** A capital by the name its wagons know it by, else where its heart is. */
    private fun name(level: ServerLevel, capital: Long): String =
        CaravanData.get(level).capitalOrNull(capital)?.name?.takeIf { it.isNotEmpty() } ?: BlockPos.of(capital).let { "the capital at ${it.x}, ${it.z}" }

    private fun flat(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    // ---- Which village ----

    /** The village the sender stands in, or the nearest bell within [NEAREST] blocks; null, having said so, if there is none. */
    private fun target(source: CommandSourceStack): Pair<ServerLevel, VillageRec>? {
        val level = source.level
        if (level.dimension() != Level.OVERWORLD) {
            fail(source, "Villages are counted in the overworld only.")
            return null
        }
        if (!VillageLinks.enabled()) {
            fail(source, "Villages and the capitals' wagons are apart just now (settings: Capital Markets, Capital Caravans, Living Villages, Village Markets and Village Wagons are all needed).")
            return null
        }
        val here = BlockPos.containing(source.position)
        val rec = Villages.at(level, here) ?: Villages.near(level, here, NEAREST).firstOrNull()
        if (rec == null) {
            fail(source, "No village within $NEAREST blocks.")
            return null
        }
        return level to rec
    }

    // ---- Words ----

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
