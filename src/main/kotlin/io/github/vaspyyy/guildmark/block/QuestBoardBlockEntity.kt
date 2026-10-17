package io.github.vaspyyy.guildmark.block

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * One cell of a village's quest board, its note and, on the anchor cell, the village's own state. When the anchor comes
 * down that state goes into [VillageRecords], for the village's next board to take back.
 */
class QuestBoardBlockEntity(pos: BlockPos, state: BlockState) :
    NoticeBoardBlockEntity(ModBlockEntities.QUEST_BOARD.get(), pos, state) {

    /** Anchor cell only: how many village advances are built, and progress toward the next. */
    var advanceIndex: Int = 0
        private set
    var advancePoints: Int = 0
        private set

    /**
     * Anchor cell only: the village funded its Trade Road while no other village was in reach. It moved on
     * to its next advance instead of waiting, and builds the road once there's somewhere for it to go.
     */
    var roadOwed: Boolean = false
        private set
    /** The day the village last looked for somewhere its owed road could go. */
    private var roadLookedDay: Long = -1

    /** Anchor cell only: the last day the village lost or won a siege (situational notes follow both). */
    var lastSiegeLost: Long = -100
        private set
    var lastSiegeWon: Long = -100
        private set

    /** Anchor cell only: the board of a capital, which stood there since the land was made. */
    var capital: Boolean = false
        private set

    /** Anchor cell only: a board made with the land, still waiting for its first notes. */
    private var starterNotesDue = false

    /** Sets up a capital's board as the world generates it: every fixed advance already built, notes to come. */
    fun foundCapital() {
        capital = true
        starterNotesDue = true
        advanceIndex = Advance.entries.size
        advancePoints = 0
        setChanged()
    }

    /** True once, for a board still waiting for its first notes. */
    fun takeStarterNotes(): Boolean {
        if (!starterNotesDue) return false
        starterNotesDue = false
        setChanged()
        return true
    }

    fun recordSiege(won: Boolean, day: Long) {
        if (won) lastSiegeWon = day else lastSiegeLost = day
        setChanged()
    }

    /** Anchor cell only: each player's standing with this village (see [Standing]). Synced so the board can show it. */
    private val standing = HashMap<UUID, Int>()

    fun standingOf(player: UUID): Int = standing[player] ?: 0

    /** Returns the new standing, never below zero. */
    fun addStanding(player: UUID, amount: Int): Int {
        val points = (standingOf(player) + amount).coerceAtLeast(0)
        standing[player] = points
        setChanged()
        level?.let { it.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS) }
        return points
    }

    fun oweRoad(owed: Boolean) {
        roadOwed = owed
        setChanged()
        level?.let { it.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS) }
    }

    /** True the first time it's asked on [day]: the owed road is looked for at most once a day. */
    fun lookForRoad(day: Long): Boolean {
        if (roadLookedDay == day) return false
        roadLookedDay = day
        setChanged()
        return true
    }

    fun setAdvanceProgress(index: Int, points: Int) {
        advanceIndex = index
        advancePoints = points
        setChanged()
        level?.let { it.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS) }
    }

    /** Anchor cell only: everything the village knows, as [VillageRecords] keeps it once the board has come down on [day]. */
    fun record(day: Long): VillageRecord = VillageRecord(
        blockPos, day, advanceIndex, ADVANCE_VERSION, advancePoints, roadOwed, roadLookedDay, lastSiegeLost, lastSiegeWon, capital, HashMap(standing),
    )

    /** Anchor cell only: a board put up where its village's last one came down takes back what that one knew. */
    fun restore(record: VillageRecord) {
        advanceIndex = record.advanceIndex
        if (record.advanceVersion < ADVANCE_VERSION) advanceIndex = advanceIndex.coerceAtMost(Advance.MARKET.ordinal)
        advancePoints = record.advancePoints
        roadOwed = record.roadOwed
        roadLookedDay = record.roadLooked
        lastSiegeLost = record.siegeLost
        lastSiegeWon = record.siegeWon
        capital = record.capital
        standing.clear()
        standing.putAll(record.standing)
        setChanged()
        level?.let { it.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS) }
    }

    // The anchor going, however it goes (broken, blown up, built over): what the village knew is kept for its next board
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        super.preRemoveSideEffects(pos, state)
        val level = level as? ServerLevel ?: return
        if (state.getValue(NoticeBoardBlock.PART) != BoardPart.ANCHOR) return
        VillageRecords.get(level).keep(record(VillageBoards.day(level)))
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt("advance_index", advanceIndex)
        output.putInt("advance_version", ADVANCE_VERSION)
        output.putInt("advance_points", advancePoints)
        if (roadOwed) output.putBoolean("road_owed", true)
        if (roadLookedDay >= 0) output.putLong("road_looked", roadLookedDay)
        output.putLong("siege_lost", lastSiegeLost)
        output.putLong("siege_won", lastSiegeWon)
        if (capital) output.putBoolean("capital", true)
        if (starterNotesDue) output.putBoolean("starter_notes", true)
        if (standing.isNotEmpty()) output.store("standing", STANDING_CODEC, standing)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        advanceIndex = input.getIntOr("advance_index", 0)
        // Before the market, walls and keep, every advance past the archer tower was another trade road
        if (input.getIntOr("advance_version", 1) < ADVANCE_VERSION) advanceIndex = advanceIndex.coerceAtMost(Advance.MARKET.ordinal)
        advancePoints = input.getIntOr("advance_points", 0)
        roadOwed = input.getBooleanOr("road_owed", false)
        roadLookedDay = input.getLongOr("road_looked", -1L)
        lastSiegeLost = input.getLongOr("siege_lost", -100L)
        lastSiegeWon = input.getLongOr("siege_won", -100L)
        capital = input.getBooleanOr("capital", false)
        starterNotesDue = input.getBooleanOr("starter_notes", false)
        standing.clear()
        input.read("standing", STANDING_CODEC).ifPresent { standing.putAll(it) }
    }

    private companion object {
        /** Bumped when the chain of advances changes, so older boards can be moved onto the new one. */
        const val ADVANCE_VERSION = 2

        val STANDING_CODEC: Codec<Map<UUID, Int>> = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT)
    }
}
