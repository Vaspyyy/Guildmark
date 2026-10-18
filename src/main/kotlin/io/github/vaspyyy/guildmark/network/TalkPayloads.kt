package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.TalkClient
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Familiarity
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkTest
import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.Optional

/**
 * The talk screen's payloads (living-world/design-voices.md sections 2 and 8). The server opens a talk with a
 * townsperson ([TalkOpenPayload]), says something and offers the answers ([TalkSayPayload]), and ends it
 * ([TalkClosePayload]); the client picks an answer ([TalkPickPayload]), closes its screen ([TalkEndPayload]), or asks a
 * keeper for a talk from the market screen ([TalkAskPayload]). The server decides everything and the screen only shows
 * what it's sent: what a client sends counts only while the townsperson is within [REACH] of them.
 */
object TalkPayloads {
    /** How far off the townsperson may be for what the client sends to count. */
    const val REACH = 12.0

    /** The most parts one say may have, and the most answers. */
    const val MAX_PARTS = 8
    const val MAX_CHOICES = 8

    /** The longest choice id a client may send back. */
    const val MAX_ID = 128

    /** Registers the payloads; called once from where the mod's other payloads are registered. */
    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("1")
            .playToClient(TalkOpenPayload.TYPE, TalkOpenPayload.STREAM_CODEC, TalkOpenPayload::handle)
            .playToClient(TalkSayPayload.TYPE, TalkSayPayload.STREAM_CODEC, TalkSayPayload::handle)
            .playToClient(TalkClosePayload.TYPE, TalkClosePayload.STREAM_CODEC, TalkClosePayload::handle)
            .playToServer(TalkPickPayload.TYPE, TalkPickPayload.STREAM_CODEC, TalkPickPayload::handle)
            .playToServer(TalkEndPayload.TYPE, TalkEndPayload.STREAM_CODEC, TalkEndPayload::handle)
            .playToServer(TalkAskPayload.TYPE, TalkAskPayload.STREAM_CODEC, TalkAskPayload::handle)
    }

    /** Whether entity [entity] is alive in [player]'s level and within [REACH] of them. */
    fun near(player: ServerPlayer, entity: Int): Boolean {
        val e = player.level().getEntity(entity) ?: return false
        return e.isAlive && player.distanceToSqr(e) <= REACH * REACH
    }

    internal fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, path)
}

/**
 * Server tells the client a talk with entity [entity] has begun (-1: nobody, for `/guildmark talk test`): who they
 * are ([name], and what they are, [epithet]: "Mara Thatch", "baker of Hollowmere") and how well they know the player
 * ([knows]: [STRANGER], [HEARD_OF], [KNOWS] or [WARY], from [knows]). What they say comes after it, in a
 * [TalkSayPayload]. Sent again for a talk that's already open, it starts afresh with the same panel up.
 */
