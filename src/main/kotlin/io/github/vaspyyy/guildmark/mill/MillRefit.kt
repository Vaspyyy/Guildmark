package io.github.vaspyyy.guildmark.mill

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.WorldCanvas
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.MillSite
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * Swaps an older capital's block-built sails and wheels for the hubs that turn, so that it ends up just as a capital
 * built today: for each of its mills that turns (see [MillSite]) the blocks the plan now builds differently are set as a
 * new capital has them, and the hub is set up for the capital. Only out of sight (no player within [UNSEEN] blocks),
 * only with all of the mill's land loaded, and only if every one of those blocks is still just as the plan built it: a
 * mill a player has changed in any way is left as it is (the testing command, /guildmark market mills, says where). The
 * capitals players are in are looked at one mill at a time, a second apart.
 */
object MillRefit {
    /** How near a player may come to a mill before it's left for later. */
    const val UNSEEN = 48.0

    /** Ticks between steps of the round, between looking for the capitals players are in, and how long one stays on it after they've gone. */
    private const val STEP = 20
    private const val SCAN = 100
    private const val KEEP = 1200

    /** Ticks before a mill a player changed is looked at again (they may have put it back), and one that was in sight or not loaded. */
    private const val CHANGED_AGAIN = 6000L
    private const val WAITING_AGAIN = 200L

    /** What came of looking at a mill. */
    enum class Outcome {
        /** Already the hub that turns. */
        TURNING,
        /** Swapped for the hub that turns, just now. */
        SWAPPED,
        /** Its sails stay built of blocks: something the city built beside it stands where they would turn. */
        CROWDED,
        /** Not now: some of its land isn't loaded. */
        NOT_LOADED,
        /** Not now: a player is near enough to see. */
        SEEN,
        /** A block of it isn't as the plan built it (see [Result.at]): left as it is. */
        CHANGED,
        /** Every block of it is as the plan built it: it can be swapped (asked without swapping). */
        READY,
    }

    /**
     * What came of looking at a mill: for [Outcome.CHANGED] the first block that isn't as the plan built it ([at], now
     * [found], where the plan built [built], or nothing but open air or water if that's null), for [Outcome.CROWDED] the
     * block in the sails' way ([at]).
     */
    class Result(val outcome: Outcome, val at: BlockPos? = null, val found: BlockState? = null, val built: BlockState? = null)

    /** One block a mill changes as it starts turning: what an older capital has there ([old], null where the plan set nothing), and what a new one has. */
    private class Change(val pos: BlockPos, val old: BlockState?, val now: BlockState)

    /** Everything a mill changes as it starts turning, the hub last, and the colour of its sails' cloth. */
    private class Swap(val changes: List<Change>, val cloth: Dye)

    private class Round(val capital: CapitalPiece) {
        var seen = 0L
        var next = 0
        var plan: CityPlan? = null
        var sites: List<MillSite>? = null
        val swaps = HashMap<Int, Swap>()
        val again = HashMap<Int, Long>()
        val done = HashSet<Int>()
    }

    private val rounds = LinkedHashMap<BlockPos, Round>()
    private var scanned = Long.MIN_VALUE / 2
    private var turn = 0

