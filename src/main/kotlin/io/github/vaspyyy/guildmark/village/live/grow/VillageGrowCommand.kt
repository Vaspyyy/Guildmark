package io.github.vaspyyy.guildmark.village.live.grow

import com.mojang.brigadier.CommandDispatcher
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark village grow` (operators), on the village you are in or the nearest within 128 blocks
 * (living-world/villages/design.md 5.5 and 5.6, part V13):
 *
 * - `grow` says how its growth stands: its people against its beds and the cap, the waits for newcomers and their days, the
 *   household on its way or leaving, the babies and the children to grow up, what plays now, why nothing more came of the last
 *   close, and the last of what happened.
 * - `grow birth` has a child born now in the home with two grown-ups and a free bed that has waited longest (never past the beds
 *   or the cap); it is carried out to be seen the next morning, as any is.
 * - `grow age` has every child of the village grow up now (each as soon as nobody can see them).
 * - `grow newcomer` brings a household now to the empty home with most beds: walking in off the road if you are near the door,
 *   else by the record (never past the beds or the cap).
 * - `grow leave` sends the newest household with no duty off now, packing its cart if you are near (never below two households
 *   and three people).
 *
 * The commands work whatever the hour, the times and the odds, but not on a village whose folk are given back or that is lost,
 * and never past the beds or the cap.
 */
object VillageGrowCommand {
    private const val NEAREST = 128

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village").then(
                        Commands.literal("grow")
                            .executes { status(it.source) }
                            .then(Commands.literal("birth").executes { birth(it.source) })
                            .then(Commands.literal("age").executes { age(it.source) })
                            .then(Commands.literal("newcomer").executes { newcomer(it.source) })
                            .then(Commands.literal("leave").executes { leave(it.source) }),
                    ),
                ),
        )
    }

    // ---- helpers ----

    private fun say(source: CommandSourceStack, text: String, style: ChatFormatting = ChatFormatting.WHITE) =
        source.sendSuccess({ Component.literal(text).withStyle(style) }, false)

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }

    private fun target(source: CommandSourceStack): VillageRec? {
        val pos = BlockPos.containing(source.position)
        return Villages.at(source.level, pos) ?: Villages.near(source.level, pos, NEAREST).firstOrNull()
    }

    /** The village to act on, or a failure said and null: none near, its folk given back, or lost. */
    private fun acting(source: CommandSourceStack): VillageRec? {
        val rec = target(source) ?: return null.also { fail(source, "No village here, nor within $NEAREST blocks.") }
        if (!VillageAdopt.adopting(rec)) return null.also { fail(source, "${name(rec)}'s folk are not taken in (Living Villages or villageFolk off, or given back): it does not grow.") }
        if (rec.state == io.github.vaspyyy.guildmark.village.live.Live.LOST) return null.also { fail(source, "${name(rec)} is lost: it does not grow.") }
        return rec
    }

    private fun name(rec: VillageRec): String = rec.name.ifEmpty { "The village at ${BlockPos.of(rec.key).toShortString()}" }

    // ---- grow ----

    private fun status(source: CommandSourceStack): Int {
        val level = source.level
        val rec = target(source) ?: return fail(source, "No village here, nor within $NEAREST blocks.")
        val g = GrowthData.get(level).find(rec.key)
        val today = VillageBoards.day(level)
        say(source, "${name(rec)}: growth", ChatFormatting.GOLD)
        say(source, VillageGrowth.summary(level, rec, g))
        if (g == null) {
            say(source, "  nothing has grown here yet (its first close is at two in the morning)", ChatFormatting.GRAY)
            return 1
        }
        val s = g.state
        say(source, "  last close: day ${s.closed}; last birth ${day(s.born)}, last newcomers ${day(s.arrived)}, last to leave ${day(s.left)}", ChatFormatting.GRAY)
        if (s.why.isNotEmpty()) say(source, "  at the last close: ${s.why}", ChatFormatting.GRAY)
        for (d in s.due.sortedBy { it.at }) {
            val what = if (d.cause == GrowthRules.DEATH) "a death" else "home ${d.home} empty"
            say(source, "  waiting: $what since day ${d.from}, a newcomer due on day ${d.at}${if (d.at <= today + 1) " (now, once there is room and the times allow)" else ""}", ChatFormatting.GRAY)
        }
        if (s.zero >= 0) say(source, "  nobody has lived here since day ${s.zero}", ChatFormatting.GRAY)
        if (s.base >= 0) say(source, "  in a hard run since there were ${s.base} people", ChatFormatting.GRAY)
        val residents = Residents.get(level)
        for (b in g.babies) {
            val r = residents[b.id] ?: continue
            say(source, "  baby: ${r.name}, born day ${b.born} in home ${b.home}${if (b.shown) ", shown" else ", not shown yet"}; about from day ${b.born + GrowthRules.BABY_DAYS}", ChatFormatting.GRAY)
        }
        val children = residents.byCentre(BlockPos.of(rec.key)).filter { it.alive && it.role == TownRole.CHILD }
        for (c in children) {
            val due = c.since + GrowthRules.CHILD_DAYS
            val state = if (c.id in g.aging) "grows up as soon as nobody can see them" else "grows up on day $due"
            say(source, "  child: ${c.name} (#${c.id}), here since day ${c.since}: $state", ChatFormatting.GRAY)
        }
        if (g.make.isNotEmpty()) say(source, "  bodies to be made at home out of sight: ${g.make.joinToString { "#${it.id} from day ${it.from}" }}", ChatFormatting.GRAY)
        for (line in GrowScenes.describe(rec.key)) say(source, "  now: $line", ChatFormatting.AQUA)
        for (line in g.log.takeLast(6)) say(source, "  day ${line.day}: ${line.text}", ChatFormatting.DARK_GRAY)
        return 1
    }

    private fun day(d: Long): String = if (d < 0) "never" else "day $d"

    private fun birth(source: CommandSourceStack): Int {
        val level = source.level
        val rec = acting(source) ?: return 0
        val g = GrowthData.get(level).of(rec.key)
        val (r, why) = Births.force(level, rec, g)
        if (r == null) return fail(source, "No child is born in ${name(rec)}: $why.")
        say(source, "${r.given} is born to the ${r.family} household ($why): carried out for the lane to see the next morning, about the village from day ${r.since + GrowthRules.BABY_DAYS}.")
        return 1
    }

    private fun age(source: CommandSourceStack): Int {
        val level = source.level
        val rec = acting(source) ?: return 0
        val g = GrowthData.get(level).of(rec.key)
        val children = Residents.get(level).byCentre(BlockPos.of(rec.key)).filter { it.alive && it.role == TownRole.CHILD }
        if (children.isEmpty()) return fail(source, "${name(rec)} has no children.")
        var now = 0
        val today = VillageBoards.day(level)
        val clock = Math.floorMod(level.overworldClockTime, 24000L)
        val places = Villages.places(level, rec.key)
        for (c in children) {
            val body = c.uuid?.let { level.getEntity(it) as? Townsperson }?.takeIf { it.isAlive }
            if (places != null && VillageAdopt.swapping(rec.key) == 0 && !GrowScenes.holds(c.id) &&
                (body == null || !GrowScenes.has(body) && Comings.unseen(level, body.blockPosition()))
            ) {
                Births.grow(level, rec, places, g, c, body, today, clock)
                g.aging.remove(c.id)
                now++
            } else if (c.id !in g.aging) {
                g.aging += c.id
            }
        }
        GrowthData.get(level).changed()
        val later = children.size - now
        say(source, "${children.size} child${if (children.size == 1) "" else "ren"} of ${name(rec)} grow${if (children.size == 1) "s" else ""} up: $now now" +
            (if (later > 0) ", $later as soon as nobody can see them (look away, or step back)" else "") + ".")
        return 1
    }

    private fun newcomer(source: CommandSourceStack): Int {
        val level = source.level
        val rec = acting(source) ?: return 0
        val g = GrowthData.get(level).of(rec.key)
        if (g.vanilla < 0) countNow(level, rec, g)
        val (came, why) = Newcomers.force(level, rec, g)
        if (came.isEmpty()) return fail(source, "No household comes to ${name(rec)}: $why.")
        say(source, "The ${came.first().family} household comes to ${name(rec)} ($why): ${came.joinToString { "${it.given} (${it.role.id})" }}.")
        return 1
    }

    private fun leave(source: CommandSourceStack): Int {
        val level = source.level
        val rec = acting(source) ?: return 0
        val g = GrowthData.get(level).of(rec.key)
        val (gone, why) = Newcomers.forceLeave(level, rec, g)
        if (gone.isEmpty()) return fail(source, "Nobody leaves ${name(rec)}: $why.")
        say(source, "The ${gone.first().family} household leaves ${name(rec)} ($why): ${gone.joinToString { it.given }}.")
        return 1
    }

    /** The village's vanilla villagers counted now if they have never been (they are about, as you are there). */
    private fun countNow(level: ServerLevel, rec: VillageRec, g: Grow) {
        g.vanilla = VillageAdopt.villagersOf(level, rec).size
        GrowthData.get(level).changed()
    }
}
