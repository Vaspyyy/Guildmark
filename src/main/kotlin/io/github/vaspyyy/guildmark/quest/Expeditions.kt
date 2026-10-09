package io.github.vaspyyy.guildmark.quest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.StructureTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.resources.Identifier
import io.github.vaspyyy.guildmark.Guildmark
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The world side of deliveries, escorts and champions: finding villages and spawning who's involved. */
object Expeditions {
    /** How close to the destination a board must be to count as "that village". */
    const val ARRIVAL_RADIUS = 128
    /** How close the traveller must be to the board on arrival. */
    const val TRAVELLER_RADIUS = 16.0

    private val CHAMPION_HEALTH = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "champion_health")
    private val CHAMPION_DAMAGE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "champion_damage")

    private val EPITHETS = listOf(
        "the Rotten", "the Hollow", "the Cruel", "Bonegnaw", "the Red", "Grimjaw", "the Unburied",
        "Ironhide", "the Wretched", "Nightcaller", "the Butcher", "Ashmaw",
    )
    private val CHAMPION_NAMES = listOf("Grimbold", "Morvane", "Skarr", "Vex", "Ulgrim", "Draven", "Korrik", "Sable", "Thane", "Mordecai")

    /** Another village 200 to 2000 blocks away, searched for in a few random directions. */
    fun findVillage(level: ServerLevel, from: BlockPos): BlockPos? {
        val random = level.random
        repeat(4) {
            val angle = random.nextDouble() * 2 * Math.PI
            val probe = from.offset((cos(angle) * 600).toInt(), 0, (sin(angle) * 600).toInt())
            val found = level.findNearestMapStructure(StructureTags.VILLAGE, probe, 40, false) ?: return@repeat
            val distance = horizontalDistance(from, found)
            if (distance in 200.0..2000.0) return BlockPos(found.x, 0, found.z)
        }
        return null
    }

    fun horizontalDistance(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    fun atDestination(state: ContractState, boardPos: BlockPos): Boolean =
        state.destination != null && horizontalDistance(state.destination, boardPos) <= ARRIVAL_RADIUS

    /** A traveller standing in front of the board, ready to set off. */
    fun spawnTraveller(level: ServerLevel, boardPos: BlockPos, facing: Direction, name: String): Villager? {
        val villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT) ?: return null
        val spot = boardPos.relative(facing, 2)
        villager.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        villager.customName = Component.literal(name)
        villager.isCustomNameVisible = true
        level.addFreshEntity(villager)
        return villager
    }

    fun championName(level: ServerLevel): String =
        "${CHAMPION_NAMES[level.random.nextInt(CHAMPION_NAMES.size)]} ${EPITHETS[level.random.nextInt(EPITHETS.size)]}"

    /** A named, armoured, glowing mob somewhere 24 to 48 blocks from the board. */
    fun spawnChampion(level: ServerLevel, boardPos: BlockPos, target: Identifier, name: String, tier: Int): Mob? {
        val spot = findOpenGround(level, boardPos) ?: return null
        val mob = BuiltInRegistries.ENTITY_TYPE.getValue(target).create(level, EntitySpawnReason.EVENT) as? Mob ?: return null
        mob.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)

        mob.customName = Component.literal(name)
        mob.isCustomNameVisible = true
        mob.setPersistenceRequired()
        // A helmet also keeps undead champions from burning in daylight
        mob.setItemSlot(EquipmentSlot.HEAD, ItemStack(if (tier >= 3) Items.DIAMOND_HELMET else Items.IRON_HELMET))
        mob.setItemSlot(EquipmentSlot.CHEST, ItemStack(if (tier >= 2) Items.DIAMOND_CHESTPLATE else Items.IRON_CHESTPLATE))
        if (mob.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty) mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
        mob.setGuaranteedDrop(EquipmentSlot.HEAD)

        mob.getAttribute(Attributes.MAX_HEALTH)?.addPermanentModifier(
            AttributeModifier(CHAMPION_HEALTH, 1.0 + tier, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
        )
        mob.getAttribute(Attributes.ATTACK_DAMAGE)?.addPermanentModifier(
            AttributeModifier(CHAMPION_DAMAGE, 0.25 * tier, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
        )
        mob.health = mob.maxHealth
        mob.addEffect(MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0, false, false))
        level.addFreshEntity(mob)
        return mob
    }

    private fun findOpenGround(level: ServerLevel, around: BlockPos): BlockPos? {
        val random = level.random
        repeat(40) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 24 + random.nextInt(25)
            val column = around.offset((cos(angle) * distance).toInt(), 0, (sin(angle) * distance).toInt())
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column)
            val ground = top.below()
            if (level.getFluidState(ground).isEmpty && level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP) &&
                level.getBlockState(top).canBeReplaced() && level.getBlockState(top.above()).canBeReplaced()
            ) return top
        }
        return null
    }

    /** Keep an escorted traveller with the player carrying its contract; called every 10 ticks. */
    fun followPlayer(level: ServerLevel, player: Player, travellerId: java.util.UUID) {
        val villager = level.getEntity(travellerId) as? Villager ?: return
        val distance = villager.distanceTo(player)
        if (distance > 24f) {
            villager.teleportTo(player.x, player.y, player.z)
        } else if (distance > 3f) {
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(player, 0.7f, 2))
        }
    }

    fun travellerNear(level: ServerLevel, travellerId: java.util.UUID?, boardPos: BlockPos): Boolean {
        val villager = level.getEntity(travellerId ?: return false) as? Villager ?: return false
        return villager.isAlive && villager.position().closerThan(net.minecraft.world.phys.Vec3.atCenterOf(boardPos), TRAVELLER_RADIUS)
    }
}
