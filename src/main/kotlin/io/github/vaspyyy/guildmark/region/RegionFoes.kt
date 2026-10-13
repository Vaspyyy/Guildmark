package io.github.vaspyyy.guildmark.region

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.registry.ModEntities
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.random.Weighted
import net.minecraft.util.valueproviders.UniformInt
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.MobSpawnSettings
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.LevelEvent

/**
 * The wild lands' own foes. In the marsh, the ashen highlands, the frozen peaks and the crystal deeps, the land's
 * Hollow (enemy/RegionHollows) takes the Hollow Soldier's place among the monsters that can spawn, so wherever a
 * soldier would rise there the land's own Hollow rises instead, and a little more often. Elsewhere, or with the
 * setting off, the soldiers spawn as they always have (biome_modifier/hollow_soldier.json).
 */
object RegionFoes {
    /** Its weight among the land's monsters: the soldier's own is 25, a zombie's 95. */
    private const val WEIGHT = 40

    /**
     * Each land's Hollow as a spawn entry, made once: the spawner looks the entry it picked up again in the
     * list for every spot it tries, and finds it by equality.
     */
    private val foes: Map<RegionKind, Weighted<MobSpawnSettings.SpawnerData>> by lazy {
        mapOf(
            RegionKind.MARSH to entry(ModEntities.MIRE_HOLLOW.get()),
            RegionKind.ASHEN to entry(ModEntities.CINDER_HOLLOW.get()),
            RegionKind.FROZEN to entry(ModEntities.RIME_HOLLOW.get()),
            RegionKind.CRYSTAL to entry(ModEntities.GLIMMER_HOLLOW.get()),
        )
    }

    private fun entry(type: EntityType<*>) = Weighted(MobSpawnSettings.SpawnerData(type, UniformInt.of(1, 2)), WEIGHT)

    fun register() {
        NeoForge.EVENT_BUS.addListener(LevelEvent.PotentialSpawns::class.java, ::onPotentialSpawns)
    }

    fun enabled(): Boolean = !GuildmarkConfig.SPEC.isLoaded || GuildmarkConfig.REGION_ENEMIES.get()

    private fun onPotentialSpawns(event: LevelEvent.PotentialSpawns) {
        if (event.mobCategory != MobCategory.MONSTER) return
        val level = event.level as? ServerLevel ?: return
        val soldiers = event.spawnerDataList.filter { it.value().type() == ModEntities.HOLLOW_SOLDIER.get() }
        if (soldiers.isEmpty() || !enabled()) return
        val foe = Regions.kindAt(level, event.pos)?.let { foes[it] } ?: return
        for (soldier in soldiers) event.removeSpawnerData(soldier)
        event.addSpawnerData(foe)
    }
}
