# Audio sources

Every sound in `src/main/resources/assets/guildmark/sounds/` is made by these scripts. Nothing is
sampled: the effects are synthesised with numpy/scipy, the music is composed in code and played by
FluidSynth, and the voices are Kokoro TTS: Halvard processed into a hollow undead knight, the named
characters (Wren, Tobin, Ilsa, Pip and Maren) given a lighter touch, the three faction leaders each in a blend of
Kokoro voices of their own, and the renegade Silvan Marrow made cold and a little uncanny. The city ambience
(`ambience/`) is synthesised the same way, its crowds, criers and night watch built from Kokoro takes.

- `sfxkit.py`: the shared DSP toolkit (noise, filters, modal metal, creature voice source, room
  reverb, finalising and Ogg export).
- `make_sfx.py`: the 116 mono sound effects (`combat/`, `hollow_soldier/`, `fallen_warden/`, `hill_troll/`, `dusk_rider/`,
  `wardfire/`, `flask/`, `marks/`, `dungeon/` and the menu sounds in `ui/`). Each sound is one `@recipe(...)`
  function that sets its variant count, length cap and fade-out.
- `make_music.py`: the four boss tracks (`music/`) and the two stingers (`ui/`). Halvard's two tracks and
  the stingers are built on his leitmotif; the field boss theme is a piece of its own in E Phrygian, and the
  Dusk Rider's is a 6/8 gallop in D minor on a horn call of its own. Each piece is a `compose_*` function, and
  the stem balance lives in `PHASE1_MIX`, `PHASE2_MIX`, `FIELD_MIX`, `RIDER_MIX` and `STINGER_MIX`.
- `make_menu_music.py`: the title-screen theme, "Where the Road Begins" (`music/menu.ogg`), Guildmark's own
  tune in D major built on `make_music.py`'s parts, rendering and mixer. It sets its loudness by measurement,
  about 4 LU under the boss tracks.
- `make_region_music.py` and `make_land_music.py`: the regions' exploration themes (`music/region/`), one
  per kind of land, each a standalone piece with a soft opening and a real ending (the game plays it once,
  then waits minutes). `make_region_music.py` has the wild and far lands': the marsh ("Murk and Reed", D
  wavering between dorian and phrygian), the ashen highlands ("Ash and Ember", an F minor sarabande on a
  falling ground), the frozen peaks ("Snow and Silence", E aeolian under falling snow), the crystal deeps
  ("Glass and Echo", lydian, on vibraphone and celesta), the open sea ("Tide and Sail", a sea song in 6/8)
  and the windswept crags ("Wind and Stone", horn calls in 5/4). `make_land_music.py` has the common
  lands': the heartland ("Hearth and Meadow", a walking tune in D major), the wildwood ("Moss and Shadow",
  C dorian on bassoon and pizzicato), the dunes ("Sand and Mirage", E hijaz on a slow caravan drum), the
  steppe ("Grass and Sky", G mixolydian at a canter), the jungle ("Vine and Ruin", B dorian on marimba and
  pan flute) and the mushroom isles ("Spore and Lantern", a music-box waltz in F lydian). Both master to
  -17 LUFS with true peaks under -1 dBTP. The caverns and the deep dark keep the game's own music.
- `make_dragon_audio.py` and `make_dragon_music.py`: the dragon, "the Unbowed": its 15 effects and their variants
  (`dragon/`: roars, growls, snores, hurt, death, wingbeats, footfalls, bite, swipe, slam, inhale, fire stream,
  fireball, fire burst, flyby), synthesised with `sfxkit.py` and levelled to the Hill Troll's and Dusk Rider's
  loudness, and its battle theme (`music/dragon.ogg`, 3:14 in C minor, a horn call that keeps returning, its last
  chord falling back into the first bar so it loops), composed in code and played through `make_music.py`'s
  parts, stems and hall, its loudness set by measurement like the menu theme's. `make_dragon_audio.py` runs both;
  `--only` takes effect names and/or `music`. Its variants are listed in `sounds.json` by hand.
- `make_magic_audio.py`: the two schools of magic, sorcery and prayer: its 25 effects and 40 files (`magic/`). Sorcery
  is cold and glassy (struck crystal, clumped sparks, rushing air, frost crackle), prayer warm (bells and gongs struck
  soft, a small choir humming on a vowel, fire). Synthesised with `sfxkit.py` like the dragon's effects, one-shots
  peaking at -3 dBFS (the soft swells lower, the cycle tick at -12 dBFS). `--only` takes effect names; `--mp3 <file>`
  joins what was rendered into one listen-through MP3, a chapter mark at each sound. Its variants are listed in
  `sounds.json` by hand.
- `make_faction_audio.py`: the three factions' and the renegade's sounds: its 25 effects and 37 files (`faction/`).
  The Starwatch's ward-lights are cold glass like the sorcery (a hum, a shimmer where a blade passes through, a falling
  chime when one is put out, a cold mote spat); the Hearthsworn steel, gravel, brass and fire (blades tapped, a sword set
  down, a low horn, a Wardfire choked, a dawn swell and one bell stroke, a warm bell chord); the Quiet Hand cloth,
  leather, coin and a brass hand bell (a purse lifted and opened, a wrist seized, a watcher's suspicion, the alarm, a
  plucked figure and a flicked coin); the renegade stone and cold glass (the standing stones waking, a cold whisper, a
  ward cracking, a palm blast, a long fall). Synthesised with `sfxkit.py` like the magic effects, one-shots peaking at
  -3 dBFS (the ward-light's hum at -14, the lifted purse at -12). `--only` takes effect names or a group such as
  `heist`; `--mp3 <file>` joins what was rendered into one listen-through MP3, a chapter mark at each sound, and a full
  run adds the members' new work sounds from `make_townsfolk_sfx.py`. Its variants are listed in `sounds.json` by hand.
- `make_companion_audio.py`: the companions' six small cues, one file each (`companion/<name>.ogg`): two soft bells
  rising a third for approval and one low note sagging a fourth for disapproval, a body going down on one knee in its
  kit (the knee, steel and leather, a breath let out), a breath drawn in with a hand slapped into a hand and a small
  ember swell for a revive, a short questioning figure of three plucked lute notes (a companion has something to ask)
  and a warm swell of lute and low strings for the oath. Synthesised with `sfxkit.py` from the prayers' bells, the
  factions' plucked and bowed strings and the townsfolk's and combat's steel, leather and ground, in D major, the
  Wardfires' key; the cues that come often are quiet (peaks at -11 and -12 dBFS for approval and disapproval, -7 for
  the questioning figure), the fall, the revive and the oath at -4.
  `--only` takes effect names; `--mp3 <file>` joins what was rendered into one listen-through MP3, a chapter mark at
  each sound. Their events are listed in `sounds.json` by hand.
- `make_ship_audio.py`: the cog's 19 events and 40 files (`ship/`): her oak creaking, the sail filling and luffing,
  the halyard, the wake, lapping water and the wind in her rigging (the three loops), the anchor going down and coming
  up, the bell, grounding and knocks, the wheel and the hold. `--only` takes event names; `--mp3 <file>` joins what was
  rendered into one listen-through MP3, and `--fragments <dir>` writes the `sounds.json` entries and subtitles for
  merging. Its variants are listed in `sounds.json` by hand.
- `make_sea_audio.py` and `make_sea_music.py`: the sea monsters, the Gnashfins and the Maelgrasp: 31 effects and 55
  files (`sea/`: the Gnashfins' fin, leap, dive, snap, bite, gnaw, flop, hurt and death; the Maelgrasp's omens,
  bubbles, surge, bursting, rearing, slamming, sweeping and gripping arms, hurt and severed arms, the ship's timbers
  groaning, and its head rising, roaring, blinking, biting, spewing ink, diving and dying), synthesised with
  `sfxkit.py` and the cog's parts from `make_ship_audio.py` (bubbles, oak under strain, flesh and sucker squelches, a
  throat the size of a hold) and levelled by the loudest 100 ms like the cog's and the dragon's, and the Maelgrasp's
  battle theme (`music/maelgrasp.ogg`, 2:28 in F sharp minor, a heaving 6/8 on a three-note call, built as exactly one
  turn of a loop so that its last heartbeat runs into its first), composed in code and played through
  `make_music.py`'s parts, stems and hall, its loudness set by measurement like the dragon's. `make_sea_audio.py` runs
  both; `--only` takes effect names (`gnashfin_snap`, `sea.maelgrasp.roar`) and/or `music`; `--mp3 <file>` joins what
  was rendered into one listen-through MP3, a chapter mark at each file, and `--fragments <dir>` writes
  `sea_sounds.json` and `sea_subtitles.json` for merging. Its variants are listed in `sounds.json` by hand.
