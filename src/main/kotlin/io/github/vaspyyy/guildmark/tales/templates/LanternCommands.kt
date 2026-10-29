package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleEscort
import io.github.vaspyyy.guildmark.tales.TaleLimits
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * The lantern's commands (living-world/stories/design.md 8.9 and 12.4), game masters only:
 *
 *  - `/guildmark tale tradition list`: the traditions the capital you stand in keeps, each with the year it began and the Lanternrises
 *    it has been kept. `add <name>` makes a lantern tradition for [name] now (so a test needs no finished tale; `festival start
 *    lanternrise` then shows its lantern at the release); `clear` lets all of them go.
 *  - `/guildmark tale lantern`: the lantern tale of the capital you stand in, from the record (the festival's day, the candle, the walk,
 *    whether the town's lanterns have begun and the ending decided), and what the hour means for it. It reads; it changes nothing.
 *  - `/guildmark tale scene lantern_door`, `lantern_candle`, `lantern_letgo`, `lantern_dark` and `lantern_walk`: the tale's scenes,
 *    played now near you with whoever is free, whatever the hour or the switches say; `lantern_walk` is the led slow walk to the
 *    fountain of the capital you stand in (or forty blocks the way you face, out of one). No tale is needed or touched.
 */
internal object LanternCommands {
    /** Called once, from [LanternFestival.register] (one line in `Tales.register`). */
    fun register() {
        TaleSceneCommand.add("lantern_door", ::door)
        TaleSceneCommand.add("lantern_candle", ::candle)
        TaleSceneCommand.add("lantern_letgo", ::letGo)
        TaleSceneCommand.add("lantern_dark", ::dark)
        TaleSceneCommand.add("lantern_walk", ::walk)
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("tradition")
                    .then(Commands.literal("list").executes { list(it) })
                    .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.greedyString()).executes { add(it) }))
                    .then(Commands.literal("clear").executes { clear(it) }),
            )
            tale.then(Commands.literal("lantern").executes { show(it) })
        }
    }

    // ---- tale tradition ----

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val here = TaleCommand.here(context) ?: return 0
        TaleEngine.use(source.level)
        val traditions = TaleData.get(source.level).find(here.key)?.traditions.orEmpty()
        TaleCommand.say(source, TaleCommand.header("Traditions of ${here.name}"))
        if (traditions.isEmpty()) return TaleCommand.say(source, TaleCommand.plain("None. A lantern tale that ends A or B leaves one; 'tale tradition add <name>' makes one now."))
        for (tr in traditions) {
            TaleCommand.say(
                source,
                TaleCommand.line().append(TaleCommand.label("${tr.kind}: ")).append(TaleCommand.plain("for ${tr.name.ifBlank { "(no name)" }} at ${tr.festival}"))
                    .append(TaleCommand.grey(", since year ${tr.year}, kept ${tr.kept} of ${TaleLimits.TRADITION_FESTIVALS}")),
            )
        }
        TaleCommand.say(source, TaleCommand.grey("A town keeps ${TaleLimits.TRADITIONS} at most. At each Lanternrise one more sky lantern rises for each from the temple's door as the town's are let go."))
        return 1
    }

    private fun add(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val here = TaleCommand.here(context) ?: return 0
        TaleEngine.use(source.level)
        val name = StringArgumentType.getString(context, "name")
        if (!LanternFestival.add(source.level, here.key, name)) return TaleCommand.fail(source, "${here.name} keeps a lantern for ${name.trim()} already, or that is no name.")
        TaleCommand.say(source, TaleCommand.plain("${here.name} now lets a lantern go for ${name.trim().take(TaleLimits.STRING_LETTERS)} at every Lanternrise."))
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off, so it is kept but not shown until they are on."))
        return TaleCommand.say(source, TaleCommand.grey("To see it: '/guildmark festival start lanternrise' here, and watch from the square at the release (19:00 by the festival's clock; '/guildmark festival phase dusk' brings it near)."))
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val here = TaleCommand.here(context) ?: return 0
        TaleEngine.use(source.level)
        val n = LanternFestival.clear(source.level, here.key)
        return TaleCommand.say(source, TaleCommand.plain(if (n == 0) "${here.name} kept no traditions." else "${here.name}'s $n tradition${if (n == 1) "" else "s"} let go."))
    }

    // ---- tale lantern ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        TaleEngine.use(level)
        val tales = TaleData.get(level).all().filter { it.template == Lantern.id && it.capital == here.key }.sortedBy { it.id }
        TaleCommand.say(source, TaleCommand.header("A Lantern for ${tales.lastOrNull()?.s?.get(Lantern.S_NELL)?.ifBlank { null } ?: "Nell"} in ${here.name}"))
        val (kind, ahead) = FestivalCalendar.next(Seasons.today(level))
        TaleCommand.say(source, line("The calendar", "the next festival is ${kind.id}, " + if (ahead == 0) "today" else "in $ahead days"))
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) return TaleCommand.say(source, TaleCommand.plain("No lantern tale is on record here."))
        val today = TaleEngine.today(level, t.capital)
        val state = if (t.open) "open, at step ${t.step}" else "ended at ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state; the old resident ${t.cast(Lantern.OLD)?.name.orEmpty()}, the lantern for ${t.s[Lantern.S_NELL]}"))
        val f = LanternClock.festival(t)
        val made = if (t.n[Lantern.N_MADE] != 0) " (made up by a forced start: the town holds no festival for it)" else ""
        TaleCommand.say(source, line("Lanternrise", "market day $f, today is $today$made"))
        TaleCommand.say(source, line("The door", LanternClock.door(t)?.let { "${it.x} ${it.y} ${it.z}" } ?: "none"))
        TaleCommand.say(source, line("The candle", if (t.n[Lantern.N_CANDLE] != 0) "in the lantern" else "not given"))
        val walk = when (t.n[Lantern.N_WALK]) {
            1 -> "on the way" + (LanternWalk.walker(t)?.let { id -> level.getPlayerByUUID(id)?.let { " with ${it.gameProfile.name}" } } ?: "")
            2 -> "at the square"
            else -> "not set out (" + when (LanternClock.walk(level, t)) {
                LanternClock.Walk.OPEN -> "it can set out now, until ${LanternClock.text(LanternClock.LATEST)}"
                LanternClock.Walk.EARLY -> "it sets out from ${LanternClock.text(LanternClock.from(t))} on the day"
                LanternClock.Walk.LATE -> "too late to set out"
                LanternClock.Walk.NOT_TODAY -> "not the day"
            } + ")"
        }
        TaleCommand.say(source, line("The walk", walk))
        val site = LanternClock.site(t)
        TaleCommand.say(
            source,
            line(
                "The festival",
                if (site == null) "none live here (the lantern goes up at the fountain at ${LanternClock.text(LanternClock.DUSK)})"
                else "${if (site.forced) "forced" else "by the calendar"}, ${site.phase.id}, ${LanternClock.text(LanternClock.hour(level, t))} by its clock",
            ),
        )
        TaleCommand.say(source, line("The town's lanterns", if (t.n[Lantern.N_BEGUN] != 0) "begun" else "not yet"))
        if (t.n[Lantern.N_ROUTE] != 0) TaleCommand.say(source, line("Decided", "${LanternWalk.ending(t.n[Lantern.N_ROUTE])}, made as its scene ends"))
        TaleBodies.body(level, t, Lantern.OLD)?.let { b ->
            TaleCommand.say(source, line("Old resident", "at ${b.blockX} ${b.blockY} ${b.blockZ}, ${b.distanceTo(source.playerOrException).toInt()} blocks from you, ${b.director?.javaClass?.simpleName ?: "about their day"}"))
        }
        val traditions = LanternFestival.traditions(level, here.key).count { it.kind == LanternFestival.KIND }
        TaleCommand.say(source, line("Traditions", "$traditions lantern${if (traditions == 1) "" else "s"} kept here ('tale tradition list')"))
        return 1
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    // ---- tale scene lantern_* ----

    /** The free adults within 24 blocks of [player], the nearest first. */
    private fun free(level: ServerLevel, player: ServerPlayer): List<Townsperson> = Sets.freeAdults(level, player.position(), 24.0, station = true)

    private fun name(p: Townsperson): String = TaleSceneCommand.name(p)

    private fun first(level: ServerLevel, player: ServerPlayer): Townsperson =
        free(level, player).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 24 blocks of you.")

    private fun door(level: ServerLevel, player: ServerPlayer): String {
        val old = first(level, player)
        val passer = Sets.passerBy(old)
        return TaleSceneCommand.begin(
            level, LanternScenes.door(old, passer, player.position(), lit = false, hours = null),
            "${name(old)} holds the unlit lantern up, looking your way" + if (passer != null) "; ${name(passer)} walks up to comfort them." else " (nobody else free to comfort them).",
        )
    }

    private fun candle(level: ServerLevel, player: ServerPlayer): String {
        val old = first(level, player)
        return TaleSceneCommand.begin(level, LanternScenes.light(old, player), "${name(old)} sets a candle in the paper lantern, and it glows.")
    }

    private fun letGo(level: ServerLevel, player: ServerPlayer): String {
        val old = first(level, player)
        return TaleSceneCommand.begin(level, LanternScenes.letGo(old, player, "tale.lantern.window"), "${name(old)} says their word, lets a lantern go, watches it rise and thanks you.")
    }

    private fun dark(level: ServerLevel, player: ServerPlayer): String {
        val old = first(level, player)
        return TaleSceneCommand.begin(level, LanternScenes.dark(old, player), "${name(old)} holds the dark lantern, looking up, and sits with it.")
    }

    private fun walk(level: ServerLevel, player: ServerPlayer): String {
        val who = Sets.freeAdults(level, player.position(), 16.0).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody free within 16 blocks of you.")
        val target = fountain(level, player) ?: run {
            val look = player.lookAngle
            Sets.standable(level, BlockPos.containing(player.x + look.x * 40.0, player.y, player.z + look.z * 40.0))
        } ?: throw TaleSceneCommand.Unplayable("Nowhere to walk to that way.")
        if (!TaleEscort.start(level, null, "walker", who, player, target, until = level.gameTime + 6000L, leads = true)) throw TaleSceneCommand.Unplayable("${name(who)} cannot be asked to walk just now.")
        who.lantern = true
        return "${name(who)} walks to ${target.x} ${target.y} ${target.z} at a stoop with a lantern, you beside them: they wait if you fall behind and call if you go ahead, and rest at a bench now and then (five minutes at most)."
    }

    /** A place a few blocks out from the fountain of the capital [player] stands in, on their side of it, or null out of one. */
    private fun fountain(level: ServerLevel, player: ServerPlayer): BlockPos? {
        val capital = Capitals.covering(level, player.blockPosition()) ?: return null
        val places = CityPlaces.of(level, capital) ?: return null
        val plan = places.census.plan
        val centre = Vec3.atBottomCenterOf(capital.standing(plan, plan.fountain))
        return Sets.beside(level, centre, player.position(), plan.fountainRadius + 5.0)
    }
}
