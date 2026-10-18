package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.festival.FestivalOrnamentBlockEntity
import io.github.vaspyyy.guildmark.festival.FestivalRestores
import io.github.vaspyyy.guildmark.festival.OrnamentData
import io.github.vaspyyy.guildmark.lamps.StreetLamps
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.scenes.Alms
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.scenes.ShutStall
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * The streets in good times and hard (living-world/design-influence.md section 5, M6 Part D): each sign a live state of
 * a capital, going up and coming down by its cause, with someone nearby to say why.
 *
 * - **The dressing** ([Dressing]): garlands over the doors, pennant strings from the eaves along the main street and
 *   round the market square, flower pots on the doorsteps, and in thriving times lantern strings over the square,
 *   hung by residents up ladders after two days of good times and taken down two days after they're over.
 * - **The rationed lamps** ([Rationing], and lamps/StreetLamps, townsfolk/Lamplighters): every other lamp lit at dusk
 *   while candles are short.
 * - **The slate and the awning** ([Awnings]): a stall with nothing to sell has its awning's flap let down and a chalked
 *   slate leaning on its display.
 * - **"To let" boards** ([Signs]) by the doors of homes a household has left.
 * - **The alms table** ([AlmsTable]) by the market's well at noon on a hungry day or in hard times.
 *
 * Everything that follows the times or the market waits on [Fortunes.enabled], the boards on [Comings.enabled]; with
 * both off (as they are until M6 is switched on) nothing here runs, and nothing is put up, but by `/guildmark streets`'s
 * tests ([StreetsCommand]). What goes up goes up through the festivals' record of what stood where
 * ([FestivalRestores]), under site keys of its own ([site]), so it's put back as it was however it ends; a piece the
 * signs no longer want is put back as its chunk loads or its random tick comes ([wants], from
 * [io.github.vaspyyy.guildmark.festival.Festivities]). It's all decided here on the server, a capital at a time once a
 * second for the capitals players are in, and a few blocks a tick at most ([later]); nothing is scanned against
 * everything, and no chunk is loaded for it.
 */
object Streets {
    // ---- The signs' records ----

    /**
     * The y of each sign's site key (see [site]): under any world's floor, so no festival's site key is ever one of
     * them. A record's run says which of a capital's pieces it is (see each sign).
     */
    const val DRESSING = -2048
    const val BOARDS = -2047
    const val ALMS = -2046
    const val FLAPS = -2045

    /** A capital's column: its heart's x and z, which the signs' records and their saved data go by. */
    fun column(capital: CapitalPiece): Long = BlockPos.asLong(capital.center.x, 0, capital.center.z)

    /** The column of [key] (a capital's heart, or one of the signs' site keys). */
    fun column(key: Long): Long = BlockPos.asLong(BlockPos.getX(key), 0, BlockPos.getZ(key))

    /** The site key of [column]'s [band] of pieces (one of [DRESSING], [BOARDS], [ALMS] and [FLAPS]). */
    fun site(column: Long, band: Int): Long = BlockPos.asLong(BlockPos.getX(column), band, BlockPos.getZ(column))

    /**
     * Whether the signs still want the piece at [pos] ([record]): asked as its chunk loads and on its random ticks
     * (Festivities.wanted). Never for a festival's own (they're told apart by their site key at once).
     */
    fun wants(level: ServerLevel, pos: BlockPos, record: FestivalRestores.PieceRecord): Boolean {
        val band = BlockPos.getY(record.site)
        if (band < DRESSING || band > FLAPS) return false
        val column = column(record.site)
        return try {
            when (band) {
                DRESSING -> Dressing.wants(level, column, pos, record.run)
                BOARDS -> Signs.wants(level, column, pos, record.run)
                ALMS -> AlmsTable.wants(level, column, pos, record.run)
                else -> Awnings.wants(level, column, pos, record.run)
            }
        } catch (e: RuntimeException) {
            if (!warned) Guildmark.LOGGER.warn("Couldn't tell whether the streets still want the piece at {}", pos, e)
            warned = true
            false
        }
    }

