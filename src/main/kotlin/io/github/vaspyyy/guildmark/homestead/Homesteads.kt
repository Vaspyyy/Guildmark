package io.github.vaspyyy.guildmark.homestead

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.homestead.plan.BuildOrder
import io.github.vaspyyy.guildmark.homestead.plan.PieceKind
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.TierPlan
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.village.live.keptField
import io.github.vaspyyy.guildmark.village.live.keptList
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.bus.api.Event
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent
import net.neoforged.neoforge.event.level.BlockDropsEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import java.util.function.Supplier

/**
 * Every homestead, kept with the overworld (homesteads are only chartered there). One to a player.
 *
 * This is also where the rest of the game finds homesteads (see [at], [of], [all], [places]) and hears of them
 * ([HomesteadEvent]).
 */
class Homesteads(
    val homesteads: MutableList<Homestead> = mutableListOf(),
    var nextId: Int = 1,
    /** Posts and crates of charters given up while their chunk was away, to be taken down when it's back. */
    val stale: MutableList<BlockPos> = mutableListOf(),
) : SavedData() {
    fun owned(owner: UUID): Homestead? = homesteads.firstOrNull { it.owner == owner }
    fun byId(id: Int): Homestead? = homesteads.firstOrNull { it.id == id }

    /** The homestead whose charter takes in the world column [x], [z]. */
    fun covering(x: Int, z: Int): Homestead? = homesteads.firstOrNull { it.covers(x, z) }

    /** A homestead whose land comes within [margin] of the column [x], [z]. */
    fun near(x: Int, z: Int, margin: Int): Homestead? = homesteads.firstOrNull { it.near(x, z, margin) }

    fun add(homestead: Homestead) {
        homesteads.add(homestead)
        if (homestead.id >= nextId) nextId = homestead.id + 1
        setDirty()
    }

    fun remove(homestead: Homestead) {
        if (homesteads.remove(homestead)) setDirty()
        plans.remove(homestead.id)
        places.remove(homestead.id)
    }

    fun changed() = setDirty()

    /** Each homestead's tier plans, worked out when first wanted (they come out the same every time). */
    private val plans = HashMap<Int, HashMap<Int, TierPlan>>()

    fun plan(homestead: Homestead, tier: Int): TierPlan =
        plans.getOrPut(homestead.id) { HashMap() }.getOrPut(tier) { BuildOrder.plan(homestead.layout, tier) }

    /** Each homestead's places as of its tier (see [Companion.places]). */
    private val places = HashMap<Int, Pair<Int, HomesteadPlaces>>()

    fun placesChanged(homestead: Homestead) {
        places.remove(homestead.id)
    }

    internal fun cachedPlaces(homestead: Homestead, make: () -> HomesteadPlaces): HomesteadPlaces {
        val have = places[homestead.id]
        if (have != null && have.first == homestead.tier) return have.second
        return make().also { places[homestead.id] = homestead.tier to it }
    }

    companion object {
        private val CODEC: Codec<Homesteads> = RecordCodecBuilder.create { i ->
            i.group(
                keptList(Homestead.CODEC).keptField("homesteads") { listOf() }.forGetter { it.homesteads },
                Codec.INT.optionalFieldOf("next_id", 1).forGetter { it.nextId },
                BlockPos.CODEC.listOf().optionalFieldOf("stale", listOf()).forGetter { it.stale },
            ).apply(i) { list, next, stale -> Homesteads(list.toMutableList(), next, stale.toMutableList()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "homesteads"), Supplier { Homesteads() }, CODEC)

        fun get(level: ServerLevel): Homesteads = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Whether homesteads are on (GuildmarkConfig homesteads). Charters already granted stay either way. */
        fun enabled(): Boolean = try {
            GuildmarkConfig.HOMESTEADS.get()
        } catch (e: IllegalStateException) {
            true
        }

        /** Everything homesteads hook into: their registries, payloads, the server tick, the land's protection, shipping, the command. */
        fun register(modBus: IEventBus) {
            HomesteadRegistry.BLOCKS.register(modBus)
            HomesteadRegistry.ITEMS.register(modBus)
            HomesteadRegistry.BLOCK_ENTITIES.register(modBus)
            HomesteadRegistry.MENUS.register(modBus)
            HomesteadRegistry.SOUNDS.register(modBus)
            modBus.addListener(RegisterPayloadHandlersEvent::class.java, HomesteadNet::register)
            modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
                if (event.tabKey != ModCreativeTabs.MAIN.key) return@addListener
                event.accept(HomesteadRegistry.CHARTER.get())
                event.accept(HomesteadRegistry.SHIPPING_CRATE_ITEM.get())
            }
            val bus = NeoForge.EVENT_BUS
            bus.addListener(ServerTickEvent.Post::class.java) { HomesteadWorks.tick(it.server) }
            bus.addListener(ServerStoppedEvent::class.java) { HomesteadWorks.reset() }
            bus.addListener(RegisterCommandsEvent::class.java) { HomesteadCommand.register(it.dispatcher) }
            bus.addListener(EntityJoinLevelEvent::class.java, BuilderCrew::onJoin)
            bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { (it.entity as? ServerPlayer)?.let(HomesteadNet::sync) }
            bus.addListener(PlayerInteractEvent.EntityInteract::class.java, CharterItem::onTrade)
            bus.addListener(BreakBlockEvent::class.java, HomesteadProtection::onBreak)
            bus.addListener(BlockEvent.EntityPlaceEvent::class.java, HomesteadProtection::onPlace)
            bus.addListener(PlayerInteractEvent.RightClickBlock::class.java, HomesteadProtection::onUseBlock)
            bus.addListener(PlayerInteractEvent.RightClickItem::class.java, HomesteadProtection::onUseItem)
            bus.addListener(PlayerInteractEvent.EntityInteract::class.java, HomesteadProtection::onUseEntity)
            bus.addListener(AttackEntityEvent::class.java, HomesteadProtection::onAttack)
            bus.addListener(ExplosionEvent.Detonate::class.java, HomesteadProtection::onExplode)
            bus.addListener(EntityMobGriefingEvent::class.java, HomesteadProtection::onGrief)
            bus.addListener(BlockEvent.FarmlandTrampleEvent::class.java, HomesteadProtection::onTrample)
            bus.addListener(BlockDropsEvent::class.java, HomesteadProtection::onDrops)
            bus.addListener(PlayerSetSpawnEvent::class.java, HomesteadProtection::onSpawnSet)
            HomesteadShipping.register()
        }

        // ---- For the rest of the game ----

        /** The homestead whose land [pos] is on, if any (only ever in the overworld). */
        @JvmStatic
        fun at(level: ServerLevel, pos: BlockPos): Homestead? {
            if (level.dimension() != Level.OVERWORLD) return null
            return get(level).covering(pos.x, pos.z)
        }

        /** [owner]'s homestead, if they hold a charter. */
        @JvmStatic
        fun of(level: ServerLevel, owner: UUID): Homestead? = get(level).owned(owner)

        /** Every homestead there is. */
        @JvmStatic
        fun all(level: ServerLevel): List<Homestead> = get(level).homesteads.toList()

        /**
         * The homestead whose land, with [margin] blocks round it, takes in [x], [z], if any: for whatever would make
         * camp, dig a lair or spawn a boss there, to keep clear of people's homes.
         */
        @JvmStatic
        fun near(level: ServerLevel, x: Int, z: Int, margin: Int): Homestead? {
            if (level.dimension() != Level.OVERWORLD) return null
            return get(level).near(x, z, margin)
        }

        /** Whether [player] is one of [homestead]'s people: its owner or one of the owner's party. */
        @JvmStatic
        fun belongsTo(level: ServerLevel, homestead: Homestead, player: Player): Boolean {
            if (player.uuid == homestead.owner) return true
            val party = Parties.get(level.server).of(homestead.owner) ?: return false
            return player.uuid in party.members
        }

        /**
         * Where things are on [homestead] as it stands, in the world: its fields and pens (the ground inside their
         * fences), its shipping crates, hearths, beds and storage (chests and barrels) in the buildings finished so far,
         * and its post and supply crate.
         */
        @JvmStatic
        fun places(level: ServerLevel, homestead: Homestead): HomesteadPlaces = get(level).cachedPlaces(homestead) { survey(level, homestead) }

        private fun survey(level: ServerLevel, homestead: Homestead): HomesteadPlaces {
            val frame = homestead.frame
            val tier = homestead.tier
            val layout = homestead.layout.places(tier)
            fun box(r: io.github.vaspyyy.guildmark.city.plan.Rect): BoundingBox {
                val y = homestead.land.lie(r)
                return frame.box(r, y - 2, y + 3)
            }
            val hearths = ArrayList<BlockPos>()
            val beds = ArrayList<BlockPos>()
            val storage = ArrayList<BlockPos>()
            val shipping = ArrayList<BlockPos>()
            val data = get(level)
            for (t in 1..tier) {
                val plan = data.plan(homestead, t)
                for (step in plan.steps) {
                    if (step.kind == StepKind.SHIPPING) shipping.add(frame.world(step.x, step.y, step.z))
                    if (step.kind != StepKind.BLOCK || step.piece < 0) continue
                    if (plan.pieces[step.piece].kind != PieceKind.HOUSE) continue
                    val at = frame.world(step.x, step.y, step.z)
                    when (step.b.m) {
                        M.CAMPFIRE -> if (step.y < plan.pieces[step.piece].roofBase) hearths.add(at)
                        M.FURNACE, M.SMOKER -> hearths.add(at)
                        M.BED -> if (step.b.part == 1) beds.add(at)
                        M.CHEST, M.BARREL -> storage.add(at)
                        else -> {}
                    }
                }
            }
            return HomesteadPlaces(
                layout.fields.map(::box), layout.pens.map(::box), shipping, hearths, beds, storage, homestead.post, homestead.crate,
            )
        }
    }
}

