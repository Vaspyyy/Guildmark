package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.recipe.Recognise
import io.github.vaspyyy.guildmark.cooking.recipe.Roles
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import kotlin.math.max

/**
 * The clicks the four stations share (living-world/cook/design.md 1.1, 1.2, 1.4): pouring the pot's water, putting an
 * ingredient in, the one accent a SEASON call asks for, starting the clock, answering a call, and the wear of a tool. Each
 * station composes them with its own tools in its own `use`. A refusal says why in the action bar and takes nothing.
 */
internal object Actions {
    // ---- The tools ----

    /** Which of the cook's tools [stack] is, or null. */
    fun toolOf(stack: ItemStack): CookTool? = (stack.item as? CookToolItem)?.tool

    /** Whether [stack] is a ladle: the Cook's Ladle or the Gilded one. */
    fun isLadle(stack: ItemStack): Boolean {
        val tool = toolOf(stack)
        return tool == CookTool.LADLE || tool == CookTool.GILDED_LADLE
    }

    fun isKnife(stack: ItemStack): Boolean = toolOf(stack) == CookTool.KNIFE

    fun isTurner(stack: ItemStack): Boolean = toolOf(stack) == CookTool.TURNER

    /** A use of a tool costs it one point of durability (the Gilded Ladle has none to lose). */
    fun wear(player: ServerPlayer, hand: InteractionHand, stack: ItemStack) {
        stack.hurtAndBreak(1, player, hand)
    }

    /** Puts [stack] in the pack of [player], and what does not fit at the foot of the station at [at] (a full pack never strands a portion, 9.6). */
    fun give(level: ServerLevel, at: BlockPos, player: ServerPlayer, stack: ItemStack) {
        if (stack.isEmpty) return
        player.inventory.add(stack)
        if (!stack.isEmpty) Block.popResource(level, at.above(), stack)
    }

    // ---- The pot's water ----

    /** Pours a bucket of water into the pot: a full bucket fills it to the brim, and only while the pot is still open. */
    fun pour(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer, hand: InteractionHand): Used {
        val v = be.vessel
        if (v.station != Station.STEWPOT) {
            be.say(player, "not_for." + v.station.name.lowercase())
            return Used.REFUSED
        }
        if (!v.open) {
            be.say(player, "closed")
            return Used.REFUSED
        }
        if (v.water >= Vessel.WATER_FULL) {
            be.say(player, "water_full")
            return Used.REFUSED
        }
        v.water = Vessel.WATER_FULL
        v.dirty = true
        if (!player.hasInfiniteMaterials()) player.setItemInHand(hand, ItemStack(Items.BUCKET))
        be.ring(level, "cook.pour", 1.0f, 1.0f)
        return Used.DONE
    }

    // ---- Ingredients and the accent ----

    /** The items a SEASON call takes (1.4): a herb, honey, an egg, milk or a mushroom, and a bone where there is no herb (4.4). */
    fun isAccent(stack: ItemStack): Boolean =
        stack.`is`(CookRegistry.HERB_SPRIG.get()) || stack.`is`(Items.HONEY_BOTTLE) || stack.`is`(Items.EGG) || stack.`is`(Items.MILK_BUCKET) ||
            stack.`is`(Items.BROWN_MUSHROOM) || stack.`is`(Items.RED_MUSHROOM) || stack.`is`(Items.BONE)

