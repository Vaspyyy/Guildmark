package io.github.vaspyyy.guildmark.trade.carry

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.cart.PlayerCart
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.trade.TradeConfig
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * The commands of the Chapman's Cart (living-world/trader/design.md section 9; operators only), hung on the `trade` literal that
 * [io.github.vaspyyy.guildmark.trade.TradeCommand] has, with the same permission, so Brigadier merges them by name:
 * - `trade cart spawn`: a cart of yours set down in front of you, its shafts toward you. `trade cart status`: whether carts work, and each loaded
 *   cart of yours. `trade cart hitch` and `unhitch`: take up the shafts of the nearest one, or let go. `trade cart canvas [on|off]`: pull the canvas
 *   over the load, fold it back, or toggle it. `trade cart colour <0-5>`: the pennant in one of the booker's six. `trade cart remove`: take the
 *   nearest one apart (it drops as its item with its contents).
 * - `trade cart fill <good> [units]`: the nearest cart of yours filled with a good, to see the load heaped on the bed.
 *
 * The cart's other commands are the carriers' (`trade carry status|recall|unstick`), which count it with the pack animals.
 */
object CartCommand {
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
                        literal("cart")
                            .then(literal("spawn").executes { spawn(it) })
                            .then(literal("status").executes { status(it) })
                            .then(literal("hitch").executes { hitch(it) })
                            .then(literal("unhitch").executes { unhitch(it) })
                            .then(literal("remove").executes { remove(it) })
                            .then(
                                literal("canvas")
                                    .executes { canvas(it, null) }
                                    .then(Commands.argument("over", BoolArgumentType.bool()).executes { canvas(it, BoolArgumentType.getBool(it, "over")) }),
                            )
                            .then(
                                literal("colour").then(
                                    Commands.argument("booker", IntegerArgumentType.integer(0, PlayerCart.DYES.size - 1))
                                        .executes { colour(it, IntegerArgumentType.getInteger(it, "booker")) },
                                ),
                            )
                            .then(
                                literal("fill").then(
                                    Commands.argument("good", StringArgumentType.word())
                                        .suggests { _, b -> SharedSuggestionProvider.suggest(Good.TRADED.map { it.id }, b) }
                                        .executes { fill(it, 64) }
                                        .then(
                                            Commands.argument("units", IntegerArgumentType.integer(1, PlayerCart.SIZE * 64))
                                                .executes { fill(it, IntegerArgumentType.getInteger(it, "units")) },
                                        ),
                                ),
                            ),
                    ),
                ),
        )
    }

    /** A cart of the sender's set down 3 blocks in front of them, its shafts toward them. */
    private fun spawn(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = player.level() as ServerLevel
        val look = player.lookAngle
        val flat = Math.hypot(look.x, look.z).coerceAtLeast(1.0e-3)
        val at = player.position().add(look.x / flat * 3.2, 0.0, look.z / flat * 3.2)
        val cart = CartHitch.setDown(level, player, at, player.position(), null)
        if (cart == null) {
            say(source, red("No room for a cart there."))
            return 0
        }
        say(source, line().append(label("A cart: ")).append(plain("set down in front of you, the shafts toward you; right-click the shafts with an empty hand to take them up.")))
        if (!TradeConfig.carry()) say(source, line().append(red("Carrying is off")).append(grey(" (setting: Carrying, or Trader's Life): it will not follow, and opens for anyone.")))
        return 1
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        say(source, header("The Chapman's Cart"))
        say(source, line().append(label("Carrying: ")).append(plain(if (TradeConfig.carry()) "on" else "off")).append(grey(" - a cart drags ${(TradeConfig.cartSlow() * 100).toInt()}%")))
        val mine = CartHitch.cartsOf(player.uuid)
        if (mine.isEmpty()) say(source, grey("No loaded cart of yours (`trade cart spawn`)."))
        for (cart in mine) {
            val where = "%d %d %d".format(Locale.ROOT, cart.blockX, cart.blockY, cart.blockZ)
            val far = if (cart.level() === player.level()) "%.0f blocks".format(Locale.ROOT, cart.distanceTo(player)) else "another dimension"
            say(
                source,
                line().append(label("Cart: ")).append(plain("$where ($far), ${cart.state.name.lowercase()}, ${cart.used()}/${PlayerCart.SIZE} slots, canvas ${if (cart.covered) "over" else "folded"}, pennant #%06X".format(Locale.ROOT, cart.colour))),
            )
        }
        val held = CartHitch.inHand(player)
        say(source, line().append(label("In hand: ")).append(plain(if (held == null) "nothing" else "a cart, ${held.stuck} of ${CartHitch.CAUGHT_AFTER} ticks off the pace")))
        return mine.size
    }

    private fun hitch(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val cart = nearest(player)
        if (cart == null) {
            say(source, red("No cart of yours in this level."))
            return 0
        }
        if (!CartHitch.take(cart, player)) {
            say(source, red("Could not take up the shafts (someone has them, or you are not on your feet)."))
            return 0
        }
        say(source, line().append(label("Hitched: ")).append(plain("the nearest cart follows you; `trade cart unhitch` lets go.")))
        return 1
    }

    private fun unhitch(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        if (CartHitch.inHand(player) == null) {
            say(source, grey("You have no cart in hand."))
            return 0
        }
        CartHitch.release(player)
        say(source, line().append(label("Let go: ")).append(plain("the cart tips onto its shafts where it stands.")))
        return 1
    }

    private fun remove(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val cart = nearest(player)
        if (cart == null) {
            say(source, red("No cart of yours in this level."))
            return 0
        }
        CartHitch.breakCart(cart)
        say(source, line().append(label("Taken apart: ")).append(plain("the nearest cart is an item again, with what was in it beside it.")))
        return 1
    }

    private fun canvas(context: CommandContext<CommandSourceStack>, over: Boolean?): Int {
        val source = context.source
        val player = source.playerOrException
        val cart = nearest(player)
        if (cart == null) {
            say(source, red("No cart of yours in this level."))
            return 0
        }
        val now = over ?: !cart.covered
        cart.setCovered(now)
        say(source, line().append(label("Canvas: ")).append(plain(if (now) "pulled over the load." else "folded back.")))
        return 1
    }

    private fun colour(context: CommandContext<CommandSourceStack>, booker: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val cart = nearest(player)
        if (cart == null) {
            say(source, red("No cart of yours in this level."))
            return 0
        }
        cart.setColour(PlayerCart.DYES[booker])
        say(source, line().append(label("Pennant: ")).append(plain("the booker's colour $booker (#%06X).".format(Locale.ROOT, PlayerCart.DYES[booker]))))
        return 1
    }

    /** The nearest cart of the sender's, filled with [units] of a good (stacks of its item, from the first free slot). */
    private fun fill(context: CommandContext<CommandSourceStack>, units: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val cart = nearest(player)
        if (cart == null) {
            say(source, red("No cart of yours in this level."))
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
        for (slot in 0 until cart.containerSize) {
            if (left <= 0) break
            if (!cart.getItem(slot).isEmpty) continue
            val n = minOf(left, item.getDefaultMaxStackSize())
            cart.setItem(slot, ItemStack(item, n))
            left -= n
            put += n
        }
        say(source, line().append(label("Filled: ")).append(plain("$put ${good.id} in the nearest cart (${cart.used()}/${PlayerCart.SIZE} slots).")))
        return put
    }

    private fun nearest(player: ServerPlayer): PlayerCart? =
        CartHitch.cartsOf(player.uuid).filter { it.level() === player.level() }.minByOrNull { it.distanceToSqr(player) }

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
