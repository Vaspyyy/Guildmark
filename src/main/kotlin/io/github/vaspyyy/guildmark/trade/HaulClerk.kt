package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.HaulBoardBlockEntity
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The hall's merchant at the Haul Board (living-world/trader/design.md, Part 1 and 12.6). At half past seven in the morning and half past twelve, with
 * a player by to watch, and when tickets are posted between the rounds, they take their satchel across the square to the board, set it on the shelf,
 * chalk up only the rows that changed, a stroke at a time (the glass of a row whose news is new is tipped first), then hang each new ticket on its hook
 * and press the hall's seal on it by hand. They take the satchel up again, say a word to the square and go back to their counter. The board changes as
 * their hand does: each row is written, each ticket hung and each stamp put on at the moment of the clip that does it, so whoever watches sees the
 * board written up by the hand at it (the client draws the strokes from the times the board keeps; see [HaulBoardBlockEntity]).
 *
 * It is an [Aside] of the merchant's day, taken only when they are free (not serving, not hurt, not in another scene, as [Aside.free] has it) and
 * ended, as any is, by a word from a player, by danger, by the day's stretch moving on, or when nobody is near to see it. What was written stays
 * written; [HaulBoards] has the rest written up by itself once it has waited long enough for a merchant. A town with no merchant free to come
 * has its board written up all the same.
 */
object HaulClerk {
    /** How near a player must stay for the scene to go on (it is for being seen), and the longest it runs. */
    private const val REACH = 112.0
    private const val MOST = 2400

    /** How far from the board the capital's hall merchants are looked for. */
    private const val SEARCH = 64.0

    /**
     * The tick of a hang that the ticket is on its hook, of a stamp that the seal goes on (HAND_OVER's handoff: the hands pressed flat to the
     * ticket on the board), of a row that the chalk touches the slate, and of the setting down and taking up of the satchel.
     */
    private const val HANG_AT = 16
    private const val STAMP_AT = 16
    private const val CHALK_AT = 2
    private const val SATCHEL_AT = 8

    /** How far a merchant may be heard saying their word to the square. */
    private const val HEARD = 32.0

    /** Blocks out from the plane a ticket hangs in that the merchant stands to put it up: HAND_OVER's hands, held out, meet the board there (15 px). */
    private const val HELD_OUT = 15.0 / 16.0

    /** The boards a clerk is at, by anchor, each with the game time after which the clerk is taken to be lost (so the board is free again). */
    private val busy = HashMap<Long, Long>()

    /** Whether a clerk is at the board at [anchor] just now. */
    fun busy(level: ServerLevel, anchor: BlockPos): Boolean {
        val until = busy[anchor.asLong()] ?: return false
        if (level.gameTime <= until) return true
        busy.remove(anchor.asLong())
        return false
    }

    /** The server stopped: no clerk is at any board. */
    fun reset() {
        busy.clear()
    }

    /** The capital's hall merchants near the board at [anchor], the nearest first. */
    fun merchants(level: ServerLevel, anchor: BlockPos, capital: BlockPos, reach: Double = SEARCH): List<Townsperson> {
        val box = AABB(anchor).inflate(reach, 16.0, reach)
        return level.getEntitiesOfClass(Townsperson::class.java, box) {
            it.isAlive && it.role == TownRole.MERCHANT && it.life?.places?.centre == capital && Shop.kept(it) == Shop.HALL
        }.sortedBy { it.distanceToSqr(anchor.x + 0.5, anchor.y.toDouble(), anchor.z + 0.5) }
    }

    /**
     * Sends the nearest of the capital's hall merchants who is free to write up the board at [anchor]: the [todo] rows of [rows] (the rest wiped off
     * if [shrink]; a write-up of the round [key] if either) and the [hang] tickets. Gives whether one went (or one is at it already).
     */
    fun call(
        level: ServerLevel, anchor: BlockPos, board: HaulBoardBlockEntity, facing: Direction, capital: BlockPos,
        rows: List<HaulBoardBlockEntity.Row>, todo: List<Int>, shrink: Boolean, hang: List<HaulBoardBlockEntity.Ticket>, key: Long,
    ): Boolean {
        if (busy(level, anchor)) return true
        for (merchant in merchants(level, anchor, capital)) {
            if (play(level, anchor, board, facing, capital, merchant, rows, todo, shrink, hang, key)) return true
        }
        return false
    }

