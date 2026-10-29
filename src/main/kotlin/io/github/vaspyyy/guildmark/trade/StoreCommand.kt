package io.github.vaspyyy.guildmark.trade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.townsfolk.Comings
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * Testing commands for the Carriers' Stores (trader design, Part 5; operators only), under `/guildmark trade`.
 *
 * `stores [list]` lists every Store in the world: whose, where, its works' number, what it holds (units, kinds, how many hauled), the clerk's
 * wage (days paid ahead, or owing) and whether the clerk has come; and the goods of Stores taken down still waiting for their owners. `store`
 * shows the sender's own Store in the capital they stand in (their only one, standing in none), good by good with each parcel's days.
 * `store bond <good> <units> [days]` puts goods in it as though they had been bonded, nothing taken from the pack: hauled goods bought far
 * from it with [days] left (30 if not said; 0 for plain goods). `store wage <days>` sets the days the clerk is paid ahead (0: owing from
 * today). Nothing here makes a file by looking.
 */
object StoreCommand {
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("trade")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(
                            Commands.literal("stores")
                                .executes { list(it) }
                                .then(Commands.literal("list").executes { list(it) }),
                        )
                        .then(
                            Commands.literal("store")
                                .executes { show(it) }
                                .then(
                                    Commands.literal("bond").then(
                                        Commands.argument("good", StringArgumentType.word())
                                            .suggests { _, b -> SharedSuggestionProvider.suggest(Good.TRADED.filter { it.item != null }.map { it.id }, b) }
                                            .then(
                                                Commands.argument("units", IntegerArgumentType.integer(1, Stores.MOST))
                                                    .executes { bond(it, StringArgumentType.getString(it, "good"), IntegerArgumentType.getInteger(it, "units"), null) }
                                                    .then(
                                                        Commands.argument("days", IntegerArgumentType.integer(0, Ledger.HELD_DAYS.toInt()))
                                                            .executes {
                                                                bond(it, StringArgumentType.getString(it, "good"), IntegerArgumentType.getInteger(it, "units"), IntegerArgumentType.getInteger(it, "days"))
                                                            },
                                                    ),
                                            ),
                                    ),
                                )
                                .then(
                                    Commands.literal("wage").then(
                                        Commands.argument("days", IntegerArgumentType.integer(0, Stores.AHEAD.toInt()))
                                            .executes { wage(it, IntegerArgumentType.getInteger(it, "days")) },
                                    ),
                                ),
                        ),
                ),
        )
    }

    // ---- Every Store ----

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        if (!TradeConfig.stores()) say(source, grey("Carriers' Stores are switched off (setting: Carriers' Stores, or what it needs): nothing is bonded and no wage falls due; what is in them can be taken back."))
        val data = TradeStores.peek(level)
        if (data == null || data.stores.isEmpty() && data.returns.isEmpty()) return say(source, grey("No Carriers' Store has opened in this world."))
        say(source, header("Carriers' Stores"))
        val works = WorksData.get(level)
        for (rec in data.stores) {
            val w = works.find(rec.capital, WorksKind.STORE, owner = rec.owner)
            val head = line().append(label("${rec.name.ifEmpty { "?" }}'s ")).append(plain("in ${Stores.placeOf(level, rec.capital).ifEmpty { where(rec.capital) }}"))
                .append(grey(if (w != null) " (works #${w.id})" else " (its works is gone)"))
            say(source, head)
            val clerk = when {
                w == null -> ""
                w.hired >= 0 -> ", its clerk hired"
                Comings.enabled() -> ", its clerk not come yet"
                else -> ", no clerk (newcomers are switched off)"
            }
            say(source, grey("  holds ${rec.units()} units of ${rec.kinds()} goods, ${rec.hauled()} of them hauled; ${wageLine(level, rec)}$clerk"))
        }
        for ((owner, back) in data.returns) {
            val name = level.server.playerList.getPlayer(owner)?.name?.string ?: owner.toString()
            say(source, grey("Waiting for $name at any hall merchant: ${back.goods.sum()} units from their Store in ${back.place.ifEmpty { "a town" }}${if (back.told) "" else " (not told yet)"}."))
        }
        return 1
    }

    private fun wageLine(level: ServerLevel, rec: StoreRec): String {
        val days = Stores.paidDays(level, rec)
        return if (days > 0) "the wage paid $days day${if (days == 1L) "" else "s"} ahead" else "the wage owing since ${-days} day${if (days == -1L) "" else "s"} ago${if (rec.told) "" else " (not told yet)"}"
    }

    // ---- The sender's own ----

    /** The sender's Store in the capital they stand in, or their only one; null, having said why, if there is none. */
    private fun mine(source: CommandSourceStack): Pair<ServerPlayer, StoreRec>? {
        val player = source.player ?: run {
            fail(source, "Only a player keeps a Store.")
            return null
        }
        val level = source.level
        val works = WorksData.get(level)
        val standing = works.works.values.filter { it.kind == WorksKind.STORE && it.owner == player.uuid && it.state == io.github.vaspyyy.guildmark.economy.works.WorksState.DONE }
        if (standing.isEmpty()) {
            fail(source, "You have no Carriers' Store standing (works propose store, fund it and finish it).")
            return null
        }
        val here = Capitals.covering(level, player.blockPosition())?.center?.asLong()
        val w = standing.firstOrNull { it.capital == here } ?: standing.singleOrNull() ?: run {
            fail(source, "You keep Stores in ${standing.size} towns: stand in the one you mean.")
            return null
        }
        val rec = Stores.recOf(level, w) ?: run {
            fail(source, "That Store has no record (it has no owner).")
            return null
        }
        return player to rec
    }

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val (_, rec) = mine(source) ?: return 0
        Stores.tidy(level, rec)
        say(source, header("Your Store in ${Stores.placeOf(level, rec.capital).ifEmpty { where(rec.capital) }}"))
        say(source, grey("${wageLine(level, rec)} (${Stores.WAGE} Marks a week); ${rec.kinds()} of ${Stores.KINDS} kinds, at most ${Stores.MOST} of each"))
        if (rec.units() == 0) return say(source, grey("Nothing bonded."))
        for (g in Good.entries) {
            val n = rec.of(g)
            if (n == 0) continue
            val plain = n - rec.hauled(g)
            say(source, line().append(label("${g.id}: ")).append(plain("$n")).append(grey(if (plain > 0) " ($plain plain)" else "")))
            for (p in rec.parcels.filter { it.good == g }) {
                say(source, grey("  ${p.units} hauled from ${Suppliers.place(level, p.origin).ifEmpty { where(p.origin.asLong()) }}, ${Stores.daysLeft(level, p)} days left"))
            }
        }
        return 1
    }

    private fun bond(context: CommandContext<CommandSourceStack>, goodId: String, units: Int, days: Int?): Int {
        val source = context.source
        val level = source.level
        val (_, rec) = mine(source) ?: return 0
        val good = Good.byId(goodId)?.takeIf { it.traded && it.item != null } ?: return fail(source, "The market doesn't deal in $goodId.")
        val keeps = days ?: Ledger.HELD_DAYS.toInt()
        // Bought far from the Store, as its parcels must have been: at the farthest open market if that is far enough, else at a place that is
        val heart = BlockPos.of(rec.capital)
        val far = Markets.get(level).all().map { it.capital }.maxByOrNull { Hauls.distance(it, heart) }
        val origin = if (far != null && Hauls.distance(far, heart) >= TradeConfig.haulMin()) far else BlockPos(heart.x + TradeConfig.haulMin() + FAR_EXTRA, heart.y, heart.z)
        val n = Stores.bondWritten(level, rec, good, units, origin, keeps)
        if (n <= 0) return fail(source, "Your Store has no room for $goodId (${rec.kinds()} kinds of ${Stores.KINDS}, ${rec.of(good)} of ${Stores.MOST}).")
        val what = if (keeps > 0) "hauled from ${Suppliers.place(level, origin).ifEmpty { where(origin.asLong()) }}, $keeps days left" else "plain goods"
        return say(source, line().append(label("Bonded: ")).append(plain("$n $goodId ")).append(grey("$what. The Store holds ${rec.of(good)} of it.")))
    }

    private fun wage(context: CommandContext<CommandSourceStack>, days: Int): Int {
        val source = context.source
        val level = source.level
        val (_, rec) = mine(source) ?: return 0
        rec.paidUntil = Suppliers.today(level) + days
        rec.told = false
        TradeStores.get(level).changed()
        return say(source, grey(if (days > 0) "The clerk is paid $days days ahead." else "The clerk's wage is owing from today: the Store takes no new goods."))
    }

    // ---- Words ----

    private fun where(capital: Long): String = BlockPos.of(capital).let { "${it.x}_${it.z}" }

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

    /** Blocks past the haul distance a made-up place of purchase lies from the Store, when no open market is far enough. */
    private const val FAR_EXTRA = 100
}
