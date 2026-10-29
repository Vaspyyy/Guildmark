package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.lair.Lair
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Memorials
import io.github.vaspyyy.guildmark.tales.Part
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleEscort
import io.github.vaspyyy.guildmark.tales.TaleHold
import io.github.vaspyyy.guildmark.tales.TaleLeave
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Brother Under the Hill's once-a-second care (living-world/stories/design.md 8.12), Tales T5, while Town Tales is on and a player is online:
 *
 *  - **The mine.** Made at a late yes (taken up while the tales were off); gone from the record (a command), or cleared while nobody heard the
 *    boss fall: they come up by themselves (C, freed). A surface ring's fire is raised as a first visit would raise it.
 *  - **The one below.** Held at their mark in the mine with their dark lamp (CARRY), cowering (COWER, `tale.brother.scared` once in twenty
 *    seconds) while a hostile is within eight blocks of them and a player near; a tale body far from the mark (one taken where their day had
 *    them, after a restart) goes out of sight or by the gate first. A player within six blocks finds them (`tale.brother.found`; the deed of
 *    the way down, and its choice). Led (S8, [TaleEscort] with the brother's fright), up out of the mouth is the deed of A; a leading nobody is
 *    near for a minute is called off, and a minute more with nobody near has them wait at their mark again. Out of the mine with nobody near
 *    they set off home by themselves: along the road at a hobble while anyone sees them, gone from sight as soon as nobody does (the record has
 *    them at the forge door by first light).
 *  - **The rise** (C). The mine's fire lit while they are below and a player near: [BrotherScenes.rise] where they stand, and the record has
 *    them up when it is over.
 *  - **The gate and the forge.** The smith at the gate's front from 15:30 to 22:00 while they are below (the calling is the tale's seed scene),
 *    and at the forge door at the last step for the homecoming; the captain at the gate from 8:00 to 18:00 while they are to be asked; the
 *    delvers' leader at the tavern's door from 6:00 to 20:00 while the fee can be paid.
 *  - **The dawns.** The delvers' Return at first light after the party went, or the walk home of one who came up alone or to the fire (A at
 *    6:00, C at 7:00): with a player within 64 blocks of the forge door they are set down on a street out of sight (the delvers beside them)
 *    and walk to the door, where the scene is played; with nobody near by 10:00 the record has them home.
 *  - **After the end.** W: the laying at the stone by the forge door at dusk on the next day or the one after, with a player near (S5). A and B:
 *    a lantern hung beside the forge door once nobody sees the door (with `townTaleMemorials` on; skipped with no room).
 *
 * Everything it keeps is memory and lost on a restart (the holds and figures are the engine's, the record has the rest).
 */
internal object BrotherWatch {
    /** How near a player must be for the holds and the figures, and for the dawn's set-down to be walked rather than the record's. */
    private const val NEAR = 64.0

    /** How near a player must come to the one below to find them; how near the leader must be to take a broken-off walk up again. */
    private const val FOUND = 6.0
    private const val LEAD_ON = 16.0

    /** How near someone must be to the one out of the mine (or to a walk) for it to count as attended. */
    private const val ATTENDED = 48.0

    /** Blocks from the mark beyond which a tale body is not theirs to walk there (it goes, out of sight or by the gate). */
    private const val MARK_FAR = 48.0

    /** Ticks a walk (or a leading below) may go with nobody near before it is called off, or given up for the mark again. */
    private const val LOST_FOR = 1200L

    /** Ticks out of the mine with nobody near before they set off home by themselves. */
    private const val LONE_AFTER = 400L

    /** Ticks the way home alone may take; ticks before a way that would not go is tried again. */
    private const val LONE_FOR = 3600
    private const val LONE_AGAIN = 200L

    /** Ticks the walk up out of a cleared mine may take before the tale ends without it. */
    private const val FREED_FOR = 1200L

    /** Ticks the homecoming (or the Return) may wait to be played before the record ends the tale; ticks a set-down has to come off. */
    private const val STAGE_WAITS = 2400L
    private const val SET_DOWN_FOR = 3600L

    /** How near the forge door is home. */
    private const val AT_DOOR = 6.0

    /** Ticks between two cries of the one below, and how near a hostile frightens them. */
    private const val SCARED_EVERY = 400L
    private const val FRIGHT = 8.0

    /** Ticks after the fire is kindled before the one below is seen to make for it (the flame takes). */
    private const val RISE_AFTER = 40L

    /** The laying's hours, at dusk, and ticks between two tries. */
    private val LAY_FROM: Int = Days.clock(17.0)
    private val LAY_TO: Int = Days.clock(21.0)
    private const val LAY_EVERY = 600L

    private class Memo {
        /** The marks (and clips) each slot is held at by the watch. */
        val placed = HashMap<String, BlockPos>()
        val clips = HashMap<String, Activity?>()

        /** The walk the one below is on, and who leads it; whether its first leading is in the reasons. */
        var escort: TaleEscort? = null
        var leader: UUID? = null
        var walkTold = false

        /** Since when a walk (or the one out of the mine) has had nobody near (-1: someone is). */
        var lostSince = -1L
        var aloneSince = -1L
        var loneAfter = 0L

        /** The last cry below; when the fire was kindled; the rise playing. */
        var scaredAt = -SCARED_EVERY
        var litAt = -1L
        var rise: Stage? = null

        /** When the walk up out of the cleared mine began (-1: none). */
        var freedAt = -1L

        /** The homecoming or the Return, queued; since when it has waited; the dawn's set-down (game time, and where). */
        var stage: Stage? = null
        var together = -1L
        var setDown = -1L
        var downAt: BlockPos? = null

        /** Whether the delvers' leader stands at the tavern; whether `tale brother dawn` has the dawn now, whatever the hour. */
        var delver = false
        var forced = false

        /** Since when a leading given up has had them walking back down to their mark (-1: it has not; they are not sent away meanwhile). */
        var backSince = -1L
    }

    private class Laying {
        var next = 0L
        var stage: Stage? = null
    }

    private val memos = HashMap<Int, Memo>()
    private val layings = HashMap<Int, Laying>()

    private fun memo(t: Tale): Memo = memos.getOrPut(t.id) { Memo() }

    /** One line in `Tales.register`: the commands and scenes, and the open brother tales (and the ended ones' after) seen to once a second. */
    fun register() {
        BrotherCommands.register()
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forgetAll() }
    }

    /** From `Tales.lairCleared` (`Lairs.onBossDeath`): the tale hears the mine's boss fall. */
    fun lairCleared(level: ServerLevel, lair: Lair, heroes: List<ServerPlayer>) {
        if (Tales.listening(level, Event.Kind.LAIR)) Tales.tell(level, Event.Lair(lair.id, heroes.map { it.uuid }))
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % 20 != 17) return
        val level = server.overworld()
        if (!Tales.live || !Tales.scenes()) {
            // The engine lets the bodies go; what this placed is placed again when the scenes come back
            if (memos.isNotEmpty()) pause(level)
            if (!Tales.live) return
        }
        if (level.players().isEmpty()) return
        val open = TaleEngine.openTales(level)
        for (t in open.toList()) {
            if (t.template != Brother.id || !t.open) continue
            TaleLook.guarded(level, "the brother's second") { second(level, t) }
        }
        if (server.tickCount % 200 == 17) TaleLook.guarded(level, "the brother's after") { after(level) }
        if (memos.size > 8) memos.keys.retainAll(open.map { it.id }.toSet())
    }

    private fun second(level: ServerLevel, t: Tale) {
        val c = TaleEngine.ctx(level, t.capital) ?: return
        val memo = memo(t)
        mine(c, t)
        if (!t.open) return
        if (Brother.flag(t, Brother.FREED)) return freedOn(c, t, memo)
        if (t.step == Brother.AT_HOME) home(c, t, memo)
        if (!t.open || !Tales.scenes()) return
        brother(c, t, memo)
        if (!t.open) return
        rise(c, t, memo)
        if (!t.open) return
        holds(c, t, memo)
        delver(c, t, memo)
    }

    // ---- The mine ----

    private fun mine(c: Ctx, t: Tale) {
        val level = c.level
        if (t.n[Brother.N_LAIR] == 0) {
            // Taken up while nobody made it (a yes with the tales off, a deed before the yes): it is made now
            if (t.step >= Brother.AT_MEET && t.parts.any { Brother.joined(it) }) Brother.makeMine(c, t)
            return
        }
        val lair = Brother.lair(level, t)
        val below = Brother.where(t) <= Brother.LED && !Brother.flag(t, Brother.GONE) && !Brother.flag(t, Brother.FREED)
        if (lair == null) {
            // The mine is gone from the record (a command): they come up out of what is left of it
            if (below) {
                Brother.set(t, Brother.FREED)
                c.why(t, "brother.freed", Brother.brotherName(t))
                c.data.changed()
                Brother.finish(c, t, Ending.C)
            }
            return
        }
        // Cleared with nobody to hear the boss fall (the tales were off): they come up by themselves
        if (below && lair.cleared) {
            Brother.cleared(c, t, listOf())
            if (!t.open || Brother.flag(t, Brother.FREED)) return
        }
        if (t.step in Brother.AT_MEET..Brother.AT_DEED) BrotherMine.raise(level, lair)
    }

    /** The mine's boss fell while they were below ([Brother.cleared]): they say so, and walk up to the mouth; the tale ends C as they get there. */
    fun freed(c: Ctx, t: Tale) {
        val level = c.level
        val memo = memo(t)
        memo.freedAt = level.gameTime
        cancelEscort(level, t, memo)
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id }
        val lair = Brother.lair(level, t)
        if (!Tales.scenes() || b == null || lair == null || Comings.unseen(level, b.blockPosition())) return Brother.finish(c, t, Ending.C)
        level.getNearestPlayer(b, 16.0)?.let { b.lookAt(it) }
        Words.sayWith(b, "tale.brother.freed", why = "the old mine cleared")
        lamp(b, Brother.flag(t, Brother.ROSE))
        b.navigation.stop()
        b.director = RoadIn(listOf(lair.pos), FREED_FOR.toInt(), Activity.CARRY) { _ ->
            if (t.open) TaleEngine.ctx(level, t.capital)?.let { Brother.finish(it, t, Ending.C) }
        }
    }

    /** Freed and still open: the walk up is on, or a restart forgot it (then the tale ends now). */
    private fun freedOn(c: Ctx, t: Tale, memo: Memo) {
        if (memo.freedAt < 0 || c.level.gameTime - memo.freedAt > FREED_FOR || !Tales.scenes()) Brother.finish(c, t, Ending.C)
    }

    // ---- The one below ----

    /** From `Brother.enter`: the one below is held at their mark (or kept out of sight while it cannot be known). */
    fun below(c: Ctx, t: Tale) {
        if (!Tales.scenes() || !t.open) return
        if (Brother.where(t) != Brother.BELOW || Brother.flag(t, Brother.GONE) || Brother.flag(t, Brother.FREED)) return
        keepBelow(c, t, memo(t))
    }

    private fun brother(c: Ctx, t: Tale, memo: Memo) {
        val cast = t.cast(Brother.BROTHER) ?: return
        if (cast.resident < 0 || cast.gone.isNotEmpty()) return
        if (memo.rise?.playing == true) return
        if (Brother.flag(t, Brother.GONE)) {
            if (memo.setDown < 0) parkUnseen(c, t, memo)
            return
        }
        when (Brother.where(t)) {
            Brother.BELOW -> keepBelow(c, t, memo)
            Brother.LED, Brother.OUT -> walking(c, t, memo)
            else -> {}
        }
    }

    private fun keepBelow(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val lair = Brother.lair(level, t)
        val mine = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id }
        // The mark in the mine; while it cannot be known, the last one this had (with them about it), else none a body can be made at
        val real = lair?.let { BrotherMine.mark(level, it, Brother.side(t), c.here.capital.center) }
        val kept = memo.placed[Brother.BROTHER]?.takeIf { !BrotherMine.parked(level, it) && mine != null && mine.distanceToSqr(Vec3.atCenterOf(it)) < MARK_FAR * MARK_FAR }
        val mark = real ?: kept ?: BrotherMine.placeholder(level, lair?.pos ?: c.here.capital.center)
        // A tale body far from the mark (taken where their day had them, after a restart): out of sight it goes, in sight it leaves by the gate.
        // One a given-up leading left on the way up walks back down to it instead.
        if (memo.backSince >= 0 && (mine == null || !far(level, mine, mark) || level.gameTime - memo.backSince > LOST_FOR)) memo.backSince = -1
        if (mine != null && memo.backSince < 0 && mine.director !is TaleLeave && mine.director !is RoadIn && far(level, mine, mark)) {
            away(c, mine)
            memo.placed.remove(Brother.BROTHER)
            return
        }
        val afraid = mine != null && frightened(level, mine) && level.getNearestPlayer(mine, TaleEscort.FRIGHT_PLAYER) != null
        place(c, t, memo, Brother.BROTHER, mark, if (afraid) Activity.COWER else Activity.CARRY, go = true)
        if (mine == null || mine.director !is TaleHold) return
        lamp(mine, false)
        if (afraid && level.gameTime - memo.scaredAt >= SCARED_EVERY) {
            memo.scaredAt = level.gameTime
            Words.sayWith(mine, "tale.brother.scared", why = "one below, afraid", force = true)
        }
        found(c, t, mine)
    }

    private fun far(level: ServerLevel, b: Townsperson, mark: BlockPos): Boolean =
        if (BrotherMine.parked(level, mark)) flat(b.blockPosition(), mark) > MARK_FAR else b.distanceToSqr(Vec3.atCenterOf(mark)) > MARK_FAR * MARK_FAR

    /** [b] goes: at once where nobody sees, else out by the gate nearest them and away down its road. */
    private fun away(c: Ctx, b: Townsperson) {
        val level = c.level
        if (Comings.unseen(level, b.blockPosition())) {
            b.discard()
            return
        }
        val gate = WayOut.gateNear(c.here.places, b.blockPosition())
        if (gate == null) {
            b.discard()
            return
        }
        val front = WayOut.front(c.here.places, gate)
        val way = listOf(front) + WayOut.outward(level, c.here, front, 14)
        b.navigation.stop()
        b.carried = ItemStack.EMPTY
        b.carriedUntil = 0
        b.director = TaleLeave(way) { it.discard() }
    }

    /** A player within six blocks of them below finds them: they look up and say so, and being there is the deed of the way down (its choice too). */
    private fun found(c: Ctx, t: Tale, b: Townsperson) {
        if (Brother.flag(t, Brother.FOUND)) return
        val level = c.level
        val player = level.players().firstOrNull { it.isAlive && !it.isSpectator && it.distanceTo(b) < FOUND } ?: return
        foundBy(c, t, player, b)
    }

    /** [player] found them (below, or by `tale brother find`): the line if their body is about, and the deed of the way down. */
    fun foundBy(c: Ctx, t: Tale, player: ServerPlayer, b: Townsperson?) {
        if (!t.open) return
        if (!Brother.flag(t, Brother.FOUND)) {
            Brother.set(t, Brother.FOUND)
            c.data.changed()
            if (b != null) {
                b.lookAt(player)
                Words.sayWith(b, "tale.brother.found", why = "found below")
            }
        }
        Brother.take(c.level, t, player) ?: return
        if (t.open) Brother.deed(c, t, player, Brother.DOWN)
    }

    /** Whether the one below is on a walk with a player just now. */
    fun leading(level: ServerLevel, t: Tale): Boolean {
        val e = memos[t.id]?.escort ?: return false
        if (e.done) return false
        return TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id } != null
    }

    /** Who leads (or last led) them, if this remembers. */
    fun leaderOf(t: Tale): UUID? = memos[t.id]?.leader

    /** Whether whoever leads them is on and within [r] blocks of them. */
    fun leaderNear(level: ServerLevel, t: Tale, r: Double): Boolean {
        val b = TaleBodies.body(level, t, Brother.BROTHER) ?: return false
        val p = leader(level, t) ?: return false
        return p.distanceTo(b) < r
    }

    private fun leader(level: ServerLevel, t: Tale): ServerPlayer? {
        val id = memos[t.id]?.leader ?: Brother.partNamed(level, t, t.s[Brother.S_LEADER])?.player ?: return null
        return (level.server.playerList.getPlayer(id))?.takeIf { it.isAlive && !it.isSpectator && it.level() === level }
    }

    /**
     * "Can you walk? Come with me.": they follow [player] at their own pace (S8, [TaleEscort], their lamp in hand, cowering and crying out as a
     * hostile comes near) to the forge door. Below, they are being led now, and it is the deed of the way down. False if they cannot walk now.
     */
    fun walk(c: Ctx, t: Tale, player: ServerPlayer): Boolean {
        val level = c.level
        if (!Tales.scenes() || !t.open || Brother.flag(t, Brother.FREED) || Brother.flag(t, Brother.GONE) || Brother.where(t) >= Brother.HOME) return false
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id } ?: return false
        if (b.director is RoadIn || b.director is TaleLeave) b.director = null
        val memo = memo(t)
        cancelEscort(level, t, memo)
        val lamp = Sets.prop(if (Brother.flag(t, Brother.ROSE)) "miners_lamp_lit" else "miners_lamp_dark")
        if (!TaleEscort.start(level, t, Brother.BROTHER, b, player, Brother.forgeOr(c, t), prop = lamp, scared = "tale.brother.scared")) return false
        memo.escort = b.director as? TaleEscort
        memo.leader = player.uuid
        memo.lostSince = -1
        memo.aloneSince = -1
        Brother.unset(t, Brother.WAITS)
        Brother.unset(t, Brother.ALONE)
        if (Brother.where(t) == Brother.BELOW) Brother.setWhere(t, Brother.LED)
        Brother.set(t, Brother.FOUND)
        t.setS(Brother.S_LEADER, player.gameProfile.name)
        c.data.changed()
        Brother.take(level, t, player)
        if (!memo.walkTold && t.open) {
            memo.walkTold = true
            c.why(t, "brother.walking", player.gameProfile.name, Brother.brotherName(t))
        }
        if (t.open && t.step <= Brother.AT_CHOOSE && Brother.where(t) == Brother.LED) Brother.deed(c, t, player, Brother.DOWN)
        return true
    }

    /** "Wait here.": the walk stops where they stand, and they wait there to be asked on. False if they are on no walk. */
    fun wait(c: Ctx, t: Tale): Boolean {
        val memo = memos[t.id] ?: return false
        if (memo.escort?.done != false) return false
        cancelEscort(c.level, t, memo)
        Brother.set(t, Brother.WAITS)
        c.data.changed()
        return true
    }

    /** The walk is called off (they find their own way, the tale is over). */
    fun cancelWalk(level: ServerLevel, t: Tale) {
        memos[t.id]?.let { cancelEscort(level, t, it) }
    }

    private fun cancelEscort(level: ServerLevel, t: Tale, memo: Memo) {
        val e = memo.escort ?: return
        memo.escort = null
        if (e.done) return
        val b = TaleBodies.body(level, t, Brother.BROTHER) ?: return
        e.cancel(b)
    }

    /** Being led, or out of the mine: up at the mouth, the walk taken up again or given up, or home by themselves. */
    private fun walking(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val now = level.gameTime
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id }
        if (b == null) {
            // Not about (their land unloaded, or a restart): a leading below with nobody about them for a minute is given up
            if (Brother.where(t) == Brother.LED) lost(c, t, memo, now)
            return
        }
        if (b.director is TaleLeave) return
        val attended = level.getNearestPlayer(b, ATTENDED) != null
        val e = memo.escort?.takeIf { !it.done }
        if (e != null) {
            val lair = Brother.lair(level, t)
            if (Brother.where(t) == Brother.LED && lair != null && BrotherMine.surfaced(lair, b.blockPosition())) Brother.up(c, t, memo.leader)
            // A walk with nobody near (the leader logged off, or went on out of reach) is called off after a minute
            if (attended) memo.lostSince = -1 else if (memo.lostSince < 0) memo.lostSince = now else if (now - memo.lostSince > LOST_FOR) {
                memo.lostSince = -1
                cancelEscort(level, t, memo)
            }
            return
        }
        if (Brother.flag(t, Brother.ALONE)) return lone(c, t, memo, b)
        if (b.director is RoadIn) return
        if (b.director is TaleHold) lamp(b, Brother.flag(t, Brother.ROSE))
        // The walk broken off (a word, a restart): the leader near again takes it up
        val lead = leader(level, t)
        if (!Brother.flag(t, Brother.WAITS) && lead != null && lead.distanceTo(b) < LEAD_ON && b.attending == null && b.director is TaleHold) {
            walk(c, t, lead)
            return
        }
        if (Brother.where(t) == Brother.LED) {
            if (attended) memo.lostSince = -1 else lost(c, t, memo, now)
            return
        }
        // Up and out with nobody near: they set off home by themselves
        if (attended || Brother.flag(t, Brother.WAITS)) {
            memo.aloneSince = -1
            return
        }
        if (memo.aloneSince < 0) memo.aloneSince = now else if (now - memo.aloneSince > LONE_AFTER) {
            memo.aloneSince = -1
            Brother.alone(c, t, null)
        }
    }

    /** A leading below with nobody about for [LOST_FOR]: it is given up, and they wait at their mark again (to be found and led again). */
    private fun lost(c: Ctx, t: Tale, memo: Memo, now: Long) {
        if (memo.lostSince < 0) {
            memo.lostSince = now
            return
        }
        if (now - memo.lostSince < LOST_FOR) return
        memo.lostSince = -1
        cancelEscort(c.level, t, memo)
        Brother.setWhere(t, Brother.BELOW)
        Brother.unset(t, Brother.WAITS)
        c.data.changed()
        memo.placed.remove(Brother.BROTHER)
        memo.backSince = if (TaleBodies.body(c.level, t, Brother.BROTHER)?.takeIf { it.tale == t.id } != null) now else -1L
    }

    /**
     * Home by themselves: along the road at a hobble (and on through the streets to the forge door) while anyone can see them; gone from sight
     * as soon as nobody can (held out of the world: the record has them at the door by first light). At the door they are home.
     */
    private fun lone(c: Ctx, t: Tale, memo: Memo, b: Townsperson) {
        val level = c.level
        if (b.attending != null || b.director is RoadIn || b.director is TaleEscort) return
        val door = Brother.forgeOr(c, t)
        if (flat(b.blockPosition(), door) < AT_DOOR) return Brother.arrived(c, t)
        if (Comings.unseen(level, b.blockPosition())) return park(c, t, memo, b)
        if (level.gameTime < memo.loneAfter) return
        val center = c.here.capital.center
        val inTown = flat(b.blockPosition(), center) < flat(Brother.front(c, t), center)
        val way = (if (inTown) listOf() else WayOut.homeward(level, c.here, b.blockPosition())) + door
        lamp(b, Brother.flag(t, Brother.ROSE))
        b.navigation.stop()
        b.director = RoadIn(way, LONE_FOR, Activity.HOBBLE) { p ->
            if (!t.open) return@RoadIn
            val cc = TaleEngine.ctx(level, t.capital) ?: return@RoadIn
            if (flat(p.blockPosition(), door) < AT_DOOR) {
                Brother.arrived(cc, t)
                return@RoadIn
            }
            // A way that would not go: they stand where they got to, and it is tried again in a while (or they go once nobody sees)
            memos[t.id]?.let { m ->
                m.loneAfter = level.gameTime + LONE_AGAIN
                if (Tales.hold(level, t, Brother.BROTHER, p.blockPosition(), null)) m.placed[Brother.BROTHER] = p.blockPosition()
            }
        }
    }

    /** [b] is gone from where nobody sees them, held where no body is made (the record has them on their way). */
    private fun park(c: Ctx, t: Tale, memo: Memo, b: Townsperson) {
        val level = c.level
        val at = BrotherMine.placeholder(level, b.blockPosition())
        if (Tales.hold(level, t, Brother.BROTHER, at, null)) {
            memo.placed[Brother.BROTHER] = at
            memo.clips.remove(Brother.BROTHER)
        }
        b.carried = ItemStack.EMPTY
        b.discard()
    }

    /** The party has them: a body still about is gone once nobody sees it. */
    private fun parkUnseen(c: Ctx, t: Tale, memo: Memo) {
        val b = TaleBodies.body(c.level, t, Brother.BROTHER)?.takeIf { it.tale == t.id } ?: return
        if (b.director is TaleHold && Comings.unseen(c.level, b.blockPosition())) park(c, t, memo, b)
    }

    /** From `Brother.kindled`: the mine's fire burns; the one below is seen to make for it a moment after. */
    fun lit(c: Ctx, t: Tale) {
        memo(t).litAt = c.level.gameTime
    }

    /** From `Brother.partyGoes`: the party goes down off stage; the delver goes with it, and the one below is gone once nobody sees them. */
    fun partyGone(c: Ctx, t: Tale) {
        val level = c.level
        val memo = memo(t)
        cancelEscort(level, t, memo)
        if (t.cast(Brother.DELVER) != null) Tales.release(level, t, Brother.DELVER)
        memo.delver = false
        if (!Tales.scenes()) return
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id }
        if (b != null && !Comings.unseen(level, b.blockPosition())) {
            if (Tales.hold(level, t, Brother.BROTHER, b.blockPosition(), null)) memo.placed[Brother.BROTHER] = b.blockPosition()
            return
        }
        b?.discard()
        val at = BrotherMine.placeholder(level, Brother.lair(level, t)?.pos ?: c.here.capital.center)
        if (Tales.hold(level, t, Brother.BROTHER, at, null)) memo.placed[Brother.BROTHER] = at
    }

    // ---- The rise (C) ----

    private fun rise(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        memo.rise?.let { st ->
            if (!st.finished) return
            memo.rise = null
            // Up to the fire: the record has it, and they are held where the scene left them
            Brother.rose(c, t)
            val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id }
            if (b != null && t.open && Tales.hold(level, t, Brother.BROTHER, b.blockPosition(), null)) memo.placed[Brother.BROTHER] = b.blockPosition()
            return
        }
        if (!Brother.flag(t, Brother.LIT) || Brother.flag(t, Brother.ROSE) || Brother.where(t) != Brother.BELOW) return
        if (memo.litAt >= 0 && level.gameTime - memo.litAt < RISE_AFTER) return
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id && it.director is TaleHold && it.attending == null } ?: return
        if (level.getNearestPlayer(b, NEAR) == null) return
        val fire = Brother.fireAt(level, t) ?: return
        val kindler = Brother.partNamed(level, t, t.s[Brother.S_KINDLER])?.player?.let { level.server.playerList.getPlayer(it) }
        val player = kindler?.takeIf { it.isAlive && it.level() === level && it.distanceTo(b) < 48f } ?: level.getNearestPlayer(b, 48.0) as? ServerPlayer
        val st = BrotherScenes.rise(b, fire, player) ?: return
        if (!st.start(level)) return
        TaleLook.note(st)
        memo.rise = st
    }

    // ---- The holds ----

    private fun holds(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val now = Brother.clock(level)
        val front = Brother.front(c, t)
        val below = Brother.where(t) <= Brother.LED && !Brother.flag(t, Brother.GONE) && !Brother.flag(t, Brother.FREED)
        val asks = Brother.askerOf(t)
        // The smith (or whoever of the house waits in their place) at the gate while they are below, and at the forge door at the last step
        val door = Brother.forgeOr(c, t)
        val smith = when {
            t.step <= Brother.AT_DEED && asks == Brother.SMITH && below && now in Brother.CALL_FROM until Brother.GATE_TO && near(level, front, NEAR) -> front
            t.step == Brother.AT_HOME && atDoor(c, t, memo, now) && near(level, door, NEAR) -> door
            else -> null
        }
        place(c, t, memo, Brother.SMITH, smith, Activity.LOOKOUT)
        val captain = t.step <= Brother.AT_DEED && below && (t.step == Brother.AT_MEET || asks == Brother.CAPTAIN) &&
            now in Brother.CAPTAIN_FROM until Brother.CAPTAIN_TO && near(level, front, NEAR)
        place(c, t, memo, Brother.CAPTAIN, if (captain) Brother.captainAt(c, t) else null, Activity.STAND_GUARD)
    }

    /** Whether the smith waits at the forge door at the last step: with them home or on their way, and from 5:00 on the day of a dawn. */
    private fun atDoor(c: Ctx, t: Tale, memo: Memo, now: Int): Boolean {
        if (Brother.where(t) == Brother.HOME || leading(c.level, t) || memo.setDown >= 0 || memo.forced) return true
        if (!Brother.flag(t, Brother.GONE) && !Brother.flag(t, Brother.ALONE) && !Brother.flag(t, Brother.ROSE)) return false
        val day = t.n[Brother.N_DAY].toLong()
        return c.day > day || c.day == day && now >= Brother.DOOR_FROM
    }

    /** The delvers' leader at the tavern's door (a figure, two blocks out toward the forge) from 6:00 to 20:00 while the fee can be paid. */
    private fun delver(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        if (memo.setDown >= 0) return
        val now = Brother.clock(level)
        val wanted = t.step in Brother.AT_MEET..Brother.AT_DEED && Brother.where(t) == Brother.BELOW && !Brother.flag(t, Brother.GONE) &&
            !Brother.flag(t, Brother.FREED) && now in Brother.DELVER_FROM until Brother.DELVER_TO
        val door = if (wanted) Brother.tavern(c, t) else null
        if (door != null) {
            val forge = Brother.forgeOr(c, t)
            val at = Sets.beside(level, Vec3.atCenterOf(door), Vec3.atCenterOf(forge), 2.0) ?: door
            if (TaleBodies.figure(level, t, Brother.DELVER, at, TownRole.CITIZEN, clip = Activity.ARMS_FOLDED, yaw = yawTo(at, forge))) memo.delver = true
            return
        }
        if (memo.delver) {
            memo.delver = false
            Tales.release(level, t, Brother.DELVER)
        }
    }

    /**
     * Holds [slot] at [mark] with [clip] (or lets them go to their day, for a null mark), once for each mark: a day body far from the mark that a
     * player could see go is left to its day and tried again, unless it is to [go] (the one below, whose day does not run while they are held).
     */
    private fun place(c: Ctx, t: Tale, memo: Memo, slot: String, mark: BlockPos?, clip: Activity?, go: Boolean = false) {
        val level = c.level
        val cast = t.cast(slot) ?: return
        if (cast.resident < 0 || cast.gone.isNotEmpty()) return
        if (mark == null) {
            if (memo.placed.remove(slot) != null) {
                memo.clips.remove(slot)
                Tales.release(level, t, slot)
            }
            return
        }
        if (memo.placed[slot] == mark && memo.clips[slot] == clip && slot in memo.clips) return
        val body = TaleBodies.body(level, t, slot)
        if (!go && body != null && body.tale != t.id && body.distanceToSqr(Vec3.atCenterOf(mark)) > MARK_FAR * MARK_FAR && !Comings.unseen(level, body.blockPosition())) return
        if (Tales.hold(level, t, slot, mark, clip)) {
            memo.placed[slot] = mark
            memo.clips[slot] = clip
        }
    }

    // ---- The last step: home ----

    private fun home(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val now = level.gameTime
        memo.stage?.let { st ->
            // Queued: its own end ends the tale; one that never comes off, or runs on far too long, is the record's
            if (now - memo.together < STAGE_WAITS * 2) return
            if (!st.finished) st.cancel(level)
            memo.stage = null
            return Brother.finish(c, t, Brother.outcome(t))
        }
        when {
            Brother.where(t) == Brother.HOME -> reunion(c, t, memo)
            leading(level, t) -> {}
            Brother.flag(t, Brother.GONE) -> dawnBack(c, t, memo, true, Brother.A_DAWN)
            Brother.flag(t, Brother.ALONE) || Brother.flag(t, Brother.ROSE) ->
                dawnBack(c, t, memo, false, if (Brother.flag(t, Brother.ROSE)) Brother.C_DAWN else Brother.A_DAWN)
        }
    }

    /** Home at the forge door (walked there, or found their way): the homecoming, then the end; with the scenes off or nobody to meet them, the end. */
    private fun reunion(c: Ctx, t: Tale, memo: Memo) {
        val level = c.level
        val now = level.gameTime
        if (!Tales.scenes() || !waits(t)) return Brother.finish(c, t, Brother.outcome(t))
        if (memo.together < 0) memo.together = now
        if (now - memo.together > STAGE_WAITS) return Brother.finish(c, t, Brother.outcome(t))
        val smith = TaleBodies.body(level, t, Brother.SMITH)?.takeIf { it.tale == t.id } ?: return
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id } ?: return
        if (smith.distanceTo(b) > 32f || smith.attending != null || b.attending != null) return
        val watchers = Sets.freeAdults(level, smith.position(), 16.0, listOf(smith, b))
        val stage = BrotherScenes.homecoming(smith, b, thanked(level, t, smith), watchers) ?: return Brother.finish(c, t, Brother.outcome(t))
        queue(c, t, memo, stage)
    }

    /** Whether someone of the house waits at the forge door (the smith, or whoever stood in for them). */
    private fun waits(t: Tale): Boolean = t.cast(Brother.SMITH)?.let { it.resident >= 0 && it.gone.isEmpty() } == true

    /** Whoever the smith thanks: the one whose deed it was (else the leader), if they are on and within 32 blocks. */
    private fun thanked(level: ServerLevel, t: Tale, near: Townsperson): ServerPlayer? {
        val id = t.parts.firstOrNull { it.did(Part.DECIDED) }?.player ?: memos[t.id]?.leader ?: return null
        return level.server.playerList.getPlayer(id)?.takeIf { it.isAlive && !it.isSpectator && it.level() === level && it.distanceTo(near) < 32f }
    }

    private fun queue(c: Ctx, t: Tale, memo: Memo, stage: Stage) {
        val level = c.level
        stage.then = { _ -> if (t.open && t.step == Brother.AT_HOME) TaleEngine.ctx(level, t.capital)?.let { Brother.finish(it, t, Brother.outcome(t)) } }
        if (TaleLook.queue(level, t.capital, stage, urgent = true, hears = true)) {
            memo.stage = stage
            if (memo.together < 0) memo.together = level.gameTime
        }
    }

    /**
     * The dawn of the Return ([delvers]) or of the walk home of one who came up alone or to the fire, from [hour] on the market day the record
     * names: with a player within 64 blocks of the forge door and the scenes on they are set down on a street out of sight and walk to the door;
     * with nobody near by 10:00 (or a day late) the record has them home. One the player is still with, out at the mine, walks home instead.
     */
    private fun dawnBack(c: Ctx, t: Tale, memo: Memo, delvers: Boolean, hour: Int) {
        val level = c.level
        val day = t.n[Brother.N_DAY].toLong()
        val clock = Brother.clock(level)
        val today = c.day
        val due = memo.forced || today > day || today == day && clock >= hour
        if (!due) return
        val late = !memo.forced && (today > day || clock >= Brother.DAWN_TO)
        val door = Brother.forgeOr(c, t)
        if (memo.setDown < 0) {
            if (late || !Tales.scenes() || !waits(t)) return Brother.finish(c, t, Brother.outcome(t))
            if (!near(level, door, NEAR)) {
                if (memo.forced) Brother.finish(c, t, Brother.outcome(t))
                return
            }
            if (!delvers) {
                // Still out by the mine with someone watching them: they walk home themselves, and are home when they get there
                val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id }
                if (b != null && flat(b.blockPosition(), door) > MARK_FAR && !Comings.unseen(level, b.blockPosition())) {
                    if (!Brother.flag(t, Brother.ALONE)) Brother.alone(c, t, null)
                    return
                }
            }
            setDown(c, t, memo, delvers)
            return
        }
        if (level.gameTime - memo.setDown > SET_DOWN_FOR || late && level.gameTime - memo.setDown > STAGE_WAITS) return Brother.finish(c, t, Brother.outcome(t))
        val smith = TaleBodies.body(level, t, Brother.SMITH)?.takeIf { it.tale == t.id } ?: return
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id } ?: return
        val spot = memo.downAt ?: return
        if (b.distanceToSqr(Vec3.atCenterOf(spot)) > 4.0 * 4.0 || smith.distanceToSqr(Vec3.atCenterOf(door)) > AT_DOOR * AT_DOOR) return
        if (smith.attending != null || b.attending != null) return
        val stage = (if (delvers) {
            val lead = TaleBodies.body(level, t, Brother.DELVER) ?: return
            for (d in listOfNotNull(lead, TaleBodies.body(level, t, Brother.DELVER2), TaleBodies.body(level, t, Brother.DELVER3))) torch(d, d === lead)
            BrotherScenes.ret(smith, b, lead, TaleBodies.body(level, t, Brother.DELVER2), TaleBodies.body(level, t, Brother.DELVER3), thanked(level, t, smith), spot)
        } else {
            BrotherScenes.homecoming(smith, b, thanked(level, t, smith), Sets.freeAdults(level, smith.position(), 16.0, listOf(smith, b)))
        }) ?: return Brother.finish(c, t, Brother.outcome(t))
        queue(c, t, memo, stage)
    }

    /** The delvers come up the street with their torches still lit, and a coil of rope. */
    private fun torch(d: Townsperson, lead: Boolean) {
        if (!d.carried.isEmpty) return
        d.carried = ItemStack(if (lead || d.tickCount % 2 == 0) Items.TORCH else Items.LEAD)
        d.carriedUntil = 0
    }

    /** The set-down of a dawn: the one brought home on a street out of sight 16 to 40 blocks from the forge door (the delvers about them). */
    private fun setDown(c: Ctx, t: Tale, memo: Memo, delvers: Boolean): Boolean {
        val level = c.level
        val door = Brother.forgeOr(c, t)
        val spot = street(level, door, Brother.front(c, t)) ?: return false
        TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id && Comings.unseen(level, it.blockPosition()) }?.discard()
        if (!Tales.hold(level, t, Brother.BROTHER, spot, null)) return false
        memo.placed[Brother.BROTHER] = spot
        memo.clips[Brother.BROTHER] = null
        if (delvers) {
            var ax = (door.x - spot.x).toDouble()
            var az = (door.z - spot.z).toDouble()
            val len = sqrt(ax * ax + az * az).coerceAtLeast(0.1)
            ax /= len
            az /= len
            val yaw = yawTo(spot, door)
            fun at(along: Double, across: Double): BlockPos =
                Sets.standable(level, BlockPos.containing(spot.x + 0.5 + ax * along - az * across, spot.y.toDouble(), spot.z + 0.5 + az * along + ax * across)) ?: spot
            TaleBodies.figure(level, t, Brother.DELVER, at(1.6, 0.6), TownRole.CITIZEN, clip = Activity.CARRY, yaw = yaw)
            TaleBodies.figure(level, t, Brother.DELVER2, at(-0.8, 1.5), TownRole.GUARD, clip = Activity.CARRY, yaw = yaw)
            TaleBodies.figure(level, t, Brother.DELVER3, at(-1.2, -1.5), TownRole.GUARD, clip = Activity.CARRY, yaw = yaw)
        }
        memo.setDown = level.gameTime
        memo.downAt = spot
        return true
    }

    /**
     * A spot on a street (under the open sky) 16 to 40 blocks from [door] that no player can see, the nearer the way to the [gate] the better:
     * eight ways round, the farthest first. Null if there is none just now.
     */
    private fun street(level: ServerLevel, door: BlockPos, gate: BlockPos): BlockPos? {
        val toward = atan2((gate.z - door.z).toDouble(), (gate.x - door.x).toDouble())
        for (turn in intArrayOf(0, 1, -1, 2, -2, 3, -3, 4)) {
            val a = toward + turn * Math.PI / 4.0
            for (d in intArrayOf(40, 34, 28, 22, 16)) {
                val x = door.x + 0.5 + cos(a) * d
                val z = door.z + 0.5 + sin(a) * d
                val at = Sets.standable(level, BlockPos.containing(x, door.y.toDouble(), z)) ?: continue
                if (!level.canSeeSky(at) || !Comings.unseen(level, at)) continue
                return at
            }
        }
        return null
    }

    /** `tale brother dawn`: the dawn of [t] is now, whatever the hour; false if there is none to come (nobody is on the way home). */
    fun dawn(c: Ctx, t: Tale): Boolean {
        if (!t.open || t.step != Brother.AT_HOME || Brother.where(t) == Brother.HOME) return false
        if (!Brother.flag(t, Brother.GONE) && !Brother.flag(t, Brother.ALONE) && !Brother.flag(t, Brother.ROSE)) return false
        val memo = memo(t)
        memo.forced = true
        t.n[Brother.N_DAY] = c.day.toInt()
        c.data.changed()
        return true
    }

    /**
     * `tale brother home`: the one brought home is held three blocks from the forge door on the gate's side; a body far off that nobody sees
     * is gone from there first, to be made at the door as a player comes.
     */
    fun bringHome(c: Ctx, t: Tale) {
        if (!Tales.scenes() || !t.open) return
        val level = c.level
        val memo = memo(t)
        cancelEscort(level, t, memo)
        val door = Brother.forgeOr(c, t)
        val at = Sets.beside(level, Vec3.atCenterOf(door), Vec3.atCenterOf(Brother.front(c, t)), 3.0) ?: door
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id }
        if (b != null) {
            if (b.director is RoadIn || b.director is TaleLeave) b.director = null
            if (b.distanceToSqr(Vec3.atCenterOf(at)) > MARK_FAR * MARK_FAR && Comings.unseen(level, b.blockPosition())) b.discard()
        }
        if (Tales.hold(level, t, Brother.BROTHER, at, null)) {
            memo.placed[Brother.BROTHER] = at
            memo.clips[Brother.BROTHER] = null
        }
    }

    // ---- After the end ----

    private fun after(level: ServerLevel) {
        val data = TaleData.get(level)
        for (t in data.all()) {
            if (t.template != Brother.id || t.open || t.ended == TaleData.NEVER) continue
            when (t.ending) {
                Ending.W -> if (Tales.scenes()) laying(level, t)
                Ending.A, Ending.B -> lantern(level, t)
            }
        }
        if (layings.size > 8) layings.keys.retainAll(data.all().filter { it.template == Brother.id && !Brother.flag(it, Brother.LAID) }.map { it.id }.toSet())
    }

    /** W: the laying at the stone by the forge door, at dusk on the day after the end or the one after, with a player near and the smith by it. */
    private fun laying(level: ServerLevel, t: Tale) {
        if (Brother.flag(t, Brother.LAID)) return
        val c = TaleEngine.ctx(level, t.capital) ?: return
        if (c.day < t.ended + 1 || c.day > t.ended + 2) return
        if (Brother.clock(level) !in LAY_FROM until LAY_TO) return
        val l = layings.getOrPut(t.id) { Laying() }
        l.stage?.let {
            if (!it.finished) return
            l.stage = null
        }
        if (level.gameTime < l.next) return
        l.next = level.gameTime + LAY_EVERY
        val stone = Memorials.of(c, t).firstOrNull { it.kind == "stone" }?.pos ?: Brother.forge(t) ?: return
        if (!near(level, stone, 48.0)) return
        val stage = BrotherScenes.layingOf(c, t) ?: return
        stage.then = { played ->
            if (played) {
                Brother.set(t, Brother.LAID)
                TaleData.get(level).changed()
            }
        }
        if (TaleLook.queue(level, t.capital, stage, urgent = false, hears = true)) l.stage = stage
    }

    /** A and B: a lantern hung beside the forge door (else stood there), set while nobody sees the door; skipped for good if there is no room. */
    private fun lantern(level: ServerLevel, t: Tale) {
        if (Brother.flag(t, Brother.LAMPED) || !Tales.memorials()) return
        val door = Brother.forge(t) ?: return
        if (!level.isLoaded(door) || !Comings.unseen(level, door)) return
        val at = lampSpot(level, door)
        if (at != null) level.setBlock(at.first, at.second, 3)
        Brother.set(t, Brother.LAMPED)
        TaleData.get(level).changed()
    }

    /** Beside the door outside [front]: hung under the eaves at head height, else stood on the step; with the state to set. */
    private fun lampSpot(level: ServerLevel, front: BlockPos): Pair<BlockPos, net.minecraft.world.level.block.state.BlockState>? {
        val house = Direction.Plane.HORIZONTAL.firstOrNull { d -> level.getBlockState(front.relative(d)).block is DoorBlock }
        val sides = if (house != null) listOf(house.clockWise, house.counterClockWise) else Direction.Plane.HORIZONTAL.toList()
        val hanging = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true)
        val standing = Blocks.LANTERN.defaultBlockState()
        for (s in sides) {
            val up = front.relative(s).above(2)
            if (level.isLoaded(up) && level.getBlockState(up).isAir && hanging.canSurvive(level, up)) return up to hanging
        }
        for (s in sides) {
            val low = front.relative(s)
            if (level.isLoaded(low) && level.getBlockState(low).isAir && level.getBlockState(low.above()).isAir && standing.canSurvive(level, low)) return low to standing
        }
        return null
    }

    // ---- The end ----

    /** The tale is ending: the walks and the scenes of this watch are called off, and the lamp is put away (the engine lets the holds go after). */
    fun letGo(c: Ctx, t: Tale) {
        val level = c.level
        val memo = memos[t.id]
        if (memo != null) {
            cancelEscort(level, t, memo)
            memo.rise?.let { if (!it.finished) it.cancel(level) }
            memo.rise = null
            memo.stage?.let { if (it.playing) it.cancel(level) else it.then = null }
            memo.stage = null
        }
        val b = TaleBodies.body(level, t, Brother.BROTHER) ?: return
        if (b.director is RoadIn) b.director = null
        if (isLamp(b.carried)) {
            b.carried = ItemStack.EMPTY
            b.carriedUntil = 0
        }
    }

    /** W: the one below goes on into the dark (along the halls past the first, while anyone sees them), else is gone where they are. */
    fun intoTheDark(c: Ctx, t: Tale) {
        val level = c.level
        val b = TaleBodies.body(level, t, Brother.BROTHER)?.takeIf { it.tale == t.id } ?: return
        val lair = Brother.lair(level, t)
        if (lair == null || !Tales.scenes() || Comings.unseen(level, b.blockPosition()) || BrotherMine.parked(level, memos[t.id]?.placed?.get(Brother.BROTHER))) {
            b.discard()
            return
        }
        lamp(b, false)
        b.navigation.stop()
        b.director = TaleLeave(BrotherMine.deeper(level, lair)) { it.discard() }
    }

    /** [slot] of tale [id] is someone else now, or gone: its mark is worked out again. */
    fun forget(id: Int, slot: String) {
        memos[id]?.let {
            it.placed.remove(slot)
            it.clips.remove(slot)
        }
    }

    /** [id] ended: nothing of its watch is kept (the laying's tries are, until it is laid). */
    fun ended(id: Int) {
        memos.remove(id)
        BrotherScenes.ended(id)
    }

    /** The scenes went off: the bodies are the engine's to let go, and everything is placed again when they come back. */
    private fun pause(level: ServerLevel) {
        for (m in memos.values) {
            m.placed.clear()
            m.clips.clear()
            m.escort = null
            m.delver = false
            m.rise?.let { if (!it.finished) it.cancel(level) }
            m.rise = null
            m.stage = null
            m.together = -1
            m.setDown = -1
            m.downAt = null
        }
    }

    /** For `tale brother`: what the watch has in hand for [t], in a few words. */
    fun state(t: Tale): String {
        val m = memos[t.id] ?: return "nothing yet"
        val out = ArrayList<String>()
        if (m.placed.isNotEmpty()) out += "holding " + m.placed.entries.joinToString { (slot, at) -> "$slot at ${if (at.y < -60) "nowhere (out of sight)" else at.toShortString()}" }
        if (m.escort?.done == false) out += "the walk is on"
        if (m.lostSince >= 0) out += "nobody near the walk"
        if (m.aloneSince >= 0) out += "nobody near them out of the mine"
        if (m.rise?.playing == true) out += "the rise is playing"
        if (m.freedAt >= 0) out += "walking up out of the cleared mine"
        if (m.delver) out += "the delver waits at the tavern"
        m.downAt?.let { out += "set down at ${it.toShortString()} for the dawn" }
        if (m.stage != null) out += "the ${m.stage?.name} is on the stage"
        if (m.forced) out += "the dawn is now (tale brother dawn)"
        return out.joinToString("; ").ifEmpty { "nothing yet" }
    }

    private fun forgetAll() {
        memos.clear()
        layings.clear()
        BrotherScenes.forget()
    }

    // ---- Small helpers ----

    /** The lamp in their hand: dark below, lit once they came up to the fire. */
    private fun lamp(b: Townsperson, lit: Boolean) {
        val stack = Sets.prop(if (lit) "miners_lamp_lit" else "miners_lamp_dark")
        if (!ItemStack.isSameItemSameComponents(b.carried, stack)) {
            b.carried = stack
            b.carriedUntil = 0
        }
    }

    private fun isLamp(stack: ItemStack): Boolean =
        !stack.isEmpty && (ItemStack.isSameItemSameComponents(stack, Sets.prop("miners_lamp_dark")) || ItemStack.isSameItemSameComponents(stack, Sets.prop("miners_lamp_lit")))

    private fun frightened(level: ServerLevel, b: Townsperson): Boolean =
        level.getEntitiesOfClass(Mob::class.java, b.boundingBox.inflate(FRIGHT)) { it is Enemy && it.isAlive }.isNotEmpty()

    private fun near(level: ServerLevel, at: BlockPos, r: Double): Boolean =
        level.players().any { it.isAlive && !it.isSpectator && it.distanceToSqr(Vec3.atCenterOf(at)) < r * r }

    private fun flat(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    private fun yawTo(from: BlockPos, to: BlockPos): Float =
        (Math.toDegrees(atan2((to.z - from.z).toDouble(), (to.x - from.x).toDouble())) - 90.0).toFloat()
}
