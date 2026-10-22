package io.github.vaspyyy.guildmark.cooking.home

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.market.MenuBoardBlockEntity
import io.github.vaspyyy.guildmark.cooking.market.MenuBoards
import io.github.vaspyyy.guildmark.cooking.vessel.SpitBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StationProps
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StewpotMode
import io.github.vaspyyy.guildmark.cooking.vessel.Vessel
import io.github.vaspyyy.guildmark.cooking.vessel.VesselBlockEntity
import io.github.vaspyyy.guildmark.homestead.Homestead
import io.github.vaspyyy.guildmark.homestead.HomesteadEvent
import io.github.vaspyyy.guildmark.homestead.Homesteads
import io.github.vaspyyy.guildmark.season.SeasonCrops
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.CampfireBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.BlockDropsEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * The homestead's kitchen (living-world/cook/design.md 6.7): the tier's pot, board, plate, oven and larder fitted into
 * the house the builders raised, and free fire for them while the homestead's people are home. Part K's.
 *
 * **The fit-out.** The kitchen is not drawn into the homestead's plan, which stays a function of the tier so that no old
 * homestead changes. When the builders finish a tier ([HomesteadEvent.TierChanged]), and for a homestead raised before
 * Hearthcraft the first time one of its people comes near ([scan]), each item of the tier's kitchen ([KitchenSets]) is set
 * in the cell the plan's own block stood in, one cell at a time with a puff of dust and the block's own sound: a
 * [Cell.PLACED] cell, or one that is already as it should be, or one left alone because it is no longer the plan's block or
 * holds something ([Cell.KEPT]), or one that must wait (its chunk is away, or someone stands in it). Nothing of the owner's
 * is ever replaced, no cell the plan did not draw is ever used, nothing is charged to the world, and the verdict on each item
 * is kept ([HomeKitchenData]) so that a kitchen is fitted once.
 *
 * **Free fire.** [freeAt] answers Part B's once-a-second question: a station on a homestead's land burns no fuel while one
 * of its people is on the land or was within the last 30 seconds, by a clock of this object's own. The homestead's people
 * also light the hearth pot (an empty one, which they would otherwise find cold) while they are home, and it goes out a while
 * after the last of them leaves, as the campfire the pot took the place of did ([household]).
 *
 * **The pantry.** The Menu board fitted by the farmhouse door lists what the homestead's larders hold ([pantryLines], worked
 * out in [HomePantry]): written when it is set, and every hundred ticks after while a player is by and the pantry reads
 * differently, with a scratch of chalk ([chalkBoard]).
 *
 * Everything here asks the switch (`homeKitchens`, and homesteads) first: off, nothing is fitted, lit or counted, and nothing
 * is written. Nothing here walks the world; the loaded land is looked at only where a record says there is something to see.
 */
object HomesteadKitchens {
    /** Ticks from a tier's last block to its kitchen's first station, which the crew's cheer has filled. */
    private const val SETTLE = 60L

    /** Ticks between one cell of the fit-out and the next within an item, and between one item and the next. */
    private const val BEAT = 6L
    private const val ITEM_GAP = 16L

    /** Ticks between one look at a homestead for a kitchen still to be fitted and the next, staggered by id. */
    private const val SCAN = 100L

    /** How near one of a homestead's people must be for its kitchen to be fitted, blocks. */
    private const val NEAR = 64.0

    /** Ticks between the household's rounds, staggered by id, and how near a player must be for a round to be made, blocks. */
    private const val ROUND = 40L
    private const val SIGHT = 128.0

    /** How long a fire stays free after the last of the people left the land, ticks: thirty seconds. */
    private const val GRACE = 600L

    /** Part K's registration: the herb's seasons, the fit-out's events and scan, the household's rounds and the readout of `cook perf`. */
    fun register() {
        // The herb joins the seasons' pacing at common setup, when its block and sprig exist, on both sides, so the sprig's
        // tooltip says what the server does. Common setup is on the mod bus, which this is called too early to be handed.
        val modBus = try {
            ModLoadingContext.get().activeContainer.eventBus
        } catch (e: RuntimeException) {
            null
        }
        if (modBus != null) {
            modBus.addListener(FMLCommonSetupEvent::class.java) { event -> event.enqueueWork(Runnable { seasonHerb() }) }
        } else {
            Guildmark.LOGGER.warn("The herb waits for a world to join the seasons")
        }
        // The same, once more, should common setup have been missed (it does nothing a second time)
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent::class.java) { _ -> seasonHerb() }

