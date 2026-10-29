package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity.Slip
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.TaleSounds
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * The Bell Moot's square (living-world/stories/design.md 8.7), Tales T4: the crier's call ([cries], beside the others in `Tales.cries`), and
 * the once-a-second care of an open moot ([second]) while Town Tales is on and a player is online.
 *
 *  - **The call.** On the market days from D0 to the moot's own, until it sits, the crier's noon round carries one more line,
 *    `crier_tale_moot` ("The moot is called! Come, and say your piece!") with when it meets in grey and a slip on the board. As the crier
 *    cries it, the square's free folk are drawn in once more (`Crier.gather`), and on D0 the page turns (`tale.step`).
 *  - **The quarrel.** Before the moot sits, from 11:00 to 17:00 while a player is within 64 blocks of the board, `for` and `against` are held
 *    at marks four steps out from it, three apart, so S3 can be played between them ([MootScenes.quarrel]); once it has played through they
 *    go home and are let go.
 *  - **The moot.** On D2 from 11:30 the sides are held to either side of the crying spot. The moot sits once the crier's noon round is done
 *    (or at 12:45 whatever it is doing; at once for a crier standing in, who has no round), and the crier is held at the spot from when they are
 *    at leisure (13:25 at the latest, so the board is written up first). At two the moot rises: if no word was spoken it breaks up (W).
 *
 * What it keeps in memory (the call heard today, the crowd drawn, a `tale moot sit`) is lost on a restart and worked out again: the record
 * keeps the moot sitting (`n[5]`), the holds are the engine's, and the moot day and its hours come from the clock.
 */
internal object MootSquare {
    private const val CRY = "crier_tale_moot"
    private const val CALL = "say.guildmark.crier_tale_moot.call"
    private const val NOW = "say.guildmark.crier_tale_moot.now"
    private const val SLIP = "board.guildmark.slip.tale.moot"

    /** The hours of the moot day: the sides held from, the moot sits by at the latest, the crier held from at the latest, and it rises. */
    private const val SIDES_FROM = 11.5
    private const val SITS_BY = 12.75
    private const val CRIER_FROM = 13.4
    private const val RISES = 14.0

    /** How near the board a player must be for the sides to be held for the quarrel. */
    private const val NEAR = 64.0

    /** How long `tale moot sit` keeps the moot sitting, in ticks (two game hours). */
    private const val FORCED = 2000L

    private class Memo {
        var heard = Long.MIN_VALUE
        var gathered = Long.MIN_VALUE
        var indexDay = Long.MIN_VALUE
        var index = -1
        var forcedUntil = 0L
        val marks = HashMap<String, BlockPos>()
    }

    private val memos = HashMap<Int, Memo>()
    private var now = 0L

