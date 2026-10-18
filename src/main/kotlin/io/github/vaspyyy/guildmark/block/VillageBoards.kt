package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.outlaw.Captives
import io.github.vaspyyy.guildmark.quest.Situations
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.MarketFetch
import io.github.vaspyyy.guildmark.advance.Advances
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.quest.SeaWork
import io.github.vaspyyy.guildmark.quest.BossHunts
import io.github.vaspyyy.guildmark.quest.Bounties
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModVillagers
import io.github.vaspyyy.guildmark.wardfire.WardfireSites
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.function.Predicate
import kotlin.math.abs

/** Boards as part of village life: daily rotation, villagers pinning notes, and a board for every village. */
object VillageBoards {
    private const val BOARD_TICK_INTERVAL = 100L
    private const val VILLAGER_RANGE = 16.0
    private const val PIN_REACH = 2.5
    private const val STARTER_NOTES = 3
    private const val BELL_SEARCH_RADIUS = 64
    private const val VILLAGE_BOARD_RADIUS = 48

    /** Index of the current in-game day; sleeping through the night advances it. */
    fun day(level: Level): Long = level.overworldClockTime / 24000L

    /** A capital's board, made with the land: a few notes so it isn't bare before anyone finds it. Once, ever. */
    private fun pinStarterNotes(level: Level, anchor: BlockPos, facing: Direction) {
        val cells = BoardPart.entries.shuffled().take(STARTER_NOTES)
        for (part in cells) {
            val cell = level.getBlockEntity(QuestBoardBlock.cellPos(anchor, facing, part)) as? QuestBoardBlockEntity
            cell?.updateNote(QuestGenerator.generateAnonymous(level.random, QuestGenerator.tierAt(anchor.x, anchor.z), surroundings(level, anchor)))
        }
    }

    /**
     * A board just put up. Where a village's board came down before, it takes back all that one knew (see
     * VillageRecords) and its villagers come by as on any day. Otherwise a few villagers about who haven't pinned their
     * note today pin it there at once, so the board isn't bare. It's the note they'd have pinned anyway; however many
     * boards go up, nobody pins twice in a day.
     */
    fun setUp(level: Level, anchor: BlockPos, facing: Direction) {
        val serverLevel = level as? ServerLevel ?: return
        val board = serverLevel.getBlockEntity(anchor) as? QuestBoardBlockEntity ?: return
        val bell = serverLevel.poiManager.findClosest({ it.`is`(PoiTypes.MEETING) }, anchor, VILLAGE_BOARD_RADIUS, PoiManager.Occupancy.ANY).orElse(null)
        val record = VillageRecords.get(serverLevel).take(anchor, bell)
        if (record != null) {
            board.restore(record)
            return
        }
        val today = day(serverLevel)
        val cells = BoardPart.entries.mapNotNull {
            serverLevel.getBlockEntity(QuestBoardBlock.cellPos(anchor, facing, it)) as? QuestBoardBlockEntity
        }
        val villagers = serverLevel.getEntitiesOfClass(Villager::class.java, AABB(anchor).inflate(VILLAGER_RANGE)) {
            it.isAlive && !it.isBaby && !it.isSleeping && it.getData(ModAttachments.LAST_PINNED_DAY) != today
        }
        for ((villager, cell) in villagers.shuffled().zip(cells.shuffled()).take(STARTER_NOTES)) {
            pinNote(serverLevel, villager, cell, facing, cells)
        }
    }

    /** The board (anchor cell) already serving where [pos] is: one in reach of it, or of its village's bell. */
    fun boardInReach(level: ServerLevel, pos: BlockPos): BlockPos? {
        val poi = level.poiManager
        val board = Predicate<Holder<PoiType>> { it.`is`(ModVillagers.GUILDMASTER_POI.key!!) }
        poi.findClosest(board, pos, VILLAGE_BOARD_RADIUS, PoiManager.Occupancy.ANY).orElse(null)?.let { return it }
        val bell = poi.findClosest({ it.`is`(PoiTypes.MEETING) }, pos, VILLAGE_BOARD_RADIUS, PoiManager.Occupancy.ANY).orElse(null) ?: return null
        return poi.findClosest(board, bell, VILLAGE_BOARD_RADIUS, PoiManager.Occupancy.ANY).orElse(null)
    }

