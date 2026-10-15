package io.github.vaspyyy.guildmark.registry

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalBeard
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.CapitalPlacement
import io.github.vaspyyy.guildmark.city.CapitalStructure
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/** Structure types. Where they generate lives in data/guildmark/worldgen/structure and structure_set. */
object ModStructures {
    val STRUCTURE_TYPES: DeferredRegister<StructureType<*>> = DeferredRegister.create(Registries.STRUCTURE_TYPE, Guildmark.MOD_ID)
    val PIECE_TYPES: DeferredRegister<StructurePieceType> = DeferredRegister.create(Registries.STRUCTURE_PIECE, Guildmark.MOD_ID)
    val PLACEMENTS: DeferredRegister<MapCodec<out StructurePlacement>> = DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, Guildmark.MOD_ID)

    val CAPITAL: DeferredHolder<StructureType<*>, StructureType<CapitalStructure>> =
        STRUCTURE_TYPES.register("capital") { -> StructureType { CapitalStructure.CODEC } }

    val CAPITAL_PIECE: DeferredHolder<StructurePieceType, StructurePieceType> =
        PIECE_TYPES.register("capital") { -> StructurePieceType { _, tag -> CapitalPiece(tag) } }

    val CAPITAL_BEARD: DeferredHolder<StructurePieceType, StructurePieceType> =
        PIECE_TYPES.register("capital_beard") { -> StructurePieceType { _, tag -> CapitalBeard(tag) } }

    val CAPITAL_PLACEMENT: DeferredHolder<MapCodec<out StructurePlacement>, MapCodec<CapitalPlacement>> =
        PLACEMENTS.register("capital") { -> CapitalPlacement.CODEC }
}
