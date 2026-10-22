package io.github.vaspyyy.guildmark.cooking.town

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.WorldCanvas
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.market.MenuBoardBlock
import io.github.vaspyyy.guildmark.cooking.vessel.OvenBlock
import io.github.vaspyyy.guildmark.cooking.vessel.SpitBlock
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlock
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotMode
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.CampfireBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent

/** How a retrofit run goes (living-world/cook/design.md 7.2): [PREVIEW] only says what it would do, [SEEN] does it with a puff and a sound where each block goes, [FULL] does that and also tries the Spit. */
enum class FitMode { PREVIEW, SEEN, FULL }

/**
 * What a retrofit run came to, as `Furnish.Report` says it for beds: what was [made], what was [kept] as it was, what had
 * [noRoom] and what is still [waiting], and where the blocks were [placed]. In [FitMode.PREVIEW] nothing is made: [made] is
 * what would be, and [placed] stays empty.
 */
class FitReport {
    var made = 0
    var kept = 0
    var noRoom = 0
    var waiting = 0
    val placed = ArrayList<BlockPos>()
}

/**
 * The retrofit round (living-world/cook/design.md 7.2): gives an old capital the house pot, the guest pot, the slates by
 * the tavern's and the bakery's doors and the bake oven, wherever the plan's own block still stands and nobody has touched
 * it. A capital drawn since the plans have the kitchens (plan version 3, [KITCHENS]) has them already and is never on the
 * round.
 *
 * It is a round like [Furnish]'s and keeps its manners: a step every 10 ticks, one capital a step; every 100 ticks a look at
 * the capitals players are on the ground of (from chunks already loaded), each kept on the round for 1,200 ticks after the
 * player has gone. Each tavern and bakery is looked at once and the result kept in [KitchenFitData]. It works out of sight
 * only (never with a player within [Furnish.UNSEEN] blocks of the building), only where the building's land is loaded, never
 * on top of anyone, and makes at most [MOST] placements a step; a placement with a player near enough to see it shows itself
 * (a puff and the block's own sound), as the town works do theirs, and otherwise is made quietly.
 *
 * What it replaces is exactly what the old plan drew there, with nothing in it: the tavern's campfire (no food on it, air
 * above), the tavern's smoker, the bakery's furnace. The slates go in air on solid ground by the doors, with a clear cell in
 * front so they never shut a way through; the Spit, only by hand, in the clear cell beside the back corner. Anything else is
 * somebody's and is kept: a chest, bed, table or sign of a player's is never replaced.
 *
 * `/guildmark cook fit [here|all|full|preview]` (see [FitCommand]) runs it by hand, with players about. With Town Kitchens
 * off ([Cooking.townKitchens]) the round does not run, a run by hand only says what it would do, and the blocks already
 * placed stay.
 */
object KitchenFit {
    /** The plan version that draws the kitchens itself (Layout's "3: the kitchens"); a capital drawn to it is not fitted. */
    internal const val KITCHENS = 3

    /** Ticks between steps of the round. */
    private const val STEP = 10

    /** Ticks between looking for the capitals players are on, and how long one stays on the round after they have gone. */
    private const val SCAN = 100
    private const val KEEP = 1200

    /** The most placements a step makes in a capital: a tavern's three; its bakery is seen to by the next step. */
    private const val MOST = 3

    /** How near a player must be to a placement of the round for it to show itself. */
    private const val SHOWN = 48.0

    private class Round(val capital: CapitalPiece) {
        var seen = 0L

        /** The lodgings still to look at, worked out at the first step (null before that). */
        var todo: MutableList<Int>? = null
    }

    private val rounds = LinkedHashMap<BlockPos, Round>()
    private var scanned = Long.MIN_VALUE / 2
    private var turn = 0

    // What the round has done since the server began, for `cook perf`
    private var settled = 0
    private var placements = 0
    private var lastMicros = 0L

