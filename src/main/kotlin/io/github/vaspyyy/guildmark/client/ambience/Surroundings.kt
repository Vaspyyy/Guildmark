package io.github.vaspyyy.guildmark.client.ambience

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.npc.Npc
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What a town sounds by, round the player: its beds and workshops, bells and banners, the water at its quays,
 * the folk about, and how high and open the place the player stands is. The blocks are swept a few chunk
 * sections a tick (skipping any section that holds nothing of interest), so a full look round takes about a
 * second; the ambience works from the last full sweep.
 */
class Surroundings {
    /** One full sweep's findings. The position lists are a fair sample when there are more than they keep. */
    class Findings {
        var beds = 0
        var workstations = 0
        var bells = 0
        /** Anvils, smithing tables and blast furnaces. */
        val smithies = Sample(24)
        val grindstones = Sample(8)
        /** Fletching tables: the carpenters and bowyers. */
        val carpentry = Sample(8)
        val banners = Sample(16)
        /** Water with open air over it, counted. */
        var waterSurface = 0
        /** The surface where it meets a quay, a bank or a pier... */
        val shore = Sample(32)
        /** ...a timber one in particular... */
        val piers = Sample(16)
        /** ...and out in the open, away from any edge. */
        val openWater = Sample(32)
    }

    /** Keeps up to [cap] of the positions offered, each with the same chance of being kept. */
    class Sample(private val cap: Int) {
        val list = ArrayList<BlockPos>()
        private var offered = 0

        fun offer(pos: BlockPos, random: RandomSource) {
            offered++
            if (list.size < cap) list.add(pos.immutable())
            else random.nextInt(offered).let { if (it < cap) list[it] = pos.immutable() }
        }

        fun pick(random: RandomSource): BlockPos? = if (list.isEmpty()) null else list[random.nextInt(list.size)]
    }

    var found = Findings()
        private set
    /** Full sweeps made since the last reset: 0 means nothing is known yet. */
    var sweeps = 0
        private set
    private var sweeping = Findings()
    private val sections = ArrayList<Long>()
    private var next = 0
    private var center: BlockPos = BlockPos.ZERO
    private val random = RandomSource.create()

    /** Townsfolk about, awake or asleep, and the ones awake. */
    var folk: List<LivingEntity> = emptyList()
        private set
    var awake: List<LivingEntity> = emptyList()
        private set
    /** 0 out in the wild, 1 in the thick of a city. */
    var urbanity = 0.0
        private set
    /** How far the ground falls away round the player on average (a wall or tower top stands well over it). */
    var drop = 0.0
        private set
    /** Whether the player stands on something built (masonry, timber) rather than the land itself. */
    var built = false
        private set
    /** How much open sky there is round the player's head, 0 under a roof to 1 in the open. */
    var openness = 1.0
        private set

    fun reset() {
        found = Findings()
        sweeping = Findings()
        sections.clear()
        next = 0
        sweeps = 0
        folk = emptyList()
        awake = emptyList()
        urbanity = 0.0
        drop = 0.0
        built = false
        openness = 1.0
    }

    fun tick(level: ClientLevel, player: Player, ticks: Int) {
        sweepStep(level, player)
        if (ticks % 20 == 0) countFolk(level, player)
        if (ticks % 10 == 5) feelThePlace(level, player)
    }

    // ---- The block sweep ----

    private fun sweepStep(level: ClientLevel, player: Player) {
        if (next >= sections.size) {
            if (sections.isNotEmpty()) {
                found = sweeping
                sweeps++
            }
            sweeping = Findings()
            begin(level, player.blockPosition())
        }
        var budget = SECTIONS_PER_TICK
        while (budget-- > 0 && next < sections.size) sweep(level, sections[next++])
    }

    private fun begin(level: ClientLevel, at: BlockPos) {
        center = at
        sections.clear()
        next = 0
        val y0 = SectionPos.blockToSectionCoord(max(level.minY, at.y - DOWN))
        val y1 = SectionPos.blockToSectionCoord(min(level.maxY, at.y + UP))
        for (sx in SectionPos.blockToSectionCoord(at.x - RADIUS)..SectionPos.blockToSectionCoord(at.x + RADIUS)) {
            for (sz in SectionPos.blockToSectionCoord(at.z - RADIUS)..SectionPos.blockToSectionCoord(at.z + RADIUS)) {
                for (sy in y0..y1) sections.add(SectionPos.asLong(sx, sy, sz))
            }
        }
    }

