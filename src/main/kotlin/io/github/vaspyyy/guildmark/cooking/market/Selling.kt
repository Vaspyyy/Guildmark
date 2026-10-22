package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.dish.Journal
import io.github.vaspyyy.guildmark.cooking.recipe.Hints
import io.github.vaspyyy.guildmark.cooking.recipe.Tastes
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/** Why a dish was not taken at a door (7.7): the keeper says the line that fits, and the dish stays in the hand. */
enum class Refusal {
    /** Not a dish a door buys: Gruel (the alms table's alone), Scorched mess, or something else in the hand. */
    NOT_A_DISH,

    /** The back doors are switched off (`kitchenDoors`, Hearthcraft or Capital Markets), or the capital has no market open. */
    DOORS_OFF,

    /** A batch that came out Spoilt is not a dish anyone buys. */
    SPOILT,

    /** The dish has gone off (3.2): it pays nothing and is not taken. */
    GONE_OFF,

    /** The keeper's door does not buy this kind: the bakery, offered a stew, or the inn, offered a loaf. */
    WRONG_DOOR,

    /** The door has taken all it will of this kind, or of anything, today (or all that the player may take with another cook about). */
    FULL,
}

/**
 * What selling a stack at a door comes to, worked out and not written: [taken] portions of [kind] for [marks] whole Marks
 * (with [kept] carried), or why none are ([refusal]). The back door, the `cook door` command and the sale itself all read one
 * of these, so what the command shows is what the sale pays. [request] portions go to the day's request and [atDoor] to the
 * door; [room] is what the door would have taken of the kind before this sale. [locked]: the dish is the day's request but the
 * player's standing is below Known ([Requests.RANK]), so it is sold at the door as any dish.
 */
class Deal(
    val door: Door,
    val kind: DishKind?,
    val tier: Tier,
    val stage: Stage,
    val refusal: Refusal?,
    val hand: Int,
    val request: Int = 0,
    val atDoor: Int = 0,
    val room: Int = 0,
    val first: Boolean = false,
    val wanted: Boolean = true,
    val asked: Boolean = false,
    val index: Double = 1.0,
    val season: Double = 1.0,
    val palate: Double = 1.0,
    val value: Double = 0.0,
    val rank: Int = 0,
    val share: Double = 0.6,
    val total: Double = 0.0,
    val carry: Double = 0.0,
    val marks: Int = 0,
    val kept: Double = 0.0,
    val locked: Boolean = false,
) {
    /** Portions that leave the hand. */
    val taken: Int get() = request + atDoor

    /** The door can take no more of this kind today after this sale. */
    val full: Boolean get() = refusal == null && room - atDoor <= 0
}

/**
 * The arithmetic of a sale (living-world/cook/design.md 5.2, 7.7): [quote] works out what a door would pay for a held
 * stack and writes nothing, [commit] carries it out in one step on the server thread. The value of a portion is
 * [Pricing.value] with the capital's index, the season's and the palate's factors; a door pays the standing's share of it,
 * the inn three quarters of that for a kind it is not asking for, and twice that for the first batch of a kind from a player;
 * the request pays 1.15 times the value to anyone, and counts for nothing against the inn's caps.
 */
object Selling {
    /** A deal that came to nothing, with [refusal] as the reason. */
    fun refused(door: Door, kind: DishKind?, tier: Tier, stage: Stage, hand: Int, refusal: Refusal): Deal =
        Deal(door, kind, tier, stage, refusal, hand)

