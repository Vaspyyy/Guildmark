package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.NoticeBoardBlockEntity
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRec
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.EscortRec
import io.github.vaspyyy.guildmark.economy.EscortState
import io.github.vaspyyy.guildmark.economy.MarketFetch
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Seeing a wagon in (design-road.md section 4): a new kind of contract, [QuestType.CARAVAN]. While a road into a
 * capital is Troubled or worse, the capital's quest board carries one note for it, put up again each day while it
 * lasts, and a wanted note on the band that works it once the band is named. The note names the road and when its
 * next wagon sets out, and points to the meeting place, the start of the road's leg; the next wagon on that road waits
 * there for its holder until two hours past its time (a Closed road's until the evening, when the note lapses). Come
 * within [Caravans.MEET] of it and it's off with you; Coaches refuse you only from then on. It's done when the wagon
 * pulls up at its stand with you within [Caravans.SEEN_IN], and turned in at the capital's board for [PAY], a Mark
 * per [PER] blocks of the leg and [PER_KILL] for each waylayer you or your party killed ([KILLS_MOST] more at most),
 * shared with a party as other contracts are. If the wagon is waylaid, or goes without you, the job's off and the
 * note is worth nothing, costing nothing but the job. One holder a road at a time.
 */
object CaravanEscorts {
    /** The one target a wagon's note takes. */
    val TARGET: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "caravan")

    /** Who signs a road's notes (see quest/Labels): the capital's carters. */
    private const val POSTER = "#quest.guildmark.poster.carters"

    /** Who signs the wanted notes on a road's band: the town watch, as on the bounty boards. */
    private const val WATCH = "#quest.guildmark.poster.watch"

    /** What seeing a wagon in pays: [PAY], a Mark per [PER] blocks of its leg, and [PER_KILL] a waylayer killed, [KILLS_MOST] more at most. */
    const val PAY = 10
    const val PER = 100.0
    const val PER_KILL = 3
    const val KILLS_MOST = 12

    /** A wagon's contract's steps: met at the start of its leg (1), and in at its stand (2). */
    const val STEPS = 2

    /** Clock ticks a note's job is kept after it lapses or is done, for a holder who's away to hear of it. */
    private const val KEPT = 3 * 24000L

    /** Whether escort notes go up (caravanEscorts, which needs the wagons). */
    fun enabled(): Boolean = Caravans.escorts()

    // ---- On the capital's board ----

    /**
     * The capital board at [anchor] ([cells], facing [facing]): for each of its capital's roads that's Troubled or
     * worse and that nobody holds the job for, its escort note; and a wanted note on the band that works it, once
     * the band is named. Pinned at once, as there's nobody to do it (with someone watching, Part B's guard walks it up);
     * a note nobody's taken for a road gone quiet comes down (see [stale]).
     */
    fun pin(level: ServerLevel, anchor: BlockPos, facing: Direction, cells: List<NoticeBoardBlockEntity>) {
        if (!enabled() || level.dimension() != Level.OVERWORLD || !level.isBrightOutside) return
        val entry = MarketFetch.at(level, anchor) ?: return
        val capital = entry.market.capital
        val legs = CaravanRoutes.legs(level, capital) ?: return
        val data = CaravanData.get(level)
        val pinned = cells.mapNotNull { it.note }.toMutableList()
        val tier = QuestGenerator.tierAt(anchor.x, anchor.z)
        for (leg in legs) {
            if (leg.road < 0) continue
            val stage = RoadPeril.stage(data, capital, leg.road)
            if (!stage.troubled) continue
            if (pinned.none { it.type == QuestType.CARAVAN && it.site == leg.road } && held(data, capital, leg.road) == null) {
                val note = note(level, data, capital, leg, stage, tier) ?: continue
                val cell = room(cells) ?: return
                cell.updateNote(note)
                pinned.add(note)
                put(level, cell, facing)
            }
            val band = band(level, data, capital, leg) ?: continue
            if (pinned.any { it.type == QuestType.BOUNTY && it.site == band.id }) continue
            val notice = Bounties.notice(band)
            val wanted = Bounties.dress(QuestNote(QuestType.BOUNTY, WATCH, Bounties.OUTLAW, 1, notice.reward, 3, "", rank(tier, 1)), notice)
            val cell = room(cells) ?: return
            cell.updateNote(wanted)
            pinned.add(wanted)
            put(level, cell, facing)
        }
    }

    /** The band working [leg]'s road into [capital] that's wanted: its last to strike there, or the road's nearest, once it's named. */
    private fun band(level: ServerLevel, data: CaravanData, capital: Long, leg: CaravanRoutes.Leg): OutlawBand? {
        val camps = OutlawCamps.get(level)
        val band = data.roadOrNull(capital, leg.road)?.band?.takeIf { it >= 0 }?.let { camps.band(it) } ?: CaravanRoutes.band(level, leg)
        return band?.takeIf { !it.slain && (it.built && it.captain() != null || !it.built && it.named) }
    }

    /** An empty cell of [cells], or one whose ordinary note gives way (the oldest; never a road's, a wanted note, a plea or a story's). */
    private fun room(cells: List<NoticeBoardBlockEntity>): NoticeBoardBlockEntity? {
        cells.firstOrNull { it.note == null }?.let { return it }
        return cells.filter { c ->
            val n = c.note ?: return@filter true
            n.type != QuestType.CARAVAN && n.type != QuestType.BOUNTY && n.type != QuestType.RESCUE && n.chain.isEmpty()
        }.minByOrNull { it.postedDay }
    }

    /** A note going up: the rustle of paper and the scratch of the pin. */
    private fun put(level: ServerLevel, cell: NoticeBoardBlockEntity, facing: Direction) {
        val at = Vec3.atCenterOf(cell.blockPos).relative(facing, 0.35)
        level.playSound(null, cell.blockPos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.BLOCKS, 0.8f, 1.1f)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z, 4, 0.3, 0.3, 0.05, 0.0)
    }

    /** The note for [leg]'s road at [stage]: the road, when its next wagon sets out, and what seeing it in pays. */
    private fun note(level: ServerLevel, data: CaravanData, capital: Long, leg: CaravanRoutes.Leg, stage: RoadPeril.Stage, tier: Int): QuestNote? {
        val out = Caravans.nextWagon(level, capital, leg.road) ?: return null
        val hour = Caravans.clockOf(Math.floorDiv(out, 1000L))
        val story = "quest.guildmark.caravan.story.${stage.id}|${way(leg)}|#quest.guildmark.caravan.hour.$hour"
        val pay = PAY + (leg.length / PER).toInt()
        return QuestNote(QuestType.CARAVAN, POSTER, TARGET, STEPS, pay, 1, story, rank(tier, 0), subject = way(leg), site = leg.road)
    }

    /** For the commands: the note [leg]'s road into [capital] would have on the board at [anchor] now (as if Troubled, at the least). */
    fun noteFor(level: ServerLevel, capital: Long, leg: CaravanRoutes.Leg, anchor: BlockPos): QuestNote? {
        val data = CaravanData.get(level)
        val stage = RoadPeril.stage(data, capital, leg.road).takeIf { it.troubled } ?: RoadPeril.Stage.TROUBLED
        return note(level, data, capital, leg, stage, QuestGenerator.tierAt(anchor.x, anchor.z))
    }

    /** The road as a note names it ("the north road"), a label (see Labels). */
    fun way(leg: CaravanRoutes.Leg): String = "#road.guildmark.way.${leg.side.name.lowercase()}"

    /** The rank a road's notes are rated at, out at [tier]: a little above the jobs about, as a fight's likely. */
    private fun rank(tier: Int, more: Int): Int = ((tier - 1) * 2 + more).coerceIn(0, AdventurerRank.A.ordinal)

    /** A road note that's stale: the escorts are off, or the road it's for is no longer troubled. */
    fun stale(level: ServerLevel, anchor: BlockPos, note: QuestNote): Boolean {
        if (note.type != QuestType.CARAVAN) return false
        if (!enabled()) return true
        val entry = MarketFetch.at(level, anchor) ?: return true
        return !RoadPeril.stage(CaravanData.get(level), entry.market.capital, note.site).troubled
    }

    // ---- Taking the note, and turning it in ----

    /**
     * [player] takes [note] from the board at [board]: the job on its road is theirs, and the next wagon on it (or
     * one waiting already, on a Closed road) waits for them at the start of its leg. The note and the contract's state
     * set up, or null with a word why: no wagon to see in, or someone holds the job already.
     */
    fun take(level: ServerLevel, board: BlockPos, player: Player, note: QuestNote, state: ContractState): Pair<QuestNote, ContractState>? {
        val entry = MarketFetch.at(level, board)
        val data = CaravanData.get(level)
        val capital = entry?.market?.capital
        val leg = capital?.let { CaravanRoutes.legs(level, it)?.firstOrNull { l -> l.road == note.site && l.road >= 0 } }
        val out = if (capital != null && leg != null) Caravans.nextWagon(level, capital, leg.road) else null
        if (!enabled() || capital == null || leg == null || out == null || !RoadPeril.stage(data, capital, leg.road).troubled) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.caravan.no_wagon"))
            return null
        }
        if (held(data, capital, leg.road) != null) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.caravan.held"))
            return null
        }
        val t = level.overworldClockTime
        val until = Caravans.evening(maxOf(t, out))
        val job = UUID.randomUUID()
        data.escorts.add(EscortRec(job, player.uuid, player.name.string, capital, leg.road, until, EscortState.OPEN, -1L, 0))
        // A Closed road's wagon may be waiting at the start already, for anyone who'd come
        data.caravans.firstOrNull { it.capital == capital && it.road == leg.road && it.phase == Phase.WAITING && it.job == null }?.let { bind(data, it) }
        data.changed()
        val start = leg.start
        val pay = PAY + (leg.length / PER).toInt()
        val deadline = Caravans.gameAt(level, until) + 2 * ContractState.TICKS_PER_DAY
        val hour = Caravans.clockOf(Math.floorDiv(out, 1000L))
        val story = "quest.guildmark.caravan.story.${RoadPeril.stage(data, capital, leg.road).id}|${way(leg)}|#quest.guildmark.caravan.hour.$hour"
        return note.copy(count = STEPS, reward = maxOf(note.reward, pay), subject = way(leg), story = story) to state.copy(
            deadline = deadline, destination = BlockPos.containing(start.x, start.y, start.z), bound = job, label = way(leg), road = leg.road,
        )
    }

    /** Who holds the job on [road] into [capital] now, if anyone: one taken and not yet over. */
    private fun held(data: CaravanData, capital: Long, road: Int): EscortRec? =
        data.escorts.firstOrNull { it.capital == capital && it.road == road && (it.state == EscortState.OPEN || it.state == EscortState.MET) }

    /** The record of the job [state]'s contract is for, if it's kept. */
    private fun job(level: ServerLevel, state: ContractState): EscortRec? = state.bound?.let { CaravanData.get(level).escort(it) }

    /**
     * Brings [stack]'s contract up to date with its job as it's turned in: the wagon in with its holder by it marks it
     * done and adds the pay for the waylayers killed, if that wasn't done as it got in (the contract was put by).
     */
    fun settle(level: ServerLevel, stack: ItemStack) {
        val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: return
        if (note.type != QuestType.CARAVAN) return
        val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: return
        val rec = job(level, state) ?: return
        if (rec.state == EscortState.DONE && state.progress < note.count) done(stack, note, state, rec.kills)
    }

    /** Whether [state]'s job is off: the wagon waylaid, gone without its holder, never sent, or the record of it gone. */
    fun lost(level: ServerLevel, state: ContractState): Boolean {
        val rec = job(level, state) ?: return state.progress < STEPS
        return rec.state == EscortState.FAILED
    }

    /** [state]'s contract has been paid: its job is over. */
    fun turnedIn(level: ServerLevel, state: ContractState) {
        val data = CaravanData.get(level)
        if (state.bound != null && data.escorts.removeIf { it.job == state.bound }) data.changed()
    }

    /** Whether [stack] is a wagon's contract whose holder has met the wagon and walks it in (Coaches won't take them now). */
    fun walking(level: ServerLevel, stack: ItemStack): Boolean {
        if (stack.get(ModDataComponents.QUEST_NOTE.get())?.type != QuestType.CARAVAN) return false
        val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: return false
        return job(level, state)?.state == EscortState.MET
    }

    /** [stack]'s contract marked done, with [kills] waylayers' pay added, and its compass turned back to the board. */
    private fun done(stack: ItemStack, note: QuestNote, state: ContractState, kills: Int) {
        val bonus = minOf(KILLS_MOST, PER_KILL * kills.coerceAtLeast(0))
        stack.set(ModDataComponents.QUEST_NOTE.get(), note.copy(reward = note.reward + bonus))
        Contracts.retarget(stack, state.copy(progress = note.count, destination = state.boardPos))
    }

    /** [player]'s contract for job [job], if they carry it. */
    private fun contract(player: ServerPlayer, job: UUID): ItemStack? {
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.get(ModDataComponents.QUEST_NOTE.get())?.type != QuestType.CARAVAN) continue
            if (stack.get(ModDataComponents.CONTRACT_STATE.get())?.bound == job) return stack
        }
        return null
    }

    // ---- What befalls the wagon (called by Caravans) ----

    /** [c] sets out: a job taken on its road and not yet with a wagon is its, and it waits for the holder. */
    fun bind(data: CaravanData, c: CaravanRec) {
        if (c.job != null) return
        val rec = data.escorts.firstOrNull { it.capital == c.capital && it.road == c.road && it.state == EscortState.OPEN && it.caravan < 0 } ?: return
        rec.caravan = c.id
        c.job = rec.job
        c.holder = rec.holder
        data.changed()
    }

    /**
     * [c]'s job is off ([why]: "waylaid", "alone" when it went without its holder, "lapsed" when a Closed road's wagon
     * never went, "missed" when it got in without them, "called" when it was called off before it was in): the holder
     * hears it, and the contract is gone from their pack.
     */
    fun failed(level: ServerLevel, data: CaravanData, c: CaravanRec, why: String) {
        val job = c.job ?: return
        val rec = data.escort(job) ?: return
        if (rec.state == EscortState.DONE || rec.state == EscortState.FAILED) return
        rec.state = EscortState.FAILED
        data.changed()
        level.server.playerList.getPlayer(rec.holder)?.let { tell(it, rec, why) }
    }

    /** [rec]'s holder hears why the job is off, and its contract goes (it's worth nothing now). */
    private fun tell(player: ServerPlayer, rec: EscortRec, why: String) {
        contract(player, rec.job)?.shrink(1)
        player.sendSystemMessage(Component.translatable("message.guildmark.caravan.failed.$why").withStyle(ChatFormatting.GRAY))
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 0.9f)
        rec.caravan = -2L
    }

    /** [player], [c]'s escort, has met it at the start of [leg]: the driver waves them on, and the contract points to the stand. */
    fun met(level: ServerLevel, data: CaravanData, c: CaravanRec, player: ServerPlayer, leg: CaravanRoutes.Leg) {
        val rec = c.job?.let { data.escort(it) } ?: return
        rec.state = EscortState.MET
        data.changed()
        contract(player, rec.job)?.let { stack ->
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: return@let
            val stand = leg.stand
            Contracts.retarget(stack, state.copy(progress = 1, destination = BlockPos.containing(stand.x, stand.y, stand.z)))
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.caravan.met", Labels.text(way(leg))).withStyle(ChatFormatting.YELLOW))
        level.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.5f, 1.2f)
    }

    /**
     * [c] is in, its holder by it ([seen]) or not: done, with the waylayers' pay, and everyone else who walked it in
     * thanked; or, with no holder by it, the job's off.
     */
    fun arrived(level: ServerLevel, data: CaravanData, c: CaravanRec, seen: Boolean) {
        val rec = c.job?.let { data.escort(it) } ?: return
        if (!seen) {
            failed(level, data, c, "missed")
            return
        }
        rec.state = EscortState.DONE
        rec.kills = c.kills
        data.changed()
        val holder = level.server.playerList.getPlayer(rec.holder)
        if (holder != null) {
            contract(holder, rec.job)?.let { stack ->
                val note = stack.get(ModDataComponents.QUEST_NOTE.get())
                val state = stack.get(ModDataComponents.CONTRACT_STATE.get())
                if (note != null && state != null && state.progress < note.count) done(stack, note, state, rec.kills)
            }
            holder.sendSystemMessage(Component.translatable("message.guildmark.caravan.done", name(level, c.capital)).withStyle(ChatFormatting.GREEN))
            level.playSound(null, holder.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.3f)
        }
        // Anyone else who walked it in gets the driver's thanks
        val leg = CaravanRoutes.cached(c.capital, c.road) ?: return
        val stand = leg.stand
        for (player in level.players()) {
            if (player.uuid == rec.holder || player.isSpectator) continue
            val dx = player.x - stand.x
            val dz = player.z - stand.z
            if (dx * dx + dz * dz > Caravans.SEEN_IN * Caravans.SEEN_IN) continue
            player.sendSystemMessage(Component.translatable("message.guildmark.caravan.thanks").withStyle(ChatFormatting.YELLOW))
        }
    }

    /** The capital whose heart is [capital], by name. */
    private fun name(level: ServerLevel, capital: Long): String =
        CaravanData.get(level).capitalOrNull(capital)?.name?.takeIf { it.isNotEmpty() } ?: "?"

    /**
     * Every few seconds (clock time [t]): a job no wagon took up by its evening lapses; the holder of a job that's off
     * hears it when they're next on; a job done or off is let go a few days on.
     */
    fun sweep(level: ServerLevel, data: CaravanData, t: Long) {
        if (data.escorts.isEmpty()) return
        for (rec in data.escorts.toList()) {
            if (rec.state == EscortState.OPEN && rec.caravan < 0 && t > rec.until) {
                rec.state = EscortState.FAILED
                data.changed()
                level.server.playerList.getPlayer(rec.holder)?.let { tell(it, rec, "lapsed") }
                continue
            }
            if (rec.state == EscortState.FAILED && rec.caravan != -2L) {
                val player = level.server.playerList.getPlayer(rec.holder)
                if (player != null) {
                    tell(player, rec, "off")
                    data.changed()
                }
            }
            if ((rec.state == EscortState.FAILED || rec.state == EscortState.DONE) && t > rec.until + KEPT) {
                data.escorts.remove(rec)
                data.changed()
            }
        }
    }
}
