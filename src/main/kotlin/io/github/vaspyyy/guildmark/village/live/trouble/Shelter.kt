package io.github.vaspyyy.guildmark.village.live.trouble

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * A village called to shelter (living-world/villages/design.md section 11.2): its folk go to the nearest door, bolt it, and
 * come out at the all-clear. The names and signatures are the contract of design section 19.3; part V10 fills them in.
 * Until then nothing is called and the folk flee on their own brains, as they did.
 */
object Shelter {
    /** What the village is sheltering from. */
    enum class Why { SIEGE, RAID }

    /** Calls the village [key], whose bell is at [bell], to shelter from [why]: the folk within [reach] blocks of the bell go indoors. */
    fun call(level: ServerLevel, key: Long, bell: BlockPos, reach: Int, why: Why) {}

    /** The all-clear for the village [key]: those sheltering come out. */
    fun end(level: ServerLevel, key: Long) {}
}
