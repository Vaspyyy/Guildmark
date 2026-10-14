# Art sources

Entity models and textures are generated from the Python scripts in `scripts/`, so geometry, texture
and Kotlin model code always agree.

- `scripts/modelkit.py` describes models as parts and box-UV cubes in Minecraft model space, packs the
  UVs, paints the textures, writes the Kotlin `LayerDefinition`, renders previews and saves a
  `.bbmodel` you can open in Blockbench. A cube can draw only some of its faces (`faces=`): a flat card is one
  face, seen from both sides.
- `scripts/hollow_king.py` is the Hollow King (main story final boss).
- `scripts/story_npc.py` is the named characters: Wren, Tobin, Ilsa, Maren and Pip share one model and each
  shows only their own pieces.
- `scripts/hollow_soldier.py` is the Hollow Soldier, a risen foot soldier of the Hollow King's army (the
  `tabard` flap sways; sword and shield come from the held-item layer). Its glow map is just the two eyes.
- `scripts/region_hollows.py` is the wild lands' own Hollows (Mire, Cinder, Rime and Glimmer): the Hollow Soldier
  with each land's pieces on one model (a fringe of cards round the brim and under the shoulder plates for the weed
  and the icicles, crystals through the hat, a shoulder, the back and an arm), each showing only its own. It reads
  the soldier's painted design back as characters (`hollow_soldier.py` is importable for this) and repaints it at
  twice the declared 128x64, each material with its own fine detail, then paints each land's weathering over it:
  moss, mud and rust; char, heat-blued iron and glowing cracks; snow, frost and icicles; crystal and its veins.
  It writes the four textures and glow maps, the 32x spawn eggs, `client/model/RegionHollowMesh.kt` (straight into
  the source tree), `art/models/region_hollow.bbmodel` (all four textures) and preview sheets.
- `scripts/fallen_warden.py` is Halvard, the Fallen Warden (boss, rendered at 1.5x), with a `cloak` and a
  `greatsword`. It writes two glow maps: `_glow` (phase one, the eye slit) and `_glow2` (phase two, eyes,
  armour cracks, chest sigil and sword runes). Cracks and runes are exact marker colours in the base texture.
- `scripts/townsfolk.py` is the townsfolk of the capitals: one humanoid for all twelve roles (citizen, smith, baker,
  merchant, guard, farmer, fisher, child, noble, innkeeper, bard, priest) carrying every look's pieces (hair, beards,
  hats, helms, aprons, tabards, capes, skirts...) and the held props of `scripts/townsfolk_tools.py`; each of the 44
  looks shows only its own pieces and has its own texture. Skirts, robes and coats are one shell split down the
  middle that rides the legs, their length painted. Textures are HD: box UV declared at 256x128, painted and shipped
  at 512x256, with one shared glow map (hot iron, lantern). It writes its Kotlin straight into `client/townsfolk/`:
  `TownsfolkMesh.kt` (the geometry) and `TownsfolkLooks.kt` (every look's texture, voice and pieces). Its previews
  are contact sheets: every look front and back (also `art/models/townsfolk_preview.png`) and each role up close.
- `scripts/hdkit.py` is the HD art kit the 32x blocks and items are painted with, in the dark-fantasy look the
  shader sets: material palettes (cool shadows, warm light, a few hard tones each), height fields lit from the upper
  left, ordered dither where tones meet, bevels for stamped and carved shapes, and the dark outline round items.
- `scripts/items_hd.py` is every Guildmark item at 32x (the Guild Mark, contract, quest board, relic caches, the
  Warden's Flask at its four levels, crown shard, Dusk Bridle, Lantern Wisp and the four older creatures' spawn
  eggs), the seven quest notes and the quest board's own planks and frame. It writes textures only; the models
  and item definitions already point at them. It replaces the old 16x scripts (`cache_art.py`, `flask_art.py`,
  `shard_art.py`, `rider_items.py`, `spawn_eggs.py`).
- `scripts/wardfire_art.py` and `scripts/dungeon_art.py` paint the Wardfire and the mist gate at 32x too.
- `scripts/creature_hd.py` ships the older creatures at twice their declared size: the Hollow Soldier, the Hollow
  King, Halvard, the Hill Troll, the Dusk Rider and steed, the five named characters, the four rival guilds and the
  Guildmaster's villager coat. It remasters each 1x design one cube face at a time (read from the model scripts, so
  nothing bleeds between faces): Scale2x inside the face, each material's own fine surface (scratched metal, threads
  in cloth, grain in leather, mottled skin and moss), and a lit edge where a lighter piece meets a darker one. Glow
  maps follow the same pixels. The 1x designs are kept in `entity_1x/`; rerun a model script, then this one, and it
  picks up the fresh 1x texture by its size.
