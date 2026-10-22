package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.festival.FestivalRestores
import io.github.vaspyyy.guildmark.tales.MemorialRules.Column
import io.github.vaspyyy.guildmark.tales.MemorialRules.Face
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.streets.Streets
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * How a memorial is set down (living-world/stories/design.md 6.4, 14.10): the record in `TaleData.memorials`, the safe-site search
 * ([MemorialRules] over [MemorialSites]), the block through `Streets.place` in the `TALES` band (so the streets' record knows what stood
 * there), and the waiting list for the ones that cannot be set yet. Decided on the server, once; no chunk is loaded for it.
 *
 * A record is in one of three states: `placed` (the block stands at `pos`); `pos` set and not `placed` (waiting: its chunk is not loaded, a
 * cairn waits for a player to be near, or `townTaleMemorials` is off); `pos` null and not `placed` (no room: the search found nothing fit,
 * and the Laying lays its posy on the nearest bench or step instead). The Laying's scene is built the moment the tale ends, before stage 8
 * runs, and wants to see the block already standing, so a memorial is set at once when its chunk is loaded ([Memorials.ask]); stage 8 and
 * the waiting list see to the rest.
 */
internal object MemorialPlacing {
    /** A cairn waits on the list until a player is within this many blocks (6.4: the chunk loaded and a player near). */
    const val NEAR_CAIRN = 64.0

    /** A cairn is set from the list only when no player is within this many blocks of it, nor within [Streets.SIGHT] with a clear view of it. */
    private const val CLOSE = 24.0

    /** The waiting list is looked at every [EVERY] ticks, [AT_A_TIME] records at a time, and one that could not be judged is looked at again after [AGAIN] ticks. */
    private const val EVERY = 40
    private const val PHASE = 17
    private const val AT_A_TIME = 2
    private const val AGAIN = 200L

    /** How far from a memorial its lantern and pot are looked for when it is taken away. */
    private const val REMOVE_REACH = 24

    /** Spreads the memorial's id into the search's seed. */
    private const val SALT = 2654435761L

