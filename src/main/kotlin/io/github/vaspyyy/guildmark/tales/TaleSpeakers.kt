package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Who in a tale a townsperson is, and where a player stands with the tale they speak for (living-world/stories/design.md 3.1, 3.5),
 * Part C. A look through the open tales (a handful, kept as a list between changes) and their casts (eight at most): asked when a
 * talk begins or a choice is worked out, never between.
 */
internal object TaleSpeakers {
    /** The open tale [speaker] is in the cast of, in which [slot], with its [template] and the template's [voice] if it has one. */
    class Found(val tale: Tale, val slot: String, val template: Template, val voice: TaleVoice?) {
        /** Whether this cast member is the one who asks: the opener, and "About the note..." (the voice names the slot; else the first of the cast). */
        val asks: Boolean
            get() = slot == (TaleVoiceGuard.run(tale.template, "asker", null) { voice?.asker(tale) } ?: tale.cast.firstOrNull()?.slot)
    }

    /** The tale [speaker] is in the cast of, if an open one has them (tales on, and the person alive and not gone from it). */
    fun find(level: ServerLevel, speaker: Townsperson): Found? {
        if (!Tales.live) return null
        for (t in TaleEngine.openTales(level)) {
            val slot = slotOf(t, speaker) ?: continue
            val template = Templates.find(t.template) ?: continue
            return Found(t, slot, template, TaleVoices.of(template))
        }
        return null
    }

    /** The slot of [t]'s cast that [p] is: a resident by their record, a figure the tale made by the tale's mark on them and their name. */
    fun slotOf(t: Tale, p: Townsperson): String? {
        if (p.resident >= 0) return t.cast.firstOrNull { it.resident == p.resident && it.gone.isEmpty() }?.slot
        if (p.tale != t.id) return null
        val name = p.name.string
        return t.cast.firstOrNull { it.resident < 0 && it.gone.isEmpty() && it.name == name }?.slot
    }

    /** Whether [part] is one the cast ask now (the opener): nobody has told them, or they said "Not now" at least [TaleLimits.ASK_AGAIN_DAYS] market days ago and the cast have asked fewer than three times. */
    fun due(part: Part?, today: Long): Boolean {
        if (part == null) return true
        return when (part.status) {
            Part.Status.UNMET -> true
            Part.Status.DECLINED -> part.asks < 1 + TaleLimits.ASKS_MORE && today - part.lastAsk >= TaleLimits.ASK_AGAIN_DAYS
            else -> false
        }
    }

    /** Whether [part] has taken the tale up (IN) or seen it through (DONE). */
    fun joined(part: Part?): Boolean = part != null && (part.status == Part.Status.IN || part.status == Part.Status.DONE)

    /**
     * The request was told to [player] (the opener was said, or they asked "About the note..."): UNMET becomes ASKED with the first ask
     * counted; a "Not now" asked again becomes ASKED, counted if it was the cast who asked ([byCast]) and not the player. Nothing for a
     * part that is IN already, and nothing at all if the tale has no room for a part.
     */
    fun asked(level: ServerLevel, t: Tale, player: ServerPlayer, byCast: Boolean) {
        val part = t.partFor(player.uuid) ?: return
        val today = TaleEngine.today(level, t.capital)
        when (part.status) {
            Part.Status.UNMET -> {
                part.status = Part.Status.ASKED
                part.asks += 1
                part.lastAsk = today
            }
            Part.Status.DECLINED -> {
                part.status = Part.Status.ASKED
                if (byCast) part.asks += 1
                part.lastAsk = today
            }
            else -> return
        }
        TaleData.get(level).changed()
    }

    /** [player] said "Not now" (3.5): the part is DECLINED and the cast will ask again after two market days, at most twice more. Nothing is held against them. */
    fun declined(level: ServerLevel, t: Tale, player: ServerPlayer) {
        val part = t.partFor(player.uuid) ?: return
        if (joined(part)) return
        part.status = Part.Status.DECLINED
        part.lastAsk = TaleEngine.today(level, t.capital)
        if (part.asks == 0) part.asks = 1
        TaleData.get(level).changed()
    }
}
