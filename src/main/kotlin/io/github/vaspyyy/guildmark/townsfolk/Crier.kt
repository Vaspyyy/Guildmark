package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity.Slip
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.MarketBoards
import io.github.vaspyyy.guildmark.economy.MarketCommand
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.News
import io.github.vaspyyy.guildmark.economy.OrderState
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.Quote
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.guild.NewsItem
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.talk.TownCry
import io.github.vaspyyy.guildmark.village.live.talk.Bellman
import io.github.vaspyyy.guildmark.village.live.talk.VillageCry
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.phys.Vec3
import java.util.Random
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln

/**
 * The town crier: one of a capital's citizens (see [Hands]), who keeps its market board ([MarketBoards]). First thing
 * on a working morning they chalk up the prices that changed overnight. At eleven they're off to the square with
 * the day's news on a scroll, reading it over; at noon on the dot they ring the handbell and cry it, a line at a
 * time, while the folk about the square gather round to hear (see [Hear]); then they pin it up on the board, hang a
 * ticket on its rail for each new order, write the prices up again, and get their lunch. What they cry is what really
 * happened: the market's own news since the noon before (trouble or luck starting, trouble over), whether the town
 * went hungry, the orders its businesses posted and those filled, the prices that stand furthest off their usual and
 * why, and the guild's news of the day before. Every line is voiced; what goes with it (whose deed, which village,
 * who wants how many) is in the chat of the players about the square and on the slip pinned up for it.
 */
object Crier {
    /** How far the crier's voice carries, and how near the crier a player must be to have what's cried in chat. */
    const val HEARD = 48.0
    private const val CHAT = 32.0

    /** Ticks given each thing cried (every voiced line is shorter), and how long its words show over the crier's head. */
    private const val LINE = 100
    private const val SHOWN = 92L

    /** Ticks of the handbell before the news, and after it. */
    private const val BELL = 60
    private const val BELL_AFTER = 40

    /**
     * Ticks of a great bell ([GreatBell]) before the news and after it, and the strokes: three before (one each [STRUCK] ticks, the
     * first [STRIKE] in), one after, the bell left to hum out between. The hand meets the bell on the strike clip's tick 6 of each
     * 30 (ring_great_bell, art/scripts/village_clips.py), so the strokes fall on its strikes, and the ringing ends as a strike is
     * done, before the hand would draw back for one not struck.
     */
    private const val GREAT_BEFORE = 90
    private const val GREAT_AFTER = 30
    private const val STRUCK = 30
    private const val STRIKE = 6
    private const val STROKES = 3

    /** Ticks the chalk takes over a line. */
    private const val STROKE = 14

    /** The most things cried between the opening and the close; the most prices among them, and the most of the guild's news. */
    private const val MOST = 7
    private const val PRICES = 2
    private const val WORD = 2

    /** How near the crier folk come to listen from, and how many at most. */
    private const val GATHER = 20.0
    private const val CROWD = 12

    /** The town went hungry when this share of its meals went uneaten for want of food yesterday, and at least so many. */
    private const val HUNGRY = 0.05
    private const val HUNGRY_MEALS = 3.0

    /** How many days ahead the crier starts telling of a festival. */
    private const val FESTIVAL_AHEAD = 2

    /** The most new orders cried, and the most suppliers named for orders filled. */
    private const val WANTED = 2
    private const val FILLED = 3

    /** The most goods a waylaid wagon's slip names as short. */
    private const val SHORT = 5

    private const val EVENT_NEWS = "news.guildmark.market.event."
    private const val OVER_NEWS = "news.guildmark.market.over."

    /**
     * One thing cried: the voiced [line] (a key under say.guildmark.), what's sent to the players about ([text]), the
     * slip pinned up for it, if any, and how the crowd takes it ([tone]: 1 gladly, -1 gravely, 0 either way).
     */
    class Cry(val line: String, val text: Component, val slip: Slip?, val tone: Int)

    /**
     * How far a crier is through the news: the thing being cried ([index]; -1 while the bell rings first) and how it's taken; and
     * the way they face to cry it ([facing], the yaw), which the folk gather in front of (a village's bellman turns to the great
     * bell to strike it, and back to the green to cry).
     */
    class Crying(var index: Int, var tone: Int, val facing: Float)

    private val crying = WeakHashMap<Townsperson, Crying>()

    /** How far [crier] is through crying the news, if they're at it now. */
    fun state(crier: Townsperson): Crying? {
        val state = crying[crier] ?: return null
        if (!crier.isAlive || crier.activity != Activity.RING_BELL && crier.activity != Activity.ANNOUNCE) return null
        return state
    }

    /** The way [crier] faces to cry (where the folk gather in front of them): the crying's own, else the way they face now. */
    internal fun facing(crier: Townsperson): Float = crying[crier]?.facing ?: crier.yRot

