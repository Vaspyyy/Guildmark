package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.block.BoardPart
import io.github.vaspyyy.guildmark.block.NoteKind
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.enemy.DuskRider
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.story.HollowThrone
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Schedule
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.gamerules.GameRules
import io.github.vaspyyy.guildmark.city.plan.Kind as Plan

/**
 * When each of the old lines (say.guildmark.greet_0 and the rest, before the talk's own families) is true
 * (living-world/design-voices.md section 5, "The old lines"). With townsfolk talk on, [io.github.vaspyyy.guildmark.townsfolk.Lines]
 * says one only while it holds, from the world as it is: the hour and the sky, the fisher's basket, the stalls' stock
 * and prices, the quest board, the roads, the Hollow King and the Dusk Riders. A line that can't be checked (no market
 * kept, no living capital) holds as before only where nothing it says could be wrong.
 */
internal object OldLines {
    /** "Market's busy today.": this many at the market, within this far of its middle. */
    private const val BUSY = 10
    private const val MARKET = 12.0

    /** How near the capital's heart a Dusk Rider must have fallen to be talked of, and an outlaw camp be for the roads to be unsafe. */
    private const val RIDER = 256.0
    private const val BANDS = 400.0

    /** "Prices are up again.": the staples dearer by this much than a week ago, with at least [KEPT] days' closes to go by. */
    private const val UP = 1.06
    private const val WEEK = 7
    private const val KEPT = 3

    /** "Rain's coming. I can feel it.": rain due within this many ticks (an in-game hour). */
    private const val RAIN = 1000

    /** What everyone buys for the house, by which "prices are up" is reckoned. */
    private val STAPLES = listOf(Good.BREAD, Good.POTATO, Good.CARROT, Good.COD, Good.APPLE, Good.BEEF, Good.PORK, Good.CANDLE, Good.COAL)

    /** Whether [line] (a key under say.guildmark.) is true for [p] to say now, to [player] if it's said to one. */
    fun holds(p: Townsperson, line: String, player: Player? = null): Boolean {
        val level = p.level() as? ServerLevel ?: return true
        val hour = Say.hour(level)
        val day = hour >= 6.0 && hour < 19.0
        return when (Speech.key(line)) {
            // Greetings by day, mornings, to strangers; the farewells aren't greetings
            "greet_0" -> day
            "greet_1" -> hour >= 5.0 && hour < 12.0
            "greet_3" -> day && stranger(level, p, player) && Capitals.covering(level, p.blockPosition()) != null
            "greet_6" -> day && stranger(level, p, player)
            "greet_2", "greet_7", "gossip_4", "noble_0" -> day && fair(level, p)
            "greet_4", "greet_5" -> false
            "night_0" -> hour >= 21.0 || hour < 5.0
            "night_1" -> hour >= 17.0
            "night_2" -> Schedule.dark(p)
            "farmer_1" -> rainDue(level)
            "fisher_0" -> Days.life(p)?.let { it.basket > 0 } ?: true
            "fisher_1" -> Days.life(p)?.let { it.basket == 0 } ?: false
            // No stall sells silks and spices; apples and fish only where they're sold and there are some, the fish of a morning
            "merchant_3" -> false
            "merchant_4" -> sells(level, p, Good.APPLE)
            "merchant_5" -> hour < 12.0 && (sells(level, p, Good.COD) || sells(level, p, Good.SALMON))
            "inn_3" -> hour >= 23.0 || hour < 3.0
            // Warm bread while there's bread and the ovens are going; no pies are baked anywhere
            "baker_0" -> !cold(p) && stocked(level, p, Good.BREAD) != false
            "baker_1" -> false
            "baker_2" -> !cold(p)
            "noble_1" -> Days.life(p)?.places?.lodgings?.values?.any { it.kind == Plan.CATHEDRAL.name } == true
            // "Hmph. Adventurers." isn't for someone they know; the Hollow walks while its king's unbeaten; a thief's night
            "noble_2" -> player !is ServerPlayer || TownTalk.familiarity(level, resident(level, p), player).let {
                it == Familiarity.STRANGER || it == Familiarity.WARY || it == Familiarity.HOSTILE
            }
            "knight_3" -> HollowThrone.get(level).defeated == 0
            "thief_3" -> Schedule.dark(p)
            "guard_0" -> player is ServerPlayer && (wary(level, p, player) || Schedule.dark(p) && Street.loitering(player))
            "guard_2" -> p.threat() == null
            "gossip_0", "gossip_3" -> false
            "gossip_1" -> pricesUp(level, p)
            "gossip_2" -> contracts(level, p)
            "gossip_6" -> marketBusy(level, p)
            "gossip_8" -> HollowThrone.get(level).defeated > 0
            "gossip_9" -> stocked(level, p, Good.BREAD) != false
            "gossip_10" -> riderSeen(level, p)
            "gossip_11" -> roadsSafe(level, p)
            else -> true
        }
    }

