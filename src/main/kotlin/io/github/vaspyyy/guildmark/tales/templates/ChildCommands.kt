package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Days
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

/**
 * `/guildmark tale child` (living-world/stories/design.md 12.4), game masters only: the child tale of the capital you stand in, from the record,
 * in words: who is out and who waits, the place on the road and the clearing found there, the gate, what has happened (found, walking, at the
 * gate, after dusk, the calling played, the rumour begun, waiting for first light), and the hour against the tale's clock. It reads; it
 * changes nothing.
 */
internal object ChildCommands {
    /** Called once from `TalesT2a.register`. */
    fun register() {
        TaleCommand.sub += { tale -> tale.then(Commands.literal("child").executes { show(it) }) }
    }

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        TaleEngine.use(level)
        val tales = TaleData.get(level).all().filter { it.template == Child.id && it.capital == here.key }.sortedBy { it.id }
        TaleCommand.say(source, TaleCommand.header("Where the Little One Went in ${here.name}"))
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) return TaleCommand.say(source, TaleCommand.plain("No child tale is on record here."))
        val state = if (t.open) "open, at step ${t.step}" else "ended at ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
        TaleCommand.say(source, line("The child", t.cast(Child.CHILD)?.name ?: "(none)"))
        TaleCommand.say(source, line("Asking at the gate", t.cast(Child.PARENT)?.name.orEmpty() + if (Child.flag(t, Child.NEIGHBOUR)) " (a neighbour: the household is not protected)" else ""))
        val place = Child.place(t)
        TaleCommand.say(source, line("On the road", "${place.x} ${place.y} ${place.z}, out of the ${t.s[0].ifBlank { "?" }} gate"))
        val fire = ChildWatch.clearings[t.id]
        TaleCommand.say(source, line("The clearing", fire?.let { "the wardfire at ${it.x} ${it.y} ${it.z}" } ?: "none found (looked for once the land round the place is loaded), so the road's edge"))
        val front = Child.front(t)
        TaleCommand.say(source, line("The gate's front", "${front.x} ${front.y} ${front.z}"))
        val flags = listOf(
            Child.OUT to "out whatever the hour (a forced start)", Child.RUMOUR to "the rumour begun", Child.CALLED to "the calling played",
            Child.FOUND to "found", Child.WALKING to "walked home", Child.ARRIVED to "at the gate", Child.NIGHT to "after dusk",
            Child.DAWN to "the close has come: the watch brings them in at first light",
        ).filter { Child.flag(t, it.first) }.joinToString(", ") { it.second }
        TaleCommand.say(source, line("So far", flags.ifEmpty { "nothing yet" }))
        val body = TaleBodies.body(level, t, Child.CHILD)
        TaleCommand.say(source, line("The child's body", body?.let { "${if (it.tale == t.id) "the tale's" else "their own day's"} at ${it.blockPosition().toShortString()}" } ?: "not about"))
        val now = Child.clock(level)
        TaleCommand.say(
            source,
            line(
                "The clock", "${Days.hourOf(now)}: out at 15:00, missed at 15:30, dusk at 19:00, the close at 02:00; the watch goes out at " +
                    (if (Child.flag(t, Child.FOUND)) "07:00" else "06:00") + " if a player is near",
            ),
        )
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off, so nothing on the road moves."))
        return 1
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))
}
