package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.network.chat.Component

/**
 * A line a townsperson says, as it goes to the clients ([Townsperson.speech]): its key under say.guildmark., then
 * whatever it names, each after a "|": a number as it is, anything else a lang key ("market.cleared|good.guildmark.carrot.many"
 * says "Carrots, all gone!"). Most lines name nothing and are just their key.
 */
object Speech {
    /** The line [key] naming [args] (numbers, or lang keys). */
    fun of(key: String, vararg args: Any): String = if (args.isEmpty()) key else key + args.joinToString("") { "|$it" }

    /** The key of [line], which its voice clip is named after. */
    fun key(line: String): String = line.substringBefore('|')

    /** What [line] says. */
    fun text(line: String): Component {
        if ('|' !in line) return Component.translatable("say.guildmark.$line")
        val parts = line.split('|')
        val args = parts.drop(1).map { arg -> if (arg.toIntOrNull() != null) Component.literal(arg) else Component.translatable(arg) }
        return Component.translatable("say.guildmark." + parts[0], *args.toTypedArray())
    }
}
