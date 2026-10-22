package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.talk.Fade
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumours
import io.github.vaspyyy.guildmark.townsfolk.talk.Scope
import io.github.vaspyyy.guildmark.townsfolk.talk.Slight
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Venue
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent

/**
 * The memorials (living-world/stories/design.md 6.4, 14.10), Part F: the stone and the cairn a tale leaves, the block that keeps their
 * names ([MemorialBlock]), what the town does with a memorial (reading it, aging it, lighting candles at it, minding it being broken) and,
 * below, where a tale's memorial is set and how (stage 8 of `Tales.finish`). With `townTaleMemorials` off a memorial is recorded and no
 * block is set; a stone already standing stays and is read and aged as ever.
 */
object Memorials {
    /** The days after which the foot of a memorial is green (13.2.1), and a posy laid on its ledge withers and the wind takes it (0: it stays). */
    const val MOSS_DAYS = 10L
    const val POSY_DAYS = 5L

    /** At setup: the block, its item and entity, and the stone and the cairn in the creative hand. */
    fun register(modBus: IEventBus) {
        MemorialRegistry.BLOCKS.register(modBus)
        MemorialRegistry.ITEMS.register(modBus)
        MemorialRegistry.BLOCK_ENTITIES.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey != ModCreativeTabs.MAIN.key) return@addListener
            event.accept(MemorialRegistry.stack(MemorialKind.STONE))
            event.accept(MemorialRegistry.stack(MemorialKind.CAIRN))
        }
    }

    // ---- Reading ----

    /** [player] reads the inscription of the memorial at [pos]: one chat line (6.4). */
    fun read(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer) {
        val be = level.getBlockEntity(pos) as? MemorialBlockEntity
        val said = MemorialWords.inscription(level, state.getValue(MemorialBlock.KIND), be?.names.orEmpty(), be?.template.orEmpty(), be?.day, be?.lead.orEmpty())
        player.sendSystemMessage(said)
    }

    // ---- Candles ----

    /** A candle from [stack] goes on the ledge of the memorial at [pos], if there is room for one more; it stands lit if the others are. */
    fun addCandle(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer, stack: ItemStack): Boolean {
        val n = state.getValue(MemorialBlock.CANDLES)
        if (n >= MemorialBlock.MAX_CANDLES) return false
        level.setBlock(pos, state.setValue(MemorialBlock.CANDLES, n + 1), Block.UPDATE_ALL)
        if (!player.isCreative) stack.shrink(1)
        level.playSound(null, pos, SoundEvents.CANDLE_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        return true
    }

    /** [player] lights the candles of the memorial at [pos] with [stack] (flint and steel, or a fire charge), if any stand unlit: they burn until the coming dawn. */
    fun kindle(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer, stack: ItemStack, hand: InteractionHand): Boolean {
        if (state.getValue(MemorialBlock.CANDLES) == 0 || state.getValue(MemorialBlock.LIT)) return false
        if (stack.`is`(Items.FIRE_CHARGE)) {
            if (!player.isCreative) stack.shrink(1)
            level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0f, 1.0f)
        } else {
            stack.hurtAndBreak(1, player, hand)
            level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0f, 1.0f)
        }
        burn(level, pos, state, ticksTo(level, DAWN))
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        return true
    }

    /** [player] pinches the flames out with their fingers. */
    fun pinch(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer) {
        snuff(level, pos, state)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
    }

    /** Lights the candles of the memorial at [pos] (as it stands, [state]) for [ticks], and has the block asked when they have burnt down. */
    fun burn(level: ServerLevel, pos: BlockPos, state: BlockState, ticks: Long) {
        val be = level.getBlockEntity(pos) as? MemorialBlockEntity
        if (be != null) {
            be.litUntil = level.gameTime + ticks
            be.setChanged()
        }
        if (!state.getValue(MemorialBlock.LIT)) level.setBlock(pos, state.setValue(MemorialBlock.LIT, true), Block.UPDATE_ALL)
        level.scheduleTick(pos, state.block, ticks.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
    }

    /** The block's scheduled tick: the candles are put out if their time has come, else the block is asked again at it. */
    fun burnt(level: ServerLevel, pos: BlockPos, state: BlockState) {
        if (!state.getValue(MemorialBlock.LIT)) return
        val until = (level.getBlockEntity(pos) as? MemorialBlockEntity)?.litUntil ?: 0L
        val left = until - level.gameTime
        if (left > 0) level.scheduleTick(pos, state.block, left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else snuff(level, pos, state)
    }

    /** Puts the flames of the memorial at [pos] out, a thread of smoke going up from each. */
    fun snuff(level: ServerLevel, pos: BlockPos, state: BlockState) {
        if (!state.getValue(MemorialBlock.LIT)) return
        (level.getBlockEntity(pos) as? MemorialBlockEntity)?.let {
            it.litUntil = 0L
            it.setChanged()
        }
        level.setBlock(pos, state.setValue(MemorialBlock.LIT, false), Block.UPDATE_ALL)
        level.playSound(null, pos, SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 1.0f)
        val kind = state.getValue(MemorialBlock.KIND)
        val facing = state.getValue(MemorialBlock.FACING)
        for (i in 0 until state.getValue(MemorialBlock.CANDLES)) {
            val tip = MemorialBlock.tip(kind, i, facing, pos)
            level.sendParticles(ParticleTypes.SMOKE, tip.x, tip.y, tip.z, 3, 0.02, 0.04, 0.02, 0.005)
        }
    }

    /** Ticks from now until the clock next reads [hour] (a 24-hour clock as the days keep it), but not under [MIN_BURN]: a candle lit at dawn is not put out as it is lit. */
    fun ticksTo(level: ServerLevel, hour: Double): Long =
        maxOf(Math.floorMod(Days.clock(hour) - Days.offsetOf(level.overworldClockTime), 24000), MIN_BURN).toLong()

    /** The least a lit candle burns: two minutes. */
    private const val MIN_BURN = 2400

    /** Six in the morning. */
    const val DAWN = 6.0

    // ---- Time ----

    /** The day the memorials of [capital] stand at (a market run on ahead has lived those days): its market's, else the clock's. */
    fun today(level: ServerLevel, capital: Long?): Long =
        if (capital != null) TaleEngine.today(level, capital) else Conditions(Markets.hourOf(level)).day

    /**
     * The block's random tick: the moss grows once the memorial is [MOSS_DAYS] market days old (13.2.1), and a posy on its ledge withers
     * after [POSY_DAYS] and the wind takes it. Each is written once.
     */
    fun age(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val be = level.getBlockEntity(pos) as? MemorialBlockEntity
        if (state.getValue(MemorialBlock.LIT) && (be?.litUntil ?: 0L) <= level.gameTime) {
            // Flames whose time has come and whose scheduled tick was lost (or set by hand with no time): the next random tick sees to the rest
            snuff(level, pos, state)
            return
        }
        val now = today(level, be?.capital)
        var next = state
        val laid = be?.day
        if (!state.getValue(MemorialBlock.MOSSY) && laid != null && now - laid >= MOSS_DAYS) next = next.setValue(MemorialBlock.MOSSY, true)
        if (state.getValue(MemorialBlock.POSY) && be != null) {
            val since = be.posyDay
            if (since == null) {
                // Set by hand, with no day: its days are counted from now
                be.posyDay = now
                be.setChanged()
            } else if (POSY_DAYS > 0 && now - since >= POSY_DAYS) {
                next = next.setValue(MemorialBlock.POSY, false)
                be.posyDay = null
                be.setChanged()
                level.sendParticles(ParticleTypes.CHERRY_LEAVES, pos.x + 0.5, pos.y + 0.4, pos.z + 0.5, 10, 0.25, 0.1, 0.25, 0.03)
                level.playSound(null, pos, SoundEvents.PINK_PETALS_FALL, SoundSource.BLOCKS, 0.6f, 0.8f)
            }
        }
        if (next != state) level.setBlock(pos, next, Block.UPDATE_ALL)
    }

    // ---- Breaking ----

    /**
     * A survival player [player] is breaking the memorial at [pos] (from the block): a slight to the household of the dead named on it
     * (`Slight.DEFACED`) and a rumour of it (6.4, 6.6). Only a memorial the town set, in a capital's records, is the town's; one set by hand
     * in creative has no household. Nothing at all with tales off.
     */
    fun defaced(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer) {
        if (!Tales.live) return
        val be = level.getBlockEntity(pos) as? MemorialBlockEntity
        val record = recordAt(level, pos)
        val key = record?.capital?.takeIf { it != 0L } ?: be?.capital ?: return
        val capital = BlockPos.of(key)
        val names = record?.names?.takeIf { it.isNotEmpty() } ?: be?.names?.map { it.name }.orEmpty()
        if (names.isEmpty()) return
        val people = Residents.get(level).of(capital)
        val dead = names.mapNotNull { n -> people.firstOrNull { it.died >= 0 && it.name == n } }
        val household = dead.filter { it.home >= 0 }.flatMap { d -> people.filter { it.alive && it.home == d.home } }.distinctBy { it.id }
        val seen = Witness.seen(level, Vec3.atCenterOf(pos), null, Witness.SIGHT, true).map { it.second }.filter { it.capital == capital }
        for (h in household) TownTalk.slight(level, h, player.uuid, Slight.DEFACED)
        val knowers = (seen.map { it.id to Venue.SEEN } + household.map { it.id to Venue.HOUSEHOLD }).distinctBy { it.first }
        if (knowers.isEmpty()) return
        TownTalk.begin(
            level, capital, "slight.defaced", player.uuid, dead.firstOrNull()?.id ?: -1,
            listOf(Witness.named(player.name.string), Witness.named(MemorialWords.label(names))),
            0.8f, Fade.BIG, Scope.TOWN, -1, pos, Rumours.TOWN, knowers,
        )
    }

    /** The memorial block at [pos] is gone, however (broken, replaced, a command): the town's record of it goes with it. */
    fun gone(level: ServerLevel, pos: BlockPos) {
        // Asked too when only its state changed, and the block is still there
        if (level.getBlockState(pos).`is`(MemorialRegistry.MEMORIAL.get())) return
        val data = TaleData.get(level)
        val here = dimension(level)
        val record = data.memorials.firstOrNull { it.pos == pos && it.dimension == here } ?: return
        data.memorials.remove(record)
        data.changed()
    }

    /** The record of the memorial at [pos], if the town has one. */
    fun recordAt(level: ServerLevel, pos: BlockPos): TaleData.Memorial? {
        val here = dimension(level)
        return TaleData.get(level).memorials.firstOrNull { it.pos == pos && it.dimension == here }
    }

    /** [level]'s dimension as a memorial's record keeps it. */
    fun dimension(level: ServerLevel): String = level.dimension().identifier().toString()

    // ---- Setting it down (stage 8 of the finish) ----

    fun place(c: Ctx, t: Tale) {}

    /** Part F fills this in: the posy goes down on [memorialId]'s stone (6.4) as the Laying's LAY_POSY lands; `Sets.laying` calls it at tick 15 of the clip (the posy is down at 14, leaves her hands at 18). Part B's stub. */
    fun posy(level: net.minecraft.server.level.ServerLevel, memorialId: Int) {}
}
