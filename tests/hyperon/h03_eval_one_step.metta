; `eval` performs exactly ONE rewrite, and its answer is not reduced further — minimal MeTTa's
; stepping, which `function`/`return` bracket. That holds for a rule added at run time too. Measured on metta-repl 0.2.10.

(= (ff) (gg))
(= (gg) 42)
!(assertEqualToResult (eval (ff)) ((gg)))
!(assertEqualToResult (function (chain (eval (ff)) $r (return $r))) ((gg)))

!(add-atom &self (= (g2 $x) (* $x 3)))
!(assertEqualToResult (eval (g2 2)) ((* 2 3)))
