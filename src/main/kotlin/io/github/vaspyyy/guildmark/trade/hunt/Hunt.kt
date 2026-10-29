package io.github.vaspyyy.guildmark.trade.hunt

import io.github.vaspyyy.guildmark.caravan.Waylayers
import io.github.vaspyyy.guildmark.cart.PlayerCart
import io.github.vaspyyy.guildmark.companion.CompanionEntity
import io.github.vaspyyy.guildmark.companion.Companions
import io.github.vaspyyy.guildmark.economy.Cargo
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.outlaw.Crime
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.outlaw.Outlaws
import io.github.vaspyyy.guildmark.outlaw.Raids
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.trade.Carriers
import io.github.vaspyyy.guildmark.trade.TradeBooks
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.carry.CartHitch
import io.github.vaspyyy.guildmark.trade.carry.Laden
import io.github.vaspyyy.guildmark.trade.carry.PackFollow
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The Hunt (living-world/trader/design.md, Part 4): an outlaw band's lookout sees a laden trader on the road, a horn winds from the hill, and a few
 * seconds on a party of two to four comes down towards them at its own pace. The trader gives it up and is robbed of a share of their goods in front
 * of them, or fights (their own weapons, a sworn companion, a hired guard), or runs. Nobody dies of it, and nothing but goods is ever taken.
 *
 * One [Run] is one Hunt, kept in memory only (a restart ends it as it ends a raid): the horn and the pause while the party comes into being out of
 * sight, the approach (a loose line walking in, quickening, the leader's call at twelve blocks), the search (the leader bent over the pack, a clink),
 * the run for the camp with a sack each, and, if the trader wins, the survivors' rout. Every outcome ends in [end], which puts the world right: the
 * bar gone, the animals settled, the party discarded.
 */
object Hunt {
    /** Every body of a party carries this tag, so one saved by mistake goes again as it loads. */
    const val TAG = "guildmark.hunt"

    /** Where a run stands. */
    enum class Phase { HORN, COMING, SEARCH, LEAVE, ROUT }

    /** How a Hunt ended, for the commands and the counters. */
    enum class End { ABORT, LOST, DRIVEN, LEFT, WON }

    /** What the hunted trader's side is doing about it, for the hired guards: nothing is asked of them until it is a fight. */
    enum class Stance { NONE, PARLEY, FIGHT }

    private const val ROLL = 20L
    private const val ROLL_GAP = 8000L
    private const val SECOND = 20L

    /** The pause between the horn and the party, in ticks: six to ten seconds. */
    private const val PAUSE_LEAST = 120L
    private const val PAUSE_MORE = 80

    /** How far the party calls (blocks), and how near it stops (the leader's circle). */
    private const val CALL = 12.0

    /** Ticks the search lasts, and the least a lesson's search is. */
    private const val SEARCH_LEAST = 90
    private const val SEARCH_MORE = 40
    private const val LESSON_SEARCH = 50L

    /** The party gives up when none of it is within this of the trader, nor has seen them for this long. */
    private const val LOSE_FAR = 60.0
    private const val LOSE_SIGHT = 300L

    /** A party without a path gives up after this long; nobody within [GONE] of it for this long and it is gone. */
    private const val STUCK = 400L
    private const val GONE = 200.0
    private const val GONE_FOR = 400L

    /** A whole Hunt lasts at most this long before the party gives up (the search and the rout aside). */
    private const val LONGEST = 6000L

    /** A fight that goes this long without a result is called off by the band. */
    private const val FIGHT_LONGEST = 3600L

    /** How far from where they stopped the trader the party must run, and how near any friend of the trader's must be, for the sacks to be theirs. */
    private const val CLEAR_FAR = 80.0
    private const val CLEAR_FRIEND = 24.0

    /** The rout lasts at most this long, a survivor this far from the trader has got clean away, and a win's green bar is up at least this long. */
    private const val ROUT_MOST = 600L
    private const val ROUT_FAR = 70.0
    private const val ROUT_SHOW = 60L

    /** The share of Hunts in which the pack animals bolt at the leader's call (the others stand their ground), and how long the steady ones stand alert. */
    private const val BOLT_CHANCE = 0.5f
    private const val STEADY = 600

    /** The damage a party's blow does (their damage is cut to this much), and what a blow may leave a trader (half a heart). */
    private const val BLOW = 0.7f
    private const val HALF_HEART = 1.0f

    /** What a win brings: standing at the road's capital. */
    private const val STANDING = 10.0

    private class Run(
        val player: UUID,
        val band: Int,
        val title: Component,
        val toward: Vec3,
        val stand: HuntOdds.Stand,
        val startedAt: Long,
        val pause: Long,
        val forced: Boolean,
        val party: HuntParty,
        val caravan: Long,
        val bandName: Int,
        val bandEpithet: Int,
        val region: String,
    ) {
        var phase = Phase.HORN
        var phaseAt = startedAt
        var bar: ServerBossEvent? = null

        /** The fight is real (a first blow was struck), and when it began. */
        var fight = false
        var fightAt = 0L

        /** A first blow was struck and not yet acted on (set by the damage hooks, taken by the step). */
        var blow = false

        /** A blow that would have left the trader under half a heart was cut short. */
        var lesson = false

        /** The lesson was given: while they go through the pack after it, nothing of the trader's side touches them. */
        var lessoned = false

        /** Nothing of the party's hurts anyone any more: the lesson was given, the share taken, the rout begun. */
        var sealed = false
        var called = false
        var armDownAt = 0L
        var reached = false
        var searchEnds = 0L
        var dearest: Item? = null
        var robbed = false
        var looted = false
        var taken: List<HuntShare.Lot> = listOf()
        var stoppedAt: Vec3 = Vec3.ZERO
        var won = false
        var end: End? = null
        var lastSight = startedAt
        var lastX = 0.0
        var lastZ = 0.0
        var speed = 0.0
        var blockedSince = -1L
        var farSince = -1L
        var shoutAt = 0L
        var cryAt = 0L
        var routAt = 0L
        var spawnTries = 0
        var bolted = false

        /** What the second of them works at in the search: a pack animal that stood its ground, or a cart (null: the pack itself). */
        var work: Entity? = null

        /** One of them struck as they ran for the camp, to be turned to fight (set by the damage hook, taken by the step). */
        var turn: Member? = null
    }

    /** The runs, by the trader hunted; and which run each living member of a party belongs to. Not saved. */
    private val runs = HashMap<UUID, Run>()
    private val ofMob = HashMap<UUID, Run>()

    /** The game time each band was last rolled for each trader, so that a band is rolled for once in eight hours. */
    private val rolled = HashMap<Pair<UUID, Int>, Long>()

    /** A counter for the sacks' record ids, so that two Hunts in the same tick have their own. */
    private var serial = 0L

    /**
     * An id for the record a Hunt's sacks are kept under in the band's loot, as a wagon's are under its own: negative, so that no wagon's is ever
     * taken for one, and made from the game's clock, so that one made after a restart is never one a pile from before still holds.
     */
    private fun sackId(level: ServerLevel): Long = -(1L + level.gameTime * 8L + (serial++ and 7L))

    fun register(modBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java) { incoming(it) }
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, LivingDamageEvent.Pre::class.java) { capped(it) }
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java) { died(it) }
        NeoForge.EVENT_BUS.addListener(LivingDropsEvent::class.java) { dropped(it) }
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent::class.java) { joined(it) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { (it.entity as? ServerPlayer)?.let(::loggedOut) }
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent::class.java) { stopping(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { forget() }
        Company.register(modBus)
        HuntCommand.init()
    }

    // ---- What the guards and the commands ask ----

    /** Whether [player] is being hunted just now. */
    fun active(player: UUID): Boolean = runs.containsKey(player)

    /** What [player]'s side is doing about the Hunt they are in. */
    fun stance(player: UUID): Stance {
        val r = runs[player] ?: return Stance.NONE
        return if (r.fight && !r.sealed) Stance.FIGHT else Stance.PARLEY
    }

    /** The nearest living, armed member of the party hunting [owner] within [range] of [from]: who a hired guard of [owner]'s fights, in a fight. Null otherwise. */
    fun foeFor(owner: UUID, from: Vec3, range: Double): Mob? {
        val r = runs[owner] ?: return null
        if (!r.fight || r.sealed) return null
        return r.party.alive().map { it.mob }.filter { it.distanceToSqr(from) <= range * range }.minByOrNull { it.distanceToSqr(from) }
    }

    /** The trader [entity] is hunting, if it is one of a party; null otherwise. */
    fun huntedBy(entity: Entity): UUID? = ofMob[entity.uuid]?.player

    /** Where the party hunting [owner] is, as a point to face: its living members' middle. Null if none is about. */
    fun partyAt(owner: UUID): Vec3? {
        val r = runs[owner] ?: return null
        val alive = r.party.alive()
        if (alive.isEmpty()) return null
        return Vec3(alive.sumOf { it.mob.x } / alive.size, alive.first().mob.y, alive.sumOf { it.mob.z } / alive.size)
    }

    /** One line saying how [player]'s Hunt stands, for `trade hunt list`; null if they are not hunted. */
    fun describe(level: ServerLevel, player: UUID): String? {
        val r = runs[player] ?: return null
        val alive = r.party.alive().size
        val age = (level.gameTime - r.startedAt) / SECOND
        return "${r.phase.name.lowercase()}${if (r.fight) ", fighting" else ""}, ${alive} of ${r.party.size} on their feet, ${age}s in"
    }

    /** Ends [player]'s Hunt now, whatever it is at; true if they had one. Nothing is lost by it and everything it made is put away. */
    fun endFor(level: ServerLevel, player: UUID): Boolean {
        val r = runs[player] ?: return false
        end(level, r, End.ABORT)
        return true
    }

    /** Ends every Hunt (the switch turned off, the command, the server going); gives how many there were. */
    fun endAll(level: ServerLevel): Int {
        val all = runs.values.toList()
        for (r in all) end(level, r, End.ABORT)
        return all.size
    }

    /** The traders being hunted just now. */
    fun hunted(): List<UUID> = runs.keys.toList()

    /** Whether [band] has a party out on a Hunt just now. */
    fun bandOut(band: Int): Boolean = runs.values.any { it.band == band }

    // ---- The tick ----

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val now = level.gameTime
        if (runs.isNotEmpty()) for (r in runs.values.toList()) step(level, r, now)
        if (now % ROLL == 0L) roll(level, now)
    }

    // ---- The roll ----

    /**
     * Every [ROLL] ticks: for each trader in the overworld with goods worth the least, outside a town, not in a Hunt and not hunted today, each band in reach
     * that is free to strike is rolled for once in eight hours, the nearest first, at the caravans' own odds with the Hunt's factors in. The first to
     * roll true hunts them.
     */
    private fun roll(level: ServerLevel, now: Long) {
        if (!TradeConfig.hunt()) return
        val camps = OutlawCamps.get(level)
        if (camps.bands.isEmpty()) return
        val clock = level.overworldClockTime
        // The most laden first: a band in reach of two goes for the one with more on them
        val laden = level.players().filter { !it.isSpectator && !it.isCreative && it.isAlive }.map { it to Laden.of(it) }.sortedByDescending { it.second }
        for ((player, marks) in laden) {
            if (runs.containsKey(player.uuid)) continue
            takeOwed(level, player)
            if (marks < TradeConfig.huntLaden()) continue
            val candidates = camps.bands.filter { band ->
                band.built && !band.slain && band.captain() != null && runs.values.none { it.band == band.id } &&
                    HuntOdds.distance(player, band) <= HuntOdds.reachOf(band) &&
                    now - (rolled[player.uuid to band.id] ?: Long.MIN_VALUE / 2) >= ROLL_GAP
            }.sortedBy { HuntOdds.distance(player, it) }
            if (candidates.isEmpty()) continue
            val data = CaravanData.get(level)
            val free = candidates.filter { Caravans.allowed(level, data, it, clock) }
            if (free.isEmpty()) continue
            if (huntedToday(level, player, clock)) continue
            if (HuntOdds.inTown(level, player.blockPosition())) continue
            val stand = HuntOdds.stand(level, player.blockPosition())
            val followers = Company.followers(player)
            for (band in free) {
                rolled[player.uuid to band.id] = now
                val odds = HuntOdds.odds(level, player, band, marks, stand, followers, clock)
                if (level.random.nextDouble() < odds.chance && start(level, player, band, false) == null) break
            }
        }
    }

    /** Whether [player] was hunted within the last game day. */
    private fun huntedToday(level: ServerLevel, player: ServerPlayer, clock: Long): Boolean {
        val last = TradeBooks.peek(level)?.get(player.uuid)?.hunts?.get("last") ?: return false
        return clock / SECOND - last < 24000L / SECOND
    }

    // ---- Starting one ----

    /**
     * [band]'s lookout has seen [player]: the horn winds, their pack animals throw up their heads, and in six to ten seconds a party comes into being out of
     * sight on the camp's side. [forced] is the operator's ([HuntCommand]): the odds, the rest and the day's gap are not asked. Gives why it did not start,
     * or null if it did.
     */
    fun start(level: ServerLevel, player: ServerPlayer, band: OutlawBand, forced: Boolean): String? {
        if (!TradeConfig.hunt()) return "the Hunt is switched off (or the roads' ambushes are)"
        if (runs.containsKey(player.uuid)) return "that trader is being hunted already"
        if (runs.values.any { it.band == band.id }) return "that band is out already"
        if (player.level() !== level || level.dimension() != Level.OVERWORLD) return "the Hunt is for the overworld"
        if (!player.isAlive || player.isSpectator) return "that trader is in no state to be hunted"
        if (HuntOdds.inTown(level, player.blockPosition())) return "they are in a town (a capital's heart within 128 blocks, a village's bell within 48)"
        val now = level.gameTime
        var dx = band.pos.x + 0.5 - player.x
        var dz = band.pos.z + 0.5 - player.z
        var len = hypot(dx, dz)
        if (len < 6.0) {
            val a = level.random.nextDouble() * Math.PI * 2
            dx = Math.cos(a)
            dz = Math.sin(a)
            len = 1.0
        }
        val toward = Vec3(dx / len, 0.0, dz / len)
        val stand = HuntOdds.stand(level, player.blockPosition())
        val r = Run(
            player.uuid, band.id, band.title(), toward, stand, now, PAUSE_LEAST + level.random.nextInt(PAUSE_MORE + 1), forced, HuntParty(level, TAG),
            sackId(level), band.name, band.epithet, band.region,
        )
        runs[player.uuid] = r
        r.lastX = player.x
        r.lastZ = player.z
        count(level, player.uuid, "started")
        setLast(level, player.uuid)
        // The horn winds from the hill on the camp's side, as far off as the camp or forty blocks
        val away = min(len, 40.0)
        val hornAt = Vec3(player.x + toward.x * away, player.y + 8.0, player.z + toward.z * away)
        HuntVoice.sound(level, hornAt, "trade.hunt_horn", 1f, 1f, SoundSource.HOSTILE)
        PackFollow.alarm(player, hornAt)
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.hunt.horn"))
        return null
    }

    // ---- One run, a tick ----

    private fun step(level: ServerLevel, r: Run, now: Long) {
        val player = level.server.playerList.getPlayer(r.player)
        if (player == null || !player.isAlive || player.level() !== level || !TradeConfig.hunt()) return end(level, r, End.ABORT)
        // How fast the trader moves, so that a party on foot is never slower than one running from it
        val v = hypot(player.x - r.lastX, player.z - r.lastZ) * SECOND
        r.speed += (v - r.speed) * 0.1
        r.lastX = player.x
        r.lastZ = player.z
        when (r.phase) {
            Phase.HORN -> horn(level, r, player, now)
            Phase.COMING -> coming(level, r, player, now)
            Phase.SEARCH -> search(level, r, player, now)
            Phase.LEAVE -> leave(level, r, player, now)
            Phase.ROUT -> rout(level, r, player, now)
        }
        if (runs[r.player] === r && r.phase != Phase.HORN) bar(level, r, player)
    }

    /** The pause after the horn: the party comes into being out of sight when it is over. */
    private fun horn(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        if (now - r.startedAt < r.pause || now < r.phaseAt) return
        val at = HuntParty.spot(level, player, r.toward)
        if (at == null) {
            // No open ground just yet: the player may be in a wood or on a shore; try again each second, then let it be
            if (++r.spawnTries > 5) end(level, r, End.ABORT)
            else r.phaseAt = now + SECOND
            return
        }
        val face = Vec3(player.x - at.x, 0.0, player.z - at.z)
        val unit = if (face.lengthSqr() < 1e-6) r.toward.scale(-1.0) else face.normalize()
        val size = bandSize(level, r)
        if (!r.party.spawn(at, unit, size, now) || r.party.size < 1) return end(level, r, End.ABORT)
        for (m in r.party.members) ofMob[m.mob.uuid] = r
        r.phase = Phase.COMING
        r.phaseAt = now
        r.lastSight = now
        val bar = ServerBossEvent(
            UUID.randomUUID(), Component.translatable("event.guildmark.trade.hunt", r.title), BossEvent.BossBarColor.YELLOW,
            if (r.party.size >= 4) BossEvent.BossBarOverlay.NOTCHED_12 else BossEvent.BossBarOverlay.NOTCHED_6,
        )
        bar.progress = 1f
        r.bar = bar
        // A shout from the second man as they set off down the hill
        (r.party.members.getOrNull(1) ?: r.party.members.first()).let { HuntVoice.sound(level, it.mob.position().add(0.0, 1.6, 0.0), "trade.hunt_shout", 1f, 0.9f + level.random.nextFloat() * 0.2f) }
    }

    /** How many a band sends: two to four by its tier. */
    private fun bandSize(level: ServerLevel, r: Run): Int {
        val band = OutlawCamps.get(level).band(r.band)
        return min(4, 2 + (band?.tier ?: 0) / 2).coerceAtLeast(2)
    }

    /** How fast the party walks when it is [d] blocks off: a walk that quickens as they close, never less than the pace of a trader running from them. */
    private fun pace(r: Run, d: Double): Double {
        val base = 2.7 + 1.9 * ((42.0 - d) / 30.0).coerceIn(0.0, 1.0)
        return max(base, r.speed * 1.1 + 0.5).coerceAtMost(6.3)
    }

    /** The approach: a loose line coming in, the leader's call at twelve blocks, the circle round the trader; a blow makes it a fight. */
    private fun coming(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        val alive = r.party.alive()
        if (alive.isEmpty()) return win(level, r, player, now)
        if (watch(level, r, player, now, alive)) return
        if (r.blow) {
            r.blow = false
            if (!r.fight) makeReal(level, r, player, now)
        }
        if (r.fight) return fighting(level, r, player, now, alive)
        val d = alive.minOf { it.mob.distanceTo(player).toDouble() }
        if (!r.called && d <= CALL) call(level, r, player, now)
        if (r.called && r.armDownAt != 0L && now >= r.armDownAt) {
            r.armDownAt = 0L
            r.party.leader()?.mob?.isAggressive = false
        }
        r.party.burn()
        r.party.come(player, now) { dist -> pace(r, dist) }
        if (r.called && d <= HuntParty.STAND + 1.5) startSearch(level, r, player, now)
    }

    /**
     * The leader calls at twelve blocks, an arm going up; the guards step in front. The pack animals bolt, taking their loads with them out of reach,
     * or one time in two stand their ground with their heads up and their bells still, to be gone through with the rest.
     */
    private fun call(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        r.called = true
        val leader = r.party.leader() ?: r.party.speaker() ?: return
        HuntVoice.say(level, leader.mob, HuntVoice.pick(level, "trade_hold", 3), listOf(player))
        HuntVoice.sound(level, leader.mob.position().add(0.0, 1.6, 0.0), "trade.hunt_shout", 1f, 0.8f + level.random.nextFloat() * 0.2f)
        r.bolted = level.random.nextFloat() < BOLT_CHANCE && PackFollow.bolt(player, leader.mob.position()) > 0
        if (!r.bolted) PackFollow.alarm(player, leader.mob.position(), STEADY)
        Company.hold(player, leader.mob.position())
        leader.mob.isAggressive = true
        r.armDownAt = now + 30
    }

    /**
     * They have closed to three blocks: the leader says "Wise." and the search begins, to end where the share is taken. A cart in the trader's hands is
     * taken out of them (its shafts drop where it stands), and the second of them makes for it, or for a pack animal that did not bolt.
     */
    private fun startSearch(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        r.phase = Phase.SEARCH
        r.phaseAt = now
        r.reached = true
        r.searchEnds = now + SEARCH_LEAST + level.random.nextInt(SEARCH_MORE + 1)
        r.stoppedAt = player.position()
        workAt(player, r)
        r.dearest = HuntShare.load(player).let { load -> Good.TRADED.maxByOrNull { it.base * load[it.ordinal] }?.takeIf { load[it.ordinal] > 0.0 }?.item }
        r.party.leader()?.mob?.isAggressive = false
        val speaker = r.party.leader() ?: r.party.speaker() ?: return
        HuntVoice.say(level, speaker.mob, HuntVoice.pick(level, "trade_wise", 2), listOf(player))
    }

    /** Giving it up: the party stands round the trader and goes through what they carry until the search is over, then the share is taken. */
    private fun search(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        val alive = r.party.alive()
        if (alive.isEmpty()) return win(level, r, player, now)
        if (watch(level, r, player, now, alive)) return
        if (r.blow) {
            r.blow = false
            if (!r.fight && !r.sealed) {
                makeReal(level, r, player, now)
                r.phase = Phase.COMING
                return
            }
        }
        r.party.burn()
        r.party.search(player, now, now - r.phaseAt, r.dearest, r.work?.takeIf { it.isAlive && it.distanceToSqr(player) <= Carriers.REACH * Carriers.REACH }?.position())
        if (now >= r.searchEnds) rob(level, r, player, now)
    }

    /** What the second of the party works at in [r]'s search: a cart of [player]'s within eight blocks (let go of, if they hold it), else a pack animal that stood its ground. */
    private fun workAt(player: ServerPlayer, r: Run) {
        CartHitch.release(player)
        val reach = Carriers.REACH * Carriers.REACH
        val cart = CartHitch.cartsOf(player.uuid).filter { it.level() === player.level() && it.distanceToSqr(player) <= reach }.minByOrNull { it.distanceToSqr(player) }
        r.work = cart ?: if (r.bolted) null else PackFollow.owned(player, Carriers.REACH).firstOrNull()
    }

    // ---- The fight ----

    /** A first blow was struck: the party's arms come up, its men go for the player and whoever stands with them, and the animals run. */
    private fun makeReal(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        r.fight = true
        r.fightAt = now
        r.called = true
        r.party.leader()?.mob?.isAggressive = true
        r.party.arm({ Company.isGuardOf(it, player.uuid) }, player.uuid)
        if (!r.bolted) r.bolted = PackFollow.bolt(player, r.party.speaker()?.mob?.position() ?: player.position()) > 0
        Company.hold(player, r.party.speaker()?.mob?.position() ?: player.position())
        count(level, player.uuid, "fought")
        r.party.speaker()?.let { HuntVoice.sound(level, it.mob.position().add(0.0, 1.6, 0.0), "trade.hunt_shout", 1f, 0.8f + level.random.nextFloat() * 0.3f) }
    }

    /** The fight: it ends when the leader falls (the rout), when a blow would have been the end of the trader (the lesson) or when the band calls it off. */
    private fun fighting(level: ServerLevel, r: Run, player: ServerPlayer, now: Long, alive: List<Member>) {
        if (r.party.leader() == null) return win(level, r, player, now)
        if (r.lesson) return lesson(level, r, player, now)
        if (now - r.fightAt > FIGHT_LONGEST) return lose(level, r, player, now, End.LOST)
        // A shout now and then, between the blows
        if (now >= r.shoutAt) {
            r.shoutAt = now + 80 + level.random.nextInt(100)
            alive.randomOrNull()?.let { HuntVoice.sound(level, it.mob.position().add(0.0, 1.6, 0.0), "trade.hunt_shout", 0.9f, 0.8f + level.random.nextFloat() * 0.4f) }
        }
        r.party.burn()
        // A mob that has lost its mark goes back to looking for one (the goals do it); one that cannot see the trader walks towards them
        for (m in alive) if (m.mob.target == null && m.mob.navigation.isDone) m.go(player.position(), pace(r, m.mob.distanceTo(player).toDouble()), now)
    }

    /** "That's the lesson.": a blow that would have left the trader under half a heart was cut short, the arms go down and the share is taken. */
    private fun lesson(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        r.lesson = false
        r.lessoned = true
        r.fight = false
        r.sealed = true
        r.party.disarm()
        (r.party.leader() ?: r.party.speaker())?.let { HuntVoice.say(level, it.mob, HuntVoice.pick(level, "trade_lesson", 2), listOf(player)) }
        Company.calm(player.uuid)
        r.phase = Phase.SEARCH
        r.phaseAt = now
        r.reached = true
        r.stoppedAt = player.position()
        r.searchEnds = now + LESSON_SEARCH
        workAt(player, r)
    }

    // ---- The share, and the run for the camp ----

    /**
     * The search is over: a share of the goods within eight blocks of the trader comes off them, in front of them. What really came off is written down (the
     * ledger, the tickets, the book, the band's loot, the road, the town's talk) and handed out in sacks, one to each of the party, who run for the camp.
     */
    private fun rob(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        r.sealed = true
        r.robbed = true
        val band = OutlawCamps.get(level).band(r.band)
        val lots = HuntShare.plan(HuntShare.load(player), HuntShare.share(level.random))
        val taken = HuntShare.take(player, lots)
        r.taken = taken
        count(level, player.uuid, "robbed")
        val units = taken.sumOf { it.units }
        if (taken.isEmpty()) {
            // Nothing within eight blocks worth their while: a bolted animal took the load with it
            player.sendOverlayMessage(Component.translatable("message.guildmark.trade.hunt.nothing"))
        } else {
            writeDown(level, player, band, taken, r.stand, r.caravan, units)
            r.looted = true
            val alive = r.party.alive()
            r.party.giveSacks(HuntShare.sacks(taken, alive.size))
            for (m in alive) HuntVoice.sound(level, m.mob.position().add(0.0, 1.0, 0.0), "trade.sack_lift", 0.9f, 0.9f + level.random.nextFloat() * 0.2f)
            player.sendSystemMessage(Component.translatable("message.guildmark.trade.hunt.robbed", units, r.title))
        }
        // The cart's canvas is left turned back, as they left it
        (r.work as? PlayerCart)?.takeIf { it.covered }?.let {
            it.setCovered(false)
            HuntVoice.sound(level, it.position().add(0.0, 1.0, 0.0), "trade.canvas_flap", 0.9f, 1f, SoundSource.NEUTRAL)
        }
        r.phase = Phase.LEAVE
        r.phaseAt = now
        r.stoppedAt = player.position()
        r.party.snuff()
        Company.calm(player.uuid)
        PackFollow.settle(player)
        PackFollow.alarm(player, player.position(), 1)
    }

    /**
     * The books of a share taken from [player] by [band]'s party: the Tally's note, the hauled ledger and the tickets (a booking whose goods are gone is let go with its
     * deposit back), the goods in the band's loot for the camp's pile, the band's rest and its bounty, the road's peril and the town's talk of it.
     */
    private fun writeDown(level: ServerLevel, player: ServerPlayer, band: OutlawBand?, taken: List<HuntShare.Lot>, stand: HuntOdds.Stand, caravan: Long, units: Int) {
        val clock = level.overworldClockTime
        val books = TradeBooks.get(level)
        val record = books.of(player.uuid)
        record.waylaid = clock
        record.hunts.merge("units", units, Int::plus)
        books.changed()
        val back = HuntShare.settle(level, player, taken)
        if (back > 0) player.sendSystemMessage(Component.translatable("message.guildmark.trade.hunt.refund", back))
        if (band == null) return
        // The sacks are marked as off a load bound for the town the trader was making for (the nearest, off every road), as a wagon's are
        val bound = if (stand.capital != 0L) stand.capital else HuntOdds.nearestCapital(level, player.blockPosition())
        Caravans.loot(level, band, taken.map { Cargo(it.good, it.units, bound, caravan, clock) })
        CaravanData.get(level).band(band.id).struck = clock
        CaravanData.get(level).changed()
        OutlawCamps.get(level).commit(level, band, Crime.TRAVELLER)
        val heart = stand.heart
        if (heart != null) {
            if (stand.onRoad) RoadPeril.change(CaravanData.get(level), stand.capital, stand.road, RoadPeril.WAYLAID, Math.floorDiv(clock, 1000L))
            Witness.hunted(level, heart, player, band, taken.sortedByDescending { it.good.base * it.units }.map { it.good })
        }
    }

    /**
     * The run for the camp with a sack each: nobody stops to fight, and the sacks are the band's once they are clear. One the trader's side strikes as it
     * runs turns and fights whoever struck it, sack and all; brought down, it drops the sack.
     */
    private fun leave(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        val alive = r.party.alive()
        if (alive.isEmpty()) return runDown(level, r, player, now)
        r.turn?.let { m ->
            r.turn = null
            if (m.alive && !m.turned) {
                m.turned = true
                r.party.arm({ Company.isGuardOf(it, player.uuid) }, player.uuid, listOf(m))
                HuntVoice.sound(level, m.mob.position().add(0.0, 1.6, 0.0), "trade.hunt_shout", 1f, 0.8f + level.random.nextFloat() * 0.2f)
            }
        }
        val band = OutlawCamps.get(level).band(r.band)
        val camp = band?.let { Vec3(it.pos.x + 0.5, it.pos.y.toDouble(), it.pos.z + 0.5) } ?: r.stoppedAt.add(r.toward.scale(120.0))
        r.party.burn()
        r.party.leave(camp, now, 4.9)
        if (now % 10L != 0L) return
        // A turned one's blow that would have felled the trader: it lets them be and runs on
        if (r.lesson) {
            r.lesson = false
            for (m in alive) if (m.turned) {
                m.turned = false
                m.mob.target = null
                m.mob.targetSelector.removeAllGoals { true }
                m.mob.isAggressive = false
                HuntVoice.say(level, m.mob, HuntVoice.pick(level, "trade_lesson", 2), listOf(player))
            }
        }
        val running = alive.filter { !it.turned }
        // A turned one that has lost whoever it fought runs on after the others
        for (m in alive) if (m.turned && (m.mob.target == null || !m.mob.target!!.isAlive)) {
            m.turned = false
            m.mob.target = null
            m.mob.targetSelector.removeAllGoals { true }
        }
        val clear = running.isNotEmpty() && alive.all { !it.turned && hypot(it.mob.x - r.stoppedAt.x, it.mob.z - r.stoppedAt.z) > CLEAR_FAR } &&
            alive.none { friendNear(level, player, it.mob.position()) }
        val gone = alive.all { !level.isLoaded(it.mob.blockPosition()) || level.getNearestPlayer(it.mob, GONE) == null }
        if (clear || gone || now - r.phaseAt > 3000L) end(level, r, End.LEFT)
    }

    /** Whether the trader, their companion or one of their guards is within [CLEAR_FRIEND] of [at]. */
    private fun friendNear(level: ServerLevel, player: ServerPlayer, at: Vec3): Boolean {
        if (player.distanceToSqr(at) <= CLEAR_FRIEND * CLEAR_FRIEND) return true
        val companion: CompanionEntity? = Companions.traveller(player)
        if (companion != null && companion.isAlive && companion.distanceToSqr(at) <= CLEAR_FRIEND * CLEAR_FRIEND) return true
        return Company.guardNear(player.uuid, at, CLEAR_FRIEND)
    }

    // ---- Winning, losing, the rout ----

    /**
     * The band is beaten (every member down, or the leader down): the bar fills green, the survivors break and scatter with their torches out, the trader
     * is paid the purse, the band is wounded for each one fallen, the road's peril falls, the town's guild hears of it and the trader's standing rises.
     */
    private fun win(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        if (r.phase == Phase.ROUT) return
        r.won = true
        r.sealed = true
        r.fight = false
        val fallen = r.party.members.count { !it.alive }
        r.party.disarm()
        r.party.snuff()
        Company.calm(player.uuid)
        PackFollow.settle(player)
        count(level, player.uuid, "won")
        val band = OutlawCamps.get(level).band(r.band)
        val data = CaravanData.get(level)
        val clock = level.overworldClockTime
        val rec = data.band(r.band)
        rec.wounds += fallen
        rec.struck = clock
        data.changed()
        val heart = r.stand.heart
        if (heart != null) {
            if (r.stand.onRoad) RoadPeril.change(data, r.stand.capital, r.stand.road, RoadPeril.HELPED, Math.floorDiv(clock, 1000L))
            if (Suppliers.enabled()) Suppliers.gain(level, heart, player.uuid, STANDING)
        }
        val purse = TradeConfig.huntPurse() * r.party.size
        if (purse > 0) {
            Trading.give(player, ModItems.GUILD_MARK.get(), purse)
            coins(level, r, player)
        }
        if (band != null) {
            val key = if (r.stand.onRoad) "news.guildmark.outlaw.hunt_beaten" else "news.guildmark.outlaw.hunt_beaten_wild"
            val where = if (r.stand.onRoad) roadName(level, r) else r.region.ifEmpty { "#${Outlaws.WILDS}" }
            GuildNews.add(level, key, "#${Outlaws.NAME_KEY}.${band.name}", "#${Outlaws.EPITHET_KEY}.${band.epithet}", where, player.name.string)
        }
        HuntVoice.sound(level, player.position(), "trade.hunt_win", 1f, 1f, SoundSource.PLAYERS)
        player.sendSystemMessage(if (purse > 0) Component.translatable("message.guildmark.trade.hunt.won", r.title, purse) else Component.translatable("message.guildmark.trade.hunt.won_free", r.title))
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.hunt.won_bar"))
        r.bar?.let {
            it.color = BossEvent.BossBarColor.GREEN
            it.progress = 1f
            it.name = Component.translatable("event.guildmark.trade.hunt.won", r.title)
        }
        startRout(level, r, now, player)
    }

    /**
     * Every one of them brought down as they ran with the sacks: what each carried lies where it fell, marked as the trader's own (the drops), and the
     * band is the weaker by each of them. It is not a win: they had the better of the trader first, so there is no purse, no standing and no word of it.
     */
    private fun runDown(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        if (r.phase == Phase.ROUT) return
        r.won = true
        r.sealed = true
        r.fight = false
        count(level, player.uuid, "ran_down")
        val data = CaravanData.get(level)
        data.band(r.band).wounds += r.party.members.count { !it.alive }
        data.changed()
        Company.calm(player.uuid)
        PackFollow.settle(player)
        HuntVoice.sound(level, player.position(), "trade.hunt_win", 0.8f, 0.94f, SoundSource.PLAYERS)
        player.sendOverlayMessage(Component.translatable("message.guildmark.trade.hunt.run_down"))
        r.bar?.let {
            it.color = BossEvent.BossBarColor.GREEN
            it.progress = 1f
            it.name = Component.translatable("event.guildmark.trade.hunt.run_down", r.title)
        }
        startRout(level, r, now, player)
    }

    /** A few coins of the purse, spilling from where the leader fell (or before the trader) into their hands. */
    private fun coins(level: ServerLevel, r: Run, player: ServerPlayer) {
        val from = r.party.members.firstOrNull { it.leader }?.mob?.position() ?: player.position().add(player.lookAngle.scale(2.0))
        val mark = ItemParticleOption(ParticleTypes.ITEM, ModItems.GUILD_MARK.get())
        val to = player.position().add(0.0, 1.0, 0.0)
        for (k in 0 until 6) {
            val f = k / 5.0
            val x = from.x + (to.x - from.x) * f
            val z = from.z + (to.z - from.z) * f
            level.sendParticles(mark, x, from.y + 0.6 + 0.8 * f, z, 2, 0.15, 0.1, 0.15, 0.04)
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.x, player.y + 1.4, player.z, 6, 0.4, 0.3, 0.4, 0.02)
    }

    /** The town the road runs into, for the news (the capital's name as the talk keeps it, else the region). */
    private fun roadName(level: ServerLevel, r: Run): String =
        CaravanData.get(level).capitalOrNull(r.stand.capital)?.name?.takeIf { it.isNotEmpty() } ?: r.region.ifEmpty { "#${Outlaws.WILDS}" }

    /** The party gives up the chase: lost in the country, without a path, driven off by a gate's guards or a village's bell. No share, no purse; the band rests. */
    private fun lose(level: ServerLevel, r: Run, player: ServerPlayer, now: Long, why: End) {
        if (r.phase == Phase.ROUT) return
        r.sealed = true
        r.fight = false
        r.party.disarm()
        r.party.snuff()
        Company.calm(player.uuid)
        PackFollow.settle(player)
        count(level, player.uuid, "escaped")
        val data = CaravanData.get(level)
        data.band(r.band).struck = level.overworldClockTime
        data.changed()
        val key = when (why) {
            End.DRIVEN -> "message.guildmark.trade.hunt.driven"
            else -> "message.guildmark.trade.hunt.lost"
        }
        player.sendOverlayMessage(Component.translatable(key))
        val speaker = r.party.speaker()
        if (speaker != null) {
            if (why == End.LOST) HuntVoice.say(level, speaker.mob, HuntVoice.pick(level, "trade_lost", 2), listOf(player))
            else HuntVoice.say(level, speaker.mob, HuntVoice.pick(level, "trade_flee", 2), listOf(player), HuntVoice.CRY)
        }
        r.end = why
        r.bar?.removeAllPlayers()
        r.bar = null
        startRout(level, r, now, player)
    }

    /** The survivors break and scatter, each on a bearing of its own, towards the camp if they have somewhere to go back to. */
    private fun startRout(level: ServerLevel, r: Run, now: Long, player: ServerPlayer) {
        r.phase = Phase.ROUT
        r.phaseAt = now
        r.routAt = now
        r.cryAt = now
        if (r.party.alive().isNotEmpty() && r.won) {
            r.party.speaker()?.let { HuntVoice.say(level, it.mob, HuntVoice.pick(level, "trade_flee", 2), listOf(player), HuntVoice.CRY) }
        }
    }

    /**
     * The rout: the survivors run, each its own way, until every one of them is out of everyone's sight (or far off); the bar stays green a few seconds
     * after a win. Whoever is left after [ROUT_MOST] ticks goes then, out of sight or not.
     */
    private fun rout(level: ServerLevel, r: Run, player: ServerPlayer, now: Long) {
        val alive = r.party.alive()
        val band = OutlawCamps.get(level).band(r.band)
        val home = band?.let { Vec3(it.pos.x + 0.5, it.pos.y.toDouble(), it.pos.z + 0.5) }
        if (alive.isNotEmpty()) r.party.scatter(player.position(), home, now, 6.0)
        if (now % 10L != 0L) return
        val clear = alive.all { unseen(level, it.mob) || it.mob.distanceTo(player) > ROUT_FAR }
        val shown = !r.won || now - r.routAt >= ROUT_SHOW
        if ((clear && shown) || now - r.routAt > ROUT_MOST) end(level, r, if (r.won) End.WON else r.end ?: End.LOST)
    }

    /** Whether no player sees [mob]: none within 64 blocks with a clear line to it, or its ground is not loaded. */
    private fun unseen(level: ServerLevel, mob: Mob): Boolean {
        if (!level.isLoaded(mob.blockPosition())) return true
        return level.players().none { it.distanceToSqr(mob) <= 64.0 * 64.0 && it.hasLineOfSight(mob) }
    }

    // ---- Watching a run: the ways it ends by itself ----

    /**
     * Looks at how the run stands and ends it if it should be: the trader within a capital's gate guards' reach or a village's bell (the party breaks off),
     * out of sight and out of reach for fifteen seconds (lost), a party with no way through for twenty seconds, a Hunt that has gone on too long, or nobody about
     * for twenty seconds. Gives true if it did.
     */
    private fun watch(level: ServerLevel, r: Run, player: ServerPlayer, now: Long, alive: List<Member>): Boolean {
        if (now % 10L == 0L) {
            if (alive.any { it.mob.distanceTo(player) <= 40f && it.mob.hasLineOfSight(player) }) r.lastSight = now
            // Nobody of the party near anyone: the run is forgotten, as a raid is when nobody is left to see it
            if (alive.all { it.mob.distanceTo(player) > GONE }) {
                if (r.farSince < 0) r.farSince = now
                if (now - r.farSince >= GONE_FOR) {
                    end(level, r, End.ABORT)
                    return true
                }
            } else r.farSince = -1L
        }
        if (!r.robbed && now % SECOND == 0L) {
            if (HuntOdds.guarded(level, player.blockPosition())) {
                lose(level, r, player, now, End.DRIVEN)
                return true
            }
            if (Raids.nearestBell(level, player.blockPosition(), HuntOdds.BELL) != null) {
                lose(level, r, player, now, End.DRIVEN)
                return true
            }
        }
        val d = alive.minOf { it.mob.distanceTo(player).toDouble() }
        if (!r.reached && d > LOSE_FAR && now - r.lastSight >= LOSE_SIGHT) {
            lose(level, r, player, now, End.LOST)
            return true
        }
        // No way through: every one of them stood still some way off for twenty seconds
        if (!r.fight && d > 8.0 && alive.all { it.measured < 0.4 && now > it.startAt }) {
            if (r.blockedSince < 0) r.blockedSince = now
            if (now - r.blockedSince >= STUCK) {
                lose(level, r, player, now, End.LOST)
                return true
            }
        } else r.blockedSince = -1L
        if (now - r.phaseAt > LONGEST && r.phase == Phase.COMING && !r.fight) {
            lose(level, r, player, now, End.LOST)
            return true
        }
        return false
    }

    // ---- The bar ----

    /** The bar shows the party's strength to the trader and to those about them. */
    private fun bar(level: ServerLevel, r: Run, player: ServerPlayer) {
        val bar = r.bar ?: return
        if (!r.won) bar.progress = (r.party.alive().size.toFloat() / r.party.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (r.phase == Phase.LEAVE && r.robbed) bar.name = Component.translatable("event.guildmark.trade.hunt.flee", r.title)
        val near = level.players().filter { !it.isSpectator && it.distanceToSqr(player) <= 64.0 * 64.0 }
        for (p in bar.players.toList()) if (p !in near) bar.removePlayer(p)
        for (p in near) bar.addPlayer(p)
    }

    // ---- Ending one ----

    /** Puts everything a run made away: the bar, the animals' fright, the guards' stand, the party; piles the sacks at the camp; and counts the end. */
    private fun end(level: ServerLevel, r: Run, how: End) {
        if (runs[r.player] !== r) return
        runs.remove(r.player)
        for (m in r.party.members) ofMob.remove(m.mob.uuid)
        r.bar?.removeAllPlayers()
        r.bar = null
        r.party.discard()
        Company.calm(r.player)
        val player = level.server.playerList.getPlayer(r.player)
        if (player != null) PackFollow.settle(player)
        if (r.looted) OutlawCamps.get(level).band(r.band)?.let { pile(level, it, r.caravan) }
        if (r.reached || r.robbed) {
            val data = CaravanData.get(level)
            data.band(r.band).struck = level.overworldClockTime
            data.changed()
        }
        r.end = how
    }

    /**
     * The sacks of [caravan] (the record a Hunt's share was put under) lie in a pile by [band]'s camp, under a tarp, marked as off the load they were
     * taken from, as a broken band's loot does ([Caravans.pile]). What the band took off its wagons stays in its loot as before, for the pile it leaves
     * when it is broken; and while a pile of the band's is lying there still the sacks wait in the loot with the rest for the next.
     */
    private fun pile(level: ServerLevel, band: OutlawBand, caravan: Long) {
        val data = CaravanData.get(level)
        val t = level.overworldClockTime
        if (data.wrecks.any { it.pile && it.band == band.id && it.until > t && !it.emptied }) return
        val rec = data.bandOrNull(band.id) ?: return
        val others = rec.loot.filter { it.caravan != caravan }
        if (others.size == rec.loot.size) return
        rec.loot.removeAll(others)
        try {
            Caravans.pile(level, data, band)
        } finally {
            rec.loot.addAll(0, others)
            data.changed()
        }
    }

    // ---- What they take on a return ----

    /**
     * A trader who left the game after the party had reached them is robbed when they come back (no escaping a robbery by logging out): the share
     * owed is taken from what they carry now. Done a few seconds after they are back, when their animals are about them again.
     */
    private fun takeOwed(level: ServerLevel, player: ServerPlayer) {
        if (player.tickCount < 80) return
        val book = TradeBooks.peek(level)?.get(player.uuid) ?: return
        val percent = book.hunts["owed"] ?: return
        if (percent <= 0) return
        val bandId = (book.hunts["owed_band"] ?: 0) - 1
        val books = TradeBooks.get(level)
        val record = books.of(player.uuid)
        record.hunts.remove("owed")
        record.hunts.remove("owed_band")
        books.changed()
        val lots = HuntShare.plan(HuntShare.load(player), percent / 100.0)
        val taken = HuntShare.take(player, lots)
        count(level, player.uuid, "robbed")
        val band = if (bandId >= 0) OutlawCamps.get(level).band(bandId) else null
        if (taken.isEmpty()) return
        val stand = HuntOdds.stand(level, player.blockPosition())
        val caravan = sackId(level)
        writeDown(level, player, band, taken, stand, caravan, taken.sumOf { it.units })
        if (band != null) pile(level, band, caravan)
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.hunt.owed", taken.sumOf { it.units }))
    }

    private fun loggedOut(player: ServerPlayer) {
        val r = runs[player.uuid] ?: return
        val level = player.level() as? ServerLevel ?: return
        // Reached and not yet robbed: the share is theirs on the trader's return
        if (r.reached && !r.robbed) {
            val books = TradeBooks.get(level)
            val record = books.of(player.uuid)
            record.hunts["owed"] = (HuntShare.share(level.random) * 100).toInt().coerceIn(50, 70)
            record.hunts["owed_band"] = r.band + 1
            books.changed()
        }
        end(level, r, End.ABORT)
    }

    // ---- Counters ----

    private fun count(level: ServerLevel, player: UUID, key: String, by: Int = 1) {
        val books = TradeBooks.get(level)
        books.of(player).hunts.merge(key, by, Int::plus)
        books.changed()
    }

    private fun setLast(level: ServerLevel, player: UUID) {
        val books = TradeBooks.get(level)
        books.of(player).hunts["last"] = (level.overworldClockTime / SECOND).toInt()
        books.changed()
    }

    // ---- Events ----

    /** The party hunting the player [victim] belongs to, or the run a party member [attacker] is of. */
    private fun runOf(entity: Entity?): Run? = entity?.let { ofMob[it.uuid] }

    /**
     * Blows. A first blow from the hunted trader, a player, their companion or a hired guard at a party member makes it a fight; the party's blows at a player
     * do 70% and are cancelled altogether where it has not been made one (or has been stood down), and a party never touches a hired guard but in a fight.
     */
    private fun incoming(event: LivingIncomingDamageEvent) {
        val victim = event.entity
        val attacker = event.source.entity
        val partyVictim = runOf(victim)
        if (partyVictim != null) {
            val side = attacker != null && (attacker is ServerPlayer || attacker is CompanionEntity || Company.isGuardOf(attacker, partyVictim.player))
            if (!side) return
            when {
                // Beaten, the trader is not to lay a hand on them while they go through the pack
                partyVictim.lessoned && partyVictim.phase == Phase.SEARCH -> event.isCanceled = true
                // Run down with the sacks: the one struck turns to fight, the rest run on
                partyVictim.robbed && partyVictim.phase == Phase.LEAVE -> partyVictim.party.of(victim)?.let { if (!it.turned) partyVictim.turn = it }
                !partyVictim.sealed && !partyVictim.fight -> partyVictim.blow = true
            }
            return
        }
        val partyAttacker = runOf(attacker) ?: return
        val turned = partyAttacker.phase == Phase.LEAVE && partyAttacker.party.of(attacker!!)?.turned == true
        if ((!partyAttacker.fight || partyAttacker.sealed) && !turned) {
            event.isCanceled = true
            return
        }
        if (victim is ServerPlayer) event.amount = event.amount * BLOW
    }

    /** A blow that would leave the hunted trader under half a heart leaves them at half a heart, and ends the fight; a party never kills (unless the setting lets it). */
    private fun capped(event: LivingDamageEvent.Pre) {
        val victim = event.entity as? ServerPlayer ?: return
        val r = runOf(event.source.entity) ?: return
        if (TradeConfig.huntKills()) return
        val room = victim.health + victim.absorptionAmount - HALF_HEART
        if (event.newDamage >= room) {
            event.newDamage = max(0f, room)
            if (r.player == victim.uuid) r.lesson = true
        }
    }

    private fun died(event: LivingDeathEvent) {
        val dead = event.entity
        // A party member's fall is seen to by the run's step (the leader down is a win); a sack-carrier's sack drops with its other drops
        if (dead is Townsperson) Company.died(dead)
    }

    /** A party member felled before the party is clear drops the sack it carried (the rule of a waylaid wagon's drops), marked as off the wagon it was taken from. */
    private fun dropped(event: LivingDropsEvent) {
        val dead = event.entity as? Mob ?: return
        val r = ofMob[dead.uuid] ?: return
        val level = dead.level() as? ServerLevel ?: return
        val m = r.party.of(dead) ?: return
        // Nothing of their own (no gear, no loot table's), only the sack
        event.drops.clear()
        for (stack in Waylayers.drops(level, r.caravan, m.carry)) {
            event.drops.add(ItemEntity(level, dead.x, dead.y + 0.3, dead.z, stack))
        }
    }

    /** A party member or a guard loaded back from disk (a restart in the middle of a Hunt) is sent off. */
    private fun joined(event: EntityJoinLevelEvent) {
        val entity = event.entity
        if (event.level.isClientSide || !event.loadedFromDisk()) return
        if (entity.entityTags().contains(TAG)) {
            event.isCanceled = true
            return
        }
        if (entity is Townsperson) Company.loaded(entity, event)
    }

    private fun stopping(server: MinecraftServer) {
        endAll(server.overworld())
        Company.discardAll()
    }

    private fun forget() {
        runs.clear()
        ofMob.clear()
        rolled.clear()
        HuntVoice.forget()
        Company.forget()
    }
}
