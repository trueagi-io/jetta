; A grounded operation's answer is not reduced again: `cons-atom` builds `(+ 1 2)` and leaves it.
; A returned symbol is not called; a returned call or a call-headed tuple element is. Measured on metta-repl 0.2.10.

!(assertEqualToResult (cons-atom + (1 2)) ((+ 1 2)))

(= (nm) plus-one)
(= (plus-one) 1)
!(assertEqualToResult (nm) (plus-one))

(= (dbl $x) (* $x 2))
(= (mkcall $f) ($f 5))
!(assertEqualToResult (mkcall dbl) (10))
!(assertEqualToResult (car-atom ((dbl 3) b)) (6))
(= (mkexpr) (dbl 4))
!(assertEqualToResult (mkexpr) (8))
