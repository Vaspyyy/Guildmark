package io.github.vaspyyy.guildmark.client.trade

class WordPage : TallyPage("word") {
    override fun left(c: Ctx) {
        c.pen.title(Words.t("page.word"), 9, 8, 141)
    }

    override fun right(c: Ctx) {
    }
}