    internal fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            rounds.clear()
            scanned = Long.MIN_VALUE / 2
        }
    }

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val time = level.gameTime
        if (time % STEP != 0L || !Mills.enabled() || level.players().isEmpty()) return
        if (time - scanned >= SCAN) {
            scanned = time
            for (player in level.players()) {
                val pos = player.blockPosition()
                val capital = rounds.values.firstOrNull { it.capital.reaches(pos.x, pos.z) }?.capital ?: Furnish.capitalAt(level, pos) ?: continue
                rounds.getOrPut(capital.center) { Round(capital) }.seen = time
            }
            rounds.values.removeIf { time - it.seen > KEEP }
        }
        if (rounds.isEmpty()) return
        turn = (turn + 1) % rounds.size
        step(level, rounds.values.elementAt(turn), time)
    }

    /** The next of a capital's mills not yet seen to, looked at. */
    private fun step(level: ServerLevel, round: Round, time: Long) {
        val sites = round.sites ?: run {
            val plan = CityPlaces.of(level, round.capital)?.census?.plan ?: return
            round.plan = plan
            MillSite.of(plan).also { round.sites = it }
        }
        for (k in sites.indices) {
            val i = (round.next + k) % sites.size
            if (i in round.done || (round.again[i] ?: Long.MIN_VALUE) > time) continue
            round.next = i + 1
            val result = look(level, round, i, quiet = true, apply = true)
            when (result.outcome) {
                Outcome.TURNING, Outcome.SWAPPED, Outcome.CROWDED -> round.done.add(i)
                Outcome.CHANGED -> round.again[i] = time + CHANGED_AGAIN
                Outcome.NOT_LOADED, Outcome.SEEN, Outcome.READY -> round.again[i] = time + WAITING_AGAIN
            }
            return
        }
    }

    private fun look(level: ServerLevel, round: Round, i: Int, quiet: Boolean, apply: Boolean): Result {
        val sites = round.sites ?: return Result(Outcome.NOT_LOADED)
        val plan = round.plan ?: return Result(Outcome.NOT_LOADED)
        val site = sites[i]
        val swap = round.swaps.getOrPut(i) { swap(round.capital, plan, site) }
        return swap(level, round.capital, site, swap, quiet, apply)
    }

    /**
     * Looks at every mill of [capital] (whose plan is [plan]) now, players about or not (every other rule holds), and if
     * [apply] swaps each that can be, with a puff of dust where it's done; if not, only says what would come of it. For
     * the testing command.
     */
    fun all(level: ServerLevel, capital: CapitalPiece, plan: CityPlan, apply: Boolean): List<Pair<MillSite, Result>> {
        val round = rounds.getOrPut(capital.center) { Round(capital) }
        if (round.plan == null) round.plan = plan
        val sites = round.sites ?: MillSite.of(plan).also { round.sites = it }
        return sites.indices.map { i ->
            val result = look(level, round, i, quiet = false, apply = apply)
            if (apply) {
                when (result.outcome) {
                    Outcome.TURNING, Outcome.SWAPPED, Outcome.CROWDED -> round.done.add(i)
                    else -> round.done.remove(i)
                }
                round.again.remove(i)
            }
            sites[i] to result
        }
    }

    /** What [site] changes in [capital] as it starts turning: the blocks of the plan that differ, as the world has them. */
    private fun swap(capital: CapitalPiece, plan: CityPlan, site: MillSite): Swap {
        if (!site.turns) return Swap(emptyList(), Dye.WHITE)
        val old = site.drawn(plan, turning = false)
        val now = site.drawn(plan, turning = true)
        val list = ArrayList<Change>()
        for (at in old.keys + now.keys) {
            val a = old[at]
            val b = now[at]
            if (a != null && b != null && MillSite.alike(a, b)) continue
            val pos = capital.world(at.x, at.y, at.z)
            list.add(Change(pos, a?.let { WorldCanvas.planned(capital, it, pos) }, WorldCanvas.planned(capital, b ?: B.AIR, pos)))
        }
        // The hub last, once everything round it is as it should be
        val hub = capital.world(site.hub.x, site.hub.y, site.hub.z)
        list.sortBy { if (it.pos == hub) 1 else 0 }
        return Swap(list, now[site.hub]?.color ?: Dye.WHITE)
    }

    private fun swap(level: ServerLevel, capital: CapitalPiece, site: MillSite, swap: Swap, quiet: Boolean, apply: Boolean): Result {
        val changes = swap.changes
        if (!site.turns) return Result(Outcome.CROWDED, site.crowd?.let { capital.world(it.x, it.y, it.z) })
        val hub = capital.world(site.hub.x, site.hub.y, site.hub.z)
        if (!level.isLoaded(hub) || changes.any { !level.isLoaded(it.pos) }) return Result(Outcome.NOT_LOADED)
        val state = level.getBlockState(hub)
        if (state.block is MillBlock) {
            if (apply) set(level, capital, site, swap, hub)
            return Result(Outcome.TURNING)
        }
        if (quiet && level.players().any { it.distanceToSqr(hub.x + 0.5, hub.y + 0.5, hub.z + 0.5) < UNSEEN * UNSEEN }) return Result(Outcome.SEEN)
        for (c in changes) {
            val found = level.getBlockState(c.pos)
            if (!fits(found, c)) return Result(Outcome.CHANGED, c.pos, found, c.old)
        }
        if (!apply) return Result(Outcome.READY)
        for (c in changes) if (level.getBlockState(c.pos) != c.now) level.setBlock(c.pos, c.now, Block.UPDATE_ALL)
        set(level, capital, site, swap, hub)
        if (!quiet) {
            level.sendParticles(ParticleTypes.POOF, hub.x + 0.5, hub.y + 0.5, hub.z + 0.5, 14, 1.2, 1.2, 1.2, 0.02)
            level.playSound(null, hub, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 0.8f)
        }
        return Result(Outcome.SWAPPED)
    }

    /** Sets the hub at [hub] up for [capital], if its block entity doesn't know it yet, and has it say at once what the mill is doing. */
    private fun set(level: ServerLevel, capital: CapitalPiece, site: MillSite, swap: Swap, hub: BlockPos) {
        val be = level.getBlockEntity(hub) as? MillBlockEntity ?: return
        if (be.capital == null) be.found(capital.center, capital.style, swap.cloth, site.rank, site.count)
        Mills.check(level, be, always = true)
    }

    /**
     * Whether [found] is still what the older capital has where [c] is: the very block the plan put there (fences, walls
     * and stairs however they've joined up with their neighbours), or where the plan put nothing, the open air or water
     * of the place, or the ground a new capital would have cleared away to water.
     */
    private fun fits(found: BlockState, c: Change): Boolean {
        if (found == c.now) return true
        val old = c.old ?: return found.isAir || found.canBeReplaced() || found.`is`(Blocks.WATER) || found.`is`(Blocks.ICE) ||
            c.now.`is`(Blocks.WATER) && natural(found)
        if (found.block != old.block) return false
        return when (old.block) {
            is CrossCollisionBlock, is WallBlock -> true
            is StairBlock -> found.getValue(StairBlock.FACING) == old.getValue(StairBlock.FACING) && found.getValue(StairBlock.HALF) == old.getValue(StairBlock.HALF)
            else -> found == old
        }
    }

    /** The land's own ground: a stream's bed, a bank. */
    private fun natural(state: BlockState): Boolean = state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(BlockTags.SAND) ||
        state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.CLAY)
}
