package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.trade.carry.CartHitch
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * What the Chapman's Cart (living-world/trader/design.md, Part 3; build part D2) is asked for from the client. Both only ask: the server
 * decides in full ([CartHitch]), checking that the cart is the player's, parked, in reach and in their level. They are registered by
 * [CartHitch.register]. Folding the canvas and opening the cart need no message of their own: they are a use of the cart, which the game sends.
 */

/** Client to server: the player used an empty hand on nothing (the air): they let go of the shafts they hold. */
object CartReleasePayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<CartReleasePayload> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cart_release"))

    val STREAM_CODEC: StreamCodec<ByteBuf, CartReleasePayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<CartReleasePayload> = TYPE

    fun handle(payload: CartReleasePayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        CartHitch.releaseBy(player)
    }
}

/** Client to server: the player aimed at the shafts of the cart [id] and used an empty hand (a thin stick is easy to miss): they take them up. */
data class CartTakePayload(val id: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CartTakePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CartTakePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cart_take"))

        val STREAM_CODEC: StreamCodec<ByteBuf, CartTakePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CartTakePayload::id,
            ::CartTakePayload,
        )

        fun handle(payload: CartTakePayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            CartHitch.takeBy(player, payload.id)
        }
    }
}
