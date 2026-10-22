package io.github.vaspyyy.guildmark.village.live

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.block.VillageBoards
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The registry's commands (living-world/villages/design.md section 15.2), run by operators under `/guildmark village`:
 *
 * - `info` says what the census found in the village you stand in (or the nearest within 128 blocks): its bell and board, homes and
 *   beds, wells, benches, lamps, stalls, fields, towers and animals, what the last count cost, and the lines the other parts add;
 * - `census [now]` counts it again (`now` in one go, outside the budget) and says what changed and what it cost;
 * - `list [page]` lists every record: name, state, where, how far;
 * - `clear` forgets the record of the village (it is made afresh the next time its bell is seen);
 * - `show [seconds|off]` draws what the last count found in coloured dust, for the one who asked, so a miss is seen where it stands.
 *
 * Each ends in plain words: what a number is and where it came from, so a village that looks wrong can be followed to the thing
 * the census saw (or did not).
 */
object VillageInfoCommand {
    private const val PAGE = 8
    private const val NEAREST = 128
    private const val SHOWN = 4

    /** More lines for `village info` from the parts that know more of a village: its folk, market, links, dead and chronicle. */
    fun interface Section {
        fun lines(level: ServerLevel, rec: VillageRec): List<Component>
    }

    private val sections = ArrayList<Section>()

