package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.quest.BossHunts
import io.github.vaspyyy.guildmark.quest.Bounties
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.SplittableRandom

/**
 * What the bounty boards post. Each day a board puts up a notice on every outlaw captain at large within reach, nearest
 * first, then a hunt for each kind of great beast about (a troll camp, a dragon's roost). A notice comes down as soon as
 * its quarry has fallen, and whatever's still unclaimed at the day's end comes down with the rest. A village with a band
 * camped in reach puts one up by its bell (see VillageBoards.checkNearbyVillages).
 */
object BountyBoards {
    private const val INTERVAL = 100L

    /** Who signs a bounty board's notices (see quest/Labels). */
    private const val POSTER = "#quest.guildmark.poster.watch"

    fun tick(level: ServerLevel, anchor: BlockPos, state: BlockState) {
        if ((level.gameTime + anchor.asLong()) % INTERVAL != 0L) return
        post(level, anchor, state.getValue(NoticeBoardBlock.FACING))
    }

    /** Takes down what's stale and pins up what's wanted in the board's empty cells: by day, or at once on a new board. */
    fun post(level: ServerLevel, anchor: BlockPos, facing: Direction, fresh: Boolean = false) {
        // Read as a notice is: the top row first, left to right
        val cells = BoardPart.entries.sortedWith(compareBy({ -it.row }, { it.col }))
            .mapNotNull { level.getBlockEntity(NoticeBoardBlock.cellPos(anchor, facing, it)) as? NoticeBoardBlockEntity }
        val today = VillageBoards.day(level)
        for (cell in cells) {
            val note = cell.note ?: continue
            if (cell.postedDay != today || Bounties.stale(level, note) || huntOver(level, anchor, note)) {
                cell.updateNote(null)
                torn(level, cell.blockPos, facing)
            }
        }
        // Notices go up by day, when the watch is about
        if (!fresh && !level.isBrightOutside) return
        val empty = ArrayDeque(cells.filter { it.note == null })
        if (empty.isEmpty()) return
        val pinned = cells.mapNotNull { it.note }
        val tier = QuestGenerator.tierAt(anchor.x, anchor.z)
        val around = VillageBoards.surroundings(level, anchor)
        // Each notice reads the same all day, on every board and however often it goes up: no board to put up and
        // break again until a better one shows
        for (notice in Bounties.wanted(level, anchor)) {
            if (empty.isEmpty()) return
            if (pinned.any { it.type == QuestType.BOUNTY && it.site == notice.site }) continue
            val note = QuestGenerator.generateWhere(daily(level, today, notice.site.toLong()), tier, around, POSTER) { it.type == QuestType.BOUNTY } ?: break
            empty.removeFirst().updateNote(Bounties.dress(note, notice))
        }
        val hunted = pinned.filter { it.type == QuestType.SLAY }.mapTo(HashSet()) { it.target }
        while (empty.isNotEmpty()) {
            val key = HUNTS + hunted.map { it.toString() }.sorted().hashCode()
            val note = QuestGenerator.generateWhere(daily(level, today, key), tier, around, POSTER) { it.type == QuestType.SLAY && it.target !in hunted } ?: break
            hunted.add(note.target)
            empty.removeFirst().updateNote(note)
        }
    }

    /** Sets the hunts' notices apart from the outlaws', which go by their band's number. */
    private const val HUNTS = 1L shl 40

    /** The same every time it's asked for [key] on [day]. */
    private fun daily(level: ServerLevel, day: Long, key: Long): RandomSource =
        RandomSource.create(SplittableRandom(level.seed xor day * 0x2545F4914F6CDD1DL xor key * 0x9E3779B97F4A7C15uL.toLong()).nextLong())

    /** A hunt whose beast no longer lives in reach. */
    private fun huntOver(level: ServerLevel, anchor: BlockPos, note: QuestNote): Boolean =
        note.type == QuestType.SLAY && BossHunts.quarries(level, anchor, note.target).isEmpty()

    /** A notice torn down: the rustle of paper and a scrap or two. */
    private fun torn(level: ServerLevel, cell: BlockPos, facing: Direction) {
        val at = Vec3.atCenterOf(cell).relative(facing, 0.3)
        level.playSound(null, cell, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.6f, 0.8f)
        level.sendParticles(ParticleTypes.WHITE_ASH, at.x, at.y, at.z, 6, 0.25, 0.25, 0.1, 0.01)
    }
}
