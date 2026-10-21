package io.github.vaspyyy.guildmark.lamps

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for a capital's street lamps, run standing in the capital. `/guildmark capital lamps` tells of the
 * round of lamps nearest you: who keeps it and where they are, when it begins this evening (or this morning) and how
 * far it has got, and how the capital's lamps stand. `lamps light` and `lamps out` light or put out every lamp of the
 * capital now (the clock takes them up again at its next change); `lamps round` sets the nearest round going now,
 * whatever the hour: lighting its lamps if most are out, putting them out if most are lit, by its lamplighter if they
 * can be had.
 */
object LampCommand {
    /** Has the commands registered with the others. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    /** Adds `lamps` to /guildmark capital (each node needs an operator: a merged node keeps whatever it was first given). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val op = Commands.hasPermission<CommandSourceStack>(Commands.LEVEL_GAMEMASTERS)
        dispatcher.register(
            Commands.literal("guildmark").requires(op).then(
                Commands.literal("capital").requires(op).then(
                    Commands.literal("lamps").requires(op)
                        .executes { status(it) }
                        .then(Commands.literal("light").executes { all(it, lit = true) })
                        .then(Commands.literal("out").executes { all(it, lit = false) })
                        .then(Commands.literal("round").executes { round(it) }),
                ),
            ),
        )
    }

    /** The capital the player stands in and its lamps, or null having said why not. */
    private fun town(context: CommandContext<CommandSourceStack>): LampTown? {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        if (CityPlaces.failed(capital) || StreetLamps.failed(capital)) {
            fail(source, "Its lamps couldn't be worked out (see the log).")
            return null
        }
        val town = StreetLamps.town(level, capital)
        if (town == null) fail(source, "Its lamps are still being worked out: try again in a second.")
        return town
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val town = town(context) ?: return 0
        val source = context.source
        val level = source.level
        val capital = town.capital
        say(source, header("The street lamps of ${VillageNames.of(capital.boardAnchor(town.plan.plan))}"))
        if (!StreetLamps.enabled()) say(source, grey("The street lamps are switched off (setting: Street Lamps): they burn day and night, and older lanterns aren't swapped."))
        val clock = level.overworldClockTime
        val time = StreetLamps.since(clock)
        val stretch = StreetLamps.stretchNow(level, town)
        val litBy = StreetLamps.litBy(town, level.isRaining)
        val weather = when {
            level.isThundering -> " A thunderstorm: every lamp is lit while it lasts."
            level.isRaining -> " Rain: the evening comes on an hour early and the morning an hour late."
            else -> ""
        }
        say(
            source, line().append(label("It's ${StreetLamps.hour(time)}, ")).append(plain(words(stretch)))
                .append(grey(" Lit by ${StreetLamps.hour(litBy)}, put out from ${StreetLamps.hour(StreetLamps.dawnOf(town))} and all out by ${StreetLamps.hour(StreetLamps.outBy(town))}.$weather")),
        )
        // How the capital's lamps stand
        var loaded = 0
        var burning = 0
        var old = 0
        for (pos in town.pos) {
            if (!level.isLoaded(pos)) continue
            loaded++
            val state = level.getBlockState(pos)
            if (StreetLampBlock.burning(state)) burning++
            if (StreetLamps.old(state)) old++
        }
        val city = town.plan.lamps.count { it.city }
        val farms = town.plan.lamps.count { !it.city && it.farm >= 0 }
        val quays = town.plan.lamps.count { it.quay }
        say(
            source, line().append(label("Lamps: ")).append(plain("${town.pos.size}"))
                .append(grey(" ($city in the streets in ${town.plan.rounds.size} rounds, $farms at farm gates, $quays on the quays, ${town.pos.size - city - farms - quays} out on the roads); "))
                .append(plain("$burning of the $loaded loaded are lit"))
                .append(grey(if (old > 0) "; $old older lanterns wait to be swapped for street lamps once nobody is within ${StreetLamps.UNSEEN.toInt()} blocks" else "")),
        )
        // The round nearest
        val near = town.nearest(source.playerOrException.blockPosition()) ?: return say(source, grey("It has no lamps."))
        val run = town.runs[town.runOf[near]]
        val first = town.pos[run.lamps[0]]
        say(
            source, line().append(label("Nearest round: ")).append(plain(describe(town, run)))
                .append(grey(", about ${"%.1f".format(run.ticks / 1000.0)} hours, from ")).append(at(first)),
        )
        say(source, line().append(label("  Kept by: ")).append(keeper(level, town, run)))
        val progress = run.progress(time)
        val doing = when {
            run.force != 0.toByte() -> plain("set going by hand: ${if (run.force == LampTown.LIT) "lighting" else "putting out"} its lamps, $progress of ${run.lamps.size} seen to")
            stretch == StreetLamps.Stretch.DUSK || stretch == StreetLamps.Stretch.DAWN -> {
                val what = if (stretch == StreetLamps.Stretch.DUSK) "lighting" else "putting out"
                when {
                    run.held != null -> plain("$what its lamps with the pole: $progress of ${run.lamps.size} seen to")
                    time < run.start -> plain("begins $what its lamps at ${StreetLamps.hour(run.start)}")
                    else -> plain("$what its lamps by the clock (nobody going round it): $progress of ${run.lamps.size} seen to")
                }
            }
            stretch == StreetLamps.Stretch.NIGHT -> grey("all its lamps lit for the night")
            else -> grey("all its lamps out for the day")
        }
        say(source, line().append(label("  Now: ")).append(doing))
        say(
            source, line()
                .append(button("[Light them all]", "/guildmark capital lamps light", "Light every lamp of the capital now"))
                .append(plain(" "))
                .append(button("[Put them all out]", "/guildmark capital lamps out", "Put out every lamp of the capital now"))
                .append(plain(" "))
                .append(button("[Start this round]", "/guildmark capital lamps round", "Set the round nearest you going now")),
        )
        return 1
    }