    /** The scene (see [call]), with [merchant] as the clerk; false if they could not be taken. */
    private fun play(
        level: ServerLevel, anchor: BlockPos, board: HaulBoardBlockEntity, facing: Direction, capital: BlockPos, merchant: Townsperson,
        rows: List<HaulBoardBlockEntity.Row>, todo: List<Int>, shrink: Boolean, hang: List<HaulBoardBlockEntity.Ticket>, key: Long,
    ): Boolean {
        val ak = anchor.asLong()
        val slate = HaulBoards.slate(anchor, facing)
        val shelf = HaulBoards.shelf(anchor, facing)
        val round = todo.isNotEmpty() || shrink
        val home = merchant.blockPosition()
        val moves = ArrayList<Move>()
        // Across the square with the satchel, and it is set down on the shelf
        moves.add(GoTo({ HaulBoards.stand(anchor, facing, 0) }, Jobs.WALK, 1.0, Activity.CARRY_SACK))
        moves.add(Beat(Activity.NONE, 6, { shelf }))
        moves.add(
            Beat(Activity.PUT_DOWN, Activity.PUT_DOWN.ticks, { shelf }) { _, t ->
                if (t == SATCHEL_AT) {
                    if (round) board.begin(key)
                    board.satchel(true)
                    level.playSound(null, shelf.x, shelf.y, shelf.z, SoundEvents.BUNDLE_INSERT, SoundSource.BLOCKS, 0.7f, 0.9f)
                }
            },
        )
        // The slate: the rows that changed, each a stroke at a time
        if (round) {
            moves.add(GoTo({ HaulBoards.stand(anchor, facing, -1) }, Jobs.WALK, 1.0))
            moves.add(Beat(Activity.NONE, 8, { slate }))
            for (k in todo) {
                val row = rows.getOrNull(k) ?: continue
                val at = HaulBoards.rowAt(anchor, facing, k)
                moves.add(
                    Beat(Activity.SCRIBE, HaulBoardBlockEntity.ROW_TICKS, { at }) { _, t ->
                        when {
                            t == CHALK_AT -> {
                                val before = board.rows.getOrNull(k)
                                board.chalk(k, row.written(level.gameTime))
                                // The row's glass is tipped first when the news of the town is new
                                if (before == null || before.tick != row.tick || before.age != row.age) HaulBoards.sound(level, at, "trade.hourglass", 0.8f)
                                crier(level, at, "chalk", 0.6f)
                            }
                            t > CHALK_AT && t % 18 == 0 && t < HaulBoardBlockEntity.ROW_TICKS - 10 -> crier(level, at, "chalk", 0.4f)
                            t == HaulBoardBlockEntity.ROW_TICKS - 8 -> crier(level, at, "chalk_tap", 0.5f)
                        }
                    },
                )
            }
            moves.add(Now { board.done() })
        }
        // The rail: each new ticket hung on its hook and stamped, from square before it, where both hands held out reach the board
        for (ticket in hang) {
            val hook = HaulBoards.hook(anchor, facing, ticket.slot)
            val before = Vec3(hook.x + facing.stepX * HELD_OUT, hook.y, hook.z + facing.stepZ * HELD_OUT)
            moves.add(GoTo({ HaulBoards.stand(anchor, facing, HaulBoards.column(ticket.slot)) }, Jobs.WALK, 1.0))
            moves.add(StoreLife.Stand({ before }, { facing.opposite.toYRot() }))
            moves.add(Beat(Activity.NONE, 4, { hook }))
            moves.add(
                Beat(Activity.HAND_OVER, HaulBoardBlockEntity.HANG_TICKS, { hook }) { _, t ->
                    if (t == HANG_AT) {
                        // Only if it is still wanted and still has no hook: on its own if that is still free
                        val live = HaulBoards.fresh(level, board, capital).firstOrNull { it.id == ticket.id } ?: return@Beat
                        val slot = if (board.tickets.none { it.slot == ticket.slot }) ticket.slot else live.slot
                        board.hang(live.with(slot = slot, pinned = level.gameTime, stamped = HaulBoardBlockEntity.UNSTAMPED))
                        crier(level, hook, "pin", 0.7f)
                    }
                },
            )
            // The hall's seal pressed on with both hands flat to the ticket (the STAMP clip comes down onto a counter: the board is upright)
            moves.add(
                Beat(Activity.HAND_OVER, Activity.HAND_OVER.ticks, { hook }) { _, t ->
                    if (t == STAMP_AT && board.ticket(ticket.id)?.stamped == HaulBoardBlockEntity.UNSTAMPED) {
                        board.stamp(ticket.id)
                        HaulBoards.sound(level, hook, "trade.board_stamp", 1.0f)
                    }
                },
            )
        }
        // The satchel taken up, a word to the square, and back to the counter
        moves.add(GoTo({ HaulBoards.stand(anchor, facing, 0) }, Jobs.WALK, 1.0))
        moves.add(Beat(Activity.NONE, 4, { shelf }))
        moves.add(
            Beat(Activity.PICK_UP, Activity.PICK_UP.ticks, { shelf }) { _, t ->
                if (t == SATCHEL_AT) {
                    board.satchel(false)
                    level.playSound(null, shelf.x, shelf.y, shelf.z, SoundEvents.BUNDLE_REMOVE_ONE, SoundSource.BLOCKS, 0.7f, 0.9f)
                }
            },
        )
        val line = lineFor(merchant, key, round, hang.isNotEmpty())
        val ticks = Talk.ticks(merchant, line)
        moves.add(
            Beat(Activity.NONE, maxOf(24, ticks - 10), { level.getNearestPlayer(merchant, 24.0)?.eyePosition ?: slate }) { p, t ->
                if (t == 0 && Words.on()) p.say(line, force = true, hearing = HEARD, ticks = ticks + 20L)
            },
        )
        moves.add(Now { if (round) board.finish(rows.size) })
        moves.add(GoTo({ home }, Jobs.WALK, 1.0, Activity.CARRY_SACK))
        val aside = Aside(moves, reach = REACH, most = MOST)
        // Called away, they leave the satchel on the shelf a while and the chalk on the ledge (see HaulBoards.check)
        aside.onEnd = { busy.remove(ak) }
        if (!Aside.take(merchant, aside, seated = true, station = true)) return false
        busy[ak] = level.gameTime + MOST + 100L
        return true
    }

