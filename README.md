# JeTTa

**A compiler from [MeTTa](https://metta-lang.dev) to JVM bytecode.**

MeTTa is a non-deterministic, multi-valued, term-rewriting language used for
probabilistic programming and logical inference. JeTTa compiles it — ahead of
time — to ordinary `.class` files that run on any JVM, so MeTTa programs get
HotSpot's JIT, the JVM's GC, and (soon) first-class Java interop for free.

The architectural bet is **partial evaluation with a runtime fallback**:
pre-compute everything that is static, defer what isn't, and share one code path
between compile time and call time. The behavioural reference is the Rust
interpreter [`trueagi-io/hyperon-experimental`](https://github.com/trueagi-io/hyperon-experimental);
JeTTa aims to produce byte-for-byte identical answers on its test suite.

- **Version:** `0.6.1` · **License:** MIT · **Runtime:** Java 17+

> **Status.** JeTTa is under active development. The fundamentals — symbols,
> pattern match, equality, chaining, non-determinism, spaces, mutable state and
> module imports — work today; the type system (GADTs, dependent, auto) is the
> current frontier. See [correctness](#compatibility--correctness) below.

---

## Requirements

- A **Java 17** (or newer) JDK. That's it — the Gradle wrapper (`./gradlew`)
  fetches everything else, and the ANTLR parser is generated during the build.

If you have several JDKs installed, point Gradle at a 17 toolchain, e.g.:

```sh
export JAVA_HOME=/path/to/jdk-17
```

## Build

```sh
./gradlew build          # compile every module, run tests, refresh bin/jettac.jar
./gradlew test           # just the unit tests (all modules)
./gradlew :frontend:test # tests for a single module
```

A successful `build` copies a self-contained "shadow" jar to **`bin/jettac.jar`**.
The wrapper scripts `bin/jettac` (compile) and `bin/jetta` (run) simply call
`java` against that jar, so once it's built you don't need Gradle to use JeTTa.

## Quick start — "Hello, MeTTa"

Create a file `hello.metta`:

```metta
; A rule: greet(x) rewrites to (Hello x).
(= (greet $x) (Hello $x))

; Lines starting with `!` are *runs* — they execute.
!(println (greet MeTTa))
```

Compile it to a directory, then run the generated program:

```sh
# 1. Compile  →  writes hello.class + space artifacts into out/
bin/jettac hello.metta -d out

# 2. Run  →  prints:  (Hello MeTTa)
java -Djetta.dataDir=out -cp out:bin/jettac.jar hello
```

That's the whole loop. A few things worth knowing:

- **`!expr` runs, a plain expression does not.** A top-level expression without
  `!` is added to the program's knowledge base (its *space*); only `!expr` forms
  execute. To *see* a result in a compiled program, print it (`!(println …)`) or
  assert on it (`!(assertEqual …)`) — the generated `main` returns the last run's
  value but does not auto-print it. (The REPL, below, does print every result.)
- **`-Djetta.dataDir=out`** tells the running program where to find the space
  artifacts written at compile time. Omit it and the program runs against an
  empty space — fine for pure arithmetic, wrong as soon as you use `match &self`.

### The REPL

For quick experiments, start an interactive session:

```sh
bin/jettac -i
```

Type an expression, then press **ENTER on an empty line** to evaluate it;
type `:exit` to quit. Unlike a compiled program, the REPL prints every result:

```
> !(+ 1 2)

3
> :exit
```

## Command-line reference

### `jettac` — the compiler

```sh
bin/jettac [SOURCES...] [OPTIONS]
```

| Option | Meaning |
| --- | --- |
| `-d, --directory <dir>` | Output directory for `.class` files and space artifacts (default: `.`) |
| `-i, --interactive` | Start the REPL instead of compiling |
| `--ir` | Also dump the fully-typed intermediate representation to `<Name>.jir` |
| `-D, --debug` | Verbose (debug-level) compiler logging |
| `-n, --no-greetings` | Suppress the startup banner |
| `--version` | Print the version and exit |

### Running a compiled program

The generated class has a `main`, so any of these work:

```sh
java -Djetta.dataDir=out -cp out:bin/jettac.jar hello   # explicit, self-contained
```

`bin/jetta` is a shortcut that puts the current directory and `jettac.jar` on the
classpath — convenient when you run from inside the output directory:

```sh
cd out && ../bin/jetta -Djetta.dataDir=. hello
```

## What compilation produces

For a source `Foo.metta`, `jettac … -d out` writes into `out/`:

| Artifact | What it is |
| --- | --- |
| `Foo.class` | The compiled program (JVM bytecode) |
| `Foo.jtsf` | The program's *space* — its knowledge base of atoms — in a packed binary format |
| `Foo.indices/` | Pre-built match indices for patterns known at compile time (`.jtsi`, ~15–20× compressed) |
| `Foo.manifest.json` | Metadata tying the above together (module list, fingerprints) |
| `Foo.jir` | *(only with `--ir`)* the fully-typed IR, as readable text |

The `.jtsf` / `.indices` / `.manifest.json` files are why running a program
needs `-Djetta.dataDir`: they *are* the program's initial knowledge base.

## Project layout

Eight Gradle modules; the compilation pipeline flows one direction
(`.metta` → parse → rewrite → resolve/type → bytecode):

| Module | Responsibility |
| --- | --- |
| **frontend-api** | Shared IR types (`Atom`, `Expression`, `Symbol`, `Grounded`, arrow types) — no logic |
| **frontend** | ANTLR parser, the rewriter chain, resolver and type inference |
| **backend** | ASM bytecode emission (`Generator`, `FunctionGenerator`, …) |
| **runtime** | Execution support: `JettaProgram`, `Matcher`, and the `space/` store + serialization |
| **compiler** | Orchestrator + CLI (`Main.kt`); produces the shadow jar |
| **server** | Ktor-based REPL/HTTP service |
| **logger** | Common logging |
| **test-runner** | Batch-compiles and runs a directory of `.metta` files, emitting a pass/fail report |

## Examples & tests

- **`examples/`** — checked-in `.metta` programs (`intro/`, `symbolic/`,
  `interp/`, `b2backchain/`) that double as ground-truth references.
- **`tests/metta/`** — programs mirroring the hyperon reference suite, named
  `<letter><digit>_<topic>.metta` (topic × complexity: `a` symbols/match,
  `b` equality/chaining/non-det, `c` grounded values/spaces/PLN, `d` types,
  `e` mutation/states, `f` modules/imports, `g` doc atoms).
- **Unit tests** live per module under `src/test/kotlin` (JUnit 5).

## How it works (the short version)

- **Non-determinism is a `List<Atom>` return.** A function that can yield several
  results returns a list; composition takes the cartesian product.
- **Rules live in the space as ordinary atoms.** `(= lhs rhs)` is both a compiled
  function *and* a space fact, so `match &self (= …)` queries and compiled calls
  see the same rules — there is no separate rule database.
- **Match indices are partial-evaluated.** Patterns known at compile time get
  pre-built indices on disk; unknown patterns build the same structure lazily at
  runtime — one code path, two trigger points.
- **The non-determinism strategy is one swappable pair of functions**
  (`map` / `flatMap`), which is the seam for future parallel / sampling / beam
  execution without touching the compiler or user code.

For the design in depth, see [`CLAUDE.md`](CLAUDE.md) (and the design notes
kept under `docs/`).

## Compatibility & correctness

Every program is checked against `hyperon-experimental` for the same answer.
Group-by-group coverage of the reference topic suite (`a`–`g`):

| Group | Feature | Status |
| --- | --- | --- |
| a | symbols / match | ✅ full |
| b | equality · chaining · non-det | core done |
| c | grounded values · spaces · PLN | spaces done |
| d | types (GADT · dependent · auto) | frontier |
| e | mutation / states | KB-write done |
| f | modules / imports | `import!` works |
| g | doc atoms (`get-doc` / `help!`) | ✅ full |

## License

[MIT](LICENSE) © trueagi-io.
