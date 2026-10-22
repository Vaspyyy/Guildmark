package io.github.vaspyyy.guildmark.cooking.town

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.ServedPayload
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.market.Pantry
import io.github.vaspyyy.guildmark.economy.Suppliers
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Locale
import kotlin.math.min

/**
 * `/guildmark cook served <kind> <n> [tier]` (operators; living-world/cook/design.md 7.4, 10.3): pretends the inn of the
 * capital you stand in was served [n] portions of [kind] (at [tier], Sound unless said), so the town's diners and plates can
 * be tried before a real day's sales have been served. It sends the players within 128 blocks of the capital the payload
 * the ledger sends at a meal hour (`Pantry.announce`), with a made-up [TEST_WINDOW] for the clients to know it by, and
 * changes nothing on the server: no lot, no market, no favour, nothing saved. Run again with another kind it adds that
 * kind to the same made-up meal (so a stew for the tavern's tables and a pie for the benches can both stand); `<n>` of 0
 * takes a kind off, and `cook served clear` takes them all. A made-up meal is shown for [TEST_TICKS] (twelve minutes) or
 * until the next meal hour's real one replaces it. It works in any capital's tavern as it stands.
 *
 * The one `cook perf` line of Part I3 is registered here ([register]); the plates themselves are drawn by the client, whose
 * own figures reach the line through [clientStats] (set by the client when it starts, so a dedicated server names none of it).
 */
object ServedCommand {
    /**
     * The windows a made-up meal carries: [TEST_WINDOW] and the [TEST_SPAN] after it, a number that goes up each time the meal
     * is changed. No real meal's window is ever near them (a real one is a town day's third, and counts up from 0), so a client
     * knows the meal for a test; and as the diners are drawn by the window, every client shown the same made-up meal draws the
     * same diners, a player who is told it later (the command run again) among them.
     */
    const val TEST_WINDOW = Int.MIN_VALUE
    const val TEST_SPAN = 1_000_000

    /** Whether [window] is a made-up meal's. */
    fun isTest(window: Int): Boolean = window < TEST_WINDOW + TEST_SPAN

    /** Game ticks a client shows a made-up meal for from the moment it arrives (twelve minutes). */
    const val TEST_TICKS = 14_400L

    /** How far from a capital's centre a player is shown the meal (the ledger's own range, `Pantry`'s). */
    private const val RANGE = 128.0

    /** The most portions one command may pretend. */
    private const val MOST = 200

    /** The client's figures for `cook perf` (set by the client's TownPlates; null on a dedicated server and until then). */
    @Volatile
    var clientStats: (() -> String)? = null

    /**
     * A capital's made-up meal as the commands have built it: portions and best tier of each kind, the game time of the last
     * command, and the version of it (which of the windows it is told at; it goes up when the portions change, so the clients
     * draw the diners afresh, and stays when the same meal is told again, so they keep them).
     */
    private class Test(var at: Long, val counts: IntArray, val best: ByteArray) {
        var version = 0
    }

    private val tests = HashMap<Long, Test>()
    private var sent = 0
    private var versions = 0

    init {
        Cooking.onForget {
            tests.clear()
            sent = 0
        }
    }

