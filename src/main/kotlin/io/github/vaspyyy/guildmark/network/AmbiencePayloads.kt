package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.ambience.District
import io.github.vaspyyy.guildmark.client.ambience.CityAmbience
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Where in a city the player stands, for their city sounds (see CityDistricts). */
data class CityDistrictPayload(val capital: Boolean, val district: District) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CityDistrictPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CityDistrictPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "city_district"))

        private val DISTRICT: StreamCodec<ByteBuf, District> =
            ByteBufCodecs.VAR_INT.map({ District.entries.getOrElse(it) { District.NONE } }, District::ordinal)

        val STREAM_CODEC: StreamCodec<ByteBuf, CityDistrictPayload> = StreamCodec.composite(
            ByteBufCodecs.BOOL, CityDistrictPayload::capital,
            DISTRICT, CityDistrictPayload::district,
            ::CityDistrictPayload,
        )

        fun handle(payload: CityDistrictPayload, context: IPayloadContext) {
            CityAmbience.hint(payload.capital, payload.district)
        }
    }
}
