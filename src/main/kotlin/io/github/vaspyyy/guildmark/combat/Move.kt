package io.github.vaspyyy.guildmark.combat

/** What a move is for, whichever weapon makes it. */
enum class MoveKind {
    /** A step of the light combo. */
    LIGHT,
    /** The heavy attack, charged while the button is held. */
    HEAVY,
    /** The attack out of a sprint. */
    RUNNING,
    /** The attack from the air. */
    JUMP,
    /** The answer straight off a blocked blow. */
    COUNTER,
    /** The weapon skill: costs focus. */
    SKILL,
    /** Rolls and the backstep. */
    DODGE,
    /** Ripostes and backstabs. */
    CRITICAL,
    OTHER,
}

/** What a blow does to the ground where it lands, for everyone near to see and hear. */
enum class Impact {
    NONE,
    /** The ground splits in a line ahead. */
    LINE,
    /** The ground shakes all around. */
    RING,
}

/**
 * Everything one combat move does, counted in ticks from its start at normal speed. Client and server
 * both read these, so a move times the same on both sides. Heavier weapons play moves slower (see [Weapons.tempo]).
 * Each weapon class has its own moves (see [WeaponClass]); the dodges, the flask, the criticals and the reels are shared.
 */
enum class Move(
    /** The animation, under assets/guildmark/animations/player/ (and first_person/). */
    val anim: String,
    val length: Int,
    /** The ticks in which the swing can land (inclusive), or -1 for moves that don't hit. */
    val hitFrom: Int = -1,
    val hitTo: Int = -1,
    val stamina: Float = 0f,
    val focus: Float = 0f,
    /** Multiplies the weapon's own damage. */
    val damage: Float = 1f,
    /** Stance damage dealt to whatever it hits. */
    val stance: Float = 0f,
    /** Multiplies the knockback the hit deals. */
    val knockback: Float = 1f,
    val reach: Double = 3.2,
    /** Half the swing's arc in degrees; 180 hits all around. */
    val arc: Float = 60f,
    /** From this tick the next attack can follow (a buffered press starts it here). */
    val chainFrom: Int = length,
    /** From this tick a roll can cut the move short. */
    val dodgeFrom: Int = length,
    /** How much of the player's walking input still applies during the move. */
    val moveSpeed: Float = 1f,
    /** A forward push at [lungeAt], in blocks per tick, given again each tick for [lungeFor] ticks. */
    val lunge: Float = 0f,
    val lungeAt: Int = 0,
    /** Ticks the player can't be hurt in, or -1. */
    val iFrom: Int = -1,
    val iTo: Int = -1,
    /** Holding the attack button pauses the move at this tick for up to [maxCharge] ticks. */
    val chargeAt: Int = -1,
    val maxCharge: Int = 0,
    /** Hyper armour: the player's poise is multiplied by this while the move winds up and swings. */
    val poise: Float = 1f,
    /** Whether the client may ask for this move; the rest only start on the server's say-so. */
    val requestable: Boolean = true,
    val kind: MoveKind = MoveKind.OTHER,
    /** Further blows after the first, as from, to pairs of ticks: each lands on everything in reach again. */
    val more: IntArray = IntArray(0),
    val lungeFor: Int = 1,
    val impact: Impact = Impact.NONE,
) {
    // ---- The straight sword (vanilla's swords, and modded ones that sweep)
    LIGHT_1("light_1", 13, 4, 5, stamina = 12f, stance = 12f, knockback = 0.35f, chainFrom = 7, dodgeFrom = 7, moveSpeed = 0.3f, lunge = 0.12f, lungeAt = 2, kind = MoveKind.LIGHT),
    LIGHT_2("light_2", 13, 4, 5, stamina = 12f, stance = 12f, knockback = 0.35f, chainFrom = 7, dodgeFrom = 7, moveSpeed = 0.3f, lunge = 0.12f, lungeAt = 2, kind = MoveKind.LIGHT),
    LIGHT_3("light_3", 17, 6, 7, stamina = 15f, damage = 1.3f, stance = 18f, knockback = 0.8f, reach = 3.5, arc = 25f, chainFrom = 11, dodgeFrom = 10, moveSpeed = 0.25f, lunge = 0.3f, lungeAt = 4, kind = MoveKind.LIGHT),
    HEAVY("heavy", 22, 9, 10, stamina = 22f, damage = 1.6f, stance = 28f, reach = 3.4, arc = 45f, chainFrom = 15, dodgeFrom = 13, moveSpeed = 0.15f, lunge = 0.2f, lungeAt = 8, chargeAt = 6, maxCharge = 14, poise = 1.8f, kind = MoveKind.HEAVY),
    RUNNING("running", 15, 4, 6, stamina = 15f, damage = 1.25f, stance = 18f, knockback = 0.9f, reach = 3.4, arc = 50f, chainFrom = 10, dodgeFrom = 9, moveSpeed = 0.5f, lunge = 0.5f, lungeAt = 1, kind = MoveKind.RUNNING),
    JUMP("jump", 16, 5, 8, stamina = 18f, damage = 1.4f, stance = 26f, knockback = 0.7f, reach = 3.0, chainFrom = 12, dodgeFrom = 12, moveSpeed = 0.6f, poise = 1.5f, kind = MoveKind.JUMP),
    GUARD_COUNTER("guard_counter", 18, 7, 8, stamina = 18f, damage = 1.5f, stance = 34f, knockback = 1.1f, reach = 3.4, arc = 50f, chainFrom = 13, dodgeFrom = 12, moveSpeed = 0.2f, lunge = 0.25f, lungeAt = 5, poise = 1.5f, kind = MoveKind.COUNTER),
    SKILL("skill", 24, 8, 12, stamina = 20f, focus = 12f, damage = 1.6f, stance = 30f, knockback = 1.2f, reach = 3.4, arc = 180f, chainFrom = 18, dodgeFrom = 17, moveSpeed = 0.2f, lunge = 0.15f, lungeAt = 7, poise = 1.8f, kind = MoveKind.SKILL),

    // ---- Shared by every weapon
    ROLL("roll", 12, stamina = 18f, chainFrom = 9, dodgeFrom = 10, moveSpeed = 0f, iFrom = 1, iTo = 8, kind = MoveKind.DODGE),
    ROLL_HEAVY("roll_heavy", 15, stamina = 18f, chainFrom = 12, dodgeFrom = 13, moveSpeed = 0f, iFrom = 2, iTo = 7, kind = MoveKind.DODGE),
    BACKSTEP("backstep", 9, stamina = 12f, chainFrom = 6, dodgeFrom = 7, moveSpeed = 0f, iFrom = 1, iTo = 4, kind = MoveKind.DODGE),
    /** A draught from the Warden's Flask: slow on your feet until it's down, and staggered before [DRINK_AT], nothing is drunk. */
    DRINK("drink", 30, chainFrom = 26, dodgeFrom = 22, moveSpeed = 0.35f),
    RIPOSTE("riposte", 32, 12, 22, damage = 1.5f, knockback = 0.1f, moveSpeed = 0f, iFrom = 0, iTo = 32, requestable = false, kind = MoveKind.CRITICAL),
    BACKSTAB("backstab", 28, 12, 12, damage = 2.5f, moveSpeed = 0f, iFrom = 0, iTo = 28, requestable = false, kind = MoveKind.CRITICAL),
    PARRY("parry", 9, moveSpeed = 0.4f, chainFrom = 3, dodgeFrom = 3, requestable = false),
    STAGGER("stagger", 14, moveSpeed = 0f, requestable = false),
    GUARD_BREAK("guard_break", 26, moveSpeed = 0f, requestable = false),

    // ---- The greatsword: slow two-handed cleaves with a long reach, heavy on stance, armoured while they swing
    GS_LIGHT_1("greatsword/light_1", 20, 8, 10, stamina = 20f, stance = 22f, knockback = 0.6f, reach = 3.8, arc = 70f, chainFrom = 13, dodgeFrom = 12, moveSpeed = 0.25f, lunge = 0.15f, lungeAt = 6, poise = 1.3f, kind = MoveKind.LIGHT),
    GS_LIGHT_2("greatsword/light_2", 20, 8, 10, stamina = 20f, stance = 22f, knockback = 0.6f, reach = 3.8, arc = 80f, chainFrom = 13, dodgeFrom = 12, moveSpeed = 0.25f, lunge = 0.15f, lungeAt = 6, poise = 1.3f, kind = MoveKind.LIGHT),
    GS_HEAVY("greatsword/heavy", 28, 12, 14, stamina = 30f, damage = 1.6f, stance = 40f, reach = 3.9, arc = 40f, chainFrom = 20, dodgeFrom = 18, moveSpeed = 0.1f, lunge = 0.25f, lungeAt = 11, chargeAt = 8, maxCharge = 16, poise = 2.2f, kind = MoveKind.HEAVY),
    GS_RUNNING("greatsword/running", 20, 7, 9, stamina = 22f, damage = 1.3f, stance = 28f, reach = 3.8, arc = 60f, chainFrom = 14, dodgeFrom = 13, moveSpeed = 0.5f, lunge = 0.55f, lungeAt = 2, poise = 1.4f, kind = MoveKind.RUNNING),
    GS_JUMP("greatsword/jump", 20, 7, 10, stamina = 24f, damage = 1.5f, stance = 34f, knockback = 0.8f, reach = 3.4, chainFrom = 15, dodgeFrom = 15, moveSpeed = 0.6f, poise = 1.8f, kind = MoveKind.JUMP),
    GS_COUNTER("greatsword/guard_counter", 22, 9, 10, stamina = 22f, damage = 1.6f, stance = 42f, knockback = 1.2f, reach = 3.8, arc = 60f, chainFrom = 16, dodgeFrom = 15, moveSpeed = 0.2f, lunge = 0.25f, lungeAt = 7, poise = 1.8f, kind = MoveKind.COUNTER),
    /** Earthsplitter: a leaping overhead blow that splits the ground in a line ahead. */
    GS_SKILL("greatsword/skill", 30, 14, 16, stamina = 26f, focus = 15f, damage = 1.9f, stance = 50f, knockback = 1.4f, reach = 5.5, arc = 30f, chainFrom = 23, dodgeFrom = 22, moveSpeed = 0.1f, lunge = 0.2f, lungeAt = 12, poise = 2.4f, kind = MoveKind.SKILL, impact = Impact.LINE),

    // ---- The dagger: quick, close and cheap, four cuts to a combo, deadlier criticals
    DAGGER_LIGHT_1("dagger/light_1", 9, 3, 3, stamina = 7f, stance = 6f, knockback = 0.2f, reach = 2.6, arc = 45f, chainFrom = 5, dodgeFrom = 4, moveSpeed = 0.5f, lunge = 0.1f, lungeAt = 1, kind = MoveKind.LIGHT),
    DAGGER_LIGHT_2("dagger/light_2", 9, 3, 3, stamina = 7f, stance = 6f, knockback = 0.2f, reach = 2.6, arc = 45f, chainFrom = 5, dodgeFrom = 4, moveSpeed = 0.5f, lunge = 0.1f, lungeAt = 1, kind = MoveKind.LIGHT),
    DAGGER_LIGHT_3("dagger/light_3", 9, 3, 4, stamina = 7f, stance = 6f, knockback = 0.2f, reach = 2.8, arc = 25f, chainFrom = 5, dodgeFrom = 4, moveSpeed = 0.5f, lunge = 0.15f, lungeAt = 1, kind = MoveKind.LIGHT),
    DAGGER_LIGHT_4("dagger/light_4", 14, 4, 4, stamina = 10f, damage = 0.9f, stance = 8f, knockback = 0.4f, reach = 2.7, arc = 50f, chainFrom = 9, dodgeFrom = 8, moveSpeed = 0.4f, lunge = 0.2f, lungeAt = 3, kind = MoveKind.LIGHT, more = intArrayOf(7, 8)),
    DAGGER_HEAVY("dagger/heavy", 16, 7, 8, stamina = 14f, damage = 1.5f, stance = 14f, knockback = 0.4f, reach = 2.9, arc = 25f, chainFrom = 11, dodgeFrom = 9, moveSpeed = 0.25f, lunge = 0.35f, lungeAt = 6, chargeAt = 4, maxCharge = 10, poise = 1.2f, kind = MoveKind.HEAVY),
    DAGGER_RUNNING("dagger/running", 12, 3, 5, stamina = 10f, damage = 1.25f, stance = 10f, knockback = 0.4f, reach = 2.9, arc = 40f, chainFrom = 8, dodgeFrom = 7, moveSpeed = 0.6f, lunge = 0.55f, lungeAt = 1, kind = MoveKind.RUNNING),
    DAGGER_JUMP("dagger/jump", 12, 4, 6, stamina = 11f, damage = 1.3f, stance = 14f, knockback = 0.5f, reach = 2.6, chainFrom = 9, dodgeFrom = 9, moveSpeed = 0.6f, kind = MoveKind.JUMP),
    DAGGER_COUNTER("dagger/guard_counter", 12, 4, 5, stamina = 10f, damage = 1.4f, stance = 18f, knockback = 0.5f, reach = 2.8, arc = 50f, chainFrom = 8, dodgeFrom = 7, moveSpeed = 0.3f, lunge = 0.2f, lungeAt = 3, kind = MoveKind.COUNTER),
    /** Shadowstep: a dash nothing can touch, ending in a stab. */
    DAGGER_SKILL("dagger/skill", 18, 8, 9, stamina = 14f, focus = 10f, damage = 1.6f, stance = 16f, knockback = 0.4f, reach = 2.8, arc = 40f, chainFrom = 13, dodgeFrom = 12, moveSpeed = 0f, lunge = 0.45f, lungeAt = 2, iFrom = 1, iTo = 7, kind = MoveKind.SKILL, lungeFor = 3),

    // ---- The sabre: fast flowing cuts with wide arcs
    SABRE_LIGHT_1("sabre/light_1", 11, 3, 4, stamina = 10f, stance = 9f, knockback = 0.3f, reach = 3.1, arc = 75f, chainFrom = 6, dodgeFrom = 6, moveSpeed = 0.35f, lunge = 0.12f, lungeAt = 1, kind = MoveKind.LIGHT),
    SABRE_LIGHT_2("sabre/light_2", 11, 3, 4, stamina = 10f, stance = 9f, knockback = 0.3f, reach = 3.1, arc = 75f, chainFrom = 6, dodgeFrom = 6, moveSpeed = 0.35f, lunge = 0.12f, lungeAt = 1, kind = MoveKind.LIGHT),
    SABRE_LIGHT_3("sabre/light_3", 15, 5, 6, stamina = 12f, damage = 1.2f, stance = 14f, knockback = 0.6f, reach = 3.2, arc = 100f, chainFrom = 10, dodgeFrom = 9, moveSpeed = 0.3f, lunge = 0.2f, lungeAt = 3, kind = MoveKind.LIGHT),
    SABRE_HEAVY("sabre/heavy", 20, 8, 9, stamina = 18f, damage = 1.5f, stance = 22f, knockback = 0.9f, reach = 3.3, arc = 70f, chainFrom = 13, dodgeFrom = 12, moveSpeed = 0.2f, lunge = 0.2f, lungeAt = 7, chargeAt = 5, maxCharge = 12, poise = 1.5f, kind = MoveKind.HEAVY),
    SABRE_RUNNING("sabre/running", 14, 4, 6, stamina = 13f, damage = 1.25f, stance = 16f, knockback = 0.8f, reach = 3.3, arc = 70f, chainFrom = 9, dodgeFrom = 8, moveSpeed = 0.55f, lunge = 0.5f, lungeAt = 1, kind = MoveKind.RUNNING),
    SABRE_JUMP("sabre/jump", 14, 4, 7, stamina = 15f, damage = 1.35f, stance = 20f, knockback = 0.7f, reach = 3.0, arc = 70f, chainFrom = 10, dodgeFrom = 10, moveSpeed = 0.6f, poise = 1.3f, kind = MoveKind.JUMP),
    SABRE_COUNTER("sabre/guard_counter", 15, 5, 6, stamina = 14f, damage = 1.45f, stance = 26f, knockback = 0.9f, reach = 3.2, arc = 70f, chainFrom = 10, dodgeFrom = 9, moveSpeed = 0.25f, lunge = 0.25f, lungeAt = 4, poise = 1.3f, kind = MoveKind.COUNTER),
    /** Crescent Dash: a dash forward through two sweeping cuts. */
    SABRE_SKILL("sabre/skill", 22, 6, 7, stamina = 18f, focus = 12f, damage = 1.3f, stance = 18f, knockback = 0.6f, reach = 3.4, arc = 100f, chainFrom = 17, dodgeFrom = 16, moveSpeed = 0.2f, lunge = 0.3f, lungeAt = 4, poise = 1.4f, kind = MoveKind.SKILL, more = intArrayOf(10, 11), lungeFor = 3),

    // ---- The axe: chopping blows, harder on stance than a sword's
    AXE_LIGHT_1("axe/light_1", 17, 6, 7, stamina = 15f, stance = 18f, knockback = 0.5f, reach = 3.1, arc = 55f, chainFrom = 11, dodgeFrom = 10, moveSpeed = 0.3f, lunge = 0.15f, lungeAt = 4, kind = MoveKind.LIGHT),
    AXE_LIGHT_2("axe/light_2", 17, 6, 7, stamina = 15f, stance = 18f, knockback = 0.5f, reach = 3.1, arc = 55f, chainFrom = 11, dodgeFrom = 10, moveSpeed = 0.3f, lunge = 0.15f, lungeAt = 4, kind = MoveKind.LIGHT),
    AXE_LIGHT_3("axe/light_3", 21, 8, 9, stamina = 17f, damage = 1.3f, stance = 26f, knockback = 0.8f, reach = 3.1, arc = 35f, chainFrom = 15, dodgeFrom = 14, moveSpeed = 0.25f, lunge = 0.25f, lungeAt = 6, poise = 1.3f, kind = MoveKind.LIGHT),
    AXE_HEAVY("axe/heavy", 24, 10, 11, stamina = 25f, damage = 1.7f, stance = 36f, reach = 3.2, arc = 40f, chainFrom = 17, dodgeFrom = 15, moveSpeed = 0.15f, lunge = 0.2f, lungeAt = 9, chargeAt = 7, maxCharge = 14, poise = 2.0f, kind = MoveKind.HEAVY),
    AXE_RUNNING("axe/running", 17, 5, 7, stamina = 17f, damage = 1.3f, stance = 24f, reach = 3.2, arc = 55f, chainFrom = 12, dodgeFrom = 11, moveSpeed = 0.5f, lunge = 0.5f, lungeAt = 1, kind = MoveKind.RUNNING),
    AXE_JUMP("axe/jump", 18, 6, 9, stamina = 20f, damage = 1.5f, stance = 32f, knockback = 0.8f, reach = 3.0, chainFrom = 13, dodgeFrom = 13, moveSpeed = 0.6f, poise = 1.6f, kind = MoveKind.JUMP),
    AXE_COUNTER("axe/guard_counter", 20, 8, 9, stamina = 20f, damage = 1.6f, stance = 40f, knockback = 1.2f, reach = 3.2, arc = 50f, chainFrom = 14, dodgeFrom = 13, moveSpeed = 0.2f, lunge = 0.25f, lungeAt = 6, poise = 1.6f, kind = MoveKind.COUNTER),
    /** Skull Splitter: a leaping overhead chop that breaks a guard wide open. */
    AXE_SKILL("axe/skill", 26, 12, 13, stamina = 22f, focus = 12f, damage = 2.0f, stance = 55f, reach = 3.3, arc = 35f, chainFrom = 20, dodgeFrom = 19, moveSpeed = 0.3f, lunge = 0.45f, lungeAt = 8, poise = 2.0f, kind = MoveKind.SKILL),

    // ---- Hammers (maces and war hammers): the slowest blows, the hardest on stance, the ground shaking under the big ones
    HAMMER_LIGHT_1("hammer/light_1", 21, 9, 10, stamina = 20f, stance = 28f, knockback = 0.8f, reach = 3.3, arc = 70f, chainFrom = 14, dodgeFrom = 13, moveSpeed = 0.2f, lunge = 0.15f, lungeAt = 7, poise = 1.5f, kind = MoveKind.LIGHT),
    HAMMER_LIGHT_2("hammer/light_2", 22, 9, 10, stamina = 20f, stance = 28f, knockback = 0.8f, reach = 3.3, arc = 70f, chainFrom = 15, dodgeFrom = 14, moveSpeed = 0.2f, lunge = 0.15f, lungeAt = 7, poise = 1.5f, kind = MoveKind.LIGHT),
    HAMMER_HEAVY("hammer/heavy", 30, 13, 15, stamina = 32f, damage = 1.7f, stance = 50f, knockback = 1.2f, reach = 3.5, arc = 180f, chainFrom = 22, dodgeFrom = 20, moveSpeed = 0.1f, lunge = 0.1f, lungeAt = 12, chargeAt = 8, maxCharge = 18, poise = 2.4f, kind = MoveKind.HEAVY, impact = Impact.RING),
    HAMMER_RUNNING("hammer/running", 20, 7, 9, stamina = 22f, damage = 1.3f, stance = 36f, knockback = 1.3f, reach = 3.4, arc = 60f, chainFrom = 15, dodgeFrom = 14, moveSpeed = 0.45f, lunge = 0.5f, lungeAt = 2, poise = 1.5f, kind = MoveKind.RUNNING),
    HAMMER_JUMP("hammer/jump", 21, 8, 11, stamina = 24f, damage = 1.5f, stance = 42f, reach = 3.2, arc = 180f, chainFrom = 16, dodgeFrom = 16, moveSpeed = 0.6f, poise = 2.0f, kind = MoveKind.JUMP, impact = Impact.RING),
    HAMMER_COUNTER("hammer/guard_counter", 24, 10, 11, stamina = 22f, damage = 1.6f, stance = 55f, knockback = 1.4f, reach = 3.4, arc = 60f, chainFrom = 17, dodgeFrom = 16, moveSpeed = 0.15f, lunge = 0.2f, lungeAt = 8, poise = 2.0f, kind = MoveKind.COUNTER),
    /** Quake: the hammer brought down on the ground, throwing back everything around. */
    HAMMER_SKILL("hammer/skill", 32, 16, 18, stamina = 28f, focus = 16f, damage = 1.8f, stance = 60f, knockback = 1.8f, reach = 4.6, arc = 180f, chainFrom = 25, dodgeFrom = 23, moveSpeed = 0.1f, poise = 2.6f, kind = MoveKind.SKILL, impact = Impact.RING),

    // ---- The spear: thrusts that reach far down a narrow line
    SPEAR_LIGHT_1("spear/light_1", 13, 5, 6, stamina = 12f, stance = 10f, knockback = 0.3f, reach = 4.3, arc = 22f, chainFrom = 8, dodgeFrom = 7, moveSpeed = 0.35f, lunge = 0.15f, lungeAt = 3, kind = MoveKind.LIGHT),
    SPEAR_LIGHT_2("spear/light_2", 13, 5, 6, stamina = 12f, stance = 10f, knockback = 0.3f, reach = 4.3, arc = 22f, chainFrom = 8, dodgeFrom = 7, moveSpeed = 0.35f, lunge = 0.15f, lungeAt = 3, kind = MoveKind.LIGHT),
    SPEAR_LIGHT_3("spear/light_3", 17, 7, 8, stamina = 15f, damage = 1.2f, stance = 16f, knockback = 0.8f, reach = 4.0, arc = 80f, chainFrom = 12, dodgeFrom = 11, moveSpeed = 0.3f, lunge = 0.2f, lungeAt = 5, kind = MoveKind.LIGHT),
    SPEAR_HEAVY("spear/heavy", 22, 10, 11, stamina = 22f, damage = 1.6f, stance = 24f, reach = 4.6, arc = 18f, chainFrom = 15, dodgeFrom = 14, moveSpeed = 0.15f, lunge = 0.35f, lungeAt = 9, chargeAt = 6, maxCharge = 14, poise = 1.6f, kind = MoveKind.HEAVY),
    SPEAR_RUNNING("spear/running", 16, 5, 8, stamina = 15f, damage = 1.3f, stance = 18f, reach = 4.4, arc = 22f, chainFrom = 11, dodgeFrom = 10, moveSpeed = 0.6f, lunge = 0.55f, lungeAt = 2, kind = MoveKind.RUNNING),
    SPEAR_JUMP("spear/jump", 16, 6, 8, stamina = 18f, damage = 1.45f, stance = 26f, knockback = 0.6f, reach = 3.8, arc = 30f, chainFrom = 12, dodgeFrom = 12, moveSpeed = 0.6f, poise = 1.5f, kind = MoveKind.JUMP),
    SPEAR_COUNTER("spear/guard_counter", 18, 7, 8, stamina = 18f, damage = 1.5f, stance = 32f, knockback = 1.1f, reach = 4.3, arc = 30f, chainFrom = 13, dodgeFrom = 12, moveSpeed = 0.2f, lunge = 0.3f, lungeAt = 5, poise = 1.5f, kind = MoveKind.COUNTER),
    /** Piercing Charge: a run with the spear levelled, through everything in the way. */
    SPEAR_SKILL("spear/skill", 26, 6, 14, stamina = 22f, focus = 14f, damage = 1.5f, stance = 26f, knockback = 1.6f, reach = 3.6, arc = 25f, chainFrom = 20, dodgeFrom = 18, moveSpeed = 0f, lunge = 0.2f, lungeAt = 4, poise = 2.0f, kind = MoveKind.SKILL, lungeFor = 10);

    val hits: Boolean get() = hitFrom >= 0
    val isRoll: Boolean get() = kind == MoveKind.DODGE
    val isAttack: Boolean get() = hits && requestable
    val isCritical: Boolean get() = kind == MoveKind.CRITICAL
    /** Light-type presses that can turn into a riposte or backstab when the server finds an opening. */
    val canBecomeCritical: Boolean get() = kind == MoveKind.LIGHT || kind == MoveKind.RUNNING || kind == MoveKind.COUNTER

    /** How many separate blows the move strikes with. */
    val windows: Int get() = if (hits) 1 + more.size / 2 else 0
    fun windowFrom(i: Int): Int = if (i == 0) hitFrom else more[(i - 1) * 2]
    fun windowTo(i: Int): Int = if (i == 0) hitTo else more[(i - 1) * 2 + 1]
    /** The tick the last blow can land. */
    val lastHit: Int get() = if (hits) windowTo(windows - 1) else -1

    /** The tick blow [i]'s swing is heard: a moment before it lands, and not while a charge is held. */
    fun swingAt(i: Int): Int = maxOf(windowFrom(i) - 3, if (i == 0) chargeAt + 1 else windowTo(i - 1) + 1, 0)

    companion object {
        private val BY_ID = entries.toTypedArray()
        fun byId(id: Int): Move? = BY_ID.getOrNull(id)

        /** The tick a flask draught takes hold. Knocked out of the move before it, the draught stays in the flask. */
        const val DRINK_AT = 15

        /** The riposte's two blows: the stab and the wrench free. */
        val RIPOSTE_HITS = intArrayOf(12, 22)
        val RIPOSTE_DAMAGE = floatArrayOf(1.2f, 1.8f)

        /** Roll and backstep speed per tick, in blocks; they glide to a stop after the listed ticks. */
        fun dodgeSpeeds(move: Move): FloatArray = when (move) {
            ROLL -> floatArrayOf(0.55f, 0.62f, 0.62f, 0.58f, 0.52f, 0.46f, 0.38f, 0.3f, 0.2f)
            ROLL_HEAVY -> floatArrayOf(0.4f, 0.44f, 0.44f, 0.4f, 0.36f, 0.3f, 0.24f, 0.18f, 0.12f)
            BACKSTEP -> floatArrayOf(0.5f, 0.48f, 0.4f, 0.3f, 0.18f)
            else -> FloatArray(0)
        }
    }
}
