package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.atlas.Atlas
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.outlaw.Captives
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.tales.Cast
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Gone
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Planned
import io.github.vaspyyy.guildmark.tales.Seeding
import io.github.vaspyyy.guildmark.tales.Step
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleCast
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleKeys
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleOption
import io.github.vaspyyy.guildmark.tales.TaleOptions
import io.github.vaspyyy.guildmark.tales.TaleReply
import io.github.vaspyyy.guildmark.tales.TaleSay
import io.github.vaspyyy.guildmark.tales.TaleShares
import io.github.vaspyyy.guildmark.tales.TaleTurn
import io.github.vaspyyy.guildmark.tales.TaleVoice
import io.github.vaspyyy.guildmark.tales.TaleWords
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.Template
import io.github.vaspyyy.guildmark.tales.Verb
import io.github.vaspyyy.guildmark.tales.View
import io.github.vaspyyy.guildmark.tales.Why
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * The Fourth Wagon (living-world/stories/design.md 8.3), Tales T3: a wagon bound for the town was waylaid on the road, and the band took
 * someone with it, the second hand who went along for the pay. The driver is back at the gate and tells it; the family waits at the same
 * gate. The band will take a price for the hand, or the hand can be fetched, or the watch can be asked to ride; and if nothing is done, the
 * band moves its captive on and the town marks the spot with a cairn.
 *
 * **The seed.** A strike the caravans' listener kept in the town's list (a wagon or a convoy of the timetable, waylaid by a band, in the last
 * two market days: the trading house's wagons and the test wagons are never kept there), the band built, at large and holding fewer than
 * `Captives.MAX`, and a household of two grown people free of a tale: one is the hand, the other their kin, and a guard of the town is the
 * captain. A forced start with no such strike makes one up on the road of a band that works it, and says so; the wagon it names has no driver.
 *
 * **The hand** is a resident the band holds as `Captives` holds anyone it takes ([WagonCamp.take]): their record's body is the one knelt by a
 * stake in the camp (`Captive.resident`), their day body is gone, and they are held in the cast with no mark, so their household cannot leave
 * from under them and no day body is made for them while the band has them. Cut loose (A) or let go by the band's rider (B), they keep close
 * behind whoever freed them and are home at the town's board (`Captives.homecoming`, which tells the tale through `Tales.captiveHome`), where
 * their kin waits for them. The watch's sortie (C) has the band let them go by the record at the close, and the watch walk them in by the road
 * at seven. None of it can harm them while the tale is open ([WagonCamp.guarded]).
 *
 * **The steps.** 0 the kin asks (ASK; at the gate 15:00 to 20:00, or the driver's "And the second hand?"); 1 the word of the camp and the
 * price (MEET: the driver while they sit at the gate, else the captain; the camp goes on the map); 2 the way (CHOOSE: "I'll go for them", "I'll
 * pay what they ask" with the kin, "Send the watch" with the captain for a player the town knows, 25 or more); 3 the deed (DO, GIVE or WATCH:
 * the rescue note, the ransom to the rider at the parley spot, or the wait for the close); 4 the walk home and the reunion (S6). A player who
 * pays the rider or cuts the hand loose without choosing has chosen by the deed.
 *
 * **What the tale keeps** ([numbers]): the flags below, the band, the waylaid wagon, the price, the wreck's place, the road and the gate, and the
 * market day the watch brings the hand in on; in [strings], the captain's name the band goes by and the side of the town the gate is on.
 */
object Wagon : Template, TaleVoice, TaleWords.Names {
    override val id: String = "wagon"
    override val size: Int = 3

    /** Every step hears the hand come home (or die) and the close: the watch rides at the close after it is sent. */
    override val steps: List<Step> = listOf(
        Step(Verb.ASK, by = 2, hears = setOf(Event.Kind.CAPTIVE, Event.Kind.DAY)),
        Step(Verb.MEET, by = 3, hears = setOf(Event.Kind.CAPTIVE, Event.Kind.DAY)),
        Step(Verb.CHOOSE, by = 4, real = true, hears = setOf(Event.Kind.CAPTIVE, Event.Kind.DAY)),
        Step(Verb.DO, by = 6, real = true, also = listOf(Verb.GIVE, Verb.WATCH), hears = setOf(Event.Kind.CAPTIVE, Event.Kind.DAY)),
        Step(Verb.SCENE, by = 6, real = true, hears = setOf(Event.Kind.CAPTIVE, Event.Kind.DAY)),
    )

    override val endings: List<Ending> = listOf(
        Ending(Ending.A, "home"),
        Ending(Ending.B, "ransomed"),
        Ending(Ending.C, "sortie"),
        Ending(Ending.W, "lost"),
    )

    /** Its W leaves the times a reason of -1: the road took someone. */
    override val hurts: Boolean = true

    /** The captain is any guard of the town; the hand and their kin are themselves. */
    override val standing: Set<String> = setOf(CAPTAIN)

    override val numbers: List<String> = listOf("flags", "band", "caravan", "price", "wreck (high)", "wreck (low)", "road and gate", "the watch's day")
    override val strings: List<String> = listOf("band", "side")

    // ---- The record ----

    const val KIN = "kin"
    const val HAND = "hand"
    const val CAPTAIN = "captain"

    /** The made figures: the band's go-between at the parley spot, and the guard who rides in with the watch. */
    const val RIDER = "rider"
    const val WATCH = "watch1"

    const val AT_ASK = 0
    const val AT_MEET = 1
    const val AT_CHOOSE = 2
    const val AT_FREE = 3
    const val AT_HOME = 4

    private const val FLAGS = 0
    private const val BAND = 1
    private const val CARAVAN = 2
    private const val PRICE = 3
    private const val WRECK = 4
    private const val ROAD = 6
    private const val COMES = 7

    /**
     * The flags, in `n[0]`: the word of the camp and the price has been told; the way chosen (one of three); the price paid to the rider; the
     * hand cut loose by a player at the camp; home at the board; the watch rode out; the band has them; a forced start made the strike up; the
     * band was broken while it held them; the price lapsed; the rider let them go; the kin is gone and the captain asks; the record says the
     * hand is out of the band's hands though nobody saw them walk (tales were off when they came home).
     */
    const val TOLD = 1
    const val WAY_A = 2
    const val WAY_B = 4
    const val WAY_C = 8
    const val PAID = 16
    const val KNIFE = 32
    const val HOME = 64
    const val RODE = 128
    const val TAKEN = 256
    const val MADE = 512
    const val SLAIN = 1024
    const val LAPSED = 2048
    const val RELEASED = 4096
    const val KIN_GONE = 8192
    const val OUTSIDE = 16384

    private const val WAYS = WAY_A or WAY_B or WAY_C

    /** The band's price (8.3), and what the rescue note is written for: 40 of the hundred of a size-3 tale's 56 (7.7). */
    const val RANSOM = 36
    const val NOTE_REWARD = 22

    /** The price stands until dusk ([DUSK]) on the fourth market day of the tale. */
    const val PRICE_DAYS = 4

    /** How far from the band's fire the parley spot is, on the town's side (outside the camp's ring). */
    const val PARLEY = 36.0

    /** The market days a strike stays a seed: it was in the last two. */
    private const val WINDOW = 2L

    /** The clock: the kin at the gate from 15:00 to 20:00; dusk at 20:00; the rider at the parley spot from 06:00 to 20:00; the watch in at 07:00. */
    val GATE_FROM: Int = Days.clock(15.0)
    val DUSK: Int = Days.clock(20.0)
    val RIDER_FROM: Int = Days.clock(6.0)
    val WATCH_IN: Int = Days.clock(7.0)
    val NOON: Int = Days.clock(12.0)

    internal fun flag(t: Tale, f: Int): Boolean = t.n[FLAGS] and f != 0

    internal fun set(t: Tale, f: Int) {
        t.n[FLAGS] = t.n[FLAGS] or f
    }

    private fun clear(t: Tale, f: Int) {
        t.n[FLAGS] = t.n[FLAGS] and f.inv()
    }

    /** The way chosen: [WAY_A], [WAY_B], [WAY_C], or 0 for none yet. */
    internal fun way(t: Tale): Int = t.n[FLAGS] and WAYS

    /** [w] is the way now, and no other. */
    internal fun choose(t: Tale, w: Int) {
        clear(t, WAYS)
        set(t, w)
    }

    /** Whether the hand is out of the band's hands by a player's knife or the rider's word (walking home, or home). */
    internal fun freed(t: Tale): Boolean = flag(t, KNIFE) || flag(t, RELEASED) || flag(t, HOME) || flag(t, OUTSIDE)

    internal fun bandId(t: Tale): Int = t.n[BAND]

    /** The waylaid wagon (its record's id), or -1 for a strike a forced start made up. */
    internal fun caravan(t: Tale): Long = t.n[CARAVAN].toLong()

    internal fun price(t: Tale): Int = t.n[PRICE]

    internal fun wreck(t: Tale): BlockPos = BlockPos.of((t.n[WRECK].toLong() shl 32) or (t.n[WRECK + 1].toLong() and 0xFFFFFFFFL))

    /** The road the wagon came by (-1: the plan's own country road). */
    internal fun road(t: Tale): Int = (t.n[ROAD] and 0xFFFF) - 1

    internal fun gate(t: Tale): Dir = Dir.entries.getOrElse((t.n[ROAD] ushr 16) and 0xFF) { Dir.entries.first() }

    /** The market day the watch brings the hand in on (C). */
    internal fun comes(t: Tale): Long = t.n[COMES].toLong()

    internal fun setComes(t: Tale, day: Long) {
        t.n[COMES] = day.toInt()
    }

    /** The name the band goes by: its captain's ("Black Aldric"). */
    internal fun bandWord(t: Tale): String = t.s[0]

    /** The side of the town the gate is on ("north"). */
    internal fun side(t: Tale): String = t.s.getOrNull(1).orEmpty().ifBlank { "north" }

    internal fun given(name: String): String = name.substringBefore(' ')

    internal fun handName(t: Tale): String = t.cast(HAND)?.name.orEmpty()

    internal fun clock(level: ServerLevel): Int = Days.offsetOf(level.overworldClockTime)

    internal fun band(level: ServerLevel, t: Tale): OutlawBand? = OutlawCamps.get(level).band(bandId(t))

    /** The gate's front, outside the gatehouse on the wagon's road: where the kin waits and the watch comes in. */
    internal fun front(c: Ctx, t: Tale): BlockPos = WayOut.front(c.here.places, gate(t))

    /** The town's board, where the hand is walked home to ([Captives]' own home for them), or null while the capital cannot be found. */
    internal fun board(level: ServerLevel, t: Tale): BlockPos? = CaravanRoutes.capital(level, t.capital)?.boardAnchor

    /** Whether the price stands: not paid, not lapsed, and before dusk on the fourth market day. */
    internal fun priceStands(level: ServerLevel, t: Tale): Boolean {
        if (flag(t, LAPSED) || flag(t, PAID) || freed(t) || flag(t, RODE)) return false
        val today = TaleEngine.today(level, t.capital)
        val last = t.d0 + PRICE_DAYS
        return today < last || today == last && clock(level) < DUSK
    }

    /** Market days from today to the dusk the price lapses at (0: this evening). */
    internal fun priceDays(level: ServerLevel, t: Tale): Long = (t.d0 + PRICE_DAYS - TaleEngine.today(level, t.capital)).coerceAtLeast(0L)

    /** The parley spot: [PARLEY] blocks from [band]'s fire towards the town, at the fire's height (the watch finds the ground there). */
    internal fun parley(t: Tale, band: OutlawBand): BlockPos {
        val center = BlockPos.of(t.capital)
        val dx = (center.x - band.pos.x).toDouble()
        val dz = (center.z - band.pos.z).toDouble()
        val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1.0)
        return BlockPos.containing(band.pos.x + 0.5 + dx / len * PARLEY, band.pos.y.toDouble(), band.pos.z + 0.5 + dz / len * PARLEY)
    }

    /** Who asks: the kin, or the captain once the kin is gone. */
    internal fun askerOf(t: Tale): String = if (flag(t, KIN_GONE) || t.cast(KIN)?.gone?.isNotEmpty() == true) CAPTAIN else KIN

    /** The words a sentence is said with (`TaleWords`): the hand, and the band (or, at W, the side of the town the road leaves by). */
    override fun words(t: Tale): List<String> = listOf(handName(t), if (t.ending == Ending.W) side(t) else bandWord(t))

    // ---- The seed ----

    override fun needs(): Boolean = Caravans.enabled() && Outlaws.enabled() && Tales.scenes()

    /** A strike to seed from: the waylaid wagon ([caravan], -1 made up), the [band], the [road], the wreck's place [at], and the [gate]. */
    private class Strike(val caravan: Long, val band: OutlawBand, val road: Int, val at: BlockPos, val gate: Dir)

    override fun seed(c: Seeding): Planned? {
        val level = c.level
        val data = TaleData.get(level)
        val places = c.here.places
        if (WayOut.gates(places).isEmpty()) return c.none("the town has no gate with a country road")
        val caravans = CaravanData.get(level)
        val camps = OutlawCamps.get(level)
        var found = "no wagon bound here was waylaid by a band in the last $WINDOW market days"
        var strike: Strike? = null
        val waylaid = data.find(c.capital)?.waylaid.orEmpty()
            .filter { it.band >= 0 && c.day - it.day in 0..WINDOW }
            .sortedWith(compareByDescending<TaleData.Waylaid> { it.day }.thenByDescending { it.caravan })
        for (w in waylaid) {
            if (data.all().any { it.template == id && it.capital == c.capital && it.n[CARAVAN] == w.caravan.toInt() }) {
                found = "the waylaid wagon has had its tale already"
                continue
            }
            val band = camps.band(w.band)
            if (band == null || !band.built || band.slain) {
                found = "the band that waylaid the wagon is broken, or has no camp yet"
                continue
            }
            if (band.captives.size >= Captives.MAX) {
                found = "the band that waylaid the wagon holds ${Captives.MAX} already"
                continue
            }
            val at = wreckOf(caravans, w.caravan)
            if (at == null) {
                found = "the waylaid wagon's wreck is not on record"
                continue
            }
            val gate = gateOf(c, caravans, w.caravan, w.road, at) ?: continue
            strike = Strike(w.caravan, band, w.road, at, gate)
            break
        }
        var madeUp = ""
        if (strike == null && c.forced) {
            strike = madeUp(c)
            if (strike != null) madeUp = "a wagon on the ${WayOut.side(c.here, strike.gate)} road waylaid by ${bandName(strike.band)} (no wagon was: it has no driver at the gate)"
        }
        if (strike == null) return c.none(if (c.forced) "$found, and no band at large works any of the town's roads" else found)
        // The household: two grown people free of a tale, one to be taken and one to wait
        val residents = Residents.get(level)
        val center = c.here.capital.center
        val pool = TaleCast.pool(level, center)
        val everyone = residents.of(center)
        class Pick(val hand: Resident, val kin: Resident)
        val picks = ArrayList<Pick>()
        for ((home, adults) in pool.groupBy { it.home }.toSortedMap()) {
            if (everyone.count { it.home == home && it.alive && it.left < 0 } < 2) continue
            // The hand went along for the pay: anyone but a guard, a citizen first
            val hands = adults.filter { it.role != TownRole.GUARD }
            val hand = hands.firstOrNull { it.role == TownRole.CITIZEN } ?: hands.firstOrNull() ?: continue
            val kin = adults.firstOrNull { it.id != hand.id } ?: continue
            picks += Pick(hand, kin)
        }
        if (picks.isEmpty()) return c.none("no household has two grown people free of a tale (one who is no guard)")
        val pick = picks[c.random.nextInt(picks.size)]
        val front = WayOut.front(places, strike.gate)
        val lodgings = places.lodgings
        val captain = pool.filter { it.role == TownRole.GUARD && it.id != pick.hand.id && it.id != pick.kin.id }
            .minWithOrNull(compareBy<Resident>({ r -> lodgings[r.home]?.box?.center?.distSqr(front) ?: Double.MAX_VALUE }, { it.id }))
            ?: return c.none("no guard is free to captain the watch")
        val cast = listOf(
            Cast(KIN, pick.kin.id, pick.kin.name),
            Cast(HAND, pick.hand.id, pick.hand.name),
            Cast(CAPTAIN, captain.id, captain.name),
        )
        val side = WayOut.side(c.here, strike.gate)
        val n = IntArray(8)
        n[FLAGS] = if (strike.caravan < 0) MADE else 0
        n[BAND] = strike.band.id
        n[CARAVAN] = strike.caravan.toInt()
        n[PRICE] = RANSOM
        val w = strike.at.asLong()
        n[WRECK] = (w ushr 32).toInt()
        n[WRECK + 1] = w.toInt()
        n[ROAD] = ((strike.road + 1) and 0xFFFF) or (strike.gate.ordinal shl 16)
        n[COMES] = 0
        return Planned(
            cast, Why("wagon.start", listOf(TaleWords.town(level, c.capital), side)), step = AT_ASK, n = n.toList(),
            s = listOf(bandName(strike.band), side), madeUp = madeUp,
        )
    }

    /** The name a band goes by, its captain's ("Black Aldric"), as the language has it. */
    private fun bandName(band: OutlawBand): String = Component.translatable("${Outlaws.NAME_KEY}.${band.name}").string

    /** Where the waylaid wagon lies: its wreck, else where its record says it was struck. */
    private fun wreckOf(caravans: CaravanData, caravan: Long): BlockPos? {
        caravans.wrecks.firstOrNull { it.caravan == caravan }?.let { return BlockPos.containing(it.x, it.y, it.z) }
        val rec = caravans.caravan(caravan) ?: return null
        if (rec.wx == 0.0 && rec.wz == 0.0) return null
        return BlockPos.containing(rec.wx, rec.wy, rec.wz)
    }

    /** The gate the wagon's road comes in by: its leg's side, else the gate its record names, else the gate nearest the wreck. */
    private fun gateOf(c: Seeding, caravans: CaravanData, caravan: Long, road: Int, at: BlockPos): Dir? {
        val places = c.here.places
        val gates = WayOut.gates(places)
        CaravanRoutes.cached(c.capital, road)?.side?.takeIf { it in gates }?.let { return it }
        caravans.caravan(caravan)?.gate?.takeIf { it != 0L }?.let { g -> WayOut.gateNear(places, BlockPos.of(g))?.let { return it } }
        return WayOut.gateNear(places, at)
    }

    /** A forced start's strike: on the first of the town's roads a band at large works (else the band nearest the town), at the point of the road nearest the camp. */
    private fun madeUp(c: Seeding): Strike? {
        val level = c.level
        val places = c.here.places
        val gates = WayOut.gates(places)
        val legs = CaravanRoutes.legs(level, c.capital).orEmpty().sortedBy { it.road }
        for (leg in legs) {
            val band = CaravanRoutes.band(level, leg)?.takeIf { it.built && it.captives.size < Captives.MAX } ?: continue
            return strikeOn(places, gates, leg, band)
        }
        val center = c.here.capital.center
        val band = OutlawCamps.get(level).bands.filter { !it.slain && it.built && it.captives.size < Captives.MAX }
            .minByOrNull { it.pos.distSqr(center) } ?: return null
        val leg = legs.minByOrNull { CaravanRoutes.reach(it, band) } ?: return null
        return strikeOn(places, gates, leg, band)
    }

    private fun strikeOn(places: CityPlaces, gates: List<Dir>, leg: CaravanRoutes.Leg, band: OutlawBand): Strike? {
        val point = leg.points.minByOrNull { p ->
            val dx = p.pos.x - band.pos.x - 0.5
            val dz = p.pos.z - band.pos.z - 0.5
            dx * dx + dz * dz
        }?.pos ?: return null
        val at = BlockPos.containing(point)
        val gate = leg.side.takeIf { it in gates } ?: WayOut.gateNear(places, at) ?: return null
        return Strike(-1L, band, leg.road, at, gate)
    }

    // ---- The steps ----

    override fun enter(c: Ctx, t: Tale) {
        if (!flag(t, TAKEN)) {
            if (!WagonCamp.take(c, t)) {
                // The band is gone between the seed and its first step: they never had the hand
                c.why(t, "wagon.untaken", handName(t))
                c.end(t, Ending.B)
                return
            }
            c.why(t, "wagon.taken", bandWord(t), handName(t))
        }
        // The word came before the yes (the driver told it): there is nothing more to hear
        if (t.step == AT_MEET && flag(t, TOLD)) c.go(t, AT_CHOOSE)
    }

    override fun heard(c: Ctx, t: Tale, e: Event) {
        when (e) {
            is Event.Captive -> if (e.home && e.resident == t.resident(HAND)) home(c, t)
            is Event.Day -> if (t.step == AT_FREE && way(t) == WAY_C && !freed(t) && !flag(t, RODE)) WagonCamp.ride(c, t)
            is Event.Contract -> if (e.step == AT_FREE && !e.expired) {
                // What the note paid is taken off the ending's pay (4.6)
                t.partFor(e.player.uuid)?.let {
                    it.paid += e.note.reward
                    it.did = it.did or Part.HANDED
                }
                c.data.changed()
            }
            is Event.Gave -> if (e.marks > 0) paid(c, t, e.player)
            else -> {}
        }
    }

    /**
     * The hand is home at the board (`Captives.homecoming`): whoever walked them is the deciding deed (A or B), a way nobody chose is the one the
     * deed took, and the reunion is next (the watch queues it once the band's hold on them is let go and the kin is there).
     */
    internal fun home(c: Ctx, t: Tale) {
        if (flag(t, HOME) || !t.open) return
        set(t, HOME)
        val walker = WagonCamp.rescuer(t.resident(HAND))?.let { t.partFor(it) }
        if (walker != null) {
            for (p in t.parts) if (p !== walker) p.did = p.did and Part.DECIDED.inv()
            walker.did = walker.did or Part.WALKED or Part.DECIDED
        }
        if (way(t) == 0 || way(t) == WAY_C) choose(t, if (flag(t, RELEASED)) WAY_B else WAY_A)
        c.why(t, "wagon.home", handName(t))
        c.data.changed()
        if (t.step != AT_HOME) c.go(t, AT_HOME)
    }

    /** [player] cut the hand loose at the camp ([WagonCamp]'s watch sees the band's record say so): the way is A, and the walk home is on. */
    internal fun knifed(c: Ctx, t: Tale, player: UUID?) {
        if (flag(t, KNIFE) || flag(t, RELEASED) || !t.open) return
        set(t, KNIFE)
        if (!flag(t, PAID)) choose(t, WAY_A)
        val part = player?.let { t.partFor(it) }
        part?.let { it.did = it.did or Part.WALKED }
        val name = player?.let { TaleEngine.playerName(c.level, it) }.orEmpty()
        c.why(t, "wagon.freed", name, handName(t))
        c.data.changed()
        if (player != null) c.level.getPlayerByUUID(player)?.let { (it as? ServerPlayer)?.let { p -> Tales.join(c.level, t, p) } }
        if (t.step != AT_HOME) c.go(t, AT_HOME)
    }

    /** [player] gave the rider the price ([TaleOptions.put], heard as `Event.Gave`): the way is B, the Marks are spent, and the rider's handing begins. */
    private fun paid(c: Ctx, t: Tale, player: ServerPlayer) {
        if (flag(t, PAID) || freed(t) || t.step > AT_FREE) return
        set(t, PAID)
        choose(t, WAY_B)
        t.partFor(player.uuid)?.let { it.did = it.did or Part.GAVE or Part.CHOSE }
        c.data.changed()
        // The step is met: what was put in is spent
        c.go(t, AT_HOME)
        WagonWatch.parley(c, t, player)
    }

    // ---- The world's way ----

    /**
     * The close (or `tale step`): nobody said yes, and the household waits on (0); the word of the camp goes round the town (1); nobody chose
     * (2). At the last day: home is the way it was walked; a hand freed and not walked home finds their own way (the way they were freed), as
     * does one whose band was broken (A); the watch's ride is C; nothing done is W.
     */
    override fun settle(c: Ctx, t: Tale) {
        when (t.step) {
            AT_ASK -> {
                c.why(t, "wagon.unasked", given(t.cast(askerOf(t))?.name.orEmpty()))
                c.next(t)
            }
            AT_MEET -> {
                set(t, TOLD)
                c.why(t, "wagon.told_world", price(t).toString())
                c.next(t)
            }
            AT_CHOOSE -> {
                c.why(t, "wagon.unchosen")
                c.next(t)
            }
            else -> {
                val ending = outcome(t)
                if ((ending == Ending.A || ending == Ending.B) && !flag(t, HOME)) c.why(t, "wagon.own_way", handName(t))
                // The deciding deed of an A nobody walked home: the knife, or the band broken (whoever was at the camp)
                if (ending == Ending.A && t.parts.none { it.did(Part.DECIDED) }) {
                    t.parts.firstOrNull { it.did(Part.WALKED) }?.let { it.did = it.did or Part.DECIDED }
                }
                // Of a B: the payer
                if (ending == Ending.B && t.parts.none { it.did(Part.DECIDED) }) {
                    t.parts.firstOrNull { it.did(Part.GAVE) }?.let { it.did = it.did or Part.DECIDED }
                }
                c.end(t, ending)
            }
        }
    }

    /**
     * The ending the record has now: C if the watch rode; A if a player's knife freed them; B if the band was paid (its rider gave the word, or
     * will: the price is paid); A if they are free some other way or the band was broken (they find their own way home); else W.
     */
    internal fun outcome(t: Tale): String = when {
        flag(t, RODE) -> Ending.C
        flag(t, KNIFE) -> Ending.A
        flag(t, RELEASED) || flag(t, PAID) -> Ending.B
        freed(t) || flag(t, SLAIN) -> Ending.A
        else -> Ending.W
    }

    override fun gone(c: Ctx, t: Tale, slot: String, how: Gone) {
        when (slot) {
            // The hand is lost only through W: dead in the band's hands, or (the record's) gone from the town
            HAND -> c.end(t, Ending.W)
            KIN -> {
                set(t, KIN_GONE)
                c.why(t, "wagon.kin", t.cast(CAPTAIN)?.name.orEmpty(), t.cast(KIN)?.name.orEmpty())
                c.data.changed()
                WagonWatch.forget(t.id, KIN)
                // Nobody waits at the board: a hand who is home is home
                if (t.step == AT_HOME && flag(t, HOME)) c.end(t, outcome(t))
            }
            CAPTAIN -> {
                c.why(t, "wagon.noguard")
                WagonWatch.forget(t.id, CAPTAIN)
            }
        }
    }

    /** A stand-in for the captain is held where the old one stood: the watch does it again from the next second. */
    override fun stoodIn(c: Ctx, t: Tale, slot: String, wasHeld: Boolean) {
        WagonWatch.forget(t.id, slot)
    }

    // ---- The endings ----

    override fun end(c: Ctx, t: Tale, ending: String) {
        WagonWatch.letGo(c, t)
        // The band never had them (it was gone before the first step): nothing happened that lasts
        if (!flag(t, TAKEN)) return WagonWatch.ended(t.id)
        val home = c.resident(t, HAND)?.home ?: -1
        when (ending) {
            Ending.A -> {
                WagonCamp.restore(c, t)
                peril(c, t, RoadPeril.HELPED)
                if (home >= 0) c.protect(t, home, 20)
                if (c.flip(t, HAND, Trait.SHY)) c.why(t, "wagon.shy", handName(t))
                noteDone(c, t)
            }
            Ending.B -> WagonCamp.restore(c, t)
            Ending.C -> {
                WagonCamp.restore(c, t)
                peril(c, t, -1)
                // The captain's thanks, and theirs alone (no one else in the town is beholden to the player for a sortie)
                for (p in t.parts.filter { TaleShares.holds(it) }) c.favour(t, CAPTAIN, p.player, Favour.TALE_HELPED)
            }
            Ending.W -> {
                WagonCamp.lose(c, t)
                if (c.memorial(t, "cairn", listOf(handName(t)), wreck(t)) != null) c.why(t, "wagon.cairn", handName(t))
                peril(c, t, 1)
                if (c.flip(t, KIN, Trait.DEVOUT)) c.why(t, "wagon.devout", t.cast(KIN)?.name.orEmpty())
            }
        }
        WagonWatch.ended(t.id)
    }

    /** The road the wagon came by is [by] more perilous (less, for a negative), as the caravans keep it. */
    private fun peril(c: Ctx, t: Tale, by: Int) {
        if (!Caravans.enabled()) return
        RoadPeril.change(CaravanData.get(c.level), t.capital, road(t), by, Markets.hourOf(c.level))
    }

    /**
     * A rescue note that is done but not handed in yet, in the pack of a player in the tale: what it will pay at the board is counted as paid
     * by the tale's own note now (the tale is over before it can hear it handed in), so the whole is the ending's 56 and no more.
     */
    private fun noteDone(c: Ctx, t: Tale) {
        val level = c.level
        for (part in t.parts) {
            val player = level.getPlayerByUUID(part.player) ?: continue
            val stack = TaleOptions.carried(player, t, AT_FREE) ?: continue
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.progress >= note.count && !state.isExpired(level.gameTime)) part.paid += note.reward
        }
        c.data.changed()
    }

    // ---- The tracker ----

    override fun view(t: Tale, player: UUID): View {
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld()
        val c = level?.let { TaleEngine.ctx(it, t.capital) }
        val title = TaleKeys.title(id)
        val hand = given(handName(t))
        val front = c?.let { front(it, t) }
        val tone = if (flag(t, HOME)) View.Tone.DONE else View.Tone.TALE
        return when (t.step) {
            AT_ASK -> View(title, status = TaleKeys.status(id, AT_ASK), statusArgs = listOf(given(t.cast(askerOf(t))?.name.orEmpty())), target = c?.let { kinAt(it, t) })
            AT_MEET -> if (level != null && driverAt(level, t) != null) View(title, status = TaleKeys.status(id, AT_MEET), target = driverAt(level, t))
            else View(title, status = STATUS_CAPTAIN, statusArgs = listOf(given(t.cast(CAPTAIN)?.name.orEmpty())), target = front)
            AT_CHOOSE -> View(title, status = TaleKeys.status(id, AT_CHOOSE), target = c?.let { kinAt(it, t) })
            AT_FREE -> when (if (way(t) == WAY_B && (level == null || !priceStands(level, t)) && !flag(t, PAID)) 0 else way(t)) {
                WAY_B -> View(title, status = STATUS_RANSOM, statusArgs = listOf(price(t).toString()), target = level?.let { band(it, t) }?.let { parley(t, it) })
                WAY_C -> View(title, status = STATUS_WAIT, target = front)
                else -> View(title, status = TaleKeys.status(id, AT_FREE), statusArgs = listOf(hand), target = level?.let { band(it, t)?.pos })
            }
            else -> if (flag(t, RODE)) View(title, status = STATUS_GATE, tone = tone, target = front)
            else View(title, status = TaleKeys.status(id, AT_HOME), statusArgs = listOf(hand), tone = tone, target = level?.let { board(it, t) })
        }
    }

    private const val STATUS_CAPTAIN = "gui.guildmark.tale.wagon.status.1.captain"
    private const val STATUS_RANSOM = "gui.guildmark.tale.wagon.status.3.ransom"
    private const val STATUS_WAIT = "gui.guildmark.tale.wagon.status.3.watch"
    private const val STATUS_GATE = "gui.guildmark.tale.wagon.status.4.watch"

    /** Where the kin is to be found: at the gate in the waiting hours, else at their door. */
    internal fun kinAt(c: Ctx, t: Tale): BlockPos {
        val front = front(c, t)
        val now = clock(c.level)
        if (now in GATE_FROM until DUSK) return front
        val r = c.resident(t, askerOf(t)) ?: return front
        return c.here.places.lodgings[r.home]?.front?.inside ?: front
    }

    /** Where the waylaid wagon's driver sits at the gate, while they do; null once they have gone (or for a strike made up). */
    internal fun driverAt(level: ServerLevel, t: Tale): BlockPos? {
        if (caravan(t) < 0) return null
        val c = TaleEngine.ctx(level, t.capital) ?: return null
        return Caravans.drivers(level, front(c, t), 64.0).firstOrNull { it.caravan == caravan(t) }?.at
    }

    // ---- The telling ----

    override fun scene(c: Ctx, t: Tale): Stage? {
        if (!t.open) return WagonScenes.ending(c, t)
        return if (t.step < AT_HOME) WagonScenes.waiting(c, t) else null
    }

    // ---- The talk ----

    override fun asker(t: Tale): String = askerOf(t)

    override fun detail(beat: String, turn: TaleTurn): String? {
        val t = turn.tale
        val hand = TaleLines.lit(handName(t))
        return when (beat) {
            "ask" -> TaleLines.facts("talk.detail.tale.wagon.ask", hand, TaleLines.lit(bandWord(t)))
            "rumour" -> TaleLines.facts("talk.detail.tale.wagon.rumour", hand, WayOut.sideKey(side(t)))
            "stand" -> stand(turn.level, t)
            "parley" -> TaleLines.facts("talk.detail.tale.wagon.parley", price(t), TaleLines.lit(given(handName(t))))
            "driver_hand" -> priceDetail(turn.level, t)
            "captain_yes" -> TaleLines.facts("talk.detail.tale.wagon.captain_yes")
            "cairn" -> TaleLines.facts("talk.detail.tale.wagon.cairn", hand)
            else -> null
        }
    }

    /** The grey words of the price, as it stands: the sum and the dusk it lapses at, or that it lapsed, or that it was paid. */
    internal fun priceDetail(level: ServerLevel, t: Tale): String? = when {
        flag(t, PAID) -> TaleLines.facts("talk.detail.tale.wagon.driver_hand.paid", price(t))
        !priceStands(level, t) -> TaleLines.facts("talk.detail.tale.wagon.driver_hand.lapsed")
        else -> TaleLines.facts("talk.detail.tale.wagon.driver_hand", price(t), TaleLines.due(priceDays(level, t)))
    }

    /** "How does it stand?": where the hand is held and what is left to do, from the record. */
    internal fun stand(level: ServerLevel, t: Tale): String? {
        val hand = TaleLines.lit(handName(t))
        val band = TaleLines.lit(bandWord(t))
        val given = TaleLines.lit(given(handName(t)))
        return when {
            flag(t, RODE) -> TaleLines.facts("talk.detail.tale.wagon.stand.rode", given)
            flag(t, HOME) -> TaleLines.facts("talk.detail.tale.wagon.stand.home", given)
            freed(t) -> TaleLines.facts("talk.detail.tale.wagon.stand.free", given)
            t.step == AT_FREE && way(t) == WAY_C -> TaleLines.facts("talk.detail.tale.wagon.stand.watch", given)
            t.step == AT_FREE && way(t) == WAY_B && priceStands(level, t) ->
                TaleLines.facts("talk.detail.tale.wagon.stand.pay", price(t), band, TaleLines.due(priceDays(level, t)))
            t.step == AT_FREE && way(t) == WAY_A -> TaleLines.facts("talk.detail.tale.wagon.stand.go", hand, band, WayOut.sideKey(side(t)))
            priceStands(level, t) -> TaleLines.facts("talk.detail.tale.wagon.stand", hand, band, price(t), TaleLines.due(priceDays(level, t)))
            else -> TaleLines.facts("talk.detail.tale.wagon.stand.lapsed", hand, band)
        }
    }

    override fun gesture(beat: String, turn: TaleTurn): Activity? = when (beat) {
        "ask" -> Activity.WRING_HANDS
        "passing" -> Activity.SIGH
        else -> null
    }

    /** The kin points the way to the camp when the way is the knife, and to the rider's spot when it is the price. */
    override fun toward(beat: String, turn: TaleTurn): BlockPos? {
        if (beat != "stand") return null
        val t = turn.tale
        val band = band(turn.level, t) ?: return null
        return when {
            freed(t) || flag(t, RODE) -> null
            way(t) == WAY_B -> parley(t, band)
            way(t) == WAY_A -> band.pos
            else -> null
        }
    }

    /** The rider names the price when a player who has taken the tale up comes to them. */
    override fun greet(turn: TaleTurn): TaleSay? = if (turn.slot == RIDER) turn.say("parley", detail("parley", turn)) else null

    override fun options(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val asks = askerOf(t)
        return when (turn.slot) {
            KIN -> if (asks == KIN) kinOptions(turn) else listOf()
            CAPTAIN -> (captainOptions(turn) + if (asks == CAPTAIN) kinOptions(turn) else listOf()).take(4)
            RIDER -> riderOptions(turn)
            else -> listOf()
        }
    }

    /** What the one who asks offers: at the choice, the knife or the price (and why not the watch); at the deed, the note, or the other way. */
    private fun kinOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        val level = turn.level
        val out = ArrayList<TaleOption>()
        when (t.step) {
            AT_CHOOSE -> {
                out += go()
                if (priceStands(level, t)) out += pay()
                out += watchAsk()
            }
            AT_FREE -> {
                if (freed(t) || flag(t, RODE)) return out
                val player = turn.player ?: return out
                if (way(t) == WAY_A || way(t) == 0) {
                    if (TaleOptions.carried(player, t, AT_FREE) == null) out += note(if (TaleOptions.wasGiven(turn)) "lost" else "take")
                } else {
                    out += go()
                }
                if (way(t) != WAY_B && priceStands(level, t)) out += pay()
            }
        }
        return out
    }

    /** What the captain offers: the word of the camp at the meeting, and the watch while the hand is held. */
    private fun captainOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        return when {
            t.step == AT_MEET -> listOf(camp())
            t.step == AT_CHOOSE || t.step == AT_FREE && way(t) != WAY_C && !freed(t) && !flag(t, RODE) -> listOf(send())
            else -> listOf()
        }
    }

    /** What the rider offers: the price, while it stands and the band still holds the hand. */
    private fun riderOptions(turn: TaleTurn): List<TaleOption> {
        val t = turn.tale
        if (t.step !in AT_MEET..AT_FREE || !priceStands(turn.level, t)) return listOf()
        return listOf(ransom(price(t)))
    }

    private fun think(): TaleReply = TaleReply("ask.think", gesture = Activity.THINK)

    // ---- The deeds (the talk's options and `tale wagon` both do them through these) ----

    /** [player] will go for the hand themselves: the way is A, the step is the deed's. False if it no longer can be. */
    internal fun goFor(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if ((t.step != AT_CHOOSE && t.step != AT_FREE) || freed(t) || flag(t, RODE)) return false
        // The reason first, so that what the next step adds is newer than it
        t.addWhy(Why("wagon.go", listOf(player.gameProfile.name)))
        choose(t, WAY_A)
        t.partFor(player.uuid)?.let { it.did = it.did or Part.CHOSE }
        c.data.changed()
        if (t.step == AT_CHOOSE) c.go(t, AT_FREE)
        return true
    }

    /** [player] will pay what the band asks: the way is B, and the price is the rider's to take. False if it no longer can be. */
    internal fun payWay(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if ((t.step != AT_CHOOSE && t.step != AT_FREE) || !priceStands(c.level, t)) return false
        t.addWhy(Why("wagon.pay", listOf(player.gameProfile.name)))
        choose(t, WAY_B)
        t.partFor(player.uuid)?.let { it.did = it.did or Part.CHOSE }
        c.data.changed()
        if (t.step == AT_CHOOSE) c.go(t, AT_FREE)
        return true
    }

    /** The captain tells [player] of the camp and the price: the camp goes on their map, the word is told and the choice is next. */
    internal fun tellCamp(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        if (t.step != AT_MEET) return false
        val level = c.level
        band(level, t)?.let { Atlas.note(player, AtlasPlace(AtlasPlace.OUTLAW_CAMP, Level.OVERWORLD, it.pos, ref = it.id, found = level.gameTime)) }
        t.addWhy(Why("wagon.told", listOf(t.cast(CAPTAIN)?.name.orEmpty(), price(t).toString())))
        set(t, TOLD)
        c.data.changed()
        c.go(t, AT_CHOOSE)
        return true
    }

    /**
     * [player] asks the captain to send the watch: for a player the town knows (25 or more at its board) it rides at the next close, the word
     * that sent it the deciding deed of C; answers the points the player has (-1 if the watch cannot be sent at all now, the step or the way
     * against it). The watch is sent if the points are [StandingTier.KNOWN]'s or more.
     */
    internal fun sendWatch(c: Ctx, t: Tale, player: ServerPlayer): Int {
        if ((t.step != AT_CHOOSE && t.step != AT_FREE) || freed(t) || flag(t, RODE) || way(t) == WAY_C) return -1
        val points = board(c.level, t)?.let { Standing.points(c.level, it, player) } ?: 0
        if (points < StandingTier.KNOWN.threshold) {
            if (t.why.lastOrNull()?.kind != "wagon.refused") t.addWhy(Why("wagon.refused", listOf(t.cast(CAPTAIN)?.name.orEmpty(), player.gameProfile.name)))
            c.data.changed()
            return points
        }
        t.addWhy(Why("wagon.watch_sent", listOf(player.gameProfile.name, t.cast(CAPTAIN)?.name.orEmpty())))
        choose(t, WAY_C)
        val part = t.partFor(player.uuid)
        for (p in t.parts) if (p !== part) p.did = p.did and Part.DECIDED.inv()
        part?.let { it.did = it.did or Part.CHOSE or Part.DECIDED }
        c.data.changed()
        if (t.step == AT_CHOOSE) c.go(t, AT_FREE)
        return points
    }

    /**
     * The rescue note for the hand ([QuestType.RESCUE], 4.6): written for [NOTE_REWARD] (the board adds for the camp's distance, as it does for
     * any rescue), due the day after the step's last, with the band and the hand on it; null with no camp to lead to.
     */
    internal fun rescueNote(level: ServerLevel, t: Tale): QuestNote? {
        val band = band(level, t) ?: return null
        val asker = t.cast(askerOf(t))?.name.orEmpty()
        val days = (t.d0 + steps[AT_FREE].by + 1 - TaleEngine.today(level, t.capital)).toInt().coerceIn(1, 7)
        val plain = QuestNote(QuestType.RESCUE, asker, Captives.CAPTIVE, 1, NOTE_REWARD, days, chain = TaleOptions.chain(t, AT_FREE))
        return Captives.dress(plain, Captives.Held(band.id, handName(t), band.name))
    }

    /** What the one who asks says as they hand the note over: "Wren hands you a note for "The Fourth Wagon"." */
    internal fun noteSaid(t: Tale): Component =
        Component.translatable("message.guildmark.tale.note", t.cast(askerOf(t))?.name.orEmpty(), Component.translatable(TaleKeys.title(id)))

    /** The band's notice for the hand, handed over in the talk (as the board's own, `TaleOptions.give`). */
    private fun giveNote(turn: TaleTurn): Boolean {
        val t = turn.tale
        if (t.step != AT_FREE) return false
        val note = rescueNote(turn.level, t) ?: return false
        return TaleOptions.give(turn, note, noteSaid(t))
    }

    // ---- The options ----

    /** "I'll go for them.": the knife, and the band's notice for the hand with it. */
    private fun go() = TaleOption("go", Component.translatable("talk.guildmark.choice.tale.wagon.go")) { turn ->
        val t = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!goFor(c, t, player)) return@TaleOption null
        giveNote(turn)
        TaleReply(listOf(turn.say("stand", stand(turn.level, t))), Activity.NOD, toward = band(turn.level, t)?.pos)
    }

    /** "I'll pay what they ask.": the price, to the rider who waits by the camp on the town's side. */
    private fun pay() = TaleOption("pay", Component.translatable("talk.guildmark.choice.tale.wagon.pay")) { turn ->
        val t = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!payWay(c, t, player)) return@TaleOption null
        TaleReply(listOf(turn.say("stand", stand(turn.level, t))), Activity.NOD, toward = band(turn.level, t)?.let { parley(t, it) })
    }

    /** "Can the watch go?": the one who asks says who to ask, and what the watch rides on. Nothing is changed. */
    private fun watchAsk() = TaleOption("watch", Component.translatable("talk.guildmark.choice.tale.wagon.watch_ask")) { turn ->
        val t = turn.tale
        if (t.step != AT_CHOOSE) return@TaleOption null
        val captain = TaleLines.lit(given(t.cast(CAPTAIN)?.name.orEmpty()))
        val c = turn.ctx()
        TaleReply(
            listOf(turn.say("stand", TaleLines.facts("talk.detail.tale.wagon.watch", captain, StandingTier.KNOWN.threshold))),
            Activity.SHRUG, toward = c?.let { front(it, t) },
        )
    }

    /** "I'll take the note." (or "I lost the note."): the band's notice for the hand, the tale's own (`tale:<id>:3`). */
    private fun note(kind: String) = TaleOption(kind, Component.translatable("talk.guildmark.choice.tale.wagon.${kind}_note")) { turn ->
        val t = turn.tale
        val player = turn.player
        if (t.step != AT_FREE || player == null || freed(t) || TaleOptions.carried(player, t, AT_FREE) != null) return@TaleOption null
        if (turn.ctx() == null) return@TaleOption think()
        if (way(t) != WAY_A) {
            choose(t, WAY_A)
            turn.changed()
        }
        if (!giveNote(turn)) return@TaleOption TaleReply(listOf(turn.say("stand", stand(turn.level, t))), Activity.SHRUG)
        TaleReply(listOf(turn.say("stand", stand(turn.level, t))), Activity.NOD, toward = band(turn.level, t)?.pos)
    }

    /** The captain's "What do you know of the camp?": the price and the camp, which goes on the player's map; the word is told. */
    private fun camp() = TaleOption("camp", Component.translatable("talk.guildmark.choice.tale.wagon.camp")) { turn ->
        val t = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        if (!tellCamp(c, t, player)) return@TaleOption null
        TaleReply(listOf(turn.say("driver_hand", priceDetail(turn.level, t))), Activity.EXPLAIN, toward = band(turn.level, t)?.pos)
    }

    /** The captain's "Send the watch.": for a player the town knows (25 or more) the watch rides at the next close; for any other, not on their word. */
    private fun send() = TaleOption("send", Component.translatable("talk.guildmark.choice.tale.wagon.send")) { turn ->
        val t = turn.tale
        val c = turn.ctx() ?: return@TaleOption think()
        val player = turn.player ?: return@TaleOption null
        val points = sendWatch(c, t, player)
        if (points < 0) return@TaleOption null
        if (way(t) != WAY_C) {
            val needed = StandingTier.KNOWN.threshold
            return@TaleOption TaleReply(listOf(turn.say("captain_no", TaleLines.facts("talk.detail.tale.wagon.captain_no", points, needed))), Activity.ARMS_FOLDED)
        }
        TaleReply(listOf(turn.say("captain_yes", detail("captain_yes", turn))), Activity.SALUTE)
    }

    /** The rider's "Here are 36 Marks.": the price from the pack, spent as the step is met; the rider takes it and lets the hand go. */
    private fun ransom(marks: Int) = TaleOption("ransom", Component.translatable("talk.guildmark.choice.tale.wagon.ransom", marks)) { turn ->
        val t = turn.tale
        if (t.step !in AT_MEET..AT_FREE || !priceStands(turn.level, t)) return@TaleOption null
        when (TaleOptions.check(turn, price(t))) {
            TaleOptions.Put.SHORT -> return@TaleOption TaleReply(listOf(turn.say("parley", detail("parley", turn))), Activity.ARMS_FOLDED)
            TaleOptions.Put.LATER -> return@TaleOption think()
            else -> {}
        }
        // The reason first, so that what the step's end adds is newer than it
        val why = Why("wagon.paid", listOf(turn.who, price(t).toString()))
        t.addWhy(why)
        if (TaleOptions.put(turn, price(t)) != TaleOptions.Put.DONE) {
            if (t.why.lastOrNull() === why) t.why.removeAt(t.why.size - 1)
            return@TaleOption think()
        }
        TaleReply(listOf(turn.say("released")), Activity.RECEIVE, ends = true)
    }

    override fun script(a: Ask, t: Tale, slot: String): Script? = null

    override fun topics(a: Ask, t: Tale, out: MutableList<Topic>) {}

    /** The driver at the gate has told the player of the second hand: the word is told, and at the meeting the tale moves on. */
    internal fun toldByDriver(c: Ctx, t: Tale, driver: String) {
        if (flag(t, TOLD)) return
        set(t, TOLD)
        c.why(t, "wagon.told", driver, price(t).toString())
        c.data.changed()
        if (t.step == AT_MEET) c.go(t, AT_CHOOSE)
    }
}
