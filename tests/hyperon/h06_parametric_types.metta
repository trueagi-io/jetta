; A parameterised type is CHECKED: `(List $t)` fixes `$t` across a constructor's arguments. Measured on metta-repl 0.2.10.

(: List (-> Type Type))
(: Nil (List $t))
(: Cons (-> $t (List $t) (List $t)))

!(assertEqualToResult (get-type (Cons 1 (Cons 2 Nil))) ((List Number)))
!(assertEqualToResult (get-type (Cons 1 (Cons "a" Nil))) ())
!(assertEqualToResult
   (collapse (Cons 1 (Cons "a" Nil)))
   (((Error (Cons 1 (Cons "a" Nil)) (BadArgType 2 (List Number) (List String))))))

(: len (-> (List $t) Number))
(= (len Nil) 0)
(= (len (Cons $x $xs)) (+ 1 (len $xs)))
!(assertEqualToResult (len (Cons 1 (Cons 2 Nil))) (2))
!(assertEqualToResult (get-type (len (Cons 1 (Cons "a" Nil)))) ())
