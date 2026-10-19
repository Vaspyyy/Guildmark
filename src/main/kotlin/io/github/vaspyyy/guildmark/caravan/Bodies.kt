package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * The caravans' bodies (design-road.md section 7, Part B): the wagons Part A keeps in its records ([Caravans]) made
 * real on the road while a player is near, and gone again when nobody is. Every 20 ticks it looks round each player
 * for caravans whose bodies should be there (two a player and four in the world at most, never closer than 48 blocks
 * to anyone, the record set back to match if they start further back), and lets go of those nobody's been near for
 * ten seconds; each tick, after everyone has moved, it runs each one ([Show]). The wrecks and loot piles
 * ([WreckShows]) and the waylaid drivers at the gates ([GateShow]) come and go the same way.
 *
 * It has its own registers, as cart/Handcarts does: the wagon, the horse, the wreck, its lots and the loot pile, and
 * the `caravan.*` sounds.
 */
internal object Bodies {
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)

    private fun key(name: String): ResourceKey<EntityType<*>> = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))

    /** A caravan's wagon: never saved, never summoned, sent every tick (the crew ride it). */
    val WAGON: DeferredHolder<EntityType<*>, EntityType<Wagon>> = ENTITIES.register("wagon") { ->
        EntityType.Builder.of(::Wagon, MobCategory.MISC).sized(1.8f, 2.6f).noSave().noSummon().fireImmune()
            .clientTrackingRange(10).updateInterval(1).build(key("wagon"))
    }

    val DRAUGHT_HORSE: DeferredHolder<EntityType<*>, EntityType<DraughtHorse>> = ENTITIES.register("draught_horse") { ->
        EntityType.Builder.of(::DraughtHorse, MobCategory.MISC).sized(1.3f, 2.0f).noSave().noSummon().fireImmune()
            .clientTrackingRange(10).updateInterval(1).build(key("draught_horse"))
    }

    val WRECK: DeferredHolder<EntityType<*>, EntityType<Wreck>> = ENTITIES.register("caravan_wreck") { ->
        EntityType.Builder.of(::Wreck, MobCategory.MISC).sized(2.0f, 1.4f).noSave().noSummon().fireImmune()
            .clientTrackingRange(10).updateInterval(20).build(key("caravan_wreck"))
    }

    val WRECK_LOT: DeferredHolder<EntityType<*>, EntityType<WreckLot>> = ENTITIES.register("wreck_lot") { ->
        EntityType.Builder.of(::WreckLot, MobCategory.MISC).sized(0.8f, 0.7f).noSave().noSummon().fireImmune()
            .clientTrackingRange(8).updateInterval(20).build(key("wreck_lot"))
    }

    val LOOT_PILE: DeferredHolder<EntityType<*>, EntityType<LootPile>> = ENTITIES.register("loot_pile") { ->
        EntityType.Builder.of(::LootPile, MobCategory.MISC).sized(1.9f, 1.2f).noSave().noSummon().fireImmune()
            .clientTrackingRange(8).updateInterval(20).build(key("loot_pile"))
    }

    /** The 43 `caravan.*` sounds (sounds.json, Part C's). */
    val NAMES: List<String> = listOf(
        "bells", "bottles", "brake", "click", "cough", "crate_down", "crate_lift", "creak", "gasp", "grab", "handoff",
        "hoof", "hoof_stone", "hoof_wood", "horse_hurt", "horse_neigh", "horse_snort", "horse_stamp", "horse_tear", "horse_thud",
        "huff", "knife_saw", "land", "overturn", "pant", "reins_flick", "reins_haul", "rip", "roll", "roll_planks", "roll_stone",
        "sheathe", "slap", "smack", "smash", "stomp", "swish", "tilt", "trace_cut", "wheel_snap", "whistle", "whoop", "wince",
    )

    private val sounds: Map<String, DeferredHolder<SoundEvent, SoundEvent>> = NAMES.associateWith { name ->
        SOUNDS.register("caravan.$name") { -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "caravan.$name")) }
    }

    /** The `caravan.[name]` sound. */
    fun sound(name: String): SoundEvent = sounds.getValue(name).get()

    /** Plays `caravan.[name]` at [at] for everyone near (heard 16 blocks a unit of [volume]). */
    fun sound(level: ServerLevel, at: Vec3, name: String, volume: Float, pitch: Float) {
        level.playSound(null, at.x, at.y, at.z, sound(name), SoundSource.NEUTRAL, volume, pitch)
    }

    // ---- What's out ----

    /** How often (ticks) it looks round for bodies to make and let go. */
    const val SCAN = 20

    /** Caravans' bodies are made within [NEAR] of a player, let go of with nobody within [FAR]; at most [EACH] per player, [MOST] in all. */
    private const val NEAR = 128.0
    private const val FAR = 160.0
    private const val EACH = 2
    private const val MOST = 4

    /** A waylaid driver at a gate comes for a player within [GATE_NEAR], unseen; goes with nobody within [GATE_FAR] for [LINGER] ticks. */
    private const val GATE_NEAR = 64.0
    private const val GATE_FAR = 96.0
    private const val LINGER = 200

    private val shows = LinkedHashMap<Long, Show>()
    private val doomed = HashSet<Long>()
    private val gates = LinkedHashMap<GateShow, Int>()
    private val strays = ArrayList<Townsperson>()
    private var registered = false

    /** Registers the bodies with the game; called once from the mod's constructor. */
    fun register(modBus: IEventBus) {
        if (registered) return
        registered = true
        ENTITIES.register(modBus)
        SOUNDS.register(modBus)
        val bus = NeoForge.EVENT_BUS
        // After everyone has moved, so the wagon is where its horse is this very tick, and the crew on it
        bus.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (level.dimension() == Level.OVERWORLD) tick(level)
        }
        bus.addListener(EntityJoinLevelEvent::class.java, ::joined)
        bus.addListener(LivingIncomingDamageEvent::class.java, ::hurt)
        bus.addListener(LivingDeathEvent::class.java, ::died)
        bus.addListener(LivingDropsEvent::class.java, ::drops)
        bus.addListener(RegisterCommandsEvent::class.java) { BodiesCommand.register(it.dispatcher) }
        bus.addListener(ServerStoppedEvent::class.java) { _ -> clear(quiet = true) }
        Caravans.listen(listener)
    }

    // ---- Each tick ----

    private fun tick(level: ServerLevel) {
        if (!Caravans.enabled()) {
            if (shows.isNotEmpty() || gates.isNotEmpty()) clear(quiet = false)
            return
        }
        for ((id, show) in shows.entries.toList()) {
            if (id in doomed) continue
            val ok = try {
                show.step()
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Caravan #{}'s bodies went wrong; letting them go", id, e)
                false
            }
            if (!ok) doomed.add(id)
        }
        if (doomed.isNotEmpty()) {
            for (id in doomed.toList()) shows.remove(id)?.dismiss()
            doomed.clear()
        }
        if (gates.isNotEmpty()) {
            val gone = gates.keys.filter { !it.step() }
            for (g in gone) gates.remove(g)
        }
        if (level.gameTime % SCAN == 3L) scan(level)
    }

    /** The look round: bodies made for caravans near the players, let go of where nobody is. */
    private fun scan(level: ServerLevel) {
        val players = level.players().filter { !it.isSpectator }
        // Nobody near for a while: gone
        for ((id, show) in shows.entries.toList()) {
            val at = show.wagonAt()
            val near = players.any { flat(it.position(), at) <= FAR }
            if (show.nobodyNear(near, SCAN)) shows.remove(id)?.dismiss()
        }
        for (player in players) {
            if (shows.size >= MOST) break
            val mine = shows.values.count { flat(it.wagonAt(), player.position()) <= NEAR }
            if (mine >= EACH) continue
            var made = mine
            for (view in Caravans.near(level, player.blockPosition(), NEAR)) {
                if (made >= EACH || shows.size >= MOST) break
                if (view.id in shows) continue
                if (view.state == Caravans.State.WAYLAID && gates.keys.any { it.driver.caravan == view.id }) continue
                val show = Show.make(level, view, players) ?: continue
                shows[view.id] = show
                made++
            }
        }
        WreckShows.scan(level, players)
        gates(level, players)
        strays(level, players)
    }

    /** The waylaid drivers at the gates near the players: one comes to sit by the gate, out of sight, for each. */
    private fun gates(level: ServerLevel, players: List<ServerPlayer>) {
        for ((g, unseen) in gates.entries.toList()) {
            val near = players.any { it.distanceToSqr(g.p) <= GATE_FAR * GATE_FAR }
            val now = if (near) 0 else unseen + SCAN
            if (now >= LINGER) {
                gates.remove(g)
                g.dismiss()
            } else gates[g] = now
        }
        for (player in players) {
            for (d in Caravans.drivers(level, player.blockPosition(), GATE_NEAR)) {
                if (gates.keys.any { it.driver.caravan == d.caravan }) continue
                if (shows[d.caravan]?.mode == Show.Mode.RUN) continue
                val at = Vec3.atBottomCenterOf(d.at)
                if (!level.isPositionEntityTicking(d.at)) continue
                if (players.any { flat(it.position(), at) < 20.0 || it.distanceToSqr(at) < 48.0 * 48.0 && looking(it, at) }) continue
                val y = Ground.stand(level, at.x, at.z, at.y + 0.5)
                val p = Crew.spawn(level, Vec3(at.x, y, at.z), level.random.nextFloat() * 360f, d.caravan, 0) ?: continue
                gates[GateShow(level, d, p)] = 0
            }
        }
    }

    /** Bodies on their way off (a driver with nobody to tell): gone once nobody sees. */
    private fun strays(level: ServerLevel, players: List<ServerPlayer>) {
        strays.removeIf { p ->
            if (p.isRemoved) return@removeIf true
            val seen = players.any { it.distanceToSqr(p) < 24.0 * 24.0 || it.distanceToSqr(p) < 64.0 * 64.0 && looking(it, p.position()) }
            if (!seen || p.tickCount > 20 * 60 * 5) {
                p.discard()
                true
            } else false
        }
    }

    private fun looking(player: ServerPlayer, at: Vec3): Boolean {
        val to = at.subtract(player.eyePosition)
        val d = to.length()
        return d < 1e-3 || player.lookAngle.dot(to.scale(1.0 / d)) > 0.5
    }

    /** A waylaid driver's body handed over from its show: theirs to sit by the gate now. */
    fun gate(g: GateShow) {
        gates[g] = 0
    }

    /** Driver [d]'s body at their place by the gate now, seen or not (a command). False if it couldn't be made. */
    fun gateNow(level: ServerLevel, d: Caravans.Driver): Boolean {
        for (g in gates.keys.filter { it.driver.caravan == d.caravan }) {
            gates.remove(g)
            g.dismiss()
        }
        val at = Vec3.atBottomCenterOf(d.at)
        val y = Ground.stand(level, at.x, at.z, at.y + 0.5)
        val near = level.getNearestPlayer(at.x, y, at.z, 16.0, false)
        val yaw = if (near == null) 0f else Show.yawOf(near.x - at.x, near.z - at.z)
        val p = Crew.spawn(level, Vec3(at.x, y, at.z), yaw, d.caravan, 0) ?: return false
        gates[GateShow(level, d, p)] = 0
        return true
    }

    /** [p] goes once nobody sees. */
    fun stray(p: Townsperson) {
        p.navigation.stop()
        strays.add(p)
    }

    // ---- Who's whose ----

    /** Whether [e] (a wagon, a horse, a wreck and its lots, a loot pile) is one of the bodies out now; if not, it goes. */
    fun owns(e: Entity): Boolean = when (e) {
        is Wagon, is DraughtHorse -> shows.values.any { it.owns(e) }
        is Wreck, is WreckLot, is LootPile -> WreckShows.owns(e)
        else -> false
    }

    /** The show [e] is in (one of its crew or its band), if any. */
    private fun showOf(e: Entity): Show? = shows.values.firstOrNull { s -> s.hands.any { it.p === e } || s.waylay?.owns(e) == true }

    /** Whether wreck [id]'s wagon is still standing, its show not yet done with it (so the wreck's body waits). */
    fun wreckHeld(level: ServerLevel, id: Long): Boolean {
        val rec = CaravanData.get(level).wrecks.firstOrNull { it.id == id } ?: return false
        val show = shows[rec.caravan] ?: return false
        return show.wagon != null
    }

    /**
     * Where a wreck's body lies: the back axle's spot and the yaw the wagon stood at as it went over, and to which side;
     * the [coat] of the horse that pulled it, and whether the band shot from the side it went over onto ([arrowsUnder]).
     */
    class Pose(val at: Vec3, val yaw: Float, val mirror: Boolean, val coat: Int = 0, val arrowsUnder: Boolean = false)

    /**
     * Where wreck [w]'s body lies: just where its wagon pulled up for the strike (swerved toward the verge the records
     * have it on), as [Show] places it there, so a wreck come upon later lies as the one seen going over.
     */
    fun wreckPose(level: ServerLevel, w: Caravans.Wreck): Pose {
        val rec = CaravanData.get(level).wrecks.firstOrNull { it.id == w.id } ?: return Pose(w.pos, w.yaw, false)
        val coat = DraughtHorse.coatOf(rec.caravan)
        val fallback = Pose(w.pos, w.yaw, false, coat)
        val leg = CaravanRoutes.cached(rec.capital, rec.road) ?: return fallback
        val way = Way(leg.points)
        if (way.empty) return fallback
        val along = way.nearest(rec.x, rec.z)
        val p = way.at(along)
        val (rx, rz) = way.right(along)
        val side = if ((rec.x - p.x) * rx + (rec.z - p.z) * rz >= 0.0) 1.0 else -1.0
        val (at, yaw) = Show.strikePose(way, along, side)
        // The band shot from its camp's side of the road (as Show.bandSide has it): gone over onto that side, the arrows are under it
        val camp = OutlawCamps.get(level).band(rec.band)?.pos
        val band = if (camp == null) -side else if ((camp.x + 0.5 - p.x) * rx + (camp.z + 0.5 - p.z) * rz >= 0.0) 1.0 else -1.0
        return Pose(at, yaw, side < 0.0, coat, band == side)
    }

    /** The shows out now, for the commands. */
    fun shows(): Collection<Show> = shows.values

    /** A line on what else is out, for the commands. */
    fun summary(): String = "${shows.size} caravan(s) shown, ${gates.size} driver(s) at the gates, ${strays.size} on their way off, " + WreckShows.summary()

    /** Lets every body go (the server stopping, [quiet]: the records aren't told; the wagons switched off). */
    fun clear(quiet: Boolean) {
        for (show in shows.values.toList()) show.dismiss(quiet)
        shows.clear()
        doomed.clear()
        for (g in gates.keys.toList()) g.dismiss()
        gates.clear()
        for (p in strays) p.discard()
        strays.clear()
        WreckShows.clear()
    }

    /** Lets caravan [id]'s bodies go now (a command). */
    fun drop(id: Long): Boolean {
        val s = shows.remove(id) ?: return false
        s.dismiss()
        return true
    }

    // ---- Heard from the records ----

    private val listener = object : Caravans.Listener {
        override fun struck(level: ServerLevel, caravan: Caravans.View, strike: Caravans.Strike) {
            val s = shows[caravan.id] ?: return
            if (!s.settled(strike)) doomed.add(caravan.id)
        }

        override fun arrived(level: ServerLevel, caravan: Caravans.View) {
            val s = shows[caravan.id] ?: return
            if (!s.arrived(caravan)) doomed.add(caravan.id)
        }

        override fun ended(level: ServerLevel, caravan: Long) {
            shows[caravan]?.ended()
        }

        override fun wreckCleared(level: ServerLevel, wreck: Long) {
            WreckShows.cleared(wreck)
        }
    }

    // ---- Events ----

    /** A body that was saved by mistake (a chunk saved with the crew or the band in it) goes again as it's loaded. */
    private fun joined(event: EntityJoinLevelEvent) {
        if (!event.loadedFromDisk()) return
        val tags = event.entity.entityTags()
        if (Crew.TAG in tags || Waylay.TAG in tags || Watchmen.TAG in tags) event.isCanceled = true
    }

    /**
     * Who may be hurt: the crew by nobody but the band in a fight a player has joined (the driver never), and the band
     * by anyone, a player's blow making the fight real.
     */
    private fun hurt(event: LivingIncomingDamageEvent) {
        val e = event.entity
        if (e.level().isClientSide) return
        val crew = e is Townsperson && Crew.of(e)
        val band = Waylay.TAG in e.entityTags()
        if (!crew && !band) return
        val show = showOf(e)
        val w = show?.waylay
        if (w == null) {
            // A gate's driver, a guard limping in, one of a band whose show has gone: the crew come to no harm
            if (crew) event.isCanceled = true
            return
        }
        if (!w.allows(e, event.source)) event.isCanceled = true
    }

    private fun died(event: LivingDeathEvent) {
        val e = event.entity
        if (e.level().isClientSide || Waylay.TAG !in e.entityTags()) return
        showOf(e)?.waylay?.died(e)
    }

    /** The band and the crew drop nothing but what the band carried off (a sack of it, marked as off the wagon). */
    private fun drops(event: LivingDropsEvent) {
        val e = event.entity
        val level = e.level() as? ServerLevel ?: return
        val tags = e.entityTags()
        if (Waylay.TAG in tags) {
            event.drops.clear()
            val carried = showOf(e)?.waylay?.drops(e) ?: return
            for (stack in carried) event.drops.add(ItemEntity(level, e.x, e.y + 0.5, e.z, stack))
        } else if (e is Townsperson && (Crew.TAG in tags || Watchmen.TAG in tags)) {
            event.drops.clear()
        }
    }
}
