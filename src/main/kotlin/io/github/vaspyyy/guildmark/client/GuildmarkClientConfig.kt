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
    val MENU_SOUNDS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Play Guildmark's own sounds as its screens open, close and turn pages, and as their buttons are pressed. Off gives the usual click.")
        .define("menuSounds", true)
    val TITLE_SCREEN: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show Guildmark's own title screen and loading screens, with its painted dusk behind the other menus. Off gives the vanilla ones.")
        .define("titleScreen", true)
    val MENU_MUSIC: ModConfigSpec.BooleanValue = BUILDER
        .comment("Play Guildmark's own theme in the menus instead of the vanilla menu music.")
        .define("menuMusic", true)

    val SPEC: ModConfigSpec = BUILDER.build()
}
