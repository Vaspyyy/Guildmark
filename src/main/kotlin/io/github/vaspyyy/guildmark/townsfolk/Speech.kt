package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * A line a townsperson says, as it goes to the clients ([Townsperson.speech]): its key under say.guildmark., then
 * whatever it names, each after a "|": a number as it is, a name as it's written after a "=", anything else a lang key
 * ("market.cleared|good.guildmark.carrot.many" says "Carrots, all gone!"). Most lines name nothing and are just their key.
 *
 * A talk line (living-world/design-voices.md section 5) may carry a detail after a "~", in the same form, that's shown
 * but never voiced: "talk.market.dear_0~talk.detail.price|item.minecraft.bread|3" says "Everything's dear this week."
 * and then, in grey, "Bread: 3 Marks.". A talk line that names something shows its .named text, the names filled in
 * ("talk.greet.friend_0|=Ransom"), since names can't be voiced; a detail said as a line of its own (talk.detail.*) shows as
 * it is.
 */
object Speech {
    /** The line [key] naming [args] (numbers, names as "=Name", or lang keys). */
    fun of(key: String, vararg args: Any): String = if (args.isEmpty()) key else key + args.joinToString("") { "|$it" }

    /** [line] with [detail] (a line of the same form, or null for none) after it, unvoiced. */
    fun withDetail(line: String, detail: String?): String = if (detail.isNullOrEmpty()) line else "$line~$detail"

    /** The key of [line], which its voice clip is named after. */
    fun key(line: String): String = line.substringBefore('~').substringBefore('|')

    /** What [line] says, its detail after it in grey. */
    fun text(line: String): Component {
        val said = said(line)
        val detail = detail(line) ?: return said
        return Component.empty().append(said).append(" ").append(detail.withStyle(ChatFormatting.GRAY))
    }

    /** What [line] says, without its detail. */
    fun said(line: String): MutableComponent {
        val main = line.substringBefore('~')
        if ('|' !in main) return Component.translatable("say.guildmark.$main")
        val parts = main.split('|')
        val key = if (parts[0].startsWith("talk.") && !parts[0].startsWith("talk.detail.")) parts[0] + ".named" else parts[0]
        return Component.translatable("say.guildmark.$key", *args(parts))
    }

    /** The detail [line] carries, or null. */
    fun detail(line: String): MutableComponent? {
        val cut = line.indexOf('~')
        if (cut < 0 || cut == line.length - 1) return null
        val parts = line.substring(cut + 1).split('|')
        return Component.translatable("say.guildmark." + parts[0], *args(parts))
    }

    private fun args(parts: List<String>): Array<Component> = parts.drop(1).map { arg ->
        when {
            arg.startsWith("=") -> Component.literal(arg.substring(1))
            arg.toIntOrNull() != null -> Component.literal(arg)
            else -> Component.translatable(arg)
        }
    }.toTypedArray()
}