    /** Runs on the anchor cell of every loaded board. */
    fun tickBoard(level: ServerLevel, anchor: BlockPos, state: BlockState) {
        if ((level.gameTime + anchor.asLong()) % BOARD_TICK_INTERVAL != 0L) return
        val facing = state.getValue(QuestBoardBlock.FACING)
        // A board that came with the land (a capital's) gets its first notes once it's loaded
        if ((level.getBlockEntity(anchor) as? QuestBoardBlockEntity)?.takeStarterNotes() == true) pinStarterNotes(level, anchor, facing)
        val cells = BoardPart.entries.mapNotNull {
            level.getBlockEntity(QuestBoardBlock.cellPos(anchor, facing, it)) as? QuestBoardBlockEntity
        }

        // Unclaimed notes from another day come down (also covers /time set going backwards)
        val today = day(level)
        for (cell in cells) {
            val note = cell.note ?: continue
            // A wanted note comes down too once its captain has fallen, and a plea once its captive's free
            if (cell.postedDay != today || Bounties.stale(level, note) || Captives.stale(level, note)) cell.updateNote(null)
        }

        if (!level.isBrightOutside) return
        val empty = cells.filter { it.note == null }
        if (empty.isEmpty()) return

        val villagers = level.getEntitiesOfClass(Villager::class.java, AABB(anchor).inflate(VILLAGER_RANGE)) {
            it.isAlive && !it.isBaby && !it.isSleeping && it.getData(ModAttachments.LAST_PINNED_DAY) != today
        }
        if (villagers.isEmpty()) return

        val front = anchor.relative(facing)
        val atBoard = villagers.firstOrNull { villager ->
            cells.any { villager.position().closerThan(Vec3.atCenterOf(it.blockPos.relative(facing)), PIN_REACH) }
        }
        if (atBoard != null) {
            pinNote(level, atBoard, empty.random(), facing, cells)
        } else if (level.random.nextInt(4) == 0) {
            // Nudge someone over; their own routine may win, which is fine
            villagers.random().brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(front, 0.5f, 1))
        }
    }

    private fun pinNote(level: ServerLevel, villager: Villager, cell: QuestBoardBlockEntity, facing: Direction, cells: List<QuestBoardBlockEntity>) {
        val profession = villager.villagerData.profession().unwrapKey().orElse(null)?.identifier()
        val tier = QuestGenerator.tierAt(cell.blockPos.x, cell.blockPos.z)
        val poster = posterName(villager, profession)
        val anchor = QuestBoardBlock.anchorPos(cell.blockPos, cell.blockState)
        cell.updateNote(plea(level, anchor, cells, poster, tier)
            ?: Situations.note(level, villager, anchor, poster, tier)
            ?: QuestGenerator.generate(level.random, profession, poster, tier, surroundings(level, anchor)))
        villager.setData(ModAttachments.LAST_PINNED_DAY, day(level))
        villager.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(cell.blockPos))
        val spot = Vec3.atCenterOf(cell.blockPos.relative(facing))
        level.playSound(null, cell.blockPos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.NEUTRAL, 1.0f, 1.0f)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, spot.x, spot.y, spot.z, 4, 0.3, 0.3, 0.3, 0.0)
    }

    /** A plea for someone of this village an outlaw band holds, while no note on the board asks it already. */
    private fun plea(level: ServerLevel, anchor: BlockPos, cells: List<QuestBoardBlockEntity>, poster: String, tier: Int): QuestNote? {
        val pinned = cells.mapNotNull { it.note }
        val held = Captives.held(level, anchor).firstOrNull { held -> pinned.none { it.type == QuestType.RESCUE && it.subject == held.name } } ?: return null
        val note = QuestGenerator.generateWhere(level.random, tier, surroundings(level, anchor), poster) { it.type == QuestType.RESCUE } ?: return null
        return Captives.dress(note, held)
    }

    /** The board (anchor cell) of the village whose bell is nearest [near], within 48 blocks. */
    fun villageBoard(level: ServerLevel, near: BlockPos): BlockPos? {
        val bell = level.poiManager.findClosest({ it.`is`(PoiTypes.MEETING) }, near, 48, PoiManager.Occupancy.ANY).orElse(null) ?: return null
        for (pos in BlockPos.betweenClosed(bell.offset(-10, -5, -10), bell.offset(10, 5, 10))) {
            val state = level.getBlockState(pos)
            if (state.block is QuestBoardBlock) return QuestBoardBlock.anchorPos(pos.immutable(), state)
        }
        return null
    }

    /** Does this board's village have a road yet? Deliveries and escorts are only posted once it does. */
    fun hasRoad(level: Level, boardPos: BlockPos): Boolean {
        val serverLevel = level as? ServerLevel ?: return false
        return RoadNetwork.get(serverLevel).roadsFrom(Advances.villageCenter(serverLevel, boardPos)).isNotEmpty()
    }

    /** What the board at [anchor] has round it to post jobs about: its village's roads, the sea and the beasts about. */
    fun surroundings(level: Level, anchor: BlockPos): QuestGenerator.Surroundings {
        val serverLevel = level as? ServerLevel ?: return QuestGenerator.Surroundings(false)
        val market by lazy { MarketFetch.at(serverLevel, anchor) }
        return QuestGenerator.Surroundings(
            hasRoad(level, anchor),
            { SeaWork.reach(serverLevel, anchor) },
            { target -> BossHunts.quarries(serverLevel, anchor, target).isNotEmpty() },
            { Bounties.wanted(serverLevel, anchor) },
            { Captives.held(serverLevel, anchor) },
            { item -> market?.let { MarketFetch.ask(it, item) } },
        )
    }

    /** Name tag if it has one, otherwise a first name fixed by its UUID: "Maren the Librarian". */
    private fun posterName(villager: Villager, profession: Identifier?): String {
        villager.customName?.let { return it.string }
        val uuid = villager.uuid
        return "${QuestGenerator.nameFor(uuid.mostSignificantBits xor uuid.leastSignificantBits)} the ${QuestGenerator.title(profession)}"
    }


    /** Called periodically per player: give each nearby village without a board one, and a Wardfire, once. */
    fun checkNearbyVillages(level: ServerLevel, around: BlockPos) {
        val boards = GuildmarkConfig.AUTO_BOARDS.get()
        val fires = GuildmarkConfig.WARDFIRES.get()
        if (!boards && !fires) return
        val poi = level.poiManager
        val bells = poi.findAll({ it.`is`(PoiTypes.MEETING) }, { true }, around, BELL_SEARCH_RADIUS, PoiManager.Occupancy.ANY).toList()
        for (bell in bells) {
            val chunk = level.getChunkAt(bell)
            if (boards && !chunk.getData(ModAttachments.VILLAGE_BOARD_CHECKED)) {
                chunk.setData(ModAttachments.VILLAGE_BOARD_CHECKED, true)
                chunk.markUnsaved()
                val hasBoard = poi.getCountInRange({ it.`is`(ModVillagers.GUILDMASTER_POI.key!!) }, bell, VILLAGE_BOARD_RADIUS, PoiManager.Occupancy.ANY) > 0
                if (!hasBoard) placeNear(level, bell)
            }
            // Villages from before Wardfires get theirs too
            if (fires && !chunk.getData(ModAttachments.WARDFIRE_CHECKED)) {
                chunk.setData(ModAttachments.WARDFIRE_CHECKED, true)
                chunk.markUnsaved()
                WardfireSites.forVillage(level, bell)
            }
            // With outlaws camped in reach, the village puts up a bounty board by its bell too
            if (boards && !chunk.getData(ModAttachments.BOUNTY_BOARD_CHECKED) && Bounties.wanted(level, bell).isNotEmpty()) {
                chunk.setData(ModAttachments.BOUNTY_BOARD_CHECKED, true)
                chunk.markUnsaved()
                placeNear(level, bell, ModBlocks.BOUNTY_BOARD.get())
            }
        }
    }

    /** Try spots in a ring around the bell, facing it, on solid ground with room for 3x2, for a board of [block]. */
    private fun placeNear(level: ServerLevel, bell: BlockPos, block: NoticeBoardBlock = ModBlocks.QUEST_BOARD.get()): Boolean {
        val random = level.random
        repeat(48) {
            val dx = random.nextInt(17) - 8
            val dz = random.nextInt(17) - 8
            if (abs(dx) < 4 && abs(dz) < 4) return@repeat
            val anchor = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bell.offset(dx, 0, dz))
            val facing = if (abs(dx) > abs(dz)) {
                if (dx > 0) Direction.WEST else Direction.EAST
            } else {
                if (dz > 0) Direction.NORTH else Direction.SOUTH
            }
            if (fits(level, anchor, facing)) {
                val state = block.defaultBlockState().setValue(NoticeBoardBlock.FACING, facing)
                level.setBlock(anchor, state, Block.UPDATE_ALL)
                for (part in BoardPart.entries) {
                    if (part != BoardPart.ANCHOR) {
                        level.setBlock(NoticeBoardBlock.cellPos(anchor, facing, part), state.setValue(NoticeBoardBlock.PART, part), Block.UPDATE_ALL)
                    }
                }
                block.fill(level, anchor, facing)
                return true
            }
        }
        return false
    }

    private fun fits(level: ServerLevel, anchor: BlockPos, facing: Direction): Boolean =
        BoardPart.entries.all { part ->
            val pos = QuestBoardBlock.cellPos(anchor, facing, part)
            val clear = !level.isOutsideBuildHeight(pos) &&
                level.getBlockState(pos).canBeReplaced() &&
                level.getFluidState(pos).isEmpty &&
                level.getBlockState(pos.relative(facing)).canBeReplaced()
            val grounded = part.row != 0 || level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
            clear && grounded
        }
}
