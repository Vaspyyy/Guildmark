package io.github.vaspyyy.guildmark.cooking

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer

/**
 * What a cook is shown doing (living-world/cook/design.md 1.8). Part E's: every action of [CookAction] is sent as a
 * [CookActionPayload] to the players who see the cook, so their body plays the motion. Until it lands, nothing is shown.
 */
object CookActions {
    /** Shows [player] doing [action] at [pos] to the players tracking them, at most six a second a cook (stub: nothing). */
    fun play(player: ServerPlayer, action: CookAction, pos: BlockPos) {}

    /** Part E's registration: the `cook anim` command and the like (stub: nothing). */
    fun register() {}
}
