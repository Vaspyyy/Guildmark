package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Known
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumour
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumours
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.trade.Sight
import io.github.vaspyyy.guildmark.trade.Tally
import io.github.vaspyyy.guildmark.trade.TallyLinks
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.Word
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import kotlin.math.roundToInt

/**
 * What the townsfolk say of the other towns (living-world/trader/design.md, Part 1, "What you hear and are told"): the freshest word a person has heard
 * of a town short of something or glutted with it (and, once a delivery has been talked of, a consignment brought in), each said in their own voice with the
 * town and the good after it in grey, "The east town is short of iron; so I heard two days ago."; the hall's merchants and the inn's keeper give what the
 * Haul Board says of the nearest town that has news; the gate's guards, the road's trouble, as advice to someone laden; and anyone who has had a ticket
 * filled in their town lately is thanked for it. "About the trade..." asks the freshest of these, and is among the choices only while there is something
 * to tell (always in a merchant's talk). It is registered with the Word ([Word.register]), and every fact is checked each time it is asked, so the switches
 * work without a restart: with the trader's life off nothing is offered, with the Word off a board is blank, and a rumour is told as the talk switches have it.
 *
 * A rumour told wrongly (a muddled or tall telling) loses its town: "Some town is short of iron".
 */
object TradeTopics : TopicSource {
    /** Not "trade": the talk's own trade source (what a person's work has them at) has that id, and a second with the same id is ignored. */
    override val id = "tradeword"

    private const val ASK = "tradeword:ask"

    private val OCCASIONS = Say.TALK + Occasion.NEWS + Occasion.BARK

    /** The talk families the rumours are told in, by kind. */
    private val FAMILY = mapOf(
        Rumours.TRADE_SHORT to "trade.short",
        Rumours.TRADE_GLUT to "trade.glut",
        Rumours.TRADE_HAULED to "trade.hauled",
    )

    /** A rumour weighs this times its heat (the town's news weighs 0.7); below [OLD] it is told only when news is asked for, as little as [STALE]; in passing, only from [BARK_AT]. */
    private const val RUMOUR = 0.6
    private const val OLD = 0.1f
    private const val STALE = 0.12
    private const val BARK_AT = 0.4f

    /** How many days a delivery is thanked for. */
    private const val THANKS_DAYS = 3L

    /** What the answer to "About the trade..." weighs against the other choices ("the screen has room for two"): below hard times and a works going up, a merchant's just under them. */
    private const val PRESSING = 0.3
    private const val MERCHANT_PRESSING = 0.69

    /** Past this many hours the news is told in days. */
    private const val DAYS_FROM = 48.0