    /**
     * What selling [stack] at [door] of the capital keyed [capital] would come to for [player] now, in a town of [style] (the
     * look of the capital, which sets its palate; the capital's own if it is loaded, else the temperate one). It writes no
     * sale: the only thing it does to the ledger is bring a record of another day to today (its counters zeroed, its stale
     * lots dropped), which is the same wherever and whenever it is done.
     */
    fun quote(level: ServerLevel, kitchens: Kitchens, capital: Long, door: Door, player: ServerPlayer, stack: ItemStack, style: CityStyle? = null): Deal {
        val kind = Dishes.kindOf(stack)
        val data = Dishes.dataOf(stack)
        if (kind == null || data == null) return refused(door, null, Tier.PLAIN, Stage.FRESH, stack.count, Refusal.NOT_A_DISH)
        val tier = data.tier
        val stage = Dishes.stage(level, stack)
        val hand = stack.count
        if (!Cooking.doors() || !Markets.enabled()) return refused(door, kind, tier, stage, hand, Refusal.DOORS_OFF)
        val entry = Markets.find(level, capital) ?: return refused(door, kind, tier, stage, hand, Refusal.DOORS_OFF)
        if (tier == Tier.SPOILT) return refused(door, kind, tier, stage, hand, Refusal.SPOILT)
        if (stage == Stage.GONE) return refused(door, kind, tier, stage, hand, Refusal.GONE_OFF)

        val day = Cooking.today(level)
        val uuid = player.uuid
        val rec = kitchens.rolled(capital, day)
        val stand = rec ?: Rec(capital)
        val people = entry.town.people
        val wanted = kitchens.wanted(level, capital, day)
        val ask = kitchens.request(level, capital, day)
        val pos = BlockPos.of(capital)
        val rank = Suppliers.rank(level, pos, uuid)
        val asked = door == Door.INN && ask != null && ask.kind == kind && !ask.filled
        val known = Requests.open(rank)
        val request = if (asked && known && hand >= Requests.PORTIONS) Requests.PORTIONS else 0
        val room = Doors.room(stand, door, kind, uuid, people, day)
        val atDoor = minOf(room, hand - request).coerceAtLeast(0)
        if (request == 0 && !Doors.takes(door, kind)) return refused(door, kind, tier, stage, hand, Refusal.WRONG_DOOR)
        if (request + atDoor == 0) return refused(door, kind, tier, stage, hand, Refusal.FULL)

        val season = Requests.season(level, day)?.let { Tastes.season(kind, it) } ?: 1.0
        val palate = Tastes.palate(style ?: styleOf(level, capital, player), kind)
        val index = Index.of(rec, entry)
        val value = Pricing.value(kind, tier, stage, index, season, palate)
        val share = Suppliers.share(level, pos, uuid)
        val liked = door != Door.INN || kind in wanted
        val first = atDoor > 0 && !Doors.tasted(stand, uuid, kind)
        val perDoor = value * share * (if (liked) 1.0 else Doors.UNWANTED) * (if (first) Doors.FIRST else 1.0)
        val total = atDoor * perDoor + request * value * Doors.REQUEST
        val carry = Doors.carried(stand, door, uuid)
        val paid = Doors.count(total, carry)
        return Deal(
            door, kind, tier, stage, null, hand, request, atDoor, room, first, liked, asked, index, season, palate, value, rank, share,
            total, carry, paid.marks, paid.carry, asked && !known,
        )
    }

    /**
     * Carries out [deal] (its [quote], made just now) for [player] holding [stack]: the counters, the carry, the first-of-kind
     * mask, the request and the lots are written, the portions leave the stack, and nothing is paid (the door does that, in
     * the order of its scene, or [Owed.settle] does at once if the scene is cut short). One step, so two players at one door
     * each see the other's caps and nothing is taken or paid twice.
     */
    fun commit(level: ServerLevel, kitchens: Kitchens, capital: Long, player: ServerPlayer, stack: ItemStack, deal: Deal): Sale {
        val kind = deal.kind
        if (deal.refusal != null || kind == null || deal.taken <= 0 || deal.taken > stack.count) {
            return Sale(0, 0, false, deal.tier, deal.stage, null, deal.refusal == Refusal.FULL, 0, deal.refusal ?: Refusal.FULL, kind)
        }
        val day = Cooking.today(level)
        val rec = kitchens.rolled(capital, day, create = true) ?: return Sale(0, 0, false, deal.tier, deal.stage, null, false, 0, Refusal.DOORS_OFF, kind)
        val uuid = player.uuid
        val d = deal.door.ordinal
        // The mix is read before the portions leave the stack
        val mix = DishMix.of(stack)
        val verdict = mix?.let { Hints.verdict(level, kind, it) }
        if (deal.atDoor > 0) {
            rec.sold[d][kind.ordinal] += deal.atDoor
            rec.mine.getOrPut(uuid) { IntArray(Rec.DOORS) }[d] += deal.atDoor
            rec.seen[d][uuid] = day
            if (deal.first) Doors.taste(rec, uuid, kind)
        }
        rec.carry.getOrPut(uuid) { DoubleArray(Rec.DOORS) }[d] = deal.kept
        if (deal.request > 0) rec.asked = Asked(day, rec.asked.takeIf { it.day == day }?.salt ?: 0, true, uuid)
        stack.shrink(deal.taken)
        val name = player.name.string
        kitchens.addLot(level, capital, deal.door, Lot(kind, deal.tier, uuid, name, deal.atDoor, day))
        if (deal.request > 0) kitchens.addLot(level, capital, Door.INN, Lot(kind, deal.tier, uuid, name, deal.request, day))
        kitchens.changed()
        if (verdict != null) Journal.learn(player, kind, verdict)
        return Sale(deal.taken, deal.marks, deal.first, deal.tier, deal.stage, verdict, deal.full, deal.request, null, kind, deal.locked)
    }

    /**
     * The look of the capital keyed [capital], which sets its palate: found from where [player] stands (loaded for certain) when
     * that is the capital's ground, else from its centre, else the temperate one.
     */
    fun styleOf(level: ServerLevel, capital: Long, player: ServerPlayer): CityStyle {
        val here = Furnish.capitalAt(level, player.blockPosition())
        if (here != null && here.center.asLong() == capital) return here.style
        return Furnish.capitalAt(level, BlockPos.of(capital))?.style ?: here?.style ?: CityStyle.TEMPERATE
    }
}
