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
    val MUSIC_WARDEN = sound("music.fallen_warden")
    val MUSIC_WARDEN_2 = sound("music.fallen_warden_2")
}
