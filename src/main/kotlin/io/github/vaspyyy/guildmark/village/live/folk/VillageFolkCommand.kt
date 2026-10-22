package io.github.vaspyyy.guildmark.village.live.folk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.math.abs

/**
 * The folk's commands (living-world/villages/design.md section 15.2), run by operators under `/guildmark village`:
 *
 * - `folk [page]` lists the village's people (their trade, home, bed, when they were taken in, and whether their body is about) and the
 *   vanilla villagers left, each with the reason it is left;
 * - `adopt [force]` runs the pass that takes villagers in, now, in the village you stand in (or the nearest within 128 blocks): the
 *   hour and the eyes are as they are at night unless `force` ignores them, and a village that was released takes folk in again;
 * - `release [here]` gives the village's townsfolk back as villagers at once and takes in nobody more until `adopt`.
 *
 * Each says what it did in numbers of what really happened, and why a villager was left alone.
 */
object VillageFolkCommand {
    private const val PAGE = 8
    private const val NEAREST = 128

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
                            Commands.literal("folk").executes { folk(it, 1) }
                                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { folk(it, IntegerArgumentType.getInteger(it, "page")) }),
                        )
                        .then(
                            Commands.literal("adopt").executes { adopt(it, false) }
                                .then(Commands.literal("force").executes { adopt(it, true) }),
                        )
                        .then(Commands.literal("release").executes { release(it) }.then(Commands.literal("here").executes { release(it) })),
                ),
        )
    }

    // ---- folk ----

    private fun folk(context: CommandContext<CommandSourceStack>, page: Int): Int {
        val source = context.source
        val level = source.level
        val rec = target(source) ?: return 0
        val bell = BlockPos.of(rec.key)
        val day = io.github.vaspyyy.guildmark.block.VillageBoards.day(level)
        val mode = when {
            !Villages.enabled() -> "Living Villages is off: the folk are given back at night"
            !VillageAdopt.folkOn() -> "villageFolk is off: the folk are given back at night"
            !rec.folk -> "released: nobody is taken in until `village adopt`"
            rec.state != Live.LIVE -> "folk are taken in when the village is LIVE (it is ${rec.state.name})"
            else -> "folk are taken in at night, when nobody is near or can see"
        }
        val lines = ArrayList<Component>()
        val residents = Residents.get(level).byCentre(bell).sortedWith(compareBy({ !it.alive }, { it.id }))
        for (r in residents) {
            val c = line().append(label("#${r.id} ")).append(plain(r.name)).append(plain(", ${trade(r.craft, r.role.id)}"))
            if (!r.alive) {
                c.append(red(if (r.died >= 0) " (died on day ${r.died})" else " (away)"))
                lines.add(c)
                continue
            }
            c.append(plain(if (r.home >= 0) ", home ${r.home}" else ", no home"))
            val bed = r.bed
            if (bed != null) c.append(plain(", bed ")).append(at(bed)) else c.append(plain(", no bed"))
            val keep = VillageKeepsakes.get(level, r.id)
            if (keep != null) {
                val pending = keep.getBooleanOr(VillageKeepsakes.PENDING, false)
                c.append(plain(if (pending) ", being swapped" else ", taken in on day ${keep.getLongOr(VillageKeepsakes.DAY, 0L)}"))
                c.append(plain(if (VillageKeepsakes.wares(level, r.id)) ", has old wares" else ", no wares"))
            } else {
                c.append(grey(", never a vanilla villager here"))
            }
            val there = r.uuid?.let { level.getEntity(it) }
            c.append(
                when {
                    there is Townsperson -> grey(", body about")
                    there is Villager -> grey(", a vanilla villager now")
                    else -> grey(", body not about (not loaded?)")
                },
            )
            lines.add(c)
        }
        for (v in VillageAdopt.villagersOf(level, rec)) {
            val why = VillageAdopt.exemptCheap(level, v)
            val c = line().append(label("vanilla ")).append(plain(trade(v.villagerData.profession().unwrapKey().orElse(null)?.identifier()?.toString() ?: "", "villager")))
                .append(plain(" at ")).append(at(v.blockPosition()))
            c.append(grey(if (why != null) ", left alone: it ${why.words}" else ", to be taken in"))
            lines.add(c)
        }
        val pages = maxOf(1, (lines.size + PAGE - 1) / PAGE)
        val shown = page.coerceIn(1, pages)
        say(source, header("${rec.name}: the folk (page $shown of $pages)"))
        say(source, line().append(label("Mode: ")).append(plain(mode)))
        val s = VillageAdopt.summary(level, rec)
        say(
            source,
            line().append(label("People: ")).append(plain("${s.people} alive (the most a village has is ${VillageAdopt.maxFolk()}),${s.adopted} taken in from vanilla villagers, ${s.loaded} bodies about; day $day")),
        )
        if (lines.isEmpty()) say(source, grey("Nobody lives here on record, and no villager is about."))
        for (c in lines.drop((shown - 1) * PAGE).take(PAGE)) say(source, c)
        if (shown < pages) say(source, grey("`village folk ${shown + 1}` for the next."))
        return 1
    }

    /** A trade in words: the vanilla profession's name where there is one, else the role. */
    private fun trade(craft: String, role: String): String {
        val name = craft.substringAfter(':', craft)
        return if (name.isNotEmpty() && name != "none") name.replace('_', ' ') else role
    }

    // ---- adopt ----

    private fun adopt(context: CommandContext<CommandSourceStack>, force: Boolean): Int {
        val source = context.source
        val level = source.level
        if (!Villages.enabled()) return fail(source, "Living Villages is off: nobody is taken in, and those who were are given back at night.")
        if (!VillageAdopt.folkOn()) return fail(source, "villageFolk is off (KEEP): nobody is taken in, and those who were are given back at night. Turn it on first.")
        val rec = target(source) ?: return 0
        if (rec.state == Live.LOST) return fail(source, "${rec.name} is lost (its bell and board are gone): nobody is taken in.")
        val wasReleased = !rec.folk
        val (job, tally) = VillageAdopt.adoptNow(level, rec, force, source.player?.uuid)
        say(source, header("${rec.name}: taking folk in" + if (force) " (force: the hour and the eyes ignored)" else ""))
        if (wasReleased) say(source, grey("The village had been released; it takes folk in again from now."))
        if (tally.uncounted) return fail(source, "${rec.name} has not been counted this session, so it has no homes to give beds from: `village census` counts it.")
        if (tally.raid) return fail(source, "A raid is on at ${rec.name}: nobody is taken in until it is over.")
        say(source, line().append(label("Looked at ")).append(plain("${tally.seen} vanilla villagers: ${tally.queued} taken for the swap")))
        val left = ArrayList<String>()
        if (tally.pending > 0) left.add("${tally.pending} already on the way")
        if (tally.resumed > 0) left.add("${tally.resumed} picked up again after a stop")
        if (tally.stale > 0) left.add("${tally.stale} stale copies of folk whose body stands, discarded")
        if (tally.exempt() > 0) left.add(tally.why.entries.filter { it.value > 0 }.joinToString(", ", "left alone: ") { "${it.value} ${it.key.words}" })
        if (tally.day > 0) left.add("${tally.day} not yet (it is not night; `force` ignores that)")
        if (tally.watched > 0) left.add("${tally.watched} not yet (a player is within ${VillageAdopt.NEAR.toInt()} blocks or can see them; `force` ignores that)")
        if (tally.noBed > 0) left.add("${tally.noBed} with no free bed in the village (a bed placed in a house makes room; 6.2: the rest stay vanilla)")
        if (tally.capped > 0) left.add("${tally.capped} over the village's limit of ${VillageAdopt.maxFolk()} people")
        for (c in left) say(source, line().append(plain("  ")).append(grey(c)))
        if (tally.queued > 0) {
            say(source, grey("The swap is made as soon as the world's saved data is written (about a second), and you are told how many were made. ${job.queued} queued."))
        } else if (left.isEmpty()) {
            say(source, grey("No vanilla villager is about in ${rec.name}'s loaded chunks."))
        }
        return 1
    }

    // ---- release ----

    private fun release(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val rec = target(source) ?: return 0
        val gave = VillageAdopt.releaseNow(level, rec)
        say(source, header("${rec.name}: giving the folk back"))
        say(source, line().append(label("Gave back ")).append(plain("${gave.given} as vanilla villagers, with their trades, gossip, bed and job block")))
        val rest = ArrayList<String>()
        if (gave.already > 0) rest.add("${gave.already} were villagers already")
        if (gave.away > 0) rest.add("${gave.away} have no body about (chunks not loaded): they are given back at night when they are about")
        if (gave.kept > 0) rest.add("${gave.kept} are left as they are for now (in a talk, a tale or a captor's hands, or no room for a villager)")
        for (c in rest) say(source, line().append(plain("  ")).append(grey(c)))
        say(source, grey("Nobody is taken in at ${rec.name} until `village adopt`; the people stay on record, so a later adoption puts the same people back."))
        return 1
    }

    // ---- Shared ----

    /** The village the sender stands in, or the nearest bell within 128 blocks; null, having said so, if there is none. */
    private fun target(source: CommandSourceStack): VillageRec? {
        val level = source.level
        if (level.dimension() != Level.OVERWORLD) {
            fail(source, "Villages are counted in the overworld only.")
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
        if (rec == null) fail(source, "No village is counted within $NEAREST blocks of you. `village list` says which are.")
        return rec
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
