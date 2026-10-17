package io.github.vaspyyy.guildmark.townsfolk

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.plan.Buildings
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiRecord
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.AbstractBedBlock
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BrewingStandBlock
import net.minecraft.world.level.block.CartographyTableBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ComposterBlock
import net.minecraft.world.level.block.CraftingTableBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.GrindstoneBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.LoomBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.StonecutterBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.function.Supplier

/**
 * A bed for everyone. The city plan gave many of a capital's people a home with no bed in it (the market's
 * keepers, the watch at the gates, priests and farmers were housed wherever there was room), so each is made up a
 * real bed in their home, its colour the household's: out of sight (never with a player within [UNSEEN] blocks),
 * only where the home's land is loaded, and a few people a second, so a capital coming into view costs nothing
 * anyone would notice. [Bedding] says where in the home a bed can go.
 *
 * A bed that has since been broken, or that someone else has taken, is made up afresh; and each bed kept for one of
 * the townsfolk is held for them in the world's reckoning of homes, so villagers don't take it for their own (and
 * raise families in it). Someone with no room at all in their home is marked furnished with no bed.
 */
object Furnish {
    /** How near a player may come to a home before nothing more is made up in it. */
    const val UNSEEN = 24.0

    /** Ticks between steps of the round, and how many people each step looks in on (making up one bed at most). */
    private const val STEP = 10
    private const val PER_STEP = 3

    /** Ticks between looking for the capitals players are in, and how long one stays on the round after they've gone. */
    private const val SCAN = 100
    private const val KEEP = 1200

    /** Ticks between going round all of a capital's people. */
    private const val ROUND = 600

    /** What came of looking in on someone. */
    enum class Outcome {
        /** Nothing to do: dead, homeless, or already seen to. */
        NOTHING,
        /** Their bed is still there and still theirs. */
        KEPT,
        /** Their bed was broken or taken: one will be made up again. */
        LOST,
        MADE,
        /** No room for a bed anywhere in their home. */
        NO_ROOM,
        /** Not now: their home isn't loaded, someone's watching, or someone's standing where the bed would go. */
        WAITING,
    }

    private class Round(val capital: CapitalPiece) {
        var seen = 0L
        var started = Long.MIN_VALUE / 2
        val queue = ArrayDeque<Int>()
    }

