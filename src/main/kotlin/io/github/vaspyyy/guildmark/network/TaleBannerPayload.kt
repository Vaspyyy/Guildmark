package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Tale

/**
 * Stage 7 of `Tales.finish` (living-world/stories/design.md 6.7, 5.3), Part D: the banner. [send] tells every player who was
 * IN (or DONE) that the tale ended, with the ending's line and its sound, and nobody else. Part D makes this the payload
 * class (`TaleBannerPayload(kind, titleKey, titleArgs, subKey, subArgs)`) with [send] in its companion, which the calls
 * already written read the same. Runs once for a tale; the engine guards it.
 */
object TaleBannerPayload {
    fun send(c: Ctx, t: Tale) {}
}
