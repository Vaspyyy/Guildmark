package io.github.vaspyyy.guildmark.village.live

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.lamps.LampCommand
import io.github.vaspyyy.guildmark.lamps.LampKeepers
import io.github.vaspyyy.guildmark.lamps.LampTown
import io.github.vaspyyy.guildmark.lamps.StreetLamps
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.streets.Rationing
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * The commands for a village's lamps (living-world/villages/design.md 15.2, part V8), run by operators under `/guildmark village`
 * on the village you are in (or the nearest within [NEAREST] blocks):
 *
 * - `lamps` says what its lamps are doing: the posts the census found, its rounds with who keeps each and where they are, how the
 *   clock has the day, how many of the lamps are lit, the older lanterns waiting to be swapped for street lamps, and whether the
 *   candles are short.
 * - `lamps dusk` sets every round going now, whatever the hour, to light its lamps (by its keeper, if they can be had, or by the
 *   clock); `lamps dawn` the same to put them out; `lamps clear` gives it all back to the clock; and `lamps ration` has the candles
 *   short for the next ten minutes, so that the next `dusk` lights every other lamp.
 *
 * A village's lamps are kept while a player is within 96 blocks of its bell, so the village has to be live; and its census has to
 * have found a post.
 */
object VillageLampsCommand {
    private const val NEAREST = 128

    /** How long `lamps ration` has the candles short (ticks). */
    private const val RATIONED = 12000L

