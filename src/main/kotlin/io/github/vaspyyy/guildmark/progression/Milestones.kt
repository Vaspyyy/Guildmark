package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Player

/**
 * The Guildmark advancement tab. Every advancement has one impossible criterion and is awarded from
 * code when the milestone happens, so the tab reads as a checklist of what the mod has to offer.
 */
object Milestones {
    const val ROOT = "root"
    const val SIGNED_ON = "signed_on"
    const val PAID = "paid"
    const val COURIER = "courier"
    const val BODYGUARD = "bodyguard"
    const val CHAMPION = "champion"
    const val LEDGER = "ledger"
    const val BUILDER = "builder"
    const val ADVANCE_BUILT = "advance_built"
    const val TRADE_ROAD = "trade_road"
    const val CITY = "city"
    const val HERO = "hero"
    const val LAIR = "lair"
    const val SIEGE = "siege"
    const val RELIC = "relic"
    const val LEGENDARY = "legendary"
    const val GUILD_FOUNDED = "guild_founded"
    const val RECRUIT = "recruit"
    const val BANNER = "banner"
    const val PARTY = "party"
    const val TAKEOVER = "takeover"
    const val REPELLED = "repelled"
    const val CHARACTER = "character"
    const val FIRST_SHARD = "first_shard"
    const val MAREN = "maren"
    const val SEVEN_SHARDS = "seven_shards"
    const val HOLLOW_KING = "hollow_king"
    const val WARDFIRE = "wardfire"

    private fun holder(player: ServerPlayer, id: String): AdvancementHolder? =
        player.level().server.advancements.get(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "guildmark/$id"))

    fun done(player: Player, id: String): Boolean {
        val server = player as? ServerPlayer ?: return false
        val holder = holder(server, id) ?: return false
        return server.advancements.getOrStartProgress(holder).isDone
    }

    fun award(player: Player, id: String) {
        val server = player as? ServerPlayer ?: return
        val holder = holder(server, id) ?: return
        val progress = server.advancements.getOrStartProgress(holder)
        if (progress.isDone) return
        for (criterion in progress.remainingCriteria.toList()) server.advancements.award(holder, criterion)
        Titles.onMilestone(server, id)
    }

    /** The rank trials worth a milestone. */
    fun onRank(player: Player, rank: Int) {
        if (rank >= 1) award(player, "rank_e")
        if (rank >= 3) award(player, "rank_c")
        if (rank >= 5) award(player, "rank_a")
        if (rank >= 6) award(player, "rank_s")
    }

    /** First join: the Guild hands over the Handbook. */
    fun onLogin(player: Player) {
        if (player !is ServerPlayer || done(player, ROOT) || holder(player, ROOT) == null) return
        if (GuildmarkConfig.HANDBOOK.get()) player.inventory.placeItemBackInInventory(Handbook.create(), Prediction.SERVER_ONLY)
        player.sendSystemMessage(Component.translatable("message.guildmark.welcome").withStyle { it.withColor(0xE8C26A) })
        award(player, ROOT)
    }

    /** Relics are found in many ways (chests, drops, caches), so the inventory is checked now and then. */
    fun tick(player: Player) {
        if (player.tickCount % 40 != 20 || done(player, LEGENDARY)) return
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val relic = inventory.getItem(slot).get(ModDataComponents.RELIC.get()) ?: continue
            award(player, RELIC)
            if (relic.rarity >= Rarity.LEGENDARY.ordinal) award(player, LEGENDARY)
        }
    }
}
