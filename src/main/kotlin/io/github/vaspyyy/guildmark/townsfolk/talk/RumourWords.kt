package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * How a rumour is put into words (design-voices.md sections 4 and 5): the news family a teller says it with, and the
 * detail each version of it shows. Version 0 tells it as it happened ("Ransom, away to the north, yesterday."), 1 as
 * it grew in the telling ("Ransom. A beast the size of a barn!"), 2 muddled (the name, the place and the day gone
 * vague: "One of the guild folk, out west, the other day.") and 3 as a tall tale ("Bare-handed, they say."). Names
 * go as `=Name` arguments, so they're shown as written and never read as lang keys.
 *
 * What's read of a rumour: its kind (the part after its last dot names the news: "deed.dragon", "slight.murder",
 * "outlaw.raid_taken", "festival.won"; a guild news key as GuildNews keeps it works too), the player it's [Rumour.about]
 * (their name), the [Rumour.resident] it's about (theirs), the first `=Name` among its args when it names neither
 * (else the outlaw band's name, as its lang key, for what a band did), where it happened ([Rumour.origin]) and the
 * hour it was born ([Rumour.born], in hours of the overworld clock as Markets.hourOf counts them). The market's, the
 * road's, salvage and orders, and the town's works and households (M6) have no news family: their own sources tell
 * them.
 */
object RumourWords {
    /** The news family [rumour] is told with ("news.dragon"), or null if it has none. */
    fun family(rumour: Rumour): String? = news(rumour.kind)?.let { "news.$it" }

    /** The detail of [rumour] as version [warp] of it tells it, in Speech format ("talk.detail.news.told.short|=Ransom|..."), or null. */
    fun detail(rumour: Rumour, warp: Int): String? {
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return null
        return detail(level, rumour, warp, null)
    }

    /**
     * The detail of [rumour] as version [warp] tells it in the town whose heart is [from] (a capital's centre, as
     * Resident.capital keeps it): the place is said from there ("here in town", "away to the north"); with [from] null
     * it's left out.
     */
    fun detail(level: ServerLevel, rumour: Rumour, warp: Int, from: BlockPos?): String? {
        val news = news(rumour.kind) ?: return null
        val player = rumour.about?.let { Say.name(level, it) }
        val resident = rumour.resident.takeIf { it >= 0 }?.let { Residents.get(level)[it] }
        val named = rumour.args.firstOrNull { it.length > 1 && it.startsWith("=") }?.substring(1)
        // Who it was, as a Speech argument: the player, the resident, else a name it gives or the band that did it
        // (the band first for what a band did, whatever place it names)
        val band = band(rumour)
        val name = named?.let { "=$it" }
        val who = (player ?: resident?.name)?.let { "=$it" } ?: if (news in BANDS) band ?: name else name ?: band
        return when (warp.coerceIn(0, 3)) {
            0 -> straight(level, rumour, news, player, resident?.name, resident?.family ?: named, who, from)
            1 -> Speech.of("talk.detail.news.$news.grown", who ?: vague(rumour))
            2 -> Speech.of("talk.detail.news.told", vague(rumour), Say.muddled(from, rumour.origin, rumour.id), Say.WORD + "when.other")
            else -> "talk.detail.news.$news.tall"
        }
    }

    /** The outlaw band [rumour] names among its args, as its name's lang key ("outlaw.guildmark.name.3"), if it names one. */
    private fun band(rumour: Rumour): String? =
        rumour.args.map { it.removePrefix("#") }.firstOrNull { it.startsWith(Outlaws.NAME_KEY + ".") }

    /**
     * What a rumour of [kind] is news of: the end of its news family ("dragon" for news.dragon), or null for what has
     * none (the market's news, the road's but a wagon waylaid, salvage and orders: their own sources tell those).
     */
    fun news(kind: String): String? {
        val k = kind.removePrefix("news.guildmark.")
        KINDS[k]?.let { return it }
        if (k in UNTOLD) return null
        val head = k.substringBefore('.')
        if (head in OWN) return if (head == "road" && ("waylaid" in k || "ambush" in k)) "band" else null
        NEWS[k.substringAfterLast('.')]?.let { return it }
        NEWS[k]?.let { return it }
        // A band's crime with no word of its own is the band at large; something the guild or a player did with no
        // news of its own, big news from away
        if (head == "outlaws" || head == "outlaw") return "band"
        return if (head in DEEDS) "far" else null
    }

    /**
     * The deed family a resident says a deed of [kind] with to the player who did it ("deed.dragon"): [seen] if they
     * saw it themselves, [mine] if it was done for them (they were the one saved). Null if it has none of its own.
     */
    fun deed(kind: String, seen: Boolean, mine: Boolean): String? = when (kind.removePrefix("news.guildmark.").substringAfterLast('.')) {
        "dragon", "dragon_slain" -> "deed.dragon"
        "beast", "slain", "field_boss", "boss", "sea", "maelgrasp", "maelgrasp_slain" -> if (seen) "deed.beast_seen" else "deed.beast"
        "lair" -> "deed.lair"
        "siege_won", "siege" -> "deed.siege"
        "band", "bandits", "ambush", "raid_beaten", "outlaw_slain", "band_broken", "broken" -> "deed.band"
        "escort", "escorted" -> "deed.escort"
        "salvage", "returned", "brought" -> "deed.salvage"
        "order", "filled" -> "deed.order"
        "contract", "quest" -> "deed.contract"
        "won", "festival_won", "festival" -> "deed.festival"
        "rank", "promoted" -> "deed.rank"
        "kindle", "kindled", "wardfire" -> "deed.kindle"
        "town", "monster" -> "deed.monster_town"
        "saved" -> if (mine) "deed.saved" else "deed.monster_town"
        "rescue", "rescued", "freed" -> "deed.rescued"
        "homestead", "raised" -> "deed.homestead"
        "faction", "faction_joined", "faction_done", "companion_sworn" -> "deed.faction"
        "murder", "killing", "killed" -> "deed.murder"
        "assault", "struck", "hurt" -> "deed.hurt"
        "theft", "purse" -> "deed.theft"
        else -> null
    }

    /**
     * Version 0: who ([who], a Speech argument), where and when; for a wrong or a rescue the two people in it; for a
     * newcomer their [family] name.
     */
    private fun straight(
        level: ServerLevel, rumour: Rumour, news: String, player: String?, victim: String?, family: String?, who: String?, from: BlockPos?,
    ): String {
        val ago = Say.ago(Say.day(level) - Math.floorDiv(rumour.born, 24L))
        val where = if (from != null) rumour.origin?.let { Say.where(level, from, it) } else null
        return when {
            news == "murder" || news == "assault" || news == "theft" -> when {
                victim != null && player != null -> Speech.of("talk.detail.news.slight", "=$victim", "=$player")
                player != null -> Speech.of("talk.detail.news.culprit", "=$player", ago)
                else -> told(rumour, victim?.let { "=$it" } ?: who, where, ago)
            }
            news == "rescue" && victim != null && player != null -> Speech.of("talk.detail.news.rescue", "=$victim", "=$player")
            news == "monster" && rumour.kind.endsWith("saved") && victim != null && player != null ->
                Speech.of("talk.detail.news.saved", "=$victim", "=$player")
            news == "newcomer" && family != null -> Speech.of("talk.detail.news.newcomer", "=$family", ago)
            else -> told(rumour, who, where, ago)
        }
    }

    /** "Ransom, away to the north, yesterday." (or without the place, if it's not known); [who] a Speech argument. */
    private fun told(rumour: Rumour, who: String?, where: String?, ago: String): String {
        val w = who ?: vague(rumour)
        return if (where != null) Speech.of("talk.detail.news.told", w, where, ago) else Speech.of("talk.detail.news.told.short", w, ago)
    }

    /** Who it was, gone vague: one of the guild folk (a player), one of the townsfolk, or someone. */
    private fun vague(rumour: Rumour): String = Say.WORD + when {
        rumour.about != null -> "who.guild"
        rumour.resident >= 0 -> "who.townsfolk"
        else -> "who.someone"
    }

    /** Whole kinds whose news isn't what their last word says (H2's names: "deed.band" is a band beaten off). */
    private val KINDS = mapOf("deed.band" to "band_broken", "deed.siege" to "siege_won", "siege.lost" to "siege_lost")

    /** Kinds that aren't told as news: a wagon seen in is the road's to tell. */
    private val UNTOLD = setOf("deed.escort")

    /** The news that's about what a band did, said with the band's name before any place it names. */
    private val BANDS = setOf("band", "band_broken")

    /** The heads of kinds told by their own sources, not as news (M6: the works' and the households', see [theirs]). */
    private val OWN = setOf("market", "salvage", "order", "road", "works", "household", "trade")

    /** The heads of kinds only their own sources tell (M6, and the trader's): not the deeds, the news nor the crier. */
    private val THEIRS = setOf("works", "household", "trade")

    /**
     * Whether a rumour of [kind] is the town's works' or households' (M6), told only by WorksTopics and FortuneTopics:
     * not as a deed to the patron, nor as news, nor cried as the talk of the town (the crier has the works' own news).
     */
    fun theirs(kind: String): Boolean = kind.substringBefore('.') in THEIRS

    /** The heads of kinds that are deeds and guild news: news from away, if nothing more particular. */
    private val DEEDS = setOf("deed", "guild", "news", "outlaw", "faction")

    /** What each kind's last word (or a guild news key) is news of. */
    private val NEWS: Map<String, String> = buildMap {
        fun of(news: String, vararg words: String) = words.forEach { put(it, news) }
        of("dragon", "dragon", "dragon_slain")
        of("king", "king", "king_slain")
        of("sea", "sea", "maelgrasp", "maelgrasp_slain")
        of("beast", "beast", "slain", "field_boss", "boss", "lair")
        of("siege_won", "siege_won")
        of("siege_lost", "siege_lost")
        of("band", "outlaws", "caravan", "farm", "barn", "traveller", "tithe", "patrol", "camp", "band", "waylaid")
        of("raid", "raid", "taken", "raid_taken")
        of("band_broken", "bandits", "ambush", "raid_beaten", "outlaw_slain", "band_broken", "broken")
        of("rescue", "rescue", "rescued", "freed")
        of("monster", "town", "saved", "monster")
        of("rank", "rank", "promoted", "faction_done")
        of("guild", "guild", "guild_founded", "banners", "claimed", "lost_village", "rival_challenge", "rival_repelled")
        of("faction", "faction", "faction_joined", "faction_expelled", "companion_sworn")
        of("homestead", "homestead", "raised")
        of("festival_won", "won", "festival_won")
        of("death", "death", "died")
        of("murder", "murder", "killing", "killed")
        of("assault", "assault", "struck", "hurt")
        of("theft", "theft", "purse")
        of("newcomer", "newcomer")
        of("far", "works", "advance", "story_done", "shard", "character_arrived", "deeds", "more", "kindle", "contract", "far")
    }
}