    /** A crier crying the news within earshot of [p], if there is one. */
    fun near(p: Townsperson): Townsperson? =
        crying.keys.firstOrNull { it !== p && it.level() === p.level() && it.distanceToSqr(p) < GATHER * GATHER && state(it) != null }

    /** Where the crier stands to cry: two steps out in front of the board, looking the way it does. */
    fun cryingSpot(places: CityPlaces): Spot = places.marketBoard.let { Spot(it.pos.relative(it.facing, 2), it.facing) }

    /** Where the crier stands at the board to write on it, facing it. */
    fun writingSpot(places: CityPlaces): Spot = places.marketBoard.let { Spot(it.pos.relative(it.facing), it.facing.opposite) }

    private fun level(p: Townsperson): ServerLevel = p.level() as ServerLevel
    private fun conditions(level: ServerLevel): Conditions = Conditions(Markets.hourOf(level))

    /** The words of the voiced [line]. */
    internal fun said(line: String): MutableComponent = Component.translatable("say.guildmark.$line")

    /**
     * The crier's part of a CRY stretch: before eleven the morning's prices (if the board needs them), after it the
     * noon round; null once that's done for the day. [now]: the noon round at once, for testing, not waiting for noon.
     */
    fun round(p: Townsperson, life: Life, now: Boolean = false): Task? {
        // A village's: its bellman's round, at its great bell (living-world/villages/design.md 8.2)
        if (life.places.kind == TownKind.VILLAGE) return Bellman.round(p, life, now)
        val level = level(p)
        val places = life.places as? CityPlaces ?: return null
        val c = conditions(level)
        val entry = Trades.market(p, life)
        val board = MarketBoards.board(level, places)
        if (c.clock < 11 && !now) {
            if (life.chalked == c.day) return null
            life.chalked = c.day
            if (entry == null || board == null) return null
            // Nothing to write up and no new orders to hang
            if (MarketBoards.current(board, c.day to false) && MarketBoards.unhung(level, board, places.centre).isEmpty()) return null
            return chalk(p, life, noon = false, slips = listOf())
        }
        if (!now) {
            if (life.cried == c.day) return null
            life.cried = c.day
        }
        val cries = today(level, places, entry, c)
        val spot = cryingSpot(places)
        val crowd = Vec3.atBottomCenterOf(spot.pos.relative(spot.facing, 6)).add(0.0, 1.5, 0.0)
        val work = crying(p, spot, crowd, cries, now)
        val crier = Acts.via(p, life, spot.pos, work)
        if (entry == null || board == null) return crier
        return crier.andThen(Later(p) { chalk(p, life, noon = true, slips = cries.mapNotNull { it.slip }) })
    }

    /**
     * A great bell rung for the cry in place of the crier's handbell ([crying]): the bell at [pos], struck [STROKES] times before the
     * word and once after it by whoever cries, facing it as they pull; [length] gives each thing cried its own time (the line's and a
     * breath), and [done] is told once the word is cried to the end.
     */
    internal class GreatBell(val pos: BlockPos, val length: (Cry) -> Int, val done: () -> Unit = {})

    /**
     * The noon cry itself, at [spot] looking toward the [crowd]: until the hour (unless [now]) the news read over from the scroll, a
     * look about between whiles; then the bell, [cries] a line at a time with the folk about drawn in to hear ([gather]), the bell
     * again, and everyone about knowing what was cried ([TownCry.listened]). A capital's crier rings their handbell; with a [great]
     * bell, its own strokes ring out (the ringer is flagged to the client, [Townsperson.greatBell], which draws no handbell).
     */
    internal fun crying(p: Townsperson, spot: Spot, crowd: Vec3, cries: List<Cry>, now: Boolean, great: GreatBell? = null): Work {
        val level = level(p)
        var waiting = !now
        var base = 0
        var unrolled = false
        var said = 0
        val after = if (great != null) GREAT_AFTER else BELL_AFTER
        return Work(p, spot, crowd, plan = { i ->
            if (waiting && conditions(level).clock < MarketBoards.NOON) {
                // Reading the news over until the hour, a look about between whiles
                if (i % 2 == 0) Step(Activity.READ, 120) else Step(Activity.NONE, 60)
            } else {
                if (waiting) {
                    waiting = false
                    base = i
                }
                val j = i - base
                when {
                    j == 0 -> Step(Activity.RING_BELL, if (great != null) GREAT_BEFORE else BELL)
                    j <= cries.size -> Step(Activity.ANNOUNCE, great?.length?.invoke(cries[j - 1]) ?: LINE)
                    j == cries.size + 1 -> Step(Activity.RING_BELL, after)
                    else -> null
                }?.also { if (great != null) p.greatBell = true }
            }
        }, onTick = { s, t ->
            val mouth = p.eyePosition
            // The scroll comes out the first time it's wanted: to read it over, or (come late) to cry from; at a great bell, once it's rung
            val cue = if (great != null) Activity.ANNOUNCE else Activity.RING_BELL
            if (t == 0 && !unrolled && (s.activity == Activity.READ || s.activity == cue)) {
                unrolled = true
                sound(level, mouth, "unroll", 0.7f)
            }
            when (s.activity) {
                Activity.RING_BELL -> {
                    if (great != null) strike(p, great, t, if (said == 0) STROKES else 1)
                    if (t == 0 && said == 0) {
                        crying[p] = Crying(-1, 0, spot.yaw)
                        gather(p)
                    } else if (said >= cries.size && t == after - 1) {
                        crying.remove(p)
                        TownCry.listened(level, p)
                        sound(level, mouth, "roll_up", 0.7f)
                        great?.done?.invoke()
                    }
                }
                Activity.ANNOUNCE -> if (t == 0 && said < cries.size) {
                    val cry = cries[said]
                    crying.getOrPut(p) { Crying(said, cry.tone, spot.yaw) }.also {
                        it.index = said
                        it.tone = cry.tone
                    }
                    said++
                    p.say(cry.line, force = true, hearing = HEARD, ticks = great?.let { it.length(cry) - 8L } ?: SHOWN)
                    tell(level, p, cry.text)
                }
                else -> {}
            }
        })
    }