    /** One line in `Tales.register`: the commands and scenes, and the open moots seen to once a second. */
    fun register() {
        MootCommands.register()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> memos.clear() }
    }

    // ---- The crier ----

    /**
     * Beside the others in `Tales.cries` (6.2): the call of an open moot in [entry]'s capital at [c]'s noon, on the market days from D0 to the
     * moot's own until it sits: `crier_tale_moot`, when it meets in grey ("The moot meets at noon in 2 days, before the board."), and a slip.
     * None with Town Tales off.
     */
    fun cries(level: ServerLevel, entry: Markets.Entry, c: Conditions, at: Long): List<Crier.Cry> {
        if (!Tales.live) return listOf()
        val t = TaleData.get(level).openIn(entry.market.capital)?.takeIf { it.template == Moot.id && it.n[Moot.SITS] == 0 } ?: return listOf()
        val day = c.day
        val meets = t.d0 + Moot.MOOT_DAY
        if (day < t.d0 || day > meets) return listOf()
        val detail = if (day == meets) Component.translatable(NOW) else Component.translatable(CALL, Component.translatable(TaleLines.due(meets - day)))
        val text = Component.translatable("say.guildmark.$CRY").append(" ").append(detail.copy().withStyle(ChatFormatting.GRAY))
        return listOf(Crier.Cry(CRY, text, Slip(Slip.Kind.TALE.id, Component.translatable(SLIP), detail, at), 0))
    }

    // ---- Once a second ----

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % 20 != 11 || !Tales.live) return
        val level = server.overworld()
        if (level.players().isEmpty()) return
        now = level.gameTime
        for (t in TaleEngine.openTales(level).toList()) {
            if (t.template != Moot.id || !t.open) continue
            TaleLook.guarded(level, "the moot's second") { second(level, t) }
        }
        if (memos.size > 8) memos.keys.retainAll(TaleEngine.openTales(level).map { it.id }.toSet())
    }

    /** The hour of the clock, 2 to 26 (a market day starts at two in the morning). */
    fun hour(level: ServerLevel): Double = Days.offsetOf(level.overworldClockTime) / 1000.0 + 2.0

    /** Whether `tale moot sit` has the moot of [t] sitting now, whatever the hour. */
    fun forced(t: Tale): Boolean = (memos[t.id]?.forcedUntil ?: 0L) > now

    /** `tale moot sit`: the moot of [t] sits now and for two game hours, whatever the day and hour; false if the tale is over. */
    fun force(c: Ctx, t: Tale): Boolean {
        if (!t.open) return false
        now = c.level.gameTime
        memos.getOrPut(t.id) { Memo() }.forcedUntil = now + FORCED
        sit(c, t)
        return true
    }

    private fun second(level: ServerLevel, t: Tale) {
        val c = TaleEngine.ctx(level, t.capital) ?: return
        val memo = memos.getOrPut(t.id) { Memo() }
        val hour = hour(level)
        val today = c.day
        val meets = t.d0 + Moot.MOOT_DAY
        val forced = memo.forcedUntil > now
        if (!forced && memo.forcedUntil != 0L) {
            // A `tale moot sit` is over: the moot sits only in its own hours again
            memo.forcedUntil = 0L
            if (t.n[Moot.SITS] == 1 && !(today == meets && hour >= 12.0)) {
                t.n[Moot.SITS] = 0
                c.data.changed()
            }
        }
        // Two o'clock on the moot day (or the day after, the close not yet come): the moot rises, and with no word spoken it breaks up
        if (!forced && (today > meets || today == meets && hour >= RISES)) {
            c.why(t, if (t.lead == null) "moot.nobody" else "moot.unspoken")
            c.end(t, Ending.W)
            return
        }
        val crier = TaleBodies.body(level, t, Moot.SPEAKER)
        val places = c.here.places
        val real = crier != null && Hands.of(level, places).crier == crier.resident
        if (crier != null && today in t.d0..meets) call(c, t, crier, memo, today)
        // The moot sits: after the crier's round on the day, at a quarter to one whatever, at once for one standing in
        if (t.n[Moot.SITS] == 0 && today == meets && hour >= 12.0) {
            val done = memo.heard == today && (crier == null || Crier.state(crier) == null)
            if (done || hour >= SITS_BY || !real) sit(c, t)
        }
        hold(c, t, memo, crier, real, today, meets, hour, forced)
    }

    /** The crier is crying: at the moot's own line the square's folk are drawn in once more, and on D0 the page turns. */
    private fun call(c: Ctx, t: Tale, crier: Townsperson, memo: Memo, today: Long) {
        val state = Crier.state(crier) ?: return
        if (state.index < 0) return
        memo.heard = today
        if (memo.gathered == today) return
        if (memo.indexDay != today) {
            memo.indexDay = today
            memo.index = Crier.today(c.level, c.here.places, c.entry, Conditions(Markets.hourOf(c.level))).indexOfFirst { it.line == CRY }
        }
        if (state.index != memo.index.coerceAtLeast(0)) return
        memo.gathered = today
        Crier.gather(crier)
        if (today == t.d0) TaleSounds.at(c.level, crier.position(), TaleSounds.Sound.STEP)
    }

    /** The moot sits: in the record, and the tale goes on to the speaking if it was at the sides' accounts. */
    private fun sit(c: Ctx, t: Tale) {
        if (t.n[Moot.SITS] == 1) return
        t.n[Moot.SITS] = 1
        c.data.changed()
        c.why(t, "moot.sits", Moot.word(t))
        if (t.step == Moot.AT_MEET) c.go(t, Moot.AT_CHOOSE)
    }

    /** Who is held where this second (see the summary above), and who is let go. */
    private fun hold(c: Ctx, t: Tale, memo: Memo, crier: Townsperson?, real: Boolean, today: Long, meets: Long, hour: Double, forced: Boolean) {
        val level = c.level
        val m = MootScenes.marks(c.here.places)
        val sitting = t.n[Moot.SITS] == 1 && (forced || today == meets && hour < RISES)
        when {
            sitting || today == meets && hour >= SIDES_FROM -> {
                put(c, t, memo, Moot.FOR, m.stand(level, m.forAt), null)
                put(c, t, memo, Moot.AGAINST, m.stand(level, m.againstAt), Activity.ARMS_FOLDED)
            }
            t.n[Moot.SITS] == 0 && t.n[Moot.BITS] and Moot.QUARRELLED == 0 && hour >= MootScenes.QUARREL.start && hour < MootScenes.QUARREL.endInclusive &&
                level.getNearestPlayer(m.centre.x, m.centre.y, m.centre.z, NEAR, false) != null -> {
                put(c, t, memo, Moot.FOR, m.stand(level, m.forQuarrel), null)
                put(c, t, memo, Moot.AGAINST, m.stand(level, m.againstQuarrel), null)
            }
            else -> letGo(level, t)
        }
        if (sitting) {
            // The real crier writes the board up after the round before they are held
            val leisure = crier?.routine?.task.let { it == null || it.leisure }
            if (!real || forced || hour >= CRIER_FROM || leisure) put(c, t, memo, Moot.SPEAKER, m.stand(level, m.centre), null)
        } else if (t.cast(Moot.SPEAKER)?.held == true) {
            c.release(t, Moot.SPEAKER)
            memo.marks.remove(Moot.SPEAKER)
        }
    }

    /** [slot] held at [at] playing [clip], asked again only when the mark changes. */
    private fun put(c: Ctx, t: Tale, memo: Memo, slot: String, at: BlockPos?, clip: Activity?) {
        if (at == null) return
        val cast = t.cast(slot) ?: return
        if (cast.gone.isNotEmpty() || cast.held && memo.marks[slot] == at) return
        if (c.hold(t, slot, at, clip)) memo.marks[slot] = at
    }

    /** The two sides let go to their days (the quarrel has played, or it is not its hour). */
    fun letGo(level: ServerLevel, t: Tale) {
        val memo = memos[t.id]
        for (slot in listOf(Moot.FOR, Moot.AGAINST)) {
            if (t.cast(slot)?.held != true) continue
            Tales.release(level, t, slot)
            memo?.marks?.remove(slot)
        }
    }
}
