package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.tales.MemorialBlock
import io.github.vaspyyy.guildmark.tales.MemorialBlockEntity
import io.github.vaspyyy.guildmark.tales.MemorialKind
import io.github.vaspyyy.guildmark.tales.MemorialName
import io.github.vaspyyy.guildmark.tales.MemorialRegistry
import io.github.vaspyyy.guildmark.tales.Memorials
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.VillageSounds
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.talk.VillageCry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The memorial stone at a village's green and the candles on it (living-world/villages/design.md 11.4, 16.1 moment 26).
 *
 * The stone is the tales' [MemorialBlock] (the same block for both, design 20.4), the upright carved one. The village sets it at the
 * edge of its green at the dawn after its first death, where no player sees it go down, its carved face to the green, and puts it
 * back at a dawn after it is lost; its block entity is given the village's key and a copy of the dead list (the list in the village's
 * record is the truth), so using the stone reads the list ([Deaths.reading]).
 *
 * For each death a candle is laid at the next dawn by the one who knew them best: one of their household, else the nearest
 * neighbour. They walk to the stone, kneel with its middle 1.4 blocks ahead (`LAY_CANDLE`: the candle is set at 24 and caught at 26,
 * `village.candle_lay` with it), say a line, bow, and the devout stay on their knees a while (`PRAY`). The stone holds the last four
 * candles, the oldest taken away when a fifth is laid; each burns a week and is gone on the seventh dawn. With no player within
 * [WATCHED] of the stone the candle is set by the record, as the lamps are; nobody is ever made to come out for it.
 */
object Memorial {
    /** A player this near the stone sees the candle laid; farther off it is set by the record. */
    private const val WATCHED = 48.0

    /** The hours a mourner comes (from dawn to noon), and the last at which a candle is set at all (the rest wait for the next dawn). */
    private const val DAWN = 6.0
    private const val NOON = 12.0
    private const val DUSK = 21.0

    /** The days a candle burns: it is gone on the seventh dawn after it was laid. */
    const val BURN_DAYS = 7

    /** How far a mourner may be from the stone. */
    private const val FROM = 96.0

    /** Where the stone stands from the middle of the green: the nearest ring first. */
    private val RINGS = intArrayOf(6, 5, 7, 8, 4)

    /** The ticks of `LAY_CANDLE` at which the candle is set on the stone and caught (V12a). */
    private const val SET = 24
    private const val LIT = 26

    /** How far the stone's middle is from the mourner as they kneel (V12a). */
    private const val KNEEL = 1.4

    /** The candle laying under way at each village, by its key: memory only. */
    private val scenes = HashMap<Long, Laying>()

    /** The tries at a mourner today at each village, so a scene broken off twice is set by the record. */
    private val tries = HashMap<Long, Int>()

    /** The villages whose candles the day's close changed while their stone was not loaded: put right on the stone when it is. */
    private val stale = HashSet<Long>()

    /** At setup: using a village's stone reads its dead. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock::class.java) { use(it) }
    }

    /** At a server stop. */
    fun forget() {
        scenes.clear()
        tries.clear()
        stale.clear()
    }

    /** Whether any candle is being laid. */
    fun any(): Boolean = scenes.isNotEmpty()

    /** Ends every scene (the switch turned off): each mourner back to their day. */
    fun endAll() {
        for (s in scenes.values.toList()) s.finish()
        scenes.clear()
    }

    // ---- The look (every five seconds, from Trouble) ----

