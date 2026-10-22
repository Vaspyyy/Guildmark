package io.github.vaspyyy.guildmark.village.live.folk

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ProblemReporter
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.village.ReputationEventType
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput

/**
 * A villager that is only a merchant: the vanilla trade screen's far end for a resident's "Their old wares" (living-world/villages/
 * design.md section 6.4). It is made from a keepsake when the wares are asked for, stood at the resident's feet and **never added to
 * the world**, so nothing ticks it, its brain never runs and it claims no bed or job block; the trading screen needs nothing more
 * of it than `Merchant` (its offers, its xp, `notifyTrade`, `stillValid`), all of which work on an entity that is not in a level.
 *
 * What vanilla does for a villager on its own ticks, and so is done here by hand, by [OldWares]: the +2 trading gossip and the green
 * sparks a trade earns ([afterTrade]), gossip fading once a day and the twice-a-day restock (at the open). What it does inside the
 * trade itself it still does: the use of the offer, the xp, the level (immediately: `rewardTradeXp` raises the level and rolls the new
 * trades in the same call, and `updateTrades` sends the screen its new offers) and the xp orb at the resident's feet.
 *
 * Each trade is kept at once ([traded]) and so is the close of the screen ([closed]): a player who quits or is cut off with the screen
 * open never has the menu's `removed` called, so nothing waits for it.
 */
class KeptVillager(type: EntityType<out Villager>, level: Level) : Villager(type, level) {
    /** The resident this is the wares of, or -1. */
    var resident = -1

    /** Told after a trade has been made (its use, xp and level are in). */
    var traded: Runnable? = null

    /** Told when the screen's player lets go (the screen closed, the player walked off or left). */
    var closed: Runnable? = null

    /** Trades whose gossip and sparks are still owed (see [afterTrade]), with who they were with. */
    private var owed = 0

    /** Loads the keepsake [tag]: only the villager's own parts are read (no position, no brain), so it is the villager they were and no more. */
    fun fill(level: ServerLevel, tag: CompoundTag) {
        readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag))
    }

    /** The villager's own saved parts as they are now, cut down to a keepsake's. */
    fun saved(level: ServerLevel): CompoundTag {
        val out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess())
        addAdditionalSaveData(out)
        return VillageKeepsakes.cut(out.buildResult())
    }

    /**
     * Opens the screen for [player] as vanilla's `mobInteract` would (its own `startTrading` is private, but the pieces it is made of
     * are public): the player becomes the one trading, `postDataManipulated` works out the prices from the villager's gossip of them
     * and the Hero of the Village effect, and the menu opens with the offers. Taken apart so that a player holding sneak at the
     * click does not turn the screen away, as it does a villager they click in the world.
     */
    fun openFor(player: Player, title: Component) {
        setTradingPlayer(player)
        postDataManipulated()
        openTradingScreen(player, title, villagerData.level())
    }

    /** The profession's name, as the screen's title is made of. */
    fun trade(): Component = getTypeName()

    override fun customServerAiStep(level: ServerLevel) {}

    /** The regeneration a level-up gives a villager in the world is for those who see it: nothing is added to a merchant that is not there. */
    override fun addEffect(effectInstance: MobEffectInstance, source: Entity?): Boolean = false

    override fun notifyTrade(offer: MerchantOffer) {
        super.notifyTrade(offer)
        owed++
        traded?.run()
    }

    override fun setTradingPlayer(player: Player?) {
        val closing = tradingPlayer != null && player == null
        super.setTradingPlayer(player)
        if (closing) closed?.run()
    }

    /**
     * What a villager does a tick after a trade, done a tick after it: the gossip of trading (`onReputationEventFrom`, +2 for each
     * trade, which prices the next ones as it does a villager's, and sends the screen its new prices) and a burst of green sparks over
     * the resident. True if there was one.
     */
    fun afterTrade(level: ServerLevel): Boolean {
        if (owed <= 0) return false
        val buyer = tradingPlayer
        if (buyer != null) repeat(owed) { onReputationEventFrom(ReputationEventType.TRADE, buyer) }
        owed = 0
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y + 1.0, z, 5, 0.5, 0.5, 0.5, 0.0)
        return buyer != null
    }

    /** Whether the offers had anything sold from them since the last restock: some use is in. */
    fun inUse(): Boolean = getOffers().any { it.uses > 0 }
}
