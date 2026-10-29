package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.outlaw.Captives
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Event
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.ComingsData
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import java.util.UUID

/**
 * The Fourth Wagon's hand in the band's camp (living-world/stories/design.md 8.3 and 10.2): a town's resident the band holds as
 * `Captives` holds anyone it takes, knelt by a stake, freed by a player's knife or the band's own word, and walked home behind
 * whoever freed them. The two calls of `Captives` (`homecoming` and `onDeath`, through `Tales.captiveHome` and `Tales.captiveDied`)
 * come here and are told to the tale whose cast has the resident (`Event.Captive`).
 *
 * **One body.** The hand has one body at a time, and the record (`Resident.uuid`) names it: their day body until they are taken, the
 * body knelt in the camp from then (`Captive.resident`; `Captives` makes it whenever a player comes within reach of the camp), and the
 * same body walking home and living at home again once the band lets them go ([restore]). Each time it changes the old one is someone
 * gone (`ComingsData.Town.renewed`): dropped out of sight now, or as its land loads. The hand is never held in the cast while the band has
 * them (a held resident's body that is no tale's is dropped as it loads, and the camp's is no tale's); being in the cast is what keeps
 * their household from leaving.
 */
internal object WagonCamp {
    /** Who brought each resident home, as `Captives.homecoming` had it, for the tale to read as it hears of it (by resident). */
    private val rescuers = HashMap<Int, UUID>()

    /** The hands of open wagon tales, whom nothing can harm while the band has them or they walk home (refreshed by [WagonWatch]). */
    val guarded = HashSet<Int>()

    /** Bodies that are no one's now, dropped as soon as no player can see them (a camp's captive the band moved on, an old day body walking out). */
    private val drops = HashSet<UUID>()

    /** From `Captives.homecoming`: captive [p] is home; the tale that has their resident hears of it. */
    fun home(level: ServerLevel, p: Townsperson) {
        if (!Tales.live || p.resident < 0) return
        p.rescuer?.let { rescuers[p.resident] = it }
        Tales.tell(level, Event.Captive(p.resident, true))
        rescuers.remove(p.resident)
    }

    /** From `Captives.onDeath`: captive [p] died in the band's hands; the tale that has their resident hears of it. */
    fun died(level: ServerLevel, p: Townsperson) {
        if (!Tales.live || p.resident < 0) return
        Tales.tell(level, Event.Captive(p.resident, false))
    }

    /** The player who brought [resident] home, while the tale is hearing of it. */
    fun rescuer(resident: Int): UUID? = rescuers[resident]

    // ---- Taken ----

    /**
     * The band of [t] takes the hand (the tale's first step): a captive of the band's whose home is the town's board (so they are walked
     * home to it, and the town is named when they are freed), their record's body from now on; their day body is gone, out of sight now
     * or walking out by the wagon's gate. False if the band is broken or the hand is not to be had.
     */
    fun take(c: Ctx, t: Tale): Boolean {
        val level = c.level
        val band = Wagon.band(level, t)?.takeIf { it.built && !it.slain } ?: return false
        val r = c.resident(t, Wagon.HAND)?.takeIf { it.alive } ?: return false
        val home = Wagon.board(level, t) ?: Wagon.front(c, t)
        val old = Comings.body(level, r)
        val captive = band.captives.firstOrNull { it.resident == r.id && !it.freed }
            ?: Captives.take(level, band, r.name, home, r.role, r.look, r.id)
        renew(level, r, captive.id)
        Comings.met(level, c.capital, listOf(r.id))
        old?.let { walkOut(c, t, it) }
        Wagon.set(t, Wagon.TAKEN)
        c.data.changed()
        return true
    }

    /** [r]'s body is the one with [id] from now on (or, with a fresh id, none until one is made): any other body of theirs is someone gone. */
    private fun renew(level: ServerLevel, r: Resident, id: UUID) {
        val residents = Residents.get(level)
        r.uuid = id
        residents.add(r)
        val data = ComingsData.get(level)
        data.town(r.capital.asLong()).renewed.add(r.id)
        data.changed()
    }