    /** [rec]'s stone and candles on town day [day]: the stone set or put back at dawn, the candles due laid, the stone put right. */
    internal fun look(level: ServerLevel, rec: VillageRec, day: Long) {
        val kept = TroubleData.peek(level)?.peek(rec.key)
        if (kept == null && rec.dead.isEmpty()) return
        scenes[rec.key]?.let { if (it.done) scenes.remove(rec.key) else return }
        val hour = Trouble.hour(level)
        val stone = kept?.stone?.takeIf { it != TroubleData.NONE }?.let { BlockPos.of(it) }
        if (stone != null && !level.isLoaded(stone)) return
        val standing = stone != null && level.getBlockState(stone).`is`(MemorialRegistry.MEMORIAL.get())
        if (!standing) {
            // Set at the dawn after the first death, and put back at a dawn after it was lost; out of sight, never in a shelter
            if (hour < DAWN || hour >= NOON || Shelter.of(rec.key) != null) return
            if (rec.dead.isEmpty() && kept?.waiting.isNullOrEmpty()) return
            set(level, rec, stone)
            return
        }
        stone!!
        if (rec.key in stale) {
            stale.remove(rec.key)
            sync(level, rec, stone)
        }
        val due = kept?.waiting?.firstOrNull { it.day < day } ?: return
        if (hour < DAWN || hour >= DUSK || Shelter.of(rec.key) != null) return
        val watched = level.getNearestPlayer(stone.x + 0.5, stone.y + 0.5, stone.z + 0.5, WATCHED, false) != null
        if (!watched) {
            lay(level, rec, stone, due, seen = false, by = null)
            light(level, rec, stone, seen = false)
            return
        }
        // Someone is about: the one who knew them best comes, in the morning hours, if they can
        if (hour >= NOON || (tries[rec.key] ?: 0) >= 2) return
        val p = mourner(level, rec, due, stone) ?: return
        tries[rec.key] = (tries[rec.key] ?: 0) + 1
        Trouble.borrow(p)
        val r = Residents.get(level)[p.resident]
        val scene = Laying(rec.key, stone, due, p, r?.has(Trait.DEVOUT) == true)
        scenes[rec.key] = scene
        p.director = scene
        Trouble.log(level, rec, "${p.name.string} goes to lay a candle for ${due.name}")
    }

    /** The close of [rec]'s town day [day]: the candles laid seven dawns ago have burnt down, and the day's tries begin again. */
    internal fun daily(level: ServerLevel, rec: VillageRec, day: Long) {
        tries.remove(rec.key)
        val data = TroubleData.peek(level) ?: return
        val kept = data.peek(rec.key) ?: return
        if (!kept.candles.removeIf { day - it.day >= BURN_DAYS }) return
        data.setDirty()
        val stone = kept.stone.takeIf { it != TroubleData.NONE }?.let { BlockPos.of(it) } ?: return
        if (level.isLoaded(stone)) sync(level, rec, stone) else stale.add(rec.key)
    }

    // ---- The stone ----

    /** Sets [rec]'s stone at the edge of its green (where [was] stood, if the ground there still serves), out of sight. */
    private fun set(level: ServerLevel, rec: VillageRec, was: BlockPos?) {
        val places = Villages.places(level, rec.key)
        val green = places?.market ?: BlockPos.of(rec.key)
        val spot = was?.takeIf { fits(level, places, rec, it, facingOf(it, green)) } ?: find(level, places, rec, green) ?: return
        val facing = facingOf(spot, green)
        if (!Comings.unseen(level, spot)) return
        level.setBlock(spot, MemorialBlock.state(MemorialKind.STONE, facing), Block.UPDATE_ALL)
        val be = level.getBlockEntity(spot) as? MemorialBlockEntity ?: return
        be.village = rec.key
        be.day = Memorials.today(level, null)
        be.setChanged()
        val data = TroubleData.get(level)
        val kept = data.of(rec.key)
        kept.stone = spot.asLong()
        data.setDirty()
        copy(level, rec, spot)
        sync(level, rec, spot)
        Trouble.log(level, rec, (if (was == null) "the memorial stone set" else "the memorial stone put back") + " at ${spot.toShortString()}")
    }

    /** The way a stone at [spot] faces: to the middle of the green ([green]). */
    private fun facingOf(spot: BlockPos, green: BlockPos): Direction {
        val dx = (green.x - spot.x).toDouble()
        val dz = (green.z - spot.z).toDouble()
        if (dx == 0.0 && dz == 0.0) return Direction.SOUTH
        return Direction.getApproximateNearest(dx, 0.0, dz).takeIf { it.axis.isHorizontal } ?: Direction.SOUTH
    }

