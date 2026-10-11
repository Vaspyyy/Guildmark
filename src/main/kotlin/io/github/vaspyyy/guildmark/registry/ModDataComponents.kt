package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import com.mojang.serialization.Codec
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

object ModDataComponents {
    val COMPONENTS: DeferredRegister.DataComponents = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Guildmark.MOD_ID)

    /** The quest a contract item carries. */
    val QUEST_NOTE: DeferredHolder<DataComponentType<*>, DataComponentType<QuestNote>> = COMPONENTS.registerComponentType(
        "quest_note",
        UnaryOperator<DataComponentType.Builder<QuestNote>> {
            it.persistent(QuestNote.CODEC).networkSynchronized(ByteBufCodecs.fromCodec(QuestNote.CODEC))
        },
    )

    /** Which shard of the Hollow Crown (0 to 6) a crown shard is. */
    val SHARD: DeferredHolder<DataComponentType<*>, DataComponentType<Int>> = COMPONENTS.registerComponentType(
        "shard",
        UnaryOperator<DataComponentType.Builder<Int>> { it.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT) },
    )

    /** Marks Wren's shard compass, which the main quest keeps pointed at the next shard. */
    val SHARD_COMPASS: DeferredHolder<DataComponentType<*>, DataComponentType<Boolean>> = COMPONENTS.registerComponentType(
        "shard_compass",
        UnaryOperator<DataComponentType.Builder<Boolean>> { it.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL) },
    )

    /** Progress and deadline of a taken contract. */
    val CONTRACT_STATE: DeferredHolder<DataComponentType<*>, DataComponentType<ContractState>> = COMPONENTS.registerComponentType(
        "contract_state",
        UnaryOperator<DataComponentType.Builder<ContractState>> {
            it.persistent(ContractState.CODEC).networkSynchronized(ByteBufCodecs.fromCodec(ContractState.CODEC))
        },
    )
}