    /** [t] ticks into a ringing of [great], [p] faces the bell and pulls, striking it on the first of [strokes] strokes' ticks. */
    private fun strike(p: Townsperson, great: GreatBell, t: Int, strokes: Int) {
        val level = level(p)
        val bell = Vec3.atCenterOf(great.pos)
        p.face(p.yawTo(bell))
        p.lookAt(bell)
        if (t < STRIKE || (t - STRIKE) % STRUCK != 0 || (t - STRIKE) / STRUCK >= strokes) return
        val from = Direction.getApproximateNearest(bell.x - p.x, 0.0, bell.z - p.z)
        if (level.isLoaded(great.pos)) (level.getBlockState(great.pos).block as? BellBlock)?.attemptToRing(level, great.pos, from)
    }

    /**
     * The board written up: at noon yesterday's slips taken down and [slips] pinned up, a ticket hung on the rail for
     * each order that hasn't one, then the lines that have changed chalked in, one stroke at a time (in the morning,
     * a word to the street once it's done). Null if there's no board or market to write up.
     */
    private fun chalk(p: Townsperson, life: Life, noon: Boolean, slips: List<Slip>): Task? {
        val places = life.places as? CityPlaces ?: return null
        return chalk(p, life, places.marketBoard, { MarketBoards.town(places.capital) }, places.centre, noon, slips)
    }

    /**
     * As [chalk], at the board whose anchor stands at [at] (facing its way), headed with the town's [name], in the town whose heart
     * is [capital] (its orders' tickets): a capital's, or the market board at a village's Hall door that its bellman keeps.
     */
    internal fun chalk(p: Townsperson, life: Life, at: Spot, name: () -> Component, capital: BlockPos, noon: Boolean, slips: List<Slip>): Task? {
        val level = level(p)
        val entry = Trades.market(p, life) ?: return null
        if (MarketBoards.board(level, at.pos) == null) return null
        crying.remove(p)
        val spot = Spot(at.pos.relative(at.facing), at.facing.opposite)
        val slate = Vec3.atCenterOf(at.pos).add(0.0, 0.6, 0.0)
        val rail = Vec3.atBottomCenterOf(at.pos).add(0.0, 0.3, 0.0)
        var todo: List<Int>? = null
        var orders: List<Int> = listOf()
        var pinned = 0
        var written = 0
        val plan = plan@{ i: Int ->
            val board = MarketBoards.board(level, at.pos) ?: return@plan null
            val list = todo ?: run {
                val c = conditions(level)
                board.begin(name(), c.day, noon)
                if (noon) board.unpin()
                orders = MarketBoards.unhung(level, board, capital).map { it.order }
                val at = level.gameTime
                MarketBoards.ORDER.indices.filter { k -> !MarketBoards.line(entry, MarketBoards.ORDER[k], at).same(board.lines.getOrNull(k)) }
                    .also { todo = it }
                    .also { if (it.isEmpty()) board.finish() }
            }
            val pins = slips.size + orders.size
            when {
                i < pins -> Step(Activity.HAND_OVER)
                i == pins -> if (list.isNotEmpty()) Step(Activity.SCRIBE, STROKE * list.size + 8) else Step(Activity.NONE, 10)
                i == pins + 1 && !noon -> Step(Activity.NONE, 40, turned = true)
                else -> null
            }
        }
        return Acts.via(p, life, spot.pos, Work(p, spot, slate, plan, onTick = { s, t ->
            val board = MarketBoards.board(level, at.pos)
            if (board != null) when (s.activity) {
                Activity.HAND_OVER -> if (t == 16 && pinned < slips.size) {
                    val slip = slips[pinned++]
                    board.pin(Slip(slip.kind, slip.headline, slip.text, level.gameTime, slip.works))
                    sound(level, slate, "pin", 0.8f)
                } else if (t == 16 && pinned < slips.size + orders.size) {
                    // The order's ticket, if it's still wanted and nobody's hung it meanwhile
                    if (MarketBoards.hang(level, board, capital, orders[pinned - slips.size])) sound(level, rail, "pin", 0.7f)
                    pinned++
                }
                Activity.SCRIBE -> {
                    val list = todo.orEmpty()
                    if (t % STROKE == 3 && written < list.size) {
                        val k = list[written++]
                        board.chalk(k, MarketBoards.line(entry, MarketBoards.ORDER[k], level.gameTime))
                        sound(level, slate, "chalk", 0.6f)
                    }
                    if (t == STROKE * list.size + 6) {
                        board.finish()
                        sound(level, slate, "chalk_tap", 0.6f)
                    }
                }
                // The prices are up; or, with none changed, the new orders
                Activity.NONE -> if (s.turned && t == 0) {
                    p.say(if (todo.isNullOrEmpty() && orders.isNotEmpty()) "crier_board_orders" else "crier_board", force = true, hearing = HEARD, ticks = SHOWN)
                }
                else -> {}
            }
        }))
    }