- `scripts/ui_hd.py` is every screen, HUD, compass and map sprite at 2x. The 1x designs are kept in `ui_1x/`
  (`ui_art.py` and `hud_art.py` draw most of them, into a folder you give them; the ledger and tracker ones were drawn
  by hand), and `ui_hd.py` remasters each one: Scale2x for clean diagonals, outlines thinned to one fine line (the
  compass markers keep their full outline), a bevel of light and a little grain. The frames, buttons, bars and the
  compass band are repainted outright, with gilt corner brackets on the panels. The sprites' `.mcmeta` stay as they
  are: nine-slice sizes and borders are in screen pixels, so the finer textures draw at the same size. Frames and
  icons are 2x rather than 4x so one-texel lines still show at GUI scale 2 and 3.
- `scripts/vanilla_hd.py` builds the optional Guildmark HD resource pack (`resourcepacks/guildmark_hd`, listed in
  Options > Resource Packs, off by default; `client/HdPack.kt` registers it). It takes 53 vanilla blocks (stone and
  its variants, dirt and grass, sand, logs, planks and leaves, the ores) straight from the Minecraft jar and remasters
  them at 32x so they sit next to Guildmark's own blocks: Scale2x with wrap so they still tile, a bevel where shapes
  meet, fine grain per material, and a dark fantasy grade (a little desaturated, cool shadows, warm highlights). Tinted
  textures (grass, leaves) stay grey so biome colours still apply, and ore crystals keep their colour.

Rebuild a model (needs Python 3 with Pillow and numpy), from the repo root:

```
python3 art/scripts/hollow_king.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/story_npc.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/hollow_soldier.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/fallen_warden.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 -B art/scripts/townsfolk.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 -B art/scripts/region_hollows.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 -B art/scripts/items_hd.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/ui_hd.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/creature_hd.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/vanilla_hd.py <minecraft-26.3 jar> src/main/resources/resourcepacks/guildmark_hd [preview-dir]
```

Each run writes the textures into the mod's assets, the `.bbmodel` into `art/models`, and a preview
PNG and the model's Kotlin body (`*.kt.txt`) into `<preview-dir>`. Paste that body into
`createBodyLayer()` of the matching model in `client/model/` (e.g. `HollowKingModel.kt`).

The `.bbmodel` files are for looking at and tweaking in Blockbench. If you change a model there, export
it as a Modded Entity and copy the geometry back into the Kotlin model by hand.

## Animations

`scripts/animkit.py` is the animation kit. A clip is keyframes on a small skeleton (root, torso, head, arms,
legs), eased and baked to `assets/guildmark/animations/`, which the game's `CombatAnimator` plays.
`scripts/combat_anims.py` is the player's moves (third and first person), timed to `combat/Move.kt`.
`scripts/enemy_anims.py` is the Hollow Soldier's and Halvard's attacks, timed to their attacks in `enemy/`,
plus Halvard's kneel, rise and death. Both render contact sheets of every clip to check by eye; the enemy
sheets draw each creature's own model, read from `client/model/EnemyModels.kt`.

```
python3 art/scripts/combat_anims.py src/main/resources/assets/guildmark <preview-dir> <steve.png> <iron_sword.png>
python3 art/scripts/enemy_anims.py src/main/resources/assets/guildmark <preview-dir> - <iron_sword.png> [clip ...]
```

