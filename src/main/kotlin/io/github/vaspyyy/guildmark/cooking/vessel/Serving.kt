package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookAction
import io.github.vaspyyy.guildmark.cooking.CookActions
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.dish.Journal
import io.github.vaspyyy.guildmark.cooking.market.DishMix
import io.github.vaspyyy.guildmark.cooking.recipe.CookXp
import io.github.vaspyyy.guildmark.cooking.recipe.Hints
import io.github.vaspyyy.guildmark.cooking.recipe.Ideal
import io.github.vaspyyy.guildmark.cooking.recipe.Quality
import io.github.vaspyyy.guildmark.cooking.recipe.Recognise
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import kotlin.math.max
import kotlin.math.min

/**
 * What a vessel does when a portion comes out of it (living-world/cook/design.md 1.1, 1.7, 2.4, 2.5, 9.6): the first serve
 * fixes the batch (its portions, its tier, whose name it carries, how long it keeps) and pays the XP; every serve hands over
 * one portion, with a bowl from the pack where the dish is a pot's; a taste tells the cook what the ladle makes of the mix;
 * and what is left in a vessel that is tipped out or broken is worked out here. Every other number is in [Vessel].
 */
internal object Serving {
    /** A boil-over takes a portion at the serve, and never takes a batch below this many (1.4). */
    const val BOIL_FLOOR = 2

    // ---- Serving ----

    /**
     * Serves one portion to [player] from a vessel that is ready, overdone or already served from; [action] is the motion
     * shown. The first portion fixes the batch. A vessel that is not there yet, or burnt, says so and nothing is taken.
     */
    fun serve(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer, action: CookAction): Used {
        val v = be.vessel
        when (v.state) {
            Vessel.State.READY, Vessel.State.DONE, Vessel.State.SERVED -> {}
            Vessel.State.BURNT -> {
                be.say(player, "burnt")
                return Used.REFUSED
            }
            Vessel.State.HEATING, Vessel.State.COOKING -> {
                be.say(player, "raw")
                return Used.REFUSED
            }
            Vessel.State.EMPTY, Vessel.State.FILLING -> {
                be.say(player, "nothing_to_serve")
                return Used.REFUSED
            }
        }
        val first = v.state != Vessel.State.SERVED
        val kind = v.kind
        if (first) freeze(be, level, player)
        val tier = v.tier ?: Tier.PLAIN
        val bowl = v.station == Station.STEWPOT && tier != Tier.SPOILT && takeBowl(player)
        val dish = portion(v, 1, bowl)
        // The last portion empties the vessel, so the stack is made first
        v.portionTaken()
        be.give(level, player, dish)
        val sound = when (action) {
            CookAction.SLICE -> "cook.slice"
            CookAction.CARVE -> "cook.carve"
            else -> "cook.serve"
        }
        be.ring(level, sound, 0.9f, 0.95f + level.random.nextFloat() * 0.1f)
        be.puff(level, CookParticles.STEAM.get(), 4, 0.15)
        CookActions.play(player, action, be.blockPos)
        if (first) announce(be, level, player, dish, tier, kind)
        return Used.DONE
    }

    /** The cook of the batch: the player who did most if they are here, else whoever serves. */
    private fun cookOf(level: ServerLevel, v: Vessel, server: ServerPlayer): ServerPlayer =
        v.who.top()?.let { level.getPlayerByUUID(it.id) as? ServerPlayer } ?: server