    /** A place at the edge of the green for the stone, the rings round its middle ([green]) in turn, each from a side of its own. */
    private fun find(level: ServerLevel, places: VillagePlaces?, rec: VillageRec, green: BlockPos): BlockPos? {
        // Where on the ring to begin is the village's own, so two villages do not set theirs alike (and nothing asks the level's random)
        val start = Math.floorMod(rec.key xor (rec.key ushr 29), 16L).toInt()
        for (r in RINGS) for (k in 0 until 16) {
            val a = (start + k) * Math.PI / 8.0
            val x = green.x + (cos(a) * r).roundToInt()
            val z = green.z + (sin(a) * r).roundToInt()
            for (dy in intArrayOf(0, 1, -1, 2, -2, 3, -3)) {
                val at = BlockPos(x, green.y + dy, z)
                if (!level.isLoaded(at)) break
                if (fits(level, places, rec, at, facingOf(at, green))) return at
            }
        }
        return null
    }

    /**
     * Whether the stone can stand at [at] facing [facing]: open ground on a firm floor that is not a lane or a field, out of every
     * building and clear of doors, the bell, the stalls, the wells and the benches, with a place in front of it to kneel.
     */
    private fun fits(level: ServerLevel, places: VillagePlaces?, rec: VillageRec, at: BlockPos, facing: Direction): Boolean {
        if (!level.isLoaded(at) || !Places.standable(level, at)) return false
        val here = level.getBlockState(at)
        if (!here.isAir && !here.canBeReplaced()) return false
        val floor = level.getBlockState(at.below())
        if (floor.`is`(Blocks.DIRT_PATH) || floor.`is`(Blocks.FARMLAND) || floor.`is`(BlockTags.LEAVES)) return false
        val front = at.relative(facing)
        if (!Places.standable(level, front)) return false
        if (places != null && (places.lodgingAt(at) != null || places.lodgingAt(front) != null)) return false
        val bell = BlockPos.of(rec.key)
        if (at.distSqr(bell) < 9.0) return false
        if (places != null) {
            if (places.wells.any { it.distSqr(at) < 9.0 }) return false
            if (places.benches.any { it.pos.distSqr(at) < 4.0 }) return false
            if (places.stalls.any { s -> s.counter.any { it.distSqr(at) < 9.0 } || s.keeper.pos.distSqr(at) < 4.0 || s.customer.pos.distSqr(at) < 4.0 }) return false
        }
        val cursor = BlockPos.MutableBlockPos()
        for (dx in -2..2) for (dz in -2..2) for (dy in -1..2) {
            cursor.set(at.x + dx, at.y + dy, at.z + dz)
            if (!level.isLoaded(cursor)) return false
            if (level.getBlockState(cursor).`is`(BlockTags.DOORS)) return false
        }
        return true
    }

    /** The village's dead written into the stone at [stone], a copy of its record's (which stays the truth). */
    private fun copy(level: ServerLevel, rec: VillageRec, stone: BlockPos) {
        val be = level.getBlockEntity(stone) as? MemorialBlockEntity ?: return
        be.village = rec.key
        be.names = rec.dead.map { MemorialName(it.name, it.day) }
        be.setChanged()
    }

    /** The stone at [stone] made to show [rec]'s candles as the record has them: as many as burn, lit while any does. */
    private fun sync(level: ServerLevel, rec: VillageRec, stone: BlockPos) {
        val state = level.getBlockState(stone)
        if (!state.`is`(MemorialRegistry.MEMORIAL.get())) return
        val candles = TroubleData.peek(level)?.peek(rec.key)?.candles.orEmpty()
        val n = candles.size.coerceAtMost(MemorialBlock.MAX_CANDLES)
        if (n == 0) {
            if (state.getValue(MemorialBlock.LIT)) Memorials.snuff(level, stone, state)
            val now = level.getBlockState(stone)
            if (now.getValue(MemorialBlock.CANDLES) != 0) level.setBlock(stone, now.setValue(MemorialBlock.CANDLES, 0), Block.UPDATE_ALL)
            return
        }
        if (state.getValue(MemorialBlock.CANDLES) != n) level.setBlock(stone, state.setValue(MemorialBlock.CANDLES, n), Block.UPDATE_ALL)
        Memorials.burn(level, stone, level.getBlockState(stone), burnTicks(level, candles.last().day))
    }

