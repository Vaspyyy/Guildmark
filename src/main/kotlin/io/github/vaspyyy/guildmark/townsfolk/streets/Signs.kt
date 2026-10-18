package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.festival.FestivalTrimBlock
import io.github.vaspyyy.guildmark.festival.TrimLook
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.ComingsBodies
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * "To let" boards (living-world/design-influence.md section 5, M6 Part D2): a board on a post by the front door of a
 * home a household has left ([Comings.empty]), until someone moves in, or for [DAYS] days. Within a player's sight
 * ([Streets.SIGHT]) someone of the town brings it, a noble of the house if one's about (else a neighbour): they set it
 * down by the door, knock its post in (STAKE) and say the household's gone (`fortune.gone`); and someone takes it up
 * again when it's done with (PICK_UP) and carries it off. Out of sight it's simply there, or gone.
 *
 * Each board is the festivals' trim in a look of its own ([TrimLook.TO_LET]) under the record of what stood there
 * ([Streets.BOARDS], run: the home's lodging id), placed only into air (or a plant anyone may build over) and put back
 * as it was. Its day is kept in [StreetsData] ([StreetsData.Town.boards]), and kept after the board comes down for as
 * long as the home stands empty, so it isn't put up again. All of it waits on [Comings.enabled], but for
 * `/guildmark streets board`'s tests (this session's).
 */
object Signs : Comings.Listener {
    /** Boards someone's on their way to put up or take down just now (by position). */
    private val busy = HashSet<Long>()

    /** When a board that wouldn't go up (or come down) is tried again, by position. */
    private val retry = HashMap<Long, Long>()

    /** The streets command's boards, by capital column: each home's board and the game time it's up till. This session's only. */
    private val forced = HashMap<Long, HashMap<Int, Long>>()

    // ---- The households ----

    override fun left(level: ServerLevel, h: Household) {
        if (!Comings.enabled() && !Streets.testing) return
        val data = StreetsData.get(level)
        val town = data.town(Streets.column(h.capital))
        town.boards[h.home] = Days.dayOf(level.overworldClockTime)
        data.changed()
    }

    // An arrival (or a household come back) takes the home off Comings.empty: its board comes down at the next look

    // ---- Kept right ----

    /** Whether the board at [pos] (home [run]) is wanted still: the home empty and the board not a week up yet. */
    fun wants(level: ServerLevel, column: Long, pos: BlockPos, run: Long): Boolean {
        if (pos.asLong() in busy) return true
        val home = run.toInt()
        if (forcedFor(level, column, home)) return true
        if (!Comings.enabled()) return false
        val t = StreetsData.get(level)[column] ?: return false
        val day = t.boards[home] ?: return false
        if (Days.dayOf(level.overworldClockTime) - day >= DAYS) return false
        // Asked of Comings only once a player's in the capital again
        val h = Streets.here(column) ?: return true
        return home in Comings.empty(level, h.key)
    }

    private fun forcedFor(level: ServerLevel, column: Long, home: Int): Boolean {
        if (!Streets.testing) return false
        val until = forced[column]?.get(home) ?: return false
        return level.gameTime < until
    }

    /** Once a second, a player in the capital: each board up where it's wanted and down where it isn't. */
    fun look(level: ServerLevel, column: Long, h: Scenes.Here, now: Long) {
        val enabled = Comings.enabled()
        val tests = forced[column]
        if (tests != null) {
            tests.entries.removeIf { !Streets.testing || it.value <= now }
            if (tests.isEmpty()) forced.remove(column)
        }
        val data = StreetsData.get(level)
        val t = data[column]
        val empty = if (enabled) gone(level, h.key) else setOf()
        if (t == null && empty.isEmpty() && forced[column] == null) return
        val town = t ?: data.town(column)
        val today = Days.dayOf(level.overworldClockTime)
        var changed = false
        // A home found empty with no board yet gets one (the household left while the streets weren't watching)
        for (home in empty) if (home !in town.boards) {
            town.boards[home] = today
            changed = true
        }
        forced[column]?.keys?.forEach { home ->
            if (home !in town.boards) {
                town.boards[home] = today
                changed = true
            }
        }
        val gone = ArrayList<Int>()
        for ((home, day) in town.boards) {
            val lodging = h.places.lodgings[home]
            if (lodging == null) {
                gone += home
                continue
            }
            val want = forcedFor(level, column, home) || enabled && home in empty && today - day < DAYS
            val standing = standing(level, column, lodging)
            if (standing != null && (standing.asLong() in busy || Streets.pending(standing))) continue
            when {
                want && standing == null -> {
                    val spot = spot(level, lodging) ?: continue
                    if (spot.asLong() in busy || Streets.pending(spot) || (retry[spot.asLong()] ?: Long.MIN_VALUE) > now) continue
                    if (Streets.seen(level, spot, Streets.SIGHT)) bring(level, column, h, lodging, spot, putting = true)
                    else Streets.later(spot) { lvl -> if (!put(lvl, column, lodging, spot, sound = false)) retry[spot.asLong()] = lvl.gameTime + RETRY }
                }
                !want && standing != null -> {
                    if ((retry[standing.asLong()] ?: Long.MIN_VALUE) > now) continue
                    if (Streets.seen(level, standing, Streets.SIGHT)) bring(level, column, h, lodging, standing, putting = false)
                    else Streets.later(standing) { lvl -> take(lvl, column, standing, sound = false) }
                }
                // Down, and the home no longer empty (or its test over): done with
                !want && standing == null && home !in empty && forced[column]?.containsKey(home) != true -> gone += home
            }
        }
        for (home in gone) {
            town.boards.remove(home)
            changed = true
        }
        if (changed) data.changed()
    }