    /** What [places]' crier cries at [c]'s noon: the same each time it's asked that day (see [news]); a village's bellman's word ([VillageCry]). */
    fun today(level: ServerLevel, places: TownPlaces, entry: Markets.Entry?, c: Conditions): List<Cry> {
        if (places.kind == TownKind.VILLAGE) return VillageCry.word(level, places, entry, c).cries
        return news(level, entry, c, Random(c.day * 0x2545F4914F6CDD1DL + places.centre.asLong()))
    }

    /** The folk about the square who are free to stop come over to hear the news. */
    internal fun gather(crier: Townsperson) {
        val level = level(crier)
        val folk = level.getEntitiesOfClass(Townsperson::class.java, crier.boundingBox.inflate(GATHER, 4.0, GATHER)) {
            it !== crier && it.isAlive && !it.isSleeping && it.attending == null && !it.routine.anchored &&
                it.routine.task.let { task -> task == null || task.leisure && task !is Hear }
        }.sortedBy { it.distanceToSqr(crier) }.take(CROWD)
        for (f in folk) if (crier.random.nextInt(10) < 7) f.routine.replace(Hear(f, crier))
    }

    /** What's cried goes out to the players about the square, in chat, with the crier's name. */
    internal fun tell(level: ServerLevel, crier: Townsperson, text: Component) {
        val message = Component.translatable("chat.guildmark.crier", crier.displayName.copy().withStyle(ChatFormatting.GOLD), text.copy().withStyle(ChatFormatting.WHITE))
        for (player in level.players()) {
            if (!player.isSpectator && player.distanceToSqr(crier) < CHAT * CHAT) player.sendSystemMessage(message)
        }
    }

    private fun sound(level: ServerLevel, at: Vec3, name: String, volume: Float) {
        val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.crier.$name")
        level.playSound(null, at.x, at.y, at.z, Holder.direct(SoundEvent.createVariableRangeEvent(id)), SoundSource.NEUTRAL, volume, 0.92f + level.random.nextFloat() * 0.16f)
    }

    // ---- The news ----

