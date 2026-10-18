package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.season.SeasonCrops
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.ConcurrentHashMap

/**
 * The seasons and the grand festivals as a capital's market feels them: its fields grow each crop at the pace the
 * season grows it in the open ([SeasonCrops]), its orchards bear in autumn, its herds and coops give most when the
 * season suits them, its homes burn more coal in the cold, and on a festival day the town feasts. Each comes out to
 * the usual over a year, and hot, cold and tropical lands feel it less ([Seasons.temperate]). The date is the one the
 * hour asked about falls on, so a market catching up comes out as it would have hour by hour.
 */
object Seasonal {
    /** The crops in the fields, by the crop the seasons pace them as. */
    private val FIELDS = mapOf(
        Good.WHEAT to SeasonCrops.profile(Blocks.WHEAT),
        Good.CARROT to SeasonCrops.profile(Blocks.CARROTS),
        Good.POTATO to SeasonCrops.profile(Blocks.POTATOES),
        Good.BEETROOT to SeasonCrops.profile(Blocks.BEETROOTS),
    )

    /** Spring, summer, autumn and winter: each comes to 1 over the year. */
    private val ORCHARD = SeasonCrops.Profile(0.5f, 0.9f, 2.1f, 0.5f)
    /** Beasts fattened through the summer go to the butcher in autumn; their hides with them. */
    private val SLAUGHTER = SeasonCrops.Profile(0.9f, 1.0f, 1.3f, 0.8f)
    /** Sheep are shorn in spring and summer. */
    private val SHEARING = SeasonCrops.Profile(1.4f, 1.2f, 0.8f, 0.6f)
    /** Hens lay with the long days. */
    private val LAYING = SeasonCrops.Profile(1.2f, 1.1f, 0.9f, 0.8f)

    /** Where a good the seasons pace comes from: as the stalls put it when that's why it's dear ([lean]) or cheap ([full]). */
    enum class Yield(val lean: Why, val full: Why) {
        FIELDS(Why.FIELDS_LEAN, Why.FIELDS_FULL),
        ORCHARD(Why.ORCHARD_LEAN, Why.ORCHARD_FULL),
        HERDS(Why.HERDS_LEAN, Why.HERDS_FULL),
        FLEECE(Why.FLEECE_LEAN, Why.FLEECE_FULL),
        COOPS(Why.COOPS_LEAN, Why.COOPS_FULL),
    }

    private val HERDS: Map<Good, Pair<SeasonCrops.Profile, Yield>> = mapOf(
        Good.APPLE to (ORCHARD to Yield.ORCHARD),
        Good.BEEF to (SLAUGHTER to Yield.HERDS), Good.PORK to (SLAUGHTER to Yield.HERDS),
        Good.MUTTON to (SLAUGHTER to Yield.HERDS), Good.LEATHER to (SLAUGHTER to Yield.HERDS),
        Good.WOOL to (SHEARING to Yield.FLEECE),
        Good.CHICKEN to (LAYING to Yield.COOPS), Good.FEATHER to (LAYING to Yield.COOPS),
    )

    /** Where [good] comes from, if the seasons pace it. */
    fun yieldOf(good: Good): Yield? = if (good in FIELDS) Yield.FIELDS else HERDS[good]?.second

    /** How much coal a hearth burns in each season against the year's usual. */
    private val HEARTH = SeasonCrops.Profile(0.9f, 0.4f, 1.1f, 1.6f)

    /** How much each capital feels the seasons, by its heart: biomes don't change, so it's worked out once. */
    private val feels = ConcurrentHashMap<Long, Float>()

    /** The world in [hour] as the capital whose heart is [at] feels it (fully, if there's none). */
    fun conditions(level: ServerLevel, hour: Long, at: BlockPos?): Conditions {
        val seasons = Seasons.enabled()
        val festivals = Seasons.festivalsEnabled()
        if (!seasons && !festivals) return Conditions(hour, level.isRaining, level.isThundering)
        val date = Seasons.dateOn(level.server, Math.floorDiv(hour, 24L))
        val feast = festivals && FestivalCalendar.on(date) != null
        if (!seasons) return Conditions(hour, level.isRaining, level.isThundering, feast = feast)
        val weight = if (at == null) 1f else feels.computeIfAbsent(at.asLong()) { Seasons.temperate(level, at) }
        val crops = Seasons.cropsEnabled()
        val pace = { good: Good ->
            val field = if (crops) FIELDS[good] else null
            val profile = field ?: HERDS[good]?.first
            if (profile == null) 1.0 else felt(SeasonCrops.pace(profile, date), weight, if (field != null) SeasonCrops.SLOWEST else 0f)
        }
        return Conditions(hour, level.isRaining, level.isThundering, pace, felt(SeasonCrops.pace(HEARTH, date), weight, 0f), feast)
    }

    /** [pace] as a land that feels the seasons by [weight] has it, never under [least]. */
    private fun felt(pace: Float, weight: Float, least: Float): Double = (1.0 + (pace - 1.0) * weight).coerceAtLeast(least.toDouble())
}
