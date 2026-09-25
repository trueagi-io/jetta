; A `let` template IS reduced after substitution, so a built term bound there runs:
; `(let $x (cons-atom + (1 2)) $x)` is 3, while `(cons-atom + (1 2))` alone is `(+ 1 2)`. Measured on metta-repl 0.2.10.

!(assertEqualToResult (let $x (cons-atom + (1 2)) $x) (3))
