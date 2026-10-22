package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * What a station sends and a renderer draws (living-world/cook/design.md 1.8): everything is an absolute game-time stamp
 * or a count, so the client animates the steam, the bubbles, the surface and the ring from the clock with no per-tick
 * traffic. [station] is null for the Prep Board. [state] is the vessel's state (the ordinal of Part B's `Vessel.State`:
 * 0 empty), [notch] the heat, [water] how full, [items] the item ids in the pot (at most [MAX_ITEMS]), [tint] the
 * liquid's colour, [added] and [addedCount] the last item put in and a counter (so every client plays a plop for each
 * new one), [call] 0 for none and else the call's ordinal plus one with its deadline [callDue], the ready window
 * [readyFrom] to [readyTo], [mode] (0 a player's, 1 the house's, 2 a guest's), [swirlUntil] while a ladle stirs, [bowls]
 * set out and whether a spit is [turning].
 */
class StationView(
    val station: Station?,
    val state: Int,
    val notch: Int,
    val lit: Boolean,
    val burnt: Boolean,
    val water: Int,
    val items: IntArray,
    val tint: Int,
    val added: Int,
    val addedCount: Int,
    val call: Int,
    val callDue: Long,
    val readyFrom: Long,
    val readyTo: Long,
    val mode: Int,
    val swirlUntil: Long,
    val bowls: Int,
    val turning: Boolean,
) {
    /** Writes the view into [out] (the block entity's update tag and the fake view of `cook view`). */
    fun write(out: ValueOutput) {
        out.putInt("state", state)
        out.putInt("notch", notch)
        out.putBoolean("lit", lit)
        out.putBoolean("burnt", burnt)
        out.putInt("water", water)
        out.putIntArray("items", items)
        out.putInt("tint", tint)
        out.putInt("added", added)
        out.putInt("addedCount", addedCount)
        out.putInt("call", call)
        out.putLong("callDue", callDue)
        out.putLong("readyFrom", readyFrom)
        out.putLong("readyTo", readyTo)
        out.putInt("mode", mode)
        out.putLong("swirlUntil", swirlUntil)
        out.putInt("bowls", bowls)
        out.putBoolean("turning", turning)
    }

    companion object {
        /** The most item ids a view carries. */
        const val MAX_ITEMS = 12

        /** A cold, dry, empty station of kind [station]: what a stub station shows and what a missing record reads as. */
        fun cold(station: Station?): StationView =
            StationView(station, 0, 0, false, false, 0, IntArray(0), 0, 0, 0, 0, 0L, 0L, 0L, 0, 0L, 0, false)

        /** The view [write] wrote, every field optional (a missing one reads as cold). */
        fun read(input: ValueInput, station: Station?): StationView {
            val items = input.getIntArray("items").orElse(IntArray(0))
            return StationView(
                station,
                input.getIntOr("state", 0),
                input.getIntOr("notch", 0),
                input.getBooleanOr("lit", false),
                input.getBooleanOr("burnt", false),
                input.getIntOr("water", 0),
                if (items.size > MAX_ITEMS) items.copyOf(MAX_ITEMS) else items,
                input.getIntOr("tint", 0),
                input.getIntOr("added", 0),
                input.getIntOr("addedCount", 0),
                input.getIntOr("call", 0),
                input.getLongOr("callDue", 0L),
                input.getLongOr("readyFrom", 0L),
                input.getLongOr("readyTo", 0L),
                input.getIntOr("mode", 0),
                input.getLongOr("swirlUntil", 0L),
                input.getIntOr("bowls", 0),
                input.getBooleanOr("turning", false),
            )
        }
    }
}
