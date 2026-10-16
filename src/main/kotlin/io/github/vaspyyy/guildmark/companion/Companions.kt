package io.github.vaspyyy.guildmark.companion

import io.github.vaspyyy.guildmark.atlas.Atlas
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.block.WardfireBlock
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.dragon.Dragon
import io.github.vaspyyy.guildmark.dragon.DragonRoosts
import io.github.vaspyyy.guildmark.faction.Heists
import io.github.vaspyyy.guildmark.faction.Trials
import io.github.vaspyyy.guildmark.guild.GuildMember
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.magic.School
import io.github.vaspyyy.guildmark.magic.Spell
import io.github.vaspyyy.guildmark.magic.Spellbook
import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModParticles
import io.github.vaspyyy.guildmark.story.Characters
import io.github.vaspyyy.guildmark.story.StoryCharacter
import io.github.vaspyyy.guildmark.story.StoryNpc
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.Difficulty
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.EnumMap
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * The companions' rules: meeting them at a Wardfire, who travels with the player and who waits where, what they
 * think of what the player does (loyalty), their chapters, their perks once Sworn, and keeping them in the world
 * (they're never saved with it: the player's [CompanionState] is what lasts).
 */
object Companions {
    /** Ticks between looks at each player's companions. */
    private const val CHECK = 20
    /** A companion waiting somewhere comes out once the player is this near, and goes once they're further than [AWAY]. */
    private const val NEAR = 48.0
    private const val AWAY = 96.0
    /** What the player does this close to their companion, the companion sees. */
    private const val SEEN = 24.0
    /** Within this, a companion's perk helps the player. */
    private const val PERK_RANGE = 16.0
    /** A sitting companion gets up once the player is this far from the fire. */
    private const val LEAVE_FIRE = 7.0
    private const val TALK_RANGE = 8.0
    /** Loyalty for a chapter done, and for a liked gift. */
    private const val CHAPTER_LOYALTY = 10
    private const val GIFT_LOYALTY = 3
    /** A companion the player parted with, this far away, comes by one of their fires every few days. */
    private const val FAR = 128.0
    private const val COME_BY_DAYS = 3
    /** How long Hunter's Mark lasts. */
    private const val MARK_TICKS = 200
    /** How long a story by the fire stays up. */
    private const val STORY_TICKS = 240

