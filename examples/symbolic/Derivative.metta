; Symbolic differentiation — the SICP classic — by pure term rewriting, then
; numeric evaluation of the derivative. No eval/interpreter: `d` and `ev` are
; ordinary compiled functions; structural pattern matching on the clause heads
; does the work.
;
; Expression language:
;   x            the variable
;   (Lit n)      an integer constant
;   (Plus a b)   a + b
;   (Mul a b)    a * b
;
; Constants are tagged (Lit n) and the variable is the bare symbol x, so the leaf
; clauses never overlap — differentiation is deterministic (one result).

; --- d/dx : Atom -> Atom (a derivative expression, as data) ---
(= (d x) (Lit 1))
(= (d (Lit $n)) (Lit 0))
(= (d (Plus $a $b)) (Plus (d $a) (d $b)))
(= (d (Mul $a $b)) (Plus (Mul (d $a) $b) (Mul $a (d $b))))

; --- ev : evaluate an expression with the variable x bound to 3 ---
(: ev (-> Atom Int))
(= (ev x) 3)
(= (ev (Lit $n)) $n)
(= (ev (Plus $a $b)) (+ (ev $a) (ev $b)))
(= (ev (Mul $a $b)) (* (ev $a) (ev $b)))

; Show the derivative of (x + x*x) as a symbolic expression:
;   => (Plus (Lit 1) (Plus (Mul (Lit 1) x) (Mul x (Lit 1))))   [i.e. 1 + (1*x + x*1)]
!(println! (d (Plus x (Mul x x))))

; Show the derivative of (x * x * x):
;   => (Plus (Mul (Lit 1) (Mul x x)) (Mul x (Plus (Mul (Lit 1) x) (Mul x (Lit 1)))))
!(println! (d (Mul x (Mul x x))))

; Verify the derivatives numerically at x = 3:
;   d/dx (x + x*x) = 1 + 2x  -> 7
!(assertEqual (ev (d (Plus x (Mul x x)))) 7)
;   d/dx (x*x*x)   = 3x^2    -> 27
!(assertEqual (ev (d (Mul x (Mul x x)))) 27)
