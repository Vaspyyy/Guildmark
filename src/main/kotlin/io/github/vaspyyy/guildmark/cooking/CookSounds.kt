package io.github.vaspyyy.guildmark.cooking

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * The sound events of Hearthcraft (living-world/cook/design.md 11.2, Appendix C.5): 44 events `guildmark:cook.<name>`, made
 * by `art/audio/make_cook_audio.py` and listed in `sounds.json` through its fragment (merged by `art/lang/merge_lang.py`).
 * An event with no entry in `sounds.json` is silent, so they can be named and played from the first commit and sound
 * once Part M2 lands. [NAMES] is the contract: M2 adds a line to it only if a sound is added.
 */
object CookSounds {
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)

    /** The 44 events, in the order of Appendix C.5. */
    val NAMES: List<String> = listOf(
        "cook.place", "cook.pour", "cook.plop.soft", "cook.plop.meat", "cook.plop.grain", "cook.fuel", "cook.ignite",
        "cook.stir", "cook.taste", "cook.simmer", "cook.call", "cook.crackle", "cook.sizzle", "cook.ease", "cook.boil_over",
        "cook.skim", "cook.ready", "cook.ripe", "cook.serve", "cook.tier.plain", "cook.tier.sound", "cook.tier.fine",
        "cook.tier.hallmark", "cook.burnt", "cook.eat.spoon", "cook.eat.bite", "cook.drink", "cook.buff", "cook.oven_hum",
        "cook.oven_open", "cook.oven_shut", "cook.spit_creak", "cook.crank", "cook.drip", "cook.flare", "cook.flip",
        "cook.chop", "cook.slice", "cook.carve", "cook.loaf_down", "cook.plate", "cook.chalk", "cook.larder", "cook.chill",
    )

    private val EVENTS: Map<String, DeferredHolder<SoundEvent, SoundEvent>> = NAMES.associateWith { name ->
        SOUNDS.register(name) { -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)) }
    }

    /** The event named [name], one of [NAMES] (`cook.stir`, `cook.tier.fine`...). */
    fun get(name: String): SoundEvent = EVENTS.getValue(name).get()

    fun register(modBus: IEventBus) {
        SOUNDS.register(modBus)
    }
}
