package io.github.vaspyyy.guildmark.village.live.trouble

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * The commands for a village's trouble (living-world/villages/design.md 15.2, part V10), run by operators under `/guildmark village`
 * on the village you stand in (or the nearest within [NEAREST] blocks):
 *
 * - `watch` says what its watch and shelter are doing: the guards wanted and kept (and where each is), those on the way, the
 *   shelter under way (how many behind how many doors, how many bolted), a raid or a Bad Omen raid at it, the folk a raid holds,
 *   and the last things that happened. `watch call siege` and `watch call raid` call the shelter now, from the side you stand on
 *   (the bell rung hard, the folk to their doors, the guards to the wall), as a siege's or a raid's horn does; with no monster near it
 *   ends by itself, as a real one does, half a minute on and ten seconds of quiet later. `watch end [held|beaten|lost]` gives the
 *   all-clear now, as the trouble ended that way (quiet if not said).
 * - `dead` reads its dead (all 24 that are kept), its memorial stone and candles, and its chronicle; `dead add <name>` enters
 *   someone among them (no cause), to wait for a candle at the next dawn.
 * - `mend` says what a lost siege left to mend and who is at it; `mend now` sets the mend out today.
 * - `golem` tops up its golems now, whatever the hour, where it can (in sight or not).
 */
object VillageTroubleCommand {
    private const val NEAREST = 128

