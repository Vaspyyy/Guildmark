package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.client.works.WorksArt
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.build.BuildRec
import io.github.vaspyyy.guildmark.economy.works.build.CountingHouse
import io.github.vaspyyy.guildmark.economy.works.build.MasterBuilder
import io.github.vaspyyy.guildmark.economy.works.build.Pin
import io.github.vaspyyy.guildmark.economy.works.build.Store
import io.github.vaspyyy.guildmark.economy.works.build.TownWorksSite
import io.github.vaspyyy.guildmark.economy.works.build.WorksHoistBlockEntity
import io.github.vaspyyy.guildmark.economy.works.build.WorksYard
import io.github.vaspyyy.guildmark.lamps.StreetLampBlock
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Play
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import kotlin.math.ceil

/**
 * The Carriers' Store's life, watched (living-world/trader/design.md, Part 5; build part F2), from the works' second (WorksAtWork.second
 * calls [second] for a Store that stands, and [firstUse] until its first use has been seen). The clerk does it all where a player is
 * near to see, and the world is set straight without them where nobody is (or the clerk is away): what is shown is read from the
 * blocks themselves each second, so a scene broken off, a reload or a missed hour never leaves a door, a lamp or a board wrong for long.
 *
 * - **The first use.** The clerk takes the key from the master builder (RECEIVE, if he is still about), unlocks the door (BOLT_DOOR and
 *   `trade.store_key`), the doors swing open and the board by them turns to OPEN; the hoist lifts the first crate in ("Mind the swing!",
 *   `trade.hoist_creak`), and the clerk hauls the owner's pennant up the pole beside the name board.
 * - **Each day.** The doors stand open in work hours ([OPEN_FROM] to [OPEN_TO]) and are shut and bolted at dusk, the door board turning
 *   with them; the lamps on the posts at the way in are lit at dusk and put out at dawn (LIGHT_LAMP; street lamps only: with street
 *   lamps off the lanterns are the plain ones, always lit); now and then the clerk weighs a sack on the scale (WEIGH: the beam tips and
 *   settles as the weights go on, `trade.scale_settle`) and writes it in the book (SCRIBE); and whenever goods are bonded or taken back
 *   the hoist lifts a crate in or lets one down ([hoist]), the loft's doors opening for it.
 *
 * A Counting House's day is [HouseLife]'s, on the same helpers (its round of the door, the gate and the lamps is [round]'s).
 *
 * Nothing here is saved: the scenes are the moment's, and what they leave is in the blocks.
 */
internal object StoreLife {
    /** The hours the doors stand open; the lamps are lit from [LIT_FROM] to [LIT_TO]. */
    const val OPEN_FROM = 7.0
    const val OPEN_TO = 17.5
    const val LIT_FROM = 17.5
    const val LIT_TO = 6.5

    /** How near the Store the clerk must be to be sent on its errands, and how near a player for a scene to be played at all. */
    private const val SEEK = 48.0
    private const val SEEN = 48.0

    /** Ticks a first use waits to be seen (a game day at most; a third of one if the clerk has not come yet) before it is taken as done. */
    const val UNSEEN_FIRST = 24000L

    /** Fewest ticks between two of the clerk's own weighings, and how many more at most. */
    private const val WEIGH_GAP = 2400
    private const val WEIGH_MORE = 3600

    /** Ticks the clerk's pull on the pennant's halyard takes, as the board's renderer runs the pennant up. */
    const val RAISE_TICKS = 60

    /** Sacks and crates as the clerk and the hoist carry them. */
    private val SACK: ItemStack get() = ItemStack(Items.WHEAT)
    private val CRATE: ItemStack get() = ItemStack(Items.BARREL)
    val KEY: ItemStack get() = ItemStack(Items.TRIAL_KEY)

    /** The clerk's errand at each works (one at a time), what the hoist has still to lift, when each clerk next weighs, and until when the loft's doors stand open. */
    private class Errand(val p: Townsperson, val aside: Aside)

    private class Lift(val stack: ItemStack, val down: Boolean)

    private val errands = HashMap<Int, Errand>()

    /** Whether works [id]'s clerk or scribe is about an errand of this. */
    fun busy(id: Int): Boolean = errands[id] != null
    private val lifts = HashMap<Int, ArrayDeque<Lift>>()
    private val weighs = HashMap<Int, Long>()
    private val loftUntil = HashMap<Int, Long>()

