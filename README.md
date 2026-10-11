# Guildmark

A NeoForge mod for Minecraft 26.3. Villagers post procedurally generated quests on bulletin boards: fetch items, hunt mobs, clear areas. Take a note, finish the job, earn Guild Marks and level up.

## Status

Early prototype, played and tested in-game by hand.

- **Boards and contracts.** Villages get a 3x2 Quest Board near their bell. During the day villagers pin notes that fit their profession (fetch, hunt, clear, deliver, escort, champion, lair); unclaimed notes come down each new day. Take a note as a Contract, finish it, and turn it in for Guild Marks. Press H for the contract tracker.
- **Progression.** Contracts give guild XP and perk points for the Guild Ledger (G). Adventurer ranks F to S are earned through rank trials at a guild hall; notes are rated by rank.
- **Villages.** Each village remembers your standing (Stranger to Hero). Contracts fund village Advances (sneak and use a board): lamp posts, a guild hall with a receptionist, a Trade Road to the nearest village, a palisade and an archer tower. Roads carry caravans and travellers, and bandits ambush them.
- **Danger.** Lair hunts send you to a monster den with a boss, loot and a torn page of lore. At night, villages you have built up can be besieged by monster waves; lose and the village loses some of what it built.
- **Your own guild.** Found a guild at a hall receptionist (E rank, 50 Marks), then sneak and use a villager in a village that trusts you to swear them in. Members follow you and fight beside you; use one to make it hold or follow.
- **Named characters.** Wren, Tobin and Sister Ilsa (each with their own model) turn up in villages that know you, each with a three-chapter story that ends in lore and a keepsake.
- **Relics.** Lairs, vaults, champions, won sieges, the Hollow King and Sealed Relic Caches drop relics: rolled weapons and armour from Common to Legendary, each with a name, enchantments and affixes (lifesteal, thunder, frost, execute, warding, bramble and more). Caches come from contract pay (always from rank trials) and from the Guildmaster.
- **Main story: The Hollow Crown.** Wren's story ends with the first of seven crown shards and a compass that points to the next. Six courtiers of the Hollow King guard the rest in vaults, each a boss fight with its own tricks, unlocked as your rank grows. Halfway through you meet Maren, the Last Warden. With all seven, the compass leads to the Hollow Throne and a three-phase fight against the Hollow King. Win and you become a Warden.

Ops can test with `/guildmark advance <points>`, `/guildmark note <type>`, `/guildmark standing <points>`, `/guildmark rank <letter>`, `/guildmark level <n>`, `/guildmark siege`, `/guildmark traffic [ambush]`, `/guildmark character <id>`, `/guildmark story <shards>`, `/guildmark story tp` and `/guildmark relic <rarity>` near a board.

## Quest pools (datapacks)

Jobs come from `data/<namespace>/quest_pool/<name>.json`, so datapacks and other mods can add their own. Entries whose item or mob isn't installed are skipped.

```json
{
  "professions": ["minecraft:farmer"],
  "entries": [
    {
      "type": "fetch",
      "target": "minecraft:wheat",
      "min": 16, "max": 32,
      "reward_per": 0.12,
      "weight": 10,
      "min_tier": 1,
      "stories": ["quest.guildmark.story.farmer.fetch.0"]
    }
  ]
}
```

- `professions`: who can post these. Leave it out to let every villager post them.
- `type`: `fetch` (item), `hunt` (mob), `clear` (any hostile mobs near the board), `deliver` or `escort` (to another village; no target), `champion` (one named mob of `target`) or `lair` (a monster den built out in the wild, themed by the note's rank; no target). Single jobs use `min`/`max` 1; deliveries and escorts also pay 1 Guild Mark per 100 blocks.
- `reward_per`: Guild Marks per item or kill, on top of a base of 2.
- `weight` (default 10): how often it's picked compared to other entries.
- `min_tier` (default 1): tier 1 near spawn, 2 from 1500 blocks, 3 from 3000. Each tier raises counts by 25% and pay by 50%.
- `stories`: translation keys; one is shown on the note as the poster's reason.

## Stack

- NeoForge 26.3 with ModDevGradle
- Kotlin, stdlib bundled via Jar-in-Jar (no Kotlin for Forge dependency)
- Java 25

## Develop

```
./gradlew runClient   # launch a dev client
./gradlew build       # jar ends up in build/libs
```

Quest logic should stay free of NeoForge imports where practical, so a Fabric port stays possible later.
