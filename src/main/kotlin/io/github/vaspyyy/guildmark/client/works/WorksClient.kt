package io.github.vaspyyy.guildmark.client.works

import io.github.vaspyyy.guildmark.economy.works.build.BeaconFire
import io.github.vaspyyy.guildmark.economy.works.build.MasterBuilder
import io.github.vaspyyy.guildmark.economy.works.build.WorksBeaconBlock
import io.github.vaspyyy.guildmark.economy.works.build.WorksBeaconBlockEntity
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlocks
import io.github.vaspyyy.guildmark.economy.works.build.WorksPostBlock
import io.github.vaspyyy.guildmark.economy.works.build.WorksPostBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.abs

/**
 * The town works on a client (M6 Part B): its blocks' renderers (the post's drawing and seals, the plaque's letters,
 * the yard's heaps, the hoist's rope and load, the beacon's glow, a stall's awning) on Part F's meshes, the beacon's
 * burning heard while it's lit, and each seal heard as it drops out on its post. Wired in from the client's setup.
 */
object WorksClient {
    /** Has it all registered, from the client's setup. */
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(WorksBlocks.POST_ENTITY.get(), ::WorksPostRenderer)
            event.registerBlockEntityRenderer(WorksBlocks.PLAQUE_ENTITY.get(), ::WorksPlaqueRenderer)
            event.registerBlockEntityRenderer(WorksBlocks.YARD_ENTITY.get(), ::WorksYardRenderer)
            event.registerBlockEntityRenderer(WorksBlocks.HOIST_ENTITY.get(), ::WorksHoistRenderer)
            event.registerBlockEntityRenderer(WorksBlocks.BEACON_ENTITY.get(), ::WorksBeaconRenderer)
            event.registerBlockEntityRenderer(WorksBlocks.AWNING_ENTITY.get(), ::WorksAwningRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(WorksYardRenderer.LAYER, WorksMesh::yard)
            event.registerLayerDefinition(WorksHoistRenderer.LAYER, WorksMesh::hoist)
        }
        WorksBeaconBlock.clientTick = ::beacon
        WorksPostBlock.clientTick = ::post
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> retry.clear() }
    }

    /**
     * Whether [entity] is a works' master builder: his handbell is his own (works.handbell, from the server), so a
     * RING_BELL of his wants no crier's bell on the client as well.
     */
    fun master(entity: Entity): Boolean = (entity.customName?.contents as? TranslatableContents)?.key == MasterBuilder.NAME

    // ---- The seals ----

    /** Each tick of a post near this client: its new seals heard as each one's drop lands, and the stamp as it comes down. */
    private fun post(be: WorksPostBlockEntity) {
        val level = be.level ?: return
        val now = level.gameTime
        val from = be.heard
        be.heard = now
        // Nothing heard that came before this client looked (or while it wasn't looking)
        if (from == Long.MIN_VALUE || now <= from || now - from > LOOK_GAP) return
        val at = board(be)
        for (k in be.was until be.seals) {
            val lands = be.sealedAt + (k - be.was) * WorksPostBlockEntity.SEAL_GAP + WorksArt.SEAL_DROP.toLong()
            if (lands in from + 1..now) {
                level.playLocalSound(at.x, at.y, at.z, WorksBlocks.SEAL.get(), SoundSource.NEUTRAL, 0.8f, 0.92f + 0.04f * (k % 4), false)
            }
        }
        if (be.stamped && be.stampedAt in from + 1..now) {
            level.playLocalSound(at.x, at.y + 0.2, at.z, SoundEvents.BOOK_PUT, SoundSource.NEUTRAL, 0.6f, 1.25f, false)
        }
    }

    /** The middle of a post's board, a hair in front of it. */
    private fun board(be: WorksPostBlockEntity): Vec3 {
        val f = WorksPaint.facing(be.blockState)
        return Vec3.atCenterOf(be.blockPos).add(f.stepX * 0.12, 0.6, f.stepZ * 0.12)
    }

    // ---- The beacon's burning ----

    /** When each beacon (by position) may next try to start its loop, after the sound engine had no room for it. */
    private val retry = HashMap<Long, Long>()

    /** Each tick of a beacon near this client: its loop going while it burns, louder while it flares. */
    private fun beacon(be: WorksBeaconBlockEntity) {
        val level = be.level ?: return
        val state = be.blockState
        val fire = if (state.hasProperty(WorksBeaconBlock.FIRE)) state.getValue(WorksBeaconBlock.FIRE) else BeaconFire.OUT
        val volume = when (fire) {
            BeaconFire.OUT -> 0f
            BeaconFire.LIT -> LIT
            BeaconFire.FLARE -> FLARING
        }
        val minecraft = Minecraft.getInstance()
        val manager = minecraft.soundManager
        var loop = be.loop as? BeaconLoop
        if (loop != null && (loop.isStopped || !manager.isActive(loop))) {
            be.loop = null
            loop = null
        }
        if (loop == null) {
            if (volume <= 0f) return
            val player = minecraft.player ?: return
            if (player.distanceToSqr(Vec3.atCenterOf(be.blockPos)) > HEAR * HEAR) return
            val key = be.blockPos.asLong()
            if ((retry[key] ?: Long.MIN_VALUE) > level.gameTime) return
            val fresh = BeaconLoop(be)
            if (manager.play(fresh) == SoundEngine.PlayResult.NOT_STARTED) {
                retry[key] = level.gameTime + RETRY
                return
            }
            retry.remove(key)
            be.loop = fresh
            loop = fresh
        }
        loop.target = volume
        loop.goal = if (fire == BeaconFire.FLARE) 1.1f else 1f
    }

    /** A beacon's burning, at it: easing to [target] and [goal]; once its beacon's gone or out a while, it stops. */
    private class BeaconLoop(private val be: WorksBeaconBlockEntity) :
        AbstractTickableSoundInstance(WorksBlocks.BEACON_LOOP.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom()) {
        var target = 0f
        var goal = 1f
        private var silent = 0

        init {
            looping = true
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0f
            pitch = 1f
            val at = Vec3.atCenterOf(be.blockPos)
            x = at.x
            y = at.y + 0.6
            z = at.z
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (be.isRemoved || be.level !== Minecraft.getInstance().level) target = 0f
            volume += (target - volume) * if (target > volume) 0.08f else 0.05f
            if (abs(target - volume) < 0.002f) volume = target
            pitch += (goal - pitch) * 0.05f
            silent = if (volume <= 0.002f) silent + 1 else 0
            if (silent > STOP_AFTER) stop()
        }
    }

    /** Ticks a post may go unlooked at and still be heard (more, and what dropped meanwhile is let go). */
    private const val LOOK_GAP = 40L

    /** The beacon's loop: lit and flaring; heard from how far; how long to wait to try again; silent how long before it stops. */
    private const val LIT = 0.7f
    private const val FLARING = 1f
    private const val HEAR = 28.0
    private const val RETRY = 40L
    private const val STOP_AFTER = 60
}
