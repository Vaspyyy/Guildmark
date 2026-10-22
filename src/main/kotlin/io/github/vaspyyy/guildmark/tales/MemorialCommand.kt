package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

/**
 * `/guildmark tale memorial ...` (living-world/stories/design.md 12.4, Part F), game masters only: the stones and cairns the town keeps.
 *
 * - `list`: every memorial of this dimension with its names, its place, whether it is set, waiting or found no room, and what stands on it.
 * - `place <stone|cairn> <name[, name]>`: sets a memorial for the names where you stand, by the same rules as the tales' own (nearest first
 *   within 16 blocks, 24 for a cairn): natural level ground, not in a building, road, crop or water, no door close, turned square to the
 *   street, room before it for the lantern and the one who kneels, who is not walled in. If no place near you passes, nothing is set and
 *   the count of why each place was refused is said. In a capital it is one of its six; out in the open it belongs to no town.
 * - `town <stone|cairn> <name[, name]>`: the same, asked as a tale that names no place asks: from where the kind belongs in the capital you
 *   stand in (a stone at a temple's door, round its lot's corners, by a well, in the market; a cairn by the road out of a gate first), each
 *   of them near and then far, and the reply says which of them gave the place.
 * - `remove <id>`: takes a memorial away, with the lantern and the pot the Laying set for it, where their chunks are loaded.
 * - `posy <id>`: lays a posy on the stone as the Laying does, now; `candles <id> <0-4> [lit]` sets candles on its ledge (lit till dawn).
 * - `lay <id>`: plays the Laying at that stone now, as `tale scene laying` does at a stand-in: whoever is free near it kneels two blocks from
 *   it and lays the posy on its ledge, a priest prays, one embraces her, and at dusk (17:00 to 22:00) a lantern is set before it.
 *
 * The ones that write say why they will not, and change nothing, while tales or the memorials are off; `list` always answers.
 */
internal object MemorialCommand {
    /** The most memorials `list` shows, and the most names `place` cuts. */
    private const val LISTED = 24
    private const val NAMES = 8

