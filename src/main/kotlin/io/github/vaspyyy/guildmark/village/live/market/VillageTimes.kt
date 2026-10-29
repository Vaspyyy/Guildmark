package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.economy.fortune.FortuneData
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.village.live.VillageInfoCommand
import io.github.vaspyyy.guildmark.village.live.VillageRec
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel

/**
 * A village's times where its folk and its commands show them (living-world/villages/design.md 8.1: `Fortunes.busy` and
 * `Fortunes.spend` "apply unchanged"). Its spending goes by them already in the village market's hour (VillageMarkets.step);
 * here, how many of its grown folk are out on the green of an evening ([outTonight]): a third of them by turns in steady
 * times, as V3 had it, 0.7 of that in hard times up to 1.25 times it in thriving ([Fortunes.busy] for its bell). And
 * `village info`'s lines: its times, its biggest reasons and what its thanks for supply come to.
 *
 * With Village Fortunes off every village is steady here: a third of its folk on the green, as before.
 */
object VillageTimes {
    /** The biggest reasons `village info` lists. */
    private const val SHOWN = 3

    /** At setup (from Fortunes.register): the village's lines of `village info`. */
    fun init() {
        VillageInfoCommand.section { level, rec -> lines(level, rec) }
    }

    /** The crowd's share in the village whose bell is [key], 0.7 to 1.25 ([Fortunes.busy]); 1 while its times are not worked out. */
    fun busy(level: ServerLevel, key: Long): Double = if (VillageMarkets.fortunes()) Fortunes.busy(level, key) else 1.0

    /**
     * Whether resident [id] of the village whose bell is [key] is out on its green this evening of town [day]: in steady times one
     * in three of its grown folk by turns (the turn of [id] and [day], V3's rule); in worse times only some of those whose turn it
     * is, and in better ones some of those whose turn is next too, each resident's chance their own for the day.
     */
    fun outTonight(level: ServerLevel, key: Long, id: Int, day: Long): Boolean {
        val turn = Math.floorMod(id + day, 3L)
        val busy = busy(level, key)
        if (busy == 1.0) return turn == 0L
        val dice = dice(key, id, day)
        return if (busy < 1.0) turn == 0L && dice < busy else turn == 0L || turn == 1L && dice < busy - 1.0
    }

    /** The same number in 0..1 for the same village, resident and day. */
    private fun dice(key: Long, id: Int, day: Long): Double {
        var h = key xor id.toLong() * -0x61c8864680b583ebL xor day * 0x2545F4914F6CDD1DL
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    // ---- `village info` ----

    private fun lines(level: ServerLevel, rec: VillageRec): List<Component> {
        if (!Fortunes.enabled() || !VillageMarkets.fortunes()) {
            return listOf(line().append(label("Times: ")).append(grey("steady: the village's times are off (Village Fortunes, with Village Markets and Town Fortunes)")))
        }
        val town = FortuneData.get(level)[rec.key]
        val out = ArrayList<Component>()
        if (town == null) {
            out.add(line().append(label("Times: ")).append(plain("steady")).append(grey(": no close yet (the first is at two in the morning once its market is open)")))
        } else {
            val since = if (town.since >= 0) " since day ${town.since} (from ${town.from.id})" else ""
            out.add(
                line().append(label("Times: ")).append(plain("${town.times.id}$since, score %+d".format(town.score)))
                    .append(grey("; out on the green of an evening x%.2f, spending x%.2f (`fortune` here for the rest)".format(busy(level, rec.key), Fortunes.spend(level, rec.key)))),
            )
            for (r in town.reasons.take(SHOWN)) {
                val colour = if (r.points < 0) ChatFormatting.RED else ChatFormatting.GREEN
                out.add(line().append(Component.literal("  %+d ".format(r.points)).withStyle(colour)).append(FortuneReasons.villageText(r).withStyle(ChatFormatting.WHITE)))
            }
        }
        val thanks = if (rec.board != -1L) {
            plain("${VillageSupply.points(level, rec.key)} standing for each player's first sale of the day of what it is short of")
                .append(grey(" (${VillageSupply.POINTS}, or ${VillageSupply.HARD_POINTS} in hard or lean times)"))
        } else {
            grey("it has no board of its own, so its thanks for supply carry no standing")
        }
        out.add(line().append(label("Thanks: ")).append(thanks))
        return out
    }

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
}