    private val rounds = LinkedHashMap<BlockPos, Round>()
    private var scanned = Long.MIN_VALUE / 2
    private var turn = 0

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            rounds.clear()
            scanned = Long.MIN_VALUE / 2
        }
    }

    fun enabled(): Boolean = try {
        GuildmarkConfig.TOWNSFOLK_BEDS.get()
    } catch (e: IllegalStateException) {
        true
    }

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val time = level.gameTime
        if (time % STEP != 0L || !enabled() || level.players().isEmpty()) return
        if (time - scanned >= SCAN) {
            scanned = time
            scan(level, time)
        }
        if (rounds.isEmpty()) return
        turn = (turn + 1) % rounds.size
        step(level, rounds.values.elementAt(turn), time)
    }

    /** Which capitals players are on the ground of, from what's loaded. */
    private fun scan(level: ServerLevel, time: Long) {
        for (player in level.players()) {
            val pos = player.blockPosition()
            val capital = rounds.values.firstOrNull { it.capital.reaches(pos.x, pos.z) }?.capital ?: capitalAt(level, pos) ?: continue
            rounds.getOrPut(capital.center) { Round(capital) }.seen = time
        }
        rounds.values.removeIf { time - it.seen > KEEP }
    }

    /** A few of a capital's people looked in on, the round starting again every [ROUND] ticks. */
    private fun step(level: ServerLevel, round: Round, time: Long) {
        val places = CityPlaces.of(level, round.capital) ?: return
        val data = Residents.get(level)
        if (round.queue.isEmpty()) {
            if (time - round.started < ROUND) return
            round.started = time
            val people = data.of(round.capital.center)
            unshare(data, people)
            people.filter { it.alive }.sortedWith(compareBy({ it.role == TownRole.CHILD }, { it.id })).mapTo(round.queue) { it.id }
        }
        repeat(PER_STEP) {
            val r = data[round.queue.removeFirstOrNull() ?: return] ?: return@repeat
            val outcome = visit(level, places, r, quiet = true)
            if (outcome == Outcome.MADE || outcome == Outcome.NO_ROOM) return
        }
    }

    /**
     * Sees to [r]: checks the bed they have is still there and theirs, or makes one up in their home if they have
     * none yet. [quiet]: only where no player is near enough to see it happen.
     */
    fun visit(level: ServerLevel, places: CityPlaces, r: Resident, quiet: Boolean): Outcome {
        if (!r.alive) return Outcome.NOTHING
        val bed = r.bed
        if (bed != null) {
            if (theirs(level, bed)) return Outcome.KEPT
            lose(level, r)
            return Outcome.LOST
        }
        if (r.home < 0 || r.furnished) return Outcome.NOTHING
        val lodging = places.lodgings[r.home] ?: return Outcome.NOTHING
        if (!loaded(level, lodging) || quiet && watched(level, lodging)) return Outcome.WAITING
        return makeUp(level, places, lodging, r, quiet)
    }

    /** What making up a whole capital's beds came to (see [all]). */
    class Report {
        var made = 0
        var noRoom = 0
        var waiting = 0
        var lost = 0
        var kept = 0
        val beds = ArrayList<BlockPos>()
    }

    /**
     * Makes up every bed [capital]'s people are missing now, with players about or not (every other rule holds),
     * first checking the beds they have: those broken or taken are made up again.
     */
    fun all(level: ServerLevel, capital: CapitalPiece, places: CityPlaces): Report {
        val data = Residents.get(level)
        val people = data.of(capital.center)
        unshare(data, people)
        val report = Report()
        for (r in people.filter { it.alive }.sortedWith(compareBy({ it.role == TownRole.CHILD }, { it.id }))) {
            var outcome = visit(level, places, r, quiet = false)
            if (outcome == Outcome.LOST) {
                report.lost++
                outcome = visit(level, places, r, quiet = false)
            }
            when (outcome) {
                Outcome.KEPT -> report.kept++
                Outcome.MADE -> {
                    report.made++
                    r.bed?.let(report.beds::add)
                }
                Outcome.NO_ROOM -> report.noRoom++
                Outcome.WAITING -> report.waiting++
                else -> {}
            }
        }
        return report
    }

    /** Two of the living with the same bed: the first to have it keeps it, the other has one made up. */
    private fun unshare(data: Residents, people: List<Resident>) {
        val owners = HashSet<BlockPos>()
        for (r in people.filter { it.alive && it.bed != null }.sortedBy { it.id }) {
            if (owners.add(r.bed!!)) continue
            r.bed = null
            r.furnished = false
            data.changed()
        }
    }

    /** Whether the bed with its head at [bed] is still standing and nobody else's; true while its land isn't loaded. */
    private fun theirs(level: ServerLevel, bed: BlockPos): Boolean {
        if (!Places.loaded(level, bed, 16)) return true
        val state = level.getBlockState(bed)
        if (!state.`is`(BlockTags.BEDS) || state.getValue(AbstractBedBlock.PART) != BedPart.HEAD) return false
        return hold(level, bed)
    }

    /** [r]'s bed is gone, or someone else's now: they'll have one made up. */
    private fun lose(level: ServerLevel, r: Resident) {
        r.bed?.let { HeldBeds.get(level).remove(it) }
        r.bed = null
        r.furnished = false
        Residents.get(level).changed()
    }

    /**
     * Holds the bed with its head at [bed] for the townsfolk: takes it in the world's reckoning of homes, so no
     * villager claims it. False if a villager already has it. Its land, and round it, must be loaded.
     */
    fun hold(level: ServerLevel, bed: BlockPos): Boolean {
        val record = home(level, bed) ?: return true
        val held = HeldBeds.get(level)
        if (record.freeTickets > 0) {
            level.poiManager.take({ it.`is`(PoiTypes.HOME) }, { _, pos -> pos == bed }, bed, 1)
            held.add(bed)
            return true
        }
        return held.has(bed)
    }

    /**
     * Lets go of a bed held with [hold] (someone moved out, or it's to be a villager's), so villagers may take it.
     * For whoever looks after the townsfolk's homes; nothing here calls it.
     */
    fun release(level: ServerLevel, bed: BlockPos) {
        val held = HeldBeds.get(level)
        if (!held.has(bed)) return
        held.remove(bed)
        if (Places.loaded(level, bed, 16) && home(level, bed)?.isOccupied == true) level.poiManager.release(bed)
    }

    /** The bed at [bed] as the world's reckoning of homes has it, if it has it. */
    private fun home(level: ServerLevel, bed: BlockPos): PoiRecord? =
        level.poiManager.getInChunk({ it.`is`(PoiTypes.HOME) }, ChunkPos.containing(bed), PoiManager.Occupancy.ANY)
            .filter { it.pos == bed }.findFirst().orElse(null)

    /** Makes up a bed for [r] in [lodging], if there's room; in sight of players ([quiet] false) it's seen to be done. */
    private fun makeUp(level: ServerLevel, places: CityPlaces, lodging: CityPlaces.Lodging, r: Resident, quiet: Boolean): Outcome {
        val data = Residents.get(level)
        val seed = places.capital.seed * 31 + lodging.id
        val spot = Bedding.choose(home(lodging, seed), WorldLook(level))
        if (spot == null) {
            r.furnished = true
            data.changed()
            Guildmark.LOGGER.debug("No room for a bed for {} (#{}) in {} of the capital at {}", r.name, r.id, lodging, r.capital)
            return Outcome.NO_ROOM
        }
        val head = BlockPos(spot.x, spot.y, spot.z)
        val foot = BlockPos(spot.footX, spot.y, spot.footZ)
        // Not on top of anyone
        if (level.getEntitiesOfClass(LivingEntity::class.java, AABB(head).minmax(AABB(foot))).isNotEmpty()) return Outcome.WAITING
        val toward = Direction.Plane.HORIZONTAL.first { it.stepX == spot.dx && it.stepZ == spot.dz }
        val state = Blocks.BED.pick(colour(level, lodging, seed)).defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, toward)
        level.setBlock(foot, state.setValue(AbstractBedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL)
        level.setBlock(head, state.setValue(AbstractBedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL)
        r.bed = head
        r.furnished = true
        data.changed()
        if (Places.loaded(level, head, 16)) hold(level, head)
        if (!quiet) {
            // Shaken out and laid down: a puff of down and the soft sound of the blankets
            val mid = AABB(head).minmax(AABB(foot)).center
            level.sendParticles(ParticleTypes.POOF, mid.x, head.y + 0.6, mid.z, 8, 0.5, 0.15, 0.5, 0.01)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mid.x, head.y + 0.8, mid.z, 5, 0.5, 0.2, 0.5, 0.0)
            level.playSound(null, head, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0f, 0.9f)
        }
        return Outcome.MADE
    }

    /** The household's colour: that of a bed the plan put in the home, if it's still there, else the home's own. */
    private fun colour(level: ServerLevel, lodging: CityPlaces.Lodging, seed: Long): DyeColor {
        for (b in lodging.beds) (level.getBlockState(b).block as? BedBlock)?.let { return it.color }
        val dyes = Buildings.BED_COLORS
        return CityStyle.dye(dyes[Math.floorMod(seed, dyes.size.toLong()).toInt()])
    }

    /** [lodging] as [Bedding] sees it. */
    fun home(lodging: CityPlaces.Lodging, seed: Long): Bedding.Home {
        val used = ArrayList<IntArray>()
        for (s in lodging.stations) used.add(intArrayOf(s.pos.x, s.pos.y, s.pos.z))
        for (s in lodging.stores) used.add(intArrayOf(s.x, s.y, s.z))
        for (s in lodging.seats) used.add(intArrayOf(s.pos.x, s.pos.y, s.pos.z, s.facing.stepX, s.facing.stepZ))
        for (t in lodging.tables) used.add(intArrayOf(t.x, t.y, t.z))
        val b = lodging.box
        return Bedding.Home(
            b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), lodging.y, lodging.floors,
            lodging.doors.map { Bedding.Door(it.pos.x, it.pos.y, it.pos.z, it.inward.stepX, it.inward.stepZ) },
            lodging.ladders.map { Bedding.Ladder(it.x, it.z, it.bottom, it.top, it.facing.stepX, it.facing.stepZ) },
            used, seed,
        )
    }

    /** Whether all of [lodging]'s land, and a little round it, is loaded. */
    fun loaded(level: ServerLevel, lodging: CityPlaces.Lodging): Boolean {
        val b = lodging.box
        return Places.loaded(level, b.center, maxOf(b.xSpan, b.zSpan) / 2 + 2)
    }

    /** Whether a player is within [UNSEEN] blocks of [lodging]. */
    private fun watched(level: ServerLevel, lodging: CityPlaces.Lodging): Boolean {
        val b = lodging.box
        return level.players().any { p ->
            val dx = maxOf(b.minX() - p.x, 0.0, p.x - b.maxX() - 1)
            val dy = maxOf(b.minY() - p.y, 0.0, p.y - b.maxY() - 1)
            val dz = maxOf(b.minZ() - p.z, 0.0, p.z - b.maxZ() - 1)
            dx * dx + dy * dy + dz * dz < UNSEEN * UNSEEN
        }
    }

    /**
     * The capital whose ground takes in [pos], found only from chunks already loaded (Capitals.covering may load
     * the chunk its start is kept in); null if that isn't loaded.
     */
    fun capitalAt(level: ServerLevel, pos: BlockPos): CapitalPiece? {
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Capitals.KEY) ?: return null
        val chunk = level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) ?: return null
        for (key in chunk.getReferencesForStructure(structure)) {
            val home = ChunkPos.unpack(key)
            val start = level.chunkSource.getChunkNow(home.x, home.z)?.getStartForStructure(structure) ?: continue
            val capital = start.takeIf { it.isValid }?.pieces?.firstOrNull() as? CapitalPiece ?: continue
            if (capital.layout == null || capital.proxy || !capital.reaches(pos.x, pos.z)) continue
            return capital.inWorld(level)
        }
        return null
    }

    /** The world's blocks as [Bedding] sees them; a block whose chunk isn't loaded is never looked at, but taken as in the way. */
    private class WorldLook(private val level: ServerLevel) : Bedding.Look {
        private val cursor = BlockPos.MutableBlockPos()

        override fun at(x: Int, y: Int, z: Int): Int {
            if (!level.hasChunk(x shr 4, z shr 4)) return 0
            cursor.set(x, y, z)
            if (level.isOutsideBuildHeight(cursor)) return if (y > level.minY) Bedding.EMPTY or Bedding.OPEN or Bedding.CLEAR else 0
            val state = level.getBlockState(cursor)
            var bits = 0
            if (state.isAir) bits = bits or Bedding.EMPTY
            val wet = !state.fluidState.isEmpty
            val shape = state.getCollisionShape(level, cursor)
            if (!wet) {
                if (shape.isEmpty) bits = bits or Bedding.OPEN or Bedding.CLEAR
                else if (shape.max(Direction.Axis.Y) <= 0.2) bits = bits or Bedding.OPEN
            }
            if (!state.isAir && (!wet || state.hasProperty(BlockStateProperties.WATERLOGGED)) && !shape.isEmpty && shape.max(Direction.Axis.Y) >= 0.8) {
                bits = bits or Bedding.GROUND
            }
            if (!wet && state.isCollisionShapeFullBlock(level, cursor) && state.isFaceSturdy(level, cursor, Direction.UP)) bits = bits or Bedding.FULL
            when {
                state.`is`(BlockTags.BEDS) -> bits = bits or Bedding.BED
                state.block is DoorBlock -> bits = bits or Bedding.DOOR
                state.`is`(BlockTags.CLIMBABLE) -> bits = bits or Bedding.LADDER
            }
            used(state)?.let { bits = bits or Bedding.USED or it }
            return bits
        }

        /** For something people use, the front to keep clear ([Bedding.front], 0 for none); null for anything else. */
        private fun used(state: BlockState): Int? {
            val block = state.block
            return when {
                block is StairBlock -> if (state.getValue(StairBlock.HALF) == Half.BOTTOM) front(state.getValue(StairBlock.FACING).opposite) else null
                block is ChestBlock || block is AbstractFurnaceBlock || block is LecternBlock || block is LoomBlock ||
                    block is StonecutterBlock || block is GrindstoneBlock ->
                    if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) front(state.getValue(BlockStateProperties.HORIZONTAL_FACING)) else 0
                block is BarrelBlock -> state.getValue(BarrelBlock.FACING).takeIf { it.axis.isHorizontal }?.let(::front) ?: 0
                block is CraftingTableBlock || block is CartographyTableBlock || block is AnvilBlock || block is AbstractCauldronBlock ||
                    block is BrewingStandBlock || block is ComposterBlock || state.`is`(Blocks.BOOKSHELF) || state.`is`(Blocks.HAY_BLOCK) -> 0
                else -> null
            }
        }

        private fun front(d: Direction): Int = Bedding.front(d.stepX, d.stepZ)
    }
}

/** The beds [Furnish] holds for the townsfolk (see [Furnish.hold]), kept with the overworld. */
class HeldBeds(private val beds: MutableSet<Long> = HashSet()) : SavedData() {
    fun has(pos: BlockPos): Boolean = pos.asLong() in beds

    fun add(pos: BlockPos) {
        if (beds.add(pos.asLong())) setDirty()
    }

    fun remove(pos: BlockPos) {
        if (beds.remove(pos.asLong())) setDirty()
    }

    companion object {
        private val CODEC: Codec<HeldBeds> = RecordCodecBuilder.create { i ->
            i.group(Codec.LONG.listOf().optionalFieldOf("beds", listOf()).forGetter { it.beds.toList() })
                .apply(i) { beds -> HeldBeds(beds.toHashSet()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "held_beds"), Supplier { HeldBeds() }, CODEC)

        fun get(level: ServerLevel): HeldBeds = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
