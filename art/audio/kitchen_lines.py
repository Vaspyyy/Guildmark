#!/usr/bin/env python3
"""The kitchen's talk families (Hearthcraft, living-world/cook/design.md sections 8.5 and 11.5): what townsfolk, keepers,
judges and children say of the kitchen, as families of wordings for make_talk_voices.py's table. A module of its own, as
the build plan (living-world/build-plan.md section 6) asks of every design's voices: it imports nothing from the script,
takes the script's timbre sets and length limit as arguments, and make_talk_voices.py enters it by one block before its
`class Line` (`GROUPS += kitchen_lines.groups(MWO, CHILD, TALK)`, and its four tables updated from DELIVERY, SPEED_FOR,
SAY_AS and PHONEMES below), and only for a tier whose takes exist: `table` refuses a family with no take, so an
unrecorded group may not be in GROUPS.

  python3 -B art/audio/kitchen_lines.py check    the rules below; says what is wrong with which family (exit 1 if any)
  python3 -B art/audio/kitchen_lines.py write    writes art/lang/cook_lines.json and KitchenWords.kt, only if they changed
  python3 -B art/audio/kitchen_lines.py list     the groups, their tier and their families

A family is one meaning said two to four ways, each a whole sentence that is true whenever the family is picked. Whatever
changes (a name, a dish, a number of portions or Marks, a role, a time) is never voiced: it follows the take as grey
text, which the kitchen's own talk sources write (say.guildmark.talk.detail.kitchen.*). So no wording names a person, a
dish, a number or a role, with the few exceptions CHECK_ALLOWS lists, where the family itself is the dish's or the role's
(the rest-day roast, a style's liked dish). The takes are talk.<family>_<n>, n counted from 0; the man, the woman and the
old man say every family but the children's.

NOTHING IS RECORDED YET. RECORDED is empty, so groups() returns nothing and the script's `table` runs as it did. The
game reads every wording in full as text all the same: `write` puts the text of every wording of every tier in
art/lang/cook_lines.json (say.guildmark.talk.<family>_<n>, whatever RECORDED says) and the number of wordings of every
family in KitchenWords.kt, which is how townsfolk/talk/sources/KitchenLines.kt picks a wording to read while a family has
no take (design 8.5). To record a tier: make its takes with make_talk_voices.py (tts, process), put the tier's number in
RECORDED in the same commit as its takes and the regenerated talk_sounds.json, talk_lang.json and TalkLines.kt (`table`),
then run `merge_lang.py`. The text of a recorded wording is the same in cook_lines.json and talk_lang.json, so the two
merge as one.

Tiers (design 11.5): TIER 1 is the cook's own loop, the pot, oven and spit and the back door (the first two groups: 23
families, 67 wordings); TIER 2 is the rest, what the town says, the onlookers, the Harvest Table, the shop and the
children (33 families, 77 wordings).

Wording changes from the design's 11.5 list (every one so that a wording is true whenever its family is picked; see the
notes in the table): the scene's "Not the back pot again!" and "Hot off the spit", the verdicts' "Good, but", the plain
tier's "Warm", the hallmark tier's "in years" (a cook who sells a Hallmarked batch every day is not the best in years each
day), "bowl", "a day or two old", "comes out of an oven", "whenever you like" of the back pot, the slate's "short of" (the
wanted kinds are a draw, not a want) and the request's "in number", the autumn's "The harvest is in", the palates (they
name the two dishes each style likes, Tastes.LIKES), the relished "other night" and fedwell's "supper" and "last night"
(the meal may be any meal), the chilled "Cold night" and "bowl", the told rumours' "at the inn" (a Hallmarked dish may be
sold at the bakery) and "nobody owned up" and "new cook", the shop's "Fresh from the oven", "hot" and the cookshop's first
plate, "I've never seen one", "good food" of a burnt pot, and "what the cooks have brought" of the Harvest Table.
"""
import json
import os
import re
import sys

sys.dont_write_bytecode = True

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LANG_OUT = os.path.join(REPO, "art", "lang", "cook_lines.json")
KOTLIN_OUT = os.path.join(REPO, "src", "main", "kotlin", "io", "github", "vaspyyy", "guildmark", "townsfolk", "talk",
                          "sources", "KitchenWords.kt")

