package io.github.vaspyyy.guildmark.trade.hunt

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * What the Hunt's people say and sound like (living-world/trader/design.md, Part 4, "Art, sounds, voices"). The outlaws are vanilla illagers
 * and so have no townsperson's speech; the recorded lines of X6 (`townsfolk.voice.<man|woman>.<key>`) are played for them by the server, as
 * a fixed-range sound event whose files the client finds in `sounds.json`, and the words go to the player they are said to as the action bar
 * (the same `say.guildmark.<key>` a townsperson's bubble shows). A body keeps one voice and one pitch for good, from its own id.
 */
internal object HuntVoice {
    /** How far a spoken line carries, and how far the cry of a rout does. */
    const val REACH = 32f
    const val CRY = 24f

    private val events = HashMap<String, SoundEvent>()

    /** Whether [who] speaks in the woman's voice: the same each time for the same body. */
    fun woman(who: Entity): Boolean = (who.uuid.mostSignificantBits ushr 7) and 1L == 1L

    /** The pitch of [who]'s voice: everyone a little different, as the townsfolk's are. */
    fun pitch(who: Entity): Float = 0.92f + Math.floorMod(who.uuid.leastSignificantBits, 17L) / 100f

    /**
     * [who] says the line [key] ([key] a voiced key such as `trade_hold_1`): heard by anyone within [reach], and its words shown on the action
     * bar of each of [to] who is within that distance of them.
     */
    fun say(level: ServerLevel, who: Entity, key: String, to: List<ServerPlayer>, reach: Float = REACH) {
        val name = "townsfolk.voice.${if (woman(who)) "woman" else "man"}.$key"
        val event = events.getOrPut("$name@$reach") { SoundEvent.createFixedRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name), reach) }
        level.playSound(null, who.x, who.y + 1.6, who.z, event, SoundSource.HOSTILE, 1f, pitch(who))
        val words = Speech.text(key)
        for (player in to) if (player.distanceToSqr(who) <= reach * reach) player.sendOverlayMessage(words)
    }

    /** One of [count] takes of the line [family] (`trade_hold` of 3): `trade_hold_0` to `trade_hold_2`, picked by [level]'s dice. */
    fun pick(level: ServerLevel, family: String, count: Int): String = "${family}_${level.random.nextInt(count)}"

    /** The trade's sound [name] (`trade.hunt_shout`...) played at [at], the same for everyone near; silent until its files land. */
    fun sound(level: ServerLevel, at: Vec3, name: String, volume: Float = 1f, pitch: Float = 1f, source: SoundSource = SoundSource.HOSTILE) {
        level.playSound(null, at.x, at.y, at.z, TradeSounds.get(name), source, volume, pitch)
    }

    /** The clink of coin that is not the player's (the street thieves' own). */
    fun clink(level: ServerLevel, at: Vec3, pitch: Float = 1f) {
        val event = events.getOrPut("coin") { SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.thief.coin")) }
        level.playSound(null, at.x, at.y, at.z, event, SoundSource.HOSTILE, 0.7f, pitch)
    }

    /** Forgets the sound events made (as the server stops). */
    fun forget() {
        events.clear()
    }
}
