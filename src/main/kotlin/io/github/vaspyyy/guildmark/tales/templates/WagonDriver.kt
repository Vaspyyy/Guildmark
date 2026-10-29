package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel

/**
 * The waylaid wagon's driver at the gate, in the Fourth Wagon (living-world/stories/design.md 8.3): while a wagon tale names their wagon, the
 * driver's own talk (`GateTalk`, which a director's talk is) has "And the second hand?" before its goodbye: the driver tells of the hand the
 * band took and the price it asks (the grey words from the record), the camp goes on the player's map as the driver's story puts it there,
 * and the word of the camp is told (the meeting is done). At the asking, a player who has not taken the tale up may say "I'll see them home."
 * to the driver, and has. Nothing here changes the driver's own lines or what the caravans keep.
 */
internal object WagonDriver {
    private const val HAND = "tale.wagon.hand"
    private const val YES = "tale.wagon.driver_yes"

    /** The open wagon tale whose waylaid wagon [driver] drove, if Tales is on. */
    private fun tale(level: ServerLevel, driver: Caravans.Driver): Tale? {
        if (!Tales.live) return null
        return TaleEngine.openTales(level).firstOrNull { it.template == Wagon.id && Wagon.caravan(it) >= 0 && Wagon.caravan(it) == driver.caravan }
    }

    /** The choices the driver's talk adds, before its goodbye. */
    fun choices(ask: Ask, driver: Caravans.Driver): List<Choice> {
        val t = tale(ask.level, driver) ?: return listOf()
        val out = ArrayList<Choice>()
        out += Choice(HAND, Component.translatable("talk.guildmark.choice.tale.wagon.second_hand"))
        val status = ask.player?.let { t.part(it.uuid)?.status }
        if (ask.player != null && t.step == Wagon.AT_ASK && status != Part.Status.IN && status != Part.Status.DONE) {
            out += Choice(YES, Component.translatable("talk.guildmark.choice.tale.wagon.driver_yes"))
        }
        return out
    }

    /** What the driver says to one of [choices], or null for a choice that is not this tale's (or a tale that ended meanwhile). */
    fun answer(ask: Ask, driver: Caravans.Driver, choice: String): Pair<Utterance, Boolean>? {
        if (choice != HAND && choice != YES) return null
        val level = ask.level
        val t = tale(level, driver) ?: return null
        val c = TaleEngine.ctx(level, t.capital) ?: return null
        return when (choice) {
            HAND -> {
                ask.player?.let { Caravans.told(it, driver.caravan) }
                val detail = Wagon.priceDetail(level, t)
                Wagon.toldByDriver(c, t, ask.speaker.name.string)
                val part = TaleLines.part(ask, "tale.wagon.driver_hand", detail) ?: Utterance.Part("driver_ran", detail)
                Utterance(listOf(part), toward = Wagon.band(level, t)?.pos) to false
            }
            else -> {
                val player = ask.player ?: return null
                Tales.join(level, t, player)
                val part = TaleLines.part(ask, "tale.wagon.stand", Wagon.stand(level, t)) ?: Utterance.Part("driver_next")
                Utterance(listOf(part), toward = Wagon.band(level, t)?.pos) to false
            }
        }
    }
}
