package io.github.vaspyyy.guildmark.guild

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/** A rival's challenge against a village the player's guild holds, as the contract tracker shows it. */
data class RivalChallenge(val anchor: BlockPos, val village: String, val rival: String, val daysLeft: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, RivalChallenge> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RivalChallenge::anchor,
            ByteBufCodecs.STRING_UTF8, RivalChallenge::village,
            ByteBufCodecs.STRING_UTF8, RivalChallenge::rival,
            ByteBufCodecs.VAR_INT, RivalChallenge::daysLeft,
            ::RivalChallenge,
        )
    }
}
