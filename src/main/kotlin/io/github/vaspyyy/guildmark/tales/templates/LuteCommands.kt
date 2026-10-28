package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent

/**
 * The Silent Lute's testing commands (living-world/stories/design.md 12.4), game masters only:
 *
 *  - `/guildmark tale lute`: the lute tale of the capital you stand in, from the record (the way chosen, the Marks paid, whether the lute is in
 *    her hands, whether the concert has played), and whether the square is silent now and why. It reads; it changes nothing.
 *  - `/guildmark tale scene lute_hush`, `lute_concert`, `lute_giveback`: the tale's three scenes, played now near you with whoever is free
 *    (the nearest bard for the bard, else the nearest free adult), whatever the hour or the switches say. No tale is needed or touched.
 *
 * The silence itself is switched with the tale's own switches (Town Tales, Town Tale Scenes) and cleared with `tale clear`.
 */
internal object LuteCommands {
    /** Called once from `Tales.register`, after the `tale scene` command has its own scenes. */
    fun register() {
        TaleSceneCommand.add("lute_hush", ::hush)
        TaleSceneCommand.add("lute_concert", ::concert)
        TaleSceneCommand.add("lute_giveback", ::giveBack)
        TaleCommand.sub += { tale -> tale.then(Commands.literal("lute").executes { show(it) }) }
        // The silence's memory (what the record said a moment ago) is let go as the server stops
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> LuteSilence.forget() }
    }

    // ---- tale lute ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        TaleEngine.use(level)
        val capital = here.key
        val tales = TaleData.get(level).all().filter { it.template == LuteSilence.ID && it.capital == capital }.sortedBy { it.id }
        TaleCommand.say(source, TaleCommand.header("The Silent Lute in ${here.name}"))
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) {
            TaleCommand.say(source, TaleCommand.plain("No lute tale is on record here."))
        } else {
            val way = when (t.n[LuteSlots.WAY]) {
                LuteSlots.A -> "redeem it at once (A)"
                LuteSlots.B -> "half now, half after (B)"
                LuteSlots.C -> "lent for a night on a word (C)"
                else -> "none chosen"
            }
            val state = if (t.open) "open, at step ${t.step}" else "ended at ${t.ending} on day ${t.ended}"
            TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
            TaleCommand.say(source, line("Way", way))
            TaleCommand.say(source, line("Marks", "${t.n[LuteSlots.PAID]} of ${t.n[LuteSlots.SUM]} paid"))
            TaleCommand.say(source, line("The lute", if (t.n[LuteSlots.BACK] == 1) "in the bard's hands" else "not in the bard's hands"))
            TaleCommand.say(source, line("The concert", if (t.n[LuteSlots.PLAYED] == 1) "played" else "not played"))
        }
        val today = TaleEngine.today(level, capital)
        val silent = LuteSilence.silentIn(level, capital)
        TaleCommand.say(source, line("The square", if (silent) "silent: ${why(level, tales, today)}" else "plays as it always does"))
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off, so the bards play whatever the record says."))
        return 1
    }

    /** Why the square is silent on market [today], from the first tale that holds it. */
    private fun why(level: ServerLevel, tales: List<Tale>, today: Long): String {
        val t = tales.firstOrNull { LuteSilence.silentBy(level, it, today) } ?: return "no tale holds it"
        return when {
            t.open && t.n[LuteSlots.BACK] == 0 -> "the open tale #${t.id} has the lute out of the bard's hands"
            t.open -> "the hush before the concert of the open tale #${t.id} (18:00 to 20:00, until it has played)"
            t.ending == Ending.W -> "the lute was sold (tale #${t.id}), until day ${LuteSilence.until(t)}"
            else -> "the lute went back to its keeper (tale #${t.id}), until day ${LuteSilence.until(t)}"
        }
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    // ---- tale scene lute_* ----

    /** The free adults within 24 blocks of [player], bards first, then the nearest. */
    private fun free(level: ServerLevel, player: ServerPlayer): List<Townsperson> =
        Sets.freeAdults(level, player.position(), 24.0, station = true).sortedBy { if (it.role == TownRole.BARD) 0 else 1 }

    private fun name(p: Townsperson): String = TaleSceneCommand.name(p)

    private fun hush(level: ServerLevel, player: ServerPlayer): String {
        val bard = free(level, player).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val friend = Sets.passerBy(bard)
        return TaleSceneCommand.begin(
            level, LuteScenes.hush(bard, friend, hours = null),
            "${name(bard)} sits out the songs, sighing" + if (friend != null) "; ${name(friend)} walks up to comfort them." else " (nobody else free to comfort them).",
        )
    }

    private fun concert(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player)
        val bard = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val listeners = free.drop(1).take(4)
        val stage = LuteScenes.concert(bard, listeners, player.blockPosition(), hours = null, played = BooleanArray(1))
        return TaleSceneCommand.begin(
            level, stage,
            "${name(bard)} walks to where you stand, says their line and plays the lute for a song, and bows" +
                if (listeners.isNotEmpty()) "; ${listeners.size} walk to a ring before them and clap." else " (nobody else free to listen; anyone free within 16 blocks may join on their own).",
        )
    }

    private fun giveBack(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player)
        val bard = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val keeper = free.drop(1).minByOrNull { it.distanceToSqr(bard) } ?: throw TaleSceneCommand.Unplayable("Only one person free within 24 blocks of you: it needs two.")
        return TaleSceneCommand.begin(
            level, LuteScenes.giveBack(bard, keeper, hours = null),
            "${name(bard)} walks the lute to ${name(keeper)}, who takes it and says their word.",
        )
    }
}
