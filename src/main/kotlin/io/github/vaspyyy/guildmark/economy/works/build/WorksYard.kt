package io.github.vaspyyy.guildmark.economy.works.build

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.homestead.Materials
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

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

    fun reset() {}

    // ---- The post ----

    /** A wanted works: its post up (at once: the crier carries one out when someone's near to see), its seals kept. */
    fun wanted(level: ServerLevel, w: WorksRec, rec: BuildRec, capital: CapitalPiece) {
        val p = WorksPlans.of(rec)
        if (!loaded(level, p)) return
        if (rec.post == null || !isPost(level, rec.post!!)) raise(level, w, rec, p, fx = near(level, p))
        seal(level, w, rec)
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
}
