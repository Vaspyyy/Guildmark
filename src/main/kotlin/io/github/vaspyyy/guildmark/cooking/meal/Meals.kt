package io.github.vaspyyy.guildmark.cooking.meal

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Family
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.progression.Talents
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.MobEffectEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Meals that matter, and Winter Chill (living-world/cook/design.md section 4). Part F's. A dish of a family gives its
 * effect when eaten ([eat]); the three hooks the combat and talents code calls ([regen], [poise], [maxStamina]) and
 * [chilled] read the effects the player has, so nothing here is saved but what the game saves with an effect.
 *
 * **One family at a time** (4.1): a dish of another family replaces the effect the player has; one of the same family
 * keeps the stronger strength and the longer time left, they do not add up. A stale dish gives its effect at 0.6, in
 * strength and in time; a gone-off one none. Cooking off, or the dish a sweet one, and nothing is given.
 *
 * **The amplifier** carries the strength (4.2): the tier (0 Plain to 3 Hallmarked) plus 4 for the family's strong kind
 * (broth, roast, strips) plus 8 for a stale dish; Hearty's is the health it adds less one (the vanilla attribute
 * template adds one a step). [Boon] writes it and every reader decodes it, so the numbers have one source.
 */
object Meals {
    // ---- The numbers of 4.1, for a Sound dish ----

    /** Hearty: maximum health added, in health points (two hearts). */
    private const val HEARTY_HEALTH = 4.0

    /** Steady: stamina regeneration added (a share of the usual), poise, and maximum stamina. */
    private const val STEADY_REGEN = 0.12
    private const val STEADY_POISE = 5.0
    private const val STEADY_STAMINA = 10.0

    /** The strong kind of a family (roast) is this much stronger. */
    private const val STRONG = 1.3

    /** Provisioned: the share of each food point lost that comes back, and the most it may be (80%). */
    private const val PROVISIONED_SHARE = 0.5
    private const val PROVISIONED_CAP = 0.8

    /** A stale dish gives its effect at this share, of strength and of time (3.2, 4.1). */
    private const val STALE_SHARE = 0.6

    /** How much slower stamina comes back while Chilled (4.3). */
    private const val CHILL_REGEN = 0.15f

    /** Hearty's hearts come filled, as a talent's do ([Talents.apply]); the one line that says no is this. */
    private const val HEARTY_FILLS = true

    /** How long a kind's effect lasts at Sound, in minutes (4.1); 0 for a kind with none. */
    private fun baseMinutes(kind: DishKind): Double = when (kind) {
        DishKind.POTTAGE -> 10.0
        DishKind.CHOWDER -> 12.0
        DishKind.BROTH -> 15.0
        DishKind.STEW, DishKind.FISH -> 12.0
        DishKind.PIE -> 8.0
        DishKind.ROAST -> 10.0
        DishKind.LOAF -> 30.0
        DishKind.STRIPS -> 40.0
        else -> 0.0
    }

    /**
     * What one meal gives: the [family], the [tier], whether it is the family's [strong] kind and whether it was [stale],
     * with every number of 4.1 worked out from them. It is the one place the numbers are made, so eating, the dish's
     * tooltip ([describe]) and `cook meal` always say the same. [minutes] is how long it lasts, if it is not the kind's own.
     */
    class Boon(val family: Family, val tier: Tier, val strong: Boolean, val stale: Boolean, baseMinutes: Double, minutes: Double? = null) {
        /** What strength and time are both multiplied by: the tier's, and 0.6 of it when stale. */
        val factor: Double = tier.strength * (if (stale) STALE_SHARE else 1.0)

        /** How long it lasts, in minutes. */
        val minutes: Double = minutes ?: (baseMinutes * factor)

        /** The same in game ticks (at least a second). */
        val ticks: Int = (this.minutes * 1200.0).roundToInt().coerceAtLeast(20)

        /** Hearty: the maximum health it adds, in health points. */
        val health: Int = (HEARTY_HEALTH * factor).roundToInt().coerceAtLeast(1)

        /** Steady: stamina regeneration added (0.12 is 12% faster), poise, and maximum stamina; the strong kind a third as much again. */
        val regen: Double = STEADY_REGEN * factor * strongest()
        val poise: Double = STEADY_POISE * factor * strongest()
        val stamina: Double = STEADY_STAMINA * factor * strongest()

        /** Provisioned: the share of every food point lost that comes back. */
        val share: Double = min(PROVISIONED_CAP, PROVISIONED_SHARE * factor)

        /** The effect's amplifier (4.2). */
        val amplifier: Int = if (family == Family.HEARTY) health - 1 else (tier.ordinal - 1).coerceAtLeast(0) + (if (strong) 4 else 0) + (if (stale) 8 else 0)

