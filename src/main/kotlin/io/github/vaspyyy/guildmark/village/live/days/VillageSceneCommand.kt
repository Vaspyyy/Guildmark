package io.github.vaspyyy.guildmark.village.live.days

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.townsfolk.CapitalCommand
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownKind
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * The commands to look at a village's day and to play one of its scenes (living-world/villages/design.md part V3), run by operators
 * under `/guildmark village`:
 *
 * - `day` says what the village's day is (a working day, its market day, the rest day), who holds the bell and has the board's note,
 *   who keeps which stall and which guards keep the night; and, of the townsperson you look at (or stand right by), what they are
 *   to do today stretch by stretch with the one they are in marked, and why (a keeper, the bellman, the poster, a carter).
 * - `scene bell` has the holder of the bell strike it now, whatever the hour and whether it has rung today.
 * - `scene board` has the day's poster take a note to the board and pin it now, whether they have pinned today or not.
 * - `scene market` has the keepers go to their stalls and set them out (the rest day's too), and the others about the village come
 *   to the green for a round of it, as on a market day.
 *
 * Each plays on the village you are in (or the nearest within 128 blocks), on the bodies that are about, whatever the switches say.
 */
object VillageSceneCommand {
    val NAMES = listOf("bell", "board", "market")

    private const val NEAREST = 128

    /** At setup: the commands, and the lines this part adds to `village info`. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        VillageInfoCommand.section(VillageInfoCommand.Section { level, rec -> lines(level, rec) })
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village")
                        .then(Commands.literal("day").executes { day(it) })
                        .then(
                            Commands.literal("scene").then(
                                Commands.argument("name", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(NAMES, builder) }
                                    .executes { play(it.source, StringArgumentType.getString(it, "name")) },
                            ),
                        ),
                ),
        )
    }

    // ---- helpers ----

    private fun say(source: CommandSourceStack, c: Component) = source.sendSuccess({ c }, false)

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }

    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GOLD)
    private fun plain(s: String): MutableComponent = Component.literal(s)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun line(): MutableComponent = Component.literal("")

    /** The village you are in, or the nearest within [NEAREST] blocks. */
    private fun target(source: CommandSourceStack): VillageRec? {
        val pos = source.position.let { BlockPos.containing(it) }
        return Villages.at(source.level, pos) ?: Villages.near(source.level, pos, NEAREST).firstOrNull()
    }

    /** [r]'s body, if it is about. */
    private fun body(level: ServerLevel, r: Resident): Townsperson? = r.uuid?.let { level.getEntity(it) as? Townsperson }?.takeIf { it.isAlive }

    /** Who is who of [id], in words. */
    private fun who(level: ServerLevel, id: Int?): String = id?.let { Residents.get(level)[it] }?.let { "${it.name} (${it.role.name.lowercase()}, #${it.id})" } ?: "no one"

    // ---- day ----

    private fun day(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val rec = target(source) ?: return fail(source, "No village within $NEAREST blocks.")
        val centre = BlockPos.of(rec.key)
        val time = level.overworldClockTime
        val today = Days.dayOf(time)
        say(source, line().append(label("${rec.name}: ")).append(plain(kindOf(level, centre, today))))
        say(source, line().append(label("Bell: ")).append(plain(who(level, BellKeeper.holder(level, centre, today)))).append(grey(rung(centre, today))))
        val poster = Posters.pick(level, centre, today)
        val pinned = poster?.let { Residents.get(level)[it] }?.let { it.pinned == VillageBoards.day(level) } == true
        say(source, line().append(label("Board note: ")).append(plain(who(level, poster))).append(grey(if (pinned) ", pinned today" else ", not yet today")))
        val places = Villages.places(level, rec.key)
        if (places == null) {
            say(source, grey("Its places are not counted in this session yet: `village census now` counts them, and then the rest shows here."))
        } else {
            val crew = VillageHands.crew(level, places, today, level.gameTime)
            if (places.stalls.isEmpty()) {
                say(source, line().append(label("Stalls: ")).append(plain("none counted")))
            } else if (crew.keepers.isEmpty()) {
                say(source, line().append(label("Stalls: ")).append(plain("${places.stalls.size}, none kept today" + if (Days.restDay(today)) " (the rest day)" else "")))
            } else {
                say(source, label("Stalls:"))
                for ((id, stall) in crew.keepers.entries.sortedBy { it.value }) {
                    say(source, line().append(plain("  stall ${stall + 1} (${places.stalls[stall].trade.name.lowercase()}): ")).append(plain(who(level, id))))
                }
            }
            val night = crew.night.mapNotNull { Residents.get(level)[it]?.name }
            say(source, line().append(label("Night watch: ")).append(plain(if (night.isEmpty()) "none" else night.joinToString(", "))))
            val carters = crew.carters.mapNotNull { Residents.get(level)[it]?.name }
            say(source, line().append(label("Carters: ")).append(plain(if (carters.isEmpty()) "none" else carters.joinToString(", "))))
        }
        val person = CapitalCommand.lookedAt(source.playerOrException)
        val life = person?.let { Days.life(it) }?.takeIf { it.places.kind == TownKind.VILLAGE }
        if (person == null || life == null) {
            say(source, grey("Look at one of its people (or stand right by them) to see their day."))
            return 1
        }
        say(source, line().append(label("${life.resident.name}: ")).append(plain(why(level, life))))
        for (s in Days.describe(life, time)) say(source, plain("  $s"))
        return 1
    }

    /** What sort of day it is for the village at [centre] on town day [today]. */
    private fun kindOf(level: ServerLevel, centre: BlockPos, today: Long): String = when {
        Days.restDay(today) -> "day $today, the rest day: the service, the market and visits, the stalls shut"
        VillageHands.market(level, centre, today) -> "day $today, its market day: the stalls from eight, the green from half past ten to half past one"
        else -> "day $today, a working day"
    }

    private fun rung(centre: BlockPos, today: Long): String {
        val on = BellKeeper.rungOn(centre)
        return when {
            on == today -> ", rung today"
            on == Long.MIN_VALUE -> ", not rung since the game began"
            else -> ", last rung on day $on"
        }
    }

    /** What [life] is today besides their trade, in words. */
    private fun why(level: ServerLevel, life: Life): String {
        val things = ArrayList<String>()
        if (life.keeper >= 0) things += "keeps stall ${life.keeper + 1}"
        if (life.bellman) things += "holds the bell (rings it at half past five)"
        if (life.poster) things += "takes the board's note (pinned: " + (life.resident.pinned == VillageBoards.day(level)).toString() + ")"
        if (life.carter) things += "carts"
        if (life.resident.role == TownRole.GUARD) things += if (life.nightWatch) "of the night watch" else "of the day watch"
        if (life.market) things += "market day"
        if (life.home == null) things += "no home that stands"
        return if (things.isEmpty()) "nothing out of the way today" else things.joinToString(", ")
    }

    // ---- scenes ----

    private fun play(source: CommandSourceStack, name: String): Int {
        val level = source.level
        if (name !in NAMES) return fail(source, "No scene called $name: one of ${NAMES.joinToString(", ")}.")
        val rec = target(source) ?: return fail(source, "No village within $NEAREST blocks.")
        if (rec.state == Live.LOST) return fail(source, "${rec.name} is lost: no bell stands.")
        val places = Villages.places(level, rec.key) ?: return fail(source, "${rec.name}'s places are not counted in this session yet: `village census now` first.")
        val centre = BlockPos.of(rec.key)
        val today = Days.dayOf(level.overworldClockTime)
        val said = when (name) {
            "bell" -> one(level, BellKeeper.holder(level, centre, today), "strikes the bell now") { p, life -> BellKeeper.errand(p, life, now = true) }
            "board" -> one(level, Posters.pick(level, centre, today), "takes a note to the board") { p, life -> Posters.errand(p, life, now = true) }
            else -> market(level, places, centre, today)
        } ?: return fail(source, "Nobody about for $name: whoever it falls to is not in a loaded chunk, or has nowhere to go.")
        say(source, grey(said))
        return 1
    }

    /** The resident [id]'s body is sent on [what] (made from their [life]); the words, or null. */
    private fun one(level: ServerLevel, id: Int?, what: String, make: (Townsperson, Life) -> Task?): String? {
        val r = id?.let { Residents.get(level)[it] } ?: return null
        val p = body(level, r) ?: return null
        val life = Days.life(p)?.takeIf { it.places.kind == TownKind.VILLAGE } ?: return null
        val task = make(p, life) ?: return null
        p.routine.replace(task)
        return "${r.name} $what."
    }

    /** The keepers to their stalls and the rest of the village's grown people and children to the green. */
    private fun market(level: ServerLevel, places: VillagePlaces, centre: BlockPos, today: Long): String? {
        val keepers = VillageHands.keepersOn(level, places, today)
        var kept = 0
        var green = 0
        for (r in Residents.get(level).byCentre(centre)) {
            if (!r.alive || r.role == TownRole.GUARD) continue
            val p = body(level, r) ?: continue
            val life = Days.life(p)?.takeIf { it.places.kind == TownKind.VILLAGE } ?: continue
            val stall = keepers[r.id]
            val task = if (stall != null) VillageActs.keepNow(p, life, stall) else VillageActs.greenNow(p, life)
            if (task == null) continue
            p.routine.replace(task)
            if (stall != null) kept++ else green++
        }
        if (kept + green == 0) return null
        return "$kept at their stalls, $green on the green."
    }

    // ---- village info ----

    /** The lines this part adds to `village info`. */
    private fun lines(level: ServerLevel, rec: VillageRec): List<Component> {
        if (rec.state == Live.LOST) return listOf()
        val centre = BlockPos.of(rec.key)
        val today = Days.dayOf(level.overworldClockTime)
        val out = ArrayList<Component>()
        out += line().append(label("Today: ")).append(plain(kindOf(level, centre, today)))
        out += line().append(label("Bell: ")).append(plain(who(level, BellKeeper.holder(level, centre, today)))).append(grey(rung(centre, today)))
        return out
    }
}
