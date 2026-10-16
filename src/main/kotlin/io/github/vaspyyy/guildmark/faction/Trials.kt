package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.enemy.DuskRider
import io.github.vaspyyy.guildmark.field.NightRiders
import io.github.vaspyyy.guildmark.magic.CatalystItem
import io.github.vaspyyy.guildmark.magic.MagicRegistry
import io.github.vaspyyy.guildmark.magic.School
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.story.StoryCharacter
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Towns
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.Difficulty
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import java.util.UUID

/**
 * A faction job under way for one player: what it set up in the world (lights, a sparring knight, a heist's
 * watchers, the Long Night's waves, the renegade's ring), ticked every tick while they carry its contract.
 */
abstract class TrialRun(val player: ServerPlayer) {
    /**
     * One tick, with the contract's [stack] as it stands; false once there's nothing more for it to do (done, called
     * off or failed), when it's stopped and dropped (a contract still unfinished starts a fresh run next time).
     */
    abstract fun tick(level: ServerLevel, stack: ItemStack, note: QuestNote, state: ContractState): Boolean

    /** Takes down whatever it set up. */
    open fun stop(level: ServerLevel) {}
}

/**
 * The factions' own kinds of job (QuestType.TRIAL, the kind being the note's target path): setting each up when it's
 * taken, running it while it's carried, counting it and handing it over. See FACTIONS in the faction package docs:
 *
 * - `ward_lights`: break the Starwatch's ward-lights over the plaza with sorcery (WardLight);
 * - `spar`: a bout with a sworn knight in the training yard, to a yield (SwornKnight, [Spar]);
 * - `pickpocket`, `purses`: purses lifted unseen (Pickpocket); nobles' only for the first;
 * - `heist_manor`, `heist_reliquary`, `heist_vault`, `heist_den`: a prize stolen from a watched building (Heists);
 * - `hold`: a Wardfire on the road held from nightfall against three waves of Hollows (Hold);
 * - `dusk_rider`: the Dusk Rider brought down;
 * - `renegade`: Silvan Marrow defeated in his ring of stones (Renegade);
 * - `kindle`: Wardfires gone cold lit again (a companion's chapter, Ysolde's: see CompanionKind).
 */
object Trials {
    /** Each player's runs, by contract (its chain and kind). */
    private val runs = HashMap<UUID, HashMap<String, TrialRun>>()

    fun clear() = runs.clear()

    fun kind(note: QuestNote): String = note.target.path

    private fun key(note: QuestNote): String = "${note.chain}/${note.target.path}"

    // ---- Taking one ----

    /** Sets up a newly taken faction job; null (with word why) if it can't be done here. */
    fun begin(level: ServerLevel, player: Player, note: QuestNote, state: ContractState): ContractState? {
        val owner = StoryCharacter.ownerOf(note.chain)
        return when (val kind = kind(note)) {
            "ward_lights" -> {
                val seat = FactionSeats.seat(level, Faction.STARWATCH, state.boardPos) ?: return noSeat(player)
                if (!hasCatalyst(player, School.SORCERY)) {
                    player.inventory.placeItemBackInInventory(ItemStack(MagicRegistry.AMETHYST_STAFF.get()), Prediction.SERVER_ONLY)
                    player.sendSystemMessage(Component.translatable("message.guildmark.staff_lent", owner?.title ?: Component.literal("")))
                }
                player.sendSystemMessage(Component.translatable("message.guildmark.ward_lights"))
                state.copy(destination = seat.square)
            }
            "spar" -> Spar.begin(level, player, state) ?: noSeat(player)
            "pickpocket", "purses" -> state
            "hold" -> Hold.begin(level, player, state) ?: noSeat(player)
            "dusk_rider" -> {
                player.sendSystemMessage(Component.translatable("message.guildmark.rider_hunt"))
                state
            }
            "renegade" -> Renegade.begin(level, player, state)
            "kindle" -> {
                player.sendSystemMessage(Component.translatable("message.guildmark.kindle_trial"))
                state
            }
            else -> {
                val heist = Heist.byKind(kind) ?: return state
                Heists.begin(level, player, heist, state, owner) ?: noSeat(player)
            }
        }
    }

    private fun noSeat(player: Player): ContractState? {
        player.sendOverlayMessage(Component.translatable("message.guildmark.no_seat"))
        return null
    }

