package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.arguments.StringArgumentType
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * `/guildmark tale scene <name>` (living-world/stories/design.md 12.4, Part B), game masters only: plays one of the set pieces
 * of 4.3 now, near you, whatever the switches say, with whoever is free nearby and stand-ins for what is not there yet (a stone
 * three blocks ahead that sets no block, a household's gate), as `capital scene` does for M6's scenes. It is a test of the
 * telling: no tale is needed and none is touched. The names are in [scenes]; a template that has a set of its own adds one
 * there ([add]) so its helper can see it from the command too.
 *
 * - `count`: the nearest worker at their post (else the nearest free adult) counts coins, sighs, shrugs and grieves; a
 *   passer-by walks up, says a word of comfort and goes on, and the worker waves them off.
 * - `handing`: one hands a sealed paper to another, who receives it and counts it over.
 * - `burning`: the same, and the paper is carried to the nearest stove or fire and burnt, the onlookers cheering.
 * - `cheer`: a toast, and up to four others cheer and clap under falling petals; a bell near is rung.
 * - `calling`: someone goes a few blocks ahead of you and calls a child's name down the road; up to two others hurry up, ask which
 *   way, and search off in different directions.
 * - `quarrel`: two shout at each other, point, recoil and grumble, a third hushes them and they go to their doors; up to three look on.
 *   `moot` is the same with up to five in a half ring calling out.
 * - `laying`: someone kneels at a stone three blocks ahead (no block is set) and lays a posy, a priest among the others prays, and one embraces them.
 * - `reunion`: one waits looking out, another comes near, they run to each other and embrace, up to three clap and cheer, and you are thanked.
 * - `walk`: the nearest free adult follows you at a stoop for two minutes, resting every thirty blocks or on a bench.
 *
 * `/guildmark tale figures` lists the made figures, the held residents and the stages that are playing; `figures release` lets them
 * all go (and nothing is held or made again for a minute).
 */
internal object TaleSceneCommand {
    /** A scene that cannot be played, and why (said to the one who ran the command). */
    class Unplayable(val why: String) : RuntimeException(why)

    /** The scenes `tale scene` knows, by name: each plays one near the player and answers what it did in a sentence. */
    val scenes: LinkedHashMap<String, (ServerLevel, ServerPlayer) -> String> = LinkedHashMap()

    /** Adds the scene [name] (one a template has of its own, or one of the sets). */
    fun add(name: String, play: (ServerLevel, ServerPlayer) -> String) {
        scenes[name] = play
    }

    /** Appends the `scene` subcommand to `tale` (one line in [Tales.register]). */
    fun register() {
        if (scenes.isEmpty()) install()
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("scene").then(
                    Commands.argument("name", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(scenes.keys, builder) }
                        .executes { play(it.source, StringArgumentType.getString(it, "name")) },
                ),
            )
        }
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("figures")
                    .executes { figures(it.source, false) }
                    .then(Commands.literal("release").executes { figures(it.source, true) }),
            )
        }
    }

    /** `tale figures`: what Part B has made and held and is playing, one line each; with [release], all of it let go. */
    private fun figures(source: CommandSourceStack, release: Boolean): Int {
        val level = source.level
        if (release) {
            TaleLook.pause(level)
            TaleBodies.releaseAll(level)
            return TaleCommand.say(source, TaleCommand.grey("Every stage is over and every made figure and held resident let go; nothing is held or made again for a minute."))
        }
        TaleCommand.say(source, TaleCommand.header("Tales: the telling"))
        val bodies = TaleBodies.listing(level)
        val stages = TaleLook.stages()
        if (bodies.isEmpty() && stages.isEmpty()) return TaleCommand.say(source, TaleCommand.plain("No figures made, no residents held, no stage."))
        for (line in bodies) TaleCommand.say(source, TaleCommand.plain(line))
        for ((key, st) in stages) {
            val where = if (key == 0L) "by command" else "capital at ${BlockPos.of(key).toShortString()}"
            val state = if (st.playing) "playing" else "waiting"
            TaleCommand.say(source, TaleCommand.plain("stage ${st.name} ($state, ${st.parts.size} parts, $where)"))
        }
        return 1
    }

    private fun play(source: CommandSourceStack, name: String): Int {
        val player = source.playerOrException
        val scene = scenes[name] ?: return TaleCommand.fail(source, "No scene called $name: one of ${scenes.keys.joinToString(", ")}.")
        val said = try {
            scene(source.level, player)
        } catch (e: Unplayable) {
            return TaleCommand.fail(source, e.why)
        }
        return TaleCommand.say(source, TaleCommand.grey(said))
    }

    // ---- The scenes ----

    private fun install() {
        add("count", ::count)
        add("handing", ::handing)
        add("burning", ::burning)
        add("cheer", ::cheer)
        add("calling", ::calling)
        add("quarrel") { level, player -> quarrel(level, player, false) }
        add("moot") { level, player -> quarrel(level, player, true) }
        add("laying", ::laying)
        add("reunion", ::reunion)
        add("walk", ::walk)
    }

    /** Takes the parts of [stage] and plays it, noting it for `tale figures`; [said] is what the command answers. The street hears it, as it does a tale's. */
    internal fun begin(level: ServerLevel, stage: Stage?, said: String): String {
        if (stage == null || !stage.start(level)) throw Unplayable("Nobody free near enough for that, or nowhere for it here.")
        TaleLook.note(stage)
        TaleLook.hear(level, stage)
        return said
    }

    internal fun name(p: Townsperson): String = p.name.string

    private fun count(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0, station = true)
        val keeper = free.firstOrNull { it.seat != null || it.routine.anchored } ?: free.firstOrNull() ?: throw Unplayable("Nobody free within 24 blocks of you.")
        val passer = Sets.passerBy(keeper)
        val where = if (keeper.seat != null) "in their seat" else if (keeper.routine.anchored) "at their post" else "where they stand"
        return begin(
            level, Sets.count(keeper, passer, hours = null),
            "${name(keeper)} counts coins $where" + if (passer != null) "; ${name(passer)} comes to comfort them." else " (nobody else free to comfort them).",
        )
    }

    /** Two people for a handing: the taker the nearest worker at their post or sitter (else the nearest), the giver a free walker besides. */
    private fun pair(level: ServerLevel, player: ServerPlayer): Pair<Townsperson, Townsperson> {
        val walkers = Sets.freeAdults(level, player.position(), 24.0)
        val giver = walkers.firstOrNull() ?: throw Unplayable("Nobody free within 24 blocks of you.")
        val others = Sets.freeAdults(level, player.position(), 24.0, listOf(giver), station = true)
        val taker = others.firstOrNull { it.seat != null || it.routine.anchored } ?: others.firstOrNull() ?: throw Unplayable("Only one person free within 24 blocks of you: it needs two.")
        return giver to taker
    }

    private fun handing(level: ServerLevel, player: ServerPlayer): String {
        val (giver, taker) = pair(level, player)
        return begin(level, Sets.handing(giver, taker, Sets.prop("note_sealed"), hours = null), "${name(giver)} hands a sealed paper to ${name(taker)}.")
    }

    private fun burning(level: ServerLevel, player: ServerPlayer): String {
        val fire = Sets.fireNear(level, player.blockPosition(), 24) ?: throw Unplayable("No oven, stove or lit campfire within 24 blocks of you to burn it in.")
        val (giver, taker) = pair(level, player)
        val onlookers = Sets.freeAdults(level, player.position(), 24.0, listOf(giver, taker)).take(2)
        return begin(
            level, Sets.handing(giver, taker, Sets.prop("note_sealed"), fire, onlookers, hours = null),
            "${name(giver)} hands a sealed paper to ${name(taker)}, who burns it in the fire at ${fire.toShortString()}; ${onlookers.size} look on.",
        )
    }

    private fun cheer(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val toaster = free.firstOrNull() ?: throw Unplayable("Nobody free within 24 blocks of you.")
        val others = free.drop(1).take(4)
        val bell = Sets.bellNear(level, toaster.blockPosition())
        return begin(
            level, Sets.cheer(toaster, others, bell, hours = null),
            "${name(toaster)} raises a toast and ${others.size} cheer" + if (bell != null && others.isNotEmpty()) ", one at the bell." else ".",
        )
    }

    /** The flat direction [player] faces. */
    private fun facing(player: ServerPlayer): Vec3 {
        val look = player.lookAngle
        val flat = Vec3(look.x, 0.0, look.z)
        return if (flat.lengthSqr() < 0.01) Vec3(1.0, 0.0, 0.0) else flat.normalize()
    }

    /** A block to stand on [distance] blocks in front of [player], or the nearest to it. */
    private fun ahead(level: ServerLevel, player: ServerPlayer, distance: Double): BlockPos? {
        val dir = facing(player)
        return Sets.standable(level, BlockPos.containing(player.x + dir.x * distance, player.y, player.z + dir.z * distance))
    }

    private fun calling(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val caller = free.firstOrNull() ?: throw Unplayable("Nobody free within 24 blocks of you.")
        val at = ahead(level, player, 6.0) ?: throw Unplayable("Nowhere to stand six blocks ahead of you.")
        val helpers = free.drop(1).take(2)
        val dir = facing(player)
        val toward = Vec3(at.x + 0.5 + dir.x * 14.0, at.y + 1.0, at.z + 0.5 + dir.z * 14.0)
        return begin(
            level, Sets.calling(caller, "Wren", at, toward, helpers, hours = null),
            "${name(caller)} goes to ${at.toShortString()} and calls for Wren" + if (helpers.isNotEmpty()) "; ${helpers.size} hurry up to help." else " (nobody else free to help).",
        )
    }

    private fun quarrel(level: ServerLevel, player: ServerPlayer, crowd: Boolean): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val a = free.firstOrNull() ?: throw Unplayable("Nobody free within 24 blocks of you.")
        val rest = free.drop(1).sortedBy { it.distanceToSqr(a) }
        val b = rest.firstOrNull() ?: throw Unplayable("Only one person free within 24 blocks of you: it needs two.")
        val third = rest.getOrNull(1)
        val watchers = rest.drop(2).take(if (crowd) Stage.EXTRAS else 3)
        return begin(
            level, Sets.quarrel(a, b, third, watchers, toward = player.position().add(0.0, 1.0, 0.0), crowd = crowd, hours = null),
            "${name(a)} and ${name(b)} quarrel over you" + (if (third != null) "; ${name(third)} hushes them" else "") +
                (if (watchers.isNotEmpty()) "; ${watchers.size} look on." else "."),
        )
    }

    private fun laying(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val mourner = free.firstOrNull() ?: throw Unplayable("Nobody free within 24 blocks of you.")
        val stone = ahead(level, player, 3.0) ?: throw Unplayable("Nowhere to stand three blocks ahead of you for a stone.")
        val rest = free.drop(1)
        val priest = rest.firstOrNull { it.role == TownRole.PRIEST }
        val others = rest.filter { it !== priest }.take(3)
        val laid = { dusk: Boolean ->
            player.sendSystemMessage(TaleCommand.grey("The posy is laid at ${stone.toShortString()}" + if (dusk) ", and a lit lantern would be set beside it." else "."))
        }
        return begin(
            level, Sets.laying(mourner, stone, others, priest, onLaid = laid, hours = null),
            "${name(mourner)} lays a posy at the stone ${stone.toShortString()} (no block is set)" + (if (priest != null) ", ${name(priest)} praying" else "") +
                (if (others.isNotEmpty()) "; ${others.size} stand by." else "."),
        )
    }

    private fun reunion(level: ServerLevel, player: ServerPlayer): String {
        val free = Sets.freeAdults(level, player.position(), 24.0)
        val waiting = free.firstOrNull() ?: throw Unplayable("Nobody free within 24 blocks of you.")
        val rest = free.drop(1).sortedBy { it.distanceToSqr(waiting) }
        val returned = rest.firstOrNull() ?: throw Unplayable("Only one person free within 24 blocks of you: it needs two.")
        val watchers = rest.drop(1).take(3)
        return begin(
            level, Sets.reunion(waiting, returned, player, watchers, hours = null),
            "${name(waiting)} runs to ${name(returned)} and they embrace; ${watchers.size} look on, and you are thanked.",
        )
    }

    private fun walk(level: ServerLevel, player: ServerPlayer): String {
        val who = Sets.freeAdults(level, player.position(), 16.0).firstOrNull() ?: throw Unplayable("Nobody free within 16 blocks of you.")
        if (!TaleEscort.start(level, null, "walker", who, player, null, until = level.gameTime + 2400L)) throw Unplayable("${name(who)} cannot be asked to walk just now.")
        return "${name(who)} follows you at a stoop for two minutes, resting every thirty blocks or at a bench."
    }
}
