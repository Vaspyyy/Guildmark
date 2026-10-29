package io.github.vaspyyy.guildmark.village.live.build

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.advance.AdvanceBuilders
import io.github.vaspyyy.guildmark.advance.Advances
import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.lamps.LampCommand
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * The commands for the villages' building (living-world/villages/design.md 15.2, part V9), run by operators under `/guildmark village`:
 *
 * - `build` or `build list` lists the projects going up, in every village: what, where, its stage and how far along, and what its
 *   crew is doing (waiting for its first morning, at work, gone home for the night, nobody near to see it, its land not loaded).
 * - `build start` has the crew of the village you are in come now, rather than at the next sunrise; `build start <project>` builds that
 *   project there now as if it had been funded (the board's progress is not touched), by a crew or at once as the settings have it.
 * - `build rush` finishes the village's project now, out of sight (what is not loaded, as it loads).
 *
 * `village info` gains a line for the project going up.
 */
object VillageBuildCommand {
    private const val NEAREST = 128

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        VillageInfoCommand.section(VillageInfoCommand.Section { level, rec -> info(level, rec) })
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village").then(
                        Commands.literal("build")
                            .executes { list(it) }
                            .then(Commands.literal("list").executes { list(it) })
                            .then(
                                Commands.literal("start")
                                    .executes { start(it, null) }
                                    .then(
                                        Commands.argument("project", StringArgumentType.word())
                                            .suggests { _, b -> SharedSuggestionProvider.suggest(Advance.entries.filter { it != Advance.TRADE_ROAD }.map { it.id }, b) }
                                            .executes { start(it, StringArgumentType.getString(it, "project")) },
                                    ),
                            )
                            .then(Commands.literal("rush").executes { rush(it) }),
                    ),
                ),
        )
    }

    // ---- list ----

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        if (!Villages.enabled()) return LampCommand.fail(source, "Living Villages is off (setting: Living Villages): the villages' projects go up at once.")
        val sites = VillageBuilds.all(level)
        LampCommand.say(source, LampCommand.header("Village projects going up"))
        if (!VillageBuilds.enabled()) LampCommand.say(source, LampCommand.grey("Village Builds is off (setting: villageBuilds): a project funded now goes up at once, and any going up is finished as its land loads."))
        if (sites.isEmpty()) return LampCommand.say(source, LampCommand.grey("None. Fund one at a village's board, or `village build start <project>` in a village."))
        for (b in sites) {
            val rec = Villages.byKey(level, b.village)
            val site = VillageBuilds.site(level, b.id)
            val project = Advance.entries[b.project.coerceIn(0, Advance.entries.size - 1)]
            val line = LampCommand.line().append(LampCommand.label("#${b.id} ")).append(project.title.copy()).append(LampCommand.grey(" at ${rec?.name ?: "a village"} "))
                .append(LampCommand.at(BlockPos.of(b.yard.crate)))
            LampCommand.say(source, line)
            if (site != null) {
                val percent = if (site.total == 0) 100 else (site.laid * 100L / site.total).toInt()
                LampCommand.say(source, LampCommand.line().append(LampCommand.grey("  ")).append(LampCommand.plain("${site.stage.key}, ${site.laid} of ${site.total} blocks ($percent%)"))
                    .append(LampCommand.grey(", ${doing(level, b, site)}")))
                site.blocked?.let { LampCommand.say(source, LampCommand.line().append(LampCommand.grey("  held up by something at ")).append(LampCommand.at(it))) }
            }
        }
        return sites.size
    }

    /** What [site]'s crew is doing, in a few words. */
    private fun doing(level: ServerLevel, b: VillageBuild, site: VillageSite): String {
        val crew = BuilderCrew.builders(site.key).filter { !it.leaving }
        val lent = crew.count { it.lent }
        return when {
            b.rushing -> "being finished out of sight as its land loads"
            !VillageBuilds.isLoaded(b.id) -> "its land is not loaded"
            !site.opened() -> "the crew comes at sunrise (`village build start` brings them now)"
            !site.workday() -> "the crew has gone home for the night"
            site.onBreak() -> "the crew is at their dinner"
            !VillageBuilds.watched(b.id) -> "nobody within ${VillageBuilds.NEAR.toInt()} blocks: the clock lays it, the crew comes when someone is near"
            else -> "${crew.size} of ${site.crew} at work ($lent of the village's own)" + if (site.overdue()) "; overdue, finished out of sight tonight if nobody is near" else ""
        }
    }

    // ---- start, rush ----

    /** The village you are in (or the nearest), or null having said why not. */
    private fun target(context: CommandContext<CommandSourceStack>): VillageRec? {
        val source = context.source
        val level = source.level
        if (!Villages.enabled()) {
            LampCommand.fail(source, "Living Villages is off (setting: Living Villages).")
            return null
        }
        val pos = BlockPos.containing(source.position)
        val rec = Villages.at(level, pos) ?: Villages.near(level, pos, NEAREST).firstOrNull()
        if (rec == null || rec.state == Live.LOST) {
            LampCommand.fail(source, "No village within $NEAREST blocks.")
            return null
        }
        return rec
    }

    private fun start(context: CommandContext<CommandSourceStack>, id: String?): Int {
        val source = context.source
        val level = source.level
        val rec = target(context) ?: return 0
        val going = VillageBuilds.all(level).firstOrNull { it.village == rec.key && !it.rushing }
        if (id == null) {
            if (going == null) return LampCommand.fail(source, "${rec.name} has no project going up. `village build start <project>` starts one.")
            VillageBuilds.startNow(level, going)
            return LampCommand.say(source, LampCommand.plain("${rec.name}'s crew comes now, whatever the hour (they work in working hours, with someone near)."))
        }
        val project = Advance.entries.firstOrNull { it.id == id } ?: return LampCommand.fail(source, "No such project: $id. One of: ${Advance.entries.filter { it != Advance.TRADE_ROAD }.joinToString { it.id }}.")
        if (project == Advance.TRADE_ROAD) return LampCommand.fail(source, "The trade road is surveyed and laid by the road network, not built by a crew.")
        val bell = BlockPos.of(rec.key)
        val anchor = rec.board.takeIf { it != -1L }?.let { BlockPos.of(it) } ?: bell
        val failure = BuildSink.record(level, bell.asLong(), project.ordinal) {
            AdvanceBuilders.build(project, level, bell, Advances.villageRadius(level, bell), anchor)
        }
        if (failure != null) return LampCommand.fail(source, "No room for the ${id.replace('_', ' ')} at ${rec.name}.")
        val now = VillageBuilds.all(level).firstOrNull { it.village == rec.key && !it.rushing }
        if (now == null) return LampCommand.say(source, LampCommand.plain("Built at once (Village Builds is off, or there was nothing for a crew to lay)."))
        VillageBuilds.startNow(level, now)
        return LampCommand.say(source, LampCommand.line().append(LampCommand.plain("${rec.name}'s ")).append(project.title.copy()).append(LampCommand.plain(" is to be built by a crew, who come now: ${now.size} steps.")))
    }

    private fun rush(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val rec = target(context) ?: return 0
        val going = VillageBuilds.all(level).firstOrNull { it.village == rec.key } ?: return LampCommand.fail(source, "${rec.name} has no project going up.")
        val done = VillageBuilds.rushNow(level, going)
        return LampCommand.say(source, LampCommand.plain(if (done) "${rec.name}'s project is finished." else "${rec.name}'s project is finished as far as its land is loaded; the rest goes in as it loads."))
    }

    // ---- village info ----

    private fun info(level: ServerLevel, rec: VillageRec): List<Component> {
        val b = VillageBuilds.all(level).firstOrNull { it.village == rec.key } ?: return listOf()
        val project = Advance.entries[b.project.coerceIn(0, Advance.entries.size - 1)]
        val site = VillageBuilds.site(level, b.id) ?: return listOf()
        val percent = if (site.total == 0) 100 else (site.laid * 100L / site.total).toInt()
        return listOf(
            LampCommand.line().append(LampCommand.label("Building: ")).append(project.title.copy())
                .append(LampCommand.plain(", ${site.stage.key}, $percent%")).append(LampCommand.grey(" (${doing(level, b, site)})")),
        )
    }
}
