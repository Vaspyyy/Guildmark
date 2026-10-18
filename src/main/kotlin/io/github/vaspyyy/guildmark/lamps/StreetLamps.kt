package io.github.vaspyyy.guildmark.lamps

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.concurrent.CompletableFuture

/**
 * The capitals' street lamps, lit at dusk and put out at dawn (the Street Lamps setting). For each capital a player is
 * about (within [PROBE] blocks of its ground), its lamps are kept as a [LampTown]: every [PASS] ticks the clock says
 * what each lamp should be just now, and a lamp is touched only when that changes from what it was last set to.
 *
 * The lamps go in rounds ([LampTown.Run]): a lamplighter's round of the streets, the lamps at a farm's gate that its
 * farmer sees to, and lamps out on the roads that keep their own time. At dusk each round begins about half past five
 * (an hour earlier in rain, since rain darkens the sky) and its lamps catch one after another at a lamplighter's
 * pace; while a lamplighter really goes round it (see Lamplighters), its lamps wait for them instead. At dawn, from
 * half past five (half past six in rain), the same rounds put them out. Whatever happens, every lamp is lit by seven ([LIT_BY]),
 * before the dark lets monsters about, and out by half past seven ([OUT_BY]); in a thunderstorm, when monsters are about
 * by day too, every lamp is lit, and after it they go out one by one. A lamp nobody reaches catches (or goes out) by
 * itself, with a little flame (or a wisp of smoke) if a player is near enough to see; lamps whose land isn't loaded are
 * set as soon as it is. An older capital's lanterns on their posts are swapped for street lamps while nobody is within
 * [UNSEEN] blocks. Switched off, every street lamp burns day and night, and nothing is swapped.
 */
object StreetLamps {
    // ---- The clock, in ticks since noon (see [since]) ----

    /** The evening's rounds begin about half past five ([RAIN] earlier in rain); nothing of them before four. */
    const val DUSK = 5500
    const val DUSK_OPEN = 4000

    /** Every lamp lit by seven ([RAIN] earlier in rain: the sky is dark sooner). */
    const val LIT_BY = 7000

    /** The morning's rounds begin about half past five ([RAIN] later in rain), and every lamp is out by half past seven. */
    const val DAWN = 17500
    const val OUT_BY = 19500

    /** How much earlier the evening comes, and later the morning, in rain (an hour: the sky is dark sooner, and light later). */
    const val RAIN = 1000

    /** Over how long the lamps go out one by one after a thunderstorm by day. */
    const val STORM_AFTER = 600

    // ---- The keeping ----

    /** Ticks between passes over the lamps, between looks for the capitals players are about, and how long one is kept after. */
    const val PASS = 20
    private const val SCAN = 100
    private const val KEEP = 1200

    /** How far from a capital's ground a player may be for its lamps to be kept (monsters come about a player that far off). */
    const val PROBE = 128

    /** How near a player must be to see a lamp catch or go out by itself. */
    const val SHOW = 24.0

    /** How near a player may be to an older capital's lantern for it to be swapped for a street lamp. */
    const val UNSEEN = 48.0

    /** Ticks between looks at an older capital's lantern in sight of a player. */
    private const val RETRY = 100L

    /** How long a lamplighter may go without a word before their round is handed back to the clock. */
    const val HELD_TICKS = 60L

    /** What the day is doing, as far as the lamps go (see [stretch]). */
    enum class Stretch { AFTERNOON, DUSK, NIGHT, DAWN, MORNING }

    private val towns = LinkedHashMap<BlockPos, LampTown>()
    private val working = HashMap<BlockPos, CompletableFuture<LampTown?>>()
    private val failed = HashSet<BlockPos>()
    private val known = HashMap<BlockPos, CapitalPiece>()
    private var scanned = Long.MIN_VALUE / 2

    /** The last game time it thundered. */
    private var stormAt = Long.MIN_VALUE / 2

