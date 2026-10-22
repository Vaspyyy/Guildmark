package io.github.vaspyyy.guildmark.cooking.market

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.recipe.Hints
import io.github.vaspyyy.guildmark.cooking.recipe.Ideal
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.Locale
import java.util.UUID

/**
 * Part G's testing commands (operators only; living-world/cook/design.md 10.3), run standing in a capital, under the `cook`
 * root that `CookCommand` makes:
 *
 *  - `cook kitchens` is the ledger of the capital in words: the day, what each door has sold and may still take, the three
 *    wanted kinds and the request, what waits in the pantries, what the last meals served, the week's index, what is owed;
 *    `cook kitchens run [hours]` works the market ahead as `market run` does, so the meal hours serve the lots now.
 *  - `cook request [new|fill|clear]` shows today's request, draws another, fills it, or goes back to the seed's own.
 *  - `cook door [inn|bakery]` is what the keeper beside you would pay for the dish in your hand, with every factor, and
 *    selling nothing; `cook door stamp ...` gives the dish in hand a mix, so a keeper has something to give a verdict on.
 *  - `cook caps reset [fees]` clears today's counters (and, with `fees`, your carries and first-of-kind fees).
 *  - `cook owed [pay]` lists the Marks owed at the back doors, or pays yours.
 */
object KitchenCommand {
    /** How much of the whole a stamped mix is moved off the ideal onto (or off) one role: a verdict of "close", one role out. */
    private const val STEP = 0.08

