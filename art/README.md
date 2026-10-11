# Art sources

Entity models and textures are generated from the Python scripts in `scripts/`, so geometry, texture
and Kotlin model code always agree.

- `scripts/modelkit.py` describes models as parts and box-UV cubes in Minecraft model space, packs the
  UVs, paints the textures, writes the Kotlin `LayerDefinition`, renders previews and saves a
  `.bbmodel` you can open in Blockbench.
- `scripts/hollow_king.py` is the Hollow King (main story final boss).
- `scripts/story_npc.py` is the named characters: Wren, Tobin, Ilsa, Maren and Pip share one model and each
  shows only their own pieces.
- `scripts/shard_art.py` is the crown shard item texture.

Rebuild a model (needs Python 3 with Pillow and numpy), from the repo root:

```
python3 art/scripts/hollow_king.py src/main/resources/assets/guildmark <preview-dir> art/models
python3 art/scripts/story_npc.py src/main/resources/assets/guildmark <preview-dir> art/models
```

Each run writes the textures into the mod's assets, the `.bbmodel` into `art/models`, and a preview
PNG and the model's Kotlin body (`*.kt.txt`) into `<preview-dir>`. Paste that body into
`createBodyLayer()` of `client/model/HollowKingModel.kt` or `StoryNpcModel.kt`.

The `.bbmodel` files are for looking at and tweaking in Blockbench. If you change a model there, export
it as a Modded Entity and copy the geometry back into the Kotlin model by hand.
