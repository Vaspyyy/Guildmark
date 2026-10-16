package io.github.vaspyyy.guildmark.ship

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.netty.buffer.ByteBuf
import net.minecraft.core.GlobalPos
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.Item
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.Optional
import java.util.function.UnaryOperator

/**
 * Everything the ships register. The cog's model and textures come from art/scripts/ship.py, its sounds from
 * art/audio/make_ship_audio.py (listed in assets/guildmark/sounds.json), the sailing HUD and the helmsman's
 * poses from art/scripts/ship_hud.py and art/scripts/ship_anims.py.
 */
object ShipRegistry {
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val COMPONENTS: DeferredRegister.DataComponents = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Guildmark.MOD_ID)
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    /** A cog on the water. Its own box is its middle; [CogPart]s cover the rest of the hull. */
    val COG: DeferredHolder<EntityType<*>, EntityType<Cog>> = ENTITIES.register("cog") { ->
        EntityType.Builder.of(::Cog, MobCategory.MISC)
            .sized(3.0f, 1.6f)
            .clientTrackingRange(12)
            .updateInterval(2)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, id("cog")))
    }

    /** A cog packed away, its hold and name with it: set it on open water to sail it. */
    val COG_ITEM: DeferredItem<CogItem> = ITEMS.registerItem("cog", ::CogItem, UnaryOperator<Item.Properties> { it.stacksTo(1) })

    /** How sound a packed cog's hull is (missing means whole). */
    val HULL: DeferredHolder<DataComponentType<*>, DataComponentType<Float>> = COMPONENTS.registerComponentType(
        "cog_hull",
        UnaryOperator<DataComponentType.Builder<Float>> { it.persistent(Codec.FLOAT).networkSynchronized(ByteBufCodecs.FLOAT) },
    )

    /** Whether a packed cog carries a Maelgrasp's beak on her stem. */
    val FIGUREHEAD: DeferredHolder<DataComponentType<*>, DataComponentType<Boolean>> = COMPONENTS.registerComponentType(
        "cog_figurehead",
        UnaryOperator<DataComponentType.Builder<Boolean>> { it.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL) },
    )

    /** Where a player last left their cog, for the compass and the map. Kept through death, synced to its player. */
    val BERTH: DeferredHolder<AttachmentType<*>, AttachmentType<Berth>> = ATTACHMENTS.register("berth") { ->
        AttachmentType.builder { -> Berth() }
            .serialize(Berth.CODEC.fieldOf("berth"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, Berth.STREAM_CODEC)
            .build()
    }

    // ---- Sounds ----

    /** A sound heard as far as its loudness carries it, or out to [range] blocks whatever its loudness (one carrying over water). */
    private fun sound(name: String, range: Float? = null): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> if (range != null) SoundEvent.createFixedRangeEvent(id(name), range) else SoundEvent.createVariableRangeEvent(id(name)) }

    val CREAK = sound("ship.creak")
    val SAIL_FILL = sound("ship.sail_fill")
    val LUFF = sound("ship.luff")
    val HOIST = sound("ship.hoist")
    val WAKE = sound("ship.wake")
    val LAP = sound("ship.lap")
    val RIGGING = sound("ship.rigging")
    val ANCHOR_DROP = sound("ship.anchor_drop", 48f)
    val ANCHOR_RAISE = sound("ship.anchor_raise")
    val BELL = sound("ship.bell", 64f)
    val SCRAPE = sound("ship.scrape")
    val BUMP = sound("ship.bump")
    val LAUNCH = sound("ship.launch", 48f)
    val PACK = sound("ship.pack")
    val WHEEL = sound("ship.wheel")
    val REPAIR = sound("ship.repair")
    val WRECK = sound("ship.wreck", 64f)
    val HOLD_OPEN = sound("ship.hold_open")
    val HOLD_CLOSE = sound("ship.hold_close")

    fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)
}

/** Where a player last stepped off their cog, and what she's called ("" for no name). */
data class Berth(val at: Optional<GlobalPos> = Optional.empty(), val name: String = "") {
    companion object {
        val CODEC: Codec<Berth> = RecordCodecBuilder.create { i ->
            i.group(
                GlobalPos.CODEC.optionalFieldOf("at").forGetter(Berth::at),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Berth::name),
            ).apply(i, ::Berth)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, Berth> = StreamCodec.composite(
            ByteBufCodecs.optional(GlobalPos.STREAM_CODEC), Berth::at,
            ByteBufCodecs.STRING_UTF8, Berth::name,
            ::Berth,
        )
    }
}
