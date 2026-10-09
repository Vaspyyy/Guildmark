package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
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

    /** Progress and deadline of a taken contract. */
    val CONTRACT_STATE: DeferredHolder<DataComponentType<*>, DataComponentType<ContractState>> = COMPONENTS.registerComponentType(
        "contract_state",
        UnaryOperator<DataComponentType.Builder<ContractState>> {
            it.persistent(ContractState.CODEC).networkSynchronized(ByteBufCodecs.fromCodec(ContractState.CODEC))
        },
    )
}