    private fun words(stretch: StreetLamps.Stretch): String = when (stretch) {
        StreetLamps.Stretch.AFTERNOON -> "the afternoon: the lamps are out."
        StreetLamps.Stretch.DUSK -> "dusk: the lamplighters are going round."
        StreetLamps.Stretch.NIGHT -> "night: the lamps are lit."
        StreetLamps.Stretch.DAWN -> "dawn: the lamps are being put out."
        StreetLamps.Stretch.MORNING -> "the morning: the lamps are out."
    }

    private fun describe(town: LampTown, run: LampTown.Run): String = when (run.kind) {
        LampTown.Kind.CITY -> "lamplighter's round ${run.round + 1}, ${run.lamps.size} lamps"
        LampTown.Kind.FARM -> "the lamp${if (run.lamps.size > 1) "s" else ""} at a farm's gate (${run.lamps.size})"
        LampTown.Kind.LONE -> if (town.plan.lamps[run.lamps[0]].quay) "a lamp on the harbour's quay or piers" else "a lamp out on the road"
    }

    /** Who keeps [run], and where they are just now. */
    private fun keeper(level: ServerLevel, town: LampTown, run: LampTown.Run): MutableComponent {
        if (run.kind == LampTown.Kind.LONE) return grey("nobody: it keeps its own time")
        val id = run.keeper
        val r = if (id >= 0) Residents.get(level)[id] else null
        if (r == null) {
            return grey(
                if (run.kind == LampTown.Kind.FARM) "nobody (the farm has no farmer just now): they keep their own time"
                else "nobody yet (looked for again as the evening and the morning begin): the clock keeps them",
            )
        }
        val line = plain("${r.name}, ${r.role.id}")
        val p = r.uuid?.let { level.getEntity(it) } as? Townsperson
        if (p == null || !p.isAlive) return line.append(grey(" (not about: their part of the town isn't loaded)"))
        line.append(grey(" at ")).append(at(p.blockPosition()))
        if (run.held != null && p.director === run.held) line.append(grey(", going round it now"))
        else if (p.director != null) line.append(grey(", busy with something else"))
        if (!level.isPositionEntityTicking(p.blockPosition())) line.append(grey(" (too far from any player to go round it)"))
        return line
    }

    private fun all(context: CommandContext<CommandSourceStack>, lit: Boolean): Int {
        val town = town(context) ?: return 0
        val source = context.source
        val n = StreetLamps.setAll(source.level, town, lit)
        return say(
            source, line().append(plain(if (lit) "Lit $n lamps." else "Put out $n lamps."))
                .append(grey(" (Lamps whose land isn't loaded are left as they are; the clock takes them all up again at its next change.)")),
        )
    }

    private fun round(context: CommandContext<CommandSourceStack>): Int {
        val town = town(context) ?: return 0
        val source = context.source
        val level = source.level
        if (!StreetLamps.enabled()) return fail(source, "The street lamps are switched off (setting: Street Lamps): switch them on first.")
        val near = town.nearest(source.playerOrException.blockPosition()) ?: return fail(source, "It has no lamps.")
        val run = town.runs[town.runOf[near]]
        val lighting = StreetLamps.startNow(level, town, run)
        val who = if (run.held != null) {
            val r = Residents.get(level)[run.keeper]
            plain("${r?.name ?: "Its lamplighter"} sets out to ${if (lighting) "light" else "put out"} them")
        } else {
            grey("nobody can be had to go round it, so its lamps ${if (lighting) "catch" else "go out"} one after another by the clock")
        }
        return say(
            source, line().append(label("${describe(town, run).replaceFirstChar { it.uppercase() }}: ")).append(who)
                .append(grey(", from ")).append(at(town.pos[run.lamps[0]])).append(grey(". It holds until the day's next stretch.")),
        )
    }

    /** A position to click to go to. */
    private fun at(pos: BlockPos): MutableComponent = Component.literal("[${pos.x} ${pos.y} ${pos.z}]").withStyle {
        it.withColor(ChatFormatting.AQUA)
            .withClickEvent(ClickEvent.RunCommand("/tp @s ${pos.x} ${pos.y + 1} ${pos.z}"))
            .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to go there")))
    }

    private fun button(text: String, command: String, hover: String): MutableComponent = Component.literal(text).withStyle {
        it.withColor(ChatFormatting.GREEN)
            .withClickEvent(ClickEvent.RunCommand(command))
            .withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
    }

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
