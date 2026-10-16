package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.island.plan.Climate
import io.github.vaspyyy.guildmark.island.plan.Form
import io.github.vaspyyy.guildmark.island.plan.IslandKind
import io.github.vaspyyy.guildmark.island.plan.IslandNames
import io.github.vaspyyy.guildmark.island.plan.IslandSpec

/**
 * How an island is kept in a player's atlas and drawn on their map and compass: everything about it that's shown
 * (its name roll, what it is, its lie and its sea) packed into the one number the atlas keeps, [ref], from which its
 * name and the sort of island it is can be told again anywhere, the client too.
 */
object IslandMarks {
    private const val NAME_BITS = 20

    fun ref(spec: IslandSpec): Int =
        (spec.name and ((1 shl NAME_BITS) - 1)) or (spec.kind.ordinal shl NAME_BITS) or (spec.form.ordinal shl (NAME_BITS + 3)) or
            (spec.climate.ordinal shl (NAME_BITS + 6))

    fun kind(ref: Int): IslandKind = IslandKind.entries.getOrElse((ref ushr NAME_BITS) and 7) { IslandKind.ISLE }

    fun form(ref: Int): Form = Form.entries.getOrElse((ref ushr (NAME_BITS + 3)) and 7) { Form.BLOB }

    fun climate(ref: Int): Climate = Climate.entries.getOrElse((ref ushr (NAME_BITS + 6)) and 7) { Climate.TEMPERATE }

    fun name(ref: Int): String = IslandNames.name(ref and ((1 shl NAME_BITS) - 1), kind(ref), form(ref), climate(ref))

    /** The lang key for what sort of island it is: a lighthouse, sea stacks, an atoll. */
    fun kindKey(ref: Int): String {
        val kind = kind(ref)
        val what = if (kind == IslandKind.ISLE) when (form(ref)) {
            Form.ATOLL -> "atoll"
            Form.TWIN -> "twin"
            else -> "isle"
        } else kind.name.lowercase()
        return "atlas.guildmark.kind.island.$what"
    }

    /** The sprite name (map/ and compass/) for an island of this sort. */
    fun icon(ref: Int): String = when (val kind = kind(ref)) {
        IslandKind.ISLE -> "island"
        else -> "island_${kind.name.lowercase()}"
    }
}
