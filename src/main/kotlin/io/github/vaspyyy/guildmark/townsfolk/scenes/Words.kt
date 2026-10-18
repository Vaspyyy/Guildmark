package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Street
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * What the townsfolk say in the scenes (living-world/design-influence.md section 8's voice families: the streets',
 * the children's, the households'), said as the street talk says its lines ([Street]): only with the street talk on,
 * a take nobody near has heard lately (none at all if every take has been), never into a crowd already loud with
 * talk, and never over anyone near who's saying something (the lines are voiced: two at once is noise). A family that
 * isn't recorded yet (not in [TalkLines.FAMILIES]) says nothing, rather than failing.
 */
internal object Words {
    /** How far a scene's words carry. */
    private const val HEARING = 20.0

    /** How near another's words must be for a scene's to wait for them. */
    private const val OVER = 8.0

    /** Whether scenes say anything: the street talk on (townsfolkTalk and townsfolkBarks). */
    fun on(): Boolean = Street.on()

    /** Whether anyone else within [OVER] blocks of [p] is saying something now, so [p] would talk over them. */
    fun over(p: Townsperson): Boolean =
        p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(OVER, 3.0, OVER)) { it !== p && it.speech.isNotEmpty() }.isNotEmpty()

    /** The nearest player in hearing of [p], if any. */
    fun hearer(p: Townsperson): ServerPlayer? = p.level().getNearestPlayer(p, HEARING) as? ServerPlayer

    /** A take of [family] for [p] to say in [player]'s hearing: one neither has heard lately; null if none (or none recorded). */
    fun of(p: Townsperson, player: ServerPlayer?, family: String): Street.Line? {
        val f = TalkLines.FAMILIES[family]?.takeIf { it.count > 0 } ?: return null
        val avoid = Topics.avoid(p, player)
        val keys = (0 until f.count).map { "talk.${family}_$it" }.filter { it !in avoid }
        if (keys.isEmpty()) return null
        val key = keys[p.random.nextInt(keys.size)]
        return Street.Line(key, key, Talk.ticks(p, key))
    }

    /**
     * [p] says a take of [family] now, overheard by whoever's in hearing ([why] goes in the talk's log); with [force],
     * even over others talking (a call from a watchtower). True if it was said.
     */
    fun say(p: Townsperson, family: String, why: String, force: Boolean = false): Boolean {
        if (!on()) return false
        val player = hearer(p) ?: return false
        return say(p, player, of(p, player, family), why, force)
    }

    /** [p] says [line] now in [player]'s hearing, if they're free to; true if it was said. */
    fun say(p: Townsperson, player: ServerPlayer?, line: Street.Line?, why: String, force: Boolean = false): Boolean {
        if (line == null || !on() || !p.isAlive) return false
        if (!force && (p.speech.isNotEmpty() || Street.hushed(p) || over(p))) return false
        if (!p.say(line.speech, force = true, ticks = line.ticks + 20L)) return false
        Street.heard(p, player, line, why, toThem = false)
        return true
    }

    /**
     * [p] says something of [kind] (a works going up, the times) from the talk's topics, its first part as one line
     * (the topic then out for a while, as the street talk has it); else a take of [fallback]. True if anything was said.
     */
    fun about(level: ServerLevel, p: Townsperson, kind: Kind, fallback: String?, why: String): Boolean {
        if (!on()) return false
        val player = hearer(p) ?: return false
        if (p.speech.isNotEmpty() || Street.hushed(p) || over(p)) return false
        val ask = Talk.ask(level, p, player, Occasion.BARK)
        val topic = Street.pick(ask, Topics.LEAST) { it.kind == kind && it.say.parts.isNotEmpty() }
        val line = topic?.let { Street.said(ask, it).firstOrNull() }
        if (topic != null && line != null) {
            if (!say(p, player, line, why)) return false
            Topics.said(ask, topic)
            return true
        }
        return fallback != null && say(p, player, of(p, player, fallback), why)
    }
}
