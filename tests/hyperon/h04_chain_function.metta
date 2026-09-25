; `chain` binds its reduced value; `function` over an immediate `return` is that value. Measured on metta-repl 0.2.10.

(= (ff) (gg))
(= (gg) 42)
!(assertEqualToResult (function (return 5)) (5))
!(assertEqualToResult (chain (ff) $x $x) (42))