    /** Things that see to a round themselves once the clock has it begin (the lamplighters): given the run, they may take it. */
    fun interface Taker {
        fun take(level: ServerLevel, town: LampTown, run: LampTown.Run, dusk: Boolean, time: Int): Director?
    }

    var taker: Taker? = null

    /**
     * Whether a capital's lamps are rationed tonight (M6 Part D2's rationed lamps: candles short): its lamplighters light
     * every other lamp of their rounds, and the rest catch by themselves a little before seven ([skipped]). Asked as
     * the evening's rounds begin; null (as until Part D2 sets it), nothing is rationed.
     */
    fun interface Rationing {
        fun rationed(level: ServerLevel, capital: CapitalPiece): Boolean
    }

    @JvmStatic
    var rationing: Rationing? = null

    /** Whether [town]'s lamps are rationed tonight, as [rationing] has it (false if it fails). */
    private fun rationed(level: ServerLevel, town: LampTown): Boolean = try {
        rationing?.rationed(level, town.capital) == true
    } catch (e: RuntimeException) {
        false
    }

    /**
     * Whether the lamp at [k] in [run] is one its lamplighter leaves tonight, the lamps being rationed: every other lamp
     * of a lamplighter's round (the second, the fourth and so on). It catches by itself a little before seven.
     */
    fun skipped(town: LampTown, run: LampTown.Run, k: Int): Boolean = town.rationed && run.kind == LampTown.Kind.CITY && k % 2 == 1

