package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * The Fourth Wagon's hand in the band's camp (living-world/stories/design.md 8.3 and 10.2): a town's resident the band holds as
 * `Captives` holds anyone it takes, knelt by a stake, freed by a player's knife or the band's own word, and walked home behind
 * whoever freed them. The two calls of `Captives` (`homecoming` and `onDeath`, through `Tales.captiveHome` and `Tales.captiveDied`)
 * come here and are told to the tale whose cast has the resident (`Event.Captive`).
 */
internal object WagonCamp {
    /** Who brought each resident home, as `Captives.homecoming` had it, for the tale to read as it hears of it (by resident). */
    private val rescuers = HashMap<Int, UUID>()

    /** From `Captives.homecoming`: captive [p] is home; the tale that has their resident hears of it. */
    fun home(level: ServerLevel, p: Townsperson) {
        if (!Tales.live || p.resident < 0) return
        p.rescuer?.let { rescuers[p.resident] = it }
        Tales.tell(level, Event.Captive(p.resident, true))
        rescuers.remove(p.resident)
    }

    /** From `Captives.onDeath`: captive [p] died in the band's hands; the tale that has their resident hears of it. */
    fun died(level: ServerLevel, p: Townsperson) {
        if (!Tales.live || p.resident < 0) return
        Tales.tell(level, Event.Captive(p.resident, false))
    }

    /** The player who brought [resident] home, while the tale is hearing of it. */
    fun rescuer(resident: Int): UUID? = rescuers[resident]
}
