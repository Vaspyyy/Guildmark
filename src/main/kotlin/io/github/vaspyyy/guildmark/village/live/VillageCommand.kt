package io.github.vaspyyy.guildmark.village.live

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.GuildmarkConfig
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.ModConfigSpec
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * The testing commands for living villages, run by operators (living-world/villages/design.md section 15.2). `/guildmark
 * village info` says whether Living Villages is on and which villages are counted, then which of its switches are on and
 * off now. This is the root: each later part adds its own commands under `village` from its own file (Brigadier merges
 * the repeated `guildmark` and `village`), and the part that counts villages replaces this `info` with its own.
 */
object VillageCommand {
    /** The switches that are on or off, with the line each is named by in the settings. */
    private val SWITCHES: List<Pair<String, ModConfigSpec.BooleanValue>> = listOf(
        "guildmark.configuration.villageFolk" to GuildmarkConfig.VILLAGE_FOLK,
        "guildmark.configuration.villageMarkets" to GuildmarkConfig.VILLAGE_MARKETS,
        "guildmark.configuration.villagePedlar" to GuildmarkConfig.VILLAGE_PEDLAR,
        "guildmark.configuration.villageWagons" to GuildmarkConfig.VILLAGE_WAGONS,
        "guildmark.configuration.villageFortunes" to GuildmarkConfig.VILLAGE_FORTUNES,
        "guildmark.configuration.villageBellman" to GuildmarkConfig.VILLAGE_BELLMAN,
        "guildmark.configuration.villageLamps" to GuildmarkConfig.VILLAGE_LAMPS,
        "guildmark.configuration.villageDressing" to GuildmarkConfig.VILLAGE_DRESSING,
        "guildmark.configuration.villageTrouble" to GuildmarkConfig.VILLAGE_TROUBLE,
        "guildmark.configuration.villageBuilds" to GuildmarkConfig.VILLAGE_BUILDS,
        "guildmark.configuration.villageGrowth" to GuildmarkConfig.VILLAGE_GROWTH,
        "guildmark.configuration.villageGolems" to GuildmarkConfig.VILLAGE_GOLEMS,
    )

    /** Registers the commands; called once from Guildmark. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village")
                        .then(Commands.literal("info").executes { info(it) }),
                ),
        )
    }

    // ---- info ----

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val on = flag(GuildmarkConfig.LIVING_VILLAGES)
        // Nothing counts villages yet: the registry is the next part's
        say(source, line().append(named("guildmark.configuration.livingVillages")).append(plain(if (on) " is on; no village counted yet" else " is off; no village is counted while it is")))
        say(source, line().append(label("On: ")).append(names(SWITCHES.filter { flag(it.second) })))
        say(source, line().append(label("Off: ")).append(names(SWITCHES.filter { !flag(it.second) })))
        say(
            source,
            line().append(named("guildmark.configuration.villageBuildSpeed")).append(plain(" " + trim(number(GuildmarkConfig.VILLAGE_BUILD_SPEED)) + "x"))
                .append(plain("  ")).append(named("guildmark.configuration.maxLiveVillages")).append(plain(" ${number(GuildmarkConfig.MAX_LIVE_VILLAGES).toInt()}"))
                .append(plain("  ")).append(named("guildmark.configuration.maxVillageFolk")).append(plain(" ${number(GuildmarkConfig.MAX_VILLAGE_FOLK).toInt()}")),
        )
        return 1
    }

    // ---- Words ----

    /** [switches]' names as the settings give them, or "none". */
    private fun names(switches: List<Pair<String, ModConfigSpec.BooleanValue>>): MutableComponent {
        if (switches.isEmpty()) return plain("none")
        val out = Component.empty()
        switches.forEachIndexed { i, s ->
            if (i > 0) out.append(plain(", "))
            out.append(Component.translatable(s.first).withStyle(ChatFormatting.WHITE))
        }
        return out
    }

    /** A switch's value now; what it starts as if the settings aren't loaded yet. */
    private fun flag(value: ModConfigSpec.BooleanValue): Boolean = try {
        value.get()
    } catch (e: IllegalStateException) {
        value.default
    }

    /** A number setting's value now, or its start. */
    private fun number(value: ModConfigSpec.ConfigValue<out Number>): Double = try {
        value.get().toDouble()
    } catch (e: IllegalStateException) {
        value.default.toDouble()
    }

    private fun trim(x: Double): String = if (x == Math.rint(x)) x.toLong().toString() else x.toString()

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)

    /** A setting by the name the settings screen gives it (its language line). */
    private fun named(key: String): MutableComponent = Component.translatable(key).withStyle(ChatFormatting.YELLOW)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }
}
