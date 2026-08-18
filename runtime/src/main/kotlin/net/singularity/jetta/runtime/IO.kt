package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression

object IO {
    /**
     * `println!` — the reference interpreter's name, bang included, so the method name IS the MeTTa
     * name (`Context.addSystemFunction` keys system functions by it, the same convention as
     * `import!` / `add-atom` / `car-atom`).
     *
     * It used to be registered as plain `println`, which meant every hyperon-written program that
     * prints was SILENT: `println!` resolved to nothing, so the call compiled as an inert data
     * constructor — no output, no diagnostic, and an exit code of 0.
     *
     * Returns the empty expression `()` rather than nothing, which is what the reference stdlib
     * relies on — it sequences prints as `(let () (println! …) NEXT)`, unifying the result against
     * `()`. A `void` return also had nothing to give a value position: an argument or `let` binding
     * over a print reached `boxIfNeeded` as `Unit` and crashed the compiler.
     */
    @JvmStatic
    fun `println!`(value: Any): Atom {
        kotlin.io.println(value)
        return Expression(emptyList())
    }
}
