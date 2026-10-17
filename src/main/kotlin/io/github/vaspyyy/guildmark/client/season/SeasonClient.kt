package io.github.vaspyyy.guildmark.client.season

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.season.SeasonCrops
import io.github.vaspyyy.guildmark.season.SeasonDate
import io.github.vaspyyy.guildmark.season.SeasonPayload
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent

/**
 * The client's side of the seasons: the date the server sent, and everything that shows it (the land's colours, the
 * air's petals, leaves, breath and snow, the compass's medallion, the banner as a season comes in, the tooltips on
 * seeds and crops, and the Almanac).
 */
object SeasonClient {
    /** Today, as the server last said. Before it has, the first day of spring. */
    @Volatile
    var date: SeasonDate = SeasonDate(0L, Seasons.DEFAULT_LENGTH)
        private set
    /** Whether the server has said yet in this session. */
    var known = false
        private set
    private var flags = 0

    /** Whether the seasons are on in this world (the server's say). */
    val active: Boolean get() = known && flags and SeasonPayload.SEASONS != 0
    val crops: Boolean get() = known && flags and SeasonPayload.CROPS != 0
    val snow: Boolean get() = known && flags and SeasonPayload.SNOW != 0
    val festivals: Boolean get() = known && flags and SeasonPayload.FESTIVALS != 0

    fun colours(): Boolean = active && (!GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.SEASON_COLOURS.get())
    fun particles(): Boolean = active && (!GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.SEASON_PARTICLES.get())

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterColorHandlersEvent.ColorResolvers::class.java, SeasonColours::registerResolver)
        modBus.addListener(RegisterColorHandlersEvent.BlockTintSources::class.java, SeasonColours::registerSources)
        modBus.addListener(RegisterParticleProvidersEvent::class.java, SeasonParticles::registerProviders)
        modBus.addListener(RegisterGuiLayersEvent::class.java) { event ->
            event.registerBelow(VanillaGuiLayers.BOSS_OVERLAY, SeasonHud.ID, SeasonHud)
            event.registerBelow(VanillaGuiLayers.TITLE, SeasonBanner.ID, SeasonBanner)
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
        NeoForge.EVENT_BUS.addListener(ItemTooltipEvent::class.java, ::tooltip)
        SeasonWeather.register()
    }

    fun receive(payload: SeasonPayload) {
        val was = date
        val wasKnown = known
        val wasActive = active
        date = SeasonDate(payload.calendarDay, payload.length)
        flags = payload.flags
        known = true
        if (!wasKnown || was.calendarDay != date.calendarDay || was.length != date.length || wasActive != active) {
            SeasonColours.update(date, colours())
            SeasonHud.newDay(!wasKnown)
        }
        // A new season coming in while you're about (not on joining the world)
        if (wasKnown && active && (was.season != date.season || was.year != date.year)) SeasonBanner.show(date, was)
    }

    /** The colours again, after the setting changed. */
    fun refreshColours() = SeasonColours.update(date, colours())

    private fun tick() {
        SeasonColours.tick()
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (minecraft.isPaused) return
        SeasonBanner.tick()
        if (particles() && level.dimension() == Level.OVERWORLD) SeasonParticles.tick(minecraft, level)
    }

    private fun reset() {
        known = false
        flags = 0
        date = SeasonDate(0L, Seasons.DEFAULT_LENGTH)
        SeasonColours.reset()
        SeasonBanner.reset()
        SeasonHud.reset()
    }

    // ---- Tooltips ----

    private fun tooltip(event: ItemTooltipEvent) {
        if (!crops) return
        val block = SeasonCrops.ITEMS[event.itemStack.item] ?: return
        val profile = SeasonCrops.profile(block) ?: return
        val lines = event.toolTip
        val best = profile.best.filter { profile.pace(it) > 1f }
        val seasons = (best.ifEmpty { profile.best }).map { it.title.copy().withColor(it.color) }
        if (seasons.isNotEmpty()) {
            val joined = Component.empty()
            seasons.forEachIndexed { i, c ->
                if (i > 0) joined.append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                joined.append(c)
            }
            lines.add(Component.translatable("tooltip.guildmark.season.best", joined).withStyle(ChatFormatting.GRAY))
        }
        val today = date
        val pace = SeasonCrops.pace(profile, today)
        val (word, color) = when {
            pace >= 1.08f -> "fast" to 0x8FD46A
            pace >= 0.95f -> "steady" to 0xD8D0B0
            pace >= 0.6f -> "slow" to 0xE0A050
            else -> "dormant" to 0x9AB4D8
        }
        lines.add(Component.translatable("tooltip.guildmark.season.now", Component.translatable("tooltip.guildmark.season.pace.$word").withColor(color), today.season.title.copy().withColor(today.season.color))
            .withStyle(ChatFormatting.GRAY))
        if (pace < 1f) lines.add(Component.translatable("tooltip.guildmark.season.shelter").withStyle(ChatFormatting.DARK_GRAY))
    }
}
