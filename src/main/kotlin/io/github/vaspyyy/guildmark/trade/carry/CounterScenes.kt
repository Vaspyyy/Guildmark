package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Serving
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.EnumSet
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2

/**
 * The counter scenes (living-world/trader/design.md, Part 3, "A buying scene, not a menu"): what the hall's merchant and the town's
 * carter do in front of a player who has just bought something at the Carriers section. The sale is made before any of it starts and
 * the goods are the player's from that moment, so a scene is only ever walked behind a sale: if it cannot run, or stops half way, the
 * goods are where they should be and nothing is lost or blocked.
 *
 * - The harness: the merchant takes it from its peg ([Activity.PACK], the harness in her hands, saying one of three lines) and lays the
 *   whistle across it on the counter ([Activity.HAND_OVER], a buckle's click).
 * - The donkey: the merchant puts the horn to her lips ([Activity.SOUND_HORN]), says her line, and the donkey answers from the yard
 *   behind the hall. It was put there when the Marks were taken, out of the player's sight if there is any such place within reach,
 *   with the town's carter at its head on a lead; the carter sets off at the donkey's own pace, waits for it when it lags, comes up to
 *   the player, hands the lead over ([Activity.HAND_OVER] with the lead in his hand) and touches his hat and goes back the way he came
 *   and is gone once he is out of sight; the donkey, free of the lead, walks up to its new owner and nuzzles them.
 *
 * The merchant's part goes through [Serving]'s own gestures, so it never stands in the way of another deal: a deal that takes her
 * hands ends her part and the rest goes on without her. The merchant stays at her counter whatever happens (she whistles from it
 * rather than walking to the yard door, so a second customer is never left at an empty counter). The carter's part is checked every
 * tick and always ends in [Delivery.finish]: the lead off (never dropped), the carter gone, and the donkey brought beside the player
 * if it did not get as far as the hand-over and is far off, as the whistle's recall does, so a donkey is never left behind in a yard.
 * Nothing is saved; a carter found in a chunk as it loads goes at once.
 */
object CounterScenes {
    /** Every carter wears this tag, so one saved with its chunk is known for what it is. */
    const val TAG = "guildmark.pack_carter"

    /** The looks that suit the work (no basket on the arm, no stoop), as the homesteads' carters. */
    private val LOOKS = intArrayOf(0, 1, 2, 4, 6, 7)

    /** How far from the counter the yard is looked for, and the nearest it may be. */
    private const val YARD_FAR = 18
    private const val YARD_NEAR = 9

    /** The longest a delivery may take, in ticks. */
    private const val TIMEOUT = 1100

    /** Ticks the merchant's horn takes (the carter sets off when it is blown), and the carter's hand-over takes. */
    private const val WHISTLE = 48
    private const val HANDING = 30

    /** When in the whistle the blast sounds (the head is back at the horn from the twelfth tick) and the donkey answers it. */
    private const val BLAST = 14
    private const val ANSWER = 32

    /** How near the carter comes to the player, and how far the donkey may lag before the carter waits for it. */
    private const val CLOSE = 3.4
    private const val LAG = 4.6

    /** The longest the carter waits on a donkey that will not come, and the longest he goes without getting nearer the player (ticks). */
    private const val PATIENCE = 140
    private const val STUCK = 160

    /** How near the donkey comes to its new owner, and how long it is given to get there once the lead is off (ticks). */
    private const val GREET = 2.4f
    private const val GREET_MOST = 160

    /** The longest the carter is on his way back before he is simply gone (ticks), and how far the donkey may be left from a player before it is brought. */
    private const val LEAVE_MOST = 260
    private const val NEAR_ENOUGH = 8f

