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
- `scripts/renegade.py` is Silvan Marrow, the Unbound (the renegade Starwatch mage, the questline's final boss): a
  gaunt man in a tattered midnight-blue robe with a deep pointed `hood` on the head and a long `mantle` on the body whose
  hem is torn into six strips (`hem_0` to `hem_5`); the humanoid parts keep their standard names, so the animation
  clips drive him, and his staff is the held item. Box UV declared 128x64, painted and shipped at 256x128, with a
  glow map for his eyes and the mantle's cracked star sigils. It writes the two textures, `client/model/RenegadeModel.kt`
  (straight into the source tree; the mantle and its strips swing after `CombatAnimator`), `art/models/renegade.bbmodel`
  and previews.
- `scripts/townsfolk.py` is the townsfolk of the capitals: one humanoid for all sixteen roles (citizen, smith, baker,
  merchant, guard, farmer, fisher, child, noble, innkeeper, bard, priest, the factions' mage, knight, thief, and the four
  travelling companions) carrying every look's pieces (hair, beards, hats, helms, hoods, aprons, tabards, capes, skirts,
  scabbards...) and the held props of `scripts/townsfolk_tools.py`; each of the 60 looks shows only its own pieces and
  has its own texture (look 0 of mage, knight and thief is the faction leader, drawn closer up in
  `townsfolk_leaders.png`). The role `companion` is Bram, Ysolde, Kestrel and Lucan, looks 0 to 3 in that order and the
  most detailed of all, drawn closer up in `townsfolk_companions.png`; it is no town role (nothing spawns it in a town,
  so it has no props: what they hold is drawn by the item layer), and its look-only pieces (`messy_hair`, `fur_collar`,
  `short_cape`, `quiver`, `knife`, `satchel`, `satchel_papers`, `plait`, `lamp_charm`, `long_scarf`, `pauldron`) have
  names of their own and are packed into the atlas after the older cubes, so no older look moved a texel. Skirts, robes
  and coats are one shell split down the middle that rides the legs, their length painted. Textures are HD: box UV
  declared at 256x128, painted and shipped at 512x256, with one shared glow map (hot iron, lantern). It writes its
  Kotlin straight into `client/townsfolk/`: `TownsfolkMesh.kt` (the geometry) and `TownsfolkLooks.kt` (every look's
  texture, voice and pieces). Its previews are contact sheets: every look front and back (also
  `art/models/townsfolk_preview.png`), each role up close, the faction leaders and the companions.
- `scripts/hdkit.py` is the HD art kit the 32x blocks and items are painted with, in the dark-fantasy look the
  shader sets: material palettes (cool shadows, warm light, a few hard tones each), height fields lit from the upper
  left, ordered dither where tones meet, bevels for stamped and carved shapes, and the dark outline round items.