    /**
     * Someone of the town near [lodging] brings its board and puts it up at [spot] ([putting]) or takes it up: a noble if
     * one's about, else whoever's free near by. Nobody free: tried again in a while.
     */
    private fun bring(level: ServerLevel, column: Long, h: Scenes.Here, lodging: CityPlaces.Lodging, spot: BlockPos, putting: Boolean) {
        val door = lodging.front ?: return
        val p = Fitting.hire(level, spot, h.capital.center, setOf(), loose = false, roles = NOBLES)
            ?: Fitting.hire(level, spot, h.capital.center, setOf(), loose = false)
        if (p == null) {
            retry[spot.asLong()] = level.gameTime + WAIT
            return
        }
        val outward = door.inward.opposite
        val station = Station(
            spot, door.outside, null, outward, 0, Vec3.atCenterOf(spot).add(0.0, -0.2, 0.0), ItemStack(Items.OAK_SIGN),
            up = { lvl -> put(lvl, column, lodging, spot, sound = true) },
            untie = null,
            down = { lvl -> take(lvl, column, spot, sound = true) },
            stake = if (putting) STROKES else 0,
            words = if (putting) "fortune.gone" else null,
            why = "a \"To let\" board by the door of a home left empty",
        )
        val key = spot.asLong()
        val sent = Fitting.send(level, p, listOf(station), putting, column) {
            busy.remove(key)
            // Not done (blocked, called away): tried again in a while
            if (Streets.mine(level, spot, column, Streets.BOARDS) != putting) retry[key] = level.gameTime + WAIT
        }
        if (!sent) {
            retry[key] = level.gameTime + WAIT
            return
        }
        busy += key
    }

    /** [lodging]'s board put up at [spot] now, if it's still wanted and there's room. True if it stands. */
    private fun put(level: ServerLevel, column: Long, lodging: CityPlaces.Lodging, spot: BlockPos, sound: Boolean): Boolean {
        val door = lodging.front ?: return false
        if (!level.isLoaded(spot)) return false
        val h = Streets.here(column)
        val wanted = forcedFor(level, column, lodging.id) || Comings.enabled() && (h == null || lodging.id in gone(level, h.key))
        if (!wanted || !Places.floor(level, spot.below())) return false
        val state = FestivalTrimBlock.state(TrimLook.TO_LET, door.inward.opposite)
        if (!Streets.place(level, spot, state, column, Streets.BOARDS, lodging.id.toLong(), solid = true)) return false
        if (sound) level.playSound(null, spot, SoundEvents.HANGING_SIGN_PLACE, SoundSource.NEUTRAL, 0.8f, 0.95f)
        return true
    }

    /** The board at [pos] taken up now, what stood there put back. */
    private fun take(level: ServerLevel, column: Long, pos: BlockPos, sound: Boolean) {
        if (!Streets.mine(level, pos, column, Streets.BOARDS)) return
        Streets.restore(level, pos, column, Streets.BOARDS)
        if (sound) level.playSound(null, pos, SoundEvents.WOOD_BREAK, SoundSource.NEUTRAL, 0.6f, 1.15f)
    }

