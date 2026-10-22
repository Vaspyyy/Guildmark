package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.market.DoorLines
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.cooking.market.Owed
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Speech
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource

/**
 * A cookshop's till (living-world/cook/design.md 6.4): its owner takes the takings by using the shop's Menu board with an empty hand.
 * The till pays out in whole Marks (the fraction of a Mark the sales left over is the ledger's, carried to the next sale), and the
 * keeper, if they are by, hands them over and says what the shop made in the week. The Marks are taken off the record before they are
 * given, so they are handed over once. Anyone else reads the board.
 */
internal object ShopTill {
    /** How near the keeper must be to hand the till over. */
    private const val NEAR = 10.0

    /**
     * [player] uses the Menu board at [pos] with an empty hand (from [io.github.vaspyyy.guildmark.cooking.market.MenuBoards.onUse]):
     * true if it is their cookshop's and they took the till; false for any other board, any other player, and a till with nothing in
     * it (after a word that it is empty), so that the slate is read out as for anyone.
     */
    fun use(level: ServerLevel, pos: BlockPos, player: ServerPlayer): Boolean {
        if (!Cooking.enabled()) return false
        val works = CookShops.boardAt(level, pos) ?: return false
        val shop = CookShops.shopOf(level, works) ?: return false
        if (shop.owner != player.uuid) return false
        if (shop.till > 0) {
            take(level, works, shop, player)
            return true
        }
        player.sendOverlayMessage(Component.translatable("message.guildmark.cook.shop.till_empty"))
        return false
    }

    /** Pays [shop]'s till out to [player], whole Marks, and tells them the week; the Marks paid (0 if the till was empty). */
    fun take(level: ServerLevel, works: WorksRec, shop: ShopRec, player: ServerPlayer): Int {
        val marks = shop.till
        if (marks <= 0) return 0
        shop.till = 0
        Kitchens.get(level).changed()
        Trading.give(player, ModItems.GUILD_MARK.get(), marks)
        level.playSound(null, player.blockPosition(), StallDesk.TILL, SoundSource.PLAYERS, 0.7f, 1.1f)
        player.sendSystemMessage(Component.translatable("message.guildmark.cook.shop.took", Owed.marks(marks)).withStyle(ChatFormatting.GOLD))
        report(level, works, shop, player)
        return marks
    }

    /**
     * The week's takings, said by the keeper if they are by and awake, as one of Part J's takes or the shop's own wording with the
     * figures in grey after it; when their words did not carry the figures (a take, or no keeper by) they are shown on [player]'s action bar.
     */
    private fun report(level: ServerLevel, works: WorksRec, shop: ShopRec, player: ServerPlayer) {
        val line = week(shop, Cooking.today(level))
        val keeper = CookShops.keeperOf(level, works)?.takeIf { !it.isSleeping && it.distanceToSqr(player) <= NEAR * NEAR }
        var said = DoorLines.Said.NONE
        if (keeper != null) {
            keeper.lookAt(player)
            keeper.attend(player, 80)
            if (keeper.director == null) keeper.act(Activity.HAND_OVER)
            said = ShopLines.say(keeper, ShopLines.REPORT, line)
        }
        if (said != DoorLines.Said.OWN) Speech.detail(Speech.withDetail("x", line))?.let { player.sendOverlayMessage(it) }
    }

    /**
     * The week up to [today] as a line [Speech] reads: the Marks the shop took (after the keeper's wage) and the dish that sold the
     * most portions, or that nothing sold.
     */
    fun week(shop: ShopRec, today: Long): String {
        val top = shop.week.top(today) ?: return "talk.detail.kitchen.cookshop.report.none"
        val marks = Math.round(shop.week.takings(today)).toInt()
        val dish = "item.guildmark.dish_${top.id}"
        return if (marks == 1) Speech.of("talk.detail.kitchen.cookshop.report.one", dish) else Speech.of("talk.detail.kitchen.cookshop.report", marks, dish)
    }
}
