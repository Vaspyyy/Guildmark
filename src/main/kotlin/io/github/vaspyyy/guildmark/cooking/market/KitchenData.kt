package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.Verdict
import java.util.UUID

/*
 * The records the ledger passes round (living-world/cook/design.md 5.3, 12.3). Part G's file: it adds their codecs and
 * whatever else the ledger keeps (the caps, the carries, what is owed). Here they are only the shapes the other parts call.
 */

/** A lot in a door's pantry: [portions] of [kind] at [tier] bought or given and not yet served, cooked by [cook] ([cookName]). */
class Lot(val kind: DishKind, val tier: Tier, val cook: UUID?, val cookName: String, var portions: Int)

/**
 * What one sale came to: [taken] portions off the stack for [marks] whole Marks, whether it was the [first] of its kind
 * sold in the town (paid double), the [tier] and [stage] it was judged at, the keeper's [verdict] on the mix (null when
 * none was given), and whether the door is [full] for the day.
 */
class Sale(val taken: Int, val marks: Int, val first: Boolean, val tier: Tier, val stage: Stage, val verdict: Verdict?, val full: Boolean)

/** A capital's request of the day: [portions] of [kind], and whether it is [filled]. */
class Request(val kind: DishKind, val portions: Int, val filled: Boolean)
