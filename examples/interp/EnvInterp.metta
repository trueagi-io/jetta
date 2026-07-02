; An environment-based meta-circular-style interpreter for a tiny expression
; language, written in MeTTa and compiled to JVM bytecode by JeTTa.
;
; Expression language (all data — tagged constructors):
;   (Lit n)          integer literal
;   (Var x)          variable reference, resolved against the environment
;   (Add a b)        a + b
;   (Mul a b)        a * b
;   (Let x e body)   evaluate e, bind x to the result, evaluate body under it
;
; Environment = an association list of (Bind name value), i.e.
;   (Cons (Bind x 10) (Cons (Bind y 2) Nil))
;
; This exercises the pieces that make JeTTa a *compiler* rather than an
; interpreter of an interpreter:
;   - guarded recursion over a destructured list (`lookup`),
;   - a value-producing `if` whose two arms are heterogeneous (a scalar and a
;     recursive multivalued call) — homogenized to a single List<Int>,
;   - arithmetic composed over multivalued sub-evaluations (`(+ (ev a) (ev b))`),
;   - lexical scoping via `Let`, threading a value (not a thunk) into the env.
;
; Every `ev` clause has a destructuring head, so `ev` compiles to a Match and is
; multivalued (physically List<Int>); the driver `assertEqual` compares the whole
; result bag against the expected value.

; --- environment lookup: first matching binding wins ---
(: lookup (-> Atom Atom Int))
(= (lookup $key (Cons (Bind $k $v) $rest))
   (if (== $key $k) $v (lookup $key $rest)))

; --- the evaluator ---
(: ev (-> Atom Atom Int))
(= (ev (Lit $n) $env) $n)
(= (ev (Var $x) $env) (lookup $x $env))
(= (ev (Add $a $b) $env) (+ (ev $a $env) (ev $b $env)))
(= (ev (Mul $a $b) $env) (* (ev $a $env) (ev $b $env)))
; Let: evaluate `e` in the current env (a direct argument, so its value — not an
; unevaluated expression — is threaded into the extended environment), then
; evaluate the body. `bindEval` keeps the eval out of the data-constructor
; position so a value, not a thunk, lands in the binding.
(= (ev (Let $x $e $body) $env) (bindEval $x (ev $e $env) $body $env))

(: bindEval (-> Atom Int Atom Atom Int))
(= (bindEval $x $val $body $env) (ev $body (Cons (Bind $x $val) $env)))

; --- checks ---
; A constant folds to itself.
!(assertEqual (ev (Lit 42) Nil) 42)
; Arithmetic: 3 + (4 * 5) = 23
!(assertEqual (ev (Add (Lit 3) (Mul (Lit 4) (Lit 5))) Nil) 23)
; Variable lookup from a pre-built environment: x=7, y=2, (x + y) = 9
!(assertEqual (ev (Add (Var x) (Var y)) (Cons (Bind x 7) (Cons (Bind y 2) Nil))) 9)
; Let with lexical scope: (let x = 10 in x + 5) = 15
!(assertEqual (ev (Let x (Lit 10) (Add (Var x) (Lit 5))) Nil) 15)
; Nested Let: (let a = 3 in (let b = 4 in a + b)) = 7
!(assertEqual (ev (Let a (Lit 3) (Let b (Lit 4) (Add (Var a) (Var b)))) Nil) 7)
; Let over an expression + shadowing an outer binding:
;   env {n = 2}; (let n = (n + 10) in n * n) = 144
!(assertEqual (ev (Let n (Add (Var n) (Lit 10)) (Mul (Var n) (Var n))) (Cons (Bind n 2) Nil)) 144)
