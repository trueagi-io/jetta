# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

JeTTa is a compiler from MeTTa (a non-deterministic, multi-valued, term-rewriting language used for probabilistic programming and logical inference) to JVM bytecode. The reference behavioral spec is the Python interpreter at `trueagi-io/hyperon-experimental` — its test scripts at `python/tests/scripts/` define the compatibility target.

JeTTa is **not** a translation to Prolog (cf. PeTTa). The architectural bet is **partial evaluation with a runtime fallback**: pre-compute what's static, defer what isn't, share one code path between compile-time and call-time. Match indices already follow this pattern; the design extends to types, dispatch, and `eval` (runtime JIT-compilation — an `eval`/dynamic-dispatch site is compiled to bytecode and loaded at call time; MVP + variable-head dispatch landed, see `docs/jit_eval_implementation_plan.md`).

## Build & run

Java 17 toolchain. `./gradlew build` builds everything and copies a shadow jar to `bin/jettac.jar`; the wrapper scripts `bin/jettac` (compile) and `bin/jetta` (run a compiled class) just `java -jar` / `java -cp` that.

```sh
./gradlew build                                   # full build, refreshes bin/jettac.jar
./gradlew test                                    # all unit tests across modules
./gradlew :frontend:test                          # one module
./gradlew :frontend:test --tests "ParserTest.parseSymbol"   # single test

bin/jettac foo.metta -d out/                      # compile .metta to JVM classes + space artifacts
bin/jettac --ir foo.metta                         # also dump fully-typed IR to .jir
bin/jettac -i                                     # REPL
bin/jetta -cp out:bin/jettac.jar Foo              # run compiled program
```

The `frontend` module generates the ANTLR parser from `frontend/antlr/Jetta.g4` on every Kotlin compile.

## Module layout

Eight Gradle modules; the pipeline is one direction:

- **frontend-api** — IR types and contracts (`Atom`, `Expression`, `Symbol`, `Variable`, `Grounded`, `Lambda`, `FunctionDefinition`, `Run`, `BoundAtom`; arrow types). No implementation. Both `frontend` and `backend` consume this; the runtime references it for atom values.
- **frontend** — ANTLR parser (`AntlrParserFacadeImpl`, `JettaVisitorImpl`), rewriter chain, resolver/type-inference (`Context`).
- **backend** — ASM bytecode emission (`Generator`, `FunctionGenerator`, `LambdaGenerator`, `Externals`).
- **runtime** — execution support: `JettaProgram`, `Matcher`, and the `space/` package (`SpaceImpl`, `IndexerImpl`, packed binary serialization).
- **compiler** — orchestrator + CLI (`Main.kt`, `Compiler.kt`); produces the shadow jar.
- **server** — Ktor REPL/HTTP service.
- **logger** — common logging.
- **test-runner** — walks `.metta` files in a directory, compiles each via the `Compiler` API, loads the generated class, invokes `main`/`__main`, captures stdout/stderr, writes a report.

## Compilation pipeline

`.metta` → ANTLR parse → rewriter chain → resolver (`Context`) → ASM. Rewriting runs in two phases:

1. **Pre-resolution `CompositeRewriter`** (in `Compiler.kt`), in order: `FunctionRewriter` → `LetRewriter` → `LambdaRewriter`. `FunctionRewriter` partitions top-level forms into **declarations** (`(= ...)`, types, annotations), **space facts** (plain expressions), **runs** (`!expr`); generates `__main` from the run bucket. See `docs/top_level_semantics_impl.txt` for the precise semantics — load-bearing.
2. **During `Context.resolve`** (after symbol/type resolution), in order: `ReplaceNodesRewriter`, `MarkMultivaluedFunctionsRewriter`, `LowerAssertExpressionsRewriter`, `QuotePureSymbolicBodiesRewriter`, `CanonicalFormRewriter`.

`Generator.generate()` emits one JVM class per source file. Alongside `.class`, it writes the space as `<Name>.jtsf` (binary atom store), `<Name>.indices/index-NNNN.jtsi` (packed match indices for known patterns, ~15-20× compressed), and `<Name>.manifest.json`. With `--ir`, it also writes `<Name>.jir` (text IR).

## Runtime semantics — what to know before changing things

