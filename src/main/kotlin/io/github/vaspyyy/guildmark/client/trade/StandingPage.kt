package io.github.vaspyyy.guildmark.client.trade

class StandingPage : TallyPage("standing") {
    override fun left(c: Ctx) {
        c.pen.title(Words.t("page.standing"), 9, 8, 141)
    }

    override fun right(c: Ctx) {
    }
}
