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

    // The market's loose ends (living-world/trader/design.md, Part 7): wool and apples for the stalls, fish that climbs, Trade XP for dealing done away
    val MARKET_HERD_STOP: ModConfigSpec.BooleanValue = BUILDER
        .comment("The herds, coops and orchards stop making wool, leather, feathers and apples once the market holds what the town likes to keep of them (counting what waits in the yard for the carts), so the stalls have room for a player's lot instead of standing full. Meat and chicken are as they were, and a town whose road is in trouble keeps its whole buffer. Off, they make as before and these goods stand full.")
        .define("marketHerdStop", true)
    val MARKET_HERD_STOP_AT: ModConfigSpec.DoubleValue = BUILDER
        .comment("Where the herds, coops and orchards stop: this many times what the town likes to keep of wool, leather, feathers and apples. 1 is the target itself; a lower number leaves the stalls more room, a higher one less.")
        .defineInRange("marketHerdStopAt", 1.0, 0.8, 1.5)
    val MARKET_CARRY_EASE: ModConfigSpec.BooleanValue = BUILDER
        .comment("What it costs a capital to bring a good in eases to its new value over about a week when it changes (a new capital's market opening, a town found to have something to spare), instead of jumping in an hour: inland fish no longer doubles in price the moment a coastal market opens. Off, it jumps as before.")
        .define("marketCarryEase", true)
    val MARKET_CARRY_EASE_RATE: ModConfigSpec.DoubleValue = BUILDER
        .comment("How much of what is left of the gap to its new value what it costs to bring a good in closes each day while it eases: 0.3 is nine tenths of the way in a week; 1 is the old jump. Needs Carry Ease.")
        .defineInRange("marketCarryEaseRate", 0.3, 0.05, 1.0)
    val TRADE_OWED_XP: ModConfigSpec.BooleanValue = BUILDER
        .comment("Trade XP for dealing done while you're away (a carter shipping from your homestead, and later a trading house's carters) is kept for you and given when you next log in, with a grey line saying so. Off, dealing while you're away teaches nothing, as before.")
        .define("tradeOwedXp", true)
    val TRADE_OWED_XP_MOST: ModConfigSpec.IntValue = BUILDER
        .comment("The most dealing, in Guild Marks, kept for a player while they're away (each Mark is 2 Trade XP when it's given): 1200 is about a real day away at a farmer's pace. Anything beyond is dropped, so a server left running for a month doesn't hand its owner a month of levels. 0 keeps nothing.")
        .defineInRange("tradeOwedXpMost", 1200, 0, 100000)

    // The trader's life (living-world/trader/design.md section 9): hauls, the Word, carrying, the Hunt, the ladder, the House and the town's answer.
    // Read through trade/TradeConfig, which folds each in with what it needs, so an unloaded spec reads as the defaults. Landed ahead of the systems.
    val TRADE_LIFE: ModConfigSpec.BooleanValue = BUILDER
        .comment("The trader's life, all of it: haul tickets for what a capital runs short of, the Word of the other towns' markets, pack animals, carts and wagons, the Hunt on the road, the ladder from Pedlar to a trading house, and the town's answer to a good delivery. Off, none of it runs: no tickets, boards, Word, Hunts or new House trips, and nothing can be bought for it; what players hold or have written down stays, and every container still opens. Needs Capital Markets.")
        .define("tradeLife", true)
    val TRADE_WORD: ModConfigSpec.BooleanValue = BUILDER
        .comment("The Word: each capital's market is read four times a day, and the boards and the Tally show what the other towns' markets looked like, as old as the road between makes the news. Off, nothing is read, the boards stay blank and a Tally keeps only what its holder has seen. Needs Trader's Life.")
        .define("tradeWord", true)
    val TRADE_WORD_BASE: ModConfigSpec.DoubleValue = BUILDER
        .comment("How old, in hours, news from another town is before the road adds to it: the Word reads this many hours plus Word Delay a Block for every block between the two towns. 0 leaves only the road's share.")
        .defineInRange("tradeWordBase", 12.0, 0.0, 72.0)
    val TRADE_WORD_PER_BLOCK: ModConfigSpec.DoubleValue = BUILDER
        .comment("Hours the road adds to the age of the Word for every block between two towns: at 0.024 a town 740 blocks off is news 18 hours older than the base. 0 makes all news the same age however far it came.")
        .defineInRange("tradeWordPerBlock", 0.024, 0.0, 0.1)
    val TRADE_HAULS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Haul tickets: a capital short of a good that another can spare posts a ticket to have it brought, paid to the carrier who books it and delivers it to the shop it names; goods carried from far away sell to the stalls at a dealer's share, with room for more. Off, no ticket is posted, the open ones wait with their days standing still until it is on again, and the stalls take the usual share and the usual room. Needs Trader's Life.")
        .define("tradeHauls", true)
    val TRADE_HAUL_RATE: ModConfigSpec.DoubleValue = BUILDER
        .comment("The most haul tickets a capital posts in a day, at dawn (a fraction is the chance of one more); in practice about one, as it posts only for a good it is short of that another town can spare. 0 posts none.")
        .defineInRange("tradeHaulRate", 2.5, 0.0, 6.0)
    val TRADE_HAUL_RATE_MAX: ModConfigSpec.DoubleValue = BUILDER
        .comment("The most that rate rises to as more carriers take to the roads: half a ticket a day more for every other carrier who delivered in the last two days, or every carter a trading house hires.")
        .defineInRange("tradeHaulRateMax", 4.0, 0.0, 8.0)
    val TRADE_HAUL_DEPOSIT: ModConfigSpec.DoubleValue = BUILDER
        .comment("The share of a ticket's pay (and at least a Mark) held when a carrier books it, and paid back with the pay; what is not delivered by the due hour costs its share of it. 0 holds nothing.")
        .defineInRange("tradeHaulDeposit", 0.1, 0.0, 0.5)
    val TRADE_DEALER_SHARE: ModConfigSpec.DoubleValue = BUILDER
        .comment("The share of a good's price a stall pays for goods carried from far away (see Haul Distance), where the ordinary share is 0.6 and tops out at 0.68. 0.6 takes the dealer's edge away.")
        .defineInRange("tradeDealerShare", 0.8, 0.6, 0.9)
    val TRADE_HAUL_MIN: ModConfigSpec.IntValue = BUILDER
        .comment("How far, in blocks, a good must have come in the last ten days, from the market it was bought at to the one it is sold or delivered to, to count as hauled.")
        .defineInRange("tradeHaulMin", 600, 0, 2000)
    val TRADE_HAUL_ROOM_DAYS: ModConfigSpec.IntValue = BUILDER
        .comment("Days of the caravans' load a stall makes room for, on top of its usual room, for hauled goods while the town's stock is under what it likes to keep. 0 gives them no extra room.")
        .defineInRange("tradeHaulRoomDays", 2, 0, 6)
    val TRADE_CARRY: ModConfigSpec.BooleanValue = BUILDER
        .comment("Carrying: a harness and a drover's whistle make a tame donkey, mule or llama follow with its panniers, a Chapman's cart is pulled behind you and a Factor's wagon follows on a rein; the market hall's counter sells them. Off, the Carriers section is greyed, harnesses stop following, carts and wagons cannot be taken up, and every container (panniers, cart, wagon) still opens. Needs Trader's Life.")
        .define("tradeCarry", true)
    val TRADE_WAGON: ModConfigSpec.BooleanValue = BUILDER
        .comment("The Carrier's Wagon alone, the one piece that can be cut out whole: the Wagon Bill and its draught horse. Off, no Wagon Bill is sold and a wagon already owned can only be recalled and emptied. Needs Carrying.")
        .define("tradeWagon", true)
    val TRADE_CART_SLOW: ModConfigSpec.DoubleValue = BUILDER
        .comment("How much a cart drags whoever pulls it: 0.15 slows them by 15%. 0 pulls it free.")
        .defineInRange("tradeCartSlow", 0.15, 0.0, 0.5)
    val TRADE_WAGON_REIN: ModConfigSpec.IntValue = BUILDER
        .comment("How many blocks ahead of its wagon a player may walk, leading it by the rein, before it waits for them.")
        .defineInRange("tradeWagonRein", 20, 8, 48)
    val TRADE_HUNT: ModConfigSpec.BooleanValue = BUILDER
        .comment("The Hunt: an outlaw band within reach of a laden trader out on the road may send a party of two to four after their goods, which the trader can fight for, run with or give up. Off, no band hunts anyone. Needs Trader's Life and Road Ambushes.")
        .define("tradeHunt", true)
    val TRADE_HUNT_CHANCE: ModConfigSpec.DoubleValue = BUILDER
        .comment("The Hunt's chance, as a factor on the one worked out for a band and a trader: 1 is as designed, 0.5 half as often, 0 never.")
        .defineInRange("tradeHuntChance", 1.0, 0.0, 3.0)
    val TRADE_HUNT_LADEN: ModConfigSpec.IntValue = BUILDER
        .comment("The least value, in Marks, of the goods a trader carries for a band to think them worth a strike.")
        .defineInRange("tradeHuntLaden", 25, 0, 500)
    val TRADE_HUNT_REACH: ModConfigSpec.IntValue = BUILDER
        .comment("How far, in blocks, a band's camp sees a laden trader.")
        .defineInRange("tradeHuntReach", 160, 32, 400)
    val TRADE_HUNT_KILLS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Whether a Hunt's party may kill. Off, they hunt for goods only and never kill.")
        .define("tradeHuntKills", false)
    val TRADE_HUNT_PURSE: ModConfigSpec.IntValue = BUILDER
        .comment("The purse, in Marks, for every outlaw of a Hunt's party downed, paid when it is won. 0 pays none.")
        .defineInRange("tradeHuntPurse", 2, 0, 10)
    val TRADE_GUARD_WAGE: ModConfigSpec.DoubleValue = BUILDER
        .comment("A hired guard's wage, in Marks a day, paid up front for one to three days. 0 hires them free.")
        .defineInRange("tradeGuardWage", 2.5, 0.0, 10.0)
    val TRADE_RUNGS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The ladder: a trader's deliveries and standing raise them from Pedlar to Hawker, Chapman, Factor and the head of a trading house, and each rung opens what the counter sells and how many tickets and guards they may hold. Off, every gate passes (for testing).")
        .define("tradeRungs", true)
    val TRADE_STORES: ModConfigSpec.BooleanValue = BUILDER
        .comment("The Carriers' Store and the Counting House, the two works the town builds for a trader: offered on the Works screen once a player holds a Warrant and is Trusted there. Off, neither is offered; those already built stand. Needs Town Works.")
        .define("tradeStores", true)
    val TRADE_HOUSE: ModConfigSpec.BooleanValue = BUILDER
        .comment("Hired carters: the owner of a Counting House may hire carters to run wagons on tickets while they are away. Off, no carter is hired and no new trip is set out; the ones on the road finish. Needs Carriers' Stores and Haul Tickets.")
        .define("tradeHouse", true)
    val TRADE_CARTERS: ModConfigSpec.IntValue = BUILDER
        .comment("The most carters a trading house may hire: -1 leaves it at a quarter of the open capitals (the most the pool of tickets can use), 0 allows none.")
        .defineInRange("tradeCarters", -1, -1, 6)
    val TRADE_CARTER_WAGE: ModConfigSpec.IntValue = BUILDER
        .comment("A hired carter's wage, in Marks a day, paid from the House's purse. 0 hires them free.")
        .defineInRange("tradeCarterWage", 3, 0, 10)
    val TRADE_HOUSE_OFFLINE: ModConfigSpec.IntValue = BUILDER
        .comment("How many days a trading house goes on sending out trips after its owner was last in the game; after that it waits for them. 0 stops it the moment they log off.")
        .defineInRange("tradeHouseOffline", 7, 0, 30)
    val TRADE_WORLD: ModConfigSpec.BooleanValue = BUILDER
        .comment("The town's answer to a good delivery: the street turns and cheers, the capital's fortunes note it, residents warm to the carrier, and word of a big one travels to the other towns. Off, no cheer, favour, fortune or rumour; deliveries still pay. Needs Trader's Life.")
        .define("tradeWorld", true)
    val TRADE_CHEER: ModConfigSpec.BooleanValue = BUILDER
        .comment("The street cheering a delivery, the scene alone. Off, nobody turns or cheers (the fortunes, favours and rumours go on). Needs The Town Answers.")
        .define("tradeCheer", true)

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

    // Tales: a capital's troubles told as stories, with endings that last (tales/, living-world/stories/design.md section 12.1)
    val TOWN_TALES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Tales: now and then something in a capital's records (a debt, a death, a wagon taken, a child gone down the road) becomes a story the people tell you in their own words, with choices that change how it ends and what the town remembers. Off, no tale starts, shows or moves, and the people go about their days; tales already open wait for it to come back on.")
        .define("townTales", true)
    val TOWN_TALE_SCENES: ModConfigSpec.BooleanValue = BUILDER
        .comment("The street scenes of tales: the people act their trouble out where you can see it (a quarrel in the lane, a calling at the gate, a laying of flowers), and the story sets a child on the road or a family at a camp where it needs them. Off, tales are told by talk, rumour and the tracker alone, and the five that need a person set in the world are not begun.")
        .define("townTaleScenes", true)
    val TOWN_TALE_PACE: ModConfigSpec.DoubleValue = BUILDER
        .comment("How readily a capital you visit starts a tale once its records show a trouble: 1 is even odds each day, 2 is every day, 0.5 one day in four, and 0 starts none (tales already open go on). Eight quiet days follow every tale whatever this says.")
        .defineInRange("townTalePace", 1.0, 0.0, 2.0)
    val TOWN_TALE_MEMORIALS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The marks tales leave in the world: a memorial stone in a capital, a cairn by the road, and a posy and a lantern beside them. Off, they are remembered in the town's talk and chronicle but no block is set.")
        .define("townTaleMemorials", true)

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

    // Hearthcraft, the cook's life (cooking/, living-world/cook/design.md section 10.1): read through Cooking, so an unloaded spec reads as the default
    val COOKING: ModConfigSpec.BooleanValue = BUILDER
        .comment("Hearthcraft, the cook's life: stewpots, hobs, bake ovens and spits to cook at, twelve kinds of dish that give a meal's effect, the back doors of the capitals' inns and bakeries that buy them, and the town's kitchens, talk and plates round them. Off, the stations stand and say Hearthcraft is off; nothing is cooked, sold or judged, the town's kitchens, talk and plates go quiet, Winter Chill stops, and a dish in the pack feeds as plain food with no effect. Pots keep what is in them.")
        .define("cooking", true)
    val KITCHEN_DOORS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Cooks sell dishes at the back doors of a capital's inn and bakery, and fill the day's request on the inn's slate. Off, the innkeeper and the baker buy nothing (\"I only buy through the market\"), there is no slate and no request; the alms table still takes dishes. Needs Hearthcraft and Capital Markets.")
        .define("kitchenDoors", true)
    val COOK_SHOPS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A trusted cook can have a cookshop of their own in a capital, a works the town builds, which sells their dishes while they are away. Off, no cookshop is wanted and an open one stops selling (its stock and till are kept) and its keeper idles. Needs Hearthcraft, Capital Markets and Town Works.")
        .define("cookShops", true)
    val COOK_OFF: ModConfigSpec.BooleanValue = BUILDER
        .comment("Sheafmoot's Harvest Table: a cook-off beside the Produce Show, a dish judged by the host, the town's own entries, and the Gilded Ladle for the prize. Off, Sheafmoot has only its Produce Show and the host says they only look at the harvest. Needs Hearthcraft and Festivals.")
        .define("cookOff", true)
    val HOME_KITCHENS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A homestead is fitted with a kitchen as it grows, a stewpot, an oven and a herb bed, and its stations burn no fuel while its people are home. Off, no kitchen is fitted into a homestead and its stations pay for fuel like anyone's; what was fitted stays. Needs Hearthcraft and Homesteads.")
        .define("homeKitchens", true)
    val TOWN_KITCHENS: ModConfigSpec.BooleanValue = BUILDER
        .comment("The capitals' kitchens come alive: the innkeeper and the baker work at a pot, an oven and a spit in scenes you can watch, older capitals are fitted with the same stations, and the diners' plates are laid at the tables. Off, no kitchen scene plays and no older capital is refitted (the house pot is a still display); a new capital's plan still draws its kitchen blocks, and the back doors do not depend on this. Needs Hearthcraft and Living Capitals.")
        .define("townKitchens", true)
    val COOK_ROAST: ModConfigSpec.BooleanValue = BUILDER
        .comment("The rest-day roast: on a rest day the baker's spit turns and a roast is carved for the square. Off, no roast; the spit stands.")
        .define("cookRoast", true)
    val COOK_TALK: ModConfigSpec.BooleanValue = BUILDER
        .comment("The townsfolk talk about the kitchen: what the inn is serving, the day's request, a dish that was relished, a pot that burnt, a hallmarked plate. Off, the kitchen is no topic and its choice says nothing; the keepers still taste and pay at the back door. Needs Hearthcraft and Townsfolk Talk.")
        .define("cookTalk", true)
    val WINTER_CHILL: ModConfigSpec.BooleanValue = BUILDER
        .comment("Winter bites: a player out in the cold of winter is Chilled (a little hunger, slower stamina, never damage) until they find a roof, a fire, a torch, daylight, leather or a hot meal. Off, nobody is ever Chilled. Needs Hearthcraft and Seasons.")
        .define("winterChill", true)
    val DISH_SPOILAGE: ModConfigSpec.BooleanValue = BUILDER
        .comment("Dishes go stale and then off with the days (a stale dish sells for less and gives less; one gone off only feeds, as rotten flesh does). Off, nothing ever ages.")
        .define("dishSpoilage", true)
    val COOK_POTS_PRIVATE: ModConfigSpec.BooleanValue = BUILDER
        .comment("Only the player who set a cooking station down, and their party, can use it. Off, anyone can add to, serve from and tip out a public pot. (A homestead's own protection already closes its stations.)")
        .define("cookPotsPrivate", false)
    val COOK_FUEL_SCALE: ModConfigSpec.DoubleValue = BUILDER
        .comment("How far fuel goes under a cooking pot: seconds of mid-heat cooking for each second of a fuel's furnace burn time. 4 is the usual; 1 is the furnace's own pace.")
        .defineInRange("cookFuelScale", 4.0, 1.0, 20.0)

    // Living villages (living-world/villages/design.md section 15.1)
    val LIVING_VILLAGES: ModConfigSpec.BooleanValue = BUILDER
        .comment("The Living World outside the capitals: every village is counted (its homes, beds, wells, lamps and stalls) when a player first comes near, and its people, market, wagons, lamps, festivals and sieges go by what really happens there. Off, no village is counted, villagers who were made townsfolk are given back at night, and wagons, lamps, festivals and sieges are as they were; what was recorded is kept for when it's on again.")
        .define("livingVillages", true)
    val VILLAGE_FOLK: ModConfigSpec.BooleanValue = BUILDER
        .comment("A village's villagers become townsfolk, each with a name, a home, a bed, a trade and a day of their own, who talk and remember as a capital's do; the vanilla villager is kept, so its real trades are still on offer in the talk. They are swapped at night, out of sight, with nobody within 48 blocks. Off, nobody new is taken in and those taken in are given back at night, village by village. The village is still counted and its market, pedlar, wagons, lamps and building still run, but what needs a townsperson (days, talk, memory, the bellman, the watch, shelter, births and newcomers) is not there for vanilla villagers.")
        .define("villageFolk", true)
    val VILLAGE_MARKETS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A village has a market like a capital's: it grows, makes, eats and trades real goods an hour at a time, and its counters (the baker's oven, the smith's anvil, and stalls once it has built a market) buy and sell at prices that follow what it holds. Needs Capital Markets. Off, villages have no market, no pedlar, no wagon calls and no fortunes.")
        .define("villageMarkets", true)
    val VILLAGE_PEDLAR: ModConfigSpec.BooleanValue = BUILDER
        .comment("A pedlar comes down the road with a handcart every two to four days and on market day, parks it on the green and trades; the pedlar is how a village on no capital's road is supplied. Needs Village Markets. Off, there is no pedlar: only a capital's wagons supply a village, and a village with no wagon goes without.")
        .define("villagePedlar", true)
    val VILLAGE_WAGONS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A capital's wagons make a round trip to the village at the end of their road: the village's surplus goes in, the capital's goods and news come back, a wagon taken is a hole in both stocks, and a shut road cuts the village off. Needs Caravans and Village Markets. Off, the wagons are the capital's alone, as they were, and the pedlar serves a linked village.")
        .define("villageWagons", true)
    val VILLAGE_FORTUNES: ModConfigSpec.BooleanValue = BUILDER
        .comment("A village has good times and hard, worked out each day from what really happened to it (hunger, a wagon taken, a siege held or lost, deaths and festivals), and they show in what its people say and in its dressing. Needs Town Fortunes and Village Markets. Off, every village reads steady and has no dressing.")
        .define("villageFortunes", true)
    val VILLAGE_BELLMAN: ModConfigSpec.BooleanValue = BUILDER
        .comment("A bellman, the villager who holds the bell for the week, goes to the bell at noon on market days and whenever there is news worth telling (a cart or a wagon in, the road shut, a birth, a death, a siege held or lost, the capital's news by the wagon) and cries it; the villagers talk of the village's own affairs. Off, no cry and no village talk: the villagers say what they said before.")
        .define("villageBellman", true)
    val VILLAGE_LAMPS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A village's lamp posts are lit at dusk and put out at dawn by a lamplighter on a round of his own, and plain lanterns on posts are swapped for street lamps, out of sight. Needs Street Lamps. Off, the lamps burn day and night as before; a lamp already swapped stays a street lamp, lit.")
        .define("villageLamps", true)
    val VILLAGE_DRESSING: ModConfigSpec.BooleanValue = BUILDER
        .comment("In good times garlands and pennants go up on the green and the cottages, and come down when times are hard. Needs Village Fortunes. Off, none are put up and those that are up are taken down.")
        .define("villageDressing", true)
    val VILLAGE_TROUBLE: ModConfigSpec.BooleanValue = BUILDER
        .comment("When a siege or a raid comes, the folk run to the nearest door and bolt it, a village watch of guards stands the walls, the dead are remembered with a candle at a stone by the bell, and a crew mends the damage next morning. Needs Village Sieges for sieges. Off, sieges and raids are as before: the folk flee on their own, nothing is mended and nobody is remembered.")
        .define("villageTrouble", true)
    val VILLAGE_BUILDS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A project funded at a village's board (lamp posts, hall, palisade, tower, market, walls, keep) is laid by a crew over one to five days: the village's own craftsfolk, hands from the road and a foreman, putting the blocks up in their order. A trade road is laid along its way as before. Off, a project goes up at once, as before.")
        .define("villageBuilds", true)
    val VILLAGE_BUILD_SPEED: ModConfigSpec.DoubleValue = BUILDER
        .comment("How fast a village's crews work: 1 lays a project in one to five days; 2 is twice as fast.")
        .defineInRange("villageBuildSpeed", 1.0, 0.1, 20.0)
    val VILLAGE_GROWTH: ModConfigSpec.BooleanValue = BUILDER
        .comment("Villages grow from within: a home with a free bed and a fed household has a child every week or so, who grows up in about eight days, and a newcomer comes in off the road to fill an empty home. Off, no new people come, the dead stay dead and a village shrinks only by what happens to it.")
        .define("villageGrowth", true)
    val VILLAGE_GOLEMS: ModConfigSpec.BooleanValue = BUILDER
        .comment("A village with too few iron golems for its folk is given one back at night, out of sight (one for every ten folk, at least one and at most three). Off, only vanilla's own golem spawning applies.")
        .define("villageGolems", true)
    val MAX_LIVE_VILLAGES: ModConfigSpec.IntValue = BUILDER
        .comment("The most villages worked out at once. Past it, the ones seen longest ago are paused, and caught up (72 hours at most) when they wake. Lower is lighter on the server.")
        .defineInRange("maxLiveVillages", 48, 4, 200)
    val MAX_VILLAGE_FOLK: ModConfigSpec.IntValue = BUILDER
        .comment("The most people one village can have. At the cap no children are born and no newcomers come.")
        .defineInRange("maxVillageFolk", 60, 8, 120)

    val SPEC: ModConfigSpec = BUILDER.build()
}