    /**
     * Everything to cry at [c]'s noon, opening and close included: the market's news since the noon before, hunger,
     * the new orders and those filled, a new season or a festival at hand, the prices furthest off their usual with
     * why, the guild's news of the day before, and last the town's works and its times, at most [MOST] between the
     * opening and the close; if there's none, that the market is well and prices fair.
     */
    fun news(level: ServerLevel, entry: Markets.Entry?, c: Conditions, random: Random): List<Cry> {
        val at = level.gameTime
        val groups = ArrayList<List<Cry>>()
        val cried = HashSet<EventKind>()
        if (entry != null) {
            val m = entry.market
            // The hour of this day's noon (see Conditions: the day starts at two, ten hours before noon)
            val noon = c.day * 24 + 6
            for (n in m.news) {
                if (n.hour <= noon - 24 || n.hour > noon) continue
                val (cry, kind) = market(n, at, m) ?: continue
                if (cried.add(kind) || kind == EventKind.BLIGHT || kind == EventKind.BUMPER) groups.add(listOf(cry))
            }
            // The roads' news: a road gone worse, a band broken and the relief on its way, the road clear, salvage brought back
            road(level, entry, noon, at, cried).forEach { groups.add(it) }
            // Hungry: a twentieth of the day's meals or more went uneaten for want of food
            if ((m.hungry.lastOrNull() ?: 0.0) >= maxOf(HUNGRY_MEALS, entry.town.people * Economy.MEALS * HUNGRY)) {
                groups.add(listOf(Cry("crier_hungry", said("crier_hungry"), slip(Slip.Kind.TROUBLE, Component.translatable("board.guildmark.slip.hungry"), said("crier_hungry"), at), -1)))
            }
            orders(level, entry, noon, random).forEach { groups.add(listOf(it)) }
            // The trader's (living-world/trader, part C): the haul tickets up on the Haul Board, and the town's thanks to those who brought goods in
            io.github.vaspyyy.guildmark.trade.TradeCries.hauls(level, entry, noon, random)?.let { groups.add(listOf(it)) }
            io.github.vaspyyy.guildmark.trade.TradeCries.thanks(level, entry, noon).forEach { groups.add(listOf(it)) }
            io.github.vaspyyy.guildmark.trade.TradeCries.house(level, entry)?.let { groups.add(listOf(it)) }
        }
        calendar(level, c, at)?.let { groups.add(it) }
        // The talk of the town: the day's hottest rumour, its details on the slip
        if (entry != null) TownCry.cry(level, entry.capital, c.day)?.let { groups.add(listOf(it)) }
        // A wagon waylaid is cried as the market's news: the guild's news of the band's crime isn't cried again
        val word = GuildNews.get(level).items.filter { it.day == VillageBoards.day(level) - 1 }
            .filter { EventKind.WAYLAID !in cried || it.key != "news.guildmark.outlaw.caravan" }
            .take(WORD).map { listOf(word(it, at)) }
        word.firstOrNull()?.let { groups.add(it) }
        if (entry != null) {
            MarketBoards.ORDER.map { Trading.quote(entry, it) }
                .filter { q -> q.why != null && (q.event == null || q.event !in cried) }
                .sortedByDescending { abs(ln(it.price / it.usual)) }
                .take(PRICES)
                .forEach { groups.add(price(it, at)) }
        }
        word.drop(1).forEach { groups.add(it) }
        // The town's works and its times come last, with room kept for them (see Fortunes.cries)
        val last = if (entry != null) Fortunes.cries(level, entry, c, at) + io.github.vaspyyy.guildmark.tales.Tales.cries(level, entry, c, at) else listOf()
        val chosen = ArrayList<Cry>()
        for (g in groups) if (chosen.size + g.size <= MOST - last.size) chosen.addAll(g)
        chosen.addAll(last)
        if (chosen.isEmpty()) chosen.add(Cry("crier_quiet", said("crier_quiet"), null, 1))
        val open = "crier_open_${random.nextInt(3)}"
        val close = if (random.nextInt(2) == 0) "crier_close_1" else "crier_close_${random.nextInt(2) * 2}"
        return listOf(Cry(open, said(open), null, 0)) + chosen + Cry(close, said(close), null, 0)
    }

    /**
     * The calendar's news at [c]'s noon: a new season on its first day, and the festival today or in the next
     * [FESTIVAL_AHEAD] days. Null if there's none.
     */
    internal fun calendar(level: ServerLevel, c: Conditions, at: Long): List<Cry>? {
        val date = Seasons.dateOn(level.server, Math.floorDiv(c.hour, 24L))
        val cries = ArrayList<Cry>()
        if (Seasons.enabled() && date.day == 1) {
            val line = "crier_season_${date.season.id}"
            val about = Component.translatable("season.guildmark.banner.${date.season.id}")
            cries.add(Cry(line, said(line).append(" ").append(about.copy().withStyle(ChatFormatting.GRAY)), slip(Slip.Kind.GOOD, date.season.title, about, at), 1))
        }
        if (Seasons.festivalsEnabled()) {
            val (kind, days) = FestivalCalendar.next(date)
            if (days <= FESTIVAL_AHEAD) {
                val line = if (days == 0) "crier_festival_today" else "crier_festival_soon"
                val time = when (days) {
                    0 -> kind.title
                    1 -> Component.translatable("season.guildmark.tomorrow", kind.title)
                    else -> Component.translatable("season.guildmark.soon", kind.title, days)
                }
                val about = Component.translatable("festival.guildmark.${kind.id}.about")
                cries.add(Cry(line, said(line).append(" ").append(time.copy().withStyle(ChatFormatting.GRAY)), slip(Slip.Kind.GOOD, time, about, at), 1))
            }
        }
        return cries.ifEmpty { null }
    }

    /** A slip of [kind] for the board, pinned at [at]. */
    internal fun slip(kind: Slip.Kind, headline: Component, text: Component, at: Long): Slip = Slip(kind.id, headline, text, at)