    /** Since when each Store's door or lamps have wanted the clerk, who isn't free (game time). */
    private val waiting = HashMap<Int, Long>()

    /** Ticks a door or a lamp waits for the clerk where a player can see it before it is set without them. */
    private const val CLERK_WAIT = 1200L

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> reset() }
    }

    private fun reset() {
        for (e in errands.values) e.aside.end(e.p, drop = false)
        errands.clear()
        HouseLife.reset()
        lifts.clear()
        weighs.clear()
        loftUntil.clear()
        waiting.clear()
    }

    // ---- From the works ----

    /** Each second of a standing Store (or House) whose land is loaded: what it shows, and the clerk's day. */
    fun second(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, hour: Double, near: Boolean) {
        errands[w.id]?.let { if (it.aside.over) errands.remove(w.id) }
        when (w.kind) {
            WorksKind.STORE -> store(level, w, rec, site, hour, near)
            WorksKind.COUNTING_HOUSE -> HouseLife.second(level, w, rec, site, hour, near)
            else -> {}
        }
    }

    /** The first use of a Store just opened, until it has been seen ([done] then): the key, the door, the first crate and the pennant. */
    fun firstUse(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, hour: Double, near: Boolean, done: () -> Unit) {
        if (errands[w.id] != null) return
        val since = level.gameTime - rec.finishedAt
        val clerk = clerk(level, w, site)
        val by = since > UNSEEN_FIRST || w.hired < 0 && since > UNSEEN_FIRST / 3
        when (w.kind) {
            WorksKind.STORE -> {
                val d = site.drawing as? Store ?: return done()
                if (by || !Works.enabled()) {
                    // Nobody saw it: the pennant is up and the doors are as the hour has them, as though it was long since
                    board(level, w, site)?.raise(now = false)
                    done()
                    return
                }
                if (!near || clerk == null || hour < 6.0 || hour >= 19.0) return
                firstStore(level, w, site, d, clerk, done)
            }
            WorksKind.COUNTING_HOUSE -> HouseLife.firstUse(level, w, rec, site, hour, near, by, clerk, done)
            else -> done()
        }
    }

    /** Lifts [stack] into [owner]'s Store at [capital] on its hoist (or, [down], lets it down to the ground), when it next can. */
    fun hoist(level: ServerLevel, capital: Long, owner: java.util.UUID, stack: ItemStack, down: Boolean) {
        val w = WorksData.get(level).find(capital, WorksKind.STORE, owner = owner)?.id ?: return
        val q = lifts.getOrPut(w) { ArrayDeque() }
        if (q.size < 4) q.addLast(Lift(stack.copyWithCount(1), down))
        // What the Store holds has changed: a Counting House of the owner's beside it makes its sums again
        HouseLife.recount(level, capital, owner)
    }

    /** [owner]'s Store at [capital]'s clerk weighs a sack now, if they're free to (goods just bonded). */
    fun weigh(level: ServerLevel, capital: Long, owner: java.util.UUID) {
        val w = WorksData.get(level).find(capital, WorksKind.STORE, owner = owner)?.id ?: return
        weighs[w] = 0L
    }

    // ---- The Store ----

    private fun store(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, hour: Double, near: Boolean) {
        val d = site.drawing as? Store ?: return
        // The name board's name and colour, and the pole its pennant flies from
        val board = board(level, w, site)
        if (board != null) {
            val owner = w.owner
            if (owner != null) board.set(name(level, w, owner), StallDesk.colorOf(level, owner))
            board.pole(at(site, Store.POLE), Store.POLE_TOP + 1, site.frame.direction(Store.PENNANT_FACING))
            if (rec.firstUse && board.raised == 0L) board.raise(now = false)
        }
        if (!rec.firstUse || !Works.enabled()) return
        val now = level.gameTime
        val clerk = clerk(level, w, site)
        // The loft's doors shut again once the hoist is done
        loftUntil[w.id]?.let { if (now >= it) { loft(level, site, false); loftUntil.remove(w.id) } }
        if (errands[w.id] != null) return
        // What the hoist has to lift comes first; then the doors and the lamps; then, now and then, a sack weighed
        val q = lifts[w.id]
        if (q != null && q.isNotEmpty()) {
            lift(level, w, site, q.removeFirst(), clerk?.takeIf { near })
            if (q.isEmpty()) lifts.remove(w.id)
            return
        }
        val open = hour >= OPEN_FROM && hour < OPEN_TO
        val lit = hour >= LIT_FROM || hour < LIT_TO
        if (round(level, w, site, FRONT, open, lit, clerk?.takeIf { near }, near)) return
        if (!open || !near || clerk == null) return
        val due = weighs.getOrPut(w.id) { now + WEIGH_GAP / 2 + level.random.nextInt(WEIGH_MORE) }
        if (now < due) return
        weighs[w.id] = now + WEIGH_GAP + level.random.nextInt(WEIGH_MORE)
        weighScene(level, w, site, clerk)
    }

    /**
     * The way into a Store or a House ([front]): its door, the yard's gates, the lamps by the way in, and the open and shut board (the
     * Store's), each a pin of the drawing.
     */
    class Front(val door: Pin, val gates: List<Pin>, val lamps: List<Pin>, val doorBoard: Pin?)

    private val FRONT = Front(Store.FRONT_DOOR, listOf(), Store.LAMPS, Store.DOOR_BOARD_AT)

    /**
     * A Store's or a House's door, gates, board and lamps ([front]) set to [open] and [lit]: by its clerk or scribe ([clerk], when free and
     * seen), else at once (where a player is [near], only once the clerk has been waited for a while: they may be on their way). True if
     * anything was off.
     */
    fun round(level: ServerLevel, w: WorksRec, site: TownWorksSite, front: Front, open: Boolean, lit: Boolean, clerk: Townsperson?, near: Boolean): Boolean {
        val door = at(site, front.door)
        val doorWrong = level.isLoaded(door) && level.getBlockState(door).let { it.block is DoorBlock && it.getValue(DoorBlock.OPEN) != open }
        val boardWrong = doorBoard(level, site, front)?.let { it.open != open } == true
        val gates = front.gates.filter { gateWrong(level, at(site, it), open) }
        val lamps = front.lamps.map { at(site, it) }.filter { lampWrong(level, it, lit) }
        if (!doorWrong && !boardWrong && gates.isEmpty() && lamps.isEmpty()) {
            waiting.remove(w.id)
            return false
        }
        if (clerk == null) {
            if (near && level.gameTime - waiting.getOrPut(w.id) { level.gameTime } < CLERK_WAIT) return true
            waiting.remove(w.id)
            swing(level, door, open, sound = near)
            doorBoard(level, site, front)?.turn(open, show = true)
            for (g in gates) gate(level, at(site, g), open, sound = near)
            for (pos in lamps) StreetLampBlock.set(level, pos, lit)
            return true
        }
        waiting.remove(w.id)
        val moves = ArrayList<Move>()
        val doorMoves = if (doorWrong || boardWrong) {
            val inside = at(site, Pin(front.door.x, 0, front.door.z + 1))
            val face = centre(site, front.door, 1.0)
            listOf(
                GoTo({ inside }, Jobs.WALK, 0.8, Activity.NONE, 600),
                Turn({ face }),
                Handle(Activity.BOLT_DOOR, face, BOLT_MARK) {
                    swing(level, door, open, sound = true)
                    doorBoard(level, site, front)?.turn(open, show = true)
                    play(level, door, "trade.store_key", 0.8f)
                },
            )
        } else listOf()
        // The yard's gates (a House's), lifted open from inside the yard or swung to and barred
        val gateMoves = if (gates.isNotEmpty()) {
            val first = gates.first()
            val inside = at(site, Pin(first.x, 0, first.z + 1))
            val face = centre(site, first, 0.6)
            listOf(
                GoTo({ inside }, Jobs.WALK, 0.8, Activity.NONE, 600),
                Turn({ face }),
                Handle(Activity.BOLT_DOOR, face, BOLT_MARK) { for (g in gates) gate(level, at(site, g), open, sound = g === first) },
            )
        } else listOf()
        val lampMoves = lamps.flatMap { pos ->
            val foot = BlockPos(pos.x, site.drawing.y0, pos.z).relative(site.frame.direction(Dir.N))
            val flame = Vec3.atCenterOf(pos)
            listOf(
                GoTo({ foot }, Jobs.WALK, 0.8, Activity.NONE, 600),
                Turn({ flame }),
                Handle(Activity.LIGHT_LAMP, flame, LAMP_MARK) { if (lit) StreetLampBlock.light(level, pos, show = true) else StreetLampBlock.putOut(level, pos, show = true) },
            )
        }
        // In the morning the door first, from inside, then the gates and out to the lamps; at dusk the lamps, the gates, then in and the door bolted
        if (open) {
            moves.addAll(doorMoves)
            moves.addAll(gateMoves)
            moves.addAll(lampMoves)
        } else {
            moves.addAll(lampMoves)
            moves.addAll(gateMoves)
            moves.addAll(doorMoves)
        }
        send(w, clerk, moves, 2400) {
            swing(level, door, open, sound = false)
            doorBoard(level, site, front)?.turn(open, show = true)
            for (g in gates) gate(level, at(site, g), open, sound = false)
            for (pos in lamps) StreetLampBlock.set(level, pos, lit)
        }
        return true
    }

    private fun gateWrong(level: ServerLevel, pos: BlockPos, open: Boolean): Boolean {
        if (!level.isLoaded(pos)) return false
        val state = level.getBlockState(pos)
        return state.block is FenceGateBlock && state.getValue(FenceGateBlock.OPEN) != open
    }

    /** A yard gate's leaf open or shut ([sound]: as a gate sounds when it swings). */
    fun gate(level: ServerLevel, pos: BlockPos, open: Boolean, sound: Boolean) {
        if (!level.isLoaded(pos)) return
        val state = level.getBlockState(pos)
        val block = state.block as? FenceGateBlock ?: return
        if (state.getValue(FenceGateBlock.OPEN) == open) return
        level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, open), Block.UPDATE_CLIENTS or Block.UPDATE_IMMEDIATE)
        if (sound) level.playSound(null, pos, if (open) block.openSound else block.closeSound, SoundSource.BLOCKS, 1f, 0.9f + level.random.nextFloat() * 0.1f)
    }

    private fun lampWrong(level: ServerLevel, pos: BlockPos, lit: Boolean): Boolean {
        if (!level.isLoaded(pos)) return false
        val state = level.getBlockState(pos)
        return StreetLampBlock.isLamp(state) && state.getValue(StreetLampBlock.LIT) != lit
    }

    /** The Store's front door open or shut ([sound]: as the door sounds when it swings). */
    private fun setDoor(level: ServerLevel, site: TownWorksSite, open: Boolean, sound: Boolean) {
        swing(level, at(site, Store.FRONT_DOOR), open, sound)
    }

    /** A Store's or House's door at [pos] open or shut ([sound]: as the door sounds when it swings). */
    fun door(level: ServerLevel, pos: BlockPos, open: Boolean, sound: Boolean) = swing(level, pos, open, sound)

    /** The loft's loading doors open or shut, for the hoist. */
    private fun loft(level: ServerLevel, site: TownWorksSite, open: Boolean) {
        for (leaf in Store.LOFT_DOORS) swing(level, at(site, leaf), open, sound = leaf === Store.LOFT_DOORS[0])
    }

    private fun swing(level: ServerLevel, pos: BlockPos, open: Boolean, sound: Boolean) {
        if (!level.isLoaded(pos)) return
        val state = level.getBlockState(pos)
        val door = state.block as? DoorBlock ?: return
        if (state.getValue(DoorBlock.OPEN) == open) return
        level.setBlock(pos, state.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS or Block.UPDATE_IMMEDIATE)
        if (sound) {
            val event = if (open) door.type().doorOpen() else door.type().doorClose()
            level.playSound(null, pos, event, SoundSource.BLOCKS, 1f, 0.9f + level.random.nextFloat() * 0.1f)
        }
    }

    /** One lift on the hoist (a crate in, or one let down), the clerk hauling the rope ([clerk]) or a hand in the loft unseen. */
    private fun lift(level: ServerLevel, w: WorksRec, site: TownWorksSite, l: Lift, clerk: Townsperson?) {
        val pos = at(site, Store.HOIST_AT)
        if (!level.isLoaded(pos)) return
        val be = level.getBlockEntity(pos) as? WorksHoistBlockEntity ?: return
        val drop = site.dropBelow(pos)
        val travel = 16f * drop + WorksArt.HOOK_TOP - WorksArt.HOOK_REST
        val hauling = ceil(WorksArt.ticksFor(travel)).toInt()
        val whole = hauling + WorksArt.HOIST_LAND.toInt() + ceil(travel / WorksArt.HOIST_LOWER).toInt()
        val start = {
            loft(level, site, true)
            loftUntil[w.id] = level.gameTime + whole + 20
            be.lift(l.stack, drop, hauling, l.down)
            play(level, pos, "trade.hoist_creak", 1f)
        }
        if (clerk == null) {
            start()
            return
        }
        val stand = at(site, Store.HAULER)
        val hook = centre(site, Pin(Store.HOIST_AT.x, Store.HOIST_AT.y - 2, Store.HOIST_AT.z - 1), 0.5)
        send(
            w, clerk,
            listOf(
                GoTo({ stand }, Jobs.WALK, 0.8, Activity.NONE, 600),
                Turn({ hook }),
                Now { start() },
                Play(Activity.HOIST, hauling, at = { hook }),
                Wait({ _, _ -> false }, whole - hauling, Activity.NONE) { hook },
            ),
            whole + 900,
        ) { if (be.ticks == 0) start() }
    }

    /** The clerk weighs a sack: onto the scale's goods pan, the weights on (the beam tipping and settling), written down, and taken off to the stores. */
    private fun weighScene(level: ServerLevel, w: WorksRec, site: TownWorksSite, clerk: Townsperson) {
        val scalePos = at(site, Store.SCALE_AT)
        val scale = { level.getBlockEntity(scalePos) as? StoreScaleBlockEntity }
        if (scale() == null) return
        val stand = at(site, Store.CLERK)
        val pan = centre(site, Store.SCALE_AT, 1.1)
        val desk = centre(site, Store.DESK_AT, 1.0)
        val stores = centre(site, Pin(5, 0, 5), 0.6)
        send(
            w, clerk,
            listOf(
                GoTo({ stand }, Jobs.WALK, 0.6, Activity.NONE, 600),
                Turn({ pan }),
                Now { it.carried = SACK },
                Handle(Activity.WEIGH, pan, SET_ON) {
                    it.carried = ItemStack.EMPTY
                    scale()?.weigh()
                    play(level, scalePos, "trade.scale_settle", 0.9f)
                },
                // The weights go on by the scale's own clock; the last brings the beam level
                Wait({ _, _ -> false }, BALANCED, Activity.NONE) { pan },
                Now { play(level, scalePos, "trade.scale_settle", 0.6f) },
                Play(Activity.SCRIBE, 50, at = { desk }),
                Handle(Activity.PICK_UP, pan, PICKED) {
                    scale()?.clear()
                    it.carried = SACK
                },
                Turn({ stores }),
                Handle(Activity.PUT_DOWN, stores, SET_DOWN) { it.carried = ItemStack.EMPTY },
            ),
            900,
        ) { scale()?.clear() }
    }

    // ---- For the testing command ----

    /** The scenes `trade store scene` can ask a Store's clerk for now (a House's are [HouseLife.SCENES]). */
    val SCENES = listOf("weigh", "hoist", "lower")

    /** [w]'s clerk plays [scene] now (the hoist's lifts go as the hoist next can); a reason it can't, else null. */
    fun scene(level: ServerLevel, w: WorksRec, site: TownWorksSite, scene: String): String? {
        if (site.drawing !is Store) return "That works is no Carriers' Store."
        when (scene) {
            "hoist", "lower" -> {
                val q = lifts.getOrPut(w.id) { ArrayDeque() }
                if (q.size < 4) q.addLast(Lift(if (scene == "hoist") CRATE else SACK, scene == "lower"))
                return null
            }
            "weigh" -> {
                if (errands[w.id] != null) return "The clerk is busy with something else; try again in a moment."
                val clerk = clerk(level, w, site) ?: return "The Store's clerk isn't about (not come yet, or away)."
                weighScene(level, w, site, clerk)
                return null
            }
        }
        return "No such scene at a Store: ${SCENES.joinToString(", ")}."
    }

    // ---- The first use ----

    private fun firstStore(level: ServerLevel, w: WorksRec, site: TownWorksSite, d: Store, clerk: Townsperson, done: () -> Unit) {
        val door = at(site, Store.FRONT_DOOR)
        val outside = at(site, Pin(Store.FRONT_DOOR.x, 0, Store.FRONT_DOOR.z - 1))
        val face = centre(site, Store.FRONT_DOOR, 1.0)
        val stand = at(site, Store.HAULER)
        val hook = centre(site, Pin(Store.HOIST_AT.x, Store.HOIST_AT.y - 2, Store.HOIST_AT.z - 1), 0.5)
        val poleFoot = at(site, Pin(Store.POLE.x - 1, 0, Store.POLE.z - 1))
        val pole = centre(site, Store.POLE, 2.0)
        val hoistPos = at(site, Store.HOIST_AT)
        val hoist = { level.getBlockEntity(hoistPos) as? WorksHoistBlockEntity }
        val drop = site.dropBelow(hoistPos)
        val travel = 16f * drop + WorksArt.HOOK_TOP - WorksArt.HOOK_REST
        val hauling = ceil(WorksArt.ticksFor(travel)).toInt()
        val whole = hauling + WorksArt.HOIST_LAND.toInt() + ceil(travel / WorksArt.HOIST_LOWER).toInt()
        val moves = ArrayList<Move>(keyMoves(level, door))
        moves.addAll(
            listOf(
                Now { it.carried = KEY },
                GoTo({ outside }, Jobs.WALK, 0.6, Activity.NONE, 600),
                Turn({ face }),
                Handle(Activity.BOLT_DOOR, face, BOLT_MARK) {
                    play(level, door, "trade.store_key", 1f)
                },
                Now {
                    it.carried = ItemStack.EMPTY
                    setDoor(level, site, true, sound = true)
                    doorBoard(level, site, FRONT)?.turn(true, show = true)
                },
                // The first crate up into the loft, the clerk stood clear of its swing
                GoTo({ stand }, Jobs.WALK, 0.6, Activity.NONE, 400),
                Turn({ hook }),
                Now {
                    loft(level, site, true)
                    loftUntil[w.id] = level.gameTime + whole + 20
                    hoist()?.lift(CRATE, drop, hauling)
                    play(level, hoistPos, "trade.hoist_creak", 1f)
                    say(it, SWING)
                },
                Play(Activity.HOIST, hauling, at = { hook }),
                Wait({ _, _ -> false }, (whole - hauling).coerceAtLeast(20), Activity.NONE) { hook },
                // And the owner's pennant run up the pole
                GoTo({ poleFoot }, Jobs.WALK, 0.8, Activity.NONE, 400),
                Turn({ pole }),
                Play(Activity.HOIST, RAISE_TICKS, at = { pole }) { board(level, w, site)?.raise(now = true) },
                Wait({ _, _ -> false }, 30, Activity.NONE) { pole.add(0.0, Store.POLE_TOP - 1.0, 0.0) },
                Now { p -> level.getNearestPlayer(p, 12.0)?.let { pl -> p.lookAt(pl.eyePosition) }; say(p, HELLO[level.random.nextInt(HELLO.size)]) },
                Wait({ _, _ -> false }, 40, Activity.NONE),
            ),
        )
        send(w, clerk, moves, 3600) {
            it.carried = ItemStack.EMPTY
            setDoor(level, site, true, sound = false)
            doorBoard(level, site, FRONT)?.turn(true, show = false)
            board(level, w, site)?.let { b -> if (b.raised == 0L) b.raise(now = false) }
            done()
        }
    }

    // ---- Helpers ----

    /** The new keeper's first moves at a works just finished by [door]: the key from the master builder, if he is still about; else from their own pocket. */
    fun keyMoves(level: ServerLevel, door: BlockPos): List<Move> {
        val master = level.getEntitiesOfClass(Townsperson::class.java, AABB(door).inflate(24.0, 8.0, 24.0)) {
            it.isAlive && MasterBuilder.TAG in it.entityTags()
        }.minByOrNull { it.distanceToSqr(Vec3.atCenterOf(door)) }
        if (master == null) return listOf(Now { it.carried = KEY })
        return listOf(
            GoTo({ master.blockPosition() }, Jobs.WALK * 1.15, 1.8, Activity.NONE, 400),
            Turn({ master.eyePosition }),
            Handle(Activity.RECEIVE, master.eyePosition, TAKEN) { it.carried = KEY },
        )
    }

    /** Gives [clerk] the part [moves] (at most [most] ticks); [after] runs as it ends, however it ends (set the world straight there). */
    fun send(w: WorksRec, clerk: Townsperson, moves: List<Move>, most: Int, after: (Townsperson) -> Unit) {
        val aside = Aside(moves, reach = SEEN + 16.0, most = most)
        aside.onEnd = {
            clerk.carried = ItemStack.EMPTY
            after(clerk)
        }
        WorksYard.send(clerk, aside)
        errands[w.id] = Errand(clerk, aside)
    }

    /** [w]'s clerk (or a House's scribe), if they are about the works and free to be sent. */
    fun clerk(level: ServerLevel, w: WorksRec, site: TownWorksSite): Townsperson? {
        if (w.hired < 0) return null
        val uuid = Residents.get(level)[w.hired]?.uuid ?: return null
        val p = level.getEntity(uuid) as? Townsperson ?: return null
        val heart = Vec3.atCenterOf(at(site, site.drawing.heart))
        return p.takeIf { it.distanceToSqr(heart) < SEEK * SEEK && WorksYard.sendable(level, it) }
    }

    /** [owner]'s name as [w]'s pledges knew them, or as they are on now. */
    fun name(level: ServerLevel, w: WorksRec, owner: java.util.UUID): String =
        level.server.playerList.getPlayer(owner)?.name?.string ?: w.pledges.lastOrNull { it.player == owner }?.name ?: ""

    fun at(site: TownWorksSite, pin: Pin): BlockPos = site.frame.world(pin.x, site.drawing.y0 + pin.y, pin.z)

    fun centre(site: TownWorksSite, pin: Pin, up: Double): Vec3 = site.frame.centre(pin.x.toDouble(), site.drawing.y0 + pin.y + up, pin.z.toDouble())

    fun board(level: ServerLevel, w: WorksRec, site: TownWorksSite): NameBoardBlockEntity? {
        val pin = when (w.kind) {
            WorksKind.STORE -> Store.NAME_BOARD_AT
            WorksKind.COUNTING_HOUSE -> CountingHouse.NAME_BOARD_AT
            else -> return null
        }
        val pos = at(site, pin)
        return if (level.isLoaded(pos)) level.getBlockEntity(pos) as? NameBoardBlockEntity else null
    }

    /** The block entity of the kind [T] at [pin] of [site], if its land is loaded. */
    inline fun <reified T> fitting(level: ServerLevel, site: TownWorksSite, pin: Pin): T? {
        val pos = at(site, pin)
        return if (level.isLoaded(pos)) level.getBlockEntity(pos) as? T else null
    }

    private fun doorBoard(level: ServerLevel, site: TownWorksSite, front: Front): DoorBoardBlockEntity? {
        val pin = front.doorBoard ?: return null
        val pos = at(site, pin)
        return if (level.isLoaded(pos)) level.getBlockEntity(pos) as? DoorBoardBlockEntity else null
    }

    fun play(level: ServerLevel, pos: BlockPos, name: String, volume: Float) {
        level.playSound(null, pos, TradeSounds.get(name), SoundSource.NEUTRAL, volume, 0.95f + level.random.nextFloat() * 0.1f)
    }

    /** [p] says [key] (a trade voice's take, or a line with no voice yet) where a player can hear. */
    fun say(p: Townsperson, key: String) {
        p.say(key, force = true, ticks = (io.github.vaspyyy.guildmark.townsfolk.talk.Talk.ticks(p, key) + 20).toLong())
    }

    /** The clerk's call as the first crate goes up (no voice of its own yet: a line said in the bubble only), and their greetings. */
    private const val SWING = "trade_clerk_swing"
    private val HELLO = listOf("trade_clerk_hello_0", "trade_clerk_hello_1", "trade_clerk_hello_2")

    /** The ticks into each clip where its moment falls: the key turned, the lamp caught, the sack set on and taken off the pan, set down, the key taken. */
    const val BOLT_MARK = 24
    private const val LAMP_MARK = 28
    private const val SET_ON = 18
    private const val PICKED = 8
    private const val SET_DOWN = 10
    const val TAKEN = 14

    /** Ticks from the clip's end to the scale's last weight going on (the scale's clock: StoreScaleRenderer). */
    private const val BALANCED = 32

    /** [activity] played facing [at], with [what] done on its tick [mark] (a key turned, a sack set down). */
    class Handle(private val activity: Activity, private val at: Vec3, private val mark: Int, private val what: (Townsperson) -> Unit) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(activity)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            p.lookAt(at)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(at), Aside.TURN))
            if (t == mark) what(p)
            return t < activity.ticks
        }
    }
}
