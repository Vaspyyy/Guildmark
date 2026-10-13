package io.github.vaspyyy.guildmark.region

import io.github.vaspyyy.guildmark.GuildmarkConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID

/**
 * The named regions of the overworld: what kind of land lies where, and which region each player is in.
 * Once a second each player's spot is looked up (the caves below a land are regions of their own: the
 * lush and dripstone caves, the deep dark, the crystal deeps); a new region counts once it's been seen
 * twice running, so walking along a border doesn't flicker. Then the player is told, for the title card,
 * the music and the air, and the region is noted as found, for the map.
 */
object Regions {
    /** Ticks between looks at where each player is. */
    private const val CHECK = 20
    /** Blocks around a player whose cells' regions their map is sent. */
    private const val NEAR = 640
    /** How far under the ground (blocks) the caves count as a region apart. */
    private const val UNDERGROUND = 8
    private const val MAX_FOUND = 4096
    private const val MAX_CELLS = 1024
    /** Cells sent to one player per look, and cells put in hand for them, at most. */
    private const val SEND_BUDGET = 3
    private const val ASK_BUDGET = 2

    private class Tracker {
        var current: RegionInfo? = null
        /** A region seen once: it's entered if it's seen again next time. */
        var candidate = 0L
        /** Cells whose regions this player's client has, and cells it wants, the nearest first. */
        val sent = HashSet<Long>()
        val wanted = LinkedHashSet<Long>()
        /** Whether the cells they were near on earlier visits are in [wanted] yet. */
        var recalled = false
    }

    private val trackers = HashMap<UUID, Tracker>()

