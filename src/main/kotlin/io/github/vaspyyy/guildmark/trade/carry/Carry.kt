package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.trade.Carrier
import io.github.vaspyyy.guildmark.trade.Carriers
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Carrying, the first third of it (living-world/trader/design.md, Part 3; build part D1): the laden look on a trader's back, the
 * Pack Harness and the Drover's Whistle, the pack animal that follows, and the Carriers section at the hall's counter that sells
 * them. The cart and the wagon (parts D2 and D3) add their own beside it. [register] is the one line the trader's setup
 * (`Trade.register`) calls.
 *
 * With `tradeCarry` off ([io.github.vaspyyy.guildmark.trade.TradeConfig.carry]) none of it does anything and nothing is written:
 * no look is drawn, harnessed animals stand as animals do, and the Carriers section is greyed; every container still opens.
 */
object Carry {
    /** This part's attachments. */
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    /**
     * A trader's laden look (see [Laden]): set only while they show a pack, synced to everyone who sees them and to themselves,
     * never saved.
     */
    val LADEN: DeferredHolder<AttachmentType<*>, AttachmentType<LadenState>> = ATTACHMENTS.register("laden") { ->
        AttachmentType.builder { -> LadenState.NONE }.sync(LadenState.STREAM_CODEC).build()
    }

    /**
     * What a pack animal wears (see [PackKit]): set when a Pack Harness is fitted and taken off with it. Saved with the animal (how it
     * stands, its blanket, its bell, who the harness was fitted for, where it was told to wait) and synced to everyone who sees it
     * (how it stands, its blanket, its bell, how full the panniers are and what shows on them). The predicate saves it even when
     * nothing in it differs from a new kit, since an animal wearing a default kit is still a pack animal.
     */
    val PACK_KIT: DeferredHolder<AttachmentType<*>, AttachmentType<PackKit>> = ATTACHMENTS.register("pack_kit") { ->
        AttachmentType.builder { -> PackKit() }.serialize(PackKit.CODEC) { true }.sync(PackKit.STREAM_CODEC).build()
    }

    /**
     * What `trade carry status` asks of a kind of carrier: the lines that say where theirs are and what state they are in. The pack
     * animal adds its own here; the cart and the wagon (parts D2 and D3) add theirs from their own `register`.
     */
    fun interface Report {
        fun lines(player: ServerPlayer): List<Component>
    }

    /**
     * What `trade carry recall` and `trade carry unstick` ask of a kind of carrier: bring everything of [player]'s of that kind that is
     * loaded to them (a cart is unhitched and set down beside them, a wagon is sent to wait), answering how many. The pack animals are
     * brought by [PackFollow] itself.
     */
    fun interface Bring {
        fun bring(player: ServerPlayer): Int
    }

    val reports = CopyOnWriteArrayList<Report>()
    val recalls = CopyOnWriteArrayList<Bring>()
    val unsticks = CopyOnWriteArrayList<Bring>()

    /** Gives [player] the Pack Harness and the Drover's Whistle that come together. */
    fun giveKit(player: ServerPlayer) {
        Trading.give(player, ModItems.PACK_HARNESS.get(), 1)
        Trading.give(player, ModItems.DROVERS_WHISTLE.get(), 1)
    }

    /** Registers this part: its attachments, the count of a trader's load, the pack animal and its harness, and its commands. */
    fun register(modBus: IEventBus) {
        ATTACHMENTS.register(modBus)
        Laden.register()
        PackFollow.register()
        Harness.register()
        Carriers.add(Carrier { player, radius -> PackFollow.holdings(player, radius) })
        CarryCommand.init()
    }
}
