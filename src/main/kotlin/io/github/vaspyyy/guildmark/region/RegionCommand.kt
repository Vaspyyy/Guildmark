package io.github.vaspyyy.guildmark.region

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.hypot

/**
 * /guildmark region: which region you're in. /guildmark region find <kind>: go to the nearest region of
 * that kind (for testing), the crystal deeps included.
 */
object RegionCommand {
    /** How many rings of cells round yours are searched. */
    private const val RINGS = 3
    /** Chunks of crystal ground looked through for a cave, at most. */
    private const val MAX_CHUNKS = 32

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("region")
                        .executes { context -> where(context.source) }
                        .then(
                            Commands.literal("find").then(
                                Commands.argument("kind", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(RegionKind.entries.filter { !it.underground || it == RegionKind.CRYSTAL }.map { it.id }, builder) }
                                    .executes { context -> find(context.source, StringArgumentType.getString(context, "kind")) }
                            )
                        )
                )
        )
    }

    private fun where(source: CommandSourceStack): Int {
        val player = source.playerOrException
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.translatable("commands.guildmark.region.overworld"))
            return 0
        }
        val region = Regions.regionAt(level, player.blockPosition())
        if (region == null) {
            source.sendFailure(Component.translatable("commands.guildmark.region.surveying"))
            return 0
        }
        val cell = RegionMap.cellOf(region.id)
        source.sendSuccess({
            Component.translatable(
                "commands.guildmark.region.here", region.name, Component.translatable(region.kind.titleKey),
                RegionCells.keyX(cell), RegionCells.keyZ(cell), RegionMap.slotOf(region.id),
            )
        }, false)
        return 1
    }

    private fun find(source: CommandSourceStack, id: String): Int {
        val player = source.playerOrException
        val level = player.level()
        val kind = RegionKind.byId(id)
        if (kind == null || kind.underground && kind != RegionKind.CRYSTAL) {
            source.sendFailure(Component.translatable("commands.guildmark.region.no_kind", id))
            return 0
        }
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.translatable("commands.guildmark.region.overworld"))
            return 0
        }
        if (kind == RegionKind.CRYSTAL) return findCrystal(source, level, player)
        val map = RegionMap.of(level)
        val cells = RegionCells.of(level.seed)
        val home = cells.cellAt(player.blockX, player.blockZ)
        var pending = false
        var best: RegionInfo? = null
        var bestDistance = Double.MAX_VALUE
        for (dx in -RINGS..RINGS) for (dz in -RINGS..RINGS) {
            val plan = map.plan(RegionCells.key(RegionCells.keyX(home) + dx, RegionCells.keyZ(home) + dz))
            if (plan == null) {
                pending = true
                continue
            }
            for (region in plan.regions) {
                if (region.kind != kind) continue
                val distance = hypot(region.labelX - player.x, region.labelZ - player.z)
                if (distance < bestDistance) {
                    best = region
                    bestDistance = distance
                }
            }
        }
        val found = best
        if (found == null) {
            source.sendFailure(Component.translatable(if (pending) "commands.guildmark.region.surveying" else "commands.guildmark.region.none_near", Component.translatable(kind.titleKey)))
            return 0
        }
        val x = found.labelX
        val z = found.labelZ
        level.getChunk(x shr 4, z shr 4)
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        player.teleportTo(level, x + 0.5, y + 0.5, z + 0.5, setOf(), player.yRot, player.xRot, true)
        source.sendSuccess({ Component.translatable("commands.guildmark.region.found", found.name, bestDistance.toInt()) }, false)
        return 1
    }

    /** The nearest spot in the crystal deeps with room to stand: a cave floor below y -8 in crystal ground. */
    private fun findCrystal(source: CommandSourceStack, level: ServerLevel, player: ServerPlayer): Int {
        val cells = RegionCells.of(level.seed)
        val pos = BlockPos.MutableBlockPos()
        var tried = 0
        // Spiral out every 24 blocks; in each chunk of crystal ground found, look down a few columns for a cave
        search@ for (ring in 0..80) {
            for (dx in -ring..ring) for (dz in -ring..ring) {
                if (maxOf(Math.abs(dx), Math.abs(dz)) != ring) continue
                val baseX = player.blockX + dx * 24
                val baseZ = player.blockZ + dz * 24
                if (!cells.crystalAt(baseX, RegionCells.CRYSTAL_TOP, baseZ)) continue
                if (tried++ >= MAX_CHUNKS) break@search
                val chunk = level.getChunk(baseX shr 4, baseZ shr 4)
                for (ox in 0 until 16 step 4) for (oz in 0 until 16 step 4) {
                    val x = (baseX and 15.inv()) + ox
                    val z = (baseZ and 15.inv()) + oz
                    if (!cells.crystalAt(x, RegionCells.CRYSTAL_TOP, z)) continue
                    for (y in RegionCells.CRYSTAL_TOP downTo level.minY + 6) {
                        if (!chunk.getBlockState(pos.set(x, y, z)).isAir || !chunk.getBlockState(pos.set(x, y + 1, z)).isAir) continue
                        if (!chunk.getBlockState(pos.set(x, y - 1, z)).isSolid) continue
                        val distance = hypot(x - player.x, z - player.z).toInt()
                        player.teleportTo(level, x + 0.5, y.toDouble(), z + 0.5, setOf(), player.yRot, player.xRot, true)
                        val name = Regions.regionAt(level, BlockPos(x, y, z))?.name ?: ""
                        source.sendSuccess({ Component.translatable("commands.guildmark.region.found", name, distance) }, false)
                        return 1
                    }
                }
            }
        }
        source.sendFailure(Component.translatable("commands.guildmark.region.none_near", Component.translatable(RegionKind.CRYSTAL.titleKey)))
        return 0
    }
}
