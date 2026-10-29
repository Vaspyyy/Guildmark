package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.Move
import io.github.vaspyyy.guildmark.townsfolk.scenes.Turn
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.Prediction
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Carrier's Warrant at the hall's counter (living-world/trader/design.md, Part 5): the two wares that sell it, and the scene the
 * hall's merchant plays when one is bought.
 *
 * - **`carriers_warrant`** costs [Ladder.WARRANT_PRICE] Marks and is open once the Chapman's asks are met ([Ladder.warrantMissing]); with
 *   the ladder off it is open to anyone with the Marks. Buying it writes the book at once (the Marks are gone, so the Warrant is theirs),
 *   and the merchant hands over the sheet at the end of the scene, which is when the rope ties its knot and the title is offered.
 * - **`carriers_warrant_again`** is free and open to someone the book has down for a Warrant who carries none: a lost Warrant is copied out
 *   of the hall's book and sealed again. The price of a ware is a constant, so a free one is a ware of its own. It gives no second title.
 *
 * Whatever happens to the scene (the merchant busy, hurt or gone, the player off down the street, the world stopping), the Warrant comes:
 * the scene hands it over when it ends however it ends, and a sheet that is still owed [WAIT] ticks after the Marks were taken, or
 * [ALLOWED] after the scene began, is handed over anyway. If the world stops first, the book has it and the free copy is there for the asking.
 */
object WarrantScene {
    /** Ticks the sheet waits for a scene to begin (the screen may not have one to play), and ticks a scene is given to play out before the sheet is handed over without it. */
    private const val WAIT = 60L
    private const val ALLOWED = 700L

    /** How far off the scene is still worth playing, and how far off the voice is heard (as the crier's cries carry). */
    private const val SEEN = 24.0
    private const val HEARD = 48.0

    /** The wax: a dull red, dripped and pressed. */
    private val WAX = DustParticleOptions(0xA8231F, 1.0f)

    /** A sheet owed to a player: when it is handed over regardless, whether it is a copy of one lost (no title, no knot), and the town it is sealed at. */
    private class Owed(var due: Long, val copy: Boolean, var town: String? = null)

    private val owed = HashMap<UUID, Owed>()

    /** Puts the two wares on the hall's counter (once, at setup). */
    fun register() {
        Wares.add(
            Ware(
                "carriers_warrant", Ladder.WARRANT_PRICE,
                gate = { missing(it) },
                action = { buy(it) },
                scene = { player, merchant -> play(player, merchant, copy = false) },
            ),
        )
        Wares.add(
            Ware(
                "carriers_warrant_again", 0,
                gate = { missingCopy(it) },
                action = { copy(it) },
                scene = { player, merchant -> play(player, merchant, copy = true) },
            ),
        )
    }

    /** The server stopped: nothing owed is kept (the books have it). */
    fun forget() {
        owed.clear()
    }

    /** Hands over a sheet that is overdue; every few ticks, and nothing to look at when none is owed. */
    fun tick(server: MinecraftServer) {
        if (owed.isEmpty() || server.tickCount % 10 != 0) return
        val now = server.overworld().gameTime
        for (id in owed.filterValues { it.due <= now }.keys.toList()) {
            val player = server.playerList.getPlayer(id)
            if (player == null) owed.remove(id) else handOver(player)
        }
    }

    // ---- The wares ----

    /** What stands between [player] and the Warrant: null if nothing does, else the lang key of what. */
    private fun missing(player: ServerPlayer): String? {
        val level = player.level()
        val book = Rungs.peek(level)?.get(player.uuid)
        if (book?.warrant == true) return Ladder.WARRANT_HELD
        if (!TradeConfig.rungs()) return null
        return Ladder.warrantMissing(Rungs.facts(level, player.uuid, book))
    }

    /** What stands between [player] and a copy: they hold no Warrant by the book, or they carry theirs (or one is being written out for them just now). */
    private fun missingCopy(player: ServerPlayer): String? = when {
        Rungs.book(player)?.warrant != true -> NONE
        carries(player) || player.uuid in owed -> CARRIED
        else -> null
    }

    /** The Warrant is bought: the book has it from now (the Marks were taken), and the sheet is owed. */
    private fun buy(player: ServerPlayer): Boolean {
        if (!TradeConfig.life() || missing(player) != null) return false
        val level = player.level()
        val books = TradeBooks.get(level)
        books.of(player.uuid).warrant = true
        books.changed()
        owed[player.uuid] = Owed(level.gameTime + WAIT, copy = false)
        return true
    }

    /** A copy is asked for: the sheet is owed, and the book stays as it was. */
    private fun copy(player: ServerPlayer): Boolean {
        if (!TradeConfig.life() || missingCopy(player) != null) return false
        owed[player.uuid] = Owed(player.level().gameTime + WAIT, copy = true)
        return true
    }

