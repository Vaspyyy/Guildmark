package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * What the engine remembers between calls, and the one clock it runs by (living-world/stories/design.md 2.3, 10.0 and 11.7).
 * Nothing here ticks for itself but one pass a second, which only keeps the lists a hook reads, notes where the players are
 * (`seen`) and tries again whatever waited for a capital's places to be worked out; all the doing is at a capital's close
 * ([TaleClose]) and at the events the listeners hear ([TaleEvents]). Everything here is memory: the record is the truth, and a
 * restart loses only what is said below to be lost.
 */
internal object TaleEngine {
    /** Game ticks a closure that waits for its capital's places is kept before it is let go (ten minutes). */
    private const val WAIT_TICKS = 12000L

    /** The closures waiting at once, at most. */
    private const val WAITING = 64

    /** The overworld, as the last tick or close saw it: what [holds] and [volunteers] read, since a hook has no level of its own. */
    @Volatile
    private var level: ServerLevel? = null

    /** The record the open-tale list was made from, so a new world is told by the record not being the same. */
    private var record: TaleData? = null

    /** The open tales in id order, as of the last [reindex]. Only [openTales] makes it. */
    @Volatile
    private var open: List<Tale> = listOf()

    @Volatile
    private var stale = true

    /** Something to do when a capital's places are known: a cast member gone, a player's yes that ends an asking. */
    private class Waiting(val capital: Long, val what: String, val until: Long, val body: (Ctx) -> Unit)

    private val waiting = ArrayList<Waiting>()

    private var warnedVersion = false

    // ---- Setting up ----

    /** At setup (`Tales.register`): the clock, the listeners and the commands. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent::class.java) { start(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % 20 == 0) second(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> stop() }
        Comings.listen(Tales)
        Caravans.listen(TaleListeners.Road)
        Works.listen(TaleListeners.Building)
    }

    private fun start(server: MinecraftServer) {
        level = server.overworld()
        Tales.refresh()
        if (!Tales.live) return
        val version = TaleData.get(server.overworld()).version
        if (version > TaleLimits.VERSION && !warnedVersion) {
            warnedVersion = true
            Guildmark.LOGGER.warn("The tales file is version {}, newer than this build's {}: it is read as far as its fields are known", version, TaleLimits.VERSION)
        }
        reindex()
    }

    /** As the server stops: all of the engine's memory is let go, so the next world starts clean. */
    private fun stop() {
        level = null
        record = null
        open = listOf()
        stale = true
        waiting.clear()
        warnedVersion = false
        TaleClose.forget()
        TaleGuard.forget()
    }

    /** The switch went off (`Tales.pause`): what waited for a capital is let go, and whatever shows tales is told. */
    fun pause(level: ServerLevel) {
        waiting.clear()
        TaleClose.forget()
        TaleGuard.forget()
        stale = true
        Tales.hear { it.paused(level) }
    }

    /** `Tales.refresh` found the switch gone off: the pause, with the level the engine last saw. */
    fun paused() {
        level?.let { Tales.pause(it) }
    }

    /** Remembers [level]'s overworld for the hooks that have no level. */
    fun use(level: ServerLevel) {
        this.level = level.server.overworld()
    }

    // ---- Each second ----

    private fun second(server: MinecraftServer) {
        val overworld = server.overworld()
        level = overworld
        Tales.refresh()
        if (!Tales.live) return
        val data = TaleData.get(overworld)
        for (player in overworld.players()) {
            if (player.isSpectator) continue
            val here = Scenes.here(overworld, player) ?: continue
            noteSeen(overworld, data, here.key)
        }
        TaleClose.retry(overworld)
        retry(overworld)
    }

    /** A player is in the capital [key] today (`seen`, 2.2 step 4). */
    fun see(level: ServerLevel, key: Long) {
        noteSeen(level, TaleData.get(level), key)
    }

    private fun noteSeen(level: ServerLevel, data: TaleData, key: Long) {
        val day = today(level, key)
        val town = data.town(key)
        if (town.seen < day) {
            town.seen = day
            data.changed()
        }
    }

