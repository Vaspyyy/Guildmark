package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Labour
import io.github.vaspyyy.guildmark.townsfolk.Lookout
import io.github.vaspyyy.guildmark.townsfolk.Towns
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import java.util.UUID

/**
 * The townsfolk's side of M6 in the streets (living-world/design-influence.md sections 3, 5 and 6): the scenes that
 * show a capital's works and times in its people. Each follows from a record (a works going up or topped out, bread
 * short of a morning, coal short in the cold, the times, a stall sold out, the alms table serving, a household come or
 * gone), and each is a part of someone's day or a pick in their free time, never a takeover of the town: the days
 * ([io.github.vaspyyy.guildmark.townsfolk.Days]) stay the backbone, a scene breaks off when the day moves on, nobody is
 * in two at once, and there's one of each scene to a place however many players are about. They're decided here on
 * the server, only round players, a little each tick (each player is looked round once a second, one kind of scene
 * on each of a few ticks of it); nothing is scanned against everything, and no chunk is loaded for them.
 *
 * The switches: the works' scenes (watchers, children playing builders, the topping out) and the labourers' and
 * lookouts' days go with [Works.enabled]; the streets' (the bread queue, the market as the times go, shivering,
 * grumbling at the board, a stall shut, the alms table) and the crowds with [Fortunes.enabled]; the households' (seeing
 * one off, welcoming one in) with [Comings.enabled]. `/guildmark capital scene <name>` plays one now whatever they say
 * ([SceneCommand]).
 */
object Scenes {
    /** One player's whereabouts: the capital they're in and its places, looked up now and then. */
    class Here(val capital: CapitalPiece, val places: CityPlaces) {
        val key: Long get() = capital.center.asLong()
    }

    private class Seen(val at: Long, val here: Here?)

    private val seen = HashMap<UUID, Seen>()

    private class Known(val at: Long, val works: List<WorksView>)

    private val known = HashMap<Long, Known>()

    /** How long a player's whereabouts and a capital's works are kept before they're looked up again. */
    private const val LOOKED = 100L

    private var started = false

    /**
     * Sets the scenes going: the works' stages heard (the topping out), the households' comings and goings, and all
     * forgotten as the server stops. Once, at setup (Townsfolk.init).
     */
    fun init() {
        if (started) return
        started = true
        Works.listen(ToppingOut)
        Comings.listen(Neighbours)
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    /** Whether any of M6's scenes are on. */
    fun any(): Boolean = Works.enabled() || Fortunes.enabled() || Comings.enabled()

    /** Each tick for each player, from [io.github.vaspyyy.guildmark.townsfolk.Crowds.tick]. */
    fun tick(level: ServerLevel, player: ServerPlayer) {
        if (!started) init()
        Labour.tick(level)
        val works = Works.enabled()
        val streets = Fortunes.enabled()
        val homes = Comings.enabled()
        if (!works && !streets && !homes) return
        if (player.isSpectator || level.dimension() != Level.OVERWORLD) return
        val slot = player.tickCount % 20
        if (slot % 2 == 0 || slot < 3 || slot > 17) return
        val here = here(level, player) ?: return
        when (slot) {
            3 -> if (works) Watchers.look(level, player, here)
            5 -> if (works) ToppingOut.look(level, player)
            7 -> if (streets) Grumbling.look(level, player, here)
            9 -> if (streets) Cold.look(level, player, here)
            11 -> if (streets) BreadQueue.look(level, player, here)
            13 -> if (streets) Alms.look(level, player, here)
            15 -> if (homes) Neighbours.look(level, player)
            17 -> if (streets) ShutStall.look(level, player, here)
        }
    }

    /** The capital [player] is in, and its places, if they're worked out yet. */
    fun here(level: ServerLevel, player: ServerPlayer): Here? {
        val now = level.gameTime
        val pos = player.blockPosition()
        seen[player.uuid]?.let { s ->
            if (now - s.at in 0 until LOOKED && (s.here == null || s.here.capital.reaches(pos.x, pos.z))) return s.here
        }
        val here = if (Towns.around(level, player.uuid, pos) == null) null else Capitals.covering(level, pos)?.let { capital ->
            CityPlaces.of(level, capital)?.let { Here(capital, it) }
        }
        if (seen.size > 256) seen.clear()
        seen[player.uuid] = Seen(now, here)
        return here
    }

    /** [capital]'s works, in any state, as the building has them (looked up again every few seconds). */
    fun works(level: ServerLevel, capital: Long): List<WorksView> {
        val now = level.gameTime
        known[capital]?.let { if (now - it.at in 0 until LOOKED) return it.works }
        val all = try {
            Works.all(level, capital)
        } catch (e: RuntimeException) {
            listOf()
        }
        known[capital] = Known(now, all)
        return all
    }

    /** The capital whose heart is [capital], if it's known. */
    fun capital(level: ServerLevel, capital: Long): CapitalPiece? = Capitals.covering(level, BlockPos.of(capital))

    /** Whether any player is within [r] blocks of [pos]. */
    fun seen(level: ServerLevel, pos: BlockPos, r: Double): Boolean =
        level.getNearestPlayer(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, r, false) != null

    /** Everything forgotten, as the server stops. */
    fun forget() {
        seen.clear()
        known.clear()
        Watchers.forget()
        Builders.forget()
        ToppingOut.forget()
        BreadQueue.forget()
        Grumbling.forget()
        Cold.forget()
        Neighbours.forget()
        ShutStall.forget()
        Alms.forget()
        Lookout.forget()
    }
}