    /** Whether [player] carries a Warrant, anywhere in their pack (armour and off hand too). */
    internal fun carries(player: ServerPlayer): Boolean {
        val inventory = player.inventory
        val warrant = ModItems.CARRIERS_WARRANT.get()
        for (i in 0 until inventory.containerSize) if (inventory.getItem(i).item === warrant) return true
        return false
    }

    // ---- The scene ----

    /**
     * For `trade warrant scene`: [merchant] plays the counter's scene for [player] as if a Warrant (or, with [copy], a copy of one) had
     * just been asked for, with no Marks taken and the book left as it is, so that the scene can be watched without the counter's screen.
     * The sheet is handed over at its end, as at the counter. True if the merchant took the part; false if they were busy, in which case
     * the sheet was handed over at once.
     */
    internal fun rehearse(player: ServerPlayer, merchant: Townsperson, copy: Boolean): Boolean {
        owed[player.uuid] = Owed(player.level().gameTime + WAIT, copy)
        return play(player, merchant, copy)
    }

    /** The nearest merchant to [player] within [reach] blocks, who will do to play the counter's scene. */
    internal fun merchantNear(player: ServerPlayer, reach: Double = 12.0): Townsperson? =
        player.level().getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(reach)) { it.isAlive && it.role == TownRole.MERCHANT }
            .minByOrNull { it.distanceToSqr(player) }

    /**
     * The hall's merchant, at the counter, writes out the Warrant for [player] and hands it across. If the merchant cannot be taken for it
     * (they are in another scene, hurt or away from their post) the sheet is handed over at once, with no scene. True if the scene began.
     */
    private fun play(player: ServerPlayer, merchant: Townsperson, copy: Boolean): Boolean {
        val level = merchant.level() as? ServerLevel
        val entry = owed[player.uuid]
        if (level == null || entry == null) {
            handOver(player)
            return false
        }
        entry.town = merchant.life?.places?.name
        val key = if (copy) "trade_warrant_1" else "trade_warrant_${merchant.random.nextInt(3)}"
        val line = Talk.ticks(merchant, key)
        val toward: () -> Vec3? = { if (player.isRemoved) null else player.eyePosition }
        val hands: () -> Vec3? = { before(merchant) }
        val moves = ArrayList<Move>()
        moves.add(Turn(toward, 16))
        if (copy) {
            // Looks in the drawer for the hall's copy: the whole of RUMMAGE's loop, so that it comes round to where it began
            moves.add(Beat(Activity.RUMMAGE, Activity.RUMMAGE.ticks, hands) { p, t -> if (t == 0) rustle(p) })
        } else {
            // Takes a sheet from the drawer, writes the name, warms the wax at the lamp and drips it on
            moves.add(Beat(Activity.NONE, 12, toward) { p, t -> if (t == 0) rustle(p) })
            moves.add(Beat(Activity.SCRIBE, 84, hands))
            moves.add(Beat(Activity.NONE, WARM + DRIP, hands) { p, t -> wax(p, t) })
        }
        moves.add(Beat(Activity.STAMP, 20, hands) { p, t -> if (t == 9) seal(p) })
        moves.add(Beat(Activity.NONE, 24, hands) { p, t -> if (t == 4) ribbon(p) })
        moves.add(
            Beat(Activity.HAND_OVER, 30, toward) { p, t ->
                if (t == 0) p.say(key, force = true, hearing = HEARD, ticks = line + 20L)
                if (t == 11) {
                    handOver(player)
                    turnHeads(level, p, player)
                }
            },
        )
        // Lets the line finish before they go back to their work
        moves.add(Beat(Activity.NONE, maxOf(0, line - 30 + 8), toward))
        val aside = Aside(moves, reach = SEEN, most = 900)
        aside.onEnd = { handOver(player) }
        val taken = Aside.take(merchant, aside, seated = true, station = true)
        if (taken) entry.due = level.gameTime + ALLOWED else handOver(player)
        return taken
    }

    /**
     * The sheet is handed to [player] (once): it goes into their pack, or at their feet if that is full, and the rope looks for its knot. A
     * player who is gone from the world or dead is not handed anything and the sheet stays owed, for [tick] to give to whoever answers to
     * their name once it falls due.
     */
    private fun handOver(player: ServerPlayer) {
        if (player.isRemoved || !player.isAlive) return
        val entry = owed.remove(player.uuid) ?: return
        val level = player.level()
        player.inventory.placeItemBackInInventory(WarrantItem.stack(player.name, entry.town), Prediction.SERVER_ONLY)
        level.playSound(null, player.x, player.y, player.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3f, 1.1f)
        if (!entry.copy) Rungs.check(level, player)
    }

    /** The nearest few townsfolk turn their heads to the one who was just given a Warrant. */
    private fun turnHeads(level: ServerLevel, merchant: Townsperson, player: ServerPlayer) {
        level.getEntitiesOfClass(Townsperson::class.java, merchant.boundingBox.inflate(10.0, 3.0, 10.0)) { it !== merchant && it.isAlive }
            .sortedBy { it.distanceToSqr(merchant) }
            .take(4)
            .forEach { it.glance(player, 50) }
    }

    /** Straight before the merchant over the counter, at the height of a sheet on it: what they face and look at as they work there. */
    private fun before(p: Townsperson): Vec3 {
        val yaw = Math.toRadians(p.yBodyRot.toDouble())
        return Vec3(p.x - sin(yaw) * 0.45, p.y + 1.0, p.z + cos(yaw) * 0.45)
    }

    /**
     * Where the merchant's hands work the sheet on the counter, and the seal comes down on it: [SEAL_AHEAD] in front of them and
     * [SEAL_RIGHT] to their right, on the counter's top (the STAMP clip's press point, trade_clips.py's ST_PP): the wax is dripped there.
     */
    private fun hand(p: Townsperson): Vec3 {
        val yaw = Math.toRadians(p.yBodyRot.toDouble())
        return Vec3(p.x - sin(yaw) * SEAL_AHEAD - cos(yaw) * SEAL_RIGHT, p.y + 1.0, p.z + cos(yaw) * SEAL_AHEAD - sin(yaw) * SEAL_RIGHT)
    }

    private fun rustle(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        level.playSound(null, p.x, p.y + 1.0, p.z, SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.8f, 1.1f)
    }

    /** The wax, warmed at the lamp for [WARM] ticks (a wisp of flame at the hands, a crackle) and dripped for [DRIP] (red specks falling on the sheet). */
    private fun wax(p: Townsperson, t: Int) {
        val level = p.level() as? ServerLevel ?: return
        val at = hand(p)
        if (t == 0) level.playSound(null, at.x, at.y, at.z, SoundEvents.CANDLE_AMBIENT, SoundSource.NEUTRAL, 0.8f, 1.0f)
        if (t < WARM) {
            if (t % 3 == 0) level.sendParticles(ParticleTypes.SMALL_FLAME, at.x, at.y + 0.1, at.z, 1, 0.02, 0.0, 0.02, 0.0)
            if (t % 9 == 4) level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.15, at.z, 1, 0.02, 0.02, 0.02, 0.003)
        } else if (t % 2 == 0) {
            level.sendParticles(WAX, at.x, at.y + 0.08 - (t - WARM) * 0.008, at.z, 1, 0.01, 0.0, 0.01, 0.0)
        }
    }

    /** The seal pressed: the thump, a puff of dust and a few specks of wax thrown out. */
    private fun seal(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        val at = hand(p)
        level.playSound(null, at.x, at.y, at.z, TradeSounds.get("trade.warrant_seal"), SoundSource.NEUTRAL, 1.0f, 1.0f)
        level.sendParticles(ParticleTypes.POOF, at.x, at.y + 0.02, at.z, 6, 0.12, 0.02, 0.12, 0.01)
        level.sendParticles(WAX, at.x, at.y + 0.02, at.z, 8, 0.1, 0.01, 0.1, 0.0)
    }

    private fun ribbon(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        level.playSound(null, p.x, p.y + 1.0, p.z, SoundEvents.BUNDLE_INSERT, SoundSource.NEUTRAL, 0.8f, 1.2f)
    }

    private const val WARM = 30
    private const val DRIP = 14

    /** Blocks: how far before the merchant and to their right the STAMP clip's seal lands (13 px and 2.5 px). */
    private const val SEAL_AHEAD = 13.0 / 16.0
    private const val SEAL_RIGHT = 2.5 / 16.0

    /** The lang keys of why a copy is not offered. */
    private const val NONE = "gui.guildmark.trade.ladder.warrant.none"
    private const val CARRIED = "gui.guildmark.trade.ladder.warrant.carried"

    /** One beat of the scene: [activity] for [ticks], facing [at] and looking at it, with [each] heard on each tick of it (counted from 0). */
    private class Beat(
        private val activity: Activity, private val ticks: Int, private val at: () -> Vec3?,
        private val each: (Townsperson, Int) -> Unit = { _, _ -> },
    ) : Move() {
        override fun start(p: Townsperson, a: Aside) {
            p.navigation.stop()
            p.act(activity)
        }

        override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
            at()?.let { there ->
                p.lookAt(there)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
            }
            // Something else played meanwhile (a nod to a passer-by) settles back into the loop
            if (activity.loop && p.activity != activity && (p.activity.loop || p.actDone)) p.act(activity)
            each(p, t)
            return t < ticks
        }
    }
}
