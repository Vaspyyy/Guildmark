package io.github.vaspyyy.guildmark.client.trade

class LedgerPage : TallyPage("ledger") {
    override fun left(c: Ctx) {
        c.pen.title(Words.t("page.ledger"), 9, 8, 141)
    }

    override fun right(c: Ctx) {
    }
}