/**
 * Where things are on a homestead (see [Homesteads.places]): boxes round its [fields] and [pens] (their ground, a little
 * below and above it), and its [shipping] crates, [hearths], [beds] (their heads) and [storage].
 */
class HomesteadPlaces(
    val fields: List<BoundingBox>,
    val pens: List<BoundingBox>,
    val shipping: List<BlockPos>,
    val hearths: List<BlockPos>,
    val beds: List<BlockPos>,
    val storage: List<BlockPos>,
    val post: BlockPos,
    val crate: BlockPos,
)

/**
 * What happens to homesteads, posted on NeoForge's event bus for whoever wants to know: a charter used ([Claimed]), a
 * tier finished or set ([TierChanged]), and a charter given up or taken away ([Removed]).
 */
abstract class HomesteadEvent(val level: ServerLevel, val homestead: Homestead) : Event() {
    class Claimed(level: ServerLevel, homestead: Homestead) : HomesteadEvent(level, homestead)
    class TierChanged(level: ServerLevel, homestead: Homestead, val from: Int, val to: Int) : HomesteadEvent(level, homestead)
    class Removed(level: ServerLevel, homestead: Homestead) : HomesteadEvent(level, homestead)

    companion object {
        fun post(event: HomesteadEvent) {
            NeoForge.EVENT_BUS.post(event)
        }
    }
}
