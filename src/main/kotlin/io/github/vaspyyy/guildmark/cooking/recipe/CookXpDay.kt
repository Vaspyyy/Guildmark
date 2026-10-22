package io.github.vaspyyy.guildmark.cooking.recipe

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * The COOKING XP a cook has earned today (living-world/cook/design.md 2.5, 5.6), the data of the attachment
 * `guildmark:cook_xp_day`: the town's [day] it counts for and the [xp], which the soft cap reads. A count for an earlier
 * day is a fresh day's nothing. Part A's; nothing writes to it yet.
 */
class CookXpDay(var day: Long = -1L, var xp: Int = 0) {
    companion object {
        val CODEC: Codec<CookXpDay> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("day", -1L).forGetter { it.day },
                Codec.INT.optionalFieldOf("xp", 0).forGetter { it.xp },
            ).apply(i) { day, xp -> CookXpDay(day, xp) }
        }
    }
}
