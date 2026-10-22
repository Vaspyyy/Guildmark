package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.home.HomesteadKitchens
import io.github.vaspyyy.guildmark.cooking.shop.CookShops
import io.github.vaspyyy.guildmark.townsfolk.Days
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CookingFuel
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt
import java.util.Optional

/**
 * What burns under a pot (living-world/cook/design.md 1.3). A fire keeps a store of fuel counted in seconds of
 * mid-heat cooking; the numbers are the first lines of the file, my estimates of what feels right and the first things
 * to turn (the scale of a fuel's burn time is the rule `cookFuelScale`, [Cooking.fuelScale]).
 */
object Fuel {
    // ---- The numbers (1.3) ----

    /** The most a fire holds, seconds of mid-heat cooking: a coal block or a bucket of lava is refused as too much for it. */
    const val STORE_CAP = 400.0

    /** Seconds of store a lit fire burns each second while it cooks, on the low, mid and high notch (per dish-second: 1.0, 1.0 and 1.25). */
    val BURN: DoubleArray = doubleArrayOf(0.6, 1.0, 2.0)

    /** Seconds of store an idle lit fire burns each second (nothing heating or cooking on it). */
    const val IDLE_BURN = 0.1

    /** The light a lit station gives (hearth style: [LIGHT_HEARTH]). */
    const val LIGHT = 14

    /** The light a lit Stewpot of the hearth style gives. */
    const val LIGHT_HEARTH = 12

    /** How often a lit station asks [free], ticks (once a second, 1.3). */
    const val FREE_TICKS = 20L

    // ---- What a fuel is worth ----

    /**
     * How many ticks [stack] burns for: the furnace's own two lines (the COOKING_FUEL component, whose burn time is a
     * [ResolvableInt] read with a loot context), wrapped. 0 is "not a fuel".
     */
    fun burnTicks(level: ServerLevel, stack: ItemStack): Int {
        if (stack.isEmpty || !stack.has(DataComponents.COOKING_FUEL)) return 0
        return try {
            val params = LootParams.Builder(level).create(LootContextParamSets.EMPTY)
            val context = LootContext.Builder(params).create(Optional.empty())
            ResolvableInt.getFromItem<CookingFuel>(stack, DataComponents.COOKING_FUEL, { it.burnTime() }, context, 0).coerceAtLeast(0)
        } catch (e: RuntimeException) {
            0
        }
    }

    /** The seconds of mid-heat cooking one of [stack] feeds: its burn time in seconds times [Cooking.fuelScale] (a log: 60, a stick: 20, coal: 320). */
    fun seconds(level: ServerLevel, stack: ItemStack): Double = burnTicks(level, stack) / 20.0 * Cooking.fuelScale()

    // ---- Free fire ----

    /**
     * Whether the place at [pos] burns no fuel: the tavern's guest pot while the inn is open (6:00 to 24:00 on the
     * town's clock), else a homestead's kitchen ([HomesteadKitchens.freeAt]), else a cookshop's ([CookShops.freeAt]); each
     * of the last two only while its own switch is on.
     */
    fun free(level: ServerLevel, pos: BlockPos): Boolean {
        if (guestFire(level, pos)) return true
        if (Cooking.homeKitchens() && HomesteadKitchens.freeAt(level, pos)) return true
        return Cooking.shops() && CookShops.freeAt(level, pos)
    }

    /** The inn's hours, as offsets on the town's clock: from 6:00 up to but not including 24:00. */
    private val INN_OPEN = Days.clock(6.0)
    private val INN_SHUT = Days.clock(24.0)

    /** Whether the Stewpot at [pos] is a guest pot and the inn is open. */
    private fun guestFire(level: ServerLevel, pos: BlockPos): Boolean {
        val pot = level.getBlockEntity(pos) as? StewpotBlockEntity ?: return false
        if (pot.mode != StewpotMode.GUEST) return false
        val offset = Days.offsetOf(level.overworldClockTime)
        return offset >= INN_OPEN && offset < INN_SHUT
    }
}
