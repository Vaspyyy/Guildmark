package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.homestead.Materials
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Chat
import io.github.vaspyyy.guildmark.townsfolk.Climb
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Hear
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Lookout
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Pull
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scened
import io.github.vaspyyy.guildmark.townsfolk.scenes.GoTo
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Now
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.scenes.Wait
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * A works' post and yard (living-world/design-influence.md section 3, "The post and the stakes" and "The yard"): the
 * proposal post at a wanted works, its seals counting the pledges, stamped once it's paid for; and beside a works
 * being built its crate, its heaps (one for each material its bill has, heaped by what's really there against the
 * bill), and a mortar tub where it builds in stone. Each thing goes on the first of its drawing's places for it
 * with room ([WorksDrawing.posts], [WorksDrawing.yard]), the places chosen once and kept ([BuildRec.post],
 * [BuildRec.yard]); what has no room anywhere is done without.
 */
internal object WorksYard {
    /** A yard thing with nowhere to go, kept in its place in [BuildRec.yard] so the rest keep theirs. */
    private val NOWHERE = BlockPos(0, -2048, 0)

    /** What a yard holds, in order: the crate, a heap of each material on the bill, and the tub for stone. */
    private sealed class Thing {
        data object Crate : Thing()
        data class Heap(val stuff: YardStuff) : Thing()
        data object Tub : Thing()
    }

    /** The crier on his way with a works' post (by works id): who, his part, and when he set off. */
    private class Carry(val crier: Townsperson, val aside: Aside, val since: Long)

    private val carrying = HashMap<Int, Carry>()

    /** Works whose post waits for the crier this afternoon (by id), and since when he's been looked for. */
    private val waiting = HashMap<Int, Long>()

    fun reset() {
        carrying.clear()
        waiting.clear()
    }

    // ---- The post ----

    /**
     * A wanted works: its post up, and its seals kept. With nobody near it goes up at once, out of sight. With someone
     * near to see, the crier carries it out after his noon cries (from [CRIED] to [LATE] o'clock), sets it up and
     * knocks it in, and cries what it's for; if he can't be had by [SEEK_MOST] ticks into that (none about, or
     * busy), it goes up with its knock all the same. Before then it waits for him.
     */
    fun wanted(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece) {
        val p = WorksPlans.of(rec)
        if (!loaded(level, p)) return
        val post = rec.post
        if (post == null || !isPost(level, post)) {
            up(level, w, rec, p)
        } else {
            waiting.remove(w.id)
            carrying.remove(w.id)
        }
        seal(level, w, rec)
    }

    /** [w]'s post isn't up: the crier on his way with it, sent for, or it's put up now. */
    private fun up(level: ServerLevel, w: WorksRec, rec: BuildRec, p: WorksPlans.Planned) {
        val now = level.gameTime
        val c = carrying[w.id]
        if (c != null) {
            if (!c.aside.over && c.crier.isAlive && now - c.since < CARRY_MOST) return
            // His part over without the post in (stopped by a player, a fright, his day moving on): it goes up now
            if (!c.aside.over) c.aside.end(c.crier, drop = false)
            carrying.remove(w.id)
            raise(level, w, rec, p, fx = near(level, p))
            return
        }
        if (!near(level, p)) {
            waiting.remove(w.id)
            raise(level, w, rec, p, fx = false)
            return
        }
        val hour = (Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0) % 24.0
        if (hour < CRIED || hour >= LATE) return
        val since = waiting.getOrPut(w.id) { now }
        if (crier(level, w, rec, p)) {
            waiting.remove(w.id)
            return
        }
        if (now - since >= SEEK_MOST) {
            waiting.remove(w.id)
            raise(level, w, rec, p, fx = true)
        }
    }

