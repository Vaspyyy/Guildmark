package io.github.vaspyyy.guildmark.quest

import net.minecraft.network.chat.Component

/**
 * Text kept as a plain string on notes and contracts (a name, a flavour line): as it is, or, marked with "#", a lang
 * key with its arguments after it split by "|", each of those a "#" key itself or plain words. So
 * "#outlaw.guildmark.full|#outlaw.guildmark.name.3|#outlaw.guildmark.epithet.7" reads "Mad Jenna, the Grey Ghost" in
 * whatever language the reader has.
 */
object Labels {
    fun text(label: String): Component {
        if (!label.startsWith("#")) return Component.literal(label)
        val parts = label.drop(1).split('|')
        val args = parts.drop(1).map<String, Any> { if (it.startsWith("#")) Component.translatable(it.drop(1)) else it }
        return Component.translatable(parts[0], *args.toTypedArray())
    }
}
