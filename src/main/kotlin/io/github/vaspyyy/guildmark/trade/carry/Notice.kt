package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.TradeConfig
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * The town notices a trader on the road (living-world/trader/design.md, Part 3, "A pack animal": "townsfolk turn their heads at it", and
 * section 10's `trade_passing_0` and `_1`; build part D1). Two small things, both the town's answer to what a trader carries and so both
 * behind `tradeWorld` as well as `tradeCarry`:
 *
 * - **A word in passing.** Someone in the street within four blocks of a trader whose pack is a frame or a tall frame (a load of 100 Marks
 *   or more, [Laden.FRAME]) who can see them says "Mind how you go with that." or "Heavy load! Keep to the road, friend." and turns their
 *   head to them. Any one trader is spoken to once in two and a half minutes, and any one townsperson speaks so once in five.
 * - **A turned head.** When a harnessed animal's bell rings (see [PackFollow]) the three nearest townsfolk within thirteen blocks turn their
 *   heads to it for two and a half seconds, each at most once in twenty seconds.
 *
 * Only people who are free to are asked: not a leader, not someone at a counter or in a scene of any kind, not someone in a talk, asleep
 * or sitting. A glance does not stop them going about what they were doing. Nothing is written or kept past the session.
 */
object Notice {
    /** Ticks between two looks at everyone online for a laden trader. */
    private const val PERIOD = 20

    /** How near a laden trader must come to be spoken to, and how near a ringing bell for heads to turn, in blocks. */
    internal const val SPEAK = 4.0
    internal const val BELL = 13.0

    /** The most heads that turn at one ring, and how long a glance lasts, in ticks. */
    private const val TURNS = 3
    private const val GLANCE = 50

    /** Ticks before a trader is spoken to again, before one townsperson speaks so again, and before one turns their head again. */
    private const val SPOKEN_GAP = 3000L
    private const val PERSON_GAP = 6000L
    private const val TURN_GAP = 400L

    /** Ticks between two sweeps of the gaps kept, which let go of the ones that have run out. */
    private const val SWEEP = 6000L

    private val spokenTo = HashMap<UUID, Long>()
    private val spoke = HashMap<UUID, Long>()
    private val turned = HashMap<UUID, Long>()
    private var swept = 0L

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { event ->
            val server = event.server
            if (server.tickCount % PERIOD != 0 || !on()) return@addListener
            val now = server.overworld().gameTime
            if (now - swept >= SWEEP || now < swept) {
                swept = now
                for (gaps in listOf(spokenTo, spoke, turned)) gaps.values.removeIf { it <= now }
            }
            for (player in server.playerList.players) passing(player)
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> spokenTo.remove(event.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            spokenTo.clear()
            spoke.clear()
            turned.clear()
            swept = 0L
        }
    }

    private fun on(): Boolean = TradeConfig.carry() && TradeConfig.world()

    /** Whether [p] is free to be spoken to or to look about: not a leader, and in no scene, talk, sleep or seat. */
    private fun free(p: Townsperson): Boolean =
        p.isAlive && !p.isLeader && p.director == null && p.attending == null && !p.isSleeping && p.seat == null

    /** A laden [player] passing someone in the street gets a word. */
    private fun passing(player: ServerPlayer) {
        val laden = player.getExistingDataOrNull(Carry.LADEN)?.level ?: 0
        if (laden < 2 || player.isSpectator || !player.isAlive) return
        val level = player.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (now < (spokenTo[player.uuid] ?: 0L)) return
        val one = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(SPEAK)) { p ->
            free(p) && now >= (spoke[p.uuid] ?: 0L) && p.hasLineOfSight(player)
        }.minByOrNull { player.distanceToSqr(it) } ?: return
        if (!one.say("trade_passing_${one.random.nextInt(2)}")) return
        one.glance(player, GLANCE)
        spokenTo[player.uuid] = now + SPOKEN_GAP
        spoke[one.uuid] = now + PERSON_GAP
    }

    /** What [survey] finds near a trader, for `trade carry notice`. */
    class Survey(
        val carry: Boolean,
        val world: Boolean,
        /** The trader's load level, 0 to 3; a word in passing wants 2 or more. */
        val level: Int,
        /** Townsfolk within a bell's reach, and of them those free to look about. */
        val near: Int,
        val free: Int,
        /** Free townsfolk within speaking reach, and of them those who can see the trader and have not spoken so lately. */
        val close: Int,
        val ready: Int,
        /** Seconds before this trader is spoken to again (0: now). */
        val wait: Int,
    )

    /** Who is near [player] to remark on a load or turn at a bell, and whether the switches and the gaps let them: the answer to "why did nobody say anything?". */
    fun survey(player: ServerPlayer): Survey? {
        val level = player.level() as? ServerLevel ?: return null
        val now = level.gameTime
        val around = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(BELL))
        val close = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(SPEAK)) { free(it) }
        return Survey(
            carry = TradeConfig.carry(),
            world = TradeConfig.world(),
            level = player.getExistingDataOrNull(Carry.LADEN)?.level ?: 0,
            near = around.size,
            free = around.count { free(it) },
            close = close.size,
            ready = close.count { now >= (spoke[it.uuid] ?: 0L) && it.hasLineOfSight(player) },
            wait = (((spokenTo[player.uuid] ?: 0L) - now).coerceAtLeast(0L) / 20L).toInt(),
        )
    }

    /** Lets go of the gaps kept, so that the next pass is remarked on and the next ring turns heads again (`trade carry notice`, for trying it). */
    fun forget() {
        spokenTo.clear()
        spoke.clear()
        turned.clear()
    }

    /** A harnessed animal's bell has rung (called by [PackFollow], at most every few seconds for one animal): the nearest heads turn to it. */
    internal fun bell(rig: PackFollow.Rig) {
        val animal = rig.animal
        val level = animal.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (now < rig.noticeAt) return
        rig.noticeAt = now + NOTICE
        if (!on() || level.getNearestPlayer(animal, BELL * 2.0) == null) return
        val near = level.getEntitiesOfClass(Townsperson::class.java, animal.boundingBox.inflate(BELL)) { p ->
            free(p) && now >= (turned[p.uuid] ?: 0L)
        }
        for (p in near.sortedBy { animal.distanceToSqr(it) }.take(TURNS)) {
            p.glance(animal, GLANCE)
            turned[p.uuid] = now + TURN_GAP
        }
    }

    /** Ticks between two times one animal's ring is made something of. */
    private const val NOTICE = 60L
}
