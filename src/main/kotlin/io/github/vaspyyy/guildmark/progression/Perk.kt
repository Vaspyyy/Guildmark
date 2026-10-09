package io.github.vaspyyy.guildmark.progression

import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes

/** Perks bought with guild perk points. Attribute perks change stats; the rest are read by the contract rules. */
enum class Perk(
    val id: String,
    val maxRank: Int,
    val perRank: Double,
    val attribute: Holder<Attribute>? = null,
    val operation: AttributeModifier.Operation = AttributeModifier.Operation.ADD_VALUE,
) {
    VITALITY("vitality", 5, 2.0, Attributes.MAX_HEALTH),
    MIGHT("might", 3, 1.0, Attributes.ATTACK_DAMAGE),
    SWIFTNESS("swiftness", 3, 0.05, Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
    MINER("miner", 3, 0.15, Attributes.BLOCK_BREAK_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
    REACH("reach", 2, 0.5, Attributes.BLOCK_INTERACTION_RANGE),
    FORTUNE("fortune", 3, 1.0, Attributes.LUCK),

    /** +10% Guild Marks per rank on turn-in. */
    HAGGLER("haggler", 3, 0.1),

    /** +1 day on every contract deadline per rank. */
    PATHFINDER("pathfinder", 2, 1.0);

    val title: Component get() = Component.translatable("perk.guildmark.$id")
    val description: Component get() = Component.translatable("perk.guildmark.$id.desc")

    companion object {
        fun byId(id: String): Perk? = entries.firstOrNull { it.id == id }
    }
}
