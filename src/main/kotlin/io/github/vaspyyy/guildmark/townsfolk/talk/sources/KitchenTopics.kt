package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.market.Doors
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.cooking.market.Rec
import io.github.vaspyyy.guildmark.cooking.market.Request
import io.github.vaspyyy.guildmark.cooking.market.Requests
import io.github.vaspyyy.guildmark.cooking.meal.Meals
import io.github.vaspyyy.guildmark.cooking.vessel.Stations
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotMode
import io.github.vaspyyy.guildmark.cooking.vessel.Vessel
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Experience
import io.github.vaspyyy.guildmark.townsfolk.talk.Familiarity
import io.github.vaspyyy.guildmark.townsfolk.talk.Felt
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.Venue
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * What the town says of the kitchen (Hearthcraft, living-world/cook/design.md 8.1 and 8.4): the slate and the special order of
 * the day, what the town's palate likes, a dish of the player's that someone is still thinking about and the good supper
 * someone had, a stranger telling a Chilled player to find something hot, the three kitchen rumours as news, and a child's own
 * words about the smell, the bowl and what you are holding. And the choice "About the kitchen..." for the innkeeper and the
 * baker, which answers in at most three parts.
 *
 * Each line is a voiced family of [KitchenLines] with its changing facts (a dish, a number, a name) as unvoiced detail after
 * it, or, while the family has no take, one of its wordings as words with the same detail in grey. Everything is worked out
 * from the ledger, the calendar and what the resident remembers each time they are asked; nothing is kept here but two
 * counters for `cook perf`, and nothing is looked through but the cooking stations loaded near a child, or near the
 * innkeeper when she answers "About the kitchen...". Nothing at all while Kitchen
 * Talk is off ([Cooking.talk]); the rumours need the town to be passing them on ([Say.rumours]) or to have seen the thing
 * themselves, as the news does.
 */
object KitchenTopics : TopicSource {
    override val id = "kitchen"

    /** The talk screen's choice: "About the kitchen...". */
    const val ASK = "kitchen:ask"

    /** What `cook perf` reads: how many times the topics were asked for with Kitchen Talk on, and the nanoseconds that took. */
    @JvmField
    var asks = 0L

    @JvmField
    var nanos = 0L

    /** Where each topic may come up: a talk opening or answering, the street's barks, a chat between neighbours, the news. */
    private val STREET = setOf(Occasion.OPENER, Occasion.ANSWER, Occasion.BARK)
    private val OPENING = setOf(Occasion.OPENER, Occasion.ANSWER)
    private val IDLE = setOf(Occasion.OPENER, Occasion.CHAT)
    private val TOLD = Say.TALK + Occasion.NEWS + Occasion.BARK

    /** What a topic weighs before the speaker's leanings (8.1's table). */
    private const val SLATE_INNKEEPER = 0.5
    private const val SLATE_BAKER = 0.45
    private const val SLATE_ANYONE = 0.15
    private const val REQUEST = 0.55
    private const val PALATE = 0.2
    private const val RELISHED = 0.5
    private const val FEDWELL = 0.25
    private const val CHILLED = 0.5
    private const val RUMOUR = 0.7
    private const val SMELL = 0.4
    private const val SHARE = 0.6
    private const val HOT = 0.4

    /** The slate is called out in passing, and by anyone but the keepers, only this near the inn's walls (blocks). */
    private const val AT_THE_INN = 8

    /** A child smells a meal, and sees a bowl served, this near a station; hears a player holding a dish this near (blocks). */
    private const val STATION = 10
    private const val HOLDING = 8.0

    /** How far from the speaker a guest pot is looked for when the back pot is spoken of (blocks). */
    private const val POT = 40

    /** Below this heat a rumour is old news (told only when asked for news); below [GONE] it is not told; from [BIG] it is told to strangers and in passing. */
    private const val OLD = 0.1f
    private const val GONE = 0.03f
    private const val BIG = 0.7f

    /** What an old rumour weighs when asked for news, so it is told when nothing is fresher; how many a teller looks over. */
    private const val STALE = 0.12
    private const val KEPT = 12

    /** The most parts the answer to the choice has. */
    private const val PARTS = 3

    /** How much the choice presses: while the day's order is open to the player or they carry a dish, and otherwise. */
    private const val PRESSING = 0.7
    private const val PRESSING_LOW = 0.35

