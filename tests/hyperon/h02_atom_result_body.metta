; The BODY of a function whose result is declared `Atom` is substituted and not reduced: its own
; calls stay terms, not only the arguments it was handed. Measured on metta-repl 0.2.10.

(: q3 (-> Atom Atom))
(= (q3 $x) (foo (+ 1 1) $x))
!(assertEqualToResult (q3 (+ 1 2)) ((foo (+ 1 1) (+ 1 2))))

(= (dbl $x) (* $x 2))
(: mkq (-> Atom))
(= (mkq) (dbl 4))
!(assertEqualToResult (mkq) ((dbl 4)))
