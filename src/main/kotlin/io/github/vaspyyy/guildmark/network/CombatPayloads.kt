package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.combat.Combat
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client starts move [move] (numbered [seq] by the client). [yaw] is the world direction of a roll.
 * [flags] say whether the attack button is still held (a heavy attack that may charge) and whether the
 * client is already showing the press as a riposte or backstab, so the server can correct a wrong guess.
 */
data class StartMovePayload(val seq: Int, val move: Int, val yaw: Float, val flags: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StartMovePayload> = TYPE

    companion object {
        const val HOLDING = 1
        const val GUESS_RIPOSTE = 2
        const val GUESS_BACKSTAB = 4

        val TYPE: CustomPacketPayload.Type<StartMovePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "start_move"))

        val STREAM_CODEC: StreamCodec<ByteBuf, StartMovePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StartMovePayload::seq,
            ByteBufCodecs.VAR_INT, StartMovePayload::move,
            ByteBufCodecs.FLOAT, StartMovePayload::yaw,
            ByteBufCodecs.VAR_INT, StartMovePayload::flags,
            ::StartMovePayload,
        )

        fun handle(payload: StartMovePayload, context: IPayloadContext) {
            Combat.onStartMove(context.player(), payload)
        }
    }
}

/** Client let go of a charging heavy attack after holding it [charge] ticks. */
data class ReleaseChargePayload(val seq: Int, val charge: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ReleaseChargePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ReleaseChargePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "release_charge"))

        val STREAM_CODEC: StreamCodec<ByteBuf, ReleaseChargePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ReleaseChargePayload::seq,
            ByteBufCodecs.VAR_INT, ReleaseChargePayload::charge,
            ::ReleaseChargePayload,
        )

        fun handle(payload: ReleaseChargePayload, context: IPayloadContext) {
            Combat.onReleaseCharge(context.player(), payload)
        }
    }
}

/** Client's swing for move [seq] reached [targets] (entity ids); the server checks and applies each. */
data class StrikePayload(val seq: Int, val targets: List<Int>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StrikePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StrikePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "strike"))

        val STREAM_CODEC: StreamCodec<ByteBuf, StrikePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StrikePayload::seq,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(16)), StrikePayload::targets,
            ::StrikePayload,
        )

        fun handle(payload: StrikePayload, context: IPayloadContext) {
            Combat.onStrike(context.player(), payload)
        }
    }
}

/**
 * Server tells a player their stamina and focus. [ackSeq] is the last move the server has counted,
 * so the client knows which of its predicted costs are already included.
 */
data class VitalsPayload(
    val stamina: Float,
    val maxStamina: Float,
    val focus: Float,
    val maxFocus: Float,
    val flags: Int,
    val ackSeq: Int,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VitalsPayload> = TYPE

    companion object {
        const val IN_COMBAT = 1
        const val GUARD_COUNTER = 2

        val TYPE: CustomPacketPayload.Type<VitalsPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "vitals"))

        val STREAM_CODEC: StreamCodec<ByteBuf, VitalsPayload> = StreamCodec.composite(
            ByteBufCodecs.FLOAT, VitalsPayload::stamina,
            ByteBufCodecs.FLOAT, VitalsPayload::maxStamina,
            ByteBufCodecs.FLOAT, VitalsPayload::focus,
            ByteBufCodecs.FLOAT, VitalsPayload::maxFocus,
            ByteBufCodecs.VAR_INT, VitalsPayload::flags,
            ByteBufCodecs.VAR_INT, VitalsPayload::ackSeq,
            ::VitalsPayload,
        )

        fun handle(payload: VitalsPayload, context: IPayloadContext) {
            CombatClient.onVitals(payload)
        }
    }
}

/**
 * Server tells clients that [entityId] started [move]: for other players it's an animation to play, for the
 * mover itself a correction (a riposte instead of a slash, a stagger, a rejected move). [extra] is a roll's
 * world direction in degrees, a released charge in ticks, or the entity id a riposte or backstab is done to.
 */
data class MovePayload(val entityId: Int, val move: Int, val seq: Int, val flags: Int, val extra: Float) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MovePayload> = TYPE

    companion object {
        /** Replaces the mover's predicted move [seq] (an attack that became a riposte or backstab, or a guess that was wrong). */
        const val OVERRIDE = 1
        /** Interrupts whatever the mover is doing (stagger, guard break, parry). */
        const val FORCED = 2
        /** A heavy attack that is still charging: hold it until a RELEASE. */
        const val CHARGING = 4
        const val RELEASE = 8
        /** The server refused move [seq]; the mover cancels it. */
        const val REJECT = 16

        val TYPE: CustomPacketPayload.Type<MovePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "move"))

        val STREAM_CODEC: StreamCodec<ByteBuf, MovePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MovePayload::entityId,
            ByteBufCodecs.VAR_INT, MovePayload::move,
            ByteBufCodecs.VAR_INT, MovePayload::seq,
            ByteBufCodecs.VAR_INT, MovePayload::flags,
            ByteBufCodecs.FLOAT, MovePayload::extra,
            ::MovePayload,
        )

        fun handle(payload: MovePayload, context: IPayloadContext) {
            CombatClient.onMove(payload)
        }
    }
}

/** Server tells clients that [entityId] staggers for [ticks] (0 ends it early). [kind] is a [io.github.vaspyyy.guildmark.combat.Stance] kind. */
data class StaggerPayload(val entityId: Int, val ticks: Int, val kind: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StaggerPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StaggerPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "stagger"))

        val STREAM_CODEC: StreamCodec<ByteBuf, StaggerPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StaggerPayload::entityId,
            ByteBufCodecs.VAR_INT, StaggerPayload::ticks,
            ByteBufCodecs.VAR_INT, StaggerPayload::kind,
            ::StaggerPayload,
        )

        fun handle(payload: StaggerPayload, context: IPayloadContext) {
            CombatClient.onStagger(payload)
        }
    }
}

/** A boss fight's big moment, for the players watching: the introduction, the change of phase, the death. */
data class BossCuePayload(val entityId: Int, val cue: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BossCuePayload> = TYPE

    companion object {
        const val INTRO = 0
        const val PHASE_TWO = 1
        const val DEATH = 2

        val TYPE: CustomPacketPayload.Type<BossCuePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "boss_cue"))

        val STREAM_CODEC: StreamCodec<ByteBuf, BossCuePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BossCuePayload::entityId,
            ByteBufCodecs.VAR_INT, BossCuePayload::cue,
            ::BossCuePayload,
        )

        fun handle(payload: BossCuePayload, context: IPayloadContext) {
            io.github.vaspyyy.guildmark.client.combat.Cutscenes.onCue(payload)
        }
    }
}
