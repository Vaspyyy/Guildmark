package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalOrnamentBlock
import io.github.vaspyyy.guildmark.festival.FestivalOrnamentBlockEntity
import io.github.vaspyyy.guildmark.festival.FestivalTrimBlock
import io.github.vaspyyy.guildmark.festival.OrnamentData
import io.github.vaspyyy.guildmark.festival.OrnamentKind
import io.github.vaspyyy.guildmark.festival.TrimLook
import io.github.vaspyyy.guildmark.lamps.StreetLampBlock
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * The streets dressed for good times (living-world/design-influence.md section 5, M6 Part D1). When a capital's times
 * have been GOOD or THRIVING for two market days or more, its residents go round with ladders and dress the main street
 * (the avenue in from the south gate) and the market square: a garland swagged over each door, a string of pennants
 * from the eaves across the street (and from the square's doors to the avenue's lamps), a pot of flowers on each
 * doorstep, and, in thriving times, strings of little lanterns zig-zagging over the square from lamp to lamp, lit as
 * each lamp is lit at dusk. Two market days after the times fall back below good it all comes down again the same way
 * (the lanterns two days after they fall back below thriving). It's the festivals' own look in the season's festival's
 * colours, no new art: the festivals' ornaments and trim in new kinds and looks.
 *
 * Who does it: a few residents at a time ([Fitting]), each taking a round of the pieces near one another, by day; a
 * child near by points and cheers as a piece goes up (`child.festoons`). Out of every player's sight ([Streets.SIGHT])
 * the pieces simply go up (or come down) a few a tick, as they would have. It never dresses within [CLEAR] blocks of
 * the fountain, where a festival would. Its pieces go through the festivals' record of what stood where with a run of
 * their own ([run]: the dressing's count, so a stray from an earlier one is known, and which part of it the piece is),
 * so whatever happens they're put back. All of it waits on [Fortunes.enabled] but for `/guildmark streets dress`'s
 * tests, which last the session.
 */
object Dressing : Fortunes.Listener {
    /** What a piece is. */
    enum class Kind(val part: Int) { GARLAND(PIECES), BUNTING(PIECES), POT(PIECES), LANTERNS(LANTERN_PART) }

    /**
     * One piece of a capital's dressing as its places lay it out: what it is, the block it goes in, and how it's put up:
     * a worker stands at [foot] and sets a ladder whose bottom is [ladder] facing [facing] (away from the wall) and
     * climbs [rise] blocks (or, with no ladder, sets it down from [foot] facing [facing]), looking at [look]. A string
     * or garland is tied on at [from] (from the block's corner) and runs along [span]; a lantern string's [lamp] is the
     * street lamp under its first end, whose light it follows. [home] is the lodging whose door it dresses (-1: none).
     */
    class Piece(
        val kind: Kind, val pos: BlockPos, val home: Int, val foot: BlockPos, val ladder: BlockPos?, val facing: Direction,
        val rise: Int, val look: Vec3, val from: Vec3?, val span: Vec3?, val lamp: BlockPos?, val seed: Int,
    ) {
        val key: Long get() = pos.asLong()
    }

    /** A capital's dressing as it's being seen to, in memory: its pieces, the rounds out, and what waits. */
    private class Crew(val column: Long) {
        var pieces: List<Piece>? = null
        val busy = HashSet<Long>()
        val retry = HashMap<Long, Long>()
        val workers = HashSet<Int>()
        var rounds = 0
        var waiting = -1L

        /** The homes left (and those nobody alive lives in), looked up now and then. */
        var empty: Set<Int> = setOf()
        var lived: Set<Int> = setOf()
        var homesAt = Long.MIN_VALUE / 2
    }

    private val crews = HashMap<Long, Crew>()

    // ---- The record ----

    /** The run of [column]'s dressing [r]'s [part] ([PIECES], [LANTERN_PART] or [LADDER]), as its pieces' records carry it. */
    fun run(r: Long, part: Int): Long = r * 4 + part

    /** The run a ladder set up now for [column]'s dressing carries. */
    fun ladderRun(level: ServerLevel, column: Long): Long = run(StreetsData.get(level)[column]?.run ?: 0L, LADDER)

    /**
     * Whether the dressing's piece at [pos] ([run]) is wanted still: one of the dressing now up (or coming down, until
     * someone takes it down), of its lanterns while they hang, or a ladder someone's up just now. None with the times
     * off, but for the streets command's tests (this session's).
     */
    fun wants(level: ServerLevel, column: Long, pos: BlockPos, run: Long): Boolean {
        val part = Math.floorMod(run, 4L).toInt()
        if (part == LADDER) return pos.asLong() in Fitting.ladders
        val t = StreetsData.get(level)[column] ?: return false
        if (Math.floorDiv(run, 4L) != t.run) return false
        if (!(Fortunes.enabled() || forced(t))) return false
        return when (part) {
            PIECES -> t.dress != StreetsData.NONE
            LANTERN_PART -> t.dress != StreetsData.NONE && t.lanterns != StreetsData.NONE
            else -> false
        }
    }

    /** Whether the streets command has [t]'s dressing up or down this session, whatever the times. */
    private fun forced(t: StreetsData.Town): Boolean = Streets.testing && t.force != StreetsData.FORCE_NONE

    // ---- The times ----

    override fun changed(level: ServerLevel, capital: Long, from: Times, to: Times) {
        // Looked at again at once (rather than in a second), the times having moved
        crews[Streets.column(capital)]?.let { it.waiting = -1L }
    }

    /** What the times (or the command) want of a capital's dressing today. */
    private class Want(val dress: Boolean, val lanterns: Boolean)

    private fun wanted(level: ServerLevel, key: Long, t: StreetsData.Town?): Want {
        if (t != null && forced(t)) return when (t.force) {
            StreetsData.FORCE_GOOD -> Want(dress = true, lanterns = false)
            StreetsData.FORCE_THRIVING -> Want(dress = true, lanterns = true)
            else -> Want(dress = false, lanterns = false)
        }
        if (!Fortunes.enabled()) return Want(dress = false, lanterns = false)
        val today = Fortunes.today(level, key)
        val up = t != null && t.dress == StreetsData.UP
        val hung = t != null && t.lanterns == StreetsData.UP
        val good = from(level, key, Times.GOOD, today)
        val fell = fell(level, key, Times.GOOD)
        val dress = if (good >= 0) up || today - good >= HELD else up && today - fell < HELD
        val thriving = from(level, key, Times.THRIVING, today)
        val dimmed = fell(level, key, Times.THRIVING)
        val lanterns = dress && (thriving >= 0 || hung && today - dimmed < HELD)
        if (t != null) {
            t.goodFrom = good
            t.fellOn = fell
            t.dimmedOn = dimmed
        }
        return Want(dress, lanterns)
    }

    /** The market day the run of days at [least] or better that [key]'s times are in now began (-1: they're below it). */
    private fun from(level: ServerLevel, key: Long, least: Times, today: Long): Long {
        if (Fortunes.times(level, key) < least) return -1L
        var from = Fortunes.since(level, key).takeIf { it in 0..today } ?: today
        for (d in Fortunes.days(level, key).asReversed()) {
            if (d.day >= from) continue
            if (d.day == from - 1 && d.times >= least) from = d.day else break
        }
        return from
    }

    /** The market day [key]'s times last fell below [least] (-1: they're at it or better; [LONG_AGO] if not lately). */
    private fun fell(level: ServerLevel, key: Long, least: Times): Long {
        if (Fortunes.times(level, key) >= least) return -1L
        val days = Fortunes.days(level, key)
        val last = days.indexOfLast { it.times >= least }
        if (last < 0) return LONG_AGO
        return days.getOrNull(last + 1)?.day ?: maxOf(Fortunes.since(level, key), days[last].day + 1)
    }

    // ---- Once a second, a player in the capital ----

    fun look(level: ServerLevel, column: Long, h: Scenes.Here, now: Long) {
        val data = StreetsData.get(level)
        val t = data[column]
        // A test of another session's is over
        if (t != null && t.force != StreetsData.FORCE_NONE && !Streets.testing) {
            t.force = StreetsData.FORCE_NONE
            data.changed()
        }
        val key = h.capital.center.asLong()
        val want = wanted(level, key, t)
        if (t == null && !want.dress) return
        val town = t ?: data.town(column)
        // Up, coming down, or back up again (the times come round before it was all down)
        if (want.dress) {
            if (town.dress == StreetsData.NONE) town.run++
            if (town.dress != StreetsData.UP) {
                town.dress = StreetsData.UP
                town.downFrom = -1L
                data.changed()
            }
        } else if (town.dress == StreetsData.UP) {
            town.dress = StreetsData.DOWN
            town.downFrom = if (Fortunes.enabled()) Fortunes.today(level, key) else Days.dayOf(level.overworldClockTime)
            data.changed()
        }
        if (want.dress && want.lanterns) {
            if (town.lanterns != StreetsData.UP) {
                town.lanterns = StreetsData.UP
                data.changed()
            }
        } else if (town.lanterns == StreetsData.UP) {
            town.lanterns = StreetsData.DOWN
            data.changed()
        }
        if (town.dress == StreetsData.NONE && town.lanterns == StreetsData.NONE) {
            crews.remove(column)
            return
        }
        val crew = crews.getOrPut(column) { Crew(column) }
        val pieces = crew.pieces ?: lay(h).also { crew.pieces = it }
        work(level, data, town, crew, pieces, h, now)
    }

    /** Each piece seen to: up if it's wanted and isn't, down if it is and isn't; the lanterns lit with their lamps. */
    private fun work(level: ServerLevel, data: StreetsData, town: StreetsData.Town, crew: Crew, pieces: List<Piece>, h: Scenes.Here, now: Long) {
        val column = crew.column
        val dressUp = town.dress == StreetsData.UP
        val lanternsUp = dressUp && town.lanterns == StreetsData.UP
        homes(level, crew, h, now)
        var standing = 0
        var lanternsStanding = 0
        var unloaded = false
        val put = ArrayList<Piece>()
        val take = ArrayList<Piece>()
        for (piece in pieces) {
            val pos = piece.pos
            if (!level.isLoaded(pos)) {
                unloaded = true
                continue
            }
            val mine = Streets.mine(level, pos, column, Streets.DRESSING, run(town.run, piece.kind.part))
            if (mine) {
                standing++
                if (piece.kind == Kind.LANTERNS) {
                    lanternsStanding++
                    light(level, piece)
                }
            }
            if (piece.key in crew.busy || Streets.pending(pos)) continue
            val want = if (piece.kind == Kind.LANTERNS) lanternsUp else dressUp && homeDressed(crew, piece)
            if (want && !mine && (crew.retry[piece.key] ?: Long.MIN_VALUE) <= now) put += piece
            else if (!want && mine) take += piece
        }
        // All down: the dressing's over (once every piece's land has been seen, or two days on regardless)
        val day = if (Fortunes.enabled()) Fortunes.today(level, h.capital.center.asLong()) else Days.dayOf(level.overworldClockTime)
        val settled = crew.busy.isEmpty() && put.isEmpty()
        if (town.lanterns == StreetsData.DOWN && lanternsStanding == 0 && settled && (!unloaded || day - town.downFrom >= HELD)) {
            town.lanterns = StreetsData.NONE
            data.changed()
        }
        if (town.dress == StreetsData.DOWN && standing == 0 && settled && (!unloaded || town.downFrom < 0 || day - town.downFrom >= HELD)) {
            town.dress = StreetsData.NONE
            town.lanterns = StreetsData.NONE
            if (town.force == StreetsData.FORCE_DOWN) town.force = StreetsData.FORCE_NONE
            data.changed()
            crews.remove(column)
            return
        }
        val forcedNow = forced(town)
        val inSight = ArrayList<Piece>()
        for (piece in put) if (Streets.seen(level, piece.pos, Streets.SIGHT)) inSight += piece else later(level, column, town.run, piece, putting = true)
        val putting = inSight.isNotEmpty()
        val takeSight = ArrayList<Piece>()
        for (piece in take) if (Streets.seen(level, piece.pos, Streets.SIGHT)) takeSight += piece else later(level, column, town.run, piece, putting = false)
        // People do what's in sight, by day (the command's tests whenever), a few rounds at a time
        if (!forcedNow && !daytime(level)) return
        val jobs = if (putting) inSight else takeSight
        if (jobs.isEmpty() || crew.rounds >= ROUNDS) return
        round(level, town, crew, jobs, putting, h, now)
    }

    /** Whether a home's door is dressed: one somebody lives in, and not left empty. */
    private fun homeDressed(crew: Crew, piece: Piece): Boolean = piece.home < 0 || piece.home !in crew.empty && piece.home in crew.lived

    /** The homes left empty and those lived in, looked up again now and then. */
    private fun homes(level: ServerLevel, crew: Crew, h: Scenes.Here, now: Long) {
        if (now - crew.homesAt < HOMES_EVERY) return
        crew.homesAt = now
        crew.empty = try {
            Comings.empty(level, h.capital.center.asLong()).toSet()
        } catch (e: RuntimeException) {
            setOf()
        }
        crew.lived = Residents.get(level).of(h.capital.center).filter { it.alive && it.home >= 0 }.map { it.home }.toSet()
    }

    /** A piece out of anyone's sight put up (or taken down) in a tick or so, as it would have been. */
    private fun later(level: ServerLevel, column: Long, r: Long, piece: Piece, putting: Boolean) {
        Streets.later(piece.pos) { lvl ->
            if (putting) {
                if (!put(lvl, column, r, piece, since = 0L, sound = false)) crews[column]?.retry?.put(piece.key, lvl.gameTime + RETRY)
            } else {
                take(lvl, column, piece, sound = false)
            }
        }
    }

    /** A round of [jobs]' first few near one another, for someone near them to see to, if anyone's free. */
    private fun round(level: ServerLevel, town: StreetsData.Town, crew: Crew, jobs: List<Piece>, putting: Boolean, h: Scenes.Here, now: Long) {
        // The one nearest a player first, so it's seen; then the nearest on from each
        val first = jobs.minByOrNull { p -> level.players().minOfOrNull { it.distanceToSqr(Vec3.atCenterOf(p.pos)) } ?: Double.MAX_VALUE } ?: return
        val left = jobs.toMutableList()
        left.remove(first)
        val chosen = arrayListOf(first)
        while (chosen.size < ROUND && left.isNotEmpty()) {
            val last = chosen.last().pos
            val next = left.minByOrNull { it.pos.distSqr(last) } ?: break
            if (next.pos.distSqr(first.pos) > NEAR * NEAR) break
            left.remove(next)
            chosen += next
        }
        val loose = crew.waiting >= 0 && now - crew.waiting > LOOSE
        val p = Fitting.hire(level, first.pos, h.capital.center, crew.workers, loose)
        if (p == null) {
            if (crew.waiting < 0) crew.waiting = now
            return
        }
        val column = crew.column
        val r = town.run
        val stations = chosen.map { station(column, r, it, putting) }
        val keys = chosen.map { it.key }
        val id = p.id
        val sent = Fitting.send(level, p, stations, putting, column) {
            crew.rounds--
            crew.workers.remove(id)
            crew.busy.removeAll(keys.toSet())
            // Anything the round didn't get to waits a while before it's tried again
            val at = level.gameTime
            for (piece in chosen) {
                val up = Streets.mine(level, piece.pos, column, Streets.DRESSING, run(r, piece.kind.part))
                if (up != putting) crew.retry[piece.key] = at + RETRY_ROUND
            }
        }
        if (!sent) return
        crew.waiting = -1L
        crew.rounds++
        crew.workers += id
        crew.busy += keys
    }

    /** [piece] as a worker's station for [column]'s dressing [r]. */
    private fun station(column: Long, r: Long, piece: Piece, putting: Boolean): Station = Station(
        piece.pos, piece.foot, piece.ladder, piece.facing, piece.rise, piece.look, holding(piece.kind),
        up = { level -> put(level, column, r, piece, since = level.gameTime, sound = true) },
        untie = if (piece.kind == Kind.POT) null else ({ level -> untie(level, piece) }),
        down = { level -> take(level, column, piece, sound = true) },
    )

    /** What a worker carries up to hang, or down again. */
    private fun holding(kind: Kind): ItemStack = when (kind) {
        Kind.GARLAND -> ItemStack(Items.VINE)
        Kind.BUNTING -> ItemStack(Items.STRING)
        Kind.POT -> ItemStack(Items.FLOWER_POT)
        Kind.LANTERNS -> ItemStack(Items.LANTERN)
    }

    // ---- Putting a piece up and taking it down ----

    /**
     * [piece] put up now for [column]'s dressing [r], if it's still wanted (it may have begun coming down meanwhile)
     * and there's room (and a wall to tie it to, or a doorstep to stand it on): [since] is when it's seen going up (0:
     * there already). True if it stands.
     */
    private fun put(level: ServerLevel, column: Long, r: Long, piece: Piece, since: Long, sound: Boolean): Boolean {
        val t = StreetsData.get(level)[column] ?: return false
        if (t.run != r || t.dress != StreetsData.UP) return false
        if (piece.kind == Kind.LANTERNS && t.lanterns != StreetsData.UP) return false
        val pos = piece.pos
        if (!level.isLoaded(pos)) return false
        val festival = FestivalKind.of(Seasons.today(level).season)
        val placed = when (piece.kind) {
            Kind.POT -> {
                if (!Places.floor(level, pos.below())) return false
                val look = TrimLook.POTS[Math.floorMod(piece.seed, TrimLook.POTS.size)]
                Streets.place(level, pos, FestivalTrimBlock.state(look, piece.facing), column, Streets.DRESSING, run(r, PIECES), solid = true)
            }
            else -> {
                // Tied to a wall (or the lamp's post) behind it
                if (piece.kind != Kind.LANTERNS && level.getBlockState(pos.relative(piece.facing.opposite)).isAir) return false
                val kind = when (piece.kind) {
                    Kind.GARLAND -> OrnamentKind.STREET_GARLAND
                    Kind.BUNTING -> OrnamentKind.STREET_BUNTING
                    else -> OrnamentKind.STREET_LANTERNS
                }
                val data = OrnamentData(
                    kind, festival, index = piece.seed and 0xFFFF,
                    string = if (piece.kind == Kind.GARLAND) null else piece.span,
                    garland = if (piece.kind == Kind.GARLAND) piece.span else null,
                    from = piece.from, since = since,
                )
                val light = if (piece.kind == Kind.LANTERNS && burning(level, piece)) LIT else 0
                Streets.place(level, pos, FestivalOrnamentBlock.state(light, false), column, Streets.DRESSING, run(r, piece.kind.part), solid = false, data = data)
            }
        }
        if (placed && sound) {
            val (event, volume, pitch) = when (piece.kind) {
                Kind.GARLAND -> Triple(SoundEvents.AZALEA_LEAVES_PLACE, 0.7f, 1.1f)
                Kind.BUNTING -> Triple(SoundEvents.WOOL_PLACE, 0.6f, 1.2f)
                Kind.POT -> Triple(SoundEvents.DECORATED_POT_PLACE, 0.5f, 1.25f)
                Kind.LANTERNS -> Triple(SoundEvents.CHAIN_PLACE, 0.6f, 1.15f)
            }
            play(level, pos, event, volume, pitch)
            if (piece.kind == Kind.POT) play(level, pos, SoundEvents.CROP_PLANTED, 0.5f, 1.1f)
            if (piece.kind == Kind.LANTERNS) play(level, pos, SoundEvents.LANTERN_PLACE, 0.5f, 1.3f)
        }
        return placed
    }

    /** [piece] begins to come down: its string untied (it falls slack and is gathered in). */
    private fun untie(level: ServerLevel, piece: Piece) {
        val be = level.getBlockEntity(piece.pos) as? FestivalOrnamentBlockEntity ?: return
        be.setup(be.data.copy(phase = 1, since = level.gameTime))
        play(level, piece.pos, if (piece.kind == Kind.LANTERNS) SoundEvents.CHAIN_PLACE else SoundEvents.WOOL_BREAK, 0.5f, 0.9f)
    }

    /** [piece] taken down now, what stood there put back. */
    private fun take(level: ServerLevel, column: Long, piece: Piece, sound: Boolean) {
        if (!Streets.mine(level, piece.pos, column, Streets.DRESSING)) return
        Streets.restore(level, piece.pos, column, Streets.DRESSING)
        if (sound) play(level, piece.pos, if (piece.kind == Kind.POT) SoundEvents.DECORATED_POT_BREAK else if (piece.kind == Kind.LANTERNS) SoundEvents.LANTERN_BREAK else SoundEvents.WOOL_BREAK, 0.45f, 1.1f)
    }

    private fun play(level: ServerLevel, pos: BlockPos, event: SoundEvent, volume: Float, pitch: Float) =
        level.playSound(null, pos, event, SoundSource.NEUTRAL, volume, pitch * (0.95f + level.random.nextFloat() * 0.1f))

    /** A lantern string lit while its lamp burns (so they light along the square as the lamplighter goes round). */
    private fun light(level: ServerLevel, piece: Piece) {
        val state = level.getBlockState(piece.pos)
        if (state.block !is FestivalOrnamentBlock) return
        val want = if (burning(level, piece)) LIT else 0
        if (state.getValue(FestivalOrnamentBlock.LIGHT) != want) level.setBlock(piece.pos, state.setValue(FestivalOrnamentBlock.LIGHT, want), Block.UPDATE_ALL)
    }

    private fun burning(level: ServerLevel, piece: Piece): Boolean {
        val lamp = piece.lamp ?: return false
        return level.isLoaded(lamp) && StreetLampBlock.burning(level.getBlockState(lamp))
    }

    /** Whether it's the hours people are up ladders: seven in the morning to seven in the evening. */
    private fun daytime(level: ServerLevel): Boolean = Days.offsetOf(level.overworldClockTime) in Days.clock(7.0) until Days.clock(19.0)

    // ---- Laying it out ----

    /**
     * [h]'s capital's dressing as its places lay it out: the doors on the main street (the avenue in from the south gate
     * to the plaza) and round the market square, each with its garland, its pot and (the avenue's paired across it, the
     * square's to the avenue's nearest lamp) its string of pennants; and the lanterns zig-zagging over the square from
     * lamp to lamp along the avenue. None within [CLEAR] blocks of the fountain, where a festival dresses. Worked out from
     * the plan alone: whether there's room for each is looked at as it goes up.
     */
    private fun lay(h: Scenes.Here): List<Piece> {
        val capital = h.capital
        val places = h.places
        val plan = places.census.plan
        val fountain = capital.standing(plan, plan.fountain)
        val clear = CLEAR.toDouble() * CLEAR
        val square = plan.market.grow(2)
        class Front(val home: Int, val door: CityPlaces.Door, val lx: Int, val lz: Int, val avenue: Boolean)

        val fronts = ArrayList<Front>()
        for (l in places.lodgings.values.sortedBy { it.id }) {
            if (l.farm) continue
            val door = l.front ?: continue
            if (!door.inward.axis.isHorizontal) continue
            val out = door.outside
            if (out.distSqr(fountain) <= clear) continue
            val (lx, lz) = capital.local(out.x, out.z)
            val onSquare = square.contains(lx, lz)
            val avenue = !onSquare && abs(lx) <= AVENUE + 1 && lz > plan.plaza && lz <= plan.bandOut + 2
            if (avenue || onSquare) fronts += Front(l.id, door, lx, lz, avenue)
        }
        val lamps = places.lamps.filter { p ->
            val (lx, lz) = capital.local(p.x, p.z)
            abs(lx) == AVENUE && plan.market.contains(lx, lz) && p.distSqr(fountain) > clear
        }
        val out = ArrayList<Piece>()
        for (f in fronts) {
            val d = f.door
            val inward = d.inward
            val side = inward.clockWise
            val outward = inward.opposite
            val seed = d.pos.hashCode()
            // The garland over the door, from a ladder set against the wall to its right
            val garland = d.outside.above(2)
            val ladder = d.outside.relative(side)
            val tie = Vec3(0.5 + inward.stepX * TIE_IN - side.stepX * GARLAND_HALF, 0.32, 0.5 + inward.stepZ * TIE_IN - side.stepZ * GARLAND_HALF)
            val span = Vec3(side.stepX * GARLAND_HALF * 2, 0.0, side.stepZ * GARLAND_HALF * 2)
            out += Piece(
                Kind.GARLAND, garland, f.home, ladder.relative(outward), ladder, outward, 1,
                Vec3.atLowerCornerOf(garland).add(tie).add(span.scale(0.5)).add(0.0, -0.2, 0.0), tie, span, null, seed,
            )
            // The doorstep's pot, on the other side of the door
            val pot = d.outside.relative(side.opposite)
            out += Piece(Kind.POT, pot, f.home, d.outside, null, side.opposite, 0, Vec3.atCenterOf(pot).add(0.0, -0.3, 0.0), null, null, null, seed * 31 + 7)
            // The pennants, across the avenue to the door opposite (once a pair), or from the square's doors to a lamp
            val eave = d.outside.above(3)
            val start = Vec3(eave.x + 0.5 + inward.stepX * TIE_IN, eave.y + 0.6, eave.z + 0.5 + inward.stepZ * TIE_IN)
            val end: Vec3? = if (f.avenue) {
                if (f.lx >= 0) null else fronts.filter { it.avenue && it.lx > 0 && abs(it.lz - f.lz) <= PAIR }.minByOrNull { abs(it.lz - f.lz) }?.let { o ->
                    val e = o.door.outside.above(3)
                    Vec3(e.x + 0.5 + o.door.inward.stepX * TIE_IN, e.y + 0.6, e.z + 0.5 + o.door.inward.stepZ * TIE_IN)
                }
            } else {
                places.lamps.filter { p -> capital.local(p.x, p.z).let { (lx, lz) -> abs(lx) <= AVENUE && plan.market.grow(4).contains(lx, lz) } }
                    .minByOrNull { it.distSqr(eave) }?.takeIf { it.distSqr(eave) <= TO_LAMP * TO_LAMP }
                    ?.let { Vec3(it.x + 0.5, it.y + 1.1, it.z + 0.5) }
            }
            if (end != null && end.distanceTo(start) in 2.0..LONGEST) {
                val high = d.outside.relative(side)
                out += Piece(
                    Kind.BUNTING, eave, f.home, high.relative(outward), high, outward, 2, start.add(0.0, -0.1, 0.0),
                    start.subtract(Vec3.atLowerCornerOf(eave)), end.subtract(start), null, seed * 17 + 3,
                )
            }
        }
        // The lanterns: across the avenue and back from lamp to lamp down the square
        val left = lamps.filter { capital.local(it.x, it.z).first < 0 }.sortedBy { capital.local(it.x, it.z).second }
        val right = lamps.filter { capital.local(it.x, it.z).first > 0 }.sortedBy { capital.local(it.x, it.z).second }
        val zig = ArrayList<Pair<BlockPos, BlockPos>>()
        for (i in left.indices) {
            right.getOrNull(i)?.let { zig += left[i] to it }
            left.getOrNull(i + 1)?.let { next -> right.getOrNull(i)?.let { zig += it to next } }
        }
        for ((a, b) in zig) {
            val top = a.above()
            val from = Vec3(0.5, 0.1, 0.5)
            val span = Vec3(b.x - a.x.toDouble(), b.y - a.y.toDouble(), b.z - a.z.toDouble())
            if (span.length() > LONGEST || span.length() < 2.0) continue
            // The ladder against the lamp's post, on the avenue's side of it
            val (lx, _) = capital.local(a.x, a.z)
            val toMiddle = capital.direction(if (lx > 0) Dir.W else Dir.E)
            val ground = a.below(3)
            val ladder = ground.relative(toMiddle)
            out += Piece(
                Kind.LANTERNS, top, -1, ladder.relative(toMiddle), ladder, toMiddle, 3,
                Vec3(top.x + 0.5, top.y + 0.1, top.z + 0.5), from, span, a, a.hashCode(),
            )
        }
        return out
    }

    // ---- The streets command ----

    /** `/guildmark streets dress <how>`: [column]'s dressing up as in good or thriving times, or down, now; or cleared at once. */
    fun force(level: ServerLevel, column: Long, how: Int): Int {
        Streets.testing = true
        val data = StreetsData.get(level)
        val town = data.town(column)
        town.force = how
        data.changed()
        crews[column]?.let {
            it.retry.clear()
            it.waiting = -1L
        }
        return town.run.toInt()
    }

    /** `/guildmark streets dress clear`: the command's dressing let go, and every piece of it about [column] put back at once. */
    fun clear(level: ServerLevel, column: Long, center: BlockPos, reach: Int): Int {
        val data = StreetsData.get(level)
        val town = data[column] ?: return 0
        town.force = StreetsData.FORCE_NONE
        if (!Fortunes.enabled()) {
            town.dress = StreetsData.NONE
            town.lanterns = StreetsData.NONE
        }
        data.changed()
        crews.remove(column)
        val site = Streets.site(column, Streets.DRESSING)
        var n = 0
        for (pos in io.github.vaspyyy.guildmark.festival.FestivalRestores.recordsOf(level, site, center, reach)) {
            if (Fitting.ladders.contains(pos.asLong())) continue
            Streets.restore(level, pos, column, Streets.DRESSING)
            n++
        }
        return n
    }

    /** For the streets command: what [column]'s dressing is doing, in a few words. */
    fun status(level: ServerLevel, column: Long): String {
        val t = StreetsData.get(level)[column] ?: return "never dressed"
        val crew = crews[column]
        val state = when (t.dress) {
            StreetsData.UP -> "up"
            StreetsData.DOWN -> "coming down"
            else -> "not up"
        }
        val lanterns = when (t.lanterns) {
            StreetsData.UP -> ", lanterns up"
            StreetsData.DOWN -> ", lanterns coming down"
            else -> ""
        }
        val why = when {
            forced(t) -> " (the streets command's ${listOf("", "good", "thriving", "down").getOrElse(t.force) { "" }})"
            t.goodFrom >= 0 -> " (good times since market day ${t.goodFrom})"
            t.fellOn >= 0 -> " (the times fell back on market day ${t.fellOn})"
            else -> ""
        }
        val pieces = crew?.pieces?.size?.let { ", $it pieces laid out, ${crew.rounds} rounds out" } ?: ""
        return "dressing #${t.run} $state$lanterns$why$pieces"
    }

    fun forget() = crews.clear()

    // ---- Numbers ----

    /** The parts of a dressing, as its runs carry them: its pieces, its lanterns, the ladders set up for it. */
    const val PIECES = 0
    const val LANTERN_PART = 1
    const val LADDER = 2

    /** Market days the times must have been good for it to go up, and fallen back for it to come down. */
    private const val HELD = 2L
    private const val LONG_AGO = -1_000_000L

    /** How far from the fountain the dressing stays (a festival dresses within about 35 blocks of it). */
    const val CLEAR = 40

    /** The avenue's half-width in the city's frame (its lamps stand at its edges). */
    private const val AVENUE = 3

    /** Doors across the avenue paired within this many blocks along it, a square's door to a lamp this near, and the longest string. */
    private const val PAIR = 3
    private const val TO_LAMP = 18.0
    private const val LONGEST = 18.0

    /** A garland's half-width over the door, and how near the wall its ties are (from the block's middle). */
    private const val GARLAND_HALF = 0.85
    private const val TIE_IN = 0.44

    /** The lantern strings' light while their lamps burn. */
    private const val LIT = 11

    /** Rounds out at once in a capital, the most pieces in one, and how near one another they are. */
    private const val ROUNDS = 3
    private const val ROUND = 4
    private const val NEAR = 14.0

    /** Ticks work in sight waits for someone free before anyone about will do; ticks before a piece that wouldn't go up is tried again. */
    private const val LOOSE = 600L
    private const val RETRY = 6000L
    private const val RETRY_ROUND = 1200L

    /** Ticks between looks at which homes are lived in. */
    private const val HOMES_EVERY = 600L
}
