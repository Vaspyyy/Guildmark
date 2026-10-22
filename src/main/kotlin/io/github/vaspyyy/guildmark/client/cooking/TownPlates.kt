package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.townsfolk.LivingSchedule
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderState
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookPayloads
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.town.ServedCommand
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Vector3f
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.min

/**
 * The town's diners and their plates (living-world/cook/design.md 7.4): what the inn last served, from the last
 * `ServedPayload` of each capital ([ServedState]), shown on the townsfolk eating it, in proportion to the portions served and
 * all of it cosmetic, drawn from the client's own state with nothing sent or saved. Part I3's; [CookClient.register] calls
 * [register]. With `townPlates` (the player's option) or `townKitchens` (the server's rule) off, or nothing served, the town
 * eats what it always has, and nothing here is asked more than a switch.
 *
 *  - **At the tavern's tables.** A townsperson sitting at a table inside the inn (the payload carries the inn's box) while a
 *    meal is on is one of the diners with the chance `min(0.8, 0.15 + 0.05 x portions)` of the inn's portions (at most 30 at
 *    a time), a coin kept by who they are and which meal it is, so the same people eat for as long as the meal is shown and a
 *    player who comes late sees them at it too. A diner has a dish of a served kind (weighted by its portions, at the best tier
 *    served of it) laid flat on the table in front of them by [TownPlateLayer], set down as they sit and taken up as they rise,
 *    and the spoon in the right hand where the mug was. Never at a house table: only the inn's box counts, and only where
 *    [TownTables] finds a table with room for the plate.
 *  - **Out of doors at lunch.** Someone eating on a bench or standing with their lunch, under the open sky, while a baked kind
 *    (a pie, a tart, cakes, a loaf, strips) was served, may be one of the diners with the same chance of the baked portions:
 *    they eat a slice of it in place of the bread ([shown]).
 *
 * The state is a small record for each townsperson seen at a seat or a bench (kept a few seconds after they leave), found by
 * their entity id; what is decided is kept for as long as it stands (the same meal at the same seat), so a frame costs a lookup
 * and a few comparisons for each sitter and nothing for anyone else.
 */
object TownPlates {
    /** The most diners shown at once. */
    const val MAX_DINERS = 30

    /** Ticks a plate takes to be set down, and to be taken up. */
    private const val ARRIVE = 9f
    private const val LEAVE = 7f

    /** A plate that needs no setting down (the diner was seated at it before it was seen). */
    private const val INSTANT = -1.0e6f

    /** Ticks after which a verdict that was no is looked at again (the table or the chunk may have come in). */
    private const val RECHECK = 100L

    /** A plate whose meal came this recently (ticks) is set down in front of the diners already seated. */
    private const val FRESH = 100L

    /** Ticks a record is kept after the townsperson was last seen at a seat. */
    private const val KEEP = 200L

    /** Ticks after which the item model of a dish is looked up again. */
    private const val REFRESH = 1200L

    /** The kinds that steam when they are put on a table. */
    private val STEAMING: Set<DishKind> = setOf(DishKind.POTTAGE, DishKind.STEW, DishKind.BROTH, DishKind.ROOTS, DishKind.CHOWDER, DishKind.PIE, DishKind.FISH, DishKind.ROAST)

    /** What a diner does that the plate stays through: sitting down, the moods of sitting, getting up. */
    private val PLATED = BooleanArray(Activity.entries.size).also { table ->
        for (a in listOf(Activity.SIT_DOWN, Activity.SIT, Activity.SIT_DRINK, Activity.SIT_TALK, Activity.SIT_GOSSIP, Activity.SIT_LAUGH, Activity.STAND_UP)) table[a.ordinal] = true
    }

    /** A dish as it is drawn: its stack (the item and its tier, as the player's own would be), its model, and the size of it. */
    internal class Dish(val stack: ItemStack, val steams: Boolean) {
        val render = ItemStackRenderState()
        val mid = Vector3f()
        var extent = 1f
        var thick = 0.03f
        var ok = false
        var at = Long.MIN_VALUE
    }

    /** A plate on a table: where it is, which dish, and when it was set down and when it was taken up (an entity's age in ticks). */
    internal class Plate(val spot: TownTables.Spot, val dish: Dish) {
        var since = INSTANT
        var ended = -1f

        /** How much of its size the plate has, 0 to 1: it grows out of the table as it is set down and shrinks away as it is taken up. */
        fun grow(age: Float): Float {
            var g = smooth(((age - since) / ARRIVE).coerceIn(0f, 1f))
            if (ended >= 0f) g = min(g, 1f - smooth(((age - ended) / LEAVE).coerceIn(0f, 1f)))
            return g
        }
    }

