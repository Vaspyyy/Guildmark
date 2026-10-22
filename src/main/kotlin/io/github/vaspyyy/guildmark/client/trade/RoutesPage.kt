package io.github.vaspyyy.guildmark.client.trade

class RoutesPage : TallyPage("routes") {
    override fun left(c: Ctx) {
        c.pen.title(Words.t("page.routes"), 9, 8, 141)
    }

    override fun right(c: Ctx) {
    }
}
