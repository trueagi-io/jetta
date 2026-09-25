; A grounded operator passed as DATA inside a function body — `(cons-atom + (1 2))` — is the
; symbol, as it is at top level. The body answers the built term, not reduced. Measured on metta-repl 0.2.10.

(= (mk) (cons-atom + (1 2)))
!(assertEqualToResult (mk) ((+ 1 2)))
