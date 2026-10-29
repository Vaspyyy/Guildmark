package io.github.vaspyyy.guildmark.village.live.talk

import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.economy.fortune.FortuneData
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.guild.NewsItem
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Familiarity
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumour
import io.github.vaspyyy.guildmark.townsfolk.talk.RumourWords
import io.github.vaspyyy.guildmark.townsfolk.talk.Scope
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.build.VillageBuilds
import io.github.vaspyyy.guildmark.village.live.days.BellKeeper
import io.github.vaspyyy.guildmark.village.live.links.VillageLinks
import io.github.vaspyyy.guildmark.village.live.links.VillageNews
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster
import net.minecraft.core.BlockPos

/**
 * What a village's folk talk of (living-world/villages/design.md 8.3 and 17.4's talk families), each drawn from a record and said
 * only while it is true:
 *
 * - **the cart**: the wagon in today (from which capital, where one road links it), no wagon for more than
 *   [PedlarRoster.WAGON_DAYS] days to a village a road links; the pedlar due today or tomorrow (by name), a visit lost lately (why,
 *   only as the cart's record has it);
 * - **the bell**: the holder's own week of it, or who has it;
 * - **the watch**: the village has guards of its own;
 * - **the siege**: one held or lost in the last 10 days (from the board, which keeps them), and anyone's not wanting to go through it
 *   again for 3 days after a siege or a raid beaten off;
 * - **the dead**: anyone of the village who died in the last 10 days, by name, the candle's words only where one was laid;
 * - **the new ones**: a birth or newcomers in the chronicle;
 * - **the capital**: word the wagon brought from the city ([VillageNews.items], where a road links it);
 * - **the building**: a crew at work on a project, a project finished in the last 7 days;
 * - **mended**: a mend in the chronicle in the last 3 days;
 * - **fed**: to a player who supplied the village in the last 5 days, as its books have it.
 *
 * Not said, for want of a record (V7's wagons keep none on this base): the wagon due, and the wagon taken on the road. The harvest
 * and the market are the market source's, with a village's market (Say.market). Off with Village Bellman.
 */
object VillageTopics : TopicSource {
    override val id = "village"

    private val NEWS = Say.TALK + Occasion.NEWS

    /** Days a siege, a death stays talked of; a birth, a mend, the fear after; newcomers, a supply; a finished project. */
    private const val SIEGE = 10L
    private const val DEAD = 10L
    private const val BORN = 3L
    private const val AFTER = 3L
    private const val NEWCOMERS = 5L
    private const val FED = 4L
    private const val BUILT = 7L