- **Non-determinism is `List<Atom>` returns.** Functions that may produce multiple results return a list; `MarkMultivaluedFunctionsRewriter` decides which ones do. Composition cartesian-products results.
- **Binding stack with upward propagation.** `Matcher` (object, thread-local) keeps an `ArrayDeque<MutableMap<String, Atom>>`. `Matcher.pop()` does `parent.putAll(child)` — variables bound deep in a callee become visible to the caller. This is what makes `(ift (deduce ...) $x)` (see `examples/b2backchain/BackchainWho.metta`) yield a value through a deep recursion. Do not "fix" pop() to scope-isolate without understanding this — it's the mechanism.
- **`BoundAtom` foliation.** `SpaceImpl.match` wraps each result in `BoundAtom(atom, snapshot)` so non-deterministic branches each carry their own bindings (`enablePerCallBindings` flag, default true).
- **`=` rules live in space as ordinary `Expression`s.** There is no separate rule database. `match &self (= $lhs $rhs) ...` is just a space query. Compiled rules and space-level rules coexist.
- **Match indices are partial-eval'd.** Patterns known at compile time get pre-built `.jtsi` indices. Unknown patterns get an `IndexerImpl` built lazily on first use via `getOrPut` in `SpaceImpl.match` — same code path either way.
- **`map`/`flatMap` are pluggable — the entire non-determinism strategy is one swappable pair.** The backend defines `JettaRuntime` (`backend/.../JettaRuntime.kt`) with `mapImpl` and `flatMapImpl` pointing at `JvmMethod`s; codegen emits `INVOKESTATIC` to whatever those methods are. The default (`DefaultRuntime`) wires them to `runtime/Util.kt`'s `simpleMap` / `simpleFlatMap` — sequential, with `Matcher.push`/`pop` around each branch and `BoundAtom` unwrap to install per-branch bindings. Swapping these is the supported extension point for **parallel execution** (ForkJoinPool / coroutines), **sampling for probabilistic programming**, **beam search**, **streaming/lazy with early termination**, **PLN-style weighted branches** — without touching the compiler or user code. Constraint: `Matcher` is `ThreadLocal`, so cross-thread strategies need to propagate the binding stack explicitly.
- **Top-level semantics:** plain expressions populate the space; only `!expr` (Run nodes) execute via generated `__main`; `__main` returns the last run result. Per `docs/top_level_semantics_impl.txt`.

## Tests

- **Unit tests** per module (`src/test/kotlin`); standard JUnit 5 (`useJUnitPlatform()`). Examples: `frontend/.../ParserTest.kt`, `RewriteTest.kt`, `ResolveTest.kt`, `TypeInferenceTest.kt`; `runtime/.../SpaceImplTest.kt`, `IndexerImplTest.kt`.
- **MeTTa integration tests** live in `tests/metta/` (checked in; mirror the hyperon reference topics). Run via the `test-runner` module: it compiles each `.metta`, runs it, and emits a pass/fail+output report.
- **Examples** under `examples/` (`intro/`, `b2backchain/`, `tests/`) are checked-in `.metta` files plus their generated artifacts; useful as ground-truth references when changing rewriter or codegen output.

## Design notes (read these before making semantic changes)

- `docs/top_level_semantics_impl.txt` — top-level Run vs space-fact split. Implemented (commit `4872b9c`).
- `docs/assertion_implementation_plan.txt` — `!expr` assertion design and the `assertEqual` / `assertEqualToResult` ground functions. Implemented (commit `6c8ae09`).

## Reference compatibility

When adding or fixing semantics, sanity-check against the Python reference at `https://github.com/trueagi-io/hyperon-experimental/tree/main/python/tests/scripts`. The naming `<letter><digit>_<topic>.metta` is topic + complexity:

- `a*` symbols/match · `b*` equality, chaining, non-det, type prelim · `c*` grounded values, multiple atomspaces, PLN · `d*` types (GADT, higher-order, dependent, propagation, auto) · `e*` mutation/states · `f*` modules/imports · `g*` doc atoms.

Distinctive features whose existence the reference depends on (some implemented, some not): `Atom` meta-type to suppress argument reduction, first-class atomspaces (`&self`, `&kb`, …), state atoms inside equalities, `pragma!` runtime mode flags, `import!` with diamond dedup. Don't compile away argument evaluation without checking whether the call site declares a meta-type `Atom` parameter.
