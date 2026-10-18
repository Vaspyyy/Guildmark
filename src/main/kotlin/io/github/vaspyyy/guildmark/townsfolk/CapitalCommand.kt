package io.github.vaspyyy.guildmark.townsfolk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.plan.Census
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.AbstractBedBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.phys.HitResult
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

/**
 * Testing commands for a capital's places and people. `/guildmark capital info` sums up the capital you're in,
 * `here` lists the building you stand in (its floors, doors, ladders, beds, seats, tables, work blocks and stores)
 * and who lives there, `who` tells you about the townsperson you're looking at, `show` marks the building's places
 * for a while (only you see the marks), `furnish` makes up every bed its people are missing at once, and
 * `people [trade] [page]` lists its people. Positions in the replies can be clicked to go there.
 */
object CapitalCommand {
    /** How far `who` looks along your gaze, and how near someone must be to do without it. */
    private const val SIGHT = 16.0
    private const val NEAR = 4.0

    /** How long `show` marks a building, and how often the marks are drawn again. */
    private const val SHOW_TICKS = 300
    private const val SHOW_EVERY = 8

    private const val PAGE = 12

    /** Each kind of place `show` marks: its name and colour. */
    private enum class Mark(val label: String, val colour: Int) {
        DOOR("doors", 0xFF9A3C), LADDER("ladders", 0xFFE14D), BED("beds", 0xFF4D5E), SEAT("seats", 0x4D8BFF),
        TABLE("tables", 0x3CE0D2), STATION("work blocks", 0xC46BFF), STORE("stores", 0x5CE65C),
    }

    private class Show(val level: ServerLevel, val marks: List<Pair<BlockPos, Mark>>, val until: Int)

    private val shows = HashMap<UUID, Show>()

