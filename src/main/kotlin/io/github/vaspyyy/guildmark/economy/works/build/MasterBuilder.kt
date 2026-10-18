package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent

/** The master builder of each works going up (design section 3, "The crew"), and its ceremonies. */
internal object MasterBuilder {
    fun onJoin(event: EntityJoinLevelEvent) {}

    fun reset() {}

    /** Works [id]'s master gone from the world (nobody near, or the works done with him). */
    fun away(level: ServerLevel, id: Int) {}

    /** Each second while [w] is built: its master there while [present] (a player near and the materials in). */
    fun tick(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece, present: Boolean) {}

    /** [stage] of [site] is done, someone near to hear it called. */
    fun calls(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, stage: Stage) {}

    /** [w] is topped out: its toast (and at the tower, its beacon's first fire). */
    fun toppedOut(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, near: Boolean) {
        if (rec.toasted) return
        rec.toasted = true
        WorksBuildData.get(level).changed()
    }

    /** [w] stands finished: its opening, true once it's over. */
    fun opening(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece, near: Boolean): Boolean {
        val pin = site.drawing.plaque ?: return true
        val pos = site.frame.world(pin.x, site.drawing.y0 + pin.y, pin.z)
        if (rec.plaque == null && level.getBlockState(pos).isAir) {
            val state = WorksBlocks.PLAQUE.get().defaultBlockState()
                .setValue(WorksFacingBlock.FACING, site.frame.direction(pin.facing))
                .setValue(WorksPlaqueBlock.COVERED, false)
            level.setBlock(pos, state, Block.UPDATE_ALL)
            rec.plaque = pos
            WorksBuildData.get(level).changed()
        }
        return !level.getBlockState(pos).`is`(Blocks.AIR) || rec.plaque == null
    }

    /** Works [id]'s master, while he's about. */
    fun body(level: ServerLevel, id: Int): Townsperson? = null
}
