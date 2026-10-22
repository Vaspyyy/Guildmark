package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The base of every cooking station's block entity (living-world/cook/design.md 1.8, 9.1): the sync every station shares
 * and the [view] a renderer draws. A station keeps its own fields in [saveAdditional] and [loadAdditional] (with a
 * default for each) and tells the clients with [sync]; the update tag is its saved tag, so a late joiner sees it as it
 * stands. [station] is null for the Prep Board, which is a helper and not a fire.
 *
 * `cook view` can put a made-up view on a station for a while, for the renderers, the sounds and the screenshots, without
 * cooking anything ([showFake]); it goes to the clients in the update tag and is never saved. Part B subclasses this for
 * the four stations and the Prep Board and overrides [realView]; until then a station shows cold and empty.
 */
abstract class StationBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState, val station: Station?) : BlockEntity(type, pos, state) {
    private var fake: StationView? = null
    private var fakeUntil = 0L
    private var sending = false

    /** What the station really is now, worked out from its own fields; a stub station is cold and empty. */
    protected open fun realView(): StationView = StationView.cold(station)

    /** What a renderer draws now: the made-up view of `cook view` while it lasts, else [realView]. */
    fun view(): StationView {
        val shown = fake
        if (shown != null && (level?.gameTime ?: 0L) < fakeUntil) return shown
        return realView()
    }

    /** Server side: shows [view] on this station to everyone who sees it until game time [until], saved nowhere. */
    fun showFake(view: StationView, until: Long) {
        fake = view
        fakeUntil = until
        sync()
    }

    /** Server side: takes the made-up view off again. */
    fun clearFake() {
        if (fake == null) return
        fake = null
        sync()
    }

    /** Server side: this station's fields changed; keep them with the chunk and send them to the clients. */
    protected fun sync() {
        setChanged()
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        val shown = fake
        if (sending && shown != null) {
            output.putLong("fakeUntil", fakeUntil)
            shown.write(output.child("fake"))
        }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        val shown = input.child("fake")
        if (shown.isPresent) {
            fake = StationView.read(shown.get(), station)
            fakeUntil = input.getLongOr("fakeUntil", 0L)
        } else {
            fake = null
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        sending = true
        try {
            return saveCustomOnly(registries)
        } finally {
            sending = false
        }
    }

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)
}
