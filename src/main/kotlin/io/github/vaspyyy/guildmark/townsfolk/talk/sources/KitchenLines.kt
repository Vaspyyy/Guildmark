package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance

/**
 * A kitchen line (Hearthcraft, living-world/cook/design.md 8.5): the take of a kitchen family with its detail, or the
 * wording's own text while none is recorded. The keepers' scenes, the back door and the Harvest Table say theirs through
 * [say]. Part J's; until it lands every one of these says nothing.
 */
object KitchenLines {
    /** The part of a line for [family] with [detail], as the speaker of [ask] would say it (stub: none). */
    fun part(ask: Ask, family: String, detail: String?): Utterance.Part? = null

    /** The part that leads a line for [family], with [detail] when it has one (stub: none). */
    fun lead(ask: Ask, family: String, detail: String? = null): Utterance.Part? = null

    /** The whole line for [family] with [detail] (stub: none). */
    fun line(ask: Ask, family: String, detail: String?): Utterance? = null

    /** Has [p] say [family] now, because of [why] ("harvest", a scene's name...), over their head or aloud; [force] says it even if they said something just now. Whether they did (stub: no). */
    fun say(p: Townsperson, family: String, why: String, force: Boolean = false): Boolean = false
}