    /** Adds [section] (once, at setup) to those that add lines to `village info`, in the order they were added. */
    fun section(section: Section) {
        if (section !in sections) sections += section
    }

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village")
                        .then(Commands.literal("info").executes { info(it) }.then(Commands.literal("here").executes { info(it) }))
                        .then(
                            Commands.literal("census").executes { census(it, false) }
                                .then(Commands.literal("now").executes { census(it, true) }),
                        )
                        .then(
                            Commands.literal("list").executes { list(it, 1) }
                                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { list(it, IntegerArgumentType.getInteger(it, "page")) }),
                        )
                        .then(Commands.literal("clear").executes { clear(it) }.then(Commands.literal("here").executes { clear(it) }))
                        .then(
                            Commands.literal("show").executes { show(it, VillageShow.SECONDS) }
                                .then(Commands.literal("off").executes { showOff(it) })
                                .then(
                                    Commands.argument("seconds", IntegerArgumentType.integer(1, VillageShow.LONGEST))
                                        .executes { show(it, IntegerArgumentType.getInteger(it, "seconds")) },
                                ),
                        ),
                ),
        )
    }

    // ---- info ----

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val on = Villages.enabled()
        if (!on) say(source, line().append(label("Living Villages is off: ")).append(plain("no village is counted or kept up; the records are as they were.")))
        val rec = target(source, quiet = !on)
        if (rec == null) {
            VillageCommand.settings(source)
            return 0
        }
        val places = Villages.places(level, rec.key)
        val found = places?.found
        val bell = BlockPos.of(rec.key)
        say(source, header("${rec.name}: ${rec.size.name.lowercase()}, ${rec.state.name}"))

        // Where it is, and how old the count is
        val where = line().append(label("Bell ")).append(at(bell)).append(plain(", reaches ${rec.radius} blocks; found on day ${rec.found}; "))
        where.append(plain(if (rec.counted < 0L) "not counted yet" else "counted ${ago(level.gameTime - rec.counted)} ago"))
        say(source, where)
        if (rec.state == Live.LOST) {
            val left = rec.lostDay + VillageTick.LOST_DAYS - VillageBoards.day(level)
            say(source, line().append(red("Lost since day ${rec.lostDay}: ")).append(plain("its bell is gone and no board stands; the record is dropped in $left days unless the bell is put back.")))
        }
        say(source, board(rec, found))
        if (rec.state != Live.LOST) {
            // A village is lost when its bell and its board are both gone: one gone and one standing is not yet, and says so
            val t = LevelTerrain(level)
            if (VillageTick.bellOf(t, rec) == VillageTick.Stands.NO && VillageTick.boardOf(t, rec) == VillageTick.Stands.YES) {
                say(source, grey("Its bell is gone, but its board stands, so the village is not lost yet: it is lost when both are gone."))
            }
        }

        // What the record keeps: an entry for every building the census named (a home, a shop, a tower), each by the id it was given
        val kept = rec.homes.filter { !it.gone }
        val gone = rec.homes.size - kept.size
        val homes = kept.count { it.beds.isNotEmpty() }
        say(source, line().append(label("The record: ")).append(plain("${kept.size} buildings ($homes homes with ${kept.sumOf { it.beds.size }} beds)" + if (gone > 0) ", $gone gone" else "")))

        if (found == null) {
            say(source, grey(nothingKept(rec)))
        } else {
            describe(source, rec, places!!, found)
        }
        for (s in sections.toList()) {
            try {
                for (c in s.lines(level, rec)) say(source, c)
            } catch (e: RuntimeException) {
                say(source, grey("(something that adds to this failed: see the log)"))
            }
        }
        VillageCommand.settings(source)
        return 1
    }

    private fun board(rec: VillageRec, found: Found?): MutableComponent {
        val c = line().append(label("Board "))
        if (rec.board < 0L) return c.append(grey("none stands"))
        c.append(at(BlockPos.of(rec.board)))
        if (found?.board != null) {
            c.append(plain(" facing ${found.boardFacing?.serializedName ?: "?"}, ${found.advances} ${if (found.advances == 1) "advance" else "advances"} built"))
        }
        return c
    }

    private fun nothingKept(rec: VillageRec): String = when (rec.state) {
        Live.LIVE -> "Not counted yet this session: the census runs at once (a few ticks). Run the command again."
        Live.SLEEPING -> "Nothing is kept of a village that is asleep: it is counted when a player comes within ${VillageTick.LIVE_NEAR.toInt()} blocks of its bell."
        Live.PAUSED -> "Nothing is kept of a village that is paused (more are awake than the settings allow)."
        Live.LOST -> "Nothing is kept of a lost village."
    }

    /** What the census found, line by line: the things it counted and where they are. */
    private fun describe(source: CommandSourceStack, rec: VillageRec, places: VillagePlaces, found: Found) {
        val homes = found.buildings.filter { it.beds.isNotEmpty() }
        val others = found.buildings.filter { it.beds.isEmpty() && it.kind != "TOWER" }
        say(source, line().append(label("Homes ${homes.size}: ")).append(plain("${homes.sumOf { it.beds.size }} beds" + kinds(homes.map { it.kind }, skip = "HOUSE"))))
        if (others.isNotEmpty()) say(source, line().append(label("Other buildings ${others.size}: ")).append(plain(kinds(others.map { it.kind }).removePrefix(", "))))
        say(source, spots("Wells", found.wells))
        say(source, spots("Benches", found.benches.map { it.pos }))
        say(source, spots("Lamps", found.lamps))
        val stalls = line().append(label("Stalls ${found.stalls.size}"))
        if (found.stalls.isNotEmpty()) stalls.append(plain(": " + found.stalls.joinToString(", ") { name(it.trade.name) })).append(plain(" "))
        for (s in found.stalls.take(SHOWN)) stalls.append(at(s.keeper.pos))
        say(source, stalls)
        say(source, line().append(label("Fields ${found.fields.size}: ")).append(plain("${found.crops} crop cells")).append(plain(if (found.fields.isEmpty()) "" else " ")).append(fieldSpots(found)))
        val towers = line().append(label("Towers ${found.towers.size}: ")).append(plain(if (found.towers.isEmpty()) "no Archer Post stands" else "Archer Posts at "))
        for (p in found.towers.take(SHOWN)) towers.append(at(p)).append(plain(" "))
        say(source, towers)
        if (found.jobs.isNotEmpty()) {
            val by = found.jobs.groupingBy { name(it.m.name) }.eachCount().entries.sortedByDescending { it.value }
            say(source, line().append(label("Work blocks ${found.jobs.size}: ")).append(plain(by.joinToString(", ") { "${it.key} ${it.value}" })))
        }
        say(
            source,
            line().append(label("Animals: ")).append(plain("${found.cattle} cattle in ${found.barns} ${if (found.barns == 1) "barn" else "barns"}, ${found.hens} hens in ${found.coops} ${if (found.coops == 1) "coop" else "coops"}")),
        )
        say(source, line().append(label("The green ")).append(at(found.green)))
        say(source, cost(found.stats, found.partial))
        if (places.radius != rec.radius) say(source, grey("(the places are of radius ${places.radius}; the record's is ${rec.radius})"))
    }

    private fun kinds(all: List<String>, skip: String = ""): String {
        val by = all.filter { it != skip }.groupingBy { name(it) }.eachCount().entries.sortedByDescending { it.value }
        return if (by.isEmpty()) "" else ", " + by.joinToString(", ") { "${it.key} ${it.value}" }
    }

    private fun spots(what: String, ps: List<BlockPos>): MutableComponent {
        val c = line().append(label("$what ${ps.size}")).append(plain(if (ps.isEmpty()) "" else ": "))
        for (p in ps.take(SHOWN)) c.append(at(p)).append(plain(" "))
        if (ps.size > SHOWN) c.append(grey("and ${ps.size - SHOWN} more"))
        return c
    }

    private fun fieldSpots(found: Found): MutableComponent {
        val c = line()
        for (f in found.fields.take(SHOWN)) c.append(at(f.box.center)).append(plain(" "))
        if (found.fields.size > SHOWN) c.append(grey("and ${found.fields.size - SHOWN} more"))
        return c
    }

    /** What the count cost, from the numbers it kept as it ran. */
    private fun cost(s: Stats, partial: Boolean): MutableComponent {
        val c = line().append(label("Last count: "))
        c.append(plain("${s.ticks} ${if (s.ticks == 1) "tick" else "ticks"}, ${ms(s.nanos)} ms in all, worst tick ${ms(s.worstTick)} ms; ${count(s.reads)} blocks read over ${count(s.columns.toLong())} columns"))
        if (s.misses > 0L) c.append(plain(", ${count(s.misses)} reads of chunks that were not loaded (nothing was loaded for them)"))
        if (partial) c.append(grey("; part of the village was not loaded, so what was known of it before is kept"))
        return c
    }

    // ---- census ----

    private fun census(context: CommandContext<CommandSourceStack>, now: Boolean): Int {
        val source = context.source
        val level = source.level
        if (!Villages.enabled()) return fail(source, "Living Villages is off: nothing is counted.")
        val rec = target(source) ?: return 0
        if (rec.state == Live.LOST) return fail(source, "${rec.name} is lost (its bell is gone and no board stands): put the bell back to find it again.")
        if (level.chunkSource.getChunkNow(BlockPos.getX(rec.key) shr 4, BlockPos.getZ(rec.key) shr 4) == null) {
            return fail(source, "The chunk of ${rec.name}'s bell is not loaded. Go nearer: nothing is counted from chunks that are not loaded, and none is loaded for it.")
        }
        val player = source.player
        if (now || player == null) {
            val counted = VillageTick.countNow(level, rec) ?: return fail(source, "${rec.name} could not be counted.")
            for (c in report(counted, true)) say(source, c)
            return 1
        }
        Villages.recount(level, rec.key, player.uuid)
        say(source, line().append(label("Counting ${rec.name} again: ")).append(plain("ahead of the others, a little each tick; what changed is told here when it is done.")))
        return 1
    }

    /** Tells [who] what a count found (when it was asked for with `village census`). */
    internal fun told(level: ServerLevel, who: UUID, counted: VillageTick.Counted) {
        val player = level.server.playerList.getPlayer(who) ?: return
        for (c in report(counted, false)) player.sendSystemMessage(c)
    }

    /** The lines that say what a count changed, what it did to the record, and what it cost. */
    private fun report(c: VillageTick.Counted, now: Boolean): List<Component> {
        val out = ArrayList<Component>()
        val f = c.found
        val first = c.before == null
        val head = line().append(label("Counted ${c.rec.name}" + if (first) ": " else " again: "))
        head.append(plain(if (first) summary(f) else VillageCensus.changes(c.before, f)))
        out.add(head)
        val d = c.diff
        out.add(
            line().append(label("The record: ")).append(
                plain("${d.added} new, ${d.gone} gone, ${d.back} found again; ${d.homes} buildings kept, with ${d.beds} beds" + if (d.bedsBefore != d.beds) " (${d.bedsBefore} before)" else ""),
            ),
        )
        out.add(cost(f.stats, f.partial))
        val s = f.stats
        out.add(
            grey(
                "Where the time went: A, points of interest ${ms(s.slices[0])} ms; B, homes ${ms(s.slices[1])}; C, the columns ${ms(s.slices[2])}; D, livestock ${ms(s.slices[3])}; E, the green ${ms(s.slices[4])}",
            ),
        )
        if (now) {
            out.add(grey("Counted in one go, outside the budget; the census is given ${ms(VillageBudget.CENSUS_NANOS)} ms of a tick and ${VillageBudget.COLUMNS} columns, and takes more ticks."))
        }
        val p = VillageCensus.Perf
        if (p.runs > 0 && p.ticks > 0L) {
            out.add(
                grey(
                    "Since the world opened: ${p.runs} counts over ${p.ticks} ticks of work, ${ms(p.nanos)} ms in all, ${ms(p.nanos / p.ticks)} ms a tick on average, worst tick ${ms(p.worstTick)} ms (the share is ${ms(VillageBudget.CENSUS_NANOS)} ms); the longest count ${ms(p.worstRun)} ms",
                ),
            )
        } else if (p.runs > 0) {
            out.add(grey("Since the world opened: ${p.runs} counts, all in one go (none yet spread over ticks to measure)."))
        }
        return out
    }

    /** What a first count found, by name, as words. */
    private fun summary(f: Found): String {
        val parts = f.counts().entries.filter { it.value > 0 }.map { "${it.value} ${it.key}" }
        return if (parts.isEmpty()) "nothing found (no beds, wells, benches, lamps, stalls, fields or towers)" else parts.joinToString(", ")
    }

    // ---- list ----

    private fun list(context: CommandContext<CommandSourceStack>, page: Int): Int {
        val source = context.source
        val level = source.level
        val all = Villages.all(level)
        val here = level.dimension() == Level.OVERWORLD
        val at = source.position
        val rows = all.map { it to (if (here) Villages.flat(at.x, at.z, it.key) else 0.0) }
            .sortedWith(compareBy<Pair<VillageRec, Double>> { it.second }.thenBy { it.first.name })
        val pages = maxOf(1, (rows.size + PAGE - 1) / PAGE)
        val shown = page.coerceAtMost(pages)
        val by = all.groupingBy { it.state }.eachCount()
        say(
            source,
            header(
                "Villages: ${all.size} (${by[Live.LIVE] ?: 0} live, ${by[Live.SLEEPING] ?: 0} sleeping, ${by[Live.PAUSED] ?: 0} paused, ${by[Live.LOST] ?: 0} lost)" +
                    if (pages > 1) ", page $shown of $pages" else "",
            ),
        )
        if (!Villages.enabled()) say(source, grey("Living Villages is off: these are the records as they were."))
        if (rows.isEmpty()) say(source, grey("None counted yet: a village is counted when a player comes within 64 blocks of its bell."))
        for ((n, row) in rows.drop((shown - 1) * PAGE).take(PAGE).withIndex()) {
            val rec = row.first
            val bell = BlockPos.of(rec.key)
            val c = line().append(grey("${(shown - 1) * PAGE + n + 1}. ")).append(plain("${rec.name} ")).append(grey("${rec.size.name.lowercase()} "))
            c.append(stateName(rec.state)).append(plain(" ")).append(at(bell))
            if (here) c.append(grey(" ${sqrt(row.second).toInt()} blocks"))
            if (rec.state == Live.LOST) c.append(red(" lost since day ${rec.lostDay}"))
            say(source, c)
        }
        if (shown < pages) {
            say(
                source,
                Component.literal("Next page").withStyle {
                    it.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(ClickEvent.RunCommand("/guildmark village list ${shown + 1}"))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("/guildmark village list ${shown + 1}")))
                },
            )
        }
        return all.size
    }

    // ---- clear ----

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val rec = target(source) ?: return 0
        val bell = BlockPos.of(rec.key)
        VillageTick.clear(source.level, rec)
        say(
            source,
            line().append(label("Forgot ${rec.name} ")).append(at(bell)).append(
                plain(
                    if (Villages.enabled()) ": its record is made afresh the next time a player is within 64 blocks of its bell (a few seconds)."
                    else ": Living Villages is off, so it stays forgotten until it is on and its bell is seen.",
                ),
            ),
        )
        return 1
    }

    // ---- show ----

    private fun show(context: CommandContext<CommandSourceStack>, seconds: Int): Int {
        val source = context.source
        val player = source.player ?: return fail(source, "Only a player can be shown it: run it as one.")
        if (!Villages.enabled()) return fail(source, "Living Villages is off: nothing is counted, so there is nothing to show.")
        val rec = target(source) ?: return 0
        if (Villages.places(source.level, rec.key)?.found == null) {
            return fail(source, "${rec.name} has not been counted this session (${rec.state.name}): there is nothing to show yet. `village census` counts it; a village is counted when a player is within ${VillageTick.LIVE_NEAR.toInt()} blocks.")
        }
        VillageShow.start(source.level, player, rec.key, seconds)
        say(source, line().append(label("Showing ${rec.name} for $seconds s: ")).append(plain("the places the last count found, within ${VillageShow.NEAR} blocks of you, in coloured dust that only you see.")))
        for (c in VillageShow.legend()) say(source, c)
        say(source, grey("A place with no dust is a place the count did not find; `village info` lists the same places by position, and `village show off` ends this."))
        return 1
    }

    private fun showOff(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player ?: return fail(source, "Only a player can be shown it: run it as one.")
        say(source, grey(if (VillageShow.stop(player)) "The show is ended." else "No show was on."))
        return 1
    }

    // ---- Shared ----

    /**
     * The village the sender stands in (a lost one too), or the nearest bell within 128 blocks; null, having said so, if there is none
     * (or nothing, if [quiet]).
     */
    private fun target(source: CommandSourceStack, quiet: Boolean = false): VillageRec? {
        val level = source.level
        if (level.dimension() != Level.OVERWORLD) {
            if (!quiet) fail(source, "Villages are counted in the overworld only.")
            return null
        }
        val at = source.position
        var inside: VillageRec? = null
        var insideD = Double.MAX_VALUE
        var nearest: VillageRec? = null
        var nearestD = Double.MAX_VALUE
        for (rec in Villages.all(level)) {
            val d = Villages.flat(at.x, at.z, rec.key)
            val dx = abs(at.x - (BlockPos.getX(rec.key) + 0.5))
            val dz = abs(at.z - (BlockPos.getZ(rec.key) + 0.5))
            if (dx <= rec.radius && dz <= rec.radius && d < insideD) {
                inside = rec
                insideD = d
            }
            if (d < nearestD && d <= NEAREST.toDouble() * NEAREST) {
                nearest = rec
                nearestD = d
            }
        }
        val rec = inside ?: nearest
        if (rec == null && !quiet) {
            fail(source, "No village is counted within $NEAREST blocks of you. A village is counted when a player comes within 64 blocks of its bell; `village list` says which are.")
        }
        return rec
    }

    private fun stateName(state: Live): MutableComponent = Component.literal(state.name).withStyle(
        when (state) {
            Live.LIVE -> ChatFormatting.GREEN
            Live.SLEEPING -> ChatFormatting.AQUA
            Live.PAUSED -> ChatFormatting.YELLOW
            Live.LOST -> ChatFormatting.RED
        },
    )

    /** A kind or a block as words: GUILD_HALL as "guild hall". */
    private fun name(id: String): String = id.lowercase(Locale.ROOT).replace('_', ' ')

    private fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0)

    private fun count(n: Long): String = String.format(Locale.ROOT, "%,d", n)

    private fun ago(ticks: Long): String = when {
        ticks < 0L -> "a while"
        ticks < 1200L -> "${ticks / 20} s"
        else -> "${ticks / 1200} min"
    }

    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun red(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.RED)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)

    /** A position to click to go to: just above the block. */
    private fun at(pos: BlockPos): MutableComponent = Component.literal("[${pos.x} ${pos.y} ${pos.z}]").withStyle {
        it.withColor(ChatFormatting.AQUA)
            .withClickEvent(ClickEvent.RunCommand("/tp @s ${pos.x} ${pos.y + 1} ${pos.z}"))
            .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to go there")))
    }

    private fun say(source: CommandSourceStack, c: Component) = source.sendSuccess({ c }, false)

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
