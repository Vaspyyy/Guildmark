package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity.Slip
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.talk.Fade
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumours
import io.github.vaspyyy.guildmark.townsfolk.talk.Scope
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalkData
import io.github.vaspyyy.guildmark.townsfolk.talk.Venue
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import kotlin.math.abs

/**
 * Stage 5 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: what is told. [run] starts the ending's rumour
 * (6.2, through [rumour]) and adds the guild's news for a size-3 tale that ended A or B; the crier's cry and the board's slip are
 * not queued anywhere but worked out when the crier asks (`Tales.cries`, [cries]), from the record, so they are the same every time
 * they are asked and cannot be lost or doubled by a restart. The chronicle line is written by stage 1 (the engine), not here. Runs
 * once for a tale; the engine guards it.
 *
 * The rumour is kind `tale.<template>.<word>`, heat 0.9, [Fade.BIG], [Scope.TOWN], known first by the cast (who saw it), their
 * households, a few of their trade and their street. Its tone is +1 for A and B and -1 for C and W; it is about the lead only
 * when it is good news (a rumour about a player with a tone below nothing counts against them, and the town never punishes a
 * no, 6.6). Its `resident` is the first of the cast, and its args are the words of the sentence as a rumour carries names:
 * `[=name1, =name2, =lead]`, a blank one left "" (so the line of the ending, `fortune.guildmark.reason.tale.<template>.<word>`, reads
 * them as `%1$s`, `%2$s`, `%3$s`).
 */
object TaleTold {
    /** How hot a tale's rumour starts: as hot as a rescue (`Witness.rescued`). */
    private const val HEAT = 0.9f

    /** The most residents who know it first, how many of the first one's trade, and how many of the cast have their street told. */
    private const val KNOWERS = 40
    private const val WORKMATES = 3
    private const val STREETS = 2

    /**
     * The generic line the crier cries for an ending of each of the twelve (6.2, and the endings tables of section 8): `good` a
     * thing mended, `ill` a thing lost, `home` someone came home, `lost` someone was lost. Every ending the tables name no cry for
     * is not cried (the rumour and the chronicle carry it).
     */
    private val FAMILIES = mapOf(
        "note.A" to "good", "note.W" to "ill",
        "chair.A" to "good",
        "wagon.A" to "home", "wagon.B" to "home", "wagon.W" to "lost",
        "child.A" to "home", "child.B" to "home",
        "fence.A" to "good",
        "lute.A" to "good",
        "moot.A" to "good", "moot.W" to "ill",
        "cart.A" to "home", "cart.B" to "home",
        "informer.A" to "good", "informer.B" to "good", "informer.W" to "ill",
        "locket.A" to "good",
        "brother.A" to "home", "brother.B" to "home", "brother.W" to "lost",
    )

    /** The twelve tales (8.0): a template that is one of them is cried by [FAMILIES] alone, any other (the probe) by the general rule of [family]. */
    private val TWELVE = Templates.IDS.toSet()

    /** +1 for the endings that mend (A, B), -1 for the ones that do not (C, W), 0 for none. */
    fun tone(ending: String): Int = when (ending) {
        Ending.A, Ending.B -> 1
        Ending.C, Ending.W -> -1
        else -> 0
    }

    fun run(c: Ctx, t: Tale) {
        val tone = tone(t.ending)
        if (tone == 0) return
        rumour(c, t, tone)
        news(c, t)
    }

    /**
     * What `Ctx.rumour` does: starts the rumour of [t]'s ending with [tone], known first by [t]'s cast, their households, a few of
     * their trade and their street (at most [KNOWERS]). Nothing if nobody of the cast has a record to know it; the same rumour
     * again within three days heats the one going round.
     */
    fun rumour(c: Ctx, t: Tale, tone: Int) {
        if (!Tales.READY || tone == 0) return
        val level = c.level
        val cast = t.cast.mapNotNull { c.resident(t, it.slot) }.filter { it.alive }.distinctBy { it.id }
        val knowers = knowers(level, cast)
        if (knowers.isEmpty()) return
        val names = TaleWords.names(t)
        val args = listOf(named(names.getOrElse(0) { "" }), named(names.getOrElse(1) { "" }), named(TaleWords.lead(level, t)))
        TownTalk.begin(
            level, BlockPos.of(t.capital), "tale." + TaleWords.id(t), if (tone > 0) t.lead?.player else null, cast.first().id, args,
            HEAT, Fade.BIG, Scope.TOWN, tone, null, Rumours.TOWN, knowers,
        )
    }

    /** [name] as a rumour carries a name (`=Name`), or "" for none. */
    private fun named(name: String): String = if (name.isBlank()) "" else Witness.named(name)

    /** Those who know the ending first: the [cast] (they saw it), their households, [WORKMATES] of the first one's trade, and the street of the first [STREETS]. */
    private fun knowers(level: ServerLevel, cast: List<Resident>): List<Pair<Int, Venue>> {
        if (cast.isEmpty()) return listOf()
        val out = ArrayList<Pair<Int, Venue>>()
        for (r in cast) out.add(r.id to Venue.SEEN)
        for (r in cast) for (h in TaleMemory.household(level, r)) out.add(h.id to Venue.HOUSEHOLD)
        val first = cast.first()
        Residents.get(level).of(first.capital).filter { it.alive && it.id != first.id && it.role == first.role }.take(WORKMATES)
            .forEach { out.add(it.id to Venue.WORK) }
        for (r in cast.take(STREETS)) for (n in Witness.neighbours(level, r)) out.add(n.id to Venue.STREET)
        return out.distinctBy { it.first }.take(KNOWERS)
    }

