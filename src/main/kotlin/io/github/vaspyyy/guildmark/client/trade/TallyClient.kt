package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyView

/** The client's side of the Tally: the last view the server sent. (The screen comes with the pages.) */
object TallyClient {
    @Volatile
    var view: TallyView? = null
        private set

    fun receive(view: TallyView, open: Boolean) {
        this.view = view
    }
}
