package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.util.Prediction
import net.minecraft.world.Container
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.AttachedStemBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.neoforged.neoforge.common.Tags
import net.neoforged.neoforge.event.AnvilUpdateEvent
import net.neoforged.neoforge.event.brewing.PlayerBrewedPotionEvent
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent
import net.neoforged.neoforge.event.entity.player.AnvilCraftEvent
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent
import net.neoforged.neoforge.event.level.BlockDropsEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.PistonEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The skills that grow outside a fight: mining, woodcutting, farming, fishing, the crafts and trade. Each
 * hook gives the skill XP and plays out what the tree's nodes promise (more drops, faster digging,
 * a tree felled whole...).
 */
object Work {
    /** A tree being felled or a vein being dug: the extra blocks don't start chains of their own. */
    private var chaining = false

    private fun chance(player: Player, p: Double): Boolean = p > 0.0 && player.random.nextDouble() < p

    private fun silkTouch(tool: ItemStack): Boolean = tool.enchantments.keySet().any { it.`is`(Enchantments.SILK_TOUCH) }

    // ---- Mining, woodcutting, farming ----

    private fun isStone(state: BlockState): Boolean =
        !state.`is`(Tags.Blocks.COBBLESTONES) && (state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.BASE_STONE_NETHER) ||
            state.`is`(Tags.Blocks.END_STONES) || state.`is`(Tags.Blocks.OBSIDIANS) || state.`is`(Tags.Blocks.NETHERRACKS))

    private fun oreXp(state: BlockState): Double = when {
        state.`is`(Tags.Blocks.ORES_NETHERITE_SCRAP) -> 22.0
        state.`is`(Tags.Blocks.ORES_DIAMOND) || state.`is`(Tags.Blocks.ORES_EMERALD) -> 14.0
        state.`is`(Tags.Blocks.ORES_GOLD) -> 7.0
        state.`is`(Tags.Blocks.ORES_LAPIS) -> 6.0
        state.`is`(Tags.Blocks.ORES_IRON) -> 5.0
        state.`is`(Tags.Blocks.ORES_REDSTONE) -> 4.0
        state.`is`(Tags.Blocks.ORES_QUARTZ) -> 3.0
        state.`is`(Tags.Blocks.ORES_COAL) || state.`is`(Tags.Blocks.ORES_COPPER) -> 2.5
        else -> 4.0
    }

    /**
     * A log in a living tree: leaves that grew there (not placed ones) are close by, or atop its trunk. A
     * giant spruce or jungle tree carries its leaves well above the ground, so the trunk is climbed to them.
     */
    private fun natural(level: ServerLevel, pos: BlockPos): Boolean {
        if (leavesNear(level, pos)) return true
        var top = pos
        while (top.y - pos.y < TRUNK_CLIMB && level.getBlockState(top.above()).`is`(BlockTags.LOGS)) top = top.above()
        return top != pos && leavesNear(level, top)
    }

    private fun leavesNear(level: ServerLevel, pos: BlockPos): Boolean {
        for (dy in -1..4) for (dx in -3..3) for (dz in -3..3) {
            val state = level.getBlockState(pos.offset(dx, dy, dz))
            if (state.block is LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT)) return true
        }
        return false
    }

    private fun ripe(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean {
        val block = state.block
        return when {
            block is CropBlock -> block.isMaxAge(state)
            block is NetherWartBlock -> state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE
            block is CocoaBlock -> state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE
            // A melon or pumpkin still on its vine
            state.`is`(Blocks.MELON) || state.`is`(Blocks.PUMPKIN) -> Direction.Plane.HORIZONTAL.any { side ->
                val stem = level.getBlockState(pos.relative(side))
                stem.block is AttachedStemBlock && stem.getValue(AttachedStemBlock.FACING) == side.opposite
            }
            else -> false
        }
    }

    /** An ore or log put down is remembered, so breaking it again isn't mining or woodcutting. */
    fun onPlaced(event: BlockEvent.EntityPlaceEvent) {
        val level = event.level as? ServerLevel ?: return
        if (PlacedBlocks.tracked(event.placedBlock)) PlacedBlocks.mark(level, event.pos)
    }

    /** A piston carries a placed block's mark along with it, so pushing it aside doesn't make it natural. */
    fun onPiston(event: PistonEvent.Pre) {
        val level = event.level as? ServerLevel ?: return
        val resolver = event.structureHelper ?: return
        if (!resolver.resolve()) return
        val moved = resolver.toPush.filter { PlacedBlocks.has(level, it) }
        if (moved.isEmpty()) return
        for (pos in moved) PlacedBlocks.take(level, pos)
        for (pos in moved) PlacedBlocks.mark(level, pos.relative(resolver.pushDirection))
    }

    fun onBreak(event: BreakBlockEvent) {
        if (event.isCanceled) return
        val player = event.player as? ServerPlayer ?: return
        val level = player.level()
        val state = event.state
        val pos = event.pos
        val placed = PlacedBlocks.has(level, pos)
        // Nothing drops from a creative or bare-handed break to clear the mark, so it goes now
        if (placed && (player.isCreative || !player.hasCorrectToolForDrops(state))) PlacedBlocks.take(level, pos)
        if (player.isCreative || player.isSpectator || placed) return
        val tool = player.mainHandItem
        val silk = silkTouch(tool)
        val ore = state.`is`(Tags.Blocks.ORES)
        val log = state.`is`(BlockTags.LOGS)
        when {
            ore -> if (!silk && player.hasCorrectToolForDrops(state)) Skills.gain(player, Skill.MINING, oreXp(state))
            isStone(state) -> if (!silk && tool.`is`(ItemTags.PICKAXES)) {
                Skills.gain(player, Skill.MINING, (0.3 + state.getDestroySpeed(level, pos) * 0.1).coerceAtMost(2.0))
            }
            log -> if (natural(level, pos)) Skills.gain(player, Skill.WOODCUTTING, 1.5)
            ripe(level, pos, state) -> Skills.gain(player, Skill.FARMING, 2.0)
        }
        if (chaining || player.isShiftKeyDown) return
        val build = Talents.build(player)
        when {
            // The tree's own logs from the cut up: not the stump below, nor a cabin's beam that touches it
            log && build.has(Bonus.FELLER) && tool.`is`(ItemTags.AXES) && natural(level, pos) ->
                chain(player, level, pos, FELL_LIMIT, 8, 40) { at, it -> at.y >= pos.y && it.`is`(BlockTags.LOGS) && !PlacedBlocks.has(level, at) && natural(level, at) }
            ore && build.has(Bonus.VEIN_MINER) && tool.`is`(ItemTags.PICKAXES) && player.hasCorrectToolForDrops(state) ->
                chain(player, level, pos, VEIN_LIMIT, 5, 5) { at, it -> it.block === state.block && !PlacedBlocks.has(level, at) }
        }
    }

    /**
     * Breaks up to [limit] blocks joined to [origin] that [matches] accepts, as the player would, one
     * by one: the tool wears for each and stops short of breaking. Spawn protection and the world
     * border hold, as they would for a block broken by hand.
     */
    private fun chain(player: ServerPlayer, level: ServerLevel, origin: BlockPos, limit: Int, wide: Int, high: Int, matches: (BlockPos, BlockState) -> Boolean) {
        val found = ArrayList<BlockPos>()
        val seen = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        queue.add(origin)
        seen.add(origin)
        search@ while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val next = at.offset(dx, dy, dz)
                if (!seen.add(next)) continue
                if (abs(next.x - origin.x) > wide || abs(next.z - origin.z) > wide || next.y - origin.y > high || origin.y - next.y > wide) continue
                if (!level.mayInteract(player, next) || !matches(next, level.getBlockState(next))) continue
                found.add(next)
                queue.add(next)
                if (found.size >= limit) break@search
            }
        }
        if (found.isEmpty()) return
        // Nearest first, so a tree comes down from the cut upwards
        found.sortBy { it.distSqr(origin) }
        chaining = true
        try {
            for (at in found) {
                val tool = player.mainHandItem
                if (tool.isDamageableItem && tool.maxDamage - tool.damageValue <= 2) break
                player.gameMode.destroyBlock(at)
            }
        } finally {
            chaining = false
        }
    }

    /** Pickaxes, axes and shovels bite faster. The client works this out too, for the crack's speed. */
    fun onBreakSpeed(event: PlayerEvent.BreakSpeed) {
        val build = Talents.build(event.entity)
        val state = event.state
        var bonus = 0.0
        if (state.`is`(BlockTags.MINEABLE_WITH_PICKAXE)) bonus += build[Bonus.STONE_SPEED]
        if (state.`is`(BlockTags.MINEABLE_WITH_AXE)) bonus += build[Bonus.LOG_SPEED]
        if (state.`is`(BlockTags.MINEABLE_WITH_SHOVEL)) bonus += build[Bonus.DIRT_SPEED]
        if (bonus != 0.0) event.newSpeed = (event.newSpeed * (1.0 + bonus)).toFloat().coerceAtLeast(0f)
    }

    /** More ore, logs and crops than the block would give, and crops that plant themselves again. */
    fun onDrops(event: BlockDropsEvent) {
        val level = event.level
        val pos = event.pos
        // Put down by hand, it gives back itself and no more
        if (PlacedBlocks.take(level, pos)) return
        val player = event.breaker as? ServerPlayer ?: return
        val drops = event.drops
        if (drops.isEmpty()) return
        val state = event.state
        val build = Talents.build(player)
        when {
            // As with Fortune, an ore that drops itself (ancient debris) isn't doubled
            state.`is`(Tags.Blocks.ORES) -> if (!silkTouch(event.tool) && drops.none { it.item.`is`(state.block.asItem()) } &&
                chance(player, build[Bonus.ORE_DOUBLE])) double(level, drops)
            state.`is`(BlockTags.LOGS) -> if (chance(player, build[Bonus.LOG_DOUBLE])) double(level, drops)
            ripeDropped(state) -> {
                if (chance(player, build[Bonus.CROP_EXTRA])) {
                    // One more of the harvest, not the seed
                    val seed = state.block.asItem()
                    (drops.firstOrNull { !it.item.`is`(seed) } ?: drops.first()).let { drops.add(copyOf(level, it, 1)) }
                }
                if (build.has(Bonus.REPLANT)) replant(level, pos, state, drops)
            }
        }
    }

    /** [ripe] for a block that's already gone: the stem check can't be made, so melons and pumpkins count as they are. */
    private fun ripeDropped(state: BlockState): Boolean {
        val block = state.block
        return block is CropBlock && block.isMaxAge(state) ||
            block is NetherWartBlock && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE ||
            block is CocoaBlock && state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE
    }

    private fun double(level: ServerLevel, drops: MutableList<ItemEntity>) {
        for (drop in drops.toList()) drops.add(copyOf(level, drop, drop.item.count))
    }

    private fun copyOf(level: ServerLevel, drop: ItemEntity, count: Int): ItemEntity =
        ItemEntity(level, drop.x, drop.y, drop.z, drop.item.copyWithCount(count)).also {
            it.deltaMovement = drop.deltaMovement
            it.setDefaultPickUpDelay()
        }

    /** Green Hand: a harvested crop is sown again from its own seed. */
    private fun replant(level: ServerLevel, pos: BlockPos, state: BlockState, drops: MutableList<ItemEntity>) {
        val block = state.block
        val young = when (block) {
            is CropBlock -> block.getStateForAge(0)
            is NetherWartBlock -> state.setValue(NetherWartBlock.AGE, 0)
            else -> return
        }
        val seed = block.asItem()
        val drop = drops.firstOrNull { it.item.`is`(seed) } ?: return
        if (!level.getBlockState(pos).isAir || !young.canSurvive(level, pos)) return
        drop.item.shrink(1)
        if (drop.item.isEmpty) drops.remove(drop)
        level.setBlock(pos, young, Block.UPDATE_ALL)
    }

    // ---- Crafts ----

    fun onCrafted(event: PlayerEvent.ItemCraftedEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val stack = event.crafting
        if (stack.isEmpty) return
        val grid = event.inventory
        // Dyeing it, mending two into one, a banner on a shield: the thing was already made, and tempered
        if ((0 until grid.containerSize).any { grid.getItem(it).`is`(stack.item) }) return
        when {
            stack.isDamageableItem -> {
                Skills.gain(player, Skill.SMITHING, (4.0 + stack.maxDamage / 60.0).coerceAtMost(30.0))
                temper(player, stack)
            }
            stack.has(DataComponents.FOOD) || stack.`is`(Items.CAKE) -> if (!unpacked(grid, stack)) Skills.gain(player, Skill.COOKING, 2.0 * stack.count)
        }
    }

    /**
     * Food taken back out of what it was packed into: dried kelp from its block, honey from a honey block, a
     * crate of carrots emptied. It could go round and round, and none of it is cooking.
     */
    private fun unpacked(grid: Container, result: ItemStack): Boolean {
        if (result.`is`(Items.DRIED_KELP) || result.`is`(Items.HONEY_BOTTLE)) return true
        val filled = (0 until grid.containerSize).count { !grid.getItem(it).isEmpty }
        return filled == 1 && result.count > 1
    }

    /**
     * Gear a skilled smith makes lasts longer. Taken by hand, [crafted] is the item itself; shift-clicked,
     * it's a copy and the item is already in the inventory, so the first untouched match there gets it.
     */
    private fun temper(player: ServerPlayer, crafted: ItemStack) {
        val bonus = Talents.build(player)[Bonus.CRAFT_DURABILITY]
        if (bonus <= 0.0) return
        val longer = (crafted.maxDamage * (1.0 + bonus)).roundToInt()
        if (longer <= crafted.maxDamage) return
        val untouched = crafted.copy()
        crafted.set(DataComponents.MAX_DAMAGE, longer)
        if (player.containerMenu.carried === crafted) return
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack !== crafted && ItemStack.isSameItemSameComponents(stack, untouched)) {
                stack.set(DataComponents.MAX_DAMAGE, longer)
                return
            }
        }
    }

    fun onSmelted(event: PlayerEvent.ItemSmeltedEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val stack = event.smelting
        val count = event.amountRemoved
        if (stack.isEmpty || count <= 0) return
        val build = Talents.build(player)
        val food = stack.has(DataComponents.FOOD)
        when {
            food -> Skills.gain(player, Skill.COOKING, 1.5 * count)
            stack.`is`(Tags.Items.INGOTS) || stack.`is`(Items.NETHERITE_SCRAP) -> Skills.gain(player, Skill.SMITHING, 1.0 * count)
            else -> Skills.gain(player, Skill.SMITHING, 0.2 * count)
        }
        val extra = if (food) build[Bonus.COOK_EXTRA] else if (stack.`is`(Tags.Items.INGOTS)) build[Bonus.SMELT_EXTRA] else 0.0
        if (extra <= 0.0) return
        var more = 0
        repeat(count) { if (chance(player, extra)) more++ }
        if (more > 0) player.inventory.placeItemBackInInventory(stack.copyWithCount(more), Prediction.SERVER_ONLY)
    }

    /**
     * A potion taken from a brewing stand. Taking the same potion out and back in would count again, so
     * the XP comes from a store that fills about as fast as two stands brew.
     */
    fun onBrewed(event: PlayerBrewedPotionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val contents = event.stack.get(DataComponents.POTION_CONTENTS) ?: return
        if (contents.potion().map { it.`is`(Potions.WATER) }.orElse(true)) return
        val buffer = player.getData(ModAttachments.SKILL_BUFFER)
        val now = player.level().gameTime
        val refilled = ((now - buffer.brewsAt) / BREW_REFILL).toInt()
        if (refilled > 0) {
            buffer.brews = (buffer.brews + refilled).coerceAtMost(BREW_STORE)
            buffer.brewsAt += refilled * BREW_REFILL
        }
        if (buffer.brews >= BREW_STORE) buffer.brewsAt = now
        if (buffer.brews <= 0) return
        buffer.brews--
        Skills.gain(player, Skill.ALCHEMY, 6.0)
    }

    fun onEnchanted(event: PlayerEnchantItemEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val levels = event.enchantments.sumOf { it.level() }
        Skills.gain(player, Skill.ENCHANTING, 6.0 + 3.0 * levels)
        if (chance(player, Talents.build(player)[Bonus.ENCHANT_REFUND])) player.giveExperienceLevels(1)
    }

    /** The anvil asks fewer levels of a skilled smith. Both sides work this out, so the price shown is right. */
    fun onAnvilUpdate(event: AnvilUpdateEvent) {
        val player = event.player ?: return
        if (event.output.isEmpty || event.xpCost <= 1) return
        val cheaper = Talents.build(player)[Bonus.ANVIL_COST]
        if (cheaper == 0.0) return
        event.xpCost = (event.xpCost * (1.0 + cheaper)).roundToInt().coerceAtLeast(1)
    }

    fun onAnvilCrafted(event: AnvilCraftEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return
        val right = event.right
        when {
            right.`is`(Items.ENCHANTED_BOOK) -> Skills.gain(player, Skill.ENCHANTING, 6.0)
            event.output.isDamageableItem && !right.isEmpty -> Skills.gain(player, Skill.SMITHING, 6.0)
        }
    }

    // ---- Fishing, trade, beasts ----

    fun onFished(event: ItemFishedEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val hook = event.hookEntity
        val level = player.level()
        Skills.gain(player, Skill.FISHING, 8.0)
        val build = Talents.build(player)
        event.drops.firstOrNull()?.let { if (chance(player, build[Bonus.FISH_EXTRA])) fling(level, hook, player, it.copy()) }
        if (chance(player, build[Bonus.FISH_TREASURE])) {
            val params = LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, hook.position())
                .withParameter(LootContextParams.TOOL, player.mainHandItem)
                .withParameter(LootContextParams.THIS_ENTITY, hook)
                .withParameter(LootContextParams.ATTACKING_ENTITY, player)
                .withLuck(player.luck)
                .create(LootContextParamSets.FISHING)
            val table = level.server.reloadableRegistries().getLootTable(BuiltInLootTables.FISHING_TREASURE)
            for (stack in table.getRandomItems(params)) fling(level, hook, player, stack)
            Skills.gain(player, Skill.FISHING, 6.0)
        }
    }

    /** Throws [stack] from the hook to the player, the way a catch comes in. */
    private fun fling(level: ServerLevel, hook: FishingHook, player: Player, stack: ItemStack) {
        val item = ItemEntity(level, hook.x, hook.y, hook.z, stack)
        val dx = player.x - hook.x
        val dy = player.y - hook.y
        val dz = player.z - hook.z
        item.setDeltaMovement(dx * 0.1, dy * 0.1 + sqrt(sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1)
        level.addFreshEntity(item)
    }

    fun onTrade(event: TradeWithVillagerEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val offer = event.merchantOffer
        Skills.gain(player, Skill.TRADE, 3.0 + offer.xp * 0.5)
        if (chance(player, Talents.build(player)[Bonus.TRADE_REFUND])) {
            val price = offer.costA
            if (!price.isEmpty) player.inventory.placeItemBackInInventory(price.copy(), Prediction.SERVER_ONLY)
        }
    }

    fun onBreed(event: BabyEntitySpawnEvent) {
        val player = event.causedByPlayer as? ServerPlayer ?: return
        Skills.gain(player, Skill.FARMING, 4.0)
        // Twins: a second baby beside the one vanilla brings
        if (event.child == null || !chance(player, Talents.build(player)[Bonus.TWINS])) return
        val a = event.parentA as? AgeableMob ?: return
        val b = event.parentB as? AgeableMob ?: return
        val level = a.level() as? ServerLevel ?: return
        val twin = a.getBreedOffspring(level, b) ?: return
        twin.setBaby(true)
        twin.snapTo(a.x, a.y, a.z, 0f, 0f)
        level.addFreshEntityWithPassengers(twin)
    }

    fun onTame(event: AnimalTameEvent) {
        val player = event.tamer as? ServerPlayer ?: return
        Skills.gain(player, Skill.FARMING, 10.0)
    }

    // ---- Kills ----

    /** Guild XP for a kill: a little for monsters, a lot for bosses. Skill XP for the killing blow's weapon. */
    fun onKill(player: ServerPlayer, victim: LivingEntity, source: DamageSource) {
        Skills.onKill(player, victim, source)
        val boss = victim.typeHolder().`is`(Tags.EntityTypes.BOSSES) || victim.hasData(ModAttachments.LAIR_BOSS)
        val xp = when {
            boss -> (victim.maxHealth / 2f).roundToInt().coerceIn(20, 400)
            victim is Enemy -> (1f + victim.maxHealth / 10f).roundToInt().coerceAtMost(40)
            else -> 0
        }
        if (xp > 0) Progression.addXp(player, xp)
    }

    private const val FELL_LIMIT = 96
    /** How far up a bare trunk [natural] looks for its leaves. */
    private const val TRUNK_CLIMB = 32
    private const val VEIN_LIMIT = 24
    /** Potions' worth of alchemy XP that can be stored, and ticks for each to come back. */
    private const val BREW_STORE = 6
    private const val BREW_REFILL = 100L
}
