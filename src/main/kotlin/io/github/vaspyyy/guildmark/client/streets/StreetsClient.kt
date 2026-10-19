package io.github.vaspyyy.guildmark.client.streets

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.works.WorksMesh
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTableBlock
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTableBlockEntity
import io.github.vaspyyy.guildmark.townsfolk.streets.StreetsRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import kotlin.math.abs

/**
 * The streets on a client (M6 Part D, townsfolk/streets): the alms table's pot, stew and bowls drawn
 * ([AlmsTableRenderer], from Part F's client/works/WorksMesh.alms), its soup's level eased as it changes, its pot and
 * bowls seen set down, the keeper's ladling and the gifts timed as they're seen, and its simmer heard over the pot
 * while there's soup in it (`streets.pot_loop`). The dressing and the awnings' flaps are festival ornaments
 * (client/festival's renderer draws them), and the slate is drawn with its market display
 * (client/economy/WaresRenderer, [StreetSlate]).
 */
object StreetsClient {
    /** Has it all registered, from the client's setup. */
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(StreetsRegistry.ALMS_TABLE_ENTITY.get(), ::AlmsTableRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(AlmsTableRenderer.LAYER, WorksMesh::alms)
        }
        AlmsTableBlockEntity.clientTick = ::tick
    }

    // ---- The alms table ----

    private val POT_LOOP: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "streets.pot_loop"))

    /** Each client tick of an alms table in the loaded world. */
    private fun tick(table: AlmsTableBlockEntity) {
        val level = table.level ?: return
        val now = level.gameTime
        // The soup's level as it's seen, easing to what's in the pot
        val soup = table.soup.toFloat()
        table.shown = if (table.shown < 0f) soup else if (abs(soup - table.shown) < 0.02f) soup else table.shown + (soup - table.shown) * EASE
        // A bowl set out is seen set down, and the pot (not those there as the table comes into view)
        if (table.bowls != table.bowlsSeen) {
            if (table.bowlsSeen >= 0 && table.bowls > table.bowlsSeen) table.bowlAt = now
            table.bowlsSeen = table.bowls
        }
        val pot = if (table.pot) 1 else 0
        if (pot != table.potSeen) {
            if (table.potSeen == 0 && pot == 1) table.potAt = now
            table.potSeen = pot
        }
        // A ladling or a gift seen starting: timed from now, with the keeper's move (one under way as the table comes
        // into view goes on from when it started; the stack's then taken as it stands)
        if (table.ladled != table.ladleSeen) {
            val first = table.ladleSeen == Long.MIN_VALUE
            table.ladleAt = if (!first) now else if (table.ladled > 0L) table.ladled else Long.MIN_VALUE / 2
            table.bowlsAtLadle = if (first) Int.MAX_VALUE else table.bowls
            table.ladleSeen = table.ladled
        }
        if (table.given != table.giftSeen) {
            val first = table.giftSeen == Long.MIN_VALUE
            table.giftAt = if (!first) now else if (table.given > 0L) table.given else Long.MIN_VALUE / 2
            table.giftSeen = table.given
        }
        // The simmer, while there's soup in it and someone near enough to hear it
        val playing = table.loop as? PotLoop
        if (playing != null && !playing.isStopped) return
        table.loop = null
        if (!table.pot || table.soup <= 0 || now < table.loopRetry) return
        val player = Minecraft.getInstance().player ?: return
        val pos = table.blockPos
        if (player.distanceToSqr(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5) > HEAR * HEAR) return
        val fresh = PotLoop(table)
        if (Minecraft.getInstance().soundManager.play(fresh) == SoundEngine.PlayResult.NOT_STARTED) table.loopRetry = now + RETRY else table.loop = fresh
    }

    /** The pot's simmer, over the pot: louder the fuller it is, fading out as it's emptied, taken away, or left behind. */
    private class PotLoop(private val table: AlmsTableBlockEntity) :
        AbstractTickableSoundInstance(POT_LOOP, SoundSource.BLOCKS, SoundInstance.createUnseededRandom()) {
        init {
            looping = true
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0.01f
            val at = AlmsTableBlock.pot(table.blockState.getValue(AlmsTableBlock.FACING), table.blockPos)
            x = at.x
            y = at.y
            z = at.z
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val player = Minecraft.getInstance().player
            val gone = table.isRemoved || player == null || player.distanceToSqr(x, y, z) > (HEAR + 8) * (HEAR + 8)
            val want = if (gone || !table.pot || table.soup <= 0) 0f else 0.3f + 0.4f * (table.soup.toFloat() / table.cap.coerceAtLeast(1))
            volume += (want - volume) * 0.08f
            if (want == 0f && volume < 0.01f) stop()
        }
    }

    /** How fast the soup's level eases each tick, how near the simmer's started (its sounds.json carries 12), and the wait before trying again. */
    private const val EASE = 0.12f
    private const val HEAR = 14.0
    private const val RETRY = 100L
}