    private var warned = false

    // ---- Setting it going ----

    private var registered = false

    /**
     * Sets the streets going, once, at setup: the alms table's block, the rounds of the capitals players are in, the
     * keeper's and the hungry's tables ([Alms.source]), the awnings ([ShutStall.awning]), the lamps' rationing
     * ([StreetLamps.rationing]), the times and households heard, and `/guildmark streets`.
     */
    fun register(modBus: IEventBus) {
        if (registered) return
        registered = true
        StreetsRegistry.register(modBus)
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { StreetsCommand.register(it.dispatcher) }
        Alms.source = AlmsTable
        ShutStall.awning = Awnings
        StreetLamps.rationing = Rationing
        Fortunes.listen(Dressing)
        Comings.listen(Signs)
    }

    /**
     * Whether `/guildmark streets` has set something going this session (a dressing, a board, a table, the lamps),
     * which runs whatever the switches say.
     */
    @Volatile
    var testing = false

    /** Whether anything here runs just now: the times, the households, or the streets command's tests. */
    fun on(): Boolean = testing || Fortunes.enabled() || Comings.enabled()

    // ---- The capitals players are in ----

    /** The capitals players are in, as last looked up (by column), and the game time each was last seen. */
    private val here = HashMap<Long, Scenes.Here>()
    private val hereAt = HashMap<Long, Long>()

    /** The capital of [column] and its places, if a player has been in it lately. */
    fun here(column: Long): Scenes.Here? = here[column]

