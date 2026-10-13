package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/** The mod's own sounds. The files and subtitles are listed in assets/guildmark/sounds.json. */
object ModSounds {
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)

    private fun sound(name: String): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)) }

    val SWING_LIGHT = sound("combat.swing_light")
    val SWING_HEAVY = sound("combat.swing_heavy")
    val WHIRL = sound("combat.whirl")
    val CHARGE = sound("combat.charge")
    val HIT_FLESH = sound("combat.hit_flesh")
    val HIT_ARMOR = sound("combat.hit_armor")
    val CRITICAL = sound("combat.critical")
    val ROLL = sound("combat.roll")
    val BACKSTEP = sound("combat.backstep")
    val PARRY = sound("combat.parry")
    val GUARD = sound("combat.guard")
    val GUARD_BREAK = sound("combat.guard_break")
    val STANCE_BREAK = sound("combat.stance_break")
    val STAGGER = sound("combat.stagger")
    val LOCK_ON = sound("combat.lock_on")
    val OUT_OF_STAMINA = sound("combat.out_of_stamina")

    val SOLDIER_AMBIENT = sound("hollow_soldier.ambient")
    val SOLDIER_HURT = sound("hollow_soldier.hurt")
    val SOLDIER_DEATH = sound("hollow_soldier.death")
    val SOLDIER_ATTACK = sound("hollow_soldier.attack")

    val WARDEN_AMBIENT = sound("fallen_warden.ambient")
    val WARDEN_HURT = sound("fallen_warden.hurt")
    val WARDEN_DEATH = sound("fallen_warden.death")
    val WARDEN_ROAR = sound("fallen_warden.roar")
    val WARDEN_SLAM = sound("fallen_warden.slam")
    val WARDEN_SWING = sound("fallen_warden.swing")
    val WARDEN_FLAME = sound("fallen_warden.flame")
    val WARDEN_STEP = sound("fallen_warden.step")
    val WARDEN_VOICE_INTRO = sound("fallen_warden.voice_intro")
    val WARDEN_VOICE_PHASE2 = sound("fallen_warden.voice_phase2")
    val WARDEN_VOICE_DEATH = sound("fallen_warden.voice_death")

    val BOSS_APPEAR = sound("ui.boss_appear")
    val FOE_VANQUISHED = sound("ui.foe_vanquished")
    val UI_OPEN = sound("ui.open")
    val UI_CLOSE = sound("ui.close")
    val UI_HOVER = sound("ui.hover")
    val UI_SELECT = sound("ui.select")
    val UI_PAGE = sound("ui.page")
    val UI_TEXT = sound("ui.text")
    val UI_DEATH_TOLL = sound("ui.death_toll")
    val UI_SEAL = sound("ui.seal")
    val UI_DISCOVER = sound("ui.discover")

    val WARDFIRE_KINDLE = sound("wardfire.kindle")
    val WARDFIRE_REST = sound("wardfire.rest")
    val WARDFIRE_TRAVEL = sound("wardfire.travel")
    val FLASK_DRINK = sound("flask.drink")
    val MARKS_RECOVER = sound("marks.recover")
    val MIST_PASS = sound("dungeon.mist_pass")
    val MIST_DENY = sound("dungeon.mist_deny")
    val DUNGEON_STIR = sound("dungeon.stir")

    val TROLL_ROAR = sound("hill_troll.roar")
    val TROLL_GRUNT = sound("hill_troll.grunt")
    val TROLL_HURT = sound("hill_troll.hurt")
    val TROLL_DEATH = sound("hill_troll.death")
    val TROLL_SNORE = sound("hill_troll.snore")
    val TROLL_STEP = sound("hill_troll.step")
    val TROLL_SWING = sound("hill_troll.swing")
    val TROLL_SLAM = sound("hill_troll.slam")
    val TROLL_THROW = sound("hill_troll.throw")
    val TROLL_BOULDER = sound("hill_troll.boulder")

    val RIDER_NEIGH = sound("dusk_rider.neigh")
    val RIDER_SNORT = sound("dusk_rider.snort")
    val RIDER_HOOF = sound("dusk_rider.hoof")
    val RIDER_HURT = sound("dusk_rider.hurt")
    val RIDER_DEATH = sound("dusk_rider.death")
    val RIDER_HORN = sound("dusk_rider.horn")
    val RIDER_SWING = sound("dusk_rider.swing")
    val RIDER_SLAM = sound("dusk_rider.slam")
    val RIDER_WISP = sound("dusk_rider.wisp")
    val RIDER_WISP_BURST = sound("dusk_rider.wisp_burst")
    val RIDER_IGNITE = sound("dusk_rider.ignite")
    val RIDER_VANISH = sound("dusk_rider.vanish")

    val MUSIC_WARDEN = sound("music.fallen_warden")
    val MUSIC_WARDEN_2 = sound("music.fallen_warden_2")
    val MUSIC_FIELD_BOSS = sound("music.field_boss")
    val MUSIC_DUSK_RIDER = sound("music.dusk_rider")
    val MUSIC_MENU = sound("music.menu")
}
