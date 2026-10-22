package io.github.vaspyyy.guildmark.cooking.home

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.homestead.Homestead
import io.github.vaspyyy.guildmark.season.SeasonCrops
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent

/**
 * The homestead's kitchen (living-world/cook/design.md 6.7): the tier's pot, board, plate, oven and larder fitted into
 * the house the builders raised, and free fire for them while the homestead's people are home. Part K's; until it lands
 * no kitchen is fitted and no fire is free.
 */
object HomesteadKitchens {
    /** Part K's registration: the herb's seasons, the fit-out's event and scan, and the `cook homestead` and `cook herb` commands. */
    fun register() {
        // The herb joins the seasons' pacing at common setup, when its block and sprig exist, on both sides, so the sprig's
        // tooltip says what the server does. Common setup is on the mod bus, which this is called too early to be handed.
        val modBus = try {
            ModLoadingContext.get().activeContainer.eventBus
        } catch (e: RuntimeException) {
            null
        }
        if (modBus != null) {
            modBus.addListener(FMLCommonSetupEvent::class.java) { event -> event.enqueueWork(Runnable { seasonHerb() }) }
        } else {
            Guildmark.LOGGER.warn("The herb waits for a world to join the seasons")
        }
        // The same, once more, should common setup have been missed (it does nothing a second time)
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent::class.java) { _ -> seasonHerb() }
    }

    private var herbSeasoned = false

    /** The herb in [SeasonCrops]: best in spring and summer, slow in winter unless roofed or lit, as the vanilla crops are paced. */
    @Synchronized
    private fun seasonHerb() {
        if (herbSeasoned) return
        herbSeasoned = true
        SeasonCrops.add(CookRegistry.HERB.get(), SeasonCrops.Profile(1.15f, 1.10f, 0.80f, 0.40f), CookRegistry.HERB_SPRIG.get())
    }

    /** Fits the tier's kitchen into [home] once; the number of items fitted (stub: 0). */
    fun raise(level: ServerLevel, home: Homestead): Int = 0

    /** Whether the fire at [pos] is on a homestead's land while its people are home, so it burns no fuel (stub: no). */
    fun freeAt(level: ServerLevel, pos: BlockPos): Boolean = false

    /** The lines for the Menu board by the farmhouse door at [board] (stub: none). */
    fun pantryLines(level: ServerLevel, board: BlockPos): List<Component> = emptyList()
}
