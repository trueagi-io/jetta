# tests/hyperon — reference-semantics probes

Small programs, one behaviour each, whose expected answers were MEASURED on the reference
interpreter (`metta-repl` 0.2.10, `hyperon-experimental`), not written from the docs. Every file
passes there:

```sh
R=~/Documents/projects/singularity.net/hyperon-experimental/target/release/metta-repl
for f in tests/hyperon/h*.metta; do echo "$f $($R $f | tr '\n' ' ')"; done   # all [()]
```

JeTTa runs them through the test-runner like `tests/metta`:

```sh
java -jar test-runner/build/libs/test-runner-*-all.jar tests/hyperon /tmp/th
```

A file is either a GUARD (passes today; a failure is a regression) or a known DIVERGENCE listed in
`.xfail` with its cause. One divergence per file, so a fix shows up as exactly one
`UNEXPECTED_PASS` and a partial fix cannot hide behind another failing assertion.

Rules for adding a probe: measure it on `metta-repl` first; compare with `assertEqualToResult`
(its expected side is not evaluated — `assertEqual` would turn an expected `(+ 1 2)` into 3);
keep a top-level `(Error …)` inside `collapse`, since it ends the reference script.
