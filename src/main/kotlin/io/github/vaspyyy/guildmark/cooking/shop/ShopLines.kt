package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.cooking.market.DoorLines
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenLines

/**
 * What a cookshop's keeper says (living-world/cook/design.md 8.5, 11.5): the cry, the first plate from a new oven, the shrug at the
 * end of the stock and the report of the till. Each is a kitchen family of Part J's ([KitchenLines.say] says it as a recorded take);
 * while that says nothing (it is a stub until J lands) the keeper says the shop's own wording of the same family, kept here, as the
 * back door's keepers do ([DoorLines]), so the scenes are played and read in full whatever else is built.
 */
internal object ShopLines {
    /** The families a cookshop's keeper speaks in. */
    const val CRY = "kitchen.shop.cry"
    const val SOLD = "kitchen.shop.sold"
    const val FIRST = "kitchen.shop.first"
    const val REPORT = "kitchen.shop.report"

    private const val SAY = "say.guildmark."

    /** The shop's own wordings of each family, as complete language keys. */
    private val WORDS: Map<String, List<String>> = mapOf(
        CRY to listOf("say.guildmark.talk.kitchen.cookshop.cry_0", "say.guildmark.talk.kitchen.cookshop.cry_1", "say.guildmark.talk.kitchen.cookshop.cry_2"),
        SOLD to listOf("say.guildmark.talk.kitchen.cookshop.sold_0", "say.guildmark.talk.kitchen.cookshop.sold_1", "say.guildmark.talk.kitchen.cookshop.sold_2"),
        FIRST to listOf("say.guildmark.talk.kitchen.cookshop.first_0", "say.guildmark.talk.kitchen.cookshop.first_1"),
        REPORT to listOf("say.guildmark.talk.kitchen.cookshop.report_0", "say.guildmark.talk.kitchen.cookshop.report_1"),
    )

    /** How many wordings the shop has of [family]. */
    fun count(family: String): Int = WORDS[family]?.size ?: 0

    /**
     * Has [p] say [family] now, with [detail] (a line of the form [Speech] reads, whose key is under say.guildmark.) after it in grey.
     * Part J's take is tried first ([DoorLines.Said.TAKE]; the detail is then not said, and the caller shows it another way); else the
     * shop's wording of it, a random one, with the detail ([DoorLines.Said.OWN]). [DoorLines.Said.NONE] if there is no one near to hear.
     */
    fun say(p: Townsperson, family: String, detail: String? = null): DoorLines.Said {
        if (KitchenLines.say(p, family, "cookshop", force = true)) return DoorLines.Said.TAKE
        val words = WORDS[family] ?: return DoorLines.Said.NONE
        val key = words[p.random.nextInt(words.size)].removePrefix(SAY)
        return if (p.say(Speech.withDetail(key, detail), force = true)) DoorLines.Said.OWN else DoorLines.Said.NONE
    }
}