    /** The ticks from now to the seventh dawn after town day [day], when the candle laid on it is gone. */
    private fun burnTicks(level: ServerLevel, day: Long): Long {
        val left = (day + BURN_DAYS - 1 - VillageBoards.day(level)).coerceAtLeast(0L)
        return Memorials.ticksTo(level, Memorials.DAWN) + left * 24000L
    }

    // ---- The candle ----

    /**
     * A candle for [m] is set on [rec]'s stone at [stone] ([by] laid it, or the record did): one more on the ledge, the oldest taken away
     * when there are four, a flame only if the others already burn ([light] catches it). Said where it was [seen].
     */
    private fun lay(level: ServerLevel, rec: VillageRec, stone: BlockPos, m: TroubleData.Mourned, seen: Boolean, by: Townsperson?): Boolean {
        val state = level.getBlockState(stone)
        if (!state.`is`(MemorialRegistry.MEMORIAL.get())) return false
        val data = TroubleData.get(level)
        val kept = data.of(rec.key)
        kept.waiting.removeIf { it.name == m.name && it.day == m.day }
        val full = kept.candles.size >= MemorialBlock.MAX_CANDLES
        kept.candles.add(TroubleData.Candle(m.name, VillageBoards.day(level)))
        while (kept.candles.size > MemorialBlock.MAX_CANDLES) kept.candles.removeAt(0)
        data.setDirty()
        val n = kept.candles.size
        level.setBlock(stone, state.setValue(MemorialBlock.CANDLES, n), Block.UPDATE_ALL)
        val kind = state.getValue(MemorialBlock.KIND)
        val facing = state.getValue(MemorialBlock.FACING)
        val posy = state.getValue(MemorialBlock.POSY)
        if (seen) {
            val tip = MemorialBlock.tip(kind, n - 1, facing, stone, posy)
            VillageSounds.play(level, tip, VillageSounds.CANDLE_LAY)
            // The oldest of four burnt to its end, taken away to make room: a thread of smoke where it stood
            if (full) level.sendParticles(ParticleTypes.SMOKE, tip.x, tip.y, tip.z, 4, 0.03, 0.05, 0.03, 0.004)
        }
        VillageCry.candle(level, rec.key, m.name)
        copy(level, rec, stone)
        Trouble.log(level, rec, "a candle laid for ${m.name}" + (by?.let { " by ${it.name.string}" } ?: " (by the record, nobody near)"))
        return true
    }

    /** The candles of [rec]'s stone at [stone] caught (or kept burning), for a week from the newest; the match and the wick heard if [seen]. */
    private fun light(level: ServerLevel, rec: VillageRec, stone: BlockPos, seen: Boolean) {
        val state = level.getBlockState(stone)
        if (!state.`is`(MemorialRegistry.MEMORIAL.get()) || state.getValue(MemorialBlock.CANDLES) == 0) return
        val newest = TroubleData.peek(level)?.peek(rec.key)?.candles?.lastOrNull() ?: return
        Memorials.burn(level, stone, state, burnTicks(level, newest.day))
        if (seen) Memorials.candleLit(level, stone)
    }

