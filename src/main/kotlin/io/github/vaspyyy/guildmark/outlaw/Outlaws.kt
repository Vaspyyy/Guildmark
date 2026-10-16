package io.github.vaspyyy.guildmark.outlaw

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent

/**
 * Outlaw bands camped out in the wild (see OutlawCamps): their captains' names, what's on their heads, and the
 * hooks that keep their camps going.
 */
object Outlaws {
    const val NAME_KEY = "outlaw.guildmark.name"
    const val EPITHET_KEY = "outlaw.guildmark.epithet"
    /** "%s, %s": a captain's name and epithet together. */
    const val FULL = "outlaw.guildmark.full"
    /** The land a band's in when it has no name yet. */
    const val WILDS = "outlaw.guildmark.wilds"
    /** How many names and epithets the lang file has. */
    const val NAMES = 24
    const val EPITHETS = 20

    /** A captain's name and epithet, as "Red Maud, the Lantern Thief". */
    fun title(name: Int, epithet: Int): Component =
        Component.translatable(FULL, Component.translatable("$NAME_KEY.$name"), Component.translatable("$EPITHET_KEY.$epithet"))

    /** The same as notes and contracts keep it: "#" and the key, its arguments after "|" (see quest/Labels). */
    fun label(name: Int, epithet: Int): String = "#$FULL|#$NAME_KEY.$name|#$EPITHET_KEY.$epithet"

    /** What a captain's head is worth when their band first makes camp: more the further out it is. */
    fun baseBounty(tier: Int): Int = 30 + 15 * tier

    fun enabled(): Boolean = try {
        GuildmarkConfig.OUTLAWS.get()
    } catch (e: IllegalStateException) {
        true
    }

    fun register() {
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (level.dimension() == Level.OVERWORLD && level.gameTime % 10L == 0L) OutlawCamps.get(level).tick(level)
        }
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java) { event ->
            val victim = event.entity
            val level = victim.level() as? ServerLevel ?: return@addListener
            if (victim.hasData(ModAttachments.OUTLAW)) OutlawCamps.get(level).onDeath(level, victim)
        }
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post::class.java) { event ->
            val victim = event.entity
            val level = victim.level() as? ServerLevel ?: return@addListener
            if (victim.hasData(ModAttachments.OUTLAW) && victim.isAlive) OutlawCamps.get(level).onHurt(level, victim, event.source)
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { OutlawCommand.register(it.dispatcher) }
    }
}