# The tiers whose takes exist: make_talk_voices.py's `table` needs every family in GROUPS to have all of its takes, so a
# tier goes in here in the commit that adds its takes and nothing before. Empty: nothing is recorded.
RECORDED = []

# The length limit the script gives talk (seconds); a take is about 12 ticks + 0.97 ticks a character long (the fit of the
# 692 wordings already recorded), the longest of them 66 characters; the limit is held to 64 characters and 12 words
LONGEST_CHARS = 64
LONGEST_WORDS = 12

# (group, the group's timbre set, its tier, [(family, who says it and when, [wordings])]), in the order the design lists
# them. The timbre sets are "MWO" (man, woman and old) and "CHILD", as make_talk_voices.py names them.
TABLE = [
    ("The kitchen: at the pot, the oven and the spit", "MWO", 1, [
        # the innkeeper tastes her pot and it wants salt (one scene time in two, at the taste)
        ("kitchen.scene.salt", "the innkeeper, tasting her pot, when it wants salt", [
            "Needs a pinch of salt.",
            "A touch more salt, I think.",
        ]),
        # ... and it is right
        ("kitchen.scene.right", "the innkeeper, tasting her pot, when it is right", [
            "Just as it should be.",
            "Mm. That's the way.",
            "There. Perfect.",
        ]),
        # she ladles the first bowls (the house pot is a pot dish: pottage, stew, broth or chowder)
        ("kitchen.scene.hot", "the innkeeper, back at her bar after ladling the first bowls", [
            "Hot soup!",
            "Soup's up!",
            "Mind your fingers, it's hot!",
        ]),
        # the baker draws the loaves from the oven
        ("kitchen.scene.bread", "the baker, drawing the loaves from the oven", [
            "Bread's out!",
            "Fresh bread, still warm!",
            "Hot loaves, mind your backs!",
        ]),
        # the rest-day roast on the spit, cried when a player first comes near. The scene runs from half past ten to one, and
        # the roast is carved at noon: the design's "Hot off the spit, the rest-day roast!" is not true before it is done
        ("kitchen.scene.roast", "the innkeeper, on the rest day, when a player first comes near the roast on the spit", [
            "Rest-day roast!",
            "There's roast on the spit, it's the rest day!",
            "Roast on the spit today, folks!",
        ]),
        # a pot at the inn's back hearth has burnt. The design's "Not the back pot again!" says it has burnt before, and
        # "Something's burning" that it is still burning: it has burnt
        ("kitchen.guest.burnt", "the innkeeper, when a pot at the inn's back hearth has burnt", [
            "Who's burnt my pot?",
            "Something's burnt at the back!",
            "Oh no, the back pot!",
        ]),
    ]),
    ("The kitchen: the back door", "MWO", 1, [
        # tasted: one role too high (the role follows as text, "Too much in the greens."). The mix is not spot on and not far
        # off (the verdict's closeness is 1 or 2), so the design's "Good, but" is not said of it
        ("kitchen.verdict.over", "a keeper at the back door, tasting a dish with one role too high", [
            "Hm. One flavour is rather loud.",
            "I'd say there's too much of one thing in it.",
            "Nearly there, but there's too much of one thing.",
        ]),
        # tasted: one role too low ("It wants meat.")
        ("kitchen.verdict.under", "a keeper at the back door, tasting a dish with one role too low", [
            "Hm. There's something missing.",
            "It's light on one thing, I'd say.",
            "Nearly there. It wants a bit more of one thing.",
        ]),
        # tasted: spot on (under 0.05 from the ideal)
        ("kitchen.verdict.right", "a keeper at the back door, tasting a dish that is spot on", [
            "That's just right.",
            "I couldn't ask for better.",
            "Now that is how it's done.",
        ]),
        # tasted: far off (over 0.18)
        ("kitchen.verdict.far", "a keeper at the back door, tasting a dish that is far off", [
            "Hm. It wants working on.",
            "It's food. I'll give it that.",
            "I've tasted better, and I've tasted worse.",
        ]),
        # the tier remarks: at the back door, and from the judges at the Harvest Table. A Plain dish is not known to be warm
        ("kitchen.tier.plain", "a keeper or a judge, of a Plain dish", [
            "Honest work.",
            "It'll do.",
            "Plain and honest.",
            "Needs a pinch more salt.",
        ]),
        ("kitchen.tier.sound", "a keeper or a judge, of a Sound dish", [
            "Good, solid cooking.",
            "Well made.",
            "That's sound work.",
        ]),
        ("kitchen.tier.fine", "a keeper or a judge, of a Fine dish", [
            "Now that's fine cooking.",
            "That is a fine dish.",
            "My word, that's good.",
        ]),
        # a cook may sell a Hallmarked batch every day, so nothing here says "in years"
        ("kitchen.tier.hallmark", "a keeper or a judge, of a Hallmarked dish", [
            "I'll be telling people about this one.",
            "That's a master's hand.",
            "That is as good as it gets.",
            "Now that is a masterpiece.",
        ]),
        # the first batch of its kind the player has sold here (the extra pay follows as the action bar)
        ("kitchen.first", "a keeper, at the first batch of its kind the player has sold here", [
            "That's new from you. Here's a little extra.",
            "Something new from you! A little extra for it.",
            "First of those from you. Here's a little extra.",
        ]),
        # the door can take no more of it today; a portion is not always a bowl
        ("kitchen.full", "a keeper, when the door can take no more of that kind today", [
            "We've plenty of that already today.",
            "I couldn't sell another portion of that today.",
            "The larder's full of that one, I'm afraid.",
        ]),
        # the dish is stale (60% of the value); a stale loaf may be a month old, so not "a day or two"
        ("kitchen.stale", "a keeper, of a stale dish", [
            "That's past its best, so I'll pay less.",
            "It's not as fresh as it was. I'll pay less.",
        ]),
        # the dish has gone off (refused)
        ("kitchen.gone", "a keeper, of a dish that has gone off", [
            "No, that has gone off.",
            "I can't take that. It's turned.",
            "That's off, I'm afraid.",
        ]),
        # the baker, offered a dish only the inn takes; the bakery also takes cakes, which are made on a hob
        ("kitchen.baker.no", "the baker, offered a dish only the inn takes", [
            "That's not for me. Try the inn.",
            "I only take baked goods here.",
            "Take that along to the inn.",
        ]),
        # a keeper in the middle of something else
        ("kitchen.busy", "a keeper who is in the middle of something else", [
            "One moment.",
            "Just a moment.",
            "Hold on, I'm in the middle of something.",
        ]),
        # the innkeeper, asked for the back pot below Known standing
        ("kitchen.guest.no", "the innkeeper, asked for the back pot below Known standing", [
            "The back pot's for those I know.",
            "I don't know you well enough for the back pot.",
        ]),
        # ... and at Known or above
        ("kitchen.guest.yes", "the innkeeper, to a player of Known standing or better", [
            "The back pot is yours when you want it.",
            "You've earned the use of the back pot.",
            "The back pot's yours to use.",
        ]),
        # the alms keeper, given a Fine or Hallmarked dish
        ("kitchen.alms.fine", "the alms keeper, given a Fine or Hallmarked dish", [
            "What a lovely thing to give. Bless you.",
            "A dish like that for the poor? Bless you.",
        ]),
    ]),
    ("The kitchen: what the town says", "MWO", 2, [
        # the slate (the three kinds follow as text); "short of" is not said, for the wanted kinds are a draw and not a want
        ("kitchen.slate", "the innkeeper, the baker or anyone by the inn, of the dishes the inn wants today", [
            "The slate's chalked with what we want today.",
            "We're after a few dishes today. It's on the slate.",
            "Have a look at the slate. We're after a few dishes.",
        ]),
        # a request is open and not filled (the kind and the number follow as text)
        ("kitchen.request", "the innkeeper, of the request of the day, while it is open and not filled", [
            "There's a special order on the slate today.",
            "I've a request on the slate, if you're a cook.",
            "Someone wants several portions of one dish. See the slate.",
        ]),
        # no request is open, or it is filled
        ("kitchen.none", "the innkeeper, when there is no request to fill", [
            "Nothing special today.",
            "Nothing on the slate for you today.",
        ]),
        # where and how to sell (what the door takes follows as text)
        ("kitchen.backdoor", "the innkeeper or the baker, of selling a dish at the back door", [
            "Hold it out to me and I'll taste it.",
            "Bring me what you've cooked and I'll make you an offer.",
            "Show me your cooking and I'll tell you what it's worth.",
        ]),
        # the player is the day's best-selling cook, and a Purveyor
        ("kitchen.purveyor", "the innkeeper, to the cook of the day", [
            "Folk are asking after your cooking.",
            "Your name's the talk of the kitchen.",
        ]),
        ("kitchen.season.winter", "the innkeeper or the baker, in winter", [
            "It's a broth sort of winter.",
            "Winter wants something hot in the belly.",
        ]),
        # the harvest is coming in through the autumn, not in from its first day
        ("kitchen.season.autumn", "the innkeeper or the baker, in autumn", [
            "The harvest is coming in, and folk want feeding.",
            "Autumn's the season for a hearty dish.",
        ]),
        # the city's palate, by style (Tastes.LIKES, 2.8): the two dishes each style likes, so the dishes are the family's own
        # and not what changes: pie and tart, stew and smoked strips, glazed roots and trail loaf, roast and honey cakes
        ("kitchen.palate.temperate", "the innkeeper or the baker of a temperate town", [
            "Pies and tarts: that's what this town likes.",
            "A good pie never goes begging in this town.",
        ]),
        ("kitchen.palate.northern", "the innkeeper or the baker of a northern town", [
            "Stew and smoked strips: that's what this town likes.",
            "A good stew never goes begging in this town.",
        ]),
        ("kitchen.palate.desert", "the innkeeper or the baker of a desert town", [
            "Glazed roots and trail loaves: that's what this town likes.",
            "A good loaf never goes begging in this town.",
        ]),
        ("kitchen.palate.savanna", "the innkeeper or the baker of a savanna town", [
            "Roast and honey cakes: that's what this town likes.",
            "A good roast never goes begging in this town.",
        ]),
        # a resident who relished a dish of the player's this week, at any meal hour (the kind follows as text); "the other
        # night" is not said, for the meal may have been a breakfast or a lunch, and "whatever it was" is told by the grey text
        ("kitchen.relished", "a resident who relished a dish of the player's within the week", [
            "That dish of yours, I'm still thinking about it.",
            "What you cooked this week, I haven't stopped talking about it.",
            "You can cook, I'll say that. It was a good meal.",
        ]),
        # a resident who ate well within the week, to anyone: not a supper, not weeks ago, nothing said of the cook
        ("kitchen.fedwell", "a resident who relished a dish within the week, to a neighbour", [
            "Best meal I've had in a good while.",
            "I've eaten well this week.",
            "It's been a good week for eating.",
        ]),
        # the player is Chilled (at night, or in rain or snow, in winter, unsheltered): so not "a cold night"
        ("kitchen.chilled", "anyone grown, to a player who is Chilled", [
            "You look frozen through. Get something hot inside you.",
            "You're blue with cold. Go and find a hot bowl.",
            "Cold out here. Get something hot to eat.",
        ]),
        # the rumours (who and what follow as text); a Hallmarked dish may be sold at the bakery, so no place is voiced
        ("kitchen.told.hallmark", "anyone who has heard that a Hallmarked dish was sold or served", [
            "They say someone cooked something wonderful lately.",
            "There's talk of a dish folk are still raving about.",
        ]),
        ("kitchen.told.cookoff", "anyone who has heard who won the Harvest Table", [
            "Did you hear who won the Harvest Table?",
            "The Harvest Table has been won, you know.",
        ]),
        ("kitchen.told.burnt", "anyone who has heard that a whole pot burnt at the inn", [
            "Someone burnt a whole pot at the inn.",
            "A whole pot ruined at the inn. What a waste.",
        ]),
    ]),
    ("The kitchen: onlookers, the Harvest Table and the shop", "MWO", 2, [
        # a passer-by, seeing a pot catch / come ready / served Fine / served Hallmarked / burnt
        ("kitchen.pot.catching", "a passer-by, seeing a pot catch", [
            "Something's catching.",
            "Your pot's catching!",
            "I smell burning.",
        ]),
        ("kitchen.pot.smells", "a passer-by, when a pot comes ready", [
            "Smells good!",
            "Mmm. That smells wonderful.",
            "Whatever that is, it smells good.",
        ]),
        ("kitchen.pot.fine", "a passer-by, seeing a Fine dish served", [
            "That's fine cooking.",
            "Now that's how to cook.",
            "That looks very good.",
        ]),
        # the design says "I've never seen one!": a townsperson who has sold a Hallmarked dish at the inn has seen one
        ("kitchen.pot.hallmark", "a passer-by, seeing a Hallmarked dish served", [
            "Is that a hallmark? Look at that!",
            "That's a master's work, that is.",
        ]),
        ("kitchen.pot.burnt", "a passer-by, seeing a pot burnt", [
            "Oh dear, that's ruined.",
            "What a waste of food.",
        ]),
        # the host, opening the Harvest Table / announcing the winner (the name and dish follow as text); nothing is said of
        # what the cooks have brought, for the table may be opened with nothing on it
        ("kitchen.judge.open", "the host, opening the Harvest Table", [
            "Bring your dishes to the table!",
            "The Harvest Table is open to every cook!",
        ]),
        ("kitchen.judge.win", "the host, announcing the winner of the Harvest Table", [
            "And the Harvest Table goes to...",
            "The cook of this year's Harvest Table is...",
        ]),
        # a cookshop's keeper: a shop's dishes are not always from the oven, nor hot, so "hot" and "fresh" are not said
        ("kitchen.shop.cry", "a cookshop's keeper, calling out the wares", [
            "Come and see what's cooking!",
            "Dishes for sale, come and get them!",
            "Food for sale here, food for sale!",
        ]),
        ("kitchen.shop.sold", "a cookshop's keeper, when the stock is gone", [
            "Sold out, I'm afraid.",
            "Nothing left, I'm sorry.",
            "All gone, I'm afraid.",
        ]),
        ("kitchen.shop.first", "a cookshop's keeper, handing over the first plate", [
            "The first plate from the new shop!",
            "Our very first plate, and welcome to it!",
        ]),
        ("kitchen.shop.report", "a cookshop's keeper, to its owner, with the till", [
            "Here's what the shop took.",
            "Here's the till.",
        ]),
        # the works' talk of a raised cookshop (beside works.raised.stall; the same two forms)
        ("works.raised.cookshop", "anyone, of a cookshop raised within the week", [
            "There's a new cookshop in the square. Have you smelled it?",
            "A cookshop in the market now, and the smell of it!",
        ]),
        ("works.also.raised.cookshop", "anyone, of another works that is a cookshop, raised", [
            "And there's the cookshop in the square.",
            "There's a cookshop gone up in the market, too.",
        ]),
    ]),
    ("The kitchen: children", "CHILD", 2, [
        # near a pot, an oven or the inn that is cooking
        ("kitchen.child.smell", "a child, near a station that is cooking", [
            "Mmm, something smells good!",
            "I'm hungry! What's cooking?",
        ]),
        # a player holding a dish near them
        ("kitchen.child.share", "a child, near a player who holds a dish", [
            "Can I have some?",
            "Is that for us?",
        ]),
        # a freshly served bowl
        ("kitchen.child.hot", "a child, near a freshly served dish", [
            "Careful, it's hot!",
            "Don't burn your tongue!",
        ]),
    ]),
]

