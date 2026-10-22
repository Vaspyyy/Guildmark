package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Verdict
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenLines

/**
 * What the keepers say at the back door (living-world/cook/design.md 7.7, 8.5, 11.5). Each remark is a kitchen family of
 * Part J's ([KitchenLines.say] says it as a take, or as its own text while none is recorded); while that says nothing (it is a
 * stub until J lands) the keeper says the door's own wording of the same family, kept here, so the scene is played and read
 * in full whatever else is built. The wordings are the families' of 11.5 and a few the door needs besides (a keeper asked to buy
 * what is not hers, a door that buys nothing, the Marks carried over, the request filled).
 *
 * A verdict is followed, unvoiced, by the role it is about ("Too much in the greens."): said at once with the door's own
 * wording, or as a line of its own a moment after J's take.
 */
object DoorLines {
    /** How a family was said: as one of J's takes, in the door's own words, or not at all. */
    enum class Said { TAKE, OWN, NONE }

    private const val SAY = "say.guildmark."

    /** The door's own wordings of each family, as complete language keys. */
    private val WORDS: Map<String, List<String>> = mapOf(
        "kitchen.verdict.over" to listOf(
            "say.guildmark.talk.kitchen.door.verdict_over_0", "say.guildmark.talk.kitchen.door.verdict_over_1", "say.guildmark.talk.kitchen.door.verdict_over_2",
        ),
        "kitchen.verdict.under" to listOf(
            "say.guildmark.talk.kitchen.door.verdict_under_0", "say.guildmark.talk.kitchen.door.verdict_under_1", "say.guildmark.talk.kitchen.door.verdict_under_2",
        ),
        "kitchen.verdict.right" to listOf(
            "say.guildmark.talk.kitchen.door.verdict_right_0", "say.guildmark.talk.kitchen.door.verdict_right_1", "say.guildmark.talk.kitchen.door.verdict_right_2",
        ),
        "kitchen.verdict.far" to listOf(
            "say.guildmark.talk.kitchen.door.verdict_far_0", "say.guildmark.talk.kitchen.door.verdict_far_1", "say.guildmark.talk.kitchen.door.verdict_far_2",
        ),
        "kitchen.tier.plain" to listOf(
            "say.guildmark.talk.kitchen.door.tier_plain_0", "say.guildmark.talk.kitchen.door.tier_plain_1",
            "say.guildmark.talk.kitchen.door.tier_plain_2", "say.guildmark.talk.kitchen.door.tier_plain_3",
        ),
        "kitchen.tier.sound" to listOf(
            "say.guildmark.talk.kitchen.door.tier_sound_0", "say.guildmark.talk.kitchen.door.tier_sound_1", "say.guildmark.talk.kitchen.door.tier_sound_2",
        ),
        "kitchen.tier.fine" to listOf(
            "say.guildmark.talk.kitchen.door.tier_fine_0", "say.guildmark.talk.kitchen.door.tier_fine_1", "say.guildmark.talk.kitchen.door.tier_fine_2",
        ),
        "kitchen.tier.hallmark" to listOf(
            "say.guildmark.talk.kitchen.door.tier_hallmark_0", "say.guildmark.talk.kitchen.door.tier_hallmark_1",
            "say.guildmark.talk.kitchen.door.tier_hallmark_2", "say.guildmark.talk.kitchen.door.tier_hallmark_3",
        ),
        "kitchen.first" to listOf(
            "say.guildmark.talk.kitchen.door.first_0", "say.guildmark.talk.kitchen.door.first_1", "say.guildmark.talk.kitchen.door.first_2",
        ),
        "kitchen.full" to listOf(
            "say.guildmark.talk.kitchen.door.full_0", "say.guildmark.talk.kitchen.door.full_1", "say.guildmark.talk.kitchen.door.full_2",
        ),
        "kitchen.stale" to listOf("say.guildmark.talk.kitchen.door.stale_0", "say.guildmark.talk.kitchen.door.stale_1"),
        "kitchen.gone" to listOf(
            "say.guildmark.talk.kitchen.door.gone_0", "say.guildmark.talk.kitchen.door.gone_1", "say.guildmark.talk.kitchen.door.gone_2",
        ),
        "kitchen.baker.no" to listOf(
            "say.guildmark.talk.kitchen.door.baker_no_0", "say.guildmark.talk.kitchen.door.baker_no_1", "say.guildmark.talk.kitchen.door.baker_no_2",
        ),
        "kitchen.busy" to listOf(
            "say.guildmark.talk.kitchen.door.busy_0", "say.guildmark.talk.kitchen.door.busy_1", "say.guildmark.talk.kitchen.door.busy_2",
        ),
        "kitchen.alms.fine" to listOf("say.guildmark.talk.kitchen.door.alms_fine_0", "say.guildmark.talk.kitchen.door.alms_fine_1"),
        // The door's own, which J's families do not have: no take is asked for these
        "kitchen.inn.no" to listOf("say.guildmark.talk.kitchen.door.inn_no_0", "say.guildmark.talk.kitchen.door.inn_no_1"),
        "kitchen.off" to listOf("say.guildmark.talk.kitchen.door.off_0"),
        "kitchen.spoilt" to listOf("say.guildmark.talk.kitchen.door.spoilt_0", "say.guildmark.talk.kitchen.door.spoilt_1"),
        "kitchen.gruel" to listOf("say.guildmark.talk.kitchen.door.gruel_0", "say.guildmark.talk.kitchen.door.gruel_1"),
        "kitchen.mess" to listOf("say.guildmark.talk.kitchen.door.mess_0", "say.guildmark.talk.kitchen.door.mess_1"),
        "kitchen.carry" to listOf("say.guildmark.talk.kitchen.door.carry_0", "say.guildmark.talk.kitchen.door.carry_1"),
        "kitchen.request" to listOf("say.guildmark.talk.kitchen.door.request_0", "say.guildmark.talk.kitchen.door.request_1"),
        "kitchen.request.known" to listOf("say.guildmark.talk.kitchen.door.request_known_0", "say.guildmark.talk.kitchen.door.request_known_1"),
    )