        /** How strong it is beside another of its family: what "the stronger stays" compares. */
        val rank: Double get() = rankOf(family, amplifier)

        private fun strongest(): Double = if (family == Family.STEADY && strong) STRONG else 1.0

        /** The line a tooltip or the command gives for it ("Steady: stamina returns 12% faster, ..."), in the family's colour. */
        fun describe(): MutableComponent {
            val time = minutesText(minutes)
            val line = when (family) {
                Family.WARMTH -> Component.translatable("gui.guildmark.cook.meal.warmth", time)
                Family.HEARTY -> Component.translatable("gui.guildmark.cook.meal.hearty", health.toString(), time)
                Family.STEADY -> Component.translatable("gui.guildmark.cook.meal.steady", percent(regen), num(poise), num(stamina), time)
                Family.PROVISIONED -> Component.translatable("gui.guildmark.cook.meal.provisioned", percent(share), time)
            }
            return line.withStyle(Style.EMPTY.withColor(colour(family)))
        }

        companion object {
            /** The boon of a dish of [kind] at [tier] and [stage] (a dish of a sweet kind, a gone-off one or a spoilt one has none: null), lasting [minutes] if it is not the kind's own time. */
            fun of(kind: DishKind, tier: Tier, stage: Stage, minutes: Double? = null): Boon? {
                val family = kind.family ?: return null
                if (stage == Stage.GONE || tier == Tier.SPOILT) return null
                return Boon(family, tier, kind.strong, stage == Stage.STALE, baseMinutes(kind), minutes)
            }
        }
    }

    /** What [eat] did, for the one that asked: the effect went on (or was made longer or stronger), or what the player had was as good or better. */
    enum class Outcome { TOOK, KEPT }

    /** What giving a meal did: [outcome], the family it [replaced] (if any) and whether it [lifted] the cold. */
    class Given(val outcome: Outcome, val replaced: Family?, val lifted: Boolean)

    // ---- Eating ----

    /** The family's effect of a dish of [kind] with [data] eaten by [player] at [stage] (4.1). Nothing with Hearthcraft off. */
    fun eat(player: ServerPlayer, kind: DishKind, data: DishData, stage: Stage) {
        if (!Cooking.enabled()) return
        val boon = Boon.of(kind, data.tier, stage) ?: return
        give(player, boon)
    }

    /**
     * Puts [boon] on [player] by the rules of 4.1 (what [eat] does, and `cook meal`): another family's effect goes, the
     * same family's keeps the stronger and the longer, Warmth lifts the cold, and the eater alone hears the chime and
     * reads one line of what happened.
     */
    fun give(player: ServerPlayer, boon: Boon): Given {
        val own = holder(boon.family)
        var replaced: Family? = null
        for (other in Family.entries) {
            if (other == boon.family || !player.hasEffect(holder(other))) continue
            if (replaced == null) replaced = other
            player.removeEffect(holder(other))
        }
        // The same family: the stronger strength and the longer time left; they do not add up
        var amplifier = boon.amplifier
        var ticks = boon.ticks
        val current = player.getEffect(own)
        if (current != null) {
            if (rankOf(boon.family, current.amplifier) >= boon.rank) amplifier = current.amplifier
            ticks = max(ticks, current.duration)
            if (amplifier == current.amplifier && ticks <= current.duration) {
                kept++
                player.sendSystemMessage(Component.translatable("message.guildmark.cook.meal.kept", own.value().displayName), true)
                return Given(Outcome.KEPT, replaced, false)
            }
            // The game keeps a lower amplifier by the side as a hidden effect, which a meal never wants: start afresh
            if (amplifier < current.amplifier) player.removeEffect(own)
        }
        val before = player.maxHealth
        if (!player.addEffect(MobEffectInstance(own, ticks, amplifier, false, false, true))) return Given(Outcome.KEPT, replaced, false)
        if (HEARTY_FILLS && boon.family == Family.HEARTY && player.maxHealth > before) {
            player.health = min(player.maxHealth, player.health + (player.maxHealth - before))
        }
        var lifted = false
        if (boon.family == Family.WARMTH && player.hasEffect(CookRegistry.CHILLED)) {
            player.removeEffect(CookRegistry.CHILLED)
            lifted = true
        }
        eaten++
        if (replaced != null) swapped++
        chime(player, boon.tier)
        when {
            lifted -> player.sendSystemMessage(Component.translatable("message.guildmark.cook.chill.lifted"), true)
            replaced != null -> player.sendSystemMessage(Component.translatable("message.guildmark.cook.meal.replaced", holder(replaced).value().displayName, own.value().displayName), true)
        }
        return Given(Outcome.TOOK, replaced, lifted)
    }

    /** The line a dish's tooltip gives for its meal effect at [tier] and [stage], or null if it gives none (D's tooltip asks). */
    fun describe(kind: DishKind, tier: Tier, stage: Stage): Component? = Boon.of(kind, tier, stage)?.describe()

