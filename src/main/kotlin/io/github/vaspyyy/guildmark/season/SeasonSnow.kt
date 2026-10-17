package io.github.vaspyyy.guildmark.season

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.stream.LongStream

/**
 * A light snow cover in the temperate lands' winter, which melts again as spring comes. Round each player a few open
 * columns at a time get one layer of snow (no more: one layer has no height to trip on), more quickly while it's
 * falling, until about as much of the ground is white as the time of winter says. Every layer laid is remembered by
 * its chunk, and only those are ever taken away again: snow the world made itself, or a player put down, stays.
 */
object SeasonSnow {
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    /** The snow layers this chunk was given by the season, as packed positions. */
    val LAID: DeferredHolder<AttachmentType<*>, AttachmentType<Laid>> = ATTACHMENTS.register("season_snow") { ->
        AttachmentType.builder { -> Laid() }.serialize(Laid.CODEC.fieldOf("positions")) { !it.positions.isEmpty() }.build()
    }

    class Laid(val positions: LongOpenHashSet = LongOpenHashSet()) {
        companion object {
            val CODEC: Codec<Laid> = Codec.LONG_STREAM.xmap({ Laid(LongOpenHashSet(it.toArray())) }, { LongStream.of(*it.positions.toLongArray()) })
        }
    }

    /** How far round a player snow is laid and melted. */
    private const val REACH = 44
    /** Ticks between turns, per player. */
    private const val EVERY = 10

    fun register(modBus: IEventBus) {
        ATTACHMENTS.register(modBus)
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            if (player.tickCount % EVERY != 3) return@addListener
            val level = player.level()
            if (level.dimension() != Level.OVERWORLD || player.isSpectator) return@addListener
            tick(level, player)
        }
    }

    /**
     * The share of open ground that should be white today: none until winter, a dusting at first, most of it in
     * the depth of winter, thinning in its last days and gone in the first of spring.
     */
    fun cover(date: SeasonDate): Float {
        if (!Seasons.snowEnabled()) return 0f
        val t = (date.day - 0.5f) / date.length
        return when (date.season) {
            Season.WINTER -> when {
                t < 0.25f -> 0.12f + t / 0.25f * 0.33f
                t < 0.7f -> 0.45f + (t - 0.25f) / 0.45f * 0.25f
                else -> 0.7f - (t - 0.7f) / 0.3f * 0.45f
            }
            Season.AUTUMN -> if (date.daysLeft == 0) 0.04f else 0f
            else -> 0f
        }
    }

    private fun tick(level: ServerLevel, player: ServerPlayer) {
        val date = Seasons.today(level)
        val target = cover(date)
        val random = level.random
        val falling = level.isRaining
        // Laying: a couple of columns a turn, four times as many while it's coming down
        if (target > 0f) {
            repeat(if (falling) 8 else 2) {
                val x = player.blockX + random.nextInt(REACH * 2 + 1) - REACH
                val z = player.blockZ + random.nextInt(REACH * 2 + 1) - REACH
                val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return@repeat
                if (share(chunk) < target) lay(level, chunk, x, z)
            }
        }
        // Melting: from any chunk round about that has more than its share, a few layers a turn (sooner in spring)
        val melt = if (target <= 0f) 6 else 2
        repeat(melt) {
            val x = player.blockX + random.nextInt(REACH * 2 + 1) - REACH
            val z = player.blockZ + random.nextInt(REACH * 2 + 1) - REACH
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return@repeat
            if (!chunk.hasData(LAID)) return@repeat
            val laid = chunk.getData(LAID).positions
            if (laid.isEmpty() || share(chunk) <= target) return@repeat
            val packed = laid.toLongArray()[random.nextInt(laid.size)]
            laid.remove(packed)
            chunk.markUnsaved()
            val pos = BlockPos.of(packed)
            val state = level.getBlockState(pos)
            if (state.`is`(Blocks.SNOW)) {
                val layers = state.getValue(SnowLayerBlock.LAYERS)
                if (layers > 1) level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers - 1), Block.UPDATE_ALL)
                else level.removeBlock(pos, false)
            }
        }
    }

    private fun share(chunk: LevelChunk): Float = if (!chunk.hasData(LAID)) 0f else chunk.getData(LAID).positions.size / 256f

    /** One layer of snow on the open ground at ([x], [z]), if it's ground that snow would lie on and wouldn't anyway. */
    private fun lay(level: ServerLevel, chunk: LevelChunk, x: Int, z: Int) {
        val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15) + 1
        val pos = BlockPos(x, top, z)
        if (!level.getBlockState(pos).isAir) return
        val biome = level.getBiome(pos).value()
        if (Seasons.temperate(biome) < 0.6f || biome.getPrecipitationAt(pos, level.seaLevel) != Biome.Precipitation.RAIN) return
        if (level.getBrightness(LightLayer.SKY, pos) < 15 || level.getBrightness(LightLayer.BLOCK, pos) > 10) return
        val snow = Blocks.SNOW.defaultBlockState()
        if (!snow.canSurvive(level, pos)) return
        // Not on water's edge ice or anything that isn't a plain top
        val below = level.getBlockState(pos.below())
        if (below.`is`(Blocks.ICE) || below.`is`(Blocks.PACKED_ICE) || !below.fluidState.isEmpty) return
        if (!level.setBlock(pos, snow, Block.UPDATE_ALL)) return
        chunk.getData(LAID).positions.add(pos.asLong())
        chunk.markUnsaved()
    }
}
