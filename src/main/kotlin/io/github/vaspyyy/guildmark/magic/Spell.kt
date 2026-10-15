package io.github.vaspyyy.guildmark.magic

import io.github.vaspyyy.guildmark.combat.Move
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Stat
import net.minecraft.network.chat.Component

/**
 * The two schools of magic. Sorceries are the old arts read from the stars, cast with a staff and grown
 * with Arcana; prayers are the Wardens' prayers to the ward flame, said over a censer and grown with Faith.
 */
enum class School(val id: String, val stat: Stat, val skill: Skill, val bonus: Bonus, val color: Int) {
    SORCERY("sorcery", Stat.ARCANA, Skill.SORCERY, Bonus.SORCERY_DAMAGE, 0xFFB79CF2.toInt()),
    PRAYER("prayer", Stat.FAITH, Skill.PRAYER, Bonus.PRAYER_POWER, 0xFFF2C25A.toInt());

    val title: Component get() = Component.translatable("school.guildmark.$id")

    companion object {
        fun byId(id: String): School? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Every spell: its school, the cast it's made with (its timing and focus cost are the move's), and the
 * stat it needs. Listed by school, then by what they need, which is also the order scrolls teach them in.
 */
enum class Spell(val id: String, val school: School, val move: Move, val need: Int) {
    ARC_BOLT("arc_bolt", School.SORCERY, Move.CAST_ARC_BOLT, 10),
    SPLINTER_HAIL("splinter_hail", School.SORCERY, Move.CAST_SPLINTER_HAIL, 14),
    FLICKER("flicker", School.SORCERY, Move.CAST_FLICKER, 16),
    RIME_LANCE("rime_lance", School.SORCERY, Move.CAST_RIME_LANCE, 18),
    UNDERTOW("undertow", School.SORCERY, Move.CAST_UNDERTOW, 22),
    STARBREAK("starbreak", School.SORCERY, Move.CAST_STARBREAK, 28),

    HEARTHLIGHT("hearthlight", School.PRAYER, Move.CAST_HEARTHLIGHT, 10),
    FLARE("flare", School.PRAYER, Move.CAST_FLARE, 12),
    VIGIL("vigil", School.PRAYER, Move.CAST_VIGIL, 16),
    WAYFLAME("wayflame", School.PRAYER, Move.CAST_WAYFLAME, 18),
    HALLOWED_GROUND("hallowed_ground", School.PRAYER, Move.CAST_HALLOWED_GROUND, 22),
    DAWNBURST("dawnburst", School.PRAYER, Move.CAST_DAWNBURST, 28);

    val title: Component get() = Component.translatable("spell.guildmark.$id")
    val description: Component get() = Component.translatable("spell.guildmark.$id.desc")
    val chargeable: Boolean get() = move.chargeAt >= 0

    companion object {
        /** Known and attuned from the start: one of each school, so a first catalyst casts at once. */
        val STARTERS: List<Spell> = listOf(ARC_BOLT, HEARTHLIGHT)

        private val BY_MOVE = entries.associateBy { it.move }
        private val BY_ID = entries.associateBy { it.id }

        fun byId(id: String): Spell? = BY_ID[id]
        fun of(move: Move): Spell? = BY_MOVE[move]
    }
}
