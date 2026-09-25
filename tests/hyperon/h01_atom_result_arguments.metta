; A parameter declared `Atom` receives its argument unevaluated, and a function whose result is
; declared `Atom` hands that argument back as it came. Measured on metta-repl 0.2.10.

(: q (-> Atom Atom))
(= (q $x) $x)
!(assertEqualToResult (q (+ 1 2)) ((+ 1 2)))

(= (f $x) (* $x 10))
(: myPair (-> Atom Atom Atom))
(= (myPair $a $b) (Pair $a $b))
!(assertEqualToResult (myPair (f 1) (f 2)) ((Pair (f 1) (f 2))))
