package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.companion.Companions
import io.github.vaspyyy.guildmark.companion.Deed
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.story.StoryCharacter
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Idle
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Social
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.acos
import kotlin.math.min

/** A building a faction job robs: what's taken from it and who keeps watch. */
enum class Heist(val id: String, val prize: String, val watchRole: TownRole, val watchers: Int) {
    /** House Varn's ledger, from a manor in the noble quarter (the Quiet Hand's second chapter). */
    MANOR("manor", "varn_ledger", TownRole.GUARD, 2),
    /** The Ember of the First Hearth, from the Hearthsworn's chapter house (the Quiet Hand's fourth). */
    RELIQUARY("reliquary", "hearth_ember", TownRole.KNIGHT, 2),
    /** The Regent's seal, from the palace treasury (the Quiet Hand's last). */
    VAULT("vault", "regent_seal", TownRole.GUARD, 4),
    /** The Widow's own ledger, from the Quiet Hand's den in the tavern attic (the Hearthsworn's fourth). */
    DEN("den", "widows_ledger", TownRole.THIEF, 2);

    /** The faction job's kind (the contract's target path). */
    val kind: String get() = "heist_$id"

    val prizeName: Component get() = Component.translatable("item.guildmark.heist_prize.$prize")

    companion object {
        fun byKind(kind: String): Heist? = entries.firstOrNull { it.kind == kind }
    }
}

/**
 * The heists: a prize put in a chest inside a watched building, to be taken and carried out without anyone raising
 * the alarm. Everyone awake in there (the watchers set to keep an eye on the place, and whoever lives or works there)
 * grows suspicious of a player they can see: faster close up, in the light and on the move; slower for someone
 * crouching in the dark, and the Stealth bonus slows it more. Out of sight it fades. Half way they stop and look
 * ("Hm? Who's there?"); full, they raise the alarm, the job is off and the guards come running. The eye on the HUD
 * shows how near the closest of them is (SuspicionHud). The prize carried out of the building and clear of it,
 * with nobody's eye on the player, is the job done.
 */
object Heists {
    /** Sets up a newly taken heist; null if the building can't be found here. */
    fun begin(level: ServerLevel, player: Player, heist: Heist, state: ContractState, owner: StoryCharacter?): ContractState? {
        val site = FactionSeats.site(level, heist, state.boardPos) ?: return null
        player.sendSystemMessage(
            Component.translatable("message.guildmark.heist_begin", heist.prizeName, Component.translatable("message.guildmark.heist_place.${heist.id}")),
        )
        return state.copy(destination = site.entrance)
    }

    /** Whether [player] carries [heist]'s prize. */
    fun carries(player: Player, heist: Heist): Boolean = (0 until player.inventory.containerSize).any { slot ->
        player.inventory.getItem(slot).get(FactionRegistry.PRIZE.get()) == heist.prize
    }

