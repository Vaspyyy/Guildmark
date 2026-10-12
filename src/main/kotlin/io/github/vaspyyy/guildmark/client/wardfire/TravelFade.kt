package io.github.vaspyyy.guildmark.client.wardfire

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.network.WardfireTravelPayload
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.neoforged.neoforge.client.gui.GuiLayer
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/**
 * Travelling between Wardfires: the world fades to black, the request goes to the server once it's
 * dark, and the view comes back when the player stands at the far fire with the land around it loaded
 * (or straight away, if the server said no).
 */
object TravelFade : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "travel_fade")

    private const val OUT_TICKS = 10
    private const val IN_TICKS = 18
    /** Ticks to wait in the dark for the server and the far land before giving up and lifting the fade. */
    private const val MAX_DARK = 120
    /** Ticks the land at the far fire must have been loaded before the fade lifts. */
    private const val SETTLE = 6

    private enum class Phase { NONE, OUT, DARK, IN }

    private var phase = Phase.NONE
    private var ticks = 0
    private var target: BlockPos? = null
    /** The server has answered: true once the player has been moved. */
    private var arrived = false
    private var settled = 0

    val busy: Boolean get() = phase != Phase.NONE

    /** Starts the journey to the Wardfire at [to]. */
    fun begin(to: BlockPos) {
        if (busy) return
        target = to
        phase = Phase.OUT
        ticks = 0
        arrived = false
        settled = 0
        Ui.play(ModSounds.WARDFIRE_TRAVEL.get(), 1f, 0.5f)
    }

    /** The server's answer to the request. */
    fun travelled(moved: Boolean) {
        if (phase != Phase.DARK && phase != Phase.OUT) return
        if (moved) arrived = true
        else lift()
    }

    private fun lift() {
        phase = Phase.IN
        ticks = 0
    }

    fun reset() {
        phase = Phase.NONE
        target = null
    }

    fun tick() {
        when (phase) {
            Phase.NONE -> return
            Phase.OUT -> if (++ticks >= OUT_TICKS) {
                target?.let { ClientPacketDistributor.sendToServer(WardfireTravelPayload(it)) }
                phase = Phase.DARK
                ticks = 0
            }
            Phase.DARK -> {
                ticks++
                if (arrived && landLoaded()) settled++
                if (settled >= SETTLE || ticks >= MAX_DARK) lift()
            }
            Phase.IN -> if (++ticks >= IN_TICKS) reset()
        }
    }

    /** The chunks around the player are in, so the fade won't lift onto empty sky. */
    private fun landLoaded(): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return true
        val level = minecraft.level ?: return true
        val cx = player.blockX shr 4
        val cz = player.blockZ shr 4
        for (dx in -1..1) for (dz in -1..1) if (!level.chunkSource.hasChunk(cx + dx, cz + dz)) return false
        return true
    }

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val a = delta.getGameTimeDeltaPartialTick(false)
        val dark = when (phase) {
            Phase.NONE -> return
            Phase.OUT -> Mth.clamp((ticks + a) / OUT_TICKS, 0f, 1f)
            Phase.DARK -> 1f
            Phase.IN -> 1f - Mth.clamp((ticks + a) / IN_TICKS, 0f, 1f)
        }
        // Eased, so the dark gathers and lifts softly
        val k = dark * dark * (3f - 2f * dark)
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ARGB.color((255 * k).toInt(), 6, 4, 3))
    }
}
