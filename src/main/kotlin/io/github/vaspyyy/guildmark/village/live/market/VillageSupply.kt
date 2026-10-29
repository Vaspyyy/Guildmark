package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Serving
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.economy.fortune.FortuneData
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.VillageSounds
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * A village's thanks for what a player brings it (living-world/villages/design.md 8.1, "Standing from supply", and 16's "the
 * traveller who fed us"): a player's first sale of a day at a village's counter of a good the village was short of (the sale
 * `Fortunes.sold` notes as SUPPLIED) adds [POINTS] standing with the village's board, [HARD_POINTS] while its times are hard or
 * lean, through [Standing.add], which announces a new tier and spreads the word as a contract's points do. At 3 to 5 points a day
 * it is a slow second road to Known (25), beside the contracts' point a Mark.
 *
 * It is a moment at the counter, seen and heard: the keeper marks it in their own way, as a capital's keeper marks a supplier's
 * rise (a cheer, a nod, a bow, a clap or their thanks, by their ways: [Serving.thanked]), with a word of thanks, a warmer one in
 * hard times; those of the village idling about the counter turn to the player and thank them or cheer, and the children jump;
 * the village's cheer goes up (`village.cheer`); and the player is told what it was for and where they stand.
 *
 * A day is the village's market day as its fortunes count it ([Fortunes.today]), and whether the player was thanked already is
 * read from the village's own books: their SUPPLIED note of the day stands there once their first such sale is made, so nothing
 * new is saved, and a restart thanks nobody twice. A village with no board, or whose board's chunk is not loaded, adds no
 * standing, but is thankful all the same. With Village Fortunes off (or Town Fortunes, or the markets) nothing here runs, and
 * nothing at a capital's counter does.
 */
object VillageSupply {
    /** Standing for the day's first supply, and in hard or lean times. */
    const val POINTS = 3
    const val HARD_POINTS = 5

    /** Folk of the village this near the counter (blocks across) turn to thank the player, the nearest [TURNING] of them. */
    private const val NEAR = 10.0
    private const val TURNING = 6

    /** The keeper's word of thanks (say.guildmark.<line>), as times go, and what one of the folk about calls out. */
    private const val THANKS = 3
    private const val THANKS_HARD = 2
    private const val FOLK = 3

    /** Whether a village thanks its suppliers: its times are worked out (Village Fortunes, with Town Fortunes and the markets). */
    fun on(): Boolean = Fortunes.enabled() && VillageMarkets.fortunes()

    /**
     * From `Witness.dealt`, before the sale's SUPPLIED note is made: [player] sold [good] at [keeper]'s counter of [entry]'s market.
     * If the market is a village's, the good one it was short of (the test `Fortunes.sold` makes) and the sale the player's first
     * such there today, the village thanks them.
     */
    fun sold(level: ServerLevel, keeper: Townsperson, player: ServerPlayer, entry: Markets.Entry, good: Good) {
        if (!on()) return
        val key = entry.market.capital
        if (VillageMarkets.find(level, key) !== entry) return
        val rec = Villages.byKey(level, key) ?: return
        if (Trading.quote(entry, good).why?.dear != true) return
        if (supplied(level, key, player.uuid, Fortunes.today(level, key))) return
        thank(level, rec, keeper, player, good)
    }

    /** Whether [player] has supplied the village [key] already on its market [day]: their SUPPLIED note of the day is in its books. */
    fun supplied(level: ServerLevel, key: Long, player: UUID, day: Long): Boolean =
        FortuneData.get(level)[key]?.notes?.any { !it.hand && it.kind == FortuneReasons.SUPPLIED && it.about == player && it.from == day } == true

    /** The standing the village [key] gives for a day's first supply now: more while its times are hard or lean. */
    fun points(level: ServerLevel, key: Long): Int = if (hard(level, key)) HARD_POINTS else POINTS

    private fun hard(level: ServerLevel, key: Long): Boolean = Fortunes.times(level, key).let { it == Times.HARD || it == Times.LEAN }

    private fun thank(level: ServerLevel, rec: VillageRec, keeper: Townsperson, player: ServerPlayer, good: Good) {
        val times = Fortunes.times(level, rec.key)
        val hard = times == Times.HARD || times == Times.LEAN
        val board = rec.board.takeIf { it != -1L }?.let(BlockPos::of)?.takeIf { Standing.board(level, it) != null }
        val goods = Component.translatable(good.key + ".many")
        if (board != null) {
            val before = Standing.points(level, board, player)
            Standing.add(level, board, player, if (hard) HARD_POINTS else POINTS)
            val now = Standing.points(level, board, player)
            val gained = Component.literal("+${now - before}").withStyle(ChatFormatting.GREEN)
            val tier = Standing.tier(level, board, player).title
            val key = if (hard) "message.guildmark.village.thanks.${times.id}" else "message.guildmark.village.thanks"
            player.sendSystemMessage(Component.translatable(key, rec.name, goods, gained, now, tier))
        } else {
            player.sendSystemMessage(Component.translatable("message.guildmark.village.thanks.no_board", rec.name, goods))
        }
        val r = keeper.random
        val line = if (hard) "village_thanks_hard_${r.nextInt(THANKS_HARD)}" else "village_thanks_${r.nextInt(THANKS)}"
        (keeper.director as? Serving)?.thanked(keeper, line)
        folk(level, rec, keeper, player)
        VillageSounds.play(level, keeper.position(), VillageSounds.CHEER)
    }

    /**
     * Those of [rec]'s folk idling near [keeper]'s counter (nobody at work, under a scene's charge or asleep) turn to [player]: the
     * grown thank them or cheer, the children jump, and the nearest grown one calls out.
     */
    private fun folk(level: ServerLevel, rec: VillageRec, keeper: Townsperson, player: ServerPlayer) {
        val residents = Residents.get(level)
        val near = level.getEntitiesOfClass(Townsperson::class.java, keeper.boundingBox.inflate(NEAR, 4.0, NEAR)) {
            it !== keeper && it.isAlive && !it.isSleeping && it.director == null && it.activity.overlay &&
                it.routine.task.let { t -> t == null || t.leisure && !it.routine.anchored }
        }.filter { residents.of(it)?.capital?.asLong() == rec.key }.sortedBy { it.distanceToSqr(keeper) }.take(TURNING)
        var called = false
        for ((k, p) in near.withIndex()) {
            p.attend(player, 60)
            val child = p.role == TownRole.CHILD
            p.act(if (child) Activity.JUMP else if (k % 2 == 0) Activity.THANK else Activity.CHEER)
            if (!called && !child) called = p.say("village_thanks_folk_${p.random.nextInt(FOLK)}")
        }
    }
}
