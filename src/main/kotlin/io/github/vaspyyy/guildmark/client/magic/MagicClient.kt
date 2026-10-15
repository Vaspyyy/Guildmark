package io.github.vaspyyy.guildmark.client.magic

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.client.combat.CameraFx
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.combat.Move
import io.github.vaspyyy.guildmark.magic.CatalystItem
import io.github.vaspyyy.guildmark.magic.Magic
import io.github.vaspyyy.guildmark.magic.MagicRegistry
import io.github.vaspyyy.guildmark.magic.QuickCastPayload
import io.github.vaspyyy.guildmark.magic.Spell
import io.github.vaspyyy.guildmark.magic.SpellZone
import io.github.vaspyyy.guildmark.magic.Spellbook
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.entity.NoopRenderer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import net.neoforged.neoforge.common.NeoForge

/**
 * The client half of magic: which attuned spell is picked (cycled with [NEXT_SPELL]), turning a press of the
 * use button with a catalyst in hand into a cast, and what the caster sees and hears of their own casts as
 * they go off: the spell's sound at once, the shake of the big ones, Flicker carrying them on. Also the
 * HUD slot, the renderers and the mote particle.
 */
object MagicClient {
    val NEXT_SPELL = KeyMapping("key.guildmark.next_spell", InputConstants.Type.KEYBOARD, InputConstants.KEY_Z, CombatClient.CATEGORY)

    /** The spell picked, by id; null is the first attuned. */
    private var selected: String? = null
    /** A cast was pressed and the use button hasn't been let go since: one cast a press. */
    private var latched = false
    /** Ticks this client has run, and when the pick last changed (the HUD names the spell for a moment). */
    var ticks = 0L
        private set
    var changedAt = -1000L
        private set

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterGuiLayersEvent::class.java) { it.registerAbove(VanillaGuiLayers.HOTBAR, SpellHud.ID, SpellHud) }
        modBus.addListener(RegisterParticleProvidersEvent::class.java) { event ->
            event.registerSpriteSet(MagicRegistry.ARCANE_MOTE.get()) { sprites -> ArcaneMoteParticle.Provider(sprites) }
        }
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(MagicRegistry.SPELL_BOLT.get(), ::SpellBoltRenderer)
            event.registerEntityRenderer(MagicRegistry.SPELL_ZONE.get()) { NoopRenderer<SpellZone>(it) }
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ ->
            selected = null
            latched = false
        }
    }

    private fun tick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.isPaused) return
        ticks++
        if (!minecraft.options.keyUse.isDown) latched = false
        while (NEXT_SPELL.consumeClick()) if (minecraft.gui.screen() == null) cycle(player)
    }

    // ---- Picking a spell ----

    /**
     * The spell a cast press makes: the one picked, when a catalyst of its school is in hand; otherwise the
     * first attuned spell one in hand can cast (a staff and a sword: the first sorcery).
     */
    fun chosen(player: Player): Spell? {
        val attuned = Spellbook.get(player).attunedSpells()
        if (attuned.isEmpty()) return null
        val picked = attuned.firstOrNull { it.id == selected } ?: attuned[0]
        if (CatalystItem.handFor(player, picked.school) != null) return picked
        return attuned.firstOrNull { CatalystItem.handFor(player, it.school) != null } ?: picked
    }

    /** The next attuned spell along, skipping those no catalyst in hand can cast (when one in hand can cast any). */
    private fun cycle(player: LocalPlayer) {
        val attuned = Spellbook.get(player).attunedSpells()
        if (attuned.isEmpty()) return
        val castable = attuned.filter { CatalystItem.handFor(player, it.school) != null }
        val pool = castable.ifEmpty { attuned }
        val current = chosen(player)
        val next = pool[(pool.indexOf(current) + 1).mod(pool.size)]
        changedAt = ticks
        if (next == current && pool.size == 1) return
        selected = next.id
        player.playSound(MagicRegistry.CYCLE.get(), 0.5f, 1f)
    }

    // ---- Casting ----

    /** Use pressed with a catalyst in hand (see CatalystItem). True when the press is magic's. */
    fun onUse(player: Player): Boolean {
        val local = player as? LocalPlayer ?: return false
        if (latched) return true
        latched = true
        if (CombatClient.active()) {
            CombatClient.cast()
            return true
        }
        // Without the action combat, a quick cast
        val spell = castable(local) ?: return true
        val hand = CatalystItem.handFor(local, spell.school) ?: return true
        if (local.cooldowns.isOnCooldown(local.getItemInHand(hand))) return true
        ClientPacketDistributor.sendToServer(QuickCastPayload(spell.ordinal))
        return true
    }

    /** The move a cast press makes now, or null (having said why) when the spell picked can't be cast. */
    fun castMove(local: LocalPlayer): Move? = castable(local)?.move

    private fun castable(local: LocalPlayer): Spell? {
        val spell = chosen(local)
        if (spell == null) {
            tell(local, Component.translatable("message.guildmark.spell.none"))
            return null
        }
        val problem = Magic.problem(local, spell) ?: return spell
        tell(local, when (problem) {
            "message.guildmark.spell.needs" -> Component.translatable(problem, spell.title, spell.school.stat.title, spell.need)
            else -> Component.translatable(problem, spell.title)
        })
        local.playSound(MagicRegistry.FIZZLE.get(), 0.5f, 1f)
        return null
    }

    private fun tell(local: LocalPlayer, text: Component) {
        local.sendOverlayMessage(text.copy().withColor(0xE05A4A))
    }

    /** One tick of the local player's cast: what they see and hear of it themselves. */
    fun tickCast(local: LocalPlayer, a: CombatClient.Action, before: Float, now: Float) {
        val move = a.move
        val spell = Spell.of(move) ?: return
        if (a.charging) return
        if (before < move.castAt && now >= move.castAt) {
            // The server plays it for everyone else
            local.playSound(Magic.castSound(spell), 1f, 0.95f + local.random.nextFloat() * 0.1f)
            when (spell) {
                Spell.DAWNBURST -> CameraFx.shake(0.4f + 0.2f * a.chargeFraction())
                Spell.HALLOWED_GROUND -> CameraFx.shake(0.2f)
                Spell.RIME_LANCE -> CameraFx.shake(0.08f + 0.15f * a.chargeFraction())
                Spell.STARBREAK -> CameraFx.shake(0.12f)
                Spell.FLICKER -> CameraFx.punch(0.09f)
                else -> {}
            }
        }
        if (spell == Spell.FLICKER && now >= move.castAt) blink(local, a)
    }

    /** Flicker carries its caster a few blocks the way they chose, fast, then lets them go. */
    private fun blink(local: LocalPlayer, a: CombatClient.Action) {
        if (a.lunges > Magic.BLINK_TICKS) return
        a.lunges++
        if (a.lunges > Magic.BLINK_TICKS) {
            // Arrived: stop dead rather than slide on
            local.deltaMovement = local.deltaMovement.multiply(0.15, 1.0, 0.15)
            return
        }
        val direction = Vec3.directionFromRotation(0f, a.yaw)
        val speed = Magic.BLINK_SPEED * (if (local.onGround()) 1.0 else 0.6)
        local.deltaMovement = Vec3(direction.x * speed, local.deltaMovement.y.coerceAtMost(0.1), direction.z * speed)
    }

    /** A star landing near the local player shakes the ground under them. */
    fun onStarLanded(at: Vec3) {
        val player = Minecraft.getInstance().player ?: return
        val distance = player.position().distanceTo(at)
        if (distance < 24.0) CameraFx.shake(0.75f * (1f - (distance / 24.0).toFloat()))
    }
}
