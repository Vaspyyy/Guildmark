package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.festival.FestivalContest
import io.github.vaspyyy.guildmark.homestead.HomesteadEvent
import io.github.vaspyyy.guildmark.homestead.HomesteadTiers
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import java.util.UUID

/**
 * Where rumours start (living-world/design-voices.md section 4): what the capitals' people see happen, and what other
 * systems tell of in a line each. Monsters killed in sight of residents, a resident saved from one, struck or killed,
 * any other death in town, a purse lifted or a thief caught, a captive brought home, a festival won, a homestead raised
 * near a capital, newcomers taken in, the guild's news as it's made, a wagon waylaid or escorted in, salvage and orders.
 * Each starts its rumour (see [Rumours] for the kinds and their facts) with whoever knows it first, and the favour,
 * slight or experience that goes with it.
 *
 * The one-line calls, in the files they're about: `GuildNews.add` ([guildNews]), `Residents.died` ([died]) and
 * `Residents.adopt` ([newcomer]), `Pickpocket.lift` ([robbed]) and `Pickpocket.caught` ([caught]),
 * `Captives.homecoming` ([rescued]), `FestivalSite.award` ([festival]), `Visitors.next` ([visitor]); and, once M3
 * and M4 are in, `Stalls.open` ([opened]), `Stalls.trade` ([dealt]), M3's orders ([order]) and M4's salvage
 * ([salvage]). The rest it hears for itself: the game's deaths and blows, homesteads' tiers, and the caravans.
 */
object Witness {
    /** How far residents see a deed from, how far a monster's quarry may be from it, and a festival's crowd. */
    const val SIGHT = 16.0
    private const val QUARRY = 16.0
    private const val CROWD = 48.0

    /** How near a thief caught at it the folk who see it are, and how near a capital a homestead is talked of. */
    private const val THIEF = 10.0
    private const val HOMESTEAD = 1000.0

    /** How near its capital's centre a caravan's bell is looked for. */
    private const val BELL = 400.0

    /** Of a dead resident's trade, how many hear first; how many homes round theirs. */
    private const val TRADE = 6
    private const val NEIGHBOURS = 4

    /** Game ticks between blows of one player on one resident that count again. */
    private const val BLOWS = 200L

    /** When each player last struck each resident (by their bodies), so a flurry counts once. */
    private val blows = HashMap<Pair<UUID, UUID>, Long>()