    /** [b], the hand's old day body, is gone: at once if nobody can see it, else it walks out by the wagon's gate and is gone down the road. */
    private fun walkOut(c: Ctx, t: Tale, b: Townsperson) {
        val level = c.level
        b.resident = -1
        if (Comings.unseen(level, b.blockPosition())) {
            b.discard()
            return
        }
        if (b.isSleeping) b.stopSleeping()
        if (b.seat != null) b.standUp()
        b.routine.interrupt()
        b.navigation.stop()
        val front = Wagon.front(c, t)
        val way = listOf(front) + WayOut.outward(level, c.here, front, 24)
        b.director = io.github.vaspyyy.guildmark.tales.TaleLeave(way) { it.discard() }
        drops += b.uuid
    }

    // ---- Let go ----

    /** The captive record of [t]'s hand, if the band still has one, and the band. */
    private fun record(level: ServerLevel, t: Tale): Pair<io.github.vaspyyy.guildmark.outlaw.OutlawBand, io.github.vaspyyy.guildmark.outlaw.Captive>? {
        val id = t.resident(Wagon.HAND)
        if (id < 0) return null
        for (band in OutlawCamps.get(level).bands) {
            val captive = band.captives.firstOrNull { it.resident == id } ?: continue
            return band to captive
        }
        return null
    }

    /** Whether the band's record says the hand is free (cut loose, or let go) though the tale has not heard it yet. */
    fun freedOnRecord(level: ServerLevel, t: Tale): Boolean = record(level, t)?.second?.freed == true

    /** The band's record says the hand is free, with no body to see it done (`tale wagon free` far from the camp): false if it has no record of them. */
    fun freeOnRecord(level: ServerLevel, t: Tale): Boolean {
        val (_, captive) = record(level, t) ?: return false
        captive.freed = true
        OutlawCamps.get(level).setDirty()
        return true
    }

    /** Whether the band still has the hand on its record (held, or freed and walking home). */
    fun held(level: ServerLevel, t: Tale): Boolean = record(level, t) != null

    /** The hand's body in the camp, knelt and bound, if it is about. */
    fun bound(level: ServerLevel, t: Tale): Townsperson? {
        val r = Residents.get(level)[t.resident(Wagon.HAND)] ?: return null
        return Comings.body(level, r)?.takeIf { it.bound }
    }

    /** The player who cut the hand loose, as their body has it (null once home, or for a body not about). */
    fun cutter(level: ServerLevel, t: Tale): UUID? {
        val r = Residents.get(level)[t.resident(Wagon.HAND)] ?: return null
        return Comings.body(level, r)?.rescuer
    }

    /**
     * The band lets the hand go on its rider's word (B): they get up, keep close behind [payer] (or the nearest player to them, if the payer
     * is far or gone) and are walked home as anyone cut loose is. False while their body is not about, or nobody is near to follow.
     */
    fun free(level: ServerLevel, t: Tale, payer: UUID?): Boolean {
        val body = bound(level, t) ?: return false
        val player = (payer?.let { level.getPlayerByUUID(it) } as? ServerPlayer)?.takeIf { it.isAlive && it.distanceTo(body) < 96.0 }
            ?: (level.getNearestPlayer(body, 64.0) as? ServerPlayer)
            ?: return false
        Captives.interact(level, player, body)
        player.sendOverlayMessage(Component.translatable("message.guildmark.tale.wagon.released", body.name, Wagon.bandWord(t)))
        return true
    }

    /**
     * The watch rode at the close (C): the band lets the hand go by the record, and the watch brings them in at seven. The camp's body is
     * someone gone (out of sight now, or once nobody can see it); the hand has no body until the watch's return makes one on the road.
     */
    fun ride(c: Ctx, t: Tale) {
        val level = c.level
        val r = c.resident(t, Wagon.HAND) ?: return
        forget(level, t)
        Comings.body(level, r)?.let { drop(level, it) }
        renew(level, r, UUID.randomUUID())
        Comings.met(level, c.capital, listOf(r.id))
        Wagon.setComes(t, c.day)
        Wagon.set(t, Wagon.RODE)
        c.why(t, "wagon.rode", t.cast(Wagon.CAPTAIN)?.name.orEmpty(), Wagon.handName(t))
        c.data.changed()
        if (t.step != Wagon.AT_HOME) c.go(t, Wagon.AT_HOME)
    }

