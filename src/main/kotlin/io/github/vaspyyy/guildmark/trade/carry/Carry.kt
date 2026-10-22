package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries

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

    /** Registers this part: its attachments, the count of a trader's load and its commands. */
    fun register(modBus: IEventBus) {
        ATTACHMENTS.register(modBus)
        Laden.register()
        CarryCommand.init()
    }
}
