package io.github.vaspyyy.guildmark.cooking

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Hearthcraft's payloads, all server to client (living-world/cook/design.md 1.8, 7.4, 9.4): what a cook is doing, so
 * everyone who sees them plays the motion ([CookActionPayload]), that a cook in the middle of a clip struck at something
 * ([CookStrikePayload]), and what a capital's inn served, so the town's diners can be shown eating it ([ServedPayload]).
 * No click is ever sent to the server: every one is an ordinary block, item or entity use, which the game checks for reach.
 *
 * The handlers do not name a client class: the client sets [onAction], [onStrike] and [onServed] when it starts (Part E's
 * and Part I3's), so a dedicated server loads nothing it should not. Until it does, a payload is read and dropped.
 */
object CookPayloads {
    /** The client's handler for [CookActionPayload], set by Part E's `CookClient.register`; null on a server and until then. */
    @Volatile
    var onAction: ((CookActionPayload) -> Unit)? = null

    /** The client's handler for [CookStrikePayload], set by Part E's `CookClient.register`; null on a server and until then. */
    @Volatile
    var onStrike: ((CookStrikePayload) -> Unit)? = null

    /** The client's handler for [ServedPayload], set by Part I3; null on a server and until then. */
    @Volatile
    var onServed: ((ServedPayload) -> Unit)? = null

    /** Registers the payloads; called from [Cooking.register] when the game asks for them. Version 2: the action without its unused start tick, and the strike. */
    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("2")
            .playToClient(CookActionPayload.TYPE, CookActionPayload.STREAM_CODEC, CookActionPayload::handle)
            .playToClient(CookStrikePayload.TYPE, CookStrikePayload.STREAM_CODEC, CookStrikePayload::handle)
            .playToClient(ServedPayload.TYPE, ServedPayload.STREAM_CODEC, ServedPayload::handle)
    }

    /** A payload's id, under this mod. */
    internal fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, path)
}

/**
 * Server tells the client that the player with entity id [player] is doing [action] at [pos] (Part E plays the clip; 1.8).
 * Sent to the players tracking the cook, at most six a second a cook. Each client times the clip by its own clock from
 * the moment the word arrives (a meal by the use state), so no start tick is sent.
 */
class CookActionPayload(val player: Int, val action: CookAction, val pos: BlockPos) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CookActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CookActionPayload> = CustomPacketPayload.Type(CookPayloads.id("cook_action"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, CookActionPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.player)
                buf.writeVarInt(p.action.ordinal)
                buf.writeBlockPos(p.pos)
            },
            { buf ->
                CookActionPayload(buf.readVarInt(), CookAction.entries.getOrElse(buf.readVarInt()) { CookAction.ADD }, buf.readBlockPos())
            },
        )

        fun handle(payload: CookActionPayload, context: IPayloadContext) {
            CookPayloads.onAction?.invoke(payload)
        }
    }
}

/**
 * Server tells the client that the player with entity id [player], in the middle of a clip, struck at something: hit a
 * creature or began to break a block (1.8). Their swing is then an attack's, not the station click's that every clip
 * begins with, so Part E shows it over the clip, which plays on to its end. Sent to the players tracking the cook (the
 * cook's own client knows its attacks), at most one in four ticks a cook.
 */
class CookStrikePayload(val player: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CookStrikePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CookStrikePayload> = CustomPacketPayload.Type(CookPayloads.id("cook_strike"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, CookStrikePayload> = StreamCodec.of(
            { buf, p -> buf.writeVarInt(p.player) },
            { buf -> CookStrikePayload(buf.readVarInt()) },
        )

        fun handle(payload: CookStrikePayload, context: IPayloadContext) {
            CookPayloads.onStrike?.invoke(payload)
        }
    }
}

/**
 * Server tells the client what the inn of the capital centred on [capital] served at its last meal hour (7.4, Part G
 * sends it and Part I3 shows it): the [window] of the meal, the [inn]'s box, how many portions of each kind were served
 * ([counts], in [DishKind] order) and the best tier of each ([best], as [Tier] ordinals). About 70 bytes.
 */
class ServedPayload(val capital: Long, val window: Int, val inn: BoundingBox, val counts: IntArray, val best: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ServedPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ServedPayload> = CustomPacketPayload.Type(CookPayloads.id("cook_served"))

        /** The most kinds a payload may name, whatever it says. */
        private const val KINDS = 64

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ServedPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarLong(p.capital)
                buf.writeVarInt(p.window)
                buf.writeInt(p.inn.minX())
                buf.writeInt(p.inn.minY())
                buf.writeInt(p.inn.minZ())
                buf.writeInt(p.inn.maxX())
                buf.writeInt(p.inn.maxY())
                buf.writeInt(p.inn.maxZ())
                buf.writeVarInt(p.counts.size)
                for (n in p.counts) buf.writeVarInt(n)
                buf.writeByteArray(p.best)
            },
            { buf ->
                val capital = buf.readVarLong()
                val window = buf.readVarInt()
                val inn = BoundingBox(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt())
                val counts = IntArray(buf.readVarInt().coerceIn(0, KINDS)) { buf.readVarInt() }
                ServedPayload(capital, window, inn, counts, buf.readByteArray(KINDS))
            },
        )

        fun handle(payload: ServedPayload, context: IPayloadContext) {
            CookPayloads.onServed?.invoke(payload)
        }
    }
}
