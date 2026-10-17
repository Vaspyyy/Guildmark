package io.github.vaspyyy.guildmark.season

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.client.season.SeasonClient
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/**
 * The calendar: four seasons of [length] days, counted from the world's own day ([VillageBoards.day], which sleeping
 * moves on) plus an offset an op can set. The server keeps it and tells every client the day; clients never work it
 * out for themselves.
 *
 * For the Living World and anything else that wants to know the date:
 * - [today] gives the date (year, season, day of the season, phase).
 * - [onChange] is told when the season turns (with the old and new dates); [onNewDay] every new day.
 * - [temperate] says how much a place feels the seasons (1 in the temperate lands, little in deserts, jungles and the
 *   frozen north), which the colours, the snow and the crops all go by.
 */
object Seasons {
    /** Listeners told when the season turns: (overworld, the last day seen, today). */
    private val seasonListeners = CopyOnWriteArrayList<(ServerLevel, SeasonDate, SeasonDate) -> Unit>()
    /** Listeners told each new day: (overworld, yesterday, today). */
    private val dayListeners = CopyOnWriteArrayList<(ServerLevel, SeasonDate, SeasonDate) -> Unit>()

    /** The day each server last saw, so a new day is noticed once; and the switches it last told everyone. */
    private var lastSeen: SeasonDate? = null
    private var lastFlags = -1

