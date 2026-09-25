; `quote` is an ordinary constructor whose argument is not evaluated: the answer KEEPS the
; `quote` wrapper. Measured on metta-repl 0.2.10.

!(assertEqualToResult (quote (+ 1 2)) ((quote (+ 1 2))))
!(assertEqualToResult (let $x (quote (+ 1 2)) $x) ((quote (+ 1 2))))
