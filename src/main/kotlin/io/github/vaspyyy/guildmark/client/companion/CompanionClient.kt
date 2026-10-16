package io.github.vaspyyy.guildmark.client.companion

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.companion.CompanionEntity
import io.github.vaspyyy.guildmark.companion.CompanionRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import java.util.WeakHashMap

/** The companions on screen: drawn in their own looks, heard in their own voices, and the one beside you on the HUD. */
object CompanionClient {
    /** Blocks off a companion can be heard saying anything. */
    private const val HEARING = 32.0

    /** Each companion's line being spoken, so a new one cuts it off rather than talking over it. */
    private val speaking = WeakHashMap<CompanionEntity, SoundInstance>()

    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { it.registerEntityRenderer(CompanionRegistry.COMPANION.get(), ::CompanionRenderer) }
        modBus.addListener(RegisterGuiLayersEvent::class.java) { it.registerAbove(VanillaGuiLayers.HOTBAR, CompanionHud.ID, CompanionHud) }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> CompanionHud.tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ ->
            CompanionHud.reset()
            speaking.clear()
        }
        CompanionEntity.onSpeech = ::onSpeech
    }

    /**
     * [c] says something: in their voice from where they stand (where there's a recording of it), over their head,
     * and, when they're the player's own, on the HUD. A line the language file doesn't have is left unsaid.
     */
    private fun onSpeech(c: CompanionEntity) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val key = c.speech
        if (key.isEmpty() || c.distanceToSqr(player) > HEARING * HEARING) return
        val lang = "character.guildmark.${c.kind.id}.$key"
        if (!Language.getInstance().has(lang)) return
        val voice = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "voice.${c.kind.id}.$key")
        speaking.remove(c)?.let { minecraft.soundManager.stop(it) }
        if (minecraft.soundManager.getSoundEvent(voice) != null) {
            val sound = EntityBoundSoundInstance(SoundEvent.createVariableRangeEvent(voice), SoundSource.VOICE, 1f, 1f, c, c.random.nextLong())
            minecraft.soundManager.play(sound)
            speaking[c] = sound
        }
        if (c.ownerId == player.uuid) CompanionHud.say(c, Component.translatable(lang))
    }
}
