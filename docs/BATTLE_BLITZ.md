# Teen Titans: Battle Blitz (the game)

These notes cover the `ttbattleblitz.zip` package from
[archive.org/details/ttbattleblitz](https://archive.org/details/ttbattleblitz)
(SHA-1 `b41c01eeedcef3c707decef5a52ffeff7de93dd5`). Run `scripts/decompile` to get the sources
referenced here.

## What it is

A 2003 Cartoon Network 2D fighting game built in **Macromedia Director 8** and shipped as
Shockwave. The movies and casts are Director 8.0 files. The archive wraps them in a Director
MX 2004 projector so they run offline. The game is 1-player vs CPU, with five Titans against
five villains.

## Files to put in `original/ttbattleblitz/`

Copy only these from the zip. Don't use the projector `.exe`, `.dll` or `Xtras/` files: the
decompiler doesn't need them, and the repack added third-party Xtras.

| file | role |
|---|---|
| `game.dcr` | The game: score, screens, HUD and **all of the Lingo code** (protected Shockwave movie) |
| `char_<Fighter>.cct` (×10) | Per-fighter art, animation frames and sounds (protected external casts) |
| `char_shared.cct` | Art shared by all fighters (effects, shadows, summon portraits) |
| `empty_*.cct` (×11) | Empty stand-in casts that get swapped for the real ones while characters stream in |
| `gamewrapper.dir` | Small Director MX 2004 launcher movie used by the projector (one behavior script) |
| `lingo.ini`, `Projector.ini` | Projector start-up config (text) |

## Decompiled output

- `src/ttbattleblitz/movies/game.dir` is the unprotected main movie. It opens in Director 8.5
  or later, with editable scripts.
- `src/ttbattleblitz/movies/char_*.cst` are the unprotected casts, which also open in
  Director.
- `src/ttbattleblitz/movies/game/casts/<cast>/*.ls` is the Lingo source: **375 scripts,
  about 31,700 lines**. ProjectorRays reported no decompile errors and no unknown opcodes.
  Re-decompiling the unprotected files gives identical source, which `scripts/verify`
  checks.

## Code architecture

The game is written in object-oriented Lingo: parent scripts are classes, `new()` creates
instances, and almost everything lives in the `Lingo` cast.

**Engine and framework classes**

- **Bootstrap:** the `Global_Main` movie script, `Class_Main`, `Class_Globals` and
  `_ClassDefinitions` (class registry).
- **Screens:** `Class_Screen` is the base. Subclasses are `TitleScreen`, `IntroScreen`
  (difficulty select: easy, intermediate, expert), `SelectFighterScreen` (heroes or villains
  tab), `VersusScreen`, `ControlsScreen`, `GameScreen` and `WinScreen`. Each screen has
  matching frame behaviors (`Behavior_*`) in the score.
- **Scene graph:** `Scene`, `SpriteManager`, `ObjectGroup`, `ObjectTable`, `Overlay`,
  `StaticOverlay` and `Shadow`.
- **Actors:** `Actor` → `StaticActor`, `AnimatedActor`, `CollidableActor` → `Fighter`. Also
  `Projectile` and `ActorTrails`.
- **Animation:** `Animation`, `IndexedAnimation`, `LoopedAnimation`, `PlayOnceAnimation`,
  `ManualAnimation` and `Sequence`.
- **Moves:** `Move` (the base state of the per-fighter state machines), with `KeyCombo`
  for special-move input sequences.
- **Input and AI:** `KeyManager` and `KeyAdapter` for the human player. `AIAdapter` plus one
  `<Fighter>AI` class per character drive the CPU.
- **HUD:** `HUD`, `HUDHealthBar`, `HUDClock`, `HUDScore`, `HUDSummonPortrait` and
  `GameTimer`.
- **Effects:** dust, hit bursts, ground explosions and waves, white flashes, light columns,
  background bars, and each Titan's "summon" assist effect.
- **Assets and loading:** `Assets<Fighter>`, `AssetsCharShared`, `AssetsHUD`,
  `AssetsAudio`, `AssetsGameMessages`, `MemberLoader` and `NetPreloader` (stream the
  `char_*.cct` casts in). `AudioManager` handles sound.
- **Utility:** `Utility`, plus a `DEBUG` movie script and a debug "bypass menus" behavior.

**Fighters.** Each fighter is a `Fighter` subclass plus one class per move or state. Common
states are stand, walk forward/backward, jump, land, block, stun, knock-down and defeat.
Each fighter also has special attacks, their projectile and effect classes, a `KeyAdapter`
and an `AI` class.

| team | fighter | classes |
|---|---|---|
| Titans | Robin | 24 |
| | Starfire | 29 |
| | Cyborg | 28 |
| | Raven | 26 |
| | Beast Boy | 21 |
| Villains | Cinderblock | 17 |
| | Gizmo | 34 |
| | Jinx | 23 |
| | Mammoth | 17 |
| | Plasmus | 27 |

**Casts in `game.dir`:** `Lingo` (all classes), `audio`, `hud`, `game_messages`,
`title_screen`, `select_screen`, `versus_screen`, `controls_screen`, `win_screen`, `misc`,
`scratch`, and `cartoon_network_enhancements` (Cartoon Network site links and
navigation).

## Porting notes

- The game expects to stream `char_*.cct` from next to the movie. Keep the files together.
- Four scripts use Shockwave's network/browser calls: the three
  `cartoon_network_enhancements` behaviors (billboard, "launch orbit", play-game link) and
  `Class_NetPreloader`. None hardcodes an absolute URL. Stub them out or reroute them for a
  standalone build.
- The code splits cleanly into an engine (actors, animation, scene, input) and game data
  (fighters, moves, AI), so a port can reimplement the small engine layer and translate
  the class files almost one to one.
