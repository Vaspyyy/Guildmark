package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.WaresBlockEntity
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalOrnamentBlock
import io.github.vaspyyy.guildmark.festival.FestivalOrnamentBlockEntity
import io.github.vaspyyy.guildmark.festival.OrnamentData
import io.github.vaspyyy.guildmark.festival.OrnamentKind
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.scenes.ShutStall
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * The shut stall's awning and slate (living-world/design-influence.md section 5, "The slate", M6 Part D2): as the
 * keeper of a stall with nothing to sell lets its awning's flap down (townsfolk/scenes/ShutStall, SHUT_STALL), the
 * flap unrolls from the awning's front edge in the stall's own stripes and hangs there stirring in the breeze; as they
 * tidy the bare counter, a slate goes up leaning on the display, chalked "Nothing today" as it's seen
 * ([WaresBlockEntity.slate]). When there's something to sell again and they put the awning up (SET_UP), the flap rolls
 * up and goes, and the slate's taken away. Each with the awning's flap of sound (`streets.awning`).
 *
 * The flap is a festival ornament of its own kind ([OrnamentKind.AWNING_FLAP]) under the record of what stood there,
 * like the dressing ([Streets.FLAPS], run: the stall's index), so it's put back however it ends. A stall found shut as
 * the land loads has its flap down and its slate up already. All of it follows ShutStall, which runs with the times
 * (and for `/guildmark streets slate` and `capital scene shut`, while the streets' tests are on this session).
 */
object Awnings : ShutStall.Awning {
    /** The flaps rolling up just now, by position: they're taken down when they're up. */
    private val rolling = HashSet<Long>()

    /** Whether anything here runs: the times, or the streets command's tests. */
    private fun on(): Boolean = Fortunes.enabled() || Streets.testing

    // ---- Told by the keepers ----

    override fun down(level: ServerLevel, stall: CityPlaces.Stall, by: Townsperson) {
        if (!on()) return
        val places = by.life?.places ?: return
        val column = Streets.column(places.capital)
        val pos = flapAt(stall)
        rolling.remove(pos.asLong())
        // Come into the world at a stall shut a while since: the flap's down and the slate's up already
        val found = by.tickCount < FOUND
        val front = stall.keeper.facing
        val along = front.clockWise
        val stripe = dyeAt(level, stall.customer.pos.above(3))
        val other = dyeAt(level, stall.customer.pos.above(3).relative(along))
        val data = OrnamentData(
            OrnamentKind.AWNING_FLAP, FestivalKind.of(Seasons.today(level).season), index = front.get2DDataValue(),
            string = Vec3(along.stepX * HALF, 0.0, along.stepZ * HALF), drop = DROP,
            colors = (stripe + 1) or ((other + 1) shl 8), since = if (found) 0L else level.gameTime,
        )
        if (!Streets.place(level, pos, FestivalOrnamentBlock.state(0, false), column, Streets.FLAPS, stall.index.toLong(), solid = false, data = data)) return
        if (!found) Streets.sound(level, pos.x + 0.5, pos.y + 0.9, pos.z + 0.5, "streets.awning", REACH, 0.8f, 0.95f + level.random.nextFloat() * 0.1f)
        // The slate goes up as they tidy the counter (at once with nobody by to see it)
        val display = display(level, stall) ?: return
        if (found || !Streets.seen(level, display, SLATE_SEEN)) {
            wares(level, display)?.slate(level, -1L)
            return
        }
        Streets.after(level, SLATE_AFTER) { lvl -> if (ShutStall.isShut(stall)) wares(lvl, display)?.slate(lvl, lvl.gameTime) }
    }

    override fun up(level: ServerLevel, stall: CityPlaces.Stall, by: Townsperson) {
        val places = by.life?.places ?: return
        val column = Streets.column(places.capital)
        display(level, stall)?.let { wares(level, it)?.slate(level, 0L) }
        val pos = flapAt(stall)
        if (Streets.mine(level, pos, column, Streets.FLAPS)) rollUp(level, column, pos, sound = true)
    }

    /** The flap at [pos] rolled up (it's taken down once it's up), with its sound if [sound]. */
    private fun rollUp(level: ServerLevel, column: Long, pos: BlockPos, sound: Boolean) {
        val be = level.getBlockEntity(pos) as? FestivalOrnamentBlockEntity
        if (be == null) {
            Streets.restore(level, pos, column, Streets.FLAPS)
            return
        }
        if (be.data.phase == 1 || !rolling.add(pos.asLong())) return
        be.setup(be.data.copy(phase = 1, since = level.gameTime))
        if (sound) Streets.sound(level, pos.x + 0.5, pos.y + 0.9, pos.z + 0.5, "streets.awning", REACH, 0.7f, 1.12f + level.random.nextFloat() * 0.08f)
        Streets.after(level, ROLL) { lvl ->
            rolling.remove(pos.asLong())
            Streets.restore(lvl, pos, column, Streets.FLAPS)
        }
    }

    // ---- Kept right ----

    /**
     * Whether the flap at [pos] (stall [run]) is wanted still: while the stall is shut (or being rolled up), and after a
     * restart while it's still bare (its keeper finds it shut again). With the times off, none but the tests'.
     */
    fun wants(level: ServerLevel, column: Long, pos: BlockPos, run: Long): Boolean {
        if (pos.asLong() in rolling) return true
        if (!on()) return false
        // Not known till a player's in the capital again: looked at then
        val h = Streets.here(column) ?: return true
        val stall = h.places.stalls.getOrNull(run.toInt())?.takeIf { flapAt(it) == pos } ?: return false
        return keep(level, h, stall)
    }

    /** Whether [stall]'s flap stays down: its keeper has it shut, or (the times on) it has nothing to sell. */
    private fun keep(level: ServerLevel, h: Scenes.Here, stall: CityPlaces.Stall): Boolean {
        if (ShutStall.isShut(stall)) return true
        if (!Fortunes.enabled()) return false
        val entry = Markets.get(level)[h.capital.center] ?: return false
        return Trades.wares(entry, stall.trade).isEmpty()
    }

    /** Once a second, a player in the capital: a flap or a slate left from a stall no longer shut taken away. */
    fun look(level: ServerLevel, column: Long, h: Scenes.Here, now: Long) {
        for (stall in h.places.stalls) {
            val pos = flapAt(stall)
            if (!level.isLoaded(pos) || pos.asLong() in rolling) continue
            val shut = ShutStall.isShut(stall)
            if (!shut) display(level, stall)?.let { d -> wares(level, d)?.takeIf { it.slate != 0L }?.slate(level, 0L) }
            if (!Streets.mine(level, pos, column, Streets.FLAPS, stall.index.toLong())) continue
            if (keep(level, h, stall)) continue
            if (Streets.seen(level, pos, Streets.SIGHT)) rollUp(level, column, pos, sound = true) else Streets.restore(level, pos, column, Streets.FLAPS)
        }
    }

    /** For the streets command: [stall]'s flap and slate as they are. */
    fun status(level: ServerLevel, column: Long, stall: CityPlaces.Stall): String? {
        val pos = flapAt(stall)
        val flap = level.isLoaded(pos) && Streets.mine(level, pos, column, Streets.FLAPS, stall.index.toLong())
        val slate = display(level, stall)?.let { wares(level, it)?.slate ?: 0L } ?: 0L
        val shut = ShutStall.isShut(stall)
        if (!flap && slate == 0L && !shut) return null
        val parts = ArrayList<String>()
        if (flap) parts += if (pos.asLong() in rolling) "flap rolling up" else "flap down"
        if (slate != 0L) parts += "slate up"
        return "stall ${stall.index} at ${stall.keeper.pos.toShortString()}: ${parts.joinToString(", ").ifEmpty { "nothing up yet" }}" +
            if (shut) " (its keeper has it shut: nothing to sell)" else " (to come down)"
    }

    // ---- Where ----

    /** Where [stall]'s flap hangs from: the block under the front of its awning, over where its customers stand. */
    fun flapAt(stall: CityPlaces.Stall): BlockPos = stall.customer.pos.above(2)

    /** The display the slate leans on: the one in the middle of the counter if there is one, else the nearest to it. */
    private fun display(level: ServerLevel, stall: CityPlaces.Stall): BlockPos? {
        val middle = stall.counter.getOrNull(stall.counter.size / 2)?.above() ?: return null
        return (listOf(middle) + stall.goods.sortedBy { it.distSqr(middle) }).firstOrNull { level.isLoaded(it) && level.getBlockEntity(it) is WaresBlockEntity }
    }

    private fun wares(level: ServerLevel, pos: BlockPos): WaresBlockEntity? = if (level.isLoaded(pos)) level.getBlockEntity(pos) as? WaresBlockEntity else null

    /** The dye of the wool at [pos] (the awning's stripe), or -1 for none. */
    private fun dyeAt(level: ServerLevel, pos: BlockPos): Int {
        if (!level.isLoaded(pos)) return -1
        val block = level.getBlockState(pos).block
        return DyeColor.entries.firstOrNull { Blocks.WOOL.pick(it) === block }?.id ?: -1
    }

    fun forget() = rolling.clear()

    /** Ticks a keeper's body has been in the world and still finds their stall as it was left (as ShutStall's). */
    private const val FOUND = 200

    /** The flap's half-width along the stall (its awning is five blocks across) and how far it drops. */
    private const val HALF = 2.5
    private const val DROP = 1.1f

    /** Ticks after the flap comes down that the slate goes up (as the counter's tidied), and how near someone must be to see it. */
    private const val SLATE_AFTER = 50
    private const val SLATE_SEEN = 24.0

    /** Ticks the flap takes to roll up before it's taken down. */
    private const val ROLL = 30

    /** How far the awning's sound carries (its sounds.json reach). */
    private const val REACH = 24f
}
