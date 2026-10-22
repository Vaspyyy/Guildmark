package io.github.vaspyyy.guildmark.cooking.market

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.shop.ShopRec
import io.github.vaspyyy.guildmark.economy.Markets
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/**
 * The ledger of the capitals' kitchens (living-world/cook/design.md 5.3): one record for each capital, keyed by its
 * centre's `BlockPos.asLong()`, kept with the overworld as [io.github.vaspyyy.guildmark.economy.Suppliers] is. Part G's:
 * the counters, the carries, what is owed, the pantry and the request, with their codec. Until it lands it saves nothing
 * (so a world holds no trace of it), sells nothing and wants nothing; only the cookshops' records ([shops]) are kept, in
 * memory, so Part H can be built beside it.
 */
class Kitchens : SavedData() {
    private val shopsByCapital = HashMap<Long, MutableMap<UUID, ShopRec>>()

    /** Sells the dish in [stack] to the keeper of [door] in [capital] for [player], taking what is bought off the stack in one go (7.7; stub: nothing taken). */
    fun sell(level: ServerLevel, capital: Long, door: Door, player: ServerPlayer, stack: ItemStack): Sale =
        Sale(0, 0, false, Tier.PLAIN, Stage.FRESH, null, false)

    /** The three kinds the town wants on [day] (5.2; stub: none). */
    fun wanted(level: ServerLevel, capital: Long, day: Long): List<DishKind> = emptyList()

    /** The request of [day] (5.4; stub: none). */
    fun request(level: ServerLevel, capital: Long, day: Long): Request? = null

    /** Puts [lot] in the pantry of [door] (stub: nothing kept). */
    fun addLot(level: ServerLevel, capital: Long, door: Door, lot: Lot) {}

    /** The lots in [door]'s pantry, oldest first (stub: none). */
    fun lots(capital: Long, door: Door): List<Lot> = emptyList()

    /** The cookshops' records of [capital] (6.4), by owner. */
    fun shops(capital: Long): MutableMap<UUID, ShopRec> = shopsByCapital.getOrPut(capital) { HashMap() }

    companion object {
        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "kitchens"), Supplier { Kitchens() }, MapCodec.unitCodec(Supplier { Kitchens() }))

        /** The ledger, kept with the overworld. */
        fun get(level: ServerLevel): Kitchens = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /**
         * One market hour of the kitchens (5.3): the meal-hour serving and the cookshops' selling hours, called by
         * [Markets.step] just before the economy works the hour out, and what it answers is the portions served, which
         * the town does not eat as plain meals (stub: 0, nothing served).
         */
        fun step(level: ServerLevel, entry: Markets.Entry, hour: Long): Double = 0.0
    }
}