data class TalkOpenPayload(val entity: Int, val name: Component, val epithet: Component, val knows: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TalkOpenPayload> = TYPE

    companion object {
        /** They don't know the player: nothing beside the plate. */
        const val STRANGER = 0

        /** They've heard of the player: "has heard of you". */
        const val HEARD_OF = 1

        /** They've met the player, or more: "knows you". */
        const val KNOWS = 2

        /** They're wary of the player, or worse: "wary". */
        const val WARY = 3

        /** How the plate says someone known like [f] knows the player. */
        fun knows(f: Familiarity): Int = when (f) {
            Familiarity.STRANGER -> STRANGER
            Familiarity.HEARD_OF -> HEARD_OF
            Familiarity.MET, Familiarity.ACQUAINTED, Familiarity.FRIEND -> KNOWS
            Familiarity.WARY, Familiarity.HOSTILE -> WARY
        }

        val TYPE: CustomPacketPayload.Type<TalkOpenPayload> = CustomPacketPayload.Type(TalkPayloads.id("talk_open"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TalkOpenPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TalkOpenPayload::entity,
            ComponentSerialization.STREAM_CODEC, TalkOpenPayload::name,
            ComponentSerialization.STREAM_CODEC, TalkOpenPayload::epithet,
            ByteBufCodecs.VAR_INT, TalkOpenPayload::knows,
            ::TalkOpenPayload,
        )

        fun handle(payload: TalkOpenPayload, context: IPayloadContext) {
            TalkClient.open(payload)
        }
    }
}

/**
 * Server tells the client what entity [entity] says in their talk: the [parts], said one after another, and then the
 * answers the player may give ([choices], in order; Goodbye last). [serial] numbers the says of a talk, and a pick
 * sends back the serial of the say it answers, so a stale or doubled pick can be told apart.
 *
 * - A say with parts replaces what's on the panel; one without keeps the words and only brings new answers.
 * - No choices and not [closing]: the screen waits for the next say (and gives up after 100 ticks of silence).
 * - An empty say (no parts, no choices, not closing) changes nothing: it only tells the screen the server's still
 *   there, while the townsperson answers someone else first.
 * - [closing]: these are the last words. The screen shows them, holds a moment and closes; the talk is over on the
 *   server already, and the screen's [TalkEndPayload] after it must be let go.
 */
data class TalkSayPayload(
    val entity: Int,
    val serial: Int,
    val parts: List<TalkPart>,
    val choices: List<TalkChoice>,
    val closing: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TalkSayPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TalkSayPayload> = CustomPacketPayload.Type(TalkPayloads.id("talk_say"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TalkSayPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TalkSayPayload::entity,
            ByteBufCodecs.VAR_INT, TalkSayPayload::serial,
            TalkPart.STREAM_CODEC.apply(ByteBufCodecs.list(TalkPayloads.MAX_PARTS)), TalkSayPayload::parts,
            TalkChoice.STREAM_CODEC.apply(ByteBufCodecs.list(TalkPayloads.MAX_CHOICES)), TalkSayPayload::choices,
            ByteBufCodecs.BOOL, TalkSayPayload::closing,
            ::TalkSayPayload,
        )

        fun handle(payload: TalkSayPayload, context: IPayloadContext) {
            TalkClient.say(payload)
        }
    }
}

/**
 * One part of a say: the voiced key [line] (the take the townsperson says aloud through `say`, which the screen never
 * plays itself), or "" for words with no take; the sentence as it shows ([text]), in full; its unvoiced [detail], shown
 * after it in a softer colour, or null; and how long the take lasts ([ticks], from TalkLines; 0 for none). The screen
 * waits out each part's ticks, and 6 more before the next part, in step with the server saying them. A part with no
 * take (line "" or ticks 0) types to the tick of a pen.
 */
data class TalkPart(val line: String, val text: Component, val detail: Component?, val ticks: Int) {
    companion object {
        /**
         * The part for [speech], a line in Speech's form ("talk.market.dear_0~talk.detail.price|item.minecraft.bread|3"),
         * whose take lasts [ticks]: voiced under its key if [ticks] is more than 0, and words alone if not.
         */
        fun of(speech: String, ticks: Int): TalkPart {
            val said = speech.substringBefore('~')
            val text: Component = if (said.isEmpty()) Component.empty() else Speech.said(speech)
            val voiced = ticks > 0 && said.isNotEmpty()
            return TalkPart(if (voiced) Speech.key(speech) else "", text, Speech.detail(speech), if (voiced) ticks else 0)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TalkPart> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TalkPart::line,
            ComponentSerialization.STREAM_CODEC, TalkPart::text,
            ComponentSerialization.OPTIONAL_STREAM_CODEC, { part: TalkPart -> Optional.ofNullable(part.detail) },
            ByteBufCodecs.VAR_INT, TalkPart::ticks,
            { line: String, text: Component, detail: Optional<Component>, ticks: Int -> TalkPart(line, text, detail.orElse(null), ticks) },
        )
    }
}

/**
 * One of the answers the player may give: its [id] ("doing", "news", "trade", "name", "bye", or "<source id>:<what>"
 * for a source's own), its [label], and whether it's been [asked] this talk (dimmed, but it can still be picked).
 */
data class TalkChoice(val id: String, val label: Component, val asked: Boolean) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TalkChoice> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TalkChoice::id,
            ComponentSerialization.STREAM_CODEC, TalkChoice::label,
            ByteBufCodecs.BOOL, TalkChoice::asked,
            ::TalkChoice,
        )
    }
}

/** Client picked answer [choice] in their talk with entity [entity], answering the say numbered [serial]. */
data class TalkPickPayload(val entity: Int, val serial: Int, val choice: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TalkPickPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TalkPickPayload> = CustomPacketPayload.Type(TalkPayloads.id("talk_pick"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TalkPickPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TalkPickPayload::entity,
            ByteBufCodecs.VAR_INT, TalkPickPayload::serial,
            ByteBufCodecs.stringUtf8(TalkPayloads.MAX_ID), TalkPickPayload::choice,
            ::TalkPickPayload,
        )

        fun handle(payload: TalkPickPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            // The scripted test talk, while it's open, has the player's picks
            if (TalkTest.picked(player, payload.entity, payload.serial, payload.choice)) return
            if (TalkPayloads.near(player, payload.entity)) Talk.picked(player, payload.entity, payload.serial, payload.choice)
        }
    }
}

/**
 * Client closed its talk screen with entity [entity]: Escape (which counts as Goodbye), or the screen closed itself
 * (beyond 12 blocks, a blow, 100 ticks with nothing from the server, or after the last words of a closing say).
 */
data class TalkEndPayload(val entity: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TalkEndPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TalkEndPayload> = CustomPacketPayload.Type(TalkPayloads.id("talk_end"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TalkEndPayload> = ByteBufCodecs.VAR_INT.map(::TalkEndPayload, TalkEndPayload::entity)

        fun handle(payload: TalkEndPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            if (TalkTest.ended(player, payload.entity)) return
            if (TalkPayloads.near(player, payload.entity)) Talk.ended(player, payload.entity)
        }
    }
}

/** Client pressed the market screen's Talk button, with the keeper that's entity [entity]. */
data class TalkAskPayload(val entity: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TalkAskPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TalkAskPayload> = CustomPacketPayload.Type(TalkPayloads.id("talk_ask"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TalkAskPayload> = ByteBufCodecs.VAR_INT.map(::TalkAskPayload, TalkAskPayload::entity)

        fun handle(payload: TalkAskPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            if (TalkPayloads.near(player, payload.entity)) Talk.asked(player, payload.entity)
        }
    }
}

/**
 * Server ended the talk with entity [entity] (danger, the townsperson called away, a switch turned off): the screen
 * closes, letting the last words of a closing say finish first, and sends no [TalkEndPayload].
 */
data class TalkClosePayload(val entity: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TalkClosePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TalkClosePayload> = CustomPacketPayload.Type(TalkPayloads.id("talk_close"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TalkClosePayload> = ByteBufCodecs.VAR_INT.map(::TalkClosePayload, TalkClosePayload::entity)

        fun handle(payload: TalkClosePayload, context: IPayloadContext) {
            TalkClient.close(payload.entity)
        }
    }
}
