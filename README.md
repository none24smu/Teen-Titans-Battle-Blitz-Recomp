# Teen Titans: Battle Blitz — Recomp

Tooling to decompile **Teen Titans: Battle Blitz** (Cartoon Network, 2003) into editable
source, as groundwork for recompiling or porting it to other platforms.

| title | engine | decompiled to |
|---|---|---|
| **Battle Blitz** (the game) | Macromedia Director 8 / Shockwave | unprotected `.dir`/`.cst` movies plus **375 Lingo scripts (~31.7k lines)** |
| Teen Titans site hub (`internal.swf`) | Flash 6 / ActionScript 1 | AS source, full SWF XML, SVG/PNG/MP3/TTF assets, Animate XFL, rebuildable SWF |

**No game files are included.** The games, their art, sound and code belong to Cartoon
Network / DC Comics. You supply your own copy, and everything extracted from it stays in
gitignored folders (`original/`, `src/`, `build/`).

## Requirements

- Windows: PowerShell 5.1+ (built in). Linux/macOS/Git Bash: bash, curl, unzip.
- Java 11+ (`java` on PATH, or `JAVA_HOME` set), for the Flash decompiler.
- ProjectorRays, for Director games. `setup` downloads the Windows build automatically. On
  Linux/macOS, [build it](https://github.com/ProjectorRays/ProjectorRays) and set
  `PROJECTORRAYS=/path/to/projectorrays`.

## Usage

1. Put the original files in `original/` (see [original/README.md](original/README.md)):
   - Flash: `original/<name>.swf`
   - Director/Shockwave: a folder `original/<name>/` holding the `.dcr`/`.dir`/`.cct`/`.cxt`
     files. For Battle Blitz that's `original/ttbattleblitz/`.

2. Run the pipeline. Every step takes optional names and defaults to everything in
   `original/`.

   ```powershell
   scripts\setup.ps1       # download FFDec 26.3.0 + ProjectorRays 0.2.0 into tools/ (checksum-verified)
   scripts\decompile.ps1   # original/ -> src/
   scripts\build.ps1       # src/<name>/ -> build/<name>.swf   (Flash)
   scripts\verify.ps1      # check src/ and build/ against original/
   ```

   ```bash
   scripts/setup.sh && scripts/decompile.sh && scripts/build.sh && scripts/verify.sh
   ```

   If PowerShell refuses to run the scripts, use
   `powershell -ExecutionPolicy Bypass -File scripts\decompile.ps1`.

## What decompile produces

**Director game** (`original/ttbattleblitz/` → `src/ttbattleblitz/`):

```
movies/game.dir, gamewrapper.dir     unprotected movies: open and edit in Director 8.5+
movies/char_*.cst, empty_*.cst       unprotected casts (fighter art, sounds)
movies/game/casts/<cast>/*.ls        Lingo source, one file per script
movies/game/casts/<cast>/*.lasm      Lingo bytecode listing (reference)

assets/<movie or cast>/              everything else, in open formats (no Director needed):
  labels.json                        frame labels -> frame numbers (movies)
  score.json                         the timeline (movies): per frame the frame script, tempo,
                                     transition, sounds, palette and every sprite (member, loc,
                                     size, ink, blend, flags); plus each sprite span's frames,
                                     behaviors (with parameters) and name
  <castLib>/cast.json                every member: number, name, type, size, registration
                                     point, bit depth, palette, and the files below
  <castLib>/NNNN_<name>.png          bitmaps; 1-8 bit ones as indexed PNGs with their palette
  <castLib>/NNNN_<name>.wav          sounds
  <castLib>/NNNN_<name>.act          palettes (Adobe Color Table) + .palette.json
  <castLib>/NNNN_<name>.txt          text members
  <castLib>/NNNN_<name>.filmloop.json film loops: their own timeline, same format as score.json
```

Every file and field is described in [docs/FORMATS.md](docs/FORMATS.md). The asset export is done by `scripts/extract/DirectorExtract.java` (plain Java, no
dependencies). Bitmap pixels and palette colours were checked against dirplayer for every
bit depth the game uses (1, 2, 4, 8, 16, 32); frame labels, frame scripts, sprite positions,
blend values and span behaviors against the running game. Not exported yet: shape members
and the one Xtra member, which remain in the `.dir`.

`verify` re-decompiles the unprotected files and checks the Lingo comes out identical,
compiles every script back with the project's own Lingo compiler and checks it gives the
original bytecode byte for byte (all 375), and checks that no member failed to export.
How the compiler works and what it fixes in ProjectorRays' output: [docs/LINGO.md](docs/LINGO.md).
Architecture notes: [docs/BATTLE_BLITZ.md](docs/BATTLE_BLITZ.md).

**Flash SWF** (`original/internal.swf` → `src/internal/`):

```
swf.xml     every tag as XML. Lossless: build turns it back into the SWF
scripts/    ActionScript source; build recompiles it
pcode/      original bytecode as readable P-code (reference)
assets/     shapes (SVG), sprites/buttons/frames (PNG), sounds (MP3), images, fonts, texts
xfl/        Adobe Animate project
```

`verify` checks that every frame of `build/internal.swf` renders identically to the original
and that the recompiled scripts decompile back to the same source. Notes:
[docs/STRUCTURE.md](docs/STRUCTURE.md).

## Playing it

```powershell
scripts\build.ps1; bash scripts/build-player.sh; scripts\serve.ps1
```

`build-player` builds the patched browser player: dirplayer-rs with fixes for the Director
behaviours the game relies on. It needs Rust and Node.js; see [docs/PLAYER.md](docs/PLAYER.md).
The game itself runs unmodified.

Open <http://localhost:8000/platforms/web/shockwave.html> for the game (dirplayer-rs) or
<http://localhost:8000/platforms/web/> for the Flash hub (Ruffle). Controls: arrows,
<kbd>Z</kbd> punch, <kbd>X</kbd> kick.

## Recompiling / porting

See [docs/PORTING.md](docs/PORTING.md). In short:

- **Director game:** open `src/ttbattleblitz/movies/game.dir` in Adobe Director (8.5–12) to
  edit and republish (Shockwave or a Windows/macOS projector). Or run it through an
  open-source Director player such as ScummVM's Director engine or dirplayer-rs (web).
  For a native port, translate the Lingo classes to your engine; the code is cleanly
  object-oriented.
- **Flash hub:** run `build/internal.swf` in Ruffle (`platforms/web/`), or open the XFL in
  Adobe Animate.

## Layout

```
scripts/     pipeline (setup, decompile, build, verify), .ps1 and .sh
docs/        structure notes and porting guide
platforms/   per-platform wrappers (web/Ruffle for SWFs)
original/    your game files      (gitignored)
src/         decompiled output    (gitignored)
build/       rebuilt output       (gitignored)
tools/       decompilers          (gitignored)
```

Decompilers: [JPEXS Free Flash Decompiler](https://github.com/jindrapetrik/jpexs-decompiler) (GPLv3) and
[ProjectorRays](https://github.com/ProjectorRays/ProjectorRays) (MPL-2.0).
