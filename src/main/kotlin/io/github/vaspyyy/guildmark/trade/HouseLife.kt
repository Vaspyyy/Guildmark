package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.build.BuildRec
import io.github.vaspyyy.guildmark.economy.works.build.CountingHouse
import io.github.vaspyyy.guildmark.economy.works.build.Pin
import io.github.vaspyyy.guildmark.economy.works.build.TownWorksSite
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Jobs
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
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.floor

/**
 * The Counting House's day, watched (living-world/trader/design.md, Part 5; build part F2: the building and its fittings alive; what the
 * House does, its carters and its purse, is part G's). StoreLife hands each second of a standing House here ([second]), and its first use
 * until that has been seen ([firstUse]). As at the Store, the scribe does it all where a player is near to see, and the world is set
 * straight without them where nobody is: what is shown is read from the blocks each second.
 *
 * - **The first use.** The scribe takes the key from the master builder, unlocks the door (`trade.store_key`) and swings it open, lifts the
 *   yard's gate, and rings the bell for the first time (`trade.house_bell`); as it rings, the brass pins of every town the owner knows pop
 *   onto the map one after another, the threads running out to the towns where they trade. Then the first sums are made on the counting
 *   frame and the scribe says the books balance.
 * - **Each day.** The door and the gate stand open in work hours and are shut at dusk, the lamps by the gate lit and put out (the Store's
 *   round, [StoreLife.round]); the morning's first job is the bell, rung to say the House is open; now and then the scribe makes the day's
 *   sums: the counting frame shows what the owner's Store beside it holds is worth at this town's prices today ([worth]), so when goods are
 *   bonded or taken back, or the town's prices move, the scribe pushes the beads to the new sum a row at a time (`trade.beads`) and writes
 *   it in the ledger; and at the day's end, before the door is bolted, the scribe lifts the strongbox's lid, counts and lets it fall
 *   (`trade.strongbox`). The map's pins follow what the owner knows, looked over every [MAP_EVERY] ticks: a town new to their Tally pops
 *   in, one dropped from it shrinks away.
 *
 * Nothing here is saved: the scenes are the moment's, and what they leave is in the blocks.
 */
internal object HouseLife {
    private val FRONT = StoreLife.Front(CountingHouse.FRONT_DOOR, CountingHouse.GATES, CountingHouse.LAMPS, null)

    /** When each House's scribe next looks to the day's sums (game time); the day each rang its bell and reckoned its strongbox; when each map was last looked over. */
    private val sums = HashMap<Int, Long>()
    private val rang = HashMap<Int, Long>()
    private val reckoned = HashMap<Int, Long>()
    private val mapped = HashMap<Int, Long>()

    /** Fewest ticks between two of the scribe's looks at the sums, and how many more at most; ticks after goods are bonded before a look. */
    private const val SUMS_GAP = 2400
    private const val SUMS_MORE = 3600
    private const val RECOUNT = 200L

    /** Ticks between two looks over a map's pins. */
    const val MAP_EVERY = 200L

    /** How long before the doors shut (hours) the scribe reckons the strongbox, and until when in the morning (hours after opening) the bell is rung. */
    private const val RECKON_BEFORE = 0.6
    private const val BELL_UNTIL = 2.0

    /** The ticks into the clips where their moments fall: the rope pulled down, the lid lifted and let fall, the beads pushed. */
    private const val PULL = 10
    private const val LIFT = 8
    private const val DROP = 8
    private const val PUSH = 10

    /** The scribe's words when the sums are made: "The books balance." / "Every Mark accounted for." */
    private val BOOKS = listOf("trade_scribe_books_0", "trade_scribe_books_1")

    fun reset() {
        sums.clear()
        rang.clear()
        reckoned.clear()
        mapped.clear()
    }

    /** What [owner]'s Store in [capital] holds has changed: their Counting House beside it (if any) looks to its sums soon. */
    fun recount(level: ServerLevel, capital: Long, owner: UUID) {
        val w = WorksData.get(level).find(capital, WorksKind.COUNTING_HOUSE, owner = owner)?.id ?: return
        sums[w] = level.gameTime + RECOUNT
    }

    // ---- From the works ----