    /** A soft chime to the eater alone, as the design's reach says (`cook.buff`, 11.2). */
    private fun chime(player: ServerPlayer, tier: Tier) {
        val sound = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(CookSounds.get("cook.buff"))
        // A better meal rings a little higher: Plain 0.9 to Hallmarked 1.2
        val pitch = 0.9f + 0.1f * (tier.ordinal - 1).coerceIn(0, 3)
        player.connection.send(ClientboundSoundPacket(sound, SoundSource.PLAYERS, player.x, player.y, player.z, 0.6f, pitch, player.random.nextLong()))
    }

    private fun holder(family: Family): Holder<MobEffect> = when (family) {
        Family.WARMTH -> CookRegistry.WARMTH
        Family.HEARTY -> CookRegistry.HEARTY
        Family.STEADY -> CookRegistry.STEADY
        Family.PROVISIONED -> CookRegistry.PROVISIONED
    }

    private fun colour(family: Family): Int = when (family) {
        Family.WARMTH -> 0xE8893C
        Family.HEARTY -> 0xC24B3F
        Family.STEADY -> 0x6F9BE0
        Family.PROVISIONED -> 0xB08A5A
    }

    // ---- Reading an effect's strength back ----

    /** What an effect's strength and time were multiplied by, from its amplifier: its tier's, 0.6 of it when stale, and a third more for Steady's strong kind. */
    private fun strength(family: Family, amplifier: Int): Double {
        val tier = Tier.entries[1 + (amplifier and 3)]
        return tier.strength * (if (amplifier and 8 != 0) STALE_SHARE else 1.0) * (if (family == Family.STEADY && amplifier and 4 != 0) STRONG else 1.0)
    }

    /** How strong an effect is beside another of its family: Hearty by the health it adds, the rest by their strength. */
    private fun rankOf(family: Family, amplifier: Int): Double = if (family == Family.HEARTY) amplifier.toDouble() else strength(family, amplifier)

    // ---- The hooks (4.2) ----

    /** The extra stamina regeneration [player] has from a meal and the cold, as a share of the usual: Steady's figure less Winter Chill's 0.15. */
    fun regen(player: Player): Float {
        var share = 0f
        val steady = player.getEffect(CookRegistry.STEADY)
        if (steady != null && Cooking.enabled()) share += (STEADY_REGEN * strength(Family.STEADY, steady.amplifier)).toFloat()
        if (chilled(player)) share -= CHILL_REGEN
        return share
    }

    /** The extra poise [player] has from a Steady meal. */
    fun poise(player: Player): Float {
        val steady = player.getEffect(CookRegistry.STEADY) ?: return 0f
        return if (Cooking.enabled()) (STEADY_POISE * strength(Family.STEADY, steady.amplifier)).toFloat() else 0f
    }

    /** The extra stamina [player] can hold from a Steady meal. */
    fun maxStamina(player: Player): Float {
        val steady = player.getEffect(CookRegistry.STEADY) ?: return 0f
        return if (Cooking.enabled()) (STEADY_STAMINA * strength(Family.STEADY, steady.amplifier)).toFloat() else 0f
    }

    /** Whether [player] is Chilled (4.3): never with Winter Chill off. */
    fun chilled(player: Player): Boolean = player.hasEffect(CookRegistry.CHILLED) && Cooking.winterChill()

    // ---- Steady's maximum stamina ----

    /**
     * Who has a Steady meal that began or ended since the talents last put their stamina in place. The game tells a listener
     * of an effect ending *before* it has gone, so the refresh is done on the player's next tick, when the effect is as it will be.
     */
    private val steadyDirty = HashSet<UUID>()

    /** What [maxStamina] gave the last time it was looked at, per player who has any (the self-check below). */
    private val staminaSeen = HashMap<UUID, Float>()

    /** A Steady effect began, grew or ended on [player]: the talents put their stamina in place again on the next tick. */
    internal fun steadyChanged(player: ServerPlayer) {
        steadyDirty.add(player.uuid)
    }

    /** Every few seconds, in case an effect went some way no event told of (a switch turned off, a command): looks whether the meal's stamina is what the talents last put in place. */
    private fun staminaCheck(player: ServerPlayer) {
        val now = maxStamina(player)
        val before = if (now == 0f) staminaSeen.remove(player.uuid) else staminaSeen.put(player.uuid, now)
        if (now != (before ?: 0f)) Talents.apply(player)
    }

    // ---- Provisioned ----

    /** What Provisioned last saw of a player: the food level, the fraction of a point owed, and when. */
    private class Ration(var food: Int, var carry: Double, var at: Long)

    private val rations = HashMap<UUID, Ration>()