    /**
     * The crier, if he's free and about (within [SEEK] of the post's place), sent out with [w]'s post: he carries it
     * over on his shoulder, sets it up on its place, knocks it in ([KNOCKS] rounds of STAKE, the post's knock each),
     * and cries what it's wanted for. True if he's on his way.
     */
    private fun crier(level: ServerLevel, w: WorksRec, rec: BuildRec, p: WorksPlans.Planned): Boolean {
        val d = p.drawing
        val f = p.frame
        val spot = d.posts.map { f.world(it.x, d.y0 + it.y, it.z) }.firstOrNull { room(level, it) } ?: return false
        val middle = Vec3.atCenterOf(spot)
        val crier = level.getEntitiesOfClass(Townsperson::class.java, AABB(spot).inflate(SEEK, 32.0, SEEK)) {
            Days.life(it)?.crier == true && sendable(level, it)
        }.minByOrNull { it.distanceToSqr(middle) } ?: return false
        val stand = Places.beside(level, spot, crier.blockPosition()) ?: return false
        val head = Vec3(spot.x + 0.5, spot.y + 0.9, spot.z + 0.5)
        val line = if (w.kind == WorksKind.STALL) null else "crier_works_wanted_${w.kind.id}"
        val aside = Aside(
            listOf(
                Now { it.carried = ItemStack(Items.OAK_FENCE) },
                GoTo({ stand.pos }, Jobs.WALK, 0.6, Activity.CARRY, 1200),
                Turn({ head }),
                SetUp(head) { _ ->
                    val pos = rec.post?.takeIf { isPost(level, it) } ?: raise(level, w, rec, p, fx = false)
                    if (pos != null) dust(level, pos, level.getBlockState(pos), 6)
                },
                Knock(KNOCKS, head) { _, _ -> rec.post?.let { WorksBlocks.play(level, it, WorksBlocks.STAKE) } },
                Now { if (line != null) it.say(line, force = true, hearing = Crier.HEARD, ticks = TalkLines.ticks(line).coerceAtLeast(40) + 20L) },
                Wait({ _, _ -> false }, 60, Activity.NONE) { head },
            ),
            reach = WorksBuild.NEAR, most = CARRY_MOST.toInt(),
        )
        aside.onEnd = { crier.carried = ItemStack.EMPTY }
        send(crier, aside)
        carrying[w.id] = Carry(crier, aside, level.gameTime)
        return true
    }

    /** Gives [p] [aside] to play now, taken from whatever they were at (their own work, a seat), which they go back to after. */
    fun send(p: Townsperson, aside: Aside) {
        if (p.seat != null) {
            p.routine.interrupt()
            p.standUp()
        }
        p.navigation.stop()
        p.director = aside
    }

    /**
     * Whether someone can be sent on a works' errand now (the crier with a post, a baker with the first loaves, a
     * carter to the granary's doors): free as any scene would have them, or at their own work (or the crier at the
     * board's once the cries are over), so long as they're in no danger, talk or climb.
     */
    fun sendable(level: ServerLevel, p: Townsperson): Boolean {
        if (Aside.free(p, seated = true)) return true
        if (!p.isAlive || p.isRemoved || p.director != null || p.isSleeping || p.isPassenger || p.attending != null) return false
        if (p.target != null || p.hurtTime > 0 || p.activity == Activity.FLEE || !p.carried.isEmpty || p.seat != null) return false
        if (Lookout.up(p) || Crier.state(p) != null) return false
        val task = p.routine.task
        if (task is Scened || task is Chat || task is Hear || task is Climb || task is Pull) return false
        val doing = Days.life(p)?.let { Days.stretch(it, level.overworldClockTime).doing } ?: return false
        return doing == Doing.WORK || doing == Doing.CRY
    }

