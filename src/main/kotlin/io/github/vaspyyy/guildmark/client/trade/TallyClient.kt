package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyView
import net.minecraft.client.Minecraft

/** The client's side of the Tally: the last view the server sent, and the screen it opens or brings up to date. */
object TallyClient {
    @Volatile
    var view: TallyView? = null
        private set

    /** The server sent [view]: [open] opens the book (or, if it is open, brings it up to date); else it is an answer to the open book's own question and only updates it. */
    fun receive(view: TallyView, open: Boolean) {
        this.view = view
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.gui.screen() as? TallyScreen
        if (screen != null) {
            screen.update(view)
        } else if (open) {
            minecraft.gui.setScreen(TallyScreen(view))
        }
    }
}
