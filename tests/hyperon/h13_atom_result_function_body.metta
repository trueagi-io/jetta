; A function declaring its result `Atom` whose body is minimal MeTTa: `function` runs to its
; `return`, and the returned atom is NOT reduced further. Measured on metta-repl 0.2.10.

(: stepped (-> Atom Atom))
(= (stepped $x) (function (return (+ $x 1))))
!(assertEqualToResult (stepped 2) ((+ 2 1)))
