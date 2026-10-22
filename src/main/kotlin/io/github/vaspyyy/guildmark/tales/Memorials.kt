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

    /** The reasons a memorial adds to its tale (`fortune.guildmark.reason.tale.why.<kind>`, each given the names as a label). */
    const val WHY_STONE = "memorial.stone"
    const val WHY_CAIRN = "memorial.cairn"
    const val WHY_NOROOM = "memorial.noroom"
    const val WHY_FULL = "memorial.full"
    const val WHY_OFF = "memorial.off"
    const val WHY_WAIT = "memorial.wait"

    /** Every key a reason of the memorials can be, in full, so that the language check sees them. */
    val WHY_KEYS: List<String> = listOf(
        "fortune.guildmark.reason.tale.why.memorial.stone",
        "fortune.guildmark.reason.tale.why.memorial.cairn",
        "fortune.guildmark.reason.tale.why.memorial.noroom",
        "fortune.guildmark.reason.tale.why.memorial.full",
        "fortune.guildmark.reason.tale.why.memorial.off",
        "fortune.guildmark.reason.tale.why.memorial.wait",
    )

    /** The most names cut in one memorial. */
    private const val NAMES = 8

    /** At setup: the block, its item and entity, and the stone and the cairn in the creative hand; the waiting list. */
    fun register(modBus: IEventBus) {
        MemorialRegistry.BLOCKS.register(modBus)
        MemorialRegistry.ITEMS.register(modBus)
        MemorialRegistry.BLOCK_ENTITIES.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey != ModCreativeTabs.MAIN.key) return@addListener
            event.accept(MemorialRegistry.stack(MemorialKind.STONE))
            event.accept(MemorialRegistry.stack(MemorialKind.CAIRN))
        }
        MemorialPlacing.register()
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
        } else {
            stack.hurtAndBreak(1, player, hand)
        }
        burn(level, pos, state, ticksTo(level, DAWN))
        candleLit(level, pos)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        return true
    }

    /**
     * The one place a candle catching at a memorial is heard. Tales H's `TaleSounds` is not in this tree, so it is silent here: when it is
     * merged, the body of this function is `TaleSounds.at(level, pos, TaleSounds.Sound.CANDLE_LIGHT)` and nothing else (never a raw sound id).
     */
    fun candleLit(level: ServerLevel, pos: BlockPos) {
        // SEAM (Tales H): TaleSounds.at(level, pos, TaleSounds.Sound.CANDLE_LIGHT)
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

    /**
     * From `Tales.wants` (and so `Streets.wants`, as a chunk with the memorial's pieces loads): whether the piece at [pos] of memorial [run]
     * (the stone itself, its lantern, the posy's pot) is still wanted: true while the memorial is on file, so the block is never put back as
     * it was; with the file's entry gone (a command removed it) the generic restore drops the piece's record and leaves the block.
     */
    fun wants(level: ServerLevel, column: Long, pos: BlockPos, run: Long): Boolean {
        val here = dimension(level)
        return TaleData.get(level).memorials.any { it.id.toLong() == run && it.dimension == here }
    }

    /** The record of the memorial at [pos], if the town has one. */
    fun recordAt(level: ServerLevel, pos: BlockPos): TaleData.Memorial? {
        val here = dimension(level)
        return TaleData.get(level).memorials.firstOrNull { it.pos == pos && it.dimension == here }
    }

    /** [level]'s dimension as a memorial's record keeps it. */
    fun dimension(level: ServerLevel): String = level.dimension().identifier().toString()

    // ---- Asking for one, and setting it down (stage 8 of the finish) ----

    /**
     * The memorials of [t] on file (a tale has no link to them; they are found by its template, its capital and the day it ended, or the
     * day it is ending if it has not): oldest first. What a template's scene asks for the stone's place and whether it stands.
     */
    fun of(c: Ctx, t: Tale): List<TaleData.Memorial> {
        val day = dayOf(c, t)
        val here = dimension(c.level)
        return c.data.memorials.filter { it.template == t.template && it.capital == c.capital && it.day == day && it.dimension == here }.sortedBy { it.id }
    }

    private fun dayOf(c: Ctx, t: Tale): Long = if (t.ended > TaleData.NEVER) t.ended else c.day

    /**
     * `Ctx.memorial`: records a memorial of [kind] for [names] in [t]'s capital, as near [at] as the rules allow (null: where the kind
     * belongs, 6.4), and sets it down at once if its chunk is loaded: the Laying's scene is made at stage 7, before stage 8, and kneels
     * to the stone only if it stands. Six to a capital: a seventh is not recorded and a `Why` says so (null). Asked twice for the same
     * names it is the same memorial. With `townTaleMemorials` off it is recorded and no block is set.
     */
    fun ask(c: Ctx, t: Tale, kind: String, names: List<String>, at: BlockPos?): TaleData.Memorial? {
        val id = MemorialKind.of(kind).id
        val cut = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(NAMES)
        of(c, t).firstOrNull { it.kind == id && it.names == cut }?.let { return it }
        val data = c.data
        if (data.memorials.count { it.capital == c.capital } >= TaleLimits.MEMORIALS) {
            c.why(t, WHY_FULL, MemorialWords.label(cut))
            return null
        }
        val lead = t.lead?.let { TaleEngine.playerName(c.level, it.player) }.orEmpty()
        val rec = TaleData.Memorial(data.newMemorialId(), id, at, dimension(c.level), cut, t.template, lead, dayOf(c, t), c.capital, false)
        data.memorials.add(rec)
        data.changed()
        MemorialPlacing.settle(c, t, rec)
        return rec
    }

    /** Where a memorial of a kind belongs in [c]'s capital when its tale names no place (a temple's door, a well, the market; a cairn by a road out of the gates): for a template that stands its people there before the stone is set. Null where the places are not known. */
    fun anchor(c: Ctx, cairn: Boolean): BlockPos? = MemorialSites(c.level, c.here.capital, c.here.places).anchors(cairn).firstOrNull()

    /** Stage 8 of the finish: every memorial of [t] that is not set down yet gets another try. */
    fun place(c: Ctx, t: Tale) = MemorialPlacing.sweep(c, t)

    /** The position of the memorial block of record [memorialId], if it stands in a loaded chunk. */
    private fun standing(level: ServerLevel, memorialId: Int): BlockPos? {
        val here = dimension(level)
        val rec = TaleData.get(level).memorials.firstOrNull { it.id == memorialId && it.dimension == here } ?: return null
        val pos = rec.pos ?: return null
        if (!rec.placed || !level.isLoaded(pos) || !level.getBlockState(pos).`is`(MemorialRegistry.MEMORIAL.get())) return null
        return pos
    }

    /**
     * The posy goes down on [memorialId]'s stone (6.4) as the Laying's LAY_POSY lands; `Sets.laying` calls it at tick 15 of the clip (the posy is
     * down at 14, leaves her hands at 18). It lies on the ledge (the cairn's step) five market days and withers ([age]). Nothing if no such
     * stone stands: the Laying had put its posy in a pot, or in no place at all.
     */
    fun posy(level: ServerLevel, memorialId: Int) {
        val pos = standing(level, memorialId) ?: return
        val state = level.getBlockState(pos)
        (level.getBlockEntity(pos) as? MemorialBlockEntity)?.let {
            it.posyDay = today(level, it.capital)
            it.setChanged()
        }
        if (!state.getValue(MemorialBlock.POSY)) level.setBlock(pos, state.setValue(MemorialBlock.POSY, true), Block.UPDATE_ALL)
        // A few petals shaken from it as it settles
        val at = MemorialBlock.posyAt(state.getValue(MemorialBlock.KIND), state.getValue(MemorialBlock.FACING), pos)
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y + 0.1, at.z, 5, 0.1, 0.04, 0.1, 0.02)
        level.playSound(null, pos, SoundEvents.PINK_PETALS_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f)
    }

    /**
     * For a template (the chair's: the neighbours come with candles): puts candles on the ledge of [memorialId]'s stone until [count] stand
     * (0 to [MemorialBlock.MAX_CANDLES]; never fewer than stand already) and, if [light], lights them until the coming dawn. False if no such
     * stone stands in a loaded chunk.
     */
    fun candles(level: ServerLevel, memorialId: Int, count: Int, light: Boolean): Boolean {
        val pos = standing(level, memorialId) ?: return false
        var state = level.getBlockState(pos)
        val n = count.coerceIn(state.getValue(MemorialBlock.CANDLES), MemorialBlock.MAX_CANDLES)
        if (n != state.getValue(MemorialBlock.CANDLES)) {
            state = state.setValue(MemorialBlock.CANDLES, n)
            level.setBlock(pos, state, Block.UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.CANDLE_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f)
        }
        if (light && n > 0 && !state.getValue(MemorialBlock.LIT)) {
            burn(level, pos, state, ticksTo(level, DAWN))
            candleLit(level, pos)
        }
        return true
    }
}