    fun register(modBus: IEventBus) {
        SeasonRegistry.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) event.accept(SeasonRegistry.ALMANAC.get())
        }
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1").playToClient(SeasonPayload.TYPE, SeasonPayload.STREAM_CODEC, SeasonPayload::handle)
        }
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (level.dimension() == Level.OVERWORLD && level.gameTime % 20L == 7L) tick(level)
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            send(player)
            giveAlmanac(player)
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::send) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::send) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            lastSeen = null
            lastFlags = -1
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { SeasonCommand.register(it.dispatcher) }
        SeasonCrops.register()
        SeasonSnow.register(modBus)
    }

    // ---- Switches ----

    /** Whether the seasons show and are felt at all (colours, weather, snow, crops, the banner and the compass). */
    fun enabled(): Boolean = setting { GuildmarkConfig.SEASONS.get() }

    fun length(): Int = try {
        GuildmarkConfig.SEASON_LENGTH.get()
    } catch (e: IllegalStateException) {
        DEFAULT_LENGTH
    }

    fun cropsEnabled(): Boolean = enabled() && setting { GuildmarkConfig.SEASONAL_CROPS.get() }
    fun snowEnabled(): Boolean = enabled() && setting { GuildmarkConfig.SEASONAL_SNOW.get() }

    /** Whether the grand festivals are held (they keep to the calendar even with the seasons' effects off). */
    fun festivalsEnabled(): Boolean = setting { GuildmarkConfig.FESTIVALS.get() }

    private inline fun setting(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }

    const val DEFAULT_LENGTH = 9

    // ---- The date ----

    /** Today's date, on either side (a client has it from the server). */
    fun today(level: Level): SeasonDate {
        if (level.isClientSide) return SeasonClient.date
        val server = level.server ?: return SeasonDate(VillageBoards.day(level), length())
        return today(server)
    }

    fun today(server: MinecraftServer): SeasonDate {
        val overworld = server.overworld()
        return SeasonDate(VillageBoards.day(overworld) + SeasonState.get(overworld).offset, length())
    }

    /** Sets the calendar so today is day [day] of [season] (this year), telling everyone. */
    fun setDate(level: ServerLevel, season: Season, day: Int) {
        val now = today(level)
        val target = now.startOf(season).plus((day.coerceIn(1, now.length) - 1).toLong())
        val state = SeasonState.get(level.server.overworld())
        state.offset += target.calendarDay - now.calendarDay
        state.setDirty()
        tick(level.server.overworld())
    }

    /** Called with (overworld, old, new) whenever the season turns, including by command. */
    fun onChange(listener: (ServerLevel, SeasonDate, SeasonDate) -> Unit) {
        seasonListeners.add(listener)
    }

    /** Called with (overworld, yesterday, today) at the start of every calendar day. */
    fun onNewDay(listener: (ServerLevel, SeasonDate, SeasonDate) -> Unit) {
        dayListeners.add(listener)
    }

    private fun tick(overworld: ServerLevel) {
        val now = today(overworld.server)
        val last = lastSeen
        lastSeen = now
        // A switch flipped in the settings reaches everyone within a second
        val flags = flags()
        val flagsChanged = lastFlags != -1 && flags != lastFlags
        lastFlags = flags
        if (last == null) return
        if (last.calendarDay == now.calendarDay && last.length == now.length) {
            if (flagsChanged) for (player in overworld.server.playerList.players) send(player, now)
            return
        }
        for (player in overworld.server.playerList.players) send(player, now)
        for (listener in dayListeners) runCatching { listener(overworld, last, now) }.onFailure { Guildmark.LOGGER.warn("A new-day listener failed", it) }
        if (last.season != now.season || last.year != now.year) {
            for (listener in seasonListeners) runCatching { listener(overworld, last, now) }.onFailure { Guildmark.LOGGER.warn("A season listener failed", it) }
        }
    }

    /** Tells [player] the date and what's switched on. */
    fun send(player: ServerPlayer) = send(player, today(player.level().server))

    private fun send(player: ServerPlayer, date: SeasonDate) {
        PacketDistributor.sendToPlayer(player, SeasonPayload(date.calendarDay, date.length, flags()))
    }

    private fun flags(): Int {
        var flags = 0
        if (enabled()) flags = flags or SeasonPayload.SEASONS
        if (cropsEnabled()) flags = flags or SeasonPayload.CROPS
        if (snowEnabled()) flags = flags or SeasonPayload.SNOW
        if (festivalsEnabled()) flags = flags or SeasonPayload.FESTIVALS
        return flags
    }

    /** A player's first time in the world with the seasons on: the Almanac, to see what they bring. */
    private fun giveAlmanac(player: ServerPlayer) {
        if (!enabled() || player.getData(SeasonRegistry.ALMANAC_GIVEN.get())) return
        player.setData(SeasonRegistry.ALMANAC_GIVEN.get(), true)
        player.inventory.placeItemBackInInventory(ItemStack(SeasonRegistry.ALMANAC.get()), Prediction.SERVER_ONLY)
    }

    /** Sends everyone the date again (after a command or a settings change). */
    fun resend(server: MinecraftServer) {
        for (player in server.playerList.players) send(player)
    }

    // ---- Climate ----

    /**
     * How much the seasons are felt in [biome], 0 to 1: fully in the temperate lands; little in the hot and dry
     * (deserts, badlands, savanna), the hot and wet (jungle) and the frozen lands, which are much the same all year.
     */
    fun temperate(biome: Biome): Float = temperate(biome.baseTemperature, biome.getModifiedClimateSettings().downfall(), biome.hasPrecipitation())

    fun temperate(temperature: Float, downfall: Float, precipitation: Boolean): Float = when {
        !precipitation -> 0.12f
        temperature >= 0.9f && downfall >= 0.8f -> 0.18f
        temperature > 1.0f -> 0.2f
        temperature < 0.15f -> 0.3f
        else -> 1f
    }

    fun temperate(level: Level, pos: BlockPos): Float = if (level.dimension() != Level.OVERWORLD) 0f else temperate(level.getBiome(pos).value())
}

/** The calendar's offset from the world's own day, kept with the overworld. */
class SeasonState(var offset: Long = 0L) : SavedData() {
    companion object {
        private val CODEC: Codec<SeasonState> = RecordCodecBuilder.create { i ->
            i.group(Codec.LONG.optionalFieldOf("offset", 0L).forGetter(SeasonState::offset)).apply(i, ::SeasonState)
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "seasons"), Supplier { SeasonState() }, CODEC)

        fun get(level: ServerLevel): SeasonState = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}

/** Server tells a client the calendar day, the season length and which seasonal effects are on. */
data class SeasonPayload(val calendarDay: Long, val length: Int, val flags: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SeasonPayload> = TYPE

    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        const val SEASONS = 1
        const val CROPS = 2
        const val SNOW = 4
        const val FESTIVALS = 8

        val TYPE: CustomPacketPayload.Type<SeasonPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "season"))

        val STREAM_CODEC: StreamCodec<ByteBuf, SeasonPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SeasonPayload::calendarDay,
            ByteBufCodecs.VAR_INT, SeasonPayload::length,
            ByteBufCodecs.VAR_INT, SeasonPayload::flags,
            ::SeasonPayload,
        )

        fun handle(payload: SeasonPayload, context: IPayloadContext) {
            SeasonClient.receive(payload)
        }
    }
}