    /** Each second of a standing House whose land is loaded. */
    fun second(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, hour: Double, near: Boolean) {
        if (site.drawing !is CountingHouse) return
        val owner = w.owner
        // The name board's name and colour (a House flies no pennant)
        val board = StoreLife.board(level, w, site)
        if (board != null && owner != null) board.set(StoreLife.name(level, w, owner), StallDesk.colorOf(level, owner))
        if (!rec.firstUse) return
        // The House's own: its carters in the yard and its wagons on the map (part G)
        YardShow.second(level, w, site, near)
        val now = level.gameTime
        if (owner != null && now - (mapped[w.id] ?: Long.MIN_VALUE / 2) >= MAP_EVERY) {
            mapped[w.id] = now
            map(level, w, site, owner, show = near)
        }
        if (!Works.enabled() || StoreLife.busy(w.id)) return
        val scribe = StoreLife.clerk(level, w, site)?.takeIf { near }
        val open = hour >= StoreLife.OPEN_FROM && hour < StoreLife.OPEN_TO
        val lit = hour >= StoreLife.LIT_FROM || hour < StoreLife.LIT_TO
        if (StoreLife.round(level, w, site, FRONT, open, lit, scribe, near)) return
        val today = Suppliers.today(level)
        // The morning's bell: the House is open
        if (open && hour < StoreLife.OPEN_FROM + BELL_UNTIL && rang[w.id] != today) {
            rang[w.id] = today
            if (scribe != null) return bellScene(level, w, site, scribe)
        }
        // The day's end: the strongbox reckoned before the door is bolted
        if (open && hour >= StoreLife.OPEN_TO - RECKON_BEFORE && reckoned[w.id] != today) {
            reckoned[w.id] = today
            if (scribe != null) return strongboxScene(level, w, site, scribe)
        }
        // Now and then the day's sums; where nobody sees, the frame is simply right
        val frame = StoreLife.fitting<CountingFrameBlockEntity>(level, site, CountingHouse.FRAME_AT) ?: return
        if (!near) {
            frame.show(worth(level, w), show = false)
            return
        }
        val due = sums.getOrPut(w.id) { now + SUMS_GAP / 2 + level.random.nextInt(SUMS_MORE) }
        if (now < due || !open || scribe == null) return
        sums[w.id] = now + SUMS_GAP + level.random.nextInt(SUMS_MORE)
        sumsScene(level, w, site, scribe, frame.shown != worth(level, w))
    }

    /**
     * The first use of a House just opened, until it has been seen ([done] then): the key, the door, the gate, the first bell with the
     * map's pins, and the first sums. [by]: it has waited long enough unseen to be taken as done; [scribe]: its scribe, if free and about.
     */
    fun firstUse(
        level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, hour: Double, near: Boolean, by: Boolean, scribe: Townsperson?,
        done: () -> Unit,
    ) {
        if (site.drawing !is CountingHouse) return done()
        if (by || !Works.enabled()) {
            // Nobody saw it: the pins are on the map and the sums made, as though long since
            settle(level, w, site)
            done()
            return
        }
        if (!near || scribe == null || hour < 6.0 || hour >= 19.0) return
        firstHouse(level, w, site, scribe, done)
    }

    /** What a House shows set straight at once, nothing seen moving: its map's pins and its frame's sum. */
    private fun settle(level: ServerLevel, w: WorksRec, site: TownWorksSite) {
        w.owner?.let { map(level, w, site, it, show = false) }
        StoreLife.fitting<CountingFrameBlockEntity>(level, site, CountingHouse.FRAME_AT)?.show(worth(level, w), show = false)
    }

    // ---- What the House shows ----

    /**
     * The capitals [owner] knows, for the map: the House's own (pin 3), the towns where they keep a stall or a Store (pin 2), and the towns
     * in their Tally, one they stood in (pin 1) or only had word of (pin 0). Read without making any record.
     */
    fun known(level: ServerLevel, owner: UUID, home: Long): Map<Long, Int> {
        val out = LinkedHashMap<Long, Int>()
        out[home] = 3
        for (hall in OwnStalls.get(level).all()) if (hall.of(owner) >= 0 || hall.pitches.containsKey(owner)) out.putIfAbsent(hall.capital, 2)
        peekBooks(level)?.get(owner)?.stores?.forEach { out.putIfAbsent(it, 2) }
        for (seen in Tally.seen(level, owner)) out.putIfAbsent(seen.capital, if (seen.from == Seen.VISIT) 1 else 0)
        return if (out.size <= HouseMapBlockEntity.PINS) out else out.entries.take(HouseMapBlockEntity.PINS).associate { it.key to it.value }
    }

