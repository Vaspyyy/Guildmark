package io.github.vaspyyy.guildmark.wardfire

import net.minecraft.core.BlockPos

/** Every Wardfire gets a stable name from where it stands, so no name has to be saved. */
object WardfireNames {
    private val LOOK = listOf(
        "Ashen", "Sunken", "Weeping", "Silent", "Broken", "Hollow", "Grey", "Lonely", "Forgotten", "Fallen", "Old",
        "Last", "Pale", "Mourning", "Shattered", "Quiet", "Cinder", "Windworn", "Mossy", "Drowned", "Burnt", "Hidden",
        "Crooked", "High", "Low", "Bleak", "Golden", "Thorned", "Frozen", "Faded",
    )
    private val OWNER = listOf(
        "Pilgrim's", "Warden's", "Hunter's", "Soldier's", "Watcher's", "Shepherd's", "Widow's", "Mason's", "Beggar's",
        "Herald's", "Squire's", "Ferryman's", "Hermit's", "Knight's", "Wanderer's", "Smith's",
    )
    private val PLACE = listOf(
        "Ford", "Causeway", "Hollow", "Crossing", "Barrow", "Watch", "Overlook", "Stair", "Cairn", "Pass", "Mere",
        "Gate", "Vigil", "Ruin", "Tor", "Bridge", "Glade", "Fen", "Pines", "Cliffs", "Shore", "Bluff", "Knoll",
        "Spring", "Well", "Arch", "Hearth", "Shrine", "Grove", "Rest", "Steps", "Altar",
    )

    fun of(pos: BlockPos): String {
        val hash = (pos.x.toLong() * 73856093L) xor (pos.y.toLong() * 83492791L) xor (pos.z.toLong() * 19349663L)
        val place = PLACE[pick(hash / 7, PLACE.size)]
        // Most are known by how they look, some by who kept them
        return if (pick(hash, 10) < 7) LOOK[pick(hash / 211, LOOK.size)] + " " + place
        else OWNER[pick(hash / 211, OWNER.size)] + " " + place
    }

    private fun pick(hash: Long, size: Int): Int = Math.floorMod(hash, size.toLong()).toInt()
}
