package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.advance.Advances
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModVillagers
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
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

    /** Pin a few notes on a fresh board so it isn't bare before villagers find it. */
    fun pinStarterNotes(level: Level, anchor: BlockPos, facing: Direction) {
        val cells = BoardPart.entries.shuffled().take(STARTER_NOTES)
        for (part in cells) {
            val cell = level.getBlockEntity(QuestBoardBlock.cellPos(anchor, facing, part)) as? QuestBoardBlockEntity
            cell?.updateNote(QuestGenerator.generateAnonymous(level.random, QuestGenerator.tierAt(anchor.x, anchor.z), hasRoad(level, anchor)))
        }
    }

    /** Runs on the anchor cell of every loaded board. */
    fun tickBoard(level: ServerLevel, anchor: BlockPos, state: BlockState) {
        if ((level.gameTime + anchor.asLong()) % BOARD_TICK_INTERVAL != 0L) return
        val facing = state.getValue(QuestBoardBlock.FACING)
        val cells = BoardPart.entries.mapNotNull {
            level.getBlockEntity(QuestBoardBlock.cellPos(anchor, facing, it)) as? QuestBoardBlockEntity
        }

        // Unclaimed notes from another day come down (also covers /time set going backwards)
        val today = day(level)
        for (cell in cells) {
            if (cell.note != null && cell.postedDay != today) cell.updateNote(null)
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
            pinNote(level, atBoard, empty.random(), facing)
        } else if (level.random.nextInt(4) == 0) {
            // Nudge someone over; their own routine may win, which is fine
            villagers.random().brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(front, 0.5f, 1))
        }
    }

    private fun pinNote(level: ServerLevel, villager: Villager, cell: QuestBoardBlockEntity, facing: Direction) {
        val profession = villager.villagerData.profession().unwrapKey().orElse(null)?.identifier()
        val tier = QuestGenerator.tierAt(cell.blockPos.x, cell.blockPos.z)
        cell.updateNote(QuestGenerator.generate(level.random, profession, posterName(villager, profession), tier, hasRoad(level, cell.blockPos)))
        villager.setData(ModAttachments.LAST_PINNED_DAY, day(level))
        villager.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(cell.blockPos))
        val spot = Vec3.atCenterOf(cell.blockPos.relative(facing))
        level.playSound(null, cell.blockPos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.NEUTRAL, 1.0f, 1.0f)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, spot.x, spot.y, spot.z, 4, 0.3, 0.3, 0.3, 0.0)
    }

    /** Does this board's village have a road yet? Deliveries and escorts are only posted once it does. */
    fun hasRoad(level: Level, boardPos: BlockPos): Boolean {
        val serverLevel = level as? ServerLevel ?: return false
        return RoadNetwork.get(serverLevel).roadsFrom(Advances.villageCenter(serverLevel, boardPos)).isNotEmpty()
    }

    /** Name tag if it has one, otherwise a first name fixed by its UUID: "Maren the Librarian". */
    private fun posterName(villager: Villager, profession: Identifier?): String {
        villager.customName?.let { return it.string }
        val uuid = villager.uuid
        return "${QuestGenerator.nameFor(uuid.mostSignificantBits xor uuid.leastSignificantBits)} the ${QuestGenerator.title(profession)}"
    }


    /** Called periodically per player: give each nearby village without a board one, once. */
    fun checkNearbyVillages(level: ServerLevel, around: BlockPos) {
        val poi = level.poiManager
        val bells = poi.findAll({ it.`is`(PoiTypes.MEETING) }, { true }, around, BELL_SEARCH_RADIUS, PoiManager.Occupancy.ANY).toList()
        for (bell in bells) {
            val chunk = level.getChunkAt(bell)
            if (chunk.getData(ModAttachments.VILLAGE_BOARD_CHECKED)) continue
            chunk.setData(ModAttachments.VILLAGE_BOARD_CHECKED, true)
            chunk.markUnsaved()

            val hasBoard = poi.getCountInRange({ it.`is`(ModVillagers.GUILDMASTER_POI.key!!) }, bell, VILLAGE_BOARD_RADIUS, PoiManager.Occupancy.ANY) > 0
            if (!hasBoard) placeNear(level, bell)
        }
    }

    /** Try spots in a ring around the bell, facing it, on solid ground with room for 3x2. */
    private fun placeNear(level: ServerLevel, bell: BlockPos): Boolean {
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
                val state = ModBlocks.QUEST_BOARD.get().defaultBlockState().setValue(QuestBoardBlock.FACING, facing)
                level.setBlock(anchor, state, Block.UPDATE_ALL)
                for (part in BoardPart.entries) {
                    if (part != BoardPart.ANCHOR) {
                        level.setBlock(QuestBoardBlock.cellPos(anchor, facing, part), state.setValue(QuestBoardBlock.PART, part), Block.UPDATE_ALL)
                    }
                }
                pinStarterNotes(level, anchor, facing)
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
