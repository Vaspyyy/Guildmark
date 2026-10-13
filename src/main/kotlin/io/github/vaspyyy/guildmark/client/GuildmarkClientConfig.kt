package io.github.vaspyyy.guildmark.client

import net.neoforged.neoforge.common.ModConfigSpec

/** Settings for this player's own screen, kept in the game's config folder. */
object GuildmarkClientConfig {
    private val BUILDER = ModConfigSpec.Builder()

    val COMBAT_HUD: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show health, focus and stamina as bars in the top left (instead of hearts) while the action combat is on.")
        .define("combatHud", true)
    val CAMERA_SHAKE: ModConfigSpec.BooleanValue = BUILDER
        .comment("Shake and tilt the camera on heavy hits, rolls and staggers.")
        .define("cameraShake", true)
    val CUTSCENES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Play boss introductions as short cutscenes.")
        .define("cutscenes", true)
    val DIALOGUE_CAMERA: ModConfigSpec.BooleanValue = BUILDER
        .comment("Move the camera over your shoulder while a story character talks.")
        .define("dialogueCamera", true)
    val COMPASS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show the compass strip across the top of the screen, with the places you've found and where your contracts lead.")
        .define("compass", true)
    val DEATH_SCREEN: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show Guildmark's own death screen instead of the vanilla one.")
        .define("deathScreen", true)
    val CONTRACT_TRACKER: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show the contract tracker: your contracts, a rival's challenge and the story's next step. The tracker key shows and hides it too.")
        .define("contractTracker", true)
    val PLACE_BANNERS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show a place's name across the screen when you find it, and again when you come back after a while.")
        .define("placeBanners", true)
    val REGION_TITLES: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show a region's name across the screen as you enter it.")
        .define("regionTitles", true)
    val REGION_MUSIC: ModConfigSpec.BooleanValue = BUILDER
        .comment("Play each region's own theme in place of the usual music.")
        .define("regionMusic", true)
    val REGION_ATMOSPHERE: ModConfigSpec.BooleanValue = BUILDER
        .comment("Tint the fog and drift ash, snow, spores or glints through the air in the regions that have them.")
        .define("regionAtmosphere", true)
    val MENU_SOUNDS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Play Guildmark's own sounds as its screens open, close and turn pages, and as their buttons are pressed. Off gives the usual click.")
        .define("menuSounds", true)
    val TITLE_SCREEN: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show Guildmark's own title screen and loading screens, with its painted dusk behind the other menus. Off gives the vanilla ones.")
        .define("titleScreen", true)
    val MENU_MUSIC: ModConfigSpec.BooleanValue = BUILDER
        .comment("Play Guildmark's own theme in the menus instead of the vanilla menu music.")
        .define("menuMusic", true)
    val CITY_SOUNDS: ModConfigSpec.IntValue = BUILDER
        .comment("How loud towns and cities sound, from bells and crowds to the wind on the walls (as a share of the game's Ambient/Environment volume). 0 silences them.")
        .defineInRange("citySounds", 100, 0, 100)
    val CITY_BELLS: ModConfigSpec.IntValue = BUILDER
        .comment("The bells that keep the hours of the day: dawn, noon and dusk struck in full (a big town's chime first), and three strokes at nine, three and nine.")
        .defineInRange("cityBells", 100, 0, 100)
    val CITY_CROWDS: ModConfigSpec.IntValue = BUILDER
        .comment("The crowd's chatter, market criers, coins and carts by day, and the watch calling the hours at night.")
        .defineInRange("cityCrowds", 100, 0, 100)
    val CITY_WORKSHOPS: ModConfigSpec.IntValue = BUILDER
        .comment("Smiths at their anvils, bellows, grindstones and carpenters, wherever a villager is at work.")
        .defineInRange("cityWorkshops", 100, 0, 100)
    val CITY_HARBOUR: ModConfigSpec.IntValue = BUILDER
        .comment("Water lapping at the quays, gulls, creaking moorings and the odd ship's bell, in towns by the water.")
        .defineInRange("cityHarbour", 100, 0, 100)
    val CITY_WIND: ModConfigSpec.IntValue = BUILDER
        .comment("The wind, gusts and cracking banners high on walls and towers.")
        .defineInRange("cityWind", 100, 0, 100)

    val SPEC: ModConfigSpec = BUILDER.build()
}
