Put the original game files here. Everything in this folder except this README is gitignored.

- Flash: `original/<name>.swf`
- Director/Shockwave: a folder `original/<name>/` with the movie and cast files

## Known sources

| goes in | source | SHA-1 |
|---|---|---|
| `original/ttbattleblitz/` | `ttbattleblitz.zip` from [archive.org/details/ttbattleblitz](https://archive.org/details/ttbattleblitz) — **the game** | `b41c01eeedcef3c707decef5a52ffeff7de93dd5` (zip) |
| `original/internal.swf` | [archive.org/details/teen-titans-battle-blitz-game](https://archive.org/details/teen-titans-battle-blitz-game) — site hub/menu, not the game | `115ee2ee7c4a808fe66719039419c73ead8ac90b` |

From `ttbattleblitz.zip`, copy only the files below into `original/ttbattleblitz/`. Skip
`Projector.exe`, the `.dll`s and `Xtras/`: they aren't needed for decompiling, and the repack
added third-party Xtras.

```
game.dcr  gamewrapper.dir  char_*.cct  empty_*.cct  lingo.ini  Projector.ini
```

For example, with bash:

```bash
unzip -j ttbattleblitz.zip 'ttbattleblitz/*.dcr' 'ttbattleblitz/*.cct' 'ttbattleblitz/*.dir' 'ttbattleblitz/*.ini' -d original/ttbattleblitz
```