- `make_island_audio.py`: the islands' 28 events and 101 files (`island/`): the surf on the sand, on rock and in a cave,
  the wind (a stereo bed and six gusts), the gulls (calls, cries, alarms, wings, squabbles, splashes and a colony loop),
  the lighthouse (a clockwork loop, the fog horn and the fog bell), the wreck (groaning timbers, the torn sail, the
  drowned bell), the smugglers' cove (drips, whistled signals, the camp), the drowned shrine (a drone loop, chimes,
  bubbles, whispers), the fishing haven (boats and rope) and the discovery sting. Synthesised with `sfxkit.py`, the cog's
  parts from `make_ship_audio.py` and the harbour's gull voice, one-shots peaking at -3 dBFS and levelled by the loudest
  100 ms like the cog's, the four loops (the wind bed, the gull colony, the lighthouse clockwork and the shrine's drone,
  8 to 12 s) exact at -27 dBFS RMS; how far each sound carries is in its recipe (`reach`, 16 to 128 blocks) and in the
  script's header, with the lengths. The surf's files also have marks (`break`, `stall`, `impact`: moments in seconds a
  particle can sync to). `--only` takes names or groups (`surf`, `gull`, `island.wreck.bell`); `--mp3 <file>` joins the
  sounds into one listen-through MP3, place by place, at about their pace and levels in the game; `--variants-mp3 <file>`
  joins every file, a chapter mark at each; `--fragments <dir>` writes `island_sounds_entries.json` (the `sounds.json`
  entries, with `music.isles`), `island_subtitles.json` and `island_marks.json` for merging. Its variants are listed in
  `sounds.json` by hand.
- `make_island_music.py`: the islands' theme, "Sun and Spindrift" (`music/isles.ogg`, 2:33.6, 48 bars of A major at 75
  beats a minute): a harp, plucked strings and a wooden flute over soft strings and a light hand drum, on a tune made
  of the gull's leap of a fourth, built as exactly one turn of a loop (its last chord, E, A, B, is its first moved a
  fourth) so that it joins itself. Composed in code and played through `make_music.py`'s parts, stems and hall, its
  loudness set by measurement like the sea theme's (-18 LUFS, true peak under -1 dBTP in the encoded file). `--work <dir>`
  keeps the per-stem renders and `--reuse` mixes again from them, `--mp3 <file>` writes it to listen to, a chapter mark at
  each section and the loop's join once more at the end.
- `make_festival_music.py`: the four grand festivals' dance music (`festival/<festival>_<0-3|finale>.ogg`), the
  prize-giving fanfare (`festival/fanfare.ogg`) and the seasons' arrival stings (`season/sting_<season>.ogg`). Each
  festival has its own band and tune at 120 beats a minute, so a bar is the forty ticks a dance figure takes; a set is
  four eight-bar phrases and a finale, one file each, so the game can start each phrase on its own tick and a late
  arrival hears the next one on time. A festival's five files share one gain (the set at -17 LUFS, every file's true
  peak under -1 dBTP). `--mp3 <dir>` writes each set as the game plays it, with chapters, and the stings in a row.
- `make_voice.py`: Halvard's three lines (`voice/`). It has two stages, `tts` and `process`.
- `make_renegade_voice.py`: the renegade Silvan Marrow's three lines (`voice/renegade_<line>.ogg`), in a blend of
  three American men's voices and cold, thin processing: a faint whispered double, a stone slap and wind (out on a
  hill of standing stones, not in a hall), and a glassy shimmer on his phase-two cry. Two stages, `tts` and
  `process`, as for Halvard.
- `make_cast_voices.py`: the named characters' 344 dialogue lines (`voice/<character>/`), read from the
  language file: Wren, Tobin, Ilsa, Pip and Maren, the faction leaders Idris, Corra and Odile, and the four
  companions Bram, Ysolde, Kestrel and Lucan (240 of the lines: everything a companion says but their written notes,
  barks on the road included, each in a blend of Kokoro voices of their own, dry and close). It has three
  stages, `tts`, `process` and `sounds`.
- `ambkit.py`: what the city ambience shares on top of `sfxkit.py`: the `@recipe` registry, seamless
  stereo loops (the extra tail crossfaded over the head), RMS levelling, street and square reverbs,
  stereo panning, and waveform and spectrogram pictures for checking a sound by eye.
- `make_ambience.py`: the city ambience (`ambience/`), one recipe module per part of town in
  `ambience/`: `bells.py`, `workshops.py`, `harbour.py`, `wind.py` and `voices.py` (the crowds, criers
  and watch). The four background beds are stereo loops that play unpositioned; everything else is a
  mono one-shot the game places in the world. `village.py` is a village's (`client/ambience/VillageAmbience.kt`): five
  beds, `village_day` (two 40 s loops: the breeze in dry stalks, hens, sparrows, a far cart, a cow or sheep and rooks),
  `village_night` (two of 40 s: the air over the thatch, an owl), `village_birds` (40 s, the morning's and the evening's
  birdsong: blackbird, robin, wren, song thrush, chaffinch, a far wood pigeon and a far chorus), `village_crickets` (30
  s) and `village_market` (30 s, the townsfolk's own talk from `sounds/townsfolk/voice` handed out as the city's crowds
  hand out theirs, so it needs no `--raw`), each sound that stands out kept clear of its loop's join; and three far
  one-shots, `dog_far`, `hammer_far` and `child_far` (3 each, 1 to 2 s, carrying 80 to 96 blocks).
- `make_city_voices.py`: the raw Kokoro takes behind the crowds, criers and night watch.
- `make_mill_audio.py`: the capitals' mills, three seamless mono loops (`mill/`) the game plays by a mill while it
  turns, louder and a little higher the faster it goes (`client/mill/MillSounds.kt`): `sails` (8 s, one turn: four
  sails sweeping past the tower, the wind in the lattice, the windshaft groaning, the stocks creaking), `stones` (6 s:
  the millstones grinding inside the mill, heard through its walls, the stone nut's cogs and the damsel knocking the
  shoe) and `wheel` (8 s, one turn: sixteen paddles slapping into the stream, the water pouring off them, the churn,
  the timbers and the axle). Built in a circle with the cog's parts from `make_ship_audio.py` and set quiet by RMS
  (-30, -30 and -28 dBFS, peaks under -10). `--only` takes names; `--mp3 <file>` joins them into one listen-through
  MP3. They are listed in `sounds.json` by hand (`mill.sails`, `mill.stones`, `mill.wheel`).
- `make_lamp_sfx.py`: the lamplighters' round (`townsfolk/lamp/`), timed to their moves by
  `client/townsfolk/LivingMoments.kt` and played by the street lamps (`lamps/StreetLampBlock.kt`,
  `townsfolk/Lamplighters.kt`): `lamp.wick` (the wick at the top of the pole catching as they set out of an evening, 2
  variants), `lamp.tap` (the pole's brass end knocking on the lantern's frame as the wick goes in at its door, 3),
  `lamp.clink` (the snuffer's brass bell set down over the flame, 3) and `lamp.hiss` (the flame smothered under it,
  also a lamp put out by hand or going out by itself in sight, 2), levelled as `make_townsfolk_sfx.py`'s are; the lamp
  catching (`lamp.light`) is `make_living_sfx.py`'s. It writes `lamp_sounds.json` and `lamp_lang.json` beside itself
  (their `sounds.json` entries and subtitles), merged by hand. `--mp3 <file>` joins them into one listen-through MP3.
