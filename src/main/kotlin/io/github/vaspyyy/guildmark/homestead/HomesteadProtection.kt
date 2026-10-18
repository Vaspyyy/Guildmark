package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.StemBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.util.TriState
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent
import net.neoforged.neoforge.event.level.BlockDropsEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import java.util.UUID

/**
 * A homestead's land is its owner's: only they and their party may break or place blocks on it, open its containers,
 * sleep in its beds or use things on it; anyone else is told whose land it is, politely, and can still come and go
 * through its doors and gates. No explosion takes a block of it, no mob grieves it (the sheep still crop the grass),
 * and nothing tramples its fields. (GuildmarkConfig homesteadProtection.)
 *
 * What the builders put up is the builders' guild's work, paid for in Marks: broken, it gives nothing back but the
 * crops, so a homestead can't be taken apart for more than went into it.
 */
object HomesteadProtection {
    /** The beasts let out into a homestead's pens wear this. */
    const val STOCK_TAG = "guildmark.homestead_stock"

    private val lastRefusal = HashMap<UUID, Long>()

    /** Operators trying the land as a stranger would (/guildmark homestead stranger). */
    val strangers = HashSet<UUID>()

    fun enabled(): Boolean = try {
        GuildmarkConfig.HOMESTEAD_PROTECTION.get()
    } catch (e: IllegalStateException) {
        true
    }

    private fun homestead(level: Level, pos: BlockPos): Homestead? {
        if (level !is ServerLevel || level.dimension() != Level.OVERWORLD) return null
        return Homesteads.at(level, pos)
    }

    /** Whether [player] may use (break, place, open) what's at [pos]. */
    fun mayUse(level: ServerLevel, player: Player, pos: BlockPos): Boolean {
        val h = homestead(level, pos) ?: return true
        return mayUse(level, h, player)
    }

    fun mayUse(level: ServerLevel, h: Homestead, player: Player): Boolean {
        if (player.uuid in strangers) return false
        if (!enabled()) return true
        if (Homesteads.belongsTo(level, h, player)) return true
        return player.isCreative && player is ServerPlayer && level.server.playerList.isOp(player.nameAndId())
    }

    /** Tells [player] whose land this is, at most every couple of seconds, with a shake of the head. */
    fun refuse(level: ServerLevel, player: Player, pos: BlockPos, what: String) {
        val sp = player as? ServerPlayer ?: return
        val now = level.gameTime
        if ((lastRefusal[player.uuid] ?: -1000L) + 40L > now) return
        if (lastRefusal.size > 512) lastRefusal.clear()
        lastRefusal[player.uuid] = now
        val owner = homestead(level, pos)?.ownerName ?: "?"
        sp.sendOverlayMessage(Component.translatable("message.guildmark.homestead.refuse_$what", owner).withStyle(ChatFormatting.GOLD))
        sp.connection.send(
            ClientboundSoundPacket(HomesteadRegistry.REFUSE, SoundSource.PLAYERS, sp.x, sp.y, sp.z, 0.6f, 1f, level.random.nextLong()),
        )
    }

    // ---- What strangers may not do ----

    fun onBreak(event: BreakBlockEvent) {
        val level = event.level as? ServerLevel ?: return
        val player = event.player ?: return
        if (mayUse(level, player, event.pos)) return
        event.isCanceled = true
        refuse(level, player, event.pos, "break")
    }

    fun onPlace(event: BlockEvent.EntityPlaceEvent) {
        val level = event.level as? ServerLevel ?: return
        val player = event.entity as? Player ?: return
        if (mayUse(level, player, event.pos)) return
        event.isCanceled = true
        refuse(level, player, event.pos, "place")
    }

    fun onUseBlock(event: PlayerInteractEvent.RightClickBlock) {
        val level = event.level as? ServerLevel ?: return
        val player = event.entity
        val pos = event.pos
        val h = homestead(level, pos) ?: run {
            // Placing something onto the land from outside it
            placingOnto(level, player, event)
            return
        }
        if (mayUse(level, h, player)) return
        val state = level.getBlockState(pos)
        // The way in and out, the post (which says whose land it is), a bench and the bell are anyone's
        val open = state.`is`(BlockTags.DOORS) || state.`is`(BlockTags.FENCE_GATES) || state.`is`(BlockTags.TRAPDOORS) ||
            state.`is`(HomesteadRegistry.CHARTER_POST.get()) || state.`is`(Blocks.CRAFTING_TABLE) || state.block is BellBlock
        if (open) {
            event.useItem = TriState.FALSE
            return
        }
        event.isCanceled = true
        event.cancellationResult = InteractionResult.FAIL
        refuse(level, player, pos, if (state.hasBlockEntity()) "open" else "use")
    }

    private fun placingOnto(level: ServerLevel, player: Player, event: PlayerInteractEvent.RightClickBlock) {
        if (player.getItemInHand(event.hand).item !is BlockItem) return
        val onto = event.pos.relative(event.face ?: return)
        val h = homestead(level, onto) ?: return
        if (mayUse(level, h, player)) return
        event.useItem = TriState.FALSE
        refuse(level, player, onto, "place")
    }

    /** A bucket emptied or filled on the land from a distance. */
    fun onUseItem(event: PlayerInteractEvent.RightClickItem) {
        val level = event.level as? ServerLevel ?: return
        val player = event.entity
        if (player.getItemInHand(event.hand).item !is BucketItem) return
        val hit = player.pick(5.0, 1f, true) as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        for (pos in listOf(hit.blockPos, hit.blockPos.relative(hit.direction))) {
            val h = homestead(level, pos) ?: continue
            if (mayUse(level, h, player)) return
            event.isCanceled = true
            event.cancellationResult = InteractionResult.FAIL
            refuse(level, player, pos, "use")
            return
        }
    }

