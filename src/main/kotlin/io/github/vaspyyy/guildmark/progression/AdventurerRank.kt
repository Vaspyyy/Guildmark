package io.github.vaspyyy.guildmark.progression

import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Guild adventurer ranks, F up to S. Contracts carry a rank too: you can take anything up to one rank
 * above your own. Moving up needs the guild level below and a passed trial from a guild hall receptionist.
 *
 * @param minLevel guild level needed before the trial for this rank is offered
 * @param trialTarget the beast the trial champion is made from
 */
enum class AdventurerRank(val letter: String, val minLevel: Int, val color: Int, val trialTarget: String) {
    F("F", 1, 0xFF6E6E6E.toInt(), "zombie"),
    E("E", 3, 0xFF4F7A3A.toInt(), "zombie"),
    D("D", 6, 0xFF2F6E8E.toInt(), "husk"),
    C("C", 10, 0xFF3C4FA0.toInt(), "skeleton"),
    B("B", 15, 0xFF7A3FA0.toInt(), "vindicator"),
    A("A", 20, 0xFFB0452A.toInt(), "wither_skeleton"),
    S("S", 26, 0xFFC8961E.toInt(), "ravager");

    val title: Component get() = Component.translatable("rank.guildmark.rank", letter)
    val next: AdventurerRank? get() = entries.getOrNull(ordinal + 1)
    val trialEntity: Identifier get() = Identifier.withDefaultNamespace(trialTarget)
    /** Champion strength for this rank's trial and contracts (see Expeditions.spawnChampion). */
    val championTier: Int get() = (1 + ordinal / 2).coerceIn(1, 4)

    companion object {
        fun of(index: Int): AdventurerRank = entries[index.coerceIn(0, entries.size - 1)]
    }
}
