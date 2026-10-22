package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Verdict
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/** What the ladle tells a cook and what a keeper says of a dish (living-world/cook/design.md 2.6). Part A's; until it lands, they say nothing. */
object Hints {
    /** The ladle's hint to [player] about a pot of [share] cooking as [kind] (null: Gruel) at [clock] dish-seconds (stub: nothing said). */
    fun taste(player: ServerPlayer, kind: DishKind?, share: DoubleArray, clock: Double?): Component = Component.empty()

    /** A keeper's verdict on a mix of [share] as [kind] in [level]'s world (stub: the body is as it should be, far from certain). */
    fun verdict(level: ServerLevel, kind: DishKind, share: DoubleArray): Verdict = Verdict(Role.BODY, false, 0.0, 0)
}
