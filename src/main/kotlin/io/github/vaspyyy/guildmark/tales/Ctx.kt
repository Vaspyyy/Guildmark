package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.ComingsData
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * What a template's functions are given to work with (living-world/stories/design.md 1.6): the [level], the capital's places
 * ([here], whose `key` is the key tales go by), its market [entry] and market [day], and the helpers a template calls to
 * change the world. The helpers are the template's whole way of acting on anything outside its own tale: each delegates to
 * the part that owns the behaviour, so a template never names another system and the twelve stay side by side (10.0).
 *
 * Each helper takes the [Tale] it is for. A1 declares them and none does anything yet: the telling (B), pay and memory (E)
 * and the memorials (F) fill their bodies in place (a signature changes only together with the calls that use it), and the
 * engine (A2) fills its own. A helper called with tales off, or on a tale that has ended, writes nothing.
 */
class Ctx(
    val level: ServerLevel,
    val here: Scenes.Here,
    val entry: Markets.Entry,
    val day: Long,
) {
    /** The key tales go by: the capital's heart as a long (`Scenes.Here.key`, the key `Fortunes` goes by). */
    val capital: Long get() = here.key

    /** The saved record. Opened only when asked: a build with tales off never calls it. */
    val data: TaleData get() = TaleData.get(level)

    /** The resident in [slot] of [t]'s cast, or null (no such slot, a made figure, or a resident the world no longer has). */
    fun resident(t: Tale, slot: String): Resident? = t.resident(slot).takeIf { it >= 0 }?.let { Residents.get(level)[it] }

    // ---- The telling (Part B, 4.2 to 4.4): the people of the tale act in the street ----

    /** [slot]'s body says a take of the tale family [family] (`tale.<template>.<beat>`) with the grey [detail], through `Words.sayWith`; nothing if no body is near or the family is not recorded (4.5). */
    fun say(t: Tale, slot: String, family: String, detail: String? = null) {}

    /** [slot]'s body plays [activity] (a clip) as a one-person part of the stage (4.2). */
    fun act(t: Tale, slot: String, activity: Activity) {}

    /** Holds [slot]'s resident for the tale (4.4 rule 2): their day body is not made, and their tale body stands at [at] playing [clip]. False if they cannot be held (three at once, or tales' scenes off). */
    fun hold(t: Tale, slot: String, at: BlockPos? = null, clip: Activity? = null): Boolean = Tales.hold(level, t, slot, at, clip)

    /** Lets [slot]'s resident go back to their day (4.4 rule 4). */
    fun release(t: Tale, slot: String) = Tales.release(level, t, slot)

    // ---- Pay and memory (Part E, 6.1, 6.2, 6.5, 6.6, 7): what the town does with the tale ----

    /** Leaves the times a `tale` reason of [points] for [days] market days (6.1): `Fortunes.note(level, capital, "tale", points, days, ["<template>.<word>", names...], about)`. */
    fun note(t: Tale, points: Int, days: Int, about: UUID? = null) {}

    /** Starts the tale's rumour (6.2): kind `tale.<template>.<word>`, the lead, the cast, their household, neighbours and workmates as the ones who know, [tone] +1 (A, B) or -1 (C, W). */
    fun rumour(t: Tale, tone: Int) {}

    /** The town remembers [player] for the tale (6.6): [favour] to the resident in [slot] and their household. */
    fun favour(t: Tale, slot: String, player: UUID, favour: Favour) {}

    /** Pays [player] for the tale (7): the points of their part through `payOut`, less what the tale's own notes have paid. */
    fun pay(t: Tale, player: ServerPlayer) = Tales.pay(level, player, t)

    /** Changes the ways of the resident in [slot] to [to] (6.5): the opposite is cleared, the flip is logged. False if a limit stops it (two a tale, one a resident in forty days). */
    fun flip(t: Tale, slot: String, to: Trait): Boolean = resident(t, slot)?.let { Tales.flip(this, t, it, to) } ?: false

    // ---- The memorials (Part F, 6.4) ----

    /**
     * Asks for a memorial of [kind] ("stone" or "cairn") for [names], as near [at] as the safe-site check allows (null: where the
     * kind belongs). It records it in [TaleData.memorials], not yet placed (six a capital; a seventh is not recorded and a `Why`
     * says so), and answers it; `Memorials.place` (stage 8 of the finish) sets down every memorial of the tale that is not placed.
     */
    fun memorial(t: Tale, kind: String, names: List<String>, at: BlockPos? = null): TaleData.Memorial? = null

    // ---- The households (Part A, 6.5) ----

    /** Sends the household of lodging [home] away for the tale (`Comings.leave` for one named household); false if the town is at its floor and the household stays, which a `Why` says. */
    fun leave(t: Tale, home: Int, why: String? = null): Boolean = Tales.leave(this, t, home, why)

    /** Brings [away] home through [gate] (`Comings.comeBack` with force); false if it could not. */
    fun bringHome(t: Tale, away: ComingsData.Moved, gate: BlockPos? = null): Boolean = Tales.bringHome(this, t, away, gate)

    /** Protects the household of lodging [home] from leaving for [days] market days (6.5). */
    fun protect(t: Tale, home: Int, days: Int) = Tales.protect(this, t, home, days)

    // ---- The tale itself (Part A) ----

    /** Adds a `Why` to [t] (1.1): [kind] and its [args], oldest first, at most eight (the second is dropped at the cap). See [Why] for the key a sentence is. */
    fun why(t: Tale, kind: String, vararg args: String) {}

    /** Moves [t] on to step [step]: its days (`stepDay`, `by`) are set from the template's table and the template's `enter` is called. */
    fun go(t: Tale, step: Int) {}

    /** Moves [t] on to the next step; the same as [go] with the step after this one. */
    fun next(t: Tale) = go(t, t.step + 1)

    /** Ends [t] with [ending] ("A", "B", "C" or "W") through the whole pipeline of 6.7 (`Tales.finish`), whoever or whatever caused it. */
    fun end(t: Tale, ending: String) = Tales.finish(this, t, ending)
}
