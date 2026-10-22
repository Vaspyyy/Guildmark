package io.github.vaspyyy.guildmark.cooking

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.cooking.recipe.CookXp
import io.github.vaspyyy.guildmark.cooking.vessel.Stations
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.math.roundToInt

/**
 * Testing commands for Hearthcraft (operators only; living-world/cook/design.md 10.3): `/guildmark cook` is the root of
 * the whole tree and says where things stand: Hearthcraft and each of its switches, your COOKING level and today's XP
 * against the soft cap, the station you are looking at in a line, and the kitchen of the capital you stand in in a line.
 * Every other part adds its own branch under `cook` in its own file (Brigadier merges nodes of the same name, so none
 * edits another's), registered from its own `register()`.
 */
object CookCommand {
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { status(it) },
                ),
        )
    }

    // ---- Where things stand ----

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        say(source, header("Hearthcraft"))
        val master = Cooking.enabled()
        say(source, line().append(label("Hearthcraft: ")).append(onOff(master)))
        if (!master) say(source, grey("  The stations stand and say so; nothing is cooked, sold or judged, and every switch below reads off."))
        say(
            source,
            line().append(label("Switches: ")).append(
                plain(
                    listOf(
                        "back doors" to Cooking.doors(), "cookshops" to Cooking.shops(), "harvest table" to Cooking.cookOff(),
                        "homestead kitchens" to Cooking.homeKitchens(), "town kitchens" to Cooking.townKitchens(), "rest-day roast" to Cooking.roast(),
                        "kitchen talk" to Cooking.talk(), "winter chill" to Cooking.winterChill(), "dishes go stale" to Cooking.spoilage(),
                        "private pots" to Cooking.potsPrivate(),
                    ).joinToString(", ") { (name, on) -> "$name ${if (on) "on" else "off"}" } + ", fuel goes ${"%.1f".format(Cooking.fuelScale())}x further",
                ),
            ),
        )
        val player = source.player ?: return 1
        cook(source, player)
        return 1
    }

    /** The lines about the player at the command: their level and XP, the station in view, and the capital they stand in. */
    private fun cook(source: CommandSourceStack, player: ServerPlayer) {
        val level = source.level
        val today = Cooking.today(level)
        val xp = player.getData(CookRegistry.COOK_XP_DAY).let { if (it.day == today) it.xp else 0 }
        say(
            source,
            line().append(label("You: ")).append(
                plain("COOKING level ${Skills.level(player, Skill.COOKING)}, $xp XP today of ${CookXp.COOK_XP_SOFT_CAP} at full rate (the rest at ${(CookXp.COOK_XP_PAST_CAP * 100).roundToInt()}%)"),
            ),
        )
        val station = Stations.lookedAt(player)
        if (station == null) {
            say(source, line().append(label("Station: ")).append(grey("none in view")))
        } else {
            val v = station.view()
            val what = station.station?.name?.lowercase() ?: "prep board"
            val state = STATES.getOrElse(v.state) { "state ${v.state}" }
            say(source, line().append(label("Station: ")).append(plain("$what, $state, heat ${v.notch}, ${if (v.lit) "lit" else "cold"}${if (v.burnt) ", burnt" else ""}, ${v.items.size} in it")))
        }
        val capital = Capitals.covering(level, player.blockPosition())
        if (capital == null) {
            say(source, line().append(label("Kitchen: ")).append(grey("not in a capital")))
            return
        }
        val place = Suppliers.place(level, capital.center).ifEmpty { "the capital at ${capital.center.x}, ${capital.center.z}" }
        val key = capital.center.asLong()
        val ledger = Kitchens.get(level)
        val lots = Door.entries.joinToString(", ") { "${it.name.lowercase()} ${ledger.lots(key, it).sumOf { lot -> lot.portions }}" }
        val wanted = ledger.wanted(level, key, today).joinToString(", ") { it.id }.ifEmpty { "none" }
        val request = ledger.request(level, key, today)?.let { "${it.portions} ${it.kind.id}${if (it.filled) " (filled)" else ""}" } ?: "none"
        say(source, line().append(label("Kitchen of $place: ")).append(plain("wanted $wanted; request $request; portions waiting $lots")))
    }

    // ---- Words ----

    /** The names of `Vessel.State` (Appendix C.2), by the ordinal a [io.github.vaspyyy.guildmark.cooking.vessel.StationView] sends. */
    private val STATES = listOf("empty", "filling", "heating", "cooking", "ready", "done", "burnt", "served")

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
    private fun onOff(on: Boolean): MutableComponent = Component.literal(if (on) "on" else "off").withStyle(if (on) ChatFormatting.GREEN else ChatFormatting.RED)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }
}
