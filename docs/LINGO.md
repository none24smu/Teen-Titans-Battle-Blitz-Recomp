# Lingo: decompiled source and the compiler

The game's logic is 375 Lingo scripts (374 in `game.dir`, 1 in `gamewrapper.dir`).
ProjectorRays turns their bytecode into `.ls` source. `scripts/lingo/` holds a compiler that
turns that source back into Director bytecode, so edited scripts don't need Adobe Director.

| file | what it does |
|---|---|
| `LingoScript.java` | reads the script chunks: `Lctx`/`LctX` (which scripts a movie has), `Lnam` (the shared name table), `Lscr` (one compiled script); disassembler |
| `LingoCompiler.java` | lexer, parser and code generator: `.ls` source → handlers, bytecode, literals, name ids |
| `LingoRoundTrip.java` | compiles every `.ls` of a movie and compares the result with the movie's bytecode, handler by handler |
| `LingoFixups.java` | corrects ProjectorRays mistakes in the `.ls` files, using the bytecode (run by `decompile`) |
| `LingoBuild.java` | compiles a movie's `.ls` files and writes them back into the movie (run by `build`) |

```bash
scripts/verify.sh       # ... compile: OK (375 scripts compile back to identical bytecode)
```

`verify` runs the round trip. Every script compiles back **byte for byte**: same bytecode,
same literals, same name ids, same argument/local/global tables. A failure lands in
`build/.verify-<name>/<movie>.compile.txt` with the first differing instruction of each handler.
To run it by hand (the pipeline compiles the tools into `tools/java`):

```bash
java -cp tools/java LingoRoundTrip src/ttbattleblitz/movies/game.dir src/ttbattleblitz/movies/game -v
```

## How Director 8 compiles Lingo

These are the rules the compiler follows to match Macromedia's output. Each one was worked
out from the game's own bytecode.

**Operand widths.** An operand is 1, 2 or 4 bytes (opcode + 0x40, + 0x80). Usually the
smallest width that fits is used, but:

- forward jumps (`jmp`, `jmpifz`) and `pushint8` are always 2 bytes;
- a name operand is 2 bytes when the name was new to the movie's name table while that
  script was compiled, even if its id fits in a byte. The table only grows, so "new" means
  an id at or above the table size when the script was compiled (`Names.newFrom`);
- argument counts (`pusharglist`, `pusharglistnoret`), loop-back distances (`endrepeat`) and
  `put`/`putchunk` modes switch to 2 bytes from 32 up;
- `repeat with x in list` writes its two `1` constants as 1-byte `pushint8`.

**Numbers.** Locals, parameters and literals are numbered `index × 8` in Director 8
(`getlocal 16` is the third local). Director 8.5+ (`LctX` contexts, like `gamewrapper.dir`)
uses plain indexes and writes `pushint8` at its smallest width.

**Statements.**

- `x = y` is `y; setlocal/setparam/setprop/setglobal`. A property shadows a parameter
  with the same name.
- `case`: the value stays on the stack (`peek 0` before each label, `pop 1` at the end).
  `a, b:` tests `a` with `nteq`/`jmpifz` straight into the body, then `b` with `eq`. The
  last branch has no jump to the end unless an `otherwise` follows.
- `repeat with i = a to b` / `down to`: `endrepeat` back to the test. `repeat with x in l`
  keeps the list, its count and the index on the stack, then `pop 3`.
- `return x` is `x; pusharglistnoret 1; extcall return`. A bare `return` first pops what an
  enclosing `case` or `repeat … in` holds. `exit` is `ret`.
- `put v after|into|before var` is `v; pushint8 <var>; put <mode·16 + type>`. Types: 1
  global, 3 property, 4 parameter (`index × 8`), 5 local (**plain index**). `put … of chunk`
  first pushes the first and last char, word, item and line (0 when unused) and uses `putchunk`.

**Expressions.**

- Calls to handlers of the same script are `localcall <index>`, except built-ins: `new()`
  inside a script that has `on new` is still `extcall new`.
- `obj.prop[i]` is `obj, #prop, i; objcall getProp`. Used as the target of another index or
  a method call it becomes `getPropRef`. `obj.prop[i] = v` is `objcall setProp` with a
  `pusharglist` (not `noret`) of 4.
- `list[i]` is `objcall getAt`; `list[i] = v` is `objcall setAt`.
- `obj.prop.count` is `obj, #prop; objcall count`.
- `the timer`, `the exitLock` and the other numbered movie properties are `pushint8 <n>;
  get/set 7`. `the number of castMembers of castLib c` is `c, 2; get 8` (`the number of
  castLibs` is `4; get 8`). Other `the` properties are `getmovieprop`/`thebuiltin`.
- Every use of a constant gets its own literal. Director doesn't pool them.

**Stale scripts.** A context can keep old copies of a script after it was edited and
recompiled. `game.dir` has five. The cast member's info names the live copy (its script id),
and the stale ones are never run.

## ProjectorRays 0.2.0 corrections

`LingoFixups` repairs, in place, the `.ls` files `decompile` writes:

- **`put … into|after|before <local>`.** Director 8 stores the local's plain index here,
  not `index × 8`. ProjectorRays divides by 8 anyway, so it names the wrong variable.
  In `DEBUG`, `put RETURN after classNames` is really `put RETURN after out1`.
  48 lines in `MovieScript 230 - DEBUG.ls` are affected; the game's other scripts don't use
  this form. The `.lasm` listings keep ProjectorRays' comments.

## Writing scripts back into a movie

`LingoBuild` (run by `scripts/build`) compiles every `.ls` file and serializes each script in
Director's `Lscr` layout, which `LingoScript` reads:

- header (92 bytes), property and global name tables, handler records (42 bytes; 46 with
  `LctX`, which adds the stack height), then per handler its bytecode, argument, local and
  global tables and line table, each padded to an even offset, then the literal records and
  literal data (strings and floats with a length word), then the handler-vector table;
- the handler-vector table lists event handlers by slot (0 `mouseDown`, 1 `mouseUp`,
  2 `keyDown`, 3 `keyUp`, 5 `prepareFrame`, 7 `mouseEnter`, 8 `mouseLeave`, 12 `stopMovie`,
  15 `exitFrame`). The header holds the slot count and a bitmask of the slots used;
- an unchanged handler keeps its original source position and line table. An edited one has
  none: the line table maps bytecode to lines of the original source text, which the
  movie no longer has.

Names the compiler hasn't seen before are appended to the `Lnam` chunk. Only chunks whose
content changed are replaced. The other chunks keep their order and the gaps between them,
and the memory map is updated, so an unedited movie comes out byte-identical. `verify`
checks that ("build: OK"). Padding bytes are the exception: Director left uninitialised
memory in a few of them, and a script that differs from the original only there counts as
unchanged.

Limits: scripts are compiled into the script members that already exist. New script members
and art or sound changes still need Director.

`build` compiles the `.ls` files over whatever scripts the movie holds. If you edit scripts
inside Director instead, decompile the saved movie again so the `.ls` files match it.