    /**
     * Fixes the batch at the first serve (2.5): the portions (less one for each boil-over, never under two, and more for the
     * cook's COOK_EXTRA), the tier from the mix, the timing, the attention, the care and the cook's level, and who is named on
     * the dish. The XP is paid and the Book told now, by each contributor's share.
     */
    private fun freeze(be: VesselBlockEntity, level: ServerLevel, server: ServerPlayer) {
        val v = be.vessel
        val kind = v.kind
        val cook = cookOf(level, v, server)
        val top = v.who.top()
        val name = top?.name?.takeIf { it.isNotEmpty() } ?: cook.name.string
        val id = top?.id ?: cook.uuid
        var total = max(1, v.portions)
        total = max(min(BOIL_FLOOR, total), total - v.boils)
        val tier: Tier
        if (kind == null) {
            tier = Tier.PLAIN
        } else {
            total += Recognise.extra(cook, kind, total)
            val mix = Quality.mix(v.share, Ideal.of(level, kind))
            val care = Prep.care(v.items, v.attention())
            tier = Quality.tier(Quality.q(mix, v.timing(), v.attention(), care, Quality.bonus(cook)))
        }
        v.freeze(tier, total, name, id, Cooking.today(level), Quality.keep(cook))
        reward(level, v, kind, tier, total, server)
    }

    /** Pays each contributor who is here their share of the batch's XP and tells their Book (1.7); one who is away loses only their share. */
    private fun reward(level: ServerLevel, v: Vessel, kind: DishKind?, tier: Tier, total: Int, server: ServerPlayer) {
        if (kind == null) return
        if (v.who.isEmpty) {
            CookXp.award(server, kind, tier, total, 1.0)
            Journal.batch(server, kind, v.items, tier)
            return
        }
        for (e in v.who.all) {
            val p = level.getPlayerByUUID(e.id) as? ServerPlayer ?: continue
            CookXp.award(p, kind, tier, total, v.who.share(e))
            Journal.batch(p, kind, v.items, tier)
        }
    }

    /** The action-bar line of the first portion, and the motif of its tier (1.5 row 18). */
    private fun announce(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer, dish: ItemStack, tier: Tier, kind: DishKind?) {
        val key = when {
            tier == Tier.SPOILT -> "served.spoilt"
            kind == null -> "served.gruel"
            else -> "served." + tier.name.lowercase()
        }
        // The line is the name the dish bears: Part D puts the tier first in it ("Fine Drover's Stew"), so the line adds none
        player.sendOverlayMessage(Component.translatable("message.guildmark.cook.$key", dish.hoverName))
        val sound = when (tier) {
            Tier.PLAIN -> "cook.tier.plain"
            Tier.SOUND -> "cook.tier.sound"
            Tier.FINE -> "cook.tier.fine"
            Tier.HALLMARKED -> "cook.tier.hallmark"
            Tier.SPOILT -> null
        }
        if (kind != null && sound != null) be.ring(level, sound, 0.9f, 1.0f)
        if (kind != null && tier == Tier.HALLMARKED) be.puff(level, CookParticles.CALL.get(), 16, 0.45)
    }

    /** Takes a bowl from the pack for a pot's portion; false if there is none (the portion then comes without one, 1.1). */
    private fun takeBowl(player: ServerPlayer): Boolean {
        if (player.hasInfiniteMaterials()) return true
        for (slot in player.inventory.nonEquipmentItems) {
            if (!slot.isEmpty && slot.`is`(Items.BOWL)) {
                slot.shrink(1)
                player.inventory.setChanged()
                return true
            }
        }
        return false
    }

    // ---- What a portion is ----

    /**
     * [count] portions of the frozen batch: its dish, carrying the batch's mix for a keeper to taste (5.2); Gruel for a pot that
     * could name nothing; Scorched mess for a batch that came out Spoilt. A portion without a [bowl] has no bowl to give back
     * when it is eaten (1.1).
     */
    fun portion(v: Vessel, count: Int, bowl: Boolean): ItemStack {
        val tier = v.tier ?: Tier.PLAIN
        val data = DishData(tier, v.day, v.cookName, v.cookId, v.keep)
        val kind = v.kind
        val stack = when {
            tier == Tier.SPOILT -> other(CookRegistry.SCORCHED_MESS.get(), data, count)
            kind == null -> other(CookRegistry.GRUEL.get(), data, count)
            else -> Dishes.make(kind, data, count).also { DishMix.stamp(it, v.share) }
        }
        if (!bowl) stack.remove(DataComponents.USE_REMAINDER)
        return stack
    }

