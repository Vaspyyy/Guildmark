# Guildmark

A NeoForge mod for Minecraft 26.3. Villagers post procedurally generated quests on bulletin boards: fetch items, hunt mobs, clear areas. Take a note, finish the job, earn Guild Marks and level up.

## Status

Early prototype: a 3x2 Quest Board covered in placeholder notes. Click a note to read it, take it as a Contract. Quests are random placeholders until the real generator lands.

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
