package io.github.vaspyyy.guildmark.cooking

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.townsfolk.Days
import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent

/**
 * Hearthcraft, the cook's life (living-world/cook/design.md): stations to cook at, dishes of twelve kinds that give a
 * meal's effect, the back doors of the capitals' inns and bakeries that buy them, the town's kitchens, talk and plates
 * round them, the cookshop, the homestead's kitchen and the Harvest Table at Sheafmoot. This is where it comes
 * together: the twelve rules ([enabled] to [fuelScale]), the one registration ([register], which calls each part's
 * own `register()` in a fixed order, so no part edits this file or Guildmark.kt again), the one place the in-memory
 * tables are cleared ([onForget], [forget]) and the one meaning of "today" ([today]).
 *
 * Every rule is read here and nowhere else, the way [io.github.vaspyyy.guildmark.economy.Suppliers.enabled] reads
 * its own: a spec that isn't loaded reads as the default. Each switch also needs the master, [enabled], so it reads
 * false while Hearthcraft is off. What else a switch needs (Capital Markets for the doors, Town Works for the shop,
 * Festivals for the cook-off, Homesteads, Living Capitals, Townsfolk Talk, Seasons) is the part's to ask where it
 * applies; see the design's section 10.1.
 */
object Cooking {
    private inline fun <T> rule(default: T, get: () -> T): T = try {
        get()
    } catch (e: IllegalStateException) {
        default
    }

    /** The master switch (`cooking`): off, the stations stand and say so, and nothing is cooked, sold or judged. */
    fun enabled(): Boolean = rule(true) { GuildmarkConfig.COOKING.get() }

    /** The back doors (`kitchenDoors`): the innkeeper and the baker buy dishes, the slate and the request. */
    fun doors(): Boolean = enabled() && rule(true) { GuildmarkConfig.KITCHEN_DOORS.get() }

    /** Cookshops (`cookShops`): the works a trusted cook can have, and its selling hours. */
    fun shops(): Boolean = enabled() && rule(true) { GuildmarkConfig.COOK_SHOPS.get() }

    /** The Harvest Table (`cookOff`): the cook-off at Sheafmoot. */
    fun cookOff(): Boolean = enabled() && rule(true) { GuildmarkConfig.COOK_OFF.get() }

    /** Homestead kitchens (`homeKitchens`): the kitchen fitted into a homestead, and its free fire. */
    fun homeKitchens(): Boolean = enabled() && rule(true) { GuildmarkConfig.HOME_KITCHENS.get() }

    /** The town's kitchens (`townKitchens`): the retrofit, the kitchen scenes and the diners' plates. */
    fun townKitchens(): Boolean = enabled() && rule(true) { GuildmarkConfig.TOWN_KITCHENS.get() }

    /** The rest-day roast (`cookRoast`). */
    fun roast(): Boolean = enabled() && rule(true) { GuildmarkConfig.COOK_ROAST.get() }

    /** Kitchen talk (`cookTalk`): the kitchen as a topic of the townsfolk's, and its choice. */
    fun talk(): Boolean = enabled() && rule(true) { GuildmarkConfig.COOK_TALK.get() }

    /** Winter Chill (`winterChill`): the cold a hot meal cures. */
    fun winterChill(): Boolean = enabled() && rule(true) { GuildmarkConfig.WINTER_CHILL.get() }

    /** Dishes go stale (`dishSpoilage`): off, nothing ever ages. */
    fun spoilage(): Boolean = enabled() && rule(true) { GuildmarkConfig.DISH_SPOILAGE.get() }

    /** Private pots (`cookPotsPrivate`, off unless set): only the placer and their party can use a station. */
    fun potsPrivate(): Boolean = rule(false) { GuildmarkConfig.COOK_POTS_PRIVATE.get() }

    /** Fuel goes further (`cookFuelScale`, 4 unless set): seconds of mid-heat cooking for each second of a fuel's burn time. */
    fun fuelScale(): Double = rule(4.0) { GuildmarkConfig.COOK_FUEL_SCALE.get() }

    /**
     * Registers all of Hearthcraft: called once, from Guildmark.kt. Each part's own `register()` follows, in the fixed
     * order of the design (12.5); a part that adds a payload, a block entity type, a listener or a command branch does
     * it inside its own `register()`, so this is the only place that lists them.
     */
    fun register(modBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    private val forgetting = ArrayList<Runnable>()

    /** A part hands in the clearing of its in-memory tables (cool-downs, queues, stamps), to be run when the server stops (9.3). */
    @Synchronized
    fun onForget(action: Runnable) {
        forgetting.add(action)
    }

    /** Clears every table handed in with [onForget]: the server has stopped, and a second world opened in the same game starts clean. */
    @Synchronized
    fun forget() {
        for (action in forgetting) {
            try {
                action.run()
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A Hearthcraft table could not be cleared: {}", e.toString())
            }
        }
    }

    /** The town's day it is now (see [Days.dayOf]): the one meaning of "today" for a dish's age and the ledger (3.2, 5.3). */
    fun today(level: ServerLevel): Long = Days.dayOf(level.overworldClockTime)
}
