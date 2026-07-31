package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.ir.SeqType
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import org.objectweb.asm.Type

fun registerExternals(context: Context) {
    val random = JvmMethod(
        owner = RuntimeNames.RANDOM,
        name = "random",
        descriptor = "()D"
    )
    val seed = JvmMethod(
        owner = RuntimeNames.RANDOM,
        name = "seed",
        descriptor = "(J)V"
    )
    context.addSystemFunction(ResolvedSymbol(random, null, false))
    context.addSystemFunction(ResolvedSymbol(seed, null, false))
    val generate = JvmMethod(
        owner = RuntimeNames.RANDOM,
        name = "generate",
        descriptor = "(L${RuntimeNames.JETTA_FUNCTION};DDD)Ljava/util/List;",
    )
    context.addSystemFunction(ResolvedSymbol(generate,
        ArrowType(ArrowType(GroundedType.DOUBLE, GroundedType.DOUBLE),
            GroundedType.DOUBLE, GroundedType.DOUBLE,
            GroundedType.DOUBLE, SeqType(GroundedType.DOUBLE)
        ), true))
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = RuntimeNames.IO,
                name = "println",
                descriptor = "(Ljava/lang/Object;)V"
            ), null, false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = RuntimeNames.ASSERTIONS,
                name = "assertEqual",
                descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)V"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ANY, GroundedType.UNIT),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = RuntimeNames.ASSERTIONS,
                name = "assertEqualToResult",
                descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)V"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ANY, GroundedType.UNIT),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "match",
                // Space arg is Object (not String) so `match` works INDIRECTLY too — when the
                // space arrives through a variable as an Atom (e.g. a match-single wrapper),
                // not just as a baked-in name String. Pattern arg is Atom (not Expression) so
                // a bare-variable match-all pattern `(match &kb $x $x)` is legal.
                // JettaProgram.match resolves the space name and dispatches on the pattern shape.
                descriptor = "(Ljava/lang/Object;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM, GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    // `matchReduce` — same shape as `match`, but each result is run through the runtime
    // grounded-op reducer (see JettaProgram.matchReduce). The rewriter routes a `match` whose
    // TEMPLATE is a grounded-operator expression (`(- $y $x)`) here so the substituted
    // template is evaluated (→ 0.8) rather than returned inert.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "matchReduce",
                descriptor = "(Ljava/lang/Object;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM, GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "superpose",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    // `empty` — the empty non-deterministic bag (zero results); prunes a branch. No args,
    // multivalued (returns a List).
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "empty",
                descriptor = "()Ljava/util/List;"
            ),
            ArrowType(SeqType(GroundedType.ATOM)),
            true
        )
    )
    // `unique` — non-determinism barrier that dedupes the bag (see BARRIER_FUNCTIONS). Consumes
    // the whole bag (an Object) and returns the distinct results (multivalued → List).
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "unique",
                descriptor = "(Ljava/lang/Object;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ANY, SeqType(GroundedType.ATOM)),
            true
        )
    )
    // `unquote` — strip one `quote` layer; the runtime half of a Form-2 pattern-`let`
    // `(let (quote $v) VAL BODY)` (LetRewriter lowers it to `(let $v (unquote VAL) BODY)`).
    // Param is ANY so the argument (e.g. `(render $e)`) IS reduced before the quote is stripped.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "unquote",
                descriptor = "(Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM),
            false
        )
    )
    // Space mutation built-ins. First arg is the space (an ATOM in the arrow type; `&self`
    // lowers to the module's space-name String at the call site, exactly like `match`). The
    // atom arg is typed ATOM so the resolver does NOT reduce it — `add-atom` stores data
    // verbatim (hyperon's `add-reduct` is the reducing variant). `add-atom`/`remove-atom`
    // return the unit atom `()` (an ATOM value, as in hyperon — NOT void: a void/UNIT
    // return can't be unboxed when the call is nested in a lambda-lifted context); not
    // multivalued. `get-atoms` returns the space's atom bag (multivalued).
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "add-atom",
                descriptor = "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "remove-atom",
                descriptor = "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "get-atoms",
                descriptor = "(Ljava/lang/String;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    // `import!` — runtime, order-sensitive module import (hyperon semantics; see
    // JettaProgram.import!). The space arg is Object so `&self`/`&kb` bake to a name String
    // at the call site exactly like `match`; the module arg is ATOM so the bare module
    // Symbol is NOT reduced (it names a module, not a value). Returns the unit atom `()`;
    // single-valued. Frontend keeps the `(import! …)` Run (ImportResolutionPass no longer
    // strips it) so it reaches codegen as an ordinary system call.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "import!",
                descriptor = "(Ljava/lang/Object;Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    // `is-var` — variable predicate. Argument is ATOM (unreduced) so a variable reaches the
    // builtin as a Variable, not as its binding.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "is-var",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    // `car-atom` / `cdr-atom` — head and tail of an expression (`(car-atom (a b c))` → `a`,
    // `(cdr-atom (a b c))` → `(b c)`). Argument is ATOM so a bound list variable arrives as
    // its Expression value; result is an Atom (an element, or a tail Expression). Pure and
    // single-valued.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "car-atom",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "cdr-atom",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    // `get-type` — infer the MeTTa type of its argument. The argument is ATOM (unreduced) so an
    // application `(+ 5 "4")` reaches the engine as an inert Expression and is TYPE-checked rather
    // than evaluated. Returns a bag (`List`): a singleton with the type, or EMPTY when ill-typed
    // (the `()` empty-set result the suite asserts). Modelled on `get-atoms` — `SeqType(ATOM)`
    // return + multivalued so the List composes as a result bag. Impure (reads the `:` facts in
    // the space), so excluded from memoization in Generator.impureGrounded.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "get-type",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;",
                // The argument must reach the type engine FULLY inert: a reducible application like
                // `(drop (Cons 1 Nil))` or a constructor `(Cons 0 (Cons 1 Nil))` is type-checked as
                // the un-reduced term, not evaluated first. Only `get-type` opts in — assertEqual /
                // add-atom / match NEED their args reduced, so they keep the default behavior.
                inertAtomParams = setOf(0)
            ),
            ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    // `get-doc` / `help!` — documentation. The argument is ATOM (unreduced) so a documented
    // symbol arrives as its Symbol and an application `(f a b)` as an inert Expression; get-doc
    // queries the `@doc`/`:` facts in `&self` and returns a `@doc-formal` structure (or `Empty`),
    // help! prints it. Both read the space / print, so impure (not memoizable).
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "get-doc",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "help!",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    // `set-watermark!` — ordered-top-level-semantics per-run prologue (compiler-internal; emitted
    // only by FunctionRewriter.mkMain, never by users). Takes a `Grounded<Int>` fact-count (ATOM
    // param so the literal passes through un-reduced) and sets JettaProgram.currentWatermark, so a
    // run's `get-type`/`get-doc`/`typeCheckError` see only facts declared above it. Impure (side
    // effect), so listed in Generator.impureGrounded.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "set-watermark!",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    // `nop` — run the argument, discard its value, yield `()`. ANY param so the argument is
    // REDUCED (the effect happens); the unit result is what makes `!(nop (change-state! …))` a
    // unit-valued top-level run in the hyperon test scripts.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "nop",
                descriptor = "(Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM),
            false
        )
    )
    // Mutable state: new-state creates a cell, bind! names it via a token, get-state reads,
    // change-state! writes. Value/token args are ATOM (stored/looked-up as data, unreduced);
    // bind!'s value arg is ANY so `(bind! s (new-state x))` reduces the new-state first.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "new-state",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "bind!",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ANY, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "get-state",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "change-state!",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            // The new value is ANY, so it is REDUCED before being stored: hyperon's signature is
            // `(-> (StateMonad $t) $t (StateMonad $t))` — the second parameter is a value, not a
            // meta-Atom. With ATOM here `(change-state! $x (+ (get-state $x) 1))` stored the
            // unevaluated expression, which (since it mentions `$x`) made the cell contain itself.
            ArrowType(GroundedType.ATOM, GroundedType.ANY, GroundedType.ATOM),
            false
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "collapse",
                descriptor = "(Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM),
            false
        )
    )
    // msort — sort a tuple (typically the result of `collapse`) into a canonical order so a
    // nondeterministic bag can be compared against a literal. ANY param → the argument is
    // reduced (the `collapse` runs) before sorting; single-valued (returns one Atom).
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "msort",
                descriptor = "(Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM),
            false
        )
    )
    // once — non-determinism barrier keeping only the first result (see CanonicalFormRewriter
    // / MarkMultivaluedFunctionsRewriter BARRIER_FUNCTIONS, which route the full bag here).
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/Convert",
                name = "once",
                descriptor = "(Ljava/lang/Object;)Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
            ),
            ArrowType(GroundedType.ANY, GroundedType.ATOM),
            false
        )
    )
    // eval — the JIT-eval primitive. `(eval (quote EXPR))` hands the inert EXPR to
    // JettaJit, which compiles+loads+invokes it at call time and returns the result bag.
    // The argument must arrive as DATA (hence `quote`): the param type Atom keeps the
    // resolver from reducing it at the call site. Multivalued — returns a List<Atom>.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/functions/JettaJit",
                name = "eval",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)),
            true
        )
    )
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "matchEval",
                descriptor = "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Expression;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/runtime/functions/JettaFunction;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ATOM, GroundedType.ATOM, ArrowType(GroundedType.ATOM, SeqType(GroundedType.ATOM)), SeqType(GroundedType.ATOM)),
            true
        )
    )
    // `letMatch` — the runtime of MeTTa's Form-2 pattern-`let` (`(let (List $t) VALUE BODY)`),
    // emitted by LetRewriter. The pattern (arg 0) is ATOM so it arrives as unreduced data
    // (`$t` a Variable); the value (arg 1) is ANY so its RHS is evaluated (e.g. `get-type`); the
    // body (arg 2) is a lambda over the pattern's variables. Multivalued (`SeqType`) so it
    // composes with the surrounding result-bag semantics. See JettaProgram.letMatch.
    context.addSystemFunction(
        ResolvedSymbol(
            JvmMethod(
                owner = "net/singularity/jetta/runtime/JettaProgram",
                name = "letMatch",
                descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Atom;Ljava/lang/Object;Lnet/singularity/jetta/runtime/functions/JettaFunction;)Ljava/util/List;"
            ),
            ArrowType(GroundedType.ATOM, GroundedType.ANY, ArrowType(GroundedType.ATOM, GroundedType.ATOM), SeqType(GroundedType.ATOM)),
            true
        )
    )
}