    /** The map's pins and colour brought up to what [owner] knows (new pins popping in if [show], else there as though long since). */
    private fun map(level: ServerLevel, w: WorksRec, site: TownWorksSite, owner: UUID, show: Boolean) {
        val map = StoreLife.fitting<HouseMapBlockEntity>(level, site, CountingHouse.MAP_AT) ?: return
        map.colour(Houses.colourOf(level, owner) ?: StallDesk.colorOf(level, owner))
        map.pin(known(level, owner, w.capital), show)
    }

    /**
     * The sum the counting frame shows: what the owner's Store in the House's town holds, good by good at that town's market price
     * today, in whole Marks (its units, where there is no market to price them). Once the House keeps books (part G) it is the House's purse.
     */
    fun worth(level: ServerLevel, w: WorksRec): Long {
        val owner = w.owner ?: return 0L
        Houses.purseOf(level, owner, w.capital)?.let { return it.toLong().coerceIn(0L, CountingFrameBlockEntity.MOST) }
        val store = TradeStores.peek(level)?.find(w.capital, owner) ?: return 0L
        val entry = if (Markets.enabled()) Markets.get(level)[BlockPos.of(w.capital)] else null
        var sum = 0.0
        for (g in Good.entries) {
            val n = store.of(g)
            if (n <= 0) continue
            sum += n * (entry?.market?.price(entry.town, g) ?: 1.0)
        }
        return floor(sum).toLong().coerceIn(0L, CountingFrameBlockEntity.MOST)
    }

    // ---- The scenes ----

    /** The morning's bell: out to the post, a pull on the rope, and the scribe watches it ring. */
    internal fun bellScene(level: ServerLevel, w: WorksRec, site: TownWorksSite, scribe: Townsperson, then: (() -> Unit)? = null) {
        val stand = StoreLife.at(site, CountingHouse.RINGER)
        val rope = StoreLife.centre(site, CountingHouse.BELL_AT, ROPE_UP)
        val bell = StoreLife.centre(site, CountingHouse.BELL_AT, 0.6)
        StoreLife.send(
            w, scribe,
            listOf(
                GoTo({ stand }, Jobs.WALK, 0.6, Activity.NONE, 600),
                Turn({ rope }),
                StoreLife.Handle(Activity.HOIST, rope, PULL) { ring(level, site) },
                Wait({ _, _ -> false }, RING_WATCH, Activity.NONE) { bell },
            ),
            1200,
        ) { then?.invoke() }
    }

    /** The bell's rope pulled: it swings and rings, if it is not ringing already. */
    private fun ring(level: ServerLevel, site: TownWorksSite): Boolean =
        StoreLife.fitting<HouseBellBlockEntity>(level, site, CountingHouse.BELL_AT)?.ring() == true

    /** The day's end: the strongbox's lid lifted, what is in it counted, the lid let fall, and the count written in the ledger. */
    private fun strongboxScene(level: ServerLevel, w: WorksRec, site: TownWorksSite, scribe: Townsperson) {
        val stand = StoreLife.at(site, CountingHouse.COFFER_STAND)
        val box = StoreLife.centre(site, CountingHouse.STRONGBOX_AT, 0.4)
        val ledger = StoreLife.centre(site, CountingHouse.LEDGER_AT, 0.4)
        val strongbox = { StoreLife.fitting<StrongboxBlockEntity>(level, site, CountingHouse.STRONGBOX_AT) }
        StoreLife.send(
            w, scribe,
            listOf(
                GoTo({ stand }, Jobs.WALK, 0.6, Activity.NONE, 600),
                Turn({ box }),
                // In time with `trade.strongbox`: the lid up, the Marks counted over, the lid down
                StoreLife.Handle(Activity.PICK_UP, box, LIFT) { strongbox()?.lift() },
                Play(Activity.COUNT, LOOK_IN, at = { box }),
                StoreLife.Handle(Activity.PUT_DOWN, box, DROP) { strongbox()?.shut() },
                Turn({ ledger }),
                Play(Activity.SCRIBE, 50, at = { ledger }),
            ),
            900,
        ) { strongbox()?.shut() }
    }

