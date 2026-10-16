package io.github.vaspyyy.guildmark.sea

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.ship.Cog
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.entity.EntityMountEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import kotlin.math.floor

/**
 * What hunts the open sea: packs of Gnashfins that run down anyone out on the water by night or in foul weather, and,
 * far out over the deeps, the Maelgrasp, that rises under a ship and takes hold of her. Everything about them lives in
 * this package; the mod's main class only calls [register]. When and where they come is [SeaHunts]'s to decide.
 */
object Seas {
    fun register(modBus: IEventBus) {
        SeaRegistry.ENTITIES.register(modBus)
        SeaRegistry.ITEMS.register(modBus)
        SeaRegistry.SOUNDS.register(modBus)
        SeaRegistry.ATTACHMENTS.register(modBus)
        modBus.addListener(EntityAttributeCreationEvent::class.java) {
            it.put(SeaRegistry.GNASHFIN.get(), Gnashfin.createAttributes().build())
            it.put(SeaRegistry.MAELGRASP_ARM.get(), MaelgraspArm.createAttributes().build())
            it.put(SeaRegistry.MAELGRASP.get(), Maelgrasp.createAttributes().build())
        }
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) {
                event.accept(SeaRegistry.GNASHFIN_FANG.get())
                event.accept(SeaRegistry.RAW_GNASHFIN.get())
                event.accept(SeaRegistry.COOKED_GNASHFIN.get())
                event.accept(SeaRegistry.MAELGRASP_BEAK.get())
            }
        }
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (level.dimension() == Level.OVERWORLD) SeaHunts.tick(level)
        }
        // Caught in an arm, you can't just step off it: you struggle free (see MaelgraspArm.holding)
        NeoForge.EVENT_BUS.addListener(EntityMountEvent::class.java) { event ->
            val arm = event.entityBeingMounted as? MaelgraspArm
            if (arm != null) {
                if (event.isDismounting && !arm.letsGo(event.entityMounting)) event.isCanceled = true
            } else if (event.isMounting && !event.level.isClientSide && event.entityMounting.vehicle is MaelgraspArm) {
                // Nor can they board anything while it holds them (the ship, a steed): they'd still hang on the arm too
                event.isCanceled = true
            }
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { SeaCommand.register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> SeaHunts.clear() }
    }

    /** Whether anything hunts the sea at all (the sea monsters setting). */
    fun enabled(): Boolean = try {
        GuildmarkConfig.SEA_MONSTERS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** The top of the water at ([x], [z]), looking a little above and below sea level, if there's water there. */
    fun surface(level: Level, x: Double, z: Double): Double? {
        val cursor = BlockPos.MutableBlockPos()
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        for (by in level.seaLevel + 2 downTo level.seaLevel - 6) {
            cursor.set(bx, by, bz)
            val fluid = level.getFluidState(cursor)
            if (fluid.`is`(FluidTags.WATER)) return by + fluid.getHeight(level, cursor).toDouble()
        }
        return null
    }

    /** How deep the water is under ([x], [z]) from its [surface], up to [most] blocks. */
    fun depth(level: Level, x: Double, z: Double, surface: Double, most: Int): Int {
        val cursor = BlockPos.MutableBlockPos()
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val top = floor(surface - 0.01).toInt()
        for (d in 0 until most) {
            cursor.set(bx, top - d, bz)
            if (!level.getFluidState(cursor).`is`(FluidTags.WATER)) return d
        }
        return most
    }

    /** The players aboard [ship], alive and in the game. */
    fun crew(ship: Cog): List<Player> = ship.passengers.filterIsInstance<Player>().filter { it.isAlive && !it.isSpectator }

    /** Everyone aboard [ship], players and whoever travels with them, alive and in the game. */
    fun aboard(ship: Cog): List<Entity> = ship.passengers.filter { it.isAlive && !it.isSpectator }

    /**
     * Half her breadth at the rail, [z] blocks forward of her middle: 0 at the stem, widest just forward of amidships,
     * narrowing to her transom (the same lines as her waterline in Cog).
     */
    fun beam(z: Double): Double {
        if (z >= BEAM_Z[0]) return BEAM[0]
        for (i in 1 until BEAM_Z.size) {
            if (z >= BEAM_Z[i]) {
                val t = (z - BEAM_Z[i]) / (BEAM_Z[i - 1] - BEAM_Z[i])
                return BEAM[i] + (BEAM[i - 1] - BEAM[i]) * t
            }
        }
        return BEAM[BEAM.size - 1]
    }

    /** How high her deck is [z] blocks forward of her middle: the stern castle aft, the foredeck up at the bow, the waist between. */
    fun deckAt(z: Double): Double = when {
        z < -2.6 -> 1.875
        z > 3.0 -> 1.25
        else -> 1.2
    }

    private val BEAM_Z = doubleArrayOf(4.1, 3.2, 1.8, 0.0, -2.0, -3.8, -4.6)
    private val BEAM = doubleArrayOf(0.0, 0.9, 1.5, 1.75, 1.7, 1.3, 0.7)
}
