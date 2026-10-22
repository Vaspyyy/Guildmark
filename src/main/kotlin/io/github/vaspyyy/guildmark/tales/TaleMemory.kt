package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.talk.Favour
import io.github.vaspyyy.guildmark.townsfolk.talk.Memory
import io.github.vaspyyy.guildmark.townsfolk.talk.Mind
import io.github.vaspyyy.guildmark.townsfolk.talk.Story
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalkData
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * Stage 4 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: memory (6.6). [run] gives the town's thanks
 * (`Favour.TALE_HELPED`, 3.0, the weight of freeing someone) for an ending A or B to the people the tale was about and their
 * households, from each player who did a real step (the lead too, if they did one), through [favour]; and [heard] gives
 * `Favour.TALE_HEARD` (1.0) to the lead from each resident who hears the good ending's rumour, once for each tale. Nothing for C or
 * W, and never a slight for saying no, for a step that lapsed, or for an ending W (3.5, "Never punish no"). Runs once for a
 * tale; the engine guards it. With the townsfolk's memory off (`townsfolkMemory`) `TownTalk.favour` keeps nothing.
 *
 * Who "the people the tale was about" are: every resident of the cast who is alive, unless the template says better by implementing
 * [Helps] (the lender who only held a note is not whom a thanked stall keeper's household means).
 */
object TaleMemory {
    /** A template may implement this, besides [Template], to name the cast slots whose people the town thanks: the essential ones (8.x, "Cast"). Without it, the whole cast is thanked. */
    interface Helps {
        fun helped(t: Tale): List<String>
    }

    fun run(c: Ctx, t: Tale) {
        if (t.ending != Ending.A && t.ending != Ending.B) return
        val players = t.parts.filter { TaleShares.holds(it) }.map { it.player }
        if (players.isEmpty()) return
        val slots = (Templates.byId(t.template) as? Helps)?.helped(t) ?: t.cast.map { it.slot }
        for (player in players) {
            val thanked = HashSet<Int>()
            for (slot in slots) favour(c, t, slot, player, Favour.TALE_HELPED, thanked)
        }
    }

    /**
     * What `Ctx.favour` does: [favour] to the resident in [slot] of [t]'s cast, if they are alive, and to their household, from
     * [player]. [thanked] holds the residents already given it for this player, so a household in the cast twice is thanked once.
     */
    fun favour(c: Ctx, t: Tale, slot: String, player: UUID, favour: Favour, thanked: MutableSet<Int> = HashSet()) {
        if (!Tales.READY) return
        val r = c.resident(t, slot)?.takeIf { it.alive } ?: return
        for (who in listOf(r) + household(c.level, r)) {
            if (thanked.add(who.id)) TownTalk.favour(c.level, who, player, favour)
        }
    }

    /** The living people of [r]'s home other than [r] (none for a resident with no home). */
    fun household(level: ServerLevel, r: Resident): List<Resident> =
        if (r.home < 0) listOf() else Residents.get(level).of(r.capital).filter { it.alive && it.id != r.id && it.home == r.home }

    /**
     * [mind] has heard [s], a tale's rumour (kind `tale.<template>.<word>`), for the first time: a good ending (tone +1) is the town's
     * thanks to the one it is about, the tale's lead, once for each rumour. Called by `TownTalk.heard`, as the works' are.
     */
    internal fun heard(@Suppress("UNUSED_PARAMETER") entry: TownTalkData.Entry, mind: Mind, s: Story) {
        val rumour = s.rumour
        val about = rumour.about ?: return
        if (rumour.tone <= 0 || mind.id < 0 || !Memory.enabled()) return
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return
        val day = Memory.day(level)
        val acq = Memory.acq(mind, about, day, true) ?: return
        if (Memory.favourOnce(acq, day, Favour.TALE_HEARD, rumour.id.toLong())) TownTalkData.get(level).changed()
    }
}
