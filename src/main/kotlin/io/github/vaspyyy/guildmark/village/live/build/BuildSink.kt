package io.github.vaspyyy.guildmark.village.live.build

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * The one door every village builder puts its blocks through (living-world/villages/design.md section 12.2). The names and
 * signatures are the contract of design section 19.3; part V9 fills in the recording, so a crew can lay a project over days.
 * Until then no recording is ever open, so a block is set where it is put, and what is to be done afterwards is done at once:
 * the builders that go through it build as they always did.
 */
object BuildSink {
    /** Puts [state] at [pos]: sets it, as `level.setBlock` does, while no recording is open. */
    fun put(level: Level, pos: BlockPos, state: BlockState) {
        level.setBlock(pos, state, Block.UPDATE_ALL)
    }

    /** Runs [body], recording what it puts for the village [key]'s [project] when a recording can be opened; for now it only runs it. */
    fun <T> record(level: ServerLevel, key: Long, project: Int, body: () -> T): T = body()

    /** Does [task] when the recording ends (the hall's receptionist, the market's stall-keepers); with none open, at once. */
    fun afterwards(task: () -> Unit) = task()
}
