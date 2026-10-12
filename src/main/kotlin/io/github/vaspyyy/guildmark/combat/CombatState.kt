package io.github.vaspyyy.guildmark.combat

import it.unimi.dsi.fastutil.ints.IntOpenHashSet

/** A player's combat state on the server. Never saved: stamina and focus refill on login and respawn. */
class CombatState {
    var stamina = MAX_STAMINA
    var maxStamina = MAX_STAMINA
    var focus = MAX_FOCUS
    var maxFocus = MAX_FOCUS
    /** Game time stamina was last spent; it starts to refill a little after. */
    var lastSpend = -1000L

    /** The move under way, or null. */
    var move: Move? = null
    var moveStart = 0L
    var tempo = 1f
    /** The client's number for the move under way, so late strikes for an older move are ignored. */
    var seq = 0
    /** Ticks the heavy attack was held for, once the client reports the release (-1 while still held). */
    var charge = 0
    /** Entity ids this move has already hit. */
    val struck = IntOpenHashSet()
    /** The entity a riposte or backstab is being done to. */
    var criticalTarget = -1
    /** How many of the critical's blows have landed. */
    var criticalHits = 0

    var invulnerableFrom = 0L
    var invulnerableUntil = 0L
    /** A boss's cutscene has this player's camera until this time: nothing harms them meanwhile. */
    var sceneUntil = 0L

    /** Last time this player hit or was hit by something living. */
    var lastCombat = -1000L
    /** A monster has this player as its target nearby (checked twice a second). */
    var hunted = false

    /** Poise damage taken recently, and when. Big enough hits in a row stagger. */
    var poiseDamage = 0f
    var lastPoiseHit = -1000L

    /** A guard counter can follow a blocked hit until this time. */
    var guardCounterUntil = 0L

    // What the owning client last heard, so vitals are only sent when they change
    var sentStamina = -1f
    var sentFocus = -1f
    var sentFlags = -1
    var sentMaxStamina = -1f
    var ackSeq = 0

    fun inCombat(now: Long): Boolean = hunted || now - lastCombat < COMBAT_MEMORY

    /** Ticks into the current move at its own speed, less the ticks a heavy attack was held at its peak. */
    fun elapsed(now: Long): Float {
        val held = if (charge < 0) 0 else charge
        return (now - moveStart - held) * tempo
    }

    fun busy(now: Long): Boolean {
        val current = move ?: return false
        // Still charging: it can be held up to the move's full charge
        val extra = if (charge < 0) current.maxCharge * tempo else 0f
        return elapsed(now) < current.length + extra
    }

    /** Starts [current]; a charge move whose button is still [holding] waits for the client to report the release. */
    fun start(current: Move, now: Long, seq: Int, tempo: Float, holding: Boolean = false) {
        move = current
        moveStart = now
        this.seq = seq
        this.tempo = tempo
        charge = if (current.chargeAt >= 0 && holding) -1 else 0
        struck.clear()
        criticalTarget = -1
        criticalHits = 0
        if (current.iFrom >= 0) {
            // The client started the roll a moment ago; count its frames from now, a tick early for fairness
            invulnerableFrom = now + (current.iFrom / tempo).toLong() - 1
            invulnerableUntil = now + (current.iTo / tempo).toLong() + 1
        }
    }

    fun clear() {
        move = null
        charge = 0
        struck.clear()
        criticalTarget = -1
    }

    fun spendStamina(amount: Float, now: Long) {
        if (amount <= 0f) return
        stamina = (stamina - amount).coerceAtLeast(0f)
        lastSpend = now
    }

    companion object {
        const val MAX_STAMINA = 100f
        const val MAX_FOCUS = 40f
        /** Ticks after spending stamina before it starts to refill. */
        const val REGEN_DELAY = 12
        const val REGEN_PER_TICK = 2.4f
        const val FOCUS_PER_TICK = 0.05f
        const val SPRINT_COST = 0.3f
        /** How long a fight counts as going on after the last blow, in ticks. */
        const val COMBAT_MEMORY = 160
        /** Ticks after raising a shield in which an incoming blow is parried instead of blocked. */
        const val PARRY_WINDOW = 6
        const val GUARD_COUNTER_WINDOW = 20
    }
}

/** A creature's stance on the server: hits wear it down until it breaks and the creature staggers. */
class StanceState {
    var damage = 0f
    var lastHit = -1000L
    var staggerUntil = 0L
    var kind = 0
    /** The player holding this creature in a riposte or backstab. */
    var heldBy = -1
}
