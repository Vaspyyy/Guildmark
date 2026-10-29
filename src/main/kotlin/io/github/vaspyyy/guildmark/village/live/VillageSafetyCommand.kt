package io.github.vaspyyy.guildmark.village.live

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.build.VillageBuilds
import io.github.vaspyyy.guildmark.village.live.folk.VillageKeepsakes
import io.github.vaspyyy.guildmark.village.live.grow.GrowthData
import io.github.vaspyyy.guildmark.village.live.links.VillageWagons
import io.github.vaspyyy.guildmark.village.live.market.Pedlars
import io.github.vaspyyy.guildmark.village.live.trouble.TroubleData
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import java.util.Locale

/**
 * The commands of part V11 (living-world/villages/design.md 13.6 and 15.2), run by operators under `/guildmark village`:
 *
 * - `perf` prints what the villages' work has cost since the world opened or the last `perf reset` ([VillagePerf]): the villages
 *   by state; all their work a tick, its worst tick and the ticks over the 1.5 ms budget; the hour steps (how many, the last,
 *   average and worst, and how many a run); the census's ticks and its worst; the adoptions a minute; the bodies LIVE and the
 *   pedlars alive; how many units the budget put off; each piece's share beside what section 13.2 allows it; and a count of the
 *   records held, store by store. `perf reset` starts the count again.
 */
