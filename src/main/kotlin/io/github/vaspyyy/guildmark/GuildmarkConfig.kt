package io.github.vaspyyy.guildmark

import net.neoforged.neoforge.common.ModConfigSpec

/** Per-world gameplay settings, kept in the world's serverconfig folder and synced to clients. */
object GuildmarkConfig {
    private val BUILDER = ModConfigSpec.Builder()

    val COMBAT: ModConfigSpec.BooleanValue = BUILDER
        .comment("The new action combat: stamina, dodge rolls, light and heavy attacks, parries, poise and lock-on. Turn it off to keep vanilla combat.")
        .define("actionCombat", true)

    val LOSE_MARKS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Falling leaves the Guild Marks you carry where you fell; reach them to win them back, or fall again first and they're gone. Applies with keepInventory too.")
        .define("loseMarksOnDeath", true)

    val PAY_MULTIPLIER: ModConfigSpec.DoubleValue = BUILDER
        .comment("Multiplies the Guild Marks every contract pays.")
        .defineInRange("payMultiplier", 1.0, 0.1, 10.0)
    val XP_MULTIPLIER: ModConfigSpec.DoubleValue = BUILDER
        .comment("Multiplies the guild XP everything gives.")
        .defineInRange("xpMultiplier", 1.0, 0.1, 10.0)
    val AUTO_BOARDS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Place a Quest Board in every village players come near.")
        .define("autoPlaceBoards", true)
    val WARDFIRES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Raise Wardfires in the world: by every village players come near, outside lairs and the Hollow Throne, and here and there in newly explored wilds.")
        .define("wardfires", true)
    val DUNGEONS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Dig lairs as dungeons underground: a stairwell down, halls, treasure rooms and a boss hall behind a mist gate. Off gives the old ring on the surface. Only lairs found after a change are affected.")
        .define("dungeons", true)
    val HANDBOOK: ModConfigSpec.BooleanValue = BUILDER
        .comment("Give new players the Adventurer's Handbook.")
        .define("handbookOnJoin", true)
    val SIEGES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Monster sieges on villages players have built up.")
        .define("sieges", true)
    val SIEGE_CHANCE: ModConfigSpec.IntValue = BUILDER
        .comment("Percent chance each night that a siege starts on a built-up village a player is in at dusk.")
        .defineInRange("siegeChancePercent", 25, 0, 100)
    val BANDITS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Bandits ambush caravans and travellers on Trade Roads.")
        .define("bandits", true)
    val RIVALS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Rival guilds claim villages, challenge yours and send adventurers to boards.")
        .define("rivalGuilds", true)
    val CHARACTERS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Named characters (Wren, Tobin, Ilsa) seek out players, which starts the main story.")
        .define("characters", true)
    val RELIC_CACHE_CHANCE: ModConfigSpec.DoubleValue = BUILDER
        .comment("Base chance a finished contract also pays a Sealed Relic Cache (plus 3% per rank).")
        .defineInRange("relicCacheChance", 0.08, 0.0, 1.0)

    val SPEC: ModConfigSpec = BUILDER.build()
}
