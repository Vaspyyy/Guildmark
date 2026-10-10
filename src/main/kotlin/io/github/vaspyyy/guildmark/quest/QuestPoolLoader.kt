package io.github.vaspyyy.guildmark.quest

import com.mojang.logging.LogUtils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller

/** Reads every quest_pool JSON file, dropping entries whose item or mob isn't installed. */
class QuestPoolLoader : SimpleJsonResourceReloadListener<QuestPool>(QuestPool.CODEC, FileToIdConverter.json("quest_pool")) {
    override fun apply(preparations: Map<Identifier, QuestPool>, manager: ResourceManager, profiler: ProfilerFiller) {
        val pools = preparations.map { (id, pool) ->
            val (known, unknown) = pool.entries.partition(::targetExists)
            for (entry in unknown) LOGGER.warn("Quest pool {}: unknown {} target {}, skipping", id, entry.type.serializedName, entry.target)
            pool.copy(entries = known)
        }.filter { it.entries.isNotEmpty() }
        QuestPools.pools = pools
        LOGGER.info("Loaded {} quest pools with {} jobs", pools.size, pools.sumOf { it.entries.size })
    }

    private fun targetExists(entry: QuestPool.Entry): Boolean = when (entry.type) {
        QuestType.FETCH -> BuiltInRegistries.ITEM.containsKey(entry.target)
        QuestType.HUNT, QuestType.CHAMPION -> BuiltInRegistries.ENTITY_TYPE.containsKey(entry.target)
        QuestType.CLEAR, QuestType.DELIVER, QuestType.ESCORT, QuestType.LAIR -> true
    }

    private companion object {
        val LOGGER = LogUtils.getLogger()
    }
}