    /** Part I3's registration: the `served` branch under `cook`, and the part's one line for `cook perf`. */
    fun register() {
        CookStats.readout("Town plates") { stats() }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun stats(): String = "$sent made-up meals sent; " + (clientStats?.invoke() ?: "the plates are drawn by the client (none here)")

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cook")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(served()),
                ),
        )
    }

    private fun served(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("served")
        .then(Commands.literal("clear").executes { clear(it) })
        .then(
            Commands.argument("kind", StringArgumentType.word())
                .suggests { _, b -> SharedSuggestionProvider.suggest(DishKind.entries.map { it.id }, b) }
                .then(
                    Commands.argument("n", IntegerArgumentType.integer(0, MOST))
                        .executes { serve(it, null) }
                        .then(
                            Commands.argument("tier", StringArgumentType.word())
                                .suggests { _, b -> SharedSuggestionProvider.suggest(TIERS.map { it.name.lowercase(Locale.ROOT) }, b) }
                                .executes { serve(it, StringArgumentType.getString(it, "tier")) },
                        ),
                ),
        )

    /** The tiers a meal may be told at: the four a dish is sold at, not Spoilt. */
    private val TIERS: List<Tier> = listOf(Tier.PLAIN, Tier.SOUND, Tier.FINE, Tier.HALLMARKED)

    // ---- Where it is run from ----

    /** The capital the player stands in: its key (its centre, as the ledger keys it), its inn's box, and its name. */
    private class Here(val key: Long, val box: BoundingBox, val name: String)

    private fun here(context: CommandContext<CommandSourceStack>): Here? {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val capital = Capitals.covering(level, player.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val key = capital.center.asLong()
        val box = Pantry.innBox(level, key)
        if (box == null) {
            fail(source, "This capital's tavern is not known yet (its places are being worked out): try again in a second.")
            return null
        }
        val name = Suppliers.place(level, capital.center).ifEmpty { "the capital at ${capital.center.x}, ${capital.center.z}" }
        return Here(key, box, name)
    }

    // ---- cook served ----

    private fun serve(context: CommandContext<CommandSourceStack>, tierName: String?): Int {
        val source = context.source
        val word = StringArgumentType.getString(context, "kind").lowercase(Locale.ROOT)
        val kind = DishKind.byId(word) ?: return fail(source, "No such dish: $word. The kinds are ${DishKind.entries.joinToString(", ") { it.id }}.")
        val n = IntegerArgumentType.getInteger(context, "n")
        val tier = if (tierName == null) Tier.SOUND else TIERS.firstOrNull { it.name.equals(tierName, true) || (it == Tier.HALLMARKED && tierName.equals("hallmark", true)) }
            ?: return fail(source, "No such tier: $tierName. The tiers are ${TIERS.joinToString(", ") { it.name.lowercase(Locale.ROOT) }}.")
        val here = here(context) ?: return 0
        val now = source.level.gameTime
        val test = tests[here.key]?.takeIf { now - it.at <= TEST_TICKS && now >= it.at } ?: Test(now, IntArray(DishKind.entries.size), ByteArray(DishKind.entries.size) { (-1).toByte() }).also { tests[here.key] = it }
        test.at = now
        val best = if (n > 0) tier.ordinal.toByte() else (-1).toByte()
        // A changed meal is a new version of it (the diners are drawn afresh); the same meal told again is the same one
        if (test.version == 0 || test.counts[kind.ordinal] != n || test.best[kind.ordinal] != best) {
            versions = versions % (TEST_SPAN - 1) + 1
            test.version = versions
        }
        test.counts[kind.ordinal] = n
        test.best[kind.ordinal] = best
        val shown = tell(source, here, test)
        val total = test.counts.sum()
        if (total == 0) tests.remove(here.key)
        say(
            source,
            line().append(label("Served at ${here.name}: ")).append(
                plain(
                    if (n == 0) "${kind.id} taken off the made-up meal" + if (total == 0) ", which is now empty, so the diners eat as they always have." else "."
                    else "$n ${kind.id} (${tier.name.lowercase(Locale.ROOT)}) added to it.",
                ),
            ),
        )
        if (total > 0) report(source, test, shown)
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val here = here(context) ?: return 0
        val test = tests.remove(here.key) ?: Test(source.level.gameTime, IntArray(DishKind.entries.size), ByteArray(DishKind.entries.size) { (-1).toByte() })
        test.counts.fill(0)
        test.best.fill((-1).toByte())
        val shown = tell(source, here, test)
        say(source, line().append(label("Served at ${here.name}: ")).append(plain("the made-up meal is cleared, so the diners eat as they always have ($shown players told).")))
        return 1
    }

    /** Sends [test] as the capital's meal to every player within range of it, and how many that was. */
    private fun tell(source: CommandSourceStack, here: Here, test: Test): Int {
        val level = source.level
        val centre = BlockPos.of(here.key)
        var told = 0
        for (player in level.players()) {
            val dx = player.x - (centre.x + 0.5)
            val dz = player.z - (centre.z + 0.5)
            if (dx * dx + dz * dz > RANGE * RANGE) continue
            PacketDistributor.sendToPlayer(player, ServedPayload(here.key, TEST_WINDOW + test.version, here.box, test.counts.copyOf(), test.best.copyOf()))
            told++
        }
        sent++
        return told
    }

    /** What the made-up meal comes to for the tables and for the benches, in the chances the diners are drawn by. */
    private fun report(source: CommandSourceStack, test: Test, told: Int) {
        val tavern = DishKind.entries.filter { Door.INN in it.doors }.sumOf { test.counts[it.ordinal] }
        val baked = DishKind.entries.filter { Door.BAKERY in it.doors }.sumOf { test.counts[it.ordinal] }
        val parts = ArrayList<String>()
        if (tavern > 0) parts += "the tavern's sitters: $tavern portions, about ${percent(tavern)} of them eat"
        if (baked > 0) parts += "lunch-eaters out of doors: $baked baked portions, about ${percent(baked)} of them eat a slice"
        say(
            source,
            line().append(grey("${parts.joinToString("; ").ifEmpty { "nothing the tables or the benches serve" }}. $told players within ${RANGE.toInt()} blocks are shown it for ${TEST_TICKS / 1200} minutes.")),
        )
        if (!Cooking.townKitchens()) say(source, grey("Town Kitchens is switched off, so no plates show until it is on (and each player's Plates at the Tables option)."))
    }

    /** The chance a sitter is one of the diners for [portions] served (design 7.4): `min(0.8, 0.15 + 0.05 x portions)`, as a percentage. */
    private fun percent(portions: Int): String = "${(min(0.8, 0.15 + 0.05 * portions) * 100).toInt()}%"

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)

    private fun say(source: CommandSourceStack, c: Component): Int {
        source.sendSuccess({ c }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
