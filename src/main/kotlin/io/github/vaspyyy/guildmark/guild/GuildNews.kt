package io.github.vaspyyy.guildmark.guild

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.netty.buffer.ByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier

/** One line of guild news: a translation key and its arguments, and the day it happened. */
data class NewsItem(val day: Long, val key: String, val args: List<String>) {
    /** Arguments starting with # are translation keys themselves, so they show in the reader's language. */
    fun text(): Component = Component.translatable(key, *args.map { arg ->
        if (arg.startsWith("#")) Component.translatable(arg.drop(1)) else Component.literal(arg)
    }.toTypedArray())

    companion object {
        val CODEC: Codec<NewsItem> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("day").forGetter(NewsItem::day),
                Codec.STRING.fieldOf("key").forGetter(NewsItem::key),
                Codec.STRING.listOf().fieldOf("args").forGetter(NewsItem::args),
            ).apply(i, ::NewsItem)
        }
        val STREAM_CODEC: StreamCodec<ByteBuf, NewsItem> = ByteBufCodecs.fromCodec(CODEC)
    }
}

/**
 * What the guild halls are talking about: promotions, finished advances and roads, beaten bandits.
 * One list for the whole world, kept in the overworld's saved data, newest first.
 */
class GuildNews(val items: MutableList<NewsItem> = mutableListOf()) : SavedData() {
    companion object {
        private const val KEEP = 8

        private val CODEC: Codec<GuildNews> = RecordCodecBuilder.create { i ->
            i.group(NewsItem.CODEC.listOf().fieldOf("items").forGetter { it.items })
                .apply(i) { items -> GuildNews(items.toMutableList()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "news"), Supplier { GuildNews() }, CODEC)

        fun get(level: ServerLevel): GuildNews = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        fun add(level: Level, key: String, vararg args: String) {
            val serverLevel = level as? ServerLevel ?: return
            val news = get(serverLevel)
            news.items.add(0, NewsItem(VillageBoards.day(serverLevel), key, args.toList()))
            while (news.items.size > KEEP) news.items.removeLast()
            news.setDirty()
        }
    }
}