    /** The families that are the door's own and so are never asked of Part J. */
    private val OWN = setOf(
        "kitchen.inn.no", "kitchen.off", "kitchen.spoilt", "kitchen.gruel", "kitchen.mess", "kitchen.carry", "kitchen.request", "kitchen.request.known",
    )

    /** The role a verdict is about, said after it, for a verdict that is too much of it and for one that is too little (unvoiced). */
    private val OVER: Map<Role, String> = mapOf(
        Role.BODY to "say.guildmark.talk.detail.kitchen.door.over.body",
        Role.MEAT to "say.guildmark.talk.detail.kitchen.door.over.meat",
        Role.SEA to "say.guildmark.talk.detail.kitchen.door.over.sea",
        Role.GREEN to "say.guildmark.talk.detail.kitchen.door.over.green",
        Role.SWEET to "say.guildmark.talk.detail.kitchen.door.over.sweet",
        Role.RICH to "say.guildmark.talk.detail.kitchen.door.over.rich",
        Role.SAVOUR to "say.guildmark.talk.detail.kitchen.door.over.savour",
    )
    private val UNDER: Map<Role, String> = mapOf(
        Role.BODY to "say.guildmark.talk.detail.kitchen.door.under.body",
        Role.MEAT to "say.guildmark.talk.detail.kitchen.door.under.meat",
        Role.SEA to "say.guildmark.talk.detail.kitchen.door.under.sea",
        Role.GREEN to "say.guildmark.talk.detail.kitchen.door.under.green",
        Role.SWEET to "say.guildmark.talk.detail.kitchen.door.under.sweet",
        Role.RICH to "say.guildmark.talk.detail.kitchen.door.under.rich",
        Role.SAVOUR to "say.guildmark.talk.detail.kitchen.door.under.savour",
    )

    /** The family a keeper's [verdict] is said in: spot on, far off, or one role too high or too low. */
    fun verdictFamily(verdict: Verdict): String = when {
        verdict.close >= 3 -> "kitchen.verdict.right"
        verdict.close <= 0 -> "kitchen.verdict.far"
        verdict.over -> "kitchen.verdict.over"
        else -> "kitchen.verdict.under"
    }

    /** The unvoiced line naming the role a verdict is about ("Too much in the greens."), or null for a verdict that names none (right on, or far off). */
    fun verdictDetail(verdict: Verdict): String? = when {
        verdict.close >= 3 || verdict.close <= 0 -> null
        verdict.over -> OVER[verdict.role]?.removePrefix(SAY)
        else -> UNDER[verdict.role]?.removePrefix(SAY)
    }

    /** How many wordings the door has of [family] (for the command that says them all). */
    fun count(family: String): Int = WORDS[family]?.size ?: 0

    /** The families, in the order above. */
    fun families(): Set<String> = WORDS.keys

    /**
     * Has [p] say [family] now, with [detail] (a line key under say.guildmark. that follows it, unvoiced) after it. Part J's
     * take is tried first for J's families ([Said.TAKE]; the detail is then the caller's to say a moment later, as a line of
     * its own); else the door's wording of it, picked by [index] (a random one if it is negative), with the detail in grey
     * after it ([Said.OWN]). [Said.NONE] if there is no one near to hear.
     */
    fun say(p: Townsperson, family: String, detail: String? = null, index: Int = -1): Said {
        if (family !in OWN && index < 0 && KitchenLines.say(p, family, "back door", force = true)) return Said.TAKE
        val words = WORDS[family] ?: return Said.NONE
        val key = words[if (index in words.indices) index else p.random.nextInt(words.size)].removePrefix(SAY)
        return if (p.say(Speech.withDetail(key, detail), force = true)) Said.OWN else Said.NONE
    }

    /** Has [p] say [line] (a key under say.guildmark.) as it is. */
    fun plain(p: Townsperson, line: String): Boolean = p.say(line.removePrefix(SAY), force = true)
}
