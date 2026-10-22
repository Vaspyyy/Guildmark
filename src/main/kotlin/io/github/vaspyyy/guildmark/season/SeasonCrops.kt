package io.github.vaspyyy.guildmark.season

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.StemBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent
import net.neoforged.neoforge.event.level.block.CropGrowEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import java.util.UUID

/**
 * How the seasons pace the crops, and when animals breed best.
 *
 * Each crop has a pace for each season: below 1 some of its growth is skipped, above 1 it sometimes grows a stage
 * more. Nothing ever stops: the slowest is [SLOWEST], and a crop under a roof (glass will do) or by a lit hearth,
 * lantern or lamp keeps at least its full pace whatever the season. Hot, cold and tropical lands feel it less, the
 * nether not at all. In spring a pair of animals often has twins; in winter they rest longer before breeding again.
 *
 * For the Living World: [pace] says how fast a crop at a place grows today, [inSeason] which crops are at their best
 * in a season, and [profile] a crop's pace through the year.
 */
object SeasonCrops {
    /** A crop's pace in each season, in order: spring, summer, autumn, winter. */
    class Profile(private vararg val paces: Float) {
        fun pace(season: Season): Float = paces[season.ordinal]

        /** The seasons it does at least as well as usual in. */
        val best: List<Season> get() = Season.entries.filter { pace(it) >= 1f }
    }

    const val SLOWEST = 0.35f

    /** Block light that keeps a crop warm through winter (a torch close by gives 13, a lantern 14). */
    private const val HEARTH_LIGHT = 10

    /** How much of a pace above 1 turns into extra stages (a pace of 1.2 gives one more stage every fifth time). */
    private const val BOOST = 1f

    private val PROFILES: MutableMap<Block, Profile> = mutableMapOf(
        Blocks.WHEAT to Profile(1.15f, 1.0f, 0.85f, 0.4f),
        Blocks.CARROTS to Profile(1.0f, 0.85f, 1.15f, 0.5f),
        Blocks.POTATOES to Profile(1.15f, 0.9f, 1.0f, 0.45f),
        Blocks.BEETROOTS to Profile(0.9f, 0.85f, 1.15f, 0.6f),
        Blocks.MELON_STEM to Profile(0.85f, 1.25f, 0.7f, SLOWEST),
        Blocks.PUMPKIN_STEM to Profile(0.85f, 1.1f, 1.25f, SLOWEST),
        Blocks.SUGAR_CANE to Profile(1.0f, 1.15f, 0.85f, 0.4f),
        Blocks.SWEET_BERRY_BUSH to Profile(1.0f, 1.0f, 1.2f, SLOWEST),
        Blocks.COCOA to Profile(1.0f, 1.15f, 1.0f, 0.6f),
        Blocks.TORCHFLOWER_CROP to Profile(1.2f, 1.0f, 0.8f, 0.4f),
        Blocks.PITCHER_CROP to Profile(1.1f, 1.15f, 0.8f, 0.4f),
        Blocks.CACTUS to Profile(1.0f, 1.0f, 0.9f, 0.5f),
        Blocks.BAMBOO to Profile(1.0f, 1.0f, 0.9f, 0.5f),
    )

    private val itemBlocks: MutableMap<Item, Block> = mutableMapOf(
        Items.WHEAT_SEEDS to Blocks.WHEAT, Items.WHEAT to Blocks.WHEAT,
        Items.CARROT to Blocks.CARROTS, Items.POTATO to Blocks.POTATOES,
        Items.BEETROOT_SEEDS to Blocks.BEETROOTS, Items.BEETROOT to Blocks.BEETROOTS,
        Items.MELON_SEEDS to Blocks.MELON_STEM, Items.MELON_SLICE to Blocks.MELON_STEM,
        Items.PUMPKIN_SEEDS to Blocks.PUMPKIN_STEM, Items.PUMPKIN to Blocks.PUMPKIN_STEM,
        Items.SUGAR_CANE to Blocks.SUGAR_CANE, Items.SWEET_BERRIES to Blocks.SWEET_BERRY_BUSH,
        Items.COCOA_BEANS to Blocks.COCOA, Items.TORCHFLOWER_SEEDS to Blocks.TORCHFLOWER_CROP,
        Items.PITCHER_POD to Blocks.PITCHER_CROP, Items.CACTUS to Blocks.CACTUS, Items.BAMBOO to Blocks.BAMBOO,
    )

    /** The seeds and harvests that name each crop, for their tooltips and the Almanac. */
    val ITEMS: Map<Item, Block> = itemBlocks

    /**
     * Puts a crop of another system into the seasons' pacing, at common setup (both sides, before a world or a tooltip reads
     * the tables): [block] grows by [profile] from then on, and each of [items] names it in its tooltip. The Almanac's rows
     * ([SHOWN]) are left alone, so its layout does not change.
     */
    fun add(block: Block, profile: Profile, vararg items: Item) {
        PROFILES[block] = profile
        for (item in items) itemBlocks[item] = block
    }

    /** The crops, each by the item the Almanac shows for it. */
    val SHOWN: List<Pair<Item, Block>> = listOf(
        Items.WHEAT to Blocks.WHEAT, Items.CARROT to Blocks.CARROTS, Items.POTATO to Blocks.POTATOES,
        Items.BEETROOT to Blocks.BEETROOTS, Items.MELON_SLICE to Blocks.MELON_STEM, Items.PUMPKIN to Blocks.PUMPKIN_STEM,
        Items.SUGAR_CANE to Blocks.SUGAR_CANE, Items.SWEET_BERRIES to Blocks.SWEET_BERRY_BUSH, Items.COCOA_BEANS to Blocks.COCOA,
        Items.TORCHFLOWER_SEEDS to Blocks.TORCHFLOWER_CROP, Items.PITCHER_POD to Blocks.PITCHER_CROP,
    )

