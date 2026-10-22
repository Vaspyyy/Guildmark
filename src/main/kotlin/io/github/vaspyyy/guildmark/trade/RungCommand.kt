package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark trade rung` and `trade warrant` (trader design Part 5; operators only), for the ladder's own tests:
 *
 * - `trade rung` shows your rung, what your book holds, your standing at the open capitals and what the next rung asks, each with its numbers.
 * - `trade rung <pedlar|hawker|chapman|factor|house>` sets your rung, down as well as up (play never lowers one). A rung below Chapman also takes
 *   the Warrant out of the book; a Chapman's and above puts it in (the sheet itself is `warrant give`). The periodic look then leaves you alone
 *   until something real happens: a login, a delivery, a Warrant, a Store or a House.
 * - `trade rung deliveries <n>` sets your count of deliveries (a parcel counts 1, a consignment 2, a relief 3) and looks at your rung.
 * - `trade warrant give` writes the Warrant in your book and hands you the sheet, and looks at your rung; `trade warrant take` takes the sheet
 *   from your pack and leaves the book as it is, so the counter's free copy can be tried.
 *
 * Reading never makes the saved record; setting does. [Rungs.register] calls [init]. The `trade` literal is the one [TradeCommand] has, with
 * the same permission, so Brigadier merges them by name.
 */
object RungCommand {
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val rung = Commands.literal("rung").executes { show(it) }
        for (r in Rung.entries) rung.then(Commands.literal(r.id).executes { set(it, r) })
        rung.then(
            Commands.literal("deliveries").then(
                Commands.argument("count", IntegerArgumentType.integer(0, Rungs.MOST_DELIVERIES))
                    .executes { deliveries(it, IntegerArgumentType.getInteger(it, "count")) },
            ),
        )
        val warrant = Commands.literal("warrant")
            .then(Commands.literal("give").executes { give(it) })
            .then(Commands.literal("take").executes { take(it) })
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("trade")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(rung)
                        .then(warrant),
                ),
        )
    }

    // ---- Show ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val facts = Rungs.facts(player.level(), player.uuid, Rungs.book(player))
        say(source, gold("== Your rung: ").append(name(facts.rung)).append(" =="))
        if (!TradeConfig.life()) say(source, red("Trader's Life is off, so nothing here is asked or counted."))
        else if (!TradeConfig.rungs()) say(source, red("Trader's Ladder is off: every gate passes, whatever the rung says, and nothing is counted."))
        val carried = WarrantScene.carries(player)
        say(
            source,
            label("Book: ").append(
                plain(
                    "${facts.deliveries} deliveries (a parcel counts 1, a consignment 2, a relief 3); " +
                        (if (facts.warrant) "the Warrant is in the book, and ${if (carried) "you carry the sheet" else "the sheet is not in your pack"}" else "no Warrant in the book") +
                        "; Stores at ${facts.stores.size} capitals, stalls at ${facts.stalls.size}${if (facts.stallsOn) "" else " (stalls are off)"}, Houses at ${facts.houses.size}",
                ),
            ),
        )
        say(
            source,
            label("Standing: ").append(
                plain(
                    "${facts.ranks.size} capitals open; Known or better at ${Ladder.known(facts)}, Trusted or better at ${Ladder.trusted(facts)}" +
                        (if (facts.ranks.isEmpty()) " (no market is open, so no standing is asked)" else ""),
                ),
            ),
        )
        say(
            source,
            label("Gates: ").append(
                plain(
                    "${Ladder.heldLimit(facts.rung)} tickets held, ${Ladder.guardLimit(facts.rung)} guards, " +
                        (if (Ladder.bigTickets(facts.rung)) "consignments, relief and feast tickets" else "parcels only"),
                ),
            ),
        )
        val next = Rung.entries.getOrNull(facts.rung.ordinal + 1)
        if (next == null) {
            say(source, grey("Nothing more is asked: this is the top."))
        } else {
            say(source, label("Next, ").append(name(next)).append(label(":")))
            for (ask in Ladder.asks(facts, next)) {
                val line = Component.literal("  ").append(Component.translatable(ask.key)).append(": ${ask.have} of ${ask.need}")
                say(source, line.withStyle(if (ask.met) ChatFormatting.GREEN else ChatFormatting.YELLOW))
            }
            val would = Ladder.rung(facts)
            if (would > facts.rung) say(source, grey("The book will tie this knot at the next look (a login, a delivery or a few seconds); `trade rung deliveries` looks now."))
        }
        return 1
    }

    // ---- Set ----

    private fun set(context: CommandContext<CommandSourceStack>, rung: Rung): Int {
        val source = context.source
        val player = source.playerOrException
        val books = TradeBooks.get(player.level())
        val book = books.of(player.uuid)
        val was = Rung.byId(book.rung)
        book.rung = rung.id
        book.warrant = rung >= Rung.CHAPMAN
        books.changed()
        Rungs.pin(player)
        say(
            source,
            plain("Your rung is ").append(name(rung)).append(plain(" (it was ")).append(name(was)).append(
                plain(
                    "). The book has ${if (book.warrant) "you down for" else "no"} Warrant. " +
                        "It stays so until something real is looked at for you (a login, a delivery, a Warrant, a Store or a House).",
                ),
            ),
        )
        return 1
    }

    private fun deliveries(context: CommandContext<CommandSourceStack>, count: Int): Int {
        val source = context.source
        val player = source.playerOrException
        val level = player.level()
        val books = TradeBooks.get(level)
        books.of(player.uuid).deliveries = count
        books.changed()
        say(source, plain("Your count of deliveries is $count."))
        Rungs.check(level, player)
        return 1
    }

    // ---- The Warrant ----

    private fun give(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = player.level()
        val books = TradeBooks.get(level)
        books.of(player.uuid).warrant = true
        books.changed()
        player.inventory.placeItemBackInInventory(WarrantItem.stack(player.name, null), Prediction.SERVER_ONLY)
        say(source, plain("The Warrant is in your book and a sheet is in your pack."))
        Rungs.check(level, player)
        return 1
    }

    private fun take(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val inventory = player.inventory
        var taken = 0
        for (i in 0 until inventory.containerSize) {
            if (inventory.getItem(i).item === ModItems.CARRIERS_WARRANT.get()) {
                inventory.setItem(i, ItemStack.EMPTY)
                taken++
            }
        }
        say(
            source,
            plain(
                if (taken == 0) "There was no Warrant in your pack."
                else "Took $taken Warrant${if (taken == 1) "" else "s"} from your pack. The book is as it was: ask at a hall's counter for another, which is free.",
            ),
        )
        return taken
    }

    // ---- Words ----

    /** The rung's name, in the style of whatever it is appended to. */
    private fun name(rung: Rung): MutableComponent = Component.translatable("gui.guildmark.trade.ladder.rung.${rung.id}")

    private fun gold(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun red(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.RED)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }
}