    /** What is known of one townsperson: the tavern verdict (for the meal and the seat it was made for) and the lunch one. */
    private class Seen(val who: Long) {
        var touched = 0L
        var serial = -1
        var cell = Long.MIN_VALUE
        var checked = Long.MIN_VALUE
        var start = Int.MIN_VALUE
        var plate: Plate? = null
        var lunchChecked = Long.MIN_VALUE
        var lunch: ItemStack? = null
    }

    private val folk = Int2ObjectOpenHashMap<Seen>()
    private val dishes = HashMap<Int, Dish>()

    /** The held sets with the mug taken out, by the cached set they are made from (`Props.held` gives few, shared and never changed). */
    private val swapped = IdentityHashMap<Set<String>, Set<String>>()

    private var on = false
    private var now = 0L
    private var diners = 0

    /** Readouts for `cook perf`: sitters looked at, the nanoseconds that took, and the slowest look. */
    private var looks = 0L
    private var nanos = 0L
    private var slowest = 0L

    fun register(modBus: IEventBus) {
        CookPayloads.onServed = { payload -> ServedState.accept(payload) }
        ServedCommand.clientStats = { stats() }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
    }

    // ---- Each tick ----

    private fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        if (level == null) {
            if (ServedState.count > 0 || folk.size > 0) reset()
            return
        }
        ServedState.tick(level)
        now = level.gameTime
        val wanted = (!GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.TOWN_PLATES.get()) && Cooking.townKitchens()
        if (!wanted) {
            // Off is as it was: nothing kept, nothing drawn
            if (on || folk.size > 0) forget()
            on = false
            return
        }
        on = true
        if (folk.isEmpty()) return
        if (now % 10L == 0L) steam(level)
        if (now % 20L == 0L) tend()
    }

    /** Lets go of those not seen lately, and counts the diners who are still at it. */
    private fun tend() {
        var count = 0
        val each = folk.values.iterator()
        while (each.hasNext()) {
            val seen = each.next()
            if (now - seen.touched > KEEP) {
                each.remove()
                continue
            }
            if (now - seen.touched > 40L) continue
            val plate = seen.plate
            if (plate != null && plate.ended < 0f || seen.lunch != null) count++
        }
        diners = count
    }

    /** A wisp of steam now and then off the plates that steam, near enough to be seen. */
    private fun steam(level: ClientLevel) {
        if (!TownKitchen.particlesOn()) return
        val player = Minecraft.getInstance().player ?: return
        val random = level.random
        for (seen in folk.values) {
            val plate = seen.plate ?: continue
            if (!plate.dish.steams || plate.ended >= 0f || now - seen.touched > 20L) continue
            val spot = plate.spot
            if (player.distanceToSqr(spot.x, spot.y, spot.z) > 576.0 || random.nextInt(3) != 0) continue
            // The cook's own steam (the game's white smoke while it has no provider)
            TownKitchen.puff(level, CookParticles.STEAM.get(), ParticleTypes.WHITE_SMOKE, Vec3(spot.x, spot.y + 0.05, spot.z), 1, 0.12, 0.016)
        }
    }

    private fun forget() {
        folk.clear()
        diners = 0
    }

    private fun reset() {
        forget()
        dishes.clear()
        swapped.clear()
        ServedState.reset()
        on = false
    }

    internal fun stats(): String {
        val each = if (looks > 0L) nanos / looks else 0L
        return "${if (on) "on" else "off"}, $diners diners of at most $MAX_DINERS, ${ServedState.count} meals live; $looks looks at $each ns each, ${slowest / 1000L} us the slowest"
    }

    // ---- The tavern's tables ----

    /**
     * Called for each townsperson as their render state is filled in (LivingProps.extract, before it decides what Living World
     * clip props show): a diner at a table of the inn gets their [TownsfolkRenderState.living] plate for [TownPlateLayer] to
     * draw, and in `sit_drink` the spoon where the mug was (applied here, after the cached held set is looked up, never inside it).
     */
    internal fun eat(p: Townsperson, state: TownsfolkRenderState) {
        if (!on) return
        val activity = p.shownActivity
        if (!PLATED[activity.ordinal]) return
        if (!ServedState.any && folk.get(p.id)?.plate == null) return
        val started = System.nanoTime()
        val age = state.ageInTicks
        val plate = plateOf(p, activity, age)
        if (plate != null && plate.grow(age) > 0f) {
            state.living.plate = plate
            // Eating, not drinking: the spoon in the hand that held the mug
            if (activity == Activity.SIT_DRINK && plate.ended < 0f) {
                state.held = spoon(state.held)
                state.living.shown += "spoon"
            }
        }
        val took = System.nanoTime() - started
        looks++
        nanos += took
        if (took > slowest) slowest = took
    }

    /** The plate [p] has in front of them now, deciding it if it has not been decided for this meal and this seat; null if none. */
    private fun plateOf(p: Townsperson, activity: Activity, age: Float): Plate? {
        if (p.role == TownRole.CHILD) return null
        val seen = seenOf(p)
        val meal = ServedState.tavernAt(p.blockX, p.blockY, p.blockZ)
        var plate = seen.plate
        if (meal == null || activity == Activity.STAND_UP) {
            // Getting up, or the meal is over: taken up, and gone for good once the meal is over
            if (plate == null) return null
            if (plate.ended < 0f) plate.ended = age
            else if (meal == null && plate.grow(age) <= 0f) {
                seen.plate = null
                return null
            }
            return plate
        }
        val cell = p.blockPosition().asLong()
        if (seen.serial != meal.serial || seen.cell != cell || plate == null && now - seen.checked > RECHECK) {
            plate = lay(p, meal, activity, age)
            seen.plate = plate
            seen.serial = meal.serial
            seen.cell = cell
            seen.checked = now
            seen.start = p.shownStart
        } else if (plate != null && activity == Activity.SIT_DOWN && seen.start != p.shownStart) {
            // Sat down at the same table again: the plate is set down again
            plate.since = age
            plate.ended = -1f
            seen.start = p.shownStart
        }
        return plate
    }

    /** The plate [p] is to have for [meal], or null if they are not one of the diners or there is no table with room for one. */
    private fun lay(p: Townsperson, meal: ServedState.Meal, activity: Activity, age: Float): Plate? {
        val who = who(p, meal)
        if (unit(mix(who, SALT_TAVERN)) >= chance(meal.tavernTotal) || diners >= MAX_DINERS) return null
        val kind = meal.pick(meal.tavern, meal.tavernTotal, unit(mix(who, SALT_KIND)))
        if (kind < 0) return null
        val spot = TownTables.spot(p.level(), p.blockPosition()) ?: return null
        val dish = dishFor(DishKind.entries[kind], meal.tierOf(kind))
        if (!ready(dish, p)) return null
        diners++
        val plate = Plate(spot, dish)
        // Set down in front of someone sitting down, or of those already seated when the meal is served; else it was always there
        plate.since = if (activity == Activity.SIT_DOWN || now - meal.arrived < FRESH) age else INSTANT
        return plate
    }

    /** The mug taken out of [held] (shared and never changed, so the answer is made once for each). */
    private fun spoon(held: Set<String>): Set<String> {
        if ("mug" !in held) return held
        return swapped.getOrPut(held) { Collections.unmodifiableSet(HashSet(held).also { it.remove("mug") }) }
    }

    /**
     * A diner's sip is a spoon clink on the bowl in front of them, quieter than a mug's sip (TownsfolkClient's `sit_drink`
     * moment, tick 38): plays it and answers yes when [p] is one of the diners and so it is not the mug's sip.
     */
    internal fun sip(p: Townsperson): Boolean {
        if (!on || !ServedState.any || p.shownActivity != Activity.SIT_DRINK) return false
        val plate = plateOf(p, Activity.SIT_DRINK, p.tickCount.toFloat()) ?: return false
        if (plate.ended >= 0f) return false
        val share = TownKitchen.soundShare()
        if (share > 0.01f) {
            val voice = 0.92f + Math.floorMod(p.uuid.leastSignificantBits, 17L) / 100f
            val spot = plate.spot
            p.level().playLocalSound(spot.x, spot.y + 0.08, spot.z, CookSounds.get("cook.eat.clink"), SoundSource.NEUTRAL, 0.55f * share, voice, false)
        }
        return true
    }

    // ---- Out of doors at lunch ----

    /** The goods in hand of the clips `eat` and `sit_eat` for a lunch-eater who has a baked dish (the dish takes the place of the loaf, and sits in the bowl). */
    private val EAT_SPANS = listOf(LivingSchedule.Goods(0f, LivingSchedule.END, LivingSchedule.How.PART, "right_arm", -1f, 11.5f, -2.7f, "", 0f, 0f, 0f, 0f, 6f))
    private val SIT_SPANS = listOf(LivingSchedule.Goods(0f, LivingSchedule.END, LivingSchedule.How.PART, "left_arm", 1f, 8.4f, 2.7f, "", 0f, 0f, 0f, 0f, 4.6f))

    /**
     * The stack [p] is shown with where goods are shown (LivingProps.goods; the crumbs of a bite): [carried], or, for someone
     * eating out of doors at lunch while something baked was served who is one of the diners, the dish of a baked kind served
     * (a pie, a tart, cakes, a loaf, strips) in place of their bread.
     */
    fun shown(p: Townsperson, carried: ItemStack): ItemStack {
        if (!on || !ServedState.any) return carried
        return lunchOf(p) ?: carried
    }

    /** The goods spans of the lunch-eater [p]'s [clip] when they have a dish in hand and the clip has none of its own; null otherwise. */
    internal fun inHand(p: Townsperson, clip: String): List<LivingSchedule.Goods>? {
        if (!on || !ServedState.any) return null
        val spans = when (clip) {
            "eat" -> EAT_SPANS
            "sit_eat" -> SIT_SPANS
            else -> return null
        }
        return if (lunchOf(p) != null) spans else null
    }

    /** The prop of [clip] the dish in hand takes the place of: the loaf of a standing lunch (a bowl stays a bowl, the dish in it). */
    internal fun replaces(clip: String): String? = if (clip == "eat") "loaf" else null

    private fun lunchOf(p: Townsperson): ItemStack? {
        val activity = p.shownActivity
        if (activity != Activity.EAT && activity != Activity.SIT_EAT) return null
        val seen = seenOf(p)
        if (seen.lunchChecked != Long.MIN_VALUE && now - seen.lunchChecked < RECHECK) return seen.lunch
        seen.lunchChecked = now
        seen.lunch = decideLunch(p, seen.lunch)
        return seen.lunch
    }

    private fun decideLunch(p: Townsperson, before: ItemStack?): ItemStack? {
        val meal = ServedState.bakedNear(p.x, p.z) ?: return null
        if (!meal.lunch && !meal.test) return null
        // Out of doors: nothing over their head but sky (a house table is under a roof, and is never swapped)
        if (p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, p.blockX, p.blockZ) > p.blockY + 2) return null
        val who = who(p, meal)
        if (unit(mix(who, SALT_LUNCH)) >= chance(meal.bakedTotal)) return null
        if (before == null && diners >= MAX_DINERS) return null
        val kind = meal.pick(meal.baked, meal.bakedTotal, unit(mix(who, SALT_LUNCH_KIND)))
        if (kind < 0) return null
        if (before == null) diners++
        return dishFor(DishKind.entries[kind], meal.tierOf(kind)).stack
    }

    // ---- The record of a townsperson, the dishes, the dice ----

    private fun seenOf(p: Townsperson): Seen {
        val who = p.uuid.leastSignificantBits
        val old = folk.get(p.id)
        if (old != null && old.who == who) {
            old.touched = now
            return old
        }
        val fresh = Seen(who)
        fresh.touched = now
        folk.put(p.id, fresh)
        return fresh
    }

    private fun dishFor(kind: DishKind, tier: Tier): Dish =
        dishes.getOrPut(kind.ordinal * 8 + tier.ordinal) {
            val stack = ItemStack(CookRegistry.DISHES.getValue(kind).get())
            stack.set(CookRegistry.DISH_TIER.get(), tier.ordinal)
            Dish(stack, kind in STEAMING)
        }

    /** Makes [dish]'s model ready to draw (looked up again now and then, for a resource reload), and whether it has one. */
    private fun ready(dish: Dish, p: Townsperson): Boolean {
        if (dish.ok && now - dish.at < REFRESH) return true
        Minecraft.getInstance().itemModelResolver.updateForLiving(dish.render, dish.stack, ItemDisplayContext.FIXED, p)
        dish.at = now
        dish.ok = false
        if (dish.render.isEmpty) return false
        val box = dish.render.modelBoundingBox
        dish.mid.set(((box.minX + box.maxX) * 0.5).toFloat(), ((box.minY + box.maxY) * 0.5).toFloat(), ((box.minZ + box.maxZ) * 0.5).toFloat())
        dish.extent = maxOf(box.xsize, box.ysize, box.zsize).toFloat().coerceAtLeast(1e-3f)
        dish.thick = box.zsize.toFloat()
        dish.ok = true
        return true
    }

    /** The chance a sitter or an eater is one of the diners for [portions] served (7.4): `min(0.8, 0.15 + 0.05 x portions)`. */
    private fun chance(portions: Int): Double = if (portions <= 0) 0.0 else min(0.8, 0.15 + 0.05 * portions)

    /** What the dice for [p] and [meal] are thrown from: who they are, and which meal (a made-up meal each time it is told, so telling it again draws afresh). */
    private fun who(p: Townsperson, meal: ServedState.Meal): Long {
        val seed = if (meal.test) meal.serial.toLong() else meal.window.toLong()
        return mix(p.uuid.mostSignificantBits xor mix(p.uuid.leastSignificantBits, 17L), seed)
    }

    private const val SALT_TAVERN = 1L
    private const val SALT_KIND = 2L
    private const val SALT_LUNCH = 3L
    private const val SALT_LUNCH_KIND = 4L

    /** A well-mixed 64 bits from [a] and [b] (the finish of SplitMix64). */
    private fun mix(a: Long, b: Long): Long {
        var z = a + b * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** [bits] as a number from 0 up to but not including 1. */
    private fun unit(bits: Long): Double = (bits ushr 11) * (1.0 / (1L shl 53))

    private fun smooth(u: Float): Float = u * u * (3f - 2f * u)
}