    private fun sweep(level: ClientLevel, key: Long) {
        val sx = SectionPos.x(key)
        val sy = SectionPos.y(key)
        val sz = SectionPos.z(key)
        val chunk = level.chunkSource.getChunk(sx, sz, ChunkStatus.FULL, false) ?: return
        val index = chunk.getSectionIndexFromSectionY(sy)
        if (index < 0 || index >= chunk.sectionsCount) return
        val section = chunk.getSection(index)
        if (section.hasOnlyAir() || !section.maybeHas { interesting(it) }) return
        val x0 = max(center.x - RADIUS, sx shl 4)
        val x1 = min(center.x + RADIUS, (sx shl 4) + 15)
        val z0 = max(center.z - RADIUS, sz shl 4)
        val z1 = min(center.z + RADIUS, (sz shl 4) + 15)
        val y0 = max(center.y - DOWN, sy shl 4)
        val y1 = min(center.y + UP, (sy shl 4) + 15)
        val pos = BlockPos.MutableBlockPos()
        for (x in x0..x1) for (z in z0..z1) for (y in y0..y1) {
            val state = section.getBlockState(x and 15, y and 15, z and 15)
            if (!interesting(state)) continue
            pos.set(x, y, z)
            note(level, section, state, pos)
        }
    }

    private fun note(level: ClientLevel, section: LevelChunkSection, state: BlockState, pos: BlockPos) {
        val f = sweeping
        when {
            state.`is`(BlockTags.BEDS) -> if (state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) f.beds++
            state.`is`(Blocks.BELL) -> f.bells++
            state.`is`(BlockTags.BANNERS) -> f.banners.offer(pos, random)
            state.`is`(BlockTags.ANVIL) || state.`is`(Blocks.SMITHING_TABLE) || state.`is`(Blocks.BLAST_FURNACE) -> {
                f.workstations++
                f.smithies.offer(pos, random)
            }
            state.`is`(Blocks.GRINDSTONE) -> {
                f.workstations++
                f.grindstones.offer(pos, random)
            }
            state.`is`(Blocks.FLETCHING_TABLE) -> {
                f.workstations++
                f.carpentry.offer(pos, random)
            }
            state.block in OTHER_WORK || state.`is`(BlockTags.CAULDRONS) -> f.workstations++
        }
        if (state.fluidState.`is`(FluidTags.WATER)) water(level, section, state, pos)
    }

    /** Water at the surface: whether it lies against an edge (and what it's made of) or out in the open. */
    private fun water(level: ClientLevel, section: LevelChunkSection, state: BlockState, pos: BlockPos) {
        val local = pos.y and 15
        val above = if (local < 15) section.getBlockState(pos.x and 15, local + 1, pos.z and 15) else level.getBlockState(pos.above())
        if (above.fluidState.`is`(FluidTags.WATER)) return
        val f = sweeping
        f.waterSurface++
        var edges = 0
        var timber = false
        for (side in Direction.Plane.HORIZONTAL) {
            val next = level.getBlockState(pos.relative(side))
            if (next.isAir || next.fluidState.`is`(FluidTags.WATER)) continue
            edges++
            if (wooden(next)) timber = true
        }
        if (edges > 0) {
            f.shore.offer(pos, random)
            if (timber) f.piers.offer(pos, random)
        } else if (state.`is`(Blocks.WATER)) {
            f.openWater.offer(pos, random)
        }
    }

    // ---- The folk and the place ----

    private fun countFolk(level: ClientLevel, player: Player) {
        val box = AABB.ofSize(player.position(), FOLK_RADIUS * 2, FOLK_HEIGHT * 2, FOLK_RADIUS * 2)
        folk = level.getEntitiesOfClass(LivingEntity::class.java, box) { townsfolk(it) }
        awake = folk.filter { !it.isSleeping }
        // A town's size, from its folk, beds, workshops, bells and banners; worked out once a sweep is in
        if (sweeps == 0) return
        val f = found
        val score = folk.size + min(f.beds, 30) * 0.6 + min(f.workstations, 12) * 0.5 + min(f.bells, 2) * 2.5 + min(f.banners.list.size, 8) * 0.3
        val target = smoothstep(URBAN_FROM, URBAN_TO, score)
        urbanity += (target - urbanity) * 0.35
    }