    fun register(modBus: IEventBus) {
        RegionData.ATTACHMENTS.register(modBus)
        RegionData.SOUNDS.register(modBus)
        RegionData.PARTICLES.register(modBus)
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToClient(RegionCellPayload.TYPE, RegionCellPayload.STREAM_CODEC, RegionCellPayload::handle)
                .playToClient(RegionEnteredPayload.TYPE, RegionEnteredPayload.STREAM_CODEC, RegionEnteredPayload::handle)
        }
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java) { event -> (event.entity as? ServerPlayer)?.let(::tick) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> trackers.remove(event.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            trackers.clear()
            RegionMap.clear()
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { event -> RegionCommand.register(event.dispatcher) }
        // Each wild land's own Hollows in its monster spawns
        RegionFoes.register()
    }

    fun enabled(): Boolean = !GuildmarkConfig.SPEC.isLoaded || GuildmarkConfig.REGIONS.get()

    /** The region [player] was last found in: null outside the overworld, before the first look, or with regions off. */
    fun current(player: ServerPlayer): RegionInfo? = trackers[player.uuid]?.current

    /**
     * The kind of land at [pos]: the biome's kind, or the crystal deeps where they run. Null for seams
     * (rivers, beaches) and outside the overworld. Cheap enough to ask on every spawn check.
     */
    fun kindAt(level: ServerLevel, pos: BlockPos): RegionKind? {
        if (level.dimension() != Level.OVERWORLD) return null
        val biome = level.getBiome(pos)
        if (!biome.`is`(Biomes.DEEP_DARK) && RegionCells.of(level.seed).crystalAt(pos.x, pos.y, pos.z)) return RegionKind.CRYSTAL
        return RegionKind.ofBiome(biome)
    }

    /**
     * The region at [pos]: the caves' own region if it's well under the ground in one, else the land's.
     * Null while the land there is still being worked out (see RegionMap).
     */
    fun regionAt(level: ServerLevel, pos: BlockPos): RegionInfo? {
        if (level.dimension() != Level.OVERWORLD) return null
        val map = RegionMap.of(level)
        if (pos.y < level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x, pos.z) - UNDERGROUND) {
            val cells = RegionCells.of(level.seed)
            val cell = cells.cellAt(pos.x, pos.z)
            val biome = level.getBiome(pos)
            if (biome.`is`(Biomes.DEEP_DARK)) return map.underground(cell, RegionMap.DEEP_SLOT)
            if (cells.crystalAt(pos.x, pos.y, pos.z)) return map.underground(cell, RegionMap.CRYSTAL_SLOT)
            if (RegionKind.ofBiome(biome) == RegionKind.CAVERN) return map.underground(cell, RegionMap.CAVE_SLOT + flavour(biome))
        }
        return map.region(pos.x, pos.z)
    }

    /** Which sort of caves: lush, dripstone, sulfur or any other. */
    private fun flavour(biome: Holder<Biome>): Int = when {
        biome.`is`(Biomes.LUSH_CAVES) -> 0
        biome.`is`(Biomes.DRIPSTONE_CAVES) -> 1
        biome.`is`(Biomes.SULFUR_CAVES) -> 2
        else -> 3
    }

    private fun tick(player: ServerPlayer) {
        if (player.tickCount % CHECK != 7) return
        val level = player.level()
        val tracker = trackers.getOrPut(player.uuid) { Tracker() }
        if (!enabled() || level.dimension() != Level.OVERWORLD) {
            if (tracker.current != null) {
                tracker.current = null
                tracker.candidate = 0L
                PacketDistributor.sendToPlayer(player, RegionEnteredPayload.NONE)
            }
            return
        }
        if (player.tickCount % (CHECK * 5) == 7 || tracker.wanted.isEmpty()) gatherCells(level, player, tracker)
        sendCells(level, player, tracker)

        val here = regionAt(level, player.blockPosition()) ?: return
        val current = tracker.current
        if (current != null && here.id == current.id) {
            tracker.candidate = 0L
            return
        }
        // A new region counts once it's been seen twice running (straight away on arriving in the world)
        if (current != null && here.id != tracker.candidate) {
            tracker.candidate = here.id
            return
        }
        tracker.current = here
        tracker.candidate = 0L
        val found = player.getData(RegionData.FOUND)
        val first = here.id !in found
        if (first) player.setData(RegionData.FOUND, (found + here.id).takeLast(MAX_FOUND))
        PacketDistributor.sendToPlayer(player, RegionEnteredPayload(here.id, here.kind.id, here.name, first))
    }

    /** The cells within [NEAR] of the player: wanted by their client, and remembered for their next visit. */
    private fun gatherCells(level: ServerLevel, player: ServerPlayer, tracker: Tracker) {
        val cells = RegionCells.of(level.seed)
        val around = LinkedHashSet<Long>()
        around.add(cells.cellAt(player.blockX, player.blockZ))
        val step = NEAR / 2
        for (dx in -NEAR..NEAR step step) for (dz in -NEAR..NEAR step step) around.add(cells.cellAt(player.blockX + dx, player.blockZ + dz))
        tracker.wanted.addAll(around)
        val known = player.getData(RegionData.CELLS)
        if (!tracker.recalled) {
            tracker.recalled = true
            tracker.wanted.addAll(known.asReversed())
        }
        val fresh = around.filter { it !in known }
        if (fresh.isNotEmpty()) player.setData(RegionData.CELLS, (known + fresh).takeLast(MAX_CELLS))
    }

    /**
     * Send the player's client the cells it wants that are worked out, a few at a time, and put a few more
     * in hand: a long list (all the cells of an old map) is worked through a little each second, so it never
     * holds up the cell the player stands in.
     */
    private fun sendCells(level: ServerLevel, player: ServerPlayer, tracker: Tracker) {
        val map = RegionMap.of(level)
        var sends = SEND_BUDGET
        var asks = ASK_BUDGET
        val iterator = tracker.wanted.iterator()
        while (iterator.hasNext() && sends > 0) {
            val cell = iterator.next()
            if (cell in tracker.sent) {
                iterator.remove()
                continue
            }
            val plan = map.ready(cell)
            if (plan == null) {
                if (asks-- > 0) map.plan(cell)
                continue
            }
            PacketDistributor.sendToPlayer(player, RegionCellPayload.of(plan))
            tracker.sent.add(cell)
            iterator.remove()
            sends--
        }
    }
}