    /** A Provisioned effect began or grew: the food it counts from is the food now. */
    internal fun rationed(player: ServerPlayer) {
        rations.remove(player.uuid)
    }

    /**
     * Once a second while Provisioned lasts: the food level lost since the last second comes back at the family's share,
     * with the fraction left over carried to the next (`FoodData` has no exhaustion to read, so it watches the food
     * level; 4.2). A gap of more than three seconds, or a clock that has gone back, counts from now: nothing is owed.
     */
    internal fun provision(player: ServerPlayer) {
        val effect = player.getEffect(CookRegistry.PROVISIONED) ?: return
        val started = System.nanoTime()
        val now = player.level().gameTime
        val food = player.foodData
        val level = food.foodLevel
        val ration = rations.getOrPut(player.uuid) { Ration(level, 0.0, now) }
        if (now - ration.at > 60 || ration.at > now) {
            ration.food = level
            ration.carry = 0.0
        } else if (level < ration.food) {
            ration.carry += (ration.food - level) * min(PROVISIONED_CAP, PROVISIONED_SHARE * strength(Family.PROVISIONED, effect.amplifier))
            val back = floor(ration.carry).toInt()
            if (back > 0) {
                ration.carry -= back
                food.foodLevel = min(20, level + back)
                credited += back
            }
        }
        ration.food = food.foodLevel
        ration.at = now
        rationNanos += System.nanoTime() - started
        rationRuns++
    }

    // ---- Counts for `cook perf` ----

    private var eaten = 0L
    private var swapped = 0L
    private var kept = 0L
    private var credited = 0L
    private var rationRuns = 0L
    private var rationNanos = 0L

    // ---- Registration ----

    /** Part F's registration: the player tick (Steady's stamina, Winter Chill), the effect events, the logout, the clearing at stop and the `cook` commands. */
    fun register() {
        val bus = NeoForge.EVENT_BUS
        bus.addListener(PlayerTickEvent.Post::class.java) { event -> (event.entity as? ServerPlayer)?.let(::tick) }
        // The game tells of an effect before it has changed, so these only say that a refresh is due
        bus.addListener(MobEffectEvent.Added::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            if (cookEffect(event.effectInstance)?.family == Family.STEADY) steadyChanged(player)
        }
        bus.addListener(MobEffectEvent.Remove::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            if ((event.effect.value() as? CookEffect)?.family == Family.STEADY) steadyChanged(player)
        }
        bus.addListener(MobEffectEvent.Expired::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            val effect = cookEffect(event.effectInstance) ?: return@addListener
            if (effect.family == Family.STEADY) steadyChanged(player)
            // The hearts, the stamina or the food going back to how it was has a cause the player can read
            if (effect.family != null) player.sendSystemMessage(Component.translatable("message.guildmark.cook.meal.ended", effect.displayName), true)
        }
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> forget(event.entity.uuid) }
        Cooking.onForget { clear() }
        CookStats.readout("meals") {
            val per = if (rationRuns == 0L) 0.0 else rationNanos / 1000.0 / rationRuns
            "$eaten meal effects given ($swapped replaced another family, $kept changed nothing); Provisioned gave back $credited food points in $rationRuns runs, ${"%.1f".format(per)} us each"
        }
    }

    /** The Hearthcraft effect an event is about, or null for any other (the event's instance may be missing). */
    private fun cookEffect(instance: MobEffectInstance?): CookEffect? = instance?.effect?.value() as? CookEffect

    private fun tick(player: ServerPlayer) {
        if (steadyDirty.isNotEmpty() && steadyDirty.remove(player.uuid)) Talents.apply(player)
        // Staggered by entity id so no two players share a tick
        val phase = player.tickCount + player.id
        if (phase % 200 == 0) staminaCheck(player)
    }

    private fun forget(id: UUID) {
        steadyDirty.remove(id)
        staminaSeen.remove(id)
        rations.remove(id)
    }

    /** The server stopped (9.3): every table here starts empty in the next world. */
    private fun clear() {
        steadyDirty.clear()
        staminaSeen.clear()
        rations.clear()
    }

    // ---- Words ----

    /** A number as a player reads it: whole when it is, else one decimal ("19", "10.4"). */
    internal fun num(x: Double): String {
        val r = (x * 10.0).roundToInt() / 10.0
        return if (r == r.roundToInt().toDouble()) r.roundToInt().toString() else r.toString()
    }

    /** A share as a percentage, to the whole number (0.156 is "16"). */
    internal fun percent(share: Double): String = (share * 100.0).roundToInt().toString()

    /** Minutes as a tooltip gives them: to the whole minute from three up, else to a tenth. */
    internal fun minutesText(minutes: Double): String = if (minutes >= 3.0) minutes.roundToInt().toString() else num(minutes)
}