- `make_cook_audio.py`: Hearthcraft's kitchen (`cook/`, 45 events and 91 mono files, `guildmark:cook.<name>` in the
  order of `cooking/CookSounds.kt`; a dot in a name is a folder, `cook.plop.soft` is `cook/plop/soft1.ogg`): the pot and
  its water (`place`, `pour`, three `plop`s, `stir`, `taste`, `serve`, `skim`, `ease`), the fire and the pan (`fuel`,
  `ignite`, `crackle`, `flare`, `drip`, `flip`, `boil_over`, `burnt`), the oven's door, the spit's crank, the board and
  the larder (`oven_open`, `oven_shut`, `crank`, `chop`, `slice`, `carve`, `loaf_down`, `plate`, `chalk`, `larder`), the
  table (`eat.spoon`, `eat.bite`, `drink`, and `eat.clink`, one spoon clink for a seated diner's sip, last in the order),
  the cues (`call`, `ready`, `ripe`, the four `tier` motifs and the eater's
  `buff`; the only tuned sounds, a wooden hand-chime and a small bronze bell) and five loops: `simmer` (8 s, reach 24),
  `sizzle` (6 s, 20), `oven_hum` (8 s, 20, heard through brick), `spit_creak` (6 s, 20) and `chill` (10 s: the wind the
  frost plays to the player alone). Synthesised with `sfxkit.py`, `make_townsfolk_sfx.py`'s helpers (levelling, knocks,
  rings, the chalk) and the cog's parts from `make_ship_audio.py` (bubbles, oak, struck iron, creaks, `Ring`), each heard
  in a small hard stone room and seeded by event and file. A one-shot's loudest 100 ms is set to a K-weighted loudness of
  its own (-19.5 to -29 LUFS, the stations a little under the lamps') with its true peak under -1 dBFS, re-encoded lower
  if the encoder overshoots; the loops are built in a circle so that they join with no seam, at -27 dBFS RMS with their
  peaks (true peaks too) under -10, and each is read back after the encoder. Each recipe is an `@effect(...)` or
  `@loop(...)` carrying its file count, length, reach, subtitle and `when=` (the moment that plays it). `--only` takes
  events or groups (`stir`, `plop`, `eat`, `tier`, `loops`); `--mp3 <file>` joins what was rendered into one
  listen-through MP3, a chapter mark at each file and each loop for 12 s, and writes `cook_what_plays_when.txt` beside it;
  `--audit` renders nothing and reads every file back against the design (length, level, true peak, a loop's RMS and
  join), exiting 1 on a problem. A full run writes `cook_sounds.json` and `cook_lang.json` here (their `sounds.json`
  entries and subtitles), merged by `art/lang/merge_lang.py`.
- `make_trade_sfx.py`: the trader's sounds (`trade/`, 37 events and 84 mono files, `guildmark:trade.<name>` in the order
  of `trade/TradeSounds.NAMES`; `trade.hoist_creak` has no file, its `sounds.json` entry is an event that points at the
  granary's `works.hoist`): the board and the Tally (a stamp, a wax seal, a ticket peeling, a sand glass, the Tally opened,
  a page turned, a notch cut, a slip filed), a haul delivered (`knock`, one tally-stick knock for each ten units, and a
  crate set down), carrying (the bell on the pack animal in three strides and a trot, the harness buckled, the drover's
  two whistles, the clink of pots on a laden back, the Chapman's Cart's creak as a seamless loop, the shafts lifted and
  dropped, the canvas flapping, the tailboard, a rein slack, the wagon's brake), the Hunt (the outlaws' horn that carries
  80 blocks, `hunt_shout` as a bark, a laugh and a snarl, a fanfare, a sack heaved up, a spear butt on stone) and the
  ladder (the Warrant's seal, the Store's key, its scale settling, the strongbox, the counting frame's beads, the bell over
  the Counting House's door, a carter's call) and the street's cheer. Synthesised with `sfxkit.py`, the townsfolk's helpers
  (levelling, knocks, rings, paper, a coin), the cog's (stick-slip creaks, a bronze bell's modes, oak, struck iron,
  `Ring`), the crew's (a crowd's hurrah) and the works' (stone, cloth, a door's boom), each seeded by event and file. A
  one-shot's loudest 100 ms is set to a K-weighted loudness of its own (-13 for the horn to -27 for a sand glass) with its
  true peak under -1 dBFS, re-encoded lower if the encoder overshoots, and where the code plays a sound below full volume
  the file is levelled for that (`vol=` in the recipe: the bell at 0.55 and 0.7, the clink at 0.3, the keeper's stamp, seal, knocks, crate and ticket at 0.7). `cart_creak` is built in a
  circle to the cart's own wheel (eight turns of a wheel of radius 10/16 of a block at the pulled cart's 3.67 blocks a
  second, 8.56 s: play it at pitch = speed / 3.67) at -25 dBFS RMS with its peaks under -9, and read back after the
  encoder. Each recipe is an `@effect(...)`, `@loop(...)` or `alias(...)` carrying its file count, length, reach, subtitle and
  `when=` (the moment that plays it). A sound that carries other than 16 blocks has its reach as its files'
  `attenuation_distance` and as its event's range in `TradeSounds.RANGES`. `--only` takes events (`knock,bell_step,loops`);
  `--mp3 <file>` joins what was rendered into one listen-through MP3 at the volumes the code plays them at (a chapter mark at
  each file, the loop for 12 s) and writes `trade_what_plays_when.txt` beside it; `--audit` renders nothing and reads every
  file back against the design (length, level, true peak, the loop's RMS and join, the 37 names and ranges against
  `TradeSounds.kt`, the fragments against the files and `sounds.json`), exiting 1 on a problem. A full run writes
  `trade_sounds.json` and `trade_lang.json` here (their `sounds.json` entries and subtitles), merged by
  `art/lang/merge_lang.py`. `/guildmark trade sound [name] [blocks]` (operators) plays them in game.
- `make_caravan_sfx.py`: the caravans on the roads (`caravan/`, 43 events, 119 mono files): the wagon (`roll`,
  `roll_stone` and `roll_planks` are seamless loops for a looping sound that follows the wagon's speed; creaks,
  harness bells, the brake's squeal, the canvas flapping, a crate set down), a waylaying (the band's two-note
  whistle and whoop, the wagon going over, crates smashed, sacks ripped, bottles broken, a wheel snapping), the
  draught horse (hooves on earth, stone and planks, stamps, snorts, neighs, a squeal, a fall, grazing; the Dusk
  Rider's horse from `make_sfx.py` played by a heavier animal) and the crew's moves, timed to their clips'
  moments (the tongue's click, the reins, the knife on the traces, a slap, a crate lifted and handed down, gasps,
  pants and huffs). Levelled as the townsfolk's are; it writes `caravan_sounds.json` and `caravan_lang.json` here,
  merged into `sounds.json` and `en_us.json` by hand.
- `make_village_audio.py`: the living villages' own sounds (`village/`, 7 events and 23 mono files,
  `guildmark:village.<name>` in the order of the villages design's table 17.3, which `village/live/VillageSounds.kt` keeps):
  `door_bolt` (an iron bolt shot home, the door's clack), `hush` (two seamless loops of people whispering behind a
  bolted door: the townsfolk's own talk with the voice taken out, cut into syllables, heard through the planks), `cheer` (a
  village's cheer, 6 to 12 voices with children among them, and clapping), `candle_lay` (wax on stone, a match, the wick
  catching), `pedlar_bell` (a small brass handbell, brighter than `works.handbell`), `baby` (a baby cooing, one take
  fretful) and `rooster` (four birds crowing from a coop across a yard). Synthesised with `sfxkit.py`, the townsfolk's
  helpers, the homestead's crowd and the cog's parts, seeded by event and file, levelled as the cook's are (a one-shot's
  loudest 100 ms from -14 to -23 LUFS, true peak under -1 dBFS; the loops at -30 dBFS RMS, peaks under -10, built in a
  circle and read back after the encoder). Each recipe carries its files, lengths, reach, subtitle and `when=`. A full
  run writes `villages_sounds.json` and `villages_lang.json` here (the seven events' `sounds.json` entries and subtitles,
  and those of the village ambience that `ambience/village.py` makes), merged by `art/lang/merge_lang.py`. `--mp3 <file>`
  joins the files on disk into one listen-through MP3 (the seven, then each village bed for a minute and the far
  one-shots) and writes `village_what_plays_when.txt` beside it; `--audit` renders nothing and reads every file back
  against the table (length, level, true peak, a loop's or a bed's join, the reaches against `VillageSounds.kt`, the
  fragments against the files and `sounds.json`), exiting 1 on a problem.

All randomness is seeded, so a rerun gives bit-identical files. Only `make_cast_voices.py sounds` and
`make_ambience.py --sounds` touch `sounds.json`, each rewriting only its own entries. For everything
else, if you add or remove a variant, update that file by hand.

## Requirements

- Python 3 with numpy and scipy, plus mido for the music.
- `ffmpeg` with libvorbis, and `sox`.
- `fluidsynth` with `/usr/share/sounds/sf2/FluidR3_GM.sf2` (use `--sf2` to point elsewhere).

These versions were tested: Python 3.13, FluidSynth 2.3.4, SoX 14.4.2 and ffmpeg 6.1.

## Run (from the repo root)

`-B` stops Python from writing a `__pycache__` folder into `art/audio`.

```
python3 -B art/audio/make_sfx.py src/main/resources/assets/guildmark [--wav <dir>] [--only combat/parry,fallen_warden/]
python3 -B art/audio/make_music.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>] [--only boss_appear]
python3 -B art/audio/make_menu_music.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>]
python3 -B art/audio/make_region_music.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>] [--only marsh,ashen]
python3 -B art/audio/make_land_music.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>] [--only heartland,dunes]
python3 -B art/audio/make_dragon_audio.py src/main/resources/assets/guildmark --work <tmp-dir> [--wav <dir>] [--only roar,growl,music]
python3 -B art/audio/make_magic_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only arc_bolt_cast,flare] [--mp3 <file>]
python3 -B art/audio/make_faction_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only ward_light,heist.alarm] [--mp3 <file>]
python3 -B art/audio/make_ship_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only creak,wake] [--mp3 <file>] [--fragments <dir>]
python3 -B art/audio/make_sea_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only gnashfin_snap,music] [--mp3 <file>] [--fragments <dir>] [--theme-mp3 <file>]
python3 -B art/audio/make_island_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only surf,gull,island.wreck.bell] [--mp3 <file>] [--variants-mp3 <file>] [--fragments <dir>]
python3 -B art/audio/make_island_music.py src/main/resources/assets/guildmark [--work <tmp-dir>] [--wav <dir>] [--mp3 <file>] [--reuse]
python3 -B art/audio/make_mill_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only sails,wheel] [--mp3 <file>]
python3 -B art/audio/make_lamp_sfx.py src/main/resources/assets/guildmark [--wav <dir>] [--only lamp.clink,lamp.hiss] [--mp3 <file>]
python3 -B art/audio/make_cook_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only stir,plop,loops] [--mp3 <file>] [--audit]
python3 -B art/audio/make_trade_sfx.py src/main/resources/assets/guildmark [--wav <dir>] [--only knock,bell_step,loops] [--mp3 <file>] [--audit]
python3 -B art/audio/make_caravan_sfx.py src/main/resources/assets/guildmark [--wav <dir>] [--only roll,whistle] [--mp3 <file>]
python3 -B art/audio/make_village_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only rooster,hush] [--mp3 <file>] [--audit]
python3 -B art/audio/make_festival_music.py src/main/resources/assets/guildmark [--work <tmp-dir>] [--only ribbonwake,stings,fanfare] [--mp3 <dir>]
```