    /** Registers the commands, and the ticking of `show`'s marks; called once from Townsfolk.init. */
    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (shows.isNotEmpty()) mark(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> shows.clear() }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("capital")
                        .then(Commands.literal("info").executes { info(it) })
                        .then(Commands.literal("here").executes { here(it) })
                        .then(Commands.literal("who").executes { who(it) })
                        .then(Commands.literal("show").executes { show(it) })
                        .then(Commands.literal("furnish").executes { furnish(it) })
                        .then(
                            Commands.literal("people")
                                .executes { people(it, "all", 1) }
                                .then(
                                    Commands.argument("trade", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("all") + TownRole.entries.map { it.id }, builder) }
                                        .executes { people(it, StringArgumentType.getString(it, "trade"), 1) }
                                        .then(
                                            Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes { people(it, StringArgumentType.getString(it, "trade"), IntegerArgumentType.getInteger(it, "page")) },
                                        ),
                                ),
                        ),
                ),
        )
    }

    // ---- info ----

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val capital = Capitals.covering(level, source.playerOrException.blockPosition()) ?: return fail(source, "You're not in a capital.")
        val tier = capital.layout?.tier?.name?.lowercase() ?: "old"
        say(source, header("${name(capital)}: a $tier capital, ${capital.style.name.lowercase()} style"))
        say(source, line().append(label("Centre ")).append(at(capital.center)).append(label("  bell ")).append(at(capital.bell)))
        val places = CityPlaces.of(level, capital)
        if (places == null) {
            say(source, Component.literal(if (CityPlaces.failed(capital)) "Its places couldn't be worked out (see the log)." else "Its places are still being worked out: try again in a second.").withStyle(ChatFormatting.RED))
            return 1
        }
        val lodgings = places.lodgings.values
        val town = lodgings.filter { !it.farm }.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
        val farms = lodgings.filter { it.farm }.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
        say(source, line().append(label("Buildings ${lodgings.size}: ")).append(plain(town.joinToString(", ") { "${words(it.key)} ${it.value}" })))
        if (farms.isNotEmpty()) say(source, line().append(label("In the country: ")).append(plain(farms.joinToString(", ") { "${words(it.key)} ${it.value}" })))
        val people = Residents.get(level).of(capital.center)
        val alive = people.filter { it.alive }
        val madeUp = alive.count { it.furnished && it.bed != null }
        say(source, line().append(label("Beds: ")).append(plain("${lodgings.sumOf { it.beds.size }} laid out by the plan, $madeUp made up since")))
        say(source, line().append(label("Market stalls ")).append(plain("${places.stalls.size}")).append(label("  wells ")).append(plain("${places.wells.size}"))
            .append(label("  lamps ")).append(plain("${places.lamps.size}")).append(label("  benches ")).append(plain("${places.benches.size}"))
            .append(label("  fields ")).append(plain("${places.fields.size}")))
        val withBed = alive.count { it.bed != null }
        val toMake = alive.count { it.bed == null && it.home >= 0 && !it.furnished }
        val noRoom = alive.count { it.bed == null && it.furnished }
        val homeless = alive.count { it.bed == null && it.home < 0 }
        say(source, line().append(label("People: ")).append(plain("${alive.size} living, ${people.size - alive.size} dead (the plan has ${places.census.residents.size}; the rest are taken in as they're met)")))
        say(source, line().append(label("  with a bed ")).append(plain("$withBed")).append(label("  without ")).append(plain("${alive.size - withBed}"))
            .append(grey(" ($toMake still to be made up, $noRoom with no room, $homeless with no home)")))
        val trades = alive.groupingBy { it.role }.eachCount().entries.sortedByDescending { it.value }
        if (trades.isNotEmpty()) say(source, line().append(label("Trades: ")).append(plain(trades.joinToString(", ") { "${it.key.id} ${it.value}" })))
        say(source, grey(if (Furnish.enabled()) "Beds are made up out of sight as people are met (setting: Beds for Townsfolk)." else "Making up beds is switched off (setting: Beds for Townsfolk)."))
        return 1
    }

    // ---- here ----

    private fun here(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (player, capital, places) = where(source) ?: return 0
        val level = source.level
        val l = places.lodgingAt(player.blockPosition()) ?: return fail(source, "You're not in any of ${name(capital)}'s buildings: stand inside one.")
        val floors = floors(level, l)
        val extra = floors - l.floors.toSet() - l.y
        say(source, header("${building(l)}, floors at ${floors.joinToString(", ")}" + if (extra.isNotEmpty()) " (${extra.joinToString(", ")} found by its ladders)" else ""))
        say(source, list("Doors", l.doors.map { d -> line().append(at(d.pos)).append(grey(" opens ${d.inward.name.lowercase()}")) }))
        say(source, list("Ladders", l.ladders.map { ld ->
            val exits = floors.filter { it in ld.bottom..ld.top + 1 }
            val c = line().append(at(ld.at(ld.bottom))).append(grey(" ${ld.bottom} to ${ld.top}, off at "))
            exits.forEachIndexed { i, y -> if (i > 0) c.append(grey(" ")); c.append(at(ld.landing(y))) }
            c
        }, 4))
        val residents = Residents.get(level).of(capital.center)
        val owners = residents.filter { it.alive && it.bed != null }.associateBy { it.bed!! }
        val living = residents.filter { it.home == l.id }.sortedWith(compareBy({ !it.alive }, { it.role == TownRole.CHILD }, { it.id }))
        val beds = LinkedHashSet<BlockPos>(l.beds)
        residents.filter { it.alive }.forEach { r -> r.bed?.let { if (l.contains(it) || r.home == l.id) beds.add(it) } }
        say(source, list("Beds", beds.map { bed ->
            val owner = owners[bed]
            val c = line().append(at(bed)).append(if (owner != null) plain(" " + owner.given) else grey(" free"))
            if (owner != null && owner.furnished && bed !in l.beds) c.append(grey(" (made up)"))
            if (!isBed(level, bed)) c.append(Component.literal(" (gone)").withStyle(ChatFormatting.RED))
            c
        }, 6))
        say(source, list("Seats", l.seats.map { at(it.pos) }))
        say(source, list("Tables", l.tables.map { at(it) }))
        say(source, list("Work blocks", l.stations.map { s -> line().append(level.getBlockState(s.pos).block.name.copy().withStyle(ChatFormatting.WHITE)).append(plain(" ")).append(at(s.pos)) }, 6))
        say(source, list("Stores", l.stores.map { s -> line().append(level.getBlockState(s).block.name.copy().withStyle(ChatFormatting.WHITE)).append(plain(" ")).append(at(s)) }, 6))
        say(source, line().append(label("Living here ${living.size}:")).append(if (living.isEmpty()) grey(" nobody yet") else line()))
        for (r in living.take(8)) {
            val c = line().append(plain("  " + r.name)).append(grey(", ")).append(trade(r.role).withStyle(ChatFormatting.GRAY)).append(grey(": "))
            val bed = r.bed
            when {
                !r.alive -> c.append(grey("died on day ${r.died}"))
                bed != null -> c.append(grey("bed ")).append(at(bed))
                else -> c.append(grey(bedless(r)))
            }
            say(source, c)
        }
        if (living.size > 8) say(source, grey("  and ${living.size - 8} more (/guildmark capital people)"))
        return 1
    }

    // ---- who ----

    private fun who(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.level
        val person = lookedAt(player) ?: return fail(source, "Look at a townsperson (up to ${SIGHT.toInt()} blocks off), or stand right by one.")
        val r = Residents.get(level).of(person)
        val task = person.routine.task?.javaClass?.simpleName?.let(::words)?.ifEmpty { "busy" } ?: "nothing"
        val phase = person.routine.phase?.let { "${words(it.name)} time" } ?: "the day not started yet"
        val doing = line().append(label("Now: ")).append(plain(task)).append(grey(" (${words(person.activity.clip)}), $phase, at ")).append(at(person.blockPosition()))
        if (r == null) {
            say(source, header(person.name.string))
            say(source, line().append(trade(person.role)).append(grey(if (person.crowd) ": a passer-by, not one of the town's people" else ": not taken in as one of the town's people yet")))
            say(source, doing)
            return 1
        }
        say(source, header(r.name))
        say(source, line().append(trade(r.role)).append(plain(", resident #${r.id}" + (if (r.planned >= 0) " (the plan's #${r.planned})" else "") + ", ")).append(
            if (r.alive) Component.literal("living").withStyle(ChatFormatting.GREEN) else Component.literal("died on day ${r.died}").withStyle(ChatFormatting.RED),
        ))
        val capital = Capitals.covering(level, person.blockPosition())?.takeIf { it.center == r.capital }
            ?: Capitals.covering(level, player.blockPosition())?.takeIf { it.center == r.capital }
        val places = capital?.let { CityPlaces.of(level, it) }
        val home = places?.lodgings?.get(r.home)
        val days = VillageBoards.day(level) - r.since
        val homeLine = line().append(label("Home: "))
        when {
            r.home < 0 -> homeLine.append(plain("none"))
            home != null -> homeLine.append(plain(building(home) + " ")).append(at(home.front?.inside ?: home.box.center))
            else -> homeLine.append(plain("building #${r.home}")).append(grey(if (capital == null) " (in a capital away from here)" else " (its places are still being worked out)"))
        }
        homeLine.append(grey(", here since day ${r.since} (${if (days == 1L) "1 day" else "$days days"})"))
        say(source, homeLine)
        val bed = r.bed
        val bedLine = line().append(label("Bed: "))
        if (bed == null) bedLine.append(plain(bedless(r)))
        else {
            bedLine.append(at(bed)).append(grey(if (r.furnished) " made up for them" else " the plan's"))
            if (level.isLoaded(bed) && !isBed(level, bed)) bedLine.append(Component.literal(", gone").withStyle(ChatFormatting.RED))
            else if (HeldBeds.get(level).has(bed)) bedLine.append(grey(", kept from villagers"))
        }
        say(source, bedLine)
        val traits = Trait.entries.filter { r.has(it) }.joinToString(", ") { trait(it) }
        say(source, line().append(label("Ways: ")).append(plain(traits.ifEmpty { "nothing out of the way" })))
        say(source, doing)

        // ---- Today's agenda: agenda() below fills these lines in ----
        val today = agenda(level, r, person)
        if (today.isEmpty()) say(source, grey("Today: (no agenda yet)"))
        else {
            say(source, label("Today:"))
            for (c in today) say(source, line().append(plain("  ")).append(c))
        }
        return 1
    }

    /**
     * TODAY'S AGENDA, one line each, for `who`: where [r] is to be when, today. A stub for now: the townsfolk's
     * day (Routine, Jobs) fills it in.
     */
    fun agenda(level: ServerLevel, r: Resident, person: Townsperson?): List<Component> = listOf()

    /** The townsperson [player] is looking at, within [SIGHT] and not behind a wall; else the nearest within [NEAR]. */
    private fun lookedAt(player: ServerPlayer): Townsperson? {
        val level = player.level()
        val eye = player.eyePosition
        val look = player.getViewVector(1f)
        var end = eye.add(look.scale(SIGHT))
        val block = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (block.type != HitResult.Type.MISS) end = block.location
        val box = player.boundingBox.expandTowards(look.scale(SIGHT)).inflate(1.0)
        val hit = ProjectileUtil.getEntityHitResult(player, eye, end, box, { it is Townsperson && it.isAlive }, SIGHT * SIGHT)
        (hit?.entity as? Townsperson)?.let { return it }
        return level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(NEAR)) { it.isAlive && it.distanceTo(player) <= NEAR }
            .minByOrNull { it.distanceToSqr(player) }
    }

    // ---- show ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (player, capital, places) = where(source) ?: return 0
        val level = source.level
        val l = places.lodgingAt(player.blockPosition()) ?: return fail(source, "You're not in any of ${name(capital)}'s buildings: stand inside one.")
        val marks = ArrayList<Pair<BlockPos, Mark>>()
        for (d in l.doors) {
            marks.add(d.pos to Mark.DOOR)
            marks.add(d.pos.above() to Mark.DOOR)
        }
        for (ld in l.ladders) for (y in ld.bottom..ld.top) marks.add(ld.at(y) to Mark.LADDER)
        val beds = LinkedHashSet<BlockPos>(l.beds)
        Residents.get(level).of(capital.center).forEach { r -> r.bed?.let { if (r.alive && (l.contains(it) || r.home == l.id)) beds.add(it) } }
        for (bed in beds) {
            marks.add(bed to Mark.BED)
            val state = level.getBlockState(bed)
            if (state.`is`(BlockTags.BEDS) && state.getValue(AbstractBedBlock.PART) == BedPart.HEAD) {
                marks.add(bed.relative(state.getValue(HorizontalDirectionalBlock.FACING).opposite) to Mark.BED)
            }
        }
        l.seats.forEach { marks.add(it.pos to Mark.SEAT) }
        l.tables.forEach { marks.add(it to Mark.TABLE) }
        l.stations.forEach { marks.add(it.pos to Mark.STATION) }
        l.stores.forEach { marks.add(it to Mark.STORE) }
        shows[player.uuid] = Show(level, marks, level.server.tickCount + SHOW_TICKS)
        val legend = line().append(plain("Marking ${building(l)} for ${SHOW_TICKS / 20} seconds: "))
        Mark.entries.forEachIndexed { i, m ->
            if (i > 0) legend.append(grey("  "))
            legend.append(Component.literal("■ ").withStyle { it.withColor(m.colour) }).append(plain(m.label))
            legend.append(grey(" ${marks.count { it.second == m }}"))
        }
        say(source, legend)
        return 1
    }

    /** Draws each `show`'s marks again for its player alone: a dot of its colour at each corner of each block. */
    private fun mark(server: MinecraftServer) {
        val now = server.tickCount
        val it = shows.entries.iterator()
        while (it.hasNext()) {
            val (id, show) = it.next()
            val player = server.playerList.getPlayer(id)
            if (player == null || now > show.until || player.level() !== show.level) {
                it.remove()
                continue
            }
            if (now % SHOW_EVERY != 0) continue
            val level = show.level
            val corners = show.marks.size <= 64
            for ((pos, m) in show.marks) {
                if (pos.distSqr(player.blockPosition()) > 64 * 64) continue
                val dust = DustParticleOptions(m.colour, 1.0f)
                if (!corners) {
                    level.sendParticles(player, DustParticleOptions(m.colour, 1.5f), true, true, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 3, 0.25, 0.25, 0.25, 0.0)
                    continue
                }
                for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
                    level.sendParticles(player, dust, true, true, pos.x + 0.1 + cx * 0.8, pos.y + 0.1 + cy * 0.8, pos.z + 0.1 + cz * 0.8, 1, 0.0, 0.0, 0.0, 0.0)
                }
            }
        }
    }

    // ---- furnish ----

    private fun furnish(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val (_, capital, places) = where(source) ?: return 0
        val report = Furnish.all(source.level, capital, places)
        val made = line().append(plain("Made up ${report.made} ${if (report.made == 1) "bed" else "beds"} in ${name(capital)}; ${report.noRoom} ${if (report.noRoom == 1) "person" else "people"} had no room in their home."))
        say(source, made)
        if (report.beds.isNotEmpty()) say(source, list("New beds", report.beds.map { at(it) }, 8))
        if (report.lost > 0) say(source, grey("${report.lost} of the beds people had were broken or someone else's, and were seen to again."))
        if (report.waiting > 0) say(source, grey("${report.waiting} could not be seen to now (their home isn't loaded, or someone stands where the bed would go): go nearer and try again."))
        say(source, grey("${report.kept} already had their bed."))
        return report.made
    }

    // ---- people ----

    private fun people(context: CommandContext<CommandSourceStack>, trade: String, page: Int): Int {
        val source = context.source
        val (_, capital, places) = where(source) ?: return 0
        val role = if (trade == "all") null else TownRole.byName(trade) ?: return fail(source, "No such trade: $trade")
        val people = Residents.get(source.level).of(capital.center).filter { role == null || it.role == role }
            .sortedWith(compareBy({ !it.alive }, { it.role.ordinal }, { it.family }, { it.given }))
        val pages = maxOf(1, (people.size + PAGE - 1) / PAGE)
        val shown = page.coerceAtMost(pages)
        say(source, header("People of ${name(capital)}${if (role != null) " (${role.id})" else ""}: ${people.size}" + if (pages > 1) ", page $shown of $pages" else ""))
        if (people.isEmpty()) say(source, grey("Nobody yet: people are taken in as they're met."))
        for (r in people.drop((shown - 1) * PAGE).take(PAGE)) {
            val c = line().append(if (r.alive) plain(r.name) else grey(r.name)).append(grey(" - ")).append(trade(r.role).withStyle(ChatFormatting.GRAY)).append(grey(", "))
            val home = places.lodgings[r.home]
            if (home != null) c.append(plain(building(home) + " ")).append(at(home.front?.inside ?: home.box.center)) else c.append(grey("no home"))
            when {
                !r.alive -> c.append(grey(", died on day ${r.died}"))
                r.bed != null -> c.append(Component.literal(", bed ✔").withStyle(ChatFormatting.GREEN))
                else -> c.append(Component.literal(", no bed").withStyle(ChatFormatting.RED)).append(grey(" (${bedless(r)})"))
            }
            say(source, c)
        }
        if (shown < pages) {
            say(source, Component.literal("Next page").withStyle {
                it.withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(ClickEvent.RunCommand("/guildmark capital people $trade ${shown + 1}"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("/guildmark capital people $trade ${shown + 1}")))
            })
        }
        return people.size
    }

    // ---- Shared ----

    /** The capital the player stands in and its places; or null, having said why not. */
    private fun where(source: CommandSourceStack): Triple<ServerPlayer, CapitalPiece, CityPlaces>? {
        val player = source.playerOrException
        val capital = Capitals.covering(source.level, player.blockPosition())
        if (capital == null) {
            fail(source, "You're not in a capital.")
            return null
        }
        val places = CityPlaces.of(source.level, capital)
        if (places == null) {
            fail(source, if (CityPlaces.failed(capital)) "This capital's places couldn't be worked out (see the log)." else "This capital's places are still being worked out: try again in a second.")
            return null
        }
        return Triple(player, capital, places)
    }

    /** The floors of [l]: those the plan knows, and wherever one of its ladders lets off onto one. */
    private fun floors(level: ServerLevel, l: CityPlaces.Lodging): List<Int> {
        val ys = sortedSetOf(l.y)
        ys.addAll(l.floors)
        for (ld in l.ladders) for (y in ld.bottom..ld.top + 1) if (Places.standable(level, ld.landing(y))) ys.add(y)
        return ys.toList()
    }

    private fun isBed(level: ServerLevel, pos: BlockPos): Boolean = level.isLoaded(pos) && level.getBlockState(pos).`is`(BlockTags.BEDS)

    /** Why [r] has no bed, in a few words. */
    private fun bedless(r: Resident): String = when {
        r.home < 0 -> "no home to have one in"
        r.furnished -> "no room for one in their home"
        else -> "one is still to be made up"
    }

    private fun name(capital: CapitalPiece): String = VillageNames.of(capital.boardAnchor)

    private fun building(l: CityPlaces.Lodging): String =
        words(l.kind) + if (l.farm) " #${l.id} (farm ${l.id - Census.FARM})" else " #${l.id}"

    private fun words(kind: String): String = kind.lowercase().replace('_', ' ')

    private fun trade(role: TownRole): MutableComponent = Component.translatable("entity.guildmark.townsperson.${role.id}")

    private fun trait(t: Trait): String = when (t) {
        Trait.EARLY -> "up early"
        Trait.LATE -> "up late"
        Trait.DEVOUT -> "devout"
        Trait.DRINKER -> "fond of the tavern"
        Trait.CHATTY -> "chatty"
        Trait.SHY -> "shy"
        Trait.DILIGENT -> "hard-working"
        Trait.IDLE -> "idle"
        Trait.CHEERFUL -> "cheerful"
        Trait.GRUMPY -> "grumpy"
    }

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    /** A position to click to go to. */
    private fun at(pos: BlockPos): MutableComponent = Component.literal("[${pos.x} ${pos.y} ${pos.z}]").withStyle {
        it.withColor(ChatFormatting.AQUA)
            .withClickEvent(ClickEvent.RunCommand("/tp @s ${pos.x} ${pos.y} ${pos.z}"))
            .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to go there")))
    }

    /** "[label] [count]: a, b, c and 4 more", on one line. */
    private fun list(name: String, items: List<Component>, cap: Int = 8): MutableComponent {
        val c = line().append(label("$name ${items.size}: "))
        if (items.isEmpty()) return c.append(grey("none"))
        items.take(cap).forEachIndexed { i, item ->
            if (i > 0) c.append(grey(", "))
            c.append(item)
        }
        if (items.size > cap) c.append(grey(" and ${items.size - cap} more"))
        return c
    }

    private fun say(source: CommandSourceStack, c: Component) = source.sendSuccess({ c }, false)

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
