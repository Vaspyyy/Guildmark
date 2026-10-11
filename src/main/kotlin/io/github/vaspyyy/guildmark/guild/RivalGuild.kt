package io.github.vaspyyy.guildmark.guild

import net.minecraft.world.item.DyeColor

/** The other adventuring guilds of the world. Their ids sit above any player guild's. */
enum class RivalGuild(val id: Int, val key: String, val displayName: String, val color: DyeColor) {
    IRON_HOUNDS(1001, "iron_hounds", "The Iron Hounds", DyeColor.GRAY),
    SCARLET_LEDGER(1002, "scarlet_ledger", "The Scarlet Ledger", DyeColor.RED),
    GILDED_STAG(1003, "gilded_stag", "The Gilded Stag", DyeColor.YELLOW),
    ASHEN_CROWS(1004, "ashen_crows", "The Ashen Crows", DyeColor.BLACK);

    companion object {
        fun byId(id: Int): RivalGuild? = entries.firstOrNull { it.id == id }
        fun byKey(key: String): RivalGuild? = entries.firstOrNull { it.key == key }
    }
}