    private val deliveries = ArrayList<Delivery>()
    private val harnessScenes = ArrayList<HarnessScene>()

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent::class.java) { onJoin(it) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { onLogout(it) }
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent::class.java) { stopping(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    /** A carter from a chunk saved with them in it: gone at once. */
    private fun onJoin(event: EntityJoinLevelEvent) {
        val entity = event.entity
        if (event.loadedFromDisk() && entity is Townsperson && TAG in entity.entityTags()) event.isCanceled = true
    }

    /** A player who leaves has their donkey where they are, not in a yard. */
    private fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (deliveries.isEmpty()) return
        for (delivery in deliveries.filter { it.player == player.uuid }) {
            delivery.cleanup(player)
            deliveries.remove(delivery)
        }
    }

    /** The server is stopping: every delivery is put right while the players are still there, so nothing is saved on a lead. */
    private fun stopping(server: MinecraftServer) {
        for (delivery in deliveries.toList()) delivery.cleanup(server.playerList.getPlayer(delivery.player))
        forget()
    }

    private fun forget() {
        deliveries.clear()
        harnessScenes.clear()
    }

    private fun tick(server: MinecraftServer) {
        if (harnessScenes.isNotEmpty()) {
            harnessScenes.removeAll { scene ->
                try {
                    !scene.tick()
                } catch (e: RuntimeException) {
                    Guildmark.LOGGER.warn("The harness scene at the counter failed", e)
                    scene.end()
                    true
                }
            }
        }
        if (deliveries.isNotEmpty()) {
            deliveries.removeAll { delivery ->
                try {
                    !delivery.tick(server)
                } catch (e: RuntimeException) {
                    Guildmark.LOGGER.warn("The carter's delivery failed", e)
                    delivery.cleanup(server.playerList.getPlayer(delivery.player))
                    true
                }
            }
        }
    }

    // ---- The harness ----

    /** The harness's scene, with [merchant] at the counter. */
    fun harness(player: ServerPlayer, merchant: Townsperson) {
        val serving = merchant.director as? Serving ?: return
        harnessScenes.removeAll { it.merchant === merchant }
        harnessScenes.add(HarnessScene(merchant, serving, "trade_sell_harness_${merchant.random.nextInt(3)}"))
    }

    /** The merchant takes the harness from its peg and lays the whistle across it on the counter. */
    private class HarnessScene(val merchant: Townsperson, private val serving: Serving, line: String) {
        private var age = 0
        private var laid = false

        init {
            serving.gesture(merchant, Activity.PACK, line)
            merchant.carried = ItemStack(ModItems.PACK_HARNESS.get())
        }

        /** One tick of the scene; false once it is over. */
        fun tick(): Boolean {
            if (merchant.isRemoved || !merchant.isAlive || merchant.director !== serving) return finished()
            age++
            // The first gesture has played through by itself (a tick after, to be sure); the second is laid across it
            if (!laid && age > Activity.PACK.ticks) {
                laid = true
                // A deal that has taken her hands since has ended this
                if (merchant.activity != Activity.NONE && merchant.activity != Activity.TALK && merchant.activity != Activity.PACK) return finished()
                serving.gesture(merchant, Activity.HAND_OVER, null)
                merchant.carried = ItemStack(ModItems.DROVERS_WHISTLE.get())
                merchant.level().playSound(null, merchant.x, merchant.y + 1.0, merchant.z, TradeSounds.get("trade.harness_buckle"), SoundSource.NEUTRAL, 0.8f, 1.0f)
            }
            return age <= Activity.PACK.ticks + Activity.HAND_OVER.ticks + 1 || finished()
        }

        private fun finished(): Boolean {
            end()
            return false
        }

        /** Whatever she still holds of this is put down. */
        fun end() {
            if (merchant.isRemoved) return
            val held = merchant.carried.item
            if (held == ModItems.PACK_HARNESS.get() || held == ModItems.DROVERS_WHISTLE.get()) merchant.carried = ItemStack.EMPTY
        }
    }

    // ---- The donkey ----

    /**
     * Puts the donkey [player] has bought where the scene starts: in the yard behind the hall with the carter at its head if there is a
     * way round to the counter (out of the player's sight if there is a place that is), else beside the player. True once there is a
     * donkey; false if there was no room anywhere and nothing was made.
     */
    fun buyDonkey(player: ServerPlayer): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val merchant = CarryShop.counter(player)
        val yard = if (merchant != null) yardFor(level, merchant, player) else null
        if (yard != null) {
            val delivery = try {
                Delivery.start(level, player, yard)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("The carter's delivery could not be set up", e)
                null
            }
            if (delivery != null) {
                deliveries.add(delivery)
                return true
            }
        }
        // No way round to bring it by: it is beside the player, in a puff of the yard's dust
        val donkey = CarryShop.donkeyBeside(player) ?: return false
        level.sendParticles(ParticleTypes.CLOUD, donkey.x, donkey.y + 0.5, donkey.z, 6, 0.3, 0.3, 0.3, 0.01)
        return true
    }

    /** The donkey's scene, with [merchant] at the counter: the horn that calls it, and the line that goes with it. */
    fun donkey(player: ServerPlayer, merchant: Townsperson) {
        val serving = merchant.director as? Serving ?: return
        val delivery = deliveries.lastOrNull { it.player == player.uuid && it.merchant == null }
        val line = "trade_sell_donkey_${merchant.random.nextInt(3)}"
        if (delivery == null) {
            // The donkey is beside the player already: a word and a nod will do
            serving.gesture(merchant, Activity.NOD, line)
            return
        }
        delivery.merchant = merchant
        serving.gesture(merchant, Activity.SOUND_HORN, line)
    }

    /**
     * A place for the carter to bring the donkey from: out in the open and 9 to 18 blocks from the merchant, with a way from there to
     * the counter, hidden from [player]'s eyes if any such place is among those looked at. Null if none has a way.
     */
    private fun yardFor(level: ServerLevel, merchant: Townsperson, player: ServerPlayer): BlockPos? {
        val eye = player.eyePosition
        var best: BlockPos? = null
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(8) {
            val pos = Places.street(level, merchant.blockPosition(), YARD_FAR, level.random, YARD_NEAR, 4) ?: return@repeat
            val path = merchant.navigation.createPath(pos, 1)
            if (path == null || !path.canReach()) return@repeat
            val centre = Vec3.atCenterOf(pos)
            val seen = level.clip(ClipContext(eye, centre.add(0.0, 1.0, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS
            val distance = Math.sqrt(player.distanceToSqr(centre))
            val score = (if (seen) 0.0 else 100.0) - abs(distance - 14.0)
            if (score > bestScore) {
                bestScore = score
                best = pos
            }
        }
        return best
    }

    /** The carter's day while he is bringing a donkey: nothing of his own, the delivery drives him. */
    private object Led : Director {
        override fun tick(p: Townsperson) {
            // A one-shot (the hat, the nod) settles back into walking arms
            if (p.actDone) p.act(Activity.NONE)
        }

        override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
            p.lookAt(player)
            p.attend(player, 40)
            p.act(Activity.NOD_WALK)
            return true
        }
    }

    /** The donkey follows whoever has it on a lead, and stands by them. */
    private class LeadGoal(private val donkey: AbstractChestedHorse, private val carter: Townsperson) : Goal() {
        private var repath = 0

        init {
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK))
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun canUse(): Boolean = donkey.isAlive && carter.isAlive && donkey.distanceToSqr(carter) > 2.6 * 2.6

        override fun canContinueToUse(): Boolean = donkey.isAlive && carter.isAlive && donkey.distanceToSqr(carter) > 1.7 * 1.7

        override fun start() {
            repath = 0
        }

        override fun stop() {
            donkey.navigation.stop()
        }

        override fun tick() {
            donkey.lookControl.setLookAt(carter, 30f, donkey.maxHeadXRot.toFloat())
            if (--repath > 0) return
            repath = 8
            donkey.navigation.moveTo(carter, 1.0)
        }
    }

    /** One donkey being brought round by one carter to one player. */
    private class Delivery(
        val player: UUID,
        private val donkey: AbstractChestedHorse,
        private val carter: Townsperson,
        private val yard: BlockPos,
        private val lead: LeadGoal,
    ) {
        /** The merchant at the counter, once the scene is told of her: the horn sounds at her. */
        var merchant: Townsperson? = null

        /** Whether the lead has been handed over: the donkey is the player's, with nobody's hand on it. */
        var handed = false
            private set

        private enum class Stage { WHISTLE, COMING, HAND_OVER, LEAVING }

        private val walker = Walker(carter)

        /** The carter's pace, a little under the donkey's own, so that it keeps up with a slack lead. */
        private val pace = (donkey.getAttributeValue(Attributes.MOVEMENT_SPEED) * 0.92 / carter.getAttributeValue(Attributes.MOVEMENT_SPEED)).coerceIn(0.26, 0.6)

        private var stage = Stage.WHISTLE
        private var age = 0
        private var stageAge = 0
        private var best = Double.MAX_VALUE
        private var bestAt = 0
        private var waited = 0
        private var handedAt = 0
        private var greeted = false
        private var gone = false

        companion object {
            private const val LEAD_PRIORITY = 2

            /** Makes the carter and the donkey in the yard at [yard], with the donkey on the carter's lead. Null if either could not be made. */
            fun start(level: ServerLevel, owner: ServerPlayer, yard: BlockPos): Delivery? {
                val toward = Vec3.atCenterOf(yard)
                val yaw = (atan2(owner.z - toward.z, owner.x - toward.x) * (180.0 / Math.PI)).toFloat() - 90f
                val carter = Townsfolk.create(level, yard, TownRole.CITIZEN, LOOKS[level.random.nextInt(LOOKS.size)], yaw, EntitySpawnReason.EVENT) ?: return null
                carter.setPermanentlyInvulnerable(true)
                // Nothing can hurt him, so he does not drop the lead and run from a passing monster (Townsperson.FleeGoal)
                carter.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
                carter.addTag(TAG)
                carter.setDropChance(EquipmentSlot.MAINHAND, 0f)
                carter.setDropChance(EquipmentSlot.OFFHAND, 0f)
                carter.director = Led
                val donkey = Harness.tame(owner, EntityTypes.DONKEY, Vec3.atBottomCenterOf(beside(level, yard)), EntitySpawnReason.EVENT)
                if (donkey == null) {
                    carter.discard()
                    return null
                }
                try {
                    if (!level.addFreshEntity(carter)) {
                        donkey.discard()
                        return null
                    }
                    val lead = LeadGoal(donkey, carter)
                    donkey.goalSelector.addGoal(LEAD_PRIORITY, lead)
                    donkey.setLeashedTo(carter, true)
                    level.sendParticles(ParticleTypes.POOF, carter.x, carter.y + 0.6, carter.z, 6, 0.3, 0.4, 0.3, 0.02)
                    return Delivery(owner.uuid, donkey, carter, yard, lead)
                } catch (e: RuntimeException) {
                    // Nothing of it is left standing in the yard
                    carter.discard()
                    donkey.discard()
                    throw e
                }
            }

            /** A spot beside [yard] for the donkey to stand, or the yard itself. */
            private fun beside(level: ServerLevel, yard: BlockPos): BlockPos {
                for (d in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to -1)) {
                    val at = yard.offset(d.first, 0, d.second)
                    if (Places.standable(level, at)) return at
                }
                return yard
            }
        }

        /** One tick of the delivery; false once it is over (and put right). */
        fun tick(server: MinecraftServer): Boolean {
            val level = carter.level() as? ServerLevel
            val owner = server.playerList.getPlayer(player)
            if (level == null || owner == null || owner.level() !== level) {
                finish(owner, forced = !handed)
                return false
            }
            val carterLost = !gone && (carter.isRemoved || !carter.isAlive)
            if (carterLost || donkey.isRemoved || !donkey.isAlive || ++age > TIMEOUT) {
                finish(owner, forced = !handed)
                return false
            }
            stageAge++
            when (stage) {
                Stage.WHISTLE -> whistle(level)
                Stage.COMING -> if (!coming(owner)) {
                    finish(owner, forced = true)
                    return false
                }
                Stage.HAND_OVER -> handOver(owner)
                Stage.LEAVING -> leaving(owner)
            }
            if (handed) greet(level, owner)
            if (gone && (greeted || age - handedAt > GREET_MOST)) {
                finish(owner, forced = false)
                return false
            }
            return true
        }

        /** The horn is blown (once the merchant has been told of the scene), and the donkey answers it; then the carter sets off. */
        private fun whistle(level: ServerLevel) {
            val m = merchant
            if (stageAge == BLAST && m != null && m.isAlive && !m.isRemoved) {
                level.playSound(null, m.x, m.y + 1.0, m.z, TradeSounds.get("trade.whistle_come"), SoundSource.NEUTRAL, 1.0f, 1.0f)
            }
            if (stageAge == ANSWER) donkey.playAmbientSound()
            // Both have an ear to the horn
            carter.lookControl.setLookAt(donkey, 30f, 30f)
            if (stageAge > BLAST) donkey.lookControl.setLookAt(carter, 30f, 30f)
            if (stageAge >= WHISTLE) next(Stage.COMING)
        }

        /** Round to the player, at a pace the donkey can keep, waiting for it when it lags. False if the carter is getting nowhere. */
        private fun coming(owner: ServerPlayer): Boolean {
            // The lead can slip (a kick, a fall): put it back while the donkey is near enough to be the carter's
            if (!donkey.isLeashed && donkey.distanceTo(carter) < 6f) donkey.setLeashedTo(carter, true)
            val gap = carter.distanceTo(donkey)
            val toPlayer = carter.distanceTo(owner).toDouble()
            if (toPlayer <= CLOSE && gap <= LAG) {
                walker.stop()
                next(Stage.HAND_OVER)
                return true
            }
            if (gap > LAG) {
                // The donkey is behind: stop and let it catch up
                if (walker.target != null) walker.stop()
                carter.lookControl.setLookAt(donkey, 30f, 30f)
                return ++waited <= PATIENCE
            }
            val goal = owner.blockPosition()
            val at = walker.target
            if (at == null || at.distSqr(goal) > 9.0) walker.go(goal, pace, CLOSE - 0.6)
            if (walker.tick() == Walker.State.FAILED) return false
            if (toPlayer < best - 1.0) {
                best = toPlayer
                bestAt = age
            }
            // Getting no nearer, and not for waiting on the donkey: this will not work
            return age - bestAt < STUCK
        }

        /** The lead is handed over: the carter faces the player with it held out, lets go of it half way, and the donkey looks to its new owner. */
        private fun handOver(owner: ServerPlayer) {
            carter.navigation.stop()
            carter.lookControl.setLookAt(owner, 30f, 30f)
            carter.face(Mth.approachDegrees(carter.yBodyRot, carter.yawTo(owner.position()), 24f))
            if (stageAge == 1) {
                carter.carried = ItemStack(Items.LEAD)
                carter.act(Activity.HAND_OVER)
            }
            if (stageAge == HANDING / 2 && !handed) {
                handed = true
                handedAt = age
                donkey.removeLeash()
                donkey.goalSelector.removeGoal(lead)
                carter.carried = ItemStack.EMPTY
            }
            if (stageAge >= HANDING) next(Stage.LEAVING)
        }

        /** The carter touches his hat and goes back the way he came, and is gone once he is out of the way. */
        private fun leaving(owner: ServerPlayer) {
            if (gone) return
            if (stageAge == 1) carter.act(Activity.TIP_HAT)
            val hat = Activity.TIP_HAT.ticks
            if (stageAge == hat + 1) walker.go(yard, pace * 1.1, 1.5)
            val arrived = stageAge > hat + 1 && walker.tick() != Walker.State.WALKING
            val away = carter.distanceTo(owner)
            if (arrived || away > 24f || away > 8f && !owner.hasLineOfSight(carter) || stageAge > LEAVE_MOST) vanish()
        }

        /** The donkey, free of the lead, goes up to its new owner and nuzzles them. */
        private fun greet(level: ServerLevel, owner: ServerPlayer) {
            if (greeted) return
            donkey.lookControl.setLookAt(owner, 30f, 30f)
            if (donkey.distanceTo(owner) > GREET) {
                if (age % 10 == 0) donkey.navigation.moveTo(owner, 1.0)
                return
            }
            greeted = true
            donkey.navigation.stop()
            level.sendParticles(ParticleTypes.HEART, donkey.x, donkey.y + donkey.bbHeight + 0.2, donkey.z, 3, 0.3, 0.3, 0.3, 0.02)
            donkey.playAmbientSound()
        }

        private fun next(to: Stage) {
            stage = to
            stageAge = 0
        }

        /** The carter is gone in a puff. */
        private fun vanish() {
            if (gone) return
            gone = true
            if (carter.isRemoved) return
            (carter.level() as? ServerLevel)?.sendParticles(ParticleTypes.POOF, carter.x, carter.y + 0.6, carter.z, 8, 0.3, 0.4, 0.3, 0.02)
            carter.carried = ItemStack.EMPTY
            carter.director = null
            carter.discard()
        }

        /**
         * Puts everything right: the lead off the donkey (never dropped), the carter gone, and, if the delivery did not get as far as the
         * hand-over and the donkey is far from [owner] (it is theirs and must not be left in a yard), the donkey beside them as the
         * whistle's recall would have it.
         */
        fun finish(owner: ServerPlayer?, forced: Boolean) {
            if (!donkey.isRemoved) {
                if (donkey.isLeashed) donkey.removeLeash()
                donkey.goalSelector.removeGoal(lead)
                donkey.navigation.stop()
            }
            vanish()
            if (forced && owner != null && owner.isAlive && !donkey.isRemoved && donkey.level() === owner.level() && donkey.distanceTo(owner) > NEAR_ENOUGH) {
                PackFollow.bring(donkey, owner)
            }
        }

        /** [finish] for a delivery that is stopped from outside (the player leaves, the server stops, something went wrong). */
        fun cleanup(owner: ServerPlayer?) {
            finish(owner, forced = !handed)
        }
    }
}