    /** Sets the street lamps going: their blocks, their keeping, the lamplighters, and the testing command. */
    fun register(modBus: IEventBus) {
        LampRegistry.BLOCKS.register(modBus)
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> clear() }
        io.github.vaspyyy.guildmark.townsfolk.Lamplighters.init()
        LampCommand.init()
    }

    /** Whether the street lamps are lit and put out at all (the Street Lamps setting). */
    fun enabled(): Boolean = try {
        GuildmarkConfig.STREET_LAMPS.get()
    } catch (e: IllegalStateException) {
        true
    }

    private fun clear() {
        towns.clear()
        working.clear()
        failed.clear()
        known.clear()
        nowhere.clear()
        scanned = Long.MIN_VALUE / 2
        stormAt = Long.MIN_VALUE / 2
    }

    // ---- Time ----

    /** Ticks since noon at the clock's [time]: the lamps' day runs noon to noon, the night in its middle. */
    fun since(time: Long): Int = Math.floorMod(time - 6000L, 24000L).toInt()

    /** Which night (noon to noon) the clock's [time] is in. */
    fun night(time: Long): Long = Math.floorDiv(time - 6000L, 24000L)

    /** The clock time [since] ticks after noon, as "17:30". */
    fun hour(since: Int): String {
        val minutes = (Math.floorMod(since, 24000) * 60 / 1000 + 12 * 60) % (24 * 60)
        return "%02d:%02d".format(minutes / 60, minutes % 60)
    }

    /** When every lamp is lit by, with the evening as [town] began it (or rain now). */
    fun litBy(town: LampTown, raining: Boolean): Int = LIT_BY - if (town.rainyDusk || raining) RAIN else 0

    fun dawnOf(town: LampTown): Int = DAWN + if (town.rainyDawn) RAIN else 0

    fun outBy(town: LampTown): Int = OUT_BY + if (town.rainyDawn) RAIN else 0

    /** The stretch of the lamps' day at [time] (ticks since noon), for [town]'s evening and morning as they began. */
    fun stretch(town: LampTown, time: Int, raining: Boolean): Stretch = when {
        time < DUSK_OPEN -> Stretch.AFTERNOON
        time < litBy(town, raining) -> Stretch.DUSK
        time < dawnOf(town) -> Stretch.NIGHT
        time < outBy(town) -> Stretch.DAWN
        else -> Stretch.MORNING
    }

    /**
     * Whether it's dark enough just now for monsters to be about whatever the clock says of the lamps: a thunderstorm, or
     * rain from six in the evening to half past six in the morning. Lamps are kept lit meanwhile, and no lamp is put out.
     */
    fun dark(level: ServerLevel): Boolean {
        if (level.isThundering) return true
        val time = since(level.overworldClockTime)
        return level.isRaining && time >= LIT_BY - RAIN && time < DAWN + RAIN
    }

    // ---- The capitals players are about ----

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val time = level.gameTime
        if (level.isThundering) stormAt = time
        if (time % PASS != 0L) return
        if (time - scanned >= SCAN) {
            scanned = time
            scan(level, time)
        }
        for (town in towns.values.toList()) pass(level, town, time)
    }

    /** Finds the capitals within [PROBE] blocks of a player and keeps them; lets go of those no player has been near a while. */
    private fun scan(level: ServerLevel, time: Long) {
        for (player in level.players()) {
            val x = player.blockX
            val z = player.blockZ
            for (k in 0..16) {
                val r = if (k == 0) 0 else if (k <= 8) PROBE / 2 else PROBE
                val a = (k % 8) * Math.PI / 4
                val px = x + (Math.cos(a) * r).toInt()
                val pz = z + (Math.sin(a) * r).toInt()
                val capital = capitalNear(level, px, pz) ?: continue
                keep(level, capital, time)
            }
        }
        towns.values.removeIf { time - it.seen > KEEP }
        val gone = working.keys.filter { key -> known[key]?.let { c -> level.players().none { near(c, it.blockX, it.blockZ) } } ?: true }
        for (key in gone) working.remove(key)?.cancel(false)
    }

    private fun near(capital: CapitalPiece, x: Int, z: Int): Boolean {
        val reach = (capital.layout?.tier?.reach ?: 0) + PROBE
        return Math.abs(x - capital.center.x) <= reach && Math.abs(z - capital.center.z) <= reach
    }

    /**
     * The capital whose ground takes in the column [x], [z], looked for only in chunks already loaded (and, the first
     * time, in its start's, which may have to be read for it).
     */
    private fun capitalNear(level: ServerLevel, x: Int, z: Int): CapitalPiece? {
        for (c in known.values) if (c.reaches(x, z)) return c
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return null
        val key = chunk.pos.pack()
        if (key in nowhere) return null
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Capitals.KEY) ?: return null
        if (chunk.getReferencesForStructure(structure).isEmpty()) return null
        val pos = BlockPos(x, 64, z)
        val capital = Furnish.capitalAt(level, pos) ?: Capitals.covering(level, pos)
        if (capital == null) {
            nowhere.add(key)
            return null
        }
        known[capital.center] = capital
        return capital
    }

    /** Chunks told of a capital that turned out not to take them in (the edges of its pieces): not looked at again. */
    private val nowhere = HashSet<Long>()

    /** Keeps [capital]'s lamps from now (once its plan is worked out, off the server's thread). */
    private fun keep(level: ServerLevel, capital: CapitalPiece, time: Long) {
        towns[capital.center]?.let {
            it.seen = time
            return
        }
        if (capital.center in failed) return
        val job = working[capital.center]
        if (job == null) {
            val places = CityPlaces.of(level, capital) ?: return
            working[capital.center] = CompletableFuture.supplyAsync({
                LampTown(capital, places, LampPlan(places.census.plan, places.census))
            }, Util.backgroundExecutor())
            return
        }
        if (!job.isDone) return
        working.remove(capital.center)
        val town = try {
            job.join()
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("Couldn't work out the street lamps of the capital at {}", capital.center, e)
            failed.add(capital.center)
            null
        } ?: return
        town.seen = time
        towns[capital.center] = town
    }

    /** The lamps of [capital] if they're kept just now (starting on them if not, for the testing command). */
    fun town(level: ServerLevel, capital: CapitalPiece): LampTown? {
        known[capital.center] = capital
        keep(level, capital, level.gameTime)
        return towns[capital.center]
    }

    /** Whether working out [capital]'s lamps went wrong. */
    fun failed(capital: CapitalPiece): Boolean = capital.center in failed

    // ---- A pass over a capital's lamps ----

    private fun pass(level: ServerLevel, town: LampTown, now: Long) {
        val clock = level.overworldClockTime
        val time = since(clock)
        val raining = level.isRaining
        val stretch = setUp(level, town, clock, raining)
        val enabled = enabled()
        if (enabled) for (run in town.runs) {
            // A lamplighter gone quiet (hurt, called away, out of reach of the players) hands their lamps back to the clock
            val held = run.held
            if (held != null && now - run.heldAt > HELD_TICKS) release(level, town, run, held)
            if (run.held == null && (stretch == Stretch.DUSK || stretch == Stretch.DAWN) && run.keeper >= 0 && now >= run.lookAt) {
                look(level, town, run, stretch == Stretch.DUSK, time, now)
            }
        }
        val progress = IntArray(town.runs.size) { town.runs[it].progress(time) }
        val thunder = level.isThundering
        val sinceStorm = now - stormAt
        for (i in town.pos.indices) {
            val run = town.runs[town.runOf[i]]
            val want = wanted(town, i, run, progress[run.index], stretch, time, raining, thunder, sinceStorm, enabled)
            if (want != town.applied[i]) set(level, town, i, want, now, enabled)
        }
    }

    /**
     * The stretch of the day it is for [town]'s lamps, the evening's or the morning's rounds set going as the day reaches
     * them (or as the capital is first kept in one). A night only goes forward: rain coming on in the evening brings the
     * night on early, and the evening doesn't come back when it stops.
     */
    private fun setUp(level: ServerLevel, town: LampTown, clock: Long, raining: Boolean): Stretch {
        val night = night(clock)
        val time = since(clock)
        if (town.night != night) {
            town.night = night
            town.reached = 0
            town.rainyDusk = raining
            town.rainyDawn = raining
        }
        // The weather as the evening begins, and as the morning does, sets them early or late
        if (time < DUSK_OPEN) town.rainyDusk = raining
        if (time < DAWN) town.rainyDawn = raining
        val stretch = Stretch.entries[maxOf(stretch(town, time, raining).ordinal, town.reached)]
        town.reached = stretch.ordinal
        val key = night * 8 + stretch.ordinal
        if (town.window == key) return stretch
        town.window = key
        for (run in town.runs) {
            run.held = null
            run.done = 0
            run.force = 0
            run.lookAt = 0L
        }
        if (stretch == Stretch.DUSK || stretch == Stretch.DAWN) LampKeepers.assign(level, town)
        // Rationed or not is settled as the evening's rounds begin, for the night
        if (stretch == Stretch.DUSK) town.rationed = rationed(level, town) else if (stretch == Stretch.AFTERNOON) town.rationed = false
        when (stretch) {
            Stretch.DUSK -> for (run in town.runs) run.start = duskStart(level, town, run, night)
            Stretch.DAWN -> for (run in town.runs) run.start = dawnStart(level, town, run, night)
            else -> {}
        }
        return stretch
    }

    /** When the clock has [run] begin this evening: a round so it ends before every lamp must be lit, a farm's as its farmer comes in. */
    private fun duskStart(level: ServerLevel, town: LampTown, run: LampTown.Run, night: Long): Int {
        val shift = if (town.rainyDusk) RAIN else 0
        val litBy = LIT_BY - shift
        val jitter = (spread(run.index, night) * 200).toInt() - 100
        return when (run.kind) {
            LampTown.Kind.CITY -> {
                val late = litBy - 150 - run.ticks
                (minOf(DUSK - shift, late) + jitter).coerceAtMost(late).coerceAtLeast(DUSK - 500 - shift)
            }
            LampTown.Kind.FARM -> {
                val supper = LampKeepers.farmerIn(level, run, night)
                ((supper ?: (DUSK + 500)) - shift).coerceAtMost(litBy - 200 - run.ticks).coerceAtLeast(DUSK_OPEN)
            }
            LampTown.Kind.LONE -> {
                val from = DUSK - shift
                from + (town.phase(run.lamps[0]) * (litBy - 200 - from)).toInt()
            }
        }
    }

    /** When the clock has [run] begin this morning: the rounds from half past five, a farm's as its farmer goes out. */
    private fun dawnStart(level: ServerLevel, town: LampTown, run: LampTown.Run, night: Long): Int {
        val dawn = dawnOf(town)
        val outBy = outBy(town)
        return when (run.kind) {
            LampTown.Kind.CITY -> dawn + (spread(run.index, night) * 300).toInt()
            LampTown.Kind.FARM -> {
                val out = LampKeepers.farmerOut(level, run, night)
                (out ?: (dawn + 400)).coerceAtMost(outBy - 200 - run.ticks).coerceAtLeast(dawn)
            }
            LampTown.Kind.LONE -> dawn + (town.phase(run.lamps[0]) * (outBy - 300 - dawn)).toInt()
        }
    }

    /** A number from 0 to 1 that looks random but is fixed for a round on a night. */
    private fun spread(run: Int, night: Long): Double {
        var h = run * -0x61C8864680B583EBL + night * 0x632BE59BD9B4E019L
        h = (h xor (h ushr 29)) * -0x4B47D5FB3AC29A3FL
        h = h xor (h ushr 32)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** What lamp [i] (in [run], which has seen to [progress] of its lamps) should be just now. */
    private fun wanted(
        town: LampTown, i: Int, run: LampTown.Run, progress: Int, stretch: Stretch, time: Int, raining: Boolean,
        thunder: Boolean, sinceStorm: Long, enabled: Boolean,
    ): Byte {
        if (!enabled) return LampTown.LIT
        val k = town.placeOf[i]
        val force = run.force
        if (force != 0.toByte()) {
            // Rationed: the lamps the lamplighter leaves catch one after another once the round is done
            if (force == LampTown.LIT && skipped(town, run, k)) {
                return if (time >= run.start + run.ticks + RATIONED_AFTER + (town.phase(i) * RATIONED_SPREAD).toInt()) LampTown.LIT else LampTown.OUT
            }
            return if (k < progress) force else if (force == LampTown.LIT) LampTown.OUT else LampTown.LIT
        }
        if (thunder) return LampTown.LIT
        return when (stretch) {
            Stretch.NIGHT -> LampTown.LIT
            Stretch.DUSK -> when {
                raining && time >= LIT_BY - RAIN -> LampTown.LIT
                // Rationed: left by the lamplighter, it catches by itself a little before every lamp must be lit
                skipped(town, run, k) -> if (time >= litBy(town, false) - RATIONED_LATE - (town.phase(i) * RATIONED_SPREAD).toInt()) LampTown.LIT else LampTown.OUT
                k < progress -> LampTown.LIT
                else -> LampTown.OUT
            }
            Stretch.DAWN -> if (k < progress && !(raining && time < DAWN + RAIN)) LampTown.OUT else LampTown.LIT
            // After a storm by day the lamps go out one by one
            else -> if (sinceStorm < town.phase(i) * STORM_AFTER) LampTown.LIT else LampTown.OUT
        }
    }

    /**
     * Sets lamp [i] to [want], if its land is loaded: with a little flame or a wisp of smoke if a player is near enough
     * to see (and it was known to be otherwise); an older capital's lantern there is swapped for a street lamp when
     * nobody is near enough to see that.
     */
    private fun set(level: ServerLevel, town: LampTown, i: Int, want: Byte, now: Long, enabled: Boolean) {
        val pos = town.pos[i]
        if (!level.isLoaded(pos)) return
        val state = level.getBlockState(pos)
        val lit = want == LampTown.LIT
        when {
            StreetLampBlock.isLamp(state) -> {
                val seen = town.applied[i] != LampTown.UNKNOWN && seen(level, pos, SHOW)
                if (seen) {
                    if (lit) StreetLampBlock.light(level, pos, show = true) else StreetLampBlock.putOut(level, pos, show = true)
                } else {
                    StreetLampBlock.set(level, pos, lit)
                }
            }
            old(state) -> {
                if (enabled) {
                    if (now < town.retry[i]) return
                    if (seen(level, pos, UNSEEN)) {
                        town.retry[i] = now + RETRY
                        return
                    }
                    level.setBlock(pos, swapped(state, lit), Block.UPDATE_ALL)
                }
            }
            else -> {}
        }
        town.applied[i] = want
    }

    /** An older capital's lantern standing on its post (or a soul lantern). */
    fun old(state: BlockState): Boolean =
        (state.`is`(Blocks.LANTERN) || state.`is`(Blocks.SOUL_LANTERN)) && !state.getValue(BlockStateProperties.HANGING)

    /** The street lamp an older capital's [state] (a lantern on its post) is swapped for, [lit] or not. */
    fun swapped(state: BlockState, lit: Boolean): BlockState {
        val block = if (state.`is`(Blocks.SOUL_LANTERN)) LampRegistry.SOUL_STREET_LAMP.get() else LampRegistry.STREET_LAMP.get()
        return block.defaultBlockState().setValue(StreetLampBlock.LIT, lit)
            .setValue(StreetLampBlock.WATERLOGGED, state.getValue(BlockStateProperties.WATERLOGGED))
    }

    private fun seen(level: ServerLevel, pos: BlockPos, within: Double): Boolean =
        level.getNearestPlayer(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, within, false) != null

    // ---- The lamplighters ----

    /** Looks for [run]'s keeper to go round it, if the clock has it begin (or nearly: they have the pole to fetch first). */
    private fun look(level: ServerLevel, town: LampTown, run: LampTown.Run, dusk: Boolean, time: Int, now: Long) {
        val deadline = if (dusk) litBy(town, level.isRaining) else outBy(town)
        if (time < run.start - LEAD || time > deadline - 300 || run.progress(time) >= run.lamps.size) return
        // Dark enough for monsters (a storm, or rain after half past six): every lamp is lit, and stays so
        if (dark(level)) return
        run.lookAt = now + LOOK_AGAIN
        val director = taker?.take(level, town, run, dusk, time) ?: return
        hold(town, run, director, run.progress(time), now)
    }

    /** Ticks before its start that a round's keeper is fetched (to get the pole first), and between looks for one not to be had. */
    private const val LEAD = 300
    private const val LOOK_AGAIN = 200L

    /**
     * Rationed lamps: how long before every lamp must be lit the ones a lamplighter leaves begin to catch by themselves,
     * over how long they all have (each at its own phase in it), and how long after a testing round they begin.
     */
    private const val RATIONED_LATE = 20
    private const val RATIONED_SPREAD = 180
    private const val RATIONED_AFTER = 40

    /** [director] goes round [run] from its lamp [from]: its lamps wait for them from now. */
    fun hold(town: LampTown, run: LampTown.Run, director: Director, from: Int, now: Long) {
        run.held = director
        run.done = from
        run.heldAt = now
    }

    /**
     * Whether [director] still has [run] of [town] (not handed back for going quiet, nor a new stretch of the day begun,
     * nor the capital let go of with no player near).
     */
    fun holds(town: LampTown, run: LampTown.Run, director: Director): Boolean = run.held === director && towns[town.capital.center] === town

    /** The stretch of the day it is for [town]'s lamps just now. */
    fun stretchNow(level: ServerLevel, town: LampTown): Stretch = setUp(level, town, level.overworldClockTime, level.isRaining)

    /** [director] is still at it (each tick they go round). */
    fun alive(level: ServerLevel, run: LampTown.Run, director: Director) {
        if (run.held === director) run.heldAt = level.gameTime
    }

    /** [director] has lit, put out or passed by the lamp at [k] in [run] (lit or out already, or out of their reach). */
    fun seenTo(run: LampTown.Run, director: Director, k: Int) {
        if (run.held === director && k + 1 > run.done) run.done = k + 1
    }

    /**
     * [director] lights (or puts out, [lit] false) the lamp at [k] in [run] now, as their move reaches it: it catches (or
     * goes out) without the keeper's own little flame or smoke, since their move shows its own.
     */
    fun light(level: ServerLevel, town: LampTown, run: LampTown.Run, director: Director, k: Int, lit: Boolean) {
        if (run.held !== director) return
        val i = run.lamps[k]
        val pos = town.pos[i]
        if (level.isLoaded(pos)) {
            if (lit) StreetLampBlock.light(level, pos, show = false) else StreetLampBlock.putOut(level, pos, show = false)
            town.applied[i] = if (lit) LampTown.LIT else LampTown.OUT
        }
        seenTo(run, director, k)
    }

    /**
     * Hands [run] back from [director] to the clock: its lamps not yet seen to catch (or go out) one after another from
     * now at the lamplighter's pace, beginning a second from now.
     */
    fun release(level: ServerLevel, town: LampTown, run: LampTown.Run, director: Director) {
        if (run.held !== director) return
        run.held = null
        // The clock takes up where they left off (never going back on a lamp they've seen to)
        val time = since(level.overworldClockTime)
        run.start = if (run.done < run.lamps.size) time + PASS - run.offsets[run.done] else minOf(run.start, time - run.offsets.last())
        run.lookAt = level.gameTime + LOOK_AGAIN * 3
    }

    // ---- For the testing command ----

    /** Every lamp of [town] lit (or out) now, as it would be by itself; the clock takes them up again from its next change. */
    fun setAll(level: ServerLevel, town: LampTown, lit: Boolean): Int {
        var n = 0
        val clock = level.overworldClockTime
        val time = since(clock)
        val raining = level.isRaining
        val stretch = setUp(level, town, clock, raining)
        val enabled = enabled()
        val sinceStorm = level.gameTime - stormAt
        for (i in town.pos.indices) {
            val pos = town.pos[i]
            if (!level.isLoaded(pos)) continue
            val state = level.getBlockState(pos)
            val done = when {
                StreetLampBlock.isLamp(state) -> if (lit) StreetLampBlock.light(level, pos, seen(level, pos, SHOW)) else StreetLampBlock.putOut(level, pos, seen(level, pos, SHOW))
                old(state) && enabled -> level.setBlock(pos, swapped(state, lit), Block.UPDATE_ALL)
                else -> false
            }
            if (done) n++
            // Left so until the clock next wants it otherwise
            val run = town.runs[town.runOf[i]]
            town.applied[i] = wanted(town, i, run, run.progress(time), stretch, time, raining, level.isThundering, sinceStorm, enabled)
        }
        return n
    }

    /**
     * Sets [run] going now, whatever the hour: its lamps lit one after another if most of them are out, put out if most are
     * lit, by its keeper if they can be had, or by the clock. It holds until the day's next stretch.
     */
    fun startNow(level: ServerLevel, town: LampTown, run: LampTown.Run): Boolean {
        val clock = level.overworldClockTime
        val time = since(clock)
        setUp(level, town, clock, level.isRaining)
        val lit = run.lamps.count { i -> StreetLampBlock.burning(level.getBlockState(town.pos[i])) }
        val dusk = lit * 2 < run.lamps.size
        if (dusk) town.rationed = rationed(level, town)
        val previous = run.held
        if (previous != null) release(level, town, run, previous)
        run.force = if (dusk) LampTown.LIT else LampTown.OUT
        run.start = time + LEAD / 2
        run.lookAt = 0L
        val director = taker?.take(level, town, run, dusk, time)
        if (director != null) hold(town, run, director, 0, level.gameTime)
        return dusk
    }
}