    /**
     * The day's sums: the scribe at the high desk, the beads pushed to the sum now ([changed]: else nothing to move, and they only write),
     * the sum written in the ledger, and a word of it if a player is by to hear.
     */
    internal fun sumsScene(level: ServerLevel, w: WorksRec, site: TownWorksSite, scribe: Townsperson, changed: Boolean, then: (() -> Unit)? = null) {
        val stand = StoreLife.at(site, CountingHouse.SCRIBE)
        val ledger = StoreLife.centre(site, CountingHouse.LEDGER_AT, 0.4)
        val moves = ArrayList<Move>()
        moves.add(GoTo({ stand }, Jobs.WALK, 0.6, Activity.NONE, 600))
        if (changed) {
            val frameAt = StoreLife.centre(site, CountingHouse.FRAME_AT, 0.55)
            val framePos = StoreLife.at(site, CountingHouse.FRAME_AT)
            moves.add(Turn({ frameAt }))
            moves.add(Beads(frameAt, { StoreLife.play(level, framePos, "trade.beads", 0.7f) }) {
                StoreLife.fitting<CountingFrameBlockEntity>(level, site, CountingHouse.FRAME_AT)?.show(worth(level, w), show = true) ?: listOf()
            })
        }
        moves.add(Turn({ ledger }))
        moves.add(Play(Activity.SCRIBE, 60, at = { ledger }))
        if (changed) moves.add(Now { p -> if (level.getNearestPlayer(p, HEAR) != null) StoreLife.say(p, BOOKS[level.random.nextInt(BOOKS.size)]) })
        StoreLife.send(w, scribe, moves, 1200) { then?.invoke() }
    }

    /** The House opened: the key, the door, the gate, the first bell with the pins, and the first sums. */
    private fun firstHouse(level: ServerLevel, w: WorksRec, site: TownWorksSite, scribe: Townsperson, done: () -> Unit) {
        val door = StoreLife.at(site, CountingHouse.FRONT_DOOR)
        val outside = StoreLife.at(site, Pin(CountingHouse.FRONT_DOOR.x, 0, CountingHouse.FRONT_DOOR.z - 1))
        val face = StoreLife.centre(site, CountingHouse.FRONT_DOOR, 1.0)
        val gate = CountingHouse.GATES.first()
        val byGate = StoreLife.at(site, Pin(gate.x, 0, gate.z + 1))
        val gateAt = StoreLife.centre(site, gate, 0.6)
        val ringer = StoreLife.at(site, CountingHouse.RINGER)
        val rope = StoreLife.centre(site, CountingHouse.BELL_AT, ROPE_UP)
        val bell = StoreLife.centre(site, CountingHouse.BELL_AT, 0.6)
        val mapAt = StoreLife.centre(site, CountingHouse.MAP_AT, 1.0)
        val stand = StoreLife.at(site, CountingHouse.SCRIBE)
        val frameAt = StoreLife.centre(site, CountingHouse.FRAME_AT, 0.55)
        val framePos = StoreLife.at(site, CountingHouse.FRAME_AT)
        val gates = { open: Boolean, sound: Boolean -> for (g in CountingHouse.GATES) StoreLife.gate(level, StoreLife.at(site, g), open, sound && g === gate) }
        val moves = ArrayList<Move>(StoreLife.keyMoves(level, door))
        moves.addAll(
            listOf(
                Now { it.carried = StoreLife.KEY },
                GoTo({ outside }, Jobs.WALK, 0.6, Activity.NONE, 600),
                Turn({ face }),
                StoreLife.Handle(Activity.BOLT_DOOR, face, StoreLife.BOLT_MARK) { StoreLife.play(level, door, "trade.store_key", 1f) },
                Now {
                    it.carried = ItemStack.EMPTY
                    StoreLife.door(level, door, open = true, sound = true)
                },
                // The gate lifted open for the wagons, from the yard
                GoTo({ byGate }, Jobs.WALK, 0.6, Activity.NONE, 400),
                Turn({ gateAt }),
                StoreLife.Handle(Activity.BOLT_DOOR, gateAt, StoreLife.BOLT_MARK) { gates(true, true) },
                // The first bell; as it rings the pins go onto the map
                GoTo({ ringer }, Jobs.WALK, 0.6, Activity.NONE, 400),
                Turn({ rope }),
                StoreLife.Handle(Activity.HOIST, rope, PULL) {
                    ring(level, site)
                    w.owner?.let { owner -> map(level, w, site, owner, show = true) }
                },
                Wait({ _, _ -> false }, RING_WATCH, Activity.NONE) { bell },
                // In to the desk, a look at the map, and the first sums
                GoTo({ stand }, Jobs.WALK, 0.6, Activity.NONE, 600),
                Turn({ mapAt }),
                Wait({ _, _ -> false }, 30, Activity.NONE) { mapAt },
                Turn({ frameAt }),
                Beads(frameAt, { StoreLife.play(level, framePos, "trade.beads", 0.7f) }) {
                    StoreLife.fitting<CountingFrameBlockEntity>(level, site, CountingHouse.FRAME_AT)?.show(worth(level, w), show = true) ?: listOf()
                },
                Now { p ->
                    level.getNearestPlayer(p, HEAR)?.let { pl -> p.lookAt(pl.eyePosition) }
                    StoreLife.say(p, BOOKS[0])
                },
                Wait({ _, _ -> false }, 40, Activity.NONE),
            ),
        )
        StoreLife.send(w, scribe, moves, 4200) {
            it.carried = ItemStack.EMPTY
            StoreLife.door(level, door, open = true, sound = false)
            gates(true, false)
            settle(level, w, site)
            done()
        }
    }

