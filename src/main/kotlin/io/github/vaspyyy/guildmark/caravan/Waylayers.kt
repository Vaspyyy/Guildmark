package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.RandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.illager.Pillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.raid.Raider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * The bodies of an outlaw band out on the road: how one is made, how it is made to fight and what it drops as it falls. A caravan
 * struck at its strike point ([Waylay]) and a trader stopped on the road (the Hunt, `trade/hunt/`) both use them, so the two bands
 * look, fight and fall the same way. Moved here out of [Waylay] with nothing changed: the caravans behave exactly as before.
 */
internal object Waylayers {
    /**
     * One of a band, a [type] at [at] facing [yaw], tagged [tag] (so a body saved by mistake goes again as it loads), dropping nothing of its
     * own, going for nobody until it is [arm]ed and staying put until something tells it to move. Null if the world would not take it.
     */
    fun spawn(level: ServerLevel, type: EntityType<out Raider>, at: Vec3, yaw: Float, tag: String): Mob? {
        val mob = type.create(level, EntitySpawnReason.EVENT) ?: return null
        mob.snapTo(at.x, at.y, at.z, yaw, 0f)
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), EntitySpawnReason.EVENT, null)
        mob.setCanJoinRaid(false)
        mob.setPersistenceRequired()
        mob.addTag(tag)
        mob.customName = Component.translatable("entity.guildmark.outlaw")
        for (slot in EquipmentSlot.values()) mob.setDropChance(slot, 0f)
        mob.targetSelector.removeAllGoals { true }
        mob.goalSelector.removeAllGoals { g ->
            g is RandomStrollGoal || g is LookAtPlayerGoal || g is RandomLookAroundGoal ||
                g.javaClass.name.let { it.contains("Raid") || it.contains("Banner") || it.contains("Celebrat") }
        }
        mob.isAggressive = true
        return if (level.addFreshEntity(mob)) mob else null
    }

    /**
     * [mob] fights back for real: it goes for players, for whoever hurt one of its own and for the townsfolk [foe] says are against it (the
     * guards of a caravan, a hired guard); never for anyone else.
     */
    fun arm(mob: Mob, foe: (Entity) -> Boolean) {
        mob.navigation.stop()
        (mob as? Pillager)?.setChargingCrossbow(false)
        (mob as? Raider)?.let { mob.targetSelector.addGoal(1, HurtByTargetGoal(it, Raider::class.java)) }
        mob.targetSelector.addGoal(2, NearestAttackableTargetGoal(mob, Player::class.java, true))
        mob.targetSelector.addGoal(3, NearestAttackableTargetGoal(mob, Townsperson::class.java, 10, true, false) { e, _ -> foe(e) })
    }

    /**
     * What one of a band drops as it falls: the sacks it carried off ([carry], emptied), each the goods still held in a band's loot for
     * [caravan] (the record the goods were put under), marked as off the wagon they were bound by. Nothing for a sack the band no longer has.
     */
    fun drops(level: ServerLevel, caravan: Long, carry: MutableList<Pair<Good, Int>>): List<ItemStack> {
        val out = ArrayList<ItemStack>()
        for ((g, n) in carry) {
            val stack = Caravans.dropped(level, caravan, g, n)
            if (!stack.isEmpty) out.add(stack)
        }
        carry.clear()
        return out
    }
}