    /** Those of [keys] that hold for [p] now, to [player]. */
    fun pool(p: Townsperson, keys: List<String>, player: Player? = null): List<String> = keys.filter { holds(p, it, player) }

    /**
     * [line] if it holds for [p] now; else another of its set that does ("baker_2" for the pies of "baker_1", "fisher_1"
     * for an empty basket's "fisher_0"); null if none does.
     */
    fun alike(p: Townsperson, line: String, player: Player? = null): String? {
        if (holds(p, line, player)) return line
        val key = Speech.key(line)
        val prefix = key.substring(0, key.lastIndexOf('_') + 1)
        val count = SETS[prefix] ?: return null
        val others = (0 until count).map { "$prefix$it" }.filter { it != key && holds(p, it, player) }
        return if (others.isEmpty()) null else others[p.random.nextInt(others.size)]
    }

    /** The old lines' sets, by their prefix, and how many each has. */
    private val SETS = mapOf(
        "greet_" to 8, "night_" to 3, "gossip_" to 12, "reply_" to 6, "farmer_" to 3, "fisher_" to 3, "smith_" to 3, "baker_" to 3,
        "merchant_" to 6, "guard_" to 5, "noble_" to 4, "inn_" to 4, "bard_" to 2, "priest_" to 3, "child_" to 6, "mage_" to 6,
        "knight_" to 6, "thief_" to 6,
    )

    private fun resident(level: ServerLevel, p: Townsperson): Resident? = Days.life(p)?.resident ?: Residents.get(level).of(p)

    /** Whether [player] is a stranger to [p] (and there's a player to be one). */
    private fun stranger(level: ServerLevel, p: Townsperson, player: Player?): Boolean =
        player is ServerPlayer && TownTalk.familiarity(level, resident(level, p), player) == Familiarity.STRANGER

    /** Where [p]'s capital has its heart, if they belong to one or stand in one. */
    private fun capital(level: ServerLevel, p: Townsperson): BlockPos? =
        Days.life(p)?.places?.centre ?: Residents.get(level).of(p)?.capital ?: Capitals.covering(level, p.blockPosition())?.center

    private fun market(level: ServerLevel, p: Townsperson): Markets.Entry? {
        if (!Markets.enabled()) return null
        val at = capital(level, p) ?: return null
        // A village's market too, with Village Bellman (its folk's old lines go by what it has)
        return Markets.get(level)[at] ?: if (io.github.vaspyyy.guildmark.village.live.talk.VillageCry.enabled()) Markets.find(level, at.asLong()) else null
    }

    /** Whether the market has [good] in stock; null if there's no market to go by. */
    private fun stocked(level: ServerLevel, p: Townsperson, good: Good): Boolean? = market(level, p)?.let { it.market.stock(good) >= 1.0 }

    /** Whether [p] keeps a stall that sells [good], with some of it to sell. */
    private fun sells(level: ServerLevel, p: Townsperson, good: Good): Boolean {
        if (Days.life(p) == null) return false
        val shop = Shop.kept(p) ?: return false
        return good in shop.goods && stocked(level, p, good) == true
    }

    /** A baker whose ovens stand cold for want of flour. */
    private fun cold(p: Townsperson): Boolean {
        if (p.role != TownRole.BAKER) return false
        val life = Days.life(p) ?: return false
        return Trades.ovensCold(p, life)
    }

    /** No rain or snow falling where [p] stands. */
    private fun fair(level: ServerLevel, p: Townsperson): Boolean {
        if (!level.isRaining) return true
        val pos = p.blockPosition()
        return level.getBiome(pos).value().getPrecipitationAt(pos, level.seaLevel) == Biome.Precipitation.NONE
    }