    /**
     * The orders at [entry]'s capital since the noon before market hour [noon]: up to [WANTED] newly posted and still
     * open (the biggest first), each cried with who wants how many and about what it pays; and one cry for those
     * filled, naming up to [FILLED] suppliers. None while orders are off; their tickets go on the rail, not slips.
     */
    private fun orders(level: ServerLevel, entry: Markets.Entry, noon: Long, random: Random): List<Cry> {
        if (!Suppliers.enabled()) return listOf()
        val book = Orders.get(level)[entry.capital] ?: return listOf()
        val out = ArrayList<Cry>()
        for (o in book.orders.filter { it.state == OrderState.OPEN && it.posted > noon - 24 && it.posted <= noon }.sortedByDescending { it.worth }.take(WANTED)) {
            val line = "crier_wanted_${o.good.id}"
            val pay = Orders.marks(floor(Orders.each(o.basis, 0.0, standing = false) * o.amount + 1e-6).toInt())
            val about = Component.translatable("chat.guildmark.crier.wanted", Orders.shopName(o.shop), Orders.what(o), pay)
            out.add(Cry(line, said(line).append(" ").append(about.withStyle(ChatFormatting.GRAY)), null, 1))
        }
        val filled = book.fills.filter { it.hour > noon - 24 && it.hour <= noon }
        if (filled.isNotEmpty()) {
            val line = "crier_order_filled_${random.nextInt(2)}"
            val text = said(line)
            for ((k, f) in filled.take(FILLED).withIndex()) {
                val what = Component.translatable("supplier.guildmark.what", f.count, Component.translatable(f.good.key + ".many"))
                text.append(Component.literal(if (k == 0) " " else "; ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("chat.guildmark.crier.filled", f.who, what, Orders.shopName(f.shop)).withStyle(ChatFormatting.GRAY))
            }
            out.add(Cry(line, text, null, 1))
        }
        return out
    }

    /**
     * The roads' news at [entry]'s capital since the noon before market hour [noon] (see [RoadPeril]), each a group cried
     * together: each road's latest stage if it went worse; a band broken, with the relief wagon on its way; a road clear
     * again of itself (unless the wagons getting through is [cried] already); and those who brought salvage back, named.
     */
    private fun road(level: ServerLevel, entry: Markets.Entry, noon: Long, at: Long, cried: Set<EventKind>): List<List<Cry>> {
        if (!Caravans.enabled()) return listOf()
        val capital = entry.market.capital
        val news = CaravanData.get(level).capitalOrNull(capital)?.news?.filter { it.hour > noon - 24 && it.hour <= noon } ?: return listOf()
        if (news.isEmpty()) return listOf()
        val out = ArrayList<List<Cry>>()
        val relief = news.lastOrNull { it.kind == "relief" }
        // Each road's latest stage, once (the news is kept in the order it came, so two in an hour keep theirs)
        val stages = news.filter { RoadPeril.Stage.entries.any { s -> s.id == it.kind } || it.kind == "clear" }
            .groupBy { it.road }.values.map { it.last() }.sortedBy { it.hour }
        for (n in stages) {
            val way = road(capital, n.road)
            when (n.kind) {
                // A band broken is cried with its relief wagon, below
                "clear" -> if (relief == null && EventKind.WAYLAID !in cried) {
                    val text = Component.translatable("chat.guildmark.crier.road.clear", way)
                    out.add(listOf(Cry("crier_over_waylaid", detailed("crier_over_waylaid", text), slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.road.clear"), text, at), 1)))
                }
                RoadPeril.Stage.OPEN.id -> {}
                else -> {
                    val band = band(n.names)
                    val text = if (band != null) Component.translatable("chat.guildmark.crier.road.${n.kind}.band", way, band) else Component.translatable("chat.guildmark.crier.road.${n.kind}", way)
                    val line = "crier_road_${n.kind}"
                    out.add(listOf(Cry(line, detailed(line, text), slip(Slip.Kind.TROUBLE, Component.translatable("board.guildmark.slip.road.${n.kind}"), text, at), -1)))
                }
            }
        }
        if (relief != null) {
            val way = road(capital, relief.road)
            val band = band(relief.names)
            val broken = if (band != null) Component.translatable("chat.guildmark.crier.road.broken.band", band, way) else Component.translatable("chat.guildmark.crier.road.broken", way)
            val coming = Component.translatable("chat.guildmark.crier.road.relief", way)
            out.add(listOf(
                Cry("crier_road_clear", detailed("crier_road_clear", broken), slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.road.broken"), broken, at), 1),
                Cry("crier_relief", detailed("crier_relief", coming), slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.road.relief"), coming, at), 1),
            ))
        }
        // Everyone who brought salvage back since the noon before, named once each
        val finders = news.filter { it.kind == "salvage" }.flatMap { it.names }.distinct()
        if (finders.isNotEmpty()) {
            val text = Component.translatable("chat.guildmark.crier.road.salvage", finders.take(FILLED).joinToString(", "))
            out.add(listOf(Cry("crier_salvage_returned", detailed("crier_salvage_returned", text), slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.road.salvage"), text, at), 1)))
        }
        return out
    }

    /** The voiced [line]'s words, [detail] after it in grey. */
    internal fun detailed(line: String, detail: Component): MutableComponent = said(line).append(" ").append(detail.copy().withStyle(ChatFormatting.GRAY))

    /** The road [road] into the capital whose heart is [capital], by the gate it comes in at ("the north road"). */
    private fun road(capital: Long, road: Int): Component {
        val side = CaravanRoutes.cached(capital, road)?.side?.name?.lowercase() ?: "any"
        return Component.translatable("road.guildmark.way.$side")
    }

    /** A band's captain as road news names them ([names]: the name's key and the epithet's), if it does: "Red Maud, the Lantern Thief". */
    private fun band(names: List<String>): Component? {
        if (names.size < 2) return null
        return Component.translatable(Outlaws.FULL, Component.translatable(names[0]), Component.translatable(names[1]))
    }

    /** [goods] by name, one after another ("Cod, Candles, Iron"). */
    private fun names(goods: List<Good>): MutableComponent {
        val out = Component.empty()
        goods.forEachIndexed { k, g ->
            if (k > 0) out.append(", ")
            out.append(Component.translatable(g.key + ".many"))
        }
        return out
    }

    /** The market's piece of news [n] at [m], cried: trouble or luck starting, or trouble over; and which kind it is. */
    internal fun market(n: News, at: Long, m: Market): Pair<Cry, EventKind>? {
        val started = n.key.startsWith(EVENT_NEWS)
        if (!started && !n.key.startsWith(OVER_NEWS)) return null
        val kind = EventKind.byId(n.key.substringAfterLast('.')) ?: return null
        val good = n.good
        val text = if (good != null) Component.translatable(n.key, Component.translatable(good.key)) else Component.translatable(n.key)
        if (!started) {
            val line = "crier_over_${kind.id}"
            return Cry(line, said(line), slip(Slip.Kind.GOOD, Component.translatable("board.guildmark.slip.over.${kind.id}"), text, at), 1) to kind
        }
        if (kind == EventKind.WAYLAID) {
            // What's short now, as the waylaid wagons' trouble holds it (the news names only the dearest good), and where it was taken
            val e = m.waylaid()
            val goods = e?.goods?.sortedByDescending { it.base }?.take(SHORT) ?: listOfNotNull(good)
            val place = e?.place?.takeIf { it in Caravans.PLACES } ?: "road"
            val short = if (goods.isEmpty()) text else Component.translatable("board.guildmark.slip.waylaid.text", Component.translatable("road.guildmark.place.$place"), names(goods))
            val line = "crier_${kind.id}"
            return Cry(line, said(line).append(" ").append(short.copy().withStyle(ChatFormatting.GRAY)), slip(Slip.Kind.TROUBLE, Component.translatable("board.guildmark.slip.${kind.id}"), short, at), -1) to kind
        }
        val crop = good?.takeIf { kind == EventKind.BLIGHT || kind == EventKind.BUMPER }
        val line = if (crop != null) "crier_${kind.id}_${crop.id}" else "crier_${kind.id}"
        val headline = if (crop != null) Component.translatable("board.guildmark.slip.${kind.id}", Component.translatable(crop.key))
        else Component.translatable("board.guildmark.slip.${kind.id}")
        val kindOfSlip = if (kind.trouble) Slip.Kind.TROUBLE else Slip.Kind.GOOD
        return Cry(line, said(line), slip(kindOfSlip, headline, text, at), if (kind.trouble) -1 else 1) to kind
    }

    /** A price well off its usual, cried, and why (the trouble or luck behind it, or what the stalls say). */
    internal fun price(q: Quote, at: Long): List<Cry> {
        val why = q.why ?: return listOf()
        val dear = why.dear
        val line = (if (dear) "crier_dear_" else "crier_cheap_") + q.good.id
        val headline = Component.translatable(if (dear) "board.guildmark.slip.dear" else "board.guildmark.slip.cheap", Component.translatable(q.good.key + ".many"))
        val text = said(line).append(" ").append(MarketCommand.reason(q))
        val tone = if (dear) -1 else 1
        val first = Cry(line, said(line), slip(Slip.Kind.PRICE, headline, text, at), tone)
        val event = q.event
        val because = when {
            event == null -> "crier_why_${why.id}"
            // The trouble or luck itself, as it was cried when it began
            event == EventKind.BLIGHT || event == EventKind.BUMPER -> null
            else -> "crier_${event.id}"
        } ?: return listOf(first)
        return listOf(first, Cry(because, said(because), null, tone))
    }

    /** The guild's piece of news [item], cried: the voiced line for its kind, and the news itself after it in chat. */
    internal fun word(item: NewsItem, at: Long): Cry {
        val (kind, tone) = when (item.key.removePrefix("news.guildmark.")) {
            "field_boss" -> "slain" to 1
            "dragon_slain" -> "dragon" to 1
            "maelgrasp_slain" -> "sea" to 1
            "lair" -> "lair" to 1
            "promoted", "faction_done" -> "rank" to 1
            "advance" -> "works" to 1
            "road" -> "road" to 1
            "ambush", "outlaw.raid_beaten" -> "bandits" to 1
            // The trader's (living-world/trader, part E): a Hunt beaten off, on the road or in the wild
            "outlaw.hunt_beaten", "outlaw.hunt_beaten_wild" -> "bandits" to 1
            "siege_won" -> "siege_won" to 1
            "siege_lost" -> "siege_lost" to -1
            "outlaw.caravan", "outlaw.farm", "outlaw.barn", "outlaw.traveller", "outlaw.tithe", "outlaw.patrol", "outlaw.camp", "outlaw.raid" -> "outlaws" to -1
            "outlaw_slain" -> "outlaw_slain" to 1
            "outlaw.raid_taken" -> "taken" to -1
            "outlaw.rescued" -> "rescued" to 1
            "guild_founded" -> "guild" to 0
            "claimed", "lost_village", "rival_challenge", "rival_repelled" -> "banners" to 0
            "king_slain" -> "king" to 1
            "tale.wagon", "tale.informer", "tale.brother" -> "tale" to 1
            "story_done", "shard", "character_arrived", "faction_joined", "faction_expelled", "companion_sworn" -> "deeds" to 0
            else -> "more" to 0
        }
        val line = "crier_news_$kind"
        val news = item.text()
        val text = said(line).append(" ").append(news.copy().withStyle(ChatFormatting.GRAY))
        return Cry(line, text, slip(Slip.Kind.WORD, Component.translatable("board.guildmark.slip.word.$kind"), news, at), tone)
    }

    /** For testing: [p], the town's crier, goes to the square and cries the news now. False if they aren't the crier. */
    fun cryNow(p: Townsperson): Boolean {
        val life = Days.life(p) ?: return false
        if (!life.crier) return false
        val task = round(p, life, now = true) ?: return false
        p.routine.replace(task)
        return true
    }

    /** Where the crier stands to cry in [places]' square, for the commands. */
    fun spot(places: CityPlaces): BlockPos = cryingSpot(places).pos
}

/**
 * Comes over to hear the crier cry the news in the square: a few steps out in front of them, among the others,
 * listening, with a nod, a grave look or a cheer at what's cried; and once the bell rings off, a nod or a clap,
 * and on about their business.
 */
class Hear(p: Townsperson, private val crier: Townsperson) : Task(p) {
    private val walker = Walker(p)
    private var placed = false
    private var heard = -2
    private var over = false

    override fun start() {
        val ahead = Vec3.directionFromRotation(0f, Crier.facing(crier))
        val side = Vec3(-ahead.z, 0.0, ahead.x)
        val spot = crier.position().add(ahead.scale(3.0 + p.random.nextDouble() * 4.0)).add(side.scale(p.random.nextDouble() * 8.0 - 4.0))
        walker.go(BlockPos.containing(spot), 0.5, near = 1.2)
    }

    override fun run(): Boolean {
        val state = Crier.state(crier)
        if (state == null || over) {
            if (!over) {
                over = true
                p.act(if (p.role == TownRole.CHILD) Activity.JUMP else if (p.random.nextInt(3) == 0) Activity.CLAP else Activity.NOD)
                return true
            }
            return !p.actDone
        }
        if (!placed) {
            when (walker.tick()) {
                Walker.State.WALKING -> return age < 400
                else -> placed = true
            }
            p.act(Activity.LISTEN)
        }
        p.face(p.yawTo(crier.position()))
        p.lookAt(crier.eyePosition)
        if (state.index != heard) {
            heard = state.index
            if (state.index >= 0 && p.random.nextInt(5) < 2) react(state.tone)?.let { p.act(it) }
        }
        if (!p.activity.loop && p.actDone) p.act(Activity.LISTEN)
        return age < 2400
    }

    /** How [p] takes a piece of news cried in [tone]. */
    private fun react(tone: Int): Activity? = when {
        p.role == TownRole.CHILD -> if (tone > 0) Activity.JUMP else null
        tone > 0 -> if (p.random.nextInt(3) == 0) Activity.CHEER else Activity.NOD
        tone < 0 -> if (p.random.nextInt(3) == 0) Activity.SHRUG else Activity.NOD
        else -> Activity.NOD
    }

    override fun stop() {
        walker.stop()
        p.act(Activity.NONE)
    }
}
