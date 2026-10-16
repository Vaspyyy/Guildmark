package io.github.vaspyyy.guildmark.island

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.island.plan.IslandKind
import io.github.vaspyyy.guildmark.island.plan.IslandSpec
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import kotlin.math.sqrt

/**
 * Testing commands: `/guildmark island here [kind]` raises an island (of that kind, or any) round where you stand out
 * at sea, `/guildmark island nearest` says where the nearest island grown with the world is and how far off, and
 * `/guildmark island info` tells you about the island whose waters you're in.
 */
object IslandCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("island")
                        .then(
                            Commands.literal("here")
                                .executes { here(it, null) }
                                .then(
                                    Commands.argument("kind", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(IslandKind.entries.map { it.name.lowercase() }, builder) }
                                        .executes { here(it, StringArgumentType.getString(it, "kind")) },
                                ),
                        )
                        .then(Commands.literal("nearest").executes { nearest(it) })
                        .then(Commands.literal("info").executes { info(it) }),
                ),
        )
    }

    private fun here(context: CommandContext<CommandSourceStack>, kindName: String?): Int {
        val source = context.source
        val level = source.level
        val kind = if (kindName == null) null else IslandKind.entries.firstOrNull { it.name.equals(kindName, ignoreCase = true) }
        if (kindName != null && kind == null) {
            source.sendFailure(Component.translatable("commands.guildmark.island.no_kind", kindName))
            return 0
        }
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.translatable("commands.guildmark.island.overworld"))
            return 0
        }
        val at = BlockPos.containing(source.position)
        val sea = level.seaLevel
        if (!level.getFluidState(BlockPos(at.x, sea - 1, at.z)).`is`(FluidTags.WATER)) {
            source.sendFailure(Component.translatable("commands.guildmark.island.no_sea"))
            return 0
        }
        val climate = IslandStructure.climateOf(level.getBiome(BlockPos(at.x, sea - 8, at.z)))
        val spec = IslandStructure.specFor(level.random.nextLong(), at.x, at.z, sea, climate, kind)
        if (!Islands.raise(level, spec)) {
            source.sendFailure(Component.translatable("commands.guildmark.island.failed"))
            return 0
        }
        source.sendSuccess({ Component.translatable("commands.guildmark.island.here", describe(spec)) }, true)
        return 1
    }

    private fun nearest(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val from = BlockPos.containing(source.position)
        val pos = level.findNearestMapStructure(Islands.TAG, from, 64, false)
        if (pos == null) {
            source.sendFailure(Component.translatable("commands.guildmark.island.none"))
            return 0
        }
        // The island's middle is its start chunk's
        val x = pos.x and 15.inv() or 8
        val z = pos.z and 15.inv() or 8
        val distance = sqrt((x - from.x).toDouble() * (x - from.x) + (z - from.z).toDouble() * (z - from.z)).toInt()
        source.sendSuccess({ Component.translatable("commands.guildmark.island.nearest", x, z, distance) }, false)
        return 1
    }

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val spec = Islands.at(source.level, BlockPos.containing(source.position))
        if (spec == null) {
            source.sendFailure(Component.translatable("commands.guildmark.island.not_here"))
            return 0
        }
        source.sendSuccess({ Component.translatable("commands.guildmark.island.info", describe(spec), spec.cx, spec.cz, spec.radius, spec.seed.toString()) }, false)
        return 1
    }

    private fun describe(spec: IslandSpec): String =
        "${spec.kind.name.lowercase()} (${spec.form.name.lowercase()}, ${spec.climate.name.lowercase()} sea, ${spec.rock.name.lowercase()})"
}
