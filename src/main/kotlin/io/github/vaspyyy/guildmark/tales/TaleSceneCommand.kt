package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.arguments.StringArgumentType
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

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
    }

    /** Takes the parts of [stage] and plays it, noting it for `tale figures`; [said] is what the command answers. */
    internal fun begin(level: ServerLevel, stage: Stage?, said: String): String {
        if (stage == null || !stage.start(level)) throw Unplayable("Nobody free near enough for that, or nowhere for it here.")
        TaleLook.note(stage)
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
}