    /** Part I1's registration: the round's tick, its clearing at stop, its line for `cook perf` and the `cook fit` command. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        Cooking.onForget { forget() }
        CookStats.readout("kitchen fit") {
            "${rounds.size} capitals on the round, $settled lodgings settled, $placements blocks placed, last step $lastMicros us" +
                if (Cooking.townKitchens()) "" else " (Town Kitchens is off: the round does not run)"
        }
        FitCommand.register()
    }

    private fun forget() {
        rounds.clear()
        scanned = Long.MIN_VALUE / 2
        turn = 0
        settled = 0
        placements = 0
        lastMicros = 0L
    }

    /**
     * Runs the retrofit once on the capital whose centre is [capital] (its `asLong`) in [mode], and says what came of it: an empty
     * report if there is no such capital, it has the kitchens already, or Town Kitchens is off (then only what would be done).
     */
    fun run(level: ServerLevel, capital: Long, mode: FitMode): FitReport {
        val centre = BlockPos.of(capital)
        val piece = rounds[centre]?.capital ?: Capitals.at(level, centre)?.takeIf { it.center == centre } ?: return FitReport()
        return fit(level, piece, mode).report
    }

    /**
     * The plan's own pots are the town's (7.1): the pot a new capital's plan draws in the tavern's hearth is the innkeeper's
     * (HOUSE), the camp pot in its back corner the guests' (GUEST). Called by the city's canvas as it draws one, after the block is
     * placed; the pot's block entity is, in a chunk still being made, only a note of one, which [LevelAccessor.getBlockEntity]
     * turns into the real one, so the mode is set on it before the chunk is saved.
     */
    fun potDrawn(level: LevelAccessor, pos: BlockPos, guest: Boolean) {
        (level.getBlockEntity(pos) as? StewpotBlockEntity)?.mode = if (guest) StewpotMode.GUEST else StewpotMode.HOUSE
    }

