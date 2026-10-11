package io.github.vaspyyy.guildmark.loot

import net.minecraft.network.chat.Component

/**
 * The special properties a relic can roll. Weapon affixes trigger on hit (melee or arrows fired from the
 * relic); armour affixes protect the wearer. Stat affixes are plain attribute bonuses on the item.
 */
enum class Affix(val id: String, val weapon: Boolean, val meleeOnly: Boolean = false) {
    LIFESTEAL("lifesteal", true),
    THUNDER("thunder", true),
    FROST("frost", true),
    VENOM("venom", true),
    EMBER("ember", true),
    EXECUTE("execute", true),
    KINGSLAYER("kingslayer", true),
    KEEN("keen", true, meleeOnly = true),
    QUICK("quick", true, meleeOnly = true),
    VIGOR("vigor", false),
    SWIFT("swift", false),
    WARDING("warding", false),
    BRAMBLE("bramble", false),
    FEATHER("feather", false),
    FORTUNE("fortune", false);

    /** The word a relic named after this affix starts with ("Vampiric Iron Sword"). */
    val prefix: Component get() = Component.translatable("affix.guildmark.$id.prefix")
    val line: Component get() = Component.translatable("affix.guildmark.$id")

    companion object {
        fun byId(id: String): Affix? = entries.firstOrNull { it.id == id }
    }
}