    /** What the merchant says to the square: the morning's first word, or of the tickets, or of the word from the towns. */
    private fun lineFor(merchant: Townsperson, key: Long, round: Boolean, tickets: Boolean): String = when {
        round && HaulBoards.morning(key) -> "trade_board_first_${merchant.random.nextInt(2)}"
        tickets -> "trade_board_${merchant.random.nextInt(2) * 2}"
        else -> "trade_board_1"
    }

    /** One of the crier's board sounds (the chalk, its tap, a pin going in): the same hand's noises on the other board. */
    private fun crier(level: ServerLevel, at: Vec3, name: String, volume: Float) {
        val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.crier.$name")
        level.playSound(null, at.x, at.y, at.z, Holder.direct(SoundEvent.createVariableRangeEvent(id)), SoundSource.NEUTRAL, volume, 0.92f + level.random.nextFloat() * 0.16f)
    }

    /** One beat of the scene: [activity] for [ticks], facing what [at] gives and looking at it, [each] hearing each tick of it (from 0). */
    private class Beat(
        private val activity: Activity, private val ticks: Int, private val at: () -> Vec3?,
        private val each: (Townsperson, Int) -> Unit = { _, _ -> },
    ) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(activity)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            at()?.let { there ->
                p.lookAt(there)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            }
            // Something else played meanwhile (a nod to a passer-by) settles back into the loop
            if (activity.loop && p.activity != activity && (p.activity.loop || p.actDone)) p.act(activity)
            each(p, t)
            return t < ticks
        }
    }
}
