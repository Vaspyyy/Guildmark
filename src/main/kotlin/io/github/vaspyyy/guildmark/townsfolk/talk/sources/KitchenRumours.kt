package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Fade
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumour
import io.github.vaspyyy.guildmark.townsfolk.talk.Scope
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The three kitchen rumours (Hearthcraft, living-world/cook/design.md 8.3): a Hallmarked dish sold or served, a whole pot burnt at
 * the inn's back hearth, the Harvest Table won. Each is a call over [TownTalk.witness]: the residents who can see the place know
 * it first-hand, and it spreads by the usual rules (the hour's talk between them, the other capitals for what carries). They are
 * told by [KitchenTopics] (`kitchen.told.*`), never as news and never cried: the head `kitchen` is in RumourWords' OWN and THEIRS.
 *
 * The same thing told again within three days (the same cook's, the same pot burnt) heats the rumour going round instead of
 * making another, as for every rumour. With Kitchen Talk off a call does nothing. The ledger, the guest pot and the Harvest Table
 * call these; so does `cook rumour`.
 */
object KitchenRumours {
    /** The kinds of the three rumours. */
    const val HALLMARK = "kitchen.hallmark"
    const val COOKOFF = "kitchen.cookoff"
    const val BURNT = "kitchen.burnt"

    /** How far a back door's and a hearth's onlookers are, and the crowd round the Harvest Table (blocks). */
    private const val NEAR = 16.0
    private const val CROWD = 48.0

    /** What `cook perf` reads: the rumours begun since the server started (one that only heated another is not counted). */
    @JvmField
    var begun = 0L

    /** The places a Hallmarked sale is told of, the third argument of its rumour (a plain word, never a lang key). */
    const val AT_INN = "inn"
    const val AT_BAKERY = "bakery"

    /** The lang key of [kind]'s name ("Raised Pie"), as a Speech argument. */
    fun dish(kind: DishKind): String = "item.guildmark.dish_${kind.id}"

    /**
     * [cook] sold a Hallmarked dish of [kind] at a back door at [at] (the keeper's place), or had one served at the Harvest Table: the
     * residents within 16 blocks who can see the place know it, and where it was too (the inn's, the bakery's) if a keeper stands there.
     * Heat 0.5, small talk, the town's, good. The rumour begun, or null if no one saw it or it heated the same one going round.
     */
    fun hallmark(level: ServerLevel, at: BlockPos, cook: ServerPlayer, kind: DishKind): Rumour? {
        if (!Cooking.talk()) return null
        val args = listOfNotNull(Witness.named(cook.name.string), dish(kind), place(level, at))
        return counted(TownTalk.witness(level, at, HALLMARK, cook.uuid, -1, args, 0.5f, Fade.SMALL, Scope.TOWN, 1, NEAR))
    }

    /**
     * A guest pot burnt at [at] with six portions or more in it: whoever can see the inn's back hearth knows that a whole pot burnt (it is
     * not said whose: the call has no cook, and a burnt pot is a kindness to leave unnamed). Heat 0.3, small talk, the town's, bad.
     */
    fun burnt(level: ServerLevel, at: BlockPos): Rumour? {
        if (!Cooking.talk()) return null
        return counted(TownTalk.witness(level, at, BURNT, null, -1, listOf(), 0.3f, Fade.SMALL, Scope.TOWN, -1, NEAR))
    }

    /**
     * [winner] won the Harvest Table with a dish of [kind] at the fair centred on [centre]: the crowd round the table, out to 48 blocks
     * with a clear line to it, saw the winner named. Heat 0.8, big news, told across the region, good.
     */
    fun cookoff(level: ServerLevel, centre: BlockPos, winner: ServerPlayer, kind: DishKind): Rumour? {
        if (!Cooking.talk()) return null
        val args = listOf(Witness.named(winner.name.string), dish(kind))
        return counted(TownTalk.witness(level, centre, COOKOFF, winner.uuid, -1, args, 0.8f, Fade.BIG, Scope.REGION, 1, CROWD))
    }

    /** Whether [kind] is one of these rumours' kinds. */
    fun ours(kind: String): Boolean = kind == HALLMARK || kind == COOKOFF || kind == BURNT

    private fun counted(rumour: Rumour?): Rumour? {
        if (rumour != null) begun++
        return rumour
    }

    /** Where a keeper stands within a couple of blocks of [at], told as a word: the inn's innkeeper, the bakery's baker; none for anywhere else. */
    private fun place(level: ServerLevel, at: BlockPos): String? {
        val here = Vec3.atCenterOf(at)
        val keeper = level.getEntitiesOfClass(Townsperson::class.java, AABB(here, here).inflate(2.5)) { it.isAlive }
            .firstOrNull { it.role == TownRole.INNKEEPER || it.role == TownRole.BAKER } ?: return null
        return if (keeper.role == TownRole.BAKER) AT_BAKERY else AT_INN
    }
}
