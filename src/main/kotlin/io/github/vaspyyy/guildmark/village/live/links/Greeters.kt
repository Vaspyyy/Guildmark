package io.github.vaspyyy.guildmark.village.live.links

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/** What the villagers at the road end have come out to meet. */
enum class GreetWhy {
    /** A capital's wagon coming in. */
    WAGON,

    /** The pedlar and his cart. */
    PEDLAR,
}

/**
 * Hands out the villagers who go to the road end to meet a cart or a wagon, wave and count the sacks (living-world/
 * villages/design.md section 9.4). The name and signature are the contract of design section 19.3: part V7 makes the
 * greeters, who are directors of their own. Until then nobody comes.
 */
object Greeters {
    /** Calls up to five villagers of the village [key] with nothing pressing to [at], for [why]. */
    fun call(level: ServerLevel, key: Long, at: BlockPos, why: GreetWhy) {}
}