    // ---- What they say as they go ----

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (!TradeConfig.life() || ask.occasion !in OCCASIONS || Say.child(ask.speaker)) return
        val bark = ask.occasion == Occasion.BARK
        val asked = ask.occasion == Occasion.NEWS
        heard(ask, bark, asked, out)
        if (bark) return
        thanks(ask)?.let { part -> out.add(Topic("trade.thanks.${Say.day(ask.level)}", Kind.DEALINGS, 0.6, Utterance(listOf(part)), tone = 1, about = ask.player?.uuid)) }
        val role = ask.speaker.role
        if (role == TownRole.MERCHANT || role == TownRole.INNKEEPER) {
            board(ask, role == TownRole.MERCHANT)?.let { (key, part) -> out.add(Topic("trade.board.$key", Kind.TRADE, 0.4, Utterance(listOf(part)))) }
        }
        if (role == TownRole.GUARD) {
            road(ask)?.let { (stage, part) -> out.add(Topic("trade.road.${stage.id}", Kind.ROAD, 0.5 + 0.1 * stage.ordinal, Utterance(listOf(part)), tone = -1)) }
        }
    }

    /** The rumours of the trade the speaker has heard, hottest first. */
    private fun known(ask: Ask): List<Known> {
        val r = ask.resident ?: return listOf()
        return TownTalk.known(ask.level, r).filter { it.rumour.kind in FAMILY }
    }

    private fun heard(ask: Ask, bark: Boolean, asked: Boolean, out: MutableList<Topic>) {
        val r = ask.resident ?: return
        val level = ask.level
        val known = known(ask)
        if (known.isEmpty()) return
        val player = ask.player
        val theirs = if (player != null) TownTalk.heard(level, r.capital, player.uuid) else mapOf()
        for (k in known) {
            val rumour = k.rumour
            val warp = k.heard.warp.coerceIn(0, 3)
            if (rumour.heat < OLD && !asked || bark && rumour.heat < BARK_AT) continue
            // The one a rumour is about is thanked, not told of; and what they have heard already, in this telling or a truer, is not told again
            if (player != null && rumour.about == player.uuid) continue
            val had = theirs[rumour.id]
            if (had != null && had <= warp) continue
            val part = part(ask, rumour, warp) ?: continue
            val weight = (RUMOUR * rumour.heat).let { if (rumour.heat < OLD) maxOf(it, STALE) else it }
            out.add(Topic("trade.word.${rumour.id}.$warp", Kind.TRADE, weight, Utterance(listOf(part)), tone = rumour.tone, rumour = rumour.id, about = rumour.about))
        }
    }

    // ---- "About the trade..." ----

    /** More pressing in a merchant's talk, where there is always word of the board; else only while there is something to tell. */
    override fun choice(ask: Ask): Choice? {
        if (!TradeConfig.life() || Say.child(ask.speaker) || ask.player == null || ask.resident == null) return null
        val merchant = ask.speaker.role == TownRole.MERCHANT
        if (!merchant && tell(ask) == null) return null
        return Choice(ASK, Component.translatable("gui.guildmark.talk.trade"), pressing = if (merchant) MERCHANT_PRESSING else PRESSING)
    }

    /**
     * The freshest thing they have heard of the other towns, with what their place gives them to say first: the board's word, the road's, a thanks.
     * A merchant asked by someone with no Tally hands them one first (the book is free from the hall's merchant: the first time they ask, and again if
     * theirs is lost).
     */
    override fun answer(ask: Ask, choice: String): Utterance? {
        if (choice != ASK) return null
        val player = ask.player
        val handed = if (player != null) handOver(ask, player) else null
        val (said, rumour) = tell(ask, handed) ?: return null
        if (rumour != null && player != null) TownTalk.told(ask.level, ask.resident, player, rumour)
        return said
    }

    /** The hall's merchant gives [player], who carries no Tally, one, and says so: the part that says it, or null if no book was given. */
    private fun handOver(ask: Ask, player: ServerPlayer): Utterance.Part? {
        if (ask.speaker.role != TownRole.MERCHANT || Tally.has(player)) return null
        Tally.give(ask.level, player)
        return Utterance.Part("trade_tally_${ask.speaker.random.nextInt(2)}")
    }

    /** What [ask]'s speaker would tell, at most two parts (the [first] given if there is one), and the rumour it tells of if one is; null if they have nothing. */
    private fun tell(ask: Ask, first: Utterance.Part? = null): Pair<Utterance, Int?>? {
        if (!TradeConfig.life() || Say.child(ask.speaker)) return null
        val parts = ArrayList<Utterance.Part>()
        first?.let(parts::add)
        thanks(ask)?.let(parts::add)
        when (ask.speaker.role) {
            TownRole.MERCHANT -> board(ask, true)?.let { parts.add(it.second) }
            TownRole.INNKEEPER -> board(ask, false)?.let { parts.add(it.second) }
            TownRole.GUARD -> road(ask)?.let { parts.add(it.second) }
            else -> {}
        }
        var told: Int? = null
        if (parts.size < 2) {
            for (k in known(ask)) {
                val part = part(ask, k.rumour, k.heard.warp.coerceIn(0, 3)) ?: continue
                parts.add(part)
                told = k.rumour.id
                break
            }
        }
        if (parts.isEmpty()) return null
        return Utterance(parts.take(2)) to told
    }

    // ---- The rumours ----

    private fun part(ask: Ask, rumour: Rumour, warp: Int): Utterance.Part? {
        val family = FAMILY[rumour.kind] ?: return null
        return Say.part(ask, family, detail(ask.level, rumour, warp))
    }

    /**
     * The facts of [rumour] as [warp] has them told: the town and the good, and when the teller heard it. A muddled or tall telling (version two or three)
     * has lost the town. `trade.hauled`'s facts are [=player, the good's key, =town]; the other two's [the good's key, =town].
     */
    private fun detail(level: ServerLevel, rumour: Rumour, warp: Int): String? {
        val hauled = rumour.kind == Rumours.TRADE_HAULED
        val good = rumour.args.getOrNull(if (hauled) 1 else 0)?.let { "$it.many" } ?: return null
        val town = rumour.args.getOrNull(if (hauled) 2 else 1)?.takeIf { warp <= 1 }
        val ago = Say.ago(Say.day(level) - Math.floorDiv(rumour.born, 24L))
        val key = "talk.detail." + rumour.kind
        return when {
            hauled -> {
                val who = rumour.args.getOrNull(0) ?: return null
                if (town != null) Speech.of(key, who, good, town, ago) else Speech.of("$key.vague", good, ago)
            }
            town != null -> Speech.of(key, town, good, ago)
            else -> Speech.of("$key.vague", good, ago)
        }
    }

    // ---- The board ----

    /** The capital [ask]'s speaker speaks for. */
    private fun capital(ask: Ask): BlockPos? = ask.resident?.capital ?: Say.market(ask)?.capital

    /**
     * What the Haul Board says of the nearest town that has news worth telling (short of something, or in glut), or else the nearest at all: the town
     * and a good, and how old the news is. A merchant always has something to say of the board, if only that it is blank ([blank]); the inn's keeper
     * only when it has rows. Gives which row it is (the town and the snapshot it is from, for the topic's id) and the part.
     */
    private fun board(ask: Ask, blank: Boolean): Pair<String, Utterance.Part>? {
        val level = ask.level
        val here = capital(ask)
        val rows = if (here != null && TradeConfig.word()) Word.view(level, here, null) else listOf()
        if (rows.isEmpty()) {
            if (!blank) return null
            return Say.part(ask, "trade.board", "talk.detail.trade.board.none")?.let { "none" to it }
        }
        val row = rows.firstOrNull { it.sight.shorts(1).isNotEmpty() || it.sight.gluts(1).isNotEmpty() } ?: rows.first()
        val shorts = row.sight.shorts(1)
        val gluts = row.sight.gluts(1)
        val what = when {
            shorts.isNotEmpty() -> "short" to shorts.first()
            gluts.isNotEmpty() -> "glut" to gluts.first()
            else -> "fair" to null
        }
        val named = row.name.ifEmpty { Word.found(level, row.capital.asLong()) }
        val town = if (named.isNotEmpty()) Witness.named(named) else Say.wayKey(here!!, row.capital)
        val hours = row.age
        val (n, unit) = if (hours >= DAYS_FROM) (hours / 24.0).roundToInt().coerceAtLeast(2) to "days" else hours.roundToInt().coerceAtLeast(1) to (if (hours.roundToInt() <= 1) "hour" else "hours")
        val key = "talk.detail.trade.board." + what.first + if (named.isEmpty()) ".way" else ""
        val detail = if (what.second != null) Speech.of(key, town, what.second!!.key + ".many", n, "say.guildmark.talk.detail.trade.unit.$unit")
        else Speech.of(key, town, n, "say.guildmark.talk.detail.trade.unit.$unit")
        val part = Say.part(ask, "trade.board", detail) ?: return null
        return "${row.capital.asLong()}.${row.tick}" to part
    }

    // ---- The road ----

    /** A gate guard's word to someone laden: the worst road into the town while it is troubled or worse. Gives the stage and the part. */
    private fun road(ask: Ask): Pair<RoadPeril.Stage, Utterance.Part>? {
        if (!Caravans.enabled()) return null
        val capital = capital(ask) ?: return null
        val road: Sight.Road = Sight.road(ask.level, capital.asLong()) ?: return null
        val stage = RoadPeril.Stage.entries[road.stage.coerceIn(0, RoadPeril.Stage.entries.size - 1)]
        if (!stage.troubled) return null
        val detail = Speech.of("talk.detail.road.stage.${stage.id}", "road.guildmark.way.${road.side}")
        return Say.part(ask, "trade.road", detail)?.let { stage to it }
    }

    // ---- The thanks ----

    /** What [ask]'s speaker says to a player who has had a ticket filled in their town these last days. */
    private fun thanks(ask: Ask): Utterance.Part? {
        val player = ask.player ?: return null
        val capital = capital(ask) ?: return null
        val level = ask.level
        val today = Say.day(level)
        val fill = TallyLinks.filled(level, capital, player.uuid, today - THANKS_DAYS) ?: return null
        val detail = Speech.of("talk.detail.trade.thanks", fill.units, fill.good.key + ".many", Say.ago(today - fill.day))
        return Say.part(ask, "trade.thanks", detail)
    }
}
