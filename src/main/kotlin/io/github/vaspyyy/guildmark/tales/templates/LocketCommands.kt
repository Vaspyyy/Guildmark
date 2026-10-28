package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.faction.Factions
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * The Locket's testing commands (living-world/stories/design.md 12.4), game masters only:
 *
 *  - `/guildmark tale locket`: the locket tale of the capital you stand in, from the record: who is who (the thief too), the three facts, the
 *    words heard, the tools spent and the decoys wrongly asked, the way chosen and where the locket is. It reads; it changes nothing.
 *  - `/guildmark tale scene clasp`, `locket_doorstep`, `locket_return`, `locket_naming`, `locket_lost`: the tale's scenes, played now near you
 *    with whoever is free (the nearest free adult as the owner, the next as the culprit), with a doorstep three blocks before you, or the ground
 *    under your feet as the well, whatever the hour or the switches say. No tale is needed or touched.
 */
internal object LocketCommands {
    /** Called once from `Tales.register`, after the `tale scene` command has its own scenes. */
    fun register() {
        TaleSceneCommand.add("locket_doorstep", ::doorstep)
        TaleSceneCommand.add("locket_return", ::homecoming)
        TaleSceneCommand.add("clasp", ::clasp)
        TaleSceneCommand.add("locket_naming", ::naming)
        TaleSceneCommand.add("locket_lost", ::lost)
        TaleCommand.sub += { tale -> tale.then(Commands.literal("locket").executes { show(it) }) }
    }

