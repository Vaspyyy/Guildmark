package io.github.vaspyyy.guildmark.cooking.shop

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.market.Doors
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.townsfolk.Residents
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale

/**
 * Part H's testing commands (operators only; living-world/cook/design.md 10.3), run standing in a capital, under the `cook` root that
 * `CookCommand` makes. They are all about your own cookshop in the capital you stand in:
 *
 *  - `cook shop` is its record in words: the works and its keeper, the hours, the larder by kind and tier and what each portion
 *    brings, the till, what it has sold today and in the week, and what waits for the shoppers.
 *  - `cook shop stock <kind> <tier> <n>` puts `n` dishes of your cooking in its larder, as the keeper takes them from your hand.
 *  - `cook shop sell` works a selling hour now, as the ledger does at 8:00, 12:00 and 16:00 (the day's cap still holds).
 *  - `cook shop till [take]` shows the till, or pays it out as the Menu board does.
 *  - `cook shop close` takes the works down as `works remove` does: pledges are repaid, the till is paid out and the stock comes back.
 */
internal object ShopCommand {
    /** The most dishes one `cook shop stock` puts in: the larder's eighteen stacks of the largest size. */
    private const val MOST = 18 * 64

    fun register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(shop()),
                ),
        )
    }

    // ---- The grammar ----

    private fun shop(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("shop")
        .executes { record(it) }
        .then(
            Commands.literal("stock").then(
                Commands.argument("kind", StringArgumentType.word())
                    .suggests { _, b -> SharedSuggestionProvider.suggest(DishKind.entries.map { it.id }, b) }
                    .then(
                        Commands.argument("tier", StringArgumentType.word())
                            .suggests { _, b -> SharedSuggestionProvider.suggest(Tier.entries.map { it.name.lowercase(Locale.ROOT) }, b) }
                            .then(
                                Commands.argument("n", IntegerArgumentType.integer(1, MOST)).executes {
                                    stock(it, StringArgumentType.getString(it, "kind"), StringArgumentType.getString(it, "tier"), IntegerArgumentType.getInteger(it, "n"))
                                },
                            ),
                    ),
            ),
        )
        .then(Commands.literal("sell").executes { sell(it) })
        .then(Commands.literal("till").executes { till(it, false) }.then(Commands.literal("take").executes { till(it, true) }))
        .then(Commands.literal("close").executes { close(it) })

    // ---- Where it is run from ----

    /** The player at the command, the capital they stand in and their cookshop there, if they have one. */
    private class Here(val player: ServerPlayer, val level: ServerLevel, val capital: CapitalPiece, val works: WorksRec?) {
        val key: Long get() = capital.center.asLong()
    }

    private fun here(context: CommandContext<CommandSourceStack>): Here? {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val capital = Capitals.covering(level, player.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        return Here(player, level, capital, WorksData.get(level).find(capital.center.asLong(), WorksKind.COOKSHOP, owner = player.uuid))
    }

    private fun place(h: Here): String = Suppliers.place(h.level, h.capital.center).ifEmpty { "the capital at ${h.capital.center.x}, ${h.capital.center.z}" }

    /** The player's cookshop here; none is told with how one is had. */
    private fun mine(source: CommandSourceStack, h: Here): WorksRec? {
        val works = h.works
        if (works == null) {
            fail(source, "You have no cookshop in ${place(h)}. `works propose cookshop`, `works fund <id>`, `works start <id>` and `works finish <id>` raise one (a Trusted Supplier here, a cook of 25).")
            return null
        }
        return works
    }

    /** The player's cookshop here, built and open to the ledger (its record made). */
    private fun standing(source: CommandSourceStack, h: Here): Pair<WorksRec, ShopRec>? {
        val works = mine(source, h) ?: return null
        if (works.state != WorksState.DONE) {
            fail(source, "Your cookshop (works #${works.id}) is not built yet: ${works.state.name.lowercase(Locale.ROOT)}.")
            return null
        }
        val shop = CookShops.ensure(h.level, works)
        if (shop == null) {
            fail(source, "Your cookshop (works #${works.id}) has no owner on its record.")
            return null
        }
        return works to shop
    }

    // ---- cook shop ----

    private fun record(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val works = mine(source, h) ?: return 0
        val level = h.level
        val shop = CookShops.shopOf(level, works)
        val today = Cooking.today(level)
        say(source, header("Your cookshop in ${place(h)}"))
        say(
            source,
            line().append(label("Switches: ")).append(plain("Hearthcraft ${onOff(Cooking.enabled())}, cookshops ${onOff(Cooking.shops())}, town works ${onOff(Works.enabled())}")),
        )
        say(source, line().append(label("Works: ")).append(plain("#${works.id}, ${if (works.state == WorksState.DONE) "standing" else "not built yet (${works.state.name.lowercase(Locale.ROOT)})"}; ${keeper(h, works)}")))
        if (works.state != WorksState.DONE) return 1
        val hour = CookShops.hour(level)
        say(
            source,
            line().append(label("Hours: ")).append(
                plain(
                    "${clock(hour)} now, " + (if (CookShops.isOpen(level)) "open" else "shut") + " (open ${clock(CookShops.OPEN)} to ${clock(CookShops.SHUT)}; it sells at 08:00, 12:00 and 16:00, rest day or not)",
                ),
            ),
        )
        if (shop == null) return say(source, grey("It has no record in the ledger yet: it is made when the shop opens."))
        say(source, line().append(label("Larder: ")).append(plain(larder(level, shop))))
        val rec = Kitchens.get(level).rec(works.capital)
        val prices = Prices.of(level, works.capital, rec, shop, today)
        val wares = offers(shop, prices)
        say(
            source,
            line().append(label("Sells: ")).append(
                if (wares.isEmpty()) grey("nothing (an empty larder, or only dishes that cannot be sold)")
                else plain(wares.joinToString("; ") { "${it.kind.id} ${it.tier.name.lowercase(Locale.ROOT)}${stale(it.stage)} x${it.portions} at ${n2(it.pay)} Marks" }),
            ),
        )
        val carry = rec?.let { Doors.carried(it, Door.SHOP, shop.owner) } ?: 0.0
        say(
            source,
            line().append(label("Till: ")).append(
                plain("${shop.till} Marks, and ${n2(carry)} of a Mark carried toward the next; the keeper is owed ${n2(shop.wages)} of ${n2(OwnStalls.KEEPER_WAGE)} Marks a week"),
            ),
        )
        val entry = Markets.find(level, works.capital)
        val soldToday = if (shop.day == today) DishKind.entries.filter { shop.sold[it.ordinal] > 0 }.joinToString(", ") { "${it.id} ${shop.sold[it.ordinal]}" } else ""
        val soldAll = if (shop.day == today) shop.sold.sum() else 0
        if (entry == null) {
            say(source, line().append(label("Today: ")).append(plain("sold $soldAll portions${if (soldToday.isNotEmpty()) " ($soldToday)" else ""}; its market is not open yet, so the cap cannot be told")))
        } else {
            val people = entry.town.people
            val door = rec?.takeIf { it.day == today }?.soldAt(Door.SHOP) ?: 0
            say(
                source,
                line().append(label("Today: ")).append(
                    plain(
                        "sold $soldAll portions${if (soldToday.isNotEmpty()) " ($soldToday)" else ""}; the town's shops together have sold $door of the ${Doors.cap(Door.SHOP, people)} a day they may " +
                            "(${Doors.kindCap(Door.SHOP, people)} of one kind at most, ${Doors.serving(Door.SHOP, people)} an hour)",
                    ),
                ),
            )
        }
        val last = if (shop.lastAt < 0) "none since the server started" else "${shop.lastPortions} portions for ${n2(shop.lastMarks)} Marks after the keeper's wage, ${ago(level.gameTime - shop.lastAt)} ago"
        say(source, line().append(label("Last selling hour: ")).append(plain(last)))
        say(source, line().append(label("Waiting for shoppers: ")).append(plain("${shop.waiting.size} portions" + if (CookShoppers.serving(works.id)) " (a shopper is at the counter)" else "")))
        val top = shop.week.top(today)
        say(
            source,
            line().append(label("The week: ")).append(
                plain(if (top == null) "nothing sold" else "${n2(shop.week.takings(today))} Marks taken after the wage; ${top.id} sold the most"),
            ),
        )
        return 1
    }

    /** Who keeps [works]: the resident hired and whether they are about the counter. */
    private fun keeper(h: Here, works: WorksRec): String {
        if (works.hired < 0) return "no keeper hired yet"
        val name = Residents.get(h.level)[works.hired]?.name ?: "resident #${works.hired}"
        val body = CookShops.keeperOf(h.level, works) ?: return "keeper $name is not in the world just now"
        val post = CookShops.post(h.level, works)
        return "keeper $name" + if (post != null && body.distanceToSqr(Vec3.atCenterOf(post)) < 9.0) ", at the counter" else ", away from the counter"
    }

    /** [shop]'s larder in a line: its stacks by kind, tier and how they have kept. */
    private fun larder(level: ServerLevel, shop: ShopRec): String {
        val groups = LinkedHashMap<String, Int>()
        var stacks = 0
        for (stack in shop.stock) {
            if (stack.isEmpty) continue
            stacks++
            val kind = Dishes.kindOf(stack)?.id ?: "other"
            val tier = Dishes.dataOf(stack)?.tier?.name?.lowercase(Locale.ROOT) ?: "?"
            groups.merge("$kind $tier${stale(Dishes.stage(level, stack))}", stack.count, Int::plus)
        }
        if (groups.isEmpty()) return "empty"
        return "${groups.values.sum()} portions in $stacks of ${ShopRec.SLOTS} slots: " + groups.entries.joinToString(", ") { "${it.value} ${it.key}" }
    }

    private fun stale(stage: Stage): String = when (stage) {
        Stage.FRESH -> ""
        Stage.STALE -> " (stale)"
        Stage.GONE -> " (gone off)"
    }

    // ---- cook shop stock ----

    private fun stock(context: CommandContext<CommandSourceStack>, kindName: String, tierName: String, n: Int): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val (_, shop) = standing(source, h) ?: return 0
        val kind = DishKind.byId(kindName.lowercase(Locale.ROOT)) ?: return fail(source, "No such dish: $kindName (${DishKind.entries.joinToString(", ") { it.id }}).")
        val tier = Tier.entries.firstOrNull { it.name.equals(tierName, ignoreCase = true) }
            ?: return fail(source, "No such tier: $tierName (${Tier.entries.joinToString(", ") { it.name.lowercase(Locale.ROOT) }}).")
        val stack = Dishes.make(kind, DishData(tier, Cooking.today(h.level), h.player.name.string, h.player.uuid, 0), n)
        val moved = CookShops.view(h.level, shop).insert(stack)
        if (moved == 0) return fail(source, "The larder is full: nothing was put in.")
        val left = if (stack.isEmpty) 0 else stack.count
        return say(
            source,
            plain(
                "$moved ${tier.name.lowercase(Locale.ROOT)} ${kind.id} put in the larder of your cookshop (it holds ${CookShops.view(h.level, shop).portions()} portions now)" +
                    if (left > 0) "; the larder is full, $left did not fit." else ".",
            ),
        )
    }

    // ---- cook shop sell ----

    private fun sell(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val (works, shop) = standing(source, h) ?: return 0
        val level = h.level
        if (!Cooking.shops() || !Works.enabled()) return fail(source, "Cookshops are switched off (Cookshops, Hearthcraft, or the Living World's Town Works): nothing sells.")
        val entry = Markets.of(level, h.capital) ?: return fail(source, "Its market is not open yet (its places are still being worked out): try again in a second.")
        val before = shop.till
        val portions = shop.stock.sumOf { if (it.isEmpty) 0 else it.count }
        val sold = CookShops.sellHour(level, entry, Markets.hourOf(level))
        val kitchens = Kitchens.get(level)
        // What the shops sold waits in the ledger for the next meal, as the ledger's own step has it
        if (sold > 0.0) {
            kitchens.recOrNew(works.capital).pending += sold
            kitchens.changed()
        }
        val yours = portions - shop.stock.sumOf { if (it.isEmpty) 0 else it.count }
        return say(
            source,
            line().append(label("A selling hour worked: ")).append(
                plain(
                    "the town's shops sold ${sold.toInt()} portions in all, $yours of them yours for ${n2(shop.lastMarks)} Marks after the keeper's wage; " +
                        "your till holds ${shop.till} Marks (${shop.till - before} more). `cook shop` shows what sold.",
                ),
            ),
        )
    }

    // ---- cook shop till ----

    private fun till(context: CommandContext<CommandSourceStack>, take: Boolean): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val (works, shop) = standing(source, h) ?: return 0
        if (take) {
            val paid = ShopTill.take(h.level, works, shop, h.player)
            return if (paid > 0) 1 else say(source, grey("The till is empty."))
        }
        val carry = Kitchens.get(h.level).rec(works.capital)?.let { Doors.carried(it, Door.SHOP, shop.owner) } ?: 0.0
        return say(
            source,
            line().append(label("Till: ")).append(plain("${shop.till} Marks, and ${n2(carry)} of a Mark carried toward the next. `cook shop till take` pays it out.")),
        )
    }

    // ---- cook shop close ----

    private fun close(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val works = mine(source, h) ?: return 0
        val back = works.pledges.filter { !it.free }.sumOf { it.marks }
        val till = CookShops.shopOf(h.level, works)?.till ?: 0
        if (!Works.remove(h.level, works.id)) return fail(source, "There's no works #${works.id}.")
        say(
            source,
            plain(
                "Your cookshop (works #${works.id}) is taken down: its till of $till Marks is paid out and its stock comes back to you; " +
                    if (back > 0) "$back Marks are paid back to those who pledged them." else "there is nothing to pay back.",
            ),
        )
        return say(source, grey("Its blocks come down to what its drawing found there: now where its land is loaded, else as soon as it is."))
    }

    // ---- Words ----

    private fun clock(hour: Double): String = String.format(Locale.ROOT, "%02d:%02d", hour.toInt(), ((hour - hour.toInt()) * 60.0).toInt())

    /** [ticks] of game time as a few words. */
    private fun ago(ticks: Long): String = when {
        ticks < 20 * 90 -> "${ticks / 20} seconds"
        else -> "${ticks / 1200} minutes"
    }

    private fun n2(x: Double): String = String.format(Locale.ROOT, "%.2f", x)

    private fun onOff(on: Boolean): String = if (on) "on" else "off"

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
