; A term held by a meta-typed (`Expression`) parameter is evaluated where the body uses it — with
; ALL of its results, not only the first. Measured on metta-repl 0.2.10.

(: inc (-> Expression Number))
(= (inc $x) (+ $x 1))
!(assertEqualToResult (inc (superpose (1 2))) (2 3))

(: w (-> Expression Expression))
(= (w $a) (42 $a))
!(assertEqualToResult (w (superpose (1 2))) ((42 1) (42 2)))