    /** Appends the `memorial` subcommand to `tale` (one line in [Memorials.register]). */
    fun register() {
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("memorial")
                    .executes { list(it) }
                    .then(Commands.literal("list").executes { list(it) })
                    .then(placing("place", false))
                    .then(placing("town", true))
                    .then(Commands.literal("remove").then(id().executes { remove(it) }))
                    .then(Commands.literal("posy").then(id().executes { posy(it) }))
                    .then(Commands.literal("lay").then(id().executes { lay(it) }))
                    .then(
                        Commands.literal("candles").then(
                            id().then(
                                Commands.argument("count", IntegerArgumentType.integer(0, MemorialBlock.MAX_CANDLES))
                                    .executes { candles(it, false) }
                                    .then(Commands.literal("lit").executes { candles(it, true) }),
                            ),
                        ),
                    ),
            )
        }
    }

    /** `place` or `town`: a kind and the names, then [place]. */
    private fun placing(word: String, town: Boolean) = Commands.literal(word).then(
        Commands.argument("kind", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(MemorialKind.entries.map { it.id }, b) }
            .then(Commands.argument("names", StringArgumentType.greedyString()).executes { place(it, town) }),
    )

    private fun id(): RequiredArgumentBuilder<CommandSourceStack, Int> =
        Commands.argument("id", IntegerArgumentType.integer(0))
            .suggests { c, b -> SharedSuggestionProvider.suggest(TaleData.get(c.source.level).memorials.map { it.id.toString() }, b) }

    private fun fail(source: CommandSourceStack, s: String): Int = TaleCommand.fail(source, s)

    /** True, and says why, if tales or their memorials are off, so a command that writes does nothing. */
    private fun refuse(source: CommandSourceStack): Boolean {
        Tales.refresh()
        val why = TaleCommand.off() ?: if (!memorialsOn()) "Memorials are switched off (setting: Tale Memorials)." else return false
        TaleCommand.fail(source, "$why Nothing was changed.")
        return true
    }

    private fun memorialsOn(): Boolean = try {
        GuildmarkConfig.TOWN_TALE_MEMORIALS.get()
    } catch (e: IllegalStateException) {
        true
    }

    private fun record(context: CommandContext<CommandSourceStack>): TaleData.Memorial? {
        val id = IntegerArgumentType.getInteger(context, "id")
        val rec = TaleData.get(context.source.level).memorials.firstOrNull { it.id == id }
        if (rec == null) fail(context.source, "There is no memorial #$id (tale memorial list shows them).")
        return rec
    }

    // ---- list ----

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val here = Memorials.dimension(level)
        val all = TaleData.get(level).memorials.filter { it.dimension == here }.sortedBy { it.id }
        if (all.isEmpty()) return TaleCommand.say(source, TaleCommand.grey("The town keeps no memorials here. (tale memorial place <stone|cairn> <name> sets one.)"))
        TaleCommand.say(source, TaleCommand.header("Memorials (${all.size})"))
        for (m in all.take(LISTED)) {
            val names = m.names.joinToString(", ").ifEmpty { "(no name)" }
            val tale = if (m.template.isEmpty()) "set by hand" else "${m.template}, day ${m.day}"
            val away = m.pos?.let { " " + Math.sqrt(it.distToCenterSqr(source.position)).roundToInt() + " blocks off" } ?: ""
            TaleCommand.say(
                source,
                TaleCommand.line().append(TaleCommand.label("#${m.id} ")).append(TaleCommand.plain("${m.kind} for $names")).append(TaleCommand.grey(" ($tale)"))
                    .append(TaleCommand.grey(": " + state(level, m) + away)),
            )
        }
        if (all.size > LISTED) TaleCommand.say(source, TaleCommand.grey("... and ${all.size - LISTED} more."))
        return all.size
    }

    /** Where [m] is and what becomes of it, in a few words. */
    private fun state(level: ServerLevel, m: TaleData.Memorial): String {
        val pos = m.pos
        if (pos == null) return if (m.placed) "set (its place is lost)" else "found no room"
        if (!m.placed) {
            val why = when {
                !memorialsOn() || !Tales.memorials() -> "memorials are off"
                !level.isLoaded(pos) -> "its ground is not loaded"
                m.kind == MemorialKind.CAIRN.id -> "a cairn is set when a player is near and nobody is looking"
                else -> "its place is not settled yet"
            }
            return "waiting at ${pos.toShortString()} ($why)"
        }
        val state = if (level.isLoaded(pos)) level.getBlockState(pos) else null
        if (state == null) return "set at ${pos.toShortString()} (not loaded)"
        if (!state.`is`(MemorialRegistry.MEMORIAL.get())) return "recorded at ${pos.toShortString()}, but no memorial stands there"
        val extras = ArrayList<String>()
        val candles = state.getValue(MemorialBlock.CANDLES)
        if (candles > 0) extras += "$candles candle" + (if (candles > 1) "s" else "") + if (state.getValue(MemorialBlock.LIT)) " lit" else ""
        if (state.getValue(MemorialBlock.MOSSY)) extras += "mossy"
        if (state.getValue(MemorialBlock.POSY)) extras += if (state.getValue(MemorialBlock.WILTED)) "a withered posy" else "a posy"
        return "set at ${pos.toShortString()} facing ${state.getValue(MemorialBlock.FACING).getName()}" + if (extras.isEmpty()) "" else ", " + extras.joinToString(", ")
    }

    // ---- place ----

    /** `place`: the memorial is asked for where you stand; `town`: as a tale that names no place asks, from where its kind belongs in the capital you stand in. */
    private fun place(context: CommandContext<CommandSourceStack>, town: Boolean): Int {
        val source = context.source
        if (refuse(source)) return 0
        val player = source.playerOrException
        val level = source.level
        val said = StringArgumentType.getString(context, "kind")
        val kind = MemorialKind.entries.firstOrNull { it.id == said } ?: return fail(source, "The kind is stone or cairn, not '$said'.")
        val names = StringArgumentType.getString(context, "names").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(NAMES)
        if (names.isEmpty()) return fail(source, "Give a name, or two with a comma between: tale memorial ${if (town) "town" else "place"} stone Tam Hale, Jo Ward.")
        val capital = Capitals.covering(level, player.blockPosition())
        val places = capital?.let { CityPlaces.of(level, it) }
        if (capital != null && places == null) {
            return fail(source, if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.")
        }
        val sites = MemorialSites(level, capital, places)
        val start = if (town) {
            sites.anchors(kind == MemorialKind.CAIRN).firstOrNull() ?: return fail(source, "Stand in a capital: a memorial that names no place starts from where its kind belongs in one (a temple's door, a well, the market).")
        } else {
            player.blockPosition()
        }
        val key = capital?.center?.asLong() ?: 0L
        val data = TaleData.get(level)
        if (key != 0L && data.memorials.count { it.capital == key } >= TaleLimits.MEMORIALS) {
            return fail(source, "This capital already keeps ${TaleLimits.MEMORIALS} memorials: tale memorial remove <id> takes one away.")
        }
        val rec = TaleData.Memorial(
            data.newMemorialId(), kind.id, start, Memorials.dimension(level), names, "", player.name.string,
            Memorials.today(level, key.takeIf { it != 0L }), key, false,
        )
        data.memorials.add(rec)
        val outcome = MemorialPlacing.tryPlace(level, rec, sites, sync = true)
        val found = outcome.found
        if (outcome.result == MemorialPlacing.Result.PLACED) {
            val face = level.getBlockState(rec.pos ?: player.blockPosition()).let { if (it.`is`(MemorialRegistry.MEMORIAL.get())) it.getValue(MemorialBlock.FACING).getName() else "?" }
            return TaleCommand.say(
                source,
                TaleCommand.line().append(TaleCommand.label("#${rec.id} "))
                    .append(TaleCommand.plain("A ${kind.id} is set for ${names.joinToString(", ")} at ${rec.pos?.toShortString()}, facing $face" + (outcome.where?.let { " ($it)" } ?: "") + "."))
                    .append(TaleCommand.grey(" " + (found?.let { MemorialPlacing.describe(it) } ?: ""))),
            )
        }
        // Nothing is kept of a memorial the command could not set
        data.memorials.remove(rec)
        data.changed()
        val why = when {
            found == null -> if (town) "the ground it starts from, at ${start.toShortString()}, is not loaded: go nearer to it" else "its ground is not loaded"
            found.spot != null -> "somebody is standing where it would go: try again"
            found.unloaded -> "some of the ground ${if (town) "about the places it starts from" else "round you"} is not loaded yet: try again in a moment (${MemorialPlacing.describe(found)})"
            else -> "no place ${if (town) "in this town" else "near you"} passes the rules (${MemorialPlacing.describe(found)})"
        }
        return fail(source, "No ${kind.id} was set: $why.")
    }

    // ---- remove, posy, candles ----

    private fun remove(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (refuse(source)) return 0
        val rec = record(context) ?: return 0
        MemorialPlacing.remove(source.level, rec)
        return TaleCommand.say(
            source,
            TaleCommand.grey("Took away memorial #${rec.id} (${rec.kind} for ${rec.names.joinToString(", ")}): its block, lantern and pot are gone where their chunks are loaded."),
        )
    }

    private fun posy(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (refuse(source)) return 0
        val rec = record(context) ?: return 0
        val level = source.level
        Memorials.posy(level, rec.id)
        val pos = rec.pos
        val laid = rec.placed && pos != null && level.isLoaded(pos) && level.getBlockState(pos).let { it.`is`(MemorialRegistry.MEMORIAL.get()) && it.getValue(MemorialBlock.POSY) }
        if (!laid) return fail(source, "Memorial #${rec.id} has no stone standing in a loaded chunk, so no posy was laid.")
        return TaleCommand.say(source, Component.empty().append(TaleCommand.grey("A posy lies on memorial #${rec.id}; it dries in ${Memorials.WILT_DAYS} market days and the wind takes it on the ${Memorials.POSY_DAYS}th.")))
    }

    /** The Laying at the stone of [rec], with whoever is free within 24 blocks of it (the telling's own set, S5), made to run now whatever the hour. */
    private fun lay(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (refuse(source)) return 0
        val rec = record(context) ?: return 0
        val level = source.level
        val pos = rec.pos
        if (!rec.placed || pos == null || !level.isLoaded(pos) || !level.getBlockState(pos).`is`(MemorialRegistry.MEMORIAL.get())) {
            return fail(source, "Memorial #${rec.id} has no stone standing in a loaded chunk.")
        }
        val free = Sets.freeAdults(level, Vec3.atCenterOf(pos), 24.0)
        val mourner = free.firstOrNull() ?: return fail(source, "Nobody free within 24 blocks of memorial #${rec.id}.")
        val rest = free.drop(1)
        val priest = rest.firstOrNull { it.role == TownRole.PRIEST }
        val others = rest.filter { it !== priest }.take(3)
        val said = try {
            TaleSceneCommand.begin(
                level, Sets.laying(mourner, pos, others, priest, memorial = rec, hours = null),
                "${TaleSceneCommand.name(mourner)} kneels at memorial #${rec.id} and lays a posy" + (if (priest != null) ", ${TaleSceneCommand.name(priest)} praying" else "") +
                    (if (others.isNotEmpty()) "; ${others.size} stand by" else "") + ". (At dusk, 17:00 to 22:00, a lantern is set before it.)",
            )
        } catch (e: TaleSceneCommand.Unplayable) {
            return fail(source, "The Laying cannot be played at memorial #${rec.id}: ${e.why}")
        }
        return TaleCommand.say(source, TaleCommand.grey(said))
    }

    private fun candles(context: CommandContext<CommandSourceStack>, lit: Boolean): Int {
        val source = context.source
        if (refuse(source)) return 0
        val rec = record(context) ?: return 0
        val count = IntegerArgumentType.getInteger(context, "count")
        if (!Memorials.candles(source.level, rec.id, count, lit)) return fail(source, "Memorial #${rec.id} has no stone standing in a loaded chunk.")
        return TaleCommand.say(source, TaleCommand.grey("Memorial #${rec.id} has at least $count candles" + if (lit) ", lit until the coming dawn." else "."))
    }
}