    private fun feelThePlace(level: ClientLevel, player: Player) {
        val feet = player.blockPosition()
        // The ground round about, in a ring at two distances
        var fall = 0.0
        for (i in 0 until RING) {
            val a = i * Math.PI * 2 / RING
            for (r in RING_RADII) {
                val x = feet.x + (cos(a) * r).toInt()
                val z = feet.z + (sin(a) * r).toInt()
                val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                fall += (feet.y - ground).coerceIn(0, MAX_DROP)
            }
        }
        drop = fall / (RING * RING_RADII.size)
        val under = level.getBlockState(feet.below())
        built = !under.isAir && !natural(under)
        // Sky over the head and a few steps round it
        val eye = BlockPos.containing(player.eyePosition)
        var open = if (level.canSeeSky(eye)) 1 else 0
        for (side in Direction.Plane.HORIZONTAL) if (level.canSeeSky(eye.relative(side, 3))) open++
        openness += (open / 5.0 - openness) * 0.5
    }

    companion object {
        /** Townsfolk the crowds count beyond anything that's an Npc (villagers, traders): story characters and the like. */
        val CROWD: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "city_crowd"))

        /** Who counts toward a crowd: villagers and traders (anything that's an Npc), and whatever the tag adds. */
        fun townsfolk(entity: LivingEntity): Boolean =
            entity.isAlive && (entity is Npc || entity.type.builtInRegistryHolder().`is`(CROWD))

        private const val RADIUS = 28
        private const val DOWN = 10
        private const val UP = 14
        private const val SECTIONS_PER_TICK = 4
        private const val FOLK_RADIUS = 32.0
        private const val FOLK_HEIGHT = 12.0
        /** The town score that starts to sound like a town, and one that's the thick of a city. */
        private const val URBAN_FROM = 3.0
        private const val URBAN_TO = 26.0
        private const val RING = 12
        private val RING_RADII = intArrayOf(7, 12)
        private const val MAX_DROP = 24

        /** Job blocks that only say a town is at work here (the smiths, grinders and carpenters have their own sounds). */
        private val OTHER_WORK: Set<Block> = setOf(
            Blocks.COMPOSTER, Blocks.BARREL, Blocks.SMOKER, Blocks.CARTOGRAPHY_TABLE, Blocks.BREWING_STAND,
            Blocks.LECTERN, Blocks.LOOM, Blocks.STONECUTTER,
        )

        private fun interesting(state: BlockState): Boolean =
            state.`is`(BlockTags.BEDS) || state.`is`(BlockTags.ANVIL) || state.`is`(BlockTags.BANNERS) || state.`is`(Blocks.BELL) ||
                state.`is`(Blocks.SMITHING_TABLE) || state.`is`(Blocks.BLAST_FURNACE) || state.`is`(Blocks.GRINDSTONE) ||
                state.`is`(Blocks.FLETCHING_TABLE) || state.block in OTHER_WORK || state.`is`(BlockTags.CAULDRONS) ||
                state.fluidState.`is`(FluidTags.WATER)

        private fun wooden(state: BlockState): Boolean =
            state.`is`(BlockTags.PLANKS) || state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.WOODEN_FENCES) ||
                state.`is`(BlockTags.WOODEN_SLABS) || state.`is`(BlockTags.WOODEN_STAIRS)

        /** The land as the world made it, as opposed to anything laid by hand. */
        private fun natural(state: BlockState): Boolean =
            state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.SAND) ||
                state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.ICE) || state.`is`(BlockTags.SNOW) ||
                state.`is`(BlockTags.TERRACOTTA) || state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.SANDSTONE) ||
                !state.fluidState.isEmpty

        fun smoothstep(from: Double, to: Double, x: Double): Double {
            val t = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }
}