    // ---- tale locket ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        TaleEngine.use(level)
        val capital = here.key
        val tales = TaleData.get(level).all().filter { it.template == Locket.id && it.capital == capital }.sortedBy { it.id }
        TaleCommand.say(source, TaleCommand.header("The Locket in ${here.name}"))
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) return TaleCommand.say(source, TaleCommand.plain("No locket tale is on record here."))
        val state = if (t.open) "open, at step ${t.step}" else "ended at ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
        TaleCommand.say(source, line("Owner", name(t, LocketCast.OWNER)))
        TaleCommand.say(source, line("The thief", "${name(t, LocketCast.CULPRIT)}: ${facts(t, LocketCast.CULPRIT)}"))
        for (slot in LocketCast.DECOYS) {
            val asked = if (t.n[LocketSlots.TOOLS] and (if (slot == LocketCast.DECOY_A) LocketSlots.WRONG_A else LocketSlots.WRONG_B) != 0) " (wrongly asked)" else ""
            TaleCommand.say(source, line(slot, "${name(t, slot)}: ${facts(t, slot)}$asked"))
        }
        val words = t.n[LocketSlots.WORDS]
        for ((slot, bit) in LocketCast.WITNESSES) {
            val what = when (bit) {
                LocketSlots.SAW_SIDE -> "the side"
                LocketSlots.SAW_PIECE -> "the piece"
                else -> "the child"
            }
            TaleCommand.say(source, line(slot, "${name(t, slot)} saw $what, ${if (words and bit != 0) "heard" else "not heard yet"}"))
        }
        val tools = t.n[LocketSlots.TOOLS]
        val spent = listOfNotNull(
            "the sill read".takeIf { tools and LocketSlots.READ != 0 },
            "the Order's word".takeIf { tools and LocketSlots.LAW_TOOL != 0 },
            "the quiet word".takeIf { tools and LocketSlots.QUIET_TOOL != 0 },
        )
        TaleCommand.say(source, line("Tools spent", spent.joinToString(", ").ifEmpty { "none" }))
        TaleCommand.say(source, line("Theft noted", if (tools and LocketSlots.NOTED != 0) "yes (the crime reason, -1 for 3 days)" else "not yet"))
        val way = when (t.n[LocketSlots.WAY]) {
            LocketSlots.BREAD -> "the loaves: it goes back at dusk (A)"
            LocketSlots.FOUND -> "\"I'll say it was found\" (B)"
            LocketSlots.QUIET -> "the Quiet Hand's word (B)"
            LocketSlots.STREET -> "before the street, at the well (C)"
            LocketSlots.LAW -> "the Order's word, at the well (C)"
            LocketSlots.SILL -> "put back on the sill by night (C)"
            else -> "none chosen"
        }
        TaleCommand.say(source, line("Way", way))
        val locket = when (t.n[LocketSlots.LOCKET]) {
            LocketSlots.WITH_PLAYER -> "in the keeping of the player who took it"
            LocketSlots.HOME -> "home"
            else -> "with the thief"
        }
        TaleCommand.say(source, line("The locket", locket))
        TaleCommand.say(source, line("The return", if (t.n[LocketSlots.PLAYED] == 1) "played" else "not played"))
        source.player?.let { p ->
            val mine = Faction.entries.filter { Factions.state(p).member(it) }.joinToString(", ") { it.id }.ifEmpty { "none" }
            TaleCommand.say(source, TaleCommand.grey("Your factions: $mine."))
        }
        return 1
    }

    private fun name(t: Tale, slot: String): String {
        val cast = t.cast(slot) ?: return "nobody"
        return cast.name.ifEmpty { "nobody" } + if (cast.gone.isNotEmpty()) " (${cast.gone})" else ""
    }

    /** One suspect's three facts as the record holds them, and whether they fit the thief's. */
    private fun facts(t: Tale, slot: String): String {
        val a = LocketFacts.alibi(t, slot) ?: return "?"
        val fits = if (slot != LocketCast.CULPRIT && LocketFacts.fits(t, slot)) "; fits all three!" else ""
        return "the ${LocketFacts.SIDES.getOrElse(a.side) { "?" }} side, ${a.piece.ifEmpty { "no piece" }}, ${if (a.child) "a child at home" else "no child"}$fits"
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    // ---- tale scene ----

    /** The free adults within 24 blocks of [player], the nearest first. */
    private fun free(level: ServerLevel, player: ServerPlayer): List<Townsperson> =
        Sets.freeAdults(level, player.position(), 24.0, station = true)

    private fun two(level: ServerLevel, player: ServerPlayer): Pair<Townsperson, Townsperson> {
        val free = free(level, player)
        val owner = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val culprit = free.drop(1).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Only one person free within 24 blocks of you: it needs two.")
        return owner to culprit
    }

    private fun step(level: ServerLevel, player: ServerPlayer): LocketScenes.Step =
        LocketScenes.stepBefore(level, player) ?: throw TaleSceneCommand.Unplayable("Nowhere to stand three blocks before you.")

    private fun name(p: Townsperson): String = TaleSceneCommand.name(p)

    private fun doorstep(level: ServerLevel, player: ServerPlayer): String {
        val owner = free(level, player).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val friend = Sets.passerBy(owner)
        return TaleSceneCommand.begin(
            level, LocketScenes.doorstep(owner, friend, step(level, player), hours = null),
            "${name(owner)} walks to a doorstep before you and wrings their hands" +
                if (friend != null) "; ${name(friend)} walks up to comfort them, and is told what the locket held." else " (nobody else free to comfort them).",
        )
    }

    private fun homecoming(level: ServerLevel, player: ServerPlayer): String {
        val (owner, culprit) = two(level, player)
        val watchers = free(level, player).drop(2)
        val stage = LocketScenes.homecoming(owner, culprit, step(level, player), watchers, hours = null, played = BooleanArray(1))
        return TaleSceneCommand.begin(
            level, stage,
            "${name(culprit)} carries the locket to ${name(owner)}'s doorstep before you, gives it back and bows; they embrace" +
                if (watchers.isNotEmpty()) ", and ${minOf(watchers.size, 2)} of the street look on." else ".",
        )
    }

    private fun clasp(level: ServerLevel, player: ServerPlayer): String {
        val free = free(level, player)
        val owner = free.firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        val culprit = free.drop(1).firstOrNull()
        return TaleSceneCommand.begin(
            level, LocketScenes.clasp(owner, culprit, step(level, player), player, hours = null),
            "${name(owner)} takes the locket from your word and holds it to their chest" +
                if (culprit != null) "; ${name(culprit)} watches from the corner and goes home." else " (nobody else free to watch from the corner).",
        )
    }

    private fun naming(level: ServerLevel, player: ServerPlayer): String {
        val (owner, culprit) = two(level, player)
        val watchers = free(level, player).drop(2)
        val stage = LocketScenes.naming(owner, culprit, player.blockPosition(), watchers, hours = null, played = BooleanArray(1))
        return TaleSceneCommand.begin(
            level, stage,
            "${name(culprit)} gives the locket back to ${name(owner)} by you, as if at the well, and is told it was not wanted so" +
                if (watchers.isNotEmpty()) "; ${minOf(watchers.size, 2)} of the street grumble." else ".",
        )
    }

    private fun lost(level: ServerLevel, player: ServerPlayer): String {
        val owner = free(level, player).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")
        return TaleSceneCommand.begin(level, LocketScenes.lost(owner, step(level, player), hours = null), "${name(owner)} stands on a doorstep before you and grieves for the locket.")
    }
}
