# FetchPets

A Paper/Spigot plugin that teleports your tamed pets to you from **anywhere** on the
server — including pets sitting in **unloaded chunks** that no vanilla `/tp` command
can reach.

[Download on Modrinth](https://modrinth.com/plugin/fetchpets)

## The problem it solves

When a chunk unloads, the entities inside it stop ticking. To a normal command your
pet simply *doesn't exist* until you physically walk back into render distance —
impossible if you don't know where it wandered off to. That's an engine limitation,
not a syntax problem.

FetchPets works around it by reading the world's entity data directly off disk,
finding every pet you own, and pulling back only the specific chunks those pets are
in. No "force-load the entire world" lag.

## Commands

| Command | Description |
|---|---|
| `/fetchpets` | Teleport all your tamed pets to you |
| `/fetchpets list` | List your pets and where they are — **without** teleporting |
| `/fetchpets <player>` | *(op)* Teleport another player's pets to them |
| `/fetchpets list <player>` | *(op)* List another player's pets |
| `/fetchpets reload` | *(op)* Reload the config |

Aliases: `/fetchdogs`, `/comepets`, `/callpets`.

## Permissions

| Node | Default | Grants |
|---|---|---|
| `fetchpets.use` | everyone | Fetch and list your own pets |
| `fetchpets.others` | op | Act on other players' pets + reload |

## Supported pets

Wolves, cats, parrots, horses, donkeys, mules, llamas — and any modded pet that uses
the standard owner tag. Sitting pets are stood back up so they actually follow you home.

## Configuration

```yaml
settings:
  stand-up-sitting-pets: true   # stand sitting pets up so they follow you
  include-loaded: true          # also sweep loaded worlds, not just disk
  cooldown-seconds: 5           # per-player cooldown (0 = off)
```

## Building

Requires JDK 21+. The Gradle wrapper is included, so no local Gradle install is needed:

```
./gradlew build
```

The jar lands in `build/libs/`.

Compiled against the Spigot 1.17 API and targeted at Java 16 bytecode, so a single
jar runs on Minecraft 1.17 through 26.3.

## Reporting bugs

Open an issue on this repository. Please include your server version, the plugin
version, and the full console error if there is one.

## License

MIT — see [LICENSE](LICENSE).
