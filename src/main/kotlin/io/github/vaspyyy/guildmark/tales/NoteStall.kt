package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.WaresBlockEntity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.ShutStall
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * The debtor's stall in the Marlow note (living-world/stories/design.md 8.1), Tales T1a: what the tale does to a market stall and what it
 * hears at one, kept apart from the template so that the three small hooks in other systems (`Witness.dealt`, `ShutStall`, `Awnings`) each
 * make one short call here.
 *
 * - **Bought** ([bought]): a deal a player made at a keeper's stall is told to the tale that has the keeper in its cast, as `Event.Bought`,
 *   when an open tale's step would hear it. Selling to a keeper counts nothing.
 * - **Sealed** ([sealed], [seal]): a stall sealed for the note is `TaleData.sealed[stall] = last market day`, saved, so that it is shut again
 *   after a restart and opens on its day with no further call ([ShutStall.bare] asks [Tales.sealed] first); with Town Tales off it is
 *   not asked, and the stall is as it was. The answer is kept for a second, as the stalls are looked at every few seconds.
 * - **Leased** ([leased]): after the note's lease (ending C) the stall stays open and its slate is chalked "<lender>'s lease" for ten market
 *   days. Not a saved field of its own: it is read back from the ended tale, which the record keeps forty days.
 * - **The slate's words** ([word]): "Closed: the note" on a sealed stall's slate, `slate.guildmark.sealed`.
 * - **The scene first** ([hold], [chalk]): when the ending's scene is going to show the awning let down, or the lease chalked, the look of it
 *   waits ninety seconds for the scene, though the record has it already; the scene shows it ([seal] without `staged`, [chalk]) and the
 *   look is the record's again. With no scene played, the stall is as the record has it when the ninety seconds are over.
 * - `tale seal list|clear`: which stalls are sealed or leased, and until when; `clear` lets them all go.
 */
internal object NoteStall {
    /** The market days a stall stays sealed for the note, and the days the lease stays chalked on the slate. */
    const val DAYS = 10
    const val LEASE_DAYS = 10

    /** The ticks a seal's lock on the keeper's stall runs ahead of the record (a minute), so the awning does not go back up between the cord and the first look at the record. */
    private const val BRIDGE = 1200L

    /** The `s` slot of the note tale that keeps its stall's place (`Note.strings`), and the note's template id. */
    const val STALL = 1
    private const val NOTE = "note"

    /**
     * The note tales whose ending a scene has already shown at the stall (the step-3 scene, which then ends the tale), by tale id: the ending's
     * own scene is not played again for them. Memory only; a tale that ended while no scene was played has its ending scene queued once, as the
     * engine does for any ending.
     */
    internal val shown = HashSet<Int>()

    private class Look(val at: Long, val sealed: Boolean)

    private val looked = HashMap<Long, Look>()

    /** Ticks a sealed stall's or a leased stall's look waits for its scene (ninety seconds). */
    private const val WAIT = 1800L

    /** The stalls (by key) whose look waits for a scene until a game time, and the lease the scene has chalked and until when it stands without the record. */
    private val held = HashMap<Long, Long>()

    private class Chalk(val name: String, val until: Long)

    private val chalked = HashMap<Long, Chalk>()

    private class Lease(val name: String, val capital: Long, val until: Long)

    private var leases: Map<Long, Lease> = mapOf()
    private var leasesAt = Long.MIN_VALUE / 2

    // ---- From the hooks ----

    /** `Tales.bought`: [player] bought at [keeper]'s stall. The tale that has the keeper in its cast hears it if its step listens. */
    fun bought(level: ServerLevel, keeper: Townsperson, player: ServerPlayer, good: Good, lots: Int, marks: Int) {
        if (!Tales.live || keeper.resident < 0 || !TaleEvents.listening(level, Event.Kind.BOUGHT)) return
        Tales.tell(level, Event.Bought(player, keeper.resident, good, true, lots, marks))
    }

    /** `Tales.sealed`: whether the stall at [key] (its keeper's place) is sealed by the tales' record at game time [gameTime]. */
    fun sealed(key: Long, gameTime: Long): Boolean {
        if (!Tales.live) return false
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return false
        val data = TaleData.get(level)
        if (data.sealed.isEmpty()) return false
        val until = data.sealed[key] ?: return false
        if (held[key]?.let { gameTime < it } == true) return false
        looked[key]?.let { if (gameTime - it.at in 0L..19L) return it.sealed }
        val yes = today(level, data, key) <= until
        looked[key] = Look(gameTime, yes)
        return yes
    }

    /** The words chalked on the slate of the stall at [key]: the seal's if it is sealed, else the streets' own. */
    fun word(level: ServerLevel, key: Long): Int =
        if (sealed(key, level.gameTime)) WaresBlockEntity.SLATE_SEALED else WaresBlockEntity.SLATE_NOTHING

    /** The lender's name the lease of the stall at [key] is chalked with ("Vale", for "Vale's lease") while its lease lasts, else null. */
    fun leased(level: ServerLevel, key: Long, now: Long): String? {
        if (!Tales.live) return null
        chalked[key]?.let { if (now < it.until) return it.name else chalked.remove(key) }
        if (held[key]?.let { now < it } == true) return null
        if (now - leasesAt !in 0L..99L) {
            leasesAt = now
            leases = readLeases(level)
        }
        return leases[key]?.name
    }

    // ---- What the tale does ----