`--wav` also writes WAV copies for listening. `--work` is where the music script keeps its per-stem
MIDI and WAV renders; it defaults to `build/music_work`. A full music render takes about a minute.

### City ambience

```
python3 -B art/audio/make_ambience.py src/main/resources/assets/guildmark --raw <raw-dir> [--only bell_,gull] [--modules bells,harbour] [--wav <dir>] [--png <dir>]
python3 -B art/audio/make_ambience.py src/main/resources/assets/guildmark --sounds
```

`--only` takes sound names or prefixes, `--modules` the recipe modules to load. `--png` draws each
sound's waveform and spectrogram, the quickest check of a loop's seam or a bell's ring. `--raw` is the
folder of speech takes from `make_city_voices.py` (below); without it the crowds, criers and watch are
skipped and keep their files. The second command rewrites the `ambience.*` entries of `sounds.json`
from the files on disk: `EVENTS` in the script sets how far each sound carries and its subtitle. Run it
after adding or removing a sound or a variant. A village's sounds need no `--raw`:
`--modules village --only village_,dog_far,hammer_far,child_far` makes just them, in about forty seconds (`village.py`
loads `wind.py` and `voices.py`, so without `--only` the wind's sounds are made again too, bit-identical, and the
speech ones skipped); their entries also go through `make_village_audio.py`'s fragment.

The city's speech uses the same virtualenv, model files and `--espeak-data` as the voice lines below:

```
<venv>/bin/python -I -B art/audio/make_city_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--only hawker_,watch_] [--espeak-data <dir>]
python3 -B art/audio/make_ambience.py src/main/resources/assets/guildmark --raw <raw-dir> --modules voices
```

None of the criers or the watchman uses a voice the named characters or Halvard speak with.

### Voice lines

1. Set up the TTS in a virtualenv: `pip install kokoro-onnx soundfile`. This was tested with kokoro-onnx 0.6.1.
2. Download the model files into a folder of their own:
   - https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx
   - https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin
3. Run the two stages:

```
<venv>/bin/python -I -B art/audio/make_voice.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir>
python3 -B art/audio/make_voice.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark
```

The defaults are voice `bm_lewis` at speed 1.0. `--voice` also takes a blend such as
`bm_george*0.6+bm_lewis*0.4`.

espeak-ng refuses a data path longer than 160 characters. If the `tts` stage fails with
`Error processing file .../phontab`, copy the `espeak-ng-data` folder from the `espeakng_loader`
package to a short path and pass it as `--espeak-data <dir>`.

Silvan Marrow's lines (`make_renegade_voice.py`) run the same way, `tts` then `process`. His voice is the blend
`am_liam*0.4+am_fenrir*0.3+am_santa*0.3`, read as American English (the American voices turned "dark" into "dock"
and "chart" into "shot" when given British phonemes), and he speaks up, 1 to 2 dB under Halvard's level. Whisper
base.en reads all three lines back exactly as written.

The game times its captions to the voice files' lengths:

| File | Length |
|---|---|
| `halvard_intro` | 8.802 s |
| `halvard_phase2` | 4.356 s |
| `halvard_death` | 4.521 s |
| `renegade_intro` | 5.478 s |
| `renegade_phase2` | 3.234 s |
| `renegade_death` | 2.720 s |

A new voice, speed or text changes these lengths. Check the new lengths with `soxi -D` and update the
caption timings in the code.

### Character voices

The dialogue screen speaks every line it shows: introductions, each chapter's request, the "still
waiting", "all done" and goodbye lines, the topics, Maren's scenes, and a leader's "work" offer, the grumble when
woken and the words for a rival or an expelled member. The words come from
`en_us.json`, so after changing a character's line, voice it again. Use the same virtualenv, model files
and `--espeak-data` as for Halvard:

```
<venv>/bin/python -I -B art/audio/make_cast_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--only tobin] [--keys '^intro$']
python3 -B art/audio/make_cast_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--only tobin] [--keys '^intro$']
python3 -B art/audio/make_cast_voices.py sounds --assets src/main/resources/assets/guildmark
```

`--only` takes character ids and `--keys` a regex on the line key (`step1`, `topic.maps.0`, `scene0.p2`).
`sounds` adds a `voice.<character>.<line>` event for every recorded line; a line without a recording
falls back to the typed-text ticks in game.

| Character | Kokoro voice | Speed | Processing |
|---|---|---|---|
| Wren | `af_heart` | 1.06 | small room |
| Tobin | `bm_george` | 0.9 | down 1 semitone, a little grit, small room |
| Ilsa | `bf_emma` | 0.94 | chapel reverb |
| Pip | `bm_fable` | 1.06 | small room |
| Maren | `af_bella` | 0.9 | down 0.6 semitone, a faint chorused octave above, large cold hall |
| Idris | `bm_daniel*0.6+am_echo*0.4` | 0.8 | down 0.6 semitone, a slight tremor, a little grit, a library (books soak up the highs) |
| Corra | `bf_isabella*0.7+bm_george*0.3` | 1.0 | down 2 semitones, a stone barracks with a bright tail |
| Odile | `bf_alice*0.55+af_nicole*0.45` | 0.92 | down 1 semitone, a velvet top, a small close attic |

A voice may be a blend of Kokoro voices (`name*weight+name*weight`, as `--voice` takes for Halvard). The three
leaders' blends are theirs alone: no other line in the game is read with them. `.note` lines are written notes,
never spoken, and the "gated" line is not voiced.

