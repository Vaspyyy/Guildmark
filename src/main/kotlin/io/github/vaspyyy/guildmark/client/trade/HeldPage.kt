package io.github.vaspyyy.guildmark.client.trade

class HeldPage : TallyPage("held") {
    override fun left(c: Ctx) {
        c.pen.title(Words.t("page.held"), 9, 8, 141)
    }

    override fun right(c: Ctx) {
    }
}
