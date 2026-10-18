package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * The server's side of a talk with a townsperson (living-world/design-voices.md section 2): opening one on a
 * right-click, the greeting, the opener and the choices, the answers, several players at once, and the end. The talk
 * screen's payloads come in through [picked], [ended] and [asked].
 *
 * As it stands this is the interface only: no talk opens, and a right-click answers as before.
 */
object Talk {
    /** Opens a talk between [player] and [p] if they can talk now; false if the old path should answer instead. */
    fun open(level: ServerLevel, player: ServerPlayer, p: Townsperson): Boolean = false

    /** Opens a talk between [player] and [p] that [script] runs, for a director with scripted work (M4's driver, the festival host). */
    fun script(level: ServerLevel, player: ServerPlayer, p: Townsperson, script: Script) {}

    /** Whether [p] is in a talk. */
    fun busy(p: Townsperson): Boolean = false

    /** [player] picked [choice] in their talk with entity [entity], answering the say numbered [serial]. */
    fun picked(player: ServerPlayer, entity: Int, serial: Int, choice: String) {}

    /** [player] closed their talk with entity [entity] (Escape, or the screen closed itself). */
    fun ended(player: ServerPlayer, entity: Int) {}

    /** [player] pressed the market screen's Talk button, with the keeper that's entity [entity]. */
    fun asked(player: ServerPlayer, entity: Int) {}
}

/** A talk a director runs itself: its opener, its choices and its answers, shown, voiced and gestured as any talk. */
interface Script {
    fun opener(ask: Ask): Utterance

    fun choices(ask: Ask): List<Choice>

    /** What they say to [choice], and whether that ends the talk. */
    fun answer(ask: Ask, choice: String): Pair<Utterance, Boolean>
}
