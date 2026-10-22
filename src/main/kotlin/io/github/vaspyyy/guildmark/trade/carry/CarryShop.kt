package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Serving
import io.github.vaspyyy.guildmark.economy.Stalls
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.CarriersBuyPayload
import io.github.vaspyyy.guildmark.network.CarriersQueryPayload
import io.github.vaspyyy.guildmark.network.CarriersRow
import io.github.vaspyyy.guildmark.network.CarriersViewPayload
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.Rung
import io.github.vaspyyy.guildmark.trade.Rungs
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.Ware
import io.github.vaspyyy.guildmark.trade.Wares
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID

/**
 * The Carriers section of the market hall's counter, as the server runs it (living-world/trader/design.md, Part 3, "A buying scene,
 * not a menu"; build part D1): the two wares this part puts on the counter, the list the screen is sent, and the sale.
 *
 * The screen only ever asks. A press on a row is a request to buy it, and everything that makes it a sale is decided here: the
 * player is standing at a hall counter with its merchant serving, carrying is on, the ware's own gate is open ([Ware.gate]) and the
 * Marks are in the pack. The Marks are taken whole, then [Ware.action] hands the goods over; if that cannot be done the Marks are
 * given back. The counter scene ([Ware.scene]) is played after that, behind the sale, never in front of it: whatever happens in it,
 * the goods are already in the player's hands.
 *
 * With carrying switched off ([TradeConfig.carry]) every ware's gate says so, the rows are greyed and nothing can be bought.
 */
object CarryShop {
    /** The ids of this part's wares: their words are `ware.guildmark.<id>`, `.line` (one short line) and `.tip`. */
    const val HARNESS = "pack_harness"
    const val DONKEY = "pack_donkey"

    /** What they cost, in Marks (design Part 3, "Rules and numbers"). */
    const val HARNESS_PRICE = 12
    const val DONKEY_PRICE = 40

    /** The lang keys of what can stand in the way of a ware, and of why a press was turned down. */
    const val GATE_OFF = "gui.guildmark.trade.carriers.off"
    const val GATE_HAWKER = "gui.guildmark.trade.carriers.gate.hawker"
    private const val SHORT = "gui.guildmark.trade.carriers.short"
    private const val AWAY = "gui.guildmark.trade.carriers.away"
    private const val GONE = "gui.guildmark.trade.carriers.gone"
    private const val FAILED = "gui.guildmark.trade.carriers.failed"

    /** Ticks between two answers to one player's asking for the list, and between two sales to one player (a double click is one press). */
    private const val ASK_GAP = 10L
    private const val BUY_GAP = 8L

    /** Where the player must be to buy: within this many blocks of the hall's merchant, who must be serving. */
    private const val REACH = Stalls.REACH + 1.5

    /** The clink of the coin across the counter (the same the keepers' own deals ring). */
    private val COIN: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.thief.coin"))

    private val asked = HashMap<UUID, Long>()
    private val sold = HashMap<UUID, Long>()

