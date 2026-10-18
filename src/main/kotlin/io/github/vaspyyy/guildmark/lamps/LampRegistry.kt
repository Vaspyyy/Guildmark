package io.github.vaspyyy.guildmark.lamps

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Function
import java.util.function.UnaryOperator

/**
 * Everything the street lamps add to the game's registries: the lamp and the soul lamp. Neither has an item of its own:
 * the capitals build them, and breaking one gives the lantern it was (its loot table). Their looks are the game's own
 * lantern models with art/scripts/lamp_art.py's drawings laid over them for the lamp going out and coming on.
 */
object LampRegistry {
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Guildmark.MOD_ID)

    /** A capital's street lamp: a lantern standing on its post, lit at dusk and put out at dawn. */
    val STREET_LAMP: DeferredBlock<StreetLampBlock> =
        BLOCKS.registerBlock("street_lamp", Function { StreetLampBlock(it, soul = false) }, props(soul = false))

    /** A street lamp with a soul lantern, as one lamp post in eight out in a capital's country has. */
    val SOUL_STREET_LAMP: DeferredBlock<StreetLampBlock> =
        BLOCKS.registerBlock("soul_street_lamp", Function { StreetLampBlock(it, soul = true) }, props(soul = true))

    // A lantern's own, with the light going by whether it's lit. Explicit UnaryOperator: a bare lambda is ambiguous with the
    // Supplier overload in Kotlin.
    private fun props(soul: Boolean) = UnaryOperator<BlockBehaviour.Properties> {
        it.mapColor(MapColor.METAL).forceSolidOn().strength(3.5f).sound(SoundType.LANTERN)
            .lightLevel { state -> StreetLampBlock.brightness(state, soul) }.noOcclusion().pushReaction(PushReaction.POPPED)
    }
}
