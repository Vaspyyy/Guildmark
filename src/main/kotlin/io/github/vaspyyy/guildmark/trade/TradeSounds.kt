package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * The sound events of the trade (living-world/trader/design.md section 10, Sounds): 37 events `guildmark:trade.<name>`,
 * made by `art/audio/make_trade_sfx.py` (stream X5) and listed in `sounds.json` through its fragment
 * `art/audio/trade_sounds.json` (merged by `art/lang/merge_lang.py`). An event with no entry in `sounds.json` is silent,
 * so every part can name and play them from the first commit and they sound once X5 lands. [NAMES] is the contract:
 * X5 adds a line to it only if a sound is added.
 */
object TradeSounds {
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)

    /** The 37 events, in the order of section 10's table: the board and the Tally, a haul delivered, carrying, the Hunt,
     *  the ladder, the town answering. */
    val NAMES: List<String> = listOf(
        "trade.board_stamp", "trade.seal_press", "trade.ticket_peel", "trade.hourglass", "trade.tally_open",
        "trade.tally_page", "trade.tally_notch", "trade.report_file",
        "trade.knock", "trade.deliver",
        "trade.bell_step", "trade.bell_trot", "trade.harness_buckle", "trade.whistle_come", "trade.whistle_stay",
        "trade.pack_clink", "trade.cart_creak", "trade.shafts_lift", "trade.shafts_drop", "trade.canvas_flap",
        "trade.tailboard", "trade.rein_slack", "trade.wagon_brake",
        "trade.hunt_horn", "trade.hunt_shout", "trade.hunt_win", "trade.sack_lift", "trade.guard_step",
        "trade.warrant_seal", "trade.store_key", "trade.scale_settle", "trade.hoist_creak", "trade.strongbox",
        "trade.beads", "trade.house_bell", "trade.carter_hail",
        "trade.cheer",
    )

    private val EVENTS: Map<String, DeferredHolder<SoundEvent, SoundEvent>> = NAMES.associateWith { name ->
        SOUNDS.register(name) { -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)) }
    }

    /** The event named [name], one of [NAMES] (`trade.board_stamp`, `trade.hunt_horn`...). */
    fun get(name: String): SoundEvent = EVENTS.getValue(name).get()

    fun register(modBus: IEventBus) {
        SOUNDS.register(modBus)
    }
}