    // ---- For the testing command ----

    /** The scenes `trade store scene` can ask a House's scribe for now. */
    val SCENES = listOf("bell", "sums", "strongbox")

    /** [w]'s scribe plays [scene] now; a reason it can't, else null. */
    fun scene(level: ServerLevel, w: WorksRec, site: TownWorksSite, scene: String): String? {
        if (site.drawing !is CountingHouse) return "That works is no Counting House."
        if (StoreLife.busy(w.id)) return "The scribe is busy with something else; try again in a moment."
        val scribe = StoreLife.clerk(level, w, site) ?: return "The House's scribe isn't about (not come yet, or away)."
        when (scene) {
            "bell" -> bellScene(level, w, site, scribe)
            "strongbox" -> strongboxScene(level, w, site, scribe)
            "sums" -> sumsScene(level, w, site, scribe, StoreLife.fitting<CountingFrameBlockEntity>(level, site, CountingHouse.FRAME_AT)?.shown != worth(level, w))
            else -> return "No such scene at a House: ${SCENES.joinToString(", ")}."
        }
        return null
    }

    /** How high over the bell's block the scribe reaches for the rope's tassel (blocks), how long they watch it ring, and how long they look into the strongbox. */
    private const val ROPE_UP = 0.15
    private const val RING_WATCH = 60
    private const val LOOK_IN = 12

    /** How near a player must be for the scribe to tell them the sums. */
    private const val HEAR = 10.0

    /**
     * The scribe at the counting frame (COUNT, facing [at]): on tick [PUSH] [push] sets the frame to the sum and gives the rows that move
     * with the tick each starts, and [click] is heard as each row's push begins; done once the clip is through and the beads are still.
     */
    private class Beads(private val at: Vec3, private val click: () -> Unit, private val push: () -> List<Pair<Int, Int>>) : Move() {
        private var clicks: Set<Int> = setOf()
        private var end = Activity.COUNT.ticks

        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(Activity.COUNT)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            p.lookAt(at)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(at), Aside.TURN))
            if (t == PUSH) {
                val rows = push()
                clicks = rows.map { PUSH + it.second }.toSet()
                end = maxOf(Activity.COUNT.ticks, PUSH + (rows.maxOfOrNull { it.second } ?: 0) + SETTLE)
            }
            if (t in clicks) click()
            if (p.activity != Activity.COUNT && (p.activity.loop || p.actDone)) p.act(Activity.COUNT)
            return t < end
        }

        companion object {
            /** Ticks after the last row's push starts that its beads are still (a full row of nine). */
            const val SETTLE = 14
        }
    }
}