        val bus = NeoForge.EVENT_BUS
        bus.addListener(HomesteadEvent.TierChanged::class.java) { onTier(it) }
        bus.addListener(HomesteadEvent.Removed::class.java) { onRemoved(it) }
        bus.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        // After the land has cleared what its plan's cells drop, so that a station of the owner's gives itself back
        bus.addListener(EventPriority.LOWEST, BlockDropsEvent::class.java) { onDrops(it) }
        Cooking.onForget {
            jobs.clear()
            finds.clear()
            faults.clear()
            seen.clear()
            checked.clear()
            fitted = 0L
            kept = 0L
            notFound = 0L
            asked = 0L
            granted = 0L
        }
        CookStats.readout("homestead") {
            "$fitted items fitted, $kept kept, $notFound not found; ${jobs.size} fit-outs waiting; fire asked $asked times, free $granted"
        }
        HomeCommand.init()
    }

    private var herbSeasoned = false

    /** The herb in [SeasonCrops]: best in spring and summer, slow in winter unless roofed or lit, as the vanilla crops are paced. */
    @Synchronized
    private fun seasonHerb() {
        if (herbSeasoned) return
        herbSeasoned = true
        SeasonCrops.add(CookRegistry.HERB.get(), SeasonCrops.Profile(1.15f, 1.10f, 0.80f, 0.40f), CookRegistry.HERB_SPRIG.get())
    }

    // ---- What is on ----

    /** Whether homestead kitchens are on: Hearthcraft, its `homeKitchens` rule, and homesteads themselves. */
    internal fun on(): Boolean = Cooking.homeKitchens() && Homesteads.enabled()

    // ---- What happened, for `cook perf` and `cook homestead` ----

    private var fitted = 0L
    private var kept = 0L
    private var notFound = 0L
    private var asked = 0L
    private var granted = 0L

    // ---- The fit-out ----

    /** How one cell of an item stands when it is looked at: it may be set now, it has just been, it is a station of the item already, it is left alone, or it must wait. */
    internal enum class Cell { FIT, PLACED, ALREADY, KEPT, WAIT }

    /** What a look at one cell found, and in words why it is left alone or waits. */
    internal class Verdict(val cell: Cell, val why: String = "")

    /** One homestead's fit-out in hand: the item it is at, the cell it will try next, and what it has done so far. */
    private class Job(val id: Int, var readyAt: Long) {
        var item: Item? = null
        var cursor = 0
        val cells = ArrayList<BlockPos>()
        var newly = 0
        var kept = 0
        var why = ""

        /** The items of this job that had a station set in them (what [raise] counts). */
        var done = 0

        fun begin(next: Item) {
            item = next
            cursor = 0
            cells.clear()
            newly = 0
            kept = 0
            why = ""
        }
    }

    private val jobs = ArrayList<Job>()

    /** Each tier's items as the plan has them, found once for a homestead (a tier's plan comes out the same every time). Keyed by id times four plus the tier. */
    private val finds = HashMap<Long, List<Item>>()

    /** The items [KitchenSets] finds in tier [tier] of [home]'s plan. */
    internal fun itemsOf(level: ServerLevel, home: Homestead, tier: Int): List<Item> =
        finds.getOrPut(home.id * 4L + tier) {
            val data = Homesteads.get(level)
            KitchenSets.of(tier, data.plan(home, tier), data.plan(home, 1), home.layout.places(1).fields.firstOrNull())
        }

    /** A tier is finished: its kitchen is fitted, a little after the crew's cheer. */
    private fun onTier(event: HomesteadEvent.TierChanged) {
        if (!on() || event.to <= event.from) return
        queue(event.homestead.id, event.level.gameTime + SETTLE)
    }

    /** A charter given up: what was fitted stays, as everything the builders raised does, and the verdicts and clocks are forgotten. */
    private fun onRemoved(event: HomesteadEvent.Removed) {
        val id = event.homestead.id
        jobs.removeIf { it.id == id }
        for (tier in 1..3) finds.remove(id * 4L + tier)
        seen.remove(id)
        checked.remove(id)
        if (on()) HomeKitchenData.get(event.level).forget(id)
    }

    private fun queue(id: Int, at: Long) {
        if (jobs.none { it.id == id }) jobs.add(Job(id, at))
    }

    private fun tick(server: MinecraftServer) {
        if (!on()) return
        val level = server.overworld()
        val now = level.gameTime
        runJob(level, now)
        val homes = Homesteads.get(level).homesteads
        if (homes.isEmpty()) return
        for (home in homes) {
            if (home.tier < 1) continue
            if (Math.floorMod(now + home.id * 13L, ROUND) == 0L) attempt("household", home.id) { household(level, home) }
            if (Math.floorMod(now + home.id * 7L, SCAN) == 0L) {
                attempt("scan", home.id) { scan(level, home, now) }
                attempt("board", home.id) { chalkBoard(level, home) }
            }
        }
    }

    /** One cell of the first fit-out that is due. */
    private fun runJob(level: ServerLevel, now: Long) {
        if (jobs.isEmpty()) return
        val job = jobs.firstOrNull { it.readyAt <= now } ?: return
        val home = Homesteads.get(level).byId(job.id)
        if (home == null || home.tier < 1) {
            jobs.remove(job)
            return
        }
        val more = try {
            beat(level, home, job)
        } catch (e: RuntimeException) {
            fault("fit-out", job.id, e)
            false
        }
        if (more) job.readyAt = now + (if (job.item == null) ITEM_GAP else BEAT) else jobs.remove(job)
    }

    /** What has been reported as failing already, so that a fault is said once, with its trace, and the tick goes on without the part that failed. */
    private val faults = HashSet<String>()

    private fun fault(what: String, id: Int, e: RuntimeException) {
        if (faults.add("$what $id")) Guildmark.LOGGER.warn("The homestead kitchen's {} for homestead #{} failed and is skipped", what, id, e)
    }

    /** Runs [work], one part of a tick: a fault in it is logged (once) and never stops the server's tick or the other parts. */
    private inline fun attempt(what: String, id: Int, work: () -> Unit) {
        try {
            work()
        } catch (e: RuntimeException) {
            fault(what, id, e)
        }
    }

    /**
     * A homestead raised before Hearthcraft, or one whose tier was finished while its people were away: when one of them
     * comes within [NEAR] blocks of its post, its kitchen is put in hand. A homestead with a verdict on every item has none to see to.
     */
    private fun scan(level: ServerLevel, home: Homestead, now: Long) {
        if (!pending(home, HomeKitchenData.get(level)) || jobs.any { it.id == home.id }) return
        val post = home.post
        val limit = NEAR * NEAR
        if (level.players().none { !it.isSpectator && it.distanceToSqr(post.x + 0.5, post.y + 0.5, post.z + 0.5) <= limit && Homesteads.belongsTo(level, home, it) }) return
        queue(home.id, now)
    }

    /** Whether any item of the tiers [home] has reached is still without a verdict. */
    private fun pending(home: Homestead, data: HomeKitchenData): Boolean {
        val rec = data.home(home.id)
        for (tier in 1..minOf(home.tier, 3)) for (key in KitchenSets.keys(tier)) if (rec?.items?.containsKey(key) != true) return true
        return false
    }

    /** The first item, of the tiers [home] has reached, that has no verdict yet. */
    private fun nextItem(level: ServerLevel, home: Homestead, data: HomeKitchenData): Item? {
        val rec = data.home(home.id)
        for (tier in 1..minOf(home.tier, 3)) {
            for (item in itemsOf(level, home, tier)) if (rec?.items?.containsKey(item.key) != true) return item
        }
        return null
    }

    /**
     * Fits [home]'s kitchen now, every item of the tiers it has reached that has no verdict yet, all at once (the fit-out and
     * `cook homestead kit` go cell by cell; this does it in one go). The number of items that had a station set in them.
     */
    fun raise(level: ServerLevel, home: Homestead): Int {
        if (!on()) return 0
        val job = Job(home.id, level.gameTime)
        var guard = 0
        while (guard++ < 400 && beat(level, home, job)) {
            // a cell a time, until nothing is left to see to
        }
        return job.done
    }

    /**
     * Sets the next cell of [job]'s homestead, starting the next item when the last is settled; whether there is more to do. Items
     * that cannot be fitted (the plan has no cell for them, or the owner has changed it) are settled on the way and cost no beat.
     */
    private fun beat(level: ServerLevel, home: Homestead, job: Job): Boolean {
        val data = HomeKitchenData.get(level)
        var guard = 0
        while (guard++ < 64) {
            var item = job.item
            if (item == null) {
                item = nextItem(level, home, data) ?: return false
                job.begin(item)
                if (!item.found) {
                    data.settle(home.id, item.key, HomeKitchenData.Entry(HomeKitchenData.Status.NOT_FOUND, emptyList(), item.why))
                    notFound++
                    job.item = null
                    continue
                }
            }
            while (job.cursor < item.picks.size && job.cells.size < item.want) {
                val pick = item.picks[job.cursor]
                val pos = home.frame.world(pick.x, pick.y, pick.z)
                val verdict = fitCell(level, home, item, pick, pos)
                when (verdict.cell) {
                    Cell.WAIT, Cell.FIT -> {
                        // Not yet: its chunk is away or someone stands in it (fitCell never answers FIT: it sets the cell that fits).
                        // No verdict is written, and a scan comes back to it.
                        job.item = null
                        return false
                    }
                    Cell.PLACED -> {
                        job.cursor++
                        job.cells.add(pos)
                        job.newly++
                        return true
                    }
                    Cell.ALREADY -> {
                        job.cursor++
                        job.cells.add(pos)
                    }
                    Cell.KEPT -> {
                        job.cursor++
                        job.kept++
                        if (job.why.isEmpty()) job.why = verdict.why
                    }
                }
            }
            settle(level, home, data, item, job)
            job.item = null
            // An item that set something shows it a moment before the next begins
            if (job.newly > 0) return true
        }
        return false
    }

    /** Writes the verdict on [item] from what [job] did, and tells the owner of a station set. */
    private fun settle(level: ServerLevel, home: Homestead, data: HomeKitchenData, item: Item, job: Job) {
        if (job.cells.isNotEmpty()) {
            val note = if (job.kept > 0) "${job.kept} of its cells were left as the owner has them" else item.why
            data.settle(home.id, item.key, HomeKitchenData.Entry(HomeKitchenData.Status.DONE, job.cells.toList(), note))
        } else {
            kept++
            data.settle(home.id, item.key, HomeKitchenData.Entry(HomeKitchenData.Status.KEPT, emptyList(), job.why.ifEmpty { "its cell was left as the owner has it" }))
        }
        if (job.newly <= 0) return
        fitted++
        job.done++
        val owner = level.getPlayerByUUID(home.owner) as? ServerPlayer ?: return
        if (!home.covers(owner.blockX, owner.blockZ)) return
        owner.sendOverlayMessage(Component.translatable(messageKey(item, job.newly), job.newly))
    }

    /** The action-bar line that says [item] has been fitted, [newly] of its cells set (written out whole so that the language check sees each). */
    private fun messageKey(item: Item, newly: Int): String = when (item.thing) {
        Thing.POT -> if (item.key.startsWith("m.")) "message.guildmark.cook.home.fit.pot.kitchen" else "message.guildmark.cook.home.fit.pot"
        Thing.OVEN -> "message.guildmark.cook.home.fit.oven"
        Thing.HOB -> "message.guildmark.cook.home.fit.hob"
        Thing.BOARD -> "message.guildmark.cook.home.fit.board"
        Thing.PLATE -> if (newly == 1) "message.guildmark.cook.home.fit.plate" else "message.guildmark.cook.home.fit.plates"
        Thing.LARDER -> if (item.why.isNotEmpty()) "message.guildmark.cook.home.fit.larder.slab" else "message.guildmark.cook.home.fit.larder"
        Thing.MENU -> "message.guildmark.cook.home.fit.menu"
        Thing.SPIT -> "message.guildmark.cook.home.fit.spit"
        Thing.HERB -> if (newly == 1) "message.guildmark.cook.home.fit.herbs.one" else "message.guildmark.cook.home.fit.herbs"
    }

    /** The block a station of [thing] is. */
    private fun stationBlock(thing: Thing): Block = when (thing) {
        Thing.POT -> CookRegistry.STEWPOT.get()
        Thing.OVEN -> CookRegistry.BAKE_OVEN.get()
        Thing.HOB -> CookRegistry.HOB.get()
        Thing.BOARD -> CookRegistry.PREP_BOARD.get()
        Thing.PLATE -> CookRegistry.PLATE.get()
        Thing.LARDER -> CookRegistry.LARDER.get()
        Thing.MENU -> CookRegistry.MENU_BOARD.get()
        Thing.SPIT -> CookRegistry.SPIT.get()
        Thing.HERB -> CookRegistry.HERB.get()
    }

    /** The state a station of [thing] is set in at [pick], turned to the world as the builders' blocks are. */
    private fun stateOf(home: Homestead, thing: Thing, pick: Pick): BlockState {
        if (thing == Thing.HERB) return CookRegistry.HERB.get().defaultBlockState().setValue(HerbBlock.AGE, HerbBlock.PICKED_AGE)
        val m = thing.m ?: return stationBlock(thing).defaultBlockState()
        return home.cityStyle.state(B(m, pick.dir), 0).rotate(home.frame.rotation)
    }

    /** Looks at one cell of [item] and sets the station in it if it may be (see [look]); what became of the cell. */
    private fun fitCell(level: ServerLevel, home: Homestead, item: Item, pick: Pick, pos: BlockPos): Verdict {
        val seen = look(level, home, item, pick, pos)
        if (seen.cell != Cell.FIT) return seen
        val state = stateOf(home, item.thing, pick)
        level.setBlock(pos, state, Block.UPDATE_ALL)
        dressed(level, home, pos)
        show(level, pos, state, item.thing)
        return Verdict(Cell.PLACED)
    }

    /**
     * How one cell of [item] stands, changing nothing: it may be set if it is the plan's own block (or what the plan drew on a
     * table, or nothing where the plan drew nothing), holding nothing, with nobody standing in it, and its chunk is loaded. A
     * station of the item that is there already counts as set.
     */
    internal fun look(level: ServerLevel, home: Homestead, item: Item, pick: Pick, pos: BlockPos): Verdict {
        if (!level.isLoaded(pos)) return Verdict(Cell.WAIT, "its chunk is not loaded")
        val thing = item.thing
        val here = level.getBlockState(pos)
        if (here.block === stationBlock(thing)) return Verdict(Cell.ALREADY)
        val data = Homesteads.get(level)
        val plan = data.plan(home, pick.tier)
        val style = home.cityStyle
        when (pick.take) {
            Take.BLOCK -> {
                if (here.block !== style.state(plan.steps[pick.step].b, 0).block) return Verdict(Cell.KEPT, "its cell is not the plan's block any more")
                if (holds(level, pos)) return Verdict(Cell.KEPT, "its cell holds something")
            }
            Take.TOP -> {
                if (level.getBlockState(pos.below()).block !== style.state(plan.steps[pick.under].b, 0).block) return Verdict(Cell.KEPT, "the table is not the plan's slab any more")
                if (pick.step >= 0) {
                    if (!here.isAir && here.block !== style.state(plan.steps[pick.step].b, 0).block) return Verdict(Cell.KEPT, "something else lies on the table")
                } else if (!here.isAir) {
                    return Verdict(Cell.KEPT, "something lies on the table")
                }
            }
            Take.OPEN -> {
                if (!here.isAir && !(here.canBeReplaced() && here.fluidState.isEmpty)) return Verdict(Cell.KEPT, "its cell is taken")
                val below = pos.below()
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return Verdict(Cell.KEPT, "there is no ground under its cell")
            }
            Take.CROP -> {
                if (level.getBlockState(pos.below()).block !== Blocks.FARMLAND) return Verdict(Cell.KEPT, "there is no farmland under its cell")
                if (!here.isAir && here.block !== style.state(plan.steps[pick.step].b, 0).block) return Verdict(Cell.KEPT, "its cell holds another crop")
            }
        }
        if (!stateOf(home, thing, pick).getCollisionShape(level, pos).isEmpty && crowded(level, pos)) return Verdict(Cell.WAIT, "someone stands in it")
        return Verdict(Cell.FIT)
    }

    /** The blocks the fit-out sets as stations (the herb is a crop, which the land lets drop its own sprigs). */
    private fun isStation(block: Block): Boolean =
        block === CookRegistry.STEWPOT.get() || block === CookRegistry.BAKE_OVEN.get() || block === CookRegistry.HOB.get() ||
            block === CookRegistry.PREP_BOARD.get() || block === CookRegistry.PLATE.get() || block === CookRegistry.LARDER.get() ||
            block === CookRegistry.MENU_BOARD.get() || block === CookRegistry.SPIT.get()

    /**
     * A station the fit-out set, broken, gives itself back. The land clears what the cells of its plan drop (the builders' work
     * gives nothing back), and the station on such a cell would give nothing either; it is the owner's, so it is returned, and
     * only at a cell the record says the fit-out filled and only if nothing dropped.
     */
    private fun onDrops(event: BlockDropsEvent) {
        if (!on() || event.drops.isNotEmpty()) return
        val level = event.level as? ServerLevel ?: return
        val block = event.state.block
        if (!isStation(block)) return
        val home = Homesteads.at(level, event.pos) ?: return
        val rec = HomeKitchenData.get(level).home(home.id) ?: return
        if (rec.items.values.none { it.status == HomeKitchenData.Status.DONE && event.pos in it.cells }) return
        val pos = event.pos
        event.drops.add(ItemEntity(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, ItemStack(block)))
    }

    /** Whether the block entity at [pos] holds something (a barrel's goods, a furnace's fuel, what cooks on a campfire). */
    private fun holds(level: ServerLevel, pos: BlockPos): Boolean = when (val be = level.getBlockEntity(pos)) {
        is Container -> !be.isEmpty
        is CampfireBlockEntity -> be.items.any { !it.isEmpty }
        else -> false
    }

    /** Whether someone alive stands in the cell. */
    private fun crowded(level: ServerLevel, pos: BlockPos): Boolean =
        level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos)) { it.isAlive && !it.isSpectator }.isNotEmpty()

    /** What a station just set is given: the owner's name on it, a clockwork jack on the manor's spit, the pantry's lines on the menu board. */
    private fun dressed(level: ServerLevel, home: Homestead, pos: BlockPos) {
        val be = level.getBlockEntity(pos)
        if (be is VesselBlockEntity) be.placer = home.owner
        if (be is SpitBlockEntity) be.jack = true
        if (be is MenuBoardBlockEntity) {
            // The board is the pantry's from the first: what the larders hold now, which is nothing if they are not fitted yet
            val chalk = pantryChalk(level, home) ?: MenuBoards.Chalk(listOf(Component.translatable("gui.guildmark.cook.home.pantry.bare")), listOf(""))
            be.setLines(chalk.lines, chalk.kinds)
        }
    }

    /** The dust of the new block, a puff, and the block's own sound, with the cook's (which is silent until the sounds land). */
    private fun show(level: ServerLevel, pos: BlockPos, state: BlockState, thing: Thing) {
        val x = pos.x + 0.5
        val y = pos.y + 0.5
        val z = pos.z + 0.5
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 10, 0.3, 0.3, 0.3, 0.08)
        level.sendParticles(ParticleTypes.POOF, x, y + 0.1, z, 5, 0.2, 0.15, 0.2, 0.02)
        when (thing) {
            Thing.POT, Thing.OVEN, Thing.HOB, Thing.SPIT -> level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 0.5, z, 1, 0.1, 0.05, 0.1, 0.01)
            Thing.HERB -> level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 4, 0.25, 0.2, 0.25, 0.0)
            else -> {}
        }
        val sound = state.soundType
        level.playSound(null, pos, sound.placeSound, SoundSource.BLOCKS, (sound.volume + 1f) / 2f, sound.pitch * 0.8f)
        level.playSound(null, pos, CookSounds.get("cook.place"), SoundSource.BLOCKS, 0.7f, 0.95f + level.random.nextFloat() * 0.1f)
    }

    // ---- Free fire ----

    /** The game time a person of each homestead was last on its land, by id (this object's own clock, not saved), and when that was last looked into. */
    private val seen = HashMap<Int, Long>()
    private val checked = HashMap<Int, Long>()

    /**
     * Whether one of [home]'s people (the owner or one of their party, in any mode but spectator) is on its land now or was
     * within [GRACE] ticks. The land is looked at once a tick at most, however many stations ask.
     */
    private fun peopleHome(level: ServerLevel, home: Homestead): Boolean {
        val now = level.gameTime
        if (checked[home.id] != now) {
            checked[home.id] = now
            if (level.players().any { !it.isSpectator && home.covers(it.blockX, it.blockZ) && Homesteads.belongsTo(level, home, it) }) seen[home.id] = now
        }
        val since = seen[home.id] ?: return false
        return now - since < GRACE
    }

    /**
     * Whether the fire at [pos] is on a homestead's land while its people are home, so it burns no fuel (Part B's `Fuel.free`
     * asks once a second of a lit station). A homestead with no tier built has no kitchen to be free in.
     */
    fun freeAt(level: ServerLevel, pos: BlockPos): Boolean {
        if (!on()) return false
        val home = Homesteads.at(level, pos) ?: return false
        if (home.tier < 1) return false
        asked++
        val free = peopleHome(level, home)
        if (free) granted++
        return free
    }

    /**
     * The household's round: the clock of who is home brought up to date, and each hearth pot that was fitted seen to. A pot
     * that is cold and empty is lit while its people are home, and one that is lit with nothing in it and no fuel of its own
     * goes out when they have been gone a while, as the campfire it took the place of did. A pot with anything in it, or
     * fuel the owner put in, is the cook's and is left as it is.
     */
    private fun household(level: ServerLevel, home: Homestead) {
        val post = home.post
        if (!level.hasNearbyAlivePlayer(post.x + 0.5, post.y + 0.5, post.z + 0.5, SIGHT)) return
        val here = peopleHome(level, home)
        val rec = HomeKitchenData.get(level).home(home.id) ?: return
        for ((key, entry) in rec.items) {
            if (entry.status != HomeKitchenData.Status.DONE || !key.endsWith(".pot")) continue
            val pos = entry.cells.firstOrNull() ?: continue
            warm(level, pos, here)
        }
    }

    private fun warm(level: ServerLevel, pos: BlockPos, here: Boolean) {
        if (!level.isLoaded(pos)) return
        val pot = level.getBlockEntity(pos) as? StewpotBlockEntity ?: return
        if (pot.mode != StewpotMode.PLAYER) return
        val v = pot.vessel
        if (v.items.isNotEmpty() || v.water > 0 || (v.state != Vessel.State.EMPTY)) return
        if (here) {
            if (v.lit) return
            v.lit = true
            v.dirty = true
            pot.catchUp(level)
            if (v.lit) {
                // The fire catches: a spark, a few flames and a curl of smoke over the pot
                pot.ring(level, "cook.ignite", 0.6f, 1.0f)
                level.sendParticles(ParticleTypes.FLAME, pos.x + 0.5, pos.y + 0.35, pos.z + 0.5, 8, 0.2, 0.1, 0.2, 0.01)
                level.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 0.8, pos.z + 0.5, 3, 0.15, 0.1, 0.15, 0.01)
            }
        } else if (v.lit && v.fuel <= 0.0) {
            pot.catchUp(level)
            if (!v.lit) {
                // The fire dies down: a hiss and a last billow of smoke
                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.3f, 1.0f)
                level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x + 0.5, pos.y + 0.7, pos.z + 0.5, 6, 0.2, 0.15, 0.2, 0.02)
            }
        }
    }

    // ---- For `cook homestead` ----

    /** One item of a homestead's kitchen and what became of it: its verdict (null while it has none), the cells it took, a word on how or why, and the cell to point at. */
    internal class Row(val item: Item, val status: HomeKitchenData.Status?, val cells: List<BlockPos>, val note: String, val where: BlockPos?)

    /** Every item of the tiers [home] has reached, in the order of fitting, with what became of it. */
    internal fun rows(level: ServerLevel, home: Homestead): List<Row> {
        val rec = HomeKitchenData.get(level).home(home.id)
        val out = ArrayList<Row>()
        for (tier in 1..minOf(home.tier, 3)) {
            for (item in itemsOf(level, home, tier)) {
                val entry = rec?.items?.get(item.key)
                val where = entry?.cells?.firstOrNull() ?: item.picks.firstOrNull()?.let { home.frame.world(it.x, it.y, it.z) }
                out.add(Row(item, entry?.status, entry?.cells ?: emptyList(), entry?.note ?: item.why, where))
            }
        }
        return out
    }

    /** What each cell of [item] would do if the kitchen were fitted now, one line each; nothing is placed. */
    internal fun preview(level: ServerLevel, home: Homestead, item: Item): List<String> {
        if (!item.found) return listOf(item.why)
        val out = ArrayList<String>()
        for (pick in item.picks) {
            val pos = home.frame.world(pick.x, pick.y, pick.z)
            val seen = look(level, home, item, pick, pos)
            val what = when (seen.cell) {
                Cell.FIT, Cell.PLACED -> "would be set"
                Cell.ALREADY -> "is a ${item.thing.word} already"
                Cell.KEPT -> "would be left alone: ${seen.why}"
                Cell.WAIT -> "would wait: ${seen.why}"
            }
            out.add("${pos.x} ${pos.y} ${pos.z}: $what")
        }
        return out
    }

    /** Takes back the verdicts of [home] that left an item out (kept, not found), so that the next fitting looks at them again; how many. */
    internal fun retake(level: ServerLevel, home: Homestead): Int {
        val data = HomeKitchenData.get(level)
        val rec = data.home(home.id) ?: return 0
        var taken = 0
        for ((key, entry) in rec.items.entries.toList()) {
            if (entry.status != HomeKitchenData.Status.DONE && data.unsettle(home.id, key)) taken++
        }
        return taken
    }

    /** How the fire at a place stands: the homestead whose land it is on, whether it is free, and the ticks since one of its people was last there (-1: never, since the world was loaded). */
    internal class Fire(val home: Homestead?, val free: Boolean, val since: Long, val grace: Long)

    /** [freeAt]'s answer for the fire at [pos], with the reason, and counting nothing. */
    internal fun fireAt(level: ServerLevel, pos: BlockPos): Fire {
        val home = Homesteads.at(level, pos)
        if (home == null || home.tier < 1) return Fire(home, false, -1L, GRACE)
        val free = on() && peopleHome(level, home)
        val since = seen[home.id]?.let { level.gameTime - it } ?: -1L
        return Fire(home, free, since, GRACE)
    }

    /** How many times the fire has been asked about, and how many times it was free, since the world was loaded. */
    internal fun asks(): Long = asked

    internal fun grants(): Long = granted

    // ---- The pantry ----

    /** How near a player must be for the board to be written up, blocks (the inn's boards are written under the same). */
    private const val SEEN = 48.0

    /**
     * The lines for the Menu board by the farmhouse door at [board]: what the larders fitted in the homestead whose land it
     * stands on hold ([HomePantry]), as they are now. None if there is no homestead there, kitchens are off, or one of its
     * larders is in a chunk that is away.
     */
    fun pantryLines(level: ServerLevel, board: BlockPos): List<Component> {
        val home = Homesteads.at(level, board) ?: return emptyList()
        return pantryChalk(level, home)?.lines ?: emptyList()
    }

    /** The lines and icons of [home]'s board, or null while kitchens are off or a larder is out of reach. */
    internal fun pantryChalk(level: ServerLevel, home: Homestead): MenuBoards.Chalk? {
        if (!on()) return null
        val rec = HomeKitchenData.get(level).home(home.id)
        val cells = ArrayList<BlockPos>()
        if (rec != null) {
            for ((key, entry) in rec.items) if (entry.status == HomeKitchenData.Status.DONE && key.contains(".larder")) cells.addAll(entry.cells)
        }
        return HomePantry.chalk(level, cells)
    }

    /**
     * Every hundred ticks, if a player is by the board and the pantry reads differently from what is chalked, the board is
     * rewritten with a scratch of chalk and a little dust. A board the owner has taken away is not looked for.
     */
    private fun chalkBoard(level: ServerLevel, home: Homestead) {
        val entry = HomeKitchenData.get(level).home(home.id)?.items?.get("f.menu") ?: return
        if (entry.status != HomeKitchenData.Status.DONE) return
        val pos = entry.cells.firstOrNull() ?: return
        if (!level.isLoaded(pos)) return
        val board = level.getBlockEntity(pos) as? MenuBoardBlockEntity ?: return
        if (level.getNearestPlayer(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SEEN, false) == null) return
        val chalk = pantryChalk(level, home) ?: return
        if (board.kept && board.lines == chalk.lines && board.kinds == chalk.kinds) return
        board.setLines(chalk.lines, chalk.kinds)
        scratch(level, pos, board)
    }

    /** The scratch of chalk and a little dust in front of the slate as it is written up (the inn's board does the same). */
    private fun scratch(level: ServerLevel, pos: BlockPos, board: MenuBoardBlockEntity) {
        val front = board.blockState.getValue(StationProps.FACING)
        level.sendParticles(ParticleTypes.WHITE_ASH, pos.x + 0.5 + front.stepX * 0.3, pos.y + 0.7, pos.z + 0.5 + front.stepZ * 0.3, 7, 0.22, 0.14, 0.22, 0.01)
        level.playSound(null, pos, CookSounds.get("cook.chalk"), SoundSource.BLOCKS, 0.8f, 0.95f + level.random.nextFloat() * 0.1f)
    }
}
