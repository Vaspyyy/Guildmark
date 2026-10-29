package io.github.vaspyyy.guildmark.trade.carry

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.StallDesk
import net.minecraft.core.NonNullList
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.function.Supplier

/**
 * One trader's Carrier's Wagon as the record keeps it (living-world/trader/design.md, Part 3; build part D3): where it is and in what
 * [state], what it holds, and its horse. The bodies (the caravans' own `Wagon` and `DraughtHorse` props, never saved) are made from this
 * and go again; this is the wagon.
 */
class WagonRec(val owner: UUID) {
    /** In a Store's yard (or the capital's wagon stand) waiting to be rolled out; left standing where it was; behind its owner on the lead. */
    enum class State { YARD, PARKED, LED }

    /** The owner's name as it was last seen, for the name board and the lines that say whose it is. */
    var ownerName = ""

    var state = State.YARD

    /** The dimension it stands in. */
    var dimension: ResourceKey<Level> = Level.OVERWORLD

    /** Where its back axle stands on the ground, and the way it heads (Minecraft's yaw); null until it is first placed. */
    var at: Vec3? = null
    var yaw = 0f

    /** The capital whose Store's yard (or wagon stand) it is kept at, or was last sent to (its heart's `BlockPos.asLong`; 0 for none yet). */
    var capital = 0L

    /** The overworld's clock time from which a wagon sent to its yard is there (it comes "after a night"); 0: it is there now. */
    var due = 0L

    /** The game time the Bill was bought at: the first day's call home is free. */
    var bought = 0L

    /** Whether a draught horse has been bought for it; its name and coat (0 bay, 1 grey, 2 chestnut, as the caravans' horses have them). */
    var horse = false
    var horseName = ""
    var coat = 0

    /** Its colour, a dye id ([io.github.vaspyyy.guildmark.city.plan.Dye]), from [TradeWagons.colourOf] when it was bought and whenever its bodies are made. */
    var colour = 0

    /** Its 54 slots (a double chest's). */
    val stacks: NonNullList<ItemStack> = NonNullList.withSize(TradeWagons.SLOTS, ItemStack.EMPTY)

    /** How many of its slots hold something. */
    fun used(): Int = stacks.count { !it.isEmpty }

    /** Whether it has come to its yard by the overworld's [clock] (a clock set back by more than a day lets it come at once). */
    fun arrived(clock: Long): Boolean = due == 0L || clock >= due || due - clock > TradeWagons.DAY
}

/**
 * The traders' wagons (part D3): one [WagonRec] a player at most, kept with the overworld as `guildmark:trade_wagons`. Nothing is in it until a
 * Wagon Bill is bought (or `trade wagon spawn` is used), so an old world loads with nothing to do. Every field is optional in what is saved.
 *
 * A wagon saved while led is loaded as parked where it last stood: nobody holds its lead after a restart.
 */
class TradeWagons(private val wagons: MutableMap<UUID, WagonRec> = LinkedHashMap()) : SavedData() {
    operator fun get(owner: UUID): WagonRec? = wagons[owner]

    /** [owner]'s wagon, made (in its yard, empty, with no horse) if they have none. */
    fun make(owner: UUID): WagonRec = wagons.getOrPut(owner) { WagonRec(owner) }.also { setDirty() }

    fun all(): Collection<WagonRec> = wagons.values

    fun remove(owner: UUID): WagonRec? = wagons.remove(owner)?.also { setDirty() }

    fun changed() = setDirty()

    companion object {
        const val SLOTS = 54

        /** A game day, in ticks. */
        const val DAY = 24000L

        /**
         * The colour [owner]'s wagon is painted in: the colour their pennant flies in at the market (their homestead's, else their guild's, else
         * one of their own), a dye id. The one place the wagon's colour comes from, for the trading house (build part G) to point at its own tint.
         */
        fun colourOf(level: ServerLevel, owner: UUID): Int = StallDesk.colorOf(level, owner)

        private val POS: Codec<List<Double>> = Codec.DOUBLE.listOf()

        private val WAGON: Codec<WagonRec> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(WagonRec::owner),
                Codec.STRING.optionalFieldOf("name", "").forGetter(WagonRec::ownerName),
                Codec.STRING.optionalFieldOf("state", WagonRec.State.YARD.name).forGetter { it.state.name },
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension", Level.OVERWORLD).forGetter(WagonRec::dimension),
                POS.optionalFieldOf("at", listOf()).forGetter { r -> r.at?.let { listOf(it.x, it.y, it.z) } ?: listOf() },
                Codec.FLOAT.optionalFieldOf("yaw", 0f).forGetter(WagonRec::yaw),
                Codec.LONG.optionalFieldOf("capital", 0L).forGetter(WagonRec::capital),
                Codec.LONG.optionalFieldOf("due", 0L).forGetter(WagonRec::due),
                Codec.LONG.optionalFieldOf("bought", 0L).forGetter(WagonRec::bought),
                Codec.BOOL.optionalFieldOf("horse", false).forGetter(WagonRec::horse),
                Codec.STRING.optionalFieldOf("horseName", "").forGetter(WagonRec::horseName),
                Codec.INT.optionalFieldOf("coat", 0).forGetter(WagonRec::coat),
                Codec.INT.optionalFieldOf("colour", 0).forGetter(WagonRec::colour),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("stacks", listOf()).forGetter { it.stacks.toList() },
            ).apply(i) { owner, name, state, dimension, at, yaw, capital, due, bought, horse, horseName, coat, colour, stacks ->
                val rec = WagonRec(owner)
                rec.ownerName = name
                // A state this version doesn't know, or a lead nobody holds after a restart: it stands where it was
                val s = WagonRec.State.entries.firstOrNull { it.name == state } ?: WagonRec.State.PARKED
                rec.state = if (s == WagonRec.State.LED) WagonRec.State.PARKED else s
                rec.dimension = dimension
                rec.at = if (at.size == 3 && at.all { it.isFinite() }) Vec3(at[0], at[1], at[2]) else null
                rec.yaw = if (yaw.isFinite()) yaw else 0f
                rec.capital = capital
                rec.due = due
                rec.bought = bought
                rec.horse = horse
                rec.horseName = horseName
                rec.coat = coat.coerceIn(0, 2)
                rec.colour = colour.coerceIn(0, 15)
                // Never more than its slots: a longer list (none is written) keeps what fits and nothing is thrown away silently
                for ((k, stack) in stacks.withIndex()) if (k < SLOTS) rec.stacks[k] = stack
                if (stacks.size > SLOTS) Guildmark.LOGGER.warn("A trader's wagon was saved with {} slots; the first {} are kept", stacks.size, SLOTS)
                rec
            }
        }

        private val CODEC: Codec<TradeWagons> = RecordCodecBuilder.create { i ->
            i.group(
                WAGON.listOf().optionalFieldOf("wagons", listOf()).forGetter { it.wagons.values.toList() },
            ).apply(i) { list -> TradeWagons(LinkedHashMap(list.associateBy { it.owner })) }
        }

        /** The saved data's type: [get] makes the record if there is none (and the game saves what it made), so a look or a status line reads it with [peek]. */
        internal val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "trade_wagons"), Supplier { TradeWagons() }, CODEC)

        /** The wagons, made (and so saved, even empty) if there are none yet: for whoever buys or makes one. */
        fun get(level: ServerLevel): TradeWagons = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** The wagons if any were ever made, else null, making nothing: for the bodies, a look or a status line. */
        fun peek(level: ServerLevel): TradeWagons? = level.server.overworld().dataStorage.get(TYPE)
    }
}
