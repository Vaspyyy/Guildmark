"""
Writes the pack's program files: one short file per program, stage and dimension that sets which program and
world it is and includes the shared source in shaders/program. Iris reads programs only from a dimension's own
folder once it exists (world-1 the Nether, world1 the End), so each folder needs the full set.

    python3 art/shaderkit/stubs.py src/main/resources/shaderpack/Guildmark/shaders
"""

import os
import sys

PROGRAMS = {
    "gbuffers_basic": ("gbuffers_forward", ["PROGRAM_BASIC"]),
    "gbuffers_line": ("gbuffers_forward", ["PROGRAM_BASIC"]),
    "gbuffers_textured": ("gbuffers_forward", ["PROGRAM_TEXTURED"]),
    "gbuffers_textured_lit": ("gbuffers_forward", ["PROGRAM_TEXTURED"]),
    "gbuffers_particles": ("gbuffers_forward", ["PROGRAM_PARTICLES"]),
    "gbuffers_weather": ("gbuffers_forward", ["PROGRAM_WEATHER"]),
    "gbuffers_skybasic": ("gbuffers_sky", []),
    "gbuffers_skytextured": ("gbuffers_sky", []),
    "gbuffers_clouds": ("gbuffers_sky", []),
    "gbuffers_terrain": ("gbuffers_world", ["PROGRAM_TERRAIN"]),
    "gbuffers_damagedblock": ("gbuffers_world", ["PROGRAM_DAMAGED"]),
    "gbuffers_block": ("gbuffers_world", ["PROGRAM_BLOCK"]),
    "gbuffers_beaconbeam": ("gbuffers_world", ["PROGRAM_BEACON"]),
    "gbuffers_entities": ("gbuffers_world", ["PROGRAM_ENTITIES"]),
    "gbuffers_lightning": ("gbuffers_world", ["PROGRAM_LIGHTNING"]),
    "gbuffers_armor_glint": ("gbuffers_world", ["PROGRAM_GLINT"]),
    "gbuffers_spidereyes": ("gbuffers_world", ["PROGRAM_SPIDEREYES"]),
    "gbuffers_hand": ("gbuffers_world", ["PROGRAM_HAND"]),
    "gbuffers_water": ("gbuffers_water", []),
    "gbuffers_hand_water": ("gbuffers_water", ["PROGRAM_HAND_WATER"]),
    "shadow": ("shadow", []),
    "deferred": ("deferred", []),
    "composite": ("composite", []),
    "composite1": ("composite1", []),
    "composite2": ("composite2", []),
    "composite3": ("composite3", []),
    "composite4": ("composite4", []),
    "final": ("final", []),
    "dh_terrain": ("dh_terrain", []),
    "dh_water": ("dh_water", []),
}

WORLDS = {"": "WORLD_OVERWORLD", "world-1": "WORLD_NETHER", "world1": "WORLD_END"}
# The Nether has no sun to cast shadows
SKIP = {"world-1": {"shadow"}}


def main(shaders):
    for folder, world in WORLDS.items():
        out = os.path.join(shaders, folder) if folder else shaders
        os.makedirs(out, exist_ok=True)
        for name, (source, defines) in PROGRAMS.items():
            if name in SKIP.get(folder, ()):
                continue
            for stage, flag in (("vsh", "VSH"), ("fsh", "FSH")):
                lines = ["#version 330 compatibility", "#define " + world]
                lines += ["#define " + d for d in defines]
                lines += ["#define " + flag, '#include "/program/%s.glsl"' % source, ""]
                with open(os.path.join(out, "%s.%s" % (name, stage)), "w", encoding="utf-8") as f:
                    f.write("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1])
