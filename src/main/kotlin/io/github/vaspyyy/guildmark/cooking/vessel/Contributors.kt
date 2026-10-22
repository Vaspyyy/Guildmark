package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * Who put their hands to a vessel (living-world/cook/design.md 1.7): each player who added something or answered a call is
 * an entry with the count of what they did. The dish's cook name and the Book entry go to the player who did most, the
 * XP is split by each one's share of the actions, and every contributor gets the Book entry. Saved with the vessel, a few
 * bytes each; nothing here is ever sent to a client.
 */
class Contributors {
    /** One player's hands in the batch: [adds] items put in and [answers] calls answered (full or late). */
    class Entry(val id: UUID, var name: String, var adds: Int = 0, var answers: Int = 0) {
        /** What they did, adds and answered calls together. */
        val actions: Int get() = adds + answers
    }

    private val entries = ArrayList<Entry>(2)

    /** The entries, in the order they first acted. */
    val all: List<Entry> get() = entries

    /** Whether nobody has acted yet. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The entry of [id], made if it is new ([name] is what the dish will carry). */
    fun of(id: UUID, name: String): Entry {
        for (e in entries) {
            if (e.id == id) {
                if (name.isNotEmpty()) e.name = name
                return e
            }
        }
        if (entries.size >= MAX) return entries[entries.size - 1]
        val made = Entry(id, name)
        entries.add(made)
        return made
    }

    /** [id] put an item in. */
    fun added(id: UUID, name: String) {
        of(id, name).adds++
    }

    /** [id] answered a call. */
    fun answered(id: UUID, name: String) {
        of(id, name).answers++
    }

    /** The sum of everyone's actions. */
    fun total(): Int {
        var n = 0
        for (e in entries) n += e.actions
        return n
    }

    /** The player who did most (the first to act wins a tie), or null if nobody has acted. */
    fun top(): Entry? {
        var best: Entry? = null
        for (e in entries) if (best == null || e.actions > best.actions) best = e
        return best
    }

    /** [e]'s share of the actions, 0 to 1 (a lone contributor's is 1). */
    fun share(e: Entry): Double {
        val total = total()
        return if (total <= 0) 1.0 / entries.size.coerceAtLeast(1) else e.actions.toDouble() / total
    }

    /** Forgets everyone: the vessel is empty again. */
    fun clear() {
        entries.clear()
    }

    /** Writes the entries into [out], under `who`. */
    fun save(out: ValueOutput) {
        if (entries.isEmpty()) return
        val list = out.childrenList("who")
        for (e in entries) {
            val c = list.addChild()
            c.putString("id", e.id.toString())
            c.putString("name", e.name)
            c.putInt("adds", e.adds)
            c.putInt("answers", e.answers)
        }
    }

    /** Reads what [save] wrote; every field is optional and an entry without a readable id is skipped. */
    fun load(input: ValueInput) {
        entries.clear()
        val list = input.childrenListOrEmpty("who")
        for (c in list) {
            val id = try {
                UUID.fromString(c.getStringOr("id", ""))
            } catch (e: IllegalArgumentException) {
                continue
            }
            entries.add(Entry(id, c.getStringOr("name", ""), c.getIntOr("adds", 0).coerceAtLeast(0), c.getIntOr("answers", 0).coerceAtLeast(0)))
            if (entries.size >= MAX) break
        }
    }

    companion object {
        /** The most players a vessel remembers; more than this cannot stand at one pot. */
        const val MAX = 8
    }
}