    private fun hasCatalyst(player: Player, school: School): Boolean = (0 until player.inventory.containerSize).any { slot ->
        (player.inventory.getItem(slot).item as? CatalystItem)?.school == school
    }

    // ---- Running ----

    /** Every tick, for each player: runs the faction jobs they carry (looking for new ones once a second). */
    fun tick(level: ServerLevel, player: ServerPlayer) {
        val mine = runs[player.uuid]
        if (mine.isNullOrEmpty() && player.tickCount % 20 != 3) return
        val seen = HashSet<String>()
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            if (note.type != QuestType.TRIAL) continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.dimension != level.dimension()) continue
            val key = key(note)
            if (!seen.add(key)) continue
            var run = mine?.get(key)
            // Set up for who they were before they died: a respawned player is a new one, so it starts again for them
            if (run != null && run.player !== player) {
                run.stop(level)
                mine?.remove(key)
                run = null
            }
            if (run == null) {
                if (state.progress >= note.count || state.isExpired(level.gameTime)) continue
                run = start(level, player, note, state) ?: continue
                runs.getOrPut(player.uuid) { HashMap() }[key] = run
            }
            if (!run.tick(level, stack, note, stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: state)) {
                run.stop(level)
                runs[player.uuid]?.remove(key)
            }
        }
        // Contracts handed in, dropped or run out: what they set up goes
        mine?.entries?.removeIf { (key, run) ->
            val gone = key !in seen
            if (gone) run.stop(level)
            gone
        }
    }

    private fun start(level: ServerLevel, player: ServerPlayer, note: QuestNote, state: ContractState): TrialRun? = when (val kind = kind(note)) {
        "ward_lights" -> WardLights(player)
        "spar" -> Spar(player)
        "hold" -> Hold(player)
        "dusk_rider" -> RiderHunt(player)
        "renegade" -> Renegade.Hunt(player)
        else -> Heist.byKind(kind)?.let { Heists.Run(player, it, StoryCharacter.ownerOf(note.chain)) }
    }

    /** [player] left: everything their jobs set up goes (it's set up afresh when they're back). */
    fun forget(player: Entity) {
        val level = player.level() as? ServerLevel ?: return
        runs.remove(player.uuid)?.values?.forEach { it.stop(level) }
    }

    /** The run of [player]'s contract of [kind], if one is going. */
    fun <T : TrialRun> running(player: Player, kind: Class<T>): T? = runs[player.uuid]?.values?.firstNotNullOfOrNull { if (kind.isInstance(it)) kind.cast(it) else null }

    // ---- Counting ----

    /**
     * Counts [amount] more toward [player]'s first unfinished contract of [kind] (and tells them when it's done);
     * false if they carry none.
     */
    fun credit(player: Player, kind: String, amount: Int = 1): Boolean {
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            if (note.type != QuestType.TRIAL || kind(note) != kind) continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.progress >= note.count || state.isExpired(player.level().gameTime)) continue
            val progress = (state.progress + amount).coerceAtMost(note.count)
            Contracts.setProgress(stack, progress)
            if (progress >= note.count) done(player, note)
            return true
        }
        return false
    }

    /** Marks [player]'s contract in [stack] done. */
    fun complete(player: Player, stack: ItemStack) {
        val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: return
        val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: return
        if (state.progress >= note.count) return
        Contracts.setProgress(stack, note.count)
        done(player, note)
    }

    private fun done(player: Player, note: QuestNote) {
        val owner = StoryCharacter.ownerOf(note.chain)
        if (owner != null) player.sendSystemMessage(Component.translatable("message.guildmark.faction_return", owner.title))
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.4f)
    }

    /** A purse was lifted off [victim]. */
    fun onPurse(player: Player, victim: Townsperson) {
        if (victim.role == TownRole.NOBLE && credit(player, "pickpocket")) return
        credit(player, "purses")
    }

    /** Something died: the Dusk Rider and the renegade count for whoever was hunting them close by. */
    fun onDeath(entity: LivingEntity, source: DamageSource) {
        val level = entity.level() as? ServerLevel ?: return
        val kind = when (entity) {
            is DuskRider -> "dusk_rider"
            is Renegade -> "renegade"
            else -> return
        }
        for (player in level.players()) {
            if (!player.isAlive || player.distanceToSqr(entity) > HUNT_RANGE * HUNT_RANGE) continue
            credit(player, kind)
        }
    }

    /** A spar can't kill: a blow that would bring the player too low ends the bout instead. */
    fun onIncomingDamage(event: LivingIncomingDamageEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val knight = event.source.entity as? SwornKnight ?: return
        if (knight.sparring != player.uuid) return
        Spar.onBlow(player, knight, event)
    }

    // ---- Handing it in ----

    /**
     * Hands over what the job in [contract] was for: a heist's prize goes with the contract. False if it's not there to
     * hand over, when the heist is to do again (lost, dropped or burnt, the prize is back where it was taken from).
     */
    fun handOver(player: Player, contract: ItemStack, note: QuestNote): Boolean {
        val heist = Heist.byKind(kind(note)) ?: return true
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.get(FactionRegistry.PRIZE.get()) == heist.prize) {
                stack.shrink(1)
                return true
            }
        }
        Contracts.setProgress(contract, 0)
        player.sendSystemMessage(Component.translatable("message.guildmark.heist_no_prize", heist.prizeName))
        return false
    }

    /** Whether [player] carries an unfinished faction job of one of [kinds]. */
    fun wants(player: Player, vararg kinds: String): Boolean = (0 until player.inventory.containerSize).any { slot ->
        val stack = player.inventory.getItem(slot)
        val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: return@any false
        val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: return@any false
        note.type == QuestType.TRIAL && kind(note) in kinds && state.progress < note.count && !state.isExpired(player.level().gameTime)
    }

    /** A faction job was handed in: anything it left standing goes. */
    fun finish(level: ServerLevel, player: Player, note: QuestNote) {
        runs[player.uuid]?.remove(key(note))?.stop(level)
    }

    // ---- Words ----

    fun describe(note: QuestNote): Component = when (val kind = kind(note)) {
        "ward_lights", "pickpocket", "purses", "kindle" -> Component.translatable("quest.guildmark.trial.$kind.desc", note.count)
        "spar", "hold", "dusk_rider", "renegade" -> Component.translatable("quest.guildmark.trial.$kind.desc")
        else -> Heist.byKind(kind)?.let { Component.translatable("quest.guildmark.trial.heist.desc", it.prizeName) }
            ?: Component.translatable("quest.guildmark.trial.$kind.desc")
    }

    fun details(note: QuestNote, state: ContractState): List<Component> {
        val lines = ArrayList<Component>()
        Heist.byKind(kind(note))?.let { lines.add(Component.translatable("quest.guildmark.trial.target", Component.translatable("message.guildmark.heist_place.${it.id}"))) }
        state.destination?.let { lines.add(Component.translatable("quest.guildmark.trial.at", it.x, it.z)) }
        return lines
    }

    /** Whether [pos] is inside a town or capital, where no rider would come. */
    fun inTown(level: ServerLevel, pos: BlockPos): Boolean = Towns.at(level, pos) != null

    private const val HUNT_RANGE = 64.0
}

/** The Dusk Rider job: at dusk, out under the open sky away from towns, the rider comes for the player (once a night). */
class RiderHunt(player: ServerPlayer) : TrialRun(player) {
    private var night = -1L
    private var rider: UUID? = null

    override fun tick(level: ServerLevel, stack: ItemStack, note: QuestNote, state: ContractState): Boolean {
        // In Peaceful the rider never comes (and the leader lets the job go: Factions.accept)
        if (player.tickCount % 20 != 0 || level.dimension() != Level.OVERWORLD || level.difficulty == Difficulty.PEACEFUL) return true
        val clock = level.overworldClockTime
        val time = Math.floorMod(clock, 24000L)
        val tonight = clock / 24000L
        if (time < DUSK || time > LATE || tonight == night) return true
        if (!player.isAlive || player.isSpectator || !level.canSeeSky(player.blockPosition()) || Trials.inTown(level, player.blockPosition())) return true
        rider?.let { id -> if (level.getEntity(id)?.isAlive == true) return true }
        val sent = NightRiders.send(level, player, near = false) ?: return true
        night = tonight
        rider = sent.uuid
        return true
    }

    private companion object {
        const val DUSK = 12000L
        const val LATE = 14000L
    }
}
