package io.github.vaspyyy.guildmark.village.live.market

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.village.Festivals
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster.NONE
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark village cart [call|come|skip|clear]` (the villages design, section 15.2), run by operators in a village, or beside one:
 *
 * - bare: the village's cart and its pedlar: who the pedlar is, whether they come today and why (or why not), when they last called and
 *   what the call took and brought, when the round next falls, the visits lost, the wagon, whether the pedlar is at the green now and
 *   what they are at, and how many days of the market's last the village went hungry;
 * - `call` makes the cart's call now, in the record (the village's goods are taken and brought as at nine), whether or not anyone is seen;
 * - `come` makes today a pedlar day for the village (the pedlar walks in from half past eight; past nine, the call is made at once and
 *   the pedlar walks in as soon as nobody would see them appear);
 *
 * The words are the plain English of the other `village` commands. The pedlar is "they" in all of them.
 * - `skip` holds the next visit back: it is lost, as a band near would lose it, and the village's talk and this command say so;
 * - `clear` sends every pedlar off.
 *
 * Each ends in plain words: what a number is and where it came from.
 */
object VillageCartCommand {
    /** How far (blocks) the nearest village is looked for when the sender is not in one. */
    private const val NEAREST = 128

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        VillageInfoCommand.section { level, rec -> lines(level, rec) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("village")
                        .then(
                            Commands.literal("cart").executes { show(it) }
                                .then(Commands.literal("call").executes { call(it) })
                                .then(Commands.literal("come").executes { come(it) })
                                .then(Commands.literal("skip").executes { skip(it) })
                                .then(Commands.literal("clear").executes { clear(it) }),
                        ),
                ),
        )
    }

    // ---- The commands ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec, v) = target(source) ?: return 0
        say(source, header("The cart of ${rec.name}"))
        for (c in report(level, rec, v)) say(source, c)
        say(
            source,
            grey(
                "Pedlars come every 2 to 4 days and always on the market day, never on the rest day; a wagon that called in the last " +
                    "${PedlarRoster.WAGON_DAYS} days leaves market day alone; a closed road or a band near (one visit in ${PedlarRoster.BAND_ONE_IN}) keeps them off. " +
                    "'call' makes the call now, 'come' makes today a pedlar day, 'skip' holds the next visit back, 'clear' sends the pedlars off.",
            ),
        )
        return 1
    }

    private fun call(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec, v) = target(source) ?: return 0
        val day = Days.dayOf(level.overworldClockTime)
        val ex = VillageMarkets.call(v, v.entry.market, v.entry.town, Markets.hourOf(level), day, "command")
        VillageMarkets.changed(level, v.entry)
        say(source, line().append(label("Called on ${rec.name}: ")).append(plain("took ${goods(v.cart.took)}; brought ${goods(v.cart.brought)}")))
        say(source, grey("(${ex.taken().size} goods left the village, ${ex.brought().size} came; the market's own hours carry on from here)"))
        return 1
    }

    private fun come(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec, v) = target(source) ?: return 0
        val cart = v.cart
        if (!VillageMarkets.pedlars()) return fail(source, "Pedlars are off just now (settings: Capital Markets, Living Villages, Village Markets and Village Pedlar are all needed).")
        val time = level.overworldClockTime
        val day = Days.dayOf(time)
        if (cart.decided == day && cart.comes) {
            say(source, line().append(label("The pedlar is due today already: ")).append(plain(why(level, rec, cart))))
            return 1
        }
        cart.decided = day
        cart.comes = true
        cart.why = PedlarRoster.Why.ROUND.id
        cart.away = NONE
        cart.calls++
        cart.next = day + PedlarRoster.gap(rec.key, cart.calls)
        val clock = Pedlars.clock(level)
        if (clock >= PedlarRoster.SET_UP && cart.called != day) {
            VillageMarkets.call(v, v.entry.market, v.entry.town, Markets.hourOf(level), day, "command")
            say(source, line().append(label("The pedlar comes today. ")).append(plain("It is past nine: the call was made now, took ${goods(cart.took)}, brought ${goods(cart.brought)}.")))
        } else {
            say(source, line().append(label("The pedlar comes today. ")).append(plain("The call is made at nine; they walk in from half past eight.")))
        }
        VillageMarkets.changed(level, v.entry)
        return 1
    }

    private fun skip(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (level, rec, v) = target(source) ?: return 0
        v.cart.held++
        VillageMarkets.changed(level, v.entry)
        say(source, line().append(label("The next visit to ${rec.name} is held back")).append(plain(" (${v.cart.held} held): the pedlar does not come, the village goes without the call, and it is said so.")))
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val n = Pedlars.alive()
        Pedlars.dismissAll()
        say(source, line().append(label("Pedlars sent off: ")).append(plain("$n; they go out by the road and are gone once nobody looks.")))
        return n
    }

    // ---- What is said ----

    /** The cart's lines for `village cart`. */
    private fun report(level: ServerLevel, rec: VillageRec, v: VillageMarkets.Rec): List<Component> {
        val cart = v.cart
        val out = ArrayList<Component>()
        val day = Days.dayOf(level.overworldClockTime)
        val person = PedlarRoster.of(rec.key, cart.slain)
        out += line().append(label("Pedlar: ")).append(plain("${person.name}, one of the four who walk the roads of this part of the country (region ${PedlarRoster.region(rec.key).let { "${it.first}, ${it.second}" }})"))
        out += line().append(label("Today (day $day): ")).append(plain(why(level, rec, cart)))
        Pedlars.present(rec.key)?.let { (who, doing) -> out += line().append(label("At the green: ")).append(plain("${who.name}, $doing")) }
        if (cart.lastHour == NONE) {
            out += line().append(label("Last call: ")).append(plain("none yet"))
        } else {
            val c = Conditions(cart.lastHour)
            out += line().append(label("Last call: ")).append(plain("day ${c.day} at ${"%02d".format(c.clock)}:00, by ${cart.by.ifEmpty { "the pedlar" }}: took ${goods(cart.took)}; brought ${goods(cart.brought)}"))
        }
        out += line().append(label("Next round: ")).append(plain(if (cart.next == NONE) "not fixed yet (the first visit is on the first day the pedlar is wanted)" else "day ${cart.next}")).append(plain("   visits so far ${cart.calls}"))
        if (cart.missed != NONE) {
            out += line().append(label("Last visit lost: ")).append(plain("day ${cart.missed}" + (PedlarRoster.Why.of(cart.why)?.takeIf { !it.comes }?.let { " (${it.id})" } ?: "")))
        }
        if (cart.held > 0) out += line().append(label("Held back: ")).append(plain("${cart.held} visit${if (cart.held == 1) "" else "s"}"))
        if (cart.slain > 0) out += line().append(label("Pedlars killed here: ")).append(plain("${cart.slain}" + if (cart.away != NONE && day < cart.away) ", the place is empty until day ${cart.away}" else ""))
        if (cart.wagon != NONE) {
            val c = Conditions(cart.wagon)
            out += line().append(label("Wagon last called: ")).append(plain("day ${c.day}"))
        }
        if (rec.links.isNotEmpty()) {
            out += line().append(label("Roads: ")).append(plain("${rec.links.size} to a capital"))
        } else {
            out += line().append(label("Roads: ")).append(plain("none: the pedlar is the village's only cart"))
        }
        val m = v.entry.market
        if (m.hungry.isNotEmpty()) {
            out += line().append(label("Hungry days: ")).append(plain("${m.hungry.count { it > HUNGRY_DAY }} of the last ${m.hungry.size}")).append(grey(" (a day on which more than half a meal went short)"))
        }
        out += line().append(label("Pedlars about: ")).append(plain("${Pedlars.alive()} of ${Pedlars.MAX} allowed"))
        return out
    }

    /** The lines `village info` adds: the village's market and its cart in two. */
    private fun lines(level: ServerLevel, rec: VillageRec): List<Component> {
        val v = VillageMarkets.rec(level, rec.key) ?: return if (VillageMarkets.enabled()) listOf(grey("Market: not opened yet (it opens once the census is done)")) else emptyList()
        val m = v.entry.market
        val cart = v.cart
        val out = ArrayList<Component>()
        out += line().append(label("Market: ")).append(plain("${v.entry.town}")).append(
            plain(if (m.hungry.isNotEmpty()) "; hungry ${m.hungry.count { it > HUNGRY_DAY }} of the last ${m.hungry.size} days" else ""),
        )
        val person = PedlarRoster.of(rec.key, cart.slain)
        out += line().append(label("Pedlar: ")).append(plain("${person.name}; ${why(level, rec, cart)}" + if (cart.next != NONE) "; next round day ${cart.next}" else ""))
        return out
    }

    /** Whether the pedlar comes today and why, in plain words. */
    private fun why(level: ServerLevel, rec: VillageRec, cart: VillageMarkets.Cart): String {
        val day = Days.dayOf(level.overworldClockTime)
        val known = if (cart.decided == day) PedlarRoster.Why.of(cart.why) else null
        val market = Festivals.isMarketDay(VillageMarkets.anchor(rec), day)
        val verdict = known ?: when {
            !VillageMarkets.pedlars() -> PedlarRoster.Why.OFF
            Days.restDay(day) -> PedlarRoster.Why.REST
            market -> PedlarRoster.Why.MARKET
            PedlarRoster.round(day, cart.next) -> PedlarRoster.Why.ROUND
            else -> PedlarRoster.Why.NOT_DUE
        }
        val settled = known != null
        val first = when (verdict) {
            PedlarRoster.Why.ROUND -> "the pedlar comes, it is the round's day"
            PedlarRoster.Why.MARKET -> "the pedlar comes, it is the market day"
            PedlarRoster.Why.REST -> "no pedlar, it is the rest day"
            PedlarRoster.Why.NOT_DUE -> "no pedlar, it is not the round's day"
            PedlarRoster.Why.WAGON -> "no pedlar, a wagon called lately and the road is the village's supply"
            PedlarRoster.Why.CLOSED -> "no pedlar, the road is closed"
            PedlarRoster.Why.BAND -> "no pedlar, a band camped near kept them off"
            PedlarRoster.Why.HELD -> "no pedlar, a visit was held back"
            PedlarRoster.Why.SLAIN -> "no pedlar, the last pedlar was killed" + if (cart.away != NONE) " and the place is empty until day ${cart.away}" else ""
            PedlarRoster.Why.OFF -> "no pedlar, pedlars are off"
        }
        return if (settled || verdict == PedlarRoster.Why.REST || verdict == PedlarRoster.Why.OFF) first else "$first (not decided yet: wagons, roads and bands are read when the day begins)"
    }

    /** Goods by name and how many, the most first; "nothing" for none. */
    private fun goods(map: Map<String, Double>): String =
        if (map.isEmpty()) "nothing" else map.entries.sortedByDescending { it.value }.joinToString(", ") { "%.0f %s".format(it.value, it.key) }

    // ---- Which village ----

    /** The village the sender stands in, or the nearest bell within [NEAREST] blocks, with its market; null, having said so, if there is none. */
    private fun target(source: CommandSourceStack): Triple<ServerLevel, VillageRec, VillageMarkets.Rec>? {
        val level = source.level
        if (level.dimension() != Level.OVERWORLD) {
            fail(source, "Villages are counted in the overworld only.")
            return null
        }
        if (!VillageMarkets.enabled()) {
            fail(source, "Villages have no markets just now (settings: Capital Markets, Living Villages and Village Markets are all needed).")
            return null
        }
        val here = BlockPos.containing(source.position)
        val rec = Villages.at(level, here) ?: Villages.near(level, here, NEAREST).firstOrNull()
        if (rec == null) {
            fail(source, "No village within $NEAREST blocks.")
            return null
        }
        val v = VillageMarkets.rec(level, rec.key)
        if (v == null) {
            fail(source, "${rec.name} has no market yet: it opens once its census is done (try /guildmark village census now).")
            return null
        }
        return Triple(level, rec, v)
    }

    // ---- Words ----

    /** A day on which more than this many meals went short is a hungry day (as the villages design's model counts it, section 18). */
    private const val HUNGRY_DAY = 0.5

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GOLD)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