    /** Once a second: each capital a player is in has its signs looked at; a few blocks a tick put up or taken down. */
    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        if (timed.isNotEmpty()) due(level)
        work(level)
        if (level.gameTime % LOOK != 7L) return
        if (!on()) {
            if (here.isNotEmpty()) here.clear()
            return
        }
        val now = level.gameTime
        val seen = LinkedHashMap<Long, Scenes.Here>()
        for (player in level.players()) {
            if (player.isSpectator || player.level().dimension() != Level.OVERWORLD) continue
            val h = Scenes.here(level, player) ?: continue
            seen.getOrPut(column(h.capital)) { h }
        }
        for ((column, h) in seen) {
            here[column] = h
            hereAt[column] = now
        }
        hereAt.entries.removeIf { (column, at) -> (now - at > FORGET).also { if (it) here.remove(column) } }
        for ((column, h) in seen) {
            try {
                look(level, column, h, now)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("The streets of the capital at {} went wrong", h.capital.center, e)
            }
        }
    }

    /** Each of a capital's signs looked at, a player being in it. */
    private fun look(level: ServerLevel, column: Long, h: Scenes.Here, now: Long) {
        Dressing.look(level, column, h, now)
        Awnings.look(level, column, h, now)
        Signs.look(level, column, h, now)
        AlmsTable.look(level, column, h, now)
    }

    // ---- Putting pieces up and taking them down ----

    /** Pieces to put up or take down out of anyone's sight, a few a tick, by position (a later one for a place replaces the earlier). */
    private val jobs = LinkedHashMap<Long, (ServerLevel) -> Unit>()

    /** [job] for the block at [pos], done in a tick or so (a few blocks a tick between all the signs). */
    fun later(pos: BlockPos, job: (ServerLevel) -> Unit) {
        jobs.remove(pos.asLong())
        jobs[pos.asLong()] = job
    }

    /** Whether a job is waiting for [pos]. */
    fun pending(pos: BlockPos): Boolean = jobs.containsKey(pos.asLong())

    /** Jobs set for a moment to come (a slate set down as the keeper tidies the counter, a flap taken down once it's rolled up). */
    private class Timed(val at: Long, val job: (ServerLevel) -> Unit)

    private val timed = ArrayList<Timed>()

    /** [job] done [ticks] from now. */
    fun after(level: ServerLevel, ticks: Int, job: (ServerLevel) -> Unit) {
        timed += Timed(level.gameTime + ticks, job)
    }

    private fun due(level: ServerLevel) {
        val now = level.gameTime
        val due = timed.filter { it.at <= now }
        if (due.isEmpty()) return
        timed.removeAll(due.toSet())
        for (t in due) {
            try {
                t.job(level)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A piece of the streets went wrong", e)
            }
        }
    }

    private fun work(level: ServerLevel) {
        var budget = PER_TICK
        while (budget-- > 0 && jobs.isNotEmpty()) {
            val first = jobs.entries.iterator()
            val job = first.next().value
            first.remove()
            try {
                job(level)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A piece of the streets couldn't be put up or taken down", e)
            }
        }
    }

    /**
     * Whether the piece at [pos] is [column]'s, of [band] (and of [run], unless it's null): one of ours standing there
     * with its record.
     */
    fun mine(level: ServerLevel, pos: BlockPos, column: Long, band: Int, run: Long? = null): Boolean {
        if (!level.isLoaded(pos)) return false
        if (!FestivalRestores.ours(level.getBlockState(pos))) return false
        val record = FestivalRestores.recordAt(level, pos) ?: return false
        return record.site == site(column, band) && (run == null || record.run == run)
    }

    /**
     * Puts [state] up at [pos] as [column]'s [band] piece [run] (an ornament showing [data]), remembering what stood
     * there: only into air or a plant anything may build over, never over a festival's piece or another sign's. True
     * if it stands.
     */
    fun place(level: ServerLevel, pos: BlockPos, state: BlockState, column: Long, band: Int, run: Long, solid: Boolean, data: OrnamentData? = null): Boolean {
        if (!level.isLoaded(pos)) return false
        val current = level.getBlockState(pos)
        if (FestivalRestores.ours(current)) {
            val record = FestivalRestores.recordAt(level, pos)
            if (record == null || record.site != site(column, band)) return false
        }
        if (!FestivalRestores.place(level, pos, state, site(column, band), run, solid)) return false
        if (data != null) (level.getBlockEntity(pos) as? FestivalOrnamentBlockEntity)?.setup(data)
        return true
    }

    /** Takes [column]'s [band] piece at [pos] down, putting back what stood there, if it's ours still. */
    fun restore(level: ServerLevel, pos: BlockPos, column: Long, band: Int) {
        if (!level.isLoaded(pos)) return
        val record = FestivalRestores.recordAt(level, pos)
        if (record != null && record.site != site(column, band)) return
        if (record == null && !FestivalRestores.ours(level.getBlockState(pos))) return
        FestivalRestores.restore(level, pos)
    }

    /** A streets sound, played by the server for those within [reach] blocks (its sounds.json entry's reach). */
    fun sound(level: ServerLevel, x: Double, y: Double, z: Double, name: String, reach: Float, volume: Float = 1f, pitch: Float = 1f) {
        val event = sounds.getOrPut(name) { Holder.direct(SoundEvent.createFixedRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name), reach)) }
        level.playSound(null, x, y, z, event, SoundSource.NEUTRAL, volume, pitch)
    }

    private val sounds = HashMap<String, Holder<SoundEvent>>()

    /** Whether any player is within [r] blocks of [pos]. */
    fun seen(level: ServerLevel, pos: BlockPos, r: Double): Boolean = Scenes.seen(level, pos, r)

    /** Everything kept in memory forgotten, as the server stops. */
    private fun forget() {
        here.clear()
        hereAt.clear()
        jobs.clear()
        timed.clear()
        testing = false
        warned = false
        Dressing.forget()
        Fitting.forget()
        Awnings.forget()
        Signs.forget()
        AlmsTable.forget()
        Rationing.forget()
    }

    /** Ticks between looks at the capitals players are in, and how long one is remembered after the last player's gone. */
    private const val LOOK = 20L
    private const val FORGET = 1200L

    /** The most pieces put up or taken down out of sight in a tick, between all the signs. */
    private const val PER_TICK = 4

    /** How near a player must be to see a piece go up or come down: nearer, people do it; further, it's done as it would be. */
    const val SIGHT = 48.0
}
