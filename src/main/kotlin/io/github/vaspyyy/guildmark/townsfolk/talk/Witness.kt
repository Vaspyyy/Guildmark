package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Noted
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.WorksView
import io.github.vaspyyy.guildmark.festival.FestivalContest
import io.github.vaspyyy.guildmark.homestead.HomesteadEvent
import io.github.vaspyyy.guildmark.homestead.HomesteadTiers
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.Haul
import io.github.vaspyyy.guildmark.trade.HaulKind
import io.github.vaspyyy.guildmark.trade.Word
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
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * Where rumours start (living-world/design-voices.md section 4): what the capitals' people see happen, and what other
 * systems tell of in a line each. Monsters killed in sight of residents, a resident saved from one, struck or killed,
 * any other death in town, a purse lifted or a thief caught, a captive brought home, a festival won, a homestead raised
 * near a capital, newcomers taken in, the guild's news as it's made, a wagon waylaid or escorted in, salvage and orders;
 * and (M6) a works paid for and raised, its crew's building days and the materials players bring, and a household
 * gone in hard times or come home. Each starts its rumour (see [Rumours] for the kinds and their facts) with whoever
 * knows it first, and the favour, slight or experience that goes with it.
 *
 * The one-line calls, in the files they're about: `GuildNews.add` ([guildNews]), `Residents.died` ([died]) and
 * `Residents.adopt` ([newcomer]), `Pickpocket.lift` ([robbed]) and `Pickpocket.caught` ([caught]),
 * `Captives.homecoming` ([rescued]), `FestivalSite.award` ([festival]), `Visitors.next` ([visitor]); and, once M3
 * and M4 are in, `Stalls.open` ([opened]), `Stalls.trade` ([dealt]), M3's orders ([order]) and M4's salvage
 * ([salvage]). The rest it hears for itself: the game's deaths and blows, homesteads' tiers, the caravans, and (M6)
 * the works' and the households' listeners ([gone] and [back] are there for Part D3 to call itself if it would
 * rather). Part B's labourers and Part D2's alms table call [TownTalk.laboured] and [TownTalk.fed].
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
        Works.listen(Building)
        Comings.listen(Households)
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
    internal fun neighbours(level: ServerLevel, r: Resident): List<Resident> {
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
        // No killing to tell of: any other death in town is told as the record tells it
        if (killer == null) {
            passed(level, r, p.blockPosition(), source?.entity?.takeIf { it !is Player && it !== p }?.type?.descriptionId)
            return
        }
        val household = household(level, r)
        val seen = seen(level, p.position(), p, SIGHT, true).filter { it.second.id != r.id }
        val kin = household.map { it.id }.toSet()
        val knowers = (seen.map { it.second.id to Venue.SEEN } + household.map { it.id to Venue.HOUSEHOLD }).distinctBy { it.first }
        TownTalk.begin(
            level, r.capital, "slight.murder", killer.uuid, r.id, listOf(named(killer.name.string), named(r.name)),
            1.0f, Fade.BIG, Scope.REGION, -1, p.blockPosition(), Rumours.TOWN, knowers,
        )
        for ((_, w) in seen) if (w.id !in kin) TownTalk.slight(level, w, killer.uuid, Slight.KILLING_SEEN)
        for (h in household) TownTalk.slight(level, h, killer.uuid, Slight.KILLED_KIN)
        grieve(level, r, household)
    }

    /**
     * [r], whose record is already marked dead, has passed on with no killer to tell of, and the town hears it at [at]:
     * their household grieves, those of their trade and their street talk of it, and their own memory is let go. [by] is
     * what killed them (an entity's name), if anything did. [died] tells it from a body; `Residents.pass` calls this when
     * there is none, as when a tale ends someone's life (living-world/stories/design.md 4.4).
     */
    fun passed(level: ServerLevel, r: Resident, at: BlockPos, by: String? = null) {
        val household = household(level, r)
        val trade = Residents.get(level).of(r.capital).filter { it.alive && it.id != r.id && it.role == r.role }.take(TRADE)
        val knowers = (household.map { it.id to Venue.HOUSEHOLD } + trade.map { it.id to Venue.WORK } + neighbours(level, r).map { it.id to Venue.STREET })
            .distinctBy { it.first }
        TownTalk.begin(
            level, r.capital, "death", null, r.id, listOfNotNull(named(r.name), "entity.guildmark.townsperson.${r.role.id}", by),
            0.8f, Fade.SMALL, Scope.TOWN, -1, at, Rumours.TOWN, knowers,
        )
        grieve(level, r, household)
    }

    /** The front door of [r]'s home, or their capital's heart where its places aren't known yet: where a death with no body is heard from. */
    fun homeDoor(r: Resident): BlockPos = Spread.view(r.capital).places?.lodgings?.get(r.home)?.front?.pos ?: r.capital

    /** [r]'s [household] feels the loss, and what [r] remembered goes with them: their death is a rumour now. */
    private fun grieve(level: ServerLevel, r: Resident, household: List<Resident>) {
        for (h in household) felt(level, h, Experience.BEREAVED, r.id)
        val data = TownTalkData.get(level)
        data[r.capital]?.minds?.remove(r.id)
        data.changed()
    }

    // ---- What other systems tell of ----

    /** [player] lifted [victim]'s purse unseen: they'll find it gone. */
    fun robbed(level: ServerLevel, player: Player, victim: Townsperson) {
        Fortunes.noteAt(level, victim.blockPosition(), Noted.THEFT)
        val r = Residents.get(level).of(victim)?.takeIf { it.alive } ?: return
        felt(level, r, Experience.ROBBED, -1)
    }

    /** [player] was caught with a hand in [victim]'s purse: a slight, and the talk of whoever saw. */
    fun caught(level: ServerLevel, player: Player, victim: Townsperson) {
        Fortunes.noteAt(level, victim.blockPosition(), Noted.CAUGHT, about = player.uuid)
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
        if (!buying) Fortunes.sold(level, entry, good, player)
        val r = Residents.get(level).of(keeper)?.takeIf { it.alive } ?: return
        val quote = Trading.quote(entry, good)
        val marks = lots * ((if (buying) quote.buy else quote.sell)?.marks ?: 0)
        // The Marlow note counts what is bought at its debtor's stall (Tales T1a)
        if (buying) Tales.bought(level, keeper, player, entry, good, true, lots, marks)
        TownTalk.traded(level, r, player, marks, !buying && quote.why?.dear == true)
    }

    /** [player]'s order with [r] was [filled] or let lapse (M3): a favour or a slight, and their business talks of it. */
    fun order(level: ServerLevel, r: Resident, player: ServerPlayer, filled: Boolean) = order(level, r, player.uuid, player.name.string, filled)

    /** [player] filled their order at [shop] in [capital] (from `Orders.deliver`): a favour to whoever keeps it. */
    fun filled(level: ServerLevel, capital: BlockPos, shop: Shop, player: ServerPlayer) {
        Fortunes.note(level, capital.asLong(), Noted.SUPPLIED, listOf(player.name.string), player.uuid)
        keeper(level, capital, shop)?.let { order(level, it, player.uuid, player.name.string, true) }
    }

    /** [player] (called [name]) let their order at [shop] in [capital] lapse unmet, or gave it up (from `Orders`): a slight to whoever keeps it. */
    fun lapsed(level: ServerLevel, capital: BlockPos, shop: Shop, player: UUID, name: String) {
        keeper(level, capital, shop)?.let { order(level, it, player, name, false) }
    }

    private fun order(level: ServerLevel, r: Resident, player: UUID, name: String, filled: Boolean) {
        if (filled) TownTalk.favour(level, r, player, Favour.ORDER_FILLED) else TownTalk.slight(level, r, player, Slight.ORDER_LAPSED)
        val knowers = (listOf(r.id to Venue.SEEN) + household(level, r).map { it.id to Venue.HOUSEHOLD }).distinctBy { it.first }
        TownTalk.begin(
            level, r.capital, if (filled) "order.filled" else "order.lapsed", player, r.id, listOf(named(name)),
            0.3f, Fade.SMALL, Scope.TOWN, if (filled) 1 else -1, null, Rumours.TOWN, knowers,
        )
    }

    /**
     * Who keeps [shop] at [capital], as far as the town knows: its baker, smith or innkeeper, or the merchant at that
     * stall while they're about (a stall's keeper is known by where they stand).
     */
    private fun keeper(level: ServerLevel, capital: BlockPos, shop: Shop): Resident? {
        val role = when (shop) {
            Shop.BAKERY -> TownRole.BAKER
            Shop.SMITHY -> TownRole.SMITH
            Shop.INN -> TownRole.INNKEEPER
            else -> TownRole.MERCHANT
        }
        val people = Residents.get(level).of(capital).filter { it.alive && it.role == role }
        if (role != TownRole.MERCHANT) return people.firstOrNull()
        return people.firstOrNull { r -> (r.uuid?.let(level::getEntity) as? Townsperson)?.let { Shop.kept(it) == shop } == true }
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

    // ---- The town's works and households (M6) ----

    /** How near a works the folk who see it paid for or raised stand, and the days a crew still counts those who were on it. */
    private const val ONLOOKERS = 24.0
    private const val CREW_DAYS = 2

    /**
     * What the town hears of its works: one paid for and one raised, about its patron (each resident thanks the patron
     * and its quarter-givers as they hear of it: [TownTalk.heard]); the day's crew at each step of the building, who
     * remember their building days ([TownTalk.laboured]); and materials a player brought to the yard, which the crew
     * thank them for, once a day ([TownTalk.brought]).
     */
    private object Building : Works.Listener {
        override fun funded(level: ServerLevel, w: WorksView) {
            if (!Works.enabled()) return
            val stall = w.kind.per == WorksKind.Per.PLAYER
            start(level, w, Rumours.WORKS_FUNDED, if (stall) 0.4f else 0.6f, Fade.SMALL, if (stall) 0 else 1, street(level, w))
        }

        override fun begun(level: ServerLevel, w: WorksView) = labour(level, w)

        override fun staged(level: ServerLevel, w: WorksView, stage: Stage) = labour(level, w)

        override fun finished(level: ServerLevel, w: WorksView) {
            if (!Works.enabled()) return
            labour(level, w)
            val stall = w.kind.per == WorksKind.Per.PLAYER
            // The crew saw it raised; the street round it heard as it went up
            val knowers = (crew(level, w, Experience.LABOURED.days).map { it.id to Venue.SEEN } + street(level, w)).distinctBy { it.first }
            start(level, w, Rumours.WORKS_RAISED, if (stall) 0.4f else 0.75f, if (stall) Fade.SMALL else Fade.BIG, if (stall) 0 else 1, knowers)
        }

        override fun brought(level: ServerLevel, w: WorksView, player: UUID, units: Int) {
            if (!Works.enabled() || units <= 0) return
            for (r in crew(level, w, CREW_DAYS)) TownTalk.brought(level, r, player)
        }

        /** [w]'s rumour of [kind], about its patron, known first by [knowers]; its facts the works' id, its name and its patron's. */
        private fun start(level: ServerLevel, w: WorksView, kind: String, heat: Float, fade: Fade, tone: Int, knowers: List<Pair<Int, Venue>>) {
            if (knowers.isEmpty()) return
            val args = listOfNotNull(w.id.toString(), "fortune.guildmark.works.${w.kind.id}", patron(level, w)?.let(::named))
            TownTalk.begin(
                level, BlockPos.of(w.capital), kind, w.patron, -1, args, heat, fade, Scope.TOWN, tone,
                w.site?.lot?.center ?: w.site?.post, Rumours.TOWN, knowers,
            )
        }

        /** Who pledged most toward [w] (a stall's owner), by name. */
        private fun patron(level: ServerLevel, w: WorksView): String? {
            val id = w.patron ?: return null
            return w.pledges.firstOrNull { it.player == id }?.name?.takeIf { it.isNotEmpty() } ?: level.server.playerList.getPlayer(id)?.name?.string
        }

        /**
         * The street round [w]: those living in the homes nearest its site (or the market board, with no site yet), who
         * hear of it there, and the folk about it now, who see it; three of the town if neither is known.
         */
        private fun street(level: ServerLevel, w: WorksView): List<Pair<Int, Venue>> {
            val capital = BlockPos.of(w.capital)
            val at = w.site?.post ?: Spread.view(w.capital).places?.marketBoard?.pos
            val near = at?.let { round(level, capital, it) }.orEmpty().map { it.id to Venue.STREET }
            val seen = at?.let { pos -> seen(level, Vec3.atCenterOf(pos), null, ONLOOKERS, false).filter { it.second.capital == capital } }.orEmpty()
                .map { it.second.id to Venue.SEEN }
            return (seen + near).distinctBy { it.first }.ifEmpty { some(level, capital, w.id.toLong()).map { it.id to Venue.STREET } }
        }

        /** Today's labourers record a building day on [w], if it's the works they're lent to. */
        private fun labour(level: ServerLevel, w: WorksView) {
            if (!Works.enabled()) return
            val residents = Residents.get(level)
            for (id in labourers(level, w)) residents[id]?.takeIf { it.alive }?.let { TownTalk.laboured(level, it, w.id) }
        }

        /** [w]'s crew: today's labourers at it, and those who laboured on it in the last [days] days. */
        private fun crew(level: ServerLevel, w: WorksView, days: Int): List<Resident> {
            val capital = BlockPos.of(w.capital)
            val entry = TownTalkData.get(level)[capital]
            val day = Memory.day(level)
            val today = labourers(level, w)
            return Residents.get(level).of(capital).filter { r ->
                r.alive && (r.id in today || entry?.minds?.get(r.id)?.felt?.any { it.experience == Experience.LABOURED && it.about == w.id && day - it.day in 0..days } == true)
            }
        }

        /**
         * The citizens lent to [w]'s capital's works today ([Hands.Crew.labourers]), if [w] is the works they work at:
         * the one at [Works.workAt], or with that not known, the only works going up there.
         */
        private fun labourers(level: ServerLevel, w: WorksView): Set<Int> {
            val places = Spread.view(w.capital).places ?: return setOf()
            val at = Works.workAt(level, w.capital)
            val theirs = if (at != null) Works.at(level, at)?.id == w.id
            else Works.all(level, w.capital).none { it.id != w.id && it.state == WorksState.BUILDING }
            return if (theirs) Hands.of(level, places).labourers else setOf()
        }
    }

    /** What the town hears of its households: one gone in hard times, one come home. */
    private object Households : Comings.Listener {
        override fun left(level: ServerLevel, h: Household) = gone(level, h)

        override fun returned(level: ServerLevel, h: Household) = back(level, h)
    }

    /**
     * Household [h] has left its capital in hard times: its neighbours know first, and the town talks of the family
     * gone (FortuneTopics). Comings' listener calls it; call it while the members' records still name them.
     */
    fun gone(level: ServerLevel, h: Household) = household(level, h, Rumours.HOUSEHOLD_GONE, -1)

    /** Household [h] has come home: its neighbours know first, and the town talks of the family back (FortuneTopics). */
    fun back(level: ServerLevel, h: Household) = household(level, h, Rumours.HOUSEHOLD_BACK, 1)

    private fun household(level: ServerLevel, h: Household, kind: String, tone: Int) {
        val residents = Residents.get(level)
        val members = h.members.mapNotNull { residents[it] }
        val first = members.firstOrNull { it.family.isNotEmpty() } ?: return
        val capital = BlockPos.of(h.capital)
        val home = Spread.view(h.capital).places?.lodgings?.get(h.home)?.box?.center
        val knowers = (home?.let { round(level, capital, it, h.home) }.orEmpty().filter { it.id !in h.members }
            .ifEmpty { some(level, capital, h.id.toLong()).filter { it.id !in h.members } })
            .map { it.id to Venue.STREET }
        if (knowers.isEmpty()) return
        TownTalk.begin(
            level, capital, kind, null, first.id, listOf(named(first.family)), 0.6f, Fade.SMALL, Scope.TOWN, tone,
            home, Rumours.TOWN, knowers,
        )
    }

    /** Those living in the [NEIGHBOURS] homes nearest [at] in [capital] (but home [except]), where its places are known. */
    internal fun round(level: ServerLevel, capital: BlockPos, at: BlockPos, except: Int = -1): List<Resident> {
        val places = Spread.view(capital).places ?: return listOf()
        val living = Residents.get(level).of(capital).filter { it.alive && it.home >= 0 && it.home != except }
        val homes = living.map { it.home }.distinct()
            .sortedBy { places.lodgings[it]?.box?.center?.distSqr(at) ?: Double.MAX_VALUE }.take(NEIGHBOURS).toSet()
        return living.filter { it.home in homes }
    }

    /** Three of [capital]'s grown citizens, the same three for the same [salt], to know first where nobody else would. */
    private fun some(level: ServerLevel, capital: BlockPos, salt: Long): List<Resident> =
        Residents.get(level).of(capital).filter { it.alive && it.role == TownRole.CITIZEN }.sortedBy { Spread.mix(it.id * 17L + salt) }.take(3)

    // ---- A delivery from the road (Trade, living-world/trader/design.md Part 6) ----

    /**
     * The units a delivery has to come to for the town to take note of it, on its page and in its talk (a consignment), and to be a great haul (the
     * talk at its hottest, and the street cheering; a relief ticket's always is, and a feast's).
     */
    const val HAULED_UNITS = 64
    const val HAULED_GREAT = 128

    /** The fourth fact of a `trade.hauled` rumour when the delivery was a relief ticket's: what [relieved] looks for, and what the Word's topics can tell it apart by. */
    const val RELIEF_WORD = "relief"

    /**
     * What the town made of a delivery ([hauled]): the rumour it started or heated (none for a delivery under 64 units that was not a relief ticket's),
     * how many of the shop's own it warmed to the player, and how many residents knew of it first.
     */
    class Hauled(val rumour: Rumour?, val warmed: Int, val knew: Int)

    /**
     * The name of the town whose heart is [capital], as the trader's Word names it (so the rumour of a delivery, the guild's news and the commands
     * name a town as the board, the book and the other rumours of the trade do), else as its orders keep it or its ground has it; "" if none is known.
     */
    fun townOf(level: ServerLevel, capital: BlockPos): String =
        Word.nameOf(level, capital.asLong()).ifEmpty { Word.found(level, capital.asLong()) }
            .ifEmpty { Suppliers.place(level, capital) }.ifEmpty { Spread.view(capital).places?.name.orEmpty() }

    /** As [hauled], for a delivery [player] made in person. */
    fun hauled(level: ServerLevel, entry: Markets.Entry, player: ServerPlayer, haul: Haul, units: Int, crowd: Collection<Int> = listOf()): Hauled =
        hauled(level, entry, player.uuid, player.name.string, haul, units, crowd)

    /**
     * [player] (called [name]) brought [units] of [haul]'s good to its shop in [entry]'s capital, in person or by a carter of their House (the town's
     * answer to a delivery, living-world/trader/design.md Part 6; `WorldAnswers` calls it from `Hauls.Listener.delivered`). The shop's own, its keeper and
     * their household, think the better of them ([Favour.HAULED], once a day each, up to the daily cap). A consignment of 64 units or more, or a relief
     * ticket's, is also talked of: a `trade.hauled` rumour [=player, the good's key, =town, and "relief" for a relief ticket's] at 0.6 of its heat, or 0.8 for
     * 128 units or more and for a relief (which goes to the other capitals as word of the region), told first by the keeper and household who saw it and by
     * [crowd], the residents who stood in the cheer (three citizens of the town if none of them is known). A relief is thanked by every resident who hears
     * of it ([relieved]).
     */
    fun hauled(level: ServerLevel, entry: Markets.Entry, player: UUID, name: String, haul: Haul, units: Int, crowd: Collection<Int> = listOf()): Hauled {
        val capital = entry.capital
        val keeper = keeper(level, capital, haul.shop)
        val own = keeper?.let { listOf(it) + household(level, it) }.orEmpty()
        // A delivery of any kind warms the shop's own to whoever brought it
        for (r in own) TownTalk.hauled(level, r, player)
        val relief = haul.kind == HaulKind.RELIEF
        if (units < HAULED_UNITS && !relief) return Hauled(null, own.size, 0)
        val town = townOf(level, capital)
        if (town.isEmpty()) return Hauled(null, own.size, 0)
        val knowers = (own.mapIndexed { k, r -> r.id to (if (k == 0) Venue.SEEN else Venue.HOUSEHOLD) } + crowd.map { it to Venue.SEEN }).distinctBy { it.first }
            .ifEmpty { some(level, capital, haul.id * 31L + units).map { it.id to Venue.STREET } }
        val facts = listOfNotNull(named(name), haul.good.key, named(town), RELIEF_WORD.takeIf { relief })
        val heat = if (relief || units >= HAULED_GREAT) 0.8f else 0.6f
        val story = TownTalk.begin(
            level, capital, Rumours.TRADE_HAULED, player, keeper?.id ?: -1, facts, heat, Fade.SMALL, if (relief) Scope.REGION else Scope.TOWN, 1,
            null, Rumours.TOWN, knowers,
        ).first
        return Hauled(story.rumour, own.size, knowers.size)
    }

    /**
     * [mind] has just heard [s], a trader's delivery (kind `trade.hauled`), for the first time ([TownTalk.heard] calls it): if it was a relief ticket's and
     * was brought to this very town (not word of it from afar), they thank the one who brought it ([Favour.RELIEVED]), once for each rumour.
     */
    internal fun relieved(entry: TownTalkData.Entry, mind: Mind, s: Story) {
        val rumour = s.rumour
        val player = rumour.about ?: return
        if (s.by != Rumours.TOWN || rumour.args.getOrNull(3) != RELIEF_WORD || mind.id < 0 || !Memory.enabled()) return
        val level = ServerLifecycleHooks.getCurrentServer()?.overworld() ?: return
        val day = Memory.day(level)
        val acq = Memory.acq(mind, player, day, true) ?: return
        if (Memory.favourOnce(acq, day, Favour.RELIEVED, rumour.id.toLong())) TownTalkData.get(level).changed()
    }
}