    fun profile(block: Block): Profile? = PROFILES[block]

    /** The crops at their best in [season]. */
    fun inSeason(season: Season): List<Block> = PROFILES.filterValues { it.pace(season) > 1f }.keys.toList()

    /**
     * A crop's pace on [date] in the open in fully temperate land: its own season's pace, eased toward the next
     * season's over the last third and from the last season's over the first, so the change comes gently.
     */
    fun pace(profile: Profile, date: SeasonDate): Float {
        val here = profile.pace(date.season)
        val third = (date.length / 3).coerceAtLeast(1)
        return when (date.phase) {
            SeasonPhase.EARLY -> {
                val t = (date.day - 0.5f) / third
                profile.pace(date.season.previous) + (here - profile.pace(date.season.previous)) * (0.5f + 0.5f * t.coerceIn(0f, 1f))
            }
            SeasonPhase.LATE -> {
                val t = (date.day - (date.length - third) - 0.5f) / third
                here + (profile.pace(date.season.next) - here) * 0.5f * t.coerceIn(0f, 1f)
            }
            SeasonPhase.MID -> here
        }
    }

    /** How fast the crop [state] at [pos] grows today: 1 is vanilla's pace. */
    fun pace(level: Level, pos: BlockPos, state: BlockState): Float {
        if (!Seasons.cropsEnabled() || level.dimension() != Level.OVERWORLD) return 1f
        val profile = PROFILES[state.block] ?: return 1f
        val weight = Seasons.temperate(level, pos)
        var pace = 1f + (pace(profile, Seasons.today(level)) - 1f) * weight
        if (pace < 1f && sheltered(level, pos)) pace = 1f
        return pace.coerceAtLeast(SLOWEST)
    }

    /** A roof over it (anything solid, glass and leaves too) or a warm light by it. */
    fun sheltered(level: Level, pos: BlockPos): Boolean =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.x, pos.z) > pos.y + 1 || level.getBrightness(LightLayer.BLOCK, pos) >= HEARTH_LIGHT

    // ---- Growing ----

    /** Parents bred in winter, rested longer once the breeding's done (next tick, after vanilla sets their wait). */
    private val resting = ArrayList<Pair<ServerLevel, UUID>>()

    fun register() {
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Pre::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (event.result != CropGrowEvent.Pre.Result.DEFAULT) return@addListener
            val pace = pace(level, event.pos, event.state)
            if (pace < 1f && level.random.nextFloat() > pace) event.result = CropGrowEvent.Pre.Result.DO_NOT_GROW
        }
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Post::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            val state = level.getBlockState(event.pos)
            val pace = pace(level, event.pos, event.state)
            if (pace <= 1f || level.random.nextFloat() >= (pace - 1f) * BOOST) return@addListener
            // In its best season it sometimes comes on a stage more
            val block = state.block
            when {
                block is CropBlock && !block.isMaxAge(state) -> level.setBlock(event.pos, block.getStateForAge(block.getAge(state) + 1), Block.UPDATE_CLIENTS)
                block is StemBlock && state.getValue(StemBlock.AGE) < StemBlock.MAX_AGE ->
                    level.setBlock(event.pos, state.setValue(StemBlock.AGE, state.getValue(StemBlock.AGE) + 1), Block.UPDATE_CLIENTS)
                else -> return@addListener
            }
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, event.pos.x + 0.5, event.pos.y + 0.5, event.pos.z + 0.5, 2, 0.25, 0.2, 0.25, 0.0)
        }
        NeoForge.EVENT_BUS.addListener(BabyEntitySpawnEvent::class.java, ::onBreed)
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event ->
            if (resting.isEmpty() || event.level.isClientSide) return@addListener
            val level = event.level as ServerLevel
            val iterator = resting.iterator()
            while (iterator.hasNext()) {
                val (where, id) = iterator.next()
                if (where !== level) continue
                iterator.remove()
                (level.getEntity(id) as? AgeableMob)?.let { if (it.age in 0 until WINTER_REST) it.age = WINTER_REST }
            }
        }
    }

    /** Spring's twins, and winter's longer rest. */
    private fun onBreed(event: BabyEntitySpawnEvent) {
        if (event.isCanceled || !Seasons.cropsEnabled()) return
        val a = event.parentA as? Animal ?: return
        val b = event.parentB as? Animal ?: return
        val level = a.level() as? ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD) return
        val season = Seasons.today(level).season
        val weight = Seasons.temperate(level, a.blockPosition())
        when (season) {
            Season.SPRING -> if (event.child != null && level.random.nextFloat() < TWINS * weight) {
                val twin = a.getBreedOffspring(level, b) ?: return
                twin.isBaby = true
                twin.snapTo(a.x, a.y, a.z, level.random.nextFloat() * 360f, 0f)
                level.addFreshEntityWithPassengers(twin)
                level.sendParticles(ParticleTypes.HEART, a.x, a.y + 0.8, a.z, 4, 0.4, 0.3, 0.4, 0.0)
            }
            Season.WINTER -> if (weight > 0.5f) {
                resting.add(level to a.uuid)
                resting.add(level to b.uuid)
            }
            else -> {}
        }
    }

    private const val TWINS = 0.35f
    /** Ticks a pair rests after breeding in winter: twice vanilla's five minutes. */
    private const val WINTER_REST = 12000
}