    /** Takes [heist]'s prize off [player], wherever they carry it. */
    fun confiscate(player: Player, heist: Heist) {
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            if (inventory.getItem(slot).get(FactionRegistry.PRIZE.get()) == heist.prize) inventory.setItem(slot, ItemStack.EMPTY)
        }
    }

    /** How near to the building before the prize and the watch are set out, and how far before they're taken in again. */
    private const val NEAR = 56.0
    private const val FAR = 96.0
    /** How far clear of the building the prize has to be carried. */
    private const val CLEAR = 6.0
    /** Blocks a townsperson sees, how wide, and how close they notice someone they can't see. */
    private const val SIGHT = 16.0
    private const val FOV = 70.0
    private const val SENSE = 2.5
    /** Suspicion a tick: seen point-blank, sensed close by; and how fast it fades out of sight. */
    private const val SEEN = 2.2f
    private const val SENSED = 3.0f
    private const val FADE = 0.5f
    private const val SUSPECT = 50f
    private const val CALM = 30f
    private const val ALARM = 100f

    /** A heist under way for one player. */
    class Run(player: ServerPlayer, private val heist: Heist, private val owner: StoryCharacter?) : TrialRun(player) {
        private var site: Site? = null
        private val watchers = ArrayList<Townsperson>()
        /** Each townsperson's suspicion of the player, out of 100, by entity id. */
        private val suspicion = HashMap<Int, Float>()
        private val suspecting = HashSet<Int>()
        /** Whether this run put a prize in the chest (and so takes one out again when it stands down). */
        private var placed = false
        private var taken = false
        private var shown = -1
        private var shownBy = -1

        /** The job is under way about the player: they're at the building, and its watchers are posted. */
        val underWay: Boolean get() = watchers.isNotEmpty()

        override fun tick(level: ServerLevel, stack: ItemStack, note: QuestNote, state: ContractState): Boolean {
            val site = site ?: FactionSeats.site(level, heist, state.destination ?: state.boardPos)?.also { site = it } ?: return true
            if (state.progress >= note.count) return true
            val centre = AABB.of(site.box).center
            val far = player.distanceToSqr(centre)
            if (far > FAR * FAR || player.level() !== level) {
                standDown(level)
                return true
            }
            if (far < NEAR * NEAR && Places.loaded(level, site.chest, 12)) {
                // Looked at now and then, not once: another player on the same job may have taken theirs, or taken it in
                if (player.tickCount % 20 == 7) place(level, site)
                if (watchers.isEmpty()) post(level, site)
            }
            watchers.removeIf { !it.isAlive || it.isRemoved }

            // The prize in hand: away with it
            val holding = carries(player, heist)
            if (holding && !taken) {
                taken = true
                // What this run put out is in their hands now: nothing of its own left in the chest to take back
                placed = false
                player.sendSystemMessage(Component.translatable("message.guildmark.heist_taken", heist.prizeName, owner?.title ?: Component.empty()))
                level.playSound(null, player.x, player.y, player.z, FactionRegistry.HEIST_LOOT.get(), SoundSource.PLAYERS, 0.7f, 1f)
            } else if (!holding) {
                taken = false
            }

            if (!watch(level, site)) {
                alarm(level, stack)
                return false
            }
            if (taken && suspecting.isEmpty() && !AABB.of(site.box).inflate(CLEAR).contains(player.position())) {
                Companions.deed(player, Deed.HEIST)
                Trials.complete(player, stack)
                return false
            }
            return true
        }

        /** Everyone awake in the building looks for the player; false if one of them raises the alarm. */
        private fun watch(level: ServerLevel, site: Site): Boolean {
            val area = AABB.of(site.box).inflate(1.0)
            val inside = !player.isSpectator && !player.isCreative && player.isAlive && area.contains(player.position())
            var top = 0f
            var topId = -1
            val seen = HashSet<Int>()
            for (p in level.getEntitiesOfClass(Townsperson::class.java, area.inflate(4.0))) {
                if (!p.isAlive) continue
                seen.add(p.id)
                val rate = if (inside) rate(level, p) else 0f
                val old = suspicion[p.id] ?: 0f
                val now = if (rate > 0f) (old + rate).coerceAtMost(ALARM) else (old - FADE).coerceAtLeast(0f)
                if (now <= 0f) suspicion.remove(p.id) else suspicion[p.id] = now
                if (now >= ALARM) {
                    raise(level, p)
                    return false
                }
                if (now >= SUSPECT && suspecting.add(p.id)) suspect(level, p)
                else if (now < CALM && suspecting.remove(p.id)) calm(p)
                if (p.id in suspecting) {
                    p.navigation.stop()
                    p.lookControl.setLookAt(player, 30f, 30f)
                }
                if (now > top) {
                    top = now
                    topId = p.id
                }
            }
            // Those who've gone (out of range, or gone in) stop wondering
            suspicion.keys.retainAll(seen)
            suspecting.retainAll(seen)
            show(top.toInt(), topId)
            return true
        }

        /** How much more suspicious [p] grows of the player this tick: 0 if they can't see or sense them. */
        private fun rate(level: ServerLevel, p: Townsperson): Float {
            if (p.isSleeping || p.dozing || !p.role.adult) {
                // A sleeper stirs only at someone right beside them
                return if (p.distanceTo(player) <= SENSE * 0.6) SENSED * 0.25f else 0f
            }
            val to = player.position().add(0.0, player.bbHeight * 0.6, 0.0).subtract(p.eyePosition)
            val distance = to.length()
            if (distance > SIGHT) return 0f
            val sensed = distance <= SENSE
            if (!sensed) {
                val angle = Math.toDegrees(acos(p.getViewVector(1f).dot(to.normalize()).coerceIn(-1.0, 1.0)))
                if (angle > FOV || !p.hasLineOfSight(player)) return 0f
            }
            var rate = if (sensed) SENSED else SEEN * (1f - distance.toFloat() / (SIGHT.toFloat() + 4f))
            val light = level.getMaxLocalRawBrightness(player.blockPosition())
            rate *= 0.25f + 0.75f * light / 15f
            if (player.isCrouching) rate *= 0.45f
            if (player.isSprinting) rate *= 1.6f
            if (player.isInvisible) rate *= 0.15f
            rate *= (1.0 - min(0.6, Talents[player, Bonus.STEALTH])).toFloat()
            return rate
        }

        private fun suspect(level: ServerLevel, p: Townsperson) {
            p.suspect = player
            p.face(p.yawTo(player.position()))
            p.say("suspect_${p.random.nextInt(3)}", force = true)
            player.sendOverlayMessage(Component.translatable("message.guildmark.heist_suspect"))
            level.playSound(null, p.x, p.y, p.z, FactionRegistry.HEIST_SUSPICION.get(), SoundSource.NEUTRAL, 0.6f, 1f)
        }

        private fun calm(p: Townsperson) {
            if (p.suspect === player) p.suspect = null
            p.say("calm_${p.random.nextInt(2)}", force = true)
        }

        /** [p] has seen enough: they point and shout, and the guards near come running. */
        private fun raise(level: ServerLevel, p: Townsperson) {
            p.navigation.stop()
            p.face(p.yawTo(player.position()))
            p.act(Activity.SHOUT)
            p.say("alarm_${p.random.nextInt(3)}", force = true)
            level.playSound(null, p.x, p.y, p.z, FactionRegistry.HEIST_ALARM.get(), SoundSource.NEUTRAL, 1.4f, 1f)
            Social.alarm(level, p, player)
        }

        /** The job is off: the contract and anything taken go. */
        private fun alarm(level: ServerLevel, stack: ItemStack) {
            stack.shrink(stack.count)
            confiscate(player, heist)
            player.sendSystemMessage(Component.translatable("message.guildmark.heist_alarm", owner?.title ?: Component.empty()))
        }

        private fun show(amount: Int, by: Int) {
            val level = amount.coerceIn(0, 100)
            if (level == shown && (by == shownBy || level == 0) && player.tickCount % 20 != 0) return
            shown = level
            shownBy = by
            PacketDistributor.sendToPlayer(player, SuspicionPayload(level, if (level > 0) by else -1))
        }

        // ---- Setting out and taking in ----

        /**
         * Puts the prize in the building's chest (a chest set down if it's gone), unless one is there already (from
         * before, or another player's) or the player has it.
         */
        private fun place(level: ServerLevel, site: Site) {
            if (carries(player, heist)) return
            var container = level.getBlockEntity(site.chest) as? Container
            if (container == null) {
                if (!level.getBlockState(site.chest).canBeReplaced()) return
                level.setBlock(site.chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL)
                container = level.getBlockEntity(site.chest) as? Container ?: return
            }
            if ((0 until container.containerSize).any { container.getItem(it).get(FactionRegistry.PRIZE.get()) == heist.prize }) return
            val slot = (0 until container.containerSize).firstOrNull { container.getItem(it).isEmpty } ?: (container.containerSize / 2)
            container.setItem(slot, HeistPrizeItem.of(heist.prize))
            container.setChanged()
            placed = true
        }

        /** Takes back the prize this run put out, if it's still there: never one another player's job put there. */
        private fun unplace(level: ServerLevel, site: Site) {
            if (!placed || !level.isLoaded(site.chest)) return
            placed = false
            val container = level.getBlockEntity(site.chest) as? Container ?: return
            val slot = (0 until container.containerSize).firstOrNull { container.getItem(it).get(FactionRegistry.PRIZE.get()) == heist.prize } ?: return
            container.setItem(slot, ItemStack.EMPTY)
            container.setChanged()
        }

        /** Sets the watch: [Heist.watchers] of its role walking the building's posts. */
        private fun post(level: ServerLevel, site: Site) {
            val town = FactionSeats.layoutAt(level, site.entrance)?.town
            val posts = site.posts.filter { site.box.isInside(it) }.ifEmpty { site.posts }
            repeat(heist.watchers) { i ->
                val near = posts[(i * 7 + level.random.nextInt(posts.size)) % posts.size]
                val at = if (Places.standable(level, near)) near else FactionSeats.standNear(level, near) ?: return@repeat
                val look = 1 + level.random.nextInt((heist.watchRole.looks - 1).coerceAtLeast(1))
                val watcher = Townsfolk.create(level, at, heist.watchRole, if (heist.watchRole.looks > 1 && Faction.of(heist.watchRole) != null) look else -1,
                    level.random.nextFloat() * 360f, EntitySpawnReason.EVENT) ?: return@repeat
                watcher.watcher = true
                watcher.patrol = posts
                watcher.home = at
                watcher.town = town
                level.addFreshEntity(watcher)
                watchers.add(watcher)
            }
        }

        /** The watch goes home: back among the passers-by, who leave once nobody's looking. */
        private fun release(level: ServerLevel) {
            for (watcher in watchers) {
                if (!watcher.isAlive) continue
                watcher.watcher = false
                watcher.suspect = null
                watcher.crowd = true
                watcher.leaveAt = level.gameTime
                watcher.routine.replace(Idle(watcher, 20 + watcher.random.nextInt(40)))
            }
            watchers.clear()
        }

        /** Too far off for any of it to matter: the watch and the prize are taken in until the player's back. */
        private fun standDown(level: ServerLevel) {
            if (watchers.isEmpty() && !placed) return
            release(level)
            site?.let { unplace(level, it) }
            suspicion.clear()
            suspecting.clear()
            show(0, -1)
        }

        override fun stop(level: ServerLevel) {
            release(level)
            site?.let { unplace(level, it) }
            for (p in level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(FAR))) if (p.suspect === player) p.suspect = null
            show(0, -1)
        }
    }

}