    /**
     * Who lays the candle for [m]: one of their household (the grown first), else the neighbour whose home is nearest theirs, else
     * whoever of the village lives nearest the stone; whose body is about within [FROM] of the stone and free to come.
     */
    private fun mourner(level: ServerLevel, rec: VillageRec, m: TroubleData.Mourned, stone: BlockPos): Townsperson? {
        val people = Residents.get(level).byCentre(BlockPos.of(rec.key)).filter { it.alive && it.id != m.id }
        val places = Villages.places(level, rec.key)
        val theirs = places?.lodgings?.get(m.home)?.box?.center ?: stone
        fun near(r: Resident): Double = places?.lodgings?.get(r.home)?.box?.center?.distSqr(theirs) ?: Double.MAX_VALUE
        val household = if (m.home >= 0) people.filter { it.home == m.home } else emptyList()
        val order = household.sortedBy { if (it.role == TownRole.CHILD) 1 else 0 } +
            people.filter { it.home != m.home || m.home < 0 }.sortedWith(compareBy<Resident> { if (it.role == TownRole.CHILD) 1 else 0 }.thenBy { near(it) })
        val centre = Vec3.atCenterOf(stone)
        for (r in order.take(12)) {
            val p = Comings.body(level, r) ?: continue
            if (!Trouble.free(p) || p.distanceToSqr(centre) > FROM * FROM) continue
            if (!level.isPositionEntityTicking(p.blockPosition())) continue
            return p
        }
        return null
    }

    /** One candle's laying: to the stone, down on a knee and the candle set and caught, a word, a bow, and the devout a prayer. */
    private class Laying(val key: Long, val stone: BlockPos, val m: TroubleData.Mourned, val p: Townsperson, val devout: Boolean) : Director {
        var done = false
        private val walker = Walker(p)
        private val facing: Direction = (p.level().getBlockState(stone).takeIf { it.`is`(MemorialRegistry.MEMORIAL.get()) }
            ?.getValue(MemorialBlock.FACING)) ?: Direction.SOUTH
        private val front: BlockPos = stone.relative(facing)
        private var phase = 0
        private var at = 0
        private var lastTick = -1
        private var set = false
        private var lit = false
        /** The tick the last steps onto the kneel began, or -1 while they walk to the stone. */
        private var stepping = -1

        override fun tick(p: Townsperson) {
            if (done) return
            if (lastTick >= 0 && p.tickCount - lastTick > 5 || p.hurtTime > 0) return finish()
            lastTick = p.tickCount
            val level = p.level() as? ServerLevel ?: return finish()
            if (Shelter.of(key) != null) return finish()
            val middle = Vec3(stone.x + 0.5, stone.y + 0.5, stone.z + 0.5)
            when (phase) {
                0 -> {
                    // To the stone, at a slow walk
                    if (at == 0) {
                        at = p.tickCount.coerceAtLeast(1)
                        walker.go(front, Jobs.WALK, near = 0.7)
                    }
                    val kneel = Vec3(middle.x + facing.stepX * KNEEL, p.y, middle.z + facing.stepZ * KNEEL)
                    if (stepping < 0) {
                        val state = walker.tick()
                        if (state == Walker.State.WALKING && p.tickCount - at < WALK_MOST) return
                        if (p.position().distanceToSqr(kneel) > 2.0 * 2.0) return finish()
                        p.navigation.stop()
                        stepping = p.tickCount
                    }
                    // The last steps onto the kneel, walked, the stone ahead (something in the way: they give it up)
                    val dx = kneel.x - p.x
                    val dz = kneel.z - p.z
                    val off = dx * dx + dz * dz
                    if (off > ON_SPOT * ON_SPOT && p.tickCount - stepping < STEP_MOST) {
                        p.moveControl.setWantedPosition(kneel.x, p.y, kneel.z, Jobs.WALK)
                        p.lookAt(middle)
                        return
                    }
                    if (off > NEAR_ENOUGH * NEAR_ENOUGH) return finish()
                    p.setPos(kneel.x, p.y, kneel.z)
                    p.face(p.yawTo(middle))
                    p.act(Activity.LAY_CANDLE)
                    phase = 1
                    at = p.tickCount
                }
                1 -> {
                    // Down on a knee: the candle set at 24, caught at 26
                    p.face(p.yawTo(middle))
                    val t = p.tickCount - at
                    if (t >= SET && !set) {
                        set = true
                        val rec = Villages.byKey(level, key) ?: return finish()
                        if (!lay(level, rec, stone, m, seen = true, by = p)) return finish()
                    }
                    if (t >= LIT && set && !lit) {
                        lit = true
                        Villages.byKey(level, key)?.let { light(level, it, stone, seen = true) }
                    }
                    if (t < Activity.LAY_CANDLE.ticks && !p.actDone) return
                    // A word for them, and a bow
                    Trouble.say(p, "memorial_lay_${Math.floorMod(m.name.hashCode(), 3) + 1}", hearing = 16.0)
                    p.act(Activity.BOW)
                    phase = 2
                    at = p.tickCount
                }
                2 -> {
                    p.face(p.yawTo(middle))
                    if (p.tickCount - at < Activity.BOW.ticks + 10) return
                    if (!devout) return finish()
                    p.act(Activity.PRAY)
                    phase = 3
                    at = p.tickCount
                }
                else -> {
                    p.face(p.yawTo(middle))
                    if (p.tickCount - at >= PRAY_TICKS) finish()
                }
            }
        }

