package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.WorldCanvas
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Census
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.function.Supplier

/**
 * Sets out the market's displays of goods in the capitals built before there were any, at the spots a capital built
 * now has them (the plan's, [Census.displays]), so old capitals and new end up the same. Like [Furnish] with its beds:
 * out of sight (never with a player within [Furnish.UNSEEN] blocks of the spot), only where the land is loaded, and a
 * few spots a second, so a capital coming into view costs nothing anyone would notice. A display goes only where the
 * spot still holds just what the plan put there before, or nothing, on the counter or table it stood on: whatever a
 * player built or changed there is left as it is.
 *
 * Each spot is seen to once. When its display goes up (or is found already there, built with the capital), when one
 * there is taken away, or when the spot is found changed, it's marked in [WaresSpots] and never looked at again.
 */
object WaresPlacer {
    /** Ticks between steps of the round, and how many spots each step looks at. */
    private const val STEP = 10
    private const val PER_STEP = 4

    /** Ticks between looking for the capitals players are in, and how long one stays on the round after they've gone. */
    private const val SCAN = 100
    private const val KEEP = 1200

    /** Ticks between going round all of a capital's spots that are still to be seen to. */
    private const val ROUND = 600

    /** What came of looking at a spot. */
    enum class Outcome {
        /** A display stands there now, put up just now or before. */
        THERE,
        PLACED,
        /** Something else stands there, or what it stood on is gone: it's left as it is. */
        CHANGED,
        /** Not now: its land isn't loaded, someone's watching, or someone's standing there. */
        WAITING,
    }

    private class Round(val capital: CapitalPiece) {
        var seen = 0L
        var started = Long.MIN_VALUE / 2
        val queue = ArrayDeque<Census.Display>()
    }

    private val rounds = LinkedHashMap<BlockPos, Round>()
    private var scanned = Long.MIN_VALUE / 2
    private var turn = 0

    /** Starts the rounds with the server's ticks, and forgets them when it stops. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            rounds.clear()
            scanned = Long.MIN_VALUE / 2
        }
    }

    /** The spot at [pos] has been seen to (its display went up, or was taken away): it's never filled again. */
    fun done(level: ServerLevel, pos: BlockPos) {
        if (level.dimension() != Level.OVERWORLD || Furnish.capitalAt(level, pos) == null) return
        WaresSpots.get(level).add(pos)
    }

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val time = level.gameTime
        if (time % STEP != 0L || !WaresRegistry.enabled() || level.players().isEmpty()) return
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
            val capital = rounds.values.firstOrNull { it.capital.reaches(pos.x, pos.z) }?.capital ?: Furnish.capitalAt(level, pos) ?: continue
            rounds.getOrPut(capital.center) { Round(capital) }.seen = time
        }
        rounds.values.removeIf { time - it.seen > KEEP }
    }

    /** A few of a capital's spots looked at, the round starting again every [ROUND] ticks with those still to see to. */
    private fun step(level: ServerLevel, round: Round, time: Long) {
        val places = CityPlaces.of(level, round.capital) ?: return
        if (round.queue.isEmpty()) {
            if (time - round.started < ROUND) return
            round.started = time
            val spots = WaresSpots.get(level)
            places.census.displays.filterTo(round.queue) { !spots.has(spot(round.capital, it)) }
        }
        repeat(PER_STEP) {
            val d = round.queue.removeFirstOrNull() ?: return
            visit(level, round.capital, d, quiet = true)
        }
    }

    /** Where display [d] of [capital]'s plan stands in the world. */
    fun spot(capital: CapitalPiece, d: Census.Display): BlockPos = capital.world(d.at.x, d.at.y, d.at.z)

    /**
     * Sees to the spot of [capital]'s display [d]: puts the display up if the spot still holds what the plan put there
     * before (or nothing), and marks the spot seen to unless it has to wait. [quiet]: only with nobody near enough to
     * see it happen.
     */
    fun visit(level: ServerLevel, capital: CapitalPiece, d: Census.Display, quiet: Boolean): Outcome {
        val pos = spot(capital, d)
        val outcome = look(level, capital, d, pos, quiet)
        if (outcome == Outcome.PLACED) place(level, capital, d, pos)
        if (outcome != Outcome.WAITING) WaresSpots.get(level).add(pos)
        return outcome
    }

    /** What putting up display [d] at [pos] would come to now, without doing it. */
    fun look(level: ServerLevel, capital: CapitalPiece, d: Census.Display, pos: BlockPos, quiet: Boolean): Outcome {
        if (!level.hasChunk(pos.x shr 4, pos.z shr 4) || quiet && watched(level, pos)) return Outcome.WAITING
        val state = level.getBlockState(pos)
        if (state.block is WaresBlock) return Outcome.THERE
        if (!state.isAir && state != d.was?.let { WorldCanvas.planned(capital, it, pos) }) return Outcome.CHANGED
        val under = pos.below()
        if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) return Outcome.CHANGED
        if (level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos)).isNotEmpty()) return Outcome.WAITING
        return Outcome.PLACED
    }

    /** Puts up display [d] at [pos], for [capital]'s market, as full as the market is now. */
    private fun place(level: ServerLevel, capital: CapitalPiece, d: Census.Display, pos: BlockPos) {
        level.setBlock(pos, state(capital, d, pos), Block.UPDATE_ALL)
        val entity = level.getBlockEntity(pos) as? WaresBlockEntity ?: return
        entity.setUp(capital.center, d.wares)
        entity.refresh(level)
    }

    /** The block of [capital]'s display [d], as a capital built now has it at [pos]. */
    fun state(capital: CapitalPiece, d: Census.Display, pos: BlockPos): BlockState =
        WorldCanvas.planned(capital, B(M.WARES, d.facing, v = d.wares.ordinal), pos)

    /** Whether a player is within [Furnish.UNSEEN] blocks of [pos]. */
    private fun watched(level: ServerLevel, pos: BlockPos): Boolean =
        level.players().any { it.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) < Furnish.UNSEEN * Furnish.UNSEEN }
}

/** The spots [WaresPlacer] has seen to (see [WaresPlacer.done]), kept with the overworld. */
class WaresSpots(private val spots: MutableSet<Long> = HashSet()) : SavedData() {
    /** Whether the spot at [pos] has been seen to. */
    fun has(pos: BlockPos): Boolean = pos.asLong() in spots

    /** Marks the spot at [pos] seen to. */
    fun add(pos: BlockPos) {
        if (spots.add(pos.asLong())) setDirty()
    }

    companion object {
        private val CODEC: Codec<WaresSpots> = RecordCodecBuilder.create { i ->
            i.group(Codec.LONG.listOf().optionalFieldOf("spots", listOf()).forGetter { it.spots.toList() })
                .apply(i) { spots -> WaresSpots(spots.toHashSet()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "market_displays"), Supplier { WaresSpots() }, CODEC)

        /** The world's, kept with the overworld whichever [level] asks. */
        fun get(level: ServerLevel): WaresSpots = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
