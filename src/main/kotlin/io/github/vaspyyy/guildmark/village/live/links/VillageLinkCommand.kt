package io.github.vaspyyy.guildmark.village.live.links

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.village.live.Link
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
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
 * `/guildmark village links [refresh]` (the villages design, section 15.2), run by operators in a village, or beside one: the
 * capitals whose wagon roads end here, each with its road's stage, the leg's length, whether its wagons run yet and when the next
 * one sets out; with none, the roads that do come here and where they lead. `refresh` works every village's links out again now.
 * `village info` gets the links in a line.
 *
 * The words are the plain English of the other `village` commands. Each ends in plain words: what a number is and where it came from.
 */
object VillageLinkCommand {
    /** How far (blocks) the nearest village is looked for when the sender is not in one. */
    private const val NEAREST = 128

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
                        ),
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
                    "The links are worked out once a day and whenever a road is laid or finished; 'refresh' works them out now.",
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
        return out
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
