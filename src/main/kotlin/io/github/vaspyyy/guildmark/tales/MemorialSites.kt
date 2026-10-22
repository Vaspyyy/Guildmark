package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.plan.Grid
import io.github.vaspyyy.guildmark.tales.MemorialRules.Cell
import io.github.vaspyyy.guildmark.tales.MemorialRules.Plot
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Places
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs

/**
 * What [MemorialRules] asks of the game's world (design 6.4): the level's blocks, read only where the chunk is loaded (never loading one),
 * and, in a capital, the plan's word for each column (`Use`) and its buildings' doors. A column the capital's plan does not reach is
 * wild: only its blocks speak. One is made for a search and thrown away, so nothing in it is kept.
 */
internal class MemorialSites(
    private val level: ServerLevel,
    private val capital: CapitalPiece?,
    private val places: CityPlaces?,
) : MemorialRules.Terrain {
    private val at = BlockPos.MutableBlockPos()

    private val grid: Grid? = capital?.let {
        try {
            it.plan.grid
        } catch (e: IllegalStateException) {
            null
        }
    }

    private val doors: List<BlockPos> = places?.lodgings?.values?.flatMap { l -> l.doors.map { it.pos } } ?: listOf()

    override fun cell(x: Int, y: Int, z: Int): Cell? {
        at.set(x, y, z)
        if (level.isOutsideBuildHeight(y) || !level.isInWorldBounds(at) || !level.isLoaded(at)) return null
        return classify(level.getBlockState(at), at)
    }

    override fun plot(x: Int, z: Int): Plot {
        val g = grid ?: return Plot.WILD
        val c = capital ?: return Plot.WILD
        if (!c.reaches(x, z)) return Plot.WILD
        val (lx, lz) = c.local(x, z)
        return MemorialRules.plotOf(g[lx, lz])
    }

    override fun door(x: Int, z: Int, r: Int): Boolean = doors.any { abs(it.x - x) <= r && abs(it.z - z) <= r }

    override fun walk(x: Int, y: Int, z: Int): Boolean? {
        at.set(x, y, z)
        if (level.isOutsideBuildHeight(y) || !level.isInWorldBounds(at) || !level.isLoaded(at)) return null
        return Places.standable(level, at)
    }

    /** What [state] at [pos] is to the rules. */
    private fun classify(state: BlockState, pos: BlockPos): Cell = when {
        state.isAir -> Cell.AIR
        !state.fluidState.isEmpty -> Cell.WATER
        state.`is`(BlockTags.CROPS) || state.`is`(Blocks.FARMLAND) || state.`is`(Blocks.SWEET_BERRY_BUSH) -> Cell.FARM
        // What `Streets.place` builds over: short grass, a fern, a thin snow layer (never a flower, a tall plant or a block with an entity)
        state.canBeReplaced() && !state.hasBlockEntity() && state.block !is DoublePlantBlock -> Cell.PLANT
        natural(state) -> Cell.GROUND
        Places.floor(level, pos) -> Cell.PAVED
        else -> Cell.OTHER
    }

    /** Ground the land itself lays: grass, dirt in its kinds, sand, gravel, clay, a natural stone, terracotta (never a path, paving or planks). */
    private fun natural(state: BlockState): Boolean =
        state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.SAND) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.TERRACOTTA) ||
            state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.CLAY) || state.`is`(Blocks.SNOW_BLOCK)

    // ---- Where a memorial belongs when the tale names no place ----

    /**
     * The places a memorial of a kind belongs when its tale names none (6.4), the best first: a stone at the door of a temple, else by a
     * well in the square, else in the market; a cairn beside the road a gate leads out by, and then where a stone would go. The search
     * takes the first of them it finds room near. Empty where the capital's places are not known.
     */
    fun anchors(cairn: Boolean): List<BlockPos> {
        val p = places ?: return listOf()
        val out = ArrayList<BlockPos>()
        if (cairn) roadside()?.let { out += it }
        for (temple in p.nearest(p.centre, CityPlaces.TEMPLES).take(TEMPLES)) temple.front?.outside?.let { out += it }
        out += p.wells.take(WELLS)
        out += p.market
        return out
    }

    /** The first column of a road out of the capital's gates, beyond the glacis (south, east, west, north), at its standing height; null if none is found on the axes. */
    private fun roadside(): BlockPos? {
        val c = capital ?: return null
        val g = grid ?: return null
        val plan = try {
            c.plan
        } catch (e: IllegalStateException) {
            return null
        }
        val (lx, lz) = MemorialRules.roadside(g, plan.levelled, plan.reach) ?: return null
        return c.world(lx, plan.level(lx, lz), lz)
    }

    private companion object {
        /** How many temples and wells are tried as the place a memorial belongs. */
        const val TEMPLES = 2
        const val WELLS = 2
    }
}
