# Teen Titans: Battle Blitz — Recomp

Tooling to decompile the Teen Titans: Battle Blitz Flash SWF from Cartoon Network into
editable source: ActionScript, a full XML description of every tag, SVG/PNG/MP3/TTF assets
and an Adobe Animate project. It also rebuilds a working SWF from that source, and the
rebuild is verified against the original frame by frame.

**No game files are included.** The SWF, its art, sound and code belong to Cartoon Network /
DC Comics. You supply your own copy, and everything extracted from it stays in gitignored
folders (`original/`, `src/`, `build/`).

> **Heads-up about the Internet Archive upload.** The file in the
> [`teen-titans-battle-blitz-game`](https://archive.org/details/teen-titans-battle-blitz-game)
> item (`internal.swf`) is **not the game itself**. It is the Teen Titans hub/menu page from
> cartoonnetwork.com: a preloader, an intro animation, a music toggle, and buttons that link
> to the character pages and to `battleblitz/index.html`, where the game was hosted. It has no
> gameplay code. See [docs/STRUCTURE.md](docs/STRUCTURE.md). The pipeline works on any
> AS1/AS2/AS3 SWF, so drop the real game SWF into `original/` when you find it (BlueMaxima's
> Flashpoint is the usual place to look).

## Requirements

- Java 11+ (`java` on PATH, or `JAVA_HOME` set)
- Windows: PowerShell 5.1+ (built in). Linux/macOS/Git Bash: bash, curl, unzip.

## Usage

1. Put your SWF(s) in `original/`. For the Internet Archive upload:

   | file | SHA-1 |
   |---|---|
   | `original/internal.swf` | `115ee2ee7c4a808fe66719039419c73ead8ac90b` |

2. Run the pipeline. Every step takes optional SWF names and defaults to all of `original/*.swf`.

   ```powershell
   scripts\setup.ps1       # download FFDec 26.3.0 into tools/ (checksum-verified)
   scripts\decompile.ps1   # original/<name>.swf -> src/<name>/
   scripts\build.ps1       # src/<name>/ -> build/<name>.swf
   scripts\verify.ps1      # compare build/ against original/ frame by frame
   ```

   ```bash
   scripts/setup.sh && scripts/decompile.sh && scripts/build.sh && scripts/verify.sh
   ```

   If PowerShell refuses to run the scripts, use
   `powershell -ExecutionPolicy Bypass -File scripts\build.ps1`.

## What decompile produces

```
src/<name>/
  swf.xml     every tag in the SWF as XML (timelines, placements, shapes, sounds, fonts,
              bitmaps...). Lossless: build.sh turns this back into the SWF.
  scripts/    ActionScript source, one file per frame/button/clip action.
              build.sh recompiles these into the SWF.
  pcode/      the original bytecode as readable P-code (reference)
  assets/     shapes (SVG), sprites, buttons and frames (PNG), sounds (MP3), images,
              fonts (TTF), texts
  xfl/        Adobe Animate project (open <name>.xfl)
```

Edit `src/<name>/scripts/**/*.as` or `src/<name>/swf.xml`, run build, and `build/<name>.swf`
reflects your changes. On an untouched `src/`, `verify` reports all frames rendering
identically and the recompiled scripts decompiling back to the same source.

## Getting it onto other platforms

See [docs/PORTING.md](docs/PORTING.md). In short:

- **Anywhere, today:** run `build/<name>.swf` in [Ruffle](https://ruffle.rs) (web, Windows,
  macOS, Linux). `platforms/web/` has a ready-made Ruffle page.
- **Native app:** open `src/<name>/xfl/` in Adobe Animate and publish to HTML5 Canvas or AIR
  (Windows/macOS/Android/iOS).
- **Full source port:** reimplement the timeline logic from `src/<name>/scripts` in your
  engine of choice (OpenFL/Haxe, Godot, JavaScript...) using the extracted assets.

## Layout

```
scripts/     pipeline (setup, decompile, build, verify), .ps1 and .sh
docs/        structure notes and porting guide
platforms/   per-platform wrappers (web/Ruffle)
original/    your SWFs            (gitignored)
src/         decompiled output    (gitignored)
build/       rebuilt SWFs         (gitignored)
tools/       FFDec                (gitignored)
```

Decompilation uses [JPEXS Free Flash Decompiler](https://github.com/jindrapetrik/jpexs-decompiler) (GPLv3).