# Where a family may name a dish or a role in a wording, because the family itself is that dish's or that role's: the
# scene of the rest-day roast, the baker's loaves, and the dishes each style of town likes
CHECK_ALLOWS = {
    "kitchen.scene.roast": {"roast"},
    "kitchen.scene.bread": {"bread", "loaves"},
    "kitchen.season.winter": {"broth"},
    "kitchen.palate.temperate": {"pie", "pies", "tarts"},
    "kitchen.palate.northern": {"stew", "strips"},
    "kitchen.palate.desert": {"roots", "loaf", "loaves"},
    "kitchen.palate.savanna": {"roast", "cakes"},
}

# The words that would give away what changes: the twelve kinds of dish, the seven roles, and counting words
KIND_WORDS = {
    "pottage", "stew", "stews", "broth", "roots", "root", "chowder", "cakes", "loaf", "loaves", "pie", "pies", "tart", "tarts",
    "fish", "roast", "strips", "bread",
}
ROLE_WORDS = {"meat", "greens", "sea", "sweet", "sweetness", "rich", "richness", "savour", "starch", "body"}
NUMBER_WORDS = {
    "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "twelve", "dozen", "hundred", "marks", "mark",
}


def groups(mwo, child, limit):
    """[(group name, timbres, limit in seconds, {family: [wordings]})] in make_talk_voices.py's own form, for the tiers
    that are RECORDED only: [mwo] and [child] are its timbre sets, [limit] its length limit for other talk"""
    sets = {"MWO": mwo, "CHILD": child}
    return [(name, sets[who], limit, {family: list(ws) for family, _, ws in families})
            for name, who, tier, families in TABLE if tier in RECORDED]