    /**
     * The homes of the capital whose heart is [capital] a household has left ([Comings.empty]) and is gone from: not one
     * still packing its cart at the door or on its way to the gate (nor one coming back to it just now).
     */
    private fun gone(level: ServerLevel, capital: Long): Set<Int> {
        val empty = Comings.empty(level, capital)
        if (empty.isEmpty()) return setOf()
        val moving = Residents.get(level).of(BlockPos.of(capital)).filter { it.home in empty && ComingsBodies.busy(it.id) }.mapTo(HashSet()) { it.home }
        return empty.filterTo(HashSet()) { it !in moving }
    }

    // ---- Where ----

    /** Where [lodging]'s board may go: beside its front door on the street, on whichever side has room. */
    private fun spots(lodging: CityPlaces.Lodging): List<BlockPos> {
        val door = lodging.front ?: return listOf()
        val side = door.inward.clockWise
        return listOf(door.outside.relative(side.opposite), door.outside.relative(side))
    }

    /** Where [lodging]'s board is standing, if it is. */
    private fun standing(level: ServerLevel, column: Long, lodging: CityPlaces.Lodging): BlockPos? =
        spots(lodging).firstOrNull { level.isLoaded(it) && Streets.mine(level, it, column, Streets.BOARDS, lodging.id.toLong()) }

    /** Where [lodging]'s board would go now: the first spot beside its door with room, on firm ground. */
    private fun spot(level: ServerLevel, lodging: CityPlaces.Lodging): BlockPos? = spots(lodging).firstOrNull { pos ->
        level.isLoaded(pos) && Places.floor(level, pos.below()) && level.getBlockState(pos).let { it.isAir || it.canBeReplaced() && it.fluidState.isEmpty }
    }

    // ---- The streets command ----

    /**
     * `/guildmark streets board`: a board for the home whose front door is nearest [at] (within 24 blocks), up for
     * [ticks] whatever Comings says, brought by someone if there's anyone about. The home's id, or -1 if there's none.
     */
    fun force(level: ServerLevel, h: Scenes.Here, at: BlockPos, ticks: Long): Int {
        val lodging = h.places.lodgings.values.filter { !it.farm && it.front != null }
            .minByOrNull { it.front!!.outside.distSqr(at) }?.takeIf { it.front!!.outside.distSqr(at) <= 24.0 * 24.0 } ?: return -1
        Streets.testing = true
        val column = Streets.column(h.capital)
        forced.getOrPut(column) { HashMap() }[lodging.id] = level.gameTime + ticks
        spots(lodging).forEach { retry.remove(it.asLong()) }
        look(level, column, h, level.gameTime)
        return lodging.id
    }

    /** For the streets command: the boards up (or due) in [h]'s capital, and why. */
    fun status(level: ServerLevel, column: Long, h: Scenes.Here): List<String> {
        val t = StreetsData.get(level)[column] ?: return listOf()
        val today = Days.dayOf(level.overworldClockTime)
        val left = if (Comings.enabled()) Comings.empty(level, h.key).toSet() else setOf()
        val empty = if (left.isEmpty()) setOf() else gone(level, h.key)
        return t.boards.entries.sortedBy { it.key }.mapNotNull { (home, day) ->
            val lodging = h.places.lodgings[home] ?: return@mapNotNull null
            val at = standing(level, column, lodging)
            val why = when {
                forcedFor(level, column, home) -> "the streets command's (${((forced[column]?.get(home) ?: 0L) - level.gameTime) / 20}s left)"
                home in left && home !in empty -> "the household's still on its way out: up once it's gone"
                home in empty && today - day < DAYS -> "left empty on day $day, ${DAYS - (today - day)} days to go"
                home in empty -> "left empty on day $day: a week's gone by, so it's down"
                else -> "someone's moved in: coming down"
            }
            "${lodging.kind} #$home ${if (at != null) "board up at ${at.toShortString()}" else "no board standing"} ($why)"
        }
    }

    fun forget() {
        busy.clear()
        retry.clear()
        forced.clear()
    }

    /** Days a board stands at most. */
    const val DAYS = 7

    /** Strokes of STAKE that knock its post in. */
    private const val STROKES = 2

    /** Ticks before a board nobody could bring (or that wouldn't go up) is tried again. */
    private const val WAIT = 400L
    private const val RETRY = 6000L

    /** Who brings a board first: a noble of the town, for the house. */
    private val NOBLES = setOf(TownRole.NOBLE)
}