    fun register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, LivingDeathEvent::class.java) { onDeath(it) }
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post::class.java) { onHurt(it) }
        NeoForge.EVENT_BUS.addListener(HomesteadEvent.TierChanged::class.java) { raised(it) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> blows.clear() }
        Caravans.listen(Road)
    }

    // ---- Who saw it ----

    /**
     * The residents within [radius] of [at] who'd see it: awake, alive, with a record, and (if [sight]) with nothing in
     * the way of [target] or, with no target, of the spot itself.
     */
    fun seen(level: ServerLevel, at: Vec3, target: Entity?, radius: Double, sight: Boolean): List<Pair<Townsperson, Resident>> {
        if (level.dimension() != Level.OVERWORLD) return listOf()
        val residents = Residents.get(level)
        val out = ArrayList<Pair<Townsperson, Resident>>()
        for (p in level.getEntitiesOfClass(Townsperson::class.java, AABB(at, at).inflate(radius, 8.0, radius))) {
            if (p === target || !p.isAlive || p.isSleeping || p.resident < 0 || p.distanceToSqr(at) > radius * radius) continue
            val r = residents.of(p)?.takeIf { it.alive } ?: continue
            if (sight && !sees(level, p, at, target)) continue
            out.add(p to r)
        }
        return out
    }

    private fun sees(level: ServerLevel, p: Townsperson, at: Vec3, target: Entity?): Boolean {
        if (target != null) return p.hasLineOfSight(target)
        val hit = level.clip(ClipContext(p.eyePosition, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
        return hit.type == HitResult.Type.MISS || hit.location.distanceToSqr(at) < 2.25
    }

    /** The capital most of [seen] live in, or null if nobody saw it. */
    fun capitalOf(seen: List<Pair<Townsperson, Resident>>): BlockPos? =
        seen.groupingBy { it.second.capital }.eachCount().maxByOrNull { it.value }?.key

    /** [r]'s household: the others living in their home. */
    private fun household(level: ServerLevel, r: Resident): List<Resident> =
        if (r.home < 0) listOf() else Residents.get(level).of(r.capital).filter { it.alive && it.id != r.id && it.home == r.home }

    /** Those living in the [NEIGHBOURS] homes nearest [r]'s (where the capital's places are known). */
    private fun neighbours(level: ServerLevel, r: Resident): List<Resident> {
        val places = Spread.view(r.capital).places ?: return listOf()
        val at = places.lodgings[r.home]?.box?.center ?: return listOf()
        val living = Residents.get(level).of(r.capital).filter { it.alive && it.id != r.id && it.home >= 0 && it.home != r.home }
        val homes = living.map { it.home }.distinct()
            .sortedBy { places.lodgings[it]?.box?.center?.distSqr(at) ?: Double.MAX_VALUE }.take(NEIGHBOURS).toSet()
        return living.filter { it.home in homes }
    }

    /** A name for Speech: as it's written, without the marks Speech reads. */
    fun named(name: String): String = "=" + name.replace('|', '/').replace('~', '-')

    private fun day(level: ServerLevel): Int = Memory.day(level)

    // ---- The game's deaths and blows ----

    private fun onDeath(event: LivingDeathEvent) {
        val victim = event.entity
        val level = victim.level() as? ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD) return
        // A resident's death is told of by Residents.died (see [died]); one with no record only if a player did it
        if (victim is Townsperson) {
            if (victim.resident < 0) stranger(level, victim, event.source)
            return
        }
        if (victim !is Enemy) return
        val player = event.source.entity as? ServerPlayer ?: victim.killCredit as? ServerPlayer ?: return
        slain(level, victim, player)
    }

    /** [player] killed [monster]: a deed if anyone saw it, and if it was after one of the town, they were saved. */
    private fun slain(level: ServerLevel, monster: LivingEntity, player: ServerPlayer) {
        val seen = seen(level, monster.position(), monster, SIGHT, true)
        val quarry = ((monster as? Mob)?.target as? Townsperson)?.takeIf { it.isAlive && it.resident >= 0 && it.distanceTo(monster) <= QUARRY }
        val saved = quarry?.let { q -> Residents.get(level).of(q)?.takeIf { it.alive }?.let { q to it } }
        val name = monster.type.descriptionId
        if (saved != null) {
            val r = saved.second
            val knowers = (seen.map { it.second.id } + r.id).distinct().map { it to Venue.SEEN }
            TownTalk.begin(
                level, r.capital, "deed.saved", player.uuid, r.id, listOf(named(player.name.string), named(r.name), name),
                0.8f, Fade.BIG, Scope.TOWN, 1, monster.blockPosition(), Rumours.TOWN, knowers,
            )
            TownTalk.favour(level, r, player.uuid, Favour.SAVED)
            felt(level, r, Experience.SAVED, -1)
            return
        }
        val capital = capitalOf(seen) ?: return
        TownTalk.begin(
            level, capital, "deed.town", player.uuid, -1, listOf(named(player.name.string), name),
            0.7f, Fade.SMALL, Scope.TOWN, 1, monster.blockPosition(), Rumours.TOWN, seen.map { it.second.id to Venue.SEEN },
        )
    }

    private fun onHurt(event: LivingDamageEvent.Post) {
        val victim = event.entity as? Townsperson ?: return
        val level = victim.level() as? ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD || victim.resident < 0 || event.healthDamage <= 0f) return
        val r = Residents.get(level).of(victim)?.takeIf { it.alive } ?: return
        val by = event.source.entity
        when {
            by is ServerPlayer -> {
                // The killing blow is the death's to tell of
                if (victim.isDeadOrDying) return
                val key = by.uuid to victim.uuid
                val now = level.gameTime
                if (now - (blows[key] ?: Long.MIN_VALUE / 2) < BLOWS) return
                blows[key] = now
                if (blows.size > 256) blows.entries.removeIf { now - it.value >= BLOWS }
                struck(level, victim, r, by)
            }
            by is Enemy -> felt(level, r, Experience.FRIGHTENED, -1)
        }
    }

    /** [player] struck [r]: they and their household take it ill, and whoever saw it talks. */
    private fun struck(level: ServerLevel, body: Townsperson, r: Resident, player: ServerPlayer) {
        val seen = seen(level, body.position(), body, SIGHT, true)
        val household = household(level, r)
        val knowers = (listOf(r.id to Venue.SEEN) + seen.map { it.second.id to Venue.SEEN } + household.map { it.id to Venue.HOUSEHOLD }).distinctBy { it.first }
        TownTalk.begin(
            level, r.capital, "slight.assault", player.uuid, r.id, listOf(named(player.name.string), named(r.name)),
            0.8f, Fade.SMALL, Scope.TOWN, -1, body.blockPosition(), Rumours.TOWN, knowers,
        )
        TownTalk.slight(level, r, player.uuid, Slight.STRUCK)
        for (h in household) TownTalk.slight(level, h, player.uuid, Slight.STRUCK_KIN)
    }

    /** A townsperson with no record (a passer-by, a visitor) killed by a player in sight of the town. */
    private fun stranger(level: ServerLevel, victim: Townsperson, source: DamageSource) {
        val player = source.entity as? ServerPlayer ?: victim.killCredit as? ServerPlayer ?: return
        val seen = seen(level, victim.position(), victim, SIGHT, true)
        val capital = capitalOf(seen) ?: return
        TownTalk.begin(
            level, capital, "slight.murder", player.uuid, -1, listOf(named(player.name.string), "entity.guildmark.townsperson.${victim.role.id}"),
            1.0f, Fade.BIG, Scope.REGION, -1, victim.blockPosition(), Rumours.TOWN, seen.map { it.second.id to Venue.SEEN },
        )
        for ((_, w) in seen) TownTalk.slight(level, w, player.uuid, Slight.KILLING_SEEN)
    }

    /**
     * [p] died (from `Residents.died`, their record already marked dead): a killing by a player, or another death in
     * town. Their household grieves, the town talks of it, and their own memory is let go.
     */
    fun died(level: ServerLevel, p: Townsperson) {
        val residents = Residents.get(level)
        val r = residents.of(p) ?: return
        val source = p.lastDamageSource
        val killer = source?.entity as? ServerPlayer ?: p.killCredit as? ServerPlayer
        val household = household(level, r)
        if (killer != null) {
            val seen = seen(level, p.position(), p, SIGHT, true).filter { it.second.id != r.id }
            val kin = household.map { it.id }.toSet()
            val knowers = (seen.map { it.second.id to Venue.SEEN } + household.map { it.id to Venue.HOUSEHOLD }).distinctBy { it.first }
            TownTalk.begin(
                level, r.capital, "slight.murder", killer.uuid, r.id, listOf(named(killer.name.string), named(r.name)),
                1.0f, Fade.BIG, Scope.REGION, -1, p.blockPosition(), Rumours.TOWN, knowers,
            )
            for ((_, w) in seen) if (w.id !in kin) TownTalk.slight(level, w, killer.uuid, Slight.KILLING_SEEN)
            for (h in household) TownTalk.slight(level, h, killer.uuid, Slight.KILLED_KIN)
        } else {
            val by = source?.entity?.takeIf { it !is Player && it !== p }?.type?.descriptionId
            val trade = residents.of(r.capital).filter { it.alive && it.id != r.id && it.role == r.role }.take(TRADE)
            val knowers = (household.map { it.id to Venue.HOUSEHOLD } + trade.map { it.id to Venue.WORK } + neighbours(level, r).map { it.id to Venue.STREET })
                .distinctBy { it.first }
            TownTalk.begin(
                level, r.capital, "death", null, r.id, listOfNotNull(named(r.name), "entity.guildmark.townsperson.${r.role.id}", by),
                0.8f, Fade.SMALL, Scope.TOWN, -1, p.blockPosition(), Rumours.TOWN, knowers,
            )
        }
        for (h in household) felt(level, h, Experience.BEREAVED, r.id)
        // Their death is a rumour now: what they remembered goes with them
        val data = TownTalkData.get(level)
        data[r.capital]?.minds?.remove(r.id)
        data.changed()
    }

    // ---- What other systems tell of ----

    /** [player] lifted [victim]'s purse unseen: they'll find it gone. */
    fun robbed(level: ServerLevel, player: Player, victim: Townsperson) {
        val r = Residents.get(level).of(victim)?.takeIf { it.alive } ?: return
        felt(level, r, Experience.ROBBED, -1)
    }

    /** [player] was caught with a hand in [victim]'s purse: a slight, and the talk of whoever saw. */
    fun caught(level: ServerLevel, player: Player, victim: Townsperson) {
        val r = Residents.get(level).of(victim)?.takeIf { it.alive } ?: return
        val seen = seen(level, player.position(), player, THIEF, true)
        val knowers = (listOf(r.id to Venue.SEEN) + seen.map { it.second.id to Venue.SEEN }).distinctBy { it.first }
        TownTalk.begin(
            level, r.capital, "slight.theft", player.uuid, r.id, listOf(named(player.name.string), named(r.name)),
            0.7f, Fade.SMALL, Scope.TOWN, -1, victim.blockPosition(), Rumours.TOWN, knowers,
        )
        TownTalk.slight(level, r, player.uuid, Slight.THEFT)
    }

    /** [p], a captive, is home (before the guild's news of it): their rescuer has their thanks, and their household's news. */
    fun rescued(level: ServerLevel, p: Townsperson) {
        val r = Residents.get(level).of(p)?.takeIf { it.alive } ?: return
        val rescuer = p.rescuer?.let { level.server.playerList.getPlayer(it) } ?: return
        val seen = seen(level, p.position(), p, SIGHT, true)
        val knowers = (listOf(r.id to Venue.SEEN) + household(level, r).map { it.id to Venue.HOUSEHOLD } + seen.map { it.second.id to Venue.SEEN })
            .distinctBy { it.first }
        TownTalk.begin(
            level, r.capital, "deed.rescue", rescuer.uuid, r.id, listOf(named(rescuer.name.string), named(r.name)),
            0.9f, Fade.BIG, Scope.TOWN, 1, p.blockPosition(), Rumours.TOWN, knowers,
        )
        TownTalk.favour(level, r, rescuer.uuid, Favour.FREED)
    }

    /** The festival of [kind] at [centre] is won (before its prizes and the guild's news): the crowd saw who won. */
    fun festival(level: ServerLevel, centre: Vec3, kind: String, results: List<FestivalContest.Entry>) {
        val winner = results.firstOrNull() ?: return
        val crowd = seen(level, centre, null, CROWD, false)
        val capital = capitalOf(crowd) ?: return
        TownTalk.begin(
            level, capital, "festival.won", winner.id, -1, listOf(named(winner.name), "festival.guildmark.$kind"),
            0.7f, Fade.SMALL, Scope.TOWN, 1, BlockPos.containing(centre), Rumours.TOWN, crowd.map { it.second.id to Venue.SEEN },
        )
    }

    /** [r] has just been taken in as one of a town's people (from `Residents.adopt`): someone new, if the town was there before them. */
    fun newcomer(level: ServerLevel, r: Resident) {
        if (r.planned >= 0 || r.id < 0) return
        val entry = TownTalkData.get(level)[r.capital] ?: return
        // A town whose talk began today is taking everyone in at once: nobody's new
        if (entry.made >= Memory.day(level)) return
        val knowers = neighbours(level, r).map { it.id to Venue.STREET }
        if (knowers.isEmpty()) return
        TownTalk.begin(
            level, r.capital, "newcomer", null, -1, listOf(named(r.family), named(r.given), "entity.guildmark.townsperson.${r.role.id}"),
            0.4f, Fade.SMALL, Scope.TOWN, 0, null, Rumours.TOWN, knowers,
        )
    }

    /** A visitor [p] has come into the capital at [capital] (from `Visitors.next`): of an evening, they bring word from elsewhere to the tavern. */
    fun visitor(level: ServerLevel, capital: BlockPos, p: Townsperson) {
        // The priest and the noble come for the temple and the sights, not the tavern
        if (!Rumours.enabled() || !Days.enabled() || p.role == TownRole.PRIEST || p.role == TownRole.NOBLE) return
        if (Math.floorMod(level.overworldClockTime, 24000L) < 11000L) return
        val entry = TownTalkData.get(level)[capital] ?: return
        Spread.visitors(level, entry, Spread.view(capital), Spread.hourOf(level))
    }

    /** The guild's news as it's made (from `GuildNews.add`): every capital hears it at once, the innkeepers and a few guards first. */
    fun guildNews(level: ServerLevel, key: String, args: List<String>) {
        if (guildKind(key) == null) return
        val data = TownTalkData.get(level)
        val residents = Residents.get(level).all().filter { it.alive }.groupBy { it.capital.asLong() }
        val hour = Spread.hourOf(level)
        for (c in Spread.capitals(level)) {
            val people = residents[c] ?: continue
            val pos = BlockPos.of(c)
            val entry = data[pos] ?: data.open(pos, hour, Memory.day(level).toLong())
            val s = guild(level, entry, key, args, hour) ?: continue
            var guards = 0
            for (r in people) {
                val first = when (r.role) {
                    TownRole.INNKEEPER -> true
                    TownRole.GUARD -> guards++ < 3
                    else -> false
                }
                if (first) Rumours.hear(entry, entry.mind(r.id, true)!!, s, hour, Rumours.FAR, Venue.AFAR, Rumours.TRUE)
            }
        }
        data.changed()
    }

    /** The kind of rumour the guild's news of [key] makes, with its heat, fade and tone; null for news that isn't talked of so. */
    fun guildKind(key: String): Triple<String, Pair<Float, Fade>, Int>? {
        val news = key.removePrefix("news.guildmark.")
        return when (news) {
            "dragon_slain" -> Triple("deed.dragon", 1.0f to Fade.LORE, 1)
            "king_slain" -> Triple("deed.king", 1.0f to Fade.LORE, 1)
            "maelgrasp_slain" -> Triple("deed.sea", 1.0f to Fade.LORE, 1)
            "field_boss" -> Triple("deed.beast", 0.8f to Fade.BIG, 1)
            "lair" -> Triple("deed.lair", 0.8f to Fade.BIG, 1)
            "siege_won" -> Triple("deed.siege", 0.8f to Fade.BIG, 1)
            "outlaw_slain" -> Triple("deed.band_broken", 0.8f to Fade.BIG, 1)
            "siege_lost" -> Triple("siege.lost", 0.8f to Fade.BIG, -1)
            "ambush", "outlaw.raid_beaten" -> Triple("deed.band", 0.6f to Fade.SMALL, 1)
            "outlaw.caravan", "outlaw.farm", "outlaw.barn", "outlaw.traveller", "outlaw.tithe", "outlaw.patrol", "outlaw.camp", "outlaw.raid" ->
                Triple("outlaws." + news.removePrefix("outlaw."), 0.6f to Fade.SMALL, -1)
            "outlaw.raid_taken" -> Triple("outlaws.taken", 0.6f to Fade.SMALL, -1)
            "outlaw.rescued" -> Triple("deed.rescue", 0.6f to Fade.SMALL, 1)
            "promoted", "faction_done" -> Triple("deed.rank", 0.4f to Fade.SMALL, 1)
            "festival_won" -> Triple("festival.won", 0.5f to Fade.SMALL, 1)
            "festival_today", "festival_soon" -> null
            else -> Triple("guild." + news.replace('.', '_'), 0.4f to Fade.SMALL, 0)
        }
    }

    /**
     * The guild's news of [key] begun at [entry] at [hour] (no one knowing it yet), unless it isn't talked of or the
     * town has the same of its own from the last day (a festival won there, a captive brought home). Its story, or null.
     */
    fun guild(level: ServerLevel, entry: TownTalkData.Entry, key: String, args: List<String>, hour: Long): Story? {
        val (kind, how, tone) = guildKind(key) ?: return null
        if (entry.rumours.values.any { it.by == Rumours.TOWN && it.rumour.kind == kind && hour - it.rumour.born in 0..24 }) return null
        val words = args.map { a ->
            when {
                a.startsWith("#") -> a.substring(1)
                a.toIntOrNull() != null -> a
                else -> named(a)
            }
        }
        val about = args.asSequence().filter { !it.startsWith("#") && it.toIntOrNull() == null }
            .flatMap { it.split(", ").asSequence() }
            .firstNotNullOfOrNull { level.server.playerList.getPlayerByName(it.trim())?.uuid }
        return Rumours.start(entry, hour, kind, about, -1, words, how.first, how.second, Scope.TOWN, tone, null, Rumours.GUILD).first
    }

    /** A homestead's next tier is finished: the carters of the capital near it saw it go up. */
    private fun raised(event: HomesteadEvent.TierChanged) {
        if (event.to <= event.from) return
        val level = event.level
        val h = event.homestead
        val capital = Spread.nearest(level, h.post, HOMESTEAD) ?: return
        val pos = BlockPos.of(capital)
        val places = Spread.view(capital).places
        val people = Residents.get(level).of(pos).filter { it.alive }
        val carters = places?.let { Hands.of(level, it).carters }
        val knowers = (if (carters != null) people.filter { it.id in carters } else people.filter { it.role == TownRole.CITIZEN }.sortedBy { Spread.mix(it.id * 17L + h.id) }.take(3))
            .map { it.id to Venue.SEEN }
        if (knowers.isEmpty()) return
        TownTalk.begin(
            level, pos, "homestead.raised", h.owner, -1, listOf(named(h.ownerName), "gui.guildmark.homestead.tier.${HomesteadTiers.key(h.tier)}"),
            0.4f, Fade.SMALL, Scope.TOWN, 1, h.post, Rumours.TOWN, knowers,
        )
    }

    /** [player] opened [keeper]'s stall (from `Stalls.open`): they've met. */
    fun opened(level: ServerLevel, keeper: Townsperson, player: ServerPlayer) {
        val r = Residents.get(level).of(keeper)?.takeIf { it.alive } ?: return
        TownTalk.met(level, r, player)
    }

    /**
     * [player] dealt [lots] lots of [good] at [keeper]'s stall of [entry]'s market (from `Stalls.trade`), [buying] or
     * selling: a trade, and selling the town what it's short of is a favour too.
     */
    fun dealt(level: ServerLevel, keeper: Townsperson, player: ServerPlayer, entry: Markets.Entry, good: Good, buying: Boolean, lots: Int) {
        if (lots <= 0) return
        val r = Residents.get(level).of(keeper)?.takeIf { it.alive } ?: return
        val quote = Trading.quote(entry, good)
        val marks = lots * ((if (buying) quote.buy else quote.sell)?.marks ?: 0)
        TownTalk.traded(level, r, player, marks, !buying && quote.why?.dear == true)
    }

    /** [player]'s order with [r] was [filled] or let lapse (M3): a favour or a slight, and their business talks of it. */
    fun order(level: ServerLevel, r: Resident, player: ServerPlayer, filled: Boolean) {
        if (filled) TownTalk.favour(level, r, player.uuid, Favour.ORDER_FILLED) else TownTalk.slight(level, r, player.uuid, Slight.ORDER_LAPSED)
        val knowers = (listOf(r.id to Venue.SEEN) + household(level, r).map { it.id to Venue.HOUSEHOLD }).distinctBy { it.first }
        TownTalk.begin(
            level, r.capital, if (filled) "order.filled" else "order.lapsed", player.uuid, r.id, listOf(named(player.name.string)),
            0.3f, Fade.SMALL, Scope.TOWN, if (filled) 1 else -1, null, Rumours.TOWN, knowers,
        )
    }

    /** [player] brought [r]'s salvage back off their wagon, or kept it (M4): a favour or a slight, and the keeper's talk. */
    fun salvage(level: ServerLevel, r: Resident, player: ServerPlayer, brought: Boolean) {
        if (brought) TownTalk.favour(level, r, player.uuid, Favour.SALVAGE) else TownTalk.slight(level, r, player.uuid, Slight.SALVAGE_KEPT)
        TownTalk.begin(
            level, r.capital, if (brought) "salvage.brought" else "salvage.kept", player.uuid, r.id, listOf(named(player.name.string)),
            0.5f, Fade.SMALL, Scope.TOWN, if (brought) 1 else -1, null, Rumours.TOWN, listOf(r.id to Venue.SEEN),
        )
    }

    /** [r] went through [experience] ([about]: the resident it concerned, or -1). */
    private fun felt(level: ServerLevel, r: Resident, experience: Experience, about: Int) {
        if (r.id < 0) return
        val data = TownTalkData.get(level)
        val entry = data[r.capital] ?: data.open(r.capital, Spread.hourOf(level), Memory.day(level).toLong())
        Memory.felt(entry.mind(r.id, true)!!, experience, day(level), about)
        data.changed()
    }

    // ---- The roads (M4) ----

    /** What the town hears of its wagons: one waylaid, or one a player brought in safe. */
    private object Road : Caravans.Listener {
        override fun struck(level: ServerLevel, caravan: Caravans.View, strike: Caravans.Strike) {
            if (!strike.waylaid) return
            val capital = Spread.nearest(level, caravan.capital, BELL) ?: return
            val band = OutlawCamps.get(level).band(strike.band)?.let { "${Outlaws.NAME_KEY}.${it.name}" } ?: ""
            val good = strike.taken.firstOrNull()?.first?.key ?: ""
            TownTalk.begin(
                level, BlockPos.of(capital), "road.waylaid", null, -1, listOf(band, good),
                0.8f, Fade.SMALL, Scope.TOWN, -1, null, Rumours.TOWN, gate(level, capital).map { it.id to Venue.SEEN },
            )
        }

        override fun arrived(level: ServerLevel, caravan: Caravans.View) {
            val escort = caravan.escort?.let { level.server.playerList.getPlayer(it) } ?: return
            val capital = Spread.nearest(level, caravan.capital, BELL) ?: return
            val gate = gate(level, capital)
            if (gate.isEmpty()) return
            TownTalk.begin(
                level, BlockPos.of(capital), "deed.escort", escort.uuid, -1, listOf(named(escort.name.string)),
                0.6f, Fade.SMALL, Scope.TOWN, 1, null, Rumours.TOWN, gate.map { it.id to Venue.SEEN },
            )
            for (r in gate) TownTalk.favour(level, r, escort.uuid, Favour.ESCORTED)
        }

        /** The gate guards (three) and the keepers of the capital at [capital], who see the wagons come and go. */
        private fun gate(level: ServerLevel, capital: Long): List<Resident> {
            val people = Residents.get(level).of(BlockPos.of(capital)).filter { it.alive }
            return people.filter { it.role == TownRole.GUARD }.take(3) + people.filter { it.role == TownRole.MERCHANT }
        }
    }
}