    // ---- The open tales ----

    /** Makes the list of open tales again at its next read: a tale was seeded, ended or cleared. */
    fun reindex() {
        stale = true
    }

    /** The open tales, in id order. A look at a list kept between changes, not at the record. */
    fun openTales(level: ServerLevel): List<Tale> {
        val data = TaleData.get(level)
        if (stale || data !== record) {
            record = data
            open = data.all().filter { it.open }.sortedBy { it.id }
            stale = false
        }
        return open
    }

    /** Whether resident [id] is held by an open tale (`Tales.holds`). */
    fun holds(id: Int): Boolean {
        val level = level ?: return false
        for (t in openTales(level)) for (c in t.cast) if (c.held && c.resident == id) return true
        return false
    }

    /** The residents the works of [capital] take first, today (`Tales.volunteers`). */
    fun volunteers(capital: Long): Set<Int> {
        val level = level ?: return setOf()
        val town = TaleData.get(level).find(capital) ?: return setOf()
        if (town.volunteers.isEmpty()) return setOf()
        val today = today(level, capital)
        return town.volunteers.filter { it.until >= today }.mapTo(HashSet()) { it.resident }
    }

    // ---- The day, and the capital's places ----

    /**
     * The day tales stand at in [capital]: its market's day, or the last day its tales closed if that is later (a market run
     * on ahead has lived those days already), as the fortunes' and the comings' own days are.
     */
    fun today(level: ServerLevel, capital: Long): Long {
        val entry = Markets.find(level, capital)
        val closed = TaleData.get(level).find(capital)?.closed ?: TaleData.NEVER
        return maxOf(Conditions(entry?.market?.hour ?: Markets.hourOf(level)).day, closed)
    }

    /**
     * What a template is given for [capital]: its places, its market and the day ([day], or today). Null while any of them is
     * not known yet (the capital's piece is found a few at a time, its places are worked out off the server thread), which is
     * a matter of seconds the first time and never after; [withCtx] waits for it.
     */
    fun ctx(level: ServerLevel, capital: Long, day: Long? = null): Ctx? {
        val entry = Markets.find(level, capital) ?: return null
        val piece = CaravanRoutes.capital(level, capital, soon = true) ?: return null
        val places = CityPlaces.of(level, piece) ?: return null
        return Ctx(level, Scenes.Here(piece, places), entry, day ?: today(level, capital))
    }

    /**
     * Runs [body] with [capital]'s [Ctx], guarded: now if the places are known, else as soon as they are (within ten minutes,
     * and no more than [WAITING] such at once). Only what must not be lost waits like this: a cast member gone, a player's yes.
     */
    fun withCtx(level: ServerLevel, capital: Long, what: String, body: (Ctx) -> Unit) {
        val c = ctx(level, capital)
        if (c != null) {
            guarded(what) { body(c) }
            return
        }
        if (waiting.size >= WAITING) {
            Guildmark.LOGGER.warn("Tales did not wait for the places of the capital at {} for {}: too many are waiting", capital, what)
            return
        }
        waiting.add(Waiting(capital, what, level.gameTime + WAIT_TICKS, body))
    }

    private fun retry(level: ServerLevel) {
        if (waiting.isEmpty()) return
        for (w in waiting.toList()) {
            if (level.gameTime > w.until) {
                waiting.remove(w)
                Guildmark.LOGGER.warn("Tales gave up waiting for the places of the capital at {} for {}", w.capital, w.what)
                continue
            }
            val c = ctx(level, w.capital) ?: continue
            waiting.remove(w)
            guarded(w.what) { w.body(c) }
        }
    }

    /** Runs [body], logging and going on if it throws. */
    fun guarded(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Tales failed in {}", what, e)
        }
    }

    // ---- People ----

    /** A player's name as the world knows it, whether they are on or not; "" if it cannot be told. */
    fun playerName(level: ServerLevel, id: UUID): String =
        level.server.playerList.getPlayer(id)?.name?.string ?: level.server.services().nameToIdCache().get(id).map { it.name }.orElse("")
}
