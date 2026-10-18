package io.github.vaspyyy.guildmark

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Gameplay settings, kept in config/guildmark-synced.toml (a world's syncedconfig folder can hold its own)
 * and synced to clients. Changed in game under Guildmark Settings > World, in singleplayer.
 */
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
    val SKILL_XP_MULTIPLIER: ModConfigSpec.DoubleValue = BUILDER
        .comment("Multiplies the skill XP that mining, fighting, crafting, trading and the rest give.")
        .defineInRange("skillXpMultiplier", 1.0, 0.1, 10.0)
    val AUTO_BOARDS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Place a Quest Board in every village players come near.")
        .define("autoPlaceBoards", true)
    val WARDFIRES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Raise Wardfires in the world: by every village players come near, outside lairs and the Hollow Throne, and here and there in newly explored wilds.")
        .define("wardfires", true)
    val DUNGEONS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Dig lairs as dungeons underground: a stairwell down, halls, treasure rooms and a boss hall behind a mist gate. Off gives the old ring on the surface. Only lairs found after a change are affected.")
        .define("dungeons", true)
    val FIELD_BOSSES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Field bosses in the wild: hill trolls asleep by the fires of their camps, found as players explore. Camps already built stay.")
        .define("fieldBosses", true)
    val CAPITALS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Capital cities in newly explored land: walled cities with a keep, a guild hall, a temple, a market and townsfolk. Cities already built stay.")
        .define("capitals", true)
    val DUSK_RIDERS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Dusk Riders: now and then at night a horn sounds and a mounted field boss rides for a player out in the wild. It fades at dawn.")
        .define("duskRiders", true)
    val DRAGONS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Dragons on the high peaks of the far wilds: each nests on a summit, circles it by day and sleeps there by night, and guards a hoard. A slain dragon's nest is taken by another a week later. Nests already built stay.")
        .define("dragons", true)
    val SEA_MONSTERS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Sea monsters: packs of Gnashfins hunt anyone out on the open sea by night or in a storm, and far out over the deeps the Maelgrasp rises under a ship and takes hold of her.")
        .define("seaMonsters", true)
    val ISLANDS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Islands out at sea in newly explored ocean: wild isles with beaches, cliffs and woods of their own, sea stacks thick with gulls, coves, atolls and reefs. Islands already grown stay.")
        .define("islands", true)
    val REGIONS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Named regions: the overworld is divided into lands with names of their own, announced as you enter them and lettered on the map.")
        .define("regions", true)
    val REGION_ENEMIES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Regional foes: in the marsh, the ashen highlands, the frozen peaks and the crystal deeps, the land's own Hollows take the Hollow Soldiers' place.")
        .define("regionEnemies", true)
    val REGION_TERRAIN: ModConfigSpec.BooleanValue = BUILDER
        .comment("Regional ground cover in newly explored land: rot, bog lanterns and cattails in the marsh; ash, burnt trees and ember vents on the ashen highlands; icicles and hoarfrost in the frozen peaks; glimmer crystal in the crystal deeps. Land made before stays as it is.")
        .define("regionTerrain", true)
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
    val OUTLAWS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Outlaw bands camped in the wild, found as players explore: a captain with a price on their head, a lookout on the tower and the band about the fire. Boards post wanted notes on them. Camps already built stay.")
        .define("outlaws", true)
    val RIVALS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Rival guilds claim villages, challenge yours and send adventurers to boards.")
        .define("rivalGuilds", true)
    val CHARACTERS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Named characters (Wren, Tobin, Ilsa) seek out players, which starts the main story.")
        .define("characters", true)
    val RELIC_CACHE_CHANCE: ModConfigSpec.DoubleValue = BUILDER
        .comment("Base chance a finished contract also pays a Sealed Relic Cache (plus 3% per rank).")
        .defineInRange("relicCacheChance", 0.08, 0.0, 1.0)

    val TOWNSFOLK_CROWDS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Passers-by in towns and cities: when the streets round a player are quiet, townsfolk come walking in out of sight, go about their errands and slip away again.")
        .define("townsfolkCrowds", true)
    val TOWNSFOLK_CROWD_DENSITY: ModConfigSpec.DoubleValue = BUILDER
        .comment("How busy the streets get: 1 is the usual crowd, 0.5 half as many passers-by, 2 twice as many.")
        .defineInRange("townsfolkCrowdDensity", 1.0, 0.25, 3.0)
    val LIVING_WORLD: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' people live real days: each has a name, a home and a bed, and goes about a day of their own (work at their trade, meals at home, the market, the well, the temple, the tavern, up the ladder to bed). Off, they keep the old round of chance errands.")
        .define("livingWorld", true)
    val TOWNSFOLK_BEDS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A bed for everyone in the capitals: anyone the city left with nowhere to sleep has a bed made up in their home, out of sight of players.")
        .define("townsfolkBeds", true)
    val MARKETS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' markets: each capital grows, makes, moves, eats and trades real goods an hour at a time, whether anyone is there or not, and its stalls buy and sell at prices that follow what it holds. Off, the markets stand still.")
        .define("capitalMarkets", true)
    val HANDCARTS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' carters pull handcarts: sacks of grain from the fields to the mill, flour to the bakehouse and produce to the market, heaped on the cart's bed. Off, they carry the sacks on their shoulders.")
        .define("handcarts", true)

    val HOMESTEADS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Player homesteads: a charter from the Guildmaster stakes a plot on open land, and a crew of builders puts up a cottage, a farmstead and a manor on it, tier by tier. Off, the Guildmaster sells no charters and no building goes on; homesteads already staked stay.")
        .define("homesteads", true)
    val HOMESTEAD_PROTECTION: ModConfigSpec.BooleanValue = BUILDER
        .comment("Only a homestead's owner and their party may break, place or open things on its land.")
        .define("homesteadProtection", true)
    val HOMESTEAD_BUILD_SPEED: ModConfigSpec.DoubleValue = BUILDER
        .comment("How fast homestead builders work: 1 is a cottage in half a day of daylight, a farmstead in a day and a manor in two; 2 is twice as fast.")
        .defineInRange("homesteadBuildSpeed", 1.0, 0.1, 20.0)

    val MARKET_DISPLAYS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The market's goods on show in the capitals: baskets, crates, racks, boards, trays, shelves and sacks on the stalls and counters, heaped with the real goods as full as the market's stock of each. Off, newly built capitals keep the old stall props, no more displays are set out in capitals built before, and those already out stand still.")
        .define("marketDisplays", true)

    val TURNING_MILLS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' mills turn: a windmill's sails while its millstones grind (braked in a storm, one with a sail hanging broken while the mill is broken), a watermill's wheel with its stream, harder while it grinds. Older capitals' block-built sails and wheels are swapped for turning ones out of sight. Off, they stand still.")
        .define("turningMills", true)

    val SPEC: ModConfigSpec = BUILDER.build()
}