`steve.png` and `iron_sword.png` come from the vanilla client jar.

`scripts/townsfolk_anims.py` is the townsfolk's 43 clips (smiths at the anvil, bakers at the oven, guards, farmers,
fishers, children, nobles, the tavern, and the walk-alongs like carry and stagger), timed to the activities in
`townsfolk/TownRole.kt` and the events `client/townsfolk/TownsfolkClient.kt` fires, baked to
`assets/guildmark/animations/townsfolk/`. The held props it poses (hammer, tongs, peel, spear, hoe, rod, mug,
lute...) are built in `scripts/townsfolk_tools.py`, which `townsfolk.py` puts into the model; the clips solve
hands, blades and rims onto anvils, ovens, mouths and mugs and print each contact they check. Its contact
sheets draw the real townsfolk model with the station (anvil, oven, bar, stair, water) beside it; name clips
to render only theirs.

```
python3 -B art/scripts/townsfolk_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]
```

## Screens

The screen art is drawn in code too, from the repo root:

```
python3 art/scripts/ui_art.py src/main/resources/assets/guildmark [<preview.png>]
python3 art/scripts/heading_font.py src/main/resources/assets/guildmark [<preview.png>]
python3 art/scripts/hud_art.py src/main/resources/assets/guildmark/textures/gui/sprites/hud
python3 art/scripts/title_art.py src/main/resources/assets/guildmark [<preview-dir>] [--only sky,logo]
```

- `scripts/ui_art.py` is the screen kit: panels, buttons, insets, equipment sockets and dividers (`ui/`), the
  compass strip and its markers (`compass/`), the world map's icons, compass rose and frame (`map/`), the death
  screen (`death/`), and the map's parchment and guild-lands wash (`textures/gui/map/`). Each sprite gets its
  `.mcmeta` scaling. The preview is a contact sheet of every sprite at 4x.
- `scripts/heading_font.py` is the carved capitals used for headings and names (`font/heading.json`).
- `scripts/hud_art.py` is the combat HUD: bars, the boss bar frame, the lock-on mark and the stance-broken icon.
- `scripts/title_art.py` is the title screen (`textures/gui/title/`): the painted dusk's layers (sky, clouds, far
  mountains, hills, the capital and its window lights, mist, the rise with the Wardfire), the fire, ember, smoke
  and star sprites, the vignette and the Guildmark name. The layers' places on its 1920x1080 canvas match
  `Backdrop.kt`. The preview dir gets the scene as the game composes it. It needs SciPy too.

## Other

- `scripts/spawn_eggs.py` recolours vanilla's zombie spawn egg into the two new spawn eggs.
- `scripts/townsfolk_particles.py` is the sparks off a smith's hammer: the four `forge_spark` sprites, white-hot
  points that shrink as the spark dies (the particle tints them as they cool), and their particle definition.
  Run it with `python3 -B art/scripts/townsfolk_particles.py src/main/resources/assets/guildmark`.
- `scripts/region_particles.py` is the motes in the regions' air: ash flakes and embers, marsh wisps and gnats,
  blown snow, crystal glints, pollen, spores, sand, cave dust and bits of leaf (52 sprites at 16x16, and their
  particle definitions). Most are painted pale and tinted in the game (`client/region/RegionParticles.kt`).
  Run it with `python3 -B art/scripts/region_particles.py src/main/resources/assets/guildmark [preview.png]`.
- `scripts/region_terrain.py` is the wild lands' own ground cover (`region/terrain/`): bog rot, bog lanterns and
  cattails for the marsh; ash, charred logs and ember vents for the ashen highlands; icicles and hoarfrost for the
  frozen peaks; glimmer clusters, glimmerstone and glimmer crystal for the crystal deeps. 32x textures with
  full-bright glow overlays, and their block and item models, blockstates and item definitions.
  Run it with `python3 -B art/scripts/region_terrain.py src/main/resources/assets/guildmark [preview-dir]`.
- Sound effects, music and Halvard's voice: see `audio/README.md`.
