# Guildmark

A NeoForge mod for Minecraft 26.3. Villagers post procedurally generated quests on bulletin boards: fetch items, hunt mobs, clear areas. Take a note, finish the job, earn Guild Marks and level up.

## Status

Early prototype. Villages get a 3x2 Quest Board near their bell. During the day villagers walk up and pin notes; unclaimed notes come down at the start of each new day. Click a note to read it, take it as a Contract, finish it, and spend Guild Marks at the Guildmaster. Each villager posts jobs that fit their profession, and villages further from spawn ask for more and pay more. Finished contracts give guild XP; each guild level earns a perk point to spend in the Guild Ledger (G). Contracts also fund village Advances (sneak and use a board): lamp posts, then a palisade wall, then an archer tower that shoots monsters. Ops can test with `/guildmark advance <points>` near a board.

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
- `type`: `fetch` (item), `hunt` (mob) or `clear` (any hostile mobs near the board; no target).
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
