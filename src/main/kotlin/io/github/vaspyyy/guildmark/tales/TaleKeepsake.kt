package io.github.vaspyyy.guildmark.tales

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.season.Season
import net.minecraft.core.component.DataComponents
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/**
 * What a tale's keepsake remembers (living-world/stories/design.md 7.6): which tale it was and how it ended, the town and the
 * day, the season and the year if the calendar is on, the names of the cast and the lead. It is the one data component of the one
 * item `guildmark:keepsake`; every field has a default, so a keepsake made by a later build still reads here, and one with
 * nothing in it reads as a plain keepsake. It is a memento: it grants nothing, no shop buys it (it is in no `Good`) and it has no
 * recipe (7.5 rule 7).
 *
 * [word] is the ending's word ("paid", "time"), which the lore's key is made of (`item.guildmark.keepsake.<template>.<word>`);
 * [names] are the first two names of [TaleWords.names] and [lead] the lead's, in the order the sentences read them
 * (`%1$s`, `%2$s`, `%3$s`). [day] is the market day it ended; [season] a season's id ("winter") and [year] from 1, or "" and 0
 * when the calendar is off.
 */
data class TaleKeepsake(
    val template: String = "",
    val ending: String = "",
    val word: String = "",
    val town: String = "",
    val day: Long = 0L,
    val season: String = "",
    val year: Int = 0,
    val names: List<String> = listOf(),
    val lead: String = "",
) {
    /** The picture it is drawn with (`items/keepsake.json`): the template's own, or its lesser one if C has one drawn. */
    val look: String get() = TaleArt.keepsake(template, ending)

    companion object {
        val CODEC: Codec<TaleKeepsake> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.optionalFieldOf("template", "").forGetter(TaleKeepsake::template),
                Codec.STRING.optionalFieldOf("ending", "").forGetter(TaleKeepsake::ending),
                Codec.STRING.optionalFieldOf("word", "").forGetter(TaleKeepsake::word),
                Codec.STRING.optionalFieldOf("town", "").forGetter(TaleKeepsake::town),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(TaleKeepsake::day),
                Codec.STRING.optionalFieldOf("season", "").forGetter(TaleKeepsake::season),
                Codec.INT.optionalFieldOf("year", 0).forGetter(TaleKeepsake::year),
                Codec.STRING.listOf().optionalFieldOf("names", listOf()).forGetter(TaleKeepsake::names),
                Codec.STRING.optionalFieldOf("lead", "").forGetter(TaleKeepsake::lead),
            ).apply(i) { template, ending, word, town, day, season, year, names, lead ->
                TaleKeepsake(template, ending, word, town, day, season, year, names, lead)
            }
        }

        /** A stack of the keepsake [k], drawn as its look says (a look that is not drawn shows as the contract does). */
        fun stack(k: TaleKeepsake): ItemStack = ItemStack(ModItems.KEEPSAKE.get()).also {
            it.set(ModDataComponents.TALE_KEEPSAKE.get(), k)
            it.set(DataComponents.CUSTOM_MODEL_DATA, TaleArt.model(k.look))
        }
    }
}

/**
 * The keepsake (design 7.6, 13.2.2): one item for all sixteen looks, as the heist prize is (`faction/FactionItems.kt`). Its
 * name is the look's (`item.guildmark.keepsake.<look>`, "Pressed Posy") and its lore two lines: the story of the ending
 * (`item.guildmark.keepsake.<template>.<word>`, said with the names) and where and when. A keepsake of a tale the language
 * file has no line for (the probe) reads as "Keepsake" with one plain line. Stack size 1.
 */
class KeepsakeItem(properties: Properties) : Item(properties) {
    /** The look's name when the look is one of the sixteen drawn (named in the language file, which the server need not hold), else "Keepsake". */
    override fun getName(stack: ItemStack): Component {
        val look = stack.get(ModDataComponents.TALE_KEEPSAKE.get())?.look
        return if (look != null && look in TaleArt.KEEPSAKES) Component.translatable("item.guildmark.keepsake.$look") else super.getName(stack)
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        val k = stack.get(ModDataComponents.TALE_KEEPSAKE.get())
        val key = k?.let { "item.guildmark.keepsake.${it.template}.${it.word}" }
        val story = if (k != null && key != null && Language.getInstance().has(key)) {
            Component.translatable(key, k.names.getOrElse(0) { "" }, k.names.getOrElse(1) { "" }, k.lead)
        } else {
            Component.translatable("item.guildmark.keepsake.lore")
        }
        builder.accept(story.withStyle { it.withColor(GREY).withItalic(true) })
        where(k)?.let { builder.accept(it.withStyle { s -> s.withColor(GREY) }) }
    }

    /** "Harrowmere, Winter of year 3.": the town and the season it was, whichever the keepsake knows; null if it knows neither. */
    private fun where(k: TaleKeepsake?): MutableComponent? {
        if (k == null) return null
        val season = Season.byId(k.season)?.takeIf { k.year > 0 }
        return when {
            k.town.isNotEmpty() && season != null -> Component.translatable("item.guildmark.keepsake.where", k.town, season.title, k.year)
            k.town.isNotEmpty() -> Component.translatable("item.guildmark.keepsake.where.town", k.town)
            season != null -> Component.translatable("item.guildmark.keepsake.where.when", season.title, k.year)
            else -> null
        }
    }

    private companion object {
        const val GREY = 0x8A8A8A
    }
}
