package io.github.vaspyyy.guildmark.cooking.meal

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.SeasonCrops
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Winter Chill (living-world/cook/design.md 4.3, 9.3): the one new survival rule of Hearthcraft, mild and switchable
 * (`winterChill`). A player is **Chilled** while all of six things hold, looked at afresh on the server every 40 ticks
 * (staggered by entity id, nothing saved):
 *
 *  1. Seasons are on and it is winter;
 *  2. they are in the overworld on temperate land (`Seasons.temperate` of 0.5 or more);
 *  3. they are unsheltered: no roof, and no warm light ([SeasonCrops.sheltered]);
 *  4. it is night, or rain or snow is falling on them;
 *  5. they do not have the Warmth effect;
 *  6. they wear fewer than two pieces of `#minecraft:freeze_immune_wearables` (leather), and play in survival or adventure.
 *
 * Chilled is the harmful effect [CookRegistry.CHILLED], refreshed to five seconds at each look while the six hold and
 * taken off at the first look they do not, so it ends within a moment of a roof, a fire, daylight or a hot meal. It costs
 * a little hunger and a slower return of stamina ([Meals.regen]). **It never does damage** (nothing here hurts anyone),
 * **never stops sprinting or healing** (the hunger is not added where it could take food under what natural healing,
 * or a sprint, needs: [starve]) and **cannot kill** (it never takes food below 7, so never to starvation, and it
 * never touches health).
 */
object Chill {
    /** How often the cold is looked at, in ticks (9.3). */
    internal const val PERIOD = 40

    /** What Chilled is refreshed to at each look, in ticks: it ends within five seconds of the cold going. */
    private const val HOLD = 100

    /** The action-bar line comes at most once in five minutes (4.3), in ticks. */
    private const val LINE_EVERY = 6000L

    /** 0.03 exhaustion a second (about a food point every two minutes), added at each look, two seconds apart. */
    private const val EXHAUSTION = 0.06f

    /** Leather pieces (of four) that keep the cold out. */
    private const val LEATHER_ENOUGH = 2

    /** The cold takes no hunger from a player at or under this food while they could heal naturally (food 18 and over)... */
    private const val HEAL_FOOD = 18

    /** ...nor from one who cannot, under this, so that their sprint (food over 6) is never the cold's to end. */
    private const val SPRINT_FOOD = 7

    private val ARMOUR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    // ---- Who is told what, in memory only (9.3: cleared when the server stops) ----

    /** `cook chill on` (true) or `off` (false); no entry is the cold's own say. */
    private val forced = HashMap<UUID, Boolean>()

    /** When each player last had the action-bar line, in game ticks. */
    private val lined = HashMap<UUID, Long>()

    /** Where each Chilled player was at the last look, for the shiver. */
    private val placed = HashMap<UUID, Vec3>()

    /** Who was Chilled at the last look (for `cook perf` and for ending it once). */
    private val cold = HashSet<UUID>()

    private var checks = 0L
    private var nanos = 0L
    private var shivers = 0L

    // ---- The six conditions ----

    /** One of the six conditions of 4.3: [holds] decides it, [say] tells how it stands, for `cook chill now`. */
    private class Condition(val name: String, val holds: (ServerPlayer, ServerLevel) -> Boolean, val say: (ServerPlayer, ServerLevel) -> String)

    private val CONDITIONS = listOf(
        Condition("It is winter", { _, level -> winter(level) }, { _, level -> season(level) }),
        Condition("Temperate land in the overworld", { player, level -> temperate(player, level) >= 0.5f }, { player, level -> land(player, level) }),
        Condition("Unsheltered: no roof, no warm light", { player, level -> !SeasonCrops.sheltered(level, player.blockPosition()) }, { player, level -> shelter(player, level) }),
        Condition("Night, or rain or snow on you", { player, level -> dark(player, level) }, { player, level -> weather(player, level) }),
        Condition("No Warmth meal", { player, _ -> !player.hasEffect(CookRegistry.WARMTH) }, { player, _ -> warmth(player) }),
        Condition("Fewer than two pieces of leather, in survival or adventure", { player, _ -> leather(player) < LEATHER_ENOUGH && !player.isCreative && !player.isSpectator }, { player, _ -> dress(player) }),
    )