    /**
     * Puts the item in [stack] into the vessel, one a click (1.1): the pot, the pan, the tray or the rod. While a SEASON call is
     * pending the vessel takes the accent it asks for instead. An item the cook's data does not know as an ingredient is
     * "not for" the station, a vessel already on the heat takes nothing, and a full one takes no more.
     */
    fun ingredient(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer, stack: ItemStack): Used {
        val v = be.vessel
        if (v.call == Call.SEASON && v.cooking && isAccent(stack)) return accent(be, level, player, stack)
        val weights = Roles.of(stack)
        if (weights == null) {
            be.say(player, "not_for." + v.station.name.lowercase())
            return Used.REFUSED
        }
        if (!v.open) {
            be.say(player, if (v.state == Vessel.State.BURNT) "burnt" else "closed")
            return Used.REFUSED
        }
        if (v.items.size >= v.capacity) {
            be.say(player, "full")
            return Used.REFUSED
        }
        v.add(stack, player.uuid, player.name.string)
        spend(be, level, player, stack)
        be.noteAdded(stack)
        be.ring(level, plop(weights), 0.8f, 0.9f + level.random.nextFloat() * 0.2f)
        be.puff(level, CookParticles.STEAM.get(), 2, 0.12)
        CookActions.play(player, CookAction.ADD, be.blockPos)
        return Used.DONE
    }

    /** The plop of an item going in, pitched by what it mostly is (1.5 row 3). */
    private fun plop(weights: Map<Role, Double>): String {
        var best: Role? = null
        var top = 0.0
        for ((role, w) in weights) {
            if (w > top) {
                top = w
                best = role
            }
        }
        return when (best) {
            Role.MEAT, Role.SEA -> "cook.plop.meat"
            Role.BODY -> "cook.plop.grain"
            else -> "cook.plop.soft"
        }
    }

    /**
     * Takes one of [stack] from the hand (a creative player keeps it) and gives back the container the cook's data says it
     * leaves behind: the bucket of a milk bucket, the bottle of a honey bottle (Part A's `Roles.returns`).
     */
    private fun spend(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer, stack: ItemStack) {
        if (player.hasInfiniteMaterials()) return
        val left = Roles.returns(stack)
        stack.shrink(1)
        if (!left.isEmpty) be.give(level, player, left)
    }

    /**
     * The one accent a SEASON call asks for: it goes in as a pinch (it does not take a place in the pot) and shifts the locked
     * mix by one item's weight, and it answers the call.
     */
    private fun accent(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer, stack: ItemStack): Used {
        val v = be.vessel
        val weights = Roles.of(stack)
        if (weights != null) {
            val sum = weights.values.sum()
            val n = max(1, v.items.size).toDouble()
            if (sum > 0.0) {
                for (role in Role.entries) {
                    val w = (weights[role] ?: 0.0) / sum
                    v.share[role.ordinal] = (v.share[role.ordinal] * n + w) / (n + 1.0)
                }
            }
        }
        v.accents++
        v.resolve(level.gameTime, player.uuid, player.name.string, level.random)
        spend(be, level, player, stack)
        be.noteAdded(stack)
        be.ring(level, "cook.plop.soft", 0.7f, 1.2f)
        be.puff(level, CookParticles.STEAM.get(), 4, 0.15)
        CookActions.play(player, CookAction.ADD, be.blockPos)
        return Used.DONE
    }

    // ---- The clock ----

    /**
     * The first stir, the shut door or the first turn of the crank (1.2): starts the clock if the fire is lit and has fuel, a
     * pot has water, and there is enough in the vessel. The contents are taken for a dish (2.4) and locked. A refusal says
     * which condition failed and takes nothing.
     */
    fun start(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer): Used {
        val v = be.vessel
        val refusal = v.startRefusal(be.env.free())
        if (refusal != null) {
            be.say(player, refusal)
            return Used.REFUSED
        }
        val found = Recognise.run(level, v.station, v.items, Skills.level(player, Skill.COOKING))
        v.begin(found.kind, max(1, found.portions), found.share, found.distance, level.gameTime)
        be.say(player, "starting")
        return Used.DONE
    }

    /**
     * Answers the vessel's pending call if it is one of [calls] by [player] (the credit is theirs); false if no call is pending or
     * it is another one. The heat's own calls, BOIL_OVER and FLARE, are answered at the heat control.
     */
    fun answer(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer, vararg calls: Call): Boolean {
        val pending = be.vessel.call ?: return false
        if (pending !in calls) return false
        be.vessel.resolve(level.gameTime, player.uuid, player.name.string, level.random)
        return true
    }
}
