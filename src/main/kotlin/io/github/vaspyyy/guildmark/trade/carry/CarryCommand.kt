package io.github.vaspyyy.guildmark.trade.carry

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.trade.Carriers
import io.github.vaspyyy.guildmark.trade.TradeConfig
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * The commands of carrying (living-world/trader/design.md section 9; operators only), hung on the `trade` literal that
 * [io.github.vaspyyy.guildmark.trade.TradeCommand] has, with the same permission, so Brigadier merges them by name and neither file
 * edits the other:
 * - `trade laden [marks|off]`: the load, its level and its look; pins a value for a minute.
 * - `trade pack spawn [donkey|mule|llama]`: a tame, harnessed animal of yours beside you. `trade pack harness`: the kit (a harness and
 *   a whistle). `trade pack unharness`: takes the harness off the nearest pack animal of yours.
 * - `trade carry status`: every carrier of yours, where and in what state. `recall`: brings them all to you now. `unstick`: lets go
 *   of leads and hitches and puts them on the ground beside you. `bolt [seconds]` and `alarm [seconds]`: what the Hunt will do to your
 *   pack animals, to try them without a Hunt.
 */
object CarryCommand {
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val spawn = literal("spawn").executes { spawn(it, "donkey") }
        for (kind in KINDS) spawn.then(literal(kind).executes { spawn(it, kind) })
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    literal("trade")
                        .then(
                            literal("laden")
                                .executes { laden(it) }
                                .then(literal("off").executes { unpin(it) })
                                .then(
                                    Commands.argument("marks", IntegerArgumentType.integer(0, Laden.PIN_MOST))
                                        .executes { pin(it, IntegerArgumentType.getInteger(it, "marks")) },
                                ),
                        )
                        .then(
                            literal("pack")
                                .then(spawn)
                                .then(literal("harness").executes { harness(it) })
                                .then(literal("unharness").executes { unharness(it) }),
                        )
                        .then(
                            literal("carry")
                                .then(literal("status").executes { status(it) })
                                .then(literal("recall").executes { recall(it) })
                                .then(literal("unstick").executes { unstick(it) })
                                .then(
                                    literal("bolt")
                                        .executes { bolt(it, PackFollow.BOLT_TICKS / 20) }
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300)).executes { bolt(it, IntegerArgumentType.getInteger(it, "seconds")) }),
                                )
                                .then(
                                    literal("alarm")
                                        .executes { alarm(it, PackFollow.ALARM_TICKS / 20) }
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300)).executes { alarm(it, IntegerArgumentType.getInteger(it, "seconds")) }),
                                ),
                        ),
                ),
        )
    }

    private val KINDS = listOf("donkey", "mule", "llama")

    // ---- trade laden ----

    /** What the sender's load is worth now, and what shows of it. */
    private fun laden(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        say(source, header("Laden"))
        report(source, player)
        return 1
    }

    /** Pins the sender's load at [marks] for a minute. */
    private fun pin(context: CommandContext<CommandSourceStack>, marks: Int): Int {
        val source = context.source
        val player = source.playerOrException
        Laden.pin(player, marks)
        say(source, line().append(label("Pinned: ")).append(plain("your load is $marks Marks for a minute (${Laden.PIN_TICKS / 20} seconds); ")).append(grey("`trade laden off` lets go.")))
        report(source, player)
        return 1
    }

    private fun unpin(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        Laden.unpin(player)
        say(source, line().append(label("Let go: ")).append(plain("your load is what you carry again.")))
        report(source, player)
        return 1
    }

    /** The lines both say: the number, its level and what that looks like, the switches that decide whether it is drawn, and the pack's share against the carriers'. */
    private fun report(source: CommandSourceStack, player: ServerPlayer) {
        val marks = Laden.of(player)
        val level = Laden.level(marks)
        val pinned = Laden.pinned(player)
        say(
            source,
            line().append(label("Load: ")).append(plain("$marks Marks, level $level (${LOOKS[level]})"))
                .append(grey(if (pinned != null) " - pinned" else " - counted from what you carry")),
        )
        say(source, grey("The levels begin at ${Laden.SHOWN}, ${Laden.FRAME} and ${Laden.TALL} Marks."))
        if (!TradeConfig.carry()) say(source, line().append(red("Carrying is off")).append(grey(" (setting: Carrying, or Trader's Life): no pack is drawn; the Hunt still reads the number.")))
        if (!Laden.started(player)) say(source, grey("You have not taken up the trade (no Tally yet), so a count of your pack is 0; a pinned value shows all the same."))
        val parts = Carriers.holdings(player, Carriers.REACH).mapIndexed { i, container ->
            val worth = Laden.worth(container)
            (if (i == 0) "pack" else "carrier $i") + " " + "%.1f".format(Locale.ROOT, worth)
        }
        say(source, line().append(label("At base prices: ")).append(plain(parts.joinToString(", "))))
    }

    // ---- trade pack ----

    /** A tame, harnessed animal of the sender's beside them, its chest fitted the way the harness fits one. */
    private fun spawn(context: CommandContext<CommandSourceStack>, kind: String): Int {
        val source = context.source
        val player = source.playerOrException
        val type: EntityType<out AbstractChestedHorse> = when (kind) {
            "mule" -> EntityTypes.MULE
            "llama" -> EntityTypes.LLAMA
            else -> EntityTypes.DONKEY
        }
        val animal = Harness.tame(player, type)
        if (animal == null) {
            say(source, red("Could not make a $kind."))
            return 0
        }
        if (!Harness.fitChest(player, animal, InteractionHand.MAIN_HAND)) {
            say(source, red("The $kind would not take a chest; it is tame and yours, without a harness."))
            return 0
        }
        Harness.fit(animal, player)
        say(source, line().append(label("A $kind: ")).append(plain("tame, harnessed and yours, with ${animal.getInventory().containerSize} slots in its panniers. ")).append(grey("Walk off, and blow the whistle (`trade pack harness` gives one).")))
        if (!TradeConfig.carry()) say(source, line().append(red("Carrying is off")).append(grey(" (setting: Carrying, or Trader's Life): it will stand where it is.")))
        return 1
    }

    /** The kit: a harness and a whistle. */
    private fun harness(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        Carry.giveKit(player)
        say(source, line().append(label("The kit: ")).append(plain("a Pack Harness and a Drover's Whistle.")))
        return 1
    }

    /** Takes the harness off the nearest pack animal of the sender's within sixteen blocks, giving nothing back (the way a player gets it back is to use another harness on it, sneaking). */
    private fun unharness(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val animal = PackFollow.owned(player, 16.0).firstOrNull()
        if (animal == null) {
            say(source, grey("No pack animal of yours within 16 blocks."))
            return 0
        }
        Harness.unfit(animal)
        say(source, line().append(label("Unharnessed: ")).append(plain("the nearest one is only a tame animal again; its chest and what is in it stay.")))
        return 1
    }

    // ---- trade carry ----

    /** Every carrier of the sender's: where it is and what it is doing. */
    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        say(source, header("Carriers"))
        say(source, line().append(label("Carrying: ")).append(plain(if (TradeConfig.carry()) "on" else "off")))
        val animals = PackFollow.ownedAnywhere(player)
        if (animals.isEmpty()) say(source, grey("No loaded pack animal of yours."))
        for (animal in animals) say(source, animalLine(player, animal))
        for (extra in Carry.reports) for (text in extra.lines(player)) say(source, text)
        return animals.size
    }

    /** One pack animal as a line: its kind, where, how far, what it is doing and what it carries. */
    private fun animalLine(player: ServerPlayer, animal: AbstractChestedHorse): MutableComponent {
        val kit = PackKit.of(animal)
        val where = "%d %d %d".format(Locale.ROOT, animal.blockX, animal.blockY, animal.blockZ)
        val far = if (animal.level() === player.level()) "%.0f blocks".format(Locale.ROOT, animal.distanceTo(player)) else "another dimension"
        val chest = if (animal.hasChest()) {
            val inventory = animal.getInventory()
            var used = 0
            for (slot in 0 until inventory.containerSize) if (!inventory.getItem(slot).isEmpty) used++
            "$used/${inventory.containerSize} slots, ${"%.1f".format(Locale.ROOT, Laden.worth(inventory))} Marks at base prices, panniers ${kit?.fill ?: 0}/3"
        } else {
            "no chest"
        }
        return line().append(label(animal.type.description.string + ": ")).append(plain("$where ($far), ${PackFollow.stateOf(animal)}; $chest; owner ${kit?.owner?.ifEmpty { "?" } ?: "?"}"))
    }

    /** Brings every loaded pack animal of the sender's (and anything else that is theirs) to them now. */
    private fun recall(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val here = PackFollow.recall(player)
        var others = 0
        for (bring in Carry.recalls) others += bring.bring(player)
        val total = here + others
        say(source, line().append(label("Recalled: ")).append(plain(if (total == 0) "nothing of yours is loaded to bring." else "$total brought to you.")))
        return total
    }

    /** Lets go of leads and puts every loaded pack animal of the sender's on the ground beside them, following. */
    private fun unstick(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        var total = PackFollow.unstick(player)
        for (bring in Carry.unsticks) total += bring.bring(player)
        say(source, line().append(label("Unstuck: ")).append(plain(if (total == 0) "nothing of yours is loaded to free." else "$total put on the ground beside you.")))
        return total
    }

    /** The Hunt's bolt, for trying: each pack animal of the sender's within 64 blocks runs from them for [seconds], then goes home. */
    private fun bolt(context: CommandContext<CommandSourceStack>, seconds: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val n = PackFollow.bolt(player, player.position(), seconds * 20)
        say(source, line().append(label("Bolt: ")).append(plain("$n pack animal(s) run from you for $seconds seconds, then go to where you were last together.")))
        return n
    }

    /** The Hunt's horn, for trying: each pack animal of the sender's within 64 blocks throws up its head and goes quiet for [seconds]. */
    private fun alarm(context: CommandContext<CommandSourceStack>, seconds: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val n = PackFollow.alarm(player, player.position().add(player.lookAngle.scale(30.0)), seconds * 20)
        say(source, line().append(label("Alarm: ")).append(plain("$n pack animal(s) stand with their heads up, towards where you look, for $seconds seconds.")))
        return n
    }

    // ---- Words ----

    private val LOOKS = arrayOf("no pack", "a rolled blanket and a sack", "a frame with two sacks and a lantern", "a tall frame with sacks, a barrel and pots")

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun red(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.RED)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component) {
        source.sendSuccess({ c }, false)
    }
}
