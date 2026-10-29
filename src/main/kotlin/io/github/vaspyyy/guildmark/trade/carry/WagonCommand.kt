package io.github.vaspyyy.guildmark.trade.carry

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.trade.TradeConfig
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * The commands of the Carrier's Wagon (living-world/trader/design.md section 9; operators only), hung on the `trade` literal the trade's own
 * commands have, with the same permission, so Brigadier merges them by name. Each works on the sender's own wagon:
 * - `trade wagon spawn`: a wagon and its horse (one of yours if you have one, made if not) set down beside you on the road, harnessed;
 *   `trade wagon spawn yard`: in the nearest yard (your Store's, else the capital's wagon stand), there now, to see the rollout.
 * - `trade wagon recall`: called home as the Bill calls it (after the night, for nothing); `recall now`: there at once.
 * - `trade wagon lead`: take its lead (it must be standing near you); `trade wagon status`: where it is, what it is doing, its trail.
 * - `trade wagon fill <good> [units]`: its hold filled with a good, to see the load heaped on the bed. `trade wagon horse`: a horse for it
 *   (a new name and coat if it has one). `trade wagon bill`: a Bill for it. `trade wagon remove`: what it holds given to you, and it is gone.
 */
object WagonCommand {
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    literal("trade").then(
                        literal("wagon")
                            .then(literal("spawn").executes { spawn(it, yard = false) }.then(literal("yard").executes { spawn(it, yard = true) }))
                            .then(literal("recall").executes { recall(it, now = false) }.then(literal("now").executes { recall(it, now = true) }))
                            .then(literal("lead").executes { lead(it) })
                            .then(literal("status").executes { status(it) })
                            .then(literal("horse").executes { horse(it) })
                            .then(literal("bill").executes { bill(it) })
                            .then(literal("remove").executes { remove(it) })
                            .then(
                                literal("fill").then(
                                    Commands.argument("good", StringArgumentType.word())
                                        .suggests { _, b -> SharedSuggestionProvider.suggest(Good.TRADED.map { it.id }, b) }
                                        .executes { fill(it, 256) }
                                        .then(
                                            Commands.argument("units", IntegerArgumentType.integer(1, TradeWagons.SLOTS * 64))
                                                .executes { fill(it, IntegerArgumentType.getInteger(it, "units")) },
                                        ),
                                ),
                            ),
                    ),
                ),
        )
    }

    /** The sender's wagon's record, made (bought now, a horse with it) if they have none. */
    private fun recOf(player: ServerPlayer, makeHorse: Boolean): WagonRec {
        val level = player.level()
        val data = TradeWagons.get(level)
        val fresh = data[player.uuid] == null
        val rec = data.make(player.uuid)
        rec.ownerName = player.name.string
        if (fresh) {
            rec.bought = level.server.overworld().gameTime
            rec.colour = TradeWagons.colourOf(level.server.overworld(), player.uuid)
        }
        if (makeHorse && !rec.horse) {
            rec.horse = true
            rec.horseName = WagonBill.HORSES[level.random.nextInt(WagonBill.HORSES.size)]
            rec.coat = level.random.nextInt(3)
        }
        data.changed()
        return rec
    }

    private fun spawn(context: CommandContext<CommandSourceStack>, yard: Boolean): Int {
        val source = context.source
        val player = source.playerOrException
        val level = player.level()
        val rec = recOf(player, makeHorse = true)
        if (yard) {
            val overworld = level.server.overworld()
            val home = WagonBill.yardFor(overworld, player.uuid, player)
            if (home == null) {
                say(source, red("No yard to put it in: no Store of yours and no capital in this world."))
                return 0
            }
            LedShow.of(player.uuid)?.let { LedShow.drop(it) }
            rec.state = WagonRec.State.YARD
            rec.dimension = Level.OVERWORLD
            rec.at = home.rear
            rec.yaw = home.yaw
            rec.capital = home.capital
            rec.due = 0L
            TradeWagons.get(level).changed()
            val at = home.rear
            say(source, line().append(label("A wagon in the yard: ")).append(plain("at %d %d %d in %s, its horse beside it. Read its Bill there to roll it out.".format(
                Locale.ROOT, at.x.toInt(), at.y.toInt(), at.z.toInt(), WagonBill.placeOf(overworld, rec).ifEmpty { "the capital" },
            ))))
        } else {
            LedShow.bringBeside(player, led = true)
            say(source, line().append(label("A wagon: ")).append(plain("set down beside you, its horse in the shafts. Right-click the horse's head with an empty hand to take its lead; the tailboard opens its hold.")))
        }
        if (!WagonBill.carries(player)) player.inventory.placeItemBackInInventory(WagonBill.stack(player.uuid, player.name.string), Prediction.SERVER_ONLY)
        if (!TradeConfig.wagon()) say(source, line().append(red("The wagon is off")).append(grey(" (setting: Carrier's Wagon, Carrying or Trader's Life): it will not follow; its tailboard still opens and the Bill still calls it home.")))
        return 1
    }

    private fun recall(context: CommandContext<CommandSourceStack>, now: Boolean): Int {
        val source = context.source
        val player = source.playerOrException
        val rec = TradeWagons.peek(player.level())?.get(player.uuid)
        if (rec == null) {
            say(source, red("You have no wagon (`trade wagon spawn`)."))
            return 0
        }
        if (!WagonBill.sendHome(player, rec, null, now = now, free = true)) {
            say(source, red("It could not be sent home."))
            return 0
        }
        return 1
    }

    private fun lead(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val rec = TradeWagons.peek(player.level())?.get(player.uuid)
        val sh = LedShow.of(player.uuid)
        if (rec == null || sh == null || sh.level !== player.level()) {
            say(source, red("No wagon of yours standing near you."))
            return 0
        }
        if (rec.state == WagonRec.State.YARD) {
            say(source, red("It is in the yard: read its Bill there to roll it out first."))
            return 0
        }
        if (!LedShow.take(sh, player, rec)) {
            say(source, red("Its lead could not be taken (no horse in its shafts, a scene playing, or you are not on your feet)."))
            return 0
        }
        say(source, line().append(label("Led: ")).append(plain("it follows the way you walk; right-click the horse's head again to let go.")))
        return 1
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        say(source, header("The Carrier's Wagon"))
        say(source, line().append(label("Wagon: ")).append(plain(if (TradeConfig.wagon()) "on" else "off")).append(grey(" - carrying ${if (TradeConfig.carry()) "on" else "off"}, the rein ${TradeConfig.wagonRein()} blocks")))
        val rec = TradeWagons.peek(player.level())?.get(player.uuid)
        if (rec == null) {
            say(source, grey("You have no wagon (`trade wagon spawn`, or a Wagon Bill bought at your Store's desk)."))
        } else {
            say(source, LedShow.statusLine(player, rec))
            val sh = LedShow.of(player.uuid)
            if (sh != null) {
                val t = sh.trail
                say(
                    source,
                    line().append(label("Shown: ")).append(plain("trail ${t.size}/${LedShow.RING} points, %.1f blocks; horse at %.1f, back axle at %.1f, %.2f blocks a tick; %s%s".format(
                        Locale.ROOT, t.endAlong - (if (t.size > 0) t.along(0) else 0.0), sh.s, sh.rearS, sh.v,
                        if (sh.led) "led" else "standing", if (sh.scene != null) ", a scene playing" else "",
                    ))),
                )
            } else {
                say(source, grey("Its bodies are not out (nobody near it)."))
            }
        }
        say(source, grey("${LedShow.all().size} wagon(s) out in all."))
        return if (rec == null) 0 else 1
    }

    private fun horse(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val rec = TradeWagons.peek(player.level())?.get(player.uuid)
        if (rec == null) {
            say(source, red("You have no wagon (`trade wagon spawn`)."))
            return 0
        }
        val level = player.level()
        rec.horse = true
        rec.horseName = WagonBill.HORSES[level.random.nextInt(WagonBill.HORSES.size)]
        rec.coat = (rec.coat + 1 + level.random.nextInt(2)) % 3
        TradeWagons.get(level).changed()
        LedShow.of(player.uuid)?.let { LedShow.drop(it) }
        say(source, line().append(label("Horse: ")).append(plain("${rec.horseName}, coat ${rec.coat}; the wagon is shown again with them in a moment.")))
        return 1
    }

    private fun bill(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        player.inventory.placeItemBackInInventory(WagonBill.stack(player.uuid, player.name.string), Prediction.SERVER_ONLY)
        say(source, line().append(label("A Wagon Bill: ")).append(plain("for your wagon (a Bill naming you; it calls home whatever wagon you have).")))
        return 1
    }

    private fun remove(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val data = TradeWagons.peek(player.level())
        val rec = data?.get(player.uuid)
        if (rec == null) {
            say(source, red("You have no wagon."))
            return 0
        }
        // What it held is the player's before it goes, so nothing is lost
        var given = 0
        for (i in rec.stacks.indices) {
            val stack = rec.stacks[i]
            if (stack.isEmpty) continue
            rec.stacks[i] = ItemStack.EMPTY
            given += stack.count
            player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        }
        LedShow.dropOf(player.uuid, puff = true)
        data.remove(player.uuid)
        say(source, line().append(label("Removed: ")).append(plain("your wagon is gone; the $given items it held are yours.")))
        return 1
    }

    private fun fill(context: CommandContext<CommandSourceStack>, units: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val rec = TradeWagons.peek(player.level())?.get(player.uuid)
        if (rec == null) {
            say(source, red("You have no wagon (`trade wagon spawn`)."))
            return 0
        }
        val id = StringArgumentType.getString(context, "good")
        val good = Good.byId(id)?.takeIf { it.traded }
        val item = good?.item
        if (good == null || item == null) {
            say(source, red("The market doesn't deal in $id."))
            return 0
        }
        var left = units
        var put = 0
        for (slot in 0 until TradeWagons.SLOTS) {
            if (left <= 0) break
            if (!rec.stacks[slot].isEmpty) continue
            val n = minOf(left, item.getDefaultMaxStackSize())
            rec.stacks[slot] = ItemStack(item, n)
            left -= n
            put += n
        }
        TradeWagons.get(player.level()).changed()
        LedShow.loadChanged(player.uuid)
        say(source, line().append(label("Filled: ")).append(plain("$put ${good.id} in your wagon (${rec.used()}/${TradeWagons.SLOTS} slots).")))
        return put
    }

    // ---- Words ----

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
