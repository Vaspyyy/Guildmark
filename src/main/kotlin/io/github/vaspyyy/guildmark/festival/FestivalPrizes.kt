package io.github.vaspyyy.guildmark.festival

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.ChatFormatting
import net.minecraft.core.UUIDUtil
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/**
 * The festival prizes: the winner's keepsake (its lore says where and when it was won) and guild marks for every
 * place. Anyone who entered but has gone by the prize-giving gets theirs the next time they come on.
 */
class FestivalPrizes(val pending: MutableList<Award> = mutableListOf()) : SavedData() {
    /** [place] 0 is the winner. */
    data class Award(val player: UUID, val kind: String, val place: Int, val marks: Int, val town: String, val year: Int)

    companion object {
        /** Guild marks for first, second and third, and for everyone else who entered. */
        val MARKS = intArrayOf(20, 10, 6)
        const val MARKS_ENTERED = 4

        private val AWARD: Codec<Award> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Award::player),
                Codec.STRING.fieldOf("kind").forGetter(Award::kind),
                Codec.INT.fieldOf("place").forGetter(Award::place),
                Codec.INT.fieldOf("marks").forGetter(Award::marks),
                Codec.STRING.fieldOf("town").forGetter(Award::town),
                Codec.INT.fieldOf("year").forGetter(Award::year),
            ).apply(i, ::Award)
        }

        private val CODEC: Codec<FestivalPrizes> = RecordCodecBuilder.create { i ->
            i.group(AWARD.listOf().fieldOf("pending").forGetter { it.pending })
                .apply(i) { list -> FestivalPrizes(list.toMutableList()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "festival_prizes"), Supplier { FestivalPrizes() }, CODEC)

        fun get(level: ServerLevel): FestivalPrizes = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Hands out a festival's prizes, by its contest's [results] (best first). */
        fun award(level: ServerLevel, kind: FestivalKind, town: String, results: List<FestivalContest.Entry>) {
            if (results.isEmpty()) return
            val year = Seasons.today(level).year
            val saved = get(level)
            results.forEachIndexed { place, entry ->
                val award = Award(entry.id, kind.id, place, MARKS.getOrElse(place) { MARKS_ENTERED }, town, year)
                val player = level.server.playerList.getPlayer(entry.id)
                if (player != null) give(player, award) else {
                    saved.pending.add(award)
                    saved.setDirty()
                }
            }
            GuildNews.add(level, "news.guildmark.festival_won", results[0].name, "#festival.guildmark.${kind.id}", town)
        }

        /** Gives [player] any prizes kept for them. */
        fun deliver(level: ServerLevel, player: ServerPlayer) {
            val saved = get(level)
            val mine = saved.pending.filter { it.player == player.uuid }
            if (mine.isEmpty()) return
            saved.pending.removeAll(mine)
            saved.setDirty()
            for (award in mine) give(player, award)
        }

        /** The winner's keepsake: "Won at the Ribbonwake in Ashford, in the year 3". */
        fun keepsake(kind: FestivalKind, town: String, year: Int): ItemStack {
            val stack = ItemStack(FestivalRegistry.prize(kind))
            stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.festival_prize.won", kind.title, town, year).withStyle(ChatFormatting.GRAY))))
            return stack
        }

        private fun give(player: ServerPlayer, award: Award) {
            val kind = FestivalKind.byId(award.kind) ?: return
            if (award.place == 0) player.inventory.placeItemBackInInventory(keepsake(kind, award.town, award.year), Prediction.SERVER_ONLY)
            if (award.marks > 0) player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), award.marks), Prediction.SERVER_ONLY)
            val key = when (award.place) {
                0 -> "message.guildmark.festival.prize.first"
                1 -> "message.guildmark.festival.prize.second"
                2 -> "message.guildmark.festival.prize.third"
                else -> "message.guildmark.festival.prize.entered"
            }
            player.sendSystemMessage(Component.translatable(key, kind.title, award.town, award.marks, ItemStack(FestivalRegistry.prize(kind)).hoverName).withColor(kind.color))
            player.level().playSound(null, player.blockPosition(), if (award.place == 0) SoundEvents.PLAYER_LEVELUP else SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f)
        }
    }
}