def families():
    """{family: (timbre set's name, tier, who says it and when, [wordings])} for every family, in the table's order"""
    return {family: (who, tier, said, list(ws)) for _, who, tier, fams in TABLE for family, said, ws in fams}


def wordings():
    """[(key, text)] for every wording of every family, the key as the language file has it: say.guildmark.talk.<family>_<n>"""
    return [("say.guildmark.talk.%s_%d" % (family, n), text)
            for _, _, _, fams in TABLE for family, _, ws in fams for n, text in enumerate(ws)]


# A family's delivery, as make_talk_voices.DELIVERY: nothing here is said other than plainly (set when recorded)
DELIVERY = {}

# A take's own speed where its timbre's reading would run over the limit, misread or rush (key, timbre) -> times the
# timbre's own, instead of its family's (set when recorded)
SPEED_FOR = {}

# Text the TTS reads better (the subtitles keep the line as written): {key: {written: said}} (set when recorded)
SAY_AS = {}

# A take's phonemes written out in full, as make_talk_voices.PHONEMES: (key, timbre) or key -> phonemes, for the lines the
# recogniser heard wrong (set when recorded)
PHONEMES = {}


def check():
    """The problems with the table, as sentences: [] if there are none. The rules of design 11.5 and of the script's header:
    two to four wordings a family, each a whole sentence, none with a brace or a percent sign, no family named twice, none
    too long for its limit, and no name, dish, number or role in a wording (CHECK_ALLOWS says where the family is that dish's)"""
    problems = []
    seen = {}
    for group, who, tier, fams in TABLE:
        if who not in ("MWO", "CHILD"):
            problems.append("%s: the timbre set %r is not MWO or CHILD" % (group, who))
        if tier not in (1, 2):
            problems.append("%s: the tier %r is not 1 or 2" % (group, tier))
        for family, said, ws in fams:
            if family in seen:
                problems.append("%s is in the table twice (%s and %s)" % (family, seen[family], group))
            seen[family] = group
            if not re.fullmatch(r"[a-z]+(\.[a-z]+)+", family):
                problems.append("%s: the family's name is not lower-case words joined by dots" % family)
            if not said:
                problems.append("%s: it does not say when it is said" % family)
            if not 2 <= len(ws) <= 4:
                problems.append("%s has %d wordings, not two to four" % (family, len(ws)))
            if len(set(ws)) != len(ws):
                problems.append("%s says the same wording twice" % family)
            allowed = CHECK_ALLOWS.get(family, set())
            for n, text in enumerate(ws):
                at = "%s_%d (%r)" % (family, n, text)
                if re.search(r"[{}%]", text):
                    problems.append("%s: has a brace or a percent sign" % at)
                if not re.fullmatch(r"[A-Z][^\n]*[.!?]", text) and not text.endswith("..."):
                    problems.append("%s: is not a whole sentence (a capital, then a stop, a bang or a query)" % at)
                if text != text.strip() or "  " in text:
                    problems.append("%s: has stray spaces" % at)
                if len(text) > LONGEST_CHARS or len(text.split()) > LONGEST_WORDS:
                    problems.append("%s: is %d characters and %d words, over %d and %d (too long for its limit)"
                                    % (at, len(text), len(text.split()), LONGEST_CHARS, LONGEST_WORDS))
                if re.search(r"\d", text):
                    problems.append("%s: has a digit" % at)
                for word in re.findall(r"[A-Za-z']+", text):
                    w = word.lower().strip("'")
                    if w in NUMBER_WORDS:
                        problems.append("%s: names a number or an amount (%s)" % (at, word))
                    elif w in ROLE_WORDS and w not in allowed:
                        problems.append("%s: names a role (%s)" % (at, word))
                    elif w in KIND_WORDS and w not in allowed:
                        problems.append("%s: names a dish (%s)" % (at, word))
    names = [f for _, _, _, fams in TABLE for f, _, _ in fams]
    if len(names) != len(set(names)):
        problems.append("a family is named twice")
    return problems


