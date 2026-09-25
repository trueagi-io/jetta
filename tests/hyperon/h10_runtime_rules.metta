; A rule added at run time answers calls to its head, including through `metta`. Measured on metta-repl 0.2.10.

!(add-atom &self (= (g2 $x) (* $x 3)))
!(assertEqualToResult (g2 2) (6))
!(assertEqualToResult (metta (g2 2) %Undefined% &self) (6))
