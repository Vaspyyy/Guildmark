package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * Townsfolk: animated city folk with trades and daily rounds (see [Townsperson]). This is the way in for the
 * rest of the mod.
 *
 * To people a town, [spawn] a resident of a role where they should stand, telling them as much as is known:
 * the block they [work] at, where they live ([home], near their bed), which way they face at a [post] such as
 * a stall or a bar, the tavern they spend their [evening] in and the [Town] they belong to. Anything left out
 * is looked for nearby once they're in a loaded world. [roleFor] says who works at a block (an anvil makes a
 * smith), so [spawnAt] can staff a workstation. Spawning is safe during structure generation.
 *
 * A few residents with real jobs go a long way: the [Crowds] fill the streets round players with passers-by.
 * Towns outside the capitals can be marked with [registerTown] so crowds gather there too.
 */
object Townsfolk {
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val PARTICLES: DeferredRegister<ParticleType<*>> = DeferredRegister.create(Registries.PARTICLE_TYPE, Guildmark.MOD_ID)

    val TOWNSPERSON: DeferredHolder<EntityType<*>, EntityType<Townsperson>> = ENTITIES.register("townsperson") { ->
        EntityType.Builder.of(::Townsperson, MobCategory.MISC)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.62f)
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsperson")))
    }

    /** The sparks that fly when a smith's hammer meets hot iron. */
    val FORGE_SPARK: DeferredHolder<ParticleType<*>, SimpleParticleType> = PARTICLES.register("forge_spark") { -> SimpleParticleType(false) }

    /** Registers townsfolk with the game; called once from the mod's constructor. */
    fun init(modBus: IEventBus) {
        ENTITIES.register(modBus)
        PARTICLES.register(modBus)
        modBus.addListener(EntityAttributeCreationEvent::class.java) { it.put(TOWNSPERSON.get(), Townsperson.createAttributes().build()) }
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java) { event ->
            val player = event.entity as? ServerPlayer
            val level = player?.level() as? ServerLevel
            if (player != null && level != null) Crowds.tick(level, player)
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { TownsfolkCommand.register(it.dispatcher) }
    }

    /** A townsperson of [role] standing at [at], not yet added to the world. */
    fun create(
        level: ServerLevelAccessor, at: BlockPos, role: TownRole, look: Int = -1,
        yaw: Float = level.random.nextFloat() * 360f, reason: EntitySpawnReason = EntitySpawnReason.STRUCTURE,
    ): Townsperson? {
        val person = TOWNSPERSON.get().create(level.level, reason) ?: return null
        person.snapTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5, yaw, 0f)
        person.yHeadRot = yaw
        person.yBodyRot = yaw
        person.assign(role, look)
        person.finalizeSpawn(level, level.getCurrentDifficultyAt(at), reason, null)
        return person
    }

    /**
     * Spawns a resident [role] at [at] and returns them. [work] is the block they work at; [post] (with
     * [facing]) is where they stand to work when that isn't simply beside it, as behind a stall's counter or a
     * bar, or at a gate. Merchants, innkeepers and guards given a [facing] but no post keep [at] as their post.
     */
    fun spawn(
        level: ServerLevelAccessor, at: BlockPos, role: TownRole,
        work: BlockPos? = null, home: BlockPos? = null, post: BlockPos? = null, facing: Direction? = null,
        evening: BlockPos? = null, town: Town? = null, look: Int = -1,
    ): Townsperson? {
        val person = create(level, at, role, look, facing?.toYRot() ?: (level.random.nextFloat() * 360f)) ?: return null
        person.home = home ?: at
        person.work = work
        person.evening = evening
        person.town = town
        val keepsPost = role == TownRole.MERCHANT || role == TownRole.INNKEEPER || role == TownRole.GUARD && facing != null
        person.post = post ?: if (keepsPost) at else null
        person.postFacing = facing
        if (role == TownRole.GUARD) person.nightWatch = level.random.nextInt(3) == 0
        person.setPersistenceRequired()
        level.addFreshEntityWithPassengers(person)
        return person
    }

    /** Staffs the workstation at [workstation] (whose block is [state]) with whoever works there, standing at [at]. */
    fun spawnAt(
        level: ServerLevelAccessor, workstation: BlockPos, state: BlockState, at: BlockPos,
        home: BlockPos? = null, evening: BlockPos? = null, town: Town? = null,
    ): Townsperson? {
        val role = roleFor(state) ?: return null
        return spawn(level, at, role, work = workstation, home = home, evening = evening, town = town)
    }

    /** Who works at a block like [state]: an anvil makes a smith, an oven a baker, a composter a farmer. */
    fun roleFor(state: BlockState): TownRole? = when {
        state.`is`(BlockTags.ANVIL) || state.`is`(Blocks.SMITHING_TABLE) || state.`is`(Blocks.GRINDSTONE) || state.`is`(Blocks.BLAST_FURNACE) -> TownRole.SMITH
        state.`is`(Blocks.SMOKER) || state.`is`(Blocks.FURNACE) -> TownRole.BAKER
        state.`is`(Blocks.COMPOSTER) -> TownRole.FARMER
        state.`is`(Blocks.BARREL) -> TownRole.MERCHANT
        state.`is`(Blocks.LECTERN) || state.`is`(Blocks.BREWING_STAND) -> TownRole.PRIEST
        state.`is`(Blocks.JUKEBOX) || state.`is`(Blocks.NOTE_BLOCK) -> TownRole.BARD
        state.`is`(Blocks.LOOM) || state.`is`(Blocks.FLETCHING_TABLE) || state.`is`(Blocks.CARTOGRAPHY_TABLE) ||
            state.`is`(Blocks.STONECUTTER) || state.`is`(Blocks.CRAFTING_TABLE) -> TownRole.CITIZEN
        else -> null
    }

    /** Marks a square of [radius] round [center] as a town, so passers-by gather there. */
    fun registerTown(level: ServerLevel, center: BlockPos, radius: Int) = TownZones.get(level).add(center, radius)
}
