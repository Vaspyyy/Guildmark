package io.github.vaspyyy.guildmark.mill

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.MillSite
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for a capital's mills, run standing in the capital. `/guildmark market mills` lists each of its
 * windmills and watermill wheels: where it is (click to go there), whether its sails or wheel are turning and why or
 * why not, and for an older capital's mill still built of blocks, whether it can be swapped for one that turns or which
 * block a player has changed; `mills swap` swaps every one that can be now, players about or not.
 */
object MillCommand {
    /** Has the commands registered with the others. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    /** Adds `mills` to /guildmark market (each node needs an operator: a merged node keeps whatever it was first given). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val op = Commands.hasPermission<CommandSourceStack>(Commands.LEVEL_GAMEMASTERS)
        dispatcher.register(
            Commands.literal("guildmark").requires(op).then(
                Commands.literal("market").requires(op).then(
                    Commands.literal("mills").requires(op)
                        .executes { list(it) }
                        .then(Commands.literal("swap").executes { swap(it) }),
                ),
            ),
        )
    }

    /** The capital the player stands in and its plan, or null having said why not. */
    private fun capital(context: CommandContext<CommandSourceStack>): Pair<CapitalPiece, CityPlan>? {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val places = CityPlaces.of(level, capital)
        if (places == null) {
            fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
            return null
        }
        return capital to places.census.plan
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val (capital, plan) = capital(context) ?: return 0
        val source = context.source
        val level = source.level
        val results = MillRefit.all(level, capital, plan, apply = false)
        say(source, header("The mills of ${VillageNames.of(capital.boardAnchor(plan))}"))
        if (!Mills.enabled()) say(source, grey("The mills are switched off (setting: Turning Mills): they stand still, and older ones aren't swapped."))
        if (results.isEmpty()) return say(source, grey("It has no windmill and no watermill."))
        var old = 0
        for ((site, result) in results) {
            val hub = capital.world(site.hub.x, site.hub.y, site.hub.z)
            val line = line().append(label(name(site) + " ")).append(at(level, site, hub)).append(plain(": "))
            when (result.outcome) {
                MillRefit.Outcome.TURNING -> {
                    val be = level.getBlockEntity(hub) as? MillBlockEntity
                    if (be == null) {
                        line.append(grey("its hub has no block entity (it should)"))
                    } else {
                        val shown = be.blockState.getValue(MillBlock.WORK)
                        val why = Mills.why(level, be)
                        line.append(work(site, shown)).append(plain(": ")).append(grey(why.reason))
                        if (why.work != shown) line.append(grey(" (it shows ${words(site, why.work)} at its next look, within five seconds)"))
                        if (be.capital == null) line.append(grey(" (not yet set up for its capital)"))
                    }
                }
                MillRefit.Outcome.CROWDED -> line.append(grey("built of blocks for good: its sails would sweep through "))
                    .append(block(level, result.at)).append(grey(" at ")).append(result.at?.let { at(it) } ?: grey("?"))
                MillRefit.Outcome.READY -> {
                    old++
                    line.append(plain("still built of blocks (an older capital's), as the plan built it: "))
                        .append(grey("swapped for one that turns once nobody is within ${MillRefit.UNSEEN.toInt()} blocks"))
                }
                MillRefit.Outcome.CHANGED -> {
                    val pos = result.at
                    line.append(plain("still built of blocks, and left so: a player has changed it at "))
                        .append(pos?.let { at(it) } ?: grey("?"))
                        .append(grey(" (" + id(result.found) + " where the plan built " + (result.built?.let(::id) ?: "nothing but open air or water") + ")"))
                }
                MillRefit.Outcome.NOT_LOADED -> line.append(grey("not all of its land is loaded: it'll be looked at when it is"))
                else -> line.append(grey(result.outcome.name.lowercase()))
            }
            say(source, line)
        }
        if (old > 0) {
            say(source, Component.literal("[Swap the old ones now]").withStyle {
                it.withColor(ChatFormatting.GREEN)
                    .withClickEvent(ClickEvent.RunCommand("/guildmark market mills swap"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Swap every mill that can be for one that turns, now")))
            })
        }
        return 1
    }

    private fun swap(context: CommandContext<CommandSourceStack>): Int {
        val (capital, plan) = capital(context) ?: return 0
        val source = context.source
        val level = source.level
        if (!Mills.enabled()) return fail(source, "The mills are switched off (setting: Turning Mills): switch them on first.")
        val results = MillRefit.all(level, capital, plan, apply = true)
        say(source, header("Swapping the mills of ${VillageNames.of(capital.boardAnchor(plan))}"))
        if (results.isEmpty()) return say(source, grey("It has no windmill and no watermill."))
        for ((site, result) in results) {
            val hub = capital.world(site.hub.x, site.hub.y, site.hub.z)
            val line = line().append(label(name(site) + " ")).append(at(level, site, hub)).append(plain(": "))
            line.append(
                when (result.outcome) {
                    MillRefit.Outcome.SWAPPED -> Component.literal("swapped: it turns now").withStyle(ChatFormatting.GREEN)
                    MillRefit.Outcome.TURNING -> grey("already turns")
                    MillRefit.Outcome.CROWDED -> grey("built of blocks for good (its neighbours stand where its sails would turn)")
                    MillRefit.Outcome.CHANGED -> grey("left as it is: ").append(result.at?.let { at(it) } ?: grey("?"))
                        .append(grey(" isn't as the plan built it (" + id(result.found) + ")"))
                    MillRefit.Outcome.NOT_LOADED -> grey("not all of its land is loaded")
                    else -> grey(result.outcome.name.lowercase())
                },
            )
            say(source, line)
        }
        return 1
    }

    private fun name(site: MillSite): String =
        if (site.windmill) "Windmill" + (if (site.count > 1) " ${site.rank + 1} of ${site.count}" else "") else "Watermill wheel"

    /** What a mill doing [work] shows, coloured. */
    private fun work(site: MillSite, work: MillWork): MutableComponent = Component.literal(words(site, work)).withStyle(
        when (work) {
            MillWork.WORKING -> ChatFormatting.GREEN
            MillWork.IDLE -> if (site.windmill) ChatFormatting.YELLOW else ChatFormatting.AQUA
            MillWork.BROKEN -> ChatFormatting.RED
        },
    )

    private fun words(site: MillSite, work: MillWork): String = when (work) {
        MillWork.WORKING -> if (site.windmill) "turning" else "turning hard"
        MillWork.IDLE -> if (site.windmill) "braked" else "turning slowly"
        MillWork.BROKEN -> "broken, a sail hanging"
    }

    private fun block(level: ServerLevel, pos: BlockPos?): MutableComponent = plain(pos?.let { id(level.getBlockState(it)) } ?: "?")

    private fun id(state: BlockState?): String = state?.let { BuiltInRegistries.BLOCK.getKey(it.block).toString() } ?: "?"

    /** A mill to click to go to: for a windmill the foot of it in front of the door, for a wheel the water just in front of it. */
    private fun at(level: ServerLevel, site: MillSite, hub: BlockPos): MutableComponent {
        val facing = (level.getBlockEntity(hub) as? MillBlockEntity)?.blockState?.getValue(MillBlock.FACING)
        val there = when {
            facing == null -> hub
            site.windmill -> hub.relative(facing, 2).below(10)
            else -> hub.relative(facing, 4).above()
        }
        return Component.literal("[${hub.x} ${hub.y} ${hub.z}]").withStyle {
            it.withColor(ChatFormatting.AQUA)
                .withClickEvent(ClickEvent.RunCommand("/tp @s ${there.x} ${there.y} ${there.z}"))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to go there")))
        }
    }

    /** A position to click to go to. */
    private fun at(pos: BlockPos): MutableComponent = Component.literal("[${pos.x} ${pos.y} ${pos.z}]").withStyle {
        it.withColor(ChatFormatting.AQUA)
            .withClickEvent(ClickEvent.RunCommand("/tp @s ${pos.x} ${pos.y + 1} ${pos.z}"))
            .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to go there")))
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