    /** At setup: the commands, and the lines this part adds to `village info`. */
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
                        Commands.literal("lamps")
                            .executes { status(it) }
                            .then(Commands.literal("dusk").executes { go(it, lighting = true) })
                            .then(Commands.literal("dawn").executes { go(it, lighting = false) })
                            .then(Commands.literal("clear").executes { clear(it) })
                            .then(Commands.literal("ration").executes { ration(it) }),
                    ),
                ),
        )
    }

    // ---- The village you mean ----

    /** The village's record and its lamps, or null having said why not. */
    private fun target(context: CommandContext<CommandSourceStack>): Pair<VillageRec, LampTown>? {
        val source = context.source
        val level = source.level
        if (!Villages.enabled()) {
            LampCommand.fail(source, "Living Villages is off (setting: Living Villages): no village's lamps are kept.")
            return null
        }
        val pos = BlockPos.containing(source.position)
        val rec = Villages.at(level, pos) ?: Villages.near(level, pos, NEAREST).firstOrNull()
        if (rec == null) {
            LampCommand.fail(source, "No village within $NEAREST blocks.")
            return null
        }
        if (rec.state != Live.LIVE) {
            LampCommand.fail(source, "${rec.name} is ${rec.state.name.lowercase()}: a village's lamps are kept while a player is within 96 blocks of its bell. Go nearer.")
            return null
        }
        val places = Villages.places(level, rec.key)
        if (places == null) {
            LampCommand.fail(source, "${rec.name} has not been counted yet (`village census now` counts it).")
            return null
        }
        if (places.lamps.isEmpty()) {
            LampCommand.fail(source, "${rec.name}'s census found no lamp post (a lantern on at least two fence posts, or a street lamp on a post): there is no round to keep.")
            return null
        }
        val town = StreetLamps.keepVillage(level, rec, level.gameTime)
        if (town == null) {
            LampCommand.fail(source, "${rec.name}'s lamps could not be worked out (see the log).")
            return null
        }
        return rec to town
    }

    // ---- lamps ----

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val (rec, town) = target(context) ?: return 0
        val source = context.source
        val level = source.level
        val off = off(town)
        val where = LampCommand.Where(
            "the village",
            listOf(
                Triple("[Dusk]", "/guildmark village lamps dusk", "Set every round going now, lighting its lamps"),
                Triple("[Dawn]", "/guildmark village lamps dawn", "Set every round going now, putting out its lamps"),
                Triple("[Back to the clock]", "/guildmark village lamps clear", "Give the lamps back to the clock"),
            ),
        )
        LampCommand.report(source, town, "The lamps of ${rec.name}", where, off)
        // Every round, not only the nearest
        if (town.runs.size > 1) {
            for (run in town.runs) {
                LampCommand.say(source, LampCommand.line().append(LampCommand.label("Round ${run.round + 1}: ")).append(LampCommand.plain("${run.lamps.size} lamps"))
                    .append(LampCommand.grey(", ${keeperName(level, run)}")))
            }
        }
        LampCommand.say(source, LampCommand.line().append(LampCommand.label("Candles: ")).append(LampCommand.plain(Rationing.why(level, rec.key))))
        return 1
    }

    /** Why the lamps are not worked to the clock, if they are not. */
    private fun off(town: LampTown): String? = when {
        !StreetLamps.enabled() -> "The street lamps are switched off (setting: Street Lamps): they burn day and night, and older lanterns aren't swapped."
        !VillageLamps.on() -> "Village Lamps is off (setting): the street lamps burn day and night, and older lanterns aren't swapped."
        else -> null
    }

    /** Who keeps [run], in a few words. */
    private fun keeperName(level: ServerLevel, run: LampTown.Run): String {
        val r = if (run.keeper >= 0) Residents.get(level)[run.keeper] else null
        return if (r == null) "nobody yet: the clock keeps it" else "kept by ${r.name}, ${r.role.id}"
    }

    // ---- dusk, dawn, clear, ration ----

    private fun go(context: CommandContext<CommandSourceStack>, lighting: Boolean): Int {
        val (rec, town) = target(context) ?: return 0
        val source = context.source
        val level = source.level
        off(town)?.let { return LampCommand.fail(source, "$it Switch it on first.") }
        for (run in town.runs) {
            StreetLamps.startNow(level, town, run, lighting)
            val r = if (run.held != null) Residents.get(level)[run.keeper] else null
            val who = if (r != null) "${r.name} sets out to ${if (lighting) "light" else "put out"} them" else "nobody can be had to go round it, so its lamps ${if (lighting) "catch" else "go out"} one after another by the clock"
            LampCommand.say(
                source, LampCommand.line().append(LampCommand.label("Round ${run.round + 1} of ${rec.name} (${run.lamps.size} lamps): ")).append(LampCommand.plain(who))
                    .append(LampCommand.grey(", from ")).append(LampCommand.at(town.pos[run.lamps[0]])),
            )
        }
        return LampCommand.say(source, LampCommand.grey("They hold until the day's next stretch; `village lamps clear` gives them back to the clock."))
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val (rec, town) = target(context) ?: return 0
        StreetLamps.clearForced(context.source.level, town)
        Rationing.unforce(rec.key)
        return LampCommand.say(context.source, LampCommand.plain("${rec.name}'s lamps are the clock's again: any round set going is let go, the keepers are named and the rounds timed afresh, and the candles are as the market has them."))
    }

    private fun ration(context: CommandContext<CommandSourceStack>): Int {
        val (rec, _) = target(context) ?: return 0
        Rationing.force(context.source.level, rec.key, RATIONED)
        return LampCommand.say(context.source, LampCommand.plain("${rec.name}'s candles are short for the next ${RATIONED / 1200} minutes: `village lamps dusk` lights every other lamp, and the rest catch by themselves a little before seven."))
    }

    // ---- village info ----

    /** The lines `village info` ends this village's with: its posts, rounds and keepers. */
    private fun lines(level: ServerLevel, rec: VillageRec): List<Component> {
        val places = Villages.places(level, rec.key)
        val posts = places?.lamps?.size ?: 0
        val label = LampCommand.label("Lamps: ")
        if (!VillageLamps.working()) {
            return listOf(LampCommand.line().append(label).append(LampCommand.grey("not worked to the clock (Living Villages, Street Lamps or Village Lamps is off): $posts post${if (posts == 1) "" else "s"} burn day and night.")))
        }
        if (places == null) return listOf(LampCommand.line().append(label).append(LampCommand.grey("not counted yet")))
        if (posts == 0) return listOf(LampCommand.line().append(label).append(LampCommand.grey("no post found (a lantern on at least two fence posts, or a street lamp on a post)")))
        val kept = LampKeepers.get(level)[BlockPos.of(rec.key)]
        val folk = Residents.get(level)
        val names = kept?.filter { it >= 0 }?.mapNotNull { folk[it]?.takeIf { r -> r.alive }?.name }.orEmpty()
        val town = StreetLamps.villageTown(BlockPos.of(rec.key))
        val rounds = town?.runs?.size
        val who = if (names.isEmpty()) "no keeper yet: the clock lights them" else "kept by ${names.joinToString(", ")}"
        return listOf(
            LampCommand.line().append(label).append(LampCommand.plain("$posts post${if (posts == 1) "" else "s"}"))
                .append(LampCommand.grey(if (rounds != null) " in $rounds round${if (rounds == 1) "" else "s"}; $who" else "; $who (the rounds are worked out while a player is near)")),
        )
    }
}