    /** What a lang key of a spoken line starts with, which Speech leaves off. */
    private const val LANG = "say.guildmark."

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (!Cooking.talk()) return
        val began = System.nanoTime()
        try {
            offered(ask, out)
        } finally {
            asks++
            nanos += System.nanoTime() - began
        }
    }

    private fun offered(ask: Ask, out: MutableList<Topic>) {
        if (Say.child(ask.speaker)) {
            children(ask, out)
            return
        }
        val occasion = ask.occasion
        if (occasion in STREET) {
            val today = todayOf(ask)
            if (today != null) {
                slate(ask, today, out)
                if (occasion in OPENING) request(ask, today, out)
            }
        }
        if (occasion in IDLE) {
            palate(ask, out)
            fedwell(ask, out)
        }
        if (occasion in OPENING) relished(ask, out)
        if (occasion == Occasion.OPENER || occasion == Occasion.BARK) chilled(ask, out)
        if (occasion in TOLD) told(ask, out)
    }

    // ---- What the inn wants today ----

    /**
     * What the ledger and the calendar say of the speaker's town on its day: the [centre] of its capital and how many [people] its
     * market counts, the three kinds the inn [wanted], the day's [request], and the day's [rec]ord of what sold (null while it is
     * another day's).
     */
    private class Today(val centre: BlockPos, val day: Long, val people: Int, val wanted: List<DishKind>, val request: Request?, val rec: Rec?)

    /**
     * Today's facts for the town [ask]'s speaker lives in; null for a village's folk (a back door is a capital's), with the back
     * doors or the capital markets off, and for a capital whose market has not opened: the Menu board is blank then, and the doors
     * are shut.
     */
    private fun todayOf(ask: Ask): Today? {
        if (!Cooking.doors() || !Markets.enabled()) return null
        val centre = (ask.life?.places as? CityPlaces)?.capital?.center ?: return null
        val key = centre.asLong()
        val people = Markets.find(ask.level, key)?.town?.people ?: return null
        val kitchens = Kitchens.get(ask.level)
        val day = Cooking.today(ask.level)
        val wanted = kitchens.wanted(ask.level, key, day)
        if (wanted.isEmpty()) return null
        return Today(centre, day, people, wanted, kitchens.request(ask.level, key, day), kitchens.rec(key)?.takeIf { it.day == day })
    }

    /**
     * The kinds a [role] tells of: of the three the inn is after, the innkeeper those the inn will still take today, the baker those
     * the bakery bakes and will still take. A kind that is full is not wanted: the Menu board greys it, and she would turn it away.
     */
    private fun kindsFor(role: TownRole, today: Today): List<DishKind> =
        if (role == TownRole.BAKER) today.wanted.filter { Door.BAKERY in it.doors && !full(today, Door.BAKERY, it) }
        else today.wanted.filter { !full(today, Door.INN, it) }

    /** Whether [door] will take no more of [kind] today: its day is full, or this kind's share of it is (the sums a sale makes, and the Menu board's "(full)"). */
    private fun full(today: Today, door: Door, kind: DishKind): Boolean {
        val rec = today.rec ?: return false
        return rec.soldAt(door) >= Doors.cap(door, today.people) || rec.sold[door.ordinal][kind.ordinal] >= Doors.kindCap(door, today.people)
    }

    /** The standing of [player] in the capital, 0 (a stranger) to 4 (its purveyor); 0 with standing off. */
    private fun rank(level: ServerLevel, today: Today, player: ServerPlayer): Int = Suppliers.rank(level, today.centre, player.uuid)

    /** The day's request if it is open to [player] to fill: not filled yet, and from a cook the innkeeper knows (anyone's with standing off). */
    private fun open(level: ServerLevel, today: Today, player: ServerPlayer): Request? =
        today.request?.takeIf { !it.filled && Requests.open(rank(level, today, player)) }

    /**
     * The slate: the innkeeper tells the kinds of the three the inn will still take today, the baker those of them she bakes and will
     * still take, anyone by the inn now and then (as the Menu board has it). None when none is left to tell. In passing, and from
     * anyone but the keepers, only within a few blocks of the inn.
     */
    private fun slate(ask: Ask, today: Today, out: MutableList<Topic>) {
        val role = ask.speaker.role
        val keeper = role == TownRole.INNKEEPER || role == TownRole.BAKER
        if ((ask.occasion == Occasion.BARK || !keeper) && !nearInn(ask, AT_THE_INN)) return
        val kinds = kindsFor(role, today)
        if (kinds.isEmpty()) return
        val say = KitchenLines.line(ask, "kitchen.slate", slateDetail(kinds, null)) ?: return
        val topic = when (role) {
            TownRole.INNKEEPER -> Topic("kitchen.slate.${today.day}", Kind.KITCHEN, SLATE_INNKEEPER, say)
            TownRole.BAKER -> Topic("kitchen.slate.${today.day}.baker", Kind.KITCHEN, SLATE_BAKER, say)
            else -> Topic("kitchen.slate.${today.day}", Kind.KITCHEN, SLATE_ANYONE, say)
        }
        out.add(topic)
    }

    /** The special order, from the innkeeper to a cook she knows, while it is open. */
    private fun request(ask: Ask, today: Today, out: MutableList<Topic>) {
        if (ask.speaker.role != TownRole.INNKEEPER) return
        val player = ask.player ?: return
        val request = open(ask.level, today, player) ?: return
        val say = KitchenLines.line(ask, "kitchen.request", requestDetail(request)) ?: return
        out.add(Topic("kitchen.request.${today.day}", Kind.KITCHEN, REQUEST, say, Familiarity.MET))
    }

    // ---- What the town likes and has eaten ----

    /** What the town likes, from its innkeeper and its baker, by the look of the town (the city style's liked dishes, Tastes.LIKES). */
    private fun palate(ask: Ask, out: MutableList<Topic>) {
        val role = ask.speaker.role
        if (role != TownRole.INNKEEPER && role != TownRole.BAKER) return
        val style = (ask.life?.places as? CityPlaces)?.capital?.style ?: return
        val family = "kitchen.palate." + style.name.lowercase()
        val say = KitchenLines.line(ask, family, null) ?: return
        out.add(Topic(family, Kind.KITCHEN, PALATE, say))
    }

    /**
     * A dish of the player's that someone is still thinking about (a feast in the last week on one of their dishes, FEASTED), with
     * its kind after it. They need only know of the player, not to have met them: a diner the ledger drew at a meal hour has eaten
     * the cook's dish, and knows who cooked it from the board.
     */
    private fun relished(ask: Ask, out: MutableList<Topic>) {
        val player = ask.player ?: return
        val r = ask.resident ?: return
        val felt = TownTalk.felt(ask.level, r)
        for (feast in Feasted.among(felt).filter { Feasted.of(it, player.uuid) }.take(Feasted.MOST)) {
            if (hungry(felt, feast)) continue
            val kind = Feasted.kind(feast)
            val note = kind?.let { detail("say.guildmark.talk.detail.kitchen.relished", KitchenRumours.dish(it)) }
            val say = KitchenLines.line(ask, "kitchen.relished", note) ?: continue
            out.add(Topic("kitchen.relished.${feast.day}.${kind?.id ?: "x"}", Kind.KITCHEN, RELISHED, say, Familiarity.HEARD_OF, tone = 1))
        }
    }

    /** A good supper, said to a neighbour or in passing, by anyone who has feasted in the last week on someone else's dish. */
    private fun fedwell(ask: Ask, out: MutableList<Topic>) {
        val r = ask.resident ?: return
        val player = ask.player
        val felt = TownTalk.felt(ask.level, r)
        val feast = Feasted.among(felt).firstOrNull { player == null || !Feasted.of(it, player.uuid) } ?: return
        if (hungry(felt, feast)) return
        val say = KitchenLines.line(ask, "kitchen.fedwell", null) ?: return
        out.add(Topic("kitchen.fedwell", Kind.KITCHEN, FEDWELL, say, tone = 1))
    }

    /** Whether they have gone hungry since [feast]: then a good meal is not what they would be saying. */
    private fun hungry(felt: List<Felt>, feast: Felt): Boolean = felt.any { it.experience == Experience.HUNGRY && it.day > feast.day }

    /** A Chilled player, told by any adult to find something hot, pointing at the inn if they know it. */
    private fun chilled(ask: Ask, out: MutableList<Topic>) {
        val player = ask.player ?: return
        if (!Meals.chilled(player)) return
        val door = inn(ask)?.front?.pos
        val say = KitchenLines.line(ask, "kitchen.chilled", null) ?: return
        out.add(Topic("kitchen.chilled", Kind.KITCHEN, CHILLED, say, tone = -1, gesture = if (door != null) Activity.POINT else Activity.TALK, toward = door))
    }

    // ---- The rumours ----

    /**
     * The three kitchen rumours as news, as NewsTopics tells the others (and leaves these to us): what the speaker has heard, hottest
     * first, each as its family with the version they had of it after it. Told to a player once in each version; in passing only the
     * big news. With the town not passing news on, only what they saw themselves.
     */
    private fun told(ask: Ask, out: MutableList<Topic>) {
        val r = ask.resident ?: return
        val level = ask.level
        val known = TownTalk.known(level, r).filter { KitchenRumours.ours(it.rumour.kind) }
        if (known.isEmpty()) return
        val rumours = Say.rumours()
        val player = ask.player
        val theirs = if (player != null) TownTalk.heard(level, r.capital, player.uuid) else mapOf()
        val partner = ask.listener?.takeIf { it.resident >= 0 }?.let { Residents.get(level)[it.resident] }
        val partnerKnows = partner?.let { p -> TownTalk.known(level, p).mapTo(HashSet()) { it.rumour.id } } ?: setOf()
        val asked = ask.occasion == Occasion.NEWS
        val bark = ask.occasion == Occasion.BARK
        for (k in known.take(KEPT)) {
            val rumour = k.rumour
            val heard = k.heard
            if (rumour.heat < GONE || rumour.heat < OLD && !asked || bark && rumour.heat < BIG) continue
            // With the news not going round, only what they saw and what the crier cried
            if (!rumours && heard.venue != Venue.SEEN && heard.from != -1) continue
            if (rumour.id in partnerKnows) continue
            val warp = heard.warp.coerceIn(0, 3)
            val had = theirs[rumour.id]
            if (had != null && (had == warp || bark)) continue
            val family = "kitchen.told." + rumour.kind.substringAfter('.')
            val say = KitchenLines.line(ask, family, toldDetail(rumour.kind, rumour.args, warp)) ?: continue
            val weight = (RUMOUR * rumour.heat).let { if (rumour.heat < OLD) maxOf(it, STALE) else it }
            val least = if (player == null || rumour.heat >= BIG) Familiarity.STRANGER else Familiarity.MET
            val gesture = if (warp == 3 && !ask.speaker.activity.seated && !bark) Activity.RECOUNT else null
            out.add(Topic("$family.${rumour.id}.$warp", Kind.NEWS, weight, say, least, rumour.tone, gesture, rumour = rumour.id, about = rumour.about))
        }
    }

    /**
     * What a rumour of [kind] with its facts [args] is told with in version [warp], after the family's words: as it happened or grown,
     * muddled (the name lost) or a tall tale. None for a rumour that lacks its facts.
     */
    private fun toldDetail(kind: String, args: List<String>, warp: Int): String? {
        val name = args.getOrNull(0)
        val dish = args.getOrNull(1)
        return when (kind) {
            KitchenRumours.HALLMARK -> {
                if (name == null || dish == null) return null
                when (warp) {
                    2 -> detail("say.guildmark.talk.detail.kitchen.told.hallmark.muddled", dish)
                    3 -> detail("say.guildmark.talk.detail.kitchen.told.hallmark.tall", name, dish)
                    else -> when (args.getOrNull(2)) {
                        KitchenRumours.AT_INN -> detail("say.guildmark.talk.detail.kitchen.told.hallmark.inn", name, dish)
                        KitchenRumours.AT_BAKERY -> detail("say.guildmark.talk.detail.kitchen.told.hallmark.bakery", name, dish)
                        else -> detail("say.guildmark.talk.detail.kitchen.told.hallmark", name, dish)
                    }
                }
            }
            KitchenRumours.COOKOFF -> {
                if (name == null || dish == null) return null
                when (warp) {
                    2 -> detail("say.guildmark.talk.detail.kitchen.told.cookoff.muddled", dish)
                    3 -> detail("say.guildmark.talk.detail.kitchen.told.cookoff.tall", name, dish)
                    else -> detail("say.guildmark.talk.detail.kitchen.told.cookoff", name, dish)
                }
            }
            KitchenRumours.BURNT -> when (warp) {
                2 -> detail("say.guildmark.talk.detail.kitchen.told.burnt.muddled")
                3 -> detail("say.guildmark.talk.detail.kitchen.told.burnt.tall")
                else -> detail("say.guildmark.talk.detail.kitchen.told.burnt")
            }
            else -> null
        }
    }

    // ---- The children ----

    /** A child's own words: what smells good, what the player holds, a bowl just served. */
    private fun children(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion != Occasion.OPENER && ask.occasion != Occasion.BARK && ask.occasion != Occasion.CHAT) return
        val stations = Stations.near(ask.level, ask.speaker.blockPosition(), STATION).map { it.view() }
        if (stations.any { it.state == Vessel.State.SERVED.ordinal && it.bowls > 0 }) {
            KitchenLines.line(ask, "kitchen.child.hot", null)?.let { out.add(Topic("kitchen.child.hot", Kind.KITCHEN, HOT, it)) }
        }
        val player = ask.player
        if (player != null && holdsDish(player) && player.distanceToSqr(ask.speaker) <= HOLDING * HOLDING) {
            KitchenLines.line(ask, "kitchen.child.share", null)?.let { out.add(Topic("kitchen.child.share", Kind.KITCHEN, SHARE, it)) }
        }
        if (stations.any { it.state in Vessel.State.COOKING.ordinal..Vessel.State.DONE.ordinal }) {
            KitchenLines.line(ask, "kitchen.child.smell", null)?.let { out.add(Topic("kitchen.child.smell", Kind.KITCHEN, SMELL, it)) }
        }
    }

    /** Whether [player] holds a dish in either hand. */
    private fun holdsDish(player: ServerPlayer): Boolean =
        Dishes.kindOf(player.mainHandItem) != null || Dishes.kindOf(player.offhandItem) != null

    // ---- Where the inn is ----

    /** The inn nearest the speaker, if their town's places are known to them. */
    private fun inn(ask: Ask): CityPlaces.Lodging? = ask.life?.places?.nearest(ask.speaker.blockPosition(), CityPlaces.TAVERNS)?.firstOrNull()

    /** Whether the speaker stands within [blocks] of the inn's walls. */
    private fun nearInn(ask: Ask, blocks: Int): Boolean = inn(ask)?.box?.inflatedBy(blocks)?.isInside(ask.speaker.blockPosition()) == true

    // ---- The choice and its answer ----

    /**
     * "About the kitchen..." from the innkeeper and the baker of a capital, to a player with any Cooking level or a dish in the
     * pack: pressing while the day's order is open to them or they carry a dish, else less.
     */
    override fun choice(ask: Ask): Choice? {
        if (!Cooking.talk()) return null
        val player = ask.player ?: return null
        val role = ask.speaker.role
        if (Say.child(ask.speaker) || role != TownRole.INNKEEPER && role != TownRole.BAKER) return null
        val dish = carriesDish(player)
        if (!dish && Skills.level(player, Skill.COOKING) <= 0) return null
        val today = todayOf(ask) ?: return null
        val pressing = if (dish || open(ask.level, today, player) != null) PRESSING else PRESSING_LOW
        return Choice(ASK, Component.translatable("gui.guildmark.talk.kitchen"), pressing = pressing)
    }

    /** Whether [player] has a dish anywhere in the pack. */
    private fun carriesDish(player: ServerPlayer): Boolean =
        Dishes.kindOf(player.offhandItem) != null || player.inventory.nonEquipmentItems.any { Dishes.kindOf(it) != null }

    /**
     * What the slate wants and what the season calls for, the special order or that there is none, and how to sell: three parts, each
     * a voiced family with its detail; the sentences with no family of their own (the season's, the back pot's, the cook of the day's)
     * follow as the wording's text in grey. The baker tells the baked kinds and how to sell them.
     */
    override fun answer(ask: Ask, choice: String): Utterance? {
        if (choice != ASK || !Cooking.talk()) return null
        val player = ask.player ?: return null
        val role = ask.speaker.role
        if (role != TownRole.INNKEEPER && role != TownRole.BAKER) return null
        val today = todayOf(ask) ?: return null
        val parts = ArrayList<Utterance.Part>(PARTS)
        val kinds = kindsFor(role, today)
        if (kinds.isNotEmpty()) KitchenLines.part(ask, "kitchen.slate", slateDetail(kinds, season(ask, today)))?.let(parts::add)
        if (role == TownRole.INNKEEPER) {
            val request = open(ask.level, today, player)
            val order = if (request != null) KitchenLines.part(ask, "kitchen.request", requestDetail(request)) else KitchenLines.part(ask, "kitchen.none", null)
            order?.let(parts::add)
        }
        KitchenLines.part(ask, "kitchen.backdoor", selling(ask, today, player))?.let(parts::add)
        return if (parts.isEmpty()) null else Utterance(parts.take(PARTS))
    }

    /** The lang key of a wording of what the inn thinks of this season (winter's, autumn's), for the end of the slate's detail; none for the other seasons. */
    private fun season(ask: Ask, today: Today): String? {
        val family = when (Requests.season(ask.level, today.day)) {
            Season.WINTER -> "kitchen.season.winter"
            Season.AUTUMN -> "kitchen.season.autumn"
            else -> return null
        }
        return wording(ask, family)
    }

    /**
     * The grey words after "Hold it out to me and I'll taste it.": that the back pot is the player's to use, once the capital knows
     * them and the inn has one (the innkeeper says so), and that they are the cook of the day (a Purveyor who sold the most today).
     */
    private fun selling(ask: Ask, today: Today, player: ServerPlayer): String? {
        val rank = rank(ask.level, today, player)
        val pot = ask.speaker.role == TownRole.INNKEEPER && (rank >= SupplierRank.KNOWN.ordinal || !Suppliers.enabled()) && backPot(ask)
        val first = if (pot) wording(ask, "kitchen.guest.yes") else null
        val second = if (rank >= SupplierRank.PURVEYOR.ordinal && cookOfTheDay(today) == player.uuid) wording(ask, "kitchen.purveyor") else null
        return when {
            first != null && second != null -> detail("say.guildmark.talk.detail.kitchen.back.both", first, second)
            first != null -> detail("say.guildmark.talk.detail.kitchen.back", first)
            second != null -> detail("say.guildmark.talk.detail.kitchen.back", second)
            else -> null
        }
    }

    /** The lang key of a wording of [family] for the speaker to say to the player, as a detail's argument (its text shows in grey). */
    private fun wording(ask: Ask, family: String): String? = KitchenLines.wording(ask.speaker, ask.player, family)?.let { LANG + it }

    /** Whether the inn has a back pot: a Stewpot in guest mode stands loaded near the speaker (so it is said of the pot she can see, and of none an old town never had). */
    private fun backPot(ask: Ask): Boolean =
        Stations.near(ask.level, ask.speaker.blockPosition(), POT).any { it is StewpotBlockEntity && it.mode == StewpotMode.GUEST }

    /** Who sold the most today at the inn and the bakery between them (as the menu board counts it), if anyone did. */
    private fun cookOfTheDay(today: Today): UUID? = today.rec?.mine?.entries
        ?.map { it.key to it.value.getOrElse(Door.INN.ordinal) { 0 } + it.value.getOrElse(Door.BAKERY.ordinal) { 0 } }
        ?.filter { it.second > 0 }
        ?.sortedWith(compareByDescending<Pair<UUID, Int>> { it.second }.thenBy { it.first })
        ?.firstOrNull()?.first

    // ---- The details ----

    /** The detail line [key] (a full lang key) with [args], in the form Speech has it: the key without its first words. */
    private fun detail(key: String, vararg args: Any): String = Speech.of(key.removePrefix(LANG), *args)

    /** The kinds on the slate in words ("Drover's Stew, Raised Pie and Honey Cakes."), then [season] (a wording's lang key) when there is one. */
    private fun slateDetail(kinds: List<DishKind>, season: String?): String {
        val names = kinds.take(Requests.WANTED).map { KitchenRumours.dish(it) }
        val key = if (season == null) "say.guildmark.talk.detail.kitchen.slate.${names.size}" else "say.guildmark.talk.detail.kitchen.slate.${names.size}.season"
        return detail(key, *(names + listOfNotNull(season)).toTypedArray())
    }

    /** The request in words ("Raised Pie x5, before the day is out."). */
    private fun requestDetail(request: Request): String =
        detail("say.guildmark.talk.detail.kitchen.request", KitchenRumours.dish(request.kind), request.portions)
}