    /** How far from a keeper `cook door` looks for the door to quote. */
    private const val NEAR = 8.0

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
                        .then(kitchens())
                        .then(request())
                        .then(door())
                        .then(caps())
                        .then(owed()),
                ),
        )
    }

    // ---- The grammar ----

    private fun kitchens(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("kitchens")
        .executes { ledger(it) }
        .then(
            Commands.literal("run")
                .executes { run(it, 24) }
                .then(Commands.argument("hours", IntegerArgumentType.integer(1, 24 * 14)).executes { run(it, IntegerArgumentType.getInteger(it, "hours")) }),
        )

    private fun request(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("request")
        .executes { request(it, "show") }
        .then(Commands.literal("new").executes { request(it, "new") })
        .then(Commands.literal("fill").executes { request(it, "fill") })
        .then(Commands.literal("clear").executes { request(it, "clear") })

    private fun door(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("door")
        .executes { door(it, null) }
        .then(Commands.literal("inn").executes { door(it, Door.INN) })
        .then(Commands.literal("bakery").executes { door(it, Door.BAKERY) })
        .then(
            Commands.literal("stamp")
                .then(Commands.literal("ideal").executes { stamp(it, "ideal", null) })
                .then(Commands.literal("far").executes { stamp(it, "far", null) })
                .then(Commands.literal("clear").executes { stamp(it, "clear", null) })
                .then(role("over"))
                .then(role("under")),
        )

    private fun role(how: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(how).then(
        Commands.argument("role", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(Role.entries.map { it.name.lowercase() }, b) }
            .executes { stamp(it, how, StringArgumentType.getString(it, "role")) },
    )

    private fun caps(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("caps").then(
        Commands.literal("reset")
            .executes { capsReset(it, false) }
            .then(Commands.literal("fees").executes { capsReset(it, true) }),
    )

    private fun owed(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("owed")
        .executes { owed(it, false) }
        .then(Commands.literal("pay").executes { owed(it, true) })

    // ---- Where it is run from ----

    /** The capital the player stands in, its ledger, its market (null while its places are still being worked out) and the town's day. */
    private class Here(val player: ServerPlayer, val level: ServerLevel, val capital: CapitalPiece, val kitchens: Kitchens, val entry: Markets.Entry?, val day: Long) {
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
        if (!Cooking.enabled()) say(source, grey("Hearthcraft is switched off: nothing is cooked, sold or judged, and the back doors are shut."))
        return Here(player, level, capital, Kitchens.get(level), Markets.of(level, capital), Cooking.today(level))
    }

    private fun place(h: Here): String = Suppliers.place(h.level, h.capital.center).ifEmpty { "the capital at ${h.capital.center.x}, ${h.capital.center.z}" }

    /** The player's standing here, by name ("stranger"), with "(standing is off)" when the switch is. */
    private fun rankOf(h: Here): String =
        SupplierRank.entries[Suppliers.rank(h.level, h.capital.center, h.player.uuid)].id + if (Suppliers.enabled()) "" else " (supplier standing is off, so the request is open to all)"

    // ---- cook kitchens ----

    private fun ledger(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val rec = h.kitchens.rec(h.key)
        val today = rec?.takeIf { it.day == h.day }
        val clock = Conditions(Markets.hourOf(h.level))
        say(source, header("Kitchens of ${place(h)}: day ${h.day}, ${"%02d:00".format(clock.clock)}" + if (clock.restDay) " (rest day)" else ""))
        say(
            source,
            line().append(label("Switches: ")).append(
                plain("Hearthcraft ${onOff(Cooking.enabled())}, back doors ${onOff(Cooking.doors())}, capital markets ${onOff(Markets.enabled())}, cookshops ${onOff(Cooking.shops())}"),
            ),
        )
        val wanted = h.kitchens.wanted(h.level, h.key, h.day)
        say(source, line().append(label("Wanted today: ")).append(plain(wanted.joinToString(", ") { it.id }.ifEmpty { "none (the back doors are off)" })))
        val ask = h.kitchens.request(h.level, h.key, h.day)
        say(
            source,
            line().append(label("Request: ")).append(
                plain(
                    if (ask == null) "none (the back doors are off)"
                    else "${ask.portions} ${ask.kind.id}, ${if (ask.filled) "filled" else "open"}; it goes to cooks of rank ${SupplierRank.entries[Requests.RANK].id} and above, you are ${rankOf(h)}",
                ),
            ),
        )
        val people = h.entry?.town?.people
        if (people == null) {
            say(source, grey("Its market is not open yet, so its caps cannot be told: try again in a second."))
        } else {
            for (door in listOf(Door.INN, Door.BAKERY)) {
                val sold = today?.sold?.get(door.ordinal)
                val kinds = DishKind.entries.filter { (sold?.get(it.ordinal) ?: 0) > 0 }.joinToString(", ") { "${it.id} ${sold!![it.ordinal]}" }
                say(
                    source,
                    line().append(label("${door.label()}: ")).append(
                        plain(
                            "${sold?.sum() ?: 0} of ${Doors.cap(door, people)} portions sold today (${Doors.kindCap(door, people)} of one kind at most); serves ${Doors.serving(door, people)} a meal" +
                                if (kinds.isNotEmpty()) "; sold: $kinds" else "",
                        ),
                    ),
                )
            }
            say(source, line().append(label("Alms table: ")).append(plain("serves ${Doors.serving(Door.ALMS, people)} a meal; the pot holds ${Doors.cap(Door.ALMS, people)}")))
        }
        for (door in listOf(Door.INN, Door.BAKERY, Door.ALMS)) {
            val lots = h.kitchens.lots(h.key, door)
            var text = lots.joinToString("; ") { "${it.portions} ${it.kind.id} ${it.tier.name.lowercase()} by ${it.cookName.ifEmpty { "nobody" }} (day ${if (it.day == NO_DAY) "?" else it.day.toString()})" }
            if (door == Door.ALMS && rec != null && rec.gruel > 0) text += (if (text.isEmpty()) "" else "; ") + "${rec.gruel} gruel"
            say(source, line().append(label("Waiting at the ${door.label().lowercase()}: ")).append(if (text.isEmpty()) grey("nothing") else plain(text)))
        }
        if (rec != null && rec.served.any { it > 0 }) {
            say(
                source,
                line().append(label("Served at the last three meals: ")).append(plain(DishKind.entries.filter { rec.served[it.ordinal] > 0 }.joinToString(", ") { "${it.id} ${rec.served[it.ordinal]}" })),
            )
        }
        h.entry?.let { entry ->
            val season = Requests.season(h.level, h.day)
            say(
                source,
                line().append(label("Prices: ")).append(
                    plain(
                        "index x${n2(Index.of(rec, entry))} (${Index.seen(rec)} of ${Pricing.WINDOW} hours seen), season ${season?.name?.lowercase() ?: "off"}, " +
                            "palate of a ${h.capital.style.name.lowercase()} town, plain meal ${n2(Pricing.M_BASE)} Marks",
                    ),
                ),
            )
        }
        val slips = h.kitchens.owing.values.sumOf { it.size }
        val marks = h.kitchens.owing.values.sumOf { list -> list.sumOf { it.marks } }
        say(
            source,
            line().append(label("Owed: ")).append(plain("$slips slips, $marks Marks in all, ${Owed.total(h.kitchens, h.player.uuid)} of them yours; ${Owed.inScenes()} in scenes now")),
        )
        if (rec != null) {
            val me = h.player.uuid
            val taken = listOf(Door.INN, Door.BAKERY).joinToString("; ") { d ->
                "${d.label().lowercase()} took ${if (today != null) Doors.taken(today, d, me) else 0}, carry ${n2(Doors.carried(rec, d, me))}"
            }
            val tasted = DishKind.entries.filter { Doors.tasted(rec, me, it) }.joinToString(", ") { it.id }.ifEmpty { "none" }
            val others = listOf(Door.INN, Door.BAKERY).filter { Doors.shared(rec, it, me, h.day) }.joinToString(", ") { it.label().lowercase() }.ifEmpty { "none" }
            say(source, line().append(label("You: ")).append(plain("$taken; first-of-kind fee had for: $tasted; other cooks about at: $others")))
        }
        return 1
    }

    private fun run(context: CommandContext<CommandSourceStack>, hours: Int): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val entry = h.entry ?: return fail(source, "Its market is not open yet (its places are still being worked out): try again in a second.")
        val m = entry.market
        val from = m.hour
        val meals = Pantry.meals
        val portions = Pantry.portions
        for (i in 1..hours) Markets.step(h.level, entry, from + i)
        // Then back to the clock's hour, as if those hours had been lived already
        Markets.rewind(m, hours.toLong())
        Markets.get(h.level).changed()
        return say(
            source,
            plain(
                "Worked out $hours hours of the market at once, the kitchens' meal hours with them: ${Pantry.portions - portions} portions served at ${Pantry.meals - meals} meals " +
                    "(over every capital with a ledger). It carries on from the clock's hour; `cook kitchens` shows what is left.",
            ),
        )
    }

    // ---- cook request ----

    private fun request(context: CommandContext<CommandSourceStack>, what: String): Int {
        val source = context.source
        val h = here(context) ?: return 0
        if (!Cooking.doors()) return fail(source, "The back doors are switched off (Kitchen Back Doors, Hearthcraft or Capital Markets): there is no request.")
        if (what != "show") {
            val rec = h.kitchens.rolled(h.key, h.day, create = true) ?: return 0
            val salt = rec.asked.takeIf { it.day == h.day }?.salt ?: 0
            rec.asked = when (what) {
                "new" -> Asked(h.day, salt + 1, false, null)
                "fill" -> Asked(h.day, salt, true, h.player.uuid)
                else -> Asked(NO_DAY, 0, false, null)
            }
            h.kitchens.changed()
        }
        val ask = h.kitchens.request(h.level, h.key, h.day) ?: return 0
        val said = when (what) {
            "new" -> "Another request is drawn. "
            "fill" -> "The request is marked filled. "
            "clear" -> "Back to the seed's own request, unfilled. "
            else -> ""
        }
        return say(
            source,
            line().append(label("Request of day ${h.day}: ")).append(
                plain(
                    said + "${ask.portions} ${ask.kind.id}, ${if (ask.filled) "filled" else "open"} (the inn pays ${Doors.REQUEST} x the value, to the first cook of rank " +
                        "${SupplierRank.entries[Requests.RANK].id} or above who fills it; you are ${rankOf(h)})",
                ),
            ),
        )
    }

    // ---- cook door ----

    private fun door(context: CommandContext<CommandSourceStack>, which: Door?): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val stack = h.player.mainHandItem
        if (stack.item !is DishItem) return fail(source, "Hold a dish in your main hand first.")
        val door = which ?: nearestDoor(h.player)
        val deal = Selling.quote(h.level, h.kitchens, h.key, door, h.player, stack, h.capital.style)
        say(source, header("At the ${door.label().lowercase()}'s back door, with ${stack.count} in your hand"))
        deal.kind?.let { say(source, line().append(label("Dish: ")).append(plain("${it.id}, ${deal.tier.name.lowercase()}, ${deal.stage.name.lowercase()}"))) }
        val refusal = deal.refusal
        if (refusal != null) return say(source, line().append(label("Refused: ")).append(plain(why(refusal))))
        val kind = deal.kind ?: return 0
        say(
            source,
            line().append(label("Would take: ")).append(
                plain(
                    "${deal.taken} of ${deal.hand}" + (if (deal.request > 0) " (${deal.request} for the request, ${deal.atDoor} at the door)" else "") +
                        " for ${deal.marks} Marks (${n2(deal.total)} earned, ${n2(deal.carry)} carried in, ${n2(deal.kept)} carried on). The door had room for ${deal.room} more of it today" +
                        if (deal.full) ", and has none after this." else ".",
                ),
            ),
        )
        say(
            source,
            line().append(label("One portion's value: ")).append(
                plain(
                    "${n2(Pricing.M_BASE)} plain meal x ${kind.factor} ${kind.id} x ${deal.tier.value} ${deal.tier.name.lowercase()} x ${n2(deal.index)} index x ${n2(deal.season)} season x " +
                        "${n2(deal.palate)} palate x ${n2(Pricing.freshness(deal.stage))} freshness = ${n3(deal.value)} Marks",
                ),
            ),
        )
        val perDoor = deal.value * deal.share * (if (deal.wanted) 1.0 else Doors.UNWANTED) * (if (deal.first) Doors.FIRST else 1.0)
        say(
            source,
            line().append(label("The door pays: ")).append(
                plain(
                    "${n3(perDoor)} a portion = value x ${n2(deal.share)} (standing rank ${deal.rank})" + (if (!deal.wanted) " x ${Doors.UNWANTED} (not a wanted kind)" else "") +
                        (if (deal.first) " x ${Doors.FIRST} (the first of its kind from you here)" else "") +
                        if (deal.request > 0) "; the request pays ${n3(deal.value * Doors.REQUEST)} a portion (${Doors.REQUEST} x value)" else "",
                ),
            ),
        )
        if (deal.locked) {
            say(
                source,
                line().append(label("The request: ")).append(
                    plain("this is today's request, but you are ${SupplierRank.entries[deal.rank].id} and requests go to ${SupplierRank.entries[Requests.RANK].id} and above (${SupplierRank.entries[Requests.RANK].points} points), so it sells as any dish."),
                ),
            )
        } else if (deal.asked && deal.request == 0) {
            say(source, line().append(label("The request: ")).append(plain("this is today's request, but it takes ${Requests.PORTIONS} at once and you hold ${deal.hand}, so it sells as any dish.")))
        }
        DishMix.of(stack)?.let { mix ->
            val v = Hints.verdict(h.level, kind, mix)
            say(source, line().append(label("The keeper would say: ")).append(plain(verdict(v.role, v.over, v.items, v.close))))
        } ?: say(source, grey("The dish carries no mix, so the keeper tastes it by its tier alone (`cook door stamp ideal` gives it one)."))
        return 1
    }

    private fun verdict(role: Role, over: Boolean, items: Double, close: Int): String = when {
        close >= 3 -> "right on (close 3)"
        close <= 0 -> "far off (close 0)"
        else -> "too ${if (over) "much" else "little"} ${role.name.lowercase()}, by ${n2(items)} items (close $close)"
    }

    /** The door of the innkeeper or baker nearest [player] within [NEAR] blocks, else the inn. */
    private fun nearestDoor(player: ServerPlayer): Door {
        val keepers = player.level().getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(NEAR)) { it.role == TownRole.INNKEEPER || it.role == TownRole.BAKER }
        val keeper = keepers.minByOrNull { it.distanceToSqr(player) } ?: return Door.INN
        return if (keeper.role == TownRole.BAKER) Door.BAKERY else Door.INN
    }

    private fun why(refusal: Refusal): String = when (refusal) {
        Refusal.NOT_A_DISH -> "that is not a dish a door buys (Gruel and a Scorched mess are the alms table's)"
        Refusal.DOORS_OFF -> "the back doors are off (Kitchen Back Doors, Hearthcraft or Capital Markets), or this capital has no market open"
        Refusal.SPOILT -> "a spoilt batch is not a dish anyone buys"
        Refusal.GONE_OFF -> "it has gone off"
        Refusal.WRONG_DOOR -> "this door does not buy that kind"
        Refusal.FULL -> "the door has taken all it will of this kind today (or all that you may take with another cook about)"
    }

    // ---- cook door stamp ----

    /** Gives the dish in hand the mix `how` says: the world's ideal, far from it, one role over or under it, or none. */
    private fun stamp(context: CommandContext<CommandSourceStack>, how: String, role: String?): Int {
        val source = context.source
        val player = source.playerOrException
        val stack = player.mainHandItem
        val kind = Dishes.kindOf(stack) ?: return fail(source, "Hold a dish of one of the twelve kinds in your main hand.")
        if (how == "clear") {
            DishMix.clear(stack)
            return say(source, plain("The mix is taken off: a keeper tastes this by its tier alone."))
        }
        val r = role?.let { name -> Role.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return fail(source, "No such role: $name (${Role.entries.joinToString(", ") { it.name.lowercase() }}).") }
        val ideal = Ideal.of(source.level, kind)
        val mix = when (how) {
            "ideal" -> ideal
            "over" -> moved(ideal, r?.ordinal ?: 0, STEP)
            "under" -> moved(ideal, r?.ordinal ?: 0, -STEP)
            else -> far(ideal)
        }
        DishMix.stamp(stack, mix)
        val v = Hints.verdict(source.level, kind, mix)
        return say(source, plain("The ${kind.id} in your hand now carries a mix: a keeper would say ${verdict(v.role, v.over, v.items, v.close)}."))
    }

    /** [ideal] with [by] of the whole moved onto [role] from the others in proportion (a negative [by]: off it, onto the others). */
    private fun moved(ideal: DoubleArray, role: Int, by: Double): DoubleArray {
        val others = 1.0 - ideal[role]
        if (others <= 1.0e-9) return ideal.copyOf()
        val d = if (by >= 0.0) minOf(by, others) else -minOf(-by, ideal[role])
        return DoubleArray(ideal.size) { i -> if (i == role) ideal[i] + d else ideal[i] - d * ideal[i] / others }
    }

    /** A mix far from [ideal]: half of it, and the other half all in the role the ideal has least of. */
    private fun far(ideal: DoubleArray): DoubleArray {
        val least = ideal.indices.minByOrNull { ideal[it] } ?: 0
        return DoubleArray(ideal.size) { if (it == least) 0.5 + 0.5 * ideal[it] else 0.5 * ideal[it] }
    }

    // ---- cook caps ----

    private fun capsReset(context: CommandContext<CommandSourceStack>, fees: Boolean): Int {
        val source = context.source
        val h = here(context) ?: return 0
        val rec = h.kitchens.rolled(h.key, h.day) ?: return say(source, grey("Nothing has been sold here: there are no caps to reset."))
        val sold = rec.sold.sumOf { row -> row.sum() }
        for (row in rec.sold) row.fill(0)
        rec.mine.clear()
        for (seen in rec.seen) seen.clear()
        if (fees) {
            rec.carry.remove(h.player.uuid)
            rec.taster.remove(h.player.uuid)
        }
        h.kitchens.changed()
        return say(
            source,
            plain(
                "Today's counters at the inn and the bakery are cleared ($sold portions) and nobody counts as a cook about." +
                    if (fees) " Your carries and first-of-kind fees are cleared too." else " Carries and first-of-kind fees are kept (`cook caps reset fees` clears yours).",
            ),
        )
    }

    // ---- cook owed ----

    private fun owed(context: CommandContext<CommandSourceStack>, pay: Boolean): Int {
        val source = context.source
        val level = source.level
        val kitchens = Kitchens.get(level)
        if (pay) {
            val player = source.playerOrException
            val before = Owed.total(kitchens, player.uuid)
            if (before == 0) return say(source, grey("Nothing is owed to you."))
            val paid = Owed.all(player)
            return say(source, plain("Paid you $paid of the $before Marks owed, each slip with its line."))
        }
        if (kitchens.owing.isEmpty()) return say(source, grey("Nothing is owed to anyone."))
        say(source, header("Owed at the back doors"))
        for ((uuid, slips) in kitchens.owing.entries.sortedBy { it.key }) {
            val what = slips.joinToString("; ") { "${it.marks} for ${it.kind?.id ?: "dishes"} at the ${it.door.label().lowercase()}" }
            say(source, line().append(label("${nameOf(level, uuid)}: ")).append(plain("${slips.sumOf { it.marks }} Marks in ${slips.size} slips ($what)")))
        }
        return say(source, grey("${Owed.inScenes()} of them are in scenes now. `cook owed pay` pays yours."))
    }

    private fun nameOf(level: ServerLevel, id: UUID): String =
        level.server.playerList.getPlayer(id)?.name?.string ?: level.server.services().nameToIdCache().get(id).map { it.name }.orElse(id.toString().take(8))

    // ---- Words ----

    private fun Door.label(): String = when (this) {
        Door.INN -> "Inn"
        Door.BAKERY -> "Bakery"
        Door.SHOP -> "Cookshop"
        Door.ALMS -> "Alms table"
    }

    private fun n2(x: Double): String = String.format(Locale.ROOT, "%.2f", x)
    private fun n3(x: Double): String = String.format(Locale.ROOT, "%.3f", x)

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
