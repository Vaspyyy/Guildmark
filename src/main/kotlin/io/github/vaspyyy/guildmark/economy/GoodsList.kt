package io.github.vaspyyy.guildmark.economy

import net.minecraft.network.chat.Component

/** Goods counted out in words, for chat and the ledger: "64 Wheat, 20 Carrots and 3 Apples". */
object GoodsList {
    /** [counts] (by [Good] ordinal) in words, in the goods' order; "nothing" for none. */
    fun of(counts: IntArray): Component {
        val parts = Good.entries.filter { counts.getOrElse(it.ordinal) { 0 } > 0 }.map { one(it, counts[it.ordinal]) }
        return if (parts.isEmpty()) Component.translatable("message.guildmark.goods.none") else join(parts)
    }

    /** [n] of [good]: "20 Carrots", "1 Carrot". */
    fun one(good: Good, n: Int): Component =
        Component.translatable("message.guildmark.goods.count", n, Component.translatable(if (n == 1) good.key else good.key + ".many"))

    /** [parts] run together: "a", "a and b", "a, b and c"; an empty component for none. */
    fun join(parts: List<Component>): Component = when (parts.size) {
        0 -> Component.empty()
        1 -> parts[0]
        else -> Component.translatable(
            "message.guildmark.goods.and", parts.dropLast(1).reduce { a, b -> Component.translatable("message.guildmark.goods.comma", a, b) }, parts.last(),
        )
    }
}
