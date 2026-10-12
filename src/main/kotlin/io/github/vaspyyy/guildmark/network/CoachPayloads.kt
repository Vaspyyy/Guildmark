package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.road.Coaches
import io.github.vaspyyy.guildmark.village.VillageNames
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.WeakHashMap

/**
 * Each coach request searches the village around the player, so a client asking faster than a person
 * could click is ignored rather than allowed to stall the server.
 */
private object CoachThrottle {
    private const val TICKS = 10
    private val last = WeakHashMap<ServerPlayer, Long>()

    fun allow(player: ServerPlayer): Boolean {
        val now = player.level().gameTime
        val before = last[player]
        if (before != null && now - before in 0 until TICKS) return false
        last[player] = now
        return true
    }
}

/** Client opened the map and asks where the coaches from the village it's standing in can take it. */
object CoachStopsRequestPayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<CoachStopsRequestPayload> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "coach_stops_request"))

    val STREAM_CODEC: StreamCodec<ByteBuf, CoachStopsRequestPayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<CoachStopsRequestPayload> = TYPE

    fun handle(payload: CoachStopsRequestPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        if (!CoachThrottle.allow(player)) return
        val stops = Coaches.stops(player.level(), player.blockPosition()) ?: listOf()
        PacketDistributor.sendToPlayer(player, CoachStopsPayload(stops.map { stop ->
            CoachStop(stop.to.x, stop.to.z, stop.fare, stop.village?.let { VillageNames.of(it) } ?: "")
        }))
    }
}

/** Where a coach from here goes: the road's far end, the fare, and the village there ("" if nobody has found it). */
data class CoachStop(val x: Int, val z: Int, val fare: Int, val village: String) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, CoachStop> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CoachStop::x,
            ByteBufCodecs.VAR_INT, CoachStop::z,
            ByteBufCodecs.VAR_INT, CoachStop::fare,
            ByteBufCodecs.STRING_UTF8, CoachStop::village,
            ::CoachStop,
        )
    }
}

/** Server answers with the coach stops reachable from where the player stands (none outside a village). */
data class CoachStopsPayload(val stops: List<CoachStop>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CoachStopsPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CoachStopsPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "coach_stops"))

        val STREAM_CODEC: StreamCodec<ByteBuf, CoachStopsPayload> =
            CoachStop.STREAM_CODEC.apply(ByteBufCodecs.list()).map(::CoachStopsPayload, CoachStopsPayload::stops)

        fun handle(payload: CoachStopsPayload, context: IPayloadContext) {
            ClientHooks.coachStops(payload)
        }
    }
}

/** Client takes the coach to the road's end at ([x], [z]), picked on the map. */
data class RideCoachPayload(val x: Int, val z: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RideCoachPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RideCoachPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ride_coach"))

        val STREAM_CODEC: StreamCodec<ByteBuf, RideCoachPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RideCoachPayload::x,
            ByteBufCodecs.VAR_INT, RideCoachPayload::z,
            ::RideCoachPayload,
        )

        fun handle(payload: RideCoachPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            if (!CoachThrottle.allow(player)) return
            Coaches.ride(player, payload.x, payload.z)
        }
    }
}
