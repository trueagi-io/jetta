(Evaluation (philosopher Plato))
(Evaluation (likes-to-wrestle Plato))

; An implication rule
(Implication
   (And (Evaluation (philosopher $x))
        (Evaluation (likes-to-wrestle $x)))
   (Evaluation (human $x)))

; Another implication rule
(Implication
   (Evaluation (human $x))
   (Evaluation (mortal $x)))

; Deduction case when the desired evaluation is present in
; the knowledge base
(= (deduce (Evaluation ($P $x)))
   (match &self (Evaluation ($P $x)) T))

; Deduction case when the desired evaluation is the result
; of an implication, which implies a recursion
(= (deduce (Evaluation ($P $x)))
   (match &self
     (Implication $a (Evaluation ($P $x)))
     (deduce $a)))

; Deduction case for generic "And" expressions;
; also recursive
(= (deduce (And $a $b))
   (And (deduce $a) (deduce $b)))

; True & True = True
(= (And T T) T)

(= (ift T $then) $then)
(println (ift (deduce (Evaluation (mortal $x))) $x)) ; should be Plato
