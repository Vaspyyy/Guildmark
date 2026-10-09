package io.github.vaspyyy.guildmark.registry

import com.google.common.collect.ImmutableSet
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.BoardPart
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.item.trading.TradeSet
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/** The Guildmaster works at a Quest Board and sells gear for Guild Marks. Trades live in data/guildmark/trade_set. */
object ModVillagers {
    val POI_TYPES: DeferredRegister<PoiType> = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, Guildmark.MOD_ID)
    val PROFESSIONS: DeferredRegister<VillagerProfession> = DeferredRegister.create(Registries.VILLAGER_PROFESSION, Guildmark.MOD_ID)

    // Only the anchor cell counts as the job site, so one board is one job
    val GUILDMASTER_POI: DeferredHolder<PoiType, PoiType> = POI_TYPES.register("guildmaster") { ->
        val states = ModBlocks.QUEST_BOARD.get().stateDefinition.possibleStates
            .filter { it.getValue(QuestBoardBlock.PART) == BoardPart.ANCHOR }
            .toSet()
        PoiType(states, 1, 1)
    }

    val GUILDMASTER: DeferredHolder<VillagerProfession, VillagerProfession> = PROFESSIONS.register("guildmaster") { ->
        val tradeSets: Int2ObjectMap<ResourceKey<TradeSet>> = Int2ObjectOpenHashMap()
        for (level in 1..5) {
            tradeSets.put(level, ResourceKey.create(Registries.TRADE_SET, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "guildmaster/level_$level")))
        }
        VillagerProfession(
            Component.translatable("entity.guildmark.villager.guildmaster"),
            { it.`is`(GUILDMASTER_POI.key!!) },
            { it.`is`(GUILDMASTER_POI.key!!) },
            ImmutableSet.of(),
            ImmutableSet.of(),
            SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
            tradeSets,
        )
    }
}
