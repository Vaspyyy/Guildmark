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
    val SUPPLIER_ORDERS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Supplying the capitals: each capital's businesses post orders for the goods it has to bring in (on the market board, at their screens, cried at noon), players take and fill them for a fixed price, and filling orders and selling at the stalls earns a supplier's standing there, which pays a little more at its stalls. Off, there are no orders and no standing, and the stalls pay as they always have.")
        .define("supplierOrders", true)
    val HANDCARTS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' carters pull handcarts: sacks of grain from the fields to the mill, flour to the bakehouse and produce to the market, heaped on the cart's bed. Off, they carry the sacks on their shoulders.")
        .define("handcarts", true)
    val CARAVANS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' imports come on wagons that really drive in along their roads, three a day, whether anyone sees them or not: a driver and two guards on a horse-drawn wagon heaped with the real goods. A capital with no road has one surveyed to its nearest village and paved as the land loads. Off, the goods appear in the market at the hour, as they used to.")
        .define("caravans", true)
    val ROAD_AMBUSHES: ModConfigSpec.BooleanValue = BUILDER
        .comment("An outlaw band camped near a capital's road can waylay its wagons: the load lost, a wreck by the road, the driver back at the gate, dearer goods and hungry townsfolk. Each road keeps count of its trouble and closes bit by bit if it's left alone; it opens again when the band is broken, wagons get in under escort, or it quiets down. Needs Caravans and Outlaw Bands.")
        .define("roadAmbushes", true)
    val CARAVAN_ESCORTS: ModConfigSpec.BooleanValue = BUILDER
        .comment("While a capital's road is troubled its quest board posts a note to see the next wagon in, and a wanted note on the band working the road.")
        .define("caravanEscorts", true)
    val ROAD_SALVAGE: ModConfigSpec.BooleanValue = BUILDER
        .comment("A waylaid wagon's wreck holds the goods spilled from it, marked as off that wagon, to bring back to its capital for a finder's fee (or keep, at a cost to your standing there). Off, a wreck is only scenery and its goods are lost.")
        .define("roadSalvage", true)

    val HOMESTEADS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Player homesteads: a charter from the Guildmaster stakes a plot on open land, and a crew of builders puts up a cottage, a farmstead and a manor on it, tier by tier. Off, the Guildmaster sells no charters and no building goes on; homesteads already staked stay.")
        .define("homesteads", true)
    val HOMESTEAD_PROTECTION: ModConfigSpec.BooleanValue = BUILDER
        .comment("Only a homestead's owner and their party may break, place or open things on its land.")
        .define("homesteadProtection", true)
    val HOMESTEAD_BUILD_SPEED: ModConfigSpec.DoubleValue = BUILDER
        .comment("How fast homestead builders work: 1 is a cottage in half a day of daylight, a farmstead in a day and a manor in two; 2 is twice as fast.")
        .defineInRange("homesteadBuildSpeed", 1.0, 0.1, 20.0)
    val HOMESTEAD_SHIPPING: ModConfigSpec.BooleanValue = BUILDER
        .comment("Each morning at seven the carter from the nearest capital (within 1500 blocks) empties a homestead's shipping crates and sells their goods at that market's own falling prices, until it wants no more; the Marks wait in the Charter Post's purse, collected from its ledger. Off, the crates are left as they are.")
        .define("homesteadShipping", true)
    val OWN_STALLS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A Trusted Supplier of a capital may rent a table in its market hall by the week and stock it from their pack, and it sells to the town while they're away: each market hour up to a third of what the town buys of each good on it, at the market's price less a tenth. Off, no table is let and the stalls already let sell nothing (their goods and tills can still be taken back).")
        .define("ownStalls", true)

    val MARKET_DISPLAYS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The market's goods on show in the capitals: baskets, crates, racks, boards, trays, shelves and sacks on the stalls and counters, heaped with the real goods as full as the market's stock of each. Off, newly built capitals keep the old stall props, no more displays are set out in capitals built before, and those already out stand still.")
        .define("marketDisplays", true)

    val TURNING_MILLS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' mills turn: a windmill's sails while its millstones grind (braked in a storm, one with a sail hanging broken while the mill is broken), a watermill's wheel with its stream, harder while it grinds. Older capitals' block-built sails and wheels are swapped for turning ones out of sight. Off, they stand still.")
        .define("turningMills", true)

    val TOWNSFOLK_TALK: ModConfigSpec.BooleanValue = BUILDER
        .comment("Talking with townsfolk: a right-click opens a short talk (a greeting that knows you, what's on their mind, then what they're up to, any news, trade or goodbye), and their remarks in passing and their chats come from the same topics. Off, they say one line as before.")
        .define("townsfolkTalk", true)
    val TOWNSFOLK_MEMORY: ModConfigSpec.BooleanValue = BUILDER
        .comment("Townsfolk remember the players they've met, what passed between them and what they saw. Off, everyone is a stranger; what they remembered is kept for when it's turned on again.")
        .define("townsfolkMemory", true)
    val TOWNSFOLK_RUMOURS: ModConfigSpec.BooleanValue = BUILDER
        .comment("News goes from person to person through the townsfolk's days, changing as it goes, and the crier cries the talk of the town. Off, townsfolk know only what they saw and what the crier cried. Passing news on out of sight needs Living Capitals.")
        .define("townsfolkRumours", true)
    val TOWNSFOLK_BARKS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Townsfolk greet and call out to players in passing with what's on their mind. Off, the old greetings.")
        .define("townsfolkBarks", true)
    val STREET_LAMPS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' street lamps are lit at dusk and put out at dawn: lamplighters walk their rounds with a long pole, lighting the lamps one by one from about half past five in the evening (an hour earlier in a storm) and putting them out from half past five in the morning, farmers see to the lamps at their gates, and any lamp nobody reaches catches or goes out by itself, so every lamp is lit by seven and out by half past seven. Older capitals' lanterns on posts are swapped for street lamps out of sight. Off, the lamps burn day and night as before.")
        .define("streetLamps", true)

    // A capital's works, its good times and hard, and households coming and going (living-world/design-influence.md)
    val TOWN_WORKS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A capital asks for works once it shows the need (a second oven, a granary and then a great one, a watchtower on a road in, a stall of your own), players pledge Marks toward them, and the town's own people build them under a master builder, the materials coming in on the wagons. Off, nothing new is wanted or built, the bake is as before, the granary neither gives nor fills and towers don't count; pledges, sites and half-built works are kept for when it's on again.")
        .define("townWorks", true)
    val TOWN_WORKS_BUILD_SPEED: ModConfigSpec.DoubleValue = BUILDER
        .comment("How fast a capital's builders put up a works: 1 is as the design has it; 2 is twice as fast.")
        .defineInRange("townWorksBuildSpeed", 1.0, 0.1, 20.0)
    val TOWN_FORTUNES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Each capital's times are worked out every day from what really happened to it (hunger, robbed wagons, works built, goods supplied, deaths and festivals), from hard times to thriving, and show in its streets and its talk. Off, every capital's times read steady (no dressing, the usual crowds, nobody leaves); the record is kept.")
        .define("townFortunes", true)
    val TOWN_COMINGS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Newcomers come in through a gate to take the homes and trades of the dead, and good times grow a capital by up to a fifth; after five hard days a household may leave (one a week at most, never below 85% of the plan), and they're the first to come back. Off, nobody comes or goes, and households away are home again.")
        .define("townComings", true)

    // Seasons and festivals (season/, festival/)
    val SEASONS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Seasons in the overworld: spring, summer, autumn and winter turn the land's colours, bring blossom, falling leaves and winter snow, and pace the crops. Off, the calendar still counts (for the festivals) but nothing changes with it.")
        .define("seasons", true)
    val SEASON_LENGTH: ModConfigSpec.IntValue = BUILDER
        .comment("Days in each season; four seasons make a year. Each season's festival falls on its middle day.")
        .defineInRange("seasonLength", 9, 3, 60)
    val SEASONAL_CROPS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Crops grow faster in their own seasons and slower out of them (never stopping, and at full pace under a roof or by a lit hearth), and animals often have twins in spring.")
        .define("seasonalCrops", true)
    val SEASONAL_SNOW: ModConfigSpec.BooleanValue = BUILDER
        .comment("In winter the temperate lands get snow instead of rain, and a light snow cover that melts again in spring.")
        .define("seasonalSnow", true)
    val FESTIVALS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A grand festival in every capital and village once a season: decorations, music, dancing townsfolk, a contest with prizes and fireworks at dusk.")
        .define("festivals", true)

    val SPEC: ModConfigSpec = BUILDER.build()
}