    /**
     * Seals the stall at [key] from market day [Ctx.day] for [DAYS] days (the last of them is `day + DAYS - 1`), in the record, and holds it
     * bare for the minute it takes the record to be read. Done again it changes nothing while the seal lasts, so the scene and the ending may
     * both ask. At [TaleLimits.SEALED] the seal that ends soonest is let go to make room. With [staged] a scene is to let the awning down:
     * the record has the seal, and the stall keeps its look for ninety seconds, until the scene shows it ([seal] again, not staged).
     */
    fun seal(c: Ctx, key: Long, staged: Boolean = false) {
        val data = c.data
        if (data.sealed[key]?.let { it >= c.day } != true) {
            while (data.sealed.size >= TaleLimits.SEALED && key !in data.sealed) {
                data.sealed.remove(data.sealed.minByOrNull { it.value }?.key ?: break)
            }
            data.sealed[key] = c.day + DAYS - 1
            data.changed()
            looked.remove(key)
        }
        if (staged) {
            held[key] = c.level.gameTime + WAIT
        } else {
            held.remove(key)
            ShutStall.lock(key, c.level.gameTime + BRIDGE)
        }
    }

    /** The lease of the stall at [key] is to be shown by a scene: its slate waits ninety seconds for the lender's chalk ([chalk]). */
    fun hold(level: ServerLevel, key: Long) {
        held[key] = level.gameTime + WAIT
    }

    /** The lender has chalked "[name]'s lease" on the stall at [key] in a scene: it stands on the slate until the record has it. */
    fun chalk(level: ServerLevel, key: Long, name: String) {
        held.remove(key)
        chalked[key] = Chalk(name, level.gameTime + WAIT)
    }

    /** The lender's name for a slate: the last word of his name ("Orrin Vale" is "Vale"). */
    fun surname(name: String): String = name.trim().substringAfterLast(' ')

    private fun readLeases(level: ServerLevel): Map<Long, Lease> {
        val out = HashMap<Long, Lease>()
        for (t in TaleData.get(level).all()) {
            if (t.template != NOTE || t.open || t.ending != Ending.C || !TaleKeep.real(t.ended)) continue
            val key = t.s[STALL].toLongOrNull() ?: continue
            val until = t.ended + LEASE_DAYS - 1
            if (TaleEngine.today(level, t.capital) > until) continue
            out[key] = Lease(surname(t.cast("lender")?.name.orEmpty()), t.capital, until)
        }
        return out
    }

    /** The market day it is where the stall at [key] is: that of the nearest capital the tales know. */
    private fun today(level: ServerLevel, data: TaleData, key: Long): Long {
        val at = BlockPos.of(key)
        val capital = data.towns().keys.minByOrNull { BlockPos.of(it).distSqr(at) } ?: return TaleData.NEVER
        return TaleEngine.today(level, capital)
    }

    // ---- tale seal list|clear ----

    /** Appends `tale seal` to the `tale` command (one line in [Tales.register]). */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
        NoteStages.register()
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("seal")
                    .executes { list(it.source) }
                    .then(Commands.literal("list").executes { list(it.source) })
                    .then(Commands.literal("clear").executes { clear(it.source) }),
            )
        }
    }

    private fun list(source: CommandSourceStack): Int {
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val level = source.server.overworld()
        val data = TaleData.get(level)
        leasesAt = Long.MIN_VALUE / 2
        val held = readLeases(level)
        TaleCommand.say(source, TaleCommand.header("Tales: stalls sealed or leased"))
        if (data.sealed.isEmpty() && held.isEmpty()) return TaleCommand.say(source, TaleCommand.grey("No stall is sealed or leased by a tale."))
        for ((key, until) in data.sealed.entries.sortedBy { it.key }) {
            val today = today(level, data, key)
            val state = if (!Tales.live) "not kept: Town Tales are off" else if (today <= until) "${until - today + 1} market days left" else "over"
            TaleCommand.say(
                source,
                TaleCommand.line().append(TaleCommand.label("sealed ")).append(TaleCommand.plain("stall at ${BlockPos.of(key).toShortString()}: through day $until ($state)")),
            )
        }
        for ((key, lease) in held.entries.sortedBy { it.key }) {
            TaleCommand.say(
                source,
                TaleCommand.line().append(TaleCommand.label("leased ")).append(TaleCommand.plain("stall at ${BlockPos.of(key).toShortString()}: ${lease.name}'s lease, through day ${lease.until}")),
            )
        }
        return 1
    }

    private fun clear(source: CommandSourceStack): Int {
        val level = source.server.overworld()
        val data = TaleData.get(level)
        var n = 0
        for (key in data.sealed.keys.toList()) {
            ShutStall.unlock(key)
            n++
        }
        data.sealed.clear()
        // A lease is read back from its ended tale: forget the stall it names
        for (t in data.all()) {
            if (t.template == NOTE && !t.open && t.s[STALL].isNotEmpty() && t.ending == Ending.C) {
                t.s[STALL] = ""
                n++
            }
        }
        if (n > 0) data.changed()
        looked.clear()
        held.clear()
        chalked.clear()
        leasesAt = Long.MIN_VALUE / 2
        leases = mapOf()
        return TaleCommand.say(
            source,
            TaleCommand.grey(if (n == 0) "Nothing was sealed or leased." else "$n seal(s) and lease(s) let go: the keepers put the awnings up as they next look at their stalls."),
        )
    }

    /** Memory forgotten, as the server stops. */
    fun forget() {
        looked.clear()
        shown.clear()
        held.clear()
        chalked.clear()
        leases = mapOf()
        leasesAt = Long.MIN_VALUE / 2
    }
}
