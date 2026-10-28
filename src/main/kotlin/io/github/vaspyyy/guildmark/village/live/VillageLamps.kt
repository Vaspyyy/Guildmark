package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.lamps.LampKeepers
import io.github.vaspyyy.guildmark.lamps.LampRegistry
import io.github.vaspyyy.guildmark.lamps.StreetLampBlock
import io.github.vaspyyy.guildmark.lamps.StreetLamps
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.village.live.days.VillageLife
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * A village's lamps (living-world/villages/design.md 10.1, part V8): the posts its census found are kept as a lamp town of their
 * own ([io.github.vaspyyy.guildmark.lamps.LampTown.village]) while the village is [Live.LIVE] and for a minute after, by
 * [StreetLamps], and worked to the clock as a capital's are: lit by 19:00, out by 07:30 (an hour earlier and later in rain), and in
 * between by the village's own keepers, who are out of their door with the lit pole at about half past five, lamp to lamp, and
 * home by seven, and from half past five of a morning put them out. A village's keepers are named from its folk
 * ([io.github.vaspyyy.guildmark.lamps.LampKeepers]); with nobody to keep a round, the clock lights its lamps, each at its own phase,
 * with a quiet flicker if a player is by, and a village is never dark all night. Candles short in its market ration its lamps as a
 * capital's. An older world's plain lanterns on posts are swapped for street lamps at night while nobody is within 48 blocks, and new posts are
 * built with a street lamp, unlit ([lantern]).
 *
 * This file is the village's side of it: the switch, what posts a village has, who keeps them (the crew's, for [VillageHands]),
 * and the lines of `village info`; the lamps themselves are in the `lamps` package, and the commands in [VillageLampsCommand].
 *
 * Off with Village Lamps (or Street Lamps, or Living Villages): the lamps burn day and night as plain lanterns did, a village's
 * lamp that is a street lamp already burning too, and nothing is swapped.
 */
object VillageLamps {
    /** Whether the Village Lamps switch is on; on until the settings are loaded. */
    fun on(): Boolean = try {
        GuildmarkConfig.VILLAGE_LAMPS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** Whether a village's lamps are worked to the clock: Living Villages, Street Lamps and Village Lamps all on. */
    fun working(): Boolean = Villages.enabled() && StreetLamps.enabled() && on()

    /** Whether a village's new posts are built with a street lamp (see [lantern]): as long as [working]. */
    fun takesPosts(): Boolean = working()

    /**
     * The lantern a village's new lamp post is topped with (the advances' builder, AdvanceBuilders.lampPosts): an unlit street lamp,
     * which the village's keeper lights at dusk, so a new village never needs the swap; the plain lantern, burning day and night as
     * it always did, where the village's lamps are not worked ([takesPosts]).
     */
    fun lantern(): BlockState =
        if (takesPosts()) LampRegistry.STREET_LAMP.get().defaultBlockState().setValue(StreetLampBlock.LIT, false).setValue(StreetLampBlock.SETTLING, false)
        else Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false)

    /** The villages that are [Live.LIVE] now: those whose lamps are kept. */
    fun live(level: ServerLevel): List<VillageRec> = if (!Villages.enabled()) listOf() else Villages.awake(level).filter { it.state == Live.LIVE }

    /** Whether the village at [key] has folk who may keep its lamps: Living Villages and the folk switch on, and it is not released. */
    fun folk(level: ServerLevel, key: Long): Boolean = VillageLife.enabled() && Villages.byKey(level, key)?.let { it.folk && it.state != Live.LOST } == true

    /** Who keeps the lamps of the village at [centre] now: the residents its rounds are saved with who live still, for its crew (the keepers' stretches). */
    fun keepers(level: ServerLevel, centre: BlockPos): Set<Int> {
        if (!working() || !folk(level, centre.asLong())) return setOf()
        val saved = LampKeepers.get(level)[centre] ?: return setOf()
        val living = Residents.get(level).byCentre(centre).filter { it.alive && it.id >= 0 }.mapTo(HashSet()) { it.id }
        return saved.filterTo(HashSet()) { it in living }
    }
}
