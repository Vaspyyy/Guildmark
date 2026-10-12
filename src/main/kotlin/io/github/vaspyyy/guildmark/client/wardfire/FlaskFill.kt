package io.github.vaspyyy.guildmark.client.wardfire

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.ItemOwner
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * How full the Warden's Flask looks, from 0 to 1: the draughts its holder has left. Only your own
 * draughts are known on your side, so anyone else's flask, or one lying on the ground, looks full.
 */
object FlaskFill : RangeSelectItemModelProperty {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "flask_fill")
    val MAP_CODEC: MapCodec<FlaskFill> = MapCodec.unit(this)

    override fun get(itemStack: ItemStack, level: ClientLevel?, owner: ItemOwner?, seed: Int): Float {
        val player = owner?.asLivingEntity() as? Player ?: return 1f
        val flask = player.getExistingDataOrNull(ModAttachments.FLASK) ?: return 1f
        if (!flask.owned || flask.max <= 0) return 1f
        return flask.charges.toFloat() / flask.max
    }

    override fun type(): MapCodec<FlaskFill> = MAP_CODEC
}
