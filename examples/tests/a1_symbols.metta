;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; `match` searches for all expressions corresponding to
; the given pattern and produces the output pattern.
; It doesn't search in subexpressions.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Some expressions to be matched
((leaf1 leaf2) leaf3)
(((leaf0 leaf1) leaf2) leaf3)
; This one contains `((leaf1 leaf2) leaf3)` as a subexpression
; and thus will not be matched
(top ((leaf1 leaf2) leaf3))
; `assertEqualToResult` checks all the results of the first
; expression; it doesn't evaluate the second expression,
; which is treated as a set of expected results.
!(assertEqualToResult
  (match &self (($x leaf2) leaf3) $x)
  '[leaf1
   (leaf0 leaf1)])