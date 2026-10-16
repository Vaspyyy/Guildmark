package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.island.plan.IslandPlan
import io.github.vaspyyy.guildmark.island.plan.IslandSpec
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureStart
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/**
 * Islands out at sea: wild isles with their own beaches, cliffs, woods and birds, sea stacks, coves, atolls and reefs,
 * grown with newly explored ocean (see [IslandStructure]; the island itself is drawn by island/plan). Everything about
 * them lives in this package; the mod's main class only calls [register].
 */
object Islands {
    /** The island structure, and the tag that finds islands (data/guildmark/tags/worldgen/structure/island.json). */
    val KEY: ResourceKey<Structure> = ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "island"))
    val TAG: TagKey<Structure> = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "island"))

    fun register(modBus: IEventBus) {
        IslandRegistry.STRUCTURE_TYPES.register(modBus)
        IslandRegistry.PIECE_TYPES.register(modBus)
        IslandRegistry.BLOCKS.register(modBus)
        IslandRegistry.ITEMS.register(modBus)
        IslandRegistry.BLOCK_ENTITIES.register(modBus)
        IslandRegistry.ENTITIES.register(modBus)
        IslandRegistry.SOUNDS.register(modBus)
        modBus.addListener(EntityAttributeCreationEvent::class.java) { it.put(IslandRegistry.GULL.get(), Gull.createAttributes().build()) }
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) {
                event.accept(IslandRegistry.GULL_NEST_ITEM.get())
                event.accept(IslandRegistry.LIGHTHOUSE_LAMP_ITEM.get())
                event.accept(IslandRegistry.GULL_EGG.get())
                event.accept(IslandRegistry.SEARED_GULL_EGG.get())
            }
        }
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1").playToClient(IslandPayload.TYPE, IslandPayload.STREAM_CODEC, IslandPayload::handle)
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { IslandCommand.register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java) { event -> (event.entity as? ServerPlayer)?.let(IslandVisits::tick) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> IslandVisits.forget(event.entity) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> IslandVisits.clear() }
    }

    /** Whether islands grow in newly explored ocean (the islands setting). Islands already grown stay. */
    fun enabled(): Boolean = try {
        GuildmarkConfig.ISLANDS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** The island whose waters [pos] is in, if any. */
    fun at(level: ServerLevel, pos: BlockPos): IslandSpec? {
        val start = level.structureManager().getStructureWithPieceAt(pos, TAG)
        if (!start.isValid) return null
        return start.pieces.firstNotNullOfOrNull { (it as? IslandPiece)?.spec }
    }

    /**
     * The islands whose middles lie within [range] blocks of [pos], read from the chunks already loaded round it (none
     * is ever loaded for this). Every chunk an island reaches holds a reference to it, and every island reaches
     * across at least six chunks, so every other chunk is enough to look at; the island itself is kept with the chunk
     * at its middle, which has to be loaded too.
     */
    fun near(level: ServerLevel, pos: BlockPos, range: Int): List<IslandSpec> {
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(KEY) ?: return listOf()
        val source = level.chunkSource
        val homes = LongOpenHashSet()
        val cx = SectionPos.blockToSectionCoord(pos.x)
        val cz = SectionPos.blockToSectionCoord(pos.z)
        val reach = (range shr 4) + 2
        for (dx in -reach..reach step 2) for (dz in -reach..reach step 2) {
            val chunk = source.getChunkNow(cx + dx, cz + dz) ?: continue
            val refs = chunk.getReferencesForStructure(structure)
            if (!refs.isEmpty()) homes.addAll(refs)
        }
        if (homes.isEmpty()) return listOf()
        val out = ArrayList<IslandSpec>()
        val iterator = homes.iterator()
        while (iterator.hasNext()) {
            val home = iterator.nextLong()
            val chunk = source.getChunkNow(ChunkPos.getX(home), ChunkPos.getZ(home)) ?: continue
            val start = chunk.getStartForStructure(structure) ?: continue
            if (!start.isValid) continue
            val spec = start.pieces.firstNotNullOfOrNull { (it as? IslandPiece)?.spec } ?: continue
            val dx = (spec.cx - pos.x).toLong()
            val dz = (spec.cz - pos.z).toLong()
            if (dx * dx + dz * dz <= range.toLong() * range) out.add(spec)
        }
        return out
    }

    /**
     * Raises the island [spec] in the live world (for /guildmark island here), all of it at once, and makes it known
     * there as an island just as one grown with the world is. The chunks it reaches are loaded first.
     */
    fun raise(level: ServerLevel, spec: IslandSpec): Boolean {
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(KEY) ?: return false
        val piece = IslandPiece(spec)
        val box = piece.boundingBox
        val first = ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()))
        val last = ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()))
        val chunks = ChunkPos.rangeClosed(first, last).toList()
        for (c in chunks) level.getChunk(c.x, c.z)
        val canvas = WorldIslandCanvas(
            level, BoundingBox(box.minX(), level.minY, box.minZ(), box.maxX(), level.maxY, box.maxZ()), level.random, spec.sea, box.minY(),
        )
        IslandPlan.of(spec).draw(canvas)
        canvas.finish()
        val home = ChunkPos(SectionPos.blockToSectionCoord(spec.cx), SectionPos.blockToSectionCoord(spec.cz))
        val start = StructureStart(structure, home, 0, PiecesContainer(listOf(piece)))
        level.getChunk(home.x, home.z).setStartForStructure(structure, start)
        for (c in chunks) {
            val chunk = level.getChunk(c.x, c.z)
            chunk.addReferenceForStructure(structure, home.pack())
            chunk.markUnsaved()
        }
        return true
    }
}
