package io.github.vaspyyy.guildmark.client.streets

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.works.WorksMesh
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTableBlock
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTableBlockEntity
import io.github.vaspyyy.guildmark.townsfolk.streets.StreetsRegistry
import io.github.vaspyyy.guildmark.townsfolk.streets.ToLetBlock
import io.github.vaspyyy.guildmark.townsfolk.streets.ToLetBlockEntity
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
import kotlin.math.sign

/**
 * The streets on a client (M6 Part D, townsfolk/streets): the alms table's pot, stew and bowls drawn
 * ([AlmsTableRenderer], from Part F's client/works/WorksMesh.alms), its soup's level eased as it changes, its pot and
 * bowls seen set down, the keeper's ladling and the gifts timed as they're seen, and its simmer heard over the pot
 * while there's soup in it (`streets.pot_loop`); and the "To let" boards swinging on their posts ([ToLetRenderer]),
 * creaking now and then as they swing (`streets.to_let`). The dressing and the awnings' flaps are festival ornaments
 * (client/festival's renderer draws them), and the slate is drawn with its market display
 * (client/economy/WaresRenderer, [StreetSlate]).
 */
object StreetsClient {
    /** Has it all registered, from the client's setup. */
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(StreetsRegistry.ALMS_TABLE_ENTITY.get(), ::AlmsTableRenderer)
            event.registerBlockEntityRenderer(StreetsRegistry.TO_LET_ENTITY.get(), ::ToLetRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(AlmsTableRenderer.LAYER, WorksMesh::alms)
            event.registerLayerDefinition(ToLetRenderer.LAYER, WorksMesh::toLet)
        }
        AlmsTableBlockEntity.clientTick = ::tick
        ToLetBlockEntity.clientTick = ::creak
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

    // ---- The "To let" boards ----

    /** The board's hooks creaking as it swings (its sound comes with living-world's audio: silent till then). */
    private val TO_LET_ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "streets.to_let")
    private val TO_LET: SoundEvent = SoundEvent.createVariableRangeEvent(TO_LET_ID)

    /**
     * Each client tick of a "To let" board in the loaded world: how wet the wind is there, eased as the rain comes and
     * goes (it swings twice as far in rain), and its hooks' creak now and then as a swing turns back: the further it
     * swung out, the likelier and the louder (so more often and louder in rain), and never within [CREAK_GAP] of the last.
     */
    private fun creak(board: ToLetBlockEntity) {
        val level = board.level ?: return
        val now = level.gameTime
        val pos = board.blockPos
        val rain = if (level.isRainingAt(pos.above())) 1f else 0f
        board.wet = if (board.wet < 0f) rain else board.wet + (rain - board.wet) * WET_EASE
        val swing = ToLetRenderer.swing(level, pos, board.wet, 0f)
        val last = board.lastSwing
        board.lastSwing = swing
        if (last.isNaN()) return
        val way = sign(swing - last).toInt()
        val turned = way != 0 && board.lastWay != 0 && way != board.lastWay
        if (way != 0) board.lastWay = way
        if (!turned || now - board.creakAt < CREAK_GAP) return
        val far = abs(last)
        if (level.random.nextFloat() >= ((far - CREAK_FROM) / CREAK_SPAN).coerceIn(0f, 1f)) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player.distanceToSqr(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5) > CREAK_HEAR * CREAK_HEAR) return
        if (mc.soundManager.getSoundEvent(TO_LET_ID) == null) return
        board.creakAt = now
        // From the board, hung out toward the street
        val out = board.blockState.getValue(ToLetBlock.FACING)
        val x = pos.x + 0.5 + out.stepX * BOARD_OUT
        val z = pos.z + 0.5 + out.stepZ * BOARD_OUT
        level.playLocalSound(x, pos.y + BOARD_Y, z, TO_LET, SoundSource.BLOCKS, (0.2f + 0.05f * far).coerceAtMost(0.9f), 0.9f + level.random.nextFloat() * 0.2f, false)
    }

    /**
     * The board's creak: the least ticks between two, the degrees out a swing must turn back from to creak at all and
     * how many more till it always does (a dry wind swings it 7.5 at most, rain twice that), and how near it's heard.
     */
    private const val CREAK_GAP = 30L
    private const val CREAK_FROM = 3f
    private const val CREAK_SPAN = 9f
    private const val CREAK_HEAR = 12.0

    /** How fast the board's wetness follows the rain each tick, and where it hangs (blocks up, and out toward the street). */
    private const val WET_EASE = 0.02f
    private const val BOARD_Y = 1.0
    private const val BOARD_OUT = 0.21
}