    /** The band no longer has the hand on its record. */
    private fun forget(level: ServerLevel, t: Tale) {
        val id = t.resident(Wagon.HAND)
        if (id < 0) return
        val camps = OutlawCamps.get(level)
        var changed = false
        for (band in camps.bands) if (band.captives.removeIf { it.resident == id }) changed = true
        if (changed) camps.setDirty()
    }

    /** [b] is no one's: gone now if nobody can see it, else as soon as nobody can. */
    private fun drop(level: ServerLevel, b: Townsperson) {
        b.resident = -1
        if (Comings.unseen(level, b.blockPosition())) b.discard() else drops += b.uuid
    }

    /**
     * The tale is over and the hand lives at home again (A, B, C): the band's record of them is let go, and their body (wherever it is: home at
     * the board, walking home, still at the camp for an ending the record had) is made theirs again, their home, work and post as the town
     * keeps them. One still knelt at the camp is let go there and goes home on their own, or, out of sight, is made again at home.
     */
    fun restore(c: Ctx, t: Tale) {
        val level = c.level
        val r = c.resident(t, Wagon.HAND)?.takeIf { it.alive } ?: return
        forget(level, t)
        val b = Comings.body(level, r)
        if (b == null) {
            Comings.later(level, c.capital, listOf(r.id))
            return
        }
        if (b.captiveOf >= 0) {
            if (Comings.unseen(level, b.blockPosition()) && b.director == null) {
                b.discard()
                Comings.later(level, c.capital, listOf(r.id))
                return
            }
            b.captiveOf = -1
            b.rescuer = null
            b.post = null
            if (!b.activity.overlay) b.act(Activity.NONE)
        }
        val places = c.here.places
        val post = ComingsData.get(level).find(c.capital)?.posts?.get(r.id)
        b.home = places.lodgings[r.home]?.front?.inside
        b.work = post?.work
        b.post = post?.post
        b.postFacing = post?.facing
        b.evening = post?.evening
        b.town = places.capital.town
    }

    /**
     * The hand is lost (W): the band's record of them is let go and their body is gone (out of sight now, or as soon as nobody can see it), and,
     * if they still live on the record, the town puts them to rest (`Residents.pass`: the band moved its captive on, and they never came home).
     */
    fun lose(c: Ctx, t: Tale) {
        val level = c.level
        val r = c.resident(t, Wagon.HAND) ?: return
        forget(level, t)
        Comings.body(level, r)?.let { drop(level, it) }
        if (r.alive) Residents.pass(level, r, null, "")
    }

    // ---- Once a second, and the guard ----

    /** Drops whatever is waiting to be dropped that nobody can see now (and forgets what is no longer loaded: its land drops it as it loads). */
    fun tidy(level: ServerLevel) {
        if (drops.isEmpty()) return
        val it = drops.iterator()
        while (it.hasNext()) {
            val b = level.getEntity(it.next()) as? Townsperson
            if (b == null || !b.isAlive || b.isRemoved) {
                it.remove()
                continue
            }
            if (b.director is io.github.vaspyyy.guildmark.tales.TaleLeave) continue
            if (Comings.unseen(level, b.blockPosition())) {
                b.discard()
                it.remove()
            }
        }
    }

    /** The hands of open wagon tales are beyond harm (as every tale body is), all but what nothing withstands. */
    fun guard(event: LivingIncomingDamageEvent) {
        if (guarded.isEmpty()) return
        val p = event.entity as? Townsperson ?: return
        if (p.resident < 0 || p.resident !in guarded) return
        if (event.source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return
        event.isCanceled = true
    }

    /** Which hands are guarded, from the open tales. */
    fun refresh(level: ServerLevel) {
        guarded.clear()
        for (t in TaleData.get(level).all()) {
            if (!t.open || t.template != Wagon.id || !Wagon.flag(t, Wagon.TAKEN)) continue
            val id = t.resident(Wagon.HAND)
            if (id >= 0) guarded += id
        }
    }

    /** The server stopped: all of it forgotten. */
    fun forgetAll() {
        rescuers.clear()
        guarded.clear()
        drops.clear()
    }
}
