package io.github.vaspyyy.guildmark.village.live

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.lamps.LampCommand
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.townsfolk.streets.Dressing
import io.github.vaspyyy.guildmark.townsfolk.streets.Streets
import io.github.vaspyyy.guildmark.townsfolk.streets.StreetsData
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * The commands for a village's good times (living-world/villages/design.md 15.2, part V8b), run by operators under `/guildmark village`
 * on the village you are in (or the nearest within [NEAREST] blocks):
 *
 * - `dress` puts the dressing up as in good times, whatever the times say, for the session: garlands over the doors of the homes nearest
 *   the bell, pennants from the bell to the lamp posts, a flower pot on each step. The village's folk fetch their ladders and go round
 *   (a village with no folk of its own has it appear, as it would out of sight);
 * - `dress thriving` the same with the lantern strings between the lamp posts too, lit as the lamps are lit;
 * - `dress down` has it taken down, the same way;
 * - `dress clear` lets the command's dressing go and puts every piece of it back at once.
 *
 * `village info` ends with a line on the dressing and what it waits on. A village's dressing is looked at while a player is within 96
 * blocks of its bell, so the village has to be live.
 */
object VillageDressCommand {
    private const val NEAREST = 128

    /** How far round the bell the dressing's records are looked for to clear them. */
    private const val RECORDS = 64

    /** At setup: the commands, and the line this part adds to `village info`. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        VillageInfoCommand.section(VillageInfoCommand.Section { level, rec -> lines(level, rec) })
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village").then(
                        Commands.literal("dress")
                            .executes { dress(it, StreetsData.FORCE_GOOD) }
                            .then(Commands.literal("thriving").executes { dress(it, StreetsData.FORCE_THRIVING) })
                            .then(Commands.literal("down").executes { dress(it, StreetsData.FORCE_DOWN) })
                            .then(Commands.literal("clear").executes { clear(it) }),
                    ),
                ),
        )
    }

    // ---- The village you mean ----

    /** The village's record and places, or null having said why not. */
    private fun target(context: CommandContext<CommandSourceStack>): Pair<VillageRec, TownPlaces>? {
        val source = context.source
        val level = source.level
        if (!VillageDressing.working()) {
            LampCommand.fail(source, "Village dressing is off (setting: Living Villages or Village Dressing): none is put up, and what is up comes down. Switch it on first.")
            return null
        }
        val pos = BlockPos.containing(source.position)
        val rec = Villages.at(level, pos) ?: Villages.near(level, pos, NEAREST).firstOrNull()
        if (rec == null) {
            LampCommand.fail(source, "No village within $NEAREST blocks.")
            return null
        }
        if (rec.state != Live.LIVE) {
            LampCommand.fail(source, "${rec.name} is ${rec.state.name.lowercase()}: a village's dressing is looked at while a player is within 96 blocks of its bell. Go nearer.")
            return null
        }
        val places = Villages.places(level, rec.key)
        if (places == null) {
            LampCommand.fail(source, "${rec.name} has not been counted yet (`village census now` counts it).")
            return null
        }
        return rec to places
    }

    // ---- dress ----

    private fun dress(context: CommandContext<CommandSourceStack>, how: Int): Int {
        val (rec, places) = target(context) ?: return 0
        val source = context.source
        val level = source.level
        val pieces = VillageDressing.lay(level, places, Streets.column(rec.key))
            ?: return LampCommand.fail(source, "${rec.name}'s ground by the bell is not loaded: go nearer.")
        if (how != StreetsData.FORCE_DOWN && pieces.isEmpty()) {
            return LampCommand.fail(source, "${rec.name} has nowhere to hang anything: no home with a front door and room for a ladder beside it, and no climbable lamp post within ${VillageDressing.PENNANT_REACH.toInt()} blocks of the bell.")
        }
        val run = Dressing.force(level, Streets.column(rec.key), how, village = true)
        val folk = VillageLamps.folk(level, rec.key)
        val count = pieces.groupingBy { it.kind }.eachCount()
        val garlands = count[Dressing.Kind.GARLAND] ?: 0
        val pots = count[Dressing.Kind.POT] ?: 0
        val pennants = count[Dressing.Kind.BUNTING] ?: 0
        val strings = count[Dressing.Kind.LANTERNS] ?: 0
        val what = when (how) {
            StreetsData.FORCE_GOOD -> "${rec.name} dresses as in good times: $garlands garlands over doors, $pots flower pots on steps, $pennants pennants from the bell to the lamp posts."
            StreetsData.FORCE_THRIVING -> "${rec.name} dresses as in thriving times: $garlands garlands over doors, $pots flower pots on steps, $pennants pennants from the bell to the lamp posts, and $strings lantern strings between the lamp posts, lit as the lamps are lit."
            else -> "${rec.name}'s dressing comes down: its folk go round and take it down; out of sight it simply goes."
        }
        LampCommand.say(source, LampCommand.line().append(LampCommand.plain(what)).append(LampCommand.grey(" (Dressing #$run; for this session only.)")))
        if (how != StreetsData.FORCE_DOWN) {
            LampCommand.say(
                source,
                LampCommand.grey(
                    if (folk) "Its folk fetch their ladders and go round in a second or two, three at a time; a home with nobody living in it is left bare."
                    else "${rec.name} has no folk of its own (Village Folk is off, or it has not been adopted): nobody to hang it, so it appears at once.",
                ),
            )
        }
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val (rec, _) = target(context) ?: return 0
        val n = Dressing.clear(context.source.level, Streets.column(rec.key), BlockPos.of(rec.key), RECORDS)
        return LampCommand.say(context.source, LampCommand.plain("Cleared ${rec.name}'s dressing at once: $n pieces put back as they were."))
    }

    // ---- village info ----

    /** The line `village info` ends this village's with: its dressing, and what it waits on. */
    private fun lines(level: ServerLevel, rec: VillageRec): List<Component> {
        val label = LampCommand.label("Dressing: ")
        if (!VillageDressing.working()) {
            return listOf(LampCommand.line().append(label).append(LampCommand.grey("off (Living Villages or Village Dressing is off): none is put up, and what is up comes down")))
        }
        val status = Dressing.status(level, Streets.column(rec.key), rec.key)
        val note = when {
            !VillageLamps.folk(level, rec.key) -> "; no folk of its own to hang it (the times do not dress it)"
            !Fortunes.enabled() -> "; the times are off (Town Fortunes): only `village dress` puts it up"
            else -> ""
        }
        return listOf(LampCommand.line().append(label).append(LampCommand.plain(status)).append(LampCommand.grey(note)))
    }
}
