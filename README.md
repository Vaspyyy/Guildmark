# Guildmark

A NeoForge mod for Minecraft 26.3. Villagers post procedurally generated quests on bulletin boards: fetch items, hunt mobs, clear areas. Take a note, finish the job, earn Guild Marks and level up.

## Status

Early prototype, played and tested in-game by hand.

- **Getting started.** New players get the Adventurer's Handbook on first join (craft another from a book and a Guild Mark). The Guildmark advancement tab tracks 31 milestones, from your first contract to the Hollow King.
- **Boards and contracts.** Villages get a 3x2 Quest Board near their bell. During the day villagers pin notes that fit their profession (fetch, hunt, clear, deliver, escort, champion, lair), or news from the village itself: rebuilding after a lost siege, a feast after a won one, the monsters of their land, a den in the hills, or a rival guild that won't help. Unclaimed notes come down each new day. Take a note as a Contract, finish it, and turn it in for Guild Marks. Press H for the contract tracker.
- **Progression.** Contracts give guild XP and perk points for the Guild Ledger (G). Adventurer ranks F to S are earned through rank trials at a guild hall; notes are rated by rank. Milestones grant titles (Lairbreaker, Roadwright, Warden of the Hollow Crown and 16 more) to wear after your name in chat, the tab list and over your head: `/title`.
- **Villages.** Each village remembers your standing (Stranger to Hero). Contracts fund village Advances (sneak and use a board): lamp posts, a guild hall with a receptionist, a Trade Road to the nearest village, a palisade, an archer tower, then a market square (new villagers and trades), stone walls with gate arches (smaller sieges) and a three-storey keep. Villages grow from hamlet to village, town and city. Each village holds a festival one day in seven (the Harvest Fair, Lantern Night, Founders' Day or the Midsummer Feast): contracts there pay a quarter more, villagers celebrate, a wandering trader visits and fireworks go up at dusk. Roads carry caravans and travellers, and bandits ambush them. Coaches run along finished roads: `/coach` (or the Coach button on the Advance screen) lists where they go, for a fare of about a Guild Mark per 60 blocks; nearby party members and following guild members ride along.
- **Danger.** Lair hunts send you to a den with a boss, loot and a torn page of lore: spider dens, bandit camps with a wanted chief, barrows, witch hollows and ashen pits, varied even at the same rank. Some nights (about one in four, configurable), a village with at least its lamp posts that you're in at dusk is besieged by monster waves; lose and the village loses some of what it built.
- **Your own guild.** Found a guild at a hall receptionist (E rank, 50 Marks), then sneak and use a villager in a village that trusts you to swear them in. Members follow you and fight beside you; use one to make it hold or follow.
- **Holding villages.** A guild leader with Honored standing in a village that has its Guild Hall can raise the guild's banner there (sneak and use the board). Held villages pay tribute when you visit, pay you 10% more for contracts, and call your guild's members to the bell when besieged. Neglect them or lose sieges and they throw the banner down. Every village now has its own name.
- **Rival guilds.** The Iron Hounds, the Scarlet Ledger, the Gilded Stag and the Ashen Crows (each with their own look) raise banners over villages you've found, send adventurers who loiter at boards and take notes, and take a 10% cut of contract pay in their villages. They lean on villages you hold until one challenges you: beat their captain at the bell within two days (the contract tracker counts down and points the way) or lose the village. Challenge a rival's captain from the Advance screen to take their village.
- **Parties.** `/party invite <player>` starts a party of up to four. Members close together share kills for contracts, get a quarter of each other's contract pay on top of their own, can't hurt each other, and see each other's health and distance under the contract tracker.
- **Named characters.** Wren, Tobin, Sister Ilsa and Pip Larkspur the bard (each with their own model) turn up in villages that know you, each with a three-chapter story that ends in lore and a keepsake. Pip's keepsake is a written ballad of your deeds, and afterwards he sings a verse about the grandest thing you've done.
- **Relics.** Lairs, vaults, champions, won sieges, the Hollow King and Sealed Relic Caches drop relics: rolled weapons and armour from Common to Legendary, each with a name, enchantments and affixes (lifesteal, thunder, frost, execute, warding, bramble and more). Caches come from contract pay (always from rank trials) and from the Guildmaster.
- **Main story: The Hollow Crown.** Wren's story ends with the first of seven crown shards and a compass that points to the next. Six courtiers of the Hollow King guard the rest in vaults, each a boss fight with its own tricks, unlocked as your rank grows. Halfway through you meet Maren, the Last Warden. With all seven, the compass leads to the Hollow Throne and a three-phase fight against the Hollow King. Win and you become a Warden.

Anyone can use `/party`, `/coach` and `/title`. Ops can test with `/guildmark advance <points>`, `/guildmark note <type>`, `/guildmark standing <points>`, `/guildmark rank <letter>`, `/guildmark level <n>`, `/guildmark siege`, `/guildmark traffic [ambush]`, `/guildmark character <id>`, `/guildmark story <shards>`, `/guildmark story tp`, `/guildmark relic <rarity>`, `/guildmark claim`, `/guildmark festival` and `/guildmark rival <take|challenge|spawn|daily> <guild>` near a board.

## Config

Per-world settings live in the world's `serverconfig` folder (`guildmark` file): contract pay and XP multipliers, the relic cache chance, and switches for auto-placed boards, the handbook, sieges and their chance, bandits, rival guilds and the named characters.

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