    /** A Gruel or a Scorched mess: an item of no kind, with the dish component all the same (3.1). */
    private fun other(item: DishItem, data: DishData, count: Int): ItemStack {
        val stack = ItemStack(item, count)
        stack.set(CookRegistry.DISH.get(), data)
        stack.set(CookRegistry.DISH_TIER.get(), data.tier.ordinal)
        return stack
    }

    // ---- Taste ----

    /**
     * Sneak and the ladle (2.6): what the ladle tells [player] about the mix as it stands while the pot fills, and about the
     * timing and the flavour once it cooks. A cook may taste a vessel once in six seconds.
     */
    fun taste(be: VesselBlockEntity, level: ServerLevel, player: ServerPlayer): Used {
        val v = be.vessel
        if (v.state == Vessel.State.BURNT) {
            be.say(player, "taste_burnt")
            return Used.REFUSED
        }
        if (v.items.isEmpty() && !v.started) {
            be.say(player, "taste_empty")
            return Used.REFUSED
        }
        if (!Calls.tryTaste(level.dimension().hashCode(), be.blockPos.asLong(), player.uuid, level.gameTime)) {
            be.say(player, "taste_wait")
            return Used.REFUSED
        }
        // The station is passed so that a pot taken for Gruel is told the dish of its own station that it is nearest to
        val hint = if (v.started) {
            Hints.taste(player, v.kind, v.share, if (v.cooking || v.state == Vessel.State.SERVED) v.p else null, v.speed, v.station)
        } else {
            val guess = Recognise.run(level, v.station, v.items, Skills.level(player, Skill.COOKING))
            Hints.taste(player, guess.kind, guess.share, null, 1.0, v.station)
        }
        if (hint.string.isEmpty()) be.say(player, "taste_none") else player.sendOverlayMessage(hint)
        be.ring(level, "cook.taste", 0.8f, 1.0f)
        CookActions.play(player, CookAction.TASTE, be.blockPos)
        return Used.DONE
    }

    // ---- What is left ----

    /**
     * What a tip-out drops (1.5 row 21, 9.6): the raw items of a vessel that was still open, Scorched mess for one that
     * burnt, and nothing for a dish in the making or a served one, which is lost with the pot's contents.
     */
    fun tipped(level: ServerLevel, v: Vessel): List<ItemStack> = when {
        v.state == Vessel.State.BURNT -> mess(level, v, max(1, v.portions))
        v.open -> v.items.map { it.copy() }
        else -> emptyList()
    }

    /**
     * What is left when the station is broken: what a tip-out would drop, and also the raw items of a dish that was still
     * cooking (it was never made) and the portions still in a served one.
     */
    fun broken(level: ServerLevel, v: Vessel): List<ItemStack> = when (v.state) {
        Vessel.State.BURNT -> mess(level, v, max(1, v.portions))
        Vessel.State.SERVED -> if (v.left > 0) listOf(portion(v, v.left, false)) else emptyList()
        else -> v.items.map { it.copy() }
    }

    private fun mess(level: ServerLevel, v: Vessel, count: Int): List<ItemStack> {
        val data = DishData(Tier.SPOILT, Cooking.today(level), v.who.top()?.name ?: "", v.who.top()?.id, 0)
        return listOf(other(CookRegistry.SCORCHED_MESS.get(), data, count))
    }

    /** Drops [stack] at [pos] in stacks of its size limit (a heap of Scorched mess is more than one stack). */
    fun drop(level: ServerLevel, pos: BlockPos, stack: ItemStack) {
        while (!stack.isEmpty) {
            Block.popResource(level, pos, stack.split(min(stack.count, stack.maxStackSize)))
        }
    }
}