def tier_counts():
    """{tier: (families, wordings)}"""
    out = {}
    for _, _, tier, fams in TABLE:
        f, w = out.get(tier, (0, 0))
        out[tier] = (f + len(fams), w + sum(len(ws) for _, _, ws in fams))
    return out


# ------------------------------------------------------------------ the two generated files
def lang_text():
    """art/lang/cook_lines.json as `write` has it: the text of every wording, json.dumps with a 2-space indent"""
    return json.dumps(dict(wordings()), indent=2, ensure_ascii=False) + "\n"


def kotlin_text():
    """KitchenWords.kt as `write` has it: every family and the number of its wordings"""
    rows = "".join('        "%s" to %d,\n' % (family, len(ws)) for _, _, _, fams in TABLE for family, _, ws in fams)
    return (
        "package io.github.vaspyyy.guildmark.townsfolk.talk.sources\n"
        "\n"
        "/**\n"
        " * The kitchen's wordings (Hearthcraft, living-world/cook/design.md 8.5, 11.5), generated by art/audio/kitchen_lines.py\n"
        " * (`write`) from its table: don't edit by hand. Each family (\"kitchen.slate\") is one meaning said in COUNT[family]\n"
        " * wordings, the text of wording n in the language file as `say.guildmark.talk.<family>_<n>` (art/lang/cook_lines.json),\n"
        " * whether the family has takes recorded or not: [KitchenLines] reads a wording as text while it has none.\n"
        " */\n"
        "internal object KitchenWords {\n"
        "    val COUNT: Map<String, Int> = mapOf(\n"
        + rows +
        "    )\n"
        "\n"
        "    /** How many wordings [family] has, or 0 if it is not a kitchen family. */\n"
        "    fun count(family: String): Int = COUNT[family] ?: 0\n"
        "}\n"
    )


