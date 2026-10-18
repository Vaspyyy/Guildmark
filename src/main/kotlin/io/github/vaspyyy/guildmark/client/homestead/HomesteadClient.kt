package io.github.vaspyyy.guildmark.client.homestead

import io.github.vaspyyy.guildmark.homestead.ClaimVerdictPayload
import io.github.vaspyyy.guildmark.homestead.HomesteadSyncPayload
import io.github.vaspyyy.guildmark.homestead.LedgerPayload

/** What the client knows of homesteads. */
object HomesteadClient {
    var homesteads: List<HomesteadSyncPayload.Entry> = listOf()
        private set

    fun onSync(payload: HomesteadSyncPayload) {
        homesteads = payload.entries
    }

    fun onVerdict(payload: ClaimVerdictPayload) {
    }

    fun onLedger(payload: LedgerPayload) {
    }
}