    // ---- The round ----

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val time = level.gameTime
        if (time % STEP != 0L || level.players().isEmpty() || !Cooking.townKitchens()) return
        if (time - scanned >= SCAN) {
            scanned = time
            scan(level, time)
        }
        if (rounds.isEmpty()) return
        turn = (turn + 1) % rounds.size
        step(level, rounds.values.elementAt(turn))
    }

    /** Which old capitals players are on the ground of, from what is loaded. */
    private fun scan(level: ServerLevel, time: Long) {
        for (player in level.players()) {
            val pos = player.blockPosition()
            val capital = rounds.values.firstOrNull { it.capital.reaches(pos.x, pos.z) }?.capital ?: Furnish.capitalAt(level, pos) ?: continue
            if (!old(capital)) continue
            rounds.getOrPut(capital.center) { Round(capital) }.seen = time
        }
        rounds.values.removeIf { time - it.seen > KEEP }
    }

    /** Whether [capital] was drawn before the plans had the kitchens (one saved by a mod too old to have a plan has no kitchens to fit). */
    private fun old(capital: CapitalPiece): Boolean = (capital.layout?.version ?: KITCHENS) < KITCHENS

    /** One step on one capital: looks at the lodgings not yet looked at, out of sight, and fits what it may, at most [MOST] blocks. */
    private fun step(level: ServerLevel, round: Round) {
        if (round.todo?.isEmpty() == true) return
        val capital = round.capital
        val places = CityPlaces.of(level, capital) ?: return
        val data = KitchenFitData.get(level)
        val key = capital.center.asLong()
        val todo = round.todo ?: relevant(places).filter { !data.has(key, it.id) }.mapTo(ArrayList()) { it.id }.also { round.todo = it }
        if (todo.isEmpty()) return
        val started = System.nanoTime()
        var budget = MOST
        val report = FitReport()
        val each = todo.iterator()
        while (each.hasNext() && budget > 0) {
            val lodging = places.lodgings[each.next()]
            if (lodging == null || data.has(key, lodging.id)) {
                each.remove()
                continue
            }
            if (!Furnish.loaded(level, lodging) || watched(level, lodging)) continue
            val items = inspect(level, capital, lodging, full = false)
            for (item in items) {
                if (item.finding != Finding.READY || budget == 0) continue
                make(level, item, report, show = seenBy(level, item.pos))
                budget--
            }
            if (settle(data, key, lodging, items)) each.remove()
        }
        lastMicros = (System.nanoTime() - started) / 1000
    }

    /** Whether a player is within [Furnish.UNSEEN] blocks of [lodging] (as `Furnish` has it). */
    private fun watched(level: ServerLevel, lodging: CityPlaces.Lodging): Boolean {
        val b = lodging.box
        return level.players().any { p ->
            val dx = maxOf(b.minX() - p.x, 0.0, p.x - b.maxX() - 1)
            val dy = maxOf(b.minY() - p.y, 0.0, p.y - b.maxY() - 1)
            val dz = maxOf(b.minZ() - p.z, 0.0, p.z - b.maxZ() - 1)
            dx * dx + dy * dy + dz * dz < Furnish.UNSEEN * Furnish.UNSEEN
        }
    }

    /** Whether a player is near enough to [pos] to see a block set there. */
    private fun seenBy(level: ServerLevel, pos: BlockPos): Boolean =
        level.players().any { !it.isSpectator && it.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) < SHOWN * SHOWN }

    /** The lodgings with a kitchen to fit: the city's taverns and bakeries (not the farms' inns), in the plan's order. */
    private fun relevant(places: CityPlaces): List<CityPlaces.Lodging> =
        places.lodgings.values.filter { !it.farm && (it.kind == Kind.TAVERN.name || it.kind == Kind.BAKERY.name) }.sortedBy { it.id }

    // ---- Looking ----

    /** The parts of a lodging's kitchen. */
    internal enum class Part(val label: String) {
        HOUSE_POT("house pot"),
        GUEST_POT("guest pot"),
        MENU_BOARD("menu board"),
        SPIT("spit"),
        BAKE_OVEN("bake oven"),
    }

    /** What looking at a part found. */
    internal enum class Finding {
        /** The plan's own block stands there and nothing is in the way: it will be made. */
        READY,

        /** Made just now. */
        MADE,

        /** Already as the kitchen has it: a pot, an oven, a slate or a spit stands there. */
        DONE,

        /** The cell holds something else, or the plan's block with something in it: kept as it is. */
        CHANGED,

        /** No clear cell for it. */
        NO_ROOM,

        /** Not now: its land is not loaded, or someone is in the way. */
        WAITING,

        /** Not tried in this mode (the spit, without `full`). */
        LATER,
    }

    /** One part of one lodging: where, what was found and why, and (for [Finding.READY]) what to make there. */
    internal class Item(val lodging: CityPlaces.Lodging, val part: Part, val pos: BlockPos, var finding: Finding, val note: String) {
        var state: BlockState? = null
        var mode: StewpotMode? = null

        /** The floor under it that becomes its hearthstone, if the plan's own floor is still there. */
        var floor: Pair<BlockPos, BlockState>? = null
    }

    /**
     * What a run came to: the capital and its plan [version], the [mode] it ran in (a preview when the switch is [off]), its
     * [report], each part looked at, and whether the capital's [places] were worked out yet and it is [old] enough to fit.
     */
    internal class Result(
        val capital: CapitalPiece, val version: Int, val mode: FitMode, val report: FitReport, val items: List<Item>,
        val places: Boolean, val old: Boolean, val off: Boolean,
    )

    /**
     * Looks at every kitchen part of [capital]'s taverns and bakeries and, unless [mode] is [FitMode.PREVIEW] (or Town Kitchens is
     * off), makes what it may, whatever the notes say and however near players are: by hand, so it is shown with a puff and a
     * sound. The notes are kept up to date. A building the round already looked at is looked at again here, which fits only where
     * the plan's own block still stands, so a pot, oven or slate that was fitted, and anything of a player's, is kept as it is.
     */
    internal fun fit(level: ServerLevel, capital: CapitalPiece, mode: FitMode): Result {
        val report = FitReport()
        val version = capital.layout?.version ?: 0
        val off = !Cooking.townKitchens()
        val doing = if (off) FitMode.PREVIEW else mode
        if (!old(capital)) return Result(capital, version, doing, report, listOf(), places = true, old = false, off = off)
        val places = CityPlaces.of(level, capital) ?: return Result(capital, version, doing, report, listOf(), places = false, old = true, off = off)
        val data = KitchenFitData.get(level)
        val key = capital.center.asLong()
        val items = ArrayList<Item>()
        for (lodging in relevant(places)) {
            val looked = inspect(level, capital, lodging, full = doing == FitMode.FULL)
            items.addAll(looked)
            if (doing == FitMode.PREVIEW) continue
            for (item in looked) if (item.finding == Finding.READY) make(level, item, report, show = true)
            settle(data, key, lodging, looked)
        }
        for (item in items) {
            when (item.finding) {
                // Only in a preview does a part stay ready: what would be made
                Finding.READY -> report.made++
                Finding.MADE, Finding.LATER -> {}
                Finding.DONE, Finding.CHANGED -> report.kept++
                Finding.NO_ROOM -> report.noRoom++
                Finding.WAITING -> report.waiting++
            }
        }
        return Result(capital, version, doing, report, items, places = true, old = true, off = off)
    }

    /** Looks at the parts of [lodging]'s kitchen; nothing is changed. */
    private fun inspect(level: ServerLevel, capital: CapitalPiece, lodging: CityPlaces.Lodging, full: Boolean): List<Item> {
        val items = ArrayList<Item>()
        val loaded = Furnish.loaded(level, lodging)
        if (lodging.kind == Kind.BAKERY.name) {
            lodging.stations.firstOrNull { it.m == M.FURNACE }?.let { items.add(if (loaded) oven(level, capital, lodging, it.pos) else unloaded(lodging, Part.BAKE_OVEN, it.pos)) }
            boardCell(lodging)?.let { (pos, facing) -> items.add(if (loaded) board(level, capital, lodging, pos, facing) else unloaded(lodging, Part.MENU_BOARD, pos)) }
            return items
        }
        // The hearth is the campfire on the ground floor (the one on the chimney's top is only for the smoke)
        lodging.stations.firstOrNull { it.m == M.CAMPFIRE && it.pos.y == lodging.y }?.let {
            items.add(if (loaded) house(level, capital, lodging, it.pos) else unloaded(lodging, Part.HOUSE_POT, it.pos))
        }
        val smoker = lodging.stations.firstOrNull { it.m == M.SMOKER }?.pos
        if (smoker != null) items.add(if (loaded) guest(level, capital, lodging, smoker) else unloaded(lodging, Part.GUEST_POT, smoker))
        boardCell(lodging)?.let { (pos, facing) -> items.add(if (loaded) board(level, capital, lodging, pos, facing) else unloaded(lodging, Part.MENU_BOARD, pos)) }
        if (smoker != null) {
            spitCell(lodging, smoker)?.let { (pos, facing) ->
                items.add(
                    when {
                        !full -> Item(lodging, Part.SPIT, pos, Finding.LATER, "only `full` tries the spit")
                        loaded -> spit(level, capital, lodging, pos, facing)
                        else -> unloaded(lodging, Part.SPIT, pos)
                    },
                )
            }
        }
        return items
    }

    private fun unloaded(lodging: CityPlaces.Lodging, part: Part, pos: BlockPos) = Item(lodging, part, pos, Finding.WAITING, "its land is not loaded")

    private fun item(lodging: CityPlaces.Lodging, part: Part, pos: BlockPos, finding: Finding, note: String) = Item(lodging, part, pos, finding, note)

    /** The innkeeper's pot, in the hearth's campfire. */
    private fun house(level: ServerLevel, capital: CapitalPiece, lodging: CityPlaces.Lodging, pos: BlockPos): Item {
        val state = level.getBlockState(pos)
        return when {
            state.block is StewpotBlock -> item(lodging, Part.HOUSE_POT, pos, Finding.DONE, "a pot already hangs over the hearth")
            !state.`is`(Blocks.CAMPFIRE) -> item(lodging, Part.HOUSE_POT, pos, Finding.CHANGED, "the hearth is not the plan's campfire any more (it holds ${name(state)})")
            (level.getBlockEntity(pos) as? CampfireBlockEntity)?.items?.any { !it.isEmpty } == true ->
                item(lodging, Part.HOUSE_POT, pos, Finding.CHANGED, "there is food on the hearth's campfire")
            !level.getBlockState(pos.above()).isAir -> item(lodging, Part.HOUSE_POT, pos, Finding.NO_ROOM, "something hangs over the hearth, where the crane swings")
            busy(level, pos) -> item(lodging, Part.HOUSE_POT, pos, Finding.WAITING, "someone is standing in the hearth")
            else -> item(lodging, Part.HOUSE_POT, pos, Finding.READY, "the hearth's campfire becomes the innkeeper's pot").also {
                it.state = drawn(capital, M.STEWPOT, null, pos, 0)
                it.mode = StewpotMode.HOUSE
            }
        }
    }

    /** The guests' pot, in the back corner's smoker, on a hearthstone. */
    private fun guest(level: ServerLevel, capital: CapitalPiece, lodging: CityPlaces.Lodging, pos: BlockPos): Item {
        val state = level.getBlockState(pos)
        return when {
            state.block is StewpotBlock -> item(lodging, Part.GUEST_POT, pos, Finding.DONE, "a pot already stands in the back corner")
            !state.`is`(Blocks.SMOKER) -> item(lodging, Part.GUEST_POT, pos, Finding.CHANGED, "the back corner is not the plan's smoker any more (it holds ${name(state)})")
            (level.getBlockEntity(pos) as? AbstractFurnaceBlockEntity)?.let { !it.isEmpty } == true ->
                item(lodging, Part.GUEST_POT, pos, Finding.CHANGED, "the smoker has something in it")
            !level.getBlockState(pos.above()).isAir -> item(lodging, Part.GUEST_POT, pos, Finding.NO_ROOM, "something stands over the back corner")
            busy(level, pos) -> item(lodging, Part.GUEST_POT, pos, Finding.WAITING, "someone is standing in the back corner")
            else -> item(lodging, Part.GUEST_POT, pos, Finding.READY, "the back corner's smoker becomes the guests' pot").also {
                it.state = drawn(capital, M.STEWPOT, null, pos, 1)
                it.mode = StewpotMode.GUEST
                // On a hearthstone let into the floor, if the floor under it is still the plan's planks
                val below = pos.below()
                if (level.getBlockState(below) == WorldCanvas.planned(capital, B(M.PLANKS, v = 1), below)) it.floor = below to WorldCanvas.planned(capital, B(M.STONE), below)
            }
        }
    }

    /** The slate by the door, on solid ground, with a clear cell in front of it. */
    private fun board(level: ServerLevel, capital: CapitalPiece, lodging: CityPlaces.Lodging, pos: BlockPos, facing: Direction): Item {
        val state = level.getBlockState(pos)
        return when {
            state.block is MenuBoardBlock -> item(lodging, Part.MENU_BOARD, pos, Finding.DONE, "a slate already stands by the door")
            !roomy(state) -> item(lodging, Part.MENU_BOARD, pos, Finding.NO_ROOM, "${name(state)} stands where the slate would")
            !sturdy(level, pos.below()) -> item(lodging, Part.MENU_BOARD, pos, Finding.NO_ROOM, "there is no solid ground under the cell by the door")
            !clear(level, pos.relative(facing)) -> item(lodging, Part.MENU_BOARD, pos, Finding.NO_ROOM, "the cell in front of it is not clear, and the slate would shut the way through")
            busy(level, pos) -> item(lodging, Part.MENU_BOARD, pos, Finding.WAITING, "someone is standing by the door")
            else -> item(lodging, Part.MENU_BOARD, pos, Finding.READY, "a slate on a post goes by the door").also {
                it.state = drawn(capital, M.MENU_BOARD, facing, pos)
            }
        }
    }

    /** The Spit, in the clear cell beside the back corner. */
    private fun spit(level: ServerLevel, capital: CapitalPiece, lodging: CityPlaces.Lodging, pos: BlockPos, facing: Direction): Item {
        val state = level.getBlockState(pos)
        return when {
            state.block is SpitBlock -> item(lodging, Part.SPIT, pos, Finding.DONE, "a spit already stands by the back corner")
            !state.isAir -> item(lodging, Part.SPIT, pos, Finding.NO_ROOM, "${name(state)} stands where the spit would")
            !level.getBlockState(pos.above()).isAir -> item(lodging, Part.SPIT, pos, Finding.NO_ROOM, "something stands over the cell for the spit")
            !sturdy(level, pos.below()) -> item(lodging, Part.SPIT, pos, Finding.NO_ROOM, "there is no solid floor under the cell for the spit")
            !clear(level, pos.relative(facing)) -> item(lodging, Part.SPIT, pos, Finding.NO_ROOM, "there is no clear cell in front of it for the cook to stand")
            busy(level, pos) -> item(lodging, Part.SPIT, pos, Finding.WAITING, "someone is standing by the back corner")
            else -> item(lodging, Part.SPIT, pos, Finding.READY, "a spit goes in the clear cell beside the back corner").also {
                it.state = drawn(capital, M.SPIT, facing, pos)
            }
        }
    }

    /** The bakery's great oven, in the furnace's mouth. */
    private fun oven(level: ServerLevel, capital: CapitalPiece, lodging: CityPlaces.Lodging, pos: BlockPos): Item {
        val state = level.getBlockState(pos)
        return when {
            state.block is OvenBlock -> item(lodging, Part.BAKE_OVEN, pos, Finding.DONE, "a bake oven already stands in the great oven")
            !state.`is`(Blocks.FURNACE) -> item(lodging, Part.BAKE_OVEN, pos, Finding.CHANGED, "the oven's mouth is not the plan's furnace any more (it holds ${name(state)})")
            (level.getBlockEntity(pos) as? AbstractFurnaceBlockEntity)?.let { !it.isEmpty } == true ->
                item(lodging, Part.BAKE_OVEN, pos, Finding.CHANGED, "the furnace has something in it")
            busy(level, pos) -> item(lodging, Part.BAKE_OVEN, pos, Finding.WAITING, "someone is standing in the oven's mouth")
            else -> item(lodging, Part.BAKE_OVEN, pos, Finding.READY, "the furnace in the great oven becomes a bake oven").also {
                it.state = drawn(capital, M.BAKE_OVEN, state.getValue(BlockStateProperties.HORIZONTAL_FACING), pos)
            }
        }
    }

    // ---- Where things go ----

    /**
     * The cell for a slate by the front door: one to the left of the door as the street sees it, outside the front wall, with the
     * slate facing out into the street. The door is a pair of cells; the slate's cell is beyond the one on the left.
     */
    private fun boardCell(lodging: CityPlaces.Lodging): Pair<BlockPos, Direction>? {
        val front = lodging.front ?: return null
        val inward = front.inward
        val left = inward.counterClockWise
        val pair = lodging.doors.filter { it.pos.y == front.pos.y && it.inward == inward && it.pos.distManhattan(front.pos) <= 1 }
        val end = pair.maxByOrNull { it.pos.x * left.stepX + it.pos.z * left.stepZ } ?: front
        return end.pos.relative(left).relative(inward.opposite) to inward.opposite
    }

    /** The cell for the spit: beside the back corner's smoker, along the wall toward the barrels, facing the front like the smoker. */
    private fun spitCell(lodging: CityPlaces.Lodging, smoker: BlockPos): Pair<BlockPos, Direction>? {
        val front = lodging.front ?: return null
        val toFront = front.inward.opposite
        return smoker.relative(toFront.counterClockWise) to toFront
    }

    /** The block the plan draws for [m] facing [facing] (a direction in the world) at [pos]: what a new capital's plan puts there. */
    private fun drawn(capital: CapitalPiece, m: M, facing: Direction?, pos: BlockPos, v: Int = 0): BlockState {
        val dir = facing?.let { f -> Dir.entries.firstOrNull { capital.direction(it) == f } }
        return WorldCanvas.planned(capital, B(m, dir, v = v), pos)
    }

    // ---- Making ----

    /**
     * Makes a [Finding.READY] item: the block, its hearthstone and pot mode, and (when [show]) the puff and the sound of it being
     * set down.
     */
    private fun make(level: ServerLevel, item: Item, report: FitReport, show: Boolean) {
        val state = item.state ?: return
        level.setBlock(item.pos, state, Block.UPDATE_ALL)
        item.floor?.let { (pos, floor) -> level.setBlock(pos, floor, Block.UPDATE_ALL) }
        item.mode?.let { mode -> (level.getBlockEntity(item.pos) as? StewpotBlockEntity)?.mode = mode }
        if (show) {
            level.sendParticles(ParticleTypes.POOF, item.pos.x + 0.5, item.pos.y + 0.6, item.pos.z + 0.5, 8, 0.3, 0.3, 0.3, 0.01)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, item.pos.x + 0.5, item.pos.y + 0.9, item.pos.z + 0.5, 5, 0.3, 0.3, 0.3, 0.0)
            level.playSound(null, item.pos, state.soundType.placeSound, SoundSource.BLOCKS, 1.0f, 0.9f)
        }
        item.finding = Finding.MADE
        report.made++
        report.placed.add(item.pos)
        placements++
    }

    /**
     * Notes how [lodging] came out once nothing of it is left to wait for: SKIPPED if a part had been changed, NO_ROOM if one had no
     * room, else FITTED. True if it was noted; false if a part is still ready or waiting (the next look goes on from there).
     */
    private fun settle(data: KitchenFitData, key: Long, lodging: CityPlaces.Lodging, items: List<Item>): Boolean {
        if (items.any { it.finding == Finding.READY || it.finding == Finding.WAITING }) return false
        val state = when {
            items.any { it.finding == Finding.CHANGED } -> FitState.SKIPPED
            items.any { it.finding == Finding.NO_ROOM } -> FitState.NO_ROOM
            else -> FitState.FITTED
        }
        if (!data.has(key, lodging.id)) settled++
        data.set(key, lodging.id, state)
        return true
    }

    // ---- Small questions of the world ----

    /** Whether anyone is standing in the cell at [pos]. */
    private fun busy(level: ServerLevel, pos: BlockPos): Boolean = level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos)).isNotEmpty()

    /** Whether the block at [pos] has a solid top to stand a block on. */
    private fun sturdy(level: ServerLevel, pos: BlockPos): Boolean = level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)

    /** Whether the cell at [pos] and the one above it can be walked through. */
    private fun clear(level: ServerLevel, pos: BlockPos): Boolean =
        level.getBlockState(pos).getCollisionShape(level, pos).isEmpty && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty

    /** Whether [state] is air or a plant or the like, which a block may be set in the place of. */
    private fun roomy(state: BlockState): Boolean = state.isAir || state.canBeReplaced() && state.fluidState.isEmpty && !state.hasBlockEntity()

    /** A block's name for a line of text. */
    private fun name(state: BlockState): String = state.block.name.string
}