def put(path, text):
    """Writes [text] to [path] unless it is there already; True if the file changed"""
    try:
        with open(path, encoding="utf-8", newline="") as f:
            if f.read() == text:
                return False
    except OSError:
        pass
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    return True


def write():
    """Writes the two generated files; says which changed (a second run changes neither)"""
    for path, text in ((LANG_OUT, lang_text()), (KOTLIN_OUT, kotlin_text())):
        print("%s: %s" % (os.path.relpath(path, REPO), "written" if put(path, text) else "unchanged"))


def main(argv):
    command = argv[0] if argv else "check"
    if command == "check":
        problems = check()
        for p in problems:
            print("PROBLEM: " + p)
        fams = families()
        print("%d families, %d wordings; tiers %s; recorded %s" % (
            len(fams), sum(len(v[3]) for v in fams.values()),
            ", ".join("%d: %d families, %d wordings" % (t, f, w) for t, (f, w) in sorted(tier_counts().items())),
            RECORDED or "none"))
        return 1 if problems else 0
    if command == "write":
        problems = check()
        if problems:
            for p in problems:
                print("PROBLEM: " + p)
            return 1
        write()
        return 0
    if command == "list":
        for name, who, tier, fams in TABLE:
            print("tier %d, %s, %s" % (tier, who, name))
            for family, said, ws in fams:
                print("  %-28s %d  %s" % (family, len(ws), said))
        return 0
    print(__doc__.split("\n\n")[0], file=sys.stderr)
    print("usage: kitchen_lines.py [check|write|list]", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