    /** The guild's news of a great turn (6.2): a size-3 tale ended A or B, "<the lead> brought the Fourth Wagon home to <town>". */
    private fun news(c: Ctx, t: Tale) {
        if (!Tales.READY || TaleWords.size(t) < 3 || t.ending != Ending.A && t.ending != Ending.B) return
        val level = c.level
        val hero = TaleWords.lead(level, t).ifEmpty {
            TaleShares.shares(3, t.ending, t.parts).firstOrNull()?.part?.player?.let { TaleWords.leadName(level, it) }.orEmpty()
        }
        GuildNews.add(level, "news.guildmark.tale.${t.template}", hero.ifEmpty { "?" }, TaleWords.town(level, t.capital).ifEmpty { "?" })
    }

    // ---- The crier ----

    /**
     * The line the crier cries for [template]'s ending [ending] ([hurts]: the world's way hurts others): one of [FAMILIES] for the
     * twelve; for any other template a good ending is "mended" and a hurting W "ill". Null: it is not cried.
     */
    fun family(template: String, ending: String, hurts: Boolean): String? =
        if (template in TWELVE) FAMILIES["$template.$ending"]
        else when (ending) {
            Ending.A, Ending.B -> "good"
            Ending.W -> if (hurts) "ill" else null
            else -> null
        }

    private fun family(t: Tale): String? = family(t.template, t.ending, TaleWords.hurts(t))

    /**
     * What [entry]'s crier cries of tales at [c]'s noon (`Crier.news` keeps room for it; [at] is the game time, for the slip): the
     * ending of the tale that ended last at this capital, at the first noon after it ended, one cry: the voiced generic line of its
     * family (`say.guildmark.crier_tale_<family>`), the sentence of the ending in grey after it, and a TALE slip on the board (its
     * headline the tale's name, its text the sentence). Nothing while tales are off. It reads the record and nothing else, so asked
     * twice it says the same, and a noon the crier missed is simply missed (the rumour and the chronicle carry the tale on).
     *
     * The first noon after an ending is the first moment at or after the hour its rumour was born (the end of its day if the
     * town no longer holds the rumour) when the clock reads twelve; a noon's window is the hours `(noon - 24, noon]`, as the
     * fortunes' cries have it.
     */
    fun cries(level: ServerLevel, entry: Markets.Entry, c: Conditions, at: Long): List<Crier.Cry> {
        if (!Tales.enabled()) return listOf()
        val capital = entry.market.capital
        val noon = c.day * 24 + 6
        var best: Tale? = null
        var bestHour = Long.MIN_VALUE
        for (t in TaleData.get(level).all()) {
            if (t.phase != Tale.Phase.ENDED || t.capital != capital || t.ended == TaleData.NEVER) continue
            if (t.ended < c.day - 3 || t.ended > c.day + 1 || family(t) == null) continue
            val hour = endHour(level, t)
            if (firstNoon(hour) != noon) continue
            if (hour > bestHour || hour == bestHour && t.id > (best?.id ?: -1)) {
                best = t
                bestHour = hour
            }
        }
        val t = best ?: return listOf()
        return listOf(cry(level, t, family(t) ?: return listOf(), at))
    }

    /** The first hour at or after [hour] that is a noon: twelve o'clock is hour 6 of a day, as `Fortunes.cries` reckons it. */
    internal fun firstNoon(hour: Long): Long {
        val noon = Math.floorDiv(hour - 6, 24L) * 24 + 6
        return if (noon >= hour) noon else noon + 24
    }

    /**
     * The market hour [t] ended, for the noon that cries it: the hour its rumour was born if the capital still holds one for it (the
     * one nearest the middle of the tale's last day), else the end of that day.
     */
    private fun endHour(level: ServerLevel, t: Tale): Long {
        val mid = t.ended * 24 + 8
        val kind = "tale." + TaleWords.id(t)
        val born = TownTalkData.get(level)[BlockPos.of(t.capital)]?.rumours?.values
            ?.map { it.rumour }?.filter { it.kind == kind && abs(it.born - mid) <= 40 }?.minByOrNull { abs(it.born - mid) }?.born
        return born ?: ((t.ended + 1) * 24 - 4)
    }

    /** The cry of [t]'s ending in [family]: the voiced line, the sentence in grey, and the TALE slip. */
    private fun cry(level: ServerLevel, t: Tale, family: String, at: Long): Crier.Cry {
        val line = "crier_tale_$family"
        val sentence = TaleWords.sentence(level, t)
        val text = Component.translatable("say.guildmark.$line").append(" ").append(sentence.copy().withStyle(ChatFormatting.GRAY))
        val headline = Component.translatableWithFallback("board.guildmark.slip.tale.${t.template}", "A tale")
        return Crier.Cry(line, text, Slip(Slip.Kind.TALE.id, headline, sentence, at), if (t.ending == Ending.W) -1 else 1)
    }
}
