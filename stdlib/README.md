# The MeTTa standard library, vendored

`stdlib.metta` is a **verbatim** copy of the reference interpreter's standard library:

| | |
|---|---|
| upstream | [trueagi-io/hyperon-experimental](https://github.com/trueagi-io/hyperon-experimental) |
| path | `lib/src/metta/runner/stdlib/stdlib.metta` |
| version | 0.2.10 |
| license | MIT, Copyright (c) 2021 SingularityNET Foundation |

Kept byte-identical on purpose, so bumping the reference is a plain `diff` against
upstream rather than a merge. Do not edit it to work around a JeTTa limitation — a
divergence here is a silent incompatibility with the interpreter this project is
measured against. Fix the compiler instead, and if a construct genuinely cannot be
supported yet, record it in the build that compiles this file.

The build compiles it ONCE into the artifact set a program links against
(`stdlib.class`, `stdlib.jctx`, `stdlib.jtsf`, `stdlib.manifest.json`) and ships those
inside `jettac.jar`. Programs then LINK it — see `ArtifactModuleResolver` and
`ImportResolutionPass`. Nothing recompiles this file per program.
