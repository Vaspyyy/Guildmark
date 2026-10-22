package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * The back doors of the capitals' inns and bakeries (living-world/cook/design.md 5.2, 7.7): the listener that sees a cook hold a
 * dish out to a keeper, the payment of Marks still owed at login and respawn, and the few small things the ledger does on the
 * server's tick (the sweep of sales that can never finish, the pantry's showing). The sale itself is the ledger's
 * ([Kitchens.sell]) and its scene the keeper's ([KitchenDoor]).
 */
object KitchenDoors {
    /** Ticks between the sweeps for slips whose scene is over, and between the pantry's checks for players who have come into range. */
    private const val SWEEP = 10
    private const val SHOWN = 40

    /** The game time each player last held a dish out to a keeper, so the same click does not also eat it (see [onUseItem]). */
    private val poked = HashMap<UUID, Long>()

    /** Part G's registration: the `EntityInteract` listener, the payment of what is owed, the server's tick, and its commands (`cook kitchens|request|door|caps|owed`). */
    fun register() {
        Cooking.onForget { poked.clear() }
        KitchenCommand.register()
        val bus = NeoForge.EVENT_BUS
        bus.addListener(PlayerInteractEvent.EntityInteract::class.java, ::onUseEntity)
        bus.addListener(PlayerInteractEvent.RightClickItem::class.java, ::onUseItem)
        bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { (it.entity as? ServerPlayer)?.let { player -> Owed.all(player) } }
        bus.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { (it.entity as? ServerPlayer)?.let { player -> Owed.all(player) } }
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { (it.entity as? ServerPlayer)?.let { player -> left(player.uuid) } }
        bus.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        CookStats.readout("Kitchens (ledger and back doors)") { readout() }
    }

    /**
     * A player used a townsperson with a dish in their main hand. It does what the market hall's merchants do for a player's
     * own stall (`StallDesk.onUseEntity`): the event comes before the townsperson's own `interact`, so before any director or
     * the stall's screen, and only the innkeeper or the baker of a capital, with a dish in hand, is any of its business; for
     * everyone else it returns at once and the usual chain runs (a guard offered a pie talks as ever).
     */
    private fun onUseEntity(event: PlayerInteractEvent.EntityInteract) {
        val player = event.entity as? ServerPlayer ?: return
        if (event.hand != InteractionHand.MAIN_HAND || player.isSpectator) return
        val stack = player.mainHandItem
        if (stack.item !is DishItem) return
        val keeper = event.target as? Townsperson ?: return
        if (keeper.role != TownRole.INNKEEPER && keeper.role != TownRole.BAKER) return
        // Hearthcraft off: nothing is sold or judged, and the keeper is only a person to talk to
        if (!Cooking.enabled()) return
        val level = player.level()
        val life = Days.life(keeper) ?: return
        // A back door is a capital's: a village's baker, or a keeper without a capital's places, is not one
        val capital = (life.places as? CityPlaces)?.capital ?: return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        poked[player.uuid] = level.gameTime
        KitchenDoor.offer(level, player, keeper, life, capital.center.asLong(), stack)
    }

    /**
     * The same click, as the game sends it on: an item used. A dish that was just held out to a keeper (who may be asleep,
     * where the client's own prediction does not take the click) is not also eaten.
     */
    private fun onUseItem(event: PlayerInteractEvent.RightClickItem) {
        val player = event.entity as? ServerPlayer ?: return
        val at = poked[player.uuid] ?: return
        if (player.level().gameTime - at > 1L || event.itemStack.item !is DishItem) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.FAIL
    }

    private fun left(player: UUID) {
        poked.remove(player)
        Pantry.left(player)
    }

    private fun tick(server: MinecraftServer) {
        val t = server.tickCount
        if (t % SWEEP == 0) Owed.sweep(server)
        if (t % SHOWN == 0) Pantry.tick(server)
    }

    /** The line for `cook perf`: the ledger's size, what is owed and in scenes, and what the pantry has done. */
    private fun readout(): String {
        val server = ServerLifecycleHooks.getCurrentServer() ?: return "no server"
        val ledger = Kitchens.get(server.overworld())
        val lots = ledger.all().sumOf { rec -> rec.lots.sumOf { it.size } }
        val slips = ledger.owing.values.sumOf { it.size }
        val marks = ledger.owing.values.sumOf { list -> list.sumOf { it.marks } }
        val slowest = Pantry.slowest / 1000
        return "${ledger.all().size} capitals with a ledger, $lots lots waiting; $slips slips owed ($marks Marks), ${Owed.inScenes()} in scenes; " +
            "pantry served ${Pantry.portions} portions in ${Pantry.meals} meal hours (slowest $slowest us), ${Pantry.showing()} showing"
    }
}