`SAY_AS` in the script respells words the TTS gets wrong ("Hmph" was spelled out letter by letter, and
"Halvard" came out as "Harvard"). Every line was checked with Whisper base.en: 98% of words come back
as written (99% for the leaders' 49 lines, 42 of them exactly).

### Townsfolk

The sounds the city's people make (`townsfolk/`): their work and play, the bard's tunes, and what they
say as you pass.

- `make_townsfolk_sfx.py`: the 81 effects (31 events and their variants, the factions' mages, knights and
  thieves among them, and the town crier's chalk, notice and scroll), each timed to a townsfolk animation, and the
  bard's four lute tunes (`townsfolk/bard/tune1..4`). The effects are close-up and dry
  apart from a small room (the crier's are out in the square, with its slap off the house fronts instead).
  Each is set to a loudness target (its loudest 100 ms, K-weighted) instead of by
  peak: the smith's hammer is the loudest at -12 LUFS, the soft sounds sit at -17 to -18, and peaks stay under
  -1 dBFS. `@effect(...)` on each recipe sets its variants, subtitle, target and carry distance. The tunes
  are composed in the script and played by FluidSynth's General MIDI nylon guitar, with a second string a
  few cents sharp for the lute's paired courses and a small synthesised frame drum in the dances. They are
  levelled to -18 LUFS integrated.
- `make_townsfolk_voices.py`: the 527 voice lines (`townsfolk/voice/<timbre>/<line>`) in four Kokoro voices, 108 of
  them what the three factions' members say in the street (`mage_`, `knight_`, `thief_`, `alarm_`, `suspect_`, `calm_`
  and `purse_` lines, in the man's, the woman's and the old man's timbres) and 226 the town crier's news (`crier_`
  lines, in the man's and the woman's).
  It also writes the two fragment files the lead merges into `sounds.json` and `en_us.json`:
  `townsfolk_sounds.json` and `townsfolk_lang.json`.

```
python3 -B art/audio/make_townsfolk_sfx.py src/main/resources/assets/guildmark [--wav <dir>] [--only smith.hammer,tavern.,tunes] [--work <tmp-dir>]
<venv>/bin/python -I -B art/audio/make_townsfolk_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--only man,child] [--keys '^greet'] [--espeak-data <dir>]
python3 -B art/audio/make_townsfolk_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--only man] [--keys '^greet'] [--wav <dir>]
python3 -B art/audio/make_townsfolk_voices.py fragments
```

`--only` takes event names without the `townsfolk.` prefix, a group prefix such as `tavern.`, or `tunes`.
`--work` holds the tunes' MIDI and FluidSynth renders (default `build/townsfolk_music_work`). The whole
effects script takes under a minute, and the `tts` stage a few minutes. The voices script's `--only` takes
timbres and `--keys` a regex on the line name. A `phonemes` stage (the `tts` arguments without `--out`)
prints what Kokoro will say for each line after `SAY_AS`. `fragments` rewrites both fragment files from the
two scripts and warns about any voice line that has not been rendered.

| File | Tune | Length |
|---|---|---|
| `tune1` | "The Copper Kettle", a double jig in D, 6/8 | 37.1 s |
| `tune2` | "Over the Market Square", a reel in G | 41.9 s |
| `tune3` | "The Lantern by the Water", a slow air in A minor, 3/4 | 39.1 s |
| `tune4` | "The Guildhall Hornpipe", a hornpipe in A | 44.4 s |

Each tune starts on its first note and ends on a chord that dies away to silence, so they can play back to
back.

| Timbre | Kokoro voice | Speed | Processing | Lines |
|---|---|---|---|---|
| `man` | `am_puck` | 1.0 (the crier 0.88) | street (the crier: the square) | 223 |
| `woman` | `af_nova` | 1.12 (the crier 0.97) | street (the crier: the square) | 223 |
| `old` | `am_adam` | 0.86 | down 1.2 semitones, a slight tremor, a little grit, street | 75 |
| `child` | `af_jessica` | 1.1 | up 3 semitones, street | 6 |

All four read British phonemes (`en-gb`), as the city's criers do. None of them is a voice used by the named
characters, Halvard, the criers or the watch. Old women use `woman`, and the game varies the pitch a little
per townsperson. Each line plays as `townsfolk.voice.<timbre>.<line>` and is subtitled with its own text
(`say.guildmark.<line>`). Line lengths do not matter: nothing in the game is timed to them, so a line can be
voiced again freely (the town crier's are the exception, below). `SAY_AS` respells "Hmph" (otherwise spelled out
letter by letter) and "Hmm" (otherwise a weak, swallowed sound), and puts a comma where a line breathes when the recogniser lost a word without it.
`PHONEMES` gives the IPA outright for the eight lines no respelling fixed: the American voices turn the British
long "a" of "stars", "guards" and "hearth" into an "o" ("stalls", "gods", "Hawthorne") unless the r is sounded,
and `alarm_0` ("Thief! Stop, thief!") is written out so that its lead-in can be. `PHONEMES_FOR` gives a line other
phonemes for one timbre only (the woman's and the old man's `alarm_0`, whose first "Thief" came out as "Thieve"
with a plain "f"). Kokoro also lets the first word of a take go (an opening "th" is almost silent), so `LEAD_IN`
has a throw-away "oh" read in front of such a line and the `tts` stage cuts it off again where its vowel ends
(`cut_lead_in`, with an 8 ms fade-in): "Thief" then starts as it would in the middle of a sentence.
The processing notches out thin whistles that Kokoro's vocoder leaves at 4.8 and
9.6 kHz. It sets every line to the named characters' speech level, with cries 1 to 2 dB above it and
mutters 1 to 3 dB below. The first 193 lines were checked by their phonemes, speaking rate, pitch and spectrograms,
the 108 faction lines also with Whisper base.en (the encoded files, with a retry when its decoder loops on a word):
107 of them come back exactly as written. The one that does not is the man's `knight_kin_0` ("Hearth and road,
friend." is heard as "Harthing Road, friend"); the larger fp32 model of Whisper also hears the old man's `alarm_0`
as "Thieve, stop thief".

The town crier's 113 lines (`CRIER`) are in the man's and the woman's voice only: an old crier borrows the man's a
little lower, as old men do with every line their own voice lacks, so there are no `old` takes. They are the
everyday voices crying instead of talking (`process_crier`): read slower (`CRIER_SPEED`, 0.88 and 0.97), each take
cut into its calls where the voice rests between them, each call's last stressed vowel drawn out by a fifth and the
last call's by a third (`CRIER_DRAW`, with `ambience/voices.py`'s WSOLA) and at least 0.36 s left between calls, then
projected (less chest, +3.5 dB around 2.6 kHz, a gentle 2.5:1 compressor) and set in the square: a slap off its stone
fronts (34 to 283 ms, `CRIER_SLAP`) and a faint open tail ducked under the words. Every crier line sits at -19 LUFS
(within 0.3), about 2.3 LU over the everyday lines' average, with true peaks under -1 dBTP, and its `sounds.json`
entry has `attenuation_distance` 48 so the whole square hears it. The server gives each line 100 ticks, so none may
run over 4.5 s: the longest are the man's and the woman's `crier_fever`, 3.7 and 3.8 s. `CRIER_SPEED_FOR` gives a
line its own speed (only the woman's `crier_festival_soon` and `crier_blight_carrot`, a little quicker, which Whisper
heard better). `CRIER_PHONEMES` puts right, in espeak's British reading, what Whisper base.en heard wrong: "Oyez" is
read oh-YEZ (heard as "Oh yes", as criers say it) and "murrain" MURR-in; the r is sounded where the American voices
lost or bent a vowel without it ("dear", "are", "short", "board", "pork", "banners", "bumper", "iron"); and as both
voices say a short vowel a step lower than written, "Bread" is read with the vowel of "bid" (else "Brad") and the
hens in "among the hens" likewise. `CRIER_PHONEMES_FOR` has a line's fixes for one timbre (the man's "Bring, in the
harvest", else "bringing the harvest"). With them Whisper base.en hears 105 of each voice's 113 lines word for word
(98.3% of the man's words, 98.8% of the woman's; the fp32 model 98.0% and 98.8%). What it still mishears is a single
word in a short line: "Cod" as "Caught" (the man's both, the woman's cheap), the woman's "Beetroot" as "Beat wrote",
"pest" as "past", "murrain" as "Maran" (a word it does not know), and once each "Flint" as "Flynn", "Coal" as "Call",
"Mutton" as "Martin", the man's "Wheat is dear" as "We is dear", "hens are laying" as "hands" and "want of flour" as
"one of flour", and the woman's "ovens" as "oven". The 226 takes were also checked for clicks (none) and clipping
(none).

`make_festival_voices.py` voices the grand festivals' 38 townsfolk lines (`fest_*`: the host's calls through the day,
the helpers putting up the decorations, what dancers and the crowd say to a player, and the prize-giving) the same way,
in the man's, the woman's and the old man's timbres (a child borrows the woman's, pitched up). Calls are read a little
quicker, 35 cents higher and 2 dB over the speech level, with a touch more of the square's reverb. `PHONEMES` sounds the
r of "targets", "archers", "start", "harvest", "barns", "dark", "lantern" and "answers" for the reason above. Checked
with Whisper base.en on the encoded files: 105 of the 114 come back exactly as written, and the rest are heard as
near-homophones ("Mind the latter, who's got the penance?", "Harvest in", "Upper bit"). Its `sounds` stage prints the
`sounds.json` entries.

```
<venv>/bin/python -I -B art/audio/make_festival_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--timbres man] [--keys chat] [--espeak-data <dir>]
python3 -B art/audio/make_festival_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
python3 -B art/audio/make_festival_voices.py sounds
```

### Stalls and shipping

`make_stall_audio.py` makes the sounds of a player's own stall in a capital's market hall and of a homestead's
shipping: 7 events and 16 files (`stall/`, and `carter_wheels`, `carter_load` and `purse` in `homestead/`).
`stall.rent` is a week's rent paid (coins set down on the counter, the hall's rent book stamped and shut),
`stall.set_out` goods going on the table (a crate set down and slid into line, produce tipped out of a sack and
rolling, a sack set down and patted into shape), `stall.till` the till (a handful of coins dropped in, the coins
scooped out, a single coin), and `stall.pennant` the renter's pennant unrolling over the table, its cords creaking
and a soft snap as it falls taut (the game plays it higher as one rolls up). `homestead.carter.wheels` is the
carter's handcart on the road, played every 16 ticks while it moves so each overlaps the next,
`homestead.carter.load` a sack, a crate or a basket heaped onto the cart, and `homestead.purse` the Charter Post's
purse emptied and the Marks counted out into its owner's hand. They are synthesised with `sfxkit.py` and
`make_townsfolk_sfx.py`'s parts, in the hall's small room or out in the street, and levelled as the townsfolk
effects are: the loudest 100 ms at -16 to -21 LUFS (K-weighted), peaks under -1 dBFS. The pennant and the wheels
carry 24 blocks, the purse 12. `--preview <file>` writes every sound in a row as an MP3 and `--entries <file>` their
`sounds.json` entries.

