; Rules are space atoms, also for a head with a rule in the file: an added rule joins the answers,
; a removed one stops answering. Measured on metta-repl 0.2.10.

(= (color) red)
!(add-atom &self (= (color) blue))
!(assertEqualToResult (collapse (color)) ((red blue)))

(= (function1) OK)
!(remove-atom &self (= (function1) OK))
!(assertEqualToResult (function1) ((function1)))
!(add-atom &self (= (function1) (OK)))
!(assertEqualToResult (function1) ((OK)))