object VillageSafetyCommand {
    /** At setup (from [Villages.register]). */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village")
                        .then(Commands.literal("perf").executes { perf(it) }.then(Commands.literal("reset").executes { reset(it) })),
                ),
        )
    }

    // ---- village perf ----

    private fun perf(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.server.overworld()
        val w = VillagePerf.whole()
        val since = if (w.since < 0L) "nothing counted yet" else "${w.ticks} ticks, ${secs(w.seconds)} since " + if (resetAt < 0L) "the world opened" else "the last reset"
        say(source, head("What the villages cost: ").append(grey(since)))
        if (!Villages.enabled()) say(source, grey("Living Villages is off (setting: Living Villages): nothing of theirs runs, and nothing is counted."))

        // The villages by state
        val all = Villages.all(level)
        val by = Live.entries.associateWith { s -> all.count { it.state == s } }
        say(
            source,
            label("Villages: ").append(
                plain("${by[Live.LIVE]} live, ${by[Live.SLEEPING]} sleeping, ${by[Live.PAUSED]} paused, ${by[Live.LOST]} lost (${all.size} in all; at most ${maxLive()} awake)"),
            ),
        )

        // All their work together
        val busy = if (w.busy > 0L) w.nanos / w.busy else 0L
        val avg = if (w.ticks > 0L) w.nanos / w.ticks else 0L
        say(
            source,
            label("All their work: ").append(
                plain(
                    "${ms(avg)} ms a tick on average, ${ms(busy)} ms in the ${w.busy} ticks that had any; the worst tick ${ms(w.worst)} ms; " +
                        "${w.over} ticks over the budget of ${ms(VillageBudget.TICK_NANOS)} ms",
                ),
            ),
        )
        say(
            source,
            label("Put off: ").append(
                plain("${VillageBudget.putOff} units of work waited for a later tick (${VillageBudget.stepsPutOff} of them hour steps); nothing is dropped"),
            ),
        )

        // The hour steps
        val h = VillagePerf.hours()
        if (h.steps > 0L) {
            say(
                source,
                label("Hour steps: ").append(
                    plain(
                        "${h.steps} run; the last ${ms(h.last)} ms, on average ${ms(h.nanos / h.steps)} ms, the worst ${ms(h.worst)} ms (section 13.2 reckons 0.1 to 0.25); " +
                            "${h.runs} runs, the last of ${h.lastRun} steps, the most ${h.mostRun} (at most ${VillageBudget.STEPS})",
                    ),
                ),
            )
        } else {
            say(source, label("Hour steps: ").append(grey("none run yet (the villages awake are on the hour, or none is awake)")))
        }

        // The census
        val c = VillageCensus.Perf
        if (c.ticks > 0L) {
            say(
                source,
                label("The census: ").append(
                    plain(
                        "${c.runs} counts over ${c.ticks} ticks, ${ms(c.nanos / c.ticks)} ms a tick on average, the worst tick ${ms(c.worstTick)} ms " +
                            "(its share is ${ms(VillageBudget.CENSUS_NANOS)} ms and ${VillageBudget.COLUMNS} columns); the longest count ${ms(c.worstRun)} ms",
                    ),
                ),
            )
        } else {
            say(source, label("The census: ").append(grey("no count spread over ticks yet")))
        }

        // Adoptions, bodies, pedlars
        val minutes = w.seconds / 60.0
        val rate = if (minutes > 0.0) VillageBudget.tickets / minutes else 0.0
        say(
            source,
            label("Adoptions: ").append(
                plain(
                    "${VillageBudget.tickets} begun, ${String.format(Locale.ROOT, "%.1f", rate)} a minute " +
                        "(at most ${VillageBudget.ADOPT_VILLAGE} a second for a village, ${VillageBudget.ADOPT_ALL} in all)",
                ),
            ),
        )
        val live = all.filter { it.state == Live.LIVE }.mapTo(HashSet()) { it.key }
        say(
            source,
            label("Bodies: ").append(
                plain("${bodies(level, live)} townsfolk of the live villages in the world now; ${Pedlars.alive()} pedlars alive (at most ${Pedlars.MAX})"),
            ),
        )

        // Each piece
        say(source, head("Piece by piece").append(grey(" (a tick it ran, on average; its worst tick; its share of the villages' time; what 13.2 allows)")))
        val total = w.nanos.coerceAtLeast(1L)
        for (line in VillagePerf.lines()) {
            if (line.calls == 0L) continue
            val each = if (line.ticks > 0L) line.nanos / line.ticks else 0L
            val share = line.nanos * 100.0 / total
            say(
                source,
                label(" ${line.piece.words}: ").append(
                    plain("${ms(each)} ms, worst ${ms(line.worst)} ms, ${String.format(Locale.ROOT, "%.0f", share)}% (in ${line.ticks} ticks)"),
                ).append(grey("; allows ${line.piece.allows}")),
            )
        }

        // The records held
        say(source, head("Held").append(grey(" (section 13.4 has what each costs an autosave)")))
        for (line in held(level, all)) say(source, plain(" $line"))
        return 1
    }

    /** When the last `perf reset` was (the game tick), or -1 if none since the world opened. */
    private var resetAt = -1L

    private fun reset(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        VillagePerf.reset()
        VillageBudget.clearReadout()
        VillageCensus.Perf.forget()
        resetAt = source.server.overworld().gameTime
        say(source, head("Village perf: ").append(plain("the count starts again from now.")))
        return 1
    }

    /** As the server stops: the next world's count is since it opened. */
    private fun forget() {
        resetAt = -1L
    }

    /** The bodies in the world now of the residents of the villages [live]. */
    private fun bodies(level: ServerLevel, live: Set<Long>): Int {
        if (live.isEmpty()) return 0
        val residents = Residents.get(level)
        var n = 0
        for (e in level.allEntities) {
            val p = e as? Townsperson ?: continue
            if (p.isRemoved) continue
            val r = residents.of(p) ?: continue
            if (r.capital.asLong() in live) n++
        }
        return n
    }

    /** The records the villages' stores hold, one line a store (counted as they stand; nothing is loaded that is not). */
    private fun held(level: ServerLevel, all: List<VillageRec>): List<String> {
        val out = ArrayList<String>()
        val dead = all.sumOf { it.dead.size }
        val homes = all.sumOf { it.homes.size }
        out.add("villages: ${all.size} records, ${homes} homes, ${dead} of the dead, ${all.sumOf { it.chronicle.size }} chronicle lines")
        val markets = VillageMarkets.get(level).all()
        out.add("village_markets: ${markets.size} markets, ${markets.sumOf { it.mail.size }} letters of mail")
        val keys = all.mapTo(HashSet()) { it.key }
        val folk = Residents.get(level).all().filter { it.capital.asLong() in keys }
        val kept = folk.count { VillageKeepsakes.has(level, it.id) }
        out.add("residents: ${folk.size} of the villages (${folk.count { it.alive }} alive); village_keepsakes: $kept kept villagers")
        out.add("village_builds: ${VillageBuilds.get(level).sites.size} sites going up")
        out.add("village_growth: ${GrowthData.get(level).villages.size}; village_trouble: ${TroubleData.get(level).villages.size}; village_wagons: ${VillageWagons.store(level).books.size} books")
        out.add("in memory: the places of ${Villages.placesHeld()} villages")
        return out
    }

    private fun maxLive(): Int = try {
        GuildmarkConfig.MAX_LIVE_VILLAGES.get()
    } catch (e: IllegalStateException) {
        48
    }

    private fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

    private fun secs(s: Double): String = if (s < 120.0) String.format(Locale.ROOT, "%.0f s", s) else String.format(Locale.ROOT, "%.1f min", s / 60.0)

    // ---- Printing ----

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun head(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GOLD)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
}
