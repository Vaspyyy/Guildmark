package io.github.vaspyyy.guildmark.cooking.vessel

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.recipe.Quality
import io.github.vaspyyy.guildmark.cooking.recipe.Roles
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.math.roundToInt

/**
 * `/guildmark cook vessel ...` (living-world/cook/design.md 10.3, Appendix C.4): the station you are looking at, for
 * operators. The bare command says where its vessel stands; `fill`, `fuel` and `light` set one up from nowhere, `advance`
 * works out that much time as if you had been away, `call` and `burn` make the thing happen, `tip` and `clear` end it. It
 * hangs off the root that Part 0's `CookCommand` makes (Brigadier joins nodes of the same name).
 */
object VesselCommand {
    /** What `fuel` adds when no number is given, seconds of mid heat (two logs). */
    private const val FUEL_DEFAULT = 120.0

    /** The kinds, for the completion of `fill`. */
    private val KINDS: SuggestionProvider<CommandSourceStack> =
        SuggestionProvider { _, builder -> SharedSuggestionProvider.suggest(DishKind.entries.map { it.id }, builder) }

    /** The calls by the short words of the command. */
    private val CALLS: Map<String, Call> = linkedMapOf(
        "stick" to Call.STICK, "boil" to Call.BOIL_OVER, "skim" to Call.SKIM, "flare" to Call.FLARE, "turn" to Call.TURN, "season" to Call.SEASON,
    )

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val callNode = Commands.literal("call")
        for (word in CALLS.keys) callNode.then(Commands.literal(word).executes { call(it, word) })
        val vessel = Commands.literal("vessel")
            .executes { status(it) }
            .then(
                Commands.literal("fill").executes { fill(it, null) }
                    .then(Commands.argument("kind", StringArgumentType.word()).suggests(KINDS).executes { fill(it, StringArgumentType.getString(it, "kind")) }),
            )
            .then(
                Commands.literal("fuel").executes { fuel(it, FUEL_DEFAULT) }
                    .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.0, Fuel.STORE_CAP)).executes { fuel(it, DoubleArgumentType.getDouble(it, "seconds")) }),
            )
            .then(Commands.literal("light").executes { light(it) })
            .then(
                Commands.literal("advance")
                    .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.0, 36000.0)).executes { advance(it, DoubleArgumentType.getDouble(it, "seconds")) }),
            )
            .then(callNode)
            .then(Commands.literal("burn").executes { burn(it) })
            .then(Commands.literal("tip").executes { tip(it) })
            .then(Commands.literal("clear").executes { clear(it) })
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("cook").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(vessel)),
        )
    }

    // ---- The station in view ----

    /** The vessel of the station the operator is looking at, brought up to date; or null after saying why there is none. */
    private fun target(context: CommandContext<CommandSourceStack>): VesselBlockEntity? {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player, looking at a station."))
            return null
        }
        val station = Stations.lookedAt(player)
        if (station == null) {
            source.sendFailure(Component.literal("Look at a station first (within six blocks)."))
            return null
        }
        if (station !is VesselBlockEntity) {
            source.sendFailure(Component.literal("That is the prep board, which has no vessel."))
            return null
        }
        station.catchUp(source.level)
        return station
    }

    private fun say(context: CommandContext<CommandSourceStack>, text: String): Int {
        context.source.sendSuccess({ Component.literal(text).withStyle(ChatFormatting.WHITE) }, false)
        return 1
    }

    private fun fail(context: CommandContext<CommandSourceStack>, text: String): Int {
        context.source.sendFailure(Component.literal(text))
        return 0
    }

    /** Whether Hearthcraft is off, which stops time at every station: a command that works time out or makes a call says so and does nothing. */
    private fun off(context: CommandContext<CommandSourceStack>): Boolean {
        if (Cooking.enabled()) return false
        fail(context, "Hearthcraft is off (setting), so nothing at a station moves; turn it on first.")
        return true
    }

    // ---- The commands ----

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val be = target(context) ?: return 0
        val level = context.source.level
        val cookLevel = context.source.player?.let { Skills.level(it, Skill.COOKING) } ?: 1
        val bonus = context.source.player?.let { Quality.bonus(it) } ?: 0.0
        context.source.sendSuccess({ Component.literal("== Vessel ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD) }, false)
        if (!Cooking.enabled()) {
            context.source.sendSuccess({ Component.literal("Hearthcraft is off (setting): this station keeps what it holds and does not move.").withStyle(ChatFormatting.GRAY) }, false)
        }
        for (line in be.describe(level, cookLevel, bonus)) say(context, line)
        return 1
    }

    /** The vessel of an open station is filled with water (a pot) and the kind's book bill, from nowhere. */
    private fun fill(context: CommandContext<CommandSourceStack>, id: String?): Int {
        val be = target(context) ?: return 0
        val v = be.vessel
        val kind = if (id == null) DishKind.entries.first { it.station == v.station } else DishKind.byId(id) ?: return fail(context, "There is no kind '$id'.")
        if (!v.open) return fail(context, "It has been started; `cook vessel clear` first.")
        if (v.station == Station.STEWPOT) v.water = Vessel.WATER_FULL
        var added = 0
        for ((name, count) in kind.bill) {
            val item = billItem(name) ?: continue
            repeat(count) {
                if (v.items.size < v.capacity) {
                    v.add(ItemStack(item), null, "")
                    added++
                }
            }
        }
        v.dirty = true
        be.settleNow()
        return say(context, "Filled with the bill of ${kind.id}: $added items" + if (v.station == Station.STEWPOT) " and a full pot of water." else ".")
    }

    private fun fuel(context: CommandContext<CommandSourceStack>, seconds: Double): Int {
        val be = target(context) ?: return 0
        val result = be.vessel.addFuel(seconds)
        be.settleNow()
        return when (result) {
            Vessel.FuelResult.OK -> say(context, "Fuel now ${be.clock(be.vessel.fuel)} (${be.vessel.fuel.roundToInt()} s of mid heat).")
            Vessel.FuelResult.TOO_MUCH -> fail(context, "That is more than the fire ever holds (${Fuel.STORE_CAP.roundToInt()} s).")
            Vessel.FuelResult.FULL -> fail(context, "The fire cannot hold that much more (now ${be.vessel.fuel.roundToInt()} of ${Fuel.STORE_CAP.roundToInt()} s).")
        }
    }

    private fun light(context: CommandContext<CommandSourceStack>): Int {
        val be = target(context) ?: return 0
        val v = be.vessel
        if (v.lit) return fail(context, "It is already burning.")
        if (v.fuel <= 0.0) return fail(context, "There is no fuel; `cook vessel fuel` first (a free fire is only free once it is lit).")
        v.lit = true
        v.dirty = true
        be.settleNow()
        return say(context, "Lit.")
    }

    /** Works out [seconds] as an absence: the vessel's stamp is moved back and it is brought up to date, with no calls issued or missed. */
    private fun advance(context: CommandContext<CommandSourceStack>, seconds: Double): Int {
        if (off(context)) return 0
        val be = target(context) ?: return 0
        val level = context.source.level
        val v = be.vessel
        val ticks = (seconds * 20.0).toLong()
        v.stamp = (level.gameTime - ticks).coerceAtLeast(1L)
        be.catchUp(level)
        return say(context, "Worked out ${seconds.roundToInt()} s as if you had been away. It is ${v.state.name.lowercase()}, dish-clock ${"%.1f".format(v.p)}, fuel ${be.clock(v.fuel)}.")
    }

    private fun call(context: CommandContext<CommandSourceStack>, word: String): Int {
        if (off(context)) return 0
        val be = target(context) ?: return 0
        val level = context.source.level
        val v = be.vessel
        val call = CALLS.getValue(word)
        if (!v.cooking) return fail(context, "It is not cooking; a call needs a vessel on the clock.")
        if (v.call != null) return fail(context, "A call is already due (${v.call?.name?.lowercase()}).")
        if (!v.post(call, be.env, level.gameTime)) return fail(context, "Nobody within 16 blocks can take a call.")
        be.playEvents(level, level.gameTime)
        be.settleNow()
        return say(context, "${call.name.lowercase()} is due.")
    }

    private fun burn(context: CommandContext<CommandSourceStack>): Int {
        val be = target(context) ?: return 0
        val level = context.source.level
        val v = be.vessel
        if (!(v.state == Vessel.State.HEATING || v.cooking)) return fail(context, "It is not on the heat.")
        v.burnNow()
        be.playEvents(level, level.gameTime)
        be.settleNow()
        return say(context, "It has caught.")
    }

    /** The tip-out of a sneak and an empty hand, without the second click that asks: Scorched mess falls from a burnt one, the raw items from an open one. */
    private fun tip(context: CommandContext<CommandSourceStack>): Int {
        val be = target(context) ?: return 0
        val v = be.vessel
        if (v.state == Vessel.State.EMPTY && v.items.isEmpty() && v.water <= 0) return fail(context, "It is empty.")
        be.emptyOut(context.source.level)
        return say(context, "Tipped out; the fire, its fuel and the heat are as they were.")
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val be = target(context) ?: return 0
        val v = be.vessel
        v.empty()
        v.fuel = 0.0
        v.lit = false
        v.setHeat(1)
        v.dirty = true
        be.settleNow()
        return say(context, "Emptied, fuel and fire out.")
    }

    // ---- The bill ----

    /** The item a name of a standard bill stands for (Appendix B.3): the first item the role data names for that food, else the usual one. */
    private fun billItem(name: String): Item? {
        for (id in Roles.current().itemsOf(name)) {
            val key = Identifier.tryParse(id) ?: continue
            val item = BuiltInRegistries.ITEM.getOptional(key).orElse(null)
            if (item != null) return item
        }
        return usualItem(name)
    }

    /** The usual item of a food of the standard bills, for a world whose role data names none. */
    private fun usualItem(name: String): Item? = when (name) {
        "potato" -> Items.POTATO
        "carrot" -> Items.CARROT
        "wheat" -> Items.WHEAT
        "beetroot" -> Items.BEETROOT
        "herb" -> CookRegistry.HERB_SPRIG.get()
        "beef" -> Items.BEEF
        "mushroom" -> Items.BROWN_MUSHROOM
        "mutton" -> Items.MUTTON
        "bone" -> Items.BONE
        "cod" -> Items.COD
        "milk" -> Items.MILK_BUCKET
        "honey" -> Items.HONEY_BOTTLE
        "egg" -> Items.EGG
        "apple" -> Items.APPLE
        "pork" -> Items.PORKCHOP
        "salmon" -> Items.SALMON
        else -> null
    }
}