    fun register(modBus: IEventBus) {
        CompanionRegistry.ITEMS.register(modBus)
        CompanionRegistry.ENTITIES.register(modBus)
        CompanionRegistry.SOUNDS.register(modBus)
        CompanionRegistry.ATTACHMENTS.register(modBus)
        modBus.addListener(EntityAttributeCreationEvent::class.java) { it.put(CompanionRegistry.COMPANION.get(), CompanionEntity.createAttributes().build()) }
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1").playToServer(CompanionOrderPayload.TYPE, CompanionOrderPayload.STREAM_CODEC, CompanionOrderPayload::handle)
        }
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) CompanionRegistry.TAB.forEach { event.accept(it.get()) }
        }
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            if (player.tickCount % CHECK == 13) tick(player)
            if (player.tickCount % CHECK == 3) CompanionBanter.tick(player)
        }
        // Ysolde's Last Light comes before anything else hears of the death
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, LivingDeathEvent::class.java) { lastLight(it) }
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java) { onDeath(it) }
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java) { onIncomingDamage(it) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { dismissAll(it.entity) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { (it.entity as? ServerPlayer)?.let(CompanionBanter::onRespawn) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            live.clear()
            marks.clear()
            refused.clear()
            CompanionBanter.clear()
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { CompanionCommand.register(it.dispatcher) }
    }

    fun state(player: Player): CompanionState = player.getData(CompanionRegistry.COMPANIONS)
    fun set(player: Player, state: CompanionState) = player.setData(CompanionRegistry.COMPANIONS, state)

    /** Game days (sleeping through a night doesn't skip one). */
    fun day(level: Level): Long = level.gameTime / 24000L

    // ---- In the world ----

    /** Each player's companions now in the world. */
    private val live = HashMap<UUID, EnumMap<CompanionKind, CompanionEntity>>()

    fun live(player: Player, kind: CompanionKind): CompanionEntity? = live[player.uuid]?.get(kind)?.takeIf { !it.isRemoved && it.isAlive }

    /** The companion travelling with [player], if they're in the world. */
    fun traveller(player: Player): CompanionEntity? = state(player).activeKind?.let { live(player, it) }

    /**
     * [player]'s companion of [kind] within [range] and on their feet, on either side (the client finds them among the
     * entities it knows, for the spell costs it shows).
     */
    fun near(player: Player, kind: CompanionKind, range: Double = PERK_RANGE): CompanionEntity? =
        player.level().getEntitiesOfClass(CompanionEntity::class.java, player.boundingBox.inflate(range)) {
            it.kind == kind && it.ownerId == player.uuid && !it.isDowned && it.isAlive
        }.firstOrNull()

    /** Whether [kind]'s perk helps [player] now: Sworn, travelling with them, near and on their feet. */
    fun perk(player: Player, kind: CompanionKind): Boolean {
        val state = state(player)
        return state.isSworn(kind) && state.isActive(kind) && near(player, kind) != null
    }

    private fun tick(player: ServerPlayer) {
        val level = player.level()
        val state = state(player)
        val guildLevel = Progression.get(player).level
        for (kind in CompanionKind.entries) {
            val c = live(player, kind)
            val following = state.isActive(kind) && state.following && kind.id !in state.leftOn
            if (following) {
                if (c == null || c.level() !== level) {
                    c?.discard()
                    spawn(level, player, kind, near = player.blockPosition())?.order(CompanionEntity.Mode.FOLLOW)
                    continue
                }
                c.scale(guildLevel)
                // By the fire while the player rests; up and after them once they walk away from it
                if (c.mode == CompanionEntity.Mode.CAMP) {
                    val fire = c.fire
                    if (fire == null || player.distanceToSqr(Vec3.atCenterOf(fire)) > LEAVE_FIRE * LEAVE_FIRE) c.order(CompanionEntity.Mode.FOLLOW)
                } else if (c.mode != CompanionEntity.Mode.FOLLOW && c.mode != CompanionEntity.Mode.ARRIVE) {
                    c.order(CompanionEntity.Mode.FOLLOW)
                }
                continue
            }
            if (c?.mode == CompanionEntity.Mode.LEAVE) continue
            // Waiting somewhere (or come back to a fire after leaving): there while the player is near enough to come across them
            val spot = state.spots[kind.id]
            val there = spot != null && spot.dimension() == level.dimension() && (state.hasJoined(kind) || state.hasMet(kind))
            if (!there) {
                c?.discard()
                continue
            }
            val pos = spot.pos()
            val d2 = player.distanceToSqr(Vec3.atCenterOf(pos))
            if (c == null) {
                if (d2 < NEAR * NEAR && level.isLoaded(pos)) {
                    val fire = fireBeside(level, pos)
                    spawn(level, player, kind, at = pos)?.order(if (fire != null) CompanionEntity.Mode.CAMP else CompanionEntity.Mode.WAIT, pos, fire)
                }
            } else if (d2 > AWAY * AWAY || c.level() !== level) {
                c.discard()
            } else {
                c.scale(guildLevel)
                if (c.mode == CompanionEntity.Mode.FOLLOW) c.order(CompanionEntity.Mode.WAIT, pos)
            }
        }
    }

    /** A new companion entity for [player], standing [at] a spot or somewhere [near] a place; null if there's no room. */
    private fun spawn(level: ServerLevel, player: ServerPlayer, kind: CompanionKind, at: BlockPos? = null, near: BlockPos? = null): CompanionEntity? {
        val c = CompanionRegistry.COMPANION.get().create(level, EntitySpawnReason.EVENT) ?: return null
        c.setup(kind, player, Progression.get(player).level)
        val pos = at ?: near ?: return null
        c.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360f, 0f)
        if (at == null && !c.teleportNear(pos)) c.snapTo(player.x, player.y, player.z, player.yRot, 0f)
        level.addFreshEntity(c)
        live.getOrPut(player.uuid) { EnumMap(CompanionKind::class.java) }[kind] = c
        return c
    }

    private fun dismiss(player: Player, kind: CompanionKind) {
        live[player.uuid]?.remove(kind)?.discard()
    }

    private fun dismissAll(player: Player) {
        live.remove(player.uuid)?.values?.forEach { it.discard() }
        CompanionBanter.forget(player)
    }

    /** The Wardfire [seat] is beside, if any. */
    private fun fireBeside(level: ServerLevel, seat: BlockPos): BlockPos? =
        BlockPos.betweenClosedStream(seat.offset(-3, -1, -3), seat.offset(3, 1, 3))
            .filter { level.getBlockState(it).block is WardfireBlock }.findFirst().map { it.immutable() }.orElse(null)

    /** A place to sit at the fire at [fire]: across it from [player], or round it until there's room. */
    private fun seatBy(level: ServerLevel, fire: BlockPos, player: Player, taken: Collection<BlockPos> = emptyList()): BlockPos {
        val away = Vec3.atCenterOf(fire).subtract(player.position()).multiply(1.0, 0.0, 1.0)
        val base = if (away.lengthSqr() < 0.01) 0.0 else Math.atan2(away.z, away.x)
        for (step in 0 until 12) {
            val angle = base + (if (step % 2 == 0) 1 else -1) * ((step + 1) / 2) * Math.PI / 6
            val at = fire.offset(Math.round(cos(angle) * 2.0).toInt(), 0, Math.round(sin(angle) * 2.0).toInt())
            for (dy in intArrayOf(0, 1, -1)) {
                val pos = at.above(dy)
                if (pos !in taken && pos != fire && standable(level, pos)) return pos
            }
        }
        return fire.offset(2, 0, 0)
    }

    /** Somewhere a person can stand at [pos]: firm ground, room to stand, no water. */
    private fun standable(level: ServerLevel, pos: BlockPos): Boolean =
        level.getBlockState(pos).getCollisionShape(level, pos).isEmpty && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty &&
            level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP) && level.getFluidState(pos).isEmpty

    // ---- At a Wardfire ----

    /** [player] rested at the Wardfire at [fire]: whoever travels with them sits by it too, and someone may come by. */
    fun onRest(level: ServerLevel, fire: BlockPos, player: ServerPlayer) {
        var state = state(player)
        val day = day(level)
        val seats = ArrayList<BlockPos>()
        traveller(player)?.takeIf { state.following && it.level() === level && it.distanceToSqr(player) < 32.0 * 32.0 }?.let { c ->
            val kind = c.kind
            c.restore()
            val seat = seatBy(level, fire, player)
            seats += seat
            c.order(CompanionEntity.Mode.CAMP, seat, fire)
            deed(player, Deed.REST)
            state = state(player)
            // Once a day a story by the fire, or the ask, when their next chapter has opened
            val step = Characters.chapters(player, kind.character)
            val open = !state.isSworn(kind) && step < kind.character.steps.size && state.loyalty(kind) >= CompanionKind.CHAPTERS[step] && !carries(player, kind)
            if (open && state.asked[kind.id] != step) {
                c.sayLater("bark.ask", 60)
                c.playSoundLater(CompanionRegistry.CHAPTER.get(), 60)
                state = state.copy(asked = state.asked + (kind.id to step))
            } else if (state.toldOn[kind.id] != day) {
                val loyalty = state.loyalty(kind)
                val unlocked = 1 + (if (loyalty >= CompanionKind.CHAPTERS[0]) 1 else 0) + (if (loyalty >= CompanionKind.CHAPTERS[1]) 1 else 0)
                val told = state.told[kind.id] ?: 0
                val story = if (told < unlocked) told else level.random.nextInt(unlocked)
                c.sayLater("bark.camp.$story", 60, STORY_TICKS)
                state = state.copy(toldOn = state.toldOn + (kind.id to day), told = state.told + (kind.id to told + 1))
            }
            set(player, state)
        }
        if (state.metOn != day) visit(level, fire, player, state, day, seats)
    }

    /** Someone comes to the fire: a new face the player has earned, or one of theirs who's been away. One a day. */
    private fun visit(level: ServerLevel, fire: BlockPos, player: ServerPlayer, state: CompanionState, day: Long, seats: List<BlockPos>) {
        val here = GlobalPos.of(level.dimension(), fire)
        fun far(kind: CompanionKind): Boolean = state.spots[kind.id]?.let { it.dimension() != here.dimension() || !it.pos().closerThan(fire, FAR) } ?: true
        // Nobody comes by again within a few days of parting from the player or of coming by last
        fun due(kind: CompanionKind): Boolean = state.partedOn[kind.id]?.let { day - it >= COME_BY_DAYS } ?: true
        val coming = CompanionKind.entries.firstOrNull { kind ->
            // Back after leaving in anger, the next day or later
            kind.id in state.leftOn && (state.leftOn[kind.id] ?: day) < day && (state.partedOn[kind.id]?.let { it < day } ?: true)
        } ?: CompanionKind.entries.firstOrNull { kind ->
            !state.hasMet(kind) && earned(level, player, kind)
        } ?: CompanionKind.entries.firstOrNull { kind ->
            // Met but never asked along, or parted with (or told to wait) and left far behind
            state.hasMet(kind) && kind.id !in state.leftOn && far(kind) && !(state.isActive(kind) && state.following) && due(kind)
        } ?: return
        dismiss(player, coming)
        val seat = seatBy(level, fire, player, seats)
        val from = approach(level, fire) ?: seat
        val c = spawn(level, player, coming, at = from) ?: return
        c.order(CompanionEntity.Mode.ARRIVE, seat, fire)
        if (from == seat) level.sendParticles(ModParticles.WARD_EMBER.get(), c.x, c.getY(0.5), c.z, 20, 0.3, 0.6, 0.3, 0.02)
        set(player, state.copy(met = state.met + coming.id, metOn = day, partedOn = state.partedOn + (coming.id to day))
            .withSpot(coming, GlobalPos.of(level.dimension(), seat)))
    }

    /** Whether [player] has done enough for [kind] to seek them out. */
    private fun earned(level: ServerLevel, player: ServerPlayer, kind: CompanionKind): Boolean {
        val guild = Progression.get(player).level
        if (guild >= kind.meetLevel + 4) return true
        return when (kind) {
            CompanionKind.BRAM -> guild >= kind.meetLevel
            CompanionKind.YSOLDE -> guild >= kind.meetLevel || Atlas.get(player).places.count { it.kind == AtlasPlace.WARDFIRE } >= 3
            CompanionKind.KESTREL -> guild >= kind.meetLevel && level.isDarkOutside
            CompanionKind.LUCAN -> guild >= kind.meetLevel ||
                Spellbook.get(player).known.mapNotNull { id -> Spell.entries.firstOrNull { it.id == id } }.count { it.school == School.SORCERY } >= 2
        }
    }

    /** Where someone walks in from: out of the dark a dozen blocks off, somewhere they can stand. */
    private fun approach(level: ServerLevel, fire: BlockPos): BlockPos? {
        val start = level.random.nextDouble() * Math.PI * 2
        for (step in 0 until 12) {
            val angle = start + step * Math.PI / 6
            for (distance in intArrayOf(13, 10, 7)) {
                val at = fire.offset(Math.round(cos(angle) * distance).toInt(), 0, Math.round(sin(angle) * distance).toInt())
                for (dy in 0..4) for (sign in intArrayOf(1, -1)) {
                    val pos = at.above(dy * sign)
                    if (standable(level, pos)) return pos
                }
            }
        }
        return null
    }

    /** [player] travelled to the Wardfire beside [spot]: whoever follows them comes too. */
    fun onTravel(player: ServerPlayer, spot: Vec3) {
        val c = traveller(player) ?: return
        if (!state(player).following) return
        if (c.mode == CompanionEntity.Mode.CAMP) c.order(CompanionEntity.Mode.FOLLOW)
        if (c.level() !== player.level()) return
        if (!c.teleportNear(BlockPos.containing(spot))) c.snapTo(spot.x, spot.y, spot.z, player.yRot, 0f)
    }

    // ---- Talking ----

    /** Flags for the conversation with a companion (OpenCharacterPayload.flags); their band is in [BAND_SHIFT] on. */
    const val STRANGER = 1
    const val FOLLOWING = 2
    const val WAITING = 4
    const val PARTED = 8
    const val GATED = 16
    const val OFFER = 32
    const val GIFT = 64
    const val GIFTED = 128
    const val COLD = 256
    const val LEFT = 512
    const val TURNED_IN = 1024
    /** Someone else travels with the player: asking this one along sends them off. */
    const val SWAP = 2048
    const val BAND_SHIFT = 12

    /** [player] used their companion [c]: hand in a finished chapter, or talk. */
    fun talk(level: ServerLevel, player: ServerPlayer, c: CompanionEntity) {
        val character = c.kind.character
        val held = player.mainHandItem
        val note = held.get(ModDataComponents.QUEST_NOTE.get())
        val contract = held.get(ModDataComponents.CONTRACT_STATE.get())
        if (note != null && contract != null && StoryCharacter.parseChain(note.chain)?.first == character &&
            (contract.progress >= note.count || contract.isExpired(level.gameTime))) {
            val before = Characters.chapters(player, character)
            Contracts.turnIn(held, player, level, c.blockPosition(), character)
            if (Characters.chapters(player, character) > before) return open(player, c, turnedIn = true)
        }
        open(player, c)
    }

    private fun open(player: ServerPlayer, c: CompanionEntity, turnedIn: Boolean = false) {
        val kind = c.kind
        val state = state(player)
        val day = day(player.level())
        val character = kind.character
        val chapters = Characters.chapters(player, character)
        val carrying = carries(player, kind)
        var flags = 0
        val left = kind.id in state.leftOn
        when {
            left -> flags = flags or if ((state.leftOn[kind.id] ?: day) >= day) COLD else LEFT
            !state.hasJoined(kind) -> flags = flags or STRANGER
            state.isActive(kind) -> flags = flags or if (state.following) FOLLOWING else WAITING
            else -> flags = flags or PARTED
        }
        if (state.active != null && !state.isActive(kind)) flags = flags or SWAP
        if (state.hasJoined(kind) && !left && chapters < character.steps.size && !carrying && !state.isSworn(kind)) {
            flags = flags or if (state.loyalty(kind) >= CompanionKind.CHAPTERS[chapters]) OFFER else GATED
        }
        if (state.hasJoined(kind) && !left && kind.likes(player.mainHandItem)) flags = flags or GIFT
        if (state.giftedOn[kind.id] == day) flags = flags or GIFTED
        if (turnedIn) flags = flags or TURNED_IN
        flags = flags or (CompanionKind.band(state.loyalty(kind), state.isSworn(kind)) shl BAND_SHIFT)
        c.listen(true)
        PacketDistributor.sendToPlayer(player, OpenCharacterPayload(c.id, character.id, chapters, carrying, turnedIn && chapters >= character.steps.size, flags))
    }

    /** The conversation with [c] is over. */
    fun endTalk(c: CompanionEntity) {
        if (c.tickCount - c.talkOpenedAt > 2) c.listen(false)
    }

    /** Whether [player] carries one of [kind]'s chapters. */
    fun carries(player: Player, kind: CompanionKind): Boolean = (0 until player.inventory.containerSize).any { slot ->
        val chain = player.inventory.getItem(slot).get(ModDataComponents.QUEST_NOTE.get())?.chain ?: return@any false
        StoryCharacter.parseChain(chain)?.first == kind.character
    }

    /** [player]'s companion [entityId], near enough to deal with. */
    private fun mine(player: Player, entityId: Int): CompanionEntity? {
        val c = player.level().getEntity(entityId) as? CompanionEntity ?: return null
        return c.takeIf { it.ownerId == player.uuid && it.distanceTo(player) <= TALK_RANGE && it.isAlive }
    }

    /** An answer in the talk that changes something (see CompanionOrderPayload). */
    fun order(player: ServerPlayer, entityId: Int, order: Int) {
        val c = mine(player, entityId) ?: return
        val kind = c.kind
        val level = player.level()
        var state = state(player)
        val day = day(level)
        val here = GlobalPos.of(level.dimension(), c.blockPosition())
        when (order) {
            CompanionOrderPayload.JOIN, CompanionOrderPayload.REJOIN, CompanionOrderPayload.RETURN -> {
                if (order == CompanionOrderPayload.RETURN) {
                    val leftOn = state.leftOn[kind.id] ?: return
                    if (leftOn >= day) return
                    state = state.copy(leftOn = state.leftOn - kind.id).withLoyalty(kind, CompanionKind.FIRST)
                } else if (kind.id in state.leftOn) {
                    return
                }
                set(player, state)
                takeAlong(player, kind, here)
                c.listen(false)
                c.order(CompanionEntity.Mode.FOLLOW)
            }
            CompanionOrderPayload.FOLLOW -> {
                if (!state.isActive(kind)) return
                set(player, state.copy(following = true, partedOn = state.partedOn - kind.id).withSpot(kind, null))
                c.listen(false)
                c.order(CompanionEntity.Mode.FOLLOW)
            }
            CompanionOrderPayload.WAIT -> {
                if (!state.isActive(kind)) return
                // Left waiting a day and far behind, they come by one of the player's fires (see visit)
                set(player, state.copy(following = false, partedOn = state.partedOn + (kind.id to day)).withSpot(kind, here))
                c.listen(false)
                c.order(CompanionEntity.Mode.WAIT, c.blockPosition())
            }
            CompanionOrderPayload.PART -> {
                if (!state.isActive(kind)) return
                set(player, state.copy(active = null, following = true, partedOn = state.partedOn + (kind.id to day)).withSpot(kind, here))
                c.listen(false)
                val fire = fireBeside(level, c.blockPosition())
                if (fire != null && c.mode == CompanionEntity.Mode.CAMP) c.order(CompanionEntity.Mode.CAMP, c.spot ?: c.blockPosition(), fire)
                else c.order(CompanionEntity.Mode.WAIT, c.blockPosition())
            }
            CompanionOrderPayload.GIFT -> {
                val stack = player.mainHandItem
                if (!state.hasJoined(kind) || !kind.likes(stack) || state.giftedOn[kind.id] == day) return
                stack.shrink(1)
                set(player, state.copy(giftedOn = state.giftedOn + (kind.id to day)).withLoyalty(kind, state.loyalty(kind) + GIFT_LOYALTY))
                c.gesture(CompanionEntity.Gesture.APPROVE)
                level.playSound(null, c.x, c.y, c.z, CompanionRegistry.APPROVE.get(), SoundSource.NEUTRAL, 0.8f, 1f)
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.getY(0.9), c.z, 6, 0.3, 0.3, 0.3, 0.0)
                checkChapter(player, kind, c)
            }
        }
    }

    /** [kind] travels with [player] from now on; whoever did before waits where they stand (or at [here]). */
    fun takeAlong(player: ServerPlayer, kind: CompanionKind, here: GlobalPos) {
        val level = player.level()
        val day = day(level)
        var state = state(player)
        state.activeKind?.takeIf { it != kind }?.let { other ->
            state = state.copy(partedOn = state.partedOn + (other.id to day))
            live(player, other)?.let { o ->
                state = state.withSpot(other, GlobalPos.of(level.dimension(), o.blockPosition()))
                o.order(CompanionEntity.Mode.WAIT, o.blockPosition())
                o.say("bark.swap", force = true)
            } ?: run { if (state.spots[other.id] == null) state = state.withSpot(other, here) }
        }
        if (!state.hasJoined(kind)) player.sendSystemMessage(Component.translatable("message.guildmark.companion_joined", kind.title))
        set(player, state.copy(recruited = state.recruited + kind.id, met = state.met + kind.id, active = kind.id, following = true,
            partedOn = state.partedOn - kind.id, leftOn = state.leftOn - kind.id).withSpot(kind, null))
    }

    // ---- Chapters ----

    /** Each player's chapter that couldn't be set up at their last try, by its chain tag. */
    private val refused = HashMap<UUID, String>()

    /** The player said yes to [c]'s next chapter. */
    fun accept(player: Player, c: CompanionEntity) {
        val level = player.level() as? ServerLevel ?: return
        if (c.ownerId != player.uuid || c.distanceTo(player) > TALK_RANGE) return
        c.listen(false)
        val kind = c.kind
        val character = kind.character
        val state = state(player)
        val step = Characters.chapters(player, character)
        if (!state.hasJoined(kind) || step >= character.steps.size || carries(player, kind) || state.loyalty(kind) < CompanionKind.CHAPTERS[step]) return
        val taken = character.note(step)
        // Nothing hostile walks a Peaceful world, and without a roost there's no dragon to hunt: the chapter is let go
        if (level.difficulty == Difficulty.PEACEFUL && fights(taken)) return letGo(level, player, c, step, "message.guildmark.chapter_peaceful")
        if (taken.type == QuestType.HUNT && taken.target.path == "dragon" && DragonRoosts.get(level).nearest(level, player.blockPosition()) == null) {
            return letGo(level, player, c, step, "message.guildmark.companion_no_dragon")
        }
        // Their chapters are theirs to give: never above what the player could take on
        val note = taken.copy(rank = minOf(taken.rank, Progression.get(player).adventurerRank + 1))
        val chain = "${character.id}:$step"
        val stack = Contracts.take(note, level, c.blockPosition(), Direction.NORTH, player, fromHall = true)
        if (stack == null) {
            if (refused.put(player.uuid, chain) == chain) {
                refused.remove(player.uuid)
                letGo(level, player, c, step, "message.guildmark.chapter_let_go")
            }
            return
        }
        refused.remove(player.uuid)
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        player.sendSystemMessage(Component.translatable("message.guildmark.story_accepted", character.title, step + 1, character.steps.size))
        level.playSound(null, c.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0f, 1.0f)
    }

    private fun fights(note: QuestNote): Boolean =
        note.type == QuestType.HUNT || note.type == QuestType.LAIR || note.type == QuestType.TRIAL && Trials.kind(note) == "dusk_rider"

    /** Chapter [step] can't be done here: it counts as done (not its pay), and the talk goes on to the next. */
    private fun letGo(level: ServerLevel, player: Player, c: CompanionEntity, step: Int, why: String) {
        player.sendSystemMessage(Component.translatable(why, c.kind.title))
        onChapterDone(level, player, c.kind, step)
        if (player is ServerPlayer) open(player, c, turnedIn = true)
    }

    /** Chapter [step] of [kind]'s questline was handed in: closer for it, and Sworn at the end, with their keepsake. */
    fun onChapterDone(level: ServerLevel, player: Player, kind: CompanionKind, step: Int) {
        val character = kind.character
        val chapters = maxOf(Characters.chapters(player, character), step + 1).coerceAtMost(character.steps.size)
        player.setData(ModAttachments.STORY, player.getData(ModAttachments.STORY) + (character.id to chapters))
        var state = state(player)
        state = state.withLoyalty(kind, state.loyalty(kind) + CHAPTER_LOYALTY).copy(asked = state.asked - kind.id)
        if (chapters < character.steps.size) {
            set(player, state)
            player.sendSystemMessage(Component.translatable("message.guildmark.story_chapter", character.title, chapters, character.steps.size))
            level.playSound(null, player.blockPosition(), CompanionRegistry.APPROVE.get(), SoundSource.PLAYERS, 1f, 1f)
            return
        }
        set(player, state.copy(sworn = state.sworn + kind.id))
        Milestones.award(player, Milestones.CHARACTER)
        player.inventory.placeItemBackInInventory(keepsake(level, kind), Prediction.SERVER_ONLY)
        player.sendSystemMessage(Component.translatable("message.guildmark.companion_sworn", kind.title, Component.translatable("companion.guildmark.${kind.id}.perk")))
        level.playSound(null, player.blockPosition(), CompanionRegistry.SWORN.get(), SoundSource.PLAYERS, 1f, 1f)
        GuildNews.add(level, "news.guildmark.companion_sworn", player.name.string, character.displayName)
    }

    /** What each gives when Sworn: their keepsake, with its story on it. */
    private fun keepsake(level: ServerLevel, kind: CompanionKind): ItemStack {
        val enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
        val stack = when (kind) {
            CompanionKind.BRAM -> ItemStack(CompanionRegistry.GREYHART.get()).also {
                it.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 4)
                it.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 3)
            }
            CompanionKind.YSOLDE -> ItemStack(CompanionRegistry.LAMPLIGHTERS_CENSER.get())
            CompanionKind.KESTREL -> ItemStack(CompanionRegistry.THORNWOOD_BOW.get()).also {
                it.enchant(enchantments.getOrThrow(Enchantments.POWER), 4)
                it.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 3)
            }
            CompanionKind.LUCAN -> ItemStack(CompanionRegistry.UNPICKED_STAR.get())
        }
        val id = stack.typeHolder().unwrapKey().map { it.identifier().path }.orElse("")
        stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.$id.lore"))))
        return stack
    }

    // ---- What they think of what the player does ----

    /**
     * [player] did [deed]. Whoever travels with them and saw it (or, for news, every companion who's joined them)
     * thinks the more or the less of them for it, a few times a day at most, and says so.
     */
    fun deed(player: Player, deed: Deed) {
        val level = player.level() as? ServerLevel ?: return
        var state = state(player)
        val day = day(level)
        var changed = false
        for (kind in CompanionKind.entries) {
            if (!state.hasJoined(kind) || kind.id in state.leftOn) continue
            val c = live(player, kind)
            val seen = state.isActive(kind) && c != null && c.level() === level && c.distanceToSqr(player) < SEEN * SEEN && !c.isDowned
            if (!deed.news && !seen) continue
            val change = kind.reaction(deed)
            if (change == 0 || state.deedsToday(day, kind, deed) >= deed.daily) continue
            state = state.withLoyalty(kind, state.loyalty(kind) + change).countDeed(day, kind, deed)
            changed = true
            if (c != null && seen) react(level, c, deed, change)
        }
        if (!changed) return
        set(player, state)
        for (kind in CompanionKind.entries) {
            if (!state.hasJoined(kind) || kind.id in state.leftOn) continue
            if (!state.isSworn(kind) && state.loyalty(kind) <= CompanionKind.LEAVE_AT) leave(level, player, kind)
            else checkChapter(player, kind, live(player, kind))
        }
    }

    /** [c] approves or disapproves out loud, with a gesture. */
    private fun react(level: ServerLevel, c: CompanionEntity, deed: Deed, change: Int) {
        val key = when {
            deed == Deed.REST -> return
            deed.news -> "bark.${deed.group}"
            change > 0 -> "bark.approve.${deed.group}"
            else -> "bark.disapprove.${deed.group}"
        }
        c.say(key, force = true)
        c.gesture(if (change > 0) CompanionEntity.Gesture.APPROVE else CompanionEntity.Gesture.DISAPPROVE)
        level.playSound(null, c.x, c.y, c.z, if (change > 0) CompanionRegistry.APPROVE.get() else CompanionRegistry.DISAPPROVE.get(), SoundSource.NEUTRAL, 0.7f, 1f)
    }

    /** [kind]'s next chapter has just opened: they'll ask at the next fire (and mention it now, if they're beside the player). */
    private fun checkChapter(player: Player, kind: CompanionKind, c: CompanionEntity?) {
        val state = state(player)
        val step = Characters.chapters(player, kind.character)
        if (state.isSworn(kind) || step >= kind.character.steps.size || state.loyalty(kind) < CompanionKind.CHAPTERS[step]) return
        if (state.asked[kind.id] == step || carries(player, kind)) return
        if (c != null && state.isActive(kind) && c.sayLater("bark.ask", 50)) {
            c.playSoundLater(CompanionRegistry.CHAPTER.get(), 50)
            set(player, state.copy(asked = state.asked + (kind.id to step)))
            player.sendSystemMessage(Component.translatable("message.guildmark.companion_asks", kind.title))
        }
    }

    /** Loyalty gone: [kind] walks off. A day later they'll hear the player out at one of their fires. */
    private fun leave(level: ServerLevel, player: Player, kind: CompanionKind) {
        val state = state(player)
        set(player, state.copy(
            active = if (state.isActive(kind)) null else state.active,
            leftOn = state.leftOn + (kind.id to day(level)),
        ).withSpot(kind, null))
        live(player, kind)?.let { c ->
            c.say("leave", 120, force = true)
            c.gesture(CompanionEntity.Gesture.DISAPPROVE)
            c.order(CompanionEntity.Mode.LEAVE)
        }
        player.sendSystemMessage(Component.translatable("message.guildmark.companion_left", kind.title))
    }

    /** A new land: Kestrel and Lucan are glad of it, and whoever's along remarks on it. */
    fun onNewLand(player: ServerPlayer) {
        val c = traveller(player)
        val before = c?.lastSpoke
        deed(player, Deed.NEW_LAND)
        if (c != null && c.lastSpoke == before) CompanionBanter.remark(c, "bark.region")
    }

    fun onCapital(player: ServerPlayer) {
        traveller(player)?.let { CompanionBanter.remark(it, "bark.capital", cooldown = 12000) }
    }

    /** A spell was cast: Ysolde likes a prayer said, Lucan a sorcery. */
    fun onSpell(player: Player, school: School) = deed(player, if (school == School.SORCERY) Deed.SORCERY else Deed.PRAYER)

    /** Whether [c] should fight [entity] (see CompanionFighting.isFoe). */
    fun isFoe(c: CompanionEntity, entity: LivingEntity): Boolean = CompanionFighting.isFoe(c, entity)

    /** In the middle of a heist they keep their heads down: no fighting the household. */
    fun holdingBack(player: Player): Boolean = Trials.running(player, Heists.Run::class.java)?.underWay == true

    // ---- Deaths ----

    private fun onDeath(event: LivingDeathEvent) {
        val victim = event.entity
        val level = victim.level() as? ServerLevel ?: return
        if (victim is CompanionEntity) {
            live[victim.ownerId]?.let { if (it[victim.kind] === victim) it.remove(victim.kind) }
            return
        }
        val source = event.source
        val byPlayer = source.entity as? Player ?: victim.killCredit as? Player
        val player = byPlayer ?: (source.entity as? CompanionEntity)?.player ?: return
        if (player.level() !== level) return
        // Townsfolk struck down by the player's own hand
        if (byPlayer != null && (victim is Townsperson || victim is AbstractVillager || victim is StoryNpc) && victim !is GuildMember) deed(player, Deed.MURDER)
        val boss = Talents.isBoss(victim) || victim is CombatEnemy && victim.isBoss
        when {
            victim is Dragon -> deed(player, Deed.DRAGON)
            victim.hasData(ModAttachments.LAIR_BOSS) -> deed(player, Deed.LAIR)
            boss -> deed(player, Deed.BOSS)
        }
        CompanionBanter.onKill(player, victim, boss)
    }

    // ---- Perks ----

    /** Ysolde's Last Light: once a day, a blow that would kill the player leaves them standing, mended by her prayer. */
    private fun lastLight(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (event.source.`is`(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return
        val state = state(player)
        val day = day(player.level())
        if (state.lightOn == day || !perk(player, CompanionKind.YSOLDE)) return
        event.isCanceled = true
        set(player, state.copy(lightOn = day))
        player.health = player.maxHealth * 0.4f
        player.addEffect(MobEffectInstance(MobEffects.REGENERATION, 100, 1))
        val level = player.level()
        level.sendParticles(ModParticles.WARD_EMBER.get(), player.x, player.getY(0.5), player.z, 40, 0.4, 0.8, 0.4, 0.04)
        level.sendParticles(ParticleTypes.END_ROD, player.x, player.getY(0.6), player.z, 16, 0.3, 0.6, 0.3, 0.05)
        level.playSound(null, player.x, player.y, player.z, io.github.vaspyyy.guildmark.magic.MagicRegistry.HEARTHLIGHT.get(), SoundSource.PLAYERS, 1.2f, 0.9f)
        player.sendOverlayMessage(Component.translatable("message.guildmark.last_light", CompanionKind.YSOLDE.title))
    }

    /** Foes Kestrel's arrows have marked, and whose player's blows they take harder: until (game time). */
    private val marks = WeakHashMap<LivingEntity, Pair<UUID, Long>>()

    /** One of Kestrel's arrows struck [target]: once she's Sworn, it's marked for the player. */
    fun onKestrelHit(kestrel: CompanionEntity, target: LivingEntity) {
        val owner = kestrel.ownerId ?: return
        val player = kestrel.player ?: return
        if (kestrel.kind != CompanionKind.KESTREL || !state(player).isSworn(CompanionKind.KESTREL)) return
        target.addEffect(MobEffectInstance(MobEffects.GLOWING, MARK_TICKS, 0, false, false), kestrel)
        marks[target] = owner to target.level().gameTime + MARK_TICKS
    }

    private fun onIncomingDamage(event: LivingIncomingDamageEvent) {
        val victim = event.entity
        if (victim.level().isClientSide) return
        // Bram's Shield-Brother: he takes some of what's meant for the player
        if (victim is ServerPlayer && perk(victim, CompanionKind.BRAM)) event.amount *= 0.85f
        // Kestrel's Hunter's Mark
        val by = event.source.entity as? Player ?: return
        val mark = marks[victim] ?: return
        if (mark.first == by.uuid && mark.second > victim.level().gameTime) event.amount *= 1.2f
    }

    /** Lucan's Shared Focus: the player's spells cost a quarter less while he's near. */
    fun focusFactor(player: Player): Float = if (perk(player, CompanionKind.LUCAN)) 0.75f else 1f

    // ---- For testing (see CompanionCommand) ----

    /** [kind] walks up to [player] as if to their fire, wherever they are. */
    fun meetNow(player: ServerPlayer, kind: CompanionKind) {
        val level = player.level()
        val state = state(player)
        dismiss(player, kind)
        val c = spawn(level, player, kind, near = player.blockPosition()) ?: return
        val seat = c.blockPosition()
        c.order(CompanionEntity.Mode.ARRIVE, seat, fireBeside(level, seat))
        set(player, state.copy(met = state.met + kind.id, leftOn = state.leftOn - kind.id).withSpot(kind, GlobalPos.of(level.dimension(), seat)))
    }

    /** Forgets everything about [player]'s companions. */
    fun reset(player: ServerPlayer) {
        dismissAll(player)
        set(player, CompanionState())
        val story = player.getData(ModAttachments.STORY)
        player.setData(ModAttachments.STORY, story - CompanionKind.entries.map { it.character.id }.toSet())
    }

    fun refresh(player: ServerPlayer, kind: CompanionKind) = dismiss(player, kind)
}
