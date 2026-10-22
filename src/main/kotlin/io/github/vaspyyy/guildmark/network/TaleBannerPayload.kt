package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.atlas.TaleBanner
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.TaleRows
import io.github.vaspyyy.guildmark.tales.TaleWords
import io.github.vaspyyy.guildmark.tales.Templates
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * A tale's banner across the upper screen (living-world/stories/design.md 5.3, Part D): [kind] says which (one of the four below,
 * with [TEST] added when `tale banner` sent it), [titleKey] with its [titleArgs] the big line and [subKey] with its [subArgs] the
 * small one under it, all language keys the client puts in its own tongue. The server sends one to each player who is IN the tale
 * at that moment (the end's, to each who was IN or DONE) and to nobody else; the client holds it until nothing else is across the
 * screen, shows it, and plays its sound. About eighty bytes.
 *
 * The lines: a part taken up is the tale's name over "A tale begins in <Town>"; a step turned is the step's headline over the
 * tale's name; an ending is its line over the tale's name, "Ended". For an ending [subKey] is the tale's name and the client adds
 * the word, so the one key serves the two.
 */
data class TaleBannerPayload(
    val kind: Int,
    val titleKey: String,
    val titleArgs: List<String>,
    val subKey: String,
    val subArgs: List<String>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TaleBannerPayload> = TYPE

    /** Which of the four this is, without the test flag. */
    val banner: Int get() = kind and TEST.inv()

    /** Whether `tale banner` sent it. */
    val test: Boolean get() = kind and TEST != 0

    companion object {
        /** A part was taken up: the tale begins (5.5 s, the begin chime). */
        const val BEGIN = 0

        /** A step turned (3.5 s, quieter). */
        const val STEP = 1

        /** The tale ended well, at A or B (6 s, the seal). */
        const val GOOD = 2

        /** The tale ended ill, at C or W (6 s, the toll). */
        const val ILL = 3

        /** Added to the kind by `tale banner`, so the client can say so when its setting keeps the banner from showing. */
        const val TEST = 8

        val TYPE: CustomPacketPayload.Type<TaleBannerPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tale_banner"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TaleBannerPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TaleBannerPayload::kind,
            ByteBufCodecs.STRING_UTF8, TaleBannerPayload::titleKey,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TaleBannerPayload::titleArgs,
            ByteBufCodecs.STRING_UTF8, TaleBannerPayload::subKey,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TaleBannerPayload::subArgs,
            ::TaleBannerPayload,
        )

        fun handle(payload: TaleBannerPayload, context: IPayloadContext) {
            TaleBanner.receive(payload)
        }

        /** At setup (`TaleRows.register`): the payload goes to clients, with or without tales ready, so both sides always know it. */
        fun register(modBus: IEventBus) {
            modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
                event.registrar("1").playToClient(TYPE, STREAM_CODEC, ::handle)
            }
        }

        // ---- The banners, as the client is told them ----

        /** The beginning of the [template] tale in [town] (blank when the town's name is not to be had: "A tale begins"). */
        fun beginning(template: String, town: String): TaleBannerPayload = TaleBannerPayload(
            BEGIN, TaleKeys.title(template), listOf(),
            if (town.isBlank()) "gui.guildmark.tale.banner.begin_bare" else "gui.guildmark.tale.banner.begin",
            if (town.isBlank()) listOf() else listOf(town),
        )

        /** [step] of the [template] tale begun: its headline over the tale's name. */
        fun turning(template: String, step: Int): TaleBannerPayload =
            TaleBannerPayload(STEP, TaleKeys.banner(template, step), listOf(), TaleKeys.title(template), listOf())

        /** The [template] tale ended, with the ending whose word is [word], well or ill: its line, over the tale's name. */
        fun ending(template: String, word: String, good: Boolean): TaleBannerPayload =
            TaleBannerPayload(if (good) GOOD else ILL, TaleKeys.ended(template, word), listOf(), TaleKeys.title(template), listOf())

        // ---- Who is told, and when ----

        /** How near after a part is taken up (game ticks) the turn of the step that saying yes ended is counted as the beginning's own. */
        private const val JOIN_TURN = 200L

        /** When each player took up each tale, for a turn that follows at once; memory only. */
        private val joinedAt = HashMap<UUID, HashMap<Int, Long>>()

        /** The last turn each player was told of in each tale, as game time and step together, so one that came twice in one call is told once; memory only. */
        private val turned = HashMap<UUID, HashMap<Int, Long>>()

        /** [player] took up [t] (`Tales.Listener.joined`): the tale begins for them, and only for them. */
        fun begin(level: ServerLevel, t: Tale, player: ServerPlayer) {
            joinedAt.getOrPut(player.uuid) { HashMap() }[t.id] = level.gameTime
            PacketDistributor.sendToPlayer(player, beginning(t.template, TaleWords.town(level, t.capital)))
        }

        /**
         * [t] moved to another step (`Tales.Listener.stepped`): everyone following it is told the step's headline, except one who
         * took the tale up this moment (the turn that saying yes made is the beginning's own, and the tracker says what is next),
         * and a step reached twice in one call is told once.
         */
        fun step(level: ServerLevel, t: Tale) {
            val now = level.gameTime
            for (player in following(level, t)) {
                val joined = joinedAt[player.uuid]?.remove(t.id)
                if (joined != null && now - joined <= JOIN_TURN) continue
                val stamp = now * 64L + t.step
                val last = turned.getOrPut(player.uuid) { HashMap() }
                if (last[t.id] == stamp) continue
                last[t.id] = stamp
                PacketDistributor.sendToPlayer(player, turning(t.template, t.step))
            }
        }

        /**
         * Stage 7 of `Tales.finish` (6.7): every player who was IN or DONE is told the tale ended, with the ending's line and its
         * sound, and nobody else. Runs once for a tale; the engine guards it.
         */
        fun send(c: Ctx, t: Tale) {
            val template = Templates.find(t.template) ?: return
            val word = template.ending(t.ending)?.word ?: return
            val banner = ending(t.template, word, t.ending == Ending.A || t.ending == Ending.B)
            for (player in following(c.level, t)) PacketDistributor.sendToPlayer(player, banner)
            for (map in joinedAt.values) map.remove(t.id)
            for (map in turned.values) map.remove(t.id)
        }

        /** The online players whose part of [t] is IN or DONE. */
        private fun following(level: ServerLevel, t: Tale): List<ServerPlayer> =
            t.parts.filter { TaleRows.following(it) }.mapNotNull { level.server.playerList.getPlayer(it.player) }

        /** [player] left: what is remembered of them goes. */
        fun forget(player: UUID) {
            joinedAt.remove(player)
            turned.remove(player)
        }

        /** The server stopped, or tales went off: nothing is remembered. */
        fun forgetAll() {
            joinedAt.clear()
            turned.clear()
        }
    }
}
