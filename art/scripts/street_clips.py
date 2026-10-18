"""
The streets' moves (M6, influence: living-world/design-influence.md, sections 5, 6 and 8), on the Living World's kit.
"""
import works_clips as wc

la = None
STREET_CLIPS = []
BUILT = {}


def build(living, only=None):
    global la
    la = living
    if BUILT:
        return
    BUILT['done'] = True
    la.WANTED.extend(STREET_CLIPS)
