# RedSteelCore

A NeoForge **1.21.1** faction mod built on the Aetherium Framework. Two factions —
**Самураи** (Samurai) and **Коммунисты** (Communists) — fight for territory and
essence through the Anomaly Core: claim a chunk, let it accrue, come back and collect.

## Features

- **Factions & territory** — claim chunks with the Anomaly Core; hostile factions are
  kept off your ground.
- **Asymmetric progression** — Samurai *Focus* (an honourable-kill meter that resets on
  any damage taken) and Communist *Kinetic Resonance* (a mining combo).
- **Faction-restricted items** — an item may be reserved for one faction; the rule is
  enforced on use, attack, mining, placement and block interaction.
- **Server administration** — a keyboard-navigable in-game settings editor **and** a full
  `/redsteel` command tree (config, items, territory, players). Edits made in the GUI are
  sent to the server, validated, and broadcast back.
- **Always-on HUD** — faction, essence, your progress meter, the owner of the ground under
  you, and a warning when you are holding an item you may not use.

## Building

```
./gradlew clean runSelfTest verifyJar packageForGame
```

The build resolves the Aetherium Framework from your local Maven repository and produces a
single universal jar. Released artifacts are protected (obfuscated + integrity-manifested)
by default; pass `-PrscNoShield` for a readable development build.

## Licence

Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>.
Licensed under the **GNU AGPL-3.0-or-later** — see [LICENSE](LICENSE), [NOTICE](NOTICE) and
[AUTHORS](AUTHORS). Verifying the authenticity of a copy is described in
[SECURITY.md](SECURITY.md).