`make_stall_voices.py` voices the 16 lines the hall's merchant and the carter say (`stall_*` and `carter_*`) in the
man's, the woman's and the old man's timbres, through `make_townsfolk_voices.py`'s voices and processing as
`make_festival_voices.py` does. No line holds a name or a number: those are in the chat line beside it. The carter's
call up the road is read a little quicker, 35 cents higher and 2 dB over the speech level; the heave as a sack goes on
and the word to a player who stops the cart are a little quicker and 20 cents higher. `PHONEMES` sounds the r of "yours"
and "Carter" (else "yaws" and "Cotta") and gives the man's "Off" and "Folks" their American vowels (else "of" and
"Though"). Checked with Whisper base.en on the encoded files (`check`): 38 of the 48 come back word for word, 97.2% of
the words; the rest are homophones and spellings ("That's a good wait", "Write the markets waiting", "The markets got
plenty of that") and a stray "(b" or "(buzzer" Whisper adds after the woman's `stall_well_0` and `stall_gone_0` as
their room tail dies away. `preview` writes every line in the three voices as one MP3 and `sounds` prints the
`sounds.json` entries. Neither script touches `townsfolk_sounds.json` or `townsfolk_lang.json`.

```
python3 -B art/audio/make_stall_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only stall.till,homestead.purse] [--preview <file>] [--entries <file>]
<venv>/bin/python -I -B art/audio/make_stall_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--timbres man] [--keys carter] [--espeak-data <dir>]
python3 -B art/audio/make_stall_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
<venv>/bin/python -I -B art/audio/make_stall_voices.py check --asr <sherpa-onnx-whisper-base.en dir> --assets src/main/resources/assets/guildmark
python3 -B art/audio/make_stall_voices.py preview --assets src/main/resources/assets/guildmark --mp3 <file>
python3 -B art/audio/make_stall_voices.py sounds
```

### Orders and standing

`make_order_voices.py` voices the capitals' orders and supplier standing. A business's keeper has 18 lines (`order_`):
an order to be had, taken, filled or part filled, let lapse or given up; a standing order offered or turned down; goods
refused for having come off their own stall; and a supplier's rise to each rank. They are in the man's, the woman's and
the old man's timbres, as everyday speech. The town crier has 25 order cries: `crier_wanted_<good>` for each of the 22
goods a business can ask for, `crier_order_filled_0` and `_1`, and `crier_board_orders`. They are in the man's and the
woman's voices, cried through `make_townsfolk_voices`'s own delivery (`process_crier`), with the lines joining `CRIER`
for the run. No line says a name or a number.

Every line is read from espeak's British phonemes, with `PHONEMES` put right:
- The r of "order", "short", "mark", "more", "here", "sooner", "our", "purveyor", "suppliers" and "iron" is sounded,
  as in the crier's "dear".
- "of" takes the vowel of the crier's "little of it".

`PHONEMES_FOR` holds a line's fixes for one timbre. The man breathes after "Bring" in "Bring, in your catch" (else
"bringing your catch", as in the crier's harvest line) and after "Wheat" in "Wheat, wanted!". Two lines were reworded
because no reading was heard right: "Wool wanted" always came out as "We'll want it", so the crier says "Wool is
wanted!", and "An order filled" as "In order filled", so it is "An order is filled!".

Checked with Whisper base.en on the encoded files: 101 of the 104 come back exactly as written (99.6% of words). The
three that don't are "Purveyor", a word it doesn't know, heard as "Perveyor". The fp32 model also hears the woman's
"Cod" as "Codd" and the old man's "A known supplier" as "Unknown supplier". The keeper's lines average -21.1 LUFS
against the everyday lines' -20.8. The crier's sit at -19 LUFS within 0.3, and the longest runs 3.6 s. All have true
peaks under -1 dBTP. The townsfolk fragments are not rewritten; the `sounds` stage prints the `sounds.json` entries.

```
<venv>/bin/python -I -B art/audio/make_order_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--timbres man] [--keys crier] [--espeak-data <dir>]
python3 -B art/audio/make_order_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
python3 -B art/audio/make_order_voices.py sounds
```

### Trade voices

`make_trade_voices.py` voices the trader's life (living-world/trader/design.md section 10): 94 lines in 205 takes, and
the trade's ten talk families in 86 more. The hall's merchant (`trade_board` to `trade_warrant`: the board's calls, the
Tally, a report, a booking, and the harness, donkey, cart, wagon and Warrant sold at the counter), the Store's clerk,
the Counting House's scribe and carters and the gate guards (`trade_clerk_*`, `trade_scribe_*`, `trade_carter_*`,
`trade_gate_house`), a Hunt's outlaws and a hired guard are in the man's and the woman's timbres; the keeper at the
counter (`trade_delivered`, `_relief`, `_part`, `_street`, `_firstcall`) and the street's passers-by and cheer are in the
old man's too. The crier's 12 (`crier_hauls_*`, `crier_house`) are the man's and the woman's, join `CRIER` for the run
and are cried through `process_crier`, at -19 LUFS, none over 4.5 s, heard 48 blocks off. None of the lines says a name,
a number, a good or a place. The outlaws' "Lost them!" and "Gone to ground. Back to the camp." do not take the player
for a man.

`DELIVERY` sets a family's speed, pitch and level (dB over the speech level) and how much of the street's reverb it
has. What is called across a hall, a square or a yard (the board's calls, the street's, the scribe's and the carters',
the gate's) is a little louder and has a little more of it; the cheer is 60 cents up and 3 dB over. The outlaws are
rough: mostly 40 to 80 cents lower (the rout 20 higher), with grit and half the reverb, as on the open road; their calls
and blows are 2.5 to 3 dB over, "Wise. We'll take our due." said low. A hired guard's greeting is steady and their warning
shouted; a carter robbed is slow, low and 1 dB under. Three families of outlaw lines are shouted across a distance, so
their `sounds.json` entries carry them further than the default sixteen blocks (at which a call "at twelve blocks"
would reach the player at a quarter of its volume): the leader's `trade_hold_*` and the curse after a player who got
away, `trade_lost_*`, 32 blocks; the rout, `trade_flee_*`, 24 (`REACH`). A caller gives `Townsperson.say` a `hearing`
of at least as much, since it says a line only while a player is within it (20 by default).

Every line is read from espeak's British phonemes with the r's of its American reading sounded (as the talk is), then
`PHONEMES` and the line's own `PHONEMES_FOR` put right what Whisper heard wrong, each with a comment saying what it
heard: a hard or stressed vowel or consonant ("Fit" heard "With", "Let" "lit", "has" "is", "Some" "So"), a letter
the voice swallowed ("Fresher" heard "Fresh", "Booked" "Book to", "Counted" "Count it", the scribe's "A wagon" "wagging"),
a pause the comma didn't give ("Bless the road, that brought it", "Off, off!" read with the long vowel), and
"short of" heard "sure to" in the man's `trade_scribe_float_0` until a breath after "of" parted it from "float". The
man's "Hurrah" is read "hurray" in full phonemes (`FULL`) and set 2.2 dB louder than its family (`LEVEL_FOR`), where it
measured 2.2 LU under. `SPEED_FOR` slows two of the man's outlaw lines ("Wise" ran into "we'll", "stand" into
"still").

The talk families are `trade_talk.py`, a module of its own that one block in `make_talk_voices.py` (after `PHONEMES`)
enters into `GROUPS`, `DELIVERY`, `SPEED_FOR`, `SAY_AS` and `PHONEMES`; they are recorded and processed by
`make_talk_voices.py` (`tts` and `process` with `--keys '^talk\.trade\.'`) and `table` writes their entries and
`TalkLines.kt`. The man, the woman and the old man say the 28 wordings of nine families (what is heard of a town's
shortage, glut and big delivery; a guard's word of the roads and a merchant's of the board; the town's thanks, a trader
of repute, a player robbed and a trader robbed), the child the two of hers. Four wordings differ from the design's: "Heard that a
carrier saved a town's shelves, not long ago." and "That was good carrying. We noticed." (the design says "last
week", which a delivery of yesterday's would make untrue), "The cart came!" (the design's "The cart man" takes the
trader for a man) and "Oh, the carrier!" (Kokoro's "Ah" before "the" was heard "All", "Are" and "or", cut off on its
own too, and "Oh" is heard right in all three voices). The old man's `trade.board_1` ran 4.54 s at his pace, over the
4.0 s limit, and is read at 1.24 times it.

Checked with Whisper base.en on the encoded files (`check`): of the 291 takes 263 come back word for word and 28 right
but for a homophone, a spelling or a contraction, none wrong (98.4% of the words of the 205, 99.4% of the 86). The 28
are "Hall Board" heard "Haul Board" (the crier's six), "hall's" "halls" (`trade_warrant_0`, two), "Honour" "Honor" (two),
"Hurrah" "hooray" (the three `trade_cheer_0`), "Mind" "mined" (`trade_sell_cart_0`, two), "chalked" "chocked" (three; to
the American voices they are one sound), "Wagon's" "Waggons", "strongbox" "strong box" (three), "we're" "were" (two),
"they're" "they are", "trader" "traitor" (three; both are flapped) and a "(buzzing)" Whisper adds after the woman's
`trade_scribe_float_0` as its room tail dies away. They are listed in `HOMOPHONES` and `loosely`. The recogniser's venv
(onnxruntime 1.29) and the recording venv (1.31) read the same phonemes a little differently, so a fix tried in one is
only known good once the take is recorded again and checked: do that with `tts --force --keys`. `loudness` reads each
group against the everyday lines' -20.9 LUFS and its own level: the hall's -20.7, the keeper's -20.6, the street's -19.6
with the cheer, the outlaws' -18.8, the guard's -20.4, the Store's -20.8, the crier's -19.1 and the talk's -20.9 on
average (the worst take 1.0 LU off its family's level), and no true peak is over -1.0 dBTP. The longest non-talk take is
3.7 s and the longest talk take 3.93 s. `listen` writes every take in turn, each line in each of its voices, as one MP3
and a text file of what is said when. In the game, `/guildmark trade say [key]` (`trade/TradeSayCommand.kt`; operators)
has the townsperson you look at say the 94 lines in turn, or one line, or every line that starts so, in their own voice,
with the key and the words in chat; `/guildmark talk family trade.short` and the other nine say a talk family's.

```
<venv>/bin/python -I -B art/audio/make_trade_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--timbres man] [--keys '^trade_board'] [--force] [--espeak-data <dir>]
python3 -B art/audio/make_trade_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
<venv>/bin/python -I -B art/audio/make_trade_voices.py phonemes --model ... --voices ... [--timbres ...] [--keys ...]
<venv with sherpa_onnx>/bin/python -I -B art/audio/make_trade_voices.py check --asr <sherpa-onnx-whisper-base.en dir> --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--json <file>]
python3 -B art/audio/make_trade_voices.py loudness --assets src/main/resources/assets/guildmark [--keys ...]
python3 -B art/audio/make_trade_voices.py listen --assets src/main/resources/assets/guildmark --out <dir> [--timbres ...] [--keys ...]
python3 -B art/audio/make_trade_voices.py fragments   # trade_voices_sounds.json and trade_voices_lang.json, then art/lang/merge_lang.py
```

### Talk voices

`make_talk_voices.py` voices the townsfolk's talk (living-world/design-voices.md section 5, `townsfolk/talk`): what the
capital's people say on the talk screen, in passing and to each other, as 183 families of wordings that fit what is
really going on (greetings by the hour and by how well they know you, farewells, the screen's own lines, the daily
round, the trades at work, market talk, the town's doings, the weather and the season, deeds and dealings, news
lead-ins and the news after them, replies, the household, the old man's own and the children's). A family is one
meaning said one to four ways, each a whole sentence that is true whenever the family is picked: its takes are
`talk.<family>_0`, `_1`, ... (366 keys). What changes (a name, a good, a price, a place, a time) is never voiced: it
follows the take as text. Where a name belongs, the family has `.named` texts (`say.guildmark.talk.<family>_<n>.named`,
seven families): the take says "There you are!" and the screen shows "There you are, Ransom!". The script also voices
the keepers' 98 stall lines that name nothing (`economy/StallTalk.kt`'s `say.guildmark.market.*`, read from
`en_us.json` as written; a line's detail, `market.detail.*`, follows it as text), in the man's and the woman's voices, and its entries carry the five talk-of-the-town lines
the crier cries, which `make_townsfolk_voices.py` records with the rest of `CRIER` (`crier_talk_*`).

The man and the woman say every family but the children's and the old man's own; the old man says those old folk say
most (greetings and farewells, the screen's own, the daily round, market talk, the town, the weather, the lead-ins,
the replies and the household, and his own three lines: 210 takes) and borrows the man's voice lower for the rest;
a child has 18 of their own and borrows the woman's. With the crier's ten, 1,124 takes. A take may last 2.5 s for a
greeting, a farewell, the screen's own lines and the replies, 4.0 s for other talk and the keepers' lines, 1.8 s for a
lead-in and 3.0 s for the news after it (as processed, the street's tail included): `process` refuses a take over its
limit and writes nothing for it. `DELIVERY` sets a family's speed, pitch and level (the lead-ins a little quicker;
"Can't stop!" over a shoulder and louder; the sleepy, the hushed and what's said in confidence 3 to 4 dB lower), and
`SPEED_FOR` a take's own speed (the man's `news.assault_0`, which rushed).

All four voices are American, and `make_townsfolk_voices.py` gives them espeak's British phonemes. Whisper heard
nearly a third of the man's lines wrong that way, mostly where a vowel's r goes unsounded ("Zappa" for "Supper", "shot"
for "short", "farge" for "forge", "mock it" for "market", "Dia" for "dear"), which the crier's and the festival's lines
put right a word at a time. Here every line is read as espeak's British reading with the r's of its American reading
sounded, word by word (`rhotic`: the British vowels stay, and with them the voices the townsfolk already have).
`TALK_PHONEMES` then fixes a few words wherever they come ("can't", "Hollow", "one", a weak "We'll" or "for a"), and
`PHONEMES` writes out in full the lines that were still heard wrong (a stressed "at", "as", "has" or "and", a "been"
said "bin", a pause the comma didn't give, a sounded t in "can't" and "carts", "near" with the American vowel and
"hens" with a higher one; the sleepy mumbles as a hum), some for one timbre only where the others were heard right. A
few wordings were changed where no reading would do.

Checked with Whisper base.en on the encoded files (a retry when its decoder loops on a word): 404 of the man's 434
takes, 413 of the woman's 434, 202 of the old man's 210, all 18 of the children's and all ten of the crier's come back
exactly as written. The rest are heard as near-homophones ("Please to meet you", "Goodale", "Ribbon wakes", "Price is
on the board", "the forges banked", "the floor is hard", "Everyone saying"), as words it doesn't know ("murrain" as
"Maran", "Sheafmoot" as "Chief Mootzoon" or "Sheaf moots"), the sleepy hum as a word ("Do", "Remember", "Smooth"), or
with a sound lost or added in a short word ("hearth's" as "heart's", "pest's" as "pastes", "ovens" as "oven", "Oi" as
"Boy"). The keepers' nine road lines (a waylaid wagon, goods off it, and the cool greeting) came later and have not
been heard back yet. The man's takes run at a median 5.5 syllables a second, the woman's 4.8, the children's 4.75 and the old man's
4.3, with no pause inside a line longer than 0.4 s.

```
<venv>/bin/python -I -B art/audio/make_talk_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> [--timbres man,old] [--keys greet] [--force] [--espeak-data <dir>]
python3 -B art/audio/make_talk_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
<venv>/bin/python -I -B art/audio/make_talk_voices.py phonemes --model ... --voices ... [--timbres ...] [--keys ...]
python3 -B art/audio/make_talk_voices.py table [--assets src/main/resources/assets/guildmark]
```

`tts` reads only the takes not in `<raw-dir>` yet (`--force` reads them again); `--keys` is a regex on the key.
`phonemes` prints what Kokoro is given for each take. `table` writes `talk_sounds.json` and `talk_lang.json` (every
take's `sounds.json` entry, its text and `.named` text for `en_us.json`, and the crier's five lines with the square's
reach) and generates `townsfolk/talk/TalkLines.kt` from the table and the takes on disk: each family, its number of
wordings, each wording's length in ticks (the longest timbre's), whether it has `.named` texts and which timbres say
it, and `OTHERS`, the length of every other voiced townsfolk line, so that `TalkLines.ticks` knows any line a talk can
say.

### Works and streets (M6)

`make_works_audio.py` makes the sounds of M6's works going up and of its streets in good times and hard
(living-world/design-influence.md section 8): 26 events and 93 mono files in `works/` and `streets/`.
- **The building:** `works.trowel` (a stroke through mortar), `works.chisel` (a mallet on a chisel), `works.mortar`
  (the hoe turned through the tub), `works.hoist` (the rope hauled hand over hand), `works.beam_down`,
  `works.stone_set` and `works.stake` (the works' post knocked in, heavier than the homestead's).
- **The ceremonies and the works at work:** `works.cheer` (the topping out), `works.toast`, `works.unveil` (the cloth
  off the plaque), `works.handbell` (the master's, at the opening), `works.horn` (the watch's, reaching 128 blocks),
  `works.beacon_flare` and `works.beacon_loop`, `works.granary_doors` and `works.pledge` (Marks into the post's box).
- **The streets:** `streets.ladle`, `streets.pot_loop`, `streets.bowl`, `streets.queue` (the bread queue's murmur,
  cut from the townsfolk's own recorded talk and muffled so that nothing in it can be made out), `streets.awning`,
  `streets.basket`, `streets.stamp`, `streets.pat`, `streets.bundle` and `streets.handcart_goods`.

Every one-shot starts on its contact, so a clip's moment plays it on the frame it happens. The four loops are built
round, not cut and faded: their noise is made in the frequency domain to come round on itself, what happens in them is
laid on a ring, their filters run three laps and their reverb's tail is folded back, so each file is one lap with no
seam. They are levelled as the homestead's are (the loudest 100 ms at -14 to -20 LUFS, K-weighted; the loops by gain
alone, at -23 to -28) with peaks under -1 dBFS, and seeded. The script writes `works_sounds.json` and
`works_lang.json` here, merged into `sounds.json` and `en_us.json` by hand; `--mp3 <file>` joins what it rendered
into one listen-through MP3, each loop played round twice.

M6's voices are made by the scripts above:
- `make_talk_voices.py` has seven new groups at the end of `GROUPS`: the works, the works' deeds, the times, the
  household's comings and goings, the neighbours', the streets and the children's. They hold 44 families, 106
  wordings and 284 takes, which brings the talk to 227 families, 472 keys and 1,408 takes with the crier's.
  `TALK_PHONEMES` gives "granary" its "a" (else "gronary") and "mortar" both its r's.
- `make_townsfolk_voices.py` has the crier's works and times: 21 lines (`crier_works_*`, `crier_times_*`) in his two
  voices. It also has the homestead crew's 36 lines (`HOMESTEAD`, text only until now), in the man's, the woman's and
  the old man's voices, and a town works' master builder's 14 (`MASTER`, `works_master_*`), in the man's and the old
  man's.
  - The builders' lines are read as the talk is (`talk_phonemes`: espeak's British reading with the American r's).
  - "Hurrah!" is said "hooray": Kokoro reads "Hurrah" as "her eye" or "Hara".
  - `LEVEL` sets their stage calls, shortages and the master's calls 1 to 2.5 dB over the speech level, and their
    waiting and working 1 to 1.5 dB under.
  - `fragments` now leaves the crier's talk-of-the-town lines (`crier_talk_*`) to `make_talk_voices.py`'s `table`,
    which writes their entries.

Checked with Whisper base.en on the encoded files: 385 of the 462 new takes come back word for word, 97.4% of the
words. Most of the rest are homophones and spellings ("The new ovens made all the difference", "a cue for bread",
"Bow on the ridge", "Write tools out", "Morning, the cruise here") or "hooray" for "Hurrah". A few are still heard a
little wrong: "Find day for building" in all three voices, "Ground level, lads", and the woman's "with the granary".
Eleven wordings were changed where no reading would do.

The new talk sits at the existing takes' speech level (a median of -19.1 dB RMS) and the crier at -19.1 LUFS.
living-world/works-audio.md in the shared notes lists every sound and line with what plays it.

```
python3 -B art/audio/make_works_audio.py src/main/resources/assets/guildmark [--wav <dir>] [--only works.trowel,streets.bowl] [--mp3 <file>]
<venv>/bin/python -I -B art/audio/make_talk_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> --keys '^talk\.(works|fortune|comings|streets)\.|^talk\.child\.(builders|hungry|festoons)_'
python3 -B art/audio/make_talk_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark --keys '<the same>'
<venv>/bin/python -I -B art/audio/make_townsfolk_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> --keys '^(crier_works_|crier_times_|homestead_|works_master_)'
python3 -B art/audio/make_townsfolk_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark --keys '<the same>'
python3 -B art/audio/make_townsfolk_voices.py fragments --assets src/main/resources/assets/guildmark
python3 -B art/audio/make_talk_voices.py table --assets src/main/resources/assets/guildmark
```

### Village voices

The villages' voices (living-world/villages/design.md section 17.4, Villages V12c) are four groups in
`make_townsfolk_voices.py` and a module of talk families, 138 lines in 350 takes:
- **The bellman** (`BELLMAN`, 32 lines in the man's and the woman's voices, 64 takes): the cry's opening and close, the
  village's times, a project built and a crew coming, a siege, a raid, a birth, a death, newcomers, the wagon, the
  pedlar and the feast. They join `CRIER` and are cried as the crier's are (`process_crier`), at -19 LUFS, none over
  4.5 s (the longest 3.81 s), heard 48 blocks off; an old bellman borrows the man's voice lower.
- **The pedlar** (`PEDLAR`, 12 lines in the man's and the woman's, 24 takes): 17.4's ten and the two cries the game
  already says (`pedlar_call_look`, `pedlar_call_road`), heard 24 blocks off. No line calls the pedlar he or she.
- **Shelter and the watch** (`SHELTER`, 24 lines, 52 takes, each in the voices 17.4 gives it, `SHELTER_TIMBRES`; the
  child's own among them): the horn's shouts, the run, the knock and the door, the hush behind it, the all-clear and
  what was lost; the watch's calls on the wall, the cheer, the salute and the quiet night. What is shouted at the horn
  and on the wall carries 32 blocks.
- **The crews, the carters and the neighbours** (`VILLAGE`, 38 lines in the man's, the woman's and the old man's, 114
  takes): 17.4's 22 (a crew's stages and mend, the carters at the road end, the candle at the memorial stone, a birth,
  a child grown, a welcome and its reply) and the 16 lines a village's building crew already showed as text, in the
  game's words. The carters' greeting carries 24 blocks.
- **The talk** (`village_talk.py`, 20 families in 32 wordings, 96 takes in the man's, the woman's and the old man's):
  the wagon, the pedlar, the bell, the watch, a siege, the dead, a birth, newcomers, the city's word, the builders, the
  player and the days after. Like the trade's, it is a module of its own that one block in `make_talk_voices.py`
  enters after the tales'; `table` writes their entries and `TalkLines.kt`.

No line names a person, a good, a place, a count or an hour: those follow it in grey. Where 17.4's words would be
untrue at times the game says them, the line says it another way, and its comment gives the design's words and why:
the pedlar's first two calls (they named goods the cart may not have), the watch's "east wall" and 18 of the talk's
32 wordings (the wagon's hour and day, the pedlar's day, the watch's tower and night, a wall a village may not have,
the candle, the bread). `pedlar_late` and `village_stage_posts` keep the words the game already shows.

Every line, the bellman's too, is read as the talk is (`village_phonemes`: espeak's British reading with the American
r's sounded), with "Hurrah" said "hooray" as the builders' is (`VILLAGE_FIXES`). `VILLAGE_DELIVERY` gives each line's
speed, pitch, level and reverb by its longest key prefix: what is called across the green or the road end and shouted
at the horn is quicker, higher and 2 to 3 dB over, with more of the open air; the hush behind the door, the child's
fear, the candle at the stone and the crew at dinner are lower and 1 to 4 dB under. The talk's `DELIVERY` says the lost
wagon, a lost siege, the dead and the night after lower and slower, a birth brighter.

Checked with Whisper base.en on the encoded files (a scratch checker, as the trade's `check`): the first reading had 40
of the 350 takes heard wrong, nearly all of them short shouts in the man's voice. `VILLAGE_FIXES_FOR`, `VILLAGE_FULL`,
`VILLAGE_SPEED_FOR`, a lead-in for `welcome_2` and the talk's `PHONEMES` put them right, each with a comment saying what
was heard: the American a of "wagon", "at", "stand", "Glad" and "Pass" (else "wagging", "come a first light", "with
sand", "Glide"), the American o of "Bolt", "Hold" and "Posts" (else "both", "All", "Post her in"), a long "in" (else
"end"), a sounded t (else "slight them well", "weed off"), a pause the punctuation didn't give, and eight of the man's
takes read a little slower. "All quiet on the wall" was heard "and the walls" in every reading of the man's and is "All
quiet on this wall." Now all 350 come back right: 294 word for word and 56 but for a homophone, a spelling or a
contraction ("peddler" for "pedlar", "hooray", "wagons" for "wagon's", "neighbors", "Guildhall", "do" for "due"). Each
group sits at its level: the bellman's -19.1 LUFS, the pedlar's -20.0, shelter and the watch -20.6 (-25.7 for the hush
to -17.5 for the horn), the crews' -20.4 and the talk's -20.8 to -21.7, against the everyday lines' -21.0; no take is
more than 1.4 LU off its level or over -1.0 dBTP, and none but the bellman's over 4.0 s. In the game, `/guildmark talk
family village.cart.due` (and the other nineteen) has the townsperson you look at say a family's wordings, and
`/guildmark talk say bellman_open` any one line in their voice.

```
<venv>/bin/python -I -B art/audio/make_townsfolk_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> --keys '^(bellman_|pedlar_|shelter_|watch_(wall|cheer|salute|quiet)|village_|cart_(greet|count|late)|memorial_lay|born_|grown_up|welcome_)'
python3 -B art/audio/make_townsfolk_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark --keys '<the same>'
<venv>/bin/python -I -B art/audio/make_talk_voices.py tts --model <dl>/kokoro-v1.0.onnx --voices <dl>/voices-v1.0.bin --out <raw-dir> --keys '^talk\.village\.' [--force]
python3 -B art/audio/make_talk_voices.py process --raw <raw-dir> --assets src/main/resources/assets/guildmark --keys '^talk\.village\.'
python3 -B art/audio/make_townsfolk_voices.py fragments --assets src/main/resources/assets/guildmark   # then art/lang/merge_lang.py
python3 -B art/audio/make_talk_voices.py table --assets src/main/resources/assets/guildmark             # then art/lang/merge_lang.py again
```
