package io.github.vaspyyy.guildmark.registry

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.wardfire.WardfireFeature
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.feature.Feature
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/** Worldgen feature types. Where they generate lives in data/guildmark/worldgen and the biome modifiers. */
object ModFeatures {
    val FEATURE_TYPES: DeferredRegister<MapCodec<out Feature>> = DeferredRegister.create(Registries.FEATURE_TYPE, Guildmark.MOD_ID)

    val WARDFIRE: DeferredHolder<MapCodec<out Feature>, MapCodec<WardfireFeature>> = FEATURE_TYPES.register("wardfire") { -> WardfireFeature.CODEC }
}
