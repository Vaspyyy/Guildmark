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
    val DEATH_SCREEN: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show Guildmark's own death screen instead of the vanilla one.")
        .define("deathScreen", true)

    val SPEC: ModConfigSpec = BUILDER.build()
}
