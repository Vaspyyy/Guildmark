"""The Sergeant's Friend (living-world/stories/design.md section 8.10): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GENTLE, GREETING, GRIEF, MW, TALK, TIRED, WARM

GROUPS = [
    ("The Sergeant's Friend", MW, TALK, {
        # the sergeant at his post at the gate (S1 The Count, 10:00 to 17:00, a slate in his hand), to a player who
        # has not said yes yet: the opener; the road follows in grey
        "tale.informer.ask": [
            "Two wagons gone, and the band knew which.",
            "Someone is selling the wagons' hours to the band.",
        ],
        # the sergeant, to anyone passing who has not been asked
        "tale.informer.passing": [
            "Somebody's talking, and I don't like it.",
            "The band always knew the day. Why is that?",
        ],
        # anyone who has heard that the band always knows the wagons' days (3.2); who says so and which road follow in
        # grey
        "tale.informer.rumour": [
            "They say the band always knows the wagons' days.",
            "Someone must be telling the band when wagons go.",
        ],
        # the sergeant, when the player says yes: let one hear of a wagon and he will send a decoy; the three names
        # follow in grey
        "tale.informer.plan": [
            "Let one hear of a wagon. I'll send a decoy.",
            "Whisper a false wagon to one. I'll send a decoy.",
        ],
        # the sergeant, of his oldest friend, when the player brings the friend's name up (steps 1 and 2): not that
        # one
        "tale.informer.friend": [
            "Not that one. We go back a lifetime.",
            "I'd stake my life on that one.",
        ],
        # a gate neighbour, in their own day (step 1): what they see of the wagons from their door; where they live
        # follows in grey
        "tale.informer.tell": [
            "I see every wagon from my door. I say nothing.",
            "Half the street sees the wagons. Why me?",
        ],
        # the sergeant's friend, in his own day (step 1): he drinks with the sergeant, and that is all
        "tale.informer.tell_friend": [
            "I only drink with the sergeant. That's all.",
            "We're old friends. We talk of old days.",
        ],
        # the sergeant at first light, when the decoy was not struck (step 2): that man is clear; how many are tested
        # follows in grey
        "tale.informer.clear": [
            "Nothing came. Not that one, then.",
            "The road stayed quiet. That one is clear.",
        ],
        # the sergeant at first light, when the decoy was struck: the band knew; the road and the man told follow in
        # grey
        "tale.informer.struck": [
            "They were waiting for it. Somebody talked.",
            "The band struck the decoy, just as feared.",
        ],
        # the sergeant, when the band lay low and the test could not be tried (step 2)
        "tale.informer.lowlie": [
            "The band is lying low. Try again soon.",
            "Nothing stirred. The band must be resting.",
        ],
        # the sergeant, once the leak is shown (step 3): "Who did you tell?"; the names told follow in grey
        "tale.informer.who": [
            "Who did you tell? Say it plain.",
            "Name the one the band listened to.",
        ],
        # the sergeant, to a player who names a man before any test has struck (step 2)
        "tale.informer.proof": [
            "That's a heavy word. Bring me proof first.",
            "I'll not shame a neighbour on a hunch.",
        ],
        # the sergeant in the gate yard (The Naming, ending A), as the friend is named before the watch
        "tale.informer.named": [
            "After all these years. After everything.",
            "You? Of all the people in this town.",
        ],
        # the friend, when the player says "Let me speak to him first." (route B): he asks for the night
        "tale.informer.hush": [
            "I'll tell the sergeant myself. Give me till dawn.",
            "It stops tonight. I'll face the sergeant myself.",
        ],
        # the sergeant at the friend's door at dusk (The Handshake, ending B), after the friend has told him himself
        "tale.informer.forgive": [
            "I heard it from their own mouth. That counts.",
            "Sit down. It's said, and we'll leave it there.",
        ],
        # the sergeant, when the player says "Only the road: the band is listening." (route C); the second pair at the
        # gate follows in grey
        "tale.informer.road_only": [
            "Then I'll watch that road twice over.",
            "Double the guard on that road. I'll see to it.",
        ],
        # the sergeant, to the player after the ending (endings A to C)
        "tale.informer.thanks": [
            "You've done the town a service. I won't forget.",
            "The road's safer for what you did.",
        ],
        # the sergeant, when nobody acted and another wagon is lost (ending W)
        "tale.informer.w": [
            "Another wagon gone. Someone is still talking.",
            "The band knew again. I trust no one now.",
        ],
        # the sergeant, when a player who is in asks "How does it stand?"; who has been tested follows in grey
        "tale.informer.stand": [
            "Any word? Someone is still talking.",
            "Tested or not, the band still listens.",
        ],
    }),
    ("The Sergeant's Friend: short lines", MW, GREETING, {
        # whichever man the player lets a wagon slip to (step 2): the same family for all three, so the answer tells
        # nothing; the road follows in grey
        "tale.informer.slip_ok": [
            "A wagon is coming? Good to know.",
            "Is that so? I'll keep it quiet.",
        ],
        # the friend at the dead drop (The Dead Drop, 18:00 to 19:30), caught in the act by the player
        "tale.informer.caught": [
            "It isn't what it looks like!",
            "Please, let me explain.",
        ],
        # the friend in the gate yard (The Naming, ending A), answering the sergeant
        "tale.informer.sorry": [
            "I'm sorry. I never meant harm.",
            "They said no one would be hurt.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.informer.ask": dict(level=-1.5),  # a thing not said loudly
    "tale.informer.passing": dict(level=-1.5),  # muttered: somebody is talking
    "tale.informer.rumour": dict(level=-1.5),  # the town's talk of it, lowered
    "tale.informer.stand": dict(level=-2.0),  # kept low: someone is still listening
    "tale.informer.thanks": WARM,  # the road made safer
    "tale.informer.plan": dict(level=-2.0),  # a plan whispered
    "tale.informer.tell": dict(speed=1.03, level=0.5),  # defensive
    "tale.informer.struck": dict(speed=0.96, pitch=-15),  # the decoy struck: grim
    "tale.informer.who": dict(speed=0.97, level=1.0),  # asking sternly
    "tale.informer.named": GRIEF,  # a friend named
    "tale.informer.hush": dict(speed=0.97),  # resolved
    "tale.informer.forgive": GENTLE,  # forgiving
    "tale.informer.w": TIRED,  # another wagon lost
    "tale.informer.slip_ok": dict(level=-0.5),  # a sly answer
    "tale.informer.caught": dict(speed=1.06, level=1.0),  # caught out
    "tale.informer.sorry": dict(speed=0.93, pitch=-15, level=-1.0),  # contrite
}
SPEED_FOR = {}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    ("talk.tale.informer.thanks_0", "man"): "juːv dˈʌn ðə tˈaʊn ˈeɪ sˈɜːvɪs. aɪ wəʊnt fɚɡˈɛt.",  # "a service", not "of service"
}