    /** The post set upright on its place from the shoulder (PUT_DOWN, the post in at [SET_AT]): [set] puts it in. */
    private class SetUp(private val at: Vec3, private val set: (Townsperson) -> Unit) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(Activity.PUT_DOWN)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            p.lookAt(at)
            if (t == SET_AT) {
                p.carried = ItemStack.EMPTY
                set(p)
            }
            return t < Activity.PUT_DOWN.ticks
        }
    }

    /** The mallet on the post's head, [rounds] rounds of STAKE: [knocked] hears each knock (its clip's tick [KNOCK_AT]). */
    private class Knock(private val rounds: Int, private val at: Vec3, private val knocked: (Townsperson, Int) -> Unit) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(Activity.STAKE)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            p.lookAt(at)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(at), Aside.TURN))
            if (p.activity != Activity.STAKE && (p.activity.loop || p.actDone)) p.act(Activity.STAKE)
            if (t % Activity.STAKE.ticks == KNOCK_AT) knocked(p, t / Activity.STAKE.ticks)
            return t < rounds * Activity.STAKE.ticks
        }
    }

    /** Marks pledged to [rec]'s works: its seals at once, and the coins into the post's box if [player] pledged at it. */
    fun pledged(level: ServerLevel, rec: BuildRec, player: java.util.UUID) {
        val w = WorksData.get(level).works[rec.id] ?: return
        seal(level, w, rec)
        val pos = rec.post ?: return
        if (!level.isLoaded(pos)) return
        val by = level.server.playerList.getPlayer(player) ?: return
        if (by.level() !== level || by.distanceToSqr(Vec3.atCenterOf(pos)) > AT_POST * AT_POST) return
        val be = level.getBlockEntity(pos) as? WorksPostBlockEntity ?: return
        be.pledged()
        WorksBlocks.play(level, pos, WorksBlocks.PLEDGE)
    }

    /** The works is paid for: its post stamped. */
    fun stamp(level: ServerLevel, rec: BuildRec) {
        val w = WorksData.get(level).works[rec.id] ?: return
        seal(level, w, rec)
    }

    /** The post's seals: a tenth of the price pledged a seal; stamped once it's paid. */
    private fun seal(level: ServerLevel, w: WorksRec, rec: BuildRec) {
        val pos = rec.post ?: return
        if (!level.isLoaded(pos)) return
        val be = level.getBlockEntity(pos) as? WorksPostBlockEntity ?: return
        val seals = if (w.price <= 0) 10 else (w.pledged * 10 / w.price).coerceIn(0, 10)
        be.set(w.id, w.kind.id, seals, w.paid)
    }

    private fun isPost(level: ServerLevel, pos: BlockPos): Boolean = !level.isLoaded(pos) || level.getBlockState(pos).`is`(WorksBlocks.POST.get())

    /** The post knocked in at the first of the drawing's places for it with room. */
    private fun raise(level: ServerLevel, w: WorksRec, rec: BuildRec, p: WorksPlans.Planned, fx: Boolean): BlockPos? {
        val d = p.drawing
        val f = p.frame
        val pos = d.posts.map { f.world(it.x, d.y0 + it.y, it.z) }.firstOrNull { room(level, it) } ?: return null
        val state = WorksBlocks.POST.get().defaultBlockState().setValue(WorksFacingBlock.FACING, f.front)
        level.setBlock(pos, state, Block.UPDATE_ALL)
        if (fx) {
            WorksBlocks.play(level, pos, WorksBlocks.STAKE, 1f, 0.9f)
            dust(level, pos, state, 12)
        }
        rec.post = pos
        WorksBuildData.get(level).changed()
        Works.sited(level, w.id, WorksSiting.siteOf(level, rec))
        return pos
    }

    // ---- The yard ----

    /** [w]'s things, in the order [BuildRec.yard] keeps them. */
    private fun things(w: WorksRec): List<Thing> = buildList {
        add(Thing.Crate)
        for ((i, stuff) in Stuff.PAID.withIndex()) {
            if (w.bill.getOrElse(i) { 0 } <= 0) continue
            YardStuff.of(stuff)?.let { add(Thing.Heap(it)) }
        }
        if (w.bill.getOrElse(Stuff.PAID.indexOf(Stuff.STONE)) { 0 } > 0) add(Thing.Tub)
    }

    /**
     * [w]'s post and yard laid out at [site] (its land loaded): each thing's place chosen the first time, and
     * anything missing put back, one a second with its dust while someone's near to see, all at once otherwise.
     */
    fun lay(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite, capital: CapitalPiece) {
        val near = WorksBuild.near(level, site)
        var told = false
        if (rec.post == null || !isPost(level, rec.post!!)) {
            raise(level, w, rec, site.planned, near)
            told = true
        }
        val things = things(w)
        if (rec.yard.size < things.size) {
            choose(level, rec, site.planned, things)
            told = true
        }
        var placed = 0
        for ((k, thing) in things.withIndex()) {
            val pos = rec.yard.getOrNull(k) ?: continue
            if (pos == NOWHERE || !level.isLoaded(pos)) continue
            val want = stateOf(thing, rec, site.planned, k)
            val here = level.getBlockState(pos)
            if (here.block === want.block) continue
            if (!room(level, pos)) continue
            if (near && placed >= 1) break
            level.setBlock(pos, want, Block.UPDATE_ALL)
            placed++
            if (near) {
                dust(level, pos, want, 10)
                WorksBlocks.play(level, pos, if (thing is Thing.Heap && thing.stuff == YardStuff.STONE) WorksBlocks.STONE_SET else WorksBlocks.BEAM_DOWN, 0.8f)
            }
        }
        if (told) {
            WorksBuildData.get(level).changed()
            Works.sited(level, w.id, WorksSiting.siteOf(level, rec))
        }
    }

    /** Each of [things] given the first of the yard's places with room that none of the others has (or nowhere). */
    private fun choose(level: ServerLevel, rec: BuildRec, p: WorksPlans.Planned, things: List<Thing>) {
        val d = p.drawing
        val f = p.frame
        val pins = d.yard.map { f.world(it.x, d.y0 + it.y, it.z) }
        val used = HashSet<BlockPos>(rec.yard)
        rec.post?.let { used.add(it) }
        while (rec.yard.size < things.size) {
            val pos = pins.firstOrNull { it !in used && room(level, it) }
            if (pos == null) {
                rec.yard.add(NOWHERE)
            } else {
                rec.yard.add(pos)
                used.add(pos)
            }
        }
    }

    /** What thing [k] of the yard is as a block, facing the way its place says. */
    private fun stateOf(thing: Thing, rec: BuildRec, p: WorksPlans.Planned, k: Int): BlockState {
        val d = p.drawing
        val pos = rec.yard[k]
        val pin = d.yard.firstOrNull { p.frame.world(it.x, d.y0 + it.y, it.z) == pos }
        val facing = pin?.let { p.frame.direction(it.facing) } ?: p.frame.front
        val block = when (thing) {
            Thing.Crate -> WorksBlocks.CRATE.get()
            is Thing.Heap -> WorksBlocks.yard(thing.stuff).get()
            Thing.Tub -> WorksBlocks.TUB.get()
        }
        return block.defaultBlockState().setValue(WorksFacingBlock.FACING, facing)
    }

    /** The heaps showing what's in the yard ([w].here, less what's still being carried in from the wagon), and the post's seals. */
    fun sync(level: ServerLevel, w: WorksRec, rec: BuildRec, site: TownWorksSite) {
        seal(level, w, rec)
        for ((k, thing) in things(w).withIndex()) {
            if (thing !is Thing.Heap) continue
            val pos = rec.yard.getOrNull(k) ?: continue
            if (pos == NOWHERE || !level.isLoaded(pos)) continue
            val be = level.getBlockEntity(pos) as? WorksYardBlockEntity ?: continue
            val i = Stuff.PAID.indexOf(thing.stuff.stuff)
            val here = (w.here[i] - Unloads.carrying(w.id, i)).coerceAtLeast(0)
            be.set(here, w.bill[i].coerceAtLeast(1))
        }
    }

    /** The tub at [rec]'s yard, if it has one standing. */
    fun tub(level: ServerLevel, rec: BuildRec): BlockPos? =
        rec.yard.firstOrNull { it != NOWHERE && level.isLoaded(it) && level.getBlockState(it).`is`(WorksBlocks.TUB.get()) }

    /** The post and the yard taken away (the works opened, or taken down), and with [plaque] its plaque too. */
    fun clear(level: ServerLevel, rec: BuildRec, plaque: Boolean = false) {
        val all = ArrayList<BlockPos>()
        rec.post?.let { all.add(it) }
        all.addAll(rec.yard.filter { it != NOWHERE })
        if (plaque) rec.plaque?.let { all.add(it) }
        for (pos in all) {
            if (!level.isLoaded(pos)) continue
            val state = level.getBlockState(pos)
            if (!WorksBlocks.ours(state)) continue
            dust(level, pos, state, 8)
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        }
        rec.post = null
        rec.yard.clear()
        if (plaque) rec.plaque = null
        WorksBuildData.get(level).changed()
    }

    // ---- Materials brought ----

    /**
     * [player] puts [stack] into the yard at [pos] (its crate, a heap, the post): what the works' bill still wants of it
     * taken (Works.bring) and said, true; false (its screen opens instead) if it's no yard's, or its works isn't being
     * built.
     */
    fun bring(level: ServerLevel, player: ServerPlayer, pos: BlockPos, stack: ItemStack): Boolean {
        if (!Works.enabled()) return false
        val rec = WorksBuildData.get(level).recs.values.firstOrNull { !it.removed && (it.post == pos || pos in it.yard) } ?: return false
        val w = WorksData.get(level).works[rec.id]?.takeIf { it.open } ?: return false
        val (stuff, _) = Materials.of(stack) ?: return false
        val units = Works.bring(level, player, w.id, stack)
        val name = Component.translatable("gui.guildmark.homestead.stuff.${stuff.name.lowercase()}")
        if (units <= 0) {
            player.sendSystemMessage(Component.translatable("message.guildmark.works.not_wanted", name, Component.translatable(w.kind.key)).withStyle(ChatFormatting.GRAY))
            return true
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.works.brought", units, name, Component.translatable(w.kind.key)).withStyle(ChatFormatting.GOLD))
        val sound = if (stuff == Stuff.STONE) WorksBlocks.STONE_SET else WorksBlocks.BEAM_DOWN
        WorksBlocks.play(level, pos, sound, 0.7f)
        WorksBuild.site(w.id)?.let { sync(level, w, rec, it) }
        return true
    }

    // ---- Helpers ----

    /** [p]'s lot and yard, as TownWorksSite.box has it. */
    private fun box(p: WorksPlans.Planned) = p.frame.box(p.drawing.lot, p.drawing.y0 + p.drawing.low, p.drawing.y0 + p.drawing.high)

    /** Whether the land under [p]'s lot and yard is loaded. */
    private fun loaded(level: ServerLevel, p: WorksPlans.Planned): Boolean {
        val b = box(p).inflatedBy(Works.YARD, 0, Works.YARD)
        return level.isLoaded(BlockPos(b.minX(), b.minY(), b.minZ())) && level.isLoaded(BlockPos(b.maxX(), b.minY(), b.maxZ())) &&
            level.isLoaded(BlockPos(b.minX(), b.minY(), b.maxZ())) && level.isLoaded(BlockPos(b.maxX(), b.minY(), b.minZ()))
    }

    /** Whether a player is within WorksBuild.NEAR of [p]'s lot. */
    private fun near(level: ServerLevel, p: WorksPlans.Planned): Boolean {
        val c = box(p).center
        return level.players().any { !it.isSpectator && it.distanceToSqr(c.x + 0.5, it.y, c.z + 0.5) < WorksBuild.NEAR * WorksBuild.NEAR }
    }

    /** Whether [pos] has room for a yard thing: nothing there but air or a plant, and solid ground under it. */
    private fun room(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.isLoaded(pos)) return false
        val state = level.getBlockState(pos)
        val clear = state.isAir || WorksBlocks.ours(state) || state.canBeReplaced() && state.fluidState.isEmpty && !state.hasBlockEntity()
        if (!clear) return false
        val under = pos.below()
        return level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)
    }

    private fun dust(level: ServerLevel, pos: BlockPos, state: BlockState, n: Int) {
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, state), pos.x + 0.5, pos.y + 0.4, pos.z + 0.5, n, 0.3, 0.3, 0.3, 0.05)
        level.sendParticles(ParticleTypes.POOF, pos.x + 0.5, pos.y + 0.3, pos.z + 0.5, 3, 0.2, 0.1, 0.2, 0.01)
    }

    /** The clock hours the crier may carry a post out: after his noon cries and lunch, until his work ends. */
    private const val CRIED = 12.9
    private const val LATE = 16.5

    /** How far off the crier is looked for, how long he's looked for, and the longest his part may take. */
    private const val SEEK = 96.0
    private const val SEEK_MOST = 1200L
    private const val CARRY_MOST = 2400L

    /** Rounds of the mallet on the post, the clip's knock in each (homestead_anims' stake: tick 9), and the post's moment in PUT_DOWN. */
    private const val KNOCKS = 3
    private const val KNOCK_AT = 9
    private const val SET_AT = 10

    /** How near the post a player pledges for the coins to go into its box (as they stand at it, with its screen open). */
    private const val AT_POST = 8.0
}
