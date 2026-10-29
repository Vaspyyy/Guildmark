package io.github.vaspyyy.guildmark.cooking

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

/**
 * What a cook is shown doing (living-world/cook/design.md 1.8, 3.3, 4.3): every action of [CookAction] is sent as a
 * [CookActionPayload] to the players who see the cook and to the cook, so each of their clients plays the clip of 11.3
 * on the cook's body (the client's `CookActionHandler`). The cook's own client has usually started the clip already, on
 * the click; the payload confirms it, or corrects a wrong guess.
 *
 * At most [PER_SECOND] actions a cook in any second are sent: a clip is longer than a click's repeat, so what is held
 * back is covered by the clip already playing, and a cook who spams a station costs the server six small payloads a
 * second at worst. Off with Hearthcraft: nothing is sent, and the bodies stay as they were.
 */
object CookActions {
    /** The most actions sent for one cook in any twenty ticks. */
    const val PER_SECOND = 6
    private const val SECOND = 20

    /** The clip [action] plays (11.3), by its name under `animations/player/` (and `animations/first_person/`, where M3 made one). */
    fun clip(action: CookAction): String = when (action) {
        CookAction.EAT_SPOON -> "eat_spoon"
        CookAction.EAT_BITE -> "eat_bite"
        CookAction.DRINK -> "drink_bowl"
        else -> "cook_" + action.name.lowercase()
    }

    /** How long [action]'s clip runs, in ticks (11.3; the shiver is a loop of this length). */
    fun length(action: CookAction): Int = when (action) {
        CookAction.ADD -> 10
        CookAction.FEED -> 12
        CookAction.STIR -> 24
        CookAction.TASTE -> 40
        CookAction.EASE -> 14
        CookAction.SKIM -> 16
        CookAction.SERVE -> 20
        CookAction.SERVE_PEEL -> 22
        CookAction.CHOP -> 8
        CookAction.FLIP -> 14
        CookAction.TURN -> 12
        CookAction.CARVE -> 20
        CookAction.SLICE -> 16
        CookAction.SHIVER -> 40
        CookAction.EAT_SPOON -> 32
        CookAction.EAT_BITE -> 24
        CookAction.DRINK -> 32
    }

    /** The action whose clip is called [name], or null. */
    fun byClip(name: String): CookAction? = CookAction.entries.firstOrNull { clip(it) == name }

    /** The server ticks of a cook's last [PER_SECOND] sends, oldest at [next]. */
    private class Sends {
        val ticks = LongArray(PER_SECOND) { Long.MIN_VALUE }
        var next = 0
    }

    private val sends = HashMap<UUID, Sends>()

    /** Counted for `cook perf`: payloads sent, and actions held back by the limit. */
    private var sent = 0L
    private var held = 0L

    /** Shows [player] doing [action] at [pos] to the players tracking them and to them, at most six a second a cook. */
    fun play(player: ServerPlayer, action: CookAction, pos: BlockPos) {
        if (!Cooking.enabled()) return
        val now = player.level().server.tickCount.toLong()
        val mine = sends.getOrPut(player.uuid) { Sends() }
        // The oldest of the last six went out less than a second ago: this one waits for the clip already playing
        if (mine.ticks[mine.next] > now - SECOND) {
            held++
            return
        }
        mine.ticks[mine.next] = now
        mine.next = (mine.next + 1) % PER_SECOND
        sent++
        PerfCommand.sent(PerfCommand.Payload.ACTION)
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, CookActionPayload(player.id, action, pos.immutable(), player.level().gameTime))
    }

    /** Part E's registration: the `cook anim` command, the line of `cook perf`, and the clearing of the table. */
    fun register() {
        CookAnimCommand.register()
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { sends.remove(it.entity.uuid) }
        Cooking.onForget {
            sends.clear()
            sent = 0L
            held = 0L
        }
        CookStats.readout("cook in motion") {
            "$sent actions sent, $held held back by the six-a-second limit; ${sends.size} cooks counted, ${CookAnimCommand.looping()} looping (cook anim)"
        }
    }
}