    /** The beasts in the pens, the frames and stands: the owner's. */
    fun onUseEntity(event: PlayerInteractEvent.EntityInteract) {
        val level = event.level as? ServerLevel ?: return
        if (!guarded(event.target)) return
        val pos = event.target.blockPosition()
        if (mayUse(level, event.entity, pos)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.FAIL
        refuse(level, event.entity, pos, "stock")
    }

    fun onAttack(event: AttackEntityEvent) {
        val level = event.entity.level() as? ServerLevel ?: return
        if (!guarded(event.target)) return
        val pos = event.target.blockPosition()
        if (mayUse(level, event.entity, pos)) return
        event.isCanceled = true
        refuse(level, event.entity, pos, "stock")
    }

    private fun guarded(entity: Entity): Boolean =
        STOCK_TAG in entity.entityTags() || entity is HangingEntity || entity is ArmorStand

    // ---- What nothing may do ----

    fun onExplode(event: ExplosionEvent.Detonate) {
        val level = event.level as? ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD || !enabled()) return
        val data = Homesteads.get(level)
        if (data.homesteads.isEmpty()) return
        event.affectedBlocks.removeIf { pos -> data.covering(pos.x, pos.z) != null }
    }

    fun onGrief(event: EntityMobGriefingEvent) {
        val entity = event.entity
        if (entity is Sheep || entity is Player) return
        val level = entity.level() as? ServerLevel ?: return
        if (!enabled()) return
        if (homestead(level, entity.blockPosition()) != null) event.setCanGrief(false)
    }

    fun onTrample(event: BlockEvent.FarmlandTrampleEvent) {
        val level = event.level as? ServerLevel ?: return
        if (homestead(level, event.pos) != null) event.isCanceled = true
    }

    /** Tells whether a block is one a town works' builders put up (economy/works/build sets it at setup). */
    fun interface Built {
        fun built(level: ServerLevel, pos: BlockPos): Boolean
    }

    /** The town works' builders' blocks, which give nothing back either (null until the works' building is set up). */
    var works: Built? = null

    /** The builders' work gives nothing back when broken, but for what grows in the fields; nor does a town works'. */
    fun onDrops(event: BlockDropsEvent) {
        val level = event.level as? ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD) return
        val state = event.state
        if (state.block is CropBlock || state.block is StemBlock || state.`is`(Blocks.PUMPKIN) || state.`is`(Blocks.MELON)) return
        val h = Homesteads.at(level, event.pos)
        if (h == null) {
            if (works?.built(level, event.pos) == true) {
                event.drops.clear()
                event.droppedExperience = 0
            }
            return
        }
        if (isWork(level, h, event.pos)) {
            event.drops.clear()
            event.droppedExperience = 0
        }
    }

    /** A bed on their own land: home. */
    fun onSpawnSet(event: PlayerSetSpawnEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val pos = event.newSpawn ?: return
        if (event.isForced || event.spawnLevel != Level.OVERWORLD) return
        val level = player.level()
        val h = homestead(level, pos) ?: return
        if (!Homesteads.belongsTo(level, h, player)) return
        player.sendSystemMessage(Component.translatable("message.guildmark.homestead.home_bed").withStyle(ChatFormatting.GOLD))
    }

    // ---- What the builders put up ----

    private class Work(val tier: Int, val done: Int, val positions: LongOpenHashSet)

    private val work = HashMap<Int, Work>()

    /** Whether [pos] is a block the builders put up on [h] (any tier finished, or laid already of the one going up). */
    fun isWork(level: ServerLevel, h: Homestead, pos: BlockPos): Boolean {
        val site = HomesteadWorks.live(h.id)
        val laid = site?.laid ?: 0
        var w = work[h.id]
        if (w == null || w.tier != h.tier || w.done != laid) {
            val set = LongOpenHashSet()
            val data = Homesteads.get(level)
            val frame = h.frame
            fun add(kind: StepKind, x: Int, y: Int, z: Int) {
                when (kind) {
                    StepKind.BLOCK, StepKind.SHIPPING -> set.add(BlockPos.asLong(frame.worldX(x, z), y, frame.worldZ(x, z)))
                    StepKind.STAKE -> {
                        set.add(BlockPos.asLong(frame.worldX(x, z), y, frame.worldZ(x, z)))
                        set.add(BlockPos.asLong(frame.worldX(x, z), y + 1, frame.worldZ(x, z)))
                    }
                    StepKind.GROUND -> set.add(BlockPos.asLong(frame.worldX(x, z), y - 1, frame.worldZ(x, z)))
                    else -> {}
                }
            }
            for (t in 0..h.tier) for (s in data.plan(h, t).steps) add(s.kind, s.x, s.y, s.z)
            if (site != null) for (i in 0 until site.total) if (site.isDone(i)) {
                val s = site.step(i)
                add(s.kind, s.x, s.y, s.z)
            }
            w = Work(h.tier, laid, set)
            work[h.id] = w
        }
        return pos.asLong() in w.positions
    }

    fun forget(id: Int) {
        work.remove(id)
    }

    fun reset() {
        work.clear()
        lastRefusal.clear()
        strangers.clear()
    }
}