    /** At setup (from [Trouble.register]). */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village")
                        .then(
                            Commands.literal("watch")
                                .executes { watch(it) }
                                .then(
                                    Commands.literal("call")
                                        .then(Commands.literal("siege").executes { call(it, Shelter.Why.SIEGE) })
                                        .then(Commands.literal("raid").executes { call(it, Shelter.Why.RAID) }),
                                )
                                .then(
                                    Commands.literal("end")
                                        .executes { end(it, Shelter.Outcome.QUIET) }
                                        .then(Commands.literal("held").executes { end(it, Shelter.Outcome.HELD) })
                                        .then(Commands.literal("beaten").executes { end(it, Shelter.Outcome.BEATEN) })
                                        .then(Commands.literal("lost").executes { end(it, Shelter.Outcome.LOST) }),
                                ),
                        )
                        .then(
                            Commands.literal("dead")
                                .executes { dead(it) }
                                .then(
                                    Commands.literal("add").then(
                                        Commands.argument("name", StringArgumentType.greedyString()).executes { add(it, StringArgumentType.getString(it, "name")) },
                                    ),
                                ),
                        )
                        .then(Commands.literal("mend").executes { mend(it) }.then(Commands.literal("now").executes { mendNow(it) }))
                        .then(Commands.literal("golem").executes { golem(it) }),
                ),
        )
    }

    // ---- The village you mean ----

    /** The village you stand in or the nearest; null having said why not. With [live], one whose trouble is worked out now. */
    private fun target(context: CommandContext<CommandSourceStack>, live: Boolean): VillageRec? {
        val source = context.source
        val level = source.level
        if (!Villages.enabled()) {
            fail(source, "Living Villages is off (setting: Living Villages): no village's trouble is kept.")
            return null
        }
        val pos = BlockPos.containing(source.position)
        val rec = Villages.at(level, pos) ?: Villages.near(level, pos, NEAREST).firstOrNull()
        if (rec == null) {
            fail(source, "No village within $NEAREST blocks.")
            return null
        }
        if (live && !Trouble.on()) {
            fail(source, "Shelter and the Watch is off (setting: villageTrouble): sieges and raids at ${rec.name} go as they did before.")
            return null
        }
        if (live && rec.state != Live.LIVE) {
            fail(source, "${rec.name} is ${rec.state.name.lowercase()}: go nearer (a player within 96 blocks of its bell keeps it live).")
            return null
        }
        return rec
    }

    // ---- watch ----

    private fun watch(context: CommandContext<CommandSourceStack>): Int {
        val rec = target(context, live = false) ?: return 0
        val source = context.source
        val level = source.level
        say(source, head("${rec.name}: the watch and the shelter"))
        if (!Trouble.on()) say(source, grey("Shelter and the Watch (villageTrouble) is off: nothing below is worked out, and sieges and raids go as they did before."))
        for (line in Watch.describe(level, rec)) say(source, plain(line))
        say(source, label("Shelter: ").append(plain(Shelter.describe(rec.key, level.gameTime).joinToString("; "))))
        for (line in RaidWatch.describe(level, rec.key)) say(source, label("Raid: ").append(plain(line)))
        val log = Trouble.logged(rec.key)
        if (log.isNotEmpty()) {
            say(source, label("Lately (the newest last):"))
            for (line in log) say(source, grey("  $line"))
        }
        return 1
    }

    private fun call(context: CommandContext<CommandSourceStack>, why: Shelter.Why): Int {
        val rec = target(context, live = true) ?: return 0
        val source = context.source
        val level = source.level
        val bell = BlockPos.of(rec.key)
        // From the side you stand on; from the east if you stand at the bell
        val here = BlockPos.containing(source.position)
        val dx = (here.x - bell.x).toDouble()
        val dz = (here.z - bell.z).toDouble()
        val side = if (dx * dx + dz * dz < 64.0) Direction.EAST else Direction.getApproximateNearest(dx, 0.0, dz).takeIf { it.axis.isHorizontal } ?: Direction.EAST
        val from = bell.relative(side, rec.radius + 18)
        Shelter.call(level, rec.key, bell, rec.radius + 8, why, from)
        Trouble.log(level, rec, "a ${why.name.lowercase()} called by command, from the ${side.serializedName}")
        val c = Shelter.of(rec.key)
        say(source, plain("${rec.name}: sheltering from a ${why.name.lowercase()} from the ${side.serializedName}, ${c?.folk?.size ?: 0} going indoors. With no monster near, the all-clear comes by itself in about 40 seconds; `village watch end` gives it now."))
        return 1
    }

    private fun end(context: CommandContext<CommandSourceStack>, how: Shelter.Outcome): Int {
        val rec = target(context, live = false) ?: return 0
        val source = context.source
        if (Shelter.of(rec.key)?.ending != null || Shelter.of(rec.key) == null) return fail(source, "${rec.name} is not sheltering.")
        Shelter.end(source.level, rec.key, how)
        Trouble.log(source.level, rec, "the all-clear (${how.name.lowercase()}) by command")
        say(source, plain("${rec.name}: the all-clear (${how.name.lowercase()}); the folk come out over the next ten seconds."))
        return 1
    }

    // ---- dead ----

    private fun dead(context: CommandContext<CommandSourceStack>): Int {
        val rec = target(context, live = false) ?: return 0
        val source = context.source
        val level = source.level
        say(source, head("${rec.name}: its dead"))
        if (rec.dead.isEmpty()) say(source, grey("Nobody has died here yet."))
        for (d in rec.dead.asReversed()) say(source, Component.literal("  ").append(Deaths.line(d)))
        for (line in Memorial.describe(level, rec)) say(source, plain(line))
        if (rec.chronicle.isNotEmpty()) {
            say(source, label("Chronicle (the newest first):"))
            for (c in rec.chronicle.asReversed()) say(source, Component.literal("  ").append(Deaths.chron(c)))
        }
        return 1
    }

    private fun add(context: CommandContext<CommandSourceStack>, name: String): Int {
        val rec = target(context, live = false) ?: return 0
        val source = context.source
        if (!Trouble.on()) return fail(source, "Shelter and the Watch (villageTrouble) is off: nobody new is entered among the dead.")
        val clean = name.trim().take(48)
        if (clean.isEmpty()) return fail(source, "Give a name.")
        Deaths.bury(source.level, rec, clean, VillageBoards.day(source.level), "", -1, -1)
        say(source, plain("$clean is entered among ${rec.name}'s dead; a candle is laid for them at the next dawn (`/time set 0` is six in the morning)."))
        return 1
    }

    // ---- mend ----

    private fun mend(context: CommandContext<CommandSourceStack>): Int {
        val rec = target(context, live = false) ?: return 0
        val source = context.source
        say(source, head("${rec.name}: the mend"))
        for (line in Mend.describe(source.level, rec)) say(source, plain(line))
        return 1
    }

    private fun mendNow(context: CommandContext<CommandSourceStack>): Int {
        val rec = target(context, live = true) ?: return 0
        val source = context.source
        if (!Mend.now(source.level, rec)) return fail(source, "${rec.name} has nothing to mend (a lost siege leaves the damage to mend).")
        say(source, plain("${rec.name}: the mend sets out today (from seven in the morning to a quarter to six; by the record when nobody is near)."))
        return 1
    }

    // ---- golem ----

    private fun golem(context: CommandContext<CommandSourceStack>): Int {
        val rec = target(context, live = true) ?: return 0
        val source = context.source
        say(source, plain("${rec.name}: " + Golems.now(source.level, rec)))
        return 1
    }

    // ---- Saying it ----

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }

    private fun head(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GOLD)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
}