    /** When a memorial on the list was last looked at, by id: memory, forgotten as the server stops. */
    private val retry = HashMap<Int, Long>()

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> retry.clear() }
    }

    /** How an attempt to set a memorial down ended. */
    enum class Result { PLACED, WAIT, NO_ROOM }

    /** An attempt's [result] and, when the search was made, what it [found]. */
    class Outcome(val result: Result, val found: MemorialRules.Found? = null)

    // ---- Settling a memorial just asked for ----

    /**
     * Gives [rec] (just recorded for [t]) the place it starts from and sets it down if it can be now, adding a `Why` for what became of it.
     * With `townTaleMemorials` off it stays recorded and waiting.
     */
    fun settle(c: Ctx, t: Tale, rec: TaleData.Memorial) {
        val sites = MemorialSites(c.level, c.here.capital, c.here.places)
        val cairn = rec.kind == MemorialKind.CAIRN.id
        // No place named: where the kind belongs (`tryPlace` goes on to the next best places if that has no room)
        if (rec.pos == null) rec.pos = sites.anchors(cairn).firstOrNull()
        c.data.changed()
        val label = MemorialWords.label(rec.names)
        if (rec.pos == null) {
            c.why(t, Memorials.WHY_NOROOM, label)
            return
        }
        if (!Tales.memorials()) {
            c.why(t, Memorials.WHY_OFF, label)
            return
        }
        when (tryPlace(c.level, rec, sites, sync = true).result) {
            Result.PLACED -> c.why(t, if (cairn) Memorials.WHY_CAIRN else Memorials.WHY_STONE, label)
            Result.NO_ROOM -> c.why(t, Memorials.WHY_NOROOM, label)
            Result.WAIT -> c.why(t, Memorials.WHY_WAIT, label)
        }
    }

    /** Stage 8: every memorial of [t] still waiting gets another try, with the capital's places in hand. */
    fun sweep(c: Ctx, t: Tale) {
        if (!Tales.memorials()) return
        val sites = MemorialSites(c.level, c.here.capital, c.here.places)
        for (rec in Memorials.of(c, t)) {
            if (rec.placed || rec.pos == null) continue
            val cairn = rec.kind == MemorialKind.CAIRN.id
            val label = MemorialWords.label(rec.names)
            when (tryPlace(c.level, rec, sites, sync = true).result) {
                Result.PLACED -> c.why(t, if (cairn) Memorials.WHY_CAIRN else Memorials.WHY_STONE, label)
                Result.NO_ROOM -> c.why(t, Memorials.WHY_NOROOM, label)
                Result.WAIT -> {}
            }
        }
    }

    // ---- Setting one down ----

    /**
     * Tries to set [rec] down: finds its spot by the rules, from where it wants to be, and builds the block. A memorial that was given no
     * place started from where its kind belongs (the first of [MemorialSites.anchors]) and, if that has no room, goes on to the next best
     * of them, now or on a later try alike ([MemorialRules.findFrom]: every one of them near, then every one far); one whose tale named
     * its place has only that place, as far as [MemorialRules.STONE_REACH] ([MemorialRules.CAIRN_REACH]). A [sync] attempt is made as a
     * tale ends (a scene is waiting to see the block): it needs only the chunk; one from the waiting list sets a cairn only with a player
     * within [NEAR_CAIRN] blocks and nobody close to it or looking. Ground not yet loaded is a wait, never a refusal.
     */
    fun tryPlace(level: ServerLevel, rec: TaleData.Memorial, sites: MemorialSites?, sync: Boolean): Outcome {
        if (rec.placed) return Outcome(Result.PLACED)
        val want = rec.pos ?: return Outcome(Result.NO_ROOM)
        val cairn = rec.kind == MemorialKind.CAIRN.id
        if (!level.isLoaded(want)) return Outcome(Result.WAIT)
        if (!sync && cairn && (level.getNearestPlayer(want.x + 0.5, want.y + 0.5, want.z + 0.5, NEAR_CAIRN, false) == null || !unseen(level, want))) return Outcome(Result.WAIT)
        val town = sites ?: sitesFor(level, rec, cairn) ?: return Outcome(Result.WAIT)
        val belongs = town.anchors(cairn)
        val wants = listOf(want) + if (belongs.firstOrNull() == want) belongs.drop(1) else listOf()
        val found = MemorialRules.findFrom(
            town, cairn, wants.map { MemorialRules.Anchor("place", it.x, it.y, it.z) }, others(level, rec), rec.id * SALT,
        ) { level.isLoaded(BlockPos(it.x, it.y, it.z)) }.found
        val spot = found.spot
        if (spot == null) {
            if (found.unloaded) return Outcome(Result.WAIT, found)
            Guildmark.LOGGER.info("No room for the {} for {} near {}: {}", rec.kind, rec.names, want, describe(found))
            rec.pos = null
            TaleData.get(level).changed()
            return Outcome(Result.NO_ROOM, found)
        }
        return Outcome(if (build(level, rec, spot)) Result.PLACED else Result.WAIT, found)
    }

    /** Builds [rec]'s block at [spot] and writes what it keeps. False if the cell was taken in the meantime (somebody stands in it). */
    private fun build(level: ServerLevel, rec: TaleData.Memorial, spot: MemorialRules.Spot): Boolean {
        val pos = BlockPos(spot.x, spot.y, spot.z)
        val cairn = rec.kind == MemorialKind.CAIRN.id
        val state = MemorialBlock.state(MemorialKind.of(rec.kind), direction(spot.face))
        val watched = Streets.seen(level, pos, Streets.SIGHT)
        if (!Streets.place(level, pos, state, Streets.column(rec.capital), Streets.TALES, rec.id.toLong(), solid = true)) return false
        (level.getBlockEntity(pos) as? MemorialBlockEntity)?.set(
            rec.names.map { MemorialName(it) }, rec.template, rec.lead, rec.day, rec.capital.takeIf { it != 0L }, rec.id,
        )
        rec.pos = pos
        rec.placed = true
        TaleData.get(level).changed()
        // A stone that comes where someone is looking is set down with a puff of dust and the sound of a stone laid
        if (watched) {
            level.sendParticles(ParticleTypes.POOF, pos.x + 0.5, pos.y + 0.3, pos.z + 0.5, if (cairn) 18 else 10, 0.5, 0.2, 0.5, 0.02)
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0f, 0.8f)
        }
        return true
    }

    /** The way a face of the rules is a direction of the world. */
    fun direction(face: Face): Direction = when (face) {
        Face.NORTH -> Direction.NORTH
        Face.EAST -> Direction.EAST
        Face.SOUTH -> Direction.SOUTH
        Face.WEST -> Direction.WEST
    }

    /**
     * The sites of [rec]'s capital when no tale is at hand to give them: only where its places are known. A cairn may go by its blocks
     * alone, with no plan (null: wait for the places).
     */
    fun sitesFor(level: ServerLevel, rec: TaleData.Memorial, cairn: Boolean): MemorialSites? {
        val wild = if (cairn) MemorialSites(level, null, null) else null
        if (rec.capital == 0L) return MemorialSites(level, null, null)
        if (!level.isLoaded(BlockPos.of(rec.capital))) return wild
        val piece = CaravanRoutes.capital(level, rec.capital, soon = true) ?: return wild
        val places = CityPlaces.of(level, piece) ?: return wild
        return MemorialSites(level, piece, places)
    }

    /** The other memorials standing in [level], as columns (the search keeps its distance from them). */
    fun others(level: ServerLevel, except: TaleData.Memorial?): List<Column> {
        val here = Memorials.dimension(level)
        return TaleData.get(level).memorials.filter { it !== except && it.placed && it.dimension == here }.mapNotNull { m -> m.pos?.let { Column(it.x, it.z) } }
    }

    /** A line of the search's counts, for the log and the command: "128 places tried: 70 on a street, 30 by a door, ...". */
    fun describe(found: MemorialRules.Found): String =
        "${found.tried} places tried" + if (found.refused.isEmpty()) "" else ": " + found.refused.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key.words}" }

    // ---- The waiting list ----

    /** A few memorials a time that are waiting, looked at every two seconds while `townTaleMemorials` is on. */
    private fun tick(server: MinecraftServer) {
        if (server.tickCount % EVERY != PHASE || !Tales.memorials()) return
        val level = server.overworld()
        val data = TaleData.get(level)
        if (data.memorials.none { !it.placed && it.pos != null }) return
        val here = Memorials.dimension(level)
        val now = level.gameTime
        var budget = AT_A_TIME
        for (rec in data.memorials.toList()) {
            if (budget == 0) break
            if (rec.placed || rec.pos == null || rec.dimension != here || (retry[rec.id] ?: 0L) > now) continue
            budget--
            val result = try {
                tryPlace(level, rec, null, sync = false).result
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("The memorial #{} could not be set down", rec.id, e)
                Result.WAIT
            }
            if (result == Result.WAIT) retry[rec.id] = now + AGAIN else retry.remove(rec.id)
        }
    }

    /** Whether no player is within [CLOSE] blocks of [pos], nor within [Streets.SIGHT] with a clear line to it (spectators do not count). */
    private fun unseen(level: ServerLevel, pos: BlockPos): Boolean {
        val spot = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0)
        for (player in level.players()) {
            if (player.isSpectator) continue
            val d = player.distanceToSqr(spot)
            if (d < CLOSE * CLOSE) return false
            if (d > Streets.SIGHT * Streets.SIGHT) continue
            val hit = level.clip(ClipContext(player.eyePosition, spot, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
            if (hit.type == HitResult.Type.MISS) return false
        }
        return true
    }

    // ---- Taking one away ----

    /**
     * Takes [rec] away: its block put back as it was (what the streets' record says stood there), the lantern and the pot of the Laying
     * taken down in the loaded chunks near it, and the record gone. What is not loaded is left as it is: with the record gone `Tales.wants`
     * says no, and the streets' record of those pieces is dropped when their chunk next loads.
     */
    fun remove(level: ServerLevel, rec: TaleData.Memorial) {
        val data = TaleData.get(level)
        val pos = rec.pos
        // The record first, so that the block's going does not look like a break
        data.memorials.remove(rec)
        if (pos != null && rec.dimension == Memorials.dimension(level)) {
            val site = Streets.site(Streets.column(rec.capital), Streets.TALES)
            for (p in FestivalRestores.recordsOf(level, site, pos, REMOVE_REACH)) {
                val record = FestivalRestores.recordAt(level, p) ?: continue
                if (record.run != rec.id.toLong()) continue
                val state = level.getBlockState(p)
                val ours = state.`is`(MemorialRegistry.MEMORIAL.get()) || state.`is`(Blocks.LANTERN) || state.block is FlowerPotBlock
                FestivalRestores.restore(level, p)
                // Only what the Laying and the town set: never a block put in their place since
                if (ours) level.setBlock(p, record.original, Block.UPDATE_ALL)
            }
        }
        data.changed()
        retry.remove(rec.id)
    }
}
