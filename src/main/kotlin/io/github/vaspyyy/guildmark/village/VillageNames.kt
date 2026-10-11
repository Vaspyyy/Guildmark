package io.github.vaspyyy.guildmark.village

import net.minecraft.core.BlockPos

/** Every village gets a stable name from where its board stands, so no name has to be saved. */
object VillageNames {
    private val FRONT = listOf(
        "Ash", "Elm", "Oak", "Thorn", "Mill", "Brook", "Stone", "Wolf", "Raven", "Bright", "Harrow", "Fen", "Glen",
        "Kettle", "Marl", "Pike", "Rush", "Sedge", "Tall", "Wick", "Yarrow", "Bram", "Cinder", "Dun", "Lark", "Moss",
        "Rye", "Salt", "Whit", "Amber", "Barley", "Copper", "Dove", "Ember", "Frost", "Gold", "Heron", "Iron", "Juniper",
    )
    private val BACK = listOf(
        "ford", "brook", "stead", "wick", "ton", "holm", "by", "field", "mere", "cross", "bridge", "well", "dale",
        "gate", "haven", "moor", "crest", "barrow", "combe", "hithe", "ley", "worth",
    )

    fun of(anchor: BlockPos): String {
        val hash = (anchor.x.toLong() * 73856093L) xor (anchor.z.toLong() * 19349663L)
        val front = FRONT[Math.floorMod(hash, FRONT.size.toLong()).toInt()]
        var back = BACK[Math.floorMod(hash / 41, BACK.size.toLong()).toInt()]
        // Avoid doubled letters at the seam, like "Brookkford"
        if (front.last().lowercaseChar() == back.first()) back = BACK[Math.floorMod(hash / 41 + 1, BACK.size.toLong()).toInt()]
        return front + back
    }
}