    /** The building crew's hours (VillageDays.BUILD: seven to a quarter to six). */
    private const val CREW_FROM = 7.0
    private const val CREW_TO = 17.75

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in NEWS || !VillageCry.enabled() || !Days.enabled()) return
        val r = ask.resident ?: return
        if (!r.alive) return
        val level = ask.level
        val key = r.capital.asLong()
        val rec = Villages.byKey(level, key) ?: return
        val news = ask.occasion == Occasion.NEWS
        val today = VillageBoards.day(level)
        val day = Days.dayOf(level.overworldClockTime)
        val child = Say.child(ask.speaker)

        // ---- The cart ----
        val cart = VillageMarkets.rec(level, key)?.cart
        val links = VillageLinks.linked(level, key)
        if (cart != null && !child) {
            if (cart.wagon != PedlarRoster.NONE && Conditions(cart.wagon).day == day) {
                val from = links.singleOrNull()?.let { capitalName(level, it.capital) }
                add(ask, out, "village.cart.in", "village.cart.in.${cart.wagon}", Kind.ROAD, 0.6, 1, from?.let { Speech.of("talk.detail.village.from", "=$it") })
            } else if (links.isNotEmpty() && cart.wagon != PedlarRoster.NONE && day - Conditions(cart.wagon).day > PedlarRoster.WAGON_DAYS && !news) {
                add(ask, out, "village.cart.late", "village.cart.late.${cart.wagon}", Kind.ROAD, 0.4, -1, Speech.of("talk.detail.village.since", day - Conditions(cart.wagon).day))
            }
            if (VillageMarkets.pedlars()) {
                val person = PedlarRoster.of(key, cart.slain).name
                val due = when {
                    cart.decided == day && cart.comes && cart.called != day -> "today"
                    cart.next == day + 1 && !Days.restDay(day + 1) -> "tomorrow"
                    else -> null
                }
                if (due != null && !news) add(ask, out, "village.pedlar.due", "village.pedlar.due.${day}.$due", Kind.MARKET, 0.4, 1, Speech.of("talk.detail.village.pedlar.$due", "=$person"))
            }
            if (cart.missed != PedlarRoster.NONE && day - cart.missed in 0..AFTER) {
                val why = PedlarRoster.Why.of(cart.why)?.takeIf { cart.decided == cart.missed }
                val detail = when (why) {
                    PedlarRoster.Why.BAND -> "talk.detail.village.why.band"
                    PedlarRoster.Why.SLAIN -> "talk.detail.village.why.slain"
                    else -> null
                }
                add(ask, out, "village.pedlar.missed", "village.pedlar.missed.${cart.missed}", Kind.MARKET, 0.5, -1, detail)
            }
        }

        // ---- The bell, and the watch ----
        val centre = BlockPos.of(key)
        val holder = BellKeeper.holder(level, centre, day)
        if (holder != null && !news && !child) {
            if (holder == r.id) {
                add(ask, out, "village.bell.mine", "village.bell.mine.$day", Kind.HOUSEHOLD, 0.35, 0, null)
            } else {
                Residents.get(level)[holder]?.takeIf { it.alive }?.let { h ->
                    add(ask, out, "village.bell.other", "village.bell.other.$day.${h.id}", Kind.HOUSEHOLD, 0.2, 0, Speech.of("talk.detail.village.who", "=${h.name}"))
                }
            }
        }
        val folk = Residents.get(level).byCentre(centre)
        if (!news && folk.any { it.alive && it.role == TownRole.GUARD && it.id != r.id }) {
            add(ask, out, "village.watch", "village.watch", Kind.EVENT, 0.15, 1, null)
        }

        // ---- The siege, and the night after ----
        val board = VillageCry.board(level, rec)
        if (board != null) {
            val held = today - board.lastSiegeWon
            val lost = today - board.lastSiegeLost
            if (board.lastSiegeWon >= 0 && held in 0..SIEGE && (board.lastSiegeLost < board.lastSiegeWon || lost !in 0..SIEGE)) {
                add(ask, out, "village.siege.held", "village.siege.held.${board.lastSiegeWon}", Kind.EVENT, 0.8 - 0.4 * held / SIEGE, 1, Say.agoDetail(held), Activity.CHEER.takeIf { held == 0L })
            } else if (board.lastSiegeLost >= 0 && lost in 0..SIEGE) {
                add(ask, out, "village.siege.lost", "village.siege.lost.${board.lastSiegeLost}", Kind.EVENT, 0.8 - 0.4 * lost / SIEGE, -1, Say.agoDetail(lost), Activity.SIGH)
            }
        }
        val fought = listOfNotNull(
            board?.lastSiegeWon?.takeIf { it >= 0 && today - it in 0..AFTER },
            board?.lastSiegeLost?.takeIf { it >= 0 && today - it in 0..AFTER },
            raided(level, rec, today),
        ).maxOrNull()
        if (fought != null && !news) add(ask, out, "village.shelter.after", "village.shelter.after.$fought", Kind.EVENT, 0.5, -1, null, Activity.SIGH)

        // ---- The dead, and the new ones ----
        val residents = Residents.get(level)
        folk.filter { !it.alive && it.died >= 0 && today - it.died in 0..DEAD && it.id != r.id }.maxByOrNull { it.died }?.let { dead ->
            val days = today - dead.died
            val detail = when (days) {
                0L -> Speech.of("talk.detail.home.grief.0", "=${dead.name}")
                1L -> Speech.of("talk.detail.home.grief.1", "=${dead.name}")
                else -> Speech.of("talk.detail.home.grief", "=${dead.name}", days)
            }
            // The candle's words only for one a candle was laid for
            val take = if (VillageCry.candleFor(level, key, dead.name, DEAD)) "talk.village.dead_0" else "talk.village.dead_1"
            val say = Utterance(listOf(Utterance.Part(take, detail)))
            out.add(Topic("village.dead.${dead.id}", Kind.HOUSEHOLD, 0.9 - 0.5 * days / DEAD, say, Familiarity.STRANGER, -1, Activity.SIGH))
        }
        for (ch in rec.chronicle) {
            val (family, within, weight) = when (ch.kind) {
                "born" -> Triple("village.born", BORN, 0.7)
                "arrived" -> Triple("village.newcomers", NEWCOMERS, 0.5)
                "mended" -> Triple("village.mended", AFTER, 0.4)
                else -> continue
            }
            if (today - ch.day !in 0..within) continue
            add(ask, out, family, "$family.${ch.day}.${ch.arg}", if (family == "village.mended") Kind.WORKS else Kind.HOUSEHOLD, weight, 1, null)
        }

        // ---- Word from the city ----
        if (links.isNotEmpty() && !child) {
            VillageNews.items(level, key).firstOrNull()?.let { n ->
                add(ask, out, "village.capital", "village.capital.${n.day}.${n.key}", Kind.NEWS, 0.6, 0, detail(level, n, centre))
            }
        }

        // ---- The building ----
        val clock = level.overworldClockTime
        VillageBuilds.recFor(level, key)?.takeIf { it.opens == Long.MIN_VALUE || it.opens <= clock }?.let { b ->
            val project = Advance.entries.getOrNull(b.project) ?: return@let
            val hour = Say.hour(level)
            // "Hard at it" only while the crew is
            val at = !Days.restDay(day) && hour >= CREW_FROM && hour < CREW_TO
            val take = if (at) Say.take(ask, "village.build.going") else "talk.village.build.going_0"
            if (take.isEmpty() || news) return@let
            val say = Utterance(listOf(Utterance.Part(take, Speech.of("talk.detail.village.project.going", "advance.guildmark.${project.id}"))))
            out.add(Topic("village.build.going.${b.id}", Kind.WORKS, 0.35, say, Familiarity.STRANGER, 1))
        }
        VillageCry.builtAt(level, key).lastOrNull { today - it.day in 0..BUILT }?.let { b ->
            val project = Advance.entries.getOrNull(b.project) ?: return@let
            add(ask, out, "village.build.done", "village.build.done.${b.day}.${b.project}", Kind.WORKS, 0.6 - 0.3 * (today - b.day) / BUILT, 1, Speech.of("talk.detail.village.project.done", "advance.guildmark.${project.id}"))
        }

        // ---- The one who fed them ----
        val player = ask.player
        if (player != null && !child && VillageMarkets.fortunes()) {
            val market = Fortunes.today(level, key)
            val notes = FortuneData.get(level)[key]?.notes.orEmpty()
            notes.filter { !it.hand && it.kind == FortuneReasons.SUPPLIED && it.about == player.uuid && market - it.from in 0..FED }.maxByOrNull { it.from }?.let { n ->
                val say = Say.line(ask, "village.fed", Say.agoDetail(market - n.from)) ?: return@let
                out.add(Topic("village.fed.${n.from}", Kind.DEALINGS, 0.9, say, Familiarity.STRANGER, 1, Activity.THANK, about = player.uuid))
            }
        }
    }

    /** [family] said with [detail], as a topic of [kind]. */
    private fun add(ask: Ask, out: MutableList<Topic>, family: String, id: String, kind: Kind, weight: Double, tone: Int, detail: String?, gesture: Activity? = null) {
        val say = Say.line(ask, family, detail) ?: return
        out.add(Topic(id, kind, weight, say, Familiarity.STRANGER, tone, gesture))
    }

    /** The town day of the last raid beaten off at [rec] in the last [AFTER] days, as the guild's news has it; null if none. */
    private fun raided(level: net.minecraft.server.level.ServerLevel, rec: VillageRec, today: Long): Long? =
        GuildNews.get(level).items.filter { it.key == "news.guildmark.outlaw.raid_beaten" && it.args.lastOrNull() == rec.name && today - it.day in 0..AFTER }.maxOfOrNull { it.day }

    /** The capital [key]'s name, as its caravans keep it; null if none. */
    private fun capitalName(level: net.minecraft.server.level.ServerLevel, key: Long): String? =
        io.github.vaspyyy.guildmark.economy.CaravanData.get(level).capitalOrNull(key)?.name?.takeIf { it.isNotEmpty() }

    /** The talk's own words for the guild's news [n], as a capital's folk would tell it heard straight (RumourWords); null if it has none. */
    private fun detail(level: net.minecraft.server.level.ServerLevel, n: NewsItem, from: BlockPos): String? {
        val (kind, how, tone) = Witness.guildKind(n.key) ?: return null
        val words = n.args.map { a ->
            when {
                a.startsWith("#") -> a.substring(1)
                a.toIntOrNull() != null -> a
                else -> Witness.named(a)
            }
        }
        val rumour = Rumour(-1, kind, null, -1, words, n.day * 24L, how.first, how.second, Scope.TOWN, tone, null)
        return RumourWords.detail(level, rumour, 0, from)
    }
}