    private fun winter(level: ServerLevel): Boolean = Seasons.enabled() && Seasons.today(level).season == Season.WINTER

    private fun temperate(player: ServerPlayer, level: ServerLevel): Float = Seasons.temperate(level, player.blockPosition())

    private fun dark(player: ServerPlayer, level: ServerLevel): Boolean = level.isDarkOutside || level.precipitationAt(player.blockPosition()) != Biome.Precipitation.NONE

    private fun leather(player: Player): Int = ARMOUR.count { player.getItemBySlot(it).`is`(ItemTags.FREEZE_IMMUNE_WEARABLES) }

    private fun season(level: ServerLevel): String {
        if (!Seasons.enabled()) return "seasons are off in this world"
        val date = Seasons.today(level)
        return "it is ${date.season.id}, day ${date.day} of ${date.length}"
    }

    private fun land(player: ServerPlayer, level: ServerLevel): String {
        if (level.dimension() != Level.OVERWORLD) return "not the overworld"
        val biome = level.getBiome(player.blockPosition()).unwrapKey().map { it.identifier().toString() }.orElse("an unnamed biome")
        return "$biome feels the seasons at ${"%.2f".format(temperate(player, level))} (it takes 0.50)"
    }

    private fun shelter(player: ServerPlayer, level: ServerLevel): String {
        val pos = player.blockPosition()
        val roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.x, pos.z) > pos.y + 1
        val light = level.getBrightness(LightLayer.BLOCK, pos)
        return when {
            roof -> "a roof is over you (block light $light)"
            SeasonCrops.sheltered(level, pos) -> "open to the sky, but warm light of $light keeps the cold off"
            else -> "open to the sky, block light $light"
        }
    }

    private fun weather(player: ServerPlayer, level: ServerLevel): String {
        val fall = level.precipitationAt(player.blockPosition())
        return when {
            level.isDarkOutside -> "it is night" + if (fall != Biome.Precipitation.NONE) ", and ${fall.name.lowercase()} is falling on you" else ""
            fall != Biome.Precipitation.NONE -> "${fall.name.lowercase()} is falling on you"
            else -> "daylight, and nothing falling on you"
        }
    }

    private fun warmth(player: Player): String {
        val effect = player.getEffect(CookRegistry.WARMTH) ?: return "none on you"
        return "Warmth holds for ${effect.duration / 20} more seconds"
    }

    private fun dress(player: Player): String {
        val mode = if (player.isCreative) "creative" else if (player.isSpectator) "spectator" else "survival or adventure"
        return "${leather(player)} of 4 pieces are leather, $mode"
    }

    // ---- The look ----

    /** Whether the cold is on [player] now: the six hold, or `cook chill on` says so, and a Warmth meal still keeps it off. */
    private fun holds(player: ServerPlayer, level: ServerLevel): Boolean {
        val force = forced[player.uuid]
        return when {
            force == false -> false
            player.hasEffect(CookRegistry.WARMTH) -> false
            force == true -> true
            else -> CONDITIONS.all { it.holds(player, level) }
        }
    }

    /** Every [PERIOD] ticks, for each player (the player tick calls it staggered by entity id): puts Chilled on or takes it off. */
    internal fun check(player: ServerPlayer) {
        if (!Cooking.winterChill()) {
            // Off: nothing is looked at, and the effect, if one is left, ends at its own next tick
            if (cold.remove(player.uuid)) placed.remove(player.uuid)
            return
        }
        if (!player.isAlive) return
        val started = System.nanoTime()
        val level = player.level()
        if (holds(player, level)) hold(player, level) else release(player)
        checks++
        nanos += System.nanoTime() - started
    }

    private fun hold(player: ServerPlayer, level: ServerLevel) {
        val id = player.uuid
        player.addEffect(MobEffectInstance(CookRegistry.CHILLED, HOLD, 0, false, false, true))
        cold.add(id)
        starve(player)
        val now = level.gameTime
        val last = lined[id]
        if (last == null || last > now || now - last >= LINE_EVERY) {
            player.sendSystemMessage(Component.translatable("message.guildmark.cook.chill.bites"), true)
            lined[id] = now
        }
        shiver(player)
    }

    private fun release(player: ServerPlayer) {
        val id = player.uuid
        if (player.hasEffect(CookRegistry.CHILLED)) player.removeEffect(CookRegistry.CHILLED)
        cold.remove(id)
        placed.remove(id)
    }

    /**
     * A little hunger: 0.03 exhaustion a second (4.3). Never where it could do what the cold must not: a player who could
     * heal naturally (food 18 or more) is never taken under 18, and one already under it never under 7, where sprinting
     * ends; saturation, which exhaustion spends first, counts as food left. So the cold never ends a heal or a sprint by
     * itself, and never takes anyone near starving.
     */
    private fun starve(player: ServerPlayer) {
        if (player.isCreative || player.isSpectator) return
        val food = player.foodData
        val level = food.foodLevel
        val floor = if (level >= HEAL_FOOD) HEAL_FOOD else SPRINT_FOOD
        if (level > floor || (level == floor && food.saturationLevel > 0f)) player.causeFoodExhaustion(EXHAUSTION)
    }

    /** Standing still while Chilled, they shiver (E plays the clip): asked each look, which is as often as the clip is long. */
    private fun shiver(player: ServerPlayer) {
        val here = player.position()
        val before = placed.put(player.uuid, here)
        if (before == null || before.distanceToSqr(here) > 0.01) return
        if (!player.onGround() || player.isPassenger || player.isSleeping || player.isUsingItem || player.isSwimming || player.isFallFlying || player.abilities.flying) return
        shivers++
        CookActions.play(player, CookAction.SHIVER, player.blockPosition())
    }

    // ---- For `cook chill` ----

    /** `cook chill on` (true) forces the cold on [player], `off` (false) forces it off, null leaves it to the six; whichever, it looks at once. */
    internal fun force(player: ServerPlayer, on: Boolean?) {
        if (on == null) forced.remove(player.uuid) else forced[player.uuid] = on
        // Off is done at once, with the switch on or not
        if (on == false) release(player) else check(player)
    }

    /** Whether `cook chill on` or `off` is in force on [player]: true, false, or null. */
    internal fun forcing(player: ServerPlayer): Boolean? = forced[player.uuid]

    /** The lines `cook chill now` prints: each of the six conditions, whether it holds and how it stands, and what the cold does to this player. */
    internal fun report(player: ServerPlayer): List<Component> {
        val level = player.level()
        val lines = ArrayList<Component>()
        for ((index, condition) in CONDITIONS.withIndex()) {
            val ok = condition.holds(player, level)
            lines.add(
                Component.literal("  ${index + 1}. ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(if (ok) "yes " else "no  ").withStyle(if (ok) ChatFormatting.GREEN else ChatFormatting.RED))
                    .append(Component.literal(condition.name).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(": " + condition.say(player, level)).withStyle(ChatFormatting.GRAY)),
            )
        }
        val chilled = Meals.chilled(player)
        val verdict: MutableComponent = Component.literal(if (chilled) "Chilled: yes" else "Chilled: no").withStyle(if (chilled) ChatFormatting.AQUA else ChatFormatting.GREEN)
        lines.add(verdict)
        val food = player.foodData
        val floor = if (food.foodLevel >= HEAL_FOOD) HEAL_FOOD else SPRINT_FOOD
        val regen = (Meals.regen(player) * 100).roundToInt()
        lines.add(
            Component.literal(
                "  Hunger: the cold takes none from you under food $floor (it keeps 18 while you can heal naturally and 7 once you cannot, " +
                    "so it never ends a heal or a sprint); food is ${food.foodLevel}, saturation ${"%.1f".format(food.saturationLevel)}. " +
                    "Stamina regeneration from meals and the cold: ${if (regen >= 0) "+" else ""}$regen%.",
            ).withStyle(ChatFormatting.GRAY),
        )
        return lines
    }

    // ---- Housekeeping and counts ----

    internal fun forget(id: UUID) {
        forced.remove(id)
        lined.remove(id)
        placed.remove(id)
        cold.remove(id)
    }

    internal fun clear() {
        forced.clear()
        lined.clear()
        placed.clear()
        cold.clear()
    }

    /** The line of `cook perf` for the cold: players Chilled now, looks made and what each cost. */
    internal fun stats(): String {
        val per = if (checks == 0L) 0.0 else nanos / 1000.0 / checks
        return "${cold.size} Chilled now; $checks looks at the cold, ${"%.1f".format(per)} us each; $shivers shivers asked of the cook's clip"
    }
}
