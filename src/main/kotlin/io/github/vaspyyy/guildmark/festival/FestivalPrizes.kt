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
    /** [place] 0 is the winner; [contest] is [COOK] for Sheafmoot's Harvest Table, else "" (the festival's own contest). */
    data class Award(val player: UUID, val kind: String, val place: Int, val marks: Int, val town: String, val year: Int, val contest: String = "")

    companion object {
        /** Guild marks for first, second and third, and for everyone else who entered. */
        val MARKS = intArrayOf(20, 10, 6)
        const val MARKS_ENTERED = 4
        /** The [Award.contest] of the Harvest Table's prizes (living-world/cook/design.md 6.8). */
        const val COOK = "cook"

        private val AWARD: Codec<Award> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Award::player),
                Codec.STRING.fieldOf("kind").forGetter(Award::kind),
                Codec.INT.fieldOf("place").forGetter(Award::place),
                Codec.INT.fieldOf("marks").forGetter(Award::marks),
                Codec.STRING.fieldOf("town").forGetter(Award::town),
                Codec.INT.fieldOf("year").forGetter(Award::year),
                // An award kept from before the Harvest Table is the festival's own
                Codec.STRING.optionalFieldOf("contest", "").forGetter(Award::contest),
            ).apply(i, ::Award)
        }

        private val CODEC: Codec<FestivalPrizes> = RecordCodecBuilder.create { i ->
            i.group(AWARD.listOf().fieldOf("pending").forGetter { it.pending })
                .apply(i) { list -> FestivalPrizes(list.toMutableList()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "festival_prizes"), Supplier { FestivalPrizes() }, CODEC)

        fun get(level: ServerLevel): FestivalPrizes = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /**
         * Hands out a festival's prizes, by its contest's [results] (best first); [contest] is [COOK] for the Harvest Table's,
         * whose town entries ([FestivalContest.Entry.npc]) take their places but are paid nothing, and whose news is only of a
         * player who won it.
         */
        fun award(level: ServerLevel, kind: FestivalKind, town: String, results: List<FestivalContest.Entry>, contest: String = "") {
            if (results.isEmpty()) return
            val year = Seasons.today(level).year
            val saved = get(level)
            results.forEachIndexed { place, entry ->
                if (entry.npc) return@forEachIndexed
                val award = Award(entry.id, kind.id, place, MARKS.getOrElse(place) { MARKS_ENTERED }, town, year, contest)
                val player = level.server.playerList.getPlayer(entry.id)
                if (player != null) give(player, award) else {
                    saved.pending.add(award)
                    saved.setDirty()
                }
            }
            if (results[0].npc) return
            val title = if (contest == COOK) "#${io.github.vaspyyy.guildmark.cooking.feast.HarvestTable.TITLE}" else "#festival.guildmark.${kind.id}"
            GuildNews.add(level, "news.guildmark.festival_won", results[0].name, title, town)
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

        /**
         * The winner's keepsake: "Won at the Ribbonwake in Ashford, in the year 3". The Harvest Table's ([contest] [COOK]) is the
         * Gilded Ladle: "Won at the Harvest Table in Ashford, Year 3".
         */
        fun keepsake(kind: FestivalKind, town: String, year: Int, contest: String = ""): ItemStack {
            val stack = ItemStack(prize(kind, contest))
            stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.festival_prize.won", title(kind, contest), town, year).withStyle(ChatFormatting.GRAY))))
            return stack
        }

        /** The prize of [kind]'s contest, or of the Harvest Table. */
        private fun prize(kind: FestivalKind, contest: String) = if (contest == COOK) FestivalRegistry.cookPrize() else FestivalRegistry.prize(kind)

        /** The name of the contest an award is for: the festival's own ("Sheafmoot"), or the Harvest Table. */
        private fun title(kind: FestivalKind, contest: String): Component =
            if (contest == COOK) Component.translatable(io.github.vaspyyy.guildmark.cooking.feast.HarvestTable.TITLE) else kind.title

        private fun give(player: ServerPlayer, award: Award) {
            val kind = FestivalKind.byId(award.kind) ?: return
            if (award.place == 0) player.inventory.placeItemBackInInventory(keepsake(kind, award.town, award.year, award.contest), Prediction.SERVER_ONLY)
            if (award.marks > 0) player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), award.marks), Prediction.SERVER_ONLY)
            val key = when (award.place) {
                0 -> "message.guildmark.festival.prize.first"
                1 -> "message.guildmark.festival.prize.second"
                2 -> "message.guildmark.festival.prize.third"
                else -> "message.guildmark.festival.prize.entered"
            }
            player.sendSystemMessage(Component.translatable(key, title(kind, award.contest), award.town, award.marks, ItemStack(prize(kind, award.contest)).hoverName).withColor(kind.color))
            player.level().playSound(null, player.blockPosition(), if (award.place == 0) SoundEvents.PLAYER_LEVELUP else SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f)
        }
    }
}