- `scripts/items_hd.py` is every Guildmark item at 32x (the Guild Mark, contract, quest board, relic caches, the
  Warden's Flask at its four levels, crown shard, Dusk Bridle, Lantern Wisp and the four older creatures' spawn
  eggs), the nine quest notes (new kinds go on the end of `NOTE_TYPES`, which seeds each note's paper) and the
  quest board's own planks and frame. It writes textures only; the models
  and item definitions already point at them. It replaces the old 16x scripts (`cache_art.py`, `flask_art.py`,
  `shard_art.py`, `rider_items.py`, `spawn_eggs.py`).
- `scripts/wardfire_art.py` and `scripts/dungeon_art.py` paint the Wardfire and the mist gate at 32x too.
- `scripts/wares_art.py` is the capitals' market displays (`economy/WaresBlock.kt`): the produce basket, fish crate,
  meat rack, cloth board, arrow rack, candle tray, bread shelf and grain sacks at 32x, with their models, blockstates
  and item definitions. The goods on them are the game's own items, laid by `economy/WaresLayout.kt` on the floors,
  rims and hooks of these models, so the two have to agree. Run it with
  `python3 -B art/scripts/wares_art.py [assets dir [preview dir]]`.
- `scripts/faction_art.py` is the factions' other art: the sworn knight (`sworn_knight.png`, a living Hearthsworn knight
  painted on the Hollow Soldier's own model and UV layout at the same 2x size), the 32x purse, the four heist prizes
  (`item/heist/`, which `items/heist_prize.json` picks between by custom model data), the Starwatch staff, the Widow's
  Kiss and Hearthbrand (staff and dagger on `weaponkit.py`'s layouts, the sword a vanilla-style handheld), and the
  ward-light (`entity/ward_light.png`, a soft full-bright orb with a faint star cross, for a camera-facing quad). It
  writes the textures, models and item definitions, and preview sheets.
- `scripts/companion_art.py` is the companions' keepsake weapons, the gifts they give once Sworn, painted at 32x with
  `hdkit.py` the way `faction_art.py` paints its staff and daggers: `greyhart` (Bram's reforged greatsword: blackened
  steel, a grey hart etched on the blade above the guard, grey leather grip; the greatswords' own layout and model),
  `lamplighters_censer` (Ysolde's: blackened brass, a small flame steady in the fretwork; the iron censer's layout and
  model), `thornwood_bow` (Kestrel's: black yew, a green leather grip, pale hart-horn tips; the vanilla bow's four
  textures, `thornwood_bow` and `thornwood_bow_pulling_0` to `_2`, its models and display transforms, and its item
  definition that switches them by how long the bow has been drawn) and `unpicked_star` (Lucan's: pale ash wound with
  faded midnight-blue cloth, a silver star sewn at the head, a cold light caged there; the amethyst staff's layout and
  model). It writes the textures, models and item definitions (no Kotlin), and a preview sheet: each keepsake large, then
  in a hotbar beside the older weapons.
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
- `scripts/market_board_art.py` is the capitals' market board (3x2): its six part models, drawn as one board in model
  pixels (two posts in stone collars, a slate and a news panel in a dark frame with iron corner straps, a chalk ledge,
  a carved header under a slate-tiled cap, a back of planks and battens), its 32x textures, the item icon, and the
  sheets the game writes with: `market_board/letters.png` (the chalk hand and the ink hand of
  `scripts/market_board_letters.py`, every chalk glyph also cut into the strokes it is drawn in, and the chalk's
  underline, strike, smudge and stick) and `market_board/slips.png` (the five slip papers, a tack, shadows). It also
  writes `client/MarketBoardArt.kt`, the layout numbers and glyph tables `client/MarketBoardRenderer.kt` draws the
  live writing from: change the layout in the script, not the Kotlin. Given a preview dir and the Minecraft client
  jar (for the goods' icons), `scripts/market_board_preview.py` renders the face offline with the same numbers: the
  textures, a filled-in face, close-ups, the board in 3D front and back, and `crier_at_work.gif`.
- `scripts/season_art.py` is the seasons' and festivals' art: the compass medallion and the four season glyphs, the
  festival emblems, the Almanac's wheel of the year, needle and drifting motes (row designs put through
  `ui_hd.remaster`, the wheel painted at 2x), the season particles (petals, leaves, breath and frost glints, 16x16,
  painted pale where the game tints them) and the 32x Almanac item. With a preview dir it writes a contact sheet.
- `scripts/festival_art.py` is the grand festivals' art: the decorations' atlas (`entity/festival/ornaments.png`,
  the regions `FestivalOrnamentRenderer` declares at 256x256, painted at 512x512: pale pennant cloth, cord, ribbon,
  bows, glow, lantern glass and paper for the game to tint, and in their own colours the wood, iron and gilt, the
  four banners, the garlands' flowers and leaves, the sun wheel, the star and the sheaves), the Ribbonwake's painted
  maypole (its stripes run on round the pole's faces and up into the next block), the four 32x prizes, the Ribbon
  Crown as worn, the sky lantern particle, and the JSON that points at them: the trim's blockstate (the poles, the
  rest the vanilla blocks' models), the ornament's, the poles' models, the prizes' item models and definitions and the
  crown's equipment asset. With a preview dir it writes a contact sheet.
- `scripts/cook_art.py` (with the `cook_*.py` beside it) is Hearthcraft's art: the four stations (the stewpot in its camp
  and hearth styles, the hob, the bake oven, the spit), the prep board, plate, larder, menu board and the herb's four ages,
  as block models, blockstates and 32x textures, with every moving piece (the pot on its chain, the pan, the oven's door,
  the spit's rod and crank, the larder's door) a model of its own: the list of their pivots and ranges, the click boxes and the
  places the renderers draw things is the first comment block of `cook_art.py`, the contract with the renderers. It also
  draws the twenty items (the fourteen dishes, the scorched mess among them, with their tier stamps and stage looks,
  steam and flies over them, and the tools, whose `display` blocks set how they are held), the five effect icons, the cold
  vignette, the 21 GUI sprites, the seven kitchen particles and the four textures the renderers draw
  (`textures/entity/cook/`). With a preview dir it writes the contact sheets and GIFs (`cook_prev.py`, about six
  minutes); the third argument, an unpacked game `assets/minecraft`, adds the vanilla campfire and furnace beside the
  stations at true scale, the herb on farmland and the tavern and bakery dioramas. The cookshop's parchment on the works
  post is in `works_art.py`. Before any preview it checks what it wrote (`cook_check.py`, which also runs alone: every
  id has its files and everything they name exists) and stops with the list if something is wrong.
  Run it with `python3 -B art/scripts/cook_art.py [assets dir [preview dir [game assets dir]]]`.
- `scripts/haul_board_art.py` (with `haul_board_icons.py`, `haul_board_model.py`, `haul_board_paint.py`, `haul_board_item.py`
  and `haul_board_preview.py` beside it) is the trade's board and its small art: the Haul Board (dark oak and iron, in the
  market board's six cells and blockstate: a slate with a chalk ledge, a rail of eight pegs, a shelf with an inkwell, a quill
  and a brass bell, a cap with a wheel-and-sheaf crest whose glint is an animated texture), its moving 32x icon, a Blockbench
  file of the whole board (`art/models/haul_board.bbmodel`, Java Block format) and the chip atlas (the 23 goods short and in
  glut, the event badges, the road pennants, the hourglass in eight sand levels, the booker's six seals, the notches, a ticket's
  paper), written both as a block-atlas texture and as a plain one, with the screens' sprites. It writes
  `client/trade/HaulBoardArt.kt`, where every cell of the atlas is and the numbers the board's lettering, rows and tickets are
  laid out by: change the layout in the script, not the Kotlin. The board's lettering is not painted, only room is left for it.
  Before it is done it checks what it wrote. With a preview dir it renders the board offline with the same numbers (straight on,
  bare, in 3D beside the market board, close-ups, the crest's glint, and eight seconds of the board alive: the sand running, a
  glass turned, the pennants rippling, the tickets stirring, a ticket peeled off and a new one hung and stamped) and sheets of
  every picture. The moving picture shows what the cells allow; how the game's renderer moves them is that renderer's.
- `scripts/trade_items.py` is the trade's six items at 32x: the Tally (closed in the inventory, open in a hand), the Carrier's
  Warrant, the Pack Harness, the Drover's Whistle (with the raised variant a held, used whistle takes), the Chapman's Cart (its
  pennant a second layer, tinted by the stack's dye) and the Wagon Bill. Each is a strip with a `.mcmeta` (every one rests a
  different number of ticks, then moves, then a light crosses what is shiny), its model and its item definition. With a preview
  dir it writes the sheets and a GIF of each moving item and the cart in every dye; with `TRADE_VANILLA` set to a folder of the
  game's item textures, the items beside those they stand in for. It checks what it wrote.
- `scripts/pack_harness.py` (with `carrier_kit.py` and `carrier_paint.py` beside it, which the carriers' other scripts share) is
  the Pack Harness on a donkey, a mule and a llama: a pack saddle and leather, a blanket in the owner's colour, two wicker
  panniers that fill in four steps, a brass bell with a clapper of its own, a halter and a crupper. It is laid over the vanilla
  animal's own model the way the game dresses a saddled horse, so every part the animal's model looks up is there. It writes
  `textures/entity/trade/pack_harness.png`, the two Blockbench files (`art/models/pack_harness_donkey.bbmodel`, `_llama`) and
  `client/trade/PackHarnessMesh.kt` (generated: the meshes and the numbers the renderer poses the moving parts by; change the
  script, not the Kotlin). Its `Rig` also takes the layer's own springs (starts, stops, turns, a knock, a step taken) as optional
  arguments, each eased off where the animal leaves the load no room. It checks its own numbers, that nothing moving goes through
  the animal, the straps or the blanket (the springs pushed to their limits among the poses), that the animal's own neck goes no
  further than 2 px into the still shells over its look (a llama from 20 degrees up to 60 down, a donkey from 45 up to 60 down),
  that nothing floats and what it wrote. With a preview dir it renders the animals beside the vanilla ones from four sides, the
  four fills, the six colours, the springs pushed past their limits, the head tipped up and down, and walking and
  start-turn-stop GIFs.
- `scripts/laden_pack.py` is the three back-packs of the laden look, in the player's own space: a blanket roll and a sack (25
  Marks), an ash frame with two sacks, a cup and a lit lantern (100), and an oak ladder-frame with sacks, a keg and two pots that
  clink (250). Each level is a layer carrying the player model's own part names (so `PlayerModel` can stand on it), with every
  loose part (sacks, tufts, rope ends, lantern, cup, pots, lid, the frame's crown) a named part of its own. It writes
  `textures/entity/trade/laden_pack.png` and `laden_pack_glow.png` (the lantern's lit glass), `art/models/laden_pack.bbmodel` and
  `client/trade/LadenPackMesh.kt` (generated: the three meshes, the numbers and a `Rig` that poses every part from the walk;
  change the script, not the Kotlin). It checks its own numbers, that nothing goes through the wearer over a hundred poses,
  that nothing floats and what it wrote. With a preview dir it renders the packs on a player from five sides, strides, sneaks,
  armour, cape and head-up limits, and GIFs (walking, sprinting, sneaking, the lantern and the pots close up, a pack appearing).
- `scripts/store_art.py` and `scripts/house_art.py` (with `trade_works_kit.py` and `trade_works_preview.py` beside them, on
  `works_art.py`'s materials and mesh packing) are the Carriers' Store's and the Counting House's fittings, in the works' form:
  each a block model facing north turned by its blockstate (`store_scale`, `store_name_board`, `store_door_board`, `strongbox`,
  `strongbox_shut`, `counting_frame`; `house_map`, three blocks by two, `house_name_board`, `house_bell`) with what stands still,
  and a mesh of what moves, every moving piece a named part at its own pivot: the scale's beam and pans, the boards, the
  strongbox's lid and hasp, the counting frame's 54 beads, the map's pins, threads, wagons, wheels and pennants in the six House
  colours, the bell, its clapper and rope. They write `textures/block/trade/`, `models/block/`, `blockstates/`, the Blockbench
  files (`art/models/store_*.bbmodel`, `house_*.bbmodel`) and `client/trade/StoreArt.kt` and `HouseArt.kt` (generated: the
  meshes and the functions the renderers move every piece by; change the script, not the Kotlin). No picture carries a letter:
  the boards' lettering is the renderers'. They check what they wrote (every file there, every element in range, no partly clear
  texel, the lid short of the wall, the beads clear of each other, the bell at rest by its last tick) and stop if not. With a
  preview dir they render the fittings beside the works' own and at room sizes, sheets of every picture, and GIFs drawn by the
  tables' own numbers (a weighing, the boards, the lid, the beads, the map alive, the bell rung).

Rebuild a model (needs Python 3 with Pillow and numpy), from the repo root:

```
python3 art/scripts/hollow_king.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/story_npc.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/hollow_soldier.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/fallen_warden.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 -B art/scripts/townsfolk.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 -B art/scripts/region_hollows.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 -B art/scripts/renegade.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 -B art/scripts/items_hd.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/faction_art.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/companion_art.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/ui_hd.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/creature_hd.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/vanilla_hd.py <minecraft-26.3 jar> src/main/resources/resourcepacks/guildmark_hd [preview-dir]
python3 -B art/scripts/market_board_art.py src/main/resources/assets/guildmark [preview-dir [minecraft-26.3 jar]]
python3 -B art/scripts/season_art.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/festival_art.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/cook_art.py src/main/resources/assets/guildmark [preview-dir [game assets/minecraft]]
python3 -B art/scripts/haul_board_art.py src/main/resources/assets/guildmark [kotlin-dir [art/models [preview-dir]]]
python3 -B art/scripts/trade_items.py src/main/resources/assets/guildmark [preview-dir]
python3 -B art/scripts/pack_harness.py [assets-dir [kotlin-dir [art/models [preview-dir [game assets/minecraft]]]]]
python3 -B art/scripts/laden_pack.py [assets-dir [kotlin-dir [art/models [preview-dir [game assets/minecraft]]]]]
python3 -B art/scripts/store_art.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/trade art/models [preview-dir]
python3 -B art/scripts/house_art.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/trade art/models [preview-dir]
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

- `scripts/renegade_anims.py` is Silvan Marrow's ten clips (his six spells, strike, shove, the 108-tick intro timed
  to his voice line, and the death), timed to `faction/Renegade.kt` and baked to
  `assets/guildmark/animations/renegade/`; its sheets draw his model with the Amethyst Staff in his hand. Run it
  like `enemy_anims.py`:
  `python3 -B art/scripts/renegade_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]`.
  `townsfolk_anims.py` also holds the sixteen faction clips (reading, sparring, lurking and the rest).
- `scripts/companion_anims.py` is the four companions' eleven clips, baked to `assets/guildmark/animations/companion/`
  for the townsfolk model (role `companion`): `sit_fire` (a loop) and `stand_up` at a Wardfire, `downed` (ticks 16 to 28 are
  its held loop) and `revive`, the upper-body `approve` and `disapprove` that play over a walk, `bow_release` (added over the
  vanilla bow pose) and an idle each (`idle_bram` rests the greatsword on a shoulder, `idle_ysolde` swings the censer and
  blesses, `idle_kestrel` scans and tests the string, `idle_lucan` reads his book). It imports `animkit.py` and
  `townsfolk_anims.py`, draws every clip with the item the companion really holds (Bram's iron greatsword, Ysolde's iron
  censer, Kestrel's vanilla bow, Lucan's amethyst staff, placed as the item layer does) and checks it against the body, the
  look's own pieces (the pauldron, the knife) and the ground, every loop's seam and which bones the upper-body clips touch. With
  no clip named it also writes `companion_moves.gif`, all eleven in a row (set `GUILDMARK_VANILLA_BOW` to the vanilla client's
  `textures/item/bow.png` to draw the real bow). Run it like `renegade_anims.py`:
  `python3 -B art/scripts/companion_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]`.
- `scripts/festival_anims.py` is the grand festivals' fourteen townsfolk clips (`fest_*`, played by the activities in
  `townsfolk/TownRole.kt`): the dance steps (the two ring steps, stepping in, the couples' turn, the twirl, the bow and
  the hurrah, each one 40-tick bar of the 120-beat music, ending where it began), the crowd clapping on the beat, the
  band's minstrel, hanging the decorations, and the Lanternrise's lantern walk, raise, release (let go at tick 22) and
  gaze. It builds on `townsfolk_anims.py` and writes only its own clips; it also prints the ribbon hands' table that
  `client/festival/FestivalOrnamentRenderer.kt` ties the Ribbonwake's ribbons to (`RIBBON_HANDS`), and where the
  released lantern is (`FestivalSite.LET_GO_*`), so paste those in when a step changes:
  `python3 -B art/scripts/festival_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]`.
- `scripts/cook_anims.py` is the cook's own clips for Hearthcraft's stations, made in the manner of `ship_anims.py`: the
  player's seventeen third-person clips (`cook_add`, `cook_feed`, `cook_stir`, `cook_taste`, `cook_ease`, `cook_skim`,
  `cook_serve`, `cook_serve_peel`, `cook_chop`, `cook_flip`, `cook_turn`, `cook_carve`, `cook_slice`, the `cook_shiver` loop,
  and `eat_spoon`, `drink_bowl` and `eat_bite`), baked to `assets/guildmark/animations/player/`, and the ten that move the held
  item in first person, baked to `animations/first_person/`, with the generated `client/cooking/CookGrips.kt` those ten turn
  the item about. Each third-person clip is a body style (lean, twist, crouch, feet, head) and the reaches of the two arms at
  a stand-in station drawn from the cook blocks' own boxes, fitted by a least-squares solve and read back with the game's own
  chain; the script prints what it checked (nothing of her or her tool through the station, the working end on its mark, her
  arms clear of her body, the loop's join, the first-person item in view all through) and exits 1 if a line is off. Its
  header, which it prints when run with no arguments, holds the clip table and the ticks things happen at (the JSON carries
  no marks). With a preview dir it also draws five GIFs of all 27 clips (`player_*.gif`; a clean run is about three minutes
  without them, seven with them on a busy machine):
  `python3 -B art/scripts/cook_anims.py src/main/resources/assets/guildmark [preview-dir [steve.png]]`.
- `scripts/cook_clips.py` is the cook's seven townsfolk clips (`stir`, `taste`, `flip`, `turn_spit`, `slice`, `carve`, `peel`)
  with their props' tracks and moments, built on `living_anims.py`'s kit and imported at the end of it like the works' and the
  streets' clip scripts, so running `living_anims.py` writes them to `assets/guildmark/animations/townsfolk/` and into
  `client/townsfolk/LivingSchedule.kt` with the rest. The `turner` and the cook's knife (`cooks_knife`) they hold are built in
  `living_props.py` and packed into the model by `townsfolk.py` (which rewrites `TownsfolkMesh.kt`), after every older cube.
  The stations it checks against are drawn to the numbers of Hearthcraft's own block scripts (`cook_stewpot.py`, `cook_hob.py`,
  `cook_spit.py`, `cook_oven.py`, `cook_helpers.py`), each placed where the kitchen scenes stand the keeper (`NEAR`, 0.12 of a
  block in from the cell's middle; the cookshop's hob at the middle), and every number a clip leans on is a named constant there.

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

## Islands

- `scripts/island_gull.py` is the island gull: its model, its three textures (herring gull, black-backed gull and
  a mottled young one, HD on one atlas), `client/island/GullModel.kt`, `models/gull.bbmodel` and the previews.
  GullModel.kt is GENERATED: edit the scripts, never the Kotlin. The parts are in `island_gull_geo.py`, the
  painting in `island_gull_paint.py`, and what the bird does in `island_gull_anim.py`, written once in the small
  Python subset that `island_kit.py` turns into `setupAnim`; `island_gull_previews.py` draws the GIFs with the very
  same functions. `--model` makes the assets and the Kotlin only, `--previews` only the pictures, and `--check`
  compiles the Kotlin and compares its poses with the Python ones.
  Run it with `python3 -B art/scripts/island_gull.py` from the repo root.
- `scripts/island_art.py` is the islands' small art: the map icons and compass marks of the island kinds, the gull
  egg and the seared egg, the gull nest (four egg counts) and the lighthouse lamp, lit and unlit, with their models
  and blockstates, and the lighthouse's beam textures.
  Run it with `python3 -B art/scripts/island_art.py [assets dir [preview dir]]`.
- `scripts/island_lamp_flare.py` is the glare of the lighthouse lamp's lens (`textures/entity/island/lamp_flare.png`).
- `scripts/island_blockview.py` draws a block model the way the game does, to look at one without the game:
  `python3 -B art/scripts/island_blockview.py <assets dir> <model name> [out.png]`.

## Shader pack

Guildmark's own shader pack for Iris is in `src/main/resources/shaderpack/Guildmark/`. `shaderkit/` checks it and
renders previews of it without the game: see `shaderkit/README.md`.

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
- `scripts/mill_art.py` is the mills' turning parts, which `client/mill/` draws on the hubs the capitals' plan places: a
  windmill's sails (a timber frame in its city's wood, one texture a style, and white canvas the game dyes with the
  sail's cloth: spread, furled into a roll, or torn on a broken sail whose outer half hangs from where it broke) and a
  watermill's wheel (eight spokes a side and sixteen paddles). It writes the textures (`textures/entity/mill/`),
  `client/mill/MillMesh.kt` (GENERATED: edit the script, never the Kotlin), the three `.bbmodel` files and previews;
  `--check` compiles the Kotlin and compares the quads Minecraft draws, and where they sample the texture, with the
  Python ones. Its bottom faces take their texture as Minecraft lays it, back to front like the top's (modelkit's
  `world_faces` lays the bottom the other way round).
  Run it with `python3 -B art/scripts/mill_art.py [--check] [assets dir [preview dir [models dir]]]`.
- `lang/` is how the four living-world designs (Hearthcraft, Trade, Tales, Villages; `living-world/build-plan.md`
  section 6) add language and sound lines. The game's `en_us.json` and `sounds.json` (`src/main/resources/assets/guildmark/lang/`
  and `src/main/resources/assets/guildmark/`) are output: no part edits either by hand. A part writes its lines into a
  fragment of its own (`lang/<design>_<part>.json`; a design's subtitles in `audio/<design>_lang.json`, its sound events
  in `audio/<design>_sounds.json`) and `lang/merge_lang.py` adds them to the end of the two files. It refuses a line the
  files already have with other words, a repeated key and bad JSON, and a second run changes nothing (`--check` only says
  what a run would do). `lang/check_lang.py` reads the Kotlin for the keys under each design's prefixes and lists every one
  `en_us.json` lacks.
  Run them with `python3 -B art/lang/merge_lang.py [--check]` and `python3 -B art/lang/check_lang.py [cook|trade|tales|villages]`.