        fun finish() {
            if (done) return
            done = true
            // A candle set and not yet caught is caught now; one never set waits for the next look
            if (set && !lit) (p.level() as? ServerLevel)?.let { level -> Villages.byKey(level, key)?.let { light(level, it, stone, seen = true) } }
            Trouble.giveBack(p, this)
        }

        companion object {
            const val WALK_MOST = 900
            const val PRAY_TICKS = 200
            /** The last steps onto the kneel: on it within [ON_SPOT], and given up after [STEP_MOST] ticks if not within [NEAR_ENOUGH]. */
            const val ON_SPOT = 0.12
            const val STEP_MOST = 40
            const val NEAR_ENOUGH = 0.25
        }
    }

    // ---- Reading ----

    /** A player using a village's stone (not with a candle or a flame in hand, not crouching) reads its dead. */
    private fun use(event: PlayerInteractEvent.RightClickBlock) {
        val level = event.level as? ServerLevel ?: return
        if (event.hand != InteractionHand.MAIN_HAND || !Trouble.on()) return
        val player = event.entity as? ServerPlayer ?: return
        if (player.isShiftKeyDown) return
        if (!level.getBlockState(event.pos).`is`(MemorialRegistry.MEMORIAL.get())) return
        val stack = event.itemStack
        if (stack.`is`(ItemTags.CANDLES) || stack.`is`(Items.FLINT_AND_STEEL) || stack.`is`(Items.FIRE_CHARGE)) return
        val key = (level.getBlockEntity(event.pos) as? MemorialBlockEntity)?.village ?: return
        val rec = Villages.byKey(level, key) ?: return
        for (line in Deaths.reading(level, rec)) player.sendSystemMessage(line)
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    // ---- For the commands ----

    /** What `village dead` says of [rec]'s stone and candles. */
    fun describe(level: ServerLevel, rec: VillageRec): List<String> {
        val kept = TroubleData.peek(level)?.peek(rec.key)
        val out = ArrayList<String>()
        val stone = kept?.stone?.takeIf { it != TroubleData.NONE }?.let { BlockPos.of(it) }
        out += when {
            stone == null -> "No memorial stone yet: it is set at the edge of the green at the dawn after the first death, where nobody sees it go down."
            !level.isLoaded(stone) -> "The memorial stone is at ${stone.toShortString()} (not loaded now)."
            level.getBlockState(stone).`is`(MemorialRegistry.MEMORIAL.get()) -> "The memorial stone is at ${stone.toShortString()}."
            else -> "The memorial stone at ${stone.toShortString()} is gone: it is put back at the next dawn."
        }
        val candles = kept?.candles.orEmpty()
        if (candles.isNotEmpty()) {
            val today = VillageBoards.day(level)
            out += "Candles burning: " + candles.joinToString(", ") { "${it.name} (gone in ${BURN_DAYS - (today - it.day)} days)" }
        }
        val waiting = kept?.waiting.orEmpty()
        if (waiting.isNotEmpty()) out += "Waiting for a candle at dawn: " + waiting.joinToString(", ") { it.name }
        scenes[rec.key]?.takeIf { !it.done }?.let { out += "${it.p.name.string} is laying a candle for ${it.m.name} now." }
        return out
    }
}
