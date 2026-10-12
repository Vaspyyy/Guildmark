package io.github.vaspyyy.guildmark.wardfire

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModParticles
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.netty.buffer.ByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/** A player's Warden's Flask: whether they've been given one, and how many draughts are left in it. */
data class FlaskState(val owned: Boolean = false, val charges: Int = 0, val max: Int = BASE_CHARGES) {
    companion object {
        const val BASE_CHARGES = 3

        val CODEC: Codec<FlaskState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.BOOL.optionalFieldOf("owned", false).forGetter(FlaskState::owned),
                Codec.INT.optionalFieldOf("charges", 0).forGetter(FlaskState::charges),
                Codec.INT.optionalFieldOf("max", BASE_CHARGES).forGetter(FlaskState::max),
            ).apply(i, ::FlaskState)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, FlaskState> = StreamCodec.composite(
            ByteBufCodecs.BOOL, FlaskState::owned,
            ByteBufCodecs.VAR_INT, FlaskState::charges,
            ByteBufCodecs.VAR_INT, FlaskState::max,
            ::FlaskState,
        )
    }
}

/**
 * The Warden's Flask, handed to you at the first Wardfire you kindle. Its draughts belong to you rather
 * than to the bottle: drink from the bottle in hand or with the flask key, and a Wardfire (or waking
 * after a fall) fills it again.
 */
object WardensFlask {
    /** Share of the drinker's greatest health one draught gives back. */
    const val HEAL = 0.4f

    fun get(player: Player): FlaskState = player.getData(ModAttachments.FLASK)

    /** The first Wardfire you kindle gives you the flask, full. */
    fun onKindle(player: ServerPlayer) {
        if (get(player).owned) return
        player.setData(ModAttachments.FLASK, FlaskState(true, FlaskState.BASE_CHARGES, FlaskState.BASE_CHARGES))
        player.inventory.placeItemBackInInventory(ItemStack(ModItems.WARDENS_FLASK.get()), Prediction.SERVER_ONLY)
        player.sendSystemMessage(Component.translatable("message.guildmark.flask.received").withColor(GOLD))
    }

    fun refill(player: Player) {
        val flask = get(player)
        if (flask.owned && flask.charges < flask.max) player.setData(ModAttachments.FLASK, flask.copy(charges = flask.max))
    }

    /** One draught: heals [player] and spends a charge. False when there's nothing left to drink. */
    fun drink(player: ServerPlayer): Boolean {
        val flask = get(player)
        if (!flask.owned || flask.charges <= 0 || !player.isAlive) return false
        player.setData(ModAttachments.FLASK, flask.copy(charges = flask.charges - 1))
        player.heal(player.maxHealth * HEAL)
        val level = player.level()
        level.playSound(null, player.x, player.y, player.z, ModSounds.FLASK_DRINK.get(), SoundSource.PLAYERS, 0.9f, 0.95f + player.random.nextFloat() * 0.1f)
        level.sendParticles(ModParticles.WARD_EMBER.get(), player.x, player.y + 1.0, player.z, 16, 0.35, 0.5, 0.35, 0.02)
        return true
    }

    fun empty(player: ServerPlayer) {
        player.sendOverlayMessage(Component.translatable("message.guildmark.flask.empty").withColor(RED))
    }

    private const val GOLD = 0xE8C26A
    private const val RED = 0xE05A4A
}
