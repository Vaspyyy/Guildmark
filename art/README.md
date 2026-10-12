# Art sources

Entity models and textures are generated from the Python scripts in `scripts/`, so geometry, texture
and Kotlin model code always agree.

- `scripts/modelkit.py` describes models as parts and box-UV cubes in Minecraft model space, packs the
  UVs, paints the textures, writes the Kotlin `LayerDefinition`, renders previews and saves a
  `.bbmodel` you can open in Blockbench.
- `scripts/hollow_king.py` is the Hollow King (main story final boss).
- `scripts/story_npc.py` is the named characters: Wren, Tobin, Ilsa, Maren and Pip share one model and each
  shows only their own pieces.
- `scripts/hollow_soldier.py` is the Hollow Soldier, a risen foot soldier of the Hollow King's army (the
  `tabard` flap sways; sword and shield come from the held-item layer). Its glow map is just the two eyes.
- `scripts/fallen_warden.py` is Halvard, the Fallen Warden (boss, rendered at 1.5x), with a `cloak` and a
  `greatsword`. It writes two glow maps: `_glow` (phase one, the eye slit) and `_glow2` (phase two, eyes,
  armour cracks, chest sigil and sword runes). Cracks and runes are exact marker colours in the base texture.
- `scripts/shard_art.py` is the crown shard item texture.

Rebuild a model (needs Python 3 with Pillow and numpy), from the repo root:

```
python3 art/scripts/hollow_king.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/story_npc.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/hollow_soldier.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/fallen_warden.py src/main/resources/assets/guildmark <preview-dir> art/models
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

## Other

- `scripts/spawn_eggs.py` recolours vanilla's zombie spawn egg into the two new spawn eggs.
- Sound effects, music and Halvard's voice: see `audio/README.md`.
