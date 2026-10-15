package io.github.vaspyyy.guildmark.progression

import net.minecraft.network.chat.Component
import kotlin.math.min

/**
 * The seven stats, raised one point a level at a Wardfire with guild XP. Every stat starts at [BASE].
 * What a point is worth falls off past the soft caps, so spreading points pays as well as piling them up.
 */
enum class Stat(val id: String, val color: Int) {
    /** Greatest health. */
    VIGOR("vigor", 0xFFD0573F.toInt()),
    /** The focus that weapon skills (and later sorceries and prayers) spend. */
    FOCUS("focus", 0xFF86A9E6.toInt()),
    /** Stamina, and how much armour you can wear before rolls turn heavy. */
    ENDURANCE("endurance", 0xFF8FCB7A.toInt()),
    /** Heavy weapons hit harder; poise and a firmer stance. */
    MIGHT("might", 0xFFD08A4E.toInt()),
    /** Swords and bows hit harder; quicker moves and softer falls. */
    FINESSE("finesse", 0xFFE0CC86.toInt()),
    /** Focus comes back faster and weapon skills hit harder; magic hurts less. */
    ARCANA("arcana", 0xFFA88BE3.toInt()),
    /** The flask heals more; the dead and fire fear you more. */
    FAITH("faith", 0xFFF2D58C.toInt());

    val title: Component get() = Component.translatable("stat.guildmark.$id")
    val description: Component get() = Component.translatable("stat.guildmark.$id.desc")

    /** What each effective point of this stat adds (see [effective]). */
    val perPoint: List<Pair<Bonus, Double>>
        get() = when (this) {
            VIGOR -> listOf(Bonus.MAX_HEALTH to 1.0)
            FOCUS -> listOf(Bonus.FOCUS to 2.5)
            ENDURANCE -> listOf(Bonus.STAMINA to 2.0, Bonus.STAMINA_REGEN to 0.01, Bonus.ROLL_LOAD to 0.4)
            MIGHT -> listOf(Bonus.POISE to 1.0, Bonus.KNOCKBACK_RESIST to 0.005)
            FINESSE -> listOf(Bonus.TEMPO to 0.003, Bonus.SAFE_FALL to 0.1)
            ARCANA -> listOf(Bonus.FOCUS_REGEN to 0.02, Bonus.SKILL_DAMAGE to 0.01, Bonus.MAGIC_TAKEN to -0.006)
            FAITH -> listOf(Bonus.FLASK_HEAL to 0.01, Bonus.UNDEAD_DAMAGE to 0.008, Bonus.FIRE_TAKEN to -0.005)
        }

    /** What this stat at [value] adds. */
    fun effectsAt(value: Int): List<Effect> {
        val points = effective(value)
        return perPoint.map { (bonus, each) -> Effect(bonus, each * points) }
    }

    companion object {
        const val BASE = 10
        const val MAX = 60
        /** Past this each point is worth half as much... */
        const val SOFT = 25
        /** ...and past this, a quarter. */
        const val HARD = 40

        /** What [value] is worth above the base: in full up to [SOFT], half up to [HARD], a quarter beyond. */
        fun effective(value: Int): Double {
            val v = value.coerceIn(BASE, MAX)
            return (min(v, SOFT) - BASE) + 0.5 * (min(v, HARD) - SOFT).coerceAtLeast(0) + 0.25 * (v - HARD).coerceAtLeast(0)
        }

        /** What a stat at [MAX] is worth, so a stat's share of its full effect is effective(value) / FULL. */
        val FULL: Double = effective(MAX)

        fun byId(id: String): Stat? = entries.firstOrNull { it.id == id }
    }
}