    /**
     * Registers the section's payloads, puts this part's wares on the counter (the harness with its whistle first, then the donkey,
     * which are the first two rows of the hall's list) and clears what is kept of a player when they leave.
     */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToClient(CarriersViewPayload.TYPE, CarriersViewPayload.STREAM_CODEC, CarriersViewPayload::handle)
                .playToServer(CarriersQueryPayload.TYPE, CarriersQueryPayload.STREAM_CODEC, CarriersQueryPayload::handle)
                .playToServer(CarriersBuyPayload.TYPE, CarriersBuyPayload.STREAM_CODEC, CarriersBuyPayload::handle)
        }
        Wares.add(Ware(HARNESS, HARNESS_PRICE, gate = ::gate, action = ::sellHarness, scene = CounterScenes::harness))
        Wares.add(Ware(DONKEY, DONKEY_PRICE, gate = ::gate, action = CounterScenes::buyDonkey, scene = CounterScenes::donkey))
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            asked.remove(event.entity.uuid)
            sold.remove(event.entity.uuid)
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            asked.clear()
            sold.clear()
        }
    }

    // ---- This part's wares ----

    /** What stands between [player] and this part's wares: carrying switched off, or a rung not yet climbed; null if nothing does. */
    private fun gate(player: ServerPlayer): String? = when {
        !TradeConfig.carry() -> GATE_OFF
        !Rungs.reached(player, Rung.HAWKER) -> GATE_HAWKER
        else -> null
    }

    /** The harness with its whistle: put in the pack, or at the player's feet if the pack is full. */
    private fun sellHarness(player: ServerPlayer): Boolean {
        Carry.giveKit(player)
        return true
    }

    /**
     * A new tame donkey of [player]'s standing beside them (nothing on it: it has no chest and wears no harness), or null if there is no
     * room to put one. The donkey of the counter scene ([CounterScenes.buyDonkey]) is brought round by the carter instead; this is where
     * it is put when there is no way to bring it.
     */
    fun donkeyBeside(player: ServerPlayer): AbstractChestedHorse? = Harness.tame(player, EntityTypes.DONKEY, null, EntitySpawnReason.EVENT)

    // ---- The list, and the sale ----

    /** The counter's list as [player] sees it: every ware in order, each with the lang key of what stands in the way (or ""). */
    fun rows(player: ServerPlayer): List<CarriersRow> = Wares.all().map { CarriersRow(it.id, it.price, Wares.missing(player, it) ?: "") }

    /** [player] asks for the list: it is sent unless they asked a moment ago. */
    fun query(player: ServerPlayer) {
        val now = player.level().gameTime
        if (now - (asked[player.uuid] ?: Long.MIN_VALUE / 2) < ASK_GAP) return
        asked[player.uuid] = now
        PacketDistributor.sendToPlayer(player, CarriersViewPayload(rows(player)))
    }

    /** The hall's merchant [player] is standing at the counter of, if there is one: serving, and near enough. */
    fun counter(player: ServerPlayer): Townsperson? {
        val level = player.level() as? ServerLevel ?: return null
        val box = player.boundingBox.inflate(REACH)
        return level.getEntitiesOfClass(Townsperson::class.java, box) { p ->
            p.isAlive && p.role == TownRole.MERCHANT && p.director is Serving && player.distanceToSqr(p) <= REACH * REACH && Shop.kept(p) == Shop.HALL
        }.minByOrNull { player.distanceToSqr(it) }
    }

    /**
     * [player] pressed the row [id]: bought if everything allows it, else turned down with the reason, and either way the list is sent
     * again so the screen shows how things stand. A second press in the same moment is ignored, so a double click buys once.
     */
    fun buy(player: ServerPlayer, id: String) {
        val level = player.level() as? ServerLevel ?: return
        val now = level.gameTime
        val ware = Wares.byId(id)
        if (ware == null) return turnDown(player, id, null, GONE)
        if (now - (sold[player.uuid] ?: Long.MIN_VALUE / 2) < BUY_GAP) return
        val keeper = counter(player) ?: return turnDown(player, id, null, AWAY)
        val why = Wares.missing(player, ware)
        if (why != null) return turnDown(player, id, keeper, why)
        if (TownControl.marks(player) < ware.price) return turnDown(player, id, keeper, SHORT)
        TownControl.pay(player, ware.price)
        val done = try {
            ware.action(player)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("Handing over {} at the hall's counter failed", ware.id, e)
            false
        }
        if (!done) {
            Trading.give(player, ModItems.GUILD_MARK.get(), ware.price)
            return turnDown(player, id, keeper, FAILED)
        }
        sold[player.uuid] = now
        level.playSound(null, keeper.x, keeper.y + 1.0, keeper.z, COIN, SoundSource.NEUTRAL, 0.6f, 1.0f)
        // The scene is walked behind the sale: whatever goes wrong in it, the goods are in the player's hands already
        try {
            ware.scene?.invoke(player, keeper)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("The counter scene for {} failed", ware.id, e)
        }
        Stalls.refresh(player)
        PacketDistributor.sendToPlayer(player, CarriersViewPayload(rows(player), row = id, bought = true))
    }

    /** A press that came to nothing: the merchant shrugs (if there is one), nothing is taken, and the screen is told why. */
    private fun turnDown(player: ServerPlayer, id: String, keeper: Townsperson?, why: String) {
        if (keeper != null) (keeper.director as? Serving)?.gesture(keeper, Activity.SHRUG, null)
        PacketDistributor.sendToPlayer(player, CarriersViewPayload(rows(player), row = id, refused = why))
    }
}
