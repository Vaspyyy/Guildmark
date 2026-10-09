package io.github.vaspyyy.guildmark.quest

import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource

/**
 * Placeholder generator: random picks from small fixed pools so the board can be tested.
 * The real generator (profession needs, nearby structures, tags) replaces this.
 */
object QuestGenerator {
    private data class Pick(val id: String, val min: Int, val max: Int)

    private val FETCH = listOf(
        Pick("wheat", 16, 32), Pick("carrot", 12, 24), Pick("iron_ingot", 4, 10),
        Pick("leather", 4, 8), Pick("string", 6, 12), Pick("coal", 8, 16),
        Pick("bread", 6, 12), Pick("oak_log", 16, 32), Pick("feather", 6, 12),
    )
    private val HUNT = listOf(
        Pick("zombie", 4, 8), Pick("skeleton", 4, 8), Pick("spider", 3, 6), Pick("creeper", 2, 4),
    )
    private val NAMES = listOf("Maren", "Tobin", "Elsbeth", "Garrick", "Wren", "Osric", "Hilde", "Bram", "Isolde", "Pell")
    private val PROFESSIONS = listOf("Farmer", "Fletcher", "Armorer", "Librarian", "Butcher", "Mason", "Cartographer", "Cleric")

    /** [profession] is the poster's job title; a random one is picked when null. */
    fun generate(random: RandomSource, profession: String? = null): QuestNote {
        val type = QuestType.entries[random.nextInt(QuestType.entries.size)]
        val title = profession ?: PROFESSIONS[random.nextInt(PROFESSIONS.size)]
        val poster = "${NAMES[random.nextInt(NAMES.size)]} the $title"
        val deadline = 2 + random.nextInt(3)
        return when (type) {
            QuestType.FETCH, QuestType.HUNT -> {
                val pool = if (type == QuestType.FETCH) FETCH else HUNT
                val pick = pool[random.nextInt(pool.size)]
                val count = pick.min + random.nextInt(pick.max - pick.min + 1)
                val reward = if (type == QuestType.FETCH) 2 + count / 6 else 3 + count
                QuestNote(type, poster, Identifier.withDefaultNamespace(pick.id), count, reward, deadline)
            }
            QuestType.CLEAR -> {
                val count = 6 + random.nextInt(7)
                QuestNote(type, poster, Identifier.withDefaultNamespace("zombie"), count, 4 + count, deadline)
            }
        }
    }
}
