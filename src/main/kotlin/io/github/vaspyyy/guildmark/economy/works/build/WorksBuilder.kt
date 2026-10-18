package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.Worksite
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/** One of a works' crew, a labourer lent by the town or a stranger: a homestead's builder, and a mason and carpenter besides. */
class WorksBuilder(p: Townsperson, id: Int, road: BlockPos, lent: Boolean) : BuilderCrew.Builder(p, id, road, lent) {
    /** A works' stakes are knocked in to its own sound. */
    override fun ownSound(site: Worksite, i: Int): Boolean {
        val kind = site.step(i).kind
        if (kind != StepKind.STAKE && kind != StepKind.PULL_STAKE) return false
        val level = site.level as? ServerLevel ?: return false
        WorksBlocks.play(level, site.at(i), WorksBlocks.STAKE, 0.8f)
        return true
    }
}
