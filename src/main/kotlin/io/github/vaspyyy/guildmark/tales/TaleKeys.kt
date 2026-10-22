package io.github.vaspyyy.guildmark.tales

import net.minecraft.network.chat.Component

/**
 * The language keys a tale is told in, built in one place (living-world/stories/design.md 5.1, 8.0 and 13.6) so that the
 * tracker's row, the banner, the "Why?", the chronicle and `tale check` all name a line the same way. A key put together
 * from pieces is invisible to `art/lang/check_lang.py`, so what the engine itself says is also listed in full below
 * ([ENGINE_WHY], [ENGINE_LINES]) and `tale check` looks each of those up in the language file.
 */
object TaleKeys {
    /** A tale's short name on the tracker: sixteen letters or fewer. */
    fun title(id: String): String = "gui.guildmark.tale.$id.title"

    /** What is next in [step] of template [id], on the tracker: sixteen letters or fewer, given names only. */
    fun status(id: String, step: Int): String = "gui.guildmark.tale.$id.status.$step"

    /** The banner's headline when [step] of template [id] begins. */
    fun banner(id: String, step: Int): String = "gui.guildmark.tale.$id.step.$step"

    /** The banner's headline when template [id] ends with the ending whose word is [word]. */
    fun ended(id: String, word: String): String = "gui.guildmark.tale.$id.end.$word"

    /** The one sentence every channel says of an ending (the times' reason, the slip, the crier's detail, the chronicle). */
    fun sentence(id: String, word: String): String = "fortune.guildmark.reason.tale.$id.$word"

    /** The sentence a [Why] of this [kind] is: the engine's kinds are bare words, a template's start with its id (`note.lean`). */
    fun why(kind: String): String = "fortune.guildmark.reason.tale.why.$kind"

    /** [w] as a sentence, its words filled in. */
    fun text(w: Why): Component = Component.translatable(why(w.kind), *w.args.toTypedArray())

    /** The `Why` kind that closes a tale at [ending] (a letter of [Ending.LETTERS]): `finished_a` to `finished_w`. */
    fun closing(ending: String): String = "finished_" + ending.lowercase()

    /** Every reason the engine adds itself, in full. `died` and `left` take the name; `stood_in` the new name and the old; `stays`, `stays_kept` and `stays_off` the household's family name. */
    val ENGINE_WHY: List<String> = listOf(
        "fortune.guildmark.reason.tale.why.died",
        "fortune.guildmark.reason.tale.why.left",
        "fortune.guildmark.reason.tale.why.stood_in",
        "fortune.guildmark.reason.tale.why.none_left",
        "fortune.guildmark.reason.tale.why.late",
        "fortune.guildmark.reason.tale.why.skipped",
        "fortune.guildmark.reason.tale.why.broke",
        "fortune.guildmark.reason.tale.why.finished_a",
        "fortune.guildmark.reason.tale.why.finished_b",
        "fortune.guildmark.reason.tale.why.finished_c",
        "fortune.guildmark.reason.tale.why.finished_w",
        "fortune.guildmark.reason.tale.why.stays",
        "fortune.guildmark.reason.tale.why.stays_kept",
        "fortune.guildmark.reason.tale.why.stays_off",
    )

    /** The other lines the engine sends a player. */
    const val PARTY: String = "message.guildmark.tale.party"

    val ENGINE_LINES: List<String> = listOf(PARTY)
}
