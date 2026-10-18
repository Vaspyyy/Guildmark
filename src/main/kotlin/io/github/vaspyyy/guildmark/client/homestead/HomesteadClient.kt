package io.github.vaspyyy.guildmark.client.homestead

import io.github.vaspyyy.guildmark.homestead.ClaimCheckPayload
import io.github.vaspyyy.guildmark.homestead.ClaimVerdictPayload
import io.github.vaspyyy.guildmark.homestead.HomesteadRegistry
import io.github.vaspyyy.guildmark.homestead.HomesteadSyncPayload
import io.github.vaspyyy.guildmark.homestead.LedgerPayload
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import net.neoforged.neoforge.common.NeoForge

/**
 * The client's half of homesteads: every homestead as the server last said (for the bounds and the map), the spot a
 * charter in hand is pointing at and what the server's survey made of it, the ledger, the crates' screens and the
 * Charter Post's pennant.
 */
object HomesteadClient {
    var homesteads: List<HomesteadSyncPayload.Entry> = listOf()
        private set

    /** What a survey found: for the spot [post] facing [facing] (none: a claim or a commission just refused). */
    class Verdict(val ok: Boolean, val reason: Component, val highlight: List<BlockPos>, val post: BlockPos?, val facing: Int, val at: Long)

    /** The survey of the spot the charter last pointed at. */
    var verdict: Verdict? = null
        private set

    /** A refusal whose places at fault stay picked out on the ground a while. */
    var refusal: Verdict? = null
        private set

    /** The spot the charter in hand points at now, where its post would stand, and which way the plot would reach. */
    var target: BlockPos? = null
        private set
    var targetFacing: Direction = Direction.SOUTH
        private set

    /** Client ticks, for the bounds' and the ledger's animations. */
    var ticks = 0L
        private set

    private var sentPost: BlockPos? = null
    private var sentFacing = -1
    private var sentAt = -1000L
    private var saidOk: Boolean? = null

    fun register(modBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent::class.java) { event: SubmitCustomGeometryEvent -> HomesteadBounds.submit(event) }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
        modBus.addListener(RegisterMenuScreensEvent::class.java) { event ->
            val screen = MenuScreens.ScreenConstructor<ChestMenu, GoodsScreen> { menu, inventory, title -> GoodsScreen(menu, inventory, title) }
            event.register(HomesteadRegistry.SUPPLY_MENU.get(), screen)
            event.register(HomesteadRegistry.SHIPPING_MENU.get(), screen)
        }
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(HomesteadRegistry.CHARTER_POST_ENTITY.get(), ::CharterPostRenderer)
        }
    }

    /** Whether [player] has a charter in either hand. */
    fun holdsCharter(player: Player): Boolean =
        player.mainHandItem.`is`(HomesteadRegistry.CHARTER.get()) || player.offhandItem.`is`(HomesteadRegistry.CHARTER.get())

    private fun tick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        ticks++
        // Where a charter would put its post: on top of the block looked at, as using it there does
        val hit = minecraft.hitResult as? BlockHitResult
        val aiming = holdsCharter(player) && level.dimension() == Level.OVERWORLD && minecraft.gui.screen() == null &&
            hit != null && hit.type == HitResult.Type.BLOCK && hit.direction == Direction.UP
        target = if (aiming) hit!!.blockPos.above() else null
        targetFacing = player.direction
        val at = target
        if (at != null) {
            // Asked again when the spot or the facing changes (no faster than the server listens), and now and then anyway
            val changed = at != sentPost || targetFacing.get2DDataValue() != sentFacing
            if (changed && ticks - sentAt >= ASK_EVERY || ticks - sentAt >= ASK_AGAIN) {
                ClientPacketDistributor.sendToServer(ClaimCheckPayload(at, targetFacing.get2DDataValue()))
                sentPost = at
                sentFacing = targetFacing.get2DDataValue()
                sentAt = ticks
            }
        } else {
            sentPost = null
            sentFacing = -1
            saidOk = null
        }
        refusal?.let { if (ticks - it.at > REFUSAL_SHOWN) refusal = null }
        HomesteadBounds.tick(minecraft, level, player)
    }

    /** The survey of the spot in [verdict]'s answer, or none if it's of another spot. */
    fun verdictFor(post: BlockPos, facing: Direction): Verdict? =
        verdict?.takeIf { it.post == post && it.facing == facing.get2DDataValue() }

    fun onSync(payload: HomesteadSyncPayload) {
        homesteads = payload.entries
        HomesteadBounds.changed()
    }

    fun onVerdict(payload: ClaimVerdictPayload) {
        val minecraft = Minecraft.getInstance()
        val v = Verdict(payload.ok, payload.reason, payload.highlight, payload.post, payload.facing, ticks)
        if (payload.post == null) {
            refusal = v
            return
        }
        verdict = v
        // Said over the hotbar while the charter points at the spot; a soft chime when it turns out the land will do
        if (payload.post != target) return
        val text = payload.reason.copy().withStyle(if (payload.ok) ChatFormatting.GREEN else ChatFormatting.RED)
        minecraft.gui.hud.setOverlayMessage(text, false)
        if (payload.ok && saidOk != true) {
            minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f, 0.35f))
        }
        saidOk = payload.ok
    }

    fun onLedger(payload: LedgerPayload) {
        val minecraft = Minecraft.getInstance()
        val open = minecraft.gui.screen()
        if (open is HomesteadLedgerScreen && open.id == payload.id) {
            open.update(payload)
            return
        }
        if (open != null) return
        minecraft.gui.setScreen(HomesteadLedgerScreen(payload))
    }

    private fun reset() {
        homesteads = listOf()
        verdict = null
        refusal = null
        target = null
        sentPost = null
        sentFacing = -1
        saidOk = null
        HomesteadBounds.reset()
    }

    /** Ticks between asking about a new spot (the server answers one a player every six). */
    private const val ASK_EVERY = 7L
    /** Ticks before the same spot is asked about again, in case the land there has changed. */
    private const val ASK_AGAIN = 50L
    /** Ticks a refusal's places at fault stay picked out. */
    private const val REFUSAL_SHOWN = 200L
}