    /** Dry now, and the sky set to turn within the hour. */
    private fun rainDue(level: ServerLevel): Boolean {
        val overworld = level.server.overworld()
        if (overworld.isRaining || !overworld.gameRules.get(GameRules.ADVANCE_WEATHER)) return false
        val weather = overworld.weatherData
        return weather.clearWeatherTime <= 0 && weather.rainTime in 1..RAIN
    }

    private fun wary(level: ServerLevel, p: Townsperson, player: ServerPlayer): Boolean =
        TownTalk.familiarity(level, resident(level, p), player).let { it == Familiarity.WARY || it == Familiarity.HOSTILE }

    /** The staples, as the house buys them, dearer now than a week ago (or since the closes began, with [KEPT] days at least). */
    private fun pricesUp(level: ServerLevel, p: Townsperson): Boolean {
        val entry = market(level, p) ?: return false
        val m = entry.market
        var now = 0.0
        var then = 0.0
        for (g in STAPLES) {
            val closes = m.closes[g.ordinal]
            if (closes.size < KEPT) continue
            now += g.share * m.price(entry.town, g) / g.base
            then += g.share * closes[maxOf(0, closes.size - WEEK)]
        }
        return then > 0.0 && now > then * UP
    }

    /** Ten or more about the market square. */
    private fun marketBusy(level: ServerLevel, p: Townsperson): Boolean {
        val at = Days.life(p)?.places?.market ?: return false
        if (!level.isLoaded(at)) return false
        val box = net.minecraft.world.phys.AABB(at).inflate(MARKET, 4.0, MARKET)
        return level.getEntitiesOfClass(Townsperson::class.java, box) { it.isAlive && !it.isSleeping }.size >= BUSY
    }

    /** Notes up on the capital's quest board. */
    private fun contracts(level: ServerLevel, p: Townsperson): Boolean {
        val anchor = ((Days.life(p)?.places as? CityPlaces)?.capital ?: Capitals.covering(level, p.blockPosition()))?.boardAnchor ?: return false
        if (!level.isLoaded(anchor)) return false
        val state = level.getBlockState(anchor)
        if (state.block !is QuestBoardBlock) return false
        val facing = state.getValue(QuestBoardBlock.FACING)
        return BoardPart.entries.any { part ->
            val cell = level.getBlockState(QuestBoardBlock.cellPos(anchor, facing, part))
            cell.block is QuestBoardBlock && cell.getValue(QuestBoardBlock.NOTE) != NoteKind.NONE
        }
    }

    /** A Dusk Rider brought down near the capital, as the guild's news has it. */
    private fun riderSeen(level: ServerLevel, p: Townsperson): Boolean {
        val heart = capital(level, p) ?: return false
        val rider = "#" + DuskRider.NAME_KEY + "."
        return GuildNews.get(level).items.any { item ->
            if (item.key != "news.guildmark.field_boss" || item.args.getOrNull(1)?.startsWith(rider) != true) return@any false
            val x = item.args.getOrNull(2)?.toDoubleOrNull() ?: return@any false
            val z = item.args.getOrNull(3)?.toDoubleOrNull() ?: return@any false
            val dx = x - heart.x
            val dz = z - heart.z
            dx * dx + dz * dz <= RIDER * RIDER
        }
    }

    /**
     * The roads round the capital are safe: with the caravans on, no road into it Troubled or worse (its peril); else,
     * or while its roads are still being worked out, no outlaw band at large near the town.
     */
    private fun roadsSafe(level: ServerLevel, p: Townsperson): Boolean {
        val heart = capital(level, p) ?: return false
        // With the wagons on the roads, it's what the roads' peril says: no road into the capital Troubled or worse
        val legs = if (Caravans.enabled()) CaravanRoutes.legs(level, heart.asLong()) else null
        if (!legs.isNullOrEmpty()) {
            val data = CaravanData.get(level)
            return legs.none { RoadPeril.stage(data, heart.asLong(), it.road).troubled }
        }
        if (!Outlaws.enabled()) return true
        return OutlawCamps.get(level).wanted(heart, BANDS).none { !it.slain }
    }
}